package com.movieshell.ui.music

import com.movieshell.ui.theme.V4
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.movieshell.player.MusicPlayer
import com.movieshell.ui.components.AppCard

/**
 * v0.91.0：全局迷你播放条（原 MusicScreen 私有实现，抽成 ui.music 包内公共组件）。
 *
 * MainActivity 的 Scaffold bottomBar 里全局挂载：切到任何 tab 都能看到正在播的音乐、
 * 点暂停、点整条进全屏播放页。外观保持不变：毛玻璃卡 + 封面/歌名/歌手/上一首/播停/下一首。
 */
@Composable
internal fun MiniPlayer(
    playState: MusicPlayer.State,
    onOpenPlayer: () -> Unit = {},
) {
    val context = LocalContext.current
    val current = playState.track ?: return
    // v0.89.0：封面走 picImageUrl 解析后的真实地址
    val artUrl = rememberArtworkUrl(current.picId, current.source)
    AppCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onOpenPlayer),
    ) {
        Column {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(V4.RPoster)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = V4.c.t3,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = V4.c.t1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = current.artist,
                    fontSize = 11.sp,
                    color = V4.c.t3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // v0.213.0 音乐界面误导修复：加载时原来三个控制钮整个消失，
            // 解析卡住时迷你条什么都点不了；改成播放页同款——上一首/
            // 下一首始终在，转圈只嵌在主按钮里。
            IconButton(onClick = { MusicPlayer.prev(context) }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首", tint = V4.c.t2)
            }
            IconButton(
                onClick = { MusicPlayer.toggle() },
                // v0.280.0 V4：主按钮 = 强调色实心圆
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(V4.c.accent),
            ) {
                if (playState.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = V4.c.onAccent,
                    )
                } else {
                    Icon(
                        if (playState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playState.playing) "暂停" else "播放",
                        tint = V4.c.onAccent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            IconButton(onClick = { MusicPlayer.next(context) }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "下一首", tint = V4.c.t2)
            }
            // v0.215.0：迷你条关闭入口（停播并清掉迷你条；原来只能进播放页停）。
            IconButton(onClick = { MusicPlayer.stop() }) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = V4.c.t3, modifier = Modifier.size(18.dp))
            }
        }
        // v0.215.0：迷你条进度（读播放器 state 的 position/duration；未解析出时长时不画）。
        if (playState.durationMs > 0) {
            LinearProgressIndicator(
                progress = { (playState.positionMs.toFloat() / playState.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = V4.c.accent,
                trackColor = V4.c.s3,
            )
        }
        }
    }
}
