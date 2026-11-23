package com.aboezzat.receiver;

import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback-only streaming bridge for TikTok FLV; media bytes are never stored. */
final class TikTokVlcHttpBridge implements Closeable {
    private static final String VLC_USER_AGENT = "VLC/3.6.5 LibVLC/3.6.5";
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADER_BYTES = 32768;
    private static final int MAX_HEADERS = 64;
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 32 * 1024;

    private final HttpUrl originalUrl;
    private final Map<String, String> sourceHeaders;
    private final OkHttpClient client;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean requestActive = new AtomicBoolean(false);
    private final AtomicReference<Call> activeCall = new AtomicReference<>();
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private volatile ServerSocket serverSocket;
    private volatile Thread acceptThread;

    private String upstreamStatus = "not connected";
    private String upstreamHost = "unknown";
    private String contentType = "unknown";
    private String contentRange = "none";
    private String acceptRanges = "unknown";
    private long contentLength = -1L;
    private long responseTimeMs = -1L;
    private long bytesDelivered = 0L;
    private int redirectCount = 0;
    private String firstBytesHex = "none";
    private String signature = "not captured";
    private boolean eofReached = false;
    private String readError = "none";
    private String localTransferEncoding = "unknown";
    private long transferStartedAtMs = 0L;

    TikTokVlcHttpBridge(String mediaUrl, Map<String, String> headers) throws IOException {
        HttpUrl parsed = HttpUrl.parse(mediaUrl == null ? "" : mediaUrl);
        if (parsed == null || !"https".equalsIgnoreCase(parsed.scheme()) || parsed.host().isEmpty()) {
            throw new IOException("TikTok media URL must be HTTPS");
        }
        originalUrl = parsed;
        sourceHeaders = headers == null ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        client = new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    String start() throws IOException {
        if (closed.get()) throw new IOException("Bridge already closed");
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4);
        server.setSoTimeout(500);
        serverSocket = server;
        synchronized (this) {
            upstreamStatus = "bridge listening";
            upstreamHost = originalUrl.host();
            transferStartedAtMs = System.currentTimeMillis();
        }
        acceptThread = new Thread(this::acceptLoop, "TikTok-VLC-Bridge-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/live.flv";
    }

    private void acceptLoop() {
        while (!closed.get()) {
            ServerSocket server = serverSocket;
            if (server == null) return;
            try {
                Socket socket = server.accept();
                socket.setSoTimeout(15000);
                clientSockets.add(socket);
                workers.execute(() -> handleClient(socket));
            } catch (java.net.SocketTimeoutException ignored) {
                // Re-check shutdown state.
            } catch (SocketException error) {
                if (!closed.get()) setReadError(error);
                return;
            } catch (IOException | RuntimeException error) {
                if (!closed.get()) setReadError(error);
                return;
            }
        }
    }

    private void handleClient(Socket socket) {
        boolean ownsRequest = false;
        try (Socket clientSocket = socket) {
            BufferedInputStream input = new BufferedInputStream(clientSocket.getInputStream());
            OutputStream output = clientSocket.getOutputStream();
            String requestLine = readLine(input, MAX_LINE);
            if (requestLine == null || requestLine.isEmpty()) {
                writeSimpleResponse(output, 400, "Bad Request");
                return;
            }
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                writeSimpleResponse(output, 400, "Bad Request");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            if (!("GET".equals(method) || "HEAD".equals(method))) {
                writeSimpleResponse(output, 405, "Method Not Allowed");
                return;
            }
            if (!("/live.flv".equals(target) || "/".equals(target))) {
                writeSimpleResponse(output, 404, "Not Found");
                return;
            }

            String range = null;
            int totalHeaderBytes = 0;
            int headerCount = 0;
            String line;
            while ((line = readLine(input, MAX_LINE)) != null && !line.isEmpty()) {
                totalHeaderBytes += line.length() + 2;
                if (totalHeaderBytes > MAX_HEADER_BYTES || ++headerCount > MAX_HEADERS) {
                    writeSimpleResponse(output, 431, "Request Header Fields Too Large");
                    return;
                }
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if ("range".equalsIgnoreCase(name) && safeHeader(value, 256)) range = value;
            }

            if (!requestActive.compareAndSet(false, true)) {
                writeSimpleResponse(output, 503, "Stream Request Already Active");
                return;
            }
            ownsRequest = true;
            proxyUpstream(method, range, output);
        } catch (Exception error) {
            setReadError(error);
        } finally {
            clientSockets.remove(socket);
            if (ownsRequest) requestActive.set(false);
        }
    }

    private void proxyUpstream(String method, String range, OutputStream localOutput) {
        HttpUrl currentUrl = originalUrl;
        int redirects = 0;
        while (!closed.get()) {
            Request request = buildUpstreamRequest(currentUrl, method, range);
            Call call = client.newCall(request);
            activeCall.set(call);
            if (closed.get()) call.cancel();
            long started = System.currentTimeMillis();
            Response response = null;
            boolean localResponseStarted = false;
            try {
                response = call.execute();
                int code = response.code();
                String location = response.header("Location");
                if (isRedirect(code)) {
                    if (location == null) {
                        synchronized (this) {
                            upstreamStatus = String.valueOf(code);
                            upstreamHost = currentUrl.host();
                            redirectCount = redirects;
                            readError = "RedirectMissingLocation";
                        }
                        writeSimpleResponse(localOutput, 502, "Upstream Redirect Missing Location");
                        return;
                    }
                    HttpUrl next = currentUrl.resolve(location);
                    if (redirects >= MAX_REDIRECTS || next == null
                            || !"https".equalsIgnoreCase(next.scheme()) || next.port() != 443
                            || !next.username().isEmpty() || !next.password().isEmpty()
                            || !isAllowedRedirectHost(originalUrl.host(), next.host())) {
                        synchronized (this) {
                            upstreamStatus = String.valueOf(code);
                            upstreamHost = currentUrl.host();
                            redirectCount = redirects;
                            responseTimeMs = Math.max(0L, System.currentTimeMillis() - started);
                            readError = "RedirectBlockedByHostPolicy";
                        }
                        writeSimpleResponse(localOutput, 502, "Upstream Redirect Blocked");
                        return;
                    }
                    response.close();
                    response = null;
                    activeCall.compareAndSet(call, null);
                    currentUrl = next;
                    redirects++;
                    continue;
                }

                ResponseBody body = response.body();
                long length = "HEAD".equals(method) || body == null
                        ? parseLength(response.header("Content-Length"))
                        : body.contentLength();
                synchronized (this) {
                    upstreamStatus = String.valueOf(code);
                    upstreamHost = currentUrl.host();
                    contentType = safeDiagnosticValue(response.header("Content-Type"), "unknown");
                    contentRange = safeDiagnosticValue(response.header("Content-Range"), "none");
                    acceptRanges = safeDiagnosticValue(response.header("Accept-Ranges"), "unknown");
                    contentLength = length;
                    responseTimeMs = Math.max(0L, System.currentTimeMillis() - started);
                    redirectCount = redirects;
                    bytesDelivered = 0L;
                    firstBytesHex = "none";
                    signature = "capturing";
                    eofReached = false;
                    readError = "none";
                    transferStartedAtMs = System.currentTimeMillis();
                    localTransferEncoding = "HEAD".equals(method) ? "none"
                            : length >= 0L ? "content-length" : "chunked";
                }

                boolean chunkedResponse = !"HEAD".equals(method) && body != null && length < 0L;
                writeResponseHeaders(localOutput, code, response, length, chunkedResponse);
                localResponseStarted = true;
                if ("HEAD".equals(method) || body == null) {
                    synchronized (this) { eofReached = true; }
                    return;
                }

                InputStream upstream = body.byteStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                ByteArrayOutputStream first = new ByteArrayOutputStream(16);
                int count;
                while (!closed.get() && (count = upstream.read(buffer)) != -1) {
                    if (count == 0) continue;
                    if (first.size() < 16) {
                        first.write(buffer, 0, Math.min(count, 16 - first.size()));
                        updateFirstBytes(first.toByteArray());
                    }
                    if (chunkedResponse) {
                        byte[] chunkHeader = (Integer.toHexString(count) + "\r\n")
                                .getBytes(StandardCharsets.ISO_8859_1);
                        localOutput.write(chunkHeader);
                        localOutput.write(buffer, 0, count);
                        localOutput.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    } else {
                        localOutput.write(buffer, 0, count);
                    }
                    localOutput.flush();
                    synchronized (this) { bytesDelivered += count; }
                }
                if (chunkedResponse && !closed.get()) {
                    localOutput.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    localOutput.flush();
                }
                synchronized (this) {
                    eofReached = !closed.get();
                    if (closed.get()) readError = "BridgeStopped";
                    signature = describeSignature(first.toByteArray());
                }
                return;
            } catch (Exception error) {
                if (!closed.get()) setReadError(error);
                if (!localResponseStarted) {
                    try { writeSimpleResponse(localOutput, 502, "Upstream Stream Failed"); }
                    catch (Exception ignored) { }
                }
                return;
            } finally {
                activeCall.compareAndSet(call, null);
                if (response != null) response.close();
            }
        }
    }

    private Request buildUpstreamRequest(HttpUrl url, String method, String range) {
        Request.Builder builder = new Request.Builder().url(url)
                .header("User-Agent", VLC_USER_AGENT)
                .header("Accept-Encoding", "identity");
        putSourceHeader(builder, "accept", "Accept", 2048);
        putSourceHeader(builder, "accept-language", "Accept-Language", 2048);
        putSourceHeader(builder, "referer", "Referer", 2048);
        putSourceHeader(builder, "origin", "Origin", 2048);
        putSourceHeader(builder, "sec-fetch-site", "Sec-Fetch-Site", 128);
        putSourceHeader(builder, "sec-fetch-mode", "Sec-Fetch-Mode", 128);
        putSourceHeader(builder, "sec-fetch-dest", "Sec-Fetch-Dest", 128);
        putSourceHeader(builder, "sec-ch-ua", "Sec-CH-UA", 512);
        putSourceHeader(builder, "sec-ch-ua-mobile", "Sec-CH-UA-Mobile", 32);
        putSourceHeader(builder, "sec-ch-ua-platform", "Sec-CH-UA-Platform", 128);
        // Never send the browser cookie to a redirected, different host.
        if (url.host().equalsIgnoreCase(originalUrl.host())) {
            putSourceHeader(builder, "cookie", "Cookie", 8192);
        }
        if (range != null) builder.header("Range", range);
        if ("HEAD".equals(method)) builder.head(); else builder.get();
        return builder.build();
    }

    private void putSourceHeader(Request.Builder builder, String sourceName,
                                 String outgoingName, int maxLength) {
        for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
            if (entry.getKey() != null && sourceName.equalsIgnoreCase(entry.getKey())) {
                if (safeHeader(entry.getValue(), maxLength)) builder.header(outgoingName, entry.getValue());
                return;
            }
        }
    }

