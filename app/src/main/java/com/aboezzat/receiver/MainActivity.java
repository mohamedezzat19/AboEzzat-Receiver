
package com.aboezzat.receiver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.CookieManager;
import android.view.Window;
import android.view.WindowManager;
import android.util.TypedValue;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;
import android.widget.EditText;
import org.json.JSONObject;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.net.Socket;
import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@UnstableApi
public class MainActivity extends Activity {

    private static final String TAG = "AboEzzatReceiver";
    private static final int PORT = 8081;
    private static final int DISCOVERY_PORT = 37020;
    private static final String DISCOVERY_GROUP = "239.255.42.99";
    private volatile boolean heartbeatRunning = false;
    private volatile long lastRequestAt = 0L;
    private volatile boolean extractionInProgress = false;
    private volatile int extractionAttemptCount = 0;
    private volatile String extractionStatus = "idle";
    private volatile java.util.Map<String, String> pendingPlaybackHeaders = java.util.Collections.emptyMap();

    private FrameLayout root;
    private LinearLayout welcomeScreen;

    private ImageView welcomeBackground;
    private LinearLayout connectionCard;
    private TextView ipText;
    private TextView receiverStatusText;

    private PlayerView playerView;
    private final java.util.ArrayList<String> playbackQueue = new java.util.ArrayList<>();
    private int playbackQueueIndex = -1;
    private WebView fallbackWebView;
    private TextView browserShortcut;
    private TextView settingsHomeButton;
    private TextView browserHomeButton;
    private String pendingBrowserUrl = "";
    private volatile boolean fallbackStreamCaptured = false;
    private volatile String fallbackPageUrl = "";
    private final Handler backPressHandler = new Handler(Looper.getMainLooper());
    private boolean backPressedOnceInBrowser = false;
    private final Runnable resetBrowserBackPress = () -> backPressedOnceInBrowser = false;
    private View webFullscreenView;
    private WebChromeClient.CustomViewCallback webFullscreenCallback;
    private ImageView imageView;
    private float imageZoomScale = 1.0f;
    private float imagePanX = 0f;
    private float imagePanY = 0f;
    private float imageDownX = 0f;
    private float imageDownY = 0f;
    private float imageGestureStartX = 0f;
    private float imageGestureStartY = 0f;
    private boolean imageDragging = false;
    private ProgressBar imageLoading;
    private ExoPlayer player;
    // Keep the selected level separately from mute state.
    private float lastNonZeroVolume = 1.0f;
    private boolean muted = false;

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered = false;

    private final Object playerLock = new Object();
    private SharedPreferences playbackPrefs;
    private String currentPlaybackUrl = "";
    private final Handler playbackProgressHandler = new Handler(Looper.getMainLooper());
    // Persist the position against the URI actually loaded in ExoPlayer. The
    // mutable currentPlaybackUrl can already point at the next request.
    private void savePlaybackPositionLocked() {
        if (player == null || playbackPrefs == null
                || player.getPlaybackState() == Player.STATE_ENDED
                || player.getCurrentMediaItem() == null
                || player.getCurrentMediaItem().localConfiguration == null) {
            // Do not recreate a resume point after STATE_ENDED cleared it.
            return;
        }
        String itemUrl = player.getCurrentMediaItem().localConfiguration.uri.toString();
        if (itemUrl == null || itemUrl.isEmpty()) return;
        long position = Math.max(0L, player.getCurrentPosition());
        playbackPrefs.edit().putLong("position_" + itemUrl.hashCode(), position).apply();
    }

    private final Runnable playbackProgressRunnable = new Runnable() {
        @Override public void run() {
            synchronized (playerLock) {
                if (player != null && player.isPlaying()) {
                    savePlaybackPositionLocked();
                }
            }
            playbackProgressHandler.postDelayed(this, 3000L);
        }
    };

