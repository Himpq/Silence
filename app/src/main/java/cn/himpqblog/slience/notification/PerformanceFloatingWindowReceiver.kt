package cn.himpqblog.slience.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PerformanceFloatingWindowReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        PerformanceFloatingWindowController.show(context.applicationContext)
    }
}