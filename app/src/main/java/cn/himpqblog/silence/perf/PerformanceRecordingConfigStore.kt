package cn.himpqblog.silence.perf

import android.content.Context
import android.util.Log
import cn.himpqblog.silence.settings.SettingsStore
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 小型的 daemon 记录配置文件。
 *
 * 记录开关和间隔仍由 App 设置页维护，daemon 只读取这份设备保护存储中的配置，
 * 这样 App 不需要通过未鉴权的 root 控制命令去改变 daemon 状态。
 */
object PerformanceRecordingConfigStore {

    private const val LOG_TAG = "Silence_Perf_Log"
    private const val FILE_NAME = "silence_recording.config"

    fun sync(context: Context): Boolean {
        val appContext = context.applicationContext
        val file = configFile(appContext)
        val enabled = SettingsStore.isPowerRecordEnabled(appContext)
        val intervalSeconds = SettingsStore.getPowerRecordPollIntervalSeconds(appContext)
        val performanceConfig = PerformanceConfigStore.readConfig(appContext)
        val profile = PerformanceProfileStore.activeProfile(appContext)
        val content = buildString {
            append("enabled=")
            append(if (enabled) "1" else "0")
            append('\n')
            append("intervalMs=")
            append(intervalSeconds * 1000)
            append('\n')
            append("defaultMode=")
            append(performanceConfig.defaultMode.code)
            append('\n')
            append("profileId=")
            append(profile.id)
            append('\n')
            append("profileRevision=")
            append(profile.revision)
            append('\n')
            append("profilePath=")
            append(PerformanceProfileStore.activeProfilePath(appContext))
            append('\n')
            // 当前阶段只下发配置，不允许 daemon 执行任何性能调控。
            append("controlEnabled=0\n")
            performanceConfig.packageModes
                .toSortedMap()
                .forEach { (packageName, mode) ->
                    append("packageMode=")
                    append(packageName)
                    append('|')
                    append(mode.code)
                    append('\n')
                }
        }
        return runCatching {
            if (file.exists() && file.readText(StandardCharsets.UTF_8) == content) return true
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(content, StandardCharsets.UTF_8)
            if (!temporary.renameTo(file)) {
                throw IllegalStateException("无法提交 daemon 配置: ${file.absolutePath}")
            }
            Log.i(
                LOG_TAG,
                "[Silence_Perf_Log] daemon recording config synced enabled=$enabled " +
                    "intervalMs=${intervalSeconds * 1000} path=${file.absolutePath}"
            )
            true
        }.getOrElse { error ->
            Log.e(
                LOG_TAG,
                "[Silence_Perf_Log] daemon recording config sync failed path=${file.absolutePath}",
                error
            )
            false
        }
    }

    fun configPath(context: Context): String {
        return configFile(context.applicationContext).absolutePath
    }

    fun recordDirectory(context: Context): String {
        return context.applicationContext
            .createDeviceProtectedStorageContext()
            .filesDir
            .absolutePath
    }

    private fun configFile(context: Context): File {
        val deviceContext = context.createDeviceProtectedStorageContext()
        return File(deviceContext.filesDir, FILE_NAME)
    }
}
