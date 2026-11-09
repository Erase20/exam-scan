@echo off
chcp 65001 >nul
setlocal
call "%~dp0env-deploy.bat"

echo 正在停止后端 (%BACKEND_PORT%) 与 OCR (%OCR_PORT%) ...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%BACKEND_PORT%" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>nul
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":%OCR_PORT%" ^| findstr "LISTENING"') do taskkill /f /pid %%p >nul 2>nul
echo 已停止。
pause
