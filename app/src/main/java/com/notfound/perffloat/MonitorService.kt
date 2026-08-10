package com.notfound.perffloat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.notfound.perffloat.data.MetricsReader

/**
 * 前台服务：持有悬浮窗，每秒刷新一次性能数据。
 * 前台服务保证进程常驻，避免浮窗被系统回收。
 */
class MonitorService : Service() {

    private val metricsReader by lazy { MetricsReader(applicationContext) }
    private val overlay by lazy { OverlayManager(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (overlay.isShowing()) {
                overlay.update(metricsReader.read())
            }
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        overlay.show()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay.hide()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "性能监控", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "perf_monitor"
        private const val NOTIFICATION_ID = 1
        private const val REFRESH_INTERVAL_MS = 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
