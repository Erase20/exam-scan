@echo off
chcp 65001 >nul
rem ===== Exam Scan 停止全部服务 =====
call "%~dp0env.bat"
title ExamScan - Stop

echo 停止前端 (%FRONTEND_PORT%) ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%FRONTEND_PORT%" ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1

echo 停止前端 esbuild / vite 子进程 ...
taskkill /F /FI "WINDOWTITLE eq ExamFrontend*" >nul 2>&1

echo 停止后端 (%BACKEND_PORT%) ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%BACKEND_PORT%" ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq ExamBackend*" >nul 2>&1

echo 停止 OCR 服务 (%OCR_PORT%) ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%OCR_PORT%" ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq ExamOCR*" >nul 2>&1

echo.
echo 全部已停止。
timeout /t 3 /nobreak >nul
