package com.skt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import java.io.InputStream;
import java.util.*;

@Service
public class GradeService {

    private static final Logger log = LoggerFactory.getLogger(GradeService.class);

    @Autowired
    private JdbcTemplate jdbc;

    public List<Map<String, Object>> list(Long classId, Long studentId, String examName, Long semesterId, Long teacherId, String role) {
        if ("parent".equals(role) && teacherId != null) {
            return listForParent(teacherId, classId, studentId, examName, semesterId);
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM grades WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if ("teacher".equals(role) && teacherId != null) {
            sql.append(" AND teacher_id=?");
            params.add(teacherId);
        }
        if (classId != null) { sql.append(" AND class_id=?"); params.add(classId); }
        if (studentId != null) { sql.append(" AND student_id=?"); params.add(studentId); }
        if (examName != null) { sql.append(" AND exam_name=?"); params.add(examName); }
        if (semesterId != null) { sql.append(" AND semester_id=?"); params.add(semesterId); }
        sql.append(" ORDER BY created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> listForParent(Long parentId, Long classId, Long studentId, String examName, Long semesterId) {
        StringBuilder sql = new StringBuilder(
            "SELECT g.* FROM grades g INNER JOIN students s ON s.id=g.student_id " +
            "WHERE s.parent_id=? AND (s.is_deleted IS NULL OR s.is_deleted=0) AND (s.status IS NULL OR s.status='active')"
        );
        List<Object> params = new ArrayList<>();
        params.add(parentId);
        if (classId != null) { sql.append(" AND g.class_id=?"); params.add(classId); }
        if (studentId != null) { sql.append(" AND g.student_id=?"); params.add(studentId); }
        if (examName != null && !examName.trim().isEmpty()) { sql.append(" AND g.exam_name=?"); params.add(examName.trim()); }
        if (semesterId != null) { sql.append(" AND g.semester_id=?"); params.add(semesterId); }
        sql.append(" ORDER BY g.created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> listForStudent(Long studentId, Long classId, String examName, Long semesterId) {
        StringBuilder sql = new StringBuilder("SELECT g.* FROM grades g WHERE g.student_id=?");
        List<Object> params = new ArrayList<>();
        params.add(studentId);
        if (classId != null) { sql.append(" AND g.class_id=?"); params.add(classId); }
        if (examName != null && !examName.trim().isEmpty()) { sql.append(" AND g.exam_name=?"); params.add(examName.trim()); }
        if (semesterId != null) { sql.append(" AND g.semester_id=?"); params.add(semesterId); }
        sql.append(" ORDER BY g.created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public Long create(Long studentId, String studentName, Long classId, String className,
                       String examName, String examType, Double score, Double totalScore,
                       Long semesterId, Long teacherId, String remark, Long rank) {
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        jdbc.update("INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, remark, `rank`) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            studentId, studentName != null ? studentName : "", classId,
            className != null ? className : "", examName,
            examType != null ? examType : "unit_test", score,
            totalScore != null ? totalScore : 100, semesterId, teacherId,
            remark != null ? remark : "", rank);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Map<String, Object> validateCreatePayload(Object classIdValue, Object studentIdValue, Object studentNameValue,
                                                     Object classNameValue, Object examNameValue, Object examTypeValue,
                                                     Object scoreValue, Object totalScoreValue, Object semesterIdValue,
                                                     Object rankValue, Object remarkValue) {
        Long classId = toLong(classIdValue);
        Long studentId = toLong(studentIdValue);
        String studentName = toTrimString(studentNameValue);
        String className = toTrimString(classNameValue);
        String examName = toTrimString(examNameValue);
        String examType = toTrimString(examTypeValue);
        String remark = remarkValue == null ? "" : String.valueOf(remarkValue).trim();

        if (classId == null) {
            return errorMap("请选择班级。");
        }
        if (studentId == null || studentId <= 0) {
            return errorMap("请选择学生。");
        }
        if (examName.isEmpty()) {
            return errorMap("请填写考试名称。");
        }
        if (examType.isEmpty()) {
            return errorMap("请选择考试类型。");
        }
        if (classExists(classId) == false || studentExists(studentId) == false) {
            return errorMap("所选班级/学生不存在。");
        }
        if (studentName.isEmpty()) {
            return errorMap("请选择学生。");
        }

        Double score = parseDouble(scoreValue, "分数必须为0~满分之间的有效数字。");
        Double totalScore = parseDouble(totalScoreValue, "满分必须填写大于0数字。");
        if (score == null || totalScore == null) {
            return errorMap("分数必须为0~满分之间的有效数字。");
        }
        if (totalScore <= 0) {
            return errorMap("满分必须填写大于0数字。");
        }
        if (score < 0 || score > totalScore) {
            return errorMap("分数不能大于满分。");
        }

        Integer rank = null;
        if (rankValue != null && !"".equals(String.valueOf(rankValue).trim())) {
            try {
                rank = Integer.parseInt(String.valueOf(rankValue).trim());
            } catch (Exception e) {
                return errorMap("排名请输入正整数或者留空。");
            }
            if (rank <= 0) {
                return errorMap("排名请输入正整数或者留空。");
            }
        }

        if (remarkValue != null && String.valueOf(remarkValue).length() > 500) {
            remark = String.valueOf(remarkValue).substring(0, 500);
        }

        return successMap();
    }

    public Map<String, Object> validateUpdatePayload(Object scoreValue, Object totalScoreValue, Object rankValue) {
        Double score = parseDouble(scoreValue, "分数必须为0~满分之间的有效数字。");
        Double totalScore = parseDouble(totalScoreValue, "满分必须填写大于0数字。");
        if (score == null || totalScore == null) {
            return errorMap("分数必须为0~满分之间的有效数字。");
        }
        if (totalScore <= 0) {
            return errorMap("满分必须填写大于0数字。");
        }
        if (score < 0 || score > totalScore) {
            return errorMap("分数不能大于满分。");
        }
        if (rankValue != null && !"".equals(String.valueOf(rankValue).trim())) {
            try {
                int rank = Integer.parseInt(String.valueOf(rankValue).trim());
                if (rank <= 0) {
                    return errorMap("排名请输入正整数或者留空。");
                }
            } catch (Exception e) {
                return errorMap("排名请输入正整数或者留空。");
            }
        }
        return successMap();
    }

    private boolean classExists(Long classId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM classes WHERE id=?", Integer.class, classId);
        return count != null && count > 0;
    }

    private boolean studentExists(Long studentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM students WHERE id=? AND (is_deleted IS NULL OR is_deleted = 0) AND (status IS NULL OR status='active')",
            Integer.class,
            studentId
        );
        return count != null && count > 0;
    }

    public Double parseDouble(Object value, String fallbackMsg) {
        if (value == null) {
            return null;
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public Long toLong(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String toTrimString(Object value) {
        if (value == null) return "";
        return String.valueOf(value).trim();
    }

    private Map<String, Object> errorMap(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", 400);
        m.put("msg", msg);
        m.put("error", msg);
        return m;
    }

    private Map<String, Object> successMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("code", 200);
        return m;
    }

    @Transactional
    public int batchCreate(Long classId, String className, String examName, String examType,
                           Double totalScore, Long semesterId, Long teacherId,
                           List<Map<String, Object>> entries) {
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        int count = 0;
        for (Map<String, Object> e : entries) {
            jdbc.update("INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                ((Number) e.get("studentId")).longValue(),
                e.containsKey("studentName") ? e.get("studentName") : "",
                classId, className != null ? className : "", examName,
                examType != null ? examType : "unit_test",
                ((Number) e.get("score")).doubleValue(),
                totalScore != null ? totalScore : 100, semesterId, teacherId,
                e.containsKey("remark") ? e.get("remark") : "");
            count++;
        }
        return count;
    }

    public void update(Long id, Double score, Double totalScore, Integer rank, String remark) {
        jdbc.update("UPDATE grades SET score=?, total_score=?, `rank`=?, remark=? WHERE id=?",
            score, totalScore != null ? totalScore : 100, rank, remark != null ? remark : "", id);
    }

    public void delete(Long id) {
        jdbc.update("DELETE FROM grades WHERE id=?", id);
    }

    public Map<String, Object> stats(Long classId, String examName, Long teacherId, String role) {
        String sql = "SELECT score FROM grades WHERE class_id=? AND exam_name=?";
        List<Object> params = new ArrayList<>();
        params.add(classId);
        params.add(examName);
        if ("teacher".equals(role) && teacherId != null) {
            sql += " AND teacher_id=?";
            params.add(teacherId);
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());
        Map<String, Object> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            result.put("count", 0);
            return result;
        }
        double sum = 0, max = Double.MIN_VALUE, min = Double.MAX_VALUE;
        int passCount = 0;
        for (Map<String, Object> r : rows) {
            double s = ((Number) r.get("score")).doubleValue();
            sum += s;
            if (s > max) max = s;
            if (s < min) min = s;
            if (s >= 60) passCount++;
        }
        double avg = Math.round(sum / rows.size() * 10) / 10.0;
        result.put("count", rows.size());
        result.put("avg", avg);
        result.put("max", max);
        result.put("min", min);
        result.put("passRate", Math.round(passCount * 100.0 / rows.size() * 10) / 10.0);
        return result;
    }

    public Map<String, Object> parseExcelPreview(MultipartFile file, Long classId, String className,
                                                  String examName, String examType, Double totalScore,
                                                  Long semesterId) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> previewList = new ArrayList<>();

        List<Map<String, Object>> classStudents = getStudentsByClassId(classId);
        Map<String, Long> studentNameToId = new HashMap<>();
        for (Map<String, Object> s : classStudents) {
            String name = String.valueOf(s.get("name")).trim();
            if (!name.isEmpty()) {
                studentNameToId.put(name, ((Number) s.get("id")).longValue());
            }
        }

        try (InputStream is = file.getInputStream();
             Workbook workbook = file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".xls")
                 ? new HSSFWorkbook(is) : new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            int nameColIdx = 0;
            int scoreColIdx = 1;
            boolean headerFound = false;

            Row headerRow = sheet.getRow(0);
            if (headerRow != null) {
                for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                    Cell cell = headerRow.getCell(c);
                    if (cell == null) continue;
                    String header = getCellStringValue(cell).trim();
                    if (header.contains("姓名") || header.equalsIgnoreCase("name") || header.contains("学生")) {
                        nameColIdx = c;
                        headerFound = true;
                    }
                    if (header.contains("成绩") || header.contains("分数") || header.equalsIgnoreCase("score") || header.contains("分数")) {
                        scoreColIdx = c;
                        headerFound = true;
                    }
                }
            }
            if (!headerFound) {
                nameColIdx = 0;
                scoreColIdx = 1;
            }

            int startRow = headerFound ? 1 : 0;
            for (int r = startRow; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Cell nameCell = row.getCell(nameColIdx);
                Cell scoreCell = row.getCell(scoreColIdx);
                String studentName = nameCell != null ? getCellStringValue(nameCell).trim() : "";
                String scoreStr = scoreCell != null ? getCellStringValue(scoreCell).trim() : "";

                if (studentName.isEmpty() && scoreStr.isEmpty()) continue;

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rowNum", r + 1);
                item.put("studentName", studentName);
                item.put("scoreStr", scoreStr);
                item.put("valid", true);
                item.put("errorMsg", "");

                if (studentName.isEmpty()) {
                    item.put("valid", false);
                    item.put("errorMsg", "学生姓名为空");
                } else if (!studentNameToId.containsKey(studentName)) {
                    item.put("valid", false);
                    item.put("errorMsg", "该学生不在选定班级中");
                } else {
                    item.put("studentId", studentNameToId.get(studentName));
                }

                Double score = null;
                if (!scoreStr.isEmpty()) {
                    try {
                        score = Double.parseDouble(scoreStr);
                        if (totalScore != null && score > totalScore) {
                            item.put("valid", false);
                            item.put("errorMsg", item.get("errorMsg") + "；成绩超过满分");
                        }
                        if (score < 0) {
                            item.put("valid", false);
                            item.put("errorMsg", item.get("errorMsg") + "；成绩不能为负");
                        }
                    } catch (NumberFormatException e) {
                        item.put("valid", false);
                        item.put("errorMsg", item.get("errorMsg") + "；成绩不是有效数字");
                    }
                } else {
                    item.put("valid", false);
                    item.put("errorMsg", item.get("errorMsg") + "；成绩为空");
                }
                item.put("score", score);

                previewList.add(item);
            }

            result.put("code", 200);
            result.put("data", previewList);
            result.put("classId", classId);
            result.put("className", className);
            result.put("examName", examName);
            result.put("examType", examType);
            result.put("totalScore", totalScore);
            result.put("semesterId", semesterId);
            result.put("totalCount", previewList.size());
            result.put("validCount", previewList.stream().filter(m -> (boolean) m.get("valid")).count());
            return result;
        } catch (Exception e) {
            log.error("Excel解析失败: ", e);
            result.put("code", 500);
            result.put("msg", "Excel解析失败：" + e.getMessage());
            return result;
        }
    }

    @Transactional
    public int importBatchCreate(Long classId, String className, String examName, String examType,
                                 Double totalScore, Long semesterId, Long teacherId,
                                 List<Map<String, Object>> entries) {
        if (semesterId == null) {
            Map<String, Object> active = jdbc.queryForMap("SELECT id FROM semesters WHERE is_active=1 LIMIT 1");
            semesterId = ((Number) active.get("id")).longValue();
        }
        int count = 0;
        for (Map<String, Object> e : entries) {
            jdbc.update("INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, semester_id, teacher_id, remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                ((Number) e.get("studentId")).longValue(),
                e.get("studentName") != null ? e.get("studentName") : "",
                classId, className != null ? className : "", examName,
                examType != null ? examType : "unit_test",
                ((Number) e.get("score")).doubleValue(),
                totalScore != null ? totalScore : 100, semesterId, teacherId,
                e.get("remark") != null ? e.get("remark") : "");
            count++;
        }
        return count;
    }

    private List<Map<String, Object>> getStudentsByClassId(Long classId) {
        String sql = "SELECT id, name FROM students WHERE class_id=? AND (is_deleted IS NULL OR is_deleted=0) AND (status IS NULL OR status='active') ORDER BY name";
        return jdbc.queryForList(sql, classId);
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v)) return String.valueOf((long) v);
                return String.valueOf(v);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA: return cell.getCellFormula();
            default: return "";
        }
    }
}
