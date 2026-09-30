package com.nova.gpspro.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.nova.gpspro.R
import com.nova.gpspro.location.LocationEngine
import kotlin.math.min

/**
 * Launch splash – exactly 4.0 s on ONE clock (SystemClock + postOnAnimation).
 * Centre: a large FIXED sapphire navigation arrow. Around it: a graduated dial with
 * N/NE/E/SE/S/SW/W/NW that ROTATES around the arrow, driven only by the GPS bearing
 * from LocationEngine (no compass / no sensors).
 */
class SplashView(ctx: Context, private val gps: LocationEngine, private val onFinished: () -> Unit) : FrameLayout(ctx) {

    private val dial = DialView(ctx)
    private val title = ctx.text(ctx.getString(R.string.app_name), 27f, C.GOLD_DEEP, Fonts.medium).apply {
        gravity = Gravity.CENTER; letterSpacing = 0.24f
    }
    private val underline = View(ctx).apply { background = gradientRect(0x002457D6, C.GOLD, ctx.dp(1).toFloat()) }
    private val messages = listOf(R.string.splash_m1, R.string.splash_m2, R.string.splash_m3, R.string.splash_m4).map { ctx.getString(it) }
    /** single message slot → messages can never overlap */
    private val message = ctx.text("", 18f, C.TEXT, Fonts.regular).apply {
        gravity = Gravity.CENTER; alpha = 0f; letterSpacing = 0.02f
        setPadding(ctx.dp(24), 0, ctx.dp(24), 0)
    }
    private var t0 = 0L
    private var lastFrame = 0L
    private var shownIndex = -2
    private var finished = false
    @Volatile private var gpsBearing: Float? = null
    private val gpsListener = LocationEngine.Listener { s -> gpsBearing = if (s.isUsable && s.hasBearing) s.bearing else null }

