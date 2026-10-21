package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Palette of the redesign: Material 3, tonal, derived from a “fir” green (seed
// #2E7D5B) with an apricot tertiary. Values taken as is from the mockup's
// “Design system” board, role by role.
//
// **Fixed palette, the author's choice.** The mockup planned it as a fallback for
// Android 12+ dynamic color (Material You); the author preferred it to
// apply everywhere, so that the app looks the same whatever the
// wallpaper. See `MyApplicationTheme`.

// ---------------------------------------------------------------- Light

val LightPrimary = Color(0xFF1F6A4F)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFA8F2CE)
val LightOnPrimaryContainer = Color(0xFF002115)
val LightSecondary = Color(0xFF4C6358)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCEE9DA)
val LightOnSecondaryContainer = Color(0xFF092017)
val LightTertiary = Color(0xFF8A4F24)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDCC4)
val LightOnTertiaryContainer = Color(0xFF311300)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightSurface = Color(0xFFF5FBF5)
val LightOnSurface = Color(0xFF171D1A)
val LightOnSurfaceVariant = Color(0xFF404944)
val LightOutline = Color(0xFF707973)
val LightOutlineVariant = Color(0xFFBFC9C2)
val LightInverseSurface = Color(0xFF2B322E)
val LightInverseOnSurface = Color(0xFFECF2EC)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFEFF5EF)
val LightSurfaceContainer = Color(0xFFE9EFE9)
val LightSurfaceContainerHigh = Color(0xFFE4EAE4)
val LightSurfaceContainerHighest = Color(0xFFDEE4DE)

// ---------------------------------------------------------------- Dark

val DarkPrimary = Color(0xFF8CD5B3)
val DarkOnPrimary = Color(0xFF003826)
val DarkPrimaryContainer = Color(0xFF005139)
val DarkOnPrimaryContainer = Color(0xFFA8F2CE)
val DarkSecondary = Color(0xFFB3CCBF)
val DarkOnSecondary = Color(0xFF1E352A)
val DarkSecondaryContainer = Color(0xFF354B40)
val DarkOnSecondaryContainer = Color(0xFFCEE9DA)
val DarkTertiary = Color(0xFFFFB781)
val DarkOnTertiary = Color(0xFF502400)
val DarkTertiaryContainer = Color(0xFF6D390F)
val DarkOnTertiaryContainer = Color(0xFFFFDCC4)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkSurface = Color(0xFF0F1512)
val DarkOnSurface = Color(0xFFDEE4DE)
val DarkOnSurfaceVariant = Color(0xFFBFC9C2)
val DarkOutline = Color(0xFF89938D)
val DarkOutlineVariant = Color(0xFF404944)
val DarkInverseSurface = Color(0xFFDEE4DE)
val DarkInverseOnSurface = Color(0xFF2B322E)
val DarkSurfaceContainerLowest = Color(0xFF0A0F0D)
val DarkSurfaceContainerLow = Color(0xFF171D1A)
val DarkSurfaceContainer = Color(0xFF1B211E)
val DarkSurfaceContainerHigh = Color(0xFF252B28)
val DarkSurfaceContainerHighest = Color(0xFF303633)

// ------------------------------------------------ Colors outside the Material scheme

/**
 * Color of the recording in progress (“RECORDING” chip, live line).
 * Outside the Material scheme, which has no role for “it is recording”: neither error,
 * which would say something is wrong, nor primary, which would not stand out
 * from the buttons. Hence [LocalRecordingColor], which follows the light or dark theme.
 */
val RecordingLight = Color(0xFFE4572E)
val RecordingDark = Color(0xFFFF8A65)
