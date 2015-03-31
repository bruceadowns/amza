package com.jivesoftware.os.amza.ui.region;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.shared.AmzaInstance;
import com.jivesoftware.os.amza.shared.AmzaRing;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.stats.AmzaStats.Totals;
import com.jivesoftware.os.amza.ui.soy.SoyRenderer;
import com.jivesoftware.os.mlogger.core.LoggerSummary;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

/**
 *
 */
// soy.page.healthPluginRegion
public class HealthPluginRegion implements PageRegion<Optional<HealthPluginRegion.HealthPluginRegionInput>> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final String template;
    private final String statsTemplate;
    private final SoyRenderer renderer;
    private final AmzaRing amzaRing;
    private final AmzaInstance amzaInstance;
    private final AmzaStats amzaStats;

    private final List<GarbageCollectorMXBean> garbageCollectors;
    private final OperatingSystemMXBean osBean;
    private final ThreadMXBean threadBean;
    private final MemoryMXBean memoryBean;
    private final RuntimeMXBean runtimeBean;

    public HealthPluginRegion(String template,
        String statsTemplate,
        SoyRenderer renderer,
        AmzaRing amzaRing,
        AmzaInstance amzaInstance,
        AmzaStats amzaStats
    ) {
        this.template = template;
        this.statsTemplate = statsTemplate;
        this.renderer = renderer;
        this.amzaRing = amzaRing;
        this.amzaInstance = amzaInstance;
        this.amzaStats = amzaStats;

        garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
        osBean = ManagementFactory.getOperatingSystemMXBean();
        threadBean = ManagementFactory.getThreadMXBean();
        memoryBean = ManagementFactory.getMemoryMXBean();
        runtimeBean = ManagementFactory.getRuntimeMXBean();

    }

    public static class HealthPluginRegionInput {

        final String cluster;
        final String host;
        final String service;

        public HealthPluginRegionInput(String cluster, String host, String service) {
            this.cluster = cluster;
            this.host = host;
            this.service = service;
        }
    }

    @Override
    public String render(Optional<HealthPluginRegionInput> optionalInput) {
        Map<String, Object> data = Maps.newHashMap();

        try {
            HealthPluginRegionInput input = optionalInput.get();

            List<Map.Entry<String, Long>> ongoingCompactions = amzaStats.ongoingCompactions();
            data.put("ongoingCompactions", (Object) Lists.transform(ongoingCompactions, new Function<Map.Entry<String, Long>, Map<String, String>>() {

                @Override
                public Map<String, String> apply(Map.Entry<String, Long> input) {
                    return ImmutableMap.of("name", input.getKey(), "elapse", String.valueOf(input.getValue()));
                }
            }));

            List<Map.Entry<String, Long>> recentCompaction = amzaStats.recentCompaction();
            data.put("recentCompactions", (Object) Lists.transform(recentCompaction, new Function<Map.Entry<String, Long>, Map<String, String>>() {

                @Override
                public Map<String, String> apply(Map.Entry<String, Long> input) {
                    return ImmutableMap.of("name", input.getKey(), "elapse", String.valueOf(input.getValue()));
                }
            }));
            data.put("totalCompactions", String.valueOf(amzaStats.getTotalCompactions()));

        } catch (Exception e) {
            log.error("Unable to retrieve data", e);
        }

        return renderer.render(template, data);
    }

    public String renderStats() {
        Map<String, Object> data = Maps.newHashMap();
        try {
            data.put("grandTotals", regionTotals(null, amzaStats.getGrandTotal()));
            List<Map<String, Object>> regionTotals = new ArrayList<>();
            ArrayList<RegionName> regions = new ArrayList<>(amzaInstance.getRegionNames());
            Collections.sort(regions);
            for (RegionName regionName : regions) {
                Totals totals = amzaStats.getRegionTotals().get(regionName);
                if (totals == null) {
                    totals = new Totals();
                }
                regionTotals.add(regionTotals(regionName, totals));
            }
            data.put("regionTotals", regionTotals);
        } catch (Exception e) {
            log.error("Unable to retrieve data", e);
        }
        return renderer.render(statsTemplate, data);
    }

    public Map<String, Object> regionTotals(RegionName name, AmzaStats.Totals totals) throws Exception {
        Map<String, Object> map = new HashMap<>();
        if (name != null) {
            map.put("name", name.getRegionName());
            map.put("ringName", name.getRingName());
            List<RingHost> ring = amzaRing.getRing(name.getRingName());
            List<Map<String, String>> ringMaps = new ArrayList<>();
            for (RingHost r : ring) {
                ringMaps.add(ImmutableMap.of("host", r.getHost(), "port", String.valueOf(r.getPort())));
            }
            map.put("ring", ringMaps);
        }
        map.put("received", String.valueOf(totals.received.get()));
        map.put("receivedLag", String.valueOf(totals.receivedLag.get()));
        map.put("applies", String.valueOf(totals.receivedApplies.get()));
        map.put("appliesLag", String.valueOf(totals.receivedAppliesLag.get()));
        map.put("gets", String.valueOf(totals.gets.get()));
        map.put("getsLag", String.valueOf(totals.getsLag.get()));
        map.put("scans", String.valueOf(totals.scans.get()));
        map.put("scansLag", String.valueOf(totals.scansLag.get()));
        map.put("directApplies", String.valueOf(totals.directApplies.get()));
        map.put("directAppliesLag", String.valueOf(totals.directAppliesLag.get()));
        map.put("replicates", String.valueOf(totals.replicates.get()));
        map.put("replicatesLag", String.valueOf(totals.replicatesLag.get()));
        map.put("takes", String.valueOf(totals.takes.get()));
        map.put("takesLag", String.valueOf(totals.takesLag.get()));
        map.put("takeApplies", String.valueOf(totals.takeApplies.get()));
        map.put("takeAppliesLag", String.valueOf(totals.takeAppliesLag.get()));

        return map;
    }

    public String renderOverview() throws Exception {

        StringBuilder sb = new StringBuilder();
        sb.append("<p>uptime<span class=\"badge\">" + getDurationBreakdown(runtimeBean.getUptime()) + "</span>");
        sb.append("&nbsp&nbsp&nbsp&nbspdiskR<span class=\"badge\">" + humanReadableByteCount(amzaStats.ioStats.read.get(), false) + "</span>");
        sb.append("&nbsp&nbsp&nbsp&nbspdiskW<span class=\"badge\">" + humanReadableByteCount(amzaStats.ioStats.wrote.get(), false) + "</span>");
        sb.append("&nbsp&nbsp&nbsp&nbspnetR<span class=\"badge\">" + humanReadableByteCount(amzaStats.netStats.read.get(), false) + "</span>");
        sb.append("&nbsp&nbsp&nbsp&nbspnetW<span class=\"badge\">" + humanReadableByteCount(amzaStats.netStats.wrote.get(), false) + "</span>");

        double processCpuLoad = getProcessCpuLoad();
        sb.append(progress("CPU",
            (int) (processCpuLoad),
            String.valueOf(processCpuLoad) + " cpu load"));

        double memoryLoad = (double) memoryBean.getHeapMemoryUsage().getUsed() / (double) memoryBean.getHeapMemoryUsage().getMax();
        sb.append(progress("Heap",
            (int) (memoryLoad * 100),
            String.valueOf(humanReadableByteCount(memoryBean.getHeapMemoryUsage().getUsed(), false)
                + " used out of " + humanReadableByteCount(memoryBean.getHeapMemoryUsage().getMax(), false))));

        long s = 0;
        for (GarbageCollectorMXBean gc : garbageCollectors) {
            s += gc.getCollectionTime();
        }
        double gcLoad = (double) s / (double) runtimeBean.getUptime();
        sb.append(progress("GC",
            (int) (gcLoad * 100),
            getDurationBreakdown(s) + " total gc"));

        Totals grandTotal = amzaStats.getGrandTotal();

        sb.append(progress("Gets (" + grandTotal.gets + ")",
            (int) (((double) grandTotal.getsLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.getsLag.longValue())) + " lag"));

        sb.append(progress("Scans (" + grandTotal.scans + ")",
            (int) (((double) grandTotal.scansLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.scansLag.longValue())) + " lag"));

        sb.append(progress("Direct Applied (" + grandTotal.directApplies + ")",
            (int) (((double) grandTotal.directAppliesLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.directAppliesLag.longValue())) + " lag"));

        sb.append(progress("Took Applied (" + grandTotal.takeApplies + ")",
            (int) (((double) grandTotal.takeAppliesLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.takeAppliesLag.longValue())) + " lag"));

        sb.append(progress("Received Applied (" + grandTotal.receivedApplies + ")",
            (int) (((double) grandTotal.receivedAppliesLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.receivedAppliesLag.longValue())) + " lag"));

        sb.append(progress("Replicated (" + grandTotal.replicates + ")",
            (int) (((double) grandTotal.replicatesLag.longValue() / 1000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.replicatesLag.longValue())) + " lag"));

        sb.append(progress("Took (" + grandTotal.takes + ")",
            (int) (((double) grandTotal.takesLag.longValue() / 10000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.takesLag.longValue())) + " lag"));

        sb.append(progress("Received (" + grandTotal.received + ")",
            (int) (((double) grandTotal.receivedLag.longValue() / 10000d) * 100),
            String.valueOf(getDurationBreakdown(grandTotal.receivedLag.longValue())) + " lag"));

        sb.append("<p><pre>");
        for (String l : LoggerSummary.INSTANCE.lastNErrors.get()) {
            sb.append("ERROR ").append(l).append("\n");
        }
        for (String l : LoggerSummary.INSTANCE.lastNWarns.get()) {
            sb.append("WARN ").append(l).append("\n");
        }
        for (String l : LoggerSummary.INSTANCE.lastNInfos.get()) {
            sb.append("INFO ").append(l).append("\n");
        }
        sb.append("</pre><p>");

        return sb.toString();
    }

    private String progress(String title, int progress, String value) {
        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("progress", progress);
        data.put("value", value);
        return renderer.render("soy.page.amzaStackedProgress", data);
    }

    public static double getProcessCpuLoad() throws MalformedObjectNameException, ReflectionException, InstanceNotFoundException {

        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = ObjectName.getInstance("java.lang:type=OperatingSystem");
        AttributeList list = mbs.getAttributes(name, new String[]{"ProcessCpuLoad"});

        if (list.isEmpty()) {
            return Double.NaN;
        }

        Attribute att = (Attribute) list.get(0);
        Double value = (Double) att.getValue();

        if (value == -1.0) {
            return 0;  // usually takes a couple of seconds before we get real values
        }
        return ((int) (value * 1000) / 10.0);        // returns a percentage value with 1 decimal point precision
    }

    @Override
    public String getTitle() {
        return "Health";
    }

    public static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    public static String getDurationBreakdown(long millis) {
        if (millis < 0) {
            return String.valueOf(millis);
        }

        long days = TimeUnit.MILLISECONDS.toDays(millis);
        millis -= TimeUnit.DAYS.toMillis(days);
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        millis -= TimeUnit.HOURS.toMillis(hours);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        millis -= TimeUnit.MINUTES.toMillis(minutes);
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis);
        millis -= TimeUnit.SECONDS.toMillis(seconds);

        StringBuilder sb = new StringBuilder(64);
        if (days > 0) {
            sb.append(days);
            sb.append(" Days ");
        }
        if (hours > 0) {
            sb.append(hours);
            sb.append(":");
        }
        if (minutes > 0) {
            sb.append(minutes);
            sb.append(":");
        }
        if (seconds > 0) {
            sb.append(seconds);
            sb.append(".");
        }
        sb.append(millis);

        return (sb.toString());
    }

}
