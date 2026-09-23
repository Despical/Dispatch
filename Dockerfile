FROM node:22.17.0-alpine AS frontend
WORKDIR /workspace/frontend
RUN corepack enable && corepack prepare pnpm@10.28.2 --activate
COPY frontend/package.json frontend/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM eclipse-temurin:25-jdk-alpine AS backend
WORKDIR /workspace
COPY gradle/ gradle/
COPY gradlew settings.gradle build.gradle flyway.conf ./
COPY src/ src/
COPY --from=frontend /workspace/src/main/resources/static/assets/ src/main/resources/static/assets/
RUN chmod +x gradlew && ./gradlew bootJar -x frontendBuild -x pnpmInstall --no-daemon --console=plain

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S dispatch && adduser -S -G dispatch dispatch
WORKDIR /app
COPY --from=backend /workspace/build/libs/dispatch.jar /app/dispatch.jar
RUN mkdir -p /app/data/attachments && chown -R dispatch:dispatch /app
USER dispatch
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/dispatch.jar"]
