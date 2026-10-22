package net.hawyn.vivootahelper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MonetThemeTest {
    @Test fun hexInputAcceptsOnlyOpaqueRgb() {
        assertEquals(0xFF6F58A8.toInt(), parseAccentHex("#6f58a8"))
        assertEquals(0xFF6F58A8.toInt(), parseAccentHex(" 6F58A8 "))
        assertEquals("#000000", formatAccentHex(0))
        assertEquals("#FFFFFF", formatAccentHex(-1))
        listOf("", "#", "FFF", "#12345", "#1234567", "#00123456", "##123456", "12 456", "GG0000", ";bad").forEach {
            assertNull(it, parseAccentHex(it))
        }
    }

    @Test fun everyStyleIsAnActualVariantAndSpecMatchesUpstreamSupport() {
        assertEquals(9, MonetStyle.entries.size)
        for (style in MonetStyle.entries) for (spec in MonetSpec.entries) {
            val scheme = generateMonetScheme(0xFF6750A4.toInt(), false, style, spec)
            assertEquals(style.name, scheme.variant.name)
            val expected = if (spec == MonetSpec.SPEC_2025 && style.supports2025) ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021
            assertEquals("$style / $spec", expected, scheme.specVersion)
        }
        assertEquals(4, MonetStyle.entries.count { it.supports2025 })
    }

    @Test fun everyThemeHasReadableOpaqueCoreRoles() {
        val seeds = listOf(0xFF2867DB, 0xFF43815B, 0xFFFFFF00, 0xFF000000, 0xFFFFFFFF, 0xFFAD477C).map { it.toInt() }
        var cases = 0
        var lowestPrimaryContrast = Double.MAX_VALUE
        for (seed in seeds) for (dark in listOf(false, true)) for (style in MonetStyle.entries) for (spec in MonetSpec.entries) {
            val settings = UiPreferencesState(monetEnabled = true, accentSource = AccentSource.CUSTOM, customAccentArgb = seed, monetStyle = style, monetSpec = spec)
            val colors = buildOtaColors(dark, settings, seed)
            val name = "${formatAccentHex(seed)} $dark $style $spec"
            listOf(colors.primary, colors.onPrimary, colors.primaryContainer, colors.onPrimaryContainer,
                colors.primaryVariant, colors.onPrimaryVariant, colors.secondary, colors.onSecondary,
                colors.secondaryVariant, colors.onSecondaryVariant, colors.secondaryContainer, colors.onSecondaryContainer,
                colors.secondaryContainerVariant, colors.onSecondaryContainerVariant, colors.tertiaryContainer, colors.onTertiaryContainer,
                colors.tertiaryContainerVariant, colors.background, colors.onBackground, colors.onBackgroundVariant,
                colors.surface, colors.onSurface, colors.surfaceVariant, colors.onSurfaceSecondary, colors.onSurfaceVariantSummary,
                colors.onSurfaceVariantActions, colors.surfaceContainer, colors.onSurfaceContainer, colors.onSurfaceContainerVariant,
                colors.surfaceContainerHigh, colors.onSurfaceContainerHigh, colors.surfaceContainerHighest, colors.onSurfaceContainerHighest,
                colors.error, colors.onError, colors.errorContainer, colors.onErrorContainer, colors.outline, colors.dividerLine,
                colors.disabledPrimary, colors.disabledOnPrimary, colors.disabledPrimaryButton, colors.disabledOnPrimaryButton,
                colors.disabledPrimarySlider, colors.disabledSecondary, colors.disabledOnSecondary, colors.disabledSecondaryVariant,
                colors.disabledOnSecondaryVariant, colors.disabledOnSurface, colors.sliderKeyPoint, colors.sliderKeyPointForeground,
                colors.sliderBackground).forEach { assertEquals(name, 1f, it.alpha, .0001f) }
            val primaryContrast = contrast(colors.primary, colors.onPrimary)
            lowestPrimaryContrast = min(lowestPrimaryContrast, primaryContrast)
            assertTrue("primary contrast $name = $primaryContrast", primaryContrast >= 4.45)
            assertTrue("background contrast $name", contrast(colors.background, colors.onBackground) >= 7.0)
            assertTrue("card contrast $name", contrast(colors.surfaceContainer, colors.onSurfaceContainer) >= 7.0)
            assertTrue("summary contrast $name", contrast(colors.surfaceContainer, colors.onSurfaceVariantSummary) >= 4.45)
            cases++
        }
        assertEquals(216, cases)
        println("THEME_MATRIX cases=$cases opaqueRoles=52 lowestPrimaryContrast=$lowestPrimaryContrast")
    }

    @Test fun spec2025ChangesRealColorsForSupportedStyles() {
        MonetStyle.entries.filter { it.supports2025 }.forEach { style ->
            val old = generateMonetScheme(0xFF2867DB.toInt(), false, style, MonetSpec.SPEC_2021)
            val modern = generateMonetScheme(0xFF2867DB.toInt(), false, style, MonetSpec.SPEC_2025)
            assertNotEquals(style.name, listOf(old.primary, old.primaryContainer, old.secondary, old.background, old.surfaceContainer),
                listOf(modern.primary, modern.primaryContainer, modern.secondary, modern.background, modern.surfaceContainer))
        }
    }

    @Test fun stylesAffectPalettesRatherThanOnlyPreferenceLabels() {
        val signatures = MonetStyle.entries.map { style ->
            val s = generateMonetScheme(0xFFAD477C.toInt(), false, style, MonetSpec.SPEC_2021)
            listOf(s.primary, s.primaryContainer, s.secondaryContainer, s.tertiaryContainer, s.surfaceContainer)
        }
        assertTrue("Expected distinct real palettes", signatures.distinct().size >= 8)
    }

    @Test fun monetOffPreservesBaseSurfacesWhileAccentStillWorks() {
        for (dark in listOf(false, true)) {
            val settings = UiPreferencesState(monetEnabled = false)
            val blue = buildOtaColors(dark, settings, 0xFF2867DB.toInt())
            val green = buildOtaColors(dark, settings.copy(monetStyle = MonetStyle.MONOCHROME, monetSpec = MonetSpec.SPEC_2025), 0xFF43815B.toInt())
            assertEquals(blue.background, green.background)
            assertEquals(blue.surfaceContainer, green.surfaceContainer)
            assertEquals(blue.onSurface, green.onSurface)
            assertNotEquals(blue.primary, green.primary)
            assertNotEquals(blue.primaryContainer, green.primaryContainer)
            assertTrue(contrast(green.primary, green.onPrimary) >= 4.45)
            val expectedBackground = if (dark) 0xFF101217.toInt() else 0xFFF4F6FB.toInt()
            assertEquals(expectedBackground, blue.background.toArgb())
        }
    }

    @Test fun heroGradientAndBadgeRetainReadableTextAcrossPalettes() {
        // Audit the homepage's contrast contract, including every point along its gradient.
        var minimum = Double.MAX_VALUE
        var previousMinimum = Double.MAX_VALUE
        var samples = 0
        val seeds = listOf(0xFF2867DB, 0xFF43815B, 0xFFFFFF00, 0xFF000000, 0xFFFFFFFF, 0xFFAD477C).map { it.toInt() }
        for (seed in seeds) for (dark in listOf(false, true)) for (style in MonetStyle.entries) for (spec in MonetSpec.entries) {
            val c = buildOtaColors(dark, UiPreferencesState(monetEnabled = true, monetStyle = style, monetSpec = spec), seed)
            val fg = c.onPrimary
            val weighted = .2126f * fg.red + .7152f * fg.green + .0722f * fg.blue
            val opposite = if (weighted > .5f) Color.Black else Color.White
            val bottom = lerp(c.primary, opposite, .12f)
            val oldBottom = lerp(c.primary, c.background, .16f)
            for (i in 0..20) {
                val bg = lerp(c.primary, bottom, i / 20f)
                val badge = opposite.copy(alpha = .12f).compositeOver(bg)
                val ratio = min(contrast(fg, bg), contrast(fg, badge))
                minimum = min(minimum, ratio)
                assertTrue("Hero $dark $style $spec ${formatAccentHex(seed)} step=$i contrast=$ratio", ratio >= 4.45)
                val oldBg = lerp(c.primary, oldBottom, i / 20f)
                previousMinimum = min(previousMinimum, contrast(fg.copy(alpha = .7f).compositeOver(oldBg), oldBg))
                samples++
            }
        }
        assertEquals(4536, samples)
        println("HERO_CONTRAST samples=$samples minimum=$minimum previousAlphaGradientMinimum=$previousMinimum")
    }

    private fun contrast(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= .04045f) v / 12.92 else ((v + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + .05) / (min(x, y) + .05)
    }
}
