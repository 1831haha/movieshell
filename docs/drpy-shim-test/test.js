/* drpy shim 自测：在 node 里跑 assets/jsbridge/host.js + assets/drpy/shim.js + 真实规则。
 * v0.112.0：shim 真 async 化后走 __hrpc 基元（原 __bridge 已删）。
 * __hrpc 用 node fetch/zlib 做桩（走 https_proxy 环境变量，node 24 需 NODE_USE_ENV_PROXY=1）。
 * query 桩返回 '[]'：node 没有 jsoup，HTML 抓取类规则无法在 node 侧全量自测（API 类规则不受影响）。
 * 用法：RULE=/path/to/rule.js node test.js
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const crypto = require('crypto');

const SHIM = path.resolve(__dirname, '../../app/src/main/assets/drpy/shim.js');
const RULE = process.env.RULE || '/home/hatch/workspace/tvx-push/spider/js/sp002.js';

// 必须在 eval shim 之前保存原生 fetch：shim 会把 globalThis.fetch 覆盖成 drpy 语义
//（返回 Promise<string>），桩里若再调它就无限递归。Android 宿主走 OkHttp，无此问题。
const nativeFetch = globalThis.fetch;

globalThis.__hrpc = async (payload) => {
    switch (payload.kind) {
        case 'http': {
            const headers = JSON.parse(payload.headers || '{}');
            const m = (payload.method || 'GET').toUpperCase();
            const opts = { method: m, headers };
            if ((m === 'POST' || m === 'PUT' || m === 'PATCH') && payload.body) opts.body = payload.body;
            const res = await nativeFetch(payload.url, opts);
            return await res.text();
        }
        case 'query':
            return '[]'; // node 无 jsoup，HTML 查询桩置空
        case 'gunzip':
            return zlib.gunzipSync(Buffer.from(payload.data, 'base64')).toString('utf8');
        default:
            throw new Error('unknown rpc kind: ' + payload.kind);
    }
};
globalThis.__hsend = () => {};
globalThis.__hB64Decode = (s) => Buffer.from(String(s), 'base64').toString('utf8');
globalThis.__hMd5 = (s) => crypto.createHash('md5').update(String(s), 'utf8').digest('hex');
globalThis.log = (...a) => console.log('[rule-log]', ...a);

// 间接 eval：var rule 落到全局，shim 的 globalThis 函数能读到
const shimSrc = fs.readFileSync(SHIM, 'utf8');
const ruleSrc = fs.readFileSync(RULE, 'utf8');
(0, eval)(shimSrc + '\n;\n' + ruleSrc + '\n;');

function assert(cond, msg) {
    if (!cond) { console.error('ASSERT FAIL:', msg); process.exitCode = 1; }
    else console.log('  ok:', msg);
}

(async () => {
    console.log('rule =', RULE);
    console.log('rule.title =', (typeof rule !== 'undefined' && rule.title) || '?');

    console.log('\n[1] __drpyHome');
    const home = JSON.parse(await __drpyHome());
    if (home.error) { console.log('  error:', home.error); process.exitCode = 1; }
    assert(Array.isArray(home.class), 'class 是数组');
    assert(Array.isArray(home.list), 'list 是数组');
    assert(home.list.length > 0, `推荐 list 非空 (${home.list.length})`);
    console.log('  分类:', home.class.map(c => c.type_name).join('/'));
    console.log('  首条:', JSON.stringify(home.list[0]));

    console.log('\n[2] __drpyCategory');
    const tid = (home.class[0] && home.class[0].type_id) || '1';
    const cat = JSON.parse(await __drpyCategory(tid, 1));
    if (cat.error) { console.log('  error:', cat.error); process.exitCode = 1; }
    assert(Array.isArray(cat.list), 'list 是数组');
    assert(cat.list.length > 0, `一级 list 非空 (${cat.list.length}), tid=${tid}`);
    console.log('  首条:', JSON.stringify(cat.list[0]));

    console.log('\n[3] __drpyDetail');
    const vid = cat.list[0].vod_id;
    const det = JSON.parse(await __drpyDetail(vid));
    if (det.error) { console.log('  error:', det.error); process.exitCode = 1; }
    assert(det.vod_name && det.vod_name.length > 0, `详情 vod_name 非空 (${det.vod_name})`);
    console.log('  名称:', det.vod_name, '| play_from:', det.vod_play_from.slice(0, 60));
    console.log('  play_url 头 80 字符:', det.vod_play_url.slice(0, 80));

    console.log('\n[4] __drpySearch');
    const sch = JSON.parse(await __drpySearch('哪吒'));
    if (sch.error) console.log('  error:', sch.error);
    console.log('  搜索返回条数:', sch.list ? sch.list.length : 'n/a');
    if (sch.list && sch.list.length > 0) console.log('  首条:', JSON.stringify(sch.list[0]));
    else console.log('  (注: sp002 搜索走 MeiliSearch, 规则内 Bearer token 已被打码, 空结果属预期)');

    console.log('\n[5] __drpyPlay');
    const p1 = JSON.parse(await __drpyPlay('', 'https://example.com/v.m3u8'));
    const p2 = JSON.parse(await __drpyPlay('', 'https://example.com/v.mp4'));
    if (p1.error) console.log('  error:', p1.error);
    assert(p1.url === 'https://example.com/v.m3u8' && p1.parse === 0, `lazy m3u8 直连 ${JSON.stringify(p1)}`);
    assert(p2.url === 'https://example.com/v.mp4' && p2.parse === 1, `lazy 非 m3u8 需嗅探 ${JSON.stringify(p2)}`);

    console.log('\nDONE, exitCode =', process.exitCode || 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
