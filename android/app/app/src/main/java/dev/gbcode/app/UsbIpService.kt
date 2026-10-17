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
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * USB/IP exporter (host half) for arbitrary-USB forwarding.
 *
 * The app connects out to the guest relay (Terminal-forwarded 127.0.0.1:3241),
 * authenticates, then serves USB/IP over multiplexed streams: one stream per
 * local `usbip` client in the VM. Implements DEVLIST, IMPORT and URB submit for
 * control/bulk/interrupt transfers (no isochronous). The guest needs
 * `vhci_hcd`; `usbip attach -r 127.0.0.1 -b <busid>` then exposes the device.
 *
 * This is the transparent lane: unmodified Linux drivers and tools (esptool,
 * OpenOCD, PlatformIO) use the guest's /dev nodes.
 */
class UsbIpService : Service() {
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null
    private val sendLock = Any()
    private val sessions = ConcurrentHashMap<Int, UsbIpSession>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "USB/IP forwarding", NotificationManager.IMPORTANCE_LOW))
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
                startForeground(NOTIF_ID, notification("Starting USB/IP forwarding…"))
                if (worker?.isAlive != true) {
                    running = true
                    worker = Thread({ runExport(token) }, "cloudy-usbip").apply { start() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runExport(token: String) {
        var socket: Socket? = null
        try {
            val usb = getSystemService(Context.USB_SERVICE) as UsbManager
            val device = usb.deviceList.values.firstOrNull { it.vendorId == ESPRESSIF_VID }
                ?: error("no Espressif USB device attached")
            if (!usb.hasPermission(device)) error("USB permission not granted")
            state = "waiting for guest relay"
            notify("Waiting for the guest relay…")
            while (running && socket == null) {
                socket = try {
                    Socket().apply { connect(InetSocketAddress("127.0.0.1", RELAY_PORT), 2000) }
                } catch (e: Exception) {
                    Thread.sleep(2000)
                    null
                }
            }
            val sock = socket ?: return
            val output = sock.getOutputStream()
            out = output
            output.write("CLOUDY-USBIP 1 $token\n".toByteArray())
            output.flush()
            val input = sock.getInputStream()
            if (readLine(input) != "OK") error("guest relay rejected the token")
            state = "connected"
            notify("USB/IP exporter connected")
            Log.i(TAG, "relay authenticated; exporting ${device.productName ?: device.deviceName}")
            val head = ByteArray(7)
            while (running) {
                if (!readFully(input, head)) break
                val buf = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
                val sid = buf.int
                val type = buf.get().toInt() and 0xff
                val len = buf.short.toInt() and 0xffff
                val payload = ByteArray(len)
                if (len > 0 && !readFully(input, payload)) break
                when (type) {
                    MUX_OPEN -> {
                        val session = UsbIpSession(sid, device, usb, this::sendStream)
                        sessions[sid] = session
                        Log.i(TAG, "stream $sid opened")
                    }
                    MUX_DATA -> sessions[sid]?.onData(payload)
                    MUX_CLOSE -> {
                        sessions.remove(sid)?.close()
                        Log.i(TAG, "stream $sid closed")
                    }
                }
            }
            state = "guest disconnected"
            notify("Guest relay disconnected; waiting…")
        } catch (e: Exception) {
            state = "error: ${e.message}"
            notify("USB/IP forwarding: ${e.message}")
            Log.w(TAG, "usbip exporter failed", e)
        } finally {
            sessions.values.forEach { it.close() }
            sessions.clear()
            out = null
            try { socket?.close() } catch (_: Exception) {}
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sendStream(sid: Int, type: Int, payload: ByteArray) {
        val stream = out
        Log.i(TAG, "send sid=$sid type=$type len=${payload.size} out=${stream != null}")
        if (stream == null) return
        synchronized(sendLock) {
            try {
                val head = ByteBuffer.allocate(7).order(ByteOrder.BIG_ENDIAN)
                    .putInt(sid).put(type.toByte()).putShort(payload.size.toShort())
                stream.write(head.array())
                if (payload.isNotEmpty()) stream.write(payload)
                stream.flush()
                Log.i(TAG, "sent sid=$sid")
            } catch (e: Exception) {
                Log.w(TAG, "send failed: ${e.message}")
            }
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

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun notification(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, dev.gbcode.app.ui.UsbInventoryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Cloudy Code")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .addAction(0, "USB devices", open)
            .setContentIntent(open)
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        state = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CloudyUsbIp"
        private const val CHANNEL = "cloudy-usbip"
        private const val NOTIF_ID = 42
        private const val ESPRESSIF_VID = 0x303A
        const val RELAY_PORT = 3241
        const val MUX_OPEN = 1
        const val MUX_DATA = 2
        const val MUX_CLOSE = 3
        const val ACTION_START = "dev.gbcode.app.usbip.START"
        const val ACTION_STOP = "dev.gbcode.app.usbip.STOP"
        const val EXTRA_TOKEN = "token"

        @Volatile
        var state: String = "stopped"
    }
}

/**
 * One USB/IP client connection (a mux stream). Parses op headers and URBs,
 * executes transfers against the claimed device, writes replies as mux data.
 */
class UsbIpSession(
    private val streamId: Int,
    private val device: UsbDevice,
    private val usb: UsbManager,
    private val send: (Int, Int, ByteArray) -> Unit,
) {
    private val buffer = java.io.ByteArrayOutputStream()
    private var imported = false
    private var closed = false
    private var connection: UsbDeviceConnection? = null
    private var claimed: MutableList<android.hardware.usb.UsbInterface> = mutableListOf()
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "cloudy-usbip-urb").apply { isDaemon = true }
    }

    @Synchronized
    fun onData(bytes: ByteArray) {
        if (closed) return
        Log.i(TAG, "session $streamId data ${bytes.size}B")
        buffer.write(bytes)
        try {
            loop()
        } catch (e: Exception) {
            Log.w(TAG, "session parse failed", e)
            close()
        }
    }

    private fun loop() {
        while (true) {
            val data = buffer.toByteArray()
            if (!imported) {
                if (data.size < 8) return
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                val version = buf.short.toInt() and 0xffff
                val code = buf.short.toInt() and 0xffff
                if (version != VERSION) {
                    Log.w(TAG, "unsupported usbip version 0x%04x".format(version))
                    close()
                    return
                }
                when (code) {
                    OP_REQ_DEVLIST -> {
                        trim(8)
                        val reply = devlistReply()
                        Log.i(TAG, "session $streamId devlist reply ${reply.size}B")
                        send(streamId, UsbIpService.MUX_DATA, reply)
                    }
                    OP_REQ_IMPORT -> {
                        if (data.size < 8 + 32) return
                        val busid = String(data, 8, 32, Charsets.US_ASCII).trimEnd('\u0000')
                        trim(8 + 32)
                        if (busid != BUSID) {
                            send(streamId, UsbIpService.MUX_DATA, importErrorReply())
                            close()
                            return
                        }
                        if (!claim()) {
                            send(streamId, UsbIpService.MUX_DATA, importErrorReply())
                            close()
                            return
                        }
                        imported = true
                        send(streamId, UsbIpService.MUX_DATA, importReply())
                        Log.i(TAG, "session $streamId imported $busid")
                    }
                    else -> {
                        Log.w(TAG, "unexpected op 0x%04x".format(code))
                        close()
                        return
                    }
                }
            } else {
                if (data.size < 48) return
                val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
                val command = buf.int
                when (command) {
                    CMD_SUBMIT -> {
                        val seqnum = buf.int
                        val devid = buf.int
                        val direction = buf.int
                        val ep = buf.int
                        buf.int // transfer_flags
                        val length = buf.int
                        buf.int // start_frame
                        val packets = buf.int
                        buf.int // interval
                        val setup = ByteArray(8).also { buf.get(it) }
                        val outLen = if (direction == 0) length else 0
                        val total = 48 + outLen
                        if (data.size < total) return
                        val outData = if (outLen > 0) data.copyOfRange(48, total) else ByteArray(0)
                        trim(total)
                        if (packets > 0) {
                            // Isochronous is not supported by Android's host API.
                            sendRet(seqnum, direction, ep, -32, 0, ByteArray(0))
                        } else {
                            pool.execute { execute(seqnum, direction, ep, setup, outData, length) }
                        }
                    }
                    CMD_UNLINK -> {
                        val seqnum = buf.int
                        val devid = buf.int
                        val direction = buf.int
                        val ep = buf.int
                        val unlinkSeq = buf.int
                        trim(48)
                        sendUnlinkRet(seqnum, direction, ep, 0)
                    }
                    else -> {
                        Log.w(TAG, "unknown urb command $command")
                        close()
                        return
                    }
                }
            }
        }
    }

    private fun trim(n: Int) {
        val rest = buffer.toByteArray().copyOfRange(n, buffer.size())
        buffer.reset()
        buffer.write(rest)
    }

    private fun claim(): Boolean {
        return try {
            val conn = connection ?: (usb.openDevice(device) ?: return false).also { connection = it }
            var ok = true
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (conn.claimInterface(iface, true)) claimed.add(iface) else ok = false
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "claim failed: ${e.message}")
            false
        }
    }

    private fun execute(seqnum: Int, direction: Int, ep: Int, setup: ByteArray,
                        outData: ByteArray, length: Int) {
        val conn = connection ?: return
        var status = 0
        var actual = 0
        var payload = ByteArray(0)
        try {
            if (ep == 0) {
                val bmRequestType = setup[0].toInt() and 0xff
                val bRequest = setup[1].toInt() and 0xff
                val wValue = (setup[2].toInt() and 0xff) or ((setup[3].toInt() and 0xff) shl 8)
                val wIndex = (setup[4].toInt() and 0xff) or ((setup[5].toInt() and 0xff) shl 8)
                val wLength = (setup[6].toInt() and 0xff) or ((setup[7].toInt() and 0xff) shl 8)
                if (bmRequestType and 0x80 != 0) {
                    val buf = ByteArray(wLength)
                    val n = conn.controlTransfer(bmRequestType, bRequest, wValue, wIndex,
                        buf, wLength, 1500)
                    if (n >= 0) {
                        actual = n
                        payload = buf.copyOf(n)
                    } else status = -32
                } else {
                    val data = if (outData.isNotEmpty()) outData
                    else ByteArray(0)
                    val n = conn.controlTransfer(bmRequestType, bRequest, wValue, wIndex,
                        data, data.size, 1500)
                    if (n < 0) status = -32 else actual = data.size
                }
            } else {
                val epAddr = if (direction == 1) (0x80 or ep) else ep
                val endpoint = findEndpoint(epAddr)
                if (endpoint == null) {
                    status = -32
                } else if (direction == 1) {
                    val buf = ByteArray(if (length > 0) length else 512)
                    val n = conn.bulkTransfer(endpoint, buf, buf.size, 1000)
                    actual = if (n > 0) n else 0
                    payload = if (n > 0) buf.copyOf(n) else ByteArray(0)
                } else {
                    val n = conn.bulkTransfer(endpoint, outData, outData.size, 1000)
                    if (n < 0) status = -32 else actual = outData.size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "urb failed: ${e.message}")
            status = -32
        }
        sendRet(seqnum, direction, ep, status, actual, payload)
    }

    private fun findEndpoint(address: Int): UsbEndpoint? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if ((ep.address and 0xff) == (address and 0xff)) return ep
            }
        }
        return null
    }

    private fun sendRet(seqnum: Int, direction: Int, ep: Int, status: Int,
                        actual: Int, payload: ByteArray) {
        val head = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putInt(RET_SUBMIT)
            .putInt(seqnum)
            .putInt(DEVID)
            .putInt(direction)
            .putInt(ep)
            .putInt(status)
            .putInt(actual)
            .putInt(0)
            .putInt(0)
            .putInt(0)
            .array()
        send(streamId, UsbIpService.MUX_DATA, head + payload)
    }

    private fun sendUnlinkRet(seqnum: Int, direction: Int, ep: Int, status: Int) {
        val head = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putInt(RET_UNLINK)
            .putInt(seqnum)
            .putInt(DEVID)
            .putInt(direction)
            .putInt(ep)
            .putInt(status)
            .array()
        send(streamId, UsbIpService.MUX_DATA, head)
    }

    private fun devlistReply(): ByteArray {
        val b = ByteBuffer.allocate(8 + 4 + 312 + 4 * device.interfaceCount)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(VERSION.toShort())
            .putShort(OP_REP_DEVLIST.toShort())
            .putInt(0)
            .putInt(1) // ndev
        putDevice(b)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            b.put(iface.interfaceClass.toByte())
                .put(iface.interfaceSubclass.toByte())
                .put(iface.interfaceProtocol.toByte())
                .put(0.toByte())
        }
        return b.array()
    }

    private fun importReply(): ByteArray {
        val b = ByteBuffer.allocate(8 + 312).order(ByteOrder.BIG_ENDIAN)
            .putShort(VERSION.toShort())
            .putShort(OP_REP_IMPORT.toShort())
            .putInt(0)
        putDevice(b)
        return b.array()
    }

    private fun importErrorReply(): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(VERSION.toShort()).putShort(OP_REP_IMPORT.toShort()).putInt(1).array()

    private fun putDevice(b: ByteBuffer) {
        val path = byteArrayOf(0)
        b.put(path); b.put(ByteArray(255))
        val busid = BUSID.toByteArray(Charsets.US_ASCII)
        b.put(busid); b.put(ByteArray(32 - busid.size))
        b.putInt(1) // busnum
        b.putInt(1) // devnum
        b.putInt(2) // speed: full (12 Mbps)
        b.putShort(device.vendorId.toShort())
        b.putShort(device.productId.toShort())
        // UsbDevice.getVersion() is a string like "1.01" (not bcdDevice).
        val bcd = device.version.split('.').let { parts ->
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            ((major shl 8) or minor) and 0xffff
        }
        b.putShort(bcd.toShort())
        b.put(device.deviceClass.toByte())
        b.put(device.deviceSubclass.toByte())
        b.put(device.deviceProtocol.toByte())
        b.put(1.toByte()) // bConfigurationValue
        b.put(1.toByte()) // bNumConfigurations
        b.put(device.interfaceCount.toByte())
    }

    fun close() {
        if (closed) return
        closed = true
        pool.shutdownNow()
        try {
            val conn = connection
            claimed.forEach { iface -> try { conn?.releaseInterface(iface) } catch (_: Exception) {} }
            conn?.close()
        } catch (_: Exception) {}
        connection = null
        claimed.clear()
    }

    companion object {
        private const val TAG = "CloudyUsbIp"
        private const val VERSION = 0x0111
        private const val OP_REQ_DEVLIST = 0x8005
        private const val OP_REP_DEVLIST = 0x0005
        private const val OP_REQ_IMPORT = 0x8003
        private const val OP_REP_IMPORT = 0x0003
        private const val CMD_SUBMIT = 0x00000001
        private const val CMD_UNLINK = 0x00000002
        private const val RET_SUBMIT = 0x00000003
        private const val RET_UNLINK = 0x00000004
        private const val DEVID = 0x00010001
        private const val BUSID = "cloudy-303a-1001"
    }
}
