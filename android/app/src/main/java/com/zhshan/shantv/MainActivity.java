package com.zhshan.shantv;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 山影视主界面：内置本地服务器 + WebView；电视自动进入 TV 桌面 */
public class MainActivity extends Activity {

    private static final String BASE = "http://127.0.0.1:17891";
    private static final String PREFS = "shan_tv_prefs";
    private static final String KEY_FORCE_MODE = "force_mode"; // tv / phone / (空)=自动

    private WebView webView;
    private LocalServer server;
    private boolean isTv = false;
    private long lastBackPress = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        isTv = "tv".equals(getForceMode()) || (!"phone".equals(getForceMode()) && detectTv());

        webView = new WebView(this);
        setContentView(webView);
        webView.setBackgroundColor(0xFF08080E);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " ShanTV/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                // 电视端把 config.js 的代理切到 Cloudflare 线上地址，并注入自动回退逻辑
                if (isTv && request.getMethod().equals("GET") && request.getUrl() != null
                        && request.getUrl().getPath() != null
                        && request.getUrl().getPath().endsWith("/js/config.js")) {
                    try {
                        InputStream in = getAssets().open("public/js/config.js");
                        String js = new String(readAll(in), StandardCharsets.UTF_8);
                        in.close();
                        String inject = "const PROXY_URL='https://libretv-74y.pages.dev/proxy/';"
                                + "window.__CF_PROXY_FALLBACK='/proxy/';"
                                + ";(function(){try{var FB=window.__CF_PROXY_FALLBACK;"
                                + "var CF='https://libretv-74y.pages.dev/proxy/';var of=window.fetch.bind(window);"
                                + "function toLocal(u){return FB+u.slice(CF.length);}"
                                + "window.fetch=function(input,init){try{"
                                + "var u=(typeof input==='string')?input:((input&&input.url)||'');"
                                + "if(u.indexOf(CF)===0){return of(u,init).then(function(r){if(r.ok)return r;"
                                + "return of(toLocal(u),init).then(function(r2){return r2.ok?r2:r;})"
                                + ".catch(function(){return r;});},function(e){return of(toLocal(u),init);});}"
                                + "return of(input,init);}catch(e){return of(input,init);}};}catch(e){}})();";
                        js = js.replace("const PROXY_URL = '/proxy/';", inject);
                        return new WebResourceResponse("application/javascript", "utf-8",
                                new ByteArrayInputStream(js.getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception ignore) { }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void switchMode(String mode) {
                runOnUiThread(() -> MainActivity.this.switchMode(mode));
            }
        }, "ShanTV");

        try {
            server = new LocalServer(this);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        webView.loadUrl(isTv ? BASE + "/tv.html" : BASE + "/");
    }

    /** 手动切换显示模式，供 TV 首页按钮调用 */
    public void switchMode(final String mode) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FORCE_MODE, mode).apply();
        Toast.makeText(this, "已切换" + ("tv".equals(mode) ? "电视版" : "手机版") + "，正在重启…", Toast.LENGTH_SHORT).show();
        webView.postDelayed(new Runnable() {
            @Override public void run() { recreate(); }
        }, 500);
    }

    private String getForceMode() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_FORCE_MODE, "");
    }

    /** 多重判断：系统电视模式 / leanback 特性 / 大屏高分辨率 */
    private boolean detectTv() {
        try {
            UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        } catch (Exception ignore) { }
        try {
            if (getPackageManager().hasSystemFeature("android.software.leanback")) return true;
        } catch (Exception ignore) { }
        try {
            DisplayMetrics m = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(m);
            float widthDp = m.widthPixels / m.density;
            float heightDp = m.heightPixels / m.density;
            // 电视/盒子的逻辑屏幕通常 >= 960dp 宽
            if (widthDp >= 960 && heightDp >= 540) return true;
        } catch (Exception ignore) { }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        final long now = System.currentTimeMillis();
        webView.evaluateJavascript(
                "(function(){var r=document.getElementById('resultsArea');" +
                "return (r && !r.classList.contains('hidden')) ? '1' : '0';})()",
                value -> {
                    boolean showingResults = value != null && value.contains("1");
                    boolean within = now - lastBackPress < 2500;
                    if (showingResults && !within) {
                        lastBackPress = now;
                        webView.evaluateJavascript(
                                "if(typeof resetToHome==='function'){resetToHome();}else{window.location.href='/';}", null);
                        Toast.makeText(MainActivity.this, "已返回首页，再按一次退出", Toast.LENGTH_SHORT).show();
                    } else if (!within) {
                        lastBackPress = now;
                        Toast.makeText(MainActivity.this, "再按一次退出山影视", Toast.LENGTH_SHORT).show();
                    } else {
                        super.onBackPressed();
                    }
                });
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}
