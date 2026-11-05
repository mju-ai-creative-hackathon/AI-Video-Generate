# VM에서 소스로 직접 빌드한다(CI 없이). 루트 Dockerfile은 CI가 만든 jar만 복사한다.
# 빌드: docker compose -f deploy/vm/docker-compose.yml build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src src
# 테스트는 Testcontainers(Docker 안의 Docker)가 필요해 여기서는 건너뛴다. PR CI에서 실행된다.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 app
RUN mkdir -p /app/data/shortforms && chown -R app:app /app/data
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
