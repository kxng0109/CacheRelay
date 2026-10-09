# syntax=docker/dockerfile:1

# FS-B06 provenance: builder image digest as pulled 2026-09-25
# (eclipse-temurin:25-jdk-noble =
# sha256:264fafc3390db78c93dc51da0109a0d66ad1fb59f7a893f12b7e3df1f15e52da).
# Tags stay floating deliberately: PRX-B12 warns that the request-timeout scope
# changes past this patch, so no JDK bump lands before the upstream-fault
# handling it requires. Re-record the digest whenever the base is refreshed.

# -----------------------------------------------------------------------------
# Stage 0: Build operator console (FE-02)
# -----------------------------------------------------------------------------
# Node base tracks the project's .nvmrc/engines (Node 24): the Vite bundle is
# built here and staged into the JAR's static dir below, so the gateway — the
# documented production host — serves the console itself. Build context is the
# repository root (see docker-compose.yml); frontend sources are build input
# only and never edited by this stage.
FROM node:24-bookworm-slim AS frontend

WORKDIR /ui

# Cache the dependency layer separately from sources
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY frontend/ ./
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 1: Build Application
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace

# Cache Maven dependencies layer
COPY backend/.mvn/ .mvn/
COPY backend/mvnw backend/pom.xml ./
RUN ./mvnw dependency:go-offline -B -ntp

# Build executable JAR without tests
COPY backend/src/ src/
# FE-02: stage the console bundle into the JAR's static dir BEFORE packaging
# so the image serves the shell at / with immutable /assets/**.
COPY --from=frontend /ui/dist/ src/main/resources/static/
RUN ./mvnw clean package -DskipTests -B -ntp

# -----------------------------------------------------------------------------
# Stage 2: Hardened Production Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS runtime

LABEL maintainer="CacheRelay Team" \
      description="Enterprise AI Gateway and Resilient Multi-Provider Reverse Proxy" \
      org.opencontainers.image.source="https://github.com/kxng0109/CacheRelay"

# Install curl for health checking and update certificates. OpenSSL and libssl are
# installed by name (not merely as transitive deps) so a rebuild always carries
# the latest noble-updates patch: apt-get install upgrades named packages while
# leaving pre-installed base layers untouched (CVE-2026-84782 stayed at
# 3.0.13-0ubuntu3.15 until named explicitly).
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates openssl libssl3t64 && \
    rm -rf /var/lib/apt/lists/*

# Create unprivileged system user and group
RUN groupadd --system --gid 10001 cacherelay && \
    useradd --system --uid 10001 --gid cacherelay --no-create-home --shell /usr/sbin/nologin cacherelay

WORKDIR /app

# Copy application artifact and model artifacts with non-root ownership
COPY --from=builder --chown=cacherelay:cacherelay /workspace/target/CacheRelay-*.jar /app/cacherelay.jar
COPY --chown=cacherelay:cacherelay backend/models/ /app/models/

USER cacherelay:cacherelay

EXPOSE 8080 9091

HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD curl -fsS http://localhost:9091/actuator/health || exit 1

# 2 vCPU / 2 GB cgroup profile (PERF-15): heap 1152m + direct 256m + ~200m
# metaspace/native stays under the limit with headroom (cgroup kills are NOT
# covered by ExitOnOutOfMemoryError — see JDK-8257790). Thread counts are left
# to JVM container ergonomics: HotSpot derives ParallelGCThreads,
# ConcGCThreads, and the virtual-thread scheduler parallelism from the cgroup
# CPU quota (verified: unpinned defaults compute exactly 2/1 at --cpus=2 and
# scale to 8/2 at --cpus=8), so fixed pins would only cap bigger iron.
# ZUncommitDelay stays explicit (not ergonomic).
ENTRYPOINT ["java", \
  "-XX:+UseCompactObjectHeaders", \
  "-XX:+UseZGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Xmx1152m", \
  "-XX:SoftMaxHeapSize=768m", \
  "-XX:MaxDirectMemorySize=256m", \
  "-XX:ZUncommitDelay=60", \
  "-jar", "/app/cacherelay.jar"]
