package dev.gbcode.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gbcode.app.connection.Gateway
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * ADR-0006 milestone-2 coverage: the gateway serves the packaged Code-OSS
 * workbench (class A) from the APK, renders the upstream template without
 * placeholders, and denies the guest's core asset routes instead of proxying
 * them. Runs on the emulator only (ANDROID_SERIAL-scoped).
 */
@RunWith(AndroidJUnit4::class)
class WebAssetGatewayTest {

    private fun assets(): WebAssets =
        WebAssets(InstrumentationRegistry.getInstrumentation().targetContext.assets)

    private fun rawRequest(gwPort: Int, head: String): String {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", gwPort), 5000)
        socket.soTimeout = 20000
        socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = try {
                socket.getInputStream().read(buf)
            } catch (_: Exception) {
                -1
            }
            if (n < 0) break
            out.write(buf, 0, n)
        }
        socket.close()
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    /** Upstream stub that records whether it was contacted. */
    private class StubUpstream : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        @Volatile var contacted = false

        init {
            Thread {
                while (!server.isClosed) {
                    try {
                        val s = server.accept()
                        contacted = true
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            server.close()
        }
    }

    private fun startGateway(upstream: StubUpstream? = null, renderer: Boolean = true): Gateway {
        val a = assets()
        val template = a.readText("out/vs/code/browser/workbench/workbench.html")!!
        return Gateway(upstream?.let { "127.0.0.1" }, upstream?.port ?: 0, a) { nonce ->
            if (renderer) WorkbenchPage.renderWorkbench(template, "127.0.0.1:41873", nonce) else ""
        }.also { it.start(0) }
    }

    @Test
    fun indexRendersUpstreamTemplateWithoutPlaceholders() {
        val gw = startGateway()
        try {
            val resp = rawRequest(gw.port,
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue("200 expected", resp.startsWith("HTTP/1.1 200"))
            assertTrue("csp expected", resp.contains("Content-Security-Policy:"))
            assertTrue("workbench configuration meta expected",
                resp.contains("vscode-workbench-web-configuration"))
            assertTrue("remoteAuthority expected in configuration",
                resp.contains("127.0.0.1:41873"))
            assertFalse("template placeholders must be rendered: ${resp.take(400)}",
                resp.contains("{{WORKBENCH"))
        } finally {
            gw.close()
        }
    }

    @Test
    fun coreAssetsAreServedLocally() {
        val gw = startGateway(renderer = false)
        try {
            val js = rawRequest(gw.port,
                "HEAD /out/vs/code/browser/workbench/workbench.js HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue(js.startsWith("HTTP/1.1 200"))
            assertTrue("js content type expected: $js", js.contains("Content-Type: text/javascript"))

            val css = rawRequest(gw.port,
                "HEAD /out/vs/code/browser/workbench/workbench.css HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue(css.startsWith("HTTP/1.1 200"))

            val icon = rawRequest(gw.port,
                "HEAD /resources/server/favicon.ico HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue(icon.startsWith("HTTP/1.1 200"))
        } finally {
            gw.close()
        }
    }

    @Test
    fun missingCoreAssetIs404AndNeverProxied() {
        val upstream = StubUpstream()
        val gw = startGateway(upstream)
        try {
            val resp = rawRequest(gw.port,
                "GET /out/definitely-missing.js HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue("404 expected: ${resp.take(80)}", resp.startsWith("HTTP/1.1 404"))
            assertFalse("upstream must not be contacted for core assets", upstream.contacted)
        } finally {
            gw.close()
            upstream.close()
        }
    }

    @Test
    fun guestCoreAssetRoutesAreDeniedWithTrace() {
        val upstream = StubUpstream()
        val gw = startGateway(upstream)
        try {
            for (path in listOf(
                "/stable-abc123/static/out/vs/loader.js",
                "/stable-abc123/static/node_modules/xterm/xterm.js",
                "/stable-abc123/static/out/vs/code/browser/workbench/workbench.html",
            )) {
                val resp = rawRequest(gw.port,
                    "GET $path HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
                assertTrue("$path must be denied: ${resp.take(80)}", resp.startsWith("HTTP/1.1 403"))
            }
            assertEquals(3, gw.denyCoreCount)
            assertFalse("upstream must not be contacted for guest core routes", upstream.contacted)
        } finally {
            gw.close()
            upstream.close()
        }
    }

    @Test
    fun assetManifestMatchesShippedAssets() {
        val a = assets()
        val manifestText = a.readText("asset-manifest.json")
        assertNotNull("asset-manifest.json must be packaged", manifestText)
        val manifest = JSONObject(manifestText!!)
        val files = manifest.getJSONArray("files")
        assertTrue("manifest covers the packaged build", files.length() > 1000)
        val distInfo = JSONObject(a.readText("dist-info.json")!!)
        assertEquals("2a59476c9bfcb90b3ddc372c36762471b7dfad1c", distInfo.getString("upstreamCommit"))
        assertEquals("1.141.0", distInfo.getString("upstreamVersion"))
        // Spot-check that manifest entries resolve to real packaged assets.
        for (i in listOf(0, files.length() / 2, files.length() - 1)) {
            val path = files.getJSONObject(i).getString("path")
            assertNotNull("manifest file must exist: $path", a.open(path))
        }
        // The page shell the workbench.html loads must be present.
        assertNotNull(a.open("out/vs/code/browser/workbench/workbench.js"))
        assertNotNull(a.open("out/vs/code/browser/workbench/workbench.css"))
    }

    @Test
    fun localModeWorksWithoutUpstream() {
        // The installed workbench must start with no guest at all (milestone 2).
        val gw = startGateway(upstream = null)
        try {
            val resp = rawRequest(gw.port,
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue(resp.startsWith("HTTP/1.1 200"))
            assertTrue(resp.contains("vscode-workbench-web-configuration"))

            val proxied = rawRequest(gw.port,
                "GET /login HTTP/1.1\r\nHost: 127.0.0.1:${gw.port}\r\nConnection: close\r\n\r\n")
            assertTrue("no upstream: service routes are 404", proxied.startsWith("HTTP/1.1 404"))
        } finally {
            gw.close()
        }
    }
}
