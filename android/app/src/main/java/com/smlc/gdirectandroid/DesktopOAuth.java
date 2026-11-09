package com.smlc.gdirectandroid;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Opt-in desktop OAuth client on Android. Google documents loopback callbacks
 * for Desktop OAuth clients, but does not recommend the loopback flow as a
 * mobile app integration. This is experimental, user-configured behavior.
 *
 * Full Drive scope: https://www.googleapis.com/auth/drive
 * External browser + PKCE S256 + state + ephemeral localhost-only listener.
 * Refresh tokens and imported desktop-client credentials are encrypted using
 * Android Keystore AES/GCM; neither credentials nor tokens are logged.
 */
public final class DesktopOAuth {
    public interface Result {
        void success(String accessToken);
        void failure(String reason);
    }

    public static final String DRIVE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String REVOKE = "https://oauth2.googleapis.com/revoke";
    private static final String ALIAS = "gdirect4android.desktop-oauth.v1";
    private static final String CLIENT = "client";
    private static final String REFRESH = "refresh";
    private static final int MAX_JSON = 32768;

    private final Activity activity;
    private final SharedPreferences settings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SecureRandom random = new SecureRandom();
    private volatile String access;
    private volatile long accessExpiresAt;
    private volatile ServerSocket listening;
    private volatile boolean closed;

    private static final class Credentials {
        final String id, secret;
        Credentials(String id, String secret) { this.id = id; this.secret = secret; }
    }

    public DesktopOAuth(Activity activity) {
        this.activity = activity;
        this.settings = activity.getSharedPreferences("gdirect_oauth_encrypted_v1", Context.MODE_PRIVATE);
    }

    public boolean configured() { return settings.contains(CLIENT); }

    /** Accept Google's {"installed":{...}} desktop client JSON or flat fields.
     * Desktop client secrets are public-client metadata, but still encrypted at
     * rest and never embedded into the APK or printed. */
    public void saveDesktopClient(String json) throws Exception {
        Credentials parsed = parseClient(json);
        String normalized = new JSONObject().put("client_id", parsed.id)
            .put("client_secret", parsed.secret).toString();
        settings.edit().putString(CLIENT, encrypt(normalized)).remove(REFRESH).apply();
        access = null;
        accessExpiresAt = 0;
    }

    private static Credentials parseClient(String text) throws Exception {
        if (text == null || text.length() > MAX_JSON) throw new Exception("凭据 JSON 为空或超过大小上限");
        JSONObject object = new JSONObject(text);
        if (object.has("web")) throw new Exception("这是 Web OAuth 客户端，请使用 Desktop app（桌面应用）类型");
        JSONObject entry = object.optJSONObject("installed");
        if (entry == null) entry = object;
        String id = entry.optString("client_id", "").trim();
        String secret = entry.optString("client_secret", "").trim();
        if (!id.endsWith(".apps.googleusercontent.com") || !id.matches("[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com")) {
            throw new Exception("client_id 格式无效，应使用 Google Desktop app OAuth 客户端");
        }
        if (secret.isEmpty() || secret.length() > 512) {
            throw new Exception("桌面 OAuth 客户端缺少 client_secret");
        }
        return new Credentials(id, secret);
    }

    private Credentials client() throws Exception {
        String data = settings.getString(CLIENT, null);
        if (data == null) throw new Exception("请先导入桌面 OAuth 客户端 JSON");
        return parseClient(decrypt(data));
    }

