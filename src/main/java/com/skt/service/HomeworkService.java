package com.skt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class HomeworkService {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OperationLogService operationLogService;
    @Autowired
    private MessageService messageService;
    @Autowired
    private AIService aiService;

    public List<Map<String, Object>> list(Long classId, Long teacherId, String role) {
        StringBuilder sql = new StringBuilder("SELECT h.*, c.name as class_name FROM homework h LEFT JOIN classes c ON h.class_id=c.id WHERE 1=1");
        List<Object> params = new ArrayList<>();
        // 教师角色：只能查看自己所带班级的作业
        if ("teacher".equals(role) && teacherId != null) {
            sql.append(" AND h.class_id IN (SELECT id FROM classes WHERE teacher_id=?)");
            params.add(teacherId);
        }
        if (classId != null) {
            sql.append(" AND h.class_id=?");
            params.add(classId);
        }
        sql.append(" ORDER BY h.created_at DESC");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    public List<Map<String, Object>> listForParent(Long parentId) {
        String sql = "SELECT DISTINCT h.*, c.name AS class_name " +
            "FROM homework h " +
            "LEFT JOIN classes c ON h.class_id = c.id " +
            "INNER JOIN students s ON s.class_id = h.class_id " +
            "WHERE s.parent_id=? AND (s.is_deleted IS NULL OR s.is_deleted = 0) AND (s.status IS NULL OR s.status='active') " +
            "ORDER BY h.created_at DESC";
        return jdbc.queryForList(sql, parentId);
    }

    /** 学生端「我的作业」：按 students.user_id 隔离，只返回本人班级作业并带本人提交/批改状态 */
    public List<Map<String, Object>> listForStudent(Long studentId, Long classId) {
        if (classId == null) return Collections.emptyList();
        return jdbc.queryForList(
            "SELECT DISTINCT h.*, c.name AS class_name, " +
            "hs.content AS submit_content, hs.score AS submit_score, hs.comment AS submit_comment, " +
            "hs.ai_score AS submit_ai_score, hs.ai_comment AS submit_ai_comment, hs.ai_mode AS submit_ai_mode, " +
            "hs.submitted_at, hs.graded_at, " +
            "CASE WHEN hs.id IS NULL THEN 0 ELSE 1 END AS submitted " +
            "FROM homework h " +
            "LEFT JOIN classes c ON h.class_id = c.id " +
            "LEFT JOIN homework_submissions hs ON hs.homework_id=h.id AND hs.student_id=? " +
            "WHERE h.class_id=? ORDER BY h.created_at DESC", studentId, classId);
    }

    public Long create(Long classId, String title, String content, String deadline, Long createdBy, String teacherName) {
        jdbc.update("INSERT INTO homework (class_id, title, content, deadline, created_by) VALUES (?,?,?,?,?)",
            classId, title, content != null ? content : "", deadline != null ? deadline : "", createdBy);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // 发布作业后通知该班级全部家长 + 全部学生
        try {
            String safeTitle = title != null ? title : "";
            String noticeContent = "教师发布了新作业：「" + safeTitle + "」，请前往查看并按时完成";
            String senderName = teacherName != null ? teacherName : "教师";
            // 1) 家长：notice + classId 走现有通知机制
            messageService.sendMessage(createdBy, senderName, "teacher",
                "新作业通知", noticeContent, null, classId, null, "notice");
            // 2) 学生：逐个学生账号推送
            List<Map<String, Object>> stus = jdbc.queryForList(
                "SELECT user_id FROM students WHERE class_id=? AND user_id IS NOT NULL AND (is_deleted IS NULL OR is_deleted=0) AND (status IS NULL OR status='active')",
                classId);
            for (Map<String, Object> stu : stus) {
                Object uidObj = stu.get("user_id");
                if (uidObj != null) {
                    Long uid = ((Number) uidObj).longValue();
                    messageService.sendMessage(createdBy, senderName, "teacher",
                        "新作业通知", noticeContent, null, classId, uid, "private");
                }
            }
        } catch (Exception e) {
            // 通知失败不影响发布
        }
        return id;
    }

    /** 作业提交后 AI 自动审阅：优先调用大模型，失败/超时降级为规则评分，绝不阻塞提交 */
    public void runAiReview(Long submissionId, Long homeworkId, String content, String fileName) {
        try {
            double score;
            String comment;
            String mode;
            String aiReply = null;
            try {
                StringBuilder prompt = new StringBuilder();
                prompt.append("你是一位认真负责的课外辅导班教师，请对学生提交的作业进行审阅评分。\n");
                if (fileName != null && !fileName.isEmpty()) {
                    prompt.append("作业附件文件：").append(fileName).append("\n");
                }
                if (content != null && !content.isEmpty()) {
                    prompt.append("学生提交的文字内容：\n").append(content).append("\n");
                }
                prompt.append("你无法读取附件内部内容，只能看到文件名；请基于文件名与文字说明审阅。若附件内容不可见，请客观给出80-90的中性分，并在评语中说明建议教师下载附件人工复核。\n");
                prompt.append("请用中文回复，严格按下面两行格式（得分范围0-100）：\n得分:85\n评语:（一两句具体的鼓励性评价与改进建议）");
                aiReply = aiService.chatOnce("你是课外辅导班教师，负责批改学生作业，评分客观公正。", prompt.toString());
            } catch (Exception e) {
                aiReply = null;
            }
            if (aiReply != null) {
                double s = parseAiScore(aiReply);
                String c = parseAiComment(aiReply);
                if (s >= 0) {
                    score = s;
                    comment = c;
                    mode = "llm";
                } else {
                    Map<String, Object> r = ruleReview(content, fileName);
                    score = (double) r.get("score");
                    comment = (String) r.get("comment");
                    mode = (String) r.get("mode");
                }
            } else {
                Map<String, Object> r = ruleReview(content, fileName);
                score = (double) r.get("score");
                comment = (String) r.get("comment");
                mode = (String) r.get("mode");
            }
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            jdbc.update("UPDATE homework_submissions SET ai_score=?, ai_comment=?, ai_graded_at=?, ai_mode=? WHERE id=?",
                score, comment, now, mode, submissionId);
        } catch (Exception e) {
            // AI 审阅失败绝不影响作业提交主流程
        }
    }

    private double parseAiScore(String reply) {
        // 仅接受严格格式「得分:XX」，解析失败返回 -1，由调用方降级为规则评分，避免误取评语中的数字
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("得分[:：]\\s*(\\d{1,3})").matcher(reply);
            if (m.find()) {
                double s = Double.parseDouble(m.group(1));
                return Math.max(0, Math.min(100, s));
            }
        } catch (Exception e) {
            // ignore
        }
        return -1;
    }

    private String parseAiComment(String reply) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("评语[:：]\\s*([^\\n]+)").matcher(reply);
        if (m.find()) {
            String c = m.group(1).trim();
            if (!c.isEmpty()) return c;
        }
        String[] lines = reply.split("\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String c = lines[i].trim();
            if (c.length() > 2 && !c.startsWith("得分")) return c;
        }
        return reply.length() > 120 ? reply.substring(0, 120) : reply;
    }

    /** 规则评分（AI不可用时的确定性降级方案） */
    private Map<String, Object> ruleReview(String content, String fileName) {
        double score = 85.0;
        List<String> notes = new ArrayList<>();
        if (fileName == null || fileName.isEmpty()) {
            score -= 10;
            notes.add("未附带文件");
        }
        int len = content == null ? 0 : content.trim().length();
        if (len >= 50) {
            score += 5;
            notes.add("文字说明详细充实");
        } else if (len > 0) {
            score += 2;
        } else {
            score -= 3;
            notes.add("无文字说明");
        }
        score = Math.max(60, Math.min(98, score));
        int s = (int) Math.round(score);
        StringBuilder sb = new StringBuilder("AI自动审阅（规则模式）：已收到作业提交");
        if (fileName != null && !fileName.isEmpty()) {
            sb.append("，附件：").append(fileName);
        }
        sb.append("；").append(notes.isEmpty() ? "内容完整" : String.join("；", notes));
        sb.append("。建议得分 ").append(s).append(" 分，仅供参考，最终以教师批改确认为准。");
        Map<String, Object> r = new HashMap<>();
        r.put("score", (double) s);
        r.put("comment", sb.toString());
        r.put("mode", "rule");
        return r;
    }

    public Long submit(Long homeworkId, Long studentId, String studentName, String content) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        jdbc.update("INSERT INTO homework_submissions (homework_id, student_id, student_name, content, submitted_at) VALUES (?,?,?,?,?)",
            homeworkId, studentId, studentName != null ? studentName : "", content, now);
        Long subId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        runAiReview(subId, homeworkId, content, null);
        return subId;
    }

    /** 家长/学生提交作业（支持任意安全格式文件） */
    public Long submitFile(Long homeworkId, Long studentId, String studentName, String content,
                           String fileName, String filePath, String submitRole, Long submitUserId) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        jdbc.update("INSERT INTO homework_submissions (homework_id, student_id, student_name, content, file_name, file_path, submit_role, submit_user_id, submitted_at) VALUES (?,?,?,?,?,?,?,?,?)",
            homeworkId, studentId, studentName != null ? studentName : "", content != null ? content : "",
            fileName, filePath, submitRole, submitUserId, now);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        // 通知该班授课教师
        try {
            List<Map<String, Object>> hw = jdbc.queryForList(
                "SELECT h.*, c.teacher_id, c.name AS class_name FROM homework h LEFT JOIN classes c ON c.id=h.class_id WHERE h.id=?",
                homeworkId);
            if (!hw.isEmpty()) {
                Map<String, Object> h = hw.get(0);
                Object tidObj = h.get("teacher_id");
                if (tidObj != null) {
                    Long tid = ((Number) tidObj).longValue();
                    String hwTitle = h.get("title") != null ? String.valueOf(h.get("title")) : "作业";
                    String submitName = (fileName != null && !fileName.isEmpty()) ? fileName : "文本内容";
                    messageService.sendMessage(submitUserId,
                        studentName != null && !studentName.isEmpty() ? studentName : "家长/学生",
                        submitRole != null ? submitRole : "parent",
                        "作业提交通知", "收到「" + hwTitle + "」的新作业提交：" + submitName,
                        studentId, null, tid, "private");
                }
            }
        } catch (Exception e) {
            // 通知失败不影响提交
        }
        // AI 自动审阅（大模型优先，失败降级规则评分）
        runAiReview(id, homeworkId, content, fileName);
        return id;
    }

    /** 查询某作业的全部提交记录（教师查看） */
    public List<Map<String, Object>> listSubmissions(Long homeworkId) {
        return jdbc.queryForList(
            "SELECT hs.*, h.title AS homework_title, h.class_id FROM homework_submissions hs " +
            "LEFT JOIN homework h ON h.id=hs.homework_id WHERE hs.homework_id=? ORDER BY hs.submitted_at DESC",
            homeworkId);
    }

    public void grade(Long submissionId, Double score, String comment) {
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        jdbc.update("UPDATE homework_submissions SET score=?, comment=?, graded_at=? WHERE id=?",
            score, comment != null ? comment : "", now, submissionId);

        // 同步成绩到grades表
        try {
            List<Map<String, Object>> subs = jdbc.queryForList(
                "SELECT hs.*, h.title as homework_title, h.class_id, c.name as class_name, c.teacher_id " +
                "FROM homework_submissions hs " +
                "LEFT JOIN homework h ON h.id=hs.homework_id " +
                "LEFT JOIN classes c ON c.id=h.class_id " +
                "WHERE hs.id=?", submissionId);
            if (!subs.isEmpty()) {
                Map<String, Object> sub = subs.get(0);
                Long studentId = sub.get("student_id") != null ? ((Number) sub.get("student_id")).longValue() : null;
                String studentName = (String) sub.get("student_name");
                Long classId = sub.get("class_id") != null ? ((Number) sub.get("class_id")).longValue() : null;
                String className = (String) sub.get("class_name");
                String homeworkTitle = (String) sub.get("homework_title");
                Long teacherId = sub.get("teacher_id") != null ? ((Number) sub.get("teacher_id")).longValue() : null;

                if (studentId != null && classId != null) {
                    String examName = "作业:" + (homeworkTitle != null ? homeworkTitle : "未命名");
                    // 检查是否已存在该学生该作业的成绩记录
                    List<Map<String, Object>> existing = jdbc.queryForList(
                        "SELECT id FROM grades WHERE student_id=? AND exam_name=? AND class_id=?",
                        studentId, examName, classId);
                    if (!existing.isEmpty()) {
                        Long gradeId = ((Number) existing.get(0).get("id")).longValue();
                        jdbc.update("UPDATE grades SET score=?, remark=?, teacher_id=? WHERE id=?",
                            score, comment != null ? comment : "", teacherId, gradeId);
                    } else {
                        jdbc.update(
                            "INSERT INTO grades (student_id, student_name, class_id, class_name, exam_name, exam_type, score, total_score, teacher_id, remark) VALUES (?,?,?,?,?,?,?,?,?,?)",
                            studentId, studentName, classId, className, examName, "homework", score, 100.0, teacherId, comment != null ? comment : "");
                    }
                }
                // 操作日志
                if (teacherId != null) {
                    operationLogService.log(teacherId, "teacher_"+teacherId, "teacher", "作业批改",
                        "批改作业提交ID="+submissionId+", 分数="+score+", 评语="+comment, null);
                }
            }
        } catch (Exception e) {
            // 成绩同步失败不影响批改主流程
        }
    }
}
