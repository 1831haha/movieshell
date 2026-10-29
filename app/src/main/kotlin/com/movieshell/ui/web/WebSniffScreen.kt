package com.movieshell.ui.web

import com.movieshell.ui.theme.V4
import com.movieshell.ui.theme.V4Type
import com.movieshell.ui.components.V4Tag
import com.movieshell.ui.components.V4TagKind
import android.annotation.SuppressLint
import com.movieshell.ui.components.AppDropdownMenu
import com.movieshell.ui.components.AppToast
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.movieshell.ui.components.TechDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.movieshell.data.source.CmsApi
import com.movieshell.ui.components.TabTopBar
import com.movieshell.data.repo.LogBus
import com.movieshell.data.source.SniffCandidate
import com.movieshell.data.source.SniffConfig
import com.movieshell.data.source.SniffEngine
import com.movieshell.data.source.SniffEngine.SniffKind
import com.movieshell.player.SniffPlayHeaders
import com.movieshell.ui.components.AppCard
import com.movieshell.ui.components.TechDialog
import com.movieshell.ui.theme.SoftType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 嗅探超时：20 秒内没抓到直链则提示用户，可继续手动浏览 */
private const val SNIFF_TIMEOUT_MS = 20_000L

/** 拦截到的 URL 记录上限（供"嗅探测试"用，太多只留最近的） */
private const val SEEN_URL_CAP = 300

/**
 * v0.92 第 4 层：JS video hook 轮询脚本。
 * 首次执行时 hook HTMLMediaElement.src 的 setter（hls.js 等库常走 el.src = xxx 赋值）；
 * 每次返回 {v:[video 地址], f:[MutationObserver 攒的新增 iframe src]}。
 * iframe 队列在 JS 侧 splice 取走，天然去重（observer 侧另有 seen 表）。
 * v0.149 静音收口：每轮顺手静音页内全部 video/audio——嗅探页只负责抓地址，
 * 真播放走播放器，嗅探页出声纯属打扰。
 */
private const val JS_VIDEO_POLL = """
(function(){
  var out=[];
  if(!window.__xhHooked){
    window.__xhHooked=true;
    try{
      var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
      Object.defineProperty(HTMLMediaElement.prototype,'src',{
        set:function(v){ window.__xhLastSrc=v; if(d&&d.set) d.set.call(this,v); },
        get:function(){ return (d&&d.get)?d.get.call(this):this.getAttribute('src'); },
        configurable:true
      });
    }catch(e){}
  }
  try{
    document.querySelectorAll('video,audio').forEach(function(m){ try{ m.muted=true; }catch(e){} });
    document.querySelectorAll('video').forEach(function(v){
      var s=v.currentSrc||v.src||''; if(s) out.push(s);
    });
  }catch(e){}
  if(window.__xhLastSrc) out.push(window.__xhLastSrc);
  var fr=[];
  try{
    if(window.__xhIframeQueue && window.__xhIframeQueue.length){
      fr=window.__xhIframeQueue.splice(0, window.__xhIframeQueue.length);
    }
  }catch(e){}
  return JSON.stringify({v:out, f:fr});
})()
"""

/**
 * v0.149 document_start 等价注入：onPageStarted 时点先把视频钩子装上
 * （Android 没有 document_start API，onPageStarted 是最接近的时点；
 * 注入幂等，onPageFinished 还会再装一次兜底）。
 */
private const val JS_HOOK_INSTALL = """
(function(){
  if(!window.__xhHooked){
    window.__xhHooked=true;
    try{
      var d=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
      Object.defineProperty(HTMLMediaElement.prototype,'src',{
        set:function(v){ window.__xhLastSrc=v; if(d&&d.set) d.set.call(this,v); },
        get:function(){ return (d&&d.get)?d.get.call(this):this.getAttribute('src'); },
        configurable:true
      });
    }catch(e){}
  }
  return 'ok';
})()
"""

/**
 * v0.149 懒播放触发：点播才加载流的懒播放器，逼它一把。
 * 只点已知播放器的大播放键 + 调 video.play()，不点通用 div、不碰广告位；
 * 每页只触发一次（由 Kotlin 侧 lazyTriggered 布防），且只在候选为空时触发——
 * 已有候选就不需要再惊动页面。
 */
private const val JS_LAZY_PLAY = """
(function(){
  try{
    document.querySelectorAll('video').forEach(function(v){ try{ v.play(); }catch(e){} });
    ['.vjs-big-play-button','.jw-display-icon-display','.play-button','[class*="play-btn"]','[class*="big-play"]'].forEach(function(sel){
      try{ document.querySelectorAll(sel).forEach(function(el){ try{ el.click(); }catch(e){} }); }catch(e){}
    });
  }catch(e){}
  return 'ok';
})()
"""

/**
 * v0.92 第 3 层：动态 iframe 追踪。
 * onPageFinished 后注入；SPA 内跳走不刷新页面时重复注入会先 disconnect 旧的。
 * 回调里只收集 src 进队列，探测仍在 Kotlin 侧（由轮询每 2 秒消费）。
 */
private const val JS_IFRAME_OBSERVER = """
(function(){
  try{
    if(window.__xhObs) window.__xhObs.disconnect();
    if(!window.__xhIframeQueue) window.__xhIframeQueue=[];
    if(!window.__xhIframeSeen) window.__xhIframeSeen={};
    var q=window.__xhIframeQueue, seen=window.__xhIframeSeen;
    function pushSrc(s){ if(s && !seen[s]){ seen[s]=1; q.push(s); } }
    window.__xhObs=new MutationObserver(function(muts){
      for(var i=0;i<muts.length;i++){
        var nodes=muts[i].addedNodes;
        for(var j=0;j<nodes.length;j++){
          var n=nodes[j];
          if(!n) continue;
          if(n.tagName==='IFRAME'){ pushSrc(n.src); }
          else if(n.querySelectorAll){
            var fs=n.querySelectorAll('iframe');
            for(var k=0;k<fs.length;k++) pushSrc(fs[k].src);
          }
        }
      }
    });
    window.__xhObs.observe(document.documentElement,{childList:true,subtree:true});
  }catch(e){}
  return 'ok';
})()
"""

/**
 * v0.92 blob 深挖：video 播的是 blob: 地址（MSE 方案）时，真实流地址藏在
 * JS 或页面源码里。扫 innerHTML 找媒体 URL，JS 侧正则、去重，只回传命中的地址。
 * v0.92：后缀扩到全部 DIRECT 后缀 + ts（分片只当线索）+ `/m3u8` 路径形式。
 * v0.149 案例 1 词边界：后缀后必须跟 ?/# 或结束/引号类边界（lookahead 拦住
 * 后续的字母/点/斜杠/横杠）——player.m3u8.js 再也不能被截断成 player.m3u8
 * 伪造候选；第二条 /m3u8 规则扫到的地址仍走 Kotlin 侧 sniffKind 的静态资源防线。
 */
