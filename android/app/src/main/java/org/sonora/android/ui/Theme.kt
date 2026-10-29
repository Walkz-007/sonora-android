package org.sonora.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF9D8CFF)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF15122B),
    primaryContainer = Color(0xFF2A2550),
    onPrimaryContainer = Color(0xFFE9E6FF),
    secondary = Color(0xFFB9B4D6),
    background = Color(0xFF0E0F13),
    onBackground = Color(0xFFE8E8EE),
    surface = Color(0xFF0E0F13),
    onSurface = Color(0xFFE8E8EE),
    surfaceVariant = Color(0xFF1B1C23),
    onSurfaceVariant = Color(0xFF9A9BA8),
    surfaceContainer = Color(0xFF16171D),
    surfaceContainerHigh = Color(0xFF1E1F27),
    surfaceContainerHighest = Color(0xFF262731),
    outline = Color(0xFF3A3B47),
    error = Color(0xFFFF8A8A),
)

private val typography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun SonoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

val SectionTitle: TextStyle
    @Composable get() = MaterialTheme.typography.titleLarge
