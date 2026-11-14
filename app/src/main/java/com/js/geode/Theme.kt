package com.js.geode

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Monochrome design system. JS's rule: black / white / neutral shades
 * only — no accent colour at all. The generated design system had teal
 * #0D9488 and orange #EA580C; both are explicitly overridden here.
 *
 * Light: bg #FFFFFF, surface #FAFAFA, card #FFFFFF, border #E5E7EB,
 * text #111111, secondary #6B7280, muted #9CA3AF.
 * Dark:  bg #0A0A0A, surface #111111, card #161616, border #262626,
 * text #FAFAFA, secondary #A3A3A3, muted #737373.
 * Primary button: black bg + white text (light), white bg + black text (dark).
 */
object Mono {
    // Light
    val LightBg = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFAFAFA)
    val LightCard = Color(0xFFFFFFFF)
    val LightBorder = Color(0xFFE5E7EB)
    val LightText = Color(0xFF111111)
    val LightSecondary = Color(0xFF6B7280)
    val LightMuted = Color(0xFF737373)
    // Dark
    val DarkBg = Color(0xFF0A0A0A)
    val DarkSurface = Color(0xFF111111)
    val DarkCard = Color(0xFF161616)
    val DarkBorder = Color(0xFF262626)
    val DarkText = Color(0xFFFAFAFA)
    val DarkSecondary = Color(0xFFA3A3A3)
    val DarkMuted = Color(0xFF8F8F8F)
}

val JakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
    Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold)
)

/** Extra neutral tokens beyond Material3's colour scheme. */
data class NeutralColors(
    val surface: Color,
    val card: Color,
    val border: Color,
    val secondaryText: Color,
    val mutedText: Color,
    /** Primary button background (inverted vs background). */
    val primaryButton: Color,
    val onPrimaryButton: Color,
    /** Disabled primary-button text: must stay >= 4.5:1 on [border]. */
    val disabledButtonText: Color
)

val LocalNeutral = staticCompositionLocalOf {
    NeutralColors(
        surface = Mono.LightSurface, card = Mono.LightCard, border = Mono.LightBorder,
        secondaryText = Mono.LightSecondary, mutedText = Mono.LightMuted,
        primaryButton = Mono.LightText, onPrimaryButton = Mono.LightBg,
        disabledButtonText = Color(0xFF525252)
    )
}

private val LightScheme = lightColorScheme(
    primary = Mono.LightText,
    onPrimary = Mono.LightBg,
    background = Mono.LightBg,
    onBackground = Mono.LightText,
    surface = Mono.LightSurface,
    onSurface = Mono.LightText,
    surfaceVariant = Mono.LightSurface,
    onSurfaceVariant = Mono.LightSecondary,
    outline = Mono.LightBorder,
    outlineVariant = Mono.LightBorder,
    secondary = Mono.LightSecondary,
    onSecondary = Mono.LightBg,
    error = Mono.LightText,
    onError = Mono.LightBg
)

private val DarkScheme = darkColorScheme(
    primary = Mono.DarkText,
    onPrimary = Mono.DarkBg,
    background = Mono.DarkBg,
    onBackground = Mono.DarkText,
    surface = Mono.DarkSurface,
    onSurface = Mono.DarkText,
    surfaceVariant = Mono.DarkSurface,
    onSurfaceVariant = Mono.DarkSecondary,
    outline = Mono.DarkBorder,
    outlineVariant = Mono.DarkBorder,
    secondary = Mono.DarkSecondary,
    onSecondary = Mono.DarkBg,
    error = Mono.DarkText,
    onError = Mono.DarkBg
)

@Composable
fun GeodeTheme(
    themePref: String = "system",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themePref) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val scheme = if (dark) DarkScheme else LightScheme
    val neutral = if (dark) {
        NeutralColors(
            surface = Mono.DarkSurface, card = Mono.DarkCard, border = Mono.DarkBorder,
            secondaryText = Mono.DarkSecondary, mutedText = Mono.DarkMuted,
            primaryButton = Mono.DarkText, onPrimaryButton = Mono.DarkBg,
            disabledButtonText = Mono.DarkSecondary
        )
    } else {
        NeutralColors(
            surface = Mono.LightSurface, card = Mono.LightCard, border = Mono.LightBorder,
            secondaryText = Mono.LightSecondary, mutedText = Mono.LightMuted,
            primaryButton = Mono.LightText, onPrimaryButton = Mono.LightBg,
            disabledButtonText = Color(0xFF525252)
        )
    }
    val typography = androidx.compose.material3.Typography(
        // v0.1.2: styles carry NO colour on purpose. v0.1.1 put
        // color = scheme.onBackground on every style, and a TextStyle
        // colour overrides a Button's LocalContentColor — so the
        // PrimaryButton label took onBackground onto a container of the
        // same colour (white-on-white dark, black-on-black light, 1:1).
        // Text now inherits LocalContentColor, which GeodeTheme provides
        // as scheme.onBackground below; buttons/chips set their label
        // colour explicitly. (v0.1.0's black-on-black came from the
        // LocalContentColor default being black with no provider.)
        displayLarge = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp),
        headlineLarge = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 32.sp),
        headlineMedium = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp),
        titleLarge = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 23.sp),
        titleSmall = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = TextStyle(fontFamily = JakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp)
    )
    CompositionLocalProvider(
        LocalNeutral provides neutral,
        // Belt and braces for the dark-mode bug: the ambient content
        // colour is the theme text colour, so even a bare Text() can
        // never render black-on-black again.
        LocalContentColor provides scheme.onBackground
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography,
            content = content
        )
    }
}

/** 8dp spacing rhythm. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Section label style: 12sp semibold, uppercase, letter-spaced. */
@Composable
fun sectionLabelStyle(): TextStyle = TextStyle(
    fontFamily = JakartaSans,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    letterSpacing = 0.8.sp,
    color = LocalNeutral.current.secondaryText
)
