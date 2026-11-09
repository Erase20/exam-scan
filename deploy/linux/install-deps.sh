#!/usr/bin/env bash
# 安装系统依赖 + OCR Python 依赖（首次部署执行一次，需联网）
set -e
cd "$(dirname "$0")"
source ./env.sh

# git 仓库布局（直接 clone 部署）时，ocr-service 在仓库根目录而不是 deploy/linux 下
if [ ! -f "$OCR_DIR/requirements.txt" ] && [ -f "$APP_ROOT/../../ocr-service/requirements.txt" ]; then
  OCR_DIR="$APP_ROOT/../../ocr-service"
fi

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

if command -v apt-get >/dev/null 2>&1; then
  echo "使用 apt 安装依赖 ..."
  $SUDO apt-get update
  $SUDO apt-get install -y openjdk-21-jdk python3 python3-venv python3-pip unzip curl libgl1 libglib2.0-0 \
    || echo "[提示] openjdk-21-jdk 安装失败：Ubuntu 22.04 请按 README 用 Adoptium 源安装 JDK 21"
elif command -v dnf >/dev/null 2>&1; then
  echo "使用 dnf 安装依赖 ..."
  $SUDO dnf install -y java-21-openjdk python3 python3-pip unzip curl mesa-libGL glib2
elif command -v yum >/dev/null 2>&1; then
  echo "使用 yum 安装依赖 ..."
  $SUDO yum install -y java-21-openjdk python3 python3-pip unzip curl mesa-libGL glib2
else
  echo "[警告] 未识别的包管理器，请手动安装 JDK 21、Python 3.11+、curl、libGL"
fi

echo "创建 Python 虚拟环境并安装 OCR 依赖（首次较慢）..."
if [ ! -x "$OCR_DIR/.venv/bin/python" ]; then
  python3 -m venv "$OCR_DIR/.venv"
fi
# 云服务器在国内时 pypi.org 较慢，优先使用阿里云镜像
PIP_MIRROR="https://mirrors.aliyun.com/pypi/simple/"
"$OCR_DIR/.venv/bin/pip" install -U pip -i "$PIP_MIRROR" || "$OCR_DIR/.venv/bin/pip" install -U pip
"$OCR_DIR/.venv/bin/pip" install -r "$OCR_DIR/requirements.txt" -i "$PIP_MIRROR" \
  || "$OCR_DIR/.venv/bin/pip" install -r "$OCR_DIR/requirements.txt"

echo
echo "依赖安装完成。下一步：bash start.sh"
