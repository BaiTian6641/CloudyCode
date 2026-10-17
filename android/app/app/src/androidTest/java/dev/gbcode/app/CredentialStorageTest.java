package dev.gbcode.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Credential-at-rest tests (S02.03): the backend password must never be
 * stored in plaintext SharedPreferences, legacy plaintext must migrate, and
 * clearing must remove it.
 */
@RunWith(AndroidJUnit4.class)
public class CredentialStorageTest {

    private static final String SEED_HEX =
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
    private static final String PASSWORD = "s3cret-backend-pass";

    private GbCodeApp app;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        app = (GbCodeApp) ctx.getApplicationContext();
        prefs = ctx.getSharedPreferences("gbcode", Context.MODE_PRIVATE);
        app.clearPairedState();
    }

    @After
    public void tearDown() {
        app.clearPairedState();
    }

    private GbCodeApp.PairedState state(String password) {
        return new GbCodeApp.PairedState(
                SEED_HEX,
                "a2c00577e92f1dc3827ead7e2d7a00b9298edf181ae99f799ed1ac69c09a565a",
                "http://127.0.0.1:9123",
                "http://127.0.0.1:8080",
                "19bf87b3e23ca3081ca28067e23f5376d13e057df4af7e738f732fa6a32eeeaa",
                "0rK0opBiLgM1blYdrcsLtKwzTxwSorbom7udp3IS-Lk",
                password,
                null,
                "code-server");
    }

    @Test
    public void passwordIsNeverPlaintextOnDisk() throws Exception {
        app.savePairedState(state(PASSWORD));

        // 1. In-memory prefs must not carry the plaintext value.
        for (Object v : prefs.getAll().values()) {
            assertFalse("plaintext password in prefs map",
                    v instanceof String && ((String) v).contains(PASSWORD));
        }
        // Flush SharedPreferences' async writer before inspecting the file
        // (commit() serializes behind pending apply() writes).
        prefs.edit().putBoolean("flushMarker", true).commit();
        prefs.edit().remove("flushMarker").commit();
        // 2. The raw XML on disk must not contain it either.
        File xml = new File(app.getApplicationInfo().dataDir + "/shared_prefs/gbcode.xml");
        assertTrue(xml.exists());
        String raw = new String(Files.readAllBytes(xml.toPath()), StandardCharsets.UTF_8);
        assertFalse("plaintext password in shared_prefs XML", raw.contains(PASSWORD));
        assertTrue("encrypted password entry expected", raw.contains("passwordEnc"));

        // 3. It must still be recoverable through the store.
        GbCodeApp.PairedState loaded = app.loadPairedState();
        assertNotNull(loaded);
        assertEquals(PASSWORD, loaded.getBackendPassword());
    }

    @Test
    public void legacyPlaintextMigratesAndDisappears() {
        app.savePairedState(state(PASSWORD));
        // Simulate a pre-S02.03 install: plaintext key, no encrypted entry.
        prefs.edit().remove("passwordEnc").putString("backendPassword", PASSWORD).commit();

        GbCodeApp.PairedState loaded = app.loadPairedState();
        assertNotNull(loaded);
        assertEquals(PASSWORD, loaded.getBackendPassword());

        assertFalse("legacy plaintext key must be removed",
                prefs.contains("backendPassword"));
        assertTrue("encrypted entry must exist", prefs.contains("passwordEnc"));
    }

    @Test
    public void legacyPlaintextScrubsEvenWithoutServerPublicKey() {
        app.savePairedState(state(PASSWORD));
        // Simulate a pre-M1 install: plaintext password present, serverPublicKey
        // (introduced by this lane) missing. The state is unusable, but the
        // plaintext must still be migrated and removed from disk.
        prefs.edit()
                .remove("passwordEnc")
                .remove("serverPublicKey")
                .putString("backendPassword", PASSWORD)
                .commit();

        assertEquals(null, app.loadPairedState());
        assertFalse("legacy plaintext key must be removed",
                prefs.contains("backendPassword"));
        assertTrue("encrypted entry must exist", prefs.contains("passwordEnc"));
    }

    @Test
    public void clearPairedStateRemovesPassword() {
        app.savePairedState(state(PASSWORD));
        assertTrue(prefs.contains("passwordEnc"));
        app.clearPairedState();
        assertFalse(prefs.contains("passwordEnc"));
        assertFalse(prefs.contains("backendPassword"));
        assertEquals(null, app.loadPairedState());
    }

    @Test
    public void noPasswordStoresNothing() {
        app.savePairedState(state(null));
        assertFalse(prefs.contains("passwordEnc"));
        assertFalse(prefs.contains("backendPassword"));
        GbCodeApp.PairedState loaded = app.loadPairedState();
        assertNotNull(loaded);
        assertEquals(null, loaded.getBackendPassword());
    }
}
