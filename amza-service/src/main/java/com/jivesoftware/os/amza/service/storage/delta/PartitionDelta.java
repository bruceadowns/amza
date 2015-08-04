package com.jivesoftware.os.amza.service.storage.delta;

import com.google.common.collect.Iterators;
import com.google.common.primitives.Longs;
import com.jivesoftware.os.amza.service.storage.PartitionIndex;
import com.jivesoftware.os.amza.service.storage.PartitionStore;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import com.jivesoftware.os.amza.shared.wal.FpKeyValueStream;
import com.jivesoftware.os.amza.shared.wal.KeyUtil;
import com.jivesoftware.os.amza.shared.wal.KeyValuePointerStream;
import com.jivesoftware.os.amza.shared.wal.KeyValues;
import com.jivesoftware.os.amza.shared.wal.WALKey;
import com.jivesoftware.os.amza.shared.wal.WALKeyStream;
import com.jivesoftware.os.amza.shared.wal.WALKeys;
import com.jivesoftware.os.amza.shared.wal.WALPointer;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.lang.mutable.MutableBoolean;

/**
 * @author jonathan.colt
 */
class PartitionDelta {

    public static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final VersionedPartitionName versionedPartitionName;
    private final DeltaWAL deltaWAL;
    final AtomicReference<PartitionDelta> merging;

    private final Map<WALKey, WALPointer> pointerIndex = new ConcurrentHashMap<>(); //TODO replace with concurrent byte[] map
    private final ConcurrentSkipListMap<byte[], WALPointer> orderedIndex = new ConcurrentSkipListMap<>(KeyUtil::compare);
    //private final ConcurrentSkipListMap<Long, long[]> txIdWAL = new ConcurrentSkipListMap<>();
    private final AppendOnlyConcurrentArrayList txIdWAL = new AppendOnlyConcurrentArrayList(1024); //TODO expose to config
    private final AtomicLong updatesSinceLastHighwaterFlush = new AtomicLong();

    PartitionDelta(VersionedPartitionName versionedPartitionName,
        DeltaWAL deltaWAL,
        PartitionDelta merging) {
        this.versionedPartitionName = versionedPartitionName;
        this.deltaWAL = deltaWAL;
        this.merging = new AtomicReference<>(merging);
    }

    public long size() {
        return pointerIndex.size();
    }

    private boolean streamRawValues(WALKeys keys, FpKeyValueStream fpKeyValueStream) throws Exception {
        return deltaWAL.hydrate(fpStream -> {
            PartitionDelta mergingPartitionDelta = merging.get();
            if (mergingPartitionDelta != null) {
                return mergingPartitionDelta.streamRawValues(
                    mergingKeyStream -> keys.consume((prefix, key) -> {
                        WALPointer got = pointerIndex.get(new WALKey(prefix, key));
                        if (got == null) {
                            return mergingKeyStream.stream(prefix, key);
                        } else {
                            return fpStream.stream(got.getFp());
                        }
                    }),
                    fpKeyValueStream);
            } else {
                return keys.consume((prefix, key) -> {
                    WALPointer got = pointerIndex.get(new WALKey(prefix, key));
                    if (got == null) {
                        return fpKeyValueStream.stream(-1, prefix, key, null, -1, false);
                    } else {
                        return fpStream.stream(got.getFp());
                    }
                });
            }
        }, fpKeyValueStream);
    }

    boolean get(WALKeys keys, FpKeyValueStream fpKeyValueStream) throws Exception {
        return streamRawValues(keys::consume, fpKeyValueStream);
    }

    WALPointer getPointer(byte[] prefix, byte[] key) throws Exception {
        WALPointer got = pointerIndex.get(new WALKey(prefix, key));
        if (got != null) {
            return got;
        }
        PartitionDelta partitionDelta = merging.get();
        if (partitionDelta != null) {
            return partitionDelta.getPointer(prefix, key);
        }
        return null;
    }

    boolean getPointers(KeyValues keyValues, KeyValuePointerStream stream) throws Exception {
        return keyValues.consume((prefix, key, value, valueTimestamp, valueTombstone) -> {
            WALPointer pointer = getPointer(prefix, key);
            if (pointer != null) {
                return stream.stream(prefix, key, value, valueTimestamp, valueTombstone, pointer.getTimestampId(), pointer.getTombstoned(), pointer.getFp());
            } else {
                return stream.stream(prefix, key, value, valueTimestamp, valueTombstone, -1, false, -1);
            }
        });
    }

    Boolean containsKey(byte[] prefix, byte[] key) {
        WALPointer got = pointerIndex.get(new WALKey(prefix, key));
        if (got != null) {
            return !got.getTombstoned();
        }
        PartitionDelta partitionDelta = merging.get();
        if (partitionDelta != null) {
            return partitionDelta.containsKey(prefix, key);
        }
        return null;
    }

    boolean containsKeys(WALKeys keys, KeyTombstoneExistsStream stream) throws Exception {
        return keys.consume((prefix, key) -> {
            Boolean got = containsKey(prefix, key);
            return stream.stream(prefix, key, got != null && !got, got != null);
        });
    }

