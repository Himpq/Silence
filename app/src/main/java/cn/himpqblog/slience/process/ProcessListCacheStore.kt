package cn.himpqblog.slience.process

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProcessListCacheStore {

    private const val FILE_NAME = "process_list_cache.json"

    fun write(context: Context, items: List<ProcessAppItem>) {
        val root = JSONObject().apply {
            put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("packageName", item.packageName)
                        put("appName", item.appName)
                        put("uid", item.uid)
                        put("processCount", item.processCount)
                        put("frozenProcessCount", item.frozenProcessCount)
                        put("processNames", JSONArray(item.processNames))
                        put("frozenProcessNames", JSONArray(item.frozenProcessNames.toList()))
                        put("childProcessNames", JSONArray(item.childProcessNames))
                        put("isFrozen", item.isFrozen)
                        put("freezeMode", item.freezeMode)
                        put("memoryBytes", item.memoryBytes)
                        put("cpuPercent", item.cpuPercent)
                    })
                }
            })
        }
        cacheFile(context).writeText(root.toString(), Charsets.UTF_8)
    }

    fun read(context: Context): List<ProcessAppItem> {
        val file = cacheFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("items") ?: return emptyList()
        val pm = context.packageManager
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val packageName = item.optString("packageName").trim()
                if (packageName.isEmpty()) {
                    continue
                }
                val icon = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
                add(
                    ProcessAppItem(
                        packageName = packageName,
                        appName = item.optString("appName", packageName),
                        icon = icon,
                        uid = item.optInt("uid"),
                        processCount = item.optInt("processCount"),
                        frozenProcessCount = item.optInt("frozenProcessCount"),
                        processNames = item.optJSONArray("processNames").toStringList(),
                        frozenProcessNames = item.optJSONArray("frozenProcessNames").toStringList().toSet(),
                        childProcessNames = item.optJSONArray("childProcessNames").toStringList(),
                        processEntries = emptyList(),
                        isFrozen = item.optBoolean("isFrozen"),
                        freezeMode = item.optString("freezeMode", "V2"),
                        memoryBytes = item.optLong("memoryBytes"),
                        cpuPercent = item.optDouble("cpuPercent")
                    )
                )
            }
        }
    }

    private fun cacheFile(context: Context): File {
        return context.createDeviceProtectedStorageContext().getFileStreamPath(FILE_NAME)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) {
            return emptyList()
        }
        return buildList {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    }
}
