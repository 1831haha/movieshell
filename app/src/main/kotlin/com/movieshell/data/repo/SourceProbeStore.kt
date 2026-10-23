package com.movieshell.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v0.90.0：批量检测结果持久化。
 *
 * 写入点：SourceProbeWorker 跑完 saveReport；
 * 读取点：站源页检测 tab（结果列表 + 上次检测时间）与各 tab 的探活徽标；
 * 持久化边界：filesDir/source_probe.json，内存另有一份 StateFlow 供 UI 订阅。
 * 清空点：暂不提供（结果即上次检测快照，下次检测覆盖）。
 */
@Serializable
data class ProbeRecord(
    val kind: String = "",
    val name: String = "",
    val label: String = "",
    /** OK / SLOW / FAIL / UNSUPPORTED */
    val status: String = "",
    val ms: Long = 0L,
    val reason: String = "",
)

@Serializable
data class ProbeReport(
    val time: Long = 0L,
    val records: List<ProbeRecord> = emptyList(),
)

/** SourceProbe.Result（不可序列化）<-> ProbeRecord（可落盘）互转 */
fun SourceProbe.Result.toRecord(kind: String, name: String, label: String): ProbeRecord =
    when (this) {
        is SourceProbe.Result.Ok -> ProbeRecord(kind, name, label, "OK", ms)
        is SourceProbe.Result.Slow -> ProbeRecord(kind, name, label, "SLOW", ms)
        is SourceProbe.Result.Fail -> ProbeRecord(kind, name, label, "FAIL", 0L, reason)
        is SourceProbe.Result.Unsupported -> ProbeRecord(kind, name, label, "UNSUPPORTED")
    }

fun ProbeRecord.toResult(): SourceProbe.Result = when (status) {
    "OK" -> SourceProbe.Result.Ok(ms)
    "SLOW" -> SourceProbe.Result.Slow(ms)
    "FAIL" -> SourceProbe.Result.Fail(reason.ifBlank { "失败" })
    else -> SourceProbe.Result.Unsupported
}

object SourceProbeStore {
    private const val TAG = "SourceProbeStore"
    private const val FILE_NAME = "source_probe.json"
    private val json = Json { ignoreUnknownKeys = true }

    private val _report = MutableStateFlow(ProbeReport())
    val report: StateFlow<ProbeReport> = _report

    /** key="kind:name" -> record，供各 tab 探活徽标订阅 */
    private val _map = MutableStateFlow<Map<String, ProbeRecord>>(emptyMap())
    val map: StateFlow<Map<String, ProbeRecord>> = _map

    fun probeKey(kind: String, name: String) = "$kind:$name"

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return@withContext
        val r = runCatching { json.decodeFromString<ProbeReport>(f.readText()) }
            .onFailure {
                Log.w(TAG, "读取检测结果失败", it)
                // v0.170.0：读坏先保全原文件，下次写盘不再悄悄清空
                AtomicFile.preserveCorrupt(f)
            }
            .getOrNull() ?: return@withContext
        _report.value = r
        _map.value = r.records.associateBy { probeKey(it.kind, it.name) }
    }

    suspend fun saveReport(context: Context, report: ProbeReport) =
        withContext(Dispatchers.IO) {
            runCatching {
                // v0.170.0：原子写盘
                AtomicFile.writeText(
                    File(context.filesDir, FILE_NAME),
                    json.encodeToString(ProbeReport.serializer(), report),
                )
            }.onFailure { Log.w(TAG, "写入检测结果失败", it) }
            _report.value = report
            _map.value = report.records.associateBy { probeKey(it.kind, it.name) }
        }

    /** 相对时间文案：x 分钟前 / x 小时前 / x 天前 */
    fun agoText(time: Long): String {
        if (time <= 0) return "从未检测"
        val min = (System.currentTimeMillis() - time) / 60000
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            min < 1440 -> "${min / 60} 小时前"
            else -> "${min / 1440} 天前"
        }
    }
}
