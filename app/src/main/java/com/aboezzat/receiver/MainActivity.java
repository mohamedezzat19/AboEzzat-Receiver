
package com.aboezzat.receiver;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.TypedValue;
import android.util.Log;

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
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@UnstableApi
public class MainActivity extends Activity {

    private static final String TAG = "AboEzzatReceiver";
    private static final int PORT = 8081;

    private FrameLayout root;
    private LinearLayout welcomeScreen;
    private PlayerView playerView;
    private ExoPlayer player;

    private volatile boolean running = false;
    private ServerSocket serverSocket;

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
        root.setBackground(gradientBackground());

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(24), dp(14), dp(24), dp(14));

        FrameLayout.LayoutParams contentParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                );

        root.addView(content, contentParams);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);

        TextView brand = makeText(
                "ABO EZZAT  •  RECEIVER",
                13,
                Color.rgb(255, 207, 94),
                true
        );
        brand.setLetterSpacing(0.16f);

        header.addView(
                brand,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView heading = makeText(
                "أهلاً بك يا أبو عزت",
                30,
                Color.WHITE,
                true
        );
        heading.setPadding(0, dp(7), 0, dp(2));

        header.addView(
                heading,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView subtitle = makeText(
                "مشاهدة ممتعة • تجربة استقبال مميزة",
                13,
                Color.rgb(151, 183, 220),
                false
        );

        header.addView(
                subtitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        content.addView(
                header,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1.0f
                )
        );

        // Portrait frame
        FrameLayout portraitFrame = new FrameLayout(this);
        portraitFrame.setBackground(
                roundedBackground(
                        Color.rgb(10, 28, 53),
                        Color.rgb(255, 204, 89),
                        120
                )
        );
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
                new LinearLayout.LayoutParams(
                        dp(230),
                        dp(230)
                );
        frameParams.gravity = Gravity.CENTER;
        frameParams.topMargin = dp(5);
        frameParams.bottomMargin = dp(10);

        content.addView(portraitFrame, frameParams);

        // Decorative gold divider
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
        divider.setCornerRadius(dp(3));
        divider.setIntrinsicHeight(dp(2));
        divider.setIntrinsicWidth(dp(200));
        divider.setBounds(0, 0, dp(200), dp(2));
        divider.setColor(Color.rgb(255, 197, 74));
        divider.setBackground(line);

        LinearLayout.LayoutParams dividerParams =
                new LinearLayout.LayoutParams(
                        dp(210), dp(2)
                );
        dividerParams.gravity = Gravity.CENTER;
        dividerParams.topMargin = dp(8);
        dividerParams.bottomMargin = dp(8);

        content.addView(divider, dividerParams);

        // Prayer
        TextView prayer = makeText(
                "سبحان الله وبحمده سبحان الله العظيم",
                18,
                Color.rgb(255, 222, 151),
                true
        );
        prayer.setSingleLine(false);
        prayer.setMaxLines(2);
        prayer.setPadding(dp(4), dp(4), dp(4), dp(4));

        content.addView(
                prayer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        // Receiver status card
        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusCard.setBackground(
                roundedBackground(
                        Color.rgb(8, 26, 50),
                        Color.rgb(40, 91, 135),
                        22
                )
        );

        TextView signal = makeText(
                "●",
                26,
                Color.rgb(49, 239, 164),
                true
        );

        LinearLayout.LayoutParams signalParams =
                new LinearLayout.LayoutParams(dp(42), dp(42));
        signalParams.setMarginEnd(dp(10));
        statusCard.addView(signal, signalParams);

        LinearLayout statusTexts = new LinearLayout(this);
        statusTexts.setOrientation(LinearLayout.VERTICAL);
        statusTexts.setGravity(Gravity.CENTER_VERTICAL);

        TextView statusTitle = makeText(
                "الرسيفر جاهز لاستقبال الفيديو",
                16,
                Color.WHITE,
                true
        );
        statusTitle.setGravity(Gravity.CENTER_VERTICAL);

        TextView statusSubtitle = makeText(
                "في انتظار الرابط من جهازك",
                13,
                Color.rgb(150, 185, 222),
                false
        );
        statusSubtitle.setGravity(Gravity.CENTER_VERTICAL);
        statusSubtitle.setPadding(0, dp(4), 0, 0);

        statusTexts.addView(statusTitle);
        statusTexts.addView(statusSubtitle);

        statusCard.addView(
                statusTexts,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1
                )
        );

        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
        cardParams.topMargin = dp(16);
        cardParams.bottomMargin = dp(8);

        content.addView(statusCard, cardParams);

        TextView footer = makeText(
                "ABO EZZAT RECEIVER  •  PORT 8081",
                10,
                Color.rgb(113, 146, 185),
                false
        );
        footer.setLetterSpacing(0.10f);

        content.addView(
                footer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(24)
                )
        );

        // Video player stays above the welcome screen.
        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setVisibility(View.GONE);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(
                playerView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

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
                        String query =
                                target.substring(questionIndex + 1);

                        for (String pair : query.split("&")) {
                            String[] item = pair.split("=", 2);

                            if (item.length != 2) {
                                continue;
                            }

                            String key = URLDecoder.decode(
                                    item[0], "UTF-8");

                            String value = URLDecoder.decode(
                                    item[1], "UTF-8");

                            if ("url".equals(key)) {
                                mediaUrl = value;
                            } else if ("video".equals(key)) {
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

        if (finalMediaUrl != null
                && !finalMediaUrl.trim().isEmpty()) {
            runOnUiThread(() ->
                    playMedia(finalMediaUrl, finalAudioUrl));
        }
    }

    private void playMedia(String videoUrl, String audioUrl) {
        try {
            synchronized (playerLock) {
                if (player == null) {
                    player = new ExoPlayer.Builder(this).build();
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
                                    MediaItem.fromUri(videoUrl));

                    MediaSource audioSource =
                            factory.createMediaSource(
                                    MediaItem.fromUri(audioUrl));

                    MergingMediaSource merged =
                            new MergingMediaSource(
                                    true,
                                    videoSource,
                                    audioSource
                            );

                    player.setMediaSource(merged);

                } else {
                    player.setMediaItem(
                            MediaItem.fromUri(videoUrl));
                }

                welcomeScreen.setVisibility(View.GONE);
                playerView.setVisibility(View.VISIBLE);

                player.prepare();
                player.play();
            }

        } catch (Exception e) {
            Log.e(TAG, "Playback error", e);
        }
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
