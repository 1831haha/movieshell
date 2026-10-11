# movieshell v0.2 PY 引擎路线评估

> 评估日期：2026-10-11　评估范围：只评估、只出结论，不涉及代码改动。
> 结论前置：**推荐路线 B（外置 py 引擎插件 APK）**，理由见文末。

## 一、背景

movieshell v0.1 已交付（Compose 深色 UI、ExoPlayer、TVBox JSON 订阅、本地规则管理），v0.2 规划支持 PY 源——即 hipy 风格的 Python 爬虫规则（TVBox 生态 type=3 源：规则是一个 `.py` 文件，继承 hipy 的 spider 基类、用 requests 等库抓取）。

用户手机上已有 DsPlayer，其 PY 源靠"py 引擎插件 APK"跑通（hipy 已验证可用）。

两条路线：

- **路线 A：Chaquopy 嵌入** —— 用 Chaquopy Gradle 插件把 CPython 解释器＋hipy 规则＋依赖打进 movieshell 自己的 APK，Kotlin 通过 `Python.getInstance()` 经 JNI 直接调 Python 代码。
- **路线 B：外置 py 引擎插件 APK** —— 像 DsPlayer 那样，壳子本体不含解释器；用户在 PY 源时，壳子通过 intent/广播/AIDL 把规则和参数发给手机上已装的第三方 py 引擎插件 APK，由它执行 Python 并把结果回传。

## 二、四维度对比

### 维度 1：包体积

| 项 | 路线 A：Chaquopy | 路线 B：外置插件 |
|---|---|---|
| 解释器本体 | 约 +18MB（libpython3.x.so＋C 运行时垫片） | 0 |
| Python 标准库 | 约 +12MB（打包的 stdlib zip） | 0 |
| pip 依赖（requests/urllib3/Crypto 等） | 约 +18MB | 0 |
| **APK 增量（arm64-v8a 单 ABI）** | **约 +30～50MB**（仅 1 个 ABI；多 ABI 每个都要再加一份） | **0** |
| 当前 v0.1 APK（20MB）会变成 | 约 50～70MB | 仍为 ~20MB |

- 增量数字来源：Chaquopy 类项目的实测拆解（libpython ~18MB、stdlib ~12MB、pip 包 ~18MB，某项目从 19MB 膨胀到 68MB）；另有评估给出"每个 ABI 30–60MB"的区间口径。均标注为**社区实测估算**，非 Chaquopy 官方承诺值（官方未给出固定数字，因依赖具体 pip 列表）。
- 路线 A 若只支持 arm64-v8a（已是本项目 ABI 基线），只加一份；但若以后想加 x86_64（模拟器）或 armeabi-v7a（老机），增量按 ABI 线性叠加。
- 路线 B 的体积成本转移到了用户手机上的插件 APK（插件自带解释器，体积同样不小），但对 movieshell 本体是零增量，且插件可被多个壳共用、摊薄。

### 维度 2：启动速度

| 项 | 路线 A：Chaquopy | 路线 B：外置插件 |
|---|---|---|
| 解释器冷启动 | `Python.start()` 约 **1.2～1.8 秒 CPU 时间**（社区实测；另有评估给出 2–5 秒量级） | 插件进程独立，壳子侧为一次跨进程调用（毫秒级） |
| 是否阻塞主线程 | 必须**手动避开**：官方文档明确支持懒加载——只在"用到 Python 时"先 `Python.isStarted()` 再 `Python.start(new AndroidPlatform(context))`，不必放在 Application.onCreate | 天然不阻塞：插件自己负责进程和解释器生命周期 |
| 首次搜片体验 | 用户第一次点"PY 源搜索"时，会有 1–2 秒的解释器初始化＋规则 import，之后常驻复用 | 插件若已在后台常驻则快；若插件进程被杀，首次调用也有冷启动，但成本在插件侧、可被多 App 摊薄 |
| 运行时开销 | 每次 Kotlin↔Python 调用走 JNI 两层（Kotlin→Chaquopy C 垫片→CPython C-API），产生大量临时包装对象，GC 压力高于纯 Kotlin | 跨进程序列化开销（intent/binder 传参＋结果），但调用频率低（搜片/详情/播放三类请求），影响可忽略 |

