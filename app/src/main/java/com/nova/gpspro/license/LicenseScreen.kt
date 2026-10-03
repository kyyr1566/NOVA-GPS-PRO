package com.nova.gpspro.license

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nova.gpspro.R
import com.nova.gpspro.ui.C
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.goldButton
import com.nova.gpspro.ui.gradientRect
import com.nova.gpspro.ui.hbox
import com.nova.gpspro.ui.lp
import com.nova.gpspro.ui.margins
import com.nova.gpspro.ui.ripple
import com.nova.gpspro.ui.roundRect
import com.nova.gpspro.ui.softElevation
import com.nova.gpspro.ui.text
import com.nova.gpspro.ui.vbox

/** Dark navy palette of the activation screen (same sapphire accent / fonts as the rest of the app). */
internal object LicensePalette {
    const val BG = 0xFF0B1628.toInt()
    const val CARD = 0xFF111F38.toInt()
    const val CARD_BORDER = 0xFF223557.toInt()
    const val FIELD = 0xFF0D1A31.toInt()
    const val BUTTON_2 = 0xFF16294A.toInt()
    const val TEXT = 0xFFEAF0FB.toInt()
    const val TEXT2 = 0xFF93A4C3.toInt()
    const val TEXT3 = 0xFF63769B.toInt()
    const val OK = 0xFF4CD08A.toInt()
    const val ERR = 0xFFFF7F78.toInt()
}

/**
 * The license activation UI: a single compact card, centred on the screen, scrollable only when
 * the screen is too small (or the keyboard is open). Pure view code – all decisions are made by
 * [LicenseActivity] / [LicenseManager].
 */
