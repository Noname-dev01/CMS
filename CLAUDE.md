# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 지침 파일 지도

아래 파일들은 해당 디렉터리 작업 시 자동 로드된다. 코드를 열지 않고 판단해야 하는 상황이라면 이 표를 보고 직접 읽는다 — 추측하지 않는다.

| 파일 위치 | 담긴 내용 |
|---|---|
| `com.cms.admin.board` | 범용 게시판 — 정의·게시글·첨부, 게시판 행 잠금, 공지 흡수, 예약 게시, 조회·다운로드 수, 슬러그, 대표 이미지, 유형, 휴지통 |
| `com.cms.admin.banner` | 공개 메인 배너 — 위임 기능 `BANNER`, 가드 행 `banner_lock`, 전체 교체 `PUT`, 이미지 네임스페이스 |
| `com.cms.admin.popup` | 공개 메인 팝업 — 위임 기능 `POPUP`, `revision`, 이미지/HTML 종류, 기간·높이 파싱 함정 |
| `com.cms.admin.page` | 정적 페이지 — 위임 기능 `SITE_PAGE`, 개정 테이블, `version` 409, 본문 이미지 불허, "사이트 관리" 화면 구성 |
| `com.cms.admin.contentimage` | 편집기 본문 이미지 — 업로드 권한·출처, 카운터 잠금, 참조 교체, 공개 판정, 수동 회수, 대표 이미지 썸네일 |
| `com.cms.admin.log` | `@AdminActionLogged` 독립 트랜잭션(REQUIRES_NEW)·예외 격리 계약 |
| `com.cms.admin.member` | 초기 관리자 부트스트랩, 비밀번호 재설정·로그인 실패 잠금·유출 비밀번호 차단·변경 강제, 프로필 이미지 |
| `com.cms.admin.site` | 사이트 설정 — 기본 공개 사이트, 키 예약어 `SiteKey.RESERVED`, 링크 정책, 잠금 규약, 사이트별 콘텐츠·게시판, 공개 메뉴 렌더링 |
| `com.cms.admin.menu` | 메뉴 — 사이트별 편집, `/admin/menu/manage`, 사이드바 3단 제약, 노출 계산 `MenuVisibility`, 시드 조건 |
| `com.cms.admin.message` | 상단바 쪽지·쪽지함 — 입력 파이프라인, 발송 트랜잭션, 보관함 삭제, 현재 자격 가드 |
| `com.cms.admin.notification` | 상단바 알림의 최선 노력 생성 계약·리스너 실행 순서(메서드 `@Order`, AFTER_COMMIT이 AFTER_COMPLETION보다 먼저)·`beforeId` 커서·D11(열람 시점 ADMIN) |
| `com.cms.admin.session` | 세션 관리(영구 ADMIN 전용) — 해시 핸들, 현재 세션 보호, best-effort 만료, 감사 한계 |
| `com.cms.admin.media` | 미디어 라이브러리 — 위임 기능 `MEDIA`, `content_image` 원본, 출처별 가시성, 잠금 순서, 사용 중 삭제 409 |
| `com.cms.admin.permission` | MANAGER 위임 권한 카탈로그·판정기·캐시(**회원별 허용 행**), 권한관리 API·화면(`PUT` 교체·409·변형 행 가드), 역할 변경 시 개별 권한 삭제, 인가 선언 컨벤션, 롤백·재배포 주의 |
| `com.cms.config` | `SecurityConfig` 경로별 접근 제어 표(승인 이력 포함) |
| `com.cms.publicweb.support` | 공개 경로 `/{siteKey}/…` — `@SiteScopedPublic`, `PublicSiteInterceptor`, `sitePrefix`, 공개 인벤토리 시험, 공개 파일 전송 동시 상한 `@PublicFileTransfer` |
| `com.cms.publicweb.home` | 공개 메인 — 배너·공지·새 글, fail-soft, 스크립트 금지와 팝업 조각 예외, 레이트리밋 |
| `com.cms.publicweb.page` | 공개 정적 페이지 — 현재 버전·개정 목록, 동일 404, 출력 시 이미지 불허 |
| `com.cms.admin.faq` | FAQ 관리 — 위임 기능 `FAQ`, 가드 행 `faq_lock`·구조 `version`, 순서 반영 `PUT /order`, 상한, DnD 초안 |
| `com.cms.publicweb.faq` | 공개 FAQ — 노출 조건 쿼리 고정, 빈 답변 제거, 하위 경로 302/403, 즉시 필터 |
| `com.cms.admin.inquiry` | 문의함 관리 — 위임 기능 `INQUIRY`, 평문 입력 정책, 미처리 소프트 상한, 트랜잭션 밖 `InquiryNotifier` |
| `com.cms.publicweb.inquiry` | 공개 문의 — 처리 순서 고정, Turnstile fail-closed, 기본 닫힘 503, 입력 보존 범위, 쓰기 매핑 고정 |
| `com.cms.publicweb.board` | 공개 게시판 — 공개 불변식, 404 흡수, 스트리밍, 이미지 공개 판정, 유형별 목록 |
| `com.cms.publicweb.notice` | 공개 공지 — 공지 게시판으로 302 흡수, 숫자 공지 링크 치환·저장 거부 |
| `src/test/java` | MockMvc·spring-security-test·슬라이스 우선·Testcontainers |
| `com.cms.common.html` | 콘텐츠 본문 HTML — 편집기·sanitizer 허용 목록, 내부 링크, 공백 저장 형식 |
| `com.cms.common.storage` | 파일 스토리지 — 읽기 두 종류, 심볼릭 링크 거부, 트랜잭션 정리와 보존 규칙 |

