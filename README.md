# movieshell（侠影）

Android 视频壳子：drpy（内置 V8 引擎）、PY（内置运行时）、PHP（CLI 引擎）等多引擎站源播放器。

## 本地构建

本仓库没有 `gradlew`（只有 `gradle/wrapper/gradle-wrapper.properties`，其中已锁定 gradle 8.7）。
本地构建请直接用系统 gradle 8.7，例如：

```sh
gradle :app:assembleRelease
```

不要凭空补一个 `gradlew` 壳脚本——没有 wrapper jar 的假入口只会误导构建。

云构建走 GitHub Actions（见 `.github/workflows/build.yml`）。

## 版本档案

每个版本的改动见 `docs/changelog/vX.Y.Z.md`，与 versionName 同步。