    /** Launch real Google authorization in an external browser.
     * Only this localhost listener (127.0.0.1:ephemeral) receives the code.
     */
    public void login(Result callback) {
        if (closed) return;
        worker.execute(() -> {
            try {
                Credentials client = client();
                String verifier = randomUrl(48);
                String state = randomUrl(32);
                String challenge = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                try (ServerSocket listener = new ServerSocket(
                        0, 1, InetAddress.getByName("127.0.0.1"))) {
                    listening = listener;
                    listener.setSoTimeout(1000);
                    String redirect = "http://127.0.0.1:" + listener.getLocalPort();
                    String url = AUTH + "?client_id=" + enc(client.id)
                        + "&redirect_uri=" + enc(redirect)
                        + "&response_type=code"
                        + "&scope=" + enc(DRIVE_SCOPE)
                        + "&access_type=offline&prompt=consent"
                        + "&state=" + enc(state)
                        + "&code_challenge=" + enc(challenge)
                        + "&code_challenge_method=S256";
                    activity.runOnUiThread(() -> {
                        if (closed) return;
                        try {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                            intent.addCategory(Intent.CATEGORY_BROWSABLE);
                            activity.startActivity(intent);
                        } catch (Exception e) {
                            // Timeout on the listener naturally terminates the flow.
                            callback.failure("无法打开外部浏览器：" + safe(e));
                        }
                    });

                    String code = null;
                    long deadline = SystemClock.elapsedRealtime() + 180000;
                    while (!closed && code == null && SystemClock.elapsedRealtime() < deadline) {
                        try (Socket socket = listener.accept()) {
                            socket.setSoTimeout(4000);
                            String firstLine = readRequestLine(socket.getInputStream());
                            String response = "授权回调无效，请返回应用重试。";
                            int status = 400;
                            try {
                                String[] parts = firstLine.split(" ", 3);
                                if (parts.length < 2 || !"GET".equals(parts[0])) throw new Exception("请求方法无效");
                                URI uri = URI.create(parts[1]);
                                if (!"/".equals(uri.getPath())) throw new Exception("回调路径无效");
                                Map<String, String> params = parseQuery(uri.getRawQuery());
                                if (!constantEqual(state, params.get("state"))) {
                                    throw new Exception("授权 state 不匹配");
                                }
                                if (params.containsKey("error")) {
                                    throw new Exception("Google 拒绝授权：" + params.get("error"));
                                }
                                String candidate = params.get("code");
                                if (candidate == null || candidate.isEmpty() || candidate.length() > 4096) {
                                    throw new Exception("授权码缺失");
                                }
                                code = candidate;
                                status = 200;
                                response = "Google 授权已返回应用。可以关闭此页面。";
                            } catch (Exception ignored) {
                                // Deliberately do not log or echo state/code.
                            }
                            sendBrowserResponse(socket, status, response);
                        } catch (SocketTimeoutException ignored) {
                            // Allow cancellation and bounded 3-minute timeout.
                        }
                    }
                    if (code == null) throw new Exception("授权超时或浏览器没有完成 127.0.0.1 回调");
                    LinkedHashMap<String, String> form = new LinkedHashMap<>();
                    form.put("client_id", client.id);
                    form.put("client_secret", client.secret);
                    form.put("code", code);
                    form.put("code_verifier", verifier);
                    form.put("redirect_uri", redirect);
                    form.put("grant_type", "authorization_code");
                    acceptTokens(post(TOKEN, form), true);
                    uiSuccess(callback, access);
                } finally {
                    listening = null;
                }
            } catch (Exception e) {
                uiFailure(callback, safe(e));
            }
        });
    }

    /** Reuse memory token or securely refresh an offline token. */
    public void getToken(Result callback) {
        if (closed) return;
        String present = access;
        if (present != null && SystemClock.elapsedRealtime() + 60000 < accessExpiresAt) {
            uiSuccess(callback, present);
            return;
        }
        worker.execute(() -> {
            try {
                Credentials client = client();
                String stored = settings.getString(REFRESH, null);
                if (stored == null) throw new Exception("请先点击「浏览器授权完整 Drive 权限」登录");
                LinkedHashMap<String, String> form = new LinkedHashMap<>();
                form.put("client_id", client.id);
                form.put("client_secret", client.secret);
                form.put("refresh_token", decrypt(stored));
                form.put("grant_type", "refresh_token");
                acceptTokens(post(TOKEN, form), false);
                uiSuccess(callback, access);
            } catch (Exception e) {
                uiFailure(callback, safe(e));
            }
        });
    }

    public void disconnect(Result callback) {
        if (closed) return;
        worker.execute(() -> {
            String stored = settings.getString(REFRESH, null);
            String token = access;
            access = null;
            accessExpiresAt = 0;
            settings.edit().remove(REFRESH).apply();
            try {
                String revoke = stored == null ? token : decrypt(stored);
                if (revoke == null || revoke.isEmpty()) {
                    uiSuccess(callback, "本地会话已清除，当前无可撤销令牌。");
                    return;
                }
                LinkedHashMap<String, String> form = new LinkedHashMap<>();
                form.put("token", revoke);
                post(REVOKE, form);
                uiSuccess(callback, "已清除本地会话，并向 Google 发送撤销授权请求。");
            } catch (Exception e) {
                uiFailure(callback, "已清除本地会话；Google 撤销请求未确认：" + safe(e));
            }
        });
    }

