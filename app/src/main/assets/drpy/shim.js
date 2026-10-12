/* drpy 规则执行垫片（movieshell WebView 引擎用）。
 * 在 WebView 全局作用域 evaluate，本文件 + 规则源码拼在一起执行。
 * 规则侧只用到：全局 request(url, options)（同步，drpy 语义）、setResult(d)、
 * this.input / this.HOST / this.KEY / this.MY_PAGE（函数形态）或裸变量 input/KEY…
 *（"js:" 字符串形态），以及导出的 __drpyHome/__drpyCategory/__drpyDetail/
 * __drpySearch/__drpyPlay/__drpyFilters（全部返回 JSON 字符串）。
 * 筛选支持（drpy2 语义）：rule.filter_url 里的 {{fl.键名}} 占位符按筛选值渲染后拼进
 * url 的 fyfilter 处（无 fyfilter 标记时追加到 url 末尾）；无筛选值时按规则默认值
 *（filter_def[tid]，再取 filter 分组首项）或空字符串渲染；gzip+base64 的 filter 字符串自动解开。
 * 已知不支持：cheerio/pdfa/pdfh 等 HTML 解析、rule.lazy 返回非对象时的复杂嗅探、
 * jsDelivr 远程依赖。
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

/* ---------- 全局 request：转调 Java 桥（同步，drpy 语义） ---------- */
globalThis.request = function (url, options) {
    options = options || {};
    var method = String(options.method || 'GET').toUpperCase();
    var body = options.body == null ? '' : String(options.body);
    var headers = buildHeaders(typeof rule !== 'undefined' ? rule : null, options.headers);
    var resp = globalThis.__bridge.http(String(url), JSON.stringify(headers), method, body);
    return resp == null ? '' : String(resp);
};

/* drpy2 的 $js.toString(fn)：把函数转成 "js:" 字符串章节。 */
globalThis.$js = {
    toString: function (fn) {
        try { return 'js:' + fn.toString(); } catch (e) { return ''; }
    }
};

/* v0.11.3：规则常用的 CryptoJS（目前只用到 MD5）。 */
globalThis.CryptoJS = {
    MD5: function (s) {
        return { toString: function () { try { return md5(String(s)); } catch (e) { return ''; } } };
    }
};

/* v0.11.3：drpy 常用小函数。pd 是 pdfh 的别名。 */
globalThis.pd = globalThis.pdfh;
globalThis.urlencode = function (s) { try { return encodeURIComponent(String(s)); } catch (e) { return String(s); } };
globalThis.stringify = function (o) { try { return JSON.stringify(o); } catch (e) { return String(o); } };
if (typeof globalThis.print !== 'function') {
    globalThis.print = function () { try { log.apply(null, arguments); } catch (e) {} };
}

/* drpy2 宿主全局量（规则裸变量引用）。 */
globalThis.MOBILE_UA = MOBILE_UA;
globalThis.IOS_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
globalThis.rule_fetch_params = { headers: {} };
globalThis.fetch_params = globalThis.rule_fetch_params;
/** drpy 语义的同步 fetch（覆盖 WebView 原生异步 fetch）。 */
globalThis.fetch = function (url, params) {
    return request(url, params || {});
};
/** drpy 语义的同步 post。 */
globalThis.post = function (url, params) {
    params = params || {};
    params.method = 'POST';
    return request(url, params);
};

/* ---------- pdfh/pdfa：基于 DOM 的最小实现（drpy && 链式写法） ---------- */
function _asRoot(html) {
    if (html && html.nodeType) return html;
    return new DOMParser().parseFromString(String(html == null ? '' : html), 'text/html');
}
function _selAll(root, sel) {
    sel = String(sel);
    // jQuery 伪类 :eq(n)/:gt(n)/:lt(n)，按从左到右在当前集合上过滤
    var pseudos = [];
    var base = sel.replace(/:(eq|gt|lt)\((\d+)\)/g, function (m, name, n) {
        pseudos.push({ name: name, n: Number(n) });
        return '';
    }).trim();
    var list;
    try { list = Array.prototype.slice.call(root.querySelectorAll(base || '*')); }
    catch (e) { return []; }
    pseudos.forEach(function (p) {
        if (p.name === 'eq') list = list[p.n] != null ? [list[p.n]] : [];
        else if (p.name === 'gt') list = list.filter(function (_, i) { return i > p.n; });
        else if (p.name === 'lt') list = list.filter(function (_, i) { return i < p.n; });
    });
    return list;
}
function _selOne(root, sel) {
    var all = _selAll(root, sel);
    return all[0] || null;
}
function _pick(el, op) {
    if (!el) return '';
    if (op === 'Text') return (el.textContent || '').trim();
    if (op === 'Html' || op === 'html') return el.innerHTML || '';
    try { return el.getAttribute(op) || ''; } catch (e) { return ''; }
}
/** pdfh(html|el, 'a.b&&c&&Text')：链式下钻取文本/属性。 */
globalThis.pdfh = function (html, parse) {
    try {
        var parts = String(parse).split('&&');
        var cur = _asRoot(html);
        for (var i = 0; i < parts.length - 1; i++) {
            cur = (cur && cur.nodeType ? _selOne(cur, parts[i]) : null);
            if (!cur) return '';
        }
        return _pick(cur, parts[parts.length - 1]);
    } catch (e) { return ''; }
};
/** pdfa(html|el, 'a.b&&c&&Text')：链式下钻取数组。 */
globalThis.pdfa = function (html, parse) {
    try {
        var parts = String(parse).split('&&');
        var cur = [_asRoot(html)];
        for (var i = 0; i < parts.length - 1; i++) {
            var next = [];
            cur.forEach(function (c) {
                if (c && c.nodeType) next = next.concat(_selAll(c, parts[i]));
            });
            cur = next;
        }
        var last = parts[parts.length - 1];
        return cur.map(function (el) { return _pick(el, last); });
    } catch (e) { return []; }
};

