package com.jivesoftware.os.amza.shared.take;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.shared.partition.TxHighestPartitionTx;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionProvider;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.ring.RingTopology;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.take.AvailableRowsTaker.AvailableStream;
import com.jivesoftware.os.aquarium.LivelyEndState;
import com.jivesoftware.os.filer.io.IBA;
import com.jivesoftware.os.jive.utils.ordered.id.IdPacker;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class TakeCoordinator {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AmzaStats amzaStats;
    private final TimestampedOrderIdProvider timestampedOrderIdProvider;
    private final VersionedPartitionProvider versionedPartitionProvider;
    private final IdPacker idPacker;

    private final ConcurrentHashMap<IBA, TakeRingCoordinator> takeRingCoordinators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RingMember, Object> ringMembersLocks = new ConcurrentHashMap<>();
    private final AtomicLong updates = new AtomicLong();
    private final AtomicLong cyaLock = new AtomicLong();
    private final long cyaIntervalMillis;
    private final long slowTakeInMillis;
    private final long systemReofferDeltaMillis;
    private final long reofferDeltaMillis;

    public TakeCoordinator(AmzaStats amzaStats,
        TimestampedOrderIdProvider timestampedOrderIdProvider,
        IdPacker idPacker,
        VersionedPartitionProvider versionedPartitionProvider,
        long cyaIntervalMillis,
        long slowTakeInMillis,
        long systemReofferDeltaMillis,
        long reofferDeltaMillis) {
        this.amzaStats = amzaStats;
        this.timestampedOrderIdProvider = timestampedOrderIdProvider;
        this.idPacker = idPacker;
        this.versionedPartitionProvider = versionedPartitionProvider;
        this.cyaIntervalMillis = cyaIntervalMillis;
        this.slowTakeInMillis = slowTakeInMillis;
        this.systemReofferDeltaMillis = systemReofferDeltaMillis;
        this.reofferDeltaMillis = reofferDeltaMillis;
    }

    //TODO bueller?
    public void awakeCya() {
        cyaLock.incrementAndGet();
        synchronized (cyaLock) {
            cyaLock.notifyAll();
        }
    }

    public interface BootstrapPartitions {

        boolean bootstrap(PartitionStream partitionStream) throws Exception;
    }

    public interface PartitionStream {

        boolean stream(VersionedPartitionName versionedPartitionName, LivelyEndState livelyEndState) throws Exception;
    }

    public void start(AmzaRingReader ringReader, BootstrapPartitions bootstrapPartitions) {
        ExecutorService cya = Executors.newFixedThreadPool(1, new ThreadFactoryBuilder().setNameFormat("cya-%d").build());
        cya.submit(() -> {
            while (true) {
                long updates = cyaLock.get();
                try {
                    for (IBA ringName : takeRingCoordinators.keySet()) {
                        TakeRingCoordinator takeRingCoordinator = takeRingCoordinators.get(ringName);
                        RingTopology ring = ringReader.getRing(ringName.getBytes());
                        if (takeRingCoordinator.cya(ring)) {
                            awakeRemoteTakers(ring);
                        }
                    }

                } catch (Exception x) {
                    LOG.error("Failed while ensuring alignment.", x);
                }

                /*bootstrapPartitions.bootstrap((versionedPartitionName) -> {
                    byte[] ringName = versionedPartitionName.getPartitionName().getRingName();
                    List<Entry<RingMember, RingHost>> neighbors = ringReader.getNeighbors(ringName);
                    ensureRingCoordinator(ringName, () -> neighbors).update(neighbors, versionedPartitionName, -1);
                    return true;
                });*/

                try {
                    synchronized (cyaLock) {
                        if (cyaLock.get() == updates) {
                            cyaLock.wait(cyaIntervalMillis);
                        }
                    }
                } catch (InterruptedException x) {
                    Thread.currentThread().interrupt();
                }
            }

        });
    }

    public void expunged(List<VersionedPartitionName> versionedPartitionNames) {
        SetMultimap<IBA, VersionedPartitionName> expunged = HashMultimap.create();

        for (VersionedPartitionName versionedPartitionName : versionedPartitionNames) {
            expunged.put(new IBA(versionedPartitionName.getPartitionName().getRingName()), versionedPartitionName);
        }

        for (IBA ringName : expunged.keySet()) {
            TakeRingCoordinator takeRingCoordinator = takeRingCoordinators.get(ringName);
            if (takeRingCoordinator != null) {
                takeRingCoordinator.expunged(expunged.get(ringName));
            }
        }
    }

    public void update(AmzaRingReader ringReader, VersionedPartitionName versionedPartitionName, long txId) throws Exception {
        updates.incrementAndGet();
        byte[] ringName = versionedPartitionName.getPartitionName().getRingName();
        RingTopology ring = ringReader.getRing(ringName);
        ensureRingCoordinator(ringName, () -> ring).update(ring, versionedPartitionName, txId);
        amzaStats.updates(ringReader.getRingMember(), versionedPartitionName.getPartitionName(), 1, txId);
        awakeRemoteTakers(ring);
    }

    public void stateChanged(AmzaRingReader ringReader, VersionedPartitionName versionedPartitionName) throws Exception {
        update(ringReader, versionedPartitionName, 0);
    }

    interface RingSupplier {
        RingTopology get();
    }

    private TakeRingCoordinator ensureRingCoordinator(byte[] ringName, RingSupplier ringSupplier) {
        return takeRingCoordinators.computeIfAbsent(new IBA(ringName),
            key -> new TakeRingCoordinator(ringName,
                timestampedOrderIdProvider,
                idPacker,
                versionedPartitionProvider,
                slowTakeInMillis,
                systemReofferDeltaMillis,
                reofferDeltaMillis,
                ringSupplier.get()));
    }

    private void awakeRemoteTakers(RingTopology ring) {
        for (int i = 0; i < ring.entries.size(); i++) {
            if (ring.rootMemberIndex != i) {
                Object lock = ringMembersLocks.computeIfAbsent(ring.entries.get(i).ringMember, (ringMember) -> new Object());
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }
    }

    public void availableRowsStream(TxHighestPartitionTx<Long> txHighestPartitionTx,
        AmzaRingReader ringReader,
        RingMember remoteRingMember,
        CheckState checkState,
        long takeSessionId,
        long heartbeatIntervalMillis,
        AvailableStream availableStream,
        Callable<Void> deliverCallback,
        Callable<Void> pingCallback) throws Exception {

        AtomicLong offered = new AtomicLong();
        AvailableStream watchAvailableStream = (versionedPartitionName, txId) -> {
            //LOG.info("OFFER:local:{} remote:{} txId:{} partition:{} state:{}",
            //    ringReader.getRingMember(), remoteRingMember, txId, versionedPartitionName, state);
            offered.incrementAndGet();
            availableStream.available(versionedPartitionName, txId);
            amzaStats.offers(remoteRingMember, versionedPartitionName.getPartitionName(), 1, txId);
        };

        while (true) {
            long start = updates.get();
            /*LOG.info("Checking available for {}...", remoteRingMember);
            long timestamp = System.currentTimeMillis();*/
            //LOG.info("CHECKING: remote:{} local:{}", remoteRingMember, ringReader.getRingMember());

            long[] suggestedWaitInMillis = new long[] { Long.MAX_VALUE };
            ringReader.getRingNames(remoteRingMember, (ringName) -> {
                TakeRingCoordinator ring = ensureRingCoordinator(ringName, () -> {
                    try {
                        return ringReader.getRing(ringName);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                if (ring != null) {
                    suggestedWaitInMillis[0] = Math.min(suggestedWaitInMillis[0],
                        ring.availableRowsStream(txHighestPartitionTx,
                            remoteRingMember,
                            checkState,
                            takeSessionId,
                            watchAvailableStream));
                }
                return true;
            });
            if (suggestedWaitInMillis[0] == Long.MAX_VALUE) {
                suggestedWaitInMillis[0] = heartbeatIntervalMillis; // Hmmm
            }

            /*LOG.info("Checked available for {} in {}", remoteRingMember, System.currentTimeMillis() - timestamp);
            LOG.info("Streaming available for {}...", remoteRingMember);
            timestamp = System.currentTimeMillis();*/

            Object lock = ringMembersLocks.computeIfAbsent(remoteRingMember, (key) -> new Object());
            synchronized (lock) {
                long time = System.currentTimeMillis();
                long timeRemaining = suggestedWaitInMillis[0];
                while (start == updates.get() && System.currentTimeMillis() - time < suggestedWaitInMillis[0]) {
                    long timeToWait = Math.min(timeRemaining, heartbeatIntervalMillis);
                    //LOG.info("PARKED:remote:{} for {}millis on local:{}",
                    //    remoteRingMember, wait, ringReader.getRingMember());
                    if (offered.get() == 0) {
                        pingCallback.call(); // Ping aka keep the socket alive
                    } else {
                        deliverCallback.call();
                    }
                    lock.wait(timeToWait);
                    timeRemaining -= heartbeatIntervalMillis;
                    if (timeRemaining < 0) {
                        break;
                    }
                }
            }

            /*LOG.info("Streamed available for {} in {}", remoteRingMember, System.currentTimeMillis() - timestamp);*/
        }
    }

    public void rowsTaken(TxHighestPartitionTx<Long> txHighestPartitionTx,
        RingMember remoteRingMember,
        long takeSessionId,
        VersionedPartitionName localVersionedPartitionName,
        long localTxId) throws Exception {

        //LOG.info("TAKEN remote:{} took local:{} txId:{} partition:{}",
        //    remoteRingMember, null, localTxId, localVersionedPartitionName);
        byte[] ringName = localVersionedPartitionName.getPartitionName().getRingName();
        TakeRingCoordinator ring = takeRingCoordinators.get(new IBA(ringName));
        ring.rowsTaken(txHighestPartitionTx, remoteRingMember, takeSessionId, localVersionedPartitionName, localTxId);
    }

}
