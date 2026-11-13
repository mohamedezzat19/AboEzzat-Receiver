package com.aboezzat.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.Gravity;
import android.view.ViewGroup;
import androidx.core.content.FileProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdater {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String RELEASES_API = "https://api.github.com/repos/mohamedezzat19/AboEzzat-Receiver/releases/latest";
    private final Activity activity;

    public AppUpdater(Activity activity) { this.activity = activity; }

    public void checkForUpdate(boolean showResult) {
        IO.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "AboEzzat-Receiver-Updater");
                JSONObject release;
                try (InputStream in = c.getInputStream();
                     java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) != -1) out.write(b, 0, n);
                    release = new JSONObject(out.toString("UTF-8"));
                } finally {
                    c.disconnect();
                }

                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("versionCode\\s*:\\s*(\\d+)")
                        .matcher(release.optString("body", ""));
                long latest = m.find() ? Long.parseLong(m.group(1)) : 0;
                long installed = installedVersionCode();
                String apkUrl = null;
                JSONArray assets = release.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject a = assets.optJSONObject(i);
                        if (a != null && "app-release.apk".equals(a.optString("name"))) {
                            apkUrl = a.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                final String downloadUrl = apkUrl;
                if (latest <= installed || downloadUrl == null || downloadUrl.isEmpty()) {
                    if (showResult) MAIN.post(() -> Toast.makeText(activity,
                            "التطبيق محدث بالفعل، أو لم تُنشر نسخة موقّعة بعد.",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                MAIN.post(() -> new AlertDialog.Builder(activity)
                        .setTitle("تحديث جديد متاح")
                        .setMessage("يوجد إصدار أحدث رقم " + latest + ". هل تريد تنزيله وتثبيته؟")
                        .setNegativeButton("لاحقًا", null)
                        .setPositiveButton("تحديث", (d, w) -> download(downloadUrl, latest))
                        .show());
            } catch (Exception e) {
                if (showResult) MAIN.post(() -> Toast.makeText(activity,
                        "تعذر التحقق من التحديث. تحقق من الإنترنت وحاول مرة أخرى.",
                        Toast.LENGTH_LONG).show());
            }
        });
    }

    private long installedVersionCode() {
        try {
            PackageInfo p = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private void download(String url, long version) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(12), dp(24), dp(8));

        TextView status = new TextView(activity);
        status.setText("جارٍ الاتصال بخادم التحديث...");
        status.setTextSize(15);
        panel.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(12));
        progressParams.topMargin = dp(16);
        progressParams.bottomMargin = dp(12);
        panel.addView(progress, progressParams);

        TextView details = new TextView(activity);
        details.setGravity(Gravity.CENTER_HORIZONTAL);
        details.setText("في انتظار بدء التنزيل...");
        details.setTextSize(14);
        panel.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("تنزيل التحديث " + version)
                .setView(panel)
                .setCancelable(false)
                .create();
        progressDialog.show();

        IO.execute(() -> {
            File apk = new File(activity.getExternalCacheDir() != null ? activity.getExternalCacheDir() : activity.getCacheDir(), "aboezzat-receiver-update-" + version + ".apk");
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "AboEzzat-Receiver-Updater");
                if (c.getResponseCode() != HttpURLConnection.HTTP_OK)
                    throw new IllegalStateException("HTTP " + c.getResponseCode());

                final long total = c.getContentLength();
                MAIN.post(() -> {
                    progress.setIndeterminate(total <= 0);
                    status.setText("جارٍ تنزيل ملف التحديث...");
                    details.setText(total > 0 ? "0%  •  0 / " + formatBytes(total) + "  •  السرعة: تُحسب..." :
                            "تم بدء التنزيل  •  الحجم غير محدد من الخادم");
                });

                long downloaded = 0L;
                long sampleBytes = 0L;
                long sampleTime = System.currentTimeMillis();
                long lastUiTime = 0L;
                try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) != -1) {
                        out.write(b, 0, n);
                        downloaded += n;
                        long now = System.currentTimeMillis();
                        if (now - lastUiTime >= 300) {
                            final long currentBytes = downloaded;
                            final long elapsed = Math.max(1L, now - sampleTime);
                            final long speed = (downloaded - sampleBytes) * 1000L / elapsed;
                            final int percent = total > 0 ? (int) Math.min(1000L, downloaded * 1000L / total) : 0;
                            MAIN.post(() -> {
                                if (total > 0) progress.setProgress(percent);
                                details.setText((total > 0 ? (percent / 10) + "." + (percent % 10) + "%  •  " : "") +
                                        formatBytes(currentBytes) + (total > 0 ? " / " + formatBytes(total) : "") +
                                        "  •  السرعة: " + formatBytes(speed) + "/ث");
                            });
                            sampleBytes = downloaded;
                            sampleTime = now;
                            lastUiTime = now;
                        }
                    }
                    out.flush();
                } finally {
                    c.disconnect();
                }

                final long finalDownloaded = downloaded;
                MAIN.post(() -> {
                    progress.setIndeterminate(false);
                    progress.setProgress(1000);
                    status.setText("اكتمل التنزيل، جارٍ التحقق من ملف التحديث...");
                    details.setText(formatBytes(finalDownloaded) + "  •  اكتمل 100%");
                });

                PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(
                        apk.getAbsolutePath(), android.content.pm.PackageManager.GET_META_DATA);
                if (archive == null || !activity.getPackageName().equals(archive.packageName))
                    throw new IllegalStateException("Downloaded APK is not the Receiver package");
                long downloadedCode = Build.VERSION.SDK_INT >= 28
                        ? archive.getLongVersionCode() : archive.versionCode;
                if (downloadedCode <= installedVersionCode() || downloadedCode < version)
                    throw new IllegalStateException("Downloaded APK is not a newer expected build");
                MAIN.post(() -> {
                    progressDialog.dismiss();
                    install(apk);
                });
            } catch (Exception e) {
                if (apk.exists()) apk.delete();
                android.util.Log.e("AboEzzatUpdater", "Receiver update validation failed", e);
                MAIN.post(() -> {
                    if (progressDialog.isShowing()) progressDialog.dismiss();
                    Toast.makeText(activity,
                            "فشل تنزيل التحديث أو التحقق من ملفه. لم يتم تثبيت شيء.",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void install(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(activity)
                        .setTitle("السماح بالتثبيت")
                        .setMessage("فعّل السماح بتثبيت التطبيقات من هذا المصدر، ثم ارجع واضغط «التحقق من التحديث» مرة أخرى.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("فتح الإعدادات", (d, w) ->
                                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + activity.getPackageName()))))
                        .show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            android.util.Log.e("AboEzzatUpdater", "Could not launch Receiver package installer", e);
            Toast.makeText(activity, "تعذر فتح مثبت التحديث.", Toast.LENGTH_LONG).show();
        }
    }
}
