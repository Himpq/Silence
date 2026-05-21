package cn.himpqblog.slience

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.notification.PersistentStatusNotificationService
import cn.himpqblog.slience.perf.PerformanceLogStore
import cn.himpqblog.slience.perf.PerformanceRecordingScheduler
import cn.himpqblog.slience.settings.SettingsStore
import com.topjohnwu.superuser.Shell

class SilenceApp : Application(), ComponentCallbacks2 {
    @Volatile
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        RuntimeLogStore.setRecordMode(SettingsStore.getLogRecordMode(this))
        runCatching {
            FreezeListStore.ensureRuntimeConfig(this)
            FreezeListStore.syncRuntimeMirror(this)
        }
        PersistentStatusNotificationService.syncState(this)
        // 进程重启后如果当天已有日志且中间断了较长时间，这里先补一个新的时间锚点，
        // 后续样本就会从新的真实时间继续，而不会错误续接到上一段采样尾部。
        runCatching {
            PerformanceLogStore.ensureSessionTimeAnchor(this)
        }
        registerActivityLifecycleCallbacks(AppLifecycleFlushCallbacks())
        PerformanceRecordingScheduler.start(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            PerformanceLogStore.flushPending(this)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        PerformanceLogStore.flushPending(this)
    }

    override fun onTerminate() {
        PerformanceLogStore.flushPending(this)
        super.onTerminate()
    }

    // 对“正常离开应用”的主路径来说，Activity 全部停止比 onTerminate 更可靠。
    // 这里在应用退到后台时立即刷盘，尽量把内存中的性能记录提前落到文件里。
    private inner class AppLifecycleFlushCallbacks : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivityCount += 1
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            if (startedActivityCount == 0 && !activity.isChangingConfigurations) {
                PerformanceLogStore.flushPending(this@SilenceApp)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
