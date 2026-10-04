package com.nova.gpspro.license

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.nova.gpspro.R
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.text
import com.nova.gpspro.ui.vbox

/** Compact premium one-time license activation screen. */
class ActivationView(
    context: Context,
    private val onActivate: (String) -> Unit,
    private val onScanCamera: () -> Unit,
    private val onPickQr: () -> Unit
) : FrameLayout(context) {

    private val codeInput: EditText
    private val activateButton: FrameLayout
    private val activateLabel: TextView
    private val progress: ProgressBar
    private val cameraButton: TextView
    private val galleryButton: TextView
    private val status: TextView
    private var busy = false

    init {
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(ActivationColors.BACKGROUND, ActivationColors.BACKGROUND_MID, ActivationColors.BACKGROUND)
        )
        isFocusableInTouchMode = true

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val column = context.vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(context.dp(22), context.dp(26), context.dp(22), context.dp(28))
        }
        val availableWidth = (context.resources.displayMetrics.widthPixels - context.dp(44)).coerceAtLeast(context.dp(220))
        val columnWidth = minOf(availableWidth, context.dp(480))
        val contentFrame = FrameLayout(context).apply {
            addView(column, LayoutParams(columnWidth, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        scroll.addView(contentFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val mark = TextView(context).apply {
            text = "N"
            gravity = Gravity.CENTER
            setTextColor(ActivationColors.BACKGROUND)
            setTextSize(22f)
            typeface = Fonts.bold
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(ActivationColors.CYAN, ActivationColors.VIOLET)
            ).apply { shape = GradientDrawable.OVAL }
            elevation = context.dp(7).toFloat()
        }
        val brandRow = LinearLayout(context).apply { gravity = Gravity.CENTER }
        brandRow.addView(mark, LinearLayout.LayoutParams(context.dp(42), context.dp(42)))
        brandRow.addView(context.text("NOVA GPS PRO", 16f, ActivationColors.ICE, Fonts.medium).apply {
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(11)
        })
        column.addView(brandRow)

        column.addView(context.text(context.getString(R.string.activation_eyebrow), 10.5f, ActivationColors.GOLD, Fonts.medium).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(20)
        })
        column.addView(context.text(context.getString(R.string.activation_title), 24f, ActivationColors.TEXT, Fonts.bold).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.06f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(5)
        })
        column.addView(context.text(context.getString(R.string.activation_subtitle), 13.5f, ActivationColors.MUTED, Fonts.regular).apply {
            gravity = Gravity.CENTER
            setLineSpacing(context.dp(2).toFloat(), 1.12f)
            setPadding(context.dp(10), 0, context.dp(10), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(7)
            bottomMargin = context.dp(20)
        })

        val card = context.vbox().apply {
            background = GradientDrawable().apply {
                setColor(ActivationColors.CARD)
                cornerRadius = context.dp(23).toFloat()
                setStroke(context.dp(1), ActivationColors.BORDER)
            }
            setPadding(context.dp(17), context.dp(17), context.dp(17), context.dp(16))
            elevation = context.dp(5).toFloat()
        }
        column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        card.addView(context.text(context.getString(R.string.activation_code_label), 12.5f, ActivationColors.ICE, Fonts.medium),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(7)
            })

        codeInput = EditText(context).apply {
            hint = context.getString(R.string.activation_code_hint)
            setHintTextColor(ActivationColors.PLACEHOLDER)
            setTextColor(ActivationColors.TEXT)
            setTextSize(15f)
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            background = GradientDrawable().apply {
                setColor(ActivationColors.FIELD)
                cornerRadius = context.dp(13).toFloat()
                setStroke(context.dp(1), ActivationColors.FIELD_BORDER)
            }
            setPadding(context.dp(13), context.dp(11), context.dp(13), context.dp(11))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            isSaveEnabled = false // Do not persist a raw license code as view state.
            textDirection = View.TEXT_DIRECTION_LTR
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            letterSpacing = 0.035f
            setOnEditorActionListener { _, actionId, event ->
                val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (actionId == EditorInfo.IME_ACTION_DONE || isEnter) {
                    submit()
                    true
                } else false
            }
        }
        card.addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(50)))

        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        cameraButton = secondaryButton(context.getString(R.string.activation_scan_camera), "◉").apply {
            setOnClickListener { if (!busy) onScanCamera() }
        }
        galleryButton = secondaryButton(context.getString(R.string.activation_pick_qr), "▧").apply {
            setOnClickListener { if (!busy) onPickQr() }
        }
        actions.addView(cameraButton, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { marginEnd = context.dp(6) })
        actions.addView(galleryButton, LinearLayout.LayoutParams(0, context.dp(46), 1f).apply { marginStart = context.dp(6) })
        card.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })

        activateButton = FrameLayout(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ActivationColors.CYAN, ActivationColors.ICE, ActivationColors.VIOLET)
            ).apply { cornerRadius = context.dp(15).toFloat() }
            isClickable = true
            isFocusable = true
            elevation = context.dp(3).toFloat()
        }
        activateLabel = context.text(context.getString(R.string.activation_button), 15f, ActivationColors.BACKGROUND, Fonts.bold).apply {
            gravity = Gravity.CENTER
        }
        activateButton.addView(activateLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        progress = ProgressBar(context).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(ActivationColors.BACKGROUND)
            visibility = View.GONE
        }
        activateButton.addView(progress, LayoutParams(context.dp(19), context.dp(19), Gravity.CENTER_VERTICAL or Gravity.END).apply {
            marginEnd = context.dp(15)
        })
        activateButton.setOnClickListener { submit() }
        card.addView(activateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(50)).apply {
            topMargin = context.dp(12)
        })

        status = context.text("", 12.5f, ActivationColors.ERROR, Fonts.medium).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setLineSpacing(context.dp(1).toFloat(), 1.08f)
            setPadding(context.dp(11), context.dp(8), context.dp(11), context.dp(8))
            visibility = View.GONE
        }
        card.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(10)
        })

        codeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { if (!busy) clearMessage() }
        })

        column.addView(context.text(context.getString(R.string.activation_offline_note), 11.5f, ActivationColors.MUTED, Fonts.regular).apply {
            gravity = Gravity.CENTER
            setLineSpacing(context.dp(1).toFloat(), 1.1f)
            setPadding(context.dp(12), context.dp(14), context.dp(12), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setLicenseCode(code: String) {
        codeInput.setText(code.trim())
        codeInput.setSelection(codeInput.text.length)
        clearMessage()
    }

    fun setBusy(isBusy: Boolean) {
        busy = isBusy
        codeInput.isEnabled = !isBusy
        cameraButton.isEnabled = !isBusy
        galleryButton.isEnabled = !isBusy
        activateButton.isEnabled = !isBusy
        activateButton.alpha = if (isBusy) 0.78f else 1f
        cameraButton.alpha = if (isBusy) 0.55f else 1f
        galleryButton.alpha = if (isBusy) 0.55f else 1f
        progress.visibility = if (isBusy) View.VISIBLE else View.GONE
        activateLabel.text = context.getString(if (isBusy) R.string.activation_button_busy else R.string.activation_button)
    }

    fun showError(message: String) = showMessage(message, success = false)
    fun showSuccess(message: String) = showMessage(message, success = true)

    private fun showMessage(message: String, success: Boolean) {
        status.text = message
        status.setTextColor(if (success) ActivationColors.SUCCESS else ActivationColors.ERROR)
        status.background = GradientDrawable().apply {
            setColor(if (success) ActivationColors.SUCCESS_BG else ActivationColors.ERROR_BG)
            cornerRadius = context.dp(12).toFloat()
            setStroke(context.dp(1), if (success) ActivationColors.SUCCESS_BORDER else ActivationColors.ERROR_BORDER)
        }
        status.visibility = View.VISIBLE
    }

    private fun clearMessage() {
        status.text = ""
        status.visibility = View.GONE
    }

    private fun submit() {
        if (busy) return
        val code = codeInput.text.toString().trim()
        if (code.isEmpty()) {
            showError(context.getString(R.string.activation_error_enter_code))
            return
        }
        onActivate(code)
    }

    private fun secondaryButton(label: String, icon: String): TextView = context.text(
        "$icon  $label", 12.5f, ActivationColors.ICE, Fonts.medium
    ).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(ActivationColors.FIELD)
            cornerRadius = context.dp(13).toFloat()
            setStroke(context.dp(1), ActivationColors.FIELD_BORDER)
        }
        isClickable = true
        isFocusable = true
        maxLines = 1
        setPadding(context.dp(5), context.dp(7), context.dp(5), context.dp(7))
    }
}

