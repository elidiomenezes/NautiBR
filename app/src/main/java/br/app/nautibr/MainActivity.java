package br.app.nautibr;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private static final String CATALOG = "https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster";
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(this);
        Button home = new Button(this);
        home.setText("Cartas da Marinha");
        home.setOnClickListener(v -> web.loadUrl(CATALOG));
        Button back = new Button(this);
        back.setText("Voltar");
        back.setOnClickListener(v -> { if (web.canGoBack()) web.goBack(); });
        toolbar.addView(home, new LinearLayout.LayoutParams(0, -2, 2));
        toolbar.addView(back, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(toolbar);
        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.getSettings().setSupportZoom(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) return false;
                return true;
            }
        });
        web.setDownloadListener((url, userAgent, disposition, mimeType, contentLength) -> {
            try {
                Uri uri = Uri.parse(url);
                if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("URL insegura");
                String filename = android.webkit.URLUtil.guessFileName(url, disposition, mimeType);
                filename = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
                DownloadManager.Request req = new DownloadManager.Request(uri);
                req.setTitle(filename);
                req.setDescription("Carta nautica - NautiBR");
                req.setMimeType(mimeType);
                req.addRequestHeader("User-Agent", userAgent == null ? "NautiBR" : userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                ((DownloadManager)getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
                Toast.makeText(this, "Baixando: " + filename, Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Falha no download: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        if (state == null) web.loadUrl(CATALOG);
        else web.restoreState(state);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        web.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}