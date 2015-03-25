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

import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALScan;
import com.jivesoftware.os.amza.shared.WALScanable;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.transport.tcp.replication.serialization.MessagePayload;
import java.io.IOException;
import java.util.List;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;

/**
 *
 */
public class RowUpdatesPayload implements MessagePayload, WALScanable {

    private RegionName mapName;
    private long largestTransactionId;
    private List<WALKey> keys;
    private List<WALValue> values;

    /**
     * for serialization
     */
    public RowUpdatesPayload() {
    }

    public RowUpdatesPayload(RegionName mapName, long largestTransactionId, List<WALKey> keys, List<WALValue> values) {
        this.mapName = mapName;
        this.largestTransactionId = largestTransactionId;
        this.keys = keys;
        this.values = values;
    }

    @Override
    public void serialize(FSTObjectOutput output) throws IOException {
        output.writeObject(mapName, RegionName.class);
        output.writeLong(largestTransactionId);
        output.writeObject(keys, List.class);
        output.writeObject(values, List.class);
    }

    @Override
    public void deserialize(FSTObjectInput input) throws Exception {
        this.mapName = (RegionName) input.readObject(RegionName.class);
        this.largestTransactionId = input.readLong();
        this.keys = (List<WALKey>) input.readObject(List.class);
        this.values = (List<WALValue>) input.readObject(List.class);
    }

    public RegionName getMapName() {
        return mapName;
    }

    public int size() {
        return keys.size();
    }

    @Override
    public void rowScan(WALScan walScan) throws Exception {
        for (int i = 0; i < keys.size(); i++) {
            if (!walScan.row(largestTransactionId, keys.get(i), values.get(i))) {
                return;
            }
        }
    }

    @Override
    public void rangeScan(WALKey from, WALKey to, WALScan walScan) throws Exception {
        for (int i = 0; i < keys.size(); i++) {
            if (!walScan.row(largestTransactionId, keys.get(i), values.get(i))) {
                return;
            }
        }
    }

    @Override
    public String toString() {
        return "UpdatesPayload{" + "mapName=" + mapName + ", keys=" + keys + ", values=" + values + '}';
    }

}
