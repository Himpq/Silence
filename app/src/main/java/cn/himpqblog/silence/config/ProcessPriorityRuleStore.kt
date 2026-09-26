package cn.himpqblog.silence.config

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProcessPriorityRuleStore {
    const val PROTECTED_MAX_ADJ = 250

    private const val FILE_NAME = "ProcessPriorityRules.json"
    private const val VERSION = 1
    private const val TAG = "Silence"

    data class Rule(
        val uid: Int,
        val packageName: String,
        val processName: String,
        val maxAdj: Int = PROTECTED_MAX_ADJ
    )

    fun loadRules(context: Context): List<Rule> {
        val target = ruleFile(context)
        if (!target.exists()) return emptyList()

        return runCatching {
            val raw = AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            require(root.getInt("version") == VERSION) { "unsupported priority rule version" }
            val array = root.getJSONArray("rules")
            buildList {
                for (index in 0 until array.length()) {
                    val entry = array.getJSONObject(index)
                    val rule = Rule(
                        uid = entry.getInt("uid"),
                        packageName = entry.getString("package_name").trim(),
                        processName = entry.getString("process_name").trim(),
                        maxAdj = entry.getInt("max_adj")
                    )
                    require(rule.uid >= 10000) { "invalid uid=${rule.uid}" }
                    require(rule.packageName.isNotBlank()) { "empty package name" }
                    require(rule.processName.isNotBlank()) { "empty process name" }
                    require(rule.maxAdj in -1000..1000) { "invalid max_adj=${rule.maxAdj}" }
                    add(rule)
                }
            }
                .distinctBy { Triple(it.uid, it.packageName, it.processName) }
        }.onFailure { error ->
            Log.e(
                TAG,
                "Silence|oom-adj|rule file rejected path=${target.absolutePath}: ${error.javaClass.simpleName}:${error.message}"
            )
        }.getOrThrow()
    }

    fun isEnabled(context: Context, uid: Int, packageName: String, processName: String): Boolean =
        loadRules(context).any {
            it.uid == uid && it.packageName == packageName && it.processName == processName
        }

    fun migrateTruncatedProcessNames(
        context: Context,
        uid: Int,
        packageName: String,
        currentProcessNames: List<String>
    ): Boolean {
        val current = loadRules(context)
        val validNames = currentProcessNames.distinct().filter {
            it == packageName || it.startsWith("$packageName:")
        }
        var migratedCount = 0
        val migratedNames = mutableListOf<String>()
        val updated = current.map { rule ->
            if (rule.uid != uid || rule.packageName != packageName || rule.processName.length != 15) {
                return@map rule
            }
            val candidates = validNames.filter {
                it.length > rule.processName.length && it.endsWith(rule.processName)
            }
            if (candidates.size != 1) return@map rule
            migratedCount += 1
            migratedNames += "${rule.processName}->${candidates.single()}"
            rule.copy(processName = candidates.single())
        }.distinctBy { Triple(it.uid, it.packageName, it.processName) }
            .sortedWith(compareBy<Rule>({ it.packageName }, { it.processName }, { it.uid }))
        if (migratedCount == 0 || updated == current) return false

        writeRules(context, updated)
        Log.i(
            TAG,
            "Silence|oom-adj|truncated process rules migrated uid=$uid package=$packageName count=$migratedCount names=${migratedNames.joinToString(",")}"
        )
        return true
    }

    fun setEnabled(
        context: Context,
        uid: Int,
        packageName: String,
        processName: String,
        enabled: Boolean
    ): List<Rule> {
        require(uid >= 10000) { "invalid uid=$uid" }
        require(packageName.isNotBlank()) { "empty package name" }
        require(processName.isNotBlank()) { "empty process name" }

        val identity = Triple(uid, packageName, processName)
        val current = loadRules(context)
        val updated = current
            .filterNot { Triple(it.uid, it.packageName, it.processName) == identity }
            .toMutableList()
        if (enabled) {
            updated += Rule(uid, packageName, processName)
        }
        val normalized = updated.sortedWith(
            compareBy<Rule>({ it.packageName }, { it.processName }, { it.uid })
        )
        writeRules(context, normalized)
        Log.i(
            TAG,
            "Silence|oom-adj|rule saved enabled=$enabled uid=$uid package=$packageName process=$processName maxAdj=$PROTECTED_MAX_ADJ count=${normalized.size}"
        )
        return normalized
    }

    fun encodeRuntimeRules(context: Context): String {
        val rules = loadRules(context)
        val root = JSONObject().put("version", VERSION).put(
            "rules",
            JSONArray().apply {
                rules.forEach { rule ->
                    put(
                        JSONObject()
                            .put("uid", rule.uid)
                            .put("package_name", rule.packageName)
                            .put("process_name", rule.processName)
                            .put("max_adj", rule.maxAdj)
                    )
                }
            }
        )
        return Base64.encodeToString(root.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun writeRules(context: Context, rules: List<Rule>) {
        val root = JSONObject().put("version", VERSION).put(
            "rules",
            JSONArray().apply {
                rules.forEach { rule ->
                    put(
                        JSONObject()
                            .put("uid", rule.uid)
                            .put("package_name", rule.packageName)
                            .put("process_name", rule.processName)
                            .put("max_adj", rule.maxAdj)
                    )
                }
            }
        )
        val atomicFile = AtomicFile(ruleFile(context))
        var output: java.io.FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(root.toString(2).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            if (output != null) atomicFile.failWrite(output)
            throw error
        }
    }

    private fun ruleFile(context: Context): File = File(
        context.createDeviceProtectedStorageContext().filesDir,
        FILE_NAME
    )
}
