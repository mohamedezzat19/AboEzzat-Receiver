package com.aboezzat.receiver;

import android.net.Uri;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loopback-only HTTP bridge for LibVLC. It forwards the original request headers
 * and Range requests, and rewrites HLS child URIs so segments and nested playlists
 * continue to pass through the same authenticated bridge.
 */
public final class LocalMediaProxyServer implements AutoCloseable {
    private static final String TAG = "ReceiverMediaProxy";
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final Pattern URI_ATTRIBUTE =
            Pattern.compile("(?i)(URI\\s*=\\s*)\"([^\"]+)\"");
    private final Map<String, String> sourceHeaders;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService clients = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "ReceiverMediaProxy-Client");
        thread.setDaemon(true);
        return thread;
    });
    private ServerSocket server;
    private Thread acceptThread;

    public LocalMediaProxyServer(Map<String, String> headers) {
        sourceHeaders = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null
                        && !entry.getValue().trim().isEmpty()) {
                    sourceHeaders.put(entry.getKey().trim(), entry.getValue().trim());
                }
            }
        }
    }

    public synchronized void start() throws Exception {
        if (running.get()) return;
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        running.set(true);
        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    socket.setSoTimeout(20000);
                    activeSockets.add(socket);
                    try {
                        clients.execute(() -> handle(socket));
                    } catch (RuntimeException rejected) {
                        activeSockets.remove(socket);
                        try { socket.close(); } catch (Exception ignored) { }
                        if (running.get()) Log.w(TAG, "Proxy client rejected", rejected);
                    }
                } catch (Exception e) {
                    if (running.get()) Log.w(TAG, "Proxy accept failed", e);
                }
            }
        }, "ReceiverMediaProxy-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        Log.i(TAG, "Loopback media proxy started on port " + server.getLocalPort());
    }

    public String proxyUrl(String remoteUrl) {
        if (!isHttp(remoteUrl) || server == null || !running.get()) {
            throw new IllegalStateException("Media proxy is not running or URL is invalid");
        }
        return "http://127.0.0.1:" + server.getLocalPort()
                + "/media?url=" + Uri.encode(remoteUrl);
    }

    private void handle(Socket socket) {
        HttpURLConnection upstream = null;
        try (Socket client = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     new BufferedInputStream(client.getInputStream()), StandardCharsets.ISO_8859_1));
             OutputStream output = client.getOutputStream()) {
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2 || !("GET".equalsIgnoreCase(parts[0])
                    || "HEAD".equalsIgnoreCase(parts[0]))) {
                writeError(output, 405, "Method Not Allowed");
                return;
            }
            Map<String, String> requestHeaders = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) requestHeaders.put(line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim());
            }
            Uri local = Uri.parse("http://127.0.0.1" + parts[1]);
            String remote = local.getQueryParameter("url");
            if (!isHttp(remote)) {
                writeError(output, 400, "Bad Request");
                return;
            }

            upstream = (HttpURLConnection) new URL(remote).openConnection();
            upstream.setInstanceFollowRedirects(true);
            upstream.setConnectTimeout(15000);
            upstream.setReadTimeout(30000);
            upstream.setRequestMethod(parts[0].toUpperCase(Locale.ROOT));
            upstream.setRequestProperty("Accept-Encoding", "identity");

            for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
                if (isHopByHop(entry.getKey()) || "range".equalsIgnoreCase(entry.getKey())
                        || "host".equalsIgnoreCase(entry.getKey())) continue;
                upstream.setRequestProperty(entry.getKey(), entry.getValue());
            }
            String range = findHeader(requestHeaders, "Range");
            if (range != null) upstream.setRequestProperty("Range", range);
            String ifRange = findHeader(requestHeaders, "If-Range");
            if (ifRange != null) upstream.setRequestProperty("If-Range", ifRange);

            int status = upstream.getResponseCode();
            String type = upstream.getContentType();
            String finalUrl = upstream.getURL().toString();
            boolean hls = isHls(finalUrl, type);
            InputStream body = status >= 400 ? upstream.getErrorStream() : upstream.getInputStream();
            byte[] manifest = null;
            if (hls && body != null && !"HEAD".equalsIgnoreCase(parts[0])) {
                manifest = readBounded(body, MAX_MANIFEST_BYTES);
                String rewritten = rewriteManifest(new String(manifest, StandardCharsets.UTF_8), finalUrl);
                manifest = rewritten.getBytes(StandardCharsets.UTF_8);
            }

            String reason = reasonPhrase(status);
            StringBuilder response = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
                    .append(reason).append("\r\n");
            if (type != null) response.append("Content-Type: ").append(type).append("\r\n");
            response.append("Connection: close\r\n");
            if (hls && manifest != null) {
                response.append("Content-Length: ").append(manifest.length).append("\r\n");
                response.append("Cache-Control: no-store\r\n");
            } else {
                long length = upstream.getContentLengthLong();
                if (length >= 0) response.append("Content-Length: ").append(length).append("\r\n");
                String acceptRanges = upstream.getHeaderField("Accept-Ranges");
                if (acceptRanges != null) response.append("Accept-Ranges: ").append(acceptRanges).append("\r\n");
                String contentRange = upstream.getHeaderField("Content-Range");
                if (contentRange != null) response.append("Content-Range: ").append(contentRange).append("\r\n");
                String lastModified = upstream.getHeaderField("Last-Modified");
                if (lastModified != null) response.append("Last-Modified: ").append(lastModified).append("\r\n");
                String etag = upstream.getHeaderField("ETag");
                if (etag != null) response.append("ETag: ").append(etag).append("\r\n");
            }
            response.append("\r\n");
            output.write(response.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!"HEAD".equalsIgnoreCase(parts[0])) {
                if (manifest != null) {
                    output.write(manifest);
                } else if (body != null) {
                    byte[] buffer = new byte[64 * 1024];
                    int count;
                    long total = 0;
                    while ((count = body.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                        total += count;
                    }
                    Log.d(TAG, "Stream completed: status=" + status + ", bytes=" + total
                            + ", type=" + type);
                }
            }
            output.flush();
            if (body != null) body.close();
        } catch (Exception e) {
            Log.w(TAG, "Proxy request failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            try { writeError(socket.getOutputStream(), 502, "Bad Gateway"); }
            catch (Exception ignored) { }
        } finally {
            activeSockets.remove(socket);
            if (upstream != null) upstream.disconnect();
        }
    }

    private String rewriteManifest(String manifest, String baseUrl) throws Exception {
        URI base = new URI(baseUrl);
        StringBuilder out = new StringBuilder(manifest.length() + 256);
        String[] lines = manifest.split("\\r?\\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                Matcher matcher = URI_ATTRIBUTE.matcher(line);
                StringBuffer replaced = new StringBuffer();
                while (matcher.find()) {
                    String absolute = resolve(base, matcher.group(2));
                    matcher.appendReplacement(replaced, Matcher.quoteReplacement(
                            matcher.group(1) + "\"" + proxyUrl(absolute) + "\""));
                }
                matcher.appendTail(replaced);
                out.append(replaced);
            } else if (!trimmed.isEmpty()) {
                out.append(proxyUrl(resolve(base, trimmed)));
            } else {
                out.append(line);
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String resolve(URI base, String value) throws Exception {
        URI resolved = base.resolve(new URI(value.trim()));
        String scheme = resolved.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Unsupported HLS child URI scheme");
        }
        return resolved.toString();
    }

    private static boolean isHls(String url, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        return type.contains("mpegurl") || type.contains("vnd.apple")
                || lower.matches(".*\\.m3u8(?:[?#].*)?$")
                || lower.contains("format=m3u8") || lower.contains("chunklist");
    }

    private static boolean isHttp(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        try {
            URI uri = new URI(value.trim());
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception e) { return false; }
    }

    private static boolean isHopByHop(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("connection") || k.equals("keep-alive") || k.equals("proxy-authenticate")
                || k.equals("proxy-authorization") || k.equals("te") || k.equals("trailer")
                || k.equals("transfer-encoding") || k.equals("upgrade");
    }

    private static String findHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet())
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return null;
    }

    private static byte[] readBounded(InputStream input, int limit) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > limit) throw new IllegalStateException("HLS manifest too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String reasonPhrase(int status) {
        if (status == 200) return "OK";
        if (status == 206) return "Partial Content";
        if (status == 301) return "Moved Permanently";
        if (status == 302) return "Found";
        if (status == 304) return "Not Modified";
        if (status == 400) return "Bad Request";
        if (status == 403) return "Forbidden";
        if (status == 404) return "Not Found";
        if (status == 416) return "Range Not Satisfiable";
        if (status >= 500) return "Bad Gateway";
        return "OK";
    }

    private static void writeError(OutputStream output, int status, String reason) throws Exception {
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    @Override public synchronized void close() {
        if (!running.getAndSet(false)) return;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        for (Socket socket : activeSockets) {
            try { socket.close(); } catch (Exception ignored) { }
        }
        activeSockets.clear();
        clients.shutdownNow();
        Log.i(TAG, "Loopback media proxy stopped");
    }
}
