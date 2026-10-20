#!/usr/bin/env python3
"""生成内置示例服务插件包 app/src/main/assets/demo-service-plugin.zip。

v0.126.0：服务插件页「示例插件」一键安装的数据来源。包结构按
xiaying-plugins/SERVER_PLUGIN_SPEC.md（server.json v1）：
根部 server.json + hello-service/server.sh。server.json 里的 sha256 是
server.sh 的实算摘要（安装时 App 按同一口径校验，不为示例开后门）。

打包可复现：zip 条目固定时间戳 (2026,1,1) + Python zipfile（禁用 7-Zip），
同一份脚本跑两次产出逐字节一致。改 server.sh 后重跑本脚本即可。
"""
import hashlib
import io
import json
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
OUT_ZIP = REPO_ROOT / "app" / "src" / "main" / "assets" / "demo-service-plugin.zip"
FIXED_DATE = (2026, 1, 1, 0, 0, 0)

# 示例服务本体：/system/bin/sh 小服务。PORT/SERVICE_PORT 由 App 启动时注入；
# 有 nc 时用 FIFO 双工模式提供极简 HTTP（/healthz 回 OK、其余回一页 HTML），
# nc 不可用/参数不兼容就打日志转心跳保活（进程存活即状态 RUNNING）。
SERVER_SH = r"""#!/system/bin/sh
# hello-service: 侠影示例服务（服务型插件演示）
# PORT/SERVICE_PORT 由侠影 App 启动时注入环境变量，只绑 127.0.0.1。
PORT="${PORT:-${SERVICE_PORT:-29578}}"
echo "[hello-service] 侠影示例服务启动，监听 127.0.0.1:$PORT，时间 $(date '+%Y-%m-%d %H:%M:%S')"

HTML='<!DOCTYPE html><html><head><meta charset="utf-8"><title>侠影示例服务</title></head><body><h1>侠影示例服务运行中</h1><p>hello-service 1.0.0 - 仅监听 127.0.0.1，这是服务型插件的演示页面。</p></body></html>'

# 处理一次请求：从 stdin 读请求行，响应写 stdout（调用方把 stdout 接到 FIFO 喂回 nc）。
serve_once() {
  IFS=' ' read -r _METHOD _PATH _REST || exit 0
  case "$_PATH" in
    *healthz*)
      printf 'HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK'
      ;;
    *)
      printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n%s' "$HTML"
      ;;
  esac
}

heartbeat() {
  echo "[hello-service] 心跳保活模式，每 5 秒一条日志（端口 $PORT 未提供 HTTP）"
  while true; do
    echo "[hello-service] heartbeat $(date '+%H:%M:%S')"
    sleep 5
  done
}

if command -v nc >/dev/null 2>&1; then
  FIFO="${TMPDIR:-.}/.hello_resp_fifo"
  rm -f "$FIFO"
  if mkfifo "$FIFO" 2>/dev/null; then
    echo "[hello-service] 使用 nc 在 127.0.0.1:$PORT 提供 HTTP（/healthz 探活）"
    # FIFO 双工：nc 的 stdin 从 FIFO 读响应，请求经管道进 serve_once，
    # serve_once 的 stdout 重定向回 FIFO——两端在启动时互相 rendezvous。
    while true; do
      nc -l -p "$PORT" 127.0.0.1 <"$FIFO" | serve_once >"$FIFO" || break
    done
    echo "[hello-service] nc 服务循环退出（参数可能不兼容），转入心跳保活"
    rm -f "$FIFO"
  else
    echo "[hello-service] 无法创建 FIFO，转入心跳保活"
  fi
else
  echo "[hello-service] 系统无 nc，跳过 HTTP，转入心跳保活"
fi
heartbeat
"""


def zip_info(name: str, executable: bool = False) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=FIXED_DATE)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3  # unix，保证 external_attr 权限位稳定
    info.external_attr = (0o755 if executable else 0o644) << 16
    return info


def main() -> None:
    sh_bytes = SERVER_SH.encode("utf-8")
    sha256 = hashlib.sha256(sh_bytes).hexdigest()
    server_json = {
        "id": "hello-service",
        "name": "示例服务",
        "version": "1.0.0",
        "workDir": "hello-service",
        "command": "server.sh",
        "port": 0,
        "healthPath": "/healthz",
        "sha256": sha256,
    }
    json_bytes = (json.dumps(server_json, ensure_ascii=False, indent=2) + "\n").encode("utf-8")

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        zf.writestr(zip_info("server.json"), json_bytes)
        zf.writestr(zip_info("hello-service/server.sh", executable=True), sh_bytes)
    data = buf.getvalue()

    OUT_ZIP.parent.mkdir(parents=True, exist_ok=True)
    OUT_ZIP.write_bytes(data)
    print(f"wrote {OUT_ZIP} ({len(data)} bytes)")
    print(f"server.sh sha256 = {sha256}")
    print(f"zip md5 = {hashlib.md5(data).hexdigest()}")


if __name__ == "__main__":
    main()
