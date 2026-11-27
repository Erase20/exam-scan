#!/usr/bin/env bash
# 停止后端与 OCR
cd "$(dirname "$0")"
source ./env.sh

stop_one() {
  name="$1"
  pidfile="$2"
  if [ -f "$pidfile" ]; then
    pid="$(cat "$pidfile" 2>/dev/null)"
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
      for _ in $(seq 1 10); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
      done
      kill -9 "$pid" 2>/dev/null || true
      echo "已停止 $name (PID $pid)"
    fi
    rm -f "$pidfile"
  fi
}

stop_one "后端" "$PID_DIR/backend.pid"

# OCR：systemd 托管时用 systemctl 停（避免 systemctl 又自动拉起与手动 kill 打架）
if systemctl list-unit-files 2>/dev/null | grep -q '^exam-ocr\.service'; then
  systemctl stop exam-ocr 2>/dev/null || true
else
  stop_one "OCR" "$PID_DIR/ocr.pid"
fi

# 兜底：PID 文件丢失时按进程特征清理
pkill -f "exam-scan-1.0.0.jar" 2>/dev/null || true
pkill -f "uvicorn app:app" 2>/dev/null || true

echo "完成。"
