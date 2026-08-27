# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 지침 파일 지도

아래 파일들은 해당 디렉터리 작업 시 자동 로드된다. 코드를 열지 않고 판단해야 하는 상황이라면 이 표를 보고 직접 읽는다 — 추측하지 않는다.

| 파일 위치 | 담긴 내용 |
|---|---|
| `com.cms.admin.log` | `@AdminActionLogged` 독립 트랜잭션(REQUIRES_NEW)·예외 격리 계약 |
| `com.cms.admin.member` | 초기 관리자 부트스트랩, 비밀번호 재설정·로그인 실패 잠금·90일 만료, 프로필 이미지 |
| `com.cms.admin.menu` | 사이드바 2단 제약·역할 필터·메뉴 시드 조건 |
| `com.cms.admin.notice` | `useYn`/`deleted` 분리, 비관적 락, 첨부파일 상한·삭제 차단 |
| `com.cms.config` | `SecurityConfig` 경로별 접근 제어 표(승인 이력 포함) |
| `com.cms.publicweb.notice` | 공개 노출 불변식 격리, 404 흡수 정책, 공개 첨부 TOCTOU |
| `src/test/java` | MockMvc·spring-security-test·슬라이스 우선·Testcontainers |

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

- `AdminPage`는 Thymeleaf 페이지 컨트롤러 마커 어노테이션이다. 새 페이지 컨트롤러에 **필수 부착** — 누락은 `AdminPageAnnotationConventionTest`가 감지한다.
- `AdminSidebarAdvice`는 `@AdminPage` 컨트롤러에만 사이드바 모델을 주입한다. REST API 요청에 메뉴 DB 조회가 나가지 않도록 의도적으로 범위를 제한한 것이다.
- `publicweb`은 비관리자(공개) 화면 전용으로 `admin` 패키지와 분리한다. `@AdminPage` 미부착 대상이며, 예외 처리도 `publicweb/support/PublicWebExceptionAdvice`가 범위 한정으로 담당한다.
- `config/ProfileGuardEnvironmentPostProcessor`는 dev+prod 동시 활성화와 활성 프로파일 0개를 컨텍스트 생성 **전에** 차단한다(`META-INF/spring.factories` 등록).
- `common/storage`의 `FileStorage`(구현 `LocalDiskFileStorage`)는 파일 스토리지 추상화이며, 공지 첨부파일이 첫 소비자다.

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

## RESTful API 설계 규칙

신규·변경 엔드포인트의 URI 명명, 상태 코드 계약, 목록 조회 파라미터 규칙은 `api-conventions` 스킬 참조(API 작업 시 자동 로드).

## 환경 설정

- **개발**: `application-dev.yml` + `.env.dev`, MariaDB 포트 3307
- **운영(prod, 배포 가능 상태 검증 완료 — 2026-07-29)**: `application-prod.yml` + `.env.prod`(커밋 금지). 절차·필수 환경변수는 `docs/deployment.md` 참고. 실제 인터넷 배포(호스트·도메인·TLS)는 별도 범위.
- **프로파일 기본값 없음(의도)**: `spring.profiles.active: ${SPRING_PROFILES_ACTIVE}` — 미지정 시 기동 자체가 실패한다(placeholder 해석 실패로 fail-fast). `com.cms.config.ProfileGuardEnvironmentPostProcessor`가 추가로 `dev`+`prod` 동시 활성화와 활성 프로파일 0개(빈 문자열)를 컨텍스트 생성 전에 차단한다. 로컬 `./gradlew test`·CI 모두 `SPRING_PROFILES_ACTIVE=dev`를 명시 주입한다(`build.gradle`의 `test` 태스크, `.github/workflows/ci.yml`).
- **스키마 관리는 Flyway** (`src/main/resources/db/migration/`, `ddl-auto: validate` — 공통값, 전 프로파일 적용): 엔티티 변경만으로는 스키마가 바뀌지 않는다 — 컬럼/인덱스 추가·변경 시 반드시 마이그레이션 파일을 함께 작성한다. 머지된 마이그레이션 파일은 수정 금지(체크섬 불일치로 기동 실패). 기존 DB 전환·새 마이그레이션 작성 규칙은 `docs/migration-guide.md` 참고.
- **초기 관리자 계정**: dev(`TestMemberLoader`)·prod(`AdminBootstrapLoader`) 부트스트랩 계약과 기동 실패 조건은 `com.cms.admin.member`의 `CLAUDE.md` 참조.
- **actuator**: `management.endpoints.web.exposure.include: health`(공통, 전 프로파일)+`show-details: never`. `SecurityConfig`가 `/actuator/health`만 `permitAll()`, `/actuator/**`는 `denyAll()`로 이중 방어한다(설정이 실수로 넓어져도 Security 레이어가 막음).
- 민감 정보(DB 비밀번호, 메일 계정, 시크릿)는 코드에 하드코딩하지 않고 프로파일/환경변수로 분리한다. `.env.dev`·`.env.prod` 모두 git 추적 대상 아님(`.gitignore`의 `.env*` 규칙, `.env.example`만 예외).

