package com.example.veikkreader

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock

/**
 * Talks to the tablet over Android's USB host API: asks permission, takes over the HID
 * interface(s) from the system, and streams every raw interrupt-IN report to the listener.
 * Listener callbacks arrive on background threads.
 */
class UsbTabletReader(private val ctx: Context, private val l: Listener) {

    interface Listener {
        fun onStatus(msg: String)
        fun onDescriptor(ifaceId: Int, d: ByteArray)
        fun onReport(data: ByteArray, ifaceId: Int)
    }

    companion object {
        const val ACTION_PERMISSION = "com.example.veikkreader.USB_PERMISSION"

        @Suppress("DEPRECATION")
        fun deviceFrom(i: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)

        fun label(d: UsbDevice): String {
            val name = runCatching { d.productName }.getOrNull() ?: ""
            return "%04X:%04X %s".format(d.vendorId, d.productId, name).trim()
        }
    }

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var running = false
    private var conn: UsbDeviceConnection? = null
    private val claimed = ArrayList<UsbInterface>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    val dev = deviceFrom(i)
                    if (dev != null && i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(dev)
                    else l.onStatus("USB permission was denied")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> if (running) {
                    close()
                    l.onStatus("Tablet unplugged")
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val f = IntentFilter(ACTION_PERMISSION).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(receiver, f)
        registered = true
    }

    fun unregister() {
        if (registered) {
            ctx.unregisterReceiver(receiver)
            registered = false
        }
        close()
    }

    /** Non-hub USB devices currently plugged in, likely VEIKK first. */
    fun listDevices(): List<UsbDevice> =
        usb.deviceList.values
            .filter { it.deviceClass != UsbConstants.USB_CLASS_HUB }
            .sortedBy { if (it.vendorId == 0x2FEB) 0 else 1 }

    fun request(dev: UsbDevice) {
        if (usb.hasPermission(dev)) {
            open(dev)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        usb.requestPermission(dev, pi)
        l.onStatus("Waiting for USB permission…")
    }

    @Synchronized
    fun open(dev: UsbDevice) {
        close()
        val c = usb.openDevice(dev)
        if (c == null) {
            l.onStatus("Could not open ${label(dev)}")
            return
        }
        conn = c
        running = true
        val notes = StringBuilder()

        // Some tablets only start sending pen data after the vendor string descriptors are read.
        // Harmless if the device doesn't have them.
        for (idx in intArrayOf(100, 200)) {
            val b = ByteArray(256)
            val n = c.controlTransfer(0x80, 0x06, 0x0300 or idx, 0x0409, b, b.size, 300)
            notes.append("str$idx=$n ")
        }

        var endpoints = 0
        for (i in 0 until dev.interfaceCount) {
            val itf = dev.getInterface(i)
            val ins = ArrayList<UsbEndpoint>()
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) ins.add(ep)
            }
            if (ins.isEmpty()) continue
            // force = true: detach Android's own HID driver so we get the raw reports
            if (!c.claimInterface(itf, true)) {
                notes.append("iface${itf.id}:claim-failed ")
                continue
            }
            claimed.add(itf)
            if (itf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                val d = ByteArray(2048)
                val n = c.controlTransfer(0x81, 0x06, 0x2200, itf.id, d, d.size, 1000) // GET_DESCRIPTOR: HID report
                if (n > 0) l.onDescriptor(itf.id, d.copyOf(n))
                c.controlTransfer(0x21, 0x0A, 0, itf.id, null, 0, 300) // HID SET_IDLE
            }
            for (ep in ins) {
                endpoints++
                Thread { loop(c, ep, itf.id) }.apply { isDaemon = true }.start()
            }
        }

        if (endpoints == 0) {
            close()
            l.onStatus("${label(dev)}: no readable HID endpoints. $notes")
        } else {
            l.onStatus("Connected to ${label(dev)} ($endpoints endpoint${if (endpoints > 1) "s" else ""}). Move the pen over the tablet. $notes")
        }
    }

    private fun loop(c: UsbDeviceConnection, ep: UsbEndpoint, ifaceId: Int) {
        val buf = ByteArray(maxOf(ep.maxPacketSize, 64))
        try {
            while (running && conn === c) {
                val t0 = SystemClock.uptimeMillis()
                val n = c.bulkTransfer(ep, buf, buf.size, 100)
                if (n > 0) l.onReport(buf.copyOf(n), ifaceId)
                else if (n < 0 && SystemClock.uptimeMillis() - t0 < 10) Thread.sleep(10) // avoid spinning on errors
            }
        } catch (_: Throwable) {
        }
    }

    @Synchronized
    fun close() {
        running = false
        val c = conn
        if (c != null) {
            for (i in claimed) runCatching { c.releaseInterface(i) }
            runCatching { c.close() }
        }
        claimed.clear()
        conn = null
    }
}
