package com.notfound.perffloat

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 识别当前前台应用。
 * 依赖「使用情况访问权限」（Usage Access），未授权时 hasPermission() 返回 false。
 */
class ForegroundAppTracker(private val context: Context) {

    private val usm: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    fun hasPermission(): Boolean {
        val usm = usm ?: return false
        return runCatching {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, 0L, System.currentTimeMillis())
            !stats.isNullOrEmpty()
        }.getOrDefault(false)
    }

    /** 返回最近一次切到前台的应用包名，找不到返回 null。 */
    fun foregroundPackage(): String? {
        val usm = usm ?: return null
        val end = System.currentTimeMillis()
        return runCatching {
            val events = usm.queryEvents(end - 60_000, end)
            val event = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = event.packageName
                }
            }
            last
        }.getOrNull()
    }

    /** 解析包名对应的应用名称与图标，失败时返回包名本身。 */
    fun resolveApp(pkg: String): Pair<String, Drawable?> {
        val pm = context.packageManager
        return runCatching {
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            if (info.icon != 0) {
                val icon = pm.getApplicationIcon(info)
                label to icon
            } else {
                label to null
            }
        }.getOrDefault(pkg to null)
    }
}
