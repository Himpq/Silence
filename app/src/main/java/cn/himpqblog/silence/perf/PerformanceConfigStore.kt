package cn.himpqblog.silence.perf

import android.content.Context
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import org.json.JSONObject

enum class PerformanceMode(val code: Int, val labelRes: Int) {
    POWER_SAVE(0, cn.himpqblog.silence.R.string.performance_mode_power_save),
    BALANCED(1, cn.himpqblog.silence.R.string.performance_mode_balanced),
    PERFORMANCE(2, cn.himpqblog.silence.R.string.performance_mode_performance),
    EXTREME(3, cn.himpqblog.silence.R.string.performance_mode_extreme);

    companion object {
        // 外部读配置时统一从整数码恢复枚举，非法值直接回退到省电挡位。
        fun fromCode(code: Int): PerformanceMode {
            return entries.firstOrNull { it.code == code } ?: POWER_SAVE
        }
    }
}

data class PerformanceConfig(
    val defaultMode: PerformanceMode,
    val packageModes: Map<String, PerformanceMode>
)

object PerformanceConfigStore {

    // 统一读取性能调控配置，保证默认挡位和应用覆盖挡位始终从同一份文件解析。
    fun readConfig(context: Context): PerformanceConfig {
        val root = PerformanceProfileStore.readActiveRoot(context)
        val defaultMode = PerformanceMode.fromCode(root.optInt("default", PerformanceMode.POWER_SAVE.code))
        val packagesObject = root.optJSONObject("packages") ?: JSONObject()
        val packageModes = LinkedHashMap<String, PerformanceMode>()
        packagesObject.keys().forEach { packageName ->
            val normalized = packageName.trim()
            if (normalized.isNotEmpty()) {
                packageModes[normalized] = PerformanceMode.fromCode(
                    packagesObject.optInt(packageName, defaultMode.code)
                )
            }
        }
        return PerformanceConfig(
            defaultMode = defaultMode,
            packageModes = packageModes
        )
    }

    // 修改全局默认挡位时，同时写入性能日志和外部联动状态。
    fun setDefaultMode(context: Context, mode: PerformanceMode) {
        val appContext = context.applicationContext
        val previous = readConfig(appContext).defaultMode
        val root = PerformanceProfileStore.readActiveRoot(appContext)
        root.put("default", mode.code)
        PerformanceProfileStore.writeActiveRoot(appContext, root)
        if (previous != mode) {
            PerformanceLogStore.recordEvent(
                context = appContext,
                eventName = "default_mode_changed",
                oldValue = previous.name,
                newValue = mode.name
            )
        }
        PerformanceRecordingConfigStore.sync(appContext)
        PerformanceExternalModeWriter.syncCurrentMode(appContext)
    }

    // 修改应用专属挡位时，使用包名作为覆盖键，并把这次变化记入日志。
    fun setPackageMode(context: Context, packageName: String, mode: PerformanceMode) {
        val appContext = context.applicationContext
        val normalized = packageName.trim()
        if (normalized.isEmpty()) {
            return
        }
        val previous = resolveModeForPackage(appContext, normalized)
        val root = PerformanceProfileStore.readActiveRoot(appContext)
        val packagesObject = root.optJSONObject("packages") ?: JSONObject().also {
            root.put("packages", it)
        }
        packagesObject.put(normalized, mode.code)
        PerformanceProfileStore.writeActiveRoot(appContext, root)
        if (previous != mode) {
            PerformanceLogStore.recordEvent(
                context = appContext,
                eventName = "package_mode_changed",
                oldValue = "${normalized}:${previous.name}",
                newValue = "${normalized}:${mode.name}"
            )
        }
        PerformanceRecordingConfigStore.sync(appContext)
        PerformanceExternalModeWriter.syncCurrentMode(appContext)
    }

    // 当前台应用没有专属挡位时，统一回退到全局默认挡位。
    fun resolveModeForPackage(context: Context, packageName: String?): PerformanceMode {
        val config = readConfig(context)
        val normalized = packageName?.trim().orEmpty()
        return if (normalized.isEmpty()) {
            config.defaultMode
        } else {
            config.packageModes[normalized] ?: config.defaultMode
        }
    }

    // 这里读的是“当前有效挡位”，用于通知、悬浮窗和外部模式同步共用。
    fun resolveCurrentMode(context: Context): PerformanceMode {
        val snapshot = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) SilenceDaemonClient.latestSnapshot
            else SilenceDaemonClient.getSnapshot(context)
        val foregroundPackage = snapshot?.foregroundPackageName
        return resolveModeForPackage(context, foregroundPackage)
    }

}
