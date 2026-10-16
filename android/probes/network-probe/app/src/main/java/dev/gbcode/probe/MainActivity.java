package dev.gbcode.probe;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * S00.03/S00.04 probe: native HTTP, native WebSocket and WebView connectivity
 * plus browser-capability checks against a developer-machine echo server.
 * Results go to logcat, the on-screen log and POST /report (source-separated).
 */
public final class MainActivity extends Activity {
    private static final String TAG = "GBCodeProbe";
    private static final int TIMEOUT_MS = 10_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText hostField;
    private TextView logView;
    private LinearLayout contentRoot;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hostField = new EditText(this);
        hostField.setText("10.0.2.2:8766");
        hostField.setSingleLine(true);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        addButton(buttons, "Native HTTP", v -> runNativeHttp());
        addButton(buttons, "Native WS", v -> runNativeWs());
        addButton(buttons, "WebView", v -> runWebView());

        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);

        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(hostField);
        contentRoot.addView(buttons);
        contentRoot.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(contentRoot);

        log("probe ready; target host " + hostField.getText());
    }

    private void addButton(LinearLayout parent, String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleTestIntent(intent);
    }

    private void handleTestIntent(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String host = intent.getStringExtra("host");
        if (host != null) {
            hostField.setText(host);
        }
        String test = intent.getStringExtra("test");
        log("intent trigger test=" + test);
        switch (test) {
            case "http":
                runNativeHttp();
                break;
            case "ws":
                runNativeWs();
                break;
            case "webview":
                runWebView();
                break;
            default:
                log("unknown test extra: " + test);
        }
    }

    private String hostPort() {
        return hostField.getText().toString().trim();
    }

    private void log(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> {
            logView.append(timestamp() + " " + message + "\n");
        });
        try (FileOutputStream fos = openFileOutput("probe-log.txt", MODE_APPEND)) {
            fos.write((timestamp() + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "log file write failed", e);
        }
    }

    private static String timestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private void report(String testId, boolean pass, String detail) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("source", "native")
                        .put("test", testId)
                        .put("pass", pass)
                        .put("detail", detail)
                        .put("device", android.os.Build.FINGERPRINT)
                        .put("sdk", android.os.Build.VERSION.SDK_INT)
                        .put("ts", timestamp());
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://" + hostPort() + "/report").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                log("report " + testId + " -> HTTP " + code);
            } catch (JSONException | IOException e) {
                log("report " + testId + " failed to send: " + e.getMessage());
            }
        });
    }

    private void runNativeHttp() {
        executor.execute(() -> {
            String testId = "NET-EMU-HTTP";
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        new URL("http://" + hostPort() + "/nonce").openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                String body = readAll(conn.getInputStream());
                int code = conn.getResponseCode();
                conn.disconnect();
                boolean pass = code == 200 && body.contains("\"nonce\"");
                log(testId + (pass ? " PASS" : " FAIL") + " code=" + code + " body=" + body.trim());
                report(testId, pass, "http code " + code);
            } catch (IOException e) {
                log(testId + " ERROR " + e);
                report(testId, false, e.toString());
            }
        });
    }

    private void runNativeWs() {
        executor.execute(() -> {
            String testId = "NET-EMU-WS-BINARY";
            byte[] payload = new byte[1024];
            new SecureRandom().nextBytes(payload);
            String expected = WsClient.sha256Hex(payload);
            String hp = hostPort();
            int colon = hp.lastIndexOf(':');
            String host = colon > 0 ? hp.substring(0, colon) : hp;
            int port = colon > 0 ? Integer.parseInt(hp.substring(colon + 1)) : 80;
            try (WsClient ws = WsClient.connect(host, port, "/ws", TIMEOUT_MS)) {
                ws.sendBinary(payload);
                WsClient.Frame frame = ws.receive();
                String actual = WsClient.sha256Hex(frame.payload);
                boolean pass = frame.opcode == 0x2 && expected.equals(actual);
                log(testId + (pass ? " PASS" : " FAIL")
                        + " opcode=" + frame.opcode + " bytes=" + frame.payload.length
                        + (pass ? "" : " expected=" + expected + " actual=" + actual));
                report(testId, pass, "binary echo " + frame.payload.length + " bytes");
            } catch (Exception e) {
                log(testId + " ERROR " + e);
                report(testId, false, e.toString());
            }
        });
    }

    private void runWebView() {
        runOnUiThread(() -> {
            if (webView == null) {
                webView = new WebView(this);
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                contentRoot.addView(webView, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            String url = "http://" + hostPort() + "/";
            log("WEB-EMU loading " + url + " (results arrive via /report)");
            webView.loadUrl(url);
        });
    }

    private static String readAll(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
