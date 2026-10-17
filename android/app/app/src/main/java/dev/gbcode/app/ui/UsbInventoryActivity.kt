package dev.gbcode.app.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * USB host diagnostic (S07.02 spike): enumerate devices/interfaces/endpoints,
 * request the user's USB permission, then claim the CDC data interface and read
 * bytes from the ESP32-S3 native USB serial endpoint.
 *
 * This proves the Android-side half of the hardware broker. The guest side
 * (USB/IP import or a serial transport into the VM) builds on these handles.
 */
class UsbInventoryActivity : Activity() {
    private lateinit var output: TextView
    private lateinit var status: TextView
    private lateinit var requestButton: Button
    private lateinit var claimReadButton: Button
    private lateinit var startBridge: Button
    private lateinit var stopBridge: Button
    private lateinit var copyBridge: Button
    private lateinit var startUsbIp: Button
    private lateinit var stopUsbIp: Button
    private lateinit var copyUsbIp: Button
    private lateinit var copySetup: Button
    private lateinit var copyAttach: Button
    private lateinit var bridgeStatus: TextView
    private var debugToken: String? = null
    private val token: String
        get() = debugToken ?: (application as dev.gbcode.app.GbCodeApp).connectionToken()

    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }
    private var target: UsbDevice? = null
    private var receiverRegistered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            status.text = if (granted) {
                "USB permission granted for ${device?.productName ?: "device"}"
            } else {
                "USB permission denied"
            }
            claimReadButton.isEnabled = granted
            Log.i(TAG, "permission granted=$granted device=${device?.deviceName}")
            scan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        debugToken = intent.getStringExtra("token")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "USB devices"
            textSize = 22f
            setTextColor(Color.parseColor("#e0e0e0"))
        }
        status = TextView(this).apply {
            text = "Scanning…"
            textSize = 14f
            setTextColor(Color.parseColor("#c0c0c0"))
            setPadding(0, 8, 0, 12)
        }
        output = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#DBE8EC"))
            setBackgroundColor(Color.parseColor("#101C22"))
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply { addView(output) }
        requestButton = Button(this).apply {
            text = "Request USB permission"
            isEnabled = false
            setOnClickListener { requestPermission() }
        }
        claimReadButton = Button(this).apply {
            text = "Claim CDC interface and read 1 s"
            isEnabled = false
            setOnClickListener { claimAndRead() }
        }
        bridgeStatus = TextView(this).apply {
            text = "Bridge: ${dev.gbcode.app.SerialBridgeService.state}"
            textSize = 13f
            setTextColor(Color.parseColor("#909090"))
            setPadding(0, 12, 0, 4)
        }
        startBridge = Button(this).apply {
            text = "Start serial forwarding"
            setOnClickListener { startForwarding() }
        }
        stopBridge = Button(this).apply {
            text = "Stop serial forwarding"
            setOnClickListener {
                startService(Intent(this@UsbInventoryActivity, dev.gbcode.app.SerialBridgeService::class.java)
                    .setAction(dev.gbcode.app.SerialBridgeService.ACTION_STOP))
            }
        }
        copyBridge = Button(this).apply {
            text = "Copy guest bridge command"
            setOnClickListener { copyGuestCommand() }
        }
        startUsbIp = Button(this).apply {
            text = "Start USB/IP forwarding (arbitrary USB)"
            setOnClickListener { startUsbIpForwarding() }
        }
        stopUsbIp = Button(this).apply {
            text = "Stop USB/IP forwarding"
            setOnClickListener {
                startService(Intent(this@UsbInventoryActivity, dev.gbcode.app.UsbIpService::class.java)
                    .setAction(dev.gbcode.app.UsbIpService.ACTION_STOP))
            }
        }
        copyUsbIp = Button(this).apply {
            text = "Copy USB/IP relay command"
            setOnClickListener { copyUsbIpCommand() }
        }
        copyAttach = Button(this).apply {
            text = "Copy USB auto-attach patch (short)"
            setOnClickListener {
                val patch = assets.open("cloudy-attach-patch.sh").bufferedReader().use { it.readText() }
                setClipboardText("Cloudy attach patch", patch)
                status.text = "Attach patch copied. Paste it in the Terminal (sudo). It installs " +
                    "usbip, vhci and the auto-attach unit."
            }
        }
        copySetup = Button(this).apply {
            text = "Copy Linux setup command (services + auto-attach)"
            setOnClickListener {
                val script = assets.open("cloudy-setup.sh").bufferedReader().use { it.readText() }
                setClipboardText("Cloudy setup", GuestSetup.setupCommand(token, script))
                status.text = "Setup command copied. Run it once in the Terminal (sudo). It " +
                    "refreshes the bridges and installs the USB/IP auto-attach unit."
            }
        }
        root.addView(title)
        root.addView(status)
        root.addView(requestButton)
        root.addView(claimReadButton)
        root.addView(bridgeStatus)
        root.addView(startBridge)
        root.addView(stopBridge)
        root.addView(copyBridge)
        root.addView(startUsbIp)
        root.addView(stopUsbIp)
        root.addView(copyUsbIp)
        root.addView(copySetup)
        root.addView(copyAttach)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permissionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(permissionReceiver, filter)
        }
        receiverRegistered = true
        scan()
        val handler = android.os.Handler(mainLooper)
        handler.post(object : Runnable {
            override fun run() {
                bridgeStatus.text = "Serial: ${dev.gbcode.app.SerialBridgeService.state}" +
                    "   USB/IP: ${dev.gbcode.app.UsbIpService.state}"
                handler.postDelayed(this, 800)
            }
        })
    }

    private fun startForwarding() {
        val device = target
        if (device != null && !usb.hasPermission(device)) {
            status.text = "Grant USB permission first; forwarding starts automatically after it."
            requestPermission()
            return
        }
        startForegroundService(Intent(this, dev.gbcode.app.SerialBridgeService::class.java)
            .setAction(dev.gbcode.app.SerialBridgeService.ACTION_START)
            .putExtra(dev.gbcode.app.SerialBridgeService.EXTRA_TOKEN, token))
        status.text = "Forwarding requested; waiting for the guest bridge."
    }

    private fun startUsbIpForwarding() {
        val device = target
        if (device != null && !usb.hasPermission(device)) {
            status.text = "Grant USB permission first; forwarding starts after it."
            requestPermission()
            return
        }
        android.util.Log.i("CloudyUsb", "usbip start, token source=" +
            (if (debugToken != null) "intent" else "app") + " token=" + token.take(4) + "…")
        startForegroundService(Intent(this, dev.gbcode.app.UsbIpService::class.java)
            .setAction(dev.gbcode.app.UsbIpService.ACTION_START)
            .putExtra(dev.gbcode.app.UsbIpService.EXTRA_TOKEN, token))
        status.text = "USB/IP forwarding requested; waiting for the guest relay."
    }

    private fun copyUsbIpCommand() {
        val script = assets.open("cloudy-usbip-relay.py").bufferedReader().use { it.readText() }
        val command = "cat > ~/cloudy-usbip-relay.py <<'PY'\n$script\nPY\n" +
            "python3 ~/cloudy-usbip-relay.py --token $token"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
            "Cloudy USB/IP relay", command))
        status.text = "USB/IP relay command copied. Run it in the Terminal, start USB/IP " +
            "forwarding here, then in the VM: usbip attach -r 127.0.0.1 -b cloudy-303a-1001"
    }

    private fun setClipboardText(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    }

    private fun copyGuestCommand() {
        val script = assets.open("cloudy-serial-bridge.py").bufferedReader().use { it.readText() }
        val command = "cat > ~/cloudy-serial-bridge.py <<'PY'\n$script\nPY\n" +
            "python3 ~/cloudy-serial-bridge.py --token $token"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
            "Cloudy serial bridge", command))
        status.text = "Guest command copied. Run it once in the Terminal, keep the Terminal " +
            "open, then use rfc2217://127.0.0.1:7002 in esptool/PlatformIO/pyserial."
    }

    private fun scan() {
        val manager = usb
        val sb = StringBuilder()
        val devices = manager.deviceList.values.sortedBy { "${it.vendorId}:${it.productId}" }
        for (dev in devices) {
            val granted = manager.hasPermission(dev)
            sb.append("%04x:%04x".format(dev.vendorId, dev.productId))
            // Descriptor/name access requires the per-device USB permission on
            // Android 17; surface that instead of crashing the scan.
            try {
                sb.append("  %s / %s  serial=%s".format(
                    dev.manufacturerName ?: "?", dev.productName ?: "?",
                    dev.serialNumber ?: "?"))
            } catch (e: SecurityException) {
                sb.append("  (names need USB permission)")
            }
            sb.append(if (granted) "" else "  [permission required]").append("\n")
            if (granted) {
                try {
                    for (i in 0 until dev.interfaceCount) {
                        val iface: UsbInterface = dev.getInterface(i)
                        sb.append("  iface %d class=%02x sub=%02x proto=%02x eps=%d\n".format(
                            iface.id, iface.interfaceClass, iface.interfaceSubclass,
                            iface.interfaceProtocol, iface.endpointCount))
                        for (e in 0 until iface.endpointCount) {
                            val ep: UsbEndpoint = iface.getEndpoint(e)
                            sb.append("    ep %02x type=%d dir=%s max=%d\n".format(
                                ep.address, ep.type,
                                if (ep.direction == UsbConstants.USB_DIR_IN) "in" else "out",
                                ep.maxPacketSize))
                        }
                    }
                } catch (e: SecurityException) {
                    sb.append("  (descriptors need USB permission)\n")
                }
            }
        }
        if (devices.isEmpty()) sb.append("No USB devices\n")
        output.text = sb.toString()
        target = dev.gbcode.app.SerialDevicePicker.pick(manager)
        requestButton.isEnabled = target != null && !manager.hasPermission(target!!)
        claimReadButton.isEnabled = target != null && manager.hasPermission(target!!)
        status.text = if (target != null) {
            "USB device found (${"%04x:%04x".format(target!!.vendorId, target!!.productId)})"
        } else {
            "No serial-capable USB device attached"
        }
        Log.i(TAG, "scan:\n$sb")
    }

    private fun requestPermission() {
        val device = target ?: return
        val flags = PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), flags)
        usb.requestPermission(device, pi)
        status.text = "Waiting for the system USB permission dialog…"
    }

    /**
     * Claim the CDC data interface (class 0x0a: bulk OUT/IN) and read whatever
     * the firmware emits for one second. Read-only: this never writes and never
     * toggles DTR/RTS (which could reset the target).
     */
    private fun claimAndRead() {
        val device = target ?: return
        if (!usb.hasPermission(device)) {
            status.text = "USB permission not granted"
            return
        }
        val dataIface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
        if (dataIface == null) {
            status.text = "CDC data interface not found"
            return
        }
        val epIn = (0 until dataIface.endpointCount)
            .map { dataIface.getEndpoint(it) }
            .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        if (epIn == null) {
            status.text = "CDC IN endpoint not found"
            return
        }
        var conn: UsbDeviceConnection? = null
        try {
            conn = usb.openDevice(device) ?: run {
                status.text = "openDevice returned null"
                return
            }
            val claimed = conn.claimInterface(dataIface, true)
            Log.i(TAG, "claimInterface(${dataIface.id}) = $claimed")
            if (!claimed) {
                status.text = "Could not claim CDC interface (driver busy)"
                return
            }
            val buffer = ByteArray(4096)
            val total = StringBuilder()
            val deadline = System.currentTimeMillis() + 1000
            var bytesRead = 0
            while (System.currentTimeMillis() < deadline) {
                val n = conn.bulkTransfer(epIn, buffer, buffer.size, 200)
                if (n > 0) {
                    bytesRead += n
                    total.append(String(buffer, 0, n, Charsets.UTF_8))
                }
            }
            conn.releaseInterface(dataIface)
            status.text = "Claimed CDC; read $bytesRead byte(s) in 1 s"
            if (total.isNotEmpty()) {
                output.append("\n--- CDC read ---\n" + total.take(2000))
            }
        } catch (e: Exception) {
            status.text = "USB read failed: ${e.message}"
            Log.w(TAG, "read failed", e)
        } finally {
            conn?.close()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugToken = intent.getStringExtra("token")
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(permissionReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CloudyUsb"
        private const val ACTION_USB_PERMISSION = "dev.gbcode.app.USB_PERMISSION"
    }
}