/**
 * drpy 列表模板：'容器;条目;标题;图;描述;链接'（6 段）或
 * '条目;标题;图;描述;链接'（5 段，无容器）。
 */
function runListTemplate(tpl, ctx) {
    var parts = String(tpl).split(';');
    var hasContainer = parts.length >= 6;
    var itemSel = hasContainer ? parts[1] : parts[0];
    var titleSel = hasContainer ? parts[2] : parts[1];
    var imgSel = hasContainer ? parts[3] : parts[2];
    var descSel = hasContainer ? parts[4] : parts[3];
    var urlSel = hasContainer ? parts[5] : parts[4];
    var doc = _asRoot(request(ctx.input));
    var items = [];
    if (hasContainer && parts[0]) {
        _selAll(doc, parts[0]).forEach(function (c) {
            items = items.concat(_selAll(c, itemSel));
        });
    }
    if (items.length === 0) items = _selAll(doc, itemSel);
    return items.map(function (el) {
        return {
            url: pdfh(el, urlSel),
            title: pdfh(el, titleSel),
            img: pdfh(el, imgSel),
            desc: pdfh(el, descSel)
        };
    });
}
var _sectionResult;
globalThis.setResult = function (d) { _sectionResult = d; };

/**
 * v0.11.1：规则章节（推荐/一级/二级/搜索/播放）有两种写法：
 * 1) 函数：function(){ ... this.input ... }，this 即 ctx；
 * 2) 字符串："js:..."，裸变量 input/KEY/MY_PAGE… 解析到 ctx，用 setResult(d) 交结果。
 * 两种都兼容；setResult 优先于返回值。
 */
function makeJsRunner(code) {
    var c = String(code).trim();
    // $js.toString(fn) 转出来的是函数表达式，需要调用；普通语句直接求值
    var isFn = /^(async\s+)?(\([^)]*\)|[\w$]+)\s*=>/.test(c) ||
        /^async\s*function/.test(c) || /^function/.test(c);
    if (isFn) {
        return new Function('ctx', 'with(ctx){ return (' + c + ')(); }');
    }
    // new Function 默认非 strict，允许 with；ctx 挂裸变量
    return new Function('ctx', 'with(ctx){\n' + code + '\n}');
}

/** JSON 路径取值：'response.3.docs' 逐级下钻。 */
function getPath(obj, path) {
    var cur = obj;
    String(path || '').split('.').forEach(function (k) {
        if (cur == null) return;
        cur = cur[k];
    });
    return cur;
}

/**
 * drpy class_parse 字符串模板：'列表选择器;标题解析;链接解析;链接正则'
 * 例：'.stui-header__menu li:gt(0):lt(7);a&&Text;a&&href;/(\d+).html'
 */
function parseClassTemplate(tpl, baseUrl) {
    var parts = String(tpl).split(';');
    var doc = _asRoot(request(baseUrl));
    return _selAll(doc, parts[0]).map(function (el) {
        var name = pdfh(el, parts[1] || '');
        var url = pdfh(el, parts[2] || '');
        var tid = url;
        if (parts[3]) {
            try {
                var m = url.match(new RegExp(parts[3]));
                if (m) tid = m[1] || m[0];
            } catch (e) {}
        }
        return { type_id: str(tid), type_name: str(name) };
    }).filter(function (c) { return c.type_name; });
}

/** drpy 'json:路径;标题;图;描述;链接' 简写：拉 input 取 JSON 按路径映射列表。 */
function runJsonTemplate(tpl, ctx) {
    var parts = tpl.slice(5).split(';');
    var json = JSON.parse(request(ctx.input));
    var arr = getPath(json, parts[0]);
    if (!Array.isArray(arr)) return [];
    return arr.map(function (it) {
        it = it || {};
        return {
            url: it[parts[4]],
            title: it[parts[1]],
            img: it[parts[2]],
            desc: it[parts[3]]
        };
    });
}

