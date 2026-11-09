#!/usr/bin/env bash
# 启动 OCR + 后端（后台运行），日志写入 logs/
set -e
cd "$(dirname "$0")"
source ./env.sh

if [ ! -f "$JAR" ]; then
  echo "[错误] 缺少 $JAR，部署包不完整"
  exit 1
fi
if [ ! -f "$DIST/index.html" ]; then
  echo "[错误] 缺少 $DIST/index.html，部署包不完整"
  exit 1
fi
if ! command -v "$JAVA_EXE" >/dev/null 2>&1; then
  echo "[错误] 未找到 java，请安装 JDK 21（或设置 JAVA_HOME）"
  exit 1
fi
if ! command -v "$PY_EXE" >/dev/null 2>&1; then
  echo "[错误] 未找到 python3，请安装 Python 3.11+"
  exit 1
fi

is_running() {
  [ -f "$1" ] && kill -0 "$(cat "$1")" 2>/dev/null
}

# ---- OCR ----
if is_running "$PID_DIR/ocr.pid"; then
  echo "[跳过] OCR 已在运行 (PID $(cat "$PID_DIR/ocr.pid"))"
else
  echo "[1/2] 启动 OCR ($OCR_PORT) ..."
  cd "$OCR_DIR"
  nohup "$PY_EXE" -m uvicorn app:app --host 127.0.0.1 --port "$OCR_PORT" \
    >> "$LOG_DIR/ocr.log" 2>> "$LOG_DIR/ocr-err.log" &
  echo $! > "$PID_DIR/ocr.pid"
  cd "$APP_ROOT"
fi

# ---- 后端 ----
if is_running "$PID_DIR/backend.pid"; then
  echo "[跳过] 后端已在运行 (PID $(cat "$PID_DIR/backend.pid"))"
else
  echo "[2/2] 启动后端 ($BACKEND_PORT) ..."
  cd "$APP_ROOT/backend"
  nohup "$JAVA_EXE" $JAVA_OPTS -Dfile.encoding=UTF-8 -jar "$JAR" \
    --server.port="$BACKEND_PORT" \
    --exam-scan.storage.root="$STORAGE_DIR" \
    --exam-scan.export.dir="$EXPORT_DIR" \
    --exam-scan.ocr.engine=local \
    --exam-scan.ocr.base-url="http://127.0.0.1:$OCR_PORT" \
    "--spring.web.resources.static-locations=file:$DIST/,classpath:/static/" \
    >> "$LOG_DIR/backend.log" 2>> "$LOG_DIR/backend-err.log" &
  echo $! > "$PID_DIR/backend.pid"
  cd "$APP_ROOT"
fi

# ---- 等待就绪 ----
if ! command -v curl >/dev/null 2>&1; then
  echo "未安装 curl，跳过健康检查；请直接访问 http://<服务器IP>:$BACKEND_PORT/"
  exit 0
fi

echo "等待后端就绪（最多 90 秒）..."
for _ in $(seq 1 45); do
  if curl -fsS "http://127.0.0.1:$BACKEND_PORT/api/scan/health" >/dev/null 2>&1; then
    echo "启动成功："
    echo "  Web UI : http://<服务器IP>:$BACKEND_PORT/"
    echo "  OCR    : http://127.0.0.1:$OCR_PORT/health（仅本机）"
    echo "  日志   : $LOG_DIR"
    exit 0
  fi
  sleep 2
done

echo "[警告] 90 秒内未就绪，请查看 $LOG_DIR/backend-err.log 与 $LOG_DIR/ocr-err.log"
exit 1
