package net.hawyn.vivootahelper.core

import java.io.BufferedReader
import java.io.InputStreamReader

/** Blocking implementation: call from a foreground-service worker, never the UI thread. */
class SuRootShell : RootShell {
    override fun execute(script: String, onOutput: (String) -> Unit): ShellResult {
        val process = try {
            ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
        } catch (e: Exception) {
            throw TransactionException("启动 su 失败：${e.message}", e)
        }
        val output = StringBuilder()
        // Continue draining even after the transcript limit; otherwise a full pipe can deadlock dd.
        BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (output.length < MAX_TRANSCRIPT) output.append(line).append('\n')
                try { onOutput(line) } catch (_: Exception) { /* Observers must not interrupt writes. */ }
            }
        }
        val code = try {
            process.waitFor()
        } catch (e: InterruptedException) {
            // A caller cancellation must not kill an in-flight dd/rollback subprocess.
            var result: Int
            while (true) {
                try { result = process.waitFor(); break } catch (_: InterruptedException) { }
            }
            Thread.currentThread().interrupt()
            result
        }
        return ShellResult(code, output.toString())
    }

    companion object { private const val MAX_TRANSCRIPT = 2 * 1024 * 1024 }
}
