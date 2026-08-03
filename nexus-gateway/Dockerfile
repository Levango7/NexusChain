# ConPay Gateway - Multi-stage production Dockerfile
# NexusChain Gateway 基于 Spring Boot 3.2.5，要求 Java 17（toolchain 锁定 17）。
# 构建期与运行期必须均为 JDK/JRE 17，使用 JDK8 会导致 bootJar 无法运行。
FROM gradle:7.6.1-jdk17 AS builder
WORKDIR /app
COPY build.gradle settings.gradle ./
COPY src ./src
RUN gradle bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-jammy
RUN addgroup --system conpay && adduser --system --ingroup conpay conpay
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
RUN mkdir -p /var/log/conpay && chown conpay:conpay /var/log/conpay
USER conpay
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=10s --retries=3 --start-period=40s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]