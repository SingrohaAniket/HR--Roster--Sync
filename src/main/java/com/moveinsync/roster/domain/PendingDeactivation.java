package com.moveinsync.roster.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "reconciliation_pending_deactivation")
public class PendingDeactivation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id")
    private ReconciliationRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    protected PendingDeactivation() {
    }

    public PendingDeactivation(ReconciliationRun run, Employee employee) {
        this.run = run;
        this.employee = employee;
    }

    public ReconciliationRun getRun() {
        return run;
    }

    public Employee getEmployee() {
        return employee;
    }
}
