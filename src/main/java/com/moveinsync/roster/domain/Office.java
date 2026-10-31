package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "office")
public class Office extends BaseEntity {

    private Long tenantId;

    private String code;

    private String name;

    private String city;

    private boolean active = true;

    @CreationTimestamp
    private Instant createdAt;

    protected Office() {
    }

    public Office(Long tenantId, String code, String name, String city) {
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.city = city;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