    init {
        isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFFDF8.toInt(), C.BG, 0xFFF4F1EA.toInt()))
        val col = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        col.addView(dial, LinearLayout.LayoutParams(ctx.dp(260), ctx.dp(260)))
        col.addView(title, lp().margins(t = ctx.dp(22)))
        col.addView(underline, LinearLayout.LayoutParams(ctx.dp(90), ctx.dp(2)).margins(t = ctx.dp(8)))
        col.addView(message, lp().margins(t = ctx.dp(18)))
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dial.dialDeg = SplashTimeline.DIAL_INTRO_OFFSET
    }

    fun start() {
        gps.addListener(gpsListener)
        viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                t0 = SystemClock.uptimeMillis(); lastFrame = t0
                postOnAnimation(frame)
                return true
            }
        })
    }

    private val frame = object : Runnable {
        override fun run() {
            if (finished) return
            val now = SystemClock.uptimeMillis()
            val t = now - t0
            if (t >= SplashTimeline.TOTAL_MS) { finish(); return }
            val dt = ((now - lastFrame).coerceIn(0, 100)) / 1000f
            lastFrame = now

            val idx = SplashTimeline.messageIndex(t)
            if (idx != shownIndex) { shownIndex = idx; if (idx >= 0) message.text = messages[idx] }
            message.alpha = SplashTimeline.messageAlpha(t)
            message.translationY = message.dp(1) * SplashTimeline.messageOffsetDp(t)
            val sc = SplashTimeline.messageScale(t); message.scaleX = sc; message.scaleY = sc
            alpha = SplashTimeline.screenAlpha(t)

            dial.dialDeg = SplashTimeline.dialStep(dial.dialDeg, SplashTimeline.dialTarget(gpsBearing), dt)
            dial.invalidate()
            postOnAnimation(this)
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        removeCallbacks(frame)
        gps.removeListener(gpsListener)
        (parent as? android.view.ViewGroup)?.removeView(this)
        onFinished()
    }

    override fun onDetachedFromWindow() { finished = true; removeCallbacks(frame); gps.removeListener(gpsListener); super.onDetachedFromWindow() }

    /** Graduated rotating dial + fixed central arrow. */
    private class DialView(ctx: Context) : View(ctx) {
        var dialDeg = 0f
        private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.CARD }
        private val faceRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.BORDER }
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.GOLD }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.GOLD_LIGHT }
        private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT3; strokeCap = Paint.Cap.ROUND }
        private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GOLD_DEEP; strokeCap = Paint.Cap.ROUND }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = C.TEXT2; typeface = Fonts.medium }
        private val labelN = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = C.GOLD; typeface = Fonts.bold }
        private val nMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GOLD }
        private val lubber = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.GOLD_DEEP }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrow = Path(); private val half = Path(); private val tri = Path(); private val lub = Path()
        private val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        init { setLayerType(LAYER_TYPE_HARDWARE, null) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val s = min(w, h).toFloat(); val cx = w / 2f; val cy = h / 2f
            outer.strokeWidth = s * 0.012f; inner.strokeWidth = s * 0.006f; faceRing.strokeWidth = s * 0.004f
            tickMinor.strokeWidth = s * 0.005f; tickMajor.strokeWidth = s * 0.010f
            label.textSize = s * 0.050f; labelN.textSize = s * 0.068f
            val r = s * 0.25f   // large fixed arrow
            arrow.reset()
            arrow.moveTo(cx, cy - r); arrow.lineTo(cx + r * 0.62f, cy + r * 0.72f)
            arrow.lineTo(cx, cy + r * 0.34f); arrow.lineTo(cx - r * 0.62f, cy + r * 0.72f); arrow.close()
            half.reset(); half.moveTo(cx, cy - r); half.lineTo(cx, cy + r * 0.34f); half.lineTo(cx - r * 0.62f, cy + r * 0.72f); half.close()
            fill.shader = LinearGradient(cx, cy - r, cx, cy + r, 0xFF5A8FF2.toInt(), C.ORANGE_DEEP, Shader.TileMode.CLAMP)
            shade.shader = LinearGradient(cx, cy - r, cx, cy + r, 0xFF3F6FDC.toInt(), C.GOLD_DEEP, Shader.TileMode.CLAMP)
            fill.setShadowLayer(s * 0.025f, 0f, s * 0.01f, 0x401A44B0)
            // fixed index mark at the top (travel direction)
            val R = s * 0.48f
            lub.reset(); lub.moveTo(cx, cy - R + s * 0.055f); lub.lineTo(cx - s * 0.022f, cy - R + s * 0.005f); lub.lineTo(cx + s * 0.022f, cy - R + s * 0.005f); lub.close()
        }

        override fun onDraw(canvas: Canvas) {
            val s = min(width, height).toFloat(); val cx = width / 2f; val cy = height / 2f
            val R = s * 0.48f
            canvas.drawCircle(cx, cy, R, face)
            canvas.drawCircle(cx, cy, R, faceRing)

            // ---------- rotating dial ----------
            canvas.save()
            canvas.rotate(dialDeg, cx, cy)
            canvas.drawCircle(cx, cy, R * 0.93f, outer)
            canvas.drawCircle(cx, cy, R * 0.62f, inner)
            for (deg in 0 until 360 step 5) {
                val major = deg % 45 == 0; val mid = deg % 15 == 0
                val len = when { major -> s * 0.055f; mid -> s * 0.035f; else -> s * 0.02f }
                val p = if (major) tickMajor else tickMinor
                canvas.save(); canvas.rotate(deg.toFloat(), cx, cy)
                canvas.drawLine(cx, cy - R * 0.93f + s * 0.012f, cx, cy - R * 0.93f + s * 0.012f + len, p)
                canvas.restore()
            }
            // labels stay upright while travelling around the ring
            val lr = R * 0.93f - s * 0.12f
            for (i in names.indices) {
                drawUpright(canvas, names[i], cx, cy, i * 45f, lr, if (i == 0) labelN else label)
            }
            // small north triangle on the ring
            tri.reset()
            val ty = cy - R * 0.93f - s * 0.004f
            tri.moveTo(cx, ty - s * 0.035f); tri.lineTo(cx - s * 0.02f, ty); tri.lineTo(cx + s * 0.02f, ty); tri.close()
            canvas.drawPath(tri, nMark)
            canvas.restore()

            // ---------- fixed parts ----------
            canvas.drawPath(lub, lubber)
            canvas.drawPath(arrow, fill); canvas.drawPath(half, shade)
        }

        /** Draw [text] at angle [deg] on the (already rotated) dial, keeping it upright on screen. */
        private fun drawUpright(canvas: Canvas, text: String, cx: Float, cy: Float, deg: Float, r: Float, p: Paint) {
            val a = Math.toRadians((deg - 90).toDouble())
            val x = cx + (Math.cos(a) * r).toFloat(); val y = cy + (Math.sin(a) * r).toFloat()
            canvas.save()
            canvas.rotate(-dialDeg, x, y)
            val fm = p.fontMetrics
            canvas.drawText(text, x, y - (fm.ascent + fm.descent) / 2f, p)
            canvas.restore()
        }
    }
}
