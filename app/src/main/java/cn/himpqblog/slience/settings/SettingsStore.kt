package cn.himpqblog.slience.settings

import android.content.Context

object SettingsStore {

    private const val PREFS_NAME = "silence_settings"
    private const val KEY_PROCESS_SORT_MODE = "process_sort_mode"
    private const val KEY_LOG_RECORD_MODE = "log_record_mode"
    private const val KEY_APP_STATE_POLL_INTERVAL_SECONDS = "app_state_poll_interval_seconds"
    private const val KEY_PROCESS_REFRESH_INTERVAL_SECONDS = "process_refresh_interval_seconds"
    private const val KEY_HOOK_POLL_INTERVAL_SECONDS = "hook_poll_interval_seconds"
    private const val KEY_HOOK_ENABLED = "hook_enabled"
    private const val KEY_PROCESS_DEBUG_LOG_ENABLED = "process_debug_log_enabled"
    private const val KEY_FOREGROUND_DEBUG_LOG_ENABLED = "foreground_debug_log_enabled"
    private const val KEY_PERSISTENT_NOTIFICATION_ENABLED = "persistent_notification_enabled"

    enum class ProcessSortMode {
        CPU,
        MEMORY
    }

    enum class LogRecordMode {
        ERROR,
        LOG,
        ALL
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProcessSortMode(context: Context): ProcessSortMode {
        val value = prefs(context)
            .getString(KEY_PROCESS_SORT_MODE, ProcessSortMode.CPU.name)
        return ProcessSortMode.entries.firstOrNull { it.name == value } ?: ProcessSortMode.CPU
    }

    fun setProcessSortMode(context: Context, mode: ProcessSortMode) {
        prefs(context)
            .edit()
            .putString(KEY_PROCESS_SORT_MODE, mode.name)
            .apply()
    }

    fun getLogRecordMode(context: Context): LogRecordMode {
        val value = prefs(context)
            .getString(KEY_LOG_RECORD_MODE, LogRecordMode.LOG.name)
        return LogRecordMode.entries.firstOrNull { it.name == value } ?: LogRecordMode.LOG
    }

    fun setLogRecordMode(context: Context, mode: LogRecordMode) {
        prefs(context)
            .edit()
            .putString(KEY_LOG_RECORD_MODE, mode.name)
            .apply()
    }

    fun getAppStatePollIntervalSeconds(context: Context): Int {
        return prefs(context)
            .getInt(KEY_APP_STATE_POLL_INTERVAL_SECONDS, 30)
            .coerceIn(10, 300)
    }

    fun setAppStatePollIntervalSeconds(context: Context, seconds: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_APP_STATE_POLL_INTERVAL_SECONDS, seconds.coerceIn(10, 300))
            .apply()
    }

    fun getProcessRefreshIntervalSeconds(context: Context): Int {
        return prefs(context)
            .getInt(KEY_PROCESS_REFRESH_INTERVAL_SECONDS, 3)
            .coerceIn(1, 15)
    }

    fun setProcessRefreshIntervalSeconds(context: Context, seconds: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_PROCESS_REFRESH_INTERVAL_SECONDS, seconds.coerceIn(1, 15))
            .apply()
    }

    fun getHookPollIntervalSeconds(context: Context): Int {
        return prefs(context)
            .getInt(KEY_HOOK_POLL_INTERVAL_SECONDS, 30)
            .coerceIn(5, 300)
    }

    fun setHookPollIntervalSeconds(context: Context, seconds: Int) {
        prefs(context)
            .edit()
            .putInt(KEY_HOOK_POLL_INTERVAL_SECONDS, seconds.coerceIn(5, 300))
            .apply()
    }

    fun isHookEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HOOK_ENABLED, true)
    }

    fun setHookEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_HOOK_ENABLED, enabled)
            .apply()
    }

    fun isProcessDebugLogEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PROCESS_DEBUG_LOG_ENABLED, false)
    }

    fun setProcessDebugLogEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PROCESS_DEBUG_LOG_ENABLED, enabled)
            .apply()
    }

    fun isForegroundDebugLogEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_FOREGROUND_DEBUG_LOG_ENABLED, false)
    }

    fun setForegroundDebugLogEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_FOREGROUND_DEBUG_LOG_ENABLED, enabled)
            .apply()
    }

    fun isPersistentNotificationEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, false)
    }

    fun setPersistentNotificationEnabled(context: Context, enabled: Boolean) {
        prefs(context)
            .edit()
            .putBoolean(KEY_PERSISTENT_NOTIFICATION_ENABLED, enabled)
            .apply()
    }
}
