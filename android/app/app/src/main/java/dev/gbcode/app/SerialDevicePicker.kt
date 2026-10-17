package dev.gbcode.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Select a USB device for serial forwarding. Prefer Espressif boards, then
 * composite multi-UART devices (e.g. SLogic UARTx4 with 8 interfaces), then
 * any device exposing a CDC data interface. Internal laptop devices (hub,
 * camera, FPMCU) are excluded by VID.
 */
object SerialDevicePicker {
    private const val ESPRESSIF_VID = 0x303A
    private val INTERNAL_VIDS = setOf(0x1D6B, 0x18D1, 0x0BDA)

    fun pick(usb: UsbManager): UsbDevice? {
        val devices = usb.deviceList.values
        devices.firstOrNull { it.vendorId == ESPRESSIF_VID && hasCdc(it) }?.let { return it }
        devices.filter { it.vendorId !in INTERNAL_VIDS && it.interfaceCount >= 4 }
            .maxByOrNull { it.interfaceCount }?.let { return it }
        devices.firstOrNull { it.vendorId !in INTERNAL_VIDS && hasCdc(it) }?.let { return it }
        return null
    }

    private fun hasCdc(device: UsbDevice): Boolean = try {
        (0 until device.interfaceCount).any {
            device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_CDC_DATA
        }
    } catch (e: SecurityException) {
        false
    } catch (e: Exception) {
        false
    }
}
