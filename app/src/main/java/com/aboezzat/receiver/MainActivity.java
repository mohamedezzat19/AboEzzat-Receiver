
package com.aboezzat.receiver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
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
import android.net.wifi.WifiManager;
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
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
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
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
    private volatile String playbackStatusMessage = "";
    private volatile java.util.Map<String, String> pendingPlaybackHeaders = java.util.Collections.emptyMap();

    private FrameLayout root;
    private LinearLayout welcomeScreen;

    private ImageView welcomeBackground;
    // Persistent circular brand portrait: uses the standalone app icon artwork.
    private FrameLayout persistentBrandBadge;
    private LinearLayout connectionCard;
    private TextView ipText;
    private TextView receiverStatusText;

    private PlayerView playerView;
    private TextView playerSubtitleButton;
    private TextView playerSettingsButton;
    private boolean currentPlaybackHasVideo = false;
    private String currentPlaybackAudioUrl = "";
    private java.util.Map<String, String> currentPlaybackHeaders = java.util.Collections.emptyMap();
    private final java.util.ArrayList<String> playbackQueue = new java.util.ArrayList<>();
    private int playbackQueueIndex = -1;
    private WebView fallbackWebView;
    // Full TV browser chrome, separate from media-fallback playback.
    private FrameLayout browserOverlay;
    private EditText browserAddress;
    private ProgressBar browserProgress;
    private FrameLayout browserHomePanel;
    private LinearLayout browserBookmarksContainer;
    private static final int REQUEST_BROWSER_VOICE = 7421;
    private boolean tvBrowserMode = false;
    private WebView pendingPopupWebView;
    private WebView activePopupWebView;
    private AlertDialog popupRequestDialog;
    private final Handler popupHandler = new Handler(Looper.getMainLooper());
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
    private boolean playerControllerVisible = false;

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered = false;

    private final Object playerLock = new Object();
    private SharedPreferences playbackPrefs;
    private String currentPlaybackUrl = "";
    private String currentPlaybackFallbackUrl = "";
    private boolean smartTubeFallbackAttempted = false;
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

        // Check for a newer Receiver release automatically when launched from its icon.
        // Do not interrupt media arriving through Android's share/view intents.
        String launchAction = getIntent() == null ? null : getIntent().getAction();
        if (launchAction == null || android.content.Intent.ACTION_MAIN.equals(launchAction)) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    new AppUpdater(this).checkForUpdate(false);
                }
            }, 700L);
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }


    // User-controlled opt-in: Fire OS/Android may block a background Activity launch.
    // SYSTEM_ALERT_WINDOW is an Android BAL exemption on many versions; OEM behavior can vary.
    private void configureAutoForegroundPlayback() {
        if (android.os.Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "الإذن غير مطلوب على هذا الإصدار", Toast.LENGTH_LONG).show();
            return;
        }

        if (android.provider.Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("التشغيل التلقائي")
                    .setMessage("الإذن مفعّل. سيحاول Receiver فتح الفيديو تلقائيًا عند وصول رابط وهو في الخلفية. قد يظل Fire OS يمنع ذلك في بعض الحالات.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("السماح بالتشغيل التلقائي")
                .setMessage("Fire OS قد يمنع Receiver من فتح الفيديو فوق التطبيق الحالي. افتح إعدادات النظام واسمح لـ AboEzzat Receiver بخيار «الظهور فوق التطبيقات الأخرى». هذا إذن اختياري، ولا يضمنه كل إصدار من Fire OS.")
                .setPositiveButton("فتح إعدادات الإذن", (dialog, which) -> {
                    try {
                        android.content.Intent intent = new android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        try {
                            android.content.Intent fallback = new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + getPackageName()));
                            startActivity(fallback);
                        } catch (Exception ignored) {
                            Toast.makeText(this, "افتح إعدادات التطبيق يدويًا من إعدادات Fire TV", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("لاحقًا", null)
                .show();
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
                "🚀  فتح الفيديو تلقائيًا: " + ((android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) ? "مسموح" : "يحتاج إذن")
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
                        case 7: configureAutoForegroundPlayback(); break;
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
        // استخدم نفس دالة IP الخاصة بالشاشة والإعلان حتى لا تختلف العناوين.
        String ip = getCurrentIpAddress();
        if (ip == null || ip.trim().isEmpty()) ip = "غير معروف";

        int port = getSharedPreferences("receiver_settings", MODE_PRIVATE)
                .getInt("receiver_port", 8081);

        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        boolean networkConnected = false;
        if (cm != null) {
            Network[] networks = cm.getAllNetworks();
            for (Network network : networks) {
                android.net.NetworkCapabilities caps =
                        cm.getNetworkCapabilities(network);
                if (caps != null
                        && !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                        && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    networkConnected = true;
                    break;
                }
            }
        }

        String details = "عنوان IP: " + ip + "\nبورت الاستقبال: " + port
                + "\nالشبكة: " + (networkConnected ? "متصل" : "غير متصل أو لم تُكتشف")
                + "\nإصدار أندرويد: " + android.os.Build.VERSION.RELEASE
                + "\nالجهاز: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + "\n\nلو Share لا يتصل، تأكد أن الجهازين على نفس الشبكة وأن البورت متطابق في التطبيقين.";
        new AlertDialog.Builder(this).setTitle("تشخيص الاتصال")
                .setMessage(details)
                .setPositiveButton("نسخ المعلومات", (d,w) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
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
        new AppUpdater(this).checkForUpdate(true);
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
            java.util.Map<String, String> incomingHeaders = new java.util.LinkedHashMap<>();
            try {
                JSONObject headerJson = new JSONObject(intent.getStringExtra("server_headers") == null
                        ? "{}" : intent.getStringExtra("server_headers"));
                java.util.Iterator<String> keys = headerJson.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    String value = headerJson.optString(key, "");
                    if (!value.isEmpty()) incomingHeaders.put(key, value);
                }
            } catch (Exception ignored) { }
            if (command != null && !command.trim().isEmpty()) {
                handleRemoteCommand(command.trim());
            } else {
                clearPendingServerMedia();
                queueIncomingMedia(incomingUrl, incomingAudio, incomingHeaders);
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

    private GradientDrawable browserFocusBackground(int fillColor, int strokeColor,
            int radius, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fillColor);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(focused ? 2 : 1), strokeColor);
        return background;
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

    private void addPersistentBrandBadge() {
        if (root == null || persistentBrandBadge != null) return;

        FrameLayout badge = new FrameLayout(this);
        android.graphics.drawable.GradientDrawable frame =
                new android.graphics.drawable.GradientDrawable();
        frame.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        frame.setColor(Color.rgb(7, 16, 34));
        frame.setStroke(dp(3), Color.rgb(255, 197, 74));
        badge.setBackground(frame);
        badge.setPadding(dp(4), dp(4), dp(4), dp(4));
        badge.setElevation(dp(14));
        if (android.os.Build.VERSION.SDK_INT >= 21) badge.setClipToOutline(true);
        badge.setContentDescription("صورة AboEzzat Receiver");
        badge.setFocusable(false);
        badge.setClickable(false);

        ImageView portrait = new ImageView(this);
        // Use the standalone app icon artwork, not the welcome image containing text.
        portrait.setImageResource(R.drawable.app_icon);
        portrait.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable circularClip =
                new android.graphics.drawable.GradientDrawable();
        circularClip.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circularClip.setColor(Color.TRANSPARENT);
        portrait.setBackground(circularClip);
        if (android.os.Build.VERSION.SDK_INT >= 21) portrait.setClipToOutline(true);
        badge.addView(portrait, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(64), dp(64),
                Gravity.TOP | Gravity.LEFT);
        params.setMargins(dp(14), dp(12), 0, 0);
        root.addView(badge, params);
        persistentBrandBadge = badge;
        keepPersistentBrandBadgeOnTop();
    }

    private void keepPersistentBrandBadgeOnTop() {
        if (persistentBrandBadge != null && root != null) {
            persistentBrandBadge.setVisibility(View.VISIBLE);
            persistentBrandBadge.bringToFront();
        }
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

        ipText.setTextIsSelectable(false);
        ipText.setFocusable(false);
        ipText.setClickable(false);
        ipText.setLongClickable(false);
        ipText.setContentDescription("عنوان IP الخاص بالريسيفر للعرض فقط");

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

        portText.setTextIsSelectable(false);
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

        // بطاقة معلومات الاتصال ظاهرة أسفل اليمين للقراءة فقط دون أي تفاعل.
        connectionCard.setFocusable(false);
        connectionCard.setClickable(false);
        connectionCard.setLongClickable(false);
        ipText.setFocusable(false);
        ipText.setClickable(false);
        ipText.setLongClickable(false);
        ipText.setTextIsSelectable(false);
        ipText.setContentDescription("عنوان IP الخاص بالريسيفر للعرض فقط");
        portText.setFocusable(false);
        portText.setClickable(false);
        portText.setLongClickable(false);
        portText.setTextIsSelectable(false);
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
            homeButton.setBackground(browserFocusBackground(Color.rgb(20, 34, 61), Color.rgb(86, 132, 255), 14, false));
            homeButton.setOnFocusChangeListener((v, focused) -> {
                v.setScaleX(focused ? 1.12f : 1f);
                v.setScaleY(focused ? 1.12f : 1f);
                v.setElevation(focused ? dp(12) : dp(3));
                v.setBackground(browserFocusBackground(
                        focused ? Color.rgb(43, 66, 108) : Color.rgb(20, 34, 61),
                        focused ? Color.rgb(255, 212, 0) : Color.rgb(86, 132, 255), 14, focused));
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
        playerView.setResizeMode(getPlayerSettingsPrefs().getInt("resize_mode",
                                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT));
        playerView.setVisibility(View.GONE);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // أدوات المشغل الإضافية تظهر وتختفي مع شريط تحكم ExoPlayer.
        playerSettingsButton = makeText("⚙", 23, Color.WHITE, true);
        playerSubtitleButton = makeText("CC", 15, Color.WHITE, true);
        TextView[] playerActions = {playerSubtitleButton, playerSettingsButton};
        for (TextView action : playerActions) {
            action.setGravity(Gravity.CENTER);
            action.setFocusable(true);
            action.setFocusableInTouchMode(true);
            action.setClickable(true);
            action.setBackground(browserFocusBackground(
                    Color.TRANSPARENT, Color.TRANSPARENT, 14, false));
            action.setOnFocusChangeListener((view, focused) -> {
                // Clean player controls: no dark button tile; a soft blue focus cue
                // replaces the heavy yellow frame while keeping the active control clear.
                view.setBackground(browserFocusBackground(
                        focused ? Color.argb(42, 112, 190, 255) : Color.TRANSPARENT,
                        focused ? Color.rgb(112, 190, 255) : Color.TRANSPARENT,
                        14, focused));
                view.setElevation(focused ? dp(5) : 0);
                view.setScaleX(focused ? 1.04f : 1.0f);
                view.setScaleY(focused ? 1.04f : 1.0f);
            });
            action.setVisibility(View.GONE);
            action.setMinWidth(dp(50));
            action.setMinHeight(dp(46));
        }
        playerSettingsButton.setId(View.generateViewId());
        playerSubtitleButton.setId(View.generateViewId());
        playerSettingsButton.setContentDescription("إعدادات المشغل");
        playerSubtitleButton.setContentDescription("البحث عن ترجمة عربية");
        playerSettingsButton.setNextFocusLeftId(playerSubtitleButton.getId());
        playerSettingsButton.setNextFocusDownId(androidx.media3.ui.R.id.exo_play_pause);
        playerSubtitleButton.setNextFocusRightId(playerSettingsButton.getId());
        playerSubtitleButton.setNextFocusDownId(androidx.media3.ui.R.id.exo_play_pause);
        playerSettingsButton.setOnClickListener(v -> showPlaybackSettingsDialog());
        playerSubtitleButton.setOnClickListener(v -> searchAndApplyArabicSubtitle());
        playerSubtitleButton.setOnLongClickListener(v -> {
            promptSubtitleTitleAndSearch();
            return true;
        });

        FrameLayout.LayoutParams playerSettingsParams = new FrameLayout.LayoutParams(
                dp(50), dp(46), Gravity.TOP | Gravity.END);
        playerSettingsParams.setMargins(dp(8), dp(14), dp(16), dp(8));
        root.addView(playerSettingsButton, playerSettingsParams);

        FrameLayout.LayoutParams playerSubtitleParams = new FrameLayout.LayoutParams(
                dp(50), dp(46), Gravity.TOP | Gravity.END);
        playerSubtitleParams.setMargins(dp(8), dp(14), dp(74), dp(8));
        root.addView(playerSubtitleButton, playerSubtitleParams);

        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean controlsVisible = visibility == View.VISIBLE
                    && playerView != null && playerView.getVisibility() == View.VISIBLE;
            playerControllerVisible = controlsVisible;
            if (playerSettingsButton != null) {
                playerSettingsButton.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
            }
            if (playerSubtitleButton != null) {
                playerSubtitleButton.setVisibility(controlsVisible && currentPlaybackHasVideo
                        ? View.VISIBLE : View.GONE);
            }
        });
        // Give every native controller button the same remote-focus treatment.
        playerView.post(this::stylePlayerControllerFocus);

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
        browserShortcut.setOnClickListener(v -> openTvBrowser());

        // Keep the original shortcut available internally, but the visible home entry
        // is the compact top-row browser icon beside Settings.
        browserShortcut.setVisibility(View.GONE);
        // The old lower-left browser shortcut is deliberately not attached to the screen.

        addPersistentBrandBadge();
        setContentView(root);
        settingsButton.requestFocus();
    }

    // يختار عنوان شبكة Wi-Fi/Ethernet الفعلية، ويتجاهل VPN وواجهات البروكسي.
    // لا نستخدم getActiveNetwork() وحدها لأنها قد تشير إلى VPN.
    private String getCurrentIpAddress() {
        // Read the IP assigned to the device's actual Wi-Fi connection first.
        // This avoids accidentally displaying a proxy/VPN/tunnel interface address.
        try {
            WifiManager wifiManager =
                    (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager != null) {
                int wifiIp = wifiManager.getConnectionInfo().getIpAddress();
                if (wifiIp != 0) {
                    String primaryWifiIp = String.format(Locale.US, "%d.%d.%d.%d",
                            wifiIp & 0xff,
                            (wifiIp >> 8) & 0xff,
                            (wifiIp >> 16) & 0xff,
                            (wifiIp >> 24) & 0xff);
                    if (!"0.0.0.0".equals(primaryWifiIp)) return primaryWifiIp;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not read primary Wi-Fi IP directly", e);
        }

        ConnectivityManager cm =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        if (cm != null) {
            try {
                Network[] networks = cm.getAllNetworks();

                // الأولوية لشبكة Wi-Fi الحقيقية، ثم Ethernet.
                for (int wantedTransport : new int[] {
                        android.net.NetworkCapabilities.TRANSPORT_WIFI,
                        android.net.NetworkCapabilities.TRANSPORT_ETHERNET
                }) {
                    for (Network network : networks) {
                        android.net.NetworkCapabilities caps =
                                cm.getNetworkCapabilities(network);
                        if (caps == null
                                || !caps.hasTransport(wantedTransport)
                                || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) {
                            continue;
                        }

                        LinkProperties properties = cm.getLinkProperties(network);
                        String ip = getPrivateIpv4FromLinkProperties(properties);
                        if (ip != null) return ip;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not inspect Wi-Fi/Ethernet network IP", e);
            }
        }

        // Fallback لأجهزة TV التي لا تعرض LinkProperties بشكل صحيح.
        // استبعاد أسماء واجهات VPN/tunnel/proxy قبل فحص عناوينها.
        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || isVirtualTunnelInterface(ni.getName())) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (isUsablePrivateIpv4(address)) return address.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not enumerate network interfaces", e);
        }

        return null;
    }

    private String getPrivateIpv4FromLinkProperties(LinkProperties properties) {
        if (properties == null) return null;
        for (android.net.LinkAddress linkAddress : properties.getLinkAddresses()) {
            InetAddress address = linkAddress.getAddress();
            if (isUsablePrivateIpv4(address)) return address.getHostAddress();
        }
        return null;
    }

    private boolean isUsablePrivateIpv4(InetAddress address) {
        return address instanceof Inet4Address
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && address.isSiteLocalAddress();
    }

    private boolean isVirtualTunnelInterface(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("tun")
                || n.startsWith("tap")
                || n.startsWith("ppp")
                || n.startsWith("wg")
                || n.startsWith("ipsec")
                || n.startsWith("vpn")
                || n.startsWith("v4-")
                || n.startsWith("clat");
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
        String sourceUrl = null;
        String command = null;
        java.util.Map<String, String> mediaHeaders = new java.util.LinkedHashMap<>();
        boolean requestAccepted = false;

        try (Socket socket = client) {
            socket.setSoTimeout(5000);
            java.io.InputStream input = new java.io.BufferedInputStream(socket.getInputStream());
            String requestLine = readHttpLine(input, 4096);
            if (requestLine != null) {
                String[] parts = requestLine.split(" ", 3);
                String method = parts.length > 0 ? parts[0] : "";
                int contentLength = 0;
                int totalHeaderBytes = 0;
                String headerLine;
                while ((headerLine = readHttpLine(input, 8192)) != null && !headerLine.isEmpty()) {
                    totalHeaderBytes += headerLine.length();
                    if (totalHeaderBytes > 16384) throw new java.io.IOException("HTTP headers too large");
                    int colon = headerLine.indexOf(':');
                    if (colon > 0 && "content-length".equalsIgnoreCase(headerLine.substring(0, colon).trim())) {
                        try {
                            contentLength = Integer.parseInt(headerLine.substring(colon + 1).trim());
                            if (contentLength < 0 || contentLength > 16384) throw new java.io.IOException("Invalid content length");
                        } catch (NumberFormatException invalidLength) {
                            throw new java.io.IOException("Invalid content length");
                        }
                    }
                }

                if ("POST".equalsIgnoreCase(method) && parts.length >= 2 && "/play".equals(parts[1])) {
                    if (contentLength <= 0) throw new java.io.IOException("Empty playback request");
                    byte[] bodyBytes = new byte[contentLength];
                    int offset = 0;
                    while (offset < contentLength) {
                        int count = input.read(bodyBytes, offset, contentLength - offset);
                        if (count < 0) break;
                        offset += count;
                    }
                    if (offset != contentLength) throw new java.io.IOException("Incomplete playback request");
                    JSONObject payload = new JSONObject(new String(bodyBytes, StandardCharsets.UTF_8));
                    mediaUrl = payload.optString("url", null);
                    audioUrl = payload.optString("audio", null);
                    sourceUrl = payload.optString("sourceUrl", null);
                    JSONObject headers = payload.optJSONObject("headers");
                    if (headers != null) {
                        java.util.Iterator<String> keys = headers.keys();
                        while (keys.hasNext() && mediaHeaders.size() < 6) {
                            String key = keys.next();
                            String lower = key.toLowerCase(Locale.ROOT);
                            String value = headers.optString(key, "");
                            if (value.length() <= ("cookie".equals(lower) ? 8192 : 2048)
                                    && !value.contains("\r") && !value.contains("\n")
                                    && ("user-agent".equals(lower) || "referer".equals(lower) || "origin".equals(lower)
                                    || "accept".equals(lower) || "accept-language".equals(lower) || "cookie".equals(lower))) {
                                mediaHeaders.put(key, value);
                            }
                        }
                    }
                } else if ("GET".equalsIgnoreCase(method) && parts.length >= 2) {
                    String target = parts[1];
                    int questionIndex = target.indexOf('?');
                    if (questionIndex >= 0) {
                        String query = target.substring(questionIndex + 1);
                        for (String pair : query.split("&")) {
                            String[] item = pair.split("=", 2);
                            if (item.length != 2) continue;
                            String key = URLDecoder.decode(item[0], "UTF-8");
                            String value = URLDecoder.decode(item[1], "UTF-8");
                            if ("url".equals(key) || "video".equals(key)) mediaUrl = value;
                            else if ("audio".equals(key)) audioUrl = value;
                            else if ("sourceUrl".equals(key)) sourceUrl = value;
                            else if ("command".equals(key)) command = value;
                        }
                    }
                }

                if (sourceUrl != null && !isHttpUrl(sourceUrl)) sourceUrl = null;
                boolean hasMedia = mediaUrl != null && !mediaUrl.trim().isEmpty();
                boolean hasAudio = audioUrl != null && !audioUrl.trim().isEmpty();
                boolean validMedia = (!hasMedia || isHttpUrl(mediaUrl)) && (!hasAudio || isHttpUrl(audioUrl));
                requestAccepted = ("ping".equals(command) || "status".equals(command))
                        || (validMedia && (hasMedia || hasAudio));
            }

            String responseBody = requestAccepted ? "OK" : "Invalid or unsupported request";
            String contentType = "text/plain; charset=utf-8";
            if ("status".equals(command)) {
                JSONObject statusJson = new JSONObject();
                statusJson.put("extracting", extractionInProgress);
                statusJson.put("attempt", extractionAttemptCount);
                statusJson.put("status", extractionStatus);
                statusJson.put("message", playbackStatusMessage == null ? "" : playbackStatusMessage);
                responseBody = statusJson.toString();
                contentType = "application/json; charset=utf-8";
            }
            byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            String statusLine = requestAccepted ? "HTTP/1.1 200 OK\r\n" : "HTTP/1.1 400 Bad Request\r\n";
            byte[] response = (statusLine
                    + "Content-Type: " + contentType + "\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(response);
            output.write(bodyBytes);
            output.flush();
        } catch (Exception e) {
            Log.e(TAG, "Request error", e);
            return;
        }

        if ("ping".equals(command) || "status".equals(command)) return;
        if (command != null && !command.trim().isEmpty()) {
            final String requestedCommand = command.trim();
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
        if (requestAccepted && ((mediaUrl != null && !mediaUrl.trim().isEmpty())
                || (audioUrl != null && !audioUrl.trim().isEmpty()))) {
            final String receivedMediaUrl = mediaUrl;
            final String receivedAudioUrl = audioUrl;
            final java.util.Map<String, String> receivedHeaders = new java.util.LinkedHashMap<>(mediaHeaders);
            final String receivedSourceUrl = sourceUrl;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "وصل طلب فيديو من Share New؛ جاري تجهيز التشغيل…", Toast.LENGTH_LONG).show();
                queueIncomingMedia(receivedMediaUrl, receivedAudioUrl, receivedHeaders, receivedSourceUrl);
            });
        }
    }

    private String readHttpLine(java.io.InputStream input, int maxBytes) throws java.io.IOException {
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
            if (line.size() > maxBytes) throw new java.io.IOException("HTTP line too long");
        }
        if (value == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
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

    // Local media endpoints used by the AboEzzat sender apps.
    // AboEzzat Cast serves picked phone files on 8088 as /media.jpg or /media.mp4.
    // Share New serves them on 8082 as /media?id=... .
    // Keep both routes out of social-page extraction so local videos play as-is.
    private boolean isPhoneLocalMediaUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || path == null) return false;

            int port = uri.getPort();
            if (port == 8088) {
                return "/media.mp4".equalsIgnoreCase(path)
                        || "/media.jpg".equalsIgnoreCase(path);
            }
            return port == 8082 && "/media".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDirectStreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        if (isPhoneLocalMediaUrl(value)) return true;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String lower = value.toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            // Social CDNs frequently return signed, extensionless video URLs. Treat
            // only recognizable video endpoints as direct streams; do not re-run
            // page extraction for a valid Facebook/Instagram/Threads/TikTok/X stream.
            boolean knownSocialCdnVideo = host.equals("video.twimg.com")
                    || lowerPath.contains("/ext_tw_video/")
                    || lowerPath.contains("/amplify_video/")
                    || lowerPath.contains("/tweet_video/")
                    || ((host.contains("tiktokcdn") || host.contains("tiktokv")
                    || host.contains("byteoversea") || host.contains("ibytedtos"))
                    && (lowerPath.contains("/video/") || lowerPath.contains("/obj/")
                    || lowerPath.contains("/tos/")))
                    // Facebook frequently serves signed extensionless MP4 streams from
                    // scontent*.fbcdn.net with /v/t... or /o1/v/t... paths.
                    || ((host.endsWith("fbcdn.net") || host.endsWith("fbsbx.com"))
                    && (host.startsWith("video.") || lowerPath.contains("/v/t")
                    || lowerPath.contains("/t39.") || lowerPath.contains("/t42.")
                    || lowerPath.contains("/video/") || lowerPath.contains("/reel/")))
                    || (host.endsWith("cdninstagram.com")
                    && (lowerPath.contains("/t50.") || lowerPath.contains("/v/t")));
            return knownSocialCdnVideo
                    || lowerPath.endsWith(".mp4") || lowerPath.endsWith(".m4v")
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
        // Receiver accepts direct media URLs from Cast. A YouTube watch page means
        // sender-side extraction did not complete; do not run extractors on the TV.
        if (isYouTubeUrl(pageUrl)) {
            extractionInProgress = false;
            extractionStatus = "youtube_page_rejected";
            playbackStatusMessage = "لم يصل رابط فيديو مباشر من Cast.";
            persistExtractionState();
            if (!tryOpenSmartTubeFallback(pageUrl)) showExtractionFailureFallback(pageUrl);
            return;
        }
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
        // Some senders provide the YouTube page as mediaUrl but omit sourceUrl.
        // Preserve that original page URL so the SmartTube fallback is not skipped.
        if (!isYouTubeUrl(currentPlaybackFallbackUrl) && isYouTubeUrl(pageUrl)) {
            currentPlaybackFallbackUrl = pageUrl;
        }
        if (isYouTubeUrl(currentPlaybackFallbackUrl)) {
            if (tryOpenSmartTubeFallback(currentPlaybackFallbackUrl)) {
                extractionStatus = "smarttube_fallback";
                playbackStatusMessage = "تعذر استخراج رابط مباشر؛ تم تحويل رابط YouTube الأصلي إلى SmartTube.";
                persistExtractionState();
                return;
            }
            try {
                android.content.Intent viewIntent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, Uri.parse(currentPlaybackFallbackUrl));
                viewIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(android.content.Intent.createChooser(viewIntent, "اختَر تطبيق تشغيل فيديو YouTube"));
                extractionStatus = "app_chooser";
                playbackStatusMessage = "تعذر استخراج رابط مباشر؛ اختر تطبيق تشغيل الفيديو.";
                persistExtractionState();
                return;
            } catch (Exception chooserError) {
                Log.w(TAG, "Could not show YouTube app chooser", chooserError);
            }
        }
        pendingBrowserUrl = pageUrl;
        showWelcome();
        if (browserShortcut != null) browserShortcut.setVisibility(View.VISIBLE);
        if (receiverStatusText != null) {
            receiverStatusText.setText("تعذر استخراج الفيديو. اضغط أيقونة المتصفح لو حابب تفتح صفحة الموقع.");
        }
        Toast.makeText(MainActivity.this,
                "تعذر استخراج الفيديو. اضغط أيقونة المتصفح لو حابب تفتح صفحة الموقع.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Inject TV-friendly focus navigation into the active website.
     * A thick yellow outline follows the currently selected link, button, input,
     * video or other interactive element. Directional keys move spatially between
     * visible controls; OK activates the focused control.
     */
    private String getTvRemoteNavigationScript() {
        return "(function(){"
                + "try{"
                + "var isYouTube=/(^|\\.)youtube\\.com$|youtu\\.be$/i.test(location.hostname);"
                + "var isWatchPage=location.pathname==='/watch';"
                + "var styleId='abo-ezzat-tv-focus-style';"
                + "var style=document.getElementById(styleId);"
                + "if(!style){style=document.createElement('style');style.id=styleId;"
                + "style.textContent=':focus,.abo-ezzat-remote-focus{outline:6px solid #FFD400!important;"
                + "outline-offset:4px!important;box-shadow:0 0 0 3px #111!important;}';"
                + "document.documentElement.appendChild(style);}"
                + "var selector='a[href],button,input:not([type=hidden]),select,textarea,video,[role=button],[tabindex],summary,iframe,#movie_player';"
                + "function visible(el){if(!el||!el.getBoundingClientRect)return false;"
                + "var r=el.getBoundingClientRect(),s=getComputedStyle(el);"
                + "return r.width>3&&r.height>3&&el.getAttribute('tabindex')!=='-1'&&s.display!=='none'&&s.visibility!=='hidden'"
                + "&&Number(s.opacity||1)>0&&r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth;}"
                + "function anchorOf(el){try{return el&&el.closest?el.closest('a[href]'):null;}catch(e){return null;}}"
                + "function playerSurface(el){var p=document.querySelector('#movie_player');return !!(p&&el&&(el===p||p.contains(el)));}"
                + "function playerControls(){var p=document.querySelector('#movie_player');if(!p)return[];"
                + "var q=['.ytp-play-button','.ytp-mute-button','.ytp-subtitles-button','.ytp-settings-button','.ytp-size-button','.ytp-fullscreen-button'];"
                + "var out=[];q.forEach(function(sel){var e=p.querySelector(sel);if(e&&visible(e))out.push(e);});return out;}"
                + "function isChannelLink(el){var a=anchorOf(el)||el;if(!a||!a.getAttribute)return false;"
                + "var h=a.getAttribute('href')||'';return /^\\/(?:@|channel\\/|c\\/|user\\/)/i.test(h)||/youtube\\.com\\/(?:@|channel\\/|c\\/|user\\/)/i.test(h);}"
                + "function isVideoLink(el){var a=anchorOf(el)||el;if(!a||!a.getAttribute)return false;"
                + "var h=a.getAttribute('href')||'';return /(?:^|\\/)watch(?:\\?|$)/i.test(h)||h.indexOf('/watch?')>=0;}"
                + "function rawItems(){return Array.prototype.slice.call(document.querySelectorAll(selector)).filter(visible);}"
                + "function videoThumbs(){if(!isYouTube||isWatchPage)return[];"
                + "var q='a#thumbnail[href*=\\\"watch\\\"],ytd-thumbnail a[href*=\\\"watch\\\"],a.yt-lockup-view-model__content-image[href*=\\\"watch\\\"],a[href*=\\\"/watch?\\\"]';"
                + "var a=Array.prototype.slice.call(document.querySelectorAll(q)).filter(function(e){return visible(e)&&isVideoLink(e);});"
                + "a.sort(function(x,y){var rx=x.getBoundingClientRect(),ry=y.getBoundingClientRect();return (ry.width*ry.height)-(rx.width*rx.height);});return a;}"
                + "function normalizeTarget(el){if(!el)return el;if(isYouTube&&isWatchPage&&playerSurface(el)){"
                + "if(el.closest&&el.closest('.ytp-chrome-controls'))return el;"
                + "var p=document.querySelector('#movie_player');if(p){p.setAttribute('tabindex','0');return p;}}var a=anchorOf(el)||el;if(isYouTube&&!isWatchPage&&isVideoLink(a)){"
                + "var thumb=a.matches&&a.matches('a#thumbnail,a.yt-lockup-view-model__content-image')?a:"
                + "(a.querySelector&&a.querySelector('ytd-thumbnail')?a:(a.closest&&a.closest('ytd-rich-item-renderer,ytd-video-renderer,yt-lockup-view-model')?a:null));"
                + "if(thumb)return thumb;}return a;}"
                + "function items(){var raw=rawItems(),list=[],seen=[];raw.forEach(function(el){var t=normalizeTarget(el);"
                + "if(!t||seen.indexOf(t)>=0)return;seen.push(t);"
                + "if(isYouTube&&!isWatchPage&&(isChannelLink(t)||t.closest&&t.closest('ytd-menu-renderer,#menu,ytd-video-meta-block')))return;"
                + "if(isYouTube&&isWatchPage&&playerSurface(t)){if(list.indexOf(t)<0)list.unshift(t);return;}"
                + "if(isYouTube&&isWatchPage&&playerSurface(t))return;list.push(t);});"
                + "if(isYouTube&&isWatchPage){var ps=document.querySelector('#movie_player');if(ps){ps.setAttribute('tabindex','0');if(visible(ps)&&list.indexOf(ps)<0)list.unshift(ps);}}"
                + "if(isYouTube&&!isWatchPage){var thumbs=videoThumbs();thumbs.forEach(function(v){if(list.indexOf(v)<0)list.unshift(v);});"
                + "if(thumbs.length)list=list.filter(function(el){return !isVideoLink(el)||thumbs.indexOf(el)>=0;});}"
                + "return list;}"
                + "function firstTarget(){var thumbs=videoThumbs();if(thumbs.length)return thumbs[0];var list=items();"
                + "if(isYouTube&&!isWatchPage){for(var i=0;i<list.length;i++)if(isVideoLink(list[i]))return list[i];}"
                + "return list.length?list[0]:null;}"
                + "var active=null;"
                + "function focus(el){if(!el)return false;el=normalizeTarget(el);"
                + "if(isYouTube&&!isWatchPage&&isChannelLink(el)){var preferred=firstTarget();if(preferred&&preferred!==el)el=preferred;}"
                + "if(active&&active!==el)active.classList.remove('abo-ezzat-remote-focus');"
                + "active=el;active.classList.add('abo-ezzat-remote-focus');"
                + "try{active.focus({preventScroll:true});}catch(e){try{active.focus();}catch(x){}}"
                + "try{active.scrollIntoView({block:'nearest',inline:'nearest',behavior:'smooth'});}catch(e){}"
                + "return true;}"
                + "function move(dir){"
                + "if(isYouTube&&isWatchPage&&active&&playerSurface(active)){"
                + "if(dir==='left'||dir==='right'){window.__aboRemotePlayerCommand(dir==='left'?'rewind':'forward');return;}"
                + "var controls=playerControls();if(controls.length){var at=controls.indexOf(active),next=dir==='down'?Math.min(controls.length-1,at+1):Math.max(0,at-1);focus(controls[next<0?0:next]);return;}"
                + "var surface=document.querySelector('#movie_player');if(surface)focus(surface);return;}"
                + "var list=items();if(!list.length)return;"
                + "if(!active||!document.contains(active)||!visible(active)){focus(firstTarget());return;}"
                + "var a=active.getBoundingClientRect(),ax=a.left+a.width/2,ay=a.top+a.height/2;"
                + "var best=null,bestScore=1e20;"
                + "list.forEach(function(el){if(el===active)return;var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;"
                + "var dx=x-ax,dy=y-ay,primary=0,cross=0;"
                + "if(dir==='right'){if(dx<=5)return;primary=dx;cross=Math.abs(dy);}"
                + "else if(dir==='left'){if(dx>=-5)return;primary=-dx;cross=Math.abs(dy);}"
                + "else if(dir==='down'){if(dy<=5)return;primary=dy;cross=Math.abs(dx);}"
                + "else{if(dy>=-5)return;primary=-dy;cross=Math.abs(dx);}"
                + "var score=primary+cross*1.65;if(score<bestScore){bestScore=score;best=el;}});"
                + "if(best){focus(best);}else{var amount=Math.max(180,Math.floor(innerHeight*.65));"
                + "window.scrollBy(0,(dir==='down'?amount:dir==='up'?-amount:0));"
                + "if(dir==='left')window.scrollBy(-Math.max(180,Math.floor(innerWidth*.55)),0);"
                + "if(dir==='right')window.scrollBy(Math.max(180,Math.floor(innerWidth*.55)),0);"
                + "setTimeout(function(){var next=items();if(next.length)focus(firstTarget());},180);}}"
                + "window.__aboRemoteMove=move;"
                + "window.__aboRemoteActivate=function(){if(!active||!document.contains(active)){focus(firstTarget());return;}"
                + "if(isYouTube&&isWatchPage&&playerSurface(active)){if(active.closest&&active.closest('.ytp-chrome-controls')){try{active.click();}catch(e){}}else window.__aboRemotePlayerCommand('toggle');return;}"
                + "if(active.tagName==='VIDEO'){try{if(active.paused)active.play();else active.pause();}catch(e){}}"
                + "else{try{active.click();}catch(e){}}};"
                + "window.__aboRemoteTogglePlayback=function(){var p=document.querySelector('#movie_player'),b=p&&p.querySelector('.ytp-play-button');"
                + "if(b){b.click();return;}var v=active&&active.tagName==='VIDEO'?active:document.querySelector('video');"
                + "if(v){try{if(v.paused)v.play();else v.pause();}catch(e){}}};"
                + "window.__aboRemotePlayerCommand=function(cmd){var p=document.querySelector('#movie_player'),v=p&&p.querySelector('video')||document.querySelector('video');"
                + "if(cmd==='toggle'){window.__aboRemoteTogglePlayback();return;}"
                + "if(cmd==='forward'||cmd==='rewind'){if(v){try{v.currentTime=Math.max(0,v.currentTime+(cmd==='forward'?10:-10));}catch(e){}}return;}"
                + "if(cmd==='fullscreen'){var f=p&&p.querySelector('.ytp-fullscreen-button');if(f)f.click();return;}"
                + "if(cmd==='settings'){var q=p&&p.querySelector('.ytp-settings-button');if(q)q.click();return;}"
                + "if(cmd==='mute'){var m=p&&p.querySelector('.ytp-mute-button');if(m)m.click();return;}"
                + "if(cmd==='captions'){var c=p&&p.querySelector('.ytp-subtitles-button');if(c)c.click();return;}};"
                + "document.addEventListener('focusin',function(e){if(e.target&&e.target.matches&&e.target.matches(selector)){"
                + "if(isYouTube&&!isWatchPage&&isChannelLink(e.target)){var v=firstTarget();if(v){e.stopPropagation();focus(v);return;}}"
                + "focus(e.target);}},true);"
                + "var observer=new MutationObserver(function(){if(!active||!document.contains(active)){focus(firstTarget());}});"
                + "observer.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['style','class','hidden']});"
                + "if(!window.__aboRemoteInstalled){window.__aboRemoteInstalled=true;"
                + "document.addEventListener('keydown',function(e){var k=e.key;"
                + "if(k==='ArrowUp'||k==='ArrowDown'||k==='ArrowLeft'||k==='ArrowRight'){e.preventDefault();move(k.substring(5).toLowerCase());}"
                + "else if(k==='Enter'){e.preventDefault();window.__aboRemoteActivate();}},true);}"
                + "var current=document.activeElement;"
                + "if(!current||current===document.body||current===document.documentElement||"
                + "(isYouTube&&!isWatchPage&&isChannelLink(current))){focus(firstTarget());}"
                + "}catch(e){}})();";
    }

    private boolean isTvBrowserPageVisible() {
        return tvBrowserMode && browserOverlay != null
                && browserOverlay.getVisibility() == View.VISIBLE
                && fallbackWebView != null
                && fallbackWebView.getVisibility() == View.VISIBLE
                && (browserHomePanel == null || browserHomePanel.getVisibility() != View.VISIBLE);
    }

    // Last-resort browser playback: show the page without browser chrome and honor
    // HTML5 video fullscreen requests. Existing ExoPlayer remains the first choice.
    private void openTvBrowser() {
        tvBrowserMode = true;
        String initialUrl = isHttpUrl(pendingBrowserUrl) ? pendingBrowserUrl : "";
        if (browserOverlay == null) createTvBrowserChrome();
        // Keep the normal browser home visible unless we are opening a pending shared URL.
        showWebFallback(isHttpUrl(initialUrl) ? initialUrl : "https://www.google.com");
        if (fallbackWebView != null) {
            ViewGroup parent = (ViewGroup) fallbackWebView.getParent();
            if (parent != null) parent.removeView(fallbackWebView);
            FrameLayout body = browserOverlay.findViewWithTag("tv_browser_body");
            if (body != null) body.addView(fallbackWebView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            browserOverlay.setVisibility(View.VISIBLE);
            browserOverlay.bringToFront();
            keepPersistentBrandBadgeOnTop();
            if (isHttpUrl(initialUrl)) {
                browserAddress.setText(initialUrl);
                browserHomePanel.setVisibility(View.GONE);
                fallbackWebView.setVisibility(View.VISIBLE);
                browserAddress.setSelection(browserAddress.getText().length());
                browserAddress.requestFocus();
            } else {
                browserAddress.setText("");
                showBrowserStartPage();
            }
        }
    }

    private void createTvBrowserChrome() {
        browserOverlay = new FrameLayout(this);
        browserOverlay.setBackgroundColor(Color.rgb(3, 12, 32));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(3, 12, 32));
        page.setPadding(dp(18), dp(10), dp(18), dp(12));
        browserOverlay.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        brandRow.setPadding(dp(68), 0, 0, 0);
        TextView brandIcon = makeText("📺", 22, Color.rgb(255, 207, 94), true);
        TextView brand = makeText("ABO EZZAT RECEIVER", 17, Color.rgb(255, 207, 94), true);
        brand.setLetterSpacing(0.04f);
        brandRow.addView(brandIcon, new LinearLayout.LayoutParams(dp(42), dp(38)));
        brandRow.addView(brand, new LinearLayout.LayoutParams(0, dp(38), 1f));
        page.addView(brandRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        // Reserve the top-left corner for the always-visible circular brand portrait.
        toolbar.setPadding(dp(68), dp(5), 0, dp(5));
        TextView back = makeBrowserButton("‹", "رجوع");
        TextView forward = makeBrowserButton("›", "تقدم");
        TextView refresh = makeBrowserButton("⟳", "تحديث");
        TextView home = makeBrowserButton("⌂", "الرئيسية");
        TextView tabs = makeBrowserButton("▢", "علامات التبويب");
        TextView menu = makeBrowserButton("⋮", "قائمة المتصفح");
        tabs.setOnClickListener(v -> showBrowserTabs());
        menu.setOnClickListener(v -> showBrowserMenu());

        browserAddress = new EditText(this);
        browserAddress.setSingleLine(true);
        browserAddress.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        browserAddress.setTextColor(Color.WHITE);
        browserAddress.setHintTextColor(Color.rgb(165, 184, 210));
        browserAddress.setHint("ابحث أو اكتب عنوان الموقع");
        browserAddress.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        browserAddress.setPadding(dp(14), dp(8), dp(14), dp(8));
        browserAddress.setBackground(roundedBackground(Color.rgb(18, 31, 54),
                Color.rgb(86, 132, 255), 16));
        browserAddress.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        browserAddress.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                navigateBrowserAddress(); return true;
            }
            return false;
        });
        TextView mic = makeBrowserButton("🎙", "البحث بالصوت");
        TextView clear = makeBrowserButton("×", "مسح البحث");
        TextView go = makeBrowserButton("OK", "بحث أو فتح");
        back.setOnClickListener(v -> { if (fallbackWebView != null && fallbackWebView.canGoBack()) fallbackWebView.goBack(); });
        forward.setOnClickListener(v -> { if (fallbackWebView != null && fallbackWebView.canGoForward()) fallbackWebView.goForward(); });
        refresh.setOnClickListener(v -> { if (fallbackWebView != null) fallbackWebView.reload(); });
        home.setOnClickListener(v -> showBrowserStartPage());
        mic.setOnClickListener(v -> startBrowserVoiceSearch());
        clear.setOnClickListener(v -> { browserAddress.setText(""); browserAddress.requestFocus(); });
        go.setOnClickListener(v -> navigateBrowserAddress());

        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(forward, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(home, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(tabs, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(46)));
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        addressParams.setMargins(dp(8), 0, dp(8), 0);
        toolbar.addView(browserAddress, addressParams);
        toolbar.addView(mic, new LinearLayout.LayoutParams(dp(48), dp(46)));
        toolbar.addView(clear, new LinearLayout.LayoutParams(dp(42), dp(46)));
        toolbar.addView(go, new LinearLayout.LayoutParams(dp(58), dp(46)));
        page.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        browserProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        browserProgress.setMax(100);
        browserProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 197, 74)));
        browserProgress.setVisibility(View.GONE);
        page.addView(browserProgress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        FrameLayout body = new FrameLayout(this);
        body.setTag("tv_browser_body");
        body.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = dp(8);
        page.addView(body, bodyParams);
        browserHomePanel = new FrameLayout(this);
        browserHomePanel.setBackgroundColor(Color.rgb(5, 15, 34));
        body.addView(browserHomePanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        buildBrowserHomePanel();
        root.addView(browserOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        browserOverlay.setVisibility(View.GONE);
        keepPersistentBrandBadgeOnTop();
    }

    private TextView makeBrowserButton(String label, String description) {
        TextView button = makeText(label, 20, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setClickable(true);
        button.setContentDescription(description);
        button.setBackground(browserFocusBackground(Color.rgb(20, 34, 61), Color.rgb(86, 132, 255), 12, false));
        button.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(browserFocusBackground(
                    focused ? Color.rgb(43, 66, 108) : Color.rgb(20, 34, 61),
                    focused ? Color.rgb(255, 212, 0) : Color.rgb(86, 132, 255), 12, focused));
            v.setScaleX(focused ? 1.06f : 1f);
            v.setScaleY(focused ? 1.06f : 1f);
            v.setElevation(focused ? dp(12) : dp(3));
        });
        return button;
    }


    private void showBrowserStartPage() {
        if (browserHomePanel == null || fallbackWebView == null) return;
        fallbackWebView.stopLoading();
        fallbackWebView.setVisibility(View.GONE);
        browserHomePanel.setVisibility(View.VISIBLE);
        browserHomePanel.bringToFront();
        browserAddress.setText("");
        browserAddress.clearFocus();
        if (browserProgress != null) browserProgress.setVisibility(View.GONE);
        // D-pad starts on the search field, then moves down to the social shortcuts.
        browserAddress.requestFocus();
    }

    private void startBrowserVoiceSearch() {
        try {
            android.content.Intent voice = new android.content.Intent(
                    android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
            voice.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            voice.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "ابحث بالصوت أو قل اسم الموقع");
            startActivityForResult(voice, REQUEST_BROWSER_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "ميزة البحث بالصوت غير متاحة على هذا الجهاز", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر تشغيل البحث بالصوت", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_BROWSER_VOICE || resultCode != RESULT_OK || data == null) return;
        java.util.ArrayList<String> matches = data.getStringArrayListExtra(
                android.speech.RecognizerIntent.EXTRA_RESULTS);
        if (matches != null && !matches.isEmpty()) {
            browserAddress.setText(matches.get(0));
            navigateBrowserAddress();
        }
    }

    private void buildBrowserHomePanel() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(22));
        scroll.addView(content, new android.widget.ScrollView.LayoutParams(
                android.widget.ScrollView.LayoutParams.MATCH_PARENT,
                android.widget.ScrollView.LayoutParams.WRAP_CONTENT));

        TextView welcome = makeText("أهلاً بك في متصفح أبو عزت", 22, Color.WHITE, true);
        welcome.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.addView(welcome, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        TextView subtitle = makeText("اختَر موقعك المفضل أو ابحث بالريموت / المايك", 13,
                Color.rgb(165, 190, 220), false);
        subtitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.addView(subtitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        TextView socialTitle = makeText("مواقع التواصل الاجتماعي", 17, Color.rgb(255, 207, 94), true);
        socialTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        titleParams.topMargin = dp(8);
        content.addView(socialTitle, titleParams);

        String[][] socials = {
                {"YouTube", "https://www.youtube.com", "youtube.com"},
                {"Facebook", "https://www.facebook.com", "facebook.com"},
                {"TikTok", "https://www.tiktok.com", "tiktok.com"},
                {"Instagram", "https://www.instagram.com", "instagram.com"},
                {"X", "https://x.com", "x.com"},
                {"Threads", "https://www.threads.net", "threads.net"}
        };
        for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(112));
            rowParams.topMargin = dp(7);
            content.addView(row, rowParams);
            for (int col = 0; col < 3; col++) {
                String[] item = socials[rowIndex * 3 + col];
                addBrowserTile(row, item[0], item[1], item[2], true);
            }
        }

        TextView sitesTitle = makeText("مواقعك المضافة", 17, Color.rgb(255, 207, 94), true);
        sitesTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams sitesTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        sitesTitleParams.topMargin = dp(12);
        content.addView(sitesTitle, sitesTitleParams);

        browserBookmarksContainer = new LinearLayout(this);
        browserBookmarksContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(browserBookmarksContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        refreshBrowserBookmarks();

        LinearLayout addRow = new LinearLayout(this);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(88));
        addParams.topMargin = dp(8);
        content.addView(addRow, addParams);
        TextView add = makeText("＋   إضافة موقع جديد", 16, Color.WHITE, true);
        add.setGravity(Gravity.CENTER);
        add.setFocusable(true);
        add.setClickable(true);
        add.setBackground(roundedBackground(Color.rgb(17, 42, 70),
                Color.rgb(255, 197, 74), 16));
        add.setOnFocusChangeListener((v, focused) -> v.setBackground(roundedBackground(
                focused ? Color.rgb(43, 66, 108) : Color.rgb(17, 42, 70),
                focused ? Color.rgb(255, 196, 87) : Color.rgb(255, 197, 74), 16)));
        add.setOnClickListener(v -> showAddBrowserSiteDialog());
        addRow.addView(add, new LinearLayout.LayoutParams(dp(280), dp(76)));
        browserHomePanel.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void addBrowserTile(LinearLayout row, String title, String url, String domain, boolean social) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(true);
        tile.setClickable(true);
        tile.setPadding(dp(8), dp(6), dp(8), dp(6));
        tile.setBackground(browserFocusBackground(Color.rgb(16, 31, 54),
                Color.rgb(53, 82, 126), 16, false));
        tile.setOnFocusChangeListener((v, focused) -> {
            v.setBackground(browserFocusBackground(
                    focused ? Color.rgb(35, 58, 91) : Color.rgb(16, 31, 54),
                    focused ? Color.rgb(255, 212, 0) : Color.rgb(53, 82, 126), 16, focused));
            v.setScaleX(focused ? 1.05f : 1f);
            v.setScaleY(focused ? 1.05f : 1f);
        });
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(android.R.drawable.ic_menu_view);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        loadBrowserFavicon(icon, domain);
        TextView label = makeText(title, 13, Color.WHITE, true);
        label.setMaxLines(1);
        tile.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        tile.setOnClickListener(v -> {
            pendingBrowserUrl = "";
            browserAddress.setText(url);
            navigateBrowserAddress();
        });
        if (!social) {
            tile.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle(title)
                        .setMessage("حذف الموقع من المفضلة؟")
                        .setPositiveButton("حذف", (d, w) -> removeBrowserBookmark(url))
                        .setNegativeButton("إلغاء", null).show();
                return true;
            });
        }
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        tileParams.setMargins(dp(5), dp(2), dp(5), dp(2));
        row.addView(tile, tileParams);
    }

    private void loadBrowserFavicon(ImageView target, String domain) {
        if (target == null || domain == null || domain.trim().isEmpty()) return;
        String faviconUrl = "https://www.google.com/s2/favicons?domain=" + Uri.encode(domain) + "&sz=128";
        Thread thread = new Thread(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(faviconUrl).openConnection();
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setInstanceFollowRedirects(true);
                try (java.io.InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not load browser favicon for " + domain, e);
            } finally {
                if (connection != null) connection.disconnect();
            }
            final Bitmap image = bitmap;
            if (image != null) runOnUiThread(() -> {
                if (target.getParent() != null) target.setImageBitmap(image);
            });
        }, "BrowserFavicon");
        thread.setDaemon(true);
        thread.start();
    }

    private void showAddBrowserSiteDialog() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(4), dp(12), 0);
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("اسم الموقع (مثلاً: موقعي المفضل)");
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://example.com");
        panel.addView(name);
        panel.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("إضافة موقع للمفضلة")
                .setView(panel).setNegativeButton("إلغاء", null)
                .setPositiveButton("إضافة", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String siteName = name.getText().toString().trim();
            String siteUrl = address.getText().toString().trim();
            if (siteName.isEmpty()) siteName = Uri.parse(siteUrl).getHost() == null
                    ? "موقع جديد" : Uri.parse(siteUrl).getHost();
            if (!siteUrl.matches("(?i)^https?://.+") || !isHttpUrl(siteUrl)) {
                address.setError("اكتب رابطًا يبدأ بـ http:// أو https://");
                return;
            }
            org.json.JSONArray sites = getBrowserBookmarks();
            for (int i = 0; i < sites.length(); i++) {
                JSONObject existing = sites.optJSONObject(i);
                if (existing != null && siteUrl.equalsIgnoreCase(existing.optString("url", ""))) {
                    Toast.makeText(this, "الموقع موجود بالفعل في المفضلة", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    return;
                }
            }
            JSONObject item = new JSONObject();
            try { item.put("name", siteName); item.put("url", siteUrl); item.put("domain", Uri.parse(siteUrl).getHost()); sites.put(item); }
            catch (Exception ignored) { }
            getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).edit()
                    .putString("browser_bookmarks", sites.toString()).apply();
            refreshBrowserBookmarks();
            dialog.dismiss();
            Toast.makeText(this, "تمت إضافة الموقع للمفضلة", Toast.LENGTH_SHORT).show();
        }));
        dialog.show();
    }

    private org.json.JSONArray getBrowserBookmarks() {
        try {
            return new org.json.JSONArray(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                    .getString("browser_bookmarks", "[]"));
        } catch (Exception e) { return new org.json.JSONArray(); }
    }

    private void refreshBrowserBookmarks() {
        if (browserBookmarksContainer == null) return;
        browserBookmarksContainer.removeAllViews();
        org.json.JSONArray sites = getBrowserBookmarks();
        for (int start = 0; start < sites.length(); start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            browserBookmarksContainer.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(112)));
            for (int i = start; i < Math.min(start + 3, sites.length()); i++) {
                JSONObject site = sites.optJSONObject(i);
                if (site == null) continue;
                String name = site.optString("name", "موقع");
                String url = site.optString("url", "");
                String domain = site.optString("domain", Uri.parse(url).getHost());
                addBrowserTile(row, name, url, domain, false);
            }
        }
    }

    private void removeBrowserBookmark(String url) {
        org.json.JSONArray old = getBrowserBookmarks();
        org.json.JSONArray updated = new org.json.JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.optJSONObject(i);
            if (item != null && !url.equals(item.optString("url", ""))) updated.put(item);
        }
        getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).edit()
                .putString("browser_bookmarks", updated.toString()).apply();
        refreshBrowserBookmarks();
    }

    // Browser essentials: lightweight tab sessions, history, preferences and privacy tools.
    private final java.util.ArrayList<String> browserTabUrls = new java.util.ArrayList<>();
    private int browserTabIndex = 0;

    private void showBrowserTabs() {
        if (browserTabUrls.isEmpty()) {
            String current = fallbackWebView == null ? "" : fallbackWebView.getUrl();
            browserTabUrls.add(isHttpUrl(current) ? current : "https://www.google.com");
            browserTabIndex = 0;
        }
        String[] choices = new String[browserTabUrls.size() + 2];
        for (int i = 0; i < browserTabUrls.size(); i++) {
            choices[i] = (i == browserTabIndex ? "● " : "") + "تبويب " + (i + 1) + " — " + browserTabUrls.get(i);
        }
        choices[choices.length - 2] = "＋ فتح تبويب جديد";
        choices[choices.length - 1] = "× إغلاق التبويب الحالي";
        new AlertDialog.Builder(this).setTitle("علامات التبويب")
                .setItems(choices, (dialog, which) -> {
                    if (which == browserTabUrls.size()) {
                        String home = getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                                .getString("homepage", "https://www.google.com");
                        browserTabUrls.add(home);
                        browserTabIndex = browserTabUrls.size() - 1;
                        if (fallbackWebView != null) fallbackWebView.loadUrl(home);
                        if (browserAddress != null) browserAddress.setText(home);
                        Toast.makeText(this, "تم فتح تبويب جديد", Toast.LENGTH_SHORT).show();
                    } else if (which == browserTabUrls.size() + 1) {
                        if (browserTabUrls.size() > 1) {
                            browserTabUrls.remove(browserTabIndex);
                            browserTabIndex = Math.min(browserTabIndex, browserTabUrls.size() - 1);
                            openBrowserTab(browserTabIndex);
                        } else {
                            Toast.makeText(this, "هذا آخر تبويب مفتوح", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        openBrowserTab(which);
                    }
                }).setNegativeButton("إغلاق", null).show();
    }

    private void openBrowserTab(int index) {
        if (index < 0 || index >= browserTabUrls.size()) return;
        browserTabIndex = index;
        String url = browserTabUrls.get(index);
        if (browserAddress != null) browserAddress.setText(url);
        if (fallbackWebView != null && isHttpUrl(url)) fallbackWebView.loadUrl(url);
    }

    private void rememberBrowserVisit(String url) {
        if (!isHttpUrl(url)) return;
        SharedPreferences prefs = getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE);
        org.json.JSONArray old;
        try { old = new org.json.JSONArray(prefs.getString("history", "[]")); }
        catch (Exception e) { old = new org.json.JSONArray(); }
        if (old.length() > 0) {
            JSONObject first = old.optJSONObject(0);
            if (first != null && url.equals(first.optString("url", ""))) return;
        }
        org.json.JSONArray next = new org.json.JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("url", url);
            item.put("title", Uri.parse(url).getHost());
            item.put("visited", System.currentTimeMillis());
            next.put(item);
            for (int i = 0; i < Math.min(old.length(), 99); i++) {
                JSONObject previous = old.optJSONObject(i);
                if (previous != null) next.put(previous);
            }
            prefs.edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void showBrowserHistory() {
        org.json.JSONArray history;
        try { history = new org.json.JSONArray(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                .getString("history", "[]")); }
        catch (Exception e) { history = new org.json.JSONArray(); }
        if (history.length() == 0) {
            new AlertDialog.Builder(this).setTitle("سجل التصفح").setMessage("السجل فارغ")
                    .setPositiveButton("إغلاق", null).show();
            return;
        }
        String[] labels = new String[history.length()];
        String[] urls = new String[history.length()];
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            urls[i] = item == null ? "" : item.optString("url", "");
            labels[i] = item == null ? urls[i] : item.optString("title", urls[i]) + "\n" + urls[i];
        }
        new AlertDialog.Builder(this).setTitle("سجل التصفح")
                .setItems(labels, (d, which) -> {
                    if (browserAddress != null) {
                        browserAddress.setText(urls[which]);
                        navigateBrowserAddress();
                    }
                }).setNeutralButton("مسح السجل", (d, w) -> {
                    getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).edit()
                            .putString("history", "[]").apply();
                    Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show();
                }).setNegativeButton("إغلاق", null).show();
    }

    private void saveCurrentBrowserPage() {
        if (fallbackWebView == null || !isHttpUrl(fallbackWebView.getUrl())) {
            Toast.makeText(this, "افتح صفحة أولًا", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = fallbackWebView.getUrl();
        org.json.JSONArray sites = getBrowserBookmarks();
        for (int i = 0; i < sites.length(); i++) {
            JSONObject existing = sites.optJSONObject(i);
            if (existing != null && url.equalsIgnoreCase(existing.optString("url", ""))) {
                Toast.makeText(this, "الصفحة محفوظة بالفعل", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        JSONObject item = new JSONObject();
        try {
            String title = fallbackWebView.getTitle();
            item.put("name", title == null || title.trim().isEmpty() ? Uri.parse(url).getHost() : title);
            item.put("url", url);
            item.put("domain", Uri.parse(url).getHost());
            sites.put(item);
            getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).edit()
                    .putString("browser_bookmarks", sites.toString()).apply();
            refreshBrowserBookmarks();
            Toast.makeText(this, "تم حفظ الصفحة في المفضلة", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    private void showBrowserSettings() {
        SharedPreferences prefs = getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(4), dp(12), 0);
        android.widget.CheckBox javascript = new android.widget.CheckBox(this);
        javascript.setText("تشغيل JavaScript");
        javascript.setChecked(prefs.getBoolean("javascript", true));
        android.widget.CheckBox cookies = new android.widget.CheckBox(this);
        cookies.setText("السماح بملفات تعريف الارتباط وتسجيل الدخول");
        cookies.setChecked(prefs.getBoolean("cookies", true));
        android.widget.CheckBox desktop = new android.widget.CheckBox(this);
        desktop.setText("عرض نسخة سطح المكتب");
        desktop.setChecked(prefs.getBoolean("desktop", true));
        android.widget.CheckBox ads = new android.widget.CheckBox(this);
        ads.setText("مانع الإعلانات");
        ads.setChecked(prefs.getBoolean("adblock_enabled", true));
        EditText home = new EditText(this);
        home.setSingleLine(true);
        home.setHint("https://www.google.com");
        home.setText(prefs.getString("homepage", "https://www.google.com"));
        panel.addView(javascript); panel.addView(cookies); panel.addView(desktop); panel.addView(ads);
        panel.addView(makeText("الصفحة الرئيسية", 14, Color.WHITE, true));
        panel.addView(home);
        new AlertDialog.Builder(this).setTitle("إعدادات المتصفح").setView(panel)
                .setNegativeButton("إلغاء", null).setPositiveButton("حفظ", (d, w) -> {
                    String homepage = home.getText().toString().trim();
                    if (!isHttpUrl(homepage)) homepage = "https://www.google.com";
                    prefs.edit().putBoolean("javascript", javascript.isChecked())
                            .putBoolean("cookies", cookies.isChecked())
                            .putBoolean("desktop", desktop.isChecked())
                            .putBoolean("adblock_enabled", ads.isChecked())
                            .putString("homepage", homepage).apply();
                    if (fallbackWebView != null) {
                        WebSettings settings = fallbackWebView.getSettings();
                        settings.setJavaScriptEnabled(javascript.isChecked());
                        settings.setUserAgentString(desktop.isChecked()
                                ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                : WebSettings.getDefaultUserAgent(this));
                        CookieManager manager = CookieManager.getInstance();
                        manager.setAcceptCookie(cookies.isChecked());
                        if (android.os.Build.VERSION.SDK_INT >= 21)
                            manager.setAcceptThirdPartyCookies(fallbackWebView, cookies.isChecked());
                    }
                    Toast.makeText(this, "تم حفظ الإعدادات؛ أعد تحميل الصفحة لتطبيق كل التغييرات", Toast.LENGTH_LONG).show();
                }).show();
    }

    private void showBrowserMenu() {
        // Tab management has one entry point in the toolbar; keep it out of this menu.
        String[] options = {"⚙ إعدادات المتصفح", "⭐ حفظ الصفحة الحالية",
                "🕘 سجل التصفح", "⬇ تنزيلات أندرويد", "🧹 مسح بيانات التصفح"};
        new AlertDialog.Builder(this).setTitle("قائمة المتصفح").setItems(options, (d, which) -> {
            switch (which) {
                case 0: showBrowserSettings(); break;
                case 1: saveCurrentBrowserPage(); break;
                case 2: showBrowserHistory(); break;
                case 3:
                    try { startActivity(new android.content.Intent("android.intent.action.VIEW_DOWNLOADS")); }
                    catch (Exception e) { Toast.makeText(this, "افتح تطبيق الملفات ومجلد Downloads", Toast.LENGTH_LONG).show(); }
                    break;
                case 4:
                    new AlertDialog.Builder(this).setTitle("مسح بيانات التصفح")
                            .setMessage("سيتم مسح السجل وذاكرة التخزين المؤقت وملفات تعريف الارتباط. قد تحتاج إلى تسجيل الدخول مرة أخرى.")
                            .setPositiveButton("مسح", (dialog, w) -> {
                                getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).edit().putString("history", "[]").apply();
                                if (fallbackWebView != null) fallbackWebView.clearCache(true);
                                CookieManager.getInstance().removeAllCookies(null);
                                CookieManager.getInstance().flush();
                                Toast.makeText(this, "تم مسح بيانات التصفح", Toast.LENGTH_SHORT).show();
                            }).setNegativeButton("إلغاء", null).show();
                    break;
            }
        }).show();
    }

    private void navigateBrowserAddress() {
        if (browserAddress == null || fallbackWebView == null) return;
        String target = browserAddress.getText().toString().trim();
        if (target.isEmpty()) return;
        if (!target.matches("(?i)^https?://.*")) {
            target = (target.contains(" ") || !target.contains("."))
                    ? "https://www.google.com/search?q=" + Uri.encode(target)
                    : "https://" + target;
        }
        if (!isHttpUrl(target)) {
            Toast.makeText(this, "اكتب عنوان موقع صحيح", Toast.LENGTH_SHORT).show(); return;
        }
        pendingBrowserUrl = target;
        if (browserTabUrls.isEmpty()) {
            browserTabUrls.add(target);
            browserTabIndex = 0;
        } else if (browserTabIndex >= 0 && browserTabIndex < browserTabUrls.size()) {
            browserTabUrls.set(browserTabIndex, target);
        }
        browserAddress.setText(target);
        fallbackWebView.loadUrl(target);
        browserAddress.clearFocus();
        if (browserHomePanel != null) browserHomePanel.setVisibility(View.GONE);
        fallbackWebView.setVisibility(View.VISIBLE);
        fallbackWebView.requestFocus();
    }

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
            settings.setJavaScriptEnabled(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                    .getBoolean("javascript", true));
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
            settings.setUserAgentString(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                    .getBoolean("desktop", true)
                    ? "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    : WebSettings.getDefaultUserAgent(this));
            // Keep the fallback browser compact on TV screens; users can still
            // zoom in with the remote when a particular site needs it.
            settings.setTextZoom(85);
            fallbackWebView.setInitialScale(75);
            settings.setSupportZoom(true);
            settings.setBuiltInZoomControls(true);
            settings.setDisplayZoomControls(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(false); // لا تسمح بفتح نافذة تلقائيًا دون نقرة المستخدم
            settings.setSupportMultipleWindows(true);
            CookieManager.getInstance().setAcceptCookie(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                    .getBoolean("cookies", true));
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
                            && !tvBrowserMode && !candidate.equals(fallbackPageUrl) && !fallbackStreamCaptured) {
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
                    if (tvBrowserMode && browserAddress != null && url != null) {
                        rememberBrowserVisit(url);
                        browserAddress.setText(url);
                        browserAddress.setSelection(browserAddress.getText().length());
                        if (browserProgress != null) browserProgress.setVisibility(View.GONE);
                    }
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
                    if (tvBrowserMode && view == fallbackWebView) {
                        view.evaluateJavascript(getTvRemoteNavigationScript(), null);
                        view.setFocusable(true);
                        view.setFocusableInTouchMode(true);
                        view.requestFocus();
                    }
                }
            });
            fallbackWebView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
                try {
                    android.app.DownloadManager.Request request =
                            new android.app.DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    if (userAgent != null && !userAgent.isEmpty())
                        request.addRequestHeader("User-Agent", userAgent);
                    String fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(fileName);
                    request.setDescription("تنزيل من متصفح أبو عزت");
                    request.setNotificationVisibility(
                            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, fileName);
                    android.app.DownloadManager manager =
                            (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager != null) {
                        manager.enqueue(request);
                        Toast.makeText(MainActivity.this, "بدأ التنزيل — ستجد الملف في Downloads", Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذّر بدء التنزيل", Toast.LENGTH_LONG).show();
                }
            });
            fallbackWebView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                        android.os.Message resultMsg) {
                    // Keep popups blocked until the user explicitly chooses Open.
                    if (!tvBrowserMode || !isUserGesture) return false;
                    if (pendingPopupWebView != null) {
                        try { pendingPopupWebView.destroy(); } catch (Exception ignored) { }
                    }
                    WebView popup = new WebView(MainActivity.this);
                    WebSettings popupSettings = popup.getSettings();
                    popupSettings.setJavaScriptEnabled(true);
                    popupSettings.setDomStorageEnabled(true);
                    popupSettings.setJavaScriptCanOpenWindowsAutomatically(false);
                    popupSettings.setSupportMultipleWindows(false);
                    popupSettings.setAllowFileAccess(false);
                    popupSettings.setAllowContentAccess(true);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        popupSettings.setSafeBrowsingEnabled(true);
                    }
                    CookieManager.getInstance().setAcceptCookie(true);
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
                    }
                    popup.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView w, android.webkit.WebResourceRequest req) {
                            Uri u = req == null ? null : req.getUrl();
                            return u != null && getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                                    .getBoolean("adblock_enabled", true) && isKnownBrowserAdRequest(u.toString());
                        }
                        @Override public void onSafeBrowsingHit(WebView w, android.webkit.WebResourceRequest req,
                                int threatType, android.webkit.SafeBrowsingResponse response) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                response.backToSafety(true);
                                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                        "تم حظر نافذة لموقع تحذر منه ميزة التصفح الآمن", Toast.LENGTH_LONG).show());
                            }
                        }
                    });
                    pendingPopupWebView = popup;
                    android.webkit.WebView.WebViewTransport transport =
                            (android.webkit.WebView.WebViewTransport) resultMsg.obj;
                    transport.setWebView(popup);
                    resultMsg.sendToTarget();
                    popupHandler.postDelayed(() -> {
                        if (pendingPopupWebView != popup || isFinishing()) return;
                        popupRequestDialog = new AlertDialog.Builder(MainActivity.this)
                                .setTitle("نافذة منبثقة")
                                .setMessage("الموقع يريد فتح نافذة جديدة. افتحها؟")
                                .setPositiveButton("فتح النافذة", (d, which) -> openRequestedPopup(popup))
                                .setNegativeButton("إغلاق", (d, which) -> discardRequestedPopup(popup))
                                .setOnCancelListener(d -> discardRequestedPopup(popup))
                                .create();
                        popupRequestDialog.setOnShowListener(d -> {
                            popupRequestDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
                        });
                        popupRequestDialog.show();
                    }, 1000L);
                    return true;
                }

                @Override
                public void onProgressChanged(WebView view, int progress) {
                    if (tvBrowserMode && browserProgress != null) {
                        browserProgress.setProgress(progress);
                        browserProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
                    }
                }

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
                    keepPersistentBrandBadgeOnTop();
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
        keepPersistentBrandBadgeOnTop();
        Toast.makeText(this, "تعذّر استخراج رابط مباشر؛ جارٍ تجربة تشغيل صفحة الفيديو", Toast.LENGTH_LONG).show();
    }

    private void openRequestedPopup(WebView popup) {
        if (popup == null || browserOverlay == null || !tvBrowserMode) return;
        if (activePopupWebView != null) {
            try { browserOverlay.removeView(activePopupWebView); activePopupWebView.destroy(); } catch (Exception ignored) { }
        }
        if (pendingPopupWebView == popup) pendingPopupWebView = null;
        activePopupWebView = popup;
        popup.setBackgroundColor(Color.BLACK);
        popup.setFocusable(true);
        popup.setFocusableInTouchMode(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        browserOverlay.addView(popup, lp);
        popup.bringToFront();
        popup.requestFocus();
        Toast.makeText(this, "النافذة المنبثقة مفتوحة — اضغط رجوع للعودة", Toast.LENGTH_SHORT).show();
    }

    private void discardRequestedPopup(WebView popup) {
        if (pendingPopupWebView == popup) pendingPopupWebView = null;
        if (popup == null) return;
        try { popup.stopLoading(); popup.destroy(); } catch (Exception ignored) { }
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
        queueIncomingMedia(mediaUrl, audioUrl, headers, null);
    }

    private void queueIncomingMedia(String mediaUrl, String audioUrl,
                                    java.util.Map<String, String> headers, String sourceUrl) {
        extractionStatus = "preparing";
        playbackStatusMessage = "Receiver استلم الرابط؛ جاري تجهيز مشغّل الفيديو.";
        persistExtractionState();
        pendingBrowserUrl = "";
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        // The receiver is reachable on the local network, so accept only remote HTTP(S)
        // URLs from incoming requests; never pass file/content/javascript or other schemes
        // to ExoPlayer or the social-video resolver.
        String originalMediaUrl = mediaUrl;
        String originalAudioUrl = audioUrl;
        currentPlaybackFallbackUrl = isYouTubeUrl(sourceUrl) ? sourceUrl
                : (isYouTubeUrl(mediaUrl) ? mediaUrl : "");
        pendingPlaybackHeaders = headers == null ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(headers);
        if (mediaUrl != null && !mediaUrl.trim().isEmpty() && !isHttpUrl(mediaUrl)) {
            mediaUrl = null;
        }
        if (audioUrl != null && !audioUrl.trim().isEmpty() && !isHttpUrl(audioUrl)) {
            audioUrl = null;
        }
        // YouTube links now enter the same direct-stream extraction pipeline first.
        // The original page URL is retained separately for SmartTube fallback.

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
            extractionStatus = "preparing";
            playbackStatusMessage = "تم تحديد رابط مباشر؛ جاري تحميله في مشغّل الفيديو.";
            persistExtractionState();
        }
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()
                && !isImageUrl(mediaUrl) && !isDirectStreamUrl(mediaUrl)
                && !isPhoneLocalMediaUrl(mediaUrl)
                // A separate audio URL is an explicit DASH A/V pair; do not resolve
                // the video URL alone or the companion audio track would be discarded.
                && (audioUrl == null || audioUrl.trim().isEmpty())
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
        // Prefer an installed YouTube / SmartTube client so playback is handled by
        // the TV app rather than treating a YouTube page or googlevideo track as a
        // normal ExoPlayer URL.
        String[] preferredPackages = {
                "com.google.android.youtube.tv",
                "com.amazon.firetv.youtube",
                "com.google.android.youtube",
                "com.liskovsoft.smarttubetv",
                "com.liskovsoft.smarttubetv.beta"
        };
        for (String packageName : preferredPackages) {
            try {
                android.content.Intent target = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, Uri.parse(videoUrl));
                target.setPackage(packageName);
                target.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(target);
                Log.i(TAG, "Opened YouTube URL with " + packageName);
                return;
            } catch (Exception ignored) {
                // Try the next known YouTube-compatible package.
            }
        }

        // No compatible TV app accepted the link. Keep the user inside Receiver:
        // its embedded WebView can inspect the page and hand a discovered stream
        // to the existing ExoPlayer fallback path.
        Log.i(TAG, "No installed YouTube app accepted the URL; opening Receiver browser");
        pendingBrowserUrl = videoUrl;
        try {
            openTvBrowser();
            Toast.makeText(this,
                    "لا يوجد تطبيق YouTube متوافق؛ جاري فتح الفيديو داخل متصفح الريسيفر.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "Receiver browser fallback failed for YouTube", e);
            Toast.makeText(this, "تعذّر فتح فيديو YouTube داخل الريسيفر.", Toast.LENGTH_LONG).show();
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

        // Consistent hardware-remote controls while the native ExoPlayer surface is active.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                && playerView != null && playerView.getVisibility() == View.VISIBLE
                && currentPlaybackHasVideo && player != null) {
            int key = event.getKeyCode();
            if (key == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePlaybackFromRemote();
                return true;
            }
            // OK/Enter is left to the focused native Play/Pause button or the
            // focused top-row action. DPAD arrows remain native focus navigation.
            if ((key == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                    || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && (!playerControllerVisible || getCurrentFocus() == null
                    || getCurrentFocus() == playerView)) {
                togglePlaybackFromRemote();
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP
                    || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    || key == android.view.KeyEvent.KEYCODE_DPAD_LEFT
                    || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                playerView.showController();
            }
            if (key == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                seekByConfiguredIncrement(1);
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_MEDIA_REWIND) {
                seekByConfiguredIncrement(-1);
                return true;
            }
        }

        // Route TV remote navigation into the website instead of requiring a mouse.
        // Keep Android's native focus handling for the address bar and browser toolbar.
        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && isTvBrowserPageVisible()
                && (browserAddress == null || !browserAddress.hasFocus())) {
            WebView target = activePopupWebView != null ? activePopupWebView : fallbackWebView;
            if (target != null) {
                String direction = null;
                switch (event.getKeyCode()) {
                    case android.view.KeyEvent.KEYCODE_DPAD_UP: direction = "up"; break;
                    case android.view.KeyEvent.KEYCODE_DPAD_DOWN: direction = "down"; break;
                    case android.view.KeyEvent.KEYCODE_DPAD_LEFT: direction = "left"; break;
                    case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: direction = "right"; break;
                    case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                    case android.view.KeyEvent.KEYCODE_ENTER:
                    case android.view.KeyEvent.KEYCODE_NUMPAD_ENTER:
                        target.evaluateJavascript("window.__aboRemoteActivate&&window.__aboRemoteActivate();", null);
                        return true;
                    case android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        target.evaluateJavascript("window.__aboRemotePlayerCommand&&window.__aboRemotePlayerCommand('toggle');", null);
                        return true;
                    case android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                        target.evaluateJavascript("window.__aboRemotePlayerCommand&&window.__aboRemotePlayerCommand('forward');", null);
                        return true;
                    case android.view.KeyEvent.KEYCODE_MEDIA_REWIND:
                        target.evaluateJavascript("window.__aboRemotePlayerCommand&&window.__aboRemotePlayerCommand('rewind');", null);
                        return true;
                    case android.view.KeyEvent.KEYCODE_MENU:
                        target.evaluateJavascript("window.__aboRemotePlayerCommand&&window.__aboRemotePlayerCommand('settings');", null);
                        return true;
                    case android.view.KeyEvent.KEYCODE_INFO:
                    case android.view.KeyEvent.KEYCODE_ZOOM_IN:
                        target.evaluateJavascript("window.__aboRemotePlayerCommand&&window.__aboRemotePlayerCommand('fullscreen');", null);
                        return true;
                    default: break;
                }
                if (direction != null) {
                    target.evaluateJavascript("window.__aboRemoteMove&&window.__aboRemoteMove('" + direction + "');", null);
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleRemoteCommand(String command) {
        synchronized (playerLock) {
            // Volume buttons control Fire TV's system/media volume, not ExoPlayer's
            // per-player gain. FLAG_SHOW_UI asks Fire OS to display its volume panel.
            if ("volume_up".equals(command) || "volume_down".equals(command) || "mute".equals(command)) {
                android.media.AudioManager audioManager =
                        (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                if (audioManager != null) {
                    int adjustment = "volume_up".equals(command)
                            ? android.media.AudioManager.ADJUST_RAISE
                            : "volume_down".equals(command)
                            ? android.media.AudioManager.ADJUST_LOWER
                            : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
                    audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                            adjustment,
                            android.media.AudioManager.FLAG_SHOW_UI
                                    | android.media.AudioManager.FLAG_PLAY_SOUND);
                }
                return;
            }

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
                    if (player.isPlaying()) {
                        player.pause();
                    } else {
                        // ExoPlayer remains in STATE_ENDED after a video finishes.
                        // Calling play() alone does not rewind an ended item.
                        if (player.getPlaybackState() == Player.STATE_ENDED) {
                            player.seekToDefaultPosition();
                        }
                        player.play();
                    }
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
                    currentPlaybackUrl = "";
                    // Stop means close the current video/image and return to the
                    // receiver home screen, while keeping the HTTP server alive.
                    hideImage();
                    showWelcome();
                    break;
                case "previous":
                    playQueueRelative(-1);
                    break;
                case "next":
                    playQueueRelative(1);
                    break;
                // System volume commands are handled before player-specific controls.
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

        smartTubeFallbackAttempted = false;
        currentPlaybackUrl = playbackUrl;
        currentPlaybackHasVideo = mediaUrl != null && !mediaUrl.trim().isEmpty() && !isImageUrl(mediaUrl);
        currentPlaybackAudioUrl = audioUrl == null ? "" : audioUrl;
        currentPlaybackHeaders = requestHeaders == null
                ? java.util.Collections.emptyMap() : new java.util.LinkedHashMap<>(requestHeaders);
        // Home shortcuts must never sit above or steal DPAD focus from ExoPlayer.
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        rememberPlaybackUrl(playbackUrl);
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        hideImage();

        if (fallbackWebView != null) fallbackWebView.setVisibility(View.GONE);
        if (webFullscreenView != null) closeWebFullscreen();
        welcomeBackground.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
        playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true);
        playerView.requestFocus();

        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    applySavedPlayerSettings();
                    player.addListener(new Player.Listener() {
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            if (isPlaying) {
                                extractionStatus = "playing";
                                playbackStatusMessage = "بدأ تشغيل الفيديو على شاشة Receiver.";
                                persistExtractionState();
                            }
                        }

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
                        public void onPlayerError(androidx.media3.common.PlaybackException error) {
                            String detail = error == null ? "خطأ غير معروف" : error.getErrorCodeName();
                            extractionStatus = "error";
                            playbackStatusMessage = "فشل تشغيل الفيديو: " + detail;
                            persistExtractionState();
                            Log.e(TAG, "Media3 playback failed: " + detail,
                                    error == null ? null : error);
                            runOnUiThread(() -> {
                                String fallbackUrl = currentPlaybackFallbackUrl;
                                if (!currentPlaybackHasVideo || smartTubeFallbackAttempted
                                        || !isYouTubeUrl(fallbackUrl)) {
                                    Toast.makeText(MainActivity.this,
                                            "فشل تشغيل الوسائط: " + detail,
                                            Toast.LENGTH_LONG).show();
                                    return;
                                }
                                smartTubeFallbackAttempted = true;
                                if (!tryOpenSmartTubeFallback(fallbackUrl)) {
                                    Toast.makeText(MainActivity.this,
                                            "فشل تشغيل الفيديو، ولا يوجد تطبيق SmartTube متوافق مع الرابط.",
                                            Toast.LENGTH_LONG).show();
                                }
                            });
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
            playerView.showController();
            focusPlayPauseAsPlayerHome();

        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
            showWelcome();
        }
    }

    private SharedPreferences getPlayerSettingsPrefs() {
        return getSharedPreferences("AboEzzatReceiverPlayerSettings", MODE_PRIVATE);
    }

    private void applySavedPlayerSettings() {
        if (player == null) return;
        SharedPreferences prefs = getPlayerSettingsPrefs();
        float speed = prefs.getFloat("playback_speed", 1.0f);
        player.setPlaybackParameters(new PlaybackParameters(speed));
        player.setRepeatMode(prefs.getBoolean("repeat_one", false)
                ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        int maxVideoHeight = prefs.getInt("max_video_height", 0);
        TrackSelectionParameters.Builder trackBuilder = player.getTrackSelectionParameters().buildUpon();
        trackBuilder.setMaxVideoSize(Integer.MAX_VALUE,
                maxVideoHeight <= 0 ? Integer.MAX_VALUE : maxVideoHeight);
        player.setTrackSelectionParameters(trackBuilder.build());
        long seekMs = prefs.getLong("seek_increment_ms", 10000L);
        player.setSeekBackIncrementMs(seekMs);
        player.setSeekForwardIncrementMs(seekMs);
        if (playerView != null) {
            playerView.setResizeMode(prefs.getInt("resize_mode",
                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT));
        }
    }

    private void showPlaybackSettingsDialog() {
        if (player == null || currentPlaybackUrl == null || currentPlaybackUrl.isEmpty()) {
            Toast.makeText(this, "لا يوجد محتوى يعمل حاليًا", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getPlayerSettingsPrefs();
        String speed = String.format(Locale.US, "%.2fx",
                player.getPlaybackParameters().speed);
        String repeat = player.getRepeatMode() == Player.REPEAT_MODE_ONE ? "مفعّل" : "متوقف";
        String quality = qualityLabel(prefs.getInt("max_video_height", 0));
        String[] options = {
                "📺  جودة الفيديو والبث المباشر (" + quality + ")",
                "🔊  مسار الصوت",
                "💬  مسار الترجمة",
                "↺  إعادة التشغيل من البداية",
                "⏩  سرعة التشغيل (" + speed + ")",
                "▣  أبعاد الصورة",
                "🔁  تكرار الفيديو (" + repeat + ")",
                "⏪  مدة التقديم والترجيع"
        };
        new AlertDialog.Builder(this)
                .setTitle("إعدادات المشغل")
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: showPlaybackQualityDialog(); break;
                        case 1: showPlaybackTrackDialog(C.TRACK_TYPE_AUDIO); break;
                        case 2: showPlaybackTrackDialog(C.TRACK_TYPE_TEXT); break;
                        case 3: restartCurrentVideo(); break;
                        case 4: showPlaybackSpeedDialog(); break;
                        case 5: showPlaybackResizeDialog(); break;
                        case 6:
                            boolean repeatOne = player.getRepeatMode() != Player.REPEAT_MODE_ONE;
                            player.setRepeatMode(repeatOne ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
                            prefs.edit().putBoolean("repeat_one", repeatOne).apply();
                            Toast.makeText(this, repeatOne ? "تكرار الفيديو مفعّل" : "تكرار الفيديو متوقف",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 7: showSeekIncrementDialog(); break;
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private String qualityLabel(int height) {
        if (height <= 0) return "تلقائي";
        return height + "p كحد أقصى";
    }

    private void showPlaybackQualityDialog() {
        final int[] heights = {0, 2160, 1440, 1080, 720, 480, 360};
        final String[] labels = {"تلقائي — أفضل جودة متاحة", "2160p / 4K كحد أقصى",
                "1440p كحد أقصى", "1080p كحد أقصى", "720p كحد أقصى",
                "480p كحد أقصى", "360p كحد أقصى"};
        int current = getPlayerSettingsPrefs().getInt("max_video_height", 0);
        int selected = 0;
        for (int i = 0; i < heights.length; i++) if (heights[i] == current) selected = i;
        new AlertDialog.Builder(this).setTitle("جودة الفيديو والبث المباشر")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    int height = heights[which];
                    if (player != null) {
                        TrackSelectionParameters.Builder builder =
                                player.getTrackSelectionParameters().buildUpon();
                        if (height <= 0) {
                            builder.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
                        } else {
                            builder.setMaxVideoSize(Integer.MAX_VALUE, height);
                        }
                        player.setTrackSelectionParameters(builder.build());
                    }
                    getPlayerSettingsPrefs().edit().putInt("max_video_height", height).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "تم ضبط الجودة: " + labels[which], Toast.LENGTH_SHORT).show();
                }).setNegativeButton("إغلاق", null).show();
    }

    private void showPlaybackTrackDialog(int trackType) {
        if (player == null) return;
        Tracks tracks = player.getCurrentTracks();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<TrackSelectionOverride> overrides = new java.util.ArrayList<>();
        int selected = -1;
        if (trackType == C.TRACK_TYPE_TEXT) labels.add("إيقاف الترجمة");
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != trackType) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                androidx.media3.common.Format format = group.getTrackFormat(i);
                String language = format.language == null ? "" : format.language;
                String label = format.label == null || format.label.isEmpty()
                        ? language : format.label;
                if (label == null || label.isEmpty()) label = trackType == C.TRACK_TYPE_AUDIO
                        ? "صوت " + (labels.size() + 1) : "ترجمة " + (labels.size() + 1);
                labels.add(label + (language.isEmpty() ? "" : " (" + language + ")"));
                overrides.add(new TrackSelectionOverride(group.getMediaTrackGroup(), i));
                if (group.isTrackSelected(i)) selected = labels.size() - 1;
            }
        }
        if (labels.isEmpty() || (trackType == C.TRACK_TYPE_AUDIO && overrides.isEmpty())) {
            Toast.makeText(this, trackType == C.TRACK_TYPE_AUDIO
                    ? "لا توجد مسارات صوت إضافية في هذا الفيديو"
                    : "لا توجد ترجمة مدمجة في هذا الفيديو", Toast.LENGTH_SHORT).show();
            return;
        }
        if (trackType == C.TRACK_TYPE_TEXT && selected < 0) selected = 0;
        final int initial = Math.max(0, selected);
        new AlertDialog.Builder(this)
                .setTitle(trackType == C.TRACK_TYPE_AUDIO ? "اختيار مسار الصوت" : "اختيار الترجمة")
                .setSingleChoiceItems(labels.toArray(new String[0]), initial, (dialog, which) -> {
                    TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon()
                            .clearOverridesOfType(trackType);
                    if (trackType == C.TRACK_TYPE_TEXT && which == 0) {
                        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                    } else {
                        int overrideIndex = trackType == C.TRACK_TYPE_TEXT ? which - 1 : which;
                        builder.setTrackTypeDisabled(trackType, false);
                        builder.setOverrideForType(overrides.get(overrideIndex));
                    }
                    player.setTrackSelectionParameters(builder.build());
                    dialog.dismiss();
                }).setNegativeButton("إغلاق", null).show();
    }

    private void stylePlayerControllerFocus() {
        if (playerView == null) return;
        stylePlayerControllerFocusRecursive(playerView);
    }

    private void stylePlayerControllerFocusRecursive(View view) {
        if (view == null) return;
        if (!(view instanceof ViewGroup) && (view.isClickable() || view.isFocusable())) {
            view.setFocusable(true);
            view.setFocusableInTouchMode(true);
            view.setBackground(browserFocusBackground(Color.TRANSPARENT,
                    Color.TRANSPARENT, 10, false));
            view.setOnFocusChangeListener((focusedView, focused) -> {
                // Transparent at rest; a slim cool-blue outline and subtle tint
                // identify only the currently focused playback control.
                focusedView.setBackground(browserFocusBackground(
                        focused ? Color.argb(42, 112, 190, 255) : Color.TRANSPARENT,
                        focused ? Color.rgb(112, 190, 255) : Color.TRANSPARENT,
                        10, focused));
                focusedView.setElevation(focused ? dp(4) : 0);
            });
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                stylePlayerControllerFocusRecursive(group.getChildAt(i));
            }
        }
    }

    private void focusPlayPauseAsPlayerHome() {
        if (playerView == null) return;
        playerView.post(() -> {
            if (playerView == null || playerView.getVisibility() != View.VISIBLE) return;
            stylePlayerControllerFocus();
            View playPause = playerView.findViewById(androidx.media3.ui.R.id.exo_play_pause);
            if (playPause != null) {
                playPause.setFocusable(true);
                playPause.setFocusableInTouchMode(true);
                playPause.setNextFocusUpId(playerSettingsButton == null
                        ? View.NO_ID : playerSettingsButton.getId());
                if (playerSettingsButton != null) {
                    playerSettingsButton.setNextFocusDownId(playPause.getId());
                }
                if (playerSubtitleButton != null) {
                    playerSubtitleButton.setNextFocusDownId(playPause.getId());
                }
                playPause.requestFocus();
            } else {
                playerView.requestFocus();
            }
        });
    }

    private void togglePlaybackFromRemote() {
        if (player == null) return;
        synchronized (playerLock) {
            if (player.isPlaying()) player.pause();
            else {
                if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
                player.play();
            }
        }
        if (playerView != null) playerView.showController();
    }

    private void seekByConfiguredIncrement(int direction) {
        if (player == null) return;
        synchronized (playerLock) {
            long increment = player.getSeekForwardIncrement();
            long duration = player.getDuration();
            long target = Math.max(0L, player.getCurrentPosition() + (direction >= 0 ? increment : -increment));
            player.seekTo(duration > 0 ? Math.min(target, duration) : target);
        }
        if (playerView != null) playerView.showController();
    }

    private void restartCurrentVideo() {
        if (player == null) return;
        synchronized (playerLock) {
            player.seekTo(0L);
            player.play();
            if (playbackPrefs != null && currentPlaybackUrl != null) {
                playbackPrefs.edit().remove("position_" + currentPlaybackUrl.hashCode()).apply();
            }
        }
        Toast.makeText(this, "بدأ الفيديو من البداية", Toast.LENGTH_SHORT).show();
    }

    private void showPlaybackSpeedDialog() {
        final float[] values = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        final String[] labels = {"0.5x", "0.75x", "عادي (1.0x)", "1.25x", "1.5x", "1.75x", "2.0x"};
        int selected = 2;
        float current = player == null ? 1.0f : player.getPlaybackParameters().speed;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.01f) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("سرعة التشغيل")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (player != null) {
                        player.setPlaybackParameters(new PlaybackParameters(values[which]));
                        getPlayerSettingsPrefs().edit().putFloat("playback_speed", values[which]).apply();
                    }
                    dialog.dismiss();
                    Toast.makeText(this, "سرعة التشغيل: " + labels[which], Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showPlaybackResizeDialog() {
        final int[] modes = {
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL,
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        };
        String[] labels = {"ملاءمة الشاشة (بدون قص)", "ملء الشاشة", "تكبير مع قص الأطراف"};
        int current = playerView == null
                ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                : getPlayerSettingsPrefs().getInt("resize_mode", androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
        int selected = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("أبعاد الصورة")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    if (playerView != null) playerView.setResizeMode(modes[which]);
                    getPlayerSettingsPrefs().edit().putInt("resize_mode", modes[which]).apply();
                    dialog.dismiss();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showSeekIncrementDialog() {
        final int[] seconds = {10, 15, 30, 60};
        String[] labels = {"10 ثوانٍ", "15 ثانية", "30 ثانية", "60 ثانية"};
        long currentMs = getPlayerSettingsPrefs().getLong("seek_increment_ms", 10000L);
        int selected = 0;
        for (int i = 0; i < seconds.length; i++) {
            if (seconds[i] * 1000L == currentMs) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("مدة التقديم والترجيع")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    long value = seconds[which] * 1000L;
                    if (player != null) {
                        player.setSeekBackIncrementMs(value);
                        player.setSeekForwardIncrementMs(value);
                    }
                    getPlayerSettingsPrefs().edit().putLong("seek_increment_ms", value).apply();
                    dialog.dismiss();
                    Toast.makeText(this, "تم ضبط التقديم والترجيع إلى " + labels[which],
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // Arabic subtitles are searched only after the user presses the on-screen button.
    // Guest API access is provided by subtitles.website; availability/rate limits depend on that service.
    private void searchAndApplyArabicSubtitle() {
        final String videoUrl = currentPlaybackUrl;
        if (videoUrl == null || videoUrl.trim().isEmpty() || player == null) {
            Toast.makeText(this, "لا يوجد فيديو يعمل حاليًا", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = guessSubtitleTitle(videoUrl);
        if (title.isEmpty()) {
            promptSubtitleTitleAndSearch();
            return;
        }
        searchArabicSubtitle(title, videoUrl);
    }

    private void promptSubtitleTitleAndSearch() {
        EditText titleInput = new EditText(this);
        titleInput.setSingleLine(true);
        titleInput.setHint("اسم الفيلم أو المسلسل بالإنجليزي");
        titleInput.setText(guessSubtitleTitle(currentPlaybackUrl));
        new AlertDialog.Builder(this)
                .setTitle("البحث عن ترجمة عربية")
                .setMessage("اكتب اسم الفيلم أو الحلقة لو اسم الرابط غير واضح.")
                .setView(titleInput)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("بحث", (dialog, which) -> {
                    String title = titleInput.getText().toString().trim();
                    if (!title.isEmpty()) searchArabicSubtitle(title, currentPlaybackUrl);
                    else Toast.makeText(this, "اكتب اسم الفيلم أولًا", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private String guessSubtitleTitle(String mediaUrl) {
        try {
            android.net.Uri uri = android.net.Uri.parse(mediaUrl);
            String name = uri.getLastPathSegment();
            if (name == null || name.trim().isEmpty()) return "";
            try { name = URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) { }
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
            name = name.replace('_', ' ').replace('.', ' ').replace('-', ' ');
            name = name.replaceAll("(?i)\\b(480p|720p|1080p|2160p|4k|8k|web[- ]?dl|webrip|bluray|blu[- ]?ray|hdrip|x264|x265|h264|h265|hevc|aac|dts)\\b", " ");
            name = name.replaceAll("\\s+", " ").trim();
            if (name.matches("(?i)^(index|master|playlist|video|stream|manifest|segment|file|media)$")) return "";
            return name;
        } catch (Exception e) {
            return "";
        }
    }

    private void searchArabicSubtitle(String title, String videoUrl) {
        Toast.makeText(this, "جارٍ البحث عن ترجمة عربية…", Toast.LENGTH_SHORT).show();
        Thread thread = new Thread(() -> {
            String downloadUrl = null;
            String subtitleName = "";
            String errorMessage = null;
            HttpURLConnection connection = null;
            try {
                String endpoint = "https://subtitles.website/strapi/api/search/subtitles?title="
                        + java.net.URLEncoder.encode(title, "UTF-8")
                        + "&language=ar&format=srt";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(20000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "AboEzzat Receiver Android");
                int status = connection.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new java.io.IOException("Subtitle search HTTP " + status);
                }
                StringBuilder jsonText = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) jsonText.append(line);
                }
                JSONObject response = new JSONObject(jsonText.toString());
                org.json.JSONArray results = response.optJSONArray("data");
                if (results == null) results = response.optJSONArray("subtitles");
                int bestDownloads = -1;
                if (results != null) {
                    for (int i = 0; i < results.length(); i++) {
                        JSONObject item = results.optJSONObject(i);
                        if (item == null) continue;
                        String candidate = item.optString("download_url", "");
                        if (candidate.isEmpty()) candidate = item.optString("url", "");
                        if (candidate.isEmpty()) continue;
                        JSONObject language = item.optJSONObject("language");
                        String languageCode = language == null ? item.optString("language", "")
                                : language.optString("code", "");
                        String languageName = language == null ? "" : language.optString("name", "");
                        boolean arabic = "ar".equalsIgnoreCase(languageCode)
                                || "ara".equalsIgnoreCase(languageCode)
                                || "arabic".equalsIgnoreCase(languageCode)
                                || "العربية".equals(languageName);
                        // The endpoint is asked for Arabic; tolerate providers that omit language metadata.
                        if (!arabic && !languageCode.isEmpty()) continue;
                        int downloads = item.optInt("downloads", 0);
                        if (downloadUrl == null || downloads > bestDownloads) {
                            downloadUrl = candidate;
                            bestDownloads = downloads;
                            subtitleName = item.optString("release",
                                    item.optString("film_title", title));
                        }
                    }
                }
                if (downloadUrl == null) {
                    errorMessage = "لم يتم العثور على ترجمة عربية لهذا الفيلم.";
                } else {
                    if (!downloadUrl.startsWith("http://") && !downloadUrl.startsWith("https://")) {
                        if (!downloadUrl.startsWith("/")) downloadUrl = "/" + downloadUrl;
                        downloadUrl = "https://subtitles.website" + downloadUrl;
                    }
                    File cacheDir = new File(getCacheDir(), "arabic_subtitles");
                    if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                        throw new java.io.IOException("Could not create subtitle cache");
                    }
                    File subtitleFile = new File(cacheDir, "subtitle_" + Math.abs(videoUrl.hashCode()) + ".srt");
                    HttpURLConnection downloadConnection = (HttpURLConnection) new URL(downloadUrl).openConnection();
                    downloadConnection.setConnectTimeout(15000);
                    downloadConnection.setReadTimeout(30000);
                    downloadConnection.setInstanceFollowRedirects(true);
                    downloadConnection.setRequestProperty("User-Agent", "AboEzzat Receiver Android");
                    try {
                        int downloadStatus = downloadConnection.getResponseCode();
                        if (downloadStatus < 200 || downloadStatus >= 300) {
                            throw new java.io.IOException("Subtitle download HTTP " + downloadStatus);
                        }
                        try (InputStream raw = downloadConnection.getInputStream();
                             BufferedInputStream buffered = new BufferedInputStream(raw)) {
                            buffered.mark(4);
                            int first = buffered.read();
                            int second = buffered.read();
                            buffered.reset();
                            if (first == 'P' && second == 'K') {
                                boolean extracted = false;
                                try (ZipInputStream zip = new ZipInputStream(buffered)) {
                                    ZipEntry entry;
                                    while ((entry = zip.getNextEntry()) != null) {
                                        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".srt")) {
                                            try (FileOutputStream out = new FileOutputStream(subtitleFile)) {
                                                byte[] buffer = new byte[8192];
                                                int count;
                                                while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                                            }
                                            extracted = true;
                                            break;
                                        }
                                    }
                                }
                                if (!extracted) throw new java.io.IOException("ZIP did not contain SRT");
                            } else {
                                try (FileOutputStream out = new FileOutputStream(subtitleFile)) {
                                    byte[] buffer = new byte[8192];
                                    int count;
                                    while ((count = buffered.read(buffer)) != -1) out.write(buffer, 0, count);
                                }
                            }
                        }
                    } finally {
                        downloadConnection.disconnect();
                    }
                    if (subtitleFile.length() < 20) throw new java.io.IOException("Subtitle file is empty");
                    final File readyFile = subtitleFile;
                    final String readyName = subtitleName;
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        if (!videoUrl.equals(currentPlaybackUrl)) {
                            Toast.makeText(MainActivity.this, "الفيديو اتغير قبل اكتمال البحث؛ لم يتم تطبيق الترجمة.", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (applySubtitleFile(readyFile)) {
                            Toast.makeText(MainActivity.this, "تم تشغيل الترجمة العربية" +
                                    (readyName.isEmpty() ? "" : ": " + readyName), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "تم تنزيل الترجمة لكن تعذّر إضافتها للفيديو.", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            } catch (Exception e) {
                Log.w(TAG, "Arabic subtitle search/download failed", e);
                if (errorMessage == null) errorMessage = "تعذّر البحث أو تنزيل الترجمة. جرّب لاحقًا.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (errorMessage != null) {
                final String message = errorMessage;
                runOnUiThread(() -> {
                    if (!isFinishing()) new AlertDialog.Builder(MainActivity.this)
                            .setTitle("الترجمة العربية")
                            .setMessage(message)
                            .setPositiveButton("حسنًا", null)
                            .show();
                });
            }
        }, "ReceiverArabicSubtitleSearch");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean applySubtitleFile(File subtitleFile) {
        if (player == null || currentPlaybackUrl == null || currentPlaybackUrl.trim().isEmpty()
                || subtitleFile == null || !subtitleFile.isFile()) return false;
        try {
            synchronized (playerLock) {
                long position = Math.max(0L, player.getCurrentPosition());
                boolean wasPlaying = player.isPlaying();
                savePlaybackPositionLocked();
                MediaItem.SubtitleConfiguration subtitleConfig =
                        new MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitleFile))
                                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                                .setLanguage("ar")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build();
                MediaItem videoItem = new MediaItem.Builder()
                        .setUri(currentPlaybackUrl)
                        .setSubtitleConfigurations(java.util.Collections.singletonList(subtitleConfig))
                        .build();
                DefaultMediaSourceFactory factory = createMediaSourceFactory(currentPlaybackHeaders);
                MediaSource videoSource = factory.createMediaSource(videoItem);
                if (currentPlaybackAudioUrl != null && !currentPlaybackAudioUrl.trim().isEmpty()) {
                    MediaSource audioSource = factory.createMediaSource(
                            MediaItem.fromUri(currentPlaybackAudioUrl));
                    player.setMediaSource(new MergingMediaSource(true, videoSource, audioSource));
                } else {
                    player.setMediaSource(videoSource);
                }
                player.prepare();
                player.seekTo(position);
                if (wasPlaying) player.play();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not apply Arabic subtitle", e);
            return false;
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
                        || "accept".equals(key) || "accept-language".equals(key) || "cookie".equals(key))
                        && entry.getValue() != null
                        && entry.getValue().length() <= ("cookie".equals(key) ? 8192 : 2048)
                        && !entry.getValue().contains("\\r") && !entry.getValue().contains("\\n")) {
                    safe.put(entry.getKey(), entry.getValue());
                }
            }
            httpFactory.setDefaultRequestProperties(safe);
        }
        return new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, httpFactory));
    }

    // الصورة تظل ظاهرة بينما يشغّل ExoPlayer الصوت في الخلفية.

    /**
     * YouTube-only fallback: keep AboEzzat Receiver as the primary player.
     * SmartTube is attempted only after Media3 reports a playback error.
     * Ordinary MP4/HLS/DASH links are not handed to SmartTube.
     */
    /**
     * SmartTube is only a fallback for the original YouTube page URL.
     * Arbitrary direct MP4/HLS links remain Receiver/ExoPlayer content.
     */
    private boolean tryOpenSmartTubeFallback(String failedUrl) {
        if (!isYouTubeUrl(failedUrl)) return false;
        Uri uri;
        try {
            uri = Uri.parse(failedUrl.trim());
        } catch (Exception ignored) {
            return false;
        }
        String[] smartTubePackages = {
                "com.liskovsoft.smarttubetv.beta",
                "com.liskovsoft.smarttubetv"
        };
        for (String packageName : smartTubePackages) {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, uri);
                intent.setPackage(packageName);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    Log.i(TAG, "Opened original YouTube page in SmartTube: " + packageName);
                    return true;
                }
            } catch (Exception e) {
                Log.w(TAG, "SmartTube fallback failed for " + packageName, e);
            }
        }
        return false;
    }

    private void playAudioAlongsideImage(String audioUrl) {
        currentPlaybackUrl = audioUrl;
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
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
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        stopCurrentPlayer();
        resetImageTransform();

        playerView.setVisibility(View.GONE);
        if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
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
        tvBrowserMode = false;
        if (browserOverlay != null) browserOverlay.setVisibility(View.GONE);
        // Returning home must stop playback, not merely hide the player UI.
        stopCurrentPlayer();
        hideImage();
        if (webFullscreenView != null) closeWebFullscreen();
        if (fallbackWebView != null) {
            fallbackWebView.stopLoading();
            fallbackWebView.setVisibility(View.GONE);
        }
        playerView.setVisibility(View.GONE);
        if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.VISIBLE);
        connectionCard.setVisibility(View.VISIBLE);
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.VISIBLE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.VISIBLE);

        updateConnectionInfo();
        if (settingsHomeButton != null) {
            settingsHomeButton.post(() -> {
                if (!isFinishing() && settingsHomeButton.getVisibility() == View.VISIBLE) {
                    settingsHomeButton.requestFocus();
                }
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (closeWebFullscreen()) return;
        if (activePopupWebView != null) {
            try { browserOverlay.removeView(activePopupWebView); activePopupWebView.destroy(); } catch (Exception ignored) { }
            activePopupWebView = null;
            if (fallbackWebView != null) fallbackWebView.requestFocus();
            return;
        }
        if (popupRequestDialog != null && popupRequestDialog.isShowing()) {
            popupRequestDialog.dismiss();
            return;
        }

        boolean browserVisible = tvBrowserMode && browserOverlay != null
                && browserOverlay.getVisibility() == View.VISIBLE
                && fallbackWebView != null && fallbackWebView.getVisibility() == View.VISIBLE;
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

        if (popupRequestDialog != null) { popupRequestDialog.dismiss(); popupRequestDialog = null; }
        if (pendingPopupWebView != null) { try { pendingPopupWebView.destroy(); } catch (Exception ignored) { } pendingPopupWebView = null; }
        if (activePopupWebView != null) { try { activePopupWebView.destroy(); } catch (Exception ignored) { } activePopupWebView = null; }
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
