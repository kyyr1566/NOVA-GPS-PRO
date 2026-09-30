package com.nova.gpspro.ui

import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** Small professional message shown in the middle of the screen (green = success, red = delete/error). */
class CenterMessage(private val host: FrameLayout) {
    private var view: TextView? = null
    private val hide = Runnable { dismiss() }

    fun success(msg: String) = show(msg, C.GREEN, "✓")
    fun error(msg: String) = show(msg, C.RED, "✕")

    private fun show(msg: String, color: Int, icon: String) {
        val ctx = host.context
        view?.let { host.removeView(it) }
        host.removeCallbacks(hide)
        val tv = ctx.text("$icon  $msg", 15f, Color.WHITE, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = roundRect(color, ctx.dp(20).toFloat())
            setPadding(ctx.dp(22), ctx.dp(13), ctx.dp(22), ctx.dp(13))
            elevation = ctx.dp(10).toFloat()
            maxWidth = (host.width * 0.8f).toInt().coerceAtLeast(ctx.dp(200))
            alpha = 0f; scaleX = 0.9f; scaleY = 0.9f
        }
        host.addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        view = tv
        tv.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        host.postDelayed(hide, 1700)
    }

    private fun dismiss() {
        val tv = view ?: return
        tv.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(200).withEndAction {
            host.removeView(tv); if (view === tv) view = null
        }.start()
    }
}
