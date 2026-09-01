# --- Build stage -----------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies separately from source so code edits don't re-download the world
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# --- Run stage ---------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

RUN useradd --create-home --shell /bin/false appuser \
    && chown appuser:appuser app.jar
USER appuser

EXPOSE 8080

# Frontend is bundled into the jar's static resources, so this single
# container serves both the API and the web UI.
ENTRYPOINT ["java", "-jar", "app.jar"]
