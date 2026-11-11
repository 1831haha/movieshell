package com.movieshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.movieshell.data.source_mgr.AliyunAuthStore
import com.movieshell.data.source_mgr.BaiduAuthStore
import com.movieshell.data.source_mgr.GuangyaAuthStore
import com.movieshell.data.source_mgr.PanProvider
import com.movieshell.data.source_mgr.QuarkAuthStore
import com.movieshell.data.source_mgr.UcAuthStore
import com.movieshell.data.source_mgr.XunleiAuthStore
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.theme.V5

/**
 * 网盘中心页（v1.1.25：按批准的六宫格效果图重做）。
 *
 * 3x2 六家，每格：名＋品牌徽标、登录状态小标签、底部“扫码登录”＋
 * “清除 Cookie”两按钮一一对应（阿里先不做扫码，那格是“手动输入”＋
 * “清除 Cookie”）；“无需登录”那组列表已去掉（游客
 * 播放照旧走规则，不在中心页单列）。不再嵌 WebView 登录页，登录
 * 一律走扫码弹窗（阿里只留手动输入）；手动填 Cookie/Token 入口在扫码弹窗里兜底。
 * 登录态以私有目录下的登录态文件是否存在为准，过期标记另行提示重登。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanCenterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var states by remember { mutableStateOf(PanProvider.entries.associateWith { loadLoginState(it) }) }
    var qrProvider by remember { mutableStateOf<PanProvider?>(null) }

    fun refreshLoginStates() {
        states = PanProvider.entries.associateWith { loadLoginState(it) }
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(V5.Gutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(PanProvider.entries) { provider ->
                PanProviderCard(
                    provider = provider,
                    state = states[provider] ?: PanLoginState.LOGGED_OUT,
                    // 阿里先不做扫码：按钮直接叫“手动输入”，点的还是同一个弹窗，
                    // 弹窗里默认展开手动输入 Token。
                    scanLabel = if (provider == PanProvider.ALIYUN) "手动输入" else "扫码登录",
                    onScan = { qrProvider = provider },
                    onClear = {
                        when (provider) {
                            PanProvider.QUARK -> QuarkAuthStore.clearAll()
                            PanProvider.UC -> UcAuthStore.clearAll()
                            PanProvider.BAIDU -> BaiduAuthStore.clearAll()
                            PanProvider.ALIYUN -> AliyunAuthStore.clear()
                            PanProvider.GUANGYA -> GuangyaAuthStore.clear()
                            PanProvider.XUNLEI -> XunleiAuthStore.clear()
                        }
                        refreshLoginStates()
                        AppToast.show(context, "已清除${provider.displayName}登录态")
                    },
                )
            }
        }
    }

    qrProvider?.let { provider ->
        PanQrLoginDialog(
            provider = provider,
            onDismiss = {
                qrProvider = null
                refreshLoginStates()
            },
        )
    }
}

private enum class PanLoginState { LOGGED_OUT, LOGGED_IN, EXPIRED }

private fun loadLoginState(provider: PanProvider): PanLoginState = when (provider) {
    PanProvider.QUARK -> when {
        QuarkAuthStore.isExpired() -> PanLoginState.EXPIRED
        QuarkAuthStore.hasCookie() -> PanLoginState.LOGGED_IN
        else -> PanLoginState.LOGGED_OUT
    }
    PanProvider.UC -> when {
        UcAuthStore.isExpired() -> PanLoginState.EXPIRED
        UcAuthStore.hasCookie() -> PanLoginState.LOGGED_IN
        else -> PanLoginState.LOGGED_OUT
    }
    PanProvider.BAIDU -> when {
        BaiduAuthStore.isExpired() -> PanLoginState.EXPIRED
        BaiduAuthStore.hasCookie() -> PanLoginState.LOGGED_IN
        else -> PanLoginState.LOGGED_OUT
    }
    PanProvider.ALIYUN -> if (AliyunAuthStore.hasToken()) PanLoginState.LOGGED_IN else PanLoginState.LOGGED_OUT
    PanProvider.GUANGYA -> if (GuangyaAuthStore.hasToken()) PanLoginState.LOGGED_IN else PanLoginState.LOGGED_OUT
    PanProvider.XUNLEI -> if (XunleiAuthStore.hasToken()) PanLoginState.LOGGED_IN else PanLoginState.LOGGED_OUT
}

/** 品牌徽标色（效果图口径）。 */
private val PanProvider.brandColor: Color
    get() = when (this) {
        PanProvider.QUARK -> Color(0xFF7B61FF)
        PanProvider.UC -> Color(0xFF2F7CF6)
        PanProvider.BAIDU -> Color(0xFF306EFF)
        PanProvider.ALIYUN -> Color(0xFFFF6A00)
        PanProvider.GUANGYA -> Color(0xFFFFB800)
        PanProvider.XUNLEI -> Color(0xFF2BA5E8)
    }

@Composable
private fun PanProviderCard(
    provider: PanProvider,
    state: PanLoginState,
    scanLabel: String,
    onScan: () -> Unit,
    onClear: () -> Unit,
) {
    AppCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(provider.brandColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = provider.badgeChar,
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            StatusPill(state)
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) {
                    Text(scanLabel, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                ) {
                    Text("清除 Cookie", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: PanLoginState) {
    val (text, bg) = when (state) {
        PanLoginState.LOGGED_IN -> "已登录" to Color(0xFF4CAF50)
        PanLoginState.EXPIRED -> "登录已过期" to MaterialTheme.colorScheme.error
        PanLoginState.LOGGED_OUT -> "未登录" to Color(0xFF9E9E9E)
    }
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
