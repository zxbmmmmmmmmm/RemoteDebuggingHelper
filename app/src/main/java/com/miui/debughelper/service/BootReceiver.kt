package com.miui.debughelper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.miui.debughelper.data.DebugRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Credential-encrypted preferences become available after normal BOOT_COMPLETED. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = DebugRepository.get(context)
                if (repository.awaitSettings().autoConnect) {
                    runCatching { WifiMonitorService.start(context) }.onFailure {
                        repository.recordServiceError("开机后台监测未启动，请打开应用恢复监测。")
                    }
                }
            } finally { pending.finish() }
        }
    }
}
