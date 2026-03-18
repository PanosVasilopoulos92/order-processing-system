package org.viators.apigateway.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * JWT configuration and parsing for the API Gateway.
 *
 * This class binds the JWT secret key from application.yaml and
 * provides methods to parse and validate tokens. Unlike the auth
 * service's JwtService, this class does NOT generate tokens —
 * it only verifies signatures and extracts claims.
 *
 * Why duplicate JWT parsing instead of sharing a library?
 * The gateway runs on WebFlux/Netty and has zero JPA or servlet
 * dependencies. Pulling in ops-common (which needs JPA and servlet)
 * just for 3 JWT methods would bloat the gateway's classpath.
 * For this small surface area, duplication is cleaner than coupling.
 */
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
@Getter
@Setter
@Slf4j
public class JwtConfig {

    /**
     * The Base64-encoded secret key, bound from application.yaml.
     * This is the same key the monolith uses to SIGN tokens.
     * The gateway uses it to VERIFY signatures.
     */
    private String secretKey;

    /**
     * The parsed cryptographic key. Initialized once at startup.
     */
    private SecretKey signingKey;

    /**
     * Decodes the Base64 secret and creates the HMAC-SHA key.
     * Runs once after Spring injects the property values.
     */
    @PostConstruct
    private void init() {
        byte[] keyBytes = Base64.getDecoder().decode(secretKey);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        log.info("JWT singing key initialized for gateway token validation");
    }

    /**
     * Extracts all claims from a JWT token.
     *
     * <p>This is where cryptographic verification happens. JJWT will:</p>
     * <ol>
     *   <li>Base64-decode the header, payload, and signature</li>
     *   <li>Recompute the signature using our signing key</li>
     *   <li>Compare computed vs. provided signature</li>
     *   <li>Check that the token hasn't expired</li>
     * </ol>
     *
     * <p>If any check fails, JJWT throws a specific exception
     * (SignatureException, ExpiredJwtException, MalformedJwtException).
     * The caller (our GlobalFilter) catches these and returns 401.</p>
     *
     * @param token the raw JWT string (without "Bearer " prefix)
     * @return the parsed claims containing sub, role, exp, etc.
     */
    public Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean isTokenExpired(Claims claims) {
        return claims.getExpiration().before(new Date());
    }
}