private const val JS_BLOB_DIG = """
(function(){
  var html='';
  try{ html=document.documentElement.innerHTML||''; }catch(e){}
  var out=[], seen={};
  function grab(re){
    var m;
    while((m=re.exec(html))!==null){ var u=m[0]; if(!seen[u]){ seen[u]=1; out.push(u); } }
  }
  grab(/https?:\/\/[^\s"'<>]+\.(m3u8|mp4|mkv|flv|avi|ts|webm|mp3|m4a|aac|mov|wmv|mpd)([?#][^\s"'<>]*)?(?![\w./-])/gi);
  grab(/https?:\/\/[^\s"'<>]*\/m3u8([?&#\/][^\s"'<>]*)?/gi);
  return JSON.stringify(out);
})()
"""

/**
 * v0.94：inject 式去广告（drpy 截图里 inject 思路）。
 * onPageFinished 后注入：删广告 DOM、禁 window.open 弹窗、隐藏广告横幅，
 * 每 800ms 跑一次 rmAd 兜底后加载的广告。
 * 保守处理：不 blanket 删 iframe（会破坏第 3 层 iframe 嗅探，只清广告 src 的）、
 * 不用 div[class*="ad"]（会误删 header/badge 等正常元素）。
 */
private const val JS_RM_AD = """
(function(){
  var AD_RE=/advert|doubleclick|googlesyndication|adsrv|popad|adservice/i;
  function rmAd(){
    try{
      document.querySelectorAll('.ads,.advert,.ad-popup,.ad-banner,.popup,.tip').forEach(function(e){e.remove();});
      document.querySelectorAll('div[id^="ad-"],div[id$="-ad"],div[id="ad"]').forEach(function(e){e.remove();});
      document.querySelectorAll('iframe').forEach(function(f){
        try{ if(f.src && AD_RE.test(f.src)) f.remove(); }catch(e){}
      });
      document.querySelectorAll('style,script').forEach(function(s){
        try{ if(/advert|adpop|ads\.js/.test(s.innerHTML)) s.remove(); }catch(e){}
      });
    }catch(e){}
  }
  rmAd();
  try{ window.open=function(){return null;}; }catch(e){}
  try{ window.onbeforeunload=null; }catch(e){}
  try{
    var sty=document.createElement('style');
    sty.textContent='.ad,.ads,.advert,.ad-popup,.ad-banner{display:none!important}';
    document.head.appendChild(sty);
  }catch(e){}
  try{ setInterval(rmAd,800); }catch(e){}
  return 'ok';
})()
"""

/**
 * evaluateJavascript 返回值解开一层转义：回调拿到的是 JSON 字符串字面量
 * （如 "\"{\\\"v\\\":[...]}\""），包一层 {"v":...} 再 getString 即得原文。
 */
private fun decodeJsResult(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault("")
}

