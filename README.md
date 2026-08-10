# PerfFloat 性能浮窗

Android 悬浮窗性能监控工具：以可拖动的顶层浮窗显示 CPU 负载、内存占用、温度、电量。

## 功能

- 常驻悬浮窗（`TYPE_APPLICATION_OVERLAY`），可拖动、可随时启停
- CPU 负载：读取 `/proc/stat` 两次采样计算
- 内存：读取 `/proc/meminfo`（总/已用）
- 温度：遍历 `/sys/class/thermal/thermal_zone*`，优先 CPU/GPU 传感器
- 电量：`BatteryManager` 实时容量
- 前台服务保活 + 每秒刷新，退出 App 后浮窗仍常驻

## 构建

要求：JDK 17、Android SDK 34。

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 安装后打开 App，点击「启动监控」
2. 按提示授予「悬浮窗权限」（显示在其他应用上层）
3. Android 13+ 还需允许通知权限（前台服务通知）
4. 浮窗显示在屏幕顶部，长按拖动，点击「停止监控」或下拉停止服务

> 注意：部分国产 ROM 需要在系统设置中允许 App 后台运行/自启动，否则锁屏后浮窗可能被系统回收。

## 技术栈

- Kotlin 1.9 + Android Gradle Plugin 8.2
- minSdk 26 (Android 8.0) / targetSdk 34 (Android 14)
- 无第三方监控依赖，数据全部来自系统 `/proc`、`sysfs` 与 `BatteryManager`
