package com.jivesoftware.os.amza.shared.wal;

public interface TxFpKeyValueStream {

    boolean stream(long txId, long fp, byte[] prefix, byte[] key, byte[] value, long valueTimestamp, boolean valueTombstoned, byte[] row) throws Exception;

}