    private static boolean safeHeader(String value, int maxLength) {
        return value != null && !value.isEmpty() && value.length() <= maxLength
                && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static boolean isAllowedRedirectHost(String originalHost, String nextHost) {
        if (originalHost.equalsIgnoreCase(nextHost)) return true;
        return isTikTokCdnHost(originalHost) && isTikTokCdnHost(nextHost);
    }

    private static boolean isTikTokCdnHost(String host) {
        return host != null && (host.equalsIgnoreCase("tiktokcdn.com")
                || host.toLowerCase(Locale.ROOT).endsWith(".tiktokcdn.com"));
    }

    private void writeResponseHeaders(OutputStream output, int code, Response response,
                                      long length, boolean chunked) throws IOException {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(reasonPhrase(code)).append("\r\n");
        appendResponseHeader(headers, "Content-Type", response.header("Content-Type"));
        appendResponseHeader(headers, "Content-Range", response.header("Content-Range"));
        appendResponseHeader(headers, "Accept-Ranges", response.header("Accept-Ranges"));
        appendResponseHeader(headers, "Cache-Control", response.header("Cache-Control"));
        if (length >= 0L) headers.append("Content-Length: ").append(length).append("\r\n");
        else if (chunked) headers.append("Transfer-Encoding: chunked\r\n");
        headers.append("Connection: close\r\n\r\n");
        output.write(headers.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static void appendResponseHeader(StringBuilder output, String name, String value) {
        if (safeHeader(value, 2048)) output.append(name).append(": ").append(value).append("\r\n");
    }

    private static String reasonPhrase(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 303: return "See Other";
            case 304: return "Not Modified";
            case 307: return "Temporary Redirect";
            case 308: return "Permanent Redirect";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            case 503: return "Service Unavailable";
            default: return "Upstream Response";
        }
    }

    private static void writeSimpleResponse(OutputStream output, int code, String reason)
            throws IOException {
        byte[] body = reason.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private static String readLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') {
                if (line.size() >= limit) throw new IOException("HTTP line too long");
                line.write(value);
            }
        }
        if (value == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private void updateFirstBytes(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(16, bytes.length); i++) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.ROOT, "%02X", bytes[i] & 0xff));
        }
        synchronized (this) {
            firstBytesHex = hex.length() == 0 ? "none" : hex.toString();
            signature = describeSignature(bytes);
        }
    }

    private static String describeSignature(byte[] bytes) {
        if (bytes == null || bytes.length < 3) return "incomplete";
        if (bytes[0] == 'F' && bytes[1] == 'L' && bytes[2] == 'V') return "FLV";
        if (bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') return "ID3";
        return "unknown";
    }

    private static long parseLength(String value) {
        try { return value == null ? -1L : Long.parseLong(value); }
        catch (Exception ignored) { return -1L; }
    }

    private static String safeDiagnosticValue(String value, String fallback) {
        if (value == null || value.isEmpty() || value.length() > 160
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return fallback;
        return value;
    }

    private void setReadError(Throwable error) {
        synchronized (this) {
            readError = error == null ? "unknown" : error.getClass().getSimpleName();
        }
    }

    synchronized String getDiagnostic() {
        long elapsed = transferStartedAtMs <= 0L ? -1L
                : Math.max(0L, System.currentTimeMillis() - transferStartedAtMs);
        return "upstream_status=" + upstreamStatus + ", host=" + upstreamHost
                + ", content_type=" + contentType + ", content_length=" + contentLength
                + ", content_range=" + contentRange + ", accept_ranges=" + acceptRanges
                + ", redirects=" + redirectCount + ", response_ms=" + responseTimeMs
                + ", local_transfer_encoding=" + localTransferEncoding
                + ", payload_bytes_written_to_local_socket=" + bytesDelivered + ", elapsed_ms=" + elapsed
                + ", signature=" + signature + ", first_bytes_hex=" + firstBytesHex
                + ", eof=" + eofReached + ", error=" + readError;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Call call = activeCall.getAndSet(null);
        if (call != null) call.cancel();
        ServerSocket server = serverSocket;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) { }
        }
        for (Socket socket : clientSockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        clientSockets.clear();
        workers.shutdownNow();
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
        Thread thread = acceptThread;
        if (thread != null) thread.interrupt();
        synchronized (this) {
            if (!eofReached && bytesDelivered > 0L) readError = "BridgeStopped";
        }
    }
}
