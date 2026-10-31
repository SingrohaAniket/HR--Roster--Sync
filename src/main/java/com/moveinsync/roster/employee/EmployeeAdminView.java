package com.moveinsync.roster.employee;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;

public record EmployeeAdminView(
        String externalId,
        String name,
        String managerExternalId,
        String officeCode,
        String shiftCode,
        EmployeeStatus status) {

    static EmployeeAdminView from(Employee employee) {
        return new EmployeeAdminView(
                employee.getExternalId(),
                employee.getName(),
                employee.getManagerExternalId(),
                employee.getOffice() == null ? null : employee.getOffice().getCode(),
                employee.getShiftCode(),
                employee.getStatus());
    }

    @Override
    public String toString() {
        return "EmployeeAdminView[externalId=" + externalId + "]";
    }
}
