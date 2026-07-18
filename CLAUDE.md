# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 브랜치 전략

**GitHub Flow** 채택. `master`는 항상 배포 가능 상태로 유지하고 직접 push하지 않는다.

- 브랜치 접두사: `feat/` · `fix/` · `refactor/` · `security/` · `test/` · `chore/` + kebab-case
- 작업 완료 시 PR → CI 통과(`./gradlew test`) → Squash merge → 브랜치 삭제
- 상세 규칙: `docs/branching.md` 참고
- CI: `.github/workflows/ci.yml` (PR 및 master push 시 자동 실행, MariaDB service container 포함)

## 빌드 및 실행 명령어

```bash
# 빌드
./gradlew build

# 애플리케이션 실행 (로컬)
./gradlew bootRun

# 테스트 전체 실행
./gradlew test

# 특정 테스트 클래스 실행
./gradlew test --tests "com.cms.admin.member.service.AdminMemberServiceTest"

# 특정 테스트 메서드 실행
./gradlew test --tests "com.cms.admin.member.service.AdminMemberServiceTest.메서드명"

# QueryDSL Q클래스 생성 (코드 변경 후 필요)
./gradlew compileJava
```

### Docker 환경

```bash
# 개발용 DB만 시작 (로컬 개발 시 권장)
make dev-db

# 전체 개발 스택 시작
make dev-up

# 개발 환경 중지
make dev-down

# 앱/DB 로그 확인
make logs-app
make logs-db
```

## 아키텍처 개요

Spring Boot 3.5.16 기반 관리자 CMS로, 계층화된 MVC 패턴을 따른다. 의존 방향은 **Controller → Service → Repository → Entity** 단방향을 유지한다.

### 레이어 구조

```
Presentation  →  Controller (REST API) + Thymeleaf 페이지 컨트롤러
Business      →  Service
Data Access   →  Repository (Spring Data JPA + QueryDSL)
Domain        →  Entity
Cross-cutting →  Security, AOP 로깅, 전역 예외 처리
```

- **REST API Controller**: `/admin/api/**` 경로, `@RestController`
- **페이지 Controller**: Thymeleaf 뷰 반환, `@Controller` (예: `AdminMainController`, `AdminMemberPageController`)
- **QueryDSL 동적 쿼리**: `*RepositoryImpl` 클래스에 구현 (`MemberRepositoryImpl`)
- **전역 예외 처리**: `GlobalApiExceptionHandler` (`@RestControllerAdvice`)

### 주요 패키지

```
com.cms/
├── admin/                   # 도메인별 기능 + 공통 페이지 컨트롤러
│   ├── AdminMainController  # /admin, /admin/login, /admin/login-error 페이지 서빙
│   ├── AdminViewAdvice      # admin 패키지 전체에 공통 모델 속성 주입 (currentAdminName, currentAdminProfileImageUrl)
│   ├── AdminPage            # Thymeleaf 페이지 컨트롤러 마커 어노테이션 — 새 페이지 컨트롤러에 필수 부착 (누락은 AdminPageAnnotationConventionTest가 감지)
│   ├── AdminSidebarAdvice   # @AdminPage 컨트롤러에만 사이드바 모델 주입 (sidebarMenus, currentUri) — REST API 요청에 메뉴 DB 조회가 나가지 않도록 범위 제한
│   ├── member/              # 관리자 계정 관리 (핵심 도메인) — 생성·조회·자기수정·타 관리자 수정
│   │   └── controller/      #   - AdminMemberController (REST API, /admin/api/*)
│   │                        #   - AdminMemberPageController (Thymeleaf: /admin/member/{new,manage,info})
│   ├── log/                 # 관리자 활동 로그 (AOP 기반, `AdminActionLogRepository`)
│   └── menu/                # 메뉴 도메인 (CRUD·트리 API + 관리 화면 + 동적 사이드바 완성. 기본 메뉴 시드는 Flyway V3 담당)
├── common/         # 공통 API 응답, 예외 클래스
├── config/         # Spring Security, QueryDSL 설정
│   ├── auth/       # 인증·인가 컴포넌트 (AdminSecurityService, CustomUserDetailsService, CustomUserDetails)
│   │               #   + 세션 강제 만료 (AdminSessionService, AdminSessionRevokeEvent/Listener — AFTER_COMMIT)
│   └── security/   # Security 필터 핸들러 (ApiAuthenticationEntryPoint, ApiAccessDeniedHandler, AdminSessionExpiredStrategy)
└── error/          # 커스텀 에러 처리 (CustomErrorController)
```

