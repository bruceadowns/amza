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

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedState;
import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.aquarium.Liveliness;
import com.jivesoftware.os.amza.aquarium.State;
import com.jivesoftware.os.amza.aquarium.Waterline;
import com.jivesoftware.os.amza.service.replication.AmzaAquariumProvider;
import com.jivesoftware.os.amza.service.replication.PartitionComposter;
import com.jivesoftware.os.amza.service.replication.PartitionStateStorage;
import com.jivesoftware.os.amza.service.replication.PartitionStripe;
import com.jivesoftware.os.amza.service.replication.PartitionStripeProvider;
import com.jivesoftware.os.amza.service.replication.PartitionTombstoneCompactor;
import com.jivesoftware.os.amza.service.replication.RowChangeTaker;
import com.jivesoftware.os.amza.service.storage.PartitionCreator;
import com.jivesoftware.os.amza.service.storage.PartitionIndex;
import com.jivesoftware.os.amza.service.storage.PartitionStore;
import com.jivesoftware.os.amza.service.storage.SystemWALStorage;
import com.jivesoftware.os.amza.shared.AckWaters;
import com.jivesoftware.os.amza.shared.AmzaInstance;
import com.jivesoftware.os.amza.shared.ChunkWriteable;
import com.jivesoftware.os.amza.shared.Partition;
import com.jivesoftware.os.amza.shared.PartitionProvider;
import com.jivesoftware.os.amza.shared.partition.PartitionProperties;
import com.jivesoftware.os.amza.shared.partition.RemoteVersionedState;
import com.jivesoftware.os.amza.shared.partition.TxHighestPartitionTx;
import com.jivesoftware.os.amza.shared.scan.RowChanges;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.take.AvailableRowsTaker.AvailableStream;
import com.jivesoftware.os.amza.shared.take.HighwaterStorage;
import com.jivesoftware.os.amza.shared.take.TakeCoordinator;
import com.jivesoftware.os.amza.shared.wal.WALUpdated;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.commons.lang.mutable.MutableLong;

/**
 * Amza pronounced (AH m z ah )
 * Sanskrit word meaning partition / share.
 */
public class AmzaService implements AmzaInstance, PartitionProvider {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final TxHighestPartitionTx<Long> txHighestPartitionTx = (partitionName, highestPartitionTx)
        -> getPartition(partitionName).highestTxId(highestPartitionTx);

    private final TimestampedOrderIdProvider orderIdProvider;
    private final AmzaStats amzaStats;
    private final AmzaRingStoreReader ringStoreReader;
    private final AmzaRingStoreWriter ringStoreWriter;
    private final AckWaters ackWaters;
    private final SystemWALStorage systemWALStorage;
    private final HighwaterStorage systemHighwaterStorage;
    private final TakeCoordinator takeCoordinator;
    private final PartitionStateStorage partitionStateStorage;
    private final RowChangeTaker changeTaker;
    private final PartitionTombstoneCompactor partitionTombstoneCompactor;
    private final PartitionComposter partitionComposter;
    private final PartitionIndex partitionIndex;
    private final PartitionCreator partitionProvider;
    private final PartitionStripeProvider partitionStripeProvider;
    private final WALUpdated walUpdated;
    private final AmzaPartitionWatcher partitionWatcher;
    private final AmzaAquariumProvider aquariumProvider;
    private final Liveliness liveliness;

    public AmzaService(TimestampedOrderIdProvider orderIdProvider,
        AmzaStats amzaStats,
        AmzaRingStoreReader ringReader,
        AmzaRingStoreWriter amzaHostRing,
        AckWaters ackWaters,
        SystemWALStorage systemWALStorage,
        HighwaterStorage systemHighwaterStorage,
        TakeCoordinator takeCoordinator,
        PartitionStateStorage partitionStateStorage,
        RowChangeTaker changeTaker,
        PartitionTombstoneCompactor partitionTombstoneCompactor,
        PartitionComposter partitionComposter,
        PartitionIndex partitionIndex,
        PartitionCreator partitionProvider,
        PartitionStripeProvider partitionStripeProvider,
        WALUpdated walUpdated,
        AmzaPartitionWatcher partitionWatcher,
        AmzaAquariumProvider aquariumProvider,
        Liveliness liveliness) {

        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.ringStoreReader = ringReader;
        this.ringStoreWriter = amzaHostRing;
        this.ackWaters = ackWaters;
        this.systemWALStorage = systemWALStorage;
        this.systemHighwaterStorage = systemHighwaterStorage;
        this.takeCoordinator = takeCoordinator;
        this.partitionStateStorage = partitionStateStorage;
        this.changeTaker = changeTaker;
        this.partitionTombstoneCompactor = partitionTombstoneCompactor;
        this.partitionComposter = partitionComposter;
        this.partitionIndex = partitionIndex;
        this.partitionProvider = partitionProvider;
        this.partitionStripeProvider = partitionStripeProvider;
        this.walUpdated = walUpdated;
        this.partitionWatcher = partitionWatcher;
        this.aquariumProvider = aquariumProvider;
        this.liveliness = liveliness;
    }