/** 从 {"v":[...],"f":[...]} 这类对象里取字符串数组 */
private fun parseStringArray(json: String, key: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray(key) ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

/** 从顶层 JSON 数组字符串里取字符串列表 */
private fun parseRootStringArray(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

/**
 * 网页源嗅探页（v0.92 增强）：可见 WebView 加载目标站，用户可自行浏览点播。
 *
 * 嗅探五层（判定全在 [SniffEngine]）：
 * 1. 后缀直判：shouldInterceptRequest 抓 .m3u8/.mp4/.mpd/.aac 直接命中；
 *    rtmp 进候选列表、点选后走 libmpv（v0.152.0；未装插件时 EngineHost 拒绝并提示）。
 * 2. 无后缀流探测：URL 可疑（path/query 含 video/play/stream 等词）时异步 HEAD
 *    看 Content-Type 判定，不阻塞原请求、不全量探测。
 * 3. iframe 剥壳 + 动态追踪：页面加载完抠所有 iframe src（v0.92 修：之前把
 *    JSON 数组误当 HTML 解析，这层实际没生效）；MutationObserver 追踪后注入的
 *    iframe，src 进队列由第 4 层的轮询统一消费。
 * 4. JS video hook：hook HTMLMediaElement.src 的 setter + 每 2 秒轮询
 *    video.currentSrc，抓 hls.js/dplayer 等 MSE 播放器的真实地址；video 播的是
 *    blob: 地址时自动深挖一次（扫页面 innerHTML 找媒体 URL + 重跑 iframe 探测）。
 * 5. 深度嗅探（手动）：顶栏按钮，把拦截到的全部 http(s) URL（去静态资源后缀）
 *    逐个 Content-Type 探测，并发 6，命中即停。
 *
 * 命中直链后 stopLoading（省流量）并回调 onPlay；20 秒超时仅 Toast 提示不关页面。
 * Service Worker 嗅探是补齐：SW 上下文的 fetch，WebViewClient 拦不到；
 * 各链路共用 hitDone 去重，先命中的生效。
 *
 * v0.93：候选列表模式——各链路命中后不再首条即播，而是收集进候选列表
 * （[SniffCandidate]，按 url 去重），用户点选后才 stopLoading + onPlay；
 * hitDone 语义改为"用户已选播"。顶栏溢出菜单可调嗅探参数（[SniffConfig]）。
 *
 * v0.149（DS 材料包 P0-2 落地，行为描述见 DS 0.9.8 changelog；以下映射均已
 * 按自家"候选列表、用户点选"架构改写，不照搬 DS 的自动结算）：
 * - 拦截显式开启 + document_start 等价注入（onPageStarted 装钩子）+ 零候选时
 *   自动重探已拦截队列一轮（DS 案例 2 的重试轮回）；
 * - 点选播放前结算验证（DS 案例 3），只拦网页/脚本类硬拒绝；
 * - 超时预算耗尽时只记观测统计、永不作废已有候选（DS 案例 5 的根修在本架构
 *   下是天然成立的：候选列表模式下超时从不清空候选）；
 * - 主 frame 导航的 /m3u8 路径形式让位于剥壳（DS 案例 6）；
 * - 帧目标提升加载（player iframe 先探）、懒播放触发、静音收口、CF 验证页
 *   检测提示（只提示不绕过）、player_aaaa 加密壳检测（只记日志：解密逻辑在
 *   DS Flutter 侧 libapp.so 内、APK 里拿不到，不伪造解密）；
 * - 候选面板按 m3u8 > mp4/mpd > 其余排序展示，并带拦截/iframe 诊断行（DSINFO 的
 *   轻量版：只展示本屏已有统计，不另起诊断页）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebSniffScreen(
    name: String,
    url: String,
    onBack: () -> Unit,
    onPlay: (directUrl: String, title: String) -> Unit,
) {
    val context = LocalContext.current
    val probeScope = rememberCoroutineScope()
    val probeClient = remember { CmsApi.defaultClient() }

    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf(name) }
    var currentUrl by remember { mutableStateOf(url) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    // v0.93：用户已选播标记——点选候选后停止后续收集（省流量）
    val hitDone = remember { AtomicBoolean(false) }
    // 超时提示标记：超时后只提示一次
    var timeoutToasted by remember { mutableStateOf(false) }
    // AndroidView factory 里创建的 WebView 引用
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // 拦截到的 URL（去重保序，供"嗅探测试"用；拦截回调在后台线程，用同步容器）
    val seenUrls = remember { java.util.Collections.synchronizedSet(LinkedHashSet<String>()) }
    // 嗅探测试：结果列表，null=没在测
    var testResults by remember { mutableStateOf<List<Pair<String, SniffKind>>?>(null) }
    var testing by remember { mutableStateOf(false) }
    // v0.92：深度嗅探按钮的 loading 态
    var deepSniffing by remember { mutableStateOf(false) }
    // v0.92：blob 深挖去重——同一主页面只深挖一次，主 frame 跳走后重置
    val blobDug = remember { AtomicBoolean(false) }
    var lastMainUrl by remember { mutableStateOf("") }
    // v0.275.0（T40）：候选列表、去重集、待合并请求头收进 ViewModel，
    // 转屏重建不丢候选；离开嗅探页入口销毁时自然清空。其余 remember 状态不动。
    val sniffVm: WebSniffViewModel = viewModel()
    val candidates = sniffVm.candidates
    val candidateUrls = sniffVm.candidateUrls
    val pendingHeaders = sniffVm.pendingHeaders
    // v0.93：hitDone 语义改为"用户已选播"；picked 驱动候选面板显隐
    var picked by remember { mutableStateOf(false) }
    // v0.93：嗅探参数（顶栏溢出菜单可调，改完即时生效）
    var concurrency by remember { mutableIntStateOf(SniffConfig.deepSniffConcurrency) }
    var retryCount by remember { mutableIntStateOf(SniffConfig.probeRetry) }
    // v0.94：广告过滤总开关（候选排除 + 去广告 JS 注入 + 广告域名拦截）
    var adFilter by remember { mutableStateOf(SniffConfig.adFilterEnabled) }
    var menuOpen by remember { mutableStateOf(false) }
    var cfgDialog by remember { mutableStateOf<String?>(null) } // "concurrency" / "retry" / null
    // v0.149：嗅探链路观测（DS 案例 5 要求 init/nav/pageFinished/timeout 全程 LogBus）
    val pageFinishedCount = remember { AtomicInteger(0) }
    // v0.149：主 frame 当前 URL（onPageStarted 更新；onLoadResource 靠它认主 frame）
    var mainFrameUrl by remember { mutableStateOf(url) }
    // v0.149：懒播放触发布防（每页一次，主 frame 换页时重置）
    val lazyTriggered = remember { AtomicBoolean(false) }
    // v0.149：验证页提示布防（同一页只提示一次）
    var challengeToastedUrl by remember { mutableStateOf("") }
    // v0.149：零候选自动重探布防（每个入口 URL 只自动重探一次）
    var autoRetried by remember(url) { mutableStateOf(false) }
    // v0.149：最近一次 iframe 剥壳扫到的 iframe 个数（候选面板诊断行用）
    var lastIframeCount by remember { mutableIntStateOf(0) }

    fun rememberSeen(u: String) {
        if (u.isBlank()) return
        if (seenUrls.size >= SEEN_URL_CAP) {
            // 超上限：清掉一半最老的（同步容器上操作）
            synchronized(seenUrls) {
                val it = seenUrls.iterator()
                var n = seenUrls.size / 2
                while (n-- > 0 && it.hasNext()) { it.next(); it.remove() }
            }
        }
        seenUrls.add(u)
    }

    /**
     * v0.93：候选收集——各链路命中直链后进候选列表（按 url 去重），
     * 用户点选后才播。v0.186.0：拦截链路把原始请求头原样存入候选，
     * 点选时优先用存下的原始头、无存下时才现取现存（[playCandidate]）。
     */
    fun addCandidate(
        url: String,
        source: String,
        kind: SniffKind = SniffKind.DIRECT,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (hitDone.get() || url.isBlank()) return
        // v0.94：广告地址过滤（SniffConfig 广告过滤总开关；默认开）
        if (SniffConfig.adFilterEnabled && SniffEngine.isAdUrl(url)) {
            LogBus.i("WebSniff", "广告过滤 [$source] ${url.take(120)}")
            return
        }
        if (!candidateUrls.add(url)) {
            // 已在列表：若本次拦截带了原始头而旧候选头为空，用原始头补齐
            // （先到 onLoadResource 的候选无请求头，后到的拦截不应被去重丢掉头）。
            // v0.246.0：candidates 是 SnapshotStateList，只能主线程写；
            // shouldInterceptRequest/probe 链路在后台线程，改列表必须切主线程。
            // v0.246.0 补：补头任务可能比入列任务先跑（同一 URL 两路先后到），
            // 此时列表里还没有候选，头不能丢——先存 pendingHeaders，入列时合并。
            // v0.246.0 四轮（GPT 审）：补头任务保存 pending 前再确认预占仍有效——
            // 入列任务可能已因 hitDone 拦截并清掉预占，之后才跑的补头任务若仍写
            // pending，会残留无对应入列任务的过期头；预占已清或已选播就不存。
            if (headers.isNotEmpty()) {
                probeScope.launch {
                    val idx = candidates.indexOfFirst { it.url == url }
                    if (idx >= 0 && candidates[idx].headers.isEmpty()) {
                        candidates[idx] = candidates[idx].copy(headers = headers)
                    } else if (idx < 0 && !hitDone.get() && candidateUrls.contains(url)) {
                        pendingHeaders[url] = headers
                    }
                }
            }
            return
        }
        // v0.246.0：同上，新候选入列也切主线程（probeScope 默认 Main；
        // 主线程调时立即执行，后台调时 post 到主线程，不改调用点）。
        // v0.246.0 补：入列前 hitDone 已翻 true 时清理预占——
        // 原来 URL 留在 candidateUrls 里成幽灵去重记录，之后 hitDone 恢复也加不进来；
        // 入列时合并先到的 pendingHeaders，不丢头。
        // v0.246.0 三轮（GPT 审）：pending 项必须始终消费——
        // 入列被拦截时也要清 pending，否则过期头残留、后续重试误用；
        // 本次 headers 非空时旧 pending 也要清，不能只在 headers 为空时 remove。
        probeScope.launch {
            val pending = pendingHeaders.remove(url)
            if (hitDone.get()) {
                candidateUrls.remove(url)
                return@launch
            }
            val mergedHeaders = if (headers.isEmpty()) pending.orEmpty() else headers
            candidates.add(SniffCandidate(url = url, kind = kind, source = source, headers = mergedHeaders))
            LogBus.i("WebSniff", "候选+1 [$source] ${url.take(120)}")
        }
    }

    /**
     * v0.93：用户从候选列表点选播放（hitDone 语义改为"用户已选播"）。
     * 先把 Referer/UA/Cookie 存进 [SniffPlayHeaders]（v0.21.0：防盗链站点常要 Cookie），
     * 再 stopLoading（省流量）-> 交给播放器。全局只执行一次。
     * v0.149 案例 3：点选后先结算验证——用播放时将带的请求头探一次 Content-Type，
     * 返回网页/脚本类硬拒绝时拦下（aa1.cn 文本截断假地址）、解锁并把该候选从面板移除，
     * 用户可另选；用户自定义正则命中的候选跳过验证（用户已显式背书）。
     */
    fun playCandidate(view: WebView?, candidate: SniffCandidate) {
        if (!hitDone.compareAndSet(false, true)) return
        picked = true
        if (view == null) {
            // v0.186.0：view 缺失时也不得直接绕过结算——先走一次带空头集合的
            // 结算 Content-Type 探测，硬拒绝则不播并提示、解锁，探测通过才播。
            // 播时若候选存有拦截原始头，仍把原始头存给播放器。
            probeScope.launch(Dispatchers.IO) {
                val denied = SniffEngine.settlementDenied(candidate.url, probeClient, emptyMap())
                withContext(Dispatchers.Main) {
                    if (denied) {
                        LogBus.w("WebSniff", "结算拦截：${candidate.url.take(120)} 返回网页/脚本不是视频流")
                        candidates.remove(candidate)
                        candidateUrls.remove(candidate.url)
                        hitDone.set(false)
                        picked = false
                        AppToast.show(context, "该地址返回的是网页不是视频流，已拦截", long = true)
                    } else {
                        if (candidate.headers.isNotEmpty()) {
                            SniffPlayHeaders.put(candidate.url, candidate.headers)
                        }
                        onPlay(candidate.url, pageTitle.ifBlank { name })
                    }
                }
            }
            return
        }
        // v0.109.0 A13：hitDone 先置位防连点，但 post 被丢弃/内部异常时必须解锁——
        // 之前先置位后 post，一旦 runCatching 内异常（被吞掉）或 runnable 永不执行，
        // onPlay 永不执行且 hitDone 已锁死，用户点播放没反应、再点也被拦，直接卡死
        val posted = view.post {
            val headersBuilt = runCatching {
                val referer = view.url.orEmpty()
                val userAgent = runCatching { view.settings?.userAgentString }.getOrNull().orEmpty()
                val cookie = runCatching {
                    CookieManager.getInstance().getCookie(candidate.url)
                }.getOrNull().orEmpty()
                buildMap {
                    if (referer.isNotBlank()) put("Referer", referer)
                    if (userAgent.isNotBlank()) put("User-Agent", userAgent)
                    if (cookie.isNotBlank()) put("Cookie", cookie)
                }
            }.getOrNull()
            if (headersBuilt == null) {
                hitDone.set(false)
                picked = false
                return@post
            }
            // v0.186.0：拦截存下的原始头优先于现拼头（现拼 Referer 取的是主 frame
            // 地址、UA 取当前 WebView 设置，iframe 与跨域候选的原始头会被改写）。
            val headersToUse = if (candidate.headers.isNotEmpty()) candidate.headers else headersBuilt
            probeScope.launch(Dispatchers.IO) {
                val denied = !SniffEngine.matchesCustomPattern(candidate.url) &&
                    SniffEngine.settlementDenied(candidate.url, probeClient, headersToUse)
                withContext(Dispatchers.Main) {
                    if (denied) {
                        LogBus.w("WebSniff", "结算拦截：${candidate.url.take(120)} 返回网页/脚本不是视频流")
                        candidates.remove(candidate)
                        candidateUrls.remove(candidate.url)
                        hitDone.set(false)
                        picked = false
                        AppToast.show(context, "该地址返回的是网页不是视频流，已拦截", long = true)
                    } else {
                        SniffPlayHeaders.put(candidate.url, headersToUse)
                        view.stopLoading()
                        onPlay(candidate.url, pageTitle.ifBlank { name })
                    }
                }
            }
        }
        if (!posted) {
            hitDone.set(false)
            picked = false
        }
    }

    fun onRtmp(view: WebView, rtmpUrl: String) {
        // v0.152.0：rtmp 不再只 Toast 提示，改为进候选列表、点选后走 libmpv 播；
        // 未装 libmpv 时 EngineHost.play 拒绝并提示去市场装，不改用户内核设置。
        // 结算验证对 rtmp 做不了 HEAD（OkHttp 不认 rtmp 协议），fetch 失败即放行，见 settlementDenied。
        addCandidate(rtmpUrl, "rtmp", SniffKind.RTMP)
        LogBus.i("WebSniff", "rtmp 候选+1 ${rtmpUrl.take(120)}")
    }

    /** 可疑 URL 的异步 Content-Type 探测（不阻塞原请求）；命中进候选列表 */
    fun probeAsync(view: WebView, target: String, source: String) {
        probeScope.launch(Dispatchers.IO) {
            val kind = SniffEngine.probeContentType(target, probeClient) ?: return@launch
            when (kind) {
                SniffKind.DIRECT -> addCandidate(target, source)
                SniffKind.RTMP -> onRtmp(view, target)
            }
        }
    }

    /**
     * 各链路发现 URL 的统一入口（第 1/3/4 层共用）：
     * DIRECT 进候选列表；RTMP 提示；blob: 触发深挖；其余可疑的异步探测。
     * 与 deepDigBlob/probeIframes 循环引用，故先声明后赋值。
     */
    lateinit var onUrlFound: (WebView, String, String) -> Unit

    /**
     * v0.92：ts 分片线索处理——同目录拼出 playlist 候选，逐个 probe 验证，
     * 命中才进候选列表。分片 URL 本身永远不直接播。
     */
    fun probeTsClues(view: WebView, tsUrl: String, source: String) {
        rememberSeen(tsUrl)
        val cands = SniffEngine.tsCluePlaylists(tsUrl)
        if (cands.isEmpty()) return
        LogBus.i("WebSniff", "ts 线索 ${tsUrl.take(80)}… 派生 ${cands.size} 个 playlist 候选")
        for (cand in cands) {
            if (hitDone.get()) break
            rememberSeen(cand)
            probeScope.launch(Dispatchers.IO) {
                if (hitDone.get()) return@launch
                if (SniffEngine.probeContentType(cand, probeClient) == SniffKind.DIRECT) {
                    addCandidate(cand, source)
                }
            }
        }
    }

    /**
     * iframe 剥壳：页面加载完抠 iframe src，逐个探测直链。
     * v0.92 修：JS 返回的是 JSON.stringify 后的 src 数组，之前误用
     * [SniffEngine.extractIframeSrcs] 按 HTML 正则解析，永远拿到空列表。
     * v0.149 帧目标提升加载：含 player 的 iframe 先探（播放器壳最可能藏真流）。
     */
    fun probeIframes(view: WebView) {
        if (hitDone.get()) return
        view.evaluateJavascript(
            "(function(){var r=[];var f=document.getElementsByTagName('iframe');" +
                "for(var i=0;i<f.length;i++){var s=f[i].src;if(s)r.push(s);}" +
                "return JSON.stringify(r);})()",
        ) { raw ->
            if (hitDone.get()) return@evaluateJavascript
            val base = view.url.orEmpty()
            val srcs = parseRootStringArray(decodeJsResult(raw))
                .map { SniffEngine.resolveUrl(base, it) }
                .distinct()
            lastIframeCount = srcs.size
            val ordered = srcs.sortedWith(compareByDescending { "player" in it.lowercase() })
            for (src in ordered) {
                if (hitDone.get()) break
                onUrlFound(view, src, "iframe")
            }
        }
    }

    /**
     * blob 命中深挖（第 4 层）：video 播的是 blob: 地址（hls.js/dplayer/artplayer
     * 等 MSE 方案）时，真实流地址藏在 JS 或页面源码里。扫一遍 innerHTML 找媒体
     * URL 逐个判定，同时重跑一遍 iframe 探测。同一主页面只深挖一次。
     * UI 上只给弱提示（Log + 一次 Toast），不打断用户浏览。
     */
    fun deepDigBlob(view: WebView) {
        if (hitDone.get() || !blobDug.compareAndSet(false, true)) return
        LogBus.i("WebSniff", "检测到 blob 播放器，正在深挖…")
        view.post {
            AppToast.show(context, "检测到 blob 播放器，正在深挖…", long = false)
        }
        view.evaluateJavascript(JS_BLOB_DIG) { raw ->
            if (hitDone.get()) return@evaluateJavascript
            val found = parseRootStringArray(decodeJsResult(raw))
            LogBus.i("WebSniff", "blob 深挖扫到 ${found.size} 个候选 URL")
            for (rawU in found) {
                if (hitDone.get()) break
                // v0.202.0：JSON 里转义过的地址（https:\/\/cdn\/x.m3u8、\u0026、\u002f）先还原，否则进播放器是坏链
                val u = rawU.replace("\\/", "/").replace("\\u0026", "&")
                    .replace("\\u002f", "/").replace("\\u002F", "/")
                // v0.92：ts 分片只是线索——同目录拼 playlist 候选并验证，不直接进候选
                if (SniffEngine.isTsSegment(u)) probeTsClues(view, u, "blob深挖")
                else onUrlFound(view, u, "blob深挖")
            }
            // 同时重跑一遍 iframe 剥壳探测
            if (!hitDone.get()) probeIframes(view)
        }
    }

    /** 第 4 层：JS video hook 轮询——抓 video.currentSrc/src + hook 到的 src + 动态 iframe 队列 */
    fun pollVideoHook(view: WebView) {
        if (hitDone.get()) return
        // v0.149 懒播放触发：候选为空时每页只触发一次（见 JS_LAZY_PLAY）
        if (candidates.isEmpty() && !lazyTriggered.getAndSet(true)) {
            view.evaluateJavascript(JS_LAZY_PLAY, null)
        }
        view.evaluateJavascript(JS_VIDEO_POLL) { raw ->
            if (hitDone.get()) return@evaluateJavascript
            val decoded = decodeJsResult(raw)
            if (decoded.isBlank()) return@evaluateJavascript
            for (u in parseStringArray(decoded, "v")) {
                if (hitDone.get()) break
                onUrlFound(view, u, "JS钩子")
            }
            for (f in parseStringArray(decoded, "f")) {
                if (hitDone.get()) break
                onUrlFound(view, SniffEngine.resolveUrl(view.url.orEmpty(), f), "iframe")
            }
        }
    }

    onUrlFound = { view, url, source ->
        if (url.isNotBlank() && !hitDone.get()) {
            rememberSeen(url)
            when (SniffEngine.sniffKind(url)) {
                SniffKind.DIRECT -> addCandidate(url, source)
                SniffKind.RTMP -> onRtmp(view, url)
                null -> when {
                    url.startsWith("blob:", ignoreCase = true) -> deepDigBlob(view)
                    SniffEngine.isSuspiciousMediaUrl(url) -> probeAsync(view, url, source)
                }
            }
        }
    }

    fun showTimeoutToast() {
        if (!timeoutToasted && !picked && candidates.isEmpty()) {
            timeoutToasted = true
            AppToast.show(context, "未嗅探到可播放直链，可继续手动浏览", long = true)
        }
    }

    /** 一键嗅探测试：对已拦截的 URL + 当前页 iframe 逐个做 Content-Type 判定，结果弹列表 */
    fun runSniffTest() {
        val view = webViewRef ?: return
        if (testing) return
        testing = true
        probeScope.launch(Dispatchers.IO) {
            val candidates = LinkedHashSet<String>()
            synchronized(seenUrls) { candidates += seenUrls }
            // 当前页 iframe 也加进来
            // v0.246.0：原来 withContext(Main) 里 latch.await(5s) 等主线程回调，
            // 主线程被自己堵住、回调跑不了，必卡满 5 秒。改挂起等回调，不阻塞主线程。
            // v0.246.0 三轮（GPT 审）：外层 runCatching 会吞取消——取消时
            // suspendCancellableCoroutine 抛 CancellationException，被 runCatching
            // 当普通失败返回空列表，探测继续跑完；改 try/catch，取消重抛。
            val iframeSrcs = withContext(Dispatchers.Main) {
                try {
                    suspendCancellableCoroutine<List<String>> { cont ->
                        val handler = android.os.Handler(android.os.Looper.getMainLooper())
                        // v0.246.0 补：超时任务要能取消——原来正常回调后 5 秒任务仍留着，
                        // 闭包继续持有 continuation。正常回调后移除，取消时也移除。
                        val timeoutTask = Runnable {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                        cont.invokeOnCancellation { handler.removeCallbacks(timeoutTask) }
                        view.evaluateJavascript(
                            "(function(){var r=[];var f=document.getElementsByTagName('iframe');" +
                                "for(var i=0;i<f.length;i++){var s=f[i].src;if(s)r.push(s);}" +
                                "return JSON.stringify(r);})()",
                        ) { raw ->
                            val list = runCatching {
                                val decoded = JSONObject("{\"v\":$raw}").getString("v")
                                SniffEngine.extractIframeSrcs(decoded, view.url.orEmpty())
                            }.getOrDefault(emptyList())
                            handler.removeCallbacks(timeoutTask)
                            if (cont.isActive) cont.resume(list)
                        }
                        // 超时兜底：5 秒没回就放空列表，不无限挂起
                        handler.postDelayed(timeoutTask, 5000)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
            }
            candidates += iframeSrcs
            val results = mutableListOf<Pair<String, SniffKind>>()
            for (u in candidates) {
                if (hitDone.get()) break
                val kind = SniffEngine.sniffKind(u)
                    ?: if (SniffEngine.isSuspiciousMediaUrl(u)) {
                        SniffEngine.probeContentType(u, probeClient)
                    } else null
                if (kind != null) results += u to kind
            }
            withContext(Dispatchers.Main) {
                testing = false
                testResults = results
                if (results.isEmpty()) {
                    AppToast.show(context, "嗅探测试：未发现可播直链", long = false)
                }
            }
        }
    }

    /**
     * v0.92 第 5 层：深度嗅探（手动触发）。
     * v0.93：命中进候选列表（不再首条即播）；并发数走 [SniffConfig]。
     * 把拦截到的全部 http(s) URL（去重、排除静态资源后缀）逐个 Content-Type 探测，
     * ts 分片只当线索（同目录拼 playlist 候选验证）。全灭则 Toast 提示。
     * 按钮探测中显示 loading，禁用重复点击。
     */
    fun runDeepSniff() {
        val view = webViewRef ?: return
        if (deepSniffing || testing) return
        deepSniffing = true
        LogBus.i("WebSniff", "深度嗅探开始")
        probeScope.launch(Dispatchers.IO) {
            val seen = LinkedHashSet<String>()
            synchronized(seenUrls) { seen += seenUrls }
            val httpUrls = seen.filter { u ->
                val l = u.lowercase()
                (l.startsWith("http://") || l.startsWith("https://")) &&
                    !SniffEngine.isStaticResource(u)
            }
            val permits = SniffConfig.deepSniffConcurrency.coerceIn(2, 16)
            LogBus.i("WebSniff", "深度嗅探候选 ${httpUrls.size} 个，并发 $permits")
            val semaphore = Semaphore(permits)
            val added = AtomicInteger(0)
            suspend fun collectIfDirect(u: String) {
                if (hitDone.get()) return
                val kind = SniffEngine.sniffKind(u) ?: SniffEngine.probeContentType(u, probeClient)
                when (kind) {
                    SniffKind.DIRECT -> {
                        val before = candidateUrls.size
                        addCandidate(u, "深度嗅探")
                        if (candidateUrls.size > before) added.incrementAndGet()
                    }
                    SniffKind.RTMP -> onRtmp(view, u)
                    null -> {}
                }
            }
            val jobs = httpUrls.map { u ->
                launch {
                    if (hitDone.get()) return@launch
                    // ts 分片只是线索：同目录拼 playlist 候选并验证，不直接进候选
                    if (SniffEngine.isTsSegment(u)) {
                        rememberSeen(u)
                        for (cand in SniffEngine.tsCluePlaylists(u)) {
                            if (hitDone.get()) break
                            semaphore.withPermit {
                                if (hitDone.get()) return@withPermit
                                rememberSeen(cand)
                                collectIfDirect(cand)
                            }
                        }
                        return@launch
                    }
                    semaphore.withPermit {
                        if (hitDone.get()) return@withPermit
                        collectIfDirect(u)
                    }
                }
            }
            jobs.joinAll()
            withContext(Dispatchers.Main) {
                deepSniffing = false
                if (!hitDone.get() && added.get() == 0) {
                    AppToast.show(context, "深度嗅探也没找到，可试手动复制地址", long = true)
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
        topBar = {
            Column {
                TabTopBar(
                    title = pageTitle,
                    onBack = onBack,
                    actions = {
                        // v0.93：嗅探参数（溢出菜单）
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "嗅探设置")
                            }
                            AppDropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("探测并发数：$concurrency") },
                                    onClick = { menuOpen = false; cfgDialog = "concurrency" },
                                )
                                DropdownMenuItem(
                                    text = { Text("失败重试：$retryCount 次") },
                                    onClick = { menuOpen = false; cfgDialog = "retry" },
                                )
                                DropdownMenuItem(
                                    text = { Text("过滤广告地址：${if (adFilter) "开" else "关"}") },
                                    onClick = {
                                        menuOpen = false
                                        SniffConfig.update(context, adFilter = !adFilter)
                                        adFilter = SniffConfig.adFilterEnabled
                                        AppToast.show(context, "已生效", long = false)
                                    },
                                )
                            }
                        }
                    },
                )
                // v0.10：浏览器式头部——后退/前进/刷新 + 当前 URL + 嗅探测试
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { webViewRef?.goBack() },
                        enabled = canGoBack,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "后退")
                    }
                    IconButton(
                        onClick = { webViewRef?.goForward() },
                        enabled = canGoForward,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "前进")
                    }
                    IconButton(onClick = { webViewRef?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    Text(
                        text = currentUrl,
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = ::runSniffTest,
                        enabled = !testing,
                    ) {
                        Text(if (testing) "测试中…" else "嗅探测试")
                    }
                    // v0.92：第 5 层深度嗅探——手动把拦截到的 URL 全量探测一遍
                    TextButton(
                        onClick = ::runDeepSniff,
                        enabled = !deepSniffing && !testing,
                    ) {
                        if (deepSniffing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (deepSniffing) "深挖中…" else "深度嗅探")
                    }
                }
            }
        },
    ) { inner ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        // v0.10：混合内容放行 + 第三方 Cookie（部分站点的视频/鉴权依赖它们）
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                if (!title.isNullOrBlank()) pageTitle = title
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                super.onPageStarted(view, url, favicon)
                                if (view != null && !url.isNullOrBlank()) {
                                    mainFrameUrl = url
                                    // v0.149 document_start 等价注入：页面脚本跑起来之前先把
                                    // 视频钩子与 iframe 追踪装上（注入幂等，onPageFinished 再兜底一次）
                                    view.evaluateJavascript(JS_HOOK_INSTALL, null)
                                    view.evaluateJavascript(JS_IFRAME_OBSERVER, null)
                                    LogBus.i("WebSniff", "导航 init $url")
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                currentUrl = view?.url ?: url.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                if (view != null) {
                                    pageFinishedCount.incrementAndGet()
                                    // v0.149 CF 真窗口检测：只提示不绕过——本屏就是真 WebView，
                                    // 用户手动过验证后 Cookie 自动续上、嗅探继续
                                    val mainUrl = view.url.orEmpty()
                                    if (mainUrl.isNotBlank() &&
                                        SniffEngine.isChallengePage(mainUrl, view.title.orEmpty()) &&
                                        challengeToastedUrl != mainUrl
                                    ) {
                                        challengeToastedUrl = mainUrl
                                        LogBus.w("WebSniff", "检测到验证页，等用户手动通过后嗅探自动继续：$mainUrl")
                                        AppToast.show(context, "检测到验证页，请手动完成验证，验证通过后嗅探自动继续", long = true)
                                    }
                                    // v0.149 player_aaaa 加密壳检测：只记日志，不伪造解密——
                                    // 解密逻辑在 DS Flutter 侧 libapp.so 内、APK 里拿不到；
                                    // 解密后的地址靠视频钩子（src setter）在播放器起播时捕获
                                    view.evaluateJavascript(
                                        "(document.documentElement.innerHTML.indexOf('player_aaaa')>=0)?'1':'0'",
                                    ) { raw ->
                                        if (decodeJsResult(raw) == "1") {
                                            LogBus.w(
                                                "WebSniff",
                                                "检测到 player_aaaa 加密播放器，解密逻辑不在包内，靠视频钩子捕获解密后地址",
                                            )
                                        }
                                    }
                                    // v0.92：主 frame 换页后重置 blob 深挖标记
                                    if (mainUrl.isNotBlank() && mainUrl != lastMainUrl) {
                                        lastMainUrl = mainUrl
                                        blobDug.set(false)
                                        lazyTriggered.set(false)
                                    }
                                    // v0.92 第 3 层：动态 iframe 追踪（新页面重新注入）
                                    view.evaluateJavascript(JS_IFRAME_OBSERVER, null)
                                    // v0.94：inject 式去广告（广告过滤总开关控制）
                                    if (SniffConfig.adFilterEnabled) {
                                        view.evaluateJavascript(JS_RM_AD, null)
                                    }
                                    probeIframes(view)
                                }
                            }

                            /**
                             * v0.92：onLoadResource 监听——拿到页面加载的全部资源 URL，
                             * 含部分 shouldInterceptRequest 视角不易命中的（如重定向后的地址）。
                             * 主线程回调：rememberSeen 是同步容器，探测走异步 probeAsync。
                             * 判定与拦截链路完全一致，共用 hitDone 去重。
                             */
                            override fun onLoadResource(view: WebView?, url: String?) {
                                super.onLoadResource(view, url)
                                val webView = view ?: return
                                val u = url.orEmpty()
                                if (u.isEmpty() || hitDone.get()) return
                                rememberSeen(u)
                                // v0.149 案例 6：主 frame 自身的 /m3u8 路径形式不进候选
                                // （壳页让位于剥壳；真后缀直链不受影响）
                                when (SniffEngine.sniffKind(u, u == mainFrameUrl)) {
                                    SniffKind.DIRECT -> addCandidate(u, "网络拦截")
                                    SniffKind.RTMP -> onRtmp(webView, u)
                                    null -> if (SniffEngine.isSuspiciousMediaUrl(u)) {
                                        probeAsync(webView, u, "网络拦截")
                                    }
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val webView = view
                                val reqUrl = request?.url?.toString().orEmpty()
                                // v0.94：广告域名黑名单——命中直接返回空响应拦截，不请求
                                if (SniffConfig.adFilterEnabled && reqUrl.isNotEmpty()) {
                                    val host = request?.url?.host.orEmpty()
                                    if (SniffEngine.isAdHost(host)) {
                                        LogBus.i("WebSniff", "广告域名拦截 $host")
                                        return WebResourceResponse(
                                            "text/plain", "utf-8",
                                            ByteArrayInputStream(ByteArray(0)),
                                        )
                                    }
                                }
                                if (webView != null && reqUrl.isNotEmpty()) {
                                    rememberSeen(reqUrl)
                                    // v0.149 案例 6：主 frame 导航的 /m3u8 路径形式不进候选
                                    when (SniffEngine.sniffKind(reqUrl, request?.isForMainFrame == true)) {
                                        SniffKind.DIRECT -> addCandidate(
                                            reqUrl, "网络拦截",
                                            headers = request?.requestHeaders.orEmpty(),
                                        )
                                        SniffKind.RTMP -> onRtmp(webView, reqUrl)
                                        null -> if (SniffEngine.isSuspiciousMediaUrl(reqUrl)) {
                                            probeAsync(webView, reqUrl, "网络拦截")
                                        }
                                    }
                                }
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        // Service Worker 嗅探补齐：部分站点的 fetch 走 SW 上下文，WebViewClient 拦不到。
                        // 不支持该特性的 WebView 静默跳过（不崩）；命中时进候选列表，
                        // 共用 hitDone 去重；未命中返回 null 不拦截。
                        runCatching {
                            if (WebViewFeature.isFeatureSupported(
                                    WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
                                )
                            ) {
                                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
                                    object : ServiceWorkerClientCompat() {
                                        override fun shouldInterceptRequest(
                                            request: WebResourceRequest
                                        ): WebResourceResponse? {
                                            val reqUrl = request.url.toString()
                                            if (reqUrl.isEmpty()) return null
                                            when (SniffEngine.sniffKind(reqUrl)) {
                                                SniffKind.DIRECT -> addCandidate(
                                                    reqUrl, "网络拦截",
                                                    headers = request.requestHeaders,
                                                )
                                                SniffKind.RTMP -> onRtmp(this@apply, reqUrl)
                                                null -> { /* SW 上下文不做 HEAD 探测，避免跨线程复杂 */ }
                                            }
                                            return null
                                        }
                                    }
                                )
                            }
                        }
                        webViewRef = this
                        loadUrl(url)
                    }
                },
            )
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // v0.93：嗅探候选列表（毛玻璃卡片，底部悬浮；点选播放）
            if (!picked) {
                SniffCandidatePanel(
                    candidates = candidates,
                    seenCount = seenUrls.size,
                    iframeCount = lastIframeCount,
                    onPick = { c -> playCandidate(webViewRef, c) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }
    }

    // 嗅探测试结果弹窗：点 DIRECT 条目直接播放
    val results = testResults
    if (results != null) {
        TechDialog(
            onDismissRequest = { testResults = null },
            title = "嗅探测试（${results.size} 条）",
            dismissText = "关闭",
        ) {
                if (results.isEmpty()) {
                    Text(
                        "没抓到可播直链，可以换个页面再试一次。",
                        style = SoftType.Body,
                    )
                } else {
                    LazyColumn {
                        // v0.166.0：嗅探结果同 URL 去重，免 key 撞车（"debug" 式崩溃同类）
                        items(results.distinctBy { it.first }, key = { it.first }) { (u, kind) ->
                            val label = if (kind == SniffKind.DIRECT) "可播" else "rtmp·走 libmpv"
                            TextButton(
                                onClick = {
                                    testResults = null
                                    // v0.152.0：rtmp 也走 playCandidate（结算验证对 rtmp 失败即放行，
                                    // EngineHost.play 再按 libmpv 是否安装决定播还是提示）。
                                    playCandidate(
                                        webViewRef,
                                        SniffCandidate(url = u, kind = kind, source = "嗅探测试"),
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.Start,
                                ) {
                                    Text(
                                        text = "[$label]",
                                        style = SoftType.Caption,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = u,
                                        style = SoftType.Body,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    // v0.93：嗅探参数设置（顶栏溢出菜单进入，改完即时生效）
    val cfg = cfgDialog
    if (cfg != null) {
        val isConcurrency = cfg == "concurrency"
        TechDialog(
            onDismissRequest = { cfgDialog = null },
            title = if (isConcurrency) "探测并发数" else "失败重试",
            subtitle = if (isConcurrency) "深度嗅探同时探测几个地址" else "可疑地址探测失败后重试几次",
            onConfirm = null,
            content = {
                (if (isConcurrency) listOf(4, 6, 8) else listOf(0, 1, 2)).forEach { v ->
                    val selected = v == if (isConcurrency) concurrency else retryCount
                    TextButton(
                        onClick = {
                            if (isConcurrency) {
                                SniffConfig.update(context, concurrency = v)
                                concurrency = SniffConfig.deepSniffConcurrency
                            } else {
                                SniffConfig.update(context, retry = v)
                                retryCount = SniffConfig.probeRetry
                            }
                            cfgDialog = null
                            AppToast.show(context, "已生效", long = false)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = (if (isConcurrency) "$v" else "$v 次") +
                                if (selected) " ✓" else "",
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
        )
    }

    // 20 秒嗅探超时：只 Toast，不关闭页面
    LaunchedEffect(url) {
        SniffConfig.ensureLoaded(context)
        concurrency = SniffConfig.deepSniffConcurrency
        retryCount = SniffConfig.probeRetry
        adFilter = SniffConfig.adFilterEnabled
        delay(SNIFF_TIMEOUT_MS)
        // v0.149 案例 5 超时观测：预算耗尽时只结算统计、永不作废已有候选——
        // DS 荣耀案"候选已到手却被超时作废"在本架构下不可能发生：候选列表模式下
        // 超时从不清空候选，超时后用户仍可点选；这里只记一笔观测日志供事后复盘。
        LogBus.i(
            "WebSniff",
            "嗅探超时统计：已拦截 ${seenUrls.size} 个请求，候选 ${candidates.size} 条，" +
                "页面加载 ${pageFinishedCount.get()} 次，已选播=$picked",
        )
        if (picked) return@LaunchedEffect
        if (candidates.isNotEmpty()) {
            AppToast.show(context, "嗅探超时，已保留 ${candidates.size} 条候选，点选播放", long = true)
        } else if (!autoRetried) {
            // v0.149 案例 2 重试轮回：零候选时自动把已拦截队列全量再探一轮；
            // 全灭时深度嗅探自己会 Toast，用户无需再点一次按钮
            autoRetried = true
            LogBus.i("WebSniff", "零候选，自动重探已拦截队列一轮")
            runDeepSniff()
        } else {
            showTimeoutToast()
        }
    }

    // v0.92 第 4 层：每 2 秒轮询 JS video hook（video.currentSrc/src + hook 到的
    // src + MutationObserver 攒的动态 iframe）；命中或 WebView 销毁后停
    LaunchedEffect(url) {
        while (!hitDone.get()) {
            delay(2_000)
            val v = webViewRef ?: break
            if (hitDone.get()) break
            runCatching { pollVideoHook(v) }
        }
    }

    // 页面销毁时销毁 WebView，防止泄漏；同时清掉 SW 嗅探拦截器，
    // 避免拦截器持有已销毁 WebView 的引用
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webViewRef?.apply {
                    stopLoading()
                    removeAllViews()
                    destroy()
                }
            }
            webViewRef = null
            runCatching {
                ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(null)
            }
        }
    }
}

/**
 * v0.93：嗅探候选列表面板——毛玻璃卡片，底部悬浮。
 * 为空时显示"嗅探中…"；有候选后列出格式徽标 + 来源 + 地址，点条目播放。
 * v0.149：展示按 m3u8 > mp4/mpd > 其余排序（[SniffEngine.rankIndex]，同级按发现顺序）；
 * 标题下带拦截/iframe 诊断行（DSINFO 轻量版）。
 */
@Composable
private fun SniffCandidatePanel(
    candidates: List<SniffCandidate>,
    seenCount: Int,
    iframeCount: Int,
    onPick: (SniffCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 展示排序：m3u8 最优，mp4/mpd 次之，其余最后；同级保持发现顺序
    val ordered = candidates.withIndex()
        .sortedWith(compareBy({ SniffEngine.rankIndex(it.value.url) }, { it.index }))
        .map { it.value }
    AppCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "嗅探候选",
                    style = V4Type.Section,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                if (candidates.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "嗅探中…",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "${candidates.size} 条，点选播放",
                        style = SoftType.Caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = "已拦截 $seenCount 个请求 · iframe $iframeCount 个",
                style = V4Type.MonoSmall,
                color = V4.c.t3,
            )
            if (ordered.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(ordered, key = { it.url }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 格式徽标
                            // v0.280.0 V4：格式徽标改等宽小标签
                            V4Tag(formatLabelOf(c.url), V4TagKind.Accent)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = c.url,
                                    style = V4Type.MonoMeta,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "来源：" + c.source,
                                    style = SoftType.Caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 候选条目的格式徽标：M3U8 / MP4 / RTMP / …；认不出时显示"直链" */
private fun formatLabelOf(url: String): String {
    val l = url.lowercase()
    if (l.startsWith("rtmp://")) return "RTMP"
    if ("m3u8" in l) return "M3U8"
    val path = l.substringBefore('#').substringBefore('?')
    val ext = path.substringAfterLast('.', "")
    return if (ext.isNotEmpty() && ext.length <= 5 && '/' !in ext) ext.uppercase() else "直链"
}
