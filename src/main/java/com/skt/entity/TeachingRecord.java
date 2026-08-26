package com.skt.entity;

public class TeachingRecord {
    private Long id;
    private String date;
    private Long classId;
    private String className;
    private String course;
    private Integer sessions;
    private String type;
    private Long semesterId;
    private String remark;
    private String absentJson;
    private String trialStudents;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getCourse() { return course; }
    public void setCourse(String course) { this.course = course; }
    public Integer getSessions() { return sessions; }
    public void setSessions(Integer sessions) { this.sessions = sessions; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getSemesterId() { return semesterId; }
    public void setSemesterId(Long semesterId) { this.semesterId = semesterId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getAbsentJson() { return absentJson; }
    public void setAbsentJson(String absentJson) { this.absentJson = absentJson; }
    public String getTrialStudents() { return trialStudents; }
    public void setTrialStudents(String trialStudents) { this.trialStudents = trialStudents; }
}
