package cn.himpqblog.silence.daemon

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

object DaemonControlClient {
    @Volatile private var appUid = -1
    private val sequence = AtomicLong()

    fun initialize(context: Context) { appUid = context.applicationInfo.uid }

    fun nextVersion(): Long {
        while (true) {
            val old = sequence.get()
            val next = maxOf(old + 1, SystemClock.elapsedRealtimeNanos())
            if (sequence.compareAndSet(old, next)) return next
        }
    }

    fun request(request: JSONObject, uid: Int = appUid, timeoutMs: Int = 1500, prelaunch: Boolean = false): JSONObject? {
        if (uid < 10000) return null
        return DaemonTransport.request(uid, request.toString(), timeoutMs, prelaunch)
    }

    fun freeze(packageName: String, targetUid: Int, frozen: Boolean, targets: Set<String>,
               uid: Int = appUid, version: Long = nextVersion(), timeoutMs: Int = 1500,
               prelaunch: Boolean = false): JSONObject? = request(JSONObject().apply {
        put("command", "SET_FREEZE")
        put("package", packageName)
        put("uid", targetUid)
        put("freeze", frozen)
        put("prelaunch", prelaunch)
        put("targets", JSONArray(targets.toList()))
        put("version", version)
        put("deadlineMs", SystemClock.elapsedRealtime() + timeoutMs)
    }, uid, timeoutMs, prelaunch)

    fun processes(packageName: String? = null, targetUid: Int? = null): JSONObject? = request(JSONObject().apply {
        put("command", "GET_PROCESSES")
        if (packageName != null && targetUid != null) { put("package", packageName); put("uid", targetUid) }
    })
}