## 브랜치 전략

**GitHub Flow** 채택. `master`는 항상 배포 가능 상태로 유지하고 직접 push하지 않는다.

- 브랜치 접두사: `feat/` · `fix/` · `refactor/` · `security/` · `test/` · `chore/` + kebab-case
- 작업 완료 시 PR → CI(`.github/workflows/ci.yml`) 통과(`./gradlew test`) → Squash merge → 브랜치 삭제
- 상세 규칙: `docs/branching.md` 참고

## 빌드 및 실행 명령어

- Docker 기반 개발/검증 명령은 `make help`로 확인한다(prod 타깃은 배포 가능 상태 검증용 — 실배포 절차는 `docs/deployment.md`).

## 아키텍처 개요

Spring Boot 기반 관리자 CMS로, 계층화된 MVC 패턴을 따른다. 의존 방향은 **Controller → Service → Repository → Entity** 단방향을 유지한다.

### 구조상 알아둘 점

- **Spring Boot 4.0.8 + 모듈식 스타터**(2026-10-06, CVE-2026-47884 대응 `adversarial-review/plan/PLAN-spring-boot-4.md` → classic 제거 `PLAN-modular-starters.md`): 실제로 쓰는 기술의 스타터만 선언한다(`starter-webmvc`·`thymeleaf`·`security`·`data-jpa`·`validation`·`mail`·`actuator`·`flyway`·`aspectj`). **새 기술(캐시·RestClient·스케줄링 외 모듈 등)을 쓰려면 해당 스타터를 추가해야 자동 구성이 켜진다** — classic처럼 모든 모듈이 클래스패스에 있지 않다. `starter-aspectj`는 `@AdminActionLogged` AOP용으로 명시(전이 의존에 맡기지 않음). 테스트는 `starter-webmvc-test`·`starter-data-jpa-test`·**`starter-security-test`** — 마지막 것은 `@WebMvcTest`·`@AutoConfigureMockMvc`에 Security 자동 구성과 `@WithMockUser` 연동을 넣는 필수 의존이다(빼면 보안 슬라이스 테스트가 `HttpSecurity` 빈 없음으로 컨텍스트 생성부터 실패). JSON은 **Jackson 3** — databind 코드는 `tools.jackson.databind.*`를 import하고(`com.fasterxml.jackson.databind`는 SpringDoc이 끌어온 Jackson 2라 쓰지 않는다), 어노테이션은 Jackson 3에서도 `com.fasterxml.jackson.annotation.*` 그대로다. 테스트 슬라이스 어노테이션은 모듈 패키지(`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` 등)에 있다. `EnvironmentPostProcessor` 등록 키는 `org.springframework.boot.EnvironmentPostProcessor`.
- **Java 21**(2026-10-07, `adversarial-review/plan/PLAN-java-21.md`): `build.gradle` toolchain·`Dockerfile` 빌더 `eclipse-temurin:21-jdk`/런타임 `21-jre`(태그+index digest)·CI `setup-java` 세 곳의 **메이저를 항상 함께 바꾼다**(테스트한 JVM = 운영 JVM 정책, 자동 검사 없음). toolchain 자동 다운로드(foojay)는 없어 로컬에 JDK 21이 설치돼 있어야 한다. 가상 스레드는 켜지 않았다(동시성 계약 재검증이 필요한 별도 작업). JDK 메이저마다 Unicode 버전이 달라 `toLowerCase(Locale.ROOT)` 결과도 바뀔 수 있다 — 이메일 정규화(`EmailNormalizer`)가 영향받으므로 JDK 메이저를 바꿀 때는 비ASCII 이메일 행(`email REGEXP '[^ -~]'`)을 점검한다.
- `AdminPage`는 Thymeleaf 페이지 컨트롤러 마커 어노테이션이다. 새 페이지 컨트롤러에 **필수 부착** — 누락은 `AdminPageAnnotationConventionTest`가 감지한다.
- `AdminSidebarAdvice`는 `@AdminPage` 컨트롤러에만 사이드바 모델을 주입한다. REST API 요청에 메뉴 DB 조회가 나가지 않도록 의도적으로 범위를 제한한 것이다.
- `publicweb`은 비관리자(공개) 화면 전용으로 `admin` 패키지와 분리한다. `@AdminPage` 미부착 대상이며, 예외 처리도 `publicweb/support/PublicWebExceptionAdvice`가 범위 한정으로 담당한다.
- `config/ProfileGuardEnvironmentPostProcessor`는 dev+prod 동시 활성화와 활성 프로파일 0개를 컨텍스트 생성 **전에** 차단한다(`META-INF/spring.factories` 등록).
- **콘텐츠 본문은 HTML이다**: 본문을 출력하는 새 경로·새 콘텐츠 도메인은 반드시 `common/html/HtmlContentSanitizer`를 저장 시와 출력 시 모두 거치고, 편집기 `formats`·툴바·허용 목록을 1:1로 맞춘다. 상세는 `com.cms.common.html`의 `CLAUDE.md`.
- **파일 저장은 `common/storage`의 `FileStorage`를 쓴다**: `open()`의 스트림은 호출자가 닫는다. 첨부·본문 검증은 `AttachmentFilePolicy`·`ContentBodyPolicy` 한 벌을 공유한다(두 벌 금지). 상세는 `com.cms.common.storage`의 `CLAUDE.md`.
- **`spring.jpa.open-in-view: false`**(전 프로파일 공통, 2026-09-29): OSIV를 켜 두면 서비스 트랜잭션이 끝나도 요청이 끝날 때까지 JDBC 연결이 유지돼 응답 전송이 긴 요청(공개 첨부 다운로드)이 커넥션 풀을 점유한다(실측: 전송 중 활성 커넥션 1→0). 엔티티에 연관관계 매핑이 없어 지연 로딩 의존이 없다. 다시 켜면 `PublicBoardAttachmentStreamingServerTest`가 실패한다.
- **시각 원천은 KST `Clock` 하나**(`AppConfig.clock()`, 2026-09-30): 저장·조회 코드는 `LocalDateTime.now()` 같은 시스템 기본 시각 호출을 쓰지 않고 주입된 `Clock`(`LocalDateTime.now(clock)`)을 쓴다. 엔티티는 시계를 모르며 도메인 변경 메서드가 `LocalDateTime now`를 파라미터로 받는다(서비스가 한 요청에서 1회 산출해 전달). `CmsApplication.main()`이 JVM 기본 시간대를 KST로 고정하므로 운영 값은 원래 같았지만, `main()`을 거치지 않는 테스트 JVM(CI는 UTC)에서 시각 원천이 갈라지는 것을 막는 규약이다 — `ClockUsageConventionTest`가 `src/main/java`를 스캔해 위반을 잡는다. 허용 예외 2건: `LocalDiskFileStorage`(저장 디렉터리 샤딩 이름)·`ApiErrorResponse`(static 팩토리라 주입 불가 → `AppConfig.KST` 시간대만 공유). 한계: Flyway 시드 `V2__baseline_seed.sql`의 초기 메뉴 시각은 DB `NOW()`를 쓴다(옛 시드의 형태를 그대로 옮긴 것). 새 `now()` 호출이 필요하면 Clock 주입 대신 예외를 추가하지 말 것. 시각이 시스템 시각과 무관함을 검증하려면 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test`(환경변수는 Gradle 입력이 아니라 `cleanTest` 없이는 UP-TO-DATE로 생략될 수 있음). 상세는 `adversarial-review/plan/PLAN-clock-unification.md` 참조.

### AOP 기반 액션 로깅

`@AdminActionLogged`로 관리자 행위가 자동 감사 기록된다. 트랜잭션·예외 격리 계약은 `com.cms.admin.log`의 `CLAUDE.md` 참조.

### 프로필 이미지

`com.cms.admin.member`의 `CLAUDE.md` 참조(FileStorage 이관·검증·다운로드 라우트 등 상세).

## 코딩 컨벤션

- **DI는 생성자 주입**: `@RequiredArgsConstructor` + `final` 필드. 필드 주입(`@Autowired`) 금지.
- **Lombok**: 엔티티에는 `@Setter`/`@Data` 사용 금지(양방향 연관관계에서 순환 위험). 생성/변경은 `@Builder`와 의미 있는 도메인 메서드로 처리.
- **DTO 경계**: 엔티티를 Controller 응답으로 직접 노출하지 않는다. 항상 요청/응답 DTO로 변환한다.
- **검증**: 컨트롤러 진입 DTO에 Bean Validation(`@Valid`, `@NotNull` 등)을 적용한다. 열거형 값의 일부만 허용해야 할 경우 `@AllowedRoles` 같은 커스텀 `ConstraintValidator`를 사용한다 (`dto/request/validation/` 패키지). `@ModelAttribute` 바인딩 검증 실패(`BindException`)도 `GlobalApiExceptionHandler`가 400 VALIDATION_ERROR JSON으로 반환한다.
- **트랜잭션**: 비즈니스 로직과 트랜잭션 경계는 Service에 둔다. 조회 전용은 `@Transactional(readOnly = true)`.
- **QueryDSL 우선**: 동적 조건·복잡한 조인은 `@Query` 문자열보다 QueryDSL(`*RepositoryImpl`)로 작성한다.
- **예외 처리**: `GlobalApiExceptionHandler`(`@RestControllerAdvice`)를 통해 처리한다. 컨트롤러에서 try-catch를 남발하지 않는다.
- **대소문자 변환은 `Locale.ROOT`**: `toLowerCase()`·`toUpperCase()`를 인자 없이 쓰지 않는다 — JVM 기본 로케일이 tr/az이면 `"GIF"` → `"gıf"`처럼 결과가 바뀐다(2026-10-07 전수 정리, `adversarial-review/plan/PLAN-extension-locale-root.md`). `LocaleRootConventionTest`가 `src/main/java`의 인자 없는 호출을 잡는다(메서드 참조 `String::toLowerCase`와 시험 코드는 검사 밖 — 리뷰에서 확인).

## RESTful API 설계 규칙

신규·변경 엔드포인트의 URI 명명, 상태 코드 계약, 목록 조회 파라미터 규칙은 `api-conventions` 스킬 참조(API 작업 시 자동 로드).

## 환경 설정

- **개발**: `application-dev.yml` + `.env.dev`, MariaDB 포트 3307
- **운영(prod, 배포 가능 상태 검증 완료 — 2026-07-29)**: `application-prod.yml` + `.env.prod`(커밋 금지). 절차·필수 환경변수는 `docs/deployment.md` 참고. 실제 인터넷 배포(호스트·도메인·TLS)는 별도 범위.
- **프로파일 기본값 없음(의도)**: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE}` — 미지정 시 기동 자체가 실패한다(placeholder 해석 실패로 fail-fast). `com.cms.config.ProfileGuardEnvironmentPostProcessor`가 추가로 `dev`+`prod` 동시 활성화와 활성 프로파일 0개(빈 문자열)를 컨텍스트 생성 전에 차단한다. 로컬 `./gradlew test`·CI 모두 `SPRING_PROFILES_ACTIVE=dev`를 명시 주입한다(`build.gradle`의 `test` 태스크, `.github/workflows/ci.yml`).
- **스키마 관리는 Flyway** (`src/main/resources/db/migration/`, `ddl-auto: validate` — 공통값, 전 프로파일 적용): 엔티티 변경만으로는 스키마가 바뀌지 않는다 — 컬럼/인덱스 추가·변경 시 반드시 마이그레이션 파일을 함께 작성한다. 머지된 마이그레이션 파일은 수정 금지(체크섬 불일치로 기동 실패) — **유일한 예외는 실배포 전 기준 스키마로 합치기**다: 2026-10-21에 V1~V58을 `V1__baseline_schema.sql`·`V2__baseline_seed.sql`로 합쳤고(`adversarial-review/plan/PLAN-migration-squash.md`), 첫 실배포 직전에 그 사이 생긴 V3~Vn을 마지막으로 한 번 더 합친 뒤에는 예외가 없다. 기존 행에 영향을 주는 새 마이그레이션은 업그레이드 경로 시험을 쓴다. 합치기 후 기존 DB 재생성·시험 정책·새 마이그레이션 작성 규칙은 `docs/migration-guide.md` 참고.
- **초기 관리자 계정**: dev(`TestMemberLoader`)·prod(`AdminBootstrapLoader`) 부트스트랩 계약과 기동 실패 조건은 `com.cms.admin.member`의 `CLAUDE.md` 참조.
- **CI 배포 게이트**(`prod-smoke` job, 2026-09-29): prod 이미지 빌드·기동·보안 응답·백업복구 왕복·Trivy 이미지 스캔을 자동 검증한다. `mariadb`·`eclipse-temurin` 이미지는 digest로 고정돼 있어 참조를 바꾸면 `scripts/ci/check-image-refs.sh` 일치 검사를 통과해야 한다. `scripts/ci/*.sh`는 고정 이름 prod 자원을 만들고 지우므로 폐기 가능한 Docker 환경에서만 실행한다(로컬은 `CMS_CI_DISPOSABLE_DOCKER=1`, 상세는 `docs/deployment.md` "CI 배포 게이트").
- **actuator**: `management.endpoints.web.exposure.include: health`(공통, 전 프로파일)+`show-details: never`. `SecurityConfig`가 `/actuator/health`만 `permitAll()`, `/actuator/**`는 `denyAll()`로 이중 방어한다(설정이 실수로 넓어져도 Security 레이어가 막음).
- 민감 정보(DB 비밀번호, 메일 계정, 시크릿)는 코드에 하드코딩하지 않고 프로파일/환경변수로 분리한다. `.env.dev`·`.env.prod` 모두 git 추적 대상 아님(`.gitignore`의 `.env*` 규칙, `.env.example`만 예외).

