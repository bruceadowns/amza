package com.jivesoftware.os.amza.service.storage;

import com.jivesoftware.os.amza.shared.TimestampedValue;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Not thread safe. Each Thread should get their own IsolatedUpdatableMap.
 *
 * @param <K>
 * @param <V>
 */
class IsolatedChanges<K, V> {

    private final ConcurrentSkipListMap<K, TimestampedValue<V>> writeMap;
    private final ConcurrentSkipListMap<K, TimestampedValue<V>> changes;
    private final long timestamp;

    IsolatedChanges(ConcurrentSkipListMap<K, TimestampedValue<V>> writeMap, long timestamp) {
        this.writeMap = writeMap;
        this.timestamp = timestamp;
        this.changes = new ConcurrentSkipListMap<>();
    }

    ConcurrentSkipListMap<K, TimestampedValue<V>> getChangesMap() {
        return changes;
    }

    public Set<Map.Entry<K, TimestampedValue<V>>> getWritableEntrySet() throws IOException {
        return writeMap.entrySet();
    }

    public boolean containsKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null.");
        }
        return writeMap.containsKey(key);
    }

    public V getValue(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null.");
        }
        TimestampedValue<V> got = writeMap.get(key);
        if (got == null || got.getTombstoned()) {
            return null;
        }
        return got.getValue();
    }

    public TimestampedValue<V> getTimestampedValue(K key) {
        if (key == null) {
            return null;
        }
        return writeMap.get(key);
    }

    public boolean put(K key, V value) throws IOException {
        if (key == null) {
            throw new IllegalArgumentException("key cannot be null.");
        }
        long putTimestamp = timestamp + 1;
        TimestampedValue<V> update = new TimestampedValue<>(value, putTimestamp, false);
        TimestampedValue<V> current = writeMap.get(key);
        if (current == null || current.getTimestamp() < update.getTimestamp()) {
            writeMap.put(key, update);
            changes.put(key, update);
            return true;
        }
        return false;
    }

    public boolean remove(K key) throws IOException {
        if (key == null) {
            return false;
        }
        long removeTimestamp = timestamp;
        TimestampedValue<V> current = writeMap.get(key);
        V value = (current != null) ? current.getValue() : null;
        TimestampedValue<V> update = new TimestampedValue<>(value, removeTimestamp, true);
        if (current == null || current.getTimestamp() < update.getTimestamp()) {
            writeMap.put(key, update);
            changes.put(key, update);
            return true;
        }
        return false;
    }
}