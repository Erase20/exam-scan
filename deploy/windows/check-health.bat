@echo off
chcp 65001 >nul
setlocal
call "%~dp0env-deploy.bat"

echo 健康检查（200 表示正常）：
curl.exe -s -o nul -w "OCR     : %%{http_code}\n" http://127.0.0.1:%OCR_PORT%/health
curl.exe -s -o nul -w "Backend : %%{http_code}\n" http://127.0.0.1:%BACKEND_PORT%/
pause
