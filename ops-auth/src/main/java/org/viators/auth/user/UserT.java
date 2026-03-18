package org.viators.auth.user;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.common.enums.StatusEnum;
import org.viators.common.enums.UserRolesEnum;
import org.viators.common.entity.BaseEntity;

import java.util.Collection;
import java.util.List;

/**
 * User entity for the auth service. Maps to the same "users" table
 * as the monolith's UserT (shared database — Phase 1 of migration).
 *
 * This copy is auth-focused: it implements UserDetails for Spring Security
 * and owns the password field. The monolith's copy will diverge over time
 * as it focuses on customer/business concerns.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class UserT extends BaseEntity implements UserDetails {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "firstname")
    private String firstName;

    @Column(name = "lastname")
    private String lastName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "age")
    private Integer age;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "shipping_address")
    private String shippingAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    @Builder.Default
    private UserRolesEnum userRole = UserRolesEnum.CUSTOMER;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_".concat(userRole.name())));
    }

    @Override
    public boolean isEnabled() {
        return StatusEnum.ACTIVE.equals(getStatus());
    }
}