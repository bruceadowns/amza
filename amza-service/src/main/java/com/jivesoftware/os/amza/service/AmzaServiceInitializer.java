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

import com.google.common.base.Optional;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jivesoftware.os.amza.service.replication.RegionBackedHighwaterStorage;
import com.jivesoftware.os.amza.service.replication.RegionChangeReceiver;
import com.jivesoftware.os.amza.service.replication.RegionChangeReplicator;
import com.jivesoftware.os.amza.service.replication.RegionChangeTaker;
import com.jivesoftware.os.amza.service.replication.RegionCompactor;
import com.jivesoftware.os.amza.service.replication.RegionComposter;
import com.jivesoftware.os.amza.service.replication.RegionStatusStorage;
import com.jivesoftware.os.amza.service.replication.RegionStripe;
import com.jivesoftware.os.amza.service.replication.RegionStripeProvider;
import com.jivesoftware.os.amza.service.replication.SendFailureListener;
import com.jivesoftware.os.amza.service.replication.TakeFailureListener;
import com.jivesoftware.os.amza.service.storage.RegionIndex;
import com.jivesoftware.os.amza.service.storage.RegionPropertyMarshaller;
import com.jivesoftware.os.amza.service.storage.RegionProvider;
import com.jivesoftware.os.amza.service.storage.SystemStripeWALStorage;
import com.jivesoftware.os.amza.service.storage.WALs;
import com.jivesoftware.os.amza.service.storage.delta.DeltaStripeWALStorage;
import com.jivesoftware.os.amza.service.storage.delta.DeltaWALFactory;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RegionTx;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.RingMember;
import com.jivesoftware.os.amza.shared.RowChanges;
import com.jivesoftware.os.amza.shared.TxRegionStatus;
import com.jivesoftware.os.amza.shared.UpdatesSender;
import com.jivesoftware.os.amza.shared.UpdatesTaker;
import com.jivesoftware.os.amza.shared.VersionedRegionName;
import com.jivesoftware.os.amza.shared.WALStorageProvider;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.storage.binary.BinaryHighwaterRowMarshaller;
import com.jivesoftware.os.amza.storage.binary.BinaryPrimaryRowMarshaller;
import com.jivesoftware.os.amza.storage.binary.BinaryRowIOProvider;
import com.jivesoftware.os.amza.storage.binary.RowIOProvider;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AmzaServiceInitializer {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    public static class AmzaServiceConfig {

        public String[] workingDirectories = new String[] { "./var/data/" };

        public int resendReplicasIntervalInMillis = 1000;
        public int applyReplicasIntervalInMillis = 1000;
        public int takeFromNeighborsIntervalInMillis = 1000;

        public long checkIfCompactionIsNeededIntervalInMillis = 60_000;
        public long compactTombstoneIfOlderThanNMillis = 30 * 24 * 60 * 60 * 1000L;

        public int numberOfResendThreads = 8;
        public int numberOfApplierThreads = 8;
        public int numberOfCompactorThreads = 8;
        public int numberOfTakerThreads = 8;
        public int numberOfReplicatorThreads = 24;

        public int corruptionParanoiaFactor = 10;

        public int numberOfDeltaStripes = 4;
        public int maxUpdatesBeforeDeltaStripeCompaction = 1_000_000;
        public int deltaStripeCompactionIntervalInMillis = 1_000 * 60;

        public boolean hardFsync = false;

        public boolean useMemMap = false;
    }

    public AmzaService initialize(AmzaServiceConfig config,
        AmzaStats amzaStats,
        BinaryPrimaryRowMarshaller primaryRowMarshaller,
        BinaryHighwaterRowMarshaller highwaterRowMarshaller,
        RingMember ringMember,
        RingHost ringHost,
        TimestampedOrderIdProvider orderIdProvider,
        RegionPropertyMarshaller regionPropertyMarshaller,
        WALStorageProvider regionsWALStorageProvider,
        WALStorageProvider replicaWALStorageProvider,
        WALStorageProvider resendWALStorageProvider,
        UpdatesSender updatesSender,
        UpdatesTaker updatesTaker,
        Optional<SendFailureListener> sendFailureListener,
        Optional<TakeFailureListener> takeFailureListener,
        RowChanges allRowChanges) throws Exception {

        AmzaRegionWatcher amzaRegionWatcher = new AmzaRegionWatcher(allRowChanges);

        RowIOProvider ioProvider = new BinaryRowIOProvider(amzaStats.ioStats, config.corruptionParanoiaFactor, config.useMemMap);

        RegionIndex regionIndex = new RegionIndex(amzaStats, config.workingDirectories, "amza/stores",
            regionsWALStorageProvider, regionPropertyMarshaller, config.hardFsync);

        AmzaRingReader amzaRingReader = new AmzaRingReader(ringMember, regionIndex);

        WALs resendWALs = new WALs(config.workingDirectories, "amza/WAL/resend", resendWALStorageProvider);
        resendWALs.load();

        RegionChangeReplicator replicator = new RegionChangeReplicator(amzaStats,
            primaryRowMarshaller,
            amzaRingReader,
            regionIndex,
            resendWALs,
            updatesSender,
            Executors.newFixedThreadPool(config.numberOfReplicatorThreads),
            sendFailureListener,
            config.resendReplicasIntervalInMillis,
            config.numberOfResendThreads);

        RegionStripe systemRegionStripe = new RegionStripe("system",
            amzaStats,
            regionIndex,
            new SystemStripeWALStorage(),
            new TxRegionStatus() {

                @Override
                public <R> R tx(RegionName regionName, RegionTx<R> tx) throws Exception {
                    return tx.tx(new VersionedRegionName(regionName, 0), TxRegionStatus.Status.ONLINE);
                }
            },
            amzaRegionWatcher,
            (VersionedRegionName input) -> input.getRegionName().isSystemRegion());

        RegionStatusStorage regionStatusStorage = new RegionStatusStorage(orderIdProvider, ringMember, systemRegionStripe, replicator);
        regionIndex.open(regionStatusStorage);

        final int deltaStorageStripes = config.numberOfDeltaStripes;
        long maxUpdatesBeforeCompaction = config.maxUpdatesBeforeDeltaStripeCompaction;

        RegionBackedHighwaterStorage highwaterStorage = new RegionBackedHighwaterStorage(orderIdProvider, ringMember, systemRegionStripe, replicator);

        RegionStripe[] regionStripes = new RegionStripe[deltaStorageStripes];
        for (int i = 0; i < deltaStorageStripes; i++) {
            File walDir = new File(config.workingDirectories[i % config.workingDirectories.length], "delta-wal-" + i);
            DeltaWALFactory deltaWALFactory = new DeltaWALFactory(orderIdProvider, walDir, ioProvider, primaryRowMarshaller, highwaterRowMarshaller, -1);
            DeltaStripeWALStorage deltaWALStorage = new DeltaStripeWALStorage(highwaterStorage,
                i,
                primaryRowMarshaller,
                highwaterRowMarshaller,
                deltaWALFactory,
                maxUpdatesBeforeCompaction);
            int stripeId = i;
            regionStripes[i] = new RegionStripe("stripe-" + i, amzaStats, regionIndex, deltaWALStorage, regionStatusStorage, amzaRegionWatcher,
                (input) -> {
                    if (!input.getRegionName().isSystemRegion()) {

                        return Math.abs(input.hashCode()) % deltaStorageStripes == stripeId;
                    }
                    return false;
                });
        }

        RegionStripeProvider regionStripeProvider = new RegionStripeProvider(systemRegionStripe, regionStripes);

        RegionProvider regionProvider = new RegionProvider(
            orderIdProvider,
            regionPropertyMarshaller,
            replicator,
            regionIndex,
            allRowChanges,
            config.hardFsync);

        ExecutorService stripeLoaderThreadPool = Executors.newFixedThreadPool(regionStripes.length,
            new ThreadFactoryBuilder().setNameFormat("load-stripes-%d").build());
        List<Future> futures = new ArrayList<>();
        for (final RegionStripe regionStripe : regionStripes) {
            futures.add(stripeLoaderThreadPool.submit(() -> {
                try {
                    regionStripe.load();
                } catch (Exception x) {
                    LOG.error("Failed while loading " + regionStripe, x);
                    throw new RuntimeException(x);
                }
            }));
        }
        for (Future future : futures) {
            future.get();
        }
        stripeLoaderThreadPool.shutdown();

        ScheduledExecutorService compactDeltasThreadPool = Executors.newScheduledThreadPool(config.numberOfCompactorThreads,
            new ThreadFactoryBuilder().setNameFormat("compact-deltas-%d").build());
        for (final RegionStripe regionStripe : regionStripes) {
            compactDeltasThreadPool.scheduleAtFixedRate(() -> {
                try {
                    regionStripe.compact();
                } catch (Throwable x) {
                    LOG.error("Compactor failed.", x);
                }
            }, config.deltaStripeCompactionIntervalInMillis, config.deltaStripeCompactionIntervalInMillis, TimeUnit.MILLISECONDS);
        }

        AmzaHostRing amzaHostRing = new AmzaHostRing(amzaRingReader, systemRegionStripe, replicator, orderIdProvider);
        amzaRegionWatcher.watch(RegionProvider.RING_INDEX.getRegionName(), amzaHostRing);
        amzaHostRing.register(ringMember, ringHost);

        WALs replicatedWALs = new WALs(config.workingDirectories, "amza/WAL/replicated", replicaWALStorageProvider);
        replicatedWALs.load();

        RegionChangeReceiver changeReceiver = new RegionChangeReceiver(amzaStats,
            primaryRowMarshaller,
            regionStatusStorage,
            regionStripeProvider,
            replicatedWALs,
            config.applyReplicasIntervalInMillis,
            config.numberOfApplierThreads
        );

        RegionChangeTaker changeTaker = new RegionChangeTaker(amzaStats,
            amzaRingReader,
            regionIndex,
            regionStripeProvider,
            regionStripes,
            highwaterStorage,
            regionStatusStorage,
            updatesTaker,
            takeFailureListener,
            config.takeFromNeighborsIntervalInMillis,
            config.numberOfTakerThreads,
            config.hardFsync);

        RegionCompactor regionCompactor = new RegionCompactor(amzaStats,
            regionIndex,
            orderIdProvider,
            config.checkIfCompactionIsNeededIntervalInMillis,
            config.compactTombstoneIfOlderThanNMillis,
            config.numberOfCompactorThreads);

        RegionComposter regionComposter = new RegionComposter(regionIndex, regionProvider, amzaRingReader, regionStatusStorage, regionStripeProvider);

        return new AmzaService(orderIdProvider,
            amzaStats,
            amzaRingReader,
            amzaHostRing,
            highwaterStorage,
            regionStatusStorage,
            changeReceiver,
            changeTaker,
            replicator,
            regionCompactor,
            regionComposter, // its all about being GREEN!!
            regionIndex,
            regionProvider,
            regionStripeProvider,
            replicator,
            amzaRegionWatcher);
    }
}
