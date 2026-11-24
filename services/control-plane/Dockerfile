FROM gradle:9.2.1-jdk17 AS build
WORKDIR /workspace/services/control-plane
COPY services/control-plane/ ./
COPY proto/ /workspace/proto/
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:17-jre
RUN useradd --system --uid 10001 --create-home hyperlooms
WORKDIR /app
COPY --from=build /workspace/services/control-plane/build/libs/*.jar /app/control-plane.jar
USER 10001
EXPOSE 8080 9090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/control-plane.jar"]
