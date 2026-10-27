package com.movieshell.data.novel

import android.content.Context
import android.util.Log
import com.movieshell.data.repo.AtomicFile
import com.movieshell.data.source.CmsApi
import com.movieshell.data.source.DEFAULT_UA
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * v0.248.0：小说书源轻量连通探测（发现页源列表的状态点）。
 * - 只测“能不能连上站点根地址”，不评质量、不冒充评分；
 * - 结果按书源 key 持久化，发现页进页时只补测未知或超过 24 小时的源；
 * - 失败只记状态不抛异常，探测本身绝不阻塞页面。
 */

/** 单源连通快照：status 为 OK / SLOW / FAIL，无记录即未测；url 用于识别
 * 记录是否仍对应当前源配置（源地址改过后旧记录不再当有效缓存）。 */
@Serializable
data class BookProbeRecord(
    val status: String = "",
    val ms: Long = 0L,
    val time: Long = 0L,
    val url: String = "",
)

object BookProbeStore {
    private const val TAG = "BookProbeStore"
    private const val FILE_NAME = "book_probe.json"
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSer = MapSerializer(String.serializer(), BookProbeRecord.serializer())

    private val _map = MutableStateFlow<Map<String, BookProbeRecord>>(emptyMap())
    val map: StateFlow<Map<String, BookProbeRecord>> = _map

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val f = File(context.filesDir, FILE_NAME)
        if (!f.isFile) return@withContext
        val m = runCatching {
            json.decodeFromString<Map<String, BookProbeRecord>>(f.readText())
        }.onFailure {
            Log.w(TAG, "读取小说源探测结果失败", it)
            AtomicFile.preserveCorrupt(f)
        }.getOrNull() ?: return@withContext
        _map.value = m
    }

    /** 写盘失败只记日志、不当成功（AtomicFile 写盘失败返回 false 而非抛异常）。 */
    suspend fun update(context: Context, key: String, record: BookProbeRecord) =
        withContext(Dispatchers.IO) {
            _map.value = _map.value + (key to record)
            val ok = AtomicFile.writeText(
                File(context.filesDir, FILE_NAME),
                json.encodeToString(mapSer, _map.value),
            )
            if (!ok) Log.w(TAG, "写入小说源探测结果失败")
        }
}

object BookProbe {
    private const val SLOW_MS = 3000L

    /** GET 站点根地址验连通：2xx/3xx 为通（超 3 秒记慢），其余与异常记不通。 */
    suspend fun probe(url: String): BookProbeRecord {
        val start = System.currentTimeMillis()
        return try {
            val call = CmsApi.defaultClient().newCall(
                Request.Builder().url(url).header("User-Agent", DEFAULT_UA).build(),
            )
            val code = suspendCancellableCoroutine<Int> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { cont.resume(it.code) }
                    }
                })
            }
            val ms = System.currentTimeMillis() - start
            val status = when {
                code !in 200..399 -> "FAIL"
                ms > SLOW_MS -> "SLOW"
                else -> "OK"
            }
            BookProbeRecord(status, if (status == "FAIL") 0L else ms, System.currentTimeMillis(), url)
        } catch (e: CancellationException) {
            // GPT 第 2 批复核：协程取消不是不通，必须原样抛出，绝不能记成 FAIL。
            throw e
        } catch (e: Exception) {
            BookProbeRecord("FAIL", 0L, System.currentTimeMillis(), url)
        }
    }
}
