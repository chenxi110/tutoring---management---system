package com.skt.entity;

import java.util.Date;

/**
 * 课堂文件互传实体
 * 对应数据库表 course_file
 */
public class CourseFile {
    private Long id;
    private Long classId;
    private Long teacherId;
    private Long studentId;
    private String fileName;
    private String savePath;
    private String fileSuffix;
    private Long fileSize;
    private Date uploadTime;
    private Integer isTeacherUpload;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getClassId() { return classId; }
    public void setClassId(Long classId) { this.classId = classId; }

    public Long getTeacherId() { return teacherId; }
    public void setTeacherId(Long teacherId) { this.teacherId = teacherId; }

    public Long getStudentId() { return studentId; }
    public void setStudentId(Long studentId) { this.studentId = studentId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getSavePath() { return savePath; }
    public void setSavePath(String savePath) { this.savePath = savePath; }

    public String getFileSuffix() { return fileSuffix; }
    public void setFileSuffix(String fileSuffix) { this.fileSuffix = fileSuffix; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public Date getUploadTime() { return uploadTime; }
    public void setUploadTime(Date uploadTime) { this.uploadTime = uploadTime; }

    public Integer getIsTeacherUpload() { return isTeacherUpload; }
    public void setIsTeacherUpload(Integer isTeacherUpload) { this.isTeacherUpload = isTeacherUpload; }
}
