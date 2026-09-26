package cn.himpqblog.silence.daemon

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets

object SilenceDaemonClient {

    @Volatile var latestSnapshot: DaemonSnapshot? = null
        private set

    private const val LOG_TAG = "Silence_Daemon"
    const val PROTOCOL_VERSION = 8
    private const val LOOPBACK_PORT_BASE = 20000
    private const val LOOPBACK_PORT_RANGE = 30000
    private const val SOCKET_TIMEOUT_MS = 1500
    private const val MAX_RESPONSE_LENGTH = 1024 * 1024

    internal fun socketPort(context: android.content.Context): Int {
        val uid = context.applicationContext.applicationInfo.uid
        return LOOPBACK_PORT_BASE + (uid % LOOPBACK_PORT_RANGE)
    }

    internal fun socketName(context: android.content.Context): String {
        return "127.0.0.1:${socketPort(context)}"
    }

    fun isAlive(context: android.content.Context): Boolean {
        val response = request(context, "PING") ?: return false
        return response.optString("type") == "PONG" &&
            response.optInt("protocol", -1) == PROTOCOL_VERSION
    }

    fun getSnapshot(context: android.content.Context): DaemonSnapshot? {
        val response = request(context, "GET_SNAPSHOT") ?: return null
        if (response.optString("type") != "SNAPSHOT") {
            return null
        }
        val cores = response.optJSONArray("cpuCores")?.toCpuCores().orEmpty()
        val power = response.optJSONObject("power")?.toPowerSnapshot()
        return DaemonSnapshot(
            timestampEpochMs = response.optLong("timestampEpochMs", 0L),
            cores = cores,
            power = power,
            foregroundPackageName = response.optString("foregroundPackage").trim()
                .takeIf { it.isNotEmpty() && it != "null" }
        ).also { latestSnapshot = it }
    }

    fun getStatus(context: android.content.Context): JSONObject? {
        return request(context, "GET_STATUS")
    }

    fun stop(context: android.content.Context): Boolean {
        return request(context, "STOP")?.optString("type") == "STOPPING"
    }

    fun setSamplingInterval(context: android.content.Context, intervalMs: Int): Boolean {
        val response = request(context, "SET_INTERVAL ${intervalMs.coerceIn(250, 10_000)}")
            ?: return false
        return response.optString("type") == "INTERVAL"
    }

    private fun request(context: android.content.Context, command: String): JSONObject? =
        DaemonTransport.request(context.applicationContext.applicationInfo.uid, command)

    private fun JSONArray.toCpuCores(): List<DaemonCpuCore> {
        val result = ArrayList<DaemonCpuCore>(length())
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val coreIndex = item.optInt("index", -1)
            if (coreIndex < 0) continue
            val frequencies = item.optJSONArray("availableMhz")?.toIntList().orEmpty()
            result += DaemonCpuCore(
                coreIndex = coreIndex,
                currentMhz = item.optInt("currentMhz", 0),
                minMhz = item.optInt("minMhz", 0),
                maxMhz = item.optInt("maxMhz", 0),
                availableFrequenciesMhz = frequencies,
                usagePercent = if (item.isNull("usagePercent")) {
                    null
                } else {
                    item.optDouble("usagePercent", -1.0).toFloat().takeIf { it.isFinite() && it >= 0f }
                }
            )
        }
        return result.sortedBy { it.coreIndex }
    }

    private fun JSONArray.toIntList(): List<Int> {
        return buildList {
            for (index in 0 until length()) {
                val value = optInt(index, 0)
                if (value > 0) add(value)
            }
        }
    }

    private fun JSONObject.toPowerSnapshot(): DaemonPowerSnapshot {
        return DaemonPowerSnapshot(
            available = optBoolean("available", false),
            powerW = optDouble("powerW", 0.0).toFloat(),
            batteryLevelPercent = if (isNull("batteryLevelPercent")) {
                null
            } else {
                optInt("batteryLevelPercent", -1).takeIf { it >= 0 }
            },
            voltageV = optDouble("voltageV", 0.0).toFloat(),
            temperatureC = if (isNull("temperatureC")) {
                null
            } else {
                optDouble("temperatureC", 0.0).toFloat()
            },
            state = optString("state", "unknown")
        )
    }
}
