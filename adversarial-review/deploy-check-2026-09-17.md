# Pre-Release Review Report

Target: 전체 프로젝트
Additional scope: (없음 — 기본 전수 점검, 보안 / API·도메인·DB / 화면·테스트·CI / 설정·빌드·운영준비 4개 영역 병렬 조사 종합)
Verdict: **ship** (운영 준비 공백은 별도 백로그로 분리)

## 전제 정정 (중요)

이번 점검 요청 프롬프트는 "prod 프로파일·prod compose·SSL·운영 DB는 존재하지 않는다"를 전제로 제시했지만, 실제 코드베이스는 이미 이 단계를 지나 있다:

- `application-prod.yml`, `docker-compose.prod.yml`, `Makefile`의 `prod-up`/`prod-down`/`prod-backup` 타깃, `docs/deployment.md`가 모두 존재하고 서로 일관되게 연결되어 있다.
- 루트 `CLAUDE.md`는 "운영(prod, 배포 가능 상태 검증 완료 — 2026-07-29)"라고 명시한다. 즉 이 프로젝트는 "dev만 있는 상태"가 아니라 "prod 기동 가능 상태 검증까지 끝낸 상태"다.
- `docker-compose.prod.yml`은 `127.0.0.1:8080:8080`으로 루프백에만 바인딩되어 있고 주석으로 "실제 인터넷 노출은 리버스 프록시·TLS가 갖춰진 뒤(범위 밖)"라고 명시한다 — 실제 인터넷 배포(도메인·TLS)만 미구축이며, 이는 원 전제와 일치한다.

이 정정에 따라 아래 점검은 "해당 없음"으로 생략하지 않고 prod 관련 파일까지 포함해 확인했다.

## Executive Summary

전체 코드베이스를 4개 영역(보안/인증/세션, API 계약·도메인·DB·트랜잭션, 화면(Thymeleaf)·테스트·CI, 설정·빌드·운영준비)으로 나눠 병렬 전수 점검한 결과, **릴리스(운영 환경 구축) 차단(no-ship) 항목은 발견되지 않았다.** 보안 설정(SecurityConfig 경로별 접근 제어, CSRF, 세션 강제 만료, 비밀번호 재설정 토큰 해시화, 최후 ADMIN 가드, actuator 이중 방어), 트랜잭션 계약(감사 로그 REQUIRES_NEW), Flyway 마이그레이션 이력, prod 시크릿 관리 전략까지 CLAUDE.md에 기술된 계약과 실제 코드가 일치했다. 다만 로컬 개발 스택 기동 경로(`make dev-up`)에 실사용 결함이 하나 발견되어 Needs-Attention으로 분류했다 — 포트폴리오 리뷰어가 이 경로로 실행을 시도하면 실패할 수 있다.

- 차단(No-Ship) 항목: 0건
- 주의(Needs-Attention) 항목: 4건 (모두 no-ship 사유는 아니나 그중 1건은 "즉시 실행 가능"이라는 포트폴리오 인상에 직접 영향)
- 운영 준비 공백: 4건 (no-ship 아님, 실 인터넷 배포 전 필수)
- 미확인 항목: 4건 (실기동·Docker 필요)

## No-Ship Findings

없음.

## Needs-Attention Findings

