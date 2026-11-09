-- H2 (MySQL 兼容模式) 建表脚本 —— 结构与 schema-mysql.sql 一致
CREATE TABLE IF NOT EXISTS scan_doc (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_name       VARCHAR(200) NOT NULL,
    doc_type       VARCHAR(20)  DEFAULT 'PAPER',
    subject        VARCHAR(50),
    grade          VARCHAR(50),
    source_file    VARCHAR(500),
    page_count     INT DEFAULT 1,
    status         VARCHAR(20) DEFAULT 'UPLOADED',
    ocr_engine     VARCHAR(30),
    parse_ms       BIGINT DEFAULT 0,
    remark         VARCHAR(500),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS scan_page (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id         BIGINT NOT NULL,
    page_no        INT NOT NULL,
    image_path     VARCHAR(500),
    width          INT DEFAULT 0,
    height         INT DEFAULT 0,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS scan_block (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id         BIGINT NOT NULL,
    page_id        BIGINT NOT NULL,
    page_no        INT NOT NULL,
    block_type     VARCHAR(20) NOT NULL,
    x              INT DEFAULT 0,
    y              INT DEFAULT 0,
    w              INT DEFAULT 0,
    h              INT DEFAULT 0,
    sort_no        INT DEFAULT 0,
    content        CLOB,
    confidence     DECIMAL(5,4),
    image_path     VARCHAR(500),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_point (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    subject        VARCHAR(50),
    grade          VARCHAR(50),
    parent_id      BIGINT DEFAULT 0,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS question (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    doc_id         BIGINT,
    block_id       BIGINT,
    page_no        INT,
    q_no           VARCHAR(20),
    q_type         VARCHAR(20) DEFAULT 'OTHER',
    section_order  INT DEFAULT 0      COMMENT '大题序号(一/二/三 -> 1/2/3), 0 表示未识别到题型大类',
    section_title  VARCHAR(100)       COMMENT '大题标题, 如 填空题',
    seq            INT DEFAULT 0      COMMENT '卷内连续序号',
    lesson_order   INT DEFAULT NULL   COMMENT '课序(所属课次序号, 整数, 可为空)',
    stem           CLOB NOT NULL,
    options        CLOB,
    answer         CLOB,
    analysis       CLOB,
    difficulty     TINYINT DEFAULT 3,
    score          DECIMAL(5,1) DEFAULT 5.0,
    subject        VARCHAR(50),
    grade          VARCHAR(50),
    knowledge      VARCHAR(255),
    has_image      TINYINT DEFAULT 0,
    image_path     VARCHAR(500),
    source         VARCHAR(50) DEFAULT 'SCAN',
    status         VARCHAR(20) DEFAULT 'NORMAL',
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS paper (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    title          VARCHAR(200) NOT NULL,
    subject        VARCHAR(50),
    grade          VARCHAR(50),
    total_score    DECIMAL(6,1) DEFAULT 0,
    duration       INT DEFAULT 90,
    question_count INT DEFAULT 0,
    paper_type     VARCHAR(20) DEFAULT 'MANUAL',
    config_json    CLOB,
    status         VARCHAR(20) DEFAULT 'DRAFT',
    remark         VARCHAR(500),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS paper_item (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id       BIGINT NOT NULL,
    question_id    BIGINT NOT NULL,
    section_no     INT DEFAULT 1,
    section_name   VARCHAR(100),
    q_type         VARCHAR(20) NOT NULL,
    sort_no        INT DEFAULT 0,
    score          DECIMAL(5,1) DEFAULT 5.0
);

CREATE TABLE IF NOT EXISTS answer_sheet (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    paper_id       BIGINT,
    title          VARCHAR(200) NOT NULL,
    layout_json    CLOB,
    page_count     INT DEFAULT 2,
    marker_count   INT DEFAULT 4,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS student (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(50) NOT NULL,
    student_no     VARCHAR(40) NOT NULL,
    class_name     VARCHAR(60),
    grade          VARCHAR(50),
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS grade_sheet (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id       BIGINT,
    paper_id       BIGINT NOT NULL,
    student_no     VARCHAR(40),
    student_name   VARCHAR(50),
    image_path     VARCHAR(500),
    objective_score DECIMAL(6,1) DEFAULT 0,
    subjective_score DECIMAL(6,1) DEFAULT 0,
    total_score    DECIMAL(6,1) DEFAULT 0,
    full_score     DECIMAL(6,1) DEFAULT 0,
    status         VARCHAR(20) DEFAULT 'AUTO',
    detail_json    CLOB,
    create_time    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted        TINYINT DEFAULT 0
);

-- ------------------------------------------------------------
-- 兼容升级：为已有库补齐「题型大类」归属字段
-- ------------------------------------------------------------
ALTER TABLE question ADD COLUMN IF NOT EXISTS section_order INT DEFAULT 0;
ALTER TABLE question ADD COLUMN IF NOT EXISTS section_title VARCHAR(100);
ALTER TABLE question ADD COLUMN IF NOT EXISTS seq INT DEFAULT 0;
ALTER TABLE question ADD COLUMN IF NOT EXISTS lesson_order INT DEFAULT NULL;

-- 阅卷记录补录班级
ALTER TABLE grade_sheet ADD COLUMN IF NOT EXISTS class_name VARCHAR(50);
-- 阅卷模式：ocr=全部OCR / fast=快速截图（不调 OCR）
ALTER TABLE grade_sheet ADD COLUMN IF NOT EXISTS mode VARCHAR(10) DEFAULT 'ocr';
