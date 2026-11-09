Exam Scan 纸质试卷智能扫描系统 —— Windows 单机部署包
============================================================

一、包内组成
  backend\exam-scan-1.0.0.jar   后端主程序（页面由 frontend\dist 托管）
  backend\data\                 H2 数据库目录（首次启动自动生成 examscan.mv.db）
  backend\db\                   MySQL 建表脚本（schema-mysql.sql / schema-h2.sql）
  frontend\dist\                前端静态页面（由后端同源托管）
  ocr-service\                  OCR 服务源码 + requirements.txt
  data\storage\                 上传的试卷图片与切图
  data\export\                  导出文件
  logs\                         运行日志
  env-deploy.bat                环境变量与端口配置
  start-all.bat / stop-all.bat  一键启动 / 停止
  install-ocr-deps.bat          安装 OCR Python 依赖
  check-health.bat              健康检查

二、适用环境
  - Windows 10 / 11 64 位
  - JDK 21（必须，低版本无法启动）
  - Python 3.11+（建议 3.12 / 3.13）
  - 首次安装 OCR 依赖需联网

  可选（离线/免安装）：
    runtime\jre     放入便携版 JRE 21（含 bin\java.exe）
    runtime\python  放入便携版 Python（含 python.exe）
  两者存在时脚本优先使用，无需系统安装。

三、安装前检查（建议先做）
  1) Java 版本必须是 21：
       java -version
     输出应类似 openjdk version "21.0.x"。
  2) Python 版本 3.11+：
       python --version
  3) 端口 8091 / 8801 未被占用：
       netstat -ano | findstr ":8091"
       netstat -ano | findstr ":8801"

四、首次安装（3 步）
  1) 双击 install-ocr-deps.bat，等待 pip 安装完成（仅需一次，需联网）
  2) 双击 start-all.bat
  3) 等待约 20 秒，浏览器自动打开 http://localhost:8091
     若未自动打开，手动访问该地址；首次启动 OCR 模型加载较慢，可多等 10-30 秒再刷新。

  停止：双击 stop-all.bat
  体检：双击 check-health.bat（OCR / Backend 均返回 200 即正常）

五、局域网访问
  - 本机访问：http://localhost:8091
  - 其他电脑：http://<本机IP>:8091/
  - 放行防火墙（管理员 PowerShell 执行一次）：
      netsh advfirewall firewall add rule name="ExamScan 8091" dir=in action=allow protocol=TCP localport=8091
  - OCR 仅监听 127.0.0.1:8801，不需要对外开放。

六、数据与备份
  停止服务后，整体拷贝以下目录即可迁移/备份：
    backend\data\    数据库
    data\storage\    图片与切图
    data\export\     导出结果
  注意：H2 运行中数据库文件被锁，务必先 stop-all.bat。

七、升级版本
  1) 双击 stop-all.bat
  2) 备份 backend\data 与 data\
  3) 用新包覆盖 backend\exam-scan-1.0.0.jar 与 frontend\dist\
  4) 双击 start-all.bat
  数据库与图片目录保持不变，无需重新初始化。

八、修改端口
  记事本打开 env-deploy.bat，修改：
    set "OCR_PORT=8801"
    set "BACKEND_PORT=8091"
  保存后先 stop-all.bat，再 start-all.bat。

九、不部署 OCR（演示模式）
  记事本打开 run-backend.bat，把
    --exam-scan.ocr.engine=local
  改为
    --exam-scan.ocr.engine=mock
  此模式用模拟数据，不做真实识别，仅用于查看界面。

十、切换到 MySQL 8（可选）
  1) 在 MySQL 执行 backend\db\schema-mysql.sql 建库建表；
  2) 在 run-backend.bat 的 java 命令中追加启动参数：
       --spring.profiles.active=mysql
       --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/exam_scan?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
       --spring.datasource.username=root
       --spring.datasource.password=你的密码
  3) 重启后端。mysql profile 不会自动建表，必须先用脚本建表。

十一、常见问题
  - 启动报 “Unsupported class file major version”：Java 不是 21，安装 JDK 21 或放入 runtime\jre。
  - 提示未找到 Java / Python：安装对应运行时，或放入 runtime\jre / runtime\python。
  - 提示 OCR 依赖未安装：先运行 install-ocr-deps.bat（需联网）。
  - 页面能打开但识别失败：查看 logs\ocr-err.log、logs\backend-err.log，确认 8801 未被占用。
  - 页面 404 或样式丢失：确认 frontend\dist\index.html 存在，且未改动 run-backend.bat 的 static-locations。
  - 组卷导出 Word 没有图片：确认题库页“配图”列有缩略图，且 data\storage\crop 下文件存在。
  - 端口被占用：改 env-deploy.bat 端口后重启。
  - 首次启动访问失败：OCR 模型加载 + 后端启动约 20-40 秒，稍等刷新。

十二、验证清单（安装完成后）
  [ ] check-health.bat：OCR 200、Backend 200
  [ ] 打开 http://localhost:8091 能看到首页
  [ ] 上传一张试卷图片能识别出题目
  [ ] 题库页“配图”列能看到缩略图
  [ ] 自定义组卷后“试卷详情”能看到配图
  [ ] 导出 Word 试卷中图片存在
