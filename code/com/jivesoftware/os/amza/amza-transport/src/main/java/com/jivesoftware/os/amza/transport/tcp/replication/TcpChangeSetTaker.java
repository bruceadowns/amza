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
package com.jivesoftware.os.amza.transport.tcp.replication;

import com.jivesoftware.os.amza.shared.ChangeSetTaker;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.TableName;
import com.jivesoftware.os.amza.shared.TransactionSet;
import com.jivesoftware.os.amza.shared.TransactionSetStream;
import com.jivesoftware.os.amza.transport.tcp.replication.protocol.ChangeSetRequestPayload;
import com.jivesoftware.os.amza.transport.tcp.replication.protocol.ChangeSetResponsePayload;
import com.jivesoftware.os.amza.transport.tcp.replication.protocol.IndexReplicationProtocol;
import com.jivesoftware.os.amza.transport.tcp.replication.shared.Message;
import com.jivesoftware.os.amza.transport.tcp.replication.shared.TcpClient;
import com.jivesoftware.os.amza.transport.tcp.replication.shared.TcpClientProvider;
import com.jivesoftware.os.jive.utils.base.interfaces.CallbackStream;
import com.jivesoftware.os.jive.utils.logger.MetricLogger;
import com.jivesoftware.os.jive.utils.logger.MetricLoggerFactory;

/**
 *
 */
public class TcpChangeSetTaker implements ChangeSetTaker {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final TcpClientProvider clientProvider;
    private final IndexReplicationProtocol indexReplicationProtocol;

    public TcpChangeSetTaker(TcpClientProvider clientProvider, IndexReplicationProtocol indexReplicationProtocol) {
        this.clientProvider = clientProvider;
        this.indexReplicationProtocol = indexReplicationProtocol;
    }

    @Override
    public <K, V> void take(RingHost ringHost, TableName<K, V> tableName,
            long transationId, final TransactionSetStream transactionSetStream) throws Exception {
        TcpClient client = clientProvider.getClientForHost(ringHost);
        try {
            ChangeSetRequestPayload requestPayload = new ChangeSetRequestPayload(tableName, transationId);
            Message message = new Message(indexReplicationProtocol.nextInteractionId(),
                    indexReplicationProtocol.OPCODE_REQUEST_CHANGESET, true, requestPayload);

            client.sendMessage(message);

            CallbackStream<TransactionSet> messageStream = new CallbackStream<TransactionSet>() {
                @Override
                public TransactionSet callback(TransactionSet transactionSet) throws Exception {
                    if (transactionSet != null) {
                        return transactionSetStream.stream(transactionSet) ? transactionSet : null;
                    } else {
                        return null;
                    }
                }
            };

            Message entry = null;
            boolean streamingResults = true;

            while ((entry = client.receiveMessage()) != null) {

                if (entry.getOpCode() == indexReplicationProtocol.OPCODE_ERROR) {
                    String errorMsg = entry.getPayload();
                    throw new Exception(errorMsg);
                }

                //if we aren't dispatching results anymore, we still need to loop over the input to drain the socket
                if (streamingResults) {
                    try {
                        ChangeSetResponsePayload payload = entry.getPayload();
                        TransactionSet returned = messageStream.callback(payload.getTransactionSet());
                        if (returned == null) {
                            streamingResults = false;
                        }
                    } catch (Exception ex) {
                        LOG.error("Error streaming in transaction set: " + entry, ex);
                    }
                }

                if (entry.isLastInSequence()) {
                    break;
                }
            }
        } finally {
            clientProvider.returnClient(client);
        }
    }
}
