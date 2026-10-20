package com.movieshell.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
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
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onOpenPlayer),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artUrl.isNotBlank()) {
                AsyncImage(
                    model = artUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = current.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = current.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playState.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = { MusicPlayer.prev(context) }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "上一首")
                }
                IconButton(
                    onClick = { MusicPlayer.toggle() },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        if (playState.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playState.playing) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { MusicPlayer.next(context) }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }
        }
    }
}
