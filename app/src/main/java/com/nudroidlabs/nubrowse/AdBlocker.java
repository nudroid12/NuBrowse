package com.nudroidlabs.nubrowse;

import android.net.Uri;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class AdBlocker {
    private static final Set<String> BLOCKED_HOSTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "doubleclick.net",
            "googleadservices.com",
            "googlesyndication.com",
            "googletagservices.com",
            "google-analytics.com",
            "googletagmanager.com",
            "adservice.google.com",
            "amazon-adsystem.com",
            "adsrvr.org",
            "adnxs.com",
            "criteo.com",
            "criteo.net",
            "taboola.com",
            "outbrain.com",
            "scorecardresearch.com",
            "quantserve.com",
            "zedo.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "casalemedia.com",
            "moatads.com",
            "smartadserver.com",
            "serving-sys.com",
            "yieldmo.com",
            "sharethrough.com",
            "lijit.com",
            "rlcdn.com",
            "demdex.net",
            "bluekai.com",
            "mathtag.com",
            "adsafeprotected.com",
            "innovid.com",
            "branch.io",
            "appsflyer.com",
            "chartbeat.com",
            "hotjar.com",
            "clarity.ms",
            "adform.net",
            "adform.com",
            "media.net",
            "mgid.com",
            "revcontent.com",
            "exoclick.com",
            "exosrv.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "onclicka.com",
            "trafficjunky.net",
            "juicyads.com",
            "hilltopads.net",
            "adsterra.com",
            "adcash.com"
    )));

    private static final String[] THIRD_PARTY_PATH_MARKERS = {
            "/ads/", "/adserver/", "/adserve/", "/advert/", "/advertising/",
            "/bannerads/", "/popunder/", "/popupads/", "?adunit=", "&adunit="
    };

    private AdBlocker() {}

    static boolean shouldBlock(String requestUrl, String pageUrl) {
        if (requestUrl == null || requestUrl.isEmpty()) return false;
        try {
            Uri request = Uri.parse(requestUrl);
            String host = normaliseHost(request.getHost());
            if (host == null) return false;

            for (String blocked : BLOCKED_HOSTS) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
            }

            String pageHost = null;
            if (pageUrl != null && !pageUrl.isEmpty()) {
                pageHost = normaliseHost(Uri.parse(pageUrl).getHost());
            }
            if (pageHost != null && !sameSite(host, pageHost)) {
                String lower = requestUrl.toLowerCase(Locale.US);
                for (String marker : THIRD_PARTY_PATH_MARKERS) {
                    if (lower.contains(marker)) return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static String normaliseHost(String host) {
        if (host == null || host.trim().isEmpty()) return null;
        String value = host.toLowerCase(Locale.US);
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    private static boolean sameSite(String first, String second) {
        if (first.equals(second)) return true;
        return first.endsWith("." + second) || second.endsWith("." + first);
    }
}