    public PartitionIndex getPartitionIndex() {
        return partitionIndex;
    }

    public AmzaRingStoreReader getRingReader() {
        return ringStoreReader;
    }

    public AmzaRingStoreWriter getRingWriter() {
        return ringStoreWriter;
    }

    public PartitionStateStorage getPartitionStateStorage() {
        return partitionStateStorage;
    }

    public PartitionComposter getPartitionComposter() {
        return partitionComposter;
    }

    public PartitionCreator getPartitionProvider() {
        return partitionProvider;
    }

    public PartitionStripeProvider getPartitionStripeProvider() {
        return partitionStripeProvider;
    }

    public HighwaterStorage getSystemHighwaterStorage() {
        return systemHighwaterStorage;
    }

    public AmzaAquariumProvider getAquariumProvider() {
        return aquariumProvider;
    }

    public Liveliness getLiveliness() {
        return liveliness;
    }

    public void start() throws Exception {
        partitionStripeProvider.start();
        changeTaker.start();
        partitionTombstoneCompactor.start();
        partitionComposter.start();
    }

    public void stop() throws Exception {
        partitionStripeProvider.stop();
        changeTaker.stop();
        partitionTombstoneCompactor.stop();
        partitionComposter.stop();
    }

    @Override
    public long getTimestamp(long timestampId, long deltaMillis) throws Exception {
        if (timestampId <= 0) {
            return 0;
        }
        return orderIdProvider.getApproximateId(timestampId, deltaMillis);
    }

    public void setPropertiesIfAbsent(PartitionName partitionName, PartitionProperties partitionProperties) throws Exception {
        PartitionProperties properties = partitionIndex.getProperties(partitionName);
        if (properties == null) {
            partitionProvider.updatePartitionProperties(partitionName, partitionProperties);
        }
    }

    public void awaitOnline(PartitionName partitionName, long timeoutMillis) throws Exception {
        if (!ringStoreWriter.isMemberOfRing(partitionName.getRingName())) {
            throw new IllegalStateException("Not a member of the ring for partition: " + partitionName);
        }

        PartitionProperties properties = partitionIndex.getProperties(partitionName);
        if (properties == null) {
            throw new IllegalStateException("No properties for partition: " + partitionName);
        }

        partitionStateStorage.tx(partitionName, (versionedPartitionName, partitionState, isOnline) -> {
            if (!isOnline) {
                VersionedState versionedState = partitionStateStorage.markAsBootstrap(partitionName);
                versionedPartitionName = new VersionedPartitionName(partitionName, versionedState.storageVersion.partitionVersion);
                partitionState = versionedState.waterline;
            }
            if (partitionProvider.createPartitionStoreIfAbsent(versionedPartitionName, properties)) {
                takeCoordinator.stateChanged(ringStoreReader, versionedPartitionName, partitionState, isOnline);
            }
            long start = System.currentTimeMillis();
            aquariumProvider.awaitOnline(versionedPartitionName, timeoutMillis);
            //LOG.info("Finished awaiting online in {} ms", System.currentTimeMillis() - start);
            return null;
        });
    }

