package cn.himpqblog.slience

import android.app.Application
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.notification.PersistentStatusNotificationService
import cn.himpqblog.slience.settings.SettingsStore
import com.topjohnwu.superuser.Shell

class SilenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        RuntimeLogStore.setRecordMode(SettingsStore.getLogRecordMode(this))
        runCatching {
            FreezeListStore.ensureRuntimeConfig(this)
            FreezeListStore.syncRuntimeMirror(this)
        }
        PersistentStatusNotificationService.syncState(this)
    }
}
