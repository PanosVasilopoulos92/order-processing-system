package org.viators.orderprocessingsystem.auth;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.common.enums.UserRolesEnum;

import java.util.Collection;
import java.util.List;

/**
 * A lightweight principal constructed from gateway-injected headers.
 *
 * <p>When a request comes through the API Gateway, the gateway validates
 * the JWT and injects X-User-UUID, X-User-Role, and X-User-Username
 * headers. This class is constructed from those headers and placed into
 * the SecurityContext as the {@code Authentication.principal}.</p>
 *
 * <h3>Why not keep using {@code UserT}?</h3>
 * <p>{@code UserT} is a JPA entity loaded from the database. Loading it
 * per request just for authentication wastes a DB round-trip when we
 * already have the identity information from the gateway headers.
 * {@code GatewayPrincipal} carries only what's needed for authorization
 * decisions — no DB access required.</p>
 *
 * <h3>Compatibility with existing code:</h3>
 * <ul>
 *   <li>{@code @AuthenticationPrincipal GatewayPrincipal p} → works (direct injection)</li>
 *   <li>{@code @AuthenticationPrincipal(expression = "uuid") String uuid} → works (calls getUuid())</li>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} → works (getAuthorities() returns ROLE_ADMIN)</li>
 *   <li>{@code @PreAuthorize("@userSecurity.isSelf(#uuid)")} → works after UserSecurity refactor</li>
 *   <li>{@code principal.isAdminUser()} → works (delegated to role check)</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class GatewayPrincipal implements UserDetails {

    /** The user's public UUID — same as {@code UserT.getUuid()}. */
    private final String uuid;

    /** The user's role (CUSTOMER, ADMIN, etc.) as an enum. */
    private final UserRolesEnum userRole;

    /** The username — used as the {@code Authentication.name}. */
    private final String username;

    /**
     * Returns the authorities (roles) for Spring Security.
     *
     * <p>Uses the same ROLE_ prefix convention as {@code UserT.getAuthorities()}.
     * This ensures {@code hasRole("ADMIN")} works identically whether
     * the principal is {@code UserT} or {@code GatewayPrincipal}.</p>
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_".concat(userRole.name())));
    }

    /**
     * Not used — the gateway already validated the JWT.
     * Returning empty string because UserDetails requires it.
     */
    @Override
    public @Nullable String getPassword() {
        return "";
    }

    /**
     * Convenience method matching {@code UserT.isAdminUser()}.
     *
     * <p>This allows existing service code like
     * {@code principal.isAdminUser()} to work without changes
     * when the principal type is updated.</p>
     *
     * @return true if the user has the ADMIN role
     */
    public boolean isAdminUser() {
        return UserRolesEnum.ADMIN.equals(this.userRole);
    }
}
