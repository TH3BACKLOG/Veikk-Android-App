package com.example.veikkreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), UsbTabletReader.Listener {

    private lateinit var reader: UsbTabletReader
    private lateinit var status: TextView
    private lateinit var hexView: TextView
    private lateinit var canvasView: TabletView
    private lateinit var learnBtn: Button

    private var cfg = ParserConfig()
    private val parser = ReportParser(cfg)

    private val learned = ArrayList<ByteArray>()
    @Volatile private var learning = false

    private val header = StringBuilder() // HID descriptors etc., kept for "Copy log"
    private val log = java.util.ArrayDeque<String>()
    private var count = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reader = UsbTabletReader(this, this)
        reader.register()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        status = TextView(this).apply {
            textSize = 13f
            text = "Plug the tablet into your phone (USB-C / OTG cable), then tap Connect."
        }
        root.addView(status)

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        learnBtn = btn("Learn") { toggleLearn() }
        val row1 = LinearLayout(this).apply {
            addView(btn("Connect") { connect() })
            addView(learnBtn)
            addView(btn("Fields") { editFields() })
        }
        val row2 = LinearLayout(this).apply {
            addView(btn("Rotate") { canvasView.turn = (canvasView.turn + 1) and 3 })
            addView(btn("Clear") { canvasView.clear() })
            addView(btn("Copy log") { copyLog() })
        }
        root.addView(row1)
        root.addView(row2)

        canvasView = TabletView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(6); bottomMargin = dp(6) }
        }
        root.addView(canvasView)

        hexView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            minLines = 3
            text = "Raw reports show up here."
        }
        root.addView(hexView)

        setContentView(root)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            UsbTabletReader.deviceFrom(i)?.let { reader.request(it) }
        }
    }

    override fun onDestroy() {
        reader.unregister()
        super.onDestroy()
    }

    // ---- buttons ----

    private fun connect() {
        val devs = reader.listDevices()
        when {
            devs.isEmpty() -> status.text =
                "No USB device found. Use a USB-C/OTG cable straight into the phone, " +
                    "and check the tablet is powered on and in data mode."
            devs.size == 1 -> reader.request(devs[0])
            else -> AlertDialog.Builder(this)
                .setTitle("Which one is the tablet?")
                .setItems(devs.map { UsbTabletReader.label(it) }.toTypedArray()) { _, i -> reader.request(devs[i]) }
                .show()
        }
    }

    private fun toggleLearn() {
        if (!learning) {
            synchronized(learned) { learned.clear() }
            learning = true
            learnBtn.text = "Done"
            status.text = "Learning: sweep the pen across the whole tablet, pressing lightly and hard, then tap Done."
        } else {
            learning = false
            learnBtn.text = "Learn"
            val snap = synchronized(learned) { ArrayList(learned) }
            val r = Learner.learn(snap)
            if (r == null) {
                status.text = "Not enough usable data (${snap.size} reports). Is the tablet connected and the pen moving?"
            } else {
                setConfig(r)
                status.text = "Learned: ${r.toText()}"
            }
        }
    }

    private fun setConfig(c: ParserConfig) {
        cfg = c
        parser.cfg = c
        parser.reset()
    }

    private fun editFields() {
        val et = EditText(this).apply {
            setText(cfg.toText())
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        AlertDialog.Builder(this)
            .setTitle("Report layout")
            .setMessage(
                "Byte offsets into each raw report. x/y/p are 16-bit little-endian; " +
                    "p=-1 means no pressure. xmax/ymax/pmax=0 auto-ranges. id=-1 and len=0 accept every report."
            )
            .setView(et)
            .setPositiveButton("Apply") { _, _ ->
                setConfig(ParserConfig.fromText(et.text.toString(), cfg))
                status.text = "Using: ${cfg.toText()}"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyLog() {
        val text = header.toString() + log.joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VEIKK log", text))
        status.text = "Copied ${log.size} reports + descriptors to the clipboard."
    }

    private fun addLog(line: String) {
        log.addLast(line)
        while (log.size > 400) log.removeFirst()
    }

    // ---- UsbTabletReader.Listener (background threads) ----

    override fun onStatus(msg: String) {
        runOnUiThread {
            status.text = msg
            header.append("# ").append(msg).append('\n')
        }
    }

    override fun onDescriptor(ifaceId: Int, d: ByteArray) {
        runOnUiThread {
            header.append("# HID report descriptor, interface $ifaceId (${d.size} bytes):\n# ")
                .append(hex(d)).append('\n')
        }
    }

    override fun onReport(data: ByteArray, ifaceId: Int) {
        if (learning) synchronized(learned) { if (learned.size < 3000) learned.add(data) }
        val s = parser.parse(data)
        runOnUiThread {
            count++
            val h = hex(data)
            val decoded = s?.let {
                "x=%.3f y=%.3f p=%.3f %s".format(it.x, it.y, it.pressure, if (it.down) "DOWN" else "hover")
            } ?: "(skipped by id/len filter)"
            hexView.text = "if$ifaceId #$count\n$h\n$decoded"
            addLog("if$ifaceId $h")
            if (s != null) canvasView.addSample(s)
        }
    }
}
