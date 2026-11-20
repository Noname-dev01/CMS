# PLAN — 로그인 입구 레이트리밋 초과 시 로그인 화면 안내 (이슈 #288)

> 상태: v4 (2026-11-20) — 계획 리뷰 4라운드 ship, 사용자 승인(2026-11-20 — 로그인 입구 한도 초과 응답 429→302), 구현·검증 완료, 코드 리뷰 2라운드 통과, PR 전. 결과는 "구현·검증 결과" 절

> 위험 변경: 보안 설정(레이트리밋 필터의 거절 응답) — URL 인가 규칙·필터 순서·CSRF 위치·한도 값은 바꾸지 않는다.
> 외부 계약 변경: 로그인 입구 두 곳의 한도 초과 응답 429 → 302(사용자 결정 2026-11-20).
> 스키마·의존성 변경 없음. 설정 키 1개 추가(`cms.rate-limit.rules[].login-redirect`).

## Context

- #284(`PLAN-password-login-rate-limit.md`)가 비밀번호 로그인 `POST /admin/login`에 `admin-password-login`(20/600초)을, #280이 패스키 로그인
  `POST /admin/login/passkey`에 `admin-passkey-login`(30/60초)을 걸었다. 한도를 넘으면 `RateLimitFilter.reject`가 `sendError(429)` →
  컨테이너 ERROR 재디스패치 → `CustomErrorController` → `error/429.html`(공용, 링크는 "홈으로 돌아가기" `/` 하나)로 끝난다.
