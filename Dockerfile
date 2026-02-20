# 🔧 Stage 1: 빌드용 (Gradle로 JAR 생성)
# 필요프로그램 설치
FROM eclipse-temurin:17-jdk-alpine AS stage1
# 파일 복사
WORKDIR /app
COPY gradle gradle
COPY src src
COPY build.gradle .
COPY settings.gradle .
COPY gradlew .
# 빌드
RUN chmod +x gradlew
RUN ./gradlew bootJar

# Stage 2: 실행용 (표준 JDK 환경에 빌드 결과물만 포함하여 이미지 최적화)
FROM eclipse-temurin:17-jdk

# 1. 타임존 데이터 설치
RUN apt-get update && \
    apt-get install -y tzdata && \
    rm -rf /var/lib/apt/lists/*
# 2. 시스템 타임존을 Asia/Seoul로 설정
ENV TZ=Asia/Seoul
# 3. JVM도 명시적으로 Asia/Seoul로 고정
ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul"

WORKDIR /app
COPY --from=stage1 /app/build/libs/*.jar app.jar

# 실행 : CMD 또는 ENTRYPOINT를 통해 컨테이너를 배열 형태의 명령어로 실행
ENTRYPOINT ["java", "-Dspring.profiles.active=prod", "-jar", "app.jar"]
