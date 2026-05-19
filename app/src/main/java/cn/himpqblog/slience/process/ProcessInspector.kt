package cn.himpqblog.slience.process

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.hook.RuntimeLogStore
import cn.himpqblog.slience.settings.SettingsStore
import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import kotlin.math.max

object ProcessInspector {
    private const val ENABLE_IPC_LOG = false

    private const val FAST_LIST_PROCESS_CMD =
        """ps -A | grep 'u0_' | awk '{print ${'$'}2 "|" ${'$'}9}'"""
    private const val FAST_LIST_PROCESS_NAMES_ONLY_CMD =
        """ps -A | grep 'u0_' | awk '{print ${'$'}9}'"""
    private const val LEGACY_LIST_PROCESS_CMD =
        "(ps -A -o PID,NAME || ps -A -o PID,ARGS || ps -A)"
    private const val TOYBOX_PS_CMD = "toybox ps -A"
    private const val SYSTEM_TOYBOX_PS_CMD = "/system/bin/toybox ps -A"
    private const val SYSTEM_PS_CMD = "/system/bin/ps -A"
    private const val PROC_GLOB_CMD = "echo /proc/[0-9]*"
    private const val PROC_SAMPLE_CMD =
        "for d in /proc/[0-9]*; do pid=${'$'}{d##*/}; [ -r \"${'$'}d/cmdline\" ] || continue; cmd=${'$'}(tr '\\000' ' ' < \"${'$'}d/cmdline\"); [ -n \"${'$'}cmd\" ] && echo \"${'$'}pid|${'$'}cmd\"; done"
    private const val ROOT_PROC_SNAPSHOT_CMD =
        """read -r _ user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat; total=${'$'}((user + nice + system + idle + iowait + irq + softirq + steal + guest + guest_nice)); echo "TOTAL|${'$'}total"; for d in /proc/[0-9]*; do pid=${'$'}{d##*/}; [ -r "${'$'}d/status" ] || continue; uid=""; rss=0; while IFS=" 	" read -r key value _; do case "${'$'}key" in Uid:) uid=${'$'}value ;; VmRSS:) rss=${'$'}value ;; esac; done < "${'$'}d/status"; [ -z "${'$'}uid" ] && continue; [ "${'$'}uid" -lt 10000 ] && continue; proc=""; if [ -r "${'$'}d/comm" ]; then read -r proc < "${'$'}d/comm"; fi; [ -z "${'$'}proc" ] && continue; ticks=0; if [ -r "${'$'}d/stat" ]; then read -r stat_line < "${'$'}d/stat"; stat_tail=${'$'}{stat_line##*) }; set -- ${'$'}stat_tail; if [ ${'$'}# -ge 13 ]; then ticks=${'$'}(( ${'$'}12 + ${'$'}13 )); fi; fi; echo "${'$'}pid|${'$'}uid|${'$'}rss|${'$'}ticks|${'$'}proc"; done"""
    private const val CGROUP_FREEZE_BASE = "/sys/fs/cgroup/apps"

    private val cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val appMetaCache = ConcurrentHashMap<String, AppMeta>()
    private val uidPackageCache = ConcurrentHashMap<Int, List<String>>()
    private val missingMetaPackages = ConcurrentHashMap.newKeySet<String>()
    private val silenceManagedFrozenPackages = ConcurrentHashMap.newKeySet<String>()
    private val uidTrafficCache = ConcurrentHashMap<Int, TrafficCounter>()
    private val probeRunning = AtomicBoolean(false)
    private val rawSuLock = Any()
    private val processResolveExecutor = Executors.newFixedThreadPool(max(2, cpuCoreCount.coerceAtMost(4)))
    private const val ROOT_CHECK_CACHE_MS = 15_000L
    private const val PROCESS_LOG_THROTTLE_MS = 5_000L

    @Volatile
    private var rootVerifiedUntil = 0L

    @Volatile
    private var rawSuProcess: Process? = null

    @Volatile
    private var rawSuReader: BufferedReader? = null

    @Volatile
    private var rawSuWriter: BufferedWriter? = null

    @Volatile
    private var processCommandsRequireRawSu: Boolean = false

    @Volatile
    private var processDebugLogEnabled: Boolean = false

    private data class ExecResult(
        val out: List<String>,
        val err: List<String>,
        val isSuccess: Boolean,
        val code: Int
    )

    fun applyFreezeCommand(
        context: Context,
        packageName: String,
        freeze: Boolean,
        targetNames: Set<String>
    ): Boolean {
        val shell = runCatching { Shell.getShell() }.getOrNull() ?: return false
        val pm = context.packageManager
        val appInfo = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull() ?: return false
        val uid = appInfo.uid
        if (uid < 10000) {
            return false
        }
        val entries = collectEntriesForPackage(shell, uid, packageName)
        if (entries.isEmpty() && !freeze) {
            val uidOnlyResult = shell.newJob()
                .add("""writes=0; uid_file="$CGROUP_FREEZE_BASE/uid_${uid}/cgroup.freeze"; if [ -w "${'$'}uid_file" ]; then echo 0 > "${'$'}uid_file" && writes=${'$'}((writes+1)); fi; echo "writes|${'$'}writes" """)
                .exec()
            val uidOnlyWrites = uidOnlyResult.out.firstOrNull { it.startsWith("writes|") }
                ?.substringAfter("writes|")
                ?.toIntOrNull()
                ?: 0
            val uidOnlySuccess = uidOnlyResult.isSuccess && uidOnlyWrites > 0
            if (uidOnlySuccess) {
                silenceManagedFrozenPackages.remove(packageName)
                syncRuntimeMirrorIfPossible(context)
                return true
            }
        }
        if (entries.isEmpty()) {
            if (ENABLE_IPC_LOG) {
                RuntimeLogStore.appendDiagnostic(
                    source = "ipc",
                    message = "applyFreezeCommand entries empty package=$packageName uid=$uid",
                    throttleKey = "ipc_entries_empty_$packageName",
                    throttleMs = 3000L
                )
            }
            return false
        }
        val item = ProcessAppItem(
            packageName = packageName,
            appName = pm.getApplicationLabel(appInfo).toString(),
            icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull(),
            uid = uid,
            processCount = entries.size,
            frozenProcessCount = entries.count { it.isFrozen },
            processNames = entries.map { it.displayName }.distinct(),
            frozenProcessNames = entries.filter { it.isFrozen }.map { it.displayName }.toSet(),
            childProcessNames = entries.map { "${it.displayName}(${it.pid})" },
            processEntries = entries,
            isFrozen = entries.any { it.isFrozen },
            freezeMode = if (entries.any { it.isFrozen }) "V2" else "V2",
            memoryBytes = 0L,
            cpuPercent = 0.0
        )
        val targetSet = if (targetNames.isEmpty()) setOf("ALL") else targetNames
        val targetFrozen = freeze
        val command = buildFreezeToggleCommandForTarget(item, targetFrozen, targetSet)
        val result = shell.newJob().add(command).exec()
        val wrote = result.out.firstOrNull { it.startsWith("writes|") }
            ?.substringAfter("writes|")
            ?.toIntOrNull()
            ?: 0
        val success = result.isSuccess && wrote > 0
        if (success) {
            if (targetFrozen) {
                silenceManagedFrozenPackages.add(packageName)
            } else {
                silenceManagedFrozenPackages.remove(packageName)
            }
        }
        if (ENABLE_IPC_LOG) {
            RuntimeLogStore.appendDiagnostic(
                source = "ipc",
                message = "applyFreezeCommand package=$packageName freeze=$freeze wrote=$wrote exit=${result.code}",
                throttleKey = "ipc_apply_$packageName",
                throttleMs = 0L
            )
        }
        if (success) {
            syncRuntimeMirrorIfPossible(context)
        }
        return success
    }

