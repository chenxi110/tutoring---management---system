package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LeaveRequestService {

    @Autowired
    private JdbcTemplate jdbc;

    public Long create(Long studentId, String studentName, Long classId, String className,
                       Long parentId, String parentName, String leaveDate, String leaveType, String reason) {
        jdbc.update(
            "INSERT INTO leave_requests (student_id, student_name, class_id, class_name, parent_id, parent_name, leave_date, leave_type, reason) VALUES (?,?,?,?,?,?,?,?,?)",
            studentId, studentName, classId, className, parentId, parentName, leaveDate,
            leaveType != null ? leaveType : "sick", reason != null ? reason : "");
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Map<String, Object>> listForTeacher(Long classId, String status, Long teacherId, String role) {
        StringBuilder sql = new StringBuilder("SELECT * FROM leave_requests WHERE 1=1");
        List<Object> params = new ArrayList<>();
        // 教师角色：只能查看自己所带班级的请假
        if ("teacher".equals(role) && teacherId != null) {
            sql.append(" AND class_id IN (SELECT id FROM classes WHERE teacher_id=?)");
            params.add(teacherId);
        }
        if (classId != null) {
            sql.append(" AND class_id=?");
            params.add(classId);
        }
        if (status != null && !status.trim().isEmpty()) {
            sql.append(" AND status=?");
            params.add(status.trim());
        }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> listForParent(Long parentId) {
        return jdbc.queryForList(
            "SELECT * FROM leave_requests WHERE parent_id=? ORDER BY created_at DESC", parentId);
    }

    public Map<String, Object> approve(Long id, Long approverId, String approverName,
                                        boolean approved, String comment) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String status = approved ? "approved" : "rejected";
        int updated = jdbc.update(
            "UPDATE leave_requests SET status=?, approver_id=?, approver_name=?, approve_comment=?, approved_at=? WHERE id=? AND status='pending'",
            status, approverId, approverName, comment != null ? comment : "", now, id);
        if (updated == 0) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 400);
            r.put("msg", "该请假申请已处理或不存在");
            return r;
        }
        // 批准后同步到缺勤记录
        if (approved) {
            try {
                List<Map<String, Object>> reqs = jdbc.queryForList("SELECT * FROM leave_requests WHERE id=?", id);
                if (!reqs.isEmpty()) {
                    Map<String, Object> req = reqs.get(0);
                    Long studentId = req.get("student_id") != null ? ((Number) req.get("student_id")).longValue() : null;
                    String studentName = (String) req.get("student_name");
                    String leaveDate = req.get("leave_date") != null ? req.get("leave_date").toString() : null;
                    if (studentId != null && leaveDate != null) {
                        // 检查是否已存在缺勤记录
                        List<Map<String, Object>> existing = jdbc.queryForList(
                            "SELECT id FROM records WHERE student_id=? AND date=? AND type='absent'",
                            studentId, leaveDate);
                        if (existing.isEmpty()) {
                            jdbc.update(
                                "INSERT INTO records (student_id, student_name, date, type, remark, created_at) VALUES (?,?,?,?,?,?)",
                                studentId, studentName, leaveDate, "absent", "请假:" + (comment != null ? comment : ""), now);
                        }
                    }
                }
            } catch (Exception e) {
                // 同步缺勤失败不影响审批
            }
        }
        Map<String, Object> r = new HashMap<>();
        r.put("code", 200);
        r.put("msg", approved ? "已批准请假" : "已拒绝请假");
        return r;
    }
}
