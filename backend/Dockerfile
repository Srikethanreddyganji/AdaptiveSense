FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

ENV MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC"

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

# Restrict glibc memory arenas to prevent off-heap memory bloat in containerized multi-core environments
ENV MALLOC_ARENA_MAX=2

COPY --from=build /app/target/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -XX:+UseSerialGC -Xmx160m -Xms64m -XX:MaxMetaspaceSize=80m -XX:CompressedClassSpaceSize=24m -XX:ReservedCodeCacheSize=24m -Xss256k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -Dserver.port=${PORT:-8080} -jar app.jar"]