package com.wangzan101.mika.testworkspace

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.usage.RunUsageTally
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Independent per-phase tallies: never add them to Mika's execution-only ModelCalls total. */
object MpRunCosts {
    const val EVENT = "mp_cost_phase"
    private const val STARTED = "mp_cost_started"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val labels = linkedMapOf("execution" to "执行与断言核查", "outcome" to "结局判定", "learning" to "后台学习")

    @Serializable
    data class Phase(
        val phase: String, val durationMs: Long, val status: String,
        val calls: Int, val httpCalls: Int?, val unreported: Int, val httpUnreported: Int?,
        val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
    )

    suspend fun <T> measure(
        phase: String, tally: RunUsageTally = RunUsageTally(),
        write: suspend (RunEvent) -> Unit, block: suspend () -> T,
    ): T {
        val started = System.nanoTime()
        runCatching { write(RunEvent.State(System.currentTimeMillis(), STARTED, phase)) }
        var status = "interrupted"
        try {
            return withContext(tally) { block() }.also { status = "completed" }
        } finally {
            val usage = tally.event(System.currentTimeMillis())
            val row = Phase(phase, (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000, status,
                usage.calls, usage.httpCalls, usage.unreported, usage.httpUnreported, usage.inputTokens, usage.outputTokens, usage.cachedTokens)
            withContext(NonCancellable) { runCatching { write(RunEvent.State(usage.atMs, EVENT, json.encodeToString(row))) } }
        }
    }

    fun rows(events: List<RunEvent>): List<Phase> = events.filterIsInstance<RunEvent.State>()
        .filter { it.name == EVENT }.mapNotNull { runCatching { json.decodeFromString<Phase>(it.detail) }.getOrNull() }
        .groupBy { it.phase }.map { it.value.last() }

    fun report(events: List<RunEvent>): List<String> {
        val rows = rows(events).associateBy { it.phase }
        val started = events.filterIsInstance<RunEvent.State>().filter { it.name == STARTED }.map { it.detail }.toSet()
        return labels.map { (phase, label) ->
            val row = rows[phase]
            if (row == null) "$label：${if (phase in started) "尚未完成；用量未知" else "未记录（旧版本或尚未开始）；用量未知"}"
            else {
                val unknown = if (row.unreported > 0 || (row.httpUnreported ?: 0) > 0) "；存在未上报请求，总 token 未知" else ""
                "$label：${row.durationMs} 毫秒；模型调用 ${row.calls} 次；HTTP 请求 ${row.httpCalls ?: "未知"} 次；" +
                    "已上报输入/输出/缓存 token ${row.inputTokens}/${row.outputTokens}/${row.cachedTokens}；" +
                    "未上报用量 ${row.unreported} 次；未完成用量的 HTTP 请求 ${row.httpUnreported ?: "未知"} 次" +
                    (if (row.status == "completed") "" else "；阶段中断，数据不完整") + unknown
            }
        } + "耗时包含等待；缓存 token 已包含在输入中。缺失用量不按零计算，各阶段用量与执行小结不要重复相加。"
    }
}
