package com.movieshell.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.theme.XiaYingDarkScheme
import com.movieshell.ui.theme.XiaYingLightScheme
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.movieshell.ui.theme.LocalBrandGradient

/**
 * v0.84.0：科技感弹窗——深色玻璃拟态底 + 四色渐变描边 + 标题下渐变细线。
 * 替代默认 AlertDialog（在深色主题下发白、输入框看不清，用户反馈"导入框框都看不到"）。
 * v0.194.0：弹窗体跟随系统主题——之前恒深色 #171B28，用户在浅色主题下嫌
 * “所有弹窗页都太暗了”；现深色主题仍走深底、浅色主题走浅底，内容色一律
 * 跟主题 onSurface/onSurfaceVariant 走，此前各弹窗里写死的浅色一并改掉
 * （见 BookDetailDialog/ReaderScreen/MusicPlayerScreen 等），不许再写死。
 */
@Composable
fun TechDialog(
    onDismissRequest: () -> Unit,
    title: String,
    subtitle: String? = null,
    confirmText: String = "确定",
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    /** v1.3.25 UI 优化：危险确认（如高危风险仍要导入）——确认按钮改 error 色，
     * 与普通确认区分；默认 false，其他 90 多处弹窗不受影响。 */
    confirmDanger: Boolean = false,
    /** 取消按钮文案；传 null 则隐藏取消按钮（如不可取消的进度弹窗） */
    dismissText: String? = "取消",
    /** v0.240.0：弹窗宽度占屏幕宽的比例；null 则走平台默认宽（其他弹窗不受影响）。 */
    widthFraction: Float? = null,
    /** v0.245.0：强制深浅；null=跟系统主题（其他页面默认），阅读页传
     * 阅读背景的实际深浅，系统浅色时夜间黑阅读页的弹窗也不再发白。 */
    forceDark: Boolean? = null,
    // v1.1.20：紧凑模式（v1.3.33 起默认开：内边距 16×12dp、标题 titleMedium 16sp、
    // 底部按钮 36dp；90 多处弹窗默认走紧凑口径）。
    // 长内容弹窗（日志、条款、长列表、长错误详情等）显式传 compact = false 走宽松模式，
    // 避免文字拥挤、按钮贴边、滚动区域不足。
    compact: Boolean = true,
    // v1.2.15：内容区默认可滚动 + 底部按钮常驻——长表单（如 Emby 添加服务器）
    // 再也不会把按钮截在屏外。内含 LazyColumn 的弹窗传 false，让 LazyColumn
    // 自己滚（滚动容器里再放 LazyColumn 会量出无限高而崩）：BookDetailDialog、
    // ParseProgressOverlay、BookSourceScreen 订阅预览、ChangeSourceDialog、ReaderScreen 书签。
    scrollable: Boolean = true,
    // content 放最后：调用处用尾随 lambda 写法时必须对应最后一个参数
    content: @Composable ColumnScope.() -> Unit,
) {
    // v0.194.0：用主题背景明度判深浅（App 主题默认跟随系统），不用写死深色。
    // v0.245.0：forceDark 非空时按它走，并把整套配色换成对应深浅的副本，
    // 否则系统浅色下深底弹窗里的字会是深色、看不见。
    val systemDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isDark = forceDark ?: systemDark
    val baseScheme = MaterialTheme.colorScheme
    val effectiveScheme = if (forceDark == null || forceDark == systemDark) {
        baseScheme
    } else if (isDark) {
        // v0.292.0 V5：强制深浅的副本取 M3 深色 token，主色沿用当前主题。
        XiaYingDarkScheme.copy(primary = baseScheme.inversePrimary, onPrimary = baseScheme.onPrimaryContainer)
    } else {
        XiaYingLightScheme.copy(primary = baseScheme.inversePrimary, onPrimary = baseScheme.onPrimaryContainer)
    }
    // M3 对话框：surfaceContainerHigh 底、28dp 圆角、无描边
    val dialogBg = effectiveScheme.surfaceContainerHigh
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = if (widthFraction != null) DialogProperties(usePlatformDefaultWidth = false) else DialogProperties(),
    ) {
        MaterialTheme(
            colorScheme = effectiveScheme,
            typography = MaterialTheme.typography,
            shapes = MaterialTheme.shapes,
        ) {
        // v0.294.3：横屏下平台默认宽能拉到五六百 dp，一个确认框横跨大半屏；
        // 统一封顶（默认 420dp，显式给了宽度比例的封顶 560dp），横屏边距收紧。
        val cfg = LocalConfiguration.current
        val landscape = cfg.screenWidthDp > cfg.screenHeightDp
        Box(
            modifier = Modifier
                .fillMaxWidth(widthFraction ?: 1f)
                .widthIn(max = if (widthFraction != null) 560.dp else 420.dp)
                // v1.2.15：弹窗限高 85% 屏高，超长内容走内容区滚动，标题与按钮常驻。
                .heightIn(max = (cfg.screenHeightDp * 0.85f).dp)
                // v0.279.0 旧版：平面弹窗——s1 底 + 1px 分隔线描边 + 12dp 圆角，去掉渐变描边。
                // v1.3.33：弹窗圆角定档 16dp（紧凑/宽松同档）。
                .clip(RoundedCornerShape(16.dp))
                .background(dialogBg)
                .padding(
                    horizontal = if (compact) 16.dp else if (landscape) 20.dp else 24.dp,
                    vertical = if (compact) 12.dp else if (landscape) 16.dp else 20.dp,
                ),
        ) {
            Column {
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    // v0.279.0 旧版：标题用主文字色，不再青字 + 渐变短线。
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
                // v0.194.0：内容区不再固定浅色，改跟主题——浅色主题下裸 Text
                // 拿 onSurface 深色正好可读，深色主题下拿浅色也可读。
                // v1.2.15：内容区占剩余高度、超长可滚；短内容不撑满，按钮紧贴其下。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                ) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                        content()
                    }
                }
                Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
                // v1.2.15：底部按钮与效果图一致——取消/确认各占一半宽、同为胶囊；
                // 取消是淡描边空心，确认是主题色实填；只有一个按钮时占满整行。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissText != null) {
                        OutlinedButton(
                            onClick = onDismissRequest,
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier.weight(1f).height(if (compact) 36.dp else 40.dp),
                        ) {
                            Text(
                                dismissText,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (onConfirm != null) {
                        Button(
                            onClick = onConfirm,
                            enabled = confirmEnabled,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.weight(1f).height(if (compact) 36.dp else 40.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (confirmDanger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                contentColor = if (confirmDanger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                                // v1.2.14 E片：M3 默认禁用字 onSurface 38% 只有 2.34:1（浅）/3.03:1（深）；
                                // 禁用字改 65%（5.21/6.59），禁用底仍是淡底、仍一眼可辨是禁用态。
                                disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                                disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            ),
                        ) {
                            Text(
                                confirmText,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * v0.84.0：科技感输入框——深底 + 清晰描边，聚焦时描边变主题色。
 * 替代弹窗里的 OutlinedTextField（原来边框太淡、看不清）。
 * v1.2.15：全 app 输入框统一样式——全胶囊（圆角 50%）、描边放淡到几乎
 * 看不见（靠底色与弹窗/页面底区分，对比度走填充不走框线）、默认收窄
 * （上下内边距 12dp 降到 8dp）。新增 leadingIcon/trailingIcon/keyboardActions，
 * 供原 OutlinedTextField/TextField 调用处迁移（密码眼睛、搜索图标等）。
 */
@Composable
fun TechTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true,
    // v0.213.0：Cookie 输入框遮掩用（默认不遮掩，其他调用方不受影响）
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // v1.1.20：紧凑模式（默认关）——上下内边距 12dp 降到 6dp、字号降到
    // bodyMedium，供一屏放不下的长弹窗（如 AI 助手设置）调用处显式开启。
    compact: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    // v1.2.15：输入框右侧单位（如“秒”），空则不显示（原 AppTextField 同名参数，播放器跳过设置用）。
    suffix: String? = null,
    // v1.2.22：形状（默认全胶囊）；多行文本域（如粘贴导入）传小圆角，别硬拉胶囊成大鸡蛋。
    shape: Shape = RoundedCornerShape(50),
) {
    // v0.294.3：有值时占位提示缩成框内顶部小标签（同 AppTextField），预填值的编辑框不再“无标签”
    val floatLabel = value.isNotEmpty() && placeholder.isNotBlank() && placeholder.any { !it.isDigit() }
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        enabled = enabled,
        textStyle = (if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge)
            .copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interactionSource,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.30f),
                shape = shape,
            )
            .padding(
                horizontal = 16.dp,
                // v1.2.22：多行文本域上下留 12dp，提示/正文不再顶着边框（粘贴导入框的教训）。
                vertical = if (compact || floatLabel) 6.dp else if (!singleLine) 12.dp else 8.dp,
            ),
        decorationBox = { inner ->
            Column {
                if (floatLabel) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (focused) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // v1.2.22：多行顶对齐（单行才垂直居中），空态提示落在左上角。
                Row(verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
                    if (leadingIcon != null) {
                        Box(Modifier.padding(end = 8.dp)) { leadingIcon() }
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            // v1.2.16：占位提示只显示一行、超长省略——原来没限行数，
                            // 长提示（如 Emby 密码“无密码用户留空”）会在胶囊里折成两三行。
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                    if (trailingIcon != null) {
                        Box(Modifier.padding(start = 8.dp)) { trailingIcon() }
                    }
                    if (!suffix.isNullOrBlank()) {
                        Text(
                            suffix,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
    )
}
