package dev.gbcode.app.bridge;

import android.net.Uri;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;

/**
 * S02.04 constrained native bridge fixture.
 *
 * Origin-aware messaging via WebViewCompat.addWebMessageListener:
 * - exact allowed origins only (the loopback backend origin),
 * - main-frame delivery by platform design,
 * - schema-validated, size-capped messages,
 * - a few allowlisted methods; sensitive/hardware paths return typed errors,
 * - no generic exec/file/fetch/USB primitives.
 */
public final class NativeBridge {
    static final String JS_OBJECT = "gbcode";
    private static final int MAX_MESSAGE = 16 * 1024;

    public interface Listener {
        void onBridgeMessage(String method, JSONObject payload, String origin);
    }

    private final Listener listener;
    private final String allowedOrigin;

    public NativeBridge(String allowedOrigin, Listener listener) {
        this.allowedOrigin = allowedOrigin;
        this.listener = listener;
    }

    public boolean isSupported() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
    }

    public void attach(WebView webView) {
        if (!isSupported()) {
            listener.onBridgeMessage("__unsupported__", new JSONObject(), "");
            return;
        }
        WebViewCompat.addWebMessageListener(
                webView,
                JS_OBJECT,
                Collections.singleton(allowedOrigin),
                (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                    String origin = sourceOrigin == null ? "" : sourceOrigin.toString();
                    String body = message == null || message.getData() == null ? "" : message.getData();
                    if (!isMainFrame) {
                        listener.onBridgeMessage("__rejected_subframe__", new JSONObject(), origin);
                        return;
                    }
                    if (body.length() > MAX_MESSAGE) {
                        reply("error", "message_too_large", null, replyProxy);
                        return;
                    }
                    JSONObject parsed;
                    try {
                        parsed = new JSONObject(body);
                    } catch (JSONException e) {
                        reply("error", "bad_schema", null, replyProxy);
                        return;
                    }
                    String method = parsed.optString("method", "");
                    JSONObject payload = parsed.optJSONObject("payload");
                    if (payload == null) payload = new JSONObject();
                    listener.onBridgeMessage(method, payload, origin);
                    switch (method) {
                        case "ping":
                            try {
                                reply("ok", null, new JSONObject().put("pong", payload.opt("n")), replyProxy);
                            } catch (JSONException e) {
                                reply("error", "internal", null, replyProxy);
                            }
                            break;
                        case "getDeviceInfo":
                            reply("ok", null, deviceInfo(), replyProxy);
                            break;
                        case "openExternal": {
                            String url = payload.optString("url", "");
                            if (!url.startsWith("https://")) {
                                reply("error", "url_rejected", null, replyProxy);
                                break;
                            }
                            listener.onBridgeMessage("openExternal", payload, origin);
                            reply("ok", null, new JSONObject(), replyProxy);
                            break;
                        }
                        case "requestDeviceSelection":
                            // Hardware authority stays native-side and is not
                            // implemented in the slice: typed error, never a fake grant.
                            reply("error", "unsupported_in_slice", null, replyProxy);
                            break;
                        default:
                            reply("error", "unknown_method", null, replyProxy);
                    }
                });
    }

    private static void reply(String status, String error, JSONObject data,
                              androidx.webkit.JavaScriptReplyProxy replyProxy) {
        try {
            JSONObject out = new JSONObject().put("status", status);
            if (error != null) out.put("error", error);
            if (data != null) out.put("data", data);
            replyProxy.postMessage(out.toString());
        } catch (JSONException e) {
            replyProxy.postMessage("{\"status\":\"error\",\"error\":\"internal\"}");
        }
    }

    private static JSONObject deviceInfo() {
        JSONObject out = new JSONObject();
        try {
            out.put("manufacturer", android.os.Build.MANUFACTURER);
            out.put("model", android.os.Build.MODEL);
            out.put("sdk", android.os.Build.VERSION.SDK_INT);
            out.put("abi", android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "unknown");
        } catch (JSONException ignored) {
        }
        return out;
    }
}