### AOP 기반 액션 로깅

`@AdminActionLogged` 어노테이션을 메서드에 붙이면 호출 성공/실패가 `AdminActionLog`에 자동 기록된다. `AdminActionLogAspect`가 처리한다.

- **독립 트랜잭션**: `AdminActionLogService.log()`는 `Propagation.REQUIRES_NEW`로 실행된다. 원 비즈니스 트랜잭션이 롤백되어도 FAIL 로그는 별도 트랜잭션으로 커밋된다.
- **예외 격리**: Aspect 내부에서 로그 저장 실패를 try-catch로 격리한다. 로그 저장이 실패해도 원 요청 결과(성공/실패)는 뒤집히지 않는다.

### 현재 로그인 관리자 정보 조회

`AdminSecurityService` (`com.cms.config.auth`)가 `SecurityContextHolder`에서 현재 인증된 관리자 정보를 꺼내는 역할을 담당한다. `AdminMemberService`의 모든 메서드에서 `getCurrentAdminId()`, `hasAdminAuthority()` 등을 통해 사용한다.

### 프로필 이미지

업로드한 이미지는 `data:<mime>;base64,...` 형태의 Base64 데이터 URI로 DB의 `LONGTEXT` 컬럼(`profile_image_url`)에 저장된다(2MB 이하, `image/*` 파일만 허용). 기본 프리셋 4종(`profile-default`, `profile-1`, `profile-2`, `profile-3`)은 Base64가 아니라 정적 리소스 경로(`/img/undraw_profile*.svg`)로 저장된다. 별도 파일 스토리지 없음.

## 코딩 컨벤션

- **DI는 생성자 주입**: `@RequiredArgsConstructor` + `final` 필드. 필드 주입(`@Autowired`) 금지.
- **Lombok**: 엔티티에는 `@Setter`/`@Data` 사용 금지(양방향 연관관계에서 순환 위험). 생성/변경은 `@Builder`와 의미 있는 도메인 메서드로 처리.
- **DTO 경계**: 엔티티를 Controller 응답으로 직접 노출하지 않는다. 항상 요청/응답 DTO로 변환한다.
- **검증**: 컨트롤러 진입 DTO에 Bean Validation(`@Valid`, `@NotNull` 등)을 적용한다. 열거형 값의 일부만 허용해야 할 경우 `@AllowedRoles` 같은 커스텀 `ConstraintValidator`를 사용한다 (`dto/request/validation/` 패키지). `@ModelAttribute` 바인딩 검증 실패(`BindException`)도 `GlobalApiExceptionHandler`가 400 VALIDATION_ERROR JSON으로 반환한다.
- **트랜잭션**: 비즈니스 로직과 트랜잭션 경계는 Service에 둔다. 조회 전용은 `@Transactional(readOnly = true)`.
- **QueryDSL 우선**: 동적 조건·복잡한 조인은 `@Query` 문자열보다 QueryDSL(`*RepositoryImpl`)로 작성한다.
- **예외 처리**: `GlobalApiExceptionHandler`(`@RestControllerAdvice`)를 통해 처리한다. 컨트롤러에서 try-catch를 남발하지 않는다.

## RESTful API 설계 규칙

이 프로젝트의 API는 RESTful 컨벤션을 따른다. 신규 엔드포인트는 아래 규칙을 기준으로 설계한다.

### URI 규칙

- **자원은 명사, 복수형, 소문자**로 표현한다. 동사를 URI에 넣지 않는다. (`/createMember` ✕ → `POST /members` ○)
- 다중 단어는 하이픈(`-`)으로 연결한다. (`/profile-image`)
- 컬렉션과 단일 자원을 구분한다.
    - 컬렉션: `/admin/api/members`
    - 단일 자원: `/admin/api/members/{id}`
- 현재 로그인 사용자(본인) 리소스는 `me` 별칭을 사용한다. (`/admin/api/members/me`)
- 하위 관계는 중첩 경로로 표현한다. (`/admin/api/members/me/profile-image`)

### HTTP 메서드 매핑

