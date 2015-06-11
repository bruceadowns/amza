package com.jivesoftware.os.amza.shared;

import com.google.common.base.Optional;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author jonathan.colt
 */
public class AckWaters {

    private final AwaitNotify<VersionedPartitionName> awaitNotify;
    private final ConcurrentHashMap<RingMember, ConcurrentHashMap<VersionedPartitionName, Long>> ackWaters = new ConcurrentHashMap<>();

    public AckWaters(int stripingLevel) {
        this.awaitNotify = new AwaitNotify<>(stripingLevel);
    }

    public void set(RingMember ringMember, VersionedPartitionName partitionName, Long txId) throws Exception {
        ConcurrentHashMap<VersionedPartitionName, Long> partitionTxIds = ackWaters.computeIfAbsent(ringMember, (t) -> new ConcurrentHashMap<>());
        awaitNotify.notifyChange(partitionName, () -> {
            long merge = partitionTxIds.merge(partitionName, txId, Math::max);
            return (merge == txId);
        });
    }

    public Long get(RingMember ringMember, VersionedPartitionName partitionName) {
        ConcurrentHashMap<VersionedPartitionName, Long> partitionTxIds = ackWaters.get(ringMember);
        if (partitionTxIds == null) {
            return null;
        }
        return partitionTxIds.get(partitionName);
    }

    public int await(VersionedPartitionName partitionName,
        long desiredTxId,
        Collection<RingMember> takeRingMembers,
        int desiredTakeQuorum,
        long toMillis) throws Exception {

        RingMember[] ringMembers = takeRingMembers.toArray(new RingMember[takeRingMembers.size()]);
        int[] passed = new int[1];
        return awaitNotify.awaitChange(partitionName, () -> {
            for (int i = 0; i < ringMembers.length; i++) {
                RingMember ringMember = ringMembers[i];
                if (ringMember == null) {
                    continue;
                }
                Long txId = get(ringMember, partitionName);
                if (txId != null && txId >= desiredTxId) {
                    passed[0]++;
                    ringMembers[i] = null;
                }
                if (passed[0] >= desiredTakeQuorum) {
                    return Optional.of(passed[0]);
                }
            }
            return null;
        }, toMillis);
    }
}
