@echo off
chcp 65001 >nul
rem ===== 启动 OCR 服务（Python + RapidOCR） =====
call "%~dp0env.bat"
title ExamScan - OCR Service

echo 启动 OCR 服务 http://127.0.0.1:%OCR_PORT% ...
start "ExamOCR" /min "%ROOT%\run-ocr.bat"

echo 已在新窗口启动，日志：logs\ocr.log / logs\ocr-err.log
echo 健康检查：浏览器打开 http://127.0.0.1:%OCR_PORT%/health
timeout /t 5 /nobreak >nul
