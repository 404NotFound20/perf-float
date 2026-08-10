package com.notfound.perffloat

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.notfound.perffloat.data.MetricsReader
import com.notfound.perffloat.data.SystemMetrics
import kotlin.math.roundToInt

/**
 * 主界面 = 实时监控仪表盘。
 * 打开即可直接观察 CPU / 内存 / 温度 / 电量 / 单核负载，每秒刷新；
 * 同时保留悬浮窗的启动与停止。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var cpuValue: TextView
    private lateinit var cpuSub: TextView
    private lateinit var memValue: TextView
    private lateinit var memSub: TextView
    private lateinit var tempValue: TextView
    private lateinit var batteryValue: TextView
    private val coreItems = ArrayList<CoreBarItem>()

    private val metricsReader by lazy { MetricsReader(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateDashboard(metricsReader.read())
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        startButton.setOnClickListener { handleStart() }
        stopButton.setOnClickListener {
            MonitorService.stop(this)
            refreshState()
            Toast.makeText(this, "已停止监控", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun handleStart() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.overlay_permission_title)
                .setMessage(R.string.overlay_permission_message)
                .setPositiveButton(R.string.grant_overlay) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        MonitorService.start(this)
        refreshState()
    }

    private fun refreshState() {
        val running = hasOverlay() && isServiceRunning()
        statusText.text = if (running) "● 监控运行中（悬浮窗已开启）" else "○ 监控未运行"
        statusText.setTextColor(if (running) Color.rgb(29, 158, 117) else Color.rgb(160, 160, 160))
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun isServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return false
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == MonitorService::class.java.name }
    }

    private fun updateDashboard(m: SystemMetrics) {
        cpuValue.text = if (m.cpuLoadPercent > 0f) "${m.cpuLoadPercent.roundToInt()}%" else "--"
        cpuSub.text = "整机平均负载"

        val totalG = m.memTotalMb / 1024
        val usedG = m.memUsedMb / 1024
        memValue.text = "$usedG/$totalG G"
        memSub.text = "已用 ${m.memUsedPercent.roundToInt()}%"

        tempValue.text = if (m.tempCelsius > 0f) "${m.tempCelsius.roundToInt()}°C" else "--"
        batteryValue.text = if (m.batteryPercent >= 0) "${m.batteryPercent}%" else "--"

        for (i in coreItems.indices) {
            val load = m.perCpuLoads.getOrNull(i)
            if (load != null) coreItems[i].setLoad(load) else coreItems[i].setLoad(-1f)
        }
    }

    private fun buildUi(): ViewGroup {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        statusText = TextView(this).apply {
            text = "○ 监控未运行"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        startButton = Button(this).apply { text = getString(R.string.start_monitor) }
        stopButton = Button(this).apply { text = getString(R.string.stop_monitor) }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(12), 0))
            addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        }

        val (cpuCard, cpuValueRef, cpuSubRef) = createMetricCard(
            "CPU 总负载", "", Color.rgb(29, 158, 117))
        cpuValue = cpuValueRef
        cpuSub = cpuSubRef
        val (memCard, memValueRef, memSubRef) = createMetricCard(
            "内存占用", "", Color.rgb(55, 138, 221))
        memValue = memValueRef
        memSub = memSubRef
        val (tempCard, tempValueRef, _) = createMetricCard(
            "设备温度", "", Color.rgb(217, 90, 48))
        tempValue = tempValueRef
        val (batteryCard, batteryValueRef, _) = createMetricCard(
            "电量", "", Color.rgb(100, 153, 34))
        batteryValue = batteryValueRef

        val cardsRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cpuCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 0))
            addView(memCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val cardsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tempCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(10), 0))
            addView(batteryCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val coreTitle = TextView(this).apply {
            text = "单核负载"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(70, 70, 70))
        }
        val coreRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (i in 0 until 8) {
                val item = CoreBarItem(this@MainActivity, i)
                coreItems.add(item)
                addView(item.container, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val hint = TextView(this).apply {
            text = "主界面为实时仪表盘，每秒刷新；也可启动悬浮窗在任意界面查看"
            textSize = 12f
            setTextColor(Color.rgb(150, 150, 150))
            gravity = Gravity.CENTER
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(20))
            background = GradientDrawable().apply { setColor(Color.rgb(245, 245, 245)) }
            addView(statusText, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))
            addView(buttonRow, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(14)))
            addView(cardsRow1, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(10)))
            addView(cardsRow2, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(16)))
            addView(coreTitle, LinearLayout.LayoutParams(MATCH_PARENT, dp(28)))
            addView(coreRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(96)))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(12)))
            addView(hint, LinearLayout.LayoutParams(MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return root
    }

    private fun createMetricCard(
        title: String,
        unit: String,
        accent: Int,
    ): Triple<View, TextView, TextView> {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val titleView = TextView(this).apply {
            text = title
            textSize = 12f
            setTextColor(Color.rgb(120, 120, 120))
        }
        val valueView = TextView(this).apply {
            text = "--"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(accent)
            setSingleLine(true)
        }
        val subView = TextView(this).apply {
            text = unit
            textSize = 11f
            setTextColor(Color.rgb(150, 150, 150))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
            }
            addView(titleView)
            addView(View(this@MainActivity), LinearLayout.LayoutParams(0, dp(4)))
            addView(valueView)
            addView(subView)
        }
        return Triple(card, valueView, subView)
    }

    private inner class CoreBarItem(context: Context, index: Int) {
        private val density = resources.displayMetrics.density
        private val barMaxHeight = (56 * density).toInt()
        private val barWidth = (10 * density).toInt()

        private val value: TextView
        private val bar: View

        val container: LinearLayout

        init {
            value = TextView(context).apply {
                text = "--"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(90, 90, 90))
            }
            val label = TextView(context).apply {
                text = "C$index"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(140, 140, 140))
            }
            bar = View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = (3 * density)
                    setColor(Color.rgb(200, 200, 200))
                }
            }
            val barWrap = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                addView(bar, LinearLayout.LayoutParams(barWidth, 0))
            }
            container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(barWrap, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barMaxHeight))
                addView(value)
                addView(label)
            }
        }

        fun setLoad(load: Float) {
            if (load < 0f) {
                value.text = "--"
                bar.layoutParams.height = 0
                (bar.background as GradientDrawable).setColor(Color.rgb(220, 220, 220))
                bar.requestLayout()
                return
            }
            value.text = "${load.roundToInt()}%"
            bar.layoutParams.height = (barMaxHeight * load / 100f).toInt()
            (bar.background as GradientDrawable).setColor(loadColor(load))
            bar.requestLayout()
        }

        private fun loadColor(load: Float): Int = when {
            load < 50f -> Color.rgb(29, 158, 117)
            load < 80f -> Color.rgb(186, 117, 23)
            else -> Color.rgb(163, 45, 45)
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1000L
    }
}