### [high] `make dev-up`(앱+DB 풀스택)이 컨테이너 네트워킹 문제로 기동 실패 가능성
- 위치: `docker-compose.dev.yml` (`app` 서비스), `.env.dev`
- 확인한 파일: `docker-compose.dev.yml`, `.env.dev`, `docker-compose.prod.yml`(대조군)
- 문제: `app` 서비스가 `.env.dev`를 `env_file`로 상속받는데, `.env.dev`의 `DB_URL`은 `jdbc:mariadb://localhost:3307/cms`로 되어 있다(IntelliJ에서 호스트 머신 기준 `bootRun`을 돌릴 때 쓰는 값). `docker-compose.dev.yml`의 `app.environment`는 `APP_FILE_STORAGE_ROOT`만 오버라이드할 뿐 `DB_URL`은 건드리지 않으므로, 컨테이너 내부에서도 이 값이 그대로 쓰인다. 컨테이너 안에서 `localhost`는 컨테이너 자기 자신을 가리키므로 `db` 서비스(도커 내부 네트워크, 포트 3306)에 연결할 수 없다. `docker-compose.prod.yml`은 같은 함정을 `DB_URL: jdbc:mariadb://db:3306/...`로 올바르게 서비스명 기반으로 오버라이드해 피하고 있어 dev/prod 처리 방식이 다르다는 점이 대조로 명확하다.
- 영향: `make dev-db`(DB 컨테이너만 띄우고 IntelliJ/로컬에서 `bootRun`)는 정상 동작하지만, Makefile이 제공하는 `make dev-up`(앱 컨테이너까지 포함한 원스톱 기동)은 `Connection refused`로 실패할 것으로 판단된다(Docker 네트워킹 원리상 코드만으로도 사실상 확정적 — 실행 재현은 하지 않음, 아래 "미확인" 참조). 포트폴리오 리뷰어가 README/Makefile만 보고 `make dev-up` 한 번으로 전체 스택을 띄우려 하면 여기서 막힌다.
- Recommendation: `docker-compose.dev.yml`의 `app.environment`에 `DB_URL: jdbc:mariadb://db:3306/cms`(또는 서비스명 기준 값)를 명시적으로 오버라이드해 `.env.dev`의 호스트용 값을 가리도록 수정. prod compose의 패턴을 그대로 따라가면 된다.

### [medium] README가 최근 기능(레이트리밋, prod 백업/복구, Spring Boot 실제 버전)을 반영하지 못함
- 위치: `README.md`
- 확인한 파일: `README.md`(최종 수정 8/3), `build.gradle`(Spring Boot 3.5.16), `application.yml`(`cms.rate-limit`), `docker-compose.prod.yml`, `Makefile`(`prod-backup`)
- 문제: README 배지는 "Spring Boot 3.4"라고 표기하지만 실제 `build.gradle`은 `3.5.16`이다. 또한 README에는 무인증 공개 엔드포인트 레이트리밋(2026-08-12 도입), prod DB/파일 백업·복구(`make prod-backup`), 세션 강제 만료, 로그인 실패 자동 잠금, 비밀번호 90일 만료, 감사 로그(AdminActionLog) 같은 — 이 프로젝트에서 가장 실무적으로 어필할 수 있는 기능들이 전혀 언급되지 않는다. "Security" 섹션도 "`/admin/**` 경로 보호, Role 기반 접근 제어" 수준의 매우 일반적인 문구에 그친다.
- 영향: 포트폴리오로 제출 시 README는 채용 담당자/리뷰어가 코드를 열어보기 전에 보는 첫 화면이다. 실제로 구현된 것(동시성 제어, 세션 무효화, 감사 로그, 레이트리밋 등)이 README에 드러나지 않으면 프로젝트의 실질적인 깊이가 저평가된다.
- Recommendation: README에 "주요 기능" 섹션을 추가해 인증/인가, 감사 로그, 동시성 제어, 레이트리밋, 파일 스토리지 이관 등 실제로 구현된 항목을 나열하고, 배지 버전을 `build.gradle` 기준으로 갱신한다. (코드 수정이 아니라 문서 갱신이므로 별도 작업으로 진행 권장)

### [low] 레거시 프로필 이미지(`LEGACY_INLINE`)의 Base64 원본이 목록 응답 DTO에도 그대로 재사용됨
- 위치: `src/main/java/com/cms/admin/member/dto/response/AdminMemberResponse.java:26` 부근 (`profileImageUrl` 필드)
- 확인한 파일: `AdminMemberResponse.java`, `AdminMemberService.java`(목록 조회 경로), `com.cms.admin.member`의 CLAUDE.md
- 문제: `AdminMemberResponse`는 상세 조회뿐 아니라 회원 목록(페이지) 응답에도 동일하게 재사용되는데, 화이트리스트 밖 MIME(webp 등)으로 FileStorage 이관에 실패해 `LEGACY_INLINE` 상태로 남은 행은 이 DTO의 `profileImageUrl`에 `data:image/...;base64,...` 원본 문자열을 그대로 담는다.
- 영향: 이미 CLAUDE.md에 "가용성 우선 정책"으로 명시적으로 수용된 기존 기술부채이며 신규 결함은 아니다. 다만 레거시 행이 목록 페이지에 여러 건 섞이면 목록 API 응답 크기가 비정상적으로 커질 수 있어, 운영 규모가 커지기 전에 인지해둘 필요는 있다.
- Recommendation: no-ship 사유 아님. 목록 응답 DTO는 별도로 분리해 Base64 원본을 제외하거나, LEGACY_INLINE 행을 목록에서는 플레이스홀더로 대체하는 개선을 백로그로 남기는 정도면 충분.

