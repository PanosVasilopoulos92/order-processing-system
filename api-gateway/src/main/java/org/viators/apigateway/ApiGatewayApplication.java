package org.viators.apigateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the API Gateway.
 *
 * <p>This is a standalone Spring Boot application running on Netty
 * (not Tomcat) because Spring Cloud Gateway uses Spring WebFlux.</p>
 *
 * <p>{@code @EnableDiscoveryClient} registers this gateway with Eureka
 * so it appears in the dashboard, and more importantly, so it can
 * resolve {@code lb://order-processing-system} route URIs by looking
 * up registered service instances.</p>
 *
 * <p>The gateway has three responsibilities:</p>
 * <ol>
 *   <li>Route all incoming requests to the appropriate downstream service</li>
 *   <li>Validate JWT tokens on protected routes</li>
 *   <li>Inject user identity headers (X-User-UUID, X-User-Role) for downstream services</li>
 * </ol>
 */
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }

}
