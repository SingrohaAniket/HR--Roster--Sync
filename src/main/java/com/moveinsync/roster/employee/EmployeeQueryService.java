package com.moveinsync.roster.employee;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.web.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class EmployeeQueryService {

    private final EmployeeRepository employees;

    public EmployeeQueryService(EmployeeRepository employees) {
        this.employees = employees;
    }

    public EmployeeSelfView findSelf(String externalId) {
        return EmployeeSelfView.from(findInCurrentTenant(externalId));
    }

    public EmployeeAdminView findForAdmin(String externalId) {
        return EmployeeAdminView.from(findInCurrentTenant(externalId));
    }

    private Employee findInCurrentTenant(String externalId) {
        return employees.findByTenantIdAndExternalId(TenantContext.require(), externalId)
                .orElseThrow(ResourceNotFoundException::new);
    }
}
