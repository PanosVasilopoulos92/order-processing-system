package org.viators.eurekaserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Entry point for the Eureka Service Registry.
 *
 * <p>This is a standalone Spring Boot application whose sole responsibility
 * is to act as a service registry. It doesn't contain business logic,
 * database connections, or security configuration.</p>
 *
 * <h3>What {@code @EnableEurekaServer} does under the hood:</h3>
 * <ol>
 *   <li>Imports {@code EurekaServerAutoConfiguration} — this sets up the
 *       in-memory registry, the REST API endpoints ({@code /eureka/apps/*}),
 *       and the dashboard UI.</li>
 *   <li>Configures the Eureka server's own embedded Eureka client
 *       (yes, the server also has a client — in a cluster, servers replicate
 *       to each other via this client. We disable it for standalone mode).</li>
 *   <li>Registers Jersey servlets that handle the Eureka REST protocol
 *       (registration, heartbeats, cancellation, query).</li>
 * </ol>
 *
 *
 * @see org.springframework.cloud.netflix.eureka.server.EnableEurekaServer
 */
@SpringBootApplication
@EnableEurekaServer // Activates the embedded Eureka server
public class EurekaServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(EurekaServerApplication.class, args);
    }

}
