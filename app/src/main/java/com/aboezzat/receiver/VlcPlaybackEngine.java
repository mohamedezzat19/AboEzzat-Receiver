package com.aboezzat.receiver;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.VLCVideoLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Receiver-owned playback engine wrapper around LibVLC.
 *
 * This class deliberately contains no media library, network browser, casting,
 * or VLC application UI. MainActivity owns routing and the user-facing controls.
 * All public methods are expected to be called on the main thread.
 */
@androidx.annotation.Keep
public final class VlcPlaybackEngine {
    private static final String TAG = "AboEzzatVlcEngine";
    private static final int DEFAULT_NETWORK_CACHING_MS = 1500;

    public interface Listener {
        void onPlaying();
        void onPaused();
        void onEnded();
        void onError();
        void onTimeChanged(long positionMs);
    }

    private final LibVLC libVlc;
    private final MediaPlayer mediaPlayer;
    private final VLCVideoLayout videoLayout;
    private Listener listener;
    private boolean released;
    private int preferredVideoHeight;

    public VlcPlaybackEngine(Context context, VLCVideoLayout layout, Listener listener) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        if (layout == null) throw new IllegalArgumentException("VLCVideoLayout is required");
        this.videoLayout = layout;
        this.listener = listener;

        ArrayList<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--network-caching=" + DEFAULT_NETWORK_CACHING_MS);
        options.add("--no-video-title-show");
        libVlc = new LibVLC(context.getApplicationContext(), options);
        mediaPlayer = new MediaPlayer(libVlc);
        mediaPlayer.setEventListener(event -> {
            Listener callback = this.listener;
            if (callback == null || released) return;
            final int type = event.type;
            if (type == MediaPlayer.Event.Playing) callback.onPlaying();
            else if (type == MediaPlayer.Event.Paused) callback.onPaused();
            else if (type == MediaPlayer.Event.EndReached) callback.onEnded();
            else if (type == MediaPlayer.Event.EncounteredError) callback.onError();
            else if (type == MediaPlayer.Event.TimeChanged) callback.onTimeChanged(event.getTimeChanged());
        });
        mediaPlayer.attachViews(videoLayout, null, true, true);
    }

    /**
     * LibVLC 3 exposes User-Agent and Referer as explicit HTTP options.
     * Other request headers are logged by the caller because LibVLC's HTTP
     * access module does not provide arbitrary per-request header injection.
     */
    public static boolean supportsHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return true;
        for (String key : headers.keySet()) {
            if (key == null) continue;
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (!normalized.equals("user-agent")
                    && !normalized.equals("referer")
                    && !normalized.equals("referrer")) return false;
        }
        return true;
    }

    public void setPreferredVideoHeight(int height) {
        ensureActive();
        preferredVideoHeight = Math.max(0, height);
    }

    public void play(String url, Map<String, String> requestHeaders) {
        play(url, requestHeaders, null);
    }

    /**
     * Play the primary media and, when supplied, attach a separate audio stream
     * as a LibVLC audio slave before playback begins.
     */
    public void play(String url, Map<String, String> requestHeaders, String separateAudioUrl) {
        playInternal(url, requestHeaders, separateAudioUrl, true);
    }

    /**
     * Second-chance decode path for devices whose hardware decoder rejects a stream.
     * This remains LibVLC playback; it only switches VLC's decoder to software.
     */
    public void playSoftwareFallback(String url, Map<String, String> requestHeaders,
                                     String separateAudioUrl) {
        Log.w(TAG, "Retrying failed stream with LibVLC software decoding");
        playInternal(url, requestHeaders, separateAudioUrl, false);
    }

    private void playInternal(String url, Map<String, String> requestHeaders,
                              String separateAudioUrl, boolean hardwareDecoder) {
        ensureActive();
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("Media URL is empty");
        }

        mediaPlayer.stop();
        Media media = new Media(libVlc, Uri.parse(url.trim()));
        try {
            media.setHWDecoderEnabled(hardwareDecoder, false);
            media.addOption(":network-caching=" + DEFAULT_NETWORK_CACHING_MS);
            media.addOption(":clock-jitter=0");
            String lower = Uri.decode(url).toLowerCase(Locale.ROOT);
            if (lower.contains("/index.html") || lower.contains(".m3u8")
                    || lower.contains("format=m3u8") || lower.contains("chunklist")) {
                media.addOption(":demux=adaptive");
            }
            if (preferredVideoHeight > 0) {
                media.addOption(":preferred-resolution=" + preferredVideoHeight);
            }
            if (requestHeaders != null) {
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) continue;
                    String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
                    String value = entry.getValue().trim();
                    if (value.isEmpty()) continue;
                    if ("user-agent".equals(key)) media.addOption(":http-user-agent=" + value);
                    else if ("referer".equals(key) || "referrer".equals(key)) {
                        media.addOption(":http-referrer=" + value);
                    } else {
                        Log.w(TAG, "LibVLC 3 cannot apply arbitrary HTTP header: " + key);
                    }
                }
            }
            mediaPlayer.setMedia(media);
            if (separateAudioUrl != null && !separateAudioUrl.trim().isEmpty()) {
                boolean attached = mediaPlayer.addSlave(IMedia.Slave.Type.Audio,
                        Uri.parse(separateAudioUrl.trim()), true);
                if (!attached) {
                    Log.w(TAG, "LibVLC could not attach separate audio stream");
                }
            }
        } finally {
            media.release();
        }
        Log.i(TAG, "Starting LibVLC playback; hardwareDecoder=" + hardwareDecoder
                + ", separateAudio=" + (separateAudioUrl != null && !separateAudioUrl.trim().isEmpty()));
        mediaPlayer.play();
    }

    public void play() { ensureActive(); mediaPlayer.play(); }
    public void pause() { ensureActive(); mediaPlayer.pause(); }
    public void togglePlayback() {
        ensureActive();
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        else mediaPlayer.play();
    }
    public void stop() {
        if (!released) mediaPlayer.stop();
    }
    public boolean isPlaying() { return !released && mediaPlayer.isPlaying(); }
    public boolean isSeekable() { return !released && mediaPlayer.isSeekable(); }
    public long getPositionMs() { return released ? 0L : Math.max(0L, mediaPlayer.getTime()); }
    public long getDurationMs() { return released ? 0L : Math.max(0L, mediaPlayer.getLength()); }
    public void seekTo(long positionMs) {
        ensureActive();
        mediaPlayer.setTime(Math.max(0L, positionMs));
    }
    public void seekBy(long deltaMs) {
        ensureActive();
        long current = Math.max(0L, mediaPlayer.getTime());
        long duration = mediaPlayer.getLength();
        long target = Math.max(0L, current + deltaMs);
        if (duration > 0L) target = Math.min(target, duration);
        mediaPlayer.setTime(target);
    }
    public void setVolume(float volume) {
        ensureActive();
        int value = Math.max(0, Math.min(100, Math.round(volume * 100f)));
        mediaPlayer.setVolume(value);
    }
    public void setRate(float rate) {
        ensureActive();
        mediaPlayer.setRate(Math.max(0.25f, Math.min(4.0f, rate)));
    }
    public float getRate() { return released ? 1.0f : mediaPlayer.getRate(); }

    public MediaPlayer.TrackDescription[] getAudioTracks() {
        ensureActive();
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getAudioTracks();
        return tracks == null ? new MediaPlayer.TrackDescription[0] : tracks;
    }
    public int getAudioTrack() { ensureActive(); return mediaPlayer.getAudioTrack(); }
    public boolean setAudioTrack(int id) { ensureActive(); return mediaPlayer.setAudioTrack(id); }

    public MediaPlayer.TrackDescription[] getSubtitleTracks() {
        ensureActive();
        MediaPlayer.TrackDescription[] tracks = mediaPlayer.getSpuTracks();
        return tracks == null ? new MediaPlayer.TrackDescription[0] : tracks;
    }
    public int getSubtitleTrack() { ensureActive(); return mediaPlayer.getSpuTrack(); }
    public boolean setSubtitleTrack(int id) { ensureActive(); return mediaPlayer.setSpuTrack(id); }
    public boolean addExternalSubtitle(Uri subtitleUri, boolean select) {
        ensureActive();
        return mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle, subtitleUri, select);
    }
    public boolean addExternalSubtitle(String localPath) {
        ensureActive();
        return localPath != null && !localPath.trim().isEmpty()
                && mediaPlayer.addSlave(IMedia.Slave.Type.Subtitle,
                Uri.fromFile(new java.io.File(localPath)), true);
    }

    // LibVLC delay values are microseconds; Receiver settings can present milliseconds.
    public long getAudioDelayMs() { ensureActive(); return mediaPlayer.getAudioDelay() / 1000L; }
    public boolean setAudioDelayMs(long delayMs) {
        ensureActive();
        return mediaPlayer.setAudioDelay(delayMs * 1000L);
    }
    public long getSubtitleDelayMs() { ensureActive(); return mediaPlayer.getSpuDelay() / 1000L; }
    public boolean setSubtitleDelayMs(long delayMs) {
        ensureActive();
        return mediaPlayer.setSpuDelay(delayMs * 1000L);
    }

    public void setAspectRatio(String aspectRatio) {
        ensureActive();
        mediaPlayer.setAspectRatio(aspectRatio);
    }
    public void setScale(float scale) {
        ensureActive();
        mediaPlayer.setScale(Math.max(0f, scale));
    }

    public void setListener(Listener listener) { this.listener = listener; }
    public VLCVideoLayout getVideoLayout() { return videoLayout; }

    private void ensureActive() {
        if (released) throw new IllegalStateException("VLC engine has been released");
    }

    public void release() {
        if (released) return;
        released = true;
        listener = null;
        try {
            mediaPlayer.stop();
            mediaPlayer.detachViews();
        } catch (Exception e) {
            Log.w(TAG, "Error detaching VLC output", e);
        }
        mediaPlayer.setEventListener(null);
        mediaPlayer.release();
        libVlc.release();
    }
}
