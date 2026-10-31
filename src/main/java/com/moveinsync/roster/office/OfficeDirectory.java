package com.moveinsync.roster.office;

import com.moveinsync.roster.config.CacheConfig;
import com.moveinsync.roster.domain.Office;
import java.util.Optional;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class OfficeDirectory {

    private final OfficeRepository offices;

    public OfficeDirectory(OfficeRepository offices) {
        this.offices = offices;
    }

    @Cacheable(cacheNames = CacheConfig.OFFICES, key = "#tenantId + ':' + #code")
    public Optional<Office> findActive(long tenantId, String code) {
        return offices.findByTenantIdAndCodeAndActiveTrue(tenantId, code);
    }

    @CacheEvict(cacheNames = CacheConfig.OFFICES, key = "#office.tenantId + ':' + #office.code")
    public Office save(Office office) {
        return offices.save(office);
    }
}
