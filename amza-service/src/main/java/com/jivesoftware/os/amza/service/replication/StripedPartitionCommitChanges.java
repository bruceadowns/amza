package com.jivesoftware.os.amza.service.replication;

import com.google.common.base.Optional;
import com.jivesoftware.os.amza.api.partition.Durability;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.wal.WALUpdated;

/**
 * @author jonathan.colt
 */
public class StripedPartitionCommitChanges implements CommitChanges {

    private final PartitionStripeProvider partitionStripeProvider;
    private final boolean hardFlush;
    private final WALUpdated walUpdated;

    public StripedPartitionCommitChanges(PartitionStripeProvider partitionStripeProvider,
        boolean hardFlush,
        WALUpdated walUpdated) {

        this.partitionStripeProvider = partitionStripeProvider;
        this.hardFlush = hardFlush;
        this.walUpdated = walUpdated;
    }

    @Override
    public void commit(VersionedPartitionName versionedPartitionName, CommitTx commitTx) throws Exception {
        PartitionName partitionName = versionedPartitionName.getPartitionName();
        partitionStripeProvider.txPartition(partitionName,
            (txPartitionStripe, highwaterStorage, versionedAquarium) -> {
                return commitTx.tx(highwaterStorage,
                    versionedAquarium,
                    (prefix, commitable) -> {
                        return txPartitionStripe.tx((deltaIndex, stripeIndex, partitionStripe) -> {
                            return partitionStripe.commit(highwaterStorage,
                                versionedAquarium,
                                false,
                                Optional.of(versionedPartitionName.getPartitionVersion()),
                                false,
                                prefix,
                                commitable,
                                walUpdated);
                        });
                    });
            });
        partitionStripeProvider.flush(partitionName, hardFlush ? Durability.fsync_always : Durability.fsync_async, 0);
    }

    @Override
    public String toString() {
        return "StripedPartitionCommitChanges{" + "partitionStripeProvider=" + partitionStripeProvider + '}';
    }

}
