FROM gradle:8.7-jdk17 AS builder
WORKDIR /workspace
COPY . .
RUN gradle clean bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app

# 첨부파일 저장 디렉터리를 appuser 전환 전에 만들고 소유권을 넘긴다 — 안 하면 named volume
# 마운트 지점이 root 소유로 남아 appuser가 쓰기 실패한다(PLAN-notice-attachment.md 쟁점 10).
# Docker는 named volume이 처음 마운트될 때 이미지 쪽 대상 디렉터리의 내용·소유권을 그대로
# 복사하므로, 이 순서면 volume도 appuser 소유로 초기화된다.
RUN useradd -m appuser \
    && mkdir -p /app/data/attachments \
    && chown -R appuser:appuser /app
USER appuser

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]