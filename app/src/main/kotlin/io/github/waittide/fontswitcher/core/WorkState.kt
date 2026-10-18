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
 * `rememberCoroutineScope()` 里，重建瞬间协程就被取消，日志永远写不进最后几行，
 * 用户会看到界面「闪一下回到初始态、按钮变灰」。所以：
 *
 *   · 日志、进度放在这个单例里（Compose 的 `mutableStateOf`，重建后自动回读）
 *   · 任务跑在 App 级的 [CoroutineScope] 上，与任何 Activity 生命周期无关
 */
object WorkState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var log by mutableStateOf("")
        private set

    var busy by mutableStateOf(false)
        private set

    /** 最近一次任务结束后自增，UI 可用它触发刷新。 */
    var finishedTick by mutableStateOf(0)
        private set

    fun append(msg: String) {
        log = if (log.isBlank()) msg else log + "\n" + msg
    }

    /**
     * 后台执行 [block]，自动维护 [busy] / [log] / [finishedTick]。
     * 传 [reset] 会先清空日志。异常统一转成一行失败文案，不会崩掉进程。
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
            log = if (result.startsWith(title)) result else "$title\n$result"
            busy = false
            finishedTick++
        }
    }

    /** 给 task 内部逐行追加日志用（与 [append] 同义，语义更清楚）。 */
    fun step(msg: String) = append(msg)
}
