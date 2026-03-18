package org.viators.orderprocessingsystem.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.viators.orderprocessingsystem.exceptions.ResourceNotFoundException;
import org.viators.orderprocessingsystem.user.UserRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /**
     * Locates the user based on the username.
     *
     * <p>Called by Spring Security's {@code AuthenticationManager} during
     * the authentication process (login). Also called by our
     * {@code JwtAuthenticationFilter} on every authenticated request
     * to reconstruct the user's security context.
     *
     * @param username the username identifying the user whose data is required
     * @return a fully populated UserDetails object (our UserT entity)
     * @throws ResourceNotFoundException if no user is found with the given username
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username: %s".formatted(username)));
    }
}
