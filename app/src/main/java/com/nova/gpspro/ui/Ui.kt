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

/** Luxury light palette – no black anywhere. */
object C {
    const val BG = 0xFFFBF8F1.toInt()          // ivory
    const val CARD = 0xFFFFFFFF.toInt()
    const val CARD_SOFT = 0xFFFDFBF6.toInt()
    const val BORDER = 0xFFEFE7D6.toInt()
    const val GOLD = 0xFF2457D6.toInt()          // primary: royal sapphire blue
    const val GOLD_DEEP = 0xFF1A44B0.toInt()     // deep sapphire
    const val GOLD_LIGHT = 0xFFD5E0FA.toInt()    // light sapphire
    const val GOLD_PALE = 0xFFEEF3FD.toInt()     // pale sapphire tint
    const val ORANGE_GOLD = 0xFFE39B2E.toInt()
    const val ORANGE_DEEP = 0xFF1A44B0.toInt()   // arrow deep tone (sapphire)
    const val ACCENT = 0xFF3A6DE6.toInt()        // bright sapphire accent
    const val GREEN = 0xFF2FA66A.toInt()
    const val GREEN_LIGHT = 0xFFDDF3E6.toInt()
    const val RED = 0xFFD9544D.toInt()
    const val RED_LIGHT = 0xFFFBE3E1.toInt()
    const val AMBER = 0xFFE0A030.toInt()
    const val TEXT = 0xFF3D3628.toInt()          // warm deep brown-gray (not black)
    const val TEXT2 = 0xFF9B958A.toInt()
    const val TEXT3 = 0xFFC2BCB0.toInt()
    const val TRACK = 0xFFEEEAE1.toInt()
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

fun ripple(content: Drawable, radius: Float, color: Int = 0x332457D6): Drawable =
    RippleDrawable(ColorStateList.valueOf(color), content, roundRect(Color.WHITE, radius))

fun Context.card(radiusDp: Int = 22, color: Int = C.CARD): Drawable =
    roundRect(color, dp(radiusDp).toFloat(), C.BORDER, dp(1))

fun View.softElevation(e: Float = 3f) {
    elevation = dpf(e)
    outlineAmbientShadowColor = 0x402457D6
    outlineSpotShadowColor = 0x402457D6
}

fun Context.text(s: String = "", sizeSp: Float = 15f, color: Int = C.TEXT, tf: Typeface = Fonts.regular): TextView =
    TextView(this).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp); setTextColor(color); typeface = tf
        includeFontPadding = true
    }

fun Context.goldButton(label: String, onClick: () -> Unit): TextView = text(label, 16f, Color.WHITE, Fonts.medium).apply {
    gravity = Gravity.CENTER
    val r = dp(18).toFloat()
    background = ripple(gradientRect(0xFF3F74EA.toInt(), C.GOLD_DEEP, r), r, 0x55FFFFFF)
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
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun LinearLayout.LayoutParams.margins(s: Int = 0, t: Int = 0, e: Int = 0, b: Int = 0) = apply {
    marginStart = s; topMargin = t; marginEnd = e; bottomMargin = b
}

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
