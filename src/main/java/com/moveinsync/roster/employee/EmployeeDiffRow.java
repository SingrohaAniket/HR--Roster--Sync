package com.moveinsync.roster.employee;

import com.moveinsync.roster.domain.EmployeeStatus;
import java.time.Instant;

public interface EmployeeDiffRow {

    Long getId();

    String getExternalId();

    String getContentHash();

    Instant getHrUpdatedAt();

    EmployeeStatus getStatus();
}
