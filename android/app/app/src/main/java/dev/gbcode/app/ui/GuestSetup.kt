package dev.gbcode.app.ui

/**
 * One-time guest setup shared by the startup and reconnect surfaces.
 *
 * The Terminal app's command endpoint is protected by the platform
 * signature|preinstalled permission MANAGE_TERMINAL, so a sideloaded build
 * cannot run commands in the VM. The user runs this once in the Terminal;
 * afterwards the app connects by itself whenever the forwarded port is up.
 */
object GuestSetup {
    /** Backend used by the sideloaded flow. */
    const val CODE_SERVER_PORT = 8080

    /** Pinned reh-web backend (preinstalled/daemon lane). */
    const val REH_WEB_PORT = 8085

    const val COMMAND =
        "curl -fsSL https://code-server.dev/install.sh | sh -s -- --method=standalone " +
            "--prefix=\$HOME/.local && nohup \$HOME/.local/bin/code-server " +
            "--bind-addr 0.0.0.0:8080 --auth none >\$HOME/code-server.log 2>&1 &"
}
