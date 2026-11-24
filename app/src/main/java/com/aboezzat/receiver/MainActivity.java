
package com.aboezzat.receiver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.ActivityNotFoundException;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
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
import okhttp3.OkHttpClient;

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
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
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
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.net.URL;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@UnstableApi
public class MainActivity extends Activity {

    private static final String TAG = "AboEzzatReceiver";
    private volatile boolean extractionInProgress = false;
    private volatile int extractionAttemptCount = 0;
    private volatile String extractionStatus = "idle";
    private volatile String lastJsonIndexDiagnostic = "";
    // URLs whose response body was positively identified as an HLS playlist,
    // even when the server gives them a misleading .json suffix or MIME type.
    private final java.util.Set<String> detectedHlsManifestUrls =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private volatile String lastIncomingIntentDiagnostic = "";
    private volatile String playbackStatusMessage = "";
    private volatile String originalIncomingMediaUrl = "";
    private volatile java.util.Map<String, String> pendingPlaybackHeaders = java.util.Collections.emptyMap();
    private final Object tikTokCdnDiagnosticLock = new Object();
    private String lastTikTokCdnResponseDiagnostic = "";
    private long lastTikTokCdnBytesReceived = 0L;
    private String lastTikTokCdnInitialBytes = "not captured";
    private boolean lastTikTokCdnTransferEnded = false;
    private boolean lastTikTokCdnReachedEof = false;
    private long lastTikTokCdnTransferStartMs = 0L;
    private long lastTikTokCdnTransferDurationMs = -1L;
    private String lastTikTokCdnHttpStatus = "unknown";
    private String lastTikTokCdnReadError = "none";
    private String lastTikTokCdnFlvProbe = "not captured";

    private FrameLayout root;
    private LinearLayout welcomeScreen;

    private ImageView welcomeBackground;
    // Persistent circular brand portrait: uses the standalone app icon artwork.
    private FrameLayout persistentBrandBadge;
    private LinearLayout connectionCard;
    private TextView ipText;
    private TextView receiverStatusText;

