package com.moveinsync.roster.employee;

import com.moveinsync.roster.security.JwtClaims;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

    private final EmployeeQueryService employees;

    public EmployeeController(EmployeeQueryService employees) {
        this.employees = employees;
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('EMPLOYEE')")
    public EmployeeSelfView me(@AuthenticationPrincipal JwtClaims principal) {
        return employees.findSelf(principal.employeeExternalId());
    }

    @GetMapping("/{externalId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public EmployeeAdminView byExternalId(@PathVariable String externalId) {
        return employees.findForAdmin(externalId);
    }
}
