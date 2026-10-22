package net.hawyn.vivootahelper.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class UiPreferencesTest {
    @Test fun appearanceRoundTripsWithoutReplacingOriginalThemeAndGlass() {
        val context = MemoryContext()
        val first = UiPreferences(context)
        assertFalse(first.state.value.monetEnabled)
        assertEquals(AccentSource.SYSTEM, first.state.value.accentSource)
        first.setTheme(ThemeMode.DARK)
        first.setLiquidGlass(false)
        first.setMonetEnabled(true)
        first.setCustomAccentArgb(0x006F58A8)
        first.setMonetStyle(MonetStyle.EXPRESSIVE)
        first.setMonetSpec(MonetSpec.SPEC_2025)
        val expected = UiPreferencesState(false, ThemeMode.DARK, true, AccentSource.CUSTOM, 0xFF6F58A8.toInt(), MonetStyle.EXPRESSIVE, MonetSpec.SPEC_2025)
        assertEquals(expected, first.state.value)
        first.close()
        assertEquals(0, context.storage.listenerCount)
        val restarted = UiPreferences(context)
        assertEquals(expected, restarted.state.value)
        restarted.setAccentSource(AccentSource.SYSTEM)
        assertEquals(0xFF6F58A8.toInt(), restarted.state.value.customAccentArgb)
        restarted.close()
    }

    @Test fun unsupportedSpecIsPersistedAs2021InsteadOfSilentlyMislabeled() {
        val context = MemoryContext()
        val preferences = UiPreferences(context)
        preferences.setMonetStyle(MonetStyle.VIBRANT)
        preferences.setMonetSpec(MonetSpec.SPEC_2025)
        assertEquals(MonetSpec.SPEC_2025, preferences.state.value.monetSpec)
        preferences.setMonetStyle(MonetStyle.FIDELITY)
        assertEquals(MonetSpec.SPEC_2021, preferences.state.value.monetSpec)
        assertEquals("SPEC_2021", context.storage.getString("monet_spec", null))
        preferences.setMonetSpec(MonetSpec.SPEC_2025)
        assertEquals(MonetSpec.SPEC_2021, preferences.state.value.monetSpec)
        preferences.close()
        assertEquals(MonetSpec.SPEC_2021, UiPreferences(context).state.value.monetSpec)
    }

    @Test fun unknownStoredEnumValuesHaveStableDefaults() {
        val context = MemoryContext()
        context.storage.edit().putString("theme", "old-unknown").putString("accent_source", "bad")
            .putString("monet_style", "bad").putString("monet_spec", "SPEC_2026").apply()
        val preferences = UiPreferences(context)
        assertEquals(ThemeMode.SYSTEM, preferences.state.value.theme)
        assertEquals(AccentSource.SYSTEM, preferences.state.value.accentSource)
        assertEquals(MonetStyle.TONAL_SPOT, preferences.state.value.monetStyle)
        assertEquals(MonetSpec.SPEC_2021, preferences.state.value.monetSpec)
        preferences.close()
    }

    private class MemoryContext : ContextWrapper(null) {
        val storage = MemoryPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = storage
    }

    /** A synchronous in-memory SharedPreferences contract, no Android resource or device use. */
    private class MemoryPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()
        private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
        val listenerCount get() = listeners.size
        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun contains(key: String?): Boolean = key in values
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners += listener }
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) { if (listener != null) listeners -= listener }
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var cleared = false
            override fun putString(key: String?, value: String?) = apply { changes[requireNotNull(key)] = value }
            override fun putStringSet(key: String?, value: MutableSet<String>?) = apply { changes[requireNotNull(key)] = value?.toSet() }
            override fun putInt(key: String?, value: Int) = apply { changes[requireNotNull(key)] = value }
            override fun putLong(key: String?, value: Long) = apply { changes[requireNotNull(key)] = value }
            override fun putFloat(key: String?, value: Float) = apply { changes[requireNotNull(key)] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { changes[requireNotNull(key)] = value }
            override fun remove(key: String?) = apply { changes[requireNotNull(key)] = null }
            override fun clear() = apply { cleared = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (cleared) values.clear()
                changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                changes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@MemoryPreferences, key) } }
            }
        }
    }
}
