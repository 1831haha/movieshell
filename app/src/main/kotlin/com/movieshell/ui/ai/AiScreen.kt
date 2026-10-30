package com.movieshell.ui.ai

import com.movieshell.ui.theme.V5
import androidx.compose.material3.MaterialTheme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movieshell.ui.components.V5Divider
import com.movieshell.ui.components.V5Dot
import com.movieshell.ui.components.dockClearance

/**
 * 全量重做框架：AI TAB 先空着（用户 2026-10-26 定：空着吧，先做别的）。
 *
 * 只有新风格外壳（空态），内容后面再定；Muse 直达入口版已撤回。
 * v0.279.0 V4：顶栏“AI + COMING SOON”，中间强调色图标井 + 即将上线 + 开发中标签；
 * 仍不做假功能。
 */
@Composable
fun AiScreen() {
    val v = MaterialTheme.colorScheme
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
            Text("AI", style = MaterialTheme.typography.titleLarge, color = v.onSurface)
            Spacer(Modifier.weight(1f))
            Text("COMING SOON", style = MaterialTheme.typography.bodySmall, color = v.outline)
        }
        V5Divider()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = dockClearance()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                Text("AI 即将上线", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = v.onSurface)
                Spacer(Modifier.height(6.dp))
                Text("AI 功能正在完善，后面再补", fontSize = 12.sp, color = v.outline)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .background(v.surfaceContainer, RoundedCornerShape(V5.CornerSm + 2.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    V5Dot(v.tertiary)
                    Spacer(Modifier.width(6.dp))
                    Text("开发中", fontSize = 11.sp, color = v.onSurfaceVariant)
                }
            }
        }
    }
}
