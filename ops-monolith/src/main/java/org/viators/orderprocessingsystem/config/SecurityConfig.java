package org.viators.orderprocessingsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.viators.orderprocessingsystem.auth.GatewayAuthenticationFilter;

/**
 * Security configuration for the monolith after auth extraction.
 *
 * Dramatically simplified from the pre-gateway version:
 * - No JWT validation (gateway handles this)
 * - No AuthenticationManager (moved to ops-auth)
 * - No PasswordEncoder (moved to ops-auth)
 * - No DaoAuthenticationProvider (moved to ops-auth)
 *
 * The only filter is GatewayAuthenticationFilter, which reads
 * the X-User-* headers the gateway injected and builds the
 * SecurityContext. Role-based authorization (@PreAuthorize, hasRole)
 * still works because GatewayPrincipal carries the user's role.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final GatewayAuthenticationFilter gatewayAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints no longer exist in the monolith —
                // they're in ops-auth. But we keep health open.
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/admins/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .addFilterBefore(
                gatewayAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}