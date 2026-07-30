# Pre-Release Review Report

Target: 전체 프로젝트 (feat/prod-profile 브랜치, prod 프로파일 부활 작업 포함)
Additional scope: 없음
Verdict: **needs-attention**

## Executive Summary

- **차단(no-ship) 항목: 0건.** 로그인·인가·CSRF·감사 로그·최후 ADMIN 가드·prod 부트스트랩·actuator 노출 등 핵심 안전장치는 코드 열람과 이번 세션의 실제 Docker 실기 검증(빈 DB fail-fast, 시크릿 격리, 특수문자 비밀번호, 재기동 무중복 등)으로 실측 확인됐다.
- **주의(needs-attention) 항목: 2건.** (1) 핸들러 없는 경로가 404 대신 500을 반환하는 기존 결함(이번 PR 범위 밖으로 이미 문서화됨) — 보안 실질 피해는 없음. (2) `docker-compose.dev.yml`/`docker-compose.prod.yml`이 원래 프로젝트 이름을 명시하지 않아 실제로 dev 컨테이너가 prod로 대체되는 사고가 이번 세션에 발생했었음 — **코드 수정으로 이미 해결**했으나, 유사 패턴(compose 파일을 프로젝트 이름 없이 다중 배포)이 재발하지 않도록 원칙으로 남긴다.
- **미확인: 3건** — 전부 실기동/브라우저가 필요한 항목으로, 이 세션의 다음 단계(Playwright 실기 검증)에서 확인 예정.

이 리포트는 prod 프로파일 골격이 존재하는 상태를 전제로 작성됐다 — 스킬 기본 전제("prod 미구축")와 달리 `application-prod.yml`·`docker-compose.prod.yml`·`AdminBootstrapLoader`·`ProfileGuardEnvironmentPostProcessor`가 이미 코드베이스에 존재하므로 이들을 정식 점검 대상에 포함했다.

## No-Ship Findings

없음.

## Needs-Attention Findings

### [medium] 핸들러 없는 경로가 404가 아니라 500을 반환

- **위치**: `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java:315`(`@ExceptionHandler(Exception.class)` catch-all, selector 없는 전역 `@RestControllerAdvice`)
- **확인한 파일**: 위 파일 + Docker 실기 검증(`GET /swagger-ui.html`·`/v3/api-docs`가 인증된 ADMIN 세션에서도 404가 아니라 500 JSON 반환 — 2026-07-30 실측)
- **문제**: `springdoc.swagger-ui.enabled=false`(prod)로 핸들러 자체가 사라지면 Spring MVC가 `NoResourceFoundException` 계열을 던지는데, 이 예외까지 전역 catch-all이 잡아 500으로 응답한다. `GET /admin/logout`(POST 전용)·`GET /favicon.ico`도 동일 증상이 이미 `PLAN-public-notice.md` 실기 검증에서 발견된 바 있다(3번째 발견 사례).
- **영향**: 보안 정보 노출은 없음(문서가 새는 게 아니라 그냥 500). 다만 모니터링/알림 관점에서 "진짜 장애"와 "존재하지 않는 경로 접근"이 같은 500으로 섞인다.
- **Recommendation**: `GlobalApiExceptionHandler`에 `NoResourceFoundException`(또는 `NoHandlerFoundException`) 전용 핸들러를 추가해 404로 응답하게 한다 — 이는 admin API를 포함한 앱 전체의 예외 처리 범위를 건드리는 변경이라 특정 기능 PR이 아니라 별도 작업으로 다룰 것을 권장(사용자와 이미 합의해 `docs/troubleshooting.md`에 근본원인·해결 방향을 기록해둠).

### [low, 이미 해결됨] Docker Compose 프로젝트 이름 미지정으로 인한 스택 간 충돌

