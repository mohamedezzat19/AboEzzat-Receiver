
package com.aboezzat.receiver;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;

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

    private static final int PORT = 8081;

    private FrameLayout root;
    private LinearLayout welcomeScreen;
    private PlayerView playerView;
    private ExoPlayer player;

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        buildScreen();
        startReceiver();
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        welcomeScreen = new LinearLayout(this);
        welcomeScreen.setOrientation(LinearLayout.VERTICAL);
        welcomeScreen.setGravity(Gravity.CENTER);
        welcomeScreen.setPadding(24, 20, 24, 20);
        welcomeScreen.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("أهلاً بك يا أبو عزت");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        welcomeScreen.addView(title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView image = new ImageView(this);
        image.setImageResource(
                getResources().getIdentifier(
                        "app_icon", "drawable", getPackageName()));

        image.setScaleType(ImageView.ScaleType.FIT_CENTER);

        LinearLayout.LayoutParams imageParams =
                new LinearLayout.LayoutParams(
                        240, 240);
        imageParams.gravity = Gravity.CENTER;
        imageParams.topMargin = 18;
        imageParams.bottomMargin = 18;

        welcomeScreen.addView(image, imageParams);

        TextView prayer = new TextView(this);
        prayer.setText("سبحان الله وبحمده سبحان الله العظيم");
        prayer.setTextColor(Color.rgb(230, 200, 120));
        prayer.setTextSize(20);
        prayer.setGravity(Gravity.CENTER);
        prayer.setSingleLine(true);
        prayer.setSelected(true);

        welcomeScreen.addView(prayer,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView status = new TextView(this);
        status.setText("جهاز الاستقبال جاهز");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 16, 0, 0);

        welcomeScreen.addView(status);

        root.addView(welcomeScreen,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setVisibility(View.GONE);

        root.addView(playerView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
    }

    private void startReceiver() {
        running = true;

        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);

                while (running) {
                    Socket client = serverSocket.accept();

                    Thread requestThread = new Thread(() ->
                            handleRequest(client));
                    requestThread.start();
                }

            } catch (Exception e) {
                if (running) {
                    android.util.Log.e(
                            "AboEzzatReceiver",
                            "Receiver server error", e);
                }
            }
        });

        serverThread.start();
    }

    private void handleRequest(Socket client) {
        String videoUrl = null;
        String audioUrl = null;

        try (Socket socket = client) {
            socket.setSoTimeout(5000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            socket.getInputStream(),
                            StandardCharsets.UTF_8));

            String requestLine = reader.readLine();

            if (requestLine != null) {
                String[] parts = requestLine.split(" ");

                if (parts.length >= 2) {
                    String target = parts[1];
                    int question = target.indexOf('?');

                    if (question >= 0) {
                        String query = target.substring(question + 1);

                        for (String pair : query.split("&")) {
                            String[] item = pair.split("=", 2);

                            if (item.length == 2) {
                                String key = URLDecoder.decode(
                                        item[0], "UTF-8");
                                String value = URLDecoder.decode(
                                        item[1], "UTF-8");

                                if ("url".equals(key)) {
                                    videoUrl = value;
                                } else if ("video".equals(key)) {
                                    videoUrl = value;
                                } else if ("audio".equals(key)) {
                                    audioUrl = value;
                                }
                            }
                        }
                    }
                }
            }

            OutputStream output = socket.getOutputStream();
            byte[] response = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: 2\r\n\r\nOK"
            ).getBytes(StandardCharsets.UTF_8);

            output.write(response);
            output.flush();

        } catch (Exception e) {
            android.util.Log.e(
                    "AboEzzatReceiver",
                    "Request error", e);
        }

        final String finalVideo = videoUrl;
        final String finalAudio = audioUrl;

        if (finalVideo != null && !finalVideo.trim().isEmpty()) {
            runOnUiThread(() ->
                    playMedia(finalVideo, finalAudio));
        }
    }

    private void playMedia(String videoUrl, String audioUrl) {
        try {
            if (player == null) {
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);
            }

            if (audioUrl != null && !audioUrl.trim().isEmpty()) {
                DefaultMediaSourceFactory factory =
                        new DefaultMediaSourceFactory(this);

                MediaSource videoSource = factory.createMediaSource(
                        MediaItem.fromUri(Uri.parse(videoUrl)));

                MediaSource audioSource = factory.createMediaSource(
                        MediaItem.fromUri(Uri.parse(audioUrl)));

                MergingMediaSource mergedSource =
                        new MergingMediaSource(
                                true, videoSource, audioSource);

                player.setMediaSource(mergedSource);
            } else {
                player.setMediaItem(
                        MediaItem.fromUri(Uri.parse(videoUrl)));
            }

            welcomeScreen.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);

            player.prepare();
            player.play();

        } catch (Exception e) {
            android.util.Log.e(
                    "AboEzzatReceiver",
                    "Playback error", e);
        }
    }

    @Override
    protected void onDestroy() {
        running = false;

        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }

        if (player != null) {
            player.release();
            player = null;
        }

        super.onDestroy();
    }
}
