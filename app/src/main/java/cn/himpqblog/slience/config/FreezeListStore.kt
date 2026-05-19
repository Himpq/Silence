package cn.himpqblog.slience.config

import android.content.Context
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.provider.Settings
import android.os.Process
import android.util.Log
import android.util.Base64
import com.topjohnwu.superuser.Shell
import cn.himpqblog.slience.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FreezeListStore {

    private const val TAG = "Silence"

    private const val ASSET_NAME = "FreezeList.json"
    private const val PACKAGE_NAME = "cn.himpqblog.slience"
    private const val MIRROR_DIR = "/data/media/0/Android/data/$PACKAGE_NAME/data"
    private const val MIRROR_CONFIG_NAME = "FreezeList.runtime.json"
    private const val MIRROR_SETTINGS_NAME = "silence_settings.runtime.xml"
    private const val RUNTIME_STATE_PREFS_NAME = "silence_runtime_state"
    private const val FORCE_POLL_PROP = "persist.silence.force_poll"
    private const val GLOBAL_RULES_KEY = "silence_freeze_rules_json_b64"
    private const val GLOBAL_HOOK_POLL_INTERVAL_KEY = "silence_hook_poll_interval_seconds"
    private const val GLOBAL_HOOK_ENABLED_KEY = "silence_hook_enabled"
    private const val GLOBAL_PROCESS_DEBUG_LOG_ENABLED_KEY = "silence_process_debug_log_enabled"
    private const val GLOBAL_FOREGROUND_PACKAGE_KEY = "silence_foreground_package"
    private const val GLOBAL_FOREGROUND_SOURCE_KEY = "silence_foreground_source"
    private const val GLOBAL_FOREGROUND_UPDATED_AT_KEY = "silence_foreground_updated_at"
    private const val RUNTIME_FOREGROUND_PACKAGE_KEY = "runtime_foreground_package"
    private const val RUNTIME_FOREGROUND_SOURCE_KEY = "runtime_foreground_source"
    private const val RUNTIME_FOREGROUND_UPDATED_AT_KEY = "runtime_foreground_updated_at"
    private const val USAGE_STATS_LOOKBACK_MS = 12L * 60L * 60L * 1000L
    private const val ACTIVITY_RESUME_CACHE_MS = 1500L
    private const val WINDOW_FOCUS_CACHE_MS = 1500L
    private val WINDOW_FOCUS_PACKAGE_REGEX = Regex("""([A-Za-z0-9_.]+)/(?:[A-Za-z0-9_.$]+)""")

    @Volatile
    private var cachedActivityResumeState: ForegroundState? = null

    @Volatile
    private var cachedActivityResumeAt = 0L

    @Volatile
    private var cachedWindowFocusState: ForegroundState? = null

    @Volatile
    private var cachedWindowFocusAt = 0L

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

    fun loadRule(context: Context, packageName: String): FreezeRuleConfig? {
        val apps = loadAppsObject(context) ?: return null
        val rule = apps.optJSONObject(packageName) ?: return null
        return FreezeRuleConfig(
            freezeProcesses = rule.optJSONArray("freeze_processes").toStringList(),
            dontFreezeWhen = rule.optJSONArray("dont_freeze_when").toStringList(),
            isWhitelist = rule.optBoolean("whitelist", false)
        )
    }

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
        val mirrorOk = syncRuntimeMirror(context)
        val keys = apps.keys().asSequence().asIterable().joinToString(",")
        Log.i(
            "Silence",
            "Silence|config|rule saved pkg=$packageName apps=${apps.length()} keys=$keys runtime=${file.absolutePath} mirror=$mirrorOk"
        )
    }

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

    fun runtimeGlobalProcessDebugLogEnabledKey(): String = GLOBAL_PROCESS_DEBUG_LOG_ENABLED_KEY

    fun runtimeGlobalForegroundPackageKey(): String = GLOBAL_FOREGROUND_PACKAGE_KEY

    fun runtimeGlobalForegroundSourceKey(): String = GLOBAL_FOREGROUND_SOURCE_KEY

    fun runtimeGlobalForegroundUpdatedAtKey(): String = GLOBAL_FOREGROUND_UPDATED_AT_KEY

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

    fun readForegroundState(context: Context): ForegroundState? {
        readForegroundStateFromActivityResume()?.let { return it }
        readForegroundStateFromWindowFocus()?.let { return it }
        val prefsState = readForegroundStateFromPrefs(context)
        val usageStatsState = readForegroundStateFromUsageStats(context)
        val globalState = readForegroundStateFromGlobalSettings(context)
        return when {
            prefsState == null && usageStatsState == null -> globalState
            prefsState == null && globalState == null -> usageStatsState
            usageStatsState == null && globalState == null -> prefsState
            else -> listOfNotNull(prefsState, usageStatsState, globalState).maxByOrNull { it.updatedAt }
        }
    }

    fun hasUsageStatsAccess(context: Context): Boolean {
        val appOpsManager = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun readForegroundStateFromUsageStats(context: Context): ForegroundState? {
        if (!hasUsageStatsAccess(context)) {
            return null
        }
        val usageStatsManager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val endTime = System.currentTimeMillis()
        val beginTime = (endTime - USAGE_STATS_LOOKBACK_MS).coerceAtLeast(0L)
        val usageEvents = usageStatsManager.queryEvents(beginTime, endTime)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = 0L
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val isForegroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (!isForegroundEvent) {
                continue
            }
            val packageName = event.packageName?.trim().orEmpty()
            if (packageName.isEmpty()) {
                continue
            }
            latestPackage = packageName
            latestTimestamp = event.timeStamp
        }
        if (latestPackage.isNullOrEmpty()) {
            return null
        }
        return ForegroundState(
            packageName = latestPackage,
            source = "usage_stats",
            updatedAt = latestTimestamp
        )
    }

    private fun readForegroundStateFromPrefs(context: Context): ForegroundState? {
        val prefs = foregroundStatePrefs(context)
        val packageName = prefs.getString(RUNTIME_FOREGROUND_PACKAGE_KEY, null)?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return null
        }
        val source = prefs.getString(RUNTIME_FOREGROUND_SOURCE_KEY, null)?.trim().orEmpty()
        val updatedAt = prefs.getLong(RUNTIME_FOREGROUND_UPDATED_AT_KEY, 0L)
        return ForegroundState(
            packageName = packageName,
            source = source,
            updatedAt = updatedAt
        )
    }

    private fun readForegroundStateFromGlobalSettings(context: Context): ForegroundState? {
        val resolver = context.contentResolver
        val packageName = runCatching {
            Settings.Global.getString(resolver, GLOBAL_FOREGROUND_PACKAGE_KEY)
        }.getOrNull()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return null
        }
        val source = runCatching {
            Settings.Global.getString(resolver, GLOBAL_FOREGROUND_SOURCE_KEY)
        }.getOrNull()?.trim().orEmpty()
        val updatedAt = runCatching {
            Settings.Global.getLong(resolver, GLOBAL_FOREGROUND_UPDATED_AT_KEY)
        }.getOrDefault(0L)
        return ForegroundState(
            packageName = packageName,
            source = source,
            updatedAt = updatedAt
        )
    }

    private fun readForegroundStateFromWindowFocus(): ForegroundState? {
        val now = System.currentTimeMillis()
        val cached = cachedWindowFocusState
        if (cached != null && now - cachedWindowFocusAt <= WINDOW_FOCUS_CACHE_MS) {
            return cached
        }
        val command = """
            dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus=|mFocusedApp=|mResumeActivity:|ResumedActivity:' | tail -n 8
        """.trimIndent()
        val result = runCatching { Shell.cmd(command).exec() }.getOrNull() ?: return cached
        if (!result.isSuccess) {
            return cached
        }
        val resolved = result.out
            .asSequence()
            .mapNotNull { line -> extractPackageNameFromWindowFocusLine(line) }
            .firstOrNull()
            ?.let { packageName ->
                ForegroundState(
                    packageName = packageName,
                    source = "window_focus",
                    updatedAt = now
                )
            }
        if (resolved != null) {
            cachedWindowFocusState = resolved
            cachedWindowFocusAt = now
            return resolved
        }
        return cached
    }

    private fun readForegroundStateFromActivityResume(): ForegroundState? {
        val now = System.currentTimeMillis()
        val cached = cachedActivityResumeState
        if (cached != null && now - cachedActivityResumeAt <= ACTIVITY_RESUME_CACHE_MS) {
            return cached
        }
        val command = """
            dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity=|ResumedActivity:|mResumedActivity:' | tail -n 8
        """.trimIndent()
        val result = runCatching { Shell.cmd(command).exec() }.getOrNull() ?: return cached
        if (!result.isSuccess) {
            return cached
        }
        val resolved = result.out
            .asSequence()
            .mapNotNull { line -> extractPackageNameFromWindowFocusLine(line) }
            .lastOrNull()
            ?.let { packageName ->
                ForegroundState(
                    packageName = packageName,
                    source = "activity_resumed",
                    updatedAt = now
                )
            }
        if (resolved != null) {
            cachedActivityResumeState = resolved
            cachedActivityResumeAt = now
            return resolved
        }
        return cached
    }

    private fun extractPackageNameFromWindowFocusLine(line: String): String? {
        val normalized = line.trim()
        if (normalized.isEmpty()) {
            return null
        }
        val match = WINDOW_FOCUS_PACKAGE_REGEX.find(normalized) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun foregroundStatePrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(RUNTIME_STATE_PREFS_NAME, Context.MODE_PRIVATE)

    fun syncRuntimeMirror(context: Context): Boolean {
        val runtimeFile = ensureRuntimeConfig(context)
        val runtimeJson = runCatching { runtimeFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val runtimeJsonB64 = Base64.encodeToString(runtimeJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val hookPollSeconds = SettingsStore.getHookPollIntervalSeconds(context)
        val hookEnabled = if (SettingsStore.isHookEnabled(context)) 1 else 0
        val processDebugLogEnabled = if (SettingsStore.isProcessDebugLogEnabled(context)) 1 else 0
        val command = """
            mkdir -p "${runtimeMirrorDirPath()}" 2>/dev/null
            if [ -r "${runtimeConfigPath()}" ]; then cp "${runtimeConfigPath()}" "${runtimeMirrorConfigPath()}"; fi
            if [ -r "${runtimeSettingsPath()}" ]; then cp "${runtimeSettingsPath()}" "${runtimeMirrorSettingsPath()}"; fi
            chmod 0644 "${runtimeMirrorConfigPath()}" 2>/dev/null
            chmod 0644 "${runtimeMirrorSettingsPath()}" 2>/dev/null
            settings put global "${runtimeGlobalRulesKey()}" '$runtimeJsonB64'
            settings put global "${runtimeGlobalHookPollIntervalKey()}" "$hookPollSeconds"
            settings put global "${runtimeGlobalHookEnabledKey()}" "$hookEnabled"
            settings put global "${runtimeGlobalProcessDebugLogEnabledKey()}" "$processDebugLogEnabled"
            if [ -f "${runtimeMirrorConfigPath()}" ]; then
                echo "config_exists=1 size=$(wc -c < "${runtimeMirrorConfigPath()}") path=${runtimeMirrorConfigPath()}"
            else
                echo "config_exists=0 path=${runtimeMirrorConfigPath()}"
            fi
            if [ -f "${runtimeMirrorSettingsPath()}" ]; then
                echo "settings_exists=1 size=$(wc -c < "${runtimeMirrorSettingsPath()}") path=${runtimeMirrorSettingsPath()}"
            else
                echo "settings_exists=0 path=${runtimeMirrorSettingsPath()}"
            fi
            echo "global_rules_len=$(settings get global "${runtimeGlobalRulesKey()}" | wc -c)"
            echo "global_poll=$(settings get global "${runtimeGlobalHookPollIntervalKey()}")"
            echo "global_hook_enabled=$(settings get global "${runtimeGlobalHookEnabledKey()}")"
            echo "global_process_debug_log_enabled=$(settings get global "${runtimeGlobalProcessDebugLogEnabledKey()}")"
        """.trimIndent()
        return runCatching {
            val result = Shell.cmd(command).exec()
            Log.i(
                "Silence",
                "Silence|config|mirror sync success=${result.isSuccess} probe=${result.out.joinToString(" | ").ifEmpty { "empty" }}"
            )
            result.isSuccess
        }.getOrDefault(false)
    }

    fun writeForcePollTriggerToken(token: String): Boolean {
        val safeToken = token.replace("'", "")
        val command = """
            setprop "${runtimeForcePollPropertyKey()}" "$safeToken"
        """.trimIndent()
        return runCatching {
            Shell.cmd(command).exec().isSuccess
        }.getOrDefault(false)
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
