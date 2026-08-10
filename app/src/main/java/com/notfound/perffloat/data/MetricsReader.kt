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

    /** 最近一次采集失败的根因，供界面诊断展示。 */
    @Volatile
    var lastError: String? = null
        private set

    private data class CpuSnapshot(
        val total: ProcParser.CpuTimes,
        val perCore: List<ProcParser.CpuTimes>,
    )

    fun read(): SystemMetrics {
        val snapshot = readSnapshot()
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
            loadAvg = readLoadAvg(),
        )
    }

    private fun readSnapshot(): CpuSnapshot? {
        return runCatching {
            val lines = File("/proc/stat").readLines()
            val totalLine = lines.firstOrNull { it.startsWith("cpu ") }
                ?: error("/proc/stat 中没有 cpu 总行")
            val total = ProcParser.parseCpuTimes(totalLine)
                ?: error("cpu 行格式异常: $totalLine")
            val perCore = ProcParser.parsePerCoreCpuTimes(lines.joinToString("\n"))
            CpuSnapshot(total, perCore)
        }.onFailure { lastError = "CPU 读取失败: ${it.message}" }.getOrNull()
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
     * 1. BatteryManager.BATTERY_PROPERTY_TEMPERATURE（属性 id=4）
     *    - 官方定义单位为十分之一摄氏度（如 350 = 35.0°C）
     *    - 部分厂商（实测 OPPO）直接返回整数摄氏度（如 50 = 50°C）
     *    - 用启发式：raw >= 100 视为十分之一度，否则视为摄氏度
     * 2. 兜底 sysfs thermal zone —— 部分设备普通应用无权限读取（会得到 0）
     */
    private fun readTemperature(): Float {
        val raw = batteryManager?.getIntProperty(4) ?: 0
        if (raw > 0) {
            val celsius = if (raw >= 100) raw / 10f else raw.toFloat()
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

    private fun readBattery(): Int {
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    /** /proc/loadavg 的 1 分钟负载指数，作为 CPU 百分比不可用时的兜底指标。 */
    private fun readLoadAvg(): Float {
        return runCatching {
            ProcParser.parseLoadAvg(File("/proc/loadavg").readText()) ?: 0f
        }.getOrDefault(0f)
    }
}
