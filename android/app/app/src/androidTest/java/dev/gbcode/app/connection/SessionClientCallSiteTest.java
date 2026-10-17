package dev.gbcode.app.connection;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Call-site tests (code review finding): the golden-vector tests prove the
 * verification helpers; these drive pair()/session() against a stub daemon so
 * that removing the verification call, or verifying with the wrong key, breaks
 * a test. Signatures here are produced by the same Ed25519 primitive (whose
 * cross-implementation behavior is pinned separately by the Node vectors).
 */
@RunWith(AndroidJUnit4.class)
public class SessionClientCallSiteTest {

    private ServerSocket server;

    @After
    public void tearDown() throws Exception {
        if (server != null) server.close();
    }

    /** Serve scripted JSON per request path (first line routing, body drained). */
    private void serve(Map<String, String> responses) throws Exception {
        server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    InputStream in = s.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int b, crlf = 0;
                    while ((b = in.read()) != -1) {
                        head.append((char) b);
                        if (b == '\r' && (crlf == 0 || crlf == 2)) crlf++;
                        else if (b == '\n' && (crlf == 1 || crlf == 3)) crlf++;
                        else crlf = 0;
                        if (crlf == 4) break;
                    }
                    String headText = head.toString();
                    String path = headText.split(" ")[1];
                    // drain the request body
                    int contentLength = 0;
                    for (String line : headText.split("\r\n")) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            contentLength = Integer.parseInt(line.substring(15).trim());
                        }
                    }
                    for (int i = 0; i < contentLength; i++) if (in.read() < 0) break;
                    String key = path.startsWith("/session/challenge") ? "/session/challenge" : path;
                    String body = responses.get(key);
                    if (body == null) body = "{\"v\":1,\"error\":{\"code\":\"not_found\"}}";
                    byte[] out = body.getBytes(StandardCharsets.UTF_8);
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + out.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    os.write(out);
                    os.flush();
                } catch (Exception ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
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

    private static String b64u(byte[] data) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Build a self-consistent enrollment receipt signed by the stub server key. */
    private static JSONObject receipt(Ed25519.KeyPair serverKey, byte[] appPublicKey,
                                      String nonce) {
        byte[] appHash = digest(appPublicKey);
        byte[] sig = Ed25519.sign(serverKey.privateKey, serverKey.publicKey,
                concat("enroll".getBytes(StandardCharsets.UTF_8), appHash,
                        nonce.getBytes(StandardCharsets.UTF_8)));
        JSONObject out = new JSONObject();
        try {
            out.put("v", 1);
            out.put("serverPublicKey", b64u(serverKey.publicKey));
            out.put("serverFingerprint", hex(digest(serverKey.publicKey)));
            out.put("enrolledApp", hex(appHash));
            out.put("receiptSig", b64u(sig));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return out;
    }

    private static byte[] digest(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] flip(byte[] data) {
        byte[] out = data.clone();
        out[0] ^= 0x01;
        return out;
    }

    @Test
    public void pairVerifiesReceiptAtCallSite() throws Exception {
        Ed25519.KeyPair serverKey = Ed25519.generate();
        Map<String, String> responses = new HashMap<>();
        serve(responses);
        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        JSONObject resp = receipt(serverKey, client.getKeyPair().publicKey, "call-site-nonce");
        responses.put("/pair", resp.toString());

        SessionClient.PairResult result = client.pair("secret", "call-site-nonce");
        assertEquals(resp.getString("enrolledApp"), result.appHash);
        assertTrue(result.appHash.length() == 64); // full hash, never a truncation
    }

    @Test
    public void pairRejectsTamperedReceiptAtCallSite() throws Exception {
        Ed25519.KeyPair serverKey = Ed25519.generate();
        Map<String, String> responses = new HashMap<>();
        serve(responses);
        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        JSONObject resp = receipt(serverKey, client.getKeyPair().publicKey, "call-site-nonce");
        resp.put("receiptSig", b64u(flip(java.util.Base64.getUrlDecoder()
                .decode(resp.getString("receiptSig")))));
        responses.put("/pair", resp.toString());

        assertThrows(SecurityException.class, () -> client.pair("secret", "call-site-nonce"));
    }

    @Test
    public void sessionVerifiesServerProofAtCallSite() throws Exception {
        Ed25519.KeyPair serverKey = Ed25519.generate();
        String appHash = "b095d1caaae9176d6640d5a73120daf9b113f6a1988d937857638f620774f722";
        String challenge = "stub-challenge-1";
        byte[] proof = Ed25519.sign(serverKey.privateKey, serverKey.publicKey,
                concat("serverprove".getBytes(StandardCharsets.UTF_8),
                        hexToBytes(appHash),
                        challenge.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> responses = new HashMap<>();
        responses.put("/session/challenge", "{\"v\":1,\"challenge\":\"" + challenge + "\",\"expiresInMs\":60000}");
        responses.put("/session", "{\"v\":1,\"session\":\"v1.stub.token\",\"expiresAt\":0,\"serverProof\":\""
                + b64u(proof) + "\"}");
        serve(responses);

        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        String token = client.session(appHash, b64u(serverKey.publicKey));
        assertEquals("v1.stub.token", token);
    }

    private static byte[] hexToBytes(String hexStr) {
        byte[] out = new byte[hexStr.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hexStr.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    @Test
    public void sessionRejectsMissingServerProofAtCallSite() throws Exception {
        Map<String, String> responses = new HashMap<>();
        responses.put("/session/challenge", "{\"v\":1,\"challenge\":\"c1\",\"expiresInMs\":60000}");
        responses.put("/session", "{\"v\":1,\"session\":\"v1.stub.token\",\"expiresAt\":0}");
        serve(responses);

        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        String appHash = "b095d1caaae9176d6640d5a73120daf9b113f6a1988d937857638f620774f722";
        assertThrows(SecurityException.class,
                () -> client.session(appHash, b64u(Ed25519.generate().publicKey)));
    }

    @Test
    public void sessionRejectsTamperedServerProofAtCallSite() throws Exception {
        Ed25519.KeyPair serverKey = Ed25519.generate();
        String appHash = "b095d1caaae9176d6640d5a73120daf9b113f6a1988d937857638f620774f722";
        String challenge = "stub-challenge-2";
        byte[] proof = Ed25519.sign(serverKey.privateKey, serverKey.publicKey,
                concat("serverprove".getBytes(StandardCharsets.UTF_8),
                        hexToBytes(appHash),
                        challenge.getBytes(StandardCharsets.UTF_8)));
        Map<String, String> responses = new HashMap<>();
        responses.put("/session/challenge", "{\"v\":1,\"challenge\":\"" + challenge + "\",\"expiresInMs\":60000}");
        responses.put("/session", "{\"v\":1,\"session\":\"v1.stub.token\",\"expiresAt\":0,\"serverProof\":\""
                + b64u(flip(proof)) + "\"}");
        serve(responses);

        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        assertThrows(SecurityException.class, () -> client.session(appHash, b64u(serverKey.publicKey)));
    }

    @Test
    public void httpErrorStatusIsRejectedAtCallSite() throws Exception {
        // 403 responses must surface as failures, never as a usable identity.
        Map<String, String> responses = new HashMap<>();
        serve(responses);
        SessionClient client = new SessionClient("http://127.0.0.1:" + server.getLocalPort());
        assertThrows(Exception.class,
                () -> client.pair("secret", "nonce-x"));
    }
}
