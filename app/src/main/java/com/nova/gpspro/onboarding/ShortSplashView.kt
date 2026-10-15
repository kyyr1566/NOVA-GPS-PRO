package com.nova.gpspro.onboarding

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.nova.gpspro.R
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.text
import com.nova.gpspro.ui.vbox

/**
 * Short, clean splash — 700ms — ivory background, centered logo + name.
 * No messages, no dial, no GPS dependency. Used before onboarding or main UI.
 */
class ShortSplashView(
    ctx: Context,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private val ivory = Color.parseColor("#F7F4EC")
    private val primary = Color.parseColor("#2F5FE3")

    private val logo = ImageView(ctx).apply {
        setImageResource(R.drawable.splash_icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        // subtle rounded elevation
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(24).toFloat()
            setColor(Color.TRANSPARENT)
        }
        clipToOutline = true
        elevation = ctx.dp(6).toFloat()
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(24).toFloat())
            }
        }
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
    }

    private val title = ctx.text(ctx.getString(R.string.app_name), 24f, primary, Fonts.medium).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.06f
        alpha = 0f
    }

    private val tagline = ctx.text(ctx.getString(R.string.splash_short), 12f, Color.parseColor("#8E877F"), Fonts.regular).apply {
        gravity = Gravity.CENTER
        letterSpacing = 0.04f
        alpha = 0f
    }

    init {
        setBackgroundColor(ivory)
        isClickable = true
        val col = ctx.vbox().apply { gravity = Gravity.CENTER }
        col.addView(logo, LinearLayout.LayoutParams(ctx.dp(120), ctx.dp(120)).apply { gravity = Gravity.CENTER })
        col.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = ctx.dp(18)
        })
        col.addView(tagline, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = ctx.dp(6)
        })
        addView(col, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun start() {
        // fade-in
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        title.animate().alpha(1f).setStartDelay(120).setDuration(280).start()
        tagline.animate().alpha(1f).setStartDelay(180).setDuration(280).start()
        handler.postDelayed({ finish() }, 700)
    }

    private fun finish() {
        if (finished) return
        finished = true
        // fade-out
        animate().alpha(0f).setDuration(220).withEndAction {
            (parent as? android.view.ViewGroup)?.removeView(this)
            onFinished()
        }.start()
    }

    override fun onDetachedFromWindow() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
