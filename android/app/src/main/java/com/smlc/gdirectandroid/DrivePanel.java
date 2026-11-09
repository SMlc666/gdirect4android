package com.smlc.gdirectandroid;

import android.app.Activity;
import android.accounts.Account;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.auth.api.identity.RevokeAccessRequest;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.auth.api.identity.AuthorizationClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Google Identity Services Authorization API + Drive v3 browser.
 * Holds only a short-lived access token in memory, never logs or persists it.
 * Google Play Services handles native Android account authorization and token
 * refresh. No embedded OAuth client secret or unsupported redirect scheme.
 */
public final class DrivePanel extends LinearLayout {
    static final int AUTH_REQUEST = 7508;
    private static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final int TEXT = Color.rgb(30, 47, 70);

    public interface DownloadHandler {
        void start(DriveFile file, String accessToken);
    }
    public static final class DriveFile {
        public final String id, name, mimeType, md5;
        public final long size;
        DriveFile(JSONObject o) throws Exception {
            id = o.getString("id");
            name = o.optString("name", id);
            mimeType = o.optString("mimeType", "");
            md5 = o.optString("md5Checksum", "");
            size = o.optLong("size", -1);
        }
        public boolean folder() { return FOLDER.equals(mimeType); }
        public boolean googleWorkspace() { return mimeType.startsWith("application/vnd.google-apps."); }
    }

    private final Activity activity;
    private final DownloadHandler onDownload;
    private final AuthorizationClient authorization;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Scope> scopes = Arrays.asList(new Scope(SCOPE));
    private final ArrayDeque<String> folders = new ArrayDeque<>();
    private String activeFolder = "root";
    private String accessToken; // in-memory only. Never expose to TextView or logs.
    private Account authorizedAccount;
    private String nextPageToken;
    private Consumer<String> pending;
    private TextView status, location;
    private EditText search;
    private LinearLayout listing;
    private boolean destroyed = false;

    public DrivePanel(Activity activity, DownloadHandler onDownload) {
        super(activity);
        this.activity = activity;
        this.onDownload = onDownload;
        this.authorization = Identity.getAuthorizationClient(activity);
        setOrientation(VERTICAL);
        setPadding(0, 20, 0, 24);
        title("Google Drive · 文件浏览与下载");
        status = title("尚未连接 Google 账号。需要 Google Play 服务，以及与 APK 包名/签名匹配的 OAuth Android 客户端。");
        Button login = button("授权只读访问 Drive");
        login.setOnClickListener(v -> withToken(token -> {
            status.setText("已获得临时授权令牌。点击「浏览文件」查看 Drive。");
        }));
        Button browse = button("浏览当前文件夹");
        browse.setOnClickListener(v -> list(false));
        Button back = button("返回上级文件夹");
        back.setOnClickListener(v -> {
            if (!folders.isEmpty()) {
                activeFolder = folders.pop();
                nextPageToken = null;
                list(false);
            }
        });
        Button disconnect = button("撤销 Google Drive 授权");
        disconnect.setOnClickListener(v -> {
            accessToken = null;
            pending = null;
            listing.removeAllViews();
            if (authorizedAccount == null) {
                status.setText("已清除本地临时访问令牌。无法取得账号标识；请在 Google 账号权限页面撤销授权。");
                return;
            }
            Account account = authorizedAccount;
            authorizedAccount = null;
            RevokeAccessRequest request = RevokeAccessRequest.builder()
                .setAccount(account)
                .setScopes(scopes)
                .build();
            status.setText("正在请求 Google 撤销 Drive 访问授权…");
            authorization.revokeAccess(request)
                .addOnSuccessListener(unused -> {
                    if (!destroyed) status.setText("已向 Google 撤销本应用的访问授权。");
                })
                .addOnFailureListener(error -> {
                    if (!destroyed) status.setText(
                        "本地令牌已清除，但 Google 授权撤销失败，请在账号权限页面检查：" + error.getMessage());
                });
        });
        search = new EditText(activity);
        search.setHint("在当前文件夹按名称搜索（留空显示全部）");
        search.setSingleLine(true);
        addView(search);
        Button runSearch = button("搜索 / 刷新");
        runSearch.setOnClickListener(v -> list(false));
        location = title("目录：根目录");
        listing = new LinearLayout(activity);
        listing.setOrientation(VERTICAL);
        addView(listing);
        Button next = button("加载下一页");
        next.setOnClickListener(v -> {
            if (nextPageToken != null) list(true);
            else status.setText("已经没有下一页。");
        });
    }

    private TextView title(String text) {
        TextView v = new TextView(activity);
        v.setTextColor(TEXT);
        v.setTextSize(15);
        v.setText(text);
        v.setPadding(0, 12, 0, 10);
        addView(v);
        return v;
    }

