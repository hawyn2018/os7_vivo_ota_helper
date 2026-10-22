package net.hawyn.vivootahelper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun isOtaDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun OtaTheme(dark: Boolean, preferences: UiPreferencesState, content: @Composable () -> Unit) {
    val seed = rememberOtaAccentArgb(preferences)
    val colors = remember(dark, seed, preferences.monetEnabled, preferences.monetStyle, preferences.monetSpec) {
        buildOtaColors(dark, preferences, seed)
    }
    MiuixTheme(colors = colors, content = content)
}

/** Reads the platform's public accent resource; it does not infer a wallpaper's original seed. */
@Composable
internal fun rememberOtaAccentArgb(preferences: UiPreferencesState): Int {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration, preferences.accentSource, preferences.customAccentArgb) {
        if (preferences.accentSource == AccentSource.CUSTOM) preferences.customAccentArgb or 0xFF000000.toInt()
        else runCatching { resources.getColor(android.R.color.system_accent1_500, null) }
            .getOrDefault(0xFF2867DB.toInt()) or 0xFF000000.toInt()
    }
}

/** MaterialKolor 6.0.0 (MIT), generated upstream MCU algorithms (Apache-2.0).
 * Source and license evidence: verification/theme/. No Material3 UI dependency is used.
 */
internal fun generateMonetScheme(seed: Int, dark: Boolean, style: MonetStyle, spec: MonetSpec): DynamicScheme {
    val source = Hct.fromInt(seed or 0xFF000000.toInt())
    val standard = when (effectiveMonetSpec(style, spec)) {
        MonetSpec.SPEC_2021 -> ColorSpec.SpecVersion.SPEC_2021
        MonetSpec.SPEC_2025 -> ColorSpec.SpecVersion.SPEC_2025
    }
    return when (style) {
        MonetStyle.TONAL_SPOT -> SchemeTonalSpot(source, dark, 0.0, standard)
        MonetStyle.NEUTRAL -> SchemeNeutral(source, dark, 0.0, standard)
        MonetStyle.VIBRANT -> SchemeVibrant(source, dark, 0.0, standard)
        MonetStyle.EXPRESSIVE -> SchemeExpressive(source, dark, 0.0, standard)
        MonetStyle.RAINBOW -> SchemeRainbow(source, dark, 0.0, standard)
        MonetStyle.FRUIT_SALAD -> SchemeFruitSalad(source, dark, 0.0, standard)
        MonetStyle.FIDELITY -> SchemeFidelity(source, dark, 0.0, standard)
        MonetStyle.CONTENT -> SchemeContent(source, dark, 0.0, standard)
        MonetStyle.MONOCHROME -> SchemeMonochrome(source, dark, 0.0, standard)
    }
}

