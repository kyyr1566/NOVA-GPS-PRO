package com.nova.gpspro.portal

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.google.zxing.PlanarYUVLuminanceSource
import com.nova.gpspro.MainActivity
import com.nova.gpspro.R
import com.nova.gpspro.ui.C
import com.nova.gpspro.ui.Fonts
import com.nova.gpspro.ui.dp
import com.nova.gpspro.ui.roundRect
import com.nova.gpspro.ui.text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen live camera QR scanner (android.hardware.Camera preview frames → ZXing, fully offline).
 * The legacy Camera API is used because the app has no AndroidX/CameraX dependency; it is still
 * supported on Android 11–16.
 */
@Suppress("DEPRECATION")
class QrScanner(private val act: MainActivity, private val onText: (String) -> Unit) : TextureView.SurfaceTextureListener {
    private val dialog = Dialog(act, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
    private val texture = TextureView(act)
    private var camera: Camera? = null
    private var pw = 0; private var ph = 0
    private val worker = HandlerThread("qr-decode").apply { start() }
    private val workerH = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    @Volatile private var done = false

    fun show() {
        val root = FrameLayout(act).apply { setBackgroundColor(0xFF0E1A33.toInt()) }
        root.addView(texture, FrameLayout.LayoutParams(-1, -1))
        root.addView(Overlay(act), FrameLayout.LayoutParams(-1, -1))
        root.addView(act.text(act.getString(R.string.portal_scan_hint), 15f, 0xFFFFFFFF.toInt(), Fonts.medium).apply {
            gravity = Gravity.CENTER; setPadding(act.dp(24), 0, act.dp(24), 0)
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = act.dp(72) })
        root.addView(act.text(act.getString(R.string.cancel), 16f, C.GOLD_DEEP, Fonts.medium).apply {
            gravity = Gravity.CENTER; background = roundRect(0xFFFFFFFF.toInt(), act.dp(24).toFloat())
            setPadding(act.dp(36), act.dp(12), act.dp(36), act.dp(12))
            setOnClickListener { dialog.dismiss() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dp(56) })
        texture.surfaceTextureListener = this
        dialog.setContentView(root)
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.setOnDismissListener { release(); worker.quitSafely() }
        dialog.show()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        try {
            val id = (0 until Camera.getNumberOfCameras()).firstOrNull { i ->
                Camera.CameraInfo().also { Camera.getCameraInfo(i, it) }.facing == Camera.CameraInfo.CAMERA_FACING_BACK } ?: 0
            val cam = Camera.open(id); camera = cam
            val info = Camera.CameraInfo().also { Camera.getCameraInfo(id, it) }
            val p = cam.parameters
            val size = p.supportedPreviewSizes.minByOrNull { abs(it.width * it.height - 1280 * 720) + abs(it.width * 9 - it.height * 16) }!!
            p.setPreviewSize(size.width, size.height); pw = size.width; ph = size.height
            val modes = p.supportedFocusModes
            when {
                Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
                Camera.Parameters.FOCUS_MODE_AUTO in modes -> p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
            }
            cam.parameters = p
            val rot = when (act.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val orient = (info.orientation - rot + 360) % 360
            cam.setDisplayOrientation(orient)
            applyCenterCrop(w, h, orient)
            cam.setPreviewTexture(st)
            val buf = pw * ph * 3 / 2
            repeat(2) { cam.addCallbackBuffer(ByteArray(buf)) }
            cam.setPreviewCallbackWithBuffer { data, c -> onFrame(data, c) }
            cam.startPreview()
        } catch (_: Exception) {
            release(); dialog.dismiss(); act.message.error(act.getString(R.string.portal_camera_error))
        }
    }

    private fun applyCenterCrop(w: Int, h: Int, orient: Int) {
        val bw = if (orient % 180 == 0) pw else ph
        val bh = if (orient % 180 == 0) ph else pw
        val scale = max(w.toFloat() / bw, h.toFloat() / bh)
        texture.setTransform(Matrix().apply { setScale(bw * scale / w, bh * scale / h, w / 2f, h / 2f) })
    }

    private fun onFrame(data: ByteArray?, cam: Camera) {
        if (data == null || done) return
        if (busy) { cam.addCallbackBuffer(data); return }
        busy = true
        val w = pw; val h = ph
        workerH.post {
            // centre crop (the framed area) for speed; QR detection is rotation-invariant
            val side = (min(w, h) * 0.85f).toInt()
            val text = try { QrCodec.decode(PlanarYUVLuminanceSource(data, w, h, (w - side) / 2, (h - side) / 2, side, side, false)) }
                       catch (_: Exception) { null }
                ?: try { QrCodec.decode(PlanarYUVLuminanceSource(data, w, h, 0, 0, w, h, false)) } catch (_: Exception) { null }
            main.post {
                busy = false
                if (text != null && !done) { done = true; dialog.dismiss(); onText(text) }
                else if (!done) runCatching { camera?.addCallbackBuffer(data) }
            }
        }
    }

    private fun release() {
        done = true
        camera?.let { runCatching { it.setPreviewCallbackWithBuffer(null); it.stopPreview(); it.release() } }
        camera = null
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Dimmed surround with a rounded royal-blue scan frame. */
    private class Overlay(ctx: Context) : View(ctx) {
        private val dim = Paint().apply { color = 0x880E1A33.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = C.ACCENT.toInt(); strokeWidth = ctx.dp(4).toFloat() }
        private val r = RectF(); private val path = Path()
        override fun onDraw(c: Canvas) {
            val side = min(width, height) * 0.72f
            r.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
            val rad = dp(28).toFloat()
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.addRoundRect(r, rad, rad, Path.Direction.CW)
            c.drawPath(path, dim); c.drawRoundRect(r, rad, rad, frame)
        }
    }
}
