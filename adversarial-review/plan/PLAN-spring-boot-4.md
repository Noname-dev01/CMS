# PLAN: Spring Boot 4.0.8 전환 (CVE-2026-47884 해소)

- 작성: 2026-10-06
- 브랜치: `security/spring-boot-4` (base `master`)
- 상태: **완료(2026-10-06 · #97 `1505c43` 머지, master CI success)**. 계획 리뷰 3라운드 ship, 코드 리뷰 1라운드 통과.

## 구현 결과 요약 (2026-10-06)
- 0단계 기준선(Boot 3.5.16): 정적 mapper 경로 `timestamp` = 배열 확인, 로그인 `Location` = `http://localhost/admin/login` 확인, 활동 로그 QueryDSL 실 DB 테스트(`AdminActionLogRepositoryDataJpaTest`) 통과.
- 전환 후: `./gradlew test` 전체 통과(1,199+), `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` 통과.
- 해석 버전: spring-webmvc 7.0.9, Jackson 3.1.7·2.21.7, Tomcat 11.0.24, Hibernate 7.2.24.Final, Security 7.0.7, QueryDSL 5.1.0(jakarta, Hibernate 7에서 동작 — R1 해소).
- prod 이미지 로컬 빌드 성공, `app.jar` 내 버전 확인. Trivy·기동·보안 응답은 CI `prod-smoke`로 확인 예정.
- 실서버(dev, 8081): 공개 경로 429 상태·`Retry-After`·`error/429` 본문·리다이렉트 없음(R7), 로그인·대시보드·사이드바·활동 로그·공지 화면(R8), Swagger UI(R9, OAS 3.1 표기), 401 JSON `timestamp` ISO 문자열 확인.
- R5: 옛 `spring.factories` 키는 Boot 4에서 **조용히 무시됨**을 변이 실험으로 확인 — 새 키로 교체하고 실제 `SpringApplication` 기동 경로 테스트(`registeredViaSpringFactories_blocksDevAndProdOnRealStartup`)를 추가.
- **PR #97 CI 실패 대응**: §4-1의 "Tomcat 오버라이드 삭제"는 오판이었다 — Tomcat 11.0.24가 CVE-2026-65182·65905·68525(CRITICAL, 수정판 11.0.25)를 재유입해 Trivy 실패. `ext['tomcat.version'] = '11.0.26'` 추가로 해소.
- `FAIL_ON_UNKNOWN_PROPERTIES`: 설정을 빼도 Boot 4.0.8 기본 구성에서 미지 필드가 무시됨을 변이 실험으로 확인 — 명시적 고정으로 유지.

## 개정 이력
- v2 변경(1라운드 리뷰 6건 전부 수용):
  - R1-1 Jackson 3: Boot 4.0.8 BOM의 3.1.5는 CVE-2026-68497(`tools.jackson.core:jackson-databind` < 3.1.6) 영향 → `ext['jackson-bom.version'] = '3.1.7'`로 오버라이드(§4-1).
  - R1-2 Jackson 2 잔존: SpringDoc 3.0.3 → `swagger-core-jakarta`가 Jackson 2 databind를 끌어오고 BOM의 `jackson-2-bom` 2.21.5는 같은 CVE(2.19.0 ~ < 2.21.6) 영향 → `ext['jackson-2-bom.version'] = '2.21.7'` 유지, 양쪽 해석 버전·`app.jar` 포함 여부 검사(§4-1, §5 R10).
  - R1-3 날짜 설정 키: Jackson 3에서 `WRITE_DATES_AS_TIMESTAMPS`가 `DateTimeFeature`로 이동 → `spring.jackson.serialization.write-dates-as-timestamps`를 Boot 4 키로 교체하고 기동 검증(§4-4). 정확한 키는 구현 시 Boot 4.0 공식 속성 문서로 확정.
  - R1-4 static mapper 응답 계약: `ApiAuthenticationEntryPoint`·`ApiAccessDeniedHandler`·`AdminSessionExpiredStrategy`·`RateLimitFilter`는 자체 `ObjectMapper`라 `spring.jackson.*` 미적용 → 전환 **전** 응답을 기준으로 401·403·세션 만료·429의 `timestamp` 타입·형식 단언 테스트 추가(§4-4, §6 0단계).
  - R1-5 활동 로그 QueryDSL 실DB 검증 공백: 기존 테스트는 `JPAQueryFactory`·리포지토리 mock → 실 MariaDB 통합 테스트(필터·정렬·페이지·count) 추가(§5 R1).
  - R1-6 실제 Tomcat 429 디스패치: 레이트리밋 활성 실서버(또는 playwright)에서 429 유지·`error/429` 본문·`Retry-After` 보존·로그인 리다이렉트 없음 확인(§5 R7, §6).
- v3 변경(2라운드):
  - R2-1 수용: 목표의 "0건"을 현행 CI 정책(`ignore-unfixed: true`) 기준으로 한정하고, unfixed 포함 스캔은 참고 기록으로 추가(§2). CI 정책 자체 변경은 범위 밖.
  - R2-2 수용(사용자 결정 2026-10-06 "엄격화 수용"): Jackson 3 `FAIL_ON_TRAILING_TOKENS=true` 기본값을 그대로 받아들인다 — 후행 토큰이 있는 기형 JSON은 `400 JSON_PARSE_ERROR`. §2 무변경 범위의 **의도된 예외**로 기록하고, 후행 토큰 400 테스트와 기존 `ord` 등 미지 필드 무시 계약 유지 테스트를 둔다(§2, §4-4).
  - R2-3 수용(사용자 결정 2026-10-06 "상대 URI 수용"): Security 7 기본값대로 미인증 로그인 리다이렉트 `Location`을 상대 URI로 받아들인다. §2 무변경 범위의 **의도된 예외**로 기록하고, 전환 전후 `Location` 정확값 단언 테스트로 고정한다(§2, §5 R3).

## 1. 배경

CI `prod-smoke`의 Trivy 이미지 스캔이 `app.jar`의 `org.springframework:spring-webmvc 6.2.19`에서
**CVE-2026-47884(CRITICAL, GHSA-pc63-qcmh-9cmg, XsltView 경로 제한 미흡에 의한 RCE)**를 검출해
#94(쪽지)·#95(mariadb digest)·#96(temurin digest)의 CI가 모두 실패한다.

- GitHub 권고 기준 영향 범위: 6.2.0 ~ 6.2.19 **수정판 없음(`patched: null`)**, 7.0.0 ~ 7.0.8 → **7.0.9 수정**.
- 현재 Boot 3.5.16(최신 3.5.x)이 관리하는 6.2.19가 6.2 라인 최신이라 같은 라인 업그레이드로는 해소 불가.
- 사용자 결정(2026-10-06): 예외 등록이 아니라 **Spring Framework 7 전환**으로 근본 해소. 버전은 **Boot 4.0.8**,
  전략은 **단계적(classic 스타터)**.

## 2. 목표 / 비목표

**목표**
- Boot 4.0.8 BOM으로 전환해 `spring-webmvc` ≥ 7.0.9를 사용하고 **현행 CI Trivy 정책(`severity: CRITICAL,HIGH`, `ignore-unfixed: true`, `.trivyignore.yaml` 예외 적용) 기준 0건**으로 `prod-smoke` 통과. 수정판 없는(unfixed) 취약점은 이 정책상 게이트 대상이 아니며, 참고용으로 `ignore-unfixed=false` 스캔 결과(잔여 건수·대상)를 PR 본문에 기록한다(게이트 아님, v3).
- 기존 동작·보안 계약(SecurityConfig 경로 표, CSRF, 세션 만료, 레이트리밋, 에러 응답 JSON 형식, Clock 규약) 무변경.
- **의도된 계약 변경(사용자 승인, v3)** — 이 두 가지만 무변경 범위에서 제외한다:
  1. 요청 JSON 뒤에 후행 토큰이 있으면 `400 JSON_PARSE_ERROR`(Jackson 3 `FAIL_ON_TRAILING_TOKENS` 기본값). 미지 필드 무시 계약은 유지.
  2. 미인증 페이지 요청의 로그인 리다이렉트 `Location`이 절대 URI → 상대 URI(`/admin/login`, Security 7 기본값). **구현 후 실측(2026-10-06)**: 상대 URI는 서블릿 API 수준(MockMvc)에서만 보이고, 실제 Tomcat 응답은 여전히 절대 URL(`http://localhost:8081/admin/login`)이었다 — 실제 클라이언트가 받는 응답은 변하지 않았다. 테스트는 MockMvc 기준 정확값(`/admin/login`)으로 고정했다.
  3. (구현 0단계 발견, 사용자 결정 2026-10-06 "ISO 문자열로 통일") 정적 mapper 경로(401·403·세션 만료·429)의 `timestamp`가 배열(`[yyyy,M,d,H,m,s,n]`) → ISO 문자열. 기준선 테스트로 현재 배열임을 확인했고, 컨트롤러 경로(`GlobalApiExceptionHandler`)는 이미 문자열이라 전환 후 모든 `ApiErrorResponse`가 같은 형식이 된다. JS에서 `timestamp`를 읽는 곳은 없다.
- `./gradlew test` 전체 그린(로컬 + CI), `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` 그린.

**비목표 (후속 PR)**
- 모듈식 스타터(`spring-boot-starter-webmvc` 등)로의 세분화 — 이번에는 `spring-boot-starter-classic`·`spring-boot-starter-test-classic`으로 자동 구성 가용성을 기존과 같게 유지한다.
- 기능 변경, 리팩터링, 새 API.

## 3. 버전 매트릭스 (Boot 4.0.8 BOM 실측, Maven Central 2026-10-06)

| 항목 | 현재 | 전환 후 | 비고 |
|---|---|---|---|
| Spring Boot | 3.5.16 | 4.0.8 | |
| Spring Framework | 6.2.19 | 7.0.9 | CVE 수정판 |
| Spring Security | 6.5.x | 7.0.7 | |
| Hibernate ORM | 6.6.x | 7.2.24.Final | |
| Tomcat | 10.1.60(오버라이드) | 11.0.24 | 오버라이드 제거 |
| Jackson 3 | — | 3.1.7(오버라이드, BOM 3.1.5는 CVE 영향) | `tools.jackson`, §4-1·§4-4 |
| Jackson 2 | 2.21.7(오버라이드) | 2.21.7(오버라이드 유지, BOM 2.21.5는 CVE 영향) | SpringDoc(swagger-core) 경유 잔존 |
| Flyway | 11.x | 11.14.1 | `spring-boot-starter-flyway` 필요 |
| Testcontainers | 1.x | 2.0.5 | 아티팩트·패키지 변경 |
| SpringDoc | 2.9.1 | 3.0.3 | 3.0.x는 Boot 4.0.5 기준, 3.1.x는 4.1 기준 |
| thymeleaf-extras-springsecurity6 | BOM | 3.1.5.RELEASE(BOM) | Boot 4 BOM이 여전히 `springsecurity6` 아티팩트를 관리 |
| QueryDSL | 5.1.0:jakarta | 5.1.0:jakarta (유지 시도) | Hibernate 7 호환 미확인, §5 R1 |
| Gradle / dependency-management | 8.14.5 / 1.1.7 | 유지 | Boot 4 요구(Gradle 8.14+) 충족 |
| Java | 17 | 17 | Boot 4 최소 17 |

## 4. 변경 내용

### 4-1. `build.gradle`
- `org.springframework.boot` 플러그인 `4.0.8`.
- `ext['tomcat.version'] = '10.1.60'` **삭제**(Boot 4 BOM의 Tomcat 11.0.24 사용 — 남기면 Tomcat 10으로 내려가 깨진다).
- Jackson 오버라이드는 **의미를 바꿔 유지**(v2): Boot 4에서 `jackson-bom.version`은 Jackson 3 BOM, `jackson-2-bom.version`은 Jackson 2 BOM이다.
  - `ext['jackson-bom.version'] = '3.1.7'` — BOM 3.1.5는 CVE-2026-68497 영향(< 3.1.6).
  - `ext['jackson-2-bom.version'] = '2.21.7'` — SpringDoc(swagger-core)가 Jackson 2를 끌어오며 BOM 2.21.5는 같은 CVE 영향(< 2.21.6).
  - 주석에 "Boot BOM이 수정판 이상을 관리하게 되면 제거" 조건을 남긴다.
  - 검증: `./gradlew dependencies --configuration runtimeClasspath`에서 `tools.jackson.core:jackson-databind`·`com.fasterxml.jackson.core:jackson-databind` 해석 버전 확인.
- `spring-boot-starter-web` → 유지하되 classic 집계를 위해 `spring-boot-starter-classic` 추가 여부는 §4-2.
- `flyway-core` 단독 → `spring-boot-starter-flyway` + `flyway-mysql`(Boot 4는 Flyway 자동 구성이 별도 모듈).
- `springdoc-openapi-starter-webmvc-ui:3.0.3`.
- `spring-boot-starter-test` → `spring-boot-starter-test-classic`.
- `org.testcontainers:mariadb` → `org.testcontainers:testcontainers-mariadb`.

### 4-2. classic 스타터의 범위
- `spring-boot-starter-classic`·`spring-boot-starter-test-classic`은 **자동 구성 모듈을 전부 클래스패스에 올려** 3.x와 같은 자동 구성 가용성을 준다. **패키지 이동은 흡수하지 않는다** — import 수정은 이번 PR에서 불가피하다(§4-3).
- 확인할 것: classic 추가 후 기존에 없던 자동 구성(예: 불필요한 클라이언트·메트릭 모듈)이 켜져 기동·보안 표면이 바뀌지 않는지. `/actuator/**` denyAll, `management.endpoints.web.exposure.include=health` 유지 확인.

### 4-3. 패키지 이동 대응 (컴파일 오류 기반으로 수정, 아래는 사전 집계)
- 테스트 슬라이스: `org.springframework.boot.test.autoconfigure.web.servlet.{WebMvcTest,AutoConfigureMockMvc}`(30), `...orm.jpa.DataJpaTest`(7), `...jdbc.AutoConfigureTestDatabase`(7) → Boot 4 모듈 패키지.
- main: `boot.autoconfigure.jdbc.JdbcConnectionDetails`, `boot.autoconfigure.mail.MailSenderAutoConfiguration`, `boot.autoconfigure.task.TaskExecutionAutoConfiguration`, `boot.web.servlet.FilterRegistrationBean`, `boot.web.servlet.error.ErrorController`, `boot.env.EnvironmentPostProcessor`(+ `META-INF/spring.factories` 키).
- 정확한 새 패키지는 context7(Boot 4.0 문서)과 jar 내용으로 확인한 뒤 바꾼다 — 추측 금지.
- **`spring.factories`의 `EnvironmentPostProcessor` 키가 바뀌면 `ProfileGuardEnvironmentPostProcessor`가 조용히 등록 해제된다**(컴파일 오류가 안 남). 기존 가드 테스트가 실제 등록 경로를 타는지 확인하고, 아니면 등록 검증 테스트를 추가한다.

### 4-4. Jackson 3
- classic이어도 Boot 4의 기본 JSON은 Jackson 3이다. Jackson 2를 유지하려면 deprecated `spring-boot-jackson2`로 이중 스택이 되므로 채택하지 않는다.
- main 5개 파일(`ObjectMapper`·`JsonNode`·`JsonNodeFactory` 사용: `RateLimitFilter`, `AdminSessionExpiredStrategy`, `ApiAccessDeniedHandler`, `ApiAuthenticationEntryPoint`, `MenuStructureRequest`)과 테스트 4개 파일의 `com.fasterxml.jackson.databind` → `tools.jackson.databind`. 어노테이션(`com.fasterxml.jackson.annotation.*`)은 Jackson 3에서도 같은 패키지라 그대로.
- **요청 파싱(v3)**: Jackson 3 `FAIL_ON_TRAILING_TOKENS=true`를 수용한다(§2 의도된 변경 1). 후행 토큰 요청 → `400 JSON_PARSE_ERROR` 테스트와, 미지 필드(`ord` 등) 무시 계약(`FAIL_ON_UNKNOWN_PROPERTIES=false` 상당) 유지 테스트를 둔다.
- Jackson 3은 `JsonProcessingException`(checked) 대신 unchecked `JacksonException`을 던진다 — 기존 try-catch 의미가 바뀌지 않게 각 호출부를 확인한다(특히 401/403 JSON 직렬화 실패 경로).
- `application.yml`의 `spring.jackson.serialization.write-dates-as-timestamps: false`는 Jackson 3에서 `DateTimeFeature`로 옮겨져 **키 교체가 필요하다**(v2, 리뷰 제시 키 `spring.jackson.datatype.datetime.write-dates-as-timestamps` — Boot 4.0 공식 속성 문서로 확정 후 적용). `default-property-inclusion: non_null`은 Boot 4 키 유효성을 같은 방식으로 확인. 잘못된 키는 enum 바인딩 실패로 기동 자체가 깨질 수 있으므로 실제 기동으로 검증한다.
- **static mapper 경로(v2)**: `ApiAuthenticationEntryPoint`·`ApiAccessDeniedHandler`·`AdminSessionExpiredStrategy`·`RateLimitFilter`는 `new ObjectMapper()`를 직접 만들어 `spring.jackson.*`이 적용되지 않는다. Jackson 3 기본값(날짜를 ISO 문자열로 직렬화) 때문에 `timestamp` 형식이 바뀔 수 있다. **전환 전에** 현재 응답의 `timestamp` 타입·형식을 단언하는 테스트를 401·403·세션 만료(API)·429 각각에 추가하고, 전환 후 같은 테스트로 형식을 보존한다. 형식이 바뀌면 mapper 설정으로 기존 형식을 맞춘다(응답 계약 변경 금지).
- 이 항목은 "classic 단계적" 선택과 별개로 **이번 PR 포함이 불가피**하다는 판단이다(사용자 확인 필요 시 질문).

### 4-5. Testcontainers 2
- `org.testcontainers.containers.MariaDBContainer` → `org.testcontainers.mariadb.MariaDBContainer`(2.x). digest 고정 이미지 참조(`MariaDbContainerSupport`)는 그대로 둔다 — `check-image-refs.sh` 일치 계약.

### 4-6. 문서
- `CLAUDE.md`·`docs/troubleshooting.md`(비자명 이슈 발생 시)·`build.gradle` 주석의 버전 서술 갱신.

## 5. 위험과 검증

| ID | 위험 | 검증 / 대응 |
|---|---|---|
| R1 | QueryDSL 5.1.0(jakarta)이 Hibernate 7에서 동작하지 않을 수 있다(`HQLTemplates`·`HibernateHandler`) | QueryDSL 사용 리포지토리 통합 테스트(공지·회원·활동 로그 검색) 통과로 확인. **활동 로그는 기존 테스트가 `JPAQueryFactory`·리포지토리를 mock해 실 DB 검증이 없으므로**(v2) 실 MariaDB 통합 테스트를 전환 전에 추가한다: 수행자 문자열·결과 enum·기간 필터·정렬·페이지 이동·count 쿼리. 공지·회원 검색도 실 DB 통합 테스트 존재 여부를 확인하고 없으면 같은 수준으로 보강. 실패 시 **멈추고** 대체(OpenFeign `io.github.openfeign.querydsl` 7.x)를 새 의존성으로 별도 제안 |
| R2 | Hibernate 7의 `ddl-auto: validate` 타입 판정 차이로 기동 실패 | 전체 통합 테스트 기동. 실패 시 엔티티 매핑 조정(스키마 변경 없이) — 마이그레이션 수정 금지 |
| R3 | Security 7 동작 변화(CSRF·세션·예외 처리 순서) | `SecurityConfigTest`·세션 만료·레이트리밋(CsrfFilter 다음 위치) 테스트 그린. 기존 `**/admin/login` 패턴 단언은 절대·상대를 모두 통과시키므로 **`Location` 정확값 단언**을 추가해 상대 URI 전환(§2 의도된 변경 2)을 고정한다(v3). `prod-smoke`의 리다이렉트 부분 문자열 검사가 상대 URI에서도 의미 있게 동작하는지 확인 |
| R4 | Jackson 3 직렬화 기본값 차이(날짜·null·빈 객체) | API 응답 JSON 단언 테스트, 필요 시 설정 보강 |
| R5 | `EnvironmentPostProcessor` 등록 키 변경으로 프로파일 가드 무력화 | §4-3 등록 검증 |
| R6 | classic 스타터가 새 자동 구성을 켜 보안 표면 확대 | actuator·Swagger(dev 전용, prod 비활성) 응답 확인, `prod-smoke` 보안 응답 검사 |
| R7 | Tomcat 11 동작 차이(정적 리소스·에러 디스패치·`Retry-After` 헤더) | `CustomErrorController`·공개 첨부 스트리밍 테스트 그린. **MockMvc는 컨테이너 ERROR 디스패치를 타지 않고 일반 테스트는 레이트리밋이 꺼져 있으므로**(v2) 레이트리밋 활성 실서버(dev 기동 또는 RANDOM_PORT 테스트)에서 공개 경로 한도 초과 시 429 상태 유지·`error/429` 본문·`Retry-After` 보존·로그인 리다이렉트 없음을 확인 |
| R10 | Jackson 2·3 공존 시 어느 한쪽이 취약 버전으로 해석 | `dependencies` 출력과 prod 이미지 Trivy(로컬 또는 CI)로 양쪽 버전 확인 |
| R8 | thymeleaf-extras-springsecurity6 + Security 7 호환 | 사이드바·로그인 화면 렌더 테스트, playwright로 실기 확인 |
| R9 | SpringDoc 3 어노테이션·설정 키 변화 | dev 기동 후 `/swagger-ui.html` 확인, prod 비활성 유지 확인 |

## 6. 작업 순서
0. **전환 전 기준선 테스트 추가**(v2, Boot 3.5.16 상태에서 그린 확인): 401·403·세션 만료·429 응답의 `timestamp` 형식 단언, 활동 로그 QueryDSL 실 DB 통합 테스트(R1).
1. `build.gradle` 전환 → `./gradlew compileJava compileTestJava`로 컴파일 오류 목록 확보.
2. 패키지 이동·Jackson 3·Testcontainers 2 수정(무관한 정리 금지).
3. `./gradlew test` → 실패 원인별 대응(R1~R9). 설계 변경 수준이면 멈추고 보고.
4. UTC 테스트(`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test`).
5. 로컬 prod 이미지 빌드 + Trivy(가능하면) / 불가 시 CI `prod-smoke`로 확인.
6. playwright로 로그인·사이드바·공지 화면 확인 + 레이트리밋 활성 실서버에서 공개 경로 429 응답 확인(R7).
7. 문서 갱신 → code-review-loop → commitPR.

## 7. 후속
- #94: 이 PR 머지 후 리베이스, 쪽지 테스트의 패키지 이동 반영.
- #95: 새 mariadb digest를 5곳에 일치시킨 뒤 재실행.
- #96: 리베이스 후 재실행, openssl 예외(CVE-2026-84782, 만료 2026-10-08) 대상이 새 JRE digest에서 해소됐는지 예외를 빼고 확인.
- 후속 PR: 모듈식 스타터 전환, classic 제거.
