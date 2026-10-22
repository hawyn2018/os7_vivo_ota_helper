package net.hawyn.vivootahelper

import android.content.Context
import android.content.Intent
import net.hawyn.vivootahelper.core.*
import net.hawyn.vivootahelper.ui.*
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** Application-scoped UI adapter. The service owns execution; the activity owns no root process. */
class OtaRepository(private val context: Context) {
    sealed interface Command {
        data class Inspect(val automatic: Boolean = false) : Command
        data object Prepare : Command
        data class Write(val id: String) : Command
        data class PreviewRestore(val backup: RecoveryBackup) : Command
        data class Restore(val id: String) : Command
    }
    private val busy = AtomicBoolean()
    private val launchInspectionClaimed = AtomicBoolean()
    private val exportLock = AtomicBoolean()
    private val logIds = AtomicLong()
    private val backupRoot = File(context.filesDir, "backups")
    private val logFile = File(context.filesDir, "activity.log")
    private val mutableState = MutableStateFlow(OtaUiState(device = DeviceOverview(root = RootAccess.UNAVAILABLE)))
    val state: StateFlow<OtaUiState> = mutableState.asStateFlow()
    private var pending: Pair<String, Command>? = null
    @Volatile private var prepared: PreparedTransaction? = null
    @Volatile private var recovery: RecoveryPlan? = null
    @Volatile private var recoveries: Map<String, RecoveryBackup> = emptyMap()

    init { reloadBackups() }

    private fun manager(lease: () -> Boolean) = OtaTransactionManager(
        backupRoot, SuRootShell(),
        VendorBootPatcher { input, output, scp, capacity -> NativeBridge.patchVendor(input.path, output.path, scp.path, capacity) },
        ExecutionLease(lease), ::progress,
    )

    fun claimLaunchInspection(): Boolean = launchInspectionClaimed.compareAndSet(false, true)
    fun inspectOnLaunch() = submit(Command.Inspect(automatic = true), "自动检测 Root 与槽位")
    fun refresh() = submit(Command.Inspect(), "读取槽位与 root 状态")
    fun prepare() = submit(Command.Prepare, "备份并修补对槽镜像")
    fun confirmWrite(id: String) {
        if (prepared?.id != id || state.value.summary?.readyToWrite != true) return
        submit(Command.Write(id), "写入对槽镜像")
    }
    fun selectRestore(key: String) {
        recoveries[key]?.let { submit(Command.PreviewRestore(it), "准备恢复预览") }
    }
    fun confirmRestore(id: String) {
        if (recovery?.id != id || state.value.restoreSummary?.readyToRestore != true) return
        submit(Command.Restore(id), "恢复对槽备份")
    }

    @Synchronized private fun submit(command: Command, title: String) {
        if (exportLock.get() || !busy.compareAndSet(false, true)) return
        val token = UUID.randomUUID().toString()
        pending = token to command
        mutableState.update { it.copy(
            task = TaskUiState(token, title, "启动前台服务"), refreshing = command is Command.Inspect, notice = null,
            device = if (command is Command.Inspect) it.device.copy(root = RootAccess.CHECKING) else it.device,
        ) }
        try { context.startForegroundService(Intent(context, OperationService::class.java).putExtra("token", token)) }
        catch (error: Exception) { pending = null; startFailed(error) }
    }

    @Synchronized fun takeCommand(token: String?): Command? {
        val value = pending ?: return null
        if (token != value.first) return null
        pending = null
        return value.second
    }

    fun startFailed(error: Exception) {
        fail(error)
        busy.set(false)
    }

