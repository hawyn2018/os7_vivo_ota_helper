package net.hawyn.vivootahelper.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Control-flow tests use a fake block backend; no su, actual block device, or phone access. */
class OtaTransactionManagerTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Fixture(val root: File) {
        val shell = FakeRoot(root)
        var leaseHeld = true
        var nativeCalls = 0
        var corruptNativeHash = false
        var badStructure = false
        var failNative = false
        var btLogCompatible = true
        var btLogLength = 4
        var btLogPrefix = "11100000"
        var omitBtLogReport = false
        val manager get() = OtaTransactionManager(root, shell, VendorBootPatcher { input, output, _, capacity ->
            nativeCalls++
            if (failNative) return@VendorBootPatcher """{"ok":false,"schema":2,"error":"fixture malformed DTB"}"""
            val bytes = input.readBytes().also { it[100] = (it[100].toInt() xor 0x55).toByte() }
            output.writeBytes(bytes)
            JSONObject().put("ok", true).put("schema", 2).put("structureValidated", !badStructure).put("outputPath", output.path)
                .put("outputSize", capacity).apply { if (corruptNativeHash) put("outputSha256", "legacy-field-ignored") }
                .put("scpDramSize", 0x1d0000).put("changed", true).put("fullMetadataRepair", false)
                .put("avb", JSONObject().put("present", true).put("metadataStatus", "preserved").put("imageStatus", "modified").put("verificationStatus", "unverified").put("resigned", false))
                .put("fragments", org.json.JSONArray()).put("dtbs", org.json.JSONArray().put(JSONObject().apply {
                    if (!omitBtLogReport) {
                        put("btLogCompatible", btLogCompatible); put("btLogLength", btLogLength)
                        put("btLogPrefix", btLogPrefix); put("btLogAdded", true); put("btLogChanged", true)
                    }
                })).put("warnings", org.json.JSONArray()).toString()
        }, ExecutionLease { leaseHeld })
    }

    private class FakeRoot(private val root: File) : RootShell {
        var slot = "_a"
        var collision = false
        var failSecondWrite = false
        var identityChanged = false
        var writeCalls = 0
        var snapshotCalls = 0
        var holdProbe: CountDownLatch? = null
        var probeEntered: CountDownLatch? = null
        val scripts = mutableListOf<String>()
        val bytes = linkedMapOf(
            "current" to ByteArray(1024) { 0x11 },
            "inactive" to ByteArray(1024) { 0x22 },
            "vendor" to ByteArray(4096) { 0x33 },
            "scp" to ByteArray(1024) { 0x44 },
        )
        private fun path(key: String): String {
            val suffix = if (key == "current") slot else if (slot == "_a") "_b" else "_a"
            val name = when (key) { "current", "inactive" -> "lk"; "vendor" -> "vendor_boot"; else -> "scp" }
            return "/dev/block/by-name/$name$suffix"
        }
        private fun identity(key: String) = if ((collision || identityChanged) && key == "inactive") "8:1" else when (key) { "current" -> "8:1"; "inactive" -> "8:2"; "vendor" -> "8:3"; else -> "8:4" }
        override fun execute(script: String, onOutput: (String) -> Unit): ShellResult {
            // Recover the single POSIX-quoted inner script for assertions only; nothing is executed.
            check(script.contains("exec 0<>") && script.contains("flock -n 0 ||"))
            check(!script.contains("exec 9>") && !script.contains("flock -n 9"))
            check(!script.contains("flock -n '"))
            val inner = script.substringAfter("sh -c '", script).removeSuffix("'").replace("'\\''", "'")
            check(!inner.contains("sha256sum") && !inner.contains("vota_file_sha256") && !inner.contains("\$(hash "))
            check(!inner.contains("readlink") && !inner.contains("/dev/block/fixture_") && !inner.contains("/dev/block/sdc"))
            scripts += inner
            check(!inner.contains("init_boot"))
            check(!inner.contains("set-active") && !inner.contains("set_active") && !inner.contains("reboot"))
            if (inner.contains("# VOTA_STAGE=PROBE")) {
                probeEntered?.countDown()
                holdProbe?.await(5, TimeUnit.SECONDS)
                val fields = listOf("CURRENT_LK" to "current", "INACTIVE_LK" to "inactive", "VENDOR_BOOT" to "vendor", "SCP" to "scp")
                check(!inner.contains("_HASH\"") && !inner.contains("dd if="))
                return ShellResult(0, "VOTA_SLOT=$slot\n" + fields.joinToString("\n") { (label, key) -> "VOTA_${label}_PATH=${path(key)}\nVOTA_${label}_BYTES=${bytes.getValue(key).size}\nVOTA_${label}_IDENTITY=${identity(key)}" })
            }
            val expectedSlot = Regex("getprop ro.boot.slot_suffix\\)\\\" = '(_[ab])'").find(inner)?.groupValues?.get(1)
            if (expectedSlot != slot) return ShellResult(71, "VOTA_ERROR=slot_changed\nVOTA_RESULT=FAILED\n")
            for (key in bytes.keys) {
                if (!inner.contains("vota_device_identity '${path(key)}')\" = '${identity(key)}'")) return ShellResult(71, "VOTA_ERROR=identity_changed\nVOTA_RESULT=FAILED\n")
            }
            val copyPattern = Regex("dd if='([^']+)' of='([^']+)' bs=1048576")
            fun readCopies(after: Int) = copyPattern.findAll(inner).filter { it.range.first > after && it.groupValues[1].startsWith("/dev/block/by-name/") }.toList()
            fun copyReads(copies: List<MatchResult>) {
                copies.forEach { match ->
                    val key = bytes.keys.single { path(it) == match.groupValues[1] }
                    File(match.groupValues[2]).writeBytes(bytes.getValue(key))
                }
            }
            if (inner.contains("# VOTA_STAGE=SNAPSHOT")) {
                snapshotCalls++
                copyReads(readCopies(inner.indexOf("# VOTA_STAGE=SNAPSHOT")))
                return ShellResult(0, "VOTA_SNAPSHOT=OK\n")
            }
            if (inner.contains("# VOTA_STAGE=RESTORE_SNAPSHOT")) {
                copyReads(readCopies(inner.indexOf("# VOTA_STAGE=RESTORE_SNAPSHOT")))
                return ShellResult(0, "VOTA_SNAPSHOT=OK\n")
            }
            check(inner.contains("# VOTA_STAGE=WRITE"))
            val freshCopies = readCopies(inner.indexOf("# VOTA_STAGE=FRESH_ROLLBACK"))
            copyReads(freshCopies)
            val freshDir = File(freshCopies.first().groupValues[2]).parentFile
            Properties().apply {
                setProperty("backups.complete", "true")
                setProperty("state", "ROLLBACK_READY")
                setProperty("fresh.snapshot.schema", "2")
            }.also { p -> File(freshDir, "fresh_snapshot.properties").outputStream().use { p.store(it, null) } }
            writeCalls++
            check(inner.indexOf("touched_LK=1") < inner.indexOf("dd if=", inner.indexOf("touched_LK=1")) || !inner.contains("touched_LK=1"))
            val source = copyPattern.findAll(inner).filter { it.range.first > inner.indexOf("trap finish EXIT") && it.groupValues[2].startsWith("/dev/block/by-name/") }.toList()
            check(source.isNotEmpty())
            val original = bytes.mapValues { it.value.copyOf() }
            for (match in source) {
                val key = bytes.keys.single { path(it) == match.groupValues[2] }
                bytes[key] = File(match.groupValues[1]).readBytes()
                if (failSecondWrite && key == "vendor") {
                    // Simulate a partial dd failure and the trap restoring the saved originals.
                    bytes["inactive"] = original.getValue("inactive")
                    bytes["vendor"] = original.getValue("vendor")
                    return ShellResult(5, "VOTA_ROLLBACK_VENDOR_BOOT=COMMAND_OK_NO_BLOCK_VERIFY\nVOTA_ROLLBACK_LK=COMMAND_OK_NO_BLOCK_VERIFY\nVOTA_RESULT=FAILED\n")
                }
            }
            return ShellResult(0, "VOTA_RESULT=SUCCESS\n")
        }
    }

    private fun fixture() = Fixture(temporary.newFolder("backups"))
    private fun expectFailure(block: () -> Unit) {
        try { block(); fail("Expected guarded failure") } catch (_: TransactionException) { }
    }

    @Test fun constructionAndRecoveryListingDoNotRunRoot() {
        val f = fixture(); f.manager.listRecoveries(); f.manager.listInterruptedTransactions()
        assertTrue(f.shell.scripts.isEmpty())
    }

    @Test fun explicitInspectionShowsOppositeSlotWithoutPartitionWrites() {
        val f = fixture(); val inspection = f.manager.inspect()
        assertEquals(Slot.A, inspection.currentSlot); assertEquals(Slot.B, inspection.targetSlot)
        assertEquals(1024L, inspection.inactiveLk.bytes)
        assertTrue(inspection.estimatedRequiredBytes > inspection.inactiveVendorBoot.bytes * 2)
        assertEquals(0, f.nativeCalls); assertEquals(0, f.shell.snapshotCalls); assertEquals(0, f.shell.writeCalls)
    }

    @Test fun discardIsLocalAndKeepsBackupsWhilePreventingWrite() {
        val f = fixture(); val prepared = f.manager.prepare(); val calls = f.shell.scripts.size
        f.manager.discardPrepared(prepared.id)
        f.manager.discardPrepared(prepared.id) // Terminal no-op is safe for UI cleanup.
        assertEquals(calls, f.shell.scripts.size)
        assertEquals(2, f.manager.listRecoveries().size)
        expectFailure { f.manager.confirmAndWrite(prepared.id) }
    }

    @Test fun serviceLeaseIsRequiredBeforeRoot() {
        val f = fixture(); f.leaseHeld = false
        expectFailure { f.manager.prepare() }
        assertTrue(f.shell.scripts.isEmpty())
    }

    @Test fun ambiguousSlotFailsBeforeBackupAndPatching() {
        val f = fixture(); f.shell.slot = "_c"
        expectFailure { f.manager.prepare() }
        assertEquals(0, f.nativeCalls); assertEquals(0, f.shell.snapshotCalls)
    }

    @Test fun canonicalAliasCollisionFailsBeforeBackup() {
        val f = fixture(); f.shell.collision = true
        expectFailure { f.manager.prepare() }
        assertEquals(0, f.shell.snapshotCalls)
    }

    @Test fun differentLkCapacitiesFailBeforeCopy() {
        val f = fixture(); f.shell.bytes["inactive"] = ByteArray(2048)
        expectFailure { f.manager.prepare() }
        assertEquals(0, f.shell.snapshotCalls)
    }

    @Test fun prepareIsReadOnlyAndAllBackupsAreVerified() {
        val f = fixture(); val old = f.shell.bytes.mapValues { it.value.copyOf() }
        val plan = f.manager.prepare()
        assertEquals(Slot.A, plan.currentSlot); assertEquals(Slot.B, plan.targetSlot)
        assertEquals(0, f.shell.writeCalls)
        old.forEach { (key, bytes) -> assertArrayEquals(bytes, f.shell.bytes[key]) }
        assertArrayEquals(old["inactive"], plan.backups.inactiveLk.file.readBytes())
        assertArrayEquals(old["vendor"], plan.backups.inactiveVendorBoot.file.readBytes())
        assertEquals("", plan.backups.inactiveVendorBoot.sha256); assertEquals("", plan.patchedVendorBoot.sha256)
        assertFalse(plan.backups.inactiveVendorBoot.file.readBytes().contentEquals(plan.patchedVendorBoot.file.readBytes()))
        assertEquals(2, f.manager.listRecoveries().size)
        assertFalse(f.shell.scripts.any { it.contains("blockdev --setrw") })
    }

    @Test fun nativeLegacyDigestFieldIsIgnoredAndNotNeeded() {
        val f = fixture(); f.corruptNativeHash = true
        val plan = f.manager.prepare()
        assertEquals("", plan.patchedVendorBoot.sha256)
        assertEquals(0, f.shell.writeCalls)
        assertEquals(2, f.manager.listRecoveries().size)
    }

    @Test fun nativeErrorPreservesCompletedBackups() {
        val f = fixture(); f.failNative = true
        expectFailure { f.manager.prepare() }
        assertEquals(2, f.manager.listRecoveries().size)
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun modifiedPreparedFileIsRejectedBeforeWriteScript() {
        val f = fixture(); val plan = f.manager.prepare()
        plan.backups.currentLk.file.appendText("tampered")
        expectFailure { f.manager.confirmAndWrite(plan.id) }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun slotChangeBetweenPreviewAndConfirmationRejectsWrites() {
        val f = fixture(); val plan = f.manager.prepare(); f.shell.slot = "_b"
        assertFalse(f.manager.confirmAndWrite(plan.id).success)
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun changedInactiveContentDoesNotGateConfirmedCandidateAndFreshRollbackKeepsIt() {
        val f = fixture(); val plan = f.manager.prepare()
        f.shell.bytes.getValue("vendor")[8] = 0x7f
        val latest = f.shell.bytes.getValue("vendor").copyOf()
        val result = f.manager.confirmAndWrite(plan.id)
        assertTrue(result.success)
        assertEquals(1, f.shell.writeCalls)
        val fresh = f.manager.listRecoveries().single { it.id != plan.id && it.kind == BackupKind.INACTIVE_VENDOR_BOOT }
        assertArrayEquals(latest, fresh.image.file.readBytes())
        assertArrayEquals(plan.patchedVendorBoot.file.readBytes(), f.shell.bytes["vendor"])
    }

    @Test fun successfulWritePreservesCurrentSlotAndScpAndCannotBeReused() {
        val f = fixture(); val plan = f.manager.prepare()
        val current = f.shell.bytes.getValue("current").copyOf(); val scp = f.shell.bytes.getValue("scp").copyOf()
        val result = f.manager.confirmAndWrite(plan.id)
        assertTrue(result.success)
        assertArrayEquals(current, f.shell.bytes["inactive"])
        assertArrayEquals(current, f.shell.bytes["current"])
        assertArrayEquals(scp, f.shell.bytes["scp"])
        assertArrayEquals(plan.patchedVendorBoot.file.readBytes(), f.shell.bytes["vendor"])
        assertEquals("_a", f.shell.slot)
        expectFailure { f.manager.confirmAndWrite(plan.id) }
        val script = f.shell.scripts.last()
        assertFalse(script.contains("blockdev --setrw '/dev/block/by-name/lk_a'"))
        assertFalse(script.contains("blockdev --setrw '/dev/block/by-name/scp_b'"))
        assertFalse(script.contains("READBACK_"))
        assertTrue(result.message.contains("未检查"))
    }

    @Test fun partialSecondWriteReportsBothRollbackResults() {
        val f = fixture(); val plan = f.manager.prepare(); f.shell.failSecondWrite = true
        val inactive = f.shell.bytes.getValue("inactive").copyOf(); val vendor = f.shell.bytes.getValue("vendor").copyOf()
        val result = f.manager.confirmAndWrite(plan.id)
        assertFalse(result.success)
        assertEquals(mapOf("LK" to "COMMAND_OK_NO_BLOCK_VERIFY", "VENDOR_BOOT" to "COMMAND_OK_NO_BLOCK_VERIFY"), result.rollback)
        assertArrayEquals(inactive, f.shell.bytes["inactive"]); assertArrayEquals(vendor, f.shell.bytes["vendor"])
        val script = f.shell.scripts.last()
        assertTrue(script.indexOf("emit ROLLBACK_VENDOR_BOOT") < script.indexOf("emit ROLLBACK_LK"))
    }

    @Test fun recoveryRejectsAFormerInactivePartitionNowActive() {
        val f = fixture(); val plan = f.manager.prepare(); f.shell.slot = "_b"
        expectFailure { f.manager.prepareRestore(plan.id, BackupKind.INACTIVE_LK) }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun singlePartitionRecoveryHasItsOwnPreviewAndRescueBackup() {
        val f = fixture(); val prepared = f.manager.prepare()
        assertTrue(f.manager.confirmAndWrite(prepared.id).success)
        val vendorBefore = f.shell.bytes.getValue("vendor").copyOf()
        val recovery = f.manager.prepareRestore(prepared.id, BackupKind.INACTIVE_LK)
        assertArrayEquals(f.shell.bytes["inactive"], recovery.rescueBackup.file.readBytes())
        assertEquals(1, f.shell.writeCalls)
        assertTrue(f.manager.confirmRestore(recovery.id).success)
        assertArrayEquals(prepared.backups.inactiveLk.file.readBytes(), f.shell.bytes["inactive"])
        assertArrayEquals(vendorBefore, f.shell.bytes["vendor"])
        expectFailure { f.manager.confirmRestore(recovery.id) }
    }

    @Test fun interruptedManifestIsListedWithoutRootOrAutomaticResume() {
        val f = fixture(); val prepared = f.manager.prepare()
        val manifest = File(prepared.backups.directory, "manifest.properties")
        val properties = Properties().apply { manifest.inputStream().use { load(it) }; setProperty("state", "WRITING") }
        manifest.outputStream().use { properties.store(it, null) }
        val callsBefore = f.shell.scripts.size
        val restartedManager = f.manager
        assertEquals(prepared.id, restartedManager.listInterruptedTransactions().single().id)
        assertEquals(2, restartedManager.listRecoveries().size)
        assertEquals(callsBefore, f.shell.scripts.size)
        expectFailure { restartedManager.confirmAndWrite(prepared.id) }
    }

    @Test fun maliciousIdIsRejectedWithoutRoot() {
        val f = fixture()
        expectFailure { f.manager.exportableFiles("../../other") }
        assertTrue(f.shell.scripts.isEmpty())
    }

    @Test fun concurrentManagersCannotPrepareTogether() {
        val f = fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.shell.probeEntered = entered; f.shell.holdProbe = release
        var firstError: Throwable? = null
        val worker = thread { try { f.manager.prepare() } catch (e: Throwable) { firstError = e } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        expectFailure { f.manager.prepare() }
        release.countDown(); worker.join(10_000)
        assertFalse(worker.isAlive); assertNull(firstError)
    }

    @Test fun currentSourceContentChangesDoNotAddAnUndisclosedContentGate() {
        val f = fixture(); val plan = f.manager.prepare()
        f.shell.bytes.getValue("current")[4] = 0x7f
        assertTrue(f.manager.confirmAndWrite(plan.id).success)
        assertArrayEquals(plan.backups.currentLk.file.readBytes(), f.shell.bytes["inactive"])
    }

    @Test fun identityChangeRejectsBeforeWritingWithoutAContentDigest() {
        val f = fixture(); val plan = f.manager.prepare(); f.shell.identityChanged = true
        assertFalse(f.manager.confirmAndWrite(plan.id).success)
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun schema2StoresNoDigestsAndOnlyByNameDeviceMetadata() {
        val f = fixture(); val plan = f.manager.prepare()
        val m = Properties().apply { File(plan.backups.directory, "manifest.properties").inputStream().use { load(it) } }
        assertEquals("2", m.getProperty("schema"))
        for (key in listOf("current", "inactive", "vendor", "scp")) {
            assertNull(m.getProperty("$key.hash"))
            assertTrue(m.getProperty("$key.path").startsWith("/dev/block/by-name/"))
            assertTrue(m.getProperty("$key.identity").contains(':'))
            assertNull(m.getProperty("backup.$key.hash"))
        }
        assertEquals("", plan.targetLk.sha256); assertEquals("", plan.targetVendorBoot.sha256)
        assertNull(m.getProperty("patched.hash"))
    }

    @Test fun legacyRecoveryIgnoresPhysicalNumbersAndUsesSlotDerivedByName() {
        val f = fixture(); val plan = f.manager.prepare()
        val manifest = File(plan.backups.directory, "manifest.properties")
        val m = Properties().apply { manifest.inputStream().use { load(it) } }
        m.remove("schema"); m.setProperty("state", "APPLIED")
        for (key in listOf("current", "inactive", "vendor", "scp")) {
            m.remove("$key.identity"); m.setProperty("$key.path", "/dev/block/sdc999")
            m.setProperty("$key.hash", "0".repeat(64))
        }
        manifest.outputStream().use { m.store(it, null) }
        assertTrue(f.manager.listRecoveries().all { it.targetCanonicalPath.startsWith("/dev/block/by-name/") })
        val recovery = f.manager.prepareRestore(plan.id, BackupKind.INACTIVE_LK)
        assertTrue(f.manager.confirmRestore(recovery.id).success)
        assertTrue(f.shell.scripts.none { it.contains("sdc999") || it.contains("readlink") })
    }

    @Test fun failedWriteRollsBackToFreshOtaContentRatherThanOldPreparationBackup() {
        val f = fixture(); val plan = f.manager.prepare()
        f.shell.bytes.getValue("inactive")[20] = 0x55
        f.shell.bytes.getValue("vendor")[20] = 0x66
        val latestLk = f.shell.bytes.getValue("inactive").copyOf(); val latestVendor = f.shell.bytes.getValue("vendor").copyOf()
        f.shell.failSecondWrite = true
        assertFalse(f.manager.confirmAndWrite(plan.id).success)
        assertArrayEquals(latestLk, f.shell.bytes["inactive"]); assertArrayEquals(latestVendor, f.shell.bytes["vendor"])
        assertFalse(plan.backups.inactiveVendorBoot.file.readBytes().contentEquals(latestVendor))
    }

    @Test fun nativeStructuralFailureStopsBeforeWrites() {
        val f = fixture(); f.badStructure = true
        expectFailure { f.manager.prepare() }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun invalidBootCheckReportsStopPreparationBeforeWrites() {
        for (mode in listOf("missing", "false", "short", "byte0", "byte1", "nonhex")) {
            val f = Fixture(temporary.newFolder("backups-$mode"))
            when (mode) {
                "missing" -> f.omitBtLogReport = true
                "false" -> f.btLogCompatible = false
                "short" -> f.btLogLength = 3
                "byte0" -> f.btLogPrefix = "00100000"
                "byte1" -> f.btLogPrefix = "11200000"
                "nonhex" -> f.btLogPrefix = "1110xxxx"
            }
            expectFailure { f.manager.prepare() }
            assertEquals(mode, 0, f.shell.writeCalls)
        }
    }

    @Test fun existingBootCheckExtraBitsAndLengthAreAccepted() {
        val f = fixture(); f.btLogLength = 12; f.btLogPrefix = "111f80ff"
        assertTrue(f.manager.confirmAndWrite(f.manager.prepare().id).success)
    }

    @Test fun oldPreparedReportRequiresFreshPreparation() {
        val f = fixture(); val plan = f.manager.prepare()
        val reportFile = File(plan.backups.directory, "native_report.json")
        val report = JSONObject(reportFile.readText())
        report.getJSONArray("dtbs").getJSONObject(0).remove("btLogCompatible")
        reportFile.writeText(report.toString())
        expectFailure { f.manager.confirmAndWrite(plan.id) }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun everyDtbNeedsBootCheckSuccessBeforeConfirmation() {
        val f = fixture(); val plan = f.manager.prepare()
        val file = File(plan.backups.directory, "native_report.json")
        val report = JSONObject(file.readText())
        report.getJSONArray("dtbs").put(JSONObject().put("btLogCompatible", false))
        file.writeText(report.toString())
        expectFailure { f.manager.confirmAndWrite(plan.id) }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun emptyDtbReportStopsConfirmation() {
        val f = fixture(); val plan = f.manager.prepare()
        val file = File(plan.backups.directory, "native_report.json")
        file.writeText(JSONObject(file.readText()).put("dtbs", org.json.JSONArray()).toString())
        expectFailure { f.manager.confirmAndWrite(plan.id) }
        assertEquals(0, f.shell.writeCalls)
    }

    @Test fun sameLengthBackupModificationHasNoDigestGate() {
        val f = fixture(); val plan = f.manager.prepare()
        plan.backups.currentLk.file.writeBytes(ByteArray(1024) { 0x77 })
        assertTrue(f.manager.confirmAndWrite(plan.id).success)
        assertArrayEquals(ByteArray(1024) { 0x77 }, f.shell.bytes["inactive"])
    }
}