    private PlayerView playerView;
    private FrameLayout playerBufferingOverlay;
    private TextView playerBufferingText;
    private TextView playerSubtitleButton;
    private boolean currentPlaybackHasVideo = false;
    private String currentPlaybackAudioUrl = "";
    private java.util.Map<String, String> currentPlaybackHeaders = java.util.Collections.emptyMap();
    private final java.util.ArrayList<String> playbackQueue = new java.util.ArrayList<>();
    private int playbackQueueIndex = -1;
    private static final class PlaybackHistoryItem {
        String videoUrl;
        String audioUrl;
        String title;
        java.util.Map<String, String> headers;
        PlaybackHistoryItem(String videoUrl, String audioUrl, String title) {
            this(videoUrl, audioUrl, title, java.util.Collections.emptyMap());
        }
        PlaybackHistoryItem(String videoUrl, String audioUrl, String title,
                            java.util.Map<String, String> headers) {
            this.videoUrl = videoUrl;
            this.audioUrl = audioUrl;
            this.title = title;
            this.headers = headers == null ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(headers);
        }
    }
    private final java.util.ArrayList<PlaybackHistoryItem> playbackHistoryItems = new java.util.ArrayList<>();
    private AlertDialog historyDialog;
    private int historyScrollPosition = 0;
    private boolean reopenHistoryAfterPlayback = false;
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
    private final Handler playerControlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hidePlayerControlsRunnable = () -> {
        if (playerView != null && playerView.getVisibility() == View.VISIBLE) {
            playerView.hideController();
        }
    };
    private TextView browserShortcut;
    private TextView settingsHomeButton;
    private TextView browserHomeButton;
    private TextView playerSettingsButton;
    private TextView playerDownloadButton;
    private TextView playerSubtitleControlButton;
    private androidx.appcompat.widget.AppCompatImageButton playerDownloadControlButton;
    private TextView playerSettingsControlButton;
    private TextView playerRepeatControlButton;
    private final Handler imageControlsHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideImageControlsRunnable = () -> setImageControlsVisible(false);
    private boolean imageControlsVisible = false;
    private String currentImageUrl = "";
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
    private volatile TikTokFfmpegHlsPipeline tikTokFfmpegHlsPipeline;
    private volatile long tikTokFfmpegPlaybackAttemptToken = 0L;
    private volatile boolean tikTokFfmpegPlaybackStarted = false;
    private volatile boolean tikTokFfmpegFailureRecorded = false;
    private volatile boolean externalVlcFallbackLaunched = false;
    // Keep the selected level separately from mute state.
    private float lastNonZeroVolume = 1.0f;
    private boolean muted = false;
    private boolean playerControllerVisible = false;
    private boolean isDashManifestUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".mpd") || lower.contains("application/dash+xml") || lower.contains("format=mpd");
    }

    private String buildDashTrackDiagnostic() {
        if (player == null) return "المشغّل غير مهيأ.";
        StringBuilder out = new StringBuilder();
        int state = player.getPlaybackState();
        out.append("حالة المشغّل: ").append(state == Player.STATE_READY ? "READY" : state == Player.STATE_BUFFERING ? "BUFFERING" : state == Player.STATE_IDLE ? "IDLE" : state == Player.STATE_ENDED ? "ENDED" : "UNKNOWN");
        out.append("\nالتحميل مستمر: ").append(player.isLoading() ? "نعم" : "لا");
        out.append("\nالوقت الحالي: ").append(player.getCurrentPosition() / 1000).append(" ثانية");
        androidx.media3.common.VideoSize size = player.getVideoSize();
        out.append("\nمقاس الفيديو: ").append(size.width).append("x").append(size.height);
        boolean videoSelected = false, audioSelected = false, drmDetected = false;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            int type = group.getType();
            String kind = type == C.TRACK_TYPE_VIDEO ? "فيديو" : type == C.TRACK_TYPE_AUDIO ? "صوت" : type == C.TRACK_TYPE_TEXT ? "ترجمة" : "مسار آخر";
            out.append("\n").append(kind).append(": ");
            for (int i = 0; i < group.length; i++) {
                androidx.media3.common.Format f = group.getTrackFormat(i);
                boolean selected = group.isTrackSelected(i);
                boolean supported = group.isTrackSupported(i);
                if (type == C.TRACK_TYPE_VIDEO && selected) videoSelected = true;
                if (type == C.TRACK_TYPE_AUDIO && selected) audioSelected = true;
                if (f.drmInitData != null) drmDetected = true;
                if (i > 0) out.append(" | ");
                out.append(f.sampleMimeType == null ? "نوع غير معروف" : f.sampleMimeType);
                if (f.codecs != null) out.append(" (").append(f.codecs).append(")");
                if (f.width > 0 && f.height > 0) out.append(" ").append(f.width).append("x").append(f.height);
                out.append(selected ? " [محدد]" : "").append(supported ? "" : " [غير مدعوم]");
            }
            if (group.length == 0) out.append("لا توجد صيغ");
        }
        out.append("\nمسار فيديو محدد: ").append(videoSelected ? "نعم" : "لا");
        out.append("\nمسار صوت محدد: ").append(audioSelected ? "نعم" : "لا");
        out.append("\nمساحة التحميل المؤقت: ").append(Math.max(0L, player.getBufferedPosition() - player.getCurrentPosition()) / 1000).append(" ثانية");
        out.append("\nالمدة: ").append(player.getDuration() == C.TIME_UNSET ? "غير معروفة" : (player.getDuration() / 1000) + " ثانية");
        out.append("\nبيانات DRM ظاهرة: ").append(drmDetected ? "نعم" : "لا");
        androidx.media3.common.PlaybackException playerError = player.getPlayerError();
        out.append("\nآخر خطأ للمشغّل: ").append(playerError == null ? "لا يوجد خطأ مسجل" : getPlaybackFailureDiagnostic(playerError));
        return out.toString();
    }

    private void showDashPlaybackDiagnostic(String requestedUrl) {
        if (isFinishing() || isDestroyed() || player == null || !requestedUrl.equals(currentPlaybackUrl)) return;
        // Do not interrupt healthy DASH playback. Show the diagnostic only when
        // Media3 has failed or the stream is still idle/buffering after startup.
        int playbackState = player.getPlaybackState();
        if (player.getPlayerError() == null
                && playbackState != Player.STATE_IDLE
                && playbackState != Player.STATE_BUFFERING) return;
        String details = buildDashTrackDiagnostic();
        Log.i(TAG, "DASH playback diagnostic: " + details.replace('\n', ' '));
        new AlertDialog.Builder(this).setTitle("تشخيص بث DASH")
                .setMessage(details + "\n\nانسخ التشخيص وأرسله لتحديد سبب الشاشة السوداء.")
                .setPositiveButton("نسخ التشخيص", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("DASH diagnostic", details));
                        Toast.makeText(this, "تم نسخ التشخيص", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("إغلاق", null).show();
    }



    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean networkCallbackRegistered = false;

    private final Object playerLock = new Object();
    private SharedPreferences playbackPrefs;
    private String currentPlaybackUrl = "";
    private String currentPlaybackFallbackUrl = "";
    private boolean smartTubeFallbackAttempted = false;
    private final Handler sleepTimerHandler = new Handler(Looper.getMainLooper());
    private Runnable sleepTimerRunnable;
    private final Handler playbackProgressHandler = new Handler(Looper.getMainLooper());
    private final Handler startupBufferHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingStartupPlayback;
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
    private String pendingPlaybackSourceUrl;
    private Runnable pendingPlayback;

    private int dp(float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        );
    }


    private static final String FAILED_MEDIA_LOG_PREF = "failed_media_log_v1";
    private static final int MAX_FAILED_MEDIA_LOG_ENTRIES = 40;

    private String redactSensitiveUrl(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            Uri uri = Uri.parse(value.trim());
            Uri.Builder builder = uri.buildUpon().clearQuery();
            java.util.Set<String> sensitive = new java.util.HashSet<>(java.util.Arrays.asList(
                    "token", "access_token", "auth", "authorization", "key", "api_key",
                    "apikey", "signature", "sig", "sign", "expire", "expires", "_session_id", "session_id", "abr_pts", "session", "sessionid", "password",
                    "passwd", "secret", "jwt", "expiresig"));
            for (String name : uri.getQueryParameterNames()) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (sensitive.contains(lower) || lower.endsWith("_token")
                        || lower.endsWith("_secret") || lower.endsWith("_signature")) {
                    builder.appendQueryParameter(name, "[REDACTED]");
                } else {
                    for (String parameterValue : uri.getQueryParameters(name)) {
                        builder.appendQueryParameter(name, parameterValue);
                    }
                }
            }
            return builder.build().toString();
        } catch (Exception ignored) {
            return value.replaceAll("(?i)(token|key|auth|signature|sig|session|password)=([^&\\s]+)",
                    "$1=[REDACTED]");
        }
    }

    private void recordReceiverFailure(String phase, String requestedUrl,
                                       String resolvedUrl, String reason) {
        if (requestedUrl == null || requestedUrl.trim().isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences("receiver_settings", MODE_PRIVATE);
            org.json.JSONArray old = new org.json.JSONArray(prefs.getString(FAILED_MEDIA_LOG_PREF, "[]"));
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("time", System.currentTimeMillis());
            entry.put("phase", phase == null ? "غير معروف" : phase);
            entry.put("requested_url", requestedUrl);
            entry.put("resolved_url", resolvedUrl == null ? "" : resolvedUrl);
            entry.put("reason", reason == null ? "" : reason);
            entry.put("incoming_intent", lastIncomingIntentDiagnostic == null ? "" : lastIncomingIntentDiagnostic);
            if (phase != null && phase.toLowerCase(Locale.ROOT).contains("tiktok")) {
                entry.put("technical_diagnostic", buildTikTokFailureDiagnostic());
            }
            entry.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            next.put(entry);
            for (int i = 0; i < old.length() && next.length() < MAX_FAILED_MEDIA_LOG_ENTRIES; i++) {
                Object item = old.opt(i);
                if (item instanceof org.json.JSONObject) next.put(item);
            }
            prefs.edit().putString(FAILED_MEDIA_LOG_PREF, next.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "Could not save failed-media diagnostic entry");
        }
    }

    private org.json.JSONArray readFailedMediaLog() {
        try {
            return new org.json.JSONArray(getSharedPreferences("receiver_settings", MODE_PRIVATE)
                    .getString(FAILED_MEDIA_LOG_PREF, "[]"));
        } catch (Exception ignored) {
            return new org.json.JSONArray();
        }
    }

    private String buildFailedMediaReport(org.json.JSONObject entry, boolean includeFullUrls) {
        String requested = entry.optString("requested_url", "");
        String resolved = entry.optString("resolved_url", "");
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        StringBuilder report = new StringBuilder();
        report.append("AboEzzat Receiver - Failed Media Diagnostic\n")
                .append("Time: ").append(format.format(new java.util.Date(entry.optLong("time")))).append("\n")
                .append("Phase: ").append(entry.optString("phase", "unknown")).append("\n")
                .append("Reason: ").append(entry.optString("reason", "not available")).append("\n")
                .append("App version: ").append(entry.optString("version", "unknown")).append("\n")
                .append("Requested URL: ").append(includeFullUrls ? requested : redactSensitiveUrl(requested)).append("\n");
        String intentShape = entry.optString("incoming_intent", "");
        if (!intentShape.isEmpty()) report.append("Incoming intent: ").append(intentShape).append("\n");
        String technical = entry.optString("technical_diagnostic", "");
        if (!technical.isEmpty()) report.append("Technical diagnostic:\n").append(technical).append("\n");
        if (!resolved.isEmpty()) {
            report.append("Resolved URL: ").append(includeFullUrls ? resolved : redactSensitiveUrl(resolved)).append("\n");
        }
        report.append("Device: ").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL).append(" / Android ")
                .append(android.os.Build.VERSION.RELEASE);
        return report.toString();
    }

    private void shareFailedMediaReport(String report) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, "AboEzzat Receiver failure diagnostic");
        intent.putExtra(android.content.Intent.EXTRA_TEXT, report);
        startActivity(android.content.Intent.createChooser(intent, "إرسال تقرير الفشل"));
    }

    private void showFailedMediaLog() {
        org.json.JSONArray entries = readFailedMediaLog();
        if (entries.length() == 0) {
            new AlertDialog.Builder(this).setTitle("سجل الروابط الفاشلة")
                    .setMessage("لسه مفيش محاولات فاشلة مسجلة. أي رابط يفشل استخراجه أو تشغيله هيتسجل هنا تلقائيًا.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        String[] labels = new String[entries.length()];
        for (int i = 0; i < entries.length(); i++) {
            org.json.JSONObject item = entries.optJSONObject(i);
            if (item == null) item = new org.json.JSONObject();
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", Locale.getDefault());
            labels[i] = format.format(new java.util.Date(item.optLong("time"))) + " — "
                    + item.optString("phase", "فشل") + " — "
                    + redactSensitiveUrl(item.optString("requested_url", ""));
        }
        new AlertDialog.Builder(this).setTitle("سجل الروابط الفاشلة (" + entries.length() + ")")
                .setItems(labels, (dialog, which) -> showFailedMediaEntry(entries.optJSONObject(which)))
                .setNeutralButton("مسح السجل", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("مسح سجل الفشل؟")
                                .setMessage("سيتم حذف السجلات المحفوظة على هذا الجهاز.")
                                .setPositiveButton("مسح", (d, w) -> {
                                    getSharedPreferences("receiver_settings", MODE_PRIVATE).edit()
                                            .remove(FAILED_MEDIA_LOG_PREF).apply();
                                    Toast.makeText(this, "تم مسح سجل الفشل", Toast.LENGTH_SHORT).show();
                                }).setNegativeButton("إلغاء", null).show())
                .setNegativeButton("إغلاق", null).show();
    }

    private void showFailedMediaEntry(org.json.JSONObject entry) {
        if (entry == null) return;
        String safeReport = buildFailedMediaReport(entry, false);
        String fullReport = buildFailedMediaReport(entry, true);
        new AlertDialog.Builder(this).setTitle("تفاصيل محاولة الفشل")
                .setMessage(safeReport + "\n\nالرابط الكامل محفوظ محليًا. التقرير العادي يخفي القيم الحساسة مثل token.")
                .setPositiveButton("مشاركة تقرير آمن", (dialog, which) -> shareFailedMediaReport(safeReport))
                .setNeutralButton("خيارات الرابط", (dialog, which) ->
                        new AlertDialog.Builder(this).setTitle("الرابط الكامل")
                                .setMessage("الرابط قد يحتوي على token أو بيانات وصول مؤقتة. لا تشاركه إلا مع جهة تثق بها.")
                                .setPositiveButton("مشاركة بالرابط الكامل", (d, w) -> shareFailedMediaReport(fullReport))
                                .setNeutralButton("نسخ الرابط الكامل", (d, w) -> {
                                    android.content.ClipboardManager clipboard =
                                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                                    if (clipboard != null) {
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                                "Original failed URL", entry.optString("requested_url", "")));
                                        Toast.makeText(this, "تم نسخ الرابط الكامل", Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton("إلغاء", null).show())
                .setNegativeButton("إغلاق", null).show();
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
                "🧾  سجل الروابط الفاشلة والتشخيص",
                "ℹ  معلومات الإصدار",
                "🚀  فتح الفيديو تلقائيًا: " + ((android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) ? "مسموح" : "يحتاج إذن"),
                "🔐  إظهار كود الربط مع كاست"
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
                        case 6: showFailedMediaLog(); break;
                        case 7:
                            try {
                                android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
                                String version = info.versionName == null ? "غير معروف" : info.versionName;
                                long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                                new AlertDialog.Builder(this).setTitle("معلومات الريسيفر")
                                        .setMessage("AboEzzat Receiver\nالإصدار: " + version + "\nرقم البناء: " + code)
                                        .setPositiveButton("حسنًا", null).show();
                            } catch (Exception e) { Toast.makeText(this, "تعذّر قراءة معلومات الإصدار", Toast.LENGTH_SHORT).show(); }
                            break;
                        case 8: configureAutoForegroundPlayback(); break;
                        case 9: showSharePairingCode(); break;
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

        final String displayedCode = code;
        final String certificateFingerprint;
        try {
            certificateFingerprint = ReceiverTlsIdentity.getCertificateFingerprint();
        } catch (Exception identityError) {
            Log.e("AboEzzatReceiver", "Receiver TLS identity is unavailable", identityError);
            Toast.makeText(this, "تعذر تجهيز هوية الاتصال المشفر. أعد تشغيل التطبيق وحاول مرة أخرى.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        long remaining = Math.max(0L, (expiresAt - System.currentTimeMillis() + 59999L) / 60000L);
        int pad = (int) (getResources().getDisplayMetrics().density * 18);

        android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);

        android.widget.TextView instructions = new android.widget.TextView(this);
        instructions.setText("انسخ كل قيمة من الزر الموجود تحتها، ثم أدخلها في أبو عزت كاست عند ربط الريسيفر لأول مرة.");
        instructions.setTextSize(15);
        instructions.setTextColor(Color.WHITE);
        panel.addView(instructions);

        android.widget.TextView codeLabel = new android.widget.TextView(this);
        codeLabel.setText("كود الربط");
        codeLabel.setTextSize(16);
        codeLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        codeLabel.setTextColor(Color.WHITE);
        android.widget.LinearLayout.LayoutParams labelParams =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        labelParams.topMargin = pad;
        panel.addView(codeLabel, labelParams);

        android.widget.TextView codeValue = new android.widget.TextView(this);
        codeValue.setText(displayedCode);
        codeValue.setTextSize(25);
        codeValue.setGravity(android.view.Gravity.CENTER);
        codeValue.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        codeValue.setTextColor(Color.WHITE);
        codeValue.setPadding(pad, pad / 2, pad, pad / 2);
        codeValue.setBackgroundColor(Color.rgb(35, 48, 68));
        panel.addView(codeValue, new android.widget.LinearLayout.LayoutParams(-1, -2));

        android.widget.Button copyCode = new android.widget.Button(this);
        copyCode.setText("نسخ كود الربط");
        copyCode.setAllCaps(false);
        panel.addView(copyCode, new android.widget.LinearLayout.LayoutParams(-1, -2));
        copyCode.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "AboEzzat Receiver pairing code", displayedCode));
                Toast.makeText(this, "تم نسخ كود الربط", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "تعذر الوصول إلى الحافظة", Toast.LENGTH_SHORT).show();
            }
        });

        android.widget.TextView fingerprintLabel = new android.widget.TextView(this);
        fingerprintLabel.setText("بصمة الاتصال (SHA-256)");
        fingerprintLabel.setTextSize(16);
        fingerprintLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        fingerprintLabel.setTextColor(Color.WHITE);
        android.widget.LinearLayout.LayoutParams fingerprintLabelParams =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        fingerprintLabelParams.topMargin = pad;
        panel.addView(fingerprintLabel, fingerprintLabelParams);

        android.widget.TextView fingerprintValue = new android.widget.TextView(this);
        fingerprintValue.setText(certificateFingerprint);
        fingerprintValue.setTextSize(13);
        fingerprintValue.setTypeface(android.graphics.Typeface.MONOSPACE);
        fingerprintValue.setTextColor(Color.WHITE);
        fingerprintValue.setGravity(android.view.Gravity.CENTER);
        fingerprintValue.setTextIsSelectable(true);
        fingerprintValue.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        fingerprintValue.setPadding(pad, pad, pad, pad);
        fingerprintValue.setBackgroundColor(Color.rgb(35, 48, 68));
        panel.addView(fingerprintValue, new android.widget.LinearLayout.LayoutParams(-1, -2));

        android.widget.Button copyFingerprint = new android.widget.Button(this);
        copyFingerprint.setText("نسخ البصمة");
        copyFingerprint.setAllCaps(false);
        panel.addView(copyFingerprint, new android.widget.LinearLayout.LayoutParams(-1, -2));
        copyFingerprint.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "AboEzzat Receiver TLS fingerprint", certificateFingerprint));
                Toast.makeText(this, "تم نسخ بصمة الاتصال", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "تعذر الوصول إلى الحافظة", Toast.LENGTH_SHORT).show();
            }
        });

        android.widget.TextView expiry = new android.widget.TextView(this);
        expiry.setText("صالح لمدة تصل إلى " + remaining
                + " دقائق، ويسمح بخمس محاولات فقط. لا تشارك كود الربط مع أي شخص.");
        expiry.setTextSize(13);
        expiry.setTextColor(Color.LTGRAY);
        android.widget.LinearLayout.LayoutParams expiryParams =
                new android.widget.LinearLayout.LayoutParams(-1, -2);
        expiryParams.topMargin = pad;
        panel.addView(expiry, expiryParams);

        new AlertDialog.Builder(this).setTitle("ربط أبو عزت كاست بالريسيفر")
                .setView(panel)
                .setPositiveButton("إغلاق", null)
                .setNeutralButton("توليد كود جديد", (d, w) -> {
                    pairing.edit().remove("pairing_code").remove("code_expires_at")
                            .remove("failed_attempts").apply();
                    showSharePairingCode();
                }).show();
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
        field.setHint("https://cobalt-one.example/\nhttps://cobalt-two.example/");
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
                            .putString("cobalt_endpoint", String.join("\n", endpoints))
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

    // Log only DLNA metadata structure; never log values that may contain credentials.
    private String summarizeDlnaMetadataShape(String metadata) {
        if (metadata == null || metadata.trim().isEmpty()) return "empty";
        java.util.LinkedHashSet<String> tags = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> attributes = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher tagMatcher = java.util.regex.Pattern
                .compile("<\\s*/?\\s*([A-Za-z_][A-Za-z0-9_.:-]*)").matcher(metadata);
        while (tagMatcher.find() && tags.size() < 24) {
            tags.add(tagMatcher.group(1).toLowerCase(Locale.ROOT));
        }
        java.util.regex.Matcher attributeMatcher = java.util.regex.Pattern
                .compile("\\s([A-Za-z_][A-Za-z0-9_.:-]*)\\s*=").matcher(metadata);
        while (attributeMatcher.find() && attributes.size() < 24) {
            attributes.add(attributeMatcher.group(1).toLowerCase(Locale.ROOT));
        }
        String trimmed = metadata.trim();
        boolean jsonLike = trimmed.startsWith("{") || trimmed.startsWith("[");
        return "tags=" + tags + ", attributes=" + attributes
                + ", cdata=" + metadata.contains("<![CDATA[")
                + ", escapedXml=" + metadata.contains("&lt;")
                + ", jsonLike=" + jsonLike;
    }

    // Support web links shared directly to Receiver in addition to links sent over port 8081.
    private String summarizeIncomingIntentShape(android.content.Intent intent) {
        if (intent == null) return "intent=none";
        StringBuilder out = new StringBuilder();
        String action = intent.getAction();
        out.append("action=").append(action == null ? "unknown" : action);
        android.os.Bundle extras = intent.getExtras();
        if (extras != null) {
            java.util.ArrayList<String> shapes = new java.util.ArrayList<>();
            for (String key : extras.keySet()) {
                if (shapes.size() >= 24) break;
                Object value = extras.get(key);
                String safeKey = key == null ? "unknown" : key.replaceAll("[^A-Za-z0-9_.-]", "");
                String type = value == null ? "null"
                        : value instanceof android.os.Bundle ? "bundle"
                        : value instanceof android.net.Uri ? "uri"
                        : value instanceof CharSequence ? "text"
                        : value instanceof java.util.Map ? "map"
                        : value.getClass().getSimpleName();
                shapes.add(safeKey + ":" + type);
                if (value instanceof android.os.Bundle
                        && (safeKey.toLowerCase(Locale.ROOT).contains("header")
                        || safeKey.toLowerCase(Locale.ROOT).contains("request"))) {
                    android.os.Bundle nested = (android.os.Bundle) value;
                    java.util.Iterator<String> it = nested.keySet().iterator();
                    int nestedCount = 0;
                    while (it.hasNext() && nestedCount++ < 12 && shapes.size() < 32) {
                        String nestedKey = it.next();
                        shapes.add("header-key:" + (nestedKey == null ? "unknown"
                                : nestedKey.replaceAll("[^A-Za-z0-9_.-]", "")));
                    }
                }
            }
            out.append("; extras={").append(android.text.TextUtils.join(",", shapes)).append("}");
        } else {
            out.append("; extras=none");
        }
        android.content.ClipData clip = intent.getClipData();
        if (clip != null) {
            out.append("; clip-items=").append(clip.getItemCount());
            android.content.ClipDescription description = clip.getDescription();
            if (description != null) {
                java.util.ArrayList<String> mimeTypes = new java.util.ArrayList<>();
                for (int i = 0; i < description.getMimeTypeCount() && i < 6; i++) {
                    mimeTypes.add(description.getMimeType(i));
                }
                out.append("; clip-mime={").append(android.text.TextUtils.join(",", mimeTypes)).append("}");
            }
        } else {
            out.append("; clip=none");
        }
        if (out.length() > 1800) return out.substring(0, 1800);
        return out.toString();
    }

    private void handleExternalIntent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ReceiverServerService.ACTION_SERVER_REQUEST.equals(action)) {
            String command = intent.getStringExtra(ReceiverServerService.EXTRA_COMMAND);
            String incomingUrl = intent.getStringExtra(ReceiverServerService.EXTRA_URL);
            String incomingAudio = intent.getStringExtra(ReceiverServerService.EXTRA_AUDIO);
            String incomingSourceUrl = intent.getStringExtra(ReceiverServerService.EXTRA_SOURCE_URL);
            boolean openIncomingInBrowser = intent.getBooleanExtra(ReceiverServerService.EXTRA_OPEN_BROWSER, false);
            String dlnaMetadata = intent.getStringExtra(ReceiverServerService.EXTRA_METADATA);
            // Internal remote-control/status commands arrive through the same Activity
            // action as media requests. Do not let them overwrite the last real share
            // diagnostic before a failed-media report is generated.
            if ((incomingUrl != null && !incomingUrl.trim().isEmpty())
                    || (incomingAudio != null && !incomingAudio.trim().isEmpty())
                    || (dlnaMetadata != null && !dlnaMetadata.trim().isEmpty())) {
                lastIncomingIntentDiagnostic = summarizeIncomingIntentShape(intent);
            }

            if (dlnaMetadata != null && !dlnaMetadata.isEmpty()) {
                String lowerMetadata = dlnaMetadata.toLowerCase(Locale.ROOT);
                Log.i(TAG, "DLNA metadata received: chars=" + dlnaMetadata.length()
                        + ", headerTag=" + lowerMetadata.contains("<header")
                        + ", httpHeaderTag=" + lowerMetadata.contains("http-header")
                        + ", cookieMarker=" + lowerMetadata.contains("cookie")
                        + ", refererMarker=" + lowerMetadata.contains("referer")
                        + ", originMarker=" + lowerMetadata.contains("origin")
                        + ", shape={" + summarizeDlnaMetadataShape(dlnaMetadata) + "}");
            } else {
                Log.i(TAG, "DLNA metadata received: empty");
            }
            java.util.Map<String, String> incomingHeaders = new java.util.LinkedHashMap<>();
            try {
                JSONObject headerJson = new JSONObject(intent.getStringExtra("server_headers") == null
                        ? "{}" : intent.getStringExtra("server_headers"));
                java.util.Iterator<String> keys = headerJson.keys();
                String[] allowedHeaders = {"user-agent", "referer", "origin", "accept",
                        "accept-language", "cookie", "sec-fetch-site", "sec-fetch-mode",
                        "sec-fetch-dest", "sec-ch-ua", "sec-ch-ua-mobile",
                        "sec-ch-ua-platform", "priority"};
                java.util.Set<String> allowedHeaderSet = new java.util.HashSet<>(
                        java.util.Arrays.asList(allowedHeaders));
                while (keys.hasNext()) {
                    String key = keys.next();
                    String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
                    String value = headerJson.optString(key, "");
                    int maxLength = "cookie".equals(normalizedKey) ? 8192 : 2048;
                    if (allowedHeaderSet.contains(normalizedKey) && !value.isEmpty()
                            && value.length() <= maxLength
                            && !value.contains("\r") && !value.contains("\n")) {
                        incomingHeaders.put(normalizedKey, value);
                    }
                }
            } catch (Exception ignored) { }
            if (command != null && !command.trim().isEmpty()) {
                handleRemoteCommand(command.trim());
            } else if (openIncomingInBrowser && isHttpUrl(incomingUrl)
                    && !isDirectStreamUrl(incomingUrl) && !isTikTokLiveCdnVideoUrl(incomingUrl)) {
                clearPendingServerMedia();
                pendingBrowserUrl = incomingUrl;
                openTvBrowser();
            } else {
                clearPendingServerMedia();
                queueIncomingMedia(incomingUrl, incomingAudio, incomingHeaders, incomingSourceUrl);
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
        String pendingSourceUrl = pending.getString("source_url", "");
        boolean pendingOpenBrowser = pending.getBoolean("open_browser", false);
        long receivedAt = pending.getLong("received_at", 0L);
        if (System.currentTimeMillis() - receivedAt <= 10 * 60 * 1000L
                && (!pendingUrl.trim().isEmpty() || !pendingAudio.trim().isEmpty())) {
            java.util.Map<String, String> pendingHeaders = new java.util.LinkedHashMap<>();
            try {
                JSONObject savedHeaders = new JSONObject(pending.getString("headers", "{}"));
                java.util.Iterator<String> keys = savedHeaders.keys();
                String[] allowedHeaders = {"user-agent", "referer", "origin", "accept",
                        "accept-language", "cookie", "sec-fetch-site", "sec-fetch-mode",
                        "sec-fetch-dest", "sec-ch-ua", "sec-ch-ua-mobile",
                        "sec-ch-ua-platform", "priority"};
                java.util.Set<String> allowedHeaderSet = new java.util.HashSet<>(
                        java.util.Arrays.asList(allowedHeaders));
                while (keys.hasNext()) {
                    String key = keys.next();
                    String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
                    String value = savedHeaders.optString(key, "");
                    int maxLength = "cookie".equals(normalizedKey) ? 8192 : 2048;
                    if (allowedHeaderSet.contains(normalizedKey) && !value.isEmpty()
                            && value.length() <= maxLength
                            && !value.contains("\r") && !value.contains("\n")) {
                        pendingHeaders.put(normalizedKey, value);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not restore saved playback headers");
            }
            clearPendingServerMedia();
            if (pendingOpenBrowser && isHttpUrl(pendingUrl)
                    && !isDirectStreamUrl(pendingUrl) && !isTikTokLiveCdnVideoUrl(pendingUrl)) {
                pendingBrowserUrl = pendingUrl;
                openTvBrowser();
            } else {
                queueIncomingMedia(pendingUrl, pendingAudio, pendingHeaders, pendingSourceUrl);
            }
        } else if (receivedAt != 0L) {
            clearPendingServerMedia();
        }
    }

    private void clearPendingServerMedia() {
        getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE).edit()
                .remove("url")
                .remove("audio")
                .remove("source_url")
                .remove("headers")
                .remove("open_browser")
                .remove("received_at")
                .apply();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerNetworkCallback();
        updateConnectionInfo();
        new AppUpdater(this).verifyPendingInstallAfterResume();
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

        boolean compactPhone = !isTelevisionDevice()
                && getResources().getConfiguration().smallestScreenWidthDp < 600;
        int badgeSize = dp(compactPhone ? 44 : 64);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(badgeSize, badgeSize,
                Gravity.TOP | Gravity.LEFT);
        params.setMargins(dp(compactPhone ? 8 : 14), dp(compactPhone ? 8 : 12), 0, 0);
        root.addView(badge, params);
        persistentBrandBadge = badge;
        keepPersistentBrandBadgeOnTop();
    }

    private void keepPersistentBrandBadgeOnTop() {
        if (persistentBrandBadge != null && root != null) {
            // During video playback, the portrait follows the native player controls.
            // Outside video playback it remains visible as before.
            boolean videoPlayerActive = playerView != null
                    && playerView.getVisibility() == View.VISIBLE;
            persistentBrandBadge.setVisibility(
                    !videoPlayerActive || playerControllerVisible ? View.VISIBLE : View.GONE);
            if (persistentBrandBadge.getVisibility() == View.VISIBLE) {
                persistentBrandBadge.bringToFront();
            }
        }
    }

    // Phone home actions stay at the upper-right, opposite the brand portrait.
    // Android TV / Fire TV keeps its original home-button geometry.
    private void applyMobileHomeLayout() {
        if (root == null || isTelevisionDevice()
                || getResources().getConfiguration().smallestScreenWidthDp >= 600
                || settingsHomeButton == null || browserHomeButton == null) {
            return;
        }

        int side = dp(44);
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(
                side, side, Gravity.TOP | Gravity.RIGHT);
        settingsParams.setMargins(dp(8), dp(10), dp(10), dp(8));
        settingsHomeButton.setLayoutParams(settingsParams);

        FrameLayout.LayoutParams browserParams = new FrameLayout.LayoutParams(
                side, side, Gravity.TOP | Gravity.RIGHT);
        browserParams.setMargins(dp(8), dp(10), dp(62), dp(8));
        browserHomeButton.setLayoutParams(browserParams);

        settingsHomeButton.setNextFocusLeftId(browserHomeButton.getId());
        browserHomeButton.setNextFocusRightId(settingsHomeButton.getId());
    }

    private boolean isTelevisionDevice() {
        int uiMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_TYPE_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                || getPackageManager().hasSystemFeature("android.software.leanback");
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
        boolean compactPhoneLayout = !isTelevisionDevice()
                && getResources().getConfiguration().smallestScreenWidthDp < 600;
        FrameLayout.LayoutParams cardParams =
                new FrameLayout.LayoutParams(
                        dp(compactPhoneLayout ? 190 : 230),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        // Use physical RIGHT: END resolves to the left in RTL Arabic layouts.
                        Gravity.BOTTOM | Gravity.RIGHT
                );

        cardParams.setMargins(
                dp(8),
                dp(8),
                dp(compactPhoneLayout ? 12 : 8),
                dp(compactPhoneLayout ? 16 : 12)
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
        boolean compactPlayerChrome = !isTelevisionDevice()
                && getResources().getConfiguration().smallestScreenWidthDp < 600;
        int homeActionSize = dp(compactPlayerChrome ? 44 : 52);
        TextView[] homeButtons = {settingsButton, browserHomeButton};
        for (TextView homeButton : homeButtons) {
            homeButton.setGravity(Gravity.CENTER);
            homeButton.setFocusable(true);
            homeButton.setFocusableInTouchMode(true);
            homeButton.setClickable(true);
            homeButton.setPadding(dp(4), dp(3), dp(4), dp(3));
            if (android.os.Build.VERSION.SDK_INT >= 26) homeButton.setDefaultFocusHighlightEnabled(false);
            homeButton.setBackground(browserFocusBackground(Color.rgb(20, 34, 61), Color.rgb(86, 132, 255), 12, false));
            homeButton.setOnFocusChangeListener((v, focused) -> {
                v.setScaleX(focused ? 1.05f : 1f);
                v.setScaleY(focused ? 1.05f : 1f);
                v.setElevation(focused ? dp(5) : 0);
                v.setBackground(browserFocusBackground(
                        focused ? Color.argb(48, 105, 185, 255) : Color.rgb(20, 34, 61),
                        focused ? Color.rgb(105, 185, 255) : Color.rgb(86, 132, 255), 12, focused));
            });
        }
        settingsButton.setContentDescription("الإعدادات");
        browserHomeButton.setContentDescription("المتصفح");
        settingsButton.setNextFocusLeftId(browserHomeButton.getId());
        browserHomeButton.setNextFocusRightId(settingsButton.getId());
        settingsButton.setOnClickListener(v -> showReceiverSettings());
        browserHomeButton.setOnClickListener(v -> browserShortcut.performClick());
        FrameLayout.LayoutParams settingsButtonParams = new FrameLayout.LayoutParams(
                homeActionSize, homeActionSize, Gravity.TOP | Gravity.END);
        settingsButtonParams.setMargins(dp(8), dp(10), dp(10), dp(8));
        root.addView(settingsButton, settingsButtonParams);
        FrameLayout.LayoutParams browserHomeParams = new FrameLayout.LayoutParams(
                homeActionSize, homeActionSize, Gravity.TOP | Gravity.END);
        browserHomeParams.setMargins(dp(8), dp(10), dp(compactPlayerChrome ? 62 : 70), dp(8));
        root.addView(browserHomeButton, browserHomeParams);

        // مشغل الفيديو والصوت.
        // Inflate with surface_type="texture_view" so Media3 can apply rotation metadata
        // on Android TV devices; PlayerView exposes this setting through XML attributes.
        playerView = (PlayerView) getLayoutInflater().inflate(R.layout.player_view_texture, root, false);
        playerView.setUseController(true);
        // Hide the native controls exactly two seconds after playback or the last interaction.
        playerView.setControllerShowTimeoutMs(2000);
        playerView.setControllerHideOnTouch(true);
        // A tap on the video surface reveals the complete controller again.
        // Media3's 2-second timeout then hides both native controls and mirrored overlays.
        playerView.setOnClickListener(v -> showPlayerController());
        playerView.setResizeMode(getPlayerSettingsPrefs().getInt("resize_mode",
                                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT));
        playerView.setVisibility(View.GONE);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Clear on-screen feedback while Media3 fills the startup/rebuffer safety buffer.
        playerBufferingOverlay = new FrameLayout(this);
        playerBufferingOverlay.setVisibility(View.GONE);
        playerBufferingOverlay.setClickable(false);
        playerBufferingOverlay.setFocusable(false);
        LinearLayout bufferingCard = new LinearLayout(this);
        bufferingCard.setOrientation(LinearLayout.VERTICAL);
        bufferingCard.setGravity(Gravity.CENTER);
        bufferingCard.setPadding(dp(24), dp(18), dp(24), dp(18));
        GradientDrawable bufferingBackground = new GradientDrawable();
        bufferingBackground.setColor(Color.argb(220, 18, 22, 30));
        bufferingBackground.setCornerRadius(dp(16));
        bufferingCard.setBackground(bufferingBackground);
        ProgressBar bufferingSpinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        spinnerParams.gravity = Gravity.CENTER_HORIZONTAL;
        bufferingCard.addView(bufferingSpinner, spinnerParams);
        playerBufferingText = makeText("جاري التحميل...", 17, Color.WHITE, true);
        playerBufferingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bufferingTextParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bufferingTextParams.topMargin = dp(12);
        bufferingCard.addView(playerBufferingText, bufferingTextParams);
        FrameLayout.LayoutParams bufferingCardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        playerBufferingOverlay.addView(bufferingCard, bufferingCardParams);
        root.addView(playerBufferingOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // أدوات المشغل مستقلة عن أزرار التطبيق الرئيسية، وتظهر مع شريط التحكم فقط.
        playerSubtitleButton = makeText("CC", 15, Color.WHITE, true);
        playerSubtitleButton.setGravity(Gravity.CENTER);
        playerSubtitleButton.setFocusable(true);
        playerSubtitleButton.setFocusableInTouchMode(true);
        playerSubtitleButton.setClickable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) playerSubtitleButton.setDefaultFocusHighlightEnabled(false);
        playerSubtitleButton.setBackground(browserFocusBackground(
                Color.TRANSPARENT, Color.TRANSPARENT, 12, false));
        playerSubtitleButton.setOnFocusChangeListener((view, focused) -> {
            view.setBackground(browserFocusBackground(
                    focused ? Color.argb(48, 105, 185, 255) : Color.TRANSPARENT,
                    focused ? Color.rgb(105, 185, 255) : Color.TRANSPARENT, 12, focused));
            view.setElevation(focused ? dp(4) : 0);
            view.setScaleX(focused ? 1.04f : 1f);
            view.setScaleY(focused ? 1.04f : 1f);
        });
        playerSubtitleButton.setVisibility(View.GONE);
        playerSubtitleButton.setMinWidth(dp(46));
        playerSubtitleButton.setMinHeight(dp(44));
        playerSubtitleButton.setId(View.generateViewId());
        playerSubtitleButton.setContentDescription("البحث عن ترجمة عربية");
        playerSubtitleButton.setOnClickListener(v -> searchAndApplyArabicSubtitle());
        playerSubtitleButton.setNextFocusRightId(playerDownloadButton == null ? View.NO_ID : playerDownloadButton.getId());
        playerSubtitleButton.setOnLongClickListener(v -> {
            promptSubtitleTitleAndSearch();
            return true;
        });
        int subtitleEndMargin = compactPlayerChrome ? dp(114) : dp(130);
        FrameLayout.LayoutParams playerSubtitleParams = new FrameLayout.LayoutParams(
                dp(46), dp(44), Gravity.TOP | Gravity.END);
        playerSubtitleParams.setMargins(dp(8), dp(10), subtitleEndMargin, dp(8));
        root.addView(playerSubtitleButton, playerSubtitleParams);

        playerSettingsButton = makeText("⚙", 24, Color.WHITE, true);
        playerSettingsButton.setGravity(Gravity.CENTER);
        playerSettingsButton.setFocusable(true);
        playerSettingsButton.setFocusableInTouchMode(true);
        playerSettingsButton.setClickable(true);
        playerSettingsButton.setMinWidth(dp(46));
        playerSettingsButton.setMinHeight(dp(44));
        playerSettingsButton.setId(View.generateViewId());
        playerSettingsButton.setContentDescription("إعدادات المشغل");
        playerSettingsButton.setBackground(browserFocusBackground(Color.TRANSPARENT, Color.TRANSPARENT, 12, false));
        playerSettingsButton.setOnFocusChangeListener((v, focused) -> v.setBackground(browserFocusBackground(
                focused ? Color.argb(48, 105, 185, 255) : Color.TRANSPARENT,
                focused ? Color.rgb(105, 185, 255) : Color.TRANSPARENT, 12, focused)));
        playerSettingsButton.setOnClickListener(v -> showPlaybackSettingsDialog());
        playerSettingsButton.setNextFocusLeftId(playerDownloadButton == null ? View.NO_ID : playerDownloadButton.getId());
        FrameLayout.LayoutParams playerSettingsParams = new FrameLayout.LayoutParams(
                dp(46), dp(44), Gravity.TOP | Gravity.END);
        playerSettingsParams.setMargins(dp(8), dp(10), dp(62), dp(8));
        root.addView(playerSettingsButton, playerSettingsParams);
        // Player settings are hidden on the home screen; playback/image flows reveal them.
        playerSettingsButton.setVisibility(View.GONE);

        playerDownloadButton = makeText("⬇", 24, Color.WHITE, true);
        playerDownloadButton.setGravity(Gravity.CENTER);
        playerDownloadButton.setFocusable(true);
        playerDownloadButton.setFocusableInTouchMode(true);
        playerDownloadButton.setClickable(true);
        playerDownloadButton.setMinWidth(dp(46));
        playerDownloadButton.setMinHeight(dp(44));
        playerDownloadButton.setId(View.generateViewId());
        playerDownloadButton.setContentDescription("تنزيل الوسائط");
        playerDownloadButton.setBackground(browserFocusBackground(Color.TRANSPARENT, Color.TRANSPARENT, 12, false));
        playerDownloadButton.setOnFocusChangeListener((v, focused) -> v.setBackground(browserFocusBackground(
                focused ? Color.argb(48, 105, 185, 255) : Color.TRANSPARENT,
                focused ? Color.rgb(105, 185, 255) : Color.TRANSPARENT, 12, focused)));
        playerDownloadButton.setOnClickListener(v -> showCurrentPlaybackDownloadOptions());
        playerDownloadButton.setNextFocusRightId(playerSettingsButton == null ? View.NO_ID : playerSettingsButton.getId());
        playerDownloadButton.setNextFocusLeftId(playerSubtitleButton == null ? View.NO_ID : playerSubtitleButton.getId());
        FrameLayout.LayoutParams playerDownloadParams = new FrameLayout.LayoutParams(
                dp(46), dp(44), Gravity.TOP | Gravity.END);
        playerDownloadParams.setMargins(dp(8), dp(10), dp(compactPlayerChrome ? 166 : 182), dp(8));
        root.addView(playerDownloadButton, playerDownloadParams);
        // Download is a player action, never a home-screen shortcut.
        playerDownloadButton.setVisibility(View.GONE);
        playerSubtitleButton.setNextFocusRightId(playerDownloadButton.getId());
        playerDownloadButton.setNextFocusRightId(playerSettingsButton.getId());
        playerDownloadButton.setNextFocusLeftId(playerSubtitleButton.getId());
        playerSettingsButton.setNextFocusLeftId(playerDownloadButton.getId());

        // These overlays must sit above the full-screen PlayerView on phones and TVs.
        settingsHomeButton.bringToFront();
        browserHomeButton.bringToFront();
        playerSubtitleButton.bringToFront();
        playerSettingsButton.bringToFront();
        playerDownloadButton.bringToFront();

        playerView.post(() -> {
            View nativeSettings = playerView.findViewById(androidx.media3.ui.R.id.exo_settings);
            if (nativeSettings != null) {
                nativeSettings.setVisibility(View.GONE);
                nativeSettings.setFocusable(false);
                nativeSettings.setClickable(false);
            }
            playerSubtitleControlButton = playerView.findViewById(R.id.abo_player_subtitle);
            playerDownloadControlButton = playerView.findViewById(R.id.abo_player_download);
            playerSettingsControlButton = playerView.findViewById(R.id.abo_player_settings);
            playerRepeatControlButton = playerView.findViewById(R.id.abo_player_repeat);
            if (playerSubtitleControlButton != null) {
                playerSubtitleControlButton.setOnClickListener(v -> showSubtitleOptionsDialog());
            }
            if (playerDownloadControlButton != null) {
                playerDownloadControlButton.setOnClickListener(v -> showCurrentPlaybackDownloadOptions());
            }
            if (playerSettingsControlButton != null) {
                playerSettingsControlButton.setOnClickListener(v -> showPlaybackSettingsDialog());
            }
            if (playerRepeatControlButton != null) {
                playerRepeatControlButton.setOnClickListener(v -> cyclePlaybackRepeatMode());
                updatePlaybackRepeatControl();
            }
        });
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            boolean controlsVisible = visibility == View.VISIBLE
                    && playerView != null && playerView.getVisibility() == View.VISIBLE;
            playerControllerVisible = controlsVisible;
            keepPersistentBrandBadgeOnTop();
            if (controlsVisible) {
                schedulePlayerControllerAutoHide();
            } else {
                playerControlsHandler.removeCallbacks(hidePlayerControlsRunnable);
            }
            // أزرار التطبيق الرئيسية لا تظهر مطلقًا فوق المشغل، ولا نخفيها بعد العودة للرئيسية.
            boolean mediaSurfaceActive = playerView != null && playerView.getVisibility() == View.VISIBLE;
            if (mediaSurfaceActive) {
                if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
                if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
            }
            // Video controls live together below the seek bar inside the controller.
            // The old floating overlays stay hidden during video playback.
            if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
            if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
            if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
            if (playerSubtitleControlButton != null) {
                playerSubtitleControlButton.setVisibility(controlsVisible && currentPlaybackHasVideo
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
                    showImageControls();
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
        if (compactPlayerChrome) {
            root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                applyMobileHomeLayout();
            });
            root.post(this::applyMobileHomeLayout);
        }
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
            if (path == null) return false;

            int port = uri.getPort();
            if (port == 8088) {
                String token = uri.getQueryParameter("token");
                return "https".equalsIgnoreCase(uri.getScheme())
                        && ("/media.mp4".equalsIgnoreCase(path) || "/media.jpg".equalsIgnoreCase(path))
                        && token != null && token.matches("[A-Za-z0-9_-]{43}");
            }
            return port == 8082 && "http".equalsIgnoreCase(uri.getScheme())
                    && "/media".equals(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTikTokCdnVideoUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

            // Standard TikTok video CDN URLs use /video/tos/. LIVE streams
            // may use either /stage/stream-* or /game/stream-* paths.
            boolean standardTikTokVideo = (host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && path.contains("/video/tos/");
            boolean liveStreamPath = path.contains("/stage/stream-") || path.contains("/game/stream-");
            boolean tiktokLiveCdn = (host.equals("tiktokcdn.com") || host.endsWith(".tiktokcdn.com"))
                    && host.startsWith("pull-")
                    && liveStreamPath
                    && (path.endsWith(".flv") || path.endsWith(".m3u8"));
            return standardTikTokVideo || tiktokLiveCdn;
        } catch (Exception ignored) {
            return false;
        }
    }

    // A signed TikTok webapp-prime MP4 is already the final media resource.
    // Never replace it with a second extraction of the source post page.
    private boolean isTikTokSignedMp4CdnUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            String query = uri.getEncodedQuery() == null ? "" : uri.getEncodedQuery().toLowerCase(Locale.ROOT);
            boolean tiktokHost = host.equals("tiktok.com") || host.endsWith(".tiktok.com")
                    || host.equals("tiktokcdn.com") || host.endsWith(".tiktokcdn.com");
            return "https".equalsIgnoreCase(uri.getScheme()) && tiktokHost
                    && path.contains("/video/tos/")
                    && query.contains("mime_type=video_mp4");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTikTokLiveCdnVideoUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            boolean liveStreamPath = path.contains("/stage/stream-") || path.contains("/game/stream-");
            return (host.equals("tiktokcdn.com") || host.endsWith(".tiktokcdn.com"))
                    && host.startsWith("pull-")
                    && liveStreamPath
                    && (path.endsWith(".flv") || path.endsWith(".m3u8"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isTikTokSourcePageUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return (host.equals("tiktok.com") || host.endsWith(".tiktok.com"))
                    && !isTikTokCdnVideoUrl(value);
        } catch (Exception ignored) {
            return false;
        }
    }

    // Known live-provider index.html endpoints are HLS manifests, not ordinary web pages.
    // Route them straight to Media3 so the HLS MIME override below can take effect;
    // sending them through generic page extraction can incorrectly report extraction failure.
    private boolean isKnownHtmlHlsPlaylistUrl(String value) {
        if (!isHttpUrl(value)) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            return path != null && path.toLowerCase(Locale.ROOT)
                    .matches("^/[^/]+/[^/]+/index\\.html$");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isDirectStreamUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        if (isPhoneLocalMediaUrl(value)) return true;
        if (isTikTokCdnVideoUrl(value)) return true;
        try {
            Uri uri = Uri.parse(value.trim());
            String path = uri.getPath();
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
            String lower = value.toLowerCase(Locale.ROOT);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ("rtsp".equals(scheme) && uri.getHost() != null) return true;
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
                    || lowerPath.endsWith(".mkv") || lowerPath.endsWith(".webm") || lowerPath.endsWith(".mov")
                    || lowerPath.endsWith(".flv") || lowerPath.endsWith(".avi")
                    || lowerPath.endsWith(".mpg") || lowerPath.endsWith(".mpeg")
                    || lowerPath.endsWith(".3gp") || lowerPath.endsWith(".ogv")
                    || lowerPath.endsWith(".wmv") || lowerPath.endsWith(".vob")
                    || lowerPath.endsWith(".ts") || lowerPath.endsWith(".m2ts")
                    || lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".mpd")
                    || lowerPath.endsWith(".ism") || lowerPath.endsWith(".ismc")
                    || lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a")
                    || lowerPath.endsWith(".aac") || lowerPath.endsWith(".ogg")
                    || lowerPath.endsWith(".oga") || lowerPath.endsWith(".opus")
                    || lowerPath.endsWith(".wav") || lowerPath.endsWith(".flac")
                    || lowerPath.endsWith(".mid") || lowerPath.endsWith(".midi")
                    || lower.contains("mime=video") || lower.contains("type=video")
                    || lower.contains("mime_type=video_mp4")
                    || lower.contains("video_playback") || lower.contains("/videoplayback")
                    || lower.contains("contenttype=video")
                    || lower.contains("mime=audio") || lower.contains("type=audio")
                    || lower.contains("audio_playback") || lower.contains("/audioplayback")
                    || lower.contains("contenttype=audio");
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveExtractedUrl(String value, String pageUrl) {
        String candidate = cleanExtractedUrl(value);
        if (candidate != null) return candidate;
        if (value == null || pageUrl == null) return null;
        try {
            return cleanExtractedUrl(new URL(new URL(pageUrl), value.trim()).toString());
        } catch (Exception ignored) {
            return null;
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
                || candidate.startsWith("rtsp://") ? candidate : null;
    }

    /**
     * Resolve same-origin JSON indexes referenced by a player HTML page.
     * This intentionally does not crawl arbitrary hosts or execute page scripts.
     */
    private String extractLinkedJsonMedia(String html, String pageUrl) {
        if (html == null || html.isEmpty() || pageUrl == null) return null;
        java.util.LinkedHashSet<String> jsonUrls = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)(?:src|href|data-src|data-config|configUrl|indexUrl|jsonUrl)\\s*[=:]\\s*['\\\"]([^'\\\"]{1,2048}?\\.json(?:[?#][^'\\\"]*)?)['\\\"]")
                .matcher(html);
        while (matcher.find() && jsonUrls.size() < 5) {
            String resolved = resolveExtractedUrl(matcher.group(1), pageUrl);
            if (resolved != null && isHttpUrl(resolved) && isSameOrigin(pageUrl, resolved)) {
                jsonUrls.add(resolved);
            }
        }

        int checked = 0;
        for (String jsonUrl : jsonUrls) {
            if (checked++ >= 3) break;
            HttpURLConnection jsonConnection = null;
            try {
                jsonConnection = (HttpURLConnection) new URL(jsonUrl).openConnection();
                jsonConnection.setInstanceFollowRedirects(false);
                jsonConnection.setConnectTimeout(5000);
                jsonConnection.setReadTimeout(7000);
                jsonConnection.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 12; TV) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
                jsonConnection.setRequestProperty("Accept", "application/json,text/plain,*/*;q=0.8");
                jsonConnection.setRequestProperty("Referer", pageUrl);
                String cookie = CookieManager.getInstance().getCookie(jsonUrl);
                if (cookie != null && !cookie.isEmpty() && cookie.length() <= 8192
                        && !cookie.contains("\r") && !cookie.contains("\n")) {
                    jsonConnection.setRequestProperty("Cookie", cookie);
                }
                int status = jsonConnection.getResponseCode();
                String type = jsonConnection.getContentType();
                if (status < 200 || status >= 300
                        || (type != null && !type.toLowerCase(Locale.ROOT).contains("json")
                        && !jsonUrl.toLowerCase(Locale.ROOT).contains(".json"))) {
                    continue;
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        jsonConnection.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) != -1 && body.length() < 2 * 1024 * 1024) {
                        body.append(buffer, 0, count);
                    }
                }
                String jsonBody = body.toString();
                String media = extractDirectMediaFromHtml(jsonBody, jsonUrl);
                if (media != null && (isDirectStreamUrl(media)
                        || isJsonMediaFieldCandidate(jsonBody, jsonUrl, media))) return media;
            } catch (Exception e) {
                Log.w(TAG, "Same-origin JSON index lookup failed");
            } finally {
                if (jsonConnection != null) jsonConnection.disconnect();
            }
        }
        return null;
    }

    private String buildJsonIndexDiagnostic(int status, String contentType, String body) {
        StringBuilder out = new StringBuilder();
        out.append("HTTP ").append(status)
                .append("; type=").append(contentType == null ? "unknown" : contentType)
                .append("; chars=").append(body == null ? 0 : body.length());
        if (body == null || body.trim().isEmpty()) {
            out.append("; body=empty");
            return out.toString();
        }
        // Some providers return a tiny PNG placeholder/error image from a URL
        // ending in .json. Never mislabel binary image bytes as a JSON scalar.
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            out.append("; body=non-json-image");
            return out.toString();
        }
        try {
            Object root = new org.json.JSONTokener(body).nextValue();
            if (root instanceof JSONObject) {
                JSONObject object = (JSONObject) root;
                out.append("; JSON=object; keys=");
                java.util.Iterator<String> keys = object.keys();
                int count = 0;
                while (keys.hasNext() && count < 12) {
                    if (count > 0) out.append(",");
                    String key = keys.next();
                    String safeKey = key == null ? "" : key.replaceAll("[^A-Za-z0-9_-]", "");
                    out.append(safeKey.substring(0, Math.min(48, safeKey.length())));
                    count++;
                }
                if (keys.hasNext()) out.append(",more");
            } else if (root instanceof org.json.JSONArray) {
                out.append("; JSON=array");
            } else {
                out.append("; JSON=scalar");
            }
            StringBuilder shape = new StringBuilder();
            appendJsonShape(root, "", shape, 0, new int[] {0});
            if (shape.length() > 0) out.append("; shape=").append(shape);
        } catch (Exception ignored) {
            out.append("; JSON=invalid-or-non-json");
        }
        return out.toString();
    }

    // Report only JSON field paths and value types, never values. This reveals
    // unfamiliar track/index schemas without exposing signed URLs, tokens or IPs.
    private void appendJsonShape(Object node, String path, StringBuilder out,
            int depth, int[] count) {
        if (node == null || depth > 4 || count[0] >= 24) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && count[0] < 24) {
                String key = keys.next();
                String safeKey = key == null ? "field"
                        : key.replaceAll("[^A-Za-z0-9_-]", "");
                if (safeKey.isEmpty()) safeKey = "field";
                Object value = object.opt(key);
                String childPath = path.isEmpty() ? safeKey : path + "." + safeKey;
                String type = value instanceof JSONObject ? "object"
                        : value instanceof org.json.JSONArray ? "array"
                        : value instanceof String ? "string"
                        : value instanceof Number ? "number"
                        : value instanceof Boolean ? "boolean" : "null";
                if (out.length() > 0) out.append(",");
                out.append(childPath).append(":").append(type);
                count[0]++;
                if (value instanceof JSONObject || value instanceof org.json.JSONArray) {
                    appendJsonShape(value, childPath, out, depth + 1, count);
                }
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            int limit = Math.min(array.length(), 3);
            for (int i = 0; i < limit && count[0] < 24; i++) {
                Object value = array.opt(i);
                String childPath = path + "[]";
                String type = value instanceof JSONObject ? "object"
                        : value instanceof org.json.JSONArray ? "array"
                        : value instanceof String ? "string"
                        : value instanceof Number ? "number"
                        : value instanceof Boolean ? "boolean" : "null";
                if (out.length() > 0) out.append(",");
                out.append(childPath).append(":").append(type);
                count[0]++;
                if (value instanceof JSONObject || value instanceof org.json.JSONArray) {
                    appendJsonShape(value, childPath, out, depth + 1, count);
                }
            }
        }
    }

    private boolean isSameOrigin(String firstUrl, String secondUrl) {
        try {
            URL first = new URL(firstUrl);
            URL second = new URL(secondUrl);
            int firstPort = first.getPort() >= 0 ? first.getPort()
                    : ("https".equalsIgnoreCase(first.getProtocol()) ? 443 : 80);
            int secondPort = second.getPort() >= 0 ? second.getPort()
                    : ("https".equalsIgnoreCase(second.getProtocol()) ? 443 : 80);
            return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                    && first.getHost().equalsIgnoreCase(second.getHost())
                    && firstPort == secondPort;
        } catch (Exception ignored) {
            return false;
        }
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
                "hlsPullUrl", "hls_pull_url", "live_play_url", "livePlayUrl",
                "src", "source", "file", "fileUrl", "file_url", "media", "mediaUrl",
                "media_url", "manifest", "manifestUrl", "manifest_url", "dashUrl",
                "dash_url", "mpdUrl", "mpd_url", "playlist", "playlistUrl",
                "playlist_url", "stream", "streamUrl", "stream_url", "url"
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

        // Capture common direct media extensions, including extensionless RTSP URLs
        // exposed by HTML media elements and public player configuration.
        java.util.regex.Matcher mediaElements = java.util.regex.Pattern
                .compile("(?is)<(?:video|audio|source)\\b[^>]*?\\b(?:src|data-src)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
                .matcher(html);
        while (mediaElements.find()) {
            String candidate = resolveExtractedUrl(mediaElements.group(1), pageUrl);
            if (candidate != null && isDirectStreamUrl(candidate)) candidates.add(candidate);
        }

        java.util.regex.Matcher rawMedia = java.util.regex.Pattern
                .compile("(?is)(?:https?:\\\\?/\\\\?/|rtsp://)[^\\s'\\\"><]+?\\.(?:mp4|m4v|mkv|webm|mov|flv|avi|mpg|mpeg|3gp|ogv|wmv|vob|ts|m2ts|m3u8|mpd|ism|ismc|mp3|m4a|aac|ogg|oga|opus|wav|flac|wma)(?:[?#][^\\s'\\\"><]*)?")
                .matcher(html);
        while (rawMedia.find()) {
            String candidate = cleanExtractedUrl(rawMedia.group(0));
            if (candidate != null) candidates.add(candidate);
        }

        // Some live-TV providers expose the actual stream only inside an index.json
        // response. Parse JSON structurally instead of relying only on HTML regexes.
        // Keep media-key URLs separate so signed extension-less CDN URLs are not
        // discarded by the extension-based direct-URL classifier below.
        java.util.LinkedHashSet<String> jsonMediaCandidates = new java.util.LinkedHashSet<>();
        try {
            Object jsonRoot = new org.json.JSONTokener(html).nextValue();
            collectJsonMediaCandidates(jsonRoot, jsonMediaCandidates, pageUrl, 0, new int[] {0});
        } catch (Exception ignored) { }

        for (String candidate : candidates) {
            if (isDirectStreamUrl(candidate) && !candidate.equals(pageUrl)) return candidate;
        }
        for (String candidate : jsonMediaCandidates) {
            if (candidate != null && isHttpUrl(candidate) && !candidate.equals(pageUrl)) return candidate;
        }
        return null;
    }

    // Bound recursive parsing so unusually deep or oversized JSON cannot exhaust
    // the receiver's stack or keep the extraction worker busy indefinitely.
    private static final int MAX_JSON_DEPTH = 16;
    private static final int MAX_JSON_NODES = 5000;

    private boolean isExcludedJsonMediaKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()) return false;
        String[] excluded = {
                "thumbnail", "thumbnailurl", "thumb", "poster", "cover",
                "preview", "previewurl", "avatar", "image", "imageurl",
                "logo", "icon", "subtitle", "subtitles", "caption",
                "description", "watermark", "ads", "advertisement", "adurl", "adtag",
                "banner", "sprite", "storyboard"
        };
        for (String token : excluded) {
            if (normalizedKey.equals(token) || normalizedKey.startsWith(token)
                    || normalizedKey.endsWith(token)) return true;
        }
        return false;
    }

    private boolean isLikelyMediaJsonKey(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isEmpty()
                || isExcludedJsonMediaKey(normalizedKey)) return false;
        // These keys commonly hold signed, extension-less CDN URLs in IPTV and
        // social-media player JSON. Avoid accepting arbitrary generic "url" fields.
        String[] mediaKeys = {
                "playaddr", "downloadaddr", "contenturl", "videourl",
                "playbackurl", "hlsurl", "playurl", "streamurl",
                "hlspullurl", "flvpullurl", "pullurl", "liveurl",
                "liveplayurl", "mediaurl", "manifesturl", "dashurl",
                "mpdurl", "playlisturl", "sourceurl", "fileurl",
                "flvurl", "srcurl"
        };
        for (String key : mediaKeys) {
            if (normalizedKey.equals(key)) return true;
        }
        return false;
    }

    private boolean isJsonMediaFieldCandidate(String html, String pageUrl, String mediaUrl) {
        if (html == null || html.isEmpty() || mediaUrl == null) return false;
        java.util.LinkedHashSet<String> jsonMediaCandidates = new java.util.LinkedHashSet<>();
        try {
            Object jsonRoot = new org.json.JSONTokener(html).nextValue();
            collectJsonMediaCandidates(jsonRoot, jsonMediaCandidates, pageUrl, 0, new int[] {0});
        } catch (Exception ignored) { }
        return jsonMediaCandidates.contains(mediaUrl);
    }

    private void collectJsonMediaCandidates(Object node,
            java.util.Set<String> candidates, String pageUrl,
            int depth, int[] visitedNodes) {
        if (node == null || depth > MAX_JSON_DEPTH
                || visitedNodes[0] >= MAX_JSON_NODES) return;
        visitedNodes[0]++;

        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext() && visitedNodes[0] < MAX_JSON_NODES) {
                String key = keys.next();
                String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT)
                        .replace("-", "").replace("_", "");
                if (isExcludedJsonMediaKey(normalizedKey)) continue;
                Object value = object.opt(key);
                if (value instanceof String) {
                    String raw = ((String) value).trim();
                    String candidate = resolveExtractedUrl(raw, pageUrl);
                    if (candidate != null && (isDirectStreamUrl(candidate)
                            || (isLikelyMediaJsonKey(normalizedKey) && isHttpUrl(candidate)))) {
                        candidates.add(candidate);
                    } else if (raw.length() > 1 && raw.length() <= 1024 * 1024
                            && (raw.startsWith("{") || raw.startsWith("["))) {
                        // Some APIs double-encode the player object as a JSON string.
                        try {
                            Object nested = new org.json.JSONTokener(raw).nextValue();
                            collectJsonMediaCandidates(nested, candidates, pageUrl,
                                    depth + 1, visitedNodes);
                        } catch (Exception ignored) { }
                    }
                } else if (value instanceof JSONObject || value instanceof org.json.JSONArray) {
                    collectJsonMediaCandidates(value, candidates, pageUrl,
                            depth + 1, visitedNodes);
                }
            }
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            int length = Math.min(array.length(), MAX_JSON_NODES - visitedNodes[0]);
            for (int i = 0; i < length && visitedNodes[0] < MAX_JSON_NODES; i++) {
                Object value = array.opt(i);
                if (value instanceof String) {
                    String raw = ((String) value).trim();
                    String candidate = resolveExtractedUrl(raw, pageUrl);
                    if (candidate != null && isDirectStreamUrl(candidate)) {
                        candidates.add(candidate);
                    } else if (raw.length() > 1 && raw.length() <= 1024 * 1024
                            && (raw.startsWith("{") || raw.startsWith("["))) {
                        try {
                            Object nested = new org.json.JSONTokener(raw).nextValue();
                            collectJsonMediaCandidates(nested, candidates, pageUrl,
                                    depth + 1, visitedNodes);
                        } catch (Exception ignored) { }
                    }
                } else if (value instanceof JSONObject || value instanceof org.json.JSONArray) {
                    collectJsonMediaCandidates(value, candidates, pageUrl,
                            depth + 1, visitedNodes);
                }
            }
        }
    }

    private volatile int extractionGeneration = 0;

    /**
     * YouTube pages received from Cast are resolved on the Receiver itself using
     * the exact NewPipe extractor and stream-selection policy used by Cast's
     * working native YouTube share route. The original page URL is retained only
     * for the SmartTube fallback if direct extraction fails.
     */
    private void extractYouTubeOnReceiver(String pageUrl) {
        final int generation = ++extractionGeneration;
        currentPlaybackFallbackUrl = pageUrl;
        smartTubeFallbackAttempted = false;
        extractionInProgress = true;
        extractionAttemptCount = 0;
        extractionStatus = "youtube_receiver_extracting";
        playbackStatusMessage = "الريسيفر يستخرج رابط الفيديو والصوت مباشرة من YouTube…";
        persistExtractionState();
        runOnUiThread(() -> {
            if (receiverStatusText != null) {
                receiverStatusText.setText("جاري استخراج فيديو YouTube على الريسيفر…");
            }
        });

        YoutubeDirectExtractor.extract(pageUrl, new YoutubeDirectExtractor.Callback() {
            @Override
            public void onSuccess(String videoUrl, String audioUrl, String title, String quality) {
                if (generation != extractionGeneration || isFinishing()) return;
                if (videoUrl == null || videoUrl.trim().isEmpty() || !isHttpUrl(videoUrl)) {
                    onError("المستخرج لم يرجع رابط فيديو HTTP صالحًا");
                    return;
                }
                if (audioUrl != null && !audioUrl.trim().isEmpty() && !isHttpUrl(audioUrl)) {
                    audioUrl = null;
                }

                java.util.Map<String, String> streamHeaders = new java.util.LinkedHashMap<>();
                String userAgent = YoutubeDirectExtractor.getStreamUserAgent();
                if (userAgent != null && !userAgent.trim().isEmpty()) {
                    streamHeaders.put("User-Agent", userAgent);
                }
                streamHeaders.put("Referer", "https://www.youtube.com/");
                streamHeaders.put("Origin", "https://www.youtube.com");
                streamHeaders.put("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8");

                extractionInProgress = false;
                extractionStatus = "youtube_receiver_extracted";
                playbackStatusMessage = "تم استخراج فيديو YouTube على الريسيفر: "
                        + (title == null ? "" : title) + " — " + quality;
                persistExtractionState();
                Log.i(TAG, "Receiver extracted YouTube stream: quality=" + quality
                        + ", separateAudio=" + (audioUrl != null && !audioUrl.isEmpty()));
                playContent(videoUrl, audioUrl, streamHeaders);
            }

            @Override
            public void onError(String reason) {
                if (generation != extractionGeneration || isFinishing()) return;
                extractionInProgress = false;
                extractionStatus = "youtube_receiver_extract_failed";
                playbackStatusMessage = "فشل استخراج YouTube على الريسيفر: " + reason;
                persistExtractionState();
                Log.e(TAG, "Receiver-side YouTube extraction failed: " + reason);
                showExtractionFailureFallback(pageUrl);
            }
        });
    }

    private void resolveSharedVideoToStream(String pageUrl) {
        // Receiver accepts direct media URLs from Cast. A YouTube watch page means
        // sender-side extraction did not complete; do not run extractors on the TV.
        if (isYouTubeUrl(pageUrl)) {
            extractionInProgress = false;
            extractionStatus = "youtube_page_rejected";
            playbackStatusMessage = "لم يصل رابط فيديو مباشر من Cast.";
            persistExtractionState();
            showExtractionFailureFallback(pageUrl);
            return;
        }
        // Known IPTV providers expose the HLS manifest directly at
        // /<category>/<channel-id>/index.html (including /pages/<id>/index.html).
        // Do not run page/social extractors against this URL: pass it straight to
        // Media3 with the HLS MIME override applied by createPlaybackMediaItem().
        if (isKnownHtmlHlsPlaylistUrl(pageUrl)) {
            extractionInProgress = false;
            extractionStatus = "known_html_hls_direct";
            playbackStatusMessage = "تم التعرف على رابط HLS مباشر";
            persistExtractionState();
            playContent(pageUrl, null);
            return;
        }

        final int generation = ++extractionGeneration;
        lastJsonIndexDiagnostic = "";
        android.content.SharedPreferences settings =
                getSharedPreferences("receiver_settings", MODE_PRIVATE);
        boolean useCobalt = settings.getBoolean("receiver_engine_cobalt", true);
        boolean jsonIndexInput = false;
        try {
            String inputPath = new URL(pageUrl).getPath();
            jsonIndexInput = inputPath != null
                    && inputPath.toLowerCase(Locale.ROOT).endsWith(".json");
        } catch (Exception ignored) { }
        // A direct JSON index is not an ordinary page. Always run the structural
        // page/JSON parser for it, even if the user disabled generic page extraction.
        boolean usePage = settings.getBoolean("receiver_engine_page", true) || jsonIndexInput;
        boolean useSocial = settings.getBoolean("receiver_engine_social", true);
        final boolean directJsonIndexInput = jsonIndexInput;
        if (directJsonIndexInput) {
            lastJsonIndexDiagnostic = "JSON index resolver: enabled for direct .json URL";
        }
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
                    boolean jsonIndexRequest = false;
                    try {
                        String requestPath = new URL(pageUrl).getPath();
                        jsonIndexRequest = requestPath != null
                                && requestPath.toLowerCase(Locale.ROOT).endsWith(".json");
                    } catch (Exception ignored) { }
                    connection.setRequestProperty("Accept", jsonIndexRequest
                            ? "application/json,text/plain,application/*;q=0.9,*/*;q=0.8"
                            : "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    connection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                    // Reuse the TikTok WebView request context when resolving the original
                    // watch page; otherwise the Receiver fetches it as an unrelated client.
                    if (isTikTokSourcePageUrl(pageUrl) && pendingPlaybackHeaders != null) {
                        for (java.util.Map.Entry<String, String> entry : pendingPlaybackHeaders.entrySet()) {
                            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                            String value = entry.getValue();
                            if (value == null || value.isEmpty() || value.length() > ("cookie".equals(key) ? 8192 : 2048)
                                    || value.contains("\r") || value.contains("\n")) continue;
                            if ("user-agent".equals(key) || "cookie".equals(key) || "referer".equals(key)
                                    || "origin".equals(key) || "accept".equals(key) || "accept-language".equals(key)
                                    || "sec-fetch-site".equals(key) || "sec-fetch-mode".equals(key)
                                    || "sec-fetch-dest".equals(key) || "sec-ch-ua".equals(key)
                                    || "sec-ch-ua-mobile".equals(key) || "sec-ch-ua-platform".equals(key)
                                    || "priority".equals(key)) {
                                connection.setRequestProperty(key, value);
                            }
                        }
                    }
                    // A directly shared JSON index may rely on cookies already stored
                    // for that exact URL by the Receiver browser. Reuse only the
                    // matching CookieManager cookie; never forward cookies cross-origin.
                    if (directJsonIndexInput && connection.getRequestProperty("Cookie") == null) {
                        String storedCookie = CookieManager.getInstance().getCookie(pageUrl);
                        if (storedCookie != null && !storedCookie.isEmpty()
                                && storedCookie.length() <= 8192
                                && !storedCookie.contains("\r") && !storedCookie.contains("\n")) {
                            connection.setRequestProperty("Cookie", storedCookie);
                        }
                    }
                    // Record only whether request context was available; never log cookie or header values.
                    final boolean sentCookie = connection.getRequestProperty("Cookie") != null
                            && !connection.getRequestProperty("Cookie").isEmpty();
                    final boolean sentReferer = connection.getRequestProperty("Referer") != null
                            && !connection.getRequestProperty("Referer").isEmpty();
                    final boolean sentOrigin = connection.getRequestProperty("Origin") != null
                            && !connection.getRequestProperty("Origin").isEmpty();
                    int status = connection.getResponseCode();
                    String responseType = connection.getContentType();
                    if (jsonIndexRequest) {
                        lastJsonIndexDiagnostic = "HTTP " + status + "; type="
                                + (responseType == null ? "unknown" : responseType);
                    }
                    if (status >= 200 && status < 400) {
                        String type = responseType;
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
                        String pageContent = html.toString();
                        if (jsonIndexRequest) {
                            String responseHost = "";
                            try {
                                String host = connection.getURL().getHost();
                                responseHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
                            } catch (Exception ignored) { }
                            String disposition = connection.getHeaderField("Content-Disposition");
                            String safeDisposition = "none";
                            if (disposition != null) {
                                String lowerDisposition = disposition.toLowerCase(Locale.ROOT);
                                safeDisposition = lowerDisposition.startsWith("attachment")
                                        ? "attachment" : lowerDisposition.startsWith("inline")
                                        ? "inline" : "present";
                            }
                            String finalPath = "";
                            try {
                                String pathOnly = connection.getURL().getPath();
                                finalPath = pathOnly == null ? "" : pathOnly;
                                if (finalPath.length() > 160) finalPath = finalPath.substring(0, 160);
                            } catch (Exception ignored) { }
                            boolean redirected = !pageUrl.equals(connection.getURL().toString());
                            String declaredLength = connection.getHeaderField("Content-Length");
                            lastJsonIndexDiagnostic = buildJsonIndexDiagnostic(status, responseType, pageContent)
                                    + "; final-host=" + responseHost
                                    + "; final-path=" + (finalPath.isEmpty() ? "/" : finalPath)
                                    + "; redirected=" + (redirected ? "yes" : "no")
                                    + "; cookie=" + (sentCookie ? "present" : "absent")
                                    + "; referer=" + (sentReferer ? "present" : "absent")
                                    + "; origin=" + (sentOrigin ? "present" : "absent")
                                    + "; length=" + (declaredLength == null ? "unknown" : declaredLength)
                                    + "; disposition=" + safeDisposition;
                        }
                        // Some IPTV endpoints return the HLS manifest body from index.html
                        // (often text/plain or text/html) instead of exposing a .m3u8 URL.
                        // Confirm the manifest signature before handing the original URL to
                        // Media3; otherwise continue parsing the page for its real stream URL.
                        String manifestProbe = pageContent;
                        if (!manifestProbe.isEmpty() && manifestProbe.charAt(0) == 0xFEFF) manifestProbe = manifestProbe.substring(1);
                        manifestProbe = manifestProbe.trim();
                        // A few proxy endpoints wrap the actual playlist in a JSON string
                        // scalar. JSONTokener reports that wrapper as JSON=scalar, although
                        // the decoded string itself is a valid HLS manifest.
                        boolean decodedJsonString = false;
                        if (directJsonIndexInput && !manifestProbe.startsWith("#EXTM3U")) {
                            try {
                                Object scalar = new org.json.JSONTokener(manifestProbe).nextValue();
                                if (scalar instanceof String) {
                                    String decoded = ((String) scalar).trim();
                                    if (!decoded.isEmpty() && decoded.charAt(0) == 0xFEFF) {
                                        decoded = decoded.substring(1).trim();
                                    }
                                    if (decoded.startsWith("#EXTM3U")) {
                                        manifestProbe = decoded;
                                        decodedJsonString = true;
                                    }
                                }
                            } catch (Exception ignored) { }
                        }
                        if (manifestProbe.startsWith("#EXTM3U")) {
                            // The body, not the .json suffix or Content-Type, is authoritative.
                            // Remember the exact final URL so Media3 uses its HLS parser.
                            detectedHlsManifestUrls.add(connection.getURL().toString());
                            if (decodedJsonString) {
                                lastJsonIndexDiagnostic += "; decoded-body=JSON-string-wrapped-HLS";
                            }
                            run.candidate(connection.getURL().toString(), "HLS manifest body", false, true);
                            return;
                        }
                        String result = extractDirectMediaFromHtml(pageContent, pageUrl);
                        // A number of IPTV/player pages expose only a small index.html
                        // shell and keep the actual stream URLs in a same-origin index.json.
                        // Follow a few explicitly referenced JSON config files, bounded by
                        // size/time and without forwarding cookies to another host.
                        if (result == null) {
                            result = extractLinkedJsonMedia(pageContent, pageUrl);
                        }
                        if (result != null && (isDirectStreamUrl(result)
                                || isJsonMediaFieldCandidate(pageContent, pageUrl, result))) {
                            run.candidate(result, "Page/JSON Metadata", false, false);
                            return;
                        }
                    }
                } catch (Exception e) {
                    if (directJsonIndexInput) {
                        // Keep diagnostics useful without recording exception messages,
                        // which can contain signed URLs or other request details.
                        lastJsonIndexDiagnostic = (lastJsonIndexDiagnostic == null
                                ? "" : lastJsonIndexDiagnostic)
                                + "; request-error=" + e.getClass().getSimpleName();
                    }
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
            String socialCookie = null;
            if (isTikTokSourcePageUrl(pageUrl) && pendingPlaybackHeaders != null) {
                socialCookie = pendingPlaybackHeaders.get("cookie");
            }
            if (socialCookie == null || socialCookie.isEmpty()) {
                socialCookie = android.webkit.CookieManager.getInstance().getCookie(pageUrl);
            }
            FastMediaExtractor.extractDirectMedia(pageUrl, socialCookie, new FastMediaExtractor.ExtractorCallback() {
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
                // A directly shared .json URL can itself return an HLS playlist.
                // In that case the confirmed manifest URL is intentionally the same
                // as the incoming URL; do not reject it as a non-extracted page.
                if (!isHttpUrl(url) || (pageUrl.equals(url) && !contentTypeResult)) {
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
                    if (isTikTokSourcePageUrl(pageUrl)) {
                        playContent(bestUrl, null, pendingPlaybackHeaders);
                    } else {
                        playContent(bestUrl, null);
                    }
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

    private static final String VLC_PACKAGE = "org.videolan.vlc";

    private boolean isAmazonFireTvOrFireDevice() {
        String manufacturer = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER;
        String brand = android.os.Build.BRAND == null ? "" : android.os.Build.BRAND;
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL;
        String product = android.os.Build.PRODUCT == null ? "" : android.os.Build.PRODUCT;
        String device = android.os.Build.DEVICE == null ? "" : android.os.Build.DEVICE;
        String identity = (manufacturer + " " + brand + " " + model + " " + product + " " + device)
                .toLowerCase(Locale.ROOT);
        return identity.contains("amazon") || identity.contains("aft")
                || identity.contains("firetv") || identity.contains("fire tv");
    }

    private boolean isVlcInstalled() {
        try {
            getPackageManager().getPackageInfo(VLC_PACKAGE, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openVlcStoreListing() {
        boolean amazonDevice = isAmazonFireTvOrFireDevice();
        String storeUri = amazonDevice
                ? "amzn://apps/android?p=" + VLC_PACKAGE
                : "market://details?id=" + VLC_PACKAGE;
        String webUri = amazonDevice
                ? "https://www.amazon.com/gp/mas/dl/android?p=" + VLC_PACKAGE
                : "https://play.google.com/store/apps/details?id=" + VLC_PACKAGE;
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(storeUri)));
            playbackStatusMessage = amazonDevice
                    ? "VLC غير مثبت؛ تم فتح متجر Amazon Appstore لتنزيله."
                    : "VLC غير مثبت؛ تم فتح Google Play لتنزيله.";
            extractionStatus = "vlc_install_requested";
            persistExtractionState();
            return;
        } catch (Exception storeError) {
            Log.w(TAG, "Could not open the device app store for VLC; opening the official store page.");
        }
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(webUri)));
            playbackStatusMessage = "VLC غير مثبت؛ تم فتح صفحة التنزيل الرسمية.";
            extractionStatus = "vlc_install_requested";
            persistExtractionState();
        } catch (Exception webError) {
            playbackStatusMessage = "VLC غير مثبت، وتعذر فتح متجر التطبيقات أو صفحة التنزيل.";
            extractionStatus = "vlc_unavailable";
            persistExtractionState();
            Toast.makeText(this, playbackStatusMessage, Toast.LENGTH_LONG).show();
        }
    }

    /** Automatically hands failed media to the separately installed VLC app. */
    private void launchExternalVlcFallback(String mediaUrl) {
        if (externalVlcFallbackLaunched || isFinishing() || isDestroyed()) return;
        externalVlcFallbackLaunched = true;
        String targetUrl = mediaUrl;
        if (!isHttpUrl(targetUrl)) targetUrl = originalIncomingMediaUrl;
        if (!isHttpUrl(targetUrl)) targetUrl = currentPlaybackFallbackUrl;

        if (!isVlcInstalled()) {
            Log.i(TAG, "VLC app is not installed; routing to the device's app store.");
            openVlcStoreListing();
            return;
        }
        if (isHttpUrl(targetUrl)) {
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.setClassName(VLC_PACKAGE, VLC_PACKAGE + ".StartActivity");
                intent.putExtra("title", "AboEzzat Receiver");
                startActivity(intent);
                extractionStatus = "external_vlc_fallback";
                playbackStatusMessage = "تعذر تشغيل الفيديو داخل الريسيفر؛ تم تحويله تلقائيًا إلى تطبيق VLC.";
                persistExtractionState();
                Log.i(TAG, "Automatically handed failed media to the installed VLC app.");
                return;
            } catch (Exception explicitError) {
                Log.w(TAG, "VLC explicit playback activity failed; trying package-scoped VIEW.", explicitError);
            }
            try {
                android.content.Intent intent = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.setPackage(VLC_PACKAGE);
                intent.putExtra("title", "AboEzzat Receiver");
                startActivity(intent);
                extractionStatus = "external_vlc_fallback";
                playbackStatusMessage = "تعذر تشغيل الفيديو داخل الريسيفر؛ تم تحويله تلقائيًا إلى تطبيق VLC.";
                persistExtractionState();
                return;
            } catch (Exception packageError) {
                Log.w(TAG, "Installed VLC could not accept the media URL; opening its store listing.");
            }
        }
        openVlcStoreListing();
    }

    private void showExtractionFailureFallback(String pageUrl) {
        playbackStatusMessage = "تعذر استخراج رابط مباشر؛ جاري تحويل الرابط الأصلي إلى VLC.";
        if (pageUrl != null && pageUrl.toLowerCase(Locale.ROOT).contains(".json")
                && lastJsonIndexDiagnostic != null && !lastJsonIndexDiagnostic.isEmpty()) {
            playbackStatusMessage += "\nتشخيص JSON: " + lastJsonIndexDiagnostic;
        }
        persistExtractionState();
        recordReceiverFailure("استخراج الرابط", pageUrl, "", playbackStatusMessage);
        launchExternalVlcFallback(pageUrl);
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
        if (requestCode == 9318) {
            // The authoritative check runs in onResume against PackageManager's installed version.
            return;
        }
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
            settings.setAllowContentAccess(false);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            }
            settings.setGeolocationEnabled(false);
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
                CookieManager.getInstance().setAcceptThirdPartyCookies(fallbackWebView,
                        getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE).getBoolean("cookies", true));
            }
            fallbackWebView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                    Uri uri = request == null ? null : request.getUrl();
                    if (uri == null || !isHttpUrl(uri.toString())) return true;
                    return getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                            .getBoolean("adblock_enabled", true) && isKnownBrowserAdRequest(uri.toString());
                }

                @Override
                @SuppressWarnings("deprecation")
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    if (!isHttpUrl(url)) return true;
                    return getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                            .getBoolean("adblock_enabled", true) && isKnownBrowserAdRequest(url);
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
                public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                        android.net.http.SslError error) {
                    // Never let the browser bypass certificate validation, even after a user prompt.
                    handler.cancel();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "تم إيقاف الاتصال: شهادة أمان الموقع غير موثوقة", Toast.LENGTH_LONG).show());
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
                    popupSettings.setJavaScriptEnabled(getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                            .getBoolean("javascript", true));
                    popupSettings.setDomStorageEnabled(true);
                    popupSettings.setJavaScriptCanOpenWindowsAutomatically(false);
                    popupSettings.setSupportMultipleWindows(false);
                    popupSettings.setAllowFileAccess(false);
                    popupSettings.setAllowContentAccess(false);
                    // Keep file-origin pages from reaching local files or cross-origin network resources.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN) {
                        popupSettings.setAllowFileAccessFromFileURLs(false);
                        popupSettings.setAllowUniversalAccessFromFileURLs(false);
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        popupSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
                    }
                    popupSettings.setGeolocationEnabled(false);
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        popupSettings.setSafeBrowsingEnabled(true);
                    }
                    boolean acceptBrowserCookies = getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                            .getBoolean("cookies", true);
                    CookieManager.getInstance().setAcceptCookie(acceptBrowserCookies);
                    if (android.os.Build.VERSION.SDK_INT >= 21) {
                        CookieManager.getInstance().setAcceptThirdPartyCookies(popup,
                                getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                                        .getBoolean("cookies", true));
                    }
                    popup.setWebViewClient(new WebViewClient() {
                        @Override public boolean shouldOverrideUrlLoading(WebView w, android.webkit.WebResourceRequest req) {
                            Uri u = req == null ? null : req.getUrl();
                            if (u == null || !isHttpUrl(u.toString())) return true;
                            return getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                                    .getBoolean("adblock_enabled", true) && isKnownBrowserAdRequest(u.toString());
                        }
                        @Override @SuppressWarnings("deprecation")
                        public boolean shouldOverrideUrlLoading(WebView w, String url) {
                            if (!isHttpUrl(url)) return true;
                            return getSharedPreferences("AboEzzatBrowser", MODE_PRIVATE)
                                    .getBoolean("adblock_enabled", true) && isKnownBrowserAdRequest(url);
                        }
                        @Override public void onReceivedSslError(WebView w, android.webkit.SslErrorHandler handler,
                                android.net.http.SslError error) {
                            // Never proceed through an invalid, expired, or intercepted TLS certificate.
                            handler.cancel();
                            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                    "تم إيقاف الاتصال: شهادة أمان الموقع غير موثوقة", Toast.LENGTH_LONG).show());
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

    // Network media protocols accepted by the native player. Keep ordinary page/API
    // fetching HTTP(S)-only; RTSP is accepted only as a playback source.
    private boolean isPlayableNetworkUrl(String value) {
        if (isHttpUrl(value)) return true;
        if (value == null || value.trim().isEmpty()) return false;
        try {
            Uri uri = Uri.parse(value.trim());
            return "rtsp".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
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

    // Raw whitespace is not valid inside an HTTP URL and some Drama Live
    // endpoints accidentally arrive with spaces inserted by the source app.
    // Remove literal ASCII whitespace only; preserve encoded values such as %20.
    private String normalizeIncomingHttpUrl(String value) {
        if (value == null) return null;
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch > 0x20 && ch != 0x7F) normalized.append(ch);
        }
        return normalized.toString();
    }

    // natively play media URLs; resolve page URLs to a direct stream instead of opening another app.
    private void queueIncomingMedia(String mediaUrl, String audioUrl) {
        queueIncomingMedia(mediaUrl, audioUrl, java.util.Collections.emptyMap());
    }

    private void queueIncomingMedia(String mediaUrl, String audioUrl, java.util.Map<String, String> headers) {
        queueIncomingMedia(mediaUrl, audioUrl, headers, null);
    }


    private boolean isLikelySeparateAudioTrack(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("mp4a_")
                || (lower.contains("vencode_tag=") && lower.contains("audio"))
                || lower.contains("mime=audio")
                || lower.contains("audio_track")
                || lower.matches(".*\\.(m4a|aac|mp3|oga|opus)(?:[?#].*)?$");
    }

    private void queueIncomingMedia(String mediaUrl, String audioUrl,
                                    java.util.Map<String, String> headers, String sourceUrl) {
        mediaUrl = normalizeIncomingHttpUrl(mediaUrl);
        audioUrl = normalizeIncomingHttpUrl(audioUrl);
        sourceUrl = normalizeIncomingHttpUrl(sourceUrl);
        String originalMediaCandidate = mediaUrl;
        externalVlcFallbackLaunched = false;
        extractionStatus = "preparing";
        playbackStatusMessage = "Receiver استلم الرابط؛ جاري تجهيز مشغّل الفيديو.";
        persistExtractionState();
        pendingBrowserUrl = "";
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);

        if (mediaUrl != null && !mediaUrl.trim().isEmpty() && !isPlayableNetworkUrl(mediaUrl)) mediaUrl = null;
        // Separate audio renditions remain HTTP(S); RTSP carries its own session tracks.
        if (audioUrl != null && !audioUrl.trim().isEmpty() && !isHttpUrl(audioUrl)) audioUrl = null;
        if (audioUrl == null && isLikelySeparateAudioTrack(mediaUrl)) {
            audioUrl = mediaUrl;
            mediaUrl = null;
        }
        if ((mediaUrl == null || mediaUrl.trim().isEmpty())
                && (audioUrl == null || audioUrl.trim().isEmpty())) {
            String failedSource = isHttpUrl(sourceUrl) ? sourceUrl
                    : (isHttpUrl(originalMediaCandidate) ? originalMediaCandidate : "");
            if (!failedSource.isEmpty()) {
                originalIncomingMediaUrl = failedSource;
                String reason = "تعذر تجهيز رابط الوسائط المستلم؛ جاري تحويل الرابط الأصلي إلى VLC.";
                recordReceiverFailure("استخراج الرابط", failedSource, "", reason);
                launchExternalVlcFallback(failedSource);
            }
            return;
        }

        boolean incomingVideo = mediaUrl != null && !mediaUrl.trim().isEmpty();
        boolean incomingAudio = audioUrl != null && !audioUrl.trim().isEmpty();
        // A new video (or a duplicate audio rendition) starts a separate capture
        // rather than merging unrelated items into the current pending item.
        if (pendingPlayback != null && incomingVideo
                && pendingVisualUrl != null && !pendingVisualUrl.isEmpty()) {
            dispatchPendingIncomingMedia();
        } else if (pendingPlayback != null && incomingAudio
                && pendingAudioUrl != null && !pendingAudioUrl.isEmpty()) {
            dispatchPendingIncomingMedia();
        }

        if (incomingVideo) pendingVisualUrl = mediaUrl;
        if (incomingAudio) pendingAudioUrl = audioUrl;
        // Replace request context on every incoming item. Never let headers or a
        // source page from a previous TikTok stream leak into a new playback.
        pendingPlaybackHeaders = headers == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(headers);
        pendingPlaybackSourceUrl = sourceUrl == null ? "" : sourceUrl;

        if (pendingPlayback != null) mediaHandler.removeCallbacks(pendingPlayback);
        pendingPlayback = this::dispatchPendingIncomingMedia;
        mediaHandler.postDelayed(pendingPlayback, 700L);
    }

    private void dispatchPendingIncomingMedia() {
        String mediaUrl = pendingVisualUrl;
        String audioUrl = pendingAudioUrl;
        java.util.Map<String, String> headers = pendingPlaybackHeaders == null
                ? java.util.Collections.emptyMap()
                : new java.util.LinkedHashMap<>(pendingPlaybackHeaders);
        String sourceUrl = pendingPlaybackSourceUrl;
        if (pendingPlayback != null) mediaHandler.removeCallbacks(pendingPlayback);
        pendingPlayback = null;
        pendingVisualUrl = null;
        pendingAudioUrl = null;
        pendingPlaybackHeaders = java.util.Collections.emptyMap();
        pendingPlaybackSourceUrl = null;
        processIncomingMedia(mediaUrl, audioUrl, headers, sourceUrl);
    }

    private void processIncomingMedia(String mediaUrl, String audioUrl,
                                      java.util.Map<String, String> headers, String sourceUrl) {
        String originalMediaUrl = mediaUrl;
        String originalAudioUrl = audioUrl;
        originalIncomingMediaUrl = sourceUrl != null && !sourceUrl.trim().isEmpty()
                ? sourceUrl : (mediaUrl == null ? (audioUrl == null ? "" : audioUrl) : mediaUrl);
        currentPlaybackFallbackUrl = isYouTubeUrl(sourceUrl) ? sourceUrl
                : (isYouTubeUrl(mediaUrl) ? mediaUrl : "");
        boolean hasMedia = mediaUrl != null && !mediaUrl.trim().isEmpty();
        boolean hasAudio = audioUrl != null && !audioUrl.trim().isEmpty();

        // TikTok LIVE pull-* URLs are already extracted, signed CDN streams.
        // Do not discard them and re-resolve the source page (which can lose the
        // captured media URL and its request headers). Keep the legacy page refresh
        // behavior only for standard TikTok video CDN URLs.
        if (hasMedia && !hasAudio && isTikTokCdnVideoUrl(mediaUrl)
                && !isTikTokLiveCdnVideoUrl(mediaUrl)
                && !isTikTokSignedMp4CdnUrl(mediaUrl)
                && isTikTokSourcePageUrl(sourceUrl)) {
            resolveSharedVideoToStream(sourceUrl);
            return;
        }
        if (!hasMedia && !hasAudio) {
            if ((originalMediaUrl != null && !originalMediaUrl.trim().isEmpty())
                    || (originalAudioUrl != null && !originalAudioUrl.trim().isEmpty())) {
                String reason = "الرابط المستلم لم يكن عنوان HTTP/HTTPS صالحًا أو لم يكن مدعومًا.";
                recordReceiverFailure("استلام الرابط", originalIncomingMediaUrl, "", reason);
                if (isHttpUrl(originalIncomingMediaUrl)) {
                    launchExternalVlcFallback(originalIncomingMediaUrl);
                } else {
                    Toast.makeText(this, "رابط غير مدعوم؛ استخدم رابط HTTP أو HTTPS صالحًا.", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        // A known index.html endpoint may be either an HLS manifest served from an
        // .html path or a real HTML player shell. Inspect it before choosing playback.
        if (hasMedia && isDirectStreamUrl(mediaUrl)) {
            extractionInProgress = false;
            extractionAttemptCount = 0;
            extractionStatus = "preparing";
            playbackStatusMessage = "تم تحديد رابط مباشر؛ جاري تحميله في مشغّل الفيديو.";
            persistExtractionState();
        }
        if (hasMedia && !isImageUrl(mediaUrl) && !isDirectStreamUrl(mediaUrl)
                && !isPhoneLocalMediaUrl(mediaUrl) && !hasAudio
                && (mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))) {
            if (isYouTubeUrl(mediaUrl)) extractYouTubeOnReceiver(mediaUrl);
            else resolveSharedVideoToStream(mediaUrl);
            return;
        }
        playContent(mediaUrl, audioUrl, headers);
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
        if (playerSettingsButton != null && playerSettingsButton.hasFocus()) return false;
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK || keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
            resetImageTransform();
            hideImage();
            showWelcome();
            return true;
        }
        boolean wasVisible = imageControlsVisible;
        showImageControls();
        if (!wasVisible || (playerSettingsButton != null && !playerSettingsButton.hasFocus())) {
            if (playerSettingsButton != null) playerSettingsButton.requestFocus();
            return true;
        }
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
                showPlayerController();
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

            if ("dlna_seek".equals(command.substring(0, Math.min(command.length(), 9)))) {
                try {
                    long seekMs = Long.parseLong(command.substring("dlna_seek:".length()));
                    if (player != null) player.seekTo(Math.max(0L, seekMs));
                } catch (Exception ignored) { }
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
                    case "dlna_play":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PLAY;
                        break;
                    case "dlna_pause":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_PAUSE;
                        break;
                    case "dlna_stop":
                        keyCode = android.view.KeyEvent.KEYCODE_MEDIA_STOP;
                        break;
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
                case "dlna_play":
                    if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
                    player.play();
                    break;
                case "dlna_pause":
                    player.pause();
                    break;
                case "dlna_stop":
                    savePlaybackPositionLocked();
                    player.stop();
                    player.clearMediaItems();
                    currentPlaybackUrl = "";
                    hideImage();
                    showWelcome();
                    break;
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
        stopTikTokFfmpegPipeline();
        
        synchronized (playerLock) {
            if (player != null) {
                if (pendingStartupPlayback != null) {
                    startupBufferHandler.removeCallbacks(pendingStartupPlayback);
                    pendingStartupPlayback = null;
                }
                savePlaybackPositionLocked();
                player.stop();
                player.clearMediaItems();
            }
        }
        // Preserve the last saved position; do not overwrite it with position zero on shutdown.
        currentPlaybackUrl = "";
    }

    private void rememberPlaybackUrl(String url) {
        if (url == null || url.trim().isEmpty() || isCastMediaPortUrl(url)) return;
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
                if (isHttpUrl(url) && !isCastMediaPortUrl(url) && !playbackQueue.contains(url)) playbackQueue.add(url);
            }
            playbackQueueIndex = playbackQueue.size() - 1;
            loadPlaybackHistoryItems();
            // Remove any previously persisted bearer-token media URLs from both history stores.
            savePlaybackHistory();
            savePlaybackHistoryItems();

        } catch (Exception e) {
            Log.w(TAG, "Unable to load playback history", e);
            playbackQueue.clear();
            playbackQueueIndex = -1;
        }
    }


    private String historyTitleForUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getLastPathSegment();
            if (path != null && !path.trim().isEmpty()) {
                path = path.replaceAll("[._-]+", " ").trim();
                if (path.length() > 52) path = path.substring(0, 49) + "...";
                return path;
            }
            return uri.getHost() == null ? "فيديو محفوظ" : uri.getHost();
        } catch (Exception e) {
            return "فيديو محفوظ";
        }
    }

    private void loadPlaybackHistoryItems() {
        playbackHistoryItems.clear();
        try {
            org.json.JSONArray array = new org.json.JSONArray(
                    playbackPrefs.getString("recent_playback_items", "[]"));
            for (int i = 0; i < array.length() && playbackHistoryItems.size() < 100; i++) {
                org.json.JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String video = item.optString("video", "");
                String audio = item.optString("audio", "");
                String title = item.optString("title", "");
                java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                org.json.JSONObject storedHeaders = item.optJSONObject("headers");
                if (storedHeaders != null) {
                    java.util.Iterator<String> names = storedHeaders.keys();
                    while (names.hasNext()) {
                        String key = names.next();
                        String value = storedHeaders.optString(key, "");
                        if (!value.isEmpty()) headers.put(key, value);
                    }
                }
                if ((isHttpUrl(video) && !isCastMediaPortUrl(video))
                        || (isHttpUrl(audio) && !isCastMediaPortUrl(audio))) {
                    if (!isHttpUrl(video) || isCastMediaPortUrl(video)) video = "";
                    if (!isHttpUrl(audio) || isCastMediaPortUrl(audio)) audio = "";
                    playbackHistoryItems.add(new PlaybackHistoryItem(video, audio,
                            title.isEmpty() ? historyTitleForUrl(video.isEmpty() ? audio : video) : title,
                            headers));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to load paired playback history", e);
        }
        // Migrate older single-URL history entries without losing them.
        if (playbackHistoryItems.isEmpty()) {
            for (int i = playbackQueue.size() - 1; i >= 0 && playbackHistoryItems.size() < 100; i--) {
                String url = playbackQueue.get(i);
                playbackHistoryItems.add(new PlaybackHistoryItem(url, "", historyTitleForUrl(url)));
            }
            savePlaybackHistoryItems();
        }
    }

    private void savePlaybackHistoryItems() {
        if (playbackPrefs == null) return;
        org.json.JSONArray array = new org.json.JSONArray();
        for (PlaybackHistoryItem item : playbackHistoryItems) {
            org.json.JSONObject object = new org.json.JSONObject();
            try {
                object.put("video", item.videoUrl);
                object.put("audio", item.audioUrl);
                object.put("title", item.title);
                org.json.JSONObject storedHeaders = new org.json.JSONObject();
                if (item.headers != null) {
                    for (java.util.Map.Entry<String, String> entry : item.headers.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            storedHeaders.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                object.put("headers", storedHeaders);
                array.put(object);
            } catch (Exception ignored) { }
        }
        playbackPrefs.edit().putString("recent_playback_items", array.toString()).apply();
    }

    private java.util.Map<String, String> historySafeHeaders(java.util.Map<String, String> source) {
        java.util.Map<String, String> safe = new java.util.LinkedHashMap<>();
        if (source == null) return safe;
        for (java.util.Map.Entry<String, String> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            if (("user-agent".equals(key) || "referer".equals(key) || "origin".equals(key)
                    || "accept".equals(key) || "accept-language".equals(key))
                    && entry.getValue().length() <= 2048
                    && !entry.getValue().contains("\r") && !entry.getValue().contains("\n")) {
                safe.put(entry.getKey(), entry.getValue());
            }
        }
        return safe;
    }

    private void rememberPlaybackItem(String videoUrl, String audioUrl,
                                      java.util.Map<String, String> requestHeaders) {
        String video = isHttpUrl(videoUrl) && !isCastMediaPortUrl(videoUrl) ? videoUrl : "";
        String audio = isHttpUrl(audioUrl) && !isCastMediaPortUrl(audioUrl) ? audioUrl : "";
        if (video.isEmpty() && audio.isEmpty()) return;
        for (int i = playbackHistoryItems.size() - 1; i >= 0; i--) {
            PlaybackHistoryItem old = playbackHistoryItems.get(i);
            if (video.equals(old.videoUrl) && audio.equals(old.audioUrl)) {
                playbackHistoryItems.remove(i);
            }
        }
        String title = historyTitleForUrl(video.isEmpty() ? audio : video);
        playbackHistoryItems.add(new PlaybackHistoryItem(video, audio, title,
                historySafeHeaders(requestHeaders)));
        while (playbackHistoryItems.size() > 100) playbackHistoryItems.remove(0);
        savePlaybackHistoryItems();
    }


    private void loadPlaybackHistoryThumbnailAsync(PlaybackHistoryItem item, ImageView target) {
        if (item == null || target == null) return;
        final String sourceUrl = item.videoUrl == null || item.videoUrl.isEmpty()
                ? item.audioUrl : item.videoUrl;
        if (sourceUrl == null || sourceUrl.isEmpty()) return;
        target.setTag(sourceUrl);
        Thread worker = new Thread(() -> {
            Bitmap bitmap = null;
            File directory = new File(getFilesDir(), "playback_history_thumbnails");
            if (!directory.exists()) directory.mkdirs();
            File cached = new File(directory, Integer.toHexString(sourceUrl.hashCode()) + ".jpg");
            try {
                if (cached.isFile() && cached.length() > 0) {
                    bitmap = BitmapFactory.decodeFile(cached.getAbsolutePath());
                }
                if (bitmap == null) {
                    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                    try {
                        retriever.setDataSource(sourceUrl, historySafeHeaders(item.headers));
                        Bitmap frame = retriever.getFrameAtTime(1_000_000L,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (frame != null) {
                            int width = Math.min(320, frame.getWidth());
                            int height = Math.max(1, Math.round(frame.getHeight()
                                    * (width / (float) Math.max(1, frame.getWidth()))));
                            bitmap = Bitmap.createScaledBitmap(frame, width, height, true);
                            if (bitmap != frame) frame.recycle();
                            try (FileOutputStream output = new FileOutputStream(cached)) {
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output);
                                output.flush();
                            }
                        }
                    } finally {
                        retriever.release();
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "History thumbnail unavailable for this stream type");
            }
            final Bitmap result = bitmap;
            if (result != null) runOnUiThread(() -> {
                if (sourceUrl.equals(target.getTag()) && !isFinishing() && !isDestroyed()) {
                    target.setColorFilter(null);
                    target.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    target.setImageBitmap(result);
                }
            });
        }, "ReceiverHistoryThumbnail");
        worker.setDaemon(true);
        worker.start();
    }

    private String shortenHistoryUrl(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost() == null ? "" : uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String shortPath = path.length() > 26 ? "…" + path.substring(path.length() - 25) : path;
            return host + shortPath;
        } catch (Exception e) {
            return url.length() > 48 ? url.substring(0, 45) + "…" : url;
        }
    }

    private void showPlaybackHistory() {
        if (playbackHistoryItems.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("سجل المشاهدة")
                    .setMessage("لسه مفيش فيديوهات محفوظة. الفيديوهات اللي تشغّلها هتظهر هنا.")
                    .setPositiveButton("حسنًا", null).show();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = new TextView(this);
        heading.setText("آخر الفيديوهات");
        heading.setTextSize(18);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.setTextColor(0xFFFFFFFF);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView clear = new TextView(this);
        clear.setText("مسح السجل");
        clear.setTextSize(13);
        clear.setTextColor(0xFFFF8A80);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(12), dp(8), dp(12), dp(8));
        clear.setBackgroundResource(android.R.drawable.list_selector_background);
        header.addView(clear, new LinearLayout.LayoutParams(-2, dp(44)));
        root.addView(header);
        View separator = new View(this);
        separator.setBackgroundColor(0x33FFFFFF);
        root.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        java.util.ArrayList<PlaybackHistoryItem> recent = new java.util.ArrayList<>();
        for (int i = playbackHistoryItems.size() - 1; i >= 0; i--) recent.add(playbackHistoryItems.get(i));
        for (int i = 0; i < recent.size(); i++) {
            PlaybackHistoryItem item = recent.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            ImageView thumb = new ImageView(this);
            thumb.setImageResource(android.R.drawable.ic_media_play);
            thumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            thumb.setColorFilter(0xFFFFFFFF);
            thumb.setBackgroundColor(0xFF263746);
            loadPlaybackHistoryThumbnailAsync(item, thumb);
            LinearLayout.LayoutParams thumbParams = new LinearLayout.LayoutParams(dp(76), dp(54));
            thumbParams.setMargins(0, 0, dp(12), 0);
            row.addView(thumb, thumbParams);
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(this);
            title.setText(item.title);
            title.setTextColor(0xFFFFFFFF);
            title.setTextSize(15);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView url = new TextView(this);
            String displayUrl = shortenHistoryUrl(item.videoUrl.isEmpty() ? item.audioUrl : item.videoUrl);
            if (!item.audioUrl.isEmpty()) displayUrl += "  •  صوت + صورة";
            url.setText(displayUrl);
            url.setTextColor(0xFFB0BEC5);
            url.setTextSize(11);
            url.setMaxLines(1);
            url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            labels.addView(title);
            labels.addView(url);
            LinearLayout downloadActions = new LinearLayout(this);
            downloadActions.setOrientation(LinearLayout.HORIZONTAL);
            downloadActions.setGravity(Gravity.CENTER_VERTICAL);
            TextView mp4Button = new TextView(this);
            mp4Button.setText("تحميل MP4");
            mp4Button.setTextSize(12);
            mp4Button.setTextColor(0xFF80CBC4);
            mp4Button.setGravity(Gravity.CENTER);
            mp4Button.setPadding(dp(10), dp(7), dp(10), dp(7));
            mp4Button.setBackgroundResource(android.R.drawable.list_selector_background);
            TextView mp3Button = new TextView(this);
            mp3Button.setText("تحميل MP3");
            mp3Button.setTextSize(12);
            mp3Button.setTextColor(0xFF90CAF9);
            mp3Button.setGravity(Gravity.CENTER);
            mp3Button.setPadding(dp(10), dp(7), dp(10), dp(7));
            mp3Button.setBackgroundResource(android.R.drawable.list_selector_background);
            downloadActions.addView(mp4Button);
            downloadActions.addView(mp3Button);
            labels.addView(downloadActions);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
            mp4Button.setOnClickListener(v -> startHistoryDownload(item, false));
            mp3Button.setOnClickListener(v -> startHistoryDownload(item, true));
            row.setOnClickListener(v -> {
                historyScrollPosition = scroll.getScrollY();
                reopenHistoryAfterPlayback = true;
                String video = item.videoUrl;
                String audio = item.audioUrl;
                clearPendingServerMedia();
                queueIncomingMedia(video.isEmpty() ? null : video, audio.isEmpty() ? null : audio,
                        item.headers == null ? java.util.Collections.emptyMap() : item.headers);
                if (historyDialog != null) historyDialog.dismiss();
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            View divider = new View(this);
            divider.setBackgroundColor(0x22FFFFFF);
            list.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
        }
        scroll.addView(list);
        scroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            historyScrollPosition = y;
        });
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(root)
                .setNegativeButton("إغلاق", null).create();
        historyDialog = dialog;
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("مسح سجل المشاهدة؟")
                .setMessage("سيتم حذف الفيديوهات المحفوظة من هذا الجهاز.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("مسح", (d, w) -> {
                    playbackQueue.clear();
                    playbackQueueIndex = -1;
                    historyScrollPosition = 0;
                    reopenHistoryAfterPlayback = false;
                    playbackHistoryItems.clear();
                    savePlaybackHistory();
                    savePlaybackHistoryItems();
                    dialog.dismiss();
                    Toast.makeText(this, "تم مسح سجل المشاهدة", Toast.LENGTH_SHORT).show();
                }).show());
        dialog.show();
        scroll.post(() -> scroll.scrollTo(0, Math.max(0, historyScrollPosition)));
    }

    private void startHistoryDownload(PlaybackHistoryItem item, boolean asMp3) {
        if (item == null) return;
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle(asMp3 ? "تحميل MP3" : "تحميل MP4");
        progress.setMessage("جاري تجهيز الملف بأعلى جودة متاحة...");
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();
        HistoryMediaDownloader.download(this, item.videoUrl, item.audioUrl, item.title,
                item.headers, asMp3, (success, message) -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (progress.isShowing()) progress.dismiss();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }));
    }

    private void savePlaybackHistory() {
        if (playbackPrefs == null) return;
        org.json.JSONArray array = new org.json.JSONArray();
        for (String url : playbackQueue) array.put(url);
        playbackPrefs.edit().putString("recent_playback_urls", array.toString()).apply();
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

    private String buildTikTokFailureDiagnostic() {
        StringBuilder detail = new StringBuilder("TikTok playback diagnostic:");
        TikTokFfmpegHlsPipeline pipeline = tikTokFfmpegHlsPipeline;
        detail.append("\n- FFmpeg/HLS pipeline: ")
                .append(pipeline == null ? "not active" : pipeline.getDiagnostic())
                .append("\n- ").append(getTikTokRequestHeaderDiagnostic())
                .append("\n- ").append(getTikTokCdnResponseDiagnostic());
        return detail.toString();
    }

    private String getTikTokCdnResponseDiagnostic() {
        synchronized (tikTokCdnDiagnosticLock) {
            if (lastTikTokCdnResponseDiagnostic == null || lastTikTokCdnResponseDiagnostic.isEmpty()) {
                return "TikTok CDN response: no HTTP response captured";
            }
            return lastTikTokCdnResponseDiagnostic + " / http_status=" + lastTikTokCdnHttpStatus
                    + " / bytes_received=" + lastTikTokCdnBytesReceived
                    + " / transfer_ended=" + lastTikTokCdnTransferEnded
                    + " / eof_reached=" + lastTikTokCdnReachedEof
                    + " / transfer_duration_ms=" + lastTikTokCdnTransferDurationMs
                    + " / first_bytes_hex=" + lastTikTokCdnInitialBytes
                    + " / flv_probe={" + lastTikTokCdnFlvProbe + "}"
                    + " / read_error=" + lastTikTokCdnReadError;
        }
    }

    private String getTikTokRequestHeaderDiagnostic() {
        java.util.Map<String, String> headers = currentPlaybackHeaders;
        if (headers == null) headers = java.util.Collections.emptyMap();
        boolean ua = false, referer = false, origin = false, cookie = false;
        boolean fetchSite = false, fetchMode = false, fetchDest = false;
        int cookieLength = 0;
        for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            String value = entry.getValue() == null ? "" : entry.getValue();
            ua |= "user-agent".equals(key) && !value.isEmpty();
            referer |= "referer".equals(key) && !value.isEmpty();
            origin |= "origin".equals(key) && !value.isEmpty();
            if ("cookie".equals(key)) { cookie = !value.isEmpty(); cookieLength = value.length(); }
            fetchSite |= "sec-fetch-site".equals(key) && !value.isEmpty();
            fetchMode |= "sec-fetch-mode".equals(key) && !value.isEmpty();
            fetchDest |= "sec-fetch-dest".equals(key) && !value.isEmpty();
        }
        return "TikTok request context: UA=" + ua + ", Referer=" + referer
                + ", Origin=" + origin + ", Cookie=" + cookie + " (" + cookieLength + " chars)"
                + ", Sec-Fetch=" + fetchSite + "/" + fetchMode + "/" + fetchDest
                + ", headerCount=" + headers.size();
    }

    private void appendHttpResponseHeaderDiagnostics(StringBuilder detail, Throwable error) {
        if (detail == null || error == null) return;
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 6) {
            Class<?> type = current.getClass();
            while (type != null) {
                for (String fieldName : new String[]{"headerFields", "responseHeaders"}) {
                    try {
                        java.lang.reflect.Field field = type.getDeclaredField(fieldName);
                        field.setAccessible(true);
                        Object raw = field.get(current);
                        if (!(raw instanceof java.util.Map)) continue;
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) raw;
                        StringBuilder selected = new StringBuilder();
                        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                            String key = entry.getKey() == null ? "" : entry.getKey().toString();
                            String lower = key.toLowerCase(Locale.ROOT);
                            if (!("server".equals(lower) || "via".equals(lower)
                                    || "content-type".equals(lower) || "retry-after".equals(lower)
                                    || "x-cache".equals(lower) || "content-length".equals(lower))) continue;
                            String value = String.valueOf(entry.getValue());
                            if (value.length() > 120) value = value.substring(0, 120);
                            if (selected.length() > 0) selected.append("; ");
                            selected.append(key).append("=").append(value);
                        }
                        if (selected.length() > 0) detail.append(" / Response headers: ").append(selected);
                        return;
                    } catch (Exception ignored) {}
                }
                type = type.getSuperclass();
            }
            current = current.getCause();
        }
    }

    private String getPlaybackFailureDiagnostic(androidx.media3.common.PlaybackException error) {
        if (error == null) return "UNKNOWN";
        StringBuilder detail = new StringBuilder(error.getErrorCodeName());
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && depth++ < 6) {
            detail.append(" / ").append(cause.getClass().getSimpleName());
            // Read HTTP responseCode reflectively for compatibility across Media3 versions.
            if (cause.getClass().getSimpleName().contains("InvalidResponseCode")) {
                Class<?> type = cause.getClass();
                while (type != null) {
                    try {
                        java.lang.reflect.Field field = type.getDeclaredField("responseCode");
                        field.setAccessible(true);
                        detail.append(" HTTP ").append(field.getInt(cause));
                        break;
                    } catch (Exception ignored) {
                        type = type.getSuperclass();
                    }
                }
            }
            cause = cause.getCause();
        }
        appendHttpResponseHeaderDiagnostics(detail, error.getCause());
        return detail.toString();
    }

    private void playContent(String mediaUrl, String audioUrl) {
        playContent(mediaUrl, audioUrl, java.util.Collections.emptyMap());
    }

    private void playContent(String mediaUrl, String audioUrl, java.util.Map<String, String> requestHeaders) {
        final String historyVideoUrl = mediaUrl;
        final String historyAudioUrl = audioUrl;

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
        externalVlcFallbackLaunched = false;
        currentPlaybackUrl = playbackUrl;
        currentPlaybackHasVideo = mediaUrl != null && !mediaUrl.trim().isEmpty() && !isImageUrl(mediaUrl);
        currentPlaybackAudioUrl = audioUrl == null ? "" : audioUrl;
        if (!currentPlaybackHasVideo && currentPlaybackAudioUrl.isEmpty()) currentPlaybackAudioUrl = playbackUrl;
        java.util.Map<String, String> effectiveHeaders = requestHeaders == null
                ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(requestHeaders);
        // TikTok webapp-prime links are signed MP4 CDN URLs. Request them as a browser
        // media fetch when the sender did not supply the original browser headers.
        if (isTikTokCdnVideoUrl(playbackUrl)) {
            boolean hasUserAgent = false;
            boolean hasReferer = false;
            boolean hasAccept = false;
            for (String key : effectiveHeaders.keySet()) {
                if (key == null) continue;
                hasUserAgent |= "user-agent".equalsIgnoreCase(key);
                hasReferer |= "referer".equalsIgnoreCase(key);
                hasAccept |= "accept".equalsIgnoreCase(key);
            }
            if (!hasUserAgent) effectiveHeaders.put("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36");
            if (!hasReferer) effectiveHeaders.put("Referer", "https://www.tiktok.com/");
            if (!hasAccept) effectiveHeaders.put("Accept", "*/*");
        }
        requestHeaders = effectiveHeaders;
        currentPlaybackHeaders = effectiveHeaders;
        // Home shortcuts must never sit above or steal DPAD focus from ExoPlayer.
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        rememberPlaybackUrl(playbackUrl);
        rememberPlaybackItem(historyVideoUrl, historyAudioUrl, effectiveHeaders);
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
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true);
        playerView.requestFocus();

        // TikTok LIVE FLV is remuxed by the bundled FFmpeg engine into a rolling
        // local HLS playlist. All other media keeps the existing Media3 path.
        boolean tikTokFlvPlayback = isTikTokLiveCdnVideoUrl(playbackUrl)
                && playbackUrl.toLowerCase(Locale.ROOT).contains(".flv");
        stopTikTokFfmpegPipeline();
        

        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this)
                            .setLoadControl(new DefaultLoadControl.Builder()
                                    // Low startup threshold keeps fast direct links responsive.
                                    // YouTube is separately held until its adaptive 8-second startup buffer is ready.
                                    .setBufferDurationsMs(12000, 90000, 1500, 5000)
                                    .build())
                            .build();
                    applySavedPlayerSettings();
                    player.addListener(new Player.Listener() {
                        @Override
                        public void onIsPlayingChanged(boolean isPlaying) {
                            if (isPlaying) {
                                setPlayerBufferingVisible(false);
                                extractionStatus = "playing";
                                if (isTikTokLiveCdnVideoUrl(currentPlaybackUrl)
                                        && currentPlaybackUrl.toLowerCase(Locale.ROOT).contains(".flv")) {
                                    tikTokFfmpegPlaybackStarted = true;
                                    playbackStatusMessage = "TikTok LIVE يعمل عبر FFmpeg/HLS.";
                                } else {
                                    playbackStatusMessage = "بدأ تشغيل الفيديو على شاشة Receiver.";
                                }
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
                            String detail = getPlaybackFailureDiagnostic(error);
                            if (isTikTokLiveCdnVideoUrl(currentPlaybackUrl)
                                    && currentPlaybackUrl.toLowerCase(Locale.ROOT).contains(".flv")) {
                                TikTokFfmpegHlsPipeline pipeline = tikTokFfmpegHlsPipeline;
                                detail += "\n" + (pipeline == null
                                        ? "TikTok FFmpeg/HLS pipeline is no longer active."
                                        : pipeline.getDiagnostic());
                                tikTokFfmpegFailureRecorded = true;
                                if (pipeline != null) pipeline.stop();
                            } else if (isTikTokCdnVideoUrl(currentPlaybackUrl)) {
                                detail += "\n" + getTikTokRequestHeaderDiagnostic();
                                detail += "\n" + getTikTokCdnResponseDiagnostic();
                            }
                            extractionStatus = "error";
                            playbackStatusMessage = "فشل تشغيل الفيديو: " + detail;
                            recordReceiverFailure("تشغيل الوسائط",
                                    originalIncomingMediaUrl == null || originalIncomingMediaUrl.isEmpty()
                                            ? currentPlaybackUrl : originalIncomingMediaUrl,
                                    currentPlaybackUrl, detail);
                            persistExtractionState();
                            // Avoid logging full exceptions: signed CDN URLs may contain temporary tokens.
                            Log.e(TAG, "Media3 playback failed: " + detail);
                            runOnUiThread(() -> launchExternalVlcFallback(currentPlaybackUrl));
                        }

                        @Override
                        public void onPlaybackStateChanged(int playbackState) {
                            setPlayerBufferingVisible(playbackState == Player.STATE_BUFFERING
                                    || pendingStartupPlayback != null
                                    || (tikTokFfmpegHlsPipeline != null
                                    && !tikTokFfmpegPlaybackStarted
                                    && !tikTokFfmpegFailureRecorded));
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
                            if (player.getRepeatMode() == Player.REPEAT_MODE_OFF
                                    && getPlaybackRepeatMode() == Player.REPEAT_MODE_ALL
                                    && playbackQueue.size() > 1) {
                                playbackQueueIndex = 0;
                                queueIncomingMedia(playbackQueue.get(0), null);
                            }
                        }
                    });
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    playerView.setPlayer(player);
                }

                savePlaybackPositionLocked();
                player.stop();
                player.clearMediaItems();

                if (tikTokFlvPlayback) {
                    startTikTokFfmpegHlsPipeline(playbackUrl, effectiveHeaders);
                } else {
                    if (audioUrl != null
                            && !audioUrl.trim().isEmpty()) {

                        DefaultMediaSourceFactory factory = createMediaSourceFactory(requestHeaders);

                        MediaSource videoSource =
                                factory.createMediaSource(
                                        createPlaybackMediaItem(playbackUrl)
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
                                .createMediaSource(createPlaybackMediaItem(playbackUrl)));
                    }

                    player.prepare();
                    long savedPosition = playbackPrefs == null ? 0L
                            : playbackPrefs.getLong("position_" + playbackUrl.hashCode(), 0L);
                    if (savedPosition > 5000L) {
                        player.seekTo(savedPosition);
                        Toast.makeText(MainActivity.this, "تم استئناف التشغيل من آخر موضع محفوظ", Toast.LENGTH_SHORT).show();
                    }
                    // Only original YouTube shares receive the deeper startup buffer.
                    // Direct MP4/HLS and other fast streams start as soon as Media3 is ready.
                    boolean youtubeSource = isYouTubeUrl(currentPlaybackFallbackUrl)
                            || isYouTubeUrl(playbackUrl);
                    if (youtubeSource) {
                        startAfterAdaptiveBuffer(playbackUrl);
                    } else {
                        if (pendingStartupPlayback != null) {
                            startupBufferHandler.removeCallbacks(pendingStartupPlayback);
                            pendingStartupPlayback = null;
                        }
                        player.play();
                    }
                    if (isDashManifestUrl(playbackUrl)) {
                        final String diagnosticUrl = playbackUrl;
                        new Handler(Looper.getMainLooper()).postDelayed(() -> showDashPlaybackDiagnostic(diagnosticUrl), 15000L);
                    }
                }
            }
            // Keep the screen clean when a newly received video starts.
            playerView.hideController();
            focusPlayPauseAsPlayerHome();

        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
            showWelcome();
            if (currentPlaybackHasVideo) {
                launchExternalVlcFallback(currentPlaybackUrl);
            }
        }
    }

    // Some live IPTV providers serve an HLS playlist from an .html URL.
    // Media3 otherwise infers a progressive HTML resource from the suffix and
    // never starts the HLS parser. Restrict the override to the three-segment
    // /<type>/<id>/index.html playlist pattern; ordinary web pages remain unchanged.
    private void applyKnownPlaylistMimeType(MediaItem.Builder builder, String url) {
        if (builder == null || url == null) return;
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            String lowerPath = path == null ? "" : path.toLowerCase(Locale.ROOT);

            // Some providers expose a manifest at an extension-less URL and
            // identify its format only through MIME/format query parameters.
            boolean hlsManifest = lowerPath.endsWith(".m3u8")
                    || lowerUrl.contains("application/vnd.apple.mpegurl")
                    || lowerUrl.contains("application/x-mpegurl")
                    || lowerUrl.contains("format=m3u8")
                    || lowerUrl.contains("playlist.m3u8")
                    || lowerUrl.contains("chunklist");
            boolean dashManifest = lowerUrl.contains("application/dash+xml")
                    || lowerUrl.contains("format=mpd") || lowerPath.endsWith(".mpd");
            boolean smoothManifest = lowerPath.endsWith(".ism") || lowerPath.endsWith(".ismc")
                    || lowerUrl.contains(".ism/manifest") || lowerUrl.contains("format=smoothstreaming");

            boolean bodyConfirmedHls = detectedHlsManifestUrls.contains(url);
            if (hlsManifest || bodyConfirmedHls
                    || lowerPath.matches("^/[^/]+/[^/]+/index\\.html$")) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8);
                Log.i(TAG, "Treating URL as an HLS playlist");
            } else if (dashManifest) {
                builder.setMimeType(MimeTypes.APPLICATION_MPD);
                Log.i(TAG, "Treating URL as a DASH manifest");
            } else if (smoothManifest) {
                builder.setMimeType(MimeTypes.APPLICATION_SS);
                Log.i(TAG, "Treating URL as a SmoothStreaming manifest");
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not inspect playback URL for playlist MIME override", e);
        }
    }

    private MediaItem createPlaybackMediaItem(String url) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(url);
        applyKnownPlaylistMimeType(builder, url);
        // Applies only to live media: keep a moderate live-edge target instead of
        // letting the larger VOD safety buffer add excessive live latency.
        builder.setLiveConfiguration(new MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(8000L)
                .build());
        return builder.build();
    }

    // YouTube high-bitrate streams get a deeper startup buffer; ordinary fast links start promptly.
    private void startAfterAdaptiveBuffer(String requestedUrl) {
        if (pendingStartupPlayback != null) {
            startupBufferHandler.removeCallbacks(pendingStartupPlayback);
            pendingStartupPlayback = null;
        }
        final long startedAt = System.currentTimeMillis();
        final long targetBufferMs = 8000L;
        final long maxWaitMs = 15000L;
        setPlayerBufferingVisible(true);
        pendingStartupPlayback = new Runnable() {
            @Override public void run() {
                synchronized (playerLock) {
                    if (player == null || !requestedUrl.equals(currentPlaybackUrl)
                            || player.getPlaybackState() == Player.STATE_IDLE
                            || player.getPlaybackState() == Player.STATE_ENDED) {
                        pendingStartupPlayback = null;
                        return;
                    }
                    long bufferedMs = Math.max(0L,
                            player.getBufferedPosition() - player.getCurrentPosition());
                    boolean enoughBuffered = bufferedMs >= targetBufferMs;
                    boolean timedOut = System.currentTimeMillis() - startedAt >= maxWaitMs;
                    if (enoughBuffered || timedOut) {
                        pendingStartupPlayback = null;
                        Log.i(TAG, "Adaptive startup buffer: " + bufferedMs
                                + "ms, waited " + (System.currentTimeMillis() - startedAt) + "ms");
                        player.play();
                        return;
                    }
                }
                startupBufferHandler.postDelayed(this, 250L);
            }
        };
        startupBufferHandler.post(pendingStartupPlayback);
    }

    private void setPlayerBufferingVisible(boolean visible) {
        runOnUiThread(() -> {
            if (playerBufferingOverlay == null || playerView == null) return;
            boolean playbackVisible = playerView.getVisibility() == View.VISIBLE;
            boolean show = visible && playbackVisible;
            playerBufferingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void startTikTokFfmpegHlsPipeline(String sourceUrl,
                                               java.util.Map<String, String> headers) {
        stopTikTokFfmpegPipeline();
        final long attemptToken = tikTokFfmpegPlaybackAttemptToken;
        tikTokFfmpegPlaybackStarted = false;
        tikTokFfmpegFailureRecorded = false;
        extractionStatus = "preparing";
        playbackStatusMessage = "جاري التحميل...";
        if (playerBufferingText != null) {
            playerBufferingText.setText("جاري التحميل...");
        }
        setPlayerBufferingVisible(true);

        final TikTokFfmpegHlsPipeline pipeline = new TikTokFfmpegHlsPipeline(
                this, sourceUrl, headers, new TikTokFfmpegHlsPipeline.Listener() {
            @Override
            public void onPlaylistReady(File playlist) {
                if (attemptToken != tikTokFfmpegPlaybackAttemptToken
                        || !sourceUrl.equals(currentPlaybackUrl)
                        || player == null) return;
                try {
                    synchronized (playerLock) {
                        if (player == null) return;
                        String localPlaylist = Uri.fromFile(playlist).toString();
                        player.setMediaItem(createPlaybackMediaItem(localPlaylist));
                        player.prepare();
                        player.play();
                    }
                    playbackStatusMessage = "تم تجهيز TikTok LIVE؛ جاري تشغيل HLS المحلي.";
                    persistExtractionState();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (attemptToken != tikTokFfmpegPlaybackAttemptToken
                                || !sourceUrl.equals(currentPlaybackUrl)
                                || tikTokFfmpegPlaybackStarted
                                || tikTokFfmpegFailureRecorded
                                || player == null || !player.getPlayWhenReady()) return;
                        int state = player.getPlaybackState();
                        if (state == Player.STATE_READY && player.isPlaying()) return;
                        TikTokFfmpegHlsPipeline active = tikTokFfmpegHlsPipeline;
                        String detail = (active == null ? "TikTok FFmpeg/HLS pipeline unavailable."
                                : active.getDiagnostic())
                                + "\nMedia3 state=" + state
                                + ", isLoading=" + player.isLoading()
                                + ", playWhenReady=" + player.getPlayWhenReady();
                        failTikTokFfmpegPlayback(sourceUrl, detail, attemptToken);
                    }, 30000L);
                } catch (Exception error) {
                    TikTokFfmpegHlsPipeline active = tikTokFfmpegHlsPipeline;
                    String detail = (active == null ? "TikTok FFmpeg/HLS pipeline unavailable."
                            : active.getDiagnostic())
                            + "\nMedia3 setup error: " + error.getClass().getSimpleName();
                    failTikTokFfmpegPlayback(sourceUrl, detail, attemptToken);
                }
            }

            @Override
            public void onFailure(String diagnostic) {
                failTikTokFfmpegPlayback(sourceUrl, diagnostic, attemptToken);
            }

            @Override
            public void onFinished(String diagnostic) {
                if (attemptToken != tikTokFfmpegPlaybackAttemptToken
                        || !sourceUrl.equals(currentPlaybackUrl)) return;
                playbackStatusMessage = "انتهى مصدر بث TikTok LIVE.";
                persistExtractionState();
                Log.i(TAG, "TikTok FFmpeg/HLS input finished.");
            }
        });
        tikTokFfmpegHlsPipeline = pipeline;
        try {
            pipeline.start();
        } catch (Exception error) {
            failTikTokFfmpegPlayback(sourceUrl,
                    pipeline.getDiagnostic() + "\nPipeline start error: "
                            + error.getClass().getSimpleName(), attemptToken);
        }
    }

    private void failTikTokFfmpegPlayback(String sourceUrl, String diagnostic,
                                          long attemptToken) {
        if (attemptToken != tikTokFfmpegPlaybackAttemptToken
                || !sourceUrl.equals(currentPlaybackUrl)
                || tikTokFfmpegFailureRecorded) return;
        tikTokFfmpegFailureRecorded = true;
        extractionStatus = "error";
        playbackStatusMessage = "فشل تشغيل TikTok LIVE عبر FFmpeg/HLS.";
        persistExtractionState();
        setPlayerBufferingVisible(false);
        recordReceiverFailure("تشغيل TikTok LIVE عبر FFmpeg/HLS",
                originalIncomingMediaUrl == null || originalIncomingMediaUrl.isEmpty()
                        ? sourceUrl : originalIncomingMediaUrl,
                sourceUrl, diagnostic);
        TikTokFfmpegHlsPipeline activePipeline = tikTokFfmpegHlsPipeline;
        if (activePipeline != null) activePipeline.stop();
        Log.e(TAG, "TikTok FFmpeg/HLS playback failed: "
                + diagnostic.replace('\n', ' '));
        launchExternalVlcFallback(sourceUrl);
    }

    private void stopTikTokFfmpegPipeline() {
        tikTokFfmpegPlaybackAttemptToken++;
        tikTokFfmpegPlaybackStarted = false;
        TikTokFfmpegHlsPipeline pipeline = tikTokFfmpegHlsPipeline;
        tikTokFfmpegHlsPipeline = null;
        if (pipeline != null) {
            try { pipeline.stop(); } catch (Exception ignored) { }
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
        int savedRepeatMode = prefs.getInt("repeat_mode",
                prefs.getBoolean("repeat_one", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        if (savedRepeatMode != Player.REPEAT_MODE_ONE && savedRepeatMode != Player.REPEAT_MODE_ALL) {
            savedRepeatMode = Player.REPEAT_MODE_OFF;
        }
        player.setRepeatMode(savedRepeatMode == Player.REPEAT_MODE_ALL
                ? Player.REPEAT_MODE_OFF : savedRepeatMode);
        prefs.edit().putInt("repeat_mode", savedRepeatMode).apply();
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

    private int getPlaybackRepeatMode() {
        SharedPreferences prefs = getPlayerSettingsPrefs();
        int mode = prefs.getInt("repeat_mode",
                prefs.getBoolean("repeat_one", false) ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        return mode == Player.REPEAT_MODE_ONE || mode == Player.REPEAT_MODE_ALL
                ? mode : Player.REPEAT_MODE_OFF;
    }

    private String playbackRepeatModeLabel(int mode) {
        if (mode == Player.REPEAT_MODE_ONE) return "تكرار مرة واحدة";
        if (mode == Player.REPEAT_MODE_ALL) return "تكرار القائمة";
        return "التكرار متوقف";
    }

    private void updatePlaybackRepeatControl() {
        if (playerRepeatControlButton == null) return;
        int mode = getPlaybackRepeatMode();
        playerRepeatControlButton.setText(mode == Player.REPEAT_MODE_OFF ? "↻" :
                mode == Player.REPEAT_MODE_ONE ? "↻¹" : "🔁");
        playerRepeatControlButton.setContentDescription("تكرار التشغيل: " + playbackRepeatModeLabel(mode));
        playerRepeatControlButton.setAlpha(mode == Player.REPEAT_MODE_OFF ? 0.78f : 1f);
    }

    private void cyclePlaybackRepeatMode() {
        if (player == null) return;
        int current = getPlaybackRepeatMode();
        int next = current == Player.REPEAT_MODE_OFF ? Player.REPEAT_MODE_ONE
                : current == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_ALL : Player.REPEAT_MODE_OFF;
        player.setRepeatMode(next == Player.REPEAT_MODE_ONE ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        getPlayerSettingsPrefs().edit().putInt("repeat_mode", next)
                .putBoolean("repeat_one", next == Player.REPEAT_MODE_ONE).apply();
        updatePlaybackRepeatControl();
        Toast.makeText(this, playbackRepeatModeLabel(next), Toast.LENGTH_SHORT).show();
    }

    private void showPlaybackSettingsDialog() {
        if (imageView != null && imageView.getVisibility() == View.VISIBLE) {
            showImageSettingsDialog();
            return;
        }
        if (player == null || currentPlaybackUrl == null || currentPlaybackUrl.isEmpty()) {
            Toast.makeText(this, "لا يوجد محتوى يعمل حاليًا", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences prefs = getPlayerSettingsPrefs();
        String speed = String.format(Locale.US, "%.2fx",
                player.getPlaybackParameters().speed);
        String repeat = playbackRepeatModeLabel(getPlaybackRepeatMode());
        String quality = qualityLabel(prefs.getInt("max_video_height", 0));
        String[] options = {
                "📺  جودة الفيديو والبث المباشر (" + quality + ")",
                "🔊  مسار الصوت",
                "💬  مسار الترجمة",
                "↺  إعادة التشغيل من البداية",
                "⏩  سرعة التشغيل (" + speed + ")",
                "▣  أبعاد الصورة",
                "🔁  تكرار الفيديو (" + repeat + ")",
                "⏪  مدة التقديم والترجيع",
                "⏰  مؤقت النوم"
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
                        case 6: cyclePlaybackRepeatMode(); break;
                        case 7: showSeekIncrementDialog(); break;
                        case 8: showSleepTimerDialog(); break;
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void showCurrentPlaybackDownloadOptions() {
        boolean hasVideo = currentPlaybackHasVideo && currentPlaybackUrl != null && !currentPlaybackUrl.isEmpty();
        String audio = currentPlaybackAudioUrl == null ? "" : currentPlaybackAudioUrl;
        if (audio.isEmpty() && currentPlaybackUrl != null) audio = currentPlaybackUrl;
        boolean hasAudio = currentPlaybackHasVideo || !audio.isEmpty();
        if (!hasVideo && !hasAudio) {
            Toast.makeText(this, "لا توجد وسائط قابلة للتنزيل حاليًا", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> options = new java.util.ArrayList<>();
        if (hasVideo) options.add("تنزيل فيديو MP4 بأعلى جودة");
        if (hasAudio) options.add("تنزيل صوت MP3 بأعلى جودة");
        new AlertDialog.Builder(this).setTitle("تنزيل الوسائط الحالية")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    boolean asMp3 = hasVideo ? which == 1 : true;
                    String video = hasVideo ? currentPlaybackUrl : "";
                    String audioUrl = !currentPlaybackAudioUrl.isEmpty() ? currentPlaybackAudioUrl : "";
                    PlaybackHistoryItem item = new PlaybackHistoryItem(video, audioUrl,
                            "وسائط AboEzzat", currentPlaybackHeaders);
                    startHistoryDownload(item, asMp3);
                }).setNegativeButton("إلغاء", null).show();
    }

    private void showImageSettingsDialog() {
        String[] options = {"ملاءمة الصورة للشاشة", "ملء الشاشة مع قص الأطراف", "تكبير الصورة", "إعادة ضبط التكبير والتحريك"};
        new AlertDialog.Builder(this).setTitle("إعدادات عرض الصورة")
                .setItems(options, (dialog, which) -> {
                    if (imageView == null || imageView.getVisibility() != View.VISIBLE) return;
                    switch (which) {
                        case 0: imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); resetImageTransform(); break;
                        case 1: imageView.setScaleType(ImageView.ScaleType.CENTER_CROP); resetImageTransform(); break;
                        case 2: imageZoomScale = Math.min(4.0f, Math.max(1.5f, imageZoomScale * 1.5f)); applyImageTransform(); break;
                        case 3: imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); resetImageTransform(); break;
                    }
                    showImageControls();
                }).setNegativeButton("إغلاق", null).show();
    }

    private void showImageControls() {
        if (imageView == null || imageView.getVisibility() != View.VISIBLE) return;
        setImageControlsVisible(true);
        imageControlsHandler.removeCallbacks(hideImageControlsRunnable);
        imageControlsHandler.postDelayed(hideImageControlsRunnable, 2000L);
    }

    private void setImageControlsVisible(boolean visible) {
        imageControlsVisible = visible;
        boolean imageActive = imageView != null && imageView.getVisibility() == View.VISIBLE;
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(imageActive && visible ? View.VISIBLE : View.GONE);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
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
            if (android.os.Build.VERSION.SDK_INT >= 26) view.setDefaultFocusHighlightEnabled(false);
            view.setBackground(browserFocusBackground(Color.TRANSPARENT,
                    Color.TRANSPARENT, 10, false));
            view.setOnFocusChangeListener((focusedView, focused) -> {
                // Transparent at rest; a slim cool-blue outline and subtle tint
                // identify only the currently focused playback control.
                focusedView.setBackground(browserFocusBackground(
                        focused ? Color.argb(48, 105, 185, 255) : Color.TRANSPARENT,
                        focused ? Color.rgb(105, 185, 255) : Color.TRANSPARENT,
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
                int topActionId = playerSettingsControlButton == null ? View.NO_ID : playerSettingsControlButton.getId();
                playPause.setNextFocusUpId(topActionId);
                if (playerSettingsControlButton != null) playerSettingsControlButton.setNextFocusDownId(playPause.getId());
                if (playerDownloadControlButton != null) playerDownloadControlButton.setNextFocusDownId(playPause.getId());
                if (playerSubtitleControlButton != null) playerSubtitleControlButton.setNextFocusDownId(playPause.getId());
                playPause.requestFocus();
            } else {
                playerView.requestFocus();
            }
            // Initial remote focus must not reveal playback controls.
            playerView.hideController();
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
        if (playerView != null) showPlayerController();
    }

    private void showPlayerController() {
        if (playerView == null || playerView.getVisibility() != View.VISIBLE) return;
        playerView.showController();
        schedulePlayerControllerAutoHide();
    }

    private void schedulePlayerControllerAutoHide() {
        playerControlsHandler.removeCallbacks(hidePlayerControlsRunnable);
        if (playerView != null && playerView.getVisibility() == View.VISIBLE) {
            playerControlsHandler.postDelayed(hidePlayerControlsRunnable, 2000L);
        }
    }

    private void seekByConfiguredIncrement(int direction) {
        if (player == null) return;
        synchronized (playerLock) {
            long increment = player.getSeekForwardIncrement();
            long duration = player.getDuration();
            long target = Math.max(0L, player.getCurrentPosition() + (direction >= 0 ? increment : -increment));
            player.seekTo(duration > 0 ? Math.min(target, duration) : target);
        }
        if (playerView != null) showPlayerController();
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

    private void showSleepTimerDialog() {
        final long[] minutes = {15, 30, 45, 60, 90, 120, 0};
        final String[] labels = {
                "15 دقيقة", "30 دقيقة", "45 دقيقة", "ساعة واحدة",
                "ساعة ونصف", "ساعتان", "إلغاء مؤقت النوم"
        };
        new AlertDialog.Builder(this)
                .setTitle("مؤقت النوم")
                .setItems(labels, (dialog, which) -> {
                    sleepTimerHandler.removeCallbacks(sleepTimerRunnable == null
                            ? () -> { } : sleepTimerRunnable);
                    sleepTimerRunnable = null;
                    if (minutes[which] == 0) {
                        Toast.makeText(this, "تم إلغاء مؤقت النوم", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final long delayMs = minutes[which] * 60_000L;
                    sleepTimerRunnable = () -> {
                        if (player != null) {
                            player.pause();
                            if (playerView != null && playerView.getVisibility() == View.VISIBLE) {
                                showPlayerController();
                            }
                        }
                        sleepTimerRunnable = null;
                        Toast.makeText(this, "انتهى مؤقت النوم وتم إيقاف التشغيل", Toast.LENGTH_LONG).show();
                    };
                    sleepTimerHandler.postDelayed(sleepTimerRunnable, delayMs);
                    Toast.makeText(this, "سيتم إيقاف التشغيل بعد " + labels[which],
                            Toast.LENGTH_SHORT).show();
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

        private void showSubtitleOptionsDialog() {
        if (player == null || currentPlaybackUrl == null || currentPlaybackUrl.trim().isEmpty()) {
            Toast.makeText(this, "لا يوجد فيديو يعمل حاليًا", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] options = {
                "اختيار مسار ترجمة مدمج",
                "البحث عن ترجمة عربية",
                "إيقاف الترجمة"
        };
        new AlertDialog.Builder(this)
                .setTitle("الترجمة")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showPlaybackTrackDialog(C.TRACK_TYPE_TEXT);
                    } else if (which == 1) {
                        searchAndApplyArabicSubtitle();
                    } else {
                        TrackSelectionParameters.Builder builder =
                                player.getTrackSelectionParameters().buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                        player.setTrackSelectionParameters(builder.build());
                        Toast.makeText(this, "تم إيقاف الترجمة", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
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
                MediaItem.Builder videoItemBuilder = new MediaItem.Builder()
                        .setUri(currentPlaybackUrl);
                applyKnownPlaylistMimeType(videoItemBuilder, currentPlaybackUrl);
                MediaItem videoItem = videoItemBuilder
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

    private String describeTikTokFlvBytes(byte[] data, int length) {
        if (data == null || length <= 0) return "empty";
        int size = Math.min(length, data.length);
        if (size < 9) return "header=incomplete(" + size + ")";
        boolean signature = data[0] == 'F' && data[1] == 'L' && data[2] == 'V';
        int headerSize = ((data[5] & 0xFF) << 24) | ((data[6] & 0xFF) << 16)
                | ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
        StringBuilder out = new StringBuilder();
        out.append("signature=").append(signature ? "FLV" : "invalid")
                .append(", version=").append(data[3] & 0xFF)
                .append(", flags=").append(data[4] & 0xFF)
                .append(", header_size=").append(headerSize);
        if (!signature || headerSize < 9 || headerSize > size) return out.toString() + ", tags=unavailable";
        int position = headerSize + 4; // Skip PreviousTagSize0.
        int tags = 0, audio = 0, video = 0, script = 0, malformed = 0;
        StringBuilder types = new StringBuilder();
        while (position + 11 <= size && tags < 512) {
            int type = data[position] & 0xFF;
            int dataSize = ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            long next = (long) position + 11L + dataSize + 4L;
            if (next > size) {
                malformed++;
                break;
            }
            tags++;
            if (type == 8) audio++;
            else if (type == 9) video++;
            else if (type == 18) script++;
            if (types.length() < 120) {
                if (types.length() > 0) types.append(',');
                types.append(type).append(':').append(dataSize);
            }
            position = (int) next;
        }
        out.append(", tags=").append(tags)
                .append(", audio_tags=").append(audio)
                .append(", video_tags=").append(video)
                .append(", script_tags=").append(script)
                .append(", incomplete_tag=").append(malformed > 0)
                .append(", tag_types=[").append(types).append("]");
        return out.toString();
    }

    private String describeTikTokTransferError(Throwable error) {
        if (error == null) return "none";
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (out.length() > 0) out.append(" -> ");
            out.append(current.getClass().getSimpleName());
            String message = current.getMessage();
            if (message != null && !message.trim().isEmpty()) {
                // Exception messages can contain signed URLs. Keep only a short,
                // URL-redacted summary for the local diagnostic.
                String safe = redactSensitiveUrl(message);
                safe = safe.replaceAll("https?://\\S+", "[URL]");
                if (safe.length() > 140) safe = safe.substring(0, 140);
                out.append('(').append(safe).append(')');
            }
            current = current.getCause();
        }
        return out.toString();
    }

    private boolean isCastMediaPortUrl(String value) {
        try { return Uri.parse(value).getPort() == 8088; }
        catch (Exception ignored) { return false; }
    }

    private void configurePinnedCastMediaConnection(HttpURLConnection connection, String mediaUrl)
            throws Exception {
        if (isCastMediaPortUrl(mediaUrl) && !isPhoneLocalMediaUrl(mediaUrl)) {
            throw new java.security.cert.CertificateException("Unpinned or plaintext Cast media URL rejected");
        }
        if (!isPhoneLocalMediaUrl(mediaUrl)) return;
        if (!(connection instanceof HttpsURLConnection)) {
            throw new java.security.cert.CertificateException("Cast media requires HTTPS");
        }
        String host = Uri.parse(mediaUrl).getHost();
        String pin = getSharedPreferences("AboEzzatReceiverMediaTlsPins", MODE_PRIVATE)
                .getString("pin_" + host.toLowerCase(Locale.ROOT), "");
        if (pin == null || !pin.matches("[A-Za-z0-9_-]{43}")) {
            throw new java.security.cert.CertificateException("Cast media certificate has not been pinned");
        }
        final byte[] expectedPin = android.util.Base64.decode(pin,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        if (expectedPin.length != 32) throw new java.security.cert.CertificateException("Invalid media pin");
        X509TrustManager trustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0
                        || !matchesCastMediaCertificate(chain[0], expectedPin)) {
                    throw new CertificateException("Cast media certificate pin mismatch");
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new java.security.SecureRandom());
        HttpsURLConnection https = (HttpsURLConnection) connection;
        https.setSSLSocketFactory(sslContext.getSocketFactory());
        https.setHostnameVerifier((hostname, session) -> {
            if (hostname == null || !host.equalsIgnoreCase(hostname)) return false;
            try {
                java.security.cert.Certificate[] peer = session.getPeerCertificates();
                return peer.length > 0 && peer[0] instanceof X509Certificate
                        && matchesCastMediaCertificate((X509Certificate) peer[0], expectedPin);
            } catch (Exception ignored) { return false; }
        });
        connection.setInstanceFollowRedirects(false);
    }

    private OkHttpClient createPinnedCastMediaClient(String host) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("Missing media host");
        String pin = getSharedPreferences("AboEzzatReceiverMediaTlsPins", MODE_PRIVATE)
                .getString("pin_" + host.toLowerCase(Locale.ROOT), "");
        if (pin == null || !pin.matches("[A-Za-z0-9_-]{43}")) {
            throw new java.security.cert.CertificateException("Cast media certificate has not been pinned");
        }
        final byte[] expectedPin = android.util.Base64.decode(pin,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        if (expectedPin.length != 32) throw new java.security.cert.CertificateException("Invalid media pin length");
        X509TrustManager trustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("Client certificates are not accepted");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0 || !matchesCastMediaCertificate(chain[0], expectedPin)) {
                    throw new CertificateException("Cast media certificate pin mismatch");
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new java.security.SecureRandom());
        HostnameVerifier hostnameVerifier = (hostname, session) -> {
            if (hostname == null || !host.equalsIgnoreCase(hostname)) return false;
            try {
                java.security.cert.Certificate[] peer = session.getPeerCertificates();
                return peer.length > 0 && peer[0] instanceof X509Certificate
                        && matchesCastMediaCertificate((X509Certificate) peer[0], expectedPin);
            } catch (Exception ignored) { return false; }
        };
        return new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory(), trustManager)
                .hostnameVerifier(hostnameVerifier)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    private boolean matchesCastMediaCertificate(X509Certificate certificate, byte[] expectedPin) {
        try {
            certificate.checkValidity();
            byte[] actual = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(certificate.getEncoded());
            return java.security.MessageDigest.isEqual(actual, expectedPin);
        } catch (Exception ignored) { return false; }
    }

    private DefaultMediaSourceFactory createMediaSourceFactory(java.util.Map<String, String> requestHeaders) {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(15000).setReadTimeoutMs(30000)
                .setTransferListener(new androidx.media3.datasource.TransferListener() {
                    @Override
                    public void onTransferInitializing(androidx.media3.datasource.DataSource source,
                                                       androidx.media3.datasource.DataSpec dataSpec,
                                                       boolean isNetwork) {}

                    @Override
                    public void onTransferStart(androidx.media3.datasource.DataSource source,
                                                androidx.media3.datasource.DataSpec dataSpec,
                                                boolean isNetwork) {
                        if (!isTikTokCdnVideoUrl(dataSpec.uri.toString())) return;
                        String responseUrl = source.getUri() == null ? "" : source.getUri().toString();
                        StringBuilder response = new StringBuilder("TikTok CDN response: url=")
                                .append(redactSensitiveUrl(responseUrl));
                        String status = "unknown";
                        try {
                            java.lang.reflect.Method method = source.getClass().getMethod("getResponseCode");
                            Object value = method.invoke(source);
                            if (value instanceof Number) status = String.valueOf(((Number) value).intValue());
                        } catch (Exception ignored) { }
                        if (source instanceof androidx.media3.datasource.HttpDataSource) {
                            java.util.Map<String, java.util.List<String>> headers =
                                    ((androidx.media3.datasource.HttpDataSource) source).getResponseHeaders();
                            if (headers != null) {
                                for (java.util.Map.Entry<String, java.util.List<String>> entry : headers.entrySet()) {
                                    String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                                    if (!("content-type".equals(key) || "content-length".equals(key)
                                            || "server".equals(key) || "accept-ranges".equals(key)
                                            || "content-range".equals(key) || "content-encoding".equals(key)
                                            || "transfer-encoding".equals(key) || "cache-control".equals(key)
                                            || "connection".equals(key) || "location".equals(key))) continue;
                                    String value = entry.getValue() == null ? "" : String.join(", ", entry.getValue());
                                    if ("location".equals(key)) value = redactSensitiveUrl(value);
                                    if (value.length() > 180) value = value.substring(0, 180);
                                    response.append(" / ").append(key).append("=").append(value);
                                }
                            }
                        }
                        synchronized (tikTokCdnDiagnosticLock) {
                            lastTikTokCdnResponseDiagnostic = response.toString();
                            lastTikTokCdnHttpStatus = status;
                            lastTikTokCdnBytesReceived = 0L;
                            lastTikTokCdnInitialBytes = "not captured";
                            lastTikTokCdnTransferEnded = false;
                            lastTikTokCdnReachedEof = false;
                            lastTikTokCdnTransferStartMs = System.currentTimeMillis();
                            lastTikTokCdnTransferDurationMs = -1L;
                            lastTikTokCdnReadError = "none";
                            lastTikTokCdnFlvProbe = "capturing";
                        }
                        Log.i(TAG, response.toString() + " / http_status=" + status);
                    }

                    @Override
                    public void onBytesTransferred(androidx.media3.datasource.DataSource source,
                                                   androidx.media3.datasource.DataSpec dataSpec,
                                                   boolean isNetwork, int bytesTransferred) {
                        if (isTikTokCdnVideoUrl(dataSpec.uri.toString()) && bytesTransferred > 0) {
                            synchronized (tikTokCdnDiagnosticLock) {
                                lastTikTokCdnBytesReceived += bytesTransferred;
                            }
                        }
                    }

                    @Override
                    public void onTransferEnd(androidx.media3.datasource.DataSource source,
                                              androidx.media3.datasource.DataSpec dataSpec,
                                              boolean isNetwork) {
                        if (isTikTokCdnVideoUrl(dataSpec.uri.toString())) {
                            synchronized (tikTokCdnDiagnosticLock) {
                                lastTikTokCdnTransferEnded = true;
                                if (lastTikTokCdnTransferStartMs > 0L) {
                                    lastTikTokCdnTransferDurationMs =
                                            Math.max(0L, System.currentTimeMillis() - lastTikTokCdnTransferStartMs);
                                }
                            }
                        }
                    }
                });
        if (requestHeaders != null && !requestHeaders.isEmpty()) {
            java.util.Map<String, String> safe = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (("user-agent".equals(key) || "referer".equals(key) || "origin".equals(key)
                        || "accept".equals(key) || "accept-language".equals(key) || "cookie".equals(key)
                        || "sec-fetch-site".equals(key) || "sec-fetch-mode".equals(key)
                        || "sec-fetch-dest".equals(key) || "sec-ch-ua".equals(key)
                        || "sec-ch-ua-mobile".equals(key) || "sec-ch-ua-platform".equals(key)
                        || "priority".equals(key))
                        && entry.getValue() != null
                        && entry.getValue().length() <= ("cookie".equals(key) ? 8192 : 2048)
                        && !entry.getValue().contains("\r") && !entry.getValue().contains("\n")) {
                    safe.put(entry.getKey(), entry.getValue());
                }
            }
            httpFactory.setDefaultRequestProperties(safe);
        }
        if (isCastMediaPortUrl(currentPlaybackUrl) && !isPhoneLocalMediaUrl(currentPlaybackUrl)) {
            throw new IllegalStateException("Plaintext or malformed Cast media URL rejected");
        }
        androidx.media3.datasource.DataSource.Factory networkFactory = httpFactory;
        if (isPhoneLocalMediaUrl(currentPlaybackUrl)) {
            try {
                String mediaHost = Uri.parse(currentPlaybackUrl).getHost();
                OkHttpClient pinnedMediaClient = createPinnedCastMediaClient(mediaHost);
                networkFactory = new OkHttpDataSource.Factory(pinnedMediaClient);
            } catch (Exception pinError) {
                Log.e(TAG, "Pinned local media TLS setup failed; refusing plaintext fallback", pinError);
                throw new IllegalStateException("Local media certificate verification failed", pinError);
            }
        }
        androidx.media3.datasource.DataSource.Factory upstreamFactory =
                new DefaultDataSource.Factory(this, networkFactory);
        // Buffer only the first 64 KiB for bounded FLV tag diagnostics. No media
        // payload or signed URL is written to logs or persistent storage.
        androidx.media3.datasource.DataSource.Factory diagnosticFactory = () -> {
            final androidx.media3.datasource.DataSource upstream = upstreamFactory.createDataSource();
            return new androidx.media3.datasource.DataSource() {
                private boolean captureTikTok;
                private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream(4096);
                private final StringBuilder capturedHex = new StringBuilder();
                private int capturedByteCount;

                private void publishProbe() {
                    if (!captureTikTok) return;
                    byte[] bytes = captured.toByteArray();
                    String probe = describeTikTokFlvBytes(bytes, bytes.length);
                    synchronized (tikTokCdnDiagnosticLock) {
                        lastTikTokCdnFlvProbe = probe;
                        if (capturedHex.length() > 0) lastTikTokCdnInitialBytes = capturedHex.toString();
                    }
                }

                private void recordReadError(java.io.IOException error) {
                    if (!captureTikTok) return;
                    publishProbe();
                    synchronized (tikTokCdnDiagnosticLock) {
                        lastTikTokCdnReadError = describeTikTokTransferError(error);
                    }
                }

                @Override
                public void addTransferListener(androidx.media3.datasource.TransferListener listener) {
                    upstream.addTransferListener(listener);
                }

                @Override
                public long open(androidx.media3.datasource.DataSpec dataSpec) throws java.io.IOException {
                    captureTikTok = isTikTokCdnVideoUrl(dataSpec.uri.toString());
                    captured.reset();
                    capturedHex.setLength(0);
                    capturedByteCount = 0;
                    if (captureTikTok) {
                        synchronized (tikTokCdnDiagnosticLock) {
                            lastTikTokCdnInitialBytes = "waiting";
                            lastTikTokCdnFlvProbe = "waiting";
                            lastTikTokCdnReadError = "none";
                        }
                    }
                    try {
                        return upstream.open(dataSpec);
                    } catch (java.io.IOException error) {
                        recordReadError(error);
                        throw error;
                    }
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                    try {
                        int count = upstream.read(buffer, offset, length);
                        if (captureTikTok && count == androidx.media3.common.C.RESULT_END_OF_INPUT) {
                            synchronized (tikTokCdnDiagnosticLock) {
                                lastTikTokCdnReachedEof = true;
                            }
                            publishProbe();
                        }
                        if (captureTikTok && count > 0) {
                            int room = 65536 - captured.size();
                            if (room > 0) captured.write(buffer, offset, Math.min(room, count));
                            if (capturedByteCount < 16) {
                                int take = Math.min(16 - capturedByteCount, count);
                                for (int i = 0; i < take; i++) {
                                    if (capturedHex.length() > 0) capturedHex.append(' ');
                                    capturedHex.append(String.format(Locale.ROOT, "%02X",
                                            buffer[offset + i] & 0xFF));
                                }
                                capturedByteCount += take;
                                synchronized (tikTokCdnDiagnosticLock) {
                                    lastTikTokCdnInitialBytes = capturedHex.toString();
                                }
                            }
                        }
                        return count;
                    } catch (java.io.IOException error) {
                        recordReadError(error);
                        throw error;
                    }
                }

                @Override
                public android.net.Uri getUri() {
                    return upstream.getUri();
                }

                @Override
                public java.util.Map<String, java.util.List<String>> getResponseHeaders() {
                    return upstream.getResponseHeaders();
                }

                @Override
                public void close() throws java.io.IOException {
                    if (captureTikTok) publishProbe();
                    try {
                        upstream.close();
                    } catch (java.io.IOException error) {
                        recordReadError(error);
                        throw error;
                    }
                }
            };
        };
        return new DefaultMediaSourceFactory(diagnosticFactory);
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
        stopTikTokFfmpegPipeline();
        
        currentPlaybackUrl = audioUrl;
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this)
                            .setLoadControl(new DefaultLoadControl.Builder()
                                    // Deeper safety buffer for variable YouTube/googlevideo throughput.
                                    .setBufferDurationsMs(25000, 60000, 5000, 8000)
                                    .build())
                            .build();
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
        if (isCastMediaPortUrl(imageUrl) && !isPhoneLocalMediaUrl(imageUrl)) {
            Toast.makeText(this, "رابط صورة Cast غير مشفر أو غير موثوق.", Toast.LENGTH_LONG).show();
            return;
        }
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.GONE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.GONE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        stopCurrentPlayer();
        resetImageTransform();

        playerView.setVisibility(View.GONE);
        if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.VISIBLE);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        currentImageUrl = imageUrl;
        setImageControlsVisible(false);
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
                configurePinnedCastMediaConnection(connection, imageUrl);
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
                Log.e(TAG, "Image loading error for " + redactSensitiveUrl(imageUrl), e);
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
                    if (playerSettingsButton != null) playerSettingsButton.bringToFront();
                    if (playerDownloadButton != null) playerDownloadButton.bringToFront();
                    if (playerSubtitleButton != null) playerSubtitleButton.bringToFront();
                    showImageControls();
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
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        imageControlsHandler.removeCallbacks(hideImageControlsRunnable);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.VISIBLE);
        connectionCard.setVisibility(View.VISIBLE);
        if (browserShortcut != null) browserShortcut.setVisibility(View.GONE);
        if (settingsHomeButton != null) settingsHomeButton.setVisibility(View.VISIBLE);
        if (browserHomeButton != null) browserHomeButton.setVisibility(View.VISIBLE);
        if (playerSettingsButton != null) playerSettingsButton.setVisibility(View.GONE);
        if (playerDownloadButton != null) playerDownloadButton.setVisibility(View.GONE);
        if (playerSubtitleButton != null) playerSubtitleButton.setVisibility(View.GONE);
        imageControlsHandler.removeCallbacks(hideImageControlsRunnable);
        imageControlsVisible = false;

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
        boolean restoreHistory = reopenHistoryAfterPlayback;
        showWelcome();
        if (restoreHistory) {
            reopenHistoryAfterPlayback = false;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) showPlaybackHistory();
            }, 120L);
        }
    }

    @Override
    protected void onDestroy() {
        backPressHandler.removeCallbacks(resetBrowserBackPress);
        playbackProgressHandler.removeCallbacks(playbackProgressRunnable);
        if (sleepTimerRunnable != null) sleepTimerHandler.removeCallbacks(sleepTimerRunnable);
        if (pendingStartupPlayback != null) startupBufferHandler.removeCallbacks(pendingStartupPlayback);
        synchronized (playerLock) {
            savePlaybackPositionLocked();
        }
        if (pendingPlayback != null) {
            mediaHandler.removeCallbacks(pendingPlayback);
            pendingPlayback = null;
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
        stopTikTokFfmpegPipeline();
        

        super.onDestroy();
    }
}
