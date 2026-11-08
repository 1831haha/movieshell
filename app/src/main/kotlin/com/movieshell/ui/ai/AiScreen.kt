package com.movieshell.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.store.AiChatStore
import com.movieshell.data.ai.AiRepairEngine
import com.movieshell.data.ai.AiRepairSettings
import com.movieshell.data.ai.LlmClient
import com.movieshell.data.source_mgr.SourceProbe
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.V5Divider
import com.movieshell.ui.components.dockClearance
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.launch

/** 对话消息里规则的落盘状态（不用枚举：check_imports 不认枚举条目，怕撞名）。 */
private const val APPLY_IDLE = 0
private const val APPLY_CHECKING = 1
private const val APPLY_PASSED = 2
private const val APPLY_FAILED = 3
private const val APPLY_SAVED = 4

/** v1.1.3：对话里的一条消息。AI 消息能抠出规则时，下方给探活验证 / 保存按钮。 */
@Composable
private fun MessageRow(msg: AiChatStore.ChatMsg, onAskAi: (String) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = MaterialTheme.colorScheme
    val isUser = msg.role == "user"
    val rule = remember(msg.text) { AiRepairEngine.extractRule(msg.text) }
    val kind = remember(rule) { if (isUser) null else AiRepairEngine.detectKind(rule) }
    var applyState by remember { mutableIntStateOf(APPLY_IDLE) }
    var applyText by remember { mutableStateOf("") }
    // 探活/保存失败的原因原文，留给“发给 AI 接着改”拼消息用。
    var failReason by remember { mutableStateOf("") }

    fun checkRule() {
        val k = kind ?: return
        applyState = APPLY_CHECKING
        applyText = ""
        scope.launch {
            when (val probe = AiRepairEngine.probeRule(context, k, rule)) {
                is SourceProbe.Result.Ok, is SourceProbe.Result.Slow -> {
                    applyState = APPLY_PASSED
                    applyText = "验证通过，可以保存"
                }
                is SourceProbe.Result.Fail -> {
                    applyState = APPLY_FAILED
                    failReason = probe.reason
                    applyText = "验证没过：${probe.reason}"
                }
                is SourceProbe.Result.Unsupported -> {
                    applyState = APPLY_FAILED
                    failReason = "不支持"
                    applyText = "验证没过：不支持"
                }
            }
        }
    }

    fun saveRule(asReplace: Boolean) {
        val k = kind ?: return
        val target = AiChatStore.replaceTarget
        val replaceFile = if (asReplace) {
            target?.let { AiRepairEngine.ruleFile(context, it.first, it.second)?.takeIf { file -> file.isFile } }
        } else {
            null
        }
        val savingAsReplace = replaceFile != null
        applyState = APPLY_CHECKING
        applyText = ""
        scope.launch {
            val nameHint = target?.second.orEmpty()
            when (val r = AiRepairEngine.applyRuleFromChat(context, k, nameHint, rule, replaceFile)) {
                is AiRepairEngine.ApplyResult.Ok -> {
                    applyState = APPLY_SAVED
                    applyText = if (savingAsReplace) "已替换原源：${r.fileName}（${r.ms}ms）" else "已保存为新源：${r.fileName}（${r.ms}ms）"
                }
                is AiRepairEngine.ApplyResult.Fail -> {
                    applyState = APPLY_FAILED
                    failReason = r.reason
                    applyText = "没保存：${r.reason}"
                }
            }
        }
    }

    val isPureRule = !isUser && kind != null && rule == msg.text.trim()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(
                        if (isUser) v.primaryContainer else v.surfaceContainerHighest,
                        RoundedCornerShape(V5.CornerMd),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = msg.text,
                    style = if (isPureRule) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (isUser) v.onPrimaryContainer else v.onSurface,
                )
            }
        }
        if (!isUser && kind != null && !isPureRule) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v.surfaceContainer, RoundedCornerShape(V5.CornerMd))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = v.onSurface,
                )
            }
        }
        if (!isUser && kind != null) {
            if (applyText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = applyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = v.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            when (applyState) {
                APPLY_IDLE, APPLY_FAILED -> {
                    Row {
                        AppButton(
                            text = if (applyState == APPLY_FAILED) "再验证一次" else "探活验证",
                            variant = AppButtonVariant.Ghost,
                            onClick = { checkRule() },
                        )
                        // 验证失败不用手动复制：失败原因一键发回给 AI 接着改。
                        if (applyState == APPLY_FAILED && failReason.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            AppButton(
                                text = "发给 AI 接着改",
                                variant = AppButtonVariant.Ghost,
                                onClick = {
                                    onAskAi("刚才那条规则探活验证没过：$failReason，请接着改，只返回修好的完整规则，不要解释。")
                                },
                            )
                        }
                    }
                }
                APPLY_CHECKING -> {
                    Text(
                        text = "验证中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = v.onSurfaceVariant,
                    )
                }
                APPLY_PASSED -> {
                    Row {
                        AppButton(
                            text = "保存为新源",
                            onClick = { saveRule(false) },
                        )
                        if (AiChatStore.replaceTarget != null) {
                            Spacer(Modifier.width(8.dp))
                            AppButton(
                                text = "替换原源",
                                variant = AppButtonVariant.Ghost,
                                onClick = { saveRule(true) },
                            )
                        }
                    }
                }
                APPLY_SAVED -> {
                    // 已保存，applyText 里写了结果，不再给按钮。
                }
            }
        }
    }
}

