package com.nova.gpspro.onboarding

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Luxury 3-page onboarding experience for RMG-GPS-Pro.
 * Identity: Royal Blue (#2457D6), Crisp White (#FFFFFF), Warm Ivory (#F7F4EC).
 * Shown once after license activation; supports RTL & LTR, interactive swipe, dots, skip/next/start.
 */
class OnboardingView(
    private val ctx: Context,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    // Core brand palette
    private val ivory = Color.parseColor("#F7F4EC")
    private val ivorySoft = Color.parseColor("#FAF8F2")
    private val white = Color.parseColor("#FFFFFF")
    private val royal = Color.parseColor("#2457D6")
    private val royalBright = Color.parseColor("#356AE6")
    private val royalDeep = Color.parseColor("#1A43AD")
    private val royalPale = Color.parseColor("#EEF3FD")
    private val royalStroke = Color.parseColor("#D4E1FA")
    private val borderWarm = Color.parseColor("#E6DEC9")
    private val textPrimary = Color.parseColor("#1F2430")
    private val textSecondary = Color.parseColor("#68635B")
    private val dotIdle = Color.parseColor("#D7CEBC")
    private val greenOk = Color.parseColor("#24995E")
    private val greenPale = Color.parseColor("#E5F6EE")
    private val cardShadowTint = Color.parseColor("#242457D6")

    private var current = 0
    private val pageCount = 3
    private var finishing = false

    private val pagerContainer: FrameLayout
    private val dots: List<View>
    private val skipBtn: TextView
    private val actionBtn: TextView
    private val pages = ArrayList<View>(pageCount)
    private val animatedCanvases = ArrayList<PhaseAnimatable>(pageCount)

    // Continuous smooth ambient animation driver (60 fps)
    private val phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 9000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            val p = anim.animatedValue as Float
            if (current in animatedCanvases.indices) {
                animatedCanvases[current].setPhase(p)
            }
        }
    }

    // Interactive horizontal swipe handling
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null

    init {
        setBackgroundColor(ivory)
        isClickable = true
        layoutDirection = ctx.resources.configuration.layoutDirection

        val root = ctx.vbox().apply {
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(18))
            clipChildren = false
            clipToPadding = false
        }

        // ---------------- 1) Top Bar: Brand Emblem + Skip ----------------
        val topBar = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ctx.dp(2), 0, ctx.dp(2))
        }

        val brandChip = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val miniLogoBox = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(white, ivorySoft)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(10f)
                setStroke(ctx.dp(1), borderWarm)
            }
            elevation = this@OnboardingView.dpf(3f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            val iv = ImageView(ctx).apply {
                setImageResource(R.drawable.splash_icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                setPadding(ctx.dp(5), ctx.dp(4), ctx.dp(5), ctx.dp(5))
            }
            addView(iv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        brandChip.addView(miniLogoBox, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
        brandChip.addView(
            ctx.text(ctx.getString(R.string.app_name), 14f, royal, Fonts.bold).apply {
                letterSpacing = 0.04f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(9)
            }
        )
        topBar.addView(brandChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        skipBtn = ctx.text(ctx.getString(R.string.onboarding_skip), 13.5f, textSecondary, Fonts.medium).apply {
            gravity = Gravity.CENTER
            val r = this@OnboardingView.dpf(18f)
            background = ripple(
                roundRect(white, r, borderWarm, ctx.dp(1)),
                r,
                Color.parseColor("#182457D6")
            )
            setPadding(ctx.dp(15), ctx.dp(7), ctx.dp(15), ctx.dp(7))
            elevation = this@OnboardingView.dpf(1f)
            isClickable = true
            isFocusable = true
            textDirection = View.TEXT_DIRECTION_LOCALE
            setOnClickListener { completeAndExit() }
        }
        topBar.addView(skipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topBar, lp())

        // ---------------- 2) Pager Stage ----------------
        pagerContainer = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }

        pages.add(buildPage1())
        pages.add(buildPage2())
        pages.add(buildPage3())

        pages.forEachIndexed { idx, page ->
            page.visibility = if (idx == 0) View.VISIBLE else View.GONE
            page.alpha = if (idx == 0) 1f else 0f
            pagerContainer.addView(page, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
        root.addView(pagerContainer, lp(h = 0, weight = 1f).margins(t = ctx.dp(10), b = ctx.dp(10)))

        // ---------------- 3) Page Indicator Pills ----------------
        val dotsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        dots = (0 until pageCount).map { idx ->
            View(ctx).apply {
                val active = idx == 0
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = this@OnboardingView.dpf(6f)
                    setColor(if (active) royal else dotIdle)
                }
                layoutParams = LinearLayout.LayoutParams(if (active) ctx.dp(28) else ctx.dp(8), ctx.dp(8)).apply {
                    marginStart = ctx.dp(4)
                    marginEnd = ctx.dp(4)
                }
                isClickable = true
                setOnClickListener { if (idx != current) goTo(idx) }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        root.addView(dotsRow, lp().margins(t = ctx.dp(4)))

        // ---------------- 4) Primary Royal-Blue Action Button ----------------
        actionBtn = ctx.text("", 16f, white, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = this@OnboardingView.dpf(18f)
            val fill = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(royalBright, royal, royalDeep)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            background = ripple(fill, r, Color.parseColor("#33FFFFFF"))
            setPadding(ctx.dp(24), ctx.dp(15), ctx.dp(24), ctx.dp(15))
            elevation = this@OnboardingView.dpf(6f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            isClickable = true
            isFocusable = true
            letterSpacing = 0.02f
            setOnClickListener {
                if (current < pageCount - 1) goTo(current + 1) else completeAndExit()
            }
        }
        root.addView(actionBtn, lp(h = ctx.dp(54)).margins(t = ctx.dp(14)))
        updateActionButton()

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        post { animatePageEntrance(pages[0]) }
    }

    private fun isRtl(): Boolean = layoutDirection == View.LAYOUT_DIRECTION_RTL

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!phaseAnimator.isStarted) phaseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        phaseAnimator.cancel()
        velocityTracker?.recycle()
        velocityTracker = null
        super.onDetachedFromWindow()
    }

    // ---------------- Interactive Swipe Handling ----------------
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (finishing) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.15f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (finishing) return true
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                }
                if (dragging) {
                    val activePage = pages[current]
                    val maxShift = (width * 0.28f).coerceAtLeast(this@OnboardingView.dpf(60f))
                    val damped = (dx * 0.42f).coerceIn(-maxShift, maxShift)
                    activePage.translationX = damped
                    activePage.alpha = (1f - abs(damped) / (maxShift * 2.2f)).coerceIn(0.65f, 1f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - downX
                velocityTracker?.computeCurrentVelocity(1000)
                val vx = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null

                val activePage = pages[current]
                val threshold = (width * 0.16f).coerceAtLeast(this@OnboardingView.dpf(48f))
                val fastSwipe = abs(vx) > 450f && abs(dx) > this@OnboardingView.dpf(24f)
                if (dragging && (abs(dx) > threshold || fastSwipe) && ev.actionMasked == MotionEvent.ACTION_UP) {
                    // RTL natural paging: next page sits visually to the left, so dragging right (dx > 0) or left?
                    // Support standard directional feel: in RTL dragging right advances, and on page 0 dragging left also advances smoothly if user swipes left.
                    val rtl = isRtl()
                    val wantNext = if (rtl) dx > 0f else dx < 0f
                    val target = if (wantNext) current + 1 else current - 1
                    when {
                        target in 0 until pageCount -> goTo(target)
                        wantNext && current == pageCount - 1 -> completeAndExit()
                        // Helpful fallback on first page if an RTL user swipes left expecting forward motion
                        rtl && current == 0 && dx < -threshold -> goTo(1)
                        else -> snapBack(activePage)
                    }
                } else {
                    snapBack(activePage)
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun snapBack(page: View) {
        page.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun completeAndExit() {
        if (finishing) return
        finishing = true
        phaseAnimator.cancel()
        OnboardingManager.setCompleted(ctx)
        animate().alpha(0f).scaleX(0.985f).scaleY(0.985f).setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onFinished()
            }.start()
    }

    private fun goTo(target: Int) {
        if (finishing || target == current || target !in 0 until pageCount) return
        val from = pages[current]
        val to = pages[target]
        val forward = target > current
        val sign = if (isRtl()) (if (forward) -1 else 1) else (if (forward) 1 else -1)
        val travel = this@OnboardingView.dpf(36f)

        from.animate().cancel()
        to.animate().cancel()

        from.animate()
            .alpha(0f)
            .translationX(-sign * travel)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                from.visibility = View.GONE
                from.translationX = 0f
                from.alpha = 1f
            }.start()

        to.visibility = View.VISIBLE
        to.alpha = 0f
        to.translationX = sign * travel
        to.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(270)
            .setInterpolator(DecelerateInterpolator())
            .start()

        current = target
        animatePageEntrance(to)
        updateDots()
        updateActionButton()
        skipBtn.animate().alpha(if (current == pageCount - 1) 0.72f else 1f).setDuration(180).start()
    }

    private fun updateDots() {
        dots.forEachIndexed { idx, dot ->
            val sel = idx == current
            val bg = dot.background as GradientDrawable
            bg.setColor(if (sel) royal else dotIdle)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            val targetW = if (sel) ctx.dp(28) else ctx.dp(8)
            if (lp.width != targetW) {
                ValueAnimator.ofInt(lp.width, targetW).apply {
                    duration = 230
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { a ->
                        lp.width = a.animatedValue as Int
                        dot.layoutParams = lp
                    }
                    start()
                }
            }
        }
    }

    private fun updateActionButton() {
        val label = if (current == pageCount - 1) {
            ctx.getString(R.string.onboarding_start)
        } else {
            ctx.getString(R.string.onboarding_next)
        }
        actionBtn.text = label
        actionBtn.contentDescription = label
    }

    private fun animatePageEntrance(page: View) {
        page.findViewWithTag<View>("card")?.let { card ->
            card.scaleX = 0.965f
            card.scaleY = 0.965f
            card.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        page.findViewWithTag<View>("badge")?.let { b ->
            b.alpha = 0f
            b.translationY = this@OnboardingView.dpf(6f)
            b.animate().alpha(1f).translationY(0f).setStartDelay(50).setDuration(250)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        page.findViewWithTag<View>("title")?.let { t ->
            t.alpha = 0f
            t.translationY = this@OnboardingView.dpf(9f)
            t.animate().alpha(1f).translationY(0f).setStartDelay(90).setDuration(270)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        page.findViewWithTag<View>("desc")?.let { d ->
            d.alpha = 0f
            d.translationY = this@OnboardingView.dpf(8f)
            d.animate().alpha(1f).translationY(0f).setStartDelay(130).setDuration(280)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ---------------- Shared Luxury Page Shell ----------------
    /**
     * Keeps proportions balanced across compact and ultra-tall phones without crowding or huge gaps.
     */
    private fun buildBalancedPage(
        heroCardContent: View,
        badgeRes: Int,
        titleRes: Int,
        descRes: Int
    ): View {
        val wrapper = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }

        val inner = object : LinearLayout(ctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = ctx.dp(560)
                val hMode = MeasureSpec.getMode(heightMeasureSpec)
                val hSize = MeasureSpec.getSize(heightMeasureSpec)
                val cappedSpec = if (hMode != MeasureSpec.UNSPECIFIED && hSize > maxH) {
                    MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                } else {
                    heightMeasureSpec
                }
                super.onMeasure(widthMeasureSpec, cappedSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
        }

        val heroCard = LinearLayout(ctx).apply {
            tag = "card"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(28f)
                setColor(white)
                setStroke(ctx.dp(1), borderWarm)
            }
            elevation = this@OnboardingView.dpf(6f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        heroCard.addView(heroCardContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inner.addView(heroCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = ctx.dp(2)
            marginEnd = ctx.dp(2)
        })

        // Eyebrow badge
        val badge = ctx.text(ctx.getString(badgeRes), 11f, royal, Fonts.bold).apply {
            tag = "badge"
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(14f)
                setColor(royalPale)
                setStroke(ctx.dp(1), royalStroke)
            }
            setPadding(ctx.dp(13), ctx.dp(5), ctx.dp(13), ctx.dp(5))
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        inner.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ctx.dp(16)
        })

        // Main Title
        val title = ctx.text(ctx.getString(titleRes), 22.5f, textPrimary, Fonts.bold).apply {
            tag = "title"
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setLineSpacing(0f, 1.12f)
        }
        inner.addView(title, lp().margins(t = ctx.dp(10), s = ctx.dp(6), e = ctx.dp(6)))

        // Description
        val desc = ctx.text(ctx.getString(descRes), 14.5f, textSecondary, Fonts.regular).apply {
            tag = "desc"
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setLineSpacing(0f, 1.38f)
        }
        inner.addView(desc, lp().margins(t = ctx.dp(8), s = ctx.dp(10), e = ctx.dp(10)))

        wrapper.addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        return wrapper
    }

    // =========================================================================
    // PAGE 1: GPS, Satellites & Coordinate Precision
    // =========================================================================
    private fun buildPage1(): View {
        val body = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        val canvasView = GpsSatelliteArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Status + satellites row
        val statusRow = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        val livePill = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(16f)
                setColor(greenPale)
            }
            setPadding(ctx.dp(11), ctx.dp(6), ctx.dp(12), ctx.dp(6))
        }
        val greenDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(greenOk)
            }
        }
        livePill.addView(greenDot, LinearLayout.LayoutParams(ctx.dp(8), ctx.dp(8)))
        livePill.addView(
            ctx.text(ctx.getString(R.string.onboarding_gps_connected), 12f, greenOk, Fonts.bold),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(6)
            }
        )

        val satPill = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(16f)
                setColor(royalPale)
                setStroke(ctx.dp(1), royalStroke)
            }
            setPadding(ctx.dp(11), ctx.dp(6), ctx.dp(11), ctx.dp(6))
        }
        satPill.addView(SignalBarsMiniView(ctx, royal), LinearLayout.LayoutParams(ctx.dp(15), ctx.dp(12)))
        satPill.addView(
            ctx.text(ctx.getString(R.string.onboarding_sats_mock), 11.5f, royal, Fonts.bold),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(6)
            }
        )

        statusRow.addView(livePill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        statusRow.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        statusRow.addView(satPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(statusRow, lp().margins(t = ctx.dp(12)))

        // Coordinate readout strip (handles large font scales cleanly without wrapping collisions)
        val coordStrip = ctx.vbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(15f)
                setColor(ivory)
                setStroke(ctx.dp(1), borderWarm)
            }
            setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
        }
        coordStrip.addView(
            ctx.text("32.0258° N  •  44.3462° E", 13f, textPrimary, Fonts.bold).apply {
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_LTR
                maxLines = 1
            },
            lp()
        )
        coordStrip.addView(
            ctx.text(ctx.getString(R.string.onboarding_accuracy_mock), 11.5f, royal, Fonts.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            },
            lp().margins(t = ctx.dp(2))
        )
        body.addView(coordStrip, lp().margins(t = ctx.dp(9)))

        return buildBalancedPage(
            heroCardContent = body,
            badgeRes = R.string.onboarding_badge_1,
            titleRes = R.string.onboarding_title_1,
            descRes = R.string.onboarding_desc_1
        )
    }

    // =========================================================================
    // PAGE 2: Smart Navigation & Royal-Blue Direction Arrow
    // =========================================================================
    private fun buildPage2(): View {
        val body = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        // Destination header bar inside card
        val destBar = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(16f)
                setColor(ivory)
                setStroke(ctx.dp(1), borderWarm)
            }
            setPadding(ctx.dp(11), ctx.dp(9), ctx.dp(12), ctx.dp(9))
        }
        val pinBadge = FrameLayout(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(royalPale)
                setStroke(ctx.dp(1), royalStroke)
            }
            addView(PinMiniIconView(ctx, royal), LayoutParams(ctx.dp(16), ctx.dp(16), Gravity.CENTER))
        }
        destBar.addView(pinBadge, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))

        val destTexts = ctx.vbox().apply {
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
        }
        destTexts.addView(
            ctx.text(ctx.getString(R.string.destination), 10.5f, textSecondary, Fonts.medium).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        destTexts.addView(
            ctx.text(ctx.getString(R.string.onboarding_dest_mock), 13f, textPrimary, Fonts.bold).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        destBar.addView(destTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val bearingBadge = ctx.text("024° NE", 11.5f, white, Fonts.bold).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(11f)
                setColor(royal)
            }
            setPadding(ctx.dp(10), ctx.dp(5), ctx.dp(10), ctx.dp(5))
        }
        destBar.addView(bearingBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(destBar, lp())

        // Large compass + royal-blue arrow artwork
        val navCanvas = SmartNavigationArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(navCanvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ctx.dp(10)
            bottomMargin = ctx.dp(10)
        })

        // 3 balanced live trip metrics
        val statsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        fun metricTile(labelRes: Int, valRes: Int): View = ctx.vbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(14f)
                setColor(ivory)
                setStroke(ctx.dp(1), borderWarm)
            }
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            addView(
                ctx.text(ctx.getString(labelRes), 10.5f, textSecondary, Fonts.medium).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                lp()
            )
            addView(
                ctx.text(ctx.getString(valRes), 13.5f, royal, Fonts.bold).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                lp().margins(t = ctx.dp(2))
            )
        }

        statsRow.addView(metricTile(R.string.onboarding_stat_dist_label, R.string.onboarding_stat_dist_val),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(7) })
        statsRow.addView(metricTile(R.string.onboarding_stat_speed_label, R.string.onboarding_stat_speed_val),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ctx.dp(7) })
        statsRow.addView(metricTile(R.string.onboarding_stat_eta_label, R.string.onboarding_stat_eta_val),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(statsRow, lp())

        return buildBalancedPage(
            heroCardContent = body,
            badgeRes = R.string.onboarding_badge_2,
            titleRes = R.string.onboarding_title_2,
            descRes = R.string.onboarding_desc_2
        )
    }

    // =========================================================================
    // PAGE 3: Live Radar & QR Location Portal
    // =========================================================================
    private fun buildPage3(): View {
        val body = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        val radarQrCanvas = RadarPortalArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(radarQrCanvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 4 distinct custom-drawn vector tool chips (Radar, Import, Export, QR)
        val actionsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        fun toolChip(kind: PortalGlyphView.Kind, labelRes: Int): View = ctx.vbox().apply {
            gravity = Gravity.CENTER
            val circle = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ivory)
                    setStroke(ctx.dp(1), borderWarm)
                }
                addView(PortalGlyphView(ctx, kind, royal), LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
            }
            addView(circle, LinearLayout.LayoutParams(ctx.dp(42), ctx.dp(42)))
            addView(
                ctx.text(ctx.getString(labelRes), 11f, textPrimary, Fonts.medium).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(5)
                }
            )
        }

        actionsRow.addView(toolChip(PortalGlyphView.Kind.RADAR, R.string.onboarding_action_radar),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionsRow.addView(toolChip(PortalGlyphView.Kind.IMPORT, R.string.onboarding_action_import),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionsRow.addView(toolChip(PortalGlyphView.Kind.EXPORT, R.string.onboarding_action_export),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionsRow.addView(toolChip(PortalGlyphView.Kind.QR, R.string.onboarding_action_qr),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        body.addView(actionsRow, lp().margins(t = ctx.dp(13)))

        return buildBalancedPage(
            heroCardContent = body,
            badgeRes = R.string.onboarding_badge_3,
            titleRes = R.string.onboarding_title_3,
            descRes = R.string.onboarding_desc_3
        )
    }

    // =========================================================================
    // Custom High-Craft Vector / Canvas Illustrations
    // =========================================================================
    private interface PhaseAnimatable {
        fun setPhase(phase: Float)
    }

    /**
     * Screen 1 Artwork: Geodesic grid, concentric satellite orbits, 3 orbiting GNSS satellites
     * transmitting lock beams, pulsing accuracy halo, and central emblem with the royal-blue arrow.
     */
    private class GpsSatelliteArtworkView(ctx: Context) : View(ctx), PhaseAnimatable {
        private var phase = 0f
        private val logo: Drawable? = ctx.getDrawable(R.drawable.splash_icon)?.mutate()

        private val stageRect = RectF()
        private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#E3ECFA")
            strokeWidth = dpf(1f)
        }
        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#142457D6")
            strokeWidth = dpf(1f)
        }
        private val orbitOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#3D2457D6")
            strokeWidth = dpf(1.2f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(5f)), 0f)
        }
        private val orbitInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#522457D6")
            strokeWidth = dpf(1.3f)
        }
        private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#442457D6")
            strokeWidth = dpf(1.1f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(3f), dpf(4f)), 0f)
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.6f)
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val centerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(2f)
        }
        private val satBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val satPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2457D6") }
        private val satStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(1.4f)
        }
        private val satLedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#24995E") }
        private val coreShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(34, 36, 87, 214) }
        private val clipPath = Path()

        override fun setPhase(phase: Float) {
            this.phase = phase
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            stageRect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f))
            stagePaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#F4F8FF"),
                Color.parseColor("#FCFBF7"),
                Shader.TileMode.CLAMP
            )
            val r = min(w, h) * 0.42f
            if (r > 1f) {
                haloPaint.shader = RadialGradient(
                    w / 2f, h / 2f, r,
                    intArrayOf(Color.parseColor("#2A2457D6"), Color.parseColor("#002457D6")),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            clipPath.reset()
            clipPath.addRoundRect(stageRect, dpf(20f), dpf(20f), Path.Direction.CW)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = min(w, h)
            if (s <= dpf(8f)) return
            val cx = w / 2f
            val cy = h / 2f

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stagePaint)

            // Subtle geodesic grid lines
            val step = s * 0.18f
            var gx = cx - step * 3
            while (gx <= cx + step * 3) {
                canvas.drawLine(gx, 0f, gx, h, gridPaint)
                gx += step
            }
            var gy = cy - step * 3
            while (gy <= cy + step * 3) {
                canvas.drawLine(0f, gy, w, gy, gridPaint)
                gy += step
            }

            // Soft radial glow
            canvas.drawCircle(cx, cy, s * 0.42f, haloPaint)

            val outerR = s * 0.39f
            val innerR = s * 0.265f

            // Expanding signal pulse waves
            for (i in 0..1) {
                val p = (phase * 2f + i * 0.5f) % 1f
                val pr = s * 0.14f + p * (outerR - s * 0.12f)
                val alpha = ((1f - p) * 95f).toInt().coerceIn(0, 255)
                pulsePaint.color = Color.argb(alpha, 36, 87, 214)
                canvas.drawCircle(cx, cy, pr, pulsePaint)
            }

            // Orbits
            canvas.drawCircle(cx, cy, outerR, orbitOuterPaint)
            canvas.drawCircle(cx, cy, innerR, orbitInnerPaint)

            // 3 Orbiting GNSS satellites
            val baseAngles = floatArrayOf(-38f, 92f, 212f)
            val radii = floatArrayOf(outerR, innerR, outerR)
            val speeds = floatArrayOf(1f, -1f, 1f)
            for (i in 0..2) {
                val deg = baseAngles[i] + speeds[i] * phase * 360f * 0.35f
                val rad = deg * (PI / 180.0)
                val sx = cx + (cos(rad) * radii[i]).toFloat()
                val sy = cy + (sin(rad) * radii[i]).toFloat()
                canvas.drawLine(sx, sy, cx, cy, beamPaint)
                drawSatelliteNode(canvas, sx, sy, deg + 45f, s * 0.055f)
            }

            // Central precision reticle + white disc + royal-blue arrow emblem
            val coreR = (s * 0.155f).coerceAtLeast(dpf(26f))
            // Crosshair ticks around core
            val tickIn = coreR + dpf(3f)
            val tickOut = coreR + dpf(8f)
            canvas.drawLine(cx, cy - tickOut, cx, cy - tickIn, centerRingPaint)
            canvas.drawLine(cx, cy + tickIn, cx, cy + tickOut, centerRingPaint)
            canvas.drawLine(cx - tickOut, cy, cx - tickIn, cy, centerRingPaint)
            canvas.drawLine(cx + tickIn, cy, cx + tickOut, cy, centerRingPaint)

            // Soft shadow under central disc
            canvas.drawCircle(cx, cy + dpf(3f), coreR + dpf(1f), coreShadowPaint)
            canvas.drawCircle(cx, cy, coreR, centerDiscPaint)
            canvas.drawCircle(cx, cy, coreR, centerRingPaint)

            logo?.let { d ->
                val half = (coreR * 0.66f).toInt()
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.draw(canvas)
            }

            canvas.restore()
            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stageStroke)
        }

        private fun drawSatelliteNode(canvas: Canvas, x: Float, y: Float, angleDeg: Float, unit: Float) {
            val u = unit.coerceIn(dpf(8f), dpf(13f))
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angleDeg)

            // Solar wings
            val wingW = u * 1.15f
            val wingH = u * 0.62f
            val bodyHalf = u * 0.44f
            val leftWing = RectF(-bodyHalf - wingW - dpf(2f), -wingH / 2f, -bodyHalf - dpf(2f), wingH / 2f)
            val rightWing = RectF(bodyHalf + dpf(2f), -wingH / 2f, bodyHalf + wingW + dpf(2f), wingH / 2f)
            canvas.drawRoundRect(leftWing, dpf(2.5f), dpf(2.5f), satPanelPaint)
            canvas.drawRoundRect(rightWing, dpf(2.5f), dpf(2.5f), satPanelPaint)

            // Central bus
            val body = RectF(-bodyHalf, -bodyHalf, bodyHalf, bodyHalf)
            canvas.drawRoundRect(body, dpf(3f), dpf(3f), satBodyPaint)
            canvas.drawRoundRect(body, dpf(3f), dpf(3f), satStrokePaint)
            canvas.drawCircle(0f, 0f, dpf(2.2f), satLedPaint)

            canvas.restore()
        }
    }

    /**
     * Screen 2 Artwork: Luxury precision navigation compass bezel, active bearing arc,
     * pulsing target beacon, and large central royal-blue RMG navigation arrow smoothly tracking target.
     */
    private class SmartNavigationArtworkView(ctx: Context) : View(ctx), PhaseAnimatable {
        private var phase = 0f
        private val arrowLogo: Drawable? = ctx.getDrawable(R.drawable.splash_icon)?.mutate()

        private val stageRect = RectF()
        private val arcRect = RectF()
        private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#E3ECFA")
            strokeWidth = dpf(1f)
        }
        private val outerFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#D4E1FA")
            strokeWidth = dpf(1.6f)
        }
        private val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#EAF0FC")
            strokeWidth = dpf(5f)
        }
        private val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C7D4EE")
            strokeWidth = dpf(1.1f)
            strokeCap = Paint.Cap.ROUND
        }
        private val tickMajor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(1.9f)
            strokeCap = Paint.Cap.ROUND
        }
        private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Fonts.bold
            color = Color.parseColor("#2457D6")
        }
        private val subCardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Fonts.medium
            color = Color.parseColor("#8A96AE")
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val discShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 36, 87, 214) }
        private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2457D6") }
        private val beaconHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#382457D6")
            strokeWidth = dpf(1.4f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(4f), dpf(4f)), 0f)
        }

        override fun setPhase(phase: Float) {
            this.phase = phase
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            stageRect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f))
            stagePaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#F5F8FF"),
                Color.parseColor("#FCFBF7"),
                Shader.TileMode.CLAMP
            )
            val r = min(w, h) * 0.46f
            if (r > 1f) {
                haloPaint.shader = RadialGradient(
                    w / 2f, h / 2f, r,
                    intArrayOf(Color.parseColor("#222457D6"), Color.parseColor("#002457D6")),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = min(w, h)
            if (s <= dpf(8f)) return
            val cx = w / 2f
            val cy = h / 2f

            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stagePaint)
            canvas.drawCircle(cx, cy, s * 0.45f, haloPaint)

            val dialR = s * 0.415f
            // Soft shadow under compass disc
            canvas.drawCircle(cx, cy + dpf(3.5f), dialR, discShadowPaint)
            canvas.drawCircle(cx, cy, dialR, outerFacePaint)
            canvas.drawCircle(cx, cy, dialR, outerRingPaint)

            // Graduated compass ticks
            for (deg in 0 until 360 step 6) {
                val major = deg % 30 == 0
                val len = if (major) s * 0.036f else s * 0.019f
                val p = if (major) tickMajor else tickMinor
                canvas.save()
                canvas.rotate(deg.toFloat(), cx, cy)
                canvas.drawLine(cx, cy - dialR + dpf(5f), cx, cy - dialR + dpf(5f) + len, p)
                canvas.restore()
            }

            // Cardinal letters N / E / S / W
            val textR = dialR - s * 0.082f
            cardinalPaint.textSize = (s * 0.052f).coerceIn(dpf(10f), dpf(14f))
            subCardinalPaint.textSize = cardinalPaint.textSize * 0.92f
            val labels = arrayOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
            for ((lbl, ang) in labels) {
                val rad = (ang - 90f) * (PI / 180.0)
                val lx = cx + (cos(rad) * textR).toFloat()
                val ly = cy + (sin(rad) * textR).toFloat()
                val p = if (lbl == "N") cardinalPaint else subCardinalPaint
                val fm = p.fontMetrics
                canvas.drawText(lbl, lx, ly - (fm.ascent + fm.descent) / 2f, p)
            }

            // Inner bearing ring + live target angle (24° +/- gentle 4.5° live tracking motion)
            val trackR = dialR * 0.72f
            arcRect.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
            canvas.drawOval(arcRect, innerTrackPaint)

            val targetAngle = 24f + 4.5f * sin(phase * 2.0 * PI).toFloat()
            canvas.drawArc(arcRect, -90f, targetAngle, false, activeArcPaint)

            // Target beacon on the bearing ring
            val targetRad = (targetAngle - 90f) * (PI / 180.0)
            val bx = cx + (cos(targetRad) * trackR).toFloat()
            val by = cy + (sin(targetRad) * trackR).toFloat()

            canvas.drawLine(cx, cy, bx, by, guideLinePaint)

            val pulse = 0.5f + 0.5f * sin(phase * 4.0 * PI).toFloat()
            beaconHaloPaint.color = Color.argb((45 + pulse * 55).toInt(), 36, 87, 214)
            canvas.drawCircle(bx, by, dpf(9f) + pulse * dpf(4f), beaconHaloPaint)
            canvas.drawCircle(bx, by, dpf(5.5f), outerFacePaint)
            canvas.drawCircle(bx, by, dpf(4f), beaconPaint)

            // Large Royal-Blue RMG Navigation Arrow aligned to targetAngle
            arrowLogo?.let { d ->
                val half = (dialR * 0.44f).toInt()
                canvas.save()
                canvas.rotate(targetAngle, cx, cy)
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.draw(canvas)
                canvas.restore()
            }

            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stageStroke)
        }
    }

    /**
     * Screen 3 Artwork: Deep navy/royal-blue live radar disc with rotating sweep beam and glowing
     * nearby target blips + crisp floating white QR location card with deterministic royal-blue QR matrix.
     */
    private class RadarPortalArtworkView(ctx: Context) : View(ctx), PhaseAnimatable {
        private var phase = 0f

        private val stageRect = RectF()
        private val radarOval = RectF()
        private val qrCardRect = RectF()
        private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#E3ECFA")
            strokeWidth = dpf(1f)
        }
        private val radarDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val radarOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 36, 87, 214) }
        private val radarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(2.2f)
        }
        private val radarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#385E99")
            strokeWidth = dpf(1f)
        }
        private val sweepSectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6CE5FF")
            strokeWidth = dpf(1.8f)
            strokeCap = Paint.Cap.ROUND
        }
        private val centerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#446CE5FF")
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val linkDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#552457D6")
            strokeWidth = dpf(1.4f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(4f), dpf(4f)), 0f)
        }
        private val qrCardShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(36, 20, 48, 120)
        }
        private val qrCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val qrCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CFDDF8")
            strokeWidth = dpf(1.3f)
        }
        private val qrInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2457D6")
            style = Paint.Style.FILL
        }
        private val qrScanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#88356AE6")
            strokeWidth = dpf(1.8f)
            strokeCap = Paint.Cap.ROUND
        }

        // Deterministic 11x11 crisp mini QR matrix (3 finder corners + clean data modules)
        private val qrMatrix = arrayOf(
            "11111010111",
            "10001001001",
            "10101011101",
            "10001000101",
            "11111010111",
            "00000010000",
            "11111011010",
            "10001000101",
            "10101011011",
            "10001010001",
            "11111001101"
        )

        override fun setPhase(phase: Float) {
            this.phase = phase
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            stageRect.set(dpf(2f), dpf(2f), w - dpf(2f), h - dpf(2f))
            stagePaint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#F4F8FF"),
                Color.parseColor("#FCFBF7"),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = min(w, h)
            if (s <= dpf(8f)) return
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stagePaint)

            // Balanced two-element composition: main radar disc + floating QR badge
            val radarR = (s * 0.37f).coerceAtMost(w * 0.32f)
            val shiftX = if (rtl) radarR * 0.24f else -radarR * 0.24f
            val rcx = w / 2f + shiftX
            val rcy = h / 2f - s * 0.02f

            radarDiscPaint.shader = RadialGradient(
                rcx, rcy - radarR * 0.2f, radarR * 1.15f,
                intArrayOf(Color.parseColor("#16346B"), Color.parseColor("#0B1933")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )

            // Outer glow + disc
            canvas.drawCircle(rcx, rcy + dpf(3f), radarR + dpf(4f), radarOuterGlowPaint)
            canvas.drawCircle(rcx, rcy, radarR, radarDiscPaint)
            canvas.drawCircle(rcx, rcy, radarR, radarBorderPaint)

            // Concentric radar rings & crosshairs
            canvas.drawCircle(rcx, rcy, radarR * 0.68f, radarRingPaint)
            canvas.drawCircle(rcx, rcy, radarR * 0.38f, radarRingPaint)
            canvas.drawLine(rcx - radarR + dpf(6f), rcy, rcx + radarR - dpf(6f), rcy, radarRingPaint)
            canvas.drawLine(rcx, rcy - radarR + dpf(6f), rcx, rcy + radarR - dpf(6f), radarRingPaint)

            // Rotating radar sweep cone (smooth 360°)
            val sweepDeg = (phase * 360f * 2f) % 360f
            radarOval.set(rcx - radarR + dpf(2f), rcy - radarR + dpf(2f), rcx + radarR - dpf(2f), rcy + radarR - dpf(2f))
            for (step in 0 until 10) {
                val frac = (step + 1) / 10f
                sweepSectorPaint.color = Color.argb((frac * 26f).toInt(), 92, 225, 255)
                canvas.drawArc(radarOval, sweepDeg - 50f + step * 5f, 5.5f, true, sweepSectorPaint)
            }
            val sweepRad = sweepDeg * (PI / 180.0)
            canvas.drawLine(
                rcx, rcy,
                rcx + (cos(sweepRad) * (radarR - dpf(3f))).toFloat(),
                rcy + (sin(sweepRad) * (radarR - dpf(3f))).toFloat(),
                sweepLinePaint
            )

            // 3 Radar target blips that brighten as sweep passes
            val blipAngles = floatArrayOf(-42f, 48f, 152f)
            val blipRadii = floatArrayOf(0.56f, 0.72f, 0.46f)
            for (i in 0..2) {
                val a = blipAngles[i]
                val r = radarR * blipRadii[i]
                val rad = a * (PI / 180.0)
                val tx = rcx + (cos(rad) * r).toFloat()
                val ty = rcy + (sin(rad) * r).toFloat()

                val diff = ((sweepDeg - a) % 360f + 360f) % 360f
                val glow = if (diff < 95f) (1f - diff / 95f) else 0.25f

                blipPaint.color = Color.argb((80 + glow * 120).toInt(), 255, 92, 92)
                canvas.drawCircle(tx, ty, dpf(6.5f) + glow * dpf(2.5f), blipPaint)
                blipPaint.color = Color.parseColor("#FF4D4D")
                canvas.drawCircle(tx, ty, dpf(3.6f), blipPaint)
                blipPaint.color = Color.WHITE
                canvas.drawCircle(tx, ty, dpf(1.4f), blipPaint)
            }

            // Center user beacon
            canvas.drawCircle(rcx, rcy, dpf(7f), centerHaloPaint)
            canvas.drawCircle(rcx, rcy, dpf(3.3f), centerDotPaint)

            // Floating QR Badge Card on the opposite corner
            val qrSize = (s * 0.37f).coerceIn(dpf(68f), dpf(96f))
            val qx = if (rtl) (rcx - radarR - qrSize * 0.42f).coerceAtLeast(dpf(14f))
            else (rcx + radarR - qrSize * 0.58f).coerceAtMost(w - qrSize - dpf(14f))
            val qy = (rcy + radarR * 0.16f).coerceAtMost(h - qrSize - dpf(12f))

            // Dashed connection from radar target to QR card
            canvas.drawLine(rcx + (if (rtl) -radarR * 0.4f else radarR * 0.4f), rcy + radarR * 0.3f, qx + qrSize / 2f, qy + qrSize / 2f, linkDashPaint)

            qrCardRect.set(qx, qy + dpf(3f), qx + qrSize, qy + qrSize + dpf(3f))
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardShadow)
            qrCardRect.set(qx, qy, qx + qrSize, qy + qrSize)
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardFill)
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardStroke)

            // Draw crisp QR matrix inside badge
            val pad = qrSize * 0.16f
            val innerSize = qrSize - pad * 2f
            val n = qrMatrix.size
            val cell = innerSize / n
            for (r in 0 until n) {
                val row = qrMatrix[r]
                for (c in 0 until n) {
                    if (row[c] == '1') {
                        val left = qx + pad + c * cell
                        val top = qy + pad + r * cell
                        canvas.drawRoundRect(
                            left, top, left + cell * 0.88f, top + cell * 0.88f,
                            cell * 0.22f, cell * 0.22f, qrInkPaint
                        )
                    }
                }
            }

            // Gentle scan laser line across QR card
            val scanT = 0.5f + 0.5f * sin(phase * 2.0 * PI * 2.0).toFloat()
            val scanY = qy + pad + scanT * innerSize
            canvas.drawLine(qx + pad * 0.7f, scanY, qx + qrSize - pad * 0.7f, scanY, qrScanLinePaint)

            canvas.drawRoundRect(stageRect, dpf(20f), dpf(20f), stageStroke)
        }
    }

    // ---------------- Crisp Helper Micro-Icons ----------------
    private class SignalBarsMiniView(ctx: Context, color: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val barW = w / 6.2f
            val gap = barW * 0.65f
            for (i in 0..3) {
                val bh = h * (0.35f + i * 0.21f)
                val left = i * (barW + gap)
                canvas.drawRoundRect(left, h - bh, left + barW, h, barW / 2f, barW / 2f, p)
            }
        }
    }

    private class PinMiniIconView(ctx: Context, color: Int) : View(ctx) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeWidth = dpf(1.7f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }
        private val tipPath = Path()
        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val h = height.toFloat()
            val r = min(width, height) * 0.30f
            val cy = h * 0.38f
            canvas.drawCircle(cx, cy, r * 0.36f, dotFill)
            tipPath.reset()
            tipPath.moveTo(cx - r * 0.88f, cy + r * 0.45f)
            tipPath.lineTo(cx, h * 0.90f)
            tipPath.lineTo(cx + r * 0.88f, cy + r * 0.45f)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 155f, 230f, false, stroke)
            canvas.drawPath(tipPath, stroke)
        }
    }

    private class PortalGlyphView(ctx: Context, private val kind: Kind, color: Int) : View(ctx) {
        enum class Kind { RADAR, IMPORT, EXPORT, QR }

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.color = color
            strokeWidth = dpf(1.7f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            this.color = color
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = min(w, h) * 0.40f

            when (kind) {
                Kind.RADAR -> {
                    canvas.drawCircle(cx, cy, r, stroke)
                    canvas.drawCircle(cx, cy, r * 0.52f, stroke)
                    canvas.drawLine(cx, cy, cx + r * 0.72f, cy - r * 0.68f, stroke)
                    canvas.drawCircle(cx, cy, dpf(1.6f), fill)
                }
                Kind.IMPORT -> {
                    // Tray + downward arrow into tray
                    val trayTop = cy + r * 0.25f
                    val trayBottom = cy + r * 0.85f
                    canvas.drawLine(cx - r * 0.85f, trayTop, cx - r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx - r * 0.85f, trayBottom, cx + r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx + r * 0.85f, trayBottom, cx + r * 0.85f, trayTop, stroke)
                    // Arrow down
                    val aTop = cy - r * 0.85f
                    val aBot = cy + r * 0.35f
                    canvas.drawLine(cx, aTop, cx, aBot, stroke)
                    canvas.drawLine(cx - r * 0.42f, aBot - r * 0.40f, cx, aBot, stroke)
                    canvas.drawLine(cx + r * 0.42f, aBot - r * 0.40f, cx, aBot, stroke)
                }
                Kind.EXPORT -> {
                    // Tray + upward arrow out of tray
                    val trayTop = cy + r * 0.25f
                    val trayBottom = cy + r * 0.85f
                    canvas.drawLine(cx - r * 0.85f, trayTop, cx - r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx - r * 0.85f, trayBottom, cx + r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx + r * 0.85f, trayBottom, cx + r * 0.85f, trayTop, stroke)
                    // Arrow up
                    val aTop = cy - r * 0.85f
                    val aBot = cy + r * 0.35f
                    canvas.drawLine(cx, aBot, cx, aTop, stroke)
                    canvas.drawLine(cx - r * 0.42f, aTop + r * 0.40f, cx, aTop, stroke)
                    canvas.drawLine(cx + r * 0.42f, aTop + r * 0.40f, cx, aTop, stroke)
                }
                Kind.QR -> {
                    val m = min(w, h) * 0.14f
                    val half = (min(w, h) - m * 2f) * 0.42f
                    val g = (min(w, h) - m * 2f) - half * 2f
                    val x1 = m
                    val y1 = m
                    val x2 = m + half + g
                    val y2 = m + half + g
                    canvas.drawRoundRect(x1, y1, x1 + half, y1 + half, dpf(1.8f), dpf(1.8f), stroke)
                    canvas.drawRoundRect(x2, y1, x2 + half, y1 + half, dpf(1.8f), dpf(1.8f), stroke)
                    canvas.drawRoundRect(x1, y2, x1 + half, y2 + half, dpf(1.8f), dpf(1.8f), stroke)
                    canvas.drawCircle(x2 + half * 0.3f, y2 + half * 0.3f, dpf(1.5f), fill)
                    canvas.drawCircle(x2 + half * 0.78f, y2 + half * 0.3f, dpf(1.5f), fill)
                    canvas.drawCircle(x2 + half * 0.3f, y2 + half * 0.78f, dpf(1.5f), fill)
                    canvas.drawCircle(x2 + half * 0.78f, y2 + half * 0.78f, dpf(1.5f), fill)
                }
            }
        }
    }
}
