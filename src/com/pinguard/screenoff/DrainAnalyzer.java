package com.pinguard.screenoff;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DrainAnalyzer {

    public static class BatterySnapshot {
        public long timestampMs;
        public int level;
        public int chargeCounterMah;
        public int voltageMv;
        public float tempC;
        public int currentMa;
        public boolean isCharging;
    }

    public static boolean hasUsageStatsPermission(Context ctx) {
        try {
            AppOpsManager appOps = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    ctx.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    public static BatterySnapshot captureBattery(Context ctx) {
        BatterySnapshot s = new BatterySnapshot();
        s.timestampMs = System.currentTimeMillis();
        try {
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                int chargeUah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (chargeUah > 0) s.chargeCounterMah = chargeUah / 1000;
                int currUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                s.currentMa = currUa / 1000;
                s.level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            }
            Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int lv = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (lv >= 0 && sc > 0) s.level = (lv * 100) / sc;
                s.voltageMv = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                s.tempC = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0f;
                int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                s.isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception ignored) {}
        return s;
    }

    private static class Accum {
        long fgsMs = 0;
        long lastFgsStart = -1;
        long mobileBytes = 0;
        long wifiBytes = 0;
        int events = 0;
    }

    public static List<SessionStore.AppDrainItem> analyzeWindow(Context ctx, long startMs, long endMs) {
        Map<String, Accum> map = new HashMap<>();
        String selfPkg = ctx.getPackageName();
        PackageManager pm = ctx.getPackageManager();

        // 1. UsageEvents: Foreground Services & background events during [startMs, endMs]
        try {
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm != null) {
                UsageEvents ue = usm.queryEvents(startMs, endMs);
                UsageEvents.Event ev = new UsageEvents.Event();
                while (ue != null && ue.hasNextEvent()) {
                    ue.getNextEvent(ev);
                    String pkg = ev.getPackageName();
                    if (pkg == null || pkg.equals(selfPkg) || pkg.equals("android")) continue;
                    Accum a = map.get(pkg);
                    if (a == null) {
                        a = new Accum();
                        map.put(pkg, a);
                    }
                    int type = ev.getEventType();
                    if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                        a.lastFgsStart = ev.getTimeStamp();
                        a.events++;
                    } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) {
                        long from = (a.lastFgsStart > 0) ? a.lastFgsStart : startMs;
                        long dur = Math.max(0, ev.getTimeStamp() - from);
                        a.fgsMs += dur;
                        a.lastFgsStart = -1;
                    } else if (type == UsageEvents.Event.STANDBY_BUCKET_CHANGED
                            || type == UsageEvents.Event.USER_INTERACTION
                            || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                        a.events++;
                    }
                }
                // Close any FGS that remained running until endMs
                for (Accum a : map.values()) {
                    if (a.lastFgsStart > 0) {
                        a.fgsMs += Math.max(0, endMs - a.lastFgsStart);
                        a.lastFgsStart = -1;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. NetworkStatsManager: Mobile (4G/5G) & Wi-Fi bytes per UID strictly within [startMs, endMs]
        try {
            NetworkStatsManager nsm = (NetworkStatsManager) ctx.getSystemService(Context.NETWORK_STATS_SERVICE);
            if (nsm != null) {
                collectNetwork(nsm, pm, ConnectivityManager.TYPE_MOBILE, startMs, endMs, true, selfPkg, map);
                collectNetwork(nsm, pm, ConnectivityManager.TYPE_WIFI, startMs, endMs, false, selfPkg, map);
            }
        } catch (Exception ignored) {}

        // 3. Score and sort
        List<SessionStore.AppDrainItem> result = new ArrayList<>();
        for (Map.Entry<String, Accum> e : map.entrySet()) {
            String pkg = e.getKey();
            Accum a = e.getValue();
            long fgsSec = a.fgsMs / 1000L;
            if (fgsSec == 0 && a.mobileBytes < 4096 && a.wifiBytes < 16384 && a.events < 2) {
                continue;
            }
            double mobKB = a.mobileBytes / 1024.0;
            double wifiKB = a.wifiBytes / 1024.0;
            double score = (mobKB * 0.08) + (fgsSec * 0.6) + (wifiKB * 0.015) + (a.events * 2.0);
            if (score < 1.0) continue;

            SessionStore.AppDrainItem item = new SessionStore.AppDrainItem();
            item.packageName = pkg;
            item.appLabel = resolveLabel(pm, pkg);
            item.fgsSeconds = fgsSec;
            item.mobileBytes = a.mobileBytes;
            item.wifiBytes = a.wifiBytes;
            item.eventCount = a.events;
            item.score = score;
            result.add(item);
        }

        Collections.sort(result, new Comparator<SessionStore.AppDrainItem>() {
            @Override
            public int compare(SessionStore.AppDrainItem a, SessionStore.AppDrainItem b) {
                return Double.compare(b.score, a.score);
            }
        });

        if (result.size() > 50) {
            return new ArrayList<>(result.subList(0, 50));
        }
        return result;
    }

    private static void collectNetwork(
            NetworkStatsManager nsm,
            PackageManager pm,
            int networkType,
            long startMs,
            long endMs,
            boolean isMobile,
            String selfPkg,
            Map<String, Accum> map) {
        NetworkStats stats = null;
        try {
            stats = nsm.querySummary(networkType, null, startMs, endMs);
            if (stats == null) return;
            NetworkStats.Bucket bucket = new NetworkStats.Bucket();
            Map<Integer, Long> uidBytes = new HashMap<>();
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket);
                int uid = bucket.getUid();
                if (uid < 10000) continue; // skip kernel/root/system UIDs
                long bytes = bucket.getRxBytes() + bucket.getTxBytes();
                if (bytes <= 0) continue;
                Long prev = uidBytes.get(uid);
                uidBytes.put(uid, (prev == null ? 0L : prev) + bytes);
            }
            for (Map.Entry<Integer, Long> entry : uidBytes.entrySet()) {
                String[] pkgs = pm.getPackagesForUid(entry.getKey());
                if (pkgs == null || pkgs.length == 0) continue;
                String pkg = pkgs[0];
                if (pkg.equals(selfPkg)) continue;
                Accum a = map.get(pkg);
                if (a == null) {
                    a = new Accum();
                    map.put(pkg, a);
                }
                if (isMobile) a.mobileBytes += entry.getValue();
                else a.wifiBytes += entry.getValue();
            }
        } catch (Exception ignored) {
        } finally {
            if (stats != null) {
                try { stats.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static String resolveLabel(PackageManager pm, String pkg) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) return cs.toString();
        } catch (Exception ignored) {}
        return pkg;
    }
}
