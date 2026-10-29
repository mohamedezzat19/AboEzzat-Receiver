package com.aboezzat.receiver;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.Proxy;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fallback extractor for public social pages; does not bypass login or DRM. */
public final class FastMediaExtractor {
    public interface ExtractorCallback {
        void onSuccess(String videoUrl, String platform);
        void onError(String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(7, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build();
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PROP = Pattern.compile("(?:property|name)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT = Pattern.compile("content\\s*=\\s*['\\\"]([^'\\\"]*)['\\\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_VIDEO = Pattern.compile("['\\\"](browser_native_hd_url|browser_native_sd_url|playable_url_quality_hd|playable_url|playAddr|play_addr|downloadAddr)['\\\"]\\s*:\\s*['\\\"]((?:\\\\.|[^'\\\"])*)['\\\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern MP4 = Pattern.compile("https?:\\\\?/\\\\?/[^\\\"'\\s<>]+?\\.mp4(?:[^\\\"'\\s<>]*)?", Pattern.CASE_INSENSITIVE);

    private FastMediaExtractor() {}

    public static void extractDirectMedia(String inputUrl, ExtractorCallback callback) {
        if (callback == null) return;
        final String url = inputUrl == null ? "" : inputUrl.trim();
        final String platform = platformFor(url);
        if (platform == null || !isHttp(url)) {
            error(callback, "الرابط غير صالح أو المنصة غير مدعومة بالاستخراج السريع");
            return;
        }
        String ua = "TikTok".equals(platform) ? MOBILE_UA : DESKTOP_UA;
        Request request;
        try {
            request = new Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .get().build();
        } catch (IllegalArgumentException ex) {
            error(callback, "الرابط غير صالح");
            return;
        }
        CLIENT.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                error(callback, "فشل الاتصال: " + (e.getMessage() == null ? "خطأ شبكة" : e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response closeable = response) {
                    if (!closeable.isSuccessful() || closeable.body() == null) {
                        error(callback, "الرابط غير متاح أو قد يتطلب تسجيل الدخول");
                        return;
                    }
                    String html = closeable.body().string();
                    String media = findMeta(html);
                    if (media == null) media = findJson(html, platform);
                    if (media == null) media = findMp4(html);
                    media = clean(media);
                    if (!isHttp(media)) {
                        error(callback, "لم يتم العثور على رابط فيديو مباشر صالح");
                        return;
                    }
                    final String result = media;
                    MAIN.post(() -> callback.onSuccess(result, platform));
                } catch (Exception e) {
                    error(callback, "تعذر تحليل بيانات الفيديو");
                }
            }
        });
    }

    private static String platformFor(String value) {
        try {
            String host = Uri.parse(value).getHost();
            if (host == null) return null;
            host = host.toLowerCase(Locale.ROOT);
            if (host.equals("fb.watch") || host.endsWith(".fb.watch") || host.equals("facebook.com") || host.endsWith(".facebook.com")) return "Facebook";
            if (host.equals("threads.net") || host.endsWith(".threads.net") || host.equals("threads.com") || host.endsWith(".threads.com")) return "Threads";
            if (host.equals("instagram.com") || host.endsWith(".instagram.com")) return "Instagram";
            if (host.equals("tiktok.com") || host.endsWith(".tiktok.com")) return "TikTok";
        } catch (Exception ignored) {}
        return null;
    }

    private static String findMeta(String html) {
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            Matcher p = PROP.matcher(tag), c = CONTENT.matcher(tag);
            if (!p.find() || !c.find()) continue;
            String key = p.group(1).toLowerCase(Locale.ROOT);
            if (key.equals("og:video") || key.equals("og:video:url") || key.equals("og:video:secure_url") || key.equals("twitter:player:stream")) {
                String value = clean(c.group(1));
                if (isHttp(value)) return value;
            }
        }
        return null;
    }

    private static String findJson(String html, String platform) {
        Matcher m = JSON_VIDEO.matcher(html);
        while (m.find()) {
            String key = m.group(1);
            if ("TikTok".equals(platform) && !(key.equalsIgnoreCase("playAddr") || key.equalsIgnoreCase("play_addr") || key.equalsIgnoreCase("downloadAddr"))) continue;
            String value = clean(m.group(2));
            if (isHttp(value) && (value.toLowerCase(Locale.ROOT).contains(".mp4") || key.toLowerCase(Locale.ROOT).contains("playable") || key.toLowerCase(Locale.ROOT).contains("playaddr") || key.equalsIgnoreCase("downloadAddr"))) return value;
        }
        return null;
    }

    private static String findMp4(String html) {
        Matcher m = MP4.matcher(html);
        return m.find() ? clean(m.group()) : null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        return value.trim().replace("\\\\/", "/").replace("\\\\u0026", "&")
                .replace("\\\\u003D", "=").replace("&amp;", "&");
    }

    private static boolean isHttp(String value) {
        if (value == null || value.isEmpty()) return false;
        try {
            Uri u = Uri.parse(value);
            String scheme = u.getScheme();
            return u.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception ignored) { return false; }
    }

    private static void error(ExtractorCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }
}