## 보안 규칙

`SecurityConfig`의 경로별 접근 제어 표(승인 이력 포함)는 `com.cms.config`의 `CLAUDE.md` 참조. MANAGER가 접근할 수 있는 기능은 코드 카탈로그(`AdminFeature`) ∩ **그 회원 본인의** DB 허용 행으로 정해지고(기능 단위 위임은 `member_permission`, 게시판 단위 위임은 `member_board_permission` — 공지는 2026-10-09부터 **공지 게시판의 게시글이라 게시판 권한**이며 기능 단위 `NOTICE`는 제거됐다. 현재 `DELEGABLE` 기능은 공개 메인 배너 `BANNER`·정적 페이지 `SITE_PAGE`(2026-10-11)·공개 메인 팝업 `POPUP`(2026-10-12)·FAQ `FAQ`(2026-10-12)·미디어 라이브러리 `MEDIA`(2026-10-12)·문의함 `INQUIRY`(2026-10-12) 여섯이다(2026-10-10 — 첫 기능 단위 위임이라 공지 흡수 때 생긴 그 경로의 시험 공백이 이때 복구됐다) — `com.cms.admin.permission`의 `CLAUDE.md`. 2026-10-03부터 회원별 — 신규 MANAGER는 권한이 없다) **ADMIN은 DB를 보지 않고 항상 허용**이다 — 카탈로그·판정기·캐시·인가 선언 컨벤션·롤백 주의는 `com.cms.admin.permission`의 `CLAUDE.md` 참조(2026-10-02, 위임 불가 기능은 어떤 DB 값으로도 MANAGER에게 열리지 않는다).

