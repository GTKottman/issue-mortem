package app.issuemortem;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The Sync button: listens on PORT for a minute while the helper on each of the person's computers offers itself
 * (name, address, key) over Tailscale. The helpers find this device with `tailscale status`, and only offer to
 * devices signed in to their own Tailscale account; here only Tailscale addresses (100.64.0.0/10) are listened to, so other apps on this device can't
 * slip in a computer.
 */
public class Sync {
    static final int PORT = 8642;
    static final long LISTEN_MS = 60_000;

    public interface Listener {
        void found(Prefs.Computer c);

        /** error is null when the minute ran out or stop() was called. */
        void done(String error);
    }

    private volatile ServerSocket server;
    private volatile boolean stopped;

    public void start(Listener l) {
        new Thread(() -> run(l), "sync").start();
    }

    public void stop() {
        stopped = true;
        ServerSocket s = server;
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    private void run(Listener l) {
        long end = System.currentTimeMillis() + LISTEN_MS;
        try (ServerSocket s = new ServerSocket(PORT)) {
            server = s;
            if (stopped) s.close();
            for (long left; (left = end - System.currentTimeMillis()) > 0; ) {
                s.setSoTimeout((int) left);
                try (Socket c = s.accept()) {
                    Prefs.Computer pc = read(c);
                    if (pc != null) l.found(pc);
                }
            }
            l.done(null);
        } catch (SocketTimeoutException e) {
            l.done(null);
        } catch (Exception e) {
            l.done(stopped ? null : "Couldn't listen on port " + PORT + ": " + e.getMessage());
        }
    }

    /** One POST /sync from a helper; anything else gets no answer. */
    private static Prefs.Computer read(Socket c) {
        try {
            if (!tailnet(c.getInetAddress())) return null;
            c.setSoTimeout(3000);
            InputStream in = c.getInputStream();
            String head = head(in);
            if (head == null || !head.startsWith("POST /sync ")) return null;
            int length = 0;
            for (String line : head.split("\r\n")) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:"))
                    length = Integer.parseInt(line.substring(15).trim());
            }
            if (length <= 0 || length > 4096) return null;
            byte[] body = new byte[length];
            for (int got = 0, n; got < length; got += n) {
                if ((n = in.read(body, got, length - got)) < 0) return null;
            }
            JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
            Prefs.Computer pc = new Prefs.Computer(o.optString("name"), o.optString("address"), o.optString("key"));
            if (pc.address.isEmpty() || pc.key.isEmpty()) return null;
            if (pc.name.isEmpty()) pc.name = pc.address;
            OutputStream out = c.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return pc;
        } catch (Exception e) {
            return null;
        }
    }

    /** The request line and headers, up to the blank line; null when it's too long or cut off. */
    private static String head(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int ch, match = 0; (ch = in.read()) >= 0 && b.size() < 8192; ) {
            b.write(ch);
            match = (ch == (match % 2 == 0 ? '\r' : '\n')) ? match + 1 : (ch == '\r' ? 1 : 0);
            if (match == 4) return b.toString("US-ASCII");
        }
        return null;
    }

    /** Tailscale's 100.64.0.0/10. */
    static boolean tailnet(InetAddress a) {
        if (!(a instanceof Inet4Address)) return false;
        byte[] b = a.getAddress();
        return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }
}
