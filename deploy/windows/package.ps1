# 生成 Windows 单机部署包：收集产物 -> deploy/windows/build/exam-scan-win -> exam-scan-win.zip
# 用法：右键“使用 PowerShell 运行”，或在 PowerShell 中执行 ./package.ps1
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = (Resolve-Path (Join-Path $here '..\..')).Path

$jar  = Join-Path $root 'backend\target\exam-scan-1.0.0.jar'
$dist = Join-Path $root 'frontend\dist'
$ocr  = Join-Path $root 'ocr-service'

if (-not (Test-Path $jar)) { throw "未找到后端 jar: $jar    请先在 backend 执行 mvn package" }
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw "未找到前端 dist: $dist    请先在 frontend 执行 npm run build" }
if (-not (Test-Path (Join-Path $ocr 'requirements.txt'))) { throw "未找到 $ocr\requirements.txt" }

$stage = Join-Path $here 'build\exam-scan-win'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# 后端
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'backend\data') | Out-Null
Copy-Item $jar (Join-Path $stage 'backend\exam-scan-1.0.0.jar') -Force

# 数据库建表脚本（切换到 MySQL 时使用）
$dbSrc = Join-Path $root 'backend\src\main\resources\db'
if (Test-Path $dbSrc) {
  Copy-Item $dbSrc (Join-Path $stage 'backend\db') -Recurse -Force
}

# 前端
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'frontend\dist') | Out-Null
Copy-Item (Join-Path $dist '*') (Join-Path $stage 'frontend\dist') -Recurse -Force

# OCR 源码 + 依赖清单（排除缓存/样例/输出）
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'ocr-service') | Out-Null
Get-ChildItem $ocr -File |
  Where-Object { $_.Extension -eq '.py' -or $_.Name -eq 'requirements.txt' } |
  Copy-Item -Destination (Join-Path $stage 'ocr-service') -Force

# 数据/日志目录（空目录打 zip 会丢，启动脚本会自动重建）
'data\storage','data\export','logs' | ForEach-Object {
  New-Item -ItemType Directory -Force -Path (Join-Path $stage $_) | Out-Null
}

# 启动脚本与说明
$files = @('env-deploy.bat','run-ocr.bat','run-backend.bat','start-all.bat','stop-all.bat','install-ocr-deps.bat','check-health.bat','README.txt')
foreach ($f in $files) {
  $src = Join-Path $here $f
  if (-not (Test-Path $src)) { throw "缺少部署脚本: $src" }
  Copy-Item $src (Join-Path $stage $f) -Force
}

# 批处理统一转 CRLF（UTF-8 无 BOM），避免 Windows 换行问题
$enc = New-Object System.Text.UTF8Encoding($false)
Get-ChildItem $stage -Filter *.bat | ForEach-Object {
  $t = [System.IO.File]::ReadAllText($_.FullName)
  $t = $t -replace "`r?`n", "`r`n"
  [System.IO.File]::WriteAllText($_.FullName, $t, $enc)
}

# 压缩（新拷贝的大 jar 可能被杀软/索引短暂占用，重试；失败则用系统 tar 兜底）
$zip = Join-Path $here 'exam-scan-win.zip'
if (Test-Path $zip) { Remove-Item $zip -Force }
Start-Sleep -Seconds 2
$ok = $false
for ($i = 1; $i -le 6; $i++) {
  try {
    Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force -ErrorAction Stop
    if ((Get-Item $zip).Length -gt 5MB) { $ok = $true; break }
    throw "压缩结果异常（文件过小）"
  } catch {
    Write-Host ("压缩第 " + $i + " 次未完成，2 秒后重试 ...")
    if (Test-Path $zip) { Remove-Item $zip -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 2
  }
}
if (-not $ok) {
  Write-Host "改用系统 tar 打包 ..."
  if (Test-Path $zip) { Remove-Item $zip -Force }
  tar -a -c -f $zip -C $stage .
}

$sizeMB = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ("部署包已生成: " + $zip + "  (" + $sizeMB + " MB)")
