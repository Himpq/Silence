package cn.himpqblog.silence.perf

import android.content.Context
import android.util.Log
import cn.himpqblog.silence.config.FreezeListStore
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import cn.himpqblog.silence.daemon.toPerformancePowerSnapshot
import cn.himpqblog.silence.settings.SettingsStore
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CpuGroupFrequencies(
    val group1Ghz: Float,
    val group2Ghz: Float,
    val group3Ghz: Float
)

data class CpuCoreFrequency(
    val coreIndex: Int,
    val currentMhz: Int,
    val minMhz: Int,
    val maxMhz: Int,
    val availableFrequenciesMhz: List<Int>,
    val usagePercent: Float? = null
)

data class CpuCoreFrequencySnapshot(
    val cores: List<CpuCoreFrequency>
)

enum class PerformanceLogEntryType {
    SAMPLE,
    EVENT
}

data class PerformanceLogRecord(
    val recordedAtEpochMs: Long? = null,
    val entryType: PerformanceLogEntryType = PerformanceLogEntryType.SAMPLE,
    val cpuGroup1Ghz: Float? = null,
    val cpuGroup2Ghz: Float? = null,
    val cpuGroup3Ghz: Float? = null,
    val foregroundPackageName: String? = null,
    val powerW: Float? = null,
    val performanceGear: Int? = null,
    val batteryLevelPercent: Int? = null,
    val eventName: String? = null,
    val eventOldValue: String? = null,
    val eventNewValue: String? = null,
    val eventDetail: String? = null
)

data class PerformanceLogFileSummary(
    val fileName: String,
    val dateLabel: String,
    val recordCount: Int,
    val firstRecordedAt: Long?,
    val lastModifiedAt: Long,
    val fileSizeBytes: Long
)

data class PerformanceTrendPoint(
    val timestampEpochMs: Long,
    val elapsedFromStartMs: Long,
    val powerW: Float,
    val batteryLevelPercent: Int?
)

object PerformanceLogStore {

    private const val FILE_PREFIX = "silence_perf_"
    private const val FILE_SUFFIX = ".csv"
    private const val LOG_TAG = "Silence_Perf_Log"
    private const val MAX_PENDING_RECORDS = 100
    private const val RECORD_LOCK_DIRECTORY = ".silence_perf.lock"
    private const val RECORD_LOCK_ATTEMPTS = 240
    private const val RECORD_LOCK_STALE_MS = 15_000L
    private const val FORMAT_MARKER = "# SILENCE_PERF_V2"
    private const val DATE_PREFIX = "# DATE="
    private const val START_EPOCH_PREFIX = "# START_EPOCH_MS="
    private const val START_TIME_PREFIX = "# START_TIME="
    private const val SAMPLE_HEADER = "# SAMPLE=CPU1_GHZ,CPU2_GHZ,CPU3_GHZ,PACKAGE,POWER_W,GEAR,BATTERY"
    private const val DEFAULT_TIME_GAP_SECONDS = 15
    private const val SESSION_GAP_RESET_THRESHOLD_MS = 10 * 60 * 1000L
    private val fileDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private data class PendingBatch(
        val dateLabel: String,
        val records: MutableList<PerformanceLogRecord>
    )

    @Volatile
    private var lastAppendAtElapsed = 0L
    @Volatile
    private var pendingBatch: PendingBatch? = null

    private data class FileMetadata(
        val dateLabel: String,
        val startEpochMs: Long,
        val startTimeText: String,
        val initialTimeGapSeconds: Int
    )

    private data class TimelineState(
        var currentTimestampMs: Long,
        var currentGapSeconds: Int,
        var lastSampleTimestampMs: Long? = null
    )

    // 返回的是 V2 日志文件模板，只用于说明格式，不直接参与计数和解析。
    fun formatHeader(): String {
        return listOf(
            FORMAT_MARKER,
            "$DATE_PREFIX<yyyy-MM-dd>",
            "$START_EPOCH_PREFIX<epoch_ms>",
            "$START_TIME_PREFIX<local_time>",
            SAMPLE_HEADER,
            "---- POWER_RECORD_TIME=${DEFAULT_TIME_GAP_SECONDS}s ----"
        ).joinToString(System.lineSeparator())
    }

    // 把内存记录编码成最终落盘文本：样本行只保留核心字段，配置变化走事件行。
    fun encode(
        record: PerformanceLogRecord,
        previousPackageName: String? = null,
        previousBatteryLevel: Int? = null
    ): String {
        if (record.entryType == PerformanceLogEntryType.EVENT) {
            return encodeEvent(record)
        }
        val currentPackage = record.foregroundPackageName?.trim().orEmpty()
        val packageField = when {
            currentPackage.isBlank() -> ""
            currentPackage == previousPackageName?.trim().orEmpty() -> ""
            else -> currentPackage
        }
        val currentBattery = record.batteryLevelPercent?.toString().orEmpty()
        val batteryField = when {
            currentBattery.isBlank() -> ""
            currentBattery == previousBatteryLevel?.toString().orEmpty() -> ""
            else -> currentBattery
        }
        return listOf(
            record.cpuGroup1Ghz?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            record.cpuGroup2Ghz?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            record.cpuGroup3Ghz?.let { String.format(Locale.US, "%.2f", it) }.orEmpty(),
            packageField,
            record.powerW?.let { String.format(Locale.US, "%+.2f", it) }.orEmpty(),
            record.performanceGear?.toString().orEmpty(),
            batteryField
        ).joinToString(",") { csvEscape(it) }
    }

