package org.viators.orderprocessingsystem.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT authentication filter that intercepts every HTTP request.
 *
 * <p>Extends {@link OncePerRequestFilter} to guarantee single execution
 * per request, even if the request is forwarded internally (e.g., by
 * Spring's error handling mechanism or dispatcher servlet).
 *
 * <p><strong>Position in the filter chain:</strong> This filter is registered
 * BEFORE Spring Security's {@code UsernamePasswordAuthenticationFilter}.
 * This means JWT authentication is attempted first. If no token is present,
 * the request continues down the chain and Spring Security handles it
 * according to the configured rules (permit or reject).
 *
 * <p><strong>Flow per request:</strong>
 * <pre>
 * 1. Extract "Authorization" header
 * 2. If no header or doesn't start with "Bearer " → skip, pass to next filter
 * 3. Extract JWT token (everything after "Bearer ")
 * 4. Parse token to get username
 * 5. If username found AND no authentication exists yet in SecurityContext:
 *    a. Load UserDetails from database
 *    b. Validate token against UserDetails
 *    c. If valid → create Authentication object and set in SecurityContext
 * 6. Pass request to next filter in chain
 * </pre>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // Step 1: Extract the Authorization header
        String authHeader = request.getHeader("Authorization");

        // If there's no Authorization header, or it doesn't start with "Bearer ",
        // this request doesn't have JWT authentication. Pass it along.
        // It might be a public endpoint (like /api/v1/auth/login) that doesn't
        // need authentication, or it might get rejected by Spring Security later.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Step 2: Extract the token
        // "Bearer eyJhbGciOiJIUzI1NiJ9..." → "eyJhbGciOiJIUzI1NiJ9..."
        String jwt = authHeader.substring(7);

        try {
            // Step 3: Extract username from token
            String username = jwtService.extractUsername(jwt);

            // Step 4: Authenticate  if not already authenticated -
            // SecurityContextHolder.getContext().getAuthentication() == null
            // ensures we don't re-authenticate if something else already did.
            // This is a guard against redundant work.
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                // Step 5: Load full user details from database
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // Step 6: Validate token
                if (jwtService.isTokenValid(jwt, userDetails)) {

                    // Create Authentication token
                    // UsernamePasswordAuthenticationToken is Spring Security's
                    // standard Authentication implementation. The 3-argument
                    // constructor marks it as "authenticated".
                    //
                    // Arguments:
                    // - principal: the user (UserDetails object)
                    // - credentials: null (we already validated via JWT, no need to carry the password around)
                    // - authorities: the user's roles/permissions
                    var authToken = new UsernamePasswordAuthenticationToken(
                            userDetails,
                            null,
                            userDetails.getAuthorities()
                    );

                    // Attach request-specific details (remote IP, session ID, etc.)
                    // Used for auditing and logging purposes.
                    authToken.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    // Step 8: Set authentication in SecurityContext
                    // This is THE critical line. After this, Spring Security
                    // considers this request authenticated. All downstream
                    // filters, @PreAuthorize annotations, and SecurityContext
                    // lookups will see this user as the authenticated principal.
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                    log.debug("Authenticated user '{}' for request: {} {}",
                            username, request.getMethod(), request.getRequestURI());

                }
            }
        } catch (Exception ex) {
            // Token parsing/validation failed. Don't throw — just log and continue.
            // Spring Security will handle the unauthenticated request downstream
            // (either permitting it for public endpoints or rejecting with 401).
            log.debug("JWT authentication failed: {}", ex.getMessage());
        }

        // ── Always continue the filter chain ─────────────────────
        // Whether authentication succeeded or failed, the request must
        // continue through the remaining filters. Spring Security's
        // authorization filters will check the SecurityContext and decide
        // whether to allow or deny access.
        filterChain.doFilter(request, response);
    }
}