    interface KeyTombstoneExistsStream {

        boolean stream(byte[] prefix, byte[] key, boolean tombstoned, boolean exists) throws Exception;
    }

    void put(long fp,
        byte[] prefix,
        byte[] key,
        long valueTimestamp,
        boolean valueTombstone) {

        WALPointer pointer = new WALPointer(fp, valueTimestamp, valueTombstone);
        WALKey walKey = new WALKey(prefix, key);
        pointerIndex.put(walKey, pointer);
        orderedIndex.put(walKey.compose(), pointer);
    }

    private AtomicBoolean firstAndOnlyOnce = new AtomicBoolean(true);

    public boolean shouldWriteHighwater() {
        long got = updatesSinceLastHighwaterFlush.get();
        if (got > 1000) { // TODO expose to partition config
            updatesSinceLastHighwaterFlush.set(0);
            return true;
        } else {
            return firstAndOnlyOnce.compareAndSet(true, false);
        }
    }

    boolean keys(WALKeyStream keyStream) throws Exception {
        return WALKey.decompose(
            txFpRawKeyValueEntryStream -> {
                for (byte[] pk : orderedIndex.keySet()) {
                    if (!txFpRawKeyValueEntryStream.stream(-1, -1, pk, null, -1, false, null)) {
                        return false;
                    }
                }
                return true;
            },
            (txId, fp, prefix, key, value, valueTimestamp, valueTombstoned, entry) -> keyStream.stream(prefix, key));
    }

    DeltaPeekableElmoIterator rangeScanIterator(byte[] fromPrefix, byte[] fromKey, byte[] toPrefix, byte[] toKey) {
        byte[] from = WALKey.compose(fromPrefix, fromKey);
        byte[] to = WALKey.compose(toPrefix, toKey);
        Iterator<Map.Entry<byte[], WALPointer>> iterator = orderedIndex.subMap(from, to).entrySet().iterator();
        Iterator<Map.Entry<byte[], WALPointer>> mergingIterator = Iterators.emptyIterator();
        PartitionDelta mergingPartitionDelta = merging.get();
        DeltaWAL mergingDeltaWAL = null;
        if (mergingPartitionDelta != null) {
            mergingIterator = mergingPartitionDelta.orderedIndex.subMap(from, to).entrySet().iterator();
            mergingDeltaWAL = mergingPartitionDelta.deltaWAL;
        }
        return new DeltaPeekableElmoIterator(iterator, mergingIterator, deltaWAL, mergingDeltaWAL);
    }

    DeltaPeekableElmoIterator rowScanIterator() {
        Iterator<Map.Entry<byte[], WALPointer>> iterator = orderedIndex.entrySet().iterator();
        Iterator<Map.Entry<byte[], WALPointer>> mergingIterator = Iterators.emptyIterator();
        PartitionDelta mergingPartitionDelta = merging.get();
        DeltaWAL mergingDeltaWAL = null;
        if (mergingPartitionDelta != null) {
            mergingIterator = mergingPartitionDelta.orderedIndex.entrySet().iterator();
            mergingDeltaWAL = mergingPartitionDelta.deltaWAL;
        }
        return new DeltaPeekableElmoIterator(iterator, mergingIterator, deltaWAL, mergingDeltaWAL);
    }

    long highestTxId() {
        if (txIdWAL.isEmpty()) {
            return -1;
        }
        return txIdWAL.last().txId;
    }

    public long lowestTxId() {
        PartitionDelta partitionDelta = merging.get();
        if (partitionDelta != null) {
            long lowestTxId = partitionDelta.lowestTxId();
            if (lowestTxId >= 0) {
                return lowestTxId;
            }
        }

        if (txIdWAL.isEmpty()) {
            return -1;
        }
        return txIdWAL.first().txId;
    }

    void onLoadAppendTxFp(long rowTxId, long rowFP) {
        if (txIdWAL.isEmpty() || txIdWAL.last().txId != rowTxId) {
            txIdWAL.add(new TxFps(rowTxId, new long[] { rowFP }));
        } else {
            txIdWAL.onLoadAddFpToTail(rowFP);
        }
    }

    void appendTxFps(long rowTxId, long[] rowFPs) {
        txIdWAL.add(new TxFps(rowTxId, rowFPs));
        updatesSinceLastHighwaterFlush.addAndGet(rowFPs.length);
    }

    public boolean takeRowsFromTransactionId(long transactionId, RowStream rowStream) throws Exception {
        PartitionDelta partitionDelta = merging.get();
        if (partitionDelta != null) {
            if (!partitionDelta.takeRowsFromTransactionId(transactionId, rowStream)) {
                return false;
            }
        }

        if (txIdWAL.isEmpty() || txIdWAL.last().txId < transactionId) {
            return true;
        }

        return deltaWAL.takeRows(txFpsStream -> txIdWAL.streamFromTxId(transactionId, false, txFpsStream), rowStream);
    }

