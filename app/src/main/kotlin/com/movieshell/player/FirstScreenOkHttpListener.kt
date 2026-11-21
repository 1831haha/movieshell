package com.movieshell.player

import android.os.SystemClock
import okhttp3.Call
import okhttp3.EventListener
import java.io.IOException

/**
 * v1.4.6 A1.1：OkHttp 网络观测（只挂在原始请求上，不额外探测、不改请求头/代理/DNS/连接池/重试）。
 *
 * - 每个 Call 一个实例；第一个创建的实例认领主请求（首个 m3u8），只记主请求的
 *   DNS/建连/响应等待/首字节；后续分片请求一律忽略——分片失败只走
 *   AnalyticsListener.onLoadError 单一来源，不重复计数。
 * - 失败只记异常类名，不记完整 URL。
 * - 连接复用时无 dnsStart/connectStart：callEnd 时补记“未发生（连接复用）”。
 * - 默认分支（DefaultHttpDataSource）不经过 OkHttp，无此监听器，DNS/建连标未取到。
 */
class FirstScreenOkHttpListener(
    private val attempt: FirstScreenAttempt,
) : EventListener() {

    private val isMain: Boolean = attempt.claimMainCall()
    private var callStartMs: Long = 0L
    private var dnsStarted: Boolean = false
    private var dnsEnded: Boolean = false
    private var connectStarted: Boolean = false
    private var connectEnded: Boolean = false
    private var openEnded: Boolean = false
    private var secureStartMs: Long = 0L

    override fun callStart(call: Call) {
        if (!isMain) return
        callStartMs = SystemClock.elapsedRealtime()
        attempt.markStart(TraceStage.OPEN, callStartMs)
    }

    override fun dnsStart(call: Call, domainName: String) {
        if (!isMain) return
        dnsStarted = true
        attempt.markStart(TraceStage.DNS)
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        if (!isMain) return
        dnsEnded = true
        attempt.markEnd(TraceStage.DNS)
    }

    override fun connectStart(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
        if (!isMain) return
        connectStarted = true
        attempt.markStart(TraceStage.CONNECT)
    }

    override fun secureConnectStart(call: Call) {
        if (!isMain) return
        secureStartMs = SystemClock.elapsedRealtime()
    }

    override fun secureConnectEnd(call: Call, handshake: okhttp3.Handshake?) {
        if (!isMain || secureStartMs == 0L) return
        attempt.noteTls(SystemClock.elapsedRealtime() - secureStartMs)
    }

    override fun connectEnd(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
        if (!isMain) return
        connectEnded = true
        attempt.markEnd(TraceStage.CONNECT)
    }

    override fun connectFailed(call: Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?, e: IOException) {
        if (!isMain) return
        if (dnsStarted && !dnsEnded) {
            attempt.markFailed(TraceStage.DNS, e.javaClass.simpleName)
        } else if (connectStarted && !connectEnded) {
            attempt.markFailed(TraceStage.CONNECT, e.javaClass.simpleName)
        }
    }

    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
        if (!isMain || callStartMs == 0L) return
        attempt.noteResponseWait(SystemClock.elapsedRealtime() - callStartMs)
    }

    override fun responseBodyStart(call: Call) {
        if (!isMain || callStartMs == 0L) return
        attempt.noteFirstByte(SystemClock.elapsedRealtime() - callStartMs)
        openEnded = true
        attempt.markEnd(TraceStage.OPEN)
    }

    override fun callEnd(call: Call) {
        if (!isMain) return
        if (!dnsStarted) attempt.markMissing(TraceStage.DNS, TraceMissing.NOT_OCCURRED)
        if (!connectStarted) attempt.markMissing(TraceStage.CONNECT, TraceMissing.NOT_OCCURRED)
        if (!openEnded) {
            openEnded = true
            attempt.markEnd(TraceStage.OPEN)
        }
    }

    override fun callFailed(call: Call, e: IOException) {
        if (!isMain) return
        val errorClass = e.javaClass.simpleName
        when {
            dnsStarted && !dnsEnded -> attempt.markFailed(TraceStage.DNS, errorClass)
            connectStarted && !connectEnded -> attempt.markFailed(TraceStage.CONNECT, errorClass)
            !openEnded -> attempt.markFailed(TraceStage.OPEN, errorClass)
        }
    }
}
