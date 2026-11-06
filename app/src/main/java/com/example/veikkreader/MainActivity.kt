package com.example.veikkreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.hardware.usb.UsbManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView

class MainActivity : Activity(), UsbTabletReader.Listener {

    private lateinit var reader: UsbTabletReader
    private lateinit var status: TextView
    private lateinit var hexView: TextView
    private lateinit var canvasView: TabletView
    private lateinit var learnBtn: Button

    private lateinit var mapper: ButtonMapper
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
        mapper = ButtonMapper(this)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 7)
        }
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
        val row3 = LinearLayout(this).apply {
            addView(btn("Buttons") { buttonsMenu() })
            addView(btn("Undo") { canvasView.undo() })
            addView(btn("Key setup") { keySetup() })
        }
        root.addView(row1)
        root.addView(row2)
        root.addView(row3)

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

    // ---- tablet button mapping ----

    private fun doAct(code: String) {
        val keys = Actions.keys(code)
        if (keys != null) {
            if (KeySender.granted()) KeySender.send(keys)
            else status.text = "Key not sent: open Key setup and allow Shizuku first."
            return
        }
        val a = runCatching { Act.valueOf(code) }.getOrNull() ?: return
        when (a) {
            Act.CLEAR -> canvasView.clear()
            Act.UNDO -> canvasView.undo()
            Act.ROTATE -> canvasView.turn = (canvasView.turn + 1) and 3
            Act.BRUSH_UP -> canvasView.brush = (canvasView.brush * 1.4f).coerceAtMost(6f)
            Act.BRUSH_DOWN -> canvasView.brush = (canvasView.brush / 1.4f).coerceAtLeast(0.3f)
            Act.ERASER -> canvasView.eraser = !canvasView.eraser
            Act.COLOR -> canvasView.nextColor()
        }
        status.text = "Button: " + a.label
    }

    private fun keySetup() {
        val running = KeySender.running()
        val granted = KeySender.granted()
        val msg = "To send keyboard keys to other apps, this app uses Shizuku (a free helper app).\n\n" +
            "1. Install Shizuku and start it (in Shizuku: \"Start via Wireless debugging\", Android 11+).\n" +
            "2. Tap Allow keys below.\n\n" +
            "Shizuku running: " + (if (running) "yes" else "no") + "\n" +
            "Permission for this app: " + (if (granted) "yes" else "no")
        AlertDialog.Builder(this)
            .setTitle("Key setup")
            .setMessage(msg)
            .setPositiveButton("Allow keys") { _, _ ->
                if (!running) Toast.makeText(this, "Shizuku isn't running yet.", Toast.LENGTH_LONG).show()
                else KeySender.requestPermission()
            }
            .setNeutralButton("Open Shizuku") { _, _ ->
                val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (i != null) startActivity(i)
                else Toast.makeText(this, "Shizuku isn't installed.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Test (volume up)") { _, _ ->
                if (granted) KeySender.send(listOf(24))
                else Toast.makeText(this, "Not allowed yet.", Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun buttonsMenu() {
        val list = mapper.snapshot()
        val items = ArrayList<String>()
        for (e in list) items.add(Actions.label(e.second) + "  <-  " + e.first.describe() + "   (tap to remove)")
        items.add("+ Add a button")
        items.add("Remove all")
        AlertDialog.Builder(this)
            .setTitle("Tablet buttons")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < list.size) {
                    mapper.remove(list[i].first)
                    status.text = "Removed."
                } else if (i == list.size) {
                    addMapping()
                } else {
                    mapper.clearAll()
                    status.text = "All button mappings removed."
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addMapping() {
        val acts = Act.values()
        val items = ArrayList<String>()
        items.add("Keyboard key / shortcut...")
        for (a in acts) items.add(a.label)
        AlertDialog.Builder(this)
            .setTitle("What should the button do?")
            .setItems(items.toTypedArray()) { _, i ->
                if (i == 0) pickKey() else learnButton(acts[i - 1].name)
            }
            .show()
    }

    private fun pickKey() {
        val items = ArrayList<String>(Keys.presets)
        items.add("Type my own...")
        AlertDialog.Builder(this)
            .setTitle("Which key?")
            .setItems(items.toTypedArray()) { _, i ->
                if (i < Keys.presets.size) learnButton(Keys.toCode(Keys.presets[i])!!)
                else typeKey()
            }
            .show()
    }

    private fun typeKey() {
        val et = EditText(this).apply { hint = "ctrl+shift+z"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("Type the key")
            .setMessage(
                "Join with +. Names: a-z, 0-9, f1-f12, ctrl, shift, alt, meta, space, enter, esc, tab, " +
                    "backspace, delete, up, down, left, right, pageup, pagedown, [ ] - = , . / ; ' plus, " +
                    "back, home, recents, volup, voldown"
            )
            .setView(et)
            .setPositiveButton("Next") { _, _ ->
                val code = Keys.toCode(et.text.toString())
                if (code == null) Toast.makeText(this, "Didn't understand that key.", Toast.LENGTH_LONG).show()
                else learnButton(code)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun learnButton(code: String) {
        val h = Handler(Looper.getMainLooper())
        val dlg = AlertDialog.Builder(this)
            .setTitle(Actions.label(code))
            .setMessage(
                "Make sure the tablet is connected. Hold the pen hovering just above the tablet " +
                    "(don't touch it down) and keep your hands off the buttons. Then tap Start."
            )
            .setPositiveButton("Start", null)
            .setNegativeButton("Cancel") { _, _ -> mapper.cancel() }
            .create()
        dlg.setOnDismissListener { mapper.cancel() }
        dlg.show()
        val btn = dlg.getButton(DialogInterface.BUTTON_POSITIVE)
        var stage = 0
        btn.setOnClickListener {
            if (stage == 0) {
                stage = 1
                btn.isEnabled = false
                dlg.setMessage("Step 1/2: recording the idle state. Keep the pen hovering, don't press anything...")
                mapper.startBaseline()
                h.postDelayed({
                    if (!dlg.isShowing) return@postDelayed
                    dlg.setMessage(
                        "Step 2/2: now press and release the tablet button several times (at least 3). " +
                            "Take your time, then tap Done."
                    )
                    mapper.startPress()
                    btn.text = "Done"
                    btn.isEnabled = true
                    stage = 2
                }, 2500)
            } else if (stage == 2) {
                val sig = mapper.finishPress()
                if (sig == null) {
                    dlg.setMessage(
                        "Didn't see a button. Is the tablet connected and sending data? Tap Start to try again."
                    )
                    btn.text = "Start"
                    stage = 0
                } else {
                    mapper.add(sig, code)
                    status.text = "Mapped: " + Actions.label(code) + "  <-  " + sig.describe()
                    dlg.dismiss()
                }
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
            if (msg.startsWith("Connected to")) {
                try {
                    val si = Intent(this, KeepAliveService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(si) else startService(si)
                } catch (t: Throwable) {
                }
            }
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
        val acts = mapper.onReport(ifaceId, data, cfg)
        if (acts.isNotEmpty()) runOnUiThread { for (a in acts) doAct(a) }
        val s = if (mapper.isButtonReport(ifaceId, data)) null else parser.parse(data)
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
