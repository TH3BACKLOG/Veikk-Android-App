package com.example.veikkreader

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Parses key names ("ctrl+shift+z") into Android keycodes. */
object Keys {
    private val names: Map<String, Int> = HashMap<String, Int>().apply {
        put("ctrl", 113); put("control", 113); put("shift", 59); put("alt", 57); put("meta", 117); put("win", 117)
        put("space", 62); put("enter", 66); put("return", 66); put("esc", 111); put("escape", 111)
        put("tab", 61); put("backspace", 67); put("delete", 112); put("del", 112)
        put("up", 19); put("down", 20); put("left", 21); put("right", 22)
        put("pageup", 92); put("pagedown", 93)
        put("[", 71); put("]", 72); put("-", 69); put("=", 70); put(",", 55); put(".", 56)
        put("/", 76); put(";", 74); put("'", 75); put("\\", 73); put("plus", 81)
        put("back", 4); put("home", 3); put("recents", 187); put("volup", 24); put("voldown", 25)
        for (i in 0 until 26) put(('a' + i).toString(), 29 + i)
        for (i in 0..9) put(i.toString(), 7 + i)
        for (i in 1..12) put("f$i", 130 + i)
    }

    val presets = listOf(
        "ctrl+z", "ctrl+shift+z", "ctrl+y", "ctrl+s", "ctrl+c", "ctrl+v", "ctrl+a",
        "space", "enter", "esc", "tab", "backspace", "delete", "b", "e", "[", "]"
    )

    /** Returns the stored action code ("KEY:113+54|ctrl+z") or null if a name isn't understood. */
    fun toCode(text: String): String? {
        val parts = text.trim().lowercase().split("+").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val codes = ArrayList<Int>()
        for (p in parts) codes.add(names[p] ?: return null)
        return "KEY:" + codes.joinToString("+") + "|" + parts.joinToString("+")
    }
}

/** Sends key presses to whatever app is in front, through Shizuku (a helper app that grants shell access). */
object KeySender {
    fun running(): Boolean = try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    fun granted(): Boolean = try {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) { false }

    fun requestPermission() {
        try { Shizuku.requestPermission(1) } catch (t: Throwable) { }
    }

    fun send(codes: List<Int>) {
        Thread {
            try {
                val cmd: Array<String> =
                    if (codes.size == 1) arrayOf("input", "keyevent", codes[0].toString())
                    else (listOf("input", "keycombination") + codes.map { it.toString() }).toTypedArray()
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                val p = m.invoke(null, cmd, null as Array<String>?, null as String?) as Process
                p.waitFor()
            } catch (t: Throwable) {
            }
        }.start()
    }
}