| 메서드 | 용도 | 성공 상태 코드 |
|--------|------|----------------|
| `GET` | 조회 (목록/단건) | 200 OK |
| `POST` | 생성 | 201 Created (+ `Location` 헤더) |
| `PUT` | 전체 교체 | 200 OK / 204 No Content |
| `PATCH` | 부분 수정 | 200 OK |
| `DELETE` | 삭제 | 204 No Content |

### 상태 코드 규칙

- `400` 검증 실패(`VALIDATION_ERROR`), JSON 파싱 오류(`JSON_PARSE_ERROR`), `401` 미인증, `403` 권한 없음, `404` 자원 없음, `409` 상태 충돌·중복(`DUPLICATE_RESOURCE`), `500` 서버 오류.
- DB 유니크 제약 위반(`DataIntegrityViolationException`)도 409 `DUPLICATE_RESOURCE`로 처리된다. `uk_member_user_id`·`uk_member_email` 위반 시 각각 사람이 읽을 수 있는 메시지로 응답한다.
- 컨트롤러까지 도달한 API 예외는 `GlobalApiExceptionHandler`를 통해 `common` 패키지의 공통 응답 포맷으로 반환한다.
- `@PreAuthorize` 위반으로 발생하는 `AccessDeniedException`은 `GlobalApiExceptionHandler.handleAccessDenied()`가 잡아 **JSON 403** (`ACCESS_DENIED`)으로 반환한다. 단, 이는 컨트롤러까지 도달한 요청에만 해당한다.
- `/admin/api/**` 경로는 Security Filter Chain 레벨에서 전용 핸들러가 처리한다. 미인증은 `ApiAuthenticationEntryPoint`(JSON 401 `UNAUTHORIZED`), 권한 부족은 `ApiAccessDeniedHandler`(JSON 403 `ACCESS_DENIED`)가 응답한다. HTML 리다이렉트나 기본 오류 페이지는 반환되지 않는다.
- `/admin/api/**` 이외의 경로(Thymeleaf 페이지 등)에서 발생하는 **401(미인증)**은 로그인 페이지로 리다이렉트된다.

### 목록 조회 파라미터

- 페이징·정렬·검색은 쿼리 파라미터로 전달한다. (`?page=0&size=20&sort=createdAt,desc&keyword=...`)
- 페이징 응답은 일관된 구조(콘텐츠 + 페이지 메타)를 유지한다.

## 환경 설정

- **개발**: `application-dev.yml` + `.env.dev`, MariaDB 포트 3307
- 기본 프로파일: `dev` (환경변수 `SPRING_PROFILES_ACTIVE`로 오버라이드 가능)
- **스키마 관리는 Flyway** (`src/main/resources/db/migration/`, `ddl-auto: validate`): 엔티티 변경만으로는 스키마가 바뀌지 않는다 — 컬럼/인덱스 추가·변경 시 반드시 마이그레이션 파일을 함께 작성한다. 머지된 마이그레이션 파일은 수정 금지(체크섬 불일치로 기동 실패). 기존 DB 전환·새 마이그레이션 작성 규칙은 `docs/migration-guide.md` 참고.
- 기본 관리자 계정: `dev` 프로파일에서 회원이 없으면 `TestMemberLoader`가 `userId=admin` / `pwd=1234`(BCrypt) ROLE_ADMIN 계정을 자동 생성한다. 최초 계정이 이미 있는 경우에는 `POST /admin/api/members`(관리자 인증 필요)로 추가 생성한다.
- 민감 정보(DB 비밀번호, 메일 계정, 시크릿)는 코드에 하드코딩하지 않고 프로파일/환경변수로 분리한다.

## 보안 규칙

`SecurityConfig`에 정의된 접근 제어:

| 경로 | 접근 |
|------|------|
| `/admin/login`, `/admin/login-error` | 공개 |
| `/admin/password-reset`, `/admin/password-reset/confirm` | 공개 (비밀번호 재설정 페이지, 2026-07-13 승인) |
| `/admin/api/password-reset-requests`, `/admin/api/password-resets` | 공개 (비밀번호 재설정 API — CSRF 토큰은 필요) |
| `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**` | `ROLE_ADMIN` 필수 |
| `/admin/**` | `ROLE_ADMIN` 필수 |
| `/actuator/health`, `/actuator/info` | 공개 (별도 `requestMatchers` 없음 — `anyRequest().permitAll()`에 포함) |
| 그 외 모든 경로 | 공개 (`anyRequest().permitAll()`) |

