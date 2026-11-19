package com.movieshell.data.ai

import android.content.Context

/**
 * v1.3.25 合并 3（AI Agent 工具化操控）：带工具循环的对话入口。
 *
 * LlmClient 没有原生 function-calling，用 JSON 代码块协议代替
 * （见 AppActionBus）：操作说明以 user 角色消息打头（Anthropic 的
 * messages 不接受 system 角色），历史只取最后 12 条——加上来回的
 * 动作消息后总长度仍在 LlmClient 的 20 条截断线以内，说明不会被截掉。
 * 最多 3 轮动作；模型回纯文字即最终答案（前面执行过的动作会以
 * “已执行：…”附在答案前面，让用户看得见 AI 动了什么）。
 * 修源/写源流程不走这里（它们直接调 LlmClient.chat，不受影响）。
 */
object AiAgent {

    private const val MAX_ROUNDS = 3
    private const val MAX_HISTORY = 12

    suspend fun chat(context: Context, messages: List<LlmClient.ChatMessage>): LlmClient.ChatResult {
        val work = ArrayList<LlmClient.ChatMessage>(messages.size + 2 * MAX_ROUNDS + 1)
        work += LlmClient.ChatMessage("user", AppActionBus.toolsPrompt())
        work += messages.takeLast(MAX_HISTORY)
        val taken = ArrayList<String>()
        repeat(MAX_ROUNDS) {
            when (val r = LlmClient.chatMessages(context, work)) {
                is LlmClient.ChatResult.Fail -> return r
                is LlmClient.ChatResult.Ok -> {
                    val call = AppActionBus.parseActionCall(r.text)
                    if (call == null) {
                        val text = if (taken.isEmpty()) r.text
                        else "（已执行：${taken.joinToString("；")}）\n${r.text}"
                        return LlmClient.ChatResult.Ok(text, r.ms)
                    }
                    val result = AppActionBus.dispatch(context, call.id, call.args)
                    taken += "${call.id}：${result.summary}"
                    work += LlmClient.ChatMessage("assistant", r.text)
                    work += LlmClient.ChatMessage(
                        "user",
                        "执行结果：${result.summary}。请继续完成用户的请求：需要再操作就再发一个动作块，不需要就直接回复最终结果。",
                    )
                }
            }
        }
        return LlmClient.ChatResult.Ok("已执行：${taken.joinToString("；")}。", 0L)
    }
}
