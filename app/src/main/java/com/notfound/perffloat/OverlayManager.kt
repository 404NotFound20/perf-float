package com.notfound.perffloat

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.notfound.perffloat.data.SystemMetrics
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 悬浮窗管理：创建/更新/移除顶层监控药丸，支持拖动。
 */
class OverlayManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var downX = 0
    private var downY = 0
    private var startX = 0
    private var startY = 0
    private var dragging = false

    fun isShowing(): Boolean = overlayView != null

    fun show() {
        if (isShowing()) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setText("监控启动中…")
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE6111111.toInt())
            }
            setOnTouchListener { _, event -> handleTouch(event) }
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(36)
        }

        try {
            wm.addView(view, lp)
            windowManager = wm
            overlayView = view
            params = lp
        } catch (e: Exception) {
            // 权限未授予或其他系统限制，直接失败静默处理
            overlayView = null
        }
    }

    fun update(metrics: SystemMetrics) {
        val view = overlayView ?: return
        val cpuText = when {
            metrics.cpuLoadPercent > 0f -> "CPU ${metrics.cpuLoadPercent.roundToInt()}%"
            metrics.loadAvg > 0f -> "LD " + String.format(Locale.US, "%.1f", metrics.loadAvg)
            else -> "CPU --"
        }
        val text = if (metrics.failed) {
            "数据不可用"
        } else {
            String.format(
                Locale.US,
                "%s  MEM %d/%dG  %.0f°C  %d%%",
                cpuText,
                metrics.memUsedMb / 1024,
                (metrics.memTotalMb + 1023) / 1024,
                metrics.tempCelsius,
                metrics.batteryPercent,
            )
        }
        view.text = text
    }

    fun hide() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        try {
            wm.removeView(view)
        } catch (_: Exception) {
        } finally {
            windowManager = null
            overlayView = null
            params = null
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        val view = overlayView ?: return false
        val lp = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX.toInt()
                downY = event.rawY.toInt()
                startX = lp.x
                startY = lp.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX.toInt() - downX
                val dy = event.rawY.toInt() - downY
                if (!dragging && (kotlin.math.abs(dx) > 6 || kotlin.math.abs(dy) > 6)) {
                    dragging = true
                }
                if (dragging) {
                    lp.x = startX + dx
                    lp.y = startY + dy
                    runCatching { windowManager?.updateViewLayout(view, lp) }
                }
            }
        }
        return true
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
