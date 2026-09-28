FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

ENV MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC"

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/app.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -XX:+UseSerialGC -Xmx256m -Xms128m -XX:MaxMetaspaceSize=96m -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=32m -Xss512k -Dserver.port=${PORT:-8080} -jar app.jar"]