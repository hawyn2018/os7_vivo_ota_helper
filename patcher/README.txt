Portable vendor_boot patcher

JNI contract
  package net.hawyn.vivootahelper.core; object NativeBridge
  external fun patchVendor(inputPath:String, outputPath:String,
                           scpPath:String, capacity:Long):String
  Library: vendorpatch / libvendorpatch.so.

All input paths must identify ordinary backup files. The library never chooses
an Android slot and never opens block devices for writing. It validates the
complete image structure in memory before creating a separate temporary output,
flushes and checks its length, then commits without replacing an existing
output. Root transaction/backup/partition capacity and flashing belong to the
Android service. inputPath and scpPath are preserved.
Android/Linux commit uses renameat2(RENAME_NOREPLACE), retaining O_EXCL temporary
creation and fsync. Unsupported kernels or denied syscalls return errno and its
description; there is no replacing-rename or hardlink fallback.

Success JSON has ok=true, schema=2, structureValidated=true, outputPath,
outputSize, scpDramSize, changed, fullMetadataRepair=false, avb, fragments, dtbs,
warnings. Failure JSON has ok=false, schema=2, error. There is no backup/output
digest computation or readback-comparison acceptance gate. Output capacity
is fixed to the requested inactive partition size, at most 1GiB.
AVB status is {present, metadataStatus:preserved|absent,
imageStatus:modified|unchanged, verificationStatus:unverified|not_present,
resigned:false}. The in-memory changed flag describes transformation only;
it is not an integrity check on a backup or the written output.

Implemented transformation
  * Vendor header v3 and v4, all ramdisk fragments, table entries/board IDs and
    bootconfig; gzip, legacy LZ4 and raw newc/CRC CPIO. Non-target CPIO records
    retain bytes/metadata/order. Unknown compression or concatenated CPIO fails.
  * Exact vr/vklp module removal and supported text module-index cleanup.
    vklp_smaps_rollup file stays; its exact autoload references are removed from
    modules.load and modules.load.*. There is no old firmware hash allow-list.
  * Android DT table v0, raw DTB or aligned concatenated DTBs. Each full DTB is
    validated with pinned libfdt. Primary dsi0 alias target becomes /soc/dsi;
    path-valued references change with it, while alias/symbol keys and phandles
    remain. MIPI PHY path/SoC compatible are preserved.
  * Real SCP full table stays. First-compatible helper fully clones register/IRQ
    properties, has no phandle and a present zero-length status property. Legacy
    IDs<23 are retained, tail23..25 sizes folded into ID22. Requires sorted unique
    IDs<=25, alignment0 and dynamic dump row0. Unknown schemas fail before output.
  * scpPath is scanned read-only for one consistent MTK header named exactly
    tinysys-scp-RV55_A_dram. Length is rounded to64KiB and applied to the real
    DRAM property. The helper retains the original configured DRAM capacity,
    which the old LK uses for its initial firmware allocation. Reprocessing
    validates the existing helper and preserves that capacity rather than
    copying the already-updated real value. Missing/malformed/unaligned, zero,
    over-1GiB or firmware-insufficient capacity fails before output. Each DTB
    report includes helperDramSize and realDramSize. chosen/scp-compat-*
    diagnostic properties are removed.
  * Each DTB exposes /chosen/bt_log to the new system/init consumer. Missing
    property becomes raw bytes 11 10 00 00. Existing length>=4 keeps its length,
    byte1 low nibble, byte2/3 and all tail bytes; only byte0 and byte1 high nibble
    are normalized to 11 and 1. Existing lengths0..3, or missing /chosen, fail
    before output. After packing, the complete property is checked against the
    expected bytes. Every dtbs[] report includes btLogCompatible=true,
    btLogLength, btLogPrefix (actual first4 bytes as lowercase8-digit hex),
    btLogAdded and btLogChanged. This is an ABI compatibility field, not a
    firmware signature or an authentication certificate.
  * Original AVB metadata bytes stay, unsigned footer placement is updated.
    Descriptor bounds are checked structurally. Digest/signature verification
    is not performed, and no staleness result is computed. No signing occurs.

Compatibility boundary
This ports the successful V3 allocation/display/rmvr behavior. It does not
program the new secure-world SCP metadata IDs23..25 and does not inject the
rejected kernel-entry040F replay. Enlarged ID22 and unverified low-power network
offload remain explicit in reports. No model or OS-version checks are made;
binary structure/schema checks are required to avoid corrupting unknown input.

Build
  Android: ndk-build NDK_PROJECT_PATH=null APP_BUILD_SCRIPT=<absolute>/Android.mk
    NDK_APPLICATION_MK=<absolute>/Application.mk
  AGP can use externalNativeBuild.ndkBuild with the same Android.mk.
  Host: python build_host.py (local clang/clang++), or standard CMake.
  Host CLI: vendorpatch_cli INPUT OUTPUT SCP_BACKUP CAPACITY_BYTES
  Tests: python tests/validate_host.py

Host tests use development-only byte comparisons with reviewed outputs; these
are not application acceptance checks. Tests write only local generated
fixtures/reports under build/. Third-party
license notices and pinned revisions are in THIRD_PARTY_NOTICES.txt.
