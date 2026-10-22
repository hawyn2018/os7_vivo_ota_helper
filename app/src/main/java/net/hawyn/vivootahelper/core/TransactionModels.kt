package net.hawyn.vivootahelper.core

import java.io.File

enum class Slot(val suffix: String) {
    A("_a"), B("_b");
    val opposite: Slot get() = if (this == A) B else A
    companion object {
        fun parse(value: String): Slot = entries.firstOrNull { it.suffix == value.trim() }
            ?: throw TransactionException("当前槽位不是明确的 _a 或 _b，预检终止")
    }
}

enum class BackupKind { INACTIVE_LK, INACTIVE_VENDOR_BOOT }

/** sha256 is an empty compatibility field; V2 performs no digest computation or comparison. */
data class ImageEvidence(val file: File, val bytes: Long, val sha256: String = "")

data class PartitionEvidence(
    val alias: String,
    /** V2 keeps this API name for UI compatibility; its value is always the by-name alias. */
    val canonicalPath: String,
    val bytes: Long,
    /** Empty in V2: block-device content digests are intentionally never collected. */
    val sha256: String = "",
    val deviceIdentity: String? = null,
)

data class BackupSet(
    val directory: File,
    val currentLk: ImageEvidence,
    val inactiveLk: ImageEvidence,
    val inactiveVendorBoot: ImageEvidence,
    val inactiveScpSnapshot: ImageEvidence,
)

/** Display every target, backup, capacity and native patch result before confirmAndWrite(id). */
data class PreparedTransaction(
    val id: String,
    val currentSlot: Slot,
    val targetSlot: Slot,
    val sourceLk: PartitionEvidence,
    val targetLk: PartitionEvidence,
    val targetVendorBoot: PartitionEvidence,
    val readOnlyScp: PartitionEvidence,
    val backups: BackupSet,
    val patchedVendorBoot: ImageEvidence,
    val nativeReportJson: String,
)

data class RecoveryBackup(
    val id: String,
    val originalCurrentSlot: Slot,
    val targetSlot: Slot,
    val kind: BackupKind,
    val image: ImageEvidence,
    val targetCanonicalPath: String,
    val targetBytes: Long,
)

data class RecoveryPlan(
    val id: String,
    val backup: RecoveryBackup,
    val targetBeforeRestore: PartitionEvidence,
    val rescueBackup: ImageEvidence,
)

data class InterruptedTransaction(val id: String, val state: String, val backupDirectory: File)

data class InspectionSnapshot(
    val currentSlot: Slot,
    val targetSlot: Slot,
    val currentLk: PartitionEvidence,
    val inactiveLk: PartitionEvidence,
    val inactiveVendorBoot: PartitionEvidence,
    val inactiveScp: PartitionEvidence,
    val freeBackupBytes: Long,
    val estimatedRequiredBytes: Long,
)

data class TransactionResult(
    val success: Boolean,
    val operation: String,
    val message: String,
    val targetSlot: Slot,
    /** Per-partition results: OK, FAILED, or NOT_NEEDED. */
    val rollback: Map<String, String>,
    val transcript: String,
    val backupDirectory: File,
)

class TransactionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Called on the worker thread, after the caller starts its foreground service and wake lock. */
fun interface ExecutionLease {
    fun isHeld(): Boolean
}

/** Implement with NativeBridge.patchVendor(input.path, output.path, scp.path, capacity). */
fun interface VendorBootPatcher {
    fun patch(input: File, output: File, scp: File, capacity: Long): String
}

data class ShellResult(val exitCode: Int, val output: String)

interface RootShell {
    /** Internal generated scripts only. Never expose script/path entry to the UI. */
    fun execute(script: String, onOutput: (String) -> Unit = {}): ShellResult
}
