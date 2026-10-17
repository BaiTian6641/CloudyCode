package dev.gbcode.app.connection;

import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Set-Cookie handling for the native backend login (S02.03). Every raw header
 * value is forwarded to CookieManager with its attributes intact: stripping
 * attributes (the previous behavior) loses Path/HttpOnly/SameSite and silently
 * changes cookie scope and page-JS visibility.
 */
public final class Cookies {
    private Cookies() {}

    /** Collect raw Set-Cookie values (attributes included), one entry per cookie. */
    public static List<String> setCookieHeaders(URLConnection conn) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("set-cookie")) {
                continue;
            }
            for (String v : e.getValue()) {
                if (v != null && !v.isEmpty()) out.add(v);
            }
        }
        return out;
    }
}
