package com.miui.debughelper.root

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

internal data class AdbStatus(
    val requestedEnabled: Boolean,
    val enabled: Boolean,
    val port: Int?,
    val tlsEnabled: Boolean,
    val listenerPresent: Boolean,
) {
    val switchEnabled: Boolean get() = requestedEnabled || enabled
}

/** Uses Android's own Wireless debugging switch. Pairing and Wi-Fi consent stay native. */
internal class RootAdbController(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    @Volatile private var rootAuthorized = false

    suspend fun requestRoot(): Result<Unit> = runCatching {
        rootAuthorized = false
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "系统无线调试需要 Android 11 或更新版本。" }
        val result = command(listOf("su", "-c", "id -u"), 30)
        check(result.exitCode == 0 && result.output.trim() == "0") {
            "未获得 Root 权限，请在 Root 管理器中授权后重试。"
        }
        rootAuthorized = true
    }

    /** Read-only. Uses su only after an explicit successful root authorization in this process. */
    suspend fun readStatus(): AdbStatus = withContext(Dispatchers.IO) {
        val requested = Settings.Global.getInt(resolver, WIRELESS_SETTING, 0) > 0
        val arguments = if (rootAuthorized) listOf("su", "-c", ROOT_GUARD + STATUS_COMMAND)
            else listOf("/system/bin/sh", "-c", STATUS_COMMAND)
        val result = try {
            command(arguments, 4)
        } catch (exception: Exception) {
            rootAuthorized = false
            throw exception
        }
        if (result.exitCode != 0) {
            rootAuthorized = false
            error("无法读取系统无线调试状态，请检查 Root 授权。")
        }
        val properties = result.output.lines()
        val tlsEnabled = properties.getOrNull(0)?.trim() == "1"
        val port = WirelessStatusParser.parsePort(properties.getOrNull(1))
        val daemonRunning = properties.getOrNull(2)?.trim() == "running"
        // Native adbd publishes this TLS port. Never send plaintext legacy ADB packets to it.
        val listener = port != null && isTcpListening(port)
        AdbStatus(
            requestedEnabled = requested,
            enabled = WirelessStatusParser.isRunning(tlsEnabled, port, daemonRunning, listener),
            port = port,
            tlsEnabled = tlsEnabled,
            listenerPresent = listener,
        )
    }

    suspend fun setEnabled(enabled: Boolean): Result<AdbStatus> = runCatching {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { "系统无线调试需要 Android 11 或更新版本。" }
        if (enabled) {
            check(Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) > 0) {
                "请先在系统设置中开启开发者选项，再使用系统无线调试。"
            }
        }
        val before = readStatus()
        val target = if (enabled) 1 else 0
        val result = command(
            listOf("su", "-c", ROOT_GUARD + "settings put global $WIRELESS_SETTING $target"), 30,
        )
        check(result.exitCode == 0 && !result.output.contains("Error", ignoreCase = true) &&
            !result.output.contains("Exception", ignoreCase = true)) {
            "切换系统无线调试失败，请检查 Root 授权和 ROM 限制。"
        }
        rootAuthorized = true
        var latest = before
        repeat(if (enabled) 12 else 24) {
            delay(250)
            latest = readStatus()
            if (enabled && latest.enabled) return@runCatching latest
            if (!enabled && !latest.requestedEnabled && !latest.tlsEnabled && !latest.listenerPresent &&
                (before.port == null || !isTcpListening(before.port))) return@runCatching latest
        }
        if (enabled) {
            // An untrusted Wi-Fi triggers native consent and can immediately reset the switch.
            // The settings write is a request, not proof of a running TLS server.
            latest
        } else error("系统无线调试尚未完全关闭，请在系统设置中检查。")
    }

    suspend fun openWirelessSettings(): Boolean = runCatching {
        val result = command(listOf("su", "-c", ROOT_GUARD + OPEN_SETTINGS_COMMAND), 8)
        result.exitCode == 0 && !result.output.contains("Error", ignoreCase = true) &&
            !result.output.contains("Exception", ignoreCase = true)
    }.getOrDefault(false)

    private suspend fun isTcpListening(port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 350) }
            true
        }.getOrDefault(false)
    }

    private suspend fun command(arguments: List<String>, timeoutSeconds: Long): CommandResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val process = try {
                    ProcessBuilder(arguments).redirectErrorStream(true).start()
                } catch (exception: Exception) {
                    throw IllegalStateException("无法执行系统命令，请确认设备已 Root 且安装了 su。", exception)
                }
                val output = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader().use { reader ->
                        val captured = StringBuilder()
                        val buffer = CharArray(1024)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (captured.length < 16_384) {
                                captured.append(buffer, 0, minOf(count, 16_384 - captured.length))
                            }
                        }
                        captured.toString()
                    }
                }
                try {
                    check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                        "等待系统命令超时，请检查 Root 授权弹窗。"
                    }
                    CommandResult(process.exitValue(), output.await())
                } finally {
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                    process.inputStream.close()
                    output.cancel()
                }
            }
        }

    private data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        const val WIRELESS_SETTING = "adb_wifi_enabled"
        private const val ROOT_GUARD = "[ \"$(id -u)\" = \"0\" ] || exit 13\n"
        private const val STATUS_COMMAND = "getprop persist.adb.tls_server.enable\n" +
            "getprop service.adb.tls.port\ngetprop init.svc.adbd"
        private const val OPEN_SETTINGS_COMMAND = "am start --user current -n com.android.settings/.SubSettings " +
            "--es :settings:show_fragment com.android.settings.development.WirelessDebuggingFragment"
    }
}

/** Isolated validation keeps stale and malformed system values testable on the JVM. */
internal object WirelessStatusParser {
    fun parsePort(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    fun isRunning(tlsEnabled: Boolean, port: Int?, daemonRunning: Boolean, listenerPresent: Boolean): Boolean =
        tlsEnabled && port != null && port in 1..65535 && daemonRunning && listenerPresent
}

