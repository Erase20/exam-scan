#!/usr/bin/env bash
# 从 git 仓库直接构建并部署（阿里云 ECS 等 Linux 服务器）
# ============================================================
# 用法（服务器上，首次部署）：
#   git clone https://github.com/Erase20/exam-scan.git
#   cd exam-scan
#   bash deploy/linux/install-deps.sh       # 装 JDK21/Python/系统依赖（首次执行一次）
#   bash deploy/linux/deploy-from-git.sh    # 构建 + 组装 + 启动
#
# 以后升级：cd exam-scan && git pull && bash deploy/linux/deploy-from-git.sh
# （数据在 deploy/linux/backend/data 与 deploy/linux/data 下，升级不丢）
set -e
cd "$(dirname "$0")/../.."          # 仓库根目录
ROOT="$(pwd)"
DEPLOY_DIR="$ROOT/deploy/linux"

SUDO=""
if [ "$(id -u)" -ne 0 ]; then
  SUDO="sudo"
fi

APT=0
command -v apt-get >/dev/null 2>&1 && APT=1

# ---- 基础工具：git / curl / xz ----
for t in git curl xz; do
  if ! command -v "$t" >/dev/null 2>&1; then
    echo "[依赖] 安装 $t ..."
    if [ "$APT" -eq 1 ]; then
      $SUDO apt-get update && $SUDO apt-get install -y "$t"
    else
      $SUDO dnf install -y "$t" 2>/dev/null || $SUDO yum install -y "$t"
    fi
  fi
done

# ---- Maven（构建后端）----
if ! command -v mvn >/dev/null 2>&1; then
  echo "[依赖] 安装 Maven ..."
  if [ "$APT" -eq 1 ]; then
    $SUDO apt-get install -y maven
  else
    $SUDO dnf install -y maven 2>/dev/null || $SUDO yum install -y maven
  fi
fi
# 阿里云 Maven 镜像加速（仅在用户没有自定义 settings.xml 时写入）
if [ ! -f "$HOME/.m2/settings.xml" ]; then
  mkdir -p "$HOME/.m2"
  cat > "$HOME/.m2/settings.xml" <<'EOF'
<settings>
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <mirrorOf>central</mirrorOf>
      <name>Aliyun Maven Central</name>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
EOF
  echo "[加速] 已写入 ~/.m2/settings.xml（阿里云 Maven 镜像）"
fi

# ---- Node.js 18+（构建前端；vite 6 要求）----
node_major() { node -p "process.versions.node.split('.')[0]" 2>/dev/null || echo 0; }
if [ "$(node_major)" -lt 18 ]; then
  echo "[依赖] 安装 Node.js 20 ..."
  NODE_VER="v20.18.1"
  if [ "$APT" -eq 1 ]; then
    $SUDO apt-get install -y nodejs npm || true
  fi
  if [ "$(node_major)" -lt 18 ]; then
    # apt 版本过老（如 Ubuntu 22.04 的 nodejs 12）时，用阿里云镜像装官方二进制包
    curl -fsSL "https://mirrors.aliyun.com/nodejs-release/${NODE_VER}/node-${NODE_VER}-linux-x64.tar.xz" -o /tmp/node.tar.xz
    $SUDO mkdir -p /usr/local
    $SUDO tar -xJf /tmp/node.tar.xz -C /usr/local --strip-components=1
    rm -f /tmp/node.tar.xz
    export PATH="/usr/local/bin:$PATH"
  fi
  if [ "$(node_major)" -lt 18 ]; then
    echo "[错误] Node.js 安装失败，请手动安装 Node 18+ 后重试"
    exit 1
  fi
fi
# npm 镜像加速
npm config set registry https://registry.npmmirror.com || true

# ---- [1/4] 构建后端 jar ----
echo "[1/4] 构建后端（mvn package，首次需下载依赖，几分钟）..."
mvn -B -q -DskipTests package
if [ ! -f "$ROOT/backend/target/exam-scan-1.0.0.jar" ]; then
  echo "[错误] 后端构建失败：未生成 backend/target/exam-scan-1.0.0.jar"
  exit 1
fi

# ---- [2/4] 构建前端 ----
echo "[2/4] 构建前端（npm ci + vite build）..."
cd "$ROOT/frontend"
if [ -d node_modules ]; then
  npm run build
else
  npm ci --no-audit --no-fund && npm run build
fi
cd "$ROOT"

# ---- [3/4] 组装到 deploy/linux 期望的布局 ----
echo "[3/4] 组装部署目录 ..."
mkdir -p "$DEPLOY_DIR/backend" "$DEPLOY_DIR/frontend"
cp -f "$ROOT/backend/target/exam-scan-1.0.0.jar" "$DEPLOY_DIR/backend/"
rm -rf "$DEPLOY_DIR/frontend/dist"
cp -r "$ROOT/frontend/dist" "$DEPLOY_DIR/frontend/dist"
# OCR 服务直接链接仓库源码（venv 建在源码目录里，升级不丢）
if [ ! -e "$DEPLOY_DIR/ocr-service" ]; then
  ln -s "$ROOT/ocr-service" "$DEPLOY_DIR/ocr-service"
fi

# ---- [4/4] 启动（先停旧进程，升级场景不丢数据）----
echo "[4/4] 启动服务 ..."
bash "$DEPLOY_DIR/stop.sh" || true
cd "$DEPLOY_DIR"
bash start.sh

echo
echo "部署完成。浏览器访问：http://<服务器公网IP>:8091/"
echo "（阿里云安全组需放行 TCP 8091 入方向；OCR 8801 仅本机使用，不要放行）"
