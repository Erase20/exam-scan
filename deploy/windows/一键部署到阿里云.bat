@echo off
chcp 65001 >nul
title exam-scan 一键部署到阿里云
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy-aliyun.ps1"
