package com.moveinsync.roster.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "employee")
public class Employee extends BaseEntity {

    private Long tenantId;

    private String externalId;

    private String name;

    private String email;

    private String phone;

    private String managerExternalId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "office_id")
    private Office office;

    private String shiftCode;

    private BigDecimal pickupLat;

    private BigDecimal pickupLng;

    private String pickupLabel;

    private String specialAssistance;

    @Enumerated(EnumType.STRING)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    private long hrVersion;

    private long transportVersion;

    private Instant hrUpdatedAt;

    private Instant transportUpdatedAt;

    @Column(columnDefinition = "char(64)")
    private String contentHash;

    @Column(columnDefinition = "smallint unsigned")
    private int bucket;

    private Instant deactivatedAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    protected Employee() {
    }

    public Employee(Long tenantId, String externalId, String contentHash, int bucket) {
        this.tenantId = tenantId;
        this.externalId = externalId;
        this.contentHash = contentHash;
        this.bucket = bucket;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getManagerExternalId() {
        return managerExternalId;
    }

    public void setManagerExternalId(String managerExternalId) {
        this.managerExternalId = managerExternalId;
    }

    public Office getOffice() {
        return office;
    }

    public void setOffice(Office office) {
        this.office = office;
    }

    public String getShiftCode() {
        return shiftCode;
    }

    public void setShiftCode(String shiftCode) {
        this.shiftCode = shiftCode;
    }

    public BigDecimal getPickupLat() {
        return pickupLat;
    }

    public void setPickupLat(BigDecimal pickupLat) {
        this.pickupLat = pickupLat;
    }

    public BigDecimal getPickupLng() {
        return pickupLng;
    }

    public void setPickupLng(BigDecimal pickupLng) {
        this.pickupLng = pickupLng;
    }

    public String getPickupLabel() {
        return pickupLabel;
    }

    public void setPickupLabel(String pickupLabel) {
        this.pickupLabel = pickupLabel;
    }

    public String getSpecialAssistance() {
        return specialAssistance;
    }

    public void setSpecialAssistance(String specialAssistance) {
        this.specialAssistance = specialAssistance;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public void setStatus(EmployeeStatus status) {
        this.status = status;
    }

    public long getHrVersion() {
        return hrVersion;
    }

    public void setHrVersion(long hrVersion) {
        this.hrVersion = hrVersion;
    }

    public long getTransportVersion() {
        return transportVersion;
    }

    public void setTransportVersion(long transportVersion) {
        this.transportVersion = transportVersion;
    }

    public Instant getHrUpdatedAt() {
        return hrUpdatedAt;
    }

    public void setHrUpdatedAt(Instant hrUpdatedAt) {
        this.hrUpdatedAt = hrUpdatedAt;
    }

    public Instant getTransportUpdatedAt() {
        return transportUpdatedAt;
    }

    public void setTransportUpdatedAt(Instant transportUpdatedAt) {
        this.transportUpdatedAt = transportUpdatedAt;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public int getBucket() {
        return bucket;
    }

    public Instant getDeactivatedAt() {
        return deactivatedAt;
    }

    public void setDeactivatedAt(Instant deactivatedAt) {
        this.deactivatedAt = deactivatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
