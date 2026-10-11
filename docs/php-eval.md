# movieshell v0.5 PHP 引擎评估

> 评估日期：2026-10-12　评估范围：只评估、只出结论，不涉及代码改动。
> 结论前置：**建议砍掉（移除 PHP 执行支持，像 AI 一样彻底删除）**，理由见文末。

## 一、背景

v0.4 引入了 `PhpSourceManager` 诚实桩（`EXECUTION_SUPPORTED=false`，所有执行函数返回空，UI 提示"暂不支持执行"），v0.4.1 刚把 AI 功能彻底砍掉（"按后缀分发不诚实"）。

本次评估回答一个问题：TVBox 生态的 PHP 爬虫规则，在 Android 手机端到底能不能跑？如果答案是"能但代价巨大"或"根本没人这么用"，PHP 分组就和 AI 分组一样，该删。

评估前先看样本：结论不是拍脑袋的，下面所有判断都建立在真实 PHP 规则长什么样、以及它们实际在哪里被执行的基础上。

## 二、PHP 规则长什么样（先看 1 个真实样本再下结论）

样本来源：`yhxxm/drpy-node` 仓库 `spider/php/` 目录（2026-10-12 查证，37 个条目中 31 个 `.php` 文件，扣除 `config.php`/`index.php`/`test_runner.php`/`_bridge.php`/`_crawler_bridge.php` 和 `lib/` 框架目录，**约 25 个真实爬虫规则**，覆盖影视、小说（`[书]`）、漫画（`[画]`）三类）。

抽看其中一个影视规则（161 行，结构如下，内容为概括转述非原文引用）：

- 开头 `require_once __DIR__ . '/lib/spider.php'`，引入 drpy-node 自带的 `BaseSpider` 框架；
- 自定义加密类：AES-128-ECB 解密（`openssl_decrypt`）＋ RSA 分块签名（`openssl_private_decrypt`，按密钥长度分块）；
- `class Spider extends BaseSpider`，实现 5 个标准方法：`homeContent / categoryContent / detailContent / searchContent / playerContent`；
- 请求头里写着 `'User-Agent' => 'dart:io'`——说明这类规则的消费端是 **Flutter 系 TVBox 壳**；
- 文件末尾一行自动运行指令：`(new Spider())->run();`

配套的《PHP Spider 开发与维护指南》明确了运行方式（概括转述）：

- **环境**：Windows 装 PHP 8.3+ NTS，或 Linux `php8.3-cli`，另需启用 `curl / mbstring / openssl / sockets / sqlite3` 等扩展；
- **调用**：Flutter 端/测试端通过 **CLI 调用 PHP 脚本**（`php 脚本.php …`），`run()` 解析参数、执行业务方法、把结果 `json_encode` 后打印到 stdout，调用方解析这段 JSON；
- **已知坑**：PHP 空数组 `json_encode` 出 `[]` 而客户端要 `{}`（指南专门列了一节讲这个）、`md5` 默认小写 hex、签名参数要 `ksort` 排序、中文 URL 必须手动 `urlencode`——这些全是 PHP 特有的语义细节。

**一句话定性：PHP 规则是"进程式 CLI 脚本"，不是可嵌入的库。** 它的整个设计假设是"有一台装了 PHP CLI 的机器"，drpy-node 本体就是跑在服务器/PC 上的 Node 服务，PHP 规则是它服务端的执行单元。

## 三、三维度对比

### 维度 1：嵌入解释器路线（对标 Chaquopy）

| 项 | 结论 |
|---|---|
| 有没有 PHP 版 Chaquopy（开箱即用的 Gradle 嵌入插件） | **没有**。2026-10-12 全网检索，未发现任何成熟的 PHP Android 嵌入 Gradle 插件（否定性结论，诚实标注为检索结果而非定理） |
| 已验证可行的嵌入先例 | 有，但都是**整套 App 框架**，不是可插拔引擎：① **Phphone**（开源 Apache-2.0，2026-09 刚发 v1.0.2）：PHP 8.4 通过 JNI 跑在 C++ 层；② **NativePHP Mobile**：PHP embed SAPI + JNI。两家都是"用 PHP 写整个 App"的框架，movieshell 要的是"在 Kotlin App 里跑第三方 PHP 脚本"，需求错位 |
| PHP 解释器的线程/进程模型坑 | NativePHP 团队公开承认：PHP 解释器"不是为在同一进程里跑两次设计的"，ZTS/TSRM 上下文隔离、破坏性 shutdown 都是深坑。他们最后用的是"序列化调度"绕过去——这是框架级的工作量 |
| 另一条路：APK 内置 PHP CLI 二进制＋`Runtime.exec` 逐次起进程 | 技术上可行（把交叉编译好的 `php` 二进制放进 APK，调规则时 fork 进程跑脚本、读 stdout JSON——和 drpy-node 的服务端模式同构）。但：① 每次搜片/详情/播放都是一次进程创建＋解释器冷启动（百毫秒级，**估算**）；② curl/openssl/mbstring/sqlite3/dom 等扩展要自己用 NDK 交叉编译、自己跟进 PHP 每年一个大版本；③ 16KB 内存页（Android 15+ 趋势）下原生库加载问题和 Chaquopy 遇到的同类。全是我们的维护成本 |
| APK 体积增量 | **无实测数据，估算 +20~40MB/ABI**（对标 Chaquopy Python 的实测区间；PHP 官方未给、社区无拆解数据，明确标注为估算） |

