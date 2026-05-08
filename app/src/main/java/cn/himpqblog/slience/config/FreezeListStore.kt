package cn.himpqblog.slience.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object FreezeListStore {

    private const val ASSET_NAME = "FreezeList.json"
    private const val PACKAGE_NAME = "cn.himpqblog.slience"

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
        val rule = JSONObject().apply {
            put("freeze_processes", JSONArray(freezeProcesses))
            put("dont_freeze_when", JSONArray(dontFreezeWhen))
            put("whitelist", isWhitelist)
        }
        apps.put(packageName, rule)
        file.writeText(root.toString(2), Charsets.UTF_8)
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
