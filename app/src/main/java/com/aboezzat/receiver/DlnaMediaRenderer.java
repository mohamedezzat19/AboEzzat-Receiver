package com.aboezzat.receiver;

import android.util.Log;
import android.content.Context;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Small UPnP MediaRenderer endpoint for LAN controllers (e.g. Web Video Caster).
 * Kept separate from the existing AboEzzat HTTP receiver on port 8081.
 */
public final class DlnaMediaRenderer {
    private static final String TAG = "AboEzzatDLNA";
    private static final String GROUP = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int HTTP_PORT = 8200;
    private static final String DEVICE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";

    public interface Listener {
        void onSetUri(String uri);
        void onCommand(String command);
    }

    private final String uuid;
    private final Listener listener;
    private final File diagnosticsFile;
    private volatile boolean running;
    private volatile String currentUri = "";
    private volatile String state = "STOPPED";
    private volatile ServerSocket httpServer;
    private volatile MulticastSocket ssdpSocket;
    private Thread httpThread;
    private Thread ssdpThread;
    private final ExecutorService clients = Executors.newFixedThreadPool(3);
    private final ScheduledExecutorService maintenance = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, EventSubscription> subscriptions = new ConcurrentHashMap<>();
    private static final class EventSubscription {
        final String sid;
        final String service;
        final URL callback;
        volatile long expiresAt;
        volatile int sequence;
        EventSubscription(String sid, String service, URL callback, long expiresAt) {
            this.sid = sid; this.service = service; this.callback = callback; this.expiresAt = expiresAt;
        }
    }

    public DlnaMediaRenderer(Context context, String stableUuid, Listener listener) {
        this.uuid = stableUuid.startsWith("uuid:") ? stableUuid : "uuid:" + stableUuid;
        this.listener = listener;
        this.diagnosticsFile = new File(context.getCacheDir(), "dlna-diagnostics.txt");
        diagnostic("Renderer initialized; HTTP port=" + HTTP_PORT);
    }

    private synchronized void diagnostic(String message) {
        try {
            if (diagnosticsFile.exists() && diagnosticsFile.length() > 128 * 1024L) {
                try (FileOutputStream clear = new FileOutputStream(diagnosticsFile, false)) {
                    clear.write("--- diagnostics rotated ---\n".getBytes(StandardCharsets.UTF_8));
                }
            }
            String line = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                    .format(new Date()) + " " + message + "\n";
            try (FileOutputStream out = new FileOutputStream(diagnosticsFile, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { Log.w(TAG, "Could not write diagnostics", e); }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(HTTP_PORT));
            httpServer = server;
            httpThread = new Thread(() -> acceptHttp(server), "AboEzzat-DLNA-HTTP");
            httpThread.setDaemon(true);
            httpThread.start();
            Log.i(TAG, "DLNA HTTP control listening on " + server.getLocalPort());
        } catch (Exception e) {
            running = false;
            Log.e(TAG, "Unable to bind DLNA HTTP control port", e);
            return;
        }
        startSsdp();
        maintenance.scheduleWithFixedDelay(() -> {
            if (running) {
                announce("ssdp:alive");
                long now = System.currentTimeMillis();
                for (EventSubscription sub : subscriptions.values()) {
                    if (sub.expiresAt <= now) subscriptions.remove(sub.sid);
                }
            }
        }, 15, 15, TimeUnit.MINUTES);
    }

    private void startSsdp() {
        try {
            MulticastSocket socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(SSDP_PORT));
            InetAddress group = InetAddress.getByName(GROUP);
            NetworkInterface ni = findLanInterface();
            if (ni != null) {
                socket.setNetworkInterface(ni);
                socket.joinGroup(new InetSocketAddress(group, SSDP_PORT), ni);
            } else {
                socket.joinGroup(group);
            }
            ssdpSocket = socket;
            ssdpThread = new Thread(() -> listenSsdp(socket), "AboEzzat-DLNA-SSDP");
            ssdpThread.setDaemon(true);
            ssdpThread.start();
            announce("ssdp:alive");
        } catch (Exception e) {
            Log.e(TAG, "Unable to start SSDP listener", e);
        }
    }

