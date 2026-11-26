package com.aboezzat.receiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

public class ReceiverServerService extends Service {
    private static final String TAG = "AboEzzatReceiverService";
    public static final String ACTION_SERVER_REQUEST = "com.aboezzat.receiver.SERVER_REQUEST";
    public static final String EXTRA_URL = "server_url";
    public static final String EXTRA_METADATA = "server_metadata";
    public static final String EXTRA_AUDIO = "server_audio";
    public static final String EXTRA_SOURCE_URL = "server_source_url";
    public static final String EXTRA_COMMAND = "server_command";
    public static final String EXTRA_OPEN_BROWSER = "server_open_browser";
    public static final String EXTRA_QUEUE_JSON = "server_queue_json";

    private static final int DEFAULT_PORT = 8081;
    private volatile int activePort = DEFAULT_PORT;

    private int getConfiguredPort() {
        return getSharedPreferences("receiver_settings", MODE_PRIVATE)
                .getInt("receiver_port", DEFAULT_PORT);
    }
    private static final int DISCOVERY_PORT = 37020;
    private static final String DISCOVERY_GROUP = "239.255.42.99";
    private static final String CHANNEL_ID = "aboezzat_receiver_service";
    private static final int NOTIFICATION_ID = 8082;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private Thread heartbeatThread;
    private DlnaMediaRenderer dlnaRenderer;
    private android.net.wifi.WifiManager.MulticastLock dlnaMulticastLock;
    // Bound concurrent clients so a burst of LAN requests cannot create unbounded threads.
    private final ThreadPoolExecutor requestExecutor = new ThreadPoolExecutor(
            2, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        activePort = getConfiguredPort();
        startForeground(NOTIFICATION_ID, buildNotification());
        startServer();
        startDiscoveryHeartbeat();
        startDlnaRenderer();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY lets Android recreate the listener after reclaiming the process.
        // It cannot override force-stop or device/OEM background restrictions.
        if (!running) {
            activePort = getConfiguredPort();
            startForeground(NOTIFICATION_ID, buildNotification());
            startServer();
            startDiscoveryHeartbeat();
            startDlnaRenderer();
        }
        return START_STICKY;
    }

