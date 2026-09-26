package cn.himpqblog.silence.daemon

import android.content.Context
import android.os.Build
import android.util.Log
import android.os.Handler
import android.os.Looper
import cn.himpqblog.silence.hook.RuntimeLogStore
import cn.himpqblog.silence.perf.PerformanceRecordingConfigStore
import cn.himpqblog.silence.config.FreezeListStore
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SilenceDaemonManager {

    private const val LOG_TAG = "Silence_Daemon"
    private const val DAEMON_FILE_NAME = "silence-daemon"
    private const val ROOT_RUNTIME_DIRECTORY = "/data/local/tmp"
    private const val ROOT_DAEMON_FILE_PREFIX = "silence-daemon"
    private const val DAEMON_PROTOCOL_VERSION = 8
    private const val BACKGROUND_INTERVAL_MS = 5_000
    private const val READY_ATTEMPTS = 40
    private const val READY_DELAY_MS = 100L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Silence-Daemon-Launcher").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val launching = AtomicBoolean(false)

    fun requestStart(context: Context, callback: ((DaemonStartResult) -> Unit)? = null) {
        val appContext = context.applicationContext
        DaemonStatusStore.markStarting(appContext)
        ensureStarted(appContext, callback)
    }

    // 性能页打开时临时提高 daemon 采样频率，离开页面后恢复低频后台采样。
    fun setSamplingInterval(
        context: Context,
        intervalMs: Int,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        executor.execute {
            val success = SilenceDaemonClient.setSamplingInterval(appContext, intervalMs)
            Log.d(LOG_TAG, "sampling interval requested=$intervalMs success=$success")
            RuntimeLogStore.appendDiagnostic(
                source = "daemon",
                message = "sampling interval requested=$intervalMs success=$success",
                throttleKey = "daemon_sampling_interval_$intervalMs",
                throttleMs = 1000L
            )
            mainHandler.post {
                callback?.invoke(success)
            }
        }
    }

    private fun ensureStarted(context: Context, callback: ((DaemonStartResult) -> Unit)? = null) {
        val appContext = context.applicationContext
        if (!launching.compareAndSet(false, true)) {
            return
        }
        executor.execute {
            var result = runCatching { ensureStartedBlocking(appContext) }
                .getOrElse { error ->
                    DaemonStartResult(
                        ready = false,
                        summary = "silence-daemon 启动失败",
                        details = listOf(error.message ?: error.javaClass.simpleName),
                        code = DaemonStartCode.START_FAILED
                    )
                }
            if (result.ready) {
                val synced = FreezeListStore.syncRuntimeMirror(appContext)
                Log.i(LOG_TAG, "daemon connected pid=${result.pid} configSynced=$synced")
                if (!synced) result = result.copy(ready = false, summary = "daemon 已连接，但配置未同步",
                    details = result.details + "configuration_sync_failed", code = DaemonStartCode.CONFIG_SYNC_FAILED)
            }
            launching.set(false)
            DaemonStatusStore.recordStartResult(appContext, result)
            runCatching { Shell.getCachedShell()?.close() }
            mainHandler.post {
                callback?.invoke(result)
            }
        }
    }

    private fun ensureStartedBlocking(context: Context): DaemonStartResult {
        val existingStatus = SilenceDaemonClient.getStatus(context)
        if (existingStatus != null &&
            existingStatus.optInt("protocol", -1) != DAEMON_PROTOCOL_VERSION
        ) {
            Log.i(
                LOG_TAG,
                "stopping incompatible daemon protocol=${existingStatus.optInt("protocol", -1)} " +
                    "expected=$DAEMON_PROTOCOL_VERSION"
            )
            RuntimeLogStore.appendDiagnostic(
                source = "daemon",
                message = "stopping incompatible daemon protocol=${existingStatus.optInt("protocol", -1)} " +
                    "expected=$DAEMON_PROTOCOL_VERSION",
                throttleKey = "daemon_protocol_upgrade",
                throttleMs = 0L
            )
            SilenceDaemonClient.stop(context)
            for (attempt in 0 until READY_ATTEMPTS) {
                if (SilenceDaemonClient.getStatus(context) == null) {
                    break
                }
                Thread.sleep(READY_DELAY_MS)
            }
            // STOP 会先结束 socket 循环，再等待当前 dumpsys 子进程和 sampler 收尾；
            // 端口不可连接不等于旧进程已经释放 bind，给它一个完整的收尾窗口。
            Thread.sleep(3000L)
        }
        if (SilenceDaemonClient.isAlive(context)) {
            SilenceDaemonClient.setSamplingInterval(context, BACKGROUND_INTERVAL_MS)
            val status = SilenceDaemonClient.getStatus(context)
            val daemonUid = status?.optInt("uid", -1) ?: -1
            if (daemonUid != 0) {
                return DaemonStartResult(
                    ready = false,
                    summary = "silence-daemon 未以 Root 身份运行",
                    details = listOf(
                        "daemonUid=$daemonUid",
                        "status=${status?.toString() ?: "empty"}"
                    ),
                    code = DaemonStartCode.HANDSHAKE_FAILED,
                    pid = status?.optInt("pid", -1) ?: -1
                )
            }
            val pid = status?.optInt("pid", -1) ?: -1
            return DaemonStartResult(
                ready = true,
                summary = "silence-daemon 已在运行",
                code = DaemonStartCode.CONNECTED,
                pid = pid
            )
        }

        if (!DaemonTransport.isEndpointAbsent(context.applicationInfo.uid)) {
            return DaemonStartResult(ready = false, summary = "daemon_endpoint_unavailable",
                details = listOf("endpoint exists or absence could not be established; root launch withheld"),
                code = DaemonStartCode.HANDSHAKE_FAILED)
        }

        val abi = resolveDaemonAbi(context)
            ?: return DaemonStartResult(
                ready = false,
                summary = "silence-daemon 资源缺失",
                details = listOf("没有找到当前设备 ABI 对应的 daemon asset"),
                code = DaemonStartCode.ASSET_MISSING
            )

        Log.i(LOG_TAG, "root session requested reason=daemon_not_running protocol=$DAEMON_PROTOCOL_VERSION")
        val shell = runCatching { Shell.getShell() }.getOrElse { error ->
            return DaemonStartResult(
                ready = false,
                summary = "无法创建 root shell",
                details = listOf(error.message ?: error.javaClass.simpleName),
                code = DaemonStartCode.ROOT_MISSING
            )
        }
        if (!shell.isRoot) return DaemonStartResult(false, "Root 未授权，无法启动 daemon", code = DaemonStartCode.ROOT_MISSING)
        val clientUid = context.applicationInfo.uid
        val rootDaemonPath = "$ROOT_RUNTIME_DIRECTORY/$ROOT_DAEMON_FILE_PREFIX-$clientUid"
        val rootDaemonTempPath = "$rootDaemonPath.tmp"
        val socketName = SilenceDaemonClient.socketName(context)
        val socketPort = SilenceDaemonClient.socketPort(context)
        val statusPath = "$ROOT_RUNTIME_DIRECTORY/$ROOT_DAEMON_FILE_PREFIX-$clientUid.status"
        val logPath = "$ROOT_RUNTIME_DIRECTORY/$ROOT_DAEMON_FILE_PREFIX-$clientUid.log"
        val lockPath = "$ROOT_RUNTIME_DIRECTORY/$ROOT_DAEMON_FILE_PREFIX-$clientUid.tcp.lock"
        val apkPath = context.applicationInfo.sourceDir
        val assetPath = "assets/$abi/$DAEMON_FILE_NAME"
        val launchCommand = buildString {
            append("rm -f ")
            append(shellQuote(rootDaemonTempPath))
            append(" ")
            append(shellQuote(rootDaemonPath))
            append(" && /system/bin/unzip -p ")
            append(shellQuote(apkPath))
            append(" ")
            append(shellQuote(assetPath))
            append(" > ")
            append(shellQuote(rootDaemonTempPath))
            append(" && chmod 700 ")
            append(shellQuote(rootDaemonTempPath))
            append(" && mv -f ")
            append(shellQuote(rootDaemonTempPath))
            append(" ")
            append(shellQuote(rootDaemonPath))
            append(" && rm -f ")
            append(shellQuote(statusPath))
            append(" && ")
            append(shellQuote(rootDaemonPath))
            append(" --tcp-port=")
            append(socketPort)
            append(" --client-uid=")
            append(clientUid)
            append(" --log=")
            append(shellQuote(logPath))
            append(" --status=")
            append(shellQuote(statusPath))
            append(" --record-dir=")
            append(shellQuote(PerformanceRecordingConfigStore.recordDirectory(context)))
            append(" --record-config=")
            append(shellQuote(PerformanceRecordingConfigStore.configPath(context)))
            // native daemon 会自行 double-fork 脱离前台；不要让 shell 再用 & 派生，
            // 某些 su 实现会让这个后台子进程回到 shell UID，导致 sysfs 读取被拒绝。
            append(" --interval-ms=")
            append(BACKGROUND_INTERVAL_MS)
            append(" >/dev/null 2>&1")
        }
        val launchResult = shell.newJob().add(launchCommand).exec()
        Log.d(
            LOG_TAG,
            "launch requested success=${launchResult.isSuccess} abi=$abi rootPath=$rootDaemonPath socket=$socketName"
        )
        RuntimeLogStore.appendDiagnostic(
            source = "daemon",
            message = "launch requested success=${launchResult.isSuccess} abi=$abi rootPath=$rootDaemonPath socket=$socketName",
            throttleKey = "daemon_launch",
            throttleMs = 1000L
        )

        repeat(READY_ATTEMPTS) {
            if (SilenceDaemonClient.isAlive(context)) {
                val status = SilenceDaemonClient.getStatus(context)
                val daemonUid = status?.optInt("uid", -1) ?: -1
                if (daemonUid != 0) {
                    val details = listOf(
                        "daemonUid=$daemonUid",
                        "status=${status?.toString() ?: "empty"}"
                    )
                    Log.e(LOG_TAG, "daemon started without root details=${details.joinToString(" | ")}")
                    return DaemonStartResult(
                        ready = false,
                        summary = "silence-daemon 未以 Root 身份运行",
                        details = details,
                        code = DaemonStartCode.HANDSHAKE_FAILED,
                        pid = status?.optInt("pid", -1) ?: -1
                    )
                }
                val pid = status?.optInt("pid", -1) ?: -1
                return DaemonStartResult(
                    ready = true,
                    summary = "silence-daemon 已启动",
                    details = listOf("pid=$pid", "abi=$abi", "socket=tcp://$socketName"),
                    code = DaemonStartCode.CONNECTED,
                    pid = pid
                )
            }
            Thread.sleep(READY_DELAY_MS)
        }
        val failureDetails = buildList {
            add("abi=$abi")
            add("rootPath=$rootDaemonPath")
            add("launchSuccess=${launchResult.isSuccess}")
            addAll(readRootStatus(shell, statusPath))
            addAll(launchResult.err.take(3))
        }
        Log.e(LOG_TAG, "daemon handshake failed details=${failureDetails.joinToString(" | ")}")
        return DaemonStartResult(
            ready = false,
            summary = "silence-daemon 未能完成握手",
            details = failureDetails,
            code = DaemonStartCode.HANDSHAKE_FAILED
        )
    }

    private fun resolveDaemonAbi(context: Context): String? {
        return Build.SUPPORTED_ABIS.firstOrNull { candidate ->
            runCatching { context.assets.open("$candidate/$DAEMON_FILE_NAME").use { true } }
                .getOrDefault(false)
        }
    }

    private fun readRootStatus(shell: Shell, statusPath: String): List<String> {
        val result = shell.newJob().add("cat ${shellQuote(statusPath)} 2>/dev/null").exec()
        return buildList {
            addAll(result.out.take(2))
            addAll(result.err.take(2))
        }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\\''")}'"
    }

}
