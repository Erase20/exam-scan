@echo off
chcp 65001 >nul
rem 实际运行后端（由 start-all.bat 拉起），日志写入 logs\
rem H2 库默认落在 backend\data；上传图片与导出走根目录 data\
call "%~dp0env-deploy.bat"
cd /d "%APP_ROOT%\backend"
"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar exam-scan-1.0.0.jar --server.port=%BACKEND_PORT% --spring.profiles.active=mysql "--exam-scan.storage.root=%APP_ROOT_FWD%/data/storage" "--exam-scan.export.dir=%APP_ROOT_FWD%/data/export" --exam-scan.ocr.engine=local --exam-scan.ocr.base-url=http://127.0.0.1:%OCR_PORT% "--spring.web.resources.static-locations=file:%APP_ROOT_FWD%/frontend/dist/,classpath:/static/" 1>>"%APP_ROOT%\logs\backend.log" 2>>"%APP_ROOT%\logs\backend-err.log"
