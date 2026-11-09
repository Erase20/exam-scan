#!/usr/bin/env bash
# 公共环境变量（启动/停止/状态脚本共用，可按需修改）
APP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

JAR="$APP_ROOT/backend/exam-scan-1.0.0.jar"
DIST="$APP_ROOT/frontend/dist"
OCR_DIR="$APP_ROOT/ocr-service"
VENV_PY="$OCR_DIR/.venv/bin/python"

# 端口，可用环境变量覆盖：BACKEND_PORT=9091 ./start.sh
OCR_PORT="${OCR_PORT:-8801}"
BACKEND_PORT="${BACKEND_PORT:-8091}"

# Java：优先 JAVA_HOME，其次 PATH 中的 java
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE="java"
fi

# Python：优先包内 venv
if [ -x "$VENV_PY" ]; then
  PY_EXE="$VENV_PY"
else
  PY_EXE="python3"
fi

# JVM 内存，小内存服务器可调小
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx1024m}"

DATA_DIR="$APP_ROOT/data"
STORAGE_DIR="$DATA_DIR/storage"
EXPORT_DIR="$DATA_DIR/export"
LOG_DIR="$APP_ROOT/logs"
PID_DIR="$APP_ROOT/run"

mkdir -p "$STORAGE_DIR" "$EXPORT_DIR" "$LOG_DIR" "$PID_DIR" "$APP_ROOT/backend/data"
