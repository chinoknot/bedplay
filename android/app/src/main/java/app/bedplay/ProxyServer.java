package app.bedplay;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local server on 127.0.0.1: serves the player page and forwards /v5 /v6 /v2 /v1 calls to the Xbox streaming API,
 * adding the token. This way the player inside the WebView is same-origin and no CORS is needed.
 */
public class ProxyServer {
    public static final int PORT = 18080;

    private final Context ctx;
    private final Auth auth;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean cloud;
    private volatile JSONObject pendingLogin;

    /**
     * Random per-launch key. Only the app's own WebView knows it (it is passed in the first URL and then kept as a
     * cookie), so other apps on the device cannot use this local server to act on the signed-in Xbox account.
     */
    public final String key;

    public ProxyServer(Context c, Auth a) {
        ctx = c.getApplicationContext();
        auth = a;
        byte[] k = new byte[16];
        new java.security.SecureRandom().nextBytes(k);
        StringBuilder sb = new StringBuilder();
        for (byte x : k) sb.append(String.format("%02x", x));
        key = sb.toString();
    }

    public boolean running() {
        return server != null && !server.isClosed();
    }

    public void start() throws Exception {
        server = new ServerSocket(PORT, 32, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    final Socket s = server.accept();
                    pool.execute(() -> handle(s));
                } catch (Exception e) {
                    break;
                }
            }
        }, "xbs-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        try {
            server.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    // ---- HTTP request ---------------------------------------------------

    private static class Req {
        String method, path, query;
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        byte[] body = new byte[0];
    }

    private Req read(InputStream in) throws Exception {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0, b;
        while (state < 4 && (b = in.read()) >= 0) {
            head.write(b);
            if ((b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3))) state++;
            else state = (b == '\r') ? 1 : 0;
            if (head.size() > 65536) throw new Exception("header too long");
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        String[] first = lines[0].split(" ");
        Req r = new Req();
        r.method = first[0];
        String target = first.length > 1 ? first[1] : "/";
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        r.query = q < 0 ? "" : target.substring(q + 1);
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c > 0) r.headers.put(lines[i].substring(0, c).trim().toLowerCase(), lines[i].substring(c + 1).trim());
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.headers.getOrDefault("content-length", "0"));
        } catch (NumberFormatException ignored) {
        }
        if (len > 0) {
            r.body = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(r.body, off, len - off);
                if (n < 0) break;
                off += n;
            }
        }
        return r;
    }

    private void reply(OutputStream o, int code, String type, byte[] body) throws Exception {
        reply(o, code, type, body, null);
    }

    private void reply(OutputStream o, int code, String type, byte[] body, String setCookie) throws Exception {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 204 ? "No Content" : "Status") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + (setCookie != null ? "Set-Cookie: " + setCookie + "\r\n" : "")
                + "Content-Length: " + (code == 204 ? 0 : body.length) + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        o.write(head.getBytes("ISO-8859-1"));
        if (code != 204) o.write(body);
        o.flush();
    }

    private void json(OutputStream o, int code, JSONObject j) throws Exception {
        reply(o, code, "application/json; charset=utf-8", j.toString().getBytes("UTF-8"));
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            Req r = read(s.getInputStream());
            OutputStream o = s.getOutputStream();
            String p = r.path;
            boolean first = p.equals("/") && r.query.equals("k=" + key);
            if (!first && !r.headers.getOrDefault("cookie", "").contains("bp=" + key)) {
                reply(o, 403, "text/plain", "forbidden".getBytes());
                return;
            }
            if (first) {
                asset("/index.html", o, "bp=" + key + "; Path=/; HttpOnly; SameSite=Strict");
            } else if (p.startsWith("/api/")) {
                api(r, o);
            } else if (p.startsWith("/v5/") || p.startsWith("/v6/") || p.startsWith("/v2/") || p.startsWith("/v1/")) {
                proxy(r, o);
            } else {
                asset(p, o, null);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- static files -----------------------------------------------------

    private void asset(String path, OutputStream o, String setCookie) throws Exception {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || !path.matches("/[A-Za-z0-9_./-]+")) {
            reply(o, 404, "text/plain", "not found".getBytes());
            return;
        }
        try {
            AssetManager am = ctx.getAssets();
            InputStream in = am.open(path.substring(1));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            String type = path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8" : "application/octet-stream";
            reply(o, 200, type, b.toByteArray(), setCookie);
        } catch (java.io.FileNotFoundException e) {
            reply(o, 404, "text/plain", "not found".getBytes());
        }
    }

    // ---- app API -----------------------------------------------------

    private void api(Req r, OutputStream o) throws Exception {
        try {
            switch (r.path) {
                case "/api/status":
                    json(o, 200, new JSONObject().put("loggedIn", auth.loggedIn()));
                    return;
                case "/api/login/start":
                    pendingLogin = auth.startDeviceCode();
                    json(o, 200, new JSONObject()
                            .put("user_code", pendingLogin.optString("user_code"))
                            .put("verification_uri", pendingLogin.optString("verification_uri"))
                            .put("interval", pendingLogin.optInt("interval", 5)));
                    return;
                case "/api/login/poll": {
                    JSONObject pl = pendingLogin;
                    if (pl == null) {
                        json(o, 400, new JSONObject().put("error", "no sign-in in progress"));
                        return;
                    }
                    boolean ok = auth.pollDeviceCode(pl.getString("device_code"));
                    if (ok) pendingLogin = null;
                    json(o, 200, new JSONObject().put("ok", ok));
                    return;
                }
                case "/api/logout":
                    auth.logout();
                    json(o, 200, new JSONObject().put("ok", true));
                    return;
                case "/api/mode": {
                    cloud = r.query.contains("cloud");
                    json(o, 200, new JSONObject().put("cloud", cloud));
                    return;
                }
                case "/api/lpt":
                    json(o, 200, new JSONObject().put("lpt", auth.lpt()));
                    return;
                default:
                    reply(o, 404, "text/plain", "not found".getBytes());
            }
        } catch (Auth.HttpError e) {
            json(o, 502, new JSONObject().put("error", e.getMessage()));
        } catch (Exception e) {
            json(o, 500, new JSONObject().put("error", String.valueOf(e.getMessage())));
        }
    }

    // ---- forwarding to the Xbox API --------------------------------------------

    private void proxy(Req r, OutputStream o) throws Exception {
        Auth.Stream st;
        try {
            st = auth.stream(cloud);
        } catch (Exception e) {
            json(o, 401, new JSONObject().put("error", "token: " + e.getMessage()));
            return;
        }
        String path = r.path;
        if (cloud) path = path.replace("/sessions/home/", "/sessions/cloud/");
        String url = st.base + path + (r.query.isEmpty() ? "" : "?" + r.query);
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(r.method);
        h.setConnectTimeout(15000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + st.token);
        h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        h.setRequestProperty("Accept", "application/json");
        String di = r.headers.get("x-ms-device-info");
        if (di != null) h.setRequestProperty("X-MS-Device-Info", di);
        if (r.body.length > 0) {
            h.setDoOutput(true);
            try (OutputStream bo = h.getOutputStream()) {
                bo.write(r.body);
            }
        } else if (r.method.equals("POST")) {
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(0);
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        byte[] data = new byte[0];
        if (in != null) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            in.close();
            data = b.toByteArray();
        }
        String type = h.getContentType();
        h.disconnect();
        reply(o, code, type == null ? "application/json" : type, data);
    }
}
