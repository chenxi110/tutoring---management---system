package com.skt.controller;

import com.skt.security.RoleAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.*;

/**
 * 学生管理 CRUD 接口（前端学生名单模块依赖）。
 * 对应前端 apiService：createStudent/updateStudent/deleteStudent/getStudents/getClassStudents。
 * 包含：手机号唯一校验、同名学生展示名（班级名+学号后四位）、学生-班级多对多关联（student_class）。
 */
@RestController
@RequestMapping("/api")
public class StudentManageController {

    private static final Logger log = LoggerFactory.getLogger(StudentManageController.class);

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 为返回的学生列表附加 displayName：存在同名学生时，展示「姓名（班级名-学号后四位）」以便区分。
     */
    private void attachDisplayName(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) return;
        Map<String, Integer> nameCount = new HashMap<>();
        for (Map<String, Object> s : list) {
            Object n = s.get("name");
            if (n == null) continue;
            String nm = String.valueOf(n);
            nameCount.merge(nm, 1, Integer::sum);
        }
        Set<String> dupNames = new HashSet<>();
        nameCount.forEach((n, c) -> { if (c > 1) dupNames.add(n); });
        if (dupNames.isEmpty()) {
            for (Map<String, Object> s : list) s.put("displayName", String.valueOf(s.get("name")));
            return;
        }
        Map<Long, String> classNames = new HashMap<>();
        for (Map<String, Object> c : jdbc.queryForList("SELECT id, name FROM classes")) {
            classNames.put(((Number) c.get("id")).longValue(), String.valueOf(c.get("name")));
        }
        for (Map<String, Object> s : list) {
            String nm = String.valueOf(s.get("name"));
            if (!dupNames.contains(nm)) { s.put("displayName", nm); continue; }
            Object id = s.get("id");
            String suffix = id != null ? String.format("%04d", ((Number) id).longValue() % 10000) : "????";
            Object cid = s.get("class_id");
            String cn = "";
            if (cid != null) {
                long cidv = ((Number) cid).longValue();
                String clsName = classNames.get(cidv);
                if (clsName != null && !clsName.trim().isEmpty() && clsName.indexOf('?') < 0) {
                    cn = clsName;
                } else {
                    cn = "班级" + cidv;
                }
            }
            s.put("displayName", nm + "（" + cn + "-" + suffix + "）");
        }
    }

    // 获取学生列表（可按班级过滤；教师按自己班级隔离；多班级经 student_class 关联）
    @GetMapping("/students")
    public Map<String, Object> listStudents(@RequestParam(required = false) Long classId,
                                            HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        try {
            String role = (String) req.getAttribute("role");
            List<Map<String, Object>> list;
            if ("parent".equals(role)) {
                Long parentId = (Long) req.getAttribute("userId");
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE (s.parent_id = ? OR s.parent_user_id = ?) AND (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                    "ORDER BY s.name",
                    parentId, parentId);
            } else if ("teacher".equals(role)) {
                Long teacherId = (Long) req.getAttribute("userId");
                if (classId != null) {
                    // 教师只能查看自己班级的学生，防越权（经 student_class 关联）
                    list = jdbc.queryForList(
                        "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                        "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                        "AND s.id IN (SELECT student_id FROM student_class WHERE class_id = ? " +
                        "  AND class_id IN (SELECT id FROM classes WHERE teacher_id = ?)) ORDER BY s.name",
                        classId, teacherId);
                } else {
                    list = jdbc.queryForList(
                        "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                        "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                        "AND s.id IN (SELECT student_id FROM student_class " +
                        "  WHERE class_id IN (SELECT id FROM classes WHERE teacher_id = ?)) ORDER BY s.name",
                        teacherId);
                }
            } else if (classId != null) {
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) " +
                    "AND s.id IN (SELECT student_id FROM student_class WHERE class_id = ?) ORDER BY s.name",
                    classId);
            } else {
                list = jdbc.queryForList(
                    "SELECT s.*, c.name AS class_name FROM students s LEFT JOIN classes c ON s.class_id = c.id " +
                    "WHERE (s.is_deleted IS NULL OR s.is_deleted = 0) ORDER BY s.name");
            }
            attachDisplayName(list);
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception ex) {
            log.error("查询学生列表失败", ex);
            result.put("code", 500);
            result.put("msg", "查询学生列表失败：" + ex.getMessage());
            return result;
        }
    }

    // 新增学生（手机号唯一校验 + 写 student_class 多班级关联）
    @PostMapping("/students")
    public Map<String, Object> createStudent(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可新增学生");
        }
        try {
            String name = body.get("name") != null ? body.get("name").toString().trim() : "";
            if (name.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "学生姓名不能为空");
                return result;
            }
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            String phone = body.get("phone") != null ? body.get("phone").toString().trim() : null;
            String parentPhone = body.get("parentPhone") != null ? body.get("parentPhone").toString().trim() : null;
            String parentName = body.get("parentName") != null ? body.get("parentName").toString().trim() : null;
            String parentRelation = body.get("parentRelation") != null ? body.get("parentRelation").toString().trim() : null;

            // 手机号校验：已存在时复用该学生并加入当前班级（支持一个学生有多个老师/多个班级）
            if (phone != null && !phone.isEmpty()) {
                List<Map<String, Object>> dup = jdbc.queryForList(
                    "SELECT id FROM students WHERE phone = ? AND (is_deleted IS NULL OR is_deleted = 0) LIMIT 1",
                    phone);
                if (!dup.isEmpty()) {
                    Long existingId = ((Number) dup.get(0).get("id")).longValue();
                    boolean inClass = false;
                    if (classId != null) {
                        List<Long> sc = jdbc.queryForList(
                            "SELECT id FROM student_class WHERE student_id = ? AND class_id = ? LIMIT 1",
                            Long.class, existingId, classId);
                        inClass = !sc.isEmpty();
                    }
                    if (inClass) {
                        result.put("code", 400);
                        result.put("msg", "该学生已在本班级");
                        return result;
                    }
                    if (classId != null) {
                        try {
                            jdbc.update("INSERT IGNORE INTO student_class (student_id, class_id) VALUES (?, ?)", existingId, classId);
                        } catch (Exception ignore) { }
                    }
                    result.put("code", 200);
                    result.put("id", existingId);
                    result.put("msg", "该学生已存在，已加入当前班级");
                    return result;
                }
            }

            jdbc.update(
                "INSERT INTO students (name, class_id, phone, parent_phone, parent_name, parent_relation, status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'active', NOW())",
                name, classId, (phone != null && !phone.isEmpty()) ? phone : null,
                parentPhone, parentName, parentRelation);
            Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            // 写入多班级关联（主班级）
            if (classId != null) {
                try {
                    jdbc.update("INSERT IGNORE INTO student_class (student_id, class_id) VALUES (?, ?)", id, classId);
                } catch (Exception ignore) { }
            }
            result.put("code", 200);
            result.put("id", id);
            result.put("msg", "添加成功");
            return result;
        } catch (Exception ex) {
            log.error("新增学生失败", ex);
            result.put("code", 500);
            result.put("msg", "新增学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 修改学生（手机号唯一校验，排除自身；修改班级同步 student_class）
    @PutMapping("/students/{id}")
    public Map<String, Object> updateStudent(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可修改学生");
        }
        try {
            String name = body.get("name") != null ? body.get("name").toString().trim() : null;
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            String phone = body.get("phone") != null ? body.get("phone").toString().trim() : null;
            String parentPhone = body.get("parentPhone") != null ? body.get("parentPhone").toString().trim() : null;
            String parentName = body.get("parentName") != null ? body.get("parentName").toString().trim() : null;
            String parentRelation = body.get("parentRelation") != null ? body.get("parentRelation").toString().trim() : null;

            // 手机号唯一校验（排除自身）
            if (phone != null && !phone.isEmpty()) {
                List<Long> dupPhone = jdbc.queryForList(
                    "SELECT id FROM students WHERE phone = ? AND id <> ? AND (is_deleted IS NULL OR is_deleted = 0) LIMIT 1",
                    Long.class, phone, id);
                if (!dupPhone.isEmpty()) {
                    result.put("code", 400);
                    result.put("msg", "该手机号已注册学生账号");
                    return result;
                }
            }

            List<Object> args = new ArrayList<>();
            StringBuilder sql = new StringBuilder("UPDATE students SET ");
            boolean first = true;
            if (name != null && !name.isEmpty()) {
                sql.append("name = ?"); args.add(name); first = false;
            }
            if (classId != null) {
                if (!first) sql.append(", ");
                sql.append("class_id = ?"); args.add(classId); first = false;
            }
            if (phone != null) {
                if (!first) sql.append(", ");
                sql.append("phone = ?"); args.add((phone.isEmpty()) ? null : phone); first = false;
            }
            if (parentPhone != null) {
                if (!first) sql.append(", ");
                sql.append("parent_phone = ?"); args.add(parentPhone); first = false;
            }
            if (parentName != null) {
                if (!first) sql.append(", ");
                sql.append("parent_name = ?"); args.add(parentName); first = false;
            }
            if (parentRelation != null) {
                if (!first) sql.append(", ");
                sql.append("parent_relation = ?"); args.add(parentRelation); first = false;
            }
            if (first) {
                result.put("code", 400);
                result.put("msg", "没有需要更新的字段");
                return result;
            }
            sql.append(" WHERE id = ?");
            args.add(id);
            jdbc.update(sql.toString(), args.toArray());
            // 修改主班级时同步 student_class
            if (classId != null) {
                try {
                    jdbc.update("INSERT IGNORE INTO student_class (student_id, class_id) VALUES (?, ?)", id, classId);
                } catch (Exception ignore) { }
            }
            result.put("code", 200);
            result.put("msg", "修改成功");
            return result;
        } catch (Exception ex) {
            log.error("修改学生失败 id={}", id, ex);
            result.put("code", 500);
            result.put("msg", "修改学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 删除学生（软删除，可回收站恢复）
    @DeleteMapping("/students/{id}")
    public Map<String, Object> deleteStudent(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可删除学生");
        }
        try {
            jdbc.update("UPDATE students SET is_deleted = 1, phone = NULL, parent_phone = NULL WHERE id = ?", id);
            result.put("code", 200);
            result.put("msg", "删除成功");
            return result;
        } catch (Exception ex) {
            log.error("删除学生失败 id={}", id, ex);
            result.put("code", 500);
            result.put("msg", "删除学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 班级学生列表（成绩录入等下拉选择用；经 student_class 关联，含多班级学生）
    @GetMapping("/classes/{id}/students")
    public Map<String, Object> classStudents(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT s.id, s.name, s.class_id, s.phone, s.parent_phone, s.parent_name, s.status FROM students s " +
                "WHERE s.id IN (SELECT student_id FROM student_class WHERE class_id = ?) " +
                "AND (s.is_deleted IS NULL OR s.is_deleted = 0) ORDER BY s.name",
                id);
            attachDisplayName(list);
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception ex) {
            log.error("查询班级学生失败 classId={}", id, ex);
            result.put("code", 500);
            result.put("msg", "查询班级学生失败：" + ex.getMessage());
            return result;
        }
    }

    // 查询学生所属全部班级及授课教师（管理员/教师可用）
    @GetMapping("/students/{id}/classes")
    public Map<String, Object> studentClasses(@PathVariable Long id, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可查看学生班级");
        }
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT sc.class_id, c.name AS class_name, c.course, " +
                "       u.display_name AS teacher_name, u.username AS teacher_username " +
                "FROM student_class sc " +
                "JOIN classes c ON c.id = sc.class_id " +
                "LEFT JOIN users u ON u.id = c.teacher_id " +
                "WHERE sc.student_id = ? ORDER BY c.name", id);
            result.put("code", 200);
            result.put("data", list);
            return result;
        } catch (Exception ex) {
            log.error("查询学生班级失败 studentId={}", id, ex);
            result.put("code", 500);
            result.put("msg", "查询学生班级失败：" + ex.getMessage());
            return result;
        }
    }

    // 为学生添加班级（多班级归属；教师仅可添加自己班级）
    @PostMapping("/students/{id}/classes")
    public Map<String, Object> addStudentClass(@PathVariable Long id, @RequestBody Map<String, Object> body,
                                               HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可为学生添加班级");
        }
        try {
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            if (classId == null) {
                result.put("code", 400);
                result.put("msg", "缺少班级ID");
                return result;
            }
            // 学生存在校验
            List<Long> st = jdbc.queryForList(
                "SELECT id FROM students WHERE id = ? AND (is_deleted IS NULL OR is_deleted = 0) LIMIT 1",
                Long.class, id);
            if (st.isEmpty()) {
                result.put("code", 404);
                result.put("msg", "学生不存在");
                return result;
            }
            // 教师角色只能添加自己的班级（管理员不受限）
            if (!RoleAccess.isAdmin(req)) {
                List<Long> owned = jdbc.queryForList(
                    "SELECT id FROM classes WHERE id = ? AND teacher_id = ? LIMIT 1",
                    Long.class, classId, (Long) req.getAttribute("userId"));
                if (owned.isEmpty()) {
                    result.put("code", 403);
                    result.put("msg", "无权限：只能将学生添加到自己所带班级");
                    return result;
                }
            }
            int cnt = jdbc.update("INSERT IGNORE INTO student_class (student_id, class_id) VALUES (?, ?)", id, classId);
            if (cnt == 0) {
                result.put("code", 400);
                result.put("msg", "该学生已在此班级中");
                return result;
            }
            result.put("code", 200);
            result.put("msg", "添加班级成功");
            return result;
        } catch (Exception ex) {
            log.error("添加学生班级失败 studentId={}", id, ex);
            result.put("code", 500);
            result.put("msg", "添加班级失败：" + ex.getMessage());
            return result;
        }
    }

    // 移除学生的班级归属（不可移除主班级）
    @DeleteMapping("/students/{id}/classes/{classId}")
    public Map<String, Object> removeStudentClass(@PathVariable Long id, @PathVariable Long classId,
                                                  HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可移除学生班级");
        }
        try {
            // 主班级不允许移除，防止失去归属
            List<Long> mainClass = jdbc.queryForList(
                "SELECT id FROM students WHERE id = ? AND class_id = ? AND (is_deleted IS NULL OR is_deleted = 0) LIMIT 1",
                Long.class, id, classId);
            if (!mainClass.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "主班级不可移除，请先在编辑中更换主班级");
                return result;
            }
            jdbc.update("DELETE FROM student_class WHERE student_id = ? AND class_id = ?", id, classId);
            result.put("code", 200);
            result.put("msg", "移除班级成功");
            return result;
        } catch (Exception ex) {
            log.error("移除学生班级失败 studentId={}", id, ex);
            result.put("code", 500);
            result.put("msg", "移除班级失败：" + ex.getMessage());
            return result;
        }
    }
    // 学生Excel导入模板下载
    @GetMapping("/students/import/template")
    public void downloadStudentTemplate(HttpServletRequest req, HttpServletResponse resp) {
        if (!RoleAccess.isTeacher(req)) {
            try {
                resp.setStatus(403);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"code\":403,\"msg\":\"无权限下载模板\"}");
            } catch (Exception ignored) { }
            return;
        }
        try {
            try (Workbook wb = new XSSFWorkbook()) {
                Sheet sheet = wb.createSheet("学生导入模板");
                String[] headers = {"姓名", "手机号", "家长手机号", "家长姓名", "家长称谓"};
                Row hr = sheet.createRow(0);
                for (int c = 0; c < headers.length; c++) {
                    Cell cell = hr.createCell(c);
                    cell.setCellValue(headers[c]);
                }
                Row tip = sheet.createRow(1);
                tip.createCell(0).setCellValue("说明：姓名必填；手机号重复的学生会自动跳过；请删除本行后从第3行开始填写数据。");
                for (int c = 0; c < headers.length; c++) sheet.setColumnWidth(c, 18 * 256);
                String fn = "学生导入模板.xlsx";
                resp.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                resp.setHeader("Content-Disposition", "attachment; filename=" +
                        java.net.URLEncoder.encode(fn, java.nio.charset.StandardCharsets.UTF_8.toString()));
                wb.write(resp.getOutputStream());
            }
        } catch (Exception ex) {
            log.error("学生模板下载失败", ex);
            try {
                resp.setStatus(500);
                resp.setContentType("application/json;charset=UTF-8");
                resp.getWriter().write("{\"code\":500,\"msg\":\"模板生成失败\"}");
            } catch (Exception ignored) { }
        }
    }

    // Excel 批量导入学生（解析预览，校验姓名/手机号）
    @PostMapping("/students/import")
    public Map<String, Object> importStudents(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long classId,
                                              HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可批量导入学生");
        }
        try {
            if (file == null || file.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "请上传Excel文件");
                return result;
            }
            String fn = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            if (!fn.endsWith(".xlsx") && !fn.endsWith(".xls")) {
                result.put("code", 400);
                result.put("msg", "仅支持 .xlsx / .xls 格式的Excel文件");
                return result;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            // 已存在手机号集合（DB 全量）
            Map<String, Long> dbPhone = new HashMap<>();
            for (Map<String, Object> s : jdbc.queryForList(
                    "SELECT id, phone FROM students WHERE phone IS NOT NULL AND phone<>'' AND (is_deleted IS NULL OR is_deleted=0)")) {
                Object ph = s.get("phone");
                if (ph != null) dbPhone.put(String.valueOf(ph).trim(), ((Number) s.get("id")).longValue());
            }
            Set<String> filePhones = new HashSet<>();
            try (InputStream is = file.getInputStream();
                 Workbook wb = fn.endsWith(".xls") ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {
                Sheet sheet = wb.getSheetAt(0);
                int lastRow = sheet.getLastRowNum();
                // 表头识别
                int nameIdx = 0, phoneIdx = 1, parentPhoneIdx = 2, parentNameIdx = 3, parentRelationIdx = 4;
                boolean headerFound = false;
                Row hr = sheet.getRow(0);
                if (hr != null) {
                    for (int c = 0; c < hr.getLastCellNum(); c++) {
                        Cell cell = hr.getCell(c);
                        if (cell == null) continue;
                        String hdr = getCellStringValue(cell).trim();
                        // 先匹配"家长"相关列，避免"家长姓名/家长手机号"被"姓名/手机号"抢先匹配
                        if (hdr.contains("家长姓名")) { parentNameIdx = c; headerFound = true; }
                        else if (hdr.contains("家长") && (hdr.contains("手机") || hdr.contains("电话"))) { parentPhoneIdx = c; headerFound = true; }
                        else if (hdr.contains("称谓") || hdr.contains("关系")) { parentRelationIdx = c; }
                        else if (hdr.contains("姓名") || hdr.equalsIgnoreCase("name")) { nameIdx = c; headerFound = true; }
                        else if (hdr.contains("手机号") || hdr.contains("电话") || hdr.equalsIgnoreCase("phone")) { phoneIdx = c; headerFound = true; }
                    }
                }
                int startRow = headerFound ? 1 : 0;
                for (int r = startRow; r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    String name = row.getCell(nameIdx) != null ? getCellStringValue(row.getCell(nameIdx)).trim() : "";
                    String phone = phoneIdx >= 0 && row.getCell(phoneIdx) != null ? getCellStringValue(row.getCell(phoneIdx)).trim() : "";
                    String parentPhone = parentPhoneIdx >= 0 && row.getCell(parentPhoneIdx) != null ? getCellStringValue(row.getCell(parentPhoneIdx)).trim() : "";
                    String parentName = parentNameIdx >= 0 && row.getCell(parentNameIdx) != null ? getCellStringValue(row.getCell(parentNameIdx)).trim() : "";
                    String parentRelation = parentRelationIdx >= 0 && row.getCell(parentRelationIdx) != null ? getCellStringValue(row.getCell(parentRelationIdx)).trim() : "";
                    if (name.startsWith("说明") || (name.isEmpty() && phone.isEmpty() && parentPhone.isEmpty())) continue;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("rowNum", r + 1);
                    item.put("name", name);
                    item.put("phone", phone);
                    item.put("parentPhone", parentPhone);
                    item.put("parentName", parentName);
                    item.put("parentRelation", parentRelation);
                    item.put("valid", true);
                    item.put("msg", "");
                    if (name.isEmpty()) {
                        item.put("valid", false);
                        item.put("msg", "学生姓名不能为空");
                    } else if (!phone.isEmpty() && (dbPhone.containsKey(phone) || filePhones.contains(phone))) {
                        item.put("valid", false);
                        item.put("msg", "手机号 " + phone + " 已注册，跳过");
                    } else {
                        if (!phone.isEmpty()) filePhones.add(phone);
                    }
                    rows.add(item);
                }
            }
            int validCount = 0;
            for (Map<String, Object> r2 : rows) if (Boolean.TRUE.equals(r2.get("valid"))) validCount++;
            result.put("code", 200);
            result.put("data", rows);
            result.put("total", rows.size());
            result.put("validCount", validCount);
            result.put("msg", "解析完成，共 " + rows.size() + " 行，其中 " + validCount + " 行可导入");
            return result;
        } catch (Exception ex) {
            log.error("学生Excel导入解析失败", ex);
            result.put("code", 500);
            result.put("msg", "解析失败：" + ex.getMessage());
            return result;
        }
    }

    // 确认导入合法行（事务批量插入 students + student_class）
    @Transactional
    @PostMapping("/students/import/confirm")
    public Map<String, Object> importStudentsConfirm(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> result = new HashMap<>();
        if (!RoleAccess.isTeacher(req)) {
            return RoleAccess.forbidTeacherOnly("仅教师/管理员可批量导入学生");
        }
        try {
            Long classId = body.get("classId") != null ? Long.valueOf(body.get("classId").toString()) : null;
            if (classId == null) {
                result.put("code", 400);
                result.put("msg", "缺少班级ID");
                return result;
            }
            List<Map<String, Object>> rows = (List<Map<String, Object>>) body.get("rows");
            if (rows == null || rows.isEmpty()) {
                result.put("code", 400);
                result.put("msg", "没有可导入的数据");
                return result;
            }
            int imported = 0;
            List<Map<String, Object>> errors = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                try {
                    String name = row.get("name") == null ? "" : String.valueOf(row.get("name")).trim();
                    String phone = row.get("phone") == null ? "" : String.valueOf(row.get("phone")).trim();
                    String parentPhone = row.get("parentPhone") == null ? "" : String.valueOf(row.get("parentPhone")).trim();
                    String parentName = row.get("parentName") == null ? "" : String.valueOf(row.get("parentName")).trim();
                    String parentRelation = row.get("parentRelation") == null ? "" : String.valueOf(row.get("parentRelation")).trim();
                    if (name.isEmpty()) continue;
                    // 二次手机号唯一校验（并发安全）
                    if (!phone.isEmpty()) {
                        List<Long> dup = jdbc.queryForList(
                            "SELECT id FROM students WHERE phone=? AND (is_deleted IS NULL OR is_deleted=0) LIMIT 1",
                            Long.class, phone);
                        if (!dup.isEmpty()) { errors.add(dupRow(row, "手机号 " + phone + " 已注册")); continue; }
                    }
                    jdbc.update(
                        "INSERT INTO students (name, class_id, phone, parent_phone, parent_name, parent_relation, status, created_at) VALUES (?,?,?,?,?,?, 'active', NOW())",
                        name, classId, phone.isEmpty() ? null : phone,
                        parentPhone.isEmpty() ? null : parentPhone,
                        parentName.isEmpty() ? null : parentName,
                        parentRelation.isEmpty() ? null : parentRelation);
                    Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    if (classId != null) {
                        try { jdbc.update("INSERT IGNORE INTO student_class (student_id, class_id) VALUES (?, ?)", id, classId); } catch (Exception ignore) { }
                    }
                    imported++;
                } catch (Exception e) {
                    errors.add(dupRow(row, "保存失败：" + e.getMessage()));
                }
            }
            result.put("code", 200);
            result.put("msg", "导入完成：成功 " + imported + " 条" + (errors.isEmpty() ? "" : "，失败 " + errors.size() + " 条"));
            result.put("imported", imported);
            result.put("errors", errors);
            return result;
        } catch (Exception ex) {
            log.error("学生Excel导入保存失败", ex);
            result.put("code", 500);
            result.put("msg", "导入失败：" + ex.getMessage());
            return result;
        }
    }

    private Map<String, Object> dupRow(Map<String, Object> row, String msg) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("rowNum", row.get("rowNum"));
        e.put("name", row.get("name"));
        e.put("msg", msg);
        return e;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v) && !Double.isInfinite(v)) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            }
            if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
            if (cell.getCellType() == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
            if (cell.getCellType() == CellType.FORMULA) {
                try { return cell.getStringCellValue(); } catch (Exception e) { return String.valueOf(cell.getNumericCellValue()); }
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
