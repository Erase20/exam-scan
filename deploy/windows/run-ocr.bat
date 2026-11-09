@echo off
chcp 65001 >nul
rem 实际运行 OCR 服务（由 start-all.bat 拉起），日志写入 logs\
call "%~dp0env-deploy.bat"
cd /d "%APP_ROOT%\ocr-service"
"%PY_EXE%" -m uvicorn app:app --host 127.0.0.1 --port %OCR_PORT% 1>>"%APP_ROOT%\logs\ocr.log" 2>>"%APP_ROOT%\logs\ocr-err.log"
