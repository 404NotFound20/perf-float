package com.notfound.perffloat.data

/**
 * /proc 与 sysfs 文本的纯解析逻辑，不依赖 Android 环境，可独立单元测试。
 */
object ProcParser {

    /** CPU 时间片快照，来自 /proc/stat 的 cpu 总行 */
    data class CpuTimes(val totalJiffies: Long, val idleJiffies: Long)

    /**
     * 解析 /proc/stat 中形如 "cpu  123 456 789 ..." 的行。
     * 字段顺序: user nice system idle iowait irq softirq steal guest guest_nice
     * idle = idle + iowait，total = 前 8 个字段之和。
     */
    fun parseCpuTimes(line: String): CpuTimes? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5 || parts[0] != "cpu") return null
        val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 8) return null
        val idle = nums[3] + nums[4]
        val total = nums.take(8).sum()
        return CpuTimes(total, idle)
    }

    /** 由两次采样计算 CPU 使用率（0-100），两次采样间隔过短会导致结果失真。 */
    fun cpuLoadPercent(prev: CpuTimes, curr: CpuTimes): Float {
        val totalDelta = curr.totalJiffies - prev.totalJiffies
        if (totalDelta <= 0) return 0f
        val idleDelta = curr.idleJiffies - prev.idleJiffies
        val busy = totalDelta - idleDelta
        return (busy.toFloat() / totalDelta * 100f).coerceIn(0f, 100f)
    }

    /**
     * 解析 /proc/meminfo，返回 Pair(totalKB, availableKB)。
     * 单位统一为 kB（/proc/meminfo 原生单位）。
     */
    fun parseMemInfo(content: String): Pair<Long, Long>? {
        var totalKb = -1L
        var availableKb = -1L
        for (line in content.lines()) {
            val t = line.trim()
            when {
                t.startsWith("MemTotal:") -> totalKb = t.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L
                t.startsWith("MemAvailable:") -> availableKb = t.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L
            }
        }
        if (totalKb <= 0 || availableKb < 0) return null
        return totalKb to availableKb
    }

    /**
     * sysfs 温度原始值转摄氏度。thermal_zone 的 temp 单位为毫摄氏度（如 38000 = 38°C）。
     */
    fun milliToCelsius(value: Long): Float = value / 1000f

    /** 从某个 thermal_zone 路径下的 temp 内容读取温度（摄氏度），失败返回 null。 */
    fun parseThermalTemp(raw: String): Float? {
        val value = raw.trim().toLongOrNull() ?: return null
        if (value == -127000L) return null // 无效温度哨兵值
        return milliToCelsius(value)
    }
}
