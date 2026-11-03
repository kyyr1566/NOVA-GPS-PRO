package com.nova.gpspro.ptt

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Full-screen Walkie-Talkie view built strictly with the existing Android Views & [C] palette system.
 * Observes [PttRepository.state] via [kotlinx.coroutines.flow.StateFlow] so language and light/dark
 * theme switches rebuild the UI seamlessly while preserving the live session.
 */
class PttPage(
    private val act: MainActivity,
    private val onBackToPortal: () -> Unit
) : FrameLayout(act) {

    private val repo: PttRepository get() = act.app.ptt
    private var uiScope: CoroutineScope? = null
    private var collectJob: Job? = null

    private var latestState: PttUiState = repo.state.value

    // Header
    private val headerSubtitle: TextView

    // Alert / Banner region
    private val endedBannerBox: LinearLayout
    private val errorBannerBox: LinearLayout
    private val errorBannerText: TextView
    private val errorActionsRow: LinearLayout

    // Setup / Lobby stage (when DISCONNECTED, SCANNING, CONNECTING, WAITING_APPROVAL)
    private val lobbyScroll: ScrollView
    private val nameInput: EditText
    private val scanSection: LinearLayout
    private val scanStatusLabel: TextView
    private val discoveredListContainer: LinearLayout
    private val connectingCard: LinearLayout
    private val connectingStatusText: TextView

    // Active Walkie-Talkie stage (when IN_SESSION)
    private val sessionScroll: ScrollView
    private val channelPillText: TextView
    private val codecPillText: TextView
    private val sessionSubStatusText: TextView
    private val hotspotInfoBanner: TextView
    private val audioMutedBanner: TextView
    private val approvalsContainer: LinearLayout
    private val pttCircleButton: PttTalkCircleView
    private val talkSubHintText: TextView
    private val connectedCounterText: TextView
    private val participantsContainer: LinearLayout
    private val muteAllBtn: TextView
    private val endSessionBtn: TextView

    init {
        setBackgroundColor(C.BG)
        isClickable = true
        isFocusable = true
        layoutDirection = if (act.isRtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val root = act.vbox().apply {
            setPadding(act.dp(18), act.dp(14), act.dp(18), act.dp(14))
        }

        // ==================== 1. Top Header ====================
        val header = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val backSymbol = if (act.isRtl()) "→" else "←"
        val backBtn = act.text(backSymbol, 19f, C.TEXT, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(14).toFloat()
            background = ripple(roundRect(C.CARD, r, C.BORDER, act.dp(1)), r)
            contentDescription = act.getString(R.string.ptt_back)
            setOnClickListener { onBackToPortal() }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(act.dp(42), act.dp(42)))

        val titleCol = act.vbox().apply {
            setPadding(act.dp(12), 0, act.dp(10), 0)
        }
        titleCol.addView(
            act.text("📡 ${act.getString(R.string.ptt_portal_title)}", 20f, C.TEXT, Fonts.bold).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        )
        headerSubtitle = act.text(act.getString(R.string.ptt_header_subtitle), 10.5f, C.GOLD, Fonts.medium).apply {
            letterSpacing = 0.14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleCol.addView(headerSubtitle, lp().margins(t = act.dp(1)))
        header.addView(titleCol, lp(0, weight = 1f))

        val settingsBtn = act.text("⚙", 18f, C.GOLD_DEEP, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(14).toFloat()
            background = ripple(roundRect(C.GOLD_PALE, r, C.GOLD_LIGHT, act.dp(1)), r)
            contentDescription = act.getString(R.string.ptt_settings_title)
            setOnClickListener { showWalkieSettingsDialog() }
        }
        header.addView(settingsBtn, LinearLayout.LayoutParams(act.dp(42), act.dp(42)))
        root.addView(header, lp().margins(b = act.dp(10)))

        // ==================== 2. Session Ended & Error Banners ====================
        endedBannerBox = act.vbox().apply {
            visibility = View.GONE
            background = roundRect(C.GOLD_PALE, act.dp(16).toFloat(), C.GOLD_LIGHT, act.dp(1))
            setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))
            addView(
                act.text(act.getString(R.string.ptt_connection_ended_banner), 13.5f, C.GOLD_DEEP, Fonts.medium).apply {
                    gravity = Gravity.CENTER
                }
            )
        }
        root.addView(endedBannerBox, lp().margins(b = act.dp(8)))

        errorBannerBox = act.vbox().apply {
            visibility = View.GONE
            background = roundRect(C.RED_LIGHT, act.dp(16).toFloat(), C.RED, act.dp(1))
            setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))
        }
        errorBannerText = act.text("", 13.5f, C.RED, Fonts.medium)
        errorActionsRow = act.hbox().apply {
            visibility = View.GONE
        }
        errorBannerBox.addView(errorBannerText, lp())
        errorBannerBox.addView(errorActionsRow, lp().margins(t = act.dp(8)))
        root.addView(errorBannerBox, lp().margins(b = act.dp(8)))

        // ==================== 3. Lobby Stage (Setup / Scan / Connecting) ====================
        val lobbyCol = act.vbox().apply { setPadding(0, act.dp(4), 0, act.dp(20)) }

        // 3a. User Name Card
        val nameCard = act.vbox().apply {
            background = act.card(22)
            softElevation(3f)
            setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(16))
        }
        nameCard.addView(act.text(act.getString(R.string.ptt_your_name_label), 12.5f, C.GOLD_DEEP, Fonts.bold))
        val nameRow = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        nameInput = act.input(act.getString(R.string.ptt_your_name_hint)).apply {
            val defaultShort = PttSecurity.shortDeviceFingerprint(repo.settings.deviceId).take(4)
            val initial = repo.settings.displayName.ifBlank {
                act.getString(R.string.ptt_default_user_fmt, defaultShort).also { repo.saveDisplayName(it) }
            }
            setText(initial)
            filters = arrayOf(InputFilter.LengthFilter(28))
        }
        val saveNameBtn = uniformActionButton(
            label = act.getString(R.string.ptt_save_name),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 46
        ) {
            val entered = nameInput.text?.toString()?.trim().orEmpty()
            if (!repo.saveDisplayName(entered)) {
                act.message.error(act.getString(R.string.ptt_err_name_empty))
            } else {
                act.message.success(act.getString(R.string.msg_saved))
            }
        }
        nameRow.addView(nameInput, lp(0, weight = 1f).margins(e = act.dp(8)))
        nameRow.addView(saveNameBtn, LinearLayout.LayoutParams(act.dp(92), act.dp(46)))
        nameCard.addView(nameRow, lp().margins(t = act.dp(8)))
        lobbyCol.addView(nameCard, lp().margins(b = act.dp(14)))

        // 3b. Symmetric Primary Action Buttons: Create Session | Find Session
        val primaryActionsRow = act.hbox()
        val createSessionBtn = uniformActionButton(
            label = act.getString(R.string.ptt_create_session),
            filled = true,
            accentColor = C.GOLD,
            heightDp = 52
        ) {
            if (commitTypedNameOrWarn()) {
                ensureWalkiePermissionsThen { showCreateSessionDialog(useHotspotFallback = false) }
            }
        }
        val findSessionBtn = uniformActionButton(
            label = act.getString(R.string.ptt_find_session),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 52
        ) {
            if (commitTypedNameOrWarn()) {
                ensureWalkiePermissionsThen { repo.startScanningSessions() }
            }
        }
        primaryActionsRow.addView(createSessionBtn, LinearLayout.LayoutParams(0, act.dp(52), 1f).margins(e = act.dp(7)))
        primaryActionsRow.addView(findSessionBtn, LinearLayout.LayoutParams(0, act.dp(52), 1f).margins(s = act.dp(7)))
        lobbyCol.addView(primaryActionsRow, lp().margins(b = act.dp(14)))

        // 3c. Connecting / Waiting Host Approval Card
        connectingCard = act.vbox().apply {
            visibility = View.GONE
            background = act.card(22)
            softElevation(2f)
            setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(18))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        connectingStatusText = act.text(act.getString(R.string.ptt_connecting_status), 15f, C.TEXT, Fonts.medium).apply {
            gravity = Gravity.CENTER
        }
        val cancelConnectBtn = uniformActionButton(
            label = act.getString(R.string.cancel),
            filled = false,
            accentColor = C.RED,
            heightDp = 44
        ) {
            repo.endSessionByUser()
        }
        connectingCard.addView(connectingStatusText, lp())
        connectingCard.addView(cancelConnectBtn, LinearLayout.LayoutParams(act.dp(160), act.dp(44)).margins(t = act.dp(14)))
        lobbyCol.addView(connectingCard, lp().margins(b = act.dp(14)))

        // 3d. Discovered Nearby Sessions Section
        scanSection = act.vbox().apply {
            visibility = View.GONE
            background = act.card(22)
            softElevation(2f)
            setPadding(act.dp(16), act.dp(14), act.dp(16), act.dp(16))
        }
        val scanHeaderRow = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        scanStatusLabel = act.text(act.getString(R.string.ptt_scanning_sessions), 13.5f, C.GOLD_DEEP, Fonts.bold)
        scanHeaderRow.addView(scanStatusLabel, lp(0, weight = 1f))

        val stopOrRescanRow = act.hbox()
        val refreshScanBtn = uniformActionButton(
            label = act.getString(R.string.ptt_rescan),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 38
        ) {
            ensureWalkiePermissionsThen { repo.startScanningSessions() }
        }
        val stopScanBtn = uniformActionButton(
            label = act.getString(R.string.ptt_stop_scan),
            filled = false,
            accentColor = C.TEXT2,
            heightDp = 38
        ) {
            repo.stopScanningSessions()
        }
        stopOrRescanRow.addView(refreshScanBtn, LinearLayout.LayoutParams(act.dp(88), act.dp(38)).margins(e = act.dp(6)))
        stopOrRescanRow.addView(stopScanBtn, LinearLayout.LayoutParams(act.dp(96), act.dp(38)))
        scanHeaderRow.addView(stopOrRescanRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scanSection.addView(scanHeaderRow, lp().margins(b = act.dp(10)))

        discoveredListContainer = act.vbox()
        scanSection.addView(discoveredListContainer, lp())

        // Optional Local Hotspot Gateway Join helper inside scan card (clearly separated from Wi-Fi Direct)
        val hotspotJoinBox = act.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C.CARD_SOFT, act.dp(14).toFloat(), C.BORDER, act.dp(1))
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
        }
        val hsTextCol = act.vbox()
        hsTextCol.addView(act.text(act.getString(R.string.ptt_join_hotspot_ip_label), 13f, C.TEXT, Fonts.medium))
        hsTextCol.addView(act.text(act.getString(R.string.ptt_join_hotspot_ip_desc), 11.5f, C.TEXT2), lp().margins(t = act.dp(2)))
        hotspotJoinBox.addView(hsTextCol, lp(0, weight = 1f).margins(e = act.dp(8)))
        val hsJoinBtn = uniformActionButton(
            label = act.getString(R.string.ptt_join_hotspot_btn),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 40,
            textSp = 12.5f
        ) {
            if (commitTypedNameOrWarn()) {
                ensureWalkiePermissionsThen { showJoinLocalHotspotDialog() }
            }
        }
        hotspotJoinBox.addView(hsJoinBtn, LinearLayout.LayoutParams(act.dp(124), act.dp(40)))
        scanSection.addView(hotspotJoinBox, lp().margins(t = act.dp(10)))

        lobbyCol.addView(scanSection, lp())

        lobbyScroll = ScrollView(act).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(lobbyCol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        // ==================== 4. Active Session Stage (Push-to-Talk Radio) ====================
        val sessionCol = act.vbox().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, act.dp(2), 0, act.dp(18))
        }

        // 4a. Channel & Negotiated Codec Badges
        val metaRow = act.hbox().apply { gravity = Gravity.CENTER }
        channelPillText = act.text("", 12.5f, C.GOLD_DEEP, Fonts.bold).apply {
            background = roundRect(C.GOLD_PALE, act.dp(12).toFloat(), C.GOLD_LIGHT, act.dp(1))
            setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
        }
        codecPillText = act.text("", 12f, C.TEXT2, Fonts.medium).apply {
            background = roundRect(C.CARD, act.dp(12).toFloat(), C.BORDER, act.dp(1))
            setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
        }
        metaRow.addView(channelPillText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(e = act.dp(8)))
        metaRow.addView(codecPillText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sessionCol.addView(metaRow, lp().margins(b = act.dp(8)))

        sessionSubStatusText = act.text("", 13.5f, C.GREEN_DEEP, Fonts.medium).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        sessionCol.addView(sessionSubStatusText, lp().margins(b = act.dp(6)))

        hotspotInfoBanner = act.text("", 12.5f, C.GOLD_DEEP, Fonts.medium).apply {
            gravity = Gravity.CENTER
            background = roundRect(C.GOLD_PALE, act.dp(14).toFloat(), C.GOLD_LIGHT, act.dp(1))
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            visibility = View.GONE
        }
        sessionCol.addView(hotspotInfoBanner, lp().margins(b = act.dp(8)))

        audioMutedBanner = act.text(act.getString(R.string.ptt_audio_muted_banner), 12.5f, C.AMBER, Fonts.bold).apply {
            gravity = Gravity.CENTER
            background = roundRect(C.CARD, act.dp(12).toFloat(), C.AMBER, act.dp(1))
            setPadding(act.dp(12), act.dp(7), act.dp(12), act.dp(7))
            visibility = View.GONE
        }
        sessionCol.addView(audioMutedBanner, lp().margins(b = act.dp(8)))

        // 4b. Host Incoming Join Requests (Equal-sized Accept & Reject buttons)
        approvalsContainer = act.vbox()
        sessionCol.addView(approvalsContainer, lp())

        // 4c. Giant Central Circular Push-to-Talk Button (#2457D6 / #14866D / #D99A32)
        pttCircleButton = PttTalkCircleView(act).apply {
            setOnTouchListener { v, event ->
                handlePttCircleTouch(v, event)
            }
        }
        sessionCol.addView(
            pttCircleButton,
            LinearLayout.LayoutParams(act.dp(224), act.dp(224)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = act.dp(6)
                bottomMargin = act.dp(4)
            }
        )

        talkSubHintText = act.text("", 12.5f, C.TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
        }
        sessionCol.addView(talkSubHintText, lp().margins(b = act.dp(12)))

        // 4d. Symmetric Session Controls Row: Mute Audio | End Session
        val sessionControlsRow = act.hbox()
        muteAllBtn = uniformActionButton(
            label = act.getString(R.string.ptt_mute_all_audio),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 46
        ) {
            repo.setLocalAudioMuted(!latestState.localAudioMuted)
        }
        endSessionBtn = uniformActionButton(
            label = act.getString(R.string.ptt_end_session),
            filled = false,
            accentColor = C.RED,
            heightDp = 46
        ) {
            repo.endSessionByUser()
        }
        sessionControlsRow.addView(muteAllBtn, LinearLayout.LayoutParams(0, act.dp(46), 1f).margins(e = act.dp(7)))
        sessionControlsRow.addView(endSessionBtn, LinearLayout.LayoutParams(0, act.dp(46), 1f).margins(s = act.dp(7)))
        sessionCol.addView(sessionControlsRow, lp().margins(b = act.dp(14)))

        // 4e. Connected Participants Card ("المتصلون: 3" — zero GPS/distance/location)
        val rosterCard = act.vbox().apply {
            background = act.card(22)
            softElevation(2f)
            setPadding(act.dp(15), act.dp(13), act.dp(15), act.dp(13))
        }
        connectedCounterText = act.text(act.getString(R.string.ptt_connected_count_fmt, 0), 14.5f, C.GOLD_DEEP, Fonts.bold)
        rosterCard.addView(connectedCounterText, lp().margins(b = act.dp(10)))

        participantsContainer = act.vbox()
        rosterCard.addView(participantsContainer, lp())
        sessionCol.addView(rosterCard, lp())

        sessionScroll = ScrollView(act).apply {
            visibility = View.GONE
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(sessionCol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        // Stage container
        val stage = FrameLayout(act).apply {
            addView(lobbyScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(sessionScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        root.addView(stage, lp(h = 0, weight = 1f))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        collectJob = scope.launch {
            repo.state.collect { state ->
                latestState = state
                bindState(state)
            }
        }
    }

    override fun onDetachedFromWindow() {
        // Ensure microphone never remains open if view detaches mid-press in Hold-to-Talk mode
        repo.onTalkTouchUpOrCancel()
        pttCircleButton.stopPulseAnimation()
        collectJob?.cancel()
        collectJob = null
        uiScope?.cancel()
        uiScope = null
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && latestState.talkMode == TalkMode.HOLD_TO_TALK) {
            repo.onTalkTouchUpOrCancel()
        }
    }

    private fun handlePttCircleTouch(view: View, event: MotionEvent): Boolean {
        if (latestState.phase != PttPhase.IN_SESSION) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (latestState.buttonState == PttButtonVisualState.REMOTE_SPEAKING_AMBER) {
                    view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    return true
                }
                view.parent?.requestDisallowInterceptTouchEvent(true)
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                repo.onTalkTouchDown()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.parent?.requestDisallowInterceptTouchEvent(false)
                repo.onTalkTouchUpOrCancel()
                return true
            }
        }
        return false
    }

    // ==================== Binding UI State ====================

    private fun bindState(state: PttUiState) {
        // 1. Ended banner & diagnostics error banner
        endedBannerBox.visibility = if (state.showSessionEndedBanner && state.phase == PttPhase.DISCONNECTED) {
            View.VISIBLE
        } else {
            View.GONE
        }
        bindErrorBanner(state.errorCode)

        // 2. Stage switching
        val inSession = state.phase == PttPhase.IN_SESSION
        lobbyScroll.visibility = if (inSession) View.GONE else View.VISIBLE
        sessionScroll.visibility = if (inSession) View.VISIBLE else View.GONE

        if (!inSession) {
            pttCircleButton.stopPulseAnimation()
            headerSubtitle.text = when (state.phase) {
                PttPhase.CHECKING -> act.getString(R.string.ptt_checking_status)
                PttPhase.HOSTING -> act.getString(R.string.ptt_hosting_starting_status)
                PttPhase.SCANNING -> act.getString(R.string.ptt_discovery_searching)
                PttPhase.CONNECTING -> act.getString(R.string.ptt_connecting_status)
                PttPhase.WAITING_APPROVAL -> act.getString(R.string.ptt_waiting_host_approval)
                else -> act.getString(R.string.ptt_status_disconnected)
            }
            headerSubtitle.setTextColor(
                if (state.phase == PttPhase.HOSTING || state.phase == PttPhase.SCANNING || state.phase == PttPhase.CONNECTING || state.phase == PttPhase.WAITING_APPROVAL)
                    C.GOLD
                else C.TEXT2
            )

            connectingCard.visibility = if (
                state.phase == PttPhase.CHECKING ||
                state.phase == PttPhase.HOSTING ||
                state.phase == PttPhase.CONNECTING ||
                state.phase == PttPhase.WAITING_APPROVAL
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            connectingStatusText.text = when (state.phase) {
                PttPhase.CHECKING -> act.getString(R.string.ptt_checking_status)
                PttPhase.HOSTING -> act.getString(R.string.ptt_hosting_starting_status)
                PttPhase.WAITING_APPROVAL -> act.getString(R.string.ptt_waiting_host_approval)
                else -> act.getString(R.string.ptt_connecting_status)
            }

            scanSection.visibility = if (
                state.phase == PttPhase.SCANNING ||
                state.discoveredSessions.isNotEmpty() ||
                state.discoveryState == PttDiscoveryState.FAILED ||
                state.discoveryState == PttDiscoveryState.FOUND
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
            when {
                state.phase == PttPhase.SCANNING && state.discoveredSessions.isEmpty() -> {
                    scanStatusLabel.text = act.getString(R.string.ptt_discovery_searching)
                    scanStatusLabel.setTextColor(C.GOLD_DEEP)
                }
                state.discoveredSessions.isNotEmpty() || state.discoveryState == PttDiscoveryState.FOUND -> {
                    scanStatusLabel.text = act.getString(R.string.ptt_discovery_found)
                    scanStatusLabel.setTextColor(C.GREEN)
                }
                state.discoveryState == PttDiscoveryState.FAILED -> {
                    scanStatusLabel.text = act.getString(R.string.ptt_discovery_failed)
                    scanStatusLabel.setTextColor(C.RED)
                }
                else -> {
                    scanStatusLabel.text = act.getString(R.string.ptt_find_session)
                    scanStatusLabel.setTextColor(C.GOLD_DEEP)
                }
            }
            renderDiscoveredSessions(state.discoveredSessions)
            return
        }

        // 3. Active Session UI
        headerSubtitle.text = act.getString(R.string.ptt_connected_count_fmt, state.connectedCount)
        headerSubtitle.setTextColor(C.GREEN)

        channelPillText.text = act.getString(R.string.ptt_session_active_channel_fmt, state.channelCode)
        val codecLabel = when (state.negotiatedCodec) {
            AudioCodecType.OPUS_16K -> act.getString(R.string.ptt_codec_opus)
            AudioCodecType.ADPCM_16K -> act.getString(R.string.ptt_codec_adpcm)
        }
        codecPillText.text = act.getString(R.string.ptt_codec_badge_fmt, codecLabel)

        if (state.isHost && state.connectedCount <= 1) {
            sessionSubStatusText.text = act.getString(R.string.ptt_session_ready_waiting)
            sessionSubStatusText.visibility = View.VISIBLE
        } else {
            sessionSubStatusText.visibility = View.GONE
        }

        if (state.hotspotInfo != null) {
            hotspotInfoBanner.text = act.getString(
                R.string.ptt_hotspot_active_fmt,
                state.hotspotInfo.ssid,
                state.hotspotInfo.passphrase
            )
            hotspotInfoBanner.visibility = View.VISIBLE
        } else {
            hotspotInfoBanner.visibility = View.GONE
        }

        audioMutedBanner.visibility = if (state.localAudioMuted) View.VISIBLE else View.GONE
        paintUniformButton(
            btn = muteAllBtn,
            label = act.getString(if (state.localAudioMuted) R.string.ptt_unmute_all_audio else R.string.ptt_mute_all_audio),
            filled = state.localAudioMuted,
            accentColor = if (state.localAudioMuted) C.AMBER else C.GOLD_DEEP
        )

        renderPendingApprovals(state.pendingApprovals)

        // Central PTT Circle Label & Visual Color (#2457D6 / #14866D / #D99A32)
        val mainCircleLabel = when (state.buttonState) {
            PttButtonVisualState.IDLE_BLUE -> {
                if (state.talkMode == TalkMode.TAP_TO_TOGGLE) act.getString(R.string.ptt_btn_tap_to_talk)
                else act.getString(R.string.ptt_btn_hold_to_talk)
            }
            PttButtonVisualState.SELF_SPEAKING_GREEN -> act.getString(R.string.ptt_btn_you_speaking)
            PttButtonVisualState.REMOTE_SPEAKING_AMBER -> {
                val speaker = state.activeSpeakerName?.ifBlank { "?" } ?: "?"
                act.getString(R.string.ptt_btn_other_speaking_fmt, speaker)
            }
        }
        pttCircleButton.updateState(state.buttonState, mainCircleLabel)

        talkSubHintText.text = when (state.buttonState) {
            PttButtonVisualState.IDLE_BLUE -> {
                if (state.talkMode == TalkMode.TAP_TO_TOGGLE) act.getString(R.string.ptt_mode_toggle)
                else act.getString(R.string.ptt_mode_hold)
            }
            PttButtonVisualState.SELF_SPEAKING_GREEN -> {
                if (state.talkMode == TalkMode.TAP_TO_TOGGLE) act.getString(R.string.ptt_btn_tap_to_stop)
                else act.getString(R.string.ptt_btn_you_speaking)
            }
            PttButtonVisualState.REMOTE_SPEAKING_AMBER -> {
                val speaker = state.activeSpeakerName?.ifBlank { "?" } ?: "?"
                act.getString(R.string.ptt_channel_busy_hint, speaker)
            }
        }

        connectedCounterText.text = act.getString(R.string.ptt_connected_count_fmt, state.connectedCount)
        renderParticipantsList(state.participants)
    }

    private fun bindErrorBanner(errorCode: PttErrorCode) {
        if (errorCode == PttErrorCode.NONE) {
            errorBannerBox.visibility = View.GONE
            return
        }
        errorBannerBox.visibility = View.VISIBLE
        errorActionsRow.removeAllViews()
        errorActionsRow.visibility = View.GONE

        errorBannerText.text = when (errorCode) {
            PttErrorCode.WIFI_DISABLED -> act.getString(R.string.ptt_err_wifi_disabled)
            PttErrorCode.LOCATION_SERVICE_OFF -> act.getString(R.string.ptt_err_location_service_off)
            PttErrorCode.PERMISSION_MISSING -> act.getString(R.string.ptt_perm_denied_msg)
            PttErrorCode.P2P_UNSUPPORTED -> act.getString(R.string.ptt_err_p2p_unsupported)
            PttErrorCode.HOSTING_FAILED -> act.getString(R.string.ptt_err_hosting_failed)
            PttErrorCode.DISCOVERY_FAILED -> act.getString(R.string.ptt_err_discovery_failed)
            PttErrorCode.HOTSPOT_START_FAILED -> act.getString(R.string.ptt_err_hotspot_start_failed)
            PttErrorCode.HOTSPOT_NOT_CONNECTED -> act.getString(R.string.ptt_err_hotspot_not_connected)
            PttErrorCode.PROTOCOL_INCOMPATIBLE -> act.getString(R.string.ptt_err_protocol_incompatible)
            PttErrorCode.AUTH_FAILED -> act.getString(R.string.ptt_err_auth_failed)
            PttErrorCode.REJECTED_BY_HOST -> act.getString(R.string.ptt_err_rejected_by_host)
            PttErrorCode.CONNECT_TIMEOUT -> act.getString(R.string.ptt_err_connect_timeout)
            PttErrorCode.CONNECTION_REFUSED -> act.getString(R.string.ptt_err_connection_refused)
            PttErrorCode.NONE -> ""
        }

        when (errorCode) {
            PttErrorCode.WIFI_DISABLED, PttErrorCode.HOTSPOT_NOT_CONNECTED -> {
                errorActionsRow.visibility = View.VISIBLE
                val wifiBtn = uniformActionButton(
                    label = act.getString(R.string.ptt_open_wifi_settings),
                    filled = true,
                    accentColor = C.GOLD,
                    heightDp = 40
                ) {
                    try { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Throwable) {}
                }
                errorActionsRow.addView(wifiBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(40)))
            }

            PttErrorCode.P2P_UNSUPPORTED, PttErrorCode.HOSTING_FAILED -> {
                errorActionsRow.visibility = View.VISIBLE
                val wifiBtn = uniformActionButton(
                    label = act.getString(R.string.ptt_open_wifi_settings),
                    filled = false,
                    accentColor = C.GOLD_DEEP,
                    heightDp = 38
                ) {
                    try { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Throwable) {}
                }
                val hsBtn = uniformActionButton(
                    label = act.getString(R.string.ptt_use_local_hotspot),
                    filled = true,
                    accentColor = C.GOLD,
                    heightDp = 38
                ) {
                    if (commitTypedNameOrWarn()) {
                        ensureWalkiePermissionsThen { showCreateSessionDialog(useHotspotFallback = true) }
                    }
                }
                errorActionsRow.addView(wifiBtn, LinearLayout.LayoutParams(0, act.dp(38), 1f).margins(e = act.dp(6)))
                errorActionsRow.addView(hsBtn, LinearLayout.LayoutParams(0, act.dp(38), 1f).margins(s = act.dp(6)))
            }

            PttErrorCode.DISCOVERY_FAILED -> {
                errorActionsRow.visibility = View.VISIBLE
                val retryBtn = uniformActionButton(
                    label = act.getString(R.string.ptt_rescan),
                    filled = true,
                    accentColor = C.GOLD,
                    heightDp = 40
                ) {
                    if (commitTypedNameOrWarn()) {
                        ensureWalkiePermissionsThen { repo.startScanningSessions() }
                    }
                }
                errorActionsRow.addView(retryBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(40)))
            }

            PttErrorCode.LOCATION_SERVICE_OFF -> {
                errorActionsRow.visibility = View.VISIBLE
                val locBtn = uniformActionButton(
                    label = act.getString(R.string.ptt_open_location_settings),
                    filled = true,
                    accentColor = C.GOLD,
                    heightDp = 38
                ) {
                    act.openLocationSettings()
                }
                errorActionsRow.addView(locBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(38)))
            }

            else -> Unit
        }
    }

    private fun renderDiscoveredSessions(sessions: List<DiscoveredPttSession>) {
        discoveredListContainer.removeAllViews()
        if (sessions.isEmpty()) {
            val emptyMsg = if (latestState.phase == PttPhase.SCANNING) {
                act.getString(R.string.ptt_scanning_sessions)
            } else {
                act.getString(R.string.ptt_no_sessions_found)
            }
            discoveredListContainer.addView(
                act.text(emptyMsg, 13f, C.TEXT2).apply {
                    gravity = Gravity.CENTER
                    background = roundRect(C.CARD_SOFT, act.dp(14).toFloat(), C.BORDER, act.dp(1))
                    setPadding(act.dp(14), act.dp(18), act.dp(14), act.dp(18))
                },
                lp()
            )
            return
        }

        for (session in sessions) {
            val row = act.hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(C.CARD_SOFT, act.dp(16).toFloat(), C.BORDER, act.dp(1))
                setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            }
            val infoCol = act.vbox()
            infoCol.addView(act.text(act.getString(R.string.ptt_discovered_host_fmt, session.hostName), 15f, C.TEXT, Fonts.bold))
            if (session.discoveredViaFallback) {
                infoCol.addView(
                    act.text(act.getString(R.string.ptt_discovered_peer_fallback_badge), 11.5f, C.GOLD_DEEP),
                    lp().margins(t = act.dp(2))
                )
            } else if (session.deviceName.isNotBlank() && session.deviceName != session.hostName) {
                infoCol.addView(act.text(session.deviceName, 12f, C.TEXT2), lp().margins(t = act.dp(2)))
            }
            row.addView(infoCol, lp(0, weight = 1f).margins(e = act.dp(8)))

            val joinBtn = uniformActionButton(
                label = act.getString(R.string.ptt_request_connect),
                filled = true,
                accentColor = C.GOLD,
                heightDp = 40
            ) {
                if (commitTypedNameOrWarn()) {
                    showJoinChannelPrompt(session)
                }
            }
            row.addView(joinBtn, LinearLayout.LayoutParams(act.dp(118), act.dp(40)))
            discoveredListContainer.addView(row, lp().margins(b = act.dp(8)))
        }
    }

    private fun renderPendingApprovals(approvals: List<PendingJoinApproval>) {
        approvalsContainer.removeAllViews()
        for (req in approvals) {
            val card = act.vbox().apply {
                background = roundRect(C.GOLD_PALE, act.dp(18).toFloat(), C.GOLD, act.dp(1))
                setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            }
            card.addView(act.text(act.getString(R.string.ptt_join_request_title), 12.5f, C.GOLD_DEEP, Fonts.bold))
            card.addView(
                act.text(
                    act.getString(R.string.ptt_join_request_body_fmt, req.displayName, req.deviceFingerprint),
                    14f,
                    C.TEXT,
                    Fonts.medium
                ),
                lp().margins(t = act.dp(4), b = act.dp(10))
            )
            // Symmetric equal-dimension Accept & Reject buttons
            val btnRow = act.hbox()
            val acceptBtn = uniformActionButton(
                label = act.getString(R.string.ptt_accept),
                filled = true,
                accentColor = C.GREEN,
                heightDp = 42
            ) {
                repo.approveJoinRequest(req.requestId)
            }
            val rejectBtn = uniformActionButton(
                label = act.getString(R.string.ptt_reject),
                filled = false,
                accentColor = C.RED,
                heightDp = 42
            ) {
                repo.rejectJoinRequest(req.requestId)
            }
            btnRow.addView(acceptBtn, LinearLayout.LayoutParams(0, act.dp(42), 1f).margins(e = act.dp(6)))
            btnRow.addView(rejectBtn, LinearLayout.LayoutParams(0, act.dp(42), 1f).margins(s = act.dp(6)))
            card.addView(btnRow, lp())
            approvalsContainer.addView(card, lp().margins(b = act.dp(10)))
        }
    }

    private fun renderParticipantsList(participants: List<PttParticipant>) {
        participantsContainer.removeAllViews()
        for (p in participants) {
            val row = act.hbox().apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(C.CARD_SOFT, act.dp(15).toFloat(), C.BORDER, act.dp(1))
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            }

            val nameCol = act.vbox()
            val roleSuffix = buildString {
                if (p.isSelf) append(" (${act.getString(R.string.ptt_you_tag)})")
                if (p.isHost) append(" • ${act.getString(R.string.ptt_host_tag)}")
            }
            nameCol.addView(
                act.text(p.displayName + roleSuffix, 14.5f, C.TEXT, Fonts.bold).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            )

            val stateText = when (p.status) {
                ParticipantStatus.SPEAKING -> act.getString(R.string.ptt_peer_state_speaking)
                ParticipantStatus.CONNECTED -> act.getString(R.string.ptt_peer_state_connected)
                ParticipantStatus.DISCONNECTED -> act.getString(R.string.ptt_peer_state_disconnected)
            }
            val qualityText = when (p.quality) {
                LinkQualityLevel.EXCELLENT -> act.getString(R.string.ptt_quality_excellent)
                LinkQualityLevel.GOOD -> act.getString(R.string.ptt_quality_good)
                LinkQualityLevel.FAIR -> act.getString(R.string.ptt_quality_fair)
                LinkQualityLevel.WEAK -> act.getString(R.string.ptt_quality_weak)
                LinkQualityLevel.MEASURING -> act.getString(R.string.ptt_quality_measuring)
            }
            val stateColor = when (p.status) {
                ParticipantStatus.SPEAKING -> C.GREEN
                ParticipantStatus.DISCONNECTED -> C.RED
                ParticipantStatus.CONNECTED -> C.TEXT2
            }
            val subtitle = if (p.isSelf) stateText else "$stateText  •  $qualityText"
            nameCol.addView(act.text(subtitle, 12f, stateColor, Fonts.medium), lp().margins(t = act.dp(2)))
            row.addView(nameCol, lp(0, weight = 1f).margins(e = act.dp(8)))

            if (!p.isSelf) {
                row.addView(
                    QualityBarsView(act, p.quality),
                    LinearLayout.LayoutParams(act.dp(20), act.dp(16)).margins(e = act.dp(10))
                )
                // Strictly uniform dimensions & corner radii for all list action buttons
                val muteLabel = if (p.locallyMuted) {
                    act.getString(R.string.ptt_unmute_peer)
                } else {
                    act.getString(R.string.ptt_mute_peer)
                }
                val peerMuteBtn = uniformActionButton(
                    label = muteLabel,
                    filled = p.locallyMuted,
                    accentColor = if (p.locallyMuted) C.AMBER else C.GOLD_DEEP,
                    heightDp = 36,
                    cornerDp = 12,
                    textSp = 12f
                ) {
                    repo.togglePeerLocalMute(p.id)
                }
                row.addView(peerMuteBtn, LinearLayout.LayoutParams(act.dp(86), act.dp(36)))
            }

            participantsContainer.addView(row, lp().margins(b = act.dp(8)))
        }
    }

    // ==================== Dialogs: Create Session, Join Code, Settings, Permission Rationale ====================

    private fun showCreateSessionDialog(useHotspotFallback: Boolean) {
        val box = act.vbox()
        box.addView(act.text(act.getString(R.string.ptt_channel_code_label), 12.5f, C.TEXT2, Fonts.medium), lp().margins(b = act.dp(6)))

        val codeInput = act.input(act.getString(R.string.ptt_channel_code_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            textDirection = View.TEXT_DIRECTION_LTR
            val initial = repo.settings.lastChannelCode.takeIf { PttSettings.isValidChannelCode(it) }
                ?: PttSettings.generateChannelCode()
            setText(initial)
        }
        val codeRow = act.hbox()
        val genBtn = uniformActionButton(
            label = act.getString(R.string.ptt_generate_code),
            filled = false,
            accentColor = C.GOLD_DEEP,
            heightDp = 46,
            textSp = 12.5f
        ) {
            codeInput.setText(PttSettings.generateChannelCode())
        }
        codeRow.addView(codeInput, lp(0, weight = 1f).margins(e = act.dp(8)))
        codeRow.addView(genBtn, LinearLayout.LayoutParams(act.dp(116), act.dp(46)))
        box.addView(codeRow, lp())

        val errLabel = act.text("", 12.5f, C.RED, Fonts.medium).apply { visibility = View.GONE }
        box.addView(errLabel, lp().margins(t = act.dp(6)))

        NovaDialog(act)
            .title(act.getString(R.string.ptt_create_session))
            .content(box)
            .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(act.getString(R.string.ptt_start_hosting), filled = true) { dlg ->
                val raw = codeInput.text?.toString()?.trim().orEmpty()
                if (!PttSettings.isValidChannelCode(raw)) {
                    errLabel.text = act.getString(R.string.ptt_err_invalid_code)
                    errLabel.visibility = View.VISIBLE
                    return@button
                }
                dlg.dismiss()
                repo.createHostSession(raw, useLocalHotspotFallback = useHotspotFallback)
            }
            .show()
    }

    private fun showJoinChannelPrompt(session: DiscoveredPttSession) {
        val box = act.vbox()
        box.addView(
            act.text(act.getString(R.string.ptt_discovered_host_fmt, session.hostName), 14.5f, C.GOLD_DEEP, Fonts.bold),
            lp().margins(b = act.dp(8))
        )
        box.addView(act.text(act.getString(R.string.ptt_channel_code_label), 12.5f, C.TEXT2, Fonts.medium), lp().margins(b = act.dp(6)))

        val codeInput = act.input(act.getString(R.string.ptt_channel_code_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            textDirection = View.TEXT_DIRECTION_LTR
            if (PttSettings.isValidChannelCode(repo.settings.lastChannelCode)) {
                setText(repo.settings.lastChannelCode)
            }
        }
        box.addView(codeInput, lp())

        val errLabel = act.text("", 12.5f, C.RED, Fonts.medium).apply { visibility = View.GONE }
        box.addView(errLabel, lp().margins(t = act.dp(6)))

        NovaDialog(act)
            .title(act.getString(R.string.ptt_request_connect))
            .content(box)
            .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(act.getString(R.string.ptt_request_connect), filled = true) { dlg ->
                val raw = codeInput.text?.toString()?.trim().orEmpty()
                if (!PttSettings.isValidChannelCode(raw)) {
                    errLabel.text = act.getString(R.string.ptt_err_invalid_code)
                    errLabel.visibility = View.VISIBLE
                    return@button
                }
                dlg.dismiss()
                repo.requestJoinDiscoveredSession(session, raw)
            }
            .show()
    }

    /**
     * Dedicated dialog exclusively for Local-Only Hotspot fallback.
     * Pre-checks whether the phone is actually associated with a Wi-Fi Hotspot gateway first so the
     * user never confuses it with standard Wi-Fi Direct discovery or sees a false "Wi-Fi is off" error.
     */
    private fun showJoinLocalHotspotDialog() {
        when (repo.wifiDirect.checkLocalHotspotGateway()) {
            is LocalHotspotGatewayCheck.WifiTurnedOff -> {
                NovaDialog(act)
                    .title(act.getString(R.string.ptt_join_hotspot_dialog_title))
                    .message(act.getString(R.string.ptt_err_wifi_disabled))
                    .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
                    .button(act.getString(R.string.ptt_open_wifi_settings), filled = true) { dlg ->
                        dlg.dismiss()
                        try { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Throwable) {}
                    }
                    .show()
                return
            }
            is LocalHotspotGatewayCheck.NotConnectedToHotspot -> {
                NovaDialog(act)
                    .title(act.getString(R.string.ptt_join_hotspot_dialog_title))
                    .message(act.getString(R.string.ptt_err_hotspot_not_connected))
                    .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
                    .button(act.getString(R.string.ptt_open_wifi_settings), filled = true) { dlg ->
                        dlg.dismiss()
                        try { act.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } catch (_: Throwable) {}
                    }
                    .show()
                return
            }
            is LocalHotspotGatewayCheck.Ready -> Unit
        }

        val box = act.vbox()
        box.addView(
            act.text(act.getString(R.string.ptt_join_hotspot_instructions), 12.5f, C.TEXT2).apply {
                background = roundRect(C.GOLD_PALE, act.dp(12).toFloat(), C.GOLD_LIGHT, act.dp(1))
                setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
            },
            lp().margins(b = act.dp(10))
        )
        box.addView(act.text(act.getString(R.string.ptt_channel_code_label), 12.5f, C.TEXT2, Fonts.medium), lp().margins(b = act.dp(6)))

        val codeInput = act.input(act.getString(R.string.ptt_channel_code_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(8))
            textDirection = View.TEXT_DIRECTION_LTR
            if (PttSettings.isValidChannelCode(repo.settings.lastChannelCode)) {
                setText(repo.settings.lastChannelCode)
            }
        }
        box.addView(codeInput, lp())

        val errLabel = act.text("", 12.5f, C.RED, Fonts.medium).apply { visibility = View.GONE }
        box.addView(errLabel, lp().margins(t = act.dp(6)))

        NovaDialog(act)
            .title(act.getString(R.string.ptt_join_hotspot_dialog_title))
            .content(box)
            .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(act.getString(R.string.ptt_join_hotspot_btn), filled = true) { dlg ->
                val raw = codeInput.text?.toString()?.trim().orEmpty()
                if (!PttSettings.isValidChannelCode(raw)) {
                    errLabel.text = act.getString(R.string.ptt_err_invalid_code)
                    errLabel.visibility = View.VISIBLE
                    return@button
                }
                dlg.dismiss()
                repo.requestJoinLocalHotspotGateway(raw)
            }
            .show()
    }

    private fun showWalkieSettingsDialog() {
        val box = act.vbox()

        box.addView(act.text(act.getString(R.string.ptt_setting_talk_mode), 12.5f, C.GOLD_DEEP, Fonts.bold), lp().margins(b = act.dp(6)))
        box.addView(
            buildSegmentedControl(
                options = listOf(act.getString(R.string.ptt_mode_hold), act.getString(R.string.ptt_mode_toggle)),
                selectedIndex = if (repo.settings.talkMode == TalkMode.HOLD_TO_TALK) 0 else 1
            ) { idx ->
                repo.setTalkMode(if (idx == 0) TalkMode.HOLD_TO_TALK else TalkMode.TAP_TO_TOGGLE)
            },
            lp().margins(b = act.dp(14))
        )

        box.addView(act.text(act.getString(R.string.ptt_setting_roger_beep), 12.5f, C.GOLD_DEEP, Fonts.bold), lp().margins(b = act.dp(6)))
        box.addView(
            buildSegmentedControl(
                options = listOf(act.getString(R.string.ptt_beep_on), act.getString(R.string.ptt_beep_off)),
                selectedIndex = if (repo.settings.rogerBeepEnabled) 0 else 1
            ) { idx ->
                repo.setRogerBeepEnabled(idx == 0)
            },
            lp()
        )

        NovaDialog(act)
            .title(act.getString(R.string.ptt_settings_title))
            .content(box)
            .button(act.getString(R.string.portal_done), filled = true) { it.dismiss() }
            .show()
    }

    private fun ensureWalkiePermissionsThen(onGranted: () -> Unit) {
        repo.clearTransientMessages()
        val missingCore = PttPermissions.missingCorePermissions(act)
        if (missingCore.isEmpty()) {
            onGranted()
            return
        }

        val triggerSystemPrompt = {
            repo.settings.permissionRationaleShown = true
            val toAsk = PttPermissions.allPromptPermissions(Build.VERSION.SDK_INT).toTypedArray()
            act.requestPerms(toAsk) {
                if (PttPermissions.missingCorePermissions(act).isEmpty()) {
                    onGranted()
                } else {
                    act.message.error(act.getString(R.string.ptt_perm_denied_msg))
                }
            }
        }

        // Always show a clear Arabic / English explanation screen before invoking system permissions
        NovaDialog(act)
            .title(act.getString(R.string.ptt_perm_rationale_title))
            .message(act.getString(R.string.ptt_perm_rationale_body))
            .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(act.getString(R.string.ptt_perm_continue), filled = true) { dlg ->
                dlg.dismiss()
                triggerSystemPrompt()
            }
            .show()
    }

    private fun commitTypedNameOrWarn(): Boolean {
        val entered = nameInput.text?.toString()?.trim().orEmpty()
        return if (repo.saveDisplayName(entered)) {
            true
        } else {
            act.message.error(act.getString(R.string.ptt_err_name_empty))
            false
        }
    }

    // ==================== Visual Helpers & Custom PTT Circle ====================

    private fun uniformActionButton(
        label: String,
        filled: Boolean,
        accentColor: Int,
        heightDp: Int,
        cornerDp: Int = 16,
        textSp: Float = 14f,
        onClick: () -> Unit
    ): TextView = act.text(label, textSp, if (filled) C.ON_PRIMARY else accentColor, Fonts.medium).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minimumHeight = act.dp(heightDp)
        setPadding(act.dp(12), 0, act.dp(12), 0)
        paintUniformButton(this, label, filled, accentColor, cornerDp)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun paintUniformButton(
        btn: TextView,
        label: String,
        filled: Boolean,
        accentColor: Int,
        cornerDp: Int = 16
    ) {
        btn.text = label
        val r = act.dp(cornerDp).toFloat()
        if (filled) {
            btn.setTextColor(C.ON_PRIMARY)
            val fillDrawable = if (accentColor == C.GOLD) primaryFill(r) else roundRect(accentColor, r)
            btn.background = ripple(fillDrawable, r, 0x55FFFFFF)
        } else {
            btn.setTextColor(accentColor)
            val stroke = (accentColor and 0x00FFFFFF) or 0x66000000
            btn.background = ripple(roundRect(C.CARD, r, stroke, act.dp(1)), r)
        }
    }

    private fun buildSegmentedControl(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit): View {
        val box = act.hbox().apply {
            background = roundRect(C.GOLD_PALE, act.dp(14).toFloat(), C.GOLD_LIGHT, act.dp(1))
            setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        }
        val tabs = options.mapIndexed { idx, title ->
            act.text(title, 13f, C.TEXT2, Fonts.medium).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(act.dp(8), act.dp(9), act.dp(8), act.dp(9))
                box.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
        fun refresh(sel: Int) {
            tabs.forEachIndexed { idx, tv ->
                if (idx == sel) {
                    tv.background = primaryFill(act.dp(11).toFloat())
                    tv.setTextColor(C.ON_PRIMARY)
                } else {
                    tv.background = null
                    tv.setTextColor(C.TEXT2)
                }
            }
        }
        refresh(selectedIndex)
        tabs.forEachIndexed { idx, tv ->
            tv.setOnClickListener {
                refresh(idx)
                onSelect(idx)
            }
        }
        return box
    }

    /**
     * Large circular Push-to-Talk control rendering the three exact required states:
     *  • Idle: Royal Blue #2457D6
     *  • Self Speaking: Emerald Green #14866D + lightweight concentric pulse rings
     *  • Other Speaking: Amber #D99A32
     */
    private class PttTalkCircleView(ctx: android.content.Context) : FrameLayout(ctx) {
        private var currentState = PttButtonVisualState.IDLE_BLUE
        private var pulseProgress = 0f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val outerHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val mainDiscPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val innerBezelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x44FFFFFF
        }

        private val micIconView: TextView
        private val statusLabelView: TextView

        private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1250L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                pulseProgress = anim.animatedValue as Float
                invalidate()
            }
        }

        init {
            setWillNotDraw(false)
            isClickable = true
            isFocusable = true

            val innerCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val pad = ctx.dp(24)
                setPadding(pad, pad, pad, pad)
            }
            micIconView = ctx.text("🎙", 34f, C.ON_PRIMARY, Fonts.bold).apply {
                gravity = Gravity.CENTER
            }
            statusLabelView = ctx.text("", 15.5f, C.ON_PRIMARY, Fonts.bold).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.12f)
            }
            innerCol.addView(micIconView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            innerCol.addView(
                statusLabelView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ctx.dp(8)
                }
            )
            addView(innerCol, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }

        fun updateState(state: PttButtonVisualState, label: String) {
            statusLabelView.text = label
            micIconView.text = when (state) {
                PttButtonVisualState.IDLE_BLUE -> "🎙"
                PttButtonVisualState.SELF_SPEAKING_GREEN -> "📡"
                PttButtonVisualState.REMOTE_SPEAKING_AMBER -> "🔊"
            }
            if (currentState != state) {
                currentState = state
                if (state == PttButtonVisualState.SELF_SPEAKING_GREEN) {
                    if (!pulseAnimator.isStarted) pulseAnimator.start()
                } else {
                    stopPulseAnimation()
                }
                invalidate()
            }
        }

        fun stopPulseAnimation() {
            if (pulseAnimator.isStarted || pulseAnimator.isRunning) {
                pulseAnimator.cancel()
            }
            pulseProgress = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val maxRadius = min(cx, cy) - dpf(4f)
            if (maxRadius <= 0f) return

            val discRadius = maxRadius * 0.78f
            val baseColor = currentState.hexColor
            val rgb = baseColor and 0x00FFFFFF

            // Soft ambient halo matching current state in both Light & Dark appearances
            outerHaloPaint.color = (0x24 shl 24) or rgb
            canvas.drawCircle(cx, cy, discRadius + dpf(10f), outerHaloPaint)

            // Animated emerald pulse rings ONLY while local user is speaking
            if (currentState == PttButtonVisualState.SELF_SPEAKING_GREEN) {
                ringPaint.strokeWidth = dpf(2.5f)
                for (ringIndex in 0..1) {
                    val phase = (pulseProgress + ringIndex * 0.5f) % 1f
                    val r = discRadius + phase * (maxRadius - discRadius)
                    val alpha = ((1f - phase) * 165f).toInt().coerceIn(0, 255)
                    ringPaint.color = (alpha shl 24) or rgb
                    canvas.drawCircle(cx, cy, r, ringPaint)
                }
            }

            // Main solid circle
            mainDiscPaint.color = baseColor
            canvas.drawCircle(cx, cy, discRadius, mainDiscPaint)

            // Crisp inner highlight bezel
            innerBezelPaint.strokeWidth = dpf(1.6f)
            canvas.drawCircle(cx, cy, discRadius - dpf(5f), innerBezelPaint)
        }
    }

    /** 4-bar link quality gauge backed by real measured RTT and packet loss. */
    private class QualityBarsView(ctx: android.content.Context, private val quality: LinkQualityLevel) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val activeColor = when (quality) {
                LinkQualityLevel.EXCELLENT -> C.GREEN
                LinkQualityLevel.GOOD -> C.SIGNAL_BLUE
                LinkQualityLevel.FAIR -> C.AMBER
                LinkQualityLevel.WEAK -> C.RED
                LinkQualityLevel.MEASURING -> C.SIGNAL_BLUE
            }
            val filledBars = if (quality == LinkQualityLevel.MEASURING) 2 else quality.bars
            val barW = w / 6.2f
            val gap = barW * 0.65f
            for (i in 0..3) {
                val barH = h * (0.34f + i * 0.22f)
                val left = i * (barW + gap)
                paint.color = if (i < filledBars) activeColor else C.SIGNAL_TRACK
                rect.set(left, h - barH, left + barW, h)
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)
            }
        }
    }
}
