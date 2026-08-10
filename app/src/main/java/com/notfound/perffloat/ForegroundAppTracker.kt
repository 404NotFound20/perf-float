package com.notfound.perffloat

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/** 一个最近运行的应用条目 */
data class RecentApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val lastUsedMillis: Long,
)

/**
 * 基于「使用情况访问权限」识别最近运行的应用。
 * 未授权时 hasPermission() 返回 false。
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

    /**
     * 返回最近 [sinceMillis] 毫秒内有使用记录的应用，按最后使用时间降序，最多 [limit] 个。
     * 排除本应用自身。
     */
    fun recentApps(sinceMillis: Long, limit: Int): List<RecentApp> {
        val usm = usm ?: return emptyList()
        val now = System.currentTimeMillis()
        return runCatching {
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_BEST, now - sinceMillis, now)
                ?.asSequence()
                ?.filter { it.packageName != context.packageName && it.lastTimeUsed > now - sinceMillis }
                ?.sortedByDescending { it.lastTimeUsed }
                ?.take(limit)
                ?.toList()
                ?: return emptyList()
            stats.map { st ->
                val (label, icon) = resolveApp(st.packageName)
                RecentApp(st.packageName, label, icon, st.lastTimeUsed)
            }
        }.getOrDefault(emptyList())
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
