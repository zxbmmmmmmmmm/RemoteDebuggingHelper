package com.miui.debughelper.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.miui.debughelper.data.DebugRepository
import com.miui.debughelper.data.RootStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DebugTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { DebugRepository.get(this) }
    private var listening: Job? = null
    private var click: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = scope.launch {
            repository.refresh()
            repository.state.collect { render() }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (click?.isActive == true) return
        unlockAndRun {
            click = scope.launch {
                repository.refresh()
                val state = repository.state.value
                if (!state.switchEnabled && (!state.currentWifi.isWifiConnected || state.currentWifi.ipv4Address == null)) {
                    openSetup()
                } else {
                    repository.setDebugging(!state.switchEnabled)
                    render()
                    if (repository.state.value.error != null) openSetup()
                }
            }
        }
    }

    private fun render() {
        val state = repository.state.value
        qsTile?.apply {
            this.state = when {
                state.busy -> Tile.STATE_UNAVAILABLE
                state.switchEnabled -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            label = "无线调试"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                subtitle = when {
                    state.busy -> "正在切换…"
                    state.debuggingEnabled && state.activePort != null -> "${state.currentWifi.ipv4Address ?: "Wi-Fi"}:${state.activePort}"
                    state.starting -> "系统开关已开启，等待端口"
                    state.rootStatus != RootStatus.AVAILABLE -> "点击完成 Root 授权"
                    !state.currentWifi.isWifiConnected -> "请连接 Wi-Fi"
                    else -> "已关闭"
                }
            }
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSetup() {
        val intent = Intent().setClassName(packageName, "$packageName.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
