package com.notfound.perffloat.data

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File
import java.io.IOException

/**
 * 读取整机性能数据：
 * - CPU 负载（整机 + 每核）：连续两次读取 /proc/stat 快照计算；
 *   Android 7+ 普通应用读 /proc/stat 会被 SELinux 拒绝（EACCES），
 *   此时自动降级为 /proc/uptime 估算，再不行则用 /proc/loadavg 指数。
 * - 内存：/proc/meminfo，受限时用 ActivityManager.MemoryInfo 公开 API 兜底
 * - 温度：系统粘性广播 ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE（0.1°C），sysfs thermal 兜底
 * - 电量：同一粘性广播的 level/scale，BatteryManager 属性兜底
 *
 * 构造时立即建立一次 CPU 基线，保证首次 read() 就能返回真实负载而非 0。
 */
class MetricsReader(context: Context) {

    private val appContext = context.applicationContext

    private val batteryManager: BatteryManager? =
        appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var prevSnapshot: CpuSnapshot? = null

    /** 已确认 /proc/stat 被系统策略拒绝，之后不再每秒重复尝试（避免异常开销）。 */
    private var statBlocked = false

    /** /proc/uptime 上一次采样 (uptime, idle)，用于估算整机占用率。 */
    private var prevUptime: Pair<Double, Double>? = null

    /** 最近一次采集失败的根因，供界面诊断展示。 */
    @Volatile
    var lastError: String? = null
        private set

    init {
        prevSnapshot = readSnapshot()
        prevUptime = readUptime()
    }

    private data class CpuSnapshot(
        val total: ProcParser.CpuTimes,
        val perCore: List<ProcParser.CpuTimes>,
    )

    fun read(): SystemMetrics {
        val snapshot = readSnapshot()
        var cpuLoad = 0f
        var perCoreLoads: List<Float> = emptyList()
        var cpuSource: CpuSource
        if (snapshot != null) {
            val prev = prevSnapshot
            if (prev != null) {
                cpuLoad = ProcParser.cpuLoadPercent(prev.total, snapshot.total)
                if (prev.perCore.size == snapshot.perCore.size && snapshot.perCore.isNotEmpty()) {
                    perCoreLoads = snapshot.perCore.indices.map { i ->
                        ProcParser.cpuLoadPercent(prev.perCore[i], snapshot.perCore[i])
                    }
                }
            }
            prevSnapshot = snapshot
            cpuSource = CpuSource.STAT
        } else {
            val uptimeLoad = readCpuBusyFromUptime()
            if (uptimeLoad != null) {
                cpuLoad = uptimeLoad
                cpuSource = CpuSource.UPTIME
            } else {
                cpuSource = CpuSource.LOAD_AVG
            }
        }

        val (usedPercent, usedMb, totalMb) = readMemory()
        return SystemMetrics(
            cpuLoadPercent = cpuLoad,
            memUsedPercent = usedPercent,
            memUsedMb = usedMb,
            memTotalMb = totalMb,
            tempCelsius = readTemperature(),
            batteryPercent = readBattery(),
            perCpuLoads = perCoreLoads,
            loadAvg = readLoadAvg(),
            cpuSource = cpuSource,
        )
    }

    private fun readSnapshot(): CpuSnapshot? {
        if (statBlocked) return null
        return runCatching {
            val lines = File("/proc/stat").readLines()
            val totalLine = lines.firstOrNull { it.startsWith("cpu ") }
                ?: error("/proc/stat 中没有 cpu 总行")
            val total = ProcParser.parseCpuTimes(totalLine)
                ?: error("cpu 行格式异常: $totalLine")
            val perCore = ProcParser.parsePerCoreCpuTimes(lines.joinToString("\n"))
            CpuSnapshot(total, perCore)
        }.onFailure { e ->
            lastError = "CPU 读取失败: ${e.message}"
            // EACCES 是 SELinux 的确定性拒绝，缓存后不再重试
            if (e is IOException && e.message?.contains("EACCES") == true) {
                statBlocked = true
            }
        }.getOrNull()
    }

    private fun readUptime(): Pair<Double, Double>? {
        return runCatching {
            ProcParser.parseUptime(File("/proc/uptime").readText())
        }.getOrNull()
    }

