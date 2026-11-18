package com.rcj811.podscompanion

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.rcj811.podscompanion.aacp.AacpPackets
import com.rcj811.podscompanion.eq.EqEngine
import com.rcj811.podscompanion.popup.BatteryPopup
import com.rcj811.podscompanion.service.PodsService
import com.rcj811.podscompanion.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Walks through every screen and feature with simulated AirPods data and fails if anything
 * throws or the app process dies.
 */
@RunWith(AndroidJUnit4::class)
class CrashTest {

    private val permissions = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(permissions).around(compose)

    private val instr get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private val device get() = UiDevice.getInstance(instr)

    @Before
    fun overlay() {
        device.executeShellCommand("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
    }

    private fun fakePods(aacp: AacpStatus = AacpStatus.CONNECTED, overEar: Boolean = false) {
        PodsRepository.update {
            it.copy(
                connected = true,
                deviceName = "Test AirPods",
                deviceAddress = "00:11:22:33:44:55",
                model = if (overEar) PodsModel.AIRPODS_MAX else PodsModel.AIRPODS_PRO_2,
                left = Pod(80, false, true), right = Pod(15, false, true), case = Pod(50, true),
                single = Pod(60, true),
                batterySource = "test",
                aacp = aacp,
                aacpError = if (aacp == AacpStatus.UNSUPPORTED) "test error" else null,
                listeningMode = ListeningMode.ANC,
                conversationalAwareness = true,
            )
        }
        compose.waitForIdle()
    }

    private fun service(action: String, extras: Intent.() -> Unit = {}) {
        PodsService.start(ctx, action, extras)
        Thread.sleep(400)
    }

    @Test
    fun everyFeature() {
        // --- not connected
        compose.onNodeWithText("尚未連線，打開充電盒並連上手機").assertExists()

        // --- connected with AACP
        fakePods()
        compose.onNodeWithText("AirPods Pro 2 · 已連線").assertExists()
        compose.onNodeWithText("顯示電量彈窗").performScrollTo().performClick()
        Thread.sleep(1500)
        assertTrue("popup visible", device.hasObject(By.text("充電盒")))
        for (mode in listOf("通透", "自適應", "關閉", "降噪")) {
            compose.onNodeWithText(mode).performScrollTo().performClick()
        }
        compose.onAllNodes(isToggleable()).onFirst().performScrollTo().performClick()

        // --- service actions
        service(PodsService.ACTION_SHOW_POPUP)
        service(PodsService.ACTION_CYCLE_MODE)
        service(PodsService.ACTION_SET_MODE) { putExtra(PodsService.EXTRA_MODE, 3) }
        service(PodsService.ACTION_SET_CA) { putExtra(PodsService.EXTRA_ENABLED, false) }
        service(PodsService.ACTION_REFRESH_EQ)
        service(PodsService.ACTION_DEVICE_CONNECTED)
        service(PodsService.ACTION_RECONNECT_AACP)
        Thread.sleep(2500) // reconnect attempt against a fake address must fail gracefully

        // --- AACP unsupported phone
        fakePods(AacpStatus.UNSUPPORTED)
        compose.onNodeWithText("重試連線").performScrollTo().performClick()
        Thread.sleep(2500)

        // --- over-ear (AirPods Max)
        fakePods(overEar = true)
        compose.onNodeWithText("耳機").assertExists()

        // --- popup directly, many updates, both layouts
        instr.runOnMainSync {
            val popup = BatteryPopup(ctx)
            popup.show(PodsRepository.state.value, 3)
            repeat(30) { i ->
                popup.update(PodsRepository.state.value.copy(left = Pod(i * 3, i % 2 == 0), model = if (i % 2 == 0) PodsModel.AIRPODS_PRO else PodsModel.AIRPODS_MAX))
            }
            popup.show(PodsState(), 3) // empty state
            popup.dismiss()
            popup.dismiss() // double dismiss
        }

        // --- EQ screen
        fakePods()
        compose.onNodeWithText("音效").performClick()
        compose.waitForIdle()
        compose.onAllNodes(isToggleable()).onFirst().performClick() // EQ on/off
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.onNodeWithText("原音校正").performScrollTo().performClick()
        compose.onAllNodesWithText("Sony WH-1000XM5").onLast().performScrollTo().performClick()
        compose.onAllNodesWithText("Sennheiser HD 600").onLast().performScrollTo().performClick()
        compose.onNodeWithText("Bose QC45").performScrollTo() // pill row + list both exist? just scroll
        compose.onAllNodesWithText("Galaxy Buds2 Pro").onFirst().performScrollTo().performClick() // change own model (pill)
        for (style in listOf("重低音", "人聲", "跑步", "夜間", "平衡")) {
            compose.onNodeWithText(style).performScrollTo().performClick()
        }
        compose.onNodeWithText("均衡器設定").performClick()
        compose.waitForIdle()
        val sliders = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        for (i in 0 until 10) {
            sliders[i].performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(if (i % 2 == 0) 12f else -12f) }
        }
        compose.onNodeWithText("全部歸零").performScrollTo().performClick()
        EqEngine.refresh()

        // --- Settings
        compose.onNodeWithText("設定").performClick()
        compose.waitForIdle()
        val toggles = compose.onAllNodes(isToggleable())
        toggles[0].performScrollTo().performClick()
        toggles[0].performScrollTo().performClick()
        toggles[1].performScrollTo().performClick()
        toggles[1].performScrollTo().performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[0]
            .performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }

        // --- AACP decoding in the app process
        AacpPackets.decode(byteArrayOf(4, 0, 4, 0, 4, 0, 3, 4, 1, 90, 2, 1, 2, 1, 80, 2, 1, 8, 1, 70, 1, 1))

        // --- disconnect
        PodsRepository.reset()
        compose.onNodeWithText("裝置").performClick()
        compose.onNodeWithText("尚未連線，打開充電盒並連上手機").assertExists()

        // rotate & recreate
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        Thread.sleep(1000)
    }
}
