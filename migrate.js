/**
 * migrate.js - 旧版 appData 数据迁移脚本
 * 
 * 使用方法:
 *   1. 将旧版备份文件保存为 backup.json 放在项目根目录
 *   2. 运行: node migrate.js
 * 
 * 旧版数据格式 (localStorage tutoringAppData):
 *   appData = { records, students, config, semesters, classes, parentInfo, msgTemplates, ... }
 * 
 * 新版数据库: SQLite (data/app.db)
 */
const db = require('./db');
const fs = require('fs');
const path = require('path');

const BACKUP_FILE = path.join(__dirname, 'backup.json');

if (!fs.existsSync(BACKUP_FILE)) {
    console.error('❌ 未找到 backup.json 文件！');
    console.error('   请将旧版备份数据保存为 backup.json 放在项目根目录后重试。');
    console.error('   备份数据可从浏览器 localStorage 的 tutoringAppData 键中获取。');
    process.exit(1);
}

console.log('📖 读取备份文件:', BACKUP_FILE);
const raw = JSON.parse(fs.readFileSync(BACKUP_FILE, 'utf8'));
const data = raw.appData || raw;

console.log('📊 备份数据概览:');
console.log('   学期:', (data.semesters || []).length, '个');
console.log('   班级:', (data.classes || []).length, '个');
console.log('   学生:', Object.keys(data.students || {}).reduce((sum, cn) => sum + (data.students[cn] || []).length, 0), '人');
console.log('   上课记录:', (data.records || []).length, '条');
console.log('   消息模板:', (data.msgTemplates || []).length, '个');

let imported = { semesters: 0, classes: 0, students: 0, records: 0, templates: 0 };

try {
    db.exec('BEGIN');

    // 1. 迁移学期
    if (data.semesters && Array.isArray(data.semesters)) {
        const stmt = db.prepare('INSERT OR IGNORE INTO semesters (name, start_date, end_date, is_active) VALUES (?,?,?,?)');
        data.semesters.forEach(s => {
            const result = stmt.run(s.name, s.startDate || '', s.endDate || '', s.isActive ? 1 : 0);
            if (result.changes > 0) imported.semesters++;
        });
    }

    // 2. 迁移班级（需要关联学期）
    const semesterMap = {}; // name -> id
    db.prepare('SELECT id, name FROM semesters').all().forEach(s => { semesterMap[s.name] = s.id; });

    if (data.semesters && Array.isArray(data.semesters)) {
        const stmt = db.prepare('INSERT OR IGNORE INTO classes (name, course, semester_id) VALUES (?,?,?)');
        data.semesters.forEach(sem => {
            const semId = semesterMap[sem.name];
            if (!semId) return;
            (sem.classes || []).forEach(c => {
                const result = stmt.run(c.name, c.course || '', semId);
                if (result.changes > 0) imported.classes++;
            });
        });
    }
    // 也处理顶层的 classes 数组
    if (data.classes && Array.isArray(data.classes)) {
        const stmt = db.prepare('INSERT OR IGNORE INTO classes (name, course, semester_id) VALUES (?,?,?)');
        const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
        const defaultSemId = activeSem ? activeSem.id : 1;
        data.classes.forEach(c => {
            if (!c.semester_id) {
                const result = stmt.run(c.name, c.course || '', defaultSemId);
                if (result.changes > 0) imported.classes++;
            }
        });
    }

    // 3. 迁移学生（需要关联班级）
    const classMap = {}; // name -> id
    db.prepare('SELECT id, name FROM classes').all().forEach(c => { classMap[c.name] = c.id; });

    if (data.students && typeof data.students === 'object') {
        const stmt = db.prepare('INSERT INTO students (name, class_id, parent_phone, parent_name, enrollment_date, status) VALUES (?,?,?,?,?,?)');
        Object.entries(data.students).forEach(([className, students]) => {
            const classId = classMap[className];
            if (!classId) {
                console.warn(`   ⚠️ 班级 "${className}" 不存在，跳过该班级的学生`);
                return;
            }
            students.forEach(sName => {
                const s = typeof sName === 'object' ? sName : { name: sName };
                const pInfo = data.parentInfo && data.parentInfo[s.name];
                const parentPhone = pInfo && pInfo[0] ? pInfo[0].phone : '';
                const parentName = pInfo && pInfo[0] ? pInfo[0].name : '';
                stmt.run(s.name, classId, parentPhone, parentName, new Date().toISOString().slice(0, 10), 'active');
                imported.students++;
            });
        });
    }

    // 4. 迁移上课记录
    if (data.records && Array.isArray(data.records) && data.records.length > 0) {
        const stmt = db.prepare(`INSERT INTO records (date, class_id, class_name, course, sessions, type, semester_id, remark, absent_json, trial_students) VALUES (?,?,?,?,?,?,?,?,?,?)`);
        const activeSem = db.prepare('SELECT id FROM semesters WHERE is_active=1').get();
        const defaultSemId = activeSem ? activeSem.id : 1;
        data.records.forEach(r => {
            const className = r.className || r.class || '';
            const cls = classMap[className];
            const semId = r.semester_id || defaultSemId;
            stmt.run(
                r.date || '',
                cls ? cls.id : null,
                className,
                r.course || '',
                r.sessions || 1,
                r.type || '正常课',
                semId,
                r.remark || '',
                JSON.stringify(r.absent || []),
                JSON.stringify(r.trialStudents || [])
            );
            imported.records++;
        });
    }

    // 5. 迁移消息模板（跳过默认的4个）
    if (data.msgTemplates && Array.isArray(data.msgTemplates)) {
        const existing = db.prepare('SELECT name FROM msg_templates').all().map(t => t.name);
        const stmt = db.prepare('INSERT INTO msg_templates (name, content, sort) VALUES (?,?,?)');
        data.msgTemplates.forEach((t, i) => {
            if (!existing.includes(t.name)) {
                stmt.run(t.name, t.content, i + 5);
                imported.templates++;
            }
        });
    }

    db.exec('COMMIT');

    console.log('\n✅ 数据迁移完成！');
    console.log('   导入学期:', imported.semesters, '个');
    console.log('   导入班级:', imported.classes, '个');
    console.log('   导入学生:', imported.students, '人');
    console.log('   导入记录:', imported.records, '条');
    console.log('   导入模板:', imported.templates, '个');
    console.log('\n💡 请重启后端服务 (node ai-service.js) 使数据生效。');
} catch (err) {
    db.exec('ROLLBACK');
    console.error('\n❌ 迁移失败:', err.message);
    console.error(err.stack);
    process.exit(1);
}

db.close();
