package com.miui.debughelper

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.miui.debughelper.service.DebugTileService
import com.miui.debughelper.ui.DebugScreen
import com.miui.debughelper.ui.DebugViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: DebugViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DebugScreen(viewModel = viewModel, onAddTile = ::addTile, onOpenWirelessSettings = ::openWirelessSettings)
        }
    }

    private fun openWirelessSettings() {
        lifecycleScope.launch {
            if (viewModel.tryOpenWirelessSettings()) return@launch
            val fragmentArguments = Bundle().apply {
                putString(":settings:fragment_args_key", "toggle_adb_wireless")
            }
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args", fragmentArguments)
            try {
                startActivity(intent)
                Toast.makeText(this@MainActivity, "请打开“无线调试”，选择“使用配对码配对设备”", Toast.LENGTH_LONG).show()
            } catch (_: RuntimeException) {
                Toast.makeText(this@MainActivity, "请在系统设置的开发者选项中打开“无线调试”", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "下拉控制中心，点击编辑，将“无线调试”拖入快捷开关。", Toast.LENGTH_LONG).show()
            return
        }
        try {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, DebugTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_debug),
                mainExecutor,
            ) { result ->
                val message = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "已添加到控制中心"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "快捷开关已在控制中心"
                    else -> "可下拉控制中心，进入编辑，手动添加“无线调试”"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } catch (_: RuntimeException) {
            Toast.makeText(this, "请在控制中心的编辑页面手动添加“无线调试”", Toast.LENGTH_LONG).show()
        }
    }
}
