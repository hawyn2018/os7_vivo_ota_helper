package net.hawyn.vivootahelper

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.SideEffect
import net.hawyn.vivootahelper.ui.isOtaDarkTheme
import net.hawyn.vivootahelper.ui.OtaApp
import net.hawyn.vivootahelper.ui.OtaCallbacks
import net.hawyn.vivootahelper.ui.UiPreferences
import net.hawyn.vivootahelper.ui.UiAppearanceCallbacks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val repository get() = (application as SlotApplication).repository
    private lateinit var preferences: UiPreferences
    private var exportKey: String? = null
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use(repository::exportLogs) ?: error("打开导出文件失败")
                }
                repository.showNotice("日志已导出", "文件已保存到你选择的位置。")
            } catch (error: Exception) { repository.showNotice("导出未完成", error.message.orEmpty(), true) }
        }
    }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val key = exportKey
        exportKey = null
        if (uri != null && key != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { repository.exportBackup(key, it) } ?: error("打开备份文件失败")
                }
                repository.showNotice("备份已导出", "镜像、修补报告和事务记录已打包保存。")
            } catch (error: Exception) { repository.showNotice("导出未完成", error.message.orEmpty(), true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        preferences = UiPreferences(this)
        exportKey = savedInstanceState?.getString("exportKey")
        setContent {
            val state = repository.state.collectAsStateWithLifecycle().value
            val appearance = preferences.state.collectAsStateWithLifecycle().value
            val dark = isOtaDarkTheme(appearance.theme)
            SideEffect {
                if (state.busy) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            OtaApp(
                state = state,
                preferences = appearance,
                onGlassChange = preferences::setLiquidGlass,
                onThemeChange = preferences::setTheme,
                appearanceCallbacks = UiAppearanceCallbacks(
                    onMonetChange = preferences::setMonetEnabled,
                    onAccentSourceChange = preferences::setAccentSource,
                    onCustomAccentChange = preferences::setCustomAccentArgb,
                    onMonetStyleChange = preferences::setMonetStyle,
                    onMonetSpecChange = preferences::setMonetSpec,
                ),
                callbacks = OtaCallbacks(
                    onRefresh = { runOperation(repository::refresh) },
                    onPrepare = { runOperation(repository::prepare) },
                    onDiscardPlan = repository::discardPlans,
                    onConfirmWrite = { id -> runOperation { repository.confirmWrite(id) } },
                    onExportLogs = { exportLog.launch("origionos7lk升级助手_操作日志.txt") },
                    onExportBackup = { key -> exportKey = key; exportBackup.launch("origionos7lk升级助手_备份_${key.substringBefore(':')}.zip") },
                    onSelectRestore = { key -> runOperation { repository.selectRestore(key) } },
                    onDiscardRestore = repository::discardPlans,
                    onConfirmRestore = { id -> runOperation { repository.confirmRestore(id) } },
                    onDismissNotice = repository::dismissNotice,
                ),
            )
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        // Start only while this Activity is resumed; recreation never repeats a write or preparation.
        if (repository.claimLaunchInspection()) runOperation(repository::inspectOnLaunch)
    }

    private fun runOperation(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getPreferences(MODE_PRIVATE).getBoolean("notificationRequested", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationRequested", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        action()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("exportKey", exportKey)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        preferences.close()
        super.onDestroy()
    }
}