- `ACTIVE` 상태 계정만 로그인 가능 (`CustomUserDetailsService`). 연속 5회 로그인 실패 시 자동 잠금(30분 해제) — 정책 상세는 "핵심 도메인 모델 > Member" 참조 (2026-07-14 승인)
- **세션 등록·강제 만료**: `sessionManagement(maximumSessions(-1))` + `SessionRegistry` + `HttpSessionEventPublisher` 활성 (동시 로그인 제한 없음 — 세션 추적만). 타 관리자 수정으로 상태·권한이 실변경되면(멱등 재잠금 LOCKED/DISABLED 동일값 포함) `AdminSessionRevokeEvent`가 발행되고 커밋 후 `AdminSessionRevokeListener`(AFTER_COMMIT)가 대상자 세션을 만료 처리한다. 만료된 세션의 다음 요청은 `AdminSessionExpiredStrategy`가 API는 JSON 401, 페이지는 `/admin/login` 리다이렉트로 응답. **계약은 best-effort** — 즉시 접근 차단 수단이 아니며, 극단적 커밋 경합 시 기존 세션이 세션 타임아웃(기본 30분) 또는 재잠금까지 유효할 수 있다.
- CSRF는 **모든 경로에 활성**이다. `/admin/api/**`를 포함한 상태 변경 요청(POST/PATCH/PUT/DELETE)은 반드시 CSRF 토큰을 포함해야 한다. Thymeleaf 페이지는 `head.html` 프래그먼트가 `<meta name="_csrf">` 태그로 토큰을 렌더링하며, JS에서 이 값을 읽어 `X-CSRF-TOKEN` 헤더로 전송한다. 신규 상태 변경 fetch 호출 작성 시 CSRF 헤더를 누락하지 않는다.
- 메서드 레벨 보안: `MethodSecurityConfig`에서 활성화
- 비밀번호는 항상 `PasswordEncoder`로 인코딩하며 평문 저장 금지.
- **인가 정책(URL 권한, 로그인 정책)은 사전 협의 없이 변경하지 않는다.** 변경이 필요하면 먼저 제안한다.

## 핵심 도메인 모델

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

