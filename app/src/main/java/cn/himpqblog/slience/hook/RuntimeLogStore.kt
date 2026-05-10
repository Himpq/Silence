package cn.himpqblog.slience.hook

import android.util.Log
import cn.himpqblog.slience.settings.SettingsStore
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object RuntimeLogStore {

    enum class LogCategory {
        ERROR,
        LOG,
        ALL
    }

    private data class LogEntry(
        val line: String,
        val category: LogCategory
    )

    private val lock = Any()
    private val operationLogs = ArrayDeque<LogEntry>()
    private val seenDebugEvents = LinkedHashSet<String>()
    private val throttleMap = ConcurrentHashMap<String, Long>()
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile
    private var hookStatus: String = "Waiting for LSPosed to load in system_server"

    @Volatile
    private var recordMode: SettingsStore.LogRecordMode = SettingsStore.LogRecordMode.ALL

    private val bgPrefix = "进入后台"
    private val freezePrefix = "冻结"
    private val unfreezePrefix = "解冻"
    private val pollPrefix = "[轮询]"
    private val broadcastUnfreezePrefix = "广播触发解冻"
    private val broadcastDeliverPrefix = "广播继续分发"
    private const val holdFreezePrefix = "hold freeze "

    fun refreshFromRuntime() {
        val status = HookRuntime.inspect()
        hookStatus = status.summary

        synchronized(lock) {
            status.debugEvents.forEach { event ->
                if (!shouldKeepDebugEvent(event)) {
                    return@forEach
                }
                if (!seenDebugEvents.add(event)) {
                    return@forEach
                }
                if (!shouldShow(recordMode, LogCategory.LOG)) {
                    return@forEach
                }
                appendLineLocked(
                    source = null,
                    message = event,
                    category = LogCategory.LOG
                )
            }

            while (operationLogs.size > 500) {
                operationLogs.removeFirst()
            }
            while (seenDebugEvents.size > 2500) {
                val iterator = seenDebugEvents.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    fun snapshotHookStatus(): String = hookStatus

    fun setRecordMode(mode: SettingsStore.LogRecordMode) {
        recordMode = mode
    }

    fun snapshotLogs(mode: SettingsStore.LogRecordMode): List<String> = synchronized(lock) {
        operationLogs
            .filter { shouldShow(mode, it.category) }
            .map { it.line }
            .toList()
    }

    fun appendDiagnostic(
        source: String,
        message: String,
        throttleKey: String? = null,
        throttleMs: Long = 0L,
        category: LogCategory = LogCategory.ALL
    ) {
        if (throttleKey != null && throttleMs > 0L) {
            val now = System.currentTimeMillis()
            val previous = throttleMap[throttleKey] ?: 0L
            if (now - previous < throttleMs) {
                return
            }
            throttleMap[throttleKey] = now
        }

        // Always mirror diagnostic logs to Android Logcat so debugging is not blocked by UI log mode.
        Log.i("Silence", "Silence|$source|$message")

        if (!shouldShow(recordMode, category)) {
            return
        }

        synchronized(lock) {
            appendLineLocked(source, message, category)
            while (operationLogs.size > 500) {
                operationLogs.removeFirst()
            }
        }
    }

    fun clear() = synchronized(lock) {
        operationLogs.clear()
        seenDebugEvents.clear()
        throttleMap.clear()
    }

    private fun appendLineLocked(source: String?, message: String, category: LogCategory) {
        val timestamp = LocalTime.now().format(timeFormatter)
        val line = if (source.isNullOrEmpty()) {
            "[$timestamp] $message"
        } else {
            "[$timestamp] [$source] $message"
        }
        operationLogs.addLast(LogEntry(line, category))
    }

    private fun shouldKeepDebugEvent(event: String): Boolean {
        val trimmed = event.trim()
        return trimmed.startsWith(bgPrefix) ||
            trimmed.startsWith(freezePrefix) ||
            trimmed.startsWith(unfreezePrefix) ||
            trimmed.startsWith(pollPrefix) ||
            trimmed.startsWith(broadcastUnfreezePrefix) ||
            trimmed.startsWith(broadcastDeliverPrefix) ||
            trimmed.startsWith(holdFreezePrefix)
    }

    private fun shouldShow(mode: SettingsStore.LogRecordMode, category: LogCategory): Boolean {
        return when (mode) {
            SettingsStore.LogRecordMode.ERROR -> category == LogCategory.ERROR
            SettingsStore.LogRecordMode.LOG -> category == LogCategory.LOG || category == LogCategory.ERROR
            SettingsStore.LogRecordMode.ALL -> true
        }
    }
}
