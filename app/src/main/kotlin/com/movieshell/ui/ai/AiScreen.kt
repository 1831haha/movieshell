package com.movieshell.ui.ai

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
import com.movieshell.ui.components.V4Divider
import com.movieshell.ui.components.V4Dot
import com.movieshell.ui.components.dockClearance
import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type

/**
 * 全量重做框架：AI TAB 先空着（用户 2026-10-26 定：空着吧，先做别的）。
 *
 * 只有新风格外壳（空态），内容后面再定；Muse 直达入口版已撤回。
 * v0.279.0 V4：顶栏“AI + COMING SOON”，中间强调色图标井 + 即将上线 + 开发中标签；
 * 仍不做假功能。
 */
@Composable
fun AiScreen() {
    val v = V4.c
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(v.bg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .padding(horizontal = V4.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AI", style = V4Type.ScreenTitle, color = v.t1)
            Spacer(Modifier.weight(1f))
            Text("COMING SOON", style = V4Type.MonoSmall, color = v.t3)
        }
        V4Divider()
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
                        .background(v.accent.copy(alpha = 0.10f), RoundedCornerShape(V4.RWell))
                        .border(1.dp, v.accent.copy(alpha = 0.22f), RoundedCornerShape(V4.RWell)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = v.accent,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("AI 即将上线", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = v.t1)
                Spacer(Modifier.height(6.dp))
                Text("AI 功能正在完善，后面再补", fontSize = 12.sp, color = v.t3)
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier
                        .background(v.s1, RoundedCornerShape(V4.RSm + 2.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    V4Dot(v.vip)
                    Spacer(Modifier.width(6.dp))
                    Text("开发中", fontSize = 11.sp, color = v.t2)
                }
            }
        }
    }
}
