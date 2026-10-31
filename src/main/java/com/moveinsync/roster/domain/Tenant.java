package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "tenant")
public class Tenant extends BaseEntity {

    private String code;

    private String name;

    private boolean active = true;

    @CreationTimestamp
    private Instant createdAt;

    protected Tenant() {
    }

    public Tenant(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
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