    fun markManagedFrozen(packageName: String, frozen: Boolean) {
        if (frozen) {
            silenceManagedFrozenPackages.add(packageName)
        } else {
            silenceManagedFrozenPackages.remove(packageName)
        }
    }

    fun collect(context: Context, previous: CpuSnapshot?): ProcessCollectResult {
        processDebugLogEnabled = SettingsStore.isProcessDebugLogEnabled(context)
        val collectStart = SystemClock.elapsedRealtime()
        if (processDebugLogEnabled) {
            RuntimeLogStore.appendDiagnostic(
                "process-enter",
                "collect invoked previous=${if (previous == null) "null" else "present"}",
                "process_collect_enter",
                PROCESS_LOG_THROTTLE_MS
            )
        }
        val shell = runCatching { Shell.getShell() }.getOrElse { err ->
            RuntimeLogStore.appendDiagnostic(
                "process-enter",
                "getShell failed: ${err.message ?: err.javaClass.simpleName}",
                "process_collect_shell_error",
                PROCESS_LOG_THROTTLE_MS,
                RuntimeLogStore.LogCategory.ERROR
            )
            return ProcessCollectResult(
                items = emptyList(),
                snapshot = null,
                errorMessage = err.message ?: err.javaClass.simpleName
            )
        }

        val isRoot = if (System.currentTimeMillis() < rootVerifiedUntil) {
            true
        } else {
            val uidResult = Shell.cmd("id -u").exec()
            logCommandResult(
                source = "process-cmd",
                command = "id -u",
                outputLines = uidResult.out,
                errorLines = uidResult.err,
                success = uidResult.isSuccess,
                exitCode = uidResult.code,
                throttleKey = "process_cmd_id_u"
            )
            val granted = uidResult.isSuccess && uidResult.out.firstOrNull()?.trim() == "0"
            if (granted) {
                rootVerifiedUntil = System.currentTimeMillis() + ROOT_CHECK_CACHE_MS
            }
            granted
        }
        if (!isRoot) {
            return ProcessCollectResult(
                items = emptyList(),
                snapshot = null,
                errorMessage = "Root unavailable or command failed"
            )
        }

        collectFromRootProcSnapshot(context, shell, previous)?.let { return it }

        val listStart = SystemClock.elapsedRealtime()
        val listResult = execProcessCommand(
            shell = shell,
            source = "process-cmd",
            command = FAST_LIST_PROCESS_CMD,
            throttleKey = "process_cmd_list",
            displayCommand = FAST_LIST_PROCESS_NAMES_ONLY_CMD
        )
        val listCost = SystemClock.elapsedRealtime() - listStart
        if (!listResult.isSuccess || listResult.out.isEmpty()) {
            logPerf("collect", "list=${listCost}ms total=${SystemClock.elapsedRealtime() - collectStart}ms failed=list")
            collectFromRootProcSnapshot(context, shell, previous)?.let { return it }
            return ProcessCollectResult(
                items = emptyList(),
                snapshot = null,
                errorMessage = "Unable to read process list"
            )
        }

        val candidateProcesses = parseFastProcessList(listResult.out)
        if (processDebugLogEnabled) {
            RuntimeLogStore.appendDiagnostic(
                "process",
                "list-lines=${listResult.out.size} candidates=${candidateProcesses.size}",
                "process_candidates",
                PROCESS_LOG_THROTTLE_MS
            )
        }
        if (candidateProcesses.isEmpty()) {
            if (processDebugLogEnabled) {
                RuntimeLogStore.appendDiagnostic(
                    "process-enter",
                    "candidateProcesses empty, trying fallback collectors",
                    "process_collect_empty_candidates",
                    PROCESS_LOG_THROTTLE_MS
                )
            }
            val rootFallbackStart = SystemClock.elapsedRealtime()
            collectFromRootProcSnapshot(context, shell, previous)?.let {
                logPerf(
                    "collect",
                    "list=${listCost}ms rootProc=${SystemClock.elapsedRealtime() - rootFallbackStart}ms total=${SystemClock.elapsedRealtime() - collectStart}ms path=root-proc items=${it.items.size}"
                )
                return it
            }
            val javaProcCandidates = collectJavaProcProcesses()
            if (processDebugLogEnabled) {
                RuntimeLogStore.appendDiagnostic(
                    "process",
                    "java-proc candidates=${javaProcCandidates.size}",
                    "process_java_proc_candidates",
                    PROCESS_LOG_THROTTLE_MS
                )
            }
            if (javaProcCandidates.isNotEmpty()) {
                return buildResultFromSeeds(
                    context = context,
                    shell = shell,
                    seeds = javaProcCandidates,
                    previous = previous,
                    collectStart = collectStart,
                    listCost = listCost,
                    path = "java-fallback"
                )
            }
            launchProbeIfNeeded()
            logPerf("collect", "list=${listCost}ms total=${SystemClock.elapsedRealtime() - collectStart}ms path=empty")
            return ProcessCollectResult(
                items = emptyList(),
                snapshot = null,
                errorMessage = null
            )
        }

        val metricsStart = SystemClock.elapsedRealtime()
        val metricsResult = execProcessCommand(
            shell,
            "process-cmd",
            buildMetricsCommand(candidateProcesses.map { it.pid }),
            "process_cmd_metrics_main",
            "metrics for ${candidateProcesses.size} pids"
        )
        val metricsCost = SystemClock.elapsedRealtime() - metricsStart
        if (!metricsResult.isSuccess) {
            return buildResultFromSeeds(
                context = context,
                shell = shell,
                seeds = candidateProcesses,
                previous = previous,
                collectStart = collectStart,
                listCost = listCost,
                metricsCost = metricsCost,
                path = "seed-fallback"
            )
        }

        val metrics = parseMetrics(metricsResult.out)
        val snapshot = CpuSnapshot(
            totalTicks = metrics.totalTicks,
            pidTicks = metrics.pidTicks
        )

        val pm = context.packageManager
        val freezeInfo = loadFreezeInfo(
            shell,
            metrics.byPid.mapNotNull { (pid, metric) ->
                metric.uid.takeIf { it >= 10000 }?.let { pid to it }
            }.toMap()
        )
        val resolveStart = SystemClock.elapsedRealtime()
        val rows = candidateProcesses.mapNotNull { process ->
            val metric = metrics.byPid[process.pid] ?: return@mapNotNull null
            if (metric.uid < 10000) {
                return@mapNotNull null
            }
            val meta = resolveAppMetaForProcess(pm, shell, process.basePackage, metric.uid)
                ?: return@mapNotNull null
            if (meta.isSystemApp) {
                return@mapNotNull null
            }

            ResolvedProcessRow(
                pid = process.pid,
                uid = metric.uid,
                processName = process.processName,
                basePackage = meta.packageName,
                rssKb = metric.rssKb,
                cpuTicks = metric.cpuTicks,
                meta = meta,
                isFrozen = freezeInfo.isFrozen(metric.uid, process.pid)
            )
        }
        val resolveCost = SystemClock.elapsedRealtime() - resolveStart

        if (rows.isEmpty()) {
            return buildResultFromSeeds(
                context = context,
                shell = shell,
                seeds = candidateProcesses,
                previous = previous,
                collectStart = collectStart,
                listCost = listCost,
                metricsCost = metricsCost,
                path = "seed-fallback-empty-rows"
            )
        }

        val aggregateStart = SystemClock.elapsedRealtime()
        val items = buildProcessItems(
            rows = rows,
            previous = previous,
            currentTotalTicks = metrics.totalTicks,
            rootFrozenByUid = freezeInfo.rootFrozenByUid
        )
        val aggregateCost = SystemClock.elapsedRealtime() - aggregateStart
        logPerf(
            "collect",
            "list=${listCost}ms metrics=${metricsCost}ms resolve=${resolveCost}ms aggregate=${aggregateCost}ms total=${SystemClock.elapsedRealtime() - collectStart}ms path=main items=${items.size}"
        )

        return ProcessCollectResult(
            items = items,
            snapshot = snapshot,
            errorMessage = null
        )
    }