### [low] AdminActionLogAspect의 클라이언트 IP 로깅이 `X-Forwarded-For`/`X-Real-IP`를 신뢰
- 위치: `src/main/java/com/cms/admin/log/aspect/AdminActionLogAspect.java:101-112`
- 문제: 감사 로그(`AdminActionLog.requestIp`)에 기록되는 IP는 `X-FORWARDED-FOR`/`X-Real-IP` 헤더를 우선 사용한다. 반면 레이트리밋(`RateLimitFilter`)은 CLAUDE.md에 명시된 대로 위조 가능성 때문에 `request.getRemoteAddr()`만 신뢰한다.
- 영향: 이 프로젝트에 리버스 프록시가 없는 현재 구성(prod compose가 앱을 직접 8080에 바인딩)에서는 이 헤더들이 클라이언트에 의해 임의로 위조될 수 있어, 감사 로그의 IP 컬럼이 실제 공격자 추적에 오히려 혼선을 줄 수 있다. 다만 이는 감사 로그의 참고 필드일 뿐 인가 판단에 쓰이지 않으므로 보안 차단 항목은 아니다.
- Recommendation: 리버스 프록시를 앞단에 두기 전까지는 이 필드도 `getRemoteAddr()`로 통일하거나, 프록시 도입 시점에 신뢰할 IP 목록(프록시 자체 IP)만 헤더를 신뢰하도록 조건을 추가한다. 급하지 않음 — 감사용 참고 필드이므로 다음 라운드에 처리해도 무방.

## 상세 확인 결과 (점검 항목별)

### 1. 릴리스 차단 후보 항목 — 전부 문제없음으로 확인
- **TestMemberLoader가 dev 밖에서 실행될 가능성**: `@Profile("dev")` 명시, `AdminBootstrapLoader`는 `@Profile("prod")` — 프로파일 미지정 시 `ProfileGuardEnvironmentPostProcessor`가 기동 자체를 차단하므로 둘 다 실행되지 않는 제3의 상태는 없음. **문제없음.**
- **인증 없이 `/admin/**` 접근**: `SecurityConfig`의 matcher 순서를 전부 읽어 확인 — 구체적 경로(로그인/재설정/swagger/member-info/notice)가 먼저 오고 `/admin/**` 캐치올이 마지막에 위치, `com.cms.config`의 CLAUDE.md 표와 실제 코드가 완전히 일치. **문제없음.**
- **ROLE_MANAGER의 ADMIN 전용 기능 접근**: URL 레벨(`SecurityConfig`)과 메서드 레벨(`@PreAuthorize`) 이중 확인 — `AdminMemberController`의 `createAdmin`/`getAdminMembers`/`updateAdminMember` 등은 `hasRole('ADMIN')`, MANAGER는 `/me/**`·공지 관리만 허용. 메뉴 `accessRole`은 사이드바 노출 전용이며 실제 차단은 Security가 담당함을 코드로 확인. **문제없음.**
- **CSRF 보호 구멍**: 상태 변경 경로에 CSRF 예외(`ignoringRequestMatchers` 등) 없음 — `SecurityConfig`에 CSRF 비활성화 설정 자체가 없어 Spring Security 기본값(전 경로 활성)이 그대로 적용됨. **문제없음.**
- **평문 저장**: `Member.pwd`는 `BCryptPasswordEncoder`로만 인코딩, `resetToken`은 `PasswordResetService.sha256Hex()`로 해시 후 `issueResetToken()`에 전달 — 평문 토큰은 메일 발송 경로에서만 잠깐 존재하고 로그 출력은 코드 전체에서 금지·마스킹 처리됨. **문제없음.**
- **시크릿 하드코딩**: `.env.dev`/`.env.prod` 모두 `git ls-files`로 미추적 확인(`.gitignore`의 `.env*` + `.env.example` 예외). `.env.dev`의 값도 `1234`/`your-email@gmail.com` 등 명백한 더미값. `docker-compose.prod.yml`은 `${VAR:?필수 환경변수입니다}` 패턴으로 미주입 시 명시적 실패. **문제없음.**
- **ddl-auto**: `application.yml` 전 프로파일 공통으로 `validate` 고정. **문제없음.**
- **머지된 마이그레이션 수정**: V1~V11 전체를 확인 — 각 파일이 정확히 1개 커밋에서만 생성되고 이후 수정 이력 없음. **문제없음.**
- **감사 로그 REQUIRES_NEW**: `AdminActionLogService.log()`에 `@Transactional(propagation = Propagation.REQUIRES_NEW)` 확인, Aspect의 `logSuccess`/`logFailure`도 try-catch로 예외 격리해 원 요청 결과에 영향 없음. **문제없음.**
- **Swagger 노출 범위**: dev `SecurityConfig`에서 `hasRole('ADMIN')`, prod `application-prod.yml`에서 `springdoc.api-docs.enabled=false`+`swagger-ui.enabled=false`로 핸들러 자체 미등록. **문제없음.**
- **로그인/관리자 핵심 플로우**: 코드 정적 확인 기준 깨질 흔적 없음(아래 "미확인" 항목에 실기동 검증 필요 부분 기재).

