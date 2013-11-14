package com.jivesoftware.os.amza.storage.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jivesoftware.os.amza.shared.TableName;
import com.jivesoftware.os.amza.shared.TimestampedValue;
import com.jivesoftware.os.amza.storage.BasicTransactionEntry;
import com.jivesoftware.os.amza.storage.RowMarshaller;
import com.jivesoftware.os.amza.storage.TransactionEntry;
import java.util.Map;

public class StringRowMarshaller<K, V> implements RowMarshaller<K, V, String> {

    private final ObjectMapper mapper;
    private final TableName<K, V> tableName;

    public StringRowMarshaller(ObjectMapper mapper, TableName<K, V> tableName) {
        this.mapper = mapper;
        this.tableName = tableName;
    }

    @Override
    public TableName<K, V> getTableName() {
        return tableName;
    }

    @Override
    public String toRow(long orderId, Map.Entry<K, TimestampedValue<V>> e) throws JsonProcessingException {
        String transaction = Long.toString(orderId);
        String key = mapper.writeValueAsString(e.getKey());
        String timestamp = Long.toString(e.getValue().getTimestamp());
        String tombstone = Boolean.toString(e.getValue().getTombstoned());
        String value = mapper.writeValueAsString(e.getValue().getValue());

        StringBuilder sb = new StringBuilder();
        sb.append("transaction.").append(transaction.length()).append('=').append(transaction).append(',');
        sb.append("key.").append(key.length()).append('=').append(key).append(',');
        sb.append("tombstone.").append(tombstone.length()).append('=').append(tombstone).append(',');
        sb.append("timestamp.").append(timestamp.length()).append('=').append(timestamp).append(',');
        sb.append("value.").append(value.length()).append('=').append(value);
        return sb.toString();
    }

    @Override
    public TransactionEntry<K, V> fromRow(String line) throws Exception {
        StringAndOffest stringAndOffest = value(line, 0);
        long orderId = Long.parseLong(stringAndOffest.getString());
        stringAndOffest = value(line, stringAndOffest.getOffset());
        K key = mapper.readValue(stringAndOffest.getString(), tableName.getKeyClass());
        stringAndOffest = value(line, stringAndOffest.getOffset());
        boolean tombstone = Boolean.parseBoolean(stringAndOffest.getString());
        stringAndOffest = value(line, stringAndOffest.getOffset());
        long timestamp = Long.parseLong(stringAndOffest.getString());
        stringAndOffest = value(line, stringAndOffest.getOffset());
        V value = mapper.readValue(stringAndOffest.getString(), tableName.getValueClass());
        return new BasicTransactionEntry<>(orderId, key, new TimestampedValue<>(value, timestamp, tombstone));
    }

    StringAndOffest value(String line, int offest) {
        int startOfStringLength = line.indexOf('.', offest);
        int endOfStringLength = line.indexOf("=", startOfStringLength);
        int length = Integer.parseInt(line.substring(startOfStringLength + 1, endOfStringLength));
        endOfStringLength += 1;
        return new StringAndOffest(line.substring(endOfStringLength, endOfStringLength + length), endOfStringLength + length + 1);
    }

    static class StringAndOffest {

        private final String string;
        private final int offset;

        public StringAndOffest(String string, int offset) {
            this.string = string;
            this.offset = offset;
        }

        public String getString() {
            return string;
        }

        public int getOffset() {
            return offset;
        }
    }
}