package org.viators.orderprocessingsystem.user;

import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.viators.orderprocessingsystem.common.BaseEntity;
import org.viators.orderprocessingsystem.common.enums.StatusEnum;
import org.viators.orderprocessingsystem.common.enums.UserRolesEnum;

import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "user")
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

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    @Builder.Default
    private UserRolesEnum userRole = UserRolesEnum.USER;


    // --- User Details implementation --------------------------------

    /**
     * Returns the authorities (roles/permissions) granted to this user.
     *
     * <p>Spring Security uses this to check authorization (e.g., hasRole("ADMIN")).
     * We prefix with "ROLE_" because Spring Security's hasRole() method
     * automatically adds this prefix when checking. So hasRole("ADMIN")
     * actually checks for authority "ROLE_ADMIN".
     *
     * @return a singleton list containing the user's role as a GrantedAuthority
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_".concat(userRole.name())));
    }

    /**
     * Indicates whether the user's account is active.
     *
     * <p>We tie this to our existing StatusEnum. If the status is INACTIVE
     * (soft-deleted), Spring Security will reject authentication even if
     * the password is correct.
     *
     * @return true if the user's status is ACTIVE
     */
    @Override
    public boolean isEnabled() {
        return StatusEnum.ACTIVE.equals(getStatus());
    }

    // --- Helper method ----------------------------------------------
    public boolean isAdminUser() {
        return UserRolesEnum.ADMIN.equals(this.userRole);
    }
}
