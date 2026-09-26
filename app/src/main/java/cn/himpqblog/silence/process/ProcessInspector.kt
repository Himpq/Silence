package cn.himpqblog.silence.process

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import cn.himpqblog.silence.daemon.DaemonControlClient
import cn.himpqblog.silence.hook.RuntimeLogStore
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** App-side presentation only. Process access and mutation belong to daemon. */
object ProcessInspector {
    data class FreezeCommandResult(val success: Boolean, val detail: String)
    data class OomScoreAdjResult(val success: Boolean, val before: Int?, val after: Int?, val detail: String)
    private data class Meta(val packageName: String, val label: String, val icon: Drawable?, val system: Boolean)
    private val metadata = ConcurrentHashMap<String, Meta>()
    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun applyFreezeCommand(context: Context, packageName: String, freeze: Boolean, targetNames: Set<String>): Boolean =
        applyFreezeCommandDetailed(context, packageName, freeze, targetNames).success

    fun applyFreezeCommandDetailed(context: Context, packageName: String, freeze: Boolean, targetNames: Set<String>): FreezeCommandResult {
        val uid = runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
            ?: return FreezeCommandResult(false, "package_not_found")
        return freezeResult(DaemonControlClient.freeze(packageName, uid, freeze, targetNames), packageName, freeze)
    }

    private fun freezeResult(response: JSONObject?, packageName: String, frozen: Boolean): FreezeCommandResult {
        val success = response?.optBoolean("success") == true
        val detail = response?.optString("detail")?.takeIf { it.isNotBlank() } ?: "daemon_unavailable"
        RuntimeLogStore.appendDiagnostic("process", "freeze package=$packageName frozen=$frozen success=$success detail=$detail",
            category = if (success) RuntimeLogStore.LogCategory.LOG else RuntimeLogStore.LogCategory.ERROR)
        return FreezeCommandResult(success, detail)
    }

    fun toggleFreeze(item: ProcessAppItem, targetNames: Set<String> = emptySet()): Boolean =
        freezeResult(DaemonControlClient.freeze(item.packageName, item.uid, !item.isFrozen, targetNames), item.packageName, !item.isFrozen).success

    fun collectCurrentEntriesForPackage(item: ProcessAppItem): List<ProcessEntry> {
        val response = DaemonControlClient.processes(item.packageName, item.uid)
        if (response?.optBoolean("success") != true) throw IllegalStateException(response?.optString("detail") ?: "daemon_unavailable")
        val rows = response.getJSONArray("processes")
        return (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            val name = row.getString("name")
            ProcessEntry(row.getInt("pid"), name.substringAfter(':', "main"), row.getBoolean("frozen"), name)
        }
    }

    fun readOomScoreAdj(item: ProcessAppItem, entry: ProcessEntry): OomScoreAdjResult = oom(item, entry, null)
    fun setOomScoreAdjOnce(item: ProcessAppItem, entry: ProcessEntry, requestedAdj: Int): OomScoreAdjResult {
        require(requestedAdj in -1000..1000)
        return oom(item, entry, requestedAdj)
    }
    private fun oom(item: ProcessAppItem, entry: ProcessEntry, adj: Int?): OomScoreAdjResult {
        val response = DaemonControlClient.request(JSONObject().apply {
            put("command", "OOM"); put("package", item.packageName); put("uid", item.uid)
            put("pid", entry.pid); put("processName", entry.processName)
            if (adj != null) put("adj", adj)
        })
        return OomScoreAdjResult(response?.optBoolean("success") == true,
            response?.takeIf { it.has("before") }?.getInt("before"), response?.takeIf { it.has("after") }?.getInt("after"),
            response?.optString("detail") ?: "daemon_unavailable")
    }

    fun inspectRuntimeState(context: Context, item: ProcessAppItem): AppRuntimeState {
        val response = DaemonControlClient.request(JSONObject().put("command", "GET_RUNTIME").put("package", item.packageName), uid = context.applicationInfo.uid)
        val state = response?.optJSONObject("state")
        if (response?.optBoolean("known") != true || state == null || !state.optBoolean("known")) {
            return AppRuntimeState(false, false, false, isKnown = false)
        }
        return AppRuntimeState(state.optBoolean("audio"), state.optBoolean("network"), state.optBoolean("visible"), state.optBoolean("foreground"))
    }

