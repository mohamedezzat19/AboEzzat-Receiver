
package com.aboezzat.receiver;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

public class MainActivity extends AppCompatActivity {

    private LinearLayout root;
    private EditText urlInput;
    private PlayerView playerView;
    private ImageView photoView;
    private TextView statusText;
    private ExoPlayer player;

    private final int background = Color.rgb(16, 25, 43);
    private final int panel = Color.rgb(31, 45, 68);
    private final int accent = Color.rgb(255, 205, 92);
    private final int white = Color.WHITE;
    private final int muted = Color.rgb(205, 216, 232);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        handleIncomingIntent(getIntent());
    }

    private int dp(float value) {
        return (int) (value * getResources()
                .getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private TextView makeText(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create(
                "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(28));

        scroll.addView(root);
        setContentView(scroll);

        TextView title = makeText(
                "أهلاً بك يا أبو عزت", 28, accent);
        title.setTypeface(Typeface.DEFAULT_BOLD);

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(-1, -2);
        titleParams.bottomMargin = dp(18);
        root.addView(title, titleParams);

        photoView = new ImageView(this);

        int photoId = getResources().getIdentifier(
                "app_icon", "drawable", getPackageName());

        if (photoId != 0) {
            photoView.setImageResource(photoId);
        }

        // عرض الصورة كاملة دون قص أطرافها
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoView.setAdjustViewBounds(true);
        photoView.setBackgroundColor(background);
        photoView.setContentDescription("الصورة الرئيسية");

        LinearLayout.LayoutParams photoParams =
                new LinearLayout.LayoutParams(
                        -1, -2);
        photoParams.gravity = Gravity.CENTER;
        photoParams.bottomMargin = dp(18);
        root.addView(photoView, photoParams);

        TextView prayer = makeText(
                "سبحان الله وبحمده سبحان الله العظيم",
                19, white);
        prayer.setTypeface(Typeface.DEFAULT_BOLD);
        prayer.setSingleLine(true);
        prayer.setTextSize(19);

        LinearLayout.LayoutParams prayerParams =
                new LinearLayout.LayoutParams(-1, -2);
        prayerParams.bottomMargin = dp(22);
        root.addView(prayer, prayerParams);

        LinearLayout statusPanel = new LinearLayout(this);
        statusPanel.setOrientation(LinearLayout.VERTICAL);
        statusPanel.setGravity(Gravity.CENTER);
        statusPanel.setPadding(dp(14), dp(14), dp(14), dp(14));
        statusPanel.setBackground(rounded(panel, 16));

        statusText = makeText(
                "التطبيق جاهز لاستقبال الروابط",
                16, accent);
        statusPanel.addView(statusText,
                new LinearLayout.LayoutParams(-1, -2));

        TextView instruction = makeText(
                "أرسل الرابط من هاتفك واختر AboEzzat Receiver",
                14, muted);
        instruction.setPadding(0, dp(8), 0, 0);

        statusPanel.addView(instruction,
                new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(-1, -2);
        statusParams.bottomMargin = dp(16);
        root.addView(statusPanel, statusParams);

        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("الصق رابط الفيديو أو البث هنا");
        urlInput.setHintTextColor(muted);
        urlInput.setTextColor(white);
        urlInput.setTextSize(16);
        urlInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        urlInput.setBackground(rounded(panel, 12));

        root.addView(urlInput,
                new LinearLayout.LayoutParams(-1, -2));

        Button playButton = new Button(this);
        playButton.setText("تشغيل الرابط");
        playButton.setTextColor(Color.BLACK);
        playButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(accent));

        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(8);
        root.addView(playButton, buttonParams);

        playerView = new PlayerView(this);
        playerView.setUseController(true);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setVisibility(View.GONE);

        LinearLayout.LayoutParams playerParams =
                new LinearLayout.LayoutParams(-1, dp(250));
        playerParams.topMargin = dp(18);
        root.addView(playerView, playerParams);

        playButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            hideKeyboard();
            playMedia(url);
        });
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

            String text = intent.getStringExtra(
                    Intent.EXTRA_TEXT);

            if (text == null || text.trim().isEmpty()) {
                Uri sharedUri = intent.getParcelableExtra(
                        Intent.EXTRA_STREAM);
                if (sharedUri != null) {
                    text = sharedUri.toString();
                }
            }

            if (text != null) {
                String url = extractUrl(text.trim());

                if (url != null) {
                    urlInput.setText(url);
                    playMedia(url);
                } else {
                    statusText.setText(
                            "المحتوى المرسل لا يحتوي على رابط واضح");
                }
            }
            return;
        }

        if (Intent.ACTION_VIEW.equals(action)) {
            Uri uri = intent.getData();

            if (uri != null) {
                String scheme = uri.getScheme();

                if ("content".equalsIgnoreCase(scheme)
                        || "file".equalsIgnoreCase(scheme)) {
                    showImage(uri);
                } else {
                    String url = uri.toString();
                    urlInput.setText(url);
                    playMedia(url);
                }
            }
        }
    }

    private String extractUrl(String text) {
        int start = text.indexOf("https://");
        if (start < 0) start = text.indexOf("http://");
        if (start < 0) return null;

        String url = text.substring(start).trim();
        int space = url.indexOf(' ');

        if (space >= 0) {
            url = url.substring(0, space);
        }

        while (url.endsWith(".") || url.endsWith(",")
                || url.endsWith(")") || url.endsWith("،")) {
            url = url.substring(0, url.length() - 1);
        }

        return url.isEmpty() ? null : url;
    }

    private void playMedia(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "أرسل رابطاً أو الصقه أولاً",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = Uri.parse(url.trim());
        String scheme = uri.getScheme();

        if (!"http".equalsIgnoreCase(scheme)
                && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(this,
                    "الرابط يجب أن يبدأ بـ http أو https",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (player == null) {
                player = new ExoPlayer.Builder(this).build();
                playerView.setPlayer(player);

                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(
                            PlaybackException error) {
                        runOnUiThread(() -> {
                            statusText.setText(
                                    "تعذر تشغيل الرابط مباشرة");
                            Toast.makeText(
                                    MainActivity.this,
                                    "قد يحتاج الرابط إلى دعم خاص بالموقع",
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onPlaybackStateChanged(int state) {
                        if (state == Player.STATE_READY) {
                            statusText.setText("الفيديو جاهز للتشغيل");
                        }
                    }
                });
            }

            photoView.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            statusText.setText("جارٍ فتح الرابط...");

            player.setMediaItem(MediaItem.fromUri(uri));
            player.prepare();
            player.play();

        } catch (Exception e) {
            statusText.setText("تعذر فتح المحتوى");
            Toast.makeText(this, "تعذر فتح الرابط",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void showImage(Uri uri) {
        try {
            photoView.setImageURI(uri);
            photoView.setVisibility(View.VISIBLE);
            playerView.setVisibility(View.GONE);
            statusText.setText("تم استلام الصورة");
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
        if (player != null) player.pause();
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