internal class LicenseScreen(
    ctx: Context,
    deviceId: String,
    private val onActivate: (String) -> Unit,
    private val onScan: () -> Unit,
    private val onGallery: () -> Unit,
    private val onCopyDeviceId: () -> Unit
) : FrameLayout(ctx) {

    enum class Tone { NEUTRAL, SUCCESS, ERROR }

    private val input = EditText(ctx).apply {
        hint = ctx.getString(R.string.license_hint)
        setHintTextColor(LicensePalette.TEXT3); setTextColor(LicensePalette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.MONOSPACE
        background = roundRect(LicensePalette.FIELD, ctx.dp(14).toFloat(), LicensePalette.CARD_BORDER, ctx.dp(1))
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        // big multi-line field: easy to long-press → paste a long code; no autocorrect / suggestions
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSingleLine = false
        minLines = 3; maxLines = 5
        gravity = Gravity.TOP or Gravity.START
        textDirection = View.TEXT_DIRECTION_LTR            // codes are always LTR, even in Arabic
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setHorizontallyScrolling(false)
    }

    private val activateBtn = ctx.goldButton(ctx.getString(R.string.license_activate)) { onActivate(input.text.toString()) }
    private val scanBtn = secondaryButton(ctx, ctx.getString(R.string.license_scan_qr)) { onScan() }
    private val galleryBtn = secondaryButton(ctx, ctx.getString(R.string.license_gallery)) { onGallery() }

    private val status = ctx.text("", 13f, LicensePalette.TEXT2, Fonts.medium).apply {
        gravity = Gravity.CENTER; minHeight = ctx.dp(20); maxLines = 3
    }

    init {
        val card = CappedColumn(ctx, ctx.dp(440)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundRect(LicensePalette.CARD, ctx.dp(28).toFloat(), LicensePalette.CARD_BORDER, ctx.dp(1))
            setPadding(ctx.dp(22), ctx.dp(24), ctx.dp(22), ctx.dp(16))
            softElevation(8f)
        }
        card.addView(LogoView(ctx), LinearLayout.LayoutParams(ctx.dp(76), ctx.dp(76)))
        card.addView(ctx.text(ctx.getString(R.string.license_title), 22f, LicensePalette.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER; letterSpacing = 0.01f
        }, lp().margins(t = ctx.dp(16)))
        card.addView(ctx.text(ctx.getString(R.string.license_desc), 13.5f, LicensePalette.TEXT2, Fonts.regular).apply {
            gravity = Gravity.CENTER; setLineSpacing(ctx.dp(3).toFloat(), 1f)
        }, lp().margins(t = ctx.dp(8)))
        card.addView(input, lp().margins(t = ctx.dp(18)))
        card.addView(activateBtn, lp().margins(t = ctx.dp(12)))
        val row = ctx.hbox()
        row.addView(scanBtn, lp(0, weight = 1f))
        row.addView(galleryBtn, lp(0, weight = 1f).margins(s = ctx.dp(10)))
        card.addView(row, lp().margins(t = ctx.dp(10)))
        card.addView(status, lp().margins(t = ctx.dp(12)))
        card.addView(View(ctx).apply { setBackgroundColor(LicensePalette.CARD_BORDER) }, lp(h = ctx.dp(1)).margins(t = ctx.dp(8)))
        card.addView(ctx.text("${ctx.getString(R.string.license_device_id)}  $deviceId", 12f, LicensePalette.TEXT2, Typeface.MONOSPACE).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR
            setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(4))
            isClickable = true; isFocusable = true
            setOnClickListener { onCopyDeviceId() }
        }, lp())
        card.addView(ctx.text(ctx.getString(R.string.license_one_time), 11f, LicensePalette.TEXT3, Fonts.regular).apply {
            gravity = Gravity.CENTER
        }, lp())

        val wrap = FrameLayout(ctx).apply { setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16)) }
        wrap.addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(ScrollView(ctx).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; addView(wrap, LayoutParams(-1, -2)) }, LayoutParams(-1, -1))
    }

    fun setCode(code: String) { input.setText(code); input.setSelection(input.text.length) }

    fun setBusy(busy: Boolean) {
        listOf<View>(activateBtn, scanBtn, galleryBtn).forEach { it.isEnabled = !busy; it.alpha = if (busy) 0.55f else 1f }
        input.isEnabled = !busy
    }

    fun setStatus(tone: Tone, message: String) {
        val (color, icon) = when (tone) {
            Tone.SUCCESS -> LicensePalette.OK to "✓  "
            Tone.ERROR -> LicensePalette.ERR to "✕  "
            Tone.NEUTRAL -> LicensePalette.TEXT2 to ""
        }
        status.setTextColor(color); status.text = icon + message
    }

    private fun secondaryButton(ctx: Context, label: String, onClick: () -> Unit): TextView =
        ctx.text(label, 14f, LicensePalette.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER; maxLines = 1
            val r = ctx.dp(16).toFloat()
            background = ripple(roundRect(LicensePalette.BUTTON_2, r, LicensePalette.CARD_BORDER, ctx.dp(1)), r, 0x333A6DE6)
            setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener { onClick() }
        }

    /** Column that never grows wider than [maxWidthPx] (tablets / large screens keep a compact card). */
    private class CappedColumn(ctx: Context, private val maxWidthPx: Int) : LinearLayout(ctx) {
        init { orientation = LinearLayout.VERTICAL }
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val size = View.MeasureSpec.getSize(widthSpec)
            val capped = if (View.MeasureSpec.getMode(widthSpec) != View.MeasureSpec.UNSPECIFIED && size > maxWidthPx)
                View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.EXACTLY) else widthSpec
            super.onMeasure(capped, heightSpec)
        }
    }

    /** The NOVA arrow-in-ring mark (same geometry as the launcher icon), tuned for a dark surface. */
    private class LogoView(ctx: Context) : View(ctx) {
        private val arrow = Path().apply { moveTo(54f, 30f); lineTo(72f, 76f); lineTo(54f, 66f); lineTo(36f, 76f); close() }
        private val shade = Path().apply { moveTo(54f, 30f); lineTo(54f, 66f); lineTo(36f, 76f); close() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f; color = 0xFF2F4A7A.toInt() }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6C97F2.toInt() }
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A6DE6.toInt() }
        init { background = gradientRect(0xFF18305A.toInt(), 0xFF0F1F3D.toInt(), ctx.dp(24).toFloat()) }
        override fun onDraw(c: Canvas) {
            val s = width / 72f                       // show the 72×72 core of the 108×108 icon canvas
            c.save(); c.scale(s, s); c.translate(-18f, -18f)
            c.drawCircle(54f, 54f, 30f, ring)
            c.drawPath(arrow, light); c.drawPath(shade, dark)
            c.restore()
        }
    }
}
