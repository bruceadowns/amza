/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.transport.tcp.replication.protocol;

import com.jivesoftware.os.amza.shared.AmzaInstance;
import com.jivesoftware.os.amza.shared.TableDelta;
import com.jivesoftware.os.amza.shared.TableIndex;
import com.jivesoftware.os.amza.shared.TableIndexKey;
import com.jivesoftware.os.amza.shared.TimestampedValue;
import com.jivesoftware.os.amza.shared.TransactionSet;
import com.jivesoftware.os.amza.shared.TransactionSetStream;
import com.jivesoftware.os.amza.transport.tcp.replication.shared.ApplicationProtocol;
import com.jivesoftware.os.amza.transport.tcp.replication.shared.Message;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.apache.commons.lang.mutable.MutableLong;

/**
 *
 */
public class IndexReplicationProtocol implements ApplicationProtocol {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    public final int OPCODE_PUSH_CHANGESET = 3;
    public final int OPCODE_REQUEST_CHANGESET = 5;
    public final int OPCODE_RESPOND_CHANGESET = 7;
    public final int OPCODE_ERROR = 9;
    public final int OPCODE_OK = 11;
    private final Map<Integer, Class<? extends Serializable>> payloadRegistry;
    private final AmzaInstance amzaInstance;
    private final OrderIdProvider idProvider;

    public IndexReplicationProtocol(AmzaInstance amzaInstance, OrderIdProvider idProvider) {
        this.amzaInstance = amzaInstance;
        this.idProvider = idProvider;

        Map<Integer, Class<? extends Serializable>> map = new HashMap<>();
        map.put(OPCODE_PUSH_CHANGESET, SendChangeSetPayload.class);
        map.put(OPCODE_REQUEST_CHANGESET, ChangeSetRequestPayload.class);
        map.put(OPCODE_RESPOND_CHANGESET, ChangeSetResponsePayload.class);
        map.put(OPCODE_ERROR, String.class);
        payloadRegistry = Collections.unmodifiableMap(map);
    }

    @Override
    public Message handleRequest(Message request) {
        switch (request.getOpCode()) {
            case OPCODE_PUSH_CHANGESET:
                return handleChangeSetPush(request);
            case OPCODE_REQUEST_CHANGESET:
                return handleChangeSetRequest(request);
            default:
                throw new IllegalArgumentException("Unexpected opcode: " + request.getOpCode());
        }
    }

    private Message handleChangeSetPush(Message request) {
        LOG.trace("Received change set push {}", request);

        try {
            SendChangeSetPayload payload = request.getPayload();
            amzaInstance.changes(payload.getMapName(), changeSetToPartionDelta(payload));

            Message response = new Message(request.getInteractionId(), OPCODE_OK, true);
            LOG.trace("Returning from change set push {}", response);
            return response;

        } catch (Exception x) {
            LOG.warn("Failed to apply changeset: " + request, x);
            ExceptionPayload exceptionPayload = new ExceptionPayload(x.toString());
            return new Message(request.getInteractionId(), OPCODE_ERROR, true, exceptionPayload);
        }
    }

    private TableDelta changeSetToPartionDelta(SendChangeSetPayload changeSet) throws Exception {
        final ConcurrentNavigableMap<TableIndexKey, TimestampedValue> changes = new ConcurrentSkipListMap<>();
        TableIndex tableIndex = changeSet.getChanges();
        tableIndex.entrySet(new TableIndex.EntryStream<RuntimeException>() {

            @Override
            public boolean stream(TableIndexKey key, TimestampedValue value) {
                changes.put(key, value);
                return true;
            }
        });
        return new TableDelta(changes, new TreeMap(), null);
    }

    //TODO figure out how to stream this out in stages vi calls to consumeSequence.
    private Message handleChangeSetRequest(Message request) {
        LOG.trace("Received change set request {}", request);

        try {

            ChangeSetRequestPayload changeSet = request.getPayload();

            final ConcurrentNavigableMap<TableIndexKey, TimestampedValue> changes = new ConcurrentSkipListMap<>();

            final MutableLong highestTransactionId = new MutableLong();

            amzaInstance.takeTableChanges(changeSet.getMapName(), changeSet.getHighestTransactionId(), new TransactionSetStream() {
                @Override
                public boolean stream(TransactionSet took) throws Exception {
                    changes.putAll(took.getChanges());

                    if (took.getHighestTransactionId() > highestTransactionId.longValue()) {
                        highestTransactionId.setValue(took.getHighestTransactionId());
                    }
                    return true;
                }
            });

            ChangeSetResponsePayload response = new ChangeSetResponsePayload(new TransactionSet(highestTransactionId.longValue(), changes));

            Message responseMsg = new Message(request.getInteractionId(), OPCODE_RESPOND_CHANGESET, true, response);
            LOG.trace("Returning from change set request {}", responseMsg);
            return responseMsg;

        } catch (Exception x) {
            LOG.warn("Failed to apply changeset: " + request, x);
            ExceptionPayload exceptionPayload = new ExceptionPayload(x.toString());
            return new Message(request.getInteractionId(), OPCODE_ERROR, true, exceptionPayload);
        }
    }

    @Override
    public Message consumeSequence(long interactionId) {
        throw new UnsupportedOperationException("Sequences not supported yet - currently sending whole changesets");
    }

    @Override
    public Class<? extends Serializable> getOperationPayloadClass(int opCode) {
        return payloadRegistry.get(opCode);
    }

    @Override
    public long nextInteractionId() {
        return idProvider.nextId();
    }
}
