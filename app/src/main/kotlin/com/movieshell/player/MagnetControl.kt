package com.movieshell.player

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/** v0.294.29：:magnet 进程内的控制接口（只听 127.0.0.1）。 */
internal object MagnetControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null
    @Volatile private var state = "idle"
    @Volatile private var msg = ""
    @Volatile private var url = ""
    @Volatile private var magnet = ""
    @Volatile var appContext: android.content.Context? = null

    private fun json(o: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())

    fun handle(uri: String, parms: Map<String, String>): Response {
        when (uri) {
            "/ctl/prepare" -> {
                val m = parms["m"].orEmpty()
                val ctx = appContext
                if (m.isBlank() || ctx == null) return json(JSONObject().put("state", "fail").put("msg", "参数错误"))
                if (m == magnet && (state == "working" || state == "ready")) return stateJson()
                if (state == "working") {
                    // 上一条还在取元数据（fetchMagnet 不可中断）：结束进程，让主进程重新拉起
                    killSoon()
                    return json(JSONObject().put("state", "restart"))
                }
                job?.cancel()
                magnet = m; state = "working"; msg = "正在连接磁力网络…"; url = ""
                job = scope.launch {
                    val r = runCatching { XiaYingMagnetEngine.prepare(ctx, m) }.getOrElse {
                        XiaYingMagnetEngine.Fail("磁力引擎异常：${it.message}")
                    }
                    if (magnet != m) return@launch
                    when (r) {
                        is XiaYingMagnetEngine.Ready -> { url = r.streamUrl; state = "ready" }
                        is XiaYingMagnetEngine.Fail -> { msg = r.message; state = "fail" }
                        else -> { msg = "未知结果"; state = "fail" }
                    }
                }
                return stateJson()
            }
            "/ctl/state" -> return stateJson()
            "/ctl/cancel" -> {
                // fetchMagnet 不可中断：直接结束整个磁力进程，下次点播重新拉起
                killSoon()
                return json(JSONObject().put("ok", true))
            }
            "/ctl/release" -> {
                if (state == "working") killSoon() else XiaYingMagnetEngine.releaseWhenIdle()
                return json(JSONObject().put("ok", true))
            }
            "/ctl/active" -> return json(JSONObject().put("active", org.json.JSONArray(XiaYingMagnetEngine.activeEntries().toList())))
        }
        return NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "no")
    }

    private fun killSoon() {
        Thread({ Thread.sleep(200); android.os.Process.killProcess(android.os.Process.myPid()) }).start()
    }

    private fun stateJson(): Response {
        val live = XiaYingMagnetEngine.lastStatus
        return json(JSONObject().put("state", state).put("url", url)
            .put("msg", if (state == "working" && live.isNotBlank()) live else msg).put("magnet", magnet))
    }
}
