#!/usr/bin/env bash
# 查看运行状态（HTTP 200 为正常）
cd "$(dirname "$0")"
source ./env.sh

code() {
  curl -s -o /dev/null -w "%{http_code}" "$1" 2>/dev/null || echo "000"
}

echo "Backend : $(code "http://127.0.0.1:$BACKEND_PORT/")   (http://<服务器IP>:$BACKEND_PORT/)"
echo "OCR     : $(code "http://127.0.0.1:$OCR_PORT/health")"
[ -f "$PID_DIR/backend.pid" ] && echo "backend PID: $(cat "$PID_DIR/backend.pid")"
[ -f "$PID_DIR/ocr.pid" ] && echo "ocr PID    : $(cat "$PID_DIR/ocr.pid")"
