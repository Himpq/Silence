package cn.himpqblog.slience.notification

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.ViewStatePopupBinding
import cn.himpqblog.slience.perf.PerformanceConfigStore
import cn.himpqblog.slience.perf.PerformanceMode
import java.util.Locale

object PerformanceFloatingWindowController {

    private const val TAG = "Silence_Perf_Log"

    private val lock = Any()

    private var currentWindowManager: WindowManager? = null
    private var currentView: View? = null

    // 悬浮窗展示时会同时读取前台包名、应用图标、电池功率和当前挡位。
    fun show(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.i(TAG, "[Silence_Perf_Log] overlay permission missing")
            return
        }

        val foregroundState = FreezeListStore.readForegroundState(appContext)
        val foregroundPackage = foregroundState?.packageName?.takeIf { it.isNotBlank() }
        val foregroundText = foregroundPackage ?: appContext.getString(R.string.home_foreground_unknown)
        val selectedMode = PerformanceConfigStore.resolveModeForPackage(appContext, foregroundPackage)
        val themedContext = ContextThemeWrapper(appContext, R.style.Theme_Silence)
        val binding = ViewStatePopupBinding.inflate(LayoutInflater.from(themedContext))
        binding.statePopupTitle.text = appContext.getString(R.string.performance_schedule_popup_title)
        binding.statePopupMessage.text = foregroundText
        binding.statePopupSubtitle.text = buildPowerSummary(appContext)
        binding.statePopupIcon.setImageDrawable(resolveAppIcon(appContext, foregroundState?.packageName))
        binding.statePopupOverlay.setOnClickListener { hide() }
        binding.statePopupCard.setOnClickListener { }
        bindModeSelection(appContext, binding, foregroundPackage, selectedMode)

        val windowManager = appContext.getSystemService(WindowManager::class.java) ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        binding.statePopupCard.post {
            val metrics = appContext.resources.displayMetrics
            val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
            val targetWidth = (shortSide * 0.88f).toInt().coerceAtLeast((300 * metrics.density).toInt())
            val layoutParams = binding.statePopupCard.layoutParams as? ViewGroup.MarginLayoutParams ?: return@post
            layoutParams.width = targetWidth
            binding.statePopupCard.layoutParams = layoutParams
        }

        synchronized(lock) {
            hideLocked()
            currentWindowManager = windowManager
            currentView = binding.root
            runCatching {
                windowManager.addView(binding.root, params)
            }.onFailure { err ->
                currentView = null
                currentWindowManager = null
                Log.i(TAG, "[Silence_Perf_Log] overlay show failed: ${err.message ?: err.javaClass.simpleName}")
                return
            }
        }
        Log.i(TAG, "[Silence_Perf_Log] overlay show foreground=$foregroundText mode=${selectedMode.name}")
    }

    // 对外只暴露一个安全隐藏入口，内部细节统一交给锁内实现处理。
    fun hide() {
        synchronized(lock) {
            hideLocked()
        }
    }

    // 真正的移除动作放在这里，避免 show / hide 并发时出现窗口残留。
    private fun hideLocked() {
        val view = currentView ?: return
        runCatching {
            currentWindowManager?.removeViewImmediate(view)
        }
        currentView = null
        currentWindowManager = null
    }

    // 挡位按钮点击后立即保存到应用专属配置，并反向触发通知刷新。
    private fun bindModeSelection(
        context: Context,
        binding: ViewStatePopupBinding,
        foregroundPackage: String?,
        initialMode: PerformanceMode
    ) {
        val modeButtons = linkedMapOf(
            PerformanceMode.POWER_SAVE to binding.stateModePowerSave,
            PerformanceMode.BALANCED to binding.stateModeBalanced,
            PerformanceMode.PERFORMANCE to binding.stateModePerformance,
            PerformanceMode.EXTREME to binding.stateModeExtreme
        )
        var selectedMode = initialMode
        modeButtons.forEach { (mode, button) ->
            button.setOnClickListener {
                if (!foregroundPackage.isNullOrBlank()) {
                    PerformanceConfigStore.setPackageMode(context, foregroundPackage, mode)
                }
                selectedMode = mode
                updateModeUi(context, binding, modeButtons, mode)
                PersistentStatusNotificationService.requestImmediateRefresh(context)
                Log.i(TAG, "[Silence_Perf_Log] overlay mode switched mode=${mode.name}")
            }
        }
        updateModeUi(context, binding, modeButtons, selectedMode)
    }

    // 这里只同步悬浮窗内部的选中态和当前模式文案，不直接写业务状态。
    private fun updateModeUi(
        context: Context,
        binding: ViewStatePopupBinding,
        modeButtons: Map<PerformanceMode, MaterialButton>,
        selectedMode: PerformanceMode
    ) {
        modeButtons.forEach { (mode, button) ->
            val selected = mode == selectedMode
            button.isChecked = selected
            button.strokeWidth = if (selected) 0 else 1
            button.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.brand_primary_dim else R.color.surface_card_soft
                )
            )
            button.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (selected) R.color.text_primary else R.color.text_secondary
                )
            )
        }
        binding.statePopupSelectedMode.text = context.getString(selectedMode.labelRes)
    }

    // 前台应用图标优先显示目标包名，失败时再回退到 Silence 或系统默认图标。
    private fun resolveAppIcon(context: Context, packageName: String?): Drawable {
        return runCatching {
            if (packageName.isNullOrBlank()) {
                ContextCompat.getDrawable(context, R.drawable.ic_stat_status_monitor)
                    ?: context.packageManager.defaultActivityIcon
            } else {
                context.packageManager.getApplicationIcon(packageName)
            }
        }.getOrElse {
            context.packageManager.defaultActivityIcon
        }
    }

    // 悬浮窗里展示的是轻量功率摘要，保持和通知栏同一套方向语义。
    private fun buildPowerSummary(context: Context): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return context.getString(R.string.performance_overlay_power_unknown)
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE }
        val currentAvg = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            .takeIf { it != Int.MIN_VALUE }
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        val signedCurrentUa = currentNow ?: currentAvg
        if (signedCurrentUa == null || voltageMv == null) {
            return context.getString(R.string.performance_overlay_power_unknown)
        }
        val direction = when {
            signedCurrentUa > 0 -> context.getString(R.string.power_state_charging)
            signedCurrentUa < 0 -> context.getString(R.string.power_state_discharging)
            else -> context.getString(R.string.power_state_idle)
        }
        val powerW = kotlin.math.abs(signedCurrentUa / 1_000_000f * (voltageMv / 1000f))
        return String.format(Locale.US, "%s %.2f W", direction, powerW)
    }

}
