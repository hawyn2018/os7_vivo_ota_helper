package net.hawyn.vivootahelper

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.hawyn.vivootahelper.core.NativeBridge
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Exercises JNI on ordinary app files. It never calls the root transaction service. */
@RunWith(AndroidJUnit4::class)
class NativeBridgeDeviceTest {
    @Test
    fun fullVendorFixtureContractAndIdempotence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = File(context.filesDir, "native_fixture")
        val vendor = File(fixture, "vendor_boot.img")
        val scp = File(fixture, "scp.img")
        assertTrue("Missing readable fixture: ${vendor.absolutePath}", vendor.isFile && vendor.canRead())
        assertTrue("Missing readable fixture: ${scp.absolutePath}", scp.isFile && scp.canRead())
        assertEquals("Expected the complete 128 MiB vendor_boot fixture", CAPACITY, vendor.length())
        assertTrue("SCP fixture is empty: ${scp.absolutePath}", scp.length() > 0)

        val work = File(context.cacheDir, "native-device-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        assertTrue("Create output directory: ${work.absolutePath}", work.mkdir())
        Log.i(TAG, "Artifacts retained at ${work.absolutePath}")
        instrumentation.sendStatus(0, Bundle().apply { putString("nativeFixtureOutputDir", work.absolutePath) })

        val scpSize = scp.length()
        val evidence = JSONObject()
            .put("outputDirectory", work.absolutePath)
            .put("vendorSource", vendor.absolutePath)
            .put("scpSource", scp.absolutePath)
            .put("scpSourceSize", scpSize)
            .put("capacity", CAPACITY)
            .put("partitionWrites", false)
        File(work, "fixture.json").writeText(evidence.toString(2))

        try {
            val output = File(work, "patched_vendor_boot.img")
            val firstJson = NativeBridge.patchVendor(vendor.absolutePath, output.absolutePath, scp.absolutePath, CAPACITY)
            File(work, "first_report.json").writeText(firstJson)
            val first = verifySuccess(JSONObject(firstJson), output)
            assertTrue("An original OS7 fixture must receive the compatibility changes", first.getBoolean("changed"))

            val secondOutput = File(work, "patched_vendor_boot_second.img")
            val secondJson = NativeBridge.patchVendor(output.absolutePath, secondOutput.absolutePath, scp.absolutePath, CAPACITY)
            File(work, "second_report.json").writeText(secondJson)
            val second = verifySuccess(JSONObject(secondJson), secondOutput)
            assertFalse("Second processing reports no further changes", second.getBoolean("changed"))
            assertEquals("Stable output size", first.getLong("outputSize"), second.getLong("outputSize"))
            assertEquals("Stable SCP DRAM parsing", first.getLong("scpDramSize"), second.getLong("scpDramSize"))

            // Existing complete output must be rejected before publishing another output.
            val existingSize = output.length()
            val failureJson = NativeBridge.patchVendor(vendor.absolutePath, output.absolutePath, scp.absolutePath, CAPACITY)
            File(work, "existing_output_report.json").writeText(failureJson)
            val failure = JSONObject(failureJson)
            assertBoolean(failure, "ok", false)
            assertEquals("Failure report schema", 2, failure.getInt("schema"))
            assertTrue("Existing-output error must be explicit", failure.getString("error").contains("output already exists"))
            assertEquals("Existing output size unchanged", existingSize, output.length())
            assertEquals("Original vendor size unchanged", CAPACITY, vendor.length())
            assertEquals("Original SCP size unchanged", scpSize, scp.length())

            evidence.put("passed", true)
                .put("outputSize", first.getLong("outputSize"))
                .put("secondPatchReportsUnchanged", true)
                .put("existingOutputRejected", true)
                .put("sourceLengthsPreserved", true)
            File(work, "result.json").writeText(evidence.toString(2))
            Log.i(TAG, "PASS ${work.absolutePath}")
        } catch (failure: Throwable) {
            evidence.put("passed", false).put("failure", failure.toString())
            File(work, "result.json").writeText(evidence.toString(2))
            Log.e(TAG, "FAIL; artifacts retained at ${work.absolutePath}", failure)
            throw failure
        }
    }

