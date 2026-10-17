package dev.gbcode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * USB serial forwarding service (host half).
 *
 * Owns the ESP32-S3 CDC interface and connects to the guest bridge through the
 * Terminal app's port forward (guest 0.0.0.0:7001 → Android 127.0.0.1:7001).
 * Frames: [type][len:2][payload]; data 0x01 app→guest, 0x02 guest→app,
 * 0x03 control bitmap (bit0 DTR, bit1 RTS), 0x04 keepalive.
 *
 * The guest bridge re-exposes the stream locally as RFC 2217 so esptool,
 * PlatformIO and pyserial work unmodified. This is the serial lane, not USB/IP
 * (no JTAG/arbitrary USB yet).
 */
class SerialBridgeService : Service() {
    private var worker: Thread? = null
    @Volatile private var running = false

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "USB serial bridge", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val token = intent.getStringExtra(EXTRA_TOKEN) ?: return START_NOT_STICKY
                startForeground(NOTIF_ID, notification("Starting USB serial bridge…"))
                if (worker?.isAlive != true) {
                    running = true
                    worker = Thread({ runBridge(token) }, "cloudy-serial-bridge").apply { start() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runBridge(token: String) {
        var connection: UsbDeviceConnection? = null
        var socket: Socket? = null
        try {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val device = SerialDevicePicker.pick(usb)
                ?: error("no serial-capable USB device attached")
            if (!usb.hasPermission(device)) error("USB permission not granted")
            connection = usb.openDevice(device) ?: error("openDevice failed")
            val dataIface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }
                ?: error("CDC data interface not found")
            if (!connection.claimInterface(dataIface, true)) error("CDC interface busy")
            val epIn = (0 until dataIface.endpointCount).map { dataIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
                ?: error("CDC IN endpoint missing")
            val epOut = (0 until dataIface.endpointCount).map { dataIface.getEndpoint(it) }
                .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
                ?: error("CDC OUT endpoint missing")

            state = "waiting for guest bridge"
            notify("Waiting for the guest bridge…")
            while (running && socket == null) {
                socket = try {
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", GUEST_PORT), 2000) }
                } catch (e: Exception) {
                    Thread.sleep(2000)
                    null
                }
            }
            val sock = socket ?: return
            val out = sock.getOutputStream()
            out.write("CLOUDY-SERIAL 1 $token\n".toByteArray())
            out.flush()
            val input = sock.getInputStream()
            val reply = readLine(input)
            if (reply != "OK") error("guest rejected the bridge token")
            state = "connected"
            notify("USB serial bridge connected")
            Log.i(TAG, "bridge connected; CDC claimed")

            val cdcToGuest = Thread({
                val buf = ByteArray(4096)
                try {
                    while (running) {
                        val n = connection.bulkTransfer(epIn, buf, buf.size, 200)
                        if (n > 0) writeFrame(out, TYPE_DATA_APP, buf.copyOf(n))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "CDC read ended: ${e.message}")
                }
            }, "cloudy-cdc-read")
            cdcToGuest.start()

            while (running) {
                val type = input.read()
                if (type < 0) break
                val hi = input.read()
                val lo = input.read()
                if (hi < 0 || lo < 0) break
                val len = (hi shl 8) or lo
                val payload = ByteArray(len)
                var off = 0
                while (off < len) {
                    val n = input.read(payload, off, len - off)
                    if (n < 0) break
                    off += n
                }
                when (type) {
                    TYPE_DATA_GUEST -> {
                        var sent = 0
                        while (sent < payload.size) {
                            val chunk = minOf(payload.size - sent, 4096)
                            val n = connection.bulkTransfer(epOut,
                                payload.copyOfRange(sent, sent + chunk), chunk, 500)
                            if (n <= 0) break
                            sent += n
                        }
                    }
                    TYPE_CONTROL -> if (payload.isNotEmpty()) {
                        val bits = payload[0].toInt()
                        val value = (bits and 0x01) or (((bits shr 1) and 0x01) shl 1)
                        connection.controlTransfer(0x21, 0x22, value, 0, null, 0, 300)
                        Log.i(TAG, "control bitmap=0x%02x dtr=%d rts=%d".format(
                            bits, value and 1, (value shr 1) and 1))
                    }
                    TYPE_PING -> Unit
                }
            }
            state = "guest disconnected"
            notify("Guest bridge disconnected; waiting…")
        } catch (e: Exception) {
            state = "error: ${e.message}"
            notify("USB serial bridge: ${e.message}")
            Log.w(TAG, "bridge failed", e)
        } finally {
            try { connection?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    @Synchronized
    private fun writeFrame(out: OutputStream, type: Int, payload: ByteArray) {
        out.write(byteArrayOf(type.toByte(), (payload.size shr 8).toByte(), payload.size.toByte()))
        out.write(payload)
        out.flush()
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle("Cloudy Code")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        state = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CloudySerial"
        private const val CHANNEL = "cloudy-serial"
        private const val NOTIF_ID = 41
        private const val ESPRESSIF_VID = 0x303A
        const val GUEST_PORT = 7001
        const val ACTION_START = "dev.gbcode.app.serial.START"
        const val ACTION_STOP = "dev.gbcode.app.serial.STOP"
        const val EXTRA_TOKEN = "token"
        const val TYPE_DATA_APP = 0x01
        const val TYPE_DATA_GUEST = 0x02
        const val TYPE_CONTROL = 0x03
        const val TYPE_PING = 0x04

        /** Last state for the USB screen (polled). */
        @Volatile
        var state: String = "stopped"
    }
}
