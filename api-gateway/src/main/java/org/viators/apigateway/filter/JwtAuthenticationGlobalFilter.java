package org.viators.apigateway.filter;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.viators.apigateway.config.JwtConfig;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global filter that validates JWT tokens on every routed request.
 *
 * Implements GlobalFilter (reactive WebFlux API), which runs on every
 * request that matches a configured route. This is the gateway's
 * equivalent of the monolith's old JwtAuthenticationFilter.
 *
 * Flow:
 *   1. Check if path is public (auth, health) — skip if so
 *   2. Extract "Authorization: Bearer ..." header
 *   3. Parse and validate the JWT token
 *   4. Extract uuid, role, username from claims
 *   5. Inject as X-User-* headers on the downstream request
 *   6. Strip the Authorization header (BR-042)
 *
 * The downstream service never sees the raw JWT — only the
 * identity headers this filter injects.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private final JwtConfig jwtConfig;

    /**
     * Paths that bypass JWT validation entirely.
     *
     * <p>These match the monolith's SecurityConfig permitAll() rules.
     * Auth endpoints must be public because the client doesn't have a
     * token yet when logging in or registering.</p>
     *
     * <p>We use {@code startsWith()} matching here. The monolith's
     * SecurityConfig uses Ant-style patterns ({@code /api/v1/auth/**}),
     * which is equivalent for prefix matching.</p>
     */
    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/v1/auth",
        "/actuator/health",
        "/eureka"
    );

    /**
     * Custom header names for downstream identity propagation.
     *
     * <p>These are the "contract" between gateway and downstream services.
     * The monolith's {@code GatewayAuthenticationFilter} reads these
     * headers to build the SecurityContext. Both sides must agree on
     * the exact header names.</p>
     */
    private static final String HEADER_USER_UUID = "X-User-UUID";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String HEADER_USER_USERNAME = "X-User-Username";

    /**
     * The filter logic. Called for every request that matches a route.
     *
     * <p><strong>WebFlux API note:</strong> In reactive programming,
     * you don't "return early" with {@code return;}. Instead, you return
     * a {@code Mono<Void>} — either by calling {@code chain.filter()}
     * to continue, or by completing the response to short-circuit.</p>
     *
     * @param exchange the current server exchange (request + response)
     * @param chain    the filter chain to delegate to
     * @return {@code Mono<Void>} that completes when the filter is done
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ── Step 1: Skip public paths ────────────────────────────
        // If the request is to a public endpoint (auth, health),
        // let it through without any token validation.
        if (isPublicPath(path)) {
            log.debug("Public path accessed: {} — skipping JWT validation", path);
            return chain.filter(exchange);
        }

        // ── Step 2: Extract Authorization header ─────────────────
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("Missing or invalid Authorization header for: {} {}", request.getMethod(), path);
            return onUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        // ── Step 3: Parse and validate the JWT ───────────────────
        String token = authHeader.substring(7);

        try {
            Claims claims = jwtConfig.extractAllClaims(token);

            if (jwtConfig.isTokenExpired(claims)) {
                log.debug("Expired JWT for: {} {}", request.getMethod(), path);
                return onUnauthorized(exchange, "JWT token has expired");
            }

            // ── Step 4: Extract user identity from claims ────────
            // These claims were set by the monolith's AuthService when
            // it generated the token during login/register:
            //   .subject(userDetails.getUsername())   → "sub" claim
            //   Map.of("role", user.getUserRole())    → "role" claim
            //   Map.of("uuid", user.getUuid())        → "uuid" claim
            String username = claims.getSubject();
            String role = claims.get("role", String.class);
            String uuid = claims.get("uuid", String.class);

            if (username == null || role == null || uuid == null) {
                log.warn("JWT missing required claims (sub/role/uuid) for: {} {}", request.getMethod(), path);
                return onUnauthorized(exchange, "JWT token missing required claims");
            }

            // ── Step 5: Inject identity headers & strip JWT ──────
            // We mutate the downstream request to:
            // a) ADD identity headers that the monolith will read
            // b) REMOVE the Authorization header so the monolith
            //    never sees the raw JWT (BR-042)
            ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER_USER_USERNAME, username)
                .header(HEADER_USER_UUID, uuid)
                .header(HEADER_USER_ROLE, role)
                .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                .build();

            // Continue the filter chain with the mutated request.
            log.debug("JWT validated for user '{}' (uuid: {}, role: {}), forwarding to downstream",
                username, uuid, role);
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            // JJWT throws specific exceptions:
            // - SignatureException: token was tampered with
            // - ExpiredJwtException: token past expiration
            // - MalformedJwtException: not a valid JWT format
            // - UnsupportedJwtException: unexpected JWT type
            // All mean the same thing to us: reject with 401.
            log.debug("JWT validation failed for {} {}: {}", request.getMethod(), path, e.getMessage());
            return onUnauthorized(exchange, "Invalid JWT token");
        }

    }

    /**
     * Determines the execution order of this filter.
     *
     * <p>A negative value means "run early." We use -1 to ensure
     * JWT validation happens BEFORE routing filters resolve the
     * {@code lb://} URI and forward the request downstream.</p>
     *
     * <p>This is analogous to the monolith's filter being added
     * "before UsernamePasswordAuthenticationFilter" in SecurityConfig.</p>
     *
     * @return the filter order (lower = earlier)
     */
    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * Checks if the request path is in the public paths list.
     *
     * <p>Uses {@code startsWith()} to match path prefixes.
     * "/api/v1/auth" matches "/api/v1/auth/login", "/api/v1/auth/register", etc.</p>
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Short-circuits the filter chain and returns a 401 Unauthorized response.
     *
     * <p><strong>WebFlux API note:</strong> We don't throw an exception
     * or write directly to an OutputStream. Instead, we set the response
     * status and call {@code setComplete()} which returns a {@code Mono<Void>}
     * that signals "response is done, don't continue the filter chain."</p>
     *
     * @param exchange the current server exchange
     * @param reason   a log-friendly reason (not sent to the client for security)
     * @return a completed Mono that ends the response
     */
    private Mono<Void> onUnauthorized(ServerWebExchange exchange, String reason) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);

        // We intentionally don't send the reason in the response body.
        // Revealing why authentication failed (expired vs invalid vs missing)
        // helps attackers. The client just sees 401.
        return response.setComplete();
    }
}
