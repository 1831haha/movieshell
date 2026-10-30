/* drpy 规则执行垫片（movieshell V8 引擎用）。
 * 在 androidx.javascriptengine 的 V8 isolate 里 evaluate，本文件 + 规则源码按序执行。
 * v0.113.0 A1–A5：真 async 化——request()/fetch()/post()/pdfh()/pdfa() 全部返回
 * Promise（V8 无 DOM、JS 无法同步调宿主，宿主能力一律经 MessagePort RPC 往返，
 * 宿主网络请求在 isolate 之外异步执行，JS 线程零阻塞）。规则侧需 await 这些调用。
 * 本地可同步完成的保留同步：md5/base64Decode 由 host.js 纯 JS 实现；
 * gzip 解压、HTML 查询（jsoup）、HTTP 走 __hrpc。
 * 规则侧只用到：全局 request(url, options)（Promise<string>，drpy 语义）、setResult(d)、
 * this.input / this.HOST / this.KEY / this.MY_PAGE（函数形态）或裸变量 input/KEY…
 *（"js:" 字符串形态），以及导出的 __drpyHome/__drpyCategory/__drpyDetail/
 * __drpySearch/__drpyPlay/__drpyFilters（全部返回 JSON 字符串的 Promise）。
 * 筛选支持（drpy2 语义）：rule.filter_url 里的 {{fl.键名}} 占位符按筛选值渲染后拼进
 * url 的 fyfilter 处（无 fyfilter 标记时追加到 url 末尾）；gzip+base64 的 filter 字符串自动解开。
 * 已知不支持：rule.lazy 返回非对象时的复杂嗅探、jsDelivr 远程依赖。
 * HTML 解析：pdfh/pdfa 经 __hrpc{kind:'query'}（Kotlin 侧 jsoup），支持 drpy && 链式、
 * :eq(n)/:gt(n)/:lt(n) 等伪类（jsoup 原生支持，直接透传选择器）。
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

/* v0.253.0：DS 别名（只补键、不改旧键值）。TID/PG/ID 是 shim 旧键，
 * MY_CATE/MY_PAGE/orId 是部分规则函数解构时要的键；目标键已在 extra 里
 * 时以调用方为准，不覆盖。MY_FL 由分类入口用 buildFilterMap 拼好后显式
 * 传入，这里不从原始 filter 猜——原始 filter 只是用户选中的几项，缺默认
 * 值时直接当 MY_FL 会丢掉 filter_def 的平台默认值。 */
function makeRuleCtx(rule, extra) {
    var ctx = makeCtx(rule, extra);
    var e = extra || {};
    if (e.TID != null && ctx.MY_CATE == null) ctx.MY_CATE = e.TID;
    if (e.PG != null && ctx.MY_PAGE == null) ctx.MY_PAGE = e.PG;
    if (e.ID != null && ctx.orId == null) ctx.orId = e.ID;
    return ctx;
}

/* ---------- drpy 筛选（filter_url / fyfilter / {{fl.键名}}） ---------- */

