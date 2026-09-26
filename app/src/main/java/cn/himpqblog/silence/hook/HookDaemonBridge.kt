package cn.himpqblog.silence.hook

import android.content.Context
import android.os.Binder
import android.util.Log
import cn.himpqblog.silence.daemon.DaemonControlClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** system_server owns observation; daemon owns configuration and execution. */
object HookDaemonBridge {
    private const val MODULE_PACKAGE = "cn.himpqblog.silence"
    @Volatile private var clientUid = -1
    @Volatile private var configuration: JSONObject? = null
    @Volatile private var configVersion = -1L
    private val logs = ArrayDeque<String>()

    private fun uid(context: Context): Int {
        if (clientUid >= 10000) return clientUid
        val identity = Binder.clearCallingIdentity()
        return try {
            val manager = context.packageManager ?: return -1
            val info = manager.getApplicationInfo(MODULE_PACKAGE, 0)
            info.uid.takeIf { it >= 10000 }?.also { clientUid = it } ?: -1
        } catch (error: Throwable) {
            Log.w("Silence", "Silence|hook|module uid unresolved: ${error.javaClass.simpleName}:${error.message}")
            -1
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    /** 配置没到位时不能把开关当成关闭，否则整条冻结链会静默停摆。 */
    fun isConfigReady(): Boolean = configuration != null

    fun setting(key: String): String = configuration?.optJSONObject("globals")?.optString(key).orEmpty()
    fun rules(): String? = configuration?.optJSONObject("rules")?.toString()

    fun recordLog(message: String) {
        if (message.length > 2048) return
        synchronized(logs) {
            if (logs.size >= 200) logs.removeFirst()
            logs.addLast(message)
        }
    }

    fun work(context: Context): JSONObject? {
        val batch = synchronized(logs) { logs.toList() }
        val response = DaemonControlClient.request(JSONObject().put("command", "HOOK_WORK")
            .put("configVersion", configVersion).put("logs", JSONArray(batch)), uid(context)) ?: return null
        if (response.optString("type") != "HOOK_WORK") return null
        synchronized(logs) { repeat(minOf(batch.size, logs.size)) { logs.removeFirst() } }
        response.optJSONObject("config")?.let {
            configuration = it
            configVersion = response.getLong("version")
            Log.i("Silence", "Silence|hook|daemon configuration applied version=$configVersion")
        }
        return response
    }

    fun freeze(context: Context, packageName: String, targetUid: Int, frozen: Boolean, targets: List<String>,
               version: Long, prelaunch: Boolean = false): JSONObject? =
        DaemonControlClient.freeze(packageName, targetUid, frozen, targets.toSet(), uid(context), version,
            timeoutMs = if (prelaunch) 150 else 1500, prelaunch = prelaunch)

    fun foreground(context: Context, packageName: String, version: Long): Boolean =
        DaemonControlClient.request(JSONObject().put("command", "REPORT_FOREGROUND").put("package", packageName).put("version", version),
            uid(context))?.optBoolean("success") == true

    fun reportRuntime(context: Context, states: JSONObject) {
        val response = DaemonControlClient.request(JSONObject().put("command", "REPORT_RUNTIME").put("states", states), uid(context))
        if (response?.optBoolean("success") != true) Log.w("Silence", "Silence|hook|runtime report failed")
    }
}
