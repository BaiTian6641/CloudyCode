package dev.gbcode.app.connection;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Set-Cookie extraction must preserve attributes and multiple cookies
 * (S02.03): the previous implementation kept only the first name=value pair
 * and silently dropped Path/HttpOnly/SameSite.
 */
@RunWith(AndroidJUnit4.class)
public class CookiesTest {

    private static ServerSocket serveOnce(String response) throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                // read request head until CRLFCRLF
                int b; int crlf = 0;
                while ((b = in.read()) != -1) {
                    if (b == '\r' && (crlf == 0 || crlf == 2)) crlf++;
                    else if (b == '\n' && (crlf == 1 || crlf == 3)) crlf++;
                    else crlf = 0;
                    if (crlf == 4) break;
                }
                OutputStream out = s.getOutputStream();
                out.write(response.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } catch (Exception ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    @Test
    public void setCookieHeadersKeepAttributesAndMultiples() throws Exception {
        String response = "HTTP/1.1 302 Found\r\n"
                + "Set-Cookie: session=abc123; Path=/; HttpOnly\r\n"
                + "Set-Cookie: theme=dark; Path=/; SameSite=Lax\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n";
        ServerSocket server = serveOnce(response);
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + "/login").openConnection();
        conn.setInstanceFollowRedirects(false);
        assertEquals(302, conn.getResponseCode());

        List<String> cookies = Cookies.setCookieHeaders(conn);
        conn.disconnect();
        server.close();

        assertEquals(2, cookies.size());
        assertTrue(cookies.get(0).startsWith("session=abc123"));
        assertTrue("Path must survive: " + cookies.get(0), cookies.get(0).contains("Path=/"));
        assertTrue("HttpOnly must survive: " + cookies.get(0), cookies.get(0).contains("HttpOnly"));
        assertTrue(cookies.get(1).startsWith("theme=dark"));
        assertTrue("SameSite must survive: " + cookies.get(1), cookies.get(1).contains("SameSite=Lax"));
    }

    @Test
    public void noSetCookieHeaderYieldsEmptyList() throws Exception {
        String response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        ServerSocket server = serveOnce(response);
        HttpURLConnection conn = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + server.getLocalPort() + "/").openConnection();
        assertEquals(200, conn.getResponseCode());
        assertEquals(0, Cookies.setCookieHeaders(conn).size());
        conn.disconnect();
        server.close();
    }
}
