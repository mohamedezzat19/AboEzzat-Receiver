package com.aboezzat.receiver;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Converts a TikTok live FLV stream into a rolling local HLS playlist.
 * FFmpeg only remuxes the incoming audio/video; it does not re-encode media.
 */
final class TikTokFfmpegHlsPipeline {
    interface Listener {
        void onPlaylistReady(File playlist);
        void onFailure(String diagnostic);
        void onFinished(String diagnostic);
    }

    private static final long STARTUP_TIMEOUT_MS = 35000L;
    private static final int MAX_LOG_CHARS = 7000;

    private final Context context;
    private final String sourceUrl;
    private final Map<String, String> sourceHeaders;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object logLock = new Object();

    private volatile boolean stopped;
    private volatile boolean playlistReady;
    private volatile boolean failureSent;
    private volatile long sessionId = -1L;
    private volatile FFmpegSession session;
    private volatile File outputDirectory;
    private volatile File playlistFile;
    private volatile String state = "not started";
    private volatile String transport = "not selected";
    private volatile String returnCode = "pending";
    private volatile long startedAtMs;
    private volatile String logTail = "";
    private volatile TikTokVlcHttpBridge bridge;
    private Runnable playlistPoll;

    TikTokFfmpegHlsPipeline(Context context, String sourceUrl,
                            Map<String, String> headers, Listener listener) {
        this.context = context.getApplicationContext();
        this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
        this.sourceHeaders = headers == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.listener = listener;
    }

    void start() throws IOException {
        if (stopped) throw new IOException("Pipeline was already stopped");
        if (sourceUrl.isEmpty()) throw new IOException("TikTok source URL is empty");

        removeOldSessionDirectories();
        outputDirectory = new File(context.getCacheDir(),
                "tiktok-live-hls-" + System.currentTimeMillis() + "-"
                        + UUID.randomUUID().toString().substring(0, 8));
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory()) {
            throw new IOException("Could not create TikTok HLS cache directory");
        }
        playlistFile = new File(outputDirectory, "live.m3u8");
        File segmentPattern = new File(outputDirectory, "segment_%06d.ts");

        String inputUrl = sourceUrl;
        String cookie = headerValue("cookie");
        if (cookie != null && !cookie.trim().isEmpty()) {
            bridge = new TikTokVlcHttpBridge(sourceUrl, sourceHeaders);
            inputUrl = bridge.start();
            transport = "FFmpegKit via loopback bridge (session cookie present; value hidden)";
        } else {
            transport = "FFmpegKit direct HTTPS (no session cookie)";
        }

        ArrayList<String> args = new ArrayList<>();
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("info");
        args.add("-rw_timeout");
        args.add("20000000");
        args.add("-probesize");
        args.add("5000000");
        args.add("-analyzeduration");
        args.add("5000000");

        String userAgent = headerValue("user-agent");
        if (userAgent == null || userAgent.trim().isEmpty()) {
            userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
        }
        if (safeHeader(userAgent, 2048)) {
            args.add("-user_agent");
            args.add(userAgent);
        }

        String headers = buildHttpHeaders();
        if (!headers.isEmpty() && bridge == null) {
            args.add("-headers");
            args.add(headers);
        }

        args.add("-i");
        args.add(inputUrl);
        args.add("-map");
        args.add("0:v:0?");
        args.add("-map");
        args.add("0:a:0?");
        args.add("-sn");
        args.add("-dn");
        args.add("-c");
        args.add("copy");
        args.add("-f");
        args.add("hls");
        args.add("-hls_time");
        args.add("2");
        args.add("-hls_list_size");
        args.add("8");
        args.add("-hls_delete_threshold");
        args.add("8");
        args.add("-hls_flags");
        args.add("delete_segments+append_list+independent_segments");
        args.add("-hls_segment_filename");
        args.add(segmentPattern.getAbsolutePath());
        args.add(playlistFile.getAbsolutePath());

