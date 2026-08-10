package com.notfound.perffloat.data

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.io.File

/**
 * 读取整机性能数据：
 * - CPU 负载：连续两次读取 /proc/stat 计算
 * - 内存：/proc/meminfo
 * - 温度：/sys/class/thermal/ 下的 thermal_zone 系列节点
 * - 电量：BatteryManager（Android 5+ 支持 getIntProperty）
 */
class MetricsReader(private val context: Context) {

    private var prevCpu: ProcParser.CpuTimes? = null

    fun read(): SystemMetrics {
        val cpu = readCpuLoad()
        val (usedPercent, usedMb, totalMb) = readMemory()
        val temp = readTemperature()
        val battery = readBattery()
        return SystemMetrics(
            cpuLoadPercent = cpu,
            memUsedPercent = usedPercent,
            memUsedMb = usedMb,
            memTotalMb = totalMb,
            tempCelsius = temp,
            batteryPercent = battery,
        )
    }

    private fun readCpuLoad(): Float {
        val line = readFirstLine("/proc/stat") { it.startsWith("cpu ") } ?: return 0f
        val curr = ProcParser.parseCpuTimes(line) ?: return 0f
        val prev = prevCpu
        prevCpu = curr
        if (prev == null) return 0f
        return ProcParser.cpuLoadPercent(prev, curr)
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
     * 遍历 thermal zones，优先取 cpu/gpu 相关传感器，取最高温度。
     */
    private fun readTemperature(): Float {
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return -1
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun readFirstLine(path: String, predicate: (String) -> Boolean): String? {
        return runCatching {
            File(path).bufferedReader().useLines { lines -> lines.firstOrNull(predicate) }
        }.getOrNull()
    }
}