    // تجميع روابط الفيديو/الصورة والصوت التي تصل كطلبين منفصلين بفاصل قصير.
    private final Handler mediaHandler = new Handler(Looper.getMainLooper());
    private String pendingVisualUrl;
    private String pendingAudioUrl;
    private Runnable pendingPlayback;

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }

    private void persistExtractionState() {
        getSharedPreferences("AboEzzatReceiverState", MODE_PRIVATE).edit()
                .putBoolean("extracting", extractionInProgress)
                .putInt("attempt", extractionAttemptCount)
                .putString("status", extractionStatus)
                .apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(3, 12, 32));
        window.setNavigationBarColor(Color.rgb(3, 12, 32));
        window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        playbackPrefs = getSharedPreferences("AboEzzatReceiverPlayback", MODE_PRIVATE);
        loadPlaybackHistory();
        buildScreen();
        startReceiverService();
        handleExternalIntent(getIntent());
        playbackProgressHandler.postDelayed(playbackProgressRunnable, 3000L);
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }


    private void showReceiverSettings() {
        String[] options = {
                "🔄  البحث عن التحديثات",
                "🌐  إعدادات استخراج الفيديو",
                "🔌  إعدادات الاستقبال والبورت",
                "♻  إعادة تشغيل خدمة الاستقبال",
                "🧪  تشخيص الاتصال",
                "🕘  سجل المشاهدة",
                "ℹ  معلومات الإصدار",
                "🔐  اقتران Share (قيد التطوير)",
                "🛡  مانع الإعلانات: " + (getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).getBoolean("adblock_enabled", true) ? "مفعّل" : "متوقف")
        };
        new AlertDialog.Builder(this)
                .setTitle("⚙ إعدادات الريسيفر")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: checkReceiverUpdate(); break;
                        case 1: showExtractorSettings(); break;
                        case 2: showReceiverPortSettings(); break;
                        case 3:
                            stopService(new android.content.Intent(this, ReceiverServerService.class));
                            new Handler(Looper.getMainLooper()).postDelayed(this::startReceiverService, 500L);
                            Toast.makeText(this, "جارٍ إعادة تشغيل خدمة الاستقبال…", Toast.LENGTH_LONG).show();
                            break;
                        case 4: showConnectionDiagnostics(); break;
                        case 5: showPlaybackHistory(); break;
                        case 6:
                            try {
                                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                                String version = info.versionName == null ? "غير معروف" : info.versionName;
                                long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                                new AlertDialog.Builder(this).setTitle("معلومات الريسيفر")
                                        .setMessage("AboEzzat Receiver\nالإصدار: " + version + "\nرقم البناء: " + code)
                                        .setPositiveButton("حسنًا", null).show();
                            } catch (Exception e) { Toast.makeText(this, "تعذّر قراءة معلومات الإصدار", Toast.LENGTH_SHORT).show(); }
                            break;
                        case 7: showSharePairingCode(); break;
                        case 8:
                            android.content.SharedPreferences browserPrefs = getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE);
                            boolean adBlockEnabled = !browserPrefs.getBoolean("adblock_enabled", true);
                            browserPrefs.edit().putBoolean("adblock_enabled", adBlockEnabled).apply();
                            Toast.makeText(this, "مانع الإعلانات " + (adBlockEnabled ? "مفعّل" : "متوقف")
                                    + "؛ أعد تحميل صفحة المتصفح لتطبيق التغيير", Toast.LENGTH_LONG).show();
                            break;
                    }
                }).setNegativeButton("إغلاق", null).show();
    }


    // A short-lived one-time code for pairing Share with this Receiver.
    private void showSharePairingCode() {
        SharedPreferences pairing = getSharedPreferences("AboEzzatPairing", MODE_PRIVATE);
        long expiresAt = pairing.getLong("code_expires_at", 0L);
        String code = pairing.getString("pairing_code", "");
        if (code.isEmpty() || System.currentTimeMillis() >= expiresAt) {
            java.security.SecureRandom random = new java.security.SecureRandom();
            code = String.format(java.util.Locale.US, "%08d", random.nextInt(100000000));
            expiresAt = System.currentTimeMillis() + 5L * 60L * 1000L;
            pairing.edit().putString("pairing_code", code)
                    .putLong("code_expires_at", expiresAt).putInt("failed_attempts", 0).apply();
        }
        long remaining = Math.max(0L, (expiresAt - System.currentTimeMillis() + 59999L) / 60000L);
        String message = "ده كود تجريبي لواجهة الاقتران فقط:\n\n"
                + code + "\n\nصالح لمدة تصل إلى " + remaining + " دقائق.\n"
                + "تنبيه: الاقتران الفعلي غير مفعّل حاليًا، والكود لا يربط Share ولا ينقل تسجيل الدخول أو بيانات المواقع. لا تشاركه مع أي شخص.";
        new AlertDialog.Builder(this).setTitle("🔐 الاقتران — قيد التطوير")
                .setMessage(message)
                .setPositiveButton("توليد كود جديد", (d, w) -> {
                    pairing.edit().remove("pairing_code").remove("code_expires_at")
                            .remove("failed_attempts").apply();
                    showSharePairingCode();
                })
                .setNegativeButton("إغلاق", null).show();
    }

    private void showConnectionDiagnostics() {
        String ip = "غير معروف";
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (address instanceof java.net.Inet4Address && !address.isLoopbackAddress()) {
                        ip = address.getHostAddress();
                        break;
                    }
                }
                if (!"غير معروف".equals(ip)) break;
            }
        } catch (Exception e) { Log.w(TAG, "Unable to inspect receiver IP", e); }
        int port = getSharedPreferences("receiver_settings", MODE_PRIVATE).getInt("receiver_port", 8081);
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean networkConnected = false;
        if (cm != null) {
            android.net.Network active = cm.getActiveNetwork();
            android.net.NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            networkConnected = caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        String details = "عنوان IP: " + ip + "\\nبورت الاستقبال: " + port
                + "\\nالشبكة: " + (networkConnected ? "متصل" : "غير متصل أو لم تُكتشف")
                + "\\nإصدار أندرويد: " + android.os.Build.VERSION.RELEASE
                + "\\nالجهاز: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\\n\\nلو Share لا يتصل، تأكد أن الجهازين على نفس الشبكة وأن البورت متطابق في التطبيقين.";
        new AlertDialog.Builder(this).setTitle("تشخيص الاتصال")
                .setMessage(details)
                .setPositiveButton("نسخ المعلومات", (d,w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Receiver diagnostics", details));
                        Toast.makeText(this, "تم نسخ معلومات التشخيص", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null).show();
    }

    private void showExtractorSettings() {
        android.content.SharedPreferences settings =
                getSharedPreferences("receiver_settings", MODE_PRIVATE);
        EditText field = new EditText(this);
        field.setSingleLine(false);
        field.setMinLines(2);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.setHint("https://cobalt-one.example/\\nhttps://cobalt-two.example/");
        field.setText(settings.getString("cobalt_endpoint", ""));

        android.widget.CheckBox cobalt = new android.widget.CheckBox(this);
        cobalt.setText("تشغيل محركات Cobalt (كل الخوادم بالتوازي)");
        cobalt.setChecked(settings.getBoolean("receiver_engine_cobalt", true));
        android.widget.CheckBox page = new android.widget.CheckBox(this);
        page.setText("تشغيل تحليل الصفحة ونوع المحتوى");
        page.setChecked(settings.getBoolean("receiver_engine_page", true));
        android.widget.CheckBox social = new android.widget.CheckBox(this);
        social.setText("تشغيل المحرك المتخصص للمنصات الاجتماعية");
        social.setChecked(settings.getBoolean("receiver_engine_social", true));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
        panel.setPadding(pad, pad / 2, pad, 0);
        TextView help = new TextView(this);
        help.setText("فعّل أو أوقف كل محرك بشكل مستقل. المحركات المفعّلة تعمل بالتوازي، وتُختار أفضل نتيجة متاحة. أضف خوادم Cobalt تملكها أو لديك إذن باستخدامها، كل عنوان في سطر منفصل.");
        help.setTextSize(13);
        panel.addView(help);
        panel.addView(cobalt);
        panel.addView(page);
        panel.addView(social);
        panel.addView(field);

        new AlertDialog.Builder(this).setTitle("إعدادات محركات استخراج الفيديو")
                .setView(panel)
                .setNeutralButton("مسح خوادم Cobalt", (d,w) ->
                        settings.edit().remove("cobalt_endpoint").apply())
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d,w) -> {
                    String endpoint = field.getText().toString().trim();
                    String[] endpoints = endpoint.isEmpty() ? new String[0] : endpoint.split("[,;\\s]+");
                    for (String candidate : endpoints) {
                        Uri u = Uri.parse(candidate);
                        String scheme = u.getScheme();
                        if (u.getHost() == null || !("https".equalsIgnoreCase(scheme)
                                || "http".equalsIgnoreCase(scheme))) {
                            Toast.makeText(this, "عنوان غير صحيح: " + candidate, Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    settings.edit()
                            .putString("cobalt_endpoint", String.join("\\n", endpoints))
                            .putBoolean("receiver_engine_cobalt", cobalt.isChecked())
                            .putBoolean("receiver_engine_page", page.isChecked())
                            .putBoolean("receiver_engine_social", social.isChecked())
                            .apply();
                    Toast.makeText(this, "تم حفظ إعدادات المحركات", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private void showReceiverPortSettings() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(getSharedPreferences("receiver_settings", MODE_PRIVATE).getInt("receiver_port", 8081)));
        new AlertDialog.Builder(this).setTitle("إعدادات الاستقبال")
                .setMessage("غيّر البورت عند الحاجة فقط. يجب أن يكون نفس الرقم مضبوطًا في تطبيق Share.")
                .setView(field).setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ وإعادة التشغيل", (d,w) -> {
                    int port;
                    try { port = Integer.parseInt(field.getText().toString().trim()); } catch (Exception e) { port = -1; }
                    if (port < 1024 || port > 65535) {
                        Toast.makeText(this, "اختَر بورتًا بين 1024 و65535", Toast.LENGTH_LONG).show();
                        return;
                    }
                    getSharedPreferences("receiver_settings", MODE_PRIVATE).edit().putInt("receiver_port", port).apply();
                    stopService(new android.content.Intent(this, ReceiverServerService.class));
                    new Handler(Looper.getMainLooper()).postDelayed(this::startReceiverService, 500L);
                    Toast.makeText(this, "تم حفظ البورت وإعادة تشغيل الخدمة. اضبط نفس الرقم في Share.", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void checkReceiverUpdate() {
        Toast.makeText(this, "جارٍ البحث عن تحديث للريسيفر…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.github.com/repos/mohamedezzat19/AboEzzat-Receiver/releases/latest");
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) throw new Exception("HTTP " + status);
                StringBuilder body = new StringBuilder();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line; while ((line = reader.readLine()) != null) body.append(line);
                }
                org.json.JSONObject release = new org.json.JSONObject(body.toString());
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("versionCode\\s*:\\s*(\\d+)").matcher(release.optString("body", ""));
                long latest = matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
                android.content.pm.PackageInfo installedInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                long installed = android.os.Build.VERSION.SDK_INT >= 28 ? installedInfo.getLongVersionCode() : installedInfo.versionCode;
                String apkUrl = null;
                org.json.JSONArray assets = release.optJSONArray("assets");
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    org.json.JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && "app-release.apk".equals(asset.optString("name"))) { apkUrl = asset.optString("browser_download_url", null); break; }
                }
                final long latestCode = latest;
                final long installedCode = installed;
                final String downloadUrl = apkUrl;
                runOnUiThread(() -> {
                    if (latestCode <= installedCode || downloadUrl == null || downloadUrl.isEmpty()) {
                        new AlertDialog.Builder(this).setTitle("البحث عن التحديثات")
                                .setMessage("أنت على أحدث إصدار متاح، أو لم تُنشر نسخة APK موقّعة بعد.\nالإصدار المثبت: " + installedCode + "\nأحدث إصدار: " + latestCode)
                                .setPositiveButton("حسنًا", null).show();
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle("تحديث جديد متاح")
                            .setMessage("الإصدار المثبت: " + installedCode + "\nالإصدار الجديد: " + latestCode + "\nهل تريد تنزيله وتثبيته؟")
                            .setNegativeButton("لاحقًا", null)
                            .setPositiveButton("تنزيل وتثبيت", (d,w) -> downloadAndInstallReceiver(downloadUrl))
                            .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Receiver update check failed", e);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("تعذّر البحث عن التحديث")
                        .setMessage("تأكد من اتصال الإنترنت ثم حاول مرة أخرى.").setPositiveButton("حسنًا", null).show());
            } finally { if (connection != null) connection.disconnect(); }
        }, "receiver-update-check").start();
    }

    private void downloadAndInstallReceiver(String apkUrl) {
        new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            java.io.File apk = new java.io.File(getExternalCacheDir() != null ? getExternalCacheDir() : getCacheDir(), "aboezzat-receiver-update.apk");
            try {
                connection = (java.net.HttpURLConnection) new URL(apkUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new Exception("Download failed");
                try (java.io.InputStream in = connection.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                    byte[] buffer = new byte[16384]; int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
                // Validate the downloaded package before launching Android's installer.
                android.content.pm.PackageInfo archive = getPackageManager().getPackageArchiveInfo(
                        apk.getAbsolutePath(), android.content.pm.PackageManager.GET_META_DATA);
                if (archive == null || !getPackageName().equals(archive.packageName)) {
                    throw new Exception("Downloaded APK is not the Receiver package");
                }
                android.content.pm.PackageInfo installedInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
                long downloadedCode = android.os.Build.VERSION.SDK_INT >= 28
                        ? archive.getLongVersionCode() : archive.versionCode;
                long installedCode = android.os.Build.VERSION.SDK_INT >= 28
                        ? installedInfo.getLongVersionCode() : installedInfo.versionCode;
                if (downloadedCode <= installedCode) {
                    throw new Exception("Downloaded APK is not newer than installed version");
                }
                runOnUiThread(() -> {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                            Toast.makeText(this, "اسمح للتطبيق بتثبيت التحديثات من هذا المصدر ثم أعد المحاولة.", Toast.LENGTH_LONG).show();
                            android.content.Intent settings = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(settings);
                            return;
                        }
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
                        android.content.Intent install = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                        install.setDataAndType(uri, "application/vnd.android.package-archive");
                        install.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(install);
                    } catch (Exception e) {
                        Log.e(TAG, "Could not launch receiver installer", e);
                        Toast.makeText(this, "تعذّر فتح مثبت التحديث. افتح إعدادات التطبيق وتأكد من السماح بالتثبيت.", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Receiver update download/validation failed", e);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("تعذّر اعتماد ملف التحديث")
                        .setMessage("لم يتم تثبيت أي ملف. قد يكون التنزيل غير مكتمل أو الملف ليس إصدارًا أحدث من الريسيفر.")
                        .setPositiveButton("حسنًا", null).show());
            } finally { if (connection != null) connection.disconnect(); }
        }, "receiver-update-download").start();
    }

    private void startReceiverService() {
        android.content.Intent serviceIntent = new android.content.Intent(this, ReceiverServerService.class);
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to start background receiver service", e);
            Toast.makeText(this, "تعذّر تشغيل الاستقبال بالخلفية. افتح التطبيق مرة أخرى.", Toast.LENGTH_LONG).show();
        }
    }

    // Support web links shared directly to Receiver in addition to links sent over port 8081.
    private void handleExternalIntent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ReceiverServerService.ACTION_SERVER_REQUEST.equals(action)) {
            String command = intent.getStringExtra(ReceiverServerService.EXTRA_COMMAND);
            String incomingUrl = intent.getStringExtra(ReceiverServerService.EXTRA_URL);
            String incomingAudio = intent.getStringExtra(ReceiverServerService.EXTRA_AUDIO);
            if (command != null && !command.trim().isEmpty()) {
                handleRemoteCommand(command.trim());
            } else {
                clearPendingServerMedia();
                queueIncomingMedia(incomingUrl, incomingAudio);
            }
            return;
        }

        String url = null;
        if (android.content.Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) {
                String scheme = data.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    url = data.toString();
                }
            }
        } else if (android.content.Intent.ACTION_SEND.equals(action)) {
            CharSequence sharedText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
            if (sharedText != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)https?://[^\\s<>\\\"']+")
                        .matcher(sharedText);
                if (matcher.find()) {
                    url = matcher.group().replaceAll("[),.?!;،؛؟\\]}]+$", "");
                    Uri parsed = Uri.parse(url);
                    String scheme = parsed.getScheme();
                    if (parsed.getHost() == null
                            || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                        url = null;
                    }
                }
            }
        }

        if (url != null && !url.trim().isEmpty()) {
            clearPendingServerMedia();
            queueIncomingMedia(url, null);
            return;
        }

        // If the OS prevented a background launch, process the last received link
        // when the user next opens Receiver. Ignore stale pending requests after 10 minutes.
        SharedPreferences pending = getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE);
        String pendingUrl = pending.getString("url", "");
        String pendingAudio = pending.getString("audio", "");
        long receivedAt = pending.getLong("received_at", 0L);
        if (System.currentTimeMillis() - receivedAt <= 10 * 60 * 1000L
                && (!pendingUrl.trim().isEmpty() || !pendingAudio.trim().isEmpty())) {
            clearPendingServerMedia();
            queueIncomingMedia(pendingUrl, pendingAudio);
        } else if (receivedAt != 0L) {
            clearPendingServerMedia();
        }
    }

    private void clearPendingServerMedia() {
        getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE).edit()
                .remove("url")
                .remove("audio")
                .remove("received_at")
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerNetworkCallback();
        updateConnectionInfo();
    }

    @Override
    protected void onPause() {
        unregisterNetworkCallback();
        super.onPause();
    }

    // تحديث عنوان IP فور تغيّر الشبكة، وليس عند فتح الشاشة فقط.
    private void registerNetworkCallback() {
        if (networkCallbackRegistered) return;

        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if (cm == null) return;

        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                runOnUiThread(() -> updateConnectionInfo());
            }

            @Override
            public void onLinkPropertiesChanged(
                    Network network,
                    LinkProperties linkProperties) {
                runOnUiThread(() -> updateConnectionInfo());
            }

            @Override
            public void onLost(Network network) {
                runOnUiThread(() -> updateConnectionInfo());
            }
        };

        try {
            cm.registerDefaultNetworkCallback(networkCallback);
            networkCallbackRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not register network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        if (!networkCallbackRegistered || networkCallback == null) return;

        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "Could not unregister network callback", e);
            }
        }

        networkCallbackRegistered = false;
        networkCallback = null;
    }

    private GradientDrawable gradientBackground() {
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Color.rgb(4, 17, 43),
                        Color.rgb(2, 8, 24),
                        Color.rgb(7, 27, 54)
                }
        );

        bg.setCornerRadius(dp(24));
        return bg;
    }

    private GradientDrawable roundedBackground(
            int color,
            int strokeColor,
            int radius) {

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), strokeColor);

        return bg;
    }

    private TextView makeText(
            String text,
            int size,
            int color,
            boolean bold) {

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                size
        );
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(
                Typeface.create(
                        "sans-serif",
                        bold ? Typeface.BOLD : Typeface.NORMAL
                )
        );
        view.setIncludeFontPadding(true);

        return view;
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(3, 12, 32));

        // صورة الترحيب في أعلى الشاشة، كاملة بلا قص أو تمديد.
        welcomeBackground = new ImageView(this);
        welcomeBackground.setImageResource(
                R.drawable.welcome_background
        );
        welcomeBackground.setScaleType(
                ImageView.ScaleType.FIT_CENTER
        );
        welcomeBackground.setAdjustViewBounds(true);
        welcomeBackground.setBackgroundColor(
                Color.rgb(3, 12, 32)
        );
        welcomeBackground.setVisibility(View.VISIBLE);

        FrameLayout.LayoutParams welcomeImageParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL
                );

        welcomeImageParams.topMargin = dp(8);
        root.addView(welcomeBackground, welcomeImageParams);

        welcomeScreen = new LinearLayout(this);
        welcomeScreen.setVisibility(View.GONE);

        root.addView(welcomeScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // معلومات السيرفر.
        connectionCard = new LinearLayout(this);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setGravity(Gravity.CENTER);
        connectionCard.setPadding(
                dp(8), dp(5), dp(8), dp(5)
        );

        connectionCard.setBackground(roundedBackground(
                Color.argb(232, 5, 12, 32),
                Color.rgb(255, 197, 74),
                18
        ));

        connectionCard.setElevation(dp(8));

        TextView brand = makeText(
                "ABO EZZAT • RECEIVER",
                10,
                Color.rgb(255, 207, 94),
                true
        );

        brand.setLetterSpacing(0.12f);
        // Long-press the existing label to configure an optional Cobalt API.
        brand.setOnLongClickListener(v -> {
            EditText field = new EditText(this);
            field.setSingleLine(true);
            field.setHint("https://your-cobalt-instance/");
            field.setText(getSharedPreferences("receiver_settings", MODE_PRIVATE)
                    .getString("cobalt_endpoint", ""));
            new AlertDialog.Builder(this)
                    .setTitle("إعداد مستخرج الفيديو")
                    .setMessage("اختياري: أدخل عنوان Cobalt API الذي تملكه أو لديك إذن باستخدامه. اتركه فارغًا لتعطيله.")
                    .setView(field)
                    .setNegativeButton("إلغاء", (d, w) -> { })
                    .setNeutralButton("مسح", (d, w) -> getSharedPreferences("receiver_settings", MODE_PRIVATE)
                            .edit().remove("cobalt_endpoint").apply())
                    .setPositiveButton("حفظ", (d, w) -> {
                        String endpoint = field.getText().toString().trim();
                        if (endpoint.isEmpty()) {
                            getSharedPreferences("receiver_settings", MODE_PRIVATE).edit()
                                    .remove("cobalt_endpoint").apply();
                        } else {
                            Uri endpointUri = Uri.parse(endpoint);
                            String endpointScheme = endpointUri.getScheme();
                            boolean validEndpoint = endpointUri.getHost() != null
                                    && ("https".equalsIgnoreCase(endpointScheme)
                                    || "http".equalsIgnoreCase(endpointScheme));
                            if (validEndpoint) {
                                // Preserve paths and query parameters exactly; appending a slash
                                // after a query string can silently corrupt a configured API URL.
                                getSharedPreferences("receiver_settings", MODE_PRIVATE).edit()
                                        .putString("cobalt_endpoint", endpoint).apply();
                                Toast.makeText(this, "تم حفظ عنوان المستخرج", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(this, "أدخل عنوانًا صالحًا يبدأ بـ http:// أو https:// ويحتوي على اسم مضيف", Toast.LENGTH_LONG).show();
                            }
                        }
                    }).show();
            return true;
        });

        connectionCard.addView(brand,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

        TextView heading = makeText(
                "أهلاً بك يا أبو عزت",
                15,
                Color.WHITE,
                true
        );

        LinearLayout.LayoutParams headingParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        headingParams.topMargin = dp(2);
        connectionCard.addView(heading, headingParams);

        receiverStatusText = makeText(
                "● الاستقبال يعمل...",
                11,
                Color.rgb(255, 222, 151),
                true
        );

        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        statusParams.topMargin = dp(6);
        connectionCard.addView(receiverStatusText, statusParams);

        ipText = makeText(
                "IP: جارٍ التحديد...",
                11,
                Color.WHITE,
                true
        );

        ipText.setTextIsSelectable(true);

        LinearLayout.LayoutParams ipParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        ipParams.topMargin = dp(4);
        connectionCard.addView(ipText, ipParams);

        TextView portText = makeText(
                "PORT: " + getSharedPreferences("receiver_settings", MODE_PRIVATE).getInt("receiver_port", 8081),
                10,
                Color.rgb(255, 207, 94),
                true
        );

        portText.setTextIsSelectable(true);
        portText.setOnClickListener(v -> {
            EditText portInput = new EditText(this);
            portInput.setSingleLine(true);
            portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            portInput.setText(String.valueOf(getSharedPreferences("receiver_settings", MODE_PRIVATE)
                    .getInt("receiver_port", 8081)));
            new AlertDialog.Builder(this)
                    .setTitle("تغيير بورت الاستقبال")
                    .setMessage("القيمة الافتراضية 8081. اختَر رقمًا بين 1024 و65535، ثم اضغط حفظ. لازم تضع الرقم نفسه في AboEzzat Share.")
                    .setView(portInput)
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("حفظ وإعادة تشغيل", (dialog, which) -> {
                        String value = portInput.getText().toString().trim();
                        int port;
                        try { port = Integer.parseInt(value); }
                        catch (Exception e) { port = -1; }
                        if (port < 1024 || port > 65535) {
                            Toast.makeText(this, "اختَر بورتًا بين 1024 و65535", Toast.LENGTH_LONG).show();
                            return;
                        }
                        getSharedPreferences("receiver_settings", MODE_PRIVATE).edit()
                                .putInt("receiver_port", port).apply();
                        portText.setText("PORT: " + port);
                        stopService(new android.content.Intent(this, ReceiverServerService.class));
                        portText.postDelayed(() -> startReceiverService(), 500L);
                        Toast.makeText(this, "تم حفظ البورت. اضبط الرقم نفسه في Share.", Toast.LENGTH_LONG).show();
                    }).show();
        });

        LinearLayout.LayoutParams portParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        portParams.topMargin = dp(2);
        connectionCard.addView(portText, portParams);

        TextView instructions = makeText(
                "اتصل بنفس الشبكة",
                9,
                Color.rgb(170, 194, 220),
                false
        );

        LinearLayout.LayoutParams instructionParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        instructionParams.topMargin = dp(3);
        connectionCard.addView(instructions, instructionParams);

        // بطاقة صغيرة في أقصى أسفل اليمين، دون تغيير صورة الخلفية.
        FrameLayout.LayoutParams cardParams =
                new FrameLayout.LayoutParams(
                        dp(230),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.END
                );

        cardParams.setMargins(
                dp(8),
                dp(8),
                dp(8),
                dp(12)
        );

        root.addView(connectionCard, cardParams);

        // أزرار رئيسية متجاورة لتسهيل الوصول بالريموت: الإعدادات ثم المتصفح.
        settingsHomeButton = makeText("⚙", 25, Color.rgb(255, 207, 94), true);
        browserHomeButton = makeText("🌐", 23, Color.WHITE, true);
        TextView settingsButton = settingsHomeButton;
        settingsButton.setId(View.generateViewId());
        browserHomeButton.setId(View.generateViewId());
        TextView[] homeButtons = {settingsButton, browserHomeButton};
        for (TextView homeButton : homeButtons) {
            homeButton.setGravity(Gravity.CENTER);
            homeButton.setFocusable(true);
            homeButton.setFocusableInTouchMode(true);
            homeButton.setClickable(true);
            homeButton.setPadding(dp(6), dp(4), dp(6), dp(4));
            homeButton.setBackground(roundedBackground(Color.rgb(20, 34, 61), Color.rgb(86, 132, 255), 14));
            homeButton.setOnFocusChangeListener((v, focused) -> {
                v.setScaleX(focused ? 1.12f : 1f);
                v.setScaleY(focused ? 1.12f : 1f);
                v.setElevation(focused ? dp(12) : dp(3));
                v.setBackground(roundedBackground(focused ? Color.rgb(43, 66, 108) : Color.rgb(20, 34, 61),
                        focused ? Color.rgb(255, 196, 87) : Color.rgb(86, 132, 255), 14));
            });
        }
        settingsButton.setContentDescription("الإعدادات");
        browserHomeButton.setContentDescription("المتصفح");
        settingsButton.setNextFocusDownId(browserHomeButton.getId());
        browserHomeButton.setNextFocusUpId(settingsButton.getId());
        settingsButton.setOnClickListener(v -> showReceiverSettings());
        browserHomeButton.setOnClickListener(v -> browserShortcut.performClick());
        FrameLayout.LayoutParams settingsButtonParams = new FrameLayout.LayoutParams(dp(58), dp(52), Gravity.TOP | Gravity.END);
        settingsButtonParams.setMargins(dp(8), dp(12), dp(12), dp(8));
        root.addView(settingsButton, settingsButtonParams);
        FrameLayout.LayoutParams browserHomeParams = new FrameLayout.LayoutParams(dp(58), dp(52), Gravity.TOP | Gravity.END);
        browserHomeParams.setMargins(dp(8), dp(72), dp(12), dp(8));
        root.addView(browserHomeButton, browserHomeParams);

        // مشغل الفيديو والصوت.
        // Inflate with surface_type="texture_view" so Media3 can apply rotation metadata
        // on Android TV devices; PlayerView exposes this setting through XML attributes.
        playerView = (PlayerView) getLayoutInflater().inflate(R.layout.player_view_texture, root, false);
        playerView.setUseController(true);
        playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setVisibility(View.GONE);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // عرض الصور المستقبلة مع الحفاظ على أبعادها.
        imageView = new ImageView(this);
        imageView.setVisibility(View.GONE);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setFocusable(true);
        imageView.setFocusableInTouchMode(true);
        imageView.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    imageDownX = event.getX();
                    imageDownY = event.getY();
                    imageGestureStartX = imagePanX;
                    imageGestureStartY = imagePanY;
                    imageDragging = false;
                    return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (imageZoomScale > 1.0f) {
                        float dx = event.getX() - imageDownX;
                        float dy = event.getY() - imageDownY;
                        if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) imageDragging = true;
                        imagePanX = imageGestureStartX + dx;
                        imagePanY = imageGestureStartY + dy;
                        applyImageTransform();
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                    if (!imageDragging && imageZoomScale <= 1.0f) {
                        imageZoomScale = 2.0f;
                        applyImageTransform();
                    } else if (!imageDragging && imageZoomScale > 1.0f) {
                        resetImageTransform();
                    }
                    return true;
                default:
                    return true;
            }
        });

        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        imageLoading = new ProgressBar(this);
        imageLoading.setVisibility(View.GONE);

        FrameLayout.LayoutParams loadingParams =
                new FrameLayout.LayoutParams(
                        dp(56),
                        dp(56),
                        Gravity.CENTER
                );

        root.addView(imageLoading, loadingParams);

        // Optional TV browser shortcut, pinned to the lower-left corner.
        // It only appears after direct media resolution fails; it never opens automatically.
        browserShortcut = makeText("🌐  المتصفح", 14, Color.WHITE, true);
        browserShortcut.setPadding(dp(18), dp(10), dp(18), dp(10));
        browserShortcut.setBackground(roundedBackground(
                Color.argb(235, 17, 27, 49), Color.rgb(86, 132, 255), 24));
        browserShortcut.setFocusable(true);
        browserShortcut.setClickable(true);
        browserShortcut.setVisibility(View.VISIBLE);
        browserShortcut.setOnClickListener(v -> {
            String url = pendingBrowserUrl;
            if (isHttpUrl(url)) {
                showWebFallback(url);
                return;
            }
            EditText addressInput = new EditText(this);
            addressInput.setSingleLine(true);
            addressInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            addressInput.setHint("https://example.com");
            new AlertDialog.Builder(this)
                    .setTitle("المتصفح المدمج")
                    .setMessage("اكتب رابط الموقع لفتحه على التلفزيون. المتصفح اختياري ولن يفتح تلقائيًا.")
                    .setView(addressInput)
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("فتح", (dialog, which) -> {
                        String entered = addressInput.getText().toString().trim();
                        if (!entered.isEmpty() && !entered.matches("(?i)^https?://.*")) {
                            entered = "https://" + entered;
                        }
                        if (isHttpUrl(entered)) {
                            pendingBrowserUrl = entered;
                            showWebFallback(entered);
                        } else {
                            Toast.makeText(this, "اكتب رابط HTTP أو HTTPS صحيح", Toast.LENGTH_LONG).show();
                        }
                    }).show();
        });
        // Keep the original shortcut available internally, but the visible home entry
        // is the compact top-row browser icon beside Settings.
        browserShortcut.setVisibility(View.GONE);
        // The old lower-left browser shortcut is deliberately not attached to the screen.

        setContentView(root);
        settingsButton.requestFocus();
    }

    // قراءة عنوان IP الحالي من الشبكة.
    private String getCurrentIpAddress() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager)
                    getSystemService(CONNECTIVITY_SERVICE);

            if (cm != null) {
                Network activeNetwork = cm.getActiveNetwork();

                if (activeNetwork != null) {
                    LinkProperties properties =
                            cm.getLinkProperties(activeNetwork);

                    if (properties != null) {
                        for (android.net.LinkAddress linkAddress :
                                properties.getLinkAddresses()) {

                            InetAddress address =
                                    linkAddress.getAddress();

                            if (address instanceof Inet4Address
                                    && !address.isLoopbackAddress()
                                    && !address.isLinkLocalAddress()
                                    && address.isSiteLocalAddress()) {

                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read active network IP", e);
        }

        // طريقة احتياطية لبعض أجهزة التلفزيون.
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface =
                        interfaces.nextElement();

                if (!networkInterface.isUp()
                        || networkInterface.isLoopback()) {
                    continue;
                }

                Enumeration<InetAddress> addresses =
                        networkInterface.getInetAddresses();

                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();

                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && address.isSiteLocalAddress()) {

                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate network interfaces", e);
        }

        return null;
    }

    private void updateConnectionInfo() {
        if (ipText == null) return;

        String ip = getCurrentIpAddress();

        if (ip == null || ip.trim().isEmpty()) {
            ipText.setText(
                    "IP الحالي: غير متاح — اتصل بالشبكة"
            );
        } else {
            ipText.setText(
                    "IP الحالي: " + ip + ":" + getSharedPreferences("receiver_settings", MODE_PRIVATE).getInt("receiver_port", 8081)
            );
        }
    }

    private void startReceiver() {
        running = true;
        startDiscoveryHeartbeat();

        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);

                Log.i(
                        TAG,
                        "Receiver listening on port " + PORT
                );

                runOnUiThread(() -> {
                    if (receiverStatusText != null) {
                        receiverStatusText.setText(
                                "●  الاستقبال يعمل الآن"
                        );

                        receiverStatusText.setTextColor(
                                Color.rgb(49, 239, 164)
                        );
                    }

                    updateConnectionInfo();
                });

                while (running) {
                    Socket client = serverSocket.accept();

                    Thread requestThread = new Thread(
                            () -> handleRequest(client)
                    );

                    requestThread.start();
                }

            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Server error", e);

                    runOnUiThread(() -> {
                        if (receiverStatusText != null) {
                            receiverStatusText.setText(
                                    "●  تعذّر تشغيل الاستقبال"
                            );

                            receiverStatusText.setTextColor(
                                    Color.rgb(255, 100, 100)
                            );
                        }
                    });
                }
            }
        }, "ReceiverServer");

        serverThread.start();
    }


    // إعلان وجود الريسيفر على الشبكة المحلية عبر multicast، دون الاعتماد على مسح كل IP.
    private void startDiscoveryHeartbeat() {
        if (heartbeatRunning) return;
        heartbeatRunning = true;
        Thread thread = new Thread(() -> {
            while (running && heartbeatRunning) {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    byte[] data = ("ABOEZZAT_RECEIVER|" + getCurrentIpAddress() + "|" + PORT)
                            .getBytes(StandardCharsets.UTF_8);
                    InetAddress group = InetAddress.getByName(DISCOVERY_GROUP);
                    DatagramPacket packet = new DatagramPacket(data, data.length, group, DISCOVERY_PORT);
                    socket.send(packet);
                } catch (Exception e) {
                    Log.w(TAG, "Discovery heartbeat send failed", e);
                }
                try { Thread.sleep(1200L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }, "ReceiverDiscoveryHeartbeat");
        thread.setDaemon(true);
        thread.start();
    }

    private void handleRequest(Socket client) {
        lastRequestAt = System.currentTimeMillis();
        String mediaUrl = null;
        String audioUrl = null;
        String command = null;
        java.util.Map<String, String> mediaHeaders = new java.util.LinkedHashMap<>();

        try (Socket socket = client) {
            socket.setSoTimeout(5000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8
                    )
            );

            String requestLine = reader.readLine();

            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                int contentLength = 0;
                String headerLine;
                while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                    int colon = headerLine.indexOf(':');
                    if (colon > 0 && "content-length".equalsIgnoreCase(headerLine.substring(0, colon).trim())) {
                        try { contentLength = Math.min(16384, Math.max(0, Integer.parseInt(headerLine.substring(colon + 1).trim()))); }
                        catch (NumberFormatException ignored) { contentLength = 0; }
                    }
                }
                if ("POST".equalsIgnoreCase(method) && parts.length >= 2 && "/play".equals(parts[1]) && contentLength > 0) {
                    StringBuilder body = new StringBuilder();
                    while (body.length() < contentLength) {
                        int ch = reader.read();
                        if (ch < 0) break;
                        body.append((char) ch);
                    }
                    JSONObject payload = new JSONObject(body.toString());
                    mediaUrl = payload.optString("url", null);
                    audioUrl = payload.optString("audio", null);
                    JSONObject headers = payload.optJSONObject("headers");
                    if (headers != null) {
                        java.util.Iterator<String> keys = headers.keys();
                        while (keys.hasNext() && mediaHeaders.size() < 12) {
                            String key = keys.next();
                            String lower = key.toLowerCase(Locale.ROOT);
                            String value = headers.optString(key, "");
                            if (value.length() <= 2048 && !value.contains("\\r") && !value.contains("\\n")
                                    && ("user-agent".equals(lower) || "referer".equals(lower) || "origin".equals(lower)
                                    || "accept".equals(lower) || "accept-language".equals(lower))) {
                                mediaHeaders.put(key, value);
                            }
                        }
                    }
                } else if (parts.length >= 2) {
                    String target = parts[1];
                    int questionIndex = target.indexOf('?');

                    if (questionIndex >= 0) {
                        String query = target.substring(
                                questionIndex + 1
                        );

                        for (String pair : query.split("&")) {
                            String[] item = pair.split("=", 2);

                            if (item.length != 2) {
                                continue;
                            }

                            String key = URLDecoder.decode(
                                    item[0],
                                    "UTF-8"
                            );

                            String value = URLDecoder.decode(
                                    item[1],
                                    "UTF-8"
                            );

                            if ("url".equals(key)
                                    || "video".equals(key)) {
                                mediaUrl = value;

                            } else if ("audio".equals(key)) {
                                audioUrl = value;
                            } else if ("command".equals(key)) {
                                command = value;
                            }
                        }
                    }
                }
            }

            String responseBody = "OK";
            String contentType = "text/plain; charset=utf-8";
            if ("status".equals(command)) {
                JSONObject statusJson = new JSONObject();
                statusJson.put("extracting", extractionInProgress);
                statusJson.put("attempt", extractionAttemptCount);
                statusJson.put("status", extractionStatus);
                responseBody = statusJson.toString();
                contentType = "application/json; charset=utf-8";
            }
            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            byte[] response = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType + "\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: " + bodyBytes.length + "\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8);

            OutputStream output = socket.getOutputStream();
            output.write(response);
            output.write(bodyBytes);
            output.flush();

        } catch (Exception e) {
            Log.e(TAG, "Request error", e);
        }

        if ("ping".equals(command) || "status".equals(command)) {
            return; // Identity/status probe only; do not send it to the playback remote.
        }
        if (command != null && !command.trim().isEmpty()) {
            final String requestedCommand = command.trim();
            // Log the exact command reaching the receiver. This makes it possible to
            // distinguish a network/IP problem from a playback-control problem.
            Log.i(TAG, "Remote command received: " + requestedCommand
                    + ", playerAvailable=" + (player != null));
            runOnUiThread(() -> {
                try {
                    handleRemoteCommand(requestedCommand);
                } catch (Exception e) {
                    Log.e(TAG, "Remote command failed: " + requestedCommand, e);
                    Toast.makeText(MainActivity.this,
                            "تعذّر تنفيذ أمر الريموت: " + requestedCommand,
                            Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }

        if ((mediaUrl != null && !mediaUrl.trim().isEmpty())
                || (audioUrl != null && !audioUrl.trim().isEmpty())) {
            final String receivedMediaUrl = mediaUrl;
            final String receivedAudioUrl = audioUrl;
            final java.util.Map<String, String> receivedHeaders = new java.util.LinkedHashMap<>(mediaHeaders);
            runOnUiThread(() -> queueIncomingMedia(receivedMediaUrl, receivedAudioUrl, receivedHeaders));
        }
    }

    private boolean isYouTubeUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("youtu.be")
                    || host.equals("youtube.com")
                    || host.endsWith(".youtube.com")
                    || host.equals("youtube-nocookie.com")
                    || host.endsWith(".youtube-nocookie.com");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isSocialVideoUrl(String value) {
        if (value == null || value.trim().isEmpty() || isYouTubeUrl(value)) return false;
        try {
            String host = Uri.parse(value.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("tiktok.com") || host.endsWith(".tiktok.com")
                    || host.equals("instagram.com") || host.endsWith(".instagram.com")
                    || host.equals("threads.net") || host.endsWith(".threads.net")
                    || host.equals("threads.com") || host.endsWith(".threads.com")
                    || host.equals("facebook.com") || host.endsWith(".facebook.com")
                    || host.equals("fb.watch") || host.endsWith(".fb.watch")
                    || host.equals("fb.me") || host.endsWith(".fb.me");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDirectStreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String lower = value.toLowerCase(Locale.ROOT);
            return lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v")
                    || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mov")
                    || lowerPath.endsWith(".flv") || lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mpd")
                    || lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a")
                    || lowerPath.endsWith(".aac") || lowerPath.endsWith(".ogg")
                    || lowerPath.endsWith(".oga") || lowerPath.endsWith(".opus")
                    || lowerPath.endsWith(".wav") || lowerPath.endsWith(".flac")
                    || lowerPath.endsWith(".mid") || lowerPath.endsWith(".midi")
                    || lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("video_playback") || lower.contains("/videoplayback")
                    || lower.contains("contenttype=video")
                    || lower.contains("mime=audio") || lower.contains("type=audio")
                    || lower.contains("audio_playback") || lower.contains("/audioplayback")
                    || lower.contains("contenttype=audio");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String cleanExtractedUrl(String value) {
        if (value == null) return null;
        String candidate = value.trim()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u002F", "/")
                .replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("\\u0026", "&");
        while (candidate.endsWith("\\") || candidate.endsWith(",")
                || candidate.endsWith(";") || candidate.endsWith(")")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        return candidate.startsWith("http://") || candidate.startsWith("https://")
                ? candidate : null;
    }

    // Extract only publicly exposed media URLs from page metadata or player JSON.
    // No login, DRM, signature bypass, or protected-stream circumvention is attempted.
    private String extractDirectMediaFromHtml(String html, String pageUrl) {
        if (html == null || html.isEmpty()) return null;
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();

        java.util.regex.Matcher metaTags = java.util.regex.Pattern
                .compile("(?is)<meta\\b[^>]*>")
                .matcher(html);
        while (metaTags.find()) {
            String tag = metaTags.group();
            java.util.regex.Matcher property = java.util.regex.Pattern
                    .compile("(?i)(?:property|name)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
                    .matcher(tag);
            java.util.regex.Matcher content = java.util.regex.Pattern
                    .compile("(?i)content\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
                    .matcher(tag);
            if (!property.find() || !content.find()) continue;
            String key = property.group(1).toLowerCase(Locale.ROOT);
            if (key.equals("og:video") || key.equals("og:video:url")
                    || key.equals("og:video:secure_url")
                    || key.equals("twitter:player:stream")
                    || key.equals("twitter:player:stream:content_type")) {
                String candidate = cleanExtractedUrl(content.group(1));
                if (candidate != null) candidates.add(candidate);
            }
        }

        String[] fieldNames = new String[] {
                "playAddr", "play_addr", "downloadAddr", "download_addr",
                "contentUrl", "content_url", "video_url", "videoUrl",
                "playbackUrl", "playback_url", "hls_url", "hlsUrl",
                "playUrl", "play_url", "stream_url", "streamUrl",
                "hls_pull_url", "hlsPullUrl", "flv_pull_url", "flvPullUrl",
                "pull_url", "pullUrl", "liveUrl", "live_url",
                "flvUrl", "flv_url", "flvPullUrl", "flv_pull_url",
                "hlsPullUrl", "hls_pull_url", "live_play_url", "livePlayUrl"
        };
        for (String field : fieldNames) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?is)['\\\"]?" + java.util.regex.Pattern.quote(field)
                            + "['\\\"]?\\s*[:=]\\s*['\\\"]([^'\\\"]{8,})['\\\"]")
                    .matcher(html);
            while (matcher.find()) {
                String candidate = cleanExtractedUrl(matcher.group(1));
                if (candidate != null) candidates.add(candidate);
            }
        }

        java.util.regex.Matcher rawMedia = java.util.regex.Pattern
                .compile("(?is)(https?:\\\\?/\\\\?/[^\\s'\\\"><]+?\\.(?:mp4|m4v|webm|mov|m3u8|mpd|mp3|m4a|aac|ogg|oga|opus|wav|flac)(?:\\?[^\\s'\\\"><]*)?)")
                .matcher(html);
        while (rawMedia.find()) {
            String candidate = cleanExtractedUrl(rawMedia.group(1));
            if (candidate != null) candidates.add(candidate);
        }

        for (String candidate : candidates) {
            if (isDirectStreamUrl(candidate) && !candidate.equals(pageUrl)) return candidate;
        }
        return null;
    }

    private volatile int extractionGeneration = 0;

    private void resolveSharedVideoToStream(String pageUrl) {
        final int generation = ++extractionGeneration;
        android.content.SharedPreferences settings =
                getSharedPreferences("receiver_settings", MODE_PRIVATE);
        boolean useCobalt = settings.getBoolean("receiver_engine_cobalt", true);
        boolean usePage = settings.getBoolean("receiver_engine_page", true);
        boolean useSocial = settings.getBoolean("receiver_engine_social", true);
        String configured = settings.getString("cobalt_endpoint", "").trim();
        java.util.ArrayList<String> endpoints = new java.util.ArrayList<>();
        if (useCobalt && !configured.isEmpty()) {
            for (String endpoint : configured.split("[,;\\s]+")) {
                if (isHttpUrl(endpoint)) endpoints.add(endpoint);
            }
        }

        int tasks = (useCobalt ? Math.max(1, endpoints.size()) : 0)
                + (usePage ? 1 : 0) + (useSocial ? 1 : 0);
        extractionInProgress = true;
        extractionAttemptCount = 0;
        extractionStatus = "starting_parallel";
        persistExtractionState();
        runOnUiThread(() -> {
            if (receiverStatusText != null) {
                receiverStatusText.setText("تشغيل محركات الاستخراج المفعّلة بالتوازي…");
            }
        });
        if (tasks == 0) {
            extractionInProgress = false;
            extractionStatus = "web_fallback";
            persistExtractionState();
            runOnUiThread(() -> showExtractionFailureFallback(pageUrl));
            return;
        }

        final ReceiverParallelExtractionRun run =
                new ReceiverParallelExtractionRun(pageUrl, generation, tasks);

        // Each configured/self-hosted Cobalt endpoint gets its own worker.
        if (useCobalt) {
            if (endpoints.isEmpty()) {
                run.failed();
            } else {
                for (String endpoint : endpoints) {
                    Thread worker = new Thread(() -> {
                        try {
                            String result = resolveWithCobaltEndpoint(pageUrl, endpoint);
                            if (result != null && isHttpUrl(result)) {
                                run.candidate(result, "Cobalt", true, false);
                            } else {
                                run.failed();
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Parallel Cobalt resolver failed", e);
                            run.failed();
                        }
                    }, "CobaltResolver");
                    worker.setDaemon(true);
                    worker.start();
                }
            }
        }

        // Generic page metadata/content-type parsing runs independently.
        if (usePage) {
            Thread worker = new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(pageUrl);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
                    connection.setRequestProperty("Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                    int status = connection.getResponseCode();
                    if (status >= 200 && status < 400) {
                        String type = connection.getContentType();
                        if (type != null && (type.toLowerCase(Locale.ROOT).startsWith("video/")
                                || type.toLowerCase(Locale.ROOT).startsWith("audio/")
                                || type.toLowerCase(Locale.ROOT).contains("application/vnd.apple.mpegurl")
                                || type.toLowerCase(Locale.ROOT).contains("application/x-mpegurl")
                                || type.toLowerCase(Locale.ROOT).contains("application/dash+xml")
                                || type.toLowerCase(Locale.ROOT).contains("application/x-flv"))) {
                            run.candidate(connection.getURL().toString(), "Content-Type", false, true);
                            return;
                        }
                        StringBuilder html = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                            char[] buffer = new char[8192];
                            int count;
                            while ((count = reader.read(buffer)) != -1 && html.length() < 4 * 1024 * 1024) {
                                html.append(buffer, 0, count);
                            }
                        }
                        String result = extractDirectMediaFromHtml(html.toString(), pageUrl);
                        if (result != null && isDirectStreamUrl(result)) {
                            run.candidate(result, "Page Metadata", false, false);
                            return;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Parallel page extraction failed", e);
                } finally {
                    if (connection != null) connection.disconnect();
                }
                run.failed();
            }, "PageMetadataExtractor");
            worker.setDaemon(true);
            worker.start();
        }

        // Social-platform parsing is independent of the generic HTML request.
        if (useSocial) {
            FastMediaExtractor.extractDirectMedia(pageUrl, android.webkit.CookieManager.getInstance().getCookie(pageUrl), new FastMediaExtractor.ExtractorCallback() {
                @Override public void onSuccess(String mediaUrl, String platform) {
                    run.candidate(mediaUrl, "Social " + platform, false, false);
                }
                @Override public void onError(String reason) { run.failed(); }
            });
        }
    }

    private final class ReceiverParallelExtractionRun {
        private final String pageUrl;
        private final int generation;
        private int pending;
        private String bestUrl;
        private String bestEngine;
        private int bestScore = -1;
        private boolean finishScheduled;
        private boolean finished;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Runnable finishRunnable = this::finish;

        ReceiverParallelExtractionRun(String pageUrl, int generation, int pending) {
            this.pageUrl = pageUrl;
            this.generation = generation;
            this.pending = pending;
        }

        void candidate(String url, String engine, boolean cobaltResult, boolean contentTypeResult) {
            runOnUiThread(() -> {
                if (finished || generation != extractionGeneration || isFinishing()) return;
                if (!isHttpUrl(url) || pageUrl.equals(url)) {
                    failedOnMain();
                    return;
                }
                int score = receiverMediaQualityHintScore(url);
                if (cobaltResult) score = Math.max(score, 1080);
                if (bestUrl == null || score > bestScore) {
                    bestUrl = url;
                    bestEngine = engine;
                    bestScore = score;
                }
                if (!finishScheduled) {
                    finishScheduled = true;
                    handler.postDelayed(finishRunnable, 1100L);
                }
                if (pending <= 0) finish();
            });
        }

        void failed() { runOnUiThread(this::failedOnMain); }

        private void failedOnMain() {
            if (finished || generation != extractionGeneration || isFinishing()) return;
            if (pending > 0) pending--;
            if (pending == 0) finish();
        }

        private void finish() {
            if (finished || generation != extractionGeneration || isFinishing()) return;
            finished = true;
            handler.removeCallbacks(finishRunnable);
            extractionInProgress = false;
            extractionAttemptCount = Math.max(1, extractionAttemptCount);
            if (bestUrl != null) {
                extractionStatus = "resolved";
                persistExtractionState();
                runOnUiThread(() -> {
                    if (generation != extractionGeneration || isFinishing()) return;
                    if (receiverStatusText != null) {
                        receiverStatusText.setText("نجح " + bestEngine + "؛ جارٍ تشغيل أفضل رابط متاح");
                    }
                    playContent(bestUrl, null);
                });
            } else {
                extractionStatus = "web_fallback";
                extractionAttemptCount = pending;
                persistExtractionState();
                showExtractionFailureFallback(pageUrl);
            }
        }
    }

    private int receiverMediaQualityHintScore(String mediaUrl) {
        String value = mediaUrl == null ? "" : mediaUrl.toLowerCase(Locale.ROOT);
        int[] heights = {2160, 1440, 1080, 720, 540, 480, 360, 240};
        for (int height : heights) {
            String number = String.valueOf(height);
            if (value.contains(number + "p") || value.contains("height=" + number)
                    || value.contains("quality=" + number) || value.contains("res=" + number)) {
                return height;
            }
        }
        if (value.contains("quality=hd") || value.contains("high")) return 700;
        if (value.contains("quality=sd") || value.contains("medium")) return 400;
        return 0;
    }

    private void showExtractionFailureFallback(String pageUrl) {
        pendingBrowserUrl = pageUrl;
        showWelcome();
        if (browserShortcut != null) browserShortcut.setVisibility(View.VISIBLE);
        if (receiverStatusText != null) {
            receiverStatusText.setText("تعذّر استخراج بث مباشر؛ اضغط أيقونة المتصفح أسفل اليسار لو حابب تفتح صفحة الموقع");
        }
        Toast.makeText(MainActivity.this,
                "تعذّر استخراج فيديو مباشر للتشغيل. المتصفح متاح اختياريًا من أسفل اليسار.",
                Toast.LENGTH_LONG).show();
    }

    // Last-resort browser playback: show the page without browser chrome and honor
    // HTML5 video fullscreen requests. Existing ExoPlayer remains the first choice.
    private void showWebFallback(String pageUrl) {
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        if (!isHttpUrl(pageUrl)) {
            showWelcome();
            Toast.makeText(this, "الرابط غير صالح للتشغيل", Toast.LENGTH_LONG).show();
            return;
        }
        fallbackStreamCaptured = false;
        fallbackPageUrl = pageUrl;
        stopCurrentPlayer();
        hideImage();
        welcomeBackground.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);
        playerView.setVisibility(View.GONE);

        if (fallbackWebView == null) {
            fallbackWebView = new WebView(this);
            fallbackWebView.setBackgroundColor(Color.BLACK);
            WebSettings settings = fallbackWebView.getSettings();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                settings.setSafeBrowsingEnabled(true);
            }
            settings.setJavaScriptEnabled(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(true);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                settings.setAllowFileAccessFromFileURLs(false);
                settings.setAllowUniversalAccessFromFileURLs(false);
            }
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setLoadsImagesAutomatically(true);
            // TV-friendly layout: scale desktop-sized pages down to the screen and
            // use a readable but compact default zoom for D-pad navigation.
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            // Request the desktop website layout on TV instead of the narrow phone layout.
            // Keep this isolated to the fallback browser; media extraction/playback is unchanged.
            settings.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            // Keep the fallback browser compact on TV screens; users can still
            // zoom in with the remote when a particular site needs it.
            settings.setTextZoom(85);
            fallbackWebView.setInitialScale(75);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false);
            settings.setSupportMultipleWindows(false);
            CookieManager.getInstance().setAcceptCookie(true);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(fallbackWebView, true);
            }
            fallbackWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    android.net.Uri uri = request == null ? null : request.getUrl();
                    return uri != null
                            && getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).getBoolean("adblock_enabled", true)
                            && isKnownBrowserAdRequest(uri.toString());
                }

                @Override
                public android.webkit.WebResourceResponse shouldInterceptRequest(
                        WebView view, android.webkit.WebResourceRequest request) {
                    String candidate = request == null || request.getUrl() == null
                            ? "" : request.getUrl().toString();
                    // A page can expose its real media only after JavaScript/player
                    // startup. Watch subresource requests as well as static page HTML.
                    // Never intercept or replay the page URL itself.
                    if (!candidate.isEmpty()
                            && getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).getBoolean("adblock_enabled", true)
                            && isKnownBrowserAdRequest(candidate)) {
                        return new android.webkit.WebResourceResponse("text/plain", "UTF-8",
                                new java.io.ByteArrayInputStream(new byte[0]));
                    }
                    if (!candidate.isEmpty() && isDirectStreamUrl(candidate) && !isImageUrl(candidate)
                            && !candidate.equals(fallbackPageUrl) && !fallbackStreamCaptured) {
                        fallbackStreamCaptured = true;
                        runOnUiThread(() -> {
                            if (fallbackWebView != null
                                    && fallbackWebView.getVisibility() == View.VISIBLE
                                    && isDirectStreamUrl(candidate)) {
                                Log.i(TAG, "Captured public media request from fallback page");
                                extractionStatus = "stream_captured";
                                persistExtractionState();
                                playContent(candidate, null);
                            }
                        });
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onSafeBrowsingHit(WebView view, android.webkit.WebResourceRequest request,
                        int threatType, android.webkit.SafeBrowsingResponse response) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        response.backToSafety(true);
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "تم حظر موقع تحذر منه ميزة التصفح الآمن", Toast.LENGTH_LONG).show());
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    // Never keep a WebView whose renderer Android has already killed.
                    // Keep the failed URL so the user can retry from the optional browser shortcut.
                    String failedUrl = fallbackPageUrl;
                    Log.e(TAG, "Fallback browser renderer exited; didCrash="
                            + (detail != null && detail.didCrash()));
                    if (fallbackWebView == view) fallbackWebView = null;
                    fallbackStreamCaptured = false;
                    if (failedUrl != null && isHttpUrl(failedUrl)) pendingBrowserUrl = failedUrl;
                    runOnUiThread(() -> {
                        if (webFullscreenView != null) closeWebFullscreen();
                        if (!isFinishing()) {
                            showWelcome();
                            Toast.makeText(MainActivity.this,
                                    "أندرويد أوقف محرك صفحة الويب. رجّعنا الشاشة الرئيسية؛ اضغط المتصفح لإعادة المحاولة.",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    try { view.destroy(); } catch (Exception ignored) { }
                    return true;
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    // Some sites ship desktop-only layouts without a viewport meta tag.
                    // Apply a conservative TV fit without changing the page's login/session.
                    if (view != null && url != null && url.equals(fallbackPageUrl)) {
                        String fitScript = "(function(){try{"
                                + "var h=document.head||document.getElementsByTagName('head')[0];"
                                + "if(h&&!document.querySelector('meta[name=viewport]')){"
                                + "var m=document.createElement('meta');m.name='viewport';"
                                + "m.content='width=1280,initial-scale=0.8,maximum-scale=2,user-scalable=yes';h.appendChild(m);}"
                                + "document.documentElement.style.maxWidth='100%';"
                                + "document.documentElement.style.overflowX='auto';"
                                + "if(document.body){document.body.style.maxWidth='100%';"
                                + "document.body.style.overflowX='auto';}"
                                + "}catch(e){}})();";
                        view.evaluateJavascript(fitScript, null);
                    }
                }
            });
            fallbackWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onShowCustomView(View view, CustomViewCallback callback) {
                    if (webFullscreenView != null) {
                        callback.onCustomViewHidden();
                        return;
                    }
                    webFullscreenView = view;
                    webFullscreenCallback = callback;
                    fallbackWebView.setVisibility(View.GONE);
                    root.addView(webFullscreenView, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }

                @Override
                public void onHideCustomView() {
                    if (webFullscreenView == null) return;
                    root.removeView(webFullscreenView);
                    webFullscreenView = null;
                    if (webFullscreenCallback != null) {
                        webFullscreenCallback.onCustomViewHidden();
                        webFullscreenCallback = null;
                    }
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                    if (fallbackWebView != null) fallbackWebView.setVisibility(View.VISIBLE);
                }
            });
            root.addView(fallbackWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        } else if (fallbackWebView.getParent() == null) {
            root.addView(fallbackWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        fallbackWebView.setVisibility(View.VISIBLE);
        fallbackWebView.setFocusable(true);
        fallbackWebView.setFocusableInTouchMode(true);
        fallbackWebView.requestFocus(View.FOCUS_DOWN);
        fallbackWebView.loadUrl(pageUrl);
        Toast.makeText(this, "تعذّر استخراج رابط مباشر؛ جارٍ تجربة تشغيل صفحة الفيديو", Toast.LENGTH_LONG).show();
    }

    private boolean closeWebFullscreen() {
        if (webFullscreenView == null) return false;
        if (webFullscreenCallback != null) {
            webFullscreenCallback.onCustomViewHidden();
            webFullscreenCallback = null;
        }
        root.removeView(webFullscreenView);
        webFullscreenView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (fallbackWebView != null) fallbackWebView.setVisibility(View.VISIBLE);
        return true;
    }

    private boolean isHttpUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) {
            return false;
        }
    }

    // Use an explicitly configured Cobalt instance; never hardcode an untrusted public API.
    private String resolveWithConfiguredCobalt(String pageUrl) {
        String configured = getSharedPreferences("receiver_settings", MODE_PRIVATE)
                .getString("cobalt_endpoint", "").trim();
        if (configured.isEmpty()) return null;
        // Try authorized/self-hosted Cobalt endpoints in order until one succeeds.
        String[] endpoints = configured.split("[,;\\s]+");
        for (String endpoint : endpoints) {
            if (!isHttpUrl(endpoint)) continue;
            String resolved = resolveWithCobaltEndpoint(pageUrl, endpoint);
            if (resolved != null) return resolved;
        }
        return null;
    }

    private String resolveWithCobaltEndpoint(String pageUrl, String endpoint) {
        HttpURLConnection api = null;
        try {
            api = (HttpURLConnection) new URL(endpoint).openConnection();
            api.setRequestMethod("POST");
            api.setConnectTimeout(8000);
            api.setReadTimeout(20000);
            api.setDoOutput(true);
            api.setRequestProperty("Content-Type", "application/json");
            api.setRequestProperty("Accept", "application/json");
            JSONObject request = new JSONObject();
            request.put("url", pageUrl);
            request.put("downloadMode", "auto");
            request.put("videoQuality", "1080");
            try (OutputStream out = api.getOutputStream()) {
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = api.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "Cobalt API returned HTTP " + code);
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(api.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString());
            String state = response.optString("status", "");
            String mediaUrl = response.optString("url", "");
            if (("tunnel".equals(state) || "redirect".equals(state))
                    && isHttpUrl(mediaUrl)) {
                return mediaUrl;
            }

            // Cobalt can return a picker for posts containing several media items.
            // Prefer an explicit video entry; if the API omits item types, use the
            // first valid HTTP(S) item rather than discarding the entire post.
            if ("picker".equals(state)) {
                org.json.JSONArray picker = response.optJSONArray("picker");
                if (picker != null) {
                    String firstValid = null;
                    for (int i = 0; i < picker.length(); i++) {
                        JSONObject item = picker.optJSONObject(i);
                        if (item == null) continue;
                        String candidate = item.optString("url", "");
                        String type = item.optString("type", "").toLowerCase(Locale.ROOT);
                        if (!isHttpUrl(candidate)) continue;
                        if (type.contains("video")) return candidate;
                        if (type.isEmpty() && firstValid == null) firstValid = candidate;
                    }
                    if (firstValid != null) return firstValid;
                }
            }
            Log.w(TAG, "Cobalt response is not a direct media URL; status=" + state);
        } catch (Exception e) {
            Log.w(TAG, "Cobalt API request failed", e);
        } finally {
            if (api != null) api.disconnect();
        }
        return null;
    }

    // natively play media URLs; resolve page URLs to a direct stream instead of opening another app.
    private void queueIncomingMedia(String mediaUrl, String audioUrl) {
        queueIncomingMedia(mediaUrl, audioUrl, java.util.Collections.emptyMap());
    }

    private void queueIncomingMedia(String mediaUrl, String audioUrl, java.util.Map<String, String> headers) {
        pendingBrowserUrl = "";
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        // The receiver is reachable on the local network, so accept only remote HTTP(S)
        // URLs from incoming requests; never pass file/content/javascript or other schemes
        // to ExoPlayer or the social-video resolver.
        String originalMediaUrl = mediaUrl;
        String originalAudioUrl = audioUrl;
        pendingPlaybackHeaders = headers == null ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(headers);
        if (mediaUrl != null && !mediaUrl.trim().isEmpty() && !isHttpUrl(mediaUrl)) {
            mediaUrl = null;
        }
        if (audioUrl != null && !audioUrl.trim().isEmpty() && !isHttpUrl(audioUrl)) {
            audioUrl = null;
        }
        // YouTube page links should be handed to the TV's YouTube-compatible app,
        // not treated as a media URL for ExoPlayer. Direct raw stream URLs still play below.
        if (mediaUrl != null && isYouTubeUrl(mediaUrl)) {
            if (audioUrl != null && !audioUrl.trim().isEmpty()) {
                Log.i(TAG, "YouTube page link takes precedence over separate audio URL");
            }
            openYouTubeInTvApp(mediaUrl);
            return;
        }

        boolean hasMedia = mediaUrl != null && !mediaUrl.trim().isEmpty();
        boolean hasAudio = audioUrl != null && !audioUrl.trim().isEmpty();
        if (!hasMedia && !hasAudio) {
            if ((originalMediaUrl != null && !originalMediaUrl.trim().isEmpty())
                    || (originalAudioUrl != null && !originalAudioUrl.trim().isEmpty())) {
                Toast.makeText(this, "رابط غير مدعوم؛ استخدم رابط HTTP أو HTTPS صالحًا.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (mediaUrl != null && isDirectStreamUrl(mediaUrl)) {
            extractionInProgress = false;
            extractionAttemptCount = 0;
            extractionStatus = "direct_playback";
            persistExtractionState();
        }
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()
                && !isImageUrl(mediaUrl) && !isDirectStreamUrl(mediaUrl)
                && (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))) {
            if (pendingPlayback != null) {
                mediaHandler.removeCallbacks(pendingPlayback);
                pendingPlayback = null;
            }
            pendingVisualUrl = null;
            pendingAudioUrl = null;
            resolveSharedVideoToStream(mediaUrl);
            return;
        }

        if (mediaUrl != null && !mediaUrl.trim().isEmpty()) pendingVisualUrl = mediaUrl;
        if (audioUrl != null && !audioUrl.trim().isEmpty()) pendingAudioUrl = audioUrl;

        if (pendingPlayback != null) mediaHandler.removeCallbacks(pendingPlayback);
        pendingPlayback = () -> {
            String visual = pendingVisualUrl;
            String audio = pendingAudioUrl;
            pendingVisualUrl = null;
            pendingAudioUrl = null;
            pendingPlayback = null;
            playContent(visual, audio, pendingPlaybackHeaders);
            pendingPlaybackHeaders = java.util.Collections.emptyMap();
        };
        mediaHandler.postDelayed(pendingPlayback, 150);
    }

    private void openYouTubeInTvApp(String videoUrl) {
        // Let Android itself decide which installed app can handle this YouTube URL.
        // Do not force SmartTube/YouTube packages or display an app picker implemented by Receiver.
        try {
            android.content.Intent target = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(videoUrl));
            target.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
            target.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);

            // Start the implicit ACTION_VIEW directly. Wrapping it in
            // Intent.createChooser() forces the chooser-only UI and removes the
            // resolver's "Just once" / "Always" default-handler choices on Android TV.
            // A direct implicit intent lets Android/Fire OS show its native resolver
            // whenever multiple apps can handle this URL and no default is set.
            startActivity(target);
        } catch (Exception e) {
            Log.w(TAG, "Android could not show the system app chooser for YouTube", e);
            Toast.makeText(this,
                    "تعذّر فتح قائمة تطبيقات أندرويد. تأكد من وجود تطبيق يدعم رابط YouTube.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private boolean isImageUrl(String url) {
        if (url == null) {
            return false;
        }

        try {
            String path = Uri.parse(url).getPath();

            if (path == null) {
                return false;
            }

            path = path.toLowerCase(Locale.ROOT);

            String lowerUrl = url.toLowerCase(Locale.ROOT);
            String decodedUrl = Uri.decode(lowerUrl);
            String query = Uri.parse(url).getQuery();
            String lowerQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
            boolean imageMimeParameter = decodedUrl.contains("media_type=image/")
                    || decodedUrl.contains("mime=image/")
                    || lowerQuery.contains("media_type=image/")
                    || lowerQuery.contains("mime=image/");
            return path.endsWith(".jpg")
                    || path.endsWith(".jpeg")
                    || path.endsWith(".png")
                    || path.endsWith(".webp")
                    || path.endsWith(".gif")
                    || path.endsWith(".bmp")
                    || path.endsWith(".avif")
                    || path.endsWith(".heic")
                    || path.endsWith(".heif")
                    || path.endsWith(".tif")
                    || path.endsWith(".tiff")
                    || lowerQuery.matches(".*(?:format|fm|type)=(?:jpg|jpeg|png|webp|gif|bmp|avif|heic|heif)(?:&.*)?")
                    || imageMimeParameter
                    || lowerUrl.matches(".*[?&](?:media_type|mime)=image%2f[a-z0-9.+-]+.*");

        } catch (Exception e) {
            return false;
        }
    }

    private boolean handleImageRemoteKey(int keyCode) {
        if (imageView == null || imageView.getVisibility() != View.VISIBLE) return false;
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                if (imageZoomScale > 1.0f) resetImageTransform();
                else { imageZoomScale = 2.0f; applyImageTransform(); }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                if (imageZoomScale > 1.0f) { imagePanY += dp(35); applyImageTransform(); }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                if (imageZoomScale > 1.0f) { imagePanY -= dp(35); applyImageTransform(); }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                if (imageZoomScale > 1.0f) { imagePanX += dp(35); applyImageTransform(); }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                if (imageZoomScale > 1.0f) { imagePanX -= dp(35); applyImageTransform(); }
                return true;
            case android.view.KeyEvent.KEYCODE_BACK:
            case android.view.KeyEvent.KEYCODE_ESCAPE:
                resetImageTransform();
                hideImage();
                showWelcome();
                return true;
            default:
                return false;
        }
    }

    private void applyImageTransform() {
        if (imageView == null) return;
        imageView.setScaleX(imageZoomScale);
        imageView.setScaleY(imageZoomScale);
        imageView.setTranslationX(imagePanX);
        imageView.setTranslationY(imagePanY);
    }

    private void resetImageTransform() {
        imageZoomScale = 1.0f;
        imagePanX = 0f;
        imagePanY = 0f;
        applyImageTransform();
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && handleImageRemoteKey(event.getKeyCode())) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleRemoteCommand(String command) {
        synchronized (playerLock) {
            if (player == null) {
                // If playback is happening in another TV app (for example SmartTube),
                // route media keys through Android's active media-session system.
                // Volume is handled at system level as well, because ExoPlayer may not exist.
                android.media.AudioManager audioManager =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if ("volume_up".equals(command) || "volume_down".equals(command) || "mute".equals(command)) {
                    if (audioManager != null) {
                        if ("volume_up".equals(command)) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_RAISE,
                                    android.media.AudioManager.FLAG_SHOW_UI | android.media.AudioManager.FLAG_PLAY_SOUND);
                        } else if ("volume_down".equals(command)) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_LOWER,
                                    android.media.AudioManager.FLAG_SHOW_UI | android.media.AudioManager.FLAG_PLAY_SOUND);
                        } else {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                                    android.media.AudioManager.FLAG_SHOW_UI | android.media.AudioManager.FLAG_PLAY_SOUND);
                        }
                    }
                    return;
                }
                int keyCode;
                switch (command) {
                    case "play_pause":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                        break;
                    case "rw":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
                        break;
                    case "ff":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
                        break;
                    case "stop":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_STOP;
                        break;
                    case "previous":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                        break;
                    case "next":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_NEXT;
                        break;
                    default:
                        Log.w(TAG, "Unsupported remote command with no local player: " + command);
                        return;
                }
                if (audioManager != null) {
                    long now = android.os.SystemClock.uptimeMillis();
                    android.view.KeyEvent down = new android.view.KeyEvent(
                            now, now, android.view.KeyEvent.ACTION_DOWN, keyCode, 0);
                    android.view.KeyEvent up = new android.view.KeyEvent(
                            now, now, android.view.KeyEvent.ACTION_UP, keyCode, 0);
                    audioManager.dispatchMediaKeyEvent(down);
                    audioManager.dispatchMediaKeyEvent(up);
                    Log.i(TAG, "Dispatched remote media key to Android media session: " + command);
                } else {
                    Log.w(TAG, "No AudioManager available for remote command: " + command);
                }
                return;
            }
            switch (command) {
                case "play_pause":
                    if (player.isPlaying()) player.pause(); else player.play();
                    break;
                case "rw":
                    player.seekTo(Math.max(0L, player.getCurrentPosition() - 10000L));
                    break;
                case "ff":
                    long duration = player.getDuration();
                    long target = player.getCurrentPosition() + 10000L;
                    player.seekTo(duration > 0 ? Math.min(target, duration) : target);
                    break;
                case "stop":
                    savePlaybackPositionLocked();
                    player.stop();
                    player.clearMediaItems();
                    break;
                case "previous":
                    playQueueRelative(-1);
                    break;
                case "next":
                    playQueueRelative(1);
                    break;
                case "volume_up":
                    lastNonZeroVolume = Math.min(1.0f, lastNonZeroVolume + 0.1f);
                    if (!muted) player.setVolume(lastNonZeroVolume);
                    break;
                case "volume_down":
                    lastNonZeroVolume = Math.max(0.0f, lastNonZeroVolume - 0.1f);
                    if (!muted) player.setVolume(lastNonZeroVolume);
                    break;
                case "mute":
                    muted = !muted;
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    break;
                default:
                    Log.w(TAG, "Unsupported remote command: " + command);
                    break;
            }
        }
    }

    private void stopCurrentPlayer() {
        synchronized (playerLock) {
            if (player != null) {
                savePlaybackPositionLocked();
                player.stop();
                player.clearMediaItems();
            }
        }
        // Preserve the last saved position; do not overwrite it with position zero on shutdown.
        currentPlaybackUrl = "";
    }

    private void rememberPlaybackUrl(String url) {
        if (url == null || url.trim().isEmpty()) return;
        if (playbackQueueIndex >= 0 && playbackQueueIndex < playbackQueue.size()
                && url.equals(playbackQueue.get(playbackQueueIndex))) return;
        if (playbackQueueIndex >= 0 && playbackQueueIndex < playbackQueue.size() - 1) {
            playbackQueue.subList(playbackQueueIndex + 1, playbackQueue.size()).clear();
        }
        if (!playbackQueue.isEmpty() && playbackQueue.get(playbackQueue.size() - 1).equals(url)) {
            playbackQueueIndex = playbackQueue.size() - 1;
            return;
        }
        playbackQueue.add(url);
        playbackQueueIndex = playbackQueue.size() - 1;
        // Persist a bounded history so recent items survive app restarts.
        if (playbackQueue.size() > 100) {
            playbackQueue.remove(0);
            playbackQueueIndex = Math.max(0, playbackQueueIndex - 1);
        }
        savePlaybackHistory();
    }

    private void loadPlaybackHistory() {
        if (playbackPrefs == null) return;
        String raw = playbackPrefs.getString("recent_playback_urls", "[]");
        try {
            org.json.JSONArray array = new org.json.JSONArray(raw);
            playbackQueue.clear();
            for (int i = 0; i < array.length() && playbackQueue.size() < 100; i++) {
                String url = array.optString(i, "");
                if (isHttpUrl(url) && !playbackQueue.contains(url)) playbackQueue.add(url);
            }
            playbackQueueIndex = playbackQueue.size() - 1;
        } catch (Exception e) {
            Log.w(TAG, "Unable to load playback history", e);
            playbackQueue.clear();
            playbackQueueIndex = -1;
        }
    }

    private void savePlaybackHistory() {
        if (playbackPrefs == null) return;
        org.json.JSONArray array = new org.json.JSONArray();
        for (String url : playbackQueue) array.put(url);
        playbackPrefs.edit().putString("recent_playback_urls", array.toString()).apply();
    }

    private void showPlaybackHistory() {
        if (playbackQueue.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("سجل المشاهدة")
                    .setMessage("لسه مفيش روابط محفوظة. الروابط اللي تشغّلها هتظهر هنا.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        java.util.ArrayList<String> recent = new java.util.ArrayList<>();
        for (int i = playbackQueue.size() - 1; i >= 0; i--) recent.add(playbackQueue.get(i));
        String[] labels = new String[recent.size()];
        for (int i = 0; i < recent.size(); i++) {
            String url = recent.get(i);
            try {
                Uri uri = Uri.parse(url);
                String host = uri.getHost() == null ? url : uri.getHost();
                String path = uri.getPath() == null ? "" : uri.getPath();
                labels[i] = host + path + "\n" + url;
            } catch (Exception e) { labels[i] = url; }
        }
        new AlertDialog.Builder(this).setTitle("سجل المشاهدة — الأحدث أولًا")
                .setItems(labels, (dialog, which) -> {
                    String selected = recent.get(which);
                    clearPendingServerMedia();
                    queueIncomingMedia(selected, null);
                })
                .setNeutralButton("مسح السجل", (dialog, which) -> new AlertDialog.Builder(this)
                        .setTitle("مسح سجل المشاهدة؟")
                        .setMessage("سيتم حذف الروابط المحفوظة من هذا الجهاز.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("مسح", (d, w) -> {
                            playbackQueue.clear();
                            playbackQueueIndex = -1;
                            savePlaybackHistory();
                            Toast.makeText(this, "تم مسح سجل المشاهدة", Toast.LENGTH_SHORT).show();
                        }).show())
                .setNegativeButton("إغلاق", null).show();
    }

    private void playQueueRelative(int delta) {
        if (playbackQueue.isEmpty()) {
            Toast.makeText(this, "مفيش فيديو سابق أو تالي في سجل التشغيل", Toast.LENGTH_SHORT).show();
            return;
        }
        int target = playbackQueueIndex + delta;
        if (target < 0 || target >= playbackQueue.size()) {
            Toast.makeText(this, delta < 0 ? "ده أول فيديو في سجل التشغيل" : "ده آخر فيديو في سجل التشغيل",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        playbackQueueIndex = target;
        String url = playbackQueue.get(target);
        queueIncomingMedia(url, null);
    }

    private void playContent(String mediaUrl, String audioUrl) {
        playContent(mediaUrl, audioUrl, java.util.Collections.emptyMap());
    }

    private void playContent(String mediaUrl, String audioUrl, java.util.Map<String, String> requestHeaders) {

        if (mediaUrl != null
                && !mediaUrl.trim().isEmpty()
                && isImageUrl(mediaUrl)) {

            showImage(mediaUrl);
            if (audioUrl != null && !audioUrl.trim().isEmpty()) {
                playAudioAlongsideImage(audioUrl);
            }
            return;
        }

        String playbackUrl = mediaUrl;

        // طلب صوت فقط.
        if (playbackUrl == null
                || playbackUrl.trim().isEmpty()) {

            playbackUrl = audioUrl;
            audioUrl = null;
        }

        if (playbackUrl == null
                || playbackUrl.trim().isEmpty()) {
            return;
        }

        currentPlaybackUrl = playbackUrl;
        rememberPlaybackUrl(playbackUrl);
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        hideImage();

        if (fallbackWebView != null) fallbackWebView.setVisibility(View.GONE);
        if (webFullscreenView != null) closeWebFullscreen();
        welcomeBackground.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);

        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    player.addListener(new Player.Listener() {
                        @Override
                        public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                            // Keep rotation metadata from the stream and let PlayerView fit
                            // the rotated aspect ratio rather than forcing landscape/portrait.
                            Log.d(TAG, "Video size=" + videoSize.width + "x" + videoSize.height
                                    + ", rotation=" + videoSize.unappliedRotationDegrees);
                            if (playerView != null) {
                                playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
                            }
                        }

                        @Override
                        public void onPlaybackStateChanged(int playbackState) {
                            if (playbackState != Player.STATE_ENDED
                                    || playbackPrefs == null
                                    || player == null
                                    || player.getCurrentMediaItem() == null
                                    || player.getCurrentMediaItem().localConfiguration == null) {
                                return;
                            }
                            // Use the URL owned by this ended media item, not the mutable
                            // global currentPlaybackUrl, which may already refer to a new request.
                            String endedUrl = player.getCurrentMediaItem()
                                    .localConfiguration.uri.toString();
                            if (endedUrl != null && !endedUrl.isEmpty()) {
                                playbackPrefs.edit()
                                        .remove("position_" + endedUrl.hashCode())
                                        .apply();
                            }
                        }
                    });
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    playerView.setPlayer(player);
                }

                savePlaybackPositionLocked();
                player.stop();
                player.clearMediaItems();

                if (audioUrl != null
                        && !audioUrl.trim().isEmpty()) {

                    DefaultMediaSourceFactory factory = createMediaSourceFactory(requestHeaders);

                    MediaSource videoSource =
                            factory.createMediaSource(
                                    MediaItem.fromUri(playbackUrl)
                            );

                    MediaSource audioSource =
                            factory.createMediaSource(
                                    MediaItem.fromUri(audioUrl)
                            );

                    MergingMediaSource merged =
                            new MergingMediaSource(
                                    true,
                                    videoSource,
                                    audioSource
                            );

                    player.setMediaSource(merged);

                } else {
                    player.setMediaSource(createMediaSourceFactory(requestHeaders)
                            .createMediaSource(MediaItem.fromUri(playbackUrl)));
                }

                player.prepare();
                long savedPosition = playbackPrefs == null ? 0L
                        : playbackPrefs.getLong("position_" + playbackUrl.hashCode(), 0L);
                if (savedPosition > 5000L) {
                    player.seekTo(savedPosition);
                    Toast.makeText(MainActivity.this, "تم استئناف التشغيل من آخر موضع محفوظ", Toast.LENGTH_SHORT).show();
                }
                player.play();
            }

        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
            showWelcome();
        }
    }

    private DefaultMediaSourceFactory createMediaSourceFactory(java.util.Map<String, String> requestHeaders) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000);
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            java.util.Map<String, String> safe = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (("user-agent".equals(key) || "referer".equals(key) || "origin".equals(key)
                        || "accept".equals(key) || "accept-language".equals(key))
                        && entry.getValue() != null && entry.getValue().length() <= 2048
                        && !entry.getValue().contains("\\r") && !entry.getValue().contains("\\n")) {
                    safe.put(entry.getKey(), entry.getValue());
                }
            }
            httpFactory.setDefaultRequestProperties(safe);
        }
        return new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, httpFactory));
    }

    // الصورة تظل ظاهرة بينما يشغّل ExoPlayer الصوت في الخلفية.
    private void playAudioAlongsideImage(String audioUrl) {
        currentPlaybackUrl = audioUrl;
        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    playerView.setPlayer(player);
                }
                savePlaybackPositionLocked();
                player.stop();
                player.clearMediaItems();
                player.setMediaItem(MediaItem.fromUri(audioUrl));
                player.prepare();
                long savedPosition = playbackPrefs == null ? 0L
                        : playbackPrefs.getLong("position_" + audioUrl.hashCode(), 0L);
                if (savedPosition > 5000L) player.seekTo(savedPosition);
                player.play();
                playerView.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio with image playback error", e);
            showWelcome();
        }
    }

    // Conservative host-based ad filter for the optional fallback browser.
    // It targets known ad/analytics domains only and avoids generic media/CDN hosts.
    private boolean isKnownBrowserAdRequest(String requestUrl) {
        try {
            android.net.Uri uri = android.net.Uri.parse(requestUrl);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.ROOT);
            String[] domains = {
                    "doubleclick.net", "googlesyndication.com", "adservice.google.com",
                    "googleadservices.com", "adnxs.com", "criteo.com", "outbrain.com",
                    "taboola.com", "popads.net", "popcash.net", "propellerads.com",
                    "exoclick.com", "smartadserver.com", "scorecardresearch.com",
                    "zedo.com", "adroll.com", "pubmatic.com", "rubiconproject.com",
                    "adsrvr.org", "advertising.com", "adform.net", "amazon-adsystem.com",
                    "bidswitch.net", "casalemedia.com", "contextweb.com", "demdex.net",
                    "everesttech.net", "flashtalking.com", "lijit.com", "media.net",
                    "moatads.com", "openx.net", "quantserve.com", "rlcdn.com",
                    "sharethrough.com", "simpli.fi", "teads.tv", "thetradedesk.com",
                    "triplelift.com", "undertone.com", "yieldmo.com", "33across.com",
                    "adsterra.com", "trafficstars.com", "hilltopads.net", "adcash.com",
                    "clickadu.com", "admaven.com", "monetag.com", "mgid.com",
                    "revcontent.com", "trafficjunky.net", "juicyads.com", "exdynsrv.com"
            };
            for (String domain : domains) if (host.equals(domain) || host.endsWith("." + domain)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    private void showImage(String imageUrl) {
        stopCurrentPlayer();
        resetImageTransform();

        playerView.setVisibility(View.GONE);
        if (fallbackWebView != null) fallbackWebView.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);

        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        imageLoading.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);

        Thread imageThread = new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                // Read dimensions first, then decode near the actual display resolution.
                // This avoids allocating hundreds of megabytes for 4K/8K phone photos.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                connection.connect();
                int response = connection.getResponseCode();
                if (response < 200 || response >= 300) {
                    throw new java.io.IOException("Image server returned HTTP " + response);
                }
                try (java.io.InputStream input = connection.getInputStream()) {
                    BitmapFactory.decodeStream(input, null, bounds);
                } finally {
                    connection.disconnect();
                    connection = null;
                }

                int targetWidth = Math.max(1, getResources().getDisplayMetrics().widthPixels);
                int targetHeight = Math.max(1, getResources().getDisplayMetrics().heightPixels);
                int sample = 1;
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    while ((bounds.outWidth / (sample * 2)) >= targetWidth
                            && (bounds.outHeight / (sample * 2)) >= targetHeight) {
                        sample *= 2;
                    }
                }

                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = sample;
                decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
                decode.inScaled = false;
                connection = (HttpURLConnection) new URL(imageUrl).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
                connection.connect();
                int imageResponse = connection.getResponseCode();
                if (imageResponse < 200 || imageResponse >= 300) {
                    throw new java.io.IOException("Image download returned HTTP " + imageResponse);
                }
                try (java.io.InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input, null, decode);
                }
            } catch (Exception e) {
                Log.e(TAG, "Image loading error for " + imageUrl, e);
            } finally {
                if (connection != null) connection.disconnect();
            }

            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                imageLoading.setVisibility(View.GONE);
                if (result != null && !isFinishing()) {
                    imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    imageView.setImageBitmap(result);
                    imageView.setVisibility(View.VISIBLE);
                } else {
                    showWelcome();
                    Toast.makeText(MainActivity.this,
                            "تعذّر تحميل الصورة من Share. تأكد أن الجهازين على نفس شبكة Wi-Fi وأن Share ما زال مفتوحًا.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "ReceiverImageLoader");
        imageThread.setDaemon(true);
        imageThread.start();
    }

    private void hideImage() {
        resetImageTransform();
        imageView.setVisibility(View.GONE);
        imageLoading.setVisibility(View.GONE);
        imageView.setImageDrawable(null);
    }

    private void showWelcome() {
        // Returning home must stop playback, not merely hide the player UI.
        stopCurrentPlayer();
        hideImage();
        if (webFullscreenView != null) closeWebFullscreen();
        if (fallbackWebView != null) {
            fallbackWebView.stopLoading();
            fallbackWebView.setVisibility(View.GONE);
        }
        playerView.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.VISIBLE);
        connectionCard.setVisibility(View.VISIBLE);
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.VISIBLE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.VISIBLE);

        updateConnectionInfo();
    }

    @Override
    public void onBackPressed() {
        if (closeWebFullscreen()) return;

        boolean browserVisible = fallbackWebView != null
                && fallbackWebView.getVisibility() == View.VISIBLE;
        if (browserVisible && backPressedOnceInBrowser) {
            backPressHandler.removeCallbacks(resetBrowserBackPress);
            backPressedOnceInBrowser = false;
            showWelcome();
            return;
        }
        if (browserVisible && fallbackWebView.canGoBack()) {
            backPressedOnceInBrowser = true;
            backPressHandler.removeCallbacks(resetBrowserBackPress);
            backPressHandler.postDelayed(resetBrowserBackPress, 1800L);
            fallbackWebView.goBack();
            Toast.makeText(this, "اضغط رجوع مرة ثانية بسرعة للعودة للرئيسية", Toast.LENGTH_SHORT).show();
            return;
        }

        // One Back from playback/image/browser-without-history returns to the app home
        // and stops media or web loading so audio/video cannot continue behind the home UI.
        backPressHandler.removeCallbacks(resetBrowserBackPress);
        backPressedOnceInBrowser = false;
        showWelcome();
    }

    @Override
    protected void onDestroy() {
        backPressHandler.removeCallbacks(resetBrowserBackPress);
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable);
        synchronized (playerLock) {
            savePlaybackPositionLocked();
        }
        running = false;
        heartbeatRunning = false;
        if (pendingPlayback != null) {
            mediaHandler.removeCallbacks(pendingPlayback);
            pendingPlayback = null;
        }

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Server close error", e);
        }

        if (fallbackWebView != null) {
            fallbackWebView.stopLoading();
            fallbackWebView.destroy();
            fallbackWebView = null;
        }
        synchronized (playerLock) {
            if (player != null) {
                player.release();
                player = null;
            }
        }

        super.onDestroy();
    }
}
