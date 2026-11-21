package com.movieshell.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.movieshell.data.store.BackgroundStore
import com.movieshell.data.store.ThemeCollectionStore
import com.movieshell.data.store.ThemeStore
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.V5
import com.movieshell.ui.theme.StaticGlowBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.2.5：主题收藏夹子页面。
 *
 * 效果图 theme-collection-preview.html 的落版：顶栏两按钮（保存当前观感 / 导入主题）、
 * 内置推荐 4 条细长条、我的收藏（保存/导入的主题 JSON）、内置底图 5 张 + 自定义底图入口。
 * 底图只在首页生效；皮肤工作室（编辑/复制/导出皮肤）这次不做。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCollectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bgId by ThemeCollectionStore.background.collectAsState()
    val saved by ThemeCollectionStore.saved.collectAsState()
    val color by ThemeStore.color.collectAsState()
    val gradientId by ThemeStore.gradientId.collectAsState()
    val brandGradient by ThemeStore.brandGradient.collectAsState()
    var confirmDelete by remember { mutableStateOf<ThemeCollectionStore.SavedTheme?>(null) }
    // v1.2.6：自定义配色（模式/主题色/炫彩/海报取色）从设置页搬到这里，面板见 ThemePanelDialog。
    var showThemeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { ThemeCollectionStore.init(context) }

    val bgPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val err = BackgroundStore.setCustomBg(context, uri)
            if (err == null) {
                ThemeCollectionStore.setBackground(context, ThemeCollectionStore.BG_CUSTOM)
                AppToast.show(context, "背景底图已更换")
            } else {
                AppToast.show(context, "设置失败：$err", long = true)
            }
        }
    }
    val jsonPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val result = if (text == null) {
                Result.failure(IllegalArgumentException("读不到这个文件"))
            } else {
                ThemeCollectionStore.importJson(context, text)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { AppToast.show(context, "已导入 ${it} 个主题") }
                    .onFailure {
                        if (it is CancellationException) throw it
                        AppToast.show(context, "导入失败：${it.message}", long = true)
                    }
            }
        }
    }

    // v1.3.33：二级页静态渐变底（StaticGlowBackground 在 Scaffold 之下，Scaffold 本体透明）。
    Box(Modifier.fillMaxSize()) {
        StaticGlowBackground(Modifier.fillMaxSize())
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            topBar = {
                TabTopBar(
                    title = "主题收藏夹",
                    onBack = onBack,
                )
            },
        ) { inner ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(V5.Gutter),
            ) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val ok = ThemeCollectionStore.saveCurrent(context)
                                AppToast.show(context, if (ok) "已保存到我的收藏" else "收藏已满 50 个，先删一些")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) { Text("✦ 保存当前观感") }
                        OutlinedButton(
                            onClick = { jsonPicker.launch("application/json") },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(40.dp),
                        ) { Text("↓ 导入主题") }
                    }
                }
                item {
                    // v1.2.6：配色入口——模式、主题色、炫彩渐变、海报取色都在面板里。
                    SettingsGroup(title = "配色") {
                        SettingsRow(
                            icon = Icons.Outlined.Palette,
                            title = "自定义配色",
                            subtitle = "模式、主题色、炫彩渐变、海报取色",
                            onClick = { showThemeDialog = true },
                            divider = false,
                        )
                    }
                }
                item {
                    CollectionCaption("内置推荐")
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeCollectionStore.BUILTIN_THEMES.forEach { theme ->
                            val active = color == theme.seed &&
                                gradientId == ThemeStore.GRADIENT_CUSTOM &&
                                brandGradient == theme.gradient &&
                                bgId == theme.backgroundId
                            ThemeStripCard(
                                theme = theme,
                                active = active,
                                onClick = {
                                    ThemeCollectionStore.applyBuiltin(context, theme)
                                    AppToast.show(context, "已换成「${theme.name}」")
                                },
                            )
                        }
                    }
                }
                item {
                    SettingsGroup(title = "我的收藏") {
                        if (saved.isEmpty()) {
                            Text(
                                text = "还没有收藏：点上方「保存当前观感」，或导入好友分享的主题 JSON",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            saved.forEach { theme ->
                                val hex = "#%06X".format(theme.seed and 0xFFFFFF)
                                SettingsRow(
                                    icon = Icons.Outlined.Palette,
                                    title = theme.name,
                                    subtitle = "种子 $hex · 底图 ${ThemeCollectionStore.backgroundLabel(theme.backgroundId)} · ${ThemeCollectionStore.modeLabel(theme.mode)}",
                                    onClick = {
                                        ThemeCollectionStore.applySaved(context, theme)
                                        AppToast.show(context, "已换成「${theme.name}」")
                                    },
                                    trailing = {
                                        Row {
                                            IconButton(onClick = { shareTheme(context, theme) }) {
                                                Icon(Icons.Filled.Share, contentDescription = "分享")
                                            }
                                            IconButton(onClick = { confirmDelete = theme }) {
                                                Icon(Icons.Filled.Delete, contentDescription = "删除")
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    CollectionCaption("内置底图")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        BgTile(
                            name = "默认",
                            sub = "无底图",
                            selected = bgId == ThemeCollectionStore.BG_NONE,
                            onClick = { ThemeCollectionStore.setBackground(context, ThemeCollectionStore.BG_NONE) },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(108.dp, 180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("默认", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ThemeCollectionStore.BACKGROUNDS.forEach { bg ->
                            val id = ThemeCollectionStore.bgId(bg.id)
                            BgTile(
                                name = bg.name,
                                sub = "配 ${bg.forTheme}",
                                selected = bgId == id,
                                onClick = {
                                    ThemeCollectionStore.setBackground(context, id)
                                    AppToast.show(context, "底图已换成「${bg.name}」")
                                },
                            ) {
                                AsyncImage(
                                    model = "file:///android_asset/theme_bg/${bg.asset}",
                                    contentDescription = bg.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(108.dp, 180.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                )
                            }
                        }
                        BgTile(
                            name = "自定义底图",
                            sub = "从相册选图",
                            selected = bgId == ThemeCollectionStore.BG_CUSTOM,
                            onClick = { bgPicker.launch("image/*") },
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(108.dp, 180.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                    .dashedBorder(MaterialTheme.colorScheme.outline), // v1.2.14 E片：上传背景虚线框 outlineVariant 只有 1.62:1（浅）/1.98:1（深），改 outline（4.27/5.86）
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+", fontSize = 44.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }

    confirmDelete?.let { target ->
        TechDialog(
            onDismissRequest = { confirmDelete = null },
            title = "删除主题",
            confirmText = "删除",
            onConfirm = {
                ThemeCollectionStore.deleteSaved(context, target)
                confirmDelete = null
                AppToast.show(context, "已删除")
            },
        ) {
            Text("确定删除「${target.name}」吗？", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showThemeDialog) {
        ThemePanelDialog(onDismiss = { showThemeDialog = false })
    }
}

/** 分组小标题（同设置页 SettingsCaption 的样式，跨文件不共享所以这里单写一份）。 */
@Composable
private fun CollectionCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 22.dp, bottom = 8.dp),
    )
}

/** 内置主题细长条：36dp 渐变块 + 名 + 一行说明 + 使用中角标 + 右箭头。 */
@Composable
private fun ThemeStripCard(
    theme: ThemeCollectionStore.BuiltinTheme,
    active: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(V5.CornerMd),
        color = cs.surfaceContainerLow,
        border = if (active) BorderStroke(1.5.dp, cs.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(8.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(theme.gradient.map { Color(it) })),
            ) {
                Box(
                    modifier = Modifier
                        .padding(9.dp, 10.dp)
                        .fillMaxSize()
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.85f)),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(theme.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(cs.primary)
                                .padding(6.dp, 2.dp),
                        ) {
                            Text("使用中", fontSize = 10.sp, color = cs.onPrimary)
                        }
                    }
                }
                Text(theme.desc, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = cs.outline)
        }
    }
}

/** 底图格子：108×180 图 + 名 + 配哪套主题；选中描 primary 边。 */
@Composable
private fun BgTile(
    name: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
    image: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.width(108.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            border = if (selected) BorderStroke(2.dp, cs.primary) else null,
        ) {
            image()
        }
        Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 7.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, fontSize = 11.sp, color = cs.onSurfaceVariant)
    }
}

/** 虚线描边（自定义底图入口用，Modifier.border 只画实线）。 */
private fun Modifier.dashedBorder(color: Color, width: Dp = 1.5.dp, radius: Dp = 12.dp) = drawBehind {
    val stroke = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f))
    drawRoundRect(color = color, style = stroke, cornerRadius = CornerRadius(radius.toPx()))
}

private fun shareTheme(context: Context, theme: ThemeCollectionStore.SavedTheme) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, ThemeCollectionStore.toJson(theme).toString(2))
    }
    context.startActivity(Intent.createChooser(intent, "分享主题"))
}
