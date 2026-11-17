package com.tvbrowser.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppUpdater {
    static final String APK_NAME = "tvbrowser-update.apk";
    static final String MIME = "application/vnd.android.package-archive";
    private static final Pattern ASSET = Pattern.compile("TVBrowser-([1-9][0-9]{0,8})\\.apk");
    private static final int MAX_APK_BYTES = 100 * 1024 * 1024;
    private final Activity activity;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AlertDialog progress;
    private volatile boolean closed;
    private boolean running;

    AppUpdater(Activity activity) {
        this.activity = activity;
    }

    String versionName() {
        try {
            return installed(0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    void check() {
        String repo = activity.getString(R.string.update_repo);
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            message("Update source unavailable. Use an APK built by the GitHub workflow.");
            return;
        }
        run("Checking for updates…", () -> findUpdate(repo), update -> {
            if (update == null) {
                message("No newer TVBrowser release found.");
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("TVBrowser update available")
                    .setMessage(update.tag + " is available. Download and install it?")
                    .setPositiveButton("Download", (dialog, which) -> download(update))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    private Update findUpdate(String repo) throws Exception {
        HttpURLConnection connection = open("https://api.github.com/repos/" + repo + "/releases/latest");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int status = connection.getResponseCode();
            if (status == 404) return null;
            requireOk(status);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                copy(input, body, 1024 * 1024);
            }
            JSONObject release = new JSONObject(body.toString(StandardCharsets.UTF_8.name()));
            String tag = release.getString("tag_name");
            JSONArray assets = release.getJSONArray("assets");
            long highest = code(installed(0));
            Update result = null;
            boolean foundApk = false;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                Matcher matcher = ASSET.matcher(name);
                if (!matcher.matches()) continue;
                foundApk = true;
                long version = Long.parseLong(matcher.group(1));
                String url = asset.getString("browser_download_url");
                String prefix = "https://github.com/" + repo + "/releases/download/";
                if (version > highest && url.startsWith(prefix) && url.endsWith("/" + name)) {
                    highest = version;
                    result = new Update(tag, url, version);
                }
            }
            if (!foundApk) throw new IOException("The latest release has no TVBrowser APK.");
            return result;
        } finally {
            connection.disconnect();
        }
    }

    private void download(Update update) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow TVBrowser to install updates")
                    .setMessage("Enable installation from TVBrowser, then return and check again.")
                    .setPositiveButton("Open settings", (dialog, which) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            message("Open your device settings to allow installation from TVBrowser.");
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        run("Downloading update…", () -> {
            File pending = File.createTempFile("tvbrowser-", ".apk", activity.getCacheDir());
            try {
                HttpURLConnection connection = open(update.url);
                try {
                    requireOk(connection.getResponseCode());
                    if (!"https".equals(connection.getURL().getProtocol())) {
                        throw new IOException("The download did not use HTTPS.");
                    }
                    try (InputStream input = connection.getInputStream();
                         FileOutputStream output = new FileOutputStream(pending)) {
                        copy(input, output, MAX_APK_BYTES);
                    }
                } finally {
                    connection.disconnect();
                }
                verify(pending, update.version);
                File apk = new File(activity.getCacheDir(), APK_NAME);
                if (!pending.renameTo(apk)) throw new IOException("Could not save the update.");
                return apk;
            } finally {
                pending.delete();
            }
        }, apk -> launchInstaller());
    }

    @SuppressWarnings("deprecation")
    private void verify(File apk, long expected) throws Exception {
        PackageInfo current = installed(PackageManager.GET_SIGNATURES);
        PackageInfo incoming = activity.getPackageManager().getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (incoming == null || !activity.getPackageName().equals(incoming.packageName)
                || code(incoming) != expected || expected <= code(current)) {
            throw new IOException("The update APK does not match this app or version.");
        }
        if (incoming.signatures == null || current.signatures == null ||
                !Arrays.equals(current.signatures, incoming.signatures)) {
            throw new IOException("This APK uses a different signing key. Use the official release build.");
        }
    }

    private void launchInstaller() {
        Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/" + APK_NAME);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            message("Android could not open the package installer.");
        }
    }

    private PackageInfo installed(int flags) throws PackageManager.NameNotFoundException {
        return activity.getPackageManager().getPackageInfo(activity.getPackageName(), flags);
    }

    @SuppressWarnings("deprecation")
    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", "TVBrowser-Updater");
        return connection;
    }

    private static void requireOk(int status) throws IOException {
        if (status != 200) throw new IOException("GitHub returned HTTP " + status + ".");
    }

    private void copy(InputStream in, OutputStream out, int limit) throws IOException {
        byte[] buffer = new byte[16384];
        int total = 0;
        int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (closed || total > limit) throw new IOException("Update transfer stopped.");
            out.write(buffer, 0, count);
        }
    }

    private <T> void run(String label, Callable<T> task, ResultHandler<T> success) {
        if (running || closed) return;
        running = true;
        progress = new AlertDialog.Builder(activity).setMessage(label).setCancelable(false).show();
        worker.execute(() -> {
            try {
                T result = task.call();
                activity.runOnUiThread(() -> finishRequest(() -> success.accept(result)));
            } catch (Exception e) {
                activity.runOnUiThread(() -> finishRequest(() ->
                        message(e.getMessage() == null ? "Update failed." : e.getMessage())));
            }
        });
    }

    private void finishRequest(Runnable callback) {
        if (closed || activity.isFinishing()) return;
        progress.dismiss();
        progress = null;
        running = false;
        callback.run();
    }

    private void message(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    void close() {
        closed = true;
        worker.shutdownNow();
        if (progress != null) progress.dismiss();
    }

    private interface ResultHandler<T> {
        void accept(T value);
    }

    private static final class Update {
        final String tag;
        final String url;
        final long version;

        Update(String tag, String url, long version) {
            this.tag = tag;
            this.url = url;
            this.version = version;
        }
    }
}
