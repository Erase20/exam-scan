@echo off
chcp 65001 >nul
rem 实际运行后端（由 start-*.bat 拉起，输出追加到 logs\backend.log）
call "%~dp0env.bat"
cd /d "%ROOT%\backend"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%JAR%" --server.port=%BACKEND_PORT% --spring.profiles.active=%SPRING_PROFILE% --exam-scan.storage.root="%ROOT%\data\storage" --exam-scan.export.dir="%ROOT%\data\export" --exam-scan.ocr.engine=local --exam-scan.ocr.base-url=http://127.0.0.1:%OCR_PORT% "--spring.web.resources.static-locations=file:%ROOT%/frontend/dist/,classpath:/static/" 1>>"%ROOT%\logs\backend.log" 2>>"%ROOT%\logs\backend-err.log"
