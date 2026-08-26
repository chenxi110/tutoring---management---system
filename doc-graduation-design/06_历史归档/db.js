const Database = require('better-sqlite3');
const path = require('path');
const fs = require('fs');

const DB_PATH = path.join(__dirname, 'data', 'app.db');
const DB_DIR = path.dirname(DB_PATH);
if (!fs.existsSync(DB_DIR)) fs.mkdirSync(DB_DIR, { recursive: true });

const db = new Database(DB_PATH);
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

const SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK(role IN ('teacher','parent')),
    display_name TEXT NOT NULL,
    phone TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS semesters (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    start_date TEXT NOT NULL,
    end_date TEXT NOT NULL,
    is_active INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS classes (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    course TEXT NOT NULL,
    semester_id INTEGER REFERENCES semesters(id),
    teacher_id INTEGER REFERENCES users(id),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS students (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    class_id INTEGER REFERENCES classes(id),
    parent_id INTEGER REFERENCES users(id),
    phone TEXT,
    parent_phone TEXT,
    parent_name TEXT,
    parent_relation TEXT,
    enrollment_date TEXT,
    status TEXT DEFAULT 'active',
    tags TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS records (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    date TEXT NOT NULL,
    class_id INTEGER REFERENCES classes(id),
    class_name TEXT,
    course TEXT,
    sessions INTEGER DEFAULT 1,
    type TEXT,
    semester_id INTEGER REFERENCES semesters(id),
    remark TEXT,
    absent_json TEXT,
    trial_students TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS schedules (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    weekday INTEGER NOT NULL,
    start_time TEXT NOT NULL,
    end_time TEXT NOT NULL,
    class_id INTEGER REFERENCES classes(id),
    class_name TEXT,
    course TEXT,
    sessions INTEGER DEFAULT 1,
    semester_id INTEGER REFERENCES semesters(id),
    teacher_id INTEGER REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS classroom_sessions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER REFERENCES classes(id),
    date TEXT,
    course TEXT,
    start_time DATETIME,
    end_time DATETIME,
    data_json TEXT
);

CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_id INTEGER REFERENCES users(id),
    sender_name TEXT,
    receiver_id INTEGER REFERENCES users(id),
    student_name TEXT,
    class_id INTEGER,
    class_name TEXT,
    title TEXT,
    content TEXT NOT NULL,
    msg_type TEXT DEFAULT 'notice',
    status TEXT DEFAULT 'unread',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    read_at DATETIME
);

CREATE TABLE IF NOT EXISTS homework (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    class_id INTEGER REFERENCES classes(id),
    title TEXT NOT NULL,
    content TEXT,
    deadline TEXT,
    created_by INTEGER REFERENCES users(id),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS homework_submissions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    homework_id INTEGER REFERENCES homework(id),
    student_id INTEGER REFERENCES students(id),
    student_name TEXT,
    content TEXT,
    score REAL,
    comment TEXT,
    submitted_at DATETIME
);

CREATE TABLE IF NOT EXISTS grades (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    student_id INTEGER REFERENCES students(id),
    student_name TEXT,
    class_id INTEGER REFERENCES classes(id),
    class_name TEXT,
    exam_name TEXT NOT NULL,
    exam_type TEXT DEFAULT 'unit_test',
    score REAL NOT NULL,
    total_score REAL DEFAULT 100,
    rank INTEGER,
    semester_id INTEGER REFERENCES semesters(id),
    teacher_id INTEGER REFERENCES users(id),
    remark TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS msg_templates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    content TEXT NOT NULL,
    sort INTEGER DEFAULT 1
);
`;

db.exec(SCHEMA_SQL);

function initDefaultData() {
    var userCount = db.prepare('SELECT COUNT(*) as c FROM users').get().c;
    if (userCount === 0) {
        var bcrypt = require('bcryptjs');
        var defaultPwd = bcrypt.hashSync('admin123', 10);
        db.prepare('INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)')
            .run('admin', defaultPwd, 'teacher', '管理员', '');
        console.log('[DB] 默认账号: admin / admin123');
    }

    var tplCount = db.prepare('SELECT COUNT(*) as c FROM msg_templates').get().c;
    if (tplCount === 0) {
        var insertTpl = db.prepare('INSERT INTO msg_templates (name, content, sort) VALUES (?,?,?)');
        insertTpl.run('上课提醒', '各位家长您好，明日按时上课，请准时接送孩子，路途注意安全。', 1);
        insertTpl.run('作业通知', '今日课后作业，请家长督促孩子认真完成，及时提交。', 2);
        insertTpl.run('考勤提醒', '您的孩子今日上课出勤异常，请您抽空沟通了解情况。', 3);
        insertTpl.run('放假通知', '近期课程临时暂停，假期注意孩子安全，开课时间另行通知。', 4);
    }

    var semCount = db.prepare('SELECT COUNT(*) as c FROM semesters').get().c;
    if (semCount === 0) {
        var insertSem = db.prepare('INSERT INTO semesters (name, start_date, end_date, is_active) VALUES (?,?,?,?)');
        insertSem.run('2024年春季', '2024-03-01', '2024-07-15', 0);
        insertSem.run('2024年秋季', '2024-09-01', '2025-01-20', 0);
        insertSem.run('2025年春季', '2025-03-01', '2025-07-15', 1);
    }
}

initDefaultData();

function migrateColumns() {
    var classCols = db.prepare("PRAGMA table_info(classes)").all();
    if (!classCols.some(function(c) { return c.name === 'teacher_id'; })) {
        db.exec("ALTER TABLE classes ADD COLUMN teacher_id INTEGER REFERENCES users(id)");
        var firstTeacher = db.prepare("SELECT id FROM users WHERE role='teacher' ORDER BY id LIMIT 1").get();
        if (firstTeacher) {
            db.prepare("UPDATE classes SET teacher_id=? WHERE teacher_id IS NULL").run(firstTeacher.id);
        }
    }

    var schedCols = db.prepare("PRAGMA table_info(schedules)").all();
    if (!schedCols.some(function(c) { return c.name === 'teacher_id'; })) {
        db.exec("ALTER TABLE schedules ADD COLUMN teacher_id INTEGER REFERENCES users(id)");
        db.exec("UPDATE schedules SET teacher_id = (SELECT teacher_id FROM classes WHERE classes.id = schedules.class_id) WHERE teacher_id IS NULL");
    }
}

migrateColumns();

function migrateFromJson() {
    var teachingDataPath = path.join(DB_DIR, 'teaching_data.json');
    if (!fs.existsSync(teachingDataPath)) {
        teachingDataPath = path.join(__dirname, 'teaching_data.json');
    }
    if (!fs.existsSync(teachingDataPath)) return;

    try {
        var raw = JSON.parse(fs.readFileSync(teachingDataPath, 'utf8'));
        var data = raw.appData || raw;

        if (data.classes && Array.isArray(data.classes)) {
            var activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
            var semId = activeSem ? activeSem.id : 1;
            var existClasses = db.prepare('SELECT COUNT(*) as c FROM classes').get().c;
            if (existClasses === 0) {
                var firstTeacher = db.prepare("SELECT id FROM users WHERE role='teacher' ORDER BY id LIMIT 1").get();
                var tid = firstTeacher ? firstTeacher.id : null;
                var insertClass = db.prepare('INSERT INTO classes (name, course, semester_id, teacher_id) VALUES (?,?,?,?)');
                data.classes.forEach(function(c) {
                    insertClass.run(c.name, c.course || '', semId, tid);
                });
            }
        }

        if (data.students && typeof data.students === 'object') {
            var existStudents = db.prepare('SELECT COUNT(*) as c FROM students').get().c;
            if (existStudents === 0) {
                var getClassId = db.prepare('SELECT id FROM classes WHERE name=?');
                var insertStudent = db.prepare('INSERT INTO students (name, class_id, parent_phone, parent_name, enrollment_date) VALUES (?,?,?,?,?)');
                Object.entries(data.students).forEach(function(entry) {
                    var className = entry[0], students = entry[1];
                    var cls = getClassId.get(className);
                    if (!cls) return;
                    students.forEach(function(sName) {
                        var pInfo = data.parentInfo && data.parentInfo[sName];
                        var parentPhone = pInfo && pInfo[0] ? pInfo[0].phone : '';
                        var parentName = pInfo && pInfo[0] ? pInfo[0].name : '';
                        insertStudent.run(sName, cls.id, parentPhone, parentName, new Date().toISOString().slice(0, 10));
                    });
                });
            }
        }

        if (data.records && Array.isArray(data.records)) {
            var existRecords = db.prepare('SELECT COUNT(*) as c FROM records').get().c;
            if (existRecords === 0) {
                var getClassId2 = db.prepare('SELECT id FROM classes WHERE name=?');
                var insertRecord = db.prepare('INSERT INTO records (date, class_id, class_name, course, sessions, type, semester_id, remark, absent_json, trial_students) VALUES (?,?,?,?,?,?,?,?,?,?)');
                var activeSem2 = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
                var semId2 = activeSem2 ? activeSem2.id : 1;
                data.records.forEach(function(r) {
                    var cls = getClassId2.get(r.className || r.class);
                    insertRecord.run(
                        r.date || '', cls ? cls.id : null,
                        r.className || r.class || '', r.course || '',
                        r.sessions || 1, r.type || '正常课', semId2,
                        r.remark || '', JSON.stringify(r.absent || []),
                        JSON.stringify(r.trialStudents || [])
                    );
                });
            }
        }

        if (data.msgTemplates && Array.isArray(data.msgTemplates) && data.msgTemplates.length > 0) {
            var existTpl = db.prepare('SELECT COUNT(*) as c FROM msg_templates').get().c;
            if (existTpl <= 4) {
                var insertTpl2 = db.prepare('INSERT INTO msg_templates (name, content, sort) VALUES (?,?,?)');
                data.msgTemplates.forEach(function(t, i) {
                    if (!['上课提醒', '作业通知', '考勤提醒', '放假通知'].includes(t.name)) {
                        insertTpl2.run(t.name, t.content, i + 5);
                    }
                });
            }
        }
    } catch (err) {
        console.error('[DB] 迁移失败:', err.message);
    }
}

migrateFromJson();

module.exports = db;
