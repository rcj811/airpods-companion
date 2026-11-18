package com.rcj811.podscompanion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rcj811.podscompanion.Prefs
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    modifier: Modifier,
    tick: Int,
    permissionsOk: Boolean,
    overlayOk: Boolean,
    batteryOptIgnored: Boolean,
    onRequestPermissions: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestBattery: () -> Unit,
    locationOn: Boolean = true,
    onRequestLocation: () -> Unit = {},
) {
    var popup by remember { mutableStateOf(Prefs.popupEnabled) }
    var seconds by remember { mutableFloatStateOf(Prefs.popupSeconds.toFloat()) }
    var lowAlert by remember { mutableStateOf(Prefs.lowBatteryAlert) }

    Column(
        modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Text("設定", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 20.dp))

        SectionTitle("必要權限")
        Card {
            PermissionRow("藍牙與附近裝置", "讀取 AirPods 電量廣播", permissionsOk, onRequestPermissions)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.08f))
            PermissionRow("顯示在其他應用程式上層", "連線時跳出 iPad 風格電量卡片", overlayOk, onRequestOverlay)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.08f))
            PermissionRow("不限制電池用量", "避免系統把背景服務關掉", batteryOptIgnored, onRequestBattery)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.08f))
            PermissionRow("定位服務（系統開關）", "Android 掃描藍牙廣播必須開定位", locationOn, onRequestLocation)
        }

        SectionTitle("連線彈窗")
        Card {
            ToggleRow("連上 AirPods 時顯示電量", "就像 iPhone / iPad 一樣", popup) {
                popup = it; Prefs.popupEnabled = it
            }
            Spacer(Modifier.height(10.dp))
            Text("顯示 ${seconds.roundToInt()} 秒", fontSize = 15.sp)
            Slider(
                value = seconds,
                onValueChange = { seconds = it },
                onValueChangeFinished = { Prefs.popupSeconds = seconds.roundToInt() },
                valueRange = 3f..15f,
                steps = 11,
            )
            ToggleRow("低電量提醒", "任一耳低於 20% 時通知", lowAlert) {
                lowAlert = it; Prefs.lowBatteryAlert = it
            }
        }

        UpdateCard()

        SectionTitle("關於")
        Card {
            Hint(
                "電量：讀取 AirPods 的藍牙 LE 廣播，所有手機都能用。\n" +
                    "降噪 / 對話感知：Apple 私有協定（參考 LibrePods、CAPod 公開的逆向研究），需較新的 Android 藍牙堆疊。\n" +
                    "EQ：手機端 DynamicsProcessing 處理，耳機曲線來自 AutoEq（MIT 授權）。\n" +
                    "Apple 官方 EQ 預設與韌體更新無法在 Android 上使用。"
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, ok: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Hint(desc)
        }
        if (ok) Text("✓ 已開啟", color = Good, fontSize = 14.sp)
        else Button(onClick = onClick) { Text("開啟") }
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            Hint(desc)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun UpdateCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val status by com.rcj811.podscompanion.update.Updater.status.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(Prefs.autoUpdate) }
    SectionTitle("更新")
    Card {
        ToggleRow("自動檢查並安裝更新", "目前版本 ${com.rcj811.podscompanion.update.Updater.currentVersion}", auto) {
            auto = it; Prefs.autoUpdate = it
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            when (val s = status) {
                is com.rcj811.podscompanion.update.Updater.Status.Idle -> "每次開啟 App 與每 3 小時自動檢查"
                is com.rcj811.podscompanion.update.Updater.Status.Checking -> "檢查中…"
                is com.rcj811.podscompanion.update.Updater.Status.UpToDate -> "已是最新版本"
                is com.rcj811.podscompanion.update.Updater.Status.Downloading -> "下載 ${s.version}… ${s.percent}%"
                is com.rcj811.podscompanion.update.Updater.Status.Installing -> "安裝 ${s.version}…"
                is com.rcj811.podscompanion.update.Updater.Status.NeedsPermission -> "有新版本 ${s.version}，需要允許「安裝未知應用程式」"
                is com.rcj811.podscompanion.update.Updater.Status.Error -> s.message
            }
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                scope.launch { com.rcj811.podscompanion.update.Updater.check(ctx, manual = true) }
            }) { Text("立即檢查") }
            if (!ctx.packageManager.canRequestPackageInstalls()) {
                Spacer(Modifier.width(10.dp))
                Button(onClick = {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}")
                        )
                    )
                }) { Text("允許安裝") }
            }
        }
    }
}
