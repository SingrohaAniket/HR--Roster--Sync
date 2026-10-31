package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    private Long tenantId;

    private String username;

    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Role role;

    private String employeeExternalId;

    private boolean enabled = true;

    @CreationTimestamp
    private Instant createdAt;

    protected AppUser() {
    }

    public AppUser(Long tenantId, String username, String passwordHash, Role role, String employeeExternalId) {
        this.tenantId = tenantId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.employeeExternalId = employeeExternalId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getEmployeeExternalId() {
        return employeeExternalId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