**维度 1 小结**：嵌入路线没有"拿来即用"的方案，只有"手搓 NDK 全套"（解释器＋扩展＋JNI＋线程安全），维护成本无上限，且生态里的 PHP 规则作者写规则时根本没考虑过 Android。

### 维度 2：转译/重写路线（PHP → Kotlin/JS）

- **机械转译不可行。** 真实规则重度依赖 PHP 语义，样本里的 AES-ECB＋分块 RSA 必须逐条手工移植；更麻烦的是那些"看起来能转、实际行为有差"的地方：`json_encode` 的 `[]` vs `{}`（drpy-node 官方指南自己列为常见 bug）、`md5` 小写 hex、`ksort` 签名排序、`urlencode` 行为、`openssl` 填充模式、`DOMDocument` 与 WebView DOM 的差异、PHP 松散类型。转译器要 100% 复刻这些才不会把规则转废——这相当于重写一个 PHP 语义子集，工作量不亚于嵌入解释器。
- **生态流向是反的。** drpy-node 指南里专门有一节叫"JS -> PHP 转换"（遇到 JS 源用加密，用 PHP 的 openssl 对应实现）——**PHP 规则大多是 drpy JS 规则的移植版，不是源头**。把移植版再转译回 JS，等于绕一圈回到起点，纯属折腾。
- 唯一可行的"转译"是挑 1~2 个热门 PHP 规则**手工重写成 drpy JS**——但那属于"加源"工作，不需要 PHP 引擎，v0.1 的 drpy 引擎已经能跑。

**维度 2 小结**：作为通用引擎路线，转译不可行；作为个案处理，不需要引擎。

### 维度 3：云端/代理路线（手机调远端 PHP 执行服务）

- **这已经是 PHP 在生态里的实际消费方式**，三处证据：
  1. drpy-node 本来就是服务端：PHP 规则跑在服务器/PC 的 PHP CLI 里，对 TVBox 只暴露 HTTP；
  2. 自家接口库（2026-10-07 备份，830 站）里 17 个 `type=4` 站点，`api` 全是远端 PHP 聚合服务的 URL（如 `zhangqun1818.serv00.net` 免费 PHP 主机上的 `py.php`/`js.php`/`php.php`，以及 `?do=py&pwd=dzyyds` 这类带口令的聚合接口）——**PHP 跑在别人服务器上，TVBox 只做 HTTP**；
  3. 第三方规则生成工具（猫爪 site-parser-builder）的"服务端解析模式"就是这个架构。
- **但作为产品功能不可接受**，两条硬伤：
  1. 用户要的是**本地壳**。把执行外包给远端服务，等于每个 PHP 源都要用户自己搭后端（或信任 serv00.net 这类免费主机＋`pwd=dzyyds` 这种口令鉴权）——和"本地"定位直接冲突；
  2. 通用"远端 PHP 执行代理"（用户丢任意 `.php` 上来、我们找个服务器跑）需要我们运维执行后端：沙箱逃逸、资源滥用、法律风险，一个都躲不掉。这不是 v0.5 该碰的东西。
- 最多只能做成"用户自备后端"的高级选项——而那不需要我们写任何引擎代码。

**维度 3 小结**：云端是 PHP 唯一的现实跑法，但和产品定位冲突，做成内置功能不可接受。

### 维度 4：需求强度（PHP 规则在生态里的实际占比和不可替代性）

| 数据 | 数值 | 来源 |
|---|---|---|
| 自家 830 站中**客户端 `.php` 规则**数量 | **0** | 查证：2026-10-07 本地备份 `index_830_20261007_1658.json`，类型分布 type=3（JS/PY spider）757、type=1（CMS）55、type=0 1、type=4（远端聚合 HTTP 服务）17。注意：`api.php/provide/vod` 只是 CMS 采集站的服务端接口名，不是 PHP 规则 |
| drpy-node `spider/php` 真实规则数 | 约 25 个 | 查证：2026-10-12 GitHub API 列目录 |
| 主流 Android 壳对 PHP 的支持 | 无。mbox 等只跑 QuickJS（JS）和 py 引擎插件；第三方规则生成器的本机执行模式只生成 py/js | 查证：公开仓库文档 |
| 不可替代性 | **零**。所有 PHP 规则都是对公开网站的 HTTP 抓取，且大多有 JS 版源头；没有"只有 PHP 能写"的站点 | 查证样本＋指南"JS -> PHP 转换"节 |

