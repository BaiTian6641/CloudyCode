package dev.gbcode.slice;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minimal RFC 6455 client for the network probe: one connection, binary/text
 * echo, ping/pong, clean close. Not a production WebSocket stack.
 */
final class WsClient implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    private WsClient(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    static WsClient connect(String host, int port, String path, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(timeoutMs);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        byte[] keyBytes = new byte[16];
        new SecureRandom().nextBytes(keyBytes);
        String key = Base64.getEncoder().encodeToString(keyBytes);
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        out.write(request.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String statusLine = readAsciiLine(in);
        if (!statusLine.contains("101")) {
            socket.close();
            throw new IOException("WS handshake refused: " + statusLine);
        }
        String line;
        while (!(line = readAsciiLine(in)).isEmpty()) {
            // consume headers
        }
        return new WsClient(socket, in, out);
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            buf.write(b);
        }
        if (b == -1 && buf.size() == 0) {
            throw new EOFException("stream closed during handshake");
        }
        String s = buf.toString(StandardCharsets.US_ASCII);
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }

    void sendBinary(byte[] payload) throws IOException {
        sendFrame(0x2, payload);
    }

    void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x80 | opcode);
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        int len = payload.length;
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            frame.write(0x80 | 126);
            frame.write((len >>> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        frame.write(mask);
        for (int i = 0; i < len; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    Frame receive() throws IOException {
        while (true) {
            int b0 = in.read();
            if (b0 == -1) {
                throw new EOFException("stream closed while reading frame");
            }
            int opcode = b0 & 0x0F;
            int b1 = in.read();
            if (b1 == -1) {
                throw new EOFException("stream closed while reading length");
            }
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = readN(in, 2);
            } else if (len == 127) {
                len = readN(in, 8);
            }
            if (len > 1 << 20) {
                throw new IOException("probe frame too large: " + len);
            }
            byte[] mask = masked ? readBytes(in, 4) : null;
            byte[] payload = readBytes(in, (int) len);
            if (mask != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 0x9) { // ping -> pong
                sendFrame(0xA, payload);
                continue;
            }
            if (opcode == 0xA) { // unsolicited pong
                continue;
            }
            return new Frame(opcode, payload);
        }
    }

    private static long readN(InputStream in, int n) throws IOException {
        long v = 0;
        for (int i = 0; i < n; i++) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("stream closed in extended length");
            }
            v = (v << 8) | b;
        }
        return v;
    }

    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) {
                throw new EOFException("stream closed in payload");
            }
            off += r;
        }
        return buf;
    }

    static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            sendFrame(0x8, new byte[0]);
        } catch (Exception ignored) {
        }
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