    private void listenSsdp(MulticastSocket socket) {
        byte[] buffer = new byte[4096];
        while (running && !socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String request = new String(packet.getData(), packet.getOffset(),
                        packet.getLength(), StandardCharsets.US_ASCII);
                if (!request.toUpperCase(Locale.ROOT).startsWith("M-SEARCH")) continue;
                String st = header(request, "ST");
                if (st == null) continue;
                List<String> targets = new ArrayList<>();
                if ("ssdp:all".equalsIgnoreCase(st)) {
                    targets.add("upnp:rootdevice"); targets.add(DEVICE);
                    targets.add(AVT); targets.add(RCS); targets.add(CM);
                } else if ("upnp:rootdevice".equalsIgnoreCase(st)
                        || DEVICE.equalsIgnoreCase(st) || AVT.equalsIgnoreCase(st)
                        || RCS.equalsIgnoreCase(st) || CM.equalsIgnoreCase(st)) {
                    targets.add(st);
                } else if (uuid.equalsIgnoreCase(st)) {
                    targets.add(uuid);
                }
                for (String target : targets) {
                    String usn = uuid + (target.startsWith("uuid:") ? "" : "::" + target);
                    String response = "HTTP/1.1 200 OK\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "EXT:\r\n"
                            + "LOCATION: http://" + lanIp() + ":" + HTTP_PORT + "/desc.xml\r\n"
                            + "SERVER: Android/1.0 UPnP/1.1 AboEzzatReceiver/1.0\r\n"
                            + "ST: " + target + "\r\nUSN: " + usn + "\r\n\r\n";
                    byte[] bytes = response.getBytes(StandardCharsets.US_ASCII);
                    DatagramPacket reply = new DatagramPacket(bytes, bytes.length,
                            packet.getAddress(), packet.getPort());
                    socket.send(reply);
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "SSDP receive/respond failed", e);
            }
        }
    }

    private void announce(String nts) {
        MulticastSocket socket = ssdpSocket;
        if (socket == null || socket.isClosed()) return;
        try {
            InetAddress group = InetAddress.getByName(GROUP);
            String[] targets = {"upnp:rootdevice", DEVICE, AVT, RCS, CM};
            for (String target : targets) {
                String msg = "NOTIFY * HTTP/1.1\r\nHOST: " + GROUP + ":" + SSDP_PORT
                        + "\r\nCACHE-CONTROL: max-age=1800\r\n"
                        + "LOCATION: http://" + lanIp() + ":" + HTTP_PORT + "/desc.xml\r\n"
                        + "NT: " + target + "\r\nNTS: " + nts
                        + "\r\nSERVER: Android/1.0 UPnP/1.1 AboEzzatReceiver/1.0\r\n"
                        + "USN: " + uuid + "::" + target + "\r\n\r\n";
                byte[] data = msg.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(data, data.length, group, SSDP_PORT));
            }
        } catch (Exception e) { Log.w(TAG, "SSDP announce failed", e); }
    }

    private void acceptHttp(ServerSocket server) {
        while (running && !server.isClosed()) {
            try {
                Socket client = server.accept();
                clients.execute(() -> serve(client));
            } catch (Exception e) {
                if (running) Log.w(TAG, "DLNA accept failed", e);
            }
        }
    }

    private void serve(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(7000);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.ROOT);
            String path = parts[1].split("\\?", 2)[0];
            Map<String,String> headers = new HashMap<>();
            String line;
            int length = 0;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    headers.put(key, value);
                    if ("content-length".equals(key)) {
                        try { length = Integer.parseInt(value); }
                        catch (Exception ignored) { length = -1; }
                    }
                }
            }
            String transferEncoding = headers.get("transfer-encoding");
            byte[] bodyBytes;
            if (transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
                bodyBytes = readChunkedBody(in, 65536);
            } else {
                if (length < 0) {
                    diagnostic("REJECT method=" + method + " path=" + path + " reason=invalid-content-length");
                    respond(client, 400, "text/plain", "Invalid Content-Length");
                    return;
                }
                if (length > 65536) {
                    diagnostic("REJECT method=" + method + " path=" + path + " reason=body-too-large length=" + length);
                    respond(client, 413, "text/plain", "Request body too large");
                    return;
                }
                bodyBytes = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(bodyBytes, read, length - read);
                    if (n < 0) throw new EOFException("Incomplete HTTP request body");
                    read += n;
                }
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            diagnostic("HTTP " + method + " " + path + " SOAPAction=" + safeSoapAction(headers.get("soapaction"))
                    + " contentLength=" + length + " transferEncoding=" + (transferEncoding == null ? "none" : transferEncoding)
                    + " bodyBytes=" + bodyBytes.length);
            if ("GET".equals(method) || "HEAD".equals(method)) {
                if ("/diagnostics.txt".equals(path)) {
                    respond(client, 200, "text/plain; charset=utf-8", "HEAD".equals(method) ? "" : readDiagnostics());
                    return;
                }
                String xml = "/desc.xml".equals(path) || "/".equals(path) ? description()
                        : "/avt.xml".equals(path) ? avtScpd()
                        : "/rcs.xml".equals(path) ? rcsScpd()
                        : "/cm.xml".equals(path) ? cmScpd() : null;
                if (xml == null) respond(client, 404, "text/plain", "");
                else respond(client, 200, "text/xml; charset=utf-8", "HEAD".equals(method) ? "" : xml);
            } else if ("POST".equals(method) && path.startsWith("/ctl/")) {
                handleSoap(client, headers, body);
            } else if ("SUBSCRIBE".equals(method) || "UNSUBSCRIBE".equals(method)) {
                String sid = headers.get("sid");
                String service = path.contains("AVTransport") ? "AVTransport"
                        : path.contains("RenderingControl") ? "RenderingControl" : "ConnectionManager";
                if ("UNSUBSCRIBE".equals(method)) {
                    if (sid != null) subscriptions.remove(sid);
                    respondExtra(client, 200, "text/plain", "", Collections.emptyMap());
                } else if (sid != null) {
                    EventSubscription existing = subscriptions.get(sid);
                    if (existing == null) { respond(client, 412, "text/plain", ""); return; }
                    existing.expiresAt = System.currentTimeMillis() + 1800000L;
                    Map<String,String> renewal = new HashMap<>();
                    renewal.put("SID", sid); renewal.put("TIMEOUT", "Second-1800");
                    respondExtra(client, 200, "text/plain", "", renewal);
                } else {
                    String callbackHeader = headers.get("callback");
                    String nt = headers.get("nt");
                    if (callbackHeader == null || !"upnp:event".equalsIgnoreCase(nt)
                            || !callbackHeader.startsWith("<") || !callbackHeader.endsWith(">")) {
                        respond(client, 412, "text/plain", ""); return;
                    }
                    URL callback = new URL(callbackHeader.substring(1, callbackHeader.length() - 1));
                    if (!"http".equalsIgnoreCase(callback.getProtocol())) {
                        respond(client, 412, "text/plain", ""); return;
                    }
                    String newSid = "uuid:" + UUID.randomUUID();
                    subscriptions.put(newSid, new EventSubscription(newSid, service, callback,
                            System.currentTimeMillis() + 1800000L));
                    Map<String,String> subscription = new HashMap<>();
                    subscription.put("SID", newSid);
                    subscription.put("TIMEOUT", "Second-1800");
                    respondExtra(client, 200, "text/plain", "", subscription);
                    notifySubscription(subscriptions.get(newSid));
                }
            } else {
                respond(client, 405, "text/plain", "");
            }
        } catch (Exception e) {
            diagnostic("HTTP ERROR " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
            Log.w(TAG, "DLNA HTTP request failed", e);
        }
    }

    private void handleSoap(Socket client, Map<String,String> headers, String body) throws Exception {
        String actionHeader = headers.get("soapaction");
        String action = actionHeader == null ? "" : actionHeader.substring(actionHeader.lastIndexOf('#') + 1).replace("\"", "").trim();
        Map<String,String> args = parseArgs(body);
        if (action.isEmpty()) {
            action = findSoapAction(body);
        }
        Log.i(TAG, "SOAP request action=" + action + " args=" + args.keySet());
        diagnostic("SOAP action=" + action + " argumentNames=" + args.keySet());
        String response;
        switch (action) {
            case "SetAVTransportURI":
                String uri = args.get("CurrentURI");
                uri = uri == null ? "" : uri.trim();
                boolean validUri = false;
                try {
                    URL parsed = new URL(uri);
                    validUri = ("http".equalsIgnoreCase(parsed.getProtocol())
                            || "https".equalsIgnoreCase(parsed.getProtocol()))
                            && parsed.getHost() != null && !parsed.getHost().trim().isEmpty();
                } catch (Exception ignored) { }
                if (!validUri) {
                    Log.w(TAG, "SetAVTransportURI rejected: CurrentURI missing or not a valid HTTP(S) URL; args=" + args.keySet());
                    soapFault(client, 402, "Invalid CurrentURI");
                    return;
                }
                currentUri = uri;
                state = "STOPPED";
                listener.onSetUri(uri);
                notifyAvTransportChanged();
                response = soapResponse(action, AVT, "");
                break;
            case "Play":
                state = "PLAYING"; listener.onCommand("play");
                notifyAvTransportChanged();
                response = soapResponse(action, AVT, "");
                break;
            case "Pause":
                state = "PAUSED_PLAYBACK"; listener.onCommand("pause");
                notifyAvTransportChanged();
                response = soapResponse(action, AVT, "");
                break;
            case "Stop":
                state = "STOPPED"; listener.onCommand("stop");
                notifyAvTransportChanged();
                response = soapResponse(action, AVT, "");
                break;
            case "Seek":
                String target = args.get("Target");
                long ms = parseTime(target);
                if (ms >= 0) listener.onCommand("seek:" + ms);
                response = soapResponse(action, AVT, "");
                break;
            case "GetTransportInfo":
                response = soapResponse(action, AVT, "<CurrentTransportState>" + state
                        + "</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>");
                break;
            case "GetPositionInfo":
                response = soapResponse(action, AVT, "<Track>1</Track><TrackDuration>00:00:00</TrackDuration>"
                        + "<TrackMetaData></TrackMetaData><TrackURI>" + xml(currentUri) + "</TrackURI>"
                        + "<RelTime>00:00:00</RelTime><AbsTime>00:00:00</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>");
                break;
            case "GetMediaInfo":
                response = soapResponse(action, AVT, "<NrTracks>" + (currentUri.isEmpty() ? "0" : "1")
                        + "</NrTracks><MediaDuration>00:00:00</MediaDuration><CurrentURI>" + xml(currentUri)
                        + "</CurrentURI><CurrentURIMetaData></CurrentURIMetaData><PlayMedium>NETWORK</PlayMedium>"
                        + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
                break;
            case "GetCurrentConnectionIDs":
                response = soapResponse(action, CM, "<ConnectionIDs>0</ConnectionIDs>");
                break;
            case "GetProtocolInfo":
                response = soapResponse(action, CM, "<Source></Source><Sink>http-get:*:*:*</Sink>");
                break;
            default:
                soapFault(client, 401, "Invalid Action"); return;
        }
        respond(client, 200, "text/xml; charset=utf-8", response);
    }

    private void notifyAvTransportChanged() {
        for (EventSubscription sub : subscriptions.values()) {
            if ("AVTransport".equals(sub.service)) clients.execute(() -> notifySubscription(sub));
        }
    }

    private void notifySubscription(EventSubscription sub) {
        if (sub == null || !running || sub.expiresAt <= System.currentTimeMillis()) return;
        HttpURLConnection connection = null;
        try {
            String property = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                    + "<e:property><LastChange></LastChange></e:property>"
                    + "<e:property><TransportState>" + xml(state) + "</TransportState></e:property>"
                    + "<e:property><AVTransportURI>" + xml(currentUri) + "</AVTransportURI></e:property>"
                    + "</e:propertyset>";
            connection = (HttpURLConnection) sub.callback.openConnection();
            connection.setRequestMethod("NOTIFY");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("NT", "upnp:event");
            connection.setRequestProperty("NTS", "upnp:propchange");
            connection.setRequestProperty("SID", sub.sid);
            connection.setRequestProperty("SEQ", String.valueOf(sub.sequence++));
            byte[] data = property.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) Log.w(TAG, "GENA callback returned HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "GENA notification failed", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String safeSoapAction(String value) {
        if (value == null) return "none";
        int hash = value.lastIndexOf('#');
        String action = hash >= 0 ? value.substring(hash + 1) : value;
        action = action.replace("\"", "").trim();
        return action.length() > 100 ? action.substring(0, 100) : action;
    }

    private String readDiagnostics() {
        try {
            if (!diagnosticsFile.exists()) return "No DLNA diagnostics yet\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = new FileInputStream(diagnosticsFile)) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) { return "Unable to read diagnostics: " + e.getClass().getSimpleName(); }
    }

    private byte[] readChunkedBody(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) throw new EOFException("Missing chunk size");
            int extension = sizeLine.indexOf(';');
            String sizeText = (extension >= 0 ? sizeLine.substring(0, extension) : sizeLine).trim();
            long size;
            try { size = Long.parseLong(sizeText, 16); }
            catch (Exception e) { throw new IOException("Invalid chunk size"); }
            if (size < 0 || size > maxBytes - out.size()) throw new IOException("Chunked body exceeds limit");
            if (size == 0) {
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) { }
                break;
            }
            byte[] chunk = new byte[(int) size];
            int offset = 0;
            while (offset < chunk.length) {
                int n = in.read(chunk, offset, chunk.length - offset);
                if (n < 0) throw new EOFException("Incomplete chunk");
                offset += n;
            }
            out.write(chunk);
            String terminator = readLine(in);
            if (terminator == null || !terminator.isEmpty()) throw new IOException("Invalid chunk terminator");
        }
        return out.toByteArray();
    }

    private String findSoapAction(String body) {
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(body));
            boolean inBody = false;
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("Body".equals(name)) inBody = true;
                    else if (inBody) return name;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to identify SOAP action from body", e);
        }
        return "";
    }

    private Map<String,String> parseArgs(String body) throws Exception {
        Map<String,String> result = new HashMap<>();
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(new StringReader(body));
        int actionDepth = -1;
        int argDepth = -1;
        String key = null;
        StringBuilder value = new StringBuilder();
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.getEventType() == XmlPullParser.START_TAG) {
                String name = p.getName();
                if (actionDepth < 0 && !"Envelope".equals(name) && !"Body".equals(name)) {
                    actionDepth = p.getDepth();
                } else if (actionDepth >= 0 && p.getDepth() == actionDepth + 1) {
                    key = name;
                    argDepth = p.getDepth();
                    value.setLength(0);
                }
            } else if ((p.getEventType() == XmlPullParser.TEXT
                    || p.getEventType() == XmlPullParser.CDSECT
                    || p.getEventType() == XmlPullParser.ENTITY_REF) && key != null) {
                String text = p.getText();
                if (text != null) value.append(text);
            } else if (p.getEventType() == XmlPullParser.END_TAG && key != null && p.getDepth() == argDepth) {
                result.put(key, value.toString());
                key = null;
                argDepth = -1;
            }
        }
        return result;
    }

    private String description() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device><deviceType>" + DEVICE + "</deviceType><friendlyName>AboEzzat Receiver</friendlyName>"
                + "<manufacturer>AboEzzat</manufacturer><modelName>AboEzzat Receiver</modelName><UDN>" + uuid + "</UDN>"
                + "<serviceList>"
                + service(AVT, "AVTransport", "/avt.xml", "/ctl/avt")
                + service(RCS, "RenderingControl", "/rcs.xml", "/ctl/rcs")
                + service(CM, "ConnectionManager", "/cm.xml", "/ctl/cm")
                + "</serviceList></device></root>";
    }
    private String service(String type, String id, String scpd, String ctl) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id
                + "</serviceId><SCPDURL>" + scpd + "</SCPDURL><controlURL>" + ctl
                + "</controlURL><eventSubURL>/evt/" + id + "</eventSubURL></service>";
    }
    private String avtScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"
                + action("SetAVTransportURI","InstanceID:in:A_ARG_TYPE_InstanceID CurrentURI:in:AVTransportURI CurrentURIMetaData:in:AVTransportURIMetaData")
                + action("Play","InstanceID:in:A_ARG_TYPE_InstanceID Speed:in:CurrentPlayMode")+action("Pause","InstanceID:in:A_ARG_TYPE_InstanceID")+action("Stop","InstanceID:in:A_ARG_TYPE_InstanceID")
                + action("Seek","InstanceID:in:A_ARG_TYPE_InstanceID Unit:in:A_ARG_TYPE_SeekMode Target:in:A_ARG_TYPE_SeekTarget")+action("GetTransportInfo","InstanceID:in:A_ARG_TYPE_InstanceID CurrentTransportState:out:TransportState CurrentTransportStatus:out:TransportStatus CurrentSpeed:out:TransportPlaySpeed")
                + action("GetPositionInfo","InstanceID:in:A_ARG_TYPE_InstanceID Track:out:NumberOfTracks TrackDuration:out:CurrentTrackDuration TrackMetaData:out:CurrentTrackMetaData TrackURI:out:CurrentTrackURI RelTime:out:RelativeTimePosition AbsTime:out:AbsoluteTimePosition RelCount:out:RelativeCounterPosition AbsCount:out:AbsoluteCounterPosition")+action("GetMediaInfo","InstanceID:in:A_ARG_TYPE_InstanceID NrTracks:out:NumberOfTracks MediaDuration:out:CurrentMediaDuration CurrentURI:out:AVTransportURI CurrentURIMetaData:out:AVTransportURIMetaData PlayMedium:out:PlaybackStorageMedium RecordMedium:out:RecordStorageMedium WriteStatus:out:RecordMediumWriteStatus")
                + "</actionList><serviceStateTable><stateVariable sendEvents=\"yes\"><name>TransportState</name><dataType>string</dataType></stateVariable></serviceStateTable></scpd>";
    }
    private String action(String name, String args) {
        StringBuilder b = new StringBuilder("<action><name>").append(name).append("</name>");
        if (args != null && !args.trim().isEmpty()) {
            b.append("<argumentList>");
            for (String spec : args.split(" ")) {
                String[] parts = spec.split(":", 3);
                if (parts.length != 3) continue;
                b.append("<argument><name>").append(parts[0])
                        .append("</name><direction>").append(parts[1])
                        .append("</direction><relatedStateVariable>").append(parts[2])
                        .append("</relatedStateVariable></argument>");
            }
            b.append("</argumentList>");
        }
        return b.append("</action>").toString();
    }
    private String rcsScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList></actionList><serviceStateTable></serviceStateTable></scpd>";
    }
    private String cmScpd() {
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"
                + action("GetProtocolInfo","Source:out:SourceProtocolInfo Sink:out:SinkProtocolInfo") + action("GetCurrentConnectionIDs","ConnectionIDs:out:ConnectionIDs")
                + "</actionList><serviceStateTable></serviceStateTable></scpd>";
    }
    private String soapResponse(String action, String service, String inner) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"
                + action + "Response xmlns:u=\"" + service + "\">" + inner
                + "</u:" + action + "Response></s:Body></s:Envelope>";
    }
    private void soapFault(Socket c, int code, String desc) throws Exception {
        Log.w(TAG, "Returning UPnP SOAP fault " + code + ": " + desc);
        diagnostic("SOAP FAULT code=" + code + " description=" + desc);
        String b = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>"
                + code + "</errorCode><errorDescription>" + desc + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
        respond(c, 500, "text/xml; charset=utf-8", b);
    }
    private void respond(Socket c, int status, String type, String body) throws Exception {
        respondExtra(c, status, type, body, Collections.emptyMap());
    }
    private void respondExtra(Socket c, int status, String type, String body, Map<String,String> extra) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed" : "Internal Server Error";
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(" ").append(reason)
                .append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(data.length)
                .append("\r\nConnection: close\r\n");
        for (Map.Entry<String,String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        out.write(h.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
        out.write(data); out.flush();
    }
    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') { if (c != '\r') { if (b.size() > 8192) throw new IOException("line too long"); b.write(c); } }
        return c == -1 && b.size() == 0 ? null : b.toString("UTF-8");
    }
    private String header(String request, String name) {
        for (String line : request.split("\\r?\\n")) {
            int i = line.indexOf(':');
            if (i > 0 && name.equalsIgnoreCase(line.substring(0, i).trim())) return line.substring(i + 1).trim();
        }
        return null;
    }
    private String lanIp() {
        String fallback = null;
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.contains("tun") || n.contains("tap") || n.contains("vpn")
                        || n.contains("ppp") || n.contains("wg")) continue;
                Enumeration<InetAddress> ips = ni.getInetAddresses();
                while (ips.hasMoreElements()) {
                    InetAddress ip = ips.nextElement();
                    if (!(ip instanceof Inet4Address) || !ip.isSiteLocalAddress()
                            || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) continue;
                    if (n.contains("wlan") || n.contains("wifi") || n.contains("eth")) {
                        return ip.getHostAddress();
                    }
                    if (fallback == null) fallback = ip.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return fallback == null ? "0.0.0.0" : fallback;
    }
    private NetworkInterface findLanInterface() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.ROOT);
                if (n.contains("wlan") || n.contains("wifi") || n.contains("eth")) return ni;
            }
        } catch (Exception ignored) {}
        return null;
    }
    private long parseTime(String value) {
        if (value == null) return -1;
        try {
            String[] p = value.split(":");
            if (p.length != 3) return -1;
            return (Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60 + Long.parseLong(p[2])) * 1000L;
        } catch (Exception e) { return -1; }
    }
    private String xml(String value) {
        return value == null ? "" : value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");
    }
    public synchronized void stop() {
        if (!running) return;
        running = false;
        announce("ssdp:byebye");
        try { if (ssdpSocket != null) ssdpSocket.close(); } catch (Exception ignored) {}
        try { if (httpServer != null) httpServer.close(); } catch (Exception ignored) {}
        if (ssdpThread != null) ssdpThread.interrupt();
        if (httpThread != null) httpThread.interrupt();
        subscriptions.clear();
        maintenance.shutdownNow();
        clients.shutdownNow();
    }
}
