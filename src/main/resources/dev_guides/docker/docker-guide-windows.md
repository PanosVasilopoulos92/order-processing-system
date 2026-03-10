# Docker for Spring Boot Development Guide

## For the Order Processing System — Windows 11 / Spring Boot 4 / Java 25

---

## Table of Contents

1. [Understanding the Big Picture](#1-understanding-the-big-picture)
2. [How Docker Works (The Mental Model)](#2-how-docker-works-the-mental-model)
3. [Installation on Windows 11](#3-installation-on-windows-11)
   - 3.1 [Enable WSL 2 (Windows Subsystem for Linux)](#31-enable-wsl-2-windows-subsystem-for-linux)
   - 3.2 [Install Docker Desktop](#32-install-docker-desktop)
   - 3.3 [Verify the Installation](#33-verify-the-installation)
4. [Core Docker Concepts You Need](#4-core-docker-concepts-you-need)
   - 4.1 [Images vs Containers](#41-images-vs-containers)
   - 4.2 [Volumes — Persistent Data](#42-volumes--persistent-data)
   - 4.3 [Networks — Container Communication](#43-networks--container-communication)
   - 4.4 [Ports — Reaching Containers from Your Machine](#44-ports--reaching-containers-from-your-machine)
5. [docker-compose — Orchestrating Multiple Containers](#5-docker-compose--orchestrating-multiple-containers)
   - 5.1 [Why docker-compose Exists](#51-why-docker-compose-exists)
   - 5.2 [The docker-compose.yml File — Anatomy](#52-the-docker-composeyml-file--anatomy)
6. [Your Project's docker-compose.yml](#6-your-projects-docker-composeyml)
   - 6.1 [MySQL Service](#61-mysql-service)
   - 6.2 [RabbitMQ Service](#62-rabbitmq-service)
   - 6.3 [MailHog Service (Optional)](#63-mailhog-service-optional)
   - 6.4 [The Complete File](#64-the-complete-file)
7. [Environment Variables — Never Hardcode Secrets](#7-environment-variables--never-hardcode-secrets)
   - 7.1 [The .env File](#71-the-env-file)
   - 7.2 [The .gitignore Rule](#72-the-gitignore-rule)
8. [Running docker-compose up -d](#8-running-docker-compose-up--d)
   - 8.1 [The Workflow](#81-the-workflow)
   - 8.2 [Essential Commands](#82-essential-commands)
9. [Connecting Your Spring Boot App](#9-connecting-your-spring-boot-app)
10. [How Everything Connects: Startup Lifecycle](#10-how-everything-connects-startup-lifecycle)
11. [Troubleshooting Common Issues](#11-troubleshooting-common-issues)
12. [Docker Cheat Sheet](#12-docker-cheat-sheet)

---

## 1. Understanding the Big Picture

Before installing anything, you need to understand **what problem Docker solves** and **why it's the standard tool for running infrastructure locally**.

### The Problem: "Works on My Machine"

Imagine you're building your Order Processing System. You need:
- **MySQL 8** running on port 3306
- **RabbitMQ** running on ports 5672 and 15672

Without Docker, you'd have to install these programs directly on your Windows machine. This creates real problems:

- **Installation complexity** — MySQL and RabbitMQ each have their own installer, configuration files, and system services. Getting them to run cleanly on Windows takes effort.
- **Version conflicts** — What if tomorrow's project needs MySQL 5.7 but today's needs MySQL 8? You'd have two MySQL installations fighting over the same ports.
- **Polluted machine** — Every tool you install adds services, registry entries, and background processes to your OS. Your development machine slowly becomes a mess.
- **Inconsistency across teams** — Your teammate installs the same tools but on a Mac, with slightly different default configs. Subtle bugs appear that only happen "on their machine."

### The Solution: Isolated, Reproducible Environments

Docker lets you run **any software in a container** — an isolated, lightweight process that has everything it needs to run, without interfering with anything else on your machine.

Think of a shipping container analogy: a physical shipping container holds cargo and can be loaded onto any ship, truck, or train regardless of what's inside. The shipping infrastructure doesn't care about the contents, and the contents don't care about the transport method. Docker containers work exactly the same way — a MySQL container holds MySQL and runs identically on your Windows laptop, your teammate's Mac, and your production Linux server.

### What Docker Does for Your Spring Boot Project

For your Order Processing System specifically, Docker manages the **infrastructure your app depends on** — MySQL and RabbitMQ. Your Spring Boot app itself still runs normally in IntelliJ (directly on the JVM). Docker handles the services your app talks to.

```
┌─────────────────────────────────────────────────────────────────┐
│                        Your Windows 11                          │
│                                                                 │
│  ┌────────────────────────┐    ┌────────────────────────────┐   │
│  │  IntelliJ IDEA         │    │  Docker Desktop            │   │
│  │                        │    │                            │   │
│  │  Spring Boot App       │    │  ┌──────────────────────┐  │   │
│  │  (runs on JVM,         │───►│  │  MySQL Container     │  │   │
│  │   not in Docker)       │    │  │  port 3306           │  │   │
│  │                        │    │  └──────────────────────┘  │   │
│  │                        │───►│  ┌──────────────────────┐  │   │
│  │                        │    │  │  RabbitMQ Container  │  │   │
│  │                        │    │  │  ports 5672, 15672   │  │   │
│  └────────────────────────┘    │  └──────────────────────┘  │   │
│                                └────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

Your Spring Boot app connects to `localhost:3306` for MySQL and `localhost:5672` for RabbitMQ, just as if those services were installed natively.

---

## 2. How Docker Works (The Mental Model)

Before diving into installation, understand the three core concepts in Docker's model.

### The Lifecycle

```
Dockerfile / DockerHub
        │
        │  docker pull (download image)
        ▼
    Docker Image
  (blueprint, read-only)
        │
        │  docker run (create & start)
        ▼
  Docker Container
  (running instance)
        │
        │  docker stop (pause it)
        │  docker start (resume it)
        │  docker rm (delete it)
        ▼
     (gone)
```

**Image** = A blueprint. A frozen snapshot of a filesystem and startup instructions. You don't modify images — you run containers from them. The `mysql:8` image from DockerHub contains everything needed to run MySQL 8.

**Container** = A running instance of an image. You can have 3 containers all running from the same `mysql:8` image, each with completely separate data. Containers are ephemeral by default — stopping and removing a container deletes its data unless you use volumes.

**DockerHub** = A registry (like Maven Central but for Docker images). When you specify `image: mysql:8` in a compose file, Docker pulls it from DockerHub automatically on first use.

### Why This Matters for Your Project

You will never write a `Dockerfile` yourself for this project — the official images for MySQL and RabbitMQ already exist on DockerHub. You just tell Docker which image to use, which ports to expose, and what environment variables to set. That's the entire job of your `docker-compose.yml`.

---

## 3. Installation on Windows 11

Docker on Windows has an important prerequisite: **WSL 2** (Windows Subsystem for Linux). Understanding why it's needed will save you debugging time later.

### Why WSL 2?

Docker is fundamentally a Linux technology. It uses Linux kernel features (namespaces, cgroups) to create isolated containers. On Windows, Docker Desktop uses WSL 2 as a lightweight Linux VM to run the Docker engine. Without WSL 2, Docker either doesn't work at all or falls back to a slower, older virtualization approach (Hyper-V).

WSL 2 gives you near-native Linux performance while staying on Windows.

### 3.1 Enable WSL 2 (Windows Subsystem for Linux)

**Step 1**: Open **PowerShell as Administrator** (right-click the Start menu → "Windows PowerShell (Admin)" or "Terminal (Admin)").

**Step 2**: Run the following command:
```powershell
wsl --install
```

This single command:
- Enables the WSL Windows feature
- Enables the Virtual Machine Platform feature
- Downloads and installs the WSL 2 Linux kernel
- Installs Ubuntu as the default Linux distribution

**Step 3**: **Restart your computer.** WSL 2 requires a reboot to activate the kernel changes.

**Step 4**: After reboot, Ubuntu will launch automatically and ask you to create a Linux username and password. This is your WSL Linux user — it doesn't need to match your Windows username. Set it to anything simple (e.g., `dev` / `dev`).

**Step 5**: Verify WSL 2 is active:
```powershell
wsl --list --verbose
```
You should see `Ubuntu` with `VERSION 2`. If it shows `VERSION 1`, upgrade it:
```powershell
wsl --set-version Ubuntu 2
```

> **Troubleshooting**: If `wsl --install` says "A required feature is not installed", go to **Settings → System → Optional Features → More Windows Features** and manually enable "Virtual Machine Platform" and "Windows Subsystem for Linux", then reboot.

---

### 3.2 Install Docker Desktop

**Step 1**: Download Docker Desktop for Windows from the official site:
```
https://www.docker.com/products/docker-desktop/
```
Download the **"Docker Desktop for Windows — AMD64"** installer (unless you have an ARM-based PC, which is rare for Windows 11).

**Step 2**: Run the installer. On the configuration screen:
- ✅ **Use WSL 2 instead of Hyper-V** — keep this checked
- ✅ **Add shortcut to desktop** — optional

**Step 3**: When the installer finishes, it will ask you to log out and log back in (or restart). Do it.

**Step 4**: After logging back in, Docker Desktop launches automatically. On first launch it shows a tutorial — you can skip it.

**Step 5**: Docker Desktop will ask you to sign in or create a Docker account. This is **optional** for local development. You can click "Continue without signing in" or "Skip".

---

### 3.3 Verify the Installation

Open a new terminal (PowerShell, Command Prompt, or Windows Terminal) and run:

```bash
docker --version
```
Expected output:
```
Docker version 27.x.x, build xxxxxxx
```

```bash
docker compose version
```
Expected output:
```
Docker Compose version v2.x.x
```

> **Important note on the command**: The modern Docker Compose is `docker compose` (with a space, as a Docker plugin). The older standalone tool was `docker-compose` (with a hyphen). Both work on modern Docker Desktop — you'll see `docker-compose up -d` in many guides including your RabbitMQ guide, and it still works as an alias. This guide uses `docker compose` as the canonical form.

Run a quick smoke test:
```bash
docker run hello-world
```
You should see:
```
Hello from Docker!
This message shows that your installation appears to be working correctly.
```

If this works, Docker is fully operational.

---

## 4. Core Docker Concepts You Need

### 4.1 Images vs Containers

The most common source of confusion for beginners. A simple analogy:

- **Image** = A Java `.class` file. It's the compiled, immutable blueprint.
- **Container** = A running JVM process executing that class. You can start many instances from the same `.class` file.

You can't "edit" a running container's image. If you want a different configuration, you change the `docker-compose.yml` and recreate the container.

### 4.2 Volumes — Persistent Data

By default, when you delete a container, **all its data is gone**. For a database, this would mean losing all your rows every time you restart. Volumes solve this.

A **volume** is a directory managed by Docker that is mounted into a container. The data lives on your host machine (Windows), not inside the container. When the container is destroyed and recreated, Docker mounts the same volume, and the data is still there.

```
Your Windows Filesystem (host)          Docker Container
┌─────────────────────────┐             ┌──────────────────────┐
│  Docker Volumes         │             │  MySQL               │
│  (managed by Docker,    │             │                      │
│   stored somewhere in   │◄───────────►│  /var/lib/mysql      │
│   Docker's storage)     │  mounted    │  (MySQL data dir)    │
│                         │             │                      │
└─────────────────────────┘             └──────────────────────┘
```

In your `docker-compose.yml`:
```yaml
volumes:
  - mysql_data:/var/lib/mysql   # named volume : container path
```

The `mysql_data` is the volume name (Docker manages where it lives on disk). `/var/lib/mysql` is where MySQL stores its data inside the container. Docker links them.

### 4.3 Networks — Container Communication

When you run multiple containers with `docker compose`, Docker automatically creates a **private network** for them. Containers on the same network can talk to each other using their **service name** as a hostname.

For example, if your Spring Boot app was running in a container (it's not in your setup, but hypothetically), it could connect to MySQL using `jdbc:mysql://mysql:3306/ops_db` — where `mysql` is the service name in your compose file, not `localhost`.

Since your Spring Boot app runs on your host machine (not in Docker), it connects to `localhost:3306`. We'll cover this in Section 9.

### 4.4 Ports — Reaching Containers from Your Machine

Containers are isolated. By default, a port inside a container is not accessible from your machine. You need to **publish** the port to make it accessible.

The syntax is `host_port:container_port`:
```yaml
ports:
  - "3306:3306"   # your machine's port 3306 → container's port 3306
  - "8080:3306"   # your machine's port 8080 → container's port 3306 (different host port)
```

The left side is **your machine's port** (what you connect to from IntelliJ or your browser). The right side is **inside the container** (the port the service listens on internally). For development, you'll almost always use the same number on both sides.

---

## 5. docker-compose — Orchestrating Multiple Containers

### 5.1 Why docker-compose Exists

Running a single container via `docker run` is straightforward. But your project needs MySQL AND RabbitMQ running together, with the right configuration, in the right order. Managing this manually becomes complex:

```bash
# Without compose — you'd run these manually every time:
docker run -d --name ops-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=secret -v mysql_data:/var/lib/mysql mysql:8
docker run -d --name ops-rabbitmq -p 5672:5672 -p 15672:15672 -e RABBITMQ_DEFAULT_USER=admin -e RABBITMQ_DEFAULT_PASS=secret -v rabbitmq_data:/var/lib/rabbitmq rabbitmq:3-management
```

`docker-compose.yml` is a **declarative YAML file** that describes your entire environment. Instead of remembering long `docker run` commands, you declare what you want, and Docker Compose makes it happen with one command.

### 5.2 The docker-compose.yml File — Anatomy

Every `docker-compose.yml` shares this top-level structure:

```yaml
services:              # The containers you want to run
  service-name:        # A name you choose (used as hostname between containers)
    image: ...         # Which Docker image to use
    container_name:... # A friendly name for the running container
    ports: ...         # Port mappings: host:container
    environment: ...   # Environment variables passed into the container
    volumes: ...       # Persistent storage mappings
    healthcheck: ...   # How to know if the container is truly ready

volumes:               # Named volumes declaration (Docker manages these)
  volume-name:         # Declare each named volume used above
```

**Why declare volumes at the bottom?** Docker requires named volumes to be declared explicitly at the top level before they can be referenced by services. Think of it like Java — you need to declare a variable before using it.

---

## 6. Your Project's docker-compose.yml

This is the `docker-compose.yml` for the Order Processing System. Place it in the **root of your project** (the same level as `pom.xml`).

### 6.1 MySQL Service

```yaml
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
    test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
```

**Key decisions explained**:

- **`image: mysql:8`** — Uses the official MySQL 8 image from DockerHub. Pinning to `8` (not `latest`) ensures you always get MySQL 8.x, which matches your `application.yaml` config. Never use `latest` in a real project — it's a silent breaking change waiting to happen.

- **`MYSQL_ROOT_PASSWORD`** — The root admin password. Required by the MySQL image on first start.

- **`MYSQL_DATABASE`** — The MySQL image will **automatically create this database** on first startup. This means you don't need to manually log in and run `CREATE DATABASE ops_db;`.

- **`MYSQL_USER` / `MYSQL_PASSWORD`** — Creates an application-level user with access to the database above. Your Spring Boot app connects as this user, not as root. Least privilege principle.

- **`${MYSQL_ROOT_PASSWORD:?error}`** — The `:?error` syntax means: "if this environment variable is not set, fail immediately with an error." This is a safety net — it prevents MySQL from starting with a blank root password because you forgot to set the variable. We'll configure these variables in the `.env` file in Section 7.

- **`healthcheck`** — Periodically pings MySQL to verify it's truly ready to accept connections. This matters because MySQL takes 10-30 seconds to initialize on first run. The healthcheck lets other services (or your startup scripts) wait for MySQL to be genuinely ready, not just "started".

### 6.2 RabbitMQ Service

```yaml
rabbitmq:
  image: rabbitmq:3-management
  container_name: ops-rabbitmq
  ports:
    - "5672:5672"      # AMQP protocol — your Spring app connects here
    - "15672:15672"    # Management UI — browse here in your browser
  environment:
    RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:?error}
    RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?error}
  volumes:
    - rabbitmq_data:/var/lib/rabbitmq
  healthcheck:
    test: ["CMD", "rabbitmq-diagnostics", "ping"]
    interval: 10s
    timeout: 5s
    retries: 5
    start_period: 30s
```

**Key decisions explained**:

- **`image: rabbitmq:3-management`** — The `-management` suffix adds RabbitMQ's browser-based management console. Without it, you'd get a plain RabbitMQ with no UI. The management console at `http://localhost:15672` lets you visually inspect exchanges, queues, and message flows during development — essential for debugging.

- **Two ports** — `5672` is the AMQP protocol port (what your Spring app uses to send and receive messages). `15672` is the management UI (what your browser uses). Both need to be published.

- **`volumes: rabbitmq_data:/var/lib/rabbitmq`** — Persists RabbitMQ's data between restarts. Without this, every time you restart the container, all your exchanges and queue bindings would disappear. Spring Boot recreates them on startup (via `RabbitMQConfig`), but it's still cleaner to persist them.

### 6.3 MailHog Service (Optional)

If you're following the email notifications path in the RabbitMQ guide, add this:

```yaml
mailhog:
  image: mailhog/mailhog
  container_name: ops-mailhog
  ports:
    - "1025:1025"    # SMTP — your app sends emails here
    - "8025:8025"    # Web UI — view caught emails in your browser
```

MailHog is a fake SMTP server for development. It catches all outgoing emails and displays them at `http://localhost:8025`. No emails are actually sent — they're just displayed in a web UI. This is invaluable when testing email notifications without risk of spamming real inboxes.

### 6.4 The Complete File

Create `docker-compose.yml` at your project root:

```yaml
# docker-compose.yml
# Place this at the root of the project, next to pom.xml

services:

  # ── MySQL 8 ────────────────────────────────────────────────────
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
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${MYSQL_ROOT_PASSWORD}"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  # ── RabbitMQ 3 with Management UI ──────────────────────────────
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
      test: ["CMD", "rabbitmq-diagnostics", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  # ── MailHog (optional — development email testing) ─────────────
  mailhog:
    image: mailhog/mailhog
    container_name: ops-mailhog
    ports:
      - "1025:1025"
      - "8025:8025"

# ── Named Volumes ─────────────────────────────────────────────────
# Declared here so Docker manages their lifecycle independently
# of the containers. Data survives container recreation.
volumes:
  mysql_data:
  rabbitmq_data:
```

---

## 7. Environment Variables — Never Hardcode Secrets

### 7.1 The .env File

Docker Compose automatically reads a file named `.env` in the same directory as your `docker-compose.yml`. Variables defined there are substituted into the compose file wherever you use `${VARIABLE_NAME}`.

Create `.env` at your project root (same level as `docker-compose.yml`):

```dotenv
# .env — Local development secrets
# NEVER commit this file to Git (see .gitignore section)

# ── MySQL ─────────────────────────────────────────────────────────
MYSQL_ROOT_PASSWORD=root_dev_password_123
MYSQL_DATABASE=ops_db
MYSQL_USER=ops_user
MYSQL_PASSWORD=ops_dev_password_123

# ── RabbitMQ ──────────────────────────────────────────────────────
RABBITMQ_USER=ops_admin
RABBITMQ_PASSWORD=rabbitmq_dev_password_123
```

> **Note on password strength**: These are for local development only. Use something you can remember easily. For any real environment (staging, production), generate strong random passwords with a password manager or secrets vault.

**How the substitution works**: When you run `docker compose up`, Docker reads `.env` first, then processes `docker-compose.yml`. Every `${MYSQL_ROOT_PASSWORD}` in the compose file is replaced with the value from `.env`. The container receives the actual value — it never sees the `${}` syntax.

### 7.2 The .gitignore Rule

The `.env` file contains passwords. It must **never** be committed to Git.

Open (or create) `.gitignore` at your project root and add:

```gitignore
# Environment variables — contains secrets, never commit
.env

# Keep the example file so teammates know what variables are needed
# (create this file manually — it has placeholders, not real values)
!.env.example
```

Create a companion `.env.example` file — this IS committed to Git. It shows your teammates which variables they need to set, without revealing the actual values:

```dotenv
# .env.example — Copy this to .env and fill in your values
# This file IS committed to Git (no real secrets here)

MYSQL_ROOT_PASSWORD=
MYSQL_DATABASE=ops_db
MYSQL_USER=ops_user
MYSQL_PASSWORD=

RABBITMQ_USER=
RABBITMQ_PASSWORD=
```

The pattern is: `.env` = real secrets (gitignored), `.env.example` = documentation template (committed). This is the industry-standard approach for managing local environment secrets.

---

## 8. Running docker-compose up -d

### 8.1 The Workflow

**Step 1**: Open a terminal at your project root.

In IntelliJ: the built-in terminal (View → Tool Windows → Terminal) opens directly at the project root. Alternatively, use Windows Terminal or PowerShell and `cd` to your project directory.

**Step 2**: Verify your file structure is correct:
```
your-project/
├── docker-compose.yml    ✅
├── .env                  ✅
├── .env.example          ✅
├── .gitignore            ✅
└── pom.xml
```

**Step 3**: Run the command:
```bash
docker compose up -d
```

**What happens when you run this**:
```
docker compose up -d
│
├── Reads docker-compose.yml
├── Reads .env (substitutes variables)
├── For each service:
│   ├── Checks if the image is already downloaded
│   ├── If not → pulls from DockerHub (first time only, takes a minute)
│   ├── Creates the named volumes if they don't exist
│   ├── Creates the container with the specified config
│   └── Starts the container
└── Returns control to your terminal immediately (-d = detached mode)
```

**What `-d` means**: "detached mode." Without it, the containers start but their logs stream to your terminal, and pressing Ctrl+C stops them. With `-d`, the containers start in the background and your terminal is free. This is what you want for development.

**First-run output** (example):
```
[+] Running 3/3
 ✔ Volume "ops_mysql_data"     Created
 ✔ Volume "ops_rabbitmq_data"  Created
 ✔ Container ops-mysql         Started
 ✔ Container ops-rabbitmq      Started
 ✔ Container ops-mailhog       Started
```

On subsequent runs (images already downloaded):
```
[+] Running 2/2
 ✔ Container ops-mysql     Running
 ✔ Container ops-rabbitmq  Running
```

### 8.2 Essential Commands

These are the commands you'll use daily. Understanding what each does prevents anxiety when something goes wrong.

**Check container status:**
```bash
docker compose ps
```
Output:
```
NAME            IMAGE                  STATUS          PORTS
ops-mysql       mysql:8                Up (healthy)    0.0.0.0:3306->3306/tcp
ops-rabbitmq    rabbitmq:3-management  Up (healthy)    0.0.0.0:5672->5672/tcp, 0.0.0.0:15672->15672/tcp
ops-mailhog     mailhog/mailhog        Up              0.0.0.0:1025->1025/tcp, 0.0.0.0:8025->8025/tcp
```
Watch for `(healthy)` in the STATUS column — this means the healthcheck passed and the service is genuinely ready.

**View logs:**
```bash
docker compose logs mysql           # logs for the mysql service
docker compose logs -f rabbitmq     # follow (stream) rabbitmq logs live
docker compose logs                 # all services
```

**Stop containers (preserves data):**
```bash
docker compose stop
```
Containers are stopped but not deleted. Their data volumes remain. Use this when you're done for the day.

**Start stopped containers:**
```bash
docker compose start
```
Resumes previously stopped containers. Faster than `up` because images are already downloaded and containers already configured.

**Stop and remove containers (preserves volumes):**
```bash
docker compose down
```
Stops and deletes the containers, but **volumes are preserved**. Your database data is safe. Use this when you want a clean slate but want to keep your data.

**Stop, remove containers, AND delete volumes:**
```bash
docker compose down -v
```
⚠️ This deletes everything including your database data. Use this when you want a completely fresh start (e.g., database schema changed dramatically and you want to reinitialize from scratch).

**Restart a single service:**
```bash
docker compose restart mysql
```

**Pull latest image versions:**
```bash
docker compose pull
```
Updates images to the latest version matching your tag (e.g., `mysql:8` → latest MySQL 8.x). Only do this intentionally, not accidentally.

---

## 9. Connecting Your Spring Boot App

With your containers running, your Spring Boot app needs to know how to connect to them. The connection details live in `application.yaml`.

### MySQL Connection

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/${MYSQL_DATABASE}?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
    username: ${MYSQL_USER}
    password: ${MYSQL_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
        format_sql: true
    show-sql: false
```

**Why `localhost` and not the container name?** Your Spring Boot app runs on your host machine (not inside Docker). From your machine, the MySQL container is reachable at `localhost:3306` because you published the port with `"3306:3306"` in the compose file. The container's internal hostname (`mysql`) is only meaningful to other containers on the same Docker network.

**The `?useSSL=false&allowPublicKeyRetrieval=true` parameters**: MySQL 8 enables SSL by default. For local development, SSL adds complexity with no benefit. These parameters disable SSL and allow the driver to retrieve the server's public key for password authentication without requiring a pre-established SSL connection.

### Set Environment Variables in IntelliJ

Your `application.yaml` uses `${MYSQL_DATABASE}`, `${MYSQL_USER}`, etc. Just like the JWT secret key, these must be set as environment variables in your run configuration.

In IntelliJ:
1. Open **Run → Edit Configurations**
2. Select your Spring Boot run configuration
3. Find **Environment Variables** and click the edit icon
4. Add the same variables from your `.env` file:

```
MYSQL_DATABASE=ops_db
MYSQL_USER=ops_user
MYSQL_PASSWORD=ops_dev_password_123
RABBITMQ_USER=ops_admin
RABBITMQ_PASSWORD=rabbitmq_dev_password_123
JWT_SECRET_KEY=<your existing key>
```

> **Tip**: You can also install the **EnvFile plugin** for IntelliJ, which lets you point your run configuration directly at your `.env` file instead of copy-pasting variables. Search for "EnvFile" in the IntelliJ plugin marketplace.

### RabbitMQ Connection

From your RabbitMQ guide, your `application.yaml` already has:

```yaml
spring:
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USER}
    password: ${RABBITMQ_PASSWORD}
```

The `${RABBITMQ_HOST:localhost}` syntax means: "use the `RABBITMQ_HOST` environment variable if set, otherwise default to `localhost`." For local development with Docker, you don't need to set `RABBITMQ_HOST` at all — it defaults to `localhost`, which is correct.

---

## 10. How Everything Connects: Startup Lifecycle

Here's the complete picture of what happens from `docker compose up -d` to a running Spring Boot app:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Terminal: docker compose up -d                                     │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Docker reads docker-compose.yml + .env                             │
│  Substitutes ${MYSQL_ROOT_PASSWORD} → "root_dev_password_123"       │
│  Substitutes ${MYSQL_DATABASE} → "ops_db"   ... etc.               │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Docker starts containers                                           │
│                                                                     │
│  ops-mysql    → STATUS: starting → initializing → (healthy)        │
│  ops-rabbitmq → STATUS: starting → initializing → (healthy)        │
│  ops-mailhog  → STATUS: starting → Up                              │
│                                                                     │
│  First run only: MySQL creates "ops_db" database and "ops_user"     │
└─────────────────────────┬───────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────────┐
│  IntelliJ: Run Spring Boot App                                      │
│                                                                     │
│  Spring Boot reads application.yaml                                 │
│  Connects to MySQL at localhost:3306  ✅                            │
│  Connects to RabbitMQ at localhost:5672  ✅                         │
│  Hibernate runs schema initialization                               │
│  RabbitMQConfig creates exchange, queues, bindings  ✅              │
│                                                                     │
│  Application started on port 8080  ✅                               │
└─────────────────────────────────────────────────────────────────────┘
```

**Development day-to-day workflow**:
```
Morning:
  docker compose start       (resume containers from yesterday)
  [Run Spring Boot in IntelliJ]

Evening:
  [Stop Spring Boot in IntelliJ]
  docker compose stop        (pause containers, data preserved)
```

---

## 11. Troubleshooting Common Issues

### Port Already in Use

**Error**:
```
Error response from daemon: Ports are not available: exposing port TCP 0.0.0.0:3306 -> 0.0.0.0:0: listen tcp 0.0.0.0:3306: bind: address already in use
```

**Cause**: Something else on your machine is already using port 3306 (likely a native MySQL installation).

**Solution**:

Option A — Stop the conflicting service:
```powershell
# Find what's using port 3306:
netstat -ano | findstr :3306
# Note the PID, then stop it in Task Manager or:
taskkill /PID <pid> /F
```

Option B — Change the host port in `docker-compose.yml` (the left side):
```yaml
ports:
  - "3307:3306"   # Your machine uses 3307, container still uses 3306 internally
```
Then update your `application.yaml`:
```yaml
url: jdbc:mysql://localhost:3307/${MYSQL_DATABASE}...
```

---

### Variable Not Set Error

**Error**:
```
variable is not set. Defaulting to a blank string.
```
or
```
ops-mysql  | [ERROR] --initialize specified but the data directory has files in it.
```

**Cause**: A variable in `.env` is blank or the `.env` file is missing.

**Solution**: Check that your `.env` file exists at the project root and all variables have values. Run `docker compose config` to see the compose file with all variables substituted — blank values will be obvious.

---

### Container Starts But App Can't Connect

**Error in Spring Boot**:
```
Communications link failure: The last packet sent successfully to the server was 0 milliseconds ago.
```

**Cause**: MySQL container is still initializing when Spring Boot tries to connect. MySQL takes 15-30 seconds on first run.

**Solution**: Wait for `docker compose ps` to show `(healthy)` before starting the Spring Boot app. The healthcheck confirms MySQL is ready to accept connections.

---

### Wrong Username or Password

**Error**:
```
Access denied for user 'ops_user'@'172.17.0.1' (using password: YES)
```

**Cause**: The credentials in `application.yaml` don't match what the container was created with.

**Solution**: Check that the environment variables in your IntelliJ run configuration match the values in `.env`. If you changed `.env` after the container was already created, the change won't take effect — MySQL baked the credentials in at first startup. You need to delete the volume and recreate:
```bash
docker compose down -v    # deletes containers AND volumes
docker compose up -d      # recreates everything fresh
```
⚠️ This deletes all your database data.

---

### Forgot to Start Docker Desktop

**Error**:
```
error during connect: Get "http://%2F%2F.%2Fpipe%2FdockerDesktopLinuxEngine/v1.47/...": 
open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
```

**Cause**: Docker Desktop isn't running.

**Solution**: Start Docker Desktop from the Start Menu. Wait for the whale icon in the system tray to stop animating (it animates while starting up). Then retry the command.

---

## 12. Docker Cheat Sheet

| Command | What it does |
|---|---|
| `docker compose up -d` | Start all services in the background |
| `docker compose down` | Stop and remove containers (keeps volumes) |
| `docker compose down -v` | Stop, remove containers AND volumes (⚠️ deletes data) |
| `docker compose start` | Resume stopped containers |
| `docker compose stop` | Pause running containers |
| `docker compose restart` | Stop and start all containers |
| `docker compose ps` | Show status of all containers |
| `docker compose logs <service>` | View logs for a service |
| `docker compose logs -f <service>` | Stream (follow) logs live |
| `docker compose pull` | Download latest versions of images |
| `docker compose config` | Show the resolved config (variables substituted) |
| `docker ps` | Show all running containers (all projects) |
| `docker ps -a` | Show all containers including stopped ones |
| `docker images` | List all downloaded images |
| `docker volume ls` | List all volumes |
| `docker volume rm <name>` | Delete a specific volume |

### Development UI Shortcuts

| URL | What's there |
|---|---|
| `http://localhost:15672` | RabbitMQ Management Console (login with your RABBITMQ_USER/PASS) |
| `http://localhost:8025` | MailHog UI — view caught emails |

### File Placement Summary

```
your-project/
├── docker-compose.yml   ← Container definitions
├── .env                 ← Real secrets (gitignored)
├── .env.example         ← Template (committed to Git)
├── .gitignore           ← Must include .env
└── pom.xml
```
