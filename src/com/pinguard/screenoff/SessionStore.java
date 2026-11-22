package com.pinguard.screenoff;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SessionStore {
    private static final String PREFS = "pinguard_prefs";
    private static final String KEY_SESSIONS = "sessions_json";
    private static final String KEY_PENDING = "pending_snapshot";
    private static final String KEY_ENABLED = "monitor_enabled";
    private static final String KEY_BLOCKED_PKGS = "blocked_packages";
    private static final String KEY_FIREWALL_ON = "firewall_enabled";
    private static final int MAX_SESSIONS = 30;

    public static class AppDrainItem {
        public String packageName;
        public String appLabel;
        public long fgsSeconds;
        public long mobileBytes;
        public long wifiBytes;
        public int eventCount;
        public double score;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("pkg", packageName);
                o.put("label", appLabel);
                o.put("fgs", fgsSeconds);
                o.put("mob", mobileBytes);
                o.put("wifi", wifiBytes);
                o.put("ev", eventCount);
                o.put("sc", score);
            } catch (Exception ignored) {}
            return o;
        }

        public static AppDrainItem fromJson(JSONObject o) {
            AppDrainItem it = new AppDrainItem();
            it.packageName = o.optString("pkg", "");
            it.appLabel = o.optString("label", it.packageName);
            it.fgsSeconds = o.optLong("fgs", 0);
            it.mobileBytes = o.optLong("mob", 0);
            it.wifiBytes = o.optLong("wifi", 0);
            it.eventCount = o.optInt("ev", 0);
            it.score = o.optDouble("sc", 0);
            return it;
        }
    }

    public static class SessionRecord {
        public long startMs;
        public long endMs;
        public int startLevel;
        public int endLevel;
        public int startMah;
        public int endMah;
        public float startTemp;
        public float endTemp;
        public boolean isManualScan;
        public boolean wasCharging;
        public List<AppDrainItem> apps = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("sMs", startMs);
                o.put("eMs", endMs);
                o.put("sLv", startLevel);
                o.put("eLv", endLevel);
                o.put("sMah", startMah);
                o.put("eMah", endMah);
                o.put("sTmp", startTemp);
                o.put("eTmp", endTemp);
                o.put("man", isManualScan);
                o.put("chg", wasCharging);
                JSONArray arr = new JSONArray();
                for (AppDrainItem a : apps) arr.put(a.toJson());
                o.put("apps", arr);
            } catch (Exception ignored) {}
            return o;
        }

        public static SessionRecord fromJson(JSONObject o) {
            SessionRecord r = new SessionRecord();
            r.startMs = o.optLong("sMs", 0);
            r.endMs = o.optLong("eMs", 0);
            r.startLevel = o.optInt("sLv", 0);
            r.endLevel = o.optInt("eLv", 0);
            r.startMah = o.optInt("sMah", 0);
            r.endMah = o.optInt("eMah", 0);
            r.startTemp = (float) o.optDouble("sTmp", 0);
            r.endTemp = (float) o.optDouble("eTmp", 0);
            r.isManualScan = o.optBoolean("man", false);
            r.wasCharging = o.optBoolean("chg", false);
            JSONArray arr = o.optJSONArray("apps");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.optJSONObject(i);
                    if (item != null) r.apps.add(AppDrainItem.fromJson(item));
                }
            }
            return r;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isMonitorEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, true);
    }

    public static void setMonitorEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static void savePendingSnapshot(Context ctx, DrainAnalyzer.BatterySnapshot snap) {
        JSONObject o = new JSONObject();
        try {
            o.put("ts", snap.timestampMs);
            o.put("lv", snap.level);
            o.put("mah", snap.chargeCounterMah);
            o.put("tmp", snap.tempC);
            o.put("chg", snap.isCharging);
        } catch (Exception ignored) {}
        prefs(ctx).edit().putString(KEY_PENDING, o.toString()).apply();
    }

    public static DrainAnalyzer.BatterySnapshot popPendingSnapshot(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String raw = p.getString(KEY_PENDING, null);
        if (raw == null) return null;
        p.edit().remove(KEY_PENDING).apply();
        try {
            JSONObject o = new JSONObject(raw);
            DrainAnalyzer.BatterySnapshot s = new DrainAnalyzer.BatterySnapshot();
            s.timestampMs = o.optLong("ts", 0);
            s.level = o.optInt("lv", 0);
            s.chargeCounterMah = o.optInt("mah", 0);
            s.tempC = (float) o.optDouble("tmp", 0);
            s.isCharging = o.optBoolean("chg", false);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    public static List<SessionRecord> loadSessions(Context ctx) {
        List<SessionRecord> list = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_SESSIONS, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) list.add(SessionRecord.fromJson(o));
            }
        } catch (Exception ignored) {}
        return list;
    }

    public static void addSession(Context ctx, SessionRecord rec) {
        List<SessionRecord> list = loadSessions(ctx);
        list.add(0, rec);
        while (list.size() > MAX_SESSIONS) {
            list.remove(list.size() - 1);
        }
        JSONArray arr = new JSONArray();
        for (SessionRecord r : list) arr.put(r.toJson());
        prefs(ctx).edit().putString(KEY_SESSIONS, arr.toString()).apply();
    }

    public static void clearSessions(Context ctx) {
        prefs(ctx).edit().putString(KEY_SESSIONS, "[]").apply();
    }

    public static boolean isFirewallEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_FIREWALL_ON, false);
    }

    public static void setFirewallEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_FIREWALL_ON, enabled).apply();
    }

    public static java.util.Set<String> getBlockedPackages(Context ctx) {
        java.util.Set<String> saved = prefs(ctx).getStringSet(KEY_BLOCKED_PKGS, null);
        return (saved == null) ? new java.util.HashSet<String>() : new java.util.HashSet<String>(saved);
    }

    public static boolean isBlocked(Context ctx, String pkg) {
        return getBlockedPackages(ctx).contains(pkg);
    }

    public static boolean toggleBlockedPackage(Context ctx, String pkg) {
        java.util.Set<String> set = getBlockedPackages(ctx);
        boolean nowBlocked;
        if (set.contains(pkg)) {
            set.remove(pkg);
            nowBlocked = false;
        } else {
            set.add(pkg);
            nowBlocked = true;
        }
        prefs(ctx).edit().putStringSet(KEY_BLOCKED_PKGS, set).apply();
        return nowBlocked;
    }
}
