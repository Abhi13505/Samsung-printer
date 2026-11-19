package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PaperStudioColorScheme = lightColorScheme(
    primary = InkBlack,
    onPrimary = SheetWhite,
    primaryContainer = CobaltSoft,
    onPrimaryContainer = CobaltAccent,
    secondary = CobaltAccent,
    onSecondary = SheetWhite,
    secondaryContainer = WarmStone,
    onSecondaryContainer = InkBlack,
    tertiary = EmeraldReady,
    onTertiary = SheetWhite,
    tertiaryContainer = EmeraldSoft,
    onTertiaryContainer = EmeraldReady,
    background = PaperWhite,
    onBackground = InkBlack,
    surface = SheetWhite,
    onSurface = InkBlack,
    surfaceVariant = WarmStone,
    onSurfaceVariant = GraphiteMuted,
    surfaceContainerLowest = SheetWhite,
    surfaceContainerLow = PaperWhite,
    surfaceContainer = WarmStone,
    surfaceContainerHigh = StoneSurface,
    outline = HairlineStone,
    outlineVariant = HairlineStone,
    error = CrimsonError,
    onError = SheetWhite,
    errorContainer = CrimsonSoft,
    onErrorContainer = CrimsonError
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun SCXPrintTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = PaperStudioColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
