package cn.himpqblog.slience.hook

import android.os.IBinder
import android.util.Log
import cn.himpqblog.slience.config.FreezeListStore
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("unused")
class HookLegacy : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "SlienceHook"
        private const val TARGET_PACKAGE = "android"
        private val TARGET_PROCESSES = setOf("android", "system", "system_server")
        private const val BACKGROUND_STATE_THRESHOLD = 10
        private const val FREEZE_LIST_ASSET = "FreezeList.json"

        @Volatile
        private var moduleApkPath: String? = null

        private val hookCallbackSeen = AtomicBoolean(false)
        private val freezeRulesLoaded = AtomicBoolean(false)
        private val lastProcStateByPid = ConcurrentHashMap<Int, Int>()
        private val installedProcesses = ConcurrentHashMap.newKeySet<String>()
        private val frozenPackages = ConcurrentHashMap.newKeySet<String>()
        private val totalHookCount = AtomicInteger(0)
        private val packageDesiredBackground = ConcurrentHashMap<String, Boolean>()
        private val packageSignalVersion = ConcurrentHashMap<String, Int>()
        private val packageSignalSource = ConcurrentHashMap<String, String>()
        private val packageLastDesiredState = ConcurrentHashMap<String, Boolean>()
        private val packageLastSignalAt = ConcurrentHashMap<String, Long>()
        private val packageLastCommitAt = ConcurrentHashMap<String, Long>()
        private val packageLastCommittedBackground = ConcurrentHashMap<String, Boolean>()
        private val packageForegroundPids = ConcurrentHashMap<String, MutableSet<Int>>()
        private val packageStateScheduler = Executors.newSingleThreadScheduledExecutor()
        private val pollScheduler = Executors.newSingleThreadScheduledExecutor()
        private val packageStateLock = Any()
        private val pollStarted = AtomicBoolean(false)
        private val pollTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val uidTrafficCache = ConcurrentHashMap<Int, TrafficBytes>()
        private const val STABLE_STATE_WINDOW_MS = 1200L
        private const val PACKAGE_SIGNAL_DEBOUNCE_MS = 1500L
        private const val MIN_COMMIT_SWITCH_INTERVAL_MS = 2500L
        private const val DEFAULT_BACKGROUND_POLL_INTERVAL_MS = 30_000L
        private const val ENABLE_BRIDGE_HOT_LOGS = false
        private const val PROCESS_STATE_TOP_THRESHOLD = 2
        private const val PROCESS_STATE_FOREGROUND_THRESHOLD = 6
        private const val PROCESS_STATE_VISIBLE_THRESHOLD = 8

        @Volatile
        private var lastPollAtMs: Long = 0L

        @Volatile
        private var freezeRules: Map<String, FreezeRule> = emptyMap()
    }

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        moduleApkPath = startupParam.modulePath
        XposedBridge.log("$TAG initZygote: modulePath=${startupParam.modulePath}")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }

        logHookDebug("handleLoadPackage package=${lpparam.packageName} process=${lpparam.processName}")
        ensureFreezeRulesLoaded()
        startBackgroundPollIfNeeded()
        XposedBridge.log("$TAG handleLoadPackage: package=${lpparam.packageName}, process=${lpparam.processName}")

        if (lpparam.processName !in TARGET_PROCESSES) {
            XposedBridge.log("$TAG skip process: ${lpparam.processName}")
            return
        }

        if (installedProcesses.contains(lpparam.processName)) {
            return
        }

        synchronized(HookLegacy::class.java) {
            if (installedProcesses.contains(lpparam.processName)) {
                return
            }
            val hooks = installHooksForProcess(lpparam.classLoader, lpparam.processName)
            if (hooks > 0) {
                installedProcesses.add(lpparam.processName)
                val aggregate = totalHookCount.addAndGet(hooks)
                XposedBridge.log("$TAG hooks installed in ${lpparam.processName}: $hooks (total=$aggregate)")
            } else {
                XposedBridge.log("$TAG no hooks installed in ${lpparam.processName}, trying deferred ActivityThread hook...")
                deferHookToSystemClassLoader(lpparam.classLoader, lpparam.processName)
            }
        }
    }

    private fun logHookDebug(message: String) {
        if (ENABLE_BRIDGE_HOT_LOGS) {
            XposedBridge.log("$TAG $message")
        }
        runCatching {
            Log.i("Silence", "Silence|hook|$message")
        }
    }

    private fun ensureFreezeRulesLoaded() {
        if (!freezeRulesLoaded.compareAndSet(false, true)) {
            return
        }
        freezeRules = loadFreezeRulesFromAsset()
        XposedBridge.log("$TAG freeze rules loaded: apps=${freezeRules.size}")
        logHookDebug("freeze rules loaded apps=${freezeRules.size}")
    }

    private fun loadFreezeRulesFromAsset(): Map<String, FreezeRule> {
        loadFreezeRulesFromRuntimeFile()?.let { return it }

        val modulePath = moduleApkPath
        if (modulePath.isNullOrEmpty()) {
            XposedBridge.log("$TAG freeze rules skipped: modulePath unavailable")
            return emptyMap()
        }

        return runCatching {
            val assetManagerClass = Class.forName("android.content.res.AssetManager")
            val assetManager = assetManagerClass.getDeclaredConstructor().newInstance()

            val addAssetPathMethod = assetManagerClass.getMethod("addAssetPath", String::class.java)
            val cookie = (addAssetPathMethod.invoke(assetManager, modulePath) as? Int) ?: 0
            if (cookie <= 0) {
                XposedBridge.log("$TAG freeze rules load failed: addAssetPath cookie=$cookie")
                return emptyMap()
            }

            val openMethod = assetManagerClass.getMethod("open", String::class.java)
            val stream = openMethod.invoke(assetManager, FREEZE_LIST_ASSET) as? InputStream
                ?: return emptyMap()

            val rawJson = stream.use { String(it.readBytes(), Charsets.UTF_8) }
            parseFreezeRules(rawJson)
        }.onFailure { err ->
            XposedBridge.log("$TAG freeze rules load error: ${err.message}")
        }.getOrDefault(emptyMap())
    }

    private fun loadFreezeRulesFromRuntimeFile(): Map<String, FreezeRule>? {
        val file = File(FreezeListStore.runtimeConfigPath())
        if (!file.exists() || !file.canRead()) {
            return null
        }
        return runCatching {
            parseFreezeRules(file.readText(Charsets.UTF_8))
        }.onFailure { err ->
            XposedBridge.log("$TAG freeze rules runtime load error: ${err.message}")
        }.getOrNull()
    }

    private fun parseFreezeRules(rawJson: String): Map<String, FreezeRule> {
        val result = linkedMapOf<String, FreezeRule>()
        runCatching {
            val root = JSONObject(rawJson)
            val apps = root.optJSONObject("apps") ?: return@runCatching
            val keys = apps.keys()
            while (keys.hasNext()) {
                val packageName = keys.next()
                val rule = apps.optJSONObject(packageName) ?: continue
                val freezeProcesses = rule.optJSONArray("freeze_processes").asStringList()
                val dontFreezeWhen = rule.optJSONArray("dont_freeze_when").asStringList()
                result[packageName] = FreezeRule(
                    freezeProcesses = freezeProcesses,
                    dontFreezeWhen = dontFreezeWhen,
                    whitelist = rule.optBoolean("whitelist", false)
                )
            }
        }.onFailure { err ->
            XposedBridge.log("$TAG freeze rules parse error: ${err.message}")
        }
        return result
    }

    private fun JSONArray?.asStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) {
            val value = optString(i)
            if (value.isNotBlank()) {
                list.add(value)
            }
        }
        return list
    }

    private fun deferHookToSystemClassLoader(classLoader: ClassLoader, processName: String) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.app.ActivityThread",
                classLoader,
                "systemMain",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val activityThread = param.result ?: return
                            val context = XposedHelpers.callMethod(activityThread, "getSystemContext") ?: return
                            val systemClassLoader = context.javaClass.classLoader ?: return

                            val deferredHooks = installHooksForProcess(systemClassLoader, "deferred_system_server")
                            if (deferredHooks > 0) {
                                installedProcesses.add(processName)
                                installedProcesses.add("deferred_system_server")
                                val aggregate = totalHookCount.addAndGet(deferredHooks)
                                XposedBridge.log("$TAG DEFERRED hooks installed: $deferredHooks (total=$aggregate)")
                            } else {
                                XposedBridge.log("$TAG DEFERRED install also yielded 0 hooks.")
                            }
                        } catch (e: Throwable) {
                            XposedBridge.log("$TAG deferred callback error: ${e.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG successfully deferred hook via ActivityThread.systemMain")
        }.onFailure { err ->
            XposedBridge.log("$TAG failed to attach ActivityThread: ${err.message}")
        }
    }

    private fun installHooksForProcess(classLoader: ClassLoader, processName: String): Int {
        var totalHooks = 0

        totalHooks += hookByClassAndMethods(
            classLoader = classLoader,
            className = "com.android.server.am.ProcessStateRecord",
            methodNames = listOf(
                "setCurProcState",
                "setCurRawProcState"
            )
        )

        totalHooks += installAtmsHooks(classLoader)

        XposedBridge.log("$TAG installHooksForProcess result: process=$processName, hooks=$totalHooks")
        return totalHooks
    }

    private fun installAtmsHooks(classLoader: ClassLoader): Int {
        val atmsClass = runCatching {
            XposedHelpers.findClass("com.android.server.wm.ActivityTaskManagerService", classLoader)
        }.getOrElse { err ->
            XposedBridge.log("$TAG class unavailable: com.android.server.wm.ActivityTaskManagerService (${err.message})")
            return 0
        }

        var hookedCount = 0
        val methods = listOf("onRecentTaskMovedToBack", "onActivityPaused", "onActivityResumed")

        methods.forEach { methodName ->
            runCatching {
                val unhooks = XposedBridge.hookAllMethods(atmsClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val movedToBackground = methodName != "onActivityResumed"
                        handleAtmsSignal(
                            source = "ATMS#$methodName",
                            atmsService = param.thisObject,
                            args = param.args,
                            movedToBackground = movedToBackground
                        )
                    }
                })
                if (unhooks.isNotEmpty()) {
                    hookedCount += unhooks.size
                    XposedBridge.log("$TAG hooked ${atmsClass.name}#$methodName count=${unhooks.size}")
                }
            }.onFailure { err ->
                XposedBridge.log("$TAG hook failed ${atmsClass.name}#$methodName (${err.message})")
            }
        }

        return hookedCount
    }

    private fun handleAtmsSignal(
        source: String,
        atmsService: Any?,
        args: Array<Any?>,
        movedToBackground: Boolean
    ) {
        val packageName = resolvePackageNameFromAtmsSignal(atmsService, args)
        if (packageName.isNullOrBlank()) {
            return
        }
        signalPackageState(packageName, movedToBackground, source)
    }

    private fun resolvePackageNameFromAtmsSignal(atmsService: Any?, args: Array<Any?>): String? {
        args.firstOrNull { it is IBinder }?.let { token ->
            val pkg = getPackageFromActivityToken(atmsService, token as IBinder)
            if (!pkg.isNullOrBlank()) return pkg
        }

        args.firstOrNull { it is Int }?.let { value ->
            val pkg = getPackageFromTaskId(atmsService, value as Int)
            if (!pkg.isNullOrBlank()) return pkg
        }

        args.forEach { arg ->
            val candidate = arg?.toString()?.trim().orEmpty()
            if (isLikelyPackageName(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun getPackageFromActivityToken(atmsService: Any?, token: IBinder): String? {
        val loader = atmsService?.javaClass?.classLoader ?: return null

        return runCatching {
            val recordClass = Class.forName("com.android.server.wm.ActivityRecord", false, loader)
            val method = recordClass.declaredMethods.firstOrNull { m ->
                m.name in setOf("forTokenLocked", "forToken") &&
                    m.parameterTypes.size == 1 &&
                    IBinder::class.java.isAssignableFrom(m.parameterTypes[0])
            } ?: return null

            method.isAccessible = true
            val record = method.invoke(null, token) ?: return null
            extractPackageNameFromRecord(record)
        }.getOrNull()
    }

    private fun getPackageFromTaskId(atmsService: Any?, taskId: Int): String? {
        val service = atmsService ?: return null

        invokeIntMethodIfPresent(service, taskId, listOf("anyTaskForId", "getTask"))?.let { task ->
            extractPackageNameFromTask(task)?.let { return it }
        }

        readObject(service, listOf("mRootWindowContainer", "mTaskSupervisor", "mRecentTasks"))?.let { holder ->
            invokeIntMethodIfPresent(holder, taskId, listOf("anyTaskForId", "getTask", "getTaskById"))?.let { task ->
                extractPackageNameFromTask(task)?.let { return it }
            }
        }

        return null
    }

    private fun invokeIntMethodIfPresent(target: Any, intArg: Int, names: List<String>): Any? {
        val methods = target.javaClass.methods + target.javaClass.declaredMethods
        names.forEach { name ->
            methods.firstOrNull { method ->
                method.name == name &&
                    method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    val args = buildDefaultArgs(method.parameterTypes).toMutableList()
                    args[0] = intArg
                    method.invoke(target, *args.toTypedArray())
                }.getOrNull()
            }
        }
        return null
    }

    private fun buildDefaultArgs(parameterTypes: Array<Class<*>>): List<Any?> {
        return parameterTypes.map { type ->
            when (type) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Char::class.javaPrimitiveType -> '\u0000'
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                String::class.java -> ""
                else -> null
            }
        }
    }

    private fun extractPackageNameFromTask(task: Any): String? {
        readString(task, listOf("getPackageName"), listOf("packageName", "mPackageName"))?.let { return it }

        readObject(task, listOf("realActivity", "origActivity", "topActivity", "mActivityComponent"))?.let { component ->
            componentToPackageName(component)?.let { return it }
        }

        readObject(task, listOf("intent", "affinityIntent", "mIntent"))?.let { intent ->
            extractPackageNameFromIntent(intent)?.let { return it }
        }

        readString(task, emptyList(), listOf("mCallingPackage", "callingPackage"))?.let { return it }
        return null
    }

    private fun extractPackageNameFromRecord(record: Any): String? {
        readString(record, listOf("getPackageName"), listOf("packageName", "mPackageName"))?.let { return it }

        readObject(record, listOf("mActivityComponent", "activityComponent", "realActivity", "origActivity"))?.let { component ->
            componentToPackageName(component)?.let { return it }
        }

        readObject(record, listOf("intent", "mIntent"))?.let { intent ->
            extractPackageNameFromIntent(intent)?.let { return it }
        }

        readObject(record, listOf("info", "activityInfo"))?.let { info ->
            readString(info, emptyList(), listOf("packageName"))?.let { return it }
            readObject(info, listOf("applicationInfo"))?.let { appInfo ->
                readString(appInfo, emptyList(), listOf("packageName"))?.let { return it }
            }
        }

        readObject(record, listOf("task", "mTask"))?.let { task ->
            extractPackageNameFromTask(task)?.let { return it }
        }

        return null
    }

    private fun extractPackageNameFromIntent(intent: Any): String? {
        runCatching { XposedHelpers.callMethod(intent, "getComponent") }
            .getOrNull()
            ?.let { component -> componentToPackageName(component)?.let { return it } }

        readObject(intent, listOf("mComponent"))?.let { component ->
            componentToPackageName(component)?.let { return it }
        }
        return null
    }

    private fun componentToPackageName(component: Any): String? {
        runCatching { XposedHelpers.callMethod(component, "getPackageName")?.toString() }
            .getOrNull()
            ?.let { if (isLikelyPackageName(it)) return it }

        val fallback = component.toString()
        if (fallback.contains("/")) {
            val pkg = fallback.substringBefore("/")
            if (isLikelyPackageName(pkg)) return pkg
        }
        return null
    }

    private fun hookByClassAndMethods(
        classLoader: ClassLoader,
        className: String,
        methodNames: List<String>
    ): Int {
        val clazz = runCatching { XposedHelpers.findClass(className, classLoader) }
            .getOrElse { err ->
                XposedBridge.log("$TAG class unavailable: $className (${err.message})")
                return 0
            }

        var hookedCount = 0

        methodNames.forEach { methodName ->
            runCatching {
                val unhooks = XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val processRecord = findProcessRecordArg(param.args) ?: findProcessRecordFromObject(param.thisObject)
                        val snapshot = processRecord?.let { readProcessSnapshot(it) } ?: return
                        onProcessStateObserved(snapshot, "${clazz.simpleName}#$methodName", processRecord)
                    }
                })
                if (unhooks.isNotEmpty()) {
                    hookedCount += unhooks.size
                    XposedBridge.log("$TAG hooked ${clazz.name}#$methodName count=${unhooks.size}")
                }
            }.onFailure { err ->
                XposedBridge.log("$TAG hook failed ${clazz.name}#$methodName (${err.message})")
            }
        }

        return hookedCount
    }

    private fun onProcessStateObserved(snapshot: ProcessSnapshot, source: String, processRecord: Any) {
        if (hookCallbackSeen.compareAndSet(false, true)) {
            XposedBridge.log(
                "$TAG first callback hit: source=$source name=${snapshot.processName} pid=${snapshot.pid}"
            )
        }

        if (snapshot.pid <= 0) {
            return
        }

        val previousState = lastProcStateByPid.put(snapshot.pid, snapshot.procState) ?: return
        val movedToBackground =
            previousState < BACKGROUND_STATE_THRESHOLD && snapshot.procState >= BACKGROUND_STATE_THRESHOLD
        val movedToForeground =
            previousState >= BACKGROUND_STATE_THRESHOLD && snapshot.procState < BACKGROUND_STATE_THRESHOLD

        val packageName = extractPackageNameFromProcessName(snapshot.processName)
        if (packageName != null && (movedToBackground || movedToForeground)) {
            handlePidStateTransition(
                packageName = packageName,
                pid = snapshot.pid,
                movedToBackground = movedToBackground,
                movedToForeground = movedToForeground,
                source = source
            )
        }

        if (movedToBackground) {
            onProcessMovedToBackground(snapshot, processRecord)
        }
    }

    private fun onProcessMovedToBackground(snapshot: ProcessSnapshot, processRecord: Any) {
        // Placeholder reserved for future real freeze action.
    }

    private fun handlePidStateTransition(
        packageName: String,
        pid: Int,
        movedToBackground: Boolean,
        movedToForeground: Boolean,
        source: String
    ) {
        var shouldSignal = false
        var desiredBackground = true
        val now = System.currentTimeMillis()
        synchronized(packageStateLock) {
            val foregroundSet = packageForegroundPids.getOrPut(packageName) { mutableSetOf() }
            if (movedToForeground) {
                foregroundSet.add(pid)
            }
            if (movedToBackground) {
                foregroundSet.remove(pid)
            }
            desiredBackground = foregroundSet.isEmpty()

            val previousDesired = packageLastDesiredState[packageName]
            val lastSignal = packageLastSignalAt[packageName] ?: 0L
            if (previousDesired == desiredBackground && (now - lastSignal) < PACKAGE_SIGNAL_DEBOUNCE_MS) {
                return
            }
            if (previousDesired == desiredBackground) {
                return
            }
            packageLastDesiredState[packageName] = desiredBackground
            packageLastSignalAt[packageName] = now
            shouldSignal = true
        }

        if (shouldSignal) {
            signalPackageState(packageName, desiredBackground, "$source#agg")
        }
    }

    private fun signalPackageState(packageName: String, movedToBackground: Boolean, source: String) {
        val normalized = packageName.trim()
        if (!isLikelyPackageName(normalized)) {
            return
        }
        val hasRule = freezeRules.containsKey(normalized)
        if (!hasRule && !frozenPackages.contains(normalized)) {
            return
        }

        packageDesiredBackground[normalized] = movedToBackground
        packageSignalSource[normalized] = source
        val nextVersion = (packageSignalVersion[normalized] ?: 0) + 1
        packageSignalVersion[normalized] = nextVersion

        if (!movedToBackground) {
            commitForegroundState(normalized, "$source#immediate")
            return
        }

        packageStateScheduler.schedule({
            val latestVersion = packageSignalVersion[normalized] ?: return@schedule
            if (latestVersion != nextVersion) {
                return@schedule
            }

            val desiredBackground = packageDesiredBackground[normalized] ?: return@schedule
            val signalSource = packageSignalSource[normalized].orEmpty()

            if (desiredBackground) {
                commitBackgroundState(normalized, signalSource)
            } else {
                commitForegroundState(normalized, signalSource)
            }
        }, STABLE_STATE_WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    private fun commitBackgroundState(packageName: String, source: String) {
        if (frozenPackages.contains(packageName)) {
            return
        }
        val now = System.currentTimeMillis()
        val lastCommit = packageLastCommitAt[packageName] ?: 0L
        val lastBackground = packageLastCommittedBackground[packageName]
        if (lastBackground == true && now - lastCommit < MIN_COMMIT_SWITCH_INTERVAL_MS) {
            return
        }
        val rule = freezeRules[packageName] ?: return
        frozenPackages.add(packageName)
        packageLastCommitAt[packageName] = now
        packageLastCommittedBackground[packageName] = true

        val processText = if (rule.freezeProcesses.isEmpty()) {
            "*"
        } else {
            rule.freezeProcesses.joinToString(", ")
        }

        logHookDebug("\u8fdb\u5165\u540e\u53f0 $packageName")
        logHookDebug("\u51bb\u7ed3 $packageName:{$processText}")
    }

    private fun commitForegroundState(packageName: String, source: String) {
        if (!frozenPackages.remove(packageName)) {
            return
        }
        val now = System.currentTimeMillis()
        packageLastCommitAt[packageName] = now
        packageLastCommittedBackground[packageName] = false
        val processText = freezeRules[packageName]?.freezeProcesses?.joinToString(", ").orEmpty()
        if (processText.isNotBlank()) {
            logHookDebug("\u89e3\u51bb $packageName:{$processText}")
        } else {
            logHookDebug("\u89e3\u51bb $packageName")
        }
    }

    private fun startBackgroundPollIfNeeded() {
        if (!pollStarted.compareAndSet(false, true)) {
            return
        }
        val delay = resolveHookPollIntervalMs()
        logHookDebug("[\u8f6e\u8be2] \u542f\u52a8 interval=${delay}ms")
        pollScheduler.scheduleWithFixedDelay(
            {
                runCatching { runBackgroundPoll() }
                    .onFailure { err ->
                        logHookDebug("[\u8f6e\u8be2] \u5931\u8d25 ${err.message ?: err.javaClass.simpleName}")
                    }
            },
            10,
            delay,
            TimeUnit.MILLISECONDS
        )
    }

    private fun runBackgroundPoll() {
        loadFreezeRulesFromRuntimeFile()?.let { runtime ->
            if (runtime.isNotEmpty()) {
                freezeRules = runtime
            }
        }
        val now = System.currentTimeMillis()
        val prev = lastPollAtMs
        lastPollAtMs = now
        val delta = if (prev <= 0L) 0L else now - prev
        val prevText = if (prev <= 0L) "-" else pollTimeFormatter.format(Date(prev))

        val rules = freezeRules
        if (rules.isEmpty()) {
            logHookDebug("[\u8f6e\u8be2] time=$prevText + ${delta}ms; \u672c\u6b21\u51bb\u7ed3: \u65e0(\u89c4\u5219\u4e3a\u7a7a)")
            return
        }

        val lines = ArrayList<String>()
        rules.forEach { (packageName, rule) ->
            val decision = evaluateFreezeDecision(packageName, rule)
            if (decision.shouldFreeze) {
                lines.add("- $packageName -> \u539f\u56e0:${decision.reason}")
            }
        }

        val detail = if (lines.isEmpty()) {
            "\u65e0"
        } else {
            "\n    " + lines.joinToString("\n    ")
        }
        logHookDebug("[\u8f6e\u8be2] time=$prevText + ${delta}ms; \u672c\u6b21\u51bb\u7ed3:$detail")
        runCatching {
            RuntimeLogStore.appendDiagnostic(
                source = "hook",
                message = "poll time=$prevText + ${delta}ms rules=${rules.size} matched=${lines.size}",
                throttleKey = "hook_poll_tick",
                throttleMs = 0L,
                category = RuntimeLogStore.LogCategory.LOG
            )
        }
    }

    private fun evaluateFreezeDecision(packageName: String, rule: FreezeRule): FreezeDecision {
        if (rule.whitelist) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "\u767d\u540d\u5355"
            )
        }
        val normalizedRules = rule.dontFreezeWhen.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val reasons = ArrayList<String>()

        val foreground = isPackageForegroundTracked(packageName)
        if (!foreground) {
            reasons.add("\u5207\u51fa")
        }

        if ("VISIBLE" in normalizedRules && isPackageVisible(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "\u4e0d\u5728\u89c4\u5219:VISIBLE\u5185"
            )
        }
        if ("AUDIO" in normalizedRules && isPackageAudioActive(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "\u4e0d\u5728\u89c4\u5219:AUDIO\u5185"
            )
        }
        if ("NETWORK" in normalizedRules && isPackageNetworkActive(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "\u4e0d\u5728\u89c4\u5219:NETWORK\u5185"
            )
        }

        if (normalizedRules.isNotEmpty()) {
            reasons.add("\u4e0d\u5728\u89c4\u5219:{${normalizedRules.joinToString(",")}}\u5185")
        }
        if (reasons.isEmpty()) {
            reasons.add("\u8f6e\u8be2\u547d\u4e2d")
        }
        return FreezeDecision(
            shouldFreeze = true,
            reason = reasons.joinToString("/")
        )
    }

    private fun isPackageForegroundTracked(packageName: String): Boolean {
        synchronized(packageStateLock) {
            val set = packageForegroundPids[packageName] ?: return false
            return set.isNotEmpty()
        }
    }

    private fun isPackageVisible(packageName: String): Boolean {
        val state = readUidProcState(packageName)
        return state in PROCESS_STATE_TOP_THRESHOLD..PROCESS_STATE_VISIBLE_THRESHOLD
    }

    private fun isPackageAudioActive(packageName: String): Boolean {
        val uid = resolvePackageUid(packageName)
        val uidHit = if (uid != null) {
            runShellExitCode(
                "dumpsys audio 2>/dev/null | grep -E \"uid[:= ]$uid\\\\b\" | grep -Eiq \"started|active|playback\""
            ) == 0
        } else {
            false
        }
        if (uidHit) {
            return true
        }
        return runShellExitCode(
            "dumpsys audio 2>/dev/null | grep -F \"$packageName\" | grep -Eiq \"started|active|playback\""
        ) == 0
    }

    private fun isPackageNetworkActive(packageName: String): Boolean {
        val uid = resolvePackageUid(packageName) ?: return false
        return hasUidTrafficDelta(uid)
    }

    private fun readUidProcState(packageName: String): Int? {
        val uid = resolvePackageUid(packageName) ?: return null
        val stateText = runShellFirstLine("cmd activity get-uid-state $uid 2>/dev/null") ?: return null
        val direct = stateText.trim().toIntOrNull()
        if (direct != null) return direct
        val parsed = Regex("""-?\d+""").find(stateText)?.value?.toIntOrNull()
        if (parsed != null) return parsed
        return when {
            stateText.contains("TOP", ignoreCase = true) -> PROCESS_STATE_TOP_THRESHOLD
            stateText.contains("FOREGROUND", ignoreCase = true) -> PROCESS_STATE_FOREGROUND_THRESHOLD
            stateText.contains("VISIBLE", ignoreCase = true) -> PROCESS_STATE_VISIBLE_THRESHOLD
            else -> null
        }
    }

    private fun resolvePackageUid(packageName: String): Int? {
        val command = """cmd package list packages -U 2>/dev/null | grep -F "package:$packageName " | sed -n 's/.* uid:\([0-9][0-9]*\).*/\1/p' | head -n 1"""
        return runShellFirstLine(command)?.toIntOrNull()
    }

    private fun hasUidTrafficDelta(uid: Int): Boolean {
        val current = readUidTrafficBytes(uid) ?: return false
        val previous = uidTrafficCache.put(uid, current)
        if (previous == null) {
            return false
        }
        return current.rxBytes > previous.rxBytes || current.txBytes > previous.txBytes
    }

    private fun readUidTrafficBytes(uid: Int): TrafficBytes? {
        val command = """
            if [ -r /proc/net/xt_qtaguid/stats ]; then
              awk -v uid="$uid" 'NR>1 && $4==uid {rx+=$6; tx+=$8} END {print (rx+0) "|" (tx+0)}' /proc/net/xt_qtaguid/stats
            elif [ -r /proc/net/xt_qtaguid/stats2 ]; then
              awk -v uid="$uid" 'NR>1 && $4==uid {rx+=$6; tx+=$8} END {print (rx+0) "|" (tx+0)}' /proc/net/xt_qtaguid/stats2
            fi
        """.trimIndent()
        val line = runShellFirstLine(command) ?: return null
        val parts = line.split('|')
        if (parts.size != 2) return null
        val rx = parts[0].toLongOrNull() ?: return null
        val tx = parts[1].toLongOrNull() ?: return null
        return TrafficBytes(rxBytes = rx, txBytes = tx)
    }

    private fun runShellFirstLine(command: String): String? {
        val lines = runShell(command)
        return lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun runShellExitCode(command: String): Int {
        val proc = runCatching {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return -1
        return runCatching {
            proc.inputStream.bufferedReader().use { reader ->
                while (reader.readLine() != null) {
                    // consume output
                }
            }
            proc.waitFor()
        }.getOrElse { -1 }
    }

    private fun runShell(command: String): List<String> {
        val proc = runCatching {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        }.getOrNull() ?: return emptyList()
        return runCatching {
            val lines = ArrayList<String>()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.add(line)
                }
            }
            proc.waitFor()
            lines
        }.getOrDefault(emptyList())
    }

    private fun resolveHookPollIntervalMs(): Long {
        val settingsFile = File(FreezeListStore.runtimeSettingsPath())
        if (!settingsFile.exists() || !settingsFile.canRead()) {
            return DEFAULT_BACKGROUND_POLL_INTERVAL_MS
        }
        return runCatching {
            val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = builder.parse(settingsFile)
            val nodes = doc.getElementsByTagName("int")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                if (name == "hook_poll_interval_seconds") {
                    val value = node.attributes?.getNamedItem("value")?.nodeValue?.toIntOrNull() ?: 30
                    return@runCatching value.coerceIn(5, 300) * 1000L
                }
            }
            DEFAULT_BACKGROUND_POLL_INTERVAL_MS
        }.getOrDefault(DEFAULT_BACKGROUND_POLL_INTERVAL_MS)
    }

    private fun extractPackageNameFromProcessName(processName: String): String? {
        val baseName = processName.substringBefore(':')
        return if (isLikelyPackageName(baseName)) baseName else null
    }

    private fun isLikelyPackageName(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.contains(" ")) return false
        if (!value.contains('.')) return false
        return value.matches(Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$"))
    }

    private fun findProcessRecordFromObject(target: Any?): Any? {
        if (target == null) {
            return null
        }
        val className = target.javaClass.name
        if (className == "com.android.server.am.ProcessRecord" || className.endsWith(".ProcessRecord")) {
            return target
        }
        return readObject(
            target,
            listOf(
                "mApp",
                "app",
                "mProcessRecord",
                "processRecord",
                "mProc"
            )
        )
    }

    private fun findProcessRecordArg(args: Array<Any?>): Any? {
        args.forEach { arg ->
            if (arg != null) {
                val name = arg.javaClass.name
                if (name == "com.android.server.am.ProcessRecord" || name.endsWith(".ProcessRecord")) {
                    return arg
                }
            }
        }
        return null
    }

    private fun readProcessSnapshot(processRecord: Any): ProcessSnapshot? {
        val pid = readInt(processRecord, listOf("getPid"), listOf("pid", "mPid"))
        val uid = readInt(processRecord, listOf("getUid"), listOf("uid", "mUid"))
        val processName = readString(
            processRecord,
            listOf("getProcessName"),
            listOf("processName", "mProcessName")
        )
        val procState = readProcState(processRecord)

        if (pid == null || uid == null || processName == null || procState == null) {
            return null
        }

        return ProcessSnapshot(
            pid = pid,
            uid = uid,
            processName = processName,
            procState = procState
        )
    }

    private fun readProcState(processRecord: Any): Int? {
        readInt(processRecord, listOf("getCurProcState"), listOf("mCurProcState"))?.let {
            return it
        }

        val stateObj = readObject(processRecord, listOf("mState", "procStateRecord")) ?: return null
        return readInt(
            stateObj,
            listOf("getCurProcState", "getCurRawProcState"),
            listOf("mCurProcState", "curProcState")
        )
    }

    private fun findField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            val klass = current
            try {
                return klass.getDeclaredField(fieldName).apply { isAccessible = true }
            } catch (_: Throwable) {
                current = klass.superclass
            }
        }
        return null
    }

    private fun findNoArgMethod(clazz: Class<*>, methodName: String): java.lang.reflect.Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            val klass = current
            try {
                return klass.getDeclaredMethod(methodName).apply { isAccessible = true }
            } catch (_: Throwable) {
                current = klass.superclass
            }
        }
        return null
    }

    private fun readObject(target: Any, fields: List<String>): Any? {
        fields.forEach { field ->
            runCatching {
                findField(target.javaClass, field)?.get(target)
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun readInt(target: Any, methods: List<String>, fields: List<String>): Int? {
        methods.forEach { method ->
            runCatching {
                (findNoArgMethod(target.javaClass, method)?.invoke(target) as? Number)?.toInt()
            }.getOrNull()?.let { return it }
        }

        fields.forEach { field ->
            runCatching {
                (findField(target.javaClass, field)?.get(target) as? Number)?.toInt()
            }.getOrNull()?.let { return it }
        }

        return null
    }

    private fun readString(target: Any, methods: List<String>, fields: List<String>): String? {
        methods.forEach { method ->
            runCatching {
                findNoArgMethod(target.javaClass, method)?.invoke(target)?.toString()
            }.getOrNull()?.let { return it }
        }

        fields.forEach { field ->
            runCatching {
                findField(target.javaClass, field)?.get(target)?.toString()
            }.getOrNull()?.let { return it }
        }

        return null
    }

    data class FreezeRule(
        val freezeProcesses: List<String>,
        val dontFreezeWhen: List<String>,
        val whitelist: Boolean
    )

    data class ProcessSnapshot(
        val pid: Int,
        val uid: Int,
        val processName: String,
        val procState: Int
    )

    data class FreezeDecision(
        val shouldFreeze: Boolean,
        val reason: String
    )

    data class TrafficBytes(
        val rxBytes: Long,
        val txBytes: Long
    )
}
