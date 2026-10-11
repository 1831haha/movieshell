# TVBox 生态"跑 PHP"调研报告

- 调研日期：2026-10-12
- 调研方式：公开源码审计（takagen99/Box）、FongMi 5.7.2 APK 实包解包、GitHub 仓库/Release 全库检索、公开 TVBox 配置实测统计
- 结论先行：**没有证据表明任何主流 TVBox 系壳子在 App 内置 PHP 解释器、"本地跑 PHP"。用户看到的"别的壳子可以跑 PHP"，实锤是"调远端 PHP 接口"（type=4 / 解析接口形态），不是手机本地执行 PHP。**

## 一、核心结论对照表

| 问题 | 实锤结论 | 证据 |
|---|---|---|
| TVBox 官方有没有 PHP 源类型？ | 没有。官方 type 只有 0=xml、1=json、3=Spider(Java) | takagen99/Box `SourceBean.java` 第 9 行注释（源码直读） |
| FongMi 是否内置 PHP？ | 否。67MB 实包里无 php 二进制、无 php-cgi、无 .php 规则资源 | FongMi/Release 5.7.2 `mobile-arm64_v8a.apk` 解包实测 |
| GitHub 上"tvbox php"是什么？ | 全是服务端 PHP（自建接口服务），零个本地执行方案 | GitHub API 全库检索仅 5 个仓库，逐一核验 |
| type=4 是不是 PHP 类型？ | 不是。type=4 是社区扩展的"HTTP API 源"，后端可以是 PHP/Node/任意语言 | ljnpng/tvbox `missav-api.json` 实证（FongMi 5.5.6 真机验证） |
| 有没有手机端 PHP 二进制可用？ | 有。termux-packages 有 php 8.5.1 aarch64 构建（.deb 约 11.7MB） | termux-packages `packages/php/build.sh`、官方 deb 仓库 |

## 二、"PHP 源/规则"在 TVBox 生态里的真实形态

### 2.1 官方 type 体系里没有 PHP

TVBoxOSC 血统的开源实现 `takagen99/Box`，`SourceBean.java`：

```java
private int type;   // 0 xml 1 json 3 Spider
```

- 证据来源：https://raw.githubusercontent.com/takagen99/Box/main/app/src/main/java/com/github/tvbox/osc/bean/SourceBean.java（2026-10-12 直读）
- type=3 的 Spider 是 Java 写的 `csp_*` 类，经 `JarLoader` 从配置里的 `spider` jar 包加载（`ApiConfig.java` 第 9、237 行）。整个官方体系里没有任何 PHP 解释器调用。

### 2.2 type=4：社区"HTTP API 源"，不是 PHP 专用类型

公开仓库 `ljnpng/tvbox` 的 `missav-api.json`（README 注明在 FongMi TV 5.5.6 真机/模拟器验证通过）：

```json
{
  "key": "missav_api",
  "name": "MissAV | 自有API",
  "type": 4,
  "api": "http://10.0.2.2:8781/api/missav",
  "searchable": 1, "quickSearch": 1, "filterable": 0, "timeout": 30
}
```

- 证据来源：https://github.com/ljnpng/tvbox（README + `missav-api.json`，2026-10-12 直读）
- 要点：`type: 4` 的 `api` 是一个 **HTTP 地址**，由仓库自带的 `scripts/missav-api-server.js`（Node.js）提供服务。App 侧只是按 `?ac=home / ?ac=detail&ids= / ?wd=` 等约定发 HTTP 请求、解析返回的 TVBox JSON。**后端用 PHP 写还是 Node 写，App 完全不感知**——这就是生态里"PHP 接口"的真相：PHP 只活在服务器那一端。

### 2.3 "PHP 规则文件"长什么样

GitHub 全库检索 `tvbox php` 仅 5 个仓库（GitHub API，2026-10-12），逐一核验全部是**服务端 PHP**：

- `Joyist2021/diyp-channel-php`（★7）："自建DIYP影音和TVBOX的接口PHP服务"。含 `live.php`（`?t=m3u / ?t=tvbox` 输出直播源）、`epg.php`、`channel.php` 等，README 明确要求 PHP 环境扩展（curl/openssl/pdo_sqlite 等）——这是跑在路由器/服务器上的，不是手机 App。
- `zcl668/tvbox_php`、`surperone/Tvbox`、`EvGeniyLell/TVBoxPhp`、`qqkzlm/ysc-tvbox-portal`：同类，均为服务端脚本或订阅门户。

