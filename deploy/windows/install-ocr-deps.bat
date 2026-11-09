@echo off
chcp 65001 >nul
setlocal
call "%~dp0env-deploy.bat"

echo 首次部署需安装 OCR 依赖（仅需执行一次，需联网）...
if exist "%PY_EXE%" goto :py_ok
where python >nul 2>nul
if errorlevel 1 (
  echo [错误] 未找到 Python。请安装 Python 3.11+，或把便携 Python 解压到 runtime\python
  pause & exit /b 1
)
set "PY_EXE=python"
:py_ok

cd /d "%APP_ROOT%\ocr-service"
"%PY_EXE%" -m pip install --upgrade pip
"%PY_EXE%" -m pip install -r requirements.txt
if errorlevel 1 (
  echo.
  echo [失败] 依赖安装出错，请检查网络连接或 Python 版本（建议 3.11+）。
  pause & exit /b 1
)
echo.
echo 安装完成。
pause
