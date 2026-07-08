package com.littleone.dailycutreport

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun DailyCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFD84D),
            onPrimary = Color.Black,
            secondary = Color(0xFFFFE680),
            onSecondary = Color.Black,
            background = Color.Black,
            onBackground = Color(0xFFF7F1D0),
            surface = Color(0xFF101010),
            onSurface = Color(0xFFF7F1D0),
            surfaceVariant = Color(0xFF202020),
            onSurfaceVariant = Color(0xFFE6DDAA),
            error = Color(0xFFFF8A80)
        ),
        content = content
    )
}
