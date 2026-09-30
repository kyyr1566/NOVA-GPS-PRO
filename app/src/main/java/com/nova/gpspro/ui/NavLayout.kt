package com.nova.gpspro.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure sizing rule for the Navigation page (JVM-tested). Everything must fit in [avail] px:
 *   1. speedometer keeps its design size [speedo];
 *   2. the arrow shrinks first (arrowMax → arrowMin);
 *   3. then the gaps shrink (100 % → 35 %);
 *   4. only if still too small (tiny window / huge font): arrow → arrowFloor, then speedometer → speedoFloor.
 * Spare room on tall screens is spread evenly over the gaps (the arrow never exceeds arrowMax).
 */
object NavFit {
    class Result(val arrow: Int, val speedo: Int, val gapScale: Float, val extraPerGap: Int)

    fun solve(avail: Int, fixed: Int, gaps: Int, gapCount: Int, speedo: Int,
              arrowMax: Int, arrowMin: Int, arrowFloor: Int, speedoFloor: Int): Result {
        var rem = avail - fixed - gaps - speedo
        if (rem >= arrowMin) {
            val arrow = min(rem, arrowMax)
            return Result(arrow, speedo, 1f, if (gapCount > 0) (rem - arrow) / gapCount else 0)
        }
        val minGaps = (gaps * 0.35f).roundToInt()
        rem = avail - fixed - speedo - arrowMin                   // arrow at min, now shrink gaps
        if (rem >= minGaps) return Result(arrowMin, speedo, if (gaps > 0) rem.toFloat() / gaps else 1f, 0)
        rem = avail - fixed - speedo - minGaps                    // gaps at min, arrow below min
        if (rem >= arrowFloor) return Result(rem, speedo, 0.35f, 0)
        val s = max(speedoFloor, avail - fixed - minGaps - arrowFloor) // last resort
        return Result(max(0, avail - fixed - minGaps - s), s, 0.35f, 0)
    }
}

/**
 * Non-scrolling vertical layout for the Navigation page. Children (in order): selector, info,
 * arrow, speedometer, stats row, controls. Arrow and speedometer are sized by [NavFit];
 * the rest are measured at their natural (wrap) height. Nothing can extend past the bottom.
 */
class NavLayout(ctx: Context) : ViewGroup(ctx) {
    lateinit var arrowView: View
    lateinit var speedoView: View
    /** Design gap above each child (index = child index), px. */
    var gapsPx = IntArray(0)
    var speedoPx = 0; var arrowMaxPx = 0; var arrowMinPx = 0; var arrowFloorPx = 0; var speedoFloorPx = 0

    private var tops = IntArray(0); private var heights = IntArray(0)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        val cw = w - paddingLeft - paddingRight
        val avail = h - paddingTop - paddingBottom
        val n = childCount
        heights = IntArray(n); tops = IntArray(n)
        val exactW = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        var fixed = 0; var gapSum = 0; var gapCount = 0
        for (i in 0 until n) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            gapSum += gapsPx.getOrElse(i) { 0 }; if (i > 0) gapCount++
            if (v === arrowView || v === speedoView) continue
            v.measure(exactW, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            heights[i] = v.measuredHeight; fixed += v.measuredHeight
        }
        val r = NavFit.solve(avail, fixed, gapSum, gapCount, speedoPx, min(arrowMaxPx, cw), arrowMinPx, arrowFloorPx, speedoFloorPx)
        var y = paddingTop
        for (i in 0 until n) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            val gh = when { v === arrowView -> r.arrow; v === speedoView -> r.speedo; else -> heights[i] }
            if (v === arrowView || v === speedoView) v.measure(exactW, MeasureSpec.makeMeasureSpec(gh, MeasureSpec.EXACTLY))
            heights[i] = gh
            y += (gapsPx.getOrElse(i) { 0 } * r.gapScale).roundToInt() + if (i > 0) r.extraPerGap else 0
            tops[i] = y; y += gh
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val bottomLimit = b - t - paddingBottom
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            val top = min(tops[i], bottomLimit); val bot = min(tops[i] + heights[i], bottomLimit)
            v.layout(paddingLeft, top, paddingLeft + v.measuredWidth, bot)
        }
    }
}
