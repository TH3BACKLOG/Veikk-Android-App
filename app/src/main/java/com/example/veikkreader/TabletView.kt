package com.example.veikkreader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Drawing surface. Fed by tablet samples (addSample) and, as a fallback, by the phone's own
 * touch / stylus / mouse input, so it also works if Android already treats the tablet as a pointer.
 */
class TabletView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.CYAN
        strokeWidth = 3f
    }
    private var lx = 0f
    private var ly = 0f
    private var wasDown = false
    private var cx = -1f
    private var cy = -1f
    private var cDown = false

    /** Quarter-turns applied to tablet coordinates (0..3), to match how the tablet is held. */
    var turn = 0

    init {
        setBackgroundColor(0xFF1A1A1A.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        val nb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp?.let { Canvas(nb).drawBitmap(it, 0f, 0f, null) }
        bmp = nb
        bc = Canvas(nb)
    }

    fun clear() {
        bmp?.eraseColor(Color.TRANSPARENT)
        wasDown = false
        invalidate()
    }

    private var lastUsb = 0L

    fun addSample(s: PenSample) {
        lastUsb = System.currentTimeMillis()
        val (nx, ny) = when (turn and 3) {
            0 -> s.x to s.y
            1 -> (1 - s.y) to s.x
            2 -> (1 - s.x) to (1 - s.y)
            else -> s.y to (1 - s.x)
        }
        pen(nx * width, ny * height, s.pressure, s.down)
    }

    private fun pen(x: Float, y: Float, pressure: Float, down: Boolean) {
        val c = bc ?: return
        if (down) {
            stroke.strokeWidth = 2f + pressure * 24f
            if (wasDown) c.drawLine(lx, ly, x, y, stroke) else c.drawPoint(x, y, stroke)
            lx = x
            ly = y
        }
        wasDown = down
        cx = x
        cy = y
        cDown = down
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (cx >= 0) {
            cursor.style = if (cDown) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawCircle(cx, cy, 10f, cursor)
        }
    }

    private fun handle(e: MotionEvent) {
        // While real tablet data is flowing, ignore the system's mouse/pen pointer events
        // (the tablet also shows up as a mouse, which would yank the cursor back).
        val usbLive = System.currentTimeMillis() - lastUsb < 3000
        if (usbLive && e.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return
        val down = e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE
        pen(e.x, e.y, e.pressure.coerceIn(0f, 1f), down)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        handle(e)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        handle(e)
        return true
    }
}
