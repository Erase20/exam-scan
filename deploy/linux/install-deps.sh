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
  # Alibaba Cloud Linux 3 / RHEL8 系：java-21-openjdk 与 python3.11 都在官方源中
  $SUDO dnf install -y java-21-openjdk python3 python3-pip unzip curl mesa-libGL glib2 \
    || echo "[提示] 部分包安装失败，可单独重试"
  # 默认 python3 是 3.6，OCR 依赖需要 3.9+，补装 python3.11
  $SUDO dnf install -y python3.11 python3.11-pip \
    || echo "[提示] python3.11 安装失败，请执行: dnf install -y python3.11 python3.11-pip"
elif command -v yum >/dev/null 2>&1; then
  echo "使用 yum 安装依赖 ..."
  $SUDO yum install -y java-21-openjdk python3 python3-pip unzip curl mesa-libGL glib2
else
  echo "[警告] 未识别的包管理器，请手动安装 JDK 21、Python 3.11+、curl、libGL"
fi

echo "创建 Python 虚拟环境并安装 OCR 依赖（首次较慢）..."
# OCR 依赖要求 Python 3.9+（推荐 3.11+）；自动挑选可用的解释器
PY=""
for cand in python3.13 python3.12 python3.11 python3.10 python3.9 python3; do
  if command -v "$cand" >/dev/null 2>&1; then
    v="$("$cand" -c 'import sys;print("%d.%d" % sys.version_info[:2])' 2>/dev/null)"
    mj="${v%%.*}"; mn="${v#*.}"
    if [ "$mj" -eq 3 ] && [ "$mn" -ge 9 ] 2>/dev/null; then
      PY="$cand"
      break
    fi
  fi
done
if [ -z "$PY" ]; then
  echo "[错误] 未找到 Python 3.9+ 的解释器（当前 python3 版本过低，无法安装 OCR 依赖）"
  echo "  老系统（如 CentOS 7，python3=3.6）需先编译安装 Python 3.11："
  echo "    yum install -y gcc make openssl-devel bzip2-devel libffi-devel zlib-devel xz-devel sqlite-devel wget"
  echo "    cd /tmp && wget https://mirrors.huaweicloud.com/python/3.11.9/Python-3.11.9.tgz"
  echo "    tar -xzf Python-3.11.9.tgz && cd Python-3.11.9"
  echo "    ./configure >/dev/null && make -j\$(nproc) && make altinstall"
  echo "    # 装完重新执行本脚本（会自动选用 python3.11）"
  echo "  更省事的做法：换用 Ubuntu 22.04/24.04 或 Rocky 9 系统镜像（自带 Python 3.10+）"
  exit 1
fi
echo "使用解释器: $PY ($("$PY" -V 2>&1))"
# 旧虚拟环境若是低版本 Python 创建的（早期失败遗留），与所选解释器不符时删除重建
if [ -x "$OCR_DIR/.venv/bin/python" ]; then
  venv_ver="$("$OCR_DIR/.venv/bin/python" -c 'import sys;print("%d.%d" % sys.version_info[:2])' 2>/dev/null)"
  want_ver="$("$PY" -c 'import sys;print("%d.%d" % sys.version_info[:2])' 2>/dev/null)"
  if [ "$venv_ver" != "$want_ver" ]; then
    echo "[修复] 已存在的虚拟环境是 Python $venv_ver（与 $PY 不符），删除重建..."
    rm -rf "$OCR_DIR/.venv"
  fi
fi
if [ ! -x "$OCR_DIR/.venv/bin/python" ]; then
  "$PY" -m venv "$OCR_DIR/.venv"
fi
# 云服务器在国内时 pypi.org 较慢，优先使用阿里云镜像
PIP_MIRROR="https://mirrors.aliyun.com/pypi/simple/"
"$OCR_DIR/.venv/bin/pip" install -U pip -i "$PIP_MIRROR" || "$OCR_DIR/.venv/bin/pip" install -U pip
"$OCR_DIR/.venv/bin/pip" install -r "$OCR_DIR/requirements.txt" -i "$PIP_MIRROR" \
  || "$OCR_DIR/.venv/bin/pip" install -r "$OCR_DIR/requirements.txt"

echo
echo "依赖安装完成。下一步：bash start.sh"
