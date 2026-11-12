package com.smlc.gdirectandroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-initiated foreground transfer, independent of an Activity lifecycle.
 *
 * No OAuth refresh token is accepted here, and the temporary access token
 * is used only inside the current process, never persisted to disk or logs.
 * No automatic retry across process death; interrupted work is reported.
 */
public final class TransferService extends Service {
    public static final String ACTION_START = "com.smlc.gdirectandroid.DOWNLOAD";
    public static final String ACTION_CANCEL = "com.smlc.gdirectandroid.CANCEL";
    private static final String CHANNEL = "gdirect_transfers";
    private static final int NOTIFY_ID = 9041;
    private static final Pattern COUNTS = Pattern.compile("(\\d+)/(\\d+)");
    private static final String HISTORY_KEY = "items";
    private static final Object MONITOR = new Object();
    private static volatile Snapshot current =
        new Snapshot("idle", "", "还没有下载任务", 0L, -1L, "", 0L);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean active;
    private boolean cancelled;
    private boolean timedOut;
    private Runnable progressTick;

    public static final class Snapshot {
        public final String status, name, detail, id;
        public final long done, total, changedAt;
        Snapshot(String status, String name, String detail, long done, long total, String id, long time) {
            this.status = status; this.name = name; this.detail = detail;
            this.done = done; this.total = total; this.id = id; changedAt = time;
        }
        public int percent() {
            if (total <= 0) return -1;
            return (int) Math.max(0, Math.min(100, done * 100.0 / total));
        }
    }

    public static Snapshot snapshot() { return current; }

    public static JSONArray history(Context context) {
        try {
            String raw = context.getSharedPreferences("gdirect_transfer_history_v1", MODE_PRIVATE)
                .getString(HISTORY_KEY, "[]");
            return new JSONArray(raw);
        } catch (Exception ignored) { return new JSONArray(); }
    }

    public static void clearHistory(Context context) {
        context.getSharedPreferences("gdirect_transfer_history_v1", MODE_PRIVATE)
            .edit().putString(HISTORY_KEY, "[]").apply();
    }

    private void update(String status, String name, String detail, long done, long total, String id) {
        current = new Snapshot(status, name, detail, done, total, id, System.currentTimeMillis());
    }

    private void record(String status, String name, String message, long size) {
        try {
            JSONArray old = history(this);
            JSONArray updated = new JSONArray();
            updated.put(new JSONObject().put("status", status).put("name", name)
                .put("detail", message).put("bytes", size)
                .put("timestamp", System.currentTimeMillis()));
            for (int i = 0; i < old.length() && i < 29; i++) updated.put(old.get(i));
            getSharedPreferences("gdirect_transfer_history_v1", MODE_PRIVATE)
                .edit().putString(HISTORY_KEY, updated.toString()).apply();
        } catch (Exception ignored) { /* No private tokens or exception dumps. */ }
    }

