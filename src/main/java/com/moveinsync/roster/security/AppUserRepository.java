package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.AppUser;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface AppUserRepository extends Repository<AppUser, Long> {

    AppUser save(AppUser user);

    Optional<AppUser> findByTenantIdAndUsername(Long tenantId, String username);

    List<AppUser> findByUsername(String username);
}
