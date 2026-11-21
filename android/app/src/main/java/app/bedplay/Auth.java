package app.bedplay;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Microsoft sign-in (device code flow) and the tokens for Xbox streaming (xhome / xgpuweb). */
public class Auth {
    private static final String CLIENT_ID = "1f907974-e22b-4810-a9de-d9647380c97e";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    /** Non-2xx HTTP response. */
    public static class HttpError extends Exception {
        public final int status;
        public final String body;

        HttpError(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
            this.body = body;
        }
    }

    public static class Stream {
        public String base;   // https://uks.core.gssv-play-prodxhome.xboxlive.com
        public String token;  // gsToken
        public long expiresAt;
    }

    private final SharedPreferences prefs;
    private Stream home, cloud;
    private String xstsGssv;
    private long xstsGssvExp;
    private String xblUserToken;
    private long xblUserExp;

    public Auth(Context c) {
        prefs = c.getSharedPreferences("auth", Context.MODE_PRIVATE);
    }

    public boolean loggedIn() {
        return prefs.getString("refresh", null) != null;
    }

    public void logout() {
        prefs.edit().clear().apply();
        home = cloud = null;
        xstsGssv = null;
        xblUserToken = null;
    }

    // ---- HTTP -----------------------------------------------------------

    static String request(String method, String url, String contentType, String body, String... headers) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", UA);
        if (contentType != null) h.setRequestProperty("Content-Type", contentType);
        for (int i = 0; i + 1 < headers.length; i += 2) h.setRequestProperty(headers[i], headers[i + 1]);
        if (body != null) {
            h.setDoOutput(true);
            try (OutputStream o = h.getOutputStream()) {
                o.write(body.getBytes("UTF-8"));
            }
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String text = in == null ? "" : readAll(in);
        h.disconnect();
        if (code < 200 || code > 299) throw new HttpError(code, text);
        return text;
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static String form(String... kv) throws Exception {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (s.length() > 0) s.append('&');
            s.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return s.toString();
    }

    // ---- device-code sign-in -------------------------------------------------

    /** Starts sign-in: returns user_code, verification_uri, device_code, interval. */
    public JSONObject startDeviceCode() throws Exception {
        String r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
                "application/x-www-form-urlencoded",
                form("client_id", CLIENT_ID, "scope", "xboxlive.signin openid profile offline_access"));
        return new JSONObject(r);
    }

