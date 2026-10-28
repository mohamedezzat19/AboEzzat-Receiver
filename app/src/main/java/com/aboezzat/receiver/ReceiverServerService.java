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
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class ReceiverServerService extends Service {
    private static final String TAG = "AboEzzatReceiverService";
    public static final String ACTION_SERVER_REQUEST = "com.aboezzat.receiver.SERVER_REQUEST";
    public static final String EXTRA_URL = "server_url";
    public static final String EXTRA_AUDIO = "server_audio";
    public static final String EXTRA_COMMAND = "server_command";

    private static final int PORT = 8081;
    private static final int DISCOVERY_PORT = 37020;
    private static final String DISCOVERY_GROUP = "239.255.42.99";
    private static final String CHANNEL_ID = "aboezzat_receiver_service";
    private static final int NOTIFICATION_ID = 8082;

    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private Thread serverThread;
    private Thread heartbeatThread;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        startServer();
        startDiscoveryHeartbeat();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // START_STICKY lets Android recreate the listener after reclaiming the process.
        // It cannot override force-stop or device/OEM background restrictions.
        if (!running) {
            startForeground(NOTIFICATION_ID, buildNotification());
            startServer();
            startDiscoveryHeartbeat();
        }
        return START_STICKY;
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
                .setContentText("استقبال الروابط يعمل على المنفذ 8081")
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
                ServerSocket listener = new ServerSocket();
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(PORT));
                serverSocket = listener;
                Log.i(TAG, "Background receiver listening on port " + PORT);
                while (running) {
                    try {
                        Socket client = listener.accept();
                        Thread request = new Thread(() -> handleRequest(client), "ReceiverRequest");
                        request.setDaemon(true);
                        request.start();
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "Accept failed; listener will retry", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Could not start background receiver on port " + PORT, e);
                running = false;
                stopSelf();
            }
        }, "ReceiverServerService");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void startDiscoveryHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) return;
        heartbeatThread = new Thread(() -> {
            while (running) {
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    byte[] data = ("ABOEZZAT_RECEIVER|" + getCurrentIpAddress() + "|" + PORT)
                            .getBytes(StandardCharsets.UTF_8);
                    DatagramPacket packet = new DatagramPacket(
                            data, data.length, InetAddress.getByName(DISCOVERY_GROUP), DISCOVERY_PORT);
                    socket.send(packet);
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
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to resolve local IPv4 address", e);
        }
        return "0.0.0.0";
    }

    private void handleRequest(Socket client) {
        String mediaUrl = null;
        String audioUrl = null;
        String command = null;
        try (Socket socket = client) {
            socket.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine != null) {
                String[] parts = requestLine.split(" ");
                if (parts.length >= 2) {
                    String target = parts[1];
                    int questionIndex = target.indexOf('?');
                    if (questionIndex >= 0) {
                        for (String pair : target.substring(questionIndex + 1).split("&")) {
                            String[] item = pair.split("=", 2);
                            if (item.length != 2) continue;
                            String key = URLDecoder.decode(item[0], "UTF-8");
                            String value = URLDecoder.decode(item[1], "UTF-8");
                            if ("url".equals(key) || "video".equals(key)) mediaUrl = value;
                            else if ("audio".equals(key)) audioUrl = value;
                            else if ("command".equals(key)) command = value;
                        }
                    }
                }
            }

            String body = "OK";
            String contentType = "text/plain; charset=utf-8";
            if ("status".equals(command)) {
                SharedPreferences state = getSharedPreferences("AboEzzatReceiverState", MODE_PRIVATE);
                JSONObject json = new JSONObject();
                json.put("extracting", state.getBoolean("extracting", false));
                json.put("attempt", state.getInt("attempt", 0));
                json.put("status", state.getString("status", "idle"));
                body = json.toString();
                contentType = "application/json; charset=utf-8";
            }
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            byte[] headers = ("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: GET, OPTIONS\r\n"
                    + "Connection: close\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8);
            OutputStream output = socket.getOutputStream();
            output.write(headers);
            output.write(bodyBytes);
            output.flush();
        } catch (Exception e) {
            Log.w(TAG, "Request handling failed", e);
            return;
        }

        if ("ping".equals(command) || "status".equals(command)) return;
        if ((mediaUrl == null || mediaUrl.trim().isEmpty())
                && (audioUrl == null || audioUrl.trim().isEmpty())
                && (command == null || command.trim().isEmpty())) return;

        // Keep the latest incoming media request in case Android blocks starting
        // an Activity from the background. MainActivity consumes and clears it later.
        if ((mediaUrl != null && !mediaUrl.trim().isEmpty())
                || (audioUrl != null && !audioUrl.trim().isEmpty())) {
            getSharedPreferences("AboEzzatReceiverPending", MODE_PRIVATE).edit()
                    .putString("url", mediaUrl == null ? "" : mediaUrl)
                    .putString("audio", audioUrl == null ? "" : audioUrl)
                    .putLong("received_at", System.currentTimeMillis())
                    .apply();
        }

        Intent request = new Intent(this, MainActivity.class);
        request.setAction(ACTION_SERVER_REQUEST);
        request.putExtra(EXTRA_URL, mediaUrl);
        request.putExtra(EXTRA_AUDIO, audioUrl);
        request.putExtra(EXTRA_COMMAND, command);
        request.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(request);
        } catch (Exception e) {
            // Some Android/TV builds can block activity launches from the background.
            // The foreground notification remains available to reopen the receiver.
            Log.e(TAG, "OS blocked opening Receiver for incoming request", e);
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
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