/** Screen-only palette; the licensed app's existing page styling is left untouched. */
private object ActivationColors {
    const val BACKGROUND = 0xFF0B1628.toInt()
    const val BACKGROUND_MID = 0xFF10213A.toInt()
    const val CARD = 0xFF11243A.toInt()
    const val BORDER = 0xFF24405A.toInt()
    const val FIELD = 0xFF0B1A2D.toInt()
    const val FIELD_BORDER = 0xFF2B4962.toInt()
    const val TEXT = 0xFFEAF6FF.toInt()
    const val MUTED = 0xFF9BB1C6.toInt()
    const val PLACEHOLDER = 0xFF6F8AA1.toInt()
    const val CYAN = 0xFF39D7ED.toInt()
    const val ICE = 0xFFB7F4FF.toInt()
    const val VIOLET = 0xFF8C79F7.toInt()
    const val GOLD = 0xFFE5C779.toInt()
    const val SUCCESS = 0xFF83E4BA.toInt()
    const val SUCCESS_BG = 0xFF123A34.toInt()
    const val SUCCESS_BORDER = 0xFF286A58.toInt()
    const val ERROR = 0xFFFFA2AB.toInt()
    const val ERROR_BG = 0xFF3A202F.toInt()
    const val ERROR_BORDER = 0xFF74404A.toInt()
}
