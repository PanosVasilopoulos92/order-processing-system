package org.viators.orderprocessingsystem.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds JWT-related properties from application.yaml.
 *
 * <p>Properties are prefixed with {@code application.security.jwt}.
 * The secret key should always be provided via environment variable.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

    private String secretKey;
    private long expiration;
}
