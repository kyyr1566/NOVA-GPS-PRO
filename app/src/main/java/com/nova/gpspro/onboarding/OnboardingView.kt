package com.nova.gpspro.onboarding

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nova.gpspro.R
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.dpf
import com.nova.gpspro.ui.hbox
import com.nova.gpspro.ui.lp
import com.nova.gpspro.ui.margins
import com.nova.gpspro.ui.ripple
import com.nova.gpspro.ui.roundRect
import com.nova.gpspro.ui.text
import com.nova.gpspro.ui.vbox
import kotlin.math.abs

/**
 * 3-page onboarding — ivory background, white cards, blue primary.
 * Only shown once (OnboardingManager). Supports swipe + dots + skip/next/start.
 */
class OnboardingView(
    private val ctx: Context,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    // Colors from spec
    private val ivory = Color.parseColor("#F7F4EC")
    private val primary = Color.parseColor("#2F5FE3")
    private val royal = Color.parseColor("#3C6BFF")
    private val white = Color.parseColor("#FFFFFF")
    private val textDark = Color.parseColor("#2E2A25")
    private val textSec = Color.parseColor("#8E877F")
    private val dotInactive = Color.parseColor("#D9D1BE")
    private val cardShadow = Color.parseColor("#1A2E2A25")

    private var current = 0
    private val pageCount = 3

    private lateinit var pagerContainer: FrameLayout
    private lateinit var dots: List<View>
    private lateinit var skipBtn: TextView
    private lateinit var actionBtn: TextView
    private val pages = mutableListOf<View>()

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy) && abs(dx) > ctx.dp(60) && abs(vx) > 400) {
                // In RTL, swipe direction is mirrored — but we keep logical next = left swipe
                val isRtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val leftSwipe = dx < 0
                // For RTL, left swipe still means next (since pages are ordered logically)
                if (leftSwipe) {
                    if (current < pageCount - 1) goTo(current + 1) else onFinishedWrapped()
                } else {
                    if (current > 0) goTo(current - 1)
                }
                return true
            }
            return false
        }
    })

    init {
        setBackgroundColor(ivory)
        isClickable = true
        layoutDirection = ctx.resources.configuration.layoutDirection

        // Root vertical
        val root = ctx.vbox().apply {
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(20))
        }

        // Top bar — skip
        val topBar = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        // spacer
        topBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        skipBtn = ctx.text(
            if (isRtl()) ctx.getString(R.string.onboarding_skip) else ctx.getString(R.string.onboarding_skip),
            14f, textSec, Fonts.medium
        ).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
            background = ripple(roundRect(Color.TRANSPARENT, ctx.dp(20).toFloat()), ctx.dp(20).toFloat(), Color.parseColor("#10000000"))
            isClickable = true
            isFocusable = true
            setOnClickListener { onFinishedWrapped() }
            // Ensure RTL text direction correct
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        topBar.addView(skipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topBar, lp())

        // Pager container — weight 1
        pagerContainer = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }
        // Build 3 pages
        pages.add(buildPage1())
        pages.add(buildPage2())
        pages.add(buildPage3())
        pages.forEachIndexed { idx, v ->
            v.visibility = if (idx == 0) View.VISIBLE else View.GONE
            v.alpha = if (idx == 0) 1f else 0f
            pagerContainer.addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        root.addView(pagerContainer, lp(h = 0, weight = 1f).margins(t = ctx.dp(8)))

        // Dots
        val dotsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        dots = (0 until pageCount).map { i ->
            View(ctx).apply {
                val sel = i == 0
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (sel) primary else dotInactive)
                }
                // For selected, make it pill (wider)
                layoutParams = LinearLayout.LayoutParams(if (sel) ctx.dp(22) else ctx.dp(8), ctx.dp(8)).apply {
                    marginStart = ctx.dp(4); marginEnd = ctx.dp(4)
                }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        root.addView(dotsRow, lp().margins(t = ctx.dp(16)))

        // Action button — next / start
        actionBtn = ctx.text("", 16f, Color.WHITE, Fonts.medium).apply {
            gravity = Gravity.CENTER
            val r = ctx.dp(16).toFloat()
            background = ripple(GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(primary, royal)).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }, r, Color.parseColor("#20FFFFFF"))
            setPadding(ctx.dp(22), ctx.dp(15), ctx.dp(22), ctx.dp(15))
            elevation = ctx.dp(4).toFloat()
            isClickable = true
            isFocusable = true
            letterSpacing = 0.02f
            setOnClickListener { handleAction() }
        }
        root.addView(actionBtn, lp().margins(t = ctx.dp(18)))
        updateActionButton()

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Touch for swipe
        setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            // also handle click outside? keep
            false
        }

        // Entrance animation for first page
        post { animatePageEntrance(pages[0]) }
    }

    private fun isRtl() = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private fun onFinishedWrapped() {
        OnboardingManager.setCompleted(ctx)
        // fade out
        animate().alpha(0f).setDuration(220).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onFinished()
        }.start()
    }

    private fun handleAction() {
        if (current < pageCount - 1) goTo(current + 1) else onFinishedWrapped()
    }

    private fun goTo(target: Int) {
        if (target == current || target !in 0 until pageCount) return
        val from = pages[current]
        val to = pages[target]
        val dir = if (target > current) 1 else -1

        // Animate out/in
        from.animate().alpha(0f).translationX(-dir * ctx.dp(24).toFloat()).setDuration(220).withEndAction {
            from.visibility = View.GONE
            from.translationX = 0f
        }.start()

        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.translationX = dir * ctx.dp(24).toFloat()
        to.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        animatePageEntrance(to)

        current = target
        updateDots()
        updateActionButton()
        // Hide skip on last page? Spec keeps skip on all, but we can keep visible
        skipBtn.alpha = if (current == pageCount - 1) 0.7f else 1f
    }

    private fun updateDots() {
        dots.forEachIndexed { i, v ->
            val sel = i == current
            val bg = v.background as GradientDrawable
            bg.setColor(if (sel) primary else dotInactive)
            val lp = v.layoutParams as LinearLayout.LayoutParams
            val targetW = if (sel) ctx.dp(22) else ctx.dp(8)
            if (lp.width != targetW) {
                // animate width change
                val anim = ObjectAnimator.ofInt(lp.width, targetW)
                anim.addUpdateListener {
                    lp.width = it.animatedValue as Int
                    v.layoutParams = lp
                }
                anim.duration = 220
                anim.start()
            }
        }
    }

    private fun updateActionButton() {
        val isLast = current == pageCount - 1
        actionBtn.text = if (isLast) ctx.getString(R.string.onboarding_start)
        else ctx.getString(R.string.onboarding_next)
        // Content description
        actionBtn.contentDescription = actionBtn.text
    }

    private fun animatePageEntrance(page: View) {
        // Find illustration inside page and animate it subtly
        val illustration = page.findViewWithTag<View>("illustration")
        illustration?.let {
            it.scaleX = 0.96f; it.scaleY = 0.96f; it.alpha = 0f
            it.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340).setInterpolator(DecelerateInterpolator()).setStartDelay(60).start()
        }
        val title = page.findViewWithTag<View>("title")
        title?.let {
            it.translationY = ctx.dp(8).toFloat(); it.alpha = 0f
            it.animate().translationY(0f).alpha(1f).setDuration(300).setStartDelay(100).start()
        }
        val desc = page.findViewWithTag<View>("desc")
        desc?.let {
            it.translationY = ctx.dp(6).toFloat(); it.alpha = 0f
            it.animate().translationY(0f).alpha(1f).setDuration(300).setStartDelay(140).start()
        }
    }

    // ---------------- Page 1: GPS ----------------
    private fun buildPage1(): View {
        val col = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        // Illustration — white card with satellite
        val illustration = FrameLayout(ctx).apply {
            tag = "illustration"
            clipChildren = false
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(22).toFloat()
                setColor(white)
                setStroke(ctx.dp(1), Color.parseColor("#EFE7D6"))
            }
            elevation = ctx.dp(4).toFloat()
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        }
        // Blue circle with satellite icon
        val circle = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(primary, royal)
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
            }
            elevation = ctx.dp(3).toFloat()
        }
        val satIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.splash_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            // tint white via color filter if needed — splash_icon already has colors, keep as is
            setPadding(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14))
        }
        circle.addView(satIcon, FrameLayout.LayoutParams(ctx.dp(72), ctx.dp(72), Gravity.CENTER))
        card.addView(circle, LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96)).apply { gravity = Gravity.CENTER })

        // Status pill
        val pill = ctx.hbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(20).toFloat()
                setColor(Color.parseColor("#EEF3FD"))
                setStroke(ctx.dp(1), Color.parseColor("#D5E0FA"))
            }
            setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2FA66A")) }
            layoutParams = LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8))
        }
        pill.addView(dot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)))
        pill.addView(ctx.text(ctx.getString(R.string.onboarding_gps_connected), 12f, primary, Fonts.medium).apply {
            setPadding(ctx.dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER; topMargin = ctx.dp(14)
        })
        // Mock coordinates
        val coordCard = ctx.vbox().apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(12).toFloat()
                setColor(Color.parseColor("#FDFBF6"))
                setStroke(ctx.dp(1), Color.parseColor("#EEEAE1"))
            }
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
        }
        coordCard.addView(ctx.text("33.3152° N  •  44.3661° E", 12f, textDark, Fonts.medium).apply {
            gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LTR
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        coordCard.addView(ctx.text(ctx.getString(R.string.onboarding_accuracy_mock), 11f, textSec, Fonts.regular).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
        card.addView(coordCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        illustration.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = ctx.dp(8); marginEnd = ctx.dp(8)
        })
        col.addView(illustration, lp(h = ctx.dp(280)))

        // Title & desc
        val title = ctx.text(ctx.getString(R.string.onboarding_title_1), 22f, textDark, Fonts.medium).apply {
            tag = "title"; gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(title, lp().margins(t = ctx.dp(18)))
        val desc = ctx.text(ctx.getString(R.string.onboarding_desc_1), 14f, textSec, Fonts.regular).apply {
            tag = "desc"; gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f)
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(desc, lp().margins(t = ctx.dp(10), s = ctx.dp(12), e = ctx.dp(12)))
        return col
    }

    // ---------------- Page 2: Navigation ----------------
    private fun buildPage2(): View {
        val col = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val illustration = FrameLayout(ctx).apply { tag = "illustration" }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(22).toFloat()
                setColor(white)
                setStroke(ctx.dp(1), Color.parseColor("#EFE7D6"))
            }
            elevation = ctx.dp(4).toFloat()
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        // Top destination field mock
        val destField = ctx.hbox().apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(14).toFloat()
                setColor(Color.parseColor("#FDFBF6"))
                setStroke(ctx.dp(1), Color.parseColor("#EFE7D6"))
            }
            setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
            gravity = Gravity.CENTER_VERTICAL
        }
        val pin = ctx.text("◆", 10f, Color.parseColor("#3C6BFF"), Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#EEF3FD")) }
            setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(6), ctx.dp(6))
        }
        // Wrap pin in fixed size
        val pinWrap = FrameLayout(ctx).apply { addView(pin, FrameLayout.LayoutParams(ctx.dp(26), ctx.dp(26))) }
        destField.addView(pinWrap, LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26)))
        val destCol = ctx.vbox().apply { setPadding(ctx.dp(10), 0, 0, 0) }
        destCol.addView(ctx.text(ctx.getString(R.string.destination), 10f, textSec, Fonts.medium).apply { letterSpacing = 0.06f }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        destCol.addView(ctx.text("النجف — موقع محفوظ", 13f, textDark, Fonts.medium).apply { maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        destField.addView(destCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        destField.addView(ctx.text(if (isRtl()) "‹" else "›", 18f, primary, Fonts.light), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(destField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Big arrow
        val arrowWrap = FrameLayout(ctx).apply {
            setPadding(0, ctx.dp(14), 0, ctx.dp(8))
        }
        val arrowBg = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#F0F5FF"), Color.parseColor("#E8EFFF"))
            }
        }
        val arrowIcon = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_nav_navigate)
            setColorFilter(primary)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(ctx.dp(18), ctx.dp(18), ctx.dp(18), ctx.dp(18))
        }
        arrowBg.addView(arrowIcon, FrameLayout.LayoutParams(ctx.dp(88), ctx.dp(88), Gravity.CENTER))
        arrowWrap.addView(arrowBg, FrameLayout.LayoutParams(ctx.dp(120), ctx.dp(120), Gravity.CENTER))
        // Small direction hint
        val hint = ctx.text("السهم يتجه نحو وجهتك", 11f, primary, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(12).toFloat()
                setColor(Color.parseColor("#EEF3FD"))
            }
            setPadding(ctx.dp(10), ctx.dp(6), ctx.dp(10), ctx.dp(6))
        }
        // Place hint below arrow
        val arrowCol = ctx.vbox().apply { gravity = Gravity.CENTER }
        arrowCol.addView(arrowWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        arrowCol.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER; topMargin = ctx.dp(4) })
        card.addView(arrowCol, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        // Mini stats row
        val stats = ctx.hbox().apply { gravity = Gravity.CENTER }
        fun miniStat(label: String, value: String): View {
            return ctx.vbox().apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = ctx.dp(10).toFloat()
                    setColor(Color.parseColor("#F7F4EC"))
                }
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
                addView(ctx.text(label, 9f, textSec, Fonts.medium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(ctx.text(value, 12f, textDark, Fonts.medium).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
            }
        }
        stats.addView(miniStat("المسافة", "1.2 كم"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(6) })
        stats.addView(miniStat("السرعة", "42 كم/س"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(6) })
        stats.addView(miniStat("الوصول", "14:32"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(stats, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        illustration.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = ctx.dp(8); marginEnd = ctx.dp(8)
        })
        col.addView(illustration, lp(h = ctx.dp(310)))

        val title = ctx.text(ctx.getString(R.string.onboarding_title_2), 22f, textDark, Fonts.medium).apply {
            tag = "title"; gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(title, lp().margins(t = ctx.dp(16)))
        val desc = ctx.text(ctx.getString(R.string.onboarding_desc_2), 14f, textSec, Fonts.regular).apply {
            tag = "desc"; gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f); textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(desc, lp().margins(t = ctx.dp(10), s = ctx.dp(12), e = ctx.dp(12)))
        return col
    }

    // ---------------- Page 3: Radar + Portal ----------------
    private fun buildPage3(): View {
        val col = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val illustration = FrameLayout(ctx).apply { tag = "illustration" }

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(22).toFloat()
                setColor(white)
                setStroke(ctx.dp(1), Color.parseColor("#EFE7D6"))
            }
            elevation = ctx.dp(4).toFloat()
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        // Radar visual — glowing circle
        val radarWrap = FrameLayout(ctx).apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)) }
        val radarBg = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(Color.parseColor("#0A1830"), Color.parseColor("#0F2A4A"))
            }
            elevation = ctx.dp(2).toFloat()
        }
        // Inner rings + sweep hint (static)
        val inner = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.dp(1), Color.parseColor("#3A5A7A"))
            }
            alpha = 0.5f
        }
        radarBg.addView(inner, FrameLayout.LayoutParams(ctx.dp(78), ctx.dp(78), Gravity.CENTER))
        val inner2 = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(ctx.dp(1), Color.parseColor("#3A5A7A"))
            }
            alpha = 0.35f
        }
        radarBg.addView(inner2, FrameLayout.LayoutParams(ctx.dp(54), ctx.dp(54), Gravity.CENTER))
        // Center dot + sweep line
        val centerDot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#7ED8A6")) }
        }
        radarBg.addView(centerDot, FrameLayout.LayoutParams(ctx.dp(8), ctx.dp(8), Gravity.CENTER))
        // Sweep line (diagonal)
        val sweep = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(2).toFloat()
                colors = intArrayOf(Color.TRANSPARENT, Color.parseColor("#7ED8A6"))
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            rotation = -35f
            alpha = 0.9f
        }
        radarBg.addView(sweep, FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(2), Gravity.CENTER).apply {
            marginStart = ctx.dp(16)
        })
        // Red dots (mock locations)
        fun redDot(): View = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#FF3A3A")); setStroke(ctx.dp(1), Color.WHITE) }
            elevation = ctx.dp(1).toFloat()
        }
        // Place 3 dots at different positions
        radarBg.addView(redDot(), FrameLayout.LayoutParams(ctx.dp(10), ctx.dp(10)).apply { gravity = Gravity.TOP or Gravity.END; topMargin = ctx.dp(22); marginEnd = ctx.dp(28) })
        radarBg.addView(redDot(), FrameLayout.LayoutParams(ctx.dp(8), ctx.dp(8)).apply { gravity = Gravity.BOTTOM or Gravity.START; bottomMargin = ctx.dp(26); marginStart = ctx.dp(24) })
        radarBg.addView(redDot(), FrameLayout.LayoutParams(ctx.dp(9), ctx.dp(9)).apply { gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = ctx.dp(30); marginEnd = ctx.dp(22) })

        radarWrap.addView(radarBg, FrameLayout.LayoutParams(ctx.dp(120), ctx.dp(120), Gravity.CENTER))

        card.addView(radarWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Subtitle under radar
        card.addView(ctx.text("رادار حي • 3 مواقع قريبة", 11f, textSec, Fonts.medium).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(8) })

        // 4 small icons row for import/export/QR
        val iconsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        fun smallAction(iconRes: Int, label: String): View {
            val v = ctx.vbox().apply { gravity = Gravity.CENTER }
            val bg = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#F7F4EC"))
                    setStroke(ctx.dp(1), Color.parseColor("#EFE7D6"))
                }
                setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            }
            val iv = ImageView(ctx).apply {
                setImageResource(iconRes)
                setColorFilter(primary)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            bg.addView(iv, FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18), Gravity.CENTER))
            v.addView(bg, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
            v.addView(ctx.text(label, 9f, textSec, Fonts.medium).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(4) })
            return v
        }
        iconsRow.addView(smallAction(R.drawable.ic_nav_portal, "استيراد"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        iconsRow.addView(smallAction(R.drawable.ic_nav_portal, "تصدير"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        iconsRow.addView(smallAction(R.drawable.ic_nav_portal, "إنشاء QR"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        iconsRow.addView(smallAction(R.drawable.ic_nav_portal, "مسح QR"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(iconsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(12) })

        illustration.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            marginStart = ctx.dp(8); marginEnd = ctx.dp(8)
        })
        col.addView(illustration, lp(h = ctx.dp(320)))

        val title = ctx.text(ctx.getString(R.string.onboarding_title_3), 22f, textDark, Fonts.medium).apply {
            tag = "title"; gravity = Gravity.CENTER; textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(title, lp().margins(t = ctx.dp(14)))
        val desc = ctx.text(ctx.getString(R.string.onboarding_desc_3), 14f, textSec, Fonts.regular).apply {
            tag = "desc"; gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f); textDirection = View.TEXT_DIRECTION_LOCALE
        }
        col.addView(desc, lp().margins(t = ctx.dp(10), s = ctx.dp(12), e = ctx.dp(12)))
        return col
    }
}