结论：生态里流传的 `.php` 规则文件，是**丢到自己服务器/PHPStudy/路由器上跑的后端脚本**，对 App 来说就是一个个 `http://xxx.php?...` 的远端接口。App 侧的"支持 PHP"，等价于"能发 HTTP 请求"。

## 三、有没有壳子在本地跑 PHP？逐个查证

### 3.1 FongMi 5.7.2 实包解包（最强反证）

- 包来源：https://github.com/FongMi/Release/releases/tag/5.7.2，`mobile-arm64_v8a.apk`（67MB，2026-10-12 下载实测）
- `unzip -l` 全包检索 `php|cgi`：**零命中**——没有 php 二进制、没有 php-cgi、没有 `.php` 规则资源。
- `lib/arm64-v8a/` 里与规则引擎相关的只有一个 `libnodebridge.so`（给 drpy-node 跑 JS 用的），**没有 PHP 相关 .so**。
- `classes.dex / classes2.dex / classes3.dex` 字符串检索 `php`：共 13 处，全部是 incidental（`index.php?` 这类 URL 片段、混淆字符串噪音），无任何 PHP 规则加载、PHP 引擎调用逻辑。

### 3.2 FongMi/TVBox Release notes

- GitHub API 拉取 FongMi/TVBox 最近 30 个 Release，全文检索 `php`：**零命中**。

### 3.3 官方源码

- `takagen99/Box` 全源码无 PHP 相关代码（`grep -i php ApiConfig.java` 零命中）；type 体系见 2.1。

### 3.4 查证局限（诚实标注）

- 影视仓、OK影视等是闭源 App，无法像 FongMi 一样解包验证。但 TVBox 生态的源类型标准由开源实现（TVBoxOSC → takagen99/Box → FongMi）定义，FongMi 作为生态事实标准不含 PHP，闭源壳另起炉灶内置 PHP 解释器的可能性极低（且无任何公开文档/更新日志佐证）。

### 3.5 小结

**没有任何实锤证据支持"某壳子在手机本地执行 PHP"。** 反证充分：旗舰开源实现无 PHP 类型、无 PHP 二进制、无相关代码。

## 四、用户看到的"别的壳子跑 PHP"最可能的三种真相

1. **远端 PHP 接口（最可能）**：壳子里配了 `http://xxx.com/api.php?...` 或 `xxx.php?url=` 的解析/聚合接口。用户看到 URL 里有 `.php`，误以为壳子在"跑 PHP"。实际壳子只发 HTTP，PHP 在别人的服务器上跑。这是 type=4 和解析接口（parses）的标准形态。
2. **"本地"指局域网自建服务**：部分玩家用 PHPStudy 在电脑上、或在路由器/NAS 上跑 PHP 脚本，壳子填 `http://192.168.x.x/xxx.php`。"本地"是局域网意义上的，PHP 依然不在手机里执行。
3. **与 drpy-node 混淆**：FongMi 等壳子确实内置了"本地执行引擎"，但那是 **Node.js（`libnodebridge.so`）跑 JS**，不是 PHP。

## 五、如果 MovieShell 要"真本地跑 PHP"，具体怎么做

以下路线技术上可行（我们已有 `magnet-stream` 即 node 二进制本地服务的先例：server 形态、`127.0.0.1:17890`），但请先看第六节的成本评估。

### 5.1 二进制来源

- termux-packages 官方构建：`php_8.5.1_aarch64.deb`（约 11.7MB，2026-10-12 实测 `Content-Length: 11725760`），含 php CLI。
- 证据来源：https://github.com/termux/termux-packages/blob/master/packages/php/build.sh ；deb 仓库 `https://packages.termux.dev/apt/termux-main/pool/main/p/php/`
- 注意：Termux 的 php 依赖 `$PREFIX/lib` 下的一堆共享库（libxml2、libssl、libsqlite3 等），不能只抽 `usr/bin/php` 一个文件，需要 `dpkg-deb -x` 解包后用 `readelf -d` 扫 NEEDED，一并打包。

### 5.2 打包位置

- 抽出的 `php` 二进制 + 依赖 `.so` 放进 APK 的 `assets/php/`（或走我们已有的 server 插件形态，参考 DsPlayer 的 binary-runtime 约定）。
- 首次启动拷贝到 `filesDir/php-bin/` 并 `chmod 700`（Android 10+ 禁止从 APK 内直接执行，必须拷贝到应用私有目录）。