- `ACTIVE` 상태 계정만 로그인 가능 (`CustomUserDetailsService`). 연속 5회 로그인 실패 시 자동 잠금(30분 해제) — 정책 상세는 "핵심 도메인 모델 > Member" 참조 (2026-07-14 승인)
- **로그인 상태 유지(remember-me)는 없다**(2026-11-12 제거 — 인증·로그인 경로 변경·V16 스키마 변경 승인, `adversarial-review/plan/PLAN-remember-me-removal.md`): 관리자 세션은 폼 로그인만 만든다. 2026-11-10~12의 remember-me·민감 작업 재인증(비밀번호 모달·`@RequireFullAuthentication`)·세션 화면 같은 기기 안내를 모두 걷어 냈다(관리자 페이지라 쿠키 탈취 시 7일 노출을 남기지 않는다). 다시 넣으려면 새 계획부터 세운다 — 세션을 끊는 모든 경로를 토큰 폐기와 묶어야 한다. 같은 세션의 폼 로그인 직렬화(`LoginSessionMutexFilter`)는 두 탭 동시 로그인의 레지스트리 불일치를 막는 독립 방어로 남겼다. 상세는 `com.cms.config`의 `CLAUDE.md` "로그인 상태 유지 없음".
- **세션 강제 만료는 best-effort다** — 즉시 접근 차단 수단이 아니다. 상세는 `com.cms.admin.session`의 `CLAUDE.md`.
- CSRF는 **모든 경로에 활성**이다. `/admin/api/**`를 포함한 상태 변경 요청(POST/PATCH/PUT/DELETE)은 반드시 CSRF 토큰을 포함해야 한다. Thymeleaf 페이지는 `head.html` 프래그먼트가 `<meta name="_csrf">` 태그로 토큰을 렌더링하며, JS에서 이 값을 읽어 `X-CSRF-TOKEN` 헤더로 전송한다. 신규 상태 변경 fetch 호출 작성 시 CSRF 헤더를 누락하지 않는다.
- 메서드 레벨 보안: `MethodSecurityConfig`에서 활성화
- 비밀번호는 항상 `PasswordEncoder`로 인코딩하며 평문 저장 금지.
- **인가 정책(URL 권한, 로그인 정책)은 사전 협의 없이 변경하지 않는다.** 변경이 필요하면 먼저 제안한다.
- **기본 거부**(2026-09-29 승인, 감사 M-08): `SecurityConfig`의 마지막 규칙은 `anyRequest().denyAll()`이다. 신규 엔드포인트는 접근 규칙을 명시적으로 추가하지 않으면 차단된다(비인증 302, 인증 403). 컨테이너 ERROR 디스패치·정적 리소스 예외와 정적 예약 접두사 규약은 `com.cms.config`의 `CLAUDE.md` 참조.
- **무인증 공개 엔드포인트 레이트리밋**(`com.cms.config.ratelimit`): 필터는 `CsrfFilter` 다음, 키는 `request.getRemoteAddr()` 고정(`X-Forwarded-For` 금지), fail-open 수용. 상세는 `com.cms.config`의 `CLAUDE.md`.

