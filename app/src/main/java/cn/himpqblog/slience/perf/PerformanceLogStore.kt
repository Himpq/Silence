package cn.himpqblog.slience.perf

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

data class CpuGroupFrequencies(
    val group1Ghz: Float,
    val group2Ghz: Float,
    val group3Ghz: Float
)

data class PerformanceLogRecord(
    val cpuGroup1Ghz: Float,
    val cpuGroup2Ghz: Float,
    val cpuGroup3Ghz: Float,
    val foregroundPackageName: String?,
    val powerW: Float,
    val performanceGear: Int
)

object PerformanceLogStore {

    private const val FILE_NAME = "silence_perf_log.csv"
    private const val LOG_TAG = "Silence_Perf_Log"

    fun formatHeader(): String {
        return "CPUGroup1,CPUGroup2,CPUGroup3,packagename,powerW,performanceGear"
    }

    fun encode(record: PerformanceLogRecord, previousPackageName: String? = null): String {
        val packageField = record.foregroundPackageName
            ?.trim()
            .orEmpty()
            .takeIf { it.isNotEmpty() && it != previousPackageName?.trim().orEmpty() }
            .orEmpty()
        return String.format(
            Locale.US,
            "%.2f,%.2f,%.2f,%s,%+.2f,%d",
            record.cpuGroup1Ghz,
            record.cpuGroup2Ghz,
            record.cpuGroup3Ghz,
            packageField,
            record.powerW,
            record.performanceGear
        )
    }

    fun decode(line: String, previousPackageName: String? = null): PerformanceLogRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed == formatHeader()) {
            return null
        }
        val parts = trimmed.split(',', limit = 6)
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
        return PerformanceLogRecord(
            cpuGroup1Ghz = cpu1,
            cpuGroup2Ghz = cpu2,
            cpuGroup3Ghz = cpu3,
            foregroundPackageName = resolvedPackage.ifBlank { null },
            powerW = power,
            performanceGear = gear
        )
    }

    fun append(context: Context, record: PerformanceLogRecord): String {
        val previousPackageName = readLatest(context)?.foregroundPackageName
        val line = encode(record, previousPackageName)
        val file = logFile(context)
        file.parentFile?.mkdirs()
        file.appendText(line + System.lineSeparator(), Charsets.UTF_8)
        Log.i(LOG_TAG, "[Silence_Perf_Log] $line")
        return line
    }

    fun readAll(context: Context): List<PerformanceLogRecord> {
        val file = logFile(context)
        if (!file.exists()) {
            return emptyList()
        }
        val records = ArrayList<PerformanceLogRecord>()
        var previousPackageName: String? = null
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName) ?: return@forEach
                previousPackageName = record.foregroundPackageName
                records.add(record)
            }
        }
        return records
    }

    fun readLatest(context: Context): PerformanceLogRecord? {
        val file = logFile(context)
        if (!file.exists()) {
            return null
        }
        var latest: PerformanceLogRecord? = null
        var previousPackageName: String? = null
        file.useLines { lines ->
            lines.forEach { line ->
                val record = decode(line, previousPackageName) ?: return@forEach
                previousPackageName = record.foregroundPackageName
                latest = record
            }
        }
        return latest
    }

    fun readCpuGroupFrequencies(): CpuGroupFrequencies? {
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policyDirs = policyRoot.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.sortedBy { parsePolicyIndex(it.name) }
            ?.toList()
            .orEmpty()
        if (policyDirs.isEmpty()) {
            return null
        }
        val frequencies = policyDirs.mapNotNull { readPolicyFrequencyGhz(it) }
        if (frequencies.isEmpty()) {
            return null
        }
        val resolved = frequencies + listOf(0f, 0f, 0f)
        return CpuGroupFrequencies(
            group1Ghz = resolved.getOrElse(0) { 0f },
            group2Ghz = resolved.getOrElse(1) { 0f },
            group3Ghz = resolved.getOrElse(2) { 0f }
        )
    }

    private fun logFile(context: Context): File {
        return context.createDeviceProtectedStorageContext()
            .getFileStreamPath(FILE_NAME)
    }

    private fun readPolicyFrequencyGhz(policyDir: File): Float? {
        val candidateFiles = listOf(
            "scaling_cur_freq",
            "cpuinfo_cur_freq",
            "scaling_max_freq",
            "cpuinfo_max_freq"
        )
        for (candidate in candidateFiles) {
            val value = readLongFile(File(policyDir, candidate)) ?: continue
            if (value > 0L) {
                return value / 1_000_000f
            }
        }
        return null
    }

    private fun readLongFile(file: File): Long? {
        return runCatching {
            if (!file.canRead()) {
                return null
            }
            file.readText(Charsets.UTF_8).trim().toLongOrNull()
        }.getOrNull()
    }

    private fun parsePolicyIndex(name: String): Int {
        return name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE
    }
}