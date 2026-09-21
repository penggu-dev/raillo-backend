# API와 Batch가 같은 빌드/실행 환경을 사용한다.
# 기본값은 API. Batch 이미지는 --build-arg APP_MODULE=raillo-batch로 빌드한다.
FROM eclipse-temurin:25-jdk-noble AS build
ARG APP_MODULE=raillo-api
WORKDIR /app
COPY gradle gradle
COPY gradlew settings.gradle build.gradle ./
COPY raillo-domain raillo-domain
COPY raillo-api raillo-api
COPY raillo-batch raillo-batch
RUN case "$APP_MODULE" in raillo-api|raillo-batch) ;; *) exit 1 ;; esac \
    && chmod +x gradlew \
    && ./gradlew ":${APP_MODULE}:bootJar" --no-daemon \
    && cp "${APP_MODULE}/build/libs/raillo-"*.jar /app/app.jar

FROM eclipse-temurin:25-jre-noble AS runtime
ENV TZ=Asia/Seoul \
    JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Seoul" \
    SPRING_PROFILES_ACTIVE=prod
RUN groupadd --gid 10001 raillo \
    && useradd --uid 10001 --gid raillo --no-create-home raillo
WORKDIR /app
COPY --from=build --chown=raillo:raillo /app/app.jar app.jar
USER raillo
# CronJob의 args가 java -jar 뒤에 전달된다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
