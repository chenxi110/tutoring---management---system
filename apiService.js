class ApiService {
    constructor() {
        this.baseURL = '/api';
        this.token = localStorage.getItem('skt_token') || '';
        try {
            this.user = JSON.parse(localStorage.getItem('skt_user') || 'null');
        } catch(e) {
            console.warn('[上课通] skt_user 缓存损坏，已清除:', e.message);
            localStorage.removeItem('skt_user');
            localStorage.removeItem('skt_token');
            this.user = null;
            this.token = '';
        }
        try {
            this.pendingQueue = JSON.parse(localStorage.getItem('skt_pending_queue') || '[]');
        } catch(e) {
            console.warn('[上课通] skt_pending_queue 缓存损坏，已清除:', e.message);
            localStorage.removeItem('skt_pending_queue');
            this.pendingQueue = [];
        }
        this.sseEventSource = null;
        this.messageCallbacks = [];
        this._initSyncListener();
    }

    setAuth(token, user) {
        this.token = token;
        this.user = user;
        localStorage.setItem('skt_token', token);
        localStorage.setItem('skt_user', JSON.stringify(user));
    }

    clearAuth() {
        this.token = '';
        this.user = null;
        localStorage.removeItem('skt_token');
        localStorage.removeItem('skt_user');
        this.closeSSE();
    }

    isLoggedIn() { return !!this.token; }
    isTeacher() { return this.user && this.user.role === 'teacher'; }
    isParent() { return this.user && this.user.role === 'parent'; }

    async request(method, path, body) {
        var url = this.baseURL + path;
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
        var res = await fetch(url, options);
        if (res.status === 401) {
            if (path.indexOf('/auth/login') === 0 || path.indexOf('/auth/register') === 0) {
                var errData = await res.json().catch(function() { return {}; });
                throw new Error(errData.error || errData.msg || '用户名或密码错误');
            }
            this.clearAuth();
            window.location.reload();
            throw new Error('登录已过期');
        }
        var data = await res.json();
        if (!res.ok) throw new Error(data.error || data.msg || '请求失败');
        return data;
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
        var data = await this.request('POST', '/auth/register', { username: username, password: password, role: role, displayName: displayName, phone: phone });
        if (data.code === 200) {
            this.setAuth(data.token, data.user);
            this.connectSSE();
        }
        return data;
    }

    async getProfile() { return this.request('GET', '/auth/profile'); }

    async getParentChildren() { return this.request('GET', '/parent/children'); }
    async getBindableStudents() { return this.request('GET', '/parent/bindable-students'); }
    async bindStudent(studentId) { return this.request('POST', '/parent/bind', { studentId: studentId }); }

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

    async getMsgTemplates() { return this.request('GET', '/msg-templates'); }
    async createMsgTemplate(data) { return this.request('POST', '/msg-templates', data); }
    async updateMsgTemplate(id, data) { return this.request('PUT', '/msg-templates/' + id, data); }
    async deleteMsgTemplate(id) { return this.request('DELETE', '/msg-templates/' + id); }

    async getHomework(classId) {
        var query = classId ? '?classId=' + classId : '';
        return this.request('GET', '/homework' + query);
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
        return this.request('GET', '/grades' + query);
    }
    async createGrade(data) { return this.request('POST', '/grades', data); }
    async batchCreateGrades(data) { return this.request('POST', '/grades/batch', data); }
    async updateGrade(id, data) { return this.request('PUT', '/grades/' + id, data); }
    async deleteGrade(id) { return this.request('DELETE', '/grades/' + id); }
    async getMyGrades() { return this.request('GET', '/grades/my'); }
    async getGradeStats(classId, examName) {
        var params = new URLSearchParams();
        params.set('classId', classId);
        params.set('examName', examName);
        return this.request('GET', '/grades/stats?' + params);
    }

    async exportData() { return this.request('GET', '/export'); }
    async importData(data) { return this.request('POST', '/import', { data: data }); }

    connectSSE() {
        if (!this.token) return;
        this.closeSSE();
        var self = this;
        this.sseEventSource = new EventSource(this.baseURL + '/sse?token=' + encodeURIComponent(this.token));
        this.sseEventSource.onmessage = function(event) {
            try {
                var data = JSON.parse(event.data);
                if (data.type === 'new_message') {
                    self.messageCallbacks.forEach(function(cb) { cb(data); });
                }
            } catch (e) {}
        };
        this.sseEventSource.onerror = function() {
            setTimeout(function() { if (self.token) self.connectSSE(); }, 5000);
        };
    }

    closeSSE() {
        if (this.sseEventSource) {
            this.sseEventSource.close();
            this.sseEventSource = null;
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
}

var apiService = new ApiService();
if (apiService.isLoggedIn()) {
    apiService.connectSSE();
}
if (typeof module !== 'undefined' && module.exports) {
    module.exports = apiService;
}
