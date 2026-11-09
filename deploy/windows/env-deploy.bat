@echo off
rem ===== Exam Scan Windows 单机部署 - 环境变量（相对本目录，自动探测运行时） =====
set "APP_ROOT=%~dp0"
if "%APP_ROOT:~-1%"=="\" set "APP_ROOT=%APP_ROOT:~0,-1%"
rem 供 Java 使用的正斜杠绝对路径（避免 file: URL 反斜杠问题）
set "APP_ROOT_FWD=%APP_ROOT:\=/%"

rem 可选：把便携版 JRE 解压到 runtime\jre，即可免安装 JDK
set "JAVA_EXE=%APP_ROOT%\runtime\jre\bin\java.exe"
if not exist "%JAVA_EXE%" if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"

rem 可选：把便携版 Python 解压到 runtime\python，即可免安装 Python
set "PY_EXE=%APP_ROOT%\runtime\python\python.exe"
if not exist "%PY_EXE%" set "PY_EXE=python"

set "OCR_PORT=8801"
set "BACKEND_PORT=8091"

if not exist "%APP_ROOT%\logs" mkdir "%APP_ROOT%\logs"
if not exist "%APP_ROOT%\backend\data" mkdir "%APP_ROOT%\backend\data"
if not exist "%APP_ROOT%\data\storage" mkdir "%APP_ROOT%\data\storage"
if not exist "%APP_ROOT%\data\export" mkdir "%APP_ROOT%\data\export"
