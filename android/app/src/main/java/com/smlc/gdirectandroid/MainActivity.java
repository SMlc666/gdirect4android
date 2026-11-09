package com.smlc.gdirectandroid;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Android bootstrap: real JNI calls into the shared gdirect Rust network code.
 * This is a network diagnostic milestone, not yet a Drive file manager.
 */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(24, 39, 65);
    private static final int BLUE = Color.rgb(42, 92, 194);
    private static final int MUTED = Color.rgb(91, 106, 129);
    private static final boolean NATIVE_LOADED;
    private static final String NATIVE_ERROR;

    static {
        boolean loaded = false;
        String error = "";
        try {
            System.loadLibrary("gdirect_android");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            error = e.toString();
        }
        NATIVE_LOADED = loaded;
        NATIVE_ERROR = error;
    }

    private static native String nativeVersion();
    private static native String nativeNetworkStatus();
    private static native String nativeProbeGoogle();
    private static native String nativeProbeIp(String ip);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView result;
    private Button googleButton;
    private Button ipButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 249, 255));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = addText(root, "gdirect for Android", 27, INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        addText(root, "Rust / Android · Milestone 1", 14, BLUE);
        addText(root,
            "这一版用来验证 APK、JNI 和原项目的 Rust 网络代码。账号登录、文件浏览、libaria2 下载与真正的 VPN 绕过尚未实现。",
            15, MUTED);

        TextView nativeLabel = addText(root,
            NATIVE_LOADED ? nativeVersion() : "原生库加载失败：" + NATIVE_ERROR,
            13, NATIVE_LOADED ? BLUE : Color.RED);
        nativeLabel.setTextIsSelectable(true);

        addText(root, "Android 网络", 18, INK).setPadding(0, dp(18), 0, dp(5));
        TextView androidStatus = addText(root, androidNetworkStatus(), 14, MUTED);
        androidStatus.setTextIsSelectable(true);

        addText(root, "Rust 网络诊断", 18, INK).setPadding(0, dp(17), 0, dp(5));
        googleButton = addButton(root, "检测 Google TCP 连通性");
        EditText ipInput = new EditText(this);
        ipInput.setSingleLine(true);
        ipInput.setTextSize(15);
        ipInput.setHint("输入要测试的 Google IPv4 / IPv6 地址");
        ipInput.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(ipInput, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ipButton = addButton(root, "测试指定 IP 的 443 端口");

        result = addText(root, "点击上方按钮进行测试。仅检测 TCP，不能证明 Drive API 或文件下载已可用。", 14, INK);
        result.setTextIsSelectable(true);
        result.setPadding(0, dp(15), 0, dp(12));

        googleButton.setEnabled(NATIVE_LOADED);
        ipButton.setEnabled(NATIVE_LOADED);
        googleButton.setOnClickListener(view -> runProbe(() ->
            nativeNetworkStatus() + "\n\n" + nativeProbeGoogle()));
        ipButton.setOnClickListener(view -> {
            final String ip = ipInput.getText().toString().trim();
            if (ip.isEmpty()) {
                result.setText("请先输入 IP 地址。");
                return;
            }
            runProbe(() -> nativeNetworkStatus() + "\n\n" + nativeProbeIp(ip));
        });
        setContentView(scroll);
    }

    private interface Probe { String execute(); }

    private void runProbe(Probe probe) {
        googleButton.setEnabled(false);
        ipButton.setEnabled(false);
        result.setText("Rust 正在测试网络连接…");
        worker.execute(() -> {
            String output;
            try {
                output = probe.execute();
            } catch (Exception e) {
                output = "诊断失败：" + e;
            } catch (LinkageError e) {
                output = "原生桥接错误：" + e;
            }
            final String completed = output;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                result.setText(completed);
                googleButton.setEnabled(true);
                ipButton.setEnabled(true);
            });
        });
    }

    private String androidNetworkStatus() {
        ConnectivityManager cm =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "ConnectivityManager 不可用";
        Network active = cm.getActiveNetwork();
        if (active == null) return "没有活动网络";
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        if (caps == null) return "无法读取活动网络能力";
        StringBuilder out = new StringBuilder();
        out.append("当前网络：");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) out.append("VPN / TUN ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) out.append("Wi-Fi ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) out.append("移动数据 ");
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) out.append("以太网 ");
        out.append("\n可访问互联网标志：")
            .append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        out.append("\n注意：这里显示的是系统默认网络，不代表 Rust 已绕开 TUN。");
        return out.toString();
    }

    private TextView addText(LinearLayout parent, String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(7));
        parent.addView(view, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private Button addButton(LinearLayout parent, String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setBackgroundTintList(ColorStateList.valueOf(BLUE));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(9);
        parent.addView(button, layout);
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
