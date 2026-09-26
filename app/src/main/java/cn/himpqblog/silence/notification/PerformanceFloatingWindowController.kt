package cn.himpqblog.silence.notification

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import cn.himpqblog.silence.R
import cn.himpqblog.silence.daemon.DaemonPowerSnapshot
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import cn.himpqblog.silence.databinding.ViewStatePopupBinding
import cn.himpqblog.silence.perf.PerformanceConfigStore
import cn.himpqblog.silence.perf.PerformanceMode
import java.util.concurrent.Executors
import java.util.Locale

object PerformanceFloatingWindowController {

    private const val TAG = "Silence_Perf_Log"

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val snapshotExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Silence-Overlay-Snapshot").apply { isDaemon = true }
    }

    private var currentWindowManager: WindowManager? = null
    private var currentView: View? = null

    // 悬浮窗展示时统一消费 daemon 快照，避免 UI 侧重复读取系统状态。
    fun show(context: Context) {
        val appContext = context.applicationContext
        Log.i(TAG, "overlay show requested")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.i(TAG, "[Silence_Perf_Log] overlay permission missing")
            return
        }

        snapshotExecutor.execute {
            val daemonSnapshot = SilenceDaemonClient.getSnapshot(appContext)
            Log.i(TAG, "overlay snapshot received=${daemonSnapshot != null}")
            mainHandler.post {
                showSnapshot(appContext, daemonSnapshot)
            }
        }
    }

    private fun showSnapshot(context: Context, daemonSnapshot: cn.himpqblog.silence.daemon.DaemonSnapshot?) {
        val foregroundPackage = daemonSnapshot?.foregroundPackageName?.takeIf { it.isNotBlank() }
        val foregroundText = foregroundPackage ?: context.getString(R.string.home_foreground_unknown)
        val selectedMode = PerformanceConfigStore.resolveModeForPackage(context, foregroundPackage)
        val themedContext = ContextThemeWrapper(context, R.style.Theme_Silence)
        val binding = ViewStatePopupBinding.inflate(LayoutInflater.from(themedContext))
        binding.statePopupTitle.text = context.getString(R.string.performance_schedule_popup_title)
        binding.statePopupMessage.text = foregroundText
        binding.statePopupSubtitle.text = buildPowerSummary(context, daemonSnapshot?.power)
        binding.statePopupIcon.setImageDrawable(resolveAppIcon(context, foregroundPackage))
        binding.statePopupOverlay.setOnClickListener { hide() }
        binding.statePopupCard.setOnClickListener { }
        bindModeSelection(context, binding, foregroundPackage, selectedMode)

        val windowManager = context.getSystemService(WindowManager::class.java) ?: return
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
            val metrics = context.resources.displayMetrics
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
    private fun buildPowerSummary(context: Context, power: DaemonPowerSnapshot?): String {
        if (power == null || !power.available) {
            return context.getString(R.string.performance_overlay_power_unknown)
        }
        val direction = when (power.state) {
            "charging" -> context.getString(R.string.power_state_charging)
            "discharging" -> context.getString(R.string.power_state_discharging)
            else -> context.getString(R.string.power_state_idle)
        }
        return String.format(Locale.US, "%s %+.2f W", direction, power.powerW)
    }

}