**Member** (관리자 계정)
- `Role`: `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_USER` / `MemberStatus`: `ACTIVE`, `LOCKED`, `DISABLED`, `DELETED`, `PASSWORD_EXPIRED`
- **비밀번호 재설정 구현 완료** (`PasswordResetService`): 이메일로 재설정 링크 발송(토큰은 URL fragment `#token=`) → 토큰 검증 → 재설정. 토큰은 SHA-256 해시로만 저장(평문·해시 모두 **로그 출력 금지** — 예외 객체 통째 로깅도 금지), 30분 TTL·일회용·60초 재발급 쿨다운(발급 시 계정 행 잠금으로 원자성 보장). 계정 존재 여부와 무관하게 항상 200 응답(열거 방지), 대상은 `ACTIVE`/`PASSWORD_EXPIRED` + `ROLE_ADMIN`/`ROLE_MANAGER` allowlist. 재설정 성공 시 기존 세션 만료(`AdminSessionRevokeEvent`) + `PASSWORD_EXPIRED`는 `ACTIVE` 복귀. **모든 비밀번호 변경 경로(`Member.changePassword()`)가 outstanding reset 토큰을 함께 클리어**한다. 상세 설계 결정은 `adversarial-review/plan/PLAN-password-reset.md` 참조
- **로그인 실패 잠금 구현 완료** (`LoginFailureService`, 2026-07-14): 연속 5회 실패(`BadCredentialsException`만 카운트) 시 `LOCKED` 자동 전이 + **30분 후 lazy 자동 해제**(로그인·비밀번호 재설정 진입점에서 조건부 벌크 UPDATE). 대상은 `ROLE_ADMIN`/`ROLE_MANAGER` allowlist — `ROLE_USER`는 오잠금되지 않는다. `locked_at null = 수동 잠금(영구)`, `locked_at 존재 = 자동 잠금(30분 해제)`로 구분되며, `changeStatus()`는 항상 `locked_at`을 정리한다. 잠금 전이는 `AdminAccountAutoLockEvent`(AFTER_COMMIT)로 `AdminActionLog`(`ACCOUNT_AUTO_LOCK`) 감사 기록 + 기존 세션 만료(`AdminSessionRevokeEvent`, 발행 순서상 세션 만료가 감사보다 먼저). 성공 핸들러(`VisitLoggingAuthenticationSuccessHandler`)는 인증 완료 직전 fresh 상태·역할·비밀번호 해시를 재확인해 불일치·예외 시 **fail-closed 거부**(경합으로 잠긴/강등된/구 비밀번호 세션 차단). **내 비밀번호 변경 성공 시 전 세션 폐기**(본인 포함 — 재로그인 필요). `Member`에 `@DynamicUpdate` 부착(더티체킹 경합의 잠금 소실 차단). 최후 ADMIN 잠금 복구는 `docs/troubleshooting.md` 참조. 상세 설계 결정은 `adversarial-review/plan/PLAN-login-failure-lockout.md` 참조
- **비밀번호 90일 만료 구현 완료** (`PasswordExpiryService`, 2026-07-18): `password_changed_at`이 90일에 도달한 `ACTIVE` + `ROLE_ADMIN`/`ROLE_MANAGER` 계정을 로그인 시점에 `PASSWORD_EXPIRED`로 전이(조건부 벌크 UPDATE, 로그인 트랜잭션 참여 + `noRollbackFor`로 전이 커밋 유지). 성공 핸들러(`VisitLoggingAuthenticationSuccessHandler`)도 성공 처리 직전 만료를 재판정한다(인증 중 90일 경계 통과 TOCTOU 차단). 만료 로그인 거부는 실패 카운트를 증가시키지 않는다(`CredentialsExpiredException`). **모든 비밀번호 변경 경로(`Member.changePassword()`)가 `passwordChangedAt` 갱신 + `PASSWORD_EXPIRED → ACTIVE` 복귀를 수행**(재설정·내 비밀번호 변경 공통 — 살아있는 세션이 만료 후 비밀번호를 바꿔도 고착되지 않음). 배치/스케줄러 없음. 관리자가 수동으로 `ACTIVE` 복구해도 비밀번호 미변경이면 다음 로그인 때 재만료(의도된 동작). 상세 설계 결정은 `adversarial-review/plan/PLAN-password-expiry.md` 참조

**Menu**
- `MenuAccessRole`: `ALL`(공용, ADMIN·MANAGER 노출) / `ADMIN`(ADMIN 전용 노출). DB 컬럼 null은 ALL로 정규화(레거시 행 호환)
- 사이드바는 `AdminSidebarAdvice` → `MenuService.getSidebarMenus()`가 활성 메뉴를 역할 필터링해 동적 렌더링한다. SB Admin 2 UI 제약으로 **2단(최상위 + 직계 하위)까지만** 그린다
- 기본 메뉴 시드는 Flyway `V3__seed_default_menus.sql`이 담당한다 — menu 테이블이 **완전히 비었을 때만** 전체 시드하며, 행이 하나라도 있으면 건드리지 않는다 (보충 기능 없음)
- `accessRole`은 사이드바 **노출** 제어일 뿐이며, 실제 접근 차단은 Security(`@PreAuthorize` 등)가 담당한다

**AdminActionLog**: 관리자 행위 감사 로그. `@AdminActionLogged`가 붙은 메서드 호출 시 성공/실패·요청 IP·URI가 자동 기록된다 (상세는 "AOP 기반 액션 로깅" 참조)

## API 문서

- Swagger UI: `http://localhost:8080/swagger-ui.html` (SpringDoc OpenAPI 2.8.14, `ROLE_ADMIN` 로그인 필요, **dev 전용**)
- API 문서는 SpringDoc Swagger(OpenAPI 3)로 단일화한다. Spring REST Docs는 사용하지 않는다.

