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
# 优先用 systemd 托管（崩溃自动拉起、服务器重启自动恢复）；单元不存在时回退 nohup
if systemctl list-unit-files 2>/dev/null | grep -q '^exam-ocr\.service'; then
  echo "[1/2] 启动 OCR (systemd 托管, 崩溃自愈) ..."
  systemctl restart exam-ocr
elif is_running "$PID_DIR/ocr.pid"; then
  echo "[跳过] OCR 已在运行 (PID $(cat "$PID_DIR/ocr.pid"))"
else
  echo "[1/2] 启动 OCR ($OCR_PORT) ..."
  cd "$OCR_DIR"
  nohup "$PY_EXE" -m uvicorn app:app --host 127.0.0.1 --port "$OCR_PORT" \
    >> "$LOG_DIR/ocr.log" 2>> "$LOG_DIR/ocr-err.log" &
  echo $! > "$PID_DIR/ocr.pid"
  cd "$APP_ROOT"
fi

# ---- HTTPS 证书：手机拍照实时取景（绿色线框）依赖浏览器摄像头权限，仅 HTTPS 可用 ----
HTTPS_STORE="$STORAGE_DIR/https.p12"
KEYTOOL="$(dirname "$JAVA_EXE")/keytool"
command -v "$KEYTOOL" >/dev/null 2>&1 || KEYTOOL=keytool
if command -v "$KEYTOOL" >/dev/null 2>&1 && [ ! -f "$HTTPS_STORE" ]; then
  echo "[HTTPS] 生成自签证书 $HTTPS_STORE ..."
  mkdir -p "$STORAGE_DIR"
  "$KEYTOOL" -genkeypair -alias examscan -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=exam-scan" -keystore "$HTTPS_STORE" -storetype PKCS12 -storepass examscan \
    >/dev/null 2>&1 || echo "[HTTPS] 证书生成失败，手机拍照取景暂不可用"
fi
HTTPS_ARGS=""
if [ -f "$HTTPS_STORE" ]; then
  HTTPS_ARGS="--exam-scan.https.enabled=true --exam-scan.https.keystore=$HTTPS_STORE --exam-scan.https.password=examscan"
  echo "[HTTPS] 已启用手机拍照取景端口 https://<服务器IP>:8443/（自签证书，浏览器提示时选「继续访问」）"
fi

# ---- 后端 ----
if is_running "$PID_DIR/backend.pid"; then
  echo "[跳过] 后端已在运行 (PID $(cat "$PID_DIR/backend.pid"))"
else
  echo "[2/2] 启动后端 ($BACKEND_PORT) ..."
  cd "$APP_ROOT/backend"
  nohup "$JAVA_EXE" $JAVA_OPTS -Dfile.encoding=UTF-8 -jar "$JAR" \
    --server.port="$BACKEND_PORT" \
    --spring.profiles.active="${PROFILE:-h2}" \
    --exam-scan.storage.root="$STORAGE_DIR" \
    --exam-scan.export.dir="$EXPORT_DIR" \
    --exam-scan.ocr.engine=local \
    --exam-scan.ocr.base-url="http://127.0.0.1:$OCR_PORT" \
    $HTTPS_ARGS \
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
    if [ -f "$HTTPS_STORE" ]; then
      echo "  手机拍照: https://<服务器IP>:8443/（取景线框需 HTTPS，自签证书请选继续访问）"
    fi
    echo "  OCR    : http://127.0.0.1:$OCR_PORT/health（仅本机）"
    echo "  日志   : $LOG_DIR"
    exit 0
  fi
  sleep 2
done

echo "[警告] 90 秒内未就绪，请查看 $LOG_DIR/backend-err.log 与 $LOG_DIR/ocr-err.log"
exit 1
