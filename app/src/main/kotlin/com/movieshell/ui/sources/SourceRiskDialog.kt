package com.movieshell.ui.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.data.source.SourceRiskScanner
import com.movieshell.ui.components.TechDialog

/**
 * v1.3.25 合并 2：源风险确认弹窗。列出全部命中项（高危在前），
 * 用户点"仍要导入"才放行，点取消/返回则不导入。调用处必须包 if 守卫
 *（TechDialog 本身不带守卫，见 1.2.22 的教训）。
 */
@Composable
fun SourceRiskDialog(
    fileName: String,
    report: SourceRiskScanner.Report,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismissRequest,
        title = "该源有高危风险",
        subtitle = fileName,
        confirmText = "仍要导入",
        onConfirm = onConfirm,
        scrollable = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(report.findings) { finding ->
                Column {
                    Text(
                        text = (if (finding.severity == SourceRiskScanner.Severity.HIGH) "高危 · " else "中危 · ") + finding.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (finding.severity == SourceRiskScanner.Severity.HIGH) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = finding.detail + if (finding.line > 0) "（第 ${finding.line} 行）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "只按对手机有无危害判：读通讯录/短信/位置、联网外发、执行代码、装东西才算风险。来源可信可点“仍要导入”。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
