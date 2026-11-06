package com.example.veikkreader

import android.content.Context

/** Things a tablet button can do inside the app. */
enum class Act(val label: String) {
    CLEAR("Clear canvas"),
    UNDO("Undo last stroke"),
    ROTATE("Rotate view"),
    BRUSH_UP("Brush bigger"),
    BRUSH_DOWN("Brush smaller"),
    ERASER("Eraser on/off"),
    COLOR("Next color")
}

/**
 * How to recognise one tablet button in the raw reports.
 * payload != null: the whole report must equal that payload (buttons that send their own report).
 * otherwise: (byte[pos] and care) == value (buttons that flip bits inside a busy report).
 */
data class ButtonSig(
    val iface: Int,
    val len: Int,
    val id: Int,      // first byte required to equal this, -1 = not used
    val pos: Int,
    val care: Int,
    val value: Int,
    val payload: String?
) {
    fun sameGroup(i: Int, d: ByteArray) =
        i == iface && d.size == len && (id < 0 || (d[0].toInt() and 0xFF) == id)

    fun matches(d: ByteArray): Boolean {
        if (payload != null) return hexOf(d) == payload
        if (pos < 0 || pos >= d.size) return false
        return ((d[pos].toInt() and 0xFF) and care) == value
    }

    fun describe(): String =
        if (payload != null) "if$iface report $payload"
        else "if$iface byte $pos = 0x" + "%02X".format(value)

    fun toText(): String =
        listOf(iface, len, id, pos, care, value).joinToString(",") + "," + (payload ?: "-")

    companion object {
        fun hexOf(d: ByteArray): String = d.joinToString(" ") { "%02X".format(it) }

        fun fromText(t: String): ButtonSig? {
            val p = t.split(",")
            if (p.size < 7) return null
            val n = IntArray(6)
            for (k in 0 until 6) n[k] = p[k].trim().toIntOrNull() ?: return null
            val pl = p[6].trim()
            return ButtonSig(n[0], n[1], n[2], n[3], n[4], n[5], if (pl == "-" || pl.isEmpty()) null else pl)
        }
    }
}

class ButtonMapper(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("buttons", Context.MODE_PRIVATE)
    private val lk = Any()
    private val map = ArrayList<Pair<ButtonSig, Act>>()
    private val active = HashMap<ButtonSig, Boolean>()

    private var phase = 0 // 0 = normal, 1 = recording idle baseline, 2 = recording button presses
    private val base = HashMap<String, IntArray>()
    private val counts = HashMap<String, Int>()
    private val sigs = HashMap<String, ButtonSig>()

    init {
        val txt = prefs.getString("map", "") ?: ""
        for (line in txt.split("\n")) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val act = runCatching { Act.valueOf(line.substring(0, eq)) }.getOrNull() ?: continue
            val sig = ButtonSig.fromText(line.substring(eq + 1)) ?: continue
            map.add(Pair(sig, act))
        }
    }

    private fun save() {
        val s = map.joinToString("\n") { it.second.name + "=" + it.first.toText() }
        prefs.edit().putString("map", s).apply()
    }

    fun snapshot(): List<Pair<ButtonSig, Act>> = synchronized(lk) { ArrayList(map) }

    fun add(sig: ButtonSig, act: Act) {
        synchronized(lk) {
            map.removeAll { it.first == sig }
            map.add(Pair(sig, act))
            active.clear()
            save()
        }
    }

    fun remove(sig: ButtonSig) {
        synchronized(lk) {
            map.removeAll { it.first == sig }
            active.clear()
            save()
        }
    }

    fun clearAll() {
        synchronized(lk) {
            map.clear()
            active.clear()
            save()
        }
    }

    fun startBaseline() {
        synchronized(lk) {
            base.clear(); counts.clear(); sigs.clear()
            phase = 1
        }
    }

    fun startPress() {
        synchronized(lk) { phase = 2 }
    }

    fun cancel() {
        synchronized(lk) { phase = 0 }
    }

    /** Ends recording and returns the most convincing button signature, or null. */
    fun finishPress(): ButtonSig? {
        return synchronized(lk) {
            phase = 0
            var best: ButtonSig? = null
            var bestScore = -1
            for ((key, sig) in sigs) {
                val c = counts[key] ?: 0
                if (c < 2) continue
                val bonus = if (sig.payload != null) {
                    sig.payload.split(" ").count { it != "00" }
                } else if (sig.pos > 0) 1 else 0
                val score = c * 10 + bonus
                if (score > bestScore) {
                    bestScore = score
                    best = sig
                }
            }
            best
        }
    }

    /** True if this report belongs to a button that sends its own reports (so it must not move the pen). */
    fun isButtonReport(iface: Int, d: ByteArray): Boolean {
        if (d.isEmpty()) return false
        return synchronized(lk) {
            phase == 0 && map.any { it.first.payload != null && it.first.sameGroup(iface, d) }
        }
    }

    private fun gkey(iface: Int, d: ByteArray, useId: Boolean): String =
        "$iface/${d.size}" + if (useId) "/" + (d[0].toInt() and 0xFF) else ""

    private fun isPenByte(i: Int, c: ParserConfig): Boolean =
        i == c.x || i == c.x + 1 || i == c.y || i == c.y + 1 || (c.p >= 0 && (i == c.p || i == c.p + 1))

    /** Feed every raw report here. Returns the actions to run (button just went down). */
    fun onReport(iface: Int, d: ByteArray, cfg: ParserConfig): List<Act> {
        if (d.isEmpty()) return emptyList()
        return synchronized(lk) {
            val useId = cfg.id >= 0
            if (phase == 1) {
                val a = base.getOrPut(gkey(iface, d, useId)) { IntArray(d.size) }
                for (i in d.indices) a[i] = a[i] or (d[i].toInt() and 0xFF)
                return@synchronized emptyList<Act>()
            }
            if (phase == 2) {
                learnPress(iface, d, cfg)
                return@synchronized emptyList<Act>()
            }
            val out = ArrayList<Act>()
            for ((sig, act) in map) {
                if (!sig.sameGroup(iface, d)) continue
                val m = sig.matches(d)
                val was = active[sig] == true
                if (m && !was) out.add(act)
                active[sig] = m
            }
            out as List<Act>
        }
    }

    private fun learnPress(iface: Int, d: ByteArray, cfg: ParserConfig) {
        val useId = cfg.id >= 0
        val idv = if (useId) (d[0].toInt() and 0xFF) else -1
        val g = gkey(iface, d, useId)
        val a = base[g]
        if (a == null) {
            val hex = ButtonSig.hexOf(d)
            val key = "N|$g|$hex"
            counts[key] = (counts[key] ?: 0) + 1
            sigs[key] = ButtonSig(iface, d.size, idv, -1, 0, 0, hex)
            return
        }
        val penGroup = (cfg.len == 0 || cfg.len == d.size) &&
            (cfg.id < 0 || cfg.id == (d[0].toInt() and 0xFF))
        for (i in d.indices) {
            if (useId && i == 0) continue
            if (penGroup && isPenByte(i, cfg)) continue
            val care = a[i].inv() and 0xFF
            val v = (d[i].toInt() and 0xFF) and care
            if (v == 0) continue
            val key = "B|$g|$i|$v"
            counts[key] = (counts[key] ?: 0) + 1
            sigs[key] = ButtonSig(iface, d.size, idv, i, care, v, null)
        }
    }
}
