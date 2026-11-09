package com.smlc.gdirectandroid;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Per-Android-Network Google frontend ranking and UI preference.
 * Scan results contain no OAuth credentials. Cached for up to 60 minutes,
 * keyed by getNetworkHandle(); stale handles cannot authorize a direct path.
 * The native layer revalidates the destination against goog-minus-cloud CIDRs
 * and always performs TLS hostname verification for www.googleapis.com.
 */
public final class EndpointPool {
    private static final String[] INITIAL = {
        "142.250.27.100", "108.177.9.138", "64.233.160.100"
    };
    private static final long TTL_MS = 60 * 60 * 1000L;
    private final SharedPreferences storage;

    private static final class Candidate {
        final String ip;
        final long latency;
        final double speed;
        Candidate(String ip, long latency, double speed) {
            this.ip = ip; this.latency = latency; this.speed = speed;
        }
    }
    public EndpointPool(Context context) {
        storage = context.getSharedPreferences("gdirect-google-ip-rank-v1", Context.MODE_PRIVATE);
    }

    private static boolean validIp(String ip) {
        if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
        String[] parts = ip.split("\\.");
        for (String part : parts) {
            int n = Integer.parseInt(part);
            if (n < 0 || n > 255) return false;
        }
        return true;
    }
    private String key(long networkHandle) { return "route-" + networkHandle; }
    private String preferenceKey(long networkHandle) { return "manual-" + networkHandle; }

    private JSONObject load(long handle) {
        String source = storage.getString(key(handle), null);
        if (source == null) return null;
        try {
            JSONObject record = new JSONObject(source);
            long when = record.optLong("saved_at", 0L);
            long elapsed = System.currentTimeMillis() - when;
            if (elapsed < 0 || elapsed > TTL_MS) return null;
            if (record.optLong("network_handle", -1L) != handle) return null;
            return record;
        } catch (Exception ignored) {
            return null;
        }
    }
    public synchronized int accept(long networkHandle, JSONObject scan) throws Exception {
        if (scan.has("error")) throw new Exception(scan.optString("error"));
        if (scan.optBoolean("cancelled") || scan.optInt("success") <= 0) {
            return 0; // Keep a previous known-good ranking.
        }
        if (scan.optLong("network_handle", -1L) != networkHandle) {
            throw new Exception("扫描时网络发生变化，拒绝套用到其他 Network");
        }
        JSONArray originals = scan.optJSONArray("winners");
        if (originals == null || originals.length() == 0) return 0;
        JSONArray safe = new JSONArray();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < originals.length() && safe.length() < 16; i++) {
            JSONObject candidate = originals.optJSONObject(i);
            if (candidate == null) continue;
            String ip = candidate.optString("ip", "");
            if (!validIp(ip) || !seen.add(ip)) continue;
            safe.put(new JSONObject()
                .put("ip", ip)
                .put("latency_ms", Math.max(0L, candidate.optLong("latency_ms", 999999L)))
                .put("speed_kib_s", Math.max(0.0, candidate.optDouble("speed_kib_s", 0.0))));
        }
        if (safe.length() == 0) return 0;
        JSONObject record = new JSONObject()
            .put("saved_at", System.currentTimeMillis())
            .put("network_handle", networkHandle)
            .put("winners", safe);
        storage.edit().putString(key(networkHandle), record.toString()).apply();
        storage.edit().remove(preferenceKey(networkHandle)).apply();
        return safe.length();
    }

    private List<Candidate> candidates(long handle) {
        List<Candidate> found = new ArrayList<>();
        JSONObject item = load(handle);
        if (item == null) return found;
        JSONArray arr = item.optJSONArray("winners");
        if (arr == null) return found;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String ip = o.optString("ip");
            if (validIp(ip)) found.add(
                new Candidate(ip, o.optLong("latency_ms", 99999),
                    o.optDouble("speed_kib_s", 0.0)));
        }
        return found;
    }

    public synchronized String[] selectionOptions(long handle, boolean speedMode) {
        List<Candidate> found = sorted(handle, speedMode);
        List<String> labels = new ArrayList<>();
        labels.add("自动优选（按当前策略）");
        for (Candidate c : found) {
            labels.add(c.ip + " · " + c.latency + " ms · "
                + String.format(Locale.ROOT, "%.1f KiB/s", c.speed));
        }
        return labels.toArray(new String[0]);
    }
    private List<Candidate> sorted(long handle, boolean speedMode) {
        List<Candidate> found = candidates(handle);
        if (speedMode) {
            found.sort(Comparator.comparingDouble((Candidate x) -> x.speed)
                .reversed().thenComparingLong(x -> x.latency));
        } else {
            found.sort(Comparator.comparingLong((Candidate x) -> x.latency)
                .thenComparing(Comparator.comparingDouble((Candidate x) -> x.speed).reversed()));
        }
        return found;
    }
    public synchronized int preferredPosition(long handle, boolean speedMode) {
        String chosen = storage.getString(preferenceKey(handle), null);
        if (chosen == null) return 0;
        List<Candidate> list = sorted(handle, speedMode);
        for (int index = 0; index < list.size(); index++) {
            if (chosen.equals(list.get(index).ip)) return index + 1;
        }
        return 0;
    }
    public synchronized void manuallySelect(long handle, String choice, boolean speedMode) {
        if (choice == null || choice.isEmpty()) {
            storage.edit().remove(preferenceKey(handle)).apply();
            return;
        }
        boolean exists = sorted(handle, speedMode).stream().anyMatch(x -> choice.equals(x.ip));
        if (exists) storage.edit().putString(preferenceKey(handle), choice).apply();
    }
    public synchronized String[] addresses(long handle, boolean speedMode) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        String preferred = storage.getString(preferenceKey(handle), null);
        List<Candidate> known = sorted(handle, speedMode);
        if (preferred != null && known.stream().anyMatch(x -> x.ip.equals(preferred))) {
            result.add(preferred);
        }
        for (Candidate c : known) {
            result.add(c.ip);
            if (result.size() >= 8) break;
        }
        for (String ip : INITIAL) {
            if (result.size() >= 8) break;
            result.add(ip);
        }
        return result.toArray(new String[0]);
    }
    public synchronized String summary(long handle, boolean speedMode) {
        List<Candidate> found = sorted(handle, speedMode);
        if (found.isEmpty()) {
            return "当前 Network 尚未完成扩展扫描，暂用 3 个真机验证过的 Google IP。";
        }
        StringBuilder out = new StringBuilder();
        out.append("已缓存 ").append(found.size()).append(" 个已验证 Google IP\n");
        int rank = 0;
        for (Candidate item : found) {
            if (++rank > 10) break;
            out.append(rank).append(". ").append(item.ip)
                .append(" · TLS+Drive ").append(item.latency).append(" ms")
                .append(" · 文档 ").append(String.format(Locale.ROOT, "%.1f", item.speed))
                .append(" KiB/s\n");
        }
        return out.toString();
    }
}
