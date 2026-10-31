package com.moveinsync.roster.employee;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import java.math.BigDecimal;

public record EmployeeSelfView(
        String externalId,
        String name,
        String email,
        String phone,
        String managerExternalId,
        String officeCode,
        String shiftCode,
        BigDecimal pickupLat,
        BigDecimal pickupLng,
        String pickupLabel,
        String specialAssistance,
        EmployeeStatus status) {

    static EmployeeSelfView from(Employee employee) {
        return new EmployeeSelfView(
                employee.getExternalId(),
                employee.getName(),
                employee.getEmail(),
                employee.getPhone(),
                employee.getManagerExternalId(),
                employee.getOffice() == null ? null : employee.getOffice().getCode(),
                employee.getShiftCode(),
                employee.getPickupLat(),
                employee.getPickupLng(),
                employee.getPickupLabel(),
                employee.getSpecialAssistance(),
                employee.getStatus());
    }

    @Override
    public String toString() {
        return "EmployeeSelfView[externalId=" + externalId + "]";
    }
}
