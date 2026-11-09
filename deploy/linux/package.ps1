# 生成 Linux 部署包：收集产物 -> deploy/linux/build/exam-scan -> exam-scan-linux.tar.gz
# 用法：powershell -ExecutionPolicy Bypass -File .\package.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path

$jar  = Join-Path $root 'backend\target\exam-scan-1.0.0.jar'
$dist = Join-Path $root 'frontend\dist'
$ocr  = Join-Path $root 'ocr-service'

if (-not (Test-Path $jar)) { throw "未找到后端 jar: $jar    请先构建后端" }
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw "未找到前端 dist: $dist    请先执行 npm run build" }
if (-not (Test-Path (Join-Path $ocr 'requirements.txt'))) { throw "未找到 $ocr\requirements.txt" }

$stageRoot = Join-Path $here 'build'
$stage = Join-Path $stageRoot 'exam-scan'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 后端
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'backend\data') | Out-Null
Copy-Item $jar (Join-Path $stage 'backend\exam-scan-1.0.0.jar') -Force
$dbSrc = Join-Path $root 'backend\src\main\resources\db'
if (Test-Path $dbSrc) {
  Copy-Item $dbSrc (Join-Path $stage 'backend\db') -Recurse -Force
}

# 前端
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'frontend\dist') | Out-Null
Copy-Item (Join-Path $dist '*') (Join-Path $stage 'frontend\dist') -Recurse -Force

# OCR 源码与依赖清单
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'ocr-service') | Out-Null
Get-ChildItem $ocr -File |
  Where-Object { $_.Extension -eq '.py' -or $_.Name -eq 'requirements.txt' } |
  Copy-Item -Destination (Join-Path $stage 'ocr-service') -Force

# 运行目录
'data\storage','data\export','logs','run' | ForEach-Object {
  New-Item -ItemType Directory -Force -Path (Join-Path $stage $_) | Out-Null
}

# 脚本与说明
$files = @('env.sh','start.sh','stop.sh','status.sh','install-deps.sh','install-service.sh','README.txt')
foreach ($f in $files) {
  $src = Join-Path $here $f
  if (-not (Test-Path $src)) { throw "缺少部署脚本: $src" }
  Copy-Item $src (Join-Path $stage $f) -Force
}
Copy-Item (Join-Path $here 'systemd') (Join-Path $stage 'systemd') -Recurse -Force

# 统一 LF，避免 CRLF 导致 bash 报 “bad interpreter”
$enc = New-Object System.Text.UTF8Encoding($false)
Get-ChildItem $stage -Recurse -File | Where-Object {
  $_.Extension -in @('.sh', '.service') -or $_.Name -eq 'README.txt'
} | ForEach-Object {
  $t = [System.IO.File]::ReadAllText($_.FullName)
  $t = $t -replace "`r`n", "`n"
  [System.IO.File]::WriteAllText($_.FullName, $t, $enc)
}

# 打包（用 Python 生成 tar.gz，修正 .sh 可执行位；不依赖系统 tar）
$tgz = Join-Path $here 'exam-scan-linux.tar.gz'
if (Test-Path $tgz) { Remove-Item $tgz -Force }
$py = Get-Command python -ErrorAction SilentlyContinue
if (-not $py) { throw "未找到 python，无法生成 tar.gz" }
& python (Join-Path $here 'make_tar.py') $stageRoot 'exam-scan' $tgz
if ($LASTEXITCODE -ne 0) { throw "生成 tar.gz 失败" }

$sizeMB = [math]::Round((Get-Item $tgz).Length / 1MB, 1)
Write-Host ("Linux 部署包已生成: " + $tgz + "  (" + $sizeMB + " MB)")
