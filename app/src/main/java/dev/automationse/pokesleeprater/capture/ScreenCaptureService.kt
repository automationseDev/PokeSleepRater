package dev.automationse.pokesleeprater.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import dev.automationse.pokesleeprater.ui.LiveState
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "dev.automationse.pokesleeprater.START"
        const val ACTION_STOP = "dev.automationse.pokesleeprater.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 24
        private const val OCR_INTERVAL_MS = 2000L
    }
    private val main = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val processing = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var lastFrame = 0L
    private var overlay: TextView? = null
    private var stopped = false
    private var width = 0
    private var height = 0
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }

    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { main.post { finishCapture() } }
        override fun onCapturedContentResize(newWidth: Int, newHeight: Int) {
            main.post { resizeCapture(newWidth, newHeight) }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishCapture()
            ACTION_START -> {
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                    @Suppress("DEPRECATION")
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    if (code < 0 || data == null) {
                        finishCapture()
                    } else {
                        startForegroundCapture()
                        try { beginCapture(code, data) }
                        catch (_: Exception) {
                            LiveState.onError()
                            finishCapture()
                        }
                    }
                }
            }
            else -> finishCapture()
        }
        return START_NOT_STICKY
    }
    private fun startForegroundCapture() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "画面解析中", NotificationManager.IMPORTANCE_LOW,
        ))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PokeSleep Rater")
            .setContentText("画面の評価中 · 停止するにはアプリへ戻るか共有を停止")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
    private fun beginCapture(code: Int, data: Intent) {
        val metrics = windowManager.maximumWindowMetrics.bounds
        width = metrics.width()
        height = metrics.height()
        thread = HandlerThread("PokeSleepFrameReader").also { it.start() }
        worker = Handler(thread!!.looper)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val currentProjection = manager.getMediaProjection(code, data)
        projection = currentProjection
        currentProjection.registerCallback(callback, main)
        val currentReader = makeReader(width, height)
        reader = currentReader
        // Android 14+: 権限トークン1つにつきcreateVirtualDisplayは1回のみ
        display = currentProjection.createVirtualDisplay(
            "PokeSleep-Rater-Analysis", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            currentReader.surface, null, worker,
        )
        LiveState.setCapturing(true)
        addOverlayIfAllowed()
    }
    private fun makeReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also {
            it.setOnImageAvailableListener({ source -> onFrame(source) }, worker)
        }
    private fun resizeCapture(w: Int, h: Int) {
        if (stopped || w <= 0 || h <= 0 || (w == width && h == height)) return
        width = w
        height = h
        val newReader = makeReader(w, h)
        val oldReader = reader
        reader = newReader
        display?.resize(w, h, resources.configuration.densityDpi)
        display?.surface = newReader.surface
        oldReader?.setOnImageAvailableListener(null, null)
        oldReader?.close()
    }
    private fun onFrame(source: ImageReader) {
        val image: Image = try { source.acquireLatestImage() ?: return }
        catch (_: IllegalStateException) { return }
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrame < OCR_INTERVAL_MS || !processing.compareAndSet(false, true)) return
            lastFrame = now
            val frame = bitmapFrom(image)
            recognizer.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener { result ->
                    LiveState.onRecognizedText(result.text)
                    updateOverlay()
                }
                .addOnFailureListener { LiveState.onError() }
                .addOnCompleteListener {
                    frame.recycle()
                    processing.set(false)
                }
        } catch (_: Exception) {
            processing.set(false)
            main.post { LiveState.onError() }
        } finally {
            image.close()
        }
    }
    private fun bitmapFrom(image: Image): Bitmap {
        val plane = image.planes[0]
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }
    private fun addOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this)) return
        val text = TextView(this).apply {
            this.text = "PokeSleep Rater · 読み取り中"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(18, 12, 18, 12)
            background = GradientDrawable().apply {
                setColor(Color.argb(220, 19, 27, 40))
                cornerRadius = 18f
            }
            maxWidth = (resources.displayMetrics.widthPixels * .72).toInt()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 12
            y = 84
        }
        try {
            windowManager.addView(text, params)
            overlay = text
        } catch (_: Exception) { /* 権限なしでも解析は可能 */ }
    }
    private fun updateOverlay() {
        val status = LiveState.report.oneLine
        val hint = LiveState.report.details.firstOrNull() ?: "画面を読み取っています"
        overlay?.text = "PokeSleep Rater  ${LiveState.role.label}\n$status\n$hint"
    }
    private fun finishCapture() {
        if (stopped) return
        stopped = true
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        display = null
        reader?.close()
        reader = null
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        projection?.let { current ->
            current.unregisterCallback(callback)
            current.stop()
        }
        projection = null
        thread?.quitSafely()
        thread = null
        LiveState.setCapturing(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        finishCapture()
        recognizer.close()
        super.onDestroy()
    }
}
