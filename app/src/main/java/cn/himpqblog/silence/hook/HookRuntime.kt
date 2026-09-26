package cn.himpqblog.silence.hook



data class HookRuntimeStatus(
    val summary: String,
    val debugEvents: List<String> = emptyList()
)

object HookRuntime {

    private const val BRIDGE_PREFIX = "LSPosed-Bridge"
    private const val TAG = "SilenceHook"
    private const val MODULE_PACKAGE = "cn.himpqblog.silence"
    private const val SILENCE_HOOK_MARKER = "Silence|hook|"
    private const val KW_BG = "进入后台"
    private const val KW_FREEZE = "冻结"
    private const val KW_UNFREEZE = "解冻"

    fun inspect(): HookRuntimeStatus {
        val response = cn.himpqblog.silence.daemon.DaemonControlClient.request(
            org.json.JSONObject().put("command", "GET_LOGS"))
            ?: return HookRuntimeStatus("Daemon unavailable")
        if (!response.optBoolean("success")) return HookRuntimeStatus("Runtime check failed: ${response.optString("detail")}")
        val events = response.optJSONArray("events")
        val lines = if (events == null) emptyList() else (0 until events.length()).map { events.getString(it) }
        return HookRuntimeStatus(if (response.optBoolean("hookActive")) "Hook debug events active" else "Waiting for system_server daemon bridge", lines)
    }
}