    long merge(PartitionIndex partitionIndex) throws Exception {
        final PartitionDelta merge = merging.get();
        long merged = 0;
        if (merge != null) {
            if (!merge.txIdWAL.isEmpty()) {
                merged = merge.size();
                try {
                    PartitionStore partitionStore = partitionIndex.get(merge.versionedPartitionName);
                    long highestTxId = partitionStore.highestTxId();
                    LOG.info("Merging ({}) deltas for partition: {} from tx: {}", merge.orderedIndex.size(), merge.versionedPartitionName, highestTxId);
                    LOG.debug("Merging keys: {}", merge.orderedIndex.keySet());
                    MutableBoolean eos = new MutableBoolean(false);
                    merge.txIdWAL.streamFromTxId(highestTxId, true, txFps -> {
                        long txId = txFps.txId;
                        partitionStore.merge(txId,
                            (highwaters, scan) -> WALKey.decompose(
                                txFpRawKeyValueStream -> merge.deltaWAL.hydrateKeyValueHighwaters(
                                    fpStream -> {
                                        for (long fp : txFps.fps) {
                                            if (!fpStream.stream(fp)) {
                                                return false;
                                            }
                                        }
                                        return true;
                                    },
                                    (fp, prefix, key, value, valueTimestamp, valueTombstone, highwater) -> {
                                        // prefix is the partitionName and is discarded
                                        WALPointer pointer = merge.orderedIndex.get(key);
                                        if (pointer == null) {
                                            throw new RuntimeException("Delta WAL missing" +
                                                " prefix: " + Arrays.toString(prefix) +
                                                " key: " + Arrays.toString(key) +
                                                " for: " + versionedPartitionName);
                                        }
                                        if (pointer.getFp() == fp) {
                                            if (!txFpRawKeyValueStream.stream(txId, fp, key, value, valueTimestamp, valueTombstone, null)) {
                                                return false;
                                            }
                                            if (highwater != null) {
                                                highwaters.highwater(highwater);
                                            }
                                        }
                                        return true;
                                    }),
                                (_txId, fp, prefix, key, value, valueTimestamp, valueTombstoned, row) -> {
                                    if (!scan.row(txId, prefix, key, value, valueTimestamp, valueTombstoned)) {
                                        eos.setValue(true);
                                        return false;
                                    }
                                    return true;
                                }));
                        if (eos.booleanValue()) {
                            return false;
                        }
                        return true;
                    });
                    partitionStore.getWalStorage().commitIndex();
                    LOG.info("Merged deltas for {}", merge.versionedPartitionName);
                } catch (Throwable ex) {
                    throw new RuntimeException("Error while streaming entry set.", ex);
                }
            }
        }
        merging.set(null);
        return merged;
    }

    private static final Comparator<TxFps> txFpsComparator = (o1, o2) -> Longs.compare(o1.txId, o2.txId);

    private static class AppendOnlyConcurrentArrayList {

        private volatile TxFps[] array;
        private volatile int length;

        public AppendOnlyConcurrentArrayList(int initialCapacity) {
            this.array = new TxFps[Math.max(initialCapacity, 1)];
        }

        public void onLoadAddFpToTail(long fp) {
            long[] existing = array[length - 1].fps;
            long[] extended = new long[existing.length + 1];
            System.arraycopy(existing, 0, extended, 0, existing.length);
            extended[existing.length] = fp;
            array[length - 1].fps = extended;
        }

        public void add(TxFps txFps) {
            synchronized (this) {
                if (length > 0 && txFps.txId <= array[length - 1].txId) {
                    throw new IllegalStateException("Appending txIds out of order: " + txFps.txId + " <= " + array[length - 1].txId);
                }
                if (length == array.length) {
                    TxFps[] doubled = new TxFps[array.length * 2];
                    System.arraycopy(array, 0, doubled, 0, array.length);
                    array = doubled;
                }
                array[length] = txFps;
                length++;
            }
        }

        public boolean streamFromTxId(long txId, boolean inclusive, TxFpsStream txFpsStream) throws Exception {
            TxFps[] array;
            int length;
            synchronized (this) {
                array = this.array;
                length = this.length;
            }
            int index = Arrays.binarySearch(array, 0, length, new TxFps(txId, null), txFpsComparator);
            if (index >= 0 && !inclusive) {
                index++;
            } else if (index < 0) {
                index = -(index + 1);
            }
            while (true) {
                for (int i = index; i < length; i++) {
                    if (!txFpsStream.stream(array[i])) {
                        return false;
                    }
                }
                int latestLength;
                synchronized (this) {
                    latestLength = this.length;
                    array = this.array;
                }
                if (latestLength != length) {
                    length = latestLength;
                } else {
                    break;
                }
            }
            return true;
        }

        public boolean isEmpty() {
            synchronized (this) {
                return length == 0;
            }
        }

        public TxFps first() {
            return array[0];
        }

        public TxFps last() {
            TxFps[] array;
            int length;
            synchronized (this) {
                array = this.array;
                length = this.length;
            }
            return array[length - 1];
        }
    }
}
