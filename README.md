# 纸质试卷智能扫描识别系统（Exam Scan）

一套完整的试卷数字化解决方案：扫描 → 图文分区 → OCR 识别 → 按题型切题入库 → 搜题 → 智能组卷 → 生成答题卡 → 扫描阅卷。

## 技术栈

| 层 | 技术 | 端口 |
|---|---|---|
| 前端 | Vue 3 + Vite + Element Plus | 5173 |
| 后端 | Spring Boot 3.3 + MyBatis-Plus 3.5 | 8090 |
| OCR 服务 | Python FastAPI + RapidOCR(PP-OCRv4) + OpenCV | 8801 |
| 数据库 | 默认 H2（免安装），生产切换 MySQL 8 | - |

## 快速启动

```bash
# 一键启动（Git Bash）
bash start-all.sh

# 或分别启动
start-ocr.bat       # 1. OCR 服务 (8801)
start-backend.bat   # 2. Spring Boot 后端 (8090)
start-frontend.bat  # 3. 前端开发服务器 (5173)
```

打开 http://localhost:5173 即可使用。首次启动自动导入 27 道演示题。

> 不启动 OCR 服务也能用：后端自动降级为演示模式（Mock 数据模拟识别结果）。

## 九大功能对应实现

1. **图文区域划分** — `ocr-service/layout.py`：倾斜校正 + 自适应二值化 + 形态学分析，区分 TEXT / IMAGE / TABLE 区块，前端可视化叠加显示
2. **OCR 识别** — RapidOCR（PP-OCRv4 中英文模型），单页约 3 秒，可插拔引擎（`OcrClient` 支持 local / cloud / mock）
3. **按题型分类** — `QuestionSplitter.java`：大题标题上下文 + 题干特征 + 选项检测，识别选择/填空/判断/计算/解答/作图/实验 7 类题型
4. **存入本地数据库** — H2 文件库开箱即用；MySQL 脚本在 `backend/src/main/resources/db/schema-mysql.sql`，切换只需 `--spring.profiles.active=mysql`
5. **搜题** — 关键词/题型/学科/难度/知识点多维筛选 + 基于 2-gram TF-IDF 余弦相似度的相似题推荐（粘贴题干即可搜题）
6. **组卷（可设分值）** — 按题型配置题量与每题分值，自动选题（难度梯度抽样）、总分校验、卷内逐题调分、导出 Word
7. **解答功能** — 题目详情页展示答案与解析，支持人工编辑校正 OCR 结果
8. **生成答题卡** — 依据试卷自动排版：四角定位块 + 考号涂卡区 + 客观题涂点 + 主观题答题框，可预览/下载 PNG（300dpi A4）
9. **扫描阅卷** — 四点透视校正（纯 Java 单应矩阵实现）→ 涂点填充率识别 → 自动判分（含多涂/空白检测、判断题√×映射）→ 主观题人工复核

## 目录结构

```
exam-scan/
├── backend/          # Spring Boot 后端（含全部业务模块）
│   └── src/main/java/com/examscan/modules/
│       ├── scan/       扫描识别（上传/OCR编排/切题/入库）
│       ├── question/   题库与搜题
│       ├── paper/      组卷与导出
│       ├── answer/     答题卡生成
│       └── grading/    阅卷判分
├── frontend/         # Vue3 前端（6 个页面）
├── ocr-service/      # Python OCR 服务（版面分析 + RapidOCR）
├── data/             # 运行时数据（H2 库、上传图片、切图）
└── start-*.sh/bat    # 启动脚本
```

## 切换 MySQL

1. 执行 `db/schema-mysql.sql` 建库
2. 修改 `application-mysql.yml` 中的账号密码
3. 启动参数加 `--spring.profiles.active=mysql`

## 已验证的端到端测试

- 上传试卷图片 → 版面分区（3 文字块 + 1 图片块，3 秒）→ 切出 6 题 → 题型分类正确 → 入库
- 自动组卷 21 题 96 分，缺题时给出警告提示
- 生成答题卡 → 模拟填涂 → 扫描阅卷：考号 `20240108` 识别正确，10 道客观题判分全部正确（对 6 错 4）
- Word 导出（试卷/含答案版）
