package com.miui.debughelper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.miui.debughelper.R
import com.miui.debughelper.data.DebugRepository
import com.miui.debughelper.data.DebugState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class WifiMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DebugRepository
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        repository = DebugRepository.get(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Wi-Fi 自动调试", NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(repository.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else 0,
            )
            foregroundStarted = true
            repository.setMonitoringActive(true)
        } catch (exception: Exception) {
            scope.launch {
                repository.recordServiceError("后台监测启动失败：${exception.message.orEmpty()}。请打开应用重试。")
                stopSelf()
            }
            return
        }
        scope.launch {
            repository.state.map { listOf(it.debuggingEnabled, it.requestedEnabled, it.currentWifi.ssid, it.error) }
                .distinctUntilChanged().collect {
                    manager.notify(NOTIFICATION_ID, notification(repository.state.value))
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) return START_NOT_STICKY
        scope.launch {
            if (intent?.action == ACTION_DISABLE) {
                repository.setAutoConnect(false)
            } else if (!repository.awaitSettings().autoConnect) {
                stopSelf()
            } else repository.refresh()
        }
        return START_STICKY
    }

    private fun notification(state: DebugState): Notification {
        val openApp = Intent().setClassName(packageName, "$packageName.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(this, 1, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, WifiMonitorService::class.java).setAction(ACTION_DISABLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            state.error != null -> "监测需要检查，点击查看详情"
            state.debuggingEnabled -> "系统无线调试已开启 · ${state.currentWifi.ssid ?: "Wi-Fi"}"
            state.starting -> "系统无线调试正在启动"
            state.currentWifi.ssid != null -> "正在监测 ${state.currentWifi.ssid}"
            else -> "等待连接已信任的 Wi-Fi"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_debug)
            .setContentTitle("RemoteDebuggingHelper")
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_debug, "关闭自动调试", stopIntent)
            .build()
    }

    override fun onDestroy() {
        if (::repository.isInitialized) repository.setMonitoringActive(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wifi_debug_monitor"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_DISABLE = "com.miui.debughelper.DISABLE_AUTOMATION"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WifiMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WifiMonitorService::class.java))
        }
    }
}
