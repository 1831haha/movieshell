package com.movieshell.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.movieshell.data.source.TmdbClient
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.V5
import kotlinx.coroutines.launch

/**
 * v1.2.14：TMDB 独立设置页（从播放设置页 TMDB Key 行点进，替代原来的弹窗）。
 *
 * 只做：填 Key、测试连接（真调接口）、清除密钥、改 API 地址与图片地址、
 * 跳官网申请 Key。Key 默认空、用户自填，绝不内置、不写死任何 Key；
 * 地址留空保存即恢复默认。测试用当前框里的值，不用先保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmdbSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    var keyText by remember { mutableStateOf(TmdbClient.storedApiKey(context).orEmpty()) }
    var keyVisible by remember { mutableStateOf(false) }
    var searchBaseText by remember { mutableStateOf(TmdbClient.searchBase(context)) }
    var imageBaseText by remember { mutableStateOf(TmdbClient.imageBase(context)) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }

    fun testConnection() {
        if (keyText.isBlank()) {
            testOk = false
            testResult = "请先填写 Key"
            return
        }
        testing = true
        testResult = null
        scope.launch {
            val base = TmdbClient.normalizeBaseUrl(searchBaseText, TmdbClient.DEFAULT_SEARCH_BASE)
            when (val r = TmdbClient.testConnection(keyText.trim(), base)) {
                TmdbClient.TestResult.Ok -> {
                    testOk = true
                    testResult = "连接成功，Key 有效"
                }
                is TmdbClient.TestResult.Failed -> {
                    testOk = false
                    testResult = r.reason
                }
            }
            testing = false
        }
    }

    fun save() {
        TmdbClient.setApiKey(context, keyText)
        TmdbClient.setSearchBase(context, searchBaseText)
        TmdbClient.setImageBase(context, imageBaseText)
        testResult = null
        AppToast.show(context, "TMDB 设置已保存")
    }

    fun clearKey() {
        TmdbClient.setApiKey(context, "")
        keyText = ""
        testResult = null
        AppToast.show(context, "TMDB Key 已清除，海报墙不联网")
    }

    fun openApplyPage() {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.themoviedb.org/settings/api"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { AppToast.show(context, "打不开浏览器") }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            topBar = { TabTopBar(title = "TMDB Key", onBack = onBack) },
            containerColor = cs.surfaceContainer,
        ) { inner ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsGroup(title = "API Key") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TechTextField(
                                value = keyText,
                                onValueChange = { keyText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "TMDB API Key",
                                singleLine = true,
                                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            imageVector = if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                            contentDescription = if (keyVisible) "隐藏 Key" else "显示 Key",
                                        )
                                    }
                                },
                            )
                            Text(
                                text = if (keyText.isBlank()) "未填写 · 海报墙暂不可用" else "已填写 · 海报墙匹配用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { testConnection() },
                                    enabled = !testing,
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (testing) "正在测试…" else "测试连接") }
                                OutlinedButton(
                                    onClick = { clearKey() },
                                    modifier = Modifier.weight(1f),
                                ) { Text("清除密钥") }
                            }
                            val result = testResult
                            if (result != null) {
                                Text(
                                    text = result,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (testOk) cs.primary else cs.error,
                                )
                            }
                            Text(
                                text = "测试用当前框里的值，不用先保存；没填 Key 或清空后，海报墙不联网，只显示占位。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SettingsGroup(title = "地址") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TechTextField(
                                value = searchBaseText,
                                onValueChange = { searchBaseText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "API 地址",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Text(
                                text = "留空恢复默认：${TmdbClient.DEFAULT_SEARCH_BASE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TechTextField(
                                value = imageBaseText,
                                onValueChange = { imageBaseText = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "图片地址",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Text(
                                text = "留空恢复默认：${TmdbClient.DEFAULT_IMAGE_BASE}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SettingsGroup(title = "申请 Key") {
                        SettingsRow(
                            icon = Icons.Outlined.OpenInNew,
                            title = "去 TMDB 官网申请 API Key",
                            subtitle = "免费注册后在设置页拿到 Key",
                            onClick = { openApplyPage() },
                            divider = false,
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { save() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保存") }
                }
            }
        }
    }
}
