package com.zip2apk.template;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;

/** Loads assets/www/index.html inside a WebView (served from https://appassets.local/). */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.local";
    private WebView web;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        setContentView(web, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return serve(u.getPath());
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String scheme = u.getScheme();
                if (HOST.equals(u.getHost()) || "http".equals(scheme) || "https".equals(scheme)) {
                    return false;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (Exception ignored) { }
                return true;
            }
        });

        if (state != null) web.restoreState(state);
        else web.loadUrl("https://" + HOST + "/index.html");
    }

    private WebResourceResponse serve(String path) {
        if (path == null || path.isEmpty()) path = "/index.html";
        if (path.endsWith("/")) path += "index.html";
        if (path.contains("..")) return notFound();
        try {
            InputStream in = getAssets().open("www" + path);
            String mime = mimeOf(path);
            boolean text = mime.startsWith("text/") || mime.contains("javascript")
                    || mime.contains("json") || mime.contains("xml");
            return new WebResourceResponse(mime, text ? "UTF-8" : null, in);
        } catch (IOException e) {
            return notFound();
        }
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String mimeOf(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        switch (ext) {
            case "html": case "htm": return "text/html";
            case "js": case "mjs": return "text/javascript";
            case "css": return "text/css";
            case "json": case "map": return "application/json";
            case "svg": return "image/svg+xml";
            case "wasm": return "application/wasm";
            default:
                String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                return m != null ? m : "application/octet-stream";
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
