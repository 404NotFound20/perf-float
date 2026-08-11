package com.notfound.perffloat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * CPU / 温度 双折线趋势图。
 * 维护固定长度历史数据，温度无效值（<=0）以 NaN 存储并在绘制时断线。
 */
class TrendChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val maxPoints = 120
    private val cpuHistory = ArrayDeque<Float>()
    private val tempHistory = ArrayDeque<Float>()

    private val gridPaint = Paint().apply { color = Color.rgb(230, 230, 230); strokeWidth = 1f }
    private val axisPaint = Paint().apply { color = Color.rgb(160, 160, 160); textSize = 22f }
    private val cpuPaint = Paint().apply { color = Color.rgb(55, 138, 221); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val tempPaint = Paint().apply { color = Color.rgb(217, 90, 48); strokeWidth = 3f; style = Paint.Style.STROKE }
    private val legendCpu = Paint().apply { color = Color.rgb(55, 138, 221) }
    private val legendTemp = Paint().apply { color = Color.rgb(217, 90, 48) }

    fun addData(cpu: Float, temp: Float) {
        if (cpuHistory.size >= maxPoints) cpuHistory.removeFirst()
        if (tempHistory.size >= maxPoints) tempHistory.removeFirst()
        cpuHistory.addLast(if (cpu.isNaN()) Float.NaN else cpu.coerceIn(0f, 100f))
        tempHistory.addLast(if (temp > 0f) temp else Float.NaN)
        invalidate()
    }

    fun reset() {
        cpuHistory.clear()
        tempHistory.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 30f
        val padR = 8f
        val padT = 18f
        val padB = 8f
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        if (chartW <= 0 || chartH <= 0) return

        // 网格：横向 4 等分
        for (i in 0..4) {
            val y = padT + chartH * i / 4f
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
        }

        // 图例
        canvas.drawRect(padL, padT - 12f, padL + 12f, padT - 4f, legendCpu)
        canvas.drawText("CPU %", padL + 16f, padT - 4f, axisPaint)
        canvas.drawRect(padL + 70f, padT - 12f, padL + 82f, padT - 4f, legendTemp)
        canvas.drawText("温度 °C", padL + 86f, padT - 4f, axisPaint)

        if (cpuHistory.size < 2) return
        val cpuPath = Path()
        val tempPath = Path()
        val n = cpuHistory.size
        var cpuStarted = false
        var tempStarted = false
        for (i in 0 until n) {
            val x = padL + chartW * i / (maxPoints - 1).toFloat()

            val cpuV = cpuHistory[i]
            if (!cpuV.isNaN()) {
                val cpuY = padT + chartH * (1f - cpuV / 100f)
                if (!cpuStarted) {
                    cpuPath.moveTo(x, cpuY)
                    cpuStarted = true
                } else {
                    cpuPath.lineTo(x, cpuY)
                }
            } else {
                cpuStarted = false
            }

            val tempV = tempHistory[i]
            if (!tempV.isNaN()) {
                val normalized = ((tempV - 20f) / 35f).coerceIn(0f, 1f) // 20~55°C 映射 0~1
                val tempY = padT + chartH * (1f - normalized)
                if (!tempStarted) {
                    tempPath.moveTo(x, tempY)
                    tempStarted = true
                } else {
                    tempPath.lineTo(x, tempY)
                }
            } else {
                tempStarted = false
            }
        }
        canvas.drawPath(cpuPath, cpuPaint)
        canvas.drawPath(tempPath, tempPaint)

        // 最新值角标
        val lastCpu = cpuHistory.last()
        if (!lastCpu.isNaN()) {
            canvas.drawText("${lastCpu.roundToInt()}%", padL, padT + chartH - 4f, axisPaint)
        }
        val lastTemp = tempHistory.last()
        if (!lastTemp.isNaN()) {
            canvas.drawText(
                "${lastTemp.roundToInt()}°",
                padL + chartW - 44f,
                padT + chartH - 4f,
                axisPaint)
        }
    }
}
