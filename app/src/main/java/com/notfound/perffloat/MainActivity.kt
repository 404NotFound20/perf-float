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
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

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
        statusText.text = if (running) "● 监控运行中" else "○ 监控未运行"
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

    private fun buildUi(): ViewGroup {
        val density = resources.displayMetrics.density

        statusText = TextView(this).apply {
            text = "○ 监控未运行"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        startButton = Button(this).apply { text = getString(R.string.start_monitor) }
        stopButton = Button(this).apply { text = getString(R.string.stop_monitor) }

        val hint = TextView(this).apply {
            text = "浮窗可长按拖动，显示 CPU / 内存 / 温度 / 电量"
            textSize = 13f
            setTextColor(Color.rgb(120, 120, 120))
            gravity = Gravity.CENTER
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, dp(density, 48), 1f))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(density, 12), 0))
            addView(stopButton, LinearLayout.LayoutParams(0, dp(density, 48), 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(density, 24), dp(density, 48), dp(density, 24), dp(density, 24))
            addView(statusText, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 56)))
            addView(hint, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 36)))
            addView(buttonRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 卡片感背景
        root.background = GradientDrawable().apply {
            setColor(Color.rgb(244, 244, 244))
        }
        return root
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()
}
