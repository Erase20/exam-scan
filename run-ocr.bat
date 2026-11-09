@echo off
chcp 65001 >nul
rem 实际运行 OCR 服务（由 start-*.bat 拉起，输出追加到 logs\ocr.log）
call "%~dp0env.bat"
cd /d "%ROOT%\ocr-service"
"%PY_EXE%" -m uvicorn app:app --host 127.0.0.1 --port %OCR_PORT% 1>>"%ROOT%\logs\ocr.log" 2>>"%ROOT%\logs\ocr-err.log"