    /** true once the user has completed sign-in, false while it is still pending. */
    public boolean pollDeviceCode(String deviceCode) throws Exception {
        try {
            String r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                    "application/x-www-form-urlencoded",
                    form("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", CLIENT_ID, "device_code", deviceCode));
            saveMsToken(new JSONObject(r));
            return true;
        } catch (HttpError e) {
            if (e.status == 400 && e.body.contains("authorization_pending")) return false;
            if (e.status == 400 && e.body.contains("slow_down")) return false;
            throw e;
        }
    }

    private void saveMsToken(JSONObject j) {
        long exp = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000;
        prefs.edit().putString("access", j.optString("access_token"))
                .putString("refresh", j.optString("refresh_token"))
                .putLong("access_exp", exp).apply();
        home = cloud = null;
        xstsGssv = null;
        xblUserToken = null;
    }

    private synchronized String msAccessToken() throws Exception {
        String refresh = prefs.getString("refresh", null);
        if (refresh == null) throw new IllegalStateException("not signed in");
        long exp = prefs.getLong("access_exp", 0);
        String access = prefs.getString("access", null);
        if (access != null && exp - System.currentTimeMillis() > 60000) return access;
        String r = request("POST", "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
                "application/x-www-form-urlencoded",
                form("client_id", CLIENT_ID, "grant_type", "refresh_token", "refresh_token", refresh,
                        "scope", "xboxlive.signin openid profile offline_access"));
        JSONObject j = new JSONObject(r);
        saveMsToken(j);
        return j.getString("access_token");
    }

    // ---- Xbox tokens -------------------------------------------------------

    private synchronized String xblUserToken() throws Exception {
        if (xblUserToken != null && xblUserExp - System.currentTimeMillis() > 60000) return xblUserToken;
        JSONObject p = new JSONObject()
                .put("Properties", new JSONObject().put("AuthMethod", "RPS")
                        .put("RpsTicket", "d=" + msAccessToken()).put("SiteName", "user.auth.xboxlive.com"))
                .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT");
        String r = request("POST", "https://user.auth.xboxlive.com/user/authenticate", "application/json", p.toString(),
                "x-xbl-contract-version", "1", "Origin", "https://www.xbox.com", "Referer", "https://www.xbox.com/");
        JSONObject j = new JSONObject(r);
        xblUserToken = j.getString("Token");
        xblUserExp = parseIso(j.optString("NotAfter"));
        return xblUserToken;
    }

    private synchronized String xstsGssv() throws Exception {
        if (xstsGssv != null && xstsGssvExp - System.currentTimeMillis() > 60000) return xstsGssv;
        JSONObject p = new JSONObject()
                .put("Properties", new JSONObject().put("SandboxId", "RETAIL")
                        .put("UserTokens", new JSONArray().put(xblUserToken())))
                .put("RelyingParty", "http://gssv.xboxlive.com/").put("TokenType", "JWT");
        String r = request("POST", "https://xsts.auth.xboxlive.com/xsts/authorize", "application/json", p.toString(),
                "x-xbl-contract-version", "1", "Origin", "https://www.xbox.com", "Referer", "https://www.xbox.com/");
        JSONObject j = new JSONObject(r);
        xstsGssv = j.getString("Token");
        xstsGssvExp = parseIso(j.optString("NotAfter"));
        return xstsGssv;
    }

    private Stream loginStream(String offering) throws Exception {
        JSONObject p = new JSONObject().put("token", xstsGssv()).put("offeringId", offering);
        String r = request("POST", "https://" + offering + ".gssv-play-prod.xboxlive.com/v2/login/user", "application/json",
                p.toString(), "x-gssv-client", "XboxComBrowser", "Cache-Control", "no-store, must-revalidate, no-cache");
        JSONObject j = new JSONObject(r);
        JSONArray regions = j.getJSONObject("offeringSettings").getJSONArray("regions");
        String base = null;
        for (int i = 0; i < regions.length(); i++) {
            JSONObject g = regions.getJSONObject(i);
            if (g.optBoolean("isDefault")) base = g.getString("baseUri");
        }
        if (base == null) base = regions.getJSONObject(0).getString("baseUri");
        Stream s = new Stream();
        s.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        s.token = j.getString("gsToken");
        s.expiresAt = System.currentTimeMillis() + j.optLong("durationInSeconds", 3600) * 1000;
        return s;
    }

    /** Token and host for streaming from the console (xhome) or from the cloud. */
    public synchronized Stream stream(boolean cloudMode) throws Exception {
        Stream s = cloudMode ? cloud : home;
        if (s != null && s.expiresAt - System.currentTimeMillis() > 60000) return s;
        if (!cloudMode) {
            home = loginStream("xhome");
            return home;
        }
        try {
            cloud = loginStream("xgpuweb");
        } catch (Exception e) {
            cloud = loginStream("xgpuwebf2p");
        }
        return cloud;
    }

    /** "lpt" token the cloud needs to attach the session (ReadyToConnect state). */
    public String lpt() throws Exception {
        String refresh = prefs.getString("refresh", null);
        if (refresh == null) throw new IllegalStateException("not signed in");
        String r = request("POST", "https://login.live.com/oauth20_token.srf", "application/x-www-form-urlencoded",
                form("client_id", CLIENT_ID,
                        "scope", "service::http://Passport.NET/purpose::PURPOSE_XBOX_CLOUD_CONSOLE_TRANSFER_TOKEN",
                        "grant_type", "refresh_token", "refresh_token", refresh));
        JSONObject j = new JSONObject(r);
        if (j.has("refresh_token")) prefs.edit().putString("refresh", j.getString("refresh_token")).apply();
        return j.getString("access_token");
    }

    private static long parseIso(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(s.length() >= 19 ? s.substring(0, 19) : s).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis() + 3600 * 1000L;
        }
    }
}
