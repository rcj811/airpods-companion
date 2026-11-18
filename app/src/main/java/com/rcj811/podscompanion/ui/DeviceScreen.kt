package com.rcj811.podscompanion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rcj811.podscompanion.AacpStatus
import com.rcj811.podscompanion.ListeningMode
import com.rcj811.podscompanion.Pod
import com.rcj811.podscompanion.PodsRepository
import com.rcj811.podscompanion.service.PodsService

@Composable
fun DeviceScreen(modifier: Modifier = Modifier) {
    val s by PodsRepository.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(
            if (s.connected) (s.deviceName ?: s.model.label) else "AirPods",
            fontSize = 28.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp, start = 4.dp),
        )
        Text(
            if (s.connected) "${s.model.label} · 已連線" else "尚未連線，打開充電盒並連上手機",
            color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )

        SectionTitle("電量")
        Card {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (s.model.overEar) {
                    BatteryGauge("耳機", s.single)
                } else {
                    BatteryGauge("左耳", s.left, showEar = true)
                    BatteryGauge("右耳", s.right, showEar = true)
                    BatteryGauge("充電盒", s.case)
                }
            }
            if (s.connected) {
                Spacer(Modifier.height(12.dp))
                Hint(if (s.batterySource.isNotEmpty()) "來源：${s.batterySource}" else "等待 AirPods 廣播電量…（打開充電盒會更快）")
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { PodsService.start(ctx, PodsService.ACTION_SHOW_POPUP) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("顯示電量彈窗") }
            }
        }

        SectionTitle("聲音模式")
        Card {
            val enabled = s.aacp == AacpStatus.CONNECTED
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ListeningMode.ANC, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.OFF).forEach { m ->
                    ModeChip(
                        label = m.label,
                        selected = s.listeningMode == m,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        PodsService.start(ctx, PodsService.ACTION_SET_MODE) { putExtra(PodsService.EXTRA_MODE, m.code) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("對話感知", fontSize = 16.sp)
                    Hint("開口說話時自動降低音量")
                }
                Switch(
                    checked = s.conversationalAwareness == true,
                    enabled = enabled,
                    onCheckedChange = { v ->
                        PodsService.start(ctx, PodsService.ACTION_SET_CA) { putExtra(PodsService.EXTRA_ENABLED, v) }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            when (s.aacp) {
                AacpStatus.CONNECTED -> Hint("已透過 Apple 協定連線，可直接控制。")
                AacpStatus.CONNECTING -> Hint("正在連線 AirPods 控制協定…")
                AacpStatus.UNSUPPORTED -> {
                    Hint(
                        (s.aacpError ?: "無法連線") +
                            "\n電量顯示與 EQ 不受影響。降噪控制需要 Pixel Android 16 QPR3+、ColorOS/OxygenOS 16、Android 17，或已 root 的手機。"
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { PodsService.start(ctx, PodsService.ACTION_RECONNECT_AACP) }) { Text("重試連線") }
                }
                AacpStatus.IDLE -> Hint(if (s.connected) "等待連線…" else "連上 AirPods 後即可切換。")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg = when {
        selected -> Accent
        else -> Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
fun BatteryGauge(label: String, pod: Pod, showEar: Boolean = false) {
    val target = if (pod.known) pod.level / 100f else 0f
    val progress by animateFloatAsState(target, tween(700), label = "battery")
    val color = when {
        pod.charging -> Good
        pod.level <= 20 -> Bad
        pod.level <= 40 -> Warn
        else -> Good
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(86.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = size.minDimension * 0.09f
                val arcSize = Size(size.width - sw, size.height - sw)
                val tl = Offset(sw / 2, sw / 2)
                drawArc(Color.White.copy(alpha = 0.12f), 0f, 360f, false, tl, arcSize, style = Stroke(sw))
                if (pod.known) drawArc(color, -90f, 360f * progress, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (pod.known) "${pod.level}%" else "--", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                if (pod.charging) Text("⚡ 充電中", fontSize = 10.sp, color = Good)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
        if (showEar && pod.known) Text(if (pod.inEar) "配戴中" else "未配戴", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
    }
}
