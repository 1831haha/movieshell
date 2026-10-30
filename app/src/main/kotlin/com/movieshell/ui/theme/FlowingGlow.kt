package com.movieshell.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * v0.33.0 影院黑：首页顶部流动渐变光斑。
 *
 * v0.279.0 起平底；v0.292.0 V5：保留函数签名（调用点不动），只画一层 M3 surface 底色。
 */
@Composable
fun FlowingGlowBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background))
}
