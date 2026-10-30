# CI 门槛与固定签名（Q4）

## 门槛

`.github/workflows/build.yml` 在打包前先跑 `gradle :app:compileReleaseKotlin :app:lintRelease`：

- Kotlin 编译失败 → job 红；
- lint 有**新增**错误 → job 红（`app/build.gradle.kts` 的 `lint { abortOnError = true }`）。
  存量问题记在 `app/lint-baseline.xml`，不会让第一天就红。
- lint 报告作为 `lint-report` 工件上传（失败时也传）。
- 确认存量问题可接受、需要重建基线时：`gradle :app:updateLintBaseline`，把新的
  `app/lint-baseline.xml` 一起提交。

## 签名：一次性设置（仓库所有者手动做，只做一次）

1. 生成正式 keystore（在自己电脑上，**不要提交到仓库**，并离线备份；丢了就再也发不了同签名的更新）：

   ```bash
   keytool -genkeypair -v \
     -keystore movieshell-release.jks \
     -alias movieshell \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -dname "CN=MovieShell, OU=Dev, O=MovieShell, C=CN"
   ```

   按提示设置 store 密码和 key 密码（记下来）。

2. 转成单行 base64：

   ```bash
   base64 -w0 movieshell-release.jks > movieshell-release.jks.b64   # Linux
   base64 -i movieshell-release.jks | tr -d '\n' > movieshell-release.jks.b64   # macOS
   ```

3. GitHub 仓库 → **Settings > Secrets and variables > Actions > New repository secret**，添加 4 个：

   | Secret 名称 | 值 |
   | --- | --- |
   | `SIGNING_KEYSTORE_BASE64` | `movieshell-release.jks.b64` 的全部内容 |
   | `SIGNING_STORE_PASSWORD` | 第 1 步设的 store 密码 |
   | `SIGNING_KEY_ALIAS` | `movieshell`（第 1 步 `-alias` 的值） |
   | `SIGNING_KEY_PASSWORD` | 第 1 步设的 key 密码 |

4. 删掉本地的 `.b64` 临时文件。

## Secrets 缺失时的行为

- `push` 到 main、手动触发（workflow_dispatch）：**直接失败**，日志写明缺哪几个 Secret；不会回退随机 key。
- `pull_request`（fork 来的 PR 拿不到 Secrets）：照常过编译 + lint 门槛，产出**未签名**包
  `app-release-unsigned.apk`，只用于验证，不能直接安装。

## 本地构建

`app/build.gradle.kts` 从 Gradle 属性（`-P`）或同名环境变量读取：
`SIGNING_STORE_FILE`（keystore 路径）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。
四项不全时 `gradle assembleRelease` 出未签名包。

## 首次切换到固定签名

用户手机上现有的包都是旧流程每轮随机 key 签的。第一个固定 key 签名的包签名不同，
**需要先卸载一次旧包再安装**（卸载会清掉 App 本地数据，先在设置里备份）。之后同 key 的包可直接覆盖升级。
