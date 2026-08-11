package com.notfound.perffloat.data

/** CPU 数值的实际来源，界面据此区分百分比 / 估算值 / 负载指数。 */
enum class CpuSource { STAT, UPTIME, LOAD_AVG, UNAVAILABLE }

/**
 * 一次性能采样结果。
 * 数值均为主线程直接可展示的格式化前数据。
 */
data class SystemMetrics(
    val cpuLoadPercent: Float = 0f,
    val memUsedPercent: Float = 0f,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val tempCelsius: Float = 0f,
    val batteryPercent: Int = -1,
    val perCpuLoads: List<Float> = emptyList(),
    val loadAvg: Float = 0f,
    val cpuSource: CpuSource = CpuSource.STAT,
) {
    /** 采集完全失败时用于兜底显示 */
    val failed: Boolean
        get() = cpuLoadPercent <= 0f && tempCelsius <= 0f && batteryPercent < 0
}
