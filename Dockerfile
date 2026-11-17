# 构建阶段：Maven + JDK 21 打包 Spring Boot 可执行 jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY config ./config
COPY src ./src
RUN mvn -B -ntp -DskipTests package

# 运行阶段：JRE 21，以数字非 root UID 运行；TLS 由平台网关终止，容器监听 HTTP 8080
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/helpdesk.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
