# Stage 1: 빌드용 (Gradle multi-module → raillo-core bootJar)
FROM eclipse-temurin:17-jdk-alpine AS stage1
WORKDIR /app
COPY gradle gradle
COPY gradlew .
COPY settings.gradle build.gradle ./
COPY raillo-common raillo-common
COPY raillo-core raillo-core
RUN chmod +x gradlew
RUN ./gradlew :raillo-core:bootJar --no-daemon

# Stage 2: 실행용
FROM eclipse-temurin:17-jdk-alpine

# 1. 타임존 데이터 설치
RUN apk add --no-cache tzdata
# 2. 시스템 타임존을 Asia/Seoul로 설정
ENV TZ=Asia/Seoul
# 3. JVM도 명시적으로 Asia/Seoul로 고정
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"

WORKDIR /app
COPY --from=stage1 /app/raillo-core/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
