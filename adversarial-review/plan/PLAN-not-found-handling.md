# PLAN — 핸들러 없는 경로의 404 응답 정정

> 작성일: 2026-08-06
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "후속 과제 — ① prod 프로파일 완료 시 발견" (234행)
> 관련 기록: `docs/troubleshooting.md:415` "핸들러가 아예 없는 경로(정적 리소스 미존재 등)가 404가 아니라 500으로 응답됨" — 현재 "아직 고치지 않음" 상태

## 개정 이력

- v1 (2026-08-06): 최초 작성(plan 모드 정찰·설계 결과). `GlobalApiExceptionHandler`·`PublicWebExceptionAdvice`·`CustomErrorController`·`SecurityConfig`·`PublicNoticeController.attachment()`(기존 `sendError`+null 패턴) 실측. `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-08-06, codex 리뷰 1차 반영 — no-ship, 6개 지적 중 2개는 사용자 결정, 4개는 즉시 수용):
  - **결정 필요→해결(높음1, Accept 협상)**: `ResponseEntity.status(...).body(body)`가 콘텐츠 협상을 그대로 타서 `Accept: text/html`/`application/xml`이면 `ApiErrorResponse`를 쓸 converter가 없어 핸들러 자체가 실패할 수 있다는 지적. **수용** — 기존 `ApiAuthenticationEntryPoint`·`ApiAccessDeniedHandler`가 같은 문제를 피하려 `MediaType.APPLICATION_JSON`을 명시하는 것과 동일하게, `.contentType(MediaType.APPLICATION_JSON)`을 명시하도록 코드 스니펫 수정(아래 결정 2). 테스트에 `Accept: text/html`·`application/xml` 케이스 추가(아래 테스트 계획 수정).
  - **결정 필요→해결(높음2, CSRF 테스트 무효)**: `addFilters=false` 테스트로는 CSRF 필터 자체가 실행되지 않고, MockMvc는 `sendError` 이후 컨테이너 ERROR 디스패치를 하지 않아 테스트 4가 주장하는 위험을 전혀 증명하지 못한다는 지적. **사용자 결정 없이 직접 재검증 필요 판단 → codex 인용 근거를 context7(Spring Security 6.5 공식 문서)로 교차검증**: `CsrfFilter`는 `OncePerRequestFilter`를 상속하고 자체 오버라이드가 없어 `shouldNotFilterErrorDispatch()` 기본값(ERROR 디스패치 스킵)을 그대로 따른다 — codex 주장과 일치, CSRF는 ERROR 재디스패치에서 재검증되지 않는다. **다만 codex가 언급하지 않은 별도 사실을 추가로 확인**: `AuthorizationFilter`는 `OncePerRequestFilter`가 아니며 `setFilterErrorDispatch` 기본값이 **true**라 ERROR 디스패치에서도 인가를 재평가한다(Spring Security 6.5 공식 문서 "Authorize HTTP Requests > Matching By Dispatcher Type"). 그러나 컨테이너 ERROR 포워드의 대상 URI는 원 요청 경로가 아니라 `server.error.path`(`/error`) 자체이므로, `SecurityConfig`의 어떤 역할 기반 규칙(`/admin/**` 등)에도 걸리지 않고 마지막 `anyRequest().permitAll()`에 항상 매칭된다 — 구조적으로 403 역전 가능성이 없다. 리스크 서술을 이 근거로 정정(아래 리스크 섹션 수정)하고, `addFilters=false` 테스트는 "MVC 예외 분기만 검증"으로 범위를 명시. 실제 CSRF·인가 재평가 무영향은 Playwright(POST `/does-not-exist` 실 CSRF 토큰, ADMIN 인증 상태의 존재하지 않는 `/admin/**` 하위 경로)로 종단 확인(아래 테스트 계획 수정).
  - **수용(중간3, Security 매처 불일치)**: `request.getRequestURI()` 문자열 비교가 `SecurityConfig`의 `PathPatternRequestMatcher("/admin/api/**")`와 1:1이 아니라는 지적(컨텍스트 경로·세미콜론 매트릭스 파라미터 등에서 판정이 갈릴 수 있음) — 타당. Security와 완전히 동일한 `RequestMatcher` 인스턴스를 재사용하도록 설계 변경(아래 결정 6 신설, `SecurityConfig.java` 소규모 수정 수반 — 인가 규칙 변경 아님, 별도 고지).
  - **수용(중간4, 직접 호출 테스트 무효)**: `NoHandlerFoundException`을 핸들러 메서드에 직접 주입해 호출하면 `@ExceptionHandler` 배열 등록·resolver 선택·catch-all 대비 우선순위를 전부 우회한다는 지적 — 타당. 실제 디스패치로 트리거하는 방식으로 테스트 설계 변경(아래 결정 3·테스트 계획 수정).
  - **수용(중간5, WebMvcTest 컨텍스트 기동 조건 누락)**: 이 저장소의 MVC 슬라이스는 `AdminSidebarAdvice`(`MenuService` 필요)·`AdminViewAdvice`(`AdminSecurityService` 필요)가 자동 포함되어 mock 빈 없이는 컨텍스트 기동이 실패한다는 지적, `SecurityConfigTest`가 이미 이 mock들을 제공하는 선례 인용 — 타당. 새 슬라이스를 만드는 대신 **기존 `SecurityConfigTest`에 테스트 케이스를 추가**해 이 문제를 원천 회피(아래 변경 파일 섹션 수정).
  - **반박→사용자 결정(낮음6, admin 접두사 경계 오류)**: `CustomErrorController`의 `startsWith("/admin")`이 `/administrator/missing`도 관리자 404로 오분류하는 기존 결함이며, 이번 변경이 그 노출 범위를 넓힌다는 지적 — 결함 자체는 이번 PR이 만든 게 아니라는 점에서 반박 여지가 있었으나, **사용자 확정(2026-08-06): 이번 PR에서 함께 고침**(노출 범위 확대가 이번 변경과 직접 연결되어 같은 PR에서 다루는 게 자연스럽다는 판단). `CustomErrorController.java` 수정 추가(아래 결정 7·변경 파일 섹션 신설).
  - **정확성 보정 2건(핸들러 타입 null 설명·신규 advice 반환 타입 제약 주장)**: 지적대로 부정확한 서술이었음을 확인 — 본문에서 관련 서술 제거(설계 결론 자체는 변경 없음, 결정 1의 "같은 클래스에 두는 이유"는 advice 순서 의존성 회피만으로 재서술).
- v3 (2026-08-06, codex 리뷰 2차 반영 — no-ship, 3개 지적 전부 수용, 사용자 결정 불필요):
  - **수용(중간1, 매처 단일화 누락)**: v2가 "패턴 문자열이 두 곳에 존재하면 조용히 어긋난다"는 이유로 `SecurityConfig`↔`GlobalApiExceptionHandler` 사이만 단일화했으나, 실제로는 `AdminSessionExpiredStrategy.java:24`도 완전히 동일한 `private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**")`를 독립적으로 소유하고 있다는 지적 — 코드 열람으로 실측 확인, 타당하다(이미 세션 만료 JSON 401 분기가 `SecurityConfig`·`GlobalApiExceptionHandler`와 별개로 세 번째 사본을 갖고 있었다). `AdminSessionExpiredStrategy`도 `GlobalApiExceptionHandler.API_MATCHER`를 참조하도록 변경 파일에 추가한다(아래 결정 6·변경 파일 섹션 수정) — v2가 표방한 "단일 인스턴스 재사용" 원칙을 실제로 완성한다.
  - **수용(중간2, 존재하지 않는 Boot 프로퍼티)**: `spring.mvc.throw-exception-if-no-handler-found`가 Spring Boot 3.5.16에 배선되지 않는다는 지적 — codex는 `WebMvcProperties`·`DispatcherServletAutoConfiguration` 바이트코드 확인을 근거로 들었고, **직접 재검증**했다: `javap`로 실제 리졸브된 `spring-webmvc-6.2.19.jar`의 `DispatcherServlet` 생성자 바이트코드를 열람한 결과 `throwExceptionIfNoHandlerFound` 필드에 `iconst_1`(= `true`)이 즉시 대입되어 **기본값이 이미 `true`**임을 확인했고, `spring-boot-autoconfigure-3.5.16.jar`의 `WebMvcProperties` 클래스에는 이 이름에 대응하는 필드·getter/setter가 전혀 없음을 `javap -p`로 확인했다 — codex 주장과 정확히 일치. 무효 프로퍼티를 테스트 설계·주석에서 제거하고 `spring.web.resources.add-mappings=false` 단독으로 실제 디스패치를 트리거하도록 수정한다. "동작 안 하면 좁은 계약으로 대체"라는 v2의 회피 문구도 제거 — 이 조합이면 실제로 도달하므로 실패 시 슬라이스 구성 결함으로 보고 원인을 찾는다(아래 결정 3·리스크 섹션 수정).
  - **수용(중간3, CustomErrorController 컨텍스트 경로 미고려)**: v2가 API 판정에서 raw 문자열 비교를 기각한 근거로 컨텍스트 경로 불일치를 직접 들었으면서(결정 6), `CustomErrorController` 수정에는 같은 결함(컨텍스트 경로 미고려 raw 비교)을 그대로 남겼다는 지적 — 자기모순이므로 타당. `server.servlet.context-path`가 현재 어느 프로파일에도 설정되어 있지 않아(실측 확인) 지금 당장 실운영에 영향은 없지만, 결정 6이 세운 원칙과의 내적 일관성을 위해 `request.getContextPath()`를 제거한 뒤 `/admin` 세그먼트 경계를 판정하도록 수정한다(아래 결정 7·테스트 계획 수정).
- v4 (2026-08-06, codex 리뷰 3차 반영 — no-ship, 2개 지적 전부 수용, 사용자 결정 불필요):
  - **수용(중간1, NoHandlerFoundDispatchTest 컨텍스트 기동 재발)**: v2가 codex 지적 5(WebMvcTest 컨텍스트 기동 조건 누락)를 해소하겠다며 기존 `SecurityConfigTest` 재사용으로 방향을 잡아놓고, 정작 `NoHandlerFoundDispatchTest`는 새 독립 슬라이스로 신설해 같은 문제를 재현했다는 지적 — `@AutoConfigureMockMvc(addFilters = false)`는 MockMvc의 필터 등록만 끌 뿐 `@ControllerAdvice` 빈 스캔·생성은 막지 않으므로, `AdminSidebarAdvice`(`MenuService` 필요)·`AdminViewAdvice`(`AdminSecurityService` 필요)가 여전히 슬라이스에 포함돼 mock 빈 없이는 컨텍스트 기동이 실패한다는 지적이 타당하다(코드 열람으로 재확인). `SecurityConfigTest`에 통합하지 않은 이유(Security를 배제한 순수 MVC 계약만 검증)는 여전히 유효하므로, 이 신규 슬라이스에서 두 advice 자체를 `@WebMvcTest(excludeFilters=...)`로 스캔 대상에서 제외해 mock 빈 요구 조건을 원천 차단한다(아래 변경 파일 섹션 수정).
  - **수용(중간2, CustomErrorController 매트릭스 파라미터 미해결)**: v2가 raw 문자열 비교를 기각한 근거로 컨텍스트 경로**와** 세미콜론 매트릭스 파라미터 둘 다 들었는데(결정 6), v3의 `CustomErrorController` 수정은 컨텍스트 경로만 제거하고 `path.equals("/admin") || path.startsWith("/admin/")`라는 raw 비교를 그대로 남겨 매트릭스 파라미터 문제(`/admin;v=1/missing`이 Security의 `/admin/**` PathPattern에는 매칭되지만 이 문자열 비교는 매칭시키지 못함)가 해소되지 않았다는 지적 — 자기모순이므로 타당. **직접 재검증**: 로컬에 실제 리졸브된 `spring-web`·`spring-core` 6.2.19 jar로 소형 자바 프로그램을 컴파일·실행해 `PathPatternParser.defaultInstance.parse("/admin/**")`의 실제 매칭 결과를 확인했다 — `/admin`·`/admin/`·`/admin/missing`·`/admin;v=1/missing` 전부 매칭(true), `/administrator/missing`·`/admin-api/missing`은 전부 불일치(false). 즉 **`/admin/**` 패턴 하나로 기존에 두 개 조건(`equals("/admin")`·`startsWith("/admin/")`)이 하던 일을 매트릭스 파라미터까지 정확히 포함해 대체할 수 있다.** raw 문자열 비교를 걷어내고 `PathPattern`+`PathContainer`(컨텍스트 경로를 제거한 문자열에 적용 — `RequestMatcher`는 살아있는 `HttpServletRequest.getRequestURI()`를 다시 읽어 ERROR 디스패치 시점에는 `/error` 자체를 보게 되므로 여기서는 쓸 수 없다) 조합으로 교체한다(아래 결정 7·변경 파일 섹션 수정).
- **4차 확인 리뷰(2026-08-06) 결과: ship.** 3차 리뷰의 2개 지적(`NoHandlerFoundDispatchTest` 컨텍스트 기동 재발, `CustomErrorController` 매트릭스 파라미터)이 전부 충분히 반영됐고, codex가 로컬 Spring 6.2.19/6.5.11 jar 재확인으로 `/admin/**` 매칭 범위·`PathPatternRequestMatcher("/admin/api/**")`의 컨텍스트 경로+매트릭스 파라미터 조합 매칭까지 직접 재검증했다. v1~v3 결정과의 기능적 모순 없음 확인 — `plan-review-loop` 4라운드 종료, 승인 단계로 진행. 구현 전 계획 리뷰라 신규 테스트 실행 결과는 아직 없으며, 계획에 정한 전체 테스트(`./gradlew test`)와 Playwright 종단 검증을 구현 완료 조건으로 유지한다.

## Context

`GlobalApiExceptionHandler`(`com.cms.common.api`)는 selector 없는 전역 `@RestControllerAdvice`이고, 맨 끝에 `@ExceptionHandler(Exception.class)` catch-all(315행)이 있다. Spring MVC가 매핑되는 핸들러·정적 리소스를 찾지 못해 던지는 `NoResourceFoundException`까지 이 catch-all이 잡아 **500 `INTERNAL_ERROR` JSON**으로 바꿔버린다.

같은 증상이 세 번 발견됐다 — `GET /admin/logout`(POST 전용 설계), `GET /favicon.ico`(둘 다 `PLAN-public-notice.md` 실기 검증), prod 프로파일에서 springdoc을 끈 `GET /swagger-ui.html`·`/v3/api-docs`(`PLAN-prod-profile.md` Docker 실기 검증). 매번 "앱 전체 예외 처리를 건드리는 변경"이라 범위 밖으로 미뤄져 `docs/troubleshooting.md:415` 항목이 "아직 고치지 않음" 상태로 남아 있고, 로드맵에 별도 작업으로 기록돼 있다.

보안상 정보 유출은 없지만 (a) 정상적인 404가 500으로 보고돼 모니터링에서 실장애와 섞이고, (b) 브라우저 사용자가 기존 `error/404.html` 대신 날 JSON을 본다. 이번 작업의 결과는 **미매핑 경로가 API면 JSON 404, 페이지면 기존 404 HTML**로 응답하는 것이다.

**범위 확정(2026-08-06 사용자 결정)**: 404만 다룬다. 같은 catch-all이 삼키는 형제 결함(`HttpRequestMethodNotSupportedException`→405여야 함, `MethodArgumentTypeMismatchException`→400이어야 함 — `GET /admin/api/members/abc`로 실측 확인)은 이번 범위에서 제외하고 로드맵 후속 과제로만 기록한다.

## 스키마 · 인가 정책 영향

- **스키마 변경: 없음.**
- **인가 정책 변경: 없음, 단 `SecurityConfig.java`·`AdminSessionExpiredStrategy.java` 파일 자체는 수정한다(v2·v3, 결정 6).** 매칭 패턴(`/admin/api/**`)·역할 요구사항·세션 만료 시 응답 분기 등 기존 동작은 한 글자도 바뀌지 않는다 — 세 클래스가 각자 갖고 있던 동일한 `API_MATCHER` 상수 선언을 `GlobalApiExceptionHandler` 한 곳으로 모으고 나머지 둘은 정적 임포트로 참조하도록 바꾸는 것뿐이다(raw 문자열 비교 대신 Security와 동일한 매처 인스턴스를 재사용하기 위함, codex 1차·2차 리뷰 지적). `anyRequest().permitAll()`이라 컨테이너 ERROR 디스패치(`/error`)는 이미 인가에 걸리지 않으며, 이번 변경은 그 디스패치를 트리거하는 조건(404 판정 위치)만 바꾼다.
- **신규 의존성: 없음.**

## 정찰로 확인한 사실 (설계 근거)

- **`PublicWebExceptionAdvice`와 충돌하지 않는다.** `NoResourceFoundException`은 실제 `ResourceHttpRequestHandler`가 처리하다 던지므로 handler type이 `null`인 것은 아니다 — `PublicWebExceptionAdvice`(`basePackages="com.cms.publicweb"`)가 이를 잡지 못하는 이유는 그 handler type이 `com.cms.publicweb` 패키지에 속하지 않기 때문이다(`HandlerTypePredicate` 불일치). handler가 정말 `null`인 경우는 `NoHandlerFoundException` 쪽뿐이다(codex 1차 리뷰 정확성 보정). 어느 쪽이든 결론은 같다 — 우선순위(`@Order`) 문제가 아니라 selector 없는 전역 advice만 이 요청들에 적용 후보가 된다(`docs/troubleshooting.md:427` 기록과 일치).
- **같은 클래스에 더 구체적인 핸들러를 추가하면 이긴다.** `ExceptionHandlerMethodResolver`가 클래스 내부에서 예외 타입 근접도로 고르므로, `NoResourceFoundException` 전용 메서드가 `Exception` catch-all보다 우선 매칭된다. 별도 advice 클래스나 `@Order` 조정이 필요 없다.
- **HTML 404로 되돌릴 경로가 이미 프로젝트에 있다.** `response.sendError(404)` + `null` 반환 → 컨테이너 `/error` 디스패치 → `CustomErrorController`(`com.cms.error`)가 `jakarta.servlet.error.request_uri`가 `/admin`으로 시작하면 `error/admin/404`, 아니면 `error/404`로 분기. `PublicNoticeController.attachment()`(87~106행)가 이미 이 패턴을 쓰며, `HttpEntityMethodProcessor`가 반환값 null이면 `requestHandled=true`로 종료해 `ResponseEntity` 반환 타입에서도 안전하다는 근거를 주석으로 남겨두었다.
- `error/404.html`·`error/admin/404.html` 모두 admin 레이아웃 프래그먼트에 의존하지 않는 단독 HTML이라 모델 주입 없이 렌더링된다(`timestamp`는 `th:if`로 선택적).

## 핵심 설계 결정

### 1. 핸들러를 `GlobalApiExceptionHandler` 안에 추가한다 (신규 advice 클래스 신설 기각)

같은 클래스 내 구체 예외 우선 규칙만으로 catch-all을 이기므로 advice 간 순서 의존이 생기지 않는다. 신규 advice로 분리하면 selector가 없어야 하므로(핸들러 없는 요청에 적용되려면) `@Order`로 전역 advice보다 앞세워야 하는 부담이 추가된다. (v1의 "반환 타입이 Object가 되어 안전하지 않다"는 근거는 codex 1차 리뷰가 부정확함을 지적 — `ResponseEntity<ApiErrorResponse>` + `sendError`+null 조합은 신규 advice에서도 그대로 쓸 수 있다. 순서 의존성 회피만이 유효한 근거다.) `docs/troubleshooting.md:431`이 지목한 위치이기도 하다.

### 2. 응답 형식은 경로로 분기하고, JSON 응답은 Content-Type을 명시한다 (Accept 헤더 협상 기각)

`/admin/api` 하위면 기존 `ApiErrorResponse` JSON 404, 그 외는 `sendError(404)` + `null` 반환으로 기존 HTML 404 페이지. CLAUDE.md 보안/RESTful 규칙이 "`/admin/api/**`에는 HTML 리다이렉트나 기본 오류 페이지를 반환하지 않는다"를 명시하고 있어 경로 기준이 규칙과 1:1로 대응한다. Accept 헤더 협상은 브라우저(`text/html`)와 curl(`*/*`)이 갈려 같은 경로가 요청자에 따라 다른 형식을 내는 예측 불가성이 생겨 기각.

**codex 1차 리뷰로 발견(수용)**: `ResponseEntity.status(...).body(body)`만으로는 Spring MVC가 여전히 콘텐츠 협상을 수행한다 — 요청의 `Accept`가 `text/html`이나 `application/xml`이면 `ApiErrorResponse`를 쓸 수 있는 `HttpMessageConverter`가 없어 예외 핸들러 자체가 실패할 수 있다. 기존 `ApiAuthenticationEntryPoint`·`ApiAccessDeniedHandler`가 `response.setContentType(MediaType.APPLICATION_JSON_VALUE)`를 명시해 이 문제를 피하는 것과 동일하게, `.contentType(MediaType.APPLICATION_JSON)`을 명시한다(아래 코드 스니펫 반영). Playwright로 API URL에 직접 접근하면 브라우저가 `text/html`을 보내므로 이 문제는 실기 검증에서 바로 드러났을 사안이었다.

### 3. `NoHandlerFoundException`도 같은 핸들러에 함께 등록하고, 실제 디스패치로 검증한다

현재 설정(`spring.web.resources.add-mappings` 기본 true)에서는 `/**`가 `ResourceHttpRequestHandler`로 매핑돼 실제로는 `NoResourceFoundException`만 발생하고 `NoHandlerFoundException`은 도달하지 않는다. 그럼에도 함께 등록하는 이유는 정적 리소스 매핑을 끄는 순간 완전히 동일한 결함이 되살아나기 때문이며, 비용은 `@ExceptionHandler` 배열 원소 하나다.

**codex 1차 리뷰로 발견(수용) — 검증 방법 변경**: v1은 핸들러 메서드에 `NoHandlerFoundException`을 직접 생성해 주입하는 단위 테스트로 이 계약을 고정하려 했으나, 이 방식은 `@ExceptionHandler` 배열 등록·`ExceptionHandlerMethodResolver`의 예외 선택·catch-all 대비 우선순위를 전부 우회한다 — 메서드가 존재하기만 하면 통과하는 테스트라 지적이 타당하다. `spring.web.resources.add-mappings=false`로 정적 리소스 핸들러(`/**`)를 끄면 `NoResourceFoundException`이 아니라 실제 "핸들러 없음" 상태에 도달하며, `DispatcherServlet`이 그 상태에서 `NoHandlerFoundException`을 던진다.

**codex 2차 리뷰로 발견(수용) — 존재하지 않는 프로퍼티 제거**: v2는 여기에 `spring.mvc.throw-exception-if-no-handler-found=true`도 함께 켜야 한다고 서술했으나, 이 프로퍼티는 Spring Boot 3.5.16에 존재하지 않는다. **직접 재검증(javap로 실제 리졸브된 jar 바이트코드 열람)**: `spring-webmvc-6.2.19.jar`의 `DispatcherServlet` 생성자는 `throwExceptionIfNoHandlerFound` 필드에 `iconst_1`(`true`)을 즉시 대입한다 — 즉 이 프레임워크 버전의 기본값 자체가 이미 `true`다. `spring-boot-autoconfigure-3.5.16.jar`의 `WebMvcProperties` 클래스에는 이 이름에 대응하는 필드·getter/setter가 전혀 없다 — Boot가 이 값을 배선하는 프로퍼티 자체를 제공하지 않는다(과거 Boot 버전에 있던 `spring.mvc.throw-exception-if-no-handler-found`가 제거된 것으로 보인다). 따라서 `spring.web.resources.add-mappings=false` **단독**으로 실제 `NoHandlerFoundException` 디스패치가 성립한다. v2의 "동작 안 하면 좁은 계약만 검증하는 대체 테스트로 낮춘다"는 회피 문구는 무효 전제에 기댄 것이었으므로 제거한다 — 이 조합은 실제로 도달하므로, 구현 중 테스트가 실패하면 범위를 낮추지 않고 슬라이스 구성 결함(다른 자동 설정 개입 등)을 찾아 고친다.

### 4. 에러 코드는 기존 `RESOURCE_NOT_FOUND`를 재사용한다

CLAUDE.md 상태 코드 규칙표의 404는 "자원 없음" 하나뿐이고, 신규 코드 추가는 클라이언트 응답 계약의 확장이다. 메시지만 경로 오류에 맞게 "요청하신 경로를 찾을 수 없습니다."로 둔다(`ApiErrorResponse`의 `path` 필드에 이미 요청 URI가 담기므로 메시지에 경로를 다시 넣지 않는다).

### 5. 로깅을 추가하지 않는다

이 클래스에는 현재 로거 자체가 없다(500 catch-all도 무로깅). 404는 정상 트래픽이라 ERROR 로깅은 오히려 노이즈이며, "500이 로깅되지 않는다"는 별개 결함은 이번 범위 밖(후속 과제로만 기록).

### 6. `/admin/api/**` 판정은 `SecurityConfig`의 `RequestMatcher` 인스턴스를 재사용한다 (raw 문자열 비교 기각, 사용자 확정)

**codex 1차 리뷰로 발견(수용)**: v1의 `uri.startsWith("/admin/api/")` 문자열 비교는 `SecurityConfig`가 실제로 쓰는 `PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**")`와 1:1이 아니다 — 컨텍스트 경로가 붙거나(`/cms/admin/api/...`), 세미콜론 매트릭스 파라미터가 섞인 경로(`/admin/api;v=1/foo`)에서 두 판정이 갈릴 수 있다. Security는 API로 취급하는데 404 핸들러는 HTML로 응답하는 불일치가 생기면 "규칙과 1:1 대응"이라는 애초 설계 의도가 깨진다.

**사용자 확정(2026-08-06): `SecurityConfig`가 쓰는 것과 동일한 `RequestMatcher` 인스턴스를 재사용**한다(별도 중복 상수 정의 기각 — 패턴 문자열이 두 곳에 존재하면 하나만 바뀌었을 때 조용히 어긋난다). 다만 물리적 위치는 v1이 전제한 "`SecurityConfig`의 private 상수를 public으로 승격"이 아니라 **반대 방향으로 옮긴다**: 이 프로젝트의 기존 관례는 `config` 패키지가 `common` 패키지를 참조하는 단방향(`ApiAuthenticationEntryPoint`·`ApiAccessDeniedHandler`가 이미 `com.cms.common.api.ApiErrorResponse`를 import)이다. `GlobalApiExceptionHandler`(`com.cms.common.api`)가 `com.cms.config.SecurityConfig`를 참조하면 이 방향이 뒤집혀 `common → config` 역의존이 새로 생긴다. 대신 매처 상수를 `GlobalApiExceptionHandler`에 `public static final RequestMatcher API_MATCHER`로 두고, `SecurityConfig`가 자신의 private 상수 선언을 없애고 `GlobalApiExceptionHandler.API_MATCHER`를 참조하도록 바꾼다 — 기존 의존 방향이 유지되고, 새 파일도 생기지 않는다.

**`SecurityConfig.java` 수정 고지**: 인가 규칙(`/admin/api/**` 매칭 패턴·역할 요구사항)은 전혀 바뀌지 않는다. 바뀌는 것은 "이 패턴 문자열을 어느 클래스가 소유하는가"뿐이다 — `private static final RequestMatcher API_MATCHER = ...` 선언을 제거하고 `import static com.cms.common.api.GlobalApiExceptionHandler.API_MATCHER;`로 대체한다. 동일 상수 재배치이므로 인가 정책 변경이 아니지만, `SecurityConfig.java`를 건드리는 변경이라는 사실 자체는 4단계 승인에서 별도로 짚는다.

**codex 2차 리뷰로 발견(수용) — `AdminSessionExpiredStrategy`도 같은 상수를 참조해야 한다**: v2는 "패턴 문자열이 두 곳에 존재하면 하나만 바뀌었을 때 조용히 어긋난다"를 근거로 들었지만, 실제로는 `AdminSessionExpiredStrategy.java:24`가 완전히 동일한 `private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**")`를 세 번째로 독립 소유하고 있었다(세션 강제 만료 시 JSON 401/HTML 리다이렉트 분기, 코드 열람으로 실측). v2가 막으려던 "조용한 매처 드리프트"를 정확히 재현하는 사례라 그대로 두면 결정 6의 목적이 절반만 달성된다. `AdminSessionExpiredStrategy`도 자신의 private 상수 선언을 제거하고 `GlobalApiExceptionHandler.API_MATCHER`를 참조하도록 함께 바꾼다(아래 변경 파일 섹션 수정) — 이로써 `/admin/api/**` 패턴 문자열은 저장소 전체에서 `GlobalApiExceptionHandler.API_MATCHER` 단 한 곳에만 존재하게 된다.

### 7. `CustomErrorController`의 admin 접두사 경계도 함께 고친다 (사용자 확정)

**codex 1차 리뷰로 발견, 사용자 결정**: `CustomErrorController.handleError()`의 `requestURI.startsWith("/admin")` 분기는 `/administrator/missing`·`/admin-api/missing` 같은 비-admin 경로도 관리자 404(`error/admin/404`)로 잘못 분류하는 기존 결함이다. 이번 변경 전에는 실제 컨트롤러가 없는 `/admin*` 계열 경로에서만 영향이 있었지만, 이번 변경으로 **임의의 모든 미매핑 경로**가 이 분기를 통과하게 되어 노출 범위가 넓어진다. **사용자 확정(2026-08-06): 이번 PR에서 함께 고침**.

**codex 2차 리뷰로 발견(수용) — 컨텍스트 경로 미고려**: v1이 제안했던 `requestURI.equals("/admin") || requestURI.startsWith("/admin/")` 수정은 raw `getRequestURI()`(컨텍스트 경로 포함) 문자열 비교라는 점에서, 바로 위 결정 6이 API 판정에서 raw 문자열 비교를 기각한 근거(컨텍스트 경로 불일치)와 정면으로 모순된다는 지적 — 자기모순이므로 타당하다. `server.servlet.context-path`가 `application.yml`·`application-dev.yml`·`application-prod.yml` 어디에도 설정되어 있지 않아(실측 확인, 전부 기본값인 빈 컨텍스트 경로) 지금 당장 `/cms/admin/missing` 같은 오분류가 실제로 발생하지는 않지만, 결정 6과의 내적 일관성을 위해 `request.getContextPath()`를 먼저 제거한다.

**codex 3차 리뷰로 발견(수용) — 매트릭스 파라미터 미해결**: v2가 raw 문자열 비교를 기각한 근거는 컨텍스트 경로**와** 세미콜론 매트릭스 파라미터 둘 다였는데, v3는 컨텍스트 경로만 고치고 `path.equals("/admin") || path.startsWith("/admin/")`라는 raw 문자열 비교를 그대로 남겨 `/admin;v=1/missing`(Security의 `/admin/**`에는 매칭되지만 이 문자열 비교로는 매칭되지 않음) 같은 경로에서 Security와 `CustomErrorController`의 판정이 다시 갈린다는 지적 — 자기모순이므로 타당. **직접 재검증**: 로컬에 실제 리졸브된 `spring-web-6.2.19.jar`·`spring-core-6.2.19.jar`로 소형 자바 프로그램을 컴파일·실행해 `PathPatternParser.defaultInstance.parse("/admin/**")`의 실제 매칭 결과를 확인했다 — `/admin`·`/admin/`·`/admin/missing`·`/admin;v=1/missing`은 전부 매칭(`true`), `/administrator/missing`·`/admin-api/missing`은 전부 불일치(`false`). **`/admin/**` 패턴 하나가 기존 두 조건(`equals`+`startsWith`)을 매트릭스 파라미터까지 정확히 포함해 그대로 대체한다** — 별도로 `/admin` 단독 패턴을 추가할 필요가 없다(이미 `/admin/**`에 포함됨). raw 문자열 비교를 걷어내고 `PathPattern`+`PathContainer`로 교체한다:

```java
private static final PathPattern ADMIN_PATTERN =
        PathPatternParser.defaultInstance.parse("/admin/**");

...

String contextPath = request.getContextPath(); // 빈 문자열이면 path == requestURI, 무회귀
String path = (requestURI != null && contextPath != null && requestURI.startsWith(contextPath))
        ? requestURI.substring(contextPath.length())
        : requestURI;
if (path != null && ADMIN_PATTERN.matches(PathContainer.parsePath(path))) {
    // 관리자 404
}
```

`RequestMatcher`(`SecurityConfig`/`GlobalApiExceptionHandler`가 쓰는 것)는 여기서 쓸 수 없다 — `RequestMatcher.matches(HttpServletRequest)`는 살아있는 요청 객체의 `getRequestURI()`를 다시 읽는데, 컨테이너 ERROR 디스패치 시점에는 이 값이 원 경로가 아니라 포워드 대상인 `/error` 자체이기 때문이다(원 경로는 `jakarta.servlet.error.request_uri` 속성 문자열로만 존재). 그래서 그 문자열에 직접 적용할 수 있는 `PathPattern`+`PathContainer.parsePath()` 조합을 쓴다. `null` 처리(현재도 `requestURI != null` 선행 체크로 null은 일반 404로 감)는 그대로 유지한다.

## 변경 파일

**수정: `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java`**

`@ExceptionHandler(Exception.class)` 바로 앞에 핸들러 하나 추가하고, `SecurityConfig`와 공유할 `API_MATCHER` 상수를 이 클래스에 둔다(결정 6). 왜 필요한지(catch-all이 404를 500으로 바꿈)와 null 반환 계약(`HttpEntityMethodProcessor`)을 주석으로 남긴다 — 같은 파일의 `MaxUploadSizeExceededException`·`MissingServletRequestPartException` 주석과 같은 형식.

```java
/**
 * /admin/api/** 여부 판정 — SecurityConfig가 인가 규칙에 쓰는 것과 동일한
 * RequestMatcher 인스턴스다. SecurityConfig가 이 상수를 참조한다(반대 방향 —
 * common → config 역의존을 만들지 않기 위해 이 클래스가 소유한다). 문자열
 * 비교(uri.startsWith(...))로 대체하면 컨텍스트 경로·세미콜론 매트릭스
 * 파라미터가 섞인 경로에서 Security의 판정과 어긋날 수 있다.
 */