현재 구현된 주요 엔드포인트(RESTful 규칙 정렬 완료):
- `POST /admin/api/members` — 관리자 생성 (201 Created); `userType`은 `ROLE_ADMIN`·`ROLE_MANAGER`만 허용 (`ROLE_USER` 불가, 위반 시 400)
- `GET /admin/api/members` — 관리자 목록 (페이징/검색)
- `GET /admin/api/members/{id}` — 관리자 상세
- `PATCH /admin/api/members/{id}` — 타 관리자 부분 수정·상태 변경 (본인 계정은 400 → `/members/me` 사용; 최후 활성 ADMIN 제거 방지·비관적 락·세션 만료 등 상세 제약은 `AdminMemberService`와 Swagger 참조)
- `GET /admin/api/members/me` — 내 정보 조회
- `PATCH /admin/api/members/me` — 내 정보 수정
- `PUT /admin/api/members/me/profile-image` — 프로필 이미지 업로드 (multipart) 또는 기본 프리셋 선택 (json 본문, 동일 경로·`consumes`로 구분)
- `DELETE /admin/api/members/me/profile-image` — 프로필 이미지 초기화 (204 No Content)
- `PATCH /admin/api/members/me/password` — 내 비밀번호 변경
- `POST /admin/api/password-reset-requests` — 비밀번호 재설정 메일 발송 (공개, 항상 200 — 계정 열거 방지)
- `POST /admin/api/password-resets` — 토큰으로 비밀번호 재설정 (공개, 204; 무효/만료/사용됨 비구분 400)
- `GET /admin/api/menus/tree` — 메뉴 트리 조회 (`useYn=true|all`)
- `GET /admin/api/menus/{id}` — 메뉴 단건 조회
- `POST /admin/api/menus` — 메뉴 생성 (201 Created); `accessRole` 누락 시 `ALL` 기본화
- `PATCH /admin/api/menus/{id}` — 메뉴 부분 수정 (null 필드는 기존값 유지, `upMenuNo` 변경 불가)
- `DELETE /admin/api/menus/{id}` — 메뉴 비활성화 (소프트 삭제, 204 No Content)

## 테스트 규칙

- 새 기능에는 테스트를 함께 작성한다.
- 컨트롤러 테스트는 `MockMvc`를 사용한다.
- 시큐리티가 걸린 엔드포인트는 `spring-security-test`(`@WithMockUser` 등)를 활용한다.
- 슬라이스 테스트(`@WebMvcTest`, `@DataJpaTest`)를 우선하고, 통합 테스트(`@SpringBootTest`)는 필요한 경우에만 사용한다.

## 문제 해결 기록 (Troubleshooting)

개발 중 해결에 시간이 들고 재발 가능한 **비자명한 이슈**를 해결한 경우 `docs/troubleshooting.md`에 기록한다. (단순 오타·일회성 실수는 제외)

- **기록 시점**: 원인을 규명하고 해결을 검증한 직후.
- **카테고리로 구분**: 새 항목은 아래 카테고리 중 적절한 곳에 추가한다. 맞는 카테고리가 없으면 새 카테고리를 만든다.
    - 개발 환경 / 인프라 (Docker, WSL, 로컬 DB 등)
    - 빌드 / 의존성 (Gradle, QueryDSL Q클래스, 라이브러리 호환성 등)
    - 애플리케이션 / 런타임 (Security 필터, AOP 로깅, 트랜잭션, JPA/QueryDSL 등)
- **형식**: 카테고리(`##`) 아래에 이슈 제목(`###`)을 두고, 내부는 `오류 메시지 / 원인 / 해결 방법(필요 시 검증 명령)` 순서로 작성한다.

## MCP 도구 활용 지침

### context7 — 라이브러리 공식 문서 조회

라이브러리 API가 **불확실하거나 버전에 민감한 작업**일 때는 코드를 작성하기 전에 context7로 최신 공식 문서를 확인한다 (훈련 데이터가 오래됐을 수 있다). 확신이 있는 안정된 API까지 매번 조회할 필요는 없다. 상황별 조회 대상:

| 상황 | 조회 대상 |
|------|-----------|
| QueryDSL 동적 쿼리·`BooleanExpression` 조합 | `querydsl` |
| Spring Data JPA `Specification` / Pageable 정렬 | `spring-data-jpa` |
| Spring Security 필터 체인·메서드 보안 설정 | `spring-security` |
| SpringDoc OpenAPI 어노테이션·Swagger 커스터마이징 | `springdoc-openapi` |
| Thymeleaf 레이아웃·조각(fragment) 문법 | `thymeleaf` |
| Spring Boot 3.x 설정·자동 구성 변경사항 | `spring-boot` |

사용 순서: `resolve-library-id` → `query-docs` (토픽과 버전을 함께 지정).

### sequential-thinking — 복잡한 작업 사전 설계

다음 조건 중 하나라도 해당하면 sequential-thinking으로 단계별 계획을 먼저 수립한다.

