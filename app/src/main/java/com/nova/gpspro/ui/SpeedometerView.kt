package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Matrix
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Luxury circular speedometer 0–220 with green progress ring. Digits support 3+ digits. */
class SpeedometerView(ctx: Context) : View(ctx) {
    private var value = 0.0         // displayed unit value (animated)
    private var target = 0.0
    private var lastFrame = 0L
    var unitLabel = "km/h"; set(v) { field = v; invalidate() }
    var active = true; set(v) { field = v; invalidate() }
    /** false = circular gauge (default, unchanged); true = digital panel in the same space. */
    var digital = false; set(v) { if (field != v) { field = v; invalidate() } }
    private val panel = RectF()
    private val panelFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CARD }
    private val panelBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.BORDER }
    private val barTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TRACK }

    private val maxScale = 220.0
    private val startAngle = 150f
    private val sweep = 240f
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = C.TRACK }
    private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = SCALE_BLUE; typeface = Fonts.medium }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = C.TEXT; typeface = Fonts.light }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = C.GOLD_DEEP; typeface = Fonts.medium; letterSpacing = 0.1f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GREEN }
    private val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x332FA66A }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CARD }
    private val faceRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.BORDER }

    fun setSpeed(v: Double) {
        target = if (v.isFinite() && v > 0) v else 0.0
        lastFrame = 0L
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w, h).toFloat()
        val stroke = s * 0.055f
        val r = s / 2f - stroke
        val cx = w / 2f; val cy = h / 2f + s * 0.04f
        oval.set(cx - r, cy - r, cx + r, cy + r)
        track.strokeWidth = stroke; prog.strokeWidth = stroke
        val sg = SweepGradient(cx, cy, intArrayOf(0xFF8FD9AE.toInt(), C.GREEN, 0xFF1E8C55.toInt(), 0xFF8FD9AE.toInt()), floatArrayOf(0f, 0.35f, 0.66f, 1f))
        val m = Matrix(); m.setRotate(startAngle - 5f, cx, cy); sg.setLocalMatrix(m)
        prog.shader = sg
        tick.strokeWidth = s * 0.006f
        label.textSize = s * 0.048f
        unit.textSize = s * 0.058f
        faceRing.strokeWidth = s * 0.004f
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (lastFrame != 0L) {
            val dt = (now - lastFrame).coerceIn(0, 100) / 1000.0
            value += (target - value) * (1 - exp(-dt / 0.18))
        }
        lastFrame = now
        val settled = abs(target - value) < 0.05
        if (settled) { value = target; lastFrame = 0L }

        if (digital) { drawDigital(canvas); if (!settled) postInvalidateOnAnimation(); return }

        val cx = oval.centerX(); val cy = oval.centerY(); val r = oval.width() / 2f
        val s = min(width, height).toFloat()

        canvas.drawCircle(cx, cy, r - track.strokeWidth * 1.2f, face)
        canvas.drawCircle(cx, cy, r - track.strokeWidth * 1.2f, faceRing)
        canvas.drawArc(oval, startAngle, sweep, false, track)

        val frac = (value / maxScale).coerceIn(0.0, 1.0).toFloat()
        prog.alpha = if (active) 255 else 110
        if (frac > 0.002f) canvas.drawArc(oval, startAngle, sweep * frac, false, prog)

        // ticks + labels
        val inner = r - track.strokeWidth * 1.4f
        for (i in 0..22) {
            val a = Math.toRadians((startAngle + sweep * i / 22f).toDouble())
            val major = i % 2 == 0
            val len = if (major) s * 0.045f else s * 0.022f
            tick.color = if (i / 22f <= frac && frac > 0) C.GREEN else C.TEXT3
            val x1 = cx + cos(a).toFloat() * inner; val y1 = cy + sin(a).toFloat() * inner
            val x2 = cx + cos(a).toFloat() * (inner - len); val y2 = cy + sin(a).toFloat() * (inner - len)
            canvas.drawLine(x1, y1, x2, y2, tick)
            if (major && i % 4 == 0) {
                val lr = inner - len - label.textSize * 1.0f
                val lx = cx + cos(a).toFloat() * lr; val ly = cy + sin(a).toFloat() * lr + label.textSize * 0.35f
                canvas.drawText((i * 10).toString(), lx, ly, label)
            }
        }

        // green indicator dot
        val ea = Math.toRadians((startAngle + sweep * frac).toDouble())
        val dx = cx + cos(ea).toFloat() * r; val dy = cy + sin(ea).toFloat() * r
        dot.alpha = if (active) 255 else 110
        canvas.drawCircle(dx, dy, track.strokeWidth * 0.95f, dotHalo)
        canvas.drawCircle(dx, dy, track.strokeWidth * 0.55f, face)
        canvas.drawCircle(dx, dy, track.strokeWidth * 0.38f, dot)

        // digital speed (auto-fit so 3+ digits never break the layout)
        val txt = displayedText()
        digits.textSize = s * 0.26f
        val maxW = r * 1.05f
        val tw = digits.measureText(txt)
        if (tw > maxW) digits.textSize *= maxW / tw
        digits.color = if (active) C.TEXT else C.TEXT3
        canvas.drawText(txt, cx, cy + digits.textSize * 0.30f, digits)
        canvas.drawText(unitLabel.uppercase(), cx, cy + digits.textSize * 0.30f + unit.textSize * 1.7f, unit)

        if (!settled) postInvalidateOnAnimation()
    }

    /** The number shown – identical for both modes (same smoothed GPS speed, same rounding). */
    fun displayedText(): String = displayText(target, value)

    private fun drawDigital(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f
        // same footprint as the circular gauge: its s × s square, slightly wider when room allows
        val halfW = min(width / 2f, s * 0.56f) - s * 0.02f
        val halfH = s / 2f - s * 0.04f
        panel.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        val rad = s * 0.09f
        panelBorder.strokeWidth = s * 0.004f
        canvas.drawRoundRect(panel, rad, rad, panelFill)
        canvas.drawRoundRect(panel, rad, rad, panelBorder)

        // status dot (green = live GPS fix)
        dot.alpha = if (active) 255 else 110
        val dr = s * 0.022f; val dxp = panel.left + s * 0.09f; val dyp = panel.top + s * 0.09f
        canvas.drawCircle(dxp, dyp, dr * 1.9f, dotHalo); canvas.drawCircle(dxp, dyp, dr, dot)

        // big digits (auto-fit)
        val txt = displayedText()
        digits.textSize = s * 0.40f
        val maxW = panel.width() * 0.82f
        val tw = digits.measureText(txt)
        if (tw > maxW) digits.textSize *= maxW / tw
        digits.color = if (active) C.TEXT else C.TEXT3
        val baseY = cy + digits.textSize * 0.22f
        canvas.drawText(txt, cx, baseY, digits)
        unit.textSize = s * 0.07f
        canvas.drawText(unitLabel.uppercase(), cx, baseY + unit.textSize * 1.8f, unit)
        unit.textSize = s * 0.058f

        // slim level bar on the same 0–220 scale
        val bh = s * 0.035f; val bl = panel.left + s * 0.1f; val br = panel.right - s * 0.1f
        val bb = panel.bottom - s * 0.09f
        canvas.drawRoundRect(bl, bb - bh, br, bb, bh / 2, bh / 2, barTrack)
        val frac = (value / maxScale).coerceIn(0.0, 1.0).toFloat()
        prog.alpha = if (active) 255 else 110
        if (frac > 0.002f) {
            val sw = prog.style; val sh = prog.shader; prog.style = Paint.Style.FILL; prog.shader = null; prog.color = C.GREEN
            canvas.drawRoundRect(bl, bb - bh, bl + max(bh, (br - bl) * frac), bb, bh / 2, bh / 2, prog)
            prog.style = sw; prog.shader = sh
        }
    }

    companion object {
        const val SCALE_BLUE = 0xFF1565C0.toInt()
        /** Pure: text shown by BOTH circular and digital modes for a given target/animated value. */
        fun displayText(target: Double, value: Double): String =
            com.nova.gpspro.settings.Units.displayInt(if (abs(target - value) < 0.5) target else value).toString()
    }
}
