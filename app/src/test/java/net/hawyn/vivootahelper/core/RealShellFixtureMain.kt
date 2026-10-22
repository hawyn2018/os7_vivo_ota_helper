package net.hawyn.vivootahelper.core

import java.io.File
import java.util.Properties
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray

/** Executes actual generated inner sh on ordinary fixtures; no su/ADB/physical device access. */
object RealShellFixtureMain {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val bash = args[0]; val root = File(args[1]).canonicalFile.apply { mkdirs() }
        require(!root.path.startsWith("/dev/"))
        val results = mutableListOf<String>()
        for (mode in listOf("success", "partial_lk", "partial_vendor", "slot_changed", "identity_changed", "capacity_changed", "inactive_changed", "source_changed", "silent_corruption")) {
            val fixture = File(root, mode).apply { mkdirs() }; val aliases = File(fixture, "by-name").apply { mkdirs() }
            val partitions = linkedMapOf("current" to File(aliases, "lk_a").apply { writeBytes(ByteArray(1024) { 0x11 }) }, "inactive" to File(aliases, "lk_b").apply { writeBytes(ByteArray(1024) { 0x22 }) }, "vendor" to File(aliases, "vendor_boot_b").apply { writeBytes(ByteArray(4096) { 0x33 }) }, "scp" to File(aliases, "scp_b").apply { writeBytes(ByteArray(1024) { 0x44 }) })
            val original = partitions.mapValues { it.value.readBytes() }; val backupRoot = File(fixture, "backups").apply { mkdirs() }
            val id = UUID.randomUUID().toString(); val dir = File(backupRoot, id).apply { mkdir() }
            mapOf("current" to "current_lk.img", "inactive" to "inactive_lk.img", "vendor" to "inactive_vendor_boot.img", "scp" to "inactive_scp_readonly.img").forEach { (key, name) -> File(dir, name).writeBytes(original.getValue(key)) }
            val output = File(dir, "patched_vendor_boot.img").apply { writeBytes(original.getValue("vendor").copyOf().also { it[100] = 0x7f }) }
            val nativeReport = JSONObject().put("ok", true).put("schema", 2).put("structureValidated", true)
                .put("outputPath", output.path).put("outputSize", output.length()).put("changed", true)
                .put("fullMetadataRepair", false).put("scpDramSize", 0x1d0000)
                .put("avb", JSONObject().put("present", false).put("resigned", false).put("metadataStatus", "absent").put("imageStatus", "modified").put("verificationStatus", "unverified"))
                .put("fragments", JSONArray()).put("warnings", JSONArray())
                .put("dtbs", JSONArray().put(JSONObject().put("btLogCompatible", true).put("btLogLength", 4)
                    .put("btLogPrefix", "11100000").put("btLogAdded", true).put("btLogChanged", true)))
            File(dir, "native_report.json").writeText(nativeReport.toString()); File(dir, "transaction.log").writeText("")
            val identities = mapOf("current" to "8:1", "inactive" to "8:2", "vendor" to "8:3", "scp" to "8:4")
            val metadata = Properties().apply {
                setProperty("schema", "2"); setProperty("id", id); setProperty("state", "PREPARED"); setProperty("backups.complete", "true"); setProperty("slot", "_a")
                partitions.forEach { (key, file) -> setProperty("$key.path", "/dev/block/by-name/${file.name}"); setProperty("$key.bytes", file.length().toString()); setProperty("$key.identity", identities.getValue(key)); setProperty("backup.$key.bytes", file.length().toString()) }
                setProperty("patched.bytes", output.length().toString())
            }
            File(dir, "manifest.properties").outputStream().use { metadata.store(it, null) }
            val slotFile = File(fixture, "slot").apply { writeText(if (mode == "slot_changed") "_b" else "_a") }; val flag = File(fixture, "injection_done")
            if (mode == "capacity_changed") partitions.getValue("vendor").appendBytes(byteArrayOf(0x7f))
            if (mode == "inactive_changed") { partitions.getValue("inactive").writeBytes(ByteArray(1024) { 0x55 }); partitions.getValue("vendor").writeBytes(ByteArray(4096) { 0x66 }) }
            if (mode == "source_changed") partitions.getValue("current").writeBytes(ByteArray(1024) { 0x77 })
            val latest = partitions.mapValues { it.value.readBytes() }; val failedName = if (mode == "partial_lk") "lk_b" else "vendor_boot_b"
            val backend = object : RootShell {
                override fun execute(script: String, onOutput: (String) -> Unit): ShellResult {
                    check(script.contains("exec 0<>") && script.contains("flock -n 0 ||"))
                    var inner = script.substringAfter("sh -c '").removeSuffix("'").replace("'\\''", "'")
                    check(!inner.contains("readlink") && !inner.contains("sha256") && !inner.contains("cmp "))
                    check(!Regex("/dev/block/(?!by-name/)").containsMatchIn(inner))
                    fun posix(f: File) = f.absolutePath.replace('\\', '/').let { if (it.length > 2 && it[1] == ':') "/${it[0].lowercaseChar()}${it.substring(2)}" else it }
                    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
                    inner = inner.replace("/dev/block/by-name/", posix(aliases) + "/").replace("[ -b \"\$1\" ]", "[ -f \"\$1\" ]").replace("[ -b '", "[ -f '")
                    backupRoot.walkTopDown().toList().sortedByDescending { it.absolutePath.length }.forEach { inner = inner.replace(it.absolutePath, posix(it)) }
                    val prelude = """
                        id() { echo 0; }
                        getprop() { cat ${quote(posix(slotFile))}; }
                        flock() { return 0; }
                        sha256sum() { echo FORBIDDEN_DIGEST_CALL >&2; return 99; }
                        cmp() { echo FORBIDDEN_CONTENT_COMPARE >&2; return 99; }
                        stat() {
                          if [ "§1" = -Lc ]; then case "§3" in
                            */lk_a) echo 8:1 ;;
                            */lk_b) if [ ${quote(mode)} = identity_changed ]; then echo 8:9; else echo 8:2; fi ;;
                            */vendor_boot_b) echo 8:3 ;;
                            */scp_b) echo 8:4 ;;
                            *) return 98 ;; esac
                          else command stat "§@"; fi
                        }
                        blockdev() { case "§1" in --getro) echo 1 ;; --setrw|--setro) return 0 ;; --getsize64) command stat -c %s "§2" ;; *) return 99 ;; esac; }
                        dd() {
                          dd_target=''; for arg in "§@"; do case "§arg" in of=*) dd_target=§{arg#of=} ;; esac; done
                          if [ "§dd_target" = ${quote(posix(File(aliases, failedName)))} ] && [ ! -e ${quote(posix(flag))} ]; then
                            case ${quote(mode)} in
                              partial_*) command dd "§@" count=1 bs=512 conv=notrunc; touch ${quote(posix(flag))}; return 5 ;;
                              silent_corruption) command dd "§@" conv=notrunc || return; printf x | command dd of="§dd_target" bs=1 seek=2 conv=notrunc; touch ${quote(posix(flag))}; return 0 ;;
                            esac
                          fi
                          command dd "§@" conv=notrunc
                        }
                    """.trimIndent().replace('§', '$')
                    val scriptFile = File(fixture, "actual_inner_script.sh").apply { writeText("$prelude\n$inner\n") }
                    val process = ProcessBuilder(bash, scriptFile.absolutePath).redirectErrorStream(true).start()
                    val transcript = process.inputStream.bufferedReader().use { it.readText() }; val code = process.waitFor()
                    File(fixture, "transcript.txt").writeText(transcript); check(!transcript.contains("FORBIDDEN_"))
                    return ShellResult(code, transcript.replace(posix(aliases) + "/", "/dev/block/by-name/"))
                }
            }
            val manager = OtaTransactionManager(backupRoot, backend, VendorBootPatcher { _, _, _, _ -> error("not used") }, ExecutionLease { true })
            val result = manager.confirmAndWrite(id); val succeeds = mode in listOf("success", "inactive_changed", "source_changed", "silent_corruption")
            check(result.success == succeeds) { "$mode unexpected ${result.transcript}" }
            if (succeeds) {
                check(partitions.getValue("inactive").readBytes().contentEquals(original.getValue("current")))
                if (mode == "silent_corruption") check(!partitions.getValue("vendor").readBytes().contentEquals(output.readBytes())) else check(partitions.getValue("vendor").readBytes().contentEquals(output.readBytes()))
                check(result.message.contains("未检查"))
            } else {
                check(partitions.getValue("inactive").readBytes().contentEquals(latest.getValue("inactive")))
                check(partitions.getValue("vendor").readBytes().contentEquals(latest.getValue("vendor")))
                if (mode == "partial_lk") check(result.rollback["LK"] == "COMMAND_OK_NO_BLOCK_VERIFY")
                if (mode == "partial_vendor") check(result.rollback.values.all { it == "COMMAND_OK_NO_BLOCK_VERIFY" })
            }
            check(partitions.getValue("current").readBytes().contentEquals(latest.getValue("current"))); check(partitions.getValue("scp").readBytes().contentEquals(latest.getValue("scp")))
            if (mode in listOf("success", "inactive_changed", "partial_lk", "partial_vendor")) {
                val fresh = manager.listRecoveries().single { it.id != id && it.kind == BackupKind.INACTIVE_VENDOR_BOOT }
                check(fresh.image.file.readBytes().contentEquals(latest.getValue("vendor")))
            }
            results += "$mode PASS success=${result.success} rollback=${result.rollback}"
        }
        File(root, "host_shell_regression.txt").writeText(results.joinToString("\n", postfix = "\n") + "Ordinary fixtures only. Actual inner metadata guards/dd/sync/EXIT trap/rollback executed; device type/id/getprop/blockdev/outer flock replaced. No digest or byte-compare commands invoked. Silent corruption is deliberately undetected; success means dd+sync only. No ADB/su/device access.\n")
        results.forEach(::println)
    }
}