/** base64(gzip JSON) → JSON 字符串；gzip 解压交宿主（V8 无 DecompressionStream）。 */
async function ungzipB64(b64) {
    return String(await globalThis.__hGunzipB64(b64));
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

/* ---------- 全局 request：宿主 RPC（真 async，drpy 语义） ----------
 * 宿主 http handler 返回 body 文本；失败/异常 resolve 为 ''（与旧契约一致，不 reject）。 */
globalThis.request = async function (url, options) {
    options = options || {};
    var method = String(options.method || 'GET').toUpperCase();
    var body = options.body == null ? '' : String(options.body);
    var headers = buildHeaders(typeof rule !== 'undefined' ? rule : null, options.headers);
    try {
        var resp = await globalThis.__hrpc({
            kind: 'http',
            url: String(url),
            headers: JSON.stringify(headers),
            method: method,
            body: body,
        });
        return resp == null ? '' : String(resp);
    } catch (e) {
        log('request 失败: ' + url + ' ' + (e && e.message));
        return '';
    }
};

/* drpy2 的 $js.toString(fn)：把函数转成 "js:" 字符串章节。 */
globalThis.$js = {
    toString: function (fn) {
        try { return 'js:' + fn.toString(); } catch (e) { return ''; }
    }
};

// v0.252.0：CryptoJS 子集补全（纯同步 JS，见覆盖表，forge/GCM/RSA/口令密钥不在内）。
(function () {
    'use strict';

    // movieshell CryptoJS subset, pure synchronous JS.
    // Covered: enc Hex/Utf8/Base64/Latin1/Utf16/Utf16LE, lib.WordArray,
    // AES CBC/ECB with Pkcs7/NoPadding, MD5, SHA256, HmacSHA256,
    // format.OpenSSL (Salted__ parse only for WordArray keys).
    // NOT covered: PBKDF2/EvpKDF passphrase keys, AES-GCM, DES/TripleDES,
    // SHA1/SHA512, RSA, forge. A string AES key is rejected on purpose:
    // real CryptoJS would derive a key from it with a salt, and silently
    // treating the string as raw key bytes would produce wrong ciphertext.

    function isWordArray(v) {
        return !!v && Array.isArray(v.words) && typeof v.sigBytes === 'number';
    }

    function bytesFromWordArray(wa) {
        var out = [];
        var i;
        for (i = 0; i < wa.sigBytes; i++) {
            out.push((wa.words[i >>> 2] >>> (24 - (i % 4) * 8)) & 255);
        }
        return out;
    }

    function wordArrayFromBytes(bytes) {
        var words = [];
        var i;
        for (i = 0; i < bytes.length; i++) {
            words[i >>> 2] = (words[i >>> 2] || 0) | ((bytes[i] & 255) << (24 - (i % 4) * 8));
        }
        return new WordArray(words, bytes.length);
    }

    function bytesFromValue(v, what) {
        if (typeof v === 'string') return utf8Encode(v);
        if (isWordArray(v)) return bytesFromWordArray(v);
        if (typeof Uint8Array !== 'undefined' && v instanceof Uint8Array) {
            var out = [];
            var i;
            for (i = 0; i < v.length; i++) out.push(v[i]);
            return out;
        }
        if (Array.isArray(v)) {
            var j;
            for (j = 0; j < v.length; j++) {
                if (typeof v[j] !== 'number' || v[j] < 0 || v[j] > 255 || Math.floor(v[j]) !== v[j]) {
                    throw new TypeError('Invalid byte array for ' + what);
                }
            }
            return v.slice();
        }
        throw new TypeError('Unsupported input for ' + what);
    }

    function keyBytesFromValue(v) {
        if (typeof v === 'string') {
            throw new Error('CryptoJS AES string passphrase keys are not supported; pass a WordArray key (Hex.parse/Utf8.parse)');
        }
        return bytesFromValue(v, 'key');
    }

    // ---------- UTF-8 (manual, no TextEncoder/TextDecoder) ----------
    function utf8Encode(s) {
        s = String(s == null ? '' : s);
        var out = [];
        var i;
        for (i = 0; i < s.length; i++) {
            var c = s.charCodeAt(i);
            if (c < 0x80) {
                out.push(c);
            } else if (c < 0x800) {
                out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
            } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
                var c2 = s.charCodeAt(i + 1);
                if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                    var cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                    out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
                    i += 1;
                } else {
                    out.push(0xEF, 0xBF, 0xBD);
                }
            } else if (c >= 0xDC00 && c <= 0xDFFF) {
                out.push(0xEF, 0xBF, 0xBD);
            } else {
                out.push(0xE0 | (c >> 12), 0x80 | ((c >> 6) & 0x3F), 0x80 | (c & 0x3F));
            }
        }
        return out;
    }

    function utf8Decode(bytes) {
        var out = '';
        var invalid = false;
        var i = 0;
        while (i < bytes.length) {
            var b = bytes[i];
            i += 1;
            var cp;
            if (b < 0x80) {
                cp = b;
            } else if ((b & 0xE0) === 0xC0 && i < bytes.length && (bytes[i] & 0xC0) === 0x80) {
                cp = ((b & 0x1F) << 6) | (bytes[i] & 0x3F);
                i += 1;
                if (cp < 0x80) { cp = 0xFFFD; invalid = true; }
            } else if ((b & 0xF0) === 0xE0 && i + 1 < bytes.length && (bytes[i] & 0xC0) === 0x80 && (bytes[i + 1] & 0xC0) === 0x80) {
                cp = ((b & 0x0F) << 12) | ((bytes[i] & 0x3F) << 6) | (bytes[i + 1] & 0x3F);
                i += 2;
                if (cp < 0x800) { cp = 0xFFFD; invalid = true; }
            } else if ((b & 0xF8) === 0xF0 && i + 2 < bytes.length && (bytes[i] & 0xC0) === 0x80 && (bytes[i + 1] & 0xC0) === 0x80 && (bytes[i + 2] & 0xC0) === 0x80) {
                cp = ((b & 0x07) << 18) | ((bytes[i] & 0x3F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
                i += 3;
                if (cp < 0x10000 || cp > 0x10FFFF) { cp = 0xFFFD; invalid = true; }
            } else {
                cp = 0xFFFD;
                invalid = true;
            }
            if (cp > 0xFFFF) {
                cp -= 0x10000;
                out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
            } else {
                out += String.fromCharCode(cp);
            }
        }
        return { str: out, invalid: invalid };
    }

    // ---------- Base64 ----------
    var B64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function b64Encode(bytes) {
        var out = '';
        var i;
        for (i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i];
            var b1 = i + 1 < bytes.length ? bytes[i + 1] : 0;
            var b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            out += B64_ALPHABET.charAt(b0 >> 2);
            out += B64_ALPHABET.charAt(((b0 & 3) << 4) | (b1 >> 4));
            out += i + 1 < bytes.length ? B64_ALPHABET.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=';
            out += i + 2 < bytes.length ? B64_ALPHABET.charAt(b2 & 63) : '=';
        }
        return out;
    }
    function b64Decode(s) {
        var clean = String(s == null ? '' : s).replace(/\s/g, '').replace(/-/g, '+').replace(/_/g, '/');
        if (clean.length % 4 === 1) throw new Error('Invalid Base64 length');
        var rev = {};
        var i;
        for (i = 0; i < B64_ALPHABET.length; i++) rev[B64_ALPHABET.charAt(i)] = i;
        var out = [];
        for (i = 0; i < clean.length; i += 4) {
            var c0 = clean.charAt(i);
            var c1 = clean.charAt(i + 1);
            var c2 = clean.charAt(i + 2);
            var c3 = clean.charAt(i + 3);
            if (!(c0 in rev) || !(c1 in rev)) throw new Error('Invalid Base64 character');
            var n = (rev[c0] << 18) | (rev[c1] << 12) | (c2 === '=' ? 0 : (rev[c2] << 6)) | (c3 === '=' ? 0 : rev[c3]);
            if (c2 !== '=' && !(c2 in rev)) throw new Error('Invalid Base64 character');
            if (c3 !== '=' && !(c3 in rev)) throw new Error('Invalid Base64 character');
            out.push((n >> 16) & 255);
            if (c2 !== '=') out.push((n >> 8) & 255);
            if (c3 !== '=') out.push(n & 255);
        }
        return out;
    }

    function hexEncode(bytes) {
        var out = '';
        var i;
        for (i = 0; i < bytes.length; i++) {
            var h = bytes[i].toString(16);
            out += h.length === 1 ? '0' + h : h;
        }
        return out;
    }
    function hexDecode(s) {
        s = String(s == null ? '' : s);
        if (s.length % 2 !== 0) throw new Error('Hex parse: odd length');
        if (/[^0-9a-fA-F]/.test(s)) throw new Error('Hex parse: invalid character');
        var out = [];
        var i;
        for (i = 0; i < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
        return out;
    }

    // ---------- WordArray ----------
    function WordArray(words, sigBytes) {
        this.words = words ? words.slice() : [];
        this.sigBytes = sigBytes != null ? sigBytes : this.words.length * 4;
        this.clamp();
    }
    WordArray.prototype.clamp = function () {
        var words = this.words;
        var sigBytes = this.sigBytes;
        words.length = Math.ceil(sigBytes / 4);
        if (sigBytes % 4 !== 0) {
            words[words.length - 1] &= 0xFFFFFFFF << (32 - (sigBytes % 4) * 8);
        }
        return this;
    };
    WordArray.prototype.clone = function () {
        return new WordArray(this.words.slice(), this.sigBytes);
    };
    WordArray.prototype.concat = function (other) {
        var bytes = bytesFromWordArray(this).concat(bytesFromValue(other, 'concat'));
        var wa = wordArrayFromBytes(bytes);
        this.words = wa.words;
        this.sigBytes = wa.sigBytes;
        return this;
    };
    WordArray.prototype.toString = function (encoder) {
        return (encoder || EncHex).stringify(this);
    };
    WordArray.create = function (value, sigBytes) {
        if (value == null) return new WordArray([], 0);
        if (isWordArray(value)) return value.clone();
        if (typeof Uint8Array !== 'undefined' && value instanceof Uint8Array) {
            return wordArrayFromBytes(bytesFromValue(value, 'WordArray.create'));
        }
        if (Array.isArray(value)) {
            if (sigBytes != null) return new WordArray(value.slice(), sigBytes);
            var allBytes = true;
            var i;
            for (i = 0; i < value.length; i++) {
                if (typeof value[i] !== 'number' || value[i] < 0 || value[i] > 255 || Math.floor(value[i]) !== value[i]) { allBytes = false; break; }
            }
            if (allBytes) return wordArrayFromBytes(value.slice());
            return new WordArray(value.slice(), value.length * 4);
        }
        if (typeof value === 'string') return EncUtf8.parse(value);
        throw new TypeError('Unsupported WordArray.create input');
    };

    // ---------- Encoders ----------
    var EncHex = {
        parse: function (s) { return wordArrayFromBytes(hexDecode(s)); },
        stringify: function (wa) { return hexEncode(bytesFromWordArray(wa)); }
    };
    var EncUtf8 = {
        parse: function (s) { return wordArrayFromBytes(utf8Encode(s)); },
        stringify: function (wa) { return utf8Decode(bytesFromWordArray(wa)).str; }
    };
    var EncBase64 = {
        parse: function (s) { return wordArrayFromBytes(b64Decode(s)); },
        stringify: function (wa) { return b64Encode(bytesFromWordArray(wa)); }
    };
    var EncLatin1 = {
        parse: function (s) {
            s = String(s == null ? '' : s);
            var out = [];
            var i;
            for (i = 0; i < s.length; i++) out.push(s.charCodeAt(i) & 255);
            return wordArrayFromBytes(out);
        },
        stringify: function (wa) {
            var bytes = bytesFromWordArray(wa);
            var out = '';
            var i;
            for (i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
            return out;
        }
    };
    function makeUtf16Encoder(littleEndian) {
        return {
            parse: function (s) {
                s = String(s == null ? '' : s);
                var out = [];
                var i;
                for (i = 0; i < s.length; i++) {
                    var c = s.charCodeAt(i);
                    if (littleEndian) { out.push(c & 255, (c >> 8) & 255); } else { out.push((c >> 8) & 255, c & 255); }
                }
                return wordArrayFromBytes(out);
            },
            stringify: function (wa) {
                var bytes = bytesFromWordArray(wa);
                var out = '';
                var i;
                for (i = 0; i + 1 < bytes.length; i += 2) {
                    out += String.fromCharCode(littleEndian ? (bytes[i] | (bytes[i + 1] << 8)) : ((bytes[i] << 8) | bytes[i + 1]));
                }
                return out;
            }
        };
    }
    var EncUtf16 = makeUtf16Encoder(false);
    var EncUtf16LE = makeUtf16Encoder(true);

    // ---------- MD5 ----------
    var MD5_SHIFT = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
    function md5Bytes(inputBytes) {
        var bytes = inputBytes.slice();
        var bitLen = bytes.length * 8;
        bytes.push(0x80);
        while ((bytes.length % 64) !== 56) bytes.push(0);
        var lo = bitLen >>> 0;
        var hi = Math.floor(bitLen / 4294967296) >>> 0;
        for (var i = 0; i < 4; i++) bytes.push((lo >>> (i * 8)) & 255);
        for (var j = 0; j < 4; j++) bytes.push((hi >>> (j * 8)) & 255);
        function rotl(x, s) { return ((x << s) | (x >>> (32 - s))) | 0; }
        function add() {
            var s = 0;
            for (var k = 0; k < arguments.length; k++) s = (s + (arguments[k] | 0)) | 0;
            return s;
        }
        var a0 = 0x67452301, b0 = 0xefcdab89, c0 = 0x98badcfe, d0 = 0x10325476;
        for (var off = 0; off < bytes.length; off += 64) {
            var m = [];
            for (var t = 0; t < 16; t++) {
                m[t] = (bytes[off + t * 4]) | (bytes[off + t * 4 + 1] << 8) | (bytes[off + t * 4 + 2] << 16) | (bytes[off + t * 4 + 3] << 24);
            }
            var a = a0, b = b0, c = c0, d = d0;
            for (var round = 0; round < 64; round++) {
                var f, g;
                if (round < 16) { f = (b & c) | (~b & d); g = round; }
                else if (round < 32) { f = (d & b) | (~d & c); g = (5 * round + 1) % 16; }
                else if (round < 48) { f = b ^ c ^ d; g = (3 * round + 5) % 16; }
                else { f = c ^ (b | ~d); g = (7 * round) % 16; }
                var k = Math.floor(Math.abs(Math.sin(round + 1)) * 4294967296) | 0;
                var tmp = d;
                d = c;
                c = b;
                b = add(b, rotl(add(a, f, k, m[g]), MD5_SHIFT[round]));
                a = tmp;
            }
            a0 = add(a0, a); b0 = add(b0, b); c0 = add(c0, c); d0 = add(d0, d);
        }
        var out = [];
        [a0, b0, c0, d0].forEach(function (w) {
            for (var s = 0; s < 4; s++) out.push((w >>> (s * 8)) & 255);
        });
        return out;
    }

    // ---------- SHA-256 ----------
    var SHA256_K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    function rotr(x, n) { return ((x >>> n) | (x << (32 - n))) >>> 0; }
    function sha256Bytes(inputBytes) {
        var bytes = inputBytes.slice();
        var bitLen = bytes.length * 8;
        bytes.push(0x80);
        while ((bytes.length % 64) !== 56) bytes.push(0);
        var hi = Math.floor(bitLen / 4294967296) >>> 0;
        var lo = bitLen >>> 0;
        for (var i = 7; i >= 0; i--) {
            bytes.push(i >= 4 ? ((hi >>> ((i - 4) * 8)) & 255) : ((lo >>> (i * 8)) & 255));
        }
        var h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
        function addN() {
            var s = 0;
            for (var k = 0; k < arguments.length; k++) s = (s + (arguments[k] >>> 0)) >>> 0;
            return s;
        }
        for (var off = 0; off < bytes.length; off += 64) {
            var w = [];
            for (var t = 0; t < 16; t++) {
                w[t] = ((bytes[off + t * 4] << 24) | (bytes[off + t * 4 + 1] << 16) | (bytes[off + t * 4 + 2] << 8) | bytes[off + t * 4 + 3]) >>> 0;
            }
            for (var u = 16; u < 64; u++) {
                var s0 = (rotr(w[u - 15], 7) ^ rotr(w[u - 15], 18) ^ (w[u - 15] >>> 3)) >>> 0;
                var s1 = (rotr(w[u - 2], 17) ^ rotr(w[u - 2], 19) ^ (w[u - 2] >>> 10)) >>> 0;
                w[u] = addN(w[u - 16], s0, w[u - 7], s1);
            }
            var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7];
            for (var r = 0; r < 64; r++) {
                var S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
                var ch = ((e & f) ^ (~e & g)) >>> 0;
                var temp1 = addN(hh, S1, ch, SHA256_K[r], w[r]);
                var S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
                var maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
                var temp2 = addN(S0, maj);
                hh = g; g = f; f = e; e = addN(d, temp1); d = c; c = b; b = a; a = addN(temp1, temp2);
            }
            h[0] = addN(h[0], a); h[1] = addN(h[1], b); h[2] = addN(h[2], c); h[3] = addN(h[3], d);
            h[4] = addN(h[4], e); h[5] = addN(h[5], f); h[6] = addN(h[6], g); h[7] = addN(h[7], hh);
        }
        var out = [];
        h.forEach(function (word) {
            for (var s = 3; s >= 0; s--) out.push((word >>> (s * 8)) & 255);
        });
        return out;
    }

    // ---------- AES ----------
    var AES_SBOX = [];
    var AES_INV_SBOX = [];
    (function buildAesTables() {
        function gfMul(a, b) {
            var p = 0;
            var aa = a, bb = b;
            for (var i = 0; i < 8; i++) {
                if (bb & 1) p ^= aa;
                var hi = aa & 0x80;
                aa = (aa << 1) & 255;
                if (hi) aa ^= 0x1B;
                bb >>= 1;
            }
            return p;
        }
        function gfPow(a, e) {
            var r = 1;
            while (e > 0) {
                if (e & 1) r = gfMul(r, a);
                a = gfMul(a, a);
                e >>= 1;
            }
            return r;
        }
        function rotl8(x, n) { return ((x << n) | (x >>> (8 - n))) & 255; }
        for (var i = 0; i < 256; i++) {
            var inv = i === 0 ? 0 : gfPow(i, 254);
            var s = inv ^ rotl8(inv, 1) ^ rotl8(inv, 2) ^ rotl8(inv, 3) ^ rotl8(inv, 4) ^ 0x63;
            AES_SBOX[i] = s & 255;
            AES_INV_SBOX[s & 255] = i;
        }
    })();
    function aesGfMul(a, b) {
        var p = 0;
        var aa = a, bb = b;
        for (var i = 0; i < 8; i++) {
            if (bb & 1) p ^= aa;
            var hi = aa & 0x80;
            aa = (aa << 1) & 255;
            if (hi) aa ^= 0x1B;
            bb >>= 1;
        }
        return p;
    }
    function aesExpandKey(keyBytes) {
        var nk = keyBytes.length / 4;
        if (nk !== 4 && nk !== 6 && nk !== 8) throw new Error('AES key must be 16, 24 or 32 bytes');
        var nr = nk + 6;
        var rcon = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36];
        var w = [];
        var i;
        for (i = 0; i < nk; i++) {
            w[i] = ((keyBytes[i * 4] << 24) | (keyBytes[i * 4 + 1] << 16) | (keyBytes[i * 4 + 2] << 8) | keyBytes[i * 4 + 3]) >>> 0;
        }
        for (i = nk; i < 4 * (nr + 1); i++) {
            var temp = w[i - 1];
            if (i % nk === 0) {
                temp = ((AES_SBOX[(temp >>> 16) & 255] << 24) | (AES_SBOX[(temp >>> 8) & 255] << 16) | (AES_SBOX[temp & 255] << 8) | AES_SBOX[(temp >>> 24) & 255]) >>> 0;
                temp ^= (rcon[i / nk - 1] << 24) >>> 0;
            } else if (nk > 6 && i % nk === 4) {
                temp = ((AES_SBOX[(temp >>> 24) & 255] << 24) | (AES_SBOX[(temp >>> 16) & 255] << 16) | (AES_SBOX[(temp >>> 8) & 255] << 8) | AES_SBOX[temp & 255]) >>> 0;
            }
            w[i] = (w[i - nk] ^ temp) >>> 0;
        }
        return { w: w, nr: nr };
    }
    function aesAddRoundKey(state, w, round) {
        for (var c = 0; c < 4; c++) {
            var word = w[round * 4 + c];
            for (var r = 0; r < 4; r++) {
                state[r + 4 * c] ^= (word >>> (24 - r * 8)) & 255;
            }
        }
    }
    function aesSubBytes(state, inv) {
        var box = inv ? AES_INV_SBOX : AES_SBOX;
        for (var i = 0; i < 16; i++) state[i] = box[state[i]];
    }
    function aesShiftRows(state, inv) {
        var old = state.slice();
        for (var r = 0; r < 4; r++) {
            for (var c = 0; c < 4; c++) {
                var src = inv ? ((c - r + 4) % 4) : ((c + r) % 4);
                state[r + 4 * c] = old[r + 4 * src];
            }
        }
    }
    function aesMixColumns(state, inv) {
        for (var c = 0; c < 4; c++) {
            var a0 = state[4 * c], a1 = state[4 * c + 1], a2 = state[4 * c + 2], a3 = state[4 * c + 3];
            if (!inv) {
                state[4 * c] = aesGfMul(a0, 2) ^ aesGfMul(a1, 3) ^ a2 ^ a3;
                state[4 * c + 1] = a0 ^ aesGfMul(a1, 2) ^ aesGfMul(a2, 3) ^ a3;
                state[4 * c + 2] = a0 ^ a1 ^ aesGfMul(a2, 2) ^ aesGfMul(a3, 3);
                state[4 * c + 3] = aesGfMul(a0, 3) ^ a1 ^ a2 ^ aesGfMul(a3, 2);
            } else {
                state[4 * c] = aesGfMul(a0, 14) ^ aesGfMul(a1, 11) ^ aesGfMul(a2, 13) ^ aesGfMul(a3, 9);
                state[4 * c + 1] = aesGfMul(a0, 9) ^ aesGfMul(a1, 14) ^ aesGfMul(a2, 11) ^ aesGfMul(a3, 13);
                state[4 * c + 2] = aesGfMul(a0, 13) ^ aesGfMul(a1, 9) ^ aesGfMul(a2, 14) ^ aesGfMul(a3, 11);
                state[4 * c + 3] = aesGfMul(a0, 11) ^ aesGfMul(a1, 13) ^ aesGfMul(a2, 9) ^ aesGfMul(a3, 14);
            }
        }
    }
    function aesEncryptBlock(block, expanded) {
        var state = block.slice();
        aesAddRoundKey(state, expanded.w, 0);
        for (var round = 1; round < expanded.nr; round++) {
            aesSubBytes(state, false);
            aesShiftRows(state, false);
            aesMixColumns(state, false);
            aesAddRoundKey(state, expanded.w, round);
        }
        aesSubBytes(state, false);
        aesShiftRows(state, false);
        aesAddRoundKey(state, expanded.w, expanded.nr);
        return state;
    }
    function aesDecryptBlock(block, expanded) {
        var state = block.slice();
        aesAddRoundKey(state, expanded.w, expanded.nr);
        for (var round = expanded.nr - 1; round >= 1; round--) {
            aesShiftRows(state, true);
            aesSubBytes(state, true);
            aesAddRoundKey(state, expanded.w, round);
            aesMixColumns(state, true);
        }
        aesShiftRows(state, true);
        aesSubBytes(state, true);
        aesAddRoundKey(state, expanded.w, 0);
        return state;
    }
    function aesPad(bytes) {
        var padLen = 16 - (bytes.length % 16);
        var out = bytes.slice();
        for (var i = 0; i < padLen; i++) out.push(padLen);
        return out;
    }
    function aesUnpad(bytes) {
        if (bytes.length === 0 || bytes.length % 16 !== 0) throw new Error('AES padding validation failed');
        var pad = bytes[bytes.length - 1];
        if (pad < 1 || pad > 16) throw new Error('AES padding validation failed');
        for (var i = 1; i <= pad; i++) {
            if (bytes[bytes.length - i] !== pad) throw new Error('AES padding validation failed');
        }
        return bytes.slice(0, bytes.length - pad);
    }
    function aesCryptBytes(inputBytes, keyBytes, ivBytes, mode, padding, encrypt) {
        var expanded = aesExpandKey(keyBytes);
        var data = encrypt ? (padding === 'NoPadding' ? inputBytes.slice() : aesPad(inputBytes)) : inputBytes.slice();
        if (data.length % 16 !== 0) throw new Error('AES input length must be a multiple of 16');
        var out = [];
        var prev = ivBytes ? ivBytes.slice() : null;
        for (var off = 0; off < data.length; off += 16) {
            var block = data.slice(off, off + 16);
            if (mode === 'CBC') {
                if (encrypt) {
                    for (var i = 0; i < 16; i++) block[i] ^= prev[i];
                    var enc = aesEncryptBlock(block, expanded);
                    out = out.concat(enc);
                    prev = enc;
                } else {
                    var dec = aesDecryptBlock(block, expanded);
                    for (var j = 0; j < 16; j++) dec[j] ^= prev[j];
                    out = out.concat(dec);
                    prev = block;
                }
            } else {
                out = out.concat(encrypt ? aesEncryptBlock(block, expanded) : aesDecryptBlock(block, expanded));
            }
        }
        if (!encrypt && padding === 'Pkcs7') return aesUnpad(out);
        return out;
    }
    function normalizeAesOptions(cfg) {
        cfg = cfg || {};
        var mode = 'CBC';
        if (cfg.mode === ModeECB) mode = 'ECB';
        else if (cfg.mode === ModeCBC || cfg.mode == null) mode = 'CBC';
        else throw new Error('Unsupported AES mode');
        var padding = 'Pkcs7';
        if (cfg.padding === PadPkcs7 || cfg.padding == null) padding = 'Pkcs7';
        else if (cfg.padding === PadNoPadding) padding = 'NoPadding';
        else throw new Error('Unsupported AES padding');
        var iv = null;
        if (mode === 'CBC') {
            if (cfg.iv == null) throw new Error('AES-CBC requires an IV');
            iv = bytesFromValue(cfg.iv, 'iv');
            if (iv.length !== 16) throw new Error('AES IV must be 16 bytes');
        }
        return { mode: mode, padding: padding, iv: iv };
    }
    function makeCipherParams(ciphertextWa, keyWa, ivWa) {
        return {
            ciphertext: ciphertextWa,
            key: keyWa,
            iv: ivWa,
            salt: undefined,
            toString: function (formatter) {
                if (formatter && typeof formatter.stringify === 'function') return formatter.stringify(this.ciphertext);
                return EncBase64.stringify(this.ciphertext);
            }
        };
    }
    function aesEncrypt(message, key, cfg) {
        var msgBytes = bytesFromValue(message, 'AES message');
        var keyB = keyBytesFromValue(key);
        var opt = normalizeAesOptions(cfg);
        var out = aesCryptBytes(msgBytes, keyB, opt.iv, opt.mode, opt.padding, true);
        var keyWa = isWordArray(key) ? key.clone() : wordArrayFromBytes(keyB);
        var ivWa = opt.iv ? wordArrayFromBytes(opt.iv) : undefined;
        return makeCipherParams(wordArrayFromBytes(out), keyWa, ivWa);
    }
    function aesDecrypt(ciphertextInput, key, cfg) {
        var cipherBytes;
        if (typeof ciphertextInput === 'string') {
            var raw = b64Decode(ciphertextInput);
            if (raw.length >= 8 && raw[0] === 0x53 && raw[1] === 0x61 && raw[2] === 0x6C && raw[3] === 0x74 && raw[4] === 0x65 && raw[5] === 0x64 && raw[6] === 0x5F && raw[7] === 0x5F) {
                if (raw.length < 16) throw new Error('AES ciphertext too short');
                cipherBytes = raw.slice(16);
            } else {
                cipherBytes = raw;
            }
        } else if (ciphertextInput && ciphertextInput.ciphertext) {
            cipherBytes = bytesFromValue(ciphertextInput.ciphertext, 'AES ciphertext');
        } else {
            cipherBytes = bytesFromValue(ciphertextInput, 'AES ciphertext');
        }
        var keyB = keyBytesFromValue(key);
        var opt = normalizeAesOptions(cfg);
        var out = aesCryptBytes(cipherBytes, keyB, opt.iv, opt.mode, opt.padding, false);
        return wordArrayFromBytes(out);
    }

    var ModeCBC = { name: 'CBC' };
    var ModeECB = { name: 'ECB' };
    var PadPkcs7 = { name: 'Pkcs7' };
    var PadNoPadding = { name: 'NoPadding' };

    function sha256Public(message) {
        return wordArrayFromBytes(sha256Bytes(bytesFromValue(message, 'SHA256 message')));
    }
    function md5Public(message) {
        return wordArrayFromBytes(md5Bytes(bytesFromValue(message, 'MD5 message')));
    }
    function hmacSha256(message, key) {
        var msgBytes = bytesFromValue(message, 'HMAC message');
        var keyB = bytesFromValue(key, 'HMAC key');
        if (keyB.length > 64) keyB = sha256Bytes(keyB);
        while (keyB.length < 64) keyB.push(0);
        var ipad = [], opad = [];
        for (var i = 0; i < 64; i++) { ipad.push(keyB[i] ^ 0x36); opad.push(keyB[i] ^ 0x5C); }
        var inner = sha256Bytes(ipad.concat(msgBytes));
        return wordArrayFromBytes(sha256Bytes(opad.concat(inner)));
    }

    var CryptoJS = {
        lib: {
            WordArray: WordArray,
            CipherParams: {
                create: function (params) {
                    params = params || {};
                    return makeCipherParams(params.ciphertext || wordArrayFromBytes([]), params.key, params.iv);
                }
            }
        },
        enc: {
            Hex: EncHex,
            Utf8: EncUtf8,
            Base64: EncBase64,
            Latin1: EncLatin1,
            Utf16: EncUtf16,
            Utf16LE: EncUtf16LE
        },
        mode: { CBC: ModeCBC, ECB: ModeECB },
        pad: { Pkcs7: PadPkcs7, NoPadding: PadNoPadding },
        AES: { encrypt: aesEncrypt, decrypt: aesDecrypt },
        MD5: md5Public,
        SHA256: sha256Public,
        HmacSHA256: hmacSha256,
        format: {
            OpenSSL: {
                parse: function (s) {
                    var raw = b64Decode(s);
                    if (raw.length >= 16 && raw[0] === 0x53 && raw[1] === 0x61 && raw[2] === 0x6C && raw[3] === 0x74 && raw[4] === 0x65 && raw[5] === 0x64 && raw[6] === 0x5F && raw[7] === 0x5F) {
                        return { ciphertext: wordArrayFromBytes(raw.slice(16)), salt: wordArrayFromBytes(raw.slice(8, 16)) };
                    }
                    return { ciphertext: wordArrayFromBytes(raw), salt: undefined };
                },
                stringify: function (cipherParams) {
                    var ct = cipherParams.ciphertext || cipherParams;
                    if (cipherParams.salt) {
                        var salted = [0x53, 0x61, 0x6C, 0x74, 0x65, 0x64, 0x5F, 0x5F].concat(bytesFromValue(cipherParams.salt, 'salt'), bytesFromValue(ct, 'ciphertext'));
                        return b64Encode(salted);
                    }
                    return EncBase64.stringify(isWordArray(ct) ? ct : wordArrayFromBytes(bytesFromValue(ct, 'ciphertext')));
                }
            }
        }
    };

    globalThis.CryptoJS = CryptoJS;

    // Only-if-missing polyfills for rules that use these in function bodies.
    // They do not overwrite a native implementation when one exists.
    if (typeof globalThis.atob === 'undefined') {
        globalThis.atob = function (s) {
            var bytes = b64Decode(String(s));
            var out = '';
            for (var i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
            return out;
        };
    }
    if (typeof globalThis.btoa === 'undefined') {
        globalThis.btoa = function (s) {
            s = String(s);
            var bytes = [];
            for (var i = 0; i < s.length; i++) {
                var c = s.charCodeAt(i);
                if (c > 255) throw new Error('btoa: string contains non-Latin1 character');
                bytes.push(c);
            }
            return b64Encode(bytes);
        };
    }
    if (typeof globalThis.TextDecoder === 'undefined') {
        globalThis.TextDecoder = function (encoding) {
            var enc = encoding == null ? 'utf-8' : String(encoding).toLowerCase().replace('_', '-');
            if (enc !== 'utf-8' && enc !== 'utf8') throw new RangeError('TextDecoder: only utf-8 is supported by this polyfill');
            this.encoding = 'utf-8';
            this.fatal = false;
            this.ignoreBOM = false;
        };
        globalThis.TextDecoder.prototype.decode = function (input) {
            var bytes;
            if (input == null) bytes = [];
            else if (typeof Uint8Array !== 'undefined' && input instanceof Uint8Array) bytes = bytesFromValue(input, 'TextDecoder input');
            else if (input instanceof ArrayBuffer) bytes = bytesFromValue(new Uint8Array(input), 'TextDecoder input');
            else if (Array.isArray(input)) bytes = input.slice();
            else bytes = utf8Encode(String(input));
            var r = utf8Decode(bytes);
            if (this.fatal && r.invalid) throw new TypeError('TextDecoder: invalid UTF-8');
            return r.str;
        };
    }
    if (typeof globalThis.TextEncoder === 'undefined') {
        globalThis.TextEncoder = function () { this.encoding = 'utf-8'; };
        globalThis.TextEncoder.prototype.encode = function (s) {
            var bytes = utf8Encode(s);
            if (typeof Uint8Array !== 'undefined') return new Uint8Array(bytes);
            return bytes;
        };
    }
})();


/* v0.11.6：最小 muban 模板（drpy 的 模板 机制）。规则用 模板:'mxpro' 时自动合并。 */
globalThis.muban = {
    mxpro: {
        一级: '.module-list;.module-items .module-item;a&&title;img&&data-src;.module-item-text&&Text;a&&href',
        二级: {
            title: 'h1&&Text;.video-info-items&&Text',
            img: '.content__thumb img&&data-original',
            desc: '.video-info-items&&Text',
            content: '.video-info-content&&Text',
            tabs: '.nav-tabs&&li',
            lists: '.module-play-list&&a',
        },
        搜索: '.module-list;.module-items .module-item;a&&title;img&&data-src;.module-item-text&&Text;a&&href',
    },
};

/* v0.11.6：应用 rule.模板——把 muban 模板合并进 rule（rule 自身属性优先）。 */
function applyMuban(r) {
    try {
        if (r && typeof r.模板 === 'string' && globalThis.muban[r.模板]) {
            var t = globalThis.muban[r.模板];
            Object.keys(t).forEach(function (k) {
                if (r[k] === undefined) r[k] = t[k];
            });
        }
    } catch (e) {}
    return r;
}

/* v0.11.3：drpy 常用小函数。 */
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
/** drpy 语义的 async fetch（Promise<string>）。 */
globalThis.fetch = function (url, params) {
    return request(url, params || {});
};
/** drpy 语义的 async post（Promise<string>）。 */
globalThis.post = function (url, params) {
    params = params || {};
    params.method = 'POST';
    return request(url, params);
};

/* ---------- pdfh/pdfa：经 __hrpc{kind:'query'}（jsoup 桥）的实现（drpy && 链式写法） ----------
 * v0.113.0：V8 无 DOM 且无法同步调宿主，pdfh/pdfa 返回 Promise（规则需 await）。
 * 元素对象：{__el: 1, text, html, outer, attrs}；字符串输入直接当 HTML 传。
 * jsoup 原生支持 :eq(n)/:gt(n)/:lt(n)/:first/:last 等伪类，选择器直接透传。 */
async function _q(src, sel) {
    try {
        sel = String(sel);
        if (src && typeof src === 'object' && src.__el) {
            if (sel.trim() === '') return [src];
            var inner = JSON.parse(await globalThis.__hrpc({ kind: 'query', html: String(src.outer), sel: sel }));
            if (!Array.isArray(inner)) return [];
            inner.forEach(function (el) { el.__el = 1; });
            return inner;
        }
        var arr = JSON.parse(await globalThis.__hrpc({ kind: 'query', html: String(src == null ? '' : src), sel: sel }));
        if (!Array.isArray(arr)) return [];
        arr.forEach(function (el) { el.__el = 1; });
        return arr;
    } catch (e) { return []; }
}
/** 把 HTML 字符串或元素对象归一成根元素（对应旧 _asRoot 的 Document 角色）。 */
async function _ctx(src) {
    if (src && typeof src === 'object' && src.__el) return src;
    var list = await _q(src, '');
    return list[0] || null;
}
function _pick(el, op) {
    if (!el) return '';
    if (op === 'Text') return String(el.text || '').trim();
    if (op === 'Html' || op === 'html') return el.html || '';
    var a = el.attrs || {};
    return a[op] != null ? String(a[op]) : '';
}
/** pdfh(html|el, 'a.b&&c&&Text')：链式下钻取文本/属性。Promise<string>。 */
globalThis.pdfh = async function (html, parse) {
    try {
        var parts = String(parse).split('&&');
        var cur = await _ctx(html);
        for (var i = 0; i < parts.length - 1; i++) {
            var one = await _q(cur, parts[i]);
            cur = one[0] || null;
            if (!cur) return '';
        }
        return _pick(cur, parts[parts.length - 1]);
    } catch (e) { return ''; }
};
/** pdfa(html|el, 'a.b&&c&&Text')：链式下钻取数组。Promise<string[]>。 */
globalThis.pdfa = async function (html, parse) {
    try {
        var parts = String(parse).split('&&');
        var root = await _ctx(html);
        var cur = root ? [root] : [];
        for (var i = 0; i < parts.length - 1; i++) {
            var next = [];
            for (var j = 0; j < cur.length; j++) {
                next = next.concat(await _q(cur[j], parts[i]));
            }
            cur = next;
        }
        var last = parts[parts.length - 1];
        return cur.map(function (el) { return _pick(el, last); });
    } catch (e) { return []; }
};

/* v0.11.6：别名挂载（必须在 pdfh/pdfa 定义之后）。 */
globalThis.pd = globalThis.pdfh;
globalThis.jsp = {
    pdfh: globalThis.pdfh,
    pdfa: globalThis.pdfa,
    pd: globalThis.pdfh,
};

/**
 * drpy 列表模板：'容器;条目;标题;图;描述;链接'（6 段）或
 * '条目;标题;图;描述;链接'（5 段，无容器）。
 */
async function runListTemplate(tpl, ctx) {
    var parts = String(tpl).split(';');
    var hasContainer = parts.length >= 6;
    var itemSel = hasContainer ? parts[1] : parts[0];
    var titleSel = hasContainer ? parts[2] : parts[1];
    var imgSel = hasContainer ? parts[3] : parts[2];
    var descSel = hasContainer ? parts[4] : parts[3];
    var urlSel = hasContainer ? parts[5] : parts[4];
    var html = await request(ctx.input);
    var items = [];
    if (hasContainer && parts[0]) {
        var containers = await _q(html, parts[0]);
        for (var i = 0; i < containers.length; i++) {
            items = items.concat(await _q(containers[i], itemSel));
        }
    }
    if (items.length === 0) items = await _q(html, itemSel);
    var out = [];
    for (var k = 0; k < items.length; k++) {
        var el = items[k];
        out.push({
            url: await pdfh(el, urlSel),
            title: await pdfh(el, titleSel),
            img: await pdfh(el, imgSel),
            desc: await pdfh(el, descSel)
        });
    }
    return out;
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
async function parseClassTemplate(tpl, baseUrl) {
    var parts = String(tpl).split(';');
    var html = await request(baseUrl);
    var els = await _q(html, parts[0]);
    var out = [];
    for (var i = 0; i < els.length; i++) {
        var el = els[i];
        var name = await pdfh(el, parts[1] || '');
        var url = await pdfh(el, parts[2] || '');
        var tid = url;
        if (parts[3]) {
            try {
                var m = url.match(new RegExp(parts[3]));
                if (m) tid = m[1] || m[0];
            } catch (e) {}
        }
        out.push({ type_id: str(tid), type_name: str(name) });
    }
    return out.filter(function (c) { return c.type_name; });
}

/** drpy 'json:路径;标题;图;描述;链接' 简写：拉 input 取 JSON 按路径映射列表。 */
async function runJsonTemplate(tpl, ctx) {
    var parts = tpl.slice(5).split(';');
    var json = JSON.parse(await request(ctx.input));
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

/* base64 解码（规则 lazy 常用）：host.js 纯 JS 实现；V8 原生缺 atob/TextDecoder 时由 CryptoJS 一节的 polyfill 补齐（仅缺失时定义）。 */
globalThis.base64Decode = function (s) {
    return globalThis.__hB64Decode(String(s));
};

/* md5（规则 lazy 常用）：host.js 纯 JS 同步实现（替代旧宿主 MessageDigest 桥）。 */
globalThis.md5 = function (s) {
    return globalThis.__hMd5(String(s));
};

/* drpy2 全局数组 d：规则往里 push，框架收集。 */
globalThis.d = [];

async function runSection(section, ctx, args) {
    _sectionResult = undefined;
    globalThis.d = [];
    var r;
    if (typeof section === 'function') {
        /* v0.253.0：函数规则的 this 必须能拿到规则自身字段和方法
         *（一级/二级/预处理/filter_def），否则推荐里 this.一级.call 会死在
         * reading 'call'。只补 ctx 里没有的键，入口参数优先于规则字段；
         * 就地补进 ctx，不新建对象——播放的 lazy 会改写 this.input 成对象，
         * 播放入口事后要读 ctx.input，新对象会丢掉这次改写。字符串模板分支
         * 不走这条，with(ctx) 的裸变量查找范围保持原样，不被规则字段遮蔽。 */
        if (typeof rule !== 'undefined' && rule && typeof rule === 'object') {
            Object.keys(rule).forEach(function (k) {
                if (ctx[k] === undefined) ctx[k] = rule[k];
            });
        }
        /* v0.254.0：函数规则按 DS 语义拿参数，不只拿 this。聚合短剧的 lazy
         * 签名是 (flag, id, flags)，分支全靠参数 flag 做 indexOf；以前这里
         * 只 call(ctx) 不传参，flag 是 undefined，第一行就抛 indexOf 错，
         * 播放入口 catch 成 error、Kotlin 侧读不到 url 就报暂无播放地址。
         * 字符串模板分支不走这条，args 对它无影响；其余入口仍不传参，
         * 它们的规则函数本来就是只读 this 的写法。 */
        r = await section.apply(ctx, args || []);
    } else if (typeof section === 'string') {
        if (section.indexOf('json:') === 0) {
            r = await runJsonTemplate(section, ctx);
        } else if (section === '*') {
            r = undefined; // '*'=走默认，二阶段暂不支持，保持空不崩
        } else if (/^js:/.test(section) || section.indexOf(';') < 0) {
            r = await makeJsRunner(section.replace(/^js:/, ''))(ctx);
        } else {
            r = await runListTemplate(section, ctx); // '容器;条目;标题;图;描述;链接' 列表模板
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
    // v0.11.6：首次使用时应用 muban 模板合并
    if (!rule.__mubanApplied) {
        applyMuban(rule);
        rule.__mubanApplied = true;
    }
}

/* ---------- 导出：5 个入口 ---------- */
globalThis.__drpyHome = async function () {
    try {
        needRule();
        var cls = [];
        var homeInput = absUrl(rule.host, rule.homeUrl || '', {});
        if (rule.class_parse) {
            if (typeof rule.class_parse === 'string' && rule.class_parse.indexOf('js:') !== 0) {
                // v0.11.1：class_parse 字符串模板（'选择器;标题;链接;正则'），在 homeUrl 页面上解析
                cls = (await parseClassTemplate(rule.class_parse, homeInput)).map(normClass);
            } else {
                var cr = await runSection(rule.class_parse, makeRuleCtx(rule));
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
        if (rule.推荐 && rule.推荐 !== '*') {
            var input = homeInput;
            list = toList(await runSection(rule.推荐, makeRuleCtx(rule, { input: input })));
        } else if (rule.推荐 === '*' && rule.一级) {
            // v0.11.3：推荐='*' 表示用默认逻辑（homeUrl 页面按一级模板解析）
            list = toList(await runSection(rule.一级, makeRuleCtx(rule, { input: homeInput })));
        }
        return JSON.stringify({ 'class': cls, list: list });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyCategory = async function (tid, pg, filter) {
    try {
        needRule();
        pg = pg || 1;
        var urlTpl = rule.url || '';
        // v0.253.0：筛选值表先按 drpy2 语义拼好（filter_def 默认值打底＋
        // 分组首选项补齐＋调用方选中值覆盖），既供 filter_url 渲染，也供
        // 一级函数的 MY_FL 用；不拼时 MY_FL 会丢平台默认值。
        var fl = await buildFilterMap(tid, filter);
        // drpy2 语义：有 filter_url 时先渲染它，再拼进 url 的 fyfilter 处；
        // url 里没有 fyfilter 标记时追加到末尾（用 & 连接）。
        if (rule.filter_url) {
            var furl = renderTpl(rule.filter_url, { tid: tid, pg: pg, fl: fl });
            if (/fyfilter/.test(urlTpl)) {
                urlTpl = urlTpl.replace('fyfilter', furl);
            } else {
                if (urlTpl.charAt(urlTpl.length - 1) !== '&' && furl.charAt(0) !== '&') urlTpl += '&';
                urlTpl += furl;
            }
        }
        var input = absUrl(rule.host, urlTpl, { tid: tid, pg: pg, fl: fl });
        var r = await runSection(rule.一级, makeRuleCtx(rule, { input: input, TID: tid, PG: pg, MY_FL: fl }));
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
        // v0.11.9：二级='*'（直播站常见）表示用默认详情：单线路单集，id 直通播放
        if (rule.二级 === '*') {
            return JSON.stringify({
                vod_id: str(id),
                vod_name: str(id),
                vod_pic: '',
                vod_remarks: '',
                vod_play_from: '默认',
                vod_play_url: '直播$' + str(id),
            });
        }
        var input = absUrl(rule.host, rule.detailUrl || '', { id: id });
        var r = await runSection(rule.二级, makeRuleCtx(rule, { input: input, ID: id })) || {};
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
        var r = await runSection(rule.搜索, makeRuleCtx(rule, { input: input, KEY: wd, MY_PAGE: pg }));
        return JSON.stringify({ list: toList(r) });
    } catch (e) { return errJson(e); }
};

globalThis.__drpyPlay = async function (flag, id) {
    try {
        needRule();
        var fn = rule.播放 || rule.lazy;
        if (fn) {
            // drpy 语义：lazy 的 input 是播放条目 id/URL 原值（vod_play_url "$" 右侧原样），
            // 不做 detailUrl 渲染——detailUrl 只在 detail 阶段拼 vod_id 用。
            // 渲染会破坏 m3u8/mp4 直链判断（规则 lazy: input.indexOf('.m3u8')）。
            var ctx = makeRuleCtx(rule, { input: str(id), flag: flag });
            var r = await runSection(fn, ctx, [flag, id]);
            // v0.11.1：lazy 形态直接改写 input 为 {jx,url,parse,header} 对象交结果
            if (r == null && ctx.input && typeof ctx.input === 'object') r = ctx.input;
            if (typeof r === 'string') return JSON.stringify({ url: r, parse: 0 });
            r = r || {};
            // v0.294.7：jx/header/playUrl 一并透传（原来只交 url/parse，宿主拿不到 Referer、
            // 也分不清“走 jx 解析”与“嗅探该页”）；header 可能是 JSON 字符串。
            var hd = r.header || r.headers || null;
            if (typeof hd === 'string') { try { hd = JSON.parse(hd); } catch (e) { hd = null; } }
            if (!hd || typeof hd !== 'object') hd = {};
            return JSON.stringify({
                url: str(r.url), parse: Number(r.parse || 0), jx: Number(r.jx || 0),
                header: hd, playUrl: str(r.playUrl || '')
            });
        }
        // v0.294.7：无 lazy 时按 drpy2 口径交 parse=1（宿主侧路由：直链直接播、
        // VIP 站走 jx、其余嗅探该页，嗅探失败回退原地址直接播）。
        var hd0 = {};
        try { hd0 = buildHeaders(rule, null); } catch (e) { hd0 = {}; }
        return JSON.stringify({ url: str(id), parse: 1, jx: 0, header: hd0 });
    } catch (e) { return errJson(e); }
};

})();
