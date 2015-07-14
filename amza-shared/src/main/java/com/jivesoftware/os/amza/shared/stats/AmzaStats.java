package com.jivesoftware.os.amza.shared.stats;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.common.collect.Multiset;
import com.google.common.util.concurrent.AtomicDouble;
import com.jivesoftware.os.amza.shared.partition.PartitionName;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.jive.utils.ordered.id.JiveEpochTimestampProvider;
import com.jivesoftware.os.jive.utils.ordered.id.SnowflakeIdPacker;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author jonathan.colt
 */
public class AmzaStats {

    private static final SnowflakeIdPacker snowflakeIdPacker = new SnowflakeIdPacker();
    private static final JiveEpochTimestampProvider jiveEpochTimestampProvider = new JiveEpochTimestampProvider();

    private final Map<RingMember, AtomicLong> took = new ConcurrentSkipListMap<>();

    private final Map<String, Long> ongoingCompaction = new ConcurrentHashMap<>();
    private final List<Entry<String, Long>> recentCompaction = new ArrayList<>();

    private final AtomicLong totalCompactions = new AtomicLong();
    public final Map<RingMember, AtomicLong> longPolled = new ConcurrentSkipListMap<>();
    public final Map<RingMember, AtomicLong> longPollAvailables = new ConcurrentSkipListMap<>();

    private final Totals grandTotals = new Totals();
    private final Map<PartitionName, Totals> partitionTotals = new ConcurrentHashMap<>();

    public final Multiset<RingMember> takeErrors = ConcurrentHashMultiset.create();

    public final IoStats ioStats = new IoStats();
    public final NetStats netStats = new NetStats();

    public final AtomicLong addMember = new AtomicLong();
    public final AtomicLong removeMember = new AtomicLong();
    public final AtomicLong getRing = new AtomicLong();
    public final AtomicLong rowsStream = new AtomicLong();
    public final AtomicLong availableRowsStream = new AtomicLong();
    public final AtomicLong rowsTaken = new AtomicLong();
    public final AtomicLong backPressure = new AtomicLong();
    public AtomicDouble[] deltaStripeLoad = new AtomicDouble[0];

    public AmzaStats() {
    }

    public void deltaStripeLoad(int index, double load) {
        AtomicDouble[] stackCopy = deltaStripeLoad;
        if (index >= stackCopy.length) {
            AtomicDouble[] newDeltaStripeLoad = new AtomicDouble[index + 1];
            System.arraycopy(stackCopy, 0, newDeltaStripeLoad, 0, stackCopy.length);
            for (int i = 0; i < index + 1; i++) {
                if (newDeltaStripeLoad[i] == null) {
                    newDeltaStripeLoad[i] = new AtomicDouble();
                }
            }
            stackCopy = newDeltaStripeLoad;
            deltaStripeLoad = stackCopy;
        }

        stackCopy[index].set(load);

    }

    static public class Totals {

        public final AtomicLong gets = new AtomicLong();
        public final AtomicLong getsLag = new AtomicLong();
        public final AtomicLong scans = new AtomicLong();
        public final AtomicLong scansLag = new AtomicLong();
        public final AtomicLong updates = new AtomicLong();
        public final AtomicLong updatesLag = new AtomicLong();
        public final AtomicLong offers = new AtomicLong();
        public final AtomicLong offersLag = new AtomicLong();
        public final AtomicLong takes = new AtomicLong();
        public final AtomicLong takesLag = new AtomicLong();
        public final AtomicLong takeApplies = new AtomicLong();
        public final AtomicLong takeAppliesLag = new AtomicLong();
        public final AtomicLong directApplies = new AtomicLong();
        public final AtomicLong directAppliesLag = new AtomicLong();
    }

    public void longPolled(RingMember member) {
        longPolled.computeIfAbsent(member, (key) -> new AtomicLong()).incrementAndGet();
    }

    public void longPollAvailables(RingMember member) {
        longPollAvailables.computeIfAbsent(member, (key) -> new AtomicLong()).incrementAndGet();
    }

    public void took(RingMember member) {
        took.computeIfAbsent(member, (key) -> new AtomicLong()).incrementAndGet();
    }

