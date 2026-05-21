package cn.himpqblog.slience.perf

import android.content.Context
import cn.himpqblog.slience.config.FreezeListStore
import org.json.JSONObject
import java.io.File

enum class PerformanceMode(val code: Int, val labelRes: Int) {
    POWER_SAVE(0, cn.himpqblog.slience.R.string.performance_mode_power_save),
    BALANCED(1, cn.himpqblog.slience.R.string.performance_mode_balanced),
    PERFORMANCE(2, cn.himpqblog.slience.R.string.performance_mode_performance),
    EXTREME(3, cn.himpqblog.slience.R.string.performance_mode_extreme);

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

    private const val FILE_NAME = "Perf.json"

    // 统一读取性能调控配置，保证默认挡位和应用覆盖挡位始终从同一份文件解析。
    fun readConfig(context: Context): PerformanceConfig {
        val root = readRootObject(configFile(context))
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
        val file = configFile(context)
        val root = readRootObject(file)
        root.put("default", mode.code)
        file.writeText(root.toString(2), Charsets.UTF_8)
        if (previous != mode) {
            PerformanceLogStore.recordEvent(
                context = appContext,
                eventName = "default_mode_changed",
                oldValue = previous.name,
                newValue = mode.name
            )
        }
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
        val file = configFile(context)
        val root = readRootObject(file)
        val packagesObject = root.optJSONObject("packages") ?: JSONObject().also {
            root.put("packages", it)
        }
        packagesObject.put(normalized, mode.code)
        file.writeText(root.toString(2), Charsets.UTF_8)
        if (previous != mode) {
            PerformanceLogStore.recordEvent(
                context = appContext,
                eventName = "package_mode_changed",
                oldValue = "${normalized}:${previous.name}",
                newValue = "${normalized}:${mode.name}"
            )
        }
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
        val foregroundPackage = FreezeListStore.readForegroundState(context)?.packageName
        return resolveModeForPackage(context, foregroundPackage)
    }

    // 配置文件固定放在设备保护存储，避免锁屏前后或早期启动阶段拿不到数据。
    private fun configFile(context: Context): File {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val file = File(deviceContext.filesDir, FILE_NAME)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("default", PerformanceMode.POWER_SAVE.code)
                put("packages", JSONObject())
            }
            file.writeText(root.toString(2), Charsets.UTF_8)
        }
        return file
    }

    // 解析失败时直接回退到空配置，避免损坏文件把主链路拖死。
    private fun readRootObject(file: File): JSONObject {
        if (!file.exists()) {
            return JSONObject().apply {
                put("default", PerformanceMode.POWER_SAVE.code)
                put("packages", JSONObject())
            }
        }
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) {
            return JSONObject().apply {
                put("default", PerformanceMode.POWER_SAVE.code)
                put("packages", JSONObject())
            }
        }
        return runCatching { JSONObject(text) }.getOrElse {
            JSONObject().apply {
                put("default", PerformanceMode.POWER_SAVE.code)
                put("packages", JSONObject())
            }
        }
    }
}
