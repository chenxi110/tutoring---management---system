class ApiService {
    constructor() {
        this.baseURL = localStorage.getItem('skt_api_base') || '/api';
        this.token = localStorage.getItem('skt_token') || '';
        this.user = JSON.parse(localStorage.getItem('skt_user') || 'null');
        this.pendingQueue = JSON.parse(localStorage.getItem('skt_pending_queue') || '[]');
        this.sseEventSource = null;
        this.messageCallbacks = [];
        this._pollingTimer = null;
        this._sseFailed = false;
        this._initSyncListener();
    }

    setBaseURL(url) {
        const finalUrl = (url || '/api').trim();
        this.baseURL = finalUrl.endsWith('/api') || finalUrl.endsWith('/api/') ? finalUrl : finalUrl.replace(/\/$/, '') + '/api';
        localStorage.setItem('skt_api_base', this.baseURL);
    }

    setAuth(token, user) {
        this.token = token;
        this.user = user;
        if (typeof window !== 'undefined') {
            window.currentUser = user || null;
            window.currentRole = user && user.role ? user.role : null;
        }
        localStorage.setItem('skt_token', token);
        localStorage.setItem('skt_user', JSON.stringify(user));
        localStorage.setItem('skt_user_role', user && user.role ? user.role : '');
        sessionStorage.setItem('skt_user_role', user && user.role ? user.role : '');
    }

    clearAuth() {
        this.token = '';
        this.user = null;
        if (typeof window !== 'undefined') {
            window.currentUser = null;
            window.currentRole = null;
        }
        ['skt_token','skt_user','skt_user_role','skt_sidebar_role','skt_menu_cache','skt_sidebar_cache','skt_last_role'].forEach(function(key){
            localStorage.removeItem(key);
            sessionStorage.removeItem(key);
        });
        this.closeSSE();
        this.stopPolling();
    }

    isLoggedIn() { return !!this.token; }
    isTeacher() { return this.user && this.user.role === 'teacher'; }
    isParent() { return this.user && this.user.role === 'parent'; }
    isAdmin() { return this.user && this.user.role === 'admin'; }

    async request(method, path, body) {
        var url = (path && path.indexOf('/api/') === 0) ? path : this.baseURL + path;
        var options = {
            method: method,
            headers: {
                'Content-Type': 'application/json',
                ...(this.token ? { 'Authorization': 'Bearer ' + this.token } : {})
            }
        };
        if (body && ['POST', 'PUT', 'PATCH'].includes(method)) {
            options.body = JSON.stringify(body);
        }
        let res;
        try {
            res = await fetch(url, options);
        } catch (e) {
            throw new Error('网络请求失败，请检查网络或稍后重试');
        }
        if (res.status === 401) {
            this.clearAuth();
            window.location.reload();
            throw new Error('登录已过期');
        }

        // 兼容服务器可能返回空响应或非 JSON 的情况
        let text = null;
        try {
            text = await res.text();
        } catch (e) {
            text = null;
        }
        let data = null;
        if (text && text.length > 0) {
            try {
                data = JSON.parse(text);
            } catch (e) {
                data = { error: text };
            }
        }

        if (!res.ok) {
            const errMsg = (data && (data.error || data.msg)) || res.statusText || ('请求失败 (' + res.status + ')');
            throw new Error(errMsg);
        }

        // 有些接口直接返回数组或空 body，统一返回对象/数组即可
        return data === null ? {} : data;
    }


    async get(path) {
        return this.request('GET', path);
    }

    async post(path, body) {
        return this.request('POST', path, body);
    }
    async login(username, password) {
        var data = await this.request('POST', '/auth/login', { username: username, password: password });
        if (data.code === 200) {
            this.setAuth(data.token, data.user);
            this.connectSSE();
        }
        return data;
    }

    async register(username, password, role, displayName, phone) {
        var payload = { username: username, password: password, role: role };
        if (role === 'parent' && displayName) payload.displayName = displayName;
        if (phone) payload.phone = phone;
        var data = await this.request('POST', '/auth/register', payload);
        if (data.code === 200) {
            this.setAuth(data.token, data.user);
            this.connectSSE();
        }
        return data;
    }

    async getProfile() { return this.request('GET', '/auth/profile'); }

    async getParentChildren() { return this.request('GET', '/parent/children'); }
    async bindStudent(studentName, parentPhone) { return this.request('POST', '/parent/bind', { studentName: studentName, parentPhone: parentPhone }); }

    async getSemesters() { return this.request('GET', '/semesters'); }
    async createSemester(data) { return this.request('POST', '/semesters', data); }
    async updateSemester(id, data) { return this.request('PUT', '/semesters/' + id, data); }

    async getClasses(semesterId) {
        var query = semesterId ? '?semesterId=' + semesterId : '';
        return this.request('GET', '/classes' + query);
    }
    async getMyClasses(semesterId) {
        var query = semesterId ? '?semesterId=' + semesterId : '';
        return this.request('GET', '/classes/my' + query);
    }
    async createClass(data) { return this.request('POST', '/classes', data); }
    async updateClass(id, data) { return this.request('PUT', '/classes/' + id, data); }
    async deleteClass(id) { return this.request('DELETE', '/classes/' + id); }

    async getClassStudents(classId) { return this.request('GET', '/classes/' + classId + '/students'); }
    async getStudents(classId) {
        var query = classId ? '?classId=' + classId : '';
        return this.request('GET', '/students' + query);
    }
    async getStudentDetail(id) { return this.request('GET', '/students/' + id); }
    async createStudent(data) { return this.request('POST', '/students', data); }
    async updateStudent(id, data) { return this.request('PUT', '/students/' + id, data); }
    async deleteStudent(id) { return this.request('DELETE', '/students/' + id); }
    async getStudentClasses(id) { return this.request('GET', '/students/' + id + '/classes'); }
    async addStudentClass(id, classId) { return this.request('POST', '/students/' + id + '/classes', { classId: classId }); }
    async removeStudentClass(id, classId) { return this.request('DELETE', '/students/' + id + '/classes/' + classId); }

    async createShareToken(studentId, isPermanent, validDays) { return this.request('POST', '/share/tokens', { studentId: studentId, isPermanent: isPermanent, validDays: validDays }); }
    async listShareTokens(studentId) { return this.request('GET', '/share/tokens?studentId=' + studentId); }
    async deleteShareToken(id) { return this.request('DELETE', '/share/tokens/' + id); }

    async getRecords(filters) {
        filters = filters || {};
        var params = new URLSearchParams();
        if (filters.classId) params.set('classId', filters.classId);
        if (filters.className) params.set('className', filters.className);
        if (filters.date) params.set('date', filters.date);
        if (filters.semesterId) params.set('semesterId', filters.semesterId);
        var query = params.toString() ? '?' + params : '';
        return this.request('GET', '/records' + query);
    }
    async createRecord(data) { return this.request('POST', '/records', data); }
    async updateRecord(id, data) { return this.request('PUT', '/records/' + id, data); }
    async deleteRecord(id) { return this.request('DELETE', '/records/' + id); }

    async getSchedules(classId, semesterId) {
        var params = new URLSearchParams();
        if (classId) params.set('classId', classId);
        if (semesterId) params.set('semesterId', semesterId);
        var query = params.toString() ? '?' + params : '';
        return this.request('GET', '/schedules' + query);
    }
    async getMySchedules(semesterId) {
        var query = semesterId ? '?semesterId=' + semesterId : '';
        return this.request('GET', '/schedules/my' + query);
    }
    async getChildSchedules() { return this.request('GET', '/schedules/child'); }
    async getNextClass() { return this.request('GET', '/schedules/next'); }
    async createSchedule(data) { return this.request('POST', '/schedules', data); }
    async batchCreateSchedules(data) { return this.request('POST', '/schedules/batch', data); }
    async deleteSchedule(id) { return this.request('DELETE', '/schedules/' + id); }

    async getMessages() { return this.request('GET', '/messages'); }
    async sendMessage(data) { return this.request('POST', '/messages', data); }
    async markMessageRead(id) { return this.request('PUT', '/messages/' + id + '/read'); }
    async getUnreadCount() { return this.request('GET', '/messages/unread/count'); }
    async replyMessage(id, data) { return this.request('POST', '/messages/' + id + '/reply', data); }

    async getMsgTemplates() { return this.request('GET', '/msg-templates'); }
    async createMsgTemplate(data) { return this.request('POST', '/msg-templates', data); }
    async updateMsgTemplate(id, data) { return this.request('PUT', '/msg-templates/' + id, data); }
    async deleteMsgTemplate(id) { return this.request('DELETE', '/msg-templates/' + id); }

    async getHomework(classId) {
        var query = classId ? '?classId=' + classId : '';
        var endpoint = this.isParent() ? '/homework/my' : '/homework';
        return this.request('GET', endpoint + query);
    }
    async createHomework(data) { return this.request('POST', '/homework', data); }
    async submitHomework(id, data) { return this.request('POST', '/homework/' + id + '/submit', data); }
    async gradeSubmission(id, data) { return this.request('PUT', '/homework/submissions/' + id + '/grade', data); }

    // 成绩管理
    async getGrades(filters) {
        filters = filters || {};
        var params = new URLSearchParams();
        if (filters.classId) params.set('classId', filters.classId);
        if (filters.studentId) params.set('studentId', filters.studentId);
        if (filters.examName) params.set('examName', filters.examName);
        if (filters.semesterId) params.set('semesterId', filters.semesterId);
        var query = params.toString() ? '?' + params : '';
        var endpoint = this.isParent() ? '/grades/my' : '/grades';
        return this.request('GET', endpoint + query);
    }
    async getMyGrades() { return this.request('GET', '/grades/my'); }
    async createGrade(data) { return this.request('POST', '/grades', data); }
    async batchCreateGrades(data) { return this.request('POST', '/grades/batch', data); }
    async updateGrade(id, data) { return this.request('PUT', '/grades/' + id, data); }
    async deleteGrade(id) { return this.request('DELETE', '/grades/' + id); }
    async getGradeStats(classId, examName) {
        var params = new URLSearchParams();
        params.set('classId', classId);
        params.set('examName', examName);
        return this.request('GET', '/grades/stats?' + params);
    }

    async exportData() { return this.request('GET', '/export'); }
    async importData(data) { return this.request('POST', '/import', { data: data }); }

    // 考试模块
    async launchExam(data) { return this.request('POST', '/exam/launch', data); }
    async getStudentExamInfo(examCode, studentName, password) {
        var params = new URLSearchParams();
        params.set('examCode', examCode);
        params.set('studentName', studentName);
        if (password) params.set('password', password);
        return this.request('GET', '/exam/getStudentExamInfo?' + params.toString());
    }
    async submitExam(data) { return this.request('POST', '/exam/submit', data); }

    // 课堂文件互传
    async uploadCourseFile(file, classId) {
        var formData = new FormData();
        formData.append('file', file);
        formData.append('classId', classId);
        var url = this.baseURL + '/course/file/uploadTeacherFile';
        var res = await fetch(url, {
            method: 'POST',
            headers: this.token ? { 'Authorization': 'Bearer ' + this.token } : {},
            body: formData
        });
        return await res.json();
    }
    async submitCourseFile(file, classId, studentId) {
        var formData = new FormData();
        formData.append('file', file);
        formData.append('classId', classId);
        formData.append('studentId', studentId);
        var url = this.baseURL + '/course/file/uploadStudentWork';
        var res = await fetch(url, {
            method: 'POST',
            headers: this.token ? { 'Authorization': 'Bearer ' + this.token } : {},
            body: formData
        });
        return await res.json();
    }
    async getCourseFiles(classId) {
        return this.request('GET', '/course/file/listByClassId?classId=' + classId);
    }
    getCourseFileDownloadUrl(fileId) {
        return this.baseURL + '/course/file/download/' + fileId + '?token=' + encodeURIComponent(this.token);
    }

    connectSSE() {
        if (!this.token) return;
        this.closeSSE();
        var self = this;
        this._sseFailed = false;
        this.sseEventSource = new EventSource(this.baseURL + '/sse?token=' + encodeURIComponent(this.token));
        this.sseEventSource.onmessage = function(event) {
            try {
                var data = JSON.parse(event.data);
                if (data.type === 'new_message') {
                    self.messageCallbacks.forEach(function(cb) { cb(data); });
                }
            } catch (e) {}
        };
        this.sseEventSource.onopen = function() {
            self._sseFailed = false;
            self.stopPolling();
        };
        this.sseEventSource.onerror = function() {
            self._sseFailed = true;
            self.closeSSE();
            self.startPolling();
        };
    }

    closeSSE() {
        if (this.sseEventSource) {
            this.sseEventSource.close();
            this.sseEventSource = null;
        }
    }

    startPolling() {
        if (this._pollingTimer) return;
        if (!this.token) return;
        var self = this;
        this._pollingTimer = setInterval(async function() {
            if (!self.token) { self.stopPolling(); return; }
            try {
                var res = await self.request('GET', '/messages/unread/count');
                if (res && res.code === 200 && res.count !== undefined) {
                    self.messageCallbacks.forEach(function(cb) { cb({ type: 'poll_check', count: res.count }); });
                }
            } catch (e) {}
        }, 5000);
    }

    stopPolling() {
        if (this._pollingTimer) {
            clearInterval(this._pollingTimer);
            this._pollingTimer = null;
        }
    }

    onMessage(callback) { this.messageCallbacks.push(callback); }

    _initSyncListener() {
        var self = this;
        window.addEventListener('online', function() {
            self._flushPendingQueue();
        });
    }

    _addToPendingQueue(method, path, body) {
        this.pendingQueue.push({ method: method, path: path, body: body, timestamp: Date.now() });
        localStorage.setItem('skt_pending_queue', JSON.stringify(this.pendingQueue));
    }

    async _flushPendingQueue() {
        if (this.pendingQueue.length === 0) return;
        var queue = this.pendingQueue.slice();
        this.pendingQueue = [];
        localStorage.setItem('skt_pending_queue', JSON.stringify(this.pendingQueue));
        for (var i = 0; i < queue.length; i++) {
            try {
                await this.request(queue[i].method, queue[i].path, queue[i].body);
            } catch (e) {
                this.pendingQueue.push(queue[i]);
            }
        }
        localStorage.setItem('skt_pending_queue', JSON.stringify(this.pendingQueue));
    }

    async migrateLocalData() {
        var localData = localStorage.getItem('tutoringData');
        if (!localData) return { migrated: false };
        try {
            var parsed = JSON.parse(localData);
            var appData = parsed.appData || parsed;
            await this.importData({
                semesters: appData.semesters || [],
                classes: appData.classes || [],
                students: [],
                records: appData.records || []
            });
            localStorage.setItem('skt_migrated', 'true');
            return { migrated: true };
        } catch (e) {
            return { migrated: false, error: e.message };
        }
    }

    // 获取用户列表（仅教师）
    async getUsers() {
        return await this.request('GET', '/user/list');
    }

    // 重置用户密码为123456（仅教师管理员）
    async resetPassword(userId) {
        return await this.request('POST', '/user/resetPwd', { userId: userId });
    }

    // 管理员新增用户（仅admin）
    async createUser(data) {
        return await this.request('POST', '/user/create', data);
    }

    // 管理员编辑用户（仅admin）
    async updateUser(data) {
        return await this.request('POST', '/user/update', data);
    }

    // 管理员启用/禁用用户（仅admin）
    async toggleUserStatus(userId, status) {
        return await this.request('POST', '/user/toggleStatus', { userId: userId, status: status });
    }

    // 修改本人密码（所有角色）
    async updateMyPassword(oldPassword, newPassword, confirmPassword) {
        return await this.request('POST', '/user/updateMyPwd', {
            oldPassword: oldPassword,
            newPassword: newPassword,
            confirmPassword: confirmPassword
        });
    }
}

var apiService = new ApiService();
if (apiService.isLoggedIn()) {
    apiService.connectSSE();
}
if (typeof module !== 'undefined' && module.exports) {
    module.exports = apiService;
}
