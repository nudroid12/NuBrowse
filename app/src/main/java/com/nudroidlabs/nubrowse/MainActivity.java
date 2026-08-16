package com.nudroidlabs.nubrowse;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {

    private static final String HOME_URL = "https://www.google.com/";
    private static final String UA_TV = "Mozilla/5.0 (Linux; Android 14; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 NuBrowseTV/3.0";
    private static final String UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String PREFS = "nubrowse_m2";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ADBLOCK = "adblock";
    private static final String KEY_POPUP_BLOCK = "popup_block";
    private static final String KEY_THIRD_PARTY_COOKIES = "third_party_cookies";
    private static final String KEY_UA = "user_agent";
    private static final int MAX_HISTORY = 50;
    private static final int REQUEST_WRITE_STORAGE = 130;

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private FrameLayout keyboardOverlay;
    private FrameLayout remoteOverlay;
    private LinearLayout keyboardRows;
    private View customView;
    private View lastContentFocus;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;

    private boolean adBlockEnabled = true;
    private boolean popupBlockEnabled = true;
    private boolean thirdPartyCookiesEnabled = true;
    private boolean keyboardNumbers = false;
    private String uaMode = "TV";
    private final AtomicInteger blockedRequests = new AtomicInteger(0);

    private String pendingDownloadUrl;
    private String pendingDownloadUserAgent;
    private String pendingDownloadDisposition;
    private String pendingDownloadMime;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();

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
        toolbar.setBackgroundColor(Color.rgb(24, 24, 24));
        page.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        Button back = button("←");
        Button forward = button("→");
        Button home = button("⌂");
        Button refresh = button("↻");
        Button bookmark = button("★");
        Button remote = button("RMT");
        Button menu = button("⋮");

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.LTGRAY);
        addressBar.setHint("Search or enter URL");
        addressBar.setTextSize(18);
        addressBar.setPadding(dp(14), 0, dp(14), 0);
        addressBar.setBackgroundResource(R.drawable.address_bg);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setFocusable(true);
        addressBar.setFocusableInTouchMode(true);
        addressBar.setShowSoftInputOnFocus(false);

        Button go = button("GO");

        toolbar.addView(back, fixed(dp(50)));
        toolbar.addView(forward, fixed(dp(50)));
        toolbar.addView(home, fixed(dp(50)));
        toolbar.addView(refresh, fixed(dp(50)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        addressParams.setMargins(dp(6), 0, dp(6), 0);
        toolbar.addView(addressBar, addressParams);
        toolbar.addView(go, fixed(dp(62)));
        toolbar.addView(bookmark, fixed(dp(50)));
        toolbar.addView(remote, fixed(dp(66)));
        toolbar.addView(menu, fixed(dp(50)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        page.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        page.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        configureWebView();

        back.setOnClickListener(v -> browserBack());
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        home.setOnClickListener(v -> loadHome());
        refresh.setOnClickListener(v -> webView.reload());
        go.setOnClickListener(v -> submitAddress());
        bookmark.setOnClickListener(v -> toggleBookmark());
        bookmark.setOnLongClickListener(v -> { showSavedList(KEY_BOOKMARKS, "Bookmarks"); return true; });
        remote.setOnClickListener(v -> showTestRemote());
        menu.setOnClickListener(v -> showSettings());

        addressBar.setOnClickListener(v -> showTvKeyboard());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitAddress();
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

    private void loadSettings() {
        adBlockEnabled = prefs.getBoolean(KEY_ADBLOCK, true);
        popupBlockEnabled = prefs.getBoolean(KEY_POPUP_BLOCK, true);
        thirdPartyCookiesEnabled = prefs.getBoolean(KEY_THIRD_PARTY_COOKIES, true);
        uaMode = prefs.getString(KEY_UA, "TV");
        if (uaMode == null) uaMode = "TV";
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean(KEY_ADBLOCK, adBlockEnabled)
                .putBoolean(KEY_POPUP_BLOCK, popupBlockEnabled)
                .putBoolean(KEY_THIRD_PARTY_COOKIES, thirdPartyCookiesEnabled)
                .putString(KEY_UA, uaMode)
                .apply();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        applyUserAgent();
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                Toast.makeText(MainActivity.this, "Unsupported link: " + scheme, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adBlockEnabled && AdBlocker.shouldBlock(request.getUrl().toString(), view.getUrl())) {
                    blockedRequests.incrementAndGet();
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
                recordHistory(url, view.getTitle());
                injectCosmeticAdCleanup();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, "Page failed to load", Toast.LENGTH_SHORT).show();
                }
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
                if (popupBlockEnabled && !isUserGesture) {
                    Toast.makeText(MainActivity.this, "Popup blocked", Toast.LENGTH_SHORT).show();
                    return false;
                }
                WebView popup = new WebView(MainActivity.this);
                popup.setVisibility(View.INVISIBLE);
                root.addView(popup, new FrameLayout.LayoutParams(1, 1));
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        String url = request.getUrl().toString();
                        root.removeView(child);
                        child.destroy();
                        hideTvKeyboard();
                        webView.loadUrl(url);
                        webView.requestFocus();
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView child, String url, Bitmap favicon) {
                        if (url != null && !"about:blank".equals(url)) {
                            child.stopLoading();
                            root.removeView(child);
                            child.destroy();
                            hideTvKeyboard();
                            webView.loadUrl(url);
                            webView.requestFocus();
                        }
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
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
                hideTvKeyboard();
                customView = view;
                customViewCallback = callback;
                fullscreenContainer = new FrameLayout(MainActivity.this);
                fullscreenContainer.setBackgroundColor(Color.BLACK);
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                root.addView(fullscreenContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                enterImmersiveMode();
                fullscreenContainer.requestFocus();
                if (remoteOverlay != null) remoteOverlay.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content", null, new ByteArrayInputStream(new byte[0]));
    }

    private void injectCosmeticAdCleanup() {
        if (!adBlockEnabled) return;
        String js = "javascript:(function(){" +
                "var s=['iframe[src*=\\\"ad\\\"]','iframe[id*=\\\"ad\\\"]','[id^=\\\"ad-\\\"]','[id^=\\\"ads-\\\"]','[class^=\\\"ad-\\\"]','[class*=\\\" ad-\\\"]','[class^=\\\"ads-\\\"]','[class*=\\\" ads-\\\"]','[aria-label*=\\\"advertisement\\\" i]'];" +
                "s.forEach(function(q){document.querySelectorAll(q).forEach(function(e){e.style.setProperty('display','none','important');});});" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void submitAddress() {
        navigate(addressBar.getText().toString());
        hideTvKeyboard();
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

    private void loadHome() {
        hideTvKeyboard();
        webView.loadUrl(HOME_URL);
        webView.requestFocus();
    }

    private void showTvKeyboard() {
        if (keyboardOverlay != null) return;

        keyboardOverlay = new FrameLayout(this);
        keyboardOverlay.setBackgroundColor(Color.argb(220, 15, 15, 15));
        FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280), Gravity.BOTTOM);
        root.addView(keyboardOverlay, overlayParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(10), dp(18), dp(12));
        keyboardOverlay.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("TV Keyboard   •   D-pad + OK");
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(14);
        body.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        keyboardRows = new LinearLayout(this);
        keyboardRows.setOrientation(LinearLayout.VERTICAL);
        body.addView(keyboardRows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rebuildKeyboard();
        if (remoteOverlay != null) remoteOverlay.bringToFront();
    }

    private void rebuildKeyboard() {
        if (keyboardRows == null) return;
        keyboardRows.removeAllViews();

        if (!keyboardNumbers) {
            addKeyboardRow(new String[]{"Q","W","E","R","T","Y","U","I","O","P"});
            addKeyboardRow(new String[]{"A","S","D","F","G","H","J","K","L"});
            addKeyboardRow(new String[]{"Z","X","C","V","B","N","M",".com","/",":"});
            addKeyboardRow(new String[]{"123","SPACE","⌫","CLEAR","GO","CLOSE"});
        } else {
            addKeyboardRow(new String[]{"1","2","3","4","5","6","7","8","9","0"});
            addKeyboardRow(new String[]{"-","_",".","/",":","?","&","=","%","#"});
            addKeyboardRow(new String[]{"@","+","~","(",")","[","]","!","'","\""});
            addKeyboardRow(new String[]{"ABC","SPACE","⌫","CLEAR","GO","CLOSE"});
        }

        if (keyboardRows.getChildCount() > 0) {
            LinearLayout firstRow = (LinearLayout) keyboardRows.getChildAt(0);
            if (firstRow.getChildCount() > 0) firstRow.getChildAt(0).requestFocus();
        }
    }

    private void addKeyboardRow(String[] keys) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        keyboardRows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        for (String key : keys) {
            Button b = keyboardButton(key);
            float weight = ("SPACE".equals(key) ? 2.2f : (("CLEAR".equals(key) || "CLOSE".equals(key)) ? 1.4f : 1f));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            row.addView(b, p);
        }
    }

    private Button keyboardButton(String key) {
        Button b = button(key);
        b.setTextSize(key.length() > 4 ? 13 : 16);
        b.setOnClickListener(v -> handleKeyboardKey(key));
        return b;
    }

    private void handleKeyboardKey(String key) {
        switch (key) {
            case "123": keyboardNumbers = true; rebuildKeyboard(); break;
            case "ABC": keyboardNumbers = false; rebuildKeyboard(); break;
            case "SPACE": replaceSelection(" "); break;
            case "⌫": backspaceAddress(); break;
            case "CLEAR": addressBar.setText(""); addressBar.setSelection(0); break;
            case "GO": submitAddress(); break;
            case "CLOSE": hideTvKeyboard(); addressBar.requestFocus(); break;
            default: replaceSelection(key); break;
        }
    }

    private void replaceSelection(String text) {
        Editable e = addressBar.getText();
        int start = addressBar.getSelectionStart();
        int end = addressBar.getSelectionEnd();
        if (start < 0 || end < 0) {
            e.append(text);
            addressBar.setSelection(e.length());
            return;
        }
        int a = Math.min(start, end);
        int b = Math.max(start, end);
        e.replace(a, b, text);
        addressBar.setSelection(a + text.length());
    }

    private void backspaceAddress() {
        Editable e = addressBar.getText();
        int start = addressBar.getSelectionStart();
        int end = addressBar.getSelectionEnd();
        if (start < 0 || end < 0) return;
        int a = Math.min(start, end);
        int b = Math.max(start, end);
        if (a != b) {
            e.delete(a, b);
            addressBar.setSelection(a);
        } else if (a > 0) {
            e.delete(a - 1, a);
            addressBar.setSelection(a - 1);
        }
    }

    private void hideTvKeyboard() {
        if (keyboardOverlay == null) return;
        root.removeView(keyboardOverlay);
        keyboardOverlay = null;
        keyboardRows = null;
    }

    private void showTestRemote() {
        if (remoteOverlay != null) return;
        lastContentFocus = getCurrentFocus();
        if (lastContentFocus == null) lastContentFocus = webView;

        remoteOverlay = new FrameLayout(this);
        remoteOverlay.setFocusable(false);
        remoteOverlay.setFocusableInTouchMode(false);
        root.addView(remoteOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shield = new View(this);
        shield.setBackgroundColor(Color.argb(70, 0, 0, 0));
        shield.setClickable(true);
        shield.setFocusable(false);
        shield.setOnTouchListener((v, event) -> true);
        remoteOverlay.addView(shield, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(14), dp(14), dp(14), dp(14));
        panel.setBackgroundResource(R.drawable.remote_bg);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        panelParams.setMargins(0, dp(8), dp(16), dp(8));
        remoteOverlay.addView(panel, panelParams);

        TextView title = new TextView(this);
        title.setText("TEST REMOTE\nTouch locked");
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(14);
        panel.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));

        panel.addView(remoteRow("", "▲", ""));
        panel.addView(remoteRow("◀", "OK", "▶"));
        panel.addView(remoteRow("", "▼", ""));
        panel.addView(remoteRow("BACK", "HOME", "MENU"));
        panel.addView(remoteRow("", "CLOSE", ""));

        if (lastContentFocus != null) lastContentFocus.requestFocus();
    }

    private LinearLayout remoteRow(String left, String centre, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(3), 0, dp(3));
        addRemoteCell(row, left);
        addRemoteCell(row, centre);
        addRemoteCell(row, right);
        return row;
    }

    private void addRemoteCell(LinearLayout row, String label) {
        if (label.isEmpty()) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, dp(52), 1f));
            return;
        }
        Button b = button(label);
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);
        b.setTextSize(label.length() > 3 ? 12 : 16);
        b.setOnClickListener(v -> handleRemoteButton(label));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(52), 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        row.addView(b, p);
    }

    private void handleRemoteButton(String label) {
        switch (label) {
            case "▲": sendVirtualDpad(KeyEvent.KEYCODE_DPAD_UP); break;
            case "▼": sendVirtualDpad(KeyEvent.KEYCODE_DPAD_DOWN); break;
            case "◀": sendVirtualDpad(KeyEvent.KEYCODE_DPAD_LEFT); break;
            case "▶": sendVirtualDpad(KeyEvent.KEYCODE_DPAD_RIGHT); break;
            case "OK": sendVirtualDpad(KeyEvent.KEYCODE_DPAD_CENTER); break;
            case "BACK": browserBack(); break;
            case "HOME": loadHome(); break;
            case "MENU": showSettings(); break;
            case "CLOSE": hideTestRemote(); break;
        }
    }

    private void sendVirtualDpad(int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, -1, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_DPAD);
        dispatchKeyEvent(down);
        dispatchKeyEvent(up);
    }

    private void hideTestRemote() {
        if (remoteOverlay == null) return;
        root.removeView(remoteOverlay);
        remoteOverlay = null;
        if (lastContentFocus != null) lastContentFocus.requestFocus();
        lastContentFocus = null;
    }

    private void toggleBookmark() {
        String url = webView.getUrl();
        if (url == null || url.trim().isEmpty()) return;
        JSONArray source = readArray(KEY_BOOKMARKS);
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < source.length(); i++) {
            JSONObject o = source.optJSONObject(i);
            if (o == null) continue;
            if (url.equals(o.optString("url"))) {
                removed = true;
                continue;
            }
            out.put(o);
        }
        if (!removed) {
            JSONObject o = new JSONObject();
            try {
                o.put("url", url);
                o.put("title", safeTitle(webView.getTitle(), url));
            } catch (Exception ignored) {}
            JSONArray withNew = new JSONArray();
            withNew.put(o);
            for (int i = 0; i < out.length(); i++) withNew.put(out.opt(i));
            out = withNew;
        }
        saveArray(KEY_BOOKMARKS, out);
        Toast.makeText(this, removed ? "Bookmark removed" : "Bookmarked", Toast.LENGTH_SHORT).show();
    }

    private void recordHistory(String url, String title) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) return;
        JSONArray source = readArray(KEY_HISTORY);
        JSONArray out = new JSONArray();
        JSONObject current = new JSONObject();
        try {
            current.put("url", url);
            current.put("title", safeTitle(title, url));
        } catch (Exception ignored) {}
        out.put(current);
        for (int i = 0; i < source.length() && out.length() < MAX_HISTORY; i++) {
            JSONObject o = source.optJSONObject(i);
            if (o == null || url.equals(o.optString("url"))) continue;
            out.put(o);
        }
        saveArray(KEY_HISTORY, out);
    }

    private void showSavedList(String key, String title) {
        JSONArray array = readArray(key);
        if (array.length() == 0) {
            Toast.makeText(this, title + " is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            String url = o.optString("url");
            labels.add(o.optString("title", url));
            urls.add(url);
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < urls.size()) {
                        hideTvKeyboard();
                        webView.loadUrl(urls.get(which));
                        webView.requestFocus();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private JSONArray readArray(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void saveArray(String key, JSONArray value) {
        prefs.edit().putString(key, value.toString()).apply();
    }

    private String safeTitle(String title, String url) {
        String t = title == null ? "" : title.trim();
        if (!t.isEmpty()) return t;
        try {
            Uri uri = Uri.parse(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (Exception ignored) {
            return url;
        }
    }

    private void showSettings() {
        String blocker = "Ad blocker: " + (adBlockEnabled ? "ON" : "OFF") + "  •  blocked " + blockedRequests.get();
        String popup = "Popup blocker: " + (popupBlockEnabled ? "ON" : "OFF");
        String cookies = "Third-party cookies: " + (thirdPartyCookiesEnabled ? "ON" : "OFF");
        String[] items = {
                "Bookmarks",
                "History",
                "Downloads",
                remoteOverlay == null ? "Open test remote" : "Close test remote",
                blocker,
                popup,
                cookies,
                "User agent: " + uaMode,
                "Clear browsing data",
                "About NuBrowse M3"
        };
        new AlertDialog.Builder(this)
                .setTitle("NuBrowse M3")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showSavedList(KEY_BOOKMARKS, "Bookmarks");
                    else if (which == 1) showSavedList(KEY_HISTORY, "History");
                    else if (which == 2) openDownloads();
                    else if (which == 3) {
                        if (remoteOverlay == null) showTestRemote(); else hideTestRemote();
                    } else if (which == 4) {
                        adBlockEnabled = !adBlockEnabled;
                        saveSettings();
                        Toast.makeText(this, "Ad blocker " + (adBlockEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 5) {
                        popupBlockEnabled = !popupBlockEnabled;
                        saveSettings();
                        Toast.makeText(this, "Popup blocker " + (popupBlockEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                    } else if (which == 6) {
                        thirdPartyCookiesEnabled = !thirdPartyCookiesEnabled;
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled);
                        saveSettings();
                        Toast.makeText(this, "Third-party cookies " + (thirdPartyCookiesEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 7) showUserAgentPicker();
                    else if (which == 8) clearBrowserData();
                    else showAbout();
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
                    applyUserAgent();
                    saveSettings();
                    dialog.dismiss();
                    webView.reload();
                })
                .show();
    }

    private void applyUserAgent() {
        if (webView == null) return;
        WebSettings settings = webView.getSettings();
        if ("Desktop".equals(uaMode)) settings.setUserAgentString(UA_DESKTOP);
        else if ("Mobile".equals(uaMode)) settings.setUserAgentString(UA_MOBILE);
        else settings.setUserAgentString(UA_TV);
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
        WebStorage.getInstance().deleteAllData();
        prefs.edit().remove(KEY_HISTORY).apply();
        blockedRequests.set(0);
        Toast.makeText(this, "Browsing data and history cleared", Toast.LENGTH_SHORT).show();
    }

    private void startDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Toast.makeText(this, "Unsupported download link", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadUrl = url;
            pendingDownloadUserAgent = userAgent;
            pendingDownloadDisposition = contentDisposition;
            pendingDownloadMime = mimeType;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_STORAGE);
            return;
        }
        enqueueDownload(url, userAgent, contentDisposition, mimeType);
    }

    private void enqueueDownload(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setDescription("Downloading with NuBrowse");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            if (mimeType != null && !mimeType.isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            String referer = webView.getUrl();
            if (referer != null && !referer.isEmpty()) request.addRequestHeader("Referer", referer);

            DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("DownloadManager unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "Download started: " + fileName, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed to start", Toast.LENGTH_LONG).show();
        }
    }

    private void openDownloads() {
        try {
            Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Downloads screen unavailable on this device", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_WRITE_STORAGE) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingDownloadUrl != null) {
            enqueueDownload(pendingDownloadUrl, pendingDownloadUserAgent, pendingDownloadDisposition, pendingDownloadMime);
        } else {
            Toast.makeText(this, "Storage permission is required for downloads on this Android version", Toast.LENGTH_LONG).show();
        }
        pendingDownloadUrl = null;
        pendingDownloadUserAgent = null;
        pendingDownloadDisposition = null;
        pendingDownloadMime = null;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("NuBrowse M3")
                .setMessage("TV-first browser\n\nM3: stronger ad blocking, smart popup handling, downloads, persistent settings, cookie control and fullscreen stability.")
                .setPositiveButton("OK", null)
                .show();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackgroundResource(R.drawable.button_bg);
        return button;
    }

    private LinearLayout.LayoutParams fixed(int width) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(46));
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void browserBack() {
        if (keyboardOverlay != null) {
            hideTvKeyboard();
            addressBar.requestFocus();
        } else if (customView != null) {
            hideCustomView();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(fullscreenContainer);
        fullscreenContainer = null;
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        exitImmersiveMode();
        webView.requestFocus();
        if (remoteOverlay != null) remoteOverlay.bringToFront();
    }

    @Override
    public void onBackPressed() {
        if (remoteOverlay != null) {
            hideTestRemote();
            return;
        }
        browserBack();
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
            webView.setDownloadListener(null);
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
