# 侠影源写作指南（V8 异步版）

> 适用：侠影 movieshell 的 JS 规则（drpy 兼容层），引擎为 `androidx.javascriptengine`（V8 isolate）。
> 本指南只写侠影自己的真实契约，不照搬任何别家文档；与上游 drpy 文档冲突时，以本指南为准。

## 一、与 DsPlayer 同步写法的核心差异

DsPlayer 的 QuickJS 桥是同步的：`request()` 直接返回结果，写完就能用。
侠影的 V8 桥是真异步的，这是写规则时唯一要时刻记住的区别：

| 维度 | DsPlayer 同步写法 | 侠影 V8 异步写法 |
|---|---|---|
| 网络请求 | `var html = request(url)` 直接拿到文本 | `var html = await request(url)`，不 await 拿到的是 Promise |
| 解析 | `pdfh(html, 'a&&Text')` 直接出结果 | `await pdfh(html, 'a&&Text')` |
| 章节函数 | `一级: function(){ ... }` | `一级: async function(){ ... }`，章节本身也要 async |
| 请求返回值 | 上游 `req()` 返回 `{content, headers}` 对象 | 本引擎 `request()` 直接返回 body 文本字符串，不要 `.content` |
| 失败语义 | 各家不一 | 失败 resolve `''`，不 reject（见第三节） |
| 单次执行超时 | 无 | 单次 JS 执行硬超时 15 秒，超时直接终止 isolate 并重建 |

**一句话：老规则迁到侠影，最小动作就是请求加 await、章节改 async 签名，其余基本原样。**

## 二、必须 await 的调用（返回 Promise）

| 调用 | 返回 |
|---|---|
| `request(url, options)` | `Promise<string>`：body 文本；失败 resolve `''`，不 reject |
| `fetch(url, params)` | `Promise<string>`，即 `request(url, params)` |
| `post(url, params)` | `Promise<string>`，method 强制 POST |
| `pdfh(html\|el, 'a&&b&&Text')` | `Promise<string>`：链式下钻取文本或属性 |
| `pdfa(html\|el, 'a&&b&&Text')` | `Promise<string[]>`：链式下钻取数组 |
| `pd(...)` | `pdfh` 的别名，同样返回 Promise |
| `jsp.pdfh / jsp.pdfa / jsp.pd` | 同上 |

POST 注意：`request(url, {method:'POST', body:'...', headers:{...}})`，POST 体只认
`options.body`（字符串），`options.data` 会被忽略。headers 默认带移动 UA，
`'MOBILE_UA'` 字面量会被替换成真实 UA。

## 三、保持同步的调用（不用 await）

`setResult(d)`、`md5(s)`、`base64Decode(s)`、`urlencode(s)`、`stringify(o)`、
`print/log`、`CryptoJS.MD5(s).toString()`、`muban` 模板对象、
`MOBILE_UA / IOS_UA / rule_fetch_params / fetch_params` 全局量、
`d` 数组（规则往里 push，框架收集）。

失败处理：`request` 失败时 resolve `''`，所以 `JSON.parse(await request(url))` 之前
先判空，推荐写成 `JSON.parse(body || '{}')`，避免空字符串导致解析异常。

## 四、三个必改的陷阱

### 1. forEach 里用 await 无效

```js
// ❌ forEach 回调里的 await 不会被等待，setResult 会先执行
items.forEach(async it => { d.push({ vod_name: await pdfh(it, 'a&&Text') }); });
setResult(d);

// ✅ 改成 for 循环
for (var i = 0; i < items.length; i++) {
    d.push({ vod_name: await pdfh(items[i], 'a&&Text') });
}
setResult(d);
```

### 2. 链式 pdfh 中间结果也要 await

```js
// ❌ 内层 pdfh 返回的是 Promise，不能直接当 html 传给外层
var title = await pdfh(pdfh(html, 'div&&Html'), 'a&&Text');

// ✅ 分步写，或内层也 await
var inner = await pdfh(html, 'div&&Html');
var title = await pdfh(inner, 'a&&Text');
```

### 3. "js:" 字符串章节同样要 await

本引擎与部分上游实现不同：`"js:"` 片段里的 `request()` 同样返回 Promise，
所以 `"js:var html=request(input);..."` 必须改成
`"js:var html=await request(input);..."`。复杂逻辑请写成 `async function` 形态，
不要依赖顶层 await（普通语句片段顶层不能用 await）。

## 五、最小可跑源示例（API 站）

```js
var rule = {
    title: '示例',
    host: 'https://api.example.com',
    homeUrl: '/',
    url: '/list?cid=fyclass&page=fypage',
    detailUrl: '/detail/fyid',
    searchUrl: '/search?wd=**&page=fypage',
    一级: async function () {
        var data = JSON.parse(await request(this.input) || '{}');
        var d = [];
        var arr = data.list || [];
        for (var i = 0; i < arr.length; i++) {
            d.push({ vod_id: arr[i].id, vod_name: arr[i].name,
                     vod_pic: arr[i].pic, vod_remarks: arr[i].remark });
        }
        setResult(d);
    },
    二级: async function () {
        var data = JSON.parse(await request(this.input) || '{}');
        setResult({ vod_id: this.ID, vod_name: data.name, vod_pic: data.pic,
                    vod_play_from: '默认', vod_play_url: data.playUrl });
    },
    搜索: async function () {
        var data = JSON.parse(await request(this.input) || '{}');
        setResult((data.list || []).map(function (it) {
            return { vod_id: it.id, vod_name: it.name, vod_pic: it.pic };
        }));
    },
    播放: async function () {
        // input 是播放条目 id/URL 原值；返回直链（parse:0）或 {url, parse:1} 走解析
        setResult({ url: this.input, parse: 0 });
    },
};
```

