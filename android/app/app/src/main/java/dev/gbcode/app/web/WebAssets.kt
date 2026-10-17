package dev.gbcode.app.web

import android.content.res.AssetManager
import java.io.InputStream

/**
 * App-owned core workbench assets (ADR-0006, asset class A): the packaged
 * Code-OSS web workbench (pinned commit, manifest generated from the actual
 * build) served from the APK. Core assets are local-only — the gateway never
 * falls back to the guest for them and denies the guest's core routes.
 */
class WebAssets(private val assets: AssetManager, private val root: String = "web") {

    /** A packaged asset; [open] yields a fresh stream per read. */
    data class Asset(val open: () -> InputStream, val contentType: String)

    companion object {
        private val CONTENT_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "json" to "application/json; charset=utf-8",
            "map" to "application/json; charset=utf-8",
            "wasm" to "application/wasm",
            "png" to "image/png",
            "ico" to "image/x-icon",
            "svg" to "image/svg+xml",
            "txt" to "text/plain; charset=utf-8",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
        )

        fun contentType(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return CONTENT_TYPES[ext] ?: "application/octet-stream"
        }
    }

    /**
     * Normalize a request path to an asset path; returns null for traversal or
     * non-relative paths. Never throws.
     */
    fun normalize(path: String): String? {
        var p = path.substringBefore('?').substringBefore('#')
        if (p.startsWith("/")) p = p.substring(1)
        if (p.isEmpty() || p == "index.html") p = "out/vs/code/browser/workbench/workbench.html"
        if (p.contains("..") || p.startsWith("/") || p.contains("\\")) return null
        return p
    }

    fun open(path: String): Asset? {
        val rel = normalize(path) ?: return null
        return try {
            assets.open("$root/$rel") // throws FileNotFoundException when absent
            Asset({ assets.open("$root/$rel") }, contentType(rel))
        } catch (e: Exception) {
            null
        }
    }

    fun readText(path: String): String? {
        val rel = normalize(path) ?: return null
        return try {
            assets.open("$root/$rel").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Renders the upstream workbench template exactly like the server does
 * (`webClientServer.renderWorkbenchTemplate`, pinned commit): `{{KEY}}`
 * placeholders become HTML-attribute-encoded values.
 */
object WorkbenchPage {
    private val PLACEHOLDER = Regex("\\{\\{([^}]+)\\}\\}")

    /**
     * Client-side counterpart of webClientServer.renderWorkbench at the pinned
     * commit (2a59476c): the upstream workbench template plus the app-held
     * construction configuration. Workspace Trust stays enabled — pairing with
     * Debian does not mark any repository trusted (ADR-0006).
     */
    fun renderWorkbench(template: String, remoteAuthority: String?, nonce: String): String {
        val cfg = org.json.JSONObject()
        if (remoteAuthority != null) cfg.put("remoteAuthority", remoteAuthority)
        cfg.put("serverBasePath", "")
        cfg.put("enableWorkspaceTrust", true)
        cfg.put("callbackRoute", "/callback")
        cfg.put("productConfiguration",
            org.json.JSONObject().put("embedderIdentifier", "server-distro"))
        return render(template, mapOf(
            "WORKBENCH_WEB_BASE_URL" to "",
            "WORKBENCH_WEB_CONFIGURATION" to cfg.toString(),
            "WORKBENCH_AUTH_SESSION" to "",
            "WORKBENCH_NLS_URL" to "",
            "WORKBENCH_NLS_FALLBACK_URL" to "/out/nls.messages.js",
            "WORKBENCH_SCRIPT_NONCE" to nonce,
        ))
    }

    fun render(template: String, values: Map<String, String>): String {
        return PLACEHOLDER.replace(template) { m ->
            htmlAttributeEncode(values[m.groupValues[1]] ?: "undefined")
        }
    }

    /** Attribute-safe encoding (mirrors webClientServer's htmlAttributeEncodeValue). */
    fun htmlAttributeEncode(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}
