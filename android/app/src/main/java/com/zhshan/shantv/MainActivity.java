package com.zhshan.shantv;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.widget.Toast;

public class MainActivity extends Activity {

    private WebView webView;
    private LocalServer server;
    private long lastBackPress = 0;
    private boolean isTv = false;

    private boolean detectTv() {
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        return getPackageManager().hasSystemFeature("android.software.leanback");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isTv = detectTv();

        webView = new WebView(this);
        setContentView(webView);
        webView.setBackgroundColor(Color.BLACK);

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
            public void onPageFinished(WebView view, String url) {
                if (!isTv) return;
                if (url != null && url.contains("tv.html")) {
                    // TV 专用首页：自带 1280 宽的电视版式，无需注入
                    return;
                }
                // 其余页面(搜索/播放/观看)：固定 1280 布局宽 + 整体放大 1.5 倍，
                // 修复电视上文字过小/海报比例失衡，同时保持横屏信息量
                String js = "(function(){"
                        + "var m=document.querySelector('meta[name=\"viewport\"]');"
                        + "if(m){m.setAttribute('content','width=1280');}"
                        + "var st=document.getElementById('shan-tv-style');"
                        + "if(!st){st=document.createElement('style');st.id='shan-tv-style';"
                        + "st.textContent='body{zoom:1.5}"
                        + "*:focus{outline:4px solid #ff5c8a!important;outline-offset:3px;border-radius:8px}"
                        + "';document.head.appendChild(st);}"
                        + "})()";
                view.evaluateJavascript(js, null);
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        if (isTv) {
            // 关闭系统字体自动缩放，避免破坏布局比例
            s.setTextZoom(100);
            s.setSupportZoom(false);
            s.setBuiltInZoomControls(false);
        }

        try {
            server = new LocalServer(this);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        // 电视从专用 TV 首页启动；手机保持原首页
        webView.loadUrl(isTv ? "http://127.0.0.1:17891/tv.html" : "http://127.0.0.1:17891/");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // 没有历史记录时：若正处于搜索结果页，先返回首页；再按一次才退出
        final long now = System.currentTimeMillis();
        webView.evaluateJavascript(
                "(function(){var r=document.getElementById('resultsArea');" +
                "return (r && !r.classList.contains('hidden')) ? '1' : '0';})()",
                value -> {
                    boolean showingResults = value != null && value.contains("1");
                    boolean withinDoublePress = now - lastBackPress < 2500;
                    if (showingResults && !withinDoublePress) {
                        lastBackPress = now;
                        webView.evaluateJavascript(
                                "if(typeof resetToHome==='function'){resetToHome();}else{window.location.href='/';}",
                                null);
                        Toast.makeText(MainActivity.this, "已返回首页，再按一次退出", Toast.LENGTH_SHORT).show();
                    } else if (!withinDoublePress) {
                        lastBackPress = now;
                        Toast.makeText(MainActivity.this, "再按一次退出山影视", Toast.LENGTH_SHORT).show();
                    } else {
                        super.onBackPressed();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}
