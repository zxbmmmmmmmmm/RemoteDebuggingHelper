package com.miui.debughelper.data

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.provider.Settings
import com.miui.debughelper.network.WifiMonitor
import com.miui.debughelper.root.AdbStatus
import com.miui.debughelper.root.RootAdbController
import com.miui.debughelper.service.DebugTileService
import com.miui.debughelper.service.WifiMonitorService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
class DebugRepository private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = SettingsStore(this.context)
    private val root = RootAdbController(this.context)
    private val wifi = WifiMonitor(this.context)
    private val mutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val monitoring = MutableStateFlow(false)
    private val saved = MutableStateFlow(SavedDebugState())
    private val mutableState = MutableStateFlow(DebugState())
    private val mutableSettings = MutableStateFlow(DebugSettings())
    val state: StateFlow<DebugState> = mutableState.asStateFlow()
    val settings: StateFlow<DebugSettings> = mutableSettings.asStateFlow()
    private val bootCount = Settings.Global.getInt(this.context.contentResolver, Settings.Global.BOOT_COUNT, 0)
    private var lastAutoAttempt: String? = null
    private var lastObservedRunning = false
    private var lastObservedConnection: String? = null
    private var pollUntil = 0L
    private var pendingEnableNotice = false
    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            scope.launch {
                ready.await()
                mutex.withLock { observeSystemStatus() }
                updateTile()
            }
        }
    }

    init {
        scope.launch {
            store.data.collect {
                saved.value = it
                mutableSettings.value = it.settings
                mutableState.update { current ->
                    current.copy(lastAction = it.lastAction, lastActionAt = it.lastActionAt, error = it.error)
                }
                ready.complete(Unit)
            }
        }
        scope.launch {
            wifi.state.collect { snapshot ->
                mutableState.update { it.copy(currentWifi = snapshot) }
            }
        }
        scope.launch {
            combine(saved.map { it.settings }.distinctUntilChanged(), wifi.state, monitoring) { _, _, _ -> Unit }
                .debounce(700)
                .collect {
                    ready.await()
                    mutex.withLock { reconcileAutomation() }
                }
        }
        this.context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(RootAdbController.WIRELESS_SETTING), false, settingsObserver,
        )
        scope.launch {
            while (isActive) {
                delay(2500)
                if (state.value.switchEnabled || SystemClock.elapsedRealtime() < pollUntil) {
                    ready.await()
                    mutex.withLock { observeSystemStatus() }
                }
            }
        }
    }

    /** Before explicit root authorization, reads only the public native switch and visible properties. */
    suspend fun refresh() {
        ready.await()
        wifi.refresh()
        mutex.withLock {
            observeSystemStatus()
        }
    }

    suspend fun requestRoot() {
        ready.await()
        mutex.withLock {
            mutableState.update { it.copy(busy = true) }
            try {
                if (authorizeRoot()) {
                    runCatching { root.readStatus() }.onSuccess(::publishStatus)
                    store.record(action = "Root 权限已获得")
                    lastAutoAttempt = null
                    reconcileAutomation()
                }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
        updateTile()
    }

    suspend fun setDebugging(enabled: Boolean) {
        ready.await()
        mutex.withLock {
            wifi.refresh()
            if (!enabled) {
                val token = connectionToken(wifi.state.value)
                store.setSuppressedConnection(token)
                saved.first { it.suppressedConnection == token }
            }
            changeDebugging(enabled, automatic = false)
        }
        updateTile()
    }

    suspend fun tryOpenWirelessSettings(): Boolean {
        if (mutableState.value.rootStatus != RootStatus.AVAILABLE) return false
        return root.openWirelessSettings()
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        ready.await()
        wifi.refresh()
        if (enabled) {
            val network = wifi.state.value
            if (!network.hasLocationPermission || !network.hasBackgroundLocationPermission || !network.locationEnabled) {
                reportError("自动开启需要精确位置权限、始终允许位置访问，并开启系统定位，以识别已信任的 Wi-Fi。")
                return
            }
            if (saved.value.settings.trustedSsids.isEmpty()) {
                reportError("请先将当前 Wi-Fi 添加为信任网络。")
                return
            }
            mutex.withLock {
                mutableState.update { it.copy(busy = true) }
                val granted = try { authorizeRoot() } finally { mutableState.update { it.copy(busy = false) } }
                if (!granted) return
            }
        }
        store.setAutoConnect(enabled)
        saved.first { it.settings.autoConnect == enabled }
        lastAutoAttempt = null
        store.record(action = if (enabled) "已开启信任 Wi-Fi 自动调试" else "已关闭自动调试")
        if (enabled) {
            runCatching { WifiMonitorService.start(context) }
                .onFailure { reportError("无法启动后台监测：${it.message.orEmpty()}。请返回应用后重试。") }
        } else {
            mutex.withLock { reconcileAutomation() }
            WifiMonitorService.stop(context)
        }
    }

    suspend fun trustCurrentWifi() {
        ready.await()
        wifi.refresh()
        val snapshot = wifi.state.value
        val ssid = snapshot.ssid
        if (!snapshot.isWifiConnected || !snapshot.identityAvailable || ssid == null) {
            reportError("无法识别当前 Wi-Fi。请授予精确位置权限，并开启系统定位后刷新。")
            return
        }
        store.trust(ssid)
        store.record(action = "已信任 Wi-Fi：$ssid")
    }

    suspend fun removeTrustedWifi(ssid: String) {
        ready.await()
        store.remove(ssid)
        store.record(action = "已移除信任网络：$ssid")
    }

    suspend fun clearError() { store.record() }

    /** Called only by the foreground service after startForeground succeeds. */
    internal fun setMonitoringActive(active: Boolean) {
        monitoring.value = active
        mutableState.update { it.copy(monitoringActive = active) }
    }

    internal suspend fun recordServiceError(message: String) { reportError(message) }

    internal suspend fun awaitSettings(): DebugSettings {
        ready.await()
        return store.data.first().settings
    }

    private suspend fun reconcileAutomation() {
        val currentSettings = saved.value.settings
        val network = wifi.state.value
        val token = connectionToken(network)
        if (saved.value.suppressedConnection != null && token != saved.value.suppressedConnection) {
            store.setSuppressedConnection(null)
            saved.first { it.suppressedConnection == null }
        }
        val eligible = monitoring.value && AutoConnectPolicy.shouldEnable(currentSettings, network)
        if (!eligible) {
            lastAutoAttempt = null
            if (saved.value.autoOwned) {
                // Ownership follows the native switch, never the dynamically assigned TLS port.
                changeDebugging(false, automatic = true)
                updateTile()
            }
            return
        }
        if (token != null && token == saved.value.suppressedConnection) return
        val attemptKey = "${network.connectionId}|${network.ssid}|${network.ipv4Address}"
        if (lastAutoAttempt == attemptKey) return
        lastAutoAttempt = attemptKey
        val status = runCatching { root.readStatus() }.getOrElse {
            reportError(it.message ?: "无法读取调试状态。")
            return
        }
        publishStatus(status)
        if (!status.switchEnabled && !status.tlsEnabled) changeDebugging(true, automatic = true)
        updateTile()
    }

    private suspend fun changeDebugging(enabled: Boolean, automatic: Boolean) {
        val networkBefore = wifi.state.value
        if (enabled && (!networkBefore.isWifiConnected || networkBefore.ipv4Address == null || networkBefore.isCaptivePortal)) {
            reportError("请连接可用的 Wi-Fi 后再开启远程调试。")
            return
        }
        mutableState.update { it.copy(busy = true) }
        try {
            if (!authorizeRoot()) return
            if (enabled && automatic && (!monitoring.value ||
                    !AutoConnectPolicy.shouldEnable(saved.value.settings, wifi.state.value))) return
            // Persist intent before enabling, so a restarted process can clean up its own session.
            if (enabled && automatic) updateOwnership(true)
            pollUntil = SystemClock.elapsedRealtime() + 30_000
            val result = root.setEnabled(enabled)
            result.onSuccess { status ->
                publishStatus(status)
                if (!enabled || !automatic) updateOwnership(false)
                if (!enabled || status.enabled) pendingEnableNotice = false
                if (enabled && (wifi.state.value.ipv4Address != networkBefore.ipv4Address ||
                        wifi.state.value.connectionId != networkBefore.connectionId ||
                        wifi.state.value.ssid != networkBefore.ssid || !wifi.state.value.isWifiConnected ||
                        (automatic && !AutoConnectPolicy.shouldEnable(saved.value.settings, wifi.state.value)))) {
                    // Root authorization can take seconds; never leave the listener open after a network change.
                    updateOwnership(true)
                    val rollback = root.setEnabled(false)
                    rollback.onSuccess(::publishStatus)
                    if (rollback.isSuccess) {
                        updateOwnership(false)
                        reportError("开启过程中 Wi-Fi 已发生变化，已撤销本次调试。")
                    } else {
                        refreshAfterFailedChange()
                        reportError("Wi-Fi 已发生变化，自动关闭失败，调试端口可能仍然开启，请手动关闭。")
                    }
                    return
                }
                store.record(action = when {
                    !enabled && automatic -> "已关闭应用自动请求的系统无线调试"
                    !enabled -> "已关闭系统无线调试"
                    status.enabled && automatic -> "已在信任 Wi-Fi 自动开启系统无线调试"
                    status.enabled -> "已开启系统无线调试"
                    status.requestedEnabled -> "已请求开启，等待系统无线调试启动"
                    else -> "已请求系统无线调试，请完成系统的 Wi-Fi 授权"
                })
                if (enabled && !status.enabled) {
                    pendingEnableNotice = true
                    reportError(if (status.requestedEnabled)
                        "系统开关已开启，TLS 连接端口尚未就绪；请稍候或打开系统无线调试设置检查。"
                    else "系统尚未开启无线调试。请完成“允许在此 Wi-Fi 网络上进行无线调试”确认，并在系统页面配对。")
                }
            }.onFailure {
                refreshAfterFailedChange()
                reportError(it.message ?: "切换远程调试失败。")
            }
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    private suspend fun refreshAfterFailedChange() {
        mutableState.update { it.copy(rootStatus = RootStatus.UNKNOWN) }
        runCatching { root.readStatus() }.onSuccess { status ->
            publishStatus(status)
            // Native ownership is retained until a disable is actually confirmed.
        }
    }

    private suspend fun authorizeRoot(): Boolean {
        if (mutableState.value.rootStatus == RootStatus.AVAILABLE) return true
        mutableState.update { it.copy(rootStatus = RootStatus.CHECKING) }
        val result = root.requestRoot()
        mutableState.update { it.copy(rootStatus = if (result.isSuccess) RootStatus.AVAILABLE else RootStatus.DENIED) }
        result.exceptionOrNull()?.let { reportError(it.message ?: "未获得 Root 权限。") }
        return result.isSuccess
    }

    private fun publishStatus(status: AdbStatus) {
        val previous = mutableState.value
        lastObservedRunning = status.enabled
        lastObservedConnection = connectionToken(wifi.state.value)
        mutableState.update {
            it.copy(requestedEnabled = status.requestedEnabled, debuggingEnabled = status.enabled,
                activePort = status.port.takeIf { status.enabled })
        }
        val current = mutableState.value
        if (previous.requestedEnabled != current.requestedEnabled ||
            previous.debuggingEnabled != current.debuggingEnabled || previous.activePort != current.activePort) {
            updateTile()
        }
    }

    private suspend fun observeSystemStatus() {
        runCatching { root.readStatus() }.onSuccess { status ->
            val token = connectionToken(wifi.state.value)
            val externallyDisabled = lastObservedRunning && !status.switchEnabled &&
                !mutableState.value.busy && token != null && token == lastObservedConnection
            publishStatus(status)
            if (status.enabled && pendingEnableNotice) {
                pendingEnableNotice = false
                store.record(action = "系统无线调试已开启")
            }
            if (externallyDisabled) {
                store.setSuppressedConnection(token)
                updateOwnership(false)
            }
        }.onFailure {
            mutableState.update { current -> current.copy(rootStatus = RootStatus.UNKNOWN) }
            reportError(it.message ?: "无法读取系统无线调试状态。")
        }
    }

    private fun connectionToken(network: WifiSnapshot): String? =
        network.connectionId?.let { "$bootCount:$it" }

    private suspend fun updateOwnership(owned: Boolean) {
        store.setOwnership(owned)
        saved.first { it.autoOwned == owned }
    }

    private suspend fun reportError(message: String) {
        mutableState.update { it.copy(error = message) }
        store.record(error = message)
    }

    private fun updateTile() {
        runCatching { TileService.requestListeningState(context, ComponentName(context, DebugTileService::class.java)) }
    }

    companion object {
        // The singleton retains only applicationContext and lives for the app process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: DebugRepository? = null
        fun get(context: Context): DebugRepository = instance ?: synchronized(this) {
            instance ?: DebugRepository(context).also { instance = it }
        }
    }
}