- **위치**: `docker-compose.dev.yml`, `docker-compose.prod.yml`
- **확인한 파일**: 두 파일의 `name:` 필드(이번 세션에 추가) + 실기 검증(수정 전: prod compose 실행 시 dev DB 컨테이너가 실제로 교체됨 → 수정 후: 재현 안 됨, dev 데이터 무결성 확인)
- **문제**: 프로젝트 이름을 명시하지 않으면 두 compose 파일이 디렉터리명("CMS") 기준 같은 암묵적 프로젝트로 묶여, 서비스 이름(`db`/`app`)이 충돌한다. 실제로 이 문제로 dev DB 컨테이너가 prod db 서비스로 대체되는 사고가 발생했다(named volume은 보존되어 데이터 유실 없음, `docker inspect` 라벨 `com.docker.compose.replace`로 확인).
- **영향**: 이미 수정 완료 — `name: cms-dev` / `name: cms-prod` + 볼륨 이름 명시 고정으로 재현되지 않음을 실측 확인.
- **Recommendation**: 향후 compose 파일을 추가할 때(예: 스테이징) 반드시 고유한 `name:`을 명시하는 것을 팀 관례로 남긴다.

## 점검 상세 (근거 포함)

### 1. 릴리스 차단 항목 점검

| 항목 | 결과 | 근거 |
|---|---|---|
| `TestMemberLoader`가 dev 밖에서 실행될 가능성 | 없음 | `@Profile("dev")`(`TestMemberLoader.java:17`) + `ProfileGuardEnvironmentPostProcessor`가 `dev`+`prod` 동시 활성화를 컨텍스트 생성 전에 차단(실기동으로 fail-fast 확인, 2026-07-29) |
| 인증 없이 `/admin/**`·`/admin/api/**` 접근 | 불가 | `SecurityConfig.java:59` `hasRole("ADMIN")`(그 외 명시 규칙 제외 전부), `anyRequest().permitAll()`은 `/admin/**` 매칭 이후에 위치하지 않음(순서상 `/admin/**` 규칙이 먼저 매칭) |
| MANAGER가 ADMIN 전용 기능 접근 | 차단됨 | `SecurityConfigTest`에 `manager_membersListApi_forbidden`·`manager_memberManagePage_forbidden` 등 회귀 테스트 존재(기존), 메뉴 `accessRole`은 노출 전용이고 실제 차단은 `SecurityConfig`가 담당(CLAUDE.md와 코드 일치 확인) |
| 상태 변경 API CSRF 보호 | 전 경로 활성 | `SecurityConfig`에 `csrf(...)` 비활성화 호출 없음(Spring Security 기본값 유지) — `SecurityConfigTest.createNotice_missingCsrf_forbidden` 등 회귀 테스트로 확인 |
| 비밀번호·토큰 평문 저장 | 없음 | `PasswordResetService`는 SHA-256 해시만 저장(코드 주석·`IssueResult.toString()` 재정의로 로그 노출도 차단), 비밀번호는 BCrypt(`SecurityConfig.java:135`) |
| secret 하드코딩 | 없음 | `.env.dev`·`.env.prod` 모두 `git ls-files`로 추적 안 됨 확인(gitignore `.env*` 규칙), `application*.yml`에 값 없이 `${VAR}` 참조만 존재 |
| `ddl-auto` | `validate` | `application.yml`(공통, 전 프로파일)에 `spring.jpa.hibernate.ddl-auto: validate` — prod 파일은 이 값을 오버라이드하지 않음 |
| Flyway 마이그레이션 파일 수정 | 없음 | `git diff --stat -- src/main/resources/db/migration/` 결과 비어있음(이번 브랜치에서 무변경) |
| 감사 로그 REQUIRES_NEW | 확인됨 | `AdminActionLogService.java:24` `@Transactional(propagation = Propagation.REQUIRES_NEW)` |
| Swagger 노출 범위 | dev만 | prod: `springdoc.api-docs.enabled=false`+`swagger-ui.enabled=false`(`application-prod.yml`) — Docker 실기 검증으로 인증 세션에서도 500(사실상 비활성, 위 needs-attention 참조)이지 정상 200이 아님을 확인. dev: 기존과 동일하게 `hasRole("ADMIN")` |
| 로그인/관리자 핵심 플로우 | 정상(코드 기준) | 회귀 테스트 다수 통과(523개), Docker 실기 검증에서 실제 로그인 성공까지 확인 |

### 2. 보안 점검