    public static Intent create(
        Context context, Uri target, DrivePanel.DriveFile file, String token,
        long handle, String route, String ips, int connections
    ) {
        Intent request = new Intent(context, TransferService.class).setAction(ACTION_START);
        request.putExtra("uri", target.toString());
        request.putExtra("file_id", file.id);
        request.putExtra("file_name", file.name);
        request.putExtra("size", file.size);
        request.putExtra("md5", file.md5);
        request.putExtra("token", token);
        request.putExtra("handle", handle);
        request.putExtra("route", route);
        request.putExtra("ips", ips);
        request.putExtra("connections", connections);
        request.putExtra("task", UUID.randomUUID().toString());
        return request;
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(
            CHANNEL, "gdirect 下载任务", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("文件传输进度与取消操作");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            if (!active) { stopSelfResult(startId); return START_NOT_STICKY; }
            cancelled = true;
            MainActivity.nativeCancelDownload();
            Snapshot s = current;
            update("cancelling", s.name, "正在安全停止下载…", s.done, s.total, s.id);
            notifyProgress(false);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        synchronized (MONITOR) {
            if (active) {
                // A service already owns original libaria2's global session.
                // Never let concurrent downloads overwrite the temp destination.
                record("rejected", intent.getStringExtra("file_name"),
                    "当前已有任务，请等待完成或取消", 0);
                return START_NOT_STICKY;
            }
            active = true;
        }
        cancelled = false;
        timedOut = false;
        final String name = intent.getStringExtra("file_name") == null
            ? "Google Drive 文件" : intent.getStringExtra("file_name");
        final String id = intent.getStringExtra("task") == null
            ? UUID.randomUUID().toString() : intent.getStringExtra("task");
        update("running", name, "准备原版 libaria2 下载引擎…", 0, -1, id);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFY_ID, notification(false),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFY_ID, notification(false));
            }
        } catch (RuntimeException e) {
            active = false;
            record("failed", name, "无法启动前台下载服务（请检查系统权限）", 0);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        scheduleProgress();
        final Uri destination;
        try {
            destination = Uri.parse(intent.getStringExtra("uri"));
            if (!"content".equals(destination.getScheme())) {
                throw new IllegalArgumentException("只支持 Android SAF content URI");
            }
        } catch (Exception e) {
            finish("failed", name, "无效的保存位置", 0, id, startId);
            return START_NOT_STICKY;
        }
        final String fileId = intent.getStringExtra("file_id");
        final String token = intent.getStringExtra("token");
        final String expectedMd5 = intent.getStringExtra("md5");
        final long size = intent.getLongExtra("size", -1);
        final long handle = intent.getLongExtra("handle", -1);
        final String route = intent.getStringExtra("route");
        final String ips = intent.getStringExtra("ips");
        final int connections = Math.max(1, Math.min(16, intent.getIntExtra("connections", 8)));
        if (fileId == null || token == null || ips == null || handle < 0) {
            finish("failed", name, "授权或所选网络参数不完整", 0, id, startId);
            return START_NOT_STICKY;
        }
        // Worker receives values, not the Intent itself, to avoid retaining
        // an unnecessary object containing the bearer token.
        worker.execute(() -> {
            try {
                long saved = transfer(destination, fileId, token, size, expectedMd5,
                    handle, ips, connections, id, name);
                if (cancelled || timedOut) {
                    finish("cancelled", name, "下载已取消，未提交不完整文件", 0, id, startId);
                } else {
                    finish("completed", name, "下载完成 · " + displaySize(saved)
                        + " · " + (route == null ? "已选网络" : route), saved, id, startId);
                }
            } catch (Exception e) {
                finish(cancelled ? "cancelled" : "failed", name,
                    cancelled ? "下载已取消" : shorten(e.getMessage()), 0, id, startId);
            } catch (LinkageError e) {
                finish("failed", name, "原生下载引擎加载失败", 0, id, startId);
            }
        });
        return START_NOT_STICKY;
    }

    private long transfer(Uri destination, String fileId, String token, long expectedSize,
                          String expectedMd5, long handle, String ips, int connections,
                          String taskId, String displayName) throws Exception {
        File folder = new File(getCacheDir(), "transfers");
        if (!folder.exists() && !folder.mkdirs()) throw new Exception("无法创建下载缓存目录");
        String stem = taskId.replaceAll("[^a-zA-Z0-9_-]", "");
        File result = new File(folder, stem + ".bin");
        File checkpoint = new File(folder, stem + ".bin.aria2");
        File ca = new File(getNoBackupFilesDir(), "gts-roots.pem");
        if (!ca.isFile() || ca.length() < 1000) {
            try (InputStream in = getAssets().open("gts-roots.pem");
                 OutputStream out = new FileOutputStream(ca)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
        }
        try {
            if (cancelled) throw new Exception("取消下载");
            String report = MainActivity.nativeDownloadDrive(
                fileId, token, ca.getAbsolutePath(), folder.getAbsolutePath(),
                result.getName(), handle, ips, connections);
            if (!report.startsWith("OK\n") || cancelled || timedOut) {
                throw new Exception(cancelled ? "取消下载" : shorten(report));
            }
            long actual = result.length();
            if (actual == 0) throw new Exception("下载结果为空");
            if (expectedSize >= 0 && expectedSize != actual) {
                throw new Exception("Drive 文件大小校验失败");
            }
            if (expectedMd5 != null && !expectedMd5.isEmpty()) {
                MessageDigest digest = MessageDigest.getInstance("MD5");
                try (InputStream in = new FileInputStream(result)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
                if (!hex.toString().equalsIgnoreCase(expectedMd5)) {
                    throw new Exception("Drive MD5 校验失败，已拒绝写入目标文件");
                }
            }
            if (cancelled) throw new Exception("取消下载");
            update("saving", displayName, "已验证文件完整性，保存到所选位置…",
                actual, actual, taskId);
            notifyProgress(false);
            long copied = 0;
            // SAF documents can be created before the download starts; we
            // never write incomplete network payloads into that destination.
            try (InputStream source = new FileInputStream(result);
                 OutputStream sink = getContentResolver().openOutputStream(destination, "wt")) {
                if (sink == null) throw new Exception("Android 文档提供程序拒绝写入");
                byte[] buf = new byte[65536];
                int n;
                while ((n = source.read(buf)) != -1) {
                    if (cancelled) throw new Exception("用户取消写入");
                    sink.write(buf, 0, n);
                    copied += n;
                }
                sink.flush();
            } catch (Exception e) {
                throw new Exception("保存到文档失败，目标文档可能不完整：" + shorten(e.getMessage()));
            }
            if (copied != actual) throw new Exception("保存字节数与下载文件不一致");
            return actual;
        } finally {
            if (!result.delete()) result.deleteOnExit();
            if (!checkpoint.delete()) checkpoint.deleteOnExit();
        }
    }

    private void scheduleProgress() {
        progressTick = new Runnable() {
            @Override public void run() {
                if (!active) return;
                Snapshot s = current;
                if ("running".equals(s.status)) {
                    try {
                        String raw = MainActivity.nativeDownloadProgress();
                        Matcher m = COUNTS.matcher(raw);
                        long done = 0, total = -1;
                        if (m.find()) {
                            done = Long.parseLong(m.group(1));
                            total = Long.parseLong(m.group(2));
                        }
                        update("running", s.name, raw, done, total, s.id);
                    } catch (Exception | LinkageError ignored) {
                        // Do not leak a private URL/token through notifications.
                    }
                }
                notifyProgress(false);
                main.postDelayed(this, 1100);
            }
        };
        main.post(progressTick);
    }

    private Notification notification(boolean finished) {
        Snapshot s = current;
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_tab", "transfers");
        PendingIntent openPending = PendingIntent.getActivity(
            this, 9021, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(finished ? "gdirect · 任务结束" : s.name)
            .setContentText(s.detail)
            .setContentIntent(openPending)
            .setOnlyAlertOnce(true)
            .setOngoing(!finished)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        if (!finished) {
            if (s.percent() >= 0) builder.setProgress(100, s.percent(), false);
            else builder.setProgress(100, 0, true);
            Intent cancel = new Intent(this, TransferService.class).setAction(ACTION_CANCEL);
            PendingIntent cancelPending = PendingIntent.getService(
                this, 9022, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPending);
        } else builder.setAutoCancel(true).setProgress(0, 0, false);
        return builder.build();
    }

    private void notifyProgress(boolean finished) {
        getSystemService(NotificationManager.class).notify(NOTIFY_ID, notification(finished));
    }

    private void finish(String state, String name, String message,
                        long bytes, String id, int startId) {
        // End on main thread so service and foreground notifications are
        // updated atomically with UI-visible final state.
        main.post(() -> {
            Snapshot before = current;
            update(state, name, message, bytes, bytes > 0 ? bytes : before.total, id);
            record(state, name, message, bytes);
            if (progressTick != null) main.removeCallbacks(progressTick);
            active = false;
            notifyProgress(true);
            stopForeground(STOP_FOREGROUND_REMOVE);
            // Post a normal, dismissible completion/failure notification.
            notifyProgress(true);
            stopSelfResult(startId);
        });
    }

    private static String shorten(String text) {
        if (text == null || text.trim().isEmpty()) return "未知错误";
        return text.length() > 320 ? text.substring(0, 320) : text;
    }
    private static String displaySize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    @Override public void onTimeout(int startId, int fgsType) {
        timedOut = true;
        cancelled = true;
        MainActivity.nativeCancelDownload();
        Snapshot snapshot = current;
        update("failed", snapshot.name, "系统前台下载服务运行时间已达到上限",
            snapshot.done, snapshot.total, snapshot.id);
        record("failed", snapshot.name, "系统前台下载服务运行时间已达到上限", 0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(startId);
    }
    @Override public void onDestroy() {
        if (active) MainActivity.nativeCancelDownload();
        if (progressTick != null) main.removeCallbacks(progressTick);
        worker.shutdownNow();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
