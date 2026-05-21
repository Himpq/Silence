package cn.himpqblog.slience.perf

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

data class CpuRangePreference(
    val minMhz: Int,
    val maxMhz: Int
)

data class PerformanceModePreference(
    val foregroundAffinity: String,
    val backgroundAffinity: String,
    val normalRanges: Map<Int, CpuRangePreference>,
    val burstRanges: Map<Int, CpuRangePreference>
)

object PerformanceModePreferenceStore {

    private const val FILE_NAME = "Pref_mode.json"

    // 读取某个挡位的详细配置，不存在时按当前核心范围动态生成默认值。
    fun readModePreference(
        context: Context,
        mode: PerformanceMode,
        defaults: CpuCoreFrequencySnapshot? = null
    ): PerformanceModePreference {
        val root = readRootObject(configFile(context))
        val modeObject = root.optJSONObject(mode.code.toString())
        val defaultRanges = buildDefaultRanges(defaults)
        if (modeObject == null) {
            return defaultPreference(defaultRanges)
        }
        return PerformanceModePreference(
            foregroundAffinity = modeObject.optString("foregroundAffinity", defaultForegroundAffinity(defaultRanges.size)),
            backgroundAffinity = modeObject.optString("backgroundAffinity", defaultBackgroundAffinity(defaultRanges)),
            normalRanges = readRanges(modeObject.optJSONObject("normalRanges"), defaultRanges),
            burstRanges = readRanges(modeObject.optJSONObject("burstRanges"), defaultRanges)
        )
    }

    // 保存挡位详细配置时，同时生成差异化日志，方便后续还原每次参数修改。
    fun saveModePreference(
        context: Context,
        mode: PerformanceMode,
        preference: PerformanceModePreference
    ) {
        val file = configFile(context)
        val root = readRootObject(file)
        val previous = readModePreference(context, mode, null)
        val modeObject = JSONObject().apply {
            put("foregroundAffinity", preference.foregroundAffinity)
            put("backgroundAffinity", preference.backgroundAffinity)
            put("normalRanges", writeRanges(preference.normalRanges))
            put("burstRanges", writeRanges(preference.burstRanges))
        }
        root.put(mode.code.toString(), modeObject)
        file.writeText(root.toString(2), Charsets.UTF_8)
        PerformanceLogStore.recordModePreferenceEvent(
            context = context,
            mode = mode,
            previous = previous,
            current = preference
        )
    }

    // 文本编码只用于展示和调试，真正持久化仍然走结构化 JSON。
    fun encodeRangesText(ranges: Map<Int, CpuRangePreference>): String {
        return ranges.entries
            .sortedBy { it.key }
            .joinToString(separator = "\n") { (coreIndex, range) ->
                "C$coreIndex=${range.minMhz}-${range.maxMhz}"
            }
    }

    // 这里保留文本解析能力，主要用于兼容旧输入方式和调试场景。
    fun parseRangesText(text: String): Map<Int, CpuRangePreference> {
        val result = LinkedHashMap<Int, CpuRangePreference>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { line ->
                val normalized = line.replace("CPU", "C", ignoreCase = true)
                val parts = normalized.split('=', limit = 2)
                if (parts.size != 2) {
                    return@forEach
                }
                val coreIndex = parts[0].trim().removePrefix("C").toIntOrNull() ?: return@forEach
                val rangeParts = parts[1].trim().split('-', limit = 2)
                if (rangeParts.size != 2) {
                    return@forEach
                }
                val min = rangeParts[0].trim().toIntOrNull() ?: return@forEach
                val max = rangeParts[1].trim().toIntOrNull() ?: return@forEach
                result[coreIndex] = CpuRangePreference(
                    minMhz = min,
                    maxMhz = max.coerceAtLeast(min)
                )
            }
        return result
    }

    // 默认配置会把前后台亲和性和频率范围都补齐，避免新挡位出现空值。
    private fun defaultPreference(defaultRanges: Map<Int, CpuRangePreference>): PerformanceModePreference {
        return PerformanceModePreference(
            foregroundAffinity = defaultForegroundAffinity(defaultRanges.size),
            backgroundAffinity = defaultBackgroundAffinity(defaultRanges),
            normalRanges = defaultRanges,
            burstRanges = defaultRanges
        )
    }

