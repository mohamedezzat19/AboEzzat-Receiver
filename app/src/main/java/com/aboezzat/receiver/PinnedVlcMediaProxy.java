package com.aboezzat.receiver;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Loopback-only bridge for VLC when the source is the Cast app's private
 * self-signed HTTPS media server. The upstream TLS connection remains pinned
 * by Receiver's OkHttpClient; only the already-verified bytes are exposed to
 * another process over 127.0.0.1. The proxy is never reachable from the LAN.
 */
final class PinnedVlcMediaProxy {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(4);
    private static ServerSocket listener;
    private static String accessPath = "";
    private static String sourceUrl = "";
    private static OkHttpClient upstreamClient;
    private static Thread acceptThread;

    private PinnedVlcMediaProxy() { }

    static synchronized String start(String upstreamUrl, OkHttpClient pinnedClient) throws Exception {
        stopLocked();
        byte[] token = new byte[24];
        RANDOM.nextBytes(token);
        accessPath = Base64.encodeToString(token,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        sourceUrl = upstreamUrl;
        upstreamClient = pinnedClient;

        ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        listener = server;
        acceptThread = new Thread(() -> acceptLoop(server), "AboEzzat-VLC-Loopback");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return "http://127.0.0.1:" + server.getLocalPort() + "/" + accessPath + "/media";
    }

    private static void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                WORKERS.execute(() -> serve(socket));
            } catch (IOException error) {
                if (!server.isClosed()) android.util.Log.w("AboEzzatVLC", "Loopback accept failed", error);
                return;
            }
        }
    }

    private static void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(20000);
            InputStream input = client.getInputStream();
            String requestLine = readLine(input, 8192);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ", 3);
            if (parts.length < 2) {
                writeError(client, 400, "Bad Request");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1].split("\\?", 2)[0];
            Map<String, String> headers = new HashMap<>();
            int total = 0;
            String line;
            while ((line = readLine(input, 8192)) != null && !line.isEmpty()) {
                total += line.length();
                if (total > 16384 || headers.size() >= 64) {
                    writeError(client, 431, "Request Header Fields Too Large");
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            final String expectedPath;
            final String target;
            final OkHttpClient clientForRequest;
            synchronized (PinnedVlcMediaProxy.class) {
                expectedPath = "/" + accessPath + "/media";
                target = sourceUrl;
                clientForRequest = upstreamClient;
            }
            if (!expectedPath.equals(path) || target.isEmpty() || clientForRequest == null) {
                writeError(client, 404, "Not Found");
                return;
            }
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                writeError(client, 405, "Method Not Allowed");
                return;
            }

            Request.Builder request = new Request.Builder().url(target)
                    .header("Accept-Encoding", "identity");
            String range = headers.get("range");
            if (range != null && range.matches("bytes=[0-9]*-[0-9]*")) request.header("Range", range);
            String ifRange = headers.get("if-range");
            if (ifRange != null && ifRange.length() <= 512) request.header("If-Range", ifRange);
            if ("HEAD".equals(method)) request.head(); else request.get();

            try (Response upstream = clientForRequest.newCall(request.build()).execute()) {
                int status = upstream.code();
                if (status >= 300 && status < 400) {
                    writeError(client, 502, "Upstream Redirect Rejected");
                    return;
                }
                ResponseBody body = upstream.body();
                StringBuilder responseHeaders = new StringBuilder("HTTP/1.1 ")
                        .append(status).append(" ").append(reason(status)).append("\r\n");
                String[] allowed = {"content-type", "content-length", "content-range",
                        "accept-ranges", "last-modified", "etag", "cache-control",
                        "content-disposition", "expires"};
                for (String name : allowed) {
                    String value = upstream.header(name);
                    if (value != null && value.length() <= 4096
                            && !value.contains("\r") && !value.contains("\n")) {
                        responseHeaders.append(name).append(": ").append(value).append("\r\n");
                    }
                }
                if (body != null && upstream.header("content-length") == null
                        && body.contentLength() >= 0) {
                    responseHeaders.append("Content-Length: ").append(body.contentLength()).append("\r\n");
                }
                responseHeaders.append("Connection: close\r\n\r\n");
                OutputStream output = client.getOutputStream();
                output.write(responseHeaders.toString().getBytes(StandardCharsets.US_ASCII));
                output.flush();
                if (!"HEAD".equals(method) && body != null) {
                    byte[] buffer = new byte[32768];
                    int count;
                    try (InputStream stream = body.byteStream()) {
                        while ((count = stream.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }
                        output.flush();
                    }
                }
            }
        } catch (Exception error) {
            android.util.Log.w("AboEzzatVLC", "Pinned VLC relay request failed", error);
        }
    }

    private static String readLine(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') {
                if (bytes.size() >= maxBytes) throw new IOException("HTTP line too long");
                bytes.write(value);
            }
        }
        if (value == -1 && bytes.size() == 0) return null;
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default: return "Upstream Response";
        }
    }

    private static void writeError(Socket socket, int code, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 " + code + " " + reason(code)
                + "\r\nContent-Type: text/plain; charset=utf-8"
                + "\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
    }

    private static synchronized void stopLocked() {
        if (listener != null) {
            try { listener.close(); } catch (IOException ignored) { }
            listener = null;
        }
        accessPath = "";
        sourceUrl = "";
        upstreamClient = null;
    }
}
