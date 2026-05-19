package cn.himpqblog.slience.notification

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.databinding.ViewStatePopupBinding

object PerformanceFloatingWindowController {

    private const val TAG = "Silence_Perf_Log"

    private val lock = Any()

    private var currentWindowManager: WindowManager? = null
    private var currentView: View? = null
    private var selectedMode: PerformanceMode = PerformanceMode.BALANCED

    fun show(context: Context) {
        val appContext = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(appContext)) {
            Log.i(TAG, "[Silence_Perf_Log] overlay permission missing")
            return
        }

        val foregroundState = FreezeListStore.readForegroundState(appContext)
        val foregroundPackage = foregroundState?.packageName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.home_foreground_unknown)
        val themedContext = ContextThemeWrapper(appContext, R.style.Theme_Silence)
        val binding = ViewStatePopupBinding.inflate(LayoutInflater.from(themedContext))
        binding.statePopupTitle.text = appContext.getString(R.string.performance_schedule_popup_title)
        binding.statePopupMessage.text = appContext.getString(
            R.string.performance_schedule_popup_message,
            foregroundPackage
        )
        binding.statePopupSubtitle.text = appContext.getString(R.string.performance_overlay_subtitle)
        binding.statePopupCloseHint.text = appContext.getString(R.string.performance_overlay_close_hint)
        binding.statePopupIcon.setImageDrawable(resolveAppIcon(appContext, foregroundState?.packageName))
        binding.statePopupOverlay.setOnClickListener { hide() }
        binding.statePopupCard.setOnClickListener { }
        bindModeSelection(appContext, binding)

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
        Log.i(TAG, "[Silence_Perf_Log] overlay show foreground=$foregroundPackage mode=${selectedMode.name}")
    }

    fun hide() {
        synchronized(lock) {
            hideLocked()
        }
    }

    private fun hideLocked() {
        val view = currentView ?: return
        runCatching {
            currentWindowManager?.removeViewImmediate(view)
        }
        currentView = null
        currentWindowManager = null
    }

    private fun bindModeSelection(context: Context, binding: ViewStatePopupBinding) {
        val modeButtons = linkedMapOf(
            PerformanceMode.POWER_SAVE to binding.stateModePowerSave,
            PerformanceMode.BALANCED to binding.stateModeBalanced,
            PerformanceMode.PERFORMANCE to binding.stateModePerformance,
            PerformanceMode.EXTREME to binding.stateModeExtreme
        )
        modeButtons.forEach { (mode, button) ->
            button.setOnClickListener {
                selectedMode = mode
                updateModeUi(context, binding, modeButtons)
                Log.i(TAG, "[Silence_Perf_Log] overlay mode switched mode=${mode.name}")
            }
        }
        updateModeUi(context, binding, modeButtons)
    }

    private fun updateModeUi(
        context: Context,
        binding: ViewStatePopupBinding,
        modeButtons: Map<PerformanceMode, MaterialButton>
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
        binding.statePopupSelectedMode.text = context.getString(
            R.string.performance_overlay_selected_mode,
            context.getString(selectedMode.labelRes)
        )
    }

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

    private enum class PerformanceMode(val labelRes: Int) {
        POWER_SAVE(R.string.performance_mode_power_save),
        BALANCED(R.string.performance_mode_balanced),
        PERFORMANCE(R.string.performance_mode_performance),
        EXTREME(R.string.performance_mode_extreme)
    }
}
