package io.github.waittide.fontswitcher.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 跨 Activity 重建的任务状态。
 *
 * 为什么必须有这个：热重载一旦生效，系统会向**所有**进程广播配置变更，
 * 包括本 App 自己 —— `Activity` 会被销毁重建。如果把「应用字体」这件事跑在
 * `rememberCoroutineScope()` 里，重建瞬间协程就被取消，进度永远跑不完。
 * 所以任务跑在 App 级的 [CoroutineScope] 上，与任何 Activity 生命周期无关。
 */
object WorkState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var busy by mutableStateOf(false)
        private set

    /** 最近一次任务结束后自增，UI 可用它触发刷新。 */
    var finishedTick by mutableStateOf(0)
        private set

    /** 最近一次任务是否成功。 */
    var lastOk by mutableStateOf(true)
        private set

    /** 最近一次任务的失败原因（成功时为空），UI 用底部提示条弹出。 */
    var lastMessage by mutableStateOf("")
        private set

    /** 内部进度日志，只用于失败时兜底提示，不渲染。 */
    private var log = ""

    private fun append(msg: String) {
        log = if (log.isBlank()) msg else log + "\n" + msg
    }

    fun step(msg: String) = append(msg)

    /**
     * 后台执行 [block]，自动维护 [busy] / [lastOk] / [lastMessage] / [finishedTick]。
     * 异常统一转成一行失败文案，不会崩掉进程。
     */
    fun submit(title: String, reset: Boolean = true, block: () -> String) {
        if (busy) return
        busy = true
        if (reset) log = ""
        append(title)
        scope.launch {
            val result = try {
                block()
            } catch (t: Throwable) {
                "失败：${t.message ?: t.javaClass.simpleName}"
            }
            log = result
            // 约定位「失败：xxx」开头的即视为失败，其它都算成功
            lastOk = !result.trimStart().startsWith("失败")
            lastMessage = if (lastOk) "" else result.trim().lineSequence().first()
            busy = false
            finishedTick++
        }
    }
}
