const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const db = require('./db');

var JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
    // 开发环境兜底，生产环境必须通过 .env 配置
    JWT_SECRET = 'skt_dev_' + (require('os').hostname() || 'localhost') + '_2024';
}

var JWT_EXPIRES = process.env.JWT_EXPIRES || '7d';

function generateToken(user) {
    return jwt.sign(
        { id: user.id, username: user.username, role: user.role, displayName: user.display_name },
        JWT_SECRET,
        { expiresIn: JWT_EXPIRES }
    );
}

function hashPassword(pwd) {
    return bcrypt.hashSync(pwd, 10);
}

function verifyPassword(pwd, hash) {
    return bcrypt.compareSync(pwd, hash);
}

function authMiddleware(req, res, next) {
    var authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ code: 401, error: '未登录，请先登录' });
    }
    var token = authHeader.split(' ')[1];
    try {
        req.user = jwt.verify(token, JWT_SECRET);
        next();
    } catch (e) {
        if (e.name === 'TokenExpiredError') {
            return res.status(401).json({ code: 401, error: '登录已过期，请重新登录' });
        }
        return res.status(401).json({ code: 401, error: '无效的认证信息' });
    }
}

function requireRole() {
    var roles = Array.from(arguments);
    return function(req, res, next) {
        if (!req.user) return res.status(401).json({ code: 401, error: '未登录' });
        if (!roles.includes(req.user.role)) {
            var msg = req.user.role === 'parent' ? '家长账号不允许执行修改操作' : '权限不足';
            return res.status(403).json({ code: 403, error: msg });
        }
        next();
    };
}

function login(username, password) {
    var user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
    if (!user) return { success: false, error: '用户不存在' };
    if (!verifyPassword(password, user.password_hash)) {
        return { success: false, error: '密码错误' };
    }
    var token = generateToken(user);
    return {
        success: true,
        token: token,
        user: {
            id: user.id, username: user.username, role: user.role,
            displayName: user.display_name, phone: user.phone
        }
    };
}

function register(username, password, role, displayName, phone) {
    var existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
    if (existing) return { success: false, error: '用户名已存在' };

    var hash = hashPassword(password);
    var result = db.prepare(
        'INSERT INTO users (username, password_hash, role, display_name, phone) VALUES (?,?,?,?,?)'
    ).run(username, hash, role, displayName, phone || '');

    var user = db.prepare('SELECT * FROM users WHERE id = ?').get(result.lastInsertRowid);
    var token = generateToken(user);
    return {
        success: true,
        token: token,
        user: {
            id: user.id, username: user.username, role: user.role,
            displayName: user.display_name, phone: user.phone
        }
    };
}

function getParentChildren(parentId) {
    return db.prepare(
        'SELECT s.*, c.name as class_name, c.course as class_course, c.teacher_id, ' +
        'u.display_name as teacher_name ' +
        'FROM students s LEFT JOIN classes c ON s.class_id = c.id ' +
        'LEFT JOIN users u ON c.teacher_id = u.id ' +
        'WHERE s.parent_id = ? ORDER BY s.name'
    ).all(parentId);
}

function bindParentToStudent(parentId, studentName, parentPhone) {
    var trimmedName = (studentName || '').trim();
    var trimmedPhone = (parentPhone || '').trim();
    console.log('[bindParentToStudent] parentId=', parentId, ', trimmedName=', trimmedName, ', trimmedPhone=', trimmedPhone);

    var allStudents = db.prepare('SELECT s.id, s.name, s.parent_phone, s.parent_id, s.status FROM students s').all();
    console.log('[bindParentToStudent] 数据库学生总数:', allStudents.length, ', 前5条:', allStudents.slice(0, 5));

    var student = db.prepare(
        'SELECT s.*, c.name as class_name, c.course as class_course ' +
        'FROM students s LEFT JOIN classes c ON s.class_id = c.id ' +
        'WHERE s.name = ? AND s.parent_phone = ? ' +
        'AND s.status = \'active\''
    ).get(trimmedName, trimmedPhone);

    if (!student) {
        console.warn('[bindParentToStudent] 未找到匹配学生: name=', trimmedName, ', phone=', trimmedPhone);
        return { success: false, error: '未找到匹配的学生，请检查学生姓名和手机号是否与教师登记一致' };
    }
    if (student.parent_id && student.parent_id !== parentId) {
        return { success: false, error: '该学生已被其他家长账号绑定，请联系教师处理' };
    }

    db.prepare('UPDATE students SET parent_id = ? WHERE id = ?').run(parentId, student.id);
    return {
        success: true,
        student: {
            id: student.id, name: student.name,
            class_name: student.class_name || '',
            class_course: student.class_course || ''
        }
    };
}

module.exports = {
    db: db,
    generateToken: generateToken,
    hashPassword: hashPassword,
    verifyPassword: verifyPassword,
    authMiddleware: authMiddleware,
    requireRole: requireRole,
    login: login,
    register: register,
    getParentChildren: getParentChildren,
    bindParentToStudent: bindParentToStudent,
    JWT_SECRET: JWT_SECRET
};
