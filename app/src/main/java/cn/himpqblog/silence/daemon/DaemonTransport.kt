package cn.himpqblog.silence.daemon

import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Shared by the App and system_server. No root shell or alternate transport. */
object DaemonTransport {
    private const val MAX_REQUEST_BYTES = 512 * 1024
    private const val MAX_RESPONSE_BYTES = 1024 * 1024

    fun port(appUid: Int): Int = 20000 + appUid % 30000

    fun isEndpointAbsent(appUid: Int): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        return try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port(appUid)), 250) }
            false
        } catch (error: Exception) {
            val refused = generateSequence(error as Throwable) { it.cause }
                .filterIsInstance<android.system.ErrnoException>()
                .any { it.errno == android.system.OsConstants.ECONNREFUSED }
            Log.i("Silence_Daemon", "endpoint probe absent=$refused error=${error.javaClass.simpleName}:${error.message}")
            refused
        }
    }

    fun request(appUid: Int, command: String, timeoutMs: Int = 1500, prelaunch: Boolean = false): JSONObject? {
        require(appUid >= 10000)
        val name = if (command.startsWith("{")) runCatching { JSONObject(command).optString("command") }.getOrDefault("INVALID") else command.substringBefore(' ')
        if (!prelaunch && Looper.myLooper() == Looper.getMainLooper()) {
            Log.e("Silence_Daemon", "request rejected command=$name reason=main_thread")
            return null
        }
        val start = SystemClock.elapsedRealtime()
        val cpuStart = android.os.Debug.threadCpuTimeNanos()
        val previousPolicy = if (prelaunch) StrictMode.getThreadPolicy() else null
        if (previousPolicy != null) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(previousPolicy).permitNetwork().build())
        return try {
            val bytes = (command + "\n").toByteArray(StandardCharsets.UTF_8)
            require(bytes.size <= MAX_REQUEST_BYTES) { "request_too_large" }
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress("127.0.0.1", port(appUid)), timeoutMs)
                socket.soTimeout = (timeoutMs - (SystemClock.elapsedRealtime() - start).toInt()).coerceAtLeast(1)
                socket.getOutputStream().write(bytes)
                val input = socket.getInputStream()
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val remaining = timeoutMs - (SystemClock.elapsedRealtime() - start).toInt()
                    if (remaining <= 0) throw java.net.SocketTimeoutException("request_deadline")
                    socket.soTimeout = remaining
                    val count = input.read(buffer)
                    if (count < 0) throw java.io.EOFException("response_missing")
                    val newline = (0 until count).firstOrNull { buffer[it] == 10.toByte() }
                    output.write(buffer, 0, newline ?: count)
                    require(output.size() <= MAX_RESPONSE_BYTES) { "response_too_large" }
                    if (newline != null) return@use JSONObject(output.toString("UTF-8"))
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } catch (error: Exception) {
            Log.w("Silence_Daemon", "request failed command=$name uid=$appUid wallMs=${SystemClock.elapsedRealtime() - start} error=${error.javaClass.simpleName}:${error.message}")
            null
        } finally {
            if (previousPolicy != null) StrictMode.setThreadPolicy(previousPolicy)
            val elapsed = SystemClock.elapsedRealtime() - start
            if (elapsed >= 100) Log.i("Silence_Daemon", "slow request command=$name wallMs=$elapsed cpuUs=${(android.os.Debug.threadCpuTimeNanos() - cpuStart) / 1000}")
        }
    }
}
