package net.hawyn.vivootahelper.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }
enum class AccentSource { SYSTEM, CUSTOM }

enum class MonetStyle(val label: String, val description: String, val supports2025: Boolean = false) {
    TONAL_SPOT("柔和", "低饱和，柔和统一", true),
    NEUTRAL("中性", "接近灰阶，保留少量色彩", true),
    VIBRANT("鲜艳", "更鲜明的主题色", true),
    EXPRESSIVE("表现", "用不同色相营造活力", true),
    RAINBOW("彩虹", "明亮配色与中性背景"),
    FRUIT_SALAD("果缤纷", "调整色相，呈现轻快配色"),
    FIDELITY("忠实", "尽量保留所选颜色"),
    CONTENT("内容", "围绕所选颜色协调配色"),
    MONOCHROME("单色", "纯黑白与灰阶"),
}

enum class MonetSpec(val label: String) { SPEC_2021("2021"), SPEC_2025("2025") }

data class UiPreferencesState(
    val liquidGlass: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val monetEnabled: Boolean = false,
    val accentSource: AccentSource = AccentSource.SYSTEM,
    val customAccentArgb: Int = 0xFF2867DB.toInt(),
    val monetStyle: MonetStyle = MonetStyle.TONAL_SPOT,
    val monetSpec: MonetSpec = MonetSpec.SPEC_2021,
)

data class UiAppearanceCallbacks(
    val onMonetChange: (Boolean) -> Unit = {},
    val onAccentSourceChange: (AccentSource) -> Unit = {},
    val onCustomAccentChange: (Int) -> Unit = {},
    val onMonetStyleChange: (MonetStyle) -> Unit = {},
    val onMonetSpecChange: (MonetSpec) -> Unit = {},
)

/** Upstream only defines the 2025 spec for these four variants. */
internal fun effectiveMonetSpec(style: MonetStyle, spec: MonetSpec): MonetSpec =
    if (spec == MonetSpec.SPEC_2025 && !style.supports2025) MonetSpec.SPEC_2021 else spec

/** Accept exactly RGB hex, with an optional leading #; theme seed colors are opaque. */
internal fun parseAccentHex(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (!hex.matches(Regex("[0-9a-fA-F]{6}"))) return null
    return hex.toLong(16).toInt() or 0xFF000000.toInt()
}

internal fun formatAccentHex(argb: Int): String = "#%06X".format(java.util.Locale.ROOT, argb and 0xFFFFFF)

/** Small, application-scoped preferences. No device/root operations happen here. */
class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T =
        runCatching { enumValueOf<T>(preferences.getString(key, null).orEmpty()) }.getOrDefault(fallback)

    private fun read(): UiPreferencesState {
        val style = readEnum("monet_style", MonetStyle.TONAL_SPOT)
        return UiPreferencesState(
            liquidGlass = preferences.getBoolean("liquid_glass", true),
            theme = readEnum("theme", ThemeMode.SYSTEM),
            monetEnabled = preferences.getBoolean("monet_enabled", false),
            accentSource = readEnum("accent_source", AccentSource.SYSTEM),
            customAccentArgb = preferences.getInt("custom_accent_argb", 0xFF2867DB.toInt()) or 0xFF000000.toInt(),
            monetStyle = style,
            monetSpec = effectiveMonetSpec(style, readEnum("monet_spec", MonetSpec.SPEC_2021)),
        )
    }
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<UiPreferencesState> = mutableState.asStateFlow()
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableState.value = read() }

    init { preferences.registerOnSharedPreferenceChangeListener(listener) }

    fun setLiquidGlass(enabled: Boolean) {
        preferences.edit().putBoolean("liquid_glass", enabled).apply()
        mutableState.value = read()
    }

    fun setTheme(theme: ThemeMode) {
        preferences.edit().putString("theme", theme.name).apply()
        mutableState.value = read()
    }

    fun setMonetEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("monet_enabled", enabled).apply()
        mutableState.value = read()
    }

    fun setAccentSource(source: AccentSource) {
        preferences.edit().putString("accent_source", source.name).apply()
        mutableState.value = read()
    }

    fun setCustomAccentArgb(argb: Int) {
        preferences.edit().putInt("custom_accent_argb", argb or 0xFF000000.toInt())
            .putString("accent_source", AccentSource.CUSTOM.name).apply()
        mutableState.value = read()
    }

    fun setMonetStyle(style: MonetStyle) {
        preferences.edit().putString("monet_style", style.name)
            .putString("monet_spec", effectiveMonetSpec(style, state.value.monetSpec).name).apply()
        mutableState.value = read()
    }

    fun setMonetSpec(spec: MonetSpec) {
        preferences.edit().putString("monet_spec", effectiveMonetSpec(state.value.monetStyle, spec).name).apply()
        mutableState.value = read()
    }

    fun close() { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
}
