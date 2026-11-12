package com.smlc.gdirectandroid;

import android.app.Activity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textview.MaterialTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.color.MaterialColors;
import android.content.Intent;
import android.graphics.Color;
import android.net.Network;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private final Supplier<Network> chosenNetwork;
    private final EndpointPool endpointPool;
    private final Supplier<Boolean> speedPriority;
    private volatile String lastVerifiedIp = "未连接";
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

    public DrivePanel(
            Activity activity, Supplier<Network> chosenNetwork, EndpointPool endpointPool,
            Supplier<Boolean> speedPriority, DownloadHandler onDownload) {
        super(activity);
        this.activity = activity;
        this.chosenNetwork = chosenNetwork;
        this.endpointPool = endpointPool;
        this.speedPriority = speedPriority;
        this.onDownload = onDownload;
        this.authorization = new DesktopOAuth(activity);
        setOrientation(VERTICAL);
        setPadding(0, 0, 0, 8);
        title("Google Drive 账号");
        title("直接浏览云端文件；文件列表和下载均使用线路页面选定的网络及优选 IP。");
        status = title(authorization.configured()
            ? "已导入桌面 OAuth 客户端。可以浏览文件，必要时重新授权。"
            : "首次使用：展开账号设置，导入自己的桌面 OAuth 客户端 JSON，然后授权。");
        oauthJson = new TextInputEditText(activity);
        oauthJson.setMinLines(2);
        oauthJson.setMaxLines(4);
        TextInputLayout jsonBox = new TextInputLayout(activity);
        jsonBox.setHint("Desktop OAuth JSON（也可通过下方导入）");
        jsonBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        jsonBox.addView(oauthJson);
        addView(jsonBox);
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
        // Keep credentials and rarely used account actions folded away by
        // default, instead of overwhelming the file browser.
        LinearLayout accountActions = new LinearLayout(activity);
        accountActions.setOrientation(VERTICAL);
        View[] setup = { jsonBox, importJson, fileJson, login, disconnect };
        for (View v : setup) {
            if (v.getParent() instanceof android.view.ViewGroup)
                ((android.view.ViewGroup) v.getParent()).removeView(v);
            accountActions.addView(v);
        }
        Button expandAccount = button("账号设置与权限管理  ▾");
        addView(accountActions);
        accountActions.setVisibility(authorization.configured() ? GONE : VISIBLE);
        expandAccount.setOnClickListener(v -> {
            boolean visible = accountActions.getVisibility() == VISIBLE;
            accountActions.setVisibility(visible ? GONE : VISIBLE);
            expandAccount.setText(visible ? "账号设置与权限管理  ▾" : "收起账号设置  ▴");
            if (!visible) accountActions.setAlpha(0f);
            if (!visible) accountActions.animate().alpha(1f).setDuration(200).start();
        });
        title("我的文件");
        search = new TextInputEditText(activity);
        search.setSingleLine(true);
        TextInputLayout searchBox = new TextInputLayout(activity);
        searchBox.setHint("搜索当前文件夹");
        searchBox.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchBox.setStartIconDrawable(R.drawable.ic_folder);
        searchBox.addView(search);
        addView(searchBox);
        Button runSearch = button("搜索 / 刷新");
        runSearch.setOnClickListener(v -> list(false));
        Button create = button("在当前目录新建文件夹");
        create.setOnClickListener(v -> newFolder());
        title("点击文件下载，点击文件夹进入；长按可重命名或移至回收站。");
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
        TextView v = new MaterialTextView(activity);
        v.setTextColor(MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorOnSurface, TEXT));
        v.setTextSize(14);
        v.setText(text);
        v.setPadding(0, 12, 0, 10);
        addView(v);
        return v;
    }

    private Button button(String text) {
        Button b = new MaterialButton(activity);
        b.setAllCaps(false);
        b.setText(text);
        b.setMinHeight(48);
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
        final Network apiNetwork = chosenNetwork.get();
        final String routeLabel = apiNetwork == null ? "系统默认（可能 VPN）"
            : "Network " + apiNetwork.toString();
        final String pageToken = loadNext ? nextPageToken : null;
        withToken(token -> {
            final String folder = activeFolder;
            final String query = search.getText().toString().trim();
            if (!loadNext) listing.removeAllViews();
            status.setText("正在读取 Google Drive 文件列表… 路径：" + routeLabel);
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
                    JSONObject data = apiGet(u, token, apiNetwork);
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
                        status.setText("列表读取完成（" + routeLabel
                            + " · Google IP " + lastVerifiedIp + "）。"
                            + (nextPageToken == null ? "" : " 还有下一页。"));
                        for (DriveFile entry : entries) addEntry(entry);
                    });
                } catch (Exception e) {
                    activity.runOnUiThread(() -> {
                        if (destroyed) return;
                        status.setText("Drive API 请求失败（" + routeLabel
                            + "；不会回退 VPN）：" + e.getMessage());
                    });
                }
            });
        });
    }

    private void addEntry(DriveFile file) {
        MaterialCardView item = new MaterialCardView(activity);
        item.setRadius(px(16));
        item.setCardElevation(0f);
        item.setClickable(true);
        item.setFocusable(true);
        item.setCardBackgroundColor(MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xfff5f4fc));
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(px(14),px(15),px(14),px(15));
        MaterialTextView glyph = new MaterialTextView(activity);
        glyph.setText(file.folder() ? "📁" : file.googleWorkspace() ? "📝" : "📄");
        glyph.setTextSize(25);
        row.addView(glyph, new LinearLayout.LayoutParams(px(42),-2));
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(VERTICAL);
        MaterialTextView name = new MaterialTextView(activity);
        name.setText(file.name);
        name.setTextSize(15);
        name.setTypeface(null,android.graphics.Typeface.BOLD);
        name.setMaxLines(2);
        info.addView(name);
        MaterialTextView metadata = new MaterialTextView(activity);
        String size = file.folder() ? "文件夹" : file.size >= 0
            ? android.text.format.Formatter.formatShortFileSize(activity,file.size)
            : file.googleWorkspace() ? "Google Workspace 文档" : "云端文件";
        metadata.setText(size);
        metadata.setTextColor(MaterialColors.getColor(activity,
            com.google.android.material.R.attr.colorOnSurfaceVariant, 0xff5f606b));
        metadata.setTextSize(12);
        info.addView(metadata);
        row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        item.addView(row);
        LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,-2);
        itemParams.bottomMargin=px(8);
        listing.addView(item,itemParams);
        item.setOnClickListener(v -> {
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
        item.setOnLongClickListener(v -> {
            showFileActions(file);
            return true;
        });
    }

    private void newFolder() {
        EditText nameInput = new EditText(activity);
        nameInput.setSingleLine(true);
        nameInput.setHint("新文件夹名称");
        new MaterialAlertDialogBuilder(activity)
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
        new MaterialAlertDialogBuilder(activity)
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
        new MaterialAlertDialogBuilder(activity).setTitle("重命名文件")
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
        new MaterialAlertDialogBuilder(activity)
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
        final Network apiNetwork = chosenNetwork.get();
        final String routeLabel = apiNetwork == null ? "系统默认（可能 VPN）"
            : "Network " + apiNetwork.toString();
        withToken(token -> {
            status.setText("Google Drive 正在执行：" + action + "… 路径：" + routeLabel);
            io.execute(() -> {
                try {
                    apiRequest(endpoint, token, method, data.serialize(), apiNetwork);
                    activity.runOnUiThread(() -> {
                        if (destroyed) return;
                        status.setText("✅ " + action + "成功（Google IP "
                            + lastVerifiedIp + "）。正在刷新列表。");
                        list(false);
                    });
                } catch (Exception error) {
                    activity.runOnUiThread(() -> {
                        if (!destroyed) status.setText(action + "失败（" + routeLabel
                            + "；不会回退 VPN）：" + error.getMessage());
                    });
                }
            });
        });
    }

    /**
     * Google API calls must use the same ranked literal IP pool as aria2.
     * Rust owns DNS bypass + selected-network TCP bind + GTS TLS hostname check.
     * NEVER use a URL with an IP as the hostname; that changes TLS identity.
     *
     * Only GET is failover-retried. POST/PATCH are side-effecting: if Google
     * committed a write but the response was lost, automatically retrying
     * could create duplicate folders or repeat an account mutation.
     */
    private JSONObject apiRequest(
            String raw, String token, String method, JSONObject body, Network apiNetwork)
            throws Exception {
        long handle = apiNetwork == null ? 0L : apiNetwork.getNetworkHandle();
        String[] candidates = endpointPool.addresses(handle, speedPriority.get());
        if (candidates.length == 0) {
            throw new IOException("没有经过验证的 Google API IP，请先扫描优选");
        }
        final String payload = body == null ? "" : body.toString();
        IOException last = null;
        for (String ip : candidates) {
            final JSONObject envelope;
            try {
                envelope = new JSONObject(MainActivity.nativeDriveApiRequest(
                    raw, method, token, payload, handle, ip));
            } catch (LinkageError err) {
                throw new IOException("Rust 原生 Google API 尚未加载：" + err.getMessage(), err);
            }
            if (envelope.has("error")) {
                last = new IOException("Google IP " + ip + "："
                    + envelope.optString("error", "未说明错误"));
                if (!"GET".equals(method)) {
                    throw new IOException("此操作可能已经提交，为防重复写入不自动换 IP 重试：" + last.getMessage());
                }
                continue;
            }
            int code = envelope.optInt("status", -1);
            lastVerifiedIp = ip;
            if (code < 200 || code >= 300) {
                // HTTP status means a real Google API response was received.
                // Never replay an OAuth error, quota error or write mutation.
                throw new IOException("Google IP " + ip + " · Drive API HTTP " + code);
            }
            String json = envelope.optString("body", "{}");
            if (json.isEmpty()) json = "{}";
            return new JSONObject(json);
        }
        throw new IOException("所选 Network 的 " + candidates.length
            + " 个 Google 优选 IP 均未完成 TLS/HTTP 请求"
            + (last == null ? "" : "；最后错误：" + last.getMessage())
            + "。不会回退到 VPN。");
    }

    private int px(int amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }

    private static String enc(String raw) throws Exception {
        return URLEncoder.encode(raw, "UTF-8");
    }

    private JSONObject apiGet(String raw, String token, Network apiNetwork)
            throws Exception {
        return apiRequest(raw, token, "GET", null, apiNetwork);
    }

    public void shutdown() {
        destroyed = true;
        authorization.shutdown();
        io.shutdownNow();
    }
}
