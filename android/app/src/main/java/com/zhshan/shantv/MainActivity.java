package com.zhshan.shantv;

import android.app.Activity;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

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

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        try {
            server = new LocalServer(this);
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        webView.loadUrl("http://127.0.0.1:17891/");
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
