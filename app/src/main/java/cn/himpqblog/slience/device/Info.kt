package cn.himpqblog.slience.device

import android.os.Build
import java.io.File
import java.util.Locale

data class TombstoneSupport(
    val level: String,
    val summary: String,
    val details: String
)

data class SystemInfo(
    val kernelVersion: String,
    val romName: String,
    val tombstone: TombstoneSupport
)

object Info {

    fun collect(): SystemInfo {
        return SystemInfo(
            kernelVersion = readKernelVersion(),
            romName = detectRomName(),
            tombstone = detectTombstoneSupport()
        )
    }

    private fun readKernelVersion(): String {
        return readFirstLine("/proc/version")
            ?.substringBefore('\u0000')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: (System.getProperty("os.version") ?: "Unknown")
    }

    private fun detectRomName(): String {
        val hyperOs = readProp("ro.mi.os.version.name")
        val miui = readProp("ro.miui.ui.version.name")
        val colorOs = readProp("ro.build.version.opporom")
        val onePlus = readProp("ro.build.version.oneplusrom")
        val oxygen = readProp("ro.oxygen.version")
        val realme = readProp("ro.build.version.realmeui")
        val magic = readProp("ro.build.version.magic")
        val emui = readProp("ro.build.version.emui")
        val vivoName = readProp("ro.vivo.os.name")
        val vivoVersion = readProp("ro.vivo.os.version")
        val samsung = readProp("ro.build.version.oneui")
        val flyme = readProp("ro.build.display.id")

        return when {
            hyperOs.isNotBlank() -> joinName("HyperOS", hyperOs)
            miui.isNotBlank() -> joinName("MIUI", miui)
            colorOs.isNotBlank() -> joinName("ColorOS", colorOs)
            onePlus.isNotBlank() -> joinName("OxygenOS", onePlus)
            oxygen.isNotBlank() -> joinName("OxygenOS", oxygen)
            realme.isNotBlank() -> joinName("realme UI", realme)
            magic.isNotBlank() -> joinName("MagicOS", magic)
            emui.isNotBlank() -> joinName("EMUI", emui)
            vivoName.isNotBlank() && vivoVersion.isNotBlank() -> joinName(vivoName, vivoVersion)
            vivoVersion.isNotBlank() -> joinName("OriginOS", vivoVersion)
            samsung.isNotBlank() -> joinName("One UI", samsung)
            flyme.contains("flyme", ignoreCase = true) -> flyme
            else -> {
                val manufacturer = Build.MANUFACTURER.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
                "$manufacturer Android ${Build.VERSION.RELEASE ?: "Unknown"}"
            }
        }
    }

    private fun detectTombstoneSupport(): TombstoneSupport {
        val hasTombstoned = existsAny(
            "/system/bin/tombstoned",
            "/apex/com.android.runtime/bin/tombstoned"
        )
        val hasCrashDump = existsAny(
            "/system/bin/crash_dump64",
            "/system/bin/crash_dump32",
            "/system/bin/crash_dump",
            "/apex/com.android.runtime/bin/crash_dump64",
            "/apex/com.android.runtime/bin/crash_dump32",
            "/apex/com.android.runtime/bin/crash_dump"
        )
        val hasTombstonedRc = existsAny(
            "/system/etc/init/tombstoned.rc",
            "/system_ext/etc/init/tombstoned.rc",
            "/product/etc/init/tombstoned.rc"
        )
        val hasLegacyDebuggerdRc = existsAny(
            "/system/etc/init/debuggerd.rc",
            "/system_ext/etc/init/debuggerd.rc"
        )
        val hasTombstoneDir = exists("/data/tombstones")
        val hasProtoDir = exists("/data/tombstones/proto")
        val sdkInt = Build.VERSION.SDK_INT

        val v2Likely = hasTombstoned || hasCrashDump || hasTombstonedRc || hasProtoDir || (sdkInt >= 29 && hasTombstoneDir)

        return when {
            v2Likely -> TombstoneSupport(
                level = "v2",
                summary = "v2",
                details = "Detected modern tombstoned pipeline (sdk=$sdkInt)."
            )

            hasTombstoneDir || hasLegacyDebuggerdRc -> TombstoneSupport(
                level = "v1",
                summary = "v1",
                details = "Detected legacy debuggerd/tombstone markers."
            )

            else -> TombstoneSupport(
                level = "kill",
                summary = "kill",
                details = "No recognizable tombstone pipeline found."
            )
        }
    }

    private fun readProp(key: String): String {
        val fromCommand = runCatching {
            val process = ProcessBuilder("/system/bin/getprop", key)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText().trim() }
        }.getOrDefault("")

        if (fromCommand.isNotBlank()) {
            return fromCommand
        }

        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, "") as String
        }.getOrDefault("").trim()
    }

    private fun readFirstLine(path: String): String? {
        return runCatching {
            File(path).bufferedReader().use { it.readLine() }
        }.getOrNull()
    }

    private fun exists(path: String): Boolean = File(path).exists()
    private fun existsAny(vararg paths: String): Boolean = paths.any { exists(it) }

    private fun joinName(name: String, version: String): String {
        val clean = version.trim()
        return if (clean.isEmpty()) name else "$name $clean"
    }
}