    public AmzaPartitionRoute getPartitionRoute(PartitionName partitionName) throws Exception {

        NavigableMap<RingMember, RingHost> ring = ringStoreReader.getRing(partitionName.getRingName());
        PartitionProperties properties = partitionIndex.getProperties(partitionName);
        if (properties == null) {
            return new AmzaPartitionRoute(new ArrayList<>(ring.values()),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        }

        List<RingHost> orderedPartitionHosts = new ArrayList<>();
        List<RingMember> unregisteredRingMembers = new ArrayList<>();
        List<RingMember> ketchupRingMembers = new ArrayList<>();
        List<RingMember> expungedRingMembers = new ArrayList<>();
        List<RingMember> missingRingMembers = new ArrayList<>();

        if (ringStoreWriter.isMemberOfRing(partitionName.getRingName())) {
            partitionStateStorage.tx(partitionName, (versionedPartitionName, partitionState, isOnline) -> {
                if (!isOnline) {
                    VersionedState versionedState = partitionStateStorage.markAsBootstrap(partitionName);
                    versionedPartitionName = new VersionedPartitionName(partitionName, versionedState.storageVersion.partitionVersion);
                }
                partitionProvider.createPartitionStoreIfAbsent(versionedPartitionName, properties);
                return getPartition(partitionName);
            });
        }

        for (Entry<RingMember, RingHost> e : ring.entrySet()) {
            if (e.getValue() == RingHost.UNKNOWN_RING_HOST) {
                unregisteredRingMembers.add(e.getKey());
            }
            RemoteVersionedState remoteVersionedState = partitionStateStorage.getRemoteVersionedState(e.getKey(), partitionName);
            if (remoteVersionedState == null) {
                missingRingMembers.add(e.getKey());
            } else if (remoteVersionedState.waterline.getState() == State.expunged) {
                expungedRingMembers.add(e.getKey());
            } else if (remoteVersionedState.waterline.getState() == State.bootstrap) {
                ketchupRingMembers.add(e.getKey());
            } else {
                orderedPartitionHosts.add(e.getValue());
            }
        }
        return new AmzaPartitionRoute(Collections.emptyList(), orderedPartitionHosts, unregisteredRingMembers, ketchupRingMembers, expungedRingMembers,
            missingRingMembers);
    }

    public void visualizePartition(byte[] rawRingName, byte[] rawPartitionName, RowStream rowStream) throws Exception {
        PartitionName partitionName = new PartitionName(false, rawRingName, rawPartitionName);
        partitionStripeProvider.txPartition(partitionName, (stripe, highwaterStorage) -> {
            stripe.takeAllRows(partitionName, rowStream);
            return null;
        });
    }

    public static class AmzaPartitionRoute {

        public List<RingHost> uninitializedHosts;
        public List<RingHost> orderedPartitionHosts;
        public List<RingMember> unregisteredRingMembers;
        public List<RingMember> ketchupRingMembers;
        public List<RingMember> expungedRingMembers;
        public List<RingMember> missingRingMembers;

        public AmzaPartitionRoute(List<RingHost> uninitializedHosts,
            List<RingHost> orderedPartitionHosts,
            List<RingMember> unregisteredRingMembers,
            List<RingMember> ketchupRingMembers,
            List<RingMember> expungedRingMembers,
            List<RingMember> missingRingMembers) {
            this.uninitializedHosts = uninitializedHosts;
            this.orderedPartitionHosts = orderedPartitionHosts;
            this.unregisteredRingMembers = unregisteredRingMembers;
            this.ketchupRingMembers = ketchupRingMembers;
            this.expungedRingMembers = expungedRingMembers;
            this.missingRingMembers = missingRingMembers;
        }

    }

    @Override
    public Partition getPartition(PartitionName partitionName) throws Exception {
        if (partitionName.isSystemPartition()) {
            return new SystemPartition(amzaStats,
                orderIdProvider,
                walUpdated,
                ringStoreReader.getRingMember(),
                partitionName,
                systemWALStorage,
                systemHighwaterStorage,
                ackWaters,
                ringStoreReader);
        } else {
            return new StripedPartition(amzaStats,
                orderIdProvider,
                walUpdated,
                ringStoreReader.getRingMember(),
                partitionName,
                partitionStripeProvider,
                ackWaters,
                ringStoreReader,
                aquariumProvider);
        }
    }

    @Override
    public RingMember awaitLeader(PartitionName partitionName, long waitForLeaderElection) throws Exception {
        if (partitionName.isSystemPartition()) {
            throw new IllegalArgumentException("System partitions do not have leaders. " + partitionName);
        } else {
            Waterline leader = partitionStateStorage.awaitLeader(partitionName, waitForLeaderElection);
            return (leader != null) ? RingMember.fromAquariumMember(leader.getMember()) : null;
        }
    }

    public boolean hasPartition(PartitionName partitionName) throws Exception {
        if (partitionName.isSystemPartition()) {
            return true;
        } else {
            PartitionStore store = partitionIndex.get(PartitionCreator.REGION_INDEX);
            if (store != null) {
                return store.containsKey(null, partitionName.toBytes());
            }
            return false;
        }
    }

    @Override
    public Set<PartitionName> getPartitionNames() {
        return Sets.newHashSet(Lists.newArrayList(Iterables.transform(partitionIndex.getAllPartitions(), VersionedPartitionName::getPartitionName)));
    }

    public PartitionProperties getPartitionProperties(PartitionName partitionName) throws Exception {
        return partitionIndex.getProperties(partitionName);
    }

    @Override
    public void destroyPartition(PartitionName partitionName) throws Exception {

        NavigableMap<RingMember, RingHost> ring = ringStoreReader.getRing(partitionName.getRingName());
        if (ring.containsKey(ringStoreReader.getRingMember())) {
            RemoteVersionedState remoteVersionedState = partitionStateStorage.getRemoteVersionedState(ringStoreReader.getRingMember(), partitionName);
            if (remoteVersionedState != null) {
                LOG.info("Handling request to destroy partitionName:{}", partitionName);
                for (RingMember ringMember : ring.keySet()) {
                    VersionedPartitionName versionedPartitionName = new VersionedPartitionName(partitionName, remoteVersionedState.version);
                    VersionedState versionedState = partitionStateStorage.markForDisposal(versionedPartitionName, ringMember);
                    LOG.info("Destroyed partitionName:{} versionedState:{} for ringMember:{}", versionedPartitionName, versionedState, ringMember);
                }
            }
        }
    }

    public void watch(PartitionName partitionName, RowChanges rowChanges) throws Exception {
        partitionWatcher.watch(partitionName, rowChanges);
    }

    public RowChanges unwatch(PartitionName partitionName) throws Exception {
        return partitionWatcher.unwatch(partitionName);
    }

    @Override
    public void availableRowsStream(ChunkWriteable writeable, RingMember remoteRingMember, long takeSessionId, long heartbeatIntervalMillis) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);