- 관리자가 로그인 화면으로 돌아가려면 주소를 다시 쳐야 한다. #284 결정 3은 "필요해지면 후속"으로 미뤘고 그 후속이 이 이슈다.
- 정찰 사실(코드 확인):
  - S1 로그인 화면의 안내는 쿼리 표지(`?passkeyError`·`?passkeyExpired`·`?totpExpired`)를 보고 `AdminMainController.login`이 `error` 모델 문구로 그린다.
  - S2 패스키 로그인 제출은 `passkey-login.js`의 `form.submit()`(일반 폼 제출)이라 302를 브라우저가 그대로 따라간다.
  - S3 두 로그인 규칙은 `methods: [POST]`뿐이고, `GET /admin/login`에 걸리는 규칙은 없다(`public-site-home`은 예약어 `admin`을 부정 선행탐색으로 제외).
  - S4 `RateLimitFilter`는 `CsrfFilter` 다음, `LoginSessionMutexFilter`·`UsernamePasswordAuthenticationFilter`·`PasskeyLoginFilter` 앞이다(`com.cms.config` `CLAUDE.md`, #284 S2 — Spring Security 7.0.7 소스 확인). 이 계획은 위치를 바꾸지 않는다.
  - S5 `/admin/api/**`(`API_MATCHER`)의 429는 JSON이다. 로그인 입구는 API 경로가 아니다.
  - S6 비밀번호 로그인 실패(`/admin/login-error`)에서도 입력한 아이디는 원래 보존되지 않는다(템플릿에 `th:value` 없음).
  - S7 `RateLimitRule`의 생성자 호출은 `TokenBucketRateLimiter.toRule` 한 곳뿐이다.

## 핵심 결정

### 결정 1 — 응답은 로그인 화면으로 302 (`/admin/login?rateLimited`) — 사용자 결정 2026-11-20

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 302 → `/admin/login?rateLimited`** (채택) | 이슈 문구 그대로 "로그인 화면에서 안내". 기존 `?passkeyError`와 같은 방식(PRG — 새로고침이 POST를 다시 보내지 않음). 로그인 화면 렌더링 코드(패스키 챌린지 발급 포함)를 재사용 | 상태 코드가 429가 아니게 된다 — 프록시 접근 로그에서 `POST /admin/login`의 302가 실패(`/admin/login-error`)와 Location으로만 구분된다 |
| B. 429 유지 + 로그인 전용 429 화면 | 접근 로그·`Retry-After`·기존 시험 유지 | 로그인 화면이 아니라 한 번 더 눌러야 한다. 새로고침이 POST를 다시 보낸다 |
| C. 429 + 오류 컨트롤러가 로그인 화면 자체를 그림 | 상태 유지 + 로그인 화면 | 패스키 챌린지·CSRF를 ERROR 디스패치에서 다시 만들어야 하고, 새로고침이 POST를 다시 보낸다 — 복잡도 대비 이득 없음 |

- 왜: 사용자가 로그인 화면으로 바로 돌아오는 것이 이 이슈의 목적이고, 화면 안내 방식이 이미 쿼리 표지로 통일돼 있다(S1).
- 탐지 영향 대응(v2): `docs/deployment.md`의 "프록시 접근 로그로 추적" 문구를 고친다 — 한도 초과는 `POST /admin/login`·`/admin/login/passkey`의 **302 + 응답 `Location`이 `/admin/login?rateLimited`**로만 구분되므로 **프록시 접근 로그 형식에 응답 `Location`을 넣어야 한다**(nginx 기본 `combined`에는 없다 — `$sent_http_location`을 추가, nginx `ngx_http_log_module` 문서). 뒤따르는 `GET /admin/login?rateLimited`는 근거로 쓰지 않는다(공격 스크립트는 리다이렉트를 따르지 않을 수 있고 누구나 직접 부를 수 있다). 프록시 제품·버전은 아직 정하지 않았으므로(#238) 이 요건을 #239(프록시 뒤 설정)의 배포 점검 항목으로 남긴다(8단계 후속 목록 — 사용자 승인 후 댓글). 앱 로그 추가는 하지 않는다(#284 결정 유지 — 범위 밖).

### 결정 2 — 대상 규칙은 설정 플래그 `login-redirect: true`로 고른다

| 선택지 | 판단 |
|---|---|
| **규칙 설정 불린 `login-redirect`** (채택) | `exempt-authenticated`와 같은 형식. 어떤 규칙이 리다이렉트하는지 `application.yml`에 보인다. 이동할 곳은 코드 상수(`/admin/login?rateLimited`)라 설정으로 임의 URL을 만들 수 없다 |
| 필터에 규칙 id 하드코딩 | id 변경 시 조용히 깨진다 — 기각 |
| 필터가 요청 경로로 판정 | 경로 문자열이 `SecurityConfig`·`PasskeyLoginFilter`·yml에 이어 넷째 사본이 된다 — 기각 |
| 리다이렉트 URL을 설정 값으로 | 쓰임이 하나뿐이고 설정 실수로 외부 URL이 들어갈 표면이 생긴다 — 기각(과설계) |

- `RateLimitRule`에 `boolean loginRedirect` 추가, `RuleConfig`에 `loginRedirect`(기본 false) 추가, `toRule`이 옮긴다.
- **기동 검증**(`RateLimitConfigValidator`): `login-redirect: true`인 규칙의 `methods`는 `POST`만이어야 한다. 위반 시 기동 실패.
  왜: GET 규칙에 켜면 이동한 `GET /admin/login`이 같은 규칙에 다시 걸려 리다이렉트 루프가 될 수 있다(S3가 지금은 안전하다는 사실일 뿐 구조적 보장이 아니다).
  `exempt-authenticated`와 함께 켜는 것은 막지 않는다(서로 독립 — 면제 판정이 먼저다).

### 결정 3 — 거절 분기 순서와 응답 형식

`reject`의 분기: ① `API_MATCHER` → JSON 429(그대로) ② `rule.loginRedirect()` → `response.sendRedirect(contextPath + "/admin/login?rateLimited")` ③ 그 밖 → `sendError(429)`(그대로).

- API를 먼저 보는 이유: 플래그가 API 규칙에 잘못 켜져도 fetch 호출자가 HTML 리다이렉트를 받지 않게(현재 해당 규칙 없음 — 방어적 순서일 뿐 비용 없음).
- `Retry-After` 헤더: 리다이렉트에는 **붙이지 않는다**. 3xx의 `Retry-After`는 "리다이렉트를 따르기 전 대기"라는 다른 뜻이 된다(RFC 9110 §10.2.3). 지금은 `reject` 첫 줄에서 무조건 설정하므로 분기 안으로 옮긴다.
- 화면에 남은 초를 표시하지 않는다 — 쿼리에 숫자를 실으면 누구나 임의 값을 넣은 안내를 만들 수 있고, 규칙마다 보충 간격이 달라(30초·2초) 고정 문구가 더 정직하다.
- 리다이렉트는 세션을 만들지 않는다(`sendRedirect`). 이동한 `GET /admin/login`이 평소처럼 세션·패스키 챌린지를 만든다(기존 동작).

### 결정 4 — 로그인 화면 문구

`AdminMainController.login`에 `@RequestParam rateLimited` 분기 추가: "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요." 비밀번호·패스키 공통(규칙을 나눠 알릴 이유 없음 — 어느 입구든 같은 IP의 시도가 많다는 뜻).
입력한 아이디는 보존하지 않는다(S6 — 실패 화면도 보존하지 않으며, 쿼리에 아이디를 실으면 접근 로그·기록에 남는다). 이슈 배경의 "아이디가 사라진다"는 이 결정으로 해소하지 않음을 명시한다.

## 성공 기준

1. 같은 IP에서 `POST /admin/login` 21번째(버스트 20 소진 후)는 **302 `/admin/login?rateLimited`**이고 인증 프로바이더를 부르지 않는다. 응답에 `Retry-After`가 없다.
2. 같은 IP에서 `POST /admin/login/passkey` 31번째는 302 `/admin/login?rateLimited`이고 식별·검증까지 가지 않는다.
3. `GET /admin/login?rateLimited`는 200 `admin/login`이고 모델 `error`가 위 문구다.
4. 다른 규칙(공개 GET)의 429와 `/admin/api/**` JSON 429·`Retry-After`는 그대로다.
5. CSRF 토큰 없는 로그인 POST는 403이고 한도를 쓰지 않는다(기존 F2 유지). 필터 위치·한도 값은 그대로다.
6. `login-redirect: true` + GET 포함 규칙은 기동 실패한다. 실제 설정에서 `login-redirect`는 `admin-password-login`·`admin-passkey-login` 둘뿐이다.
7. 실기(Playwright, dev `bootRun` — 기본 `cms.rate-limit.enabled: true`, 시험 JVM만 `CMS_RATE_LIMIT_ENABLED=false`): 같은 IP(127.0.0.1)에서 스크립트로 버킷을 미리 비운 뒤(비밀번호 20건, 패스키는 2초 보충이라 수 초 안에 40건) **마지막 제출만 실제 버튼**으로 한다. 네트워크 기록으로 원래 POST의 302·`Location`(경로·쿼리를 파싱해 `/admin/login` + `rateLimited`)·`Retry-After` 없음, 그리고 최종 화면의 안내 문구를 함께 확인한다. 주소에 `?rateLimited`를 직접 붙인 화면 확인만으로 대신하지 않는다. 공개 경로 429 화면은 그대로인지 확인한다. 자동 브라우저 시험은 추가하지 않는다 — 서버 동작은 F3·F5 통합 시험이 고정하고 화면 문구는 모델 값이다.
   - 패스키 실기 절차(v4, 리뷰 2-1·3-1·3-2): 로그인 화면 안의 스크립트가 `fetch`(폼의 CSRF 토큰, `redirect: 'manual'`)로 `POST /admin/login/passkey`를 40건 보낸 **직후 같은 스크립트에서** `passkeyForm.submit()`(실제 폼 내비게이션)을 한다. 스크립트는 응답을 판정하지 않는다 — 브라우저 Fetch의 수동 리다이렉트 응답은 `opaqueredirect`라 상태 0·헤더 없음이다(WHATWG Fetch 표준 "opaque-redirect filtered response"). **판정은 최종 폼 POST 자체**를 Playwright 네트워크 기록으로 본다(상태 302·`Location`·`Retry-After` 없음) + 도착 화면 문구. 버킷은 소수 토큰을 연속 보충하므로(`Bucket.refill`) 마지막 거절 직후라도 최종 POST가 허용될 수 있다 — 그 경우(도착이 `?passkeyExpired`) 다시 소진·제출을 **최대 3회** 재시도하고 시도 횟수를 결과에 적는다. 결정적 동작은 가짜 `Ticker` 통합 시험(F3)이 고정한다. 자격 증명 값은 더미로 둔다 — 필터가 자격 해석 전에 끝내므로(S4) 결과가 같고, 버튼 → `form.submit()` JS 경로는 무변경이며 패스키 성공 흐름은 기존 `PasskeyBrowserTest`(가상 인증기)가 고정한다. 비밀번호는 보충이 30초라 사전 소진 후 사람 속도의 버튼 제출로 충분하다(같은 판정 방식 — 최종 POST의 네트워크 기록).

## 실패 모드와 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 리다이렉트가 다른 규칙(공개 GET·API)으로 번져 공개 429 화면·JSON이 바뀐다 | `RateLimitResponseTest`(또는 `RateLimitFilterTest`)에 플래그 규칙 302·`Retry-After` 없음 + 비플래그 규칙 429·`Retry-After` 유지 단언 |
| F2 | GET 규칙에 플래그를 켜 리다이렉트 루프 | `RateLimitPropertiesValidationTest` — `login-redirect` + `GET` → 기동 실패, `POST`만 → 통과 |
| F3 | 한도 초과 요청이 인증 프로바이더·패스키 식별까지 간다(필터 순서 회귀) | `PasswordLoginRateLimitIntegrationTest`·`PasskeyLoginRateLimitIntegrationTest`의 429 단언을 302 단언으로 바꾸고 프로바이더·서비스 스파이 무호출 단언은 유지 |
| F4 | 설정에서 플래그가 빠지거나 엉뚱한 규칙에 붙는다 | `SitePublicRateLimitConventionTest`에 "`login-redirect` 규칙은 정확히 두 로그인 규칙" 추가 |
| F5 | 로그인 화면이 `?rateLimited`를 무시 | `PasswordLoginRateLimitIntegrationTest`에서 리다이렉트를 따라간 `GET /admin/login?rateLimited`의 모델 `error` 단언 + Playwright |
| F6 | API 우선 분기 순서가 뒤집혀 API 규칙에 플래그가 붙으면 fetch가 HTML 리다이렉트를 받는다(v2, 리뷰 1-3) | `RateLimitResponseTest`에 **API 경로 + `login-redirect=true` POST 규칙**을 두고 소진 후 JSON 429·`Retry-After` 있음·`Location` 없음 단언 |

변이 확인(구현 단계): 플래그 분기 제거 → F1·F3 실패, 검증기 조건 제거 → F2 실패, yml 플래그 제거 → F4 실패, API·리다이렉트 분기 순서 교환 → F6 실패.

Location 단언 방식(v2, 리뷰 1-4): MockMvc 시험은 `redirectedUrl("/admin/login?rateLimited")` 문자열 일치(MockMvc는 컨테이너 변환을 거치지 않는다). 실기는 `Location`을 URI로 파싱해 같은 출처 + 경로 `/admin/login` + 쿼리 `rateLimited`로 확인한다(Tomcat이 상대·절대 중 무엇으로 보내는지는 실측해 결과에 기록).

## 작업 단계

1. `RuleConfig.loginRedirect` + `RateLimitRule.loginRedirect` + `toRule` (컴파일)
2. 검증기 조건 + `RateLimitPropertiesValidationTest` (TDD)
3. `RateLimitFilter.reject` 분기 + 단위 시험 (TDD)
4. `application.yml` 두 규칙에 `login-redirect: true` + 주석, 컨벤션 시험
5. `AdminMainController` 문구 + 통합 시험 두 개 단언 교체
6. 문서: `com.cms.config` `CLAUDE.md` 레이트리밋 절, `docs/deployment.md:298-300`, `CustomErrorController` 429 주석(“전부 무인증 공개 경로”가 이미 틀림 — 한 줄만)
7. 영향 범위 시험: `com.cms.config.*`, `com.cms.admin.member.*`(패스키), `*ConventionTest` → 공용 설정(`config/**`) 변경이라 전체 시험(브라우저 제외) 백그라운드 → 로그인 화면 템플릿은 바뀌지 않지만(문구는 모델) 실기 Playwright

## 범위 밖 / 알려진 한계

- 입력한 아이디 보존(결정 4).
- 남은 대기 시간 표시(결정 3).
- 2단계 화면(규칙 없음, #284 결정 2).
- 공개 문의 폼 `inquiry-submit` 429의 입력 보존 — 별개 화면, 이 플래그를 켜지 않는다.

## 출처

- `Retry-After`의 3xx 의미: RFC 9110 §10.2.3 ("When sent with any 3xx (Redirection) response, Retry-After indicates the minimum time that the user agent is asked to wait before issuing the redirected request").
- 필터 순서: #284 계획 S2(Spring Security 7.0.7 소스 확인), 이번 변경은 위치 무변경.
- `sendRedirect(String)` → 302: Jakarta Servlet 6.1 `HttpServletResponse` API 문서. 컨테이너는 Tomcat 11.0.26(`build.gradle` 명시) — `Location`이 상대·절대 중 무엇인지는 미확인, 실기 실측(위 단언 방식은 둘 다 통과한다).
- 브라우저 `fetch(..., {redirect: 'manual'})` 응답은 `opaqueredirect`(상태 0, 헤더 비공개): WHATWG Fetch Living Standard, opaque-redirect filtered response(리뷰 3-1 인용).
- nginx 기본 `combined` 로그 형식에 응답 `Location` 없음: nginx `ngx_http_log_module` 문서(리뷰 1-1 인용). 프록시 제품 미정(#238).
- Spring Boot 4.0.8 `@ConfigurationProperties`의 kebab-case(`login-redirect`) → `loginRedirect` 완화 바인딩: 기존 `exempt-authenticated` 실사용으로 같은 방식 확인(저장소 실측).

## 구현·검증 결과 (2026-11-20)

### 핵심 확정 사항
- 계획 v4 그대로 구현했다(설계 이탈 없음). 이동 위치는 상수 `RateLimitFilter.LOGIN_RATE_LIMITED_PATH`(`/admin/login?rateLimited`), 컨텍스트 경로를 앞에 붙인다.

### 구현 파일
- `config/ratelimit/RateLimitProperties.java`(`RuleConfig.loginRedirect`)·`RateLimitRule.java`(`loginRedirect`)·`TokenBucketRateLimiter.toRule`
- `config/ratelimit/RateLimitConfigValidator.java` — `login-redirect`는 `methods == {POST}`만
- `config/ratelimit/RateLimitFilter.java` — `reject` 분기 API → 302 → 429, `Retry-After`는 429 분기에서만
- `application.yml` — `admin-password-login`·`admin-passkey-login`에 `login-redirect: true`
- `admin/AdminMainController.java` — `?rateLimited` 안내 문구
- `error/CustomErrorController.java` 주석, `config/CLAUDE.md`, `docs/deployment.md`(접근 로그 `Location` 기록 요건)
- 시험: `RateLimitResponseTest`(302·컨텍스트 경로·API JSON 우선), `RateLimitPropertiesValidationTest`(POST 전용), `SitePublicRateLimitConventionTest`(플래그 규칙 = 두 로그인 입구), `PasswordLoginRateLimitIntegrationTest`·`PasskeyLoginRateLimitIntegrationTest`(429 단언 → 302 + 프로바이더·식별 무호출 유지, 안내 화면 모델)

### 검증 결과
- TDD: 각 시험을 먼저 실패시킨 뒤 구현(F2 검증기, F1 응답, F4 컨벤션, F5 화면 문구). F6은 기존 분기 순서가 이미 API 우선이라 처음부터 통과 → 변이로 확인.
- 변이 4건: 플래그 분기 제거 → 9건 중 5건 실패 / 검증 조건 제거 → 1건 실패 / yml 플래그 제거 → 1건 실패 / API·리다이렉트 순서 교환 → 1건 실패. 모두 원복.
- 전체 시험(브라우저 제외 `./gradlew test -PbrowserTests=exclude`): 3252건, 실패 0, 건너뜀 12(5분 33초). 화면 템플릿·브라우저 시험은 바꾸지 않아 `-PbrowserTests=only`는 돌리지 않았다(PR CI `browser-test`가 돈다).
- 실기(Playwright MCP, dev `bootRun`, 레이트리밋 켜짐):
  - 비밀번호: 같은 페이지 `fetch` 22건으로 소진 → 실제 [로그인] 버튼 제출 → 원래 POST **302**, `Location: http://localhost:8080/admin/login?rateLimited`(**Tomcat 11.0.26은 절대 URL**로 보냈다 — 경로·쿼리 파싱으로 판정), `Retry-After` 없음, 화면 안내 "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요." (`docs/screenshots/rate-limit/login-rate-limit-01-password-notice.png`)
  - 패스키: 같은 스크립트에서 `fetch` 40건 → 즉시 `passkeyForm.submit()` → **1회째에** 원래 POST 302 `?rateLimited`, `Retry-After` 없음, 같은 안내(`…-02-passkey-notice.png`)
  - 회귀: 공개 `/main/faq` 한도 초과 → HTTP 429 + 공용 429 화면("홈으로 돌아가기", `…-03-public-429-unchanged.png`) / 재설정 API → JSON 429 `RATE_LIMITED` + `Retry-After: 720`, `Location` 없음 / 소진한 IP의 `GET /admin/login` 200 / 30초 보충 뒤 비밀번호 로그인 → `/admin/login-error`(인증까지 감)

### 이슈
- MockMvc(spring-test 7.0.9)는 값 없는 쿼리(`?rateLimited`)를 **null 값 파라미터**로 넣어(`AbstractMockHttpServletRequestBuilder.lambda$addRequestParams$1`이 null이면 디코딩만 건너뜀, javap 확인) `@RequestParam`이 null이 됐다. Tomcat은 빈 문자열을 준다(실기 확인). 시험은 `.param("rateLimited", "")`로 컨테이너 값을 보낸다 — 실제 쿼리 동작은 실기가 확인한다.
- 실기 스크립트에서 리다이렉트 직후 `page.url()`이 이전 주소를 돌려준 측정 시점 문제가 있었다(`location.href`로 재확인 → `?rateLimited`).

### 후속
- ✅ #239(프록시 뒤 설정)에 "접근 로그 형식에 응답 `Location` 기록(nginx `$sent_http_location`)" 점검 항목 댓글 게시(2026-11-20, 사용자 승인 — issuecomment-7033002185).
- ✅ MockMvc 값 없는 쿼리 함정을 `docs/troubleshooting.md`에 기록.

## 개정 이력

- v1 (2026-11-20): 초안. 사용자 결정 — 302 로그인 화면 안내(429 유지 기각), 정식 경로.
- v2 변경 (1라운드 codex): ① 탐지 — 접근 로그에 응답 `Location` 기록 요건 명시(nginx 기본 형식엔 없음), 뒤따르는 GET은 근거에서 제외, 프록시 미정이라 #239 배포 점검 항목으로 후속 ② 실기 — dev 활성 상태·스크립트 사전 소진·마지막 제출만 실제 버튼·원래 POST의 302/Location/Retry-After 네트워크 확인 ③ F6 추가 — API 경로 + 플래그 규칙의 JSON 429 유지(분기 순서 고정) ④ Location 단언 — MockMvc 문자열, 실기 URI 파싱, Tomcat 표현은 실측. 부분 기각: "실제 프록시 제품·버전 명시" — #238 미결정이라 지금 명시할 수 없어 요건만 적고 #239로 넘긴다.
- v3 변경 (2라운드 codex): 패스키 실기 — 2초 보충 때문에 같은 페이지 스크립트에서 소진 확인 직후 폼 제출로 간격 제거. 기각: 가상 인증기·실제 자격 증명 준비 및 POST 보류 — 필터가 자격 해석 전에 끝내 결과가 자격 값과 무관하고(S4), 버튼 JS 경로는 무변경이며 성공 흐름은 `PasskeyBrowserTest`가 고정한다.
- v4 변경 (3라운드 codex): ① 패스키 실기의 판정 주체를 페이지 스크립트가 아니라 Playwright 네트워크 기록의 **최종 폼 POST**로 명시(`opaqueredirect`는 상태·헤더 비공개) ② 소수 토큰 연속 보충으로 거절 직후에도 허용될 수 있어, 최종 POST 응답으로 판정하고 경계에서 허용되면 최대 3회 재시도.
