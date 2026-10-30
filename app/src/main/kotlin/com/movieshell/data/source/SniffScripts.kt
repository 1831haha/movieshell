package com.movieshell.data.source

import org.json.JSONArray
import org.json.JSONObject

/*
 * v0.294.7（H4）：嗅探页注入脚本与结果解码——原 WebSniffScreen 私有常量原样搬出，
 * 手动嗅探页与自动嗅探（AutoSniffer：解析线路 type=0、规则 parse=1 页面嗅探）共用同一套脚本。
 */

/**
 * v0.92 第 4 层：JS video hook 轮询脚本。
 * 首次执行时 hook HTMLMediaElement.src 的 setter（hls.js 等库常走 el.src = xxx 赋值）；
 * 每次返回 {v:[video 地址], f:[MutationObserver 攒的新增 iframe src]}。
 * iframe 队列在 JS 侧 splice 取走，天然去重（observer 侧另有 seen 表）。
 * v0.149 静音收口：每轮顺手静音页内全部 video/audio——嗅探页只负责抓地址，
 * 真播放走播放器，嗅探页出声纯属打扰。
 */
internal const val JS_VIDEO_POLL = """
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
internal const val JS_HOOK_INSTALL = """
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
internal const val JS_LAZY_PLAY = """
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
internal const val JS_IFRAME_OBSERVER = """
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
internal const val JS_BLOB_DIG = """
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
internal const val JS_RM_AD = """
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
internal fun decodeJsResult(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return runCatching { JSONObject("{\"v\":$raw}").getString("v") }.getOrDefault("")
}

/** 从 {"v":[...],"f":[...]} 这类对象里取字符串数组 */
internal fun parseStringArray(json: String, key: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONObject(json).optJSONArray(key) ?: return emptyList()
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

/** 从顶层 JSON 数组字符串里取字符串列表 */
internal fun parseRootStringArray(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrDefault(emptyList())
}

