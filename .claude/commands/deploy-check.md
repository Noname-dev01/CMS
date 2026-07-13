---
description: 프로젝트 전체를 보안·권한·데이터 정합성·설정 관점에서 전수 점검하고 ship/no-ship 리포트만 작성한다 (파일 수정 없음). 현재 운영 환경 미구축 상태이므로 prod 전용 점검 항목은 "해당 없음"으로 처리 — 릴리스 후보 점검이나 전면 보안 점검이 필요할 때 실행.
---

프로젝트 전체를 릴리스 후보 관점으로 전수 점검해줘.

목표:
- 이 프로젝트가 "지금 운영 환경을 만든다면" 바로 올려도 되는 상태인지 판단한다.
- 코드 스타일보다 보안, 권한 경계, 데이터 정합성, 설정 안전성을 우선한다.
- 파일은 절대 수정하지 말고 리포트만 작성한다.

전제 (이 프로젝트의 현재 상태 — 점검 시 이 사실과 달라졌는지부터 확인해):
- 운영 환경 미구축. 존재하는 설정은 `application.yml` + `application-dev.yml`,
  `docker-compose.dev.yml`, `.env.dev`, `Dockerfile`, `Makefile`(dev 타깃만)이 전부다.
- prod 프로파일·prod compose·SSL·운영 DB는 **존재하지 않는다**. 이들에 대한 점검은
  "해당 없음"으로 처리하고, 존재한다고 가정한 지적을 만들어내지 마.
  단, prod 관련 파일이 새로 생겼다면 그 시점부터 점검 대상에 포함해.

중요 원칙:
- 추측하지 말고 실제 파일을 열어서 확인해.
- **"미확인"은 코드만 읽어서는 확인할 수 없는 항목에만 허용한다** (실기동 필요, 외부 서비스 의존 등).
  미확인으로 남기는 항목에는 "왜 확인 불가인지 + 확인 방법(실행 명령 등)"을 반드시 병기해.
- 위험도가 높은 항목을 "나중에 개선"으로 넘기지 마.
- CLAUDE.md, README, 설정 파일, 테스트 코드, 실제 구현을 서로 대조해.
- 모든 답변은 한국어로 작성해.

추가 검토 범위 또는 요청: $ARGUMENTS (비어 있으면 전체 기본 점검)

# 점검 범위

## 1. 릴리스 차단 항목 (하나라도 있으면 no-ship 후보)

- 기본 관리자 계정 자동 생성(`TestMemberLoader`)이 dev 프로파일 밖에서도 실행될 가능성
- 인증 없이 관리자 기능(`/admin/**`, `/admin/api/**`) 접근 가능
- ROLE_MANAGER가 ADMIN 전용 기능에 접근 가능 (메뉴 `accessRole`은 노출 제어일 뿐 —
  실제 차단은 Security가 하는지 구분해서 확인)
- 상태 변경 API에 CSRF 보호 구멍 (CSRF는 전 경로 활성이 현재 계약)
- 비밀번호·토큰 평문 저장/노출 (resetToken 원문 저장 여부 포함)
- secret·비밀번호가 코드/저장소에 하드코딩 (`.env.dev`의 값이 실제 시크릿인지, git 추적 여부)
- `ddl-auto`가 `validate`가 아닌 값으로 바뀜 (스키마 관리는 Flyway가 원본)
- 머지된 Flyway 마이그레이션 파일이 수정됨 (체크섬 불일치로 기동 실패 위험)
- 감사 로그(`AdminActionLog`)가 원 트랜잭션 롤백 시 함께 유실될 가능성 (REQUIRES_NEW 계약 확인)
- Swagger/api-docs가 dev 전용·ROLE_ADMIN 제한을 벗어나 노출될 가능성
- 로그인/관리자 핵심 플로우가 깨질 가능성

## 2. 보안 점검 (실제 코드 기준)

- `SecurityConfig`: matcher 순서, permitAll 범위, `/admin/**`·`/admin/api/**` 접근 제어
- 메서드 보안(`@PreAuthorize`)과 URL 보안의 이중 방어 상태
- CSRF 설정과 Thymeleaf/JS의 `X-CSRF-TOKEN` 전송 계약 일치 여부
- 인증 실패/인가 실패 핸들러: API는 JSON 401/403(`ApiAuthenticationEntryPoint`/`ApiAccessDeniedHandler`),
  페이지는 로그인 리다이렉트 — 이 계약이 유지되는지
- 세션 강제 만료(`AdminSessionRevokeEvent` → AFTER_COMMIT): best-effort 계약이 문서·코드에서 일관되는지
- `ACTIVE` 상태만 로그인 가능(`CustomUserDetailsService`), 비밀번호 인코딩(BCrypt)
- 최후 활성 ADMIN 제거 방지 가드
- actuator 노출 범위 (health/info 공개가 의도 범위인지)
- CORS 설정 존재 시 범위

