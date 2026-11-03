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
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
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
 * Ultra-Premium 3-page onboarding experience for RMG-GPS-Pro.
 * Signature identity: Royal Blue (#2457D6), Pure White (#FFFFFF), Warm Ivory (#F7F4EC).
 * Displayed strictly once after license activation; supports RTL & LTR, smooth interactive swipe,
 * animated pill indicators, and Skip / Next / Start controls.
 */
class OnboardingView(
    private val ctx: Context,
    private val onFinished: () -> Unit
) : FrameLayout(ctx) {

    // Signature Brand Palette
    private val ivory = Color.parseColor("#F7F4EC")
    private val ivorySoft = Color.parseColor("#FCFBF7")
    private val white = Color.parseColor("#FFFFFF")
    private val royal = Color.parseColor("#2457D6")
    private val royalBright = Color.parseColor("#3B73F0")
    private val royalDeep = Color.parseColor("#183FA3")
    private val royalPale = Color.parseColor("#EDF3FE")
    private val royalStroke = Color.parseColor("#CEDDFB")
    private val borderWarm = Color.parseColor("#E4DCC8")
    private val textPrimary = Color.parseColor("#182032")
    private val textSecondary = Color.parseColor("#625E56")
    private val dotIdle = Color.parseColor("#D6CDBA")
    private val greenOk = Color.parseColor("#1E9E62")
    private val greenPale = Color.parseColor("#E6F7EE")
    private val cardShadowTint = Color.parseColor("#262457D6")

    private val smoothOut = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    // Subtle luxury ambient glow behind cards
    private val ambientTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ambientBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var current = 0
    private val pageCount = 3
    private var finishing = false

    private val pagerContainer: FrameLayout
    private val dots: List<View>
    private val skipBtn: TextView
    private val actionBtn: TextView
    private val pages = ArrayList<View>(pageCount)
    private val animatedCanvases = ArrayList<PhaseAnimatable>(pageCount)

    // Continuous smooth 60fps ambient animation driver
    private val phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 10_000L
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
        setWillNotDraw(false)
        setBackgroundColor(ivory)
        isClickable = true
        layoutDirection = ctx.resources.configuration.layoutDirection

        val root = ctx.vbox().apply {
            setPadding(ctx.dp(20), ctx.dp(10), ctx.dp(20), ctx.dp(18))
            clipChildren = false
            clipToPadding = false
        }

        // ---------------- 1) Refined Top Header: Brand Emblem + Skip ----------------
        val topBar = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
        }

        val brandChip = ctx.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val miniLogoBox = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(white, ivorySoft)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(11f)
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
        brandChip.addView(miniLogoBox, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))
        brandChip.addView(
            ctx.text(ctx.getString(R.string.app_name), 14.5f, royal, Fonts.bold).apply {
                letterSpacing = 0.035f
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = ctx.dp(10)
            }
        )
        topBar.addView(brandChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        skipBtn = ctx.text(ctx.getString(R.string.onboarding_skip), 13f, textSecondary, Fonts.medium).apply {
            gravity = Gravity.CENTER
            val r = this@OnboardingView.dpf(18f)
            background = ripple(
                roundRect(white, r, borderWarm, ctx.dp(1)),
                r,
                Color.parseColor("#182457D6")
            )
            setPadding(ctx.dp(16), ctx.dp(7), ctx.dp(16), ctx.dp(7))
            elevation = this@OnboardingView.dpf(1.5f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            isClickable = true
            isFocusable = true
            textDirection = View.TEXT_DIRECTION_LOCALE
            setOnClickListener { completeAndExit() }
        }
        topBar.addView(skipBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(topBar, lp())

        // ---------------- 2) Central Showcase Stage ----------------
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

        // ---------------- 3) Animated Progress Capsules ----------------
        val dotsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        dots = (0 until pageCount).map { idx ->
            View(ctx).apply {
                val active = idx == 0
                background = buildDotDrawable(active)
                layoutParams = LinearLayout.LayoutParams(if (active) ctx.dp(30) else ctx.dp(8), ctx.dp(8)).apply {
                    marginStart = ctx.dp(4)
                    marginEnd = ctx.dp(4)
                }
                isClickable = true
                setOnClickListener { if (idx != current) goTo(idx) }
            }
        }
        dots.forEach { dotsRow.addView(it) }
        root.addView(dotsRow, lp().margins(t = ctx.dp(4)))

        // ---------------- 4) Primary Royal-Blue CTA Button ----------------
        actionBtn = ctx.text("", 16f, white, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = this@OnboardingView.dpf(18f)
            val baseFill = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(royalBright, royal, royalDeep)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
            }
            val innerSheen = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setStroke(ctx.dp(1), Color.parseColor("#33FFFFFF"))
                setColor(Color.TRANSPARENT)
            }
            val layered = LayerDrawable(arrayOf(baseFill, innerSheen))
            background = ripple(layered, r, Color.parseColor("#36FFFFFF"))
            setPadding(ctx.dp(24), ctx.dp(15), ctx.dp(24), ctx.dp(15))
            elevation = this@OnboardingView.dpf(7f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            isClickable = true
            isFocusable = true
            letterSpacing = 0.02f
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(110).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
                false
            }
            setOnClickListener {
                if (current < pageCount - 1) goTo(current + 1) else completeAndExit()
            }
        }
        root.addView(actionBtn, lp(h = ctx.dp(55)).margins(t = ctx.dp(14)))
        updateActionButton()

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        post { animatePageEntrance(pages[0]) }
    }

    private fun buildDotDrawable(active: Boolean): GradientDrawable =
        if (active) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(royalBright, royal)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(6f)
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(6f)
                setColor(dotIdle)
            }
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val rTop = min(w, h) * 0.72f
            ambientTopPaint.shader = RadialGradient(
                w * 0.5f, h * 0.18f, rTop.coerceAtLeast(1f),
                intArrayOf(Color.parseColor("#142457D6"), Color.parseColor("#002457D6")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            val rBot = min(w, h) * 0.65f
            ambientBottomPaint.shader = RadialGradient(
                w * 0.5f, h * 0.90f, rBot.coerceAtLeast(1f),
                intArrayOf(Color.parseColor("#102457D6"), Color.parseColor("#002457D6")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width > 0 && height > 0) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ambientTopPaint)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ambientBottomPaint)
        }
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

    // ---------------- Smooth Interactive Swipe ----------------
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
                    activePage.alpha = (1f - abs(damped) / (maxShift * 2.3f)).coerceIn(0.68f, 1f)
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
                    val rtl = isRtl()
                    val wantNext = if (rtl) dx > 0f else dx < 0f
                    val target = if (wantNext) current + 1 else current - 1
                    when {
                        target in 0 until pageCount -> goTo(target)
                        wantNext && current == pageCount - 1 -> completeAndExit()
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
            .setDuration(220)
            .setInterpolator(smoothOut)
            .start()
    }

    private fun completeAndExit() {
        if (finishing) return
        finishing = true
        phaseAnimator.cancel()
        OnboardingManager.setCompleted(ctx)
        animate().alpha(0f).scaleX(0.985f).scaleY(0.985f).setDuration(220)
            .setInterpolator(smoothOut)
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
        val travel = this@OnboardingView.dpf(40f)

        from.animate().cancel()
        to.animate().cancel()

        from.animate()
            .alpha(0f)
            .translationX(-sign * travel)
            .setDuration(220)
            .setInterpolator(smoothOut)
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
            .setDuration(300)
            .setInterpolator(smoothOut)
            .start()

        current = target
        animatePageEntrance(to)
        updateDots()
        updateActionButton()
        skipBtn.animate().alpha(if (current == pageCount - 1) 0.72f else 1f).setDuration(200).start()
    }

    private fun updateDots() {
        dots.forEachIndexed { idx, dot ->
            val sel = idx == current
            dot.background = buildDotDrawable(sel)
            val lp = dot.layoutParams as LinearLayout.LayoutParams
            val targetW = if (sel) ctx.dp(30) else ctx.dp(8)
            if (lp.width != targetW) {
                ValueAnimator.ofInt(lp.width, targetW).apply {
                    duration = 250
                    interpolator = smoothOut
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
            card.scaleX = 0.968f
            card.scaleY = 0.968f
            card.animate().scaleX(1f).scaleY(1f).setDuration(340)
                .setInterpolator(smoothOut).start()
        }
        page.findViewWithTag<View>("badge")?.let { b ->
            b.alpha = 0f
            b.translationY = this@OnboardingView.dpf(7f)
            b.animate().alpha(1f).translationY(0f).setStartDelay(45).setDuration(270)
                .setInterpolator(smoothOut).start()
        }
        page.findViewWithTag<View>("title")?.let { t ->
            t.alpha = 0f
            t.translationY = this@OnboardingView.dpf(10f)
            t.animate().alpha(1f).translationY(0f).setStartDelay(85).setDuration(290)
                .setInterpolator(smoothOut).start()
        }
        page.findViewWithTag<View>("desc")?.let { d ->
            d.alpha = 0f
            d.translationY = this@OnboardingView.dpf(9f)
            d.animate().alpha(1f).translationY(0f).setStartDelay(125).setDuration(310)
                .setInterpolator(smoothOut).start()
        }
    }

    // ---------------- Balanced Luxury Page Architecture ----------------
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
                val maxH = ctx.dp(565)
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(white, ivorySoft)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(30f)
                setStroke(ctx.dp(1), borderWarm)
            }
            elevation = this@OnboardingView.dpf(8f)
            outlineAmbientShadowColor = cardShadowTint
            outlineSpotShadowColor = cardShadowTint
            setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
        }
        heroCard.addView(heroCardContent, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        inner.addView(heroCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            marginStart = ctx.dp(2)
            marginEnd = ctx.dp(2)
        })

        // Luxury Eyebrow Pill
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
            setPadding(ctx.dp(14), ctx.dp(5), ctx.dp(14), ctx.dp(5))
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        inner.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = ctx.dp(16)
        })

        // Headline
        val title = ctx.text(ctx.getString(titleRes), 22.5f, textPrimary, Fonts.bold).apply {
            tag = "title"
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setLineSpacing(0f, 1.14f)
        }
        inner.addView(title, lp().margins(t = ctx.dp(10), s = ctx.dp(6), e = ctx.dp(6)))

        // Concise Body Copy
        val desc = ctx.text(ctx.getString(descRes), 14.5f, textSecondary, Fonts.regular).apply {
            tag = "desc"
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LOCALE
            setLineSpacing(0f, 1.40f)
        }
        inner.addView(desc, lp().margins(t = ctx.dp(8), s = ctx.dp(10), e = ctx.dp(10)))

        wrapper.addView(inner, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        return wrapper
    }

    // =========================================================================
    // PAGE 1: Precision Satellite Positioning & Offline Coordinates
    // =========================================================================
    private fun buildPage1(): View {
        val body = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        val canvasView = GpsSatelliteArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(canvasView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Live lock & satellite telemetry pills
        val statusRow = ctx.hbox().apply { gravity = Gravity.CENTER_VERTICAL }

        val livePill = ctx.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(16f)
                setColor(greenPale)
                setStroke(ctx.dp(1), Color.parseColor("#BFE8D3"))
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
            setPadding(ctx.dp(11), ctx.dp(6), ctx.dp(12), ctx.dp(6))
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

        // Neutral geodesic coordinate readout (strictly free of any city/region/country name)
        val coordStrip = ctx.vbox().apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(16f)
                setColor(ivory)
                setStroke(ctx.dp(1), borderWarm)
            }
            setPadding(ctx.dp(14), ctx.dp(9), ctx.dp(14), ctx.dp(9))
        }
        coordStrip.addView(
            ctx.text("35.1240° N  •  28.6410° E", 13.5f, textPrimary, Fonts.bold).apply {
                gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_LTR
                letterSpacing = 0.02f
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

        // Generic saved destination header (strictly neutral — "Saved Destination" / "وجهة محفوظة")
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
        destBar.addView(pinBadge, LinearLayout.LayoutParams(ctx.dp(34), ctx.dp(34)))

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
            ctx.text(ctx.getString(R.string.onboarding_dest_mock), 13.5f, textPrimary, Fonts.bold).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        destBar.addView(destTexts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val bearingBadge = ctx.text("024° NE", 11.5f, white, Fonts.bold).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(royalBright, royal)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@OnboardingView.dpf(11f)
            }
            setPadding(ctx.dp(11), ctx.dp(5), ctx.dp(11), ctx.dp(5))
        }
        destBar.addView(bearingBadge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(destBar, lp())

        // Precision navigation compass + smooth royal-blue arrow artwork
        val navCanvas = SmartNavigationArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(navCanvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = ctx.dp(10)
            bottomMargin = ctx.dp(10)
        })

        // Balanced live trip telemetry tiles
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
    // PAGE 3: Live Royal-Navy Radar & Instant QR Location Portal
    // =========================================================================
    private fun buildPage3(): View {
        val body = ctx.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }

        val radarQrCanvas = RadarPortalArtworkView(ctx).also { animatedCanvases.add(it) }
        body.addView(radarQrCanvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 4 Crisp custom-drawn portal action medallions (Radar, Import, Export, QR)
        val actionsRow = ctx.hbox().apply { gravity = Gravity.CENTER }
        fun toolChip(kind: PortalGlyphView.Kind, labelRes: Int): View = ctx.vbox().apply {
            gravity = Gravity.CENTER
            val circle = FrameLayout(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(royalPale)
                    setStroke(ctx.dp(1), royalStroke)
                }
                addView(PortalGlyphView(ctx, kind, royal), LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
            }
            addView(circle, LinearLayout.LayoutParams(ctx.dp(44), ctx.dp(44)))
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
     * Screen 1 Artwork: Geodesic meridian dome, triple orbital rings, 4 precision GNSS satellites
     * streaming animated telemetry pulses, and central royal-blue emblem.
     */
    private class GpsSatelliteArtworkView(ctx: Context) : View(ctx), PhaseAnimatable {
        private var phase = 0f
        private val logo: Drawable? = ctx.getDrawable(R.drawable.splash_icon)?.mutate()

        private val stageRect = RectF()
        private val arcOval = RectF()
        private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#DCE7FB")
            strokeWidth = dpf(1f)
        }
        private val geodesicPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#142457D6")
            strokeWidth = dpf(1f)
        }
        private val orbitOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#3B2457D6")
            strokeWidth = dpf(1.25f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(6f), dpf(5f)), 0f)
        }
        private val orbitMidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#542457D6")
            strokeWidth = dpf(1.3f)
        }
        private val orbitInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2A2457D6")
            strokeWidth = dpf(1f)
        }
        private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#4D2457D6")
            strokeWidth = dpf(1.2f)
        }
        private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.6f)
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val centerDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val centerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(2.1f)
            strokeCap = Paint.Cap.ROUND
        }
        private val satBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val satPanelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2457D6") }
        private val satPanelDivider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#66FFFFFF")
            strokeWidth = dpf(0.8f)
        }
        private val satStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(1.35f)
        }
        private val satLedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E9E62") }
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
                intArrayOf(Color.parseColor("#EEF4FF"), Color.parseColor("#F7F9FE"), Color.parseColor("#FCFBF7")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val r = min(w, h) * 0.45f
            if (r > 1f) {
                haloPaint.shader = RadialGradient(
                    w / 2f, h / 2f, r,
                    intArrayOf(Color.parseColor("#2E2457D6"), Color.parseColor("#0D2457D6"), Color.parseColor("#002457D6")),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            clipPath.reset()
            clipPath.addRoundRect(stageRect, dpf(22f), dpf(22f), Path.Direction.CW)
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
            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stagePaint)

            // Spherical geodesic meridians & parallels
            val domeR = s * 0.46f
            canvas.drawLine(cx - domeR * 1.2f, cy, cx + domeR * 1.2f, cy, geodesicPaint)
            canvas.drawLine(cx, cy - domeR * 1.2f, cx, cy + domeR * 1.2f, geodesicPaint)
            arcOval.set(cx - domeR * 0.55f, cy - domeR, cx + domeR * 0.55f, cy + domeR)
            canvas.drawOval(arcOval, geodesicPaint)
            arcOval.set(cx - domeR, cy - domeR * 0.55f, cx + domeR, cy + domeR * 0.55f)
            canvas.drawOval(arcOval, geodesicPaint)

            // Ambient royal glow
            canvas.drawCircle(cx, cy, s * 0.44f, haloPaint)

            val outerR = s * 0.405f
            val midR = s * 0.285f
            val innerR = s * 0.195f

            // Smooth expanding harmonic signal waves
            for (i in 0..1) {
                val p = (phase * 2f + i * 0.5f) % 1f
                val pr = innerR * 0.85f + p * (outerR - innerR * 0.75f)
                val alpha = ((1f - p) * (1f - p) * 105f).toInt().coerceIn(0, 255)
                pulsePaint.color = Color.argb(alpha, 36, 87, 214)
                canvas.drawCircle(cx, cy, pr, pulsePaint)
            }

            // Concentric orbital tracks
            canvas.drawCircle(cx, cy, outerR, orbitOuterPaint)
            canvas.drawCircle(cx, cy, midR, orbitMidPaint)
            canvas.drawCircle(cx, cy, innerR, orbitInnerPaint)

            // Animated dash offset for live satellite downlink beams
            beamPaint.pathEffect = DashPathEffect(floatArrayOf(dpf(3.5f), dpf(4.5f)), -phase * dpf(64f))

            // 4 Orbiting GNSS satellites (balanced across quadrants)
            val baseAngles = floatArrayOf(-44f, 42f, 138f, 224f)
            val radii = floatArrayOf(outerR, midR, outerR, midR)
            val speeds = floatArrayOf(1f, -1f, 1f, -1f)
            for (i in 0..3) {
                val deg = baseAngles[i] + speeds[i] * phase * 360f * 0.30f
                val rad = deg * (PI / 180.0)
                val sx = cx + (cos(rad) * radii[i]).toFloat()
                val sy = cy + (sin(rad) * radii[i]).toFloat()
                canvas.drawLine(sx, sy, cx, cy, beamPaint)
                drawSatelliteNode(canvas, sx, sy, deg + 90f, s * 0.052f)
            }

            // Central precision reticle + white disc + royal-blue arrow emblem
            val coreR = (s * 0.148f).coerceAtLeast(dpf(25f))
            val tickIn = coreR + dpf(3.5f)
            val tickOut = coreR + dpf(8.5f)
            canvas.drawLine(cx, cy - tickOut, cx, cy - tickIn, centerRingPaint)
            canvas.drawLine(cx, cy + tickIn, cx, cy + tickOut, centerRingPaint)
            canvas.drawLine(cx - tickOut, cy, cx - tickIn, cy, centerRingPaint)
            canvas.drawLine(cx + tickIn, cy, cx + tickOut, cy, centerRingPaint)

            canvas.drawCircle(cx, cy + dpf(3f), coreR + dpf(1f), coreShadowPaint)
            canvas.drawCircle(cx, cy, coreR, centerDiscPaint)
            canvas.drawCircle(cx, cy, coreR, centerRingPaint)

            logo?.let { d ->
                val half = (coreR * 0.66f).toInt()
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.draw(canvas)
            }

            canvas.restore()
            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stageStroke)
        }

        private fun drawSatelliteNode(canvas: Canvas, x: Float, y: Float, angleDeg: Float, unit: Float) {
            val u = unit.coerceIn(dpf(7.5f), dpf(12.5f))
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(angleDeg)

            val wingW = u * 1.18f
            val wingH = u * 0.62f
            val bodyHalf = u * 0.44f
            val leftWing = RectF(-bodyHalf - wingW - dpf(2f), -wingH / 2f, -bodyHalf - dpf(2f), wingH / 2f)
            val rightWing = RectF(bodyHalf + dpf(2f), -wingH / 2f, bodyHalf + wingW + dpf(2f), wingH / 2f)
            canvas.drawRoundRect(leftWing, dpf(2.5f), dpf(2.5f), satPanelPaint)
            canvas.drawRoundRect(rightWing, dpf(2.5f), dpf(2.5f), satPanelPaint)
            // Subtle solar cell divider lines
            val lxMid = (leftWing.left + leftWing.right) / 2f
            val rxMid = (rightWing.left + rightWing.right) / 2f
            canvas.drawLine(lxMid, leftWing.top + dpf(1f), lxMid, leftWing.bottom - dpf(1f), satPanelDivider)
            canvas.drawLine(rxMid, rightWing.top + dpf(1f), rxMid, rightWing.bottom - dpf(1f), satPanelDivider)

            val body = RectF(-bodyHalf, -bodyHalf, bodyHalf, bodyHalf)
            canvas.drawRoundRect(body, dpf(3f), dpf(3f), satBodyPaint)
            canvas.drawRoundRect(body, dpf(3f), dpf(3f), satStrokePaint)
            canvas.drawCircle(0f, 0f, dpf(2.1f), satLedPaint)

            canvas.restore()
        }
    }

    /**
     * Screen 2 Artwork: Precision instrument bezel (72 graduated ticks), active royal-blue bearing arc,
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
            color = Color.parseColor("#DCE7FB")
            strokeWidth = dpf(1f)
        }
        private val outerFacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CFDEFB")
            strokeWidth = dpf(1.8f)
        }
        private val innerWellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F6F9FF")
        }
        private val innerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#E6EEFD")
            strokeWidth = dpf(5.5f)
        }
        private val activeArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#2457D6")
            strokeWidth = dpf(5.5f)
            strokeCap = Paint.Cap.ROUND
        }
        private val tickMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C5D4F0")
            strokeWidth = dpf(1.05f)
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
            color = Color.parseColor("#8592AC")
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val discShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 36, 87, 214) }
        private val beaconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2457D6") }
        private val beaconHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val guideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#422457D6")
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
                intArrayOf(Color.parseColor("#EEF4FF"), Color.parseColor("#F8FAFE"), Color.parseColor("#FCFBF7")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            val r = min(w, h) * 0.47f
            if (r > 1f) {
                haloPaint.shader = RadialGradient(
                    w / 2f, h / 2f, r,
                    intArrayOf(Color.parseColor("#262457D6"), Color.parseColor("#002457D6")),
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

            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stagePaint)
            canvas.drawCircle(cx, cy, s * 0.46f, haloPaint)

            val dialR = s * 0.42f
            canvas.drawCircle(cx, cy + dpf(3.5f), dialR, discShadowPaint)
            canvas.drawCircle(cx, cy, dialR, outerFacePaint)
            canvas.drawCircle(cx, cy, dialR, outerRingPaint)

            // 72 precision compass graduations (every 5°)
            for (deg in 0 until 360 step 5) {
                val major = deg % 30 == 0
                val len = if (major) s * 0.036f else s * 0.018f
                val p = if (major) tickMajor else tickMinor
                canvas.save()
                canvas.rotate(deg.toFloat(), cx, cy)
                canvas.drawLine(cx, cy - dialR + dpf(5f), cx, cy - dialR + dpf(5f) + len, p)
                canvas.restore()
            }

            // Cardinal letters N / E / S / W
            val textR = dialR - s * 0.082f
            cardinalPaint.textSize = (s * 0.052f).coerceIn(dpf(10f), dpf(14f))
            subCardinalPaint.textSize = cardinalPaint.textSize * 0.90f
            val labels = arrayOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
            for ((lbl, ang) in labels) {
                val rad = (ang - 90f) * (PI / 180.0)
                val lx = cx + (cos(rad) * textR).toFloat()
                val ly = cy + (sin(rad) * textR).toFloat()
                val p = if (lbl == "N") cardinalPaint else subCardinalPaint
                val fm = p.fontMetrics
                canvas.drawText(lbl, lx, ly - (fm.ascent + fm.descent) / 2f, p)
            }

            // Inner instrument well + active bearing track
            val trackR = dialR * 0.72f
            canvas.drawCircle(cx, cy, trackR - dpf(4f), innerWellPaint)
            arcRect.set(cx - trackR, cy - trackR, cx + trackR, cy + trackR)
            canvas.drawOval(arcRect, innerTrackPaint)

            val targetAngle = 24f + 4.2f * sin(phase * 2.0 * PI).toFloat()
            canvas.drawArc(arcRect, -90f, targetAngle, false, activeArcPaint)

            // Target destination beacon on the bearing track
            val targetRad = (targetAngle - 90f) * (PI / 180.0)
            val bx = cx + (cos(targetRad) * trackR).toFloat()
            val by = cy + (sin(targetRad) * trackR).toFloat()

            canvas.drawLine(cx, cy, bx, by, guideLinePaint)

            val pulse = 0.5f + 0.5f * sin(phase * 4.0 * PI).toFloat()
            beaconHaloPaint.color = Color.argb((42 + pulse * 54).toInt(), 36, 87, 214)
            canvas.drawCircle(bx, by, dpf(8.5f) + pulse * dpf(3.5f), beaconHaloPaint)
            canvas.drawCircle(bx, by, dpf(5.2f), outerFacePaint)
            canvas.drawCircle(bx, by, dpf(3.8f), beaconPaint)

            // Large Royal-Blue RMG Navigation Arrow aligned smoothly to targetAngle
            arrowLogo?.let { d ->
                val half = (dialR * 0.45f).toInt()
                canvas.save()
                canvas.rotate(targetAngle, cx, cy)
                d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                d.draw(canvas)
                canvas.restore()
            }

            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stageStroke)
        }
    }

    /**
     * Screen 3 Artwork: Luxury deep royal-navy radar matching the real RadarView aesthetic
     * (royal-blue rim, fine blue rings, smooth cyan scanner beam, crisp small red & selected green blips)
     * paired with a floating white QR location card.
     */
    private class RadarPortalArtworkView(ctx: Context) : View(ctx), PhaseAnimatable {
        private var phase = 0f

        private val stageRect = RectF()
        private val qrCardRect = RectF()
        private val stagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stageStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#DCE7FB")
            strokeWidth = dpf(1f)
        }
        private val radarDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val radarOuterGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(46, 29, 99, 255)
            strokeWidth = dpf(4.5f)
        }
        private val radarBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#1E64F0")
            strokeWidth = dpf(2.4f)
        }
        private val radarInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(150, 92, 174, 255)
            strokeWidth = dpf(0.9f)
        }
        private val radarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(135, 62, 142, 255)
            strokeWidth = dpf(0.95f)
        }
        private val sweepSectorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sweepRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(0.75f)
        }
        private val sweepGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(72, 62, 242, 255)
            strokeWidth = dpf(3.6f)
            strokeCap = Paint.Cap.ROUND
        }
        private val sweepLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#64F8FF")
            strokeWidth = dpf(1.9f)
            strokeCap = Paint.Cap.ROUND
        }
        private val centerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(76, 56, 189, 255)
        }
        private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        private val blipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.1f)
        }
        private val linkDashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#662457D6")
            strokeWidth = dpf(1.35f)
            pathEffect = DashPathEffect(floatArrayOf(dpf(4f), dpf(4f)), 0f)
        }
        private val qrCardShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(38, 20, 48, 120)
        }
        private val qrCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val qrCardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#CBDDFB")
            strokeWidth = dpf(1.3f)
        }
        private val qrInkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2457D6")
            style = Paint.Style.FILL
        }
        private val qrScanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#99386EEB")
            strokeWidth = dpf(1.8f)
            strokeCap = Paint.Cap.ROUND
        }

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
                intArrayOf(Color.parseColor("#EEF4FF"), Color.parseColor("#F8FAFE"), Color.parseColor("#FCFBF7")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val s = min(w, h)
            if (s <= dpf(8f)) return
            val rtl = layoutDirection == LAYOUT_DIRECTION_RTL

            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stagePaint)

            val radarR = (s * 0.375f).coerceAtMost(w * 0.325f)
            val shiftX = if (rtl) radarR * 0.24f else -radarR * 0.24f
            val rcx = w / 2f + shiftX
            val rcy = h / 2f - s * 0.015f

            // Deep navy radial disc matching the real RadarView
            radarDiscPaint.shader = RadialGradient(
                rcx, rcy, radarR,
                intArrayOf(
                    Color.parseColor("#032566"),
                    Color.parseColor("#011A54"),
                    Color.parseColor("#010E33")
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )

            canvas.drawCircle(rcx, rcy, radarR + dpf(1.5f), radarOuterGlowPaint)
            canvas.drawCircle(rcx, rcy, radarR, radarDiscPaint)

            // Fine blue rings & crosshairs
            canvas.drawCircle(rcx, rcy, radarR * 0.35f, radarRingPaint)
            canvas.drawCircle(rcx, rcy, radarR * 0.68f, radarRingPaint)
            val axisEnd = radarR - dpf(5f)
            canvas.drawLine(rcx - axisEnd, rcy, rcx + axisEnd, rcy, radarRingPaint)
            canvas.drawLine(rcx, rcy - axisEnd, rcx, rcy + axisEnd, radarRingPaint)

            // Smooth cyan sweep sector + subtle radial rays + crisp leading beam
            val sweepDeg = (phase * 360f * 2f) % 360f
            sweepSectorPaint.shader = SweepGradient(
                rcx, rcy,
                intArrayOf(
                    Color.argb(0, 56, 218, 255),
                    Color.argb(0, 56, 218, 255),
                    Color.argb(26, 56, 218, 255),
                    Color.argb(66, 78, 246, 255)
                ),
                floatArrayOf(0f, 0.84f, 0.93f, 1f)
            )
            canvas.save()
            canvas.rotate(sweepDeg, rcx, rcy)
            canvas.drawCircle(rcx, rcy, radarR - dpf(1.5f), sweepSectorPaint)
            for (i in 1..6) {
                val rayDeg = -i * 7.5
                val rad = rayDeg * (PI / 180.0)
                sweepRayPaint.color = Color.argb((36 - i * 5).coerceAtLeast(6), 92, 232, 255)
                canvas.drawLine(
                    rcx, rcy,
                    rcx + (cos(rad) * (radarR - dpf(3f))).toFloat(),
                    rcy + (sin(rad) * (radarR - dpf(3f))).toFloat(),
                    sweepRayPaint
                )
            }
            canvas.drawLine(rcx, rcy, rcx + radarR - dpf(2.5f), rcy, sweepGlowPaint)
            canvas.drawLine(rcx, rcy, rcx + radarR - dpf(2.5f), rcy, sweepLinePaint)
            canvas.restore()
            sweepSectorPaint.shader = null

            // Royal-blue rim
            canvas.drawCircle(rcx, rcy, radarR, radarBorderPaint)
            canvas.drawCircle(rcx, rcy, radarR - dpf(1.4f), radarInnerRimPaint)

            // Crisp small markers: 2 normal red + 1 selected green (linked to QR card)
            val selAngle = if (rtl) 145f else 38f
            val blipAngles = floatArrayOf(-44f, 152f, selAngle)
            val blipRadii = floatArrayOf(0.55f, 0.44f, 0.68f)
            var selX = rcx
            var selY = rcy
            for (i in 0..2) {
                val isSelected = i == 2
                val rad = blipAngles[i] * (PI / 180.0)
                val tx = rcx + (cos(rad) * radarR * blipRadii[i]).toFloat()
                val ty = rcy + (sin(rad) * radarR * blipRadii[i]).toFloat()
                if (isSelected) { selX = tx; selY = ty }

                if (isSelected) {
                    blipPaint.color = Color.argb(42, 34, 197, 94)
                    canvas.drawCircle(tx, ty, dpf(5.2f), blipPaint)
                    blipStroke.color = Color.argb(200, 34, 197, 94)
                    canvas.drawCircle(tx, ty, dpf(5.2f), blipStroke)
                    blipPaint.color = Color.parseColor("#22C55E")
                    canvas.drawCircle(tx, ty, dpf(2.9f), blipPaint)
                } else {
                    blipPaint.color = Color.argb(44, 244, 63, 63)
                    canvas.drawCircle(tx, ty, dpf(4.2f), blipPaint)
                    blipPaint.color = Color.parseColor("#F43F3F")
                    canvas.drawCircle(tx, ty, dpf(2.6f), blipPaint)
                }
                blipPaint.color = Color.WHITE
                canvas.drawCircle(tx, ty, dpf(0.95f), blipPaint)
            }

            // Center user marker (soft blue halo + crisp white core)
            canvas.drawCircle(rcx, rcy, dpf(7.5f), centerHaloPaint)
            canvas.drawCircle(rcx, rcy, dpf(3.4f), centerDotPaint)

            // Floating QR Badge Card linked cleanly to the selected green marker
            val qrSize = (s * 0.37f).coerceIn(dpf(68f), dpf(96f))
            val qx = if (rtl) (rcx - radarR - qrSize * 0.42f).coerceAtLeast(dpf(14f))
            else (rcx + radarR - qrSize * 0.58f).coerceAtMost(w - qrSize - dpf(14f))
            val qy = (rcy + radarR * 0.16f).coerceAtMost(h - qrSize - dpf(12f))

            canvas.drawLine(selX, selY, qx + qrSize / 2f, qy + qrSize / 2f, linkDashPaint)

            qrCardRect.set(qx, qy + dpf(3.5f), qx + qrSize, qy + qrSize + dpf(3.5f))
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardShadow)
            qrCardRect.set(qx, qy, qx + qrSize, qy + qrSize)
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardFill)
            canvas.drawRoundRect(qrCardRect, dpf(16f), dpf(16f), qrCardStroke)

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

            val scanT = 0.5f + 0.5f * sin(phase * 2.0 * PI * 2.0).toFloat()
            val scanY = qy + pad + scanT * innerSize
            canvas.drawLine(qx + pad * 0.7f, scanY, qx + qrSize - pad * 0.7f, scanY, qrScanLinePaint)

            canvas.drawRoundRect(stageRect, dpf(22f), dpf(22f), stageStroke)
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
                    val trayTop = cy + r * 0.25f
                    val trayBottom = cy + r * 0.85f
                    canvas.drawLine(cx - r * 0.85f, trayTop, cx - r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx - r * 0.85f, trayBottom, cx + r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx + r * 0.85f, trayBottom, cx + r * 0.85f, trayTop, stroke)
                    val aTop = cy - r * 0.85f
                    val aBot = cy + r * 0.35f
                    canvas.drawLine(cx, aTop, cx, aBot, stroke)
                    canvas.drawLine(cx - r * 0.42f, aBot - r * 0.40f, cx, aBot, stroke)
                    canvas.drawLine(cx + r * 0.42f, aBot - r * 0.40f, cx, aBot, stroke)
                }
                Kind.EXPORT -> {
                    val trayTop = cy + r * 0.25f
                    val trayBottom = cy + r * 0.85f
                    canvas.drawLine(cx - r * 0.85f, trayTop, cx - r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx - r * 0.85f, trayBottom, cx + r * 0.85f, trayBottom, stroke)
                    canvas.drawLine(cx + r * 0.85f, trayBottom, cx + r * 0.85f, trayTop, stroke)
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