注意：`this.input` 是渲染后的 URL，不是响应内容；不要对 `lazy`（播放）的 input
做 `detailUrl` 渲染，会破坏 `.m3u8` 直链判断。

## 六、规则结构速查

### rule 字段

| 字段 | 说明 |
|---|---|
| `host` | 站点根地址 |
| `homeUrl` / `url` / `detailUrl` / `searchUrl` | 首页 / 一级列表 / 详情 / 搜索地址模板 |
| `headers` | 对象或函数，合并进每次 request（`'MOBILE_UA'` 自动替换） |
| `class_name` / `class_url` | `&` 分隔的静态分类名 / 分类 id |
| `class_parse` | 分类解析：`'选择器;标题;链接;链接正则'` 字符串模板，或函数/js: |
| `filter` / `filter_def` / `filter_url` / `filterable` | 筛选分组定义 / 默认值 / 筛选 URL 模板 / 是否可筛选 |
| `模板` | 如 `'mxpro'`，自动合并 `muban.mxpro`（rule 自身属性优先） |
| `推荐` / `一级` / `二级` / `搜索` / `播放`(`lazy`) | 章节：函数 / `"js:"` 字符串 / 列表模板 / `'json:…'` 简写 |

### 章节形态

1. **函数**：`async function(){ ... this.input ... }`，`this` 即 ctx；返回值或 `setResult(d)`（setResult 优先）。
2. **`"js:"` 字符串**：裸变量 `input/KEY/MY_PAGE…` 解析到 ctx，用 `setResult(d)` 交结果。
3. **列表模板**：`'容器;条目;标题;图;描述;链接'`（6 段）或 `'条目;标题;图;描述;链接'`（5 段）。
4. **`'json:路径;标题;图;描述;链接'`**：拉 input 取 JSON 按路径映射列表。
5. `推荐: '*'` 表示用一级逻辑解析 homeUrl 页面；`二级: '*'` 表示直播默认详情（单线路，id 直通播放）。

### 地址模板占位符

`fyclass`（分类 id）、`fypage`（页码）、`fyid`（详情 id）、`**`（搜索关键词，
`searchUrl` 必须带，否则 `this.KEY` 为空）、`{{fl.键名}}`（筛选值）、`{{fyclass}}`。
`filter_url` 渲染后拼进 `url` 的 `fyfilter` 处，无 `fyfilter` 标记时追加到末尾。

### 章节内可用变量（ctx）

`this.input`（渲染后的 URL）、`this.HOST`、`this.KEY`（搜索词）、
`this.MY_PAGE`（页码）、`this.TID` / `this.PG`（一级）、`this.ID`（二级）、`this.flag`（播放）。

### 输出字段

- 列表项：`vod_id / vod_name / vod_pic / vod_remarks`（别名 `url/title/img/desc` 自动归一）。
- 详情：上 += `vod_year / vod_actor / vod_director / vod_desc`（`vod_content` 同义）、
  `vod_play_from`（线路名，`$` 分隔多线路）、`vod_play_url`（`线路$剧集$...` 格式）。
- 播放：`{url, parse}`，`parse:1` 表示走解析接口。

### pdfh/pdfa 链式语法

`'选择器&&选择器&&Text'`：`&&` 逐级下钻，最后一段是取值操作——`Text`（文本）、
`Html`（html）、或属性名（如 `href`、`src`、`data-src`）。选择器直接透传 jsoup，
支持 `:eq(n) / :gt(n) / :lt(n) / :first / :last` 等伪类。

## 七、老规则迁移 checklist

1. 全文搜 `request(`、`fetch(`、`post(`、`pdfh(`、`pdfa(`、`pd(`，逐个确认前面有 `await`（排除已有的和 `//` 注释行）。
2. 每个章节函数（推荐/一级/二级/搜索/播放/lazy）确认是 `async function`。
3. `forEach` 回调里有用 await 的，改 for 循环。
4. 跑一遍：首页 → 分类 → 详情 → 搜索 → 播放，五个入口全通才算迁完。

## 八、本引擎不支持的能力

`batchFetch`（并发批量请求）、`local`（按源 KV）、`pdfl`、`cheerio`、
`getProxyUrl`、`wasm`。规则里用到这些的需改写或放弃。

## 九、相关示例

- JS 规则完整演示：`docs/examples/hello.js`（复制到手机 `xiaying/js/`）
- Python 规则演示：`docs/examples/hello.py`（复制到手机 `xiaying/py/`）
- PHP 规则演示：`docs/examples/demo_spider.php`（复制到手机 `xiaying/php/t4_demo/`）