## 핵심 도메인 모델

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

**Member**(관리자 계정, 비밀번호 재설정·로그인 실패 잠금·유출 비밀번호 차단·변경 강제·프로필 이미지 포함), **Menu**, **공지**(2026-10-09부터 공지 게시판의 게시글 — 첨부파일·공개 공지 페이지·공개 첨부파일 다운로드 포함; 옛 `Notice` 엔티티는 제거되고 `notice`·`notice_attachment` 테이블은 V3에서 제거), **AdminActionLog** 상세는 각각 `com.cms.admin.member`·`com.cms.admin.menu`·`com.cms.admin.board`(공개 측은 `com.cms.publicweb.notice`·`com.cms.publicweb.board`)·`com.cms.admin.log`의 `CLAUDE.md` 참조(해당 패키지 작업 시 자동 로드).

**VisitLog / 대시보드**: ADMIN·MANAGER 로그인 성공 1회 = 방문 1건 (`VisitLoggingAuthenticationSuccessHandler`가 기록 — 저장·집계 모두 KST `Clock` 단일 시간원, 2026-07-18 통일). 대시보드는 통계 카드 4종 + 최근 7일 방문자 라인 차트(`DashboardService.getDailyVisitorCounts()` — 방문 없는 날 0 채움, 집계 실패 시 빈 리스트 폴백으로 500 없이 오류 문구 표시, 페이지 모델 주입 방식이라 REST API 없음). 카드·차트는 개별 조회라 순간 불일치 허용(의도된 eventual consistency).

