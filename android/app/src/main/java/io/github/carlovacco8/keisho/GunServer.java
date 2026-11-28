package io.github.carlovacco8.keisho;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import java.net.ServerSocket;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * The phone light gun: a small HTTPS server on the local network. A phone
 * opens its page (assets/gun/gun.html) in the browser; the page reads the
 * phone's motion sensors and sends the aim and the trigger back, over a
 * WebSocket or, when the browser refuses one, over plain HTTPS POSTs.
 *
 * HTTPS because iOS only gives a page the motion sensors on a secure
 * connection. The certificate is a self-signed one shipped in the APK, so the
 * browser warns once; it protects nothing beyond that.
 *
 * Each message is 9 bytes: x, y (float32, little endian, fractions of the
 * game picture; outside 0..1 is off screen) and a button byte
 * (1 trigger, 2 reload).
 */
final class GunServer {
    private static final String TAG  = "M2Gun";
    static final int            PORT = 8443;

    interface Sink {
        void aim(float x, float y, int buttons);
        void connected(boolean on);
        /** What the server last did, shown under the address on the Shield. */
        void status(String text);
    }

    static final int PLAIN_PORT = 8080;

    private final Context context;
    private final Sink    sink;
    private ServerSocket     server;
    private ServerSocket     plain;
    private SSLSocketFactory tls;
    private volatile int     requests;
    private volatile boolean running;
    private volatile int     clients;
    private byte[] page;

    GunServer(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink    = sink;
    }

    /** The address to type on the phone, or null without a local network. */
    static String url() {
        String ip = localAddress();
        return ip == null ? null : "https://" + ip + ":" + PORT;
    }