        startedAtMs = System.currentTimeMillis();
        state = "FFmpeg session starting";
        FFmpegSession created = FFmpegKit.executeWithArgumentsAsync(
                args.toArray(new String[0]),
                completed -> mainHandler.post(() -> handleCompleted(completed)),
                log -> {
                    if (log != null) appendLog(log.getMessage());
                },
                null
        );
        if (created == null) throw new IOException("FFmpegKit did not create a session");
        session = created;
        sessionId = created.getSessionId();
        state = "FFmpeg running; waiting for first complete HLS segment";
        playlistPoll = new Runnable() {
            @Override public void run() {
                checkPlaylistReady();
            }
        };
        mainHandler.post(playlistPoll);
    }

    private void checkPlaylistReady() {
        if (stopped || failureSent || playlistReady) return;
        if (isPlaylistReady()) {
            playlistReady = true;
            state = "HLS playlist and first segment ready";
            if (listener != null) listener.onPlaylistReady(playlistFile);
            return;
        }
        if (System.currentTimeMillis() - startedAtMs >= STARTUP_TIMEOUT_MS) {
            fail("FFmpeg did not produce a complete HLS segment before the startup deadline.");
            return;
        }
        mainHandler.postDelayed(playlistPoll, 250L);
    }

    private boolean isPlaylistReady() {
        File playlist = playlistFile;
        File directory = outputDirectory;
        if (playlist == null || directory == null || !playlist.isFile() || playlist.length() < 32L) {
            return false;
        }
        byte[] bytes = new byte[16384];
        int count;
        try (FileInputStream input = new FileInputStream(playlist);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            while ((count = input.read(bytes)) != -1 && output.size() < bytes.length) {
                output.write(bytes, 0, Math.min(count, bytes.length - output.size()));
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (!text.contains("#EXTM3U") || !text.contains("#EXTINF:")) return false;
            String[] lines = text.split("\\r?\\n");
            boolean afterExtInf = false;
            for (String line : lines) {
                String value = line.trim();
                if (value.startsWith("#EXTINF:")) {
                    afterExtInf = true;
                } else if (afterExtInf && !value.isEmpty() && !value.startsWith("#")) {
                    File segment = new File(directory, value);
                    return segment.isFile() && segment.length() >= 188L;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private void handleCompleted(FFmpegSession completed) {
        if (completed == null) return;
        returnCode = String.valueOf(completed.getReturnCode());
        boolean success = ReturnCode.isSuccess(completed.getReturnCode());
        if (stopped) return;

        if (!success) {
            fail("FFmpeg ended with a non-success return code.");
            return;
        }
        if (!playlistReady && !isPlaylistReady()) {
            fail("FFmpeg ended before producing a playable HLS segment.");
            return;
        }
        playlistReady = true;
        state = "FFmpeg input ended; final HLS playlist retained";
        closeBridge();
        if (listener != null) listener.onFinished(buildDiagnostic());
    }

    private void fail(String reason) {
        if (stopped || failureSent) return;
        failureSent = true;
        state = "failed: " + reason;
        long id = sessionId;
        if (id > 0L) {
            try { FFmpegKit.cancel(id); } catch (Exception ignored) { }
        }
        closeBridge();
        if (listener != null) listener.onFailure(buildDiagnostic());
    }

    void stop() {
        if (stopped) return;
        stopped = true;
        state = "stopped";
        if (playlistPoll != null) mainHandler.removeCallbacks(playlistPoll);
        long id = sessionId;
        if (id > 0L) {
            try { FFmpegKit.cancel(id); } catch (Exception ignored) { }
        }
        closeBridge();
    }

    String getDiagnostic() {
        StringBuilder out = new StringBuilder("TikTok FFmpeg/HLS diagnostic:");
        out.append("\n- State: ").append(state)
                .append("\n- Transport: ").append(transport)
                .append("\n- Source: ").append(describeSource())
                .append("\n- Session ID: ").append(sessionId)
                .append("\n- Return code: ").append(returnCode)
                .append("\n- Startup elapsed ms: ")
                .append(startedAtMs <= 0L ? "unknown"
                        : Math.max(0L, System.currentTimeMillis() - startedAtMs))
                .append("\n- Playlist ready: ").append(playlistReady)
                .append("\n- Playlist bytes: ")
                .append(playlistFile == null || !playlistFile.isFile() ? 0L : playlistFile.length())
                .append("\n- HLS segments: ").append(countSegments())
                .append("\n- Input headers: UA=").append(hasHeader("user-agent"))
                .append(", Referer=").append(hasHeader("referer"))
                .append(", Origin=").append(hasHeader("origin"))
                .append(", Cookie=").append(hasHeader("cookie"))
                .append("\n- FFmpeg log tail (URLs redacted):");
        synchronized (logLock) {
            out.append("\n").append(logTail.isEmpty() ? "no FFmpeg log messages captured" : logTail);
        }
        return out.toString();
    }

    private String describeSource() {
        try {
            Uri uri = Uri.parse(sourceUrl);
            return "scheme=" + uri.getScheme() + ", host=" + uri.getHost()
                    + ", path=" + uri.getLastPathSegment() + " (query omitted)";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    private int countSegments() {
        File directory = outputDirectory;
        if (directory == null) return 0;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".ts"));
        return files == null ? 0 : files.length;
    }

    private String buildHttpHeaders() {
        StringBuilder out = new StringBuilder();
        appendHeader(out, "Referer", headerValue("referer"));
        appendHeader(out, "Origin", headerValue("origin"));
        appendHeader(out, "Accept", headerValue("accept"));
        appendHeader(out, "Accept-Language", headerValue("accept-language"));
        appendHeader(out, "Sec-Fetch-Site", headerValue("sec-fetch-site"));
        appendHeader(out, "Sec-Fetch-Mode", headerValue("sec-fetch-mode"));
        appendHeader(out, "Sec-Fetch-Dest", headerValue("sec-fetch-dest"));
        appendHeader(out, "Sec-CH-UA", headerValue("sec-ch-ua"));
        appendHeader(out, "Sec-CH-UA-Mobile", headerValue("sec-ch-ua-mobile"));
        appendHeader(out, "Sec-CH-UA-Platform", headerValue("sec-ch-ua-platform"));
        appendHeader(out, "Priority", headerValue("priority"));
        return out.toString();
    }

    private static void appendHeader(StringBuilder out, String name, String value) {
        if (safeHeader(value, 2048)) out.append(name).append(": ").append(value).append("\r\n");
    }

    private String headerValue(String name) {
        for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
            if (entry.getKey() != null && name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean hasHeader(String name) {
        String value = headerValue(name);
        return value != null && !value.trim().isEmpty();
    }

    private static boolean safeHeader(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private void appendLog(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String safe = message.replaceAll("https?://\\S+", "[URL]");
        safe = safe.replaceAll("(?i)(set-cookie|cookie)\\s*[:=]\\s*[^\\r\\n]+",
                "$1: [REDACTED]");
        if (safe.length() > 500) safe = safe.substring(0, 500);
        synchronized (logLock) {
            String next = logTail.isEmpty() ? safe : logTail + "\n" + safe;
            if (next.length() > MAX_LOG_CHARS) {
                next = next.substring(next.length() - MAX_LOG_CHARS);
            }
            logTail = next;
        }
    }

    private void closeBridge() {
        TikTokVlcHttpBridge current = bridge;
        bridge = null;
        if (current != null) {
            try { current.close(); } catch (Exception ignored) { }
        }
    }

    private void removeOldSessionDirectories() {
        File cache = context.getCacheDir();
        File[] old = cache.listFiles((dir, name) -> name.startsWith("tiktok-live-hls-"));
        if (old == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File item : old) {
            if (item.lastModified() < cutoff) deleteRecursively(item);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        try { file.delete(); } catch (Exception ignored) { }
    }
}
