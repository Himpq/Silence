package cn.himpqblog.silence.daemon

import android.content.Context
import android.util.Log
import cn.himpqblog.silence.config.FreezeListStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Coalesces rapid settings edits; failures are visible and never start su. */
object DaemonConfigSynchronizer {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "Silence-Config-Sync") }
    private val pending = AtomicBoolean()
    private val running = AtomicBoolean()

    fun submit(context: Context) {
        val appContext = context.applicationContext
        pending.set(true)
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (pending.getAndSet(false)) {
                    runCatching { FreezeListStore.syncRuntimeMirror(appContext) }
                        .onFailure { Log.e("Silence", "Silence|config|sync failed", it) }
                }
            } finally {
                running.set(false)
                if (pending.get()) submit(appContext)
            }
        }
    }
}
