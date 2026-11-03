package com.nova.gpspro.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.nova.gpspro.R
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.dpf
import com.nova.gpspro.ui.text
import com.nova.gpspro.ui.vbox

/**
 * Short, luxury splash — 700ms — ivory background (#F7F4EC), white emblem card with royal-blue
 * RMG navigation arrow (#2457D6), app name and concise tagline.
 */
class ShortSplashView(
    ctx: Context,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val ivory = Color.parseColor("#F7F4EC")
    private val royalBlue = Color.parseColor("#2457D6")
    private val white = Color.parseColor("#FFFFFF")
    private val border = Color.parseColor("#E6DEC9")
    private val muted = Color.parseColor("#756F65")
    private val shadowTint = Color.parseColor("#2E2457D6")

    private val logoBadge = FrameLayout(ctx).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(white, Color.parseColor("#FBF9F4"))
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(28f)
            setStroke(ctx.dp(1), border)
        }
        elevation = ctx.dpf(8f)
        outlineAmbientShadowColor = shadowTint
        outlineSpotShadowColor = shadowTint
        val arrowIv = ImageView(ctx).apply {
            setImageResource(R.drawable.splash_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setPadding(ctx.dp(17), ctx.dp(15), ctx.dp(17), ctx.dp(17))
        }
        addView(arrowIv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        alpha = 0f
        scaleX = 0.90f
        scaleY = 0.90f
    }

    private val title = ctx.text(ctx.getString(R.string.app_name), 24f, royalBlue, Fonts.bold).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.06f
        alpha = 0f
    }

    private val tagline = ctx.text(ctx.getString(R.string.splash_short), 12.5f, muted, Fonts.regular).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.04f
        alpha = 0f
    }

    init {
        setBackgroundColor(ivory)
        isClickable = true
        val col = ctx.vbox().apply { gravity = Gravity.CENTER }
        col.addView(logoBadge, LinearLayout.LayoutParams(ctx.dp(112), ctx.dp(112)).apply { gravity = Gravity.CENTER })
        col.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = ctx.dp(20)
        })
        col.addView(tagline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = ctx.dp(6)
        })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun start() {
        logoBadge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(DecelerateInterpolator()).start()
        title.animate().alpha(1f).setStartDelay(100).setDuration(260).start()
        tagline.animate().alpha(1f).setStartDelay(160).setDuration(260).start()
        handler.postDelayed({ finish() }, 700)
    }

    private fun finish() {
        if (finished) return
        finished = true
        animate().alpha(0f).setDuration(200).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onFinished()
        }.start()
    }

    override fun onDetachedFromWindow() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
