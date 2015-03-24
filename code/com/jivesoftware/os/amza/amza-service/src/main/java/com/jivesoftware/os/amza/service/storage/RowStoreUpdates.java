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
package com.jivesoftware.os.amza.service.storage;

import com.jivesoftware.os.amza.shared.WALKey;

public class RowStoreUpdates {

    private final RegionStore regionStore;
    private final RowsStorageUpdates rowsStorageChangeSet;
    private int changedCount = 0;

    RowStoreUpdates(RegionStore regionStore, RowsStorageUpdates rowsStorageChangeSet) {
        this.regionStore = regionStore;
        this.rowsStorageChangeSet = rowsStorageChangeSet;
    }

    public WALKey add(WALKey key, byte[] value) throws Exception {
        if (rowsStorageChangeSet.put(key, value)) {
            changedCount++;
        }
        return key;
    }

    public boolean remove(WALKey key) throws Exception {
        if (rowsStorageChangeSet.containsKey(key)) {
            byte[] got = rowsStorageChangeSet.getValue(key);
            if (got != null) {
                if (rowsStorageChangeSet.remove(key)) {
                    changedCount++;
                }
            }
            return true;
        } else {
            return false;
        }
    }

    public void commit() throws Exception {
        if (changedCount > 0) {
            regionStore.commit(rowsStorageChangeSet);
        }
    }
}
