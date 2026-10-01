package com.nova.gpspro.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Theme palette. ONE api, TWO complete colour sets.
 *
 * Every page reads `C.*` while it builds its views, so switching the theme and recreating the
 * Activity repaints the whole app. [dark] must be set before any view is created
 * (NovaApp/MainActivity do this from the saved user setting), never while views are alive.
 *
 * Rule for the dark mode: no colour literal may be used for text/data — every text, number,
 * coordinate, distance, speed and accuracy reads its colour from here, so it always
 * contrasts with the surface it is drawn on. A FIXED literal is only allowed for things that
 * live inside their own fixed-colour artwork (radar disc, QR code, camera screen).
 */
object C {
    /** true = dark appearance (🌙), false = light appearance (☀️). */
    var dark: Boolean = false
        private set

    /** Selects the palette. Call before building any view. */
    fun use(darkMode: Boolean) { dark = darkMode }

    private fun pick(light: Int, darkValue: Int): Int = if (dark) darkValue else light

    // ---------------------------------------------------------------- surfaces
    val BG get() = pick(0xFFFBF8F1.toInt(), 0xFF0A0F16.toInt())             // ivory / night ink
    val CARD get() = pick(0xFFFFFFFF.toInt(), 0xFF151B24.toInt())
    val CARD_SOFT get() = pick(0xFFFDFBF6.toInt(), 0xFF1B2330.toInt())
    val BORDER get() = pick(0xFFEFE7D6.toInt(), 0xFF2A3442.toInt())
    val TRACK get() = pick(0xFFEEEAE1.toInt(), 0xFF232C38.toInt())

    // ---------------------------------------------------------------- brand (sapphire)
    val GOLD get() = pick(0xFF2457D6.toInt(), 0xFF6E9BFF.toInt())           // primary accent
    val GOLD_DEEP get() = pick(0xFF1A44B0.toInt(), 0xFF9DBBFF.toInt())      // primary text / outline accent
    val GOLD_LIGHT get() = pick(0xFFD5E0FA.toInt(), 0xFF2B3A57.toInt())     // accent outline / chip border
    val GOLD_PALE get() = pick(0xFFEEF3FD.toInt(), 0xFF1B2536.toInt())      // accent tint background
    val ORANGE_GOLD get() = pick(0xFFE39B2E.toInt(), 0xFFF0B45A.toInt())
    val ORANGE_DEEP get() = pick(0xFF1A44B0.toInt(), 0xFF5C82D8.toInt())
    val ACCENT get() = pick(0xFF3A6DE6.toInt(), 0xFF7FA6FF.toInt())

    /** Filled primary controls (buttons, selected segments) — readable in both themes. */
    val PRIMARY_A get() = pick(0xFF3F74EA.toInt(), 0xFF3F74EA.toInt())
    val PRIMARY_B get() = pick(0xFF1A44B0.toInt(), 0xFF2E56C8.toInt())
    val ON_PRIMARY get() = pick(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())

    // ---------------------------------------------------------------- states
    val GREEN get() = pick(0xFF2FA66A.toInt(), 0xFF35D08A.toInt())
    val GREEN_LIGHT get() = pick(0xFFDDF3E6.toInt(), 0xFF17352A.toInt())
    val GREEN_SOFT get() = pick(0xFF8FD9AE.toInt(), 0xFF6FE3AE.toInt())
    val GREEN_DEEP get() = pick(0xFF1E8C55.toInt(), 0xFF1E9A64.toInt())
    val RED get() = pick(0xFFD9544D.toInt(), 0xFFF06A62.toInt())
    val RED_LIGHT get() = pick(0xFFFBE3E1.toInt(), 0xFF3A1E1D.toInt())
    val AMBER get() = pick(0xFFE0A030.toInt(), 0xFFF0B93C.toInt())

    // ---------------------------------------------------------------- text
    val TEXT get() = pick(0xFF3D3628.toInt(), 0xFFECE7DC.toInt())           // main text / numbers
    val TEXT2 get() = pick(0xFF9B958A.toInt(), 0xFFB9B7AF.toInt())          // captions
    val TEXT3 get() = pick(0xFFC2BCB0.toInt(), 0xFF9298A2.toInt())          // hints / inactive icons

    // ---------------------------------------------------------------- GPS signal indicator
    val SIGNAL_BLUE get() = pick(0xFF2E6BE6.toInt(), 0xFF6FA8F5.toInt())    // «good» bar colour
    val SIGNAL_TRACK get() = pick(0xFFD8D2C6.toInt(), 0xFF39414D.toInt())   // empty signal bars

    // ---------------------------------------------------------------- navigation arrow
    val ARROW_A get() = pick(0xFF5A8FF2.toInt(), 0xFF6E9CFF.toInt())
    val ARROW_B get() = pick(0xFF1A44B0.toInt(), 0xFF3E6BD8.toInt())
    val ARROW_SHADE_A get() = pick(0xFF3F6FDC.toInt(), 0xFF4A79E0.toInt())
    val ARROW_SHADE_B get() = pick(0xFF1A44B0.toInt(), 0xFF2E56C8.toInt())

    // ---------------------------------------------------------------- splash
    val SPLASH_A get() = pick(0xFFFFFDF8.toInt(), 0xFF121A26.toInt())
    val SPLASH_C get() = pick(0xFFF4F1EA.toInt(), 0xFF070C12.toInt())

