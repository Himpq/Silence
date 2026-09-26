package cn.himpqblog.silence.daemon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.himpqblog.silence.MainActivity
import cn.himpqblog.silence.R
import cn.himpqblog.silence.notification.PersistentStatusNotificationService
import cn.himpqblog.silence.perf.PerformanceLogStore
import cn.himpqblog.silence.perf.PerformanceRecordingScheduler
import cn.himpqblog.silence.settings.SettingsStore

class SilenceDaemonService : Service() {

    companion object {
        private const val CHANNEL_ID = "silence_daemon_guard"
        private const val NOTIFICATION_ID = 0x534C0002
        private const val KEEP_ALIVE_INTERVAL_MS = 30_000L

        // 守护服务的总入口只负责根据开关状态决定是否需要常驻，不在外层散落启动逻辑。
        fun syncState(context: Context) {
            val appContext = context.applicationContext
            if (SettingsStore.isDaemonProtectionEnabled(appContext) &&
                PersistentStatusNotificationService.hasNotificationPermission(appContext)
            ) {
                start(appContext)
            } else {
                stop(appContext)
            }
        }

        private fun start(context: Context) {
            val intent = Intent(context, SilenceDaemonService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        private fun stop(context: Context) {
            val intent = Intent(context, SilenceDaemonService::class.java)
            runCatching {
                context.stopService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            // 守护链的职责很单纯：确保记录调度、时间锚点和状态通知链保持活着。
            PerformanceRecordingScheduler.start(applicationContext)
            runCatching { PerformanceLogStore.ensureSessionTimeAnchor(applicationContext) }
            PersistentStatusNotificationService.syncState(applicationContext)
            if (SettingsStore.isDaemonProtectionEnabled(applicationContext)) {
                mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // 守护服务本身不做重活，只负责给 Silence 提供一个稳定的常驻宿主进程。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SettingsStore.isDaemonProtectionEnabled(applicationContext) ||
            !PersistentStatusNotificationService.hasNotificationPermission(applicationContext)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        mainHandler.removeCallbacks(keepAliveRunnable)
        keepAliveRunnable.run()
        return START_STICKY
    }

    // 用户从最近任务划掉或系统回收后，只要守护开关还在，就尝试把自己重新拉起。
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (SettingsStore.isDaemonProtectionEnabled(applicationContext)) {
            syncState(applicationContext)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(keepAliveRunnable)
        super.onDestroy()
        if (SettingsStore.isDaemonProtectionEnabled(applicationContext)) {
            syncState(applicationContext)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_status_monitor)
            .setContentTitle(getString(R.string.daemon_notification_title))
            .setContentText(getString(R.string.daemon_notification_summary))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.daemon_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.daemon_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }
}
