package com.nudroidlabs.nubrowse;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.google.com/";
    private static final String UA_TV = "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 NuBrowseTV/1.0";
    private static final String UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private boolean adBlockEnabled = true;
    private String uaMode = "TV";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(16, 16, 16));
        setContentView(root);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(6), dp(8), dp(6));
        toolbar.setBackgroundColor(Color.rgb(28, 28, 28));
        page.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        Button back = button("←");
        Button forward = button("→");
        Button home = button("⌂");
        Button refresh = button("↻");
        Button menu = button("⋮");

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.LTGRAY);
        addressBar.setHint("Search or enter URL");
        addressBar.setTextSize(18);
        addressBar.setPadding(dp(14), 0, dp(14), 0);
        addressBar.setBackgroundResource(com.nudroidlabs.nubrowse.R.drawable.address_bg);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setFocusable(true);
        addressBar.setFocusableInTouchMode(true);

        Button go = button("GO");

        toolbar.addView(back, fixed(dp(52)));
        toolbar.addView(forward, fixed(dp(52)));
        toolbar.addView(home, fixed(dp(52)));
        toolbar.addView(refresh, fixed(dp(52)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        toolbar.addView(addressBar, addressParams);
        toolbar.addView(go, fixed(dp(64)));
        toolbar.addView(menu, fixed(dp(52)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        page.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        page.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        configureWebView();

        back.setOnClickListener(v -> goBack());
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        home.setOnClickListener(v -> webView.loadUrl(HOME_URL));
        refresh.setOnClickListener(v -> webView.reload());
        go.setOnClickListener(v -> navigate(addressBar.getText().toString()));
        menu.setOnClickListener(v -> showSettings());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                navigate(addressBar.getText().toString());
                return true;
            }
            return false;
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(HOME_URL);
        }

        addressBar.requestFocus();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(UA_TV);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                Toast.makeText(MainActivity.this, "Unsupported link: " + scheme, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlockEnabled && AdBlocker.shouldBlock(request.getUrl().toString())) {
                    return emptyResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                addressBar.setText(url);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                addressBar.setText(url);
                injectCosmeticAdCleanup();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                Toast.makeText(MainActivity.this, "Popup blocked", Toast.LENGTH_SHORT).show();
                return false;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("NuBrowse")
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer = new FrameLayout(MainActivity.this);
                fullscreenContainer.setBackgroundColor(Color.BLACK);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                root.addView(fullscreenContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.requestFocus();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private WebResourceResponse emptyResponse() {
        byte[] empty = new byte[0];
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", null, new ByteArrayInputStream(empty));
    }

    private void injectCosmeticAdCleanup() {
        if (!adBlockEnabled) return;
        String js = "javascript:(function(){" +
                "var s=['iframe[src*=\\\"ad\\\"]','[id^=\\\"ad-\\\"]','[class^=\\\"ad-\\\"]','[class*=\\\" ad-\\\"]','[aria-label*=\\\"advertisement\\\" i]'];" +
                "s.forEach(function(q){document.querySelectorAll(q).forEach(function(e){e.style.display='none';});});" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void navigate(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) return;
        String lower = value.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            webView.loadUrl(value);
        } else if (value.contains(".") && !value.contains(" ")) {
            webView.loadUrl("https://" + value);
        } else {
            webView.loadUrl("https://www.google.com/search?q=" + Uri.encode(value));
        }
        webView.requestFocus();
    }

    private void showSettings() {
        String blocker = adBlockEnabled ? "Ad blocker: ON" : "Ad blocker: OFF";
        String[] items = {blocker, "User agent: " + uaMode, "Clear browser data"};
        new AlertDialog.Builder(this)
                .setTitle("NuBrowse M1")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        adBlockEnabled = !adBlockEnabled;
                        Toast.makeText(this, "Ad blocker " + (adBlockEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 1) {
                        showUserAgentPicker();
                    } else {
                        clearBrowserData();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showUserAgentPicker() {
        String[] modes = {"TV", "Desktop", "Mobile"};
        new AlertDialog.Builder(this)
                .setTitle("User agent")
                .setSingleChoiceItems(modes, uaIndex(), (dialog, which) -> {
                    uaMode = modes[which];
                    WebSettings settings = webView.getSettings();
                    if (which == 0) settings.setUserAgentString(UA_TV);
                    if (which == 1) settings.setUserAgentString(UA_DESKTOP);
                    if (which == 2) settings.setUserAgentString(UA_MOBILE);
                    dialog.dismiss();
                    webView.reload();
                })
                .show();
    }

    private int uaIndex() {
        if ("Desktop".equals(uaMode)) return 1;
        if ("Mobile".equals(uaMode)) return 2;
        return 0;
    }

    private void clearBrowserData() {
        webView.clearCache(true);
        webView.clearHistory();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        Toast.makeText(this, "Browser data cleared", Toast.LENGTH_SHORT).show();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackgroundResource(com.nudroidlabs.nubrowse.R.drawable.button_bg);
        return button;
    }

    private LinearLayout.LayoutParams fixed(int width) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(44));
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void goBack() {
        if (customView != null) {
            hideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(fullscreenContainer);
        fullscreenContainer = null;
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        webView.requestFocus();
    }

    @Override
    public void onBackPressed() {
        goBack();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            showSettings();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
