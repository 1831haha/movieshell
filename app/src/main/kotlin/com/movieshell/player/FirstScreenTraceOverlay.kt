package com.movieshell.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.4.6 A1.1：首屏分段诊断浮层（纯展示，不走 TechDialog、不滚动）。
 *
 * - 只在开关开 + 本次 Attempt 已有数据时由调用方挂出；不自动隐藏，可折叠为一行，
 *   可手动关闭（关闭/折叠状态随 Attempt 换新重置）。
 * - 半透明黑底小块，只占自身区域，不拦截区域外触摸；点本体切换折叠，点 × 关闭。
 * - 只读快照拼文本，不做计算、不触发重播。
 */
@Composable
fun FirstScreenTraceOverlay(
    snapshot: AttemptSnapshot?,
    modifier: Modifier = Modifier,
) {
    if (snapshot == null || !snapshot.hasAnyMark) return
    var collapsed by remember(snapshot.sessionId, snapshot.attemptIndex) { mutableStateOf(false) }
    var dismissed by remember(snapshot.sessionId, snapshot.attemptIndex) { mutableStateOf(false) }
    if (dismissed) return
    val total = snapshot.totalMs
    val totalText = if (total == null) "未完成" else "${total}ms"
    Column(
        modifier = modifier
            .padding(top = 4.dp, end = 4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable { collapsed = !collapsed }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (collapsed) {
            Row {
                Text(
                    text = "首屏诊断 · 总计 $totalText · ${snapshot.engine}",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "×",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    modifier = Modifier.clickable { dismissed = true },
                )
            }
            return@Column
        }
        Row {
            Text(
                text = "首屏诊断 · ${if (snapshot.isLive) "直播" else "点播"} · ${snapshot.engine}",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "×",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                modifier = Modifier.clickable { dismissed = true },
            )
        }
        for (stage in TraceStage.values()) {
            Text(
                text = "${stage.label}：${formatStageValue(snapshot, stage)}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Text(
            text = "总计：$totalText",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            maxLines = 1,
        )
        snapshot.filterDownloadMs?.let {
            Text(
                text = "过滤下载：${it}ms${if (snapshot.filterCacheHit) "（缓存命中）" else ""}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
            )
        }
        if (snapshot.segmentFailures.isNotEmpty()) {
            val first = snapshot.segmentFailures.first()
            Text(
                text = "分片失败：${first.host} ${first.kind.label} ${first.errorClass}",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                maxLines = 1,
            )
        }
        if (TraceStage.FIRST_DECODE in snapshot.approximateStages) {
            Text(
                text = "首帧解码为近似值：以首个有效画面尺寸为准",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                maxLines = 1,
            )
        }
        if (TraceStage.FIRST_FRAME in snapshot.approximateStages && snapshot.engine == "LIBMPV") {
            Text(
                text = "首帧渲染为近似值：以 time-pos 首次 > 0 为准",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                maxLines = 1,
            )
        }
    }
}
