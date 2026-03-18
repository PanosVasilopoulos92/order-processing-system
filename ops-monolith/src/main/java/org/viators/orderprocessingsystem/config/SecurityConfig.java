package org.viators.orderprocessingsystem.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.viators.orderprocessingsystem.auth.JwtAuthenticationFilter;
import org.viators.orderprocessingsystem.auth.SecurityExceptionHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final UserDetailsService userDetailsService;
    private final SecurityExceptionHandler securityExceptionHandler;

    /**
     * Configures the HTTP security filter chain.
     *
     * <p>This is the most important bean in the security setup. It defines:
     * <ol>
     *   <li>Which endpoints are public vs. protected</li>
     *   <li>Session management policy (stateless for JWT)</li>
     *   <li>Where our JWT filter sits in the filter chain</li>
     *   <li>Which authentication provider to use</li>
     * </ol>
     *
     * <p><strong>How the filter chain works:</strong><br>
     * Every HTTP request passes through a chain of security filters.
     * Spring Security provides ~15 built-in filters (CSRF, CORS, logout,
     * session management, etc.). We insert our JWT filter before the
     * {@code UsernamePasswordAuthenticationFilter} so JWT tokens are
     * checked before Spring tries form-based authentication.
     *
     * @param http the HttpSecurity builder
     * @return the configured SecurityFilterChain
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http
            // ── CSRF Protection ───────────────────────────────
            // Disabled because we use JWT (stateless). CSRF attacks
            // exploit server-side sessions via cookies. Since we don't
            // use sessions or cookies for auth, CSRF is not a threat.
            // The token in the Authorization header provides equivalent
            // protection since it can't be automatically attached by
            // the browser (unlike cookies).
            .csrf(AbstractHttpConfigurer::disable)

            // ── Authorization Rules ───────────────────────────
            // Order matters! More specific rules must come first.
            // Spring Security evaluates rules top-to-bottom and uses
            // the first match.
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/admins/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )

            // ── Session Management ────────────────────────────
            // STATELESS means Spring Security will NEVER create or
            // use an HttpSession. Each request must carry its own
            // authentication (via JWT). This is essential for REST APIs.
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(securityExceptionHandler)
                .accessDeniedHandler(securityExceptionHandler))

            // ── Authentication Provider ───────────────────────
            // Tells Spring Security HOW to authenticate users
            // (using our UserDetailsService + BCrypt password encoder)
            .authenticationProvider(authenticationProvider())

            // ── JWT Filter Position ───────────────────────────
            // Insert our JWT filter BEFORE UsernamePasswordAuthenticationFilter.
            // This ensures JWT authentication is attempted first.
            .addFilterBefore(
                jwtAuthenticationFilter,
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    /**
     * Configures the authentication provider that Spring Security uses
     * to verify credentials during login.
     *
     * <p>{@link DaoAuthenticationProvider} is Spring's built-in provider
     * that authenticates by loading a user from a data source (via
     * {@link UserDetailsService}) and comparing the submitted password
     * against the stored hash (via {@link PasswordEncoder}).
     *
     * <p><strong>Authentication flow during login:</strong>
     * <pre>
     * 1. User submits { username, password }
     * 2. AuthenticationManager delegates to this provider
     * 3. Provider calls userDetailsService.loadUserByUsername(username)
     * 4. Provider calls passwordEncoder.matches(submittedPassword, storedHash)
     * 5. If match → returns authenticated Authentication object
     * 6. If no match → throws BadCredentialsException
     * </pre>
     *
     * @return the configured DaoAuthenticationProvider
     */
    @Bean
    public AuthenticationProvider authenticationProvider() {
        var authProvider = new DaoAuthenticationProvider(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder());
        return authProvider;
    }

    /**
     * Configures BCrypt as the password encoder.
     *
     * <p>BCrypt is a deliberately slow hashing algorithm designed for
     * passwords. It includes:
     * <ul>
     *   <li>A random salt per password (prevents rainbow table attacks)</li>
     *   <li>A configurable work factor (default: 10 = 2^10 = 1024 rounds)</li>
     *   <li>Future-proofing — you can increase the work factor as hardware improves</li>
     * </ul>
     *
     * <p>The default strength of 10 is appropriate for most applications.
     * Increasing to 12 or higher makes hashing slower (more secure but
     * impacts login response time).
     *
     * @return a BCryptPasswordEncoder with default strength
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Exposes the {@link AuthenticationManager} as a bean.
     *
     * <p>The AuthenticationManager is the entry point for authentication.
     * It delegates to the configured {@link AuthenticationProvider}(s).
     * We need it as a bean so we can inject it into our
     * {@code AuthenticationService} for the login endpoint.
     *
     * <p>Spring Boot autoconfigures an AuthenticationManager, but to
     * inject it, we need to explicitly expose it via this method.
     *
     * @param config the AuthenticationConfiguration provided by Spring
     * @return the AuthenticationManager
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) {
        return config.getAuthenticationManager();
    }
}
