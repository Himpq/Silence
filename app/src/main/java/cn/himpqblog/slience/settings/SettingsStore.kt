package cn.himpqblog.slience.settings

import android.content.Context

object SettingsStore {

    private const val PREFS_NAME = "silence_settings"
    private const val KEY_PROCESS_SORT_MODE = "process_sort_mode"
    private const val KEY_LOG_RECORD_MODE = "log_record_mode"
    private const val KEY_APP_STATE_POLL_INTERVAL_SECONDS = "app_state_poll_interval_seconds"
    private const val KEY_PROCESS_REFRESH_INTERVAL_SECONDS = "process_refresh_interval_seconds"
    private const val KEY_HOOK_POLL_INTERVAL_SECONDS = "hook_poll_interval_seconds"

    enum class ProcessSortMode {
        CPU,
        MEMORY
    }

    enum class LogRecordMode {
        ERROR,
        LOG,
        ALL
    }

    fun getProcessSortMode(context: Context): ProcessSortMode {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROCESS_SORT_MODE, ProcessSortMode.CPU.name)
        return ProcessSortMode.entries.firstOrNull { it.name == value } ?: ProcessSortMode.CPU
    }

    fun setProcessSortMode(context: Context, mode: ProcessSortMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROCESS_SORT_MODE, mode.name)
            .apply()
    }

    fun getLogRecordMode(context: Context): LogRecordMode {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOG_RECORD_MODE, LogRecordMode.LOG.name)
        return LogRecordMode.entries.firstOrNull { it.name == value } ?: LogRecordMode.LOG
    }

    fun setLogRecordMode(context: Context, mode: LogRecordMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_RECORD_MODE, mode.name)
            .apply()
    }

    fun getAppStatePollIntervalSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_APP_STATE_POLL_INTERVAL_SECONDS, 30)
            .coerceIn(10, 300)
    }

    fun setAppStatePollIntervalSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_APP_STATE_POLL_INTERVAL_SECONDS, seconds.coerceIn(10, 300))
            .apply()
    }

    fun getProcessRefreshIntervalSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PROCESS_REFRESH_INTERVAL_SECONDS, 3)
            .coerceIn(1, 15)
    }

    fun setProcessRefreshIntervalSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PROCESS_REFRESH_INTERVAL_SECONDS, seconds.coerceIn(1, 15))
            .apply()
    }

    fun getHookPollIntervalSeconds(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HOOK_POLL_INTERVAL_SECONDS, 30)
            .coerceIn(5, 300)
    }

    fun setHookPollIntervalSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HOOK_POLL_INTERVAL_SECONDS, seconds.coerceIn(5, 300))
            .apply()
    }
}
