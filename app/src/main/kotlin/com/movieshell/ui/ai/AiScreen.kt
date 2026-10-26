package com.movieshell.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.movieshell.ui.components.EmptyKind
import com.movieshell.ui.components.EmptyState
import com.movieshell.ui.theme.FlowingGlowBackground

/**
 * 全量重做框架：AI TAB 先空着（用户 2026-10-26 定：空着吧，先做别的）。
 *
 * 只有新风格外壳（流动光斑＋空态），内容后面再定；Muse 直达入口版已撤回。
 */
@Composable
fun AiScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        FlowingGlowBackground(Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                kind = EmptyKind.Empty,
                icon = Icons.Filled.SmartToy,
                title = "AI",
                hint = "AI 功能正在完善，后面再补",
            )
        }
    }
}