    private Button button(String text) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        b.setText(text);
        addView(b);
        return b;
    }

    private void withToken(Consumer<String> action) {
        if (destroyed) return;
        // Request a fresh GIS token for each operation to avoid stale tokens.
        pending = action;
        AuthorizationRequest request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .build();
        authorization.authorize(request)
            .addOnSuccessListener(value -> {
                if (destroyed) return;
                if (value.hasResolution()) {
                    try {
                        activity.startIntentSenderForResult(
                            value.getPendingIntent().getIntentSender(),
                            AUTH_REQUEST, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        pending = null;
                        status.setText("Google 账号授权界面无法启动：" + e.getMessage());
                    }
                } else {
                    accept(value);
                }
            })
            .addOnFailureListener(error -> {
                pending = null;
                status.setText("Google Identity 授权失败：" + error.getMessage()
                    + "\n请检查 Google Play 服务，以及 OAuth Android 客户端的包名和 APK 签名 SHA-1。");
            });
    }

    public boolean onActivityResult(int code, int result, Intent data) {
        if (code != AUTH_REQUEST) return false;
        if (result != Activity.RESULT_OK || data == null) {
            pending = null;
            status.setText("Google Drive 授权已取消。");
            return true;
        }
        try {
            accept(authorization.getAuthorizationResultFromIntent(data));
        } catch (ApiException e) {
            pending = null;
            status.setText("Google 授权回调出错：" + e.getStatusCode());
        }
        return true;
    }

    private void accept(AuthorizationResult value) {
        List<String> granted = value.getGrantedScopes();
        if (granted == null || !granted.contains(SCOPE)) {
            pending = null;
            accessToken = null;
            status.setText("Google 没有授予请求的 Drive 只读权限，不能继续访问文件。");
            return;
        }
        GoogleSignInAccount google = value.toGoogleSignInAccount();
        authorizedAccount = google == null ? null : google.getAccount();
        accessToken = value.getAccessToken();
        if (accessToken == null || accessToken.isEmpty()) {
            status.setText("没有取得 Google API access token；请重新授权。");
            pending = null;
            return;
        }
        Consumer<String> action = pending;
        pending = null;
        status.setText("Google 只读授权成功（临时 access token 仅保存在内存）。");
        if (action != null) action.accept(accessToken);
    }

    private void list(boolean loadNext) {
        final String pageToken = loadNext ? nextPageToken : null;
        withToken(token -> {
            final String folder = activeFolder;
            final String query = search.getText().toString().trim();
            if (!loadNext) listing.removeAllViews();
            status.setText("正在读取 Google Drive 文件列表…");
            io.execute(() -> {
                try {
                    StringBuilder q = new StringBuilder();
                    q.append("'").append(folder.replace("'", "")).append("' in parents and trashed = false");
                    if (!query.isEmpty()) {
                        q.append(" and name contains '")
                            .append(query.replace("\\", "\\\\").replace("'", "\\'")).append("'");
                    }
                    String u = "https://www.googleapis.com/drive/v3/files"
                        + "?pageSize=100&supportsAllDrives=true&includeItemsFromAllDrives=true"
                        + "&fields=" + enc("nextPageToken,incompleteSearch,files(id,name,mimeType,size,md5Checksum)")
                        + "&q=" + enc(q.toString())
                        + (pageToken == null ? "" : "&pageToken=" + enc(pageToken));
                    JSONObject data = apiGet(u, token);
                    JSONArray files = data.optJSONArray("files");
                    List<DriveFile> entries = new ArrayList<>();
                    if (files != null) {
                        for (int i = 0; i < files.length(); i++) entries.add(new DriveFile(files.getJSONObject(i)));
                    }
                    final String nextToken = data.optString("nextPageToken", "");
                    activity.runOnUiThread(() -> {
                        if (destroyed || !folder.equals(activeFolder)) return;
                        nextPageToken = nextToken.isEmpty() ? null : nextToken;
                        location.setText("目录 ID：" + activeFolder + " · 本页 " + entries.size() + " 项");
                        status.setText("列表读取完成。" + (nextPageToken == null ? "" : " 还有下一页。"));
                        for (DriveFile entry : entries) addEntry(entry);
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (destroyed) return;
                        status.setText("Drive API 请求失败：" + e.getMessage());
                    });
                }
            });
        });
    }

    private void addEntry(DriveFile file) {
        Button b = new Button(activity);
        b.setAllCaps(false);
        String type = file.folder() ? "📁 " : "📄 ";
        String size = file.size >= 0 ? " (" + file.size + " bytes)" : "";
        b.setText(type + file.name + size);
        b.setOnClickListener(v -> {
            if (file.folder()) {
                folders.push(activeFolder);
                activeFolder = file.id;
                search.setText("");
                nextPageToken = null;
                listing.removeAllViews();
                list(false);
            } else if (file.googleWorkspace()) {
                status.setText("这是 Google Docs/Sheets 等 Workspace 文件，需要 export MIME 路径，不能直接 alt=media 下载。");
            } else {
                withToken(token -> onDownload.start(file, token));
            }
        });
        listing.addView(b);
    }

    private static String enc(String raw) throws Exception {
        return URLEncoder.encode(raw, "UTF-8");
    }

    private static JSONObject apiGet(String raw, String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(raw).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int response = connection.getResponseCode();
            if (response != 200) throw new IOException("Google Drive API HTTP " + response);
            try (InputStream source = connection.getInputStream();
                 ByteArrayOutputStream memory = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = source.read(buf)) > 0) {
                    if (memory.size() + n > 2_000_000) throw new IOException("Drive JSON 响应过大");
                    memory.write(buf, 0, n);
                }
                return new JSONObject(memory.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    public void shutdown() {
        destroyed = true;
        accessToken = null;
        pending = null;
        io.shutdownNow();
    }
}
