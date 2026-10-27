
package com.aboezzat.receiver;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {

    private LinearLayout root;
    private EditText urlInput;
    private PlayerView playerView;
    private ImageView imageView;
    private ExoPlayer player;

    private final int background = Color.rgb(11, 16, 23);
    private final int surface = Color.rgb(21, 30, 41);
    private final int primary = Color.rgb(0, 214, 178);
    private final int white = Color.WHITE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        handleIncomingIntent(getIntent());
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(20, 24, 20, 24);
        root.setBackgroundColor(background);

        scroll.addView(root);
        setContentView(scroll);

        ImageView appIcon = new ImageView(this);
        int iconId = getResources().getIdentifier(
                "app_icon", "drawable", getPackageName());

        if (iconId != 0) {
            appIcon.setImageResource(iconId);
        }

        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(120, 120);
        iconParams.gravity = Gravity.CENTER;
        iconParams.bottomMargin = 12;
        root.addView(appIcon, iconParams);

        TextView title = new TextView(this);
        title.setText("AboEzzat Receiver");
        title.setTextSize(26);
        title.setTextColor(primary);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 8, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("استقبل وشغّل الفيديوهات والصور");
        subtitle.setTextSize(16);
        subtitle.setTextColor(white);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 20);
        root.addView(subtitle);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("ضع رابط الفيديو أو البث هنا");
        urlInput.setHintTextColor(Color.LTGRAY);
        urlInput.setTextColor(white);
        urlInput.setTextSize(16);
        urlInput.setPadding(14, 12, 14, 12);
        urlInput.setBackgroundColor(surface);

        root.addView(urlInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        Button playButton = new Button(this);
        playButton.setText("تشغيل الرابط");
        playButton.setTextColor(Color.BLACK);
        playButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(primary));

        root.addView(playButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        playButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();

            if (url.isEmpty()) {
                Toast.makeText(this, "اكتب الرابط الأول",
                        Toast.LENGTH_SHORT).show();
                return;
            }

            hideKeyboard();
            playMedia(url);
        });

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setBackgroundColor(Color.BLACK);

        root.addView(playerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 280));

        imageView = new ImageView(this);
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setVisibility(View.GONE);

        root.addView(imageView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 280));
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action)) {
            if (type != null && type.startsWith("image/")) {
                Uri imageUri = intent.getParcelableExtra(
                        Intent.EXTRA_STREAM);

                if (imageUri != null) {
                    showImage(imageUri);
                    return;
                }
            }

            String sharedText =
                    intent.getStringExtra(Intent.EXTRA_TEXT);

            if (sharedText != null && !sharedText.trim().isEmpty()) {
                urlInput.setText(sharedText.trim());
            }
        }

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();

            if (uri != null) {
                String scheme = uri.getScheme();

                if ("content".equals(scheme)
                        || "file".equals(scheme)) {
                    showImage(uri);
                } else {
                    urlInput.setText(uri.toString());
                }
            }
        }
    }

    private void playMedia(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(this,
                    "الرابط لازم يبدأ بـ http أو https",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (player == null) {
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);
            }

            imageView.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);

            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();

        } catch (Exception e) {
            Toast.makeText(this, "تعذر تشغيل الرابط",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showImage(Uri uri) {
        try {
            imageView.setImageURI(uri);
            imageView.setVisibility(View.VISIBLE);
            playerView.setVisibility(View.GONE);
        } catch (Exception e) {
            Toast.makeText(this, "تعذر فتح الصورة",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);

        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    urlInput.getWindowToken(), 0);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (player != null) {
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (player != null) {
            player.release();
            player = null;
        }
    }
}
