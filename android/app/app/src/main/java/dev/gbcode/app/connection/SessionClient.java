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

    /**
     * Pair once and verify the signed enrollment receipt before trusting it.
     * Returns the full enrolled app hash (contract: hex SHA-256 of the app key),
     * never a display truncation.
     */
    public PairResult pair(String pairingSecret, String nonce) throws Exception {
        JSONObject resp = call("POST", "/pair", new JSONObject()
                .put("v", 1)
                .put("pairingSecret", pairingSecret)
                .put("appPublicKey", b64u(keyPair.publicKey))
                .put("appLabel", "gbcode-apk")
                .put("nonce", nonce), null);
        if (resp.getInt("__status") != 200) {
            throw new IllegalStateException("pair failed: " + resp);
        }
        String appHash = resp.getString("enrolledApp");
        String serverPublicKey = resp.getString("serverPublicKey");
        String fingerprint = resp.getString("serverFingerprint");
        byte[] receiptSig = b64uDecode(resp.getString("receiptSig"));

        return verifyReceipt(keyPair.publicKey, nonce, appHash, serverPublicKey,
                fingerprint, receiptSig);
    }

    /**
     * Verify a signed enrollment receipt (contracts/session-v1.md §2):
     * ed25519(serverKey, "enroll" || appHash || nonce), appHash = SHA-256(app
     * key), fingerprint = SHA-256(server key). Exposed for golden-vector tests.
     */
    static PairResult verifyReceipt(byte[] appPublicKey, String nonce,
                                    String appHash, String serverPublicKey,
                                    String fingerprint, byte[] receiptSig) {
        // The enrolled hash must be our app identity: SHA-256(raw app public key).
        byte[] expectedHash = sha256(appPublicKey);
        if (!hex(expectedHash).equals(appHash)) {
            throw new SecurityException("enrollment receipt binds a different app key");
        }
        // The reported fingerprint must match the server public key it signs with.
        byte[] serverRaw = b64uDecode(serverPublicKey);
        if (serverRaw.length != 32) {
            throw new SecurityException("server public key length");
        }
        if (!hex(sha256(serverRaw)).equals(fingerprint)) {
            throw new SecurityException("server fingerprint does not match serverPublicKey");
        }
        // Signed receipt: ed25519(serverKey, "enroll" || appHash || nonce).
        byte[] message = concat("enroll".getBytes(StandardCharsets.UTF_8),
                expectedHash, nonce.getBytes(StandardCharsets.UTF_8));
        if (!Ed25519.verify(serverRaw, receiptSig, message)) {
            throw new SecurityException("enrollment receipt signature invalid");
        }
        return new PairResult(appHash, serverPublicKey, fingerprint, nonce);
    }

    /** Verified enrollment result; the receipt fields are covered by receiptSig. */
    public static final class PairResult {
        public final String appHash;
        public final String serverPublicKey;
        public final String serverFingerprint;
        public final String nonce;

        PairResult(String appHash, String serverPublicKey, String serverFingerprint, String nonce) {
            this.appHash = appHash;
            this.serverPublicKey = serverPublicKey;
            this.serverFingerprint = serverFingerprint;
            this.nonce = nonce;
        }
    }

    private static byte[] b64uDecode(String data) {
        return java.util.Base64.getUrlDecoder().decode(data);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    /**
     * Get a session token for the enrolled app and authenticate the daemon by
     * verifying serverProof against the key pinned at enrollment (contract §3).
     */
    public String session(String appHash, String serverPublicKey) throws Exception {
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
        String token = resp.getString("session");
        verifyServerProof(serverPublicKey, appHash, ch, resp.optString("serverProof", ""));
        return token;
    }

    /**
     * Verify the per-session daemon proof:
     * ed25519(serverKey, "serverprove" || appHash || challenge). Exposed for
     * golden-vector tests; called by session() before the token may be used.
     */
    static void verifyServerProof(String serverPublicKey, String appHash,
                                  String challenge, String serverProof) {
        if (serverProof.isEmpty()) {
            throw new SecurityException("session response missing serverProof");
        }
        byte[] serverRaw = b64uDecode(serverPublicKey);
        if (serverRaw.length != 32) {
            throw new SecurityException("server public key length");
        }
        byte[] message = concat("serverprove".getBytes(StandardCharsets.UTF_8),
                hexToBytes(appHash), challenge.getBytes(StandardCharsets.UTF_8));
        if (!Ed25519.verify(serverRaw, b64uDecode(serverProof), message)) {
            throw new SecurityException("server proof invalid");
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new SecurityException("app hash encoding");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
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
        PairResult paired = pair(pairingSecret, nonce);
        // Full hash is the identity used for /session/challenge; display code
        // may truncate, persistence must not.
        out.put("enrolledApp", paired.appHash);
        out.put("serverPublicKey", paired.serverPublicKey);
        out.put("serverFingerprint", paired.serverFingerprint);
        out.put("receiptVerified", true);
        String session = session(paired.appHash, paired.serverPublicKey);
        out.put("sessionPrefix", session.substring(0, 12) + "…");
        // Full token for the caller's immediate startup ensure; never persisted.
        out.put("session", session);
        out.put("hello", channelHello(session));
        return out;
    }

    /** Open the framed channel and exchange one hello; proves the token works. */
    public JSONObject channelHello(String session) throws Exception {
        String host = base.substring(base.indexOf("://") + 3);
        int colon = host.lastIndexOf(':');
        String hostname = colon > 0 ? host.substring(0, colon) : host;
        int port = colon > 0 ? Integer.parseInt(host.substring(colon + 1)) : 80;
        // Credentials never in URLs: the token rides a header (contract §5).
        try (WsClient ws = WsClient.connect(hostname, port, "/channel", 10_000,
                new String[] { "X-GBCode-Session: " + session })) {
            byte[] payload = "{\"v\":1,\"id\":1,\"op\":\"session.hello\",\"payload\":{\"echo\":\"from-apk\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            ws.sendText(new String(payload, StandardCharsets.UTF_8));
            WsClient.Frame frame = ws.receive();
            JSONObject hello = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
            // An error reply must never count as a successful identity check:
            // only an ok:true hello carrying the server fingerprint proves the
            // daemon answered on this channel.
            if (!hello.optBoolean("ok", false)) {
                throw new SecurityException("channel hello rejected: "
                        + hello.optJSONObject("error"));
            }
            JSONObject payloadObj = hello.optJSONObject("payload");
            if (payloadObj == null || payloadObj.optString("fingerprint", "").isEmpty()) {
                throw new SecurityException("channel hello missing server fingerprint");
            }
            return hello;
        }
    }

    /**
     * Ask the in-guest daemon to start the IDE backend. This is the startup
     * command to the Linux environment: the daemon (which lives there) spawns
     * the backend and waits for its port. Idempotent; the app calls it on
     * every launch before the workbench opens and treats failure as
     * non-fatal (the app-owned workbench still starts).
     */
    public JSONObject backendEnsure(String session) throws Exception {
        String host = base.substring(base.indexOf("://") + 3);
        int colon = host.lastIndexOf(':');
        String hostname = colon > 0 ? host.substring(0, colon) : host;
        int port = colon > 0 ? Integer.parseInt(host.substring(colon + 1)) : 80;
        try (WsClient ws = WsClient.connect(hostname, port, "/channel", 10_000,
                new String[] { "X-GBCode-Session: " + session })) {
            ws.sendText("{\"v\":1,\"id\":2,\"op\":\"backend.ensure\",\"payload\":{}}");
            WsClient.Frame frame = ws.receive();
            JSONObject reply = new JSONObject(new String(frame.payload, StandardCharsets.UTF_8));
            if (!reply.optBoolean("ok", false)) {
                throw new IllegalStateException("backend.ensure rejected: "
                        + reply.optJSONObject("error"));
            }
            JSONObject payload = reply.optJSONObject("payload");
            if (payload == null) throw new IllegalStateException("backend.ensure missing payload");
            return payload;
        }
    }
}
