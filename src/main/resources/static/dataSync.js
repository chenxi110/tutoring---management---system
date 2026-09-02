var DataSync = {
    _syncing: false,
    _lastSync: 0,
    _dirty: false,

    async syncFromServer() {
        if (!apiService.isLoggedIn()) return { success: false, reason: 'not_logged_in' };
        this._syncing = true;
        try {
            if (apiService.isTeacher() || (apiService.user && apiService.user.role === 'admin')) {
                await this._syncTeacherData();
            } else if (apiService.isParent()) {
                await this._syncParentData();
            } else if (apiService.isStudent()) {
                await this._syncStudentData();
            }
            this._lastSync = Date.now();
            this._dirty = false;
            // 数据同步完成后失效前端统计缓存，保证仪表盘数值即时刷新（不读旧缓存）
            if (typeof StatsCache !== 'undefined' && typeof StatsCache.markDirty === 'function') StatsCache.markDirty();
            return { success: true };
        } catch (e) {
            console.error('[DataSync] 同步失败:', e.message);
            return { success: false, error: e.message };
        } finally {
            this._syncing = false;
        }
    },

    async _syncStudentData() {
        // 学生端：通过 /parent/children 获取本人记录（后端已按 students.user_id 隔离返回本人），
        // 与家长端 parentData 结构保持一致，供家长版页面（课表/作业/成绩/AI）复用。
        window.parentData = { children: [], schedules: {}, records: {}, messages: [] };
        try {
            var childrenRes = await apiService.getParentChildren();
            if (childrenRes.code === 200 && childrenRes.data) {
                window.parentData.children = childrenRes.data;
            }
        } catch (e) { console.warn('[DataSync] 学生信息同步失败:', e.message); }
        try {
            var schedRes = await apiService.getChildSchedules();
            if (schedRes.code === 200 && schedRes.data) {
                window.parentData.children.forEach(function(child) {
                    window.parentData.schedules[child.id] = schedRes.data.filter(function(s) {
                        return s.student_name === child.name;
                    });
                });
            }
        } catch (e) { console.warn('[DataSync] 学生课表同步失败:', e.message); }
        try {
            var attRes = await apiService.getStudentAttendance();
            if (attRes.code === 200 && attRes.data) {
                window.parentData.children.forEach(function(child) {
                    window.parentData.records[child.id] = attRes.data || [];
                });
            }
        } catch (e) { console.warn('[DataSync] 学生出勤同步失败:', e.message); }
        try {
            var msgRes = await apiService.getMessages();
            if (msgRes.code === 200) {
                window.parentData.messages = msgRes.data || [];
            }
        } catch (e) {}
    },

    async _syncTeacherData() {
        // 学期
        try {
            var semRes = await apiService.getSemesters();
            if (semRes.code === 200 && semRes.data && semRes.data.length > 0) {
                appData.semesters = semRes.data.map(function(s) {
                    return {
                        id: s.id, name: s.name,
                        startDate: s.start_date, endDate: s.end_date,
                        isActive: s.is_active === 1,
                        classes: [], students: {}, schedule: []
                    };
                });
            }
        } catch(e) { console.warn('[DataSync] 学期同步失败:', e.message); }

        // 班级
        try {
            var isAdminRole = apiService.user && apiService.user.role === 'admin';
            var classRes = isAdminRole ? await apiService.getClasses() : await apiService.getMyClasses();
            if (classRes.code === 200 && classRes.data) {
                appData.classes = classRes.data.map(function(c) {
                    return {
                        id: c.id, name: c.name, course: c.course,
                        semester_id: c.semester_id, teacher_id: c.teacher_id,
                        studentCount: c.student_count || 0
                    };
                });
                appData.semesters.forEach(function(sem) {
                    sem.classes = appData.classes
                        .filter(function(c) { return !c.semester_id || c.semester_id === sem.id; })
                        .map(function(c) { return { id: c.id, name: c.name, course: c.course }; });
                });
            }
        } catch(e) { console.warn('[DataSync] 班级同步失败:', e.message); }

        // 学生
        try {
            var stuRes = await apiService.getStudents();
            if (stuRes.code === 200 && stuRes.data) {
                appData.students = {};
                appData.parentInfo = {};
                appData.studentIds = {};
                stuRes.data.forEach(function(s) {
                    var className = s.class_name || '';
                    if (!appData.students[className]) appData.students[className] = [];
                    appData.students[className].push(s.name);
                    appData.studentIds[className + '|' + s.name] = s.id;
                    if (s.parent_name || s.parent_phone) {
                        if (!appData.parentInfo[s.name]) appData.parentInfo[s.name] = [];
                        appData.parentInfo[s.name].push({
                            name: s.parent_name || '',
                            phone: s.parent_phone || '',
                            relation: s.parent_relation || '家长'
                        });
                    }
                });
                appData.semesters.forEach(function(sem) {
                    var semClassNames = sem.classes.map(function(c) { return c.name; });
                    sem.students = {};
                    Object.keys(appData.students).forEach(function(cn) {
                        if (semClassNames.includes(cn)) {
                            sem.students[cn] = appData.students[cn].slice();
                        }
                    });
                });
            }
        } catch(e) { console.warn('[DataSync] 学生同步失败:', e.message); }

        // 上课记录
        try {
            var recRes = await apiService.getRecords();
            if (recRes.code === 200 && recRes.data) {
                appData.records = recRes.data.map(function(r) {
                    return {
                        id: r.id, date: r.date,
                        className: r.class_name || r.className || '',
                        class: r.class_name || r.className || '',
                        classId: r.class_id, course: r.course || '',
                        sessions: r.sessions || 1, type: r.type || '正常课',
                        semester_id: r.semester_id, remark: r.remark || '',
                        absent: Array.isArray(r.absent) ? r.absent : [],
                        trialStudents: Array.isArray(r.trialStudents) ? r.trialStudents : []
                    };
                });
            }
        } catch(e) { console.warn('[DataSync] 记录同步失败:', e.message); }

        // 课表
        try {
            var schRes = await apiService.getMySchedules();
            if (schRes.code === 200 && schRes.data) {
                var allSchedules = schRes.data.map(function(s) {
                    return {
                        id: s.id, weekday: s.weekday,
                        startTime: s.start_time, endTime: s.end_time,
                        classId: s.class_id, className: s.class_name || '',
                        course: s.course || '', sessions: s.sessions || 1,
                        semesterId: s.semester_id
                    };
                });
                appData.semesters.forEach(function(sem) {
                    sem.schedule = allSchedules.filter(function(s) {
                        return !s.semesterId || s.semesterId === sem.id;
                    });
                });
            }
        } catch(e) { console.warn('[DataSync] 课表同步失败:', e.message); }

        // 消息模板
        try {
            var tplRes = await apiService.getMsgTemplates();
            if (tplRes.code === 200 && tplRes.data && tplRes.data.length > 0) {
                appData.msgTemplates = tplRes.data.map(function(t) {
                    return {
                        id: 'tpl_' + t.id, _dbId: t.id,
                        name: t.name, content: t.content, sort: t.sort
                    };
                });
            }
        } catch(e) { console.warn('[DataSync] 模板同步失败:', e.message); }

        // 缓存到 localStorage
        try {
            safeSetLocalStorage('tutoringAppData', JSON.stringify(appData));
        } catch(e) { console.warn('[DataSync] 缓存保存失败:', e.message); }
    },

    async _syncParentData() {
        var childrenRes = await apiService.getParentChildren();
        if (childrenRes.code !== 200 || !childrenRes.data) return;

        window.parentData = {
            children: childrenRes.data,
            schedules: {}, records: {}, messages: []
        };

        try {
            var schedRes = await apiService.getChildSchedules();
            if (schedRes.code === 200 && schedRes.data) {
                childrenRes.data.forEach(function(child) {
                    window.parentData.schedules[child.id] = schedRes.data.filter(function(s) {
                        return s.student_name === child.name;
                    });
                });
            }
        } catch (e) {
            console.warn('[DataSync] 获取孩子课表失败:', e.message);
        }

        for (var i = 0; i < childrenRes.data.length; i++) {
            var child = childrenRes.data[i];
            try {
                var detailRes = await apiService.getStudentDetail(child.id);
                if (detailRes.code === 200 && detailRes.data) {
                    window.parentData.records[child.id] = detailRes.data.records || [];
                    if (!window.parentData.schedules[child.id] || window.parentData.schedules[child.id].length === 0) {
                        window.parentData.schedules[child.id] = detailRes.data.schedule || [];
                    }
                }
            } catch (e) {
                console.warn('[DataSync] 获取孩子详情失败:', e.message);
            }
        }

        try {
            var msgRes = await apiService.getMessages();
            if (msgRes.code === 200) {
                window.parentData.messages = msgRes.data || [];
            }
        } catch (e) {}
    },

    async syncRecord(record, operation) {
        operation = operation || 'create';
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            var payload = {
                date: record.date,
                className: record.className || record.class,
                classId: record.classId || null,
                course: record.course || '',
                sessions: record.sessions || 1,
                type: record.type || '正常课',
                remark: record.remark || '',
                absent: record.absent || [],
                trialStudents: record.trialStudents || []
            };
            if (operation === 'create') {
                var res = await apiService.createRecord(payload);
                if (res.code === 200) record.id = res.id;
            } else if (operation === 'update' && record.id) {
                await apiService.updateRecord(record.id, payload);
            } else if (operation === 'delete' && record.id) {
                await apiService.deleteRecord(record.id);
            }
        } catch (e) {
            console.warn('[DataSync] 记录同步失败:', e.message);
            this._dirty = true;
        }
    },

    async syncStudent(student, operation, classInfo) {
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            if (operation === 'create') {
                var res = await apiService.createStudent({
                    name: student.name || student,
                    classId: classInfo ? classInfo.id : undefined,
                    parentPhone: student.parentPhone || '',
                    parentName: student.parentName || ''
                });
                return res;
            } else if (operation === 'delete' && student.id) {
                await apiService.deleteStudent(student.id);
            }
        } catch (e) {
            console.warn('[DataSync] 学生同步失败:', e.message);
            this._dirty = true;
        }
    },

    async syncClass(classInfo, operation) {
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            if (operation === 'create') {
                var res = await apiService.createClass({
                    name: classInfo.name, course: classInfo.course,
                    semesterId: classInfo.semesterId
                });
                if (res.code === 200) classInfo.id = res.id;
            } else if (operation === 'update' && classInfo.id) {
                await apiService.updateClass(classInfo.id, {
                    name: classInfo.name, course: classInfo.course,
                    semesterId: classInfo.semesterId
                });
            } else if (operation === 'delete' && classInfo.id) {
                await apiService.deleteClass(classInfo.id);
            }
        } catch (e) {
            console.warn('[DataSync] 班级同步失败:', e.message);
            this._dirty = true;
        }
    },

    async syncSchedule(schedule, operation) {
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            if (operation === 'create') {
                var res = await apiService.createSchedule({
                    weekday: schedule.weekday,
                    startTime: schedule.startTime,
                    endTime: schedule.endTime,
                    classId: schedule.classId,
                    className: schedule.className,
                    course: schedule.course,
                    sessions: schedule.sessions || 1
                });
                if (res.code === 200) schedule.id = res.id;
            } else if (operation === 'delete' && schedule.id) {
                await apiService.deleteSchedule(schedule.id);
            }
        } catch (e) {
            console.warn('[DataSync] 课表同步失败:', e.message);
            this._dirty = true;
        }
    },

    async syncSemester(semester, operation) {
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            if (operation === 'create') {
                var res = await apiService.createSemester({
                    name: semester.name,
                    startDate: semester.startDate,
                    endDate: semester.endDate,
                    isActive: semester.isActive
                });
                if (res.code === 200) semester.id = res.id;
            } else if (operation === 'update' && semester.id) {
                await apiService.updateSemester(semester.id, {
                    name: semester.name,
                    startDate: semester.startDate,
                    endDate: semester.endDate,
                    isActive: semester.isActive
                });
            }
        } catch (e) {
            console.warn('[DataSync] 学期同步失败:', e.message);
            this._dirty = true;
        }
    },

    async syncMsgTemplate(template, operation) {
        if (!apiService.isLoggedIn() || !apiService.isTeacher()) return;
        if (!navigator.onLine) { this._dirty = true; return; }
        try {
            var dbId = template._dbId || (template.id ? parseInt(String(template.id).replace('tpl_', '')) : null);
            if (operation === 'create') {
                var res = await apiService.createMsgTemplate({
                    name: template.name, content: template.content
                });
                if (res.code === 200) template._dbId = res.id;
            } else if (operation === 'update' && dbId) {
                await apiService.updateMsgTemplate(dbId, {
                    name: template.name, content: template.content
                });
            } else if (operation === 'delete' && dbId) {
                await apiService.deleteMsgTemplate(dbId);
            }
        } catch (e) {
            console.warn('[DataSync] 模板同步失败:', e.message);
            this._dirty = true;
        }
    },

    async maybeSync() {
        if (this._syncing) return;
        var elapsed = Date.now() - this._lastSync;
        if (this._dirty || elapsed > 60000) {
            await this.syncFromServer();
        }
    },

    getLastSyncText() {
        if (!this._lastSync) return '未同步';
        var elapsed = Math.floor((Date.now() - this._lastSync) / 1000);
        if (elapsed < 60) return elapsed + '秒前';
        if (elapsed < 3600) return Math.floor(elapsed / 60) + '分钟前';
        return new Date(this._lastSync).toLocaleString();
    }
};

window.addEventListener('online', function() {
    if (apiService.isLoggedIn()) {
        DataSync.syncFromServer();
    }
});

if (typeof module !== 'undefined' && module.exports) {
    module.exports = DataSync;
}
