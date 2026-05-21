package cn.himpqblog.slience.perf

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cn.himpqblog.slience.settings.SettingsStore

object PerformanceRecordingScheduler {

    private const val LOG_TAG = "Silence_Perf_Log"

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var started = false
    @Volatile
    private var lastRecordedAtEpochMs = 0L

    // 调度器始终以主线程 Handler 驱动，避免额外线程长期常驻。
    private val recordRunnable = object : Runnable {
        override fun run() {
            val context = appContext ?: return
            runCatching {
                tick(context)
            }.onFailure { err ->
                Log.e(LOG_TAG, "[Silence_Perf_Log] scheduler tick failed", err)
            }
            scheduleNext(context)
        }
    }

    @Volatile
    private var appContext: Context? = null

    // 应用启动后只需要启动一次，后续改设置时复用同一套调度器。
    fun start(context: Context) {
        val resolved = context.applicationContext
        appContext = resolved
        if (started) {
            scheduleNext(resolved)
            return
        }
        started = true
        mainHandler.removeCallbacks(recordRunnable)
        mainHandler.post(recordRunnable)
    }

    // 这里只停调度，不清缓存，避免应用收尾前把待写日志丢掉。
    fun stop() {
        started = false
        mainHandler.removeCallbacks(recordRunnable)
    }

    // 设置变化后立即重排下一次调度，保证轮询时间修改能尽快生效。
    fun notifySettingsChanged(context: Context) {
        appContext = context.applicationContext
        if (!started) {
            return
        }
        mainHandler.removeCallbacks(recordRunnable)
        mainHandler.post(recordRunnable)
    }

    // 真正的采样入口，只有记录开关打开且到达轮询间隔才会写入一条样本。
    private fun tick(context: Context) {
        if (!SettingsStore.isPowerRecordEnabled(context)) {
            return
        }
        val now = System.currentTimeMillis()
        val intervalMs = SettingsStore.getPowerRecordPollIntervalSeconds(context).coerceIn(5, 300) * 1000L
        if (lastRecordedAtEpochMs > 0L && now - lastRecordedAtEpochMs < intervalMs) {
            return
        }
        val record = PerformanceLogStore.captureSample(context) ?: return
        lastRecordedAtEpochMs = record.recordedAtEpochMs ?: now
    }

    // 调度器本身高频唤醒，但真正是否写日志由 tick 决定，这样改间隔时响应更快。
    private fun scheduleNext(context: Context) {
        if (!started) {
            return
        }
        mainHandler.removeCallbacks(recordRunnable)
        val delayMs = if (SettingsStore.isPowerRecordEnabled(context)) {
            1000L
        } else {
            3000L
        }
        mainHandler.postDelayed(recordRunnable, delayMs)
    }
}
