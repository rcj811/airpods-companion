package com.rcj811.podscompanion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF2F8BFF)
val CardBg = Color(0xFF1C1D22)
val Good = Color(0xFF34C759)
val Warn = Color(0xFFFFCC00)
val Bad = Color(0xFFFF3B30)

@Composable
fun PodsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            background = Color(0xFF0E0F12),
            surface = Color(0xFF0E0F12),
            surfaceContainer = Color(0xFF16171B),
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}
