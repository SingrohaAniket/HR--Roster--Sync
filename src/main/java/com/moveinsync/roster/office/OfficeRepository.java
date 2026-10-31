package com.moveinsync.roster.office;

import com.moveinsync.roster.domain.Office;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface OfficeRepository extends Repository<Office, Long> {

    Office save(Office office);

    Optional<Office> findByTenantIdAndCodeAndActiveTrue(Long tenantId, String code);

    List<Office> findByTenantIdAndActiveTrue(Long tenantId);
}
