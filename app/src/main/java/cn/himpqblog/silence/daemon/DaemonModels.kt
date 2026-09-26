package cn.himpqblog.silence.daemon

import cn.himpqblog.silence.perf.CpuCoreFrequency
import cn.himpqblog.silence.perf.CpuCoreFrequencySnapshot
import cn.himpqblog.silence.perf.PerformanceLogStore

data class DaemonCpuCore(
    val coreIndex: Int,
    val currentMhz: Int,
    val minMhz: Int,
    val maxMhz: Int,
    val availableFrequenciesMhz: List<Int>,
    val usagePercent: Float?
)

data class DaemonPowerSnapshot(
    val available: Boolean,
    val powerW: Float,
    val batteryLevelPercent: Int?,
    val voltageV: Float,
    val temperatureC: Float?,
    val state: String
)

data class DaemonSnapshot(
    val timestampEpochMs: Long,
    val cores: List<DaemonCpuCore>,
    val power: DaemonPowerSnapshot?,
    val foregroundPackageName: String?
)

data class DaemonStartResult(
    val ready: Boolean,
    val summary: String,
    val details: List<String> = emptyList(),
    val code: String = DaemonStartCode.START_FAILED,
    val pid: Int = -1
)

object DaemonStartCode {
    const val CONNECTED = "connected"
    const val ROOT_MISSING = "root_missing"
    const val ASSET_MISSING = "asset_missing"
    const val START_FAILED = "start_failed"
    const val CONFIG_SYNC_FAILED = "config_sync_failed"
    const val HANDSHAKE_FAILED = "handshake_failed"
}

fun DaemonSnapshot.toCpuCoreFrequencySnapshot(): CpuCoreFrequencySnapshot? {
    if (cores.isEmpty()) {
        return null
    }
    return CpuCoreFrequencySnapshot(
        cores = cores.map { core ->
            CpuCoreFrequency(
                coreIndex = core.coreIndex,
                currentMhz = core.currentMhz,
                minMhz = core.minMhz,
                maxMhz = core.maxMhz,
                availableFrequenciesMhz = core.availableFrequenciesMhz,
                usagePercent = core.usagePercent
            )
        }.sortedBy { it.coreIndex }
    )
}

fun DaemonPowerSnapshot.toPerformancePowerSnapshot(): PerformanceLogStore.PowerSnapshot? {
    if (!available) {
        return null
    }
    return PerformanceLogStore.PowerSnapshot(
        powerW = powerW,
        batteryLevelPercent = batteryLevelPercent,
        voltageV = voltageV,
        batteryTemperatureC = temperatureC,
        powerState = state
    )
}