## 3. API 계약 점검

- 엔드포인트 경로·HTTP method가 CLAUDE.md RESTful 규칙과 일치하는지
- 요청 DTO Bean Validation, 실패 시 400 VALIDATION_ERROR 응답
- 409 DUPLICATE_RESOURCE (유니크 제약 위반 매핑) 동작
- 상태 코드 일관성 (400/401/403/404/409/500)
- Swagger 문서와 실제 API 차이
- RESTful 지향 규칙을 아직 따르지 않는 API 목록 (지향 규칙이므로 no-ship 사유는 아님)

## 4. 도메인 / DB / 트랜잭션 점검

- 엔티티와 Flyway 마이그레이션(V1~) 스키마 일치 (`ddl-auto: validate` 통과 여부)
- 엔티티 변경에 마이그레이션 파일 누락 여부
- nullable / unique 제약, Enum 저장 방식
- Service 트랜잭션 경계, 조회 전용 `readOnly` 적용
- N+1 가능성 (사이드바 메뉴 조회, 목록 API)
- 소프트 삭제(메뉴 비활성화) 정책 일관성
- 프로필 이미지 Base64 LONGTEXT 저장 — 응답 크기·성능 리스크

## 5. 화면 / Thymeleaf 점검

- 로그인 → 메인 → 관리자/메뉴 관리 화면 골든 패스 (코드 정적 확인, 실기동 필요 항목은 미확인+방법 병기)
- 권한별 사이드바 노출(`MenuAccessRole`)과 실제 URL 접근 차단의 구분
- `@AdminPage` 컨벤션 준수 (누락 시 사이드바 미주입)
- 깨진 링크, 미구현 링크 노출
- 상태 변경 fetch 호출의 CSRF 헤더 누락 여부

## 6. 테스트 / CI 점검

- 인증/인가/CSRF 테스트 존재 여부
- Security Filter Chain 포함 통합 테스트 여부
- CI(`.github/workflows/ci.yml`)가 실제로 전체 테스트를 실행하는지 (MariaDB service container 포함)
- 테스트 성공 기준이 지나치게 느슨하지 않은지
- Playwright/E2E로만 검증 가능한 항목 목록

## 7. 설정 / 빌드 점검

- `build.gradle` 의존성·버전 (Boot 3.5.x, QueryDSL, Flyway)
- `application.yml` / `application-dev.yml` / `.env.dev` 정합성 (포트 3307, 프로파일 기본값 dev)
- `Dockerfile`, `docker-compose.dev.yml`, `Makefile` 타깃이 실제로 동작하는 구성인지
- 환경변수 의존성 목록과 누락 시 동작
- 메일(SMTP) 설정 — 미구현 기능이 기동을 막지 않는지
- 로그 설정, 타임존

## 8. 운영 준비 공백 (no-ship 아님 — 운영 구축 전 필수 과제로만 분류)

운영 환경을 만들기 전에 반드시 해결해야 할 항목을 별도 목록으로 정리해:
- prod 프로파일·시크릿 주입 전략 부재
- 미구현 기능(메일 발송, 비밀번호 재설정 토큰 사용, LOCKED/PASSWORD_EXPIRED 전이) 중 보안 관련 항목
- 그 외 운영 전제 조건 (백업, 로그 수집 등 발견되는 것)

# 출력

리포트를 `adversarial-review/deploy-check-<YYYY-MM-DD>.md`로 저장하고 (같은 날짜 존재 시 `-2` 접미사),
채팅에는 Verdict와 차단/주의 항목만 요약해 보고해.

# Pre-Release Review Report

Target: 전체 프로젝트
Additional scope: $ARGUMENTS
Verdict: ship | needs-attention | no-ship

## Executive Summary
- 최종 판정과 근거 요약
- 차단 항목 수 / 주의 항목 수 / 미확인 수

## No-Ship Findings
### [critical/high] 제목
- 위치 / 확인한 파일 / 문제 / 실패 시나리오 / Recommendation

## Needs-Attention Findings
### [high/medium] 제목
- 위치 / 확인한 파일 / 문제 / 영향 / Recommendation

## 운영 준비 공백 (Pre-Prod Backlog)
- 항목 / 왜 운영 전 필수인지 / 권장 시점

## 미확인 항목
- 항목 / 확인 불가 사유 / 확인 방법 (실행 명령 등)

## Final Verdict
- ship: 현재 상태 기준 문제 없음 (운영 준비 공백은 별도 백로그)
- needs-attention: 일부 수정/확인 후 통과 가능
- no-ship: 차단 항목 해결 전까지 릴리스 불가

파일은 절대 수정하지 말고 리포트 저장까지만 해.
