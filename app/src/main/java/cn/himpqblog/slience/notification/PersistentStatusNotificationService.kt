package cn.himpqblog.slience.notification

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
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cn.himpqblog.slience.R
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.ipc.FreezeCommandReceiver
import cn.himpqblog.slience.settings.SettingsStore
import java.util.Locale

class PersistentStatusNotificationService : Service() {

    companion object {
        private const val TAG = "Silence"
        private const val CHANNEL_ID = "silence_status_monitor"
        private const val NOTIFICATION_ID = 0x534C0001
        private const val UPDATE_INTERVAL_MS = 5_000L
        private const val ACTION_REFRESH_NOTIFICATION = "cn.himpqblog.slience.action.REFRESH_NOTIFICATION"

        fun syncState(context: Context) {
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
            val appContext = context.applicationContext
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
    private var receiverRegistered = false
    private var foregroundStarted = false

    private val foregroundStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FreezeCommandReceiver.ACTION_FOREGROUND_STATE) {
                refreshNotification()
            }
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            refreshNotification()
            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerForegroundReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SettingsStore.isPersistentNotificationEnabled(applicationContext) ||
            !hasNotificationPermission(applicationContext)
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) {
            foregroundStarted = true
            startForegroundCompat(buildNotification())
            schedulePeriodicRefresh()
        } else if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            refreshNotification()
        } else {
            refreshNotification()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(updateRunnable)
        runCatching { unregisterForegroundReceiver() }
        runCatching { stopForegroundCompat() }
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun schedulePeriodicRefresh() {
        mainHandler.removeCallbacks(updateRunnable)
        mainHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
    }

    private fun refreshNotification() {
        if (!SettingsStore.isPersistentNotificationEnabled(applicationContext) ||
            !hasNotificationPermission(applicationContext)
        ) {
            stopSelf()
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val foreground = FreezeListStore.readForegroundState(applicationContext)
        if (SettingsStore.isForegroundDebugLogEnabled(applicationContext)) {
            Log.i(
                TAG,
                "Silence|notification|refresh foreground=${foreground?.packageName ?: "unknown"} source=${foreground?.source ?: "none"} updatedAt=${foreground?.updatedAt ?: 0L}"
            )
        }
        manager.notify(NOTIFICATION_ID, buildNotification(foreground))
    }

    private fun buildNotification(foreground: FreezeListStore.ForegroundState? = FreezeListStore.readForegroundState(applicationContext)): Notification {
        val battery = readBatterySnapshot()
        val foregroundText = foreground?.packageName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.home_foreground_unknown)
        val powerSummary = battery?.powerSummaryText(this) ?: getString(R.string.notification_power_unknown)
        val detailText = buildString {
            append(getString(R.string.notification_foreground_label))
            append(": ")
            append(foregroundText)
            append('\n')
            append(getString(R.string.notification_power_label))
            append(": ")
            append(powerSummary)
            battery?.detailLines()?.forEach { line ->
                append('\n')
                append(line)
            }
        }
        val contentText = getString(R.string.notification_content_format, foregroundText, powerSummary)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, PerformanceFloatingWindowReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_status_monitor)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detailText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun readBatterySnapshot(): BatterySnapshot? {
        val batteryManager = getSystemService(BATTERY_SERVICE) as? BatteryManager ?: return null
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE }
        val currentAvg = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
            .takeIf { it != Int.MIN_VALUE }
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        return BatterySnapshot(
            currentNowUa = currentNow,
            currentAvgUa = currentAvg,
            voltageMv = voltageMv
        )
    }

    private fun registerForegroundReceiver() {
        if (receiverRegistered) {
            return
        }
        ContextCompat.registerReceiver(
            this,
            foregroundStateReceiver,
            IntentFilter(FreezeCommandReceiver.ACTION_FOREGROUND_STATE),
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    private fun unregisterForegroundReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching {
            unregisterReceiver(foregroundStateReceiver)
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

    private data class BatterySnapshot(
        val currentNowUa: Int?,
        val currentAvgUa: Int?,
        val voltageMv: Int?
    ) {
        fun powerSummaryText(context: Context): String {
            val signedCurrentUa = currentNowUa ?: currentAvgUa ?: return context.getString(R.string.notification_power_unknown)
            val voltageV = voltageMv?.let { it / 1000f }
            if (voltageV == null) {
                return context.getString(R.string.notification_power_unknown)
            }
            val powerW = signedCurrentUa / 1_000_000f * voltageV
            val direction = when {
                signedCurrentUa > 0 -> "放电"
                signedCurrentUa < 0 -> "充电"
                else -> "平衡"
            }
            return String.format(Locale.US, "%s %+.2f W", direction, -powerW)
        }

        fun detailLines(): List<String> {
            val lines = ArrayList<String>(3)
            currentNowUa?.let {
                lines.add(String.format(Locale.US, "瞬时电流: %+.1f mA", it / 1000f))
            }
            currentAvgUa?.let {
                lines.add(String.format(Locale.US, "平均电流: %+.1f mA", it / 1000f))
            }
            voltageMv?.let {
                lines.add(String.format(Locale.US, "电压: %.2f V", it / 1000f))
            }
            return lines
        }
    }
}
