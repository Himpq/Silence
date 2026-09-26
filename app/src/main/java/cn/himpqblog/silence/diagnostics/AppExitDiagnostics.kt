package cn.himpqblog.silence.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.concurrent.Executors

object AppExitDiagnostics {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Silence-Exit-Diagnostics") }

    fun recordPreviousExit(context: Context) {
        Log.i("Silence", "Silence|lifecycle|process started pid=${android.os.Process.myPid()}")
        if (Build.VERSION.SDK_INT < 30) return
        val appContext = context.applicationContext
        executor.execute {
            val manager = appContext.getSystemService(ActivityManager::class.java) ?: return@execute
            runCatching {
                manager.getHistoricalProcessExitReasons(appContext.packageName, 0, 3).forEach { exit ->
                    Log.i("Silence", "Silence|lifecycle|previous exit pid=${exit.pid} time=${exit.timestamp} reason=${exit.reason} importance=${exit.importance} description=${exit.description}")
                }
            }.onFailure { Log.w("Silence", "Silence|lifecycle|exit history unavailable", it) }
        }
    }
}
