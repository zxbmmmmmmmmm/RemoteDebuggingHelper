package com.miui.debughelper.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miui.debughelper.DebugHelperApplication
import com.miui.debughelper.service.WifiMonitorService
import kotlinx.coroutines.launch

class DebugViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as DebugHelperApplication).repository
    val state = repository.state
    val settings = repository.settings

    fun refresh() { viewModelScope.launch { repository.refresh() } }
    fun requestRoot() { viewModelScope.launch { repository.requestRoot() } }
    fun setDebugging(enabled: Boolean) { viewModelScope.launch { repository.setDebugging(enabled) } }
    fun setAutoConnect(enabled: Boolean) { viewModelScope.launch { repository.setAutoConnect(enabled) } }
    fun trustCurrentWifi() { viewModelScope.launch { repository.trustCurrentWifi() } }
    fun removeTrustedWifi(ssid: String) { viewModelScope.launch { repository.removeTrustedWifi(ssid) } }
    suspend fun tryOpenWirelessSettings(): Boolean = repository.tryOpenWirelessSettings()
    fun resumeMonitoring() {
        viewModelScope.launch {
            runCatching { WifiMonitorService.start(getApplication()) }
                .onFailure { repository.recordServiceError("无法恢复后台监听，请在应用设置中检查后台运行限制后重试。") }
        }
    }
}
