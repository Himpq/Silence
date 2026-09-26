package cn.himpqblog.silence.perf

import android.content.Context
import android.util.Log
import cn.himpqblog.silence.settings.SettingsStore



object PerformanceExternalModeWriter {

    private const val TAG = "Silence_Perf_Log"

    @Volatile
    private var lastWrittenPath = ""

    @Volatile
    private var lastWrittenValue = ""

    // 挡位变化后把当前有效模式同步到外部文件，并带一层去重避免重复写盘。
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "Silence-External-Mode") }

    fun syncCurrentMode(context: Context, mode: PerformanceMode = PerformanceConfigStore.resolveCurrentMode(context)): Boolean {
        val appContext = context.applicationContext
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executor.execute {
                cn.himpqblog.silence.config.FreezeListStore.syncRuntimeMirror(appContext)
                syncCurrentMode(appContext, mode)
            }
            return false // queued, not a successful write
        }
        val path = SettingsStore.getExternalPerformanceModePath(appContext)
        if (path.isBlank() || !SettingsStore.isExternalPerformanceModeEnabled(appContext)) { resetCache(); return false }
        val value = mode.externalModeToken()
        if (path == lastWrittenPath && value == lastWrittenValue) return false
        val response = cn.himpqblog.silence.daemon.DaemonControlClient.request(
            org.json.JSONObject().put("command", "SET_EXTERNAL_MODE").put("mode", value))
        val success = response?.optBoolean("success") == true
        if (success) { lastWrittenPath = path; lastWrittenValue = value }
        else Log.w(TAG, "external mode write failed detail=${response?.optString("detail") ?: "daemon_unavailable"}")
        return success
    }

    // 路径或挡位变化后需要清空去重缓存，避免下一次真实变化被误判成重复。
    fun resetCache() {
        lastWrittenPath = ""
        lastWrittenValue = ""
    }

    // 这里把 Silence 内部挡位映射成外部工具约定的文本令牌。
    private fun PerformanceMode.externalModeToken(): String {
        return when (this) {
            PerformanceMode.POWER_SAVE -> "powersave"
            PerformanceMode.BALANCED -> "balance"
            PerformanceMode.PERFORMANCE -> "performance"
            PerformanceMode.EXTREME -> "fast"
        }
    }

    // 外部路径可能需要更高权限，所以普通写失败后会走一次 root shell 回退。
}
