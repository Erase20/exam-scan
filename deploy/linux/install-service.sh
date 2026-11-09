#!/usr/bin/env bash
# 注册 systemd 服务并设置开机自启（可选，推荐生产环境使用）
set -e
cd "$(dirname "$0")"
source ./env.sh

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

for s in exam-ocr exam-scan; do
  sed "s#@APP_ROOT@#$APP_ROOT#g; s#@JAVA_EXE@#$JAVA_EXE#g; s#@PY_EXE@#$PY_EXE#g; s#@BACKEND_PORT@#$BACKEND_PORT#g; s#@OCR_PORT@#$OCR_PORT#g; s#@JAVA_OPTS@#$JAVA_OPTS#g" \
    "systemd/$s.service" | $SUDO tee "/etc/systemd/system/$s.service" >/dev/null
done

$SUDO systemctl daemon-reload
$SUDO systemctl enable --now exam-ocr exam-scan
echo "已安装并启动：exam-ocr / exam-scan"
echo "查看状态：systemctl status exam-scan --no-pager"
