package com.moveinsync.roster.tenancy;

import com.moveinsync.roster.domain.Tenant;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface TenantRepository extends Repository<Tenant, Long> {

    Optional<Tenant> findById(Long id);

    Optional<Tenant> findByCode(String code);
}