    // 默认频率范围优先来自实时核心快照，读不到时才用 0-0 占位。
    private fun buildDefaultRanges(defaults: CpuCoreFrequencySnapshot?): Map<Int, CpuRangePreference> {
        val snapshotRanges = defaults?.cores
            ?.sortedBy { it.coreIndex }
            ?.associate { core ->
                core.coreIndex to CpuRangePreference(
                    minMhz = core.minMhz,
                    maxMhz = core.maxMhz
                )
            }
            .orEmpty()
        if (snapshotRanges.isNotEmpty()) {
            return LinkedHashMap(snapshotRanges)
        }
        return LinkedHashMap<Int, CpuRangePreference>().apply {
            repeat(8) { coreIndex ->
                this[coreIndex] = CpuRangePreference(minMhz = 0, maxMhz = 0)
            }
        }
    }

    // 前台默认亲和性直接覆盖全部核心，保持占位逻辑简单明确。
    private fun defaultForegroundAffinity(coreCount: Int): String {
        return if (coreCount <= 1) {
            "0"
        } else {
            "0-${coreCount - 1}"
        }
    }

    // 后台默认亲和性先压到前半组核心，后面接真实性能调度时再细化。
    private fun defaultBackgroundAffinity(defaultRanges: Map<Int, CpuRangePreference>): String {
        val count = defaultRanges.size.coerceAtLeast(1)
        val backgroundLast = ((count / 2) - 1).coerceAtLeast(0)
        return if (backgroundLast == 0) {
            "0"
        } else {
            "0-$backgroundLast"
        }
    }

    // 读取范围时始终按默认核心集补洞，避免某些核心因为缺字段直接丢失。
    private fun readRanges(
        objectValue: JSONObject?,
        defaults: Map<Int, CpuRangePreference>
    ): Map<Int, CpuRangePreference> {
        if (objectValue == null) {
            return LinkedHashMap(defaults)
        }
        val ranges = LinkedHashMap<Int, CpuRangePreference>()
        defaults.keys.sorted().forEach { coreIndex ->
            val key = coreIndex.toString()
            val defaultRange = defaults[coreIndex] ?: CpuRangePreference(0, 0)
            val rangeObject = objectValue.optJSONObject(key)
            ranges[coreIndex] = CpuRangePreference(
                minMhz = rangeObject?.optInt("min", defaultRange.minMhz) ?: defaultRange.minMhz,
                maxMhz = rangeObject?.optInt("max", defaultRange.maxMhz) ?: defaultRange.maxMhz
            )
        }
        return ranges
    }

    // 写回 JSON 时按核心序稳定排序，方便后续比对和迁移。
    private fun writeRanges(ranges: Map<Int, CpuRangePreference>): JSONObject {
        return JSONObject().apply {
            ranges.entries.sortedBy { it.key }.forEach { (coreIndex, range) ->
                put(coreIndex.toString(), JSONObject().apply {
                    put("min", range.minMhz)
                    put("max", range.maxMhz)
                })
            }
        }
    }

    // 这里保留一个摘要构造器，主要方便后续调试或扩展更紧凑的日志展示。
    private fun buildSummary(preference: PerformanceModePreference): String {
        return buildString {
            append("fg=")
            append(preference.foregroundAffinity)
            append(";bg=")
            append(preference.backgroundAffinity)
            append(";normal=")
            append(encodeRangesText(preference.normalRanges).replace('\n', '|'))
            append(";burst=")
            append(encodeRangesText(preference.burstRanges).replace('\n', '|'))
        }
    }

    // 配置文件单独存放，和 Perf.json 分工明确，避免默认挡位和细节参数混写。
    private fun configFile(context: Context): File {
        val file = File(context.createDeviceProtectedStorageContext().filesDir, FILE_NAME)
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText(JSONObject().toString(2), Charsets.UTF_8)
        }
        return file
    }

    // 文件损坏时回退空对象，避免拖垮弹窗和日志转换链。
    private fun readRootObject(file: File): JSONObject {
        if (!file.exists()) {
            return JSONObject()
        }
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) {
            return JSONObject()
        }
        return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
    }
}
