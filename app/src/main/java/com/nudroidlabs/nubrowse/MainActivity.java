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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
import android.widget.ImageButton;
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
    private static final String UA_TV = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String UA_DESKTOP = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String UA_MOBILE = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final String PREFS = "nubrowse_m2";
    private static final String KEY_BOOKMARKS = "bookmarks";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ADBLOCK = "adblock";
    private static final String KEY_POPUP_BLOCK = "popup_block";
    private static final String KEY_THIRD_PARTY_COOKIES = "third_party_cookies";
    private static final String KEY_UA = "user_agent";
    private static final String KEY_ZOOM = "zoom_percent";
    private static final int MAX_HISTORY = 50;
    private static final int REQUEST_WRITE_STORAGE = 130;
    private static final long OK_DOUBLE_TAP_MS = 330L;

    private WebView webView;
    private EditText addressBar;
    private ProgressBar progressBar;
    private FrameLayout root;
    private FrameLayout fullscreenContainer;
    private FrameLayout browserFrame;
    private Button zoomLabel;
    private CursorIndicatorView cursorIndicator;
    private AdBlocker adBlocker;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SharedPreferences prefs;

    private boolean adBlockEnabled = true;
    private boolean popupBlockEnabled = true;
    private boolean thirdPartyCookiesEnabled = true;
    private int zoomPercent = 100;
    private String uaMode = "TV";
    private boolean initialZoomApplied = false;
    private LinearLayout pageContainer;
    private int safeInsetLeft = 0;
    private int safeInsetRight = 0;
    private boolean cursorMode = false;
    private float cursorX = -1f;
    private float cursorY = -1f;
    private long lastOkTapAt = 0L;
    private boolean pendingSingleOk = false;
    private final Handler inputHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingSingleOkRunnable = () -> {
        if (!pendingSingleOk) return;
        pendingSingleOk = false;
        lastOkTapAt = 0L;
        deliverSingleOk();
    };
    private final Runnable hideCursorRunnable = () -> {
        if (cursorMode && cursorIndicator != null) cursorIndicator.setVisibility(View.INVISIBLE);
    };
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

        ImageButton back = iconButton(R.drawable.ic_back, "Back");
        ImageButton forward = iconButton(R.drawable.ic_forward, "Forward");
        ImageButton home = iconButton(R.drawable.ic_home, "Home");
        ImageButton refresh = iconButton(R.drawable.ic_refresh, "Refresh");
        ImageButton bookmark = iconButton(R.drawable.ic_bookmark, "Bookmark");
        ImageButton menu = iconButton(R.drawable.ic_more, "Menu");

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

        ImageButton go = iconButton(R.drawable.ic_go, "Go");
        ImageButton zoomOut = iconButton(R.drawable.ic_zoom_out, "Zoom out");
        zoomLabel = button("100%");
        zoomLabel.setTextSize(12);
        ImageButton zoomIn = iconButton(R.drawable.ic_zoom_in, "Zoom in");

        toolbar.addView(back, fixed(dp(40)));
        toolbar.addView(forward, fixed(dp(40)));
        toolbar.addView(home, fixed(dp(40)));
        toolbar.addView(refresh, fixed(dp(40)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        addressParams.setMargins(dp(5), 0, dp(5), 0);
        toolbar.addView(addressBar, addressParams);
        toolbar.addView(go, fixed(dp(40)));
        toolbar.addView(bookmark, fixed(dp(40)));
        toolbar.addView(zoomOut, fixed(dp(36)));
        toolbar.addView(zoomLabel, fixed(dp(50)));
        toolbar.addView(zoomIn, fixed(dp(36)));
        toolbar.addView(menu, fixed(dp(40)));
        updateZoomLabel();

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        pageContainer.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        NuBrowseApp.markPhase(this, "creating_webview");
        browserFrame = new FrameLayout(this);
        pageContainer.addView(browserFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        webView = new WebView(this);
        webView.setId(View.generateViewId());
        addressBar.setId(View.generateViewId());
        webView.setBackgroundColor(Color.BLACK);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        browserFrame.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int webFocusId = webView.getId();
        back.setNextFocusDownId(webFocusId);
        forward.setNextFocusDownId(webFocusId);
        home.setNextFocusDownId(webFocusId);
        refresh.setNextFocusDownId(webFocusId);
        addressBar.setNextFocusDownId(webFocusId);
        go.setNextFocusDownId(webFocusId);
        bookmark.setNextFocusDownId(webFocusId);
        zoomOut.setNextFocusDownId(webFocusId);
        zoomLabel.setNextFocusDownId(webFocusId);
        zoomIn.setNextFocusDownId(webFocusId);
        menu.setNextFocusDownId(webFocusId);
        webView.setNextFocusUpId(addressBar.getId());

        cursorIndicator = new CursorIndicatorView(this);
        cursorIndicator.setVisibility(View.GONE);
        cursorIndicator.setClickable(false);
        cursorIndicator.setFocusable(false);
        FrameLayout.LayoutParams cursorParams = new FrameLayout.LayoutParams(dp(28), dp(34));
        browserFrame.addView(cursorIndicator, cursorParams);

        configureWebView();
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
        settings.setLoadWithOverviewMode(!"Mobile".equals(uaMode));
        settings.setUseWideViewPort(!"Mobile".equals(uaMode));
        settings.setTextZoom(100);
        webView.setInitialScale(0);
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
                injectTvPageOptimisation();
                injectCosmeticAdCleanup();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && !url.isEmpty()) currentPageUrl = url;
                addressBar.setText(url);
                recordHistory(url, view.getTitle());
                injectTvPageOptimisation();
                view.postDelayed(MainActivity.this::injectTvPageOptimisation, 500);
                injectCosmeticAdCleanup();
                if (!initialZoomApplied) {
                    initialZoomApplied = true;
                    if (zoomPercent != 100) {
                        webView.post(() -> webView.zoomBy(zoomPercent / 100f));
                    }
                }
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

    private void injectTvPageOptimisation() {
        if (webView == null || "Mobile".equals(uaMode)) return;
        String js = "(function(){" +
                "try{" +
                "var d=document.documentElement,b=document.body;if(!d||!b)return;" +
                "var id='nubrowse-tv-layout';var st=document.getElementById(id);" +
                "if(!st){st=document.createElement('style');st.id=id;(document.head||d).appendChild(st);}" +
                "st.textContent='img,video,svg,canvas{max-width:100%!important;height:auto;}iframe{max-width:100%!important;}';" +
                "d.style.removeProperty('zoom');b.style.removeProperty('zoom');" +
                "d.style.removeProperty('transform');b.style.removeProperty('transform');" +
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
        if (zoomPercent <= 100) webView.postDelayed(this::injectTvPageOptimisation, 80);
        updateZoomLabel();
        saveSettings();
    }

    private void resetZoom() {
        if (webView == null || zoomPercent == 100) return;
        float factor = 100f / zoomPercent;
        webView.zoomBy(factor);
        zoomPercent = 100;
        webView.postDelayed(this::injectTvPageOptimisation, 80);
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
            return insets;
        });
        root.requestApplyInsets();
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
        String[] items = {
                "Bookmarks",
                "History",
                "Downloads",
                blocker,
                popup,
                cookies,
                "User agent: " + uaMode,
                "Blocker stats",
                "Clear browsing data",
                "About NuBrowse M6 R3"
        };
        new AlertDialog.Builder(this)
                .setTitle("NuBrowse M6 R3")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) showSavedList(KEY_BOOKMARKS, "Bookmarks");
                    else if (which == 1) showSavedList(KEY_HISTORY, "History");
                    else if (which == 2) openDownloads();
                    else if (which == 3) {
                        adBlockEnabled = !adBlockEnabled;
                        saveSettings();
                        Toast.makeText(this, "Ad blocker " + (adBlockEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 4) {
                        popupBlockEnabled = !popupBlockEnabled;
                        saveSettings();
                        Toast.makeText(this, "Popup blocker " + (popupBlockEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                    } else if (which == 5) {
                        thirdPartyCookiesEnabled = !thirdPartyCookiesEnabled;
                        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, thirdPartyCookiesEnabled);
                        saveSettings();
                        Toast.makeText(this, "Third-party cookies " + (thirdPartyCookiesEnabled ? "ON" : "OFF"), Toast.LENGTH_SHORT).show();
                        webView.reload();
                    } else if (which == 6) {
                        showUserAgentPicker();
                    } else if (which == 7) {
                        showBlockStats();
                    } else if (which == 8) {
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
        boolean desktopLayout = !"Mobile".equals(uaMode);
        settings.setUseWideViewPort(desktopLayout);
        settings.setLoadWithOverviewMode(desktopLayout);
        webView.setInitialScale(0);
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
                .setTitle("NuBrowse M6 R3")
                .setMessage("TV Test Candidate\n\nM6 R3 removes the development floating remote, removes forced 1280 CSS viewport injection, restores natural WebView fitting, adds physical TV remote page scrolling, keeps double-tap OK cursor mode, exit confirmation and the M5 blocker.")
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

    private ImageButton iconButton(int drawableRes, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(drawableRes);
        button.setColorFilter(Color.rgb(235, 238, 242));
        button.setContentDescription(description);
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
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
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            showExitConfirmation();
        }
    }

    private void showExitConfirmation() {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Exit NuBrowse?")
                .setMessage("Do you want to close the browser?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Exit", (d, which) -> finish())
                .create();
        dialog.setOnShowListener(d -> {
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.requestFocus();
        });
        dialog.show();
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
    }

    @Override
    public void onBackPressed() {
        browserBack();
    }

    private boolean isArrowKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private boolean isRemoteOkKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER) return true;
        return event.getKeyCode() == KeyEvent.KEYCODE_ENTER &&
                (event.getSource() & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    private void toggleCursorMode() {
        cursorMode = !cursorMode;
        pendingSingleOk = false;
        inputHandler.removeCallbacks(pendingSingleOkRunnable);
        lastOkTapAt = 0L;
        if (cursorMode) {
            initialiseCursorIfNeeded();
            showCursor();
            webView.requestFocus();
            Toast.makeText(this, "CURSOR MODE", Toast.LENGTH_SHORT).show();
        } else {
            if (cursorIndicator != null) {
                cursorIndicator.removeCallbacks(hideCursorRunnable);
                cursorIndicator.setVisibility(View.GONE);
            }
            webView.requestFocus();
            Toast.makeText(this, "D-PAD MODE", Toast.LENGTH_SHORT).show();
        }
    }

    private void initialiseCursorIfNeeded() {
        if (browserFrame == null) return;
        int w = browserFrame.getWidth();
        int h = browserFrame.getHeight();
        if (w <= 0 || h <= 0) return;
        if (cursorX < 0 || cursorY < 0) {
            cursorX = w * 0.5f;
            cursorY = h * 0.5f;
        }
        clampCursor();
        positionCursorIndicator();
    }

    private void moveCursor(int keyCode, int repeatCount) {
        if (browserFrame == null || webView == null) return;
        initialiseCursorIfNeeded();
        float step = dp(18 + Math.min(18, repeatCount * 2));
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) cursorX -= step;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) cursorX += step;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) cursorY -= step;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) cursorY += step;
        clampCursor();
        if (cursorY >= browserFrame.getHeight() - dp(42)) {
            webView.scrollBy(0, dp(72));
        } else if (cursorY <= dp(42)) {
            webView.scrollBy(0, -dp(72));
        }
        positionCursorIndicator();
        sendCursorHover();
        showCursor();
    }

    private void clampCursor() {
        if (browserFrame == null) return;
        float maxX = Math.max(dp(4), browserFrame.getWidth() - dp(8));
        float maxY = Math.max(dp(4), browserFrame.getHeight() - dp(8));
        cursorX = Math.max(dp(2), Math.min(maxX, cursorX));
        cursorY = Math.max(dp(2), Math.min(maxY, cursorY));
    }

    private void positionCursorIndicator() {
        if (cursorIndicator == null) return;
        cursorIndicator.setX(cursorX - dp(3));
        cursorIndicator.setY(cursorY - dp(3));
        cursorIndicator.bringToFront();
    }

    private void showCursor() {
        if (!cursorMode || cursorIndicator == null) return;
        cursorIndicator.setVisibility(View.VISIBLE);
        cursorIndicator.removeCallbacks(hideCursorRunnable);
        cursorIndicator.postDelayed(hideCursorRunnable, 3000L);
    }

    private void sendCursorHover() {
        if (webView == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent hover = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0);
        hover.setSource(InputDevice.SOURCE_MOUSE);
        webView.dispatchGenericMotionEvent(hover);
        hover.recycle();
    }

    private void clickCursor() {
        if (webView == null) return;
        initialiseCursorIfNeeded();
        showCursor();
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
        down.setSource(InputDevice.SOURCE_MOUSE);
        webView.dispatchTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(now, now + 35L, MotionEvent.ACTION_UP, cursorX, cursorY, 0);
        up.setSource(InputDevice.SOURCE_MOUSE);
        webView.dispatchTouchEvent(up);
        up.recycle();
    }

    private void deliverSingleOk() {
        if (cursorMode) {
            clickCursor();
            return;
        }
        View target = getCurrentFocus();
        if (target == null || target == root) target = webView;
        if (target == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0);
        KeyEvent up = new KeyEvent(now, now + 20L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0);
        target.dispatchKeyEvent(down);
        target.dispatchKeyEvent(up);
    }

    private boolean handleOkModeToggle(KeyEvent event) {
        if (!isRemoteOkKey(event)) return false;

        if (event.getAction() == KeyEvent.ACTION_UP) {
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
            return true;
        }

        long now = event.getEventTime();
        if (pendingSingleOk && now - lastOkTapAt > 0 && now - lastOkTapAt <= OK_DOUBLE_TAP_MS) {
            pendingSingleOk = false;
            inputHandler.removeCallbacks(pendingSingleOkRunnable);
            lastOkTapAt = 0L;
            toggleCursorMode();
            return true;
        }

        pendingSingleOk = true;
        lastOkTapAt = now;
        inputHandler.removeCallbacks(pendingSingleOkRunnable);
        inputHandler.postDelayed(pendingSingleOkRunnable, OK_DOUBLE_TAP_MS);
        return true;
    }

    private boolean handleTvPageScroll(KeyEvent event) {
        if (cursorMode || webView == null) return false;
        int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_UP) return false;

        View focused = getCurrentFocus();
        if (focused != webView) return false;

        if (event.getAction() == KeyEvent.ACTION_UP) return true;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            webView.pageDown(false);
        } else {
            webView.pageUp(false);
        }
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleOkModeToggle(event)) return true;

        int keyCode = event.getKeyCode();
        if (cursorMode && isArrowKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) moveCursor(keyCode, event.getRepeatCount());
            return true;
        }

        if (handleTvPageScroll(event)) return true;

        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_MENU) {
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
        pendingSingleOk = false;
        inputHandler.removeCallbacks(pendingSingleOkRunnable);
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
    private static final class CursorIndicatorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path pointer = new Path();

        CursorIndicatorView(Context context) {
            super(context);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.rgb(20, 22, 24));
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(3f);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            pointer.reset();
            pointer.moveTo(w * 0.12f, h * 0.08f);
            pointer.lineTo(w * 0.82f, h * 0.58f);
            pointer.lineTo(w * 0.53f, h * 0.63f);
            pointer.lineTo(w * 0.69f, h * 0.93f);
            pointer.lineTo(w * 0.53f, h * 0.99f);
            pointer.lineTo(w * 0.38f, h * 0.68f);
            pointer.lineTo(w * 0.17f, h * 0.88f);
            pointer.close();
            canvas.drawPath(pointer, fill);
            canvas.drawPath(pointer, stroke);
        }
    }

}
