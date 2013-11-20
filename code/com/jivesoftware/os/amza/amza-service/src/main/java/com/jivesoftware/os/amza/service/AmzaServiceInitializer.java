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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jivesoftware.os.amza.service.storage.TableStoreProvider;
import com.jivesoftware.os.amza.service.storage.replication.HostRingProvider;
import com.jivesoftware.os.amza.service.storage.replication.MemoryBackedHighWaterMarks;
import com.jivesoftware.os.amza.service.storage.replication.TableReplicator;
import com.jivesoftware.os.amza.shared.ChangeSetSender;
import com.jivesoftware.os.amza.shared.ChangeSetTaker;
import com.jivesoftware.os.amza.shared.TableDelta;
import com.jivesoftware.os.amza.shared.TableName;
import com.jivesoftware.os.amza.shared.TableStateChanges;
import com.jivesoftware.os.amza.shared.TableStorageProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

public class AmzaServiceInitializer {

    public static class AmzaServiceConfig {

        public String workingDirectory = "./var/data/";
        public int replicationFactor = 1;
        public int takeFromFactor = 1;
        public int resendReplicasIntervalInMillis = 10000;
        public int applyReplicasIntervalInMillis = 1000;
        public int takeFromNeighborsIntervalInMillis = 10000;
        public long compactTombstoneIfOlderThanNMillis = 1 * 24 * 60 * 60 * 1000L;
    }

    public AmzaService initialize(AmzaServiceConfig config,
        OrderIdProvider orderIdProvider,
        TableStorageProvider amzaStores,
        TableStorageProvider amzaReplicasWAL,
        TableStorageProvider amzaResendWAL,
        ChangeSetSender changeSetSender,
        ChangeSetTaker tableTaker,
        final TableStateChanges<Object, Object> allChangesCallback) throws Exception {

        final AtomicReference<HostRingProvider> hostRingProvider = new AtomicReference<>();
        final AtomicReference<TableReplicator> replicator = new AtomicReference<>();
        TableStateChanges<Object, Object> tableStateChanges = new TableStateChanges<Object, Object>() {
            @Override
            public void changes(TableName<Object, Object> mapName, TableDelta<Object, Object> change) throws Exception {
                replicator.get().replicateLocalChanges(hostRingProvider.get(), mapName, change.getApply(), true);
                allChangesCallback.changes(mapName, change);
            }
        };
        AmzaTableWatcher amzaTableWatcher = new AmzaTableWatcher(tableStateChanges);

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        File workingDirectory = new File(config.workingDirectory);

        TableStoreProvider storesProvider = new TableStoreProvider(workingDirectory, "amza/stores", amzaStores, amzaTableWatcher);
        TableStoreProvider replicasProvider = new TableStoreProvider(workingDirectory, "amza/WAL/replicated", amzaReplicasWAL, null);
        TableStoreProvider resendsProvider = new TableStoreProvider(workingDirectory, "amza/WAL/resend", amzaResendWAL, null);

        MemoryBackedHighWaterMarks highWaterMarks = new MemoryBackedHighWaterMarks();

        TableReplicator tableReplicator = new TableReplicator(storesProvider,
            config.replicationFactor,
            config.takeFromFactor,
            highWaterMarks,
            replicasProvider,
            resendsProvider,
            changeSetSender, tableTaker);
        replicator.set(tableReplicator);

        AmzaService service = new AmzaService(orderIdProvider, tableReplicator, storesProvider, amzaTableWatcher);
        hostRingProvider.set(service);
        return service;
    }
}