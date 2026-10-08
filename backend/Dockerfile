# syntax=docker/dockerfile:1
# =====================================================================
# Finesse backend 이미지 (초안 — DevOps 작성, backend 담당 확인 필요)
#
# 빌드:  docker build -t finesse-backend ./backend
# 실행:  docker run -p 8081:8081 finesse-backend
#
# LLM 추론 서버 주소는 이미지에 넣지 않고 실행할 때 주입한다(application.yml 주석과 동일 원칙).
#   방법 1) 인자:   docker run ... finesse-backend --llm.servers=http://192.168.0.111:8081,http://192.168.0.113:8081
#   방법 2) 환경변수: docker run -e LLM_SERVERS=http://192.168.0.111:8081,http://192.168.0.113:8081 ...
# =====================================================================

# ---- 1단계: 빌드 (JDK 25 + Gradle wrapper) ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# 의존성 정의 파일만 먼저 복사 → 소스만 바뀌었을 때 의존성 다운로드 레이어를 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null

COPY src src
# 테스트는 CI의 backend job(./gradlew build)에서 이미 돌리므로 이미지 빌드에서는 생략
RUN ./gradlew --no-daemon bootJar -x test

# ---- 2단계: 실행 (JRE만, 빌드 도구 제외 → 이미지 크기 감소) ----
FROM eclipse-temurin:25-jre
WORKDIR /app

# root가 아닌 전용 사용자로 실행 (UID/GID는 숫자로 고정 — 호스트에서도 같은 번호로 식별 가능, hadolint DL3066)
RUN groupadd --system --gid 10001 finesse \
 && useradd --system --uid 10001 --gid 10001 --no-create-home finesse

COPY --from=build /app/build/libs/*.jar /app/app.jar

USER 10001:10001
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