/* base64 解码（规则 lazy 常用）。 */
globalThis.base64Decode = function (s) {
    try {
        var bin = atob(String(s).replace(/-/g, '+').replace(/_/g, '/'));
        var bytes = new Uint8Array(bin.length);
        for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
        return new TextDecoder().decode(bytes);
    } catch (e) { return ''; }
};

/* md5（规则 lazy 常用）：转调 Java 桥同步计算。 */
globalThis.md5 = function (s) {
    try { return String(globalThis.__bridge.md5(String(s))); }
    catch (e) { return ''; }
};

/* drpy2 全局数组 d：规则往里 push，框架收集。 */
globalThis.d = [];

async function runSection(section, ctx) {
    _sectionResult = undefined;
    globalThis.d = [];
    var r;
    if (typeof section === 'function') {
        r = await section.call(ctx);
    } else if (typeof section === 'string') {
        if (section.indexOf('json:') === 0) {
            r = runJsonTemplate(section, ctx);
        } else if (section === '*') {
            r = undefined; // '*'=走默认，二阶段暂不支持，保持空不崩
        } else if (/^js:/.test(section) || section.indexOf(';') < 0) {
            r = await makeJsRunner(section.replace(/^js:/, ''))(ctx);
        } else {
            r = runListTemplate(section, ctx); // '容器;条目;标题;图;描述;链接' 列表模板
        }
    }
    if (r === undefined && Array.isArray(globalThis.d) && globalThis.d.length > 0) r = globalThis.d;
    return _sectionResult !== undefined ? _sectionResult : r;
}

/* ---------- 归一化 ---------- */
function str(v) { return v == null ? '' : String(v); }

function normVod(v) {
    v = v || {};
    return {
        vod_id: str(v.vod_id || v.url),
        vod_name: str(v.vod_name || v.title),
        vod_pic: str(v.vod_pic || v.img || v.pic),
        vod_remarks: str(v.vod_remarks || v.desc),
        vod_year: str(v.vod_year || v.year),
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
        if (rule.class_parse) {
            if (typeof rule.class_parse === 'string' && rule.class_parse.indexOf('js:') !== 0) {
                // v0.11.1：class_parse 字符串模板（'选择器;标题;链接;正则'）
                cls = parseClassTemplate(rule.class_parse, rule.host).map(normClass);
            } else {
                var cr = await runSection(rule.class_parse, makeCtx(rule));
                var arr = (cr && cr['class']) ? cr['class'] : (Array.isArray(cr) ? cr : []);
                cls = arr.map(normClass);
            }
        } else if (rule.class_name && rule.class_url) {
            var names = String(rule.class_name).split('&');
            var urls = String(rule.class_url).split('&');
            for (var i = 0; i < names.length; i++) {
                cls.push({ type_id: str(urls[i] || names[i]), type_name: str(names[i]) });
            }
        }
        var list = [];
        if (rule.推荐) {
            var input = absUrl(rule.host, rule.homeUrl || '', {});
            list = toList(await runSection(rule.推荐, makeCtx(rule, { input: input })));
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
        var r = await runSection(rule.一级, makeCtx(rule, { input: input, TID: tid, PG: pg }));
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
        var r = await runSection(rule.二级, makeCtx(rule, { input: input, ID: id })) || {};
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
        var r = await runSection(rule.搜索, makeCtx(rule, { input: input, KEY: wd, MY_PAGE: pg }));
        return JSON.stringify({ list: toList(r) });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyPlay = async function (flag, id) {
    try {
        needRule();
        var fn = rule.播放 || rule.lazy;
        if (fn) {
            var pinput = str(id);
            // v0.11.1：lazy 形态的 input 按 drpy 语义用 detailUrl 渲染（含 fyid），
            // 规则内的正则（如 /(\d+)/）才能命中；有独立"播放"章节时保持传裸 id
            if (!rule.播放 && rule.lazy && rule.detailUrl) {
                var rendered = absUrl(rule.host, rule.detailUrl, { id: id });
                if (rendered && rendered !== pinput) pinput = rendered;
            }
            var ctx = makeCtx(rule, { input: pinput, flag: flag });
            var r = await runSection(fn, ctx);
            // v0.11.1：lazy 形态直接改写 input 为 {jx,url,parse,header} 对象交结果
            if (r == null && ctx.input && typeof ctx.input === 'object') r = ctx.input;
            if (typeof r === 'string') return JSON.stringify({ url: r, parse: 0 });
            r = r || {};
            return JSON.stringify({ url: str(r.url), parse: Number(r.parse || 0) });
        }
        return JSON.stringify({ url: str(id), parse: 0 });
    } catch (e) { return errJson(e); }
};

})();