    /**
     * /proc/uptime 兜底：按两次采样的 idle 增量估算整机占用率（0-100）。
     * 注意这是近似值：idle 为所有核心累计空闲秒数，需除以核心数换算，
     * 与 /proc/stat 的 iowait 归属略有差异。
     */
    private fun readCpuBusyFromUptime(): Float? {
        val curr = readUptime() ?: return null
        val prev = prevUptime
        prevUptime = curr
        if (prev == null) return null
        val elapsed = curr.first - prev.first
        val idleDelta = curr.second - prev.second
        if (elapsed <= 0.05) return null
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val idleFraction = (idleDelta / (elapsed * cores)).toFloat()
        return (1f - idleFraction).coerceIn(0f, 1f) * 100f
    }

    private fun readMemory(): Triple<Float, Long, Long> {
        val content = runCatching { File("/proc/meminfo").readText() }.getOrNull()
        val parsed = content?.let { ProcParser.parseMemInfo(it) }
        if (parsed == null) return readMemoryViaActivityManager()
        val (totalKb, availKb) = parsed
        val usedKb = totalKb - availKb
        val totalMb = totalKb / 1024
        val usedMb = usedKb / 1024
        val percent = if (totalKb > 0) (usedKb.toFloat() / totalKb * 100f).coerceIn(0f, 100f) else 0f
        return Triple(percent, usedMb, totalMb)
    }

    /** /proc/meminfo 受限时的公开 API 兜底，无需任何权限。 */
    private fun readMemoryViaActivityManager(): Triple<Float, Long, Long> {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return Triple(0f, 0L, 0L)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return Triple(0f, 0L, 0L)
        val totalMb = info.totalMem / 1024 / 1024
        val usedMb = (info.totalMem - info.availMem) / 1024 / 1024
        val percent = if (totalMb > 0) (usedMb.toFloat() / totalMb * 100f).coerceIn(0f, 100f) else 0f
        return Triple(percent, usedMb, totalMb)
    }

    /**
     * 温度读取策略：
     * 1. 系统粘性广播 ACTION_BATTERY_CHANGED 的 EXTRA_TEMPERATURE
     *    - 官方单位为十分之一摄氏度（如 350 = 35.0°C），API 1 起可用、无需权限
     *    - 注意：BatteryManager.BATTERY_PROPERTY_TEMPERATURE 的常量值是 7（API 28+），
     *      不是 4；4 是 BATTERY_PROPERTY_CAPACITY（电量百分比），
     *      旧代码用 getIntProperty(4) 会把电量当成温度显示，必须修正。
     * 2. 兜底 sysfs thermal zone —— 部分设备普通应用无权限读取（会得到 0）
     */
    private fun readTemperature(): Float {
        val raw = readBatteryState()
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (raw != Int.MIN_VALUE) {
            val celsius = raw / 10f
            if (celsius in 1f..120f) return celsius
        }
        return readThermalZoneTemp()
    }

    private fun readThermalZoneTemp(): Float {
        val base = File("/sys/class/thermal")
        if (!base.exists()) return 0f
        val zones = base.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return 0f

        var best = 0f
        var bestPriority = Int.MAX_VALUE
        for (zone in zones) {
            val type = runCatching { File(zone, "type").readText().trim() }.getOrNull() ?: continue
            val raw = runCatching { File(zone, "temp").readText() }.getOrNull() ?: continue
            val celsius = ProcParser.parseThermalTemp(raw) ?: continue

            val priority = when {
                type.contains("cpu", ignoreCase = true) -> 0
                type.contains("gpu", ignoreCase = true) -> 1
                type.contains("tsens", ignoreCase = true) || type.contains("soc", ignoreCase = true) -> 2
                else -> 3
            }
            if (priority < bestPriority) {
                bestPriority = priority
                best = celsius
            } else if (priority == bestPriority && celsius > best) {
                best = celsius
            }
        }
        return best
    }

    /** 获取系统最新的电池状态粘性广播（无需注册接收器、无需权限）。 */
    private fun readBatteryState(): Intent? {
        return appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    private fun readBattery(): Int {
        val intent = readBatteryState()
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level != null && level >= 0 && scale != null && scale > 0) {
            return (level * 100f / scale).toInt().coerceIn(0, 100)
        }
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    /** /proc/loadavg 的 1 分钟负载指数，作为 CPU 百分比不可用时的兜底指标。 */
    private fun readLoadAvg(): Float {
        return runCatching {
            ProcParser.parseLoadAvg(File("/proc/loadavg").readText()) ?: 0f
        }.getOrDefault(0f)
    }
}
