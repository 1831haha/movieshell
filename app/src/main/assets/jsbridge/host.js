/* movieshell V8 宿主桥基元（drpy 规则垫片与 LX 音乐垫片共用）。
 *
 * v0.113.0 A1–A5：JS 引擎从 app.cash.quickjs（同步 JNI，JSContext 绑死单线程）
 * 换成 androidx.javascriptengine（V8 isolate）。isolate 里的 `await` 是天然事件循环，
 * 但 JS 侧**没有任何同步方式**调用宿主（无 DOM、无 WebView 反射回调），因此宿主能力
 * 一律经 MessagePort 往返：
 *   JS：__hrpc({kind:'http', ...}) → port.postMessage
 *   宿主：OkHttp 异步执行 → port.postMessage({id, ok, value})
 *   JS：resolve → await 续跑
 * 宿主网络请求完全在 isolate 之外执行，JS 主线不再被阻塞（A2 的根因）。
 *
 * 本文件只依赖 ECMAScript 核心 + console + android.getNamedPort。
 * V8 沙盒没有 atob/TextDecoder/Blob/DecompressionStream，故 base64/UTF-8 用纯 JS 实现，
 * gzip 解压交宿主。
 *
 * 载入顺序（引擎负责）：先 eval 本文件，再 eval 各自的 shim。
 */
