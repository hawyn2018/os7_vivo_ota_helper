package net.hawyn.vivootahelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import net.hawyn.vivootahelper.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Debug-only UI fixture: no service, RootShell, JNI or partition access. */
class PreparedPromptFixtureActivity : ComponentActivity() {
    @Volatile var confirmedWrites = 0
        private set
    private var state by mutableStateOf(OtaUiState(
        device = DeviceOverview("_b", "_a", RootAccess.GRANTED, 4L * 1024 * 1024 * 1024),
    ))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OtaApp(state, OtaCallbacks(
                onPrepare = ::prepareFixture,
                onConfirmWrite = { confirmedWrites++ },
            ), UiPreferencesState(), {}, {}, UiAppearanceCallbacks())
        }
    }

    private fun prepareFixture() {
        state = state.copy(task = TaskUiState("prepare-fixture", "准备", "普通 UI 数据模拟"))
        lifecycleScope.launch {
            delay(400)
            state = state.copy(summary = UpdateSummary(
                "fixture-plan", "_a", 150994944,
                listOf(ImageChange("lk_a", "模拟", 16777216), ImageChange("vendor_boot_a", "模拟", 134217728)),
                readyToWrite = true, backupName = "fixture-backup",
            ))
            delay(200)
            state = state.copy(task = state.task?.copy(status = TaskStatus.SUCCEEDED))
        }
    }
}
