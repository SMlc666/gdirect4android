package com.smlc.gdirectandroid;

import android.app.Activity;
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
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(24, 39, 65);
    private static final int BLUE = Color.rgb(42, 92, 194);
    private static final int MUTED = Color.rgb(91, 106, 129);
    private static final boolean LOADED;
    private static final String LOAD_ERROR;
    static {
        boolean ok = false;
        String error = "";
        try {
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
    private static native String nativeTransferSample(long networkHandle);

    private static final class Choice {
        final Network network; // null means system default (potentially VPN).
        final long handle;
        final String title;
        Choice(Network network, String title) {
            this.network = network;
            this.handle = network == null ? 0L : network.getNetworkHandle();
            this.title = title;
        }
        @Override public String toString() { return title; }
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Choice> choices = new ArrayList<>();
    private ConnectivityManager cm;
    private Spinner spinner;
    private TextView details;
    private TextView result;
    private Button googleButton;
    private Button ipButton;
    private Button refreshButton;
    private Button tlsButton;
    private Button scanButton;
    private Button transferButton;
    private Button manualTlsButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 249, 255));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(22), dp(22), dp(22));
        scroll.addView(root);

        TextView title = text(root, "gdirect for Android", 27, INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        text(root, "Milestone 4 · Google API 真实数据传输", 14, BLUE);
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
        scanButton = button(root, "扫描 16 个 Google 前端 IP（最多 4 并发）");
        transferButton = button(root, "传输测试：从已验证的 3 个 IP 下载公开 API 数据");
        text(root, "指定 Google IP 测试", 17, INK);
        EditText ipInput = new EditText(this);
        ipInput.setHint("IPv4 或 IPv6 地址");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        ipInput.setSingleLine(true);
        root.addView(ipInput);
        ipButton = button(root, "Rust 连接指定 IP 的 443 端口");
        manualTlsButton = button(root, "验证指定 IP 的 Google HTTPS / TLS");
        result = text(root, "先刷新网络，选择路径，然后点击测试。绑定失败不会静默回退到 VPN。", 14, INK);
        result.setTextIsSelectable(true);

        refreshButton.setOnClickListener(v -> refresh());
        googleButton.setEnabled(LOADED);
        ipButton.setEnabled(LOADED);
        tlsButton.setEnabled(LOADED);
        scanButton.setEnabled(LOADED);
        transferButton.setEnabled(LOADED);
        manualTlsButton.setEnabled(LOADED);
        tlsButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) probe(() -> tlsReport(choice));
        });

        transferButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) probe(() -> choice.title + "\n" + nativeTransferSample(choice.handle));
        });
        scanButton.setOnClickListener(v -> {
            Choice choice = selected();
            if (choice != null) probe(() -> choice.title + "\n" + nativeScanGoogle(choice.handle));
        });
        manualTlsButton.setOnClickListener(v -> {
            Choice choice = selected();
            String address = ipInput.getText().toString().trim();
            if (choice == null) return;
            if (address.isEmpty()) { result.setText("请先输入 IP 地址。"); return; }
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
                return;
            }
            probe(() -> choice.title + "\n" + nativeProbeIp(address, choice.handle));
        });
        setContentView(scroll);
        refresh();
    }

    private Choice selected() {
        int index = spinner.getSelectedItemPosition();
        return index >= 0 && index < choices.size() ? choices.get(index) : null;
    }

    @SuppressWarnings("deprecation") // Explicit refresh only; service will use NetworkCallback.
    private void refresh() {
        Choice old = selected();
        long oldHandle = old == null ? 0L : old.handle;
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
        ArrayAdapter<Choice> adapter = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < choices.size(); i++) {
            if (choices.get(i).handle == oldHandle) { spinner.setSelection(i); break; }
        }
        details.setText(summary.toString());
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

    private interface Probe { String execute() throws Exception; }
    private void probe(Probe work) {
        googleButton.setEnabled(false);
        ipButton.setEnabled(false);
        tlsButton.setEnabled(false);
        scanButton.setEnabled(false);
        transferButton.setEnabled(false);
        manualTlsButton.setEnabled(false);
        refreshButton.setEnabled(false);
        result.setText("正在检测，请稍候…");
        worker.execute(() -> {
            String output;
            try { output = work.execute(); }
            catch (Exception ex) { output = "检测失败：" + ex; }
            catch (LinkageError ex) { output = "JNI 错误：" + ex; }
            final String report = output;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                result.setText(report);
                googleButton.setEnabled(LOADED);
                ipButton.setEnabled(LOADED);
                tlsButton.setEnabled(LOADED);
                scanButton.setEnabled(LOADED);
                transferButton.setEnabled(LOADED);
                manualTlsButton.setEnabled(LOADED);
                refreshButton.setEnabled(true);
            });
        });
    }

    private TextView text(LinearLayout root, String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextColor(color);
        tv.setTextSize(sp);
        tv.setPadding(0, dp(5), 0, dp(8));
        root.addView(tv);
        return tv;
    }
    private Button button(LinearLayout root, String title) {
        Button btn = new Button(this);
        btn.setText(title);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        root.addView(btn);
        return btn;
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
