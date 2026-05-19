package cn.himpqblog.slience.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.himpqblog.slience.notification.PersistentStatusNotificationService

class NotificationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED -> {
                PersistentStatusNotificationService.syncState(context)
            }
        }
    }
}
