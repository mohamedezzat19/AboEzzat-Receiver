package com.aboezzat.receiver;

import android.net.Uri;
import android.util.Log;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Extracts real, short-lived YouTube media URLs on the sender phone.
 *
 * Audio selection deliberately prefers an available Arabic dub/track, then the
 * video's original audio, and only then other available tracks. Video selection
 * prefers the highest resolution up to 1080p to avoid needlessly sending a
 * 1440p/4K rendition that commonly causes buffering or decoder overload on TVs.
 */
public final class YoutubeDirectExtractor {
    public interface Callback {
        void onSuccess(String videoUrl, String audioUrl, String title, String quality);
        void onError(String reason);
    }

    private static final String TAG = "ReceiverYoutubeExtractor";
    private static final String STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final int MAX_STABLE_HEIGHT = 1080;

    /** Must match the fallback UA used by the extractor for googlevideo playback. */
    public static String getStreamUserAgent() {
        return streamRequestUserAgent;
    }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS).build();
    private static volatile boolean initialized;
    private static volatile String streamRequestUserAgent = STREAM_USER_AGENT;

    private YoutubeDirectExtractor() {}

    private static synchronized void initialize() {
        if (initialized) return;
        NewPipe.init(new Downloader() {
            @Override
            public org.schabi.newpipe.extractor.downloader.Response execute(Request request)
                    throws IOException, ReCaptchaException {
                okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());
                boolean hasUserAgent = false;
                for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                    if (header.getKey() == null || header.getValue() == null) continue;
                    if ("user-agent".equalsIgnoreCase(header.getKey())) {
                        hasUserAgent = true;
                        if (!header.getValue().isEmpty() && header.getValue().get(0) != null
                                && !header.getValue().get(0).trim().isEmpty()) {
                            streamRequestUserAgent = header.getValue().get(0).trim();
                        }
                    }
                    for (String value : header.getValue()) builder.addHeader(header.getKey(), value);
                }
                if (!hasUserAgent) {
                    streamRequestUserAgent = STREAM_USER_AGENT;
                    builder.header("User-Agent", STREAM_USER_AGENT);
                }
                String method = request.httpMethod();
                if ("POST".equalsIgnoreCase(method)) {
                    byte[] data = request.dataToSend();
                    builder.post(RequestBody.create(data == null ? new byte[0] : data,
                            MediaType.parse("application/octet-stream")));
                } else if ("HEAD".equalsIgnoreCase(method)) {
                    builder.head();
                } else {
                    builder.get();
                }
                try (okhttp3.Response response = HTTP.newCall(builder.build()).execute()) {
                    String body = response.body() == null ? "" : response.body().string();
                    return new org.schabi.newpipe.extractor.downloader.Response(
                            response.code(), response.message(), response.headers().toMultimap(),
                            body, response.request().url().toString());
                }
            }
        });
        initialized = true;
    }

    public static void extract(String pageUrl, Callback callback) {
        if (callback == null) return;
        WORKER.execute(() -> {
            try {
                if (!isYouTubeUrl(pageUrl)) throw new IOException("رابط YouTube غير صالح");
                initialize();
                StreamInfo info = StreamInfo.getInfo(pageUrl);

                List<VideoStream> videoOnly = new ArrayList<>(info.getVideoOnlyStreams());
                videoOnly.removeIf(stream -> stream == null || !stream.isUrl()
                        || stream.getContent() == null || !isHttp(stream.getContent()));

                List<AudioStream> audioStreams = new ArrayList<>(info.getAudioStreams());
                audioStreams.removeIf(stream -> stream == null || !stream.isUrl()
                        || stream.getContent() == null || !isHttp(stream.getContent()));

                String videoUrl = null;
                String audioUrl = null;
                String quality = "غير محددة";

                if (!videoOnly.isEmpty() && !audioStreams.isEmpty()) {
                    VideoStream video = chooseStableVideo(videoOnly);
                    AudioStream audio = choosePreferredAudio(audioStreams);
                    videoUrl = video.getContent();
                    audioUrl = audio.getContent();
                    quality = video.getResolution() + " (صوت عربي مفضل + فيديو منفصل)";
                    Log.i(TAG, "Selected YouTube audio: language="
                            + audioLanguage(audio) + ", trackType=" + audio.getAudioTrackType()
                            + ", bitrate=" + audio.getAverageBitrate());
                } else {
                    // Progressive renditions already contain both video and audio.
                    // Use the best stable progressive rendition if separate tracks
                    // are not both available.
                    List<VideoStream> combined = new ArrayList<>(info.getVideoStreams());
                    combined.removeIf(stream -> stream == null || !stream.isUrl()
                            || stream.getContent() == null || !isHttp(stream.getContent()));
                    if (!combined.isEmpty()) {
                        VideoStream selected = chooseStableVideo(combined);
                        videoUrl = selected.getContent();
                        quality = selected.getResolution() + " (صوت وصورة مدمجان)";
                    } else if (info.getHlsUrl() != null && isHttp(info.getHlsUrl())) {
                        videoUrl = info.getHlsUrl();
                        quality = "HLS تلقائي";
                    }
                }

                if (videoUrl == null) throw new IOException(
                        "لم يتوفر رابط فيديو مباشر قابل للتشغيل لهذا الفيديو");
                final String resultVideo = videoUrl;
                final String resultAudio = audioUrl;
                final String title = info.getName();
                final String selectedQuality = quality;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onSuccess(resultVideo, resultAudio, title, selectedQuality));
            } catch (Throwable error) {
                Log.e(TAG, "Receiver-side YouTube extraction failed", error);
                String message = error.getMessage();
                final String reason = message == null || message.trim().isEmpty()
                        ? "تعذر استخراج رابط مباشر" : message;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onError(reason));
            }
        });
    }

    /**
     * Prefer the highest available rendition at or below 1080p. If YouTube only
     * exposes resolutions above that ceiling, choose the lowest above-ceiling
     * rendition rather than jumping straight to 4K.
     */
    private static VideoStream chooseStableVideo(List<VideoStream> streams) {
        List<VideoStream> atOrBelowCeiling = new ArrayList<>();
        int lowestAboveCeiling = Integer.MAX_VALUE;
        for (VideoStream stream : streams) {
            int height = videoHeight(stream);
            if (height > 0 && height <= MAX_STABLE_HEIGHT) {
                atOrBelowCeiling.add(stream);
            } else if (height > MAX_STABLE_HEIGHT && height < lowestAboveCeiling) {
                lowestAboveCeiling = height;
            }
        }

        List<VideoStream> candidates = atOrBelowCeiling;
        if (candidates.isEmpty() && lowestAboveCeiling != Integer.MAX_VALUE) {
            candidates = new ArrayList<>();
            for (VideoStream stream : streams) {
                if (videoHeight(stream) == lowestAboveCeiling) candidates.add(stream);
            }
        }
        if (candidates.isEmpty()) candidates = streams;

        candidates.sort(Comparator
                .comparingInt(YoutubeDirectExtractor::videoHeight).reversed()
                .thenComparing(Comparator.comparingInt(VideoStream::getBitrate).reversed())
                .thenComparing(Comparator.comparingInt(VideoStream::getFps).reversed()));
        return candidates.get(0);
    }

    /**
     * User preference: Arabic audio first, then the original-language track.
     * Bitrate is used only to choose the best rendition within the same priority.
     */
    private static AudioStream choosePreferredAudio(List<AudioStream> streams) {
        List<AudioStream> candidates = new ArrayList<>(streams);
        candidates.sort(Comparator
                .comparingInt(YoutubeDirectExtractor::audioPriority)
                .thenComparing(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed()));
        return candidates.get(0);
    }

    private static int audioPriority(AudioStream stream) {
        if (isArabicAudio(stream)) return 0;
        if (stream.getAudioTrackType() == AudioTrackType.ORIGINAL) return 1;
        return 2;
    }

    private static boolean isArabicAudio(AudioStream stream) {
        if (stream == null) return false;
        Locale locale = stream.getAudioLocale();
        if (locale != null && "ar".equalsIgnoreCase(locale.getLanguage())) return true;

        String name = stream.getAudioTrackName();
        if (name != null) {
            String normalized = name.toLowerCase(Locale.ROOT);
            if (normalized.contains("arab") || normalized.contains("العربية")
                    || normalized.contains("عربي")) return true;
        }

        String id = stream.getAudioTrackId();
        if (id != null && id.toLowerCase(Locale.ROOT)
                .matches(".*(?:^|[._-])ar(?:[._-]|$).*")) return true;
        return false;
    }

    private static String audioLanguage(AudioStream stream) {
        if (stream.getAudioLocale() != null) return stream.getAudioLocale().toLanguageTag();
        if (stream.getAudioTrackName() != null) return stream.getAudioTrackName();
        if (stream.getAudioTrackId() != null) return stream.getAudioTrackId();
        return "unknown";
    }

    private static int videoHeight(VideoStream stream) {
        if (stream == null) return 0;
        int height = stream.getHeight();
        if (height > 0) return height;
        return resolutionNumber(stream);
    }

    private static int resolutionNumber(VideoStream stream) {
        String value = stream.getResolution();
        if (value == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(value);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static boolean isYouTubeUrl(String value) {
        try {
            String host = Uri.parse(value).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            return host.equals("youtu.be") || host.endsWith(".youtu.be")
                    || host.equals("youtube.com") || host.endsWith(".youtube.com")
                    || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
        } catch (Exception ignored) { return false; }
    }

    private static boolean isHttp(String value) {
        try {
            Uri uri = Uri.parse(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) { return false; }
    }
}
