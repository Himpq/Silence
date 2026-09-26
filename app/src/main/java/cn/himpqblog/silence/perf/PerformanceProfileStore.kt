package cn.himpqblog.silence.perf

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

data class PerformanceProfileSummary(
    val id: String,
    val name: String,
    val description: String,
    val revision: Long
)

/**
 * 性能调控配置文件的唯一存储入口。
 *
 * profile 目前只保存策略，不执行任何 CPU/进程调控。这样先把配置边界和切换链路固定下来，
 * 后续 daemon 接入真正调控时可以直接读取同一份版本化文件。
 */
object PerformanceProfileStore {

    const val SCHEMA_VERSION = 1
    private const val PROFILE_DIRECTORY = "performance_profiles"
    private const val ACTIVE_PROFILE_FILE = "active_profile"
    private const val DEFAULT_PROFILE_ID = "default"
    private const val PROFILE_EXTENSION = ".json"

    @Synchronized
    fun listProfiles(context: Context): List<PerformanceProfileSummary> {
        ensureInitialized(context.applicationContext)
        return profileDirectory(context).listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(PROFILE_EXTENSION) }
            .mapNotNull { file -> readSummary(file) }
            .sortedWith(compareBy<PerformanceProfileSummary> { it.id != DEFAULT_PROFILE_ID }.thenBy { it.name })
    }

    @Synchronized
    fun activeProfile(context: Context): PerformanceProfileSummary {
        ensureInitialized(context.applicationContext)
        val id = activeProfileId(context)
        return readSummary(profileFile(context, id))
            ?: readSummary(profileFile(context, DEFAULT_PROFILE_ID))
            ?: PerformanceProfileSummary(DEFAULT_PROFILE_ID, "默认配置", "从旧配置迁移", 0L)
    }

    @Synchronized
    fun activeProfileId(context: Context): String {
        ensureInitialized(context.applicationContext)
        val activeId = activeProfilePointer(context).readText(StandardCharsets.UTF_8).trim()
        return if (isSafeProfileId(activeId) && profileFile(context, activeId).isFile) {
            activeId
        } else {
            DEFAULT_PROFILE_ID
        }
    }

    @Synchronized
    fun activeProfilePath(context: Context): String {
        return profileFile(context, activeProfileId(context)).absolutePath
    }

    @Synchronized
    fun readActiveRoot(context: Context): JSONObject {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        return readProfileRoot(appContext, activeProfileId(appContext))
    }

    @Synchronized
    fun writeActiveRoot(context: Context, root: JSONObject) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val id = activeProfileId(appContext)
        val normalized = normalizeRoot(root, id)
        normalized.put("revision", System.currentTimeMillis())
        atomicWrite(profileFile(appContext, id), normalized.toString(2))
    }

    @Synchronized
    fun setActiveProfile(context: Context, profileId: String): Boolean {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        if (!isSafeProfileId(profileId) || !profileFile(appContext, profileId).isFile) {
            return false
        }
        val previous = activeProfileId(appContext)
        if (previous == profileId) {
            return true
        }
        atomicWrite(activeProfilePointer(appContext), profileId)
        PerformanceLogStore.recordEvent(
            context = appContext,
            eventName = "performance_profile_changed",
            oldValue = previous,
            newValue = profileId
        )
        PerformanceExternalModeWriter.resetCache()
        PerformanceExternalModeWriter.syncCurrentMode(appContext)
        PerformanceRecordingConfigStore.sync(appContext)
        return true
    }

    @Synchronized
    fun duplicateActiveProfile(context: Context, displayName: String): PerformanceProfileSummary? {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        val normalizedName = displayName.trim().take(48)
        if (normalizedName.isEmpty()) {
            return null
        }
        val id = "profile-${System.currentTimeMillis()}"
        val root = JSONObject(readActiveRoot(appContext).toString())
        root.put("id", id)
        root.put("name", normalizedName)
        root.put("description", "由当前配置复制")
        root.put("revision", System.currentTimeMillis())
        atomicWrite(profileFile(appContext, id), normalizeRoot(root, id).toString(2))
        return readSummary(profileFile(appContext, id))
    }

    fun profileDirectory(context: Context): File {
        val deviceContext = context.applicationContext.createDeviceProtectedStorageContext()
        return File(deviceContext.filesDir, PROFILE_DIRECTORY)
    }

    private fun ensureInitialized(context: Context) {
        val directory = profileDirectory(context)
        directory.mkdirs()
        val defaultFile = profileFile(context, DEFAULT_PROFILE_ID)
        if (!defaultFile.exists()) {
            val legacyConfig = readJson(File(context.createDeviceProtectedStorageContext().filesDir, "Perf.json"))
            val legacyPreferences = readJson(File(context.createDeviceProtectedStorageContext().filesDir, "Pref_mode.json"))
            val root = JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("id", DEFAULT_PROFILE_ID)
                .put("name", "默认配置")
                .put("description", "从旧配置迁移")
                .put("default", legacyConfig.optInt("default", PerformanceMode.POWER_SAVE.code))
                .put("packages", legacyConfig.optJSONObject("packages") ?: JSONObject())
                .put("modes", legacyPreferences)
                .put("revision", System.currentTimeMillis())
            atomicWrite(defaultFile, root.toString(2))
        }
        val pointer = activeProfilePointer(context)
        val currentId = pointer.takeIf { it.isFile }?.readText(StandardCharsets.UTF_8)?.trim().orEmpty()
        if (!isSafeProfileId(currentId) || !profileFile(context, currentId).isFile) {
            atomicWrite(pointer, DEFAULT_PROFILE_ID)
        }
    }

    private fun readProfileRoot(context: Context, profileId: String): JSONObject {
        val root = readJson(profileFile(context, profileId))
        return normalizeRoot(root, profileId)
    }

    private fun readSummary(file: File): PerformanceProfileSummary? {
        if (!file.isFile) return null
        val fallbackId = file.name.removeSuffix(PROFILE_EXTENSION)
        if (!isSafeProfileId(fallbackId)) return null
        val root = readJson(file)
        val id = root.optString("id", fallbackId).trim()
        if (!isSafeProfileId(id)) return null
        return PerformanceProfileSummary(
            id = id,
            name = root.optString("name", id).trim().ifEmpty { id },
            description = root.optString("description", "仅保存配置，暂未执行调控").trim(),
            revision = root.optLong("revision", file.lastModified())
        )
    }

    private fun normalizeRoot(root: JSONObject, profileId: String): JSONObject {
        root.put("schemaVersion", root.optInt("schemaVersion", SCHEMA_VERSION))
        root.put("id", profileId)
        root.put("name", root.optString("name", profileId).trim().ifEmpty { profileId })
        root.put("description", root.optString("description", "仅保存配置，暂未执行调控"))
        root.put("default", PerformanceMode.fromCode(root.optInt("default", 0)).code)
        root.put("packages", root.optJSONObject("packages") ?: JSONObject())
        root.put("modes", root.optJSONObject("modes") ?: JSONObject())
        return root
    }

    private fun readJson(file: File): JSONObject {
        if (!file.isFile) return JSONObject()
        return runCatching {
            JSONObject(file.readText(StandardCharsets.UTF_8).trim())
        }.getOrElse { JSONObject() }
    }

    private fun profileFile(context: Context, profileId: String): File {
        return File(profileDirectory(context), "$profileId$PROFILE_EXTENSION")
    }

    private fun activeProfilePointer(context: Context): File {
        return File(profileDirectory(context), ACTIVE_PROFILE_FILE)
    }

    private fun isSafeProfileId(value: String): Boolean {
        return value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))
    }

    private fun atomicWrite(file: File, content: String) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(content, StandardCharsets.UTF_8)
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("无法替换配置文件: ${file.absolutePath}")
        }
        if (!temporary.renameTo(file)) {
            throw IllegalStateException("无法提交配置文件: ${file.absolutePath}")
        }
    }
}
