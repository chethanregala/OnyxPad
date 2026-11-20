package com.onyx.pad

import android.content.Context
import android.graphics.*
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

private class Btn(val bit: Int, val shape: String) {
    val r = RectF()
    val circ = shape in setOf("tri", "crs", "sqr", "cir")
}

class PadView(c: Context, private val out: (ByteArray) -> Unit, private val tapStatus: () -> Unit) : View(c) {
    var status = "REGISTERING…"
        set(v) { field = v; postInvalidate() }
    var event = ""
        set(v) { field = v; postInvalidate() }

    private val vib = c.getSystemService(Vibrator::class.java)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = .1f }
    // bits: 0 A/cross, 1 B/circle, 2 X/square, 3 Y/triangle, 4 L1, 5 R1, 6 L2, 7 R2, 8 Select, 9 Start, 12-15 dpad U/D/L/R
    private val btns = listOf(
        Btn(3, "tri"), Btn(0, "crs"), Btn(2, "sqr"), Btn(1, "cir"),
        Btn(12, "up"), Btn(13, "dn"), Btn(14, "lf"), Btn(15, "rt"),
        Btn(4, "L1"), Btn(5, "R1"), Btn(6, "L2"), Btn(7, "R2"), Btn(8, "SELECT"), Btn(9, "START"))
    private val sc = arrayOf(PointF(), PointF())
    private val axes = FloatArray(4)
    private val edge = BooleanArray(2)
    private val bp = HashMap<Int, Btn>()
    private val sp = HashMap<Int, Int>()
    private val pill = RectF()
    private var sr = 0f
    private var u = 1f
    private var mask = 0
    private var last = ByteArray(9)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        u = h / 100f; sr = 17 * u
        sc[0].set(w * .34f, 74 * u); sc[1].set(w * .66f, 74 * u)
        pill.set(w * .31f, 4 * u, w * .69f, 15 * u)
        fun put(i: Int, x: Float, y: Float, hw: Float, hh: Float) = btns[i].r.set(x - hw, y - hh, x + hw, y + hh)
        val d = 15 * u; val q = 9 * u; val cy = 45 * u; val fx = w * .84f; val dx = w * .16f
        put(0, fx, cy - d, q, q); put(1, fx, cy + d, q, q); put(2, fx - d, cy, q, q); put(3, fx + d, cy, q, q)
        put(4, dx, cy - d, q, q); put(5, dx, cy + d, q, q); put(6, dx - d, cy, q, q); put(7, dx + d, cy, q, q)
        val sw = w * .055f; val sh = 6.5f * u
        put(8, w * .215f, 10.5f * u, sw, sh); put(9, w * .785f, 10.5f * u, sw, sh)
        put(10, w * .095f, 10.5f * u, sw, sh); put(11, w * .905f, 10.5f * u, sw, sh)
        put(12, w * .43f, 42 * u, w * .045f, 4 * u); put(13, w * .57f, 42 * u, w * .045f, 4 * u)
    }

    private fun st(col: Int) { p.style = Paint.Style.STROKE; p.strokeWidth = u * .3f; p.color = col }
    private fun fl(col: Int) { p.style = Paint.Style.FILL; p.color = col }
    private fun tri(x: Float, y: Float, s: Float) = Path().apply {
        moveTo(x, y - s * 1.1f); lineTo(x + s * 1.05f, y + s * .8f); lineTo(x - s * 1.05f, y + s * .8f); close()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val grey = 0xFFCCCCCC.toInt(); val ink = 0xFF111111.toInt()
        // status pill
        val on0 = status.startsWith("CONNECTED"); val ph = pill.height()
        st(0xFF555555.toInt()); c.drawRoundRect(pill, ph / 2, ph / 2, p)
        if (on0) fl(Color.WHITE) else st(Color.WHITE)
        c.drawCircle(pill.left + ph * .5f, pill.centerY(), ph * .12f, p)
        fl(Color.WHITE); p.textSize = ph * .36f
        c.drawText(status, pill.centerX(), pill.centerY() + p.textSize * .35f, p)
        // last HID event, small gray line under the pill
        if (event.isNotEmpty()) {
            fl(0xFF888888.toInt()); p.textSize = ph * .22f
            var t = event
            val maxW = c.width() * .9f
            if (p.measureText(t) > maxW) {
                while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
                t += "…"
            }
            c.drawText(t, pill.centerX(), pill.bottom + ph * .45f, p)
        }
        // sticks
        for (s in 0..1) {
            val cx = sc[s].x; val cy = sc[s].y; val act = sp.containsValue(s)
            st(0xFF444444.toInt()); c.drawCircle(cx, cy, sr, p)
            st(0xFF222222.toInt()); c.drawCircle(cx, cy, sr * .55f, p)
            val kx = cx + axes[2 * s] * sr * .55f; val ky = cy + axes[2 * s + 1] * sr * .55f
            fl(if (act) Color.WHITE else ink); c.drawCircle(kx, ky, sr * .45f, p)
            st(if (act) Color.WHITE else grey); c.drawCircle(kx, ky, sr * .45f, p)
            fl(if (act) Color.BLACK else Color.WHITE); c.drawCircle(kx, ky, u * .8f, p)
        }
        // buttons
        for (b in btns) {
            val on = mask shr b.bit and 1 == 1
            val r = b.r; val x = r.centerX(); val y = r.centerY()
            val body = { if (b.circ) c.drawCircle(x, y, r.width() / 2, p) else c.drawRoundRect(r, u * 2.5f, u * 2.5f, p) }
            fl(if (on) Color.WHITE else ink); body()
            st(if (on) Color.WHITE else grey); body()
            val g = if (on) Color.BLACK else Color.WHITE
            val s = r.height() * .22f
            st(g)
            when (b.shape) {
                "cir" -> c.drawCircle(x, y, s, p)
                "sqr" -> c.drawRect(x - s, y - s, x + s, y + s, p)
                "crs" -> { c.drawLine(x - s, y - s, x + s, y + s, p); c.drawLine(x + s, y - s, x - s, y + s, p) }
                "tri" -> c.drawPath(tri(x, y, s), p)
                "up", "rt", "dn", "lf" -> {
                    fl(g); c.save()
                    c.rotate(when (b.shape) { "rt" -> 90f; "dn" -> 180f; "lf" -> 270f; else -> 0f }, x, y)
                    c.drawPath(tri(x, y, s * .8f), p); c.restore()
                }
                else -> { fl(g); p.textSize = r.height() * .4f; c.drawText(b.shape, x, y + p.textSize * .35f, p) }
            }
        }
    }

    private fun buzz(ms: Long, amp: Int) { vib?.vibrate(VibrationEffect.createOneShot(ms, amp)) }
    private fun click() = buzz(14, 220)
    private fun tick() = buzz(7, 100)

    private fun bit(i: Int) = mask shr i and 1
    private fun ax(i: Int) = (axes[i] * 127).toInt().toByte()

    private fun push() {
        val hat = intArrayOf(8, 1, 2, 7, 0, 3, 6, 5, 4)[(bit(13) - bit(12) + 1) * 3 + (bit(15) - bit(14) + 1)]
        val r = byteArrayOf(mask.toByte(), (mask shr 8 and 0x0F).toByte(), hat.toByte(),
            ax(0), ax(1), ax(2), ax(3), (-bit(6)).toByte(), (-bit(7)).toByte())
        if (!r.contentEquals(last)) { last = r; out(r) }
    }

    private fun down(id: Int, x: Float, y: Float) {
        if (pill.contains(x, y)) { click(); tapStatus(); return }
        for (s in 0..1) if (hypot(x - sc[s].x, y - sc[s].y) < sr * 1.4f) { sp[id] = s; move(id, x, y); return }
        btns.firstOrNull { it.r.contains(x, y) }?.let { bp[id] = it; mask = mask or (1 shl it.bit); click() }
    }

    private fun move(id: Int, x: Float, y: Float) {
        val s = sp[id] ?: return
        var dx = (x - sc[s].x) / sr; var dy = (y - sc[s].y) / sr
        val l = hypot(dx, dy)
        if (l > 1f) { dx /= l; dy /= l }
        axes[2 * s] = dx; axes[2 * s + 1] = dy
        if (l >= 1f && !edge[s]) tick()
        edge[s] = l >= 1f
    }

    private fun up(id: Int) {
        sp.remove(id)?.let { axes[2 * it] = 0f; axes[2 * it + 1] = 0f; edge[it] = false }
        bp.remove(id)?.let { mask = mask and (1 shl it.bit).inv() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_MOVE -> for (k in 0 until e.pointerCount) move(e.getPointerId(k), e.getX(k), e.getY(k))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(i))
            MotionEvent.ACTION_CANCEL -> for (id in (bp.keys + sp.keys).toList()) up(id)
        }
        push(); invalidate(); return true
    }
}
