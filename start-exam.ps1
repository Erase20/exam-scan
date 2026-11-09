# Exam Scan 一键启动：OCR(8801) + 后端(8091) + 前端(5173)
# 用法：powershell -ExecutionPolicy Bypass -File start-exam.ps1

$ErrorActionPreference = 'Continue'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir = Join-Path $Root 'logs'
if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir | Out-Null }

Write-Host '==========================================' -ForegroundColor Cyan
Write-Host '  Exam Scan System' -ForegroundColor Cyan
Write-Host '  OCR     : http://127.0.0.1:8801'
Write-Host '  Backend : http://127.0.0.1:8091/api'
Write-Host '  Web UI  : http://localhost:5173'
Write-Host '==========================================' -ForegroundColor Cyan

if (-not (Test-Path (Join-Path $Root 'backend\target\exam-scan-1.0.0.jar'))) {
  Write-Host "[错误] 未找到 backend\target\exam-scan-1.0.0.jar" -ForegroundColor Red
  exit 1
}

Write-Host '[1/3] 启动 OCR 服务 ...' -ForegroundColor Yellow
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'run-ocr.bat' -WorkingDirectory $Root -WindowStyle Minimized

Write-Host '[2/3] 启动后端 ...' -ForegroundColor Yellow
Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'run-backend.bat' -WorkingDirectory $Root -WindowStyle Minimized

Write-Host '[3/3] 启动前端 ...' -ForegroundColor Yellow
if (Test-Path (Join-Path $Root 'frontend\node_modules\vite\bin\vite.js')) {
  Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'run-frontend.bat' -WorkingDirectory $Root -WindowStyle Minimized
} else {
  Write-Host '       跳过：未找到 vite，请先 npm install；也可直接访问 http://127.0.0.1:8091/' -ForegroundColor DarkYellow
}

Write-Host "`n等待服务就绪（约 25 秒）..." -ForegroundColor Gray
Start-Sleep -Seconds 25

foreach ($t in @(
  @{ Name = 'OCR     '; Url = 'http://127.0.0.1:8801/health' },
  @{ Name = 'Backend '; Url = 'http://127.0.0.1:8091/' },
  @{ Name = 'Frontend'; Url = 'http://127.0.0.1:5173/' }
)) {
  try {
    $r = Invoke-WebRequest -Uri $t.Url -UseBasicParsing -TimeoutSec 5
    Write-Host ("{0}: {1}" -f $t.Name, $r.StatusCode) -ForegroundColor Green
  } catch {
    Write-Host ("{0}: 无响应（查看 {1} 日志）" -f $t.Name, $LogDir) -ForegroundColor Red
  }
}

Write-Host "`n完成。停止请执行 stop-exam.ps1 或 stop-exam.bat" -ForegroundColor Cyan
Start-Process 'http://localhost:5173'
