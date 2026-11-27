package com.zhshan.shantv;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

/** 内置本地 HTTP 服务：提供静态资源 + /proxy 代理（替代 server.mjs） */
public class LocalServer extends NanoHTTPD {

    private static final int PORT = 17891;
    private static final String PASSWORD_HASH = "ac592b987bce75f4fd69fcc068cbac7594fa8f2cebd7424932d8222ffb3e3bf0";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    private final Context context;
    private final AssetManager assets;
    private final OkHttpClient client;

    public LocalServer(Context context) {
        super("127.0.0.1", PORT);
        this.context = context.getApplicationContext();
        this.assets = this.context.getAssets();
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();

        try {
            if (uri.startsWith("/proxy/")) {
                return handleProxy(uri, params);
            }
            if (uri.equals("/") || uri.equals("/index.html") || uri.equals("/player.html") || uri.equals("/watch.html")) {
                return serveHtml(uri, params);
            }
            return serveAsset(uri);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "error: " + e.getMessage());
        }
    }

    /** 注入密码哈希到 HTML，实现免密打开（与 exe 一致） */
    private Response serveHtml(String uri, Map<String, String> params) throws IOException {
        String file = uri.equals("/") ? "index.html" : uri.substring(1);
        String html = readAssetAsString("public/" + file).replace("{{PASSWORD}}", PASSWORD_HASH);
        String boot = "<script>(function(){var H='" + PASSWORD_HASH + "';try{"
                + "localStorage.setItem('passwordVerified_v2',JSON.stringify({verified:true,timestamp:Date.now(),passwordHash:H}));"
                + "localStorage.setItem('proxyAuthHash',H);}catch(e){}})();</script>";
        int p = html.indexOf("<head");
        if (p >= 0) {
            int gt = html.indexOf('>', p);
            if (gt > 0) html = html.substring(0, gt + 1) + "\n" + boot + html.substring(gt + 1);
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html);
    }

    private Response handleProxy(String uri, Map<String, String> params) {
        String auth = params.get("auth");
        if (auth == null || !auth.equals(PASSWORD_HASH)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json; charset=utf-8", "{\"error\":\"unauthorized\"}");
        }
        String encoded = uri.substring("/proxy/".length());
        String target;
        try {
            target = java.net.URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "bad url");
        }
        // 去掉前端追加的 auth / t 参数
        int q = target.indexOf('?');
        if (q > 0) {
            String base = target.substring(0, q);
            String query = target.substring(q + 1);
            StringBuilder kept = new StringBuilder();
            for (String part : query.split("&")) {
                if (part.startsWith("auth=") || part.startsWith("t=")) continue;
                if (kept.length() > 0) kept.append('&');
                kept.append(part);
            }
            target = kept.length() > 0 ? base + "?" + kept : base;
        }
        // 安全校验：仅允许 http/https
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "invalid url");
        }
        try {
            Request req = new Request.Builder().url(target).header("User-Agent", UA).build();
            try (okhttp3.Response resp = client.newCall(req).execute()) {
                ResponseBody body = resp.body();
                if (body == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "empty");
                byte[] data = body.bytes();
                String ct = body.contentType() != null ? body.contentType().toString() : "application/octet-stream";
                // m3u8 需要允许 CORS（WebView 同源其实不需要，但保留）
                Response r = newFixedLengthResponse(Response.Status.OK, ct, new java.io.ByteArrayInputStream(data), data.length);
                r.addHeader("Access-Control-Allow-Origin", "*");
                return r;
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "proxy error: " + e.getMessage());
        }
    }

    private Response serveAsset(String uri) throws IOException {
        String path = uri.startsWith("/") ? uri.substring(1) : uri;
        if (path.isEmpty()) path = "index.html";
        // 去掉查询串
        int q = path.indexOf('?');
        if (q > 0) path = path.substring(0, q);
        // 网站资产打包在 assets/public/ 子目录下
        try {
            InputStream in = assets.open("public/" + path);
            byte[] data = readBytes(in);
            String ct = contentType(path);
            return newFixedLengthResponse(Response.Status.OK, ct, new java.io.ByteArrayInputStream(data), data.length);
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "not found");
        }
    }

    private String readAssetAsString(String path) throws IOException {
        InputStream in = assets.open(path);
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    private static String contentType(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