    // ---------------------------------------------------------------- radar page chrome
    val RADAR_CHIP_TEXT get() = pick(0xFF007E9A.toInt(), 0xFF6FD8EE.toInt())
    val RADAR_CHIP_BG get() = pick(0xFFE2F8FB.toInt(), 0xFF12303A.toInt())
    val RADAR_TEXT get() = pick(0xFF075B79.toInt(), 0xFF8FD9EE.toInt())
    val RADAR_TEXT2 get() = pick(0xFF386D7D.toInt(), 0xFF9FC4CF.toInt())
    val RADAR_SOFT_BG get() = pick(0xFFEAF7FA.toInt(), 0xFF12242E.toInt())
    val RADAR_BORDER get() = pick(0xFFBDE9EF.toInt(), 0xFF23414D.toInt())
    val RADAR_BTN_BG get() = pick(0xFFFFFFFF.toInt(), 0xFF16252E.toInt())
    val RADAR_SEL_A get() = pick(0xFF0D9DC4.toInt(), 0xFF0B7392.toInt())
    val RADAR_SEL_B get() = pick(0xFF087895.toInt(), 0xFF095E78.toInt())

    // ---------------------------------------------------------------- gauges
    val SCALE_BLUE get() = pick(0xFF1565C0.toInt(), 0xFF7FB6FF.toInt())      // speedometer scale numbers
    val DOT_HALO get() = pick(0x332FA66A.toInt(), 0x4435D08A.toInt())

    // ---------------------------------------------------------------- effects
    val RIPPLE get() = pick(0x332457D6.toInt(), 0x33FFFFFF.toInt())
    val SHADOW get() = pick(0x402457D6.toInt(), 0x66000000.toInt())

    // ---------------------------------------------------------------- fixed artwork
    /** QR codes must stay dark-on-white in BOTH themes to remain scannable. */
    val QR_INK get() = 0xFF1A44B0.toInt()
    val QR_BG get() = 0xFFFFFFFF.toInt()
}

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun View.dp(v: Int): Int = context.dp(v)
fun View.dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val cond: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}

fun roundRect(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

fun gradientRect(c1: Int, c2: Int, radius: Float): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply { cornerRadius = radius }

fun ripple(content: Drawable, radius: Float, color: Int = C.RIPPLE): Drawable =
    RippleDrawable(ColorStateList.valueOf(color), content, roundRect(Color.WHITE, radius))

/** Primary filled control background (light + dark safe). */
fun primaryFill(radius: Float): GradientDrawable = gradientRect(C.PRIMARY_A, C.PRIMARY_B, radius)

fun Context.card(radiusDp: Int = 22, color: Int = C.CARD): Drawable =
    roundRect(color, dp(radiusDp).toFloat(), C.BORDER, dp(1))

fun View.softElevation(e: Float = 3f) {
    elevation = dpf(e)
    outlineAmbientShadowColor = C.SHADOW
    outlineSpotShadowColor = C.SHADOW
}

fun Context.text(s: String = "", sizeSp: Float = 15f, color: Int = C.TEXT, tf: Typeface = Fonts.regular): TextView =
    TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color); typeface = tf
        includeFontPadding = true
    }

fun Context.goldButton(label: String, onClick: () -> Unit): TextView = text(label, 16f, C.ON_PRIMARY, Fonts.medium).apply {
    gravity = Gravity.CENTER
    val r = dp(18).toFloat()
    background = ripple(primaryFill(r), r, 0x55FFFFFF)
    setPadding(dp(22), dp(15), dp(22), dp(15))
    softElevation(4f)
    isClickable = true; isFocusable = true
    setOnClickListener { onClick() }
    letterSpacing = 0.03f
}

fun Context.outlineButton(label: String, color: Int = C.GOLD_DEEP, onClick: () -> Unit): TextView =
    text(label, 15f, color, Fonts.medium).apply {
        gravity = Gravity.CENTER
        val r = dp(16).toFloat()
        background = ripple(roundRect(C.CARD, r, (color and 0x00FFFFFF) or 0x66000000, dp(1)), r)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.input(hint: String, numeric: Boolean = false, multiline: Boolean = false): EditText = EditText(this).apply {
    this.hint = hint
    setHintTextColor(C.TEXT3); setTextColor(C.TEXT)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    typeface = Fonts.regular
    background = roundRect(C.CARD_SOFT, dp(14).toFloat(), C.BORDER, dp(1))
    setPadding(dp(14), dp(12), dp(14), dp(12))
    inputType = when {
        numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    }
    if (numeric) { textDirection = View.TEXT_DIRECTION_LTR; textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
    if (multiline) { minLines = 2; maxLines = 4; gravity = Gravity.TOP or Gravity.START } else isSingleLine = true
    // caret + selection must stay visible on the dark surface too (minSdk 30 → always available)
    textCursorDrawable?.setTintList(ColorStateList.valueOf(C.GOLD))
    highlightColor = (C.GOLD and 0x00FFFFFF) or 0x55000000
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(s: Int = 0, t: Int = 0, e: Int = 0, b: Int = 0) = apply {
    marginStart = s; topMargin = t; marginEnd = e; bottomMargin = b
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