- **matcher 순서**: `/admin/login` 등 permitAll 규칙 → 세분화된 admin 규칙 → `/admin/**` catch(hasRole ADMIN) → `/notices` GET/HEAD permitAll+denyAll → **`/actuator/health` permitAll + `/actuator/**` denyAll(신규)** → `anyRequest().permitAll()`. 신규 actuator 규칙이 `anyRequest()`보다 앞에 위치해 실제로 적용됨을 `SecurityConfigTest`(302/403/200 케이스)로 확인.
- **메서드 보안**: `@EnableMethodSecurity`(`MethodSecurityConfig.java`) 활성 — URL 보안과 이중 방어 구조는 기존 그대로, 이번 PR에서 변경 없음.
- **CSRF ↔ JS 계약**: 기존 `head.html`의 `<meta name="_csrf">` 패턴 무변경. 신규 fetch 호출을 이번 PR에서 추가하지 않았으므로 해당 없음.
- **인증/인가 실패 핸들러**: `/admin/api/**`는 JSON 401/403, 그 외(`/actuator/**` 포함)는 페이지 진입점의 `LOGIN_ENTRY_POINT`/`DEFAULT_ACCESS_DENIED_HANDLER`를 탐 — `/actuator/env` 비인증 302, 인증 403으로 실측 일치(계획대로).
- **세션 강제 만료**: 이번 PR에서 무변경.
- **ACTIVE만 로그인**: `CustomUserDetailsService.java:68` 확인.
- **최후 활성 ADMIN 가드**: `AdminMemberService.java:185`에서 `findActiveAdminIdsForUpdate()` 사용 확인, 이번 PR이 추가한 `existsByUserTypeAndStatus`(비잠금 존재 확인 전용, 부트스트랩 트리거 판정에만 사용)와 역할이 겹치지 않음.
- **actuator 노출 범위**: `health`만 무인증 공개(`show-details: never`) — `info`는 이번 PR에서 dev·prod 공통으로 제거(코드베이스 어디서도 미참조 확인). `WebEndpointsSupplier` 기반 자동 테스트(`ActuatorExposureTest`)로 실제 등록 엔드포인트가 `{health}` 하나뿐임을 실기(Testcontainers) 확인.
- **CORS**: `grep` 결과 `CorsConfiguration`·`@CrossOrigin`·`.cors(` 사용처 0건 — CORS 미설정(동일 출처만 허용되는 기본값 유지).

### 3~7. API 계약 / 도메인·DB / 화면 / 테스트·CI / 설정·빌드

이번 PR(prod 프로파일 부활)은 **API 엔드포인트·엔티티·화면을 추가·변경하지 않았다** — 변경 범위는 설정(`application*.yml`), 인프라(Dockerfile·compose·스크립트), 신규 인프라 컴포넌트(`ProfileGuardEnvironmentPostProcessor`·`AdminBootstrapLoader`)로 한정된다. 따라서 3~5번 섹션(API 계약·도메인/DB·화면)은 **이번 PR로 인한 변화 없음**만 확인:

- **Flyway 스키마 일치**: `ddl-auto: validate`로 기동 성공(Docker 실기 검증 시 Flyway V10 검증 통과 확인) — 엔티티 변경이 없으므로 마이그레이션 누락 우려 없음.
- **N+1·소프트 삭제 정책**: 이번 PR에서 무변경.
- **CI**: `.github/workflows/ci.yml`이 `SPRING_PROFILES_ACTIVE: dev`로 `./gradlew test` 실행 — 이번 PR의 `application.yml` 기본값 제거와 충돌하지 않음(명시적으로 dev를 주입하므로). CI가 prod 프로파일이나 Docker 이미지 빌드는 검증하지 않는다(이번 PR 범위에서 수동 Docker 실기 검증으로 대체 — PLAN-prod-profile.md 참조).
- **회귀 테스트**: `./gradlew test` 523개 전체 통과(2026-07-30, 이 세션에서 실행) — 실패 0건.
- **빌드 의존성**: 신규 의존성 추가 없음(기존 `spring-boot-starter-validation`을 프로그래매틱 `Validator`로 재사용, `spring-boot-starter-actuator`는 기존 의존성).

### 8. 운영 준비 공백 (Pre-Prod Backlog)

