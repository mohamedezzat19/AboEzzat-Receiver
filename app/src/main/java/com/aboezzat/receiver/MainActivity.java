
package com.aboezzat.receiver;

import android.app.Activity;
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
import android.view.Window;
import android.view.WindowManager;
import android.util.TypedValue;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ProgressBar;

import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
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

    private FrameLayout root;
    private LinearLayout welcomeScreen;

    private ImageView welcomeBackground;
    private LinearLayout connectionCard;
    private TextView ipText;
    private TextView receiverStatusText;

    private PlayerView playerView;
    private ImageView imageView;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(3, 12, 32));
        window.setNavigationBarColor(Color.rgb(3, 12, 32));
        window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        buildScreen();
        startReceiver();
        handleExternalIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    // Support web links shared directly to Receiver in addition to links sent over port 8081.
    private void handleExternalIntent(android.content.Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
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
            String sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT);
            if (sharedText != null) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("(?i)https?://[^\\\\s<>\\\\\"']+")
                        .matcher(sharedText);
                if (matcher.find()) {
                    url = matcher.group().replaceAll("[),.]+$", "");
                }
            }
        }

        if (url != null && !url.trim().isEmpty()) {
            queueIncomingMedia(url, null);
        }
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
                "PORT: " + PORT,
                10,
                Color.rgb(255, 207, 94),
                true
        );

        portText.setTextIsSelectable(true);

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

        // مشغل الفيديو والصوت.
        playerView = new PlayerView(this);
        playerView.setUseController(true);
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

        setContentView(root);
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
                    "IP الحالي: " + ip + ":" + PORT
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

                if (parts.length >= 2) {
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

            byte[] response = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 2\r\n\r\nOK"
            ).getBytes(StandardCharsets.UTF_8);

            OutputStream output = socket.getOutputStream();
            output.write(response);
            output.flush();

        } catch (Exception e) {
            Log.e(TAG, "Request error", e);
        }

        if (command != null && !command.trim().isEmpty()) {
            final String requestedCommand = command;
            runOnUiThread(() -> handleRemoteCommand(requestedCommand));
            return;
        }

        if ((mediaUrl != null && !mediaUrl.trim().isEmpty())
                || (audioUrl != null && !audioUrl.trim().isEmpty())) {
            final String receivedMediaUrl = mediaUrl;
            final String receivedAudioUrl = audioUrl;
            runOnUiThread(() -> queueIncomingMedia(
                    receivedMediaUrl, receivedAudioUrl
            ));
        }
    }

    // ننتظر لحظة قصيرة ليلتقط التطبيق الرابط الثاني إذا أُرسل فورًا بعد الأول.
    private void queueIncomingMedia(String mediaUrl, String audioUrl) {
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()) {
            pendingVisualUrl = mediaUrl;
        }
        if (audioUrl != null && !audioUrl.trim().isEmpty()) {
            pendingAudioUrl = audioUrl;
        }

        if (pendingPlayback != null) {
            mediaHandler.removeCallbacks(pendingPlayback);
        }

        pendingPlayback = () -> {
            String visual = pendingVisualUrl;
            String audio = pendingAudioUrl;
            pendingVisualUrl = null;
            pendingAudioUrl = null;
            pendingPlayback = null;
            playContent(visual, audio);
        };
        mediaHandler.postDelayed(pendingPlayback, 700);
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

            return path.endsWith(".jpg")
                    || path.endsWith(".jpeg")
                    || path.endsWith(".png")
                    || path.endsWith(".webp")
                    || path.endsWith(".gif")
                    || path.endsWith(".bmp");

        } catch (Exception e) {
            return false;
        }
    }

    private void handleRemoteCommand(String command) {
        synchronized (playerLock) {
            if (player == null) {
                // Keep the physical volume controls useful even when no media player is active.
                if ("volume_up".equals(command) || "volume_down".equals(command) || "mute".equals(command)) {
                    android.media.AudioManager audioManager =
                            (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    if (audioManager != null) {
                        if ("volume_up".equals(command)) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_RAISE, 0);
                        } else if ("volume_down".equals(command)) {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_LOWER, 0);
                        } else {
                            audioManager.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                                    android.media.AudioManager.ADJUST_TOGGLE_MUTE, 0);
                        }
                    }
                    return;
                }
                Log.w(TAG, "Remote command ignored; no active media: " + command);
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
                    player.stop();
                    player.clearMediaItems();
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
                player.stop();
                player.clearMediaItems();
            }
        }
    }

    private void playContent(
            String mediaUrl,
            String audioUrl) {

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

        hideImage();

        welcomeBackground.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);

        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    playerView.setPlayer(player);
                }

                player.stop();
                player.clearMediaItems();

                if (audioUrl != null
                        && !audioUrl.trim().isEmpty()) {

                    DefaultMediaSourceFactory factory =
                            new DefaultMediaSourceFactory(this);

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
                    player.setMediaItem(
                            MediaItem.fromUri(playbackUrl)
                    );
                }

                player.prepare();
                player.play();
            }

        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
            showWelcome();
        }
    }

    // الصورة تظل ظاهرة بينما يشغّل ExoPlayer الصوت في الخلفية.
    private void playAudioAlongsideImage(String audioUrl) {
        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    player.setVolume(muted ? 0.0f : lastNonZeroVolume);
                    playerView.setPlayer(player);
                }
                player.stop();
                player.clearMediaItems();
                player.setMediaItem(MediaItem.fromUri(audioUrl));
                player.prepare();
                player.play();
                playerView.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e(TAG, "Audio with image playback error", e);
            showWelcome();
        }
    }

    private void showImage(String imageUrl) {
        stopCurrentPlayer();

        playerView.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.GONE);
        connectionCard.setVisibility(View.GONE);

        imageView.setVisibility(View.VISIBLE);
        imageLoading.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);

        Thread imageThread = new Thread(() -> {
            Bitmap bitmap = null;

            try {
                URL url = new URL(imageUrl);

                java.net.URLConnection connection =
                        url.openConnection();

                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);

                try (java.io.InputStream input =
                             connection.getInputStream()) {

                    bitmap = BitmapFactory.decodeStream(input);
                }

            } catch (Exception e) {
                Log.e(TAG, "Image loading error", e);
            }

            final Bitmap result = bitmap;

            runOnUiThread(() -> {
                imageLoading.setVisibility(View.GONE);

                if (result != null && !isFinishing()) {
                    imageView.setImageBitmap(result);
                    imageView.setVisibility(View.VISIBLE);

                } else {
                    showWelcome();
                }
            });
        }, "ReceiverImageLoader");

        imageThread.start();
    }

    private void hideImage() {
        imageView.setVisibility(View.GONE);
        imageLoading.setVisibility(View.GONE);
        imageView.setImageDrawable(null);
    }

    private void showWelcome() {
        hideImage();

        playerView.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        welcomeBackground.setVisibility(View.VISIBLE);
        connectionCard.setVisibility(View.VISIBLE);

        updateConnectionInfo();
    }

    @Override
    public void onBackPressed() {
        showWelcome();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
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

        synchronized (playerLock) {
            if (player != null) {
                player.release();
                player = null;
            }
        }

        super.onDestroy();
    }
}