## 보안 규칙

`SecurityConfig`의 경로별 접근 제어 표(승인 이력 포함)는 `com.cms.config`의 `CLAUDE.md` 참조.

- `ACTIVE` 상태 계정만 로그인 가능 (`CustomUserDetailsService`). 연속 5회 로그인 실패 시 자동 잠금(30분 해제) — 정책 상세는 "핵심 도메인 모델 > Member" 참조 (2026-07-14 승인)
- **세션 등록·강제 만료**: `sessionManagement(maximumSessions(-1))` + `SessionRegistry` + `HttpSessionEventPublisher` 활성 (동시 로그인 제한 없음 — 세션 추적만). 타 관리자 수정으로 상태·권한이 실변경되면(멱등 재잠금 LOCKED/DISABLED 동일값 포함) `AdminSessionRevokeEvent`가 발행되고 커밋 후 `AdminSessionRevokeListener`(AFTER_COMMIT)가 대상자 세션을 만료 처리한다. 만료된 세션의 다음 요청은 `AdminSessionExpiredStrategy`가 API는 JSON 401, 페이지는 `/admin/login` 리다이렉트로 응답. **계약은 best-effort** — 즉시 접근 차단 수단이 아니며, 극단적 커밋 경합 시 기존 세션이 세션 타임아웃(기본 30분) 또는 재잠금까지 유효할 수 있다.
- CSRF는 **모든 경로에 활성**이다. `/admin/api/**`를 포함한 상태 변경 요청(POST/PATCH/PUT/DELETE)은 반드시 CSRF 토큰을 포함해야 한다. Thymeleaf 페이지는 `head.html` 프래그먼트가 `<meta name="_csrf">` 태그로 토큰을 렌더링하며, JS에서 이 값을 읽어 `X-CSRF-TOKEN` 헤더로 전송한다. 신규 상태 변경 fetch 호출 작성 시 CSRF 헤더를 누락하지 않는다.
- 메서드 레벨 보안: `MethodSecurityConfig`에서 활성화
- 비밀번호는 항상 `PasswordEncoder`로 인코딩하며 평문 저장 금지.
- **인가 정책(URL 권한, 로그인 정책)은 사전 협의 없이 변경하지 않는다.** 변경이 필요하면 먼저 제안한다.
- **무인증 공개 엔드포인트 레이트리밋** (`cms.rate-limit.*`, `com.cms.config.ratelimit`, 2026-08-12 도입): `/notices/**`(GET·HEAD)·비밀번호 재설정 API 2종(POST)에 토큰 버킷 기반 최소 방어를 적용한다. "N회/기간"은 버스트 상한 N + 평균 N/기간을 뜻하며(엄격한 슬라이딩 상한 아님), 정확한 유량 계약을 보장하는 게이트웨이가 아니다. 저장소는 Caffeine(`maximumSize` + 버킷별 `Expiry`)이며, **fail-open을 운영 위험으로 명시 수용**한다 — 캐시가 포화되는 극단적 상황(대량 IP 회전 공격 등)에서는 개별 IP의 정확한 누적치 보장이 흐트러질 수 있다(메모리 상한·무제한 신규 키 수용·IP별 완벽한 정확성을 동시에 요구하려면 Redis 같은 외부 원자적 저장소가 필요하나 이번 범위·인프라 제약을 벗어나 기각). 필터는 `CsrfFilter` **다음**에 위치한다 — CSRF 검증 실패 요청이 quota를 소비하면 외부 사이트가 피해자 브라우저로 토큰 없는 form POST를 반복시켜 피해자 IP의 quota를 고갈시키는 교차 사이트 공격이 가능해지기 때문이다. 키는 `request.getRemoteAddr()` 고정(`X-Forwarded-For` 등은 위조 가능해 미사용). 상세 설계·리뷰 이력은 `adversarial-review/plan/PLAN-public-endpoint-rate-limit.md` 참조.

## 핵심 도메인 모델

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

**Member**(관리자 계정, 비밀번호 재설정·로그인 실패 잠금·비밀번호 90일 만료·프로필 이미지 포함), **Menu**, **Notice**(첨부파일·공개 공지 페이지·공개 첨부파일 다운로드 포함), **AdminActionLog** 상세는 각각 `com.cms.admin.member`·`com.cms.admin.menu`·`com.cms.admin.notice`(공개 측은 `com.cms.publicweb.notice`)·`com.cms.admin.log`의 `CLAUDE.md` 참조(해당 패키지 작업 시 자동 로드).

**VisitLog / 대시보드**: ADMIN·MANAGER 로그인 성공 1회 = 방문 1건 (`VisitLoggingAuthenticationSuccessHandler`가 기록 — 저장·집계 모두 KST `Clock` 단일 시간원, 2026-07-18 통일). 대시보드는 통계 카드 4종 + 최근 7일 방문자 라인 차트(`DashboardService.getDailyVisitorCounts()` — 방문 없는 날 0 채움, 집계 실패 시 빈 리스트 폴백으로 500 없이 오류 문구 표시, 페이지 모델 주입 방식이라 REST API 없음). 카드·차트는 개별 조회라 순간 불일치 허용(의도된 eventual consistency). SB Admin 2 데모 위젯은 2026-07-18 전부 제거됨

