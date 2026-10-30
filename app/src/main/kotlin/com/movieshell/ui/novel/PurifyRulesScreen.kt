package com.movieshell.ui.novel

import androidx.compose.foundation.border
import com.movieshell.ui.components.AppSwitch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.data.novel.PurifyRule
import com.movieshell.data.novel.PurifyRuleStore
import com.movieshell.data.repo.NovelStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.components.TechTextField
import kotlinx.coroutines.launch

/**
 * v0.95.0 净化规则管理（对标阅读 3.0 替换规则）：
 * 列表 + 开关 + 新增 + 删除。正文拉取后经 NovelPurifyEngine 统一净化。
 */
@Composable
fun PurifyRulesScreen(
    onBack: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // v0.109.0 A9：亮色主题白底青字按底色明度自适应
    val accent = MaterialTheme.colorScheme.onSurface

    var rules by remember { mutableStateOf<List<PurifyRule>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch { rules = PurifyRuleStore.list(context) }
    }
    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = accent)
            }
            Text("净化规则", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, "新增规则", tint = accent)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "正文拉取后按顺序执行已启用的规则；正则写错会被跳过不影响其他规则",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // v0.215.0：规则改动后已缓存章节不会自己变新——这里手动清缓存正文
        // （目录保留，下次打开重拉并走新规则），不清则旧章节一直是旧样子。
        var showClearCache by remember { mutableStateOf(false) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showClearCache = true }) {
            Text("清除已缓存正文（规则改动后点这里）", fontSize = 13.sp)
        }
        if (showClearCache) {
            TechDialog(
                onDismissRequest = { showClearCache = false },
                title = "清除已缓存正文？",
                confirmText = "清除",
                onConfirm = {
                    showClearCache = false
                    scope.launch {
                        val n = NovelStore.clearCachedContents(context)
                        AppToast.show(context, if (n > 0) "已清除 $n 本书的缓存正文，下次打开重拉" else "没有已缓存的正文可清", long = false)
                    }
                },
            ) {
                Text("只清正文缓存，目录和进度不动；清掉的章节下次打开时会重新拉取并按新规则净化。")
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(rules, key = { i, r -> "$i-${r.name}-${r.regex}" }) { i, r ->
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name.ifBlank { "未命名规则" }, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                // v0.102.0 P2-3：内置规则标识；开关/删除走现有逻辑
                                if (r.builtin) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "内置",
                                        fontSize = 11.sp,
                                        color = accent,
                                        modifier = Modifier
                                            .border(1.dp, accent, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                "${r.group.ifBlank { "未分组" }} · ${scopeLabel(r.scope)} · ${r.regex.take(40)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AppSwitch(
                            checked = r.enable,
                            onCheckedChange = { en ->
                                scope.launch {
                                    PurifyRuleStore.toggle(context, i, en)
                                    reload()
                                }
                            },
                        )
                        IconButton(onClick = {
                            scope.launch {
                                PurifyRuleStore.removeAt(context, i)
                                reload()
                            }
                        }) {
                            Icon(Icons.Filled.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var regex by remember { mutableStateOf("") }
        var replacement by remember { mutableStateOf("") }
        TechDialog(
            onDismissRequest = { showAdd = false },
            title = "新增净化规则",
            onConfirm = {
                if (regex.isNotBlank()) {
                    scope.launch {
                        PurifyRuleStore.add(
                            context,
                            PurifyRule(
                                name = name.ifBlank { "自定义规则" },
                                group = "自定义",
                                regex = regex,
                                replacement = replacement,
                            ),
                        )
                        showAdd = false
                        reload()
                    }
                }
            },
            confirmEnabled = regex.isNotBlank(),
        ) {
            TechTextField(value = name, onValueChange = { name = it }, placeholder = "规则名称")
            Spacer(Modifier.height(8.dp))
            TechTextField(value = regex, onValueChange = { regex = it }, placeholder = "正则表达式（必填）")
            Spacer(Modifier.height(8.dp))
            TechTextField(value = replacement, onValueChange = { replacement = it }, placeholder = "替换为（删留空）")
            Spacer(Modifier.height(8.dp))
            Text(
                "新增后可单独开关；scope 默认全局",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun scopeLabel(scope: String): String = when (scope) {
    "book" -> "单书"
    "source" -> "书源"
    else -> "全局"
}