    private void acceptTokens(JSONObject payload, boolean initial) throws Exception {
        String token = payload.optString("access_token", "");
        if (token.isEmpty() || token.length() > 8192) throw new Exception("Google 没有返回有效 access token");
        String scope = payload.optString("scope", "");
        if (!scope.isEmpty() && !Arrays.asList(scope.split("\\s+")).contains(DRIVE_SCOPE)) {
            throw new Exception("Google 没有授予完整 Drive 权限");
        }
        String refresh = payload.optString("refresh_token", "");
        if (initial && refresh.isEmpty() && !settings.contains(REFRESH)) {
            throw new Exception("未得到离线 refresh token；请在 Google 账号权限页面撤销旧授权后重新登录");
        }
        if (!refresh.isEmpty()) settings.edit().putString(REFRESH, encrypt(refresh)).apply();
        access = token;
        int seconds = payload.optInt("expires_in", 3600);
        accessExpiresAt = SystemClock.elapsedRealtime() + Math.max(60, seconds) * 1000L;
    }

    private static JSONObject post(String endpoint, Map<String, String> params) throws Exception {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (form.length() > 0) form.append('&');
            form.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
        }
        byte[] data = form.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setFixedLengthStreamingMode(data.length);
        try {
            try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            int code = conn.getResponseCode();
            if (endpoint.equals(REVOKE) && code == 200) return new JSONObject();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String raw = in == null ? "" : readBounded(in, MAX_JSON);
            JSONObject obj = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            if (code < 200 || code >= 300) {
                String problem = obj.optString("error", "unknown");
                // Do not print full server JSON, authorization code or token.
                throw new Exception("Google OAuth HTTP " + code + "：" + problem);
            }
            return obj;
        } finally {
            conn.disconnect();
        }
    }

    private static String readBounded(InputStream stream, int max) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > max) throw new Exception("Google OAuth 响应超过大小上限");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static String readRequestLine(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int n;
        while ((n = in.read()) != -1 && n != '\n') {
            if (out.size() >= 8192) throw new Exception("HTTP 回调请求过长");
            out.write(n);
        }
        if (n == -1) throw new Exception("回调连接提前关闭");
        return out.toString("UTF-8").trim();
    }

    private static Map<String, String> parseQuery(String raw) throws Exception {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (raw == null) return result;
        for (String pair : raw.split("&")) {
            String[] both = pair.split("=", 2);
            if (both.length == 2) result.put(
                URLDecoder.decode(both[0], "UTF-8"), URLDecoder.decode(both[1], "UTF-8"));
        }
        return result;
    }

    private static boolean constantEqual(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBrowserResponse(Socket socket, int status, String message) throws Exception {
        String html = "<!doctype html><html><head><meta charset=\"UTF-8\"></head><body>"
            + "<p>" + message + "</p></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Bad Request")
            + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\n"
            + "Connection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private String randomUrl(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.encodeToString(raw, Base64.NO_PADDING | Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String enc(String s) throws Exception { return URLEncoder.encode(s, "UTF-8"); }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + data.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(data, 0, combined, iv.length, data.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private String decrypt(String encoded) throws Exception {
        byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
        if (payload.length < 29) throw new Exception("本地加密凭据损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
            Arrays.copyOfRange(payload, 0, 12)));
        return new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
    }

    private void uiSuccess(Result callback, String value) {
        activity.runOnUiThread(() -> { if (!closed) callback.success(value); });
    }
    private void uiFailure(Result callback, String reason) {
        activity.runOnUiThread(() -> { if (!closed) callback.failure(reason); });
    }
    private static String safe(Exception e) {
        // Never return a token endpoint raw body, full OAuth URL, or secret.
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    public void shutdown() {
        closed = true;
        access = null;
        accessExpiresAt = 0;
        ServerSocket server = listening;
        if (server != null) {
            try { server.close(); } catch (Exception ignored) {}
        }
        worker.shutdownNow();
    }
}
