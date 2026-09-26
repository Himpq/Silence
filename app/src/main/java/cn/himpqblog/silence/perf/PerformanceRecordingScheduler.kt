package cn.himpqblog.silence.perf

import android.content.Context
import android.util.Log
import cn.himpqblog.silence.settings.SettingsStore

object PerformanceRecordingScheduler {

    private const val LOG_TAG = "Silence_Perf_Log"
    @Volatile
    private var started = false

    // 现在只负责把 App 设置同步到 daemon，不再在 App 进程里创建采样定时器。
    fun start(context: Context) {
        val appContext = context.applicationContext
        started = true
        syncDaemonConfig(appContext)
    }

    // daemon 的生命周期不由这里强行停止，避免 App 页面或宿主服务销毁时丢失记录。
    fun stop() {
        started = false
    }

    // 设置变化后只更新配置文件，daemon 会在下一轮采样前读取完整配置。
    fun notifySettingsChanged(context: Context) {
        syncDaemonConfig(context.applicationContext)
    }

    private fun syncDaemonConfig(context: Context) {
        val synced = PerformanceRecordingConfigStore.sync(context)
        Log.i(
            LOG_TAG,
            "[Silence_Perf_Log] daemon recording ownership started=$started configSynced=$synced " +
                "enabled=${SettingsStore.isPowerRecordEnabled(context)} " +
                "intervalSeconds=${SettingsStore.getPowerRecordPollIntervalSeconds(context)}"
        )
        if (!synced) {
            return
        }
        // 配置写入后由 native daemon 在下一轮采样前读取；App 不再追加性能样本。
    }
}
