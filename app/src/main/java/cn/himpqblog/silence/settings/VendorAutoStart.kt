package cn.himpqblog.silence.settings

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * HyperOS / MIUI 自启动白名单探测。
 *
 * 平台自带的 BOOT_COMPLETED 权限不足以让 app 进程在开机时被拉起：厂商会再叠一层自己的
 * "自启动"判定，被拒时 logcat 里是
 * `BroadcastQueueInjector: Unable to launch app ...: process is not permitted to auto start`。
 * 该状态落在自定义 appop 上（HyperOS 上实测为 10008：未授权时 ignore 并带 rejectTime，
 * 授权后 allow）。app 自己无法申请，只能提示用户去设置里手动打开。
 */
object VendorAutoStart {

    private const val TAG = "Silence"
    private const val MIUI_AUTO_START_OP = 10008
    private const val MODE_ALLOWED = AppOpsManager.MODE_ALLOWED

    // HyperOS 上"应用自启动"管理页；其它 ROM 用下面的兜底。
    private val MIUI_AUTO_START_ACTIVITIES = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartActivity")
    )

    /**
     * 只有在厂商明确把自启动判成 ignore 时才返回 true。
     * 非 MIUI 设备上这个 op 不存在（返回 MODE_DEFAULT），一律当作允许，避免误报。
     */
    fun isAutoStartBlocked(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = AppOpsManager::class.java
            .getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            .invoke(manager, MIUI_AUTO_START_OP, context.applicationInfo.uid, context.packageName) as? Int
        val blocked = mode != null && mode != MODE_ALLOWED && mode != AppOpsManager.MODE_DEFAULT
        if (blocked) {
            Log.i(TAG, "Silence|startup|vendor auto start blocked mode=$mode")
        }
        blocked
    }.getOrElse { error ->
        // 隐藏 API 或 ROM 差异导致读不到时不打扰用户。
        Log.w(TAG, "Silence|startup|vendor auto start probe unavailable", error)
        false
    }

    /** 跳到厂商的自启动设置页；没有对应页面时返回 false，交给调用方降级。 */
    fun openSettings(context: Context): Boolean {
        val packageManager = context.packageManager
        MIUI_AUTO_START_ACTIVITIES.forEach { component ->
            val intent = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageManager.resolveActivity(intent, 0) != null && startSafely(context, intent)) {
                return true
            }
        }
        val fallback = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, fallback)
    }

    private fun startSafely(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
