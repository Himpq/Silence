package cn.himpqblog.slience.hook

import android.os.IBinder
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.os.Process
import android.util.Log
import android.util.Base64
import cn.himpqblog.slience.config.FreezeListStore
import cn.himpqblog.slience.ipc.FreezeCommandReceiver
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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Suppress("unused")
class HookLegacy : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "SilenceHook"
        private const val TARGET_PACKAGE = "android"
        private const val SELF_PACKAGE = "cn.himpqblog.slience"
        private const val CGROUP_FREEZE_BASE = "/sys/fs/cgroup/apps"
        private val TARGET_PROCESSES = setOf("android", "system", "system_server")
        private const val BACKGROUND_STATE_THRESHOLD = 10
        private const val FREEZE_LIST_ASSET = "FreezeList.json"

        @Volatile
        private var moduleApkPath: String? = null
        @Volatile
        private var appContext: Context? = null

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
        private val packageLastFreezeDispatchAt = ConcurrentHashMap<String, Long>()
        private val packageLastCommittedBackground = ConcurrentHashMap<String, Boolean>()
        private val packageForegroundPids = ConcurrentHashMap<String, MutableSet<Int>>()
        private val packageLaunchProtectUntil = ConcurrentHashMap<String, Long>()
        private val packageUidCache = ConcurrentHashMap<String, Int>()
        private val failureLogAt = ConcurrentHashMap<String, Long>()
        private val packageStateScheduler = Executors.newSingleThreadScheduledExecutor()
        private val pollScheduler = Executors.newSingleThreadScheduledExecutor()
        private val triggerScheduler = Executors.newSingleThreadScheduledExecutor()
        private val packageStateLock = Any()
        private val pollStarted = AtomicBoolean(false)
        private val pollLoopRunning = AtomicBoolean(false)
        private val triggerLoopStarted = AtomicBoolean(false)
        @Volatile
        private var pollFuture: ScheduledFuture<*>? = null
        private val pollTimeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val uidTrafficCache = ConcurrentHashMap<Int, TrafficBytes>()
        private const val STABLE_STATE_WINDOW_MS = 1200L
        private const val PACKAGE_SIGNAL_DEBOUNCE_MS = 1500L
        private const val MIN_COMMIT_SWITCH_INTERVAL_MS = 2500L
        private const val DEFAULT_BACKGROUND_POLL_INTERVAL_MS = 30_000L
        private const val FAILURE_LOG_THROTTLE_MS = 60_000L
        private const val SKIP_LOG_THROTTLE_MS = 30_000L
        private const val FREEZE_REASSERT_INTERVAL_MS = 15_000L
        private const val LAUNCH_PROTECT_MS = 15_000L
        private const val FOREGROUND_RETURN_PROTECT_MS = 5_000L
        private const val ENABLE_BRIDGE_HOT_LOGS = false
        private const val PROCESS_STATE_TOP_THRESHOLD = 2
        private const val PROCESS_STATE_FOREGROUND_THRESHOLD = 6
        private const val PROCESS_STATE_VISIBLE_THRESHOLD = 8

        @Volatile
        private var lastPollAtMs: Long = 0L
        @Volatile
        private var lastTriggerValue: String? = null
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
        ensureSystemContext(lpparam.classLoader)

        logHookDebug("handleLoadPackage package=${lpparam.packageName} process=${lpparam.processName}")
        ensureFreezeRulesLoaded()
        startBackgroundPollIfNeeded()
        startForcePollTriggerLoopIfNeeded()
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
        loadFreezeRulesFromGlobalSettings()?.let { return it }

        val primary = File(FreezeListStore.runtimeConfigPath())
        val mirror = File(FreezeListStore.runtimeMirrorConfigPath())

        val primaryRules = parseFreezeRulesFromFile(primary)
        val mirrorRules = parseFreezeRulesFromFile(mirror)
        if (primaryRules == null && mirrorRules == null) {
            val primaryState = "primary exists=${primary.exists()} canRead=${primary.canRead()} path=${primary.absolutePath}"
            val mirrorState = "mirror exists=${mirror.exists()} canRead=${mirror.canRead()} path=${mirror.absolutePath}"
            logHookDebug("freeze rules runtime unavailable $primaryState | $mirrorState")
            return null
        }

        val merged = LinkedHashMap<String, FreezeRule>()
        if (!primaryRules.isNullOrEmpty()) {
            merged.putAll(primaryRules)
        }
        if (!mirrorRules.isNullOrEmpty()) {
            merged.putAll(mirrorRules)
        }

        val mergedRules = if (merged.isEmpty()) emptyMap() else merged
        val keys = mergedRules.keys.take(12).joinToString(",")
        logHookDebug(
            "freeze rules runtime merged apps=${mergedRules.size} " +
                "primary=${primaryRules?.size ?: -1}@${primary.absolutePath} " +
                "mirror=${mirrorRules?.size ?: -1}@${mirror.absolutePath} " +
                "keys=$keys"
        )
        return mergedRules
    }

    private fun loadFreezeRulesFromGlobalSettings(): Map<String, FreezeRule>? {
        val encoded = readGlobalSetting(FreezeListStore.runtimeGlobalRulesKey()).trim()
        if (encoded.isEmpty() || encoded == "null") {
            logHookDebug("freeze rules global unavailable key=${FreezeListStore.runtimeGlobalRulesKey()} value=empty")
            return null
        }
        return runCatching {
            val raw = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
            val rules = parseFreezeRules(raw)
            logHookDebug(
                "freeze rules global merged apps=${rules.size} key=${FreezeListStore.runtimeGlobalRulesKey()} chars=${raw.length} keys=${rules.keys.take(12).joinToString(",")}"
            )
            rules
        }.onFailure { err ->
            XposedBridge.log("$TAG freeze rules global parse failed: ${err.message}")
            logHookDebug("freeze rules global parse failed key=${FreezeListStore.runtimeGlobalRulesKey()} err=${err.message}")
        }.getOrNull()
    }

    private fun parseFreezeRulesFromFile(file: File): Map<String, FreezeRule>? {
        if (!file.exists() || !file.canRead()) {
            return null
        }
        return runCatching {
            parseFreezeRules(file.readText(Charsets.UTF_8))
        }.onFailure { err ->
            XposedBridge.log("$TAG freeze rules parse failed: file=${file.absolutePath}, err=${err.message}")
            logHookDebug("freeze rules parse failed source=${file.absolutePath} err=${err.message}")
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
            result[SELF_PACKAGE] = FreezeRule(
                freezeProcesses = listOf("ALL"),
                dontFreezeWhen = emptyList(),
                whitelist = true
            )
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
                            appContext = context as? Context
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

    private fun ensureSystemContext(classLoader: ClassLoader) {
        if (appContext != null) return
        runCatching {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            val current = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            val context = XposedHelpers.callMethod(current, "getSystemContext")
            appContext = context as? Context
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
        synchronized(packageStateLock) {
            if (movedToBackground) {
                packageForegroundPids.remove(packageName)
            }
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

        val previousState = lastProcStateByPid.put(snapshot.pid, snapshot.procState)
        if (previousState == null) {
            val packageName = extractPackageNameFromProcessName(snapshot.processName)
            if (packageName != null && snapshot.uid >= 10000) {
                packageUidCache[packageName] = snapshot.uid
                if (snapshot.procState < BACKGROUND_STATE_THRESHOLD) {
                    synchronized(packageStateLock) {
                        packageForegroundPids.getOrPut(packageName) { mutableSetOf() }.add(snapshot.pid)
                    }
                    packageLaunchProtectUntil[packageName] = System.currentTimeMillis() + LAUNCH_PROTECT_MS
                }
            }
            return
        }
        val movedToBackground =
            previousState < BACKGROUND_STATE_THRESHOLD && snapshot.procState >= BACKGROUND_STATE_THRESHOLD
        val movedToForeground =
            previousState >= BACKGROUND_STATE_THRESHOLD && snapshot.procState < BACKGROUND_STATE_THRESHOLD

        val packageName = extractPackageNameFromProcessName(snapshot.processName)
        if (packageName != null && snapshot.uid >= 10000) {
            packageUidCache[packageName] = snapshot.uid
        }
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
            val forceForegroundSignal =
                movedToForeground && (
                    frozenPackages.contains(packageName) ||
                        packageLastCommittedBackground[packageName] == true ||
                        packageDesiredBackground[packageName] == true
                )
            if (forceForegroundSignal) {
                packageLastDesiredState[packageName] = false
                packageLastSignalAt[packageName] = now
                shouldSignal = true
                return@synchronized
            }
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

        if (movedToBackground) {
            synchronized(packageStateLock) {
                packageForegroundPids.remove(normalized)
            }
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
                val dispatched = commitBackgroundState(normalized, signalSource)
                if (!dispatched) {
                    // keep scheduling path warm; poll loop will periodically reassert
                }
            } else {
                commitForegroundState(normalized, signalSource)
            }
        }, STABLE_STATE_WINDOW_MS, TimeUnit.MILLISECONDS)
    }

    private fun commitBackgroundState(packageName: String, source: String): Boolean {
        if (packageDesiredBackground[packageName] == false) {
            return false
        }
        val rule = freezeRules[packageName] ?: return false
        val decision = evaluateFreezeDecision(packageName, rule)
        if (!decision.shouldFreeze) {
            logCommitAbort(packageName, source, decision.reason)
            return false
        }
        val now = System.currentTimeMillis()
        val wasFrozen = frozenPackages.contains(packageName)
        val lastDispatch = packageLastFreezeDispatchAt[packageName] ?: 0L
        val allowReassert = now - lastDispatch >= FREEZE_REASSERT_INTERVAL_MS
        if (wasFrozen && !allowReassert) {
            return false
        }
        val lastCommit = packageLastCommitAt[packageName] ?: 0L
        val lastBackground = packageLastCommittedBackground[packageName]
        if (lastBackground == true && now - lastCommit < MIN_COMMIT_SWITCH_INTERVAL_MS) {
            return false
        }
        if (rule.whitelist || packageName == SELF_PACKAGE) {
            return false
        }
        dispatchFreezeCommandIpc(packageName, freeze = true, rule = rule, source = source)
        packageLastFreezeDispatchAt[packageName] = now
        val write = writeFreezeStateForPackage(packageName, rule, targetFrozen = true)
        if (write.writes <= 0) {
            logFreezeFailure(packageName, source, "冻结探针失败(IPC已发)", write.reason)
            return false
        }
        frozenPackages.add(packageName)
        packageLastCommitAt[packageName] = now
        packageLastCommittedBackground[packageName] = true

        val processText = if (rule.freezeProcesses.isEmpty()) {
            "*"
        } else {
            rule.freezeProcesses.joinToString(", ")
        }

        if (!wasFrozen) {
            logHookDebug("进入后台 $packageName")
            logHookDebug("冻结 $packageName:{$processText}")
        } else {
            logHookDebug("hold freeze $packageName source=$source")
        }
        return true
    }

    private fun commitForegroundState(packageName: String, source: String) {
        packageLaunchProtectUntil[packageName] = System.currentTimeMillis() + FOREGROUND_RETURN_PROTECT_MS
        packageDesiredBackground[packageName] = false
        val rule = freezeRules[packageName]
        if (rule == null && !frozenPackages.contains(packageName)) {
            return
        }
        dispatchFreezeCommandIpc(packageName, freeze = false, rule = rule, source = source)
        val write = writeFreezeStateForPackage(packageName, rule, targetFrozen = false)
        if (write.writes <= 0) {
            logFreezeFailure(packageName, source, "解冻探针失败(IPC已发)", write.reason)
        }
        frozenPackages.remove(packageName)
        val now = System.currentTimeMillis()
        packageLastCommitAt[packageName] = now
        packageLastCommittedBackground[packageName] = false
        val processText = freezeRules[packageName]?.freezeProcesses?.joinToString(", ").orEmpty()
        if (processText.isNotBlank()) {
            logHookDebug("解冻 $packageName:{$processText}")
        } else {
            logHookDebug("解冻 $packageName")
        }
    }

    private fun startBackgroundPollIfNeeded() {
        if (!pollStarted.compareAndSet(false, true)) {
            return
        }
        val initialDelay = resolveHookPollIntervalMs()
        logHookDebug("[轮询] 启动 interval=${initialDelay}ms")
        scheduleNextPoll(10L)
    }

    private fun scheduleNextPoll(delayMs: Long) {
        val safeDelay = delayMs.coerceAtLeast(5_000L)
        pollFuture = pollScheduler.schedule({
            if (!pollLoopRunning.compareAndSet(false, true)) {
                scheduleNextPoll(resolveHookPollIntervalMs())
                return@schedule
            }
            try {
                runCatching { runBackgroundPoll() }
                    .onFailure { err ->
                        logHookDebug("[轮询] 失败 ${err.message ?: err.javaClass.simpleName}")
                    }
            } finally {
                pollLoopRunning.set(false)
            }
            val nextDelay = resolveHookPollIntervalMs()
            scheduleNextPoll(nextDelay)
        }, safeDelay, TimeUnit.MILLISECONDS)
    }

    private fun startForcePollTriggerLoopIfNeeded() {
        if (!triggerLoopStarted.compareAndSet(false, true)) {
            return
        }
        triggerScheduler.scheduleWithFixedDelay(
            {
                runCatching { checkForcePollTrigger() }
                    .onFailure { err ->
                        logHookDebug("force trigger loop failed: ${err.message ?: err.javaClass.simpleName}")
                    }
            },
            1000L,
            1000L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun checkForcePollTrigger() {
        val propKey = FreezeListStore.runtimeForcePollPropertyKey()
        val propToken = readSystemProperty(propKey)
        if (propToken.isNotEmpty() && propToken != lastTriggerValue) {
            lastTriggerValue = propToken
            logHookDebug("[poll] force trigger prop token=$propToken")
            runBackgroundPoll()
        }
    }

    private fun runBackgroundPoll() {
        loadFreezeRulesFromRuntimeFile()?.let { runtime ->
            freezeRules = runtime
        }
        val activeKeys = freezeRules.keys.take(12).joinToString(",")
        logHookDebug("freeze rules active apps=${freezeRules.size} keys=$activeKeys")
        val now = System.currentTimeMillis()
        val prev = lastPollAtMs
        lastPollAtMs = now
        val delta = if (prev <= 0L) 0L else now - prev
        val prevText = if (prev <= 0L) "-" else pollTimeFormatter.format(Date(prev))

        val rules = freezeRules
        if (rules.isEmpty()) {
            logHookDebug("[轮询] time=$prevText + ${delta}ms; 本次冻结: 无(规则为空)")
            return
        }

        val lines = ArrayList<String>()
        val considered = ArrayList<String>()
        rules.forEach { (packageName, rule) ->
            considered.add(packageName)
            val decision = evaluateFreezeDecision(packageName, rule)
            if (decision.shouldFreeze) {
                val dispatched = commitBackgroundState(packageName, "poll:${decision.reason}")
                if (dispatched) {
                    lines.add("- $packageName -> 原因:${decision.reason}")
                }
            } else if (frozenPackages.contains(packageName)) {
                commitForegroundState(packageName, "poll:${decision.reason}")
            } else {
                logPollSkip(packageName, decision.reason)
            }
        }

        val detail = if (lines.isEmpty()) {
            "无"
        } else {
            "\n    " + lines.joinToString("\n    ")
        }
        val consideredHead = considered.take(12).joinToString(",")
        logHookDebug("poll considered apps=${considered.size} keys=$consideredHead")
        logHookDebug("[轮询] time=$prevText + ${delta}ms; 本次冻结:$detail")
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

    private fun readSystemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as? String ?: ""
        }.getOrDefault("")
    }

    private fun readGlobalSetting(key: String): String {
        val context = appContext
        if (context != null) {
            runCatching {
                return android.provider.Settings.Global.getString(context.contentResolver, key) ?: ""
            }
        }
        return runCatching {
            val clazz = Class.forName("android.provider.Settings\$Global")
            val method = clazz.getMethod("getString", android.content.ContentResolver::class.java, String::class.java)
            val contentResolver = Context::class.java.getMethod("getContentResolver").invoke(appContext)
            method.invoke(null, contentResolver, key) as? String ?: ""
        }.getOrDefault("")
    }

    private fun evaluateFreezeDecision(packageName: String, rule: FreezeRule): FreezeDecision {
        if (rule.whitelist) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "白名单"
            )
        }
        val normalizedRules = rule.dontFreezeWhen.map { it.trim().uppercase(Locale.ROOT) }.toSet()
        val reasons = ArrayList<String>()

        val launchProtectUntil = packageLaunchProtectUntil[packageName] ?: 0L
        if (launchProtectUntil > System.currentTimeMillis()) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "启动保护"
            )
        }

        val foreground = isPackageTopApp(packageName)
        if (foreground) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "前台"
            )
        }
        reasons.add("切出")

        if ("VISIBLE" in normalizedRules && isPackageVisible(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "不在规则:VISIBLE内"
            )
        }
        if ("AUDIO" in normalizedRules && isPackageAudioActive(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "不在规则:AUDIO内"
            )
        }
        if ("NETWORK" in normalizedRules && isPackageNetworkActive(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "不在规则:NETWORK内"
            )
        }

        if (normalizedRules.isNotEmpty()) {
            reasons.add("不在规则:{${normalizedRules.joinToString(",")}}内")
        }
        if (reasons.isEmpty()) {
            reasons.add("轮询命中")
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

    private fun isPackageTopApp(packageName: String): Boolean {
        val state = readUidProcState(packageName)
        if (state != null) {
            if (state <= PROCESS_STATE_TOP_THRESHOLD) {
                return true
            }
            synchronized(packageStateLock) {
                packageForegroundPids.remove(packageName)
            }
            return false
        }
        return false
    }

    private fun isPackageVisible(packageName: String): Boolean {
        val state = readUidProcState(packageName)
        return state != null && state <= PROCESS_STATE_TOP_THRESHOLD
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
        val packageHit = runShellExitCode(
            "dumpsys audio 2>/dev/null | grep -F \"$packageName\" | grep -Eiq \"started|active|playback\""
        ) == 0
        if (packageHit) {
            return true
        }
        return runShellExitCode(
            "dumpsys media_session 2>/dev/null | grep -F \"$packageName\" -A 6 | grep -Eiq \"state=3|PlaybackState.*state=3\""
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
            stateText.contains("FOREGROUND_SERVICE", ignoreCase = true) -> PROCESS_STATE_FOREGROUND_THRESHOLD
            stateText.contains("VISIBLE", ignoreCase = true) -> PROCESS_STATE_VISIBLE_THRESHOLD
            else -> null
        }
    }

    private fun logPollSkip(packageName: String, reason: String) {
        val key = "poll_skip|$packageName|$reason"
        val now = System.currentTimeMillis()
        val last = failureLogAt[key] ?: 0L
        if (now - last < SKIP_LOG_THROTTLE_MS) {
            return
        }
        failureLogAt[key] = now
        logHookDebug("skip $packageName reason=$reason")
    }

    private fun logCommitAbort(packageName: String, source: String, reason: String) {
        val key = "commit_abort|$packageName|$source|$reason"
        val now = System.currentTimeMillis()
        val last = failureLogAt[key] ?: 0L
        if (now - last < SKIP_LOG_THROTTLE_MS) {
            return
        }
        failureLogAt[key] = now
        logHookDebug("取消冻结 $packageName source=$source reason=$reason")
    }

    private fun resolvePackageUid(packageName: String): Int? {
        packageUidCache[packageName]?.let { cached ->
            if (cached >= 10000) return cached
        }
        val escaped = packageName.replace("\"", "\\\"")
        val fromPackagesList = runShellFirstLine(
            """awk '$1=="$escaped"{print $2; exit}' /data/system/packages.list 2>/dev/null"""
        )?.toIntOrNull()
        if (fromPackagesList != null && fromPackagesList >= 10000) {
            packageUidCache[packageName] = fromPackagesList
            return fromPackagesList
        }
        val command = """cmd package list packages -U 2>/dev/null | grep -F "package:$packageName " | sed -n 's/.* uid:\([0-9][0-9]*\).*/\1/p' | head -n 1"""
        val fromCmd = runShellFirstLine(command)?.toIntOrNull()
        if (fromCmd != null && fromCmd >= 10000) {
            packageUidCache[packageName] = fromCmd
            return fromCmd
        }
        val fromDumpSys = runShellFirstLine(
            """dumpsys package "$escaped" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1"""
        )?.toIntOrNull()
        if (fromDumpSys != null && fromDumpSys >= 10000) {
            packageUidCache[packageName] = fromDumpSys
            return fromDumpSys
        }
        return null
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

    private fun writeFreezeStateForPackage(
        packageName: String,
        rule: FreezeRule?,
        targetFrozen: Boolean
    ): FreezeWriteResult {
        val uid = resolvePackageUid(packageName) ?: return FreezeWriteResult(
            writes = 0,
            selectedProcessNames = emptyList(),
            reason = buildUidResolveDebug(packageName)
        )
        val freezeTargets = rule?.freezeProcesses?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val forceAll = freezeTargets.isEmpty() || freezeTargets.any { it.equals("ALL", ignoreCase = true) }
        val targetNames = freezeTargets
            .asSequence()
            .filterNot { it.equals("ALL", ignoreCase = true) }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val childRows = listUidChildProcesses(uid)
        if (childRows.isEmpty()) {
            return FreezeWriteResult(
                writes = 0,
                selectedProcessNames = emptyList(),
                reason = buildNoChildProcessDebug(uid)
            )
        }
        val selected = if (forceAll) {
            childRows
        } else {
            childRows.filter {
                val fullName = it.processName.lowercase(Locale.ROOT)
                val displayName = it.processName.substringAfter(':', "main").lowercase(Locale.ROOT)
                targetNames.contains(displayName) || targetNames.contains(fullName)
            }
        }
        if (selected.isEmpty()) {
            return FreezeWriteResult(
                writes = 0,
                selectedProcessNames = emptyList(),
                reason = "no_target_match uid=$uid targets=${targetNames.joinToString(",")}"
            )
        }

        val state = if (targetFrozen) "1" else "0"
        var writes = 0
        val selectedNames = selected.map { it.processName.substringAfter(':', "main") }
        selected.forEach { row ->
            val pidPath = "$CGROUP_FREEZE_BASE/uid_${uid}/pid_${row.pid}/cgroup.freeze"
            if (writeCgroupValue(pidPath, state)) {
                writes++
            }
        }

        val uidPath = "$CGROUP_FREEZE_BASE/uid_${uid}/cgroup.freeze"
        if (!targetFrozen && writeCgroupValue(uidPath, "0")) {
            writes++
        }
        return FreezeWriteResult(
            writes = writes,
            selectedProcessNames = selectedNames,
            reason = if (writes > 0) "ok" else "write_failed uid=$uid selected=${selectedNames.joinToString(",")}"
        )
    }

    private fun listUidChildProcesses(uid: Int): List<UidProcessRow> {
        parseUidProcessesFromPs(
            uid = uid,
            lines = runShell("ps -A -o UID,PID,NAME 2>/dev/null")
        ).takeIf { it.isNotEmpty() }?.let { return it }

        parseUidProcessesFromPs(
            uid = uid,
            lines = runShell("ps -A -o UID,PID,ARGS 2>/dev/null")
        ).takeIf { it.isNotEmpty() }?.let { return it }

        val output = runShell(
            "for d in /proc/[0-9]*; do pid=${'$'}{d##*/}; s=\"${'$'}d/status\"; [ -r \"${'$'}s\" ] || continue; u=${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' \"${'$'}s\"); [ \"${'$'}u\" = \"$uid\" ] || continue; p=\"\"; if [ -r \"${'$'}d/cmdline\" ]; then p=${'$'}(tr '\\000' ' ' < \"${'$'}d/cmdline\"); fi; if [ -z \"${'$'}p\" ] && [ -r \"${'$'}d/comm\" ]; then p=${'$'}(cat \"${'$'}d/comm\"); fi; p=${'$'}{p%% *}; [ -z \"${'$'}p\" ] && continue; echo \"${'$'}pid|${'$'}p\"; done"
        )
        return output.mapNotNull { line ->
            val parts = line.trim().split('|')
            if (parts.size != 2) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val proc = parts[1].trim()
            if (proc.isEmpty()) return@mapNotNull null
            UidProcessRow(pid = pid, processName = proc)
        }
    }

    private fun parseUidProcessesFromPs(uid: Int, lines: List<String>): List<UidProcessRow> {
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("UID")) {
                return@mapNotNull null
            }
            val parts = trimmed.split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) {
                return@mapNotNull null
            }
            val rowUid = parseUidToken(parts[0]) ?: return@mapNotNull null
            if (rowUid != uid) {
                return@mapNotNull null
            }
            val pid = parts[1].toIntOrNull() ?: return@mapNotNull null
            val proc = parts[2].substringBefore(' ').trim()
            if (proc.isEmpty()) {
                return@mapNotNull null
            }
            UidProcessRow(pid = pid, processName = proc)
        }
    }

    private fun parseUidToken(token: String): Int? {
        val raw = token.trim()
        raw.toIntOrNull()?.let { return it }
        val userApp = Regex("""^u(\d+)_a(\d+)$""").matchEntire(raw)
        if (userApp != null) {
            val userId = userApp.groupValues[1].toIntOrNull() ?: return null
            val appOffset = userApp.groupValues[2].toIntOrNull() ?: return null
            return userId * 100000 + 10000 + appOffset
        }
        val userIsolated = Regex("""^u(\d+)_i(\d+)$""").matchEntire(raw)
        if (userIsolated != null) {
            val userId = userIsolated.groupValues[1].toIntOrNull() ?: return null
            val isolatedOffset = userIsolated.groupValues[2].toIntOrNull() ?: return null
            return userId * 100000 + 99000 + isolatedOffset
        }
        return null
    }

    private fun writeCgroupValue(path: String, value: String): Boolean {
        val escapedPath = path.replace("\"", "\\\"")
        val command = "echo $value > \"$escapedPath\""
        if (runShellExitCode("[ -w \"$escapedPath\" ] && $command") == 0) {
            return true
        }
        if (runShellExitCode("su -c '$command'") == 0) {
            return true
        }
        return runShellExitCode("/system/bin/magisk su -c '$command'") == 0
    }

    private fun dispatchFreezeCommandIpc(
        packageName: String,
        freeze: Boolean,
        rule: FreezeRule?,
        source: String
    ) {
        val context = appContext
        if (context == null) {
            logHookDebug("ipc->freeze skipped package=$packageName freeze=$freeze reason=context_null")
            return
        }
        val intent = Intent(FreezeCommandReceiver.ACTION_FREEZE_COMMAND).apply {
            `package` = SELF_PACKAGE
            component = ComponentName(SELF_PACKAGE, "cn.himpqblog.slience.ipc.FreezeCommandReceiver")
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            val includeBgFlag = runCatching {
                Intent::class.java.getField("FLAG_RECEIVER_INCLUDE_BACKGROUND").getInt(null)
            }.getOrDefault(0)
            if (includeBgFlag != 0) {
                addFlags(includeBgFlag)
            }
            putExtra(FreezeCommandReceiver.EXTRA_PACKAGE_NAME, packageName)
            putExtra(FreezeCommandReceiver.EXTRA_FREEZE, freeze)
            putStringArrayListExtra(
                FreezeCommandReceiver.EXTRA_TARGETS,
                ArrayList(rule?.freezeProcesses.orEmpty())
            )
            putExtra(FreezeCommandReceiver.EXTRA_SOURCE, source)
        }
        runCatching {
            val sendAsUser = runCatching {
                val method = Context::class.java.getMethod(
                    "sendBroadcastAsUser",
                    Intent::class.java,
                    Class.forName("android.os.UserHandle")
                )
                method.invoke(context, intent, Process.myUserHandle())
                true
            }.getOrElse {
                context.sendBroadcast(intent)
                false
            }
            logHookDebug(
                "ipc->freeze package=$packageName freeze=$freeze source=$source send=${if (sendAsUser) "as_user" else "normal"}"
            )
        }.onFailure { err ->
            logHookDebug("ipc->freeze failed package=$packageName freeze=$freeze err=${err.message ?: err.javaClass.simpleName}")
        }
    }

    private fun logFreezeFailure(packageName: String, source: String, action: String, reason: String) {
        val key = "$action|$packageName|$source"
        val now = System.currentTimeMillis()
        val last = failureLogAt[key] ?: 0L
        if (now - last < FAILURE_LOG_THROTTLE_MS) {
            return
        }
        failureLogAt[key] = now
        logHookDebug("$action $packageName source=$source reason=$reason")
    }

    private fun buildUidResolveDebug(packageName: String): String {
        val escaped = packageName.replace("\"", "\\\"")
        val cacheValue = packageUidCache[packageName]
        val packagesListHit = runShellFirstLine(
            """awk '$1=="$escaped"{print $1 "|" $2; exit}' /data/system/packages.list 2>/dev/null"""
        )
        val cmdListHit = runShellFirstLine(
            """cmd package list packages -U 2>/dev/null | grep -F "package:$packageName " | head -n 1"""
        )
        val cmdListContains = runShellExitCode(
            """cmd package list packages 2>/dev/null | grep -Fxq "package:$packageName""""
        ) == 0
        val cmdListSample = runShell(
            """cmd package list packages 2>/dev/null | grep -F "package:$packageName" | head -n 2"""
        ).joinToString(";")
            .ifEmpty { "empty" }
        val dumpUserId = runShellFirstLine(
            """dumpsys package "$escaped" 2>/dev/null | sed -n 's/.*userId=\([0-9][0-9]*\).*/\1/p' | head -n 1"""
        )
        return "uid_unresolved pkg=$packageName cache=${cacheValue ?: "null"} cmd_contains=$cmdListContains pkglist=${packagesListHit ?: "empty"} cmd_u=${cmdListHit ?: "empty"} cmd_sample=$cmdListSample dumpsys_uid=${dumpUserId ?: "empty"}"
    }

    private fun buildNoChildProcessDebug(uid: Int): String {
        val psNameLines = runShell("ps -A -o UID,PID,NAME 2>/dev/null")
        val psArgsLines = runShell("ps -A -o UID,PID,ARGS 2>/dev/null")
        val psUidNameCount = parseUidProcessesFromPs(uid, psNameLines).size
        val psUidArgsCount = parseUidProcessesFromPs(uid, psArgsLines).size
        val procRows = runShell(
            "for d in /proc/[0-9]*; do s=\"${'$'}d/status\"; [ -r \"${'$'}s\" ] || continue; u=${'$'}(awk '/^Uid:/{print ${'$'}2; exit}' \"${'$'}s\"); [ \"${'$'}u\" = \"$uid\" ] || continue; echo \"${'$'}{d##*/}\"; done"
        )
        val procCount = procRows.size
        val psNameHead = psNameLines.take(4).joinToString(";").ifEmpty { "empty" }
        val psArgsHead = psArgsLines.take(4).joinToString(";").ifEmpty { "empty" }
        val procHead = procRows.take(4).joinToString(";").ifEmpty { "empty" }
        val rootProbe = buildRootProbeSummary()
        val cgroupUidDir = "$CGROUP_FREEZE_BASE/uid_${uid}"
        val cgroupUidDirExists = runShellExitCode("[ -d \"$cgroupUidDir\" ]") == 0
        val cgroupPidDirs = runShell("ls -1d \"$cgroupUidDir\"/pid_* 2>/dev/null").size
        val cgroupRootExists = runShellExitCode("[ -d \"$CGROUP_FREEZE_BASE\" ]") == 0
        val cgroupAltUidDir = "/sys/fs/cgroup/uid_${uid}"
        val cgroupAltUidDirExists = runShellExitCode("[ -d \"$cgroupAltUidDir\" ]") == 0
        return "no_child_process uid=$uid root_probe=$rootProbe ps_name_count=$psUidNameCount ps_args_count=$psUidArgsCount proc_count=$procCount cgroup_root=$cgroupRootExists cgroup_uid_dir=$cgroupUidDirExists cgroup_alt_uid_dir=$cgroupAltUidDirExists cgroup_pid_dirs=$cgroupPidDirs ps_name_head=$psNameHead ps_args_head=$psArgsHead proc_head=$procHead"
    }

    private fun buildRootProbeSummary(): String {
        val shProbe = runShellResult("id 2>/dev/null")
        val suProbe = runShellResult("su -c 'id 2>/dev/null'")
        val magiskProbe = runShellResult("/system/bin/magisk su -c 'id 2>/dev/null'")
        return "sh=${formatProbe(shProbe)} su=${formatProbe(suProbe)} mgsu=${formatProbe(magiskProbe)}"
    }

    private fun formatProbe(result: ShellResult): String {
        val out = result.out.firstOrNull()?.take(60) ?: "empty"
        val err = result.err.firstOrNull()?.take(60) ?: "empty"
        return "code:${result.code},out:$out,err:$err"
    }

    private fun runShellResult(command: String): ShellResult {
        val proc = runCatching {
            ProcessBuilder("sh", "-c", command).start()
        }.getOrElse { err ->
            return ShellResult(
                out = emptyList(),
                err = listOf(err.message ?: err.javaClass.simpleName),
                code = -1
            )
        }
        return runCatching {
            val outLines = ArrayList<String>()
            val errLines = ArrayList<String>()
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    outLines.add(line)
                }
            }
            BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    errLines.add(line)
                }
            }
            val code = proc.waitFor()
            ShellResult(out = outLines, err = errLines, code = code)
        }.getOrElse { err ->
            ShellResult(
                out = emptyList(),
                err = listOf(err.message ?: err.javaClass.simpleName),
                code = -1
            )
        }
    }

    private fun resolveHookPollIntervalMs(): Long {
        val globalValue = readGlobalSetting(FreezeListStore.runtimeGlobalHookPollIntervalKey())
            .toIntOrNull()
            ?.coerceIn(5, 300)
        if (globalValue != null) {
            return globalValue * 1000L
        }
        val settingsPrimary = File(FreezeListStore.runtimeSettingsPath())
        val settingsMirrors = listOf(File(FreezeListStore.runtimeMirrorSettingsPath()))
        val settingsFile = sequenceOf(settingsPrimary)
            .plus(settingsMirrors.asSequence())
            .filter { it.exists() && it.canRead() }
            .maxByOrNull { it.lastModified() }
        if (settingsFile == null) {
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

    private fun pickLatestReadableFile(primary: File, mirror: File): File? {
        val pReadable = primary.exists() && primary.canRead()
        val mReadable = mirror.exists() && mirror.canRead()
        return when {
            pReadable && mReadable -> {
                if (mirror.lastModified() >= primary.lastModified()) mirror else primary
            }
            pReadable -> primary
            mReadable -> mirror
            else -> null
        }
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

    data class FreezeWriteResult(
        val writes: Int,
        val selectedProcessNames: List<String>,
        val reason: String
    )

    data class UidProcessRow(
        val pid: Int,
        val processName: String
    )

    data class TrafficBytes(
        val rxBytes: Long,
        val txBytes: Long
    )

    data class ShellResult(
        val out: List<String>,
        val err: List<String>,
        val code: Int
    )
}
