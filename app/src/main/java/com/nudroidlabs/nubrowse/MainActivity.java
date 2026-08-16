package com.nudroidlabs.nubrowse;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
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
    private static final String UA_TV = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 NuBrowseTV/4.1";
    private static final String UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String PREFS = "nubrowse_m2";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ADBLOCK = "adblock";
    private static final String KEY_POPUP_BLOCK = "popup_block";
    private static final String KEY_THIRD_PARTY_COOKIES = "third_party_cookies";
    private static final String KEY_UA = "user_agent";
    private static final String KEY_DEV_MODE = "dev_mode";
    private static final String KEY_ZOOM = "zoom_percent";
    private static final int MAX_HISTORY = 50;
    private static final int REQUEST_WRITE_STORAGE = 130;

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private FrameLayout remoteOverlay;
    private Button zoomLabel;
    private Button devRemoteFab;
    private AdBlocker adBlocker;
    private View customView;
    private View lastContentFocus;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;

    private boolean adBlockEnabled = true;
    private boolean popupBlockEnabled = true;
    private boolean thirdPartyCookiesEnabled = true;
    private boolean devModeEnabled = true;
    private int zoomPercent = 100;
    private String uaMode = "TV";
    private boolean initialZoomApplied = false;
    private LinearLayout pageContainer;
    private int safeInsetLeft = 0;
    private int safeInsetRight = 0;
    private final AtomicInteger blockedRequests = new AtomicInteger(0);
    private final AtomicInteger blockedPopups = new AtomicInteger(0);
    private final AtomicInteger pageBlockedRequests = new AtomicInteger(0);
    private final AtomicInteger pageBlockedPopups = new AtomicInteger(0);
    private volatile String currentPageUrl = HOME_URL;

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
        NuBrowseApp.markPhase(this, "activity_start");

        String lastCrash = NuBrowseApp.getLastCrash(this);
        if (lastCrash != null && !lastCrash.trim().isEmpty()) {
            showCrashRecovery(lastCrash);
            return;
        }

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadSettings();
        adBlocker = new AdBlocker(this);
        NuBrowseApp.markPhase(this, "building_ui");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        setContentView(root);

        pageContainer = new LinearLayout(this);
        pageContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(pageContainer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        installSafeAreaInsets();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(7), dp(4), dp(7), dp(4));
        toolbar.setBackgroundColor(Color.rgb(21, 23, 25));
        pageContainer.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        Button back = button("←");
        Button forward = button("→");
        Button home = button("⌂");
        Button refresh = button("↻");
        Button bookmark = button("★");
        Button menu = button("⋮");

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(Color.LTGRAY);
        addressBar.setHint("Search or enter URL");
        addressBar.setTextSize(16);
        addressBar.setPadding(dp(13), 0, dp(13), 0);
        addressBar.setBackgroundResource(R.drawable.address_bg);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setFocusable(true);
        addressBar.setFocusableInTouchMode(true);
        addressBar.setShowSoftInputOnFocus(true);

        Button go = button("GO");
        Button zoomOut = button("−");
        zoomLabel = button("100%");
        Button zoomIn = button("+");

        toolbar.addView(back, fixed(dp(38)));
        toolbar.addView(forward, fixed(dp(38)));
        toolbar.addView(home, fixed(dp(38)));
        toolbar.addView(refresh, fixed(dp(38)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        addressParams.setMargins(dp(5), 0, dp(5), 0);
        toolbar.addView(addressBar, addressParams);
        toolbar.addView(go, fixed(dp(50)));
        toolbar.addView(bookmark, fixed(dp(38)));
        toolbar.addView(zoomOut, fixed(dp(32)));
        toolbar.addView(zoomLabel, fixed(dp(52)));
        toolbar.addView(zoomIn, fixed(dp(32)));
        toolbar.addView(menu, fixed(dp(38)));
        updateZoomLabel();

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        pageContainer.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        NuBrowseApp.markPhase(this, "creating_webview");
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        pageContainer.addView(webView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        configureWebView();
        createDevRemoteFab();
        NuBrowseApp.markPhase(this, "webview_configured");

        back.setOnClickListener(v -> browserBack());
        forward.setOnClickListener(v -> { if (webView.canGoForward()) webView.goForward(); });
        home.setOnClickListener(v -> loadHome());
        refresh.setOnClickListener(v -> webView.reload());
        go.setOnClickListener(v -> submitAddress());
        bookmark.setOnClickListener(v -> toggleBookmark());
        bookmark.setOnLongClickListener(v -> { showSavedList(KEY_BOOKMARKS, "Bookmarks"); return true; });
        zoomOut.setOnClickListener(v -> changeZoom(-10));
        zoomLabel.setOnClickListener(v -> resetZoom());
        zoomIn.setOnClickListener(v -> changeZoom(10));
        menu.setOnClickListener(v -> showSettings());

        addressBar.setOnClickListener(v -> showSystemKeyboard());
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                submitAddress();
                return true;
            }
            return false;
        });

        NuBrowseApp.markPhase(this, "loading_initial_page");
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            currentPageUrl = HOME_URL;
            webView.loadUrl(HOME_URL);
        }
        addressBar.requestFocus();
        NuBrowseApp.markPhase(this, "running");
    }

    private void showCrashRecovery(String report) {
        FrameLayout crashRoot = new FrameLayout(this);
        crashRoot.setBackgroundColor(Color.rgb(16, 16, 16));
        setContentView(crashRoot);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(18), dp(22), dp(18));
        crashRoot.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = new TextView(this);
        title.setText("NuBrowse recovered a runtime crash");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        body.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView hint = new TextView(this);
        hint.setText("Copy this short diagnose and send it back. Normal browser startup is paused so the crash does not loop.");
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(14);
        body.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        TextView details = new TextView(this);
        details.setText(report);
        details.setTextColor(Color.WHITE);
        details.setTextSize(13);
        details.setTextIsSelectable(true);
        details.setPadding(dp(12), dp(10), dp(12), dp(10));
        body.addView(details, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        body.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        Button copy = button("COPY DIAGNOSE");
        Button retry = button("CLEAR + TRY AGAIN");
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        copyParams.setMargins(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        retryParams.setMargins(dp(6), dp(6), dp(6), dp(6));
        actions.addView(copy, copyParams);
        actions.addView(retry, retryParams);

        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("NuBrowse runtime diagnose", report));
                Toast.makeText(this, "Diagnose copied", Toast.LENGTH_SHORT).show();
            }
        });
        retry.setOnClickListener(v -> {
            NuBrowseApp.clearLastCrash(this);
            recreate();
        });
        copy.requestFocus();
    }

    private void loadSettings() {
        adBlockEnabled = prefs.getBoolean(KEY_ADBLOCK, true);
        popupBlockEnabled = prefs.getBoolean(KEY_POPUP_BLOCK, true);
        thirdPartyCookiesEnabled = prefs.getBoolean(KEY_THIRD_PARTY_COOKIES, true);
        devModeEnabled = prefs.getBoolean(KEY_DEV_MODE, true);
        zoomPercent = prefs.getInt(KEY_ZOOM, 100);
        if (zoomPercent < 60 || zoomPercent > 180) zoomPercent = 100;
        uaMode = prefs.getString(KEY_UA, "TV");
        if (uaMode == null) uaMode = "TV";
    }

    private void saveSettings() {
        prefs.edit()
                .putBoolean(KEY_ADBLOCK, adBlockEnabled)
                .putBoolean(KEY_POPUP_BLOCK, popupBlockEnabled)
                .putBoolean(KEY_THIRD_PARTY_COOKIES, thirdPartyCookiesEnabled)
                .putBoolean(KEY_DEV_MODE, devModeEnabled)
                .putInt(KEY_ZOOM, zoomPercent)
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
        settings.setSupportZoom(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setTextZoom(100);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, userAgent, contentDisposition, mimeType));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                        String requestUrl = request.getUrl().toString();
                        if (adBlockEnabled && adBlocker.shouldBlock(requestUrl, currentPageUrl)) {
                            blockedRequests.incrementAndGet();
                            pageBlockedRequests.incrementAndGet();
                            return emptyResponse();
                        }
                        return null;
                    }
                });
            } catch (Exception ignored) {
                // Some vendor WebView builds may not expose service-worker interception cleanly.
            }
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    String target = uri.toString();
                    if (adBlockEnabled && adBlocker.isSuspiciousNavigation(target, currentPageUrl)) {
                        blockedPopups.incrementAndGet();
                        pageBlockedPopups.incrementAndGet();
                        Toast.makeText(MainActivity.this, "Ad redirect blocked", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return false;
                }
                Toast.makeText(MainActivity.this, "Unsupported link: " + scheme, Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String requestUrl = request.getUrl().toString();
                String pageUrl = currentPageUrl;
                if (adBlockEnabled && adBlocker.shouldBlock(requestUrl, pageUrl)) {
                    blockedRequests.incrementAndGet();
                    pageBlockedRequests.incrementAndGet();
                    return emptyResponse();
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                pageBlockedRequests.set(0);
                pageBlockedPopups.set(0);
                if (url != null && !url.isEmpty()) currentPageUrl = url;
                addressBar.setText(url);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectFitToScreen();
                injectCosmeticAdCleanup();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && !url.isEmpty()) currentPageUrl = url;
                addressBar.setText(url);
                recordHistory(url, view.getTitle());
                injectFitToScreen();
                view.postDelayed(MainActivity.this::injectFitToScreen, 500);
                injectCosmeticAdCleanup();
                if (!initialZoomApplied) {
                    initialZoomApplied = true;
                    if (zoomPercent != 100) {
                        webView.post(() -> webView.zoomBy(zoomPercent / 100f));
                    }
                }
                if (devRemoteFab != null) devRemoteFab.bringToFront();
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
                    blockedPopups.incrementAndGet();
                    pageBlockedPopups.incrementAndGet();
                    Toast.makeText(MainActivity.this, "Popup blocked", Toast.LENGTH_SHORT).show();
                    return false;
                }
                WebView popup = new WebView(MainActivity.this);
                popup.setVisibility(View.INVISIBLE);
                root.addView(popup, new FrameLayout.LayoutParams(1, 1));
                popup.getSettings().setJavaScriptEnabled(true);
                popup.setWebViewClient(new WebViewClient() {
                    private boolean resolved = false;

                    private void resolvePopup(WebView child, String url) {
                        if (resolved || url == null || "about:blank".equals(url)) return;
                        resolved = true;
                        child.stopLoading();
                        root.removeView(child);
                        child.destroy();

                        if (adBlockEnabled && adBlocker.isSuspiciousNavigation(url, currentPageUrl)) {
                            blockedPopups.incrementAndGet();
                            pageBlockedPopups.incrementAndGet();
                            Toast.makeText(MainActivity.this, "Ad popup blocked", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        hideSystemKeyboard();
                        webView.loadUrl(url);
                        webView.requestFocus();
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView child, WebResourceRequest request) {
                        resolvePopup(child, request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView child, String url, Bitmap favicon) {
                        resolvePopup(child, url);
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
                hideSystemKeyboard();
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
                else if (devRemoteFab != null && devModeEnabled) devRemoteFab.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
    }

    private void injectFitToScreen() {
        if (webView == null || "Mobile".equals(uaMode) || zoomPercent > 100) return;
        String js = "(function(){" +
                "try{" +
                "var d=document.documentElement,b=document.body;if(!d||!b)return;" +
                "var v=document.querySelector('meta[name=viewport]');" +
                "if(!v){v=document.createElement('meta');v.name='viewport';(document.head||d).appendChild(v);}" +
                "v.setAttribute('content','width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes, viewport-fit=cover');" +
                "d.style.setProperty('max-width','100%','important');" +
                "d.style.setProperty('overflow-x','hidden','important');" +
                "b.style.setProperty('max-width','100%','important');" +
                "b.style.setProperty('overflow-x','hidden','important');" +
                "b.style.removeProperty('zoom');" +
                "requestAnimationFrame(function(){" +
                "var vw=Math.max(1,d.clientWidth||window.innerWidth||1);" +
                "var sw=Math.max(d.scrollWidth||0,b.scrollWidth||0,vw);" +
                "if(sw>vw+2){var r=Math.max(0.50,Math.min(1,vw/sw));b.style.setProperty('zoom',String(r),'important');}" +
                "});" +
                "setTimeout(function(){try{" +
                "var vw=Math.max(1,d.clientWidth||window.innerWidth||1);var sw=Math.max(d.scrollWidth||0,b.scrollWidth||0,vw);" +
                "if(sw>vw+2){var r=Math.max(0.50,Math.min(1,vw/sw));b.style.setProperty('zoom',String(r),'important');}" +
                "}catch(x){}},450);" +
                "}catch(e){}" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void injectCosmeticAdCleanup() {
        if (!adBlockEnabled) return;
        String js = "(function(){" +
                "try{" +
                "if(!window.__nubrowseCosmeticCount){window.__nubrowseCosmeticCount=0;}" +
                "function hide(e){" +
                "if(!e||e.nodeType!==1||e.getAttribute('data-nubrowse-hidden')==='1')return;" +
                "e.setAttribute('data-nubrowse-hidden','1');" +
                "e.style.setProperty('display','none','important');" +
                "e.style.setProperty('visibility','hidden','important');" +
                "e.style.setProperty('height','0','important');" +
                "e.style.setProperty('min-height','0','important');" +
                "e.style.setProperty('margin','0','important');" +
                "e.style.setProperty('padding','0','important');" +
                "window.__nubrowseCosmeticCount++;" +
                "}" +
                "var selectors=[" +
                "'ins.adsbygoogle','amp-ad','amp-embed[type=taboola]'," +
                "'[data-ad-client]','[data-ad-slot]','[data-adunit]','[data-ad-unit]'," +
                "'[id*=\\\"google_ads\\\" i]','[class*=\\\"adsbygoogle\\\" i]'," +
                "'[id^=\\\"ad-\\\" i]','[id^=\\\"ads-\\\" i]','[class^=\\\"ad-\\\" i]','[class*=\\\" ad-\\\" i]'," +
                "'[class^=\\\"ads-\\\" i]','[class*=\\\" ads-\\\" i]'," +
                "'[id*=\\\"advert\\\" i]','[class*=\\\"advert\\\" i]'," +
                "'[id*=\\\"iklan\\\" i]','[class*=\\\"iklan\\\" i]'," +
                "'[id*=\\\"popunder\\\" i]','[class*=\\\"popunder\\\" i]'," +
                "'[aria-label*=\\\"advertisement\\\" i]','[aria-label*=\\\"sponsored\\\" i]'," +
                "'iframe[src*=\\\"doubleclick\\\" i]','iframe[src*=\\\"adserver\\\" i]','iframe[src*=\\\"googlesyndication\\\" i]'" +
                "];" +
                "function clean(root){" +
                "var base=(root&&root.querySelectorAll)?root:document;" +
                "selectors.forEach(function(q){try{base.querySelectorAll(q).forEach(hide);}catch(x){}});" +
                "try{base.querySelectorAll('a[href]').forEach(function(a){" +
                "var u=new URL(a.href,location.href);" +
                "if(!u.hostname||u.hostname===location.hostname||u.hostname.endsWith('.'+location.hostname))return;" +
                "var h=u.hostname.toLowerCase();" +
                "if(/(^|[.-])(slot|casino|togel|gacor|bet|judi)([.-]|$)/i.test(h)||" +
                "/(popads|popcash|propellerads|adsterra|exoclick|onclick|hilltopads|monetag)/i.test(h)){" +
                "var box=a.closest('div,section,aside,li,figure')||a;" +
                "var r=box.getBoundingClientRect();" +
                "if(r.width>180&&r.height>35&&r.height<500)hide(box);else hide(a);" +
                "}" +
                "});}catch(x){}" +
                "}" +
                "clean(document);" +
                "if(!window.__nubrowseObserver&&document.documentElement){" +
                "window.__nubrowseObserver=new MutationObserver(function(m){" +
                "clearTimeout(window.__nubrowseAdTimer);" +
                "window.__nubrowseAdTimer=setTimeout(function(){clean(document);},180);" +
                "});" +
                "window.__nubrowseObserver.observe(document.documentElement,{childList:true,subtree:true});" +
                "}" +
                "return window.__nubrowseCosmeticCount;" +
                "}catch(e){return -1;}" +
                "})()";
        webView.evaluateJavascript(js, null);
    }

    private void showBlockStats() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){return window.__nubrowseCosmeticCount||0;})()",
                value -> {
                    String cosmetic = value == null ? "0" : value.replace("\"", "");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Dev blocker stats")
                            .setMessage("This page network blocked: " + pageBlockedRequests.get() +
                                    "\nThis page cosmetic hidden: " + cosmetic +
                                    "\nThis page popup/redirect blocked: " + pageBlockedPopups.get() +
                                    "\nSession network blocked: " + blockedRequests.get() +
                                    "\nSession popup/redirect blocked: " + blockedPopups.get() +
                                    "\nHost rules loaded: " + (adBlocker == null ? 0 : adBlocker.ruleCount()) +
                                    "\n\nPage: " + currentPageUrl)
                            .setPositiveButton("OK", null)
                            .show();
                });
    }

    private void updateZoomLabel() {
        if (zoomLabel != null) zoomLabel.setText(zoomPercent + "%");
    }

    private void changeZoom(int delta) {
        int target = Math.max(60, Math.min(180, zoomPercent + delta));
        if (target == zoomPercent || webView == null) return;
        float factor = target / (float) zoomPercent;
        webView.zoomBy(factor);
        zoomPercent = target;
        if (zoomPercent <= 100) webView.postDelayed(this::injectFitToScreen, 80);
        updateZoomLabel();
        saveSettings();
    }

    private void resetZoom() {
        if (webView == null || zoomPercent == 100) return;
        float factor = 100f / zoomPercent;
        webView.zoomBy(factor);
        zoomPercent = 100;
        webView.postDelayed(this::injectFitToScreen, 80);
        updateZoomLabel();
        saveSettings();
    }

    private void submitAddress() {
        navigate(addressBar.getText().toString());
        hideSystemKeyboard();
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
        hideSystemKeyboard();
        webView.loadUrl(HOME_URL);
        webView.requestFocus();
    }

    private void showSystemKeyboard() {
        if (addressBar == null) return;
        addressBar.requestFocus();
        addressBar.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideSystemKeyboard() {
        View focused = getCurrentFocus();
        if (focused == null) focused = addressBar;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && focused != null) imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private void installSafeAreaInsets() {
        if (root == null || pageContainer == null) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            safeInsetLeft = Math.max(0, insets.getSystemWindowInsetLeft());
            safeInsetRight = Math.max(0, insets.getSystemWindowInsetRight());
            pageContainer.setPadding(safeInsetLeft, 0, safeInsetRight, 0);
            updateDevRemoteSafeArea();
            return insets;
        });
        root.requestApplyInsets();
    }

    private void updateDevRemoteSafeArea() {
        if (devRemoteFab != null) {
            ViewGroup.LayoutParams raw = devRemoteFab.getLayoutParams();
            if (raw instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) raw;
                p.setMargins(dp(8) + safeInsetLeft, dp(8), dp(14) + safeInsetRight, dp(14));
                devRemoteFab.setLayoutParams(p);
            }
        }
    }

    private void createDevRemoteFab() {
        if (!devModeEnabled || root == null) return;
        if (devRemoteFab != null) {
            devRemoteFab.setVisibility(View.VISIBLE);
            devRemoteFab.bringToFront();
            return;
        }
        devRemoteFab = button("⌾");
        devRemoteFab.setTextSize(18);
        devRemoteFab.setContentDescription("Open Dev Remote");
        devRemoteFab.setOnClickListener(v -> showTestRemote());
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.BOTTOM);
        p.setMargins(dp(8) + safeInsetLeft, dp(8), dp(14) + safeInsetRight, dp(14));
        root.addView(devRemoteFab, p);
        devRemoteFab.bringToFront();
    }

    private void updateDevRemoteFab() {
        if (!devModeEnabled) {
            if (devRemoteFab != null && root != null) {
                root.removeView(devRemoteFab);
                devRemoteFab = null;
            }
            return;
        }
        if (remoteOverlay == null) createDevRemoteFab();
        else if (devRemoteFab != null) devRemoteFab.setVisibility(View.GONE);
    }

    private void showTestRemote() {
        if (!devModeEnabled) {
            Toast.makeText(this, "Dev Mode is OFF", Toast.LENGTH_SHORT).show();
            return;
        }
        if (remoteOverlay != null) return;
        lastContentFocus = getCurrentFocus();
        if (lastContentFocus == null) lastContentFocus = webView;
        if (devRemoteFab != null) devRemoteFab.setVisibility(View.GONE);

        remoteOverlay = new FrameLayout(this);
        remoteOverlay.setFocusable(false);
        remoteOverlay.setFocusableInTouchMode(false);
        root.addView(remoteOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View shield = new View(this);
        shield.setBackgroundColor(Color.TRANSPARENT);
        shield.setClickable(true);
        shield.setFocusable(false);
        shield.setOnTouchListener((v, event) -> true);
        remoteOverlay.addView(shield, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(9), dp(8), dp(9), dp(9));
        panel.setBackgroundResource(R.drawable.remote_bg);
        panel.setAlpha(0.84f);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(210), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        panelParams.setMargins(dp(10) + safeInsetLeft, dp(10), dp(16) + safeInsetRight, dp(10));
        remoteOverlay.addView(panel, panelParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("REMOTE");
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(11);
        Button close = button("×");
        close.setFocusable(false);
        close.setTextSize(17);
        close.setOnClickListener(v -> hideTestRemote());
        header.addView(title, new LinearLayout.LayoutParams(0, dp(28), 1f));
        header.addView(close, new LinearLayout.LayoutParams(dp(34), dp(28)));
        panel.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        panel.addView(remoteRow("", "▲", ""));
        panel.addView(remoteRow("◀", "OK", "▶"));
        panel.addView(remoteRow("", "▼", ""));
        panel.addView(remoteRow("BACK", "HOME", "MENU"));

        TextView hint = new TextView(this);
        hint.setText("D-pad + OK");
        hint.setTextColor(Color.LTGRAY);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(9);
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        if (lastContentFocus != null) lastContentFocus.requestFocus();
        remoteOverlay.bringToFront();
    }

    private LinearLayout remoteRow(String left, String centre, String right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(2), 0, dp(2));
        addRemoteCell(row, left);
        addRemoteCell(row, centre);
        addRemoteCell(row, right);
        return row;
    }

    private void addRemoteCell(LinearLayout row, String label) {
        if (label.isEmpty()) {
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(0, dp(40), 1f));
            return;
        }
        Button b = button(label);
        b.setFocusable(false);
        b.setFocusableInTouchMode(false);
        b.setTextSize(label.length() > 3 ? 9 : 14);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setOnClickListener(v -> handleRemoteButton(label));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(40), 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
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
        updateDevRemoteFab();
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
                        hideSystemKeyboard();
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
        String blocker = "Ad blocker: " + (adBlockEnabled ? "ON" : "OFF") +
                "  •  network " + blockedRequests.get() +
                "  •  rules " + (adBlocker == null ? 0 : adBlocker.ruleCount());
        String popup = "Popup blocker: " + (popupBlockEnabled ? "ON" : "OFF") +
                "  •  blocked " + blockedPopups.get();
        String cookies = "Third-party cookies: " + (thirdPartyCookiesEnabled ? "ON" : "OFF");
        String dev = "Dev Mode: " + (devModeEnabled ? "ON" : "OFF");
        String[] items = {
                "Bookmarks",
                "History",
                "Downloads",
                dev,
                blocker,
                popup,
                cookies,
                "User agent: " + uaMode,
                "Dev blocker stats",
                "Clear browsing data",
                "About NuBrowse M6"
        };
        new AlertDialog.Builder(this)
                .setTitle("NuBrowse M6")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showSavedList(KEY_BOOKMARKS, "Bookmarks");
                    else if (which == 1) showSavedList(KEY_HISTORY, "History");
                    else if (which == 2) openDownloads();
                    else if (which == 3) {
                        devModeEnabled = !devModeEnabled;
                        if (!devModeEnabled) hideTestRemote();
                        saveSettings();
                        updateDevRemoteFab();
                        Toast.makeText(this, "Dev Mode " + (devModeEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
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
                    } else if (which == 7) {
                        showUserAgentPicker();
                    } else if (which == 8) {
                        if (devModeEnabled) showBlockStats();
                        else Toast.makeText(this, "Enable Dev Mode first", Toast.LENGTH_SHORT).show();
                    } else if (which == 9) {
                        clearBrowserData();
                    } else {
                        showAbout();
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
        blockedPopups.set(0);
        pageBlockedRequests.set(0);
        pageBlockedPopups.set(0);
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
                .setTitle("NuBrowse M6")
                .setMessage("TV Test Candidate\n\nM6: modern compact UI, system keyboard, true Fit mode at 100%, safe-area handling, floating Dev Remote and M5 blocker retained.")
                .setPositiveButton("OK", null)
                .show();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setFocusable(true);
        button.setFocusableInTouchMode(false);
        button.setBackgroundResource(R.drawable.button_bg);
        return button;
    }

    private LinearLayout.LayoutParams fixed(int width) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, dp(40));
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void browserBack() {
        if (customView != null) {
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
        else if (devRemoteFab != null && devModeEnabled) devRemoteFab.bringToFront();
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
