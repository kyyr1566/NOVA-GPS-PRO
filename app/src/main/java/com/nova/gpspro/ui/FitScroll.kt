package com.nova.gpspro.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import kotlin.math.roundToInt

/**
 * Adaptive page container. Works from the height that is actually available (after status/nav bars,
 * bottom bar, keyboard, display size and font scale) — never from inches or a specific device.
 *
 * When the natural content is taller than the available height, one compression factor f ∈ [0,1]
 * is found by binary search (1 = original design, 0 = most compact):
 *   • vertical gaps (margins) of registered groups shrink to 35 %,
 *   • vertical padding of registered groups / targets shrinks to 55 %,
 *   • registered shrinkable elements (e.g. the satellite emblem) go from max → min size.
 * Only if the content still doesn't fit at f = 0 (tiny screen + huge font) does scrolling remain,
 * as a last-resort safety net — the normal case shows the whole page.
 */
class FitScroll(ctx: Context, private val content: ViewGroup) : ScrollView(ctx) {

    private class Gap(val lp: ViewGroup.MarginLayoutParams, val top: Int, val bottom: Int)
    private class Pad(val v: View, val top: Int, val bottom: Int)
    private class Shrink(val min: Int, val max: Int, val set: (Int) -> Unit)

    private val groups = ArrayList<ViewGroup>()
    private val padViews = ArrayList<View>()
    private val gaps = ArrayList<Gap>(); private val pads = ArrayList<Pad>()
    private val shrinks = ArrayList<Shrink>()
    private var captured = false
    private var lastF = -1f
    /** Extra height the content needs beyond its natural size (e.g. weighted arrow + speedometer). */
    var reservePx = 0

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Scale the vertical margins of the direct children and the vertical padding of these groups. */
    fun compressGroups(vararg g: ViewGroup) = apply { groups.addAll(g); captured = false }
    /** Scale the vertical padding of these views (inputs, buttons, cards). */
    fun compressPadding(vararg v: View) = apply { padViews.addAll(v); captured = false }
    /** An element whose size may go from [maxPx] down to [minPx]. */
    fun shrinkable(minPx: Int, maxPx: Int, set: (Int) -> Unit) = apply { shrinks.add(Shrink(minPx, maxPx, set)) }

    private fun capture() {
        gaps.clear(); pads.clear()
        for (g in groups) {
            pads.add(Pad(g, g.paddingTop, g.paddingBottom))
            for (i in 0 until g.childCount) (g.getChildAt(i).layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                gaps.add(Gap(it, it.topMargin, it.bottomMargin))
            }
        }
        for (v in padViews) pads.add(Pad(v, v.paddingTop, v.paddingBottom))
        captured = true
    }

    private fun apply(f: Float) {
        val mg = Fit.ratio(f, 0.35f); val pd = Fit.ratio(f, 0.55f)
        for (g in gaps) { g.lp.topMargin = (g.top * mg).roundToInt(); g.lp.bottomMargin = (g.bottom * mg).roundToInt() }
        for (p in pads) {
            val t = (p.top * pd).roundToInt(); val b = (p.bottom * pd).roundToInt()
            if (p.v.paddingTop != t || p.v.paddingBottom != b) p.v.setPaddingRelative(p.v.paddingStart, t, p.v.paddingEnd, b)
        }
        for (s in shrinks) s.set(Fit.lerp(s.min, s.max, f))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val avail = MeasureSpec.getSize(heightMeasureSpec) - paddingTop - paddingBottom - reservePx
        if (mode != MeasureSpec.UNSPECIFIED && avail > 0) {
            if (!captured) capture()
            val cw = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
            val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            val f = Fit.solve { f -> apply(f); content.measure(cw, unspec); content.measuredHeight <= avail }
            apply(f); lastF = f
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}

/** Pure helpers (JVM-tested). */
object Fit {
    fun ratio(f: Float, min: Float) = min + (1f - min) * f.coerceIn(0f, 1f)
    fun lerp(min: Int, max: Int, f: Float) = (min + (max - min) * f.coerceIn(0f, 1f)).roundToInt()

    /** Largest f in [0,1] for which [fits] is true (monotone); 0 if nothing fits. */
    fun solve(fits: (Float) -> Boolean): Float {
        if (fits(1f)) return 1f
        if (!fits(0f)) return 0f
        var lo = 0f; var hi = 1f
        repeat(7) { val m = (lo + hi) / 2; if (fits(m)) lo = m else hi = m }
        return lo
    }
}
