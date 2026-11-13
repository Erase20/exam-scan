@echo off
chcp 65001 >nul
rem 实际运行后端（由 start-*.bat 拉起，输出追加到 logs\backend.log）
call "%~dp0env.bat"

rem 防双开：端口已被占用说明后端已在运行，直接退出。
rem 多个后端进程并发写同一个数据库文件会导致数据状态错乱（历史教训），务必单实例运行。
netstat -ano | findstr /R /C:":%BACKEND_PORT% .*LISTENING" >nul 2>nul
if %errorlevel%==0 (
  echo [提示] 端口 %BACKEND_PORT% 已被占用，后端疑似已在运行。请勿重复启动！
  echo        如确认无运行实例，可运行 stop-exam.bat 后再启动。
  exit /b 0
)

cd /d "%ROOT%\backend"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --server.port=%BACKEND_PORT% --spring.profiles.active=%SPRING_PROFILE% --exam-scan.storage.root="%ROOT%\data\storage" --exam-scan.export.dir="%ROOT%\data\export" --exam-scan.ocr.engine=local --exam-scan.ocr.base-url=http://127.0.0.1:%OCR_PORT% "--spring.web.resources.static-locations=file:%ROOT%/frontend/dist/,classpath:/static/" 1>>"%ROOT%\logs\backend.log" 2>>"%ROOT%\logs\backend-err.log"
