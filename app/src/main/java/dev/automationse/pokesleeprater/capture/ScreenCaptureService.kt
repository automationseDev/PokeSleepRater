package dev.automationse.pokesleeprater.capture

import android.app.Activity
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
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
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
    private var overlay: View? = null
    private var overlayToggle: TextView? = null
    private var overlaySummary: TextView? = null
    private var overlayPanel: LinearLayout? = null
    private var showPrevious = false
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
    override fun onCreate() {
        super.onCreate()
        LiveState.initialize(applicationContext)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> finishCapture()
            ACTION_START -> {
                if (projection == null) {
                    val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    @Suppress("DEPRECATION")
                    val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                    } else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    // Activity.RESULT_OK is -1. A negative-value check rejected every success.
                    if (code != Activity.RESULT_OK || data == null) {
                        LiveState.onError("画面共有の許可データを受信できませんでした")
                        finishCapture()
                    } else {
                        startForegroundCapture()
                        try { beginCapture(code, data) }
                        catch (e: Exception) {
                            LiveState.onError("画面解析の開始に失敗: ${e.javaClass.simpleName}: ${e.message ?: "詳細不明"}")
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
        LiveState.updateCaptureState(true)
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
                .addOnFailureListener { e -> LiveState.onError("OCR失敗: ${e.javaClass.simpleName}: ${e.message ?: "詳細不明"}") }
                .addOnCompleteListener {
                    frame.recycle()
                    processing.set(false)
                }
        } catch (e: Exception) {
            processing.set(false)
            main.post { LiveState.onError("画像取得失敗: ${e.javaClass.simpleName}: ${e.message ?: "詳細不明"}") }
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
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** A touchable, compact bubble; controls appear only on tap. */
    private fun addOverlayIfAllowed() {
        if (!Settings.canDrawOverlays(this)) return
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val bubble = TextView(this).apply {
            text = "◉"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(15), dp(11), dp(15), dp(11))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 30, 70, 119))
                cornerRadius = dp(28).toFloat()
            }
            contentDescription = "PokeSleep Raterを開く"
        }
        root.addView(bubble)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(246, 21, 31, 48))
                cornerRadius = dp(15).toFloat()
            }
        }
        val panelParams = LinearLayout.LayoutParams(dp(246), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        }
        root.addView(panel, panelParams)

        val summary = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        }
        panel.addView(summary)

        fun action(label: String, click: () -> Unit) {
            val button = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { click() }
            }
            panel.addView(button, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        action("現在の評価を見る") {
            showPrevious = false
            updateOverlay()
        }
        action("保存して次のポケモン") {
            LiveState.saveAndNext()
            showPrevious = false
            updateOverlay()
        }
        action("前回の評価を見る") {
            showPrevious = true
            updateOverlay()
        }
        action("同種・同条件でも再開") {
            LiveState.startAnyway()
            showPrevious = false
            updateOverlay()
        }
        action("閉じる") {
            panel.visibility = View.GONE
            bubble.contentDescription = "PokeSleep Raterを開く"
        }
        bubble.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            bubble.contentDescription = if (panel.visibility == View.VISIBLE) "評価パネルを閉じる"
                else "PokeSleep Raterを開く"
            updateOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(100)
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var touchX = 0f
        var touchY = 0f
        var originX = 0
        var originY = 0
        var dragging = false
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchX
                    val deltaY = event.rawY - touchY
                    if (!dragging && (kotlin.math.abs(deltaX) > touchSlop ||
                            kotlin.math.abs(deltaY) > touchSlop)) dragging = true
                    if (dragging) {
                        val maxX = (resources.displayMetrics.widthPixels - dp(42)).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - dp(65)).coerceAtLeast(0)
                        params.x = (originX - deltaX).toInt().coerceIn(0, maxX)
                        params.y = (originY + deltaY).toInt().coerceIn(0, maxY)
                        if (overlay != null) {
                            runCatching { windowManager.updateViewLayout(root, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) bubble.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try {
            windowManager.addView(root, params)
            overlay = root
            overlayToggle = bubble
            overlayPanel = panel
            overlaySummary = summary
            updateOverlay()
        } catch (e: Exception) {
            LiveState.onError("フローティング表示の作成に失敗: ${e.message ?: "不明"}")
        }
    }

    private fun updateOverlay() {
        val name = LiveState.pokemonName ?: "名前を解析中"
        val grade = LiveState.report.grade
        overlayToggle?.text = if (LiveState.report.score != null) "◉ $grade" else "◉"
        if (showPrevious) {
            val last = LiveState.lastFinished()
            overlaySummary?.text = if (last == null) {
                "保存済みの評価はまだありません"
            } else {
                "前回の評価\n${last.pokemonName}\n${last.grade} ${last.score}点 / 100\n" +
                    "${last.nature ?: "性格未取得"} / ${last.role.label}得意\n" +
                    "${last.slots.joinToString("、") { it.name }.take(130)}"
            }
        } else {
            overlaySummary?.text = "$name / ${LiveState.role.label}得意" +
                if (LiveState.report.score == null) "\n評価待ち\n${LiveState.status.take(75)}"
                else "\n${LiveState.report.oneLine}\n${LiveState.report.progress.take(90)}"
        }
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
        overlayToggle = null
        overlaySummary = null
        overlayPanel = null
        projection?.let { current ->
            current.unregisterCallback(callback)
            current.stop()
        }
        projection = null
        thread?.quitSafely()
        thread = null
        LiveState.updateCaptureState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        finishCapture()
        recognizer.close()
        super.onDestroy()
    }
}