        takeCoordinator.availableRowsStream(txHighestPartitionTx,
            ringStoreReader,
            remoteRingMember,
            takeSessionId,
            heartbeatIntervalMillis,
            (partitionName, txId) -> {
                dos.write(1);
                byte[] bytes = partitionName.toBytes();
                dos.writeInt(bytes.length);
                dos.write(bytes);
                dos.writeLong(txId);
            }, () -> {
                if (out.size() > 0) {
                    writeable.write(out.toByteArray());
                    out.reset();
                }
                return null;
            }, () -> {
                dos.write(1);
                dos.writeInt(0);
                writeable.write(out.toByteArray());
                out.reset();
                return null;
            });
        dos.write(0);
        writeable.write(out.toByteArray());
    }

    // for testing only
    public void availableRowsStream(RingMember remoteRingMember,
        long takeSessionId,
        long timeoutMillis,
        AvailableStream availableStream,
        Callable<Void> deliverCallback,
        Callable<Void> pingCallback) throws Exception {
        takeCoordinator.availableRowsStream(txHighestPartitionTx,
            ringStoreReader,
            remoteRingMember,
            takeSessionId,
            timeoutMillis,
            availableStream,
            deliverCallback,
            pingCallback);
    }

    @Override
    public void rowsStream(DataOutputStream dos,
        RingMember remoteRingMember,
        VersionedPartitionName localVersionedPartitionName,
        long localTxId,
        long leadershipToken) throws Exception {

        MutableLong bytes = new MutableLong(0);
        boolean needsToMarkAsKetchup;
        if (localVersionedPartitionName.getPartitionName().isSystemPartition()) {
            needsToMarkAsKetchup = systemWALStorage.takeRowUpdatesSince(localVersionedPartitionName, localTxId,
                (versionedPartitionName, partitionState, isOnline, rowStreamer) -> {
                    return streamOnline(remoteRingMember,
                        versionedPartitionName,
                        localTxId,
                        leadershipToken,
                        dos,
                        bytes,
                        systemHighwaterStorage,
                        rowStreamer);
                });
        } else {
            needsToMarkAsKetchup = partitionStripeProvider.txPartition(localVersionedPartitionName.getPartitionName(),
                (stripe, highwaterStorage) -> stripe.takeRowUpdatesSince(localVersionedPartitionName.getPartitionName(), localTxId,
                    (versionedPartitionName, partitionWaterlineState, isOnline, streamer) -> {
                        if (localVersionedPartitionName.equals(versionedPartitionName) && isOnline) {
                            return streamOnline(remoteRingMember,
                                versionedPartitionName,
                                localTxId,
                                leadershipToken,
                                dos,
                                bytes,
                                highwaterStorage,
                                streamer);
                        } else {
                            return streamOffline(dos, bytes, versionedPartitionName, partitionWaterlineState);
                        }
                    }));
        }

        amzaStats.netStats.wrote.addAndGet(bytes.longValue());

        if (needsToMarkAsKetchup) {
            PartitionName partitionName = localVersionedPartitionName.getPartitionName();
            try {
                if (ringStoreWriter.isMemberOfRing(partitionName.getRingName()) && partitionProvider.hasPartition(partitionName)) {
                    partitionStateStorage.markAsBootstrap(partitionName);
                }
            } catch (Exception x) {
                LOG.warn("Failed to mark as ketchup for partition {}", new Object[]{partitionName}, x);
            }
        }
    }

    boolean isOnline(VersionedPartitionName versionedPartitionName, Waterline waterline) throws Exception {
        return aquariumProvider.isOnline(versionedPartitionName, waterline);
    }

    private boolean streamOffline(DataOutputStream dos,
        MutableLong bytes,
        VersionedPartitionName versionedPartitionName,
        Waterline partitionWaterlineState) throws Exception {
        dos.writeLong(-1);
        dos.writeByte(0); // not online
        dos.writeByte(0); // last entry marker
        dos.writeByte(0); // last entry marker
        bytes.add(3);
        if (versionedPartitionName == null || partitionWaterlineState == null) {
            // someone thinks we're a member for this partition
            return true;
        } else {
            // BOOTSTRAP'S BOOTSTRAPS!
            partitionIndex.get(versionedPartitionName);
            return false;
        }
    }

    private boolean streamOnline(RingMember ringMember,
        VersionedPartitionName versionedPartitionName,
        long highestTransactionId,
        long leadershipToken,
        DataOutputStream dos,
        MutableLong bytes,
        HighwaterStorage highwaterStorage,
        PartitionStripe.RowStreamer streamer) throws Exception {

        ackWaters.set(ringMember, versionedPartitionName, highestTransactionId, leadershipToken);
        dos.writeLong(versionedPartitionName.getPartitionVersion());
        dos.writeByte(1); // fully online
        bytes.increment();
        for (Entry<RingMember, RingHost> node : ringStoreReader.getNeighbors(versionedPartitionName.getPartitionName().getRingName())) {
            Long highwatermark = highwaterStorage.get(node.getKey(), versionedPartitionName);
            if (highwatermark != null) {
                byte[] ringMemberBytes = node.getKey().toBytes();
                dos.writeByte(1);
                dos.writeInt(ringMemberBytes.length);
                dos.write(ringMemberBytes);
                dos.writeLong(highwatermark);
                bytes.add(1 + 4 + ringMemberBytes.length + 8);
            }
        }

        dos.writeByte(0); // last entry marker
        bytes.increment();

        streamer.stream((rowFP, rowTxId, rowType, row) -> {
            dos.writeByte(1);
            dos.writeLong(rowTxId);
            dos.writeByte(rowType.toByte());
            dos.writeInt(row.length);
            dos.write(row);
            bytes.add(1 + 8 + 1 + 4 + row.length);
            return true;
        });

        dos.writeByte(0); // last entry marker
        bytes.increment();
        return false;
    }

    @Override
    public void rowsTaken(RingMember remoteRingMember,
        VersionedPartitionName localVersionedPartitionName,
        long localTxId,
        long leadershipToken) throws Exception {
        ackWaters.set(remoteRingMember, localVersionedPartitionName, localTxId, leadershipToken);

        VersionedState localVersionedState = partitionStateStorage.getLocalVersionedState(localVersionedPartitionName.getPartitionName());
        if (localVersionedState.storageVersion.partitionVersion == localVersionedPartitionName.getPartitionVersion()) {
            takeCoordinator.rowsTaken(remoteRingMember, localVersionedPartitionName, localTxId, localVersionedState.isOnline);
        }
    }

    public void compactAllTombstones() throws Exception {
        LOG.info("Manual compact all tombstones requests.");
        partitionTombstoneCompactor.compactTombstone(0, 1, partitionTombstoneCompactor.removeIfOlderThanTimestampId(), true);
    }

    public void mergeAllDeltas(boolean force) {
        partitionStripeProvider.mergeAll(force);
    }

    public void expunge() throws Exception {
        partitionComposter.compost();
    }
}
