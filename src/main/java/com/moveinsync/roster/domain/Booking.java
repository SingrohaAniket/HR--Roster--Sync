package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "booking")
public class Booking extends BaseEntity {

    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    private LocalDate tripDate;

    @Enumerated(EnumType.STRING)
    private BookingStatus status = BookingStatus.BOOKED;

    private String cancelReason;

    @CreationTimestamp
    private Instant createdAt;

    private Instant cancelledAt;

    protected Booking() {
    }

    public Booking(Long tenantId, Employee employee, Trip trip, LocalDate tripDate) {
        this.tenantId = tenantId;
        this.employee = employee;
        this.trip = trip;
        this.tripDate = tripDate;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Trip getTrip() {
        return trip;
    }

    public LocalDate getTripDate() {
        return tripDate;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }
}
