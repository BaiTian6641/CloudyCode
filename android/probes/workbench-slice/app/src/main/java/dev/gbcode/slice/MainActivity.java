package dev.gbcode.slice;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * S01.05 vertical-slice APK (emulator lane): hosts a real code-server workbench
 * in Android WebView over the loopback route (adb reverse on the emulator;
 * Terminal forwarding on the physical device later). Intent extras drive the
 * scripted acceptance steps so results are deterministic:
 *   --es url http://127.0.0.1:8080/   backend origin (loopback only)
 *   --es password <pw>                code-server password (login autofill)
 *   --es action connect|state|terminal|conformance
 * Results go to logcat tag GBCodeSlice and files/slice-log.txt (run-as readable).
 */
public final class MainActivity extends Activity {
    private static final String TAG = "GBCodeSlice";

    private WebView webView;
    private TextView statusView;
    private String password;
    private String action;
    private boolean loginSubmitted;
    private boolean actionDispatched;
    private String loadedUrl;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setText("GBCode slice: starting");
        statusView.setMaxLines(1);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setPadding(16, 8, 16, 8);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(statusView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loadedUrl = url;
                log("page finished: " + url);
                if (!loginSubmitted && password != null && url.contains("/login")) {
                    loginSubmitted = true;
                    String js = "(() => {"
                            + "const f=document.querySelector('input[type=password]');"
                            + "if(!f) return 'no-password-field';"
                            + "f.value=" + jsString(password) + ";"
                            + "f.closest('form').requestSubmit();"
                            + "return 'submitted';})()";
                    evaluate(js, "login");
                }
                maybeDispatchAction();
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(android.content.Intent intent) {
        if (intent == null) {
            return;
        }
        String url = intent.getStringExtra("url");
        if (url != null) {
            loginSubmitted = false;
            actionDispatched = false;
            log("load " + url);
            webView.loadUrl(url);
        }
        String pw = intent.getStringExtra("password");
        if (pw != null) {
            password = pw;
        }
        String act = intent.getStringExtra("action");
        if (act != null) {
            action = act;
            actionDispatched = false;
            maybeDispatchAction();
        }
    }

    /** Dispatch the requested scripted action once the workbench is up. */
    private void maybeDispatchAction() {
        if (action == null || actionDispatched || loadedUrl == null
                || loadedUrl.contains("/login")) {
            return;
        }
        switch (action) {
            case "connect":
            case "state":
                actionDispatched = true;
                webView.postDelayed(() -> evaluate(STATE_JS, "state"), 12_000);
                break;
            case "terminal":
                actionDispatched = true;
                webView.postDelayed(this::runTerminalMark, 15_000);
                break;
            case "conformance":
                actionDispatched = true;
                webView.postDelayed(this::runConformance, 15_000);
                break;
            case "domdump":
                actionDispatched = true;
                webView.postDelayed(() -> {
                    String dump = "(() => {"
                            + "const tc=document.querySelector('.titlebar-center');"
                            + "const tl=document.querySelector('.titlebar-left');"
                            + "const cc=document.querySelector('.command-center');"
                            + "const si=[...document.querySelectorAll('[aria-label]')].filter(e=>/search|command|menu/i.test(e.getAttribute('aria-label'))).map(e=>e.getAttribute('aria-label')).slice(0,10);"
                            + "return JSON.stringify({"
                            + "center:tc?tc.outerHTML.slice(0,1500):null,"
                            + "cc:cc?cc.outerHTML.slice(0,600):null,"
                            + "left:tl?tl.outerHTML.slice(0,600):null,"
                            + "searchables:si});})()";
                    evaluate(dump, "domdump");
                }, 12_000);
                break;
            case "menuterm":
                actionDispatched = true;
                webView.postDelayed(() -> runMenuPath(new String[]{"Terminal", "New Terminal"}, "menuterm"), 15_000);
                break;
            case "menupalette":
                actionDispatched = true;
                webView.postDelayed(() -> runMenuPath(new String[]{"View", "Command Palette"}, "menupalette"), 15_000);
                break;
            case "paste":
                actionDispatched = true;
                webView.postDelayed(() -> {
                    String feed = "(() => {"
                            + "const tas=[...document.querySelectorAll('.terminal.active .xterm-helper-textarea, .xterm-helper-textarea')];"
                            + "const ta=tas[tas.length-1];"
                            + "ta.focus();"
                            + "const cmd='echo GBCODE_SLICE_WEBVIEW_OK > /tmp/gbcode-slice-user.txt';"
                            + "ta.value=cmd;"
                            + "ta.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:cmd}));"
                            + "ta.value='';"
                            + "const kd=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true});"
                            + "ta.dispatchEvent(kd);"
                            + "return 'fed+enter';})()";
                    evaluate(feed, "feed-cmd");
                }, 3000);
                break;
            case "cc":
                actionDispatched = true;
                webView.postDelayed(() -> {
                    String open = "(() => {"
                            + "const cc=document.querySelector('.command-center');"
                            + "if(!cc) return 'no-cc';"
                            + "const t=cc.querySelector('.search-icon, [role=\"searchbox\"], input, a, div.monaco-toolbar')||cc;"
                            + "t.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));"
                            + "t.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));t.click();"
                            + "return 'cc-clicked:'+t.className.toString().slice(0,60);})()";
                    evaluate(open, "cc-open");
                    webView.postDelayed(() -> {
                        String check = "(() => {"
                                + "const inp=document.querySelector('.quick-input-widget input');"
                                + "return inp?('quick-input-open:'+(inp.placeholder||'')):'no-quick-input';})()";
                        evaluate(check, "cc-check");
                    }, 2500);
                }, 15_000);
                break;
            default:
                log("unknown action " + action);
        }
    }

    private void runTerminalMark() {
        // Web workbench has no desktop menubar: drive the command palette.
        runPaletteCommand("Terminal: Create New Terminal", "open-terminal");
        webView.postDelayed(() -> {
            String paste = "(() => {"
                    + "const ta=document.querySelector('.xterm-helper-textarea');"
                    + "if(!ta) return 'no-xterm-textarea';"
                    + "ta.focus();"
                    + "const cmd='echo GBCODE_SLICE_$(uname -m) > /tmp/gbcode-slice-mark.txt\\n';"
                    + "const dt=new DataTransfer();"
                    + "dt.setData('text/plain',cmd);"
                    + "ta.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));"
                    + "return 'pasted';})()";
            evaluate(paste, "terminal-paste");
        }, 9000);
        webView.postDelayed(() -> evaluate(STATE_JS, "post-terminal-state"), 4000);
    }

    private void runConformance() {
        runPaletteCommand("GBCode: Run Conformance Report", "conformance");
        webView.postDelayed(() -> evaluate(STATE_JS, "post-conformance-state"), 10000);
    }

    private void runPaletteCommand(String commandText, String label) {
        String open = "(() => {"
                + "window.dispatchEvent(new KeyboardEvent('keydown',{key:'F1',bubbles:true,cancelable:true}));"
                + "return 'f1-sent';})()";
        evaluate(open, label + "-f1");
        webView.postDelayed(() -> {
            String type = "(() => {"
                    + "const inp=document.querySelector('.quick-input-widget input');"
                    + "if(!inp) return 'no-quick-input';"
                    + "const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;"
                    + "setter.call(inp," + jsString(commandText) + ");"
                    + "inp.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "return 'typed';})()";
            evaluate(type, label + "-type");
        }, 1800);
        webView.postDelayed(() -> {
            String pick = "(() => {"
                    + "const rows=[...document.querySelectorAll('.quick-input-list .monaco-list-row')];"
                    + "const r=rows.find(x=>x.textContent.includes(" + jsString(commandText) + "));"
                    + "if(r){r.click();return 'clicked';}"
                    + "return 'rows:'+rows.map(x=>x.textContent).join('|').slice(0,120);})()";
            evaluate(pick, label + "-pick");
        }, 5000);
    }

    /** Opens the compact Application Menu and clicks a submenu path. */
    private void runMenuPath(String[] path, String label) {
        String open = "(() => {"
                + "const b=document.querySelector('[aria-label=\"Application Menu\"]');"
                + "if(b){b.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));"
                + "b.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));b.click();return 'opened';}"
                + "return 'no-app-menu';})()";
        evaluate(open, label + "-open");
        for (int i = 0; i < path.length; i++) {
            final String segment = path[i];
            final String stepLabel = label + "-step" + i;
            final boolean leaf = i == path.length - 1;
            webView.postDelayed(() -> {
                String handler = "it.dispatchEvent(new MouseEvent('mouseenter',{bubbles:false}));"
                        + "it.dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));"
                        + "it.dispatchEvent(new MouseEvent('mouseup',{bubbles:true}));it.click();";
                String click = "(() => {"
                        + "const items=[...document.querySelectorAll('.monaco-menu-container .action-item, .monaco-menu .action-item')];"
                        + "const visible=items.filter(e=>e.offsetParent!==null);"
                        + "const it=visible.find(e=>e.textContent.trim().startsWith(" + jsString(segment) + "));"
                        + "if(it){" + handler + "return 'ok';}"
                        + "return 'items:'+visible.map(e=>e.textContent.trim()).join('|').slice(0,150);})()";
                evaluate(click, stepLabel);
            }, 1800L * (i + 1));
        }
    }


    private static final String STATE_JS = "JSON.stringify({"
            + "url:location.href,"
            + "secure:window.isSecureContext,"
            + "hasSW:'serviceWorker' in navigator,"
            + "workbench:!!document.querySelector('.monaco-workbench'),"
            + "xterms:document.querySelectorAll('.xterm').length,"
            + "webviews:document.querySelectorAll('iframe').length,"
            + "title:document.title,"
            + "ua:navigator.userAgent.slice(0,90)"
            + "})";

    private void evaluate(String js, String label) {
        webView.evaluateJavascript(js, value ->
                log("eval " + label + " => " + value));
    }

    private static String jsString(String raw) {
        return "'" + raw.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void log(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> statusView.setText("GBCode slice: " + message));
        try (FileOutputStream fos = openFileOutput("slice-log.txt", MODE_APPEND)) {
            fos.write((timestamp() + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "log write failed", e);
        }
    }

    private static String timestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }
}
