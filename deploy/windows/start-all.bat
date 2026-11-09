@echo off
chcp 65001 >nul
setlocal
call "%~dp0env-deploy.bat"

if not exist "%APP_ROOT%\backend\exam-scan-1.0.0.jar" (
  echo [错误] 缺少 backend\exam-scan-1.0.0.jar，部署包不完整。
  pause & exit /b 1
)

rem ---- 检查 Java 21 ----
if exist "%JAVA_EXE%" goto :java_ok
where java >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Java。请安装 JDK 21，或把便携 JRE 解压到 runtime\jre
  pause & exit /b 1
)
set "JAVA_EXE=java"
:java_ok

rem ---- 检查 Python ----
if exist "%PY_EXE%" goto :py_ok
where python >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Python。请安装 Python 3.11+，或把便携 Python 解压到 runtime\python
  pause & exit /b 1
)
set "PY_EXE=python"
:py_ok

rem ---- 检查 OCR 依赖 ----
"%PY_EXE%" -c "import uvicorn, fastapi, cv2, rapidocr_onnxruntime" >nul 2>nul
if errorlevel 1 (
  echo [错误] OCR 依赖未安装或不可用，请先双击 install-ocr-deps.bat 安装。
  pause & exit /b 1
)

echo ==========================================
echo   Exam Scan - Windows 单机部署
echo   Web UI : http://localhost:%BACKEND_PORT%
echo   OCR    : http://127.0.0.1:%OCR_PORT%/health
echo ==========================================
echo.

echo [1/2] 启动 OCR 服务 ...
start "ExamOCR" /min "%APP_ROOT%\run-ocr.bat"

echo [2/2] 启动后端（已内置前端页面）...
start "ExamBackend" /min "%APP_ROOT%\run-backend.bat"

echo.
echo 等待服务就绪（约 20 秒）...
timeout /t 20 /nobreak >nul

start "" http://localhost:%BACKEND_PORT%/
echo.
echo 完成。日志在 logs\ 目录；停止请运行 stop-all.bat
pause
