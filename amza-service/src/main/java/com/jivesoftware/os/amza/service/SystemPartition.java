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
package com.jivesoftware.os.amza.service;

import com.google.common.base.Preconditions;
import com.jivesoftware.os.amza.service.storage.SystemWALStorage;
import com.jivesoftware.os.amza.shared.AckWaters;
import com.jivesoftware.os.amza.shared.AmzaPartitionAPI;
import com.jivesoftware.os.amza.shared.FailedToAchieveQuorumException;
import com.jivesoftware.os.amza.shared.TimestampedValue;
import com.jivesoftware.os.amza.shared.partition.HighestPartitionTx;
import com.jivesoftware.os.amza.shared.partition.PartitionName;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import com.jivesoftware.os.amza.shared.scan.Commitable;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.scan.Scan;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.stream.TimestampKeyValueStream;
import com.jivesoftware.os.amza.shared.stream.UnprefixedWALKeys;
import com.jivesoftware.os.amza.shared.take.HighwaterStorage;
import com.jivesoftware.os.amza.shared.take.Highwaters;
import com.jivesoftware.os.amza.shared.take.TakeResult;
import com.jivesoftware.os.amza.shared.wal.WALHighwater;
import com.jivesoftware.os.amza.shared.wal.WALUpdated;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Set;

public class SystemPartition implements AmzaPartitionAPI {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AmzaStats amzaStats;
    private final OrderIdProvider orderIdProvider;
    private final WALUpdated walUpdated;
    private final RingMember ringMember;
    private final VersionedPartitionName versionedPartitionName;
    private final SystemWALStorage systemWALStorage;
    private final HighwaterStorage systemHighwaterStorage;
    private final AckWaters ackWaters;
    private final AmzaRingStoreReader ringReader;

    public SystemPartition(AmzaStats amzaStats,
        OrderIdProvider orderIdProvider,
        WALUpdated walUpdated,
        RingMember ringMember,
        PartitionName partitionName,
        SystemWALStorage systemWALStorage,
        HighwaterStorage systemHighwaterStorage,
        AckWaters ackWaters,
        AmzaRingStoreReader ringReader) {

        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.walUpdated = walUpdated;
        this.ringMember = ringMember;
        this.versionedPartitionName = new VersionedPartitionName(partitionName, 0);
        this.systemWALStorage = systemWALStorage;
        this.systemHighwaterStorage = systemHighwaterStorage;
        this.ackWaters = ackWaters;
        this.ringReader = ringReader;
    }

    public PartitionName getPartitionName() {
        return versionedPartitionName.getPartitionName();
    }

    @Override
    public void commit(byte[] prefix,
        Commitable updates,
        int takeQuorum,
        long timeoutInMillis) throws Exception {

        long timestampId = orderIdProvider.nextId();
        RowsChanged commit = systemWALStorage.update(versionedPartitionName, prefix,
            (highwaters, scan) ->
                updates.commitable(highwaters, (rowTxId, key, value, valueTimestamp, valueTombstone) -> {
                    long timestamp = valueTimestamp > 0 ? valueTimestamp : timestampId;
                    return scan.row(rowTxId, key, value, timestamp, valueTombstone);
                }),
            walUpdated);
        amzaStats.direct(versionedPartitionName.getPartitionName(), commit.getApply().size(), commit.getOldestRowTxId());

        Set<RingMember> ringMembers = ringReader.getNeighboringRingMembers(AmzaRingReader.SYSTEM_RING);

        if (takeQuorum > 0) {
            if (ringMembers.size() < takeQuorum) {
                throw new FailedToAchieveQuorumException("There are an insufficent number of nodes to achieve desired take quorum:" + takeQuorum);
            } else {
                LOG.debug("Awaiting quorum for {} ms", timeoutInMillis);
                int takenBy = ackWaters.await(versionedPartitionName, commit.getLargestCommittedTxId(), ringMembers, takeQuorum, timeoutInMillis);
                if (takenBy < takeQuorum) {
                    throw new FailedToAchieveQuorumException("Timed out attempting to achieve desired take quorum:" + takeQuorum + " got:" + takenBy);
                }
            }
        }
    }

    @Override
    public boolean get(byte[] prefix, UnprefixedWALKeys keys, TimestampKeyValueStream valuesStream) throws Exception {
        return systemWALStorage.get(versionedPartitionName, prefix, keys, valuesStream);
    }

    @Override
    public void scan(byte[] fromPrefix, byte[] fromKey, byte[] toPrefix, byte[] toKey, Scan<TimestampedValue> scan) throws Exception {
        if (fromKey == null && toKey == null) {
            systemWALStorage.rowScan(versionedPartitionName, (prefix, key, value, valueTimestamp, valueTombstone) ->
                valueTombstone || scan.row(-1, prefix, key, new TimestampedValue(valueTimestamp, value)));
        } else {
            systemWALStorage.rangeScan(versionedPartitionName,
                fromPrefix,
                fromKey == null ? new byte[0] : fromKey,
                toPrefix,
                toKey,
                (prefix, key, value, valueTimestamp, valueTombstone) ->
                    valueTombstone || scan.row(-1, prefix, key, new TimestampedValue(valueTimestamp, value)));
        }
    }

    @Override
    public TakeResult takeFromTransactionId(long transactionId, Highwaters highwaters, Scan<TimestampedValue> scan) throws Exception {
        return takeFromTransactionIdInternal(null, transactionId, highwaters, scan);
    }

    @Override
    public TakeResult takeFromTransactionId(byte[] prefix, long transactionId, Highwaters highwaters, Scan<TimestampedValue> scan) throws Exception {
        Preconditions.checkNotNull(prefix, "Must specify a prefix");
        return takeFromTransactionIdInternal(prefix, transactionId, highwaters, scan);
    }

    private TakeResult takeFromTransactionIdInternal(byte[] takePrefix,
        long transactionId,
        Highwaters highwaters,
        Scan<TimestampedValue> scan) throws Exception {

        long[] lastTxId = { -1 };
        boolean[] done = { false };
        WALHighwater partitionHighwater = systemHighwaterStorage.getPartitionHighwater(versionedPartitionName);
        boolean tookToEnd = systemWALStorage.takeFromTransactionId(versionedPartitionName, takePrefix, transactionId, highwaters,
            (rowTxId, prefix, key, value, valueTimestamp, valueTombstone) -> {
                if (valueTombstone) {
                    return true;
                }

                if (done[0] && rowTxId > lastTxId[0]) {
                    return false;
                }

                done[0] |= !scan.row(rowTxId, prefix, key, new TimestampedValue(valueTimestamp, value));
                if (rowTxId > lastTxId[0]) {
                    lastTxId[0] = rowTxId;
                }
                return true;
            });
        return new TakeResult(ringMember, lastTxId[0], tookToEnd ? partitionHighwater : null);
    }

    @Override
    public long count() throws Exception {
        return systemWALStorage.count(versionedPartitionName);
    }

    @Override
    public void highestTxId(HighestPartitionTx highestPartitionTx) throws Exception {
        systemWALStorage.highestPartitionTxIds(highestPartitionTx);
    }

}
