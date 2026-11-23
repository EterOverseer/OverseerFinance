package com.example.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// OverseerFinance Premium Dark Navy & Emerald Palette
val NavyVoid = Color(0xFF070D17)
val NavyDeep = Color(0xFF0B1320)
val NavySurface = Color(0xFF111C2E)
val NavyElevated = Color(0xFF17243A)
val NavyBorder = Color(0xFF23334D)

val EmeraldPrimary = Color(0xFF34D399)
val EmeraldDark = Color(0xFF10B981)
val EmeraldGlow = Color(0xFFA7F3D0)
val LimeAccent = Color(0xFF84CC16)

val TextPrimaryDark = Color(0xFFF1F5F9)
val TextSecondaryDark = Color(0xFF94A3B8)
val TextMutedDark = Color(0xFF64748B)

val IncomeEmerald = Color(0xFF34D399)
val ExpenseCoral = Color(0xFFFB7185)
val TransferCyan = Color(0xFF38BDF8)
val WarningAmber = Color(0xFFFBBF24)
val PurpleHighlight = Color(0xFFA78BFA)

// Clean High-Contrast Light Mode Palette
val LightCanvas = Color(0xFFF3F6FA)
val LightCard = Color(0xFFFFFFFF)
val LightElevated = Color(0xFFE9EFF7)
val LightEmerald = Color(0xFF059669)
val TextPrimaryLight = Color(0xFF0F172A)
val TextSecondaryLight = Color(0xFF475569)

val OverseerDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = NavyVoid,
    primaryContainer = Color(0xFF0F3D33),
    onPrimaryContainer = EmeraldGlow,
    secondary = TransferCyan,
    onSecondary = NavyVoid,
    secondaryContainer = Color(0xFF0E3047),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = WarningAmber,
    onTertiary = NavyVoid,
    tertiaryContainer = Color(0xFF3F2E0B),
    onTertiaryContainer = Color(0xFFFDE68A),
    background = NavyDeep,
    onBackground = TextPrimaryDark,
    surface = NavySurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = NavyElevated,
    onSurfaceVariant = TextSecondaryDark,
    outline = NavyBorder,
    outlineVariant = Color(0xFF1A283F),
    error = ExpenseCoral,
    onError = NavyVoid,
    errorContainer = Color(0xFF4C1120),
    onErrorContainer = Color(0xFFFECDD3)
)

val OverseerLightColorScheme = lightColorScheme(
    primary = LightEmerald,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightCard,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightElevated,
    onSurfaceVariant = TextSecondaryLight,
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF881337)
)
