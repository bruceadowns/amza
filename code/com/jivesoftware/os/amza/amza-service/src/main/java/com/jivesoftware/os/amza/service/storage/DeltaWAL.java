package com.jivesoftware.os.amza.service.storage;

import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RowStream;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALReader;
import com.jivesoftware.os.amza.shared.WALTx;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.WALWriter;
import com.jivesoftware.os.amza.shared.filer.UIO;
import com.jivesoftware.os.amza.storage.RowMarshaller;
import com.jivesoftware.os.amza.storage.binary.BinaryRowWriter;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang.mutable.MutableLong;

/**
 * @author jonathan.colt
 */
public class DeltaWAL {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final RegionName regionName;
    private final OrderIdProvider orderIdProvider;
    private final RowMarshaller<byte[]> rowMarshaller;
    private final WALTx rowsTx;
    private final AtomicLong updateCount = new AtomicLong();
    private final Object oneTxAtATimeLock = new Object();

    public DeltaWAL(RegionName regionName,
        OrderIdProvider orderIdProvider,
        RowMarshaller<byte[]> rowMarshaller,
        WALTx rowsTx) {
        this.regionName = regionName;
        this.orderIdProvider = orderIdProvider;
        this.rowMarshaller = rowMarshaller;
        this.rowsTx = rowsTx;
    }

    public void load(final RowStream rowStream) throws Exception {
        rowsTx.read(new WALTx.WALRead<Void>() {

            @Override
            public Void read(WALReader reader) throws Exception {
                reader.scan(0, rowStream);
                return null;
            }
        });
    }

    public WALKey addRegionTopkey(RegionName regionName, WALKey key) throws IOException {
        byte[] regionNameBytes = regionName.toBytes();
        ByteBuffer bb = ByteBuffer.allocate(2 + regionNameBytes.length + 4 + key.getKey().length);
        bb.putShort((short) regionNameBytes.length);
        bb.put(regionNameBytes);
        bb.putInt(key.getKey().length);
        bb.put(key.getKey());
        return new WALKey(bb.array());
    }

    public DeltaWALApplied update(final RegionName regionName, AtomicLong oldestApplied, final Map<WALKey, WALValue> apply) throws Exception {
        final NavigableMap<WALKey, byte[]> keyToRowPointer = new TreeMap<>();

        final MutableLong txId = new MutableLong();
        rowsTx.write(new WALTx.WALWrite<Void>() {
            @Override
            public Void write(WALWriter rowWriter) throws Exception {
                List<WALKey> keys = new ArrayList<>();
                List<byte[]> rawRows = new ArrayList<>();
                for (Map.Entry<WALKey, WALValue> e : apply.entrySet()) {
                    WALKey key = e.getKey();
                    WALValue value = e.getValue();
                    keys.add(key);
                    key = addRegionTopkey(regionName, key);
                    rawRows.add(rowMarshaller.toRow(key, value));
                }
                long transactionId;
                List<byte[]> rowPointers;
                synchronized (oneTxAtATimeLock) {
                    transactionId = (orderIdProvider == null) ? 0 : orderIdProvider.nextId();
                    rowPointers = rowWriter.write(Collections.nCopies(rawRows.size(), transactionId),
                        Collections.nCopies(rawRows.size(), (byte) WALWriter.VERSION_1),
                        rawRows);
                }
                txId.setValue(transactionId);
                for (int i = 0; i < rowPointers.size(); i++) {
                    keyToRowPointer.put(keys.get(i), rowPointers.get(i));
                }
                return null;
            }
        });
        updateCount.addAndGet(apply.size());
        return new DeltaWALApplied(keyToRowPointer, txId.longValue());

    }

    void takeRows(final ConcurrentNavigableMap<Long, Collection<byte[]>> tailMap, final RowStream rowStream) throws Exception {
        rowsTx.read(new WALTx.WALRead<Void>() {

            @Override
            public Void read(WALReader reader) throws Exception {
                for (Map.Entry<Long, Collection<byte[]>> entry : tailMap.entrySet()) {
                    for (byte[] fp : entry.getValue()) {
                        byte[] read = reader.read(fp);
                        if (!rowStream.row(UIO.bytesLong(fp), entry.getKey(), BinaryRowWriter.VERSION_1, read)) {// TODO Ah were to get rowType
                            return null;
                        }
                    }
                }
                return null;
            }
        });

    }

    WALValue hydrate(RegionName regionName, final byte[] rowPointer) throws Exception {
        return rowsTx.read(new WALTx.WALRead<WALValue>() {

            @Override
            public WALValue read(WALReader reader) throws Exception {
                return rowMarshaller.fromRow(reader.read(rowPointer)).getValue();
            }
        });
    }

    void compact(long maxTxId) throws Exception {
        rowsTx.compact(regionName, maxTxId, maxTxId, null);
    }

    public static class DeltaWALApplied {

        public final NavigableMap<WALKey, byte[]> keyToRowPointer;
        public final long txId;

        public DeltaWALApplied(NavigableMap<WALKey, byte[]> keyToRowPointer, long txId) {
            this.keyToRowPointer = keyToRowPointer;
            this.txId = txId;
        }

    }

}
