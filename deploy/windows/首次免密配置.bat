@echo off
chcp 65001 >nul
title exam-scan 配置免密登录（只需运行一次）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-ssh-key.ps1"
echo.
echo ====== 脚本退出码 %errorlevel%（0=正常）======
echo 如遇错误，请把本窗口上方全部文字截图或复制发给 AI 助手。
pause
