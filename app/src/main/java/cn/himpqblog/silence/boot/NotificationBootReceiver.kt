package cn.himpqblog.silence.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.himpqblog.silence.daemon.DaemonConfigSynchronizer
import cn.himpqblog.silence.daemon.SilenceDaemonManager
import cn.himpqblog.silence.notification.PersistentStatusNotificationService
import cn.himpqblog.silence.perf.PerformanceRecordingScheduler
import cn.himpqblog.silence.settings.SettingsStore
import android.util.Log

/**
 * 开机/更新后把 daemon 拉起来。
 *
 * 不再监听 LOCKED_BOOT_COMPLETED：那个阶段比 BOOT_COMPLETED 更早，用户凭据存储还没就绪，
 * 进程随后也会被系统杀掉，而 daemon 用到的路径（/data/local/tmp、/data/user_de、APK 资产）
 * 全都是 device-protected，BOOT_COMPLETED 时已经可用。USER_UNLOCKED 保留，用来在解锁后补一次
 * 配置同步——那时 PackageManager 与凭据存储才是完整的。
 */
class NotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // daemon 是冻结链的唯一执行端，开机必须无条件拉起，否则用户不打开应用就没有冻结。
                SilenceDaemonManager.requestStart(context.applicationContext)
                startOptionalHost(context)
            }
            Intent.ACTION_USER_UNLOCKED -> {
                // 解锁后 daemon 若已就绪，这次同步会把最新的规则和开关推进去。
                DaemonConfigSynchronizer.submit(context.applicationContext)
                startOptionalHost(context)
            }
        }
    }

    // 常驻通知和性能记录仍按用户开关走，不因为开机自启就擅自开启。
    private fun startOptionalHost(context: Context) {
        if (!SettingsStore.isAutoStartEnabled(context)) {
            return
        }
        runCatching { PerformanceRecordingScheduler.start(context) }
            .onFailure { Log.w("Silence", "Silence|boot|recording start failed", it) }
        runCatching { PersistentStatusNotificationService.syncState(context) }
            .onFailure { Log.w("Silence", "Silence|boot|notification sync failed", it) }
    }
}
