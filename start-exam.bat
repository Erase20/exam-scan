@echo off
chcp 65001 >nul
rem ===== Exam Scan 一键启动：OCR(8801) + 后端(8091) + 前端(5173) =====
call "%~dp0env.bat"
title ExamScan - Start All

echo ==========================================
echo   Exam Scan System
echo   OCR     : http://127.0.0.1:%OCR_PORT%
echo   Backend : http://127.0.0.1:%BACKEND_PORT%/api
echo   Web UI  : http://localhost:%FRONTEND_PORT%
echo ==========================================
echo.

if not exist "%JAR%" (
  echo [错误] 未找到 jar：%JAR%，无法启动后端。
  pause
  exit /b 1
)

echo [1/3] OCR 服务 ...
start "ExamOCR" /min "%ROOT%\run-ocr.bat"

echo [2/3] 后端 ...
start "ExamBackend" /min "%ROOT%\run-backend.bat"

echo [3/3] 前端 ...
if not exist "%VITE_JS%" (
  echo       跳过：未找到 vite（请在 frontend 目录执行 npm install），可改用后端内置页面 http://127.0.0.1:%BACKEND_PORT%/
) else (
  start "ExamFrontend" /min "%ROOT%\run-frontend.bat"
)

echo.
echo 等待服务就绪（约 20 秒）...
timeout /t 20 /nobreak >nul

echo --- 健康检查 ---
curl.exe -s -o nul -w "OCR     : %%{http_code}\n" http://127.0.0.1:%OCR_PORT%/health
curl.exe -s -o nul -w "Backend : %%{http_code}\n" http://127.0.0.1:%BACKEND_PORT%/
curl.exe -s -o nul -w "Frontend: %%{http_code}\n" http://127.0.0.1:%FRONTEND_PORT%/

echo.
start "" http://localhost:%FRONTEND_PORT%
echo 完成。关闭本窗口不会影响服务；停止请运行 stop-exam.bat
pause
