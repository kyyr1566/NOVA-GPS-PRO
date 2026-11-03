package com.nova.gpspro.connect

import android.Manifest
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.data.Destination
import com.nova.gpspro.location.GpsState
import com.nova.gpspro.location.GpsStatus
import com.nova.gpspro.location.LocationEngine
import com.nova.gpspro.navigation.GeoMath
import com.nova.gpspro.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * Full-screen RMG CONNECT workspace hosted inside [MainActivity] so switching into or out of
 * Nearby Connect never triggers spurious Activity stops, while respecting Light/Dark themes,
 * RTL/LTR layouts, and the exact sapphire/ivory design language of RMG-GPS-Pro.
 */
class RmgConnectOverlay(
    private val act: MainActivity,
    private val onClose: () -> Unit
) : FrameLayout(act) {

    private val mgr: RmgConnectManager get() = act.app.rmgConnect

    private var activeTab = 0 // 0 = Peers, 1 = Chat, 2 = Locations
    private var latestSnapshot: RmgSessionSnapshot = mgr.snapshot
    private var latestMyGps: GpsState = act.app.gps.state
    private var lastMessageCount = -1

    // Header & status views
    private val subtitleView: TextView
    private val endSessionBtn: TextView

    // Voice & GPS deck views
    private val pttModeBtn: TextView
    private val openMicModeBtn: TextView
    private val btAudioBtn: TextView
    private val pttHoldBtn: TextView
    private val openMicToggleBtn: TextView
    private val gpsToggleBtn: TextView

    // Tabs
    private val tabButtons = ArrayList<TextView>(3)
    private val peersScroll: ScrollView
    private val peersContent: LinearLayout
    private val chatContainer: LinearLayout
    private val chatScroll: ScrollView
    private val chatList: LinearLayout
    private val chatInput = act.input(act.getString(R.string.rmg_connect_chat_hint))
    private val locationsScroll: ScrollView
    private val locationsContent: LinearLayout

    private val snapshotListener = RmgConnectManager.Listener { snap ->
        latestSnapshot = snap
        bindSnapshot(snap)
    }

    private val gpsListener = LocationEngine.Listener { state ->
        latestMyGps = state
        if (activeTab == 2) renderLocationsTab(latestSnapshot)
    }

    private val uiEventsHook = object : RmgConnectManager.UiEvents {
        override fun onToastInfo(resId: Int) {
            act.message.error(act.getString(resId))
        }
    }

    init {
        setBackgroundColor(C.BG)
        isClickable = true
        isFocusable = true
        layoutDirection = if (act.isRtl()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

        val root = act.vbox().apply {
            setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(12))
        }

        // ==================== 1. Header Bar ====================
        val header = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val backArrow = if (act.isRtl()) "→" else "←"
        val backBtn = act.text(backArrow, 19f, C.TEXT, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(14).toFloat()
            background = ripple(roundRect(C.CARD, r, C.BORDER, act.dp(1)), r)
            setOnClickListener { onClose() }
        }
        header.addView(backBtn, LinearLayout.LayoutParams(act.dp(40), act.dp(40)))

        val titleBox = act.vbox().apply {
            setPadding(act.dp(10), 0, act.dp(8), 0)
        }
        titleBox.addView(act.text(act.getString(R.string.rmg_connect_card_title), 19f, C.TEXT, Fonts.bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        subtitleView = act.text(act.getString(R.string.rmg_connect_badge), 11f, C.GOLD, Fonts.medium).apply {
            letterSpacing = 0.12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        titleBox.addView(subtitleView)
        header.addView(titleBox, lp(0, weight = 1f))

        // Device name & blocked list quick pills
        val nameBtn = act.text("✎", 15f, C.GOLD_DEEP, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(12).toFloat()
            background = ripple(roundRect(C.GOLD_PALE, r, C.GOLD_LIGHT, act.dp(1)), r)
            contentDescription = act.getString(R.string.rmg_connect_device_name_title)
            setOnClickListener { showEditDeviceNameDialog() }
        }
        header.addView(nameBtn, LinearLayout.LayoutParams(act.dp(36), act.dp(36)).margins(e = act.dp(6)))

        val blockedBtn = act.text("⊘", 15f, C.TEXT2, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(12).toFloat()
            background = ripple(roundRect(C.CARD, r, C.BORDER, act.dp(1)), r)
            contentDescription = act.getString(R.string.rmg_connect_blocked_title)
            setOnClickListener { showBlockedPeersDialog() }
        }
        header.addView(blockedBtn, LinearLayout.LayoutParams(act.dp(36), act.dp(36)))

        endSessionBtn = act.text(act.getString(R.string.rmg_connect_notif_end), 12f, C.RED, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(12).toFloat()
            background = ripple(roundRect(C.RED_LIGHT, r, C.RED, act.dp(1)), r)
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
            visibility = View.GONE
            setOnClickListener { mgr.disconnectAllAndEndSession() }
        }
        header.addView(endSessionBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = act.dp(6)))
        root.addView(header, lp())

        // ==================== 2. Voice & Optional GPS Control Deck ====================
        val deck = act.vbox().apply {
            background = act.card(20)
            softElevation(2f)
            setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10))
        }

        val modeRow = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val modeSwitchBox = act.hbox().apply {
            background = roundRect(C.CARD_SOFT, act.dp(13).toFloat(), C.BORDER, act.dp(1))
            setPadding(act.dp(3), act.dp(3), act.dp(3), act.dp(3))
        }
        pttModeBtn = smallSegmentBtn(act.getString(R.string.rmg_connect_voice_ptt)) {
            mgr.setVoiceMode(VoiceMode.PTT)
        }
        openMicModeBtn = smallSegmentBtn(act.getString(R.string.rmg_connect_voice_open)) {
            mgr.setVoiceMode(VoiceMode.OPEN_MIC)
        }
        modeSwitchBox.addView(pttModeBtn, lp(0, weight = 1f))
        modeSwitchBox.addView(openMicModeBtn, lp(0, weight = 1f))
        modeRow.addView(modeSwitchBox, lp(0, weight = 1f))

        btAudioBtn = act.text(act.getString(R.string.rmg_connect_bt_audio), 11.5f, C.TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
            val r = act.dp(12).toFloat()
            background = ripple(roundRect(C.CARD_SOFT, r, C.BORDER, act.dp(1)), r)
            setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
            setOnClickListener {
                mgr.setBluetoothAudioEnabled(!latestSnapshot.isBluetoothAudioActive)
            }
        }
        modeRow.addView(btAudioBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = act.dp(8)))
        deck.addView(modeRow, lp())

        val controlsRow = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }

        // Hold-to-Talk (PTT) priority control
        pttHoldBtn = act.text(act.getString(R.string.rmg_connect_ptt_hold), 12.5f, C.ON_PRIMARY, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(14).toFloat()
            background = primaryFill(r)
            setPadding(act.dp(12), act.dp(11), act.dp(12), act.dp(11))
            letterSpacing = 0.03f
            isClickable = true
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        if (!RmgPermissions.hasRecordAudioPermission(act)) {
                            requestAudioPermission { granted ->
                                if (!granted) act.message.error(act.getString(R.string.rmg_connect_perm_mic_denied))
                            }
                        } else {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            mgr.setPttPressed(true)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (latestSnapshot.isPttPressed) {
                            mgr.setPttPressed(false)
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        controlsRow.addView(pttHoldBtn, lp(0, weight = 1.25f))

        // Open-Mic instant mute/unmute control
        openMicToggleBtn = act.text(act.getString(R.string.rmg_connect_mic_muted), 12.5f, C.TEXT, Fonts.bold).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(12), act.dp(11), act.dp(12), act.dp(11))
            visibility = View.GONE
            setOnClickListener {
                if (latestSnapshot.isMicMuted) {
                    ensureAudioPermissionThen { mgr.setMicMuted(false) }
                } else {
                    mgr.setMicMuted(true)
                }
            }
        }
        controlsRow.addView(openMicToggleBtn, lp(0, weight = 1.25f))

        // Optional GPS sharing toggle (OFF by default)
        gpsToggleBtn = act.text(act.getString(R.string.rmg_connect_share_gps), 12f, C.TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
            setPadding(act.dp(10), act.dp(11), act.dp(10), act.dp(11))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener {
                if (!latestSnapshot.isSharingLocation) {
                    ensureLocationPermissionThen {
                        ensureNotificationPermissionSoft()
                        mgr.setLocationSharingEnabled(true)
                    }
                } else {
                    mgr.setLocationSharingEnabled(false)
                }
            }
        }
        controlsRow.addView(gpsToggleBtn, lp(0, weight = 1f).margins(s = act.dp(8)))
        deck.addView(controlsRow, lp().margins(t = act.dp(8)))
        root.addView(deck, lp().margins(t = act.dp(10)))

        // ==================== 3. Section Tabs ====================
        val tabBar = act.hbox().apply {
            background = roundRect(C.CARD, act.dp(16).toFloat(), C.BORDER, act.dp(1))
            setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4))
        }
        val tabTitles = listOf(
            R.string.rmg_connect_tab_peers,
            R.string.rmg_connect_tab_chat,
            R.string.rmg_connect_tab_locations
        )
        tabTitles.forEachIndexed { idx, res ->
            val btn = act.text(act.getString(res), 13f, C.TEXT2, Fonts.medium).apply {
                gravity = Gravity.CENTER
                setPadding(act.dp(8), act.dp(8), act.dp(8), act.dp(8))
                setOnClickListener { selectInnerTab(idx) }
            }
            tabButtons.add(btn)
            tabBar.addView(btn, lp(0, weight = 1f))
        }
        root.addView(tabBar, lp().margins(t = act.dp(10), b = act.dp(8)))

        // ==================== 4. Tab Stage ====================
        val stage = FrameLayout(act)

        // Tab 0: Peers & Discovery
        peersContent = act.vbox().apply { setPadding(0, act.dp(2), 0, act.dp(16)) }
        peersScroll = ScrollView(act).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(peersContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        stage.addView(peersScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Tab 1: Local Group Chat
        chatContainer = act.vbox().apply { visibility = View.GONE }
        chatList = act.vbox().apply { setPadding(0, act.dp(4), 0, act.dp(8)) }
        chatScroll = ScrollView(act).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(chatList, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        chatContainer.addView(chatScroll, lp(h = 0, weight = 1f))

        val composer = act.hbox().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, act.dp(6), 0, 0)
        }
        chatInput.apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                    submitChatMessage()
                    true
                } else false
            }
        }
        val sendBtn = act.goldButton(act.getString(R.string.rmg_connect_chat_send)) { submitChatMessage() }.apply {
            setPadding(act.dp(18), act.dp(12), act.dp(18), act.dp(12))
        }
        composer.addView(chatInput, lp(0, weight = 1f))
        composer.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(s = act.dp(8)))
        chatContainer.addView(composer, lp())
        stage.addView(chatContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Tab 2: Shared GPS Locations + Distance + Bearing + Last Known
        locationsContent = act.vbox().apply { setPadding(0, act.dp(2), 0, act.dp(16)) }
        locationsScroll = ScrollView(act).apply {
            visibility = View.GONE
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(locationsContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        stage.addView(locationsScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        root.addView(stage, lp(h = 0, weight = 1f))
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        selectInnerTab(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mgr.uiEvents = uiEventsHook
        mgr.addListener(snapshotListener)
        act.app.gps.addListener(gpsListener)
    }

    override fun onDetachedFromWindow() {
        if (mgr.uiEvents === uiEventsHook) mgr.uiEvents = null
        mgr.removeListener(snapshotListener)
        act.app.gps.removeListener(gpsListener)
        if (latestSnapshot.isPttPressed) mgr.setPttPressed(false)
        super.onDetachedFromWindow()
    }

    private fun smallSegmentBtn(label: String, onClick: () -> Unit): TextView =
        act.text(label, 11.5f, C.TEXT2, Fonts.medium).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(act.dp(8), act.dp(7), act.dp(8), act.dp(7))
            setOnClickListener { onClick() }
        }

    private fun selectInnerTab(index: Int) {
        activeTab = index.coerceIn(0, 2)
        peersScroll.visibility = if (activeTab == 0) View.VISIBLE else View.GONE
        chatContainer.visibility = if (activeTab == 1) View.VISIBLE else View.GONE
        locationsScroll.visibility = if (activeTab == 2) View.VISIBLE else View.GONE

        tabButtons.forEachIndexed { idx, tv ->
            val sel = idx == activeTab
            tv.setTextColor(if (sel) C.ON_PRIMARY else C.TEXT2)
            tv.background = if (sel) primaryFill(act.dp(12).toFloat()) else ColorDrawable(0)
        }
        bindSnapshot(latestSnapshot)
    }

    // ==================== Binding Live State ====================

    private fun bindSnapshot(snap: RmgSessionSnapshot) {
        val activeCount = snap.activeConnectedCount
        subtitleView.text = when {
            activeCount > 0 -> "${act.getString(R.string.rmg_connect_badge)}  •  ${act.getString(R.string.rmg_connect_status_active_fmt, activeCount)}"
            snap.isGroupHost -> "${act.getString(R.string.rmg_connect_badge)}  •  ${act.getString(R.string.rmg_connect_status_hosting)}"
            else -> "${act.getString(R.string.rmg_connect_badge)}  •  ${snap.localDisplayName}"
        }
        subtitleView.setTextColor(if (activeCount > 0) C.GREEN else C.GOLD)
        endSessionBtn.visibility = if (snap.hasActiveSession) View.VISIBLE else View.GONE

        // Voice mode segments
        val isPtt = snap.voiceMode == VoiceMode.PTT
        paintSegment(pttModeBtn, isPtt)
        paintSegment(openMicModeBtn, !isPtt)

        pttHoldBtn.visibility = if (isPtt) View.VISIBLE else View.GONE
        openMicToggleBtn.visibility = if (!isPtt) View.VISIBLE else View.GONE

        if (isPtt) {
            val r = act.dp(14).toFloat()
            if (snap.isPttPressed) {
                pttHoldBtn.text = act.getString(R.string.rmg_connect_ptt_transmitting)
                pttHoldBtn.background = roundRect(C.GREEN, r)
            } else {
                pttHoldBtn.text = act.getString(R.string.rmg_connect_ptt_hold)
                pttHoldBtn.background = primaryFill(r)
            }
        } else {
            val r = act.dp(14).toFloat()
            if (snap.isMicMuted) {
                openMicToggleBtn.text = "🔇  ${act.getString(R.string.rmg_connect_mic_muted)}"
                openMicToggleBtn.setTextColor(C.TEXT)
                openMicToggleBtn.background = ripple(roundRect(C.CARD_SOFT, r, C.BORDER, act.dp(1)), r)
            } else {
                openMicToggleBtn.text = "🎙  ${act.getString(R.string.rmg_connect_mic_live)} (${act.getString(R.string.rmg_connect_mute_now)})"
                openMicToggleBtn.setTextColor(C.ON_PRIMARY)
                openMicToggleBtn.background = ripple(roundRect(C.GREEN, r), r)
            }
        }

        // Bluetooth audio button state
        val btR = act.dp(12).toFloat()
        if (snap.isBluetoothAudioActive) {
            btAudioBtn.setTextColor(C.ON_PRIMARY)
            btAudioBtn.background = roundRect(C.SIGNAL_BLUE, btR)
        } else {
            btAudioBtn.setTextColor(if (snap.isBluetoothAudioAvailable) C.GOLD_DEEP else C.TEXT3)
            btAudioBtn.background = roundRect(
                if (snap.isBluetoothAudioAvailable) C.GOLD_PALE else C.CARD_SOFT,
                btR,
                if (snap.isBluetoothAudioAvailable) C.GOLD_LIGHT else C.BORDER,
                act.dp(1)
            )
        }

        // GPS sharing toggle state
        val gpsR = act.dp(14).toFloat()
        if (snap.isSharingLocation) {
            gpsToggleBtn.text = "📍 ${act.getString(R.string.rmg_connect_gps_live)}"
            gpsToggleBtn.setTextColor(C.GREEN_DEEP)
            gpsToggleBtn.background = ripple(roundRect(C.GREEN_LIGHT, gpsR, C.GREEN, act.dp(1)), gpsR)
        } else {
            gpsToggleBtn.text = act.getString(R.string.rmg_connect_share_gps)
            gpsToggleBtn.setTextColor(C.TEXT2)
            gpsToggleBtn.background = ripple(roundRect(C.CARD_SOFT, gpsR, C.BORDER, act.dp(1)), gpsR)
        }

        // Update tab badges
        val peersLabel = if (snap.incomingInvites.isNotEmpty()) {
            "${act.getString(R.string.rmg_connect_tab_peers)} (${snap.incomingInvites.size})"
        } else act.getString(R.string.rmg_connect_tab_peers)
        tabButtons[0].text = peersLabel

        val chatLabel = if (snap.messages.isNotEmpty()) {
            "${act.getString(R.string.rmg_connect_tab_chat)} (${snap.messages.size})"
        } else act.getString(R.string.rmg_connect_tab_chat)
        tabButtons[1].text = chatLabel

        val sharedLocCount = snap.connectedPeers.count { it.lastKnownLocation != null }
        val locLabel = if (sharedLocCount > 0) {
            "${act.getString(R.string.rmg_connect_tab_locations)} ($sharedLocCount)"
        } else act.getString(R.string.rmg_connect_tab_locations)
        tabButtons[2].text = locLabel

        when (activeTab) {
            0 -> renderPeersTab(snap)
            1 -> renderChatTab(snap)
            2 -> renderLocationsTab(snap)
        }
    }

    private fun paintSegment(tv: TextView, selected: Boolean) {
        if (selected) {
            tv.setTextColor(C.ON_PRIMARY)
            tv.background = primaryFill(act.dp(10).toFloat())
        } else {
            tv.setTextColor(C.TEXT2)
            tv.background = null
        }
    }

    // ==================== Tab 0: Peers, Discovery & Invites ====================

    private fun renderPeersTab(snap: RmgSessionSnapshot) {
        peersContent.removeAllViews()

        // Discovery & Direct Group Host row
        val actions = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val scanLabel = if (snap.isDiscovering) act.getString(R.string.rmg_connect_scanning)
        else act.getString(R.string.rmg_connect_scan)

        val scanBtn = act.goldButton(scanLabel) {
            if (snap.isDiscovering) {
                mgr.stopDiscovery()
            } else {
                ensureNearbyPermissionsThen {
                    ensureNotificationPermissionSoft()
                    mgr.startDiscovery()
                }
            }
        }.apply {
            setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))
            textSize = 13.5f
        }
        actions.addView(scanBtn, lp(0, weight = 1f).margins(e = act.dp(6)))

        val hostLabel = if (snap.isGroupHost) act.getString(R.string.rmg_connect_hosting_active)
        else act.getString(R.string.rmg_connect_host_group)
        val hostBtn = act.outlineButton(hostLabel, if (snap.isGroupHost) C.GREEN else C.GOLD_DEEP) {
            ensureNearbyPermissionsThen {
                ensureNotificationPermissionSoft()
                mgr.startDirectGroupHost()
            }
        }.apply {
            setPadding(act.dp(12), act.dp(12), act.dp(12), act.dp(12))
            textSize = 13f
        }
        actions.addView(hostBtn, lp(0, weight = 1f).margins(s = act.dp(6)))
        peersContent.addView(actions, lp().margins(b = act.dp(12)))

        // 1) Incoming Connection Requests (Accept / Reject / Block)
        if (snap.incomingInvites.isNotEmpty()) {
            peersContent.addView(sectionHeader(act.getString(R.string.rmg_connect_section_invites), C.GOLD_DEEP))
            for (inv in snap.incomingInvites) {
                peersContent.addView(buildInviteCard(inv), lp().margins(b = act.dp(10)))
            }
        }

        // 2) Active Connected Peers (with Signal Strength, RTT & Voice Indicator)
        val livePeers = snap.connectedPeers.filter { it.state != PeerLinkState.DISCONNECTED }
        if (livePeers.isNotEmpty()) {
            peersContent.addView(
                sectionHeader(act.getString(R.string.rmg_connect_section_connected, livePeers.size), C.GREEN_DEEP),
                lp().margins(t = act.dp(4), b = act.dp(6))
            )
            for (peer in livePeers) {
                peersContent.addView(buildConnectedPeerCard(peer), lp().margins(b = act.dp(10)))
            }
        }

        // 3) Nearby Discovered Devices (Wi-Fi Direct & Bluetooth)
        peersContent.addView(
            sectionHeader(act.getString(R.string.rmg_connect_section_discovered), C.TEXT2),
            lp().margins(t = act.dp(6), b = act.dp(6))
        )
        if (snap.discoveredPeers.isEmpty()) {
            peersContent.addView(emptyHintBox(act.getString(R.string.rmg_connect_no_peers_hint)))
        } else {
            for (d in snap.discoveredPeers) {
                peersContent.addView(buildDiscoveredPeerCard(d), lp().margins(b = act.dp(8)))
            }
        }
    }

    private fun buildInviteCard(inv: IncomingInvite): View = act.vbox().apply {
        background = roundRect(C.GOLD_PALE, act.dp(18).toFloat(), C.GOLD, act.dp(1))
        setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))

        val transportLabel = transportName(inv.transport)
        addView(act.text(act.getString(R.string.rmg_connect_incoming_title), 12f, C.GOLD_DEEP, Fonts.bold))
        addView(
            act.text(act.getString(R.string.rmg_connect_incoming_fmt, inv.deviceName, transportLabel), 14.5f, C.TEXT, Fonts.medium),
            lp().margins(t = act.dp(4), b = act.dp(10))
        )

        val btnRow = act.hbox()
        val acceptBtn = miniActionPill(act.getString(R.string.rmg_connect_accept), C.ON_PRIMARY, C.GREEN) {
            ensureNotificationPermissionSoft()
            mgr.acceptInvite(inv.inviteId)
        }
        val rejectBtn = miniActionPill(act.getString(R.string.rmg_connect_reject), C.TEXT, C.CARD) {
            mgr.rejectInvite(inv.inviteId)
        }
        val blockBtn = miniActionPill(act.getString(R.string.rmg_connect_block), C.RED, C.RED_LIGHT) {
            mgr.blockInvite(inv.inviteId)
        }
        btnRow.addView(acceptBtn, lp(0, weight = 1f).margins(e = act.dp(6)))
        btnRow.addView(rejectBtn, lp(0, weight = 1f).margins(e = act.dp(6)))
        btnRow.addView(blockBtn, lp(0, weight = 1f))
        addView(btnRow, lp())
    }

    private fun buildConnectedPeerCard(peer: ConnectedPeer): View = act.vbox().apply {
        background = act.card(18)
        setPadding(act.dp(14), act.dp(12), act.dp(14), act.dp(12))

        val topRow = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
        val infoCol = act.vbox()
        infoCol.addView(act.text(peer.name, 15.5f, C.TEXT, Fonts.bold).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })

        val statusBuilder = StringBuilder()
        statusBuilder.append(transportName(peer.transport))
        statusBuilder.append("  •  ")
        if (peer.state == PeerLinkState.RECONNECTING) {
            statusBuilder.append(act.getString(R.string.rmg_connect_reconnecting))
        } else {
            statusBuilder.append(signalLabel(peer.signalQuality))
            peer.rssiDbm?.let { statusBuilder.append(" ($it dBm)") }
            peer.rttMs?.let { statusBuilder.append(" • ${it}ms") }
            peer.linkSpeedMbps?.let { statusBuilder.append(" • ${it}Mbps") }
        }
        val statusColor = if (peer.state == PeerLinkState.RECONNECTING) C.AMBER else C.TEXT2
        infoCol.addView(act.text(statusBuilder.toString(), 11.5f, statusColor, Fonts.medium), lp().margins(t = act.dp(2)))

        if (peer.isSpeaking) {
            val spkTxt = if (peer.isPttPriority) act.getString(R.string.rmg_connect_speaking_ptt)
            else act.getString(R.string.rmg_connect_speaking_open)
            infoCol.addView(act.text("🔊 $spkTxt", 11.5f, C.GREEN, Fonts.bold), lp().margins(t = act.dp(3)))
        }

        topRow.addView(infoCol, lp(0, weight = 1f))
        topRow.addView(SignalStrengthBarsView(act, peer.signalQuality), LinearLayout.LayoutParams(act.dp(22), act.dp(16)).margins(s = act.dp(8), e = act.dp(8)))

        val discBtn = miniActionPill(act.getString(R.string.rmg_connect_disconnect), C.TEXT2, C.CARD_SOFT) {
            mgr.disconnectPeer(peer.nodeId)
        }
        topRow.addView(discBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(e = act.dp(6)))

        val blkBtn = miniActionPill(act.getString(R.string.rmg_connect_block), C.RED, C.RED_LIGHT) {
            mgr.blockConnectedPeer(peer.nodeId)
        }
        topRow.addView(blkBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(topRow, lp())
    }

    private fun buildDiscoveredPeerCard(peer: DiscoveredPeer): View = act.hbox().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = act.card(16)
        setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))

        val col = act.vbox()
        col.addView(act.text(peer.name, 15f, C.TEXT, Fonts.medium).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val meta = buildString {
            append(transportName(peer.transport))
            peer.rssiDbm?.let { append("  •  $it dBm") }
        }
        col.addView(act.text(meta, 11.5f, C.TEXT2), lp().margins(t = act.dp(2)))
        addView(col, lp(0, weight = 1f))

        val quality = SignalQuality.evaluate(peer.rssiDbm, null)
        if (quality != SignalQuality.UNKNOWN) {
            addView(SignalStrengthBarsView(act, quality), LinearLayout.LayoutParams(act.dp(20), act.dp(15)).margins(e = act.dp(10)))
        }

        val btnLabel = if (peer.isPending) act.getString(R.string.rmg_connect_pending)
        else act.getString(R.string.rmg_connect_connect_btn)
        val btn = miniActionPill(
            btnLabel,
            if (peer.isPending) C.TEXT2 else C.ON_PRIMARY,
            if (peer.isPending) C.CARD_SOFT else C.GOLD
        ) {
            if (!peer.isPending) {
                ensureNearbyPermissionsThen {
                    ensureNotificationPermissionSoft()
                    mgr.connectToDiscovered(peer)
                }
            }
        }
        addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ==================== Tab 1: Local Offline Chat ====================

    private fun renderChatTab(snap: RmgSessionSnapshot) {
        chatList.removeAllViews()
        if (snap.messages.isEmpty()) {
            chatList.addView(emptyHintBox(act.getString(R.string.rmg_connect_chat_empty)))
            lastMessageCount = 0
            return
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        for (m in snap.messages) {
            val row = act.hbox().apply {
                gravity = if (m.outgoing) Gravity.END else Gravity.START
            }
            val bubble = act.vbox().apply {
                val r = act.dp(16).toFloat()
                background = if (m.outgoing) {
                    roundRect(C.GOLD_PALE, r, C.GOLD_LIGHT, act.dp(1))
                } else {
                    roundRect(C.CARD, r, C.BORDER, act.dp(1))
                }
                setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
                minimumWidth = act.dp(84)
            }
            if (!m.outgoing) {
                bubble.addView(act.text(m.senderName, 11.5f, C.GOLD_DEEP, Fonts.bold).apply {
                    maxWidth = act.dp(260)
                })
            }
            bubble.addView(act.text(m.text, 14.5f, C.TEXT).apply {
                maxWidth = act.dp(260)
                setLineSpacing(0f, 1.18f)
            }, lp().margins(t = if (m.outgoing) 0 else act.dp(2)))

            val statusStr = buildString {
                append(fmt.format(Date(m.timestampMs)))
                if (m.outgoing) {
                    append(" • ")
                    append(
                        when (m.delivery) {
                            ChatDelivery.DELIVERED -> act.getString(R.string.rmg_connect_chat_delivered)
                            ChatDelivery.QUEUED -> act.getString(R.string.rmg_connect_chat_queued)
                            ChatDelivery.SENT -> act.getString(R.string.rmg_connect_chat_sent)
                        }
                    )
                }
            }
            bubble.addView(act.text(statusStr, 10.5f, C.TEXT2).apply {
                gravity = Gravity.END
            }, lp().margins(t = act.dp(3)))

            row.addView(bubble, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            chatList.addView(row, lp().margins(b = act.dp(8)))
        }
        if (snap.messages.size != lastMessageCount) {
            lastMessageCount = snap.messages.size
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun submitChatMessage() {
        val txt = chatInput.text?.toString().orEmpty()
        if (mgr.sendChatMessage(txt)) {
            chatInput.setText("")
        }
    }

    // ==================== Tab 2: Shared GPS Locations, Distance, Bearing & Last Known ====================

    private fun renderLocationsTab(snap: RmgSessionSnapshot) {
        locationsContent.removeAllViews()
        val withLocation = snap.connectedPeers.filter { it.lastKnownLocation != null }
        if (withLocation.isEmpty()) {
            locationsContent.addView(emptyHintBox(act.getString(R.string.rmg_connect_loc_empty)))
            return
        }

        val myState = latestMyGps
        val hasMyFix = myState.location != null &&
            (myState.gpsStatus == GpsStatus.GPS_CONNECTED || myState.gpsStatus == GpsStatus.WEAK_ACCURACY)
        val nowMs = System.currentTimeMillis()

        for (peer in withLocation) {
            val loc = peer.lastKnownLocation ?: continue
            val card = act.vbox().apply {
                background = act.card(18)
                setPadding(act.dp(14), act.dp(13), act.dp(14), act.dp(13))
            }

            val head = act.hbox().apply { gravity = Gravity.CENTER_VERTICAL }
            val leftCol = act.vbox()
            leftCol.addView(act.text(peer.name, 16f, C.TEXT, Fonts.bold))

            val ageSec = ((nowMs - loc.timestampMs).coerceAtLeast(0L)) / 1000L
            val freshnessText = if (peer.isSharingLocation && peer.state == PeerLinkState.CONNECTED && ageSec < 12L) {
                act.getString(R.string.rmg_connect_loc_live_now)
            } else {
                act.getString(R.string.rmg_connect_loc_last_known_fmt, formatElapsedShort(ageSec))
            }
            val freshnessColor = if (peer.isSharingLocation && peer.state == PeerLinkState.CONNECTED) C.GREEN else C.TEXT2
            leftCol.addView(act.text(freshnessText, 12f, freshnessColor, Fonts.medium), lp().margins(t = act.dp(2)))
            head.addView(leftCol, lp(0, weight = 1f))

            var bearingForArrow: Float? = null
            if (hasMyFix) {
                val geo = GeoMath.between(myState.latitude, myState.longitude, loc.latitude, loc.longitude)
                val relativeDeg = if (myState.bearingLive) GeoMath.norm360(geo.initialBearing - myState.bearing) else geo.initialBearing
                bearingForArrow = relativeDeg

                val rightMetric = act.vbox().apply { gravity = Gravity.END }
                rightMetric.addView(act.text(act.units.distance(geo.distanceM), 17f, C.GOLD_DEEP, Fonts.bold))
                val brgInt = Math.round(GeoMath.norm360(geo.initialBearing)) % 360
                val dirLabel = compassLabel(CompassSector.indexFromBearing(geo.initialBearing))
                rightMetric.addView(act.text(act.getString(R.string.rmg_connect_loc_bearing_fmt, brgInt, dirLabel), 11.5f, C.TEXT2, Fonts.medium))
                head.addView(rightMetric, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(e = act.dp(8)))
            }

            if (bearingForArrow != null) {
                head.addView(MiniBearingDialView(act, bearingForArrow), LinearLayout.LayoutParams(act.dp(38), act.dp(38)))
            }
            card.addView(head, lp())

            val coordText = String.format(Locale.US, "%.6f, %.6f  •  %s", loc.latitude, loc.longitude, act.units.accuracy(loc.accuracyM))
            card.addView(act.text(coordText, 12.5f, C.TEXT2).apply {
                textDirection = View.TEXT_DIRECTION_LTR
            }, lp().margins(t = act.dp(8)))

            val saveBtn = miniActionPill(act.getString(R.string.rmg_connect_loc_save_dest), C.GOLD_DEEP, C.GOLD_PALE) {
                val id = act.app.destinations.newId()
                val dest = Destination(
                    id = id,
                    name = peer.name,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    notes = "RMG CONNECT",
                    createdAt = System.currentTimeMillis()
                )
                if (act.app.destinations.upsert(dest)) {
                    act.message.success(act.getString(R.string.msg_saved))
                } else {
                    act.message.error(act.getString(R.string.err_save))
                }
            }
            card.addView(saveBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = act.dp(10)))

            locationsContent.addView(card, lp().margins(b = act.dp(10)))
        }
    }

    // ==================== Dialogs & Permissions ====================

    private fun showEditDeviceNameDialog() {
        val input = act.input(act.getString(R.string.name)).apply {
            setText(mgr.localDisplayName)
        }
        NovaDialog(act)
            .title(act.getString(R.string.rmg_connect_device_name_title))
            .content(input)
            .button(act.getString(R.string.cancel), C.TEXT2) { it.dismiss() }
            .button(act.getString(R.string.save), filled = true) { dlg ->
                val v = input.text?.toString()?.trim().orEmpty()
                if (v.isNotEmpty()) {
                    mgr.setDisplayName(v)
                    dlg.dismiss()
                }
            }.show()
    }

    private fun showBlockedPeersDialog() {
        val blocked = latestSnapshot.blockedPeers
        val box = act.vbox()
        val dlg = NovaDialog(act).title(act.getString(R.string.rmg_connect_blocked_title))
        if (blocked.isEmpty()) {
            box.addView(act.text(act.getString(R.string.rmg_connect_blocked_empty), 14f, C.TEXT2))
        } else {
            for (b in blocked) {
                val row = act.hbox().apply {
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundRect(C.CARD_SOFT, act.dp(12).toFloat(), C.BORDER, act.dp(1))
                    setPadding(act.dp(12), act.dp(9), act.dp(12), act.dp(9))
                }
                row.addView(act.text(b.name, 14f, C.TEXT, Fonts.medium), lp(0, weight = 1f))
                row.addView(miniActionPill(act.getString(R.string.rmg_connect_unblock), C.GOLD_DEEP, C.GOLD_PALE) {
                    mgr.unblockPeer(b.key)
                    dlg.dismiss()
                })
                box.addView(row, lp().margins(b = act.dp(8)))
            }
        }
        dlg.content(box).button(act.getString(R.string.portal_done), filled = true) { it.dismiss() }.show()
    }

    private fun ensureNearbyPermissionsThen(onReady: () -> Unit) {
        val missing = RmgPermissions.missingNearbyPermissions(act)
        if (missing.isEmpty()) {
            onReady()
            return
        }
        act.requestPerms(missing.toTypedArray()) { allGranted ->
            if (allGranted || RmgPermissions.hasWifiDirectPermission(act) || RmgPermissions.hasBluetoothConnectPermission(act)) {
                onReady()
            } else {
                act.message.error(act.getString(R.string.rmg_connect_perm_nearby_denied))
            }
        }
    }

    private fun ensureAudioPermissionThen(onReady: () -> Unit) {
        if (RmgPermissions.hasRecordAudioPermission(act)) {
            onReady()
        } else {
            requestAudioPermission { granted ->
                if (granted) onReady()
                else act.message.error(act.getString(R.string.rmg_connect_perm_mic_denied))
            }
        }
    }

    private fun requestAudioPermission(cb: (Boolean) -> Unit) {
        act.requestPerm(Manifest.permission.RECORD_AUDIO, cb)
    }

    private fun ensureLocationPermissionThen(onReady: () -> Unit) {
        if (RmgPermissions.hasLocationPermission(act)) {
            onReady()
        } else {
            act.requestPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
                if (RmgPermissions.hasLocationPermission(act)) {
                    act.app.gps.refresh()
                    onReady()
                } else {
                    act.message.error(act.getString(R.string.rmg_connect_perm_loc_denied))
                }
            }
        }
    }

    private fun ensureNotificationPermissionSoft() {
        if (RmgPermissions.needsPostNotificationPrompt(act)) {
            act.requestPerm(Manifest.permission.POST_NOTIFICATIONS) { /* optional — FGS still runs on Android 13-15 */ }
        }
    }

    // ==================== Visual Micro-Components ====================

    private fun sectionHeader(title: String, color: Int): View =
        act.text(title, 12f, color, Fonts.bold).apply {
            letterSpacing = 0.04f
            setPadding(act.dp(2), act.dp(2), act.dp(2), act.dp(4))
        }

    private fun emptyHintBox(text: String): View = act.vbox().apply {
        gravity = Gravity.CENTER
        background = roundRect(C.CARD_SOFT, act.dp(18).toFloat(), C.BORDER, act.dp(1))
        setPadding(act.dp(20), act.dp(24), act.dp(20), act.dp(24))
        addView(act.text(text, 13.5f, C.TEXT2).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.28f)
        })
    }

    private fun miniActionPill(label: String, textColor: Int, bgColor: Int, onClick: () -> Unit): TextView =
        act.text(label, 12f, textColor, Fonts.bold).apply {
            gravity = Gravity.CENTER
            val r = act.dp(12).toFloat()
            background = ripple(roundRect(bgColor, r, C.BORDER, act.dp(1)), r)
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun transportName(t: TransportType): String = when (t) {
        TransportType.WIFI_DIRECT -> act.getString(R.string.rmg_connect_transport_wifi)
        TransportType.BLUETOOTH -> act.getString(R.string.rmg_connect_transport_bt)
    }

    private fun signalLabel(q: SignalQuality): String = when (q) {
        SignalQuality.EXCELLENT -> act.getString(R.string.rmg_connect_sig_excellent)
        SignalQuality.GOOD -> act.getString(R.string.rmg_connect_sig_good)
        SignalQuality.FAIR -> act.getString(R.string.rmg_connect_sig_fair)
        SignalQuality.WEAK -> act.getString(R.string.rmg_connect_sig_weak)
        SignalQuality.UNKNOWN -> act.getString(R.string.rmg_connect_sig_unknown)
    }

    private fun compassLabel(sector: Int): String = when (sector and 7) {
        0 -> act.getString(R.string.rmg_connect_dir_n)
        1 -> act.getString(R.string.rmg_connect_dir_ne)
        2 -> act.getString(R.string.rmg_connect_dir_e)
        3 -> act.getString(R.string.rmg_connect_dir_se)
        4 -> act.getString(R.string.rmg_connect_dir_s)
        5 -> act.getString(R.string.rmg_connect_dir_sw)
        6 -> act.getString(R.string.rmg_connect_dir_w)
        else -> act.getString(R.string.rmg_connect_dir_nw)
    }

    private fun formatElapsedShort(seconds: Long): String = when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60L}m"
        else -> "${seconds / 3600L}h"
    }

    /** 4-bar signal indicator using theme-aware palette colours. */
    private class SignalStrengthBarsView(ctx: android.content.Context, private val quality: SignalQuality) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val activeColor = when (quality) {
                SignalQuality.EXCELLENT -> C.GREEN
                SignalQuality.GOOD -> C.SIGNAL_BLUE
                SignalQuality.FAIR -> C.AMBER
                SignalQuality.WEAK -> C.RED
                SignalQuality.UNKNOWN -> C.SIGNAL_BLUE
            }
            val activeBars = if (quality == SignalQuality.UNKNOWN) 2 else quality.bars
            val barW = w / 6.2f
            val gap = barW * 0.65f
            for (i in 0..3) {
                val bh = h * (0.34f + i * 0.22f)
                val left = i * (barW + gap)
                paint.color = if (i < activeBars) activeColor else C.SIGNAL_TRACK
                canvas.drawRoundRect(left, h - bh, left + barW, h, barW / 2f, barW / 2f, paint)
            }
        }
    }

    /** Crisp mini direction arrow dial pointing toward a connected peer's shared GPS coordinate. */
    private class MiniBearingDialView(ctx: android.content.Context, private val angleDeg: Float) : View(ctx) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpf(1.4f)
            color = C.GOLD_LIGHT
        }
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = C.GOLD_PALE
        }
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = C.GOLD
        }
        private val arrowPath = Path()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(cx, cy) - dpf(2f)
            if (r <= 2f) return
            canvas.drawCircle(cx, cy, r, bgPaint)
            canvas.drawCircle(cx, cy, r, ringPaint)

            canvas.save()
            canvas.rotate(angleDeg, cx, cy)
            arrowPath.reset()
            arrowPath.moveTo(cx, cy - r * 0.64f)
            arrowPath.lineTo(cx + r * 0.44f, cy + r * 0.52f)
            arrowPath.lineTo(cx, cy + r * 0.24f)
            arrowPath.lineTo(cx - r * 0.44f, cy + r * 0.52f)
            arrowPath.close()
            canvas.drawPath(arrowPath, arrowPaint)
            canvas.restore()
        }
    }
}