(function () {
'use strict';

/* ---------- MessagePort：宿主 RPC ---------- */

/* port 名由引擎在 eval host.js 之前注入 globalThis.__PORT_NAME（引擎生成唯一名）。
 * 惰性读取：首次 __hrpc 时才取，不依赖注入时序。 */
var portPromise = null;
var pending = {};
var seq = 0;

function port() {
    if (!portPromise) {
        var name = (typeof globalThis.__PORT_NAME === 'string') ? globalThis.__PORT_NAME : '';
        portPromise = Promise.resolve(android.getNamedPort(name)).then(function (p) {
            p.onmessage = function (ev) { onHostMessage(ev.data); };
            return p;
        });
    }
    return portPromise;
}

function onHostMessage(data) {
    var m;
    try { m = (typeof data === 'string') ? JSON.parse(data) : data; } catch (e) { return; }
    if (!m || m.id == null) return;
    var slot = pending[m.id];
    if (!slot) return;
    delete pending[m.id];
    if (m.ok) slot.resolve(m.value == null ? '' : String(m.value));
    else slot.reject(new Error(m.error ? String(m.error) : 'host rpc failed'));
}

/* ---------- 宿主 → JS：单向事件（如 LX 的 inited 上报回执场景不需要，但 resolve 需要） ---------- */

/**
 * 宿主在 RPC handler 完成后经 evaluateJavaScriptAsync 注入调用：
 * value 以 base64(UTF-8) 传输，避免 JSON 字符串转义进表达式字面量的坑。
 */
globalThis.__hrpcResolve = function (id, ok, valueB64) {
    var slot = pending[id];
    if (!slot) return '';
    delete pending[id];
    if (ok) slot.resolve(globalThis.__hB64Decode(valueB64));
    else slot.reject(new Error(globalThis.__hB64Decode(valueB64) || 'host rpc failed'));
    return '';
};

/** 请求/响应式宿主调用，返回 Promise<string>。payload 需带 kind。 */
globalThis.__hrpc = function (payload) {
    return port().then(function (p) {
        seq += 1;
        var id = seq;
        return new Promise(function (resolve, reject) {
            pending[id] = { resolve: resolve, reject: reject };
            payload.id = id;
            p.postMessage(JSON.stringify(payload));
        });
    });
};

/** 单向事件上报（不等待宿主应答），如 LX 的 send('inited', {...})。 */
globalThis.__hsend = function (kind, value) {
    try {
        port().then(function (p) {
            p.postMessage(JSON.stringify({ kind: kind, value: value }));
        });
    } catch (e) {}
};

/* ---------- UTF-8（纯 JS，替代 TextEncoder/TextDecoder） ---------- */

function utf8Bytes(s) {
    s = String(s == null ? '' : s);
    var out = [];
    for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c < 0x80) {
            out.push(c);
        } else if (c < 0x800) {
            out.push(0xC0 | (c >> 6), 0x80 | (c & 0x3F));
        } else if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length) {
            var c2 = s.charCodeAt(i + 1);
            if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                var cp = 0x10000 + ((c - 0xD800) << 10) + (c2 - 0xDC00);
                out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F),
                         0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
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

function utf8Str(bytes) {
    var out = '';
    var i = 0;
    while (i < bytes.length) {
        var b = bytes[i];
        i += 1;
        var cp;
        if (b < 0x80) {
            cp = b;
        } else if ((b & 0xE0) === 0xC0 && i < bytes.length) {
            cp = ((b & 0x1F) << 6) | (bytes[i] & 0x3F);
            i += 1;
        } else if ((b & 0xF0) === 0xE0 && i + 1 < bytes.length) {
            cp = ((b & 0x0F) << 12) | ((bytes[i] & 0x3F) << 6) | (bytes[i + 1] & 0x3F);
            i += 2;
        } else if ((b & 0xF8) === 0xF0 && i + 2 < bytes.length) {
            cp = ((b & 0x07) << 18) | ((bytes[i] & 0x3F) << 12)
               | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
            i += 3;
        } else {
            cp = 0xFFFD;
        }
        if (cp > 0xFFFF) {
            cp -= 0x10000;
            out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
        } else {
            out += String.fromCharCode(cp);
        }
    }
    return out;
}

/* ---------- base64（纯 JS，替代 atob） ---------- */

var B64_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
var B64_REV = null;

function b64Rev() {
    if (B64_REV) return B64_REV;
    B64_REV = {};
    for (var i = 0; i < B64_ALPHABET.length; i++) B64_REV[B64_ALPHABET.charAt(i)] = i;
    return B64_REV;
}

function b64Bytes(b64) {
    var rev = b64Rev();
    var clean = String(b64 == null ? '' : b64)
        .replace(/-/g, '+').replace(/_/g, '/')
        .replace(/[^A-Za-z0-9+/]/g, '');
    var out = [];
    for (var i = 0; i < clean.length; i += 4) {
        var n = ((rev[clean.charAt(i)] || 0) << 18)
              | ((rev[clean.charAt(i + 1)] || 0) << 12)
              | ((rev[clean.charAt(i + 2)] || 0) << 6)
              | (rev[clean.charAt(i + 3)] || 0);
        out.push((n >> 16) & 0xFF);
        if (i + 2 < clean.length) out.push((n >> 8) & 0xFF);
        if (i + 3 < clean.length) out.push(n & 0xFF);
    }
    return out;
}

/** base64 → UTF-8 字符串（解码失败返回空串）。 */
globalThis.__hB64Decode = function (b64) {
    try { return utf8Str(b64Bytes(b64)); } catch (e) { return ''; }
};

/* ---------- MD5（纯 JS，替代宿主 MessageDigest） ----------
 * md5/CryptoJS.MD5 在规则里是同步调用（常与 .toString() 连用），故必须在 JS 侧就地算。 */

var MD5_SHIFT = [
    7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
    5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
    4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
    6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
];

var MD5_K = (function () {
    var k = [];
    for (var i = 0; i < 64; i++) k.push(Math.floor(Math.abs(Math.sin(i + 1)) * 4294967296) | 0);
    return k;
})();

function md5Hex(bytes) {
    var len = bytes.length;
    var padLen = (((len + 8) >> 6) + 1) * 64;   // 补 0x80 + 64 位长度，凑到 64 字节整数倍
    var buf = new Uint8Array(padLen);
    for (var i = 0; i < len; i++) buf[i] = bytes[i] & 0xFF;
    buf[len] = 0x80;
    var bitLo = (len * 8) | 0;
    buf[padLen - 8] = bitLo & 0xFF;
    buf[padLen - 7] = (bitLo >>> 8) & 0xFF;
    buf[padLen - 6] = (bitLo >>> 16) & 0xFF;
    buf[padLen - 5] = (bitLo >>> 24) & 0xFF;

    var a0 = 1732584193, b0 = -271733879, c0 = -1732584194, d0 = 271733878;
    var X = new Array(16);

    for (var off = 0; off < padLen; off += 64) {
        for (var j = 0; j < 16; j++) {
            X[j] = buf[off + j * 4] | (buf[off + j * 4 + 1] << 8)
                 | (buf[off + j * 4 + 2] << 16) | (buf[off + j * 4 + 3] << 24);
        }
        var a = a0, b = b0, c = c0, d = d0;
        for (var r = 0; r < 64; r++) {
            var f, g;
            if (r < 16) { f = (b & c) | (~b & d); g = r; }
            else if (r < 32) { f = (d & b) | (~d & c); g = (5 * r + 1) % 16; }
            else if (r < 48) { f = b ^ c ^ d; g = (3 * r + 5) % 16; }
            else { f = c ^ (b | ~d); g = (7 * r) % 16; }
            var tmp = d;
            d = c;
            c = b;
            var sum = (a + f + MD5_K[r] + X[g]) | 0;
            var s = MD5_SHIFT[r];
            b = (b + ((sum << s) | (sum >>> (32 - s)))) | 0;
            a = tmp;
        }
        a0 = (a0 + a) | 0;
        b0 = (b0 + b) | 0;
        c0 = (c0 + c) | 0;
        d0 = (d0 + d) | 0;
    }

    function hex32(n) {
        var s = '';
        for (var i = 0; i < 4; i++) s += ('0' + ((n >>> (i * 8)) & 0xFF).toString(16)).slice(-2);
        return s;
    }
    return hex32(a0) + hex32(b0) + hex32(c0) + hex32(d0);
}

/** 字符串（UTF-8）→ md5 十六进制小写。 */
globalThis.__hMd5 = function (s) {
    try { return md5Hex(utf8Bytes(s)); } catch (e) { return ''; }
};

/* ---------- gzip（交宿主解压） ---------- */

/** base64(gzip 数据) → 解压后的字符串（Promise）。 */
globalThis.__hGunzipB64 = function (b64) {
    return globalThis.__hrpc({ kind: 'gunzip', data: String(b64 == null ? '' : b64) });
};

})();