/** Pure mapping, also exercised on the host for both themes and every supported style/spec. */
internal fun buildOtaColors(dark: Boolean, preferences: UiPreferencesState, resolvedSeed: Int): Colors {
    val style = if (preferences.monetEnabled) preferences.monetStyle else MonetStyle.TONAL_SPOT
    val spec = if (preferences.monetEnabled) preferences.monetSpec else MonetSpec.SPEC_2021
    val scheme = generateMonetScheme(resolvedSeed, dark, style, spec)
    val base = standardColors(dark)
    fun color(argb: Int) = Color(argb)
    val surface = if (preferences.monetEnabled) color(scheme.surfaceContainer) else base.surfaceContainer
    val text = if (preferences.monetEnabled) color(scheme.onSurface) else base.onSurface
    val disabled = text.copy(alpha = .12f).compositeOver(surface)
    val disabledText = text.copy(alpha = .38f).compositeOver(disabled)

    // Update every accent-dependent Miuix role, including pressed/disabled/slider roles.
    val accented = base.copy(
        primary = color(scheme.primary), onPrimary = color(scheme.onPrimary),
        primaryVariant = color(scheme.primaryContainer), onPrimaryVariant = color(scheme.onPrimaryContainer),
        primaryContainer = color(scheme.primaryContainer), onPrimaryContainer = color(scheme.onPrimaryContainer),
        disabledPrimary = disabled, disabledOnPrimary = disabledText,
        disabledPrimaryButton = disabled, disabledOnPrimaryButton = disabledText,
        disabledPrimarySlider = disabled,
        tertiaryContainer = color(scheme.tertiaryContainer), onTertiaryContainer = color(scheme.onTertiaryContainer),
        tertiaryContainerVariant = color(scheme.tertiaryContainer),
        sliderKeyPoint = color(scheme.primary).copy(alpha = .26f).compositeOver(surface),
        sliderKeyPointForeground = color(scheme.primary),
    )
    if (!preferences.monetEnabled) return accented

    // Miuix's secondary/variant names describe neutral controls; pair every fill with
    // the matching Material foreground instead of copying Material's strong secondary.
    return accented.copy(
        error = color(scheme.error), onError = color(scheme.onError),
        errorContainer = color(scheme.errorContainer), onErrorContainer = color(scheme.onErrorContainer),
        secondary = color(scheme.secondaryContainer), onSecondary = color(scheme.onSecondaryContainer),
        secondaryVariant = color(scheme.surfaceContainerHigh), onSecondaryVariant = color(scheme.onSurface),
        disabledSecondary = disabled, disabledOnSecondary = disabledText,
        disabledSecondaryVariant = disabled, disabledOnSecondaryVariant = disabledText,
        secondaryContainer = color(scheme.secondaryContainer), onSecondaryContainer = color(scheme.onSecondaryContainer),
        secondaryContainerVariant = color(scheme.surfaceContainerHigh), onSecondaryContainerVariant = color(scheme.onSurfaceVariant),
        background = color(scheme.background), onBackground = color(scheme.onBackground),
        onBackgroundVariant = color(scheme.onSurfaceVariant),
        surface = color(scheme.surface), onSurface = color(scheme.onSurface),
        surfaceVariant = color(scheme.surfaceVariant), onSurfaceSecondary = color(scheme.onSurfaceVariant),
        onSurfaceVariantSummary = color(scheme.onSurfaceVariant), onSurfaceVariantActions = color(scheme.onSurfaceVariant),
        disabledOnSurface = disabledText,
        surfaceContainer = surface, onSurfaceContainer = text, onSurfaceContainerVariant = color(scheme.onSurfaceVariant),
        surfaceContainerHigh = color(scheme.surfaceContainerHigh), onSurfaceContainerHigh = text,
        surfaceContainerHighest = color(scheme.surfaceContainerHighest), onSurfaceContainerHighest = text,
        outline = color(scheme.outline), dividerLine = color(scheme.outlineVariant),
        windowDimming = color(scheme.scrim).copy(alpha = if (dark) .6f else .32f),
        sliderBackground = color(scheme.surfaceContainerHighest),
    )
}

private fun standardColors(dark: Boolean): Colors = if (dark) darkColorScheme(
        primary = Color(0xFF91B9FF), onPrimary = Color(0xFF102C53),
        surface = Color(0xFF101217), background = Color(0xFF101217),
        surfaceContainer = Color(0xFF1C2029), surfaceVariant = Color(0xFF1C2029),
        onSurface = Color(0xFFF0F2F8), onBackground = Color(0xFFF0F2F8),
        onSurfaceContainer = Color(0xFFF0F2F8),
    ) else lightColorScheme(
        primary = Color(0xFF2867DB), onPrimary = Color.White,
        surface = Color(0xFFF4F6FB), background = Color(0xFFF4F6FB),
        surfaceContainer = Color.White, surfaceVariant = Color.White,
        onSurface = Color(0xFF202838), onBackground = Color(0xFF202838),
        onSurfaceContainer = Color(0xFF202838),
    )
