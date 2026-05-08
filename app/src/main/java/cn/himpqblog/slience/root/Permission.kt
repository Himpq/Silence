package cn.himpqblog.slience.root

import android.os.Handler
import android.os.Looper
import com.topjohnwu.superuser.Shell
import java.io.File

data class RootGrantResult(
    val granted: Boolean,
    val summary: String,
    val details: List<String> = emptyList()
)

object Permission {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSuBinaryPresent(): Boolean {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/debug_ramdisk/su"
        )
        return candidates.any { path -> File(path).exists() }
    }

    fun requestRoot(callback: (RootGrantResult) -> Unit) {
        Thread {
            val result = runCatching {
                Shell.getShell()
                val execResult = Shell.cmd("id").exec()
                val stdout = execResult.out
                val granted = execResult.isSuccess && stdout.any { line -> "uid=0" in line }

                if (!granted) {
                    RootGrantResult(
                        granted = false,
                        summary = "Root not granted",
                        details = listOf(
                            "libsu tried to create a su shell.",
                            "If the device is rooted, check Magisk / KernelSU / APatch authorization."
                        )
                    )
                } else {
                    RootGrantResult(
                        granted = true,
                        summary = "Root granted",
                        details = buildList {
                            add("exitCode=${execResult.code}")
                            addAll(stdout)
                        }
                    )
                }
            }.getOrElse { throwable ->
                RootGrantResult(
                    granted = false,
                    summary = "Root request failed",
                    details = listOf(throwable.message ?: throwable.javaClass.name)
                )
            }

            mainHandler.post {
                callback(result)
            }
        }.start()
    }
}