结论：路线 A 的冷启动是真实存在的 1–2 秒，可通过懒加载不拖累 App 启动，但每次新进程的首次 PY 调用都躲不掉；路线 B 把这个成本外包给了插件进程。

### 维度 3：hipy 兼容性

hipy 规则的实际依赖：`base.spider` 基类（纯 Python）、`requests`/urllib3/certifi（纯 Python）、部分规则用 Crypto（pycryptodome，**含原生扩展**）。

| 项 | 路线 A：Chaquopy | 路线 B：外置插件 |
|---|---|---|
| 纯 Python 库（requests 等） | ✅ `pip { install("requests") }` 在构建期从 PyPI 装进 APK，无障碍 | ✅ 插件已自带 |
| 含原生扩展的库（pycryptodome 等） | ⚠️ 必须从 **Chaquopy 官方 PyPI 仓库（chaquo.com/pypi）** 取 Android 预编译 wheel；该仓库覆盖的包有限，版本可能滞后；缺失时要自己交叉编译（NDK＋patch＋patchelf，成本高） | ✅ 插件作者已解决，用户手机上**实测跑通过 hipy** |
| hipy 基类/规则生态 | ❌ 需自己把 `base.spider` 等打包进 `src/main/python`，并验证每一类规则（drpy-py/js/py 各形态）在嵌入解释器下的行为 | ✅ 用户现有插件就是按 DsPlayer 规范跑的，hipy 兼容性是**已验证的事实**，不是待验证的假设 |
| Python 版本 | Chaquopy 17.0.0 支持 3.10–3.13（默认 3.10；3.8/3.9 已在 17.0.0 移除）。hipy 规则一般无版本敏感代码，风险低 | 由插件决定，用户已验证可用 |

- Chaquopy 版本事实（2026-10 调研）：Gradle 插件最新 **17.0.0**（支持 AGP 9.0–9.2；17.0.0 新增 Android 16KB 内存页支持，但官方提示"很多旧 wheel 在 16KB 设备上仍加载失败，建议用 Python 3.13+"——原生扩展兼容性是持续的风险点）。
- 路线 A 最大的兼容性成本不是"能不能跑"，而是"**谁来保证所有 142+ 规则在新解释器下都跑得一样**"——这是一整轮回归测试，外置路线直接继承插件作者的验证成果。

### 维度 4：维护成本

| 项 | 路线 A：Chaquopy | 路线 B：外置插件 |
|---|---|---|
| Python 版本跟随 | Chaquopy 发版即定 Python 版本范围；升级 = 升级 Gradle 插件＋重测全部规则；Python 3.x 大版本升级时部分 C 扩展要等官方 wheel | 与壳子无关，插件作者负责 |
| 64 位/系统合规 | arm64-v8a 本身合规；但 16KB 页大小（Android 15+ 新机趋势）下旧 wheel 可能 `dlopen` 失败，需跟进 Chaquopy 升级 | 插件作者负责 |
| 构建链复杂度 | 构建期 `pip install`（云构建 Actions 上要装对应 Python、配 chaquo.com/maven 仓库），构建变慢、失败面变大；AGP/gradle 版本被 Chaquopy 兼容性矩阵卡住（已有项目踩过 Gradle 9.1.0＋Chaquopy 17.0.0 不兼容的坑） | 构建链零变化 |
| 第三方依赖风险 | 无（解释器在自己包里） | ⚠️ 插件停更／签名变更／私有协议变更，壳子跟着挂；调试链路跨进程（intent 参数、binder 返回、插件日志看不到），定位问题要两边配合 |
| 调试链路 | 单进程，Kotlin/Python 堆栈都在自己手里，logcat 一条链 | 跨进程，黑盒程度高 |

## 三、推荐结论：选路线 B（外置 py 引擎插件 APK）

**一句话：用户手机上已经有一个跑通过 hipy 的 py 引擎，把验证成本、体积成本、维护成本全部外包出去，是当前阶段最划算的选择。**

理由（按权重排序）：

