package org.viators.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the Authentication Service.
 *
 * EntityScan points to this module's user package where UserT lives.
 * The BaseEntity class comes from ops-common (org.viators.common.entity)
 * and is discovered through JPA's @MappedSuperclass mechanism.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EntityScan(basePackages = {"org.viators.auth.user", "org.viators.common.entity"})
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }

}
