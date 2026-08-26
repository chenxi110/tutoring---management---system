-- H2 compatible schema for tests (strips MySQL-specific DDL)

CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(200) NOT NULL,
    role VARCHAR(20) NOT NULL,
    display_name VARCHAR(50) DEFAULT '',
    phone VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS semesters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    start_date VARCHAR(20) NOT NULL,
    end_date VARCHAR(20) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS classes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    course VARCHAR(100) NOT NULL,
    semester_id BIGINT,
    teacher_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS students (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    class_id BIGINT,
    parent_id BIGINT,
    phone VARCHAR(20),
    parent_phone VARCHAR(20),
    parent_name VARCHAR(50),
    parent_relation VARCHAR(20),
    enrollment_date VARCHAR(20),
    status VARCHAR(20) DEFAULT 'active',
    is_deleted BOOLEAN DEFAULT FALSE,
    tags TEXT,
    parent_user_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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
);

CREATE TABLE IF NOT EXISTS messages (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sender_id BIGINT,
    sender_name VARCHAR(50),
    sender_role VARCHAR(20),
    receiver_id BIGINT,
    student_id BIGINT,
    student_name VARCHAR(50),
    class_id BIGINT,
    class_name VARCHAR(100),
    title VARCHAR(200),
    content TEXT NOT NULL,
    msg_type VARCHAR(30) DEFAULT 'notice',
    status VARCHAR(20) DEFAULT 'unread',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    read_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS homework (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    class_id BIGINT,
    title VARCHAR(200) NOT NULL,
    content TEXT,
    deadline VARCHAR(20),
    created_by BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS homework_submissions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    homework_id BIGINT,
    student_id BIGINT,
    student_name VARCHAR(50),
    content TEXT,
    score DOUBLE,
    comment TEXT,
    submitted_at TIMESTAMP
);

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
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS msg_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    sort INT DEFAULT 1
);

CREATE TABLE IF NOT EXISTS message_reply (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_name VARCHAR(100),
    sender_role VARCHAR(20),
    content TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS share_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    token VARCHAR(64) NOT NULL UNIQUE,
    student_id BIGINT NOT NULL,
    is_permanent BOOLEAN DEFAULT FALSE,
    expires_at TIMESTAMP NULL,
    created_by BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