    public long getTotalTakes(RingMember member) {
        AtomicLong got = took.get(member);
        if (got == null) {
            return 0;
        }
        return got.get();
    }

    public void beginCompaction(String name) {
        ongoingCompaction.put(name, System.currentTimeMillis());
    }

    public void endCompaction(String name) {
        Long start = ongoingCompaction.remove(name);
        totalCompactions.incrementAndGet();
        if (start != null) {
            recentCompaction.add(new AbstractMap.SimpleEntry<>(name, System.currentTimeMillis() - start));
            while (recentCompaction.size() > 30) {
                recentCompaction.remove(0);
            }
        }
    }

    public List<Entry<String, Long>> recentCompaction() {
        return recentCompaction;
    }

    public List<Entry<String, Long>> ongoingCompactions() {
        List<Entry<String, Long>> ongoing = new ArrayList<>();
        for (Entry<String, Long> e : ongoingCompaction.entrySet()) {
            ongoing.add(new AbstractMap.SimpleEntry<>(e.getKey(), System.currentTimeMillis() - e.getValue()));
        }
        return ongoing;
    }

    public long getTotalCompactions() {
        return totalCompactions.get();
    }

    public Totals getGrandTotal() {
        return grandTotals;
    }

    public void updates(RingMember from, PartitionName partitionName, int count, long smallestTxId) {
        grandTotals.updates.addAndGet(count);
        Totals totals = partitionTotals(partitionName);
        totals.updates.addAndGet(count);
        long lag = lag(smallestTxId);
        totals.updatesLag.set(lag);
        grandTotals.updatesLag.set((grandTotals.updatesLag.get() + lag) / 2);
    }

    public void offers(RingMember from, PartitionName partitionName, int count, long smallestTxId) {
        grandTotals.offers.addAndGet(count);
        Totals totals = partitionTotals(partitionName);
        totals.offers.addAndGet(count);
        long lag = lag(smallestTxId);
        totals.offersLag.set(lag);
        grandTotals.offersLag.set((grandTotals.offersLag.get() + lag) / 2);
    }

    public void took(RingMember from, PartitionName partitionName, int count, long smallestTxId) {
        grandTotals.takes.addAndGet(count);
        Totals totals = partitionTotals(partitionName);
        totals.takes.addAndGet(count);
        long lag = lag(smallestTxId);
        totals.takesLag.set(lag);
        grandTotals.takesLag.set((grandTotals.takesLag.get() + lag) / 2);
    }

    public void tookApplied(RingMember from, PartitionName partitionName, int count, long smallestTxId) {
        grandTotals.takeApplies.addAndGet(count);
        Totals totals = partitionTotals(partitionName);
        totals.takeApplies.addAndGet(count);
        long lag = lag(smallestTxId);
        totals.takeAppliesLag.set(lag);
        grandTotals.takesLag.set((grandTotals.takesLag.get() + lag) / 2);
    }

    public void direct(PartitionName partitionName, int count, long smallestTxId) {
        grandTotals.directApplies.addAndGet(count);
        Totals totals = partitionTotals(partitionName);
        totals.directApplies.addAndGet(count);
        long lag = lag(smallestTxId);
        totals.directAppliesLag.set(lag);
        grandTotals.directAppliesLag.set((grandTotals.directAppliesLag.get() + lag) / 2);
    }

    private Totals partitionTotals(PartitionName versionedPartitionName) {
        Totals got = partitionTotals.get(versionedPartitionName);
        if (got == null) {
            got = new Totals();
            partitionTotals.put(versionedPartitionName, got);
        }
        return got;
    }

    public Map<PartitionName, Totals> getPartitionTotals() {
        return partitionTotals;
    }

    long lag(RowsChanged changed) {
        return lag(changed.getOldestRowTxId());
    }

    long lag(long oldest) {
        if (oldest != Long.MAX_VALUE) {
            long[] unpack = snowflakeIdPacker.unpack(oldest);
            long lag = jiveEpochTimestampProvider.getApproximateTimestamp(System.currentTimeMillis()) - unpack[0];
            return lag;
        }
        return 0;
    }

}
