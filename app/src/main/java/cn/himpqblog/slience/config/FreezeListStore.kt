package cn.himpqblog.slience.config

import android.content.Context
import android.util.Log
import android.util.Base64
import com.topjohnwu.superuser.Shell
import cn.himpqblog.slience.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FreezeListStore {

    private const val ASSET_NAME = "FreezeList.json"
    private const val PACKAGE_NAME = "cn.himpqblog.slience"
    private const val MIRROR_DIR = "/data/media/0/Android/data/$PACKAGE_NAME/data"
    private const val MIRROR_CONFIG_NAME = "FreezeList.runtime.json"
    private const val MIRROR_SETTINGS_NAME = "silence_settings.runtime.xml"
    private const val FORCE_POLL_PROP = "persist.silence.force_poll"
    private const val GLOBAL_RULES_KEY = "silence_freeze_rules_json_b64"
    private const val GLOBAL_HOOK_POLL_INTERVAL_KEY = "silence_hook_poll_interval_seconds"

    data class FreezeRuleConfig(
        val freezeProcesses: List<String>,
        val dontFreezeWhen: List<String>,
        val isWhitelist: Boolean
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

    fun syncRuntimeMirror(context: Context): Boolean {
        val runtimeFile = ensureRuntimeConfig(context)
        val runtimeJson = runCatching { runtimeFile.readText(Charsets.UTF_8) }.getOrDefault("")
        val runtimeJsonB64 = Base64.encodeToString(runtimeJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val hookPollSeconds = SettingsStore.getHookPollIntervalSeconds(context)
        val command = """
            mkdir -p "${runtimeMirrorDirPath()}" 2>/dev/null
            if [ -r "${runtimeConfigPath()}" ]; then cp "${runtimeConfigPath()}" "${runtimeMirrorConfigPath()}"; fi
            if [ -r "${runtimeSettingsPath()}" ]; then cp "${runtimeSettingsPath()}" "${runtimeMirrorSettingsPath()}"; fi
            chmod 0644 "${runtimeMirrorConfigPath()}" 2>/dev/null
            chmod 0644 "${runtimeMirrorSettingsPath()}" 2>/dev/null
            settings put global "${runtimeGlobalRulesKey()}" '$runtimeJsonB64'
            settings put global "${runtimeGlobalHookPollIntervalKey()}" "$hookPollSeconds"
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
