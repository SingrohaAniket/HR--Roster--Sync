package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;

@Entity
@Table(name = "trip")
public class Trip extends BaseEntity {

    private Long tenantId;

    private LocalDate tripDate;

    private String shiftCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private Office office;

    private int totalSeats;

    private int seatsTaken;

    protected Trip() {
    }

    public Trip(Long tenantId, LocalDate tripDate, String shiftCode, Office office, int totalSeats) {
        this.tenantId = tenantId;
        this.tripDate = tripDate;
        this.shiftCode = shiftCode;
        this.office = office;
        this.totalSeats = totalSeats;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public LocalDate getTripDate() {
        return tripDate;
    }

    public String getShiftCode() {
        return shiftCode;
    }

    public Office getOffice() {
        return office;
    }

    public int getTotalSeats() {
        return totalSeats;
    }

    public int getSeatsTaken() {
        return seatsTaken;
    }
}
