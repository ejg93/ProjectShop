# 백엔드 컨테이너 이미지. 빌드 문맥은 `backend/` 다(Railway 는 Root Directory 를 `backend` 로).
#
# 두 단계다 — 빌드 단계는 JDK 와 Gradle 캐시를 들고, 실행 단계는 JRE 와 jar 하나만 든다.
# 로컬은 이 파일을 안 쓴다(`bootRun`). 호스팅이 이 파일을 읽어 이미지를 만든다.
#
# `build.gradle.kts` 의 toolchain 이 25 라 빌드 이미지도 25 여야 한다 — 낮으면 Gradle 이 JDK 를 내려받다 실패한다.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# 의존성 해석을 소스보다 앞에 두면 소스만 바뀐 빌드에서 이 층이 캐시로 남는다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet > /dev/null || true
COPY src ./src
COPY config ./config
# 테스트·정적 검사는 CI 가 돈다(`ci.yml`). 이미지 빌드는 jar 만 만든다.
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
# 포트는 `application.yml` 이 `SERVER_PORT` → `PORT` → 8080 순으로 읽는다.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
