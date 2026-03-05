# JWT Security — Project Skeleton & Bean Reference

## Order Processing System — Spring Boot 4 / Spring Security 7

---

## Project Structure (Security Classes Only)

```
src/main/java/org/viators/orderprocessingsystem/
│
├── auth/                                                          ← NEW PACKAGE
│   │
│   ├── AuthenticationController.java ───────────────────────── @RestController  @RequestMapping("/api/v1/auth")
│   │     POST /api/v1/auth/register  → 201
│   │     POST /api/v1/auth/login     → 200
│   │
│   ├── AuthenticationService.java ──────────────────────────── @Service
│   │     register(RegisterRequest)   → @Transactional
│   │     login(LoginRequest)         → @Transactional(readOnly = true)
│   │
│   ├── CustomUserDetailsService.java ───────────────────────── @Service  @Transactional(readOnly = true)
│   │     implements UserDetailsService
│   │     loadUserByUsername(String)
│   │
│   ├── JwtAuthenticationFilter.java ────────────────────────── @Component
│   │     extends OncePerRequestFilter
│   │     positioned BEFORE UsernamePasswordAuthenticationFilter
│   │
│   ├── JwtService.java ─────────────────────────────────────── @Service
│   │     @PostConstruct init()           → derives SecretKey once at startup
│   │     generateToken(Map, UserDetails) → signed JWT
│   │     generateToken(UserDetails)      → signed JWT (no extra claims)
│   │     extractUsername(String)         → subject claim
│   │     isTokenValid(String, UserDetails)
│   │
│   ├── SecurityExceptionHandler.java ───────────────────────── @Component
│   │     implements AuthenticationEntryPoint   → 401 Unauthorized
│   │     implements AccessDeniedHandler        → 403 Forbidden
│   │
│   └── dto/
│       ├── request/
│       │   ├── LoginRequest.java ───────────────────────────────── record (username, password)
│       │   └── RegisterRequest.java ────────────────────────────── record (username, email, password, firstName, lastName, age)
│       └── response/
│           └── AuthenticationResponse.java ─────────────────────── record (token, uuid, username, email, role)
│
├── config/                                                        ← NEW PACKAGE
│   │
│   ├── JpaAuditingConfig.java ──────────────────────────────── @Configuration  @EnableJpaAuditing
│   │     @Bean auditorAware()             → AuditorAware<String>
│   │
│   ├── JwtProperties.java ─────────────────────────────────── @Component  @ConfigurationProperties("application.security.jwt")
│   │     secretKey, expiration
│   │
│   └── SecurityConfig.java ────────────────────────────────── @Configuration  @EnableWebSecurity  @EnableMethodSecurity
│         @Bean securityFilterChain()      → SecurityFilterChain
│         @Bean authenticationProvider()   → AuthenticationProvider  (DaoAuthenticationProvider)
│         @Bean authenticationManager()    → AuthenticationManager
│         @Bean passwordEncoder()          → PasswordEncoder  (BCryptPasswordEncoder)
│
└── user/                                                          ← MODIFIED
    │
    ├── UserT.java ────────────────────────────────────────── @Entity  implements UserDetails
    │     getAuthorities()  → ROLE_ + userRole.name()
    │     isEnabled()       → StatusEnum.ACTIVE check
    │
    └── UserRepository.java ─────────────────────────────────── @Repository  extends JpaRepository<UserT, Long>
          findByUsername(String)      → Optional<UserT>       (new)
          existsByUsername(String)    → boolean                (new)
          existsByEmail(String)      → boolean                (new)
```

---

## Bean Dependency Graph

```
SecurityConfig
├── defines → securityFilterChain            (SecurityFilterChain)
│   ├── uses → jwtAuthenticationFilter       (JwtAuthenticationFilter)
│   ├── uses → securityExceptionHandler      (SecurityExceptionHandler)
│   ├── uses → authenticationProvider        (AuthenticationProvider)
│   │   ├── uses → userDetailsService        (CustomUserDetailsService)
│   │   └── uses → passwordEncoder           (BCryptPasswordEncoder)
│   └── sets → SessionCreationPolicy.STATELESS
├── defines → authenticationManager          (AuthenticationManager)
└── defines → passwordEncoder                (PasswordEncoder)

JpaAuditingConfig
└── defines → auditorAware                   (AuditorAware<String>)
    └── reads → SecurityContextHolder

JwtAuthenticationFilter
├── injects → JwtService
└── injects → UserDetailsService             (CustomUserDetailsService)

AuthenticationService
├── injects → UserRepository
├── injects → PasswordEncoder
├── injects → JwtService
└── injects → AuthenticationManager

JwtService
└── injects → JwtProperties

CustomUserDetailsService
└── injects → UserRepository
```