## API 문서

- Swagger UI: `http://localhost:8080/swagger-ui.html` (SpringDoc OpenAPI, `ROLE_ADMIN` 로그인 필요, **dev 전용** — prod는 `application-prod.yml`의 `springdoc.api-docs.enabled=false`+`swagger-ui.enabled=false`로 핸들러 자체가 등록되지 않는다. 이 경로에 접근하면 페이지 경로이므로 HTML 404가 반환된다 — `com.cms.error.CustomErrorController`(2026-08-06 해결, `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조))
- API 문서는 SpringDoc Swagger(OpenAPI 3)로 단일화한다. Spring REST Docs는 사용하지 않는다.
- 구현된 엔드포인트 전체 목록과 파라미터·제약은 Swagger UI에서 확인한다(위 경로, dev 프로파일).

## 테스트 규칙

세부 규칙(MockMvc·spring-security-test·슬라이스 테스트 우선·Testcontainers)은 `src/test/java`의 `CLAUDE.md` 참조(테스트 코드 작업 시 자동 로드).

## 문제 해결 기록 (Troubleshooting)

비자명한 이슈를 해결했다면 `docs/troubleshooting.md`에 기록한다. 기록 시점·카테고리·형식은 `troubleshooting-log` 스킬 참조.

## MCP 도구 활용 지침

context7(라이브러리 공식 문서 조회)·playwright(브라우저 UI 검증) 사용 시점·절차는 `library-docs-lookup` 스킬 참조(관련 작업 시 자동 판단·호출).

## 작업 방식 (Claude Code에게)

1. 변경 범위가 크면 코드 작성 전에 **계획을 먼저 제시**하고 확인을 받는다.
2. 한 번에 하나의 기능/관심사만 변경한다. 무관한 리팩터링을 섞지 않는다.
3. **요청받지 않은 기능·추상화·설정 옵션을 미리 만들지 않는다. 문제를 해결하는 최소한의 코드로 구현한다.** (과도하게 복잡해 보이면 더 단순한 방법을 먼저 제시한다)
4. **검증 범위는 단계별로 다르다**(2026-10-22 — 전체 실행 약 6분, PR CI가 전체를 다시 돌린다). 실패하면 원인을 설명한다.
   - **개발 중 반복**: 바꾼 패키지의 시험 + 그 패키지를 쓰는 쪽의 시험(예: `admin.faq`를 바꾸면 `publicweb.faq`도) + 컨벤션 시험만 돌린다 — `./gradlew test --tests 'com.cms.admin.faq.*' --tests 'com.cms.publicweb.faq.*' --tests '*ConventionTest'`.
   - **전체 `./gradlew test`를 바로 돌리는 경우**: 공용 코드(`common/**`·`config/**`·`src/test/java/com/cms/support/**`·`SecurityConfig`·마이그레이션·`build.gradle`·공용 템플릿 조각)를 바꿨거나 영향 범위를 가늠할 수 없을 때.
   - **PR 직전 1회**: 커밋 전에 전체 `./gradlew test`를 한 번 통과시킨다(`code-review-loop` 마무리 단계가 이 역할 — 리뷰 루프를 건너뛰면 `commitPR` 전에 직접 돌린다). PR CI가 최종 안전망이다.
5. 엔티티/스키마 변경 시 영향 범위(연관 엔티티, 마이그레이션 필요 여부)를 먼저 알린다.
6. API 경로를 추가·변경할 때는 `api-conventions` 스킬의 규칙을 따르고, 호출하는 화면/JS 영향 범위를 함께 보고한다.
7. 라이브러리 API가 불확실하면 context7로 확인한 뒤 작성한다 — 훈련 데이터가 오래됐을 수 있다.
8. Thymeleaf 화면 변경 후에는 playwright로 UI를 직접 확인하고 결과를 보고한다.
9. 비자명한 이슈를 해결했다면 `docs/troubleshooting.md`의 알맞은 카테고리에 기록한다. (기준은 `troubleshooting-log` 스킬 참고)
10. 확인되지 않거나 확실하지 않은 부분은 **추측하지 말고 질문**한다. 사용자가 결정해야 하는 트레이드오프는 선택지로 정리해 질문한다. (사용자가 매번 요청하지 않아도 항상 적용)
11. codex 계열 도구(`/codex:review`, `/codex:adversarial-review` 등)의 출력을 사용자에게 전달할 때는 **원문 전체를 한국어로 번역**해 보여준다 — 코드 식별자·기술 용어는 원형 유지, 내용 누락·축약 금지. 요약만 제시하고 원문을 생략하지 않는다.
12. 개발 절차는 `/feature`, `plan-review-loop`, `code-review-loop`, `commitPR`이 우선한다. superpowers의 `brainstorming`, `writing-plans`, `executing-plans`, `subagent-driven-development`, `requesting-code-review`, `finishing-a-development-branch`로 대체하지 않는다. `systematic-debugging`, `test-driven-development`, `verification-before-completion`은 쓰되, 적용 범위는 `/feature`의 "스킬 적용 범위"를 따른다.

## 주의사항 / 금지 사항

(코딩 컨벤션·보안 규칙·환경 설정 섹션의 금지 규칙은 각 섹션이 원본이다. 여기에는 다른 곳에 없는 항목만 둔다.)

- QueryDSL Q클래스는 환경에 따라 생성 경로가 다르다. IntelliJ IDEA에서는 `src/main/generated/`, Gradle CLI(`./gradlew compileJava`)에서는 `build/generated/sources/annotationProcessor/java/main/`에 생성된다. 두 경로 모두 빌드 산출물이므로 커밋 대상이 아니며, 코드 변경 후 `./gradlew compileJava`로 다시 생성해야 한다.
- 프로필 이미지는 `FileStorage` 이관 완료로 API 응답에 짧은 다운로드 URL만 담긴다. 옛 Base64 이관 러너와 `LEGACY_INLINE` 종류는 V3(2026-10-22, `adversarial-review/plan/PLAN-legacy-schema-removal.md`)에서 제거했다 — `LEGACY_INLINE` 행이 남은 DB는 V3가 실패하므로 `docs/migration-guide.md` 절차로 먼저 정리한다.
- 검증되지 않은 새 라이브러리/의존성은 먼저 제안한 뒤 추가한다.