    fun toggleFreeze(item: ProcessAppItem, targetNames: Set<String> = emptySet()): Boolean {
        val targetFrozen = !item.isFrozen
        val command = buildFreezeToggleCommandForTarget(item, targetFrozen, targetNames)
        val shell = runCatching { Shell.getShell() }.getOrNull() ?: return false
        val libsuResult = shell.newJob().add(command).exec()
        val result = if (libsuResult.isSuccess && (libsuResult.out.isNotEmpty() || libsuResult.err.isNotEmpty())) {
            ExecResult(
                out = libsuResult.out,
                err = libsuResult.err,
                isSuccess = libsuResult.isSuccess,
                code = libsuResult.code
            )
        } else {
            execRawSu(command) ?: ExecResult(emptyList(), emptyList(), false, -1)
        }

        val wrote = result.out.firstOrNull { it.startsWith("writes|") }
            ?.substringAfter("writes|")
            ?.toIntOrNull()
            ?: 0

        val success = (result.isSuccess && wrote > 0) ||
            verifyFreezeState(shell, item, targetFrozen, targetNames)
        if (success) {
            if (targetFrozen) {
                silenceManagedFrozenPackages.add(item.packageName)
            } else {
                silenceManagedFrozenPackages.remove(item.packageName)
            }
            RuntimeLogStore.appendDiagnostic(
                "process",
                "toggle ${if (targetFrozen) "freeze" else "unfreeze"} ${item.packageName} writes=$wrote",
                "process_toggle_${item.packageName}",
                0L
            )
        } else {
            RuntimeLogStore.appendDiagnostic(
                "process",
                "toggle failed ${item.packageName} target=${if (targetFrozen) 1 else 0} exit=${result.code}",
                "process_toggle_failed_${item.packageName}",
                0L,
                RuntimeLogStore.LogCategory.ERROR
            )
        }
        return success
    }

