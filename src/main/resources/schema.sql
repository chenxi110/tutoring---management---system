ALTER DATABASE skt_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    role VARCHAR(20) NOT NULL,
    display_name VARCHAR(50) DEFAULT '',
    phone VARCHAR(20),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS semesters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    start_date VARCHAR(20) NOT NULL,
    end_date VARCHAR(20) NOT NULL,
    is_active TINYINT DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    course VARCHAR(100) NOT NULL,
    semester_id BIGINT,
    teacher_id BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS students (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    class_id BIGINT,
    parent_id BIGINT,
    user_id BIGINT COMMENT '绑定的学生账号ID',
    phone VARCHAR(20),
    parent_phone VARCHAR(20),
    parent_name VARCHAR(50),
    parent_relation VARCHAR(20),
    enrollment_date VARCHAR(20),
    status VARCHAR(20) DEFAULT 'active',
    is_deleted TINYINT DEFAULT 0,
    tags TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    date VARCHAR(20) NOT NULL,
    class_id BIGINT,
    class_name VARCHAR(100),
    course VARCHAR(100),
    sessions INT DEFAULT 1,
    type VARCHAR(50),
    semester_id BIGINT,
    remark TEXT,
    absent_json TEXT,
    trial_students TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS schedules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    weekday INT NOT NULL,
    start_time VARCHAR(10) NOT NULL,
    end_time VARCHAR(10) NOT NULL,
    class_id BIGINT,
    class_name VARCHAR(100),
    course VARCHAR(100),
    sessions INT DEFAULT 1,
    semester_id BIGINT,
    teacher_id BIGINT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT,
    sender_name VARCHAR(50),
    receiver_id BIGINT,
    student_name VARCHAR(50),
    class_id BIGINT,
    class_name VARCHAR(100),
    title VARCHAR(200),
    content TEXT NOT NULL,
    msg_type VARCHAR(30) DEFAULT 'notice',
    status VARCHAR(20) DEFAULT 'unread',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS homework (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    deadline VARCHAR(20),
    created_by BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS homework_submissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    homework_id BIGINT,
    student_id BIGINT,
    student_name VARCHAR(50),
    content TEXT,
    score DOUBLE,
    comment TEXT,
    submitted_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS grades (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT,
    student_name VARCHAR(50),
    class_id BIGINT,
    class_name VARCHAR(100),
    exam_name VARCHAR(100) NOT NULL,
    exam_type VARCHAR(30) DEFAULT 'unit_test',
    score DOUBLE NOT NULL,
    total_score DOUBLE DEFAULT 100,
    `rank` INT,
    semester_id BIGINT,
    teacher_id BIGINT,
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS msg_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    sort INT DEFAULT 1
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SELECT COUNT(*) INTO @student_parent_user_id_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'students'
  AND column_name = 'parent_user_id';
SET @student_parent_user_id_sql = IF(@student_parent_user_id_exists = 0,
  'ALTER TABLE students ADD COLUMN parent_user_id BIGINT NULL',
  'SELECT 1');
PREPARE student_parent_user_id_stmt FROM @student_parent_user_id_sql;
EXECUTE student_parent_user_id_stmt;
DEALLOCATE PREPARE student_parent_user_id_stmt;

SELECT COUNT(*) INTO @student_is_deleted_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'students'
  AND column_name = 'is_deleted';
SET @student_is_deleted_sql = IF(@student_is_deleted_exists = 0,
  'ALTER TABLE students ADD COLUMN is_deleted TINYINT DEFAULT 0',
  'SELECT 1');
PREPARE student_is_deleted_stmt FROM @student_is_deleted_sql;
EXECUTE student_is_deleted_stmt;
DEALLOCATE PREPARE student_is_deleted_stmt;

SELECT COUNT(*) INTO @message_sender_role_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'messages'
  AND column_name = 'sender_role';
SET @message_sender_role_sql = IF(@message_sender_role_exists = 0,
  'ALTER TABLE messages ADD COLUMN sender_role VARCHAR(20) NULL',
  'SELECT 1');
PREPARE message_sender_role_stmt FROM @message_sender_role_sql;
EXECUTE message_sender_role_stmt;
DEALLOCATE PREPARE message_sender_role_stmt;

SELECT COUNT(*) INTO @message_student_id_exists
FROM information_schema.columns
WHERE table_schema = DATABASE()
  AND table_name = 'messages'
  AND column_name = 'student_id';
SET @message_student_id_sql = IF(@message_student_id_exists = 0,
  'ALTER TABLE messages ADD COLUMN student_id BIGINT NULL',
  'SELECT 1');
PREPARE message_student_id_stmt FROM @message_student_id_sql;
EXECUTE message_student_id_stmt;
DEALLOCATE PREPARE message_student_id_stmt;

CREATE TABLE IF NOT EXISTS message_reply (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_name VARCHAR(100),
    sender_role VARCHAR(20),
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS share_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    student_id BIGINT NOT NULL,
    is_permanent TINYINT DEFAULT 0,
    expires_at DATETIME NULL,
    created_by BIGINT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 签到任务表
CREATE TABLE IF NOT EXISTS signins (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    class_name VARCHAR(100),
    teacher_id BIGINT NOT NULL,
    sign_type VARCHAR(20) DEFAULT 'password',
    password VARCHAR(20),
    status VARCHAR(20) DEFAULT 'running',
    deadline DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 签到记录表（学生签到记录）
CREATE TABLE IF NOT EXISTS signin_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    signin_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    student_name VARCHAR(50),
    parent_id BIGINT,
    status VARCHAR(20) DEFAULT 'absent',
    signed_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- AI对话记录表
CREATE TABLE IF NOT EXISTS ai_chat_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    session_id VARCHAR(64),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 课堂文件互传表（按需求规范重建，字段名对齐）
DROP TABLE IF EXISTS course_file;
CREATE TABLE course_file (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键id',
    class_id BIGINT NOT NULL COMMENT '上课班级ID',
    teacher_id BIGINT NOT NULL COMMENT '授课教师ID',
    student_id BIGINT DEFAULT NULL COMMENT '学生ID，学生提交作业才有值',
    file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
    save_path VARCHAR(500) NOT NULL COMMENT '服务器存储相对路径',
    file_suffix VARCHAR(50) NOT NULL COMMENT '文件后缀 excel/word/ppt/png/jpg',
    file_size BIGINT NOT NULL COMMENT '文件字节大小',
    upload_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_teacher_upload TINYINT NOT NULL DEFAULT 1 COMMENT '1=教师下发课件 0=学生提交作业',
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂文件互传表';

-- 将已存在的表转换为utf8mb4字符集
ALTER TABLE users CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE semesters CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE classes CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE students CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE schedules CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE messages CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE homework CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE homework_submissions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE grades CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE msg_templates CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE message_reply CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE share_tokens CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE signins CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE signin_records CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE ai_chat_history CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE course_file CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 考试表
CREATE TABLE IF NOT EXISTS exam (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_code VARCHAR(64) NOT NULL UNIQUE COMMENT '考试编码(exam_时间戳)',
    class_id BIGINT NOT NULL,
    teacher_id BIGINT NOT NULL,
    title VARCHAR(200) DEFAULT '课堂测试',
    duration INT DEFAULT 30 COMMENT '考试时长(分钟)',
    password VARCHAR(50) DEFAULT '' COMMENT '考试密码',
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending/running/ended',
    start_time DATETIME,
    end_time DATETIME,
    config_json TEXT COMMENT '考试配置JSON(防作弊设置等)',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 考试题目表
CREATE TABLE IF NOT EXISTS exam_question (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    question_json TEXT NOT NULL COMMENT '题目JSON',
    sort_order INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 考试提交记录表
CREATE TABLE IF NOT EXISTS exam_submission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    exam_id BIGINT NOT NULL,
    student_id BIGINT,
    student_name VARCHAR(50),
    answers_json TEXT,
    score DOUBLE,
    submitted_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 操作审计日志表
CREATE TABLE IF NOT EXISTS operation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT,
    username VARCHAR(100),
    role VARCHAR(20),
    operation VARCHAR(100) NOT NULL,
    detail TEXT,
    ip VARCHAR(50),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_operation (operation),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 课堂行为记录表
CREATE TABLE IF NOT EXISTS classroom_behavior (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT NOT NULL,
    class_name VARCHAR(100),
    teacher_id BIGINT NOT NULL,
    student_id BIGINT NOT NULL,
    student_name VARCHAR(50),
    session_id BIGINT,
    behavior_type VARCHAR(30) NOT NULL COMMENT 'signin/raise_hand/answer/quiz/file_upload',
    behavior_detail TEXT,
    score_change INT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_class_id (class_id),
    INDEX idx_student_id (student_id),
    INDEX idx_behavior_type (behavior_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 请假申请表
CREATE TABLE IF NOT EXISTS leave_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id BIGINT NOT NULL,
    student_name VARCHAR(50),
    class_id BIGINT,
    parent_id BIGINT,
    parent_name VARCHAR(50),
    leave_date DATE NOT NULL,
    leave_type VARCHAR(20) DEFAULT 'sick' COMMENT 'sick病假/personal事假/other其他',
    reason TEXT,
    status VARCHAR(20) DEFAULT 'pending' COMMENT 'pending待审批/approved已批准/rejected已拒绝',
    teacher_id BIGINT,
    teacher_remark TEXT,
    approved_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_student_id (student_id),
    INDEX idx_class_id (class_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS message_read_receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL COMMENT '消息ID',
    receiver_id BIGINT NOT NULL COMMENT '接收者ID',
    receiver_name VARCHAR(50) DEFAULT NULL COMMENT '接收者姓名',
    is_read TINYINT DEFAULT '0' COMMENT '是否已读',
    read_at DATETIME DEFAULT NULL COMMENT '阅读时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_receiver (message_id, receiver_id),
    KEY idx_receiver_id (receiver_id),
    KEY idx_is_read (is_read)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息已读回执表';
