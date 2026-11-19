# PLAN — 비밀번호 로그인 `POST /admin/login` IP 레이트리밋 (이슈 #284)

- 상태: v2 (2026-11-19) — 계획 리뷰 2라운드 ship(실제 서버 Tomcat 11.0.26 — `build.gradle` 고정, 리뷰 확인), 사용자 승인(2026-11-19 — 로그인 입구 동작 변경 포함), 구현·검증 완료(미커밋). 결과는 문서 끝 §8
- 이슈: #284. 출처 `PLAN-passkey-login.md` 결정 6·"후속", `PLAN-public-endpoint-rate-limit.md`
- 위험 변경: **보안 설정(레이트리밋 규칙 추가)**, **인증 입구 동작(비밀번호 로그인 POST가 한도 초과 시 429)**
- 스키마 변경: **없음**. 새 의존성·설정 키: **없음**. 설정 값 추가: `cms.rate-limit.rules`에 규칙 1개(기존 키 구조, `admin-passkey-login`과 같은 방식).
- URL 인가 규칙(`authorizeHttpRequests`) 변경: **없음**. 자바 코드 변경: **없음**(설정 + 시험 + 문서).

## 1. Context

비밀번호 로그인 입구 `POST /admin/login`의 방어는 계정별 실패 잠금(5회 → 30분)뿐이다. 패스키 입구에는 IP 레이트리밋 `admin-passkey-login`이 있지만 비밀번호 입구에는 없다(#280 범위 밖). 그래서 다음 세 가지에 노출돼 있다.

1. 여러 계정에 4회씩 흔한 비밀번호를 시도해 잠금 없이 대입할 수 있다(password spraying).
2. 미존재 사용자도 BCrypt 비교 비용을 유발한다(Spring Security 7.0.7 `DaoAuthenticationProvider`는 없는 사용자에 더미 해시 비교를 한다 — 최초 더미 해시 생성·DB 조회가 더해지고, 상태 기반 거부는 경로가 다르다). 이것으로 CPU를 묶을 수 있다.
3. 관리자 계정을 반복해서 잠글 수 있다. 이것은 IP 한도로는 일부만 줄인다(아래 한계).

**대입이 곧 탈취가 되는 계정이 있다**(v2, 리뷰 1-1): 2단계를 쓰는 회원은 비밀번호만으로 완전 권한을 얻지 못하지만, ① **2단계 미등록 ADMIN**은 비밀번호 통과 뒤 강제 등록 화면(`TotpEnrollmentLoginController`)에서 현재 비밀번호 + 새 비밀값의 코드로 **공격자 자신의 인증기를 등록**할 수 있고 ② **2단계를 켜지 않은 MANAGER**(`TotpAuthorities.isRequired`는 ADMIN만)는 비밀번호만으로 로그인된다. 그래서 이 규칙은 방해·부하뿐 아니라 이 두 계정군에 대한 대입 탈취의 빈도도 줄인다. 등록 정책 자체는 이번 범위에서 바꾸지 않는다.

### 정찰에서 확인한 사실

| # | 사실 | 출처 |
|---|---|---|
| S1 | `RateLimitFilter`는 `SecurityConfig`에서 `addFilterAfter(rateLimitFilter, CsrfFilter.class)`로만 등록된다(컨테이너 자동 등록은 `FilterRegistrationBean.setEnabled(false)`로 막혀 있다). 규칙은 `(경로 패턴, 메서드)` 첫 일치, 키는 `getRemoteAddr()`, 버킷은 `(규칙 id, IP)`. | `SecurityConfig.java:259`, `RateLimitFilter`, `RateLimitFilterConfig` |
| S2 | 폼 로그인 처리 필터(`UsernamePasswordAuthenticationFilter`, `loginProcessingUrl("/admin/login")`)와 그 앞의 `LoginSessionMutexFilter`(`addFilterBefore(…, UsernamePasswordAuthenticationFilter.class)`)는 `CsrfFilter`보다 뒤에 있다 — 레이트리밋 필터가 그 둘보다 먼저 돈다. **근거**: Spring Security 7.0.7 `config/.../web/builders/FilterOrderRegistration.java`(7.0.7 태그 소스, 1라운드 리뷰가 확인)에서 `CsrfFilter`가 `UsernamePasswordAuthenticationFilter`보다 앞이다. 새 시험이 "429 요청에서 인증 프로바이더 미호출"로 행동도 고정한다(F1). 패스키 처리 필터에 대해서는 `PasskeyLoginRateLimitIntegrationTest`가 같은 순서를 이미 고정했다. | 코드, 시험 |
| S3 | 버킷(`Bucket`)은 `capacity`로 가득 찬 채 시작하고, 요청 1건에 토큰 1개, 보충은 연속(`elapsed × capacity / refillPeriod`, 상한 `capacity`), 거부 시 `Retry-After` = 토큰 1개가 차는 데 필요한 초(올림). | `Bucket.java` 54~78행 |
| S4 | 화면 경로(비 `/admin/api/**`)의 429는 `sendError(429)` → `CustomErrorController` → `error/429.html`("요청이 너무 많습니다", 홈으로 링크). | `RateLimitFilter.reject`, `templates/error/429.html` |
| S5 | 시험 JVM은 `CMS_RATE_LIMIT_ENABLED=false`(`build.gradle:128`)라 레이트리밋이 꺼진 채 돈다. 켠 시험은 `@TestPropertySource(properties = "cms.rate-limit.enabled=true")`로 켠다. 켠 시험 중 `POST /admin/login`을 보내는 곳은 `PasskeyLoginRateLimitIntegrationTest`의 1회뿐이다(새 규칙 한도 안). `BrowserTestSupport.loginAsAdmin`은 꺼진 JVM에서 돈다. | `build.gradle`, 시험 코드 grep |
| S6 | `scripts/ci/prod-smoke.sh`는 레이트리밋이 켜진 prod 컨테이너에서 같은 IP로 `POST /admin/login`을 2회 보낸다(강제 등록 → 재로그인). | `prod-smoke.sh` 73행 부근 |
| S7 | 유일한 `AuthenticationProvider` 빈은 `TotpAwareAuthenticationProvider`(`DaoAuthenticationProvider` 상속) — 비밀번호 비교(BCrypt)는 이 안에서 일어난다. | `TotpAwareAuthenticationProvider`, `com.cms.config` `CLAUDE.md` |
| S8 | 2단계 화면 `POST /admin/login/totp`·강제 등록 `GET·POST /admin/login/totp-enrollment`는 비밀번호를 통과한 중간 상태 세션만 쓸 수 있다. 오답은 계정별로 센다 — 코드 화면은 **`member_totp.failed_attempts`**(5회 → 잠금, `TotpLoginService.recordFailureAndCheckThreshold`), 강제 등록 화면의 비밀번호 오답은 `member.failed_login_count`. (v2까지 "같은 `failed_login_count`"로 적었던 것을 구현 중 시험으로 정정 — 결정 2의 "계정당 5회" 근거는 그대로) | `com.cms.config`·`com.cms.admin.member` `CLAUDE.md`, `TotpLoginService.java:100`, F3 시험 |
| S10 | 경로 매처가 둘이다 — 레이트리밋 규칙은 `PathPatternRequestMatcher.withDefaults().matcher(pattern)`(`TokenBucketRateLimiter` 72행), 폼 로그인 처리 매처는 7.0.7 `FormLoginConfigurer`가 `getRequestMatcherBuilder()`로 만든다. 두 빌더의 설정(서블릿 경로 등)이 같은지는 **미확인** → F4 시험과 실기(curl)로 변형 경로의 결과를 고정한다. | 코드, 7.0.7 소스(리뷰 1-4) |
| S11 | prod는 앱을 `127.0.0.1:8080`에만 연다(`docker-compose.prod.yml` 82행) — 같은 호스트의 리버스 프록시가 전제다. `server.forward-headers-strategy`는 어디에도 설정되지 않았다(#239, blocked). 이 상태로 프록시 뒤에 배포하면 모든 요청의 `remoteAddr`가 프록시 주소 하나가 된다. | 코드 grep, 이슈 #239 |
| S9 | 기존 규칙 중 `/admin/login`과 겹치는 패턴은 없다 — `admin-passkey-login`은 정확 경로 `/admin/login/passkey`, `public-site-home`은 `admin`을 부정 선행탐색으로 제외한다. | `application.yml` |

## 2. 핵심 결정

### 결정 1 — 기존 장치에 규칙 1개: `admin-password-login` (POST `/admin/login`, `capacity: 20`·`refill-period-seconds: 600`)

- 의미: IP당 버스트 20회 + 30초에 1회 보충(600 ÷ 20). 한 IP가 쉬지 않고 보내면 하루 최대 약 20 + 2,880 ≈ 2,900회 — **단일 인스턴스에서 그 IP의 버킷이 유지되는 조건의 계산값**이다. 재시작·캐시 eviction(fail-open, 기존 수용)·다중 인스턴스에서는 이 상한을 보장하지 않는다(v2, 리뷰 1-출처).
- 선택지(사용자 결정 2026-11-19):
  - **20/600 (채택)** — 공유 IP에서 1인 2회(오타 포함)로 잡아 약 10명 동시 로그인 수용, 그 뒤에도 30초마다 1회. 대입 상한 하루 약 2,900회/IP.
  - 30/60(패스키와 동일) — 공유 IP에 가장 너그럽지만 하루 약 43,000회/IP, 대입·BCrypt 부하 억제가 15배 약하다. 패스키는 서명을 추측할 수 없어 느슨해도 됐지만 비밀번호는 추측 가능하다.
  - 10/600 — 하루 약 1,450회지만 공유 IP 약 5명 초과 시 출근 시간대 429 가능.
- 왜 기존 장치인가: 키 `getRemoteAddr()` 고정·`CsrfFilter` 다음·fail-open 수용 등 계약이 이미 검토돼 있고 설정 한 줄이다. 별도 필터·별도 저장소는 과설계.
- 왜 `CsrfFilter` 다음이 여기서도 맞는가: CSRF 토큰 없는 교차 사이트 POST가 관리자 IP의 한도를 비워 정상 로그인을 막는 것을 막는다(`PLAN-public-endpoint-rate-limit.md` 쟁점 4). 로그인 화면은 GET으로 토큰을 받으므로 정상 사용자에게 비용이 없다.
- 메서드는 POST만. `GET /admin/login`(화면)은 규칙 밖 — 막으면 429 뒤 화면도 못 본다.
- `exempt-authenticated`는 쓰지 않는다 — 로그인 POST는 대부분 익명이고, 인증된 세션의 재로그인을 면제하면 세션 하나로 대입할 수 있다.

### 결정 2 — 2단계 화면(`/admin/login/totp`·`/admin/login/totp-enrollment`)은 제외 (사용자 결정 2026-11-19)

- 이유: 비밀번호를 통과한 세션만 도달하고(S8), 오답은 계정별 잠금에 합산돼 대입이 이미 계정당 5회로 묶인다. 넣으면 같은 IP에서 정상적으로 2단계를 거치는 관리자들이 한도를 나눠 쓰는 비용만 생기고 늘어나는 방어는 거의 없다.
- 남는 위험(수용): 탈취한 비밀번호 여러 개로 각 계정의 2단계 코드를 4회씩 시도하는 경우. 계정당 5회 잠금이 상한이다.

### 결정 3 — 한도 초과 응답은 기존 그대로(화면 429 오류 페이지)

- `/admin/login`은 `/admin/api/**`가 아니라 `error/429.html`이 그려진다(S4). 입력한 아이디는 보존되지 않는다(패스키·문의 폼과 같음).
- 로그인 화면 전용 안내(로그인 화면으로 돌아가 "잠시 후 다시" 표시)는 만들지 않는다 — 정상 사용자가 한도에 닿을 일이 드물고, 오류 페이지 문구가 이미 그 뜻이다. 필요해지면 후속.

## 3. 범위 밖 / 알려진 한계

- IP를 바꿔 가며 보내는 공격은 막지 못한다(기존 레이트리밋과 같은 수용 한계).
- 특정 계정 잠그기 방해: 5회 실패가 버스트(20)보다 작아 한 IP에서도 계정 4개까지 즉시 잠글 수 있다. IP 한도는 한 IP가 잠글 수 있는 계정 수와 빈도만 줄인다. 근본 대책(잠금 정책 변경·CAPTCHA)은 범위 밖.
- **공유 IP 로그인 굶기기(v2, 리뷰 1-2 — 사용자 결정 2026-11-19: 수용 + #239를 배포 차단 조건으로 명시)**: 필터는 자격 증명 확인 전에 토큰을 쓴다. 피해자와 같은 `remoteAddr` 뒤의 공격자는 유효한 CSRF 토큰(로그인 화면 GET으로 누구나 얻는다)과 없는 아이디로 버킷을 비운 뒤 **30초에 1건만 보내도** 그 IP의 정상 비밀번호 로그인을 계속 막는다(올바른 비밀번호도 프로바이더에 닿지 않는다). 계정 잠금은 일어나지 않는다. 피해자 브라우저를 쓰는 교차 사이트 방식은 안 된다(토큰 없는 요청은 `CsrfFilter`가 먼저 거부, 한도 미소비 — F2).
  - 성립 조건: 같은 사내망·NAT·VPN 출구(내부자) → **수용**. 리버스 프록시 뒤에서 원 IP 전달 없이 배포(S11) → 인터넷 누구나 **모든 관리자**의 비밀번호 로그인을 막을 수 있고, 공격이 없어도 관리자 전원이 버킷 20개를 나눠 쓴다 → **#239 해결 전 실배포 불가**로 `docs/deployment.md`와 #239에 명시(댓글은 8단계에서 사용자 승인 후).
  - 탐지: 앱에는 기록이 남지 않는다(없는 아이디의 실패는 `LoginFailureService.recordFailure`가 갱신 0행으로 끝, 429는 `RateLimitFilter`가 로그 없이 응답) — 추적은 프록시 접근 로그로 한다(코드 리뷰 2라운드에서 문서 정정).
  - 운영 대응: 패스키 로그인(별도 버킷 — 단 공격자가 2초에 1건으로 그 버킷도 비울 수 있다), 최후 수단 `CMS_RATE_LIMIT_ENABLED=false`(전체 규칙이 꺼진다). 기존 세션·2단계 화면은 영향 없음.
  - 30/60 완화는 굶기기 비용만 2초에 1건으로 올리고 막지는 못해 기각(대입 상한 15배 증가).
- BCrypt 1회 비용은 측정하지 않는다(상대 억제만 주장).

## 4. 성공 기준

1. 같은 IP에서 유효한 CSRF로 `POST /admin/login`을 20회 보내면 20회 모두 인증 프로바이더까지 간다. 21번째는 429이고 프로바이더를 호출하지 않는다.
2. 시계를 30초 전진하면 1건이 다시 허용되고, 그다음 건은 429다.
3. CSRF 토큰 없는 `POST /admin/login`은 한도를 쓰지 않는다.
4. 한도를 다 쓴 IP에서도 `GET /admin/login`·`POST /admin/login/passkey`(별도 버킷)·`POST /admin/login/totp`(규칙 없음)는 429가 아니다. 다른 IP의 `POST /admin/login`도 허용된다.
5. `com.cms.config` `CLAUDE.md` 레이트리밋 절, 경로 표의 `/admin/login` 관련 서술, `docs/deployment.md` 레이트리밋 절, `application.yml` 주석(패스키 규칙의 "비밀번호 로그인에는 규칙이 없다" 문구 제거)이 현행화된다.

## 5. 실패 모드와 고정 시험

새 시험 `PasswordLoginRateLimitIntegrationTest`(`com.cms.config.auth`, `@SpringBootTest` + `MariaDbContainerSupport`, `cms.rate-limit.enabled=true`, `@MockitoBean Ticker`로 시각 고정, `@MockitoSpyBean TotpAwareAuthenticationProvider`). 각 시험은 고유한 `remoteAddr`를 써서 버킷을 나눈다(컨텍스트 공유).

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 규칙이 없거나 필터 순서가 바뀌어 21번째 요청이 BCrypt까지 간다 | 20회 → 프로바이더 `authenticate` 정확히 20회, 21번째 429 → 여전히 20회. 30초 전진 → 1건 허용(21회), 다음 429 |
| F2 | CSRF 실패 요청이 한도를 소비해 교차 사이트에서 관리자 IP를 막을 수 있다 | 같은 IP로 CSRF 없는 POST 25회(403) 뒤 유효한 POST 1건이 429가 아니다(프로바이더 호출 1회) |
| F3 | 규칙이 다른 로그인 경로까지 막거나, 진행 중인 인증을 끊는다(패턴이 넓음·같은 버킷) | **버킷을 비우기 전에** 실제 비밀번호 로그인으로 중간 상태 세션 두 개를 만든다 — 2단계 사용 회원(→ `TOTP_PENDING`), 미등록 ADMIN(→ `TOTP_ENROLLMENT_REQUIRED`). 버킷을 비운 뒤: `GET /admin/login` 200(화면 렌더링), 코드 대기 세션의 `POST /admin/login/totp` 오답 → 기존 오답 응답(구현 첫 단계에서 현재 응답을 확인해 그 값으로 고정)·`failed_login_count` +1, 강제 등록 세션의 `GET /admin/login/totp-enrollment` 200(QR 화면)·`POST` 오답 → 같은 화면 재렌더링 + 실패 +1, 패스키를 켠 상태(`PasskeyRelyingParty` 목, 기존 시험 방식)의 `POST /admin/login/passkey` 챌린지 없음 → `/admin/login?passkeyExpired`, 다른 IP의 `POST /admin/login`은 프로바이더 도달 |
| F4 | 경로 변형으로 규칙을 피하면서 로그인 처리는 되는 불일치(S10) | 버킷을 비운 IP에서: `POST /admin/login?x=1`(쿼리) → 429. `POST /admin/login/`(끝 슬래시) → 구현 첫 단계에서 실측한 동작 **하나**로 고정(429 또는 "프로바이더 미호출 + 그 응답 코드"). `;jsessionid=`·`%2F`·`//admin/login` 변형 → 방화벽 거부(400) 또는 프로바이더 미호출. 실제 서버에서도 같은 변형을 curl로 확인(7단계) — 컨테이너 URI 정규화는 MockMvc로 확정하지 않는다 |
| F5 | 한도 값이 문서·주석과 어긋난다(누가 yml만 바꿈) | F1이 20·30초를 그대로 단언하므로 값 변경 시 실패한다 |

변이 확인(구현 후): ① 규칙 제거 → F1 실패 ② `pattern: /admin/login/**` → F3(totp·passkey) 실패 ③ 메서드에 GET 추가 → F3 `GET` 실패 ④ 필터를 `addFilterBefore(CsrfFilter)`로 → F2 실패.

## 6. 작업 단계

1. 브랜치 `security/password-login-rate-limit`.
2. 시험 `PasswordLoginRateLimitIntegrationTest` 작성 → 규칙 없는 상태에서 F1 RED 확인.
3. `application.yml`에 `admin-password-login` 규칙 추가(위치: `admin-passkey-login` 바로 앞, 주석에 근거·계획 링크), 패스키 규칙 주석의 "비밀번호 로그인에는 규칙이 없다" 제거.
4. 시험 GREEN, 변이 확인 ①~④.
5. 영향 범위 시험: `com.cms.config.*`, `*RateLimit*`, `*ConventionTest`. 설정 공용 파일(`application.yml`) 변경이라 전체 시험(`-PbrowserTests=exclude`)을 백그라운드로 1회.
6. 실기 검증: dev 앱 기동(레이트리밋 켜짐) → 같은 IP에서 틀린 비밀번호 POST 21회(curl, CSRF 포함) → 21번째 429·`Retry-After`; F4 변형 경로(쿼리·끝 슬래시·`;jsessionid=`·`%2F`·`//`)를 실제 Tomcat에서 curl로 확인; 브라우저로 로그인 화면 GET 정상·429 화면 스크린샷, 30초 뒤 정상 로그인. 실기 계정 잠금을 피하려고 없는 아이디를 쓴다.
7. 문서: `com.cms.config` `CLAUDE.md`, `docs/deployment.md`(규칙·굶기기 수용 위험·**#239 해결 전 프록시 뒤 실배포 불가**·운영 대응), 이 계획서 결과 갱신, `plan/README.md` 인덱스 행. #239에 이 규칙과의 관계를 댓글로 남기는 것은 사용자 승인 후.

## 8. 구현·검증 결과 (2026-11-19)

### 핵심 확정 사항
- 규칙 `admin-password-login`(POST `/admin/login`, 20/600초)을 `admin-passkey-login` 앞에 추가했다. 자바 코드·`SecurityConfig`·URL 인가 규칙은 변경하지 않았다.
- 2단계 화면은 제외했다. 구현 중 사실 정정: 코드 화면 오답은 `member_totp.failed_attempts`에 센다(S8 갱신, 결정 2의 근거는 유지).
- 끝 슬래시는 실측해 "로그인 처리 밖 → `/admin/login`으로 302, 프로바이더 미호출" 하나로 고정했다.

### 구현 파일
- `src/main/resources/application.yml` — 규칙 1개와 근거 주석을 추가하고, 패스키 규칙 주석의 "비밀번호 로그인에는 규칙이 없다"를 지웠다.
- `src/test/java/com/cms/config/auth/PasswordLoginRateLimitIntegrationTest.java` — 신규, F1~F4 시험 4건.
- 문서: `com.cms.config` `CLAUDE.md`(경로 표 `/admin/login` 행, 레이트리밋 절 "로그인 입구 두 곳"), `docs/deployment.md`(관리자 로그인 입구, 프록시 뒤 배포 차단 조건, 굶기기 수용 위험과 대응).

### 검증 결과
- RED: 규칙이 없을 때 F1·F3·F4가 "429 기대, 302"로 실패했다. F2는 규칙이 없어도 통과하는 성질이라 변이 ④로 검증했다.
- GREEN: 새 시험 4/4 통과.
- 변이(모두 원복 후 통과): ① 규칙 없음 → F1·F3·F4 실패(RED) ② 패턴 `/admin/login/**` → F3·F4 실패 ③ 메서드에 GET 추가 → F3 실패 ④ `addFilterBefore(rateLimitFilter, CsrfFilter.class)` → F2 실패.
- 전체 시험(`-PbrowserTests=exclude`): 3,247건 중 1건 실패. `InquiryNotificationIntegrationTest.lockWaits_areBoundedByStatementTimeout`의 경과 시간이 4,011ms로 상한 3,700ms를 넘었다. 이 시험은 레이트리밋을 켜지 않고 로그인 필터를 거치지 않는다. 단독 실행(`cleanTest`)은 3회 모두 통과했다. 전체 시험 부하로 벽시계 측정이 여유분을 넘은 기존 시간 의존 시험으로 판단하며, 이번 변경과 무관하다. **PR 직전 전체 시험에서 다시 확인한다.**
- 실기(dev, 레이트리밋 켜짐, 실제 Tomcat):
  - curl로 없는 아이디 POST를 보냈다. 20회는 `/admin/login-error`로 302했고, 21번째는 429와 `Retry-After: 24`(경과만큼 보충)였다.
  - 경로 변형(curl `--path-as-is`)은 MockMvc 측정과 같았다. `?x=1`은 429, `/admin/login/`·`/ADMIN/login`·`/admin/Login`·`/admin/login%20`은 `/admin/login`으로 302, `;jsessionid=`·`%2F`·`//`·`/./`는 400이었다.
  - 버킷이 빈 동안 `GET /admin/login`은 200이었다.
  - 브라우저(Playwright)에서 버킷이 빈 상태로 로그인하면 429 오류 화면이 나왔다. 30초 뒤 시드 ADMIN(2단계 미등록) 로그인은 강제 등록 화면으로 이동했다(비밀번호 인증 도달).
  - DB의 `admin`은 `failed_login_count` 0, 2단계 등록 없음으로 그대로였다.
  - 스크린샷은 scratchpad에 두었다(`password-login-429.png`, `password-login-after-refill.png`).

### 이슈
- MockMvc에 경로를 문자열로 넘기면 `%`를 다시 인코딩하고(`/admin%252Flogin`), `//admin/login`의 `admin`을 호스트로 읽는다(요청 URI `/login`). 그래서 변형 경로는 `java.net.URI`로 넘긴다(시험 주석에 기록).
- 브라우저 첫 시도가 429가 아니라 302였다. curl로 비운 뒤 조작하는 사이 30초 보충 토큰이 찼기 때문이고, 바로 다시 보낸 요청은 429였다(동작 정상).

### 후속 (2026-11-19, 사용자 승인으로 등록)
- #287 문의 알림 시간 상한 시험이 전체 시험 부하에서 실패 — `test`
- #288 로그인 입구 레이트리밋 초과 시 로그인 화면 안내 — `enhancement`(결정 3)
- #239에 이 규칙과의 관계·완료 기준 보강 댓글(실배포 차단 조건)

## 7. 개정 이력

- v1 (2026-11-19): 초안. 사용자 결정 — 한도 20/600초, 2단계 화면 제외, 정식 경로.
- v2 변경 (1라운드 codex): ① "ADMIN은 2단계 필수라 탈취 안 됨" 정정 — 미등록 ADMIN(강제 등록 화면에서 공격자 인증기 등록)·2단계 끈 MANAGER는 대입이 곧 탈취(Context) ② 공유 IP 로그인 굶기기 위험 명시 — 사용자 결정: 20/600 유지·수용, #239 해결 전 프록시 뒤 실배포 불가를 문서·이슈에 명시(§3) ③ F3 강화 — 버킷 소진 전 만든 중간 상태 세션 두 종으로 2단계·강제 등록 GET·POST의 정해진 응답·실패 카운트, 패스키는 켠 상태의 만료 흐름 ④ F4 강화 — 쿼리 429, 끝 슬래시 실측 한 가지로 고정, `;`·`%2F`·`//` 변형, 실제 서버 curl ⑤ 출처: S2 필터 순서 7.0.7 소스 확인, S10 매처 빌더 차이 미확인 명시, BCrypt 표현 정정(더미 해시), 하루 상한은 단일 인스턴스·버킷 유지 조건의 계산값. 기각: 30/60 완화 — 굶기기를 막지 못하고 대입 상한만 15배.
