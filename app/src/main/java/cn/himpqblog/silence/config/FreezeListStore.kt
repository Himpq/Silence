package cn.himpqblog.silence.config

import android.content.Context
import android.util.Log
import android.util.Base64
import cn.himpqblog.silence.daemon.DaemonControlClient
import cn.himpqblog.silence.daemon.DaemonConfigSynchronizer
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import cn.himpqblog.silence.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FreezeListStore {

    private const val TAG = "Silence"

    private const val ASSET_NAME = "FreezeList.json"
    private const val PACKAGE_NAME = "cn.himpqblog.silence"
    private const val MIRROR_DIR = "/data/media/0/Android/data/$PACKAGE_NAME/data"
    private const val MIRROR_CONFIG_NAME = "FreezeList.runtime.json"
    private const val MIRROR_SETTINGS_NAME = "silence_settings.runtime.xml"
    private const val RUNTIME_STATE_PREFS_NAME = "silence_runtime_state"
    private const val FORCE_POLL_PROP = "persist.silence.force_poll"
    private const val GLOBAL_RULES_KEY = "silence_freeze_rules_json_b64"
    private const val GLOBAL_HOOK_POLL_INTERVAL_KEY = "silence_hook_poll_interval_seconds"
    private const val GLOBAL_HOOK_ENABLED_KEY = "silence_hook_enabled"
    private const val GLOBAL_FREEZE_HOOK_ENABLED_KEY = "silence_freeze_hook_enabled"
    private const val GLOBAL_PERFORMANCE_HOOK_ENABLED_KEY = "silence_performance_hook_enabled"
    private const val GLOBAL_PROCESS_DEBUG_LOG_ENABLED_KEY = "silence_process_debug_log_enabled"
    private const val GLOBAL_PROCESS_PRIORITY_RULES_KEY = "silence_process_priority_rules_b64"
    private const val GLOBAL_FOREGROUND_PACKAGE_KEY = "silence_foreground_package"
    private const val GLOBAL_FOREGROUND_SOURCE_KEY = "silence_foreground_source"
    private const val GLOBAL_FOREGROUND_UPDATED_AT_KEY = "silence_foreground_updated_at"
    private const val RUNTIME_FOREGROUND_PACKAGE_KEY = "runtime_foreground_package"
    private const val RUNTIME_FOREGROUND_SOURCE_KEY = "runtime_foreground_source"
    private const val RUNTIME_FOREGROUND_UPDATED_AT_KEY = "runtime_foreground_updated_at"
    data class FreezeRuleConfig(
        val freezeProcesses: List<String>,
        val dontFreezeWhen: List<String>,
        val isWhitelist: Boolean
    )

    data class ForegroundState(
        val packageName: String,
        val source: String,
        val updatedAt: Long
    )

    // 运行时配置文件是 Hook 和 app 共用的桥，首次访问时会顺手补齐基础结构。
    fun ensureRuntimeConfig(context: Context): File {
        val deviceContext = context.createDeviceProtectedStorageContext()
        val target = File(deviceContext.filesDir, ASSET_NAME)
        if (!target.exists()) {
            target.parentFile?.mkdirs()
            context.assets.open(ASSET_NAME).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        ensureBuiltinWhitelist(target)
        return target
    }

    // 读取单应用冻结规则时统一走同一份 JSON，避免页面和 Hook 看到不同配置。
    fun loadRule(context: Context, packageName: String): FreezeRuleConfig? {
        val apps = loadAppsObject(context) ?: return null
        val rule = apps.optJSONObject(packageName) ?: return null
        return FreezeRuleConfig(
            freezeProcesses = rule.optJSONArray("freeze_processes").toStringList(),
            dontFreezeWhen = rule.optJSONArray("dont_freeze_when").toStringList(),
            isWhitelist = rule.optBoolean("whitelist", false)
        )
    }

    // 保存规则后会立刻同步到运行时镜像，让 system_server 侧尽快读到新配置。
    fun saveRule(
        context: Context,
        packageName: String,
        freezeProcesses: List<String>,
        dontFreezeWhen: List<String>,
        isWhitelist: Boolean = false
    ) {
        val file = ensureRuntimeConfig(context)
        val root = readRootObject(file)
        val apps = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
        val safeWhitelist = if (packageName == PACKAGE_NAME) true else isWhitelist
        val safeFreezeTargets = if (packageName == PACKAGE_NAME) listOf("ALL") else freezeProcesses
        val safeConditions = if (packageName == PACKAGE_NAME) emptyList() else dontFreezeWhen
        val rule = JSONObject().apply {
            put("freeze_processes", JSONArray(safeFreezeTargets))
            put("dont_freeze_when", JSONArray(safeConditions))
            put("whitelist", safeWhitelist)
        }
        apps.put(packageName, rule)
        file.writeText(root.toString(2), Charsets.UTF_8)
        DaemonConfigSynchronizer.submit(context)
        val mirrorOk = "queued"
        val keys = apps.keys().asSequence().asIterable().joinToString(",")
        Log.i(
            "Silence",
            "Silence|config|rule saved pkg=$packageName apps=${apps.length()} keys=$keys runtime=${file.absolutePath} mirror=$mirrorOk"
        )
    }

    // 白名单开关本质上也是规则修改，所以这里同样要走镜像同步链。
    fun setWhitelist(
        context: Context,
        packageName: String,
        enabled: Boolean
    ) {
        val current = loadRule(context, packageName)
        saveRule(
            context = context,
            packageName = packageName,
            freezeProcesses = current?.freezeProcesses ?: listOf("ALL"),
            dontFreezeWhen = current?.dontFreezeWhen ?: emptyList(),
            isWhitelist = enabled
        )
    }

    fun runtimeConfigPath(): String {
        return "/data/user_de/0/$PACKAGE_NAME/files/$ASSET_NAME"
    }

    fun runtimeSettingsPath(): String {
        return "/data/user_de/0/$PACKAGE_NAME/shared_prefs/silence_settings.xml"
    }

    fun runtimeMirrorDirPath(): String = MIRROR_DIR

    fun runtimeMirrorConfigPath(): String = "$MIRROR_DIR/$MIRROR_CONFIG_NAME"

    fun runtimeMirrorSettingsPath(): String = "$MIRROR_DIR/$MIRROR_SETTINGS_NAME"

    fun runtimeForcePollPropertyKey(): String = FORCE_POLL_PROP

    fun runtimeGlobalRulesKey(): String = GLOBAL_RULES_KEY

    fun runtimeGlobalHookPollIntervalKey(): String = GLOBAL_HOOK_POLL_INTERVAL_KEY

    fun runtimeGlobalHookEnabledKey(): String = GLOBAL_HOOK_ENABLED_KEY

    fun runtimeGlobalFreezeHookEnabledKey(): String = GLOBAL_FREEZE_HOOK_ENABLED_KEY

    fun runtimeGlobalPerformanceHookEnabledKey(): String = GLOBAL_PERFORMANCE_HOOK_ENABLED_KEY

    fun runtimeGlobalProcessDebugLogEnabledKey(): String = GLOBAL_PROCESS_DEBUG_LOG_ENABLED_KEY

    fun runtimeGlobalProcessPriorityRulesKey(): String = GLOBAL_PROCESS_PRIORITY_RULES_KEY

    fun runtimeGlobalForegroundPackageKey(): String = GLOBAL_FOREGROUND_PACKAGE_KEY

    fun runtimeGlobalForegroundSourceKey(): String = GLOBAL_FOREGROUND_SOURCE_KEY

    fun runtimeGlobalForegroundUpdatedAtKey(): String = GLOBAL_FOREGROUND_UPDATED_AT_KEY

    // 前台状态会同时写入本地偏好和时间戳，供通知、页面和日志共用。
    fun writeForegroundState(
        context: Context,
        packageName: String,
        source: String,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        val normalizedPackage = packageName.trim()
        if (normalizedPackage.isEmpty()) {
            clearForegroundState(context)
            return
        }
        foregroundStatePrefs(context)
            .edit()
            .putString(RUNTIME_FOREGROUND_PACKAGE_KEY, normalizedPackage)
            .putString(RUNTIME_FOREGROUND_SOURCE_KEY, source.trim())
            .putLong(RUNTIME_FOREGROUND_UPDATED_AT_KEY, updatedAt)
            .apply()
    }

    fun clearForegroundState(context: Context) {
        foregroundStatePrefs(context)
            .edit()
            .remove(RUNTIME_FOREGROUND_PACKAGE_KEY)
            .remove(RUNTIME_FOREGROUND_SOURCE_KEY)
            .remove(RUNTIME_FOREGROUND_UPDATED_AT_KEY)
            .apply()
    }

    // 前台包名只从 daemon 快照读取；App 侧不再重复执行 dumpsys、UsageStats 或读取 global。
    fun readForegroundState(context: Context): ForegroundState? {
        val snapshot = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            SilenceDaemonClient.latestSnapshot
        } else {
            SilenceDaemonClient.getSnapshot(context)
        } ?: return null
        val packageName = snapshot.foregroundPackageName ?: return null
        return ForegroundState(
            packageName = packageName,
            source = "daemon",
            updatedAt = snapshot.timestampEpochMs
        )
    }

    private fun foregroundStatePrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(RUNTIME_STATE_PREFS_NAME, Context.MODE_PRIVATE)

    // 这里负责把规则和开关镜像到 Hook 可读的位置，是 app 和 system_server 的配置桥梁。
    fun syncRuntimeMirror(context: Context): Boolean {
        val root = readRootObject(ensureRuntimeConfig(context))
        val globals = JSONObject().apply {
            put(GLOBAL_HOOK_POLL_INTERVAL_KEY, SettingsStore.getHookPollIntervalSeconds(context).toString())
            put(GLOBAL_HOOK_ENABLED_KEY, if (SettingsStore.isHookEnabled(context)) "1" else "0")
            put(GLOBAL_FREEZE_HOOK_ENABLED_KEY, if (SettingsStore.isFreezeHookEnabled(context)) "1" else "0")
            put(GLOBAL_PERFORMANCE_HOOK_ENABLED_KEY, if (SettingsStore.isPerformanceHookEnabled(context)) "1" else "0")
            put(GLOBAL_PROCESS_DEBUG_LOG_ENABLED_KEY, if (SettingsStore.isProcessDebugLogEnabled(context)) "1" else "0")
            put(GLOBAL_PROCESS_PRIORITY_RULES_KEY, ProcessPriorityRuleStore.encodeRuntimeRules(context))
        }
        val performance = cn.himpqblog.silence.perf.PerformanceConfigStore.readConfig(context)
        fun token(mode: cn.himpqblog.silence.perf.PerformanceMode) = when (mode.code) {
            0 -> "powersave"
            1 -> "balance"
            2 -> "performance"
            else -> "fast"
        }
        val externalModes = JSONObject()
        performance.packageModes.forEach { (packageName, mode) -> externalModes.put(packageName, token(mode)) }
        val config = JSONObject().apply {
            put("externalDefault", token(performance.defaultMode))
            put("externalModes", externalModes)
            put("rules", root)
            put("globals", globals)
            put("externalPath", if (SettingsStore.isExternalPerformanceModeEnabled(context)) SettingsStore.getExternalPerformanceModePath(context) else "")
        }
        val result = DaemonControlClient.request(JSONObject().put("command", "SYNC_CONFIG").put("config", config),
            uid = context.applicationInfo.uid, timeoutMs = 3000)
        val success = result?.optBoolean("success") == true
        Log.i(TAG, "Silence|config|daemon sync success=$success version=${result?.optLong("version", -1)} changed=${result?.optBoolean("changed")} detail=${result?.optString("detail") ?: "daemon_unavailable"}")
        return success
    }

    fun writeForcePollTriggerToken(token: String): Boolean {
        require(token.isNotBlank())
        return DaemonControlClient.request(JSONObject().put("command", "FORCE_POLL"))?.optBoolean("success") == true
    }

    private fun loadAppsObject(context: Context): JSONObject? {
        val file = ensureRuntimeConfig(context)
        return readRootObject(file).optJSONObject("apps")
    }

    private fun readRootObject(file: File): JSONObject {
        if (!file.exists()) {
            return JSONObject().put("apps", JSONObject())
        }
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) {
            return JSONObject().put("apps", JSONObject())
        }
        return runCatching { JSONObject(text) }
            .getOrElse { JSONObject().put("apps", JSONObject()) }
    }

    private fun ensureBuiltinWhitelist(file: File) {
        val root = readRootObject(file)
        val apps = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
        val current = apps.optJSONObject(PACKAGE_NAME)
        val hasWhitelist = current?.optBoolean("whitelist", false) == true
        val hasTargets = current?.optJSONArray("freeze_processes")?.length()?.let { it > 0 } == true
        if (hasWhitelist && hasTargets) {
            return
        }
        val whitelistRule = JSONObject().apply {
            put("freeze_processes", JSONArray(listOf("ALL")))
            put("dont_freeze_when", JSONArray())
            put("whitelist", true)
        }
        apps.put(PACKAGE_NAME, whitelistRule)
        file.writeText(root.toString(2), Charsets.UTF_8)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
