package com.moveinsync.roster.employee;

import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository extends Repository<Employee, Long> {

    Employee save(Employee employee);

    Optional<Employee> findByTenantIdAndExternalId(Long tenantId, String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findForUpdateByTenantIdAndExternalId(Long tenantId, String externalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Employee> findForUpdateByTenantIdAndId(Long tenantId, Long id);

    long countByTenantIdAndStatus(Long tenantId, EmployeeStatus status);

    @Query("""
            SELECT e.id AS id, e.externalId AS externalId, e.contentHash AS contentHash,
                   e.hrUpdatedAt AS hrUpdatedAt, e.status AS status
              FROM Employee e
             WHERE e.tenantId = :tenantId
               AND e.bucket = :bucket
               AND e.id > :afterId
             ORDER BY e.id
            """)
    List<EmployeeDiffRow> findDiffRows(@Param("tenantId") Long tenantId, @Param("bucket") int bucket,
                                       @Param("afterId") long afterId, Limit limit);
}