/**
 * v1.1.3：AI 助手对话页（全屏）。
 *
 * 顶栏“AI 助手”+ 当前服务商/模型 + “新对话”；中间消息列表（用户右、AI 左，
 * AI 消息能抠出规则时给探活验证 / 保存按钮）；底部描边输入框 + 发送。
 * 截图先用文字描述（模型看不了图）；图片直传与语音不做。
 * 直达：检测页“去对话里改”与写源失败“去对话里接着改”先把首条消息种进
 * AiChatStore.pendingSeed，进页自动发一次。
 */
@Composable
fun AiScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = MaterialTheme.colorScheme
    val messages by AiChatStore.messages.collectAsState()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }

    val provider = AiRepairSettings.provider(context)
    val model = AiRepairSettings.model(context)
    val subtitle = if (model.isBlank()) provider.name else "${provider.name} · $model"

    fun sendMessage(text: String): Boolean {
        val content = text.trim()
        if (content.isBlank() || thinking) return false
        AiChatStore.addUser(content, context)
        thinking = true
        scope.launch {
            val history = AiChatStore.messages.value.map { LlmClient.ChatMessage(it.role, it.text) }
            when (val r = LlmClient.chatMessages(context, history)) {
                is LlmClient.ChatResult.Ok -> AiChatStore.addAssistant(r.text, context)
                is LlmClient.ChatResult.Fail -> AiChatStore.addAssistant("调用失败：${r.reason}", context)
            }
            thinking = false
        }
        return true
    }

    // 进页：先读落盘记录，再消费直达种子并自动发一次。
    LaunchedEffect(Unit) {
        AiChatStore.load(context)
        AiChatStore.takeSeed()?.let { seed -> sendMessage(seed) }
    }
    // 新消息滚到底部。
    LaunchedEffect(messages.size, thinking) {
        val target = if (thinking) messages.size else messages.size - 1
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(v.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = V5.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI 助手", style = MaterialTheme.typography.titleLarge, color = v.onSurface)
            Spacer(Modifier.width(8.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = v.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { AiChatStore.clear(context) }) {
                Text("新对话")
            }
        }
        V5Divider()
        if (messages.isEmpty() && !thinking) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(v.primary.copy(alpha = 0.10f), RoundedCornerShape(V5.CornerLg))
                            .border(1.dp, v.primary.copy(alpha = 0.22f), RoundedCornerShape(V5.CornerLg)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.SmartToy,
                            contentDescription = null,
                            tint = v.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("和 AI 聊写源、修源", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = v.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "粘贴报错、日志，或者让 AI 按网址写新源；截图先用文字描述，模型看不了图。",
                        fontSize = 12.sp,
                        color = v.outline,
                    )
                    if (AiRepairSettings.apiKey(context).isBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "先去设置 - AI 助手里填 API Key",
                            fontSize = 12.sp,
                            color = v.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = V5.Gutter, vertical = 8.dp),
            ) {
                itemsIndexed(messages, key = { index, _ -> index }) { _, msg ->
                    MessageRow(msg, onAskAi = { text -> sendMessage(text) })
                }
                if (thinking) {
                    item {
                        Text(
                            text = "AI 思考中…",
                            style = MaterialTheme.typography.bodySmall,
                            color = v.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter, vertical = 8.dp)
                .padding(bottom = dockClearance()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = "发消息给 AI…（可粘贴日志）",
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (sendMessage(input)) input = "" }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 120.dp),
            )
            Spacer(Modifier.width(8.dp))
            AppButton(
                text = "发送",
                enabled = !thinking && input.isNotBlank(),
                onClick = { if (sendMessage(input)) input = "" },
            )
        }
    }
}
