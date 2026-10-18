package io.github.waittide.fontswitcher.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream

/**
 * 极简 root 执行器。不依赖 libsu，直接用 `su -c`。
 *
 * 为什么不用 libsu：只有一个需求（跑几条固定命令 + 灌一个二进制到 stdin），
 * 引入一个 1MB 的库不划算，而且 libsu 的多进程服务模型对本项目是负担。
 */
object RootShell {

    data class Result(val exitCode: Int, val output: String) {
        val ok: Boolean get() = exitCode == 0
    }

    private const val SU_TIMEOUT_MS = 60_000L

    /** 粗略判断 su 是否可用（真授权要等用户点确认，这里只做存在性检查）。 */
    fun suBinaryExists(): Boolean {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/debug_ramdisk/su", "/data/adb/ksu/bin/su", "/data/adb/ap/bin/su",
        )
        if (candidates.any { File(it).exists() }) return true
        // Magisk/KernelSU 的 su 也可能只在 PATH 里
        val path = System.getenv("PATH") ?: ""
        return path.split(':').any { File(it, "su").exists() }
    }

    /** 执行一段 sh 脚本，返回 exit code 与合并后的 stdout/stderr。 */
    fun run(script: String, stdin: InputStream? = null, timeoutMs: Long = SU_TIMEOUT_MS): Result {
        return try {
            val pb = ProcessBuilder("su", "-c", script)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            if (stdin != null) {
                Thread {
                    try {
                        proc.outputStream.use { out: OutputStream -> stdin.copyTo(out, 64 * 1024) }
                    } catch (_: Throwable) {
                    }
                }.apply { isDaemon = true; start() }
            } else {
                try {
                    proc.outputStream.close()
                } catch (_: Throwable) {
                }
            }
            val sb = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val started = System.currentTimeMillis()
            val pump = Thread {
                try {
                    var line = reader.readLine()
                    while (line != null) {
                        sb.append(line).append('\n')
                        line = reader.readLine()
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            val finished = proc.waitFor(timeoutMs.coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                pump.join(500)
                return Result(-1, sb.toString() + "\n[超时 ${System.currentTimeMillis() - started} ms，已终止]")
            }
            pump.join(1000)
            Result(proc.exitValue(), sb.toString())
        } catch (t: Throwable) {
            Result(-1, "[无法执行 su: ${t.javaClass.simpleName}: ${t.message}]")
        }
    }

    /** 单条命令，非 0 退出即抛异常，便于串行流程早退。 */
    fun require(script: String, what: String) {
        val r = run(script)
        if (!r.ok) throw RootException("$what 失败 (exit=${r.exitCode})\n${r.output.trim()}")
    }
}

class RootException(message: String) : Exception(message)
