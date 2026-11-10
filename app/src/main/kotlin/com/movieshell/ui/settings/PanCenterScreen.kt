package com.movieshell.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.source_mgr.BaiduAuthStore
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.data.source_mgr.UcAuthStore
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.V5

/**
 * 网盘中心页（v1.1.24）：设置页“网盘”单一入口点进这里，6 家分两组。
 *
 * 已接入（点开登录弹窗）：夸克、UC、百度，登录态以私有目录下的
 * Cookie 文件是否存在为准，过期标记另行提示重登。
 * 第一批（游客路线，不弹登录框）：阿里、光鸭、迅雷，点行只提示
 * 无需登录、分享链接直接播。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var quarkLoggedIn by remember { mutableStateOf(QuarkAuthStore.hasCookie()) }
    var quarkExpired by remember { mutableStateOf(QuarkAuthStore.isExpired()) }
    var ucLoggedIn by remember { mutableStateOf(UcAuthStore.hasCookie()) }
    var ucExpired by remember { mutableStateOf(UcAuthStore.isExpired()) }
    var baiduLoggedIn by remember { mutableStateOf(BaiduAuthStore.hasCookie()) }
    var baiduExpired by remember { mutableStateOf(BaiduAuthStore.isExpired()) }
    var showQuarkDialog by remember { mutableStateOf(false) }
    var showUcDialog by remember { mutableStateOf(false) }
    var showBaiduDialog by remember { mutableStateOf(false) }

    fun refreshLoginStates() {
        quarkLoggedIn = QuarkAuthStore.hasCookie()
        quarkExpired = QuarkAuthStore.isExpired()
        ucLoggedIn = UcAuthStore.hasCookie()
        ucExpired = UcAuthStore.isExpired()
        baiduLoggedIn = BaiduAuthStore.hasCookie()
        baiduExpired = BaiduAuthStore.isExpired()
    }

    fun guestHint() {
        AppToast.show(context, "无需登录，分享链接直接播", long = false)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            TabTopBar(
                title = "网盘",
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
                SettingsGroup(title = "已接入") {
                    SettingsRow(
                        icon = Icons.Filled.Cloud,
                        title = "夸克网盘",
                        subtitle = "分享解析＋转存原画，游客小文件可播",
                        onClick = { showQuarkDialog = true },
                        trailing = {
                            Text(
                                when {
                                    quarkExpired -> "登录已过期"
                                    quarkLoggedIn -> "已登录"
                                    else -> "未登录"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    quarkExpired -> cs.error
                                    quarkLoggedIn -> cs.primary
                                    else -> cs.onSurfaceVariant
                                },
                            )
                        },
                    )
                    SettingsRow(
                        icon = Icons.Filled.Cloud,
                        title = "UC网盘",
                        subtitle = "分享解析，游客小文件可播",
                        onClick = { showUcDialog = true },
                        trailing = {
                            Text(
                                when {
                                    ucExpired -> "登录已过期"
                                    ucLoggedIn -> "已登录"
                                    else -> "未登录"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    ucExpired -> cs.error
                                    ucLoggedIn -> cs.primary
                                    else -> cs.onSurfaceVariant
                                },
                            )
                        },
                    )
                    SettingsRow(
                        icon = Icons.Filled.Cloud,
                        title = "百度网盘",
                        subtitle = "分享解析，取直链需 SVIP 登录",
                        onClick = { showBaiduDialog = true },
                        divider = false,
                        trailing = {
                            Text(
                                when {
                                    baiduExpired -> "登录已过期"
                                    baiduLoggedIn -> "已登录"
                                    else -> "未登录"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = when {
                                    baiduExpired -> cs.error
                                    baiduLoggedIn -> cs.primary
                                    else -> cs.onSurfaceVariant
                                },
                            )
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
                SettingsGroup(title = "第一批") {
                    SettingsRow(
                        icon = Icons.Filled.CloudOff,
                        title = "阿里云盘",
                        subtitle = "分享链接直接播，游客可列文件",
                        onClick = { guestHint() },
                        trailing = {
                            Text(
                                "无需登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                            )
                        },
                    )
                    SettingsRow(
                        icon = Icons.Filled.CloudOff,
                        title = "光鸭网盘",
                        subtitle = "分享链接直接播，游客可取直链",
                        onClick = { guestHint() },
                        trailing = {
                            Text(
                                "无需登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                            )
                        },
                    )
                    SettingsRow(
                        icon = Icons.Filled.CloudOff,
                        title = "迅雷云盘",
                        subtitle = "分享链接直接播，游客可列目录",
                        onClick = { guestHint() },
                        divider = false,
                        trailing = {
                            Text(
                                "无需登录",
                                style = MaterialTheme.typography.labelLarge,
                                color = cs.onSurfaceVariant,
                            )
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showQuarkDialog) {
        QuarkLoginDialog(onDismiss = {
            showQuarkDialog = false
            refreshLoginStates()
        })
    }
    if (showUcDialog) {
        UcLoginDialog(onDismiss = {
            showUcDialog = false
            refreshLoginStates()
        })
    }
    if (showBaiduDialog) {
        BaiduLoginDialog(onDismiss = {
            showBaiduDialog = false
            refreshLoginStates()
        })
    }
}
