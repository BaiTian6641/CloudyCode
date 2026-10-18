package dev.cloudy.x11.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.cloudy.x11.CloudyX11App

/** Simple one-time VM setup screen; no WebView, xpra or app picker. */
class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle("Cloudy X11 setup")
        val status = TextView(this).apply {
            text = "Copy the setup command, paste it into Terminal and run it once. " +
                "It installs the VM display service; this is needed only once."
            textSize = 15f
            setPadding(0, 0, 0, 20)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            addView(TextView(this@SetupActivity).apply {
                text = "Cloudy X11 setup"
                textSize = 26f
            })
            addView(status)
            addView(Button(this@SetupActivity).apply {
                text = "Copy setup command"
                setOnClickListener { copySetupCommand(status) }
            })
            addView(Button(this@SetupActivity).apply {
                text = "Open Terminal"
                setOnClickListener { openTerminal(status) }
            })
            addView(Button(this@SetupActivity).apply {
                text = "Done"
                setOnClickListener { finish() }
            })
        }
        setContentView(col)
    }

    private fun copySetupCommand(status: TextView) {
        val script = assets.open("cloudy-x11-setup.sh").bufferedReader().use { it.readText() }
        val token = (application as CloudyX11App).x11Token()
        val dir = "~/.local/share/cloudy"
        val command = "rm -f ~/cloudy-x11-setup.sh\nmkdir -p $dir\n" +
            "cat > $dir/cloudy-x11-setup.sh <<'CLOUDY_X11_EOF'\n$script\nCLOUDY_X11_EOF\n" +
            "bash $dir/cloudy-x11-setup.sh --token $token"
        val clip = ClipData.newPlainText("Cloudy X11 setup", command)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(clip)
        status.text = "Setup command copied. Paste it into Terminal and press Enter."
    }

    private fun openTerminal(status: TextView) {
        val pkg = "com.android.virtualization.terminal"
        val launch = packageManager.getLaunchIntentForPackage(pkg)
            ?: Intent().setComponent(ComponentName(pkg, "$pkg.new2.ui.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (e: Exception) {
            status.text = "Could not open Terminal. Launch it from the dock."
        }
    }
}
