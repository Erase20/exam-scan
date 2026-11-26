@echo off
chcp 65001 >nul
title exam-scan 修复免密登录
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0fix-pubkey.ps1"
echo.
echo ====== 脚本退出码 %errorlevel%（0=正常）======
pause
