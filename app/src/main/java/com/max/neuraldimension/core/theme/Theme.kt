package com.max.neuraldimension.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// MAX 2.0 "Neural Dimension" palette (from the master blueprint)
val NdBlack = Color(0xFF07050C)
val NdDarkPurple = Color(0xFF160B28)
val NdViolet = Color(0xFF813EFF)
val NdLavender = Color(0xFFD9B9FF)
val NdSilver = Color(0xFFE8E5F0)
val NdNeon = Color(0xFFA65BFF)

val NdGlass = Color(0x2EFFFFFF)
val NdGlassLow = Color(0x14FFFFFF)
val NdPanelDeep = Color(0xB31B1130)
val NdPanelBorder = Color(0x40D9B9FF)
val NdMuted = Color(0xFF9C8DB8)
val NdError = Color(0xFFFF5C7A)
val NdOk = Color(0xFF4CE0B3)
val NdWarn = Color(0xFFFFC94D)

@Composable
fun NdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = NdBlack,
            surface = NdDarkPurple,
            primary = NdViolet,
            secondary = NdLavender,
            tertiary = NdNeon,
            onBackground = NdSilver,
            onSurface = NdSilver,
            onPrimary = Color.White,
            surfaceVariant = Color(0xFF241639),
            onSurfaceVariant = NdLavender
        ),
        content = content
    )
}
