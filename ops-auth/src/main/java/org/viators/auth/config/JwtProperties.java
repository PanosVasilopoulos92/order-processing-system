package org.viators.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds JWT properties from application.yaml.
 * Prefix: application.security.jwt
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "application.security.jwt")
public class JwtProperties {

    private String secretKey;
    private long expiration;
}