package com.nudroidlabs.tvadapter;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

/** Display resources scoped to a development preview activity. */
public final class TvPreviewDisplay {
    private final int widthPx;
    private final int heightPx;
    private final Resources resources;

    public static TvPreviewDisplay fromProfile(Context host, String requested) {
        if (requested != null) {
            String[] fields = requested.split("[x@]", -1);
            if (fields.length == 3) {
                try {
                    int width = Integer.parseInt(fields[0]);
                    int height = Integer.parseInt(fields[1]);
                    int dpi = Integer.parseInt(fields[2]);
                    if (width >= height && height > 0 && width <= 8192
                            && dpi >= 72 && dpi <= 1000) {
                        return new TvPreviewDisplay(host, width, height, dpi);
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the standard development profile.
                }
            }
            Log.w("TvPreviewDisplay", "Invalid preview display profile. Use WIDTHxHEIGHT@DPI.");
        }
        return new TvPreviewDisplay(host, 1920, 1080, DisplayMetrics.DENSITY_XHIGH);
    }

    @SuppressWarnings("deprecation")
    private TvPreviewDisplay(Context host, int widthPx, int heightPx, int densityDpi) {
        this.widthPx = widthPx;
        this.heightPx = heightPx;
        float density = densityDpi / (float) DisplayMetrics.DENSITY_DEFAULT;

        Configuration config = new Configuration();
        config.fontScale = 1f;
        config.densityDpi = densityDpi;
        config.orientation = Configuration.ORIENTATION_LANDSCAPE;
        config.screenWidthDp = (int) (widthPx / density);
        config.screenHeightDp = (int) (heightPx / density);
        config.smallestScreenWidthDp = config.screenHeightDp;
        config.uiMode = Configuration.UI_MODE_TYPE_TELEVISION
                | (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.navigation = Configuration.NAVIGATION_DPAD;
        config.keyboard = Configuration.KEYBOARD_NOKEYS;
        config.screenLayout = screenLayout(config.screenWidthDp, config.screenHeightDp);

        Context configured = host.createConfigurationContext(config);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setTo(configured.getResources().getDisplayMetrics());
        metrics.widthPixels = widthPx;
        metrics.heightPixels = heightPx;
        metrics.densityDpi = densityDpi;
        metrics.density = density;
        metrics.scaledDensity = density;
        metrics.xdpi = densityDpi;
        metrics.ydpi = densityDpi;

        // Use isolated metrics. A configuration context alone can still report the host display's pixels.
        resources = new Resources(configured.getAssets(), metrics,
                configured.getResources().getConfiguration());
    }

    private static int screenLayout(int widthDp, int heightDp) {
        int size;
        if (widthDp >= 960 && heightDp >= 720) {
            size = Configuration.SCREENLAYOUT_SIZE_XLARGE;
        } else if (widthDp >= 640 && heightDp >= 480) {
            size = Configuration.SCREENLAYOUT_SIZE_LARGE;
        } else if (widthDp < 470) {
            size = Configuration.SCREENLAYOUT_SIZE_SMALL;
        } else {
            size = Configuration.SCREENLAYOUT_SIZE_NORMAL;
        }
        int length = widthDp * 3 >= heightDp * 5
                ? Configuration.SCREENLAYOUT_LONG_YES : Configuration.SCREENLAYOUT_LONG_NO;
        return size | length | Configuration.SCREENLAYOUT_ROUND_NO;
    }

    public Resources resources() {
        return resources;
    }

    int widthPx() {
        return widthPx;
    }

    int heightPx() {
        return heightPx;
    }
}
