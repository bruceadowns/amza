package com.jivesoftware.os.amza.lsm.lab;

import com.google.common.primitives.UnsignedBytes;
import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.amza.lsm.lab.api.GetRaw;
import com.jivesoftware.os.amza.lsm.lab.api.NextRawEntry;
import com.jivesoftware.os.amza.lsm.lab.api.RawEntryStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListMap;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class MergableIndexsNGTest {

    @Test(enabled = true)
    public void testTx() throws Exception {

        ConcurrentSkipListMap<byte[], byte[]> desired = new ConcurrentSkipListMap<>(UnsignedBytes.lexicographicalComparator());

        int count = 3;
        int step = 100;
        int indexes = 4;

        MergeableIndexes indexs = new MergeableIndexes();
        long time = System.currentTimeMillis();
        System.out.println("Seed:" + time);
        Random rand = new Random(1446914103456L);
        for (int wi = 0; wi < indexes; wi++) {

            File indexFiler = File.createTempFile("a-index-" + wi, ".tmp");

            LeapsAndBoundsIndex walIndex = new LeapsAndBoundsIndex(new IndexFile(indexFiler.getAbsolutePath(), "rw", false),
                64, 2);

            IndexTestUtils.append(rand, walIndex, 0, step, count, desired);
            indexs.append(walIndex);
        }

        assertions(indexs, count, step, desired);

        indexs.merge(2, () -> {
            File indexFiler = File.createTempFile("a-index-merged", ".tmp");

            return new LeapsAndBoundsIndex(new IndexFile(indexFiler.getAbsolutePath(), "rw", false), 64, 2);
        }, (index) -> {
            return index;
        });

        assertions(indexs, count, step, desired);
    }

    private void assertions(MergeableIndexes indexs,
        int count, int step,
        ConcurrentSkipListMap<byte[], byte[]> desired) throws
        Exception {

        ArrayList<byte[]> keys = new ArrayList<>(desired.navigableKeySet());

        int[] index = new int[1];
        NextRawEntry rowScan = indexs.rowScan();
        RawEntryStream stream = (rawEntry, offset, length) -> {
            System.out.println("Expected:key:" + UIO.bytesLong(keys.get(index[0])) + " Found:" + SimpleRawEntry.toString(rawEntry));
            Assert.assertEquals(UIO.bytesLong(keys.get(index[0])), SimpleRawEntry.key(rawEntry));
            index[0]++;
            return true;
        };
        while (rowScan.next(stream));

        System.out.println("rowScan PASSED");

        for (int i = 0; i < count * step; i++) {
            long k = i;
            GetRaw getPointer = indexs.get();
            byte[] key = UIO.longBytes(k);
            stream = (rawEntry, offset, length) -> {
                if (rawEntry != null) {
                    System.out.println("Got: "+SimpleRawEntry.toString(rawEntry));
                    byte[] rawKey = UIO.longBytes(SimpleRawEntry.key(rawEntry));
                    Assert.assertEquals(rawKey, key);
                    byte[] d = desired.get(key);
                    if (d == null) {
                        Assert.fail();
                    } else {
                        Assert.assertEquals(SimpleRawEntry.value(rawEntry), SimpleRawEntry.value(d));
                    }
                } else {
                    Assert.assertFalse(desired.containsKey(key));
                }
                return rawEntry != null;
            };

            getPointer.get(key, stream);
        }

        System.out.println("getPointer PASSED");

        for (int i = 0; i < keys.size() - 3; i++) {
            int _i = i;

            int[] streamed = new int[1];
            stream = (rawEntry, offset, length) -> {
                if (SimpleRawEntry.value(rawEntry) > -1) {
                    System.out.println("Streamed:" + SimpleRawEntry.toString(rawEntry));
                    streamed[0]++;
                }
                return true;
            };

            System.out.println("Asked:" + UIO.bytesLong(keys.get(_i)) + " to " + UIO.bytesLong(keys.get(_i + 3)));
            NextRawEntry rangeScan = indexs.rangeScan(keys.get(_i), keys.get(_i + 3));
            while (rangeScan.next(stream));
            Assert.assertEquals(3, streamed[0]);

        }

        System.out.println("rangeScan PASSED");

        for (int i = 0; i < keys.size() - 3; i++) {
            int _i = i;
            int[] streamed = new int[1];
            stream = (rawEntry, offset, length) -> {
                if (SimpleRawEntry.value(rawEntry) > -1) {
                    streamed[0]++;
                }
                return true;
            };
            NextRawEntry rangeScan = indexs.rangeScan(UIO.longBytes(UIO.bytesLong(keys.get(_i)) + 1), keys.get(_i + 3));
            while (rangeScan.next(stream));
            Assert.assertEquals(2, streamed[0]);

        }

        System.out.println("rangeScan2 PASSED");
    }

}
