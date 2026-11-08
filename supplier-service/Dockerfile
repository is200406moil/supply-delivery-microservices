# syntax=docker/dockerfile:1
# --- сборка (кэш Maven общий для всех сервисов) ---
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# --- запуск ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /src/target/app.jar app.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Dfile.encoding=UTF-8"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