| 항목 | 왜 운영 전 필수인지 | 권장 시점 |
|---|---|---|
| nginx 리버스 프록시·TLS 인증서 | 현재 prod compose는 `127.0.0.1:8080`으로 **루프백에만** 바인딩 — 그대로 인터넷에 노출하면 안 됨(PLAN-prod-profile.md 명시). HTTPS 없이 관리자 비밀번호가 평문으로 네트워크를 오간다. | 실배포 호스트·도메인 확정 시 |
| `forward-headers-strategy`, secure/SameSite 쿠키 | 리버스 프록시 뒤에서 `X-Forwarded-*` 헤더를 신뢰하려면 필요. 현재 미설정. | 리버스 프록시 도입과 함께 |
| 실제 호스팅(VPS·도메인·DNS), CD 파이프라인 | 이번 PR은 "prod로 뜰 수 있는 상태"까지만 다루고 실제 배포는 범위 밖(PLAN-prod-profile.md 명시) | 사용자 결정 사안 |
| 핸들러 없는 경로의 500 오응답(위 needs-attention) | 운영 모니터링 알림 노이즈 방지 | 여유 있을 때 별도 작업 |
| 메일(SMTP) 실제 자격증명 검증 | `.env.example`의 `MAIL_USER`/`MAIL_PASS`는 더미로도 기동은 되지만, 비밀번호 재설정 메일이 실제로 발송되는지는 실제 SMTP 계정으로만 확인 가능(아래 미확인 항목 참조) | 실배포 전 1회 |
| DB 백업 전략 | 이번 PR 범위 밖 — named volume만으로는 백업이 아님 | 실배포 전 |

## 미확인 항목

- **항목**: 로그인 → 대시보드 → 관리자/메뉴/공지 관리 화면 골든 패스의 prod 프로파일 브라우저 실기 확인
  **확인 불가 사유**: 이번 deploy-check는 코드 정적 점검 범위 — 브라우저 조작 필요
  **확인 방법**: 이 세션의 다음 단계(Playwright)로 dev 프로파일 기준 회귀 확인 예정(`docs/development-workflow.md` 7단계). prod 프로파일 자체의 화면은 이번 Docker 실기 검증에서 `curl` 기반으로 로그인·`/admin` 접근까지 확인 완료(HTML 렌더링 세부는 미확인).

- **항목**: 실제 SMTP 서버로 비밀번호 재설정 메일이 prod 환경에서 정상 발송되는지
  **확인 불가 사유**: 실제 SMTP 자격증명·외부 메일 서비스 의존 — 이번 검증은 더미 값으로 기동 성공만 확인
  **확인 방법**: 실배포 시 진짜 Gmail 앱 비밀번호로 `.env.prod`를 채운 뒤 `POST /admin/api/password-reset-requests`를 호출해 수신 여부 확인

- **항목**: `healthcheck.sh --connect --innodb_initialized`가 향후 `mariadb:10.11` 이미지 태그 업데이트 후에도 계속 존재하는지
  **확인 불가 사유**: 이번 세션에서는 현재 pull된 이미지로만 확인(`docker run --rm mariadb:10.11 which healthcheck.sh` → 존재 확인, 2026-07-29). 이미지가 재빌드/업데이트되면 재확인 필요
  **확인 방법**: `docker pull mariadb:10.11 && docker run --rm mariadb:10.11 which healthcheck.sh`를 배포 전 재실행

## Final Verdict

**needs-attention** — 차단 항목은 없다. 핵심 보안·인가·CSRF·감사 로그·prod 부트스트랩 안전장치는 코드 열람과 실제 Docker 기동(빈 DB fail-fast, 시크릿 격리, 특수문자 비밀번호 로그인, 재기동 무중복, actuator 이중 방어)으로 실측 확인됐다. 발견된 2건 중 1건(compose 프로젝트 이름 충돌)은 이미 코드로 수정·재검증했고, 1건(핸들러 없는 경로 500)은 기존 결함이라 사용자와 합의해 범위 밖으로 문서화했다. 미확인 3건은 전부 실기동/외부 서비스 의존이라 이번 코드 점검만으로는 판정할 수 없는 항목이며, 확인 방법을 병기했다.
