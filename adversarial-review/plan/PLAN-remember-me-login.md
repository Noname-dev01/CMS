# PLAN — 관리자 로그인 "로그인 상태 유지"(remember-me) 구현 (이슈 #222)

- 상태: **완료 (2026-11-10 · #225 `fc38ff8`)** — v8, 적대적 리뷰 8라운드 ship
- 브랜치: `feat/remember-me-login` (master `da7149c`에서 분기)
- 유형: feat / security — **로그인 정책 변경**(사용자 B안 선택, 2026-11-10) · **스키마 변경**(V15 새 테이블)
- 위험 변경: **인가·로그인 정책**(비밀번호 없이 인증되는 새 경로), **마이그레이션**(V15), **데이터 정합성**(세션 강제 만료 ↔ 토큰 폐기), **동시성**(토큰 교체·폐기 경합)

## Context

`/admin/login`에 "로그인 상태 유지" 체크박스(`name="remember-me"`, `login.html:72`)가 있으나 `SecurityConfig`에 `rememberMe()`가 없어 아무 효과가 없다(이슈 #222). 사용자는 체크박스 제거(A)가 아니라 **구현(B)**을 골랐다.

### 사용자 결정 (2026-11-10)

| # | 결정 | 값 |
|---|---|---|
| U1 | 구현 방식 | B안 — remember-me 구현, **영속 토큰**(이슈 권장) |
| U2 | 유효 기간 | **7일**. 영속 토큰은 자동 로그인마다 토큰이 교체되고 `last_used`가 갱신되므로 "마지막 사용 후 7일"(미사용 7일이면 만료) |
| U3 | 방문 집계 | 쿠키 자동 로그인도 **방문 1건**(ADMIN·MANAGER만 — 기존 규칙 그대로) |
| U4 | 로그아웃·세션 강제 만료 시 토큰 폐기 범위 | **해당 기기만**. 역할·상태·비밀번호 변경·재설정·자동 잠금은 그 회원의 토큰 **전부** 폐기(질문에 전제로 명시) |
| U5 | 쿠키 탈취 감지(교체된 옛 쿠키 재사용) 시 | 그 회원의 토큰 전부 삭제 **+ 그 회원의 모든 세션 만료**(공격자가 이미 만든 세션까지 끊는다. 여러 탭 동시 복원 같은 오탐에서도 본인 세션이 끊기는 비용을 수용) |

### 정찰에서 확인한 사실

1. **세션 강제 만료의 진입점은 둘뿐이다.**
   - 자동: `AdminSessionRevokeEvent` → `AdminSessionRevokeListener`(AFTER_COMMIT) → `AdminSessionService.expireSessionsFor`. 발행처는 `AdminMemberService.updateAdminMember`(역할·상태 실변경·멱등 재잠금), `AdminMemberService.changeMyPassword`(본인 비밀번호 변경), `PasswordResetService`(재설정), `LoginFailureService.recordFailure`(자동 잠금, `@Transactional`) — 4곳 모두 트랜잭션 안에서 같은 이벤트를 낸다.
   - 수동: `AdminSessionManageService.expireSession(handle)`·`expireMemberSessions(memberId)` → `AdminSessionService.expireByHandle`·`expireSessionsFor(memberId, exceptSessionId)`.
   - **remember-me를 그냥 켜면 강제 만료가 무력화된다** — 세션이 끊겨도 쿠키가 다음 요청에서 새 세션을 만든다(상태 변경은 `loadUserByUsername`이 거부하지만, 역할·비밀번호 변경·재설정·수동 만료는 뚫린다). 이 작업의 핵심 쟁점이다.
2. **Spring Security 7.0.7 `RememberMeConfigurer`**(javap): 공유 `SessionAuthenticationStrategy`를 `RememberMeAuthenticationFilter.setSessionAuthenticationStrategy`로 넘기고(자동 로그인 세션도 `SessionRegistry`에 등록될 것으로 보이나 **시험으로 확인**한다), `RememberMeServices`가 `LogoutHandler`면 `LogoutConfigurer`에 등록한다.
3. `AbstractRememberMeServices.autoLogin`(재정의 가능 — v1의 "`final`"은 틀렸다)은 `CookieTheftException`을 잡아 쿠키를 지운 뒤 **다시 던진다**. `RememberMeAuthenticationFilter`는 `ExceptionTranslationFilter`보다 앞이라 그대로 두면 **500**이다. 나머지(`UsernameNotFound`·`InvalidCookie`·`AccountStatus`·기타 `RememberMeAuthenticationException`)는 쿠키를 지우고 익명으로 계속한다. 라이브러리 `processAutoLoginCookie`의 순서는 토큰 조회 → 탈취 판정(불일치면 `removeUserTokens` 후 예외) → 만료 판정 → **토큰 교체(`updateToken`, 예외는 `RememberMeAuthenticationException`으로 변환) + 쿠키 설정** → `loadUserByUsername`이다. `JdbcTokenRepositoryImpl.updateToken`은 **영향 행 수를 버린다**(0행이어도 정상 반환).
4. `PersistentTokenBasedRememberMeServices.logout`은 `removeUserTokens(username)` — **그 회원의 모든 기기** 토큰을 지운다(U4와 다름). `AbstractRememberMeServices.loginSuccess`는 체크가 없으면 **기존 쿠키를 그대로 두고 반환**한다. `onLoginSuccess`는 시리즈를 만들지만 돌려주지 않고, 쿠키 설정 메서드 `addCookie`는 private이다(보호 메서드 `setCookie`·`generateSeriesData`·`generateTokenData`·`decodeCookie`·`extractRememberMeCookie`·`cancelCookie`는 쓸 수 있다).
5. 로그인 성공 처리 `VisitLoggingAuthenticationSuccessHandler`(폼 로그인 전용)가 방문 기록·읽은 알림 정리·**fresh 상태 재확인**을 한다. 거부할 때는 세션 무효화·컨텍스트 초기화만 하고 remember-me 쿠키·행은 건드리지 않는다. 자동 로그인 필터는 이 핸들러를 타지 않는다. 필터 순서(바이트코드)는 다음과 같다: `autoLogin` → `AuthenticationManager.authenticate` → `SessionAuthenticationStrategy.onAuthentication`(레지스트리 등록) → 컨텍스트 설정·저장 → 성공 핸들러. 성공 핸들러가 있으면 **3인자** `onAuthenticationSuccess`를 부른 뒤 **체인을 잇지 않고 반환**하고, 없으면 체인을 계속한다. 그래서 필터 성공 핸들러로는 "검증 후 원래 요청 계속"을 할 수 없다.
6. 세션 레지스트리의 principal은 로그인마다 새 `CustomUserDetails` 인스턴스다. 폼 로그인에서는 `loginSuccess`가 세션 등록(`RegisterSessionAuthenticationStrategy`) **뒤에** 같은 principal 인스턴스로 호출된다(`AbstractAuthenticationProcessingFilter` 순서). `AdminSecurityService.refreshAuthentication`은 SecurityContext의 principal만 새로 바꾸고 레지스트리 principal은 그대로 둔다. 이때 인증 종류와 무관하게 `UsernamePasswordAuthenticationToken`을 만든다.
7. `member.user_id`는 `varchar(50)`, 테이블 기본 콜레이션은 `utf8mb4_general_ci`. 새 테이블만 만드는 마이그레이션은 `BaselineSchemaTest` 단언 또는 기능 통합 시험으로 충분하다(`docs/migration-guide.md` 시험 정책). 현재 최신은 V14.
8. 시각은 주입된 `Clock`만 쓴다(`ClockUsageConventionTest`). 라이브러리 내부의 `new Date()`(토큰 교체·만료 판정)는 규약 대상 밖이다.

## 성공 기준 (검증 가능)

1. 체크박스를 켜고 로그인하면 `remember-me` 쿠키(HttpOnly, Max-Age 7일)와 `persistent_logins` 행 1개가 생기고, 세션 없이 쿠키만으로 `/admin` 요청이 인증된다. 끄면 쿠키·행이 없다(기존 동작).
2. 자동 로그인 세션이 `SessionRegistry`에 등록돼 세션 관리 화면에 나오고 강제 만료 대상이 된다.
3. 역할·상태·비밀번호 변경·재설정·자동 잠금이 커밋되면 그 회원의 토큰이 같은 트랜잭션에서 모두 지워져, 쿠키로 다시 들어오지 못한다.
4. 수동 단일 만료·로그아웃은 **그 기기의 시리즈만** 지우고, 같은 회원의 다른 기기 쿠키는 계속 동작한다. 회원 단위 수동 만료는 현재 세션의 기기를 뺀 그 회원의 토큰을 모두 지운다. 토큰 삭제가 실패하면 세션도 만료되지 않아 같은 요청으로 재시도할 수 있다.
5. 비활성·잠금·변경 강제 계정의 쿠키, 이미 교체된(탈취 의심) 쿠키는 500이 아니라 익명(페이지 302 `/admin/login`·API 401)으로 끝난다. 탈취가 의심되면 그 회원의 토큰과 세션이 모두 끊긴다.
6. 자동 로그인 1회 = `visit_log` 1건(ADMIN·MANAGER). 거부된 자동 로그인은 방문으로 남지 않는다.

## 쟁점과 결정

### 쟁점 1 — 토큰 방식

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| **A. 영속 토큰(채택)** | `PersistentTokenBasedRememberMeServices` + DB 테이블. 시리즈(기기)·토큰(매 사용 교체) | 서버에서 기기 단위·회원 단위로 폐기할 수 있다(U4의 전제). 탈취를 감지한다. 테이블·마이그레이션이 필요하다 |
| B. 해시 토큰 | `TokenBasedRememberMeServices` — 쿠키에 사용자·만료·서명(비밀번호 해시 포함) | 스키마가 없고 비밀번호 변경 시 자동 무효다. 그러나 특정 기기·역할 변경·수동 만료에 서버가 폐기할 수단이 없어 U4·성공 기준 3·4를 못 맞춘다 |

**결정: A** — U1(이슈 권장)과 U4는 서버 폐기 수단이 필요하다.

### 쟁점 2 — 세션 ↔ 기기(시리즈) 연결과 불변식 (U4 "해당 기기만")

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| A. 로그아웃 핸들러에만 의존 | 만료된 세션의 다음 요청에서 `ConcurrentSessionFilter`가 부르는 로그아웃 핸들러가 그 쿠키 시리즈를 지운다 | 끊긴 기기가 그 세션 쿠키로 다시 요청하지 않으면(브라우저 종료 → 재실행 시 remember-me 쿠키만 남음) 다시 들어온다. 기각(보조 경로로만 남는다) |
| **B. principal에 시리즈 기록 + 불변식 유지 + 사후 검증(채택)** | 아래 I1·I2 | 세션 ID를 저장하지 않으므로 세션 ID 변경과 무관하다 |
| C. 세션 ID ↔ 시리즈 테이블 | `persistent_logins`에 세션 ID 칼럼 | 자동 로그인은 시리즈 교체가 세션 생성 **앞**이라 그 시점에 세션 ID가 없다. 세션 ID가 바뀔 때마다 갱신해야 한다. 기각 |

**불변식 I1**: 한 브라우저의 remember-me 쿠키가 가리키는 시리즈는 **그 브라우저의 현재 인증 세션 principal에 기록된 시리즈와 같거나, 쿠키가 없다.** 서버 쪽에서 이 기기의 시리즈를 잃지 않도록 시리즈를 **두 곳에 기록**한다(v4 — 리뷰 3-2): ① principal(세션 레지스트리를 통해 수동 만료가 찾는다), ② **HTTP 세션 속성 `REMEMBER_ME_SERIES`**(세션 고정 방어의 `changeSessionId`에도 같은 세션 객체라 유지되고, 같은 세션에서의 재로그인으로 principal이 바뀌어도 남는다). 이를 위해:
- **폼 로그인(`loginSuccess` 재정의)**: 이 브라우저의 기존 시리즈를 **요청 쿠키와 세션 속성 두 출처에서 모아 먼저 삭제**한다. 세션 속성이 있어야 "remember-me 쿠키만 지운 채 같은 세션으로 재로그인"한 경우에도 이전 시리즈 A를 찾는다 — `loginSuccess`는 새 principal 등록 **뒤**에 불려 레지스트리에는 이미 새 principal만 있기 때문이다. 체크 여부·계정 전환과 무관하다 — 그 쿠키를 가진 사람만 그 시리즈를 지울 수 있으므로 남의 토큰을 지우는 공격면이 없다. 체크했으면 새 시리즈를 발급해 principal에 기록하고 쿠키를 설정한다. 체크하지 않았으면 쿠키를 취소한다(v2 — 리뷰 4).
- **`loginSuccess`의 DB 작업이 예외로 끝나면 인증을 먼저 정리한다(v8 — 리뷰 7)**: 폼 로그인 필터는 **컨텍스트 저장 → `loginSuccess` → 성공 핸들러(fresh 재검증)** 순서다(7.0.7 바이트코드). 기존 시리즈 삭제가 실패해 `loginSuccess`가 예외로 끝나면 fresh 재검증 없이 저장된 인증이 세션에 남는다. 그러므로 예외가 나면 세션 무효화·컨텍스트 초기화·쿠키 취소를 한 뒤 예외를 다시 던진다(fail-closed — 정리 실패를 무시하고 로그인을 계속하지도 않는다). 새 시리즈 **발급** 실패(`createNewToken`)는 라이브러리처럼 로그만 남기고 remember-me 없이 계속한다. 이 경우 시리즈를 기록하지 않으며, 성공 핸들러의 fresh 재검증은 그대로 돈다.
- **시리즈 기록 시점**: 폼 로그인은 `onLoginSuccess`가 새 시리즈를 principal과 세션 속성에 함께 기록한다(세션 전략 뒤라 세션이 있다). 체크하지 않은 로그인은 세션 속성을 지운다.
- **자동 로그인(`processAutoLoginCookie` 재정의)**: 반환하는 새 principal에 **DB 행의 정식 시리즈**(쿠키 값 그대로가 아님 — 쟁점 6)를 기록한다(레지스트리 등록 **전**). 세션 속성은 세션이 생긴 뒤인 사후 검증 필터(I2)가 통과 시점에 기록한다.
- **로그아웃(`logout` 재정의)**: 이 기기의 시리즈를 **네 출처에서 모아(중복 제거)** 삭제하고 쿠키를 취소한다(v3 — 리뷰 2-2, v4에 ④ 추가). 네 출처는 ① 인자로 받은 인증의 principal 시리즈(`refreshAuthentication`도 시리즈를 옮긴다), ② 현재 세션 ID로 찾은 **레지스트리 principal**의 시리즈, ③ 요청 쿠키의 시리즈(인증 세션 없는 로그아웃·만료 세션의 다음 요청), ④ 세션 속성 `REMEMBER_ME_SERIES`다. remember-me 쿠키만 지운 브라우저가 로그아웃해도 서버의 시리즈가 남지 않는다. 로그아웃 핸들러 순서상 `SecurityContextLogoutHandler`(세션 무효화)가 마지막이라 ②를 읽을 수 있다(구현 때 시험으로 확인).
  - **명시적 로그아웃도 "기기 폐기"다(v7 — 리뷰 6)**: 모은 시리즈를 삭제한 **뒤** 그 회원의 활성 세션 중 principal 시리즈가 같은 세션을 **전부 만료**한다(현재 세션은 `SecurityContextLogoutHandler`가 무효화). 수동 단일 만료와 같은 동작을 `RememberMeDeviceRevoker.revokeDevice(memberId, series)` 하나로 묶어 두 경로가 함께 쓴다. 삭제 → 만료 순서도 같다. 같은 시리즈를 가진 다른 세션(서버에 남은 S1)이 로그아웃 뒤에도 살아남지 않게 하기 위해서다. 이로써 "I2 통과 직후 로그아웃" 경합도 단일 만료와 같은 증명으로 닫힌다.
  - **만료 세션 정리와 명시적 로그아웃을 구분한다(v6 — 리뷰 5-2)**: 같은 핸들러를 `ConcurrentSessionFilter`도 부른다(만료된 세션의 다음 요청). 이때 현재 세션의 `SessionInformation`이 **만료 상태이면 쿠키만 취소하고 시리즈는 지우지 않는다.** 폐기 범위는 만료를 일으킨 쪽이 이미 정했기 때문이다(회원 전체·같은 시리즈·현재 기기 제외). 지우면 회원 단위 만료가 보존한 현재 기기 시리즈 X를, X를 공유하던 다른 만료 세션의 후속 요청이 지워 버린다.

**사후 검증 I2** — 레지스트리 등록 **뒤에** "이 세션의 시리즈가 아직 DB에 있는가"를 본다(v2 — 리뷰 2·3·8):
- **자동 로그인**: 새 필터 `RememberMeLoginVerificationFilter`를 `addFilterAfter(…, RememberMeAuthenticationFilter.class)`로 둔다. `processAutoLoginCookie`가 성공하면 요청 속성에 시리즈를 표시하고, 이 필터는 그 표시가 있고, 현재 인증이 `RememberMeAuthenticationToken`이며, principal 시리즈가 같을 때만 동작한다.
  - 시리즈 행이 없으면(그 사이 폐기): 세션 무효화·컨텍스트 초기화·쿠키 취소 후 **익명으로 체인을 계속**한다. 302/401은 인가 규칙이 정하므로 응답 형식을 따로 만들지 않는다.
  - **조회 자체가 실패하면(DB 예외) 같은 정리를 먼저 하고** 예외를 다시 던진다(v3 — 리뷰 2-1). 이미 저장된 인증이 세션에 남으면 다음 요청은 세션 인증이라 `processAutoLoginCookie`도 이 필터도 타지 않아 검증이 우회되기 때문이다. 오류 응답과 인증 정리는 별개로 보장한다(fail-closed).
  - 행이 있으면: 방문 기록·읽은 알림 정리(U3) 후 체인을 계속한다.
  - 필터 성공 핸들러는 체인을 잇지 못하므로(정찰 5) 쓰지 않는다.
- **폼 로그인**: 기존 `VisitLoggingAuthenticationSuccessHandler`가 fresh 재확인 뒤 같은 검증(principal 시리즈가 있으면 행 존재)을 한다. **거부 경로는 방금 발급한 시리즈 행 삭제 + 쿠키 취소까지** 한다(리뷰 2 — 기존 거부는 세션만 무효화했다).
- **왜 경합 없이 닫히는가**: 폐기 쪽은 항상 **DB 삭제 → (커밋) → 세션 만료** 순서다(쟁점 3). 등록은 검증보다 앞이다.
  - 검증이 행을 봤다면 그 삭제는 검증 뒤에 일어났으므로, 이어지는 세션 만료가 이미 등록된 이 세션을 찾는다.
  - 검증이 행을 못 봤다면 검증이 거부한다.
- **0행 토큰 갱신은 실패로 처리한다**: `RememberMeTokenRepository.updateToken`이 0행이면 예외를 던지고, 라이브러리가 이를 `RememberMeAuthenticationException`으로 바꿔 익명 처리한다(리뷰 3 최소 조치). 사후 검증도 같은 경우를 잡지만, 쓸모없는 새 토큰으로 쿠키를 덮어쓰지 않도록 이르게 끊는다.
- **남는 창(수용)**: 폼 로그인에서 세션 등록 ~ `loginSuccess`의 시리즈 기록 사이(같은 요청 안의 수 ms)에 ADMIN이 **수동 단일 만료**를 누르면, principal 시리즈가 아직 없어 행을 지우지 못한다.
  - 이 경우 성공 핸들러가 현재 세션의 `SessionInformation.isExpired()`를 보고 거부한다(시리즈 삭제·쿠키 취소).
  - 다만 그 필드는 일반 필드라 다른 스레드의 쓰기 가시성이 보장되지 않는다.
  - 목록 조회 → 클릭이 그 수 ms 안에 들어와야 하는 창이라 기존 "세션 강제 만료는 best-effort" 계약 안으로 수용한다. 자동 폐기(`AdminSessionRevokeEvent`)는 회원 단위 삭제라 이 창이 없다.

### 쟁점 3 — 폐기 지점·범위·트랜잭션 경계

| 사건 | 폐기 범위 | 구현·경계 |
|---|---|---|
| `AdminSessionRevokeEvent`(역할·상태 변경, 본인 비밀번호 변경, 재설정, 자동 잠금) | 그 회원의 **모든** 토큰 | 새 리스너 `RememberMeTokenRevokeListener`(`@TransactionalEventListener(phase = BEFORE_COMMIT)`)가 **원 트랜잭션 안에서** `DELETE FROM persistent_logins WHERE username = (SELECT user_id FROM member WHERE id = ?)` — 회원 변경과 원자적이다. 실패하면 원 변경도 롤백되고 요청이 실패해 그대로 재시도할 수 있다(fail-closed). 세션 만료는 기존대로 AFTER_COMMIT이다(v2 — 리뷰 1: AFTER_COMMIT의 JDBC 쓰기는 커밋이 보장되지 않는다) |
| 수동 단일 만료 | 그 세션 principal의 시리즈 1개 + **같은 시리즈를 가진 그 회원의 모든 활성 세션**(같은 기기) | **삭제 → 만료 순서**. `AdminSessionService`가 핸들로 활성 세션을 **찾기만** 하고, `AdminSessionManageService`가 시리즈 삭제(자동 커밋 JDBC)에 성공한 뒤 고른 세션과 **레지스트리에서 principal 시리즈가 같은 같은 회원의 세션 전부**에 `expireNow()`를 부른다(v5 — 리뷰 4-1: 서버에 살아 있는 S1과, 세션 쿠키만 지운 브라우저가 쿠키로 복원한 S2가 같은 시리즈를 가질 수 있다. S1만 끊으면 S2가 남고, S2는 세션 인증이라 I2도 다시 돌지 않는다. U4의 "기기" = 시리즈다). 시리즈가 없는 세션(체크 없이 로그인)은 고른 세션만 만료한다. I2 경합 증명도 이로써 단일 만료에 성립한다 — 검증이 행을 봤다면 그 뒤의 삭제 → "같은 시리즈 세션 전부 만료"가 이미 등록된 세션을 찾는다. 삭제가 실패하면 예외로 응답이 실패하고 세션은 그대로라 **같은 핸들로 재시도**할 수 있다(v2 — 리뷰 5). 감사는 기존 `@AdminActionLogged` FAIL 행이다 |
| 수동 회원 단위 만료 | 그 회원의 모든 토큰 − 현재 세션의 시리즈(본인 대상일 때) | 같은 순서(삭제 → 만료). 현재 세션 시리즈는 레지스트리 principal(`getSessionInformation(currentSessionId)`)에서 읽는다 — SecurityContext principal은 `refreshAuthentication`이 바꿨을 수 있다 |
| 명시적 로그아웃 | 이 기기의 시리즈(네 출처) + 같은 시리즈를 가진 그 회원의 활성 세션 전부 | `logout` 재정의 → `RememberMeDeviceRevoker.revokeDevice`(삭제 → 만료, 수동 단일 만료와 공유 — v7) |
| 만료 세션의 다음 요청(`ConcurrentSessionFilter`) | 없음 — 쿠키만 취소 | `logout` 재정의의 만료 분기(v6) |
| 탈취 감지(교체된 토큰 재사용) | 그 회원의 모든 토큰 + **모든 세션 만료**(U5) | `autoLogin` 재정의에서 `CookieTheftException`을 잡는다. 라이브러리가 이미 `removeUserTokens`를 했으므로, 재정의에서 탈취 판정 **전에** `getTokenForSeries`로 읽어 둔 토큰 행의 `username`으로 **레지스트리만 순회해**(DB 조회 없음) principal의 `userId`가 같은(대소문자 무시 — 칼럼 콜레이션과 같은 판정) 세션을 전부 만료한 뒤 쿠키를 취소하고 `null`을 반환한다(익명 계속 — 500 방지). 삭제 성공 이후의 세션 만료가 추가 DB 조회에 의존하면, 그 조회가 실패했을 때 공격자 세션이 남고 시리즈가 이미 없어 재시도로도 탈취 처리가 다시 일어나지 않기 때문이다(v5 — 리뷰 4-2). 사전 조회가 실패하면 삭제도 일어나기 전이라 요청 오류로 끝나고 재시도할 수 있다. WARN 로그에 아이디·시리즈를 남기지 않는다 |

- **회원 단위 수동 만료의 범위**: "살아 있는 세션의 시리즈만"이 아니라 "회원 토큰 전부(현재 기기 제외)"를 지운다.
  - 이유: 화면 의미가 "이 회원의 접속을 모두 끊기"이고, 세션 없이 쿠키만 가진 기기도 그 회원의 접속 수단이다.
  - 단일 만료는 고른 **그 세션**만 대상이라 그 기기만 지운다(U4).
- 수동 만료의 토큰 삭제는 감사 라벨·응답(`expiredCount`)을 바꾸지 않는다.

### 쟁점 4 — 자동 로그인 성공 처리와 인증 출처 보존

- **방문(U3)·읽은 알림 정리**: 사후 검증 필터(I2)가 검증을 통과한 뒤 기록한다. 인증·세션 전략·검증을 모두 지난 뒤라 거부된 요청이 방문으로 남지 않는다(v2 — 리뷰 8). 기록 코드는 `VisitLoggingAuthenticationSuccessHandler`의 private 메서드를 `LoginVisitRecorder` 컴포넌트로 옮겨 공유한다(동작 변경 없는 추출 — 기존 폼 로그인 시험이 회귀를 잡는다).
- **fresh 재확인**: 자동 로그인은 `loadUserByUsername`이 그 요청에서 상태를 막 읽는다. 상태·역할·비밀번호 변경은 모두 토큰 폐기를 동반하므로, I2의 "시리즈 행 존재" 검증이 그 경합을 잡는다(비밀번호 해시 비교 대신).
- **로그인 실패 카운트**: 자동 로그인은 리셋하지도 증가시키지도 않는다.
- **인증 출처 보존(v2 — 리뷰 7)**: `AdminSecurityService.refreshAuthentication`은 현재 인증이 `RememberMeAuthenticationToken`이면 같은 타입(같은 키)으로 다시 만들고, 새 principal에 기존 principal의 시리즈를 옮긴다. 키는 `RememberMeKey` 빈(기동마다 `SecureRandom` 32바이트)으로 `SecurityConfig`·서비스·이 메서드가 공유한다.
- **권한 부족 응답은 기존 403 유지(v3 — 리뷰 2-3)**: Spring Security 7.0.7 `ExceptionTranslationFilter`는 `AccessDeniedException`이 나면 익명뿐 아니라 **remember-me 인증도** 진입점(`AuthenticationEntryPoint`)으로 보낸다(바이트코드: `isAnonymous`·`isRememberMe` → `sendStartAuthentication`). 그대로 두면 자동 로그인한 MANAGER의 ADMIN 전용 API는 401, 페이지는 로그인 302가 된다. 이를 막기 위해 `SecurityConfig`의 진입점 람다는 **넘겨받은 예외의 `getAuthenticationRequest()`가 `RememberMeAuthenticationToken`이면**(7.0.7 `handleAccessDeniedException`이 원래 인증을 `setAuthenticationRequest`로 보존한다 — 바이트코드 확인) 접근 거부 처리기(`API_ACCESS_DENIED_HANDLER`·`DEFAULT_ACCESS_DENIED_HANDLER`)를 **직접** 호출해 기존 403(API JSON·페이지 403 화면)으로 응답한다. 현재 `SecurityContextHolder`는 보지 않는다 — `sendStartAuthentication`이 진입점 호출 **전에** 빈 컨텍스트로 바꾸므로 비어 있고, 기존 접근 거부 람다에 재위임하면 그 람다의 "익명이면 401" 분기에 걸리기 때문이다(v4 — 리뷰 3-1). 비운 컨텍스트는 세션에 저장되지 않으므로(명시적 저장) 같은 세션의 다음 요청은 그대로 인증 상태다(F5⑥에서 확인). 인가 규칙·응답 형식은 바뀌지 않고 기존 403 계약을 지키는 조치다. 민감 작업 재인증을 도입하면 이 분기를 다시 본다.

### 쟁점 5 — 구성 요소와 설정

- **`AdminRememberMeServices extends PersistentTokenBasedRememberMeServices`**(`com.cms.config.auth`)
  - 설정: 유효 기간 7일(U2), 파라미터·쿠키 이름은 기본 `remember-me`, HttpOnly(기본). `Secure`도 기본(`request.isSecure()`)으로 세션 쿠키와 같은 정책이다. TLS 프록시 뒤의 `Secure` 판정은 `deployment-edge.md` 쿠키 항목에 remember-me 쿠키를 추가해 확인한다.
  - 재정의:
    - `loginSuccess`: I1.
    - `onLoginSuccess`: 시각은 `Date.from(clock.instant())`, 시리즈를 principal에 기록한다. 라이브러리 `addCookie`가 private이라 같은 순서로 `setCookie`를 쓴다. 저장 실패는 라이브러리처럼 로그를 남기고 계속한다.
    - `processAutoLoginCookie`: 시리즈 기록, 요청 속성 표시.
    - `autoLogin`: 탈취 처리.
    - `logout`: 시리즈 1개만 삭제.
- **`RememberMeTokenRepository extends JdbcTokenRepositoryImpl`**: `updateToken` 0행이면 예외. 추가 메서드는 `removeSeries`·`existsSeries`·`removeAllForMember(memberId)`·`removeAllForMemberExcept(memberId, series)`·`findMemberIdByUsername`.
- **새 구성 요소**: `RememberMeLoginVerificationFilter`(I2), `RememberMeTokenRevokeListener`(BEFORE_COMMIT), `RememberMeDeviceRevoker`(기기 폐기 = 시리즈 삭제 → 같은 시리즈 세션 만료, 로그아웃·수동 단일 만료 공용), `RememberMeKey`, `LoginVisitRecorder`.
- **`SecurityConfig`**: `.rememberMe(r -> r.rememberMeServices(services).key(key.value()))` + `addFilterAfter(verificationFilter, RememberMeAuthenticationFilter.class)`. **URL 접근 규칙·게이트는 바꾸지 않는다.**
- **`CustomUserDetails`**: `rememberMeSeries`(`volatile`, 접근자 2개). 인증 판단에 쓰지 않는다.
- **로그인 화면**: 체크박스는 그대로 두고 라벨에 "(7일)"을 붙인다.

### 쟁점 6 — 스키마 (V15)

```sql
CREATE TABLE IF NOT EXISTS `persistent_logins` (
  `series` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `username` varchar(50) NOT NULL,
  `token` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `last_used` datetime(6) NOT NULL,
  PRIMARY KEY (`series`),
  KEY `idx_persistent_logins_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```
- `series`·`token`은 **바이너리 콜레이션**(`ascii_bin`)이다(v6 — 리뷰 5-1). 테이블 기본 `utf8mb4_general_ci`를 상속하면 대소문자만 바꾼 시리즈 `X′`가 같은 행을 찾는다. 그러면 principal에는 `X′`가 기록돼, 정확한 문자열로 비교하는 "같은 시리즈 세션 만료"에서 빠진다. 값은 라이브러리가 만드는 Base64라 ASCII다. 여기에 더해 principal·세션 속성에는 **쿠키 값이 아니라 DB에서 읽은 정식 시리즈**(`getTokenForSeries`가 돌려준 행의 `getSeries()`)를 기록한다 — 두 겹 방어.
- `username`은 `member.user_id`(varchar(50))와 길이·콜레이션이 같다.
- **FK는 두지 않는다**: 회원은 삭제가 아니라 상태 전이이고, 폐기는 위 지점들이 맡으며, 상태가 바뀐 계정의 쿠키는 `loadUserByUsername`이 거부한다.
- `last_used`는 라이브러리가 `java.sql.Timestamp`로 읽고 쓴다. 같은 JVM 시간대로 쓰고 읽으므로 `datetime(6)`이 일관된다.
- 새 테이블만 만들어 기존 행에 영향이 없다 → `BaselineSchemaTest`에 칼럼·키 단언을 더한다. 이전 앱으로 롤백해도 모르는 테이블이라 기동에 영향이 없다.
- 만료된 행은 쌓인다(관리자 수 × 기기 수라 작다). 정리 작업은 후속 후보로 둔다.

## 놓치기 쉬운 입력·실패 모드 → 고정 시험

통합 시험 `RememberMeLoginIntegrationTest`(`@SpringBootTest` + MockMvc + MariaDB, 실제 `SecurityConfig`·폼 로그인)로 고정한다.
- 경합은 `@MockitoSpyBean RememberMeTokenRepository`로 "원하는 지점"에 다른 작업을 끼워 넣어 결정적으로 만든다(프로젝트 관례 — `MediaConcurrencyIntegrationTest`).
- DB 단언은 요청이 끝난 뒤 별도 `JdbcTemplate` 조회(독립 연결)로 한다(리뷰 1).

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 강제 폐기가 쿠키로 되살아남(역할 변경·본인 비밀번호 변경), 또는 삭제가 커밋되지 않음 | 쿠키 로그인 → ADMIN 역할 변경 API / 본인 비밀번호 변경 API → 요청 종료 후 그 회원 행 0개 + 쿠키만 보낸 `/admin`이 302 `/admin/login`. 리스너 삭제 실패를 주입하면 원 변경이 롤백된다(역할 그대로) |
| F2 | 토큰 조회 ~ 세션 등록 사이의 폐기 경합(리뷰 3), 검증 조회 실패 시 우회(리뷰 2-1) | ① `getTokenForSeries` 직후 그 회원 토큰 삭제를 끼운다 → `updateToken` 0행 → 익명(302), 방문 미증가. ② `updateToken` 직후 삭제를 끼운다 → 사후 검증이 거부 → 익명, 세션 무효, 방문 미증가. ③ `existsSeries`에 예외 주입 → 그 요청은 오류지만 **그 요청이 만든 세션으로 다시 요청해도 인증되지 않음**, 쿠키 취소 |
| F3 | 폼 로그인 경합·기존 쿠키가 있는 재로그인(리뷰 2·4) | ① `createNewToken` 직전에 같은 회원의 비밀번호 변경(이벤트 경로)을 끼운다 → fresh 재확인 거부 + 방금 발급한 행 삭제·쿠키 취소(행 0개). ② 쿠키 있는 브라우저로 **체크 없이** 재로그인 → 옛 시리즈 행 삭제·쿠키 취소. 새 세션을 단일 만료한 뒤 옛 쿠키로 접근 불가. ③ 체크하고 재로그인(같은 계정·다른 계정) → 옛 시리즈 삭제, 새 시리즈 1개. ④ 세션 유지 + remember-me 쿠키만 제거(값은 보관) → 같은 세션으로 체크 없이 재로그인 → 그 시점에 옛 시리즈 삭제 → 로그아웃 뒤 보관 쿠키 재사용 거부(리뷰 3-2). ⑤ 기존 쿠키가 있는 폼 재로그인에서 `removeSeries` 실패 주입 → 그 요청은 오류, **그 요청이 쓴 세션으로 다시 요청해도 인증되지 않음**, 쿠키 취소. 같은 시험을 "비밀번호 검증 뒤 역할 강등 커밋·세션 만료가 세션 등록보다 먼저 끝난" 순서(`authenticate` 직후 훅에서 변경 실행)로도 고정(리뷰 7) |
| F4 | U4 범위 위반·재시도 불가(리뷰 5) | ① 기기 2개 → 기기 1 단일 수동 만료 → 기기 1 행만 삭제, 기기 2 쿠키 인증 유지. 자동 로그인 세션이 레지스트리에 있고 principal 시리즈 = 쿠키 시리즈. ② `removeSeries` 실패 주입 → 응답 실패·세션 활성 유지 → 재시도 성공·행 삭제. ③ 로그아웃은 그 기기만, 본인 대상 회원 단위 만료는 현재 기기만 남긴다. ④ remember-me 쿠키를 뺀 채 세션만으로 로그아웃 → 그 세션의 시리즈 삭제, 보관해 둔 쿠키로 재인증 불가(리뷰 2-2) ⑤ 체크 로그인(S1, 시리즈 X) → 세션 쿠키만 버리고 쿠키로 복원(S2, 같은 X) → ADMIN이 S1을 단일 만료 → **S2도 끊김**, X 삭제(리뷰 4-1). ⑥ S2 자동 로그인이 I2를 통과한 직후(검증 필터 뒤 훅) S1 단일 만료를 끼움 → S2의 다음 요청 거부. ⑦ 같은 시리즈 X의 S1·S2 → S2에서 본인 대상 회원 단위 만료 → S1으로 후속 요청(만료 처리) → **X 유지**, S2 쪽 쿠키로 재인증 성공(리뷰 5-2). ⑧ 대소문자만 바꾼 시리즈 쿠키 → 인증되지 않음(리뷰 5-1). ⑨ 같은 X의 S1·S2 중 S2에서 로그아웃 → **S1의 다음 요청도 거부**, 같은 회원의 다른 기기 Y는 유지. 자동 로그인이 I2를 통과한 직후(검증 필터 뒤 훅) 같은 기기 로그아웃을 끼우면 그 세션의 다음 요청 거부(리뷰 6) |
| F5 | 상태 거부·탈취가 500, 탈취 시 공격자 세션 생존(리뷰 6, U5), 인증 출처 변조(리뷰 7), 기존 동작 회귀 | ① 잠금·비활성 계정 쿠키 → 302 + 쿠키 취소. ② 기기 A가 쿠키로 세션 생성(공격자) → 원래 쿠키(교체 전 값) 재사용 → 500 아님, 그 회원 행 0개, **A 세션 만료**. 탈취 처리 중 회원 테이블 조회 실패를 주입해도(`member` 조회 스파이) A 세션의 다음 요청이 거부된다 — 만료가 DB에 의존하지 않음(리뷰 4-2). ③ 자동 로그인 → 내 정보 수정 → 인증이 여전히 `RememberMeAuthenticationToken`이고 시리즈 유지. ④ 체크 없이 빈 브라우저로 로그인 → 쿠키·행 없음. ⑤ 자동 로그인 → `visit_log` +1, 쿠키 HttpOnly·Max-Age 604800. ⑥ MANAGER가 ADMIN 전용 API·페이지에 접근 → 폼 로그인·자동 로그인 모두 **403**(API JSON `FORBIDDEN`·페이지 403 화면), 익명은 401·302로 구분, 403 뒤 **같은 세션의 정상 요청은 그대로 인증**(리뷰 2-3·3-1) |

추가로 다음을 확인한다.
- `BaselineSchemaTest`: V15 칼럼·키.
- 기존 방문 로그·로그인 실패 잠금·세션 관리·알림 로그인 시험이 `LoginVisitRecorder` 추출과 성공 핸들러 변경의 회귀를 잡는다.
- MockMvc 기반이라 브라우저 재시작·실제 세션 파괴는 확인할 수 없다. 7단계 실기(Playwright)로 다음 흐름을 확인한다: 체크 → 세션 쿠키만 지움 → 재방문 자동 로그인 → 세션 관리 화면 단일 만료 → 세션 쿠키 지우고 재방문 시 로그인 화면.

## 작업 단계

1. V15 마이그레이션 + `BaselineSchemaTest` 단언.
2. (TDD 빨강) `RememberMeLoginIntegrationTest` F1~F5 작성 → 실패 확인.
3. 구성 요소를 다음 순서로 만든다. 단계마다 `./gradlew compileJava`로 점검한다.
   - `RememberMeTokenRepository` → `RememberMeKey` → `CustomUserDetails` 시리즈
   - → `LoginVisitRecorder` 추출 → `AdminRememberMeServices` → `RememberMeLoginVerificationFilter` → `SecurityConfig` 연결
4. 폐기 연동: `RememberMeTokenRevokeListener`(BEFORE_COMMIT), `AdminSessionService`(찾기/만료 분리)·`AdminSessionManageService`(삭제 → 만료), 성공 핸들러 거부 경로, `refreshAuthentication`.
5. 로그인 화면 문구 "(7일)".
6. 영향 범위 시험: `com.cms.config.*`·`com.cms.admin.session.*`·`com.cms.admin.member.*`·`com.cms.admin.notification.*`·`com.cms.admin.dashboard.*`·`BaselineSchemaTest`·`*ConventionTest`.
7. 실기(dev bootRun + Playwright) — 위 시나리오 + 체크 없는 로그인 회귀.
8. 문서를 갱신한다.
   - `com.cms.config` `CLAUDE.md`: remember-me·폐기 표
   - `com.cms.admin.session` `CLAUDE.md`: 수동 만료의 삭제 → 만료 순서
   - `com.cms.admin.member` `CLAUDE.md`: 로그인 정책 요약
   - 루트 `CLAUDE.md` 보안 규칙: 로그인 정책 한 줄
   - `docs/migration-guide.md`: V15 행
   - `deployment-edge.md`: remember-me 쿠키 `Secure`
   - PLAN 결과, 인덱스

## 리스크

- **비밀번호 없는 인증 경로가 생긴다.** 쿠키가 탈취되면 7일(마지막 사용 기준) 동안 그 기기로 들어온다.
  - 완화 수단: HttpOnly, 탈취 감지(U5 — 토큰 전부 + 세션 전부), 비밀번호 변경·재설정·상태 변경 시 전체 폐기, 세션 관리 화면의 수동 만료.
  - 민감 작업에 재인증(`fullyAuthenticated`)을 요구하지 않는다. 그건 별도 인가 정책 변경이라 이번 범위 밖이다(후속 후보). 인증 출처는 보존되므로(쟁점 4) 나중에 도입할 수 있다.
- **동시 요청 경합(오탐 탈취)**: 브라우저 재시작 뒤 여러 탭이 같은 옛 쿠키로 동시에 요청하면 탈취로 판정돼 그 회원의 토큰·세션이 모두 끊긴다. U5로 그 비용이 커졌지만 사용자가 수용했다. 일반 화면 이동에서는 하위 리소스가 HTML 응답(새 쿠키) 뒤에 나가므로 생기지 않는다. 유예 창 구현은 과설계로 기각했다.
- **폼 로그인의 수 ms 창**(쟁점 2 "남는 창"): best-effort 계약 안으로 수용한다.
- **공개 화면 방문도 자동 로그인한다**: 쿠키를 가진 관리자가 공개 사이트를 열면 세션이 생기고 방문 1건이 된다. "새 접속 = 방문"과 같아 수용한다.
- **실패 시 동작**: 토큰 DB 장애 시 쿠키를 가진 요청이 오류가 될 수 있다. 리스너의 삭제 실패는 원 변경(역할·상태·비밀번호)을 실패시킨다(fail-closed — 의도).
- **다중 인스턴스**: 토큰은 DB라 공유되지만 세션 레지스트리는 인스턴스별이다(기존 전제와 같다).

## 구현 결과

### 핵심 확정 사항
- 계획 v8대로 구현했다. 계획과 달라진 점:
  - 탈취 처리의 "회원 테이블 조회 실패 주입" 시험(F5② 보조)은 두지 않았다 — 구현이 탈취 처리에서 DB를 조회하지 않아(미리 읽은 토큰 `username`으로 레지스트리만 순회) 주입할 지점이 없다. 보장은 구조로 성립한다.
  - "I2 통과 직후 같은 기기 로그아웃" 경합 시험은 단일 만료 시험(F4⑥)으로 갈음했다 — 두 경로가 같은 `RememberMeDeviceRevoker`를 쓴다.
  - `updateToken` 0행 검사를 단독으로 잡는 시험은 없다 — 같은 경우를 사후 검증이 함께 막아 F2①은 둘 중 하나만 있어도 통과한다(변이로 확인하지 않음).
  - 슬라이스 시험용 공용 설정 `RememberMeTestConfig`를 추가했다(`SecurityConfig`를 가져오는 시험 6개가 함께 import).
- 실기에서 확인한 동작: 세션 관리 화면에서 같은 기기(같은 시리즈)의 다른 세션을 만료하면 **현재 세션도 끊긴다** — v5 설계대로이며 `com.cms.config`·`com.cms.admin.session`의 `CLAUDE.md`에 적었다.

### 구현 파일
- 신규(`config/auth`): `AdminRememberMeServices`, `RememberMeTokenRepository`, `RememberMeLoginVerificationFilter`, `RememberMeTokenRevokeListener`, `RememberMeDeviceRevoker`, `RememberMeKey`, `RememberMeConfig`, `LoginVisitRecorder`
- 수정: `SecurityConfig`(rememberMe·검증 필터·진입점 403 보정), `VisitLoggingAuthenticationSuccessHandler`(방문 기록 위임·기기 확인·거부 시 토큰 정리), `CustomUserDetails`(시리즈), `AdminSessionService`(찾기/시리즈·아이디 단위 만료), `AdminSessionManageService`(삭제 → 만료), `AdminSecurityService`(인증 종류 보존), `templates/admin/login.html`("(7일)")
- 마이그레이션: `V15__persistent_logins.sql`
- 시험: `RememberMeLoginIntegrationTest`(신규 25건), `BaselineSchemaTest`(V15 1건), `RememberMeTestConfig`(신규), 생성자 변경 반영 — `VisitLoggingAuthenticationSuccessHandlerTest`·`AdminSessionManageServiceTest`·`SecurityConfig`를 가져오는 슬라이스 6개
- 문서: 루트·`com.cms.config`·`com.cms.admin.session`·`com.cms.admin.member` `CLAUDE.md`, `docs/migration-guide.md`(V15), `docs/verification/deployment-edge.md`, `docs/troubleshooting.md`, 인덱스 56번

### 검증 결과
- `RememberMeLoginIntegrationTest` 25/25 통과. 첫 실행 4건 실패의 원인은 시험 쪽이었다(같은 응답에 교체·취소 쿠키 두 개 → `getCookie`가 첫 번째를 돌려줌, 내 정보 수정 본문에 필수 `email` 누락, 401 과정의 새 익명 세션).
- **변이 9종**(토큰 폐기 리스너·사후 검증·만료 세션 분기·같은 시리즈 세션 만료·세션 속성 출처·403 보정·탈취 처리·인증 종류 보존·거부 시 토큰 정리)을 하나씩 되돌리면 각각 의도한 시험(F1·F2②③·F4⑦·F4⑤⑥⑨·F3④·F5⑥·F5②·F5③·F3①)이 실패하고, 원복 후 다시 통과함을 확인했다.
- 영향 범위 시험: `com.cms.config.*`·`admin.session.*`·`admin.member.*`·`admin.notification.*`·`admin.dashboard.*`·`common.api.*`·`BaselineSchemaTest`·`*ConventionTest` 통과. 전체 `./gradlew test`는 PR 직전 1회.
- 실기(dev bootRun — V15 적용 확인 + Playwright):
  - 체크 로그인 → `remember-me` HttpOnly 168시간, 행 1개, 방문 1건.
  - 세션 쿠키만 지우고 재방문 → 자동 로그인(대시보드), 토큰 교체, `last_used` 갱신, 방문 +1, 세션 관리 화면에 admin 세션 2개.
  - 옛 세션 수동 만료 → 같은 기기의 현재 세션도 끊김, 행 0개, 쿠키만으로 재방문 시 로그인 화면.
  - 체크 없는 로그인 → 쿠키 없음. 체크 로그인 → 로그아웃 → 쿠키 삭제·행 0개·로그인 화면.
  - 스크린샷 `.playwright-mcp/issue222-0{1..5}-*.png`(git 무시 경로).

- 코드 리뷰(초점 리뷰 1회차) 반영 2건: ① 폼 로그인 쿠키를 성공 핸들러의 검증 통과 뒤로 지연 발급(거부 응답 + 행 삭제 실패 시 발급 쿠키로 재인증되던 문제, 시험 F3①-b). ② 탈취를 라이브러리보다 먼저 판정해 세션 만료 → 토큰 삭제 순서로 처리(라이브러리는 삭제를 먼저 해 그 실패가 세션 만료를 건너뛰었다, 시험 F5②-b). 통합 시험 27건. 초점 리뷰 2회차 반영 2건: ③ 탈취 처리를 "만료 → 삭제 → 다시 만료"로(선행 만료 ~ 삭제 사이에 현재 토큰으로 등록·검증을 마친 세션이 살아남던 경합, 시험 F5②-c — 다른 스레드로 결정적 재현), ④ `processAutoLoginCookie`를 한 번의 조회로 직접 구현(사전 조회와 라이브러리 재조회가 어긋나 라이브러리 탈취 경로가 세션 만료를 건너뛰던 문제 — 구조적으로 제거, 별도 시험 없음). 통합 시험 28건. 초점 리뷰 3회차 반영 1건: ⑤ 탈취 처리의 토큰 삭제가 실패하면 그 회원을 메모리 차단 목록에 올려 삭제 성공까지 자동 로그인을 거부·재시도(행이 남아 공격자의 최신 쿠키로 재인증되던 문제, F5②-b 확장). 한계: 단일 인스턴스 메모리라 재기동하면 차단이 사라진다. 시험 훅의 재진입(끼운 요청이 삭제 재시도로 훅을 다시 부름)으로 커넥션 풀이 고갈돼 26건이 연쇄 실패한 적이 있다 — 훅을 1회로 제한해 해결(제품 결함 아님). 초점 리뷰 4회차 반영 1건: ⑥ 사후 검증이 탈취 차단 목록도 확인(차단 목록 검사를 지나 멈춘 자동 로그인이 탈취 처리 뒤 세션을 등록하던 경합, F5②-d). 통합 시험 29건.

### 이슈
- 변이 실험 스크립트(임시, 스크래치패드)의 첫 실행은 Windows Python의 `bash`가 WSL로 해석돼 **시험을 실행하지 않고 이전 결과 XML을 읽어** "전부 실패 0건"을 냈다. 실행 전 XML 삭제·종료 코드 확인을 넣어 미실행을 드러낸 뒤 `gradlew.bat` 전체 경로로 다시 돌렸다.
- Playwright MCP는 `page.once('dialog')`와 별개로 확인창을 모달 상태로 보고하지만, 스크립트는 수락된 상태로 계속 실행됐다.

### 후속
- 민감 작업(권한관리 등) 재인증(`fullyAuthenticated`) — 별도 인가 정책 변경. 인증 출처를 보존하므로 도입할 수 있다.
- 만료된 `persistent_logins` 행 정리 작업.
- 세션 관리 화면 문구: 같은 기기의 다른 세션을 만료하면 현재 세션도 끊긴다는 안내 추가 여부.

## 개정 이력

- v8 (2026-11-10): 7라운드 리뷰 반영. 기각 없음. 폼 로그인 `loginSuccess`의 기존 시리즈 삭제가 실패하면 세션 무효화·컨텍스트 초기화·쿠키 취소 후 예외를 다시 던진다 — 컨텍스트 저장이 `loginSuccess`보다 앞이라, 그대로 두면 fresh 재검증을 건너뛴 인증이 남는다(수용, F3⑤).
- v7 (2026-11-10): 6라운드 리뷰 반영. 기각 없음. 명시적 로그아웃도 시리즈 삭제 뒤 같은 시리즈의 세션 전부를 만료하도록 바꿨다. 수동 단일 만료와 같은 `RememberMeDeviceRevoker.revokeDevice`를 쓴다 — 로그아웃 경로에는 v5 보완이 빠져 있었다(수용, F4⑨).
- v6 (2026-11-10): 5라운드 리뷰 반영. 기각 없음.
  - ① `series`·`token`을 `ascii_bin`으로 바꾸고, principal·세션 속성에는 DB 행의 정식 시리즈를 기록한다 — 대소문자 무시 콜레이션에서 변조 시리즈 `X′`가 같은 행을 찾아, 정확한 문자열로 비교하는 같은 시리즈 세션 만료를 빠져나가던 문제(수용, F4⑧).
  - ② 만료 세션의 후속 요청에서 불리는 로그아웃 핸들러는 쿠키만 취소한다 — 회원 단위 만료가 보존한 현재 기기 시리즈를 같은 시리즈의 다른 만료 세션이 지우던 문제(수용, F4⑦).
- v5 (2026-11-10): 4라운드 리뷰 반영. 기각 없음.
  - ① 단일 수동 만료가 고른 세션의 시리즈를 지운 뒤 **같은 시리즈를 가진 같은 회원의 세션을 모두** 만료한다 — 서버에 남은 세션과 쿠키로 복원한 세션이 같은 시리즈를 가질 수 있고, U4의 "기기" = 시리즈다. I2 경합 증명도 단일 만료에 성립하게 된다(수용, F4⑤⑥).
  - ② 탈취 처리의 세션 만료를 미리 읽어 둔 토큰 `username`으로 레지스트리만 순회해 하도록 바꿨다 — 삭제 뒤 DB 조회가 실패하면 공격자 세션이 남고 재시도로도 회복되지 않기 때문이다(수용, F5②).
- v4 (2026-11-10): 3라운드 리뷰 반영. 기각 없음.
  - ① 403 보정을 현재 `SecurityContextHolder`가 아니라 진입점 예외의 `getAuthenticationRequest()`로 판정하고, 접근 거부 처리기를 직접 호출한다 — `sendStartAuthentication`이 진입점 전에 컨텍스트를 비우고, 기존 접근 거부 람다는 빈 컨텍스트를 401로 보내기 때문이다(수용, 바이트코드 확인).
  - ② 시리즈를 principal 외에 **세션 속성**에도 기록하고, 폼 로그인 정리·로그아웃이 그 속성도 출처로 쓴다 — 쿠키를 지운 채 같은 세션으로 재로그인하면 이전 시리즈 연결이 사라지던 문제(수용, F3④).
- v3 (2026-11-10): 2라운드 리뷰 반영. 기각 없음.
  - ① 사후 검증의 DB 조회가 실패해도 세션 무효화·컨텍스트 초기화·쿠키 취소를 먼저 하고 예외를 다시 던진다 — 남은 인증이 다음 요청에서 검증을 우회하기 때문이다(수용, F2③).
  - ② 로그아웃 시리즈를 인증 principal·레지스트리 principal·쿠키 세 출처에서 모아 삭제한다 — remember-me 쿠키만 지운 브라우저의 로그아웃도 그 기기 시리즈를 지운다(수용, F4④).
  - ③ remember-me 인증의 권한 부족이 진입점(401/302)으로 가는 Spring 동작을 진입점 람다에서 기존 403 분기로 돌려 응답 계약을 유지한다(수용, F5⑥).
- v2 (2026-11-10): 1라운드 리뷰 반영. 기각 없음. 정찰 3의 "`autoLogin`은 final" 오기도 정정했다.
  - ① 회원 단위 폐기를 AFTER_COMMIT JDBC에서 **BEFORE_COMMIT(원 트랜잭션 안)**으로 옮겼다 — AFTER_COMMIT의 JDBC 쓰기는 커밋이 보장되지 않는다(수용).
  - ② 폼 로그인 경합: 성공 핸들러 거부 경로가 방금 발급한 시리즈·쿠키까지 정리하고, 사후 검증(시리즈 행 존재)을 추가했다. 남는 수 ms 창은 best-effort로 명시했다(수용).
  - ③ 토큰 조회 후 삭제 경합: `updateToken` 0행을 실패로 처리하고, 레지스트리 등록 뒤 사후 검증 필터 `RememberMeLoginVerificationFilter`를 두었다. "삭제 → 만료" 순서로 경합이 닫히는 이유도 명시했다(수용).
  - ④ 기존 쿠키가 있는 재로그인: `loginSuccess` 재정의로 옛 시리즈를 삭제하고, 체크가 없으면 쿠키를 취소한다 — 불변식 I1(수용).
  - ⑤ 수동 만료를 "삭제 → 만료" 순서로 바꿔, 삭제가 실패하면 같은 핸들로 재시도할 수 있게 했다(수용).
  - ⑥ 탈취 감지 시 세션까지 만료한다 — 사용자 결정 U5(결정 필요 → 확정).
  - ⑦ `refreshAuthentication`이 `RememberMeAuthenticationToken`과 시리즈를 보존하고, 키는 빈으로 공유한다(수용).
  - ⑧ 방문 기록을 사후 검증 통과 뒤로 옮겼다. 필터 성공 핸들러는 체인을 잇지 못하므로(바이트코드: 3인자 호출 후 반환) 별도 필터로 처리한다(수용).
- v1 (2026-11-10): 최초 작성. 사용자 결정 U1~U4 반영.
