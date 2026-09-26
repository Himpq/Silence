package cn.himpqblog.silence.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import cn.himpqblog.silence.MainActivity
import cn.himpqblog.silence.R
import cn.himpqblog.silence.daemon.DaemonPowerSnapshot
import cn.himpqblog.silence.daemon.DaemonSnapshot
import cn.himpqblog.silence.daemon.SilenceDaemonClient
import cn.himpqblog.silence.perf.PerformanceConfigStore
import cn.himpqblog.silence.perf.PerformanceExternalModeWriter
import cn.himpqblog.silence.settings.SettingsStore
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PersistentStatusNotificationService : Service() {

    companion object {
        private const val TAG = "Silence"
        private const val CHANNEL_ID = "silence_status_monitor"
        private const val NOTIFICATION_ID = 0x534C0001
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_REFRESH_NOTIFICATION = "cn.himpqblog.silence.action.REFRESH_NOTIFICATION"

        fun syncState(context: Context) {
            // 这是通知服务的总入口：根据开关状态决定启动、停止或仅刷新。
            val appContext = context.applicationContext
            if (SettingsStore.isPersistentNotificationEnabled(appContext) && hasNotificationPermission(appContext)) {
                start(appContext)
            } else {
                stop(appContext)
            }
        }

        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun requestImmediateRefresh(context: Context) {
            // 前台应用或挡位变化时走这条快速刷新，不等下一轮定时器。
            val appContext = context.applicationContext
            if (!SettingsStore.isPersistentNotificationEnabled(appContext) ||
                !hasNotificationPermission(appContext)
            ) {
                return
            }
            val intent = Intent(appContext, PersistentStatusNotificationService::class.java).apply {
                action = ACTION_REFRESH_NOTIFICATION
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }
        }

        private fun start(context: Context) {
            val intent = Intent(context, PersistentStatusNotificationService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        private fun stop(context: Context) {
            val intent = Intent(context, PersistentStatusNotificationService::class.java)
            runCatching {
                context.stopService(intent)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Silence-Notification-Refresh").apply { isDaemon = true }
    }
    private val refreshInFlight = AtomicBoolean(false)
    private var receiverRegistered = false
    private var foregroundStarted = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            mainHandler.removeCallbacks(updateRunnable)
            if (intent?.action == Intent.ACTION_SCREEN_ON) updateRunnable.run()
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            val power = getSystemService(android.os.PowerManager::class.java)
            if (power?.isInteractive == true) {
                refreshNotification()
                mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerForegroundReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification(null))
            foregroundStarted = true
            schedulePeriodicRefresh()
        }
        if (!SettingsStore.isPersistentNotificationEnabled(applicationContext) ||
            !hasNotificationPermission(applicationContext)
        ) {
            mainHandler.removeCallbacks(updateRunnable)
            stopForegroundCompat()
            foregroundStarted = false
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH_NOTIFICATION || foregroundStarted) {
            refreshNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(updateRunnable)
        refreshExecutor.shutdownNow()
        runCatching { unregisterForegroundReceiver() }
        runCatching { stopForegroundCompat() }
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // 定时刷新只负责兜底，真正的前台切换刷新会由广播单独触发。
    private fun schedulePeriodicRefresh() {
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
    }

    // 每次刷新都消费 daemon 的同一份快照，保证前台应用、电量和挡位来自同一时刻。
    private fun refreshNotification() {
        if (!SettingsStore.isPersistentNotificationEnabled(applicationContext) ||
            !hasNotificationPermission(applicationContext)
        ) {
            stopSelf()
            return
        }
        if (!refreshInFlight.compareAndSet(false, true)) {
            return
        }
        refreshExecutor.execute {
            try {
                val snapshot = SilenceDaemonClient.getSnapshot(applicationContext)
                val foregroundPackage = snapshot?.foregroundPackageName
                if (SettingsStore.isForegroundDebugLogEnabled(applicationContext)) {
                    Log.i(
                        TAG,
                        "Silence|notification|refresh foreground=${foregroundPackage ?: "unknown"} " +
                            "daemonTimestamp=${snapshot?.timestampEpochMs ?: 0L}"
                    )
                }
                PerformanceExternalModeWriter.syncCurrentMode(
                    applicationContext,
                    PerformanceConfigStore.resolveModeForPackage(applicationContext, foregroundPackage)
                )
                mainHandler.post {
                    if (!foregroundStarted) return@post
                    val manager = getSystemService(NotificationManager::class.java) ?: return@post
                    manager.notify(NOTIFICATION_ID, buildNotification(snapshot))
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    // 通知内容统一从这里组装，避免普通内容和自定义布局各算各的状态。
    private fun buildNotification(snapshot: DaemonSnapshot?): Notification {
        val foregroundPackage = snapshot?.foregroundPackageName
        val foregroundText = foregroundPackage?.takeIf { it.isNotBlank() }
            ?: getString(R.string.home_foreground_unknown)
        val modeText = getString(
            PerformanceConfigStore.resolveModeForPackage(this, foregroundPackage).labelRes
        )
        val power = snapshot?.power
        val powerSummary = power?.powerSummaryText(this) ?: getString(R.string.notification_power_unknown)
        val detailText = buildString {
            append(getString(R.string.notification_foreground_label))
            append(": ")
            append(foregroundText)
            append('\n')
            append(getString(R.string.notification_performance_label))
            append(": ")
            append(modeText)
            append('\n')
            append(getString(R.string.notification_power_label))
            append(": ")
            append(powerSummary)
            power?.detailLines()?.forEach { line ->
                append('\n')
                append(line)
            }
        }
        val contentText = getString(R.string.notification_content_format, foregroundText, modeText)
        val pendingIntent = buildNotificationPendingIntent()
        val contentView = createNotificationContentView(foregroundText, powerSummary, modeText)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_status_monitor)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setCustomContentView(contentView)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    // 自定义通知面板只展示核心信息，减少系统折叠后信息丢失。
    private fun createNotificationContentView(
        foregroundText: String,
        powerSummary: String,
        modeText: String
    ): RemoteViews {
        return RemoteViews(packageName, R.layout.notification_status_panel).apply {
            resolveSilenceIconBitmap()?.let {
                setImageViewBitmap(R.id.notificationAppIcon, it)
            } ?: setImageViewResource(R.id.notificationAppIcon, R.drawable.ic_nav_home)
            setTextViewText(R.id.notificationForegroundPackage, foregroundText)
            setTextViewText(R.id.notificationPowerSummary, powerSummary)
            setTextViewText(R.id.notificationModeLabel, modeText)
            setOnClickPendingIntent(R.id.notificationStatusRoot, buildNotificationPendingIntent())
        }
    }

    private fun buildNotificationPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_PERFORMANCE_OVERLAY
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resolveSilenceIconBitmap() = runCatching {
        val iconSize = (28f * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        packageManager
            .getApplicationIcon(applicationInfo)
            .toBitmap(width = iconSize, height = iconSize)
    }.getOrNull()

    // 通知服务单独监听前台变化广播，用来在用户切应用时立刻刷新文案。
    private fun registerForegroundReceiver() {
        if (receiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterForegroundReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching {
            unregisterReceiver(screenReceiver)
        }
        receiverRegistered = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun DaemonPowerSnapshot.powerSummaryText(context: Context): String {
        if (!available) {
            return context.getString(R.string.notification_power_unknown)
        }
        val direction = when (state) {
            "charging" -> "充电"
            "discharging" -> "放电"
            else -> "平衡"
        }
        return String.format(Locale.US, "%s %+.2f W", direction, powerW)
    }

    private fun DaemonPowerSnapshot.detailLines(): List<String> {
        val lines = ArrayList<String>(4)
        if (available) {
            lines.add(String.format(Locale.US, "功率: %+.2f W", powerW))
        }
        batteryLevelPercent?.let {
            lines.add(String.format(Locale.US, "电量: %d%%", it))
        }
        if (voltageV > 0f) {
            lines.add(String.format(Locale.US, "电压: %.2f V", voltageV))
        }
        temperatureC?.let {
            lines.add(String.format(Locale.US, "温度: %.1f °C", it))
        }
        return lines
    }
}
