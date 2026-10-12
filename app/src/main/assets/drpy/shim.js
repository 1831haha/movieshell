/* drpy 规则执行垫片（movieshell WebView 引擎用）。
 * 在 WebView 全局作用域 evaluate，本文件 + 规则源码拼在一起执行。
 * 规则侧只用到：全局 request(url, options)、this.input / this.HOST / this.KEY / this.MY_PAGE、
 * 以及导出的 __drpyHome/__drpyCategory/__drpyDetail/__drpySearch/__drpyPlay/__drpyFilters
 *（全部返回 JSON 字符串）。
 * 筛选支持（drpy2 语义）：rule.filter_url 里的 {{fl.键名}} 占位符按筛选值渲染后拼进
 * url 的 fyfilter 处（无 fyfilter 标记时追加到 url 末尾）；无筛选值时按规则默认值
 *（filter_def[tid]，再取 filter 分组首项）或空字符串渲染；gzip+base64 的 filter 字符串自动解开。
 * 已知不支持：cheerio/pdfa/pdfh 等 HTML 解析、模板简写（推荐:'json:...' 字符串形式）、
 * rule.lazy 返回非对象时的复杂嗅探、jsDelivr 远程依赖。
 */
(function () {
'use strict';

var MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36';

if (typeof globalThis.log !== 'function') {
    globalThis.log = function () {
        try { if (typeof console !== 'undefined') console.log.apply(console, arguments); } catch (e) {}
    };
}

/* ---------- 请求头 ---------- */
function buildHeaders(rule, optHeaders) {
    var h = {};
    try {
        var rh = rule ? rule.headers : null;
        if (typeof rh === 'function') rh = rh();
        if (rh && typeof rh === 'object') {
            Object.keys(rh).forEach(function (k) { h[k] = rh[k]; });
        }
    } catch (e) {}
    if (optHeaders && typeof optHeaders === 'object') {
        Object.keys(optHeaders).forEach(function (k) { h[k] = optHeaders[k]; });
    }
    Object.keys(h).forEach(function (k) {
        if (h[k] === 'MOBILE_UA') h[k] = MOBILE_UA;
    });
    if (!h['User-Agent'] && !h['user-agent']) h['User-Agent'] = MOBILE_UA;
    return h;
}

/* ---------- 模板渲染：fyclass fypage fyid ** {{fl.键名}} {{fyclass}} ---------- */
function renderTpl(tpl, ctx) {
    var u = String(tpl == null ? '' : tpl);
    // {{fl.键名}} 筛选占位符（drpy2 用 jinja2 渲染，值原样拼接、不做 URL 编码；
    // 键名可含中文，如 {{fl.排序}}）。无筛选值时渲染为空字符串（drpy2 默认行为）。
    u = u.replace(/\{\{\s*fl\.([^{}]+?)\s*\}\}/g, function (m, k) {
        var v = (ctx.fl && typeof ctx.fl === 'object') ? ctx.fl[k] : null;
        return v == null ? '' : String(v);
    });
    var tid = encodeURIComponent(ctx.tid == null ? '' : String(ctx.tid));
    u = u.replace(/\{\{\s*fyclass\s*\}\}/g, tid);
    u = u.split('fyclass').join(tid);
    u = u.split('fypage').join(String(ctx.pg == null ? 1 : ctx.pg));
    u = u.split('fyid').join(String(ctx.id == null ? '' : ctx.id));
    u = u.split('**').join(encodeURIComponent(ctx.wd == null ? '' : String(ctx.wd)));
    return u;
}

function absUrl(host, tpl, ctx) {
    var u = renderTpl(tpl, ctx);
    if (/^https?:\/\//i.test(u)) return u;
    var h = String(host || '');
    if (!h) return u;
    if (h.charAt(h.length - 1) === '/' && u.charAt(0) === '/') return h + u.slice(1);
    if (h.charAt(h.length - 1) !== '/' && u.charAt(0) !== '/') return h + '/' + u;
    return h + u;
}

function makeCtx(rule, extra) {
    var ctx = { HOST: rule ? rule.host : '', input: '' };
    if (extra && typeof extra === 'object') {
        Object.keys(extra).forEach(function (k) { ctx[k] = extra[k]; });
    }
    return ctx;
}

/* ---------- drpy 筛选（filter_url / fyfilter / {{fl.键名}}） ---------- */

/** base64(gzip JSON) → JSON 字符串；失败抛错，调用方吞掉走空筛选。 */
async function ungzipB64(b64) {
    var bin = atob(String(b64).trim());
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    var ds = new DecompressionStream('gzip');
    return await new Response(new Blob([bytes]).stream().pipeThrough(ds)).text();
}

/**
 * 取某分类的筛选分组：rule.filter 可能是对象，也可能是 gzip+base64 字符串
 *（drpy2 的 ungzip 语义）。先按 tid 取，取不到再按 'default' 取。
 */
async function getFilterGroups(tid) {
    try {
        var f = (typeof rule !== 'undefined') ? rule.filter : null;
        if (typeof f === 'string' && f.trim().length > 0) {
            try { f = JSON.parse(await ungzipB64(f)); } catch (e) { return []; }
        }
        if (!f || typeof f !== 'object') return [];
        var def = f[tid] || f['default'];
        return Array.isArray(def) ? def : [];
    } catch (e) { return []; }
}

/**
 * 拼筛选值表（drpy2 语义）：
 * 1) rule.filter_def[tid] 默认值打底（drpy2 的"自动合并不同分类默认筛选"）；
 * 2) 缺的键用 rule.filter 分组首个选项的 v 补（规则里首项一般是"全部"）；
 * 3) 调用方显式传入的筛选值覆盖。
 */
async function buildFilterMap(tid, selected) {
    var fl = {};
    try {
        var fd = rule.filter_def;
        if (fd && typeof fd === 'object') {
            var d = fd[tid] || fd['default'];
            if (d && typeof d === 'object') {
                Object.keys(d).forEach(function (k) { fl[k] = str(d[k]); });
            }
        }
    } catch (e) {}
    var groups = await getFilterGroups(tid);
    groups.forEach(function (g) {
        g = g || {};
        if (g.key && fl[g.key] == null) {
            var opts = Array.isArray(g.value) ? g.value : [];
            fl[g.key] = opts.length > 0 ? str(opts[0].v) : '';
        }
    });
    if (selected && typeof selected === 'object') {
        Object.keys(selected).forEach(function (k) { fl[k] = str(selected[k]); });
    }
    return fl;
}

/* ---------- 全局 request：转调 Java 桥（同步）/ node 桩（异步） ---------- */
globalThis.request = async function (url, options) {
    options = options || {};
    var method = String(options.method || 'GET').toUpperCase();
    var body = options.body == null ? '' : String(options.body);
    var headers = buildHeaders(typeof rule !== 'undefined' ? rule : null, options.headers);
    var resp = await globalThis.__bridge.http(String(url), JSON.stringify(headers), method, body);
    return resp == null ? '' : String(resp);
};

/* ---------- 归一化 ---------- */
function str(v) { return v == null ? '' : String(v); }

function normVod(v) {
    v = v || {};
    return {
        vod_id: str(v.vod_id),
        vod_name: str(v.vod_name),
        vod_pic: str(v.vod_pic),
        vod_remarks: str(v.vod_remarks),
        vod_year: str(v.vod_year),
        type_name: str(v.type_name)
    };
}

function normClass(c) {
    c = c || {};
    return { type_id: str(c.type_id), type_name: str(c.type_name) };
}

function toList(r) {
    if (Array.isArray(r)) return r.map(normVod);
    if (r && Array.isArray(r.list)) return r.list.map(normVod);
    return [];
}

function errJson(e) {
    return JSON.stringify({ error: str((e && e.message) || e) });
}

function needRule() {
    if (typeof rule === 'undefined') throw new Error('rule not loaded');
}

/* ---------- 导出：5 个入口 ---------- */
globalThis.__drpyHome = async function () {
    try {
        needRule();
        var cls = [];
        if (typeof rule.class_parse === 'function') {
            var cr = await rule.class_parse.call(makeCtx(rule));
            var arr = (cr && cr['class']) ? cr['class'] : (Array.isArray(cr) ? cr : []);
            cls = arr.map(normClass);
        } else if (rule.class_name && rule.class_url) {
            var names = String(rule.class_name).split('&');
            var urls = String(rule.class_url).split('&');
            for (var i = 0; i < names.length; i++) {
                cls.push({ type_id: str(urls[i] || names[i]), type_name: str(names[i]) });
            }
        }
        var list = [];
        if (typeof rule.推荐 === 'function') {
            var input = absUrl(rule.host, rule.homeUrl || '', {});
            list = toList(await rule.推荐.call(makeCtx(rule, { input: input })));
        }
        return JSON.stringify({ 'class': cls, list: list });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyCategory = async function (tid, pg, filter) {
    try {
        needRule();
        pg = pg || 1;
        var urlTpl = rule.url || '';
        var fl = {};
        // drpy2 语义：有 filter_url 时先渲染它，再拼进 url 的 fyfilter 处；
        // url 里没有 fyfilter 标记时追加到末尾（用 & 连接）。
        if (rule.filter_url) {
            fl = await buildFilterMap(tid, filter);
            var furl = renderTpl(rule.filter_url, { tid: tid, pg: pg, fl: fl });
            if (/fyfilter/.test(urlTpl)) {
                urlTpl = urlTpl.replace('fyfilter', furl);
            } else {
                if (urlTpl.charAt(urlTpl.length - 1) !== '&' && furl.charAt(0) !== '&') urlTpl += '&';
                urlTpl += furl;
            }
        }
        var input = absUrl(rule.host, urlTpl, { tid: tid, pg: pg, fl: fl });
        var r = await rule.一级.call(makeCtx(rule, { input: input, TID: tid, PG: pg }));
        var page = pg;
        if (r && typeof r === 'object' && !Array.isArray(r) && r.page != null) {
            page = Number(r.page) || pg;
        }
        return JSON.stringify({ list: toList(r), page: page });
    } catch (e) { return errJson(e); }
};

/* 筛选定义：给 UI 画筛选器用。返回 {filterable, groups:[{key,name,value:[{n,v}]}]} */
globalThis.__drpyFilters = async function (tid) {
    try {
        needRule();
        var groups = await getFilterGroups(tid);
        var out = groups.map(function (g) {
            g = g || {};
            var opts = Array.isArray(g.value) ? g.value : [];
            return {
                key: str(g.key),
                name: str(g.name),
                value: opts.map(function (o) { o = o || {}; return { n: str(o.n), v: str(o.v) }; })
            };
        });
        return JSON.stringify({ filterable: rule.filterable ? 1 : 0, groups: out });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyDetail = async function (id) {
    try {
        needRule();
        var input = absUrl(rule.host, rule.detailUrl || '', { id: id });
        var r = await rule.二级.call(makeCtx(rule, { input: input, ID: id })) || {};
        return JSON.stringify({
            vod_id: str(id),
            vod_name: str(r.vod_name),
            vod_pic: str(r.vod_pic),
            vod_remarks: str(r.vod_remarks),
            vod_year: str(r.vod_year),
            vod_actor: str(r.vod_actor || r.vod_actors),
            vod_director: str(r.vod_director),
            vod_desc: str(r.vod_desc || r.vod_content),
            vod_play_from: str(r.vod_play_from),
            vod_play_url: str(r.vod_play_url)
        });
    } catch (e) { return errJson(e); }
};

globalThis.__drpySearch = async function (wd, pg) {
    try {
        needRule();
        pg = pg || 1;
        var input = absUrl(rule.host, rule.searchUrl || '', { wd: wd, pg: pg });
        var r = await rule.搜索.call(makeCtx(rule, { input: input, KEY: wd, MY_PAGE: pg }));
        return JSON.stringify({ list: toList(r) });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyPlay = async function (flag, id) {
    try {
        needRule();
        var fn = rule.播放 || rule.lazy;
        if (typeof fn === 'function') {
            var r = await fn.call(makeCtx(rule, { input: str(id), flag: flag }));
            if (typeof r === 'string') return JSON.stringify({ url: r, parse: 0 });
            r = r || {};
            return JSON.stringify({ url: str(r.url), parse: Number(r.parse || 0) });
        }
        return JSON.stringify({ url: str(id), parse: 0 });
    } catch (e) { return errJson(e); }
};

})();