### 5.3 启动与生命周期（参考 magnet-stream）

- 用 `ProcessBuilder` 启动：`php -S 127.0.0.1:17891 -t <filesDir/php-rules/>`（php CLI 自带 `-S` 内置 Web 服务器，无需 php-cgi/php-fpm）。
- 端口建议固定 `17891`（与 magnet-stream 的 17890 错开），或动态分配后经内存 side-channel 告知 App（参考 v0.5 嗅探 Referer/UA 的 side-channel 做法）。
- 生命周期：规则首次被调用时拉起；App 退出 / 任务结束时 destroy；加 `/healthz` 探活（参考 magnet-stream）。

### 5.4 规则协议对接

- 本地 PHP 规则 = 普通 PHP 脚本，对外暴露 TVBox 聚合接口约定：
  - `GET /<rule>.php?ac=home` 首页推荐
  - `GET /<rule>.php?ac=detail&ids=xxx` 详情（含 `vod_play_from` / `vod_play_url`）
  - `GET /<rule>.php?wd=关键词` 搜索
  - `GET /<rule>.php?ac=play&u=...` 播放解析
- App 侧**不需要新引擎**：这正是 type=4 的"HTTP API 源"形态，`SiteRepository` 复用现有 HTTP + TVBox JSON 解析即可，把 base URL 指向 `http://127.0.0.1:17891` 就行。
- 也就是说：A 路线真正的增量工作只有"打包+拉起 php 二进制"，协议层与 B 路线完全同构。

### 5.5 安全警告（必须写进手册）

- 任意第三方 `.php` 规则在手机本地执行 = **手机本地任意代码执行**，风险远高于 JS（WebView 沙盒）/ PY（Chaquopy 受限环境）。规则来源不可信时等同于装恶意软件。

## 六、建议

1. **先做 B 路线（远端 PHP 接口 = type=4 HTTP API 源）**：零引擎成本，App 侧只是"认 `type=4`、把 `api` 当 HTTP 接口调"。生态里所有"PHP 接口"（包括用户手里可能有的 `xxx.php` 地址）立刻可用。这极大概率就是用户要的东西。
2. **A 路线（内置 php 二进制）暂不做**，除非用户能拿出一份真实存在的、需要在手机本地执行的 `.php` 规则文件样本。目前实锤的生态现状是：**客户端 PHP 规则数量为 0**（自家 830 站规则库、GitHub 全库检索均为证）——做了引擎也没有规则可跑。
3. **请用户提供实物**：他所说的"别的壳子"的具体名字（+ 版本号，在手机设置→应用里查），以及一份他手里的 `.php` 规则文件。对着实物 10 分钟就能定性是第 1、2 还是 3 种真相，比隔空争论高效。

## 七、证据索引

| # | 证据 | 链接/位置 | 证明什么 |
|---|---|---|---|
| 1 | takagen99/Box `SourceBean.java` | https://raw.githubusercontent.com/takagen99/Box/main/app/src/main/java/com/github/tvbox/osc/bean/SourceBean.java | 官方 type 只有 0/1/3，无 PHP |
| 2 | takagen99/Box `ApiConfig.java` | https://raw.githubusercontent.com/takagen99/Box/main/app/src/main/java/com/github/tvbox/osc/api/ApiConfig.java | type=3 走 JarLoader 加载 Java Spider，无 PHP |
| 3 | FongMi 5.7.2 APK 解包 | https://github.com/FongMi/Release/releases/tag/5.7.2（`mobile-arm64_v8a.apk`，本地实测） | 无 php 二进制/规则资源；引擎只有 libnodebridge（Node/JS） |
| 4 | ljnpng/tvbox `missav-api.json` | https://github.com/ljnpng/tvbox | type=4 = HTTP API 源，后端语言 App 不感知 |
| 5 | GitHub "tvbox php" 全库检索 | GitHub API `search/repositories?q=tvbox+php`（2026-10-12） | 仅 5 仓库，全是服务端 PHP |
| 6 | Joyist2021/diyp-channel-php | https://github.com/Joyist2021/diyp-channel-php | "PHP 规则"=跑在服务器/路由器上的 PHP 脚本 |
| 7 | termux-packages php | https://github.com/termux/termux-packages/blob/master/packages/php/build.sh | Android arm64 PHP 8.5.1 构建存在，.deb 约 11.7MB |
| 8 | FongMi/TVBox releases | GitHub API `repos/FongMi/TVBox/releases`（2026-10-12） | 30 个版本日志无 php |
