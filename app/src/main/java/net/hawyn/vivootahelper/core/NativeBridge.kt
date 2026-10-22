package net.hawyn.vivootahelper.core

object NativeBridge {
    init { System.loadLibrary("vendorpatch") }
    external fun patchVendor(inputPath: String, outputPath: String, scpPath: String, capacity: Long): String
}
