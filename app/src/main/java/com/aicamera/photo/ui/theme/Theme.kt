package com.aicamera.photo.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object LumenColors {
    val Amber = Color(0xFFF4B860)
    val Teal = Color(0xFF5EC4B6)
    val Ready = Color(0xFF6BD58A)
    val Nearly = Color(0xFFF4D35E)
    val NotAligned = Color(0xFFFFFFFF)
    val Issue = Color(0xFFFF8A65)
    val Ai = Color(0xFFB39DFF)
    val Scrim = Color(0x99000000)
    val ScrimStrong = Color(0xCC0B0E11)
}

private val scheme = darkColorScheme(
    primary = LumenColors.Amber,
    onPrimary = Color(0xFF2B1A00),
    primaryContainer = Color(0xFF4A3510),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = LumenColors.Teal,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = Color(0xFF16413B),
    onSecondaryContainer = Color(0xFFB8F0E6),
    tertiary = LumenColors.Ai,
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF14181C),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF22282E),
    onSurfaceVariant = Color(0xFFB9C1C9),
    surfaceContainer = Color(0xFF1A1F24),
    surfaceContainerHigh = Color(0xFF20262C),
    outline = Color(0xFF4A535C),
    error = Color(0xFFFF8A80),
)

private val typography = Typography().let {
    it.copy(
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

val OverlayLabel = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color.White)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
