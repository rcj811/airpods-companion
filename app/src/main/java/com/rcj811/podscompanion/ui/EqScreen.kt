package com.rcj811.podscompanion.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rcj811.podscompanion.PodsRepository
import com.rcj811.podscompanion.Prefs
import com.rcj811.podscompanion.eq.EqData
import com.rcj811.podscompanion.eq.EqEngine
import com.rcj811.podscompanion.service.PodsService
import kotlin.math.roundToInt

@Composable
fun EqScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pods by PodsRepository.state.collectAsStateWithLifecycle()

    var enabled by remember { mutableStateOf(Prefs.eqEnabled) }
    var gains by remember { mutableStateOf(Prefs.eqGains) }
    var presetName by remember { mutableStateOf(Prefs.eqPresetName) }
    var ownKey by remember { mutableStateOf(Prefs.ownModelKey ?: pods.model.eqKey) }
    var tab by rememberSaveable { mutableStateOf(0) }

    fun commit(newGains: FloatArray, name: String, turnOn: Boolean = true) {
        gains = newGains
        presetName = name
        Prefs.eqGains = newGains
        Prefs.eqPresetName = name
        if (turnOn && !enabled) {
            enabled = true
            Prefs.eqEnabled = true
        }
        refreshEq(ctx)
    }

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Text("EQ 音效", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, top = 20.dp))

        Card(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("EQ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Hint("目前音效：$presetName")
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    Prefs.eqEnabled = it
                    refreshEq(ctx)
                })
            }
            Spacer(Modifier.height(12.dp))
            EqCurve(gains, Modifier.fillMaxWidth().height(70.dp))
            if (EqEngine.globalWorks == false) {
                Spacer(Modifier.height(8.dp))
                Hint("此手機不允許全域音效；仍可套用到有支援音效面板的播放器（Spotify、YouTube Music 等）。")
            }
        }

        TabRow(selectedTabIndex = tab, modifier = Modifier.padding(horizontal = 16.dp).clip(RoundedCornerShape(16.dp))) {
            Tab(tab == 0, { tab = 0 }, text = { Text("官方音效") })
            Tab(tab == 1, { tab = 1 }, text = { Text("均衡器設定") })
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            if (tab == 0) {
                SectionTitle("我的耳機（用來計算校正與模擬）")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EqData.corrections.forEach { c ->
                        Pill(c.label, selected = c.key == ownKey) {
                            ownKey = c.key
                            Prefs.ownModelKey = c.key
                        }
                    }
                }
                val own = EqData.correction(ownKey)
                if (own == null) {
                    Spacer(Modifier.height(6.dp))
                    Hint("請先選擇你手上的耳機型號，才能使用「原廠校正」和「模擬其他耳機」。")
                }

                if (own != null) {
                    SectionTitle("耳機音效（AutoEq 實測資料）")
                    PresetRow("原音校正", "把 ${own.label} 調成中性、平衡的 Harman 曲線", presetName == "原音校正") {
                        commit(EqData.neutral(own), "原音校正")
                    }
                    EqData.corrections.filter { it.key != own.key }.forEach { target ->
                        val name = "模擬 ${target.label}"
                        PresetRow(target.label, "讓 ${own.label} 聽起來像 ${target.label}", presetName == name) {
                            commit(EqData.mimic(own, target), name)
                        }
                    }
                }

                SectionTitle("風格音效")
                EqData.styles.forEach { st ->
                    PresetRow(st.name, st.desc, presetName == st.name) { commit(st.gains.copyOf(), st.name) }
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(12.dp))
                EqData.BAND_LABELS.forEachIndexed { i, label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.width(44.dp), fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                        Slider(
                            value = gains[i],
                            onValueChange = { v ->
                                val g = gains.copyOf()
                                g[i] = (v * 2).roundToInt() / 2f
                                gains = g
                            },
                            onValueChangeFinished = { commit(gains, "自訂") },
                            valueRange = -12f..12f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                        )
                        Text(
                            (if (gains[i] > 0) "+" else "") + "%.1f".format(gains[i]),
                            modifier = Modifier.width(48.dp), fontSize = 13.sp,
                        )
                    }
                }
                TextButton(onClick = { commit(FloatArray(10), "平衡") }) { Text("全部歸零") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun refreshEq(ctx: Context) {
    EqEngine.refresh()
    PodsService.start(ctx, PodsService.ACTION_REFRESH_EQ)
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) Accent else Color.White)
    }
}

@Composable
private fun PresetRow(title: String, desc: String, selected: Boolean, onApply: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .clickable(onClick = onApply)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) { Text(title.take(1), color = Color.Black, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Hint(desc)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Accent, RoundedCornerShape(12.dp))
                .background(if (selected) Accent else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) { Text(if (selected) "使用中" else "應用", color = if (selected) Color.White else Accent, fontSize = 13.sp) }
    }
}

@Composable
private fun EqCurve(gains: FloatArray, modifier: Modifier) {
    Canvas(modifier) {
        val mid = size.height / 2
        drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, mid), Offset(size.width, mid), 2f)
        val step = size.width / (gains.size - 1)
        val path = Path()
        gains.forEachIndexed { i, g ->
            val x = i * step
            val y = mid - (g / 12f) * (size.height / 2)
            if (i == 0) path.moveTo(x, y) else {
                val px = (i - 1) * step
                val py = mid - (gains[i - 1] / 12f) * (size.height / 2)
                path.cubicTo(px + step / 2, py, x - step / 2, y, x, y)
            }
        }
        drawPath(path, Accent, style = Stroke(5f))
    }
}
