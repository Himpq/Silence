package cn.himpqblog.slience.hook

import com.topjohnwu.superuser.Shell

data class HookRuntimeStatus(
    val summary: String,
    val debugEvents: List<String> = emptyList()
)

object HookRuntime {

    private const val BRIDGE_PREFIX = "LSPosed-Bridge"
    private const val TAG = "SilenceHook"
    private const val MODULE_PACKAGE = "cn.himpqblog.slience"
    private const val SILENCE_HOOK_MARKER = "Silence|hook|"
    private const val KW_BG = "进入后台"
    private const val KW_FREEZE = "冻结"
    private const val KW_UNFREEZE = "解冻"

    fun inspect(): HookRuntimeStatus {
        val result = Shell.cmd(
            "logcat -d -s Silence:I SilenceHook:V LSPosed-Bridge:V LSPosed:V lspd:V 2>/dev/null"
        ).exec()

        if (!result.isSuccess) {
            return HookRuntimeStatus(
                summary = "Runtime check failed",
                debugEvents = emptyList()
            )
        }

        val lines = result.out
        val moduleLoading = lines.filter {
            it.contains(BRIDGE_PREFIX) &&
                it.contains("Loading legacy module") &&
                it.contains(MODULE_PACKAGE)
        }
        val hookLines = lines.filter { it.contains(TAG) }
        val silenceHookEvents = lines.mapNotNull { line ->
            val idx = line.indexOf(SILENCE_HOOK_MARKER)
            if (idx == -1) {
                null
            } else {
                line.substring(idx + SILENCE_HOOK_MARKER.length).trim()
            }
        }.takeLast(1200)

        val summary = when {
            silenceHookEvents.any {
                it.contains(KW_BG) || it.contains(KW_FREEZE) || it.contains(KW_UNFREEZE)
            } -> "Hook debug events active"

            hookLines.any {
                it.contains("hooks installed in") ||
                    it.contains("hooked com.android.server") ||
                    it.contains("hooked com.android.server.wm")
            } -> "Hook installed: waiting for process transition"

            hookLines.any { it.contains("handleLoadPackage") || it.contains("initZygote") } ->
                "Module loaded: waiting for hook install"

            moduleLoading.isNotEmpty() ->
                "Module loaded by LSPosed: waiting for hook logs"

            else ->
                "Waiting for LSPosed to load in system_server"
        }

        return HookRuntimeStatus(
            summary = summary,
            debugEvents = silenceHookEvents
        )
    }
}
