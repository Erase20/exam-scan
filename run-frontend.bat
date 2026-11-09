@echo off
chcp 65001 >nul
rem 实际运行前端 dev server（由 start-*.bat 拉起，输出追加到 logs\frontend.log）
call "%~dp0env.bat"
cd /d "%ROOT%\frontend"
"%NODE_EXE%" "%VITE_JS%" --port %FRONTEND_PORT% --host 1>>"%ROOT%\logs\frontend.log" 2>>"%ROOT%\logs\frontend-err.log"