    fun collect(context: Context, previous: CpuSnapshot?): ProcessCollectResult {
        val started = SystemClock.elapsedRealtime()
        val response = DaemonControlClient.processes()
        if (response?.optBoolean("success") != true) return ProcessCollectResult(emptyList(), null, response?.optString("detail") ?: "daemon_unavailable")
        return runCatching {
            val array = response.getJSONArray("processes")
            val rows = (0 until array.length()).map { array.getJSONObject(it) }
            val total = response.getLong("totalTicks")
            val snapshot = CpuSnapshot(total, rows.associate { it.getInt("pid") to it.getLong("ticks") },
                rows.associate { it.getInt("pid") to it.getLong("startTicks") })
            val managedArray = response.getJSONArray("managedPackages")
            val managed = (0 until managedArray.length()).map { managedArray.getString(it) }.toSet()
            val resolved = rows.mapNotNull { row ->
                val uid = row.getInt("uid")
                val hint = row.getString("name").substringBefore(':')
                val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
                val packageName = packages.firstOrNull { it == hint } ?: packages.singleOrNull() ?: return@mapNotNull null
                val meta = meta(context.packageManager, packageName) ?: return@mapNotNull null
                if (meta.system) null else meta to row
            }
            val items = resolved.groupBy { it.first.packageName }.map { (_, group) ->
                val meta = group.first().first
                val processRows = group.map { it.second }
                val entries = processRows.map { row ->
                    val name = row.getString("name")
                    ProcessEntry(row.getInt("pid"), name.substringAfter(':', "main"), row.getBoolean("frozen"), name)
                }.sortedWith(compareBy<ProcessEntry> { if (it.displayName == "main") 0 else 1 }.thenBy { it.displayName }.thenBy { it.pid })
                val frozen = entries.any { it.isFrozen }
                val delta = if (previous == null) 0L else processRows.sumOf { row ->
                    val pid = row.getInt("pid")
                    if (previous.pidStartTicks[pid] != row.getLong("startTicks")) 0L
                    else max(0L, row.getLong("ticks") - (previous.pidTicks[pid] ?: row.getLong("ticks")))
                }
                val percent = if (previous == null || total <= previous.totalTicks) 0.0 else delta * 100.0 * coreCount / (total - previous.totalTicks)
                ProcessAppItem(meta.packageName, meta.label, meta.icon, processRows.first().getInt("uid"), entries.size,
                    entries.count { it.isFrozen }, entries.map { it.displayName }.distinct(), entries.filter { it.isFrozen }.map { it.displayName }.toSet(),
                    entries.map { "${it.displayName}(${it.pid})" }, entries, frozen, if (!frozen || meta.packageName in managed) "V2" else "SYSTEM_V2",
                    processRows.sumOf { it.getLong("rssKb") } * 1024L, percent)
            }.sortedWith(compareByDescending<ProcessAppItem> { it.memoryBytes }.thenByDescending { it.cpuPercent }.thenBy { it.appName.lowercase(Locale.getDefault()) })
            if (SystemClock.elapsedRealtime() - started >= 100) RuntimeLogStore.appendDiagnostic("process-perf", "collect wallMs=${SystemClock.elapsedRealtime() - started} processes=${rows.size} apps=${items.size}", "process_collect", 10000)
            ProcessCollectResult(items, snapshot)
        }.getOrElse { ProcessCollectResult(emptyList(), null, "invalid_daemon_process_response:${it.message}") }
    }

    private fun meta(pm: PackageManager, packageName: String): Meta? = metadata[packageName] ?: runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        Meta(packageName, pm.getApplicationLabel(info).toString(), pm.getApplicationIcon(info),
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
            .also { metadata[packageName] = it }
    }.getOrNull()
}