- Controller → Service → Repository → Entity를 모두 신규 작성하는 **새 도메인 기능** 추가
- 여러 레이어·파일에 걸친 **리팩터링 또는 마이그레이션** (예: API 경로 일괄 변경)
- 원인 불명 버그의 **근본 원인 추적** (AOP·Security 필터·트랜잭션 경계 포함)
- DB 스키마 변경이 수반되는 작업 (영향 엔티티·마이그레이션 순서 정리)

계획 결과를 사용자에게 먼저 제시하고 확인받은 뒤 코드를 작성한다.

### playwright — 브라우저 UI 검증

Thymeleaf 화면을 수정하거나 새 페이지를 추가한 경우 playwright로 직접 확인한다.

**기본 접속 정보**
- URL: `http://localhost:8080`
- 로그인: 사전에 DB에 등록된 관리자 계정 (`dev` 프로파일에서 DB가 비어 있으면 `TestMemberLoader`가 `admin`/`1234` 계정을 자동 생성)
- 로그인 경로: `/admin/login`

**검증 우선순위**
1. 로그인 → 해당 화면 진입 → 핵심 기능 동작 (골든 패스)
2. 폼 유효성 검사 메시지 노출 여부
3. API 호출 후 화면 갱신(목록 reload, 성공/오류 토스트 등)
4. 다른 화면에 회귀 오류가 없는지 스크린샷으로 확인

앱이 실행 중이 아닐 때는 `./gradlew bootRun`(또는 `make dev-up`)을 먼저 실행한다.
playwright로 확인할 수 없는 환경이라면 그 사실을 명시하고 완료를 주장하지 않는다.

## 작업 방식 (Claude Code에게)

1. 변경 범위가 크면 코드 작성 전에 **계획을 먼저 제시**하고 확인을 받는다. (복잡한 작업은 sequential-thinking 활용)
2. 한 번에 하나의 기능/관심사만 변경한다. 무관한 리팩터링을 섞지 않는다.
3. 작업 후 `./gradlew test`로 검증하고, 실패 시 원인을 설명한다.
4. 엔티티/스키마 변경 시 영향 범위(연관 엔티티, 마이그레이션 필요 여부)를 먼저 알린다.
5. API 경로를 추가·변경할 때는 위 RESTful 규칙을 따르고, 호출하는 화면/JS 영향 범위를 함께 보고한다.
6. 라이브러리 API가 불확실하면 context7로 확인한 뒤 작성한다 — 훈련 데이터가 오래됐을 수 있다.
7. Thymeleaf 화면 변경 후에는 playwright로 UI를 직접 확인하고 결과를 보고한다.
8. 비자명한 이슈를 해결했다면 `docs/troubleshooting.md`의 알맞은 카테고리에 위 형식대로 기록한다. (기준은 "문제 해결 기록" 섹션 참고)
9. 확인되지 않거나 확실하지 않은 부분은 **추측하지 말고 질문**한다. 사용자가 결정해야 하는 트레이드오프는 선택지로 정리해 질문한다. (사용자가 매번 요청하지 않아도 항상 적용)
10. codex 계열 도구(`/codex:review`, `/codex:adversarial-review` 등)의 출력을 사용자에게 전달할 때는 **원문 전체를 한국어로 번역**해 보여준다 — 코드 식별자·기술 용어는 원형 유지, 내용 누락·축약 금지. 요약만 제시하고 원문을 생략하지 않는다.

## 주의사항 / 금지 사항

(코딩 컨벤션·보안 규칙·환경 설정 섹션의 금지 규칙은 각 섹션이 원본이다. 여기에는 다른 곳에 없는 항목만 둔다.)

- QueryDSL Q클래스는 환경에 따라 생성 경로가 다르다. IntelliJ IDEA에서는 `src/main/generated/`, Gradle CLI(`./gradlew compileJava`)에서는 `build/generated/sources/annotationProcessor/java/main/`에 생성된다. 두 경로 모두 빌드 산출물이므로 커밋 대상이 아니며, 코드 변경 후 `./gradlew compileJava`로 다시 생성해야 한다.
- 프로필 이미지는 DB에 저장되므로 대용량 Base64 데이터가 API 응답에 포함될 수 있다.
- 검증되지 않은 새 라이브러리/의존성은 먼저 제안한 뒤 추가한다.
