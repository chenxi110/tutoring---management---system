package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class FinanceService {

    private static final Logger log = LoggerFactory.getLogger(FinanceService.class);

    @Autowired
    private JdbcTemplate jdbc;

    // 学生账户列表
    public List<Map<String, Object>> listStudentAccounts(Long classId) {
        if (classId != null) {
            return jdbc.queryForList(
                "SELECT sa.*, c.name as class_name FROM student_accounts sa " +
                "LEFT JOIN classes c ON c.id=sa.class_id WHERE sa.class_id=? ORDER BY sa.student_name", classId);
        }
        return jdbc.queryForList(
            "SELECT sa.*, c.name as class_name FROM student_accounts sa " +
            "LEFT JOIN classes c ON c.id=sa.class_id ORDER BY sa.class_id, sa.student_name");
    }

    // 缴费记录
    public List<Map<String, Object>> listPaymentRecords(Long studentId, Long classId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM payment_records WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (studentId != null) { sql.append(" AND student_id=?"); params.add(studentId); }
        if (classId != null) { sql.append(" AND class_id=?"); params.add(classId); }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    // 新增缴费记录
    public Map<String, Object> addPayment(Long studentId, String studentName, Long classId, String className,
                                           double amount, Double hours, String paymentMethod, String receiptNo,
                                           Long operatorId, String operatorName, String remark) {
        Map<String, Object> result = new HashMap<>();
        try {
            String receipt = receiptNo != null ? receiptNo : "PAY" + System.currentTimeMillis();
            jdbc.update(
                "INSERT INTO payment_records (student_id, student_name, class_id, amount, hours, payment_method, receipt_no, operator_id, operator_name, remark) VALUES (?,?,?,?,?,?,?,?,?,?)",
                studentId, studentName, classId, amount, hours, paymentMethod != null ? paymentMethod : "cash",
                receipt, operatorId, operatorName, remark != null ? remark : "");

            // 更新学生账户
            List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT * FROM student_accounts WHERE student_id=?", studentId);
            if (accounts.isEmpty()) {
                jdbc.update(
                    "INSERT INTO student_accounts (student_id, student_name, class_id, balance_hours, total_paid, status) VALUES (?,?,?,?,?, 'normal')",
                    studentId, studentName, classId, hours != null ? hours : 0, amount);
            } else {
                Map<String, Object> acc = accounts.get(0);
                double currentPaid = acc.get("total_paid") != null ? ((Number) acc.get("total_paid")).doubleValue() : 0;
                double currentHours = acc.get("balance_hours") != null ? ((Number) acc.get("balance_hours")).doubleValue() : 0;
                jdbc.update(
                    "UPDATE student_accounts SET total_paid=?, balance_hours=?, status='normal' WHERE student_id=?",
                    currentPaid + amount, currentHours + (hours != null ? hours : 0), studentId);
            }
            result.put("code", 200);
            result.put("msg", "缴费成功");
            result.put("receiptNo", receipt);
        } catch (Exception e) {
            result.put("code", 500);
            result.put("msg", "缴费失败：" + e.getMessage());
        }
        return result;
    }

    // 扣减课时（上课消耗）
    public void consumeHours(Long studentId, double hours) {
        try {
            List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT * FROM student_accounts WHERE student_id=?", studentId);
            if (!accounts.isEmpty()) {
                Map<String, Object> acc = accounts.get(0);
                double currentHours = acc.get("balance_hours") != null ? ((Number) acc.get("balance_hours")).doubleValue() : 0;
                double currentConsumed = acc.get("total_consumed") != null ? ((Number) acc.get("total_consumed")).doubleValue() : 0;
                double newBalance = Math.max(0, currentHours - hours);
                String status = newBalance <= 0 ? "arrears" : "normal";
                jdbc.update(
                    "UPDATE student_accounts SET balance_hours=?, total_consumed=?, status=? WHERE student_id=?",
                    newBalance, currentConsumed + hours, status, studentId);
            }
        } catch (Exception e) { log.warn("查询统计数据失败: {}", e.getMessage()); }
    }

    // 财务统计
    public Map<String, Object> getFinanceStats() {
        Map<String, Object> stats = new HashMap<>();
        try {
            Double totalPaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM payment_records", Double.class);
            Integer paymentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_records", Integer.class);
            Integer arrearsCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_accounts WHERE status='arrears'", Integer.class);
            stats.put("totalIncome", totalPaid != null ? totalPaid : 0);
            stats.put("paymentCount", paymentCount != null ? paymentCount : 0);
            stats.put("arrearsCount", arrearsCount != null ? arrearsCount : 0);
        } catch (Exception e) { log.warn("查询统计数据失败: {}", e.getMessage()); }
        return stats;
    }
}
