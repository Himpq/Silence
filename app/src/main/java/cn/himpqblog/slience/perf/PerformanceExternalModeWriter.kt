package cn.himpqblog.slience.perf

import android.content.Context
import android.util.Log
import cn.himpqblog.slience.settings.SettingsStore
import com.topjohnwu.superuser.Shell
import java.io.File

object PerformanceExternalModeWriter {

    private const val TAG = "Silence_Perf_Log"

    @Volatile
    private var lastWrittenPath = ""

    @Volatile
    private var lastWrittenValue = ""

    // 挡位变化后把当前有效模式同步到外部文件，并带一层去重避免重复写盘。
    fun syncCurrentMode(context: Context, mode: PerformanceMode = PerformanceConfigStore.resolveCurrentMode(context)): Boolean {
        val appContext = context.applicationContext
        val path = SettingsStore.getExternalPerformanceModePath(appContext)
        if (path.isBlank()) {
            if (SettingsStore.isExternalPerformanceModeEnabled(appContext)) {
                SettingsStore.setExternalPerformanceModeEnabled(appContext, false)
            }
            resetCache()
            return false
        }
        if (!SettingsStore.isExternalPerformanceModeEnabled(appContext)) {
            return false
        }
        val value = mode.externalModeToken()
        if (path == lastWrittenPath && value == lastWrittenValue) {
            return false
        }
        val target = File(path)
        val currentText = runCatching {
            if (target.exists()) {
                target.readText(Charsets.UTF_8).trim()
            } else {
                ""
            }
        }.getOrDefault("")
        if (currentText == value) {
            lastWrittenPath = path
            lastWrittenValue = value
            return false
        }
        return runCatching {
            target.parentFile?.mkdirs()
            target.writeText(value, Charsets.UTF_8)
            lastWrittenPath = path
            lastWrittenValue = value
            Log.i(TAG, "[Silence_Perf_Log] external mode write path=$path value=$value")
            true
        }.getOrElse { err ->
            val fallback = writeWithRootFallback(path, value)
            Log.i(
                TAG,
                "[Silence_Perf_Log] external mode write failed path=$path err=${err.message ?: err.javaClass.simpleName} fallback=$fallback"
            )
            if (fallback) {
                lastWrittenPath = path
                lastWrittenValue = value
            }
            fallback
        }
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
    private fun writeWithRootFallback(path: String, value: String): Boolean {
        val escapedPath = path.replace("'", "'\\''")
        val escapedValue = value.replace("'", "'\\''")
        val parentPath = File(path).parentFile?.absolutePath?.replace("'", "'\\''")
        val command = buildString {
            if (!parentPath.isNullOrBlank()) {
                append("mkdir -p '$parentPath' 2>/dev/null\n")
            }
            append("printf '%s' '$escapedValue' > '$escapedPath'\n")
            append("chmod 0644 '$escapedPath' 2>/dev/null")
        }
        return runCatching {
            Shell.cmd(command).exec().isSuccess
        }.getOrDefault(false)
    }
}
