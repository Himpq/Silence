package cn.himpqblog.silence.daemon

import android.content.Context
import android.content.Intent

data class DaemonUiStatus(
    val code: String,
    val summary: String,
    val detail: String,
    val pid: Int,
    val checkedAtEpochMs: Long
)

object DaemonStatusStore {

    const val ACTION_STATUS_CHANGED = "cn.himpqblog.silence.action.DAEMON_STATUS_CHANGED"

    private const val PREFS_NAME = "silence_daemon_status"
    private const val KEY_CODE = "code"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_DETAIL = "detail"
    private const val KEY_PID = "pid"
    private const val KEY_CHECKED_AT = "checked_at"

    fun read(context: Context): DaemonUiStatus {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return DaemonUiStatus(
            code = prefs.getString(KEY_CODE, "unknown") ?: "unknown",
            summary = prefs.getString(KEY_SUMMARY, "尚未检查") ?: "尚未检查",
            detail = prefs.getString(KEY_DETAIL, "启动 App 时会自动检查 daemon") ?: "启动 App 时会自动检查 daemon",
            pid = prefs.getInt(KEY_PID, -1),
            checkedAtEpochMs = prefs.getLong(KEY_CHECKED_AT, 0L)
        )
    }

    fun markStarting(context: Context) {
        save(
            context = context,
            status = DaemonUiStatus(
                code = "starting",
                summary = "正在启动 silence-daemon",
                detail = "正在检查 Root、运行文件和 IPC 握手",
                pid = -1,
                checkedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun recordStartResult(context: Context, result: DaemonStartResult) {
        save(
            context = context,
            status = DaemonUiStatus(
                code = result.code,
                summary = result.summary,
                detail = result.details.joinToString("\n").ifBlank { "无额外诊断信息" },
                pid = result.pid,
                checkedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    private fun save(context: Context, status: DaemonUiStatus) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE, status.code)
            .putString(KEY_SUMMARY, status.summary)
            .putString(KEY_DETAIL, status.detail)
            .putInt(KEY_PID, status.pid)
            .putLong(KEY_CHECKED_AT, status.checkedAtEpochMs)
            .apply()
        appContext.sendBroadcast(
            Intent(ACTION_STATUS_CHANGED).setPackage(appContext.packageName)
        )
    }
}
