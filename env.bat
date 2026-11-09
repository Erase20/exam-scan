@echo off
rem ===== Exam Scan - 公共环境变量（供各 start 脚本 call） =====
rem 当前机器实际路径：无独立 JDK/Maven，使用 JetBrains Runtime 的 java 直接启动已构建的 jar

rem 项目根目录（去掉结尾的反斜杠，便于拼接 URL 形式的路径）
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"

rem Java：优先 WorkBuddy JDK21，缺失则回退到 PyCharm JBR / PATH 里的 java
set "JAVA_EXE=C:\Users\Administrator\.workbuddy\binaries\tools\jdk-21.0.2\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=C:\Program Files\JetBrains\PyCharm 2025.2.4\jbr\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

rem Python（OCR 服务）：优先 examscan venv（含 rapidocr），缺失则回退
set "PY_EXE=C:\Users\Administrator\.workbuddy\binaries\python\envs\examscan\Scripts\python.exe"
if not exist "%PY_EXE%" set "PY_EXE=C:\Users\Administrator\AppData\Local\Programs\Python\Python313\python.exe"
if not exist "%PY_EXE%" set "PY_EXE=python"

rem Node（前端 dev server）
set "NODE_EXE=C:\Program Files\nodejs\node.exe"
if not exist "%NODE_EXE%" set "NODE_EXE=node"

set "VITE_JS=%ROOT%\frontend\node_modules\vite\bin\vite.js"
set "JAR=%ROOT%\backend\target\exam-scan-1.0.0.jar"

set "OCR_PORT=8801"
set "BACKEND_PORT=8091"
set "FRONTEND_PORT=5173"

rem 数据库 profile：本机未装 MySQL，使用内置 H2（数据在 backend\data\examscan.mv.db）
rem 以后安装了 MySQL（127.0.0.1:3306/exam_scan, root/123456）可改回 mysql
set "SPRING_PROFILE=h2"

if not exist "%ROOT%\logs" mkdir "%ROOT%\logs"