### 2. 보안 점검
- 인증/인가 실패 핸들러: `SecurityConfig.exceptionHandling`에서 `API_MATCHER` 기준으로 API는 `ApiAuthenticationEntryPoint`(401)/`ApiAccessDeniedHandler`(403), 페이지는 `LoginUrlAuthenticationEntryPoint`/`AccessDeniedHandlerImpl` — 계약대로 구현됨. CSRF 실패가 미인증 상태에서 `AccessDeniedException`으로 오는 케이스도 401로 재분류하는 코드까지 확인.
- 세션 강제 만료: `AdminSessionRevokeListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 구현, 실패 시 로그만 남기고 예외 전파하지 않음(문서화된 best-effort 계약과 일치).
- `ACTIVE`만 로그인 가능: `CustomUserDetailsService.validateMemberStatus()`가 DISABLED/DELETED/LOCKED/PASSWORD_EXPIRED를 각각 다른 예외로 분기 처리, 그 외 상태는 fail-closed(DisabledException). BCrypt 확인됨.
- 최후 활성 ADMIN 가드: `AdminMemberService.updateAdminMember()`에서 `findActiveAdminIdsForUpdate()`로 행 잠금 후 남은 활성 ADMIN 수가 1 미만이면 409. 회원 삭제(하드 delete) API 자체가 없어(`AdminMemberController`에 DELETE 없음) 이 가드를 우회할 경로도 없음.
- actuator: `application.yml`에서 `management.endpoints.web.exposure.include: health` 전 프로파일 공통 + `SecurityConfig`의 `/actuator/health` permitAll·`/actuator/**` denyAll 이중 방어. `ActuatorExposureTest`가 `WebEndpointsSupplier`로 "실제 등록된 엔드포인트가 health 하나뿐"임을 직접 검증 + prod yml이 management 프로퍼티를 오버라이드하지 않음을 확인.
- CORS: 코드 전체에서 `CorsConfiguration`/`addCorsMappings`/`@CrossOrigin` 검색 결과 0건 — Thymeleaf 서버 렌더링 + 동일 출처 fetch 구조라 CORS 설정 자체가 불필요한 아키텍처. **문제없음(설정 부재가 곧 정답).**
- RateLimitFilter 위치: `RateLimitFilterConfig`가 `FilterRegistrationBean.setEnabled(false)`로 서블릿 컨테이너 자동 등록을 차단하고, `SecurityConfig.addFilterAfter(rateLimitFilter, CsrfFilter.class)`로만 등록 — 이중 등록 위험 없음, CsrfFilter 이후 위치도 코드로 확인.
- 메서드 보안: `@EnableMethodSecurity`(`MethodSecurityConfig`) 활성화, `AdminMemberController`의 모든 액션에 `@PreAuthorize` 부착 확인.

### 3. API 계약 점검
- `GlobalApiExceptionHandler` 전수 확인: `VALIDATION_ERROR`(400), `JSON_PARSE_ERROR`(400), `INVALID_REQUEST`(400), `DUPLICATE_RESOURCE`(409, 유니크 제약 위반 `DataIntegrityViolationException`의 제약명 매핑 포함), `RESOURCE_CONFLICT`(409, 낙관적/비관적 락 경합), `RESOURCE_NOT_FOUND`(404, 핸들러 없는 경로 포함), `ACCESS_DENIED`(403), `INTERNAL_ERROR`(500) — 상태 코드 팔레트가 일관되게 좁게 유지됨. `notice_attachment.storage_key` UNIQUE 위반도 동일 catch-all로 409 처리되나 메시지는 범용 — 사소.
- 요청 DTO Bean Validation: `@RequestBody`/`@ModelAttribute` 진입점 전수(NoticeController, MenuController, AdminMemberController, PasswordResetController 등)에 `@Valid` 부착 확인. `MethodArgumentNotValidException`/`BindException` 둘 다 400 `VALIDATION_ERROR`로 매핑.
- RESTful 네이밍: 복수형 리소스(`notices`, `members`, `menu`) + PATCH(부분수정)/DELETE/POST 일관 사용. `AdminActionLogController`의 `logs`(슬래시 없음)는 클래스 레벨 매핑과 결합되므로 실질적 문제 아님. 세부 항목별 완전 대조는 지향 규칙이라 no-ship 사유 아님.
- Swagger 문서와 실제 API 시그니처 차이는 **미확인**(아래 참조).

### 4. 도메인/DB/트랜잭션 점검
- Flyway V1~V11과 엔티티 필드 대조: `Member`(resetToken, profileImageKind 등 V11까지 반영), 마이그레이션 파일명과 최근 기능(로그인 잠금 V4, 비밀번호 만료 V5-V7, 공지 V8-V9, 첨부파일 V10, 프로필 이미지 V11)이 git 커밋 이력과 1:1 대응. `password_changed_at`은 V5(nullable)→V7(NOT NULL) 2단계 전환 이력이 엔티티(`nullable=false`)와 정확히 일치.
- Enum 저장 방식: 전 엔티티 `@Enumerated(EnumType.STRING)` 사용, `ORDINAL` 0건 — 순서 변경으로 인한 데이터 깨짐 위험 없음.
- 트랜잭션 경계/readOnly: 조회 전용 메서드(`getMenu`, `getMenuTree`, `getSidebarMenus`, `AdminActionLogQueryService` 등)에 `readOnly=true` 일관 적용. `DashboardService`만 서비스 레벨 `@Transactional` 미사용인데, "커밋 시점 예외까지 통제하기 위한 의도적 설계"임이 코드 주석에 명시 — 결함 아님.
- `Member`에 `@DynamicUpdate` — 변경 컬럼만 UPDATE, 벌크 UPDATE(자동 잠금)와의 더티체킹 경합 방지 목적이 주석과 코드에서 일치.
- 사이드바 메뉴 조회(`MenuService.getSidebarMenus`): `upMenuNo`가 단순 Long 필드(FK 연관관계 아님)라 지연 로딩 N+1 자체가 발생할 구조가 아님. 단일 쿼리로 전체 활성 메뉴를 가져온 뒤 메모리에서 트리 조립. `NoticeAttachment`도 plain `Long noticeId` 컬럼만 사용(주석에 "N+1 회피" 명시). **N+1 없음.**
- 소프트 삭제 정책: Menu는 `useYn` 하나로 노출/삭제 겸용, Notice는 `useYn`(노출)과 `deleted`(삭제) 분리 — 두 CLAUDE.md 모두 이 차이를 설계 결정으로 명시, 의도된 도메인별 정책.
- 프로필 이미지: `com.cms.admin.member`의 CLAUDE.md에 따라 Base64-in-DB에서 FileStorage 실파일 저장으로 이관 완료(2026-08-10). 응답에는 짧은 다운로드 URL만 포함되며, 화이트리스트 밖 MIME으로 이관 실패한 레거시 행만 예외적으로 `LEGACY_INLINE`으로 Base64 유지 — 의도된 가용성 우선 정책. 단, 목록 응답 DTO에도 그대로 재사용되는 점은 위 Needs-Attention 항목 참조.

### 5. 화면(Thymeleaf) 점검
- 골든 패스 정적 성립: `AdminMainController`(/admin, /admin/login 등), `NoticePageController`, `MenuPageController`, `AdminMemberPageController`, `AdminActionLogPageController` 모두 매핑-템플릿 1:1 대응 확인.
- 사이드바 노출과 URL 차단 분리: 사이드바는 `Menu.access_role`(표시용 DB 데이터) 기반, 실제 차단은 `SecurityConfig`(URL matcher, 코드 레벨)가 별도로 수행 — CLAUDE.md 문서화된 접근 제어 표와 코드가 정확히 일치.
- `@AdminPage` 컨벤션: `AdminPageAnnotationConventionTest`가 `com.cms.admin` 패키지 전체를 classpath 스캔해 `@Controller`이면서 `@RestController`/`@AdminPage` 둘 다 없는 클래스를 탐지 — 실제 전수 검사 로직 확인됨. 현재 페이지 컨트롤러 전부 부착됨.
- 깨진 링크: 정적 템플릿의 `th:href` 전수 확인 + 사이드바는 DB 시드(`V3__seed_default_menus.sql`, `V9__seed_notice_menu.sql`) 기반인데, 시드된 `menu_url` 전부가 실제 컨트롤러 매핑과 일치. **없음.**
- CSRF 헤더: `static/js/`에는 상태 변경 fetch 호출이 없고(순수 데모/차트 스크립트만 존재), 실제 상태 변경 fetch는 `templates/admin/**/*.html`의 인라인 스크립트에 위치 — `admin-manage.html`, `menu/manage.html`, `notice/manage.html`, `admin-create.html`, `admin-my-info.html`, `password-reset-confirm.html` 등 8개 템플릿 전체에서 POST/PUT/PATCH/DELETE 호출마다 `meta[name="_csrf"]` 값을 읽어 헤더에 포함시키는 패턴(개별 선언 또는 `getCsrfHeaders()`/`getCsrfHeaderOnly()` 헬퍼)을 확인. 헤더 렌더링은 `admin/fragments/head.html`(공용 프래그먼트)이 담당, 비관리자 공개 페이지(`password-reset-confirm.html`)는 자체 meta 태그를 별도로 가짐. **누락 없음.**

### 6. 테스트/CI 점검
- 인증/인가/CSRF 테스트: `SecurityConfigTest`(45개 `@Test`), `ApiSecurityConfigTest`(8개), `RateLimitFilterTest`(7개) 등 다수 존재. `AdminSessionRevocationIntegrationTest`, `LoginFailureLockoutIntegrationTest`, `PasswordExpiryIntegrationTest` 등 통합 테스트로 Security Filter Chain을 포함한 실동작을 검증.
- CI(`ci.yml`): `./gradlew test`를 `SPRING_PROFILES_ACTIVE=dev`로 실행. 별도 MariaDB service container는 없으나, `MariaDbContainerSupport`(Testcontainers)가 테스트 내부에서 자체 컨테이너를 띄우므로 CI 러너에 Docker만 있으면 되고(GitHub Actions ubuntu-latest는 Docker 기본 탑재) service container 미구성이 결함이 아니다. `build.gradle`의 `maxParallelForks=1`/`forkEvery=0`으로 워커당 컨테이너 1개 보장.
- `ActuatorExposureTest`가 실제 등록 엔드포인트를 `WebEndpointsSupplier`로 직접 검증하는 등, "HTTP 응답만으로 판단하기 애매한" 계약까지 테스트가 커버하는 수준 높은 패턴을 사용 중.
- 느슨한 assertion: 이번 표본 조사 범위에서는 **미발견**(전체 파일의 assertion 밀도까지 완전 보증하는 전수조사는 아님).
- Playwright/E2E로만 확인 가능한 항목(코드 리뷰 범위 밖): 실제 로그인→대시보드→각 관리 화면 렌더링(JS 콘솔 에러 없음, 모달/차트 정상 동작), 사이드바 active 하이라이트/collapse UI 동작, 프로필 이미지 업로드·다운로드 실제 파일 I/O 왕복, 429(rate limit) 응답 시 프론트 UX.

### 7. 설정/빌드 점검
- `build.gradle`: Spring Boot `3.5.16`("3.5.x" 부합), QueryDSL `5.1.0:jakarta`, Flyway는 Boot BOM 관리(flyway-core + flyway-mysql, 버전 미고정은 의도적), MariaDB 드라이버 포함. CLAUDE.md 기술과 일치.
- `application.yml`/`application-dev.yml`/`.env.dev`: 포트 3307(`docker-compose.dev.yml`의 `3307:3306` 매핑과 일치), `SPRING_PROFILES_ACTIVE` 기본값 없음(의도) 확인.
- **`make dev-up`(docker-compose.dev.yml 풀스택) 설정 버그**: 위 Needs-Attention [high] 항목 참조. `make dev-db`(DB만 기동 후 IntelliJ bootRun)는 정상 동작.
- `docker-compose.prod.yml`: 루프백 전용 포트 바인딩(`127.0.0.1:8080`), `${VAR:?...}` 필수 변수 강제, `ADMIN_BOOTSTRAP_*` 3종만 선택 입력(주석에 이유 명시: 이미 ACTIVE ADMIN이 있는 정상 운영 환경에서 무조건 요구하면 기동을 막기 때문). `DB_URL`은 서비스명(`db:3306`) 기준으로 올바르게 오버라이드됨.
- 환경변수 목록: dev는 `DB_PASS`/`MAIL_USER`/`MAIL_PASS` 필수(기본값 없음), prod는 `DB_URL`/`DB_USER`/`DB_PASS`/`MAIL_USER`/`MAIL_PASS`/`APP_BASE_URL`/`APP_FILE_STORAGE_ROOT` 전부 필수 — `.env.example`·`docs/deployment.md`와 완전 일치.
- 메일(SMTP): `spring.mail.username/password`가 `${MAIL_USER}`/`${MAIL_PASS}`로 기본값 없이 선언되어 있으나, `management.health.mail.enabled: false`로 actuator에서는 격리됨. `.env.dev`에 더미값이 있어 dev 기동 자체는 막지 않음. 미주입 시 기동 실패 여부는 미확인(아래 참조).
- 로그/타임존: `AppConfig.clock()`이 `Asia/Seoul` 고정 — CLAUDE.md의 KST 단일 시간원 주장과 일치, `VisitLog`·`AdminActionLog` 등 여러 곳에서 일관되게 사용됨을 확인. 로깅 레벨 설정은 `application*.yml` 어디에도 없음(Boot 기본값 사용) — 결함은 아니나 운영 관찰성 백로그로 아래 기재.
- prod yml: `ddl-auto: validate`(공통 상속), `springdoc.api-docs.enabled=false`+`swagger-ui.enabled=false` 확인. 시크릿은 전부 `${VAR}` 참조뿐 하드코딩 없음.

## 운영 준비 공백 (Pre-Prod Backlog)

no-ship 사유는 아니지만, **실제 인터넷 도메인으로 서비스를 열기 전** 반드시 처리해야 할 항목:

- **리버스 프록시 + TLS 미구축**: `docker-compose.prod.yml`이 의도적으로 `127.0.0.1:8080`에만 바인딩 중 — Nginx/Caddy 등 TLS 종단 프록시 도입이 실 배포의 선행 조건. `docs/deployment.md`에 이미 "별도 사안"으로 명시되어 있어 계획된 공백임.
- **AdminActionLogAspect의 IP 로깅이 프록시 헤더를 무조건 신뢰**(Needs-Attention 항목 참조) — 리버스 프록시 도입 시점에 신뢰 소스를 프록시 IP로 한정하는 로직이 필요.
- **메일 발송 실기동 미검증**: 비밀번호 재설정 메일이 실제 SMTP(Gmail) 계정으로 발송되는지는 코드 리뷰만으로 확인 불가 — 운영 오픈 전 `.env.prod`의 `MAIL_USER`/`MAIL_PASS`로 실제 발송 테스트 필요.
- **중앙 로그 수집/모니터링 전략 부재**: 현재 로깅은 콘솔 기본 출력뿐이고, 중앙 로그 수집(ELK 등)·파일 롤링·알림 전략이 문서·코드 어디에도 없다. actuator도 `health`만 노출되어 애플리케이션 메트릭 관측 수단이 없다. 실제 인터넷 배포 전 필수 과제로 분류.

## 미확인 항목

- **로그인 → 메인 → 관리자/메뉴 관리 화면 골든 패스 실동작**: 정적 코드 검토로는 컴파일·라우팅 정합성까지만 확인 가능. 확인 방법: `make dev-up`(위 버그 수정 후) 또는 `make dev-db`+`bootRun`으로 스택 기동 후 `http://localhost:8080/admin/login` 접속, 또는 playwright MCP로 로그인 → 대시보드 → 메뉴 관리 플로우 클릭 검증.
- **`make dev-up` 실행 시 실제 `Connection refused` 재현**: Docker 네트워킹 원리상 실패가 사실상 확정적이나, 이번 점검 환경에는 Docker 데몬이 가동되어 있지 않아 직접 재현하지 못했다. 확인 방법: Docker 실행 후 `make dev-up` → `docker logs cms-app-dev`로 연결 오류 로그 확인.
- **`./gradlew test` 실제 통과 여부**: 이 환경에는 Docker 데몬이 실행되어 있지 않아(`docker version` 호출 시 연결 오류) Testcontainers 기반 테스트를 이번 점검에서 직접 실행하지 못했다. 코드 정적 검토로는 테스트 커버리지·의도가 문서와 일치함을 확인했으나 실제 그린 여부는 미확인. 확인 방법: Docker Desktop 실행 후 `./gradlew test`(환경변수 없이, `build.gradle`의 `test` 태스크가 `SPRING_PROFILES_ACTIVE=dev`를 자동 주입).
- **MAIL_USER/MAIL_PASS 미주입 시 기동 실패 여부**: `spring.mail.username/password`가 기본값 없는 placeholder라 미주입 시 `PropertySourcesPlaceholderConfigurer`가 기동을 막을 가능성이 높으나, `spring-boot-starter-mail`의 자동설정 관용도에 따라 예외일 수 있어 코드만으로 단정하지 않았다. 확인 방법: `.env.dev`에서 `MAIL_USER`/`MAIL_PASS`를 일시적으로 제거하고 `make dev-up`(또는 `bootRun`)을 시도해 실패/성공 로그 확인.
- **Swagger 문서와 실제 API 시그니처 차이**: 컨트롤러별 `@Operation` 부착 여부가 산재해 전수 대조하지 못했다. 확인 방법: `./gradlew bootRun` 후 `/swagger-ui.html`에서 실제 스펙과 컨트롤러 시그니처를 직접 비교.

