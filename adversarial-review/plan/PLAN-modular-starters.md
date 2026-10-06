# PLAN: Boot 4 모듈식 스타터 전환 (classic 스타터 제거)

- 작성: 2026-10-06
- 브랜치(예정): `refactor/modular-starters` (base `master`)
- 상태: **구현·검증 완료(2026-10-06) — 코드 리뷰·커밋 전**. 계획 리뷰 4라운드 ship.

## 구현·검증 결과 (2026-10-06)

### Context
#97(Boot 4.0.8) 직후 classic 스타터를 모듈식으로 전환했다. 동작 계약·스키마·인가 정책 변경 없음.

### 핵심 확정 사항
- 런타임: classic·`starter-web` 제거 → `starter-webmvc`·`starter-aspectj` 추가(D1·D2). 테스트: `starter-test-classic` → `starter-webmvc-test`·`starter-security-test`·`starter-data-jpa-test`(D3). Jackson 2 자동 구성은 소멸 수용(D4).
- **계획과 달라진 점 1**: D5 허용 목록에 `IntegrationMetricsAutoConfiguration`(Unconditional, `spring-boot-integration`) 추가 — 리포트 diff에서 허용 목록 밖 제거로 나와 멈추고 조사했다. 바이트코드상 **빈 메서드가 없는 순서 지정 전용 클래스**(`@AutoConfiguration(before=IntegrationAutoConfiguration, afterName=CompositeMeterRegistryAutoConfiguration)`)이고 Spring Integration 라이브러리도 없어 영향 없음.
- **계획과 달라진 점 2**: 계획 인덱스(`plan/README.md`) 30행 추가는 인덱스 27~29행을 넣은 #98이 아직 머지 전이라 충돌을 피해 #98 머지 후로 미룬다.

### 구현 파일
- `build.gradle`(의존성 선언), `CLAUDE.md`("구조상 알아둘 점" Boot 4 항목), `AdminActionLogControllerTest`(MVC 직렬화 단언 1건 추가 — R3-1).

### 검증 결과
- 기준선(classic): MVC 직렬화 단언 통과(`createAt` = `2026-10-06T09:05:07.123`, null 필드 키 생략), `/v3/api-docs` 원문(27 paths·57 schemas) 저장.
- 컴파일(강제 재실행) 통과, `./gradlew cleanTest test` 통과, `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` 통과.
- **D5 리포트 diff**(Positive 전체 항목 + Unconditional): 300 → 270 항목. 새로 생긴 항목 0. 사라진 30개 = 허용 목록 29개(RestClient·RestTemplate·ImperativeHttpClient·HttpClient·HttpClientMetrics·HttpServiceClientProperties·Jackson2AutoConfiguration 하위 포함·Cache 계열) + `IntegrationMetricsAutoConfiguration`(위 "달라진 점 1"). `Jackson2EndpointAutoConfiguration` 유지 확인.
- **R2 변이 실험**: `spring-boot-security-test` 제외(`testRuntimeClasspath`에서 0건 확인) → `ApiSecurityConfigTest` 6·`SecurityConfigTest` 56 전부 **컨텍스트 생성 실패**(`NoSuchBeanDefinitionException: HttpSecurity` → `SecurityConfig.filterChain` 생성 불가). 요청 단언 실패·통과 0 — 조용한 통과 경로 없음. 정상 구성에서 401 JSON·USER 403·ADMIN+CSRF 성공 짝 통과. 원복 확인.
- **R8**: 제거 테스트 모듈 22개 중 `spring.factories` 등록 5개 — micrometer-metrics-test(metrics export 기본값, 수용)·micrometer-tracing-test·restclient-test·restdocs·webservices-test(전부 미사용 기술).
- **R3**: 실서버 `/v3/api-docs` 정규화(키 정렬·`servers` 제거) 후 `paths`·`components`·`security` 내용 **바이트 동일**(77,680B). Swagger 접근은 ADMIN 세션에서 200.
- **R4**: 실서버 데이터 왕복 — 메뉴 생성(201)→비활성화(200)→영구삭제(204), `admin_action_log`에 `MENU_CREATE`·`MENU_UPDATE`·`MENU_DELETE`(target_label `modular-check`) 3건 기록, 메뉴 행 0으로 원복. 활동 로그 화면에 표시(playwright).
- 실서버 직렬화: `GET /admin/api/logs` 날짜 ISO 문자열·null 키 생략 — 전환 전과 동일.
- 경계: 미인증 API 401 JSON, 페이지·actuator(env·beans·metrics)·Swagger 302 → 로그인, ADMIN의 actuator/env·CSRF 없는 POST·미분류 경로 403, health만 공개.
- playwright: 로그인·대시보드·쪽지 드롭다운(API 연동)·활동 로그 화면 정상(스크린샷 `.playwright-mcp/modular-*.png`).
- **R6**: 런타임 의존성 185 → 115개, 새 라이브러리 0, 공통 라이브러리 버전 변화 0(spring-webmvc 7.0.9·Jackson 3.1.7/2.21.7·Tomcat 11.0.26·aspectjweaver 1.9.25.1 유지).
- **R5**: prod 이미지 로컬 빌드 성공, Trivy(0.70.0·CRITICAL/HIGH·ignore-unfixed·`.trivyignore.yaml`) OS·`app.jar`·pebble 0건, `app.jar` 라이브러리 116개·불필요 모듈(kafka·webflux·jetty·restclient·jackson2·devtools) 0.