    fun inspectRuntimeState(context: Context, item: ProcessAppItem): AppRuntimeState {
        val shell = runCatching { Shell.getShell() }.getOrNull() ?: return AppRuntimeState(false, false, false)
        val result = execProcessCommand(
            shell = shell,
            source = "process-cmd",
            command = buildRuntimeStateCommand(item),
            throttleKey = "runtime_state_${item.packageName}",
            displayCommand = "runtime state for ${item.packageName}"
        )
        val foregroundPackage = FreezeListStore.readForegroundState(context)?.packageName
        val isForeground = foregroundPackage == item.packageName
        if (!result.isSuccess) {
            return AppRuntimeState(false, false, isForeground, isForeground)
        }

        var audio = false
        var visible = false
        var networkUid: Int? = null
        result.out.forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("audio|") -> {
                    audio = line.substringAfter("audio|").trim() == "1"
                }

                line.startsWith("visible|") -> {
                    visible = line.substringAfter("visible|").trim() == "1"
                }

                line.startsWith("network_uid|") -> {
                    networkUid = line.substringAfter("network_uid|").trim().toIntOrNull()
                }
            }
        }
        val network = networkUid?.let { hasUidTrafficDelta(shell, it) } ?: false
        return AppRuntimeState(
            isAudioActive = audio,
            isNetworkActive = network,
            isVisible = visible || isForeground,
            isForeground = isForeground
        )
    }

    private fun buildProcessItems(
        rows: List<ResolvedProcessRow>,
        previous: CpuSnapshot?,
        currentTotalTicks: Long,
        rootFrozenByUid: Map<Int, Boolean>
    ): List<ProcessAppItem> {
        val totalDelta = if (previous == null || currentTotalTicks <= 0L) {
            0L
        } else {
            max(1L, currentTotalTicks - previous.totalTicks)
        }

        return rows.groupBy { it.basePackage }
            .map { (_, processes) ->
                val meta = processes.first().meta
                val rootFrozen = rootFrozenByUid[processes.first().uid] == true
                val processEntries = processes
                    .map { row ->
                        ProcessEntry(
                            pid = row.pid,
                            displayName = displayProcessName(row.processName),
                            isFrozen = rootFrozen || row.isFrozen
                        )
                    }
                    .sortedWith(
                        compareBy<ProcessEntry> { if (it.displayName == "main") 0 else 1 }
                            .thenBy { it.displayName }
                            .thenBy { it.pid }
                    )

                val processNames = processEntries
                    .map { it.displayName }
                    .distinct()
                val frozenProcessNames = processEntries
                    .filter { it.isFrozen }
                    .map { it.displayName }
                    .toSet()
                val childNames = processEntries.map { "${it.displayName}(${it.pid})" }
                val memoryBytes = processes.sumOf { it.rssKb } * 1024L
                val cpuPercent = if (previous == null || totalDelta <= 0L) {
                    0.0
                } else {
                    val delta = processes.sumOf { row ->
                        val oldTicks = previous.pidTicks[row.pid] ?: row.cpuTicks
                        max(0L, row.cpuTicks - oldTicks)
                    }
                    (delta * 100.0 * cpuCoreCount) / totalDelta
                }
                val isFrozen = processEntries.any { it.isFrozen }

                ProcessAppItem(
                    packageName = meta.packageName,
                    appName = meta.label,
                    icon = meta.icon,
                    uid = processes.first().uid,
                    processCount = processes.size,
                    frozenProcessCount = processEntries.count { it.isFrozen },
                    processNames = processNames,
                    frozenProcessNames = frozenProcessNames,
                    childProcessNames = childNames,
                    processEntries = processEntries,
                    isFrozen = isFrozen,
                    freezeMode = when {
                        !isFrozen -> "V2"
                        silenceManagedFrozenPackages.contains(meta.packageName) -> "V2"
                        else -> "SYSTEM_V2"
                    },
                    memoryBytes = memoryBytes,
                    cpuPercent = cpuPercent
                )
            }
            .sortedWith(
                compareByDescending<ProcessAppItem> { it.memoryBytes }
                    .thenByDescending { it.cpuPercent }
                    .thenBy { it.appName.lowercase(Locale.getDefault()) }
            )
    }

    private fun displayProcessName(processName: String): String {
        return processName.substringAfter(':', "").ifBlank { "main" }
    }

    private fun determineFreezeMode(packageName: String, isFrozen: Boolean): String {
        if (!isFrozen) {
            return "V2"
        }
        return if (silenceManagedFrozenPackages.contains(packageName)) "V2" else "SYSTEM_V2"
    }

    private fun buildFreezeToggleCommandForTarget(item: ProcessAppItem, frozen: Boolean, targetNames: Set<String>): String {
        val state = if (frozen) 1 else 0
        val pidList = resolveTargetEntries(item, targetNames).joinToString(" ") { it.pid.toString() }
        return """writes=0; uid_file="$CGROUP_FREEZE_BASE/uid_${item.uid}/cgroup.freeze"; for pid in $pidList; do pid_file="$CGROUP_FREEZE_BASE/uid_${item.uid}/pid_${'$'}pid/cgroup.freeze"; if [ -w "${'$'}pid_file" ]; then echo $state > "${'$'}pid_file" && writes=${'$'}((writes+1)); fi; done; if [ $state -eq 0 ] && [ -w "${'$'}uid_file" ]; then echo 0 > "${'$'}uid_file" && writes=${'$'}((writes+1)); fi; echo "writes|${'$'}writes" """
    }

    private fun collectEntriesForPackage(shell: Shell, uid: Int, packageName: String): List<ProcessEntry> {
        val command = """
            for d in /proc/[0-9]*; do
              pid=${'$'}{d##*/}
              status="${'$'}d/status"
              [ -r "${'$'}status" ] || continue
              proc_uid=""
              while IFS=" 	" read -r key value _; do
                case "${'$'}key" in
                  Uid:) proc_uid=${'$'}value; break ;;
                esac
              done < "${'$'}status"
              [ "${'$'}proc_uid" = "$uid" ] || continue
              proc=""
              if [ -r "${'$'}d/comm" ]; then
                read -r proc < "${'$'}d/comm"
              fi
              [ -z "${'$'}proc" ] && continue
              echo "${'$'}pid|${'$'}proc"
            done
        """.trimIndent()
        val result = execProcessCommand(
            shell = shell,
            source = if (ENABLE_IPC_LOG) "ipc-cmd" else "ipc-cmd-muted",
            command = command,
            throttleKey = if (ENABLE_IPC_LOG) "ipc_collect_$packageName" else "ipc_collect_muted_$packageName",
            displayCommand = "collect entries for $packageName uid=$uid"
        )
        if (!result.isSuccess) {
            return emptyList()
        }
        val entries = result.out.mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 2) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val proc = parts[1].trim()
            if (proc.isEmpty()) return@mapNotNull null
            val display = proc.substringAfter(':', "").ifBlank { "main" }
            ProcessEntry(pid = pid, displayName = display, isFrozen = false)
        }
        return entries
    }

    private fun buildRuntimeStateCommand(item: ProcessAppItem): String {
        val pidList = item.processEntries.joinToString(" ") { it.pid.toString() }
        val packageName = item.packageName
        return """
            audio=0
            visible=0
            uid=""
            for pid in $pidList; do
              status="/proc/${'$'}pid/status"
              [ -r "${'$'}status" ] || continue
              uid=${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' "${'$'}status")
              [ -n "${'$'}uid" ] && break
            done

            if [ -n "${'$'}uid" ]; then
              proc_state=${'$'}(cmd activity get-uid-state "${'$'}uid" 2>/dev/null | tr -d '\r')
              proc_state_num=${'$'}(echo "${'$'}proc_state" | grep -Eo '[-]?[0-9]+' | head -n 1)
              if [ -n "${'$'}proc_state_num" ] && [ "${'$'}proc_state_num" -le 2 ]; then
                visible=1
              elif echo "${'$'}proc_state" | grep -Eiq '^TOP$'; then
                visible=1
              fi
            fi

            if [ -n "${'$'}uid" ]; then
              if dumpsys audio 2>/dev/null | grep -E "uid[:= ]${'$'}uid\\b" | grep -Eiq "started|active|playback"; then
                audio=1
              fi
            fi
            if [ "${'$'}audio" -eq 0 ] && dumpsys media_session 2>/dev/null | grep -F "$packageName" -A 8 | grep -Eiq "state=3|PlaybackState.*state=3"; then
              audio=1
            fi
            if [ "${'$'}audio" -eq 0 ] && dumpsys audio 2>/dev/null | grep -F "$packageName" | grep -Eiq "started|active|playback"; then
              audio=1
            fi

            echo "audio|${'$'}audio"
            echo "network_uid|${'$'}uid"
            echo "visible|${'$'}visible"
        """.trimIndent()
    }

    private fun hasUidTrafficDelta(shell: Shell, uid: Int): Boolean {
        val current = readUidTraffic(shell, uid) ?: return false
        val previous = uidTrafficCache.put(uid, current)
        if (previous == null) {
            return false
        }
        return current.rxBytes > previous.rxBytes || current.txBytes > previous.txBytes
    }

    private fun readUidTraffic(shell: Shell, uid: Int): TrafficCounter? {
        val command = """
            if [ -r /proc/net/xt_qtaguid/stats ]; then
              awk -v uid="$uid" 'NR>1 && $4==uid {rx+=$6; tx+=$8} END {print (rx+0) "|" (tx+0)}' /proc/net/xt_qtaguid/stats
            elif [ -r /proc/net/xt_qtaguid/stats2 ]; then
              awk -v uid="$uid" 'NR>1 && $4==uid {rx+=$6; tx+=$8} END {print (rx+0) "|" (tx+0)}' /proc/net/xt_qtaguid/stats2
            else
              echo "0|0"
            fi
        """.trimIndent()
        val result = execProcessCommand(
            shell = shell,
            source = "process-cmd",
            command = command,
            throttleKey = "process_uid_traffic_$uid",
            displayCommand = "uid traffic $uid"
        )
        if (!result.isSuccess || result.out.isEmpty()) {
            return null
        }
        val line = result.out.firstOrNull()?.trim().orEmpty()
        val parts = line.split('|')
        if (parts.size != 2) {
            return null
        }
        val rx = parts[0].toLongOrNull() ?: return null
        val tx = parts[1].toLongOrNull() ?: return null
        return TrafficCounter(rxBytes = rx, txBytes = tx)
    }

    private fun verifyFreezeState(
        shell: Shell,
        item: ProcessAppItem,
        targetFrozen: Boolean,
        targetNames: Set<String>
    ): Boolean {
        val targets = resolveTargetEntries(item, targetNames)
        if (targets.isEmpty()) {
            return false
        }
        val freezeInfo = loadFreezeInfo(shell, targets.associate { it.pid to item.uid })
        return targets.all { freezeInfo.isFrozen(item.uid, it.pid) == targetFrozen }
    }

    private fun resolveTargetEntries(item: ProcessAppItem, targetNames: Set<String>): List<ProcessEntry> {
        if (targetNames.isEmpty() || targetNames.contains("ALL")) {
            return item.processEntries
        }
        return item.processEntries.filter { targetNames.contains(it.displayName) }
    }

    private fun parseProcessList(lines: List<String>): List<ProcessSeed> {
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("PID") || trimmed.startsWith("USER")) {
                return@mapNotNull null
            }

            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size < 2) {
                return@mapNotNull null
            }

            val pid = when {
                parts[0].toIntOrNull() != null -> parts[0].toInt()
                parts.size > 1 && parts[1].toIntOrNull() != null -> parts[1].toInt()
                else -> return@mapNotNull null
            }
            val processName = when {
                parts[0].toIntOrNull() != null -> parts.last().trim()
                else -> parts.last().trim()
            }
            if (processName.isEmpty()) {
                return@mapNotNull null
            }

            val basePackage = processName.substringBefore(':')
            if (!isLikelyPackageName(basePackage)) {
                return@mapNotNull null
            }

            ProcessSeed(
                pid = pid,
                processName = processName,
                basePackage = basePackage
            )
        }
    }

    private fun parseFastProcessList(lines: List<String>): List<ProcessSeed> {
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                return@mapNotNull null
            }

            val parts = trimmed.split('|', limit = 2)
            if (parts.size < 2) {
                return@mapNotNull null
            }

            val pid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
            val processName = parts[1].trim()
            if (processName.isEmpty()) {
                return@mapNotNull null
            }

            val basePackage = processName.substringBefore(':')
            if (!isLikelyPackageName(basePackage)) {
                return@mapNotNull null
            }

            ProcessSeed(
                pid = pid,
                processName = processName,
                basePackage = basePackage
            )
        }
    }

    private fun collectJavaProcProcesses(): List<ProcessSeed> {
        val procRoot = File("/proc")
        val dirs = procRoot.listFiles().orEmpty()
        return dirs.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val uid = readUidFromStatus(File(dir, "status"))
            if (uid == null || uid < 10000) {
                return@mapNotNull null
            }

            val processName = readProcessNameFromProc(dir) ?: return@mapNotNull null
            val basePackage = processName.substringBefore(':')
            if (!isLikelyPackageName(basePackage)) {
                return@mapNotNull null
            }

            ProcessSeed(
                pid = pid,
                processName = processName,
                basePackage = basePackage
            )
        }
    }

    private fun readUidFromStatus(statusFile: File): Int? {
        return runCatching {
            statusFile.useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
                    ?.split(Regex("\\s+"))
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
        }.getOrNull()
    }

    private fun readProcessNameFromProc(processDir: File): String? {
        runCatching {
            val bytes = File(processDir, "cmdline").readBytes()
            val text = bytes
                .takeWhile { it.toInt() != 0 }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
            if (text.isNotBlank()) {
                return text.substringBefore(' ')
            }
        }

        return runCatching {
            File(processDir, "comm").readText().trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun buildResultFromSeeds(
        context: Context,
        shell: Shell,
        seeds: List<ProcessSeed>,
        previous: CpuSnapshot?,
        collectStart: Long,
        listCost: Long,
        metricsCost: Long = 0L,
        path: String
    ): ProcessCollectResult {
        val fallbackMetricsStart = SystemClock.elapsedRealtime()
        val metricsResult = execProcessCommand(
            shell,
            "process-cmd",
            buildMetricsCommand(seeds.map { it.pid }),
            "process_cmd_metrics_fallback",
            "fallback metrics for ${seeds.size} pids"
        )
        val fallbackMetricsCost = SystemClock.elapsedRealtime() - fallbackMetricsStart
        val metrics = if (metricsResult.isSuccess) parseMetrics(metricsResult.out) else null
        val snapshot = metrics?.let {
            CpuSnapshot(
                totalTicks = it.totalTicks,
                pidTicks = it.pidTicks
            )
        } ?: previous

        val pm = context.packageManager
        val freezeInfo = loadFreezeInfo(
            shell,
            seeds.mapNotNull { seed ->
                metrics?.byPid?.get(seed.pid)?.uid?.takeIf { it >= 10000 }?.let { seed.pid to it }
            }.toMap()
        )
        val resolveStart = SystemClock.elapsedRealtime()
        val rows = resolveRowsParallel(seeds) { process ->
            val metric = metrics?.byPid?.get(process.pid)
            val packageHint = metric?.uid?.let { uid ->
                runCatching {
                    pm.getPackagesForUid(uid)?.firstOrNull()
                }.getOrNull()
            } ?: process.basePackage
            val meta = resolveAppMetaForProcess(pm, shell, packageHint, metric?.uid)
                ?: return@resolveRowsParallel null
            if (meta.isSystemApp) {
                return@resolveRowsParallel null
            }

            ResolvedProcessRow(
                pid = process.pid,
                uid = metric?.uid ?: -1,
                processName = process.processName,
                basePackage = meta.packageName,
                rssKb = metric?.rssKb ?: 0L,
                cpuTicks = metric?.cpuTicks ?: 0L,
                meta = meta,
                isFrozen = metric?.uid?.let { freezeInfo.isFrozen(it, process.pid) } == true
            )
        }
        val resolveCost = SystemClock.elapsedRealtime() - resolveStart

        RuntimeLogStore.appendDiagnostic(
            "process",
            "fallback rows=${rows.size} from=${seeds.size}",
            "process_fallback_rows",
            PROCESS_LOG_THROTTLE_MS
        )

        if (rows.isEmpty()) {
            logPerf(
                "collect",
                "list=${listCost}ms metrics=${metricsCost}ms fallbackMetrics=${fallbackMetricsCost}ms resolve=${resolveCost}ms total=${SystemClock.elapsedRealtime() - collectStart}ms path=$path items=0"
            )
            return ProcessCollectResult(
                items = emptyList(),
                snapshot = previous,
                errorMessage = null
            )
        }

        val aggregateStart = SystemClock.elapsedRealtime()
        val items = buildProcessItems(
            rows = rows,
            previous = previous,
            currentTotalTicks = metrics?.totalTicks ?: 0L,
            rootFrozenByUid = freezeInfo.rootFrozenByUid
        )
        val aggregateCost = SystemClock.elapsedRealtime() - aggregateStart
        logPerf(
            "collect",
            "list=${listCost}ms metrics=${metricsCost}ms fallbackMetrics=${fallbackMetricsCost}ms resolve=${resolveCost}ms aggregate=${aggregateCost}ms total=${SystemClock.elapsedRealtime() - collectStart}ms path=$path items=${items.size}"
        )

        return ProcessCollectResult(
            items = items,
            snapshot = snapshot,
            errorMessage = null
        )
    }

    private fun collectFromRootProcSnapshot(
        context: Context,
        shell: Shell,
        previous: CpuSnapshot?
    ): ProcessCollectResult? {
        val rootProcStart = SystemClock.elapsedRealtime()
        val result = execProcessCommand(
            shell,
            "process-cmd",
            ROOT_PROC_SNAPSHOT_CMD,
            "process_cmd_root_proc_snapshot"
        )
        val parsed = parseRootProcSnapshot(result.out)
        val rootProcCost = SystemClock.elapsedRealtime() - rootProcStart
        RuntimeLogStore.appendDiagnostic(
            "process",
            "root-proc rows=${parsed.rows.size}",
            "process_root_proc_rows",
            PROCESS_LOG_THROTTLE_MS
        )
        if (!result.isSuccess || parsed.rows.isEmpty()) {
            return null
        }

        val snapshot = CpuSnapshot(
            totalTicks = parsed.totalTicks,
            pidTicks = parsed.pidTicks
        )
        val pm = context.packageManager
        val freezeInfo = loadFreezeInfo(
            shell,
            parsed.rows.associate { it.pid to it.uid }
        )
        val resolveStart = SystemClock.elapsedRealtime()
        val rows = resolveRowsParallel(parsed.rows) { process ->
            val packageHint = runCatching {
                pm.getPackagesForUid(process.uid)
                    ?.firstOrNull()
            }.getOrNull() ?: process.basePackage
            val meta = resolveAppMetaForProcess(pm, shell, packageHint, process.uid)
                ?: return@resolveRowsParallel null
            if (meta.isSystemApp) {
                return@resolveRowsParallel null
            }

            ResolvedProcessRow(
                pid = process.pid,
                uid = process.uid,
                processName = process.processName,
                basePackage = meta.packageName,
                rssKb = process.rssKb,
                cpuTicks = process.cpuTicks,
                meta = meta,
                isFrozen = freezeInfo.isFrozen(process.uid, process.pid)
            )
        }
        val resolveCost = SystemClock.elapsedRealtime() - resolveStart
        if (rows.isEmpty()) {
            logPerf("root-proc", "collect=${rootProcCost}ms resolve=${resolveCost}ms items=0")
            return null
        }

        val aggregateStart = SystemClock.elapsedRealtime()
        val items = buildProcessItems(
            rows = rows,
            previous = previous,
            currentTotalTicks = parsed.totalTicks,
            rootFrozenByUid = freezeInfo.rootFrozenByUid
        )
        val aggregateCost = SystemClock.elapsedRealtime() - aggregateStart
        logPerf(
            "root-proc",
            "collect=${rootProcCost}ms resolve=${resolveCost}ms aggregate=${aggregateCost}ms total=${SystemClock.elapsedRealtime() - rootProcStart}ms items=${items.size}"
        )

        return ProcessCollectResult(
            items = items,
            snapshot = snapshot,
            errorMessage = null
        )
    }

    private fun buildMetricsCommand(pids: List<Int>): String {
        if (pids.isEmpty()) {
            return "echo TOTAL|0"
        }

        val pidList = pids.joinToString(" ")
        return """read -r _ user nice system idle iowait irq softirq steal guest guest_nice < /proc/stat; total=${'$'}((user + nice + system + idle + iowait + irq + softirq + steal + guest + guest_nice)); echo "TOTAL|${'$'}total"; for pid in $pidList; do status="/proc/${'$'}pid/status"; stat_file="/proc/${'$'}pid/stat"; [ -r "${'$'}status" ] || continue; uid=""; rss=0; while IFS=" 	" read -r key value _; do case "${'$'}key" in Uid:) uid=${'$'}value ;; VmRSS:) rss=${'$'}value ;; esac; done < "${'$'}status"; [ -z "${'$'}uid" ] && continue; ticks=0; if [ -r "${'$'}stat_file" ]; then read -r stat_line < "${'$'}stat_file"; stat_tail=${'$'}{stat_line##*) }; set -- ${'$'}stat_tail; if [ ${'$'}# -ge 13 ]; then ticks=${'$'}(( ${'$'}12 + ${'$'}13 )); fi; fi; echo "${'$'}pid|${'$'}uid|${'$'}rss|${'$'}ticks"; done"""
    }

    private fun parseMetrics(lines: List<String>): MetricSnapshot {
        var totalTicks = 0L
        val byPid = LinkedHashMap<Int, ProcessMetric>()
        val pidTicks = LinkedHashMap<Int, Long>()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            if (trimmed.startsWith("TOTAL|")) {
                totalTicks = trimmed.substringAfter("TOTAL|").toLongOrNull() ?: 0L
                return@forEach
            }

            val parts = trimmed.split('|')
            if (parts.size < 4) {
                return@forEach
            }

            val pid = parts[0].toIntOrNull() ?: return@forEach
            val uid = parts[1].toIntOrNull() ?: return@forEach
            val rssKb = parts[2].toLongOrNull() ?: 0L
            val cpuTicks = parts[3].toLongOrNull() ?: 0L

            val metric = ProcessMetric(
                uid = uid,
                rssKb = rssKb,
                cpuTicks = cpuTicks
            )
            byPid[pid] = metric
            pidTicks[pid] = cpuTicks
        }

        return MetricSnapshot(
            totalTicks = totalTicks,
            byPid = byPid,
            pidTicks = pidTicks
        )
    }

    private fun loadFreezeInfo(shell: Shell, pidToUid: Map<Int, Int>): FreezeInfo {
        val targets = pidToUid.values.filter { it >= 10000 }.distinct().sorted()
        if (targets.isEmpty()) {
            return FreezeInfo(emptyMap(), emptySet())
        }

        val command = buildFreezeStateCommand(targets, pidToUid.filterValues { it >= 10000 })
        val result = execProcessCommand(
            shell = shell,
            source = "process-cmd",
            command = command,
            throttleKey = "process_cmd_freeze_states",
            displayCommand = "cgroup.freeze for ${targets.size} uids / ${pidToUid.size} pids"
        )
        if (!result.isSuccess) {
            return FreezeInfo(emptyMap(), emptySet())
        }

        val rootFrozenByUid = LinkedHashMap<Int, Boolean>()
        val frozenPids = LinkedHashSet<Int>()
        result.out.forEach { line ->
            val parts = line.trim().split('|')
            when (parts.firstOrNull()) {
                "uid" -> {
                    val uid = parts.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    rootFrozenByUid[uid] = parts.getOrNull(2) == "1"
                }
                "pid" -> {
                    val pid = parts.getOrNull(2)?.toIntOrNull() ?: return@forEach
                    if (parts.getOrNull(3) == "1") {
                        frozenPids.add(pid)
                    }
                }
            }
        }
        return FreezeInfo(rootFrozenByUid, frozenPids)
    }

    private fun buildFreezeStateCommand(uids: List<Int>, pidToUid: Map<Int, Int>): String {
        val uidList = uids.joinToString(" ")
        val pidPairs = pidToUid.entries.joinToString(" ") { "${it.key}:${it.value}" }
        return """for uid in $uidList; do freeze_file="$CGROUP_FREEZE_BASE/uid_${'$'}uid/cgroup.freeze"; state=0; if [ -r "${'$'}freeze_file" ]; then read -r state < "${'$'}freeze_file"; fi; echo "uid|${'$'}uid|${'$'}state"; done; for pair in $pidPairs; do pid=${'$'}{pair%%:*}; uid=${'$'}{pair##*:}; freeze_file="$CGROUP_FREEZE_BASE/uid_${'$'}uid/pid_${'$'}pid/cgroup.freeze"; state=0; if [ -r "${'$'}freeze_file" ]; then read -r state < "${'$'}freeze_file"; fi; echo "pid|${'$'}uid|${'$'}pid|${'$'}state"; done"""
    }

    private fun resolveAppMeta(pm: PackageManager, shell: Shell, packageName: String): AppMeta? {
        appMetaCache[packageName]?.let {
            return it
        }
        if (missingMetaPackages.contains(packageName)) {
            return null
        }

        val meta = runCatching {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val icon = pm.getApplicationIcon(packageName)
            val isSystemApp = isSystemApp(appInfo, packageName)

            AppMeta(
                packageName = packageName,
                label = label,
                icon = icon,
                isSystemApp = isSystemApp
            )
        }.getOrNull()

        val resolvedMeta = meta ?: AppMeta(
            packageName = packageName,
            label = packageName,
            icon = pm.defaultActivityIcon,
            isSystemApp = isLikelySystemPackage(packageName)
        )

        if (resolvedMeta != null) {
            appMetaCache[packageName] = resolvedMeta
        } else {
            missingMetaPackages.add(packageName)
        }
        return resolvedMeta
    }

    private fun resolveAppMetaForProcess(
        pm: PackageManager,
        shell: Shell,
        packageHint: String,
        uid: Int?
    ): AppMeta? {
        val candidates = LinkedHashSet<String>()
        if (packageHint.isNotBlank()) {
            candidates.add(packageHint)
        }
        if (uid != null && uid >= 10000) {
            uidPackageCache.getOrPut(uid) {
                runCatching { pm.getPackagesForUid(uid) }
                    .getOrNull()
                    .orEmpty()
                    .toList()
            }
                .sortedWith(compareBy<String> { if (it == packageHint) 0 else 1 }.thenBy { it })
                .forEach { candidates.add(it) }
        }

        candidates.forEach { candidate ->
            val meta = resolveAppMeta(pm, shell, candidate) ?: return@forEach
            if (!meta.isSystemApp) {
                return meta
            }
        }

        return candidates.firstOrNull()?.let { resolveAppMeta(pm, shell, it) }
    }

    private fun isLikelyPackageName(value: String): Boolean {
        if (!value.contains('.')) return false
        if (value.contains(' ')) return false
        return value.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))
    }

    private fun isSystemApp(appInfo: ApplicationInfo, packageName: String): Boolean {
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return true
        if ((appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true

        val sourcePaths = listOfNotNull(
            appInfo.sourceDir,
            appInfo.publicSourceDir
        )
        if (sourcePaths.any { path ->
                path.startsWith("/system/") ||
                    path.startsWith("/product/") ||
                    path.startsWith("/system_ext/") ||
                    path.startsWith("/vendor/") ||
                    path.startsWith("/odm/") ||
                    path.startsWith("/apex/")
            }
        ) {
            return true
        }

        return isLikelySystemPackage(packageName)
    }

    private fun isLikelySystemPackage(packageName: String): Boolean {
        val value = packageName.lowercase(Locale.getDefault())
        return value.startsWith("android.") ||
            value.startsWith("com.android.") ||
            value.startsWith("com.google.android.") ||
            value.startsWith("com.mi.") ||
            value.startsWith("com.miui.") ||
            value.startsWith("com.xiaomi.") ||
            value.startsWith("com.milink.") ||
            value.startsWith("com.duokan.") ||
            value.startsWith("miui.") ||
            value.startsWith("vendor.") ||
            value.startsWith("product.") ||
            value.startsWith("com.qualcomm.") ||
            value.startsWith("org.codeaurora.")
    }

    private fun probeProcessEnvironment(shell: Shell) {
        try {
            RuntimeLogStore.appendDiagnostic(
                "process",
                "candidate list empty, probing shell environment",
                "process_probe_start",
                PROCESS_LOG_THROTTLE_MS
            )
            execProcessCommand(shell, "process-probe", "command -v ps", "process_probe_ps_path")
            execProcessCommand(shell, "process-probe", "command -v toybox", "process_probe_toybox_path")
            execProcessCommand(shell, "process-probe", TOYBOX_PS_CMD, "process_probe_toybox_ps")
            execProcessCommand(shell, "process-probe", SYSTEM_TOYBOX_PS_CMD, "process_probe_system_toybox_ps")
            execProcessCommand(shell, "process-probe", SYSTEM_PS_CMD, "process_probe_system_ps")
            execProcessCommand(shell, "process-probe", PROC_GLOB_CMD, "process_probe_proc_glob")
            execProcessCommand(shell, "process-probe", PROC_SAMPLE_CMD, "process_probe_proc_sample")
        } finally {
            probeRunning.set(false)
        }
    }

    private fun syncRuntimeMirrorIfPossible(context: Context) {
        runCatching {
            FreezeListStore.syncRuntimeMirror(context)
        }
    }

    private fun launchProbeIfNeeded() {
        if (!probeRunning.compareAndSet(false, true)) {
            return
        }
        Thread {
            try {
                probeProcessEnvironment(Shell.getShell())
            } catch (_: Throwable) {
                probeRunning.set(false)
            }
        }.start()
    }

    private fun execProcessCommand(
        shell: Shell,
        source: String,
        command: String,
        throttleKey: String,
        displayCommand: String = command
    ): ExecResult {
        if (processCommandsRequireRawSu) {
            val rawResult = execRawSu(command) ?: return ExecResult(
                out = emptyList(),
                err = emptyList(),
                isSuccess = false,
                code = -1
            )
            if (!source.endsWith("-muted")) {
                RuntimeLogStore.appendDiagnostic(
                    source = source,
                    message = "raw-su cached for $displayCommand => stdoutLines=${rawResult.out.size} stderrLines=${rawResult.err.size} exitCode=${rawResult.code}",
                    throttleKey = "${throttleKey}_raw_cached",
                    throttleMs = PROCESS_LOG_THROTTLE_MS
                )
            }
            return rawResult
        }

        val libsuResult = shell.newJob().add(command).exec()
        logCommandResult(
            source = source,
            command = displayCommand,
            outputLines = libsuResult.out,
            errorLines = libsuResult.err,
            success = libsuResult.isSuccess,
            exitCode = libsuResult.code,
            throttleKey = throttleKey
        )
        if (libsuResult.out.isNotEmpty() || libsuResult.err.isNotEmpty() || !libsuResult.isSuccess) {
            return ExecResult(
                out = libsuResult.out,
                err = libsuResult.err,
                isSuccess = libsuResult.isSuccess,
                code = libsuResult.code
            )
        }

        if (libsuResult.isSuccess && libsuResult.out.isEmpty() && libsuResult.err.isEmpty()) {
            processCommandsRequireRawSu = true
            if (!source.endsWith("-muted")) {
                RuntimeLogStore.appendDiagnostic(
                    source,
                    "switching process commands to raw-su for $displayCommand",
                    "${throttleKey}_switch_raw",
                    PROCESS_LOG_THROTTLE_MS
                )
            }
        }

        val rawResult = execRawSu(command) ?: return ExecResult(
            out = libsuResult.out,
            err = libsuResult.err,
            isSuccess = libsuResult.isSuccess,
            code = libsuResult.code
        )
        RuntimeLogStore.appendDiagnostic(
            source = source,
            message = "fallback raw-su for $displayCommand => stdoutLines=${rawResult.out.size} stderrLines=${rawResult.err.size} exitCode=${rawResult.code}",
            throttleKey = "${throttleKey}_raw",
            throttleMs = PROCESS_LOG_THROTTLE_MS
        )
        return rawResult
    }

    private fun execRawSu(command: String): ExecResult? {
        return runCatching {
            synchronized(rawSuLock) {
                ensureRawSuSession()
                val reader = rawSuReader ?: throw IllegalStateException("raw su reader unavailable")
                val writer = rawSuWriter ?: throw IllegalStateException("raw su writer unavailable")
                val marker = "__SILENCE_DONE_${System.nanoTime()}__"

                writer.write(command)
                writer.newLine()
                writer.write("echo $marker:$?")
                writer.newLine()
                writer.flush()

                val stdoutLines = ArrayList<String>()
                var exitCode = -1
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("$marker:")) {
                        exitCode = line.substringAfter(':').toIntOrNull() ?: -1
                        break
                    }
                    stdoutLines.add(line)
                }

                if (exitCode == -1) {
                    closeRawSuSession()
                }

                ExecResult(
                    out = stdoutLines,
                    err = emptyList(),
                    isSuccess = exitCode == 0,
                    code = exitCode
                )
            }
        }.getOrNull()
    }

    private fun ensureRawSuSession(): Process? {
        val current = rawSuProcess
        if (current != null && current.isAlive && rawSuReader != null && rawSuWriter != null) {
            return current
        }
        closeRawSuSession()
        return runCatching {
            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            rawSuReader = BufferedReader(InputStreamReader(process.inputStream))
            rawSuWriter = BufferedWriter(OutputStreamWriter(process.outputStream))
            rawSuProcess = process
            process
        }.getOrNull()
    }

    private fun closeRawSuSession() {
        runCatching { rawSuWriter?.close() }
        runCatching { rawSuReader?.close() }
        runCatching { rawSuProcess?.destroy() }
        rawSuWriter = null
        rawSuReader = null
        rawSuProcess = null
    }

    private fun logCommandResult(
        source: String,
        command: String,
        outputLines: List<String>,
        errorLines: List<String>,
        success: Boolean,
        exitCode: Int,
        throttleKey: String
    ) {
        if (!processDebugLogEnabled) {
            return
        }
        val hasOutput = outputLines.isNotEmpty() || errorLines.isNotEmpty()
        val isFailure = !success || exitCode != 0
        if (source.endsWith("-muted")) {
            return
        }
        if (isFailure) {
            RuntimeLogStore.appendDiagnostic(
                source,
                "cmd> $command\nsuccess=$success exitCode=$exitCode\nstdout:\n${formatSample(outputLines)}\nstderr:\n${formatSample(errorLines)}",
                "${throttleKey}_failure",
                PROCESS_LOG_THROTTLE_MS,
                RuntimeLogStore.LogCategory.ERROR
            )
            return
        }

        if (!hasOutput) {
            RuntimeLogStore.appendDiagnostic(
                source,
                "$command => empty output",
                "${throttleKey}_empty",
                PROCESS_LOG_THROTTLE_MS
            )
        }
    }

    private fun logPerf(stage: String, message: String) {
        if (!processDebugLogEnabled) {
            return
        }
        RuntimeLogStore.appendDiagnostic(
            "process-perf",
            "$stage $message",
            "process_perf_$stage",
            PROCESS_LOG_THROTTLE_MS
        )
    }

    private fun formatSample(lines: List<String>): String {
        val sample = lines.take(30)
        if (sample.isEmpty()) {
            return "<empty>"
        }
        return buildString {
            append(sample.joinToString("\n"))
            if (lines.size > sample.size) {
                append("\n<truncated ")
                append(lines.size - sample.size)
                append(" more lines>")
            }
        }
    }

    private fun <T> resolveRowsParallel(
        sources: List<T>,
        resolver: (T) -> ResolvedProcessRow?
    ): List<ResolvedProcessRow> {
        if (sources.isEmpty()) {
            return emptyList()
        }
        val futures = processResolveExecutor.invokeAll(
            sources.map { source -> Callable { resolver(source) } }
        )
        return futures.mapNotNull { future -> runCatching { future.get() }.getOrNull() }
    }

    private fun parseRootProcSnapshot(lines: List<String>): RootProcSnapshot {
        var totalTicks = 0L
        val pidTicks = LinkedHashMap<Int, Long>()
        val rows = ArrayList<RootProcRow>()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed.startsWith("TOTAL|")) {
                totalTicks = trimmed.substringAfter("TOTAL|").toLongOrNull() ?: 0L
                return@forEach
            }

            val parts = trimmed.split('|')
            if (parts.size < 5) return@forEach

            val pid = parts[0].toIntOrNull() ?: return@forEach
            val uid = parts[1].toIntOrNull() ?: return@forEach
            val rssKb = parts[2].toLongOrNull() ?: 0L
            val cpuTicks = parts[3].toLongOrNull() ?: 0L
            val processName = parts[4].trim()
            if (uid < 10000 || processName.isBlank()) return@forEach

            val basePackage = processName.substringBefore(':')
            if (!isLikelyPackageName(basePackage)) return@forEach

            rows.add(
                RootProcRow(
                    pid = pid,
                    uid = uid,
                    processName = processName,
                    basePackage = basePackage,
                    rssKb = rssKb,
                    cpuTicks = cpuTicks
                )
            )
            pidTicks[pid] = cpuTicks
        }

        return RootProcSnapshot(
            totalTicks = totalTicks,
            pidTicks = pidTicks,
            rows = rows
        )
    }

    private data class ProcessSeed(
        val pid: Int,
        val processName: String,
        val basePackage: String
    )

    private data class ProcessMetric(
        val uid: Int,
        val rssKb: Long,
        val cpuTicks: Long
    )

    private data class MetricSnapshot(
        val totalTicks: Long,
        val byPid: Map<Int, ProcessMetric>,
        val pidTicks: Map<Int, Long>
    )

    private data class FreezeInfo(
        val rootFrozenByUid: Map<Int, Boolean>,
        val frozenPids: Set<Int>
    ) {
        fun isFrozen(uid: Int, pid: Int): Boolean {
            return rootFrozenByUid[uid] == true || frozenPids.contains(pid)
        }
    }

    private data class ResolvedProcessRow(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val basePackage: String,
        val rssKb: Long,
        val cpuTicks: Long,
        val meta: AppMeta,
        val isFrozen: Boolean
    )

    private data class RootProcRow(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val basePackage: String,
        val rssKb: Long,
        val cpuTicks: Long
    )

    private data class RootProcSnapshot(
        val totalTicks: Long,
        val pidTicks: Map<Int, Long>,
        val rows: List<RootProcRow>
    )

    private data class AppMeta(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val isSystemApp: Boolean
    )

    private data class TrafficCounter(
        val rxBytes: Long,
        val txBytes: Long
    )
}

