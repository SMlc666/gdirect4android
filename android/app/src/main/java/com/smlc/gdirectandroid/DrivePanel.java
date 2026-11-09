package com.smlc.gdirectandroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Drive v3 browser with opt-in user-provided Desktop OAuth loopback login.
 * Requests full Drive OAuth scope; all user modifications require a GUI action.
 * Android browser loopback is experimental, not Google's recommended mobile flow.
 */
public final class DrivePanel extends LinearLayout {
    static final int CREDENTIALS_REQUEST = 7601;
    private static final String SCOPE = DesktopOAuth.DRIVE_SCOPE;
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
    private final DesktopOAuth authorization;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> folders = new ArrayDeque<>();
    private String activeFolder = "root";
    private String nextPageToken;
    private TextView status, location;
    private EditText search;
    private EditText oauthJson;
    private LinearLayout listing;
    private boolean destroyed = false;

    public DrivePanel(Activity activity, DownloadHandler onDownload) {
        super(activity);
        this.activity = activity;
        this.onDownload = onDownload;
        this.authorization = new DesktopOAuth(activity);
        setOrientation(VERTICAL);
        setPadding(0, 20, 0, 24);
        title("Google Drive · 桌面 OAuth + 完整权限（试验性）");
        status = title(
            "权限：完整 Google Drive（读取、创建、修改、移至回收站）。"
            + "\n桌面客户端的 127.0.0.1 回调不是 Google 推荐的 Android 授权方式。"
            + "\n请只导入自己创建的桌面 OAuth JSON；不会把凭据写入 APK。");
        oauthJson = new EditText(activity);
        oauthJson.setHint("粘贴 Google Desktop app 客户端 JSON（installed）");
        oauthJson.setMinLines(2);
        oauthJson.setMaxLines(4);
        addView(oauthJson);
        Button importJson = button("保存桌面 OAuth JSON（Keystore 加密）");
        importJson.setOnClickListener(v -> {
            try {
                authorization.saveDesktopClient(oauthJson.getText().toString());
                oauthJson.setText("");
                status.setText("桌面 OAuth 客户端已保存到 Android Keystore 加密存储。点击浏览器授权。");
            } catch (Exception error) {
                status.setText("桌面客户端配置无效：" + error.getMessage());
            }
        });
        Button fileJson = button("从本机文件导入 Desktop OAuth JSON");
        fileJson.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("application/json");
            try {
                activity.startActivityForResult(pick, CREDENTIALS_REQUEST);
            } catch (Exception error) {
                status.setText("Android 文件选择器无法启动：" + error.getMessage());
            }
        });
        Button login = button("使用外部浏览器授权完整 Drive 权限");
        login.setOnClickListener(v -> {
            status.setText("已启动桌面 OAuth 回环回调：请在外部浏览器完成 Google 授权（最多 3 分钟）。");
            authorization.login(new DesktopOAuth.Result() {
                @Override public void success(String token) {
                    if (!destroyed) status.setText(
                        "✅ Google 完整 Drive 授权成功；access token 仅在内存，refresh token 经 Keystore 加密。");
                }
                @Override public void failure(String reason) {
                    if (!destroyed) status.setText("OAuth 登录失败：" + reason);
                }
            });
        });
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
        Button disconnect = button("撤销 Drive 授权（保留桌面客户端配置）");
        disconnect.setOnClickListener(v -> {
            listing.removeAllViews();
            authorization.disconnect(new DesktopOAuth.Result() {
                @Override public void success(String info) {
                    if (!destroyed) status.setText(info);
                }
                @Override public void failure(String reason) {
                    if (!destroyed) status.setText(reason);
                }
            });
        });
        search = new EditText(activity);
        search.setHint("在当前文件夹按名称搜索（留空显示全部）");
        search.setSingleLine(true);
        addView(search);
        Button runSearch = button("搜索 / 刷新");
        runSearch.setOnClickListener(v -> list(false));
        Button create = button("在当前目录新建文件夹");
        create.setOnClickListener(v -> newFolder());
        title("提示：长按文件/文件夹可重命名或移至回收站；操作前会二次确认。");
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
        authorization.getToken(new DesktopOAuth.Result() {
            @Override public void success(String token) {
                if (!destroyed) action.accept(token);
            }
            @Override public void failure(String reason) {
                if (!destroyed) status.setText("Google Drive 访问失败：" + reason);
            }
        });
    }

    public boolean onActivityResult(int code, int resultCode, Intent data) {
        if (code != CREDENTIALS_REQUEST) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            status.setText("已取消桌面 OAuth JSON 导入。");
            return true;
        }
        try (InputStream input = activity.getContentResolver().openInputStream(data.getData())) {
            if (input == null) throw new IOException("无法读取所选 JSON 文件");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (buf.size() + n > 32768) throw new IOException("OAuth JSON 文件太大");
                buf.write(chunk, 0, n);
            }
            authorization.saveDesktopClient(buf.toString("UTF-8"));
            oauthJson.setText("");
            status.setText("✅ 已导入并加密保存桌面客户端配置，请点击浏览器授权完整 Drive 权限。");
        } catch (Exception error) {
            status.setText("导入桌面 OAuth JSON 失败：" + error.getMessage());
        }
        return true;
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
        b.setOnLongClickListener(v -> {
            showFileActions(file);
            return true;
        });
        listing.addView(b);
    }

    private void newFolder() {
        EditText nameInput = new EditText(activity);
        nameInput.setSingleLine(true);
        nameInput.setHint("新文件夹名称");
        new AlertDialog.Builder(activity)
            .setTitle("在当前目录新建文件夹")
            .setView(nameInput)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建", (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                if (name.isEmpty() || name.length() > 255) {
                    status.setText("文件夹名称为空或过长");
                    return;
                }
                String folder = activeFolder;
                performWrite("创建文件夹", "POST",
                    "https://www.googleapis.com/drive/v3/files?fields=id,name",
                    new JSONObjectBody(name, folder, null));
            }).show();
    }

    private void showFileActions(DriveFile entry) {
        new AlertDialog.Builder(activity)
            .setTitle(entry.name)
            .setItems(new String[] { "重命名", "移至回收站" }, (dialog, index) -> {
                if (index == 0) renameFile(entry);
                else trashFile(entry);
            }).show();
    }

    private void renameFile(DriveFile entry) {
        EditText rename = new EditText(activity);
        rename.setSingleLine(true);
        rename.setText(entry.name);
        new AlertDialog.Builder(activity).setTitle("重命名文件")
            .setView(rename)
            .setNegativeButton("取消", null)
            .setPositiveButton("重命名", (dialog, ignored) -> {
                String name = rename.getText().toString().trim();
                if (name.isEmpty() || name.length() > 255) {
                    status.setText("名称为空或过长");
                    return;
                }
                performWrite("重命名", "PATCH",
                    "https://www.googleapis.com/drive/v3/files/"
                        + entry.id + "?supportsAllDrives=true&fields=id,name",
                    new JSONObjectBody(name, null, null));
            }).show();
    }

    private void trashFile(DriveFile entry) {
        new AlertDialog.Builder(activity)
            .setTitle("确认移至回收站？")
            .setMessage("将「" + entry.name + "」移至 Google Drive 回收站。不会执行永久删除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("移至回收站", (dialog, ignored) ->
                performWrite("移至回收站", "PATCH",
                    "https://www.googleapis.com/drive/v3/files/"
                        + entry.id + "?supportsAllDrives=true&fields=id,trashed",
                    new JSONObjectBody(null, null, true)))
            .show();
    }

    private static final class JSONObjectBody {
        final String name, parent;
        final Boolean trash;
        JSONObjectBody(String name, String parent, Boolean trash) {
            this.name = name;
            this.parent = parent;
            this.trash = trash;
        }
        JSONObject serialize() throws Exception {
            JSONObject out = new JSONObject();
            if (name != null) out.put("name", name);
            if (parent != null) {
                out.put("mimeType", FOLDER);
                out.put("parents", new JSONArray().put(parent));
            }
            if (trash != null) out.put("trashed", trash);
            return out;
        }
    }

    private void performWrite(String action, String method, String endpoint, JSONObjectBody data) {
        withToken(token -> {
            status.setText("Google Drive 正在执行：" + action + "…");
            io.execute(() -> {
                try {
                    apiRequest(endpoint, token, method, data.serialize());
                    activity.runOnUiThread(() -> {
                        if (destroyed) return;
                        status.setText("✅ " + action + "成功。正在刷新列表。");
                        list(false);
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        if (!destroyed) status.setText(action + "失败：" + error.getMessage());
                    });
                }
            });
        });
    }

    private static JSONObject apiRequest(String raw, String token, String method, JSONObject body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(raw).openConnection();
        connection.setRequestMethod("PATCH".equals(method) ? "POST" : method);
        if ("PATCH".equals(method)) connection.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Google Drive API HTTP " + code);
            }
            InputStream input = connection.getInputStream();
            if (input == null) return new JSONObject();
            try (InputStream stream = input; ByteArrayOutputStream memory = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (memory.size() + read > 2_000_000) throw new IOException("Google Drive JSON 响应过大");
                    memory.write(buffer, 0, read);
                }
                return memory.size() == 0 ? new JSONObject() :
                    new JSONObject(memory.toString("UTF-8"));
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String enc(String raw) throws Exception {
        return URLEncoder.encode(raw, "UTF-8");
    }

    private static JSONObject apiGet(String raw, String token) throws Exception {
        return apiRequest(raw, token, "GET", null);
    }

    public void shutdown() {
        destroyed = true;
        authorization.shutdown();
        io.shutdownNow();
    }
}
