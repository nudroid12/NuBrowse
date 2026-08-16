package com.nudroidlabs.nubrowse;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class AdBlocker {
    private static final String[] BUILTIN_HOSTS = {
            "doubleclick.net", "googleadservices.com", "googlesyndication.com",
            "googletagservices.com", "google-analytics.com", "googletagmanager.com",
            "adservice.google.com", "amazon-adsystem.com", "adsrvr.org", "adnxs.com",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com",
            "scorecardresearch.com", "quantserve.com", "zedo.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "casalemedia.com", "moatads.com",
            "smartadserver.com", "serving-sys.com", "yieldmo.com", "sharethrough.com",
            "lijit.com", "rlcdn.com", "demdex.net", "bluekai.com", "mathtag.com",
            "adsafeprotected.com", "innovid.com", "branch.io", "appsflyer.com",
            "chartbeat.com", "hotjar.com", "clarity.ms", "adform.net", "adform.com",
            "media.net", "mgid.com", "revcontent.com", "exoclick.com", "exosrv.com",
            "popads.net", "popcash.net", "propellerads.com", "onclicka.com",
            "trafficjunky.net", "juicyads.com", "hilltopads.net", "adsterra.com",
            "adcash.com", "clickadu.com", "onclickalgo.com", "onclickmega.com",
            "onclickperformance.com", "popunder.net", "ad-maven.com", "ad-maven.com",
            "trafficstars.com", "trafficstars.net", "admaven.com", "monetag.com"
    };

    private static final String[] THIRD_PARTY_PATH_MARKERS = {
            "/ads/", "/adserver/", "/adserve/", "/advert/", "/advertising/",
            "/bannerads/", "/banner-ad/", "/popunder/", "/popupads/", "/sponsor/",
            "?adunit=", "&adunit=", "?ad_slot=", "&ad_slot=", "/prebid/", "/vast/"
    };

    private static final String[] THIRD_PARTY_HOST_MARKERS = {
            "adserver", "adservice", "adnetwork", "adnetwork", "adsystem",
            "adclick", "adtrack", "ad-delivery", "popunder", "popup", "tracking"
    };

    private final Set<String> blockedHosts = ConcurrentHashMap.newKeySet();
    private final AtomicInteger loadedRuleCount = new AtomicInteger(0);

    AdBlocker(Context context) {
        blockedHosts.addAll(Arrays.asList(BUILTIN_HOSTS));
        loadedRuleCount.set(blockedHosts.size());
        Context app = context.getApplicationContext();
        Thread loader = new Thread(() -> loadAssetHosts(app), "NuBrowse-Blocklist");
        loader.setDaemon(true);
        loader.start();
    }

    int ruleCount() {
        return loadedRuleCount.get();
    }

    boolean shouldBlock(String requestUrl, String pageUrl) {
        if (requestUrl == null || requestUrl.isEmpty()) return false;
        try {
            Uri request = Uri.parse(requestUrl);
            String host = normaliseHost(request.getHost());
            if (host == null) return false;

            if (matchesBlockedHost(host)) return true;

            String pageHost = null;
            if (pageUrl != null && !pageUrl.isEmpty()) {
                pageHost = normaliseHost(Uri.parse(pageUrl).getHost());
            }

            if (pageHost != null && !sameSite(host, pageHost)) {
                String lowerUrl = requestUrl.toLowerCase(Locale.US);
                String lowerHost = host.toLowerCase(Locale.US);

                for (String marker : THIRD_PARTY_PATH_MARKERS) {
                    if (lowerUrl.contains(marker)) return true;
                }
                for (String marker : THIRD_PARTY_HOST_MARKERS) {
                    if (lowerHost.contains(marker)) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    boolean isSuspiciousNavigation(String url, String pageUrl) {
        if (shouldBlock(url, pageUrl)) return true;
        try {
            String host = normaliseHost(Uri.parse(url).getHost());
            String pageHost = pageUrl == null ? null : normaliseHost(Uri.parse(pageUrl).getHost());
            if (host == null || pageHost == null || sameSite(host, pageHost)) return false;

            String lower = host.toLowerCase(Locale.US);
            return lower.contains("popunder") || lower.contains("onclick") ||
                    lower.contains("adclick") || lower.contains("adserver");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean matchesBlockedHost(String host) {
        String candidate = host;
        while (candidate != null && !candidate.isEmpty()) {
            if (blockedHosts.contains(candidate)) return true;
            int dot = candidate.indexOf('.');
            if (dot < 0 || dot + 1 >= candidate.length()) break;
            candidate = candidate.substring(dot + 1);
        }
        return false;
    }

    private void loadAssetHosts(Context context) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(context.getAssets().open("blocked_hosts.txt")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String host = normaliseHost(line);
                if (host == null || host.startsWith("#") || host.equals("localhost")) continue;
                blockedHosts.add(host);
            }
            loadedRuleCount.set(blockedHosts.size());
        } catch (Exception ignored) {
            loadedRuleCount.set(blockedHosts.size());
        }
    }

    private static String normaliseHost(String host) {
        if (host == null) return null;
        String value = host.trim().toLowerCase(Locale.US);
        if (value.isEmpty()) return null;
        if (value.startsWith("0.0.0.0 ")) value = value.substring(8).trim();
        if (value.startsWith("127.0.0.1 ")) value = value.substring(10).trim();
        int hash = value.indexOf('#');
        if (hash >= 0) value = value.substring(0, hash).trim();
        int space = value.indexOf(' ');
        if (space >= 0) value = value.substring(0, space).trim();
        if (value.startsWith("www.")) value = value.substring(4);
        if (value.isEmpty() || value.contains("/") || value.contains(":")) return null;
        return value;
    }

    private static boolean sameSite(String first, String second) {
        if (first.equals(second)) return true;
        return first.endsWith("." + second) || second.endsWith("." + first);
    }
}
