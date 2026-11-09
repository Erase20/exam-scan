@echo off
chcp 65001 >nul
rem ===== 启动后端（Spring Boot 可执行 jar，内含已构建的 dist 前端） =====
call "%~dp0env.bat"
title ExamScan - Backend

if not exist "%JAR%" (
  echo [错误] 未找到 jar：%JAR%
  echo 请先构建后端（mvn package）或确认 target 目录内容。
  pause
  exit /b 1
)

echo 启动后端 http://127.0.0.1:%BACKEND_PORT% ...
start "ExamBackend" /min "%ROOT%\run-backend.bat"

echo 已在新窗口启动，日志：logs\backend.log
echo 首次就绪约需 15 秒，请耐心等待。
timeout /t 3 /nobreak >nul
