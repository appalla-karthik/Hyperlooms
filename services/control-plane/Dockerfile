FROM gradle:9.2.1-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --create-home hyperlooms
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar /app/control-plane.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/control-plane.jar"]
