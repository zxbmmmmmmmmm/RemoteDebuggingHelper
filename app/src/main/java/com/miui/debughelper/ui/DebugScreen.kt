package com.miui.debughelper.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miui.debughelper.data.DebugState
import com.miui.debughelper.data.RootStatus
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

private data class Palette(
    val background: Color, val text: Color, val muted: Color, val blue: Color,
    val blueSurface: Color, val line: Color, val terminal: Color, val danger: Color,
)

private val LocalPalette = staticCompositionLocalOf {
    Palette(Color(0xFFF7F7FA), Color(0xFF181A20), Color(0xFF7D818D), Color(0xFF3478F6),
        Color(0xFFEDF3FF), Color(0xFFEBEDF2), Color(0xFF182237), Color(0xFFCF4C55))
}

private enum class Sheet { HELP, TRUST, AUTOMATION }

@Composable
fun DebugScreen(viewModel: DebugViewModel, onAddTile: () -> Unit, onOpenWirelessSettings: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) Palette(
        Color(0xFF101114), Color(0xFFF1F2F6), Color(0xFFA0A5B1), Color(0xFF82ABFF),
        Color(0xFF1C2942), Color(0xFF303239), Color(0xFF0B101C), Color(0xFFFF9299),
    ) else LocalPalette.current
    MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
        CompositionLocalProvider(LocalPalette provides palette) {
            DebugContent(viewModel, onAddTile, onOpenWirelessSettings)
        }
    }
}