    // 解析时同时兼容旧 CSV 和新 V2 事件式格式，便于自动迁移历史日志。
    fun decode(
        line: String,
        previousPackageName: String? = null,
        previousBatteryLevel: Int? = null
    ): PerformanceLogRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == FORMAT_MARKER || trimmed == SAMPLE_HEADER ||
            trimmed.startsWith(DATE_PREFIX) || trimmed.startsWith(START_EPOCH_PREFIX) ||
            trimmed.startsWith(START_TIME_PREFIX)
        ) {
            return null
        }
        if (trimmed.startsWith("----") && trimmed.endsWith("----")) {
            return decodeEventLine(trimmed)
        }
        val parts = parseCsvLine(trimmed)
        if (parts.size >= 14) {
            val entryType = when (parts[1].trim().uppercase(Locale.US)) {
                "EVENT" -> PerformanceLogEntryType.EVENT
                else -> PerformanceLogEntryType.SAMPLE
            }
            val packageField = parts[5].trim()
            val resolvedPackage = if (entryType == PerformanceLogEntryType.SAMPLE) {
                packageField.ifEmpty { previousPackageName?.trim().orEmpty() }
            } else {
                previousPackageName?.trim().orEmpty()
            }
            val batteryField = parts[8].trim()
            val resolvedBatteryLevel = if (entryType == PerformanceLogEntryType.SAMPLE) {
                batteryField.toIntOrNull() ?: previousBatteryLevel
            } else {
                previousBatteryLevel
            }
            return PerformanceLogRecord(
                recordedAtEpochMs = parts[0].trim().toLongOrNull(),
                entryType = entryType,
                cpuGroup1Ghz = parts[2].trim().toFloatOrNull(),
                cpuGroup2Ghz = parts[3].trim().toFloatOrNull(),
                cpuGroup3Ghz = parts[4].trim().toFloatOrNull(),
                foregroundPackageName = resolvedPackage.ifBlank { null },
                powerW = parts[6].trim().toFloatOrNull(),
                performanceGear = parts[7].trim().toIntOrNull(),
                batteryLevelPercent = resolvedBatteryLevel,
                eventName = parts[10].trim().ifBlank { null },
                eventOldValue = parts[11].trim().ifBlank { null },
                eventNewValue = parts[12].trim().ifBlank { null },
                eventDetail = parts[13].trim().ifBlank { null }
            )
        }
        if (parts.size >= 12) {
            val entryType = when (parts[0].trim().uppercase(Locale.US)) {
                "EVENT" -> PerformanceLogEntryType.EVENT
                else -> PerformanceLogEntryType.SAMPLE
            }
            val packageField = parts[4].trim()
            val resolvedPackage = if (entryType == PerformanceLogEntryType.SAMPLE) {
                packageField.ifEmpty { previousPackageName?.trim().orEmpty() }
            } else {
                previousPackageName?.trim().orEmpty()
            }
            val batteryField = parts[7].trim()
            val resolvedBatteryLevel = if (entryType == PerformanceLogEntryType.SAMPLE) {
                batteryField.toIntOrNull() ?: previousBatteryLevel
            } else {
                previousBatteryLevel
            }
            return PerformanceLogRecord(
                recordedAtEpochMs = null,
                entryType = entryType,
                cpuGroup1Ghz = parts[1].trim().toFloatOrNull(),
                cpuGroup2Ghz = parts[2].trim().toFloatOrNull(),
                cpuGroup3Ghz = parts[3].trim().toFloatOrNull(),
                foregroundPackageName = resolvedPackage.ifBlank { null },
                powerW = parts[5].trim().toFloatOrNull(),
                performanceGear = parts[6].trim().toIntOrNull(),
                batteryLevelPercent = resolvedBatteryLevel,
                eventName = parts[8].trim().ifBlank { null },
                eventOldValue = parts[9].trim().ifBlank { null },
                eventNewValue = parts[10].trim().ifBlank { null },
                eventDetail = parts[11].trim().ifBlank { null }
            )
        }
        if (parts.size == 7) {
            val cpu1 = parts[0].trim().toFloatOrNull() ?: return null
            val cpu2 = parts[1].trim().toFloatOrNull() ?: return null
            val cpu3 = parts[2].trim().toFloatOrNull() ?: return null
            val packageField = parts[3].trim()
            val resolvedPackage = packageField.ifEmpty { previousPackageName?.trim().orEmpty() }
            val power = parts[4].trim().toFloatOrNull() ?: return null
            val gear = parts[5].trim().toIntOrNull() ?: return null
            val batteryField = parts[6].trim()
            val resolvedBatteryLevel = batteryField.toIntOrNull() ?: previousBatteryLevel
            return PerformanceLogRecord(
                entryType = PerformanceLogEntryType.SAMPLE,
                cpuGroup1Ghz = cpu1,
                cpuGroup2Ghz = cpu2,
                cpuGroup3Ghz = cpu3,
                foregroundPackageName = resolvedPackage.ifBlank { null },
                powerW = power,
                performanceGear = gear,
                batteryLevelPercent = resolvedBatteryLevel
            )
        }
        if (parts.size < 6) {
            return null
        }
        val cpu1 = parts[0].trim().toFloatOrNull() ?: return null
        val cpu2 = parts[1].trim().toFloatOrNull() ?: return null
        val cpu3 = parts[2].trim().toFloatOrNull() ?: return null
        val packageField = parts[3].trim()
        val resolvedPackage = packageField.ifEmpty { previousPackageName?.trim().orEmpty() }
        val power = parts[4].trim().toFloatOrNull() ?: return null
        val gear = parts[5].trim().toIntOrNull() ?: return null
        val batteryField = parts.getOrNull(6)?.trim().orEmpty()
        val resolvedBatteryLevel = batteryField.toIntOrNull() ?: previousBatteryLevel
        return PerformanceLogRecord(
            entryType = PerformanceLogEntryType.SAMPLE,
            cpuGroup1Ghz = cpu1,
            cpuGroup2Ghz = cpu2,
            cpuGroup3Ghz = cpu3,
            foregroundPackageName = resolvedPackage.ifBlank { null },
            powerW = power,
            performanceGear = gear,
            batteryLevelPercent = resolvedBatteryLevel
        )
    }

    @Synchronized
    // 所有记录先入内存缓冲，只有跨天、满批次或显式 flush 才真正写文件。
    fun append(context: Context, record: PerformanceLogRecord): String {
        val normalized = if (record.entryType == PerformanceLogEntryType.SAMPLE) {
            record.copy(
                recordedAtEpochMs = record.recordedAtEpochMs ?: System.currentTimeMillis(),
                entryType = PerformanceLogEntryType.SAMPLE
            )
        } else {
            record.copy(recordedAtEpochMs = record.recordedAtEpochMs ?: System.currentTimeMillis())
        }
        bufferRecord(context, normalized)
        val line = encode(normalized)
        Log.i(LOG_TAG, "[Silence_Perf_Log] pending $line")
        return line
    }

    // 配置变化事件需要立即可见，所以这里会在追加后立刻触发一次 flush。
    fun recordEvent(
        context: Context,
        eventName: String,
        oldValue: String?,
        newValue: String?,
        detail: String? = null
    ): String {
        val line = append(
            context,
            PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = eventName.trim(),
                eventOldValue = oldValue?.trim().orEmpty().ifBlank { null },
                eventNewValue = newValue?.trim().orEmpty().ifBlank { null },
                eventDetail = detail?.trim().orEmpty().ifBlank { null }
            )
        )
        flushPending(context)
        return line
    }

    // 挡位详细配置会拆成多条差异事件，避免把整份配置序列化成难读的大块文本。
    fun recordModePreferenceEvent(
        context: Context,
        mode: PerformanceMode,
        previous: PerformanceModePreference,
        current: PerformanceModePreference
    ) {
        val events = ArrayList<PerformanceLogRecord>()
        if (previous.foregroundAffinity != current.foregroundAffinity) {
            events += PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "FG_AFFINITY_${mode.name}",
                eventOldValue = previous.foregroundAffinity,
                eventNewValue = current.foregroundAffinity
            )
        }
        if (previous.backgroundAffinity != current.backgroundAffinity) {
            events += PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "BG_AFFINITY_${mode.name}",
                eventOldValue = previous.backgroundAffinity,
                eventNewValue = current.backgroundAffinity
            )
        }
        collectRangeDiffEvents(mode, "NORMAL", previous.normalRanges, current.normalRanges, events)
        collectRangeDiffEvents(mode, "BURST", previous.burstRanges, current.burstRanges, events)
        if (events.isEmpty()) {
            return
        }
        events.forEach { append(context, it) }
        flushPending(context)
    }

    // 应用新起一段采样会话时，如果和日志尾部已经隔了较长时间，就补一条新的时间锚点事件。
    // 这样 readAll 在恢复时间线时会从新的真实时间继续，而不会把两段会话错误拼成超长跨度。
    @Synchronized
    fun ensureSessionTimeAnchor(context: Context, nowEpochMs: Long = System.currentTimeMillis()) {
        val file = logFile(context)
        if (!file.exists()) {
            return
        }
        migrateLegacyFileIfNeeded(context, file)
        val latest = readLatestFromFile(file) ?: return
        val latestTimestamp = latest.recordedAtEpochMs ?: return
        if (nowEpochMs - latestTimestamp < SESSION_GAP_RESET_THRESHOLD_MS) {
            return
        }
        append(
            context,
            PerformanceLogRecord(
                recordedAtEpochMs = nowEpochMs,
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "session_time_anchor",
                eventNewValue = nowEpochMs.toString(),
                eventDetail = Instant.ofEpochMilli(nowEpochMs)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            )
        )
        flushPendingLocked(context)
    }

    // 读取文件时先做旧版迁移，再按头部元信息恢复每条样本的真实时间线。
    fun readAll(context: Context, fileName: String? = null): List<PerformanceLogRecord> {
        val file = if (fileName.isNullOrBlank()) logFile(context) else File(logDir(context), fileName)
        if (!file.exists()) {
            return emptyList()
        }
        migrateLegacyFileIfNeeded(context, file)
        val records = ArrayList<PerformanceLogRecord>()
        var previousPackageName: String? = null
        var previousBatteryLevel: Int? = null
        val metadata = readFileMetadata(file)
        val timeline = TimelineState(
            currentTimestampMs = metadata.startEpochMs,
            currentGapSeconds = metadata.initialTimeGapSeconds
        )
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName, previousBatteryLevel) ?: return@forEach
                val resolved = assignTimeline(record, timeline)
                previousPackageName = record.foregroundPackageName
                previousBatteryLevel = record.batteryLevelPercent
                records.add(resolved)
            }
        }
        return records
    }

    @Synchronized
    // 展示层读的是“落盘文件 + 当天内存缓冲”的合并视图。
    // 新版 V2 文件的时间线已经由事件流精确描述，这里不再额外重建跨度，避免把正常日志误判成跨整天。
    fun readVisibleRecords(context: Context, fileName: String? = null): List<PerformanceLogRecord> {
        val fileRecords = readAll(context, fileName)
        val targetDate = fileName
            ?.removePrefix(FILE_PREFIX)
            ?.removeSuffix(FILE_SUFFIX)
            ?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().format(fileDateFormatter)
        val pending = readPendingRecords(targetDate)
        val merged = if (fileName.isNullOrBlank() || fileName == logFile(context, targetDate).name) {
            fileRecords + pending.toList()
        } else {
            fileRecords
        }
        return merged
    }

    @Synchronized
    fun readPendingRecords(dateLabel: String? = null): List<PerformanceLogRecord> {
        val batch = pendingBatch ?: return emptyList()
        if (dateLabel != null && batch.dateLabel != dateLabel) {
            return emptyList()
        }
        return batch.records.toList()
    }

    // 图表只吃样本记录，事件记录仅用于重建时间线和解释采样区间。
    fun buildPowerTrend(records: List<PerformanceLogRecord>): List<PerformanceTrendPoint> {
        val points = ArrayList<PerformanceTrendPoint>()
        var batteryLevel: Int? = null
        var baseTimestampMs: Long? = null
        var lastTimestampMs: Long? = null
        records.forEach { record ->
            if (record.batteryLevelPercent != null) {
                batteryLevel = record.batteryLevelPercent
            }
            if (record.entryType != PerformanceLogEntryType.SAMPLE) {
                return@forEach
            }
            val power = record.powerW ?: return@forEach
            val timestampMs = record.recordedAtEpochMs
                ?: lastTimestampMs?.plus(1000L)
                ?: System.currentTimeMillis()
            if (baseTimestampMs == null) {
                baseTimestampMs = timestampMs
            }
            lastTimestampMs = timestampMs
            points += PerformanceTrendPoint(
                timestampEpochMs = timestampMs,
                elapsedFromStartMs = timestampMs - (baseTimestampMs ?: timestampMs),
                powerW = power,
                batteryLevelPercent = batteryLevel
            )
        }
        return points
    }

    fun readLatest(context: Context): PerformanceLogRecord? {
        val pending = synchronized(this) { pendingBatch?.records?.lastOrNull() }
        if (pending != null) {
            return pending
        }
        val file = latestLogFile(context) ?: return null
        return readLatestFromFile(file)
    }

    @Synchronized
    fun flushPending(context: Context) {
        flushPendingLocked(context)
    }

    @Synchronized
    // 批次按日期隔离，避免跨天后把两天的数据写进同一个文件。
    private fun bufferRecord(context: Context, record: PerformanceLogRecord) {
        val dateLabel = resolveDateLabel(record.recordedAtEpochMs ?: System.currentTimeMillis())
        val current = pendingBatch
        if (current != null && current.dateLabel != dateLabel) {
            flushPendingLocked(context)
        }
        val batch = pendingBatch?.takeIf { it.dateLabel == dateLabel } ?: PendingBatch(
            dateLabel = dateLabel,
            records = ArrayList()
        ).also {
            pendingBatch = it
        }
        batch.records += record
        if (batch.records.size >= MAX_PENDING_RECORDS) {
            flushPendingLocked(context)
        }
    }

    @Synchronized
    // 真正的落盘入口：先补 V2 头，再按上一条上下文压缩包名和电量字段。
    private fun flushPendingLocked(context: Context) {
        val batch = pendingBatch ?: return
        if (batch.records.isEmpty()) {
            pendingBatch = null
            return
        }
        val lockDirectory = acquireRecordFileLock(context) ?: run {
            Log.e(LOG_TAG, "[Silence_Perf_Log] record file lock unavailable date=${batch.dateLabel}")
            return
        }
        try {
            val file = logFile(context, batch.dateLabel)
            file.parentFile?.mkdirs()
            migrateLegacyFileIfNeeded(context, file)
            ensureV2Header(
                file = file,
                dateLabel = batch.dateLabel,
                startEpochMs = batch.records.firstOrNull()?.recordedAtEpochMs ?: System.currentTimeMillis(),
                initialTimeGapSeconds = SettingsStore.getPowerRecordPollIntervalSeconds(context).coerceIn(1, 300)
            )
            var previousRecord = readLatestFromFile(file)
            val lines = ArrayList<String>(batch.records.size)
            batch.records.forEach { record ->
                val line = encode(
                    record = record,
                    previousPackageName = previousRecord?.foregroundPackageName,
                    previousBatteryLevel = previousRecord?.batteryLevelPercent
                )
                lines += line
                if (record.entryType == PerformanceLogEntryType.SAMPLE) {
                    previousRecord = record.copy(
                        foregroundPackageName = record.foregroundPackageName ?: previousRecord?.foregroundPackageName,
                        batteryLevelPercent = record.batteryLevelPercent ?: previousRecord?.batteryLevelPercent
                    )
                }
            }
            file.appendText(lines.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
            Log.i(LOG_TAG, "[Silence_Perf_Log] flushed date=${batch.dateLabel} count=${batch.records.size}")
            pendingBatch = null
        } finally {
            releaseRecordFileLock(lockDirectory)
        }
    }

    // 读取文件尾部的最新状态，用于继续压缩同类字段并衔接后续批次。
    private fun readLatestFromFile(file: File): PerformanceLogRecord? {
        if (!file.exists()) {
            return null
        }
        var latest: PerformanceLogRecord? = null
        var previousPackageName: String? = null
        var previousBatteryLevel: Int? = null
        val metadata = readFileMetadata(file)
        val timeline = TimelineState(
            currentTimestampMs = metadata.startEpochMs,
            currentGapSeconds = metadata.initialTimeGapSeconds
        )
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName, previousBatteryLevel) ?: return@forEach
                val resolved = assignTimeline(record, timeline)
                previousPackageName = record.foregroundPackageName
                previousBatteryLevel = record.batteryLevelPercent
                latest = resolved
            }
        }
        return latest
    }

    // 只在新文件或旧文件迁移后写一次 V2 头，头部里会记下首个时间间隔。
    private fun ensureV2Header(
        file: File,
        dateLabel: String,
        startEpochMs: Long,
        initialTimeGapSeconds: Int
    ) {
        if (file.exists() && file.length() > 0L) {
            val firstLine = runCatching {
                file.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty().trim() }
            }.getOrDefault("")
            if (firstLine == FORMAT_MARKER) {
                return
            }
        }
        val startTime = Instant.ofEpochMilli(startEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val header = listOf(
            FORMAT_MARKER,
            "$DATE_PREFIX$dateLabel",
            "$START_EPOCH_PREFIX$startEpochMs",
            "$START_TIME_PREFIX$startTime",
            SAMPLE_HEADER,
            "---- POWER_RECORD_TIME=${initialTimeGapSeconds.coerceIn(1, 300)}s ----"
        ).joinToString(System.lineSeparator()) + System.lineSeparator()
        file.writeText(header, Charsets.UTF_8)
    }

    // 文件头里的日期、起始时间和时间间隔是重建时间轴的唯一权威来源。
    private fun readFileMetadata(file: File): FileMetadata {
        val defaultStartMs = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        var dateLabel = resolveDateLabel(defaultStartMs)
        var startEpochMs = defaultStartMs
        var startTimeText = Instant.ofEpochMilli(defaultStartMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        var timeGapSeconds = DEFAULT_TIME_GAP_SECONDS
        if (!file.exists()) {
            return FileMetadata(dateLabel, startEpochMs, startTimeText, timeGapSeconds)
        }
        file.useLines { lines ->
            lines.take(6).forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith(DATE_PREFIX) -> dateLabel = trimmed.removePrefix(DATE_PREFIX).trim()
                    trimmed.startsWith(START_EPOCH_PREFIX) -> {
                        startEpochMs = trimmed.removePrefix(START_EPOCH_PREFIX).trim().toLongOrNull() ?: startEpochMs
                    }
                    trimmed.startsWith(START_TIME_PREFIX) -> {
                        startTimeText = trimmed.removePrefix(START_TIME_PREFIX).trim().ifBlank { startTimeText }
                    }
                    trimmed.startsWith("---- POWER_RECORD_TIME=") -> {
                        timeGapSeconds = trimmed
                            .removePrefix("---- POWER_RECORD_TIME=")
                            .removeSuffix("----")
                            .removeSuffix("s")
                            .trim()
                            .toIntOrNull()
                            ?.coerceIn(1, 300)
                            ?: timeGapSeconds
                    }
                    trimmed.startsWith("---- TIME_GAP=") -> {
                        timeGapSeconds = trimmed
                            .removePrefix("---- TIME_GAP=")
                            .removeSuffix("----")
                            .removeSuffix("s")
                            .trim()
                            .toIntOrNull()
                            ?.coerceIn(1, 300)
                            ?: timeGapSeconds
                    }
                }
            }
        }
        return FileMetadata(dateLabel, startEpochMs, startTimeText, timeGapSeconds)
    }

    // 时间线恢复规则：
    // 1. 样本会消耗当前 gap，并把“下一条样本时间”推进到 timestamp + gap。
    // 2. 轮询间隔事件默认写在“上一条样本之后”，因此这里要同步回推下一条样本时间，保证迁移后的 V2 仍能还原旧日志节奏。
    private fun assignTimeline(
        record: PerformanceLogRecord,
        timeline: TimelineState
    ): PerformanceLogRecord {
        if (record.entryType == PerformanceLogEntryType.EVENT) {
            val newGap = if (record.eventName == "power_record_interval_changed") {
                record.eventNewValue?.toIntOrNull()?.coerceIn(1, 300)
            } else {
                null
            }
            val timeAnchor = if (record.eventName == "session_time_anchor") {
                record.eventNewValue?.toLongOrNull()
            } else {
                null
            }
            if (timeAnchor != null) {
                timeline.currentTimestampMs = timeAnchor
                timeline.lastSampleTimestampMs = null
            }
            if (newGap != null) {
                timeline.currentGapSeconds = newGap
                timeline.lastSampleTimestampMs?.let { lastSampleTimestamp ->
                    timeline.currentTimestampMs = lastSampleTimestamp + newGap * 1000L
                }
            }
            return record.copy(recordedAtEpochMs = timeline.currentTimestampMs)
        }
        val timestamp = record.recordedAtEpochMs ?: timeline.currentTimestampMs
        timeline.lastSampleTimestampMs = timestamp
        timeline.currentTimestampMs = timestamp + timeline.currentGapSeconds * 1000L
        return record.copy(recordedAtEpochMs = timestamp)
    }

    // 列表页需要同时看到已落盘文件和当前缓冲批次，所以这里会把两部分合并统计。
    fun listLogFiles(context: Context): List<PerformanceLogFileSummary> {
        val summaries = logDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.sortedWith(compareByDescending<File> { it.name })
            ?.map { file ->
                PerformanceLogFileSummary(
                    fileName = file.name,
                    dateLabel = file.name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX),
                    recordCount = countRecords(file),
                    firstRecordedAt = readFirstSampleTimestamp(file),
                    lastModifiedAt = file.lastModified(),
                    fileSizeBytes = file.length()
                )
            }
            ?.toList()
            .orEmpty()
            .associateBy { it.fileName }
            .toMutableMap()
        val pending = synchronized(this) { pendingBatch }
        if (pending != null) {
            val fileName = logFile(context, pending.dateLabel).name
            val existing = summaries[fileName]
            val now = System.currentTimeMillis()
            summaries[fileName] = if (existing == null) {
                PerformanceLogFileSummary(
                    fileName = fileName,
                    dateLabel = pending.dateLabel,
                    recordCount = pending.records.size,
                    firstRecordedAt = pending.records.firstOrNull()?.recordedAtEpochMs,
                    lastModifiedAt = now,
                    fileSizeBytes = 0L
                )
            } else {
                existing.copy(
                    recordCount = existing.recordCount + pending.records.size,
                    lastModifiedAt = maxOf(existing.lastModifiedAt, now)
                )
            }
        }
        return summaries.values.sortedWith(compareByDescending<PerformanceLogFileSummary> { it.fileName })
    }

    data class PowerSnapshot(
        val powerW: Float,
        val batteryLevelPercent: Int?,
        val voltageV: Float,
        val batteryTemperatureC: Float?,
        val powerState: String
    )

    // 功率快照统一由 daemon 提供，日志层只负责转换和持久化。
    fun readPowerSnapshot(context: Context): PowerSnapshot? {
        return SilenceDaemonClient.getSnapshot(context)?.power?.toPerformancePowerSnapshot()
    }

    fun groupFrequenciesFromSnapshot(snapshot: CpuCoreFrequencySnapshot): CpuGroupFrequencies {
        val grouped = snapshot.cores
            .groupBy { resolveClusterIndex(it.coreIndex) }
            .toSortedMap()
            .values
            .map { cluster ->
                cluster.map { it.currentMhz }.average().toFloat() / 1000f
            }
        val resolved = grouped + listOf(0f, 0f, 0f)
        return CpuGroupFrequencies(
            group1Ghz = resolved.getOrElse(0) { 0f },
            group2Ghz = resolved.getOrElse(1) { 0f },
            group3Ghz = resolved.getOrElse(2) { 0f }
        )
    }

    private fun logFile(context: Context, dateLabel: String = LocalDate.now().format(fileDateFormatter)): File {
        return File(logDir(context), "$FILE_PREFIX$dateLabel$FILE_SUFFIX")
    }

    private fun latestLogFile(context: Context): File? {
        return logDir(context)
            .listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.maxWithOrNull(compareBy<File> { it.name }.thenBy { it.lastModified() })
    }

    private fun logDir(context: Context): File {
        return context.createDeviceProtectedStorageContext().filesDir
    }

    // daemon 以 root 写入同一组每日文件，App 事件写入前也必须取得同一个目录锁。
    private fun acquireRecordFileLock(context: Context): File? {
        val lockDirectory = File(logDir(context), RECORD_LOCK_DIRECTORY)
        repeat(RECORD_LOCK_ATTEMPTS) {
            if (lockDirectory.mkdir()) {
                return lockDirectory
            }
            if (lockDirectory.exists()) {
                val age = System.currentTimeMillis() - lockDirectory.lastModified()
                if (age > RECORD_LOCK_STALE_MS) {
                    lockDirectory.delete()
                    return@repeat
                }
            } else {
                lockDirectory.parentFile?.takeIf { !it.exists() }?.mkdirs()
            }
            try {
                Thread.sleep(5L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun releaseRecordFileLock(lockDirectory: File) {
        if (!lockDirectory.delete() && lockDirectory.exists()) {
            Log.w(LOG_TAG, "[Silence_Perf_Log] record file lock release failed path=${lockDirectory.absolutePath}")
        }
    }

    private fun resolveDateLabel(timestampEpochMs: Long): String {
        return Instant.ofEpochMilli(timestampEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(fileDateFormatter)
    }

    private fun csvEscape(value: String): String {
        if (value.isEmpty()) {
            return ""
        }
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> {
                    inQuotes = !inQuotes
                }
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
            index++
        }
        result += current.toString()
        return result
    }

    // 事件行会尽量压成可读的 diff 形式，便于人工直接推断后续采样上下文。
    private fun encodeEvent(record: PerformanceLogRecord): String {
        val name = record.eventName?.trim().orEmpty()
        val oldValue = record.eventOldValue?.trim().orEmpty()
        val newValue = record.eventNewValue?.trim().orEmpty()
        val detail = record.eventDetail?.trim().orEmpty()
        val payload = when (name) {
            "power_record_interval_changed" -> "POWER_RECORD_TIME=${newValue.ifBlank { oldValue }}s"
            "session_time_anchor" -> "TIME=${newValue.ifBlank { oldValue }}"
            "power_record_enabled_changed" -> "POWER_RECORD_ENABLED=$newValue"
            "default_mode_changed" -> "DEFAULT_MODE=$newValue"
            "package_mode_changed" -> {
                val packageMode = newValue.substringAfter(':', newValue)
                val packageName = newValue.substringBefore(':', "").trim()
                if (packageName.isNotBlank()) {
                    "PACKAGE_MODE_${packageName}=$packageMode"
                } else {
                    "PACKAGE_MODE=$packageMode"
                }
            }
            "external_mode_enabled_changed" -> "EXTERNAL_MODE_ENABLED=$newValue"
            "external_mode_path_changed" -> "EXTERNAL_MODE_PATH=$newValue"
            else -> {
                when {
                    newValue.isNotBlank() -> "$name=$newValue"
                    detail.isNotBlank() -> "$name=$detail"
                    oldValue.isNotBlank() -> "$name=$oldValue"
                    else -> name
                }
            }
        }
        return "---- $payload ----"
    }

    // 事件行解码需要反向还原成统一事件名，方便后续共用时间线和迁移逻辑。
    private fun decodeEventLine(line: String): PerformanceLogRecord {
        val payload = line.removePrefix("----").removeSuffix("----").trim()
        return when {
            payload.startsWith("POWER_RECORD_TIME=") -> {
                val seconds = payload.removePrefix("POWER_RECORD_TIME=").removeSuffix("s").trim()
                PerformanceLogRecord(
                    entryType = PerformanceLogEntryType.EVENT,
                    eventName = "power_record_interval_changed",
                    eventNewValue = seconds
                )
            }
            payload.startsWith("TIME=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "session_time_anchor",
                eventNewValue = payload.removePrefix("TIME=").trim()
            )
            payload.startsWith("TIME_GAP=") -> {
                val seconds = payload.removePrefix("TIME_GAP=").removeSuffix("s").trim()
                PerformanceLogRecord(
                    entryType = PerformanceLogEntryType.EVENT,
                    eventName = "power_record_interval_changed",
                    eventNewValue = seconds
                )
            }
            payload.startsWith("POWER_RECORD_ENABLED=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "power_record_enabled_changed",
                eventNewValue = payload.removePrefix("POWER_RECORD_ENABLED=").trim()
            )
            payload.startsWith("DEFAULT_MODE=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "default_mode_changed",
                eventNewValue = payload.removePrefix("DEFAULT_MODE=").trim()
            )
            payload.startsWith("PACKAGE_MODE_") -> {
                val split = payload.split('=', limit = 2)
                val packageName = split.first().removePrefix("PACKAGE_MODE_").trim()
                val mode = split.getOrNull(1)?.trim().orEmpty()
                PerformanceLogRecord(
                    entryType = PerformanceLogEntryType.EVENT,
                    eventName = "package_mode_changed",
                    eventNewValue = if (packageName.isBlank()) mode else "$packageName:$mode"
                )
            }
            payload.startsWith("PACKAGE_MODE=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "package_mode_changed",
                eventNewValue = payload.removePrefix("PACKAGE_MODE=").trim()
            )
            payload.startsWith("EXTERNAL_MODE_ENABLED=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "external_mode_enabled_changed",
                eventNewValue = payload.removePrefix("EXTERNAL_MODE_ENABLED=").trim()
            )
            payload.startsWith("EXTERNAL_MODE_PATH=") -> PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "external_mode_path_changed",
                eventNewValue = payload.removePrefix("EXTERNAL_MODE_PATH=").trim()
            )
            else -> {
                val split = payload.split('=', limit = 2)
                PerformanceLogRecord(
                    entryType = PerformanceLogEntryType.EVENT,
                    eventName = split.firstOrNull()?.trim().orEmpty(),
                    eventNewValue = split.getOrNull(1)?.trim()?.ifBlank { null }
                )
            }
        }
    }

    // 每个核心的范围变化都拆成单独事件，后面做日志转换时更容易精确还原。
    private fun collectRangeDiffEvents(
        mode: PerformanceMode,
        type: String,
        previous: Map<Int, CpuRangePreference>,
        current: Map<Int, CpuRangePreference>,
        out: MutableList<PerformanceLogRecord>
    ) {
        (previous.keys + current.keys).distinct().sorted().forEach { coreIndex ->
            val previousRange = previous[coreIndex]
            val currentRange = current[coreIndex]
            if (previousRange == currentRange || currentRange == null) {
                return@forEach
            }
            out += PerformanceLogRecord(
                entryType = PerformanceLogEntryType.EVENT,
                eventName = "${mode.name}_${type}_CORE_$coreIndex",
                eventOldValue = previousRange?.let { "${it.minMhz}-${it.maxMhz}" },
                eventNewValue = "${currentRange.minMhz}-${currentRange.maxMhz}"
            )
        }
    }

    private fun resolveClusterIndex(coreIndex: Int): Int {
        return when (coreIndex) {
            in 0..3 -> 0
            in 4..6 -> 1
            else -> 2
        }
    }

    // 记录数不包含头部元信息，但保留事件行，因为它们本身就是有效日志条目。
    private fun countRecords(file: File): Int {
        return runCatching {
            file.useLines { lines ->
                lines.count { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() &&
                        trimmed != FORMAT_MARKER &&
                        trimmed != SAMPLE_HEADER &&
                        !trimmed.startsWith(DATE_PREFIX) &&
                        !trimmed.startsWith(START_EPOCH_PREFIX) &&
                        !trimmed.startsWith(START_TIME_PREFIX)
                }
            }
        }.getOrDefault(0)
    }

    // 首条样本时间必须经过时间线恢复，不能直接拿原始字段，否则 V2 文件会失真。
    private fun readFirstSampleTimestamp(file: File): Long? {
        if (!file.exists()) {
            return null
        }
        var previousPackageName: String? = null
        var previousBatteryLevel: Int? = null
        val metadata = readFileMetadata(file)
        val timeline = TimelineState(
            currentTimestampMs = metadata.startEpochMs,
            currentGapSeconds = metadata.initialTimeGapSeconds
        )
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName, previousBatteryLevel) ?: return@forEach
                val resolved = assignTimeline(record, timeline)
                previousPackageName = record.foregroundPackageName
                previousBatteryLevel = record.batteryLevelPercent
                if (resolved.entryType == PerformanceLogEntryType.SAMPLE) {
                    return resolved.recordedAtEpochMs
                }
            }
        }
        return null
    }

    @Synchronized
    fun deleteLogFile(context: Context, fileName: String): Boolean {
        val file = File(logDir(context), fileName)
        val targetDate = fileName.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX)
        if (pendingBatch?.dateLabel == targetDate) {
            pendingBatch = null
        }
        return !file.exists() || file.delete()
    }

    // 检测到旧版 CSV 后会原地迁成 V2，避免展示层和新逻辑长期背兼容包袱。
    private fun migrateLegacyFileIfNeeded(context: Context, file: File) {
        if (!file.exists()) {
            return
        }
        val header = runCatching {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine().orEmpty().trim() }
        }.getOrDefault("")
        if (header == FORMAT_MARKER) {
            return
        }
        val legacyRecords = readAllFromFile(file)
        val migratedLines = ArrayList<String>(legacyRecords.size + 6)
        val normalizedRecords = normalizeRecordTimeline(context, file, legacyRecords)
        val startEpochMs = normalizedRecords.firstOrNull { it.entryType == PerformanceLogEntryType.SAMPLE }?.recordedAtEpochMs
            ?: file.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val dateLabel = resolveDateLabel(startEpochMs)
        migratedLines += FORMAT_MARKER
        migratedLines += "$DATE_PREFIX$dateLabel"
        migratedLines += "$START_EPOCH_PREFIX$startEpochMs"
        migratedLines += "$START_TIME_PREFIX${
            Instant.ofEpochMilli(startEpochMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }"
        migratedLines += SAMPLE_HEADER
        val initialTimeGapSeconds = (estimateRecordIntervalMs(context, normalizedRecords) / 1000L)
            .toInt()
            .coerceIn(1, 300)
        migratedLines += "---- POWER_RECORD_TIME=${initialTimeGapSeconds}s ----"
        var previousPackageName: String? = null
        var previousBatteryLevel: Int? = null
        normalizedRecords.forEach { normalized ->
            migratedLines += encode(normalized, previousPackageName, previousBatteryLevel)
            previousPackageName = normalized.foregroundPackageName ?: previousPackageName
            previousBatteryLevel = normalized.batteryLevelPercent ?: previousBatteryLevel
        }
        file.writeText(migratedLines.joinToString(System.lineSeparator()) + System.lineSeparator(), Charsets.UTF_8)
    }

    private fun readAllFromFile(file: File): List<PerformanceLogRecord> {
        if (!file.exists()) {
            return emptyList()
        }
        val records = ArrayList<PerformanceLogRecord>()
        var previousPackageName: String? = null
        var previousBatteryLevel: Int? = null
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName, previousBatteryLevel) ?: return@forEach
                previousPackageName = record.foregroundPackageName
                previousBatteryLevel = record.batteryLevelPercent
                records += record
            }
        }
        return records
    }

    // 如果旧文件时间跨度明显异常，这里会按轮询间隔重建一条更合理的时间线。
    private fun normalizeRecordTimeline(
        context: Context,
        file: File,
        records: List<PerformanceLogRecord>
    ): List<PerformanceLogRecord> {
        if (records.isEmpty()) {
            return records
        }
        val sampleRecords = records.filter { it.entryType == PerformanceLogEntryType.SAMPLE }
        if (sampleRecords.isEmpty()) {
            return records
        }
        val estimatedIntervalMs = estimateRecordIntervalMs(context, records)
        val timestamps = sampleRecords.mapNotNull { it.recordedAtEpochMs }
        val spanMs = if (timestamps.size >= 2) {
            (timestamps.last() - timestamps.first()).coerceAtLeast(0L)
        } else {
            0L
        }
        val expectedSpanMs = ((sampleRecords.size - 1).coerceAtLeast(1)) * estimatedIntervalMs
        val firstTimestamp = timestamps.firstOrNull()
        val suspiciousMidnightAnchor = firstTimestamp?.let { isNearStartOfDay(it) } == true &&
            spanMs > expectedSpanMs * 2
        val suspiciousLargeGap = spanMs > expectedSpanMs * 3
        val allMissingTimestamps = timestamps.isEmpty()
        if (!allMissingTimestamps && !suspiciousMidnightAnchor && !suspiciousLargeGap) {
            return records
        }

        val endAnchorMs = timestamps.lastOrNull()
            ?: file.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        var sampleIndex = 0
        val sampleStartMs = endAnchorMs - ((sampleRecords.size - 1).coerceAtLeast(0) * estimatedIntervalMs)
        return records.map { record ->
            if (record.entryType != PerformanceLogEntryType.SAMPLE) {
                val eventTime = (sampleStartMs + sampleIndex * estimatedIntervalMs).coerceAtMost(endAnchorMs)
                record.copy(recordedAtEpochMs = record.recordedAtEpochMs ?: eventTime)
            } else {
                val normalizedTime = (sampleStartMs + sampleIndex * estimatedIntervalMs).coerceAtMost(endAnchorMs)
                sampleIndex += 1
                record.copy(recordedAtEpochMs = normalizedTime)
            }
        }
    }

    // 轮询间隔优先看相邻样本的中位数，异常跨度再回退到当前设置值。
    private fun estimateRecordIntervalMs(
        context: Context,
        records: List<PerformanceLogRecord>
    ): Long {
        val configured = SettingsStore.getPowerRecordPollIntervalSeconds(context).coerceIn(1, 300) * 1000L
        val sampleTimes = records.asSequence()
            .filter { it.entryType == PerformanceLogEntryType.SAMPLE }
            .mapNotNull { it.recordedAtEpochMs }
            .toList()
        val deltas = sampleTimes.zipWithNext()
            .map { (previous, next) -> next - previous }
            .filter { it in 1_000L..(30 * 60_000L) }
            .sorted()
        val medianDelta = deltas.getOrNull(deltas.size / 2)
        return when {
            medianDelta == null -> configured
            medianDelta > configured * 4 -> configured
            else -> medianDelta
        }
    }

    private fun isNearStartOfDay(timestampEpochMs: Long): Boolean {
        val localTime = Instant.ofEpochMilli(timestampEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalTime()
        return localTime.toSecondOfDay() < 10 * 60
    }
}
