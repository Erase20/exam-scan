-- ============================================================
--  纸质试卷扫描识别与智能组卷系统  MySQL 8.x 建表脚本
--  charset: utf8mb4, engine: InnoDB
-- ============================================================
CREATE DATABASE IF NOT EXISTS exam_scan
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE exam_scan;

-- ------------------------------------------------------------
-- 1. 扫描文档（一份上传的试卷 / 答题卡原件）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS scan_doc;
CREATE TABLE scan_doc (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_name       VARCHAR(200) NOT NULL                COMMENT '文档名称',
    doc_type       VARCHAR(20)  NOT NULL DEFAULT 'PAPER' COMMENT '类型: PAPER试卷 ANSWER_SHEET答题卡',
    subject        VARCHAR(50)           DEFAULT NULL   COMMENT '学科',
    grade          VARCHAR(50)           DEFAULT NULL   COMMENT '年级',
    source_file    VARCHAR(500)          DEFAULT NULL   COMMENT '原始文件存储路径',
    page_count     INT          NOT NULL DEFAULT 1      COMMENT '页数',
    status         VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED' COMMENT '状态: UPLOADED已上传 PARSED已解析 PARSED_FAILED解析失败',
    ocr_engine     VARCHAR(30)           DEFAULT NULL   COMMENT 'OCR引擎标识',
    parse_ms       BIGINT                DEFAULT 0      COMMENT '解析耗时(毫秒)',
    remark         VARCHAR(500)          DEFAULT NULL   COMMENT '备注',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_doc_status (status),
    KEY idx_doc_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描文档';

-- ------------------------------------------------------------
-- 2. 页面与版面区块（图文区域划分结果）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS scan_page;
CREATE TABLE scan_page (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    doc_id         BIGINT      NOT NULL                COMMENT '所属文档',
    page_no        INT         NOT NULL                COMMENT '页码(从1开始)',
    image_path     VARCHAR(500)         DEFAULT NULL   COMMENT '页面图像路径',
    width          INT                  DEFAULT 0      COMMENT '图像宽(px)',
    height         INT                  DEFAULT 0      COMMENT '图像高(px)',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_page_doc (doc_id, page_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描页面';

DROP TABLE IF EXISTS scan_block;
CREATE TABLE scan_block (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    doc_id         BIGINT      NOT NULL                COMMENT '所属文档',
    page_id        BIGINT      NOT NULL                COMMENT '所属页面',
    page_no        INT         NOT NULL                COMMENT '页码冗余',
    block_type     VARCHAR(20) NOT NULL                COMMENT '区域类型: TEXT文字 IMAGE图片 TABLE表格 FORMULA公式',
    x              INT         NOT NULL DEFAULT 0      COMMENT '左上角x',
    y              INT         NOT NULL DEFAULT 0      COMMENT '左上角y',
    w              INT         NOT NULL DEFAULT 0      COMMENT '宽',
    h              INT         NOT NULL DEFAULT 0      COMMENT '高',
    sort_no        INT         NOT NULL DEFAULT 0      COMMENT '阅读顺序',
    content        TEXT                                 COMMENT '文字内容(OCR结果)',
    confidence     DECIMAL(5,4)         DEFAULT NULL   COMMENT '平均置信度',
    image_path     VARCHAR(500)         DEFAULT NULL   COMMENT '图片区块裁剪后的图像路径',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_block_page (page_id, sort_no),
    KEY idx_block_doc (doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='版面区块(图文分区)';

-- ------------------------------------------------------------
-- 3. 知识点
-- ------------------------------------------------------------
DROP TABLE IF EXISTS knowledge_point;
CREATE TABLE knowledge_point (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    name           VARCHAR(120) NOT NULL               COMMENT '知识点名称',
    subject        VARCHAR(50)          DEFAULT NULL   COMMENT '学科',
    grade          VARCHAR(50)          DEFAULT NULL   COMMENT '年级',
    parent_id      BIGINT      NOT NULL DEFAULT 0      COMMENT '父知识点(0为根)',
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_kp_name (name, subject, grade)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点';

-- ------------------------------------------------------------
-- 4. 题目（核心表）
-- ------------------------------------------------------------
-- 已有库升级（不用 DROP 重建时执行）:
-- ALTER TABLE question ADD COLUMN section_order INT DEFAULT 0 COMMENT '大题序号';
-- ALTER TABLE question ADD COLUMN section_title VARCHAR(100) COMMENT '大题标题';
-- ALTER TABLE question ADD COLUMN seq INT DEFAULT 0 COMMENT '卷内连续序号';
-- 「课序」已并入「课号」：旧库升级时执行以下两句（迁移整数课序后删除废弃列）
-- ALTER TABLE question ADD COLUMN course_no VARCHAR(64) DEFAULT NULL COMMENT '课号(课程编号)';
-- UPDATE question SET course_no = CAST(lesson_order AS CHAR) WHERE (course_no IS NULL OR course_no = '') AND lesson_order IS NOT NULL;
-- ALTER TABLE question DROP COLUMN lesson_order;
DROP TABLE IF EXISTS question;
CREATE TABLE question (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    doc_id         BIGINT                DEFAULT NULL   COMMENT '来源扫描文档',
    block_id       BIGINT                DEFAULT NULL   COMMENT '来源版面区块',
    page_no        INT                   DEFAULT NULL   COMMENT '来源页码',
    q_no           VARCHAR(20)           DEFAULT NULL   COMMENT '原卷题号, 如 1 / 12 / 三.2',
    q_type         VARCHAR(20)  NOT NULL DEFAULT 'OTHER' COMMENT '题型: CHOICE选择 FILL填空 JUDGE判断 CALC计算 ANSWER解答 DRAW作图 EXPERIMENT实验 OTHER其他',
    section_order  INT                   DEFAULT 0      COMMENT '大题序号(一/二/三 -> 1/2/3), 0 表示未识别到题型大类',
    section_title  VARCHAR(100)          DEFAULT NULL   COMMENT '大题标题, 如 填空题',
    seq            INT                   DEFAULT 0      COMMENT '卷内连续序号(按大题顺序+小题号排序)',
    course_no      VARCHAR(64)           DEFAULT NULL   COMMENT '课号(课程编号, 字符串, 可为空), 即原来的课序',
    stem           TEXT         NOT NULL                COMMENT '题干文本',
    options        TEXT                                 COMMENT '选项JSON: [{"key":"A","content":"..."}]',
    answer         TEXT                                 COMMENT '参考答案',
    analysis       TEXT                                 COMMENT '解析',
    difficulty     TINYINT      NOT NULL DEFAULT 3      COMMENT '难度 1-5',
    score          DECIMAL(5,1) NOT NULL DEFAULT 5.0    COMMENT '默认分值',
    subject        VARCHAR(50)           DEFAULT NULL   COMMENT '学科',
    grade          VARCHAR(50)           DEFAULT NULL   COMMENT '年级',
    knowledge      VARCHAR(255)          DEFAULT NULL   COMMENT '知识点, 逗号分隔',
    has_image      TINYINT      NOT NULL DEFAULT 0      COMMENT '是否含图片',
    image_path     VARCHAR(500)          DEFAULT NULL   COMMENT '题目配图路径',
    source         VARCHAR(50)  NOT NULL DEFAULT 'SCAN' COMMENT '来源: SCAN扫描 MANUAL手工 IMPORT导入',
    status         VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态: NORMAL正常 REVIEW待校对',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_q_type (q_type),
    KEY idx_q_subject_grade (subject, grade),
    KEY idx_q_difficulty (difficulty),
    KEY idx_q_doc (doc_id),
    FULLTEXT KEY ft_q_stem (stem) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='题目库';

-- ------------------------------------------------------------
-- 5. 试卷（组卷结果）
-- ------------------------------------------------------------
DROP TABLE IF EXISTS paper;
CREATE TABLE paper (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    title          VARCHAR(200) NOT NULL               COMMENT '试卷标题',
    subject        VARCHAR(50)           DEFAULT NULL,
    grade          VARCHAR(50)           DEFAULT NULL,
    total_score    DECIMAL(6,1) NOT NULL DEFAULT 0      COMMENT '总分',
    duration       INT          NOT NULL DEFAULT 90     COMMENT '考试时长(分钟)',
    question_count INT          NOT NULL DEFAULT 0      COMMENT '题目数量',
    paper_type     VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '组卷方式: AUTO自动 MANUAL手工',
    config_json    TEXT                                 COMMENT '组卷配置快照JSON',
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT草稿 PUBLISHED已发布',
    remark         VARCHAR(500)          DEFAULT NULL,
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_paper_create (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷';

DROP TABLE IF EXISTS paper_item;
CREATE TABLE paper_item (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    paper_id       BIGINT      NOT NULL                COMMENT '所属试卷',
    question_id    BIGINT      NOT NULL                COMMENT '题目ID',
    section_no     INT         NOT NULL DEFAULT 1      COMMENT '大题序号(一/二/三)',
    section_name   VARCHAR(100)         DEFAULT NULL   COMMENT '大题名称, 如 单项选择题',
    q_type         VARCHAR(20) NOT NULL                COMMENT '题型冗余',
    sort_no        INT         NOT NULL DEFAULT 0      COMMENT '卷内顺序',
    score          DECIMAL(5,1) NOT NULL DEFAULT 5.0   COMMENT '本卷分值',
    PRIMARY KEY (id),
    KEY idx_item_paper (paper_id, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试卷题目明细';

-- ------------------------------------------------------------
-- 6. 答题卡模板
-- ------------------------------------------------------------
DROP TABLE IF EXISTS answer_sheet;
CREATE TABLE answer_sheet (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    paper_id       BIGINT                DEFAULT NULL   COMMENT '对应试卷',
    title          VARCHAR(200) NOT NULL               COMMENT '答题卡标题',
    layout_json    LONGTEXT                             COMMENT '答题卡布局JSON(定位点/信息区/客观题区/主观题区)',
    page_count     INT          NOT NULL DEFAULT 2      COMMENT '页数',
    marker_count   INT          NOT NULL DEFAULT 4      COMMENT '定位标记点数量',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_sheet_paper (paper_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答题卡模板';

-- ------------------------------------------------------------
-- 7. 学生 & 阅卷记录
-- ------------------------------------------------------------
DROP TABLE IF EXISTS student;
CREATE TABLE student (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    name           VARCHAR(50) NOT NULL                COMMENT '姓名',
    student_no     VARCHAR(40) NOT NULL                COMMENT '学号/考号',
    class_name     VARCHAR(60)          DEFAULT NULL   COMMENT '班级',
    grade          VARCHAR(50)          DEFAULT NULL,
    create_time    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stu_no (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生';

DROP TABLE IF EXISTS grade_sheet;
CREATE TABLE grade_sheet (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    sheet_id       BIGINT                DEFAULT NULL   COMMENT '答题卡模板ID',
    paper_id       BIGINT       NOT NULL               COMMENT '试卷ID',
    student_no     VARCHAR(40)  NOT NULL               COMMENT '识别出的考号',
    class_name     VARCHAR(50)           DEFAULT NULL   COMMENT '班级',
    student_name   VARCHAR(50)           DEFAULT NULL   COMMENT '识别出的姓名',
    image_path     VARCHAR(500)          DEFAULT NULL   COMMENT '答题卡扫描件',
    objective_score DECIMAL(6,1) NOT NULL DEFAULT 0    COMMENT '客观题得分',
    subjective_score DECIMAL(6,1) NOT NULL DEFAULT 0   COMMENT '主观题得分',
    total_score    DECIMAL(6,1) NOT NULL DEFAULT 0     COMMENT '总分',
    full_score     DECIMAL(6,1) NOT NULL DEFAULT 0     COMMENT '满分',
    status         VARCHAR(20)  NOT NULL DEFAULT 'AUTO' COMMENT '状态: AUTO自动判分 CONFIRMED已确认 MANUAL人工复核',
    detail_json    LONGTEXT                             COMMENT '逐题判分明细JSON',
    mode           VARCHAR(10)           DEFAULT 'ocr'  COMMENT '阅卷模式: ocr全部OCR fast快速截图',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_gs_paper (paper_id),
    KEY idx_gs_student (student_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阅卷记录';
