package com.notfound.perffloat.data

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
) {
    /** 采集完全失败时用于兜底显示 */
    val failed: Boolean
        get() = cpuLoadPercent <= 0f && tempCelsius <= 0f && batteryPercent < 0
}