- **코드 리뷰(2라운드 통과)**: 1라운드 지적 1건 수용 — `jsonPath(...).doesNotExist()`는 값이 null인 키도 "없음"으로 보아 null 키 생략 계약을 지키지 못한다. `doesNotHaveJsonPath()`로 교체하고 변이 실험(`default-property-inclusion: non_null` 제거)으로 확인: 기존 단언은 통과(감지 실패), 교체 후 실패(감지). 2라운드 지적 0건, 최종 `./gradlew cleanTest test` 통과.

### 이슈
- 실서버 검증 중 메뉴 생성 400: Git Bash의 `curl -d`에 한글 JSON을 넣으면 UTF-8로 전달되지 않아 생긴 셸 인코딩 문제(ASCII 이름으로 정상 201). 코드 무관.

### 후속
- 계획 인덱스 30행(#98 머지 후), Java 21 전환(로드맵).

## 개정 이력
- v2 변경(1라운드 4건 전부 수용):
  - R1-1 수용: `Jackson2EndpointAutoConfiguration`은 유지하는 `spring-boot-actuator-autoconfigure`에 있고 Jackson 2 **클래스**만 요구하므로 사라지지 않는다 — "jackson2(2)" 포괄 허용을 **정확한 클래스명 허용 목록**으로 교체(§3 D5). 이 클래스가 사라지면 원인 조사.
  - R1-2 수용: 최상위 이름 비교는 하위 구성·빈 메서드 소멸을 놓친다 → **Positive matches 전체 항목(중첩 구성·`#빈메서드` 포함)**을 diff. 정찰 로그 확인 결과 classic 상태에서도 `Jackson2HttpMessageConvertersConfiguration`은 비활성이고 Jackson 3 컨버터(`JacksonJsonHttpMessageConverterConfiguration`)만 활성 — 그래도 실제 컨버터 목록을 실서버에서 확인. R7에 SpringDoc 문서 계약 비교(`/v3/api-docs`의 `paths` 키·`components.schemas` 키 집합 전후 동일) 추가.
  - R1-3 수용: 변이 실험을 구체화 — `testRuntimeClasspath`에서 `spring-boot-security-test` 부재를 확인하고, **컨텍스트 생성 실패와 요청 단언 실패를 구분**해 기록. 정상 구성의 판정 기준은 `ApiSecurityConfigTest`의 짝(미인증 401 JSON·USER 403·ADMIN+CSRF 성공).
  - (v2 계속) R1-4 수용: 제거되는 테스트 모듈의 `spring.factories` 등록도 조사 대상에 포함. `spring-boot-micrometer-metrics-test`의 `MetricsContextCustomizerFactory`(테스트에서 `management.defaults.metrics.export.enabled=false`·`management.simple.metrics.export.enabled=true` 주입)가 사라지는 것은 **의도적으로 수용**(외부 metrics exporter 의존·관련 테스트 없음). 다른 제거 모듈의 `spring.factories`도 구현 단계에서 목록화해 기록.
- 선행: PR #97(Spring Boot 4.0.8 전환, `PLAN-spring-boot-4.md` §2 비목표·§7 후속)

- v3 변경(2라운드 2건 전부 수용):
  - R2-1 수용: 조건 없는 자동 구성은 리포트의 **Unconditional classes**에만 나오고 Positive matches에 없다(예: `spring-boot-http-client`의 `HttpServiceClientPropertiesAutoConfiguration` — 이번 전환으로 사라짐). D5 비교 대상에 **Unconditional classes 전후 diff**를 추가하고 이 클래스를 허용 목록에 넣는다. 조건 없는 빈 메서드는 리포트에 나타나지 않을 수 있으므로 리포트 diff는 "조건부·무조건 자동 구성 클래스 수준"의 보증으로 한정해 기록한다.
  - R2-2 수용: `paths`·`schemas` 이름 비교는 같은 경로의 operation 누락·enum·필수 여부 변화를 놓친다 → 전환 전 `/v3/api-docs` **원문 JSON을 저장**하고, 키 정렬·`servers` 제거로 정규화한 뒤 `paths`·`components` **전체 내용**을 전후 비교(메서드·매개변수·요청/응답·필드 타입·required·enum·`$ref`·security 포함). 정규화는 Node(`JSON.parse` 후 키 정렬 직렬화)로 한다.

- v4 변경(3라운드 1건 수용):
  - R3-1 수용: 기존 ISO 날짜 단언(401·403·세션 만료·429)은 전부 **자체 `ObjectMapper` 경로**라 Boot 구성 MVC 메시지 컨버터를 검증하지 않고, MVC 경로 테스트(`AdminMessageBoxIntegrationTest`)는 `readAt` 비어 있지 않음만 본다. → D5의 "기존 테스트로 갈음"을 철회하고 **MVC 응답 직렬화 단언 테스트를 전환 전에 추가**(DTO의 `LocalDateTime` 필드가 ISO 로컬 일시 문자열, null 필드 키 생략) + 실서버에서 로그인 세션으로 대표 API(`GET /admin/api/logs`) 응답의 날짜 형식·null 키 생략을 확인.

## 1. Context

#97은 `spring-boot-starter-classic`·`spring-boot-starter-test-classic`으로 Boot 3.x와 같은 자동 구성 가용성을 유지했다. classic은 **약 90개 Boot 모듈**(webflux·jetty·kafka·session·h2console·restclient·jackson2 …)을 런타임 클래스패스에 올리고, 테스트 쪽은 30여 개 `*-test` 모듈을 올린다. Boot 4가 권장하는 형태는 실제로 쓰는 기술의 모듈식 스타터만 선언하는 것이며, classic은 이행용이다.

목표: classic 두 개를 제거하고 실제 사용 기술의 모듈식 스타터만 선언해 자동 구성 표면을 줄인다. **동작 계약·스키마·인가 정책 무변경.**

## 2. 정찰 사실 (추측 아님 — 근거 명시)

### 2-1. 런타임에 실제로 켜지는 자동 구성 (classic 상태, dev 기동 `--debug` 조건 평가 리포트)
최상위 자동 구성 63개의 소속 모듈:
`webmvc`·`servlet`·`tomcat`·`jackson`·`http-converter`·`thymeleaf`·`security`·`validation`·`mail`·`flyway`·`jdbc`·`hibernate`·`data-jpa`·`data-commons`·`persistence`·`transaction`·`autoconfigure`(Aop·TaskExecution·TaskScheduling)·`devtools`·actuator 계열(`actuator-autoconfigure`·`health`·`micrometer-metrics`·`micrometer-observation`) — 그리고 **classic만이 들여오는** 다음 셋:
- `restclient`(RestClient·RestTemplate 빌더 + observation 4개), `http-client`(ImperativeHttpClient·HttpClientMetrics) — `src/main`에 RestClient/RestTemplate 사용 0건.
- `jackson2`(`Jackson2AutoConfiguration`·`Jackson2EndpointAutoConfiguration` — Jackson 2 `ObjectMapper` 빈) — main 코드는 Jackson 3(`tools.jackson`)만 쓴다. Jackson 2 **라이브러리**는 SpringDoc(swagger-core) 경유로 계속 남지만 Boot의 Jackson 2 자동 구성 모듈은 classic만이 들여온다(`dependencyInsight`).
- cache 계열(`CacheMeterBinderProvidersConfiguration` 등) — `@EnableCaching`·`@Cacheable`·`CacheManager` 사용 0건(Caffeine은 레이트리밋에서 라이브러리로 직접 사용).

### 2-2. AOP(감사 로그 `@AdminActionLogged`)의 의존
`AopAutoConfiguration.AspectJAutoProxyingConfiguration`은 `aspectjweaver`가 있어야 켜진다. 현재 `aspectjweaver`는 classic 외에 **`spring-boot-data-jpa` → `spring-aspects` 경유로도** 들어온다(`dependencyInsight`). 즉 제거해도 지금은 유지되지만, 감사 로그라는 핵심 기능이 JPA 모듈의 전이 의존에 기대는 상태다.

### 2-3. 테스트가 쓰는 Boot 테스트 모듈
- 직접 import: `spring-boot-test`, `spring-boot-webmvc-test`(`WebMvcTest`·`AutoConfigureMockMvc` — 22·17개 클래스), `spring-boot-data-jpa-test`(`DataJpaTest` 9), `spring-boot-jdbc-test`(`AutoConfigureTestDatabase`), `spring-boot-testcontainers`(`@ServiceConnection`).
- **암묵 의존(import에 안 드러남)**: `spring-boot-security-test`의 `AutoConfigureMockMvc.imports`가 MockMvc 슬라이스에 `SecurityAutoConfiguration`·`UserDetailsServiceAutoConfiguration`·`SecurityFilterAutoConfiguration`·`ServletWebSecurityAutoConfiguration`·`SecurityMockMvcAutoConfiguration`을 넣는다. 빠지면 `@WebMvcTest`의 보안 필터 구성과 `@WithMockUser`(20개 파일) 동작이 달라져, 거부 단언 테스트가 **엉뚱한 이유로 통과**할 수 있다.
- Flyway를 슬라이스(`@DataJpaTest`)에 넣는 `AutoConfigureDataSourceInitialization.imports`는 `spring-boot-flyway`(런타임 모듈)와 `spring-boot-jdbc-test`에 있다.
- 실제 AOP 경로 회귀 감지: `AdminActionLogCommitOrderIntegrationTest` 등 실 DB 통합 테스트가 감사 행 저장을 확인한다.

### 2-4. Maven Central 확인(4.0.8 존재)
`spring-boot-starter-webmvc`(= starter + jackson + tomcat + http-converter + webmvc), `-aspectj`, `-webmvc-test`(+ jackson-test·resttestclient), `-security-test`(+ `spring-boot-security-test`), `-data-jpa-test`(+ `-jdbc-test`), `-thymeleaf-test`, `-validation-test`, `-mail-test`, `-actuator-test`, `-flyway-test`. (`spring-boot-starter-testcontainers`는 없음 — 모듈 `spring-boot-testcontainers` 직접 사용 유지)

## 3. 설계 결정

### D1. 런타임 스타터 구성
- 선택지: (A) classic 제거 + 모듈식 스타터 전면 선언, (B) classic만 제거하고 `spring-boot-starter-web`(deprecated) 유지.
- **결정: A.** `starter-web`은 Boot 4에서 deprecated(`starter-webmvc`로 대체). 같은 PR에서 정리하지 않으면 다시 한 번 손대야 한다.
- 최종 목록: `starter-webmvc`(web 대체), `starter-thymeleaf`, `starter-security`, `starter-data-jpa`, `starter-validation`, `starter-mail`, `starter-actuator`, `starter-flyway`(기존) + `flyway-mysql`, `starter-aspectj`(D2), `caffeine`, `devtools`, `thymeleaf-extras-springsecurity6`, `springdoc`, QueryDSL, MariaDB, Lombok — 비-Boot 의존은 그대로.

### D2. AspectJ를 명시 선언할지
- 선택지: (A) `spring-boot-starter-aspectj` 명시, (B) `data-jpa` 전이 의존에 맡김.
- **결정: A.** 감사 로그는 보안·운영 핵심 기능인데 B는 JPA 모듈 구성 변경 하나로 조용히 꺼질 수 있다(Boot 4에서 이미 스타터 구성이 크게 바뀐 전례). 비용은 한 줄.

### D3. 테스트 스타터 구성
- 선택지: (A) 기술별 test 스타터, (B) `*-test` 모듈 jar만 최소 선언.
- **결정: A** — `starter-test`(기본) 대신 아래가 각자 포함: `starter-webmvc-test`, `starter-security-test`, `starter-data-jpa-test`(jdbc-test 포함), 그리고 기존 `spring-boot-testcontainers`·`testcontainers-mariadb`·`spring-security-test`·`junit-platform-launcher` 유지. 스타터는 Boot가 정의한 조합이라 슬라이스 imports(§2-3)가 맞물리도록 설계돼 있다. B는 암묵 의존을 사람이 찾아내야 한다(security-test가 그 예).
- `thymeleaf-test`·`validation-test`·`mail-test`·`actuator-test`·`flyway-test`는 이 테스트들이 쓰는 별도 API가 없어 **넣지 않는다**(과설계 회피). 필요하면 컴파일·테스트 실패로 드러난다 — 단, §4 R2처럼 "조용히 통과"할 수 있는 것은 아래 검증으로 막는다.
- `spring-security-test` 직접 선언은 `starter-security-test`가 포함해도 **유지**(무관한 정리 금지, 테스트가 직접 import하는 라이브러리).

### D4. Jackson 2 자동 구성 소멸
- 선택지: (A) 사라지게 둔다, (B) `spring-boot-jackson2` 모듈을 명시해 유지.
- **결정: A.** Jackson 2 자동 구성은 Boot에서 deprecated이고 이 앱에 소비자가 없다(§2-1). 단 SpringDoc이 Spring 빈 `ObjectMapper`(Jackson 2)에 기대는지 정적으로 확정할 수 없으므로 **실서버에서 `/v3/api-docs`·Swagger UI로 확인**한다(R3). 실패하면 멈추고 B를 재검토.

### D5. 검증 방법 — 조건 평가 리포트 diff
- 전환 전후 dev 기동 `--debug`의 **Positive matches 전체 항목(최상위·중첩 구성·`#빈메서드`)과 Unconditional classes를 diff**한다(v2·v3). 보증 범위는 "자동 구성 클래스·조건부 빈 메서드 수준"이며, 조건 없는 빈 메서드는 리포트에 나오지 않을 수 있다(v3).
- **허용된 제거(예상, 정확한 클래스명 — 하위 항목 포함)**: `HttpServiceClientPropertiesAutoConfiguration`(Unconditional, http-client, v3), `RestClientAutoConfiguration`·`RestClientObservationAutoConfiguration`·`RestTemplateAutoConfiguration`·`RestTemplateObservationAutoConfiguration`(restclient), `ImperativeHttpClientAutoConfiguration`·`HttpClientAutoConfiguration`·`HttpClientMetricsAutoConfiguration`(http-client), `Jackson2AutoConfiguration`(jackson2), `CacheMeterBinderProvidersConfiguration`과 `GenericCacheConfiguration`·`SimpleCacheConfiguration`·`NoOpCacheConfiguration`·`CaffeineCacheConfiguration`(cache). `Jackson2EndpointAutoConfiguration`은 **유지 예상**(v2). **그 밖의 제거나 추가가 하나라도 나오면 멈추고 원인을 확인한다.**
- MVC 메시지 컨버터 직렬화(v2·v4): 정찰상 classic에서도 Jackson 2 컨버터 구성은 비활성이었다. 실제 직렬화 결과는 (a) 전환 **전에** 추가하는 MVC 경로 단언 테스트 — Boot 구성 매퍼를 타는 실 컨텍스트(`@SpringBootTest`+MockMvc 또는 `@WebMvcTest`)에서 `LocalDateTime` 필드가 `yyyy-MM-ddTHH:mm:ss(.n)` 문자열이고 null 필드 키가 없음 — 와 (b) 실서버 `GET /admin/api/logs` 응답 확인으로 보증한다. 대상 테스트는 구현 단계에서 기존 DTO 응답 테스트 중 Boot 매퍼를 타는 것을 골라 단언을 추가하고 계획에 기록한다.
- 이유: 모듈식 전환의 핵심 위험은 "테스트에 안 걸리는 자동 구성 누락"이고, 리포트 diff는 그것을 직접 관측한다.

### 설계 제약
- 동작 계약·`application*.yml` 키·`SecurityConfig`·마이그레이션 변경 금지.
- `build.gradle`의 BOM 오버라이드(Jackson 3.1.7·2.21.7·Tomcat 11.0.26)는 그대로(이번 범위 밖, 버전 변화 없음 확인).

## 4. 위험과 검증

| ID | 위험 | 검증 |
|---|---|---|
| R1 | 실제로 쓰던 자동 구성이 사라짐(테스트에 안 걸림) | D5 리포트 diff(허용 목록 외 0건), 실서버 골든 패스 |
| R2 | `@WebMvcTest` 보안 구성·`@WithMockUser` 변화로 거부 테스트가 엉뚱한 이유로 통과 | `starter-security-test` 포함(D3). **변이 실험(v2)**: `org.springframework.boot:spring-boot-security-test`를 제외하고 `testRuntimeClasspath`에서 부재 확인 → `ApiSecurityConfigTest`·`SecurityConfigTest` 실행 → 결과를 **컨텍스트 생성 실패 / 요청 단언 실패 / 통과**로 분류해 기록. 정상 구성의 판정 기준: 같은 엔드포인트의 미인증 401 JSON·USER 403·ADMIN+CSRF 성공이 모두 성립 |
| R3 | SpringDoc이 Jackson 2 빈에 의존 | 정적 확인: SpringDoc 3.0.3 `ObjectMapperProvider`는 swagger `Json/Json31.mapper()` 사용(1라운드 리뷰). 실서버 `/v3/api-docs` 200 + **전후 정규화 JSON의 `paths`·`components` 내용 동일**(v3 — 키 정렬·`servers` 제거 후 비교, operation·매개변수·요청/응답·required·enum·`$ref`·security 포함), Swagger UI 렌더 |
| R4 | AOP 누락 → 감사 로그 미기록 | `starter-aspectj` 명시(D2), 실 DB 감사 통합 테스트, 실서버에서 감사 대상 동작 후 활동 로그 확인 |
| R5 | prod 이미지·기동 차이(devtools 제외 등) | 로컬 prod 이미지 빌드 + Trivy(CI 조건), CI `prod-smoke` |
| R6 | 라이브러리 해석 버전 변화(BOM 오버라이드 무력화 등) | 전후 `runtimeClasspath`에서 spring-webmvc·Jackson 2/3·Tomcat 버전 동일 확인 |
| R7 | 런타임 클래스패스 축소로 다른 라이브러리의 선택적 통합이 꺼짐(예: SpringDoc의 actuator·security 연동) | 리포트 전체 diff에 SpringDoc 하위 구성 포함(허용 목록 외 변화 0) + R3의 문서 계약 비교 |
| R8 | 제거되는 테스트 모듈의 `spring.factories`(ContextCustomizerFactory 등) 소멸로 테스트 환경 기본값 변화(v2) | 구현 단계에서 classic 테스트 모듈 중 제거되는 것들의 `spring.factories`를 목록화해 영향 판단·기록. 알려진 것: micrometer-metrics-test의 metrics export 기본값(수용) |

## 5. 작업 순서
1. 기준선: classic 상태 리포트(정찰 시 확보) 보관, 런타임 버전 목록 저장, classic 상태 `/v3/api-docs` **원문 JSON** 저장(v3), MVC 직렬화 단언 테스트 추가 후 classic 상태에서 통과 확인(v4).
2. `build.gradle` 전환(D1~D3) → `compileJava compileTestJava`.
3. `./gradlew test` 전체 → 실패 시 원인 분류(누락 모듈 vs 환경).
4. R2 변이 실험(security-test 제외 → 슬라이스 보안 테스트 실패 확인 → 원복).
5. dev 기동 `--debug` → D5 diff, R3·R4 실서버 확인, playwright 골든 패스.
6. UTC 전체 테스트, prod 이미지 빌드 + 로컬 Trivy, 런타임 버전 diff(R6).
7. 문서(CLAUDE.md "classic" 서술, build.gradle 주석) → code-review-loop → commitPR.
