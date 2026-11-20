# PLAN — 패스키 단독 로그인(비밀번호·2단계 생략) (이슈 #280, #259 PR2)

- 상태: v7 (2026-11-19) — 계획 리뷰 7라운드 ship, 사용자 승인(2026-11-19), 구현·검증 완료, **완료 (2026-11-19 · #286 `5d18cd5`)**
- 이슈: #280. 선행 #259 PR1(`PLAN-passkey-second-factor.md`, #282 머지 `3e86cbe`) — 패스키는 TOTP 사용 회원의 2단계 대체 수단으로 이미 있다.
- 위험 변경: **인증(새 로그인 입구 — 로그인 정책 변경)**, **보안 설정(필터 체인에 처리 필터 추가·`LoginSessionMutexFilter` 대상 추가)**, **동시성(회원 행 잠금·세션 뮤텍스 재사용)**, **외부 계약(새 공개 POST `/admin/login/passkey`)**
- 스키마 변경: **없음**(PR1의 `member_passkey`·`member_passkey_user` 재사용). 새 의존성·설정 키: **없음**(`cms.passkey.*` 재사용 — 꺼짐이면 이 기능도 꺼짐). 설정 값 추가: `cms.rate-limit.rules`에 규칙 1개(결정 6, 기존 키 구조).
- URL 인가 규칙(`authorizeHttpRequests`) 변경: **없음**(결정 6). 로그인 정책 변경이라 승인은 필요하다.

## 1. Context

#280 목표: 관리자 로그인 화면에 "패스키로 로그인"을 넣어 **패스키(사용자 확인 필수) 하나로 비밀번호·2단계를 생략하고 완전 권한**을 얻는다(GitHub 방식).
새 로그인 입구라 폼 로그인에 걸린 계약 — 성공 처리기 재확인, 실패 잠금·자동 해제, 세션 ID 교체·레지스트리 등록, 같은 세션 로그인 직렬화(뮤텍스), 방문 기록 — 을 그대로 걸어야 한다.

### 정찰에서 확인한 사실

| # | 사실 | 출처 |
|---|---|---|
| S1 | `Webauthn4JRelyingPartyOperations.createCredentialRequestOptions`: 인증되지 않은 요청이면 `allowCredentials`가 빈 목록(discoverable — 브라우저가 기기의 상주 패스키를 고른다). `userVerification`은 `PREFERRED` 고정, 바꾸는 수단은 연산 객체 전역의 `setCustomizeRequestOptions`뿐(PR1 2단계 옵션까지 바뀐다). | 7.0.7 javap(`createCredentialRequestOptions`·`findCredentialRecords`) |
| S2 | `authenticate`는 요청 옵션의 `getUserVerification()`이 `REQUIRED`일 때만 webauthn4j에 UV 필수를 넘긴다. `allowCredentials`가 비면 허용 목록 검사가 없다. rawId로 저장소의 자격을 찾고, 없으면 `IllegalArgumentException`. 성공 시 서명 횟수·`lastUsed`를 `save`한다. | 7.0.7 javap(`authenticate` 186~302) |
| S3 | `SessionManagementConfigurer.init`이 `SecurityContextRepository`·`SessionAuthenticationStrategy`를 `HttpSecurity` 공유 객체로 등록하고, `AbstractAuthenticationFilterConfigurer.configure`가 그것을 처리 필터에 넣는다 — 직접 만든 `AbstractAuthenticationProcessingFilter`도 같은 공유 객체를 받으면 폼 로그인과 같은 세션 전략(동시 세션 제어·세션 ID 교체·레지스트리 등록)을 쓴다. | config 7.0.7 javap(`SessionManagementConfigurer.init` 1~204, `AbstractAuthenticationFilterConfigurer.configure` 107~199) |
| S4 | 성공 처리기 `VisitLoggingAuthenticationSuccessHandler.verifyFreshMemberState`는 "2단계 사용 회원 ⇒ `TOTP_PENDING` 토큰"이 아니면 거부한다 — 패스키 완전 토큰은 지금은 언제나 거부된다. | 코드 |
| S5 | 잠금 자동 해제(`unlockIfLockExpired`)와 상태 검사(`LOCKED`·`DISABLED`·`DELETED`·`PASSWORD_EXPIRED` → 예외)는 `CustomUserDetailsService.loadUserByUsername` 한 곳이다. | 코드 |
| S6 | 폼 로그인 실패 처리기는 `BadCredentialsException`이고 `username` 파라미터가 있을 때만 실패 횟수를 센다. | `LockingAuthenticationFailureHandler` |
| S7 | PR1 불변식: 유효한 패스키 = `member_passkey.created_at >= member_totp.enabled_at`(TOTP 없으면 0개). 등록 옵션은 `residentKey=REQUIRED`(상주 키). | PR1 결정 1, S5 |
| S8 | 로그인 화면(`AdminMainController.login`·`login-error`)은 CSRF 숨은 필드 때문에 이미 세션을 만든다. `POST /admin/login`에는 레이트리밋 규칙이 없다. | 코드, `application.yml` `cms.rate-limit.rules` |
| S10 | 실제 해결 버전: `./gradlew dependencies --configuration runtimeClasspath` → `com.webauthn4j:webauthn4j-core:0.31.6.RELEASE`(UV·서명 최종 검증 담당). S1·S2 javap 대상: `~/.gradle/caches/modules-2/.../spring-security-webauthn/7.0.7/.../spring-security-webauthn-7.0.7.jar`, S3: 같은 캐시의 `spring-security-config-7.0.7.jar`(`javap -c -p`). 소스 대조: `spring-security` 태그 7.0.7 `Webauthn4JRelyingPartyOperations.java` L349-L386(리뷰어 확인). | 실측 2026-11-19 |
| S11 | `AbstractAuthenticationProcessingFilter`(7.0.7)는 `attemptAuthentication`에서 나온 **`AuthenticationException`만** 실패 처리기로 넘긴다(그 밖의 런타임 예외는 그대로 전파 → 500). 실패 시 스레드 컨텍스트만 지우고 세션에 저장된 컨텍스트는 건드리지 않는다 — 폼 로그인 실패도 같다. | 7.0.7 소스 L232-L273(리뷰 1-3·1-4) |
| S12 | MariaDB InnoDB `REPEATABLE READ`의 일관된 읽기 스냅샷은 트랜잭션의 **첫 일반(비잠금) 읽기** 때 만들어진다 — 그 뒤 `FOR UPDATE`로 행을 잠가도 다른 테이블의 일반 읽기는 옛 스냅샷이다. 기존 `TotpLoginService.verify`가 회원 행 잠금을 첫 작업으로 두는 이유. | MariaDB 문서 `SET TRANSACTION`(리뷰 1-1) |
| S9 | `PasskeyBrowserTest`는 Chromium CDP 가상 인증기(상주 키·UV)로 실제 서명·검증을 목 없이 돌린다(`DEFINED_PORT`). | 시험 코드 |

## 2. 핵심 설계 결정

### 결정 1 — 입구: 직접 만든 처리 필터 `PasskeyLoginFilter`(폼 로그인 필터와 같은 공유 세션 전략·같은 성공 처리기)
- 선택지: (a) `http.webAuthn()` / (b) 컨트롤러가 검증 뒤 세션 ID 교체·레지스트리 등록·컨텍스트 저장을 손으로 / (c) `AbstractAuthenticationProcessingFilter` 하위 필터를 `HttpSecurity` 공유 객체(S3)로 구성.
- **(c) 채택**. (a) 기각: PR1 결정 2와 같다 — 자체 프로바이더·필터가 성공 처리기 재확인·잠금 계약 밖이다. (b) 기각: Spring의 세션 전략(동시 세션 제어 → ID 교체 → 레지스트리 등록 순서)을 복제해야 하고 어긋나면 레지스트리 밖 세션이 생긴다. (c)는 폼 로그인과 **같은 전략 객체·같은 컨텍스트 저장소·같은 성공 처리기**를 쓴다.
- 구성: `PasskeyLoginConfigurer extends AbstractHttpConfigurer`의 `configure(http)`가 `http.getSharedObject(SessionAuthenticationStrategy.class)`·`SecurityContextRepository.class`를 필터에 넣고 `addFilterAfter(filter, UsernamePasswordAuthenticationFilter.class)`로 등록한다(`LoginSessionMutexFilter`가 그 앞이라 뮤텍스 안). `SecurityConfig`에서 `http.with(...)`. 성공 처리기는 같은 `VisitLoggingAuthenticationSuccessHandler` 빈, 실패 처리기는 결정 5.
- 필터의 `AuthenticationManager`는 **빈이 아닌 람다**(`PasskeyLoginService`를 부르는 것)다 — `AuthenticationProvider` 빈을 만들지 않는다(빈이 둘이면 2단계가 사라진다, `com.cms.config` CLAUDE.md). 폼 로그인의 `ProviderManager`는 그대로다.
- 매처: `POST /admin/login/passkey` **그리고 기능 켜짐**(`PasskeyRelyingParty.isEnabled()`). 꺼짐이면 필터가 지나쳐 기존 인가 규칙(`/admin/**` ADMIN 캐치올)이 처리한다 — 익명 302 로그인.

### 결정 2 — 챌린지: 로그인 화면 GET이 발급, 세션 보관 1회용, UV `REQUIRED`
- 선택지: (a) 화면 GET에서 옵션 발급·세션 보관(서버 렌더링, PR1과 같은 방식) / (b) 버튼 클릭 시 옵션 JSON API(`POST /admin/login/passkey/options`) / (c) 조건부 UI(자동 완성).
- **(a) 채택**. 이유: 새 URL·JSON API가 없고, 로그인 화면은 이미 세션을 만든다(S8). PR1 코드 화면과 같은 구조라 JS(`passkey-login.js`)를 그대로 쓴다. (b)는 공개 JSON API가 하나 더 생긴다. (c)는 이슈의 "필요하면" — 지금은 범위 밖(§7).
- `PasskeyLoginState{loginId(무작위 UUID), options, issuedAt}`를 세션에 둔다(GET마다 대체, 5분 — PR1 `PasskeyAssertionState`와 같은 TTL). `loginId`는 패스키 폼 숨은 필드에도 싣는다.
- 옵션은 **연산 객체를 거치지 않고 직접 만든다**(S1 — 전역 커스터마이저를 바꾸면 PR1 2단계 옵션도 바뀐다): `challenge=Bytes.random()`, `rpId=cms.passkey.rp-id`, `timeout=5분`, `userVerification=REQUIRED`, `allowCredentials=[]`. `PasskeyRelyingParty.discoverableRequestOptions()`로 둔다.
- UV `REQUIRED` 이유: 비밀번호를 대신하므로 "기기 소유 + 생체/PIN" 두 요소가 있어야 한다. 서버 검증은 옵션 값을 보고 강제된다(S2).
- 기능 꺼짐이면 상태를 만들지 않고 버튼을 그리지 않는다. 화면 렌더링 중 옵션 생성 실패는 로그만 남기고 버튼 없이 그린다(비밀번호 로그인은 그대로).

### 결정 3 — 검증: 회원 식별(읽기 트랜잭션) → 상태 로딩(기존 트랜잭션) → 검증(잠금이 첫 작업인 트랜잭션)
필터 순서: 세션의 `PasskeyLoginState`를 **꺼내며 지운다** → 없음·`loginId` 불일치·5분 초과면 `PasskeyLoginExpiredException`(검증·DB 접근 없음) → 아래 세 단계. **트랜잭션을 셋으로 나누는 이유는 S12**다 — 자격 조회를 잠금과 같은 트랜잭션에서 먼저 하면 그 스냅샷 때문에 잠금 뒤에도 옛 `member_totp`를 읽고, `resetFailures()`의 전체 컬럼 UPDATE가 다른 요청이 올린 `last_used_step`을 되돌릴 수 있다(리뷰 1-1).
1. **식별**(`PasskeyLoginService.identify`, `@Transactional(readOnly = true)`, 잠금 없음): 자격 JSON 파싱(`PasskeyJson.readAssertion`) → **응답 `userHandle` 필수**(discoverable 로그인 — WebAuthn L3 §7.2 단계 6 "사용자를 의식 전에 식별하지 않았으면 `response.userHandle`이 있어야 한다", 출처: W3C REC 2026-08-25 판, 리뷰 1-6 확인) → rawId로 `member_passkey` 행의 `member_id` → 그 회원 아이디(스칼라). 하나라도 없으면 `NOT_FOUND`. 파싱된 자격 객체와 회원 아이디를 돌려준다.
2. **상태 로딩**: `CustomUserDetailsService.loadUserByUsername(userId)`(자기 트랜잭션, 커밋) — 만료 잠금 자동 해제 + 상태 검사. `LOCKED`·`DISABLED`·`PASSWORD_EXPIRED` 등은 그 `AuthenticationException` 그대로 실패(S5, 폼 로그인과 같은 판정·같은 커밋 단위).
3. **검증**(`PasskeyLoginService.verify`, `@Transactional`, **첫 DB 작업이 `findByIdForUpdate`**): principal과 행이 같은 상태인지(ACTIVE·역할·해시 — PR1 `matchesLogin`과 같은 판정) → `member_totp` 행 존재(그 `enabledAt`을 기억) → 핸들 행 존재 → **응답 `userHandle` = 그 회원 핸들** → 그 자격 행이 이 회원의 **유효한** 것인지(S7 조건, 잠금 뒤 읽기) → `relyingParty.authenticate(state.options, credential)`(UV `REQUIRED` 옵션 — S2) → 돌려받은 user entity ID = 그 회원 핸들 → 성공: `member_totp.resetFailures()` → flush. 비밀번호 쪽 실패 횟수 리셋은 성공 처리기의 `resetFailuresAndCheckActive`가 한다(폼 로그인과 같은 자리).
- **트랜잭션 경계가 실제로 생기는 호출 구조(리뷰 2-1)**: 세 단계를 묶는 진입 메서드를 서비스 안에 두지 않는다(같은 클래스 내부 호출은 프록시를 거치지 않아 `@Transactional`이 무시된다 — Spring Framework 7.0.9 `annotations.adoc` "self-invocation", 출처: 리뷰어 확인). 조정자는 **트랜잭션이 없는 필터**(`PasskeyLoginFilter`, 빈 아님)이고, 필터가 `PasskeyLoginService` 빈의 `identify`·`verify`와 `CustomUserDetailsService` 빈의 `loadUserByUsername`을 **각각 프록시를 거쳐** 부른다. 시험은 바깥 트랜잭션 없이 실제 엔드포인트로 돌린다(F2 스냅샷 경합 시험이 이 구조를 고정 — 합치면 실패).
- 결과는 **예외가 아니라 결과값**(`SUCCESS(totpEnabledAt)`·`REJECTED`)으로 돌려준다 — 라이브러리·파싱 예외는 서비스 안에서 잡아 `REJECTED`(INFO 로그, 원인 클래스만). 트랜잭션은 거부여도 쓰기가 없어 커밋된다.
- **예외 경계(S11, 리뷰 1-3)**: 필터의 `attemptAuthentication`이 결과를 `AuthenticationException`으로 바꾼다 — 만료 → `PasskeyLoginExpiredException`, `NOT_FOUND`·`REJECTED` → `BadCredentialsException`, 2단계의 상태 예외는 그대로, **그 밖의 런타임 예외(DB·커밋 실패)는 `AuthenticationServiceException`으로 감싸고 ERROR 로그** → 모두 실패 처리기(결정 5)로 간다. 500이 나가지 않는다.
- 잠금 계약: 어댑터 `save`(서명 횟수 갱신)가 회원 행 잠금 트랜잭션 안에서 불린다 — PR1 결정 3 계약 유지. 잠금 순서는 세션 뮤텍스 → 회원 행(결정 4).
- 반환: `PasskeyLoginAuthenticationToken`(결정 4 — principal `CustomUserDetails`, 권한 = 원래 역할 하나, **검증 때 읽은 `totpEnabledAt`**).

### 결정 4 — 성공 처리기: 패스키 로그인 토큰을 "완전 권한 + 2단계 사용 중"으로 인정
- 토큰 종류 대조(`verifyFreshMemberState`)에 한 갈래를 더한다: 토큰이 `PasskeyLoginAuthenticationToken`이면 **기대 조건은 스냅샷의 TOTP 등록 시각 = 토큰의 `totpEnabledAt`**(패스키 ⇒ TOTP 불변식 + **같은 등록 세대** — 로그인 진행 중 TOTP 해제가 커밋됐거나, 해제 → 재등록까지 끝나 다시 "사용 중"이 된 경우 모두 거부, 리뷰 1-2)이고 실패 횟수 리셋 경로(`resetFailuresAndCheckActive`)를 쓴다. 이를 위해 `MemberSnapshot`에 `totpEnabledAt`(없으면 null)을 더한다(`totpEnabled()`는 `totpEnabledAt != null`로 같은 값 — 기존 판정 무변경). 역할·해시 대조는 기존과 같다. 그 밖의 토큰 판정은 **한 줄도 바꾸지 않는다** — 2단계 사용 회원의 일반 완전 토큰·2단계 없는 ADMIN 완전 토큰은 여전히 거부된다.
- 성공 시 세션에 남아 있을 수 있는 중간 상태 표지(`TotpPendingState`·`PasskeyAssertionState`·`PasskeyLoginState`)를 지운다(같은 세션에서 코드 화면을 거치다 로그인 화면으로 돌아온 경우).
- 방문 기록·알림 정리는 기존 `recordCompletedLogin` → `/admin`.
- **ADMIN 2단계 필수(#256) 해석**: 패스키 로그인은 UV 필수라 그 자체로 두 요소(소유 + 생체/PIN)이고, 패스키는 TOTP를 켠 회원만 가진다(PR1 불변식) — ADMIN이 패스키로 2단계 없이 들어오는 경로는 생기지 않는다. 그래서 필수 판정(`TotpAuthorities.isRequired`)·프로바이더는 바꾸지 않는다. **(정책 승인 대상)**
- 같은 세션의 로그인 직렬화: `LoginSessionMutexFilter.LOGIN_PROCESSING`에 `POST /admin/login/passkey`를 더한다(폼 로그인·코드 제출과 같은 이유 — 두 탭 동시 로그인의 레지스트리 불일치).

### 결정 5 — 실패: 횟수를 세지 않는다, 안내는 하나로
- 선택지: (a) 자격 ID로 찾은 회원의 `failed_login_count`를 올린다 / (b) `member_totp.failed_attempts`를 올린다 / (c) 세지 않는다.
- **(c) 채택**. 이유: 대입 제한은 **추측할 수 있는 비밀**(비밀번호·6자리 코드)을 위한 것이다. 어설션은 개인키 서명이라 추측으로 맞힐 수 없고, 세면 자격 ID만 아는 사람이 피해자 계정을 잠그는 수단(서비스 거부)만 생긴다. PR1의 2단계 패스키 실패를 세는 것은 이미 비밀번호를 통과한 세션의 2단계 슬롯이 코드와 공유하는 카운터라서다 — 이 입구는 그 슬롯이 아니다. 잠긴·비활성·변경 강제 계정은 결정 3-4가 막고, 만료된 잠금은 폼 로그인과 똑같이 자동 해제된다.
- 실패 처리기(빈 아님): `PasskeyLoginExpiredException` → `/admin/login?passkeyExpired`("패스키 인증 시간이 지났습니다. 다시 시도해 주세요."), 그 밖 전부(검증 실패·없는 자격·잠김·비활성·변경 강제·TOTP 없음·형식 오류) → `/admin/login?passkeyError`("패스키로 로그인하지 못했습니다. 아이디와 비밀번호로 로그인해 주세요."). 상태별로 문구를 나누지 않는다(계정 상태 노출 없음 — 폼 로그인의 단일 문구와 같은 원칙).
- **이미 인증된 세션에서의 시도(리뷰 1-4)**: 로그인 화면은 인증 여부와 무관하게 열리고 필터 매처도 익명을 요구하지 않는다(폼 로그인과 같다). 정책은 **폼 로그인 실패와 동일** — 실패는 세션을 무효화하지 않고 세션에 저장된 기존 인증(완전 인증·`TOTP_PENDING`·`TOTP_ENROLLMENT_REQUIRED`)을 그대로 둔다(S11, 같은 기반 필터). 권한을 올리지 않으므로 안전하고, 폼 로그인과 달라 생기는 혼란이 없다. 성공은 세션 전략이 세션 ID를 바꾸고 새 컨텍스트로 대체하며 중간 상태 표지를 지운다(결정 4). 세 상태 각각의 실패·성공을 시험으로 고정한다.
- 서버 로그: 실패 원인 클래스만 INFO(PR1 `passkeyMatches`와 같은 수준), 자격 원문·핸들은 남기지 않는다.

### 결정 6 — URL 인가 규칙 무변경, IP 레이트리밋 규칙 1개 추가
- 처리 필터는 인가 필터 앞에서 응답을 끝낸다(폼 로그인의 `POST /admin/login`과 같다). 꺼짐이면 `/admin/**` ADMIN 캐치올(익명 302, MANAGER 403, ADMIN 핸들러 없음 404). `authorizeHttpRequests`에 규칙을 더하지 않는다 — `com.cms.config` CLAUDE.md 표에는 "필터 처리 경로"로 기록한다.
- **레이트리밋 규칙 `admin-passkey-login`(POST `/admin/login/passkey`, IP당 `capacity: 30`·`refill-period-seconds: 60` = 초기 버스트 30회 + 평균 2초에 1회 보충, 엄격한 구간 상한이 아니다)을 더한다**(리뷰 1-6). 이유: 자격 ID·핸들을 아는 요청자는 유효한 서명 없이도 회원 행 잠금(결정 3-3)까지 올 수 있고, 챌린지는 공개 GET으로 반복 발급되므로 1회용만으로는 빈도가 제한되지 않는다. 기존 장치(`com.cms.config.ratelimit`, `CsrfFilter` 다음 — 처리 필터보다 앞)에 설정 한 줄이라 비용이 작다. 정상 사용(사람이 생체 확인 후 제출)은 분당 몇 회를 넘지 않는다. 기존 `POST /admin/login`에 규칙이 없는 것은 그대로 둔다(범위 밖). 잠금을 쥐는 시간은 서명 검증 동안(밀리초)이라 부하 시험은 하지 않는다.
- CSRF: 일반 폼 POST라 `CsrfFilter`가 먼저 검사한다(숨은 `_csrf`).

### 결정 7 — 화면
- `login.html`: 비밀번호 폼 아래 구분선("또는") + "패스키로 로그인" 버튼과 **별도 폼**(`action=/admin/login/passkey`, `data-options`, 숨은 `loginId`·`passkeyCredential`·CSRF). 기능 꺼짐·옵션 없음이면 블록을 그리지 않는다. `passkey-codec.js`·`passkey-login.js`를 그대로 쓴다(요소 ID 동일 — 주석만 두 화면용으로 고친다). JS 미지원이면 블록을 숨긴다. 오류 문구는 기존 `error` 알림 영역(`passkeyError`·`passkeyExpired`).
- 아이디·비밀번호 입력의 `autocomplete="username webauthn"`(조건부 UI)은 넣지 않는다(§7).

### 결정 8 — 서명 횟수 역행 거부(복제 의심) — 두 패스키 경로 공통 (2026-11-19 사용자 결정, 리뷰 4-1)
- 사실: 7.0.7 `authenticate`는 등록 당시 attestation object로 검증용 자격을 만들어 **등록 때 횟수와만** 비교한다(PR1 S8, webauthn4j 0.31.6 `CoreCredentialRecordImpl` 생성자 — 리뷰어 확인). 저장된 최신 횟수 100보다 작은 1도 통과하고, 저장값이 1로 되돌아간다.
- 선택지: (a) 역행이면 거부 / (b) PR1처럼 리스크 수용 / (c) 별도 이슈. **(a) 채택**(사용자 결정) — 비밀번호 없이 완전 권한을 주는 경로로 넓어지므로 PR1의 수용 근거(R1)가 약해졌다.
- 구현: 어댑터 `PasskeyCredentialRepository.save`의 **기존 행 갱신 갈래**(라이브러리가 검증 성공 뒤 부르는 곳 — 두 값을 모두 안다, 회원 행 잠금 아래)에서 `새 값 ≠ 0 또는 저장 값 ≠ 0`이고 `새 값 ≤ 저장 값`이면 `PasskeySignatureCountException`을 던지고 갱신하지 않는다(WebAuthn L3 §7.2 단계 "signCount" 규칙 — 둘 다 0이면 횟수를 지원하지 않는 인증기라 통과, 출처: 사양). 예외는 `authenticate` 밖으로 나가 각 경로의 거부 처리를 탄다 — **두 경로 모두 실패 횟수를 세지 않는다**(리뷰 5-1): 정상 어설션 두 개의 도착 순서가 뒤바뀌어도 역행이 되므로(WebAuthn L3 §6.1.1 — 출처: 사양), 세면 정상 사용이 잠금·세션 만료로 이어진다. 패스키 로그인은 `REJECTED`(→ `?passkeyError`), **라이브러리가 어댑터보다 먼저 거부하는 역행**(등록 당시 횟수 이하 — webauthn4j 0.31.6 `AuthenticationDataVerifier`의 횟수 검사 → 기본 처리기가 `com.webauthn4j.verifier.exception.MaliciousCounterValueException`, jar 클래스 존재 확인·리뷰 6-1)도 **같은 분류**다. **PR1 2단계 경로는 `passkeyMatches`가 두 예외(`PasskeySignatureCountException`·`MaliciousCounterValueException`)를 따로 잡아 실패 기록 없이 `?passkeyError`**(새 상태 `PASSKEY_COUNTER_REJECTED` — 판정·카운트 없음, 일반 검증 실패는 기존대로 `WRONG_PASSKEY`·+1). 로그는 WARN(회원 ID·자격 행 ID만).
- 두 값이 모두 0인 자격(횟수를 구현하지 않는 인증기)에는 영향이 없다. 어떤 제공자의 패스키가 늘 0인지는 확인하지 않았다(미확인 — 효과 범위를 단정하지 않는다).

### 설계 제약
- `AuthenticationProvider` 빈 추가 금지, `http.webAuthn()` 금지, 기존 토큰 종류 판정 무변경.
- 시각은 KST `Clock`, 대소문자 변환은 `Locale.ROOT`, 잠금 순서는 세션 뮤텍스 → 회원 행.
- 엔티티 변경 없음.

## 3. 성공 기준
1. TOTP와 패스키를 가진 MANAGER·ADMIN이 로그인 화면의 [패스키로 로그인] 한 번으로(아이디·비밀번호·코드 입력 없이) `/admin`에 원래 역할로 들어간다 — 가상 인증기 브라우저 시험(실제 서명).
2. 성공 시 세션 ID가 바뀌고 레지스트리에 그 회원으로 등록되며 방문 기록 1건, `failed_login_count`·`member_totp.failed_attempts`가 0이 된다.
3. 잠김(만료 전)·비활성·비밀번호 변경 강제·TOTP 해제(무효 패스키) 회원은 들어가지 못하고 실패 횟수가 바뀌지 않는다. 만료된 잠금은 자동 해제되고 로그인된다.
4. 기능 꺼짐에서 버튼이 없고 `POST /admin/login/passkey`는 인증되지 않는다(302 로그인). 기존 폼 로그인·2단계·PR1 패스키 시험이 전부 통과한다.
5. `AuthenticationProvider` 빈 1개·폼 로그인 `ProviderManager` 무변경(`PasskeyModuleWiringIntegrationTest` 통과).

## 4. 실패 모드와 고정 시험 (최대 5)
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 새 입구가 세션 계약을 비켜 간다(세션 ID 그대로·레지스트리 미등록·방문 기록 없음·뮤텍스 밖) | `PasskeyLoginIntegrationTest`(실 DB, `PasskeyRelyingParty`만 목 — PR1 `TotpPasskeyLoginIntegrationTest` 방식): 성공 전후 세션 ID 다름, `SessionRegistry`의 그 세션 principal = 회원, `visit_log` 1행. `LoginSessionMutexFilterTest`: `POST /admin/login/passkey` 매칭. 변이: 공유 세션 전략 주입 제거 → 실패 |
| F2 | 로그인할 수 없는 회원이 패스키로 들어온다(잠김·비활성·변경 강제·TOTP 해제/재등록 뒤 옛 패스키·다른 회원 자격) / 검증이 옛 스냅샷으로 최신 TOTP 상태를 되돌린다 / 라이브러리 예외가 500이 된다 | 같은 시험: 각 상태 → `?passkeyError`·인증 없음·`failed_login_count`·`failed_attempts` 불변, 만료 잠금 → 성공. 응답 `userHandle` 없음·다른 회원 핸들 → 거부(검증기 호출 없음). TOTP 행을 더 늦은 `enabled_at`으로 다시 넣으면 거부. 형식 오류 JSON·필수 필드 누락·검증기 예외·검증 서비스의 DB 예외(스파이) → 모두 302 `?passkeyError`(500 아님). **스냅샷 경합**: 식별 단계 뒤·검증 잠금 전에(스파이로 멈춤) 다른 트랜잭션이 `member_totp.last_used_step`을 올리고 커밋 → 패스키 성공 뒤 그 값이 유지된다(`failed_attempts`>0으로 시작해 전체 UPDATE가 일어나게, 리뷰 1-1). 변이: 식별과 검증을 한 트랜잭션으로 합치면 실패 |
| F3 | 챌린지 재사용·만료·다른 탭 교체로 통과하거나, 그 실패가 검증까지 간다 | 같은 시험: 같은 상태로 두 번째 POST → `?passkeyExpired`, 5분 초과, GET 두 번 뒤 첫 `loginId` → `?passkeyExpired`, 세 경우 모두 `authenticate` 호출 0회 |
| F4 | 사용자 확인(UV)이 강제되지 않는다 | 같은 시험: `authenticate`에 넘어간 옵션(캡처)의 `userVerification == REQUIRED`·`allowCredentials` 빈 목록·세션에 둔 옵션과 같은 챌린지. **실제 서명(리뷰 1-5)**: `PasskeyBrowserTest`에 UV 미지원 가상 인증기(`hasUserVerification=false`)로 등록한 패스키로, 로그인 화면의 `data-options`를 브라우저에서 `userVerification: "preferred"`로 바꿔 서명은 유효하고 UV 비트가 없는 어설션을 제출 → 서버가 `?passkeyError`(세션에 보관한 옵션은 `REQUIRED`). 변이: 서버 옵션을 `PREFERRED`로 바꾸면 이 시험이 실패(로그인됨) |
| F5 | 성공 처리기 변경이 토큰 판정을 넓힌다/좁힌다 — 패스키 토큰이 거부되거나, 반대로 2단계 사용 회원의 일반 완전 토큰·TOTP 해제(또는 해제 → 재등록) 뒤의 패스키 토큰이 통과한다 / 이미 인증된 세션에서의 시도가 예상과 다르게 끝난다 | `VisitLoggingAuthenticationSuccessHandler` 시험(기존 시험 클래스에 추가): 패스키 토큰 + 같은 `totpEnabledAt` → 통과·리셋, TOTP 없음 → 거부·세션 무효, **다른(더 늦은) `totpEnabledAt`** → 거부, 일반 완전 토큰 + TOTP 사용 중 → 여전히 거부. `PasskeyLoginIntegrationTest`: 완전 인증·`TOTP_PENDING` 세션에서 실패 → 기존 인증 유지(다음 요청에서 같은 권한), 성공 → 세션 ID 교체·새 회원 권한·중간 표지 없음. 기존 `TotpPendingAuthorizationIntegrationTest`·`PasskeyModuleWiringIntegrationTest` 통과 |

추가: **서명 횟수 역행(결정 8)** — 어댑터 단위 시험(저장 100·새 1 → 예외·행 불변, 같은 값 재제출 → 예외, 저장 0·새 0 → 통과, 저장 5·새 6 → 갱신, 저장 5·새 0 → 예외). 실제 검증기: `PasskeyBrowserTest`에서 가상 인증기로 등록 뒤 SQL로 저장 횟수를 크게 올려 두고 패스키 로그인 → `?passkeyError`·DB 횟수 그대로(Chromium 가상 인증기는 어설션마다 횟수를 올린다 — 구현 첫 단계에서 실측, 아니면 이 시험을 통합 시험의 어댑터 경유로 대체). PR1 2단계 경로: `TotpPasskeyLoginIntegrationTest`에 역행(검증기가 `PasskeySignatureCountException` 또는 `MaliciousCounterValueException`) → `?passkeyError`·`failed_attempts` **불변**. 실제 검증기 쪽은 브라우저 시험에서 등록 당시 횟수가 0이 아닌 자격(가상 인증기 횟수 실측 결과에 따라 — 등록 뒤 한 번 로그인해 횟수를 올린 뒤 SQL로 `attestation_object`가 아닌 저장 횟수만 조작하는 방식은 어댑터 경로, 등록 당시 횟수 이하 제출은 라이브러리 경로)으로 두 경로 각각 실패 횟수·저장 횟수 불변을 확인하고, 재현이 안 되면 그 사실을 기록한다(도착 순서 역전 모사 — 리뷰 5-1), 일반 검증 실패는 기존대로 +1. 패스키 로그인도 역행 → `?passkeyError`·카운트 불변. **레이트리밋 작동(리뷰 2-2)** — 시험 JVM은 `CMS_RATE_LIMIT_ENABLED=false`(build.gradle)라 규칙이 꺼진 채 돈다. `@TestPropertySource(properties = "cms.rate-limit.enabled=true")`(기존 `RateLimitFilterTest` 방식)로 켜고, **`Ticker` 빈(`RateLimitFilterConfig.ticker()`)을 시험이 움직이는 가짜 시계로 바꿔**(리뷰 3-1 — 버킷은 연속 리필이라 실제 시계로는 느린 CI에서 31번째가 통과할 수 있다) 시간을 고정한 채 실제 보안 체인에서 같은 IP로 유효한 CSRF `POST /admin/login/passkey` 30번 허용 → 31번째 429·그 요청에서 식별·검증 서비스(스파이) 미호출 → 시계를 2초 전진하면 다음 1건 허용. 같은 IP의 `GET /admin/login`·`POST /admin/login`은 영향 없음. 기능 꺼짐(버튼 없음·POST 302), 화면 렌더링(켜짐: `data-options`·`loginId` / 꺼짐: 블록 없음), 브라우저 시험 `PasskeyBrowserTest`에 "등록 → 로그아웃 → 로그인 화면 [패스키로 로그인] → `/admin`" 1건(실제 서명·UV).

## 5. 작업 단계
0. 어댑터 서명 횟수 역행 거부(결정 8) — 시험부터.
1. `PasskeyRelyingParty.discoverableRequestOptions()` + `PasskeyLoginState` + `PasskeyLoginAuthenticationToken`.
2. `PasskeyLoginService.identify`·`verify`(결정 3, 진입 메서드 없음) — 실패 시험부터.
3. 성공 처리기 갈래(결정 4) — 시험부터.
4. `PasskeyLoginFilter`·`PasskeyLoginConfigurer`·실패 처리기, `SecurityConfig` 연결, `LoginSessionMutexFilter` 대상 추가, 레이트리밋 규칙(`application.yml`).
5. `AdminMainController` 로그인 화면 옵션·오류 문구, `login.html`, JS 주석.
6. 브라우저 시험·실기 검증(dev, CDP 가상 인증기).
7. 문서: `com.cms.config`·`com.cms.admin.member` `CLAUDE.md`, 루트 `CLAUDE.md` 패스키 줄, `docs/development-workflow.md` 위험 표(새 클래스), 계획 인덱스.

## 6. 리스크
- R1 패스키 개별 삭제와 로그인 경합: 삭제가 성공 처리기 재확인 뒤에 커밋되면 그 로그인은 살아남는다(PR1의 삭제도 세션을 끊지 않는다). TOTP 해제는 결정 4가 잡는다. 수용.
- R2 실패를 세지 않으므로 서명 검증 반복을 막는 장치는 세션 챌린지 1회용과 IP 레이트리밋(결정 6)이다. IP를 돌리는 공격자는 막지 못한다(기존 레이트리밋의 수용 한계와 같다). 회원 행 잠금은 서명 검증 동안만 쥔다.
- R3 세션 쿠키 탈취 위험은 폼 로그인과 같다. 운영 실사용은 #238(도메인·TLS) 이후 — 기본 꺼짐.
- R4 동기화 패스키(iCloud 등)는 다른 기기로 복제된다 — 그 계정 보안에 기댄다. 수용(업계 일반).
- R5 Chrome 가상 인증기와 실기기 차이 — #281.

## 7. 범위 밖
- 조건부 UI(자동 완성), 아이디 입력 후 그 회원 자격만 허용하는 방식, 패스키만 가진(TOTP 없는) 회원, 패스키 로그인 전용 레이트리밋, 실패 감사 기록.
  - 조건부 UI는 이슈 #285에서 넣었다(2026-11-20, [`PLAN-passkey-conditional-ui.md`](PLAN-passkey-conditional-ui.md) — 서버 무변경, 이 계획의 챌린지를 버튼과 나눠 쓴다).

## 개정 이력
- v1 (2026-11-19): 최초 작성.
- v2 변경 (1라운드 codex): ① 검증을 식별(읽기)·상태 로딩·검증(잠금이 첫 작업) 세 트랜잭션으로 나눔 + 스냅샷 경합 시험(S12) ② 토큰에 `totpEnabledAt`, 성공 처리기가 등록 세대까지 대조(`MemberSnapshot.totpEnabledAt`) ③ 결과값 → 필터에서 `AuthenticationException` 변환, 예상 밖 예외는 `AuthenticationServiceException`(S11) ④ 인증된 세션에서의 시도는 폼 로그인 실패와 동일 정책(기존 인증 유지)으로 명시·시험 ⑤ UV 없는 실제 서명 거부 브라우저 시험 ⑥ IP 레이트리밋 규칙 `admin-passkey-login` 추가, 근거 기록(S10·S11·S12, 사양 판본). 기각: 병렬 부하 시험 — 잠금 보유가 서명 검증 동안(밀리초)이고 레이트리밋이 빈도를 막아 과함.
- v3 변경 (2라운드 codex): ① 세 단계를 묶는 서비스 진입 메서드를 없애고 비트랜잭션 필터가 각 빈을 프록시로 호출(자기 호출로 `@Transactional`이 무시되는 문제) ② 레이트리밋을 켠 실제 체인 시험(429·서비스 미호출·다른 로그인 경로 무영향).
- v4 변경 (3라운드 codex): 레이트리밋 시험에 가짜 `Ticker` 빈(고정 시각 → 31번째 429, 2초 전진 → 1건 허용), 한도 표현을 "버스트 30 + 2초당 1회 보충"으로.
- v5 변경 (4라운드 codex + 사용자 결정): 결정 8 — 어댑터에서 서명 횟수 역행이면 거부(두 패스키 경로 공통, PR1 2단계에서는 오답으로 셈), 시험 3종. PR1 리스크 R1(수용)을 이 결정으로 대체.
- v6 변경 (5라운드 codex): 서명 횟수 역행은 두 경로 모두 **실패 횟수에 넣지 않음**(정상 어설션 도착 순서 역전이 잠금으로 이어지지 않게 — PR1 경로에 새 상태 `PASSKEY_COUNTER_REJECTED`), 역순 모사 시험. "동기화 패스키는 늘 0" 단정 삭제 → "두 값 모두 0이면 영향 없음, 제공자별 동작 미확인".
- v7 변경 (6라운드 codex): 라이브러리가 먼저 던지는 `MaliciousCounterValueException`(webauthn4j 0.31.6)도 횟수 역행으로 분류해 실패 횟수에 넣지 않음, 등록 당시 횟수가 0이 아닌 자격의 실제 검증기 시험.

## 구현 결과 (2026-11-19)

### Context
이슈 #280 — 로그인 화면 [패스키로 로그인]으로 비밀번호·2단계 없이 완전 권한(GitHub 방식). PR1(#282)의 패스키(2단계 대체 수단) 위에 새 로그인 입구를 더했다.

### 핵심 확정 사항
- 결정 1~8을 그대로 구현했다. 계획과 다른 점은 "이슈" 3(성공 처리기 거부 시 안내 경로)뿐이다.

### 구현 파일
- 로그인 경로(`config/auth`): `PasskeyLoginFilter`(처리 필터·만료 예외)·`PasskeyLoginConfigurer`(공유 세션 전략·실패 처리기)·`PasskeyLoginService`(`start`·`identify`·`verify`)·`PasskeyLoginState`·`PasskeyLoginAuthenticationToken`, `VisitLoggingAuthenticationSuccessHandler`(패스키 토큰 갈래·중간 표지 제거·`PASSKEY_LOGIN_ERROR`), `LoginFailureService.MemberSnapshot`(`totpEnabledAt`), `LoginSessionMutexFilter`(대상 추가), `TotpLoginService`(`PASSKEY_COUNTER_REJECTED`), `SecurityConfig`(`http.with(...)`).
- 패스키(`admin/member/passkey`): `PasskeyRelyingParty.discoverableRequestOptions`(+ rp-id 보관)·`PasskeyConfig`, `PasskeyCredentialRepository`(횟수 역행 거부)·`PasskeySignatureCountException`·`PasskeyCounterRegression`, `TotpLoginController`(역행 안내).
- 화면: `AdminMainController`(로그인 화면 챌린지·문구), `login.html`(패스키 블록), `passkey-login.js`(주석).
- 설정: `application.yml` 레이트리밋 `admin-passkey-login`.
- 시험: `PasskeyLoginIntegrationTest`(21)·`PasskeyLoginRateLimitIntegrationTest`·`PasskeyCredentialRepositorySignatureCountTest`(6)·`PasskeyRelyingPartyTest`(2)·`VisitLoggingAuthenticationSuccessHandlerTest`(+4)·`TotpPasskeyLoginIntegrationTest`(+2)·`LoginSessionMutexFilterTest`(+1)·`PasskeyBrowserTest`(+3), 슬라이스 공용 `PasskeyLoginTestConfig`(6개 시험 import)·`AdminMainControllerTest`·`AdminSidebarAdviceTest` 빈 추가.
- 문서: 루트·`com.cms.config`·`com.cms.admin.member` `CLAUDE.md`, `docs/deployment.md` "패스키", `docs/development-workflow.md` 위험 표.

### 검증 결과
- 영향 범위 시험 통과: `com.cms.config.*`·`admin.member.passkey.*`·슬라이스 6종·`AdminMainControllerTest`·`AdminSidebarAdviceTest`(406건), `PasskeyBrowserTest` 5건.
- 변이 확인(모두 원복 후 통과): ① 공유 세션 전략 주입 제거 → F1 3건 실패 ② 식별·검증을 바깥 `TransactionTemplate` 하나로 합침 → 스냅샷 시험 실패(`expected 999999999 but was 59836066` — 옛 `member_totp` 전체 UPDATE) ③ 뮤텍스 대상 줄 제거 → 1건 실패 ④ 서버 옵션 UV `PREFERRED` → UV 없는 실제 서명 시험 실패(로그인됨) ⑤ 레이트리밋 규칙 없음 → 시험 실패(구현 전 RED).
- 실제 검증기(브라우저, 목 없음): UV 없는 서명 → 서버 로그 `UserNotVerifiedException`, 저장 횟수보다 작은 서명 → 어댑터 거부(WARN)·저장 횟수 불변.
- **전체 시험(브라우저 제외)** `./gradlew test -PbrowserTests=exclude`: 3,243건 통과(실패 0, 건너뜀 12, 5분 22초). **브라우저 시험 전체** `-PbrowserTests=only`: 120건 통과(2분 52초).
- **실기 검증(dev `bootRun`, Playwright MCP + CDP 가상 인증기)** — 사용자의 dev ADMIN은 건드리지 않고 검증 전용 MANAGER `pkverify`를 SQL로 만들었다(admin의 해시 복사). TOTP는 실제 API로 켰다(코드는 페이지 WebCrypto로 계산).
  ① 익명 로그인 화면에 "또는" + [패스키로 로그인](`docs/screenshots/auth/passkey-login-01-login-page-button.png`) ② 보안 설정에서 패스키 등록(가상 인증기 등록 횟수 1) ③ 쿠키 삭제 → 아이디·비밀번호 빈 채로 [패스키로 로그인] → 대시보드(`passkey-login-02-dashboard-after-passkey-only.png`), DB: 방문 1건·`failed_login_count` 2→0·`failed_attempts` 0·서명 횟수 1→2·`last_used_at` KST 기록 ④ 같은 세션에서 로그인 화면을 다시 불러 챌린지를 바꾼 뒤 옛 화면에서 제출 → `?passkeyExpired`(`passkey-login-03-stale-tab-expired.png`) ⑤ 잠김(만료 전) → `?passkeyError` 단일 문구, 실패 횟수·서명 횟수 불변(`passkey-login-04-locked-account-rejected.png`) ⑥ 저장 서명 횟수를 100으로 올림 → 패스키 로그인 거부·횟수 100 유지·`last_used_at` NULL·서버 WARN, 같은 상태로 PR1 2단계 경로(비밀번호 → 코드 화면 [패스키로 인증]) → `?passkeyError`·`failed_attempts` 0 ⑦ 횟수를 낮추면 PR1 2단계 패스키 통과(회귀 없음) ⑧ 경계: 익명 `GET /admin/login/passkey` 302 로그인, CSRF 없는 `POST` 403, Spring 기본 `POST /login/webauthn` 403 ⑨ 원복: 검증 계정·방문·감사 행 삭제(패스키·TOTP 행 CASCADE 0 확인), 앱 종료.
- **라이브러리 쪽 횟수 역행**(`MaliciousCounterValueException` — 등록 당시 횟수 이하)은 실기에서 재현하지 않았다(가상 인증기의 횟수를 되돌리려면 CDP 자격 재주입이 필요) — 통합 시험(검증기가 예외를 던지는 목)으로만 고정했다.

### 이슈
1. **스냅샷 변이가 처음엔 살아남았다**: 첫 변이는 스파이된 서비스 안에서 자기 호출로 두 단계를 묶었는데, 스파이의 가로채기가 묶음 안의 호출이 아니라 필터의 두 번째 `identify` 호출(커밋 뒤)에 걸려 경합 자체가 재현되지 않았다. 바깥 `TransactionTemplate` 변이로 다시 하자 의도한 이유로 실패했다 — 시험은 맞았고 변이가 잘못됐다.
2. **UV 없는 인증기의 discoverable 응답**: Chromium 가상 인증기(UV 미지원)는 상주 자격에 대해 빈 `allowCredentials`로는 `NotAllowedError`를 낸다(실측 — CTAP2.1 credProtect "자격 ID 목록이 있을 때만 UV 생략"으로 추정, 미확인). 시험은 공격자처럼 클라이언트가 자격 ID를 넣는다(서버 옵션이 비어 있으면 라이브러리는 목록을 검사하지 않는다).
3. **성공 처리기 거부 안내**: 계획에 없던 세부 — 패스키 토큰이 재확인에서 거부되면 `/admin/login-error`(비밀번호 문구) 대신 `/admin/login?passkeyError`로 보낸다(결정 5의 단일 문구와 맞춤).
4. **슬라이스 시험 파급**: `SecurityConfig`·`AdminMainController`의 새 의존 빈 때문에 8개 슬라이스 시험의 컨텍스트가 깨졌다 — 공용 `PasskeyLoginTestConfig`(목·KST 시계·`PasskeyJson`)로 메웠다. `AdminSidebarAdviceTest`는 자기 시계가 있어 빈 두 개만 직접 더했다.
5. Chromium 가상 인증기는 등록 때 서명 횟수 1로 시작하고 어설션마다 올린다(실기 확인). 라이브러리 경로(`MaliciousCounterValueException`)는 실기·브라우저 시험에서 재현하지 않았다 — 통합 시험(목이 예외를 던짐)으로만 고정했다.

### 후속 (2026-11-19, 사용자 승인으로 등록)
- #283 CI browser-test의 Playwright 폰트(apt) 설치 시간 편차 줄이기 — `chore`·`github_actions`(PR #282 browser-test 3분→7분의 원인: us-east-2 apt 미러 122kB/s, 힙 무관)
- #284 비밀번호 로그인 `POST /admin/login`에 IP 레이트리밋 — `security`, 마일스톤 "실배포 준비"
- #285 로그인 화면 패스키 조건부 UI(아이디 칸 자동 완성) — `enhancement`
- #281(기존) 실기기 확인 범위에 패스키 단독 로그인 추가(본문 수정)
