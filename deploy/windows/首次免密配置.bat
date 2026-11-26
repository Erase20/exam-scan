@echo off
chcp 65001 >nul
title exam-scan 配置免密登录（只需运行一次）
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-ssh-key.ps1"
