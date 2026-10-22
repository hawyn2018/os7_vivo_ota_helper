package net.hawyn.vivootahelper

import android.content.Intent
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the real OtaApp with debug-only in-memory callbacks; no root transaction is started. */
@RunWith(AndroidJUnit4::class)
class PreparedPromptDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val title = "准备完成，是否开始刷写？"

    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it)?.let { found -> return found } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
    }

    private fun waitFor(label: String): AccessibilityNodeInfo {
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline) {
            find(label)?.let { return it }
            Thread.sleep(100)
        }
        error("Missing UI label: $label")
    }

    private fun control(label: String): AccessibilityNodeInfo {
        var node = waitFor(label)
        while (!node.isClickable) node = node.parent ?: error("No clickable control: $label")
        return node
    }

    private fun click(label: String) {
        val node = control(label)
        assertTrue("Enabled: $label", node.isEnabled)
        assertTrue("Clicked: $label", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }

    private fun scrollTo(label: String) {
        fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { scrollable(it)?.let { found -> return found } }
            return null
        }
        repeat(8) {
            if (find(label) != null) return
            instrumentation.uiAutomation.rootInActiveWindow?.let(::scrollable)
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
        }
    }

    private fun waitUntil(message: String, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(100)
        }
        assertTrue(message, predicate())
    }

    @Test fun preparationPromptsAutomaticallyAndRequiresExplicitConfirmation() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, PreparedPromptFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PreparedPromptFixtureActivity
        try {
            click("准备")
            waitFor(title)
            waitFor("fixture-backup")
            assertEquals(0, activity.confirmedWrites)
            assertFalse("Writing disabled before acknowledgement", control("开始写入").isEnabled)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(bitmap)
            File(context.filesDir, "prepared-dialog.png").outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
            click("返回核对")
            waitUntil("Dialog exit animation completes") { find(title) == null }
            Thread.sleep(400)
            assertNull("Dismissed dialog does not reopen", find(title))
            assertEquals(0, activity.confirmedWrites)
            scrollTo("核对并确认写入")
            click("核对并确认写入")
            waitFor(title)
            click("我已核对目标槽位和备份，确认开始。")
            waitUntil("Acknowledgement enables writing") { control("开始写入").isEnabled }
            assertTrue("Writing enabled after acknowledgement", control("开始写入").isEnabled)
            click("开始写入")
            waitUntil("Explicit confirmation calls fake writer") { activity.confirmedWrites == 1 }
            assertEquals("Only explicit confirmation invokes the fake callback", 1, activity.confirmedWrites)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
