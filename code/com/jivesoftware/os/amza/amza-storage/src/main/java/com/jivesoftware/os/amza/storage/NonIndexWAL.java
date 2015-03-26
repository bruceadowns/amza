package com.jivesoftware.os.amza.storage;

import com.google.common.collect.ArrayListMultimap;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RowStream;
import com.jivesoftware.os.amza.shared.RowsChanged;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALReader;
import com.jivesoftware.os.amza.shared.WALScan;
import com.jivesoftware.os.amza.shared.WALScanable;
import com.jivesoftware.os.amza.shared.WALStorage;
import com.jivesoftware.os.amza.shared.WALStorageDescriptor;
import com.jivesoftware.os.amza.shared.WALTx;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.WALWriter;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class NonIndexWAL implements WALStorage {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final RegionName regionName;
    private final OrderIdProvider orderIdProvider;
    private final RowMarshaller<byte[]> rowMarshaller;
    private final WALTx rowsTx;
    private final AtomicLong updateCount = new AtomicLong();

    public NonIndexWAL(RegionName regionName,
        OrderIdProvider orderIdProvider,
        RowMarshaller<byte[]> rowMarshaller,
        WALTx rowsTx) {
        this.regionName = regionName;
        this.orderIdProvider = orderIdProvider;
        this.rowMarshaller = rowMarshaller;
        this.rowsTx = rowsTx;
    }

    public RegionName getRegionName() {
        return regionName;
    }

    @Override
    public void compactTombstone(long removeTombstonedOlderThanTimestampId) throws Exception {
        if (updateCount.get() > 0) {
            rowsTx.compact(regionName, removeTombstonedOlderThanTimestampId, null);
            updateCount.set(0);
        }
    }

    @Override
    public void load() throws Exception {

    }

    @Override
    public RowsChanged update(WALScanable updates) throws Exception {
        final AtomicLong oldestApplied = new AtomicLong(Long.MAX_VALUE);
        final NavigableMap<WALKey, WALValue> apply = new TreeMap<>();

        updates.rowScan(new WALScan() {
            @Override
            public boolean row(long transactionId, WALKey key, WALValue update) throws Exception {
                apply.put(key, update);
                if (oldestApplied.get() > update.getTimestampId()) {
                    oldestApplied.set(update.getTimestampId());
                }
                return true;
            }
        });

        if (apply.isEmpty()) {
            return new RowsChanged(regionName, oldestApplied.get(), apply, new TreeMap<WALKey, WALValue>(), ArrayListMultimap.<WALKey, WALValue>create());
        } else {
            rowsTx.write(new WALTx.WALWrite<Void>() {
                @Override
                public Void write(WALWriter rowWriter) throws Exception {

                    List<byte[]> rawRows = new ArrayList<>();
                    for (Map.Entry<WALKey, WALValue> e : apply.entrySet()) {
                        WALKey key = e.getKey();
                        WALValue value = e.getValue();
                        rawRows.add(rowMarshaller.toRow(key, value));
                    }
                    long transactionId = (orderIdProvider == null) ? 0 : orderIdProvider.nextId();
                    rowWriter.write(Collections.nCopies(rawRows.size(), transactionId),
                        Collections.nCopies(rawRows.size(), (byte) WALWriter.VERSION_1),
                        rawRows,
                        true);
                    return null;
                }
            });
            updateCount.addAndGet(apply.size());
            return new RowsChanged(regionName, oldestApplied.get(), apply, new TreeMap<WALKey, WALValue>(), ArrayListMultimap.<WALKey, WALValue>create());
        }

    }

    @Override
    public void rowScan(final WALScan walScan) throws Exception {
        rowsTx.read(new WALTx.WALRead<Void>() {

            @Override
            public Void read(WALReader reader) throws Exception {
                reader.scan(0, new RowStream() {

                    @Override
                    public boolean row(long rowFP, long rowTxId, byte rowType, byte[] rawWRow) throws Exception {
                        if (rowType > 0) {
                            RowMarshaller.WALRow row = rowMarshaller.fromRow(rawWRow);
                            return walScan.row(rowTxId, row.getKey(), row.getValue());
                        }
                        return true;
                    }
                });
                return null;
            }
        });
    }

    @Override
    public void rangeScan(final WALKey from, final WALKey to, final WALScan walScan) throws Exception {
        rowsTx.read(new WALTx.WALRead<Void>() {

            @Override
            public Void read(WALReader reader) throws Exception {
                reader.scan(0, new RowStream() {

                    @Override
                    public boolean row(long rowPointer, long rowTxId, byte rowType, byte[] rawWRow) throws Exception {
                        if (rowType > 0) {
                            RowMarshaller.WALRow row = rowMarshaller.fromRow(rawWRow);
                            if (row.getKey().compareTo(to) < 0) {
                                if (from.compareTo(row.getKey()) <= 0) {
                                    walScan.row(rowTxId, row.getKey(), row.getValue());
                                }
                                return true;
                            } else {
                                return false;
                            }
                        }
                        return true;
                    }
                });
                return null;
            }
        });

    }

    @Override
    public WALValue get(WALKey key) throws Exception {
        return get(Collections.singletonList(key)).get(0);
    }

    @Override
    public List<WALValue> get(List<WALKey> keys) throws Exception {
        throw new UnsupportedOperationException("NonIndexWAL doesn't support gets.");
    }

    @Override
    public boolean containsKey(WALKey key) throws Exception {
        return containsKey(Collections.singletonList(key)).get(0);
    }

    @Override
    public List<Boolean> containsKey(List<WALKey> keys) throws Exception {
        throw new UnsupportedOperationException("NonIndexWAL doesn't support gets.");
    }

    @Override
    public void takeRowUpdatesSince(final long sinceTransactionId, final RowStream rowStream) throws Exception {
        rowsTx.read(new WALTx.WALRead<Void>() {

            @Override
            public Void read(WALReader rowReader) throws Exception {
                rowReader.reverseScan(new RowStream() {
                    @Override
                    public boolean row(long rowPointer, long rowTxId, byte rowType, byte[] row) throws Exception {
                        if (rowType > 0 && rowTxId > sinceTransactionId) {
                            return rowStream.row(rowPointer, rowTxId, rowType, row);
                        }
                        return true;
                    }
                });
                return null;
            }
        });

    }

    @Override
    public void updatedStorageDescriptor(WALStorageDescriptor walStorageDescriptor) throws Exception {
    }

}
