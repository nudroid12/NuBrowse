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
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
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
            "chartbeat.com",
            "hotjar.com",
            "clarity.ms",
            "branch.io",
            "appsflyer.com"
    )));

    private AdBlocker() {}

    static boolean shouldBlock(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) return false;
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            for (String blocked : BLOCKED_HOSTS) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }
}