@Composable
private fun DebugContent(viewModel: DebugViewModel, onAddTile: () -> Unit, onOpenWirelessSettings: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalPalette.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    val owner = LocalLifecycleOwner.current
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    val requestLocation = {
        locationPermission.launch(arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ))
    }
    val requestBackground = { context.openAppSettings() }
    val readyForAuto = state.rootStatus == RootStatus.AVAILABLE &&
        settings.trustedSsids.isNotEmpty() && state.currentWifi.hasLocationPermission &&
        state.currentWifi.hasBackgroundLocationPermission && state.currentWifi.locationEnabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = "远程调试助手",
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                        Icon(Icons.Outlined.Refresh, "刷新状态", tint = colors.muted)
                    }
                    IconButton(onClick = { sheet = Sheet.HELP }) {
                        Icon(Icons.AutoMirrored.Outlined.HelpOutline, "使用说明", tint = colors.muted)
                    }
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().background(colors.background).padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 620.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("让连接更简单", fontSize = 14.sp, color = colors.muted, modifier = Modifier.weight(1f))
                        StatusPill(
                            if (state.rootStatus == RootStatus.AVAILABLE) "ROOT 已授权" else "需要 ROOT",
                            if (state.rootStatus == RootStatus.AVAILABLE) colors.blue else colors.muted,
                        )
                    }
                }
                item {
                    ConnectionCard(state, onToggle = viewModel::setDebugging, onOpenWirelessSettings = onOpenWirelessSettings)
                }
                if (state.rootStatus != RootStatus.AVAILABLE) {
                    item {
                        Panel {
                            FeatureRow(Icons.Outlined.AdminPanelSettings, "授予 Root 权限", "通过超级用户权限管理无线 ADB")
                            Spacer(Modifier.height(16.dp))
                            Text(
                                if (state.rootStatus == RootStatus.DENIED) "授权被拒绝或设备未 Root。请在 Root 管理器中允许本应用，然后重试。"
                                else "首次使用，请在 Magisk、KernelSU 等 Root 管理器的弹窗中允许授权。",
                                color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp,
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(
                                text = if (state.busy) "正在检查…" else "申请 Root 授权",
                                onClick = viewModel::requestRoot,
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
                if (state.error != null) {
                    item {
                        Panel {
                            FeatureRow(Icons.Outlined.ErrorOutline, "操作未完成", state.error.orEmpty(), colors.danger)
                            TextButton("重新检查", viewModel::refresh, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
                item { SectionLabel("自动化", "熟悉的网络，自动就绪") }
                item {
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                FeatureRow(Icons.Outlined.AutoAwesome, "连接 Wi-Fi 时开启", "自动开启系统的“无线调试”开关")
                            }
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                modifier = Modifier.semantics { contentDescription = "连接信任 Wi-Fi 时自动开启远程调试" },
                                checked = settings.autoConnect,
                                enabled = !state.busy,
                                onCheckedChange = { enabled ->
                                    if (!enabled) viewModel.setAutoConnect(false)
                                    else if (readyForAuto) viewModel.setAutoConnect(true)
                                    else sheet = Sheet.AUTOMATION
                                },
                            )
                        }
                        ThinDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("当前 Wi-Fi", fontSize = 12.sp, color = colors.muted)
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    state.currentWifi.ssid ?: if (state.currentWifi.isWifiConnected) "网络名称不可用" else "尚未连接 Wi-Fi",
                                    fontSize = 16.sp, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                text = if (state.currentWifi.ssid in settings.trustedSsids) "已信任" else "信任此网络",
                                onClick = {
                                    sheet = if (state.currentWifi.identityAvailable) Sheet.TRUST else Sheet.AUTOMATION
                                },
                                enabled = state.currentWifi.isWifiConnected && !state.busy && state.currentWifi.ssid !in settings.trustedSsids,
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                        if (settings.trustedSsids.isNotEmpty()) {
                            ThinDivider()
                            Text("已信任的网络", color = colors.muted, fontSize = 12.sp)
                            settings.trustedSsids.sorted().forEach { ssid ->
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Wifi, null, tint = colors.muted, modifier = Modifier.size(18.dp))
                                    Text(ssid, color = colors.text, fontSize = 14.sp, maxLines = 2,
                                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                                    IconButton(onClick = { viewModel.removeTrustedWifi(ssid) }, enabled = !state.busy) {
                                        Icon(Icons.Outlined.Close, "移除信任网络 $ssid", tint = colors.muted, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        if (settings.autoConnect) {
                            ThinDivider()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (state.monitoringActive) "●  正在监听 Wi-Fi 连接" else "监听已暂停",
                                    color = if (state.monitoringActive) colors.blue else colors.muted,
                                    fontSize = 13.sp, modifier = Modifier.weight(1f),
                                )
                                if (!state.monitoringActive) {
                                    TextButton("恢复", onClick = viewModel::resumeMonitoring)
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("离开信任网络后，会关闭由自动化开启的调试。手动关闭后，本次连接不再自动开启。",
                            color = colors.muted, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                item { SectionLabel("快捷操作", "少一步，快一点") }
                item {
                    Panel {
                        FeatureRow(Icons.Outlined.DashboardCustomize, "控制中心快捷开关", "下拉即可切换系统无线调试，长按返回应用")
                        Spacer(Modifier.height(18.dp))
                        TextButton(
                            text = "添加到控制中心", onClick = onAddTile,
                            modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                item {
                    Panel {
                        FeatureRow(Icons.Outlined.Tune, "后台与权限", "自动化需要位置权限及系统定位开关")
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton("权限设置", { sheet = Sheet.AUTOMATION }, Modifier.weight(1f))
                            TextButton("应用设置", { context.openAppSettings() }, Modifier.weight(1f))
                        }
                    }
                }
                if (state.lastAction != null) {
                    item { Text(state.lastAction.orEmpty(), color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp) }
                }
                item {
                    Text("RemoteDebuggingHelper  ·  Compose + MIUIX", modifier = Modifier.fillMaxWidth(),
                        color = colors.muted.copy(alpha = .7f), fontSize = 11.sp)
                }
            }
        }
    }

    when (sheet) {
        Sheet.HELP -> AppDialog("连接指南", onDismiss = { sheet = null }) {
            HelpStep("01", "开启系统无线调试", "先开启系统开发者选项，让手机与电脑连接同一 Wi-Fi。授予本应用 Root 权限后开启，首次使用时在系统弹窗中允许当前网络。")
            HelpStep("02", "用系统配对码配对", "打开下方的系统配对页面，选择“使用配对码配对设备”。在电脑上运行 adb pair IP:配对端口，再输入系统显示的配对码。")
            HelpStep("03", "连接这台设备", "配对后，ADB 通常会通过 mDNS 自动连接；也可以执行主页的 adb connect 命令。连接端口与配对端口不同，重开无线调试后连接端口可能变化。")
            Text("使用 Android 11 及以上的原生无线调试，设备配对和 TLS 加密由系统管理。无需 USB 连接，也无需单独开启 USB 调试。",
                color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(12.dp))
            Text("自动化不会跳过系统的网络确认或设备配对。首次在系统弹窗中勾选始终允许此网络后，再使用 Wi-Fi 自动开启。",
                color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(12.dp))
            Text("配对时请保持系统的配对码弹窗打开；关闭弹窗会结束本次配对。",
                color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(16.dp))
            TextButton("打开系统无线调试与配对", onOpenWirelessSettings, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
            Spacer(Modifier.height(8.dp))
            TextButton("知道了", { sheet = null }, Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColorsPrimary())
        }
        Sheet.TRUST -> AppDialog("信任当前 Wi-Fi", onDismiss = { sheet = null }) {
            Text(state.currentWifi.ssid.orEmpty(), color = colors.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("开启自动化后，连接此名称的 Wi-Fi 将自动开启系统无线调试。系统的网络确认和设备配对仍需你完成。请仅添加私人网络；相同名称的网络也会匹配。",
                color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(20.dp))
            TextButton("信任此网络", { viewModel.trustCurrentWifi(); sheet = null }, Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(), enabled = state.currentWifi.identityAvailable)
            TextButton("取消", { sheet = null }, Modifier.fillMaxWidth())
        }
        Sheet.AUTOMATION -> AppDialog("准备自动化", onDismiss = { sheet = null }) {
            Text("位置权限用于识别 Wi-Fi 名称，不记录或上传位置。后台识别需要在系统权限中选择“始终允许”并开启精确位置。",
                color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(14.dp))
            SetupRow("Root 授权", state.rootStatus == RootStatus.AVAILABLE, "授权", viewModel::requestRoot)
            SetupRow("精确位置权限", state.currentWifi.hasLocationPermission, "申请", requestLocation)
            if (!state.currentWifi.hasLocationPermission) {
                TextButton("无法弹出授权？前往应用设置", { context.openAppSettings() }, Modifier.fillMaxWidth())
            }
            SetupRow("后台位置权限", state.currentWifi.hasBackgroundLocationPermission, "设置", requestBackground)
            SetupRow("系统定位开关", state.currentWifi.locationEnabled, "打开", { context.openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) })
            SetupRow("已信任的 Wi-Fi", settings.trustedSsids.isNotEmpty(), "添加") {
                if (state.currentWifi.identityAvailable) sheet = Sheet.TRUST
                else Toast.makeText(context, "请先授权位置权限、打开定位，并连接 Wi-Fi", Toast.LENGTH_LONG).show()
            }
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                TextButton("允许通知，查看监听状态", { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(12.dp))
            Text("MIUI / HyperOS 请在应用设置中允许自启动，并将省电策略设为无限制，以维持后台监听。重启后将在解锁时恢复。",
                color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(16.dp))
            TextButton("开启自动化", { viewModel.setAutoConnect(true); sheet = null }, Modifier.fillMaxWidth(),
                enabled = readyForAuto && !state.busy, colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton("完成", { sheet = null }, Modifier.fillMaxWidth())
        }
        null -> Unit
    }
}

@Composable
private fun ConnectionCard(state: DebugState, onToggle: (Boolean) -> Unit, onOpenWirelessSettings: () -> Unit) {
    val colors = LocalPalette.current
    val context = LocalContext.current
    val switchEnabled = state.requestedEnabled || state.debuggingEnabled
    val needsEndpointPermission = state.requestedEnabled && state.rootStatus != RootStatus.AVAILABLE
    val address = if (state.debuggingEnabled) {
        state.activePort?.let { port -> state.currentWifi.ipv4Address?.let { "$it:$port" } }
    } else null
    val command = address?.let { "adb connect $it" }
    Card(
        modifier = Modifier.fillMaxWidth(), cornerRadius = 26.dp, insideMargin = PaddingValues(24.dp),
        colors = CardDefaults.defaultColors(color = colors.blueSurface),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(when {
                state.debuggingEnabled -> "●  系统无线调试已开启"
                state.requestedEnabled -> "◌  系统开关已开启"
                else -> "○  系统无线调试已关闭"
            }, colors.blue)
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).background(colors.blue.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.WifiTethering, null, tint = colors.blue, modifier = Modifier.size(27.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(when {
            state.debuggingEnabled -> "无线调试，已就绪"
            needsEndpointPermission -> "系统开关已开启"
            state.requestedEnabled -> "正在等待系统开启"
            else -> "系统无线调试"
        }, fontSize = 25.sp,
            fontWeight = FontWeight.Bold, color = colors.text, lineHeight = 34.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!state.currentWifi.isWifiConnected) "先连接 Wi-Fi，让电脑与手机在同一网络。"
            else if (state.debuggingEnabled) "首次连接请先配对；已配对的电脑可直接连接。"
            else if (needsEndpointPermission) "授予 Root 权限后读取连接端口，也可在系统页面查看。"
            else if (state.requestedEnabled) "请查看系统网络确认弹窗，或进入无线调试页面。"
            else "与开发者选项中的开关同步，使用系统配对与 TLS。",
            color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("系统连接地址", color = colors.muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(address ?: when {
                        needsEndpointPermission -> "授权后读取端口"
                        switchEnabled -> "等待系统分配端口"
                        else -> "开启后显示"
                    }, fontFamily = FontFamily.Monospace, fontSize = 18.sp,
                        fontWeight = FontWeight.Medium, color = colors.text, maxLines = 2)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("连接方式", color = colors.muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Text("配对 · TLS", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.terminal).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("›", color = Color(0xFF8AACF4), fontSize = 21.sp, modifier = Modifier.padding(end = 10.dp))
            SelectionContainer(Modifier.weight(1f)) {
                Text(command ?: "adb connect <IP>:<连接端口>", color = Color(0xFFE3ECFF), fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace, lineHeight = 19.sp)
            }
            IconButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB 连接命令", command))
                    Toast.makeText(context, "连接命令已复制", Toast.LENGTH_SHORT).show()
                }, enabled = command != null,
            ) {
                Icon(Icons.Outlined.ContentCopy, "复制连接命令", tint = Color(0xFFA6B8DE), modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        TextButton(
            text = if (state.busy) "正在处理…" else if (switchEnabled) "关闭系统无线调试" else "开启系统无线调试",
            onClick = { onToggle(!switchEnabled) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && state.rootStatus == RootStatus.AVAILABLE && (state.currentWifi.isWifiConnected || switchEnabled),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
        Spacer(Modifier.height(10.dp))
        TextButton("系统配对与已配对设备", onOpenWirelessSettings, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp, insideMargin = PaddingValues(20.dp), content = content)
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, subtitle: String, accent: Color = LocalPalette.current.blue) {
    val colors = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, fontSize = 12.sp, lineHeight = 19.sp, color = colors.muted)
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    val colors = LocalPalette.current
    Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
        Spacer(Modifier.weight(1f))
        Text(subtitle, fontSize = 11.sp, color = colors.muted)
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(text, color = color, fontWeight = FontWeight.Medium, fontSize = 10.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .09f)).padding(horizontal = 9.dp, vertical = 5.dp))
}

@Composable
private fun ThinDivider() {
    Spacer(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(LocalPalette.current.line))
}

@Composable
private fun AppDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(cornerRadius = 24.dp, insideMargin = PaddingValues(24.dp), modifier = Modifier.widthIn(max = 480.dp)) {
            LazyColumn {
                item {
                    Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = LocalPalette.current.text)
                    Spacer(Modifier.height(20.dp))
                    Column(content = content)
                }
            }
        }
    }
}

@Composable
private fun SetupRow(title: String, done: Boolean, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null, tint = if (done) LocalPalette.current.blue else LocalPalette.current.muted, modifier = Modifier.size(19.dp))
        Text(title, color = LocalPalette.current.text, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
        if (!done) TextButton(action, onClick) else Text("已就绪", color = LocalPalette.current.muted, fontSize = 12.sp)
    }
}

@Composable
private fun HelpStep(number: String, title: String, description: String) {
    Row(Modifier.padding(bottom = 20.dp)) {
        Text(number, color = LocalPalette.current.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 3.dp, end = 14.dp))
        Column {
            Text(title, color = LocalPalette.current.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(description, color = LocalPalette.current.muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
    }
}

private fun Context.openAppSettings() {
    openSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
}

private fun Context.openSettings(action: String, uri: Uri? = null) {
    try {
        startActivity(Intent(action, uri))
    } catch (_: RuntimeException) {
        Toast.makeText(this, "无法打开此设置，请手动前往系统设置", Toast.LENGTH_LONG).show()
    }
}
