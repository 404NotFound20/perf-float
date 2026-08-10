package com.notfound.perffloat.data

import android.content.Context
import android.os.BatteryManager
import java.io.File

/**
 * 读取整机性能数据：
 * - CPU 负载（整机 + 每核）：连续两次读取 /proc/stat 快照计算
 * - 内存：/proc/meminfo
 * - 温度：优先 BatteryManager 电池温度（普通应用可读），sysfs thermal 兜底
 * - 电量：BatteryManager
 *
 * 构造时立即建立一次 CPU 基线，保证首次 read() 就能返回真实负载而非 0。
 */
class MetricsReader(context: Context) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private var prevSnapshot: CpuSnapshot? = runCatching { readSnapshot() }.getOrNull()

    private data class CpuSnapshot(
        val total: ProcParser.CpuTimes,
        val perCore: List<ProcParser.CpuTimes>,
    )

    fun read(): SystemMetrics {
        val snapshot = runCatching { readSnapshot() }.getOrNull()
        var cpuLoad = 0f
        var perCoreLoads: List<Float> = emptyList()
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
        )
    }

    private fun readSnapshot(): CpuSnapshot? {
        val content = runCatching { File("/proc/stat").readText() }.getOrNull() ?: return null
        val totalLine = content.lines().firstOrNull { it.trim().startsWith("cpu ") } ?: return null
        val total = ProcParser.parseCpuTimes(totalLine) ?: return null
        val perCore = ProcParser.parsePerCoreCpuTimes(content)
        return CpuSnapshot(total, perCore)
    }

    private fun readMemory(): Triple<Float, Long, Long> {
        val content = runCatching { File("/proc/meminfo").readText() }.getOrNull() ?: return Triple(0f, 0L, 0L)
        val (totalKb, availKb) = ProcParser.parseMemInfo(content) ?: return Triple(0f, 0L, 0L)
        val usedKb = totalKb - availKb
        val totalMb = totalKb / 1024
        val usedMb = usedKb / 1024
        val percent = if (totalKb > 0) (usedKb.toFloat() / totalKb * 100f).coerceIn(0f, 100f) else 0f
        return Triple(percent, usedMb, totalMb)
    }

    /**
     * 温度读取策略：
     * 1. BatteryManager.BATTERY_PROPERTY_TEMPERATURE（单位 0.1°C）—— 公开 API，普通应用可读
     * 2. 兜底 sysfs thermal zone —— 部分设备普通应用无权限读取（会得到 0）
     */
    private fun readTemperature(): Float {
        // BatteryManager.BATTERY_PROPERTY_TEMPERATURE 为 API 28+，其常量值为 4；
        // 用字面量以兼容 minSdk 26。低于 API 28 的设备该属性返回 0，自动走兜底。
        val batteryTemp = batteryManager?.getIntProperty(4) ?: 0
        if (batteryTemp > 0) return batteryTemp / 10f
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

    private fun readBattery(): Int {
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}
