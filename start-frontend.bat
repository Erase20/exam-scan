@echo off
chcp 65001 >nul
rem ===== 启动前端开发服务器（Vite） =====
call "%~dp0env.bat"
title ExamScan - Frontend

if not exist "%VITE_JS%" (
  echo [错误] 未找到 vite：%VITE_JS%
  echo 请先在 frontend 目录执行 npm install。
  pause
  exit /b 1
)

echo 启动前端 http://localhost:%FRONTEND_PORT% ...
start "ExamFrontend" /min "%ROOT%\run-frontend.bat"

echo 已在新窗口启动，日志：logs\frontend.log
timeout /t 3 /nobreak >nul