public static final RequestMatcher API_MATCHER =
        PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");

/**
 * 핸들러 없는 경로(정적 리소스 미존재 포함). NoResourceFoundException은
 * spring.web.resources.add-mappings=true(기본값)일 때 정적 리소스 핸들러가
 * "/**"를 잡고 있어 실제로 발생하는 경로다. NoHandlerFoundException은 그 설정이
 * 꺼지면 대신 발생하므로 함께 등록해둔다(기본 설정에서는 도달하지 않음 —
 * add-mappings=false로 전환한 슬라이스 테스트로 실제 디스패치 계약을 고정한다).
 * 이 핸들러가 없으면 아래 Exception catch-all이 잡아 정상적인 404를 500
 * INTERNAL_ERROR로 바꿔버린다(docs/troubleshooting.md 참조).
 * /admin/api/**는 JSON 404, 그 외는 기존 error/404.html을 그대로 쓰기 위해
 * sendError + null 반환한다 — HttpEntityMethodProcessor는 반환값이 null이면
 * requestHandled=true로 처리하고 종료하므로 ResponseEntity 반환 타입에서도 안전하다
 * (PublicNoticeController.attachment()와 동일 계약). JSON 응답은 Content-Type을
 * 명시한다 — 명시하지 않으면 Accept: text/html 등에서 콘텐츠 협상이 실패할 수 있다
 * (ApiAuthenticationEntryPoint·ApiAccessDeniedHandler와 동일한 이유).
 */
@ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
public ResponseEntity<ApiErrorResponse> handleNoHandlerFound(
        Exception e,
        HttpServletRequest request,
        HttpServletResponse response
) throws IOException {
    if (API_MATCHER.matches(request)) {
        ApiErrorResponse body = ApiErrorResponse.of(
                request.getRequestURI(), "RESOURCE_NOT_FOUND", "요청하신 경로를 찾을 수 없습니다.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
    response.sendError(HttpServletResponse.SC_NOT_FOUND);
    return null;
}
```

신규 import: `jakarta.servlet.http.HttpServletResponse`, `java.io.IOException`, `org.springframework.web.servlet.NoHandlerFoundException`, `org.springframework.web.servlet.resource.NoResourceFoundException`, `org.springframework.security.web.util.matcher.RequestMatcher`, `org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher`.

**수정: `src/main/java/com/cms/config/SecurityConfig.java`** (결정 6 — 인가 규칙 무변경, 상수 소유권만 이동)

```java
// 제거: private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");
// 추가: import static com.cms.common.api.GlobalApiExceptionHandler.API_MATCHER;
```
`filterChain()` 내부에서 `API_MATCHER.matches(request)`를 참조하던 두 곳(exceptionHandling 람다)은 정적 임포트로 그대로 동작한다.

**수정: `src/main/java/com/cms/config/security/AdminSessionExpiredStrategy.java`** (결정 6 v3 — codex 2차 리뷰로 발견)

```java
// 제거: private static final RequestMatcher API_MATCHER = PathPatternRequestMatcher.withDefaults().matcher("/admin/api/**");
// 추가: import static com.cms.common.api.GlobalApiExceptionHandler.API_MATCHER;
```
`onExpiredSessionDetected()` 내부의 `API_MATCHER.matches(request)` 호출은 정적 임포트로 그대로 동작한다. 세션 만료 처리 동작(JSON 401 vs 리다이렉트 분기)은 전혀 바뀌지 않는다.

**수정: `src/main/java/com/cms/error/CustomErrorController.java`** (결정 7 — 사용자 확정, v3에서 컨텍스트 경로 처리·v4에서 매트릭스 파라미터 처리 추가)

```java
// 변경 전:
// if (requestURI != null && requestURI.startsWith("/admin")) {

// 변경 후:
private static final PathPattern ADMIN_PATTERN =
        PathPatternParser.defaultInstance.parse("/admin/**");

// handleError() 내부:
String contextPath = request.getContextPath();
String path = (requestURI != null && contextPath != null && requestURI.startsWith(contextPath))
        ? requestURI.substring(contextPath.length())
        : requestURI;
if (path != null && ADMIN_PATTERN.matches(PathContainer.parsePath(path))) {
```
신규 import: `org.springframework.http.server.PathContainer`, `org.springframework.web.util.pattern.PathPattern`, `org.springframework.web.util.pattern.PathPatternParser`. `contextPath`가 빈 문자열(현재 전 프로파일 기본값)이면 `path == requestURI`와 동일하게 동작해 기존 동작에 회귀가 없다. `/admin/**` 패턴 하나가 `/admin`(루트)·`/admin/`·`/admin/*`·매트릭스 파라미터 섞인 경로까지 전부 올바르게 판정한다(실측 확인, 결정 7 참조) — 기존의 `equals`+`startsWith` 두 조건을 대체한다.

**수정: `src/test/java/com/cms/config/SecurityConfigTest.java`** (codex 지적 5 수용 — 신규 슬라이스 대신 기존 슬라이스에 추가해 `AdminSidebarAdvice`/`AdminViewAdvice` mock 빈 요구 조건을 원천 회피)

기존 `MockConfig`·스텁 컨트롤러를 그대로 쓰는 케이스를 추가:
1. `@WithMockUser(roles = "ADMIN")` + `GET /admin/api/does-not-exist` → 404 + `$.code == RESOURCE_NOT_FOUND` + `content().contentType(APPLICATION_JSON)`
2. 같은 조건 + `Accept: text/html` 헤더 → 여전히 404 JSON(콘텐츠 협상 실패로 다른 상태가 되지 않는지 확인 — codex 지적 1 검증)
3. 같은 조건 + `Accept: application/xml` 헤더 → 여전히 404 JSON
4. `@WithMockUser(roles = "ADMIN")` + `GET /admin/does-not-exist` → 404 (상태코드만 — MockMvc는 컨테이너 ERROR 디스패치를 수행하지 않음)
5. 비로그인 + `GET /does-not-exist`(공개 경로) → 404
6. 비로그인 + `GET /admin/api/does-not-exist` → 기존대로 401 `UNAUTHORIZED`(무회귀 — Security가 DispatcherServlet 도달 전에 차단)
7. 비로그인 + `POST /does-not-exist`(CSRF 토큰 포함) → 404 (MVC 예외 분기까지 도달, 이 슬라이스는 `addFilters=false`가 아니므로 실제 CSRF 필터를 통과한 뒤의 결과 — 단, ERROR 재디스패치 자체는 MockMvc 밖이라 "재디스패치에서 403으로 안 뒤집힘"까지는 이 테스트로 증명되지 않는다는 한계를 테스트 주석에 명시. 종단 확인은 Playwright.)

**신규: `src/test/java/com/cms/common/api/NoHandlerFoundDispatchTest.java`** (codex 1차 지적 4 수용, 2차 지적 2·3차 지적 1 반영)

독립된 최소 `@WebMvcTest` 슬라이스(스텁 컨트롤러 1개 + `@TestPropertySource(properties = "spring.web.resources.add-mappings=false")`)로 `NoHandlerFoundException`이 실제로 `GlobalApiExceptionHandler`까지 도달하는지 확인한다. `spring.mvc.throw-exception-if-no-handler-found`는 **쓰지 않는다** — Boot 3.5.16에 존재하지 않는 프로퍼티이고(`WebMvcProperties`에 대응 필드 없음, javap로 확인), `DispatcherServlet`(Spring Framework 6.2.19)의 `throwExceptionIfNoHandlerFound` 기본값이 이미 `true`이므로(생성자 바이트코드 `iconst_1`로 확인) `add-mappings=false` 단독으로 충분하다.

`@WebMvcTest(controllers = ..., excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {AdminSidebarAdvice.class, AdminViewAdvice.class}))`로 두 admin 전용 advice를 슬라이스 스캔에서 명시적으로 제외한다(codex 3차 지적 1 수용 — `@AutoConfigureMockMvc(addFilters = false)`는 MockMvc의 Security 필터 등록만 끄고 `@ControllerAdvice` 빈 스캔·생성은 막지 않으므로, 두 advice가 여전히 스캔되어 `MenuService`·`AdminSecurityService` mock 없이는 컨텍스트 기동이 실패한다는 지적이 코드 열람으로 재확인됨 — `SecurityConfigTest`가 이 mock들을 제공하는 이유와 동일). `SecurityConfig`는 `@Import`하지 않고 `@AutoConfigureMockMvc(addFilters = false)`로 Security 필터 실행도 배제 — 이 테스트의 목적은 순수 MVC 예외 해석 계약이지 인가가 아니다(그 범위를 테스트 클래스 Javadoc에 명시). 실제 프로퍼티 조합은 도달이 확인된 조합이므로, 구현 중 이 테스트가 실패하면 계약을 좁히지 않고 슬라이스 구성의 원인을 찾아 고친다.

**신규: `src/test/java/com/cms/error/CustomErrorControllerTest.java`** (결정 7 검증, codex 1차 지적 6·2차 지적 3·3차 지적 2)

Spring 컨텍스트 없는 순수 단위 테스트 — `HttpServletRequest`를 mock으로 `jakarta.servlet.error.status_code=404`·`jakarta.servlet.error.request_uri`·`getContextPath()`를 채워 직접 호출:
- 컨텍스트 경로 없음(`getContextPath()` → `""`): `/admin` → `error/admin/404`, `/admin/member/manage` → `error/admin/404`, `/admin;v=1/missing` → `error/admin/404`(매트릭스 파라미터 — 결정 7 v4 검증), `/administrator/missing` → `error/404`(admin 아님 — 경계 수정 검증), `/admin-api/missing` → `error/404`(admin 아님), `/notices/999` → `error/404`, `requestURI == null` → `error/404`(기존 null 처리 무회귀)
- 컨텍스트 경로 있음(`getContextPath()` → `"/cms"`, codex 2차 지적 3 검증): `/cms/admin/missing` → `error/admin/404`, `/cms/admin;v=1/missing` → `error/admin/404`(컨텍스트 경로+매트릭스 파라미터 조합), `/cms/administrator/missing` → `error/404`(admin 아님)

**수정: `docs/troubleshooting.md`**

415행 항목의 "해결 방법: 아직 고치지 않음" 문단을 실제 해결 내용으로 교체(새 항목 추가 금지 — CLAUDE.md의 중복 작성 방지 지침).

**수정: `CLAUDE.md`**

"API 문서" 섹션의 "다만 이 경로에 접근하면 404가 아니라 500이 반환된다 — 기존 결함" 문구를 해소 사실로 갱신하고, "상태 코드 규칙"에 미매핑 경로의 404 동작(API는 JSON, 페이지는 HTML)을 한 줄 추가.

**수정: `adversarial-review/plan/README.md`** — 인덱스에 이번 계획 행 추가.

**변경 없음: `adversarial-review/project-direction-roadmap.md`** — 완료 반영은 `/updateRoadmap` 담당이라 이번 PR 범위에서 건드리지 않는다.

## 작업 순서

1. `feat/not-found-404` 브랜치
2. `GlobalApiExceptionHandler`(핸들러+`API_MATCHER`)·`SecurityConfig`·`AdminSessionExpiredStrategy`(참조로 전환)·`CustomErrorController`(경계+컨텍스트 경로 수정) → `./gradlew compileJava`
3. `SecurityConfigTest` 케이스 추가 + `NoHandlerFoundDispatchTest`·`CustomErrorControllerTest` 신규 → `./gradlew test` 전체 통과
4. Playwright 실기 검증 (아래 참조)
5. `docs/troubleshooting.md`·`CLAUDE.md`·`plan/README.md` 갱신
6. `/code-review-loop` → `/commitPR`

## 검증 계획

**자동 테스트**: `./gradlew test` 전체 통과. 무회귀 확인 대상 — `PublicNoticeControllerTest`(404 + `error/404` 뷰 단언), `SecurityConfigTest`/`ApiSecurityConfigTest`(401·403 분기 기존 케이스), `AdminMemberControllerTest:300`(500 `INTERNAL_ERROR` 유지), `NoticeControllerTest`·`MenuControllerTest`(`ResourceNotFoundException` 기반 404).

**Playwright 실기 검증** (`./gradlew bootRun`, dev 프로파일 — MockMvc가 커버 못 하는 컨테이너 ERROR 디스패치·실제 CSRF/인가 재평가 확인이 목적):
- 비로그인: `GET /does-not-exist` → `error/404.html` 렌더링 + 상태 404
- ADMIN 로그인: `GET /admin/does-not-exist` → `error/admin/404.html` 렌더링, `GET /admin/logout` → admin 404 HTML(재현 사례 1 해소)
- `GET /favicon.ico` → 404(재현 사례 2 해소)
- ADMIN 로그인 상태에서 `GET /admin/api/does-not-exist` → JSON 404 `RESOURCE_NOT_FOUND`
- 비로그인 `GET /admin/api/does-not-exist` → 기존대로 JSON 401 `UNAUTHORIZED`(Security가 먼저 처리, 무회귀)
- MANAGER 로그인으로 `GET /admin/member/manage`(ADMIN 전용) → 기존대로 403(무회귀)
- **ADMIN 로그인 상태에서 실제 CSRF 토큰으로 `POST /does-not-exist` → 최종 HTML 404(403 아님)**: ERROR 재디스패치에서 `AuthorizationFilter`가 재평가되더라도 대상 URI가 `/error`(마지막 `anyRequest().permitAll()`에 매칭) 자체이므로 원 경로의 역할 요구사항이 재적용되지 않는다는 v2 리스크 분석을 종단으로 확인(codex 지적 2 반영)
- `GET /administrator/missing`·`GET /admin-api/missing` → 일반 `error/404.html`(admin 404 아님) — 결정 7 경계 수정 확인
- `GET /admin;v=1/missing` → 관리자 `error/admin/404.html`(매트릭스 파라미터 — 결정 7 v4 확인)
- 회귀: 대시보드·공지 관리(첨부 포함)·회원 관리·공개 공지 목록/상세/첨부 다운로드 정상 동작 스크린샷

**prod 사례(swagger-ui)**: dev 프로파일에서는 springdoc이 켜져 있어 재현되지 않는다. `make prod-up`으로 확인 시도하고, 확인하지 못하면 그 사실을 명시하고 완료를 주장하지 않는다.

## 리스크

- **ERROR 디스패치 재진입 — 정정된 분석(v2)**: Boot·Spring Security는 필터를 `REQUEST`뿐 아니라 기본적으로 `ERROR` 디스패치에도 적용한다. 이를 두 필터로 나눠 재확인했다 — `CsrfFilter`는 `OncePerRequestFilter`의 `shouldNotFilterErrorDispatch()` 기본값(스킵)을 오버라이드하지 않아 ERROR 재디스패치에서 CSRF를 재검증하지 않는다(Spring Security 6.5 공식 문서로 확인). `AuthorizationFilter`는 `setFilterErrorDispatch` 기본값이 `true`라 ERROR 디스패치에서도 인가를 재평가하지만, 컨테이너 ERROR 포워드의 대상 URI가 원 요청 경로가 아니라 `server.error.path`(`/error`) 자체이므로 어떤 역할 기반 규칙에도 걸리지 않고 `anyRequest().permitAll()`에 매칭된다 — 구조적으로 403 역전 가능성이 없다. Playwright로 실제 POST+CSRF·보호된 경로 조합을 종단 확인해 이 분석을 검증한다(위 검증 계획 참조).
- **MockMvc 한계**: MockMvc는 컨테이너 ERROR 디스패치를 수행하지 않아 HTML 본문·ERROR 재디스패치 이후의 필터 재평가를 슬라이스 테스트로는 단언할 수 없다. 상태코드까지는 슬라이스 테스트로 고정하고, 실제 렌더링·재디스패치 동작은 Playwright로 검증한다.
- **`NoHandlerFoundDispatchTest`**: `spring.web.resources.add-mappings=false` 단독으로 실제 `NoHandlerFoundException` 디스패치가 성립함을 javap로 리졸브된 jar를 직접 열람해 확인했다(결정 3 v3). 구현 중 이 테스트가 실패하면 슬라이스 구성 결함으로 보고 원인을 찾는다 — 계약을 좁혀 우회하지 않는다.
- **`CustomErrorController`의 비-404 공백**: 404 외 상태에는 뷰 이름을 설정하지 않는 기존 공백이 있다. 이번 변경은 404만 `sendError`하므로 영향 없음 — 관측되면 기존 결함으로 보고만 하고 고치지 않는다(범위 밖).
- **`/notices` 하위 미매핑 경로의 응답 변화**: `/notices/1/foo` 같은 경로가 JSON 500 → HTML 404로 바뀐다. 의도한 개선이며 `SecurityConfig`의 `/notices/**` GET permitAll 범위 안이라 인가 변화는 없다.

## 후속 과제 (이번 범위 제외)

- **형제 결함(405·400)**: `HttpRequestMethodNotSupportedException`(허용되지 않는 메서드)·`MethodArgumentTypeMismatchException`(타입 변환 실패)도 같은 catch-all에 잡혀 500이 된다(`GET /admin/api/members/abc`로 실측). 이번 작업은 404만 다루기로 사용자와 확정했다 — 다음 로드맵 갱신 때 별도 작업으로 재평가.

## 구현·검증 결과 (2026-08-06)

**구현 파일** (계획과 동일, 변경 없음):
- `src/main/java/com/cms/common/api/GlobalApiExceptionHandler.java` — `API_MATCHER` 상수 신설 + `handleNoHandlerFound()` 핸들러 추가
- `src/main/java/com/cms/config/SecurityConfig.java` — 자체 `API_MATCHER` 제거, 정적 임포트로 전환(인가 규칙 무변경)
- `src/main/java/com/cms/config/security/AdminSessionExpiredStrategy.java` — 동일하게 정적 임포트로 전환
- `src/main/java/com/cms/error/CustomErrorController.java` — `PathPattern("/admin/**")`+`PathContainer` 기반 판정으로 교체, `getContextPath()` 제거 처리 추가
- 신규 테스트: `src/test/java/com/cms/common/api/NoHandlerFoundDispatchTest.java`, `src/test/java/com/cms/error/CustomErrorControllerTest.java`
- 테스트 추가: `src/test/java/com/cms/config/SecurityConfigTest.java`(7개 케이스)
- 문서: `docs/troubleshooting.md`("해결" 처리), `CLAUDE.md`(API 문서·상태 코드 규칙 섹션 갱신)

**자동 테스트 결과**: `./gradlew test` 전체 577개 통과, 실패 0건(Docker 미기동으로 최초 1회 Testcontainers 17개 클래스가 환경 문제로 실패했으나 Docker Desktop 기동 후 재실행에서 전부 통과 — 코드 문제 아님). `NoHandlerFoundDispatchTest`가 `spring.web.resources.add-mappings=false` 단독으로 실제 `NoHandlerFoundException` 디스패치 성공을 실측 확인(codex 3차 리뷰의 javap 기반 예측과 일치).

**Playwright 실기 검증 결과** (`./gradlew bootRun`, dev 프로파일, ADMIN 계정 로그인):
- `GET /does-not-exist`(비로그인) → `error/404.html` 렌더링, 상태 404, 콘솔에서 `favicon.ico`도 500이 아닌 404 확인(재현 사례 2 해소)
- `GET /admin/does-not-exist` → `error/admin/404.html`("관리자 페이지를 찾을 수 없습니다") 렌더링
- `GET /admin/logout`(GET) → admin 404 HTML로 정정(재현 사례 1 해소, 이전엔 500 JSON)
- `GET /admin/api/does-not-exist`(ADMIN) → `Content-Type: application/json`, 본문 `{"code":"RESOURCE_NOT_FOUND","message":"요청하신 경로를 찾을 수 없습니다.", ...}` 확인
- `GET /admin/api/does-not-exist`(비로그인, curl) → 기존대로 401 `UNAUTHORIZED` JSON(무회귀)
- 실제 CSRF 토큰으로 `POST /does-not-exist`(ADMIN, `fetch` + `meta[name="_csrf"]`) → 404(403 아님) — ERROR 재디스패치에서 `AuthorizationFilter`가 재평가되어도 대상이 `/error`(permitAll) 자체라 원 경로 역할 요구사항이 재적용되지 않는다는 리스크 분석을 종단으로 확인
- `GET /administrator/missing`·`GET /admin-api/missing` → 일반 `error/404.html`(홈 링크 `/notices`) — 접두사 오분류 경계 수정 확인
- 회귀: 대시보드(방문자 차트 정상), 공지사항 관리, 회원 관리(기존 `admin`·`manager01` 계정 목록 정상 표시), 공개 공지 목록(빈 상태 정상 렌더) — 스크린샷 촬영, 이상 없음

**이슈 (계획에서 예상 못 한 실측 발견)**:
- **매트릭스 파라미터 시나리오(`/admin;v=1/missing`, `/admin/api;v=1/foo`)는 실제 앱에서 재현 불가능함을 확인.** Spring Security의 기본 `StrictHttpFirewall`(`allowSemicolon` 기본값 `false`)이 세미콜론을 포함한 모든 요청 경로를 필터 체인 최상단에서 `RequestRejectedException`(400)으로 차단한다 — 공개 경로(`/notices;v=1/missing`)·`/admin`·`/admin/api` 전부 동일하게 400으로 확인(curl 3종 교차 확인, Spring Security 6.5 공식 문서로 `setAllowSemicolon` 기본값 재확인). 즉 codex가 1·3차 리뷰에서 제기한 "매트릭스 파라미터에서 Security 판정과 404 핸들러 판정이 어긋난다"는 우려는 **이 앱의 실제 배포 설정에서는 애초에 도달 불가능한 시나리오**였다 — 방화벽이 Security의 `authorizeHttpRequests`·`GlobalApiExceptionHandler.API_MATCHER`·`CustomErrorController` 어디에도 도달하기 전에 이미 차단한다. 이번 PR의 `PathPattern` 기반 매트릭스 파라미터 처리(`API_MATCHER` 공유·`CustomErrorController.isAdminPath()`)는 여전히 정확하고 유지할 가치가 있다(방어 심층화, 향후 `StrictHttpFirewall.setAllowSemicolon(true)`로 설정이 바뀌는 경우에도 안전) — 다만 "실제 브라우저로 종단 검증했다"는 주장은 이 시나리오에 한해 성립하지 않으므로, 대신 `PathPattern` 매칭 자체는 `CustomErrorControllerTest`(단위 테스트)와 codex의 로컬 jar 재컴파일 검증으로 고정했다.
- MANAGER 역할의 `/admin/member/manage` 403 실기 검증은 dev DB의 기존 `manager01` 계정 비밀번호를 모르는 관계로 생략 — 이 분기는 이번 PR이 건드리지 않은 기존 인가 규칙이며 `SecurityConfigTest.manager_memberManagePage_forbidden`(기존 테스트, 무회귀 통과)로 이미 커버된다.
- `make prod-up`(prod 프로파일에서 swagger-ui 500→404 재현 확인)은 `.env.prod` 신규 작성이 필요해 이번 검증에서는 시도하지 않았다 — dev 프로파일에서 이미 동일 메커니즘(`NoResourceFoundException` → `GlobalApiExceptionHandler`)으로 `/admin/does-not-exist`·`/favicon.ico`가 404로 정정됨을 확인했으므로 프로파일 무관하게 같은 코드 경로가 적용될 것으로 예상되나, prod 자체 실기 확인은 후속 과제로 남긴다.
