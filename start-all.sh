#!/bin/bash
# Exam Scan - 一键启动全部服务（Git Bash）
# 本机环境：无独立 JDK/Maven，直接用 JetBrains Runtime 的 java 启动已构建的 jar

ROOT="$(cd "$(dirname "$0")" && pwd)"
JAVA_EXE="C:/Program Files/JetBrains/PyCharm 2025.2.4/jbr/bin/java.exe"
[ -x "$JAVA_EXE" ] || JAVA_EXE="java"
PY_EXE="C:/Users/Administrator/AppData/Local/Programs/Python/Python313/python.exe"
[ -x "$PY_EXE" ] || PY_EXE="python"
NODE_EXE="C:/Program Files/nodejs/node.exe"
[ -x "$NODE_EXE" ] || NODE_EXE="node"

JAR="$ROOT/backend/target/exam-scan-1.0.0.jar"
VITE_JS="$ROOT/frontend/node_modules/vite/bin/vite.js"
mkdir -p "$ROOT/logs"

echo "[1/3] 启动 OCR 服务 (8801)..."
cd "$ROOT/ocr-service"
"$PY_EXE" -m uvicorn app:app --host 127.0.0.1 --port 8801 \
  >"$ROOT/logs/ocr.log" 2>"$ROOT/logs/ocr-err.log" &
sleep 3

echo "[2/3] 启动后端 (8091，内含已构建的前端)..."
if [ ! -f "$JAR" ]; then
  echo "  未找到 jar: $JAR，请先构建后端"
else
  cd "$ROOT/backend"
  "$JAVA_EXE" -Dfile.encoding=UTF-8 -jar "$JAR" \
    --server.port=8091 \
    --exam-scan.storage.root="$ROOT/data/storage" \
    --exam-scan.export.dir="$ROOT/data/export" \
    --exam-scan.ocr.engine=local \
    --exam-scan.ocr.base-url=http://127.0.0.1:8801 \
    --spring.web.resources.static-locations="file:$ROOT/frontend/dist/,classpath:/static/" \
    >"$ROOT/logs/backend.log" 2>"$ROOT/logs/backend-err.log" &
fi

echo "[3/3] 启动前端 dev server (5173)..."
if [ ! -f "$VITE_JS" ]; then
  echo "  未找到 vite，请先在 frontend 目录执行 npm install；也可直接访问 http://127.0.0.1:8091/"
else
  cd "$ROOT/frontend"
  "$NODE_EXE" "$VITE_JS" --port 5173 --host \
    >"$ROOT/logs/frontend.log" 2>"$ROOT/logs/frontend-err.log" &
fi

echo ""
echo "启动完成："
echo "  Web UI  : http://localhost:5173"
echo "  Backend : http://127.0.0.1:8091/api"
echo "  OCR     : http://127.0.0.1:8801/health"
wait