    /** Runs exclusively on OperationService's worker, with its foreground notification and wake lock. */
    fun execute(command: Command, lease: () -> Boolean) {
        try {
            val core = manager(lease)
            when (command) {
                is Command.Inspect -> {
                    val info = core.inspect()
                    mutableState.update { it.copy(device = DeviceOverview(
                        currentSlot = info.currentSlot.suffix, otherSlot = info.targetSlot.suffix,
                        root = RootAccess.GRANTED, freeSpaceBytes = info.freeBackupBytes,
                        checks = listOf(CheckItem("Root 与分区工具", "root、dd、blockdev、stat、flock 已通过", CheckStatus.PASS), CheckItem("槽位与块设备", "目标均为对槽，路径和容量检查通过", CheckStatus.PASS)),
                        checkedAt = time("MM-dd HH:mm"),
                    )) }
                    complete("只读检查通过")
                }
                Command.Prepare -> {
                    clearPlans(core)
                    val plan = core.prepare()
                    prepared = plan
                    val report = JSONObject(plan.nativeReportJson)
                    val warnings = mutableListOf(
                        "沿用此前实测成功的 V3 兼容方式；每次新 OTA 仍需验证启动和实际使用。",
                        "请在 OTA 写入对槽完成后操作，并单独完成 KernelSU 的对槽 init_boot 修补。",
                        "保留原有 AVB 元数据；需继续使用当前可引导修补镜像的 LK。",
                    )
                    val nativeWarnings = report.optJSONArray("warnings")
                    for (i in 0 until (nativeWarnings?.length() ?: 0)) log(nativeWarnings!!.getString(i), LogLevel.WARNING)
                    val checks = listOf(
                        CheckItem("完整备份", plan.backups.directory.name, CheckStatus.PASS),
                        CheckItem("镜像与修补报告", "容量与修补结构检查通过；未写入分区", CheckStatus.PASS),
                        CheckItem("SCP 兼容参数", "来自对槽 SCP 镜像，保留系统侧完整表", CheckStatus.PASS),
                        CheckItem("新版启动检查兼容", "全部设备树 bt_log 字段修补检查通过", CheckStatus.PASS),
                    )
                    mutableState.update { it.copy(
                        device = it.device.copy(root = RootAccess.GRANTED, currentSlot = plan.currentSlot.suffix, otherSlot = plan.targetSlot.suffix, freeSpaceBytes = backupRoot.usableSpace),
                        summary = UpdateSummary(plan.id, plan.targetSlot.suffix, plan.backups.currentLk.bytes + plan.patchedVendorBoot.bytes,
                            listOf(ImageChange("lk${plan.targetSlot.suffix}", "复制当前槽 ${plan.currentSlot.suffix} 的 LK", plan.backups.currentLk.bytes),
                                ImageChange("vendor_boot${plan.targetSlot.suffix}", "显示、SCP 与新版启动检查兼容；移除 vr / vklp 和索引", plan.patchedVendorBoot.bytes),
                                ImageChange("init_boot", "init_boot请使用kernelsu自行刷写另一槽位", action = ImageAction.PRESERVE)), checks, warnings, true, plan.backups.directory.name),
                        restoreSummary = null,
                    ) }
                    complete("准备完成，等待确认写入")
                }
                is Command.Write -> {
                    if (prepared?.id != command.id) error("准备记录已失效，请重新准备")
                    mutableState.update { it.copy(summary = it.summary?.copy(readyToWrite = false)) }
                    result(core.confirmAndWrite(command.id))
                    prepared = null
                    mutableState.update { it.copy(summary = null) }
                }
                is Command.PreviewRestore -> {
                    clearPlans(core)
                    val plan = core.prepareRestore(command.backup.id, command.backup.kind)
                    recovery = plan
                    val name = if (plan.backup.kind == BackupKind.INACTIVE_LK) "lk" else "vendor_boot"
                    mutableState.update { it.copy(restoreSummary = RestoreSummary(
                        plan.id, "${plan.backup.id.take(8)} / $name", plan.backup.targetSlot.suffix,
                        listOf(ImageChange("$name${plan.backup.targetSlot.suffix}", "恢复原始备份", plan.backup.image.bytes)),
                        listOf(CheckItem("恢复前备份", "已保存目前目标分区，恢复失败时用于回滚", CheckStatus.PASS)),
                        listOf("只恢复所选对槽分区；确认前不会写入。"), true,
                    )) }
                    complete("恢复预览已就绪")
                }
                is Command.Restore -> {
                    if (recovery?.id != command.id) error("恢复预览已失效")
                    mutableState.update { it.copy(restoreSummary = it.restoreSummary?.copy(readyToRestore = false)) }
                    result(core.confirmRestore(command.id))
                    recovery = null
                    mutableState.update { it.copy(restoreSummary = null) }
                }
            }
        } catch (error: Throwable) {
            // Native allocation/link errors must also leave the UI and the service in a completed state.
            fail(error, showNotice = command !is Command.Inspect || !command.automatic)
        } finally {
            reloadBackups()
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    fun executionFinished() { busy.set(false) }

    fun discardPlans() {
        if (busy.get()) return
        try {
            clearPlans(manager { false }) // Local metadata only: no root process or partition operation.
            mutableState.update { it.copy(summary = null, restoreSummary = null, task = it.task?.copy(status = TaskStatus.CANCELLED, phase = "准备记录已丢弃，备份仍保留")) }
        } catch (error: Exception) { fail(error) }
    }

    private fun clearPlans(core: OtaTransactionManager) {
        prepared?.let { core.discardPrepared(it.id) }
        recovery?.let { core.discardPrepared(it.id) }
        prepared = null; recovery = null
        mutableState.update { it.copy(summary = null, restoreSummary = null) }
    }

    fun dismissNotice() { mutableState.update { it.copy(notice = null) } }
    fun showNotice(title: String, message: String, error: Boolean = false) {
        mutableState.update { it.copy(notice = UiNotice(title, message, error)) }
    }

    private fun result(result: TransactionResult) {
        result.rollback.filterValues { it != "NOT_NEEDED" }.forEach { (name, status) ->
            log("回滚 $name：$status", if (status in setOf("OK", "COMMAND_OK_NO_BLOCK_VERIFY")) LogLevel.WARNING else LogLevel.ERROR)
        }
        if (result.success) complete(result.message)
        else {
            mutableState.update { it.copy(task = it.task?.copy(status = TaskStatus.FAILED, phase = "操作结束，请查看结果", detail = result.message), notice = UiNotice("操作结果需处理", result.message, true)) }
            log(result.message, LogLevel.ERROR)
        }
    }
    private fun progress(message: String) {
        log(message)
        mutableState.update { it.copy(task = it.task?.copy(phase = message.take(180))) }
    }
    private fun complete(message: String) {
        log(message, LogLevel.SUCCESS)
        mutableState.update { it.copy(task = it.task?.copy(status = TaskStatus.SUCCEEDED, phase = message, progress = 1f)) }
    }
    private fun fail(error: Throwable, showNotice: Boolean = true) {
        val message = (error.message ?: error.javaClass.simpleName).take(6000)
        log(message, LogLevel.ERROR)
        mutableState.update { it.copy(task = it.task?.copy(status = TaskStatus.FAILED, phase = "操作已停止", detail = message), refreshing = false,
            device = if (it.refreshing) it.device.copy(root = RootAccess.DENIED) else it.device,
            notice = if (showNotice) UiNotice("操作未完成", "$message\n已生成的备份会保留。", true) else it.notice) }
    }

    @Synchronized private fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val line = LogLine(logIds.incrementAndGet(), time("HH:mm:ss"), message, level)
        mutableState.update { it.copy(logs = (it.logs + line).takeLast(500)) }
        try {
            if (logFile.length() > 2 * 1024 * 1024) logFile.writeText(logFile.readText().takeLast(512 * 1024))
            logFile.appendText("${time("yyyy-MM-dd HH:mm:ss")} [$level] $message\n")
        } catch (_: Exception) { /* Displaying root results never depends on the optional UI log. */ }
    }

    private fun reloadBackups() {
        val core = manager { false }
        val available = core.listRecoveries()
        recoveries = available.associateBy { "${it.id}:${it.kind.name}" }
        val items = recoveries.map { (key, value) ->
            val created = File(backupRoot, value.id).lastModified()
            val name = if (value.kind == BackupKind.INACTIVE_LK) "LK 原始备份" else "vendor_boot 原始备份"
            BackupItem(key, "$name · ${value.id.take(8)}", SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(Date(created)), value.targetSlot.suffix, value.image.bytes, false)
        }.sortedByDescending { it.createdAt }
        val interrupted = core.listInterruptedTransactions()
        mutableState.update { it.copy(backups = items, notice = if (interrupted.isNotEmpty()) UiNotice("发现未确认完成的事务", "记录 ${interrupted.joinToString { r -> r.id.take(8) }}。先查看日志和备份，再决定恢复；程序不会自动重试写入。", true) else it.notice) }
    }

    /** SAF output streams only. Archives contain data files; no scripts are accepted or executed. */
    fun exportBackup(key: String, output: OutputStream) = exportGuard {
        val backup = recoveries[key] ?: error("备份记录已失效")
        val files = manager { false }.exportableFiles(backup.id)
        check(files.isNotEmpty()) { "备份为空" }
        ZipOutputStream(output.buffered()).use { zip ->
            files.forEach { file ->
                zip.putNextEntry(ZipEntry("${backup.id}/${file.name}"))
                file.inputStream().use { it.copyTo(zip, 1024 * 1024) }
                zip.closeEntry()
            }
        }
    }
    fun exportLogs(output: OutputStream) = exportGuard {
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("origionos7lk升级助手 ${BuildConfig.VERSION_NAME}\n${state.value.device}\n\n")
            if (logFile.isFile) writer.write(logFile.readText())
        }
    }
    private fun <T> exportGuard(block: () -> T): T {
        synchronized(this) {
            check(!busy.get() && exportLock.compareAndSet(false, true)) { "请等待当前操作结束后再导出" }
        }
        return try { block() } finally { exportLock.set(false) }
    }
    private fun time(pattern: String) = SimpleDateFormat(pattern, Locale.ROOT).format(Date())
}
