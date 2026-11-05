# CI에서 ./gradlew build 후 생성된 bootJar만 복사한다.
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd -r -u 1001 app
RUN mkdir -p /app/data/shortforms && chown -R app:app /app/data
COPY build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/app.jar"]
