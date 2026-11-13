package com.aboezzat.receiver;

import android.net.Uri;
import android.util.Log;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

/**
 * Dedicated YouTube page-to-stream resolver.
 * It only returns public, non-DRM streams exposed by NewPipe Extractor.
 */
public final class YoutubeDirectExtractor {
    public interface Callback {
        void onSuccess(String streamUrl, String title, String quality);
        void onError(String reason);
    }

    private static final String TAG = "YoutubeDirectExtractor";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build();
    private static volatile boolean initialized;

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
                    if ("user-agent".equalsIgnoreCase(header.getKey())) hasUserAgent = true;
                    for (String value : header.getValue()) builder.addHeader(header.getKey(), value);
                }
                if (!hasUserAgent) {
                    builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0");
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
                if (pageUrl == null || !isYouTubeUrl(pageUrl)) {
                    throw new IOException("رابط YouTube غير صالح");
                }
                initialize();
                StreamInfo info = StreamInfo.getInfo(pageUrl);
                List<VideoStream> streams = new ArrayList<>(info.getVideoStreams());
                streams.removeIf(stream -> stream == null || !stream.isUrl()
                        || stream.getContent() == null || !isHttp(stream.getContent()));
                streams.sort(Comparator.comparingInt(YoutubeDirectExtractor::resolutionNumber).reversed());

                String selectedUrl = null;
                String quality = "غير محددة";
                if (!streams.isEmpty()) {
                    VideoStream selected = streams.get(0);
                    selectedUrl = selected.getContent();
                    quality = selected.getResolution();
                } else if (info.getHlsUrl() != null && isHttp(info.getHlsUrl())) {
                    selectedUrl = info.getHlsUrl();
                    quality = "HLS تلقائي";
                }
                if (selectedUrl == null) {
                    throw new IOException("يوتيوب لم يوفر مسار فيديو مباشر متوافقًا لهذا المقطع");
                }
                final String result = selectedUrl;
                final String title = info.getName();
                final String selectedQuality = quality;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onSuccess(result, title, selectedQuality));
            } catch (Exception error) {
                Log.w(TAG, "YouTube extraction failed", error);
                String message = error.getMessage();
                final String reason = message == null || message.trim().isEmpty()
                        ? "تعذر استخراج مسار YouTube مباشر" : message;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(
                        () -> callback.onError(reason));
            }
        });
    }

    private static int resolutionNumber(VideoStream stream) {
        String resolution = stream.getResolution();
        if (resolution == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(resolution);
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
