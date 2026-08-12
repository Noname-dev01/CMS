# 저장소의 Gradle Wrapper 버전(gradle-wrapper.properties)과 일치시키기 위해 시스템 Gradle
# 이미지 대신 JDK 이미지 + ./gradlew를 쓴다(PLAN-prod-profile.md 결정 9) — gradle:8.7-jdk17의
# 시스템 Gradle 8.7은 저장소가 실제로 쓰는 버전과 달라 로컬·CI와 다른 빌드 도구로
# 배포 이미지가 만들어지는 불일치가 있었다.
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace
COPY gradlew gradlew.bat ./
COPY gradle gradle
RUN chmod +x gradlew
# wrapper 파일만 먼저 복사하는 것으로는 Gradle 배포판 다운로드가 캐시되지 않는다 — 전체
# 소스 복사 전에 가벼운 명령으로 다운로드 자체를 별도 레이어로 캐시한다.
RUN ./gradlew --version --no-daemon
COPY . .
RUN ./gradlew clean bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app

# 첨부파일 저장 디렉터리를 appuser 전환 전에 만들고 소유권을 넘긴다 — 안 하면 named volume
# 마운트 지점이 root 소유로 남아 appuser가 쓰기 실패한다(PLAN-notice-attachment.md 쟁점 10).
# Docker는 named volume이 처음 마운트될 때 이미지 쪽 대상 디렉터리의 내용·소유권을 그대로
# 복사하므로, 이 순서면 volume도 appuser 소유로 초기화된다.
# UID·GID를 명시적으로 고정한다(10001) — scripts/prod-backup.sh·prod-restore.sh가
# tar --numeric-owner로 파일 볼륨을 백업/복원하는데, UID를 이미지에 맡기면 베이스
# 이미지나 사용자 생성 순서가 바뀔 때 복원된 파일 소유권이 새 appuser와 어긋날 수
# 있다(PLAN-db-backup.md 결정 21).
# 주의: 이 UID 고정 이전 이미지(useradd -m appuser, UID 미지정)로 이미 named volume을
# 생성해둔 상태에서 이 이미지로 갱신하면, Docker는 기존 volume을 재복사·재소유하지
# 않으므로 새 appuser(10001)가 옛 UID 소유 파일에 쓰기 실패할 수 있다 — 기존 볼륨
# 이관 절차는 docs/deployment.md 참고.
RUN groupadd -g 10001 appuser \
    && useradd -m -u 10001 -g 10001 appuser \
    && mkdir -p /app/data/attachments \
    && chown -R appuser:appuser /app
USER appuser

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]