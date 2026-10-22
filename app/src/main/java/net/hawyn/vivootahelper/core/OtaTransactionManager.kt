package net.hawyn.vivootahelper.core

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** No constructor side effects. All operations are synchronous and require the service lease. */
class OtaTransactionManager(
    backupRoot: File,
    private val shell: RootShell,
    private val patcher: VendorBootPatcher,
    private val lease: ExecutionLease,
    private val onProgress: (String) -> Unit = {},
) {
    // Android's /data/user/0 may be an OS-managed symlink to /data/data.
    // Normalize the app-provided root once; children must still resolve within this exact root.
    private val backupRoot: File = backupRoot.canonicalFile
    /** Explicit user-initiated read-only device inspection; construction never requests su. */
    fun inspect(): InspectionSnapshot = exclusive {
        requireLease()
        initializeRoot()
        val l = probe()
        validateLayout(l)
        InspectionSnapshot(l.slot, l.slot.opposite, l.currentLk, l.inactiveLk, l.vendorBoot, l.scp, backupRoot.usableSpace, requiredSpace(l))
    }

    fun prepare(): PreparedTransaction = exclusive {
        requireLease()
        initializeRoot()
        val before = probe()
        validateLayout(before)
        val needed = requiredSpace(before)
        if (backupRoot.usableSpace < needed) fail("备份空间不足：需要至少 $needed 字节，可用 ${backupRoot.usableSpace} 字节")
        val id = UUID.randomUUID().toString()
        val dir = checkedChild(backupRoot, id).apply { if (!mkdir()) fail("创建备份目录失败") }
        ownerDirectory(dir)
        val current = createPrivateFile(dir, "current_lk.img")
        val inactive = createPrivateFile(dir, "inactive_lk.img")
        val vendor = createPrivateFile(dir, "inactive_vendor_boot.img")
        val scp = createPrivateFile(dir, "inactive_scp_readonly.img")
        val journal = createPrivateFile(dir, "transaction.log")
        progress("读取并备份当前 LK、目标 LK、目标 vendor_boot 和只读 SCP")
        val snapshotScript = guards(before) + buildString {
            appendLine("# VOTA_STAGE=SNAPSHOT")
            appendLine("chmod 700 ${q(dir.path)}")
            listOf(before.currentLk to current, before.inactiveLk to inactive, before.vendorBoot to vendor, before.scp to scp).forEach { (partition, file) ->
                appendLine("chmod 600 ${q(file.path)}")
                appendLine("dd if=${q(partition.canonicalPath)} of=${q(file.path)} bs=1048576")
                appendLine(fileSizeGuard(file, partition.bytes))
            }
            appendLine("sync")
            appendLine(guards(before))
            appendLine("emit SNAPSHOT OK")
        }
        requireSuccess(runLocked(snapshotScript, journal), "备份预检失败，尚未写入分区")
        val backups = BackupSet(dir, evidence(current, before.currentLk), evidence(inactive, before.inactiveLk), evidence(vendor, before.vendorBoot), evidence(scp, before.scp))
        val metadata = metadata(before, id, backups)
        metadata.setProperty("state", "BACKUPS_READY")
        save(dir, metadata)
        progress("备份读取完成，生成启动兼容 vendor_boot")
        val output = checkedChild(dir, "patched_vendor_boot.img")
        try {
            requireLease()
            val nativeJson = patcher.patch(vendor, output, scp, before.vendorBoot.bytes)
            val patched = verifyNativeReport(nativeJson, output, before.vendorBoot.bytes)
            checkedChild(dir, "native_report.json").writeText(nativeJson, Charsets.UTF_8)
            metadata.setProperty("patched.bytes", patched.bytes.toString())
            metadata.setProperty("state", "PREPARED")
            save(dir, metadata)
            progress("准备完成；等待确认，目标槽 ${before.slot.opposite.suffix}")
            prepared(metadata, dir)
        } catch (e: Exception) {
            metadata.setProperty("state", "PREPARE_FAILED")
            metadata.setProperty("error", e.message.orEmpty())
            save(dir, metadata)
            throw if (e is TransactionException) e else TransactionException("生成镜像失败，完整备份已保留：${e.message}", e)
        }
    }

    /** Only a stored PREPARED id is accepted. No model/path supplied by the UI is trusted. */
    fun confirmAndWrite(preparedId: String): TransactionResult = exclusive {
        requireLease()
        val dir = sessionDir(preparedId)
        val metadata = load(dir)
        if (metadata.getProperty("state") != "PREPARED") fail("该准备记录已使用或尚未完成；请重新预检")
        if (metadata.getProperty("schema") != "2") fail("旧版准备记录仅保留备份恢复，请重新准备 2.1 方案")
        val plan = prepared(metadata, dir)
        checkImage(plan.backups.currentLk)
        checkImage(plan.backups.inactiveLk)
        checkImage(plan.backups.inactiveVendorBoot)
        checkImage(plan.backups.inactiveScpSnapshot)
        checkImage(plan.patchedVendorBoot)
        verifyNativeReport(plan.nativeReportJson, plan.patchedVendorBoot.file, capacity = plan.patchedVendorBoot.bytes)
        val before = layout(metadata)
        metadata.setProperty("state", "WRITING")
        save(dir, metadata) // Durable BEFORE the root process can touch a partition.
        val writes = listOf(
            Write("LK", before.inactiveLk, plan.backups.currentLk),
            Write("VENDOR_BOOT", before.vendorBoot, plan.patchedVendorBoot),
        )
        finishWrite(dir, metadata, before, "APPLY", writes, plan.backups.inactiveScpSnapshot)
    }

    /** Backups survive a prepare failure, cancelled preview, or interrupted write. */
    fun listRecoveries(): List<RecoveryBackup> = listSessions().flatMap { dir ->
        try {
            val m = load(dir)
            if (m.getProperty("backups.complete") != "true") emptyList() else {
                val before = layout(m)
                val backups = backups(m, dir)
                listOf(
                    RecoveryBackup(m.required("id"), before.slot, before.slot.opposite, BackupKind.INACTIVE_LK, backups.inactiveLk, before.inactiveLk.canonicalPath, before.inactiveLk.bytes),
                    RecoveryBackup(m.required("id"), before.slot, before.slot.opposite, BackupKind.INACTIVE_VENDOR_BOOT, backups.inactiveVendorBoot, before.vendorBoot.canonicalPath, before.vendorBoot.bytes),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    fun listInterruptedTransactions(): List<InterruptedTransaction> = listSessions().mapNotNull { dir ->
        try {
            val m = load(dir)
            val state = m.getProperty("state")
            if (state in setOf("WRITING", "RESTORING", "UNKNOWN")) InterruptedTransaction(m.required("id"), state, dir) else null
        } catch (_: Exception) { null }
    }

    /** Discard only the pending preview. Complete backups remain exportable/recoverable. */
    fun discardPrepared(id: String) = exclusive {
        val dir = sessionDir(id)
        val m = load(dir)
        if (m.getProperty("state") in setOf("FAILED", "PREPARE_FAILED", "APPLIED", "CANCELLED")) return@exclusive
        if (m.getProperty("state") !in setOf("PREPARED", "RESTORE_PREPARED")) fail("仅待确认方案可丢弃；写入或未完成事务请查看恢复记录")
        m.setProperty("state", "CANCELLED")
        save(dir, m)
    }

    /** Recovery also has a read-only preview phase and its own before-restore rescue backup. */
    fun prepareRestore(backupId: String, kind: BackupKind): RecoveryPlan = exclusive {
        requireLease()
        initializeRoot()
        val originalDir = sessionDir(backupId)
        val metadata = load(originalDir)
        if (metadata.getProperty("backups.complete") != "true") fail("所选备份尚未完成")
        val original = layout(metadata)
        val saved = backups(metadata, originalDir)
        val backupImage = if (kind == BackupKind.INACTIVE_LK) saved.inactiveLk else saved.inactiveVendorBoot
        checkImage(backupImage)
        val now = probe()
        validateLayout(now)
        if (now.slot != original.slot) fail("当前槽位与备份时不同，恢复终止；目标必须仍为 inactive")
        val target = if (kind == BackupKind.INACTIVE_LK) now.inactiveLk else now.vendorBoot
        val originalTarget = if (kind == BackupKind.INACTIVE_LK) original.inactiveLk else original.vendorBoot
        if (target.bytes != originalTarget.bytes || (originalTarget.deviceIdentity != null && target.deviceIdentity != originalTarget.deviceIdentity)) fail("备份目标设备或容量已变化")
        if (backupRoot.usableSpace < target.bytes + SPACE_MARGIN) fail("恢复前救援备份空间不足")
        val recoveryId = UUID.randomUUID().toString()
        val rescueDir = checkedChild(backupRoot, recoveryId).apply { if (!mkdir()) fail("创建恢复记录失败") }
        ownerDirectory(rescueDir)
        val rescue = createPrivateFile(rescueDir, "before_restore.img")
        val journal = createPrivateFile(rescueDir, "transaction.log")
        val script = guards(now) + "\n# VOTA_STAGE=RESTORE_SNAPSHOT\n" +
            "dd if=${q(target.alias)} of=${q(rescue.path)} bs=1048576\nsync\n" + fileSizeGuard(rescue, target.bytes) + guards(now)
        requireSuccess(runLocked(script, journal), "恢复前备份失败，尚未写入分区")
        val rescueEvidence = evidence(rescue, target)
        val m = Properties().apply {
            setProperty("id", recoveryId)
            setProperty("state", "RESTORE_PREPARED")
            setProperty("restore.backupId", backupId)
            setProperty("restore.kind", kind.name)
            setProperty("rescue.bytes", rescueEvidence.bytes.toString())
        }
        putLayout(m, now)
        save(rescueDir, m)
        val selected = RecoveryBackup(backupId, original.slot, original.slot.opposite, kind, backupImage, target.canonicalPath, target.bytes)
        RecoveryPlan(recoveryId, selected, target, rescueEvidence)
    }

    fun confirmRestore(recoveryId: String): TransactionResult = exclusive {
        requireLease()
        val dir = sessionDir(recoveryId)
        val m = load(dir)
        if (m.getProperty("state") != "RESTORE_PREPARED") fail("恢复准备记录已使用或未完成")
        val backupId = m.required("restore.backupId")
        val originalDir = sessionDir(backupId)
        val originalMetadata = load(originalDir)
        val before = layout(m)
        val original = layout(originalMetadata)
        if (before.slot != original.slot) fail("恢复槽位校验失败")
        val kind = BackupKind.valueOf(m.required("restore.kind"))
        val target = if (kind == BackupKind.INACTIVE_LK) before.inactiveLk else before.vendorBoot
        val saved = backups(originalMetadata, originalDir)
        val image = if (kind == BackupKind.INACTIVE_LK) saved.inactiveLk else saved.inactiveVendorBoot
        val rescue = ImageEvidence(checkedChild(dir, "before_restore.img"), m.required("rescue.bytes").toLong())
        checkImage(image)
        checkImage(rescue)
        if (image.bytes != target.bytes || rescue.bytes != target.bytes) fail("恢复镜像容量不匹配")
        m.setProperty("state", "RESTORING")
        save(dir, m)
        finishWrite(dir, m, before, "RESTORE", listOf(Write(kind.name, target, image)), saved.inactiveScpSnapshot)
    }

    /** UI can share these app-private files via its FileProvider/SAF exporter. */
    fun exportableFiles(backupId: String): List<File> {
        val dir = sessionDir(backupId)
        return listOf("manifest.properties", "fresh_snapshot.properties", "transaction.log", "native_report.json", "current_lk.img", "inactive_lk.img", "inactive_vendor_boot.img", "inactive_scp_readonly.img", "patched_vendor_boot.img", "before_restore.img")
            .map { checkedChild(dir, it) }.filter { it.isFile }
    }

    private data class Layout(val slot: Slot, val currentLk: PartitionEvidence, val inactiveLk: PartitionEvidence, val vendorBoot: PartitionEvidence, val scp: PartitionEvidence)
    private data class Write(val name: String, val target: PartitionEvidence, val replacement: ImageEvidence)

    private fun finishWrite(dir: File, m: Properties, before: Layout, operation: String, writes: List<Write>, scpSnapshot: ImageEvidence): TransactionResult {
        val targetSlot = before.slot.opposite
        progress("核对槽位、by-name 设备及容量，并为本次写入保存最新回滚备份")
        checkImage(scpSnapshot)
        val freshSpace = before.currentLk.bytes + before.inactiveLk.bytes + before.vendorBoot.bytes + before.scp.bytes + SPACE_MARGIN
        if (backupRoot.usableSpace < freshSpace) fail("本次回滚快照空间不足：至少需要 $freshSpace 字节")
        val freshId = UUID.randomUUID().toString()
        val freshDir = checkedChild(backupRoot, freshId).apply { if (!mkdir()) fail("创建本次回滚备份目录失败") }
        ownerDirectory(freshDir)
        fun emptyImage(name: String, size: Long) = ImageEvidence(createPrivateFile(freshDir, name), size, "")
        val fresh = BackupSet(freshDir, emptyImage("current_lk.img", before.currentLk.bytes), emptyImage("inactive_lk.img", before.inactiveLk.bytes), emptyImage("inactive_vendor_boot.img", before.vendorBoot.bytes), emptyImage("inactive_scp_readonly.img", before.scp.bytes))
        FileOutputStream(fresh.inactiveScpSnapshot.file).use { output -> scpSnapshot.file.inputStream().use { it.copyTo(output) }; output.fd.sync() }
        val freshMetadata = metadata(before, freshId, fresh).apply {
            setProperty("state", "SNAPSHOT_PENDING")
            setProperty("backups.complete", "false")
            setProperty("parentTransaction", m.required("id"))
        }
        save(freshDir, freshMetadata)
        createPrivateFile(freshDir, "transaction.log")
        val receipt = createPrivateFile(freshDir, "fresh_snapshot.properties")
        m.setProperty("rollbackSnapshotId", freshId)
        save(dir, m)
        val rootScript = writeScript(before, writes, fresh, receipt)
        val result = try {
            runLocked(rootScript, checkedChild(dir, "transaction.log"))
        } catch (e: Exception) {
            m.setProperty("state", "UNKNOWN")
            m.setProperty("error", e.message.orEmpty())
            save(dir, m)
            return TransactionResult(false, operation, "root 进程结果未确认，请查看持久日志及恢复备份；保持当前槽位", targetSlot, emptyMap(), e.toString(), dir)
        }
        val fields = fields(result.output)
        val success = result.exitCode == 0 && fields["RESULT"] == "SUCCESS"
        val rollback = writes.associate { it.name to (fields["ROLLBACK_${it.name}"] ?: "NOT_NEEDED") }
        m.setProperty("state", if (success) "APPLIED" else "FAILED")
        m.setProperty("result.exitCode", result.exitCode.toString())
        m.setProperty("result.rollback", rollback.toString())
        m.setProperty("result.message", result.output.takeLast(3000))
        checkedChild(dir, "transaction.log").appendText("\n--- root process result exit=${result.exitCode} ---\n${result.output}", Charsets.UTF_8)
        save(dir, m)
        val readonlyWarning = fields.any { (key, value) -> key.startsWith("READONLY_") && value == "FAILED" }
        val message = (if (success) "dd 写入与 sync 完成；未检查对槽块内容或读回摘要，槽位保持原状" else if (rollback.values.any { it == "FAILED" }) "写入失败，至少一项回滚命令失败；请使用保留备份恢复" else "事务失败；已触碰分区的回滚命令结果见详情，未检查对槽块摘要") +
            (if (readonlyWarning) "。警告：至少一个分区的只读标志恢复失败，详见日志" else "")
        progress(message)
        return TransactionResult(success, operation, message, targetSlot, rollback, result.output, dir)
    }

    private fun writeScript(before: Layout, writes: List<Write>, fresh: BackupSet, receipt: File): String = buildString {
        appendLine("# VOTA_STAGE=WRITE")
        writes.forEach { w ->
            appendLine("touched_${w.name}=0")
            appendLine("ro_${w.name}=\$(blockdev --getro ${q(w.target.alias)})")
            appendLine("case \$ro_${w.name} in 0|1) ;; *) die 'invalid_readonly_state' ;; esac")
        }
        appendLine("sync")
        appendLine("finish() {")
        appendLine("  code=\$?; trap - EXIT HUP INT TERM; set +e")
        appendLine("  if [ \"\$code\" -ne 0 ]; then")
        writes.asReversed().forEach { w ->
            val rollbackFile = if (w.target.alias == before.inactiveLk.alias) fresh.inactiveLk.file else fresh.inactiveVendorBoot.file
            appendLine("    if [ \"\$touched_${w.name}\" = 1 ]; then")
            appendLine("      if blockdev --setrw ${q(w.target.alias)} && dd if=${q(rollbackFile.path)} of=${q(w.target.alias)} bs=1048576 && sync; then emit ROLLBACK_${w.name} COMMAND_OK_NO_BLOCK_VERIFY; else emit ROLLBACK_${w.name} FAILED; fi")
            appendLine("    fi")
        }
        appendLine("  fi")
        writes.forEach { w ->
            appendLine("  if [ \"\$ro_${w.name}\" = 1 ]; then if blockdev --setro ${q(w.target.alias)}; then emit READONLY_${w.name} RESTORED; else emit READONLY_${w.name} FAILED; fi; fi")
        }
        appendLine("  if [ \"\$code\" = 0 ]; then emit RESULT SUCCESS; else emit RESULT FAILED; fi")
        appendLine("  exit \"\$code\"")
        appendLine("}")
        appendLine("trap finish EXIT; trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM")
        appendLine(guards(before))
        writes.forEach { w ->
            appendLine(fileSizeGuard(w.replacement.file, w.target.bytes))
        }
        appendLine(fileSizeGuard(fresh.inactiveScpSnapshot.file, before.scp.bytes))
        appendLine("# VOTA_STAGE=FRESH_ROLLBACK")
        listOf(before.currentLk to fresh.currentLk, before.inactiveLk to fresh.inactiveLk, before.vendorBoot to fresh.inactiveVendorBoot).forEach { (partition, image) ->
            appendLine("dd if=${q(partition.alias)} of=${q(image.file.path)} bs=1048576")
            appendLine(fileSizeGuard(image.file, partition.bytes))
        }
        appendLine("printf '%s\\n' 'backups.complete=true' 'state=ROLLBACK_READY' 'fresh.snapshot.schema=2' > ${q(receipt.path)}")
        appendLine("emit FRESH_ROLLBACK_ID ${q(fresh.directory.name)}")
        appendLine("sync")
        // Slot is queried inside guards immediately before the first writable operation.
        appendLine(guards(before))
        writes.forEach { w ->
            appendLine("blockdev --setrw ${q(w.target.alias)}")
            // Mark BEFORE dd: a nonzero dd can already have changed part of the partition.
            appendLine("touched_${w.name}=1; emit WRITE_${w.name} START")
            appendLine("dd if=${q(w.replacement.file.path)} of=${q(w.target.alias)} bs=1048576")
            appendLine("sync")
            appendLine("emit WRITE_${w.name} COMMAND_OK_NO_BLOCK_VERIFY")
        }
    }

    private fun probe(): Layout {
        val script = """
            # VOTA_STAGE=PROBE
            slot=§(getprop ro.boot.slot_suffix)
            case "§slot" in _a) other=_b ;; _b) other=_a ;; *) die ambiguous_slot ;; esac
            emit SLOT "§slot"
            describe CURRENT_LK "/dev/block/by-name/lk§slot"
            describe INACTIVE_LK "/dev/block/by-name/lk§other"
            describe VENDOR_BOOT "/dev/block/by-name/vendor_boot§other"
            describe SCP "/dev/block/by-name/scp§other"
        """.trimIndent().replace('§', '$')
        val result = runLocked(script)
        requireSuccess(result, "root 预检失败")
        val f = fields(result.output)
        val slot = Slot.parse(f["SLOT"] ?: "")
        fun p(key: String, name: String, targetSlot: Slot): PartitionEvidence {
            val alias = "/dev/block/by-name/$name${targetSlot.suffix}"
            if (f.required("${key}_PATH") != alias) fail("root 返回了非预期 by-name 路径")
            return PartitionEvidence(alias, alias, f.required("${key}_BYTES").toLong(), deviceIdentity = f.required("${key}_IDENTITY"))
        }
        return Layout(slot, p("CURRENT_LK", "lk", slot), p("INACTIVE_LK", "lk", slot.opposite), p("VENDOR_BOOT", "vendor_boot", slot.opposite), p("SCP", "scp", slot.opposite))
    }

    private fun guards(layout: Layout): String = buildString {
        appendLine("[ \"\$(getprop ro.boot.slot_suffix)\" = ${q(layout.slot.suffix)} ] || die slot_changed")
        listOf(layout.currentLk, layout.inactiveLk, layout.vendorBoot, layout.scp).forEach { p ->
            appendLine("[ -b ${q(p.alias)} ] || die not_block_device")
            if (p.deviceIdentity != null) appendLine("[ \"\$(vota_device_identity ${q(p.alias)})\" = ${q(p.deviceIdentity)} ] || die target_identity_changed")
            appendLine("[ \"\$(blockdev --getsize64 ${q(p.alias)})\" = ${q(p.bytes.toString())} ] || die capacity_changed")
        }
    }

    private fun validateLayout(l: Layout) {
        val partitions = listOf(l.currentLk, l.inactiveLk, l.vendorBoot, l.scp)
        val identities = partitions.mapNotNull { it.deviceIdentity }
        if (identities.toSet().size != identities.size) fail("分区别名指向了相同设备标识")
        val expectedAliases = listOf("/dev/block/by-name/lk${l.slot.suffix}", "/dev/block/by-name/lk${l.slot.opposite.suffix}", "/dev/block/by-name/vendor_boot${l.slot.opposite.suffix}", "/dev/block/by-name/scp${l.slot.opposite.suffix}")
        partitions.zip(expectedAliases).forEach { (p, expected) ->
            if (p.alias != expected || p.canonicalPath != expected) fail("分区 by-name 路径异常")
            if (p.bytes <= 0 || p.bytes > MAX_PARTITION || p.bytes % 512 != 0L) fail("分区容量异常：${p.alias}")
            if (p.deviceIdentity != null && !p.deviceIdentity.matches(DEVICE_ID)) fail("块设备标识异常")
        }
        if (l.currentLk.bytes != l.inactiveLk.bytes) fail("两槽 LK 容量不同，完整分区复制预检终止")
    }

    private fun requiredSpace(l: Layout): Long = l.currentLk.bytes * 2 + l.inactiveLk.bytes * 2 + l.vendorBoot.bytes * 3 + l.scp.bytes * 2 + SPACE_MARGIN

    private fun runLocked(body: String, journal: File? = null): ShellResult {
        requireLease()
        val journalPath = journal?.let { q(it.path) } ?: "''"
        val prefix = """
            set -eu
            die() { echo "VOTA_ERROR=§*" >&2; exit 71; }
            [ "§(id -u)" = 0 ] || die root_required
            for tool in getprop blockdev dd stat sync flock chmod; do command -v "§tool" >/dev/null || die "missing_tool:§tool"; done
            vota_device_identity() {
              [ -b "§1" ] || die "not_block_device:§1"
              vota_dev=§(stat -Lc '%t:%T' "§1") || die "device_identity_failed:§1"
              case "§vota_dev" in ''|*[!0-9a-f:]*|:*|*:) die "device_identity_format:§1" ;; esac
              printf '%s\n' "§vota_dev"
            }
            emit() { printf 'VOTA_%s=%s\n' "§1" "§2"; if [ -n "§journal" ]; then printf 'VOTA_%s=%s\n' "§1" "§2" >> "§journal"; fi; }
            describe() {
              vota_key=§1
              vota_path=§2
              vota_identity=§(vota_device_identity "§vota_path") || die "identity_failed:§vota_path"
              vota_bytes=§(blockdev --getsize64 "§vota_path") || die "capacity_failed:§vota_path"
              case "§vota_bytes" in ''|*[!0-9]*) die "capacity_format:§vota_path" ;; esac
              emit "§{vota_key}_PATH" "§vota_path"
              emit "§{vota_key}_BYTES" "§vota_bytes"
              emit "§{vota_key}_IDENTITY" "§vota_identity"
            }
        """.trimIndent().replace('§', '$')
        val inner = "journal=$journalPath\n$prefix\n$body"
        // Android mksh marks high numbered descriptors CLOEXEC. Standard fd0 survives exec;
        // this script never reads stdin (sh uses -c and every dd has an explicit if=).
        val outer = "# VOTA_ROOT_TRANSACTION\ncommand -v flock >/dev/null 2>&1 || { echo VOTA_ERROR=missing_flock; exit 72; }\numask 077\nexec 0<>${q(checkedChild(backupRoot, ".transaction.lock").path)}\nflock -n 0 || { echo VOTA_ERROR=transaction_busy; exit 72; }\nsh -c ${quoteScript(inner)}"
        return shell.execute(outer) { line ->
            if (line.startsWith("VOTA_")) progress(line)
        }
    }

    private fun verifyNativeReport(json: String, output: File, capacity: Long): ImageEvidence {
        val report = try { JSONObject(json) } catch (e: Exception) { throw TransactionException("native 返回的报告不是有效 JSON", e) }
        if (!report.optBoolean("ok", false)) fail("native patch 失败：${report.optString("error", "未知错误")}")
        if (report.getInt("schema") != 2 || !report.getBoolean("structureValidated") || report.getString("outputPath") != output.path || report.getLong("outputSize") != capacity) fail("native 结构成功标记、输出路径、容量或 schema 校验失败")
        if (report.getBoolean("fullMetadataRepair")) fail("native 报告与已审 V3 兼容方案不一致")
        val avb = report.getJSONObject("avb")
        if (avb.getBoolean("resigned")) fail("native 意外重签了 AVB")
        avb.getBoolean("present")
        avb.getString("metadataStatus"); avb.getString("imageStatus"); avb.getString("verificationStatus")
        report.getBoolean("changed")
        if (report.getLong("scpDramSize") <= 0) fail("SCP DRAM 尺寸无效")
        report.getJSONArray("fragments"); report.getJSONArray("warnings")
        val dtbs = report.getJSONArray("dtbs")
        if (dtbs.length() == 0) fail("native 未报告设备树启动兼容修补；请重新准备 2.1 方案")
        for (i in 0 until dtbs.length()) {
            val dtb = dtbs.getJSONObject(i)
            val prefix = dtb.optString("btLogPrefix", "")
            if (!dtb.optBoolean("btLogCompatible", false) || dtb.optInt("btLogLength", 0) < 4 ||
                !prefix.matches(Regex("111[0-9a-fA-F]{5}"))) {
                fail("设备树 bt_log 启动兼容修补检查失败；请重新准备 2.1 方案")
            }
            dtb.getBoolean("btLogAdded"); dtb.getBoolean("btLogChanged")
        }
        val image = ImageEvidence(output, capacity)
        checkImage(image)
        return image
    }

    private fun evidence(file: File, partition: PartitionEvidence): ImageEvidence = ImageEvidence(file, partition.bytes).also { checkImage(it) }
    private fun checkImage(image: ImageEvidence) {
        if (!image.file.isFile || image.file.canonicalFile != image.file.absoluteFile || image.file.length() != image.bytes) fail("文件长度、普通文件类型或路径校验失败：${image.file.name}")
    }

    private fun fileSizeGuard(file: File, size: Long): String = "[ -f ${q(file.path)} ] && [ ! -b ${q(file.path)} ] && [ ! -L ${q(file.path)} ] && [ \"\$(stat -c %s ${q(file.path)})\" = ${q(size.toString())} ] || die backup_or_output_size_changed\n"
    private fun initializeRoot() {
        if (!backupRoot.exists() && !backupRoot.mkdirs()) fail("创建备份根目录失败")
        if (!backupRoot.isDirectory || backupRoot.canonicalFile != backupRoot.absoluteFile) fail("备份目录必须是 app 私有常规目录")
        ownerDirectory(backupRoot)
    }
    private fun ownerDirectory(file: File) {
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
        file.setReadable(true, true); file.setWritable(true, true); file.setExecutable(true, true)
    }
    private fun createPrivateFile(dir: File, name: String): File = checkedChild(dir, name).also {
        if (!it.createNewFile()) fail("备份文件已存在")
        it.setReadable(false, false); it.setWritable(false, false)
        it.setReadable(true, true); it.setWritable(true, true)
    }
    private fun checkedChild(parent: File, name: String): File {
        val file = File(parent, name).absoluteFile
        if (file.parentFile?.canonicalFile != parent.canonicalFile || file.canonicalFile != file) fail("文件路径越界或存在符号链接")
        q(file.path)
        return file
    }
    private fun sessionDir(id: String): File {
        if (!id.matches(ID)) fail("事务标识格式错误")
        val dir = checkedChild(backupRoot, id)
        if (!dir.isDirectory) fail("备份记录不存在")
        return dir
    }
    private fun listSessions() = backupRoot.listFiles()?.filter { it.isDirectory && it.name.matches(ID) && it.canonicalFile == it.absoluteFile } ?: emptyList()
    private fun metadata(l: Layout, id: String, b: BackupSet) = Properties().apply {
        setProperty("id", id); setProperty("backups.complete", "true"); setProperty("createdMillis", System.currentTimeMillis().toString())
        putLayout(this, l)
        listOf("current" to b.currentLk, "inactive" to b.inactiveLk, "vendor" to b.inactiveVendorBoot, "scp" to b.inactiveScpSnapshot).forEach { (key, image) -> setProperty("backup.$key.bytes", image.bytes.toString()) }
    }
    private fun putLayout(m: Properties, l: Layout) {
        m.setProperty("schema", "2")
        m.setProperty("slot", l.slot.suffix)
        listOf("current" to l.currentLk, "inactive" to l.inactiveLk, "vendor" to l.vendorBoot, "scp" to l.scp).forEach { (key, p) ->
            m.setProperty("$key.path", p.alias); m.setProperty("$key.bytes", p.bytes.toString())
            m.remove("$key.hash")
            p.deviceIdentity?.let { m.setProperty("$key.identity", it) }
        }
    }
    private fun layout(m: Properties): Layout {
        val slot = Slot.parse(m.required("slot"))
        // Legacy physical sdc paths are deliberately ignored, never even passed to a shell.
        fun p(key: String, name: String, s: Slot): PartitionEvidence {
            val alias = "/dev/block/by-name/$name${s.suffix}"
            val identity = if (m.getProperty("schema") == "2") m.required("$key.identity") else null
            return PartitionEvidence(alias, alias, m.required("$key.bytes").toLong(), deviceIdentity = identity)
        }
        return Layout(slot, p("current", "lk", slot), p("inactive", "lk", slot.opposite), p("vendor", "vendor_boot", slot.opposite), p("scp", "scp", slot.opposite)).also { validateLayout(it) }
    }
    private fun backups(m: Properties, dir: File): BackupSet {
        fun image(key: String, name: String) = ImageEvidence(checkedChild(dir, name), m.required("backup.$key.bytes").toLong())
        return BackupSet(dir, image("current", "current_lk.img"), image("inactive", "inactive_lk.img"), image("vendor", "inactive_vendor_boot.img"), image("scp", "inactive_scp_readonly.img"))
    }
    private fun prepared(m: Properties, dir: File): PreparedTransaction {
        val l = layout(m)
        return PreparedTransaction(m.required("id"), l.slot, l.slot.opposite, l.currentLk, l.inactiveLk, l.vendorBoot, l.scp, backups(m, dir), ImageEvidence(checkedChild(dir, "patched_vendor_boot.img"), m.required("patched.bytes").toLong()), checkedChild(dir, "native_report.json").readText(Charsets.UTF_8))
    }
    private fun save(dir: File, metadata: Properties) {
        val temp = checkedChild(dir, "manifest.properties.tmp")
        FileOutputStream(temp).use { stream -> metadata.store(stream, "Vivo OTA helper transaction; never auto-resume writes"); stream.fd.sync() }
        val target = checkedChild(dir, "manifest.properties")
        // Same-directory rename on Android/Linux is atomic, replacing the previous complete manifest.
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    private fun load(dir: File) = Properties().apply {
        checkedChild(dir, "manifest.properties").inputStream().use { load(it) }
        // The root process commits this small receipt after fresh backups are complete, BEFORE
        // touching a partition. It remains discoverable if the app dies before a result returns.
        val receipt = checkedChild(dir, "fresh_snapshot.properties")
        if (getProperty("state") == "SNAPSHOT_PENDING" && receipt.isFile) {
            val update = Properties().apply { receipt.inputStream().use { load(it) } }
            val paths = listOf("current" to "current_lk.img", "inactive" to "inactive_lk.img", "vendor" to "inactive_vendor_boot.img", "scp" to "inactive_scp_readonly.img")
            val completeFiles = paths.all { (key, name) -> val image = checkedChild(dir, name); image.isFile && image.length() == getProperty("backup.$key.bytes")?.toLongOrNull() }
            if (update.getProperty("backups.complete") == "true" && update.getProperty("fresh.snapshot.schema") == "2" && completeFiles) {
                setProperty("backups.complete", "true"); setProperty("state", "ROLLBACK_READY")
            }
        }
    }
    private fun fields(output: String): Map<String, String> = output.lineSequence().filter { it.startsWith("VOTA_") && '=' in it }.associate { it.substringBefore('=').removePrefix("VOTA_") to it.substringAfter('=') }
    private fun requireSuccess(result: ShellResult, message: String) { if (result.exitCode != 0) fail("$message\n${result.output.takeLast(3000)}") }
    private fun requireLease() { if (!lease.isHeld()) fail("请先启动前台服务并持有唤醒锁") }
    private fun progress(message: String) { try { onProgress(message) } catch (_: Exception) { } }
    private fun <T> exclusive(block: () -> T): T {
        if (!PROCESS_LOCK.tryLock()) fail("已有准备、写入或恢复事务正在运行")
        return try { block() } finally { PROCESS_LOCK.unlock() }
    }

    companion object {
        private val PROCESS_LOCK = ReentrantLock()
        private val ID = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val DEVICE_ID = Regex("[0-9a-f]+:[0-9a-f]+")
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val MAX_PARTITION = 1024L * 1024 * 1024
        private fun q(value: String): String {
            if (value.any { it == '\n' || it == '\r' || it == '\u0000' || it == '§' }) fail("路径或参数包含异常字符")
            return "'" + value.replace("'", "'\\''") + "'"
        }
        private fun quoteScript(value: String): String {
            if ('\u0000' in value) fail("脚本包含异常 NUL")
            return "'" + value.replace("'", "'\\''") + "'"
        }
        private fun fail(message: String): Nothing = throw TransactionException(message)
        private fun Properties.required(key: String) = getProperty(key) ?: fail("备份记录缺少字段：$key")
        private fun Map<String, String>.required(key: String) = get(key) ?: fail("root 预检缺少字段：$key")
    }
}
