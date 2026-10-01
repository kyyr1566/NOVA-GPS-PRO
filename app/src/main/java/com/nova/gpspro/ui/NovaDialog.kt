package com.nova.gpspro.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView

/** Luxury styled dialog shell used by every dialog in the app. */
class NovaDialog(private val ctx: Context) {
    val dialog = Dialog(ctx)
    private val root = ctx.vbox().apply {
        background = ctx.card(26)
        setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(18))
        elevation = ctx.dp(12).toFloat()
    }
    private val body = ctx.vbox()
    private val scroll: FitScroll
    private val buttons = ctx.hbox().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

    init {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        scroll = FitScroll(ctx, body).apply { isFillViewport = false; compressGroups(body) }
        root.addView(scroll, lp(h = 0, weight = 1f))  // shrinks on small screens so buttons stay visible
        root.addView(buttons, lp().margins(t = ctx.dp(18)))
        val wrap = android.widget.FrameLayout(ctx).apply {
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
            addView(root, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        dialog.setContentView(wrap)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(if (C.dark) 0.55f else 0.25f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val w = minOf(ctx.resources.displayMetrics.widthPixels, ctx.dp(460))
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun title(t: String, color: Int = C.TEXT) = apply {
        body.addView(ctx.text(t, 20f, color, Fonts.medium), lp().margins(b = ctx.dp(10)))
    }

    fun message(m: String) = apply {
        body.addView(ctx.text(m, 15f, C.TEXT2).apply { setLineSpacing(0f, 1.25f) }, lp())
    }

    fun content(v: View) = apply {
        body.addView(v, lp())
        if (v is ViewGroup) {   // adaptive: compress the content's gaps and field paddings on short screens
            scroll.compressGroups(v)
            (0 until v.childCount).map { v.getChildAt(it) }.filter { it is android.widget.EditText }.forEach { scroll.compressPadding(it) }
        }
    }

    fun button(label: String, color: Int = C.GOLD_DEEP, filled: Boolean = false, onClick: (NovaDialog) -> Unit) = apply {
        val b = if (filled) ctx.goldButton(label) { onClick(this) }.apply {
            if (color != C.GOLD_DEEP) background = ripple(roundRect(color, dp(16).toFloat()), dp(16).toFloat(), 0x55FFFFFF)
            setPadding(dp(20), dp(11), dp(20), dp(11)); setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        } else ctx.text(label, 15f, color, Fonts.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(11), dp(16), dp(11))
            background = ripple(ColorDrawable(Color.TRANSPARENT), dp(14).toFloat())
            setOnClickListener { onClick(this@NovaDialog) }
        }
        buttons.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = ctx.dp(8)))
    }

    fun cancelable(c: Boolean) = apply { dialog.setCancelable(c) }
    fun onDismiss(r: () -> Unit) = apply { dialog.setOnDismissListener { r() } }
    fun show() = apply { dialog.show() }
    fun dismiss() = dialog.dismiss()
}
