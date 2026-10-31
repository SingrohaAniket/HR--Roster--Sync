package com.moveinsync.roster.security;

import com.moveinsync.roster.domain.AppUser;
import com.moveinsync.roster.domain.Tenant;
import com.moveinsync.roster.tenancy.TenantRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final String dummyHash;

    public AuthService(TenantRepository tenants, AppUserRepository users, PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public LoginResponse login(LoginRequest request) {
        Optional<Tenant> tenant = tenants.findByCode(request.tenantCode()).filter(Tenant::isActive);
        Optional<AppUser> user = tenant.flatMap(t -> users.findByTenantIdAndUsername(t.getId(), request.username()));

        boolean passwordMatches = passwordEncoder.matches(request.password(),
                user.map(AppUser::getPasswordHash).orElse(dummyHash));

        if (!passwordMatches || user.isEmpty() || !user.get().isEnabled()) {
            throw new BadCredentialsException("Bad credentials");
        }
        return new LoginResponse(jwtService.issue(user.get(), tenant.get()), TOKEN_TYPE, jwtService.ttlSeconds(),
                user.get().getRole());
    }
}
