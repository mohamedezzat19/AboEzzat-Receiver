
package com.aboezzat.receiver;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
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
import androidx.media3.common.Player;
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
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@UnstableApi
public class MainActivity extends Activity {

    private static final String TAG = "AboEzzatReceiver";
    private static final int PORT = 8081;

    private FrameLayout root;
    private LinearLayout welcomeScreen;
    private PlayerView playerView;
    private ImageView imageView;
    private ProgressBar imageLoading;
    private ExoPlayer player;

    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;

    private final Object playerLock = new Object();

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildScreen();
        startReceiver();
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
            int color, int strokeColor, int radius) {

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        bg.setStroke(dp(1), strokeColor);
        return bg;
    }

    private TextView makeText(
            String text, int size, int color, boolean bold) {

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create(
                "sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL
        ));
        view.setIncludeFontPadding(true);
        return view;
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackground(gradientBackground());

        welcomeScreen = new LinearLayout(this);
        welcomeScreen.setOrientation(LinearLayout.VERTICAL);
        welcomeScreen.setGravity(Gravity.CENTER);
        welcomeScreen.setPadding(dp(24), dp(14), dp(24), dp(14));

        root.addView(welcomeScreen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);

        TextView brand = makeText(
                "ABO EZZAT  •  RECEIVER",
                13, Color.rgb(255, 207, 94), true
        );
        brand.setLetterSpacing(0.16f);
        header.addView(brand);

        TextView heading = makeText(
                "أهلاً بك يا أبو عزت",
                30, Color.WHITE, true
        );
        heading.setPadding(0, dp(7), 0, dp(2));
        header.addView(heading);

        TextView subtitle = makeText(
                "مشاهدة ممتعة • تجربة استقبال مميزة",
                13, Color.rgb(151, 183, 220), false
        );
        header.addView(subtitle);

        welcomeScreen.addView(header,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0, 1f
                ));

        // Portrait
        FrameLayout portraitFrame = new FrameLayout(this);
        portraitFrame.setBackground(roundedBackground(
                Color.rgb(10, 28, 53),
                Color.rgb(255, 204, 89), 120
        ));
        portraitFrame.setElevation(dp(8));
        portraitFrame.setClipToOutline(true);

        ImageView portrait = new ImageView(this);
        portrait.setImageResource(R.drawable.app_icon);
        portrait.setScaleType(ImageView.ScaleType.FIT_CENTER);
        portrait.setBackgroundColor(Color.rgb(10, 28, 53));

        FrameLayout.LayoutParams portraitParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );
        portraitParams.setMargins(dp(5), dp(5), dp(5), dp(5));
        portraitFrame.addView(portrait, portraitParams);

        LinearLayout.LayoutParams frameParams =
                new LinearLayout.LayoutParams(dp(230), dp(230));
        frameParams.gravity = Gravity.CENTER;
        frameParams.topMargin = dp(5);
        frameParams.bottomMargin = dp(10);
        welcomeScreen.addView(portraitFrame, frameParams);

        // Gold divider
        View divider = new View(this);
        GradientDrawable line = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{
                        Color.TRANSPARENT,
                        Color.rgb(255, 197, 74),
                        Color.rgb(255, 232, 160),
                        Color.rgb(255, 197, 74),
                        Color.TRANSPARENT
                }
        );
        line.setCornerRadius(dp(3));
        divider.setBackground(line);

        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(dp(210), dp(2));
        dividerParams.gravity = Gravity.CENTER;
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(8);
        welcomeScreen.addView(divider, dividerParams);

        // Prayer
        TextView prayer = makeText(
                "سبحان الله وبحمده سبحان الله العظيم",
                18, Color.rgb(255, 222, 151), true
        );
        prayer.setMaxLines(2);
        prayer.setPadding(dp(4), dp(4), dp(4), dp(4));
        welcomeScreen.addView(prayer);

        // Status card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusCard.setBackground(roundedBackground(
                Color.rgb(8, 26, 50),
                Color.rgb(40, 91, 135), 22
        ));

        TextView signal = makeText(
                "●", 26, Color.rgb(49, 239, 164), true
        );
        LinearLayout.LayoutParams signalParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        signalParams.setMarginEnd(dp(10));
        statusCard.addView(signal, signalParams);

        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        statusTexts.setGravity(Gravity.CENTER_VERTICAL);

        TextView statusTitle = makeText(
                "الرسيفر جاهز لاستقبال المحتوى",
                16, Color.WHITE, true
        );
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);

        TextView statusSubtitle = makeText(
                "فيديو • صوت • صور",
                13, Color.rgb(150, 185, 222), false
        );
        statusSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        statusSubtitle.setPadding(0, dp(4), 0, 0);

        statusTexts.addView(statusTitle);
        statusTexts.addView(statusSubtitle);

        statusCard.addView(statusTexts,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1
                ));

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
        cardParams.topMargin = dp(16);
        cardParams.bottomMargin = dp(8);
        welcomeScreen.addView(statusCard, cardParams);

        TextView footer = makeText(
                "ABO EZZAT RECEIVER  •  PORT 8081",
                10, Color.rgb(113, 146, 185), false
        );
        footer.setLetterSpacing(0.10f);
        welcomeScreen.addView(footer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(24)
                ));

        // Video and audio player
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setVisibility(View.GONE);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(playerView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Image viewer
        imageView = new ImageView(this);
        imageView.setVisibility(View.GONE);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        root.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        // Image loading indicator
        imageLoading = new ProgressBar(this);
        imageLoading.setVisibility(View.GONE);

        FrameLayout.LayoutParams loadingParams =
                new FrameLayout.LayoutParams(
                        dp(56), dp(56), Gravity.CENTER
                );
        root.addView(imageLoading, loadingParams);

        setContentView(root);
    }

    private void startReceiver() {
        running = true;

        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "Receiver listening on port " + PORT);

                while (running) {
                    Socket client = serverSocket.accept();

                    Thread requestThread =
                            new Thread(() -> handleRequest(client));
                    requestThread.start();
                }

            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Server error", e);
                }
            }
        }, "ReceiverServer");

        serverThread.start();
    }

    private void handleRequest(Socket client) {
        String mediaUrl = null;
        String audioUrl = null;

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
                        String query = target.substring(questionIndex + 1);

                        for (String pair : query.split("&")) {
                            String[] item = pair.split("=", 2);

                            if (item.length != 2) {
                                continue;
                            }

                            String key = URLDecoder.decode(
                                    item[0], "UTF-8");
                            String value = URLDecoder.decode(
                                    item[1], "UTF-8");

                            if ("url".equals(key)
                                    || "video".equals(key)) {
                                mediaUrl = value;
                            } else if ("audio".equals(key)) {
                                audioUrl = value;
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

        final String finalMediaUrl = mediaUrl;
        final String finalAudioUrl = audioUrl;

        if ((finalMediaUrl != null && !finalMediaUrl.trim().isEmpty())
                || (finalAudioUrl != null && !finalAudioUrl.trim().isEmpty())) {

            runOnUiThread(() ->
                    playContent(finalMediaUrl, finalAudioUrl));
        }
    }

    private boolean isImageUrl(String url) {
        if (url == null) return false;

        try {
            String path = Uri.parse(url).getPath();

            if (path == null) return false;

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

    private void stopCurrentPlayer() {
        synchronized (playerLock) {
            if (player != null) {
                player.stop();
                player.clearMediaItems();
            }
        }
    }

    private void playContent(String mediaUrl, String audioUrl) {
        if (mediaUrl != null && !mediaUrl.trim().isEmpty()
                && isImageUrl(mediaUrl)) {
            showImage(mediaUrl);
            return;
        }

        String playbackUrl = mediaUrl;

        // Audio-only request
        if (playbackUrl == null || playbackUrl.trim().isEmpty()) {
            playbackUrl = audioUrl;
            audioUrl = null;
        }

        if (playbackUrl == null || playbackUrl.trim().isEmpty()) {
            return;
        }

        hideImage();
        welcomeScreen.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);

        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
                    playerView.setPlayer(player);
                }

                player.stop();
                player.clearMediaItems();

                if (audioUrl != null && !audioUrl.trim().isEmpty()) {
                    DefaultMediaSourceFactory factory =
                            new DefaultMediaSourceFactory(this);

                    MediaSource videoSource =
                            factory.createMediaSource(
                                    MediaItem.fromUri(playbackUrl));

                    MediaSource audioSource =
                            factory.createMediaSource(
                                    MediaItem.fromUri(audioUrl));

                    MergingMediaSource merged =
                            new MergingMediaSource(
                                    true, videoSource, audioSource
                            );

                    player.setMediaSource(merged);
                } else {
                    player.setMediaItem(MediaItem.fromUri(playbackUrl));
                }

                player.prepare();
                player.play();
            }
        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
            showWelcome();
        }
    }

    private void showImage(String imageUrl) {
        stopCurrentPlayer();

        playerView.setVisibility(View.GONE);
        welcomeScreen.setVisibility(View.GONE);
        imageView.setVisibility(View.VISIBLE);
        imageLoading.setVisibility(View.VISIBLE);
        imageView.setImageDrawable(null);

        Thread imageThread = new Thread(() -> {
            Bitmap bitmap = null;

            try {
                URL url = new URL(imageUrl);
                java.net.URLConnection connection = url.openConnection();
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
        welcomeScreen.setVisibility(View.VISIBLE);
    }

    @Override
    public void onBackPressed() {
        showWelcome();
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        running = false;

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