**维度 4 小结**：自家一个 PHP 规则都没有，生态里 PHP 规则是 drpy-node 服务端生态的小卫星（~25 个），且全是"服务器上跑"的设计。**没有非支持不可的理由。**

## 四、推荐结论：砍掉

**一句话：PHP 规则是服务器生态（drpy-node）的产物，在手机端没有成熟嵌入方案、转译是逆生态而行、云端路线与"本地壳"定位冲突，而自家 830 站里一个客户端 PHP 规则都没有——留着"PHP 分组＋诚实桩"，只会让用户误以为将来能用。AI 分组刚因为"按后缀分发不诚实"被砍，PHP 分组是同一个病，砍掉最诚实。**

理由（按权重排序）：

1. **没有 Chaquopy 等价物，嵌入＝手搓 NDK 全套**（解释器＋扩展交叉编译＋JNI＋ZTS 线程安全坑），维护成本无上限，而 PHP 官方一年一个大版本。
2. **转译是逆生态而行**：PHP 规则本就是 JS 规则的移植版（drpy-node 指南自证），转回去毫无意义；个案重写不需要引擎。
3. **需求为零**：自家 830 站 0 个客户端 PHP 规则；生态 ~25 个且全是服务端跑的；主流 Android 壳没人打包 PHP。
4. **一致性**：AI 已砍，留着 PHP 分组是双重标准。"暂不支持执行"的诚实桩，诚实的对象是"现在"，但分组本身暗示"将来"——而评估结论是"将来也不该做"。

**不砍的唯一情形**：将来某天用户明确说"我有一批 PHP 规则必须在手机上跑"——到时也应该是"用户自备 PHP 后端＋壳子只配 URL"（维度 3 的高级选项），而不是在 APK 里塞解释器。

## 五、砍掉落地步骤（只写步骤，不写代码）

1. 删除 `PhpSourceManager.kt`（含 `filesDir/php/` 目录创建逻辑）；`MainActivity` 里删 `init` 调用。
2. `SiteRepository` 删除 `php:` 分支及 `PHP_SITE_TYPE = -2` 哨兵。
3. `SourcesScreen` 删除 PHP 分组 UI、导入/删除链路及相关 Toast 文案。
4. `ManualScreen`（使用手册）/`BackupManager` 里 PHP 相关文案清掉；手册如有"PHP 不支持"章节，删掉或压缩成一句话（建议删，保持和 AI 一致的"彻底"口径）。
5. **`type=4` 远端聚合站点不受影响**：它们是纯 HTTP 服务，不需要 PHP 解释器，和本次砍掉无关（当前路由本来也没处理 type=4，保持现状即可）。
6. 兜底口径：若用户以后丢 `.php` 文件进来，导入时直接 Toast"不支持 PHP 规则"，不建分组、不存文件。

## 六、数据来源

- `yhxxm/drpy-node` 仓库 `spider/php/readme.md`《PHP Spider 开发与维护指南》：运行环境（PHP 8.3+ NTS / php-cli＋curl/mbstring/openssl/sqlite3 扩展）、`BaseSpider` 五个标准方法、`(new Spider())->run()` 约定、Flutter 端 CLI 调用、已知语义坑（`[]` vs `{}`、`md5` 小写、`ksort`）。——2026-10-12 读取，**查证**。
- 真实样本：`哇哇影视 ᵈᶻ.php`（161 行，AES-128-ECB＋分块 RSA、`dart:io` UA）。结构为概括转述。——2026-10-12 拉取，**查证**。
- 自家接口备份 `~/workspace/backup/tvx-4f5c87/index_830_20261007_1658.json`：830 站类型分布（3:757 / 1:55 / 0:1 / 4:17），客户端 PHP 规则 0 个。——本地数据，**查证**。
- Phphone（dev.to 文章＋`github.com/phphone/phphone`，Apache-2.0，PHP 8.4 JNI 嵌入，v1.0.2 发布于 2026-09-14）；NativePHP Mobile（PHP embed SAPI＋JNI，"PHP 解释器不是为在同一进程跑两次设计的"）。——2026-10-12 检索，**查证**。
- "无 PHP 版 Chaquopy"：2026-10-12 全网检索未发现成熟 PHP Android 嵌入 Gradle 插件。——否定性结论，诚实标注为检索结果。
- 体积增量 +20~40MB/ABI、PHP CLI 单次进程启动百毫秒级：**估算**，已在正文标注。
