package com.zepdriver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.zepdriver.core.alerts.AlertLevel
import com.zepdriver.ui.map.MapPalette
import com.zepdriver.ui.map.MapPalettes

/** App-specific colors on top of the Material scheme. */
@Immutable
data class ZepColors(
    val night: Boolean,
    /** Speedometer / panels over the map. */
    val panel: Color,
    val onPanel: Color,
    val onPanelMuted: Color,
    val caution: Color,
    val onCaution: Color,
    val advisory: Color,
    val onAdvisory: Color,
    val warning: Color,
    val onWarning: Color,
    val alarm: Color,
    val alarmAlt: Color,
    val onAlarm: Color,
    val onAlarmAlt: Color,
    val good: Color,
    val bad: Color,
    val potties: Color,
    val onPotties: Color,
    val map: MapPalette,
) {
    /** Background / content colors for an alert level (ALARM uses the base phase). */
    fun forLevel(level: AlertLevel): Pair<Color, Color> = when (level) {
        AlertLevel.NONE -> panel to onPanel
        AlertLevel.CAUTION -> caution to onCaution
        AlertLevel.ADVISORY -> advisory to onAdvisory
        AlertLevel.WARNING -> warning to onWarning
        AlertLevel.ALARM -> alarm to onAlarm
    }
}

private val DayColors = ZepColors(
    night = false,
    panel = Color(0xF2FFFBF2),
    onPanel = Color(0xFF0E0A06),
    onPanelMuted = Color(0xFF4A3C2C),
    caution = Color(0xFFFFA000),
    onCaution = Color(0xFF1A1000),
    advisory = Color(0xFFFFE01A),
    onAdvisory = Color(0xFF1A1600),
    warning = Color(0xFFE01010),
    onWarning = Color(0xFFFFFFFF),
    alarm = Color(0xFFE01010),
    alarmAlt = Color(0xFFFFFFFF),
    onAlarm = Color(0xFFFFFFFF),
    onAlarmAlt = Color(0xFFE01010),
    good = Color(0xFF0E8A3A),
    bad = Color(0xFFC81E1E),
    potties = Color(0xFF1660C8),
    onPotties = Color(0xFFFFFFFF),
    map = MapPalettes.DAY,
)

private val NightColors = ZepColors(
    night = true,
    panel = Color(0xE6140A06),
    onPanel = Color(0xFFE79A6E),
    onPanelMuted = Color(0xFF9C6646),
    caution = Color(0xFFB86A00),
    onCaution = Color(0xFF140A06),
    advisory = Color(0xFF9C8200),
    onAdvisory = Color(0xFF140A06),
    warning = Color(0xFFB81C12),
    onWarning = Color(0xFFFFD6C2),
    alarm = Color(0xFFE0200F),
    alarmAlt = Color(0xFFFFD6C2),
    onAlarm = Color(0xFFFFF0E8),
    onAlarmAlt = Color(0xFFB81C12),
    good = Color(0xFF8E9A3A),
    bad = Color(0xFFE0402A),
    potties = Color(0xFF3A4C80),
    onPotties = Color(0xFFE7C2A8),
    map = MapPalettes.NIGHT,
)

private val DayScheme = lightColorScheme(
    primary = Color(0xFF8A3A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9B8),
    onPrimaryContainer = Color(0xFF2E1100),
    secondary = Color(0xFF5B4330),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBD9C2),
    onSecondaryContainer = Color(0xFF231609),
    tertiary = Color(0xFF1660C8),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFF4E9D4),
    onBackground = Color(0xFF0E0A06),
    surface = Color(0xFFFAF3E5),
    onSurface = Color(0xFF0E0A06),
    surfaceVariant = Color(0xFFE6D6BC),
    onSurfaceVariant = Color(0xFF3B2E20),
    surfaceContainer = Color(0xFFF0E3CB),
    surfaceContainerHigh = Color(0xFFEADBBF),
    surfaceContainerHighest = Color(0xFFE3D2B3),
    surfaceContainerLow = Color(0xFFF6ECDA),
    surfaceContainerLowest = Color(0xFFFFFAF0),
    outline = Color(0xFF7A6650),
    outlineVariant = Color(0xFFC9B597),
    error = Color(0xFFC81E1E),
    onError = Color(0xFFFFFFFF),
)

private val NightScheme = darkColorScheme(
    primary = Color(0xFFD9602E),
    onPrimary = Color(0xFF140A06),
    primaryContainer = Color(0xFF4A1E0C),
    onPrimaryContainer = Color(0xFFF0B08E),
    secondary = Color(0xFFB0805A),
    onSecondary = Color(0xFF140A06),
    secondaryContainer = Color(0xFF34200F),
    onSecondaryContainer = Color(0xFFE0A47E),
    tertiary = Color(0xFF8C9AD0),
    onTertiary = Color(0xFF140A06),
    background = Color(0xFF0B0604),
    onBackground = Color(0xFFE39A72),
    surface = Color(0xFF120B07),
    onSurface = Color(0xFFE39A72),
    surfaceVariant = Color(0xFF26160D),
    onSurfaceVariant = Color(0xFFB57A56),
    surfaceContainer = Color(0xFF1A0F09),
    surfaceContainerHigh = Color(0xFF22140B),
    surfaceContainerHighest = Color(0xFF2A190E),
    surfaceContainerLow = Color(0xFF150C07),
    surfaceContainerLowest = Color(0xFF080402),
    outline = Color(0xFF7A4A30),
    outlineVariant = Color(0xFF3E2516),
    error = Color(0xFFE0402A),
    onError = Color(0xFF140A06),
)

private val LocalZepColors = staticCompositionLocalOf { DayColors }

/** Access to the app colors: `Zep.colors.warning`. */
object Zep {
    val colors: ZepColors
        @Composable get() = LocalZepColors.current
}

private val ZepTypography: Typography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun ZepTheme(night: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (night) NightScheme else DayScheme,
        typography = ZepTypography,
    ) {
        CompositionLocalProvider(LocalZepColors provides if (night) NightColors else DayColors) {
            content()
        }
    }
}
