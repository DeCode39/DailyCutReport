package com.littleone.dailycutreport

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun DailyCutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFFDA42),
            onPrimary = Color.Black,
            secondary = Color(0xFFFFE680),
            onSecondary = Color.Black,
            background = Color(0xFF0C0D0C),
            onBackground = Color(0xFFF3F1E8),
            surface = Color(0xFF111210),
            onSurface = Color(0xFFF3F1E8),
            surfaceVariant = Color(0xFF20211C),
            onSurfaceVariant = Color(0xFFB5B5AA),
            primaryContainer = Color(0xFFFFDA42),
            onPrimaryContainer = Color.Black,
            secondaryContainer = Color(0xFF33342D),
            onSecondaryContainer = Color(0xFFF3F1E8),
            surfaceContainer = Color(0xFF191A17),
            surfaceContainerHigh = Color(0xFF20211C),
            surfaceContainerHighest = Color(0xFF292A24),
            outlineVariant = Color(0xFF373833),
            error = Color(0xFFFF8A80)
        ),
        content = content
    )
}
