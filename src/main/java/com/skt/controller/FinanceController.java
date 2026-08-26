package com.skt.controller;

import com.skt.security.RoleAccess;
import com.skt.service.FinanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    @Autowired
    private FinanceService financeService;

    // 学生账户列表
    @GetMapping("/accounts")
    public Map<String, Object> listAccounts(@RequestParam(required = false) Long classId, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", financeService.listStudentAccounts(classId));
        return result;
    }

    // 缴费记录
    @GetMapping("/payments")
    public Map<String, Object> listPayments(@RequestParam(required = false) Long studentId,
                                              @RequestParam(required = false) Long classId,
                                              HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", financeService.listPaymentRecords(studentId, classId));
        return result;
    }

    // 新增缴费
    @PostMapping("/payment")
    public Map<String, Object> addPayment(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        if (!RoleAccess.isTeacher(req)) {
            Map<String, Object> r = new HashMap<>();
            r.put("code", 403);
            r.put("msg", "无权限");
            return r;
        }
        Long studentId = body.get("studentId") != null ? Long.valueOf(body.get("studentId").toString()) : null;
        String studentName = (String) body.get("studentName");
        Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
        String className = (String) body.get("className");
        double amount = body.get("amount") != null ? Double.parseDouble(body.get("amount").toString()) : 0;
        Double hours = body.get("hours") != null ? Double.parseDouble(body.get("hours").toString()) : null;
        String paymentMethod = (String) body.get("paymentMethod");
        String receiptNo = (String) body.get("receiptNo");
        Long operatorId = (Long) req.getAttribute("userId");
        String operatorName = (String) req.getAttribute("username");
        String remark = (String) body.get("remark");
        return financeService.addPayment(studentId, studentName, classId, className, amount, hours,
            paymentMethod, receiptNo, operatorId, operatorName, remark);
    }

    // 财务统计
    @GetMapping("/stats")
    public Map<String, Object> getStats(HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            result.put("code", 403);
            result.put("msg", "无权限");
            return result;
        }
        result.put("code", 200);
        result.put("data", financeService.getFinanceStats());
        return result;
    }
}
