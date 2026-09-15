package com.aiyerbeyer.aicontext

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * حباب شناور با دو مقصد مجزا:
 *   👤  = ثبت متن کپی‌شده به‌عنوان «سوال»
 *   🤖  = ثبت متن کپی‌شده به‌عنوان «پاسخ»
 * دیگر مفهوم «فاز» و سوییچ دستی وجود ندارد؛ هر متن مستقیم روی دکمه‌ی مقصدش ثبت می‌شود.
 * دکمه‌ی ⋯ یک ردیف کمکی (لغو / پایان) را باز و بسته می‌کند.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val ACTION_FINISH = "FINISH"
        private const val CHANNEL_ID = "overlay_capture_channel"
        private const val NOTIF_ID = 1001
        private const val MOVE_THRESHOLD_PX = 15
        var instance: OverlayService? = null
    }

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var btnPrompt: TextView? = null
    private var btnResponse: TextView? = null
    private var actionsRow: LinearLayout? = null

    // کمکی‌های تشخیص «تپ ساده» در برابر «درگ»
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                removeBubble()
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINISH -> {
                // از دکمه‌ی داخل نوتیفیکیشن؛ جاوااسکریپت خودش بعداً stopCapture را صدا می‌زند
                OverlayPlugin.instance?.emitSimple("finishRequested")
            }
            else -> {
                startForegroundNotification()
                showBubble()
            }
        }
        return START_STICKY
    }

    // ---------------------------------------------------------------- نوتیفیکیشن

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "ضبط کلیپ‌برد", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val finishIntent = Intent(this, OverlayService::class.java).apply { action = ACTION_FINISH }
        val finishPending = PendingIntent.getService(
            this, 2, finishIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ضبط کلیپ‌برد فعال است")
            .setContentText("متن را کپی کن، بعد روی 👤 یا 🤖 بزن")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .addAction(0, "🏁 پایان ضبط", finishPending)
            .build()
        startForeground(NOTIF_ID, notification)
    }

    // ---------------------------------------------------------------- ساخت حباب

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(20).toFloat()
    }

    private fun makeButton(txt: String, color: Int, onTap: () -> Unit): TextView {
        val tv = TextView(this).apply {
            text = txt
            setTextColor(Color.WHITE)
            background = pill(color)
            setPadding(dp(13), dp(9), dp(13), dp(9))
            textSize = 13f
            isFocusable = true
            isFocusableInTouchMode = true
        }
        tv.setOnTouchListener(makeTouchListener(onTap))
        return tv
    }

    private fun itemParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(6) }

    /**
     * یک لمس‌گر مشترک: درگِ کل پنجره از روی هر دکمه‌ای کار می‌کند،
     * و اگر انگشت تکان نخورده باشد، همان دکمه به‌عنوان «تپ» عمل می‌کند.
     */
    private fun makeTouchListener(onTap: () -> Unit) = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = params.x
                initialY = params.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                moved = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (abs(dx) > MOVE_THRESHOLD_PX || abs(dy) > MOVE_THRESHOLD_PX) {
                    moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved) onTap()
                true
            }
            else -> false
        }
    }

    private fun showBubble() {
        if (rootView != null) return

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val p = makeButton("👤 ۱", Color.parseColor("#3b82f6")) { onTapCapture("prompt") }
        val r = makeButton("🤖 ۱", Color.parseColor("#22c55e")) { onTapCapture("response") }
        val more = makeButton("⋯", Color.parseColor("#475569")) { toggleActions() }
        btnPrompt = p
        btnResponse = r
        row1.addView(p, itemParams())
        row1.addView(r, itemParams())
        row1.addView(more, itemParams())

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val undo = makeButton("↩️ لغو", Color.parseColor("#f59e0b")) {
            OverlayPlugin.instance?.emitSimple("undoRequested")
            toggleActions()
        }
        val finish = makeButton("🏁 پایان", Color.parseColor("#ef4444")) {
            OverlayPlugin.instance?.emitSimple("finishRequested")
            toggleActions()
        }
        row2.addView(undo, itemParams())
        row2.addView(finish, itemParams())
        actionsRow = row2

        root.addView(row1)
        root.addView(
            row2,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )
        rootView = root

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        windowManager.addView(root, params)
    }

    private fun toggleActions() {
        val row = actionsRow ?: return
        row.visibility = if (row.visibility == View.GONE) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- خواندن کلیپ‌برد

    /**
     * نکته‌ی فنی مهم: از اندروید ۱۰ به بعد، یک پنجره فقط وقتی می‌تواند کلیپ‌برد را بخواند
     * که در آن لحظه «فوکوس پنجره» را داشته باشد. چون حباب شناور به‌طور پیش‌فرض
     * FLAG_NOT_FOCUSABLE دارد (تا مزاحم تایپ در اپ‌های دیگر نشود)، دقیقاً در لحظه‌ی تپ
     * این فلگ را لحظه‌ای برمی‌داریم، فوکوس می‌گیریم، کلیپ‌برد را می‌خوانیم، و بلافاصله برمی‌گردانیم.
     */
    private fun onTapCapture(target: String) {
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        rootView?.let {
            windowManager.updateViewLayout(it, params)
            it.requestFocus()
        }

        handler.postDelayed({
            readClipboardAndEmit(target)
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            rootView?.let { windowManager.updateViewLayout(it, params) }
        }, 100)
    }

    private fun readClipboardAndEmit(target: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString() ?: ""
        } else {
            ""
        }
        OverlayPlugin.instance?.emitClipboardCaptured(text, target)
    }

    // ---------------------------------------------------------------- فراخوانی از جاوااسکریپت

    fun updateLabels(promptText: String, responseText: String) {
        handler.post {
            btnPrompt?.text = promptText
            btnResponse?.text = responseText
        }
    }

    fun showToast(text: String) {
        handler.post {
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- پاک‌سازی

    private fun removeBubble() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
        btnPrompt = null
        btnResponse = null
        actionsRow = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeBubble()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