    private static String localAddress() {
        try {
            String fallback = null;
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String name = nif.getName();
                    if (name.startsWith("wlan") || name.startsWith("eth")) return a.getHostAddress();
                    if (fallback == null) fallback = a.getHostAddress();
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::serve, "M2Gun-accept");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
            if (plain != null) plain.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        try {
            page   = readAsset("gun/gun.html");
            tls    = sslContext().getSocketFactory();
            server = new ServerSocket(PORT);
        } catch (Exception e) {
            Log.w(TAG, "server not started", e);
            sink.status("Server error: " + e.getMessage());
            return;
        }
        Log.i(TAG, "listening on " + url());
        sink.status("Waiting for the phone");
        Thread p = new Thread(this::servePlain, "M2Gun-plain");
        p.setDaemon(true);
        p.start();
        // Plain accept; the TLS handshake runs on each connection's own thread,
        // so a browser that opens a connection and stays silent (Safari does,
        // to be ready) never holds up the next one.
        try {
            while (running) {
                Socket raw = server.accept();
                Thread t = new Thread(() -> handleTls(raw), "M2Gun-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "server stopped", e);
        }
    }

    private void handleTls(Socket raw) {
        try {
            raw.setSoTimeout(15000);
            SSLSocket s = (SSLSocket) tls.createSocket(raw, raw.getInetAddress().getHostAddress(), raw.getPort(), true);
            s.setUseClientMode(false);
            s.startHandshake();
            handle(s);
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            // Before the certificate is accepted the browser drops every
            // handshake: that is expected, say so plainly.
            sink.status(requests == 0 ? "Phone reached the Shield: accept the certificate warning"
                                      : "Connection error: " + m);
            try {
                raw.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * http://address:8080 — a check that the phone reaches the Shield at all,
     * and a link on to the secure page.
     */
    private void servePlain() {
        try {
            plain = new ServerSocket(PLAIN_PORT);
            while (running) {
                Socket s = plain.accept();
                Thread t = new Thread(() -> {
                    try (Socket sock = s) {
                        sock.setSoTimeout(10000);
                        InputStream in = sock.getInputStream();
                        for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                            // headers ignored
                        }
                        String to = url();
                        String html = "<!DOCTYPE html><meta charset=utf-8><meta name=viewport content='width=device-width'>"
                                + "<body style='background:#111317;color:#fff;font:18px -apple-system,sans-serif;padding:24px'>"
                                + "<h2 style='color:#e8a33a'>M2 NEXUS raggiunto</h2>"
                                + "<p>Il telefono vede la Shield. Ora apri la pistola:</p>"
                                + "<p><a style='color:#e8a33a;font-size:22px' href='" + to + "'>" + to + "</a></p>";
                        sink.status("Phone reached the Shield (test page)");
                        reply(sock.getOutputStream(), "200 OK", "text/html; charset=utf-8",
                              html.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException ignored) {
                    }
                }, "M2Gun-plain-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) Log.w(TAG, "plain server stopped", e);
        }
    }

    private SSLContext sslContext() throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(readAsset("gun/key.der")));
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(context.getAssets().open("gun/cert.der"));
        char[] pass = "m2nexus".toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setKeyEntry("gun", key, pass, new Certificate[] {cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    private byte[] readAsset(String name) throws IOException {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    // -- one connection: HTTP/1.1 keep-alive, possibly upgraded to a WebSocket --

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true);
            sock.setSoTimeout(60000);
            InputStream  in  = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            while (running) {
                String request = readLine(in);
                if (request == null || request.isEmpty()) return;
                Map<String, String> headers = new HashMap<>();
                for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                    int c = line.indexOf(':');
                    if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                }
                String[] parts = request.split(" ");
                String method = parts[0];
                String path   = parts.length > 1 ? parts[1] : "/";
                int    length = 0;
                try {
                    length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
                } catch (NumberFormatException ignored) {
                }
                byte[] body = readFully(in, Math.min(length, 4096));

                if ("websocket".equalsIgnoreCase(headers.get("upgrade")) && headers.containsKey("sec-websocket-key")) {
                    upgrade(in, out, headers.get("sec-websocket-key"));
                    return;
                }
                if ("POST".equals(method) && path.startsWith("/aim")) {
                    if (requests++ == 0 || (requests & 63) == 0) sink.status("Phone connected");
                    if (body.length >= 9) apply(body, 0);
                    reply(out, "204 No Content", null, null);
                    continue;
                }
                if (path.equals("/") || path.startsWith("/?") || path.startsWith("/index")) {
                    requests++;
                    reply(out, "200 OK", "text/html; charset=utf-8", page);
                    sink.status("Page sent to the phone");
                    continue;
                }
                reply(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // the phone went away
        }
    }

    private static void reply(OutputStream out, String status, String type, byte[] body) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: keep-alive\r\n")
                .append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) out.write(body);
        out.flush();
    }

    private void upgrade(InputStream in, OutputStream out, String key) throws IOException {
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            accept = Base64.encodeToString(d, Base64.NO_WRAP);
        } catch (Exception e) {
            return;
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                   + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        clientUp();
        sink.status("Phone connected");
        try {
            while (running) {
                int b0 = in.read();
                int b1 = in.read();
                if (b0 < 0 || b1 < 0) return;
                int  opcode = b0 & 0x0f;
                long len    = b1 & 0x7f;
                if (len == 126) len = (in.read() << 8) | in.read();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                if (len > 65536) return;
                byte[] mask = (b1 & 0x80) != 0 ? readFully(in, 4) : null;
                byte[] data = readFully(in, (int) len);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (opcode == 0x8) return;                 // close
                if (opcode == 0x9) {                        // ping -> pong
                    out.write(new byte[] {(byte) 0x8a, (byte) data.length});
                    out.write(data);
                    out.flush();
                    continue;
                }
                if (opcode == 0x2 && data.length >= 9) apply(data, 0);
            }
        } finally {
            clientDown();
        }
    }


    private void apply(byte[] d, int at) {
        ByteBuffer bb = ByteBuffer.wrap(d, at, 9).order(ByteOrder.LITTLE_ENDIAN);
        float x = bb.getFloat();
        float y = bb.getFloat();
        int   b = bb.get() & 0xff;
        if (Float.isNaN(x) || Float.isNaN(y)) return;
        sink.aim(x, y, b);
    }

    private synchronized void clientUp() {
        if (clients++ == 0) sink.connected(true);
    }

    private synchronized void clientDown() {
        if (--clients == 0) sink.connected(false);
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("line too long");
        }
        if (c < 0 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("eof");
            off += r;
        }
        return b;
    }
}
