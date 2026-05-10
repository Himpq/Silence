package cn.himpqblog.slience.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.process.ProcessInspector
import cn.himpqblog.slience.config.FreezeListStore

class FreezeCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FREEZE_COMMAND) {
            return
        }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().trim()
        if (packageName.isEmpty()) {
            return
        }
        val freeze = intent.getBooleanExtra(EXTRA_FREEZE, false)
        val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
        val targets = intent.getStringArrayListExtra(EXTRA_TARGETS)?.toSet().orEmpty()
        val appContext = context.applicationContext
        Log.i(
            "Silence",
            "Silence|ipc|receiver hit package=$packageName freeze=$freeze source=$source targets=${targets.joinToString(",")}"
        )
        Thread {
            val success = ProcessInspector.applyFreezeCommand(
                context = appContext,
                packageName = packageName,
                freeze = freeze,
                targetNames = targets
            )
            if (success) {
                ProcessInspector.markManagedFrozen(packageName, freeze)
                FreezeListStore.syncRuntimeMirror(appContext)
            }
            RuntimeLogStore.appendDiagnostic(
                source = "ipc",
                message = "recv package=$packageName freeze=$freeze targets=${targets.joinToString(",")} source=$source success=$success",
                throttleKey = "ipc_freeze_${packageName}_${freeze}_${System.currentTimeMillis()}",
                throttleMs = 0L,
                category = RuntimeLogStore.LogCategory.LOG
            )
        }.start()
    }

    companion object {
        const val ACTION_FREEZE_COMMAND = "cn.himpqblog.slience.action.FREEZE_COMMAND"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_FREEZE = "freeze"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_SOURCE = "source"
    }
}
