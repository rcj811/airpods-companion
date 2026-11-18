package com.rcj811.podscompanion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.rcj811.podscompanion.service.PodsService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Bumped whenever we come back to the app so permission cards refresh. */
    private val resumeTick = mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        PodsService.start(this)
        if (com.rcj811.podscompanion.Prefs.autoUpdate) {
            lifecycleScope.launch {
                com.rcj811.podscompanion.update.Updater.check(this@MainActivity, manual = false, force = true)
            }
        }
        resumeTick.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PodsService.start(this)
        if (missingPermissions().isNotEmpty()) permissionLauncher.launch(missingPermissions().toTypedArray())

        setContent {
            PodsTheme {
                var tab by rememberSaveable { mutableStateOf(0) }
                val tick = resumeTick.intValue
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Headphones, null) }, label = { Text("裝置") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Equalizer, null) }, label = { Text("音效") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
                        }
                    }
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        0 -> DeviceScreen(m)
                        1 -> EqScreen(m)
                        else -> SettingsScreen(
                            modifier = m,
                            tick = tick,
                            permissionsOk = missingPermissions().isEmpty(),
                            overlayOk = Settings.canDrawOverlays(this),
                            batteryOptIgnored = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
                            onRequestPermissions = { permissionLauncher.launch(missingPermissions().toTypedArray()) },
                            onRequestOverlay = {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            },
                            locationOn = getSystemService(android.location.LocationManager::class.java).isLocationEnabled,
                            onRequestLocation = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                            onRequestBattery = {
                                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun missingPermissions(): List<String> {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            wanted += Manifest.permission.BLUETOOTH_SCAN
            wanted += Manifest.permission.BLUETOOTH_CONNECT
        }
        wanted += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }
}
