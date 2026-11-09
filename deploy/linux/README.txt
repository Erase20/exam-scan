Exam Scan 纸质试卷智能扫描系统 —— Linux / 云服务器部署包
============================================================

一、包内组成
  backend/exam-scan-1.0.0.jar   后端主程序（页面由 frontend/dist 托管）
  backend/data/                 H2 数据库目录（首次启动自动生成）
  backend/db/                   MySQL 建表脚本（schema-mysql.sql / schema-h2.sql）
  frontend/dist/                前端静态页面
  ocr-service/                  OCR 服务源码 + requirements.txt
  data/storage/                 上传图片与切图
  data/export/                  导出文件
  logs/                         运行日志
  run/                          PID 文件
  env.sh                        环境变量（端口、JVM 内存）
  install-deps.sh               安装系统与 Python 依赖（首次执行）
  start.sh / stop.sh            启动 / 停止
  status.sh                     状态检查
  install-service.sh            注册 systemd 开机自启（可选）
  systemd/                      systemd 服务模板

二、环境要求
  - Linux x86_64（Ubuntu 22.04/24.04、Debian、Rocky 9 等）
  - JDK 21（必须）
  - Python 3.11+（建议 3.12/3.13）
  - 内存建议 2 核 4G 起（OCR 较吃内存）
  - 首次安装依赖需联网

三、首次安装（4 步）
  1) 上传并解压到 /opt：
       cd /opt
       tar -xzf exam-scan-linux.tar.gz
       cd exam-scan
       chmod +x *.sh

  2) 安装依赖（JDK 21、Python3、libGL、OCR Python 包）：
       bash install-deps.sh
     Ubuntu 22.04 官方源没有 openjdk-21，若提示安装失败，用 Adoptium：
       sudo apt install -y wget gpg apt-transport-https
       sudo mkdir -p /etc/apt/keyrings
       wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
       echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
       sudo apt update && sudo apt install -y temurin-21-jdk

  3) 启动：
       bash start.sh

  4) 浏览器访问：
       http://<服务器公网IP>:8091/

四、日常操作
  启动：bash start.sh
  停止：bash stop.sh
  状态：bash status.sh（Backend/OCR 均返回 200 为正常）
  日志：logs/backend.log、logs/backend-err.log、logs/ocr.log、logs/ocr-err.log

五、开机自启（推荐生产使用）
  bash install-service.sh
  之后用 systemctl 管理：
    systemctl status exam-scan --no-pager
    systemctl restart exam-scan
    systemctl stop exam-ocr exam-scan
  注意：使用 systemd 时不要再执行 start.sh，避免重复启动。

六、安全组与防火墙
  - 入方向放行 TCP 8091（Web 访问）
  - 不要放行 8801（OCR 只监听 127.0.0.1，供后端本机调用）
  - 服务器本机防火墙（如 ufw）：
      sudo ufw allow 8091/tcp

七、修改端口
  方式一（临时）：
    BACKEND_PORT=9091 OCR_PORT=9801 bash start.sh
  方式二（永久）：编辑 env.sh 中
    OCR_PORT="${OCR_PORT:-8801}"
    BACKEND_PORT="${BACKEND_PORT:-8091}"
  改完 stop.sh 后重新 start.sh；若用 systemd，改完重新执行 install-service.sh。

八、数据备份与升级
  备份（先停止服务）：
    backend/data/    数据库
    data/storage/    图片与切图
    data/export/     导出结果
  升级：
    bash stop.sh
    覆盖 backend/exam-scan-1.0.0.jar 与 frontend/dist/
    bash start.sh
  数据库与图片目录保持不变。

九、切换到 MySQL 8（可选）
  1) 在 MySQL 执行 backend/db/schema-mysql.sql 建库建表；
  2) 启动参数追加（可写入 env.sh 或 systemd 的 ExecStart）：
       --spring.profiles.active=mysql
       --spring.datasource.url=jdbc:mysql://127.0.0.1:3306/exam_scan?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
       --spring.datasource.username=root
       --spring.datasource.password=你的密码
  3) 重启。mysql profile 不会自动建表，必须先用脚本建表。

十、常见问题
  - 启动报 “Unsupported class file major version”：Java 不是 21，装 JDK 21 并确认 `java -version`。
  - 提示未找到 java / python3：先执行 install-deps.sh，或设置 JAVA_HOME。
  - OCR 依赖安装失败：确认 Python 3.11+，并已安装 libgl1、libglib2.0-0（Rocky：mesa-libGL、glib2）。
  - 页面 404：确认 frontend/dist/index.html 存在，且未改动 start.sh 中的 static-locations。
  - 上传识别失败：看 logs/ocr-err.log 与 logs/backend-err.log，确认 8801 端口正常。
  - 内存不足/OOM：调小 env.sh 中 JAVA_OPTS（如 -Xmx768m），或升级到 4G 内存。
  - 云安全组已放行仍打不开：检查服务器本机防火墙（ufw/firewalld）与 8091 监听地址。

十一、验证清单
  [ ] bash status.sh：Backend 200、OCR 200
  [ ] 浏览器打开 http://<IP>:8091/ 看到首页
  [ ] 上传一张试卷图片能识别出题目
  [ ] 题库页“配图”列有缩略图
  [ ] 组卷详情有配图，导出 Word 内含图片
