package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.viators.common.enums.UserRolesEnum;

import java.io.IOException;

/**
 * Authentication filter that constructs the SecurityContext from
 * gateway-injected headers instead of JWT tokens.
 *
 * This replaces JwtAuthenticationFilter. The old filter parsed JWT tokens;
 * this one reads HTTP headers that the API Gateway injected after
 * validating the JWT.
 *
 * Trust model: this filter trusts X-User-* headers unconditionally.
 * This is safe because the monolith's port (8888) is internal —
 * only the gateway routes traffic to it via the Docker network.
 *
 * BR-043: requests without X-User-UUID and X-User-Role headers
 * are not authenticated. SecurityConfig's .anyRequest().authenticated()
 * will reject them with 401.
 *
 * Flow:
 *   1. Read X-User-UUID, X-User-Role, X-User-Username from headers
 *   2. If missing → skip (SecurityConfig handles the 401)
 *   3. Build GatewayPrincipal from header values
 *   4. Create Authentication token and set in SecurityContext
 *   5. Continue the filter chain
 */
@Component
@Slf4j
public class GatewayAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_USER_UUID = "X-User-UUID";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_USER_USERNAME = "X-User-Username";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {

        String uuid = request.getHeader(HEADER_USER_UUID);
        String role = request.getHeader(HEADER_USER_ROLE);
        String username = request.getHeader(HEADER_USER_USERNAME);

        if (uuid == null || role == null || username == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserRolesEnum userRole = UserRolesEnum.valueOf(role);
                GatewayPrincipal principal = new GatewayPrincipal(uuid, userRole, username);

                var authToken = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    principal.getAuthorities()
                );

                authToken.setDetails(
                    new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("Authenticated user '{}' (uuid: {}, role: {}) from gateway headers",
                    username, uuid, role);

            } catch (IllegalArgumentException ex) {
                log.warn("Invalid role '{}' in gateway header — rejecting", role);
            }
        }

        filterChain.doFilter(request, response);
    }
}