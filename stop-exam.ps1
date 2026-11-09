# Exam Scan 停止全部服务
# 用法：powershell -ExecutionPolicy Bypass -File stop-exam.ps1

$ErrorActionPreference = 'SilentlyContinue'

function Stop-Port($port, $name) {
  Write-Host "停止 $name ($port) ..."
  $conns = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach ($c in $conns) {
    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
  }
}

Stop-Port 5173 '前端'
Stop-Port 8091 '后端'
Stop-Port 8801 'OCR 服务'

Write-Host '全部已停止。' -ForegroundColor Cyan
Start-Sleep -Seconds 2
