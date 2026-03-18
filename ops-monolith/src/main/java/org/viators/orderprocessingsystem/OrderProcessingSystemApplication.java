package org.viators.orderprocessingsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Entry point for the Order Processing System monolith.
 *
 * <p>{@code @EnableDiscoveryClient} activates Spring Cloud's service discovery
 * client abstraction. This is a Spring Cloud-level annotation (not Netflix-specific),
 * which means if you ever switch from Eureka to Consul or Zookeeper, this annotation
 * stays the same — only the starter dependency changes.</p>
 *
 * <h3>What happens at startup with this annotation:</h3>
 * <ol>
 *   <li>Spring Cloud auto-detects the Eureka client on the classpath.</li>
 *   <li>{@code EurekaClientAutoConfiguration} creates an {@code EurekaClient} bean.</li>
 *   <li>The client sends a POST to the Eureka server's {@code /eureka/apps/} endpoint
 *       with this instance's metadata (host, port, health URL, app name).</li>
 *   <li>A scheduled task begins sending heartbeats every 30 seconds.</li>
 *   <li>On graceful shutdown, a DELETE request deregisters this instance.</li>
 * </ol>
 *
 * <p><strong>Note:</strong> In Spring Cloud 2025.1.0, {@code @EnableDiscoveryClient}
 * is technically optional if the eureka-client starter is on the classpath (auto-configuration
 * handles it). We include it explicitly because:</p>
 * <ul>
 *   <li>It makes the intent crystal clear in the code — this app participates in discovery.</li>
 *   <li>It satisfies BR-041 traceability — you can grep for this annotation to verify compliance.</li>
 *   <li>It's self-documenting for developers who read the main class first.</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"org.viators.orderprocessingsystem", "org.viators.common"})
@EntityScan(basePackages = {"org.viators.orderprocessingsystem", "org.viators.common.entity"})
@EnableDiscoveryClient  // Registers this service with Eureka on startup
public class OrderProcessingSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingSystemApplication.class, args);
    }
}
