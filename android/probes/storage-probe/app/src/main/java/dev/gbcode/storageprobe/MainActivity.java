package dev.gbcode.storageprobe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * S03 storage probe: SAF tree grant + file semantics suite.
 *
 * Flow: intent action "grant" opens the system tree picker; after the user
 * (or automation) selects a folder, the probe persists the grant and runs the
 * semantics suite: create/write/read/rename/unicode/case/nested/delete plus
 * the .gbcode-space-id sentinel pattern from the architecture. Results go to
 * logcat and files/storage-results.json.
 *
 * Intent extras:
 *   --es action grant   open picker (or rerun suite if a grant exists)
 *   --es action suite   rerun the semantics suite on the persisted grant
 */
public final class MainActivity extends Activity {
    private static final String TAG = "GBCodeStorage";
    private static final int REQ_TREE = 41;
    private static final String PREFS = "probe";
    private static final String KEY_TREE = "treeUri";

    private TextView logView;
    private final JSONObject results = new JSONObject();
    private final List<String> lines = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        Button grant = new Button(this);
        grant.setText("Choose folder");
        grant.setOnClickListener(v -> openTree());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(grant);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("action");
        if (action == null) return;
        switch (action) {
            case "grant": {
                String persisted = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
                if (persisted != null) {
                    log("grant already persisted: " + persisted);
                    runSuite(Uri.parse(persisted));
                } else {
                    openTree();
                }
                break;
            }
            case "suite": {
                String persisted = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
                if (persisted == null) {
                    log("no persisted grant");
                } else {
                    runSuite(Uri.parse(persisted));
                }
                break;
            }
            default:
                log("unknown action " + action);
        }
    }

    private void openTree() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_TREE);
        log("tree picker launched");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            log("grant cancelled");
            record("grant", false, "cancelled");
            return;
        }
        Uri tree = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(tree, flags);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TREE, tree.toString()).apply();
        log("grant persisted: " + tree);
        record("grant", true, tree.toString());
        runSuite(tree);
    }

    private void runSuite(Uri tree) {
        new Thread(() -> {
            try {
                DocumentFile root = DocumentFile.fromTreeUri(this, tree);
                if (root == null) {
                    record("root", false, "DocumentFile null");
                    return;
                }
                String stamp = String.valueOf(System.currentTimeMillis());
                DocumentFile dir = root.createDirectory("gbcode-probe-" + stamp);
                if (dir == null) {
                    record("mkdir", false, "createDirectory failed");
                    return;
                }
                record("mkdir", true, dir.getName());

                // create + write + read back
                DocumentFile hello = dir.createFile("text/plain", "hello.txt");
                writeAll(hello, "gbcode-probe-payload");
                String readBack = readAll(hello);
                record("create_write_read", "gbcode-probe-payload".equals(readBack),
                        "bytes=" + readBack.length());

                // sentinel pattern
                byte[] nonce = new byte[16];
                new SecureRandom().nextBytes(nonce);
                StringBuilder hex = new StringBuilder();
                for (byte b : nonce) hex.append(String.format("%02x", b));
                DocumentFile sentinel = dir.createFile("application/json", ".gbcode-space-id");
                writeAll(sentinel, "{\"spaceId\":\"" + hex + "\",\"v\":1}");
                String sentinelBack = readAll(sentinel);
                record("sentinel", sentinelBack.contains(hex.toString()), "space-id roundtrip");

                // rename to unicode
                boolean renamed = hello.renameTo("héllo Wörld 世界.txt");
                record("rename_unicode", renamed && readAll(hello).equals("gbcode-probe-payload"),
                        "renamed=" + renamed);

                // case-only sibling
                DocumentFile caseA = dir.createFile("text/plain", "Case.txt");
                writeAll(caseA, "A");
                DocumentFile caseB = dir.createFile("text/plain", "case.txt");
                if (caseB == null) {
                    record("case_siblings", true, "second create rejected (collision-safe)");
                } else {
                    writeAll(caseB, "B");
                    String a = readAll(caseA);
                    String b = readAll(caseB);
                    record("case_siblings", "A".equals(a) && "B".equals(b),
                            "a=" + a + " b=" + b + " (distinct contents required)");
                }

                // nested directory
                DocumentFile nested = dir.createDirectory("nested/deep");
                record("nested_mkdir", nested != null, nested == null ? "failed" : nested.getName());

                // list + delete
                int beforeDelete = dir.listFiles().length;
                boolean deleted = hello.delete();
                record("delete", deleted, "files before=" + beforeDelete);
                boolean dirDeleted = dir.delete();
                record("cleanup_dir", dirDeleted, "recursive delete of test dir");

                finishSuite();
            } catch (Exception e) {
                record("suite_error", false, e.toString());
                finishSuite();
            }
        }).start();
    }

    private void writeAll(DocumentFile file, String content) throws Exception {
        try (OutputStream os = getContentResolver().openOutputStream(file.getUri(), "wt")) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String readAll(DocumentFile file) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(file.getUri())) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    private void record(String test, boolean pass, String detail) {
        String line = test + (pass ? " PASS" : " FAIL") + " — " + detail;
        lines.add(line);
        try {
            results.put(test, new JSONObject().put("pass", pass).put("detail", detail));
        } catch (Exception ignored) {
        }
        Log.i(TAG, line);
        runOnUiThread(() -> logView.append(line + "\n"));
    }

    private void finishSuite() {
        try {
            String summary = "SUITE " + results.toString();
            Log.i(TAG, summary);
            try (java.io.FileOutputStream fos = openFileOutput("storage-results.json", MODE_PRIVATE)) {
                fos.write(results.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "result write failed", e);
        }
    }

    private void log(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> logView.append(message + "\n"));
    }
}
