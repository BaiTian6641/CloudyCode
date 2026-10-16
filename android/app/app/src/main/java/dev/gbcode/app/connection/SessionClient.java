package dev.gbcode.app.connection;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GBCode session-protocol client (contracts/session-v1.md) for the slice:
 * enroll with a one-time secret, obtain a session, open the channel.
 * Ed25519 via the bundled pure-Java implementation (no provider variance).
 */
public final class SessionClient {
    private final String base; // e.g. http://127.0.0.1:9123
    private final Ed25519.KeyPair keyPair;

    public SessionClient(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.keyPair = Ed25519.generate();
    }

    /** Reconnect with a previously enrolled identity. */
    public SessionClient(String base, Ed25519.KeyPair enrolledKeyPair) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.keyPair = enrolledKeyPair;
    }

    /** The enrolled app identity (persist seed via Keystore in production). */
    public Ed25519.KeyPair getKeyPair() {
        return keyPair;
    }

    /** Pair once; returns the enrolled app hash. */
    public String pair(String pairingSecret, String nonce) throws Exception {
        JSONObject resp = call("POST", "/pair", new JSONObject()
                .put("v", 1)
                .put("pairingSecret", pairingSecret)
                .put("appPublicKey", b64u(keyPair.publicKey))
                .put("appLabel", "gbcode-app")
                .put("nonce", nonce), null);
        if (resp.getInt("__status") != 200) {
            throw new IllegalStateException("pair failed: " + resp);
        }
        return resp.getString("enrolledApp");
    }

    /** Get a session token for the enrolled app. */
    public String session(String appHash) throws Exception {
        JSONObject challenge = call("GET", "/session/challenge?app=" + appHash, null, null);
        if (challenge.getInt("__status") != 200) {
            throw new IllegalStateException("challenge failed: " + challenge);
        }
        String ch = challenge.getString("challenge");
        byte[] msg = new byte["session".getBytes(StandardCharsets.UTF_8).length + ch.getBytes(StandardCharsets.UTF_8).length];
        System.arraycopy("session".getBytes(StandardCharsets.UTF_8), 0, msg, 0, "session".getBytes(StandardCharsets.UTF_8).length);
        System.arraycopy(ch.getBytes(StandardCharsets.UTF_8), 0, msg, "session".getBytes(StandardCharsets.UTF_8).length, ch.getBytes(StandardCharsets.UTF_8).length);
        byte[] sig = Ed25519.sign(keyPair.privateKey, keyPair.publicKey, msg);
        JSONObject resp = call("POST", "/session", new JSONObject()
                .put("v", 1)
                .put("challenge", ch)
                .put("appSig", b64u(sig)), appHash);
        if (resp.getInt("__status") != 200) {
            throw new IllegalStateException("session failed: " + resp);
        }
        return resp.getString("session");
    }

    private static String b64u(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private JSONObject call(String method, String path, JSONObject body, String appHeader) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        if (appHeader != null) conn.setRequestProperty("X-GBCode-App", appHeader);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream stream = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        conn.disconnect();
        return new JSONObject(sb.toString()).put("__status", code);
    }


    /** Full flow: pair → session → channel hello over WebSocket. */
    public JSONObject fullFlow(String pairingSecret, String nonce) throws Exception {
        JSONObject out = new JSONObject();
        String appHash = pair(pairingSecret, nonce);
        out.put("enrolledApp", appHash.substring(0, 16) + "…");
        String session = session(appHash);
        out.put("sessionPrefix", session.substring(0, 12) + "…");

        String host = base.substring(base.indexOf("://") + 3);
        int colon = host.lastIndexOf(':');
        String hostname = colon > 0 ? host.substring(0, colon) : host;
        int port = colon > 0 ? Integer.parseInt(host.substring(colon + 1)) : 80;
        try (WsClient ws = WsClient.connect(hostname, port,
                "/channel?session=" + java.net.URLEncoder.encode(session, "UTF-8"), 10_000)) {
            byte[] payload = "{\"v\":1,\"id\":1,\"op\":\"session.hello\",\"payload\":{\"echo\":\"from-apk\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ws.sendText(new String(payload, StandardCharsets.UTF_8));
            WsClient.Frame frame = ws.receive();
            out.put("hello", new JSONObject(new String(frame.payload, StandardCharsets.UTF_8)));
        }
        return out;
    }
}
