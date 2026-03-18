# Eureka Service Registry Guide

## For the Order Processing System — Spring Boot 4 / Spring Cloud 2025.1.0 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [How Service Discovery Works (The Flow)](#2-how-service-discovery-works-the-flow)
3. [Architecture Overview](#3-architecture-overview)
4. [Project Structure Decision: Multi-Module Maven](#4-project-structure-decision-multi-module-maven)
5. [Version Compatibility Matrix](#5-version-compatibility-matrix)
6. [Implementation: Step by Step](#6-implementation-step-by-step)
   - 6.1 [Convert to Multi-Module Maven Project](#61-convert-to-multi-module-maven-project)
   - 6.2 [Create the Eureka Server Module](#62-create-the-eureka-server-module)
   - 6.3 [Eureka Server Application Class](#63-eureka-server-application-class)
   - 6.4 [Eureka Server Configuration](#64-eureka-server-configuration)
   - 6.5 [Register the Monolith as a Eureka Client](#65-register-the-monolith-as-a-eureka-client)
   - 6.6 [Update the Monolith's application.yaml](#66-update-the-monoliths-applicationyaml)
   - 6.7 [Add Eureka Server to docker-compose.yml](#67-add-eureka-server-to-docker-composeyml)
   - 6.8 [Dockerfile for eureka-server](#68-dockerfile-for-eureka-server)
7. [How Everything Connects: Startup Lifecycle](#7-how-everything-connects-startup-lifecycle)
8. [Eureka Internals — What Happens Under the Hood](#8-eureka-internals--what-happens-under-the-hood)
9. [Testing & Verification](#9-testing--verification)
10. [Configuration Reference](#10-configuration-reference)
11. [Common Mistakes to Avoid](#11-common-mistakes-to-avoid)
12. [Final Project Structure](#12-final-project-structure)

---

## 1. Understanding the Big Picture

Before writing any code, you need to understand **what problem we're solving** and **why service discovery matters** — even when your system is still a monolith.

### The Problem: Hardcoded Service Addresses

Imagine your Order Processing System grows. You extract the notification feature into its own microservice. Your monolith needs to call `http://localhost:8090/api/v1/notifications` to send emails. This works on your machine, but:

- **In Docker**, the notification service isn't at `localhost` — it's at a container hostname.
- **In production**, you might have 3 instances of the notification service behind a load balancer. Which IP do you hardcode?
- **During scaling**, instances come and go. An address that worked 5 minutes ago might be dead now.

Hardcoded addresses are the distributed equivalent of hardcoded database credentials — they work until deployment complexity inevitably breaks them.

### The Solution: Service Registry

A **service registry** is like a phone book for your microservices. Instead of knowing each other's addresses, services know one thing: _the registry's address_. Everything else follows from that:

1. Each service **registers** itself with the registry on startup ("I'm `order-processing-system`, and I'm at `192.168.1.5:8888`").
2. Each service **deregisters** on shutdown ("I'm going offline").
3. When Service A needs to call Service B, it asks the registry: "Where is `notification-service` right now?"
4. The registry responds with a list of healthy instances.
5. Service A picks one (client-side load balancing) and makes the call.

### Why Eureka?

Netflix created Eureka to solve exactly this problem at massive scale. Spring Cloud Netflix integrates it seamlessly into the Spring ecosystem. Eureka is:

- **AP-oriented** (from CAP theorem) — it prioritizes availability over consistency. If the registry has a network partition, instances can still find each other using their local cache. This is the right trade-off for service discovery: a slightly stale address is better than no address at all.
- **Self-preserving** — if too many heartbeats fail at once (likely a network issue, not mass service failure), Eureka stops evicting instances instead of wiping the registry clean.
- **Battle-tested** — Netflix runs this in production at enormous scale.

### Why Set This Up Now (While We're Still a Monolith)?

You might wonder: "We only have one service. Why add a registry?" Three reasons:

1. **Infrastructure-first thinking** — in real projects, service discovery is foundational infrastructure. You set it up early so that when you extract your first microservice, the communication layer is already in place.
2. **Docker networking** — even in local dev, your monolith running inside Docker can't reach other containers by `localhost`. Eureka provides name-based resolution that works across Docker's internal network.
3. **Learning progression** — Epic 10 is about building the platform layer. Having Eureka ready now means Epic 11 (API Gateway) can use it immediately for routing.

---

## 2. How Service Discovery Works (The Flow)

### Registration Flow (on startup)

```
eureka-server                     order-processing-system
     |                                      |
     |          POST /eureka/apps/ORDER-PROCESSING-SYSTEM
     |<-----------------------------------------|
     |                                          |
     |  1. Receives registration request        |
     |  2. Stores instance in in-memory registry|
     |  3. Returns 204 No Content               |
     |----------------------------------------->|
     |                                          |
     |  (Every 30 seconds: heartbeat)           |
     |          PUT /eureka/apps/ORDER-PROCESSING-SYSTEM/{instanceId}
     |<-----------------------------------------|
     |  Returns 200 OK (lease renewed)          |
     |----------------------------------------->|
```

### Discovery Flow (service-to-service call)

```
notification-service          eureka-server          order-processing-system
        |                          |                          |
        | GET /eureka/apps/ORDER-PROCESSING-SYSTEM            |
        |------------------------->|                          |
        |                          |                          |
        | Returns JSON:            |                          |
        | [{host: "172.18.0.4",    |                          |
        |   port: 8888,            |                          |
        |   status: "UP"}]         |                          |
        |<-------------------------|                          |
        |                                                     |
        | GET http://172.18.0.4:8888/api/v1/orders/123        |
        |---------------------------------------------------->|
        |                                                     |
        | 200 OK { orderDetails }                             |
        |<----------------------------------------------------|
```

### Deregistration Flow (on shutdown)

```
eureka-server                     order-processing-system
     |                                      |
     |  (Graceful shutdown triggered)       |
     |                                      |
     |          DELETE /eureka/apps/ORDER-PROCESSING-SYSTEM/{instanceId}
     |<-----------------------------------------|
     |                                          |
     |  1. Removes instance from registry       |
     |  2. Returns 200 OK                       |
     |----------------------------------------->|
     |                                          |
     |  (Instance is gone)                     ✕
```

> **Key Insight**: The client (your monolith) does most of the work — it registers itself, sends heartbeats, and deregisters itself. The server is a passive registry that just stores and serves instance data. This is a deliberate design choice: it keeps the server simple and pushable, and distributes the load across clients.

---

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                    Docker Compose Network                     │
│                                                              │
│  ┌──────────────────┐         ┌───────────────────────────┐  │
│  │  eureka-server    │         │  order-processing-system  │  │
│  │  (Port 8761)      │◄────────│  (Port 8888)              │  │
│  │                   │ register│                           │  │
│  │  ┌─────────────┐  │ + hbeat │  Spring Boot 4 monolith  │  │
│  │  │  In-Memory   │  │────────►│  + Eureka Client         │  │
│  │  │  Registry    │  │ lookup  │                           │  │
│  │  └─────────────┘  │         └───────────────────────────┘  │
│  │                   │                                        │
│  │  Dashboard: 8761  │         ┌───────────────────────────┐  │
│  └──────────────────┘         │  (Future) API Gateway      │  │
│                                │  + Eureka Client           │  │
│                                └───────────────────────────┘  │
│                                                              │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │  MySQL   │  │ RabbitMQ │  │ MailHog  │                   │
│  │  3306    │  │ 5672     │  │ 1025     │                   │
│  └──────────┘  └──────────┘  └──────────┘                   │
└──────────────────────────────────────────────────────────────┘
```

The `eureka-server` is a standalone Spring Boot application with a single responsibility: maintain the service registry. It has no business logic, no database, and no dependencies on your monolith.

---

## 4. Project Structure Decision: Multi-Module Maven

### The Question

Right now, your OPS project is a single Maven module. To add `eureka-server` as a standalone Spring Boot app, we need to decide where it lives. The two options:

| Approach | Pros | Cons |
|----------|------|------|
| **Separate Git repo** | Full isolation; independent versioning | Harder to keep in sync; more repos to manage |
| **Multi-module Maven** | Single repo; shared parent BOM; atomic commits | Slightly more complex `pom.xml` structure |

### Why Multi-Module?

For a learning project in local dev (Phase 4 scope), multi-module Maven is the better choice:

1. **Shared dependency management** — the Spring Boot and Spring Cloud versions are defined once in the parent POM. Every module inherits them. No version drift.
2. **Atomic commits** — when you add Eureka client config to the monolith AND create the server module, it's one commit. The repo is always in a consistent state.
3. **Industry standard** — most real microservice projects that aren't at Netflix scale use multi-module Maven or Gradle. You'll see this pattern in job interviews and production codebases.

### What Changes

The existing `pom.xml` becomes the **parent POM** (packaging type `pom`). The current source code moves into a child module called `ops-monolith`. A new child module called `eureka-server` is created. Both inherit from the parent.

```
order-processing-system/            ← Root (parent POM)
├── pom.xml                         ← Parent: defines shared versions & modules
├── ops-monolith/                   ← Child: your existing monolith code
│   ├── pom.xml                     ← Inherits parent, adds its own deps
│   └── src/
│       ├── main/java/org/viators/orderprocessingsystem/
│       └── main/resources/
├── eureka-server/                  ← Child: new Eureka server
│   ├── pom.xml                     ← Inherits parent, adds Eureka server dep
│   └── src/
│       ├── main/java/org/viators/eurekaserver/
│       └── main/resources/
├── docker-compose.yml
└── README.md
```

> **Important**: Moving to multi-module is a structural refactor. You'll move files, not rewrite them. All your existing code stays exactly the same — it just lives one directory deeper.

---

## 5. Version Compatibility Matrix

Getting the right versions is critical. Spring Cloud release trains are tied to specific Spring Boot versions. Use the wrong combination and you'll get cryptic `NoSuchMethodError` exceptions at startup.

| Component | Version | Why This Version |
|-----------|---------|-----------------|
| Spring Boot | 4.0.3 | Your current version (parent POM) |
| Spring Cloud BOM | 2025.1.0 | The _only_ GA release train that supports Spring Boot 4.x |
| Spring Cloud Netflix | 5.0.0 | Managed by the 2025.1.0 BOM — you don't specify this directly |
| Java | 25 | Your current version |

### How the Spring Cloud BOM Works

Spring Cloud uses a **Bill of Materials (BOM)** — a special POM that only contains `<dependencyManagement>` entries. When you import it, it tells Maven: "If anyone asks for `spring-cloud-starter-netflix-eureka-server`, use version 5.0.0." You never hardcode individual Spring Cloud artifact versions — the BOM manages them all.

```
┌─────────────────────────────────────┐
│  spring-cloud-dependencies          │
│  (BOM version: 2025.1.0)           │
│                                     │
│  Manages:                           │
│  ├── spring-cloud-netflix  → 5.0.0  │
│  ├── spring-cloud-commons  → 5.0.0  │
│  ├── spring-cloud-config   → 5.0.0  │
│  ├── spring-cloud-gateway  → 5.0.0  │
│  └── ... 20+ more modules           │
└─────────────────────────────────────┘
```

---

## 6. Implementation: Step by Step

### 6.1 Convert to Multi-Module Maven Project

#### Step 1: Create the new parent POM

The root `pom.xml` becomes the parent. It changes to `<packaging>pom</packaging>` and declares two modules. All shared dependency versions live here.

**File: `order-processing-system/pom.xml`** (root — replaces your existing pom.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!--
        PARENT POM: This is the root of our multi-module project.
        It defines shared configuration that ALL child modules inherit:
        - Spring Boot version (via spring-boot-starter-parent)
        - Spring Cloud BOM (for consistent Cloud dependency versions)
        - Java version
        - Common plugins (Lombok annotation processing, etc.)

        This POM does NOT contain application code — it's purely
        a dependency management and build orchestration artifact.
    -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/> <!-- Lookup from Maven Central, not filesystem -->
    </parent>

    <groupId>org.viators</groupId>
    <artifactId>order-processing-system</artifactId>
    <version>0.0.1-SNAPSHOT</version>

    <!--
        packaging=pom tells Maven: "This project doesn't produce a JAR.
        It's a parent that aggregates child modules."
        Without this, Maven tries to compile sources that don't exist here.
    -->
    <packaging>pom</packaging>

    <name>Order Processing System</name>
    <description>Multi-module parent for the Order Processing System platform</description>

    <!--
        MODULES: Maven builds these in dependency order.
        List every child module directory here. Maven will:
        1. Read each module's pom.xml
        2. Resolve inter-module dependencies
        3. Build them in the correct order (reactor build)
    -->
    <modules>
        <module>ops-monolith</module>
        <module>eureka-server</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <!--
            Spring Cloud BOM version. This is the ONLY place you specify it.
            2025.1.0 is the GA release train for Spring Boot 4.x / Spring Framework 7.
            See: https://spring.io/projects/spring-cloud#overview
        -->
        <spring-cloud.version>2025.1.0</spring-cloud.version>
    </properties>

    <!--
        DEPENDENCY MANAGEMENT: Imported BOMs and version-pinned dependencies.
        Children inherit these — they can declare dependencies WITHOUT versions.

        Think of this as a "menu" of pre-approved dependency versions.
        Child modules pick what they need from this menu.
    -->
    <dependencyManagement>
        <dependencies>
            <!--
                Spring Cloud BOM: Importing this with scope=import and type=pom
                brings in version management for ALL Spring Cloud artifacts.
                This means any child can use spring-cloud-starter-netflix-eureka-server
                without specifying a version — the BOM resolves it to 5.0.0.
            -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <!--
        SHARED DEPENDENCIES: Every child module gets these automatically.
        Only put truly universal dependencies here (Lombok, devtools).
        Module-specific deps (JPA, Security, Eureka) go in child POMs.
    -->
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>

    <build>
        <!--
            PLUGIN MANAGEMENT: Define plugin configurations that children inherit.
            Children still need to declare the plugin, but they get this config
            for free unless they override it.
        -->
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                        <annotationProcessorPaths>
                            <path>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                            </path>
                        </annotationProcessorPaths>
                    </configuration>
                </plugin>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <configuration>
                        <excludes>
                            <exclude>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                            </exclude>
                        </excludes>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

#### Step 2: Move existing code into `ops-monolith/`

This is a filesystem operation. You move your existing `src/` directory and create a child POM for it:

```bash
# From the repository root:
mkdir ops-monolith
mv src/ ops-monolith/
```

#### Step 3: Create the monolith's child POM

**File: `ops-monolith/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!--
        CHILD POM: Inherits from the parent POM one level up.
        The <parent> block tells Maven:
        - "My parent is the POM in the directory above me."
        - "I inherit its Java version, Spring Boot version,
           Spring Cloud BOM, shared deps, and plugin config."
    -->
    <parent>
        <groupId>org.viators</groupId>
        <artifactId>order-processing-system</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>ops-monolith</artifactId>
    <name>OPS Monolith</name>
    <description>Order Processing System — monolith application</description>

    <!--
        No <packaging> tag needed — Maven defaults to "jar",
        and spring-boot-maven-plugin repackages it as an executable fat JAR.
    -->

    <dependencies>
        <!-- ── Core Spring Boot starters ───────────────────────── -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- ── Messaging & Mail ────────────────────────────────── -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-mail</artifactId>
        </dependency>

        <!-- ── Eureka Client ───────────────────────────────────── -->
        <!--
            This starter brings in:
            - spring-cloud-netflix-eureka-client (the actual Eureka client logic)
            - spring-cloud-starter (common Spring Cloud infrastructure)
            - jersey-client (Eureka's HTTP transport — Netflix built Eureka on Jersey)

            No <version> tag needed — the Spring Cloud BOM in the parent
            resolves this to the correct version (5.0.0 for the 2025.1.0 train).
        -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
        </dependency>

        <!-- ── Database Drivers ────────────────────────────────── -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ── JWT Support ─────────────────────────────────────── -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.13.0</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.13.0</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.13.0</version>
            <scope>runtime</scope>
        </dependency>

        <!-- ── Dev Tools ───────────────────────────────────────── -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-devtools</artifactId>
            <scope>runtime</scope>
        </dependency>

        <!-- ── Testing ─────────────────────────────────────────── -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <!--
                Declare the plugins so Maven picks up the config
                from the parent's <pluginManagement>.
            -->
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

### 6.2 Create the Eureka Server Module

Create the directory structure for the new module:

```bash
mkdir -p eureka-server/src/main/java/org/viators/eurekaserver
mkdir -p eureka-server/src/main/resources
```

**File: `eureka-server/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!--
        EUREKA SERVER MODULE: A standalone Spring Boot app with one job —
        be the service registry.

        This module is deliberately minimal. It has NO business logic,
        NO database, NO security configuration. Its only dependency
        (beyond what the parent provides) is the Eureka server starter.

        Design principle: infrastructure services should be as simple
        as possible. Complexity in the registry means fragility in
        the entire platform.
    -->
    <parent>
        <groupId>org.viators</groupId>
        <artifactId>order-processing-system</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>

    <artifactId>eureka-server</artifactId>
    <name>Eureka Server</name>
    <description>Netflix Eureka service registry for the OPS platform</description>

    <dependencies>
        <!--
            This single starter brings in everything needed to run
            a full Eureka server:
            - Embedded Eureka server (Netflix OSS)
            - Spring Boot auto-configuration for Eureka
            - Dashboard UI (the web page at http://localhost:8761)
            - REST API endpoints under /eureka/* for client registration

            Version is managed by the Spring Cloud BOM imported in the parent.
        -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
        </dependency>

        <!-- ── Testing ─────────────────────────────────────────── -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

---

### 6.3 Eureka Server Application Class

**File: `eureka-server/src/main/java/org/viators/eurekaserver/EurekaServerApplication.java`**

```java
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
 * <p>In a production cluster, you'd run multiple Eureka servers that replicate
 * to each other. For our Phase 4 local development scope, a single standalone
 * instance is sufficient.</p>
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
```

> **Why `@EnableEurekaServer` is still needed**: In Spring Cloud Netflix 5.0 / Spring Boot 4, this annotation is *not* auto-configured just by having the dependency on the classpath. Unlike the Eureka *client* (which auto-registers if the client starter is present), the *server* requires an explicit opt-in via `@EnableEurekaServer`. This is intentional — running a registry is a deliberate infrastructure decision, not something that should happen accidentally.

---

### 6.4 Eureka Server Configuration

**File: `eureka-server/src/main/resources/application.yaml`**

```yaml
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# EUREKA SERVER CONFIGURATION
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

server:
  # Port 8761 is the Eureka convention. All Eureka clients default to
  # looking for a server at http://localhost:8761/eureka — using this
  # port means clients work with zero additional configuration.
  port: 8761

spring:
  application:
    # This name appears in the Eureka dashboard and in log output.
    # It's also how other servers would find this instance in a cluster.
    name: eureka-server

# ── Eureka Server-Specific Settings ──────────────────────────

eureka:
  client:
    # WHY THESE ARE FALSE:
    # A Eureka server has an embedded Eureka *client* (for cluster replication).
    # In a multi-server cluster, Server A registers with Server B and vice versa.
    # But we're running a SINGLE standalone instance, so:
    #
    # register-with-eureka: false
    #   → "Don't try to register yourself with another Eureka server."
    #     There IS no other server to register with.
    #
    # fetch-registry: false
    #   → "Don't try to fetch the registry from another Eureka server."
    #     You ARE the only source of truth.
    #
    # If you set these to true (the default), the server would try to
    # reach http://localhost:8761/eureka and fail — because that's itself.
    # It would log connection-refused errors on every heartbeat cycle.
    register-with-eureka: false
    fetch-registry: false

  server:
    # HOW SELF-PRESERVATION WORKS:
    # Eureka tracks the expected number of heartbeats per minute.
    # If the actual number drops below a threshold (default: 85%),
    # Eureka enters "self-preservation mode" — it STOPS evicting instances.
    #
    # Why? If 50% of heartbeats suddenly fail, it's more likely a network
    # partition than 50% of your services dying simultaneously. Evicting
    # them would make the registry useless during a network hiccup.
    #
    # For LOCAL DEVELOPMENT, this causes confusion:
    # - You stop a service → it stays in the registry for minutes
    # - The dashboard shows a scary red warning banner
    # - You wonder why your stopped service still appears as "UP"
    #
    # So we disable it in dev. In production, LEAVE IT ENABLED.
    enable-self-preservation: false

    # When self-preservation is off, this controls how often Eureka
    # checks for expired leases and evicts them. Default is 60 seconds.
    # We use 5 seconds for fast feedback during development.
    eviction-interval-timer-in-ms: 5000

logging:
  level:
    # Reduce Eureka's chatty logging in development.
    # It logs every heartbeat, every registry fetch, every replication attempt.
    com.netflix.eureka: WARN
    com.netflix.discovery: WARN
```

### Why Each Setting Matters — Decision Table

| Setting | Value | Why |
|---------|-------|-----|
| `server.port: 8761` | Convention | Clients default to this port — zero config on client side |
| `register-with-eureka: false` | Standalone | No cluster to register with; prevents self-registration errors |
| `fetch-registry: false` | Standalone | No peer to fetch from; we ARE the registry |
| `enable-self-preservation: false` | Dev only | Fast eviction when you stop services; **re-enable in prod** |
| `eviction-interval-timer-in-ms: 5000` | Dev only | Check for dead instances every 5s instead of 60s |

---

### 6.5 Register the Monolith as a Eureka Client

On the client side (your monolith), the only code change is a single annotation on the main application class.

**File: `ops-monolith/src/main/java/org/viators/orderprocessingsystem/OrderProcessingSystemApplication.java`**

```java
package org.viators.orderprocessingsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
@SpringBootApplication
@EnableDiscoveryClient // Registers this service with Eureka on startup (BR-041)
public class OrderProcessingSystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderProcessingSystemApplication.class, args);
    }
}
```

### `@EnableDiscoveryClient` vs `@EnableEurekaClient` — Which One?

| Annotation | Scope | Use When |
|------------|-------|----------|
| `@EnableDiscoveryClient` | Spring Cloud (generic) | You want portability across discovery providers |
| `@EnableEurekaClient` | Netflix Eureka (specific) | You want Eureka-only features (rare) |

We use `@EnableDiscoveryClient` because it's the **abstraction** over Eureka. If you later replace Eureka with Consul or Kubernetes service discovery, you change the dependency — not the annotation. This is dependency inversion in action: depend on abstractions, not implementations.

---

### 6.6 Update the Monolith's application.yaml

Add the Eureka client section to the existing configuration.

**File: `ops-monolith/src/main/resources/application.yaml`** (additions only — keep all existing config)

```yaml
# ... (all your existing config stays exactly the same) ...

# ── Eureka Client Configuration ────────────────────────────────

eureka:
  client:
    # The URL of the Eureka server's REST API.
    # Note the /eureka suffix — this is the API base path, not the dashboard URL.
    #
    # We use an environment variable with a localhost default so that:
    # - Running locally (outside Docker): connects to localhost:8761
    # - Running in Docker: docker-compose sets EUREKA_URI to the container hostname
    service-url:
      defaultZone: ${EUREKA_URI:http://localhost:8761/eureka}

    # How often this client fetches the registry from the server.
    # Default is 30 seconds. We use 5 seconds in dev for fast feedback
    # when services come and go during development.
    registry-fetch-interval-seconds: 5

  instance:
    # Use the actual IP address in registration, not the container hostname.
    # This matters in Docker: container hostnames are random hex strings
    # (like "a1b2c3d4e5f6") that aren't resolvable from outside Docker.
    # IP addresses work everywhere.
    prefer-ip-address: true

    # How often this instance sends a heartbeat to the server.
    # Default is 30 seconds. We use 5 seconds in dev for fast detection
    # of stopped services.
    lease-renewal-interval-in-seconds: 5

    # How long the server waits without a heartbeat before evicting this instance.
    # Default is 90 seconds. We use 10 seconds in dev.
    # IMPORTANT: This must be > lease-renewal-interval-in-seconds.
    lease-expiration-duration-in-seconds: 10
```

### Configuration Flow Diagram

```
┌─────────────────────────────────────────────────────────────┐
│              Monolith's Eureka Client Config                 │
│                                                             │
│  service-url.defaultZone                                    │
│  ┌─────────────────────────────────────────────────┐        │
│  │ "Where is the Eureka server?"                    │        │
│  │                                                  │        │
│  │ ${EUREKA_URI} → set by Docker or env variable    │        │
│  │ Fallback → http://localhost:8761/eureka           │        │
│  └─────────────────────────────────────────────────┘        │
│                                                             │
│  prefer-ip-address: true                                    │
│  ┌─────────────────────────────────────────────────┐        │
│  │ "Register with my IP, not my hostname"           │        │
│  │                                                  │        │
│  │ Without this in Docker:                          │        │
│  │   Registers as "a1b2c3d4e5f6:8888" ← unresolvable│       │
│  │ With this:                                       │        │
│  │   Registers as "172.18.0.4:8888" ← works!       │        │
│  └─────────────────────────────────────────────────┘        │
│                                                             │
│  lease-renewal-interval: 5s    (heartbeat frequency)        │
│  lease-expiration-duration: 10s (time-to-evict)             │
│  registry-fetch-interval: 5s   (how often to refresh cache) │
│  ── All aggressive for dev; use defaults (30/90/30) in prod ─│
└─────────────────────────────────────────────────────────────┘
```

---

### 6.7 Add Eureka Server to docker-compose.yml

**File: `docker-compose.yml`** (updated — add the eureka-server service)

```yaml
services:
  # ── Eureka Service Registry ──────────────────────────────────
  # Must start BEFORE any service that registers with it.
  # Other services use depends_on to wait for Eureka's healthcheck.
  eureka-server:
    build:
      # The build context is the eureka-server/ subdirectory.
      # Docker will look for a Dockerfile there.
      context: ./eureka-server
      dockerfile: Dockerfile
    container_name: ops-eureka
    ports:
      # 8761 → convention port. Maps container port to host port
      # so you can access the dashboard at http://localhost:8761
      - "8761:8761"
    healthcheck:
      # Eureka exposes a health endpoint via Spring Boot Actuator.
      # We use wget instead of curl because our base image (eclipse-temurin)
      # doesn't include curl by default.
      #
      # The /actuator/health endpoint returns {"status":"UP"} when ready.
      # We check this to know when Eureka is truly ready to accept registrations.
      test: [ "CMD", "wget", "--spider", "-q", "http://localhost:8761/actuator/health" ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - ops-network

  mysql:
    image: mysql:8
    container_name: ops-mysql
    ports:
      - "3306:3306"
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:?error}
      MYSQL_DATABASE: ${MYSQL_DATABASE:?error}
      MYSQL_USER: ${MYSQL_USER:?error}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD:?error}
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: [ "CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}" ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - ops-network

  # ── RabbitMQ 3 with Management UI ──────────────────────────
  rabbitmq:
    image: rabbitmq:3-management
    container_name: ops-rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:?error}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?error}
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    healthcheck:
      test: [ "CMD", "rabbitmq-diagnostics", "ping" ]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s
    networks:
      - ops-network

  # ── MailHog (optional — development email testing) ──────────
  mailhog:
    image: mailhog/mailhog
    container_name: ops-mailhog
    ports:
      - "1025:1025"
      - "8025:8025"
    networks:
      - ops-network

# ── Named Volumes ──────────────────────────────────────────────
volumes:
  mysql_data:
  rabbitmq_data:

# ── Shared Network ─────────────────────────────────────────────
# All services on the same bridge network can reach each other
# by container name (e.g., "ops-eureka", "ops-mysql").
# This is how the monolith finds Eureka inside Docker.
networks:
  ops-network:
    driver: bridge
```

> **Why a named network?** Without an explicit network, Docker Compose creates a default one named `<project>_default`. That works, but naming it `ops-network` makes it self-documenting and allows external containers to join later.

> **Why `depends_on` is NOT shown on the monolith yet**: Your monolith isn't Dockerized in this ticket (it's running from your IDE). When you Dockerize it in a future ticket, you'll add `depends_on: eureka-server: condition: service_healthy` to ensure it waits for Eureka.

---

### 6.8 Dockerfile for eureka-server

**File: `eureka-server/Dockerfile`**

```dockerfile
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
# MULTI-STAGE BUILD for the Eureka Server
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#
# Stage 1 (builder): Compiles the project and creates the JAR.
#   Uses a full JDK image with Maven.
#
# Stage 2 (runtime): Runs the JAR with a minimal JRE.
#   The final image is ~300MB smaller because it doesn't include
#   the JDK compiler, Maven, or source files.

# ── Stage 1: Build ─────────────────────────────────────────────
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /build

# Copy the parent POM first (for dependency resolution).
# Maven needs the parent POM to resolve the <parent> block
# in the eureka-server's POM.
COPY ../pom.xml ./pom.xml
COPY eureka-server/pom.xml ./eureka-server/pom.xml

# Download dependencies (cached by Docker layer caching).
# The -pl flag tells Maven: "Only process the eureka-server module."
# The -am flag means "also make" — build any modules it depends on (the parent).
WORKDIR /build/eureka-server
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source code and build.
COPY eureka-server/src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn package -DskipTests -B

# ── Stage 2: Runtime ───────────────────────────────────────────
FROM eclipse-temurin:25-jre

WORKDIR /app

# Copy the fat JAR from the builder stage.
COPY --from=builder /build/eureka-server/target/*.jar app.jar

# Expose the Eureka port (documentation for docker-compose and operators).
EXPOSE 8761

# Run with sensible JVM flags for containers.
# -XX:+UseContainerSupport: JVM respects container memory/CPU limits.
# -XX:MaxRAMPercentage=75: Use at most 75% of container's memory for heap.
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
```

> **Alternative approach**: If the multi-stage build is too complex for your current setup, you can build the JAR locally with `mvn package -pl eureka-server -am` and use a simpler Dockerfile that just copies the pre-built JAR. The multi-stage approach is shown here because it's the production pattern — the Docker image can be built on any machine with Docker, without needing Java installed.

---

## 7. How Everything Connects: Startup Lifecycle

```
┌─────────────────────────────────────────────────────────────────────┐
│                    docker-compose up                                  │
│                                                                      │
│  1. Docker starts ops-eureka container                               │
│     ┌──────────────────────────────────────────────┐                 │
│     │ EurekaServerApplication.main()                │                 │
│     │   → Spring Boot starts                        │                 │
│     │   → @EnableEurekaServer triggers               │                 │
│     │   → EurekaServerAutoConfiguration runs         │                 │
│     │   → In-memory registry initialized (empty)     │                 │
│     │   → Jersey servlets registered (/eureka/*)     │                 │
│     │   → Dashboard UI available at :8761            │                 │
│     │   → Healthcheck passes → "service_healthy"     │                 │
│     └──────────────────────────────────────────────┘                 │
│                         │                                            │
│                         ▼                                            │
│  2. Developer starts monolith from IDE                               │
│     (or future: Docker starts ops-monolith container)                │
│     ┌──────────────────────────────────────────────┐                 │
│     │ OrderProcessingSystemApplication.main()       │                 │
│     │   → Spring Boot starts                        │                 │
│     │   → @EnableDiscoveryClient triggers            │                 │
│     │   → EurekaClientAutoConfiguration runs         │                 │
│     │   → DiscoveryClient bean created               │                 │
│     │                                                │                 │
│     │   → Phase: REGISTRATION                        │                 │
│     │     POST http://localhost:8761/eureka/apps/    │                 │
│     │          ORDER-PROCESSING-SYSTEM                │                 │
│     │     Body: { host, port, status: "UP", ... }    │                 │
│     │     Server responds: 204 No Content ✅         │                 │
│     │                                                │                 │
│     │   → Phase: HEARTBEAT (every 5s in dev)         │                 │
│     │     PUT /eureka/apps/ORDER-PROCESSING-SYSTEM/  │                 │
│     │         {instanceId}                            │                 │
│     │     Server responds: 200 OK ✅                 │                 │
│     │                                                │                 │
│     │   → Phase: REGISTRY FETCH (every 5s in dev)    │                 │
│     │     GET /eureka/apps                            │                 │
│     │     Caches full registry locally                │                 │
│     └──────────────────────────────────────────────┘                 │
│                                                                      │
│  3. You open http://localhost:8761 in your browser                   │
│     ┌──────────────────────────────────────────────┐                 │
│     │ Eureka Dashboard shows:                       │                 │
│     │                                               │                 │
│     │  Application            Instances             │                 │
│     │  ──────────────────────────────────           │                 │
│     │  ORDER-PROCESSING-SYSTEM   1 (UP)             │                 │
│     └──────────────────────────────────────────────┘                 │
│                                                                      │
│  4. Developer stops monolith (Ctrl+C or IDE stop)                    │
│     ┌──────────────────────────────────────────────┐                 │
│     │ Spring Boot shutdown hook fires                │                 │
│     │   → EurekaClient.shutdown() called             │                 │
│     │   → DELETE /eureka/apps/ORDER-PROCESSING-SYSTEM│                 │
│     │     /{instanceId}                              │                 │
│     │   → Server removes instance from registry      │                 │
│     │   → Dashboard now shows 0 instances            │                 │
│     └──────────────────────────────────────────────┘                 │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 8. Eureka Internals — What Happens Under the Hood

Understanding the mechanics helps you debug issues and make informed configuration choices.

### The Registry Data Structure

Eureka's server-side registry is essentially a `ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>`:

```
registry = {
  "ORDER-PROCESSING-SYSTEM": {          ← app name (uppercased)
    "192.168.1.5:order-processing-system:8888": {  ← instance ID
      instanceInfo: {
        appName: "ORDER-PROCESSING-SYSTEM",
        hostName: "192.168.1.5",
        port: 8888,
        status: "UP",
        healthCheckUrl: "http://192.168.1.5:8888/actuator/health",
        metadata: { ... }
      },
      lastRenewalTimestamp: 1710000000000,   ← last heartbeat (epoch ms)
      registrationTimestamp: 1709999000000,  ← when registered
      evictionTimestamp: 0,                  ← 0 = not evicted
      duration: 10                           ← lease duration (seconds)
    }
  },
  "NOTIFICATION-SERVICE": {             ← another app (future)
    ...
  }
}
```

### The Three Caches

Eureka uses three levels of caching to handle high-traffic discovery requests efficiently:

```
Client request: GET /eureka/apps
         │
         ▼
┌─────────────────────┐    cache miss     ┌──────────────────────┐
│   Response Cache     │ ───────────────► │   Read-Write Cache    │
│   (read-only)        │                  │   (read-write)        │
│                      │ ◄─────────────── │                       │
│   TTL: 30s (default) │   copy on timer  │   Invalidated on      │
│   Served to clients  │                  │   register/cancel/    │
│                      │                  │   heartbeat            │
└─────────────────────┘                  └──────────┬─────────────┘
                                                     │ cache miss
                                                     ▼
                                          ┌──────────────────────┐
                                          │   Registry            │
                                          │   (source of truth)   │
                                          │                       │
                                          │   ConcurrentHashMap   │
                                          └──────────────────────┘
```

**Why this matters for you**: When you register a new service, it won't appear in other clients' discovery results instantly. The delay is approximately:

```
Registration → ReadWrite Cache invalidated (immediate)
                → ReadOnly Cache refresh (up to 30s)
                  → Client registry fetch (up to 30s)
                    = Up to ~60 seconds until other clients see the new instance
```

In dev (with our aggressive intervals), this shrinks to ~10 seconds.

### Heartbeat vs Lease Expiration

```
Timeline:
─────────────────────────────────────────────────────────
  0s     5s    10s    15s    20s    25s    30s
  │      │      │      │      │      │      │
  ♥      ♥      ♥      ✕      ✕      │      │
  │      │      │   (service  │      │      │
  │      │      │    crashed) │      │      │
  │      │      │             │      │      │
  │      │      │         lease-expiration   │
  │      │      │         = 10s from last ♥  │
  │      │      │             │              │
  │      │      │          ───┤ Eureka       │
  │      │      │             │ evicts       │
  │      │      │             │ instance     │

  ♥ = heartbeat sent by client
  ✕ = missed heartbeat
```

The server doesn't immediately know the client is gone — it waits for the lease to expire. This is why `lease-expiration-duration-in-seconds` must be greater than `lease-renewal-interval-in-seconds`.

---

## 9. Testing & Verification

### Step 1: Start the Eureka Server

```bash
# From the project root, build the eureka-server module:
cd order-processing-system
mvn clean package -pl eureka-server -am -DskipTests

# Option A: Run via Docker Compose (recommended)
docker-compose up eureka-server -d

# Option B: Run directly with Java (for quick testing)
java -jar eureka-server/target/eureka-server-0.0.1-SNAPSHOT.jar
```

### Step 2: Verify the Dashboard

Open `http://localhost:8761` in your browser. You should see:

- The **Eureka** dashboard header
- **System Status** section showing uptime and environment
- **Instances currently registered with Eureka** — empty (no services registered yet)
- No red warning banners (self-preservation is off)

### Step 3: Start the Monolith

Run `OrderProcessingSystemApplication` from your IDE as usual. Watch the console for:

```
INFO  --- [main] o.s.c.n.e.s.EurekaServiceRegistry : Registering application
        ORDER-PROCESSING-SYSTEM with eureka with status UP
INFO  --- [main] c.n.d.DiscoveryClient : DiscoveryClient_ORDER-PROCESSING-SYSTEM/
        192.168.1.5:order-processing-system:8888 - registration status: 204
```

### Step 4: Verify Registration

Refresh the Eureka dashboard. Under **Instances currently registered with Eureka**, you should now see:

| Application | AMIs | Availability Zones | Status |
|------------|------|-------------------|--------|
| ORDER-PROCESSING-SYSTEM | n/a | 1 | **UP** (1) |

### Step 5: Verify via Eureka REST API

```bash
# Get all registered applications (JSON format)
curl -s http://localhost:8761/eureka/apps \
  -H "Accept: application/json" | python3 -m json.tool

# Get specific application
curl -s http://localhost:8761/eureka/apps/ORDER-PROCESSING-SYSTEM \
  -H "Accept: application/json" | python3 -m json.tool
```

Expected response (abbreviated):

```json
{
  "application": {
    "name": "ORDER-PROCESSING-SYSTEM",
    "instance": [
      {
        "hostName": "192.168.1.5",
        "port": { "$": 8888, "@enabled": "true" },
        "status": "UP",
        "healthCheckUrl": "http://192.168.1.5:8888/actuator/health"
      }
    ]
  }
}
```

### Step 6: Verify Deregistration

Stop the monolith from your IDE. Within 10 seconds (our dev eviction interval), the dashboard should show the instance gone.

Check the Eureka server logs:

```
INFO  --- [eureka-server] c.n.e.registry.AbstractInstanceRegistry : Cancelled instance
        ORDER-PROCESSING-SYSTEM/192.168.1.5:order-processing-system:8888
```

---

## 10. Configuration Reference

### Eureka Server Properties

| Property | Default | Our Dev Value | Description |
|----------|---------|---------------|-------------|
| `eureka.client.register-with-eureka` | `true` | `false` | Register this server with another Eureka server |
| `eureka.client.fetch-registry` | `true` | `false` | Fetch registry from another Eureka server |
| `eureka.server.enable-self-preservation` | `true` | `false` | Prevent eviction during network issues |
| `eureka.server.eviction-interval-timer-in-ms` | `60000` | `5000` | How often to check for expired leases |
| `eureka.server.response-cache-update-interval-ms` | `30000` | (default) | ReadOnly cache refresh interval |

### Eureka Client Properties

| Property | Default | Our Dev Value | Description |
|----------|---------|---------------|-------------|
| `eureka.client.service-url.defaultZone` | `http://localhost:8761/eureka` | (uses default) | Eureka server endpoint |
| `eureka.client.registry-fetch-interval-seconds` | `30` | `5` | How often to refresh local registry cache |
| `eureka.instance.prefer-ip-address` | `false` | `true` | Register with IP instead of hostname |
| `eureka.instance.lease-renewal-interval-in-seconds` | `30` | `5` | Heartbeat frequency |
| `eureka.instance.lease-expiration-duration-in-seconds` | `90` | `10` | Time without heartbeat before eviction |

### Production vs Development Values

| Setting | Development | Production |
|---------|-------------|------------|
| Self-preservation | **OFF** (fast feedback) | **ON** (network resilience) |
| Heartbeat interval | 5s (fast detection) | 30s (default, less traffic) |
| Lease expiration | 10s (fast eviction) | 90s (default, tolerates hiccups) |
| Registry fetch | 5s (see changes fast) | 30s (default, less traffic) |
| Eviction check | 5s (fast cleanup) | 60s (default) |

---

## 11. Common Mistakes to Avoid

**1. Forgetting to set `register-with-eureka: false` on the server**
Without this, the standalone Eureka server tries to register with itself. It logs connection-refused errors every 30 seconds. The server still works, but the logs are noisy and confusing.

**2. Using `localhost` in Docker for Eureka URLs**
Inside Docker, `localhost` refers to the container itself — not the host machine. Use the container name (`ops-eureka`) or inject the URL via an environment variable. This is why we use `${EUREKA_URI:http://localhost:8761/eureka}` — the default works for IDE development, and Docker overrides it.

**3. Not waiting for Eureka to be healthy before starting clients**
If the monolith starts before Eureka is ready, registration fails. The client retries (Eureka clients are resilient), but you'll see scary error logs. Use `depends_on` with `condition: service_healthy` in docker-compose.

**4. Setting `lease-expiration-duration-in-seconds` ≤ `lease-renewal-interval-in-seconds`**
If the eviction timeout is shorter than the heartbeat interval, healthy instances get evicted between heartbeats. The instance registers, sends a heartbeat, waits 5 seconds to send the next one, but gets evicted after 3 seconds. This creates a register/evict/register loop.

**5. Leaving self-preservation disabled in production**
Self-preservation exists for a critical reason: during network partitions, mass eviction would make the registry empty, which is worse than stale entries. In dev, disable it for fast feedback. In prod, leave the default (`true`).

**6. Confusing the dashboard URL with the API URL**
- Dashboard: `http://localhost:8761` (human-readable web page)
- API base: `http://localhost:8761/eureka` (used in `service-url.defaultZone`)
- API apps: `http://localhost:8761/eureka/apps` (REST endpoint for registry queries)

Clients need the `/eureka` path. If you forget it, registration fails silently.

**7. Not using the Spring Cloud BOM**
If you manually set `<version>5.0.0</version>` on Eureka dependencies instead of using the BOM, you risk version mismatches between Spring Cloud modules. The BOM exists to prevent this — always use it.

---

## 12. Final Project Structure

```
order-processing-system/
├── pom.xml                                          ← PARENT POM (packaging=pom)
├── docker-compose.yml                               ← MODIFIED: + eureka-server service
├── README.md
├── LICENSE
│
├── eureka-server/                                   ← NEW MODULE
│   ├── pom.xml                                      ← Depends on eureka-server starter
│   ├── Dockerfile                                   ← Multi-stage build
│   └── src/
│       └── main/
│           ├── java/org/viators/eurekaserver/
│           │   └── EurekaServerApplication.java     ← @EnableEurekaServer
│           └── resources/
│               └── application.yaml                 ← Server config (port 8761)
│
└── ops-monolith/                                    ← MOVED (was src/ at root level)
    ├── pom.xml                                      ← MODIFIED: + eureka-client dep
    └── src/
        ├── main/
        │   ├── java/org/viators/orderprocessingsystem/
        │   │   ├── OrderProcessingSystemApplication.java  ← MODIFIED: + @EnableDiscoveryClient
        │   │   ├── auth/
        │   │   ├── common/
        │   │   ├── config/
        │   │   ├── exceptions/
        │   │   ├── messaging/
        │   │   ├── notifications/
        │   │   ├── order/
        │   │   ├── orderitem/
        │   │   ├── payment/
        │   │   ├── product/
        │   │   ├── saga/
        │   │   └── user/
        │   └── resources/
        │       ├── application.yaml                       ← MODIFIED: + eureka client config
        │       └── dev_guides/
        │           ├── RabbitMQ/
        │           ├── docker/
        │           ├── exception_handling/
        │           ├── saga_pattern/
        │           └── security/
        └── test/
```

### Summary of All Changes

| File | Action | What Changed |
|------|--------|-------------|
| `pom.xml` (root) | **REWRITTEN** | Became parent POM with `packaging=pom`, Spring Cloud BOM, module declarations |
| `ops-monolith/pom.xml` | **NEW** | Child POM with all existing monolith dependencies + Eureka client |
| `ops-monolith/src/` | **MOVED** | All existing source code moved here (no code changes) |
| `OrderProcessingSystemApplication.java` | **MODIFIED** | Added `@EnableDiscoveryClient` |
| `application.yaml` (monolith) | **MODIFIED** | Added `eureka:` client configuration block |
| `eureka-server/pom.xml` | **NEW** | Child POM with Eureka server dependency |
| `EurekaServerApplication.java` | **NEW** | `@SpringBootApplication` + `@EnableEurekaServer` |
| `application.yaml` (eureka) | **NEW** | Server config: port 8761, standalone mode |
| `eureka-server/Dockerfile` | **NEW** | Multi-stage Docker build |
| `docker-compose.yml` | **MODIFIED** | Added `eureka-server` service + `ops-network` |

---

## Acceptance Criteria Checklist

| Criterion | Status | Evidence |
|-----------|--------|----------|
| Standalone Spring Boot app `eureka-server` runs on port 8761 | ✅ | `server.port: 8761` in eureka-server's application.yaml |
| Eureka dashboard accessible at http://localhost:8761 | ✅ | `@EnableEurekaServer` activates the embedded dashboard |
| Monolith registers itself with Eureka on startup | ✅ | `@EnableDiscoveryClient` + eureka client config in application.yaml |
| eureka-server added to docker-compose.yml | ✅ | New `eureka-server` service with healthcheck |
| Monolith's application.yaml includes Eureka client configuration | ✅ | `eureka.client.service-url.defaultZone` and instance config added |
| BR-041: Services register on startup and deregister on shutdown | ✅ | Eureka client handles both automatically via Spring lifecycle hooks |