    private void startDlnaRenderer() {
        if (dlnaRenderer != null) return;
        try {
            android.net.wifi.WifiManager wifi =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifi != null) {
                dlnaMulticastLock = wifi.createMulticastLock("AboEzzatReceiver:DLNA");
                dlnaMulticastLock.setReferenceCounted(false);
                dlnaMulticastLock.acquire();
            }
            SharedPreferences prefs = getSharedPreferences("receiver_settings", MODE_PRIVATE);
            String id = prefs.getString("dlna_uuid", null);
            if (id == null || id.trim().isEmpty()) {
                id = java.util.UUID.randomUUID().toString();
                prefs.edit().putString("dlna_uuid", id).apply();
            }
            dlnaRenderer = new DlnaMediaRenderer(getApplicationContext(), id, new DlnaMediaRenderer.Listener() {
                @Override public void onSetUri(String uri, String metadata) {
                    dispatchDlnaRequest(uri, metadata, null);
                }
                @Override public void onCommand(String command) {
                    dispatchDlnaRequest(null, null, command);
                }
            });
            dlnaRenderer.start();
        } catch (Exception e) {
            Log.e(TAG, "Could not initialize DLNA renderer", e);
            if (dlnaMulticastLock != null && dlnaMulticastLock.isHeld()) dlnaMulticastLock.release();
            dlnaMulticastLock = null;
        }
    }

    private void dispatchDlnaRequest(String uri, String metadata, String command) {
        Intent request = new Intent(this, MainActivity.class);
        request.setAction(ACTION_SERVER_REQUEST);
        if (uri != null) request.putExtra(EXTRA_URL, uri);
        if (metadata != null) request.putExtra(EXTRA_METADATA, metadata);
        if (command != null) request.putExtra(EXTRA_COMMAND, "dlna_" + command);
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(request);
        } catch (Exception e) {
            Log.e(TAG, "OS blocked DLNA playback/control activity launch", e);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AboEzzat Receiver", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps local-network media reception available");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 8081, openApp, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("AboEzzat Receiver")
                .setContentText("استقبال الروابط يعمل على المنفذ " + activePort + "")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }

    private synchronized void startServer() {
        if (running) return;
        running = true;
        serverThread = new Thread(() -> {
            try {
                ServerSocket listener = ReceiverTlsIdentity.createServerSocket(activePort);
                serverSocket = listener;
                Log.i(TAG, "Background receiver TLS listener ready on port " + activePort);
                while (running) {
                    try {
                        Socket client = listener.accept();
                        try {
                            requestExecutor.execute(() -> handleRequest(client));
                        } catch (RejectedExecutionException overloaded) {
                            // Fail fast instead of accumulating sockets/threads without a bound.
                            try (Socket rejected = client) {
                                // Even overload responses must use the TLS record layer.
                                // Writing plaintext HTTP bytes to an SSLSocket before its
                                // handshake makes clients report EOF/connection-closed TLS errors.
                                if (!(rejected instanceof SSLSocket)) {
                                    throw new SSLException("Rejected connection is not an SSLSocket");
                                }
                                SSLSocket rejectedTls = (SSLSocket) rejected;
                                rejectedTls.setUseClientMode(false);
                                rejectedTls.setSoTimeout(5000);
                                rejectedTls.startHandshake();
                                byte[] body = "Receiver busy".getBytes(StandardCharsets.UTF_8);
                                OutputStream output = rejectedTls.getOutputStream();
                                output.write(("HTTP/1.1 503 Service Unavailable\r\n"
                                        + "Content-Type: text/plain; charset=utf-8\r\n"
                                        + "Cache-Control: no-store, max-age=0\r\n"
                                        + "Pragma: no-cache\r\n"
                                        + "X-Content-Type-Options: nosniff\r\n"
                                        + "Connection: close\r\n"
                                        + "Content-Length: " + body.length + "\r\n\r\n")
                                        .getBytes(StandardCharsets.UTF_8));
                                output.write(body);
                                output.flush();
                            } catch (Exception ignored) { }
                        }
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "Accept failed; listener will retry", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not start background receiver on port " + activePort, e);
                running = false;
                stopSelf();
            }
        }, "ReceiverServerService");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private NetworkInterface getLanInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            NetworkInterface fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("tun") || name.contains("tap") || name.contains("vpn")
                        || name.contains("ppp") || name.contains("wg")) continue;
                boolean hasLanIpv4 = false;
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()
                            && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                        hasLanIpv4 = true;
                        break;
                    }
                }
                if (!hasLanIpv4) continue;
                if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) return ni;
                if (fallback == null) fallback = ni;
            }
            return fallback;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void startDiscoveryHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) return;
        heartbeatThread = new Thread(() -> {
            while (running) {
                try {
                    String lanIp = getCurrentIpAddress();
                    NetworkInterface lanInterface = getLanInterface();
                    try (MulticastSocket discoverySocket = new MulticastSocket()) {
                        if (lanInterface != null) discoverySocket.setNetworkInterface(lanInterface);
                        discoverySocket.setTimeToLive(1);
                        byte[] data = ("ABOEZZAT_RECEIVER|" + lanIp + "|" + activePort)
                                .getBytes(StandardCharsets.UTF_8);
                        DatagramPacket packet = new DatagramPacket(
                                data, data.length, InetAddress.getByName(DISCOVERY_GROUP), DISCOVERY_PORT);
                        discoverySocket.send(packet);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Discovery heartbeat failed", e);
                }
                try {
                    Thread.sleep(1200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "ReceiverDiscoveryHeartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private String getCurrentIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) continue;
                String name = networkInterface.getName() == null ? "" : networkInterface.getName().toLowerCase(java.util.Locale.ROOT);
                // Do not advertise VPN/tunnel addresses: the phone cannot connect to them as a TV receiver.
                if (name.contains("tun") || name.contains("tap") || name.contains("vpn")
                        || name.contains("ppp") || name.contains("wg")) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress() || !address.isSiteLocalAddress()) continue;
                    String ip = address.getHostAddress();
                    if (name.contains("wlan") || name.contains("wifi") || name.contains("eth")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback == null ? "0.0.0.0" : fallback;
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve local LAN IPv4 address", e);
            return "0.0.0.0";
        }
    }

    private String encryptPairingSecret(String plainText) throws Exception {
        SecretKey key = getOrCreatePairingKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        ByteBuffer packed = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        packed.putInt(iv.length).put(iv).put(encrypted);
        return android.util.Base64.encodeToString(packed.array(), android.util.Base64.NO_WRAP);
    }

    private String decryptPairingSecret(String encoded) {
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] packedBytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
            ByteBuffer packed = ByteBuffer.wrap(packedBytes);
            int ivLength = packed.getInt();
            if (ivLength < 12 || ivLength > 16 || packed.remaining() <= ivLength) return "";
            byte[] iv = new byte[ivLength];
            packed.get(iv);
            byte[] encrypted = new byte[packed.remaining()];
            packed.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreatePairingKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.w(TAG, "Stored receiver key could not be decrypted");
            return "";
        }
    }

    private SecretKey getOrCreatePairingKey() throws Exception {
        final String alias = "AboEzzatReceiver.AuthKey";
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (!keyStore.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
    }

    // Serialize pairing attempts so concurrent requests cannot consume the same one-time code.
    private synchronized String createPairingResponse(String suppliedCode) {
        SharedPreferences pairing = getSharedPreferences("AboEzzatPairing", MODE_PRIVATE);
        String expectedCode = pairing.getString("pairing_code", "");
        long expiresAt = pairing.getLong("code_expires_at", 0L);
        int failedAttempts = pairing.getInt("failed_attempts", 0);
        boolean valid = !expectedCode.isEmpty()
                && System.currentTimeMillis() < expiresAt
                && failedAttempts < 5
                && suppliedCode != null
                && MessageDigest.isEqual(expectedCode.getBytes(StandardCharsets.US_ASCII),
                        suppliedCode.getBytes(StandardCharsets.US_ASCII));
        if (!valid) {
            int nextAttempts = failedAttempts + 1;
            SharedPreferences.Editor edit = pairing.edit().putInt("failed_attempts", nextAttempts);
            if (nextAttempts >= 5 || System.currentTimeMillis() >= expiresAt) {
                edit.remove("pairing_code").remove("code_expires_at");
            }
            if (!edit.commit()) {
                Log.e(TAG, "Could not persist failed pairing attempt");
            }
            return null;
        }
        byte[] secretBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(secretBytes);
        String secret = android.util.Base64.encodeToString(secretBytes,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
        final String protectedSecret;
        try {
            protectedSecret = encryptPairingSecret(secret);
        } catch (Exception encryptionError) {
            Log.e(TAG, "Could not protect new receiver pairing key", encryptionError);
            return null;
        }
        // Persist the one-time code consumption and new encrypted key before returning the secret.
        // This prevents a process restart from reopening the same pairing window.
        boolean committed = pairing.edit().putString("receiver_secret_enc", protectedSecret)
                .remove("receiver_secret").remove("pairing_code")
                .remove("code_expires_at").remove("failed_attempts").commit();
        if (!committed) {
            Log.e(TAG, "Could not persist receiver pairing state");
            return null;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("paired", true);
            result.put("secret", secret);
            return result.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String queryValue(String target, String wantedKey) {
        int question = target.indexOf('?');
        if (question < 0 || question + 1 >= target.length()) return "";
        for (String pair : target.substring(question + 1).split("&")) {
            String[] item = pair.split("=", 2);
            if (item.length == 2) {
                try {
                    if (wantedKey.equals(URLDecoder.decode(item[0], "UTF-8"))) {
                        return URLDecoder.decode(item[1], "UTF-8");
                    }
                } catch (Exception ignored) { return ""; }
            }
        }
        return "";
    }

    private synchronized boolean verifySignedRequest(String method, String target, byte[] requestBody,
                                                      String timestamp, String nonce, String suppliedSignature) {
        if (timestamp == null || nonce == null || suppliedSignature == null
                || nonce.length() < 16 || nonce.length() > 80 || suppliedSignature.length() > 128) return false;
        long requestTime;
        try { requestTime = Long.parseLong(timestamp); } catch (Exception e) { return false; }
        long now = System.currentTimeMillis();
        if (requestTime < now - 90000L || requestTime > now + 90000L) return false;
        String secret = decryptPairingSecret(getSharedPreferences("AboEzzatPairing", MODE_PRIVATE)
                .getString("receiver_secret_enc", ""));
        if (secret.isEmpty()) return false;
        try {
            byte[] safeBody = requestBody == null ? new byte[0] : requestBody;
            String bodyHash = android.util.Base64.encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(safeBody),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            String canonical = method.toUpperCase(java.util.Locale.ROOT) + "\n"
                    + target + "\n" + bodyHash + "\n" + timestamp + "\n" + nonce;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = android.util.Base64.encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)),
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    suppliedSignature.getBytes(StandardCharsets.US_ASCII))) return false;

            // Persist the replay window so a service/process restart cannot reopen
            // the same signed request during its timestamp validity window.
            SharedPreferences replayPrefs = getSharedPreferences("AboEzzatAuthReplay", MODE_PRIVATE);
            JSONObject recentNonces = new JSONObject(replayPrefs.getString("recent_nonces", "{}"));
            java.util.Iterator<String> keys = recentNonces.keys();
            while (keys.hasNext()) {
                String oldNonce = keys.next();
                long seenAt = recentNonces.optLong(oldNonce, 0L);
                if (seenAt <= 0L || now - seenAt > 120000L) keys.remove();
            }
            if (recentNonces.has(nonce) || recentNonces.length() >= 4096) return false;
            recentNonces.put(nonce, now);
            return replayPrefs.edit().putString("recent_nonces", recentNonces.toString()).commit();
        } catch (Exception e) {
            Log.w(TAG, "Request authentication failed", e);
            return false;
        }
    }

    // Validate every playlist entry before accepting any part of the batch.
    // Local Cast media entries carry their own TLS pin; pins are committed only
    // after the complete bounded payload has passed validation.
    private JSONObject validateQueuePayload(byte[] requestBody, InetAddress sender) throws Exception {
        if (requestBody == null || requestBody.length == 0 || requestBody.length > 65536) {
            throw new IOException("Invalid queue body size");
        }
        JSONObject supplied = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
        String mode = supplied.optString("mode", "replace");
        if (!"replace".equals(mode) && !"append".equals(mode)) throw new IOException("Invalid queue mode");
        org.json.JSONArray entries = supplied.optJSONArray("items");
        if (entries == null || entries.length() < 1 || entries.length() > 50) {
            throw new IOException("Queue must contain 1 to 50 items");
        }
        org.json.JSONArray safeEntries = new org.json.JSONArray();
        SharedPreferences.Editor pinEdit = getSharedPreferences(
                "AboEzzatReceiverMediaTlsPins", MODE_PRIVATE).edit();
        java.util.HashMap<String, String> stagedPins = new java.util.HashMap<>();
        String[] allowed = {"user-agent", "referer", "origin", "accept", "accept-language", "cookie",
                "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-ch-ua",
                "sec-ch-ua-mobile", "sec-ch-ua-platform", "priority"};
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item == null) throw new IOException("Invalid queue item");
            String url = item.optString("url", "").trim();
            String audio = item.optString("audio", "").trim();
            String source = item.optString("sourceUrl", "").trim();
            String mediaPin = item.optString("mediaPin", "");
            String audioPin = item.optString("audioPin", "");
            if ((url.isEmpty() && audio.isEmpty()) || url.length() > 8192 || audio.length() > 8192
                    || source.length() > 8192
                    || (!url.isEmpty() && !isHttpUrl(url))
                    || (!audio.isEmpty() && !isHttpUrl(audio))
                    || (!source.isEmpty() && !isHttpUrl(source))) {
                throw new IOException("Queue item contains an invalid URL");
            }
            JSONObject safe = new JSONObject();
            if (!url.isEmpty()) safe.put("url", url);
            if (!audio.isEmpty()) safe.put("audio", audio);
            if (!source.isEmpty()) safe.put("sourceUrl", source);
            validateQueueMediaPin(url, mediaPin, sender, pinEdit, stagedPins);
            validateQueueMediaPin(audio, audioPin, sender, pinEdit, stagedPins);
            if (!mediaPin.isEmpty()) safe.put("mediaPin", mediaPin);
            if (!audioPin.isEmpty()) safe.put("audioPin", audioPin);
            JSONObject suppliedHeaders = item.optJSONObject("headers");
            JSONObject safeHeaders = new JSONObject();
            if (suppliedHeaders != null) {
                for (String key : allowed) {
                    String value = suppliedHeaders.optString(key, "");
                    int max = "cookie".equals(key) ? 8192 : 2048;
                    if (!value.isEmpty() && value.length() <= max
                            && !value.contains("\r") && !value.contains("\n")) {
                        safeHeaders.put(key, value);
                    }
                }
            }
            safe.put("headers", safeHeaders);
            safeEntries.put(safe);
        }
        if (!pinEdit.commit()) throw new IOException("Could not persist media TLS pins");
        JSONObject result = new JSONObject();
        result.put("mode", mode);
        result.put("items", safeEntries);
        result.put("receivedAt", System.currentTimeMillis());
        return result;
    }

    private void validateQueueMediaPin(String value, String suppliedPin, InetAddress sender,
                                        SharedPreferences.Editor pinEdit,
                                        java.util.Map<String, String> stagedPins) throws Exception {
        if (value == null || value.isEmpty()) {
            if (suppliedPin != null && !suppliedPin.isEmpty()) throw new IOException("Unexpected media pin");
            return;
        }
        boolean castPort = isCastMediaPortUrl(value);
        boolean validCast = isValidCastTlsMediaUrl(value);
        if (!castPort) {
            if (suppliedPin != null && !suppliedPin.isEmpty()) throw new IOException("Unexpected media pin");
            return;
        }
        if (!validCast || suppliedPin == null || !suppliedPin.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("Invalid local media TLS pin");
        }
        String host = new java.net.URL(value).getHost().toLowerCase(java.util.Locale.ROOT);
        InetAddress address = InetAddress.getByName(host);
        if (!(address instanceof java.net.Inet4Address) || !address.equals(sender)) {
            throw new IOException("Local media host does not match authenticated sender");
        }
        String previousBatchPin = stagedPins.get(host);
        if (previousBatchPin != null && !previousBatchPin.equals(suppliedPin)) {
            throw new IOException("Conflicting TLS pins for one local media host");
        }
        stagedPins.put(host, suppliedPin);
        pinEdit.putString("pin_" + host, suppliedPin);
    }

    private void handleRequest(Socket client) {
        String mediaUrl = null;
        String audioUrl = null;
        String sourceUrl = null;
        String command = null;
        String mediaTlsPin = null;
        boolean invalidMediaUrl = false;
        boolean invalidRequestMethod = false;
        boolean unauthorized = false;
        boolean pairingRequest = false;
        boolean queueRequest = false;
        String queueJson = null;
        boolean openBrowser = false;
        String pairingResponse = null;
        JSONObject incomingHeaders = new JSONObject();
        try (Socket socket = client) {
            // The listener is TLS-only. Complete the handshake explicitly before
            // parsing HTTP so TLS failures are reported at their true boundary.
            if (!(socket instanceof SSLSocket)) {
                throw new SSLException("Accepted connection is not an SSLSocket");
            }
            SSLSocket tlsSocket = (SSLSocket) socket;
            tlsSocket.setUseClientMode(false);
            tlsSocket.setSoTimeout(15000);
            try {
                tlsSocket.startHandshake();
                SSLSession tlsSession = tlsSocket.getSession();
                String tlsSuccess = "نجحت مصافحة TLS"
                        + "\nالبروتوكول: " + tlsSession.getProtocol()
                        + "\nالتشفير: " + tlsSession.getCipherSuite();
                getSharedPreferences("ReceiverTlsDiagnostics", MODE_PRIVATE).edit()
                        .putString("last_tls_result", tlsSuccess)
                        .putLong("last_tls_time", System.currentTimeMillis())
                        .apply();
                Log.i(TAG, "TLS handshake completed: protocol=" + tlsSession.getProtocol()
                        + ", cipher=" + tlsSession.getCipherSuite()
                        + ", peer=" + client.getInetAddress().getHostAddress());
            } catch (Exception handshakeError) {
                String tlsFailure = handshakeError.getClass().getSimpleName()
                        + (handshakeError.getMessage() == null ? "" : ": " + handshakeError.getMessage());
                getSharedPreferences("ReceiverTlsDiagnostics", MODE_PRIVATE).edit()
                        .putString("last_tls_result", "فشلت مصافحة TLS\n" + tlsFailure)
                        .putLong("last_tls_time", System.currentTimeMillis())
                        .apply();
                Log.e(TAG, "TLS handshake failed from "
                        + client.getInetAddress().getHostAddress()
                        + ": " + handshakeError.getClass().getSimpleName(), handshakeError);
                throw handshakeError;
            }
            InputStream input = tlsSocket.getInputStream();
            String requestLine = readBoundedRequestLine(input);
            String method = "";
            String target = "";
            if (requestLine != null) {
                String[] parts = requestLine.split(" ", 3);
                if (parts.length >= 2) {
                    method = parts[0].trim();
                    target = parts[1].trim();
                }
            }

            invalidRequestMethod = !("GET".equalsIgnoreCase(method)
                    || "POST".equalsIgnoreCase(method));
            if ("POST".equalsIgnoreCase(method)
                    && !"/play".equals(target) && !"/queue".equals(target) && !"/pair".equals(target)) {
                invalidRequestMethod = true;
            }
            if ("GET".equalsIgnoreCase(method) && target.startsWith("/pair")) {
                // Pairing codes must never be accepted in URLs or query strings.
                invalidRequestMethod = true;
            }
            int contentLength = 0;
            int totalHeaderBytes = 0;
            int headerCount = 0;
            boolean contentLengthSeen = false;
            String authTime = null;
            String authNonce = null;
            String authSignature = null;
            String requestContentType = null;
            String headerLine;
            while ((headerLine = readBoundedRequestLine(input)) != null && !headerLine.isEmpty()) {
                totalHeaderBytes += headerLine.length();
                if (totalHeaderBytes > 16384) throw new IOException("HTTP headers exceed limit");
                if (++headerCount > 64) throw new IOException("Too many HTTP headers");
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String headerName = headerLine.substring(0, colon).trim();
                    String headerValue = headerLine.substring(colon + 1).trim();
                    if ("content-length".equalsIgnoreCase(headerName)) {
                        // Conflicting or repeated lengths can make HTTP parsers disagree.
                        if (contentLengthSeen) {
                            contentLength = -1;
                        } else {
                            contentLengthSeen = true;
                            try {
                                if (!headerValue.matches("[0-9]{1,6}")) contentLength = -1;
                                else contentLength = Integer.parseInt(headerValue);
                            } catch (Exception ignored) { contentLength = -1; }
                        }
                    } else if ("transfer-encoding".equalsIgnoreCase(headerName)) {
                        // Chunked bodies are unsupported; reject instead of ambiguously parsing them.
                        contentLength = -1;
                    } else if ("content-type".equalsIgnoreCase(headerName)) requestContentType = headerValue;
                    else if ("x-aboezzat-time".equalsIgnoreCase(headerName)) authTime = headerValue;
                    else if ("x-aboezzat-nonce".equalsIgnoreCase(headerName)) authNonce = headerValue;
                    else if ("x-aboezzat-signature".equalsIgnoreCase(headerName)) authSignature = headerValue;
                }
            }

            String requestPath = target.split("\\?", 2)[0];
            pairingRequest = "POST".equalsIgnoreCase(method) && "/pair".equals(target);
            queueRequest = "POST".equalsIgnoreCase(method) && "/queue".equals(target);
            byte[] requestBody = new byte[0];
            if ("POST".equalsIgnoreCase(method)
                    && ("/play".equals(target) || queueRequest || pairingRequest)
                    && !invalidRequestMethod) {
                int maxBodyLength = pairingRequest ? 1024 : 65536;
                if (contentLength < 0 || contentLength > maxBodyLength) {
                    invalidMediaUrl = true;
                } else {
                    requestBody = new byte[contentLength];
                    int offset = 0;
                    while (offset < contentLength) {
                        int count = input.read(requestBody, offset, contentLength - offset);
                        if (count < 0) throw new IOException("Incomplete HTTP request body");
                        offset += count;
                    }
                }
            }
            if (pairingRequest && !invalidRequestMethod && !invalidMediaUrl) {
                // Require a bounded JSON payload and a syntactically valid 8-digit code.
                // Malformed requests must not consume one of the user's five pairing attempts.
                if (requestContentType == null
                        || !"application/json".equalsIgnoreCase(
                                requestContentType.split(";", 2)[0].trim())
                        || requestBody.length == 0 || requestBody.length > 1024) {
                    invalidMediaUrl = true;
                } else {
                    String suppliedCode = "";
                    try {
                        JSONObject pairingPayload = new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
                        suppliedCode = pairingPayload.optString("code", "");
                    } catch (Exception malformedPairingBody) {
                        suppliedCode = "";
                    }
                    if (!suppliedCode.matches("[0-9]{8}")) {
                        invalidMediaUrl = true;
                    } else {
                        pairingResponse = createPairingResponse(suppliedCode);
                        unauthorized = pairingResponse == null;
                    }
                }
            } else if (!invalidRequestMethod && !invalidMediaUrl) {
                unauthorized = !verifySignedRequest(method, target, requestBody,
                        authTime, authNonce, authSignature);
            }

            if (!unauthorized && queueRequest && !invalidMediaUrl && !invalidRequestMethod) {
                if (requestContentType == null
                        || !"application/json".equalsIgnoreCase(
                                requestContentType.split(";", 2)[0].trim())
                        || requestBody.length == 0) {
                    invalidMediaUrl = true;
                } else {
                    try {
                        JSONObject validatedQueue = validateQueuePayload(requestBody, client.getInetAddress());
                        queueJson = validatedQueue.toString();
                    } catch (Exception invalidQueue) {
                        Log.w(TAG, "Rejected invalid authenticated queue payload", invalidQueue);
                        invalidMediaUrl = true;
                    }
                }
            }

            if (!unauthorized && !pairingRequest && !invalidMediaUrl
                    && "POST".equalsIgnoreCase(method) && "/play".equals(target)
                    && !invalidRequestMethod) {
                    // /play is a JSON-only signed API. Reject empty bodies and ambiguous
                    // content types before parsing so clients and the server agree on framing.
                    if (requestContentType == null
                            || !"application/json".equalsIgnoreCase(
                                    requestContentType.split(";", 2)[0].trim())
                            || requestBody.length == 0) {
                        invalidMediaUrl = true;
                    }
                    JSONObject payload;
                    try {
                        payload = invalidMediaUrl ? new JSONObject()
                                : new JSONObject(new String(requestBody, StandardCharsets.UTF_8));
                    } catch (Exception malformedJson) {
                        payload = new JSONObject();
                        invalidMediaUrl = true;
                    }
                    mediaUrl = payload.optString("url", "");
                    // /play POST requests may carry a separate DASH audio-only track.
                    // Preserve it exactly like the legacy GET ?audio=... path.
                    audioUrl = payload.optString("audio", "");
                    sourceUrl = payload.optString("sourceUrl", "");
                    // Local Cast media URLs require their authenticated TLS pin too.
                    mediaTlsPin = payload.optString("mediaPin", "");
                    if ((mediaUrl == null || mediaUrl.trim().isEmpty())
                            && (audioUrl == null || audioUrl.trim().isEmpty())) {
                        invalidMediaUrl = true;
                    }
                    JSONObject suppliedHeaders = payload.optJSONObject("headers");
                    JSONObject safeHeaders = new JSONObject();
                    if (suppliedHeaders != null) {
                        String[] allowed = {"user-agent", "referer", "origin", "accept", "accept-language", "cookie",
                                "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest",
                                "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "priority"};
                        for (String key : allowed) {
                            String value = suppliedHeaders.optString(key, "");
                            int max = "cookie".equals(key) ? 8192 : 2048;
                            if (!value.isEmpty() && value.length() <= max
                                    && !value.contains("\r") && !value.contains("\n")) {
                                safeHeaders.put(key, value);
                            }
                        }
                    }
                    // Keep the sanitized headers until the URL/audio pair is persisted below.
                    incomingHeaders = safeHeaders;
            } else if (!unauthorized && !pairingRequest && target.contains("?")) {
                int questionIndex = target.indexOf('?');
                for (String pair : target.substring(questionIndex + 1).split("&")) {
                    String[] item = pair.split("=", 2);
                    if (item.length != 2) continue;
                    String key = URLDecoder.decode(item[0], "UTF-8");
                    String value = URLDecoder.decode(item[1], "UTF-8");
                    if ("url".equals(key) || "video".equals(key)) mediaUrl = value;
                    else if ("audio".equals(key)) audioUrl = value;
                    else if ("sourceUrl".equals(key)) sourceUrl = value;
                    else if ("mediaPin".equals(key)) mediaTlsPin = value;
                    else if ("command".equals(key)) command = value;
                    else if ("open".equals(key) || "browser".equals(key))
                        openBrowser = "1".equals(value) || "true".equalsIgnoreCase(value) || "browser".equalsIgnoreCase(value);
                }
            }

            if (sourceUrl != null && !sourceUrl.trim().isEmpty() && !isHttpUrl(sourceUrl)) sourceUrl = null;
            boolean castMediaPortUrl = isCastMediaPortUrl(mediaUrl);
            boolean validCastTlsMediaUrl = isValidCastTlsMediaUrl(mediaUrl);
            invalidMediaUrl = invalidMediaUrl || (mediaUrl != null && !mediaUrl.trim().isEmpty()
                    && !isHttpUrl(mediaUrl))
                    || (audioUrl != null && !audioUrl.trim().isEmpty() && !isHttpUrl(audioUrl))
                    || (castMediaPortUrl && (!validCastTlsMediaUrl
                            || mediaTlsPin == null || !mediaTlsPin.matches("[A-Za-z0-9_-]{43}")))
                    || (mediaTlsPin != null && !validCastTlsMediaUrl);
            if (!invalidMediaUrl && validCastTlsMediaUrl) {
                try {
                    String mediaHost = new java.net.URL(mediaUrl).getHost().toLowerCase(java.util.Locale.ROOT);
                    java.net.InetAddress mediaAddress = java.net.InetAddress.getByName(mediaHost);
                    // The media endpoint must be the same phone that sent this
                    // authenticated command; do not accept a pin for an arbitrary host.
                    if (!(mediaAddress instanceof java.net.Inet4Address)
                            || !mediaAddress.equals(client.getInetAddress())) {
                        invalidMediaUrl = true;
                    } else {
                        SharedPreferences mediaPins = getSharedPreferences(
                                "AboEzzatReceiverMediaTlsPins", MODE_PRIVATE);
                        String pinKey = "pin_" + mediaHost;
                        String existingPin = mediaPins.getString(pinKey, "");
                        // A first pin may be stored only once. Never silently replace an
                        // established certificate pin based on a later network request.
                        // This block is reached only after verifySignedRequest() succeeds.
                        // The request HMAC covers the complete target (including mediaPin),
                        // and the media host is constrained to the authenticated sender's IP.
                        // Therefore a paired Cast installation may rotate its own media TLS
                        // certificate pin without permitting an unauthenticated LAN client to do so.
                        if (existingPin == null || !existingPin.equals(mediaTlsPin)) {
                            if (!mediaPins.edit().putString(pinKey, mediaTlsPin).commit()) {
                                invalidMediaUrl = true;
                            } else if (existingPin != null && !existingPin.isEmpty()) {
                                Log.i(TAG, "Updated authenticated Cast media TLS pin for " + mediaHost);
                            }
                        }
                    }
                } catch (Exception invalidPinTarget) {
                    invalidMediaUrl = true;
                }
            }
            String body = unauthorized ? "Unauthorized"
                    : pairingRequest ? (pairingResponse == null ? "Pairing code invalid or expired" : pairingResponse)
                    : invalidRequestMethod ? "Method not allowed"
                    : invalidMediaUrl ? "Invalid media URL" : "OK";
            String responseStatus = unauthorized ? "401 Unauthorized"
                    : invalidRequestMethod ? "405 Method Not Allowed"
                    : invalidMediaUrl ? "400 Bad Request" : "200 OK";
            String contentType = pairingRequest && !unauthorized
                    ? "application/json; charset=utf-8" : "text/plain; charset=utf-8";
            if ("status".equals(command)) {
                SharedPreferences state = getSharedPreferences("AboEzzatReceiverState", MODE_PRIVATE);
                JSONObject json = new JSONObject();
                json.put("extracting", state.getBoolean("extracting", false));
                json.put("attempt", state.getInt("attempt", 0));
                json.put("status", state.getString("status", "idle"));
                // This is the confirmed player session, distinct from URL reception
                // or background extraction state. Cast uses it before offering Play/Add/Cancel.
                json.put("mediaActive", state.getBoolean("media_active", false));
                json.put("mediaType", state.getString("media_type", "none"));
                json.put("mediaStateUpdatedAt", state.getLong("media_state_updated_at", 0L));
                body = json.toString();
                contentType = "application/json; charset=utf-8";
            }
            // Response is written only after dispatch/persistence below.
        } catch (Exception e) {
            Log.w(TAG, "Request handling failed", e);
            return;
        }

        if (unauthorized) {
            Log.w(TAG, "Rejected unauthenticated receiver request");
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }
        if (pairingRequest) {
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }
        if (invalidRequestMethod) {
            Log.w(TAG, "Rejected unsupported HTTP method or endpoint");
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }
        if (invalidMediaUrl) {
            Log.w(TAG, "Rejected non-HTTP or malformed media URL");
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }
        if ("ping".equals(command) || "status".equals(command)) {
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }
        if (queueJson == null
                && (mediaUrl == null || mediaUrl.trim().isEmpty())
                && (audioUrl == null || audioUrl.trim().isEmpty())
                && (command == null || command.trim().isEmpty())) {
            writeHttpResponse(socket, responseStatus, contentType, body);
            return;
        }

        if (queueJson != null) {
            getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE).edit()
                    .putString("queue_json", queueJson)
                    .putLong("queue_received_at", System.currentTimeMillis())
                    .commit();
        }
        if ((mediaUrl != null && !mediaUrl.trim().isEmpty())
                || (audioUrl != null && !audioUrl.trim().isEmpty())) {
            // Persist the URL, audio track, and their matching headers atomically.
            // An empty header object intentionally clears credentials from any older request.
            getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE).edit()
                    .putString("url", mediaUrl == null ? "" : mediaUrl)
                    .putString("audio", audioUrl == null ? "" : audioUrl)
                    .putString("source_url", sourceUrl == null ? "" : sourceUrl)
                    .putString("headers", incomingHeaders.toString())
                    .putBoolean("open_browser", openBrowser)
                    .putLong("received_at", System.currentTimeMillis())
                    .commit();
        }

        Intent request = new Intent(this, MainActivity.class);
        request.setAction(ACTION_SERVER_REQUEST);
        request.putExtra(EXTRA_URL, mediaUrl);
        request.putExtra(EXTRA_AUDIO, audioUrl);
        request.putExtra(EXTRA_SOURCE_URL, sourceUrl);
        request.putExtra(EXTRA_COMMAND, command);
        request.putExtra(EXTRA_OPEN_BROWSER, openBrowser);
        if (queueJson != null) request.putExtra(EXTRA_QUEUE_JSON, queueJson);
        request.putExtra("server_headers", incomingHeaders.toString());
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(request);
        } catch (Exception e) {
            Log.e(TAG, "OS blocked opening Receiver for incoming request", e);
            responseStatus = "503 Service Unavailable";
            body = "Receiver could not open the incoming request";
        }
        writeHttpResponse(socket, responseStatus, contentType, body);
    }

    private void writeHttpResponse(Socket socket, String status, String contentType, String body)
            throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] responseHeaders = ("HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Cache-Control: no-store, max-age=0\r\n"
                + "Pragma: no-cache\r\n"
                + "X-Content-Type-Options: nosniff\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(responseHeaders);
        output.write(bodyBytes);
        output.flush();
    }

    // Never let an untrusted LAN client force an unbounded request-line allocation.
    // The endpoint only needs the request line; headers are intentionally not parsed.
    private String readBoundedRequestLine(InputStream input) throws IOException {
        final int maxBytes = 8192;
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream(256);
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') {
                if (line.size() >= maxBytes) {
                    throw new IOException("HTTP request line exceeds " + maxBytes + " bytes");
                }
                line.write(value);
            }
        }
        if (value == -1 && line.size() == 0) return null;
        return new String(line.toByteArray(), StandardCharsets.UTF_8);
    }

    private boolean isHttpUrl(String value) {
        try {
            java.net.URL url = new java.net.URL(value.trim());
            String protocol = url.getProtocol();
            return ("http".equalsIgnoreCase(protocol) || "https".equalsIgnoreCase(protocol))
                    && url.getHost() != null && !url.getHost().trim().isEmpty()
                    && url.getUserInfo() == null;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCastMediaPortUrl(String value) {
        try {
            java.net.URL url = new java.net.URL(value.trim());
            return url.getPort() == 8088;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSupportedCastMediaPath(String path) {
        if (path == null) return false;
        return path.toLowerCase(java.util.Locale.ROOT).matches(
                "/media\\.(jpg|mp4|m4v|mkv|webm|mov|avi|flv|mpg|mpeg|3gp|ogv|wmv|vob|ts|m2ts|mp3|m4a|aac|ogg|oga|opus|wav|flac|mid|midi)");
    }

    private boolean isValidCastTlsMediaUrl(String value) {
        try {
            java.net.URL url = new java.net.URL(value.trim());
            String path = url.getPath();
            String token = url.getQuery();
            String host = url.getHost();
            if (host == null || !host.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
            InetAddress address = InetAddress.getByName(host);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && url.getPort() == 8088
                    && isSupportedCastMediaPath(path)
                    && url.getUserInfo() == null
                    && address instanceof java.net.Inet4Address
                    && address.isSiteLocalAddress() && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress() && !address.isMulticastAddress()
                    && token != null && token.matches("token=[A-Za-z0-9_-]{43}");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        ServerSocket listener = serverSocket;
        if (listener != null) {
            try { listener.close(); } catch (Exception e) { Log.w(TAG, "Server close failed", e); }
        }
        if (serverThread != null) serverThread.interrupt();
        if (heartbeatThread != null) heartbeatThread.interrupt();
        if (dlnaRenderer != null) { dlnaRenderer.stop(); dlnaRenderer = null; }
        if (dlnaMulticastLock != null && dlnaMulticastLock.isHeld()) {
            try { dlnaMulticastLock.release(); } catch (Exception ignored) { }
        }
        dlnaMulticastLock = null;
        requestExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