1. **hipy 兼容性是已验证的事实，不是待办事项。** 路线 A 最大的隐性成本是"把 142+ 条规则在新解释器里逐条跑通"，路线 B 直接继承用户现有插件的验证成果，v0.2 的 PY 支持可以只做"壳子侧协议对接"，工期从"周"降到"天"。
2. **包体积。** v0.1 的 APK 才 20MB，路线 A 直接翻 2–3 倍（50–70MB 估算），对一个影视壳是明显的下载和安装门槛；路线 B 本体零增量。
3. **构建链不添乱。** movieshell 走 GitHub Actions 云构建（本地沙箱禁进程间 TCP，Gradle daemon 起不来），路线 A 要在云构建里加 Python＋pip＋chaquo maven 仓库，还要被 AGP/Chaquopy 兼容性矩阵卡版本；路线 B 构建零变化。
4. **冷启动与进程隔离。** 解释器 1–2 秒冷启动＋常驻 60MB+ 内存占用放在插件进程里，壳子进程保持轻量；插件崩了不连带壳子崩。

路线 B 的真实风险只有一条：**对第三方插件的依赖**（停更、签名变更、私有协议变更）。应对策略见落地步骤第 4 条——协议层做隔离＋降级提示，不把插件 API 直接散落在业务代码里。

**路线 A 适合的时机（将来再看）：** 用户群体中"没装 py 引擎插件"的人占多数、且抱怨"多装一个 App 很麻烦"时；或者插件停更、私有协议断裂、不得不自研时。到时 Chaquopy 的版本和 16KB 页兼容性也更成熟了。

## 四、落地步骤草案（路线 B，只写步骤）

1. **协议侦察**：在用户手机上抓 DsPlayer 调 py 引擎插件的实际调用方式（intent action、extra 参数名、返回格式）。手段：让用户配合，或反编译插件 APK 看 manifest 里声明的 intent-filter／AIDL 接口。**注意：只看调用契约，不复制插件代码。**
2. **抽象层设计**：在 movieshell 里定义 `PyEngine` 接口（`search/loadDetail/getPlayUrl` 三个方法＋`isAvailable()` 探测），把"外置插件"实现为其中一种 `ExternalPluginEngine`，以后若走路线 A，只需再实现一个 `ChaquopyEngine`，业务层不动。
3. **可用性探测与降级**：App 启动（或进入 PY 源）时探测插件是否安装；未安装→弹引导卡片（说明去哪里装插件 APK，附官方市场链接）；插件版本过低→提示升级。
4. **容错与超时**：跨进程调用加超时（如 15 秒）、异常转义（插件崩溃/无响应→提示用户重启插件，不闪退壳子）；关键调用打日志，方便用户反馈时定位是壳子问题还是插件问题。
5. **灰度验证**：先用 btsow 的 py 规则（已有 spider，行为已知）在真机上跑通"搜索→详情→拿磁链"全链路，再逐步放开其他 PY 源；记录插件版本号，形成"已验证插件版本清单"。
6. **长期观察项**：关注插件更新日志；若插件连续两个大版本无更新或协议变更，重新评估路线 A。

## 五、数据来源

- Chaquopy 官方文档（chaquo.com/chaquopy/doc）：`Python.start()` 懒加载用法、pip block 装包方式、Python 版本设置（17.0.0 支持 3.10–3.13，默认 3.10）、AGP 9.0–9.2 支持、16KB 内存页说明。镜像：github.com/chaquo/chaquopy。
- Chaquopy 17.0.0 为 2026-08 时最新稳定版（Gradle Plugin Portal 可查：`com.chaquo.python` version 17.0.0）。
- 体积与冷启动数字：社区项目实测拆解——嵌入 CPython 约 +30–60MB/ABI；某项目 libpython ~18MB＋stdlib ~12MB＋pip 包 ~18MB，APK 19MB→68MB；`Python.start()` 冷启动约 1200–1800ms CPU。均为**社区实测估算**，非官方承诺值。
- DsPlayer 外置插件机制：基于"插件 APK＋intent/广播/AIDL 跨进程调用"的通用原理描述，未深挖其私有协议（需第 1 步实地侦察）。
