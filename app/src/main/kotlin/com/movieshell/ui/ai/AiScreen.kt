package com.movieshell.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.data.store.AiChatStore
import com.movieshell.data.ai.AiRepairEngine
import com.movieshell.data.ai.AiRepairSettings
import com.movieshell.data.ai.LlmClient
import com.movieshell.data.source_mgr.SourceProbe
import com.movieshell.ui.components.AppButton
import com.movieshell.ui.components.AppButtonVariant
import com.movieshell.ui.components.AppTextField
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.V5Divider
import com.movieshell.ui.components.dockClearance
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // v1.1.11：带图的消息先放图（靠发送侧对齐），再放文字；只发图没写字时不留空文字气泡。
        if (msg.imagePath != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                AsyncImage(
                    model = AiChatStore.imageFile(context, msg.imagePath),
                    contentDescription = "对话图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(V5.CornerMd)),
                )
            }
            if (msg.text.isNotBlank() || msg.fileName != null) Spacer(Modifier.height(6.dp))
        }
        // v1.1.11：带文件的消息显示文件名（只存了文件名，大小与行数重载后不再保留）。
        if (msg.fileName != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            if (isUser) v.primaryContainer else v.surfaceContainerHighest,
                            RoundedCornerShape(V5.CornerMd),
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.UploadFile,
                        contentDescription = null,
                        tint = if (isUser) v.onPrimaryContainer else v.onSurface,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = msg.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isUser) v.onPrimaryContainer else v.onSurface,
                    )
                }
            }
            if (msg.text.isNotBlank()) Spacer(Modifier.height(6.dp))
        }
        if (msg.text.isNotBlank()) {
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

/** v1.1.11：文件大小取整显示（B / KB，待发文件行与发给 AI 的附件头共用这一个口径）。 */
private fun formatFileSize(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"

/**
 * v1.1.11：把文件内容拼进用户消息文本：文件名 + 代码围栏；只随本轮最后一条消息发。
 * v1.1.12：新文件传大小与行数（待发时算好的）；追问重读的旧文件只传文件名与行数
 * （大小与截断标记重载后不再保留，行数按内容现算）。
 */
private fun fileBlockForSend(
    displayName: String,
    content: String,
    sizeText: String?,
    lineCount: Int,
    truncated: Boolean,
): String {
    val note = buildString {
        append(displayName)
        append("（")
        if (sizeText != null) {
            append(sizeText)
            append("，")
        }
        append(lineCount)
        append(" 行")
        if (truncated) append("，内容过长只贴前 8000 字")
        append("）")
    }
    return "\n\n[附件文件：$note]\n```\n$content\n```"
}

/**
 * v1.1.3：AI 助手对话页（全屏）。
 *
 * 顶栏“AI 助手”+ 当前服务商/模型 + “新对话”；中间消息列表（用户右、AI 左，
 * AI 消息能抠出规则时给探活验证 / 保存按钮）；底部描边输入框 + 发送。
 * v1.1.11：可以发图片了——输入框旁的选图按钮调系统图片选择器，图先缩到长边 1024 内、
 * JPEG 80 落盘再随文字一起发；当前模型不支持视觉时对话里给换模型提示，不静默丢图；
 * 语音不做。图片只发给支持视觉的模型看，不进 SharedPreferences。
 * v1.1.11：也可以发文件了——输入框旁的选文件按钮调系统文件选择器，只认文本文件
 * （.py/.js/.php/.json/.txt/.log/.md 这类），内容过长只贴前 8000 字，拼进本轮最后一条
 * 用户消息的文本里发给 AI；文件内容不进 ai_chat.json，只留文件名。
 * v1.1.12：文件落盘副本发送后保留，换模型后发文字追问也会把最新一份文件重新带上
 * （只带最新的一份）；图也一样，换模型后追问带最近一张图。落盘副本只在点“新对话”时清掉。
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
    // v1.1.11：待发的图（AiChatStore 里已压缩落盘的文件名，未发送前显示缩略图）。
    var pendingImage by remember { mutableStateOf<String?>(null) }
    // v1.1.11：待发的文件（AiChatStore 里已读成文本落盘，未发送前显示文件名与大小）。
    var pendingFile by remember { mutableStateOf<AiChatStore.StagedFile?>(null) }

    val provider = AiRepairSettings.provider(context)
    val model = AiRepairSettings.model(context)
    val subtitle = if (model.isBlank()) provider.name else "${provider.name} · $model"

    // v1.1.11：系统图片选择器；选中的图先压缩落盘，失败给一句提示、不卡住输入。
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = withContext(Dispatchers.IO) { AiChatStore.stageImage(context, uri) }
            if (name != null) {
                pendingImage?.let { AiChatStore.deleteImage(context, it) }
                pendingImage = name
            } else {
                AppToast.show(context, "选图失败，换张图再试", long = false)
            }
        }
    }

    // v1.1.11：系统文件选择器；选中后立即读成文本落盘，非文本或读失败给一句提示、不卡住输入。
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { AiChatStore.stageFile(context, uri) }) {
                is AiChatStore.StageFileResult.Ok -> {
                    pendingFile?.let { AiChatStore.deleteStagedFile(context, it.storedName) }
                    pendingFile = r.file
                }
                AiChatStore.StageFileResult.NotText ->
                    AppToast.show(context, "只支持文本文件（.py/.js/.txt/.log 这类），图片请用选图按钮", long = false)
                AiChatStore.StageFileResult.Failed ->
                    AppToast.show(context, "读文件失败，换个文件再试", long = false)
            }
        }
    }

    fun sendMessage(text: String): Boolean {
        val content = text.trim()
        // v1.1.11：只发图不写字也允许发；待发图随这条用户消息一起记进会话，发出后清掉待发位。
        val image = pendingImage
        // v1.1.11：只发文件不写字也允许发；待发文件只记原文件名进会话，内容另外拼进请求。
        val file = pendingFile
        if ((content.isBlank() && image == null && file == null) || thinking) return false
        // v1.1.12：发新文件前先记下历史里最新一份文件的落盘名——新文件读到了，
        // 上一份的落盘副本就可以删了（之后追问只重读最新的一份）；没发新文件时，
        // 追问就重读这份旧文件接着发。
        val prevStored = AiChatStore.messages.value.asReversed()
            .firstOrNull { it.role == "user" && it.fileStoredName != null }?.fileStoredName
        AiChatStore.addUser(content, context, image, file?.displayName, file?.storedName)
        pendingImage = null
        pendingFile = null
        thinking = true
        scope.launch {
            // v1.1.12：文件只带最新的一份。有新文件读新文件，没新文件重读历史里
            // 最新一份；落盘副本发送后不再删，留到“新对话”清空时再删。
            // 读不到时在消息里明说，不静默丢掉。
            val fileBlock = if (file != null) {
                val staged = withContext(Dispatchers.IO) { AiChatStore.readStagedFile(context, file.storedName) }
                if (staged != null) {
                    if (prevStored != null && prevStored != file.storedName) {
                        AiChatStore.deleteStagedFile(context, prevStored)
                    }
                    fileBlockForSend(file.displayName, staged, formatFileSize(file.sizeBytes), file.lineCount, file.truncated)
                } else {
                    "\n\n[附件文件：${file.displayName}（读取失败，未附内容）]"
                }
            } else if (prevStored != null) {
                val carried = withContext(Dispatchers.IO) { AiChatStore.readStagedFile(context, prevStored) }
                val carriedName = AiChatStore.messages.value.asReversed()
                    .firstOrNull { it.role == "user" && it.fileStoredName == prevStored }?.fileName
                if (carried != null && carriedName != null) {
                    val lines = if (carried.isEmpty()) 0 else carried.count { it == '\n' } + 1
                    fileBlockForSend(carriedName, carried, null, lines, false)
                } else if (carriedName != null) {
                    "\n\n[附件文件：$carriedName（之前发的文件找不到了，未附内容）]"
                } else {
                    ""
                }
            } else {
                ""
            }
            // 图片只传绝对路径，base64 由 LlmClient 在发请求时现读现转。
            val lastIndex = AiChatStore.messages.value.lastIndex
            val history = AiChatStore.messages.value.mapIndexed { index, it ->
                val text = if (index == lastIndex && it.role == "user" && fileBlock.isNotEmpty()) {
                    it.text + fileBlock
                } else {
                    it.text
                }
                LlmClient.ChatMessage(
                    it.role,
                    text,
                    it.imagePath?.let { name -> AiChatStore.imageFile(context, name).absolutePath },
                )
            }
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
            TextButton(onClick = {
                // v1.1.12：新对话清落盘时，待发的图与文件也一并清掉，别留着悬空的待发位。
                pendingImage = null
                pendingFile = null
                AiChatStore.clear(context)
            }) {
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
                        "粘贴报错、日志，或者让 AI 按网址写新源；截图可以直接发，文本文件（.py/.js/.txt 这类）也能发，换模型后追问还会把最近的图和文件带上；看图要支持视觉的模型（设置里换模型；豆包方舟接入点在设置里打开「我的模型支持看图」）。",
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
        // 输入行精致化：附件 chip 在上、输入卡片在下；只改外观，发送条件与禁用口径一字不改。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = V5.Gutter)
                .padding(top = 8.dp, bottom = dockClearance()),
        ) {
            // 待发附件 chip（图 / 文件各一枚，右上角 × 移除，落盘副本一并删掉）。
            if (pendingImage != null || pendingFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (pendingImage != null) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .background(v.surfaceContainerHighest, RoundedCornerShape(V5.CornerMd))
                                .border(1.dp, v.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(V5.CornerMd))
                                .padding(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(
                                    model = AiChatStore.imageFile(context, pendingImage!!),
                                    contentDescription = "待发图片",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "已选一张图",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = v.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 给右上角 × 留位，别让文字顶到它。
                                Spacer(Modifier.width(20.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(v.surfaceContainer, CircleShape)
                                    .border(1.dp, v.outlineVariant.copy(alpha = 0.6f), CircleShape)
                                    .clickable {
                                        pendingImage?.let { AiChatStore.deleteImage(context, it) }
                                        pendingImage = null
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除图片",
                                    tint = v.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                    pendingFile?.let { pf ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(v.surfaceContainerHighest, RoundedCornerShape(V5.CornerMd))
                                .border(1.dp, v.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(V5.CornerMd))
                                .padding(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Outlined.UploadFile,
                                    contentDescription = null,
                                    tint = v.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = buildString {
                                        append(pf.displayName)
                                        append(" · ")
                                        append(formatFileSize(pf.sizeBytes))
                                        append(" · ")
                                        append(pf.lineCount)
                                        append(" 行")
                                        if (pf.truncated) append("（内容过长，只发前 8000 字）")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = v.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(20.dp))
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(v.surfaceContainer, CircleShape)
                                    .border(1.dp, v.outlineVariant.copy(alpha = 0.6f), CircleShape)
                                    .clickable {
                                        pendingFile?.let { AiChatStore.deleteStagedFile(context, it.storedName) }
                                        pendingFile = null
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "移除文件",
                                    tint = v.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // 输入卡片：surfaceContainer 底 + outlineVariant 微光描边 + CornerLg。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(v.surfaceContainer, RoundedCornerShape(V5.CornerLg))
                    .border(1.dp, v.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(V5.CornerLg))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 选文件按钮（系统文件选择器，只认文本文件）；思考中不可再选，避免待发文件被中途换掉。
                FilledTonalIconButton(
                    onClick = { pickFile.launch("*/*") },
                    enabled = !thinking,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Outlined.UploadFile,
                        contentDescription = "选文件",
                    )
                }
                // 选图按钮（系统图片选择器）；思考中不可再选，避免待发图被中途换掉。
                FilledTonalIconButton(
                    onClick = { pickImage.launch("image/*") },
                    enabled = !thinking,
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = "选图",
                    )
                }
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
                FilledIconButton(
                    onClick = { if (sendMessage(input)) input = "" },
                    enabled = !thinking && (input.isNotBlank() || pendingImage != null || pendingFile != null),
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = v.primary,
                        contentColor = v.onPrimary,
                    ),
                ) {
                    Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = "发送",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
