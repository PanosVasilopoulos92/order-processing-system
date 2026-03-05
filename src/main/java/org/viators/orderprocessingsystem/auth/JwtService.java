package org.viators.orderprocessingsystem.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.viators.orderprocessingsystem.config.JwtProperties;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * Service for creating, parsing, and validating JWT tokens.
 *
 * <p>Uses the JJWT library (0.13.0) with HMAC-SHA256 signing.
 * The signing key is derived from a Base64-encoded secret configured
 * in application.yaml (sourced from an environment variable).
 *
 * <p><strong>Thread Safety:</strong> This service is stateless after
 * initialization and safe for concurrent use. The {@link SecretKey}
 * is immutable and the JJWT builders create new instances per call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JwtService {

    private final JwtProperties jwtProperties;

    /**
     * The cryptographic key used for signing and verifying tokens.
     * Initialized once at startup from the configured secret.
     */
    private SecretKey signingKey;

    /**
     * Initializes the signing key from the configured secret.
     *
     * <p>{@code @PostConstruct} runs once after dependency injection is complete.
     * We decode the Base64-encoded secret and create an HMAC-SHA key.
     * This avoids recreating the key on every token operation.
     *
     * <p><strong>Why Base64?</strong> The secret in application.yaml is stored
     * as a Base64 string (produced by {@code openssl rand -base64 64}).
     * We decode it here to get the raw bytes that HMAC needs.
     */
    @PostConstruct
    private void init() {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.getSecretKey());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a JWT token for an authenticated user.
     *
     * <p>The token contains:
     * <ul>
     *   <li>{@code sub} (subject): the username — used to identify the user</li>
     *   <li>{@code iat} (issued at): current timestamp</li>
     *   <li>{@code exp} (expiration): current time + configured expiration</li>
     *   <li>Any extra claims passed in the {@code extraClaims} map</li>
     * </ul>
     *
     * <p><strong>Why accept a {@code Map} for extra claims?</strong> This gives
     * flexibility to include role information, user UUID, or other metadata
     * in the token without modifying this method's signature.
     *
     * @param extraClaims additional claims to include (e.g., role, uuid)
     * @param userDetails the authenticated user
     * @return a signed JWT token string
     */
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(now + jwtProperties.getExpiration()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Generates a JWT token with no extra claims.
     *
     * <p>Convenience overload for the common case where you only need
     * the standard claims (subject, issued-at, expiration).
     *
     * @param userDetails the authenticated user
     * @return a signed JWT token string
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(Map.of(), userDetails);
    }

    /**
     * Extracts the username (subject claim) from a token.
     *
     * <p>This is used by the {@code JwtAuthenticationFilter} to identify
     * which user a request belongs to, so it can load their full
     * {@code UserDetails} from the database.
     *
     * @param token the JWT token string
     * @return the username stored in the token's subject claim
     * @throws "JwtException" if the token is invalid, expired, or tampered with
     */
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * Validates a JWT token against a UserDetails object.
     *
     * <p>Validation checks two things:
     * <ol>
     *   <li>The username in the token matches the UserDetails username</li>
     *   <li>The token has not expired</li>
     * </ol>
     *
     * <p><strong>Why check the username match?</strong> This prevents a scenario
     * where User A's valid token is somehow used with User B's UserDetails.
     * It's a defense-in-depth measure.
     *
     * <p>Note: signature verification happens implicitly in {@code extractAllClaims()}
     * — JJWT throws a {@code SignatureException} if the signature doesn't match.
     *
     * @param token       the JWT token to validate
     * @param userDetails the user to validate against
     * @return true if the token is valid for the given user
     */
    public boolean isTokenValid(String token, UserDetails userDetails) {
        String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractAllClaims(token)
                .getExpiration()
                .before(new Date());
    }

    /**
     * Parses the token and extracts all claims.
     *
     * <p>This is where the actual cryptographic verification happens.
     * JJWT will:
     * <ol>
     *   <li>Base64-decode the header, payload, and signature</li>
     *   <li>Recompute the signature using our {@code signingKey}</li>
     *   <li>Compare the computed signature with the token's signature</li>
     *   <li>Check that the token hasn't expired</li>
     * </ol>
     *
     * <p>If any check fails, JJWT throws a specific exception:
     * <ul>
     *   <li>{@link io.jsonwebtoken.security.SignatureException} — tampered token</li>
     *   <li>{@link "ExpiredJwtException"} — token past its expiration</li>
     *   <li>{@link io.jsonwebtoken.MalformedJwtException} — not a valid JWT format</li>
     * </ul>
     *
     * @param token the JWT token string
     * @return the parsed claims (payload data)
     * @throws "JwtException" if the token is invalid for any reason
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