## Final Verdict

**ship** — 릴리스(운영 환경 구축) 차단 항목 없음. 발견된 4건의 Needs-Attention 중 3건은 경미(문서 최신화, 응답 크기 백로그, 로그 필드의 신뢰 소스)하며, 1건(`make dev-up` DB_URL 버그)은 실사용 편의성 문제로 즉시 릴리스를 막을 사유는 아니지만 포트폴리오 제출 전 고쳐두는 것을 권장한다. 운영 준비 공백 4건은 "실제 인터넷 도메인 오픈" 시점의 선행 과제로 이미 프로젝트 문서(`docs/deployment.md`)에 그 경계가 명확히 그어져 있다.

**포트폴리오 제출 관점 결론**: 코드 품질·보안 설계·테스트 커버리지·설계 문서화(CLAUDE.md, adversarial-review 계획 문서) 수준은 이미 제출 가능한 수준을 크게 상회한다. 실질적으로 손볼 부분은 두 가지로 좁혀진다 — (1) README가 프로젝트의 실제 기술적 깊이(동시성 제어, 감사 로그, 세션 무효화, 레이트리밋, 파일 스토리지 이관 등)를 드러내지 못하는 점, (2) `make dev-up`의 `DB_URL` 오버라이드 누락으로 원스톱 실행 경로가 깨질 수 있는 점. 두 가지 모두 수정 난이도가 낮으므로 제출 전 처리를 권장한다.
