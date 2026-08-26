package com.skt.entity;

import java.util.Date;

public class User {
    private Long id;
    private String username;
    private String passwordHash;
    private String role;
    private String displayName;
    private String phone;
    private Date createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getDisplayName() { return displayName == null ? "" : displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName == null ? "" : displayName.trim(); }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public Date getCreatedAt() { return createdAt; }
    public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