## API 문서

- Swagger UI: `http://localhost:8080/swagger-ui.html` (SpringDoc OpenAPI, `ROLE_ADMIN` 로그인 필요, **dev 전용** — prod는 `application-prod.yml`의 `springdoc.api-docs.enabled=false`+`swagger-ui.enabled=false`로 핸들러 자체가 등록되지 않는다. 이 경로에 접근하면 페이지 경로이므로 HTML 404가 반환된다 — `com.cms.error.CustomErrorController`(2026-08-06 해결, `docs/troubleshooting.md` "핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨" 참조))
- API 문서는 SpringDoc Swagger(OpenAPI 3)로 단일화한다. Spring REST Docs는 사용하지 않는다.
- 구현된 엔드포인트 전체 목록과 파라미터·제약은 Swagger UI에서 확인한다(위 경로, dev 프로파일).

## 테스트 규칙

세부 규칙(MockMvc·spring-security-test·슬라이스 테스트 우선·Testcontainers)은 `src/test/java`의 `CLAUDE.md` 참조(테스트 코드 작업 시 자동 로드).

## 문제 해결 기록 (Troubleshooting)

비자명한 이슈를 해결했다면 `docs/troubleshooting.md`에 기록한다. 기록 시점·카테고리·형식은 `troubleshooting-log` 스킬 참조.

## MCP 도구 활용 지침

context7(라이브러리 공식 문서 조회)·sequential-thinking(복잡한 작업 사전 설계)·playwright(브라우저 UI 검증) 사용 시점·절차는 `library-docs-lookup` 스킬 참조(관련 작업 시 자동 판단·호출).

## 작업 방식 (Claude Code에게)

1. 변경 범위가 크면 코드 작성 전에 **계획을 먼저 제시**하고 확인을 받는다. (복잡한 작업은 sequential-thinking 활용)
2. 한 번에 하나의 기능/관심사만 변경한다. 무관한 리팩터링을 섞지 않는다.
3. **요청받지 않은 기능·추상화·설정 옵션을 미리 만들지 않는다. 문제를 해결하는 최소한의 코드로 구현한다.** (과도하게 복잡해 보이면 더 단순한 방법을 먼저 제시한다)
4. 작업 후 `./gradlew test`로 검증하고, 실패 시 원인을 설명한다.
5. 엔티티/스키마 변경 시 영향 범위(연관 엔티티, 마이그레이션 필요 여부)를 먼저 알린다.
6. API 경로를 추가·변경할 때는 `api-conventions` 스킬의 규칙을 따르고, 호출하는 화면/JS 영향 범위를 함께 보고한다.
7. 라이브러리 API가 불확실하면 context7로 확인한 뒤 작성한다 — 훈련 데이터가 오래됐을 수 있다.
8. Thymeleaf 화면 변경 후에는 playwright로 UI를 직접 확인하고 결과를 보고한다.
9. 비자명한 이슈를 해결했다면 `docs/troubleshooting.md`의 알맞은 카테고리에 기록한다. (기준은 `troubleshooting-log` 스킬 참고)
10. 확인되지 않거나 확실하지 않은 부분은 **추측하지 말고 질문**한다. 사용자가 결정해야 하는 트레이드오프는 선택지로 정리해 질문한다. (사용자가 매번 요청하지 않아도 항상 적용)
11. codex 계열 도구(`/codex:review`, `/codex:adversarial-review` 등)의 출력을 사용자에게 전달할 때는 **원문 전체를 한국어로 번역**해 보여준다 — 코드 식별자·기술 용어는 원형 유지, 내용 누락·축약 금지. 요약만 제시하고 원문을 생략하지 않는다.

## 주의사항 / 금지 사항

(코딩 컨벤션·보안 규칙·환경 설정 섹션의 금지 규칙은 각 섹션이 원본이다. 여기에는 다른 곳에 없는 항목만 둔다.)

- QueryDSL Q클래스는 환경에 따라 생성 경로가 다르다. IntelliJ IDEA에서는 `src/main/generated/`, Gradle CLI(`./gradlew compileJava`)에서는 `build/generated/sources/annotationProcessor/java/main/`에 생성된다. 두 경로 모두 빌드 산출물이므로 커밋 대상이 아니며, 코드 변경 후 `./gradlew compileJava`로 다시 생성해야 한다.
- 프로필 이미지는 `FileStorage` 이관 완료로 API 응답에 짧은 다운로드 URL만 담긴다. 단, 화이트리스트 밖 MIME(webp 등)으로 이관에 실패한 레거시 행은 `LEGACY_INLINE`으로 남아 예외적으로 Base64가 계속 응답에 포함된다(가용성 우선 정책, `adversarial-review/plan/PLAN-profile-image-storage.md` 참조).
- 검증되지 않은 새 라이브러리/의존성은 먼저 제안한 뒤 추가한다.