    private fun verifySuccess(report: JSONObject, output: File): JSONObject {
        assertBoolean(report, "ok", true)
        assertEquals("Success schema", 2, report.getInt("schema"))
        assertBoolean(report, "structureValidated", true)
        assertEquals("Exact requested output path", output.absolutePath, report.getString("outputPath"))
        assertEquals("Reported full partition size", CAPACITY, report.getLong("outputSize"))
        assertTrue("Output must be a regular file", output.isFile)
        assertEquals("Actual full partition size", CAPACITY, output.length())
        assertFalse("No output digest field", report.has("outputSha256"))
        assertTrue("changed is a JSON boolean", report.get("changed") is Boolean)
        assertBoolean(report, "fullMetadataRepair", false)
        val dram = report.getLong("scpDramSize")
        assertTrue("SCP DRAM is positive and 64 KiB aligned", dram > 0 && dram % 65536L == 0L)
        assertEquals("Known complete OS7 SCP fixture DRAM", 0x1d0000L, dram)

        val avb = report.getJSONObject("avb")
        assertBoolean(avb, "present", true)
        assertEquals("AVB metadata retained", "preserved", avb.getString("metadataStatus"))
        assertEquals("AVB verification not performed", "unverified", avb.getString("verificationStatus"))
        assertEquals("AVB image modification status", if (report.getBoolean("changed")) "modified" else "unchanged", avb.getString("imageStatus"))
        assertFalse("No computed AVB digest status", avb.has("digestStale"))
        assertBoolean(avb, "resigned", false)
        assertTrue("Supported vendor header", report.getInt("headerVersion") in 3..4)
        val fragments = report.getJSONArray("fragments")
        assertTrue("Every ramdisk fragment is reported", fragments.length() > 0)
        assertEquals("Fragment count contract", fragments.length(), report.getInt("fragmentCount"))
        for (index in 0 until fragments.length()) {
            val fragment = fragments.getJSONObject(index)
            assertEquals("Fragment index", index, fragment.getInt("index"))
            assertTrue("Known compression", fragment.getString("compression") in setOf("cpio", "gzip", "lz4-legacy"))
            assertTrue("Fragment changed boolean", fragment.get("changed") is Boolean)
            assertBoolean(fragment, "orphanModulePreserved", true)
            assertTrue("Module count", fragment.getInt("removedModules") >= 0)
            assertTrue("Index line count", fragment.getInt("changedIndexLines") >= 0)
            assertTrue("CPIO record count", fragment.getInt("records") > 0)
            assertTrue("Fragment input size", fragment.getLong("beforeSize") > 0)
            assertTrue("Fragment output size", fragment.getLong("afterSize") > 0)
        }
        val dtbs = report.getJSONArray("dtbs")
        assertTrue("DTB reports required", dtbs.length() > 0)
        for (index in 0 until dtbs.length()) {
            val dtb = dtbs.getJSONObject(index)
            assertBoolean(dtb, "btLogCompatible", true)
            assertTrue("Boot check field length", dtb.getInt("btLogLength") >= 4)
            assertTrue("Boot check consumer bytes", dtb.getString("btLogPrefix").matches(Regex("111[0-9a-f]{5}")))
            assertTrue("Boot check change flag", dtb.get("btLogChanged") is Boolean)
            assertTrue("Boot check insertion flag", dtb.get("btLogAdded") is Boolean)
            assertTrue("DTB input size", dtb.getLong("beforeSize") > 0)
            assertTrue("DTB output size", dtb.getLong("afterSize") > 0)
            assertTrue("Legacy DSI path", dtb.getString("dsiPath").startsWith("/soc/dsi@"))
            assertTrue("Real SCP path", dtb.getString("realScpPath").startsWith("/soc/scp@"))
            assertTrue("Helper path", dtb.getString("helperPath").startsWith("/soc/scp-lk-compat@"))
            assertEquals("Real node uses parsed firmware DRAM", dram, dtb.getLong("realDramSize"))
            val helperDram = dtb.getLong("helperDramSize")
            assertTrue("Helper preserves sufficient aligned configured DRAM", helperDram >= dram && helperDram % 65536L == 0L)
            // These constants identify this fixture regression, not production patch policy.
            assertEquals("Known OS7 fixture keeps original helper allocation capacity", 0x300000L, helperDram)
            assertEquals("Known OS7 fixture real DRAM matches firmware", 0x1d0000L, dtb.getLong("realDramSize"))
            assertEquals("Helper present empty status", 0, dtb.getInt("helperStatusLength"))
            assertBoolean(dtb, "kernelTablePreserved", true)
            assertBoolean(dtb, "fullMetadataRepair", false)
            assertTrue("Legacy ID row bounds", dtb.getInt("legacyRows") in 1..23)
            assertTrue("Folded tail size", dtb.getLong("foldedTailBytes") >= 0)
            assertTrue("Path update count", dtb.getInt("updatedPathProperties") >= 0)
            assertTrue("Diagnostic removal count", dtb.getInt("removedDiagnosticMarkers") >= 0)
        }
        val warnings = report.getJSONArray("warnings")
        assertTrue("Compatibility and AVB limitations are reported", warnings.length() >= 2)
        for (index in 0 until warnings.length()) {
            assertTrue("Warning text", warnings.get(index) is String && warnings.getString(index).isNotBlank())
        }
        return report
    }

    private fun assertBoolean(json: JSONObject, field: String, expected: Boolean) {
        assertTrue("$field must be a JSON boolean", json.get(field) is Boolean)
        assertEquals(field, expected, json.getBoolean(field))
    }

    companion object {
        private const val CAPACITY = 134217728L
        private const val TAG = "NativeBridgeDeviceTest"
    }
}
