package cn.himpqblog.silence.hook

import android.os.IBinder
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.util.Base64
import cn.himpqblog.silence.config.FreezeListStore
import cn.himpqblog.silence.daemon.DaemonControlClient
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Suppress("unused")
class HookLegacy : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val TAG = "SilenceHook"
        private const val TARGET_PACKAGE = "android"
        private const val SELF_PACKAGE = "cn.himpqblog.silence"
        private const val CGROUP_FREEZE_BASE = "/sys/fs/cgroup/apps"
        private val TARGET_PROCESSES = setOf("android", "system", "system_server")
        private const val BACKGROUND_STATE_THRESHOLD = 10
        private const val FREEZE_LIST_ASSET = "FreezeList.json"

        @Volatile
        private var moduleApkPath: String? = null
        @Volatile
        private var appContext: Context? = null
        @Volatile
        private var systemClassLoader: ClassLoader? = null
        @Volatile
        private var lastAtmsService: Any? = null
        @Volatile
        private var currentTopResumedPackage: String? = null

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
        private val packageLastPrelaunchThawAt = ConcurrentHashMap<String, Long>()
        private val packagePendingFreezeCommands = ConcurrentHashMap<String, PendingFreezeCommand>()
        private val packageLastCommittedBackground = ConcurrentHashMap<String, Boolean>()
        private val packageForegroundPids = ConcurrentHashMap<String, MutableSet<Int>>()
        private val packageLaunchProtectUntil = ConcurrentHashMap<String, Long>()
        private val packageUidCache = ConcurrentHashMap<String, Int>()
        private val failureLogAt = ConcurrentHashMap<String, Long>()
        private val packageStateScheduler = Executors.newSingleThreadScheduledExecutor()
        private val daemonCommandExecutor = Executors.newFixedThreadPool(2)
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
        private val uidTrafficActiveUntil = ConcurrentHashMap<Int, Long>()
        private const val STABLE_STATE_WINDOW_MS = 1200L
        private const val PACKAGE_SIGNAL_DEBOUNCE_MS = 1500L
        private const val MIN_COMMIT_SWITCH_INTERVAL_MS = 2500L
        private const val DEFAULT_BACKGROUND_POLL_INTERVAL_MS = 30_000L
        private const val FAILURE_LOG_THROTTLE_MS = 60_000L
        private const val SKIP_LOG_THROTTLE_MS = 30_000L
        private const val FREEZE_REASSERT_INTERVAL_MS = 15_000L
        private const val NETWORK_ACTIVE_WINDOW_MS = 35_000L
        private const val LAUNCH_PROTECT_MS = 15_000L
        private const val FOREGROUND_RETURN_PROTECT_MS = 10_000L
        private const val ENABLE_BRIDGE_HOT_LOGS = false
        private const val ENABLE_IPC_LOG = false
        private const val PROCESS_STATE_TOP_THRESHOLD = 2
        private const val PROCESS_STATE_FOREGROUND_THRESHOLD = 6
        private const val PROCESS_STATE_VISIBLE_THRESHOLD = 8
        private const val PROCESS_PRIORITY_RULE_VERSION = 1

        @Volatile
        private var lastPollAtMs: Long = 0L
        @Volatile
        private var lastTriggerValue: String? = null
        @Volatile
        private var freezeRules: Map<String, FreezeRule> = emptyMap()
        private val processPriorityRules = AtomicReference<Map<Triple<Int, String, String>, Int>>(emptyMap())
        private val processPriorityHookInstalled = AtomicBoolean(false)
        private val processPriorityObserverInstalled = AtomicBoolean(false)
        private val systemContextReadyHookInstalled = AtomicBoolean(false)
        private val processPriorityContentServiceWaiterStarted = AtomicBoolean(false)
        private val processPriorityContentServiceReady = AtomicBoolean(false)
        private val processPrioritySettingsProviderHookInstalled = AtomicBoolean(false)
        private val lastOomClampLogByPid = ConcurrentHashMap<Int, String>()
        private const val DIRECT_FREEZE_UNKNOWN = 0
        private const val DIRECT_FREEZE_OK = 1
        private const val DIRECT_FREEZE_DENIED = -1
        private val directFreezeMode = AtomicInteger(DIRECT_FREEZE_UNKNOWN)
        @Volatile
        private var processPriorityObserver: ContentObserver? = null
    }

    // Zygote 阶段先记住模块 apk 路径，后面 system_server 里要靠它读取内置规则文件。
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        moduleApkPath = startupParam.modulePath
        XposedBridge.log("$TAG initZygote: modulePath=${startupParam.modulePath}")
    }

    // 这是 Hook 总入口：只在 android/system_server 进程里装载冻结、前台和交互相关 Hook。
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) {
            return
        }
        systemClassLoader = lpparam.classLoader
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
        HookDaemonBridge.recordLog(message)
        if (ENABLE_BRIDGE_HOT_LOGS) {
            XposedBridge.log("$TAG $message")
        }
        runCatching {
            Log.i("Silence", "Silence|hook|$message")
        }
    }


    // 冻结规则只加载一次，优先读运行时配置，读不到再回退到模块内置资产。
    private fun ensureFreezeRulesLoaded() {
        if (!freezeRulesLoaded.compareAndSet(false, true)) {
            return
        }
        freezeRules = loadFreezeRulesFromAsset()
        XposedBridge.log("$TAG freeze rules loaded: apps=${freezeRules.size}")
        logHookDebug("freeze rules loaded apps=${freezeRules.size}")
    }

    // 内置规则是最后回退来源，用来保证首次启动或运行时文件损坏时仍然能工作。
    private fun loadFreezeRulesFromAsset(): Map<String, FreezeRule> = loadFreezeRulesFromRuntimeFile().orEmpty()

    private fun loadFreezeRulesFromRuntimeFile(): Map<String, FreezeRule>? {
        val json = HookDaemonBridge.rules() ?: return null
        return parseFreezeRules(json)
    }



    // 规则解析时会强制补上 Silence 自己的白名单，避免误冻自身。
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

    // 如果当前 classLoader 装不上核心 Hook，就延迟到 ActivityThread.systemMain 再补挂一次。
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

    // system context 只要成功拿到一次，后面 IPC、Settings 和广播都靠它完成。
    private fun ensureSystemContext(classLoader: ClassLoader) {
        if (appContext != null) return
        runCatching {
            val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            val current = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            val context = XposedHelpers.callMethod(current, "getSystemContext")
            appContext = context as? Context
        }
    }

    // 把当前进程需要的进程态、ATMS 和亮灭屏 Hook 一次性装好并返回装载数量。
    private fun installHooksForProcess(classLoader: ClassLoader, processName: String): Int {
        var totalHooks = 0

        totalHooks += installProcessPriorityProtectionHook(classLoader)

        totalHooks += hookByClassAndMethods(
            classLoader = classLoader,
            className = "com.android.server.am.ProcessStateRecord",
            methodNames = listOf(
                "setCurProcState",
                "setCurRawProcState"
            )
        )

        totalHooks += installActivityClientControllerHooks(classLoader)
        totalHooks += installPrelaunchThawHook(classLoader)
        totalHooks += installInteractionHooks(classLoader)

        // 主动获取 ATMS 引用，避免依赖 Hook 回调延迟导致前台检测失效
        eagerlyResolveAtmsService()

        XposedBridge.log("$TAG installHooksForProcess result: process=$processName, hooks=$totalHooks")
        return totalHooks
    }

    // 只在系统计算结果进入 setCurAdj 时截住受保护进程，不轮询也不直接写内核值。
    private fun installProcessPriorityProtectionHook(classLoader: ClassLoader): Int {
        val clazz = runCatching {
            XposedHelpers.findClass("com.android.server.am.ProcessStateRecord", classLoader)
        }.getOrElse { error ->
            Log.e("Silence", "Silence|oom-adj|ProcessStateRecord unavailable: ${error.javaClass.simpleName}:${error.message}")
            return 0
        }
        val setterMethods = generateSequence(clazz as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { method ->
                method.name == "setCurAdj" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .distinctBy { "${it.declaringClass.name}#${it.name}" }
            .toList()
        if (setterMethods.isEmpty()) {
            Log.e("Silence", "Silence|oom-adj|setCurAdj(int) not found class=${clazz.name}")
            XposedBridge.log("$TAG oom-adj setCurAdj(int) not found class=${clazz.name}")
            return 0
        }

        var hooked = 0
        setterMethods.forEach { method ->
            runCatching {
                method.isAccessible = true
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        clampProtectedProcessAdj(param)
                    }
                })
                hooked += 1
                Log.i("Silence", "Silence|oom-adj|hook installed method=${method.declaringClass.name}#${method.name}")
                XposedBridge.log("$TAG oom-adj hook installed method=${method.declaringClass.name}#${method.name}")
            }.onFailure { error ->
                val message = "oom-adj hook install failed method=${method.declaringClass.name}#${method.name}: ${error.javaClass.simpleName}:${error.message}"
                Log.e(
                    "Silence",
                    "Silence|$message"
                )
                XposedBridge.log("$TAG $message")
            }
        }
        if (hooked > 0) {
            processPriorityHookInstalled.set(true)
            waitForProcessPriorityContentService(classLoader)
            installProcessPrioritySettingsProviderReadyHook(classLoader)
            if (appContext == null) {
                val message = "oom-adj hook ready; waiting for system context before registering rules observer"
                Log.w("Silence", "Silence|$message")
                XposedBridge.log("$TAG $message")
                installProcessPriorityObserverAfterContextReady(classLoader)
            }
        }
        return hooked
    }

    private fun installProcessPriorityObserverAfterContextReady(classLoader: ClassLoader) {
        if (!systemContextReadyHookInstalled.compareAndSet(false, true)) return

        val activityThreadClass = runCatching {
            XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        }.getOrElse { error ->
            systemContextReadyHookInstalled.set(false)
            val message = "oom-adj cannot watch system context: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
            return
        }

        var hookedMethods = 0
        listOf("systemMain", "getSystemContext").forEach { methodName ->
            runCatching {
                val hooks = XposedBridge.hookAllMethods(
                    activityThreadClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val context = if (methodName == "getSystemContext") {
                                param.result as? Context
                            } else {
                                val activityThread = param.result ?: runCatching {
                                    XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                                }.getOrNull() ?: return
                                runCatching {
                                    XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context
                                }.getOrNull()
                            } ?: return

                            appContext = context
                            val message = "oom-adj system context ready source=ActivityThread#$methodName"
                            Log.i("Silence", "Silence|$message")
                            XposedBridge.log("$TAG $message")
                            if (processPriorityHookInstalled.get()) {
                                tryInstallProcessPriorityRulesObserver("system-context-ready")
                            }
                        }
                    }
                )
                if (hooks.isNotEmpty()) {
                    hookedMethods += hooks.size
                    XposedBridge.log("$TAG oom-adj context listener installed method=${activityThreadClass.name}#$methodName count=${hooks.size}")
                }
            }.onFailure { error ->
                val message = "oom-adj context listener failed method=${activityThreadClass.name}#$methodName: ${error.javaClass.simpleName}:${error.message}"
                Log.e("Silence", "Silence|$message")
                XposedBridge.log("$TAG $message")
            }
        }

        if (hookedMethods == 0) {
            systemContextReadyHookInstalled.set(false)
            val message = "oom-adj context listener unavailable class=${activityThreadClass.name}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
        } else {
            XposedBridge.log("$TAG oom-adj waiting for system context hookedMethods=$hookedMethods")
        }
    }

    private fun waitForProcessPriorityContentService(classLoader: ClassLoader) {
        if (!processPriorityContentServiceWaiterStarted.compareAndSet(false, true)) return

        val waiter = Thread({
            val waitStartedAt = System.currentTimeMillis()
            runCatching {
                val managerClass = XposedHelpers.findClass("android.os.ServiceManager", classLoader)
                val waitForService = managerClass.getDeclaredMethod("waitForService", String::class.java).apply {
                    isAccessible = true
                }
                val binder = waitForService.invoke(null, "content") as? IBinder
                    ?: error("ServiceManager.waitForService(content) returned null")
                processPriorityContentServiceReady.set(true)
                val waitMs = System.currentTimeMillis() - waitStartedAt
                XposedBridge.log("$TAG oom-adj ContentService ready waitMs=$waitMs binderAlive=${binder.isBinderAlive}")

                if (appContext == null) {
                    ensureSystemContext(classLoader)
                }
                tryInstallProcessPriorityRulesObserver("content-service-ready")
            }.onFailure { error ->
                processPriorityContentServiceWaiterStarted.set(false)
                val message = "oom-adj waiting for ContentService failed: ${error.javaClass.simpleName}:${error.message}"
                Log.e("Silence", "Silence|$message")
                XposedBridge.log("$TAG $message")
            }
        }, "SilenceContentServiceWaiter").apply {
            isDaemon = true
        }

        runCatching {
            waiter.start()
            XposedBridge.log("$TAG oom-adj waiting for ContentService through ServiceManager.waitForService")
        }.onFailure { error ->
            processPriorityContentServiceWaiterStarted.set(false)
            val message = "oom-adj ContentService waiter thread failed to start: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
        }
    }

    private fun tryInstallProcessPriorityRulesObserver(source: String) {
        if (!processPriorityHookInstalled.get()) return
        if (!processPriorityContentServiceReady.get()) {
            XposedBridge.log("$TAG oom-adj rules observer pending source=$source: ContentService not ready")
            return
        }
        if (appContext == null) {
            XposedBridge.log("$TAG oom-adj rules observer pending source=$source: system context missing")
            return
        }
        installProcessPriorityRulesObserver()
    }

    private fun installProcessPrioritySettingsProviderReadyHook(classLoader: ClassLoader) {
        if (!processPrioritySettingsProviderHookInstalled.compareAndSet(false, true)) return

        val providerHelperClass = runCatching {
            XposedHelpers.findClass("com.android.server.am.ContentProviderHelper", classLoader)
        }.getOrElse { error ->
            processPrioritySettingsProviderHookInstalled.set(false)
            val message = "oom-adj cannot find SettingsProvider startup owner: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
            return
        }

        val hooks = runCatching {
            XposedBridge.hookAllMethods(
                providerHelperClass,
                "installSystemProviders",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        XposedBridge.log("$TAG oom-adj system providers installed; reloading priority rules")
                        reloadProcessPriorityRules("settings-provider-ready", requestRecompute = true)
                    }
                }
            )
        }.getOrElse { error ->
            processPrioritySettingsProviderHookInstalled.set(false)
            val message = "oom-adj SettingsProvider readiness hook failed class=${providerHelperClass.name}: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
            return
        }

        if (hooks.isEmpty()) {
            processPrioritySettingsProviderHookInstalled.set(false)
            val message = "oom-adj installSystemProviders method unavailable class=${providerHelperClass.name}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
        } else {
            XposedBridge.log("$TAG oom-adj SettingsProvider readiness listener installed class=${providerHelperClass.name} methods=${hooks.size}")
        }
    }

    private fun clampProtectedProcessAdj(param: XC_MethodHook.MethodHookParam) {
        val requestedAdj = (param.args.firstOrNull() as? Number)?.toInt() ?: return
        val rules = processPriorityRules.get()
        if (rules.isEmpty()) return

        val stateRecord = param.thisObject ?: return
        val processRecord = findProcessRecordFromObject(stateRecord) ?: run {
            logProcessPriorityHookIssue(
                "record-${stateRecord.javaClass.name}",
                "setCurAdj target did not resolve to ProcessRecord class=${stateRecord.javaClass.name}"
            )
            return
        }
        val uid = readInt(processRecord, listOf("getUid"), listOf("uid", "mUid")) ?: run {
            logProcessPriorityHookIssue("uid-${processRecord.javaClass.name}", "ProcessRecord UID unavailable class=${processRecord.javaClass.name}")
            return
        }
        if (rules.keys.none { it.first == uid }) return
        val processName = readString(
            processRecord,
            listOf("getProcessName"),
            listOf("processName", "mProcessName")
        ) ?: run {
            logProcessPriorityHookIssue("process-${processRecord.javaClass.name}", "ProcessRecord processName unavailable class=${processRecord.javaClass.name}")
            return
        }
        val appInfo = readObject(processRecord, listOf("info", "mInfo", "applicationInfo", "mApplicationInfo"))
        val packageName = appInfo?.let {
            readString(it, listOf("getPackageName"), listOf("packageName"))
        } ?: readString(processRecord, listOf("getPackageName"), listOf("packageName", "mPackageName"))
        if (packageName.isNullOrBlank()) {
            logProcessPriorityHookIssue(
                "package-${processRecord.javaClass.name}",
                "ProcessRecord packageName unavailable uid=$uid process=$processName class=${processRecord.javaClass.name}"
            )
            return
        }

        val maxAdj = rules[Triple(uid, packageName, processName)] ?: return
        val pid = readInt(processRecord, listOf("getPid"), listOf("pid", "mPid")) ?: -1
        if (requestedAdj <= maxAdj) {
            val signature = "$uid|$packageName|$processName|$requestedAdj<=$maxAdj"
            if (lastOomClampLogByPid.put(pid, signature) != signature) {
                val message = "oom-adj pass pid=$pid uid=$uid package=$packageName process=$processName requested=$requestedAdj max=$maxAdj"
                Log.i("Silence", "Silence|$message")
                XposedBridge.log("$TAG $message")
            }
            return
        }

        param.args[0] = maxAdj
        val signature = "$uid|$packageName|$processName|$requestedAdj->$maxAdj"
        if (lastOomClampLogByPid.put(pid, signature) != signature) {
            val message = "oom-adj clamped pid=$pid uid=$uid package=$packageName process=$processName requested=$requestedAdj applied=$maxAdj"
            Log.i(
                "Silence",
                "Silence|$message"
            )
            XposedBridge.log("$TAG $message")
        }
    }

    private fun installProcessPriorityRulesObserver() {
        val context = appContext
        if (context == null) {
            val message = "oom-adj rules observer unavailable: system context missing"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
            return
        }
        if (!processPriorityObserverInstalled.compareAndSet(false, true)) return

        val key = FreezeListStore.runtimeGlobalProcessPriorityRulesKey()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                XposedBridge.log("$TAG oom-adj settings observer event key=$key selfChange=$selfChange uri=${uri ?: "null"}")
                reloadProcessPriorityRules("settings-observer", requestRecompute = true)
            }
        }
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
            processPriorityObserver = observer
            Log.i("Silence", "Silence|oom-adj|rules observer registered key=$key")
            XposedBridge.log("$TAG oom-adj rules observer registered key=$key")
            reloadProcessPriorityRules("initial", requestRecompute = true)
        }.onFailure { error ->
            processPriorityObserverInstalled.set(false)
            val message = "oom-adj rules observer registration failed key=$key: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
        }
    }

    private fun reloadProcessPriorityRules(source: String, requestRecompute: Boolean) {
        val encoded = readGlobalSetting(FreezeListStore.runtimeGlobalProcessPriorityRulesKey()).trim()
        if (encoded.isEmpty()) {
            XposedBridge.log("$TAG oom-adj rules read empty source=$source key=${FreezeListStore.runtimeGlobalProcessPriorityRulesKey()}")
        }
        val parsedRules = runCatching {
            if (encoded.isEmpty()) {
                emptyMap()
            } else {
                val raw = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                val root = JSONObject(raw)
                require(root.getInt("version") == PROCESS_PRIORITY_RULE_VERSION) { "unsupported version" }
                val array = root.getJSONArray("rules")
                buildMap {
                    for (index in 0 until array.length()) {
                        val rule = array.getJSONObject(index)
                        val uid = rule.getInt("uid")
                        val packageName = rule.getString("package_name").trim()
                        val processName = rule.getString("process_name").trim()
                        val maxAdj = rule.getInt("max_adj")
                        require(uid >= 10000) { "invalid uid=$uid" }
                        require(packageName.isNotBlank()) { "empty package name" }
                        require(processName.isNotBlank()) { "empty process name" }
                        require(maxAdj in -1000..1000) { "invalid max_adj=$maxAdj" }
                        put(Triple(uid, packageName, processName), maxAdj)
                    }
                }
            }
        }.getOrElse { error ->
            val message = "oom-adj rules reload rejected source=$source: ${error.javaClass.simpleName}:${error.message}"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
            return
        }

        val currentRules = processPriorityRules.get()
        if (currentRules == parsedRules) {
            XposedBridge.log("$TAG oom-adj rules unchanged source=$source count=${parsedRules.size}")
            return
        }
        processPriorityRules.set(parsedRules)
        lastOomClampLogByPid.clear()
        val message = "oom-adj rules loaded source=$source count=${parsedRules.size} identities=${parsedRules.keys.joinToString(",") { "${it.first}:${it.second}/${it.third}" }}"
        Log.i("Silence", "Silence|$message")
        XposedBridge.log("$TAG $message")
        if (requestRecompute) requestProcessPriorityOomRecompute(source)
    }

    // 规则变化时只请求一次 AMS 重算；后续生命周期重算由 setCurAdj Hook 处理。
    private fun requestProcessPriorityOomRecompute(source: String) {
        val handler = Handler(Looper.getMainLooper())
        val posted = handler.post {
            runCatching {
                val localServicesClass = Class.forName("com.android.server.LocalServices")
                val activityManagerInternalClass = Class.forName("android.app.ActivityManagerInternal")
                val getService = localServicesClass.getDeclaredMethod("getService", Class::class.java).apply {
                    isAccessible = true
                }
                val service = getService.invoke(null, activityManagerInternalClass)
                    ?: error("ActivityManagerInternal service missing")
                val reasonField = findField(activityManagerInternalClass, "OOM_ADJ_REASON_RECONFIGURATION")
                    ?: error("OOM_ADJ_REASON_RECONFIGURATION missing")
                val reason = (reasonField.get(null) as? Number)?.toInt()
                    ?: error("OOM_ADJ_REASON_RECONFIGURATION unreadable")
                val updateMethod = generateSequence(service.javaClass as Class<*>?) { it.superclass }
                    .mapNotNull { current ->
                        runCatching {
                            current.getDeclaredMethod("updateOomAdj", Int::class.javaPrimitiveType).apply {
                                isAccessible = true
                            }
                        }.getOrNull()
                    }
                    .firstOrNull() ?: error("ActivityManagerInternal.updateOomAdj(int) missing")
                updateMethod.invoke(service, reason)
                Log.i("Silence", "Silence|oom-adj|recompute requested source=$source reason=$reason")
                XposedBridge.log("$TAG oom-adj recompute requested source=$source reason=$reason")
            }.onFailure { error ->
                val message = "oom-adj recompute failed source=$source: ${error.javaClass.simpleName}:${error.message}"
                Log.e("Silence", "Silence|$message")
                XposedBridge.log("$TAG $message")
            }
        }
        if (!posted) {
            val message = "oom-adj recompute post failed source=$source"
            Log.e("Silence", "Silence|$message")
            XposedBridge.log("$TAG $message")
        }
    }

    private fun logProcessPriorityHookIssue(key: String, message: String) {
        val now = System.currentTimeMillis()
        val previous = failureLogAt.put(key, now) ?: 0L
        if (now - previous >= FAILURE_LOG_THROTTLE_MS) {
            Log.w("Silence", "Silence|oom-adj|hook diagnostic=$message")
            XposedBridge.log("$TAG oom-adj hook diagnostic=$message")
        }
    }

    // 主动从系统服务中获取 ATMS 引用，确保轮询时能正确识别前台应用。
    private fun eagerlyResolveAtmsService() {
        if (lastAtmsService != null) return
        val context = appContext ?: return
        runCatching {
            val atmInternal = readObject(context, listOf("getAtmInternalCached"))
            if (atmInternal != null) {
                lastAtmsService = atmInternal
                logHookDebug("eagerlyResolved ATMS via context")
                return
            }
        }
        runCatching {
            val clazz = Class.forName("android.app.ActivityTaskManager")
            val getService = clazz.getMethod("getService")
            val service = getService.invoke(null)
            if (service != null) {
                lastAtmsService = service
                logHookDebug("eagerlyResolved ATMS via getService")
                return
            }
        }
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val current = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            if (current != null) {
                val systemContext = current.javaClass.getMethod("getSystemContext").invoke(current) as? Context
                val atmsField = systemContext?.javaClass?.let { cls ->
                    var c: Class<*>? = cls
                    while (c != null) {
                        try { return@let c.getDeclaredField("mActivityTaskManager") }
                        catch (_: Throwable) { c = c.superclass }
                    }
                    null
                }
                if (atmsField != null) {
                    atmsField.isAccessible = true
                    val atms = atmsField.get(systemContext)
                    if (atms != null) {
                        lastAtmsService = atms
                        logHookDebug("eagerlyResolved ATMS via systemContext field")
                        return
                    }
                }
            }
        }
        logHookDebug("eagerlyResolve ATMS failed, will rely on hooks")
    }

    // ATMS Hook 负责感知应用切前台、切后台，是冻结和性能调控共享的核心信号源。
    private fun installActivityClientControllerHooks(classLoader: ClassLoader): Int {
        val controllerClass = runCatching {
            XposedHelpers.findClass("com.android.server.wm.ActivityClientController", classLoader)
        }.getOrElse { err ->
            XposedBridge.log("$TAG class unavailable: com.android.server.wm.ActivityClientController (${err.message})")
            return 0
        }

        var hookedCount = 0
        // Android 16 在 ActivityClientController 中处理 ActivityClient 的生命周期 Binder 回调。
        val methods = listOf("activityPaused", "activityResumed")

        methods.forEach { methodName ->
            runCatching {
                val unhooks = XposedBridge.hookAllMethods(controllerClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val movedToBackground = methodName == "activityPaused"
                        val atmsService = readObject(param.thisObject, listOf("mService"))
                        handleAtmsSignal(
                            source = "ActivityClientController#$methodName",
                            atmsService = atmsService ?: param.thisObject,
                            args = param.args,
                            movedToBackground = movedToBackground
                        )
                    }
                })
                if (unhooks.isNotEmpty()) {
                    hookedCount += unhooks.size
                    XposedBridge.log("$TAG hooked ${controllerClass.name}#$methodName count=${unhooks.size}")
                } else {
                    XposedBridge.log("$TAG hook unavailable ${controllerClass.name}#$methodName (method not found)")
                }
            }.onFailure { err ->
                XposedBridge.log("$TAG hook failed ${controllerClass.name}#$methodName (${err.message})")
            }
        }

        return hookedCount
    }

    // 在 AMS 向应用主线程投递启动事务前同步解冻，避免等待已经被冻结的应用回调自己解冻。
    private fun installPrelaunchThawHook(classLoader: ClassLoader): Int {
        val targets = listOf(
            "com.android.server.wm.ActivityStarter" to "startActivityInner",
            "com.android.server.wm.ActivityTaskSupervisor" to "realStartActivityLocked"
        )
        var hookedCount = 0
        targets.forEach { (className, methodName) ->
            val targetClass = runCatching { XposedHelpers.findClass(className, classLoader) }
                .getOrElse { err ->
                    XposedBridge.log("$TAG class unavailable: $className (${err.message})")
                    return@forEach
                }
            runCatching {
                val hooks = XposedBridge.hookAllMethods(
                    targetClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val objects = sequenceOf(param.thisObject)
                                .plus(param.args.asSequence().filterNotNull())
                                .plus(
                                    sequenceOf(
                                        readObject(param.thisObject, listOf("mStartActivity", "mActivity", "mRequest"))
                                    ).filterNotNull()
                                )
                            val packageName = objects
                                .mapNotNull { arg -> runCatching { extractPackageNameFromRecord(arg) }.getOrNull() }
                                .firstOrNull { freezeRules.containsKey(it) }
                                ?: return
                            thawBeforeActivityStart(packageName, "$className#$methodName")
                        }
                    }
                )
                if (hooks.isEmpty()) {
                    XposedBridge.log("$TAG hook unavailable $className#$methodName")
                } else {
                    hookedCount += hooks.size
                    XposedBridge.log("$TAG hooked $className#$methodName count=${hooks.size}")
                }
            }.onFailure { err ->
                XposedBridge.log("$TAG hook failed $className#$methodName (${err.message})")
            }
        }
        return hookedCount
    }

    // 亮屏、灭屏等交互信号单独挂在 PhoneWindowManager 上，给调控和冻结做补充触发。
    private fun installInteractionHooks(classLoader: ClassLoader): Int {
        val pwmClass = runCatching {
            XposedHelpers.findClass("com.android.server.policy.PhoneWindowManager", classLoader)
        }.getOrElse {
            return 0
        }

        var hookedCount = 0
        val methods = listOf(
            "startedWakingUp",
            "finishedWakingUp",
            "screenTurnedOn",
            "finishedGoingToSleep"
        )

        methods.forEach { methodName ->
            runCatching {
                val unhooks = XposedBridge.hookAllMethods(pwmClass, methodName, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        handleInteractionSignal(
                            source = "PWM#$methodName",
                            pwm = param.thisObject,
                            waking = methodName != "finishedGoingToSleep"
                        )
                    }
                })
                if (unhooks.isNotEmpty()) {
                    hookedCount += unhooks.size
                    XposedBridge.log("$TAG hooked ${pwmClass.name}#$methodName count=${unhooks.size}")
                }
            }.onFailure { err ->
                XposedBridge.log("$TAG hook failed ${pwmClass.name}#$methodName (${err.message})")
            }
        }

        return hookedCount
    }

    // 交互事件到来后先尝试更新前台包，再主动触发一次轮询兜底。
    private fun handleInteractionSignal(source: String, pwm: Any?, waking: Boolean) {
        if (!isHookEnabled()) {
            return
        }
        logHookDebug("interaction $source waking=$waking")
        if (waking) {
            resolveTopResumedPackageNameFromPolicy(pwm)?.let { packageName ->
                markTopResumedPackage(
                    packageName,
                    "$source#policy",
                    publishForegroundState = true
                )
            }
        }
        pollScheduler.execute {
            runCatching { runBackgroundPoll("interaction:$source") }
                .onFailure { err ->
                    logHookDebug("interaction poll failed source=$source err=${err.message ?: err.javaClass.simpleName}")
                }
        }
    }

    private fun resolveTopResumedPackageNameFromPolicy(pwm: Any?): String? {
        val manager = pwm ?: return null
        val atms = readObject(
            manager,
            listOf("mActivityTaskManagerInternal", "mActivityTaskManagerService", "mAtmInternal", "mAtmService")
        ) ?: return null
        lastAtmsService = atms
        return resolveTopResumedPackageName(atms)
    }

    // ATMS 回调会把原始参数统一折叠成“某包进入前台/后台”这一层状态信号。
    private fun handleAtmsSignal(
        source: String,
        atmsService: Any?,
        args: Array<Any?>,
        movedToBackground: Boolean
    ) {
        if (!isHookEnabled()) {
            return
        }
        if (atmsService != null) {
            lastAtmsService = atmsService
        }
        val packageName = resolvePackageNameFromAtmsSignal(
            atmsService = atmsService,
            args = args,
            movedToBackground = movedToBackground
        )
        if (packageName.isNullOrBlank()) {
            return
        }
        logHookDebug(
            "前后台切换 package=$packageName state=${if (movedToBackground) "后台" else "前台"} source=$source"
        )
        var clearedTopPackage = false
        synchronized(packageStateLock) {
            if (movedToBackground) {
                packageForegroundPids.remove(packageName)
                if (currentTopResumedPackage == packageName) {
                    currentTopResumedPackage = null
                    clearedTopPackage = true
                }
                Unit
            } else {
                markTopResumedPackageLocked(packageName)
                Unit
            }
        }
        if (clearedTopPackage) {
            if (isPerformanceHookEnabled()) {
                publishForegroundPackage(null, "$source#cleared")
            }
        } else if (!movedToBackground) {
            if (isPerformanceHookEnabled()) {
                publishForegroundPackage(packageName, "$source#foreground")
            }
        }
        signalPackageState(packageName, movedToBackground, source)
    }

    private fun markTopResumedPackage(packageName: String, source: String) {
        markTopResumedPackage(packageName, source, publishForegroundState = true)
    }

    // 当前台包真正变化时，同时更新顶部缓存，并按需要向 app 侧发布前台状态。
    private fun markTopResumedPackage(
        packageName: String,
        source: String,
        publishForegroundState: Boolean
    ) {
        if (!isLikelyPackageName(packageName)) {
            return
        }
        var changed = false
        synchronized(packageStateLock) {
            changed = markTopResumedPackageLocked(packageName)
        }
        if (publishForegroundState && changed) {
            publishForegroundPackage(packageName, source)
        }
        if (changed) {
            logHookDebug("top resumed $packageName source=$source")
        }
    }

    private fun markTopResumedPackageLocked(packageName: String): Boolean {
        val normalized = packageName.trim()
        if (!isLikelyPackageName(normalized)) {
            return false
        }
        val changed = currentTopResumedPackage != normalized
        currentTopResumedPackage = normalized
        packageLaunchProtectUntil[normalized] = System.currentTimeMillis() + LAUNCH_PROTECT_MS
        val stalePackages = packageForegroundPids.keys.filter { it != normalized }
        stalePackages.forEach { packageForegroundPids.remove(it) }
        return changed
    }

    private fun resolvePackageNameFromAtmsSignal(
        atmsService: Any?,
        args: Array<Any?>,
        movedToBackground: Boolean
    ): String? {
        args.firstOrNull { it is IBinder }?.let { token ->
            val pkg = getPackageFromActivityToken(atmsService, token as IBinder)
            if (!pkg.isNullOrBlank()) return pkg
        }

        args.firstOrNull { it is Int }?.let { value ->
            val pkg = getPackageFromTaskId(atmsService, value as Int)
            if (!pkg.isNullOrBlank()) return pkg
        }

        args.forEach { arg ->
            if (arg != null) {
                extractPackageNameFromRecord(arg)?.let { return it }
                extractPackageNameFromTask(arg)?.let { return it }
                extractPackageNameFromIntent(arg)?.let { return it }
            }

            val candidate = arg?.toString()?.trim().orEmpty()
            if (isLikelyPackageName(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun resolveTopResumedPackageName(atmsService: Any?): String? {
        if (atmsService != null) {
            readObject(atmsService, listOf("mTopResumedActivity"))?.let { record ->
                extractPackageNameFromRecord(record)?.let { return it }
            }

            val root = readObject(atmsService, listOf("mRootWindowContainer"))
            if (root != null) {
                runCatching {
                    findNoArgMethod(root.javaClass, "getTopResumedActivity")?.let { method ->
                        method.isAccessible = true
                        method.invoke(root)
                    }
                }.getOrNull()?.let { record ->
                    extractPackageNameFromRecord(record)?.let { return it }
                }

                runCatching {
                    findNoArgMethod(root.javaClass, "getTopDisplayFocusedRootTask")?.let { method ->
                        method.isAccessible = true
                        method.invoke(root)
                    }
                }.getOrNull()?.let { task ->
                    extractPackageNameFromTask(task)?.let { return it }
                }
            }
        }

        // 轮询和异步冻结提交也需要一个实时顶层包；ATMS 内部字段在部分 ROM 上不可读时，
        // 直接从系统当前 Activity 状态解析，不能让旧的前台缓存继续代表当前页面。
        return currentTopResumedPackage
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
                // 进程进入前台时设置启动保护，防止轮询在 ATMS 回调到达前误冻
                packageLaunchProtectUntil[packageName] = now + LAUNCH_PROTECT_MS
            }
            if (movedToBackground) {
                foregroundSet.remove(pid)
                if (foregroundSet.isEmpty()) {
                    packageForegroundPids.remove(packageName)
                }
            }
            desiredBackground = foregroundSet.isEmpty()

            val previousDesired = packageLastDesiredState[packageName]
            val lastSignal = packageLastSignalAt[packageName] ?: 0L
            val forceForegroundSignal =
                movedToForeground && (
                    freezeRules.containsKey(packageName) ||
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

    // 这里是前后台信号聚合器：前台可立即解冻，后台则进入稳定窗口后再决定是否冻结。
    private fun signalPackageState(packageName: String, movedToBackground: Boolean, source: String) {
        if (!isHookEnabled() || !isFreezeHookEnabled()) {
            return
        }
        val normalized = packageName.trim()
        if (!isLikelyPackageName(normalized)) {
            return
        }
        val hasRule = freezeRules.containsKey(normalized)
        val isManaged = hasRule || frozenPackages.contains(normalized)

        if (!movedToBackground) {
            packageDesiredBackground[normalized] = false
            packageSignalSource[normalized] = source
            if (!isManaged) {
                return
            }
            packageSignalVersion[normalized] = (packageSignalVersion[normalized] ?: 0) + 1
            packageStateScheduler.execute {
                commitForegroundState(normalized, source, forceReconcile = true)
            }
            return
        }

        if (!isManaged) {
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

    // 真正的冻结提交入口，会在下发前把白名单、前台、保护窗口和规则条件全部再校验一遍。
    private fun commitBackgroundState(packageName: String, source: String): Boolean {
        val fromPoll = source.startsWith("poll:")
        if (packageDesiredBackground[packageName] == false && !fromPoll) {
            logCommitAbort(packageName, source, "状态标记前台")
            return false
        }
        if (fromPoll) {
            packageDesiredBackground[packageName] = true
        }
        val rule = freezeRules[packageName] ?: run {
            logCommitAbort(packageName, source, "规则缺失")
            return false
        }
        val decision = evaluateFreezeDecision(packageName, rule)
        if (!decision.shouldFreeze) {
            logCommitAbort(packageName, source, decision.reason)
            return false
        }
        val now = System.currentTimeMillis()
        val wasFrozen = frozenPackages.contains(packageName)
        if (packagePendingFreezeCommands.containsKey(packageName)) {
            logCommitAbort(packageName, source, "冻结命令等待执行回执")
            return false
        }
        val lastDispatch = packageLastFreezeDispatchAt[packageName] ?: 0L
        val allowReassert = now - lastDispatch >= FREEZE_REASSERT_INTERVAL_MS
        if (wasFrozen && !allowReassert) {
            logCommitAbort(packageName, source, "保持冻结窗口")
            return false
        }
        val lastCommit = packageLastCommitAt[packageName] ?: 0L
        val lastBackground = packageLastCommittedBackground[packageName]
        if (lastBackground == true && now - lastCommit < MIN_COMMIT_SWITCH_INTERVAL_MS) {
            logCommitAbort(packageName, source, "状态切换窗口")
            return false
        }
        if (rule.whitelist || packageName == SELF_PACKAGE) {
            logCommitAbort(packageName, source, "白名单")
            return false
        }
        val topResumedNow = resolveTopResumedPackageName(lastAtmsService)
        if (!topResumedNow.isNullOrBlank()) {
            synchronized(packageStateLock) {
                markTopResumedPackageLocked(topResumedNow)
            }
            if (topResumedNow == packageName) {
                logCommitAbort(packageName, source, "topResumed复核前台")
                return false
            }
        }
        // 最终兜底：通过 packageForegroundPids 复核，防止进程状态已变前台但信号延迟的场景
        val hasForegroundPid = synchronized(packageStateLock) {
            val pids = packageForegroundPids[packageName]
            pids != null && pids.isNotEmpty()
        }
        if (hasForegroundPid) {
            logCommitAbort(packageName, source, "foregroundPid复核前台")
            return false
        }
        if (!dispatchFreezeCommandIpc(packageName, freeze = true, rule = rule, source = source)) {
            return false
        }
        packageLastFreezeDispatchAt[packageName] = now
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

    // 真正的解冻提交入口：前台一旦确认就立即写前台状态并下发解冻 IPC。
    private fun commitForegroundState(
        packageName: String,
        source: String,
        forceReconcile: Boolean = false
    ) {
        if (packageName == SELF_PACKAGE) {
            frozenPackages.remove(packageName)
            packageLastCommittedBackground.remove(packageName)
            packagePendingFreezeCommands.remove(packageName)
            return
        }
        packageLaunchProtectUntil[packageName] = System.currentTimeMillis() + FOREGROUND_RETURN_PROTECT_MS
        packageDesiredBackground[packageName] = false
        val rule = freezeRules[packageName]
        val pending = packagePendingFreezeCommands[packageName]
        val needsThaw = frozenPackages.contains(packageName) ||
            pending?.freeze == true || packageLastCommittedBackground[packageName] == true
        if (!needsThaw && !forceReconcile) {
            return
        }
        if (pending?.freeze == false) return
        if (!needsThaw && packageLastCommittedBackground[packageName] == false &&
            System.currentTimeMillis() - (packageLastCommitAt[packageName] ?: 0L) < 1_000L) return
        if (forceReconcile) {
            val result = writeFreezeStateForPackage(packageName, rule, targetFrozen = false)
            if (result.writes > 0) {
                frozenPackages.remove(packageName)
                packageLastCommitAt[packageName] = System.currentTimeMillis()
                packageLastCommittedBackground[packageName] = false
                logHookDebug(
                    "foreground reconcile thaw package=$packageName source=$source writes=${result.writes} " +
                        "targets=${result.selectedProcessNames.joinToString(",")} result=${result.reason}"
                )
                return
            }
            logFreezeFailure(packageName, source, "foreground reconcile thaw failed", result.reason)
        }
        if (!dispatchFreezeCommandIpc(packageName, freeze = false, rule = rule, source = source)) {
            return
        }
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

    private fun thawBeforeActivityStart(packageName: String, source: String) {
        if (packageName == SELF_PACKAGE) return
        val rule = freezeRules[packageName] ?: return
        val pending = packagePendingFreezeCommands[packageName]
        logHookDebug("启动前解冻检查 package=$packageName source=$source pendingFreeze=${pending?.freeze}")
        val now = System.currentTimeMillis()
        val lastAttempt = packageLastPrelaunchThawAt.put(packageName, now) ?: 0L
        if (now - lastAttempt < 1_000L) {
            return
        }
        packageDesiredBackground[packageName] = false
        packageLaunchProtectUntil[packageName] = now + LAUNCH_PROTECT_MS
        packagePendingFreezeCommands.remove(packageName)
        val result = writeFreezeStateForPackage(packageName, rule, targetFrozen = false)
        if (result.writes > 0) {
            frozenPackages.remove(packageName)
            packageLastCommittedBackground[packageName] = false
            packageLastCommitAt[packageName] = System.currentTimeMillis()
            logHookDebug(
                "prelaunch thaw package=$packageName source=$source writes=${result.writes} " +
                    "targets=${result.selectedProcessNames.joinToString(",")} result=${result.reason}"
            )
        } else {
            logFreezeFailure(packageName, source, "prelaunch thaw failed", result.reason)
        }
        // ActivityStarter invokes this hook while holding WindowManagerGlobalLock.
        // Never call ActivityManager broadcasts from that stack: they can invert AM/WMS lock order.
        packageStateScheduler.execute {
            publishForegroundPackage(packageName, "$source#deferred")
            if (result.writes == 0) {
                commitForegroundState(packageName, "$source#ipc-retry")
            }
        }
    }

    private fun onFreezeCommandResult(
        packageName: String,
        commandId: Long,
        freeze: Boolean,
        success: Boolean,
        detail: String,
        source: String
    ) {
        val pending = packagePendingFreezeCommands[packageName]
        if (pending == null || pending.commandId != commandId || pending.freeze != freeze) {
            logHookDebug("stale freeze result package=$packageName freeze=$freeze id=$commandId source=$source")
            return
        }
        packagePendingFreezeCommands.remove(packageName, pending)
        if (success) {
            if (freeze) {
                frozenPackages.add(packageName)
            } else {
                frozenPackages.remove(packageName)
            }
            packageLastCommittedBackground[packageName] = freeze
            packageLastCommitAt[packageName] = System.currentTimeMillis()
        } else {
            if (freeze) {
                frozenPackages.remove(packageName)
                packageLastCommittedBackground[packageName] = false
            } else {
                // Keep the package managed as frozen until a thaw is confirmed, so polling retries it.
                frozenPackages.add(packageName)
                packageLastCommittedBackground[packageName] = true
            }
            logFreezeFailure(
                packageName,
                source,
                if (freeze) "freeze execution failed" else "thaw execution failed",
                detail
            )
        }

        val desiredFreeze = packageDesiredBackground[packageName] == true
        val desiredStateMismatch = success && desiredFreeze != freeze
        if (desiredStateMismatch) {
            packageStateScheduler.execute {
                if (desiredFreeze) {
                    commitBackgroundState(packageName, "$source#ack-reconcile")
                } else {
                    commitForegroundState(packageName, "$source#ack-reconcile")
                }
            }
        }
        logHookDebug("freeze result package=$packageName freeze=$freeze success=$success source=$source detail=$detail")
    }

    // 轮询只启动一次，主要负责兜底那些没被 Hook 事件及时覆盖到的状态变化。
    private fun startBackgroundPollIfNeeded() {
        if (!pollStarted.compareAndSet(false, true)) {
            return
        }
        val initialDelay = resolveHookPollIntervalMs()
        logHookDebug("[轮询] 启动 interval=${initialDelay}ms")
        scheduleNextPoll(10L)
    }

    // 每次轮询结束后根据最新设置重新计算下次间隔，支持动态改 Hook 轮询时间。
    private fun scheduleNextPoll(delayMs: Long) {
        val safeDelay = delayMs.coerceAtLeast(10_000L)
        pollFuture = pollScheduler.schedule({
            if (!pollLoopRunning.compareAndSet(false, true)) {
                scheduleNextPoll(resolveHookPollIntervalMs())
                return@schedule
            }
            try {
                        runCatching { runBackgroundPoll("scheduled") }
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
            3000L,
            3000L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun checkForcePollTrigger() {
        // system_server 起来早期拿不到 system context，这里必须自己重试，
        // 否则配置永远拉不下来，开关会被当成关闭，整条冻结链静默停摆。
        val context = appContext ?: systemClassLoader?.let { loader ->
            ensureSystemContext(loader)
            appContext
        } ?: return
        val previousRules = HookDaemonBridge.rules()
        // oom 优先级规则必须单独比对：只改它而不动冻结规则时，配置通道也要触发重载，
        // 否则它只能靠 daemon 那次 settings put 的副作用经 ContentObserver 兜进来。
        val previousPriority = HookDaemonBridge.setting(FreezeListStore.runtimeGlobalProcessPriorityRulesKey())
        val work = HookDaemonBridge.work(context) ?: return
        val updatedRules = HookDaemonBridge.rules()
        if (updatedRules != previousRules) {
            freezeRules = updatedRules?.let { parseFreezeRules(it) }.orEmpty()
            refreshTopResumedPackage("daemon-config")
        }
        val updatedPriority = HookDaemonBridge.setting(FreezeListStore.runtimeGlobalProcessPriorityRulesKey())
        if (updatedPriority != previousPriority) {
            reloadProcessPriorityRules("daemon-config", requestRecompute = true)
        }
        val topPackage = currentTopResumedPackage
        if (isHookEnabled() && !topPackage.isNullOrBlank() && work.optString("foregroundPackage") != topPackage) {
            publishForegroundPackage(topPackage, "daemon-reconcile")
        }
        val requests = work.optJSONArray("runtimeRequests")
        if (requests != null && requests.length() > 0) {
            val states = JSONObject()
            for (index in 0 until requests.length()) {
                val packageName = requests.getString(index)
                val audio = isPackageAudioActive(packageName)
                val uid = resolvePackageUid(packageName)
                val state = readUidProcState(packageName)
                states.put(packageName, JSONObject().apply {
                    put("known", audio != null && uid != null && state != null)
                    put("audio", audio == true)
                    put("network", uid?.let { hasUidTrafficDelta(it) } ?: false)
                    put("visible", state != null && state <= PROCESS_STATE_VISIBLE_THRESHOLD)
                    put("foreground", isPackageForegroundTracked(packageName))
                })
            }
            HookDaemonBridge.reportRuntime(context, states)
        }
        val token = work.optString("forcePoll")
        if (token.isNotEmpty() && token != lastTriggerValue) {
            lastTriggerValue = token
            runBackgroundPoll("force-api")
        }
    }

    // 轮询链会重新扫描规则命中的应用，并尝试把真正该冻的后台应用补冻上。
    private fun runBackgroundPoll(source: String) {
        if (!isHookEnabled()) {
            logHookDebug("hook disabled, skip poll source=$source")
            return
        }
        // 前台检测对冻结和性能调控都必需，不再仅限 performance hook
        refreshTopResumedPackage("poll")
        if (!isFreezeHookEnabled()) {
            logHookDebug("freeze hook disabled, skip freeze poll source=$source")
            return
        }
        synchronized(packageStateLock) {
            pruneForegroundTrackingLocked()
        }
        loadFreezeRulesFromRuntimeFile()?.let { runtime ->
            freezeRules = runtime
        }
        val hookDebugEnabled = isProcessDebugLogEnabled()
        val activeKeys = freezeRules.keys.joinToString(",")
        if (hookDebugEnabled) {
            logHookDebug("freeze rules active apps=${freezeRules.size} keys=$activeKeys")
        }
        val now = System.currentTimeMillis()
        val prev = lastPollAtMs
        lastPollAtMs = now
        val delta = if (prev <= 0L) 0L else now - prev
        val prevText = if (prev <= 0L) "-" else pollTimeFormatter.format(Date(prev))

        val rules = freezeRules
        if (rules.isEmpty()) {
            logHookDebug("[轮询] source=$source time=$prevText + ${delta}ms; 本次冻结: 无(规则为空)")
            return
        }

        val lines = ArrayList<String>()
        val skipped = ArrayList<String>()
        val considered = ArrayList<String>()
        rules.forEach { (packageName, rule) ->
            considered.add(packageName)
            if (!isPackageInstalled(packageName)) {
                skipped.add("- $packageName -> 原因:包不存在")
                logPollSkip(packageName, "包不存在")
                return@forEach
            }
            val decision = evaluateFreezeDecision(packageName, rule)
            if (decision.shouldFreeze) {
                val dispatched = commitBackgroundState(packageName, "poll:${decision.reason}")
                if (dispatched) {
                    lines.add("- $packageName -> 原因:${decision.reason}")
                } else if (frozenPackages.contains(packageName)) {
                    lines.add("- $packageName -> 原因:${decision.reason}(已保持冻结)")
                } else {
                    skipped.add("- $packageName -> 原因:${decision.reason}(未下发冻结)")
                }
            } else if (frozenPackages.contains(packageName)) {
                commitForegroundState(packageName, "poll:${decision.reason}")
            } else {
                skipped.add("- $packageName -> 原因:${decision.reason}")
                logPollSkip(packageName, decision.reason)
            }
        }

        val detail = if (lines.isEmpty()) {
            "无"
        } else {
            "\n    " + lines.joinToString("\n    ")
        }
        val skippedDetail = if (skipped.isEmpty()) {
            "无"
        } else {
            "\n    " + skipped.take(12).joinToString("\n    ")
        }
        val consideredHead = considered.joinToString(",")
        if (hookDebugEnabled) {
            logHookDebug("poll considered apps=${considered.size} keys=$consideredHead")
        }
        logHookDebug("[轮询] source=$source time=$prevText + ${delta}ms; 本次冻结:$detail")
        logHookDebug("[轮询] source=$source 本次未冻结原因:$skippedDetail")
        runCatching {
            RuntimeLogStore.appendDiagnostic(
                source = "hook",
                message = "poll source=$source time=$prevText + ${delta}ms rules=${rules.size} matched=${lines.size}",
                throttleKey = "hook_poll_tick",
                throttleMs = 0L,
                category = RuntimeLogStore.LogCategory.LOG
            )
        }
    }


    private fun readGlobalSetting(key: String): String = HookDaemonBridge.setting(key)

    // 开关语义：配置没送达时按“默认开启”处理，只有明确写成 0/false 才算关闭。
    // daemon 刚重启或某次请求失败时，配置会短暂为空，这里不能把它当成用户关闭。
    private fun isSwitchEnabled(key: String): Boolean {
        if (!HookDaemonBridge.isConfigReady()) return true
        val value = readGlobalSetting(key).trim()
        if (value.isEmpty()) return true
        return value != "0" && !value.equals("false", ignoreCase = true)
    }

    // 总 Hook 开关只控制逻辑执行，不影响 LSPosed 注入本身。
    private fun isHookEnabled(): Boolean = isSwitchEnabled(FreezeListStore.runtimeGlobalHookEnabledKey())

    // 冻结 Hook 开关只影响冻结链，前台跟踪和调控信号可以独立保留。
    private fun isFreezeHookEnabled(): Boolean = isSwitchEnabled(FreezeListStore.runtimeGlobalFreezeHookEnabledKey())

    // 调控 Hook 开关主要决定是否继续发布前台/亮灭屏等调控信号。
    private fun isPerformanceHookEnabled(): Boolean = isSwitchEnabled(FreezeListStore.runtimeGlobalPerformanceHookEnabledKey())

    private fun isProcessDebugLogEnabled(): Boolean {
        val value = readGlobalSetting(FreezeListStore.runtimeGlobalProcessDebugLogEnabledKey()).trim()
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    // 冻结判定统一集中在这里，方便把“前台/可见/音频/网络/保护窗口”规则一次说清。
    private fun evaluateFreezeDecision(packageName: String, rule: FreezeRule): FreezeDecision {
        if (!isHookEnabled()) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "Hook关闭"
            )
        }
        if (!isFreezeHookEnabled()) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "冻结Hook关闭"
            )
        }
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

        val foreground = isPackageForegroundTracked(packageName)
        if (foreground) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "前台"
            )
        }
        val uidState = readUidProcState(packageName)
        if (uidState == null) return FreezeDecision(false, "UID 状态不可用，暂停冻结")
        if (uidState >= 20) return FreezeDecision(false, "process_not_running")
        if (uidState <= PROCESS_STATE_FOREGROUND_THRESHOLD) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "前台(uid=$uidState)"
            )
        }
        reasons.add("切出")

        if ("VISIBLE" in normalizedRules && isPackageVisible(packageName)) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = "不在规则:VISIBLE内"
            )
        }
        val audioGuardState = if ("AUDIO" in normalizedRules) {
            isPackageAudioActive(packageName)
        } else {
            false
        }
        if (audioGuardState != false) {
            return FreezeDecision(
                shouldFreeze = false,
                reason = if (audioGuardState == true) "音频正在播放，禁止冻结" else "音频状态无法确认，暂缓冻结"
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

    private val TOP_PACKAGE_CACHE_MS = 1_000L

    private fun isPackageForegroundTracked(packageName: String): Boolean {
        synchronized(packageStateLock) {
            val topPackage = currentTopResumedPackage
            if (!topPackage.isNullOrBlank()) {
                return topPackage == packageName
            }
            pruneForegroundTrackingLocked()
            val set = packageForegroundPids[packageName] ?: return false
            if (set.isNotEmpty()) {
                return true
            }
        }
        return false
    }

    // 通过 dumpsys 获取当前 top 应用，作为 ATMS hook 失效时的兜底。

    private fun isPackageVisible(packageName: String): Boolean {
        if (isPackageForegroundTracked(packageName)) {
            return true
        }
        val uidState = readUidProcState(packageName)
        return uidState != null && uidState <= PROCESS_STATE_VISIBLE_THRESHOLD
    }

    // 某些场景下我们只知道“该刷新前台了”，这里负责重新向系统追问当前 top app。
    private fun refreshTopResumedPackage(source: String) {
        if (!isHookEnabled()) {
            return
        }
        val packageName = resolveTopResumedPackageName(lastAtmsService)
        if (packageName.isNullOrBlank()) {
            logHookDebug("top resumed unresolved source=$source")
            return
        }
        markTopResumedPackage(packageName, source, publishForegroundState = true)
    }

    // 前台状态会同时写到 GlobalSettings 并广播给 app 侧，保证通知和页面都能同步。
    private fun publishForegroundPackage(packageName: String?, source: String) {
        if (!isHookEnabled()) return
        val context = appContext ?: return
        val normalized = packageName?.trim().orEmpty()
        packageStateScheduler.execute {
            publishForegroundPackageNow(context, normalized, source)
        }
    }

    private fun publishForegroundPackageNow(context: Context, normalized: String, source: String) {
        if (!isHookEnabled()) return
        val version = DaemonControlClient.nextVersion()
        val success = HookDaemonBridge.foreground(context, normalized, version)
        if (!success) logHookDebug("foreground report failed package=$normalized source=$source")
    }

    private fun pruneForegroundTrackingLocked() {
        val iterator = packageForegroundPids.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.removeAll { pid ->
                val state = lastProcStateByPid[pid]
                state == null || state >= BACKGROUND_STATE_THRESHOLD
            }
            if (entry.value.isEmpty()) {
                iterator.remove()
            }
        }
    }

    private fun isPackageAudioActive(packageName: String): Boolean? {
        val uid = resolvePackageUid(packageName)
            ?: return audioProbeUnavailable(packageName, "package_uid_unavailable")
        val context = appContext
            ?: return audioProbeUnavailable(packageName, "system_context_unavailable")
        val audioManager = runCatching {
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull() ?: return audioProbeUnavailable(packageName, "audio_service_unavailable")

        var unavailableReason: String? = null
        val activeForPackage = try {
            val identity = android.os.Binder.clearCallingIdentity()
            try {
                val playbackConfigurations = audioManager.activePlaybackConfigurations
                var packageHasActivePlayer = false
                var activePlayerUidUnavailable = false
                var matchingPid: Int? = null
                for (configuration in playbackConfigurations) {
                    val active = XposedHelpers.callMethod(configuration, "isActive") as? Boolean
                        ?: throw IllegalStateException("AudioPlaybackConfiguration.isActive unavailable")
                    if (!active) continue

                    val clientUid = XposedHelpers.callMethod(configuration, "getClientUid") as? Int
                        ?: throw IllegalStateException("AudioPlaybackConfiguration.getClientUid unavailable")
                    if (clientUid < 0) {
                        activePlayerUidUnavailable = true
                        continue
                    }
                    if (clientUid == uid) {
                        packageHasActivePlayer = true
                        matchingPid = runCatching {
                            XposedHelpers.callMethod(configuration, "getClientPid") as? Int
                        }.getOrNull()
                        break
                    }
                }
                when {
                    packageHasActivePlayer -> {
                        logAudioProbe(
                            packageName,
                            "active",
                            "uid=$uid pid=${matchingPid ?: "unknown"} configs=${playbackConfigurations.size}"
                        )
                        true
                    }
                    activePlayerUidUnavailable -> {
                        unavailableReason = "active_player_uid_redacted"
                        null
                    }
                    else -> {
                        logAudioProbe(packageName, "inactive", "uid=$uid configs=${playbackConfigurations.size}")
                        false
                    }
                }
            } finally {
                android.os.Binder.restoreCallingIdentity(identity)
            }
        } catch (error: Throwable) {
            unavailableReason = "${error.javaClass.simpleName}:${error.message ?: "unknown"}"
            null
        }
        return if (activeForPackage == null) {
            audioProbeUnavailable(packageName, unavailableReason ?: "playback_query_failed")
        } else {
            activeForPackage
        }
    }

    private fun audioProbeUnavailable(packageName: String, reason: String): Boolean? {
        logAudioProbe(packageName, "unavailable", "reason=${reason.take(160)}，为避免误冻已跳过冻结")
        return null
    }

    private fun logAudioProbe(packageName: String, state: String, detail: String) {
        val key = "audio_guard|$packageName|$state"
        val now = System.currentTimeMillis()
        val last = failureLogAt[key] ?: 0L
        if (now - last < DEFAULT_BACKGROUND_POLL_INTERVAL_MS) return
        failureLogAt[key] = now
        val stateLabel = when (state) {
            "active" -> "命中"
            "inactive" -> "未命中"
            else -> "不可用"
        }
        logHookDebug("音频保护 $stateLabel package=$packageName $detail")
    }

    private fun isPackageNetworkActive(packageName: String): Boolean {
        val uid = resolvePackageUid(packageName) ?: return false
        sampleUidTraffic(uid)
        return (uidTrafficActiveUntil[uid] ?: 0L) >= System.currentTimeMillis()
    }

    // 轮询不只由定时器触发，亮屏/唤醒也会插一次全量扫描。这里必须按时间窗记录“有流量”，
    // 不能用一次性的差值样本，否则先跑的那次会把差值吃掉，后面的判据全变成“没流量”。
    private fun sampleUidTraffic(uid: Int) {
        val current = readUidTrafficBytes(uid) ?: return
        val previous = uidTrafficCache.put(uid, current) ?: return
        if (current.rxBytes != previous.rxBytes || current.txBytes != previous.txBytes) {
            uidTrafficActiveUntil[uid] = System.currentTimeMillis() + NETWORK_ACTIVE_WINDOW_MS
        }
    }

    private fun readUidProcState(packageName: String): Int? {
        val uid = resolvePackageUid(packageName) ?: return null
        val context = appContext ?: return null
        val identity = android.os.Binder.clearCallingIdentity()
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) ?: return null
            XposedHelpers.callMethod(activityManager, "getUidProcessState", uid) as? Int
        } catch (error: Throwable) {
            logFreezeFailure(packageName, "uid-state", "UID state unavailable", "${error.javaClass.simpleName}:${error.message}")
            null
        } finally { android.os.Binder.restoreCallingIdentity(identity) }
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
        val context = appContext ?: return null
        val uid = runCatching {
            val identity = android.os.Binder.clearCallingIdentity()
            try {
                context.packageManager.getApplicationInfo(packageName, 0).uid
            } finally {
                android.os.Binder.restoreCallingIdentity(identity)
            }
        }.getOrNull()?.takeIf { it >= 10000 } ?: return null
        packageUidCache[packageName] = uid
        return uid
    }

    private fun hasUidTrafficDelta(uid: Int): Boolean {
        sampleUidTraffic(uid)
        return (uidTrafficActiveUntil[uid] ?: 0L) >= System.currentTimeMillis()
    }

    private fun readUidTrafficBytes(uid: Int): TrafficBytes? {
        val rx = android.net.TrafficStats.getUidRxBytes(uid)
        val tx = android.net.TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return null
        return TrafficBytes(rx, tx)
    }

    // system_server 天然具备直写条件：它在 readproc 组里（/proc 挂载带 hidepid=invisible，只有该组能看
    // 到别人的进程），而 cgroup freezer 目录的 owner 就是 system:system 0775 —— 平台自己的
    // CachedProcessState 冻后台应用走的是同一条路。所以这里不需要 root，也不需要 daemon 参与；
    // 写不了才把活退回给 daemon。
    private fun writeFreezeDirect(
        uid: Int,
        packageName: String,
        targets: List<String>,
        frozen: Boolean
    ): FreezeWriteResult? {
        if (uid < 10000 || directFreezeMode.get() == DIRECT_FREEZE_DENIED) return null
        val uidDir = File("$CGROUP_FREEZE_BASE/uid_$uid")
        val children = uidDir.listFiles()
        if (children == null) {
            // 目录都读不到就说明这条路整体不可用，不必每次都重试。
            directFreezeMode.set(DIRECT_FREEZE_DENIED)
            logHookDebug("direct freeze unavailable uid=$uid reason=cgroup_dir_unreadable")
            return null
        }
        val names = targets.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val all = names.isEmpty() || names.any { it.equals("ALL", ignoreCase = true) }
        val selected = ArrayList<DirectTarget>()
        for (child in children) {
            if (!child.name.startsWith("pid_")) continue
            val pid = child.name.removePrefix("pid_").toIntOrNull() ?: continue
            val identity = readProcessIdentity(pid) ?: continue
            if (identity.processName != packageName && !identity.processName.startsWith("$packageName:")) continue
            val display = identity.processName.substringAfter(':', "main")
            if (!all && !names.contains(identity.processName) && !names.contains(display)) continue
            selected.add(DirectTarget(pid, identity.startTime))
        }
        if (frozen && selected.isEmpty()) {
            return FreezeWriteResult(0, emptyList(), "no_target_processes")
        }
        var writes = 0
        val failures = ArrayList<String>()
        // 只冻子进程时父级可能还冻着，子进程即使解冻也跑不起来，所以解冻先写父级。
        if (!frozen) {
            if (writeFreezeFlag(File(uidDir, "cgroup.freeze"), 0)) writes++ else failures += "uid_parent"
        }
        for (target in selected) {
            // 写之前复核一次 pid 身份，避免写到已回收复用的同名进程上。
            if (readProcessStartTime(target.pid) != target.startTime) {
                failures += "pid_${target.pid}_identity_changed"
                continue
            }
            if (writeFreezeFlag(File(File(uidDir, "pid_${target.pid}"), "cgroup.freeze"), if (frozen) 1 else 0)) {
                writes++
            } else {
                failures += "pid_${target.pid}_write_failed"
            }
        }
        directFreezeMode.set(DIRECT_FREEZE_OK)
        val detail = "direct uid=$uid selected=${selected.size} writes=$writes failures=${failures.joinToString(",")}"
        if (failures.isNotEmpty()) {
            logHookDebug("direct freeze incomplete $detail")
        }
        return FreezeWriteResult(if (failures.isEmpty()) maxOf(1, writes) else 0, selected.map { it.pid.toString() }, detail)
    }

    private fun writeFreezeFlag(file: File, value: Int): Boolean = runCatching {
        val payload = (if (value == 1) "1" else "0").toByteArray()
        val fd = android.system.Os.open(file.absolutePath, android.system.OsConstants.O_WRONLY, 0)
        val written = try {
            android.system.Os.write(fd, payload, 0, payload.size)
        } finally {
            android.system.Os.close(fd)
        }
        written == payload.size && runCatching { file.readText().trim() }.getOrNull() == value.toString()
    }.getOrElse { error ->
        if (error is android.system.ErrnoException &&
            (error.errno == android.system.OsConstants.EACCES || error.errno == android.system.OsConstants.EPERM)
        ) {
            // 被 SELinux 或 DAC 拒绝就记住结果，避免每条命令都白试一次。
            directFreezeMode.set(DIRECT_FREEZE_DENIED)
            logHookDebug("direct freeze denied path=${file.absolutePath} errno=${error.errno}")
        }
        false
    }

    private fun readProcessIdentity(pid: Int): DirectProcessIdentity? {
        val cmdline = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull() ?: return null
        val name = cmdline.toString(Charsets.UTF_8).substringBefore('\u0000').trim()
        if (name.isEmpty()) return null
        val startTime = readProcessStartTime(pid) ?: return null
        return DirectProcessIdentity(name, startTime)
    }

    private fun readProcessStartTime(pid: Int): Long? {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return null
        // comm 里可能带空格甚至括号，只能从最后一个 ')' 之后开始切：state 是第 3 个字段，
        // starttime 是第 22 个字段，因此落在切分后的下标 19。
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        return stat.substring(close + 1).trim().split(Regex("\\s+")).getOrNull(19)?.toLongOrNull()
    }

    private fun writeFreezeStateForPackage(packageName: String, rule: FreezeRule?, targetFrozen: Boolean): FreezeWriteResult {
        if (packageName == SELF_PACKAGE) return FreezeWriteResult(0, emptyList(), "self_package_skipped")
        val context = appContext ?: return FreezeWriteResult(0, emptyList(), "system_context_unavailable")
        val uid = resolvePackageUid(packageName) ?: return FreezeWriteResult(0, emptyList(), "package_uid_unavailable")
        val targets = rule?.freezeProcesses.orEmpty()
        writeFreezeDirect(uid, packageName, targets, targetFrozen)?.let { direct ->
            if (direct.writes > 0) return direct
        }
        val version = DaemonControlClient.nextVersion()
        val response = HookDaemonBridge.freeze(context, packageName, uid, targetFrozen, targets, version, prelaunch = !targetFrozen)
        val verified = response?.optBoolean("success") == true
        return FreezeWriteResult(if (verified) maxOf(1, response!!.optInt("writes")) else 0,
            targets, response?.optString("detail") ?: "daemon_unavailable")
    }

    // system_server 侧真正通知 app 执行冻结/解冻命令的出口在这里。
    private fun dispatchFreezeCommandIpc(packageName: String, freeze: Boolean, rule: FreezeRule?, source: String): Boolean {
        // daemon 明确拒绝以自身 uid 为目标，继续下发只会每轮收到 invalid_target_uid，
        // 还会把 frozenPackages 污染成“自己被冻着”，让这条链一直重试下去。
        if (packageName == SELF_PACKAGE) {
            frozenPackages.remove(packageName)
            packageLastCommittedBackground.remove(packageName)
            return false
        }
        val context = appContext ?: return false
        val uid = resolvePackageUid(packageName) ?: return false
        val targets = rule?.freezeProcesses.orEmpty()
        // 直写优先：不需要 daemon，也不受 daemon 存活影响；失败或不可用才回退异步通道。
        writeFreezeDirect(uid, packageName, targets, freeze)?.let { direct ->
            if (direct.writes > 0) {
                if (freeze) {
                    frozenPackages.add(packageName)
                } else {
                    frozenPackages.remove(packageName)
                }
                packageLastCommittedBackground[packageName] = freeze
                packageLastCommitAt[packageName] = System.currentTimeMillis()
                logHookDebug("direct ${if (freeze) "冻结" else "解冻"} $packageName ${direct.reason}")
                return true
            }
        }
        val commandId = DaemonControlClient.nextVersion()
        val pending = PendingFreezeCommand(commandId, freeze)
        if (freeze && packagePendingFreezeCommands.putIfAbsent(packageName, pending) != null) return false
        if (!freeze) packagePendingFreezeCommands[packageName] = pending
        daemonCommandExecutor.execute {
            val response = HookDaemonBridge.freeze(context, packageName, uid, freeze, rule?.freezeProcesses.orEmpty(), commandId)
            onFreezeCommandResult(packageName, commandId, freeze, response?.optBoolean("success") == true,
                response?.optString("detail") ?: "daemon_unavailable", source)
        }
        return true
    }

    private fun resolveHookPollIntervalMs(): Long =
        (HookDaemonBridge.setting(FreezeListStore.runtimeGlobalHookPollIntervalKey()).toIntOrNull() ?: 30).coerceIn(10, 300) * 1000L


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

    private data class PendingFreezeCommand(
        val commandId: Long,
        val freeze: Boolean
    )

    private data class DirectTarget(val pid: Int, val startTime: Long)

    private data class DirectProcessIdentity(val processName: String, val startTime: Long)

    data class FreezeWriteResult(
        val writes: Int,
        val selectedProcessNames: List<String>,
        val reason: String
    )


    data class TrafficBytes(
        val rxBytes: Long,
        val txBytes: Long
    )


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


    private fun isPackageInstalled(packageName: String): Boolean {
        val context = appContext ?: return true
        return runCatching {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

}
