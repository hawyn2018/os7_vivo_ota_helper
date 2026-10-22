package net.hawyn.vivootahelper.ui

import androidx.compose.runtime.Immutable

/** Presentation models only. Device and write policy remain in the application layer. */
@Immutable
data class OtaUiState(
    val device: DeviceOverview = DeviceOverview(),
    val summary: UpdateSummary? = null,
    val restoreSummary: RestoreSummary? = null,
    val task: TaskUiState? = null,
    val logs: List<LogLine> = emptyList(),
    val backups: List<BackupItem> = emptyList(),
    val notice: UiNotice? = null,
    val refreshing: Boolean = false,
) {
    val busy: Boolean get() = task?.status == TaskStatus.RUNNING
}

@Immutable
data class DeviceOverview(
    val currentSlot: String = "—",
    val otherSlot: String = "—",
    val root: RootAccess = RootAccess.CHECKING,
    val freeSpaceBytes: Long? = null,
    val checks: List<CheckItem> = emptyList(),
    val checkedAt: String = "",
)

enum class RootAccess { CHECKING, GRANTED, DENIED, UNAVAILABLE }
enum class CheckStatus { PASS, WARNING, FAIL, PENDING }
@Immutable data class CheckItem(val title: String, val detail: String = "", val status: CheckStatus = CheckStatus.PENDING)
@Immutable
data class UpdateSummary(
    val id: String,
    val targetSlot: String,
    val requiredBytes: Long,
    val images: List<ImageChange>,
    val checks: List<CheckItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    val readyToWrite: Boolean = false,
    val backupName: String = "",
)

enum class ImageAction { WRITE, PRESERVE }
@Immutable data class ImageChange(val name: String, val detail: String, val sizeBytes: Long = 0, val action: ImageAction = ImageAction.WRITE)

@Immutable
data class RestoreSummary(
    val id: String,
    val backupName: String,
    val targetSlot: String,
    val images: List<ImageChange>,
    val checks: List<CheckItem> = emptyList(),
    val warnings: List<String> = emptyList(),
    val readyToRestore: Boolean = false,
)

enum class TaskStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED }
@Immutable
data class TaskUiState(
    val id: String,
    val title: String,
    val phase: String,
    val status: TaskStatus = TaskStatus.RUNNING,
    val progress: Float? = null,
    val detail: String = "",
)

enum class LogLevel { INFO, SUCCESS, WARNING, ERROR }
@Immutable data class LogLine(val id: Long, val time: String, val message: String, val level: LogLevel = LogLevel.INFO)
@Immutable data class BackupItem(val id: String, val name: String, val createdAt: String, val slot: String, val sizeBytes: Long, val verified: Boolean)
@Immutable data class UiNotice(val title: String, val message: String, val isError: Boolean = false)

data class OtaCallbacks(
    val onRefresh: () -> Unit = {},
    val onPrepare: () -> Unit = {},
    val onDiscardPlan: () -> Unit = {},
    val onConfirmWrite: (String) -> Unit = {},
    val onExportLogs: () -> Unit = {},
    val onExportBackup: (String) -> Unit = {},
    val onSelectRestore: (String) -> Unit = {},
    val onDiscardRestore: () -> Unit = {},
    val onConfirmRestore: (String) -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)
