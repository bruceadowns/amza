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
package com.jivesoftware.os.amza.api.wal;

import com.jivesoftware.os.amza.api.IoStats;
import com.jivesoftware.os.amza.api.stream.RowType;

public interface WALWriter {

    int write(IoStats ioStats,
        long txId,
        RowType rowType,
        int estimatedNumberOfRows,
        int estimatedSizeInBytes,
        RawRows rows,
        IndexableKeys indexableKeys,
        TxKeyPointerFpStream stream,
        boolean addToLeapCount,
        boolean hardFsyncBeforeLeapBoundary) throws Exception;

    long writeSystem(IoStats ioStats, byte[] row) throws Exception;

    long writeHighwater(IoStats ioStats, byte[] row) throws Exception;

    long getEndOfLastRow() throws Exception;

    interface RawRows {

        boolean consume(RawRowStream stream) throws Exception;
    }

    interface RawRowStream {

        boolean stream(byte[] row) throws Exception;
    }

    interface IndexableKeys {

        boolean consume(IndexableKeyStream stream) throws Exception;
    }

    interface IndexableKeyStream {

        boolean stream(byte[] prefix, byte[] key, byte[] value, long valueTimestamp, boolean valueTombstoned, long valueVersion) throws Exception;
    }

    interface TxKeyPointerFpStream {

        boolean stream(long txId,
            byte[] prefix,
            byte[] key,
            byte[] value,
            long valueTimestamp,
            boolean valueTombstoned,
            long valueVersion,
            long fp) throws Exception;
    }
}
