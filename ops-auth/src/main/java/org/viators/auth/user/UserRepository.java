package org.viators.auth.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for user lookups needed during authentication.
 * Only includes the query methods auth requires — no business queries.
 */
@Repository
public interface UserRepository extends JpaRepository<UserT, Long> {

    Optional<UserT> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
