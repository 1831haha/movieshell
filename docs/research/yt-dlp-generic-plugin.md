# yt-dlp 通用提取器插件立项（2026-10-24 调研）

## 结论

插件形态（市场插件包），全量 yt-dlp 包 + wrapper 域名黑名单，不做提取器裁剪。
不走 APK 内 pip 内置：插件形态 = 用户按需安装 + 可独立于 App 发版更新，符合
“持续维护更新”；APK 内置会让所有用户多背 3MB 且只能跟 App 发版走。

## 已核实的机制

- Chaquopy pip 是构建时打进 APK（app/build.gradle.kts chaquopy pip 块）：
  requests、beautifulsoup4、pyquery、**pycryptodome**、lxml、Pillow、urllib3、
  chardet、idna、certifi 已内置。PY 源的 AES（Crypto）可直接用，无需另装。
- py-deps 插件先例（xiaying-plugins/packages/py-deps-1.0.0.zip）：zip 内
  `pylibs/` 目录放纯 Python 包（+ 一个 arm64 .so），ChaquopyEngine 启动时把
  pylibs 加进 sys.path（进程级一次）。yt-dlp 插件走同一机制。
- Chaquopy 17 + yt-dlp 在 Android 上有先例（LinkLift），纯 Python 包无编译问题。

## 为什么不裁剪提取器

- 实测 yt-dlp 2026.10.21：wheel 3,188,012 字节，解开 10,688,243 字节，
  extractor 模块 972 个。
- `yt_dlp/extractor/__init__.py` 会 import 全部提取器模块；删掉油管/推特/IG
  等模块会让 import 断链，除非同时改写 `__init__`——每次上游更新都要重做
  手术，脆弱。正确做法是全量包 + wrapper 层拦截，功能上绝不触碰禁用的站。

## Wrapper 设计（待实现）

- 插件包结构：`pylibs/yt_dlp/...`（全量）+ `pylibs/yt_generic.py`（wrapper）。
- `yt_generic.resolve(url, timeout) -> str`：
  1. host 黑名单先行拦截（youtube.com、youtu.be、twitter.com、x.com、
     instagram.com、twitch.tv 及其子域名），命中直接返回空串，yt-dlp 碰不到
     这些地址；
  2. `YoutubeDL({'quiet': True, 'noplaylist': True, ...}).extract_info(url,
     download=False)`，取合并格式或最优直链（优先含音视频的 mp4/m3u8）；
  3. 任何异常返回空串，不抛。
- 接入点（已实现，0.203.0）：ParseApi 新增 type=99 解析线路，复用现有
  解析线路基础设施，用户在解析设置里按需选用，默认关闭；插件未装时线路
  返回空串不熔断。手动粘贴地址解析作为第二入口备选（未做）。
- 版本钉选：2026.10.21（本次实测）；维护节奏每月一次：跟进上游新版，重打
  插件包，changelog 注明钉选版本。
- 许可证：yt-dlp 为 Unlicense（实施前以仓库 LICENSE 文件复核）；插件包内
  保留原项目 LICENSE 与来源说明，不改名洗稿。
