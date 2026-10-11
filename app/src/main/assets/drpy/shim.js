/* drpy 规则执行垫片（movieshell WebView 引擎用）。
 * 在 WebView 全局作用域 evaluate，本文件 + 规则源码拼在一起执行。
 * 规则侧只用到：全局 request(url, options)、this.input / this.HOST / this.KEY / this.MY_PAGE、
 * 以及导出的 __drpyHome/__drpyCategory/__drpyDetail/__drpySearch/__drpyPlay（全部返回 JSON 字符串）。
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

/* ---------- 模板渲染：fyclass/fypage/fyid/** ---------- */
function renderTpl(tpl, ctx) {
    var u = String(tpl == null ? '' : tpl);
    u = u.split('fyclass').join(encodeURIComponent(ctx.tid == null ? '' : String(ctx.tid)));
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

globalThis.__drpyCategory = async function (tid, pg) {
    try {
        needRule();
        pg = pg || 1;
        var input = absUrl(rule.host, rule.url || '', { tid: tid, pg: pg });
        var r = await rule.一级.call(makeCtx(rule, { input: input, TID: tid, PG: pg }));
        var page = pg;
        if (r && typeof r === 'object' && !Array.isArray(r) && r.page != null) {
            page = Number(r.page) || pg;
        }
        return JSON.stringify({ list: toList(r), page: page });
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
