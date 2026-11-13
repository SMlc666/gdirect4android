package com.smlc.gdirectandroid;

import android.content.Context;
import android.content.SharedPreferences;

/** User-owned preferences. None of these settings grant extra OAuth rights. */
public final class UserPrefs {
    private static final String FILE = "gdirect_user_preferences_v1";
    private final SharedPreferences prefs;
    public UserPrefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    private int range(String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, prefs.getInt(key, fallback)));
    }
    // Persist navigation across AppCompat day/night Activity recreation.
    public String currentTab() { return prefs.getString("current_tab", "files"); }
    public void currentTab(String tab) {
        if (tab == null) return;
        if (!tab.equals("files") && !tab.equals("transfers")
            && !tab.equals("routes") && !tab.equals("settings")) return;
        prefs.edit().putString("current_tab", tab).apply();
    }
    public void retainScroll(int index, int y) {
        if (index >= 0 && index < 4) {
            prefs.edit().putInt("scroll_" + index, Math.max(0, y)).apply();
        }
    }
    public int scroll(int index) {
        if (index < 0 || index >= 4) return 0;
        return Math.max(0, prefs.getInt("scroll_" + index, 0));
    }
    public void themeMotionPending(boolean pending) {
        prefs.edit().putBoolean("theme_motion_pending", pending).commit();
    }
    public boolean consumeThemeMotionPending() {
        boolean pending = prefs.getBoolean("theme_motion_pending", false);
        if (pending) prefs.edit().putBoolean("theme_motion_pending", false).apply();
        return pending;
    }

    public String theme() { return prefs.getString("theme", "system"); }
    public void theme(String mode) { prefs.edit().putString("theme", mode).apply(); }
    public boolean dynamicColor() { return prefs.getBoolean("dynamic_color", true); }
    public void dynamicColor(boolean enabled) { prefs.edit().putBoolean("dynamic_color", enabled).apply(); }
    public boolean animations() { return prefs.getBoolean("animations", true); }
    public void animations(boolean enabled) { prefs.edit().putBoolean("animations", enabled).apply(); }
    public int scanSize() { return range("scan_size", 384, 32, 768); }
    public void scanSize(int value) { prefs.edit().putInt("scan_size", Math.max(32, Math.min(768, value))).apply(); }
    /** Presets: quick=0, standard=1, deep=2, own=3. */
    public int scanPreset() { return range("scan_preset", 1, 0, 3); }
    public void scanPreset(int value) { prefs.edit().putInt("scan_preset",Math.max(0,Math.min(3,value))).apply(); }
    public int benchmarkCount() { return range("benchmark_count", 12, 4, 32); }
    public void benchmarkCount(int value) { prefs.edit().putInt("benchmark_count",Math.max(4,Math.min(32,value))).apply(); }
    public int scoreSpeedWeight() { return range("speed_weight", 65, 0, 100); }
    public void scoreSpeedWeight(int value) { prefs.edit().putInt("speed_weight",Math.max(0,Math.min(100,value))).apply(); }
    public int subnetDiversity() { return range("subnet_diversity", 40, 0, 100); }
    public void subnetDiversity(int value) { prefs.edit().putInt("subnet_diversity",Math.max(0,Math.min(100,value))).apply(); }
    public int scanThreads() { return range("scan_threads", 32, 4, 64); }
    public void scanThreads(int value) { prefs.edit().putInt("scan_threads", Math.max(4, Math.min(64, value))).apply(); }
    public int connections() { return range("connections", 8, 1, 16); }
    public void connections(int count) { prefs.edit().putInt("connections", Math.max(1, Math.min(16, count))).apply(); }
    public int cacheMinutes() { return range("cache_minutes", 60, 5, 1440); }
    public void cacheMinutes(int minutes) { prefs.edit().putInt("cache_minutes", Math.max(5, Math.min(1440, minutes))).apply(); }
    public int fallbackCount() { return range("fallback_count", 8, 1, 8); }
    public void fallbackCount(int count) { prefs.edit().putInt("fallback_count", Math.max(1, Math.min(8, count))).apply(); }
    public boolean startOnDownloads() { return prefs.getBoolean("download_page", true); }
    public void startOnDownloads(boolean value) { prefs.edit().putBoolean("download_page", value).apply(); }
    public long networkHandle() { return prefs.getLong("network_handle", 0L); }
    public void networkHandle(long handle) { prefs.edit().putLong("network_handle", handle).apply(); }
    public int rankMode() { return range("rank_mode", 2, 0, 2); }
    public void rankMode(int value) { prefs.edit().putInt("rank_mode", Math.max(0, Math.min(2, value))).apply(); }
    public boolean multiIp() { return prefs.getBoolean("multi_ip", false); }
    public void multiIp(boolean enabled) {
        prefs.edit().putBoolean("multi_ip",enabled).apply();
    }
    public boolean haptics() { return prefs.getBoolean("haptics", true); }
    public void haptics(boolean value) { prefs.edit().putBoolean("haptics", value).apply(); }

    /** Commit a fully validated import in one durable SharedPreferences edit. */
    boolean commitImportedSettings(String theme,boolean dynamic,boolean motion,
                                   boolean haptic,int scan,int threads,
                                   int connections,int ttl,int fallback,
                                   int rank,boolean downloadPage,boolean multiIp,
                                   int scanPreset,int benchmarkCount,int speedWeight,int diversity) {
        return prefs.edit()
            .putString("theme",theme)
            .putBoolean("dynamic_color",dynamic)
            .putBoolean("animations",motion)
            .putBoolean("haptics",haptic)
            .putInt("scan_size",scan)
            .putInt("scan_threads",threads)
            .putInt("connections",connections)
            .putInt("cache_minutes",ttl)
            .putInt("fallback_count",fallback)
            .putInt("rank_mode",rank)
            .putBoolean("download_page",downloadPage)
            .putBoolean("multi_ip",multiIp)
            .putInt("scan_preset",scanPreset)
            .putInt("benchmark_count",benchmarkCount)
            .putInt("speed_weight",speedWeight)
            .putInt("subnet_diversity",diversity)
            .commit();
    }
}
