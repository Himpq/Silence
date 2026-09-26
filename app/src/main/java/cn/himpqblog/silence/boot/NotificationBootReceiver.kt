package cn.himpqblog.silence.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.himpqblog.silence.daemon.SilenceDaemonService
import cn.himpqblog.silence.notification.PersistentStatusNotificationService
import cn.himpqblog.silence.perf.PerformanceRecordingScheduler
import cn.himpqblog.silence.settings.SettingsStore

class NotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> {
                if (!SettingsStore.isAutoStartEnabled(context)) {
                    return
                }
                // 开机自启动只恢复 Silence 自己的常驻链，不替用户偷偷打开额外功能。
                PerformanceRecordingScheduler.start(context)
                PersistentStatusNotificationService.syncState(context)
                SilenceDaemonService.syncState(context)
            }
        }
    }
}
