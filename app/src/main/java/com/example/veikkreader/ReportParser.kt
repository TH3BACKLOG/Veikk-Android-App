package com.example.veikkreader

/** Where things live inside one raw HID report from the tablet. -1 / 0 mean "don't filter" / "auto". */
data class ParserConfig(
    var id: Int = -1,          // required first byte (report ID), -1 = any
    var len: Int = 0,          // required report length, 0 = any
    var x: Int = 2,            // byte offset of 16-bit little-endian X
    var y: Int = 4,            // byte offset of 16-bit little-endian Y
    var p: Int = 6,            // byte offset of 16-bit little-endian pressure, -1 = none
    var xMax: Int = 0,         // 0 = auto-range from values seen so far
    var yMax: Int = 0,
    var pMax: Int = 0,
    var tipOff: Int = 1,       // status byte holding the tip bit
    var tipMask: Int = 1,
    var useTip: Boolean = false // true = pen is "down" when tip bit set, instead of pressure > 0
) {
    fun toText() =
        "id=$id len=$len x=$x y=$y p=$p xmax=$xMax ymax=$yMax pmax=$pMax " +
            "tipoff=$tipOff tipmask=$tipMask usetip=${if (useTip) 1 else 0}"

    companion object {
        fun fromText(t: String, base: ParserConfig): ParserConfig {
            val c = base.copy()
            for (kv in t.trim().split(Regex("\\s+"))) {
                val parts = kv.split("=", limit = 2)
                if (parts.size != 2) continue
                val n = runCatching { Integer.decode(parts[1]) }.getOrNull() ?: continue
                when (parts[0].lowercase()) {
                    "id" -> c.id = n
                    "len" -> c.len = n
                    "x" -> c.x = n
                    "y" -> c.y = n
                    "p" -> c.p = n
                    "xmax" -> c.xMax = n
                    "ymax" -> c.yMax = n
                    "pmax" -> c.pMax = n
                    "tipoff" -> c.tipOff = n
                    "tipmask" -> c.tipMask = n
                    "usetip" -> c.useTip = n != 0
                }
            }
            return c
        }
    }
}

/** One decoded pen position. x, y, pressure are all 0..1. */
data class PenSample(val x: Float, val y: Float, val pressure: Float, val down: Boolean)

class ReportParser(var cfg: ParserConfig) {
    private var seenX = 1
    private var seenY = 1
    private var seenP = 1

    fun reset() {
        seenX = 1; seenY = 1; seenP = 1
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Synchronized
    fun parse(b: ByteArray): PenSample? {
        val c = cfg
        if (c.id >= 0 && (b.isEmpty() || (b[0].toInt() and 0xFF) != c.id)) return null
        if (c.len > 0 && b.size != c.len) return null
        if (c.x < 0 || c.y < 0 || c.x + 1 >= b.size || c.y + 1 >= b.size) return null

        val rx = u16(b, c.x)
        val ry = u16(b, c.y)
        val hasP = c.p >= 0 && c.p + 1 < b.size
        val rp = if (hasP) u16(b, c.p) else 0
        if (rx > seenX) seenX = rx
        if (ry > seenY) seenY = ry
        if (rp > seenP) seenP = rp

        val mx = if (c.xMax > 0) c.xMax else seenX
        val my = if (c.yMax > 0) c.yMax else seenY
        val mp = if (c.pMax > 0) c.pMax else seenP

        val tip = c.tipOff in b.indices && (b[c.tipOff].toInt() and c.tipMask) != 0
        val down = if (c.useTip || !hasP) tip else rp > mp / 50
        return PenSample(
            (rx.toFloat() / mx).coerceIn(0f, 1f),
            (ry.toFloat() / my).coerceIn(0f, 1f),
            if (hasP) (rp.toFloat() / mp).coerceIn(0f, 1f) else 1f,
            down
        )
    }
}

/**
 * Guesses the report layout from a sweep of the pen over the tablet:
 * finds the dominant report type, then the first three 16-bit fields whose low byte varies a lot.
 */
object Learner {
    fun learn(samples: List<ByteArray>): ParserConfig? {
        val usable = samples.filter { it.size >= 6 }
        if (usable.size < 20) return null

        val distinctIds = usable.map { it[0].toInt() and 0xFF }.distinct().size
        val useId = distinctIds <= 4
        val groups = usable.groupBy { Pair(it.size, if (useId) it[0].toInt() and 0xFF else -1) }
        val best = groups.maxByOrNull { it.value.size } ?: return null
        val len = best.key.first
        val id = best.key.second
        val g = best.value

        fun range(o: Int): Int {
            var lo = 255
            var hi = 0
            for (r in g) {
                val v = r[o].toInt() and 0xFF
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            return hi - lo
        }

        fun find(from: Int, minRange: Int): Int {
            var o = from
            while (o + 1 < len) {
                if (range(o) >= minRange) return o
                o++
            }
            return -1
        }

        fun max16(o: Int) = g.maxOf { (it[o].toInt() and 0xFF) or ((it[o + 1].toInt() and 0xFF) shl 8) }

        val x = find(1, 100)
        if (x < 0) return null
        val y = find(x + 2, 100)
        if (y < 0) return null
        val p = find(y + 2, 16)

        return ParserConfig(
            id = id, len = len, x = x, y = y, p = p,
            xMax = max16(x), yMax = max16(y),
            pMax = if (p >= 0) max16(p) else 0,
            useTip = p < 0
        )
    }
}
