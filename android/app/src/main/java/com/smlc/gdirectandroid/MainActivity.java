package com.smlc.gdirectandroid;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.button.MaterialButton;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.Manifest;
import android.content.pm.PackageManager;
import java.security.MessageDigest;
import java.io.FileOutputStream;
import android.content.Intent;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import org.json.JSONObject;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Diagnostics: Android selects a Network; Rust binds the TCP socket via NDK. */
public final class MainActivity extends AppCompatActivity {
    private static final int INK = Color.rgb(24, 39, 65);
    private static final int BLUE = Color.rgb(42, 92, 194);
    private static final int MUTED = Color.rgb(91, 106, 129);
    private static final boolean LOADED;
    private static final String LOAD_ERROR;
    static {
        boolean ok = false;
        String error = "";
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("gdirect_android");
            ok = true;
        } catch (UnsatisfiedLinkError ex) {
            error = ex.toString();
        }
        LOADED = ok;
        LOAD_ERROR = error;
    }
    private static native String nativeVersion();
    private static native String nativeProbeIp(String ip, long networkHandle);
    private static native String nativeVerifyGoogleIp(String ip, long networkHandle);
    private static native String nativeScanGoogle(long networkHandle);
    private static native String nativeOptimizeGoogle(
        long networkHandle, int maxCandidates, int workers, int speedMode,
        int benchmarkCount, String recentlyVerifiedIps);
    static native String nativeGoogleScanProgress();
    private static native void nativeCancelGoogleScan();
    static native String nativeDriveApiRequest(
        String url, String method, String accessToken, String jsonBody,
        long networkHandle, String pinnedIp);
    private static native String nativeTransferSample(long networkHandle,String verifiedIps);
    private static native String nativeSavePublicDriveSchema(long networkHandle,int cacheFd,String verifiedIps);
    static native String nativeDownloadDrive(
        String fileId, String accessToken, String caPath,
        String cacheDir, String cacheName, long networkHandle, String rankedIps, int connections,
        boolean multiIp);
    static native String nativeDownloadProgress();
    static native void nativeCancelDownload();

    private static final class Choice {
        final Network network; // null means system default (potentially VPN).
        final long handle;
        final String title;
        Choice(Network network, String title) {
            this.network = network;
            this.handle = network == null ? 0L : network.getNetworkHandle();
            this.title = title;
        }
        Choice(String title, long unavailable) {
            this.network = null;
            this.handle = unavailable;
            this.title = title;
        }
        @Override public String toString() { return title; }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable updateUi = new Runnable() {
        @Override public void run() {
            if (shell != null) { shell.refreshTransfers(); shell.refreshScan(); }
            ticker.postDelayed(this, 1000L);
        }
    };
    private UserPrefs userPrefs;
    private MaterialShell shell;
    private final List<Choice> choices = new ArrayList<>();
    private ConnectivityManager cm;
    private Spinner spinner;
    private Spinner scanLevel;
    private Spinner rankPreference;
    private Spinner preferredIp;
    private TextView endpointSummary;
    private EndpointPool endpointPool;
    private boolean updatingIpOptions = false;
    private TextView details;
    private TextView result;
    private Button googleButton;
    private Button ipButton;
    private Button refreshButton;
    private Button tlsButton;
    private Button scanButton;
    private Button transferButton;
    private Button saveButton;
    private static final int REQUEST_CREATE_FILE = 6086;
    private static final int REQUEST_DRIVE_DOWNLOAD = 6087;
    static final int REQUEST_EXPORT_CONFIG=7602;
    static final int REQUEST_IMPORT_CONFIG=7603;
    private DrivePanel drivePanel;
    private DrivePanel.DriveFile pendingDriveFile;
    private long pendingDriveNetwork = -1L;
    private String pendingDriveNetworkLabel = "";
    private String pendingDriveIps = "";
    private Button cancelDownloadButton;
    private Button downloadStatusButton;
    private long pendingSaveNetwork = -1L; // Invalid; never quietly route through VPN.
    private String pendingSaveNetworkLabel = "";
    private Button manualTlsButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        userPrefs = new UserPrefs(this);
        switch(userPrefs.theme()) {
            case "light": AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_NO); break;
            case "dark": AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_YES); break;
            default: AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
        super.onCreate(savedInstanceState);
        if (userPrefs.dynamicColor()) DynamicColors.applyToActivityIfAvailable(this);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        endpointPool = new EndpointPool(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 249, 255));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(root);

        TextView title = text(root, "gdirect for Android", 27, INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text(root, "v1.0 · 网络诊断（高级模式）", 14, BLUE);
        text(root, "选择系统默认、Wi-Fi 或移动网络，分别测试 Google。物理网卡不再依赖 Linux 路由表。", 15, MUTED);
        text(root, LOADED ? nativeVersion() : "原生库加载失败：" + LOAD_ERROR,
            13, LOADED ? BLUE : Color.RED);

        text(root, "网络选择", 18, INK);
        spinner = new Spinner(this);
        root.addView(spinner);
        details = text(root, "", 13, MUTED);
        details.setTextIsSelectable(true);
        refreshButton = button(root, "刷新 Android 网络");
        googleButton = button(root, "Google DNS + TCP 连通性测试");
        tlsButton = button(root, "Google TLS / HTTPS 验证（DNS IP）");
        text(root, "Google 前端节点扫描规模", 16, INK);
        scanLevel = new Spinner(this);
        scanLevel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] {
                "快速 · 128 候选 / 16 并发 / 4 测速",
                "标准 · 384 候选 / 32 并发 / 12 测速",
                "深度 · 768 候选 / 64 并发 / 24 测速",
                "自定义 · 在此调整全部扫描参数"
            }));
        scanLevel.setSelection(userPrefs.scanPreset());
        root.addView(scanLevel);
        text(root, "Google 节点优选策略", 16, INK);
        rankPreference = new Spinner(this);
        rankPreference.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[] { "低延迟优先", "实测速率优先", "智能均衡 · 延迟 / 速度 / 可信度" }));
        rankPreference.setSelection(userPrefs.rankMode());
        root.addView(rankPreference);
        scanButton = button(root, "扫描 Google IP 并自动优选（真 TLS + Drive API）");
        Button scanCancelButton = button(root, "取消当前 Google IP 扫描");
        scanCancelButton.setOnClickListener(v -> {
            if (LOADED) nativeCancelGoogleScan();
            result.setText("已请求停止扫描，现有经过验证的优选 IP 不会被清空。");
        });
        text(root, "当前优选 IP（可以手动置顶）", 16, INK);
        preferredIp = new Spinner(this);
        root.addView(preferredIp);
        endpointSummary = text(root, "", 13, MUTED);
        preferredIp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(
                AdapterView<?> parent, android.view.View view, int position, long id) {
                if (updatingIpOptions || endpointPool == null) return;
                Choice choice = selected();
                if (choice == null) return;
                String raw = String.valueOf(preferredIp.getSelectedItem());
                String ip = position == 0 ? null : raw.split(" · ")[0];
                endpointPool.manuallySelect(choice.handle, ip, speedPreferred());
                updateEndpointSummary();
            }
        });
        scanLevel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(AdapterView<?> parent,
                android.view.View view,int position,long id) {
                userPrefs.scanPreset(position);
                if(shell!=null)shell.updateScanMode(position);
            }
        });
        rankPreference.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(
                AdapterView<?> parent, android.view.View view, int position, long id) {
                userPrefs.rankMode(position);
                if(shell!=null)shell.updateRankMode(position);
                updateEndpointChoices();
            }
        });
        transferButton = button(root, "使用当前验证结果测试 Google API 传输");
        saveButton = button(root, "真实保存文件：Google Drive API 公开文档 JSON");
        text(root, "指定 Google IP 测试", 17, INK);
        EditText ipInput = new EditText(this);
        ipInput.setHint("IPv4 或 IPv6 地址");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setSingleLine(true);
        root.addView(ipInput);
        ipButton = button(root, "Rust 连接指定 IP 的 443 端口");
        manualTlsButton = button(root, "验证指定 IP 的 Google HTTPS / TLS");
        result = text(root, "先刷新网络，选择路径，然后点击测试。绑定失败不会静默回退到 VPN。", 14, INK);
        cancelDownloadButton = button(root, "取消当前 libaria2 下载");
        cancelDownloadButton.setOnClickListener(v -> {
            if (LOADED) nativeCancelDownload();
            result.setText("已向 libaria2 发出取消指令。");
            if (shell != null) shell.showDiagnosticResult(
                "已向原生下载器发出取消指令。");
        });
        downloadStatusButton = button(root, "查看 libaria2 实时进度");
        downloadStatusButton.setOnClickListener(v -> {
            if (LOADED) {
                String state = nativeDownloadProgress();
                result.setText(state);
                if (shell != null) shell.showDiagnosticResult(state);
            }
        });
        drivePanel = new DrivePanel(this, () -> {
            Choice choice = selected();
            return choice == null ? null : choice.network;
        }, () -> selected() != null, endpointPool,
            () -> speedPreferred(), (entry, token) -> {
            if (!LOADED) {
                result.setText("原生 libaria2 未加载，无法执行文件下载：" + LOAD_ERROR);
                return;
            }
            Choice choice = selected();
            if (choice == null) return;
            pendingDriveFile = entry;
            pendingDriveNetwork = choice.handle;
            pendingDriveNetworkLabel = choice.title;
            // Snapshot the exact ranked pool on the UI thread, at selection time.
            pendingDriveIps = String.join(",",
                endpointPool.addresses(choice.handle, speedPreferred()));
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/octet-stream");
            create.putExtra(Intent.EXTRA_TITLE, entry.name);
            try {
                startActivityForResult(create, REQUEST_DRIVE_DOWNLOAD);
            } catch (android.content.ActivityNotFoundException error) {
                result.setText("无法打开 Android 文件选择器：" + error);
                clearPendingDrive();
            }
        });
        root.addView(drivePanel);
        result.setTextIsSelectable(true);

        refreshButton.setOnClickListener(v -> refresh());
        googleButton.setEnabled(LOADED);
        ipButton.setEnabled(LOADED);
        tlsButton.setEnabled(LOADED);
        scanButton.setEnabled(LOADED);
        transferButton.setEnabled(LOADED);
        saveButton.setEnabled(LOADED);
        manualTlsButton.setEnabled(LOADED);
        tlsButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) probe(() -> tlsReport(choice));
        });

        saveButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice == null) return;
            pendingSaveNetwork = choice.handle;
            pendingSaveNetworkLabel = choice.title;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "gdirect-drive-v3-discovery.json");
            try {
                startActivityForResult(intent, REQUEST_CREATE_FILE);
            } catch (android.content.ActivityNotFoundException ex) {
                result.setText("没有找到支持创建文件的 Android 文档提供程序：" + ex);
                pendingSaveNetwork = -1L;
            }
        });
        transferButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) {
                String verified=String.join(",",endpointPool.addresses(choice.handle,speedPreferred()));
                if(verified.isEmpty()) {
                    if(shell!=null)shell.notice("当前网络尚无有效 IP，请先扫描");
                    return;
                }
                probe(() -> choice.title + "\n" + nativeTransferSample(choice.handle,verified));
            }
        });
        scanButton.setOnClickListener(v -> {
            Choice choice=selected();
            if(choice==null)return;
            final int profile=userPrefs.scanPreset();
            final int[] candidates={128,384,768,userPrefs.scanSize()};
            final int[] workers={16,32,64,userPrefs.scanThreads()};
            final int[] benchmarks={4,12,24,userPrefs.benchmarkCount()};
            final int position=Math.max(0,Math.min(3,profile));
            final int ranking=userPrefs.rankMode();
            final String freshSeeds=String.join(",",
                endpointPool.verifiedSeedIps(choice.handle));
            probe(()->{
                String raw=nativeOptimizeGoogle(choice.handle,
                    candidates[position],workers[position],
                    ranking==1?1:0,benchmarks[position],freshSeeds);
                JSONObject data=new JSONObject(raw);
                if(data.has("error"))
                    return "Google IP 扫描失败："+data.optString("error");
                int stored=endpointPool.accept(choice.handle,data);
                runOnUiThread(this::updateEndpointChoices);
                return choice.title+
                    "\n本次扫描："+data.optInt("probed")+"/"+data.optInt("requested")+
                    " · TLS/Drive 验证通过："+data.optInt("success")+
                    " · 并发："+data.optInt("concurrency")+
                    (data.optBoolean("cancelled")?"\n扫描已取消":"")+
                    "\n本次持久保存的验证结果："+stored+
                    "\n"+endpointPool.summary(choice.handle,ranking==1)+
                    "\n扫描结果不会跨网络或过期后自动使用；无预置成功 IP。";
            });
        });
        Button clearVerified=button(root,"清空当前网络的已验证 IP 历史");
        clearVerified.setOnClickListener(v->{
            Choice chosen=selected();
            if(chosen==null)return;
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("清空当前网络的验证记录？")
                .setMessage("此操作会移除本机保存的扫描结果及手动置顶选择。"
                    +"已入队任务仍保留原来的 IP 快照。")
                .setNegativeButton("取消",null)
                .setPositiveButton("清空",(dialog,which)->{
                    boolean ok=endpointPool.clear(chosen.handle);
                    updateEndpointChoices();
                    if(shell!=null)shell.notice(ok?"已清空当前网络验证记录":
                        "清空记录失败");
                }).show();
        });
        manualTlsButton.setOnClickListener(v -> {
            Choice choice = selected();
            String address = ipInput.getText().toString().trim();
            if (choice == null) return;
            if (address.isEmpty()) {
                result.setText("请先输入 IP 地址。");
                if (shell != null) shell.showDiagnosticResult("请先输入 IP 地址。");
                return;
            }
            probe(() -> choice.title + "\n" + nativeVerifyGoogleIp(address, choice.handle));
        });
        googleButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) probe(() -> googleReport(choice));
        });
        ipButton.setOnClickListener(v -> {
            Choice choice = selected();
            String address = ipInput.getText().toString().trim();
            if (choice == null) return;
            if (address.isEmpty()) {
                result.setText("请先输入 IP 地址。");
                if (shell != null) shell.showDiagnosticResult("请先输入 IP 地址。");
                return;
            }
            probe(() -> choice.title + "\n" + nativeProbeIp(address, choice.handle));
        });
        MaterialShell.Slots slots = new MaterialShell.Slots();
        slots.drive = drivePanel;
        slots.googleDns = googleButton;
        slots.googleTls = tlsButton;
        slots.manualIpInput = ipInput;
        slots.manualTcp = ipButton;
        slots.manualTls = manualTlsButton;
        slots.publicTransfer = transferButton;
        slots.publicSave = saveButton;
        slots.downloadStatus = downloadStatusButton;
        slots.downloadCancel = cancelDownloadButton;
        slots.network = spinner;
        slots.scanPreset = scanLevel;
        slots.ranking = rankPreference;
        slots.preferredIp = preferredIp;
        slots.refresh = refreshButton;
        slots.scan = scanButton;
        slots.scanCancel = scanCancelButton;
        slots.clearVerified = clearVerified;
        slots.networkDetails = details;
        slots.endpointSummary = endpointSummary;
        slots.scanResult = result;
        shell = new MaterialShell(this, userPrefs, slots, savedInstanceState);
        shell.updateScanMode(userPrefs.scanPreset());
        shell.updateRankMode(userPrefs.rankMode());
        if (savedInstanceState != null) {
            pendingSaveNetwork = savedInstanceState.getLong("pendingSaveNetwork", -1L);
            pendingSaveNetworkLabel = savedInstanceState.getString("pendingSaveNetworkLabel", "");
            String pendingId=savedInstanceState.getString("pending.drive.id");
            if(pendingId!=null){
                try {
                    JSONObject savedFile=new JSONObject()
                        .put("id",pendingId)
                        .put("name",savedInstanceState.getString("pending.drive.name",pendingId))
                        .put("mimeType",savedInstanceState.getString("pending.drive.mime",""))
                        .put("md5Checksum",savedInstanceState.getString("pending.drive.md5",""))
                        .put("size",savedInstanceState.getLong("pending.drive.size",-1));
                    pendingDriveFile=new DrivePanel.DriveFile(savedFile);
                    pendingDriveNetwork=savedInstanceState.getLong("pending.drive.network",-1);
                    pendingDriveNetworkLabel=savedInstanceState.getString("pending.drive.route","");
                    pendingDriveIps=savedInstanceState.getString("pending.drive.ips","");
                } catch(Exception ignored){clearPendingDrive();}
            }
        }
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        ticker.removeCallbacks(updateUi);
        ticker.post(updateUi);
    }
    @Override protected void onPause() {
        ticker.removeCallbacks(updateUi);
        super.onPause();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (shell != null) shell.open(
            intent == null ? "files" : intent.getStringExtra("open_tab"));
    }

    private Choice selected() {
        int index = spinner.getSelectedItemPosition();
        Choice choice = index >= 0 && index < choices.size() ? choices.get(index) : null;
        return choice != null && choice.handle >= 0 ? choice : null;
    }

    @SuppressWarnings("deprecation") // Explicit refresh only; service will use NetworkCallback.
    private void refresh() {
        Choice old = selected();
        long oldHandle = old == null ? userPrefs.networkHandle() : old.handle;
        choices.clear();
        choices.add(new Choice(null, "系统默认（可能经过 VPN）"));
        StringBuilder summary = new StringBuilder();
        if (cm != null) {
            Network active = cm.getActiveNetwork();
            summary.append("默认活动网络：").append(active).append("\n");
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) continue;
                String kind;
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) kind = "VPN";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) kind = "Wi-Fi";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) kind = "移动数据";
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) kind = "以太网";
                else kind = "其他网络";
                String label = kind + (network.equals(active) ? " · 活动" : "") + " · " + network;
                choices.add(new Choice(network, label));
                LinkProperties link = cm.getLinkProperties(network);
                summary.append(label);
                if (link != null) {
                    summary.append("\n  接口：").append(link.getInterfaceName())
                           .append("  DNS：").append(link.getDnsServers());
                }
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    summary.append("\n  注意：无 INTERNET 标志");
                }
                summary.append("\n");
            }
        }
        boolean routeFound = oldHandle == 0L;
        for (Choice candidate : choices) {
            if (candidate.handle == oldHandle) routeFound = true;
        }
        if (!routeFound) {
            choices.add(0, new Choice("⚠ 原网络已断开 · 请选择新线路", -1L));
            if (result != null) result.setText(
                "上次选择的 Android 网络不再可用。请在线路页面重新选择；为避免走错 VPN，本次访问已被阻止。");
        }
        ArrayAdapter<Choice> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).handle == oldHandle) { spinner.setSelection(i); break; }
        }
        details.setText(summary.toString());
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) {}
            @Override public void onItemSelected(
                AdapterView<?> parent, android.view.View view, int position, long id) {
                Choice chosen = selected();
                if (chosen != null) userPrefs.networkHandle(chosen.handle);
                updateEndpointChoices();
            }
        });
        updateEndpointChoices();
    }

    private boolean speedPreferred() {
        return rankPreference != null && rankPreference.getSelectedItemPosition() == 1;
    }
    /** Called when adaptive ranking sliders are changed in the route UI. */
    public void refreshEndpointRanking() { updateEndpointChoices(); }
    private void updateEndpointSummary() {
        if (endpointPool == null || endpointSummary == null) return;
        Choice c = selected();
        if (c == null) return;
        endpointSummary.setText("Network handle：" + c.handle + "\n"
            + endpointPool.summary(c.handle, speedPreferred()));
    }
    private void updateEndpointChoices() {
        if (endpointPool == null || preferredIp == null) return;
        Choice c = selected();
        if (c == null) return;
        updatingIpOptions = true;
        String[] items = endpointPool.selectionOptions(c.handle, speedPreferred());
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_dropdown_item, items);
        preferredIp.setAdapter(adapter);
        preferredIp.setSelection(endpointPool.preferredPosition(c.handle, speedPreferred()));
        updatingIpOptions = false;
        updateEndpointSummary();
    }

    private String tlsReport(Choice choice) {
        StringBuilder out = new StringBuilder();
        out.append("路径：").append(choice.title)
           .append("\nTLS 证书：原项目 GTS 根证书 + hostname 检查（不会忽略校验）\n");
        InetAddress[] addresses;
        try {
            addresses = choice.network == null
                ? InetAddress.getAllByName("www.googleapis.com")
                : choice.network.getAllByName("www.googleapis.com");
        } catch (Exception e) {
            return out.append("DNS 失败：").append(e).toString();
        }
        int v4 = 0, v6 = 0;
        for (int family = 4; family <= 6; family += 2) {
            for (InetAddress address : addresses) {
                if (family == 4 && !(address instanceof Inet4Address)) continue;
                if (family == 6 && !(address instanceof Inet6Address)) continue;
                if (family == 4 && v4 >= 2) continue;
                if (family == 6 && v6 >= 1) continue;
                if (family == 4) v4++; else v6++;
                out.append("\n").append(nativeVerifyGoogleIp(
                    address.getHostAddress(), choice.handle)).append("\n");
            }
        }
        if (v4 == 0 && v6 == 0) out.append("DNS 未提供可测地址");
        return out.append("\nVPN/默认网络上的 HTTPS 成功仍可能由代理转发。").toString();
    }

    private String googleReport(Choice choice) {
        StringBuilder out = new StringBuilder();
        out.append("路径：").append(choice.title)
           .append("\n绑定策略：").append(choice.handle == 0 ? "系统默认" : "NDK 指定 Network")
           .append("\n\n参考地址 1.1.1.1:443：\n")
           .append(nativeProbeIp("1.1.1.1", choice.handle))
           .append("\n\nwww.googleapis.com DNS：\n");
        InetAddress[] addresses;
        try {
            addresses = choice.network == null
                ? InetAddress.getAllByName("www.googleapis.com")
                : choice.network.getAllByName("www.googleapis.com");
        } catch (Exception ex) {
            return out.append("DNS 失败：").append(ex).toString();
        }
        int v4 = 0, v6 = 0;
        for (int family = 4; family <= 6; family += 2) {
            for (InetAddress address : addresses) {
                if (family == 4 && !(address instanceof Inet4Address)) continue;
                if (family == 6 && !(address instanceof Inet6Address)) continue;
                if (family == 4 && v4 >= 2) continue;
                if (family == 6 && v6 >= 2) continue;
                if (family == 4) v4++; else v6++;
                out.append("\n").append(nativeProbeIp(address.getHostAddress(), choice.handle));
            }
        }
        if (v4 == 0) out.append("\n无 IPv4 地址");
        if (v6 == 0) out.append("\n无 IPv6 地址");
        return out.append("\n\nTCP 成功不代表 TLS / Drive API / 下载成功。")
                  .append("\n若绑定失败，请检查 VPN allowBypass 或始终开启 VPN 的阻止非 VPN 连接设置。")
                  .toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong("pendingSaveNetwork", pendingSaveNetwork);
        state.putString("pendingSaveNetworkLabel", pendingSaveNetworkLabel);
        if (pendingDriveFile != null) {
            state.putString("pending.drive.id", pendingDriveFile.id);
            state.putString("pending.drive.name", pendingDriveFile.name);
            state.putString("pending.drive.mime", pendingDriveFile.mimeType);
            state.putString("pending.drive.md5", pendingDriveFile.md5);
            state.putLong("pending.drive.size", pendingDriveFile.size);
            state.putLong("pending.drive.network", pendingDriveNetwork);
            state.putString("pending.drive.route", pendingDriveNetworkLabel);
            state.putString("pending.drive.ips", pendingDriveIps);
        }
        if (shell != null) shell.saveUiState(state);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (drivePanel != null && drivePanel.onActivityResult(requestCode, resultCode, data)) return;
        if(requestCode==REQUEST_EXPORT_CONFIG||requestCode==REQUEST_IMPORT_CONFIG){
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            try {
                Uri picked=data.getData();
                if(requestCode==REQUEST_EXPORT_CONFIG){
                    byte[] bytes=SettingsPortability.exportToJson(this)
                        .toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try(java.io.OutputStream out=getContentResolver().openOutputStream(picked,"wt")){
                        if(out==null)throw new java.io.IOException("文档提供程序不允许写入");
                        out.write(bytes);out.flush();
                    }
                    if(shell!=null)shell.notice("已导出非敏感设置 JSON，不包含 OAuth 和签名密钥");
                }else{
                    java.io.ByteArrayOutputStream mem=new java.io.ByteArrayOutputStream();
                    try(java.io.InputStream in=getContentResolver().openInputStream(picked)){
                        if(in==null)throw new java.io.IOException("无法读取配置文件");
                        byte[] buf=new byte[4096];int n;
                        while((n=in.read(buf))!=-1){
                            if(mem.size()+n>65536)throw new java.io.IOException("配置超过 64 KiB");
                            mem.write(buf,0,n);
                        }
                    }
                    SettingsPortability.importFromJson(this,mem.toString("UTF-8"));
                    if(shell!=null)shell.notice("设置已导入，正在应用新外观");
                    recreate();
                }
            }catch(Exception error){
                if(shell!=null)shell.notice("配置处理失败："+error.getMessage());
            }
            return;
        }
        if (requestCode == REQUEST_DRIVE_DOWNLOAD) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                result.setText("已取消 Drive 文件保存。");
                clearPendingDrive();
                return;
            }
            if (pendingDriveFile == null || pendingDriveNetwork < 0) {
                result.setText("下载授权或网络选择状态失效；请重新选择文件。");
                clearPendingDrive();
                return;
            }
            Uri destination = data.getData();
            // Persist SAF write grant if the document provider supports it;
            // queue completion may be hours later or after process restart.
            try {
                getContentResolver().takePersistableUriPermission(
                    destination, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Some ACTION_CREATE_DOCUMENT providers do not offer a
                // persistable grant. Queue/SAF errors remain visible on retry.
            }
            DrivePanel.DriveFile entry = pendingDriveFile;
            long network = pendingDriveNetwork;
            String networkLabel = pendingDriveNetworkLabel;
            String ips = pendingDriveIps;
            clearPendingDrive();
            if (!LOADED) {
                if (shell != null) shell.notice("原生下载器尚未加载，无法开始任务");
                return;
            }
            try {
                // Download service is user-initiated by SAF file selection.
                // It is no longer tied to the Activity's lifecycle.
                Intent request = TransferService.create(
                    this, destination, entry, network,
                    networkLabel, ips, userPrefs.connections(),userPrefs.multiIp());
                startForegroundService(request);
                if (shell != null) shell.open(
                    userPrefs.startOnDownloads() ? "transfers" : "files");
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7109);
                }
            } catch (Exception error) {
                if (shell != null) shell.notice("无法开始下载：" + error.getMessage());
            }
            return;
        }
        if (requestCode != REQUEST_CREATE_FILE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            result.setText("已取消保存位置选择，没有执行下载。");
            pendingSaveNetwork = -1L;
            return;
        }
        if (pendingSaveNetwork < 0) {
            result.setText("网络选择状态丢失；拒绝意外回退到默认 VPN 路径。请重新选择网络。");
            return;
        }
        Uri destination = data.getData();
        long handle = pendingSaveNetwork;
        String networkLabel = pendingSaveNetworkLabel;
        pendingSaveNetwork = -1L;
        probe(() -> saveToDocument(destination, handle, networkLabel));
    }

    private void clearPendingDrive() {
        pendingDriveFile = null;
        pendingDriveNetwork = -1L;
        pendingDriveNetworkLabel = "";
        pendingDriveIps = "";
    }

    private String saveToDocument(Uri destination, long handle, String label) throws Exception {
        // A partial HTTPS result never reaches the user-selected document.
        // We only copy the app-private temporary file after Rust confirms
        // full HTTP framing, verified Google TLS and successful fsync.
        File temp = File.createTempFile("gdirect-", ".partial", getCacheDir());
        try {
            String resultText;
            try (ParcelFileDescriptor temporary = ParcelFileDescriptor.open(
                    temp,
                    ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE)) {
                String verified=String.join(",",endpointPool.addresses(handle,speedPreferred()));
                if(verified.isEmpty())
                    return "ERR\n当前网络没有有效的 Google IP 验证结果，请先扫描";
                resultText = nativeSavePublicDriveSchema(handle,temporary.getFd(),verified);
            }
            if (!resultText.startsWith("OK\n")) {
                return label + "\n" + resultText
                    + "\n没有把未完成的网络数据复制到目标文档。文件选择器可能已经创建一个空文件。";
            }
            long length = temp.length();
            if (length <= 0 || length > 8L * 1024 * 1024) {
                return "下载文件长度异常：" + length + " 字节，拒绝写入目标位置。";
            }
            long copied = 0;
            try (InputStream input = new FileInputStream(temp);
                 OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new java.io.IOException("无法打开文档输出流");
                byte[] buf = new byte[32768];
                int read;
                while ((read = input.read(buf)) != -1) {
                    output.write(buf, 0, read);
                    copied += read;
                }
                output.flush();
            } catch (Exception err) {
                throw new java.io.IOException(
                    "网络下载完整，但写入 Android 文档时失败；目标文件可能不完整：" + err, err);
            }
            if (copied != length) {
                throw new java.io.IOException("文件复制长度不符：" + copied + "/" + length);
            }
            return "✅ 文件真实保存成功\n" + label + "\n" + resultText
                + "\nAndroid 文档保存：成功写入 " + copied + " 字节。";
        } finally {
            if (!temp.delete()) temp.deleteOnExit();
        }
    }

    private interface Probe { String execute() throws Exception; }
    private void probe(Probe work) {
        googleButton.setEnabled(false);
        ipButton.setEnabled(false);
        tlsButton.setEnabled(false);
        scanButton.setEnabled(false);
        transferButton.setEnabled(false);
        saveButton.setEnabled(false);
        manualTlsButton.setEnabled(false);
        refreshButton.setEnabled(false);
        result.setText("任务正在运行…");
        if (shell != null) shell.showDiagnosticResult("正在执行测试…");
        worker.execute(() -> {
            String output;
            try { output = work.execute(); }
            catch (Exception ex) { output = "检测失败：" + ex; }
            catch (LinkageError ex) { output = "JNI 错误：" + ex; }
            final String report = output;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                result.setText(report);
                if (shell != null) {
                    shell.showDiagnosticResult(report);
                    shell.notice(report.length() > 70
                        ? "操作已完成，详情已显示在当前诊断区域"
                        : report);
                }
                googleButton.setEnabled(LOADED);
                ipButton.setEnabled(LOADED);
                tlsButton.setEnabled(LOADED);
                scanButton.setEnabled(LOADED);
                transferButton.setEnabled(LOADED);
                saveButton.setEnabled(LOADED);
                manualTlsButton.setEnabled(LOADED);
                refreshButton.setEnabled(true);
            });
        });
    }


    /** Called only by explicit download-card actions. Never silently switch
     * to a default VPN after an old physical Network handle disappeared. */
    public void queueCommand(String action,String id) {
        if(TransferService.COMMAND_REMOVE.equals(action)) {
            worker.execute(()->{
                boolean removed=false;
                String error=null;
                try { removed=TransferService.removeFinishedRecord(getApplicationContext(),id); }
                catch(Exception e){ error=e.getMessage(); }
                final boolean success=removed;
                final String reason=error;
                runOnUiThread(()->{
                    if(shell==null)return;
                    shell.invalidateTransfers();
                    shell.notice(success?"已删除本机任务记录（下载文件未删除）":
                        "删除失败："+(reason==null?"任务状态已改变或记录不存在":reason));
                });
            });
            return;
        }
        Choice c=selected();
        boolean resume=TransferService.COMMAND_RESUME.equals(action)||
            TransferService.COMMAND_RESUME_ALL.equals(action);
        if(resume&&c==null){
            if(shell!=null)shell.notice("请在线路页面先选择有效网络，再恢复下载");
            return;
        }
        long handle=c==null?-1:c.handle;
        String ips=resume?String.join(",",endpointPool.addresses(handle,speedPreferred())):"";
        Intent request=TransferService.control(this,action,id,
            handle,c==null?"":c.title,ips);
        try {
            if(resume)startForegroundService(request);
            else startService(request);
            if(shell!=null)shell.refreshTransfers();
        }catch(Exception error){
            if(shell!=null)shell.notice("任务操作失败："+error.getMessage());
        }
    }
    public void clearFinishedRecords() {
        worker.execute(()->{
            int count=-1;
            String error=null;
            try { count=TransferService.clearHistory(getApplicationContext()); }
            catch(Exception e){ error=e.getMessage(); }
            final int removed=count;
            final String reason=error;
            runOnUiThread(()->{
                if(shell==null)return;
                shell.invalidateTransfers();
                shell.notice(removed>=0?"已清理 "+removed+" 项已结束的任务记录":
                    "清理失败："+reason);
            });
        });
    }
    public void exportConfiguration(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE,"gdirect-settings.json");
        try{startActivityForResult(intent,REQUEST_EXPORT_CONFIG);}
        catch(Exception e){if(shell!=null)shell.notice("无法打开文件选择器："+e.getMessage());}
    }
    public void importConfiguration(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try{startActivityForResult(intent,REQUEST_IMPORT_CONFIG);}
        catch(Exception e){if(shell!=null)shell.notice("无法打开文件选择器："+e.getMessage());}
    }

    private TextView text(LinearLayout root, String value, int sp, int color) {
        TextView tv = new com.google.android.material.textview.MaterialTextView(this);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setPadding(0, dp(5), 0, dp(8));
        root.addView(tv);
        return tv;
    }
    private Button button(LinearLayout root, String title) {
        Button btn = new MaterialButton(this);
        btn.setText(title);
        btn.setAllCaps(false);
        // Let the Material 3 theme and wallpaper dynamic palette tint buttons.
        // Static diagnostics-blue would make dark/dynamic themes inconsistent.
        root.addView(btn);
        return btn;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @Override protected void onDestroy() {
        ticker.removeCallbacks(updateUi);
        worker.shutdownNow();
        if (drivePanel != null) drivePanel.shutdown();
        super.onDestroy();
    }
}
