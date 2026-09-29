# PLAN — SecurityConfig 기본 거부 전환 (감사 M-08)

> 작성일: 2026-09-29
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "우선순위에서 밀린 감사 항목" M-08 (`anyRequest().permitAll()` — 미분류 URL 기본 공개)
> 감사 원본: `docs/CMS-technical-audit-2026-09-05.md` M-08

## 개정 이력

- v1 (2026-09-29): 최초 작성(정찰·설계 결과). `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-09-29, codex 리뷰 1차 반영 — needs-attention, 2개 지적):
  - **수용(P2-1, 정적 접두사 permit이 컨트롤러까지 통과시킴)**: 접두사 permit은 메서드·핸들러 종류를 가리지 않아 "규칙 누락 시 즉시 차단" 주장이 정적 접두사 하위에서는 성립하지 않는다는 지적 — 타당. 결정 3 수정: 정적 5경로 permit을 **GET/HEAD로 한정**하고 `/css`·`/js`·`/img`·`/vendor`를 **정적 전용 예약 접두사**로 명시. 결정 5에 "컨트롤러 매핑이 예약 접두사와 겹치면 실패하는 테스트" 추가. 완료 기준의 보장을 "**허용 규칙에 매칭되지 않는 신규 경로**"로 좁힘.
  - **부분 수용(P2-2, 403 검증이 상태 코드뿐)**: (a) 수용 — 실서버 통합 테스트 (b)를 상태 코드뿐 아니라 **기존 403 응답 본문(`error.html` 식별 문구)까지 검사**하도록 강화(403은 `CustomErrorController`가 뷰를 지정하지 않아 기본 `error` 뷰로 렌더링됨), ERROR permit 제거 실험 시 **어떤 검사가 어떤 실제 응답으로 실패했는지 기록**. (b) 기각(범위 조정) — "인증 세션으로 `GET /foo → 403`을 실서버 통합 테스트로"는 로그인 시드가 필요해 과중하므로, 거부 판정은 MockMvc(`@WithMockUser`)로, 실서버 종단 확인은 **실기 검증 단계(dev `bootRun` + 실제 관리자 로그인)**에서 수행한다. ERROR permit은 인증 여부와 무관해 익명 CSRF 403 경로로 같은 메커니즘이 증명된다.

## Context

`SecurityConfig.java:75`의 마지막 규칙이 `.anyRequest().permitAll()`이라, 명시 규칙(`/admin/**`·`/notices/**`·`/actuator/**`·swagger)에 걸리지 않는 모든 URL이 무인증 공개다. 지금까지는 신규 엔드포인트를 추가할 때마다 명시 규칙을 넣어온 관례(승인 이력)에 의존했을 뿐, **규칙을 빠뜨려도 조용히 공개되는 구조**다(fail-open). 이를 fail-closed(기본 거부)로 뒤집고, 실제로 공개해야 하는 비-`/admin`·비-`/notices` 경로를 전수 명시한다.

## 스키마 · 인가 정책 영향

- **스키마 변경: 없음.** **신규 의존성: 없음.**
- **인가 정책 변경: 있음 (사용자 승인 필수).** 마지막 규칙 `permitAll` → `denyAll`. 기존에 "공개"로 응답하던 미분류 경로의 동작이 바뀐다(아래 "동작 변화 표").

## 정찰로 확인한 사실 (설계 근거)

- 컨트롤러 매핑 전수(grep): 실제 핸들러는 `/admin/**`(페이지·`/admin/api/**`)·`/notices/**`·`/error` 뿐이다. `/admin/**`·`/notices/**`는 이미 명시 규칙이 있다. **즉 `anyRequest()`로 떨어지는 "의도된 공개 핸들러"는 `/error`뿐**이고, 나머지는 정적 리소스와 "핸들러 없음(404)" 경로다.
- 정적 리소스: `src/main/resources/static/{css,img,js,vendor}` 4개 최상위 디렉터리. 템플릿이 `/css/**`·`/js/**`·`/img/**`·`/vendor/**`(fontawesome webfonts 포함)를 참조한다. 로그인·공개 공지 페이지가 이를 무인증으로 로드하므로 공개가 필요하다. `/favicon.ico`는 파일이 없지만 브라우저가 매 페이지마다 요청한다(현재 404 HTML — `PLAN-not-found-handling.md` 재현 사례 중 하나).
- **`/error` ERROR 디스패치 (가장 큰 함정)**: `CustomErrorController`(`@RequestMapping("/error")`)는 404·429 페이지를 렌더링한다. 서블릿 컨테이너의 `sendError`(404 자동 처리, `RateLimitFilter`의 429, `AccessDeniedHandlerImpl`의 403 등)는 `/error`로 **ERROR 디스패치**를 하며, Spring Security 6의 `authorizeHttpRequests`는 기본적으로 **모든 디스패치 타입에 인가를 적용**한다. 현재는 `/error`가 `anyRequest().permitAll()`에 걸려 통과하지만, 기본 거부로 바꾸면 **`/error` 재디스패치가 거부되어 404/429/403 오류 페이지가 로그인 리다이렉트나 빈 응답으로 뒤바뀐다**. 기존 `SecurityConfigTest`도 "ERROR 재디스패치 자체는 MockMvc 밖이라 증명되지 않음"이라고 한계를 주석으로 남겨뒀다 → MockMvc로는 검증할 수 없고 실서버 통합 테스트가 필요하다.
- `RateLimitFilter`는 `CsrfFilter` 다음·`AuthorizationFilter` 앞이라 인가 변경의 영향을 받지 않는다(429는 필터 안에서 응답). 다만 429 페이지 렌더링은 위 ERROR 디스패치에 의존한다.
- `API_MATCHER`(`/admin/api/**`)·엔트리포인트·`AccessDeniedHandler` 분기는 그대로 재사용한다. 미분류 경로는 `API_MATCHER`에 안 걸리므로 비인증은 `LOGIN_ENTRY_POINT`(302 → `/admin/login`), 인증됐지만 거부되면 `DEFAULT_ACCESS_DENIED_HANDLER`(403)로 처리된다.
- **테스트 파급**: `RateLimitFilterTest`(`/rl-test/**` 스텁)·`RateLimitResponseTest`(`/rl-response-test/page` 스텁)는 실제 `SecurityConfig`를 import하고 미분류 경로 스텁이 200이 되는 것에 의존한다 → `denyAll` 이후엔 통과 불가(`@WithMockUser`도 소용없음 — `denyAll`은 인증 여부 무관). `SecurityConfigTest`의 "비인증 GET /존재하지않는경로 → 404"·"CSRF 포함 비인증 POST /존재하지않는경로 → 404" 2건은 옛 계약을 고정하고 있어 바뀌어야 한다. `NoHandlerFoundDispatchTest`는 `addFilters=false`라 무영향.
- 스크립트·Makefile·compose·CI가 호출하는 URL은 `/actuator/health`뿐(grep 확인) — 이미 명시 공개.

## 핵심 설계 결정

### 결정 1 — 마지막 규칙: `denyAll()` vs `authenticated()`

| 선택지 | 장단점 |
|---|---|
| A. `anyRequest().denyAll()` | 인증·역할 무관 전부 거부 → 진짜 fail-closed. 기존 `/notices`·`/actuator/**`에서 이미 쓰는 관용구와 일관. |
| B. `anyRequest().authenticated()` | 로그인한 MANAGER가 미래의 미분류 URL에 접근 가능 → "규칙 누락 = 일부 공개"가 남는다. |
| C. `hasRole("ADMIN")` | ADMIN 전용으로 안전하지만 "규칙을 넣지 않은 URL이 ADMIN에게만 열림"이라 규칙 누락이 눈에 안 띈다. |

**결정: A(`denyAll`).** 이유: 감사 M-08의 요지가 "규칙 누락이 조용히 통과"인데, A만이 누락을 즉시 눈에 띄게(테스트·실행 시 바로 실패) 만든다. B·C는 누락이 일부 사용자에게 열려 문제를 은폐한다.

### 결정 2 — `/error` ERROR 디스패치 허용 방식

| 선택지 | 장단점 |
|---|---|
| A. `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` | ERROR 디스패치만 허용. 클라이언트가 `/error`를 **직접** 요청하면(REQUEST 디스패치) 기본 거부에 걸림 → 오류 페이지 직접 접근·탐색 차단. |
| B. `requestMatchers("/error").permitAll()` | 단순하지만 `/error` 직접 접근도 무인증 공개(빈 `error` 뷰 노출). |

**결정: A.** 이유: 공개가 필요한 것은 컨테이너의 오류 재디스패치뿐이지 `/error` URL 자체가 아니다. 최소 권한. 규칙 순서상 **가장 앞**에 둔다. (제약: 이 프로젝트는 `forward:`·비동기(ASYNC) 디스패치를 쓰지 않음을 grep으로 확인 — 새로 도입되면 재검토.)

### 결정 3 — 정적 리소스·favicon 공개 범위

**결정: 명시 경로 5개만 공개, GET/HEAD 한정** — `/css/**`, `/js/**`, `/img/**`, `/vendor/**`, `/favicon.ico`. 앞 4개 접두사는 **정적 리소스 전용 예약 경로**다 — 접두사 permit은 핸들러 종류를 가리지 않으므로(codex 1차 리뷰) 여기에 컨트롤러 매핑을 두면 인가를 우회한다. 이를 테스트로 강제한다(결정 5). `PathRequest.toStaticResources().atCommonLocations()`는 쓰지 않는다: Boot 기본 위치(`/css/**`·`/js/**`·`/images/**`·`/webjars/**`)는 이 프로젝트의 `/img`·`/vendor`를 포함하지 않고, 쓰지 않는 `/images/**`·`/webjars/**`를 불필요하게 열기 때문이다(최소 공개). `/favicon.ico`는 파일이 없지만 **공개로 유지해 기존 404(HTML)를 보존**한다 — 거부하면 브라우저가 favicon 요청마다 로그인 페이지로 302 되고 그 HTML을 아이콘으로 받아간다(불필요한 부하·노이즈).

**신규 정적 디렉터리 누락 방지**: `static/`에 새 최상위 디렉터리가 생기면 자동으로 차단되어 화면이 깨진다 — 이를 조용한 회귀로 두지 않기 위해 **`static/` 최상위 항목이 허용 목록과 일치하는지 검사하는 테스트**를 둔다(결정 5). **예약 접두사 보호**: 컨트롤러 매핑이 예약 접두사와 겹치면 실패하는 테스트도 둔다(결정 5).

### 결정 4 — 미분류 경로의 응답 변화 수용

기본 거부 이후 `/`·`/foo` 같은 미분류 경로는 비인증 시 404 대신 **302 → `/admin/login`**, 인증(ADMIN/MANAGER) 시 **403**이 된다. 404 계약을 보존하려고 "미분류는 permit 후 404"로 되돌리면 M-08 자체를 무효화하므로 **수용**한다. 부수 효과로 비인증 사용자가 경로 존재 여부를 404/200 차이로 탐색하지 못한다(정보 노출 감소). `/admin/**`·`/admin/api/**`·`/notices/**`의 404 계약(JSON 404·HTML 404)은 이미 명시 규칙 아래라 **무변경**이다.

**동작 변화 표**

| 요청 | 변경 전 | 변경 후 |
|---|---|---|
| 비인증 `GET /foo`(미분류·핸들러 없음) | 404 HTML | 302 → `/admin/login` |
| ADMIN 로그인 `GET /foo` | 404 HTML | 403 |
| 비인증 `GET /` | 404 HTML | 302 → `/admin/login` |
| 비인증 `GET /favicon.ico` | 404 HTML | 404 HTML (무변경 — 명시 공개) |
| `GET /css/x.css` 등 정적(존재) | 200 | 200 (무변경) |
| `GET /css/none.css`(정적·없음) | 404 | 404 (무변경) |
| 컨테이너 ERROR 디스패치(`/error`) | 통과 | 통과 (결정 2로 유지) |
| 비인증 직접 `GET /error` | 200(빈 error 뷰) | 302 → `/admin/login` |
| `/admin/**`·`/admin/api/**`·`/notices/**`·`/actuator/**`·swagger | 기존 규칙 | **무변경** |

### 결정 5 — 테스트 전략

1. **`SecurityConfigTest`(MockMvc 슬라이스)** — 옛 계약 2건을 새 계약으로 수정(비인증 미분류 GET → 302 `/admin/login`, CSRF 포함 비인증 POST → 302), 신규 추가: ADMIN 미분류 GET → 403, **정적 접두사 하위 비-GET/HEAD(예: `POST /css/x`) 거부**, `/favicon.ico` 비인증 404, 정적 4개 디렉터리 각각 존재하지 않는 파일 요청 → 404(≠302), **`static/` 최상위 항목이 허용 목록과 일치하는지 검사(신규 정적 디렉터리 누락 감지)**.
2. **신규 실서버 통합 테스트 `DefaultDenyErrorDispatchIntegrationTest`**(`@SpringBootTest(RANDOM_PORT)` + `MariaDbContainerSupport` — 기존 `RateLimitFilterContainerRegistrationTest` 관례 재사용): MockMvc가 못 증명하는 **ERROR 재디스패치**를 실제 컨테이너로 검증 — (a) `GET /favicon.ico` → 404 + `error/404` 템플릿 본문, (b) `POST /notices`(CSRF 없음) → 403 **+ 응답 본문에 `error.html` 식별 문구(`에러가 발생했습니다`) 포함**(로그인 302로 뒤집히거나 빈 본문·컨테이너 기본 응답이 되면 실패 — ERROR 디스패치가 거부됐을 때의 서명), (c) 미분류 `GET /foo` → 302 `Location: /admin/login`, (d) 직접 `GET /error` → 302. (e) 같은 클래스에서 `RequestMappingHandlerMapping` 등록 패턴 전수를 검사해 **예약 접두사(`/css`·`/js`·`/img`·`/vendor`)와 겹치는 컨트롤러 매핑이 0건**임을 단언. 이 테스트는 결정 2를 **되돌리면(ERROR permit 제거) 실제로 실패함**을 일시 제거→실패 재현→복원으로 확인하고, **(a)~(d) 중 어떤 검사가 어떤 실제 응답(상태·Location·본문)으로 실패했는지 계획 문서에 기록**한다(`AdminActionLogCommitOrderIntegrationTest`와 동일 증거 방식).
3. **`RateLimitFilterTest`·`RateLimitResponseTest`** — 미분류 스텁 경로를 `GET/HEAD permitAll`인 **`/notices/...` 하위로 이동**하고 규칙 패턴 프로퍼티도 함께 이동. 429 동작 자체(필터 위치·응답 포맷)는 무변경.
4. 기존 `ActuatorExposureTest`·`ApiSecurityConfigTest` 등은 무수정 통과가 기대된다(전체 스위트로 확인).

### 결정 6 — 하지 않는 것 (범위 통제)

- 이미 중복이 된 규칙(`/notices/**` denyAll, `/actuator/**` denyAll)은 **제거하지 않는다** — 명시적 이중 방어로 승인된 이력이 있고, 제거는 무관한 리팩터링이다.
- `PathRequest`·설정 프로퍼티·별도 `@Configuration` 클래스·공개 경로 화이트리스트 외부화는 만들지 않는다(요청받지 않은 추상화).
- 인증 정책·역할 매핑·로그인 흐름은 건드리지 않는다.

## 수정 파일

| 구분 | 파일 | 내용 |
|---|---|---|
| 수정 | `src/main/java/com/cms/config/SecurityConfig.java` | ERROR 디스패치 permit(맨 앞) 추가, 정적 5경로 permit 추가, `anyRequest().permitAll()` → `denyAll()`, 주석에 사유·승인 |
| 수정 | `src/main/java/com/cms/config/CLAUDE.md` | 접근 제어 표 마지막 행 교체 + 정적·ERROR 디스패치 행 추가 + 승인 이력(2026-09-29) |
| 수정 | `src/test/java/com/cms/config/SecurityConfigTest.java` | 옛 계약 2건 수정 + 신규 케이스 |
| 수정 | `src/test/java/com/cms/config/RateLimitFilterTest.java`·`RateLimitResponseTest.java` | 스텁·규칙 패턴을 `/notices/...`로 이동 |
| 신규 | `src/test/java/com/cms/config/DefaultDenyErrorDispatchIntegrationTest.java` | 실서버 ERROR 디스패치·기본 거부 통합 검증 |
| 수정 | 루트 `CLAUDE.md`(필요 시) | "보안 규칙"에 기본 거부 한 줄 + 신규 엔드포인트는 규칙을 반드시 추가해야 한다는 규약 |
| 수정 | `adversarial-review/plan/README.md`·로드맵 | 인덱스·완료 표시(로드맵은 `/updateRoadmap` 담당이므로 구현 후 안내만) |

## 작업 순서

1. `security/default-deny-authorization` 브랜치 생성.
2. `SecurityConfig` 수정 → `./gradlew compileJava`.
3. 깨질 테스트 3종 수정(`SecurityConfigTest`·`RateLimit*Test`) → 신규 통합 테스트 작성.
4. `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체.
5. ERROR permit 일시 제거 → 통합 테스트 실패 재현 → 복원.
6. 실기 검증(dev 프로파일 `bootRun`, 격리 포트): 로그인 화면 정적 리소스 로드(스크린샷)·공개 공지 화면·favicon 404·미분류 302·직접 `/error` 302·429 페이지·swagger-ui(ADMIN)·`/actuator/health`.
7. 문서 갱신 → `/code-review-loop` → `/commitPR`.

## 완료 기준

- 미분류 경로가 비인증 302(`/admin/login`)·인증 403이며, 마지막 규칙이 `denyAll()`.
- 정적 리소스(로그인·공개 공지 화면 렌더링)·`/favicon.ico` 404·`/actuator/health`·`/notices/**` GET/HEAD 무회귀.
- **보장 범위**: "규칙 누락 시 차단"은 **명시 허용 규칙에 매칭되지 않는 신규 경로**에 대해서만 성립한다. 정적 예약 접두사 하위는 컨트롤러 매핑 겹침 금지 테스트가 별도로 지킨다.
- 실기 검증에서 실제 관리자 로그인 후 미분류 `GET /foo` → 403(error 뷰) 확인.
- 실서버에서 404·429·403 오류 페이지가 ERROR 디스패치로 정상 렌더링(로그인 리다이렉트로 뒤바뀌지 않음), ERROR permit 제거 시 통합 테스트가 실패함을 증거로 확인.
- `static/` 최상위 항목이 허용 목록과 어긋나면 테스트 실패.
- swagger-ui가 dev에서 ADMIN으로 정상 로드(정적 자원 포함).
- 전체 테스트 통과.

## 리스크

- **ERROR 디스패치 누락**(가장 큼) — 결정 2·통합 테스트로 방어.
- **신규 엔드포인트가 규칙을 빠뜨리면 즉시 차단**(의도된 동작) — CLAUDE.md에 규약 명시.
- **swagger-ui 정적 자원 경로**: springdoc이 `/swagger-ui/**` 하위로 서빙하므로 기존 ADMIN 규칙 아래에 있다고 가정 — 실기(dev)로 확인.
- **미분류 경로 응답 변화**(404→302/403)에 의존하는 외부 모니터링·크롤러가 있다면 영향(현재 실배포 없음, 헬스체크는 `/actuator/health`만 사용).
- 향후 `forward:`·ASYNC 디스패치 도입 시 기본 거부에 걸릴 수 있음 — 그때 별도 permit 필요.

## 구현·검증 결과 (2026-09-29)

> 상태: 구현·테스트·실기 검증 완료. 커밋·PR은 `/code-review-loop` → `/commitPR` 대기. 계획 리뷰는 codex 2라운드(v2에서 ship).

### Context
감사 M-08 — `anyRequest().permitAll()`로 규칙 누락이 조용히 공개되던 fail-open을 fail-closed로 전환. 사용자 승인(2026-09-29, 인가 정책 변경) 후 구현. 스키마·의존성 변경 없음.

### 핵심 확정 사항
`anyRequest().denyAll()` / `dispatcherTypeMatchers(ERROR).permitAll()`을 규칙 맨 앞에 / 정적 5경로(`/css/**`·`/js/**`·`/img/**`·`/vendor/**`·`/favicon.ico`) GET·HEAD 한정 공개 / 정적 4접두사는 컨트롤러 매핑 금지 예약 경로. 계획과 달라진 결정은 없음.

### 구현 파일
- 수정: `SecurityConfig.java`(규칙 3종 + `STATIC_PUBLIC_PATHS` 상수), `com/cms/config/CLAUDE.md`(접근 제어 표), 루트 `CLAUDE.md`(보안 규칙 1줄)
- 테스트: `SecurityConfigTest`(옛 계약 2건 수정 + 신규 8건), `RateLimitFilterTest`·`RateLimitResponseTest`(스텁·규칙 패턴을 `/notices/...`로 이동), 신규 `DefaultDenyErrorDispatchIntegrationTest`(실서버 5건)

### 검증 결과
- `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 **790개 중 실패 0·오류 0, 스킵 1**(기존 Windows symlink 테스트).
- **ERROR permit 제거 실험**(일시 제거 → 실패 재현 → 복원, 복원 후 `EXPERIMENT` 흔적 0건 확인): `DefaultDenyErrorDispatchIntegrationTest` 5건 중 2건 실패 — (a) `GET /favicon.ico` `expected: 404 but was: 302`, (b) `POST /notices`(CSRF 없음) `expected: 403 but was: 302`. (c)(d)(e)는 통과(ERROR 디스패치와 무관한 검사). 즉 제거 시 404·403 오류 페이지가 로그인 302로 뒤바뀌는 것을 실서버에서 재현·감지.
- **실기 검증**(dev 프로파일, 격리 포트 8099, 공유 dev DB):
  - 익명: `/admin/login`·`/notices`·정적 4종·`/actuator/health` 200 / `/favicon.ico`·`/css/none.css` 404(error/404 렌더링) / `/foo`·`/`·직접 `/error`·`/actuator/env`·`/swagger-ui.html` 302→`/admin/login` / `POST /notices`·`POST /css/x.css` 403(본문 `에러가 발생했습니다`) / `/admin/api/members` 401.
  - ADMIN 로그인 후: `/admin`·`/admin/member/manage` 200 / `/foo`·`/`·`/error`·`/actuator/env` **403(error 뷰 렌더링)** / swagger-ui 진입점·`swagger-ui-bundle.js`·`.css`·`swagger-initializer.js`·`/v3/api-docs`·`swagger-config` 전부 200 → dev swagger 무회귀.
  - 익명 첨부 다운로드 22회 연타 → 429 + `error/429` 페이지(`<title>429 - 요청이 너무 많습니다`) — ERROR 디스패치 경로로 렌더링됨.
  - Playwright: 로그인 화면 CSS·fontawesome 아이콘 정상 렌더링(스크린샷 `screenshots/default-deny-login.png`, `default-deny-404.png` — `.gitignore`가 `adversarial-review/**/*.png`를 제외해 커밋 대상 아님). 콘솔 오류는 기존과 동일한 favicon 404 1건뿐.
  - **원복**: 검증 로그인으로 생긴 `visit_log` 1건(id=234) 삭제, 앱 프로세스 종료(8099 해제) 확인.

### 이슈
- `python`이 없어 계획서 개정 이력 자동 반영이 1회 실패했고(그 사이 codex가 v1을 재검토한 호출은 폐기), Edit 도구로 v2를 반영한 뒤 2차 리뷰를 다시 받았다.
- 통합 테스트에서 `RequestMappingHandlerMapping` 빈이 액추에이터용 포함 2개라 주입이 모호했다 → `@Qualifier("requestMappingHandlerMapping")`로 해결(테스트 코드 문제, 제품 코드 무관).

### 후속
- 미분류 경로의 404→302/403 변화에 의존하는 외부 모니터링이 생기면 재평가(현재 실배포 없음, 헬스체크는 `/actuator/health`).
- 향후 `forward:`·ASYNC 디스패치 도입 시 ERROR와 별도로 permit 필요.
- 로드맵의 M-08 완료 반영은 `/updateRoadmap` 담당.
