# PLAN — 관리자 2단계 인증(TOTP) (이슈 #251)

- 상태: v6 — 계획 리뷰 6라운드 ship, **사용자 승인 완료 (2026-11-14 — 인가 정책 변경·스키마 V17·ZXing core 3.5.4·운영 필수 환경변수 포함)**, 구현·검증 완료(PR 전)
- 위험 변경: **인증·로그인 경로**, **인가 정책(URL 규칙 1개 추가)**, **스키마(V17)**, **새 의존성(ZXing)**, **세션·동시성**, **보안 설정(레이트리밋 면제 판정)**
- 이슈: #251 (deferred → 2026-11-14 사용자가 착수 지시. 재착수 조건 "라이브러리·정책 제안 승인"은 이 계획의 4단계 승인으로 충족한다)

## 0. 사용자 결정 (2026-11-14, 정찰 직후)

| 쟁점 | 결정 | 기각한 선택지 |
|---|---|---|
| 적용 대상 | **ADMIN·MANAGER 전원 선택 등록**(본인이 내 정보 설정에서 켠다). 필수화는 후속 | ADMIN 필수 / 전원 필수 — 강제 등록 흐름·시험 지원 코드 28개 파일 영향 |
| 로그인 흐름 | **비밀번호 통과 후 별도 코드 화면**(2단계 화면) | 같은 폼의 코드 칸(중간 상태 없음) |
| 복구 | **복구 코드 10개 + ADMIN의 타 계정 2단계 해제** | ADMIN 해제만 / 복구 코드만 |
| 라이브러리 | **TOTP 직접 구현(JDK `Mac`) + ZXing core(QR)** | TOTP 라이브러리 추가 / QR 없음 |
| 비밀값 보관 | **앱 키(환경변수)로 AES-GCM 암호화** | 평문 |

비밀번호 재설정 메일은 2단계를 해제하지 않는다(해제하면 메일함만으로 2단계 우회).

## 1. 정찰 사실 (출처)

| # | 사실 | 출처 |
|---|---|---|
| F1 | 폼 로그인 1단계. 성공 처리기가 상태·역할·해시를 fail-closed 재확인 | `SecurityConfig.java:183-189`, `VisitLoggingAuthenticationSuccessHandler.java` |
| F2 | `AbstractAuthenticationProcessingFilter.doFilter` 순서: `attemptAuthentication` → (mfa 병합) → `SessionAuthenticationStrategy.onAuthentication`(ID 교체·레지스트리 등록) → `successfulAuthentication`(컨텍스트 설정·**`saveContext`** → 이벤트) → 성공 처리기 | 실측 `javap -c` spring-security-web-7.0.7 |
| F3 | → 성공 처리기에서 권한을 줄이면 **이미 세션에 완전 권한 컨텍스트가 저장된 뒤**라, 같은 세션의 동시 요청이 그 사이 완전 권한을 쓸 수 있다. 권한 축소는 프로바이더 단계에서 해야 한다 | F2에서 추론 |
| F4 | `AbstractUserDetailsAuthenticationProvider.createSuccessAuthentication`은 `UserDetails.getAuthorities()` + `FACTOR_PASSWORD`로 `UsernamePasswordAuthenticationToken.authenticated(...)`를 만든다 | 실측 `javap -c` spring-security-core-7.0.7 |
| F5 | 필터의 MFA 권한 병합(`shouldPerformMfa`)은 `mfaEnabled` 플래그가 켜졌을 때만(= `@EnableMultiFactorAuthentication`) 동작한다. 이 계획은 그 애너테이션을 **쓰지 않는다** | 실측 `javap -c`, 공식 문서 7.0 `servlet/authentication/mfa.html` |
| F6 | Spring Security 7 MFA는 TOTP 요소를 기본 제공하지 않고, 요소 권한은 기본 규칙(`hasRole` 등)에만 덧붙는다 — 이 프로젝트의 `featureReadGate`·`AdminPermissionEvaluator`에는 자동 적용되지 않는다 | 공식 문서 7.0 mfa, `SecurityConfig.java:76-80,113-122` |
| F7 | `AdminPermissionEvaluator`는 `authentication.getAuthorities()`의 `ROLE_ADMIN`/`ROLE_MANAGER`로만 판정한다 → 역할 권한이 없는 인증은 모든 기능 게이트에서 거부된다 | `AdminPermissionEvaluator.java:74-77,229-236` |
| F8 | `RateLimitFilter.isAuthenticatedUser()`·`PublicTransferInterceptor.isAuthenticatedUser()`는 "익명이 아니면 로그인"으로 판정해 면제한다 → 중간 상태 세션도 면제받는다(**수정 필요**) | `RateLimitFilter.java:63,77-80`, `PublicTransferInterceptor.java:34,63-66` |
| F9 | `ContentStatsService:57`·`NotificationController:102`·`AdminSearchService:140`도 `isAuthenticated()`를 보지만 모두 ALWAYS 게이트(`hasAnyRole`) 뒤라 중간 상태는 도달하지 못한다 | 각 파일, `SecurityConfig.java:107-112` |
| F10 | 세션 ID를 바꾸면 레지스트리의 `expired` 표시가 옮겨지지 않아 만료된 세션이 부활할 수 있다 | `docs/troubleshooting.md:1452-1468` |
| F11 | `LoginSessionMutexFilter`가 세션이 있는 `POST /admin/login`을 세션 뮤텍스 안에서 처리한다 | `config/CLAUDE.md:43-47` |
| F12 | 실패 카운트는 `BadCredentialsException`만 센다(5회 → 30분 잠금 + 세션 만료) | `LockingAuthenticationFailureHandler.java`, `LoginFailureService.recordFailure` |
| F13 | ZXing core 최신 릴리스 3.5.4 (Apache-2.0) | Maven Central `com/google/zxing/core/maven-metadata.xml` (lastUpdated 2025-11-11) — 라이선스는 미확인(구현 전 POM 확인) |
| F14 | `Member`에 2단계 컬럼 없음(v2: 별도 테이블로 분리). 다음 마이그레이션 번호 V17. 시각 컬럼 관례 `datetime(6)`, FK 이름 `fk_<table>_member` | `Member.java`, `V1__baseline_schema.sql:61-62,227-233` |
| F16 | 단일 `AuthenticationProvider` 빈이 있고 전역 관리자가 아직 구성되지 않았을 때만 그 빈이 전역 `AuthenticationManager`에 등록되고, 2개 이상이면 등록하지 않는다(INFO 로그) | 실측 `javap -c` spring-security-config-7.0.7 `InitializeAuthenticationProviderBeanManagerConfigurer$InitializeAuthenticationProviderManagerConfigurer` |
| F17 | `CustomUserDetails`는 `Member` 엔티티 전체를 보관한다 → `Member`에 비밀값 컬럼을 두면 암호문이 세션·레지스트리 principal에 실린다 | `CustomUserDetails.java:16`, `AdminSecurityService.java:94` |
| F18 | `resetFailuresAndCheckActive`는 조회와 함께 실패 횟수를 0으로 만든다 | `LoginFailureService.java:93-104` |
| F19 | `AdminActionLogAspect`는 반환값의 getter로 대상 ID를 읽고, 반환값이 없으면 null | `AdminActionLogAspect.java:51,135` |
| F20 | `AdminSessionRevokeEvent(targetMemberId)`는 대상의 **모든** 세션을 AFTER_COMMIT에 만료한다(현재 세션 제외 옵션 없음) | `AdminSessionRevokeEvent.java`, `AdminSessionRevokeListener.java:29` |
| F21 | 세션 고정 방어 기본 전략은 `ChangeSessionIdAuthenticationStrategy`(로그인 요청 안에서 ID 교체), `HttpSessionSecurityContextRepository`는 `setAllowSessionCreation(boolean)`을 제공한다 | 실측 `javap` spring-security-config/web-7.0.7 `SessionManagementConfigurer.createDefaultSessionFixationProtectionStrategy`, `HttpSessionSecurityContextRepository` |
| F22 | `Member.changePassword()`(재설정 포함)는 `failedLoginCount`를 0으로 만든다 | `Member.java:188-195`, `PasswordResetService.java:341-345` |
| F23 | `AdminActionLogAspect`는 정상 반환이면 항상 `SUCCESS`를 기록한다. 결과 기반 감사는 AFTER_COMMIT 리스너가 명시 기록하는 패턴(`AdminAccountAutoLockListener`, `@Order(20)`)이 있다 | `AdminActionLogAspect.java:45-60`, `AdminAccountAutoLockListener.java` |
| F15 | 실제 폼 로그인을 쓰는 시험 28개 파일·`BrowserTestSupport.login` — 시험 회원은 2단계를 켜지 않으므로 기존 흐름 그대로 | `grep`, `BrowserTestSupport.java:106-125` |

## 2. 핵심 설계 결정

### 결정 1 — 중간 상태는 "역할 권한이 없는 인증"으로 표현한다
- 2단계를 켠 회원이 비밀번호를 통과하면 프로바이더가 **`ROLE_*`가 없는** 토큰을 만든다: 권한 = `{TOTP_PENDING}` 하나(`FACTOR_PASSWORD`도 넣지 않는다 — 토큰을 직접 만든다).
- 이유: 모든 기존 게이트(`hasRole`·`hasAnyRole`·`featureReadGate`·`@PreAuthorize`·`AdminPermissionEvaluator`·`anyRequest().denyAll()`)가 **역할 권한**을 보므로(F7) 새 검사를 곳곳에 넣지 않아도 fail-closed로 거부된다. Spring MFA(요소 권한 추가 방식)는 사용자 정의 게이트에 자동 적용되지 않아 누락 위험이 있다(F6). `@EnableMultiFactorAuthentication`은 쓰지 않는다(F5 — 켜면 같은 이름 재로그인 시 기존 권한이 병합된다).
- 구현: `DaoAuthenticationProvider`를 상속한 `TotpAwareAuthenticationProvider`(`createSuccessAuthentication` 재정의) — principal이 `CustomUserDetails`이고 `totpEnabled`면 `UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of(TOTP_PENDING))`, 아니면 `super`. **애플리케이션 전체에서 유일한 `AuthenticationProvider` 빈**으로 등록한다 — 전역 관리자는 이 빈이 정확히 하나일 때만 이를 쓴다(F16). `UserDetailsService`·`PasswordEncoder`는 기존 빈 주입, `CompromisedPasswordChecker`는 설정하지 않는다(member/CLAUDE.md 24행). 실제 체인에서 이 프로바이더가 쓰였음은 FM1 시험이 고정한다.
- `totpEnabled`: `CustomUserDetailsService`가 회원을 읽을 때 `member_totp` 행 존재를 **같은 트랜잭션**에서 조회해 `CustomUserDetails`에 불리언으로만 싣는다. 비밀값·암호문은 principal·세션에 들어가지 않는다(F17 — 그래서 결정 7에서 별도 테이블로 분리한다).
- **비밀번호 단계 성공 처리기**(`VisitLoggingAuthenticationSuccessHandler`)의 재확인을 둘로 나눈다(F18):
  - 공통 판정: fresh 스냅샷(상태·역할·해시·**2단계 사용 여부**) — 상태 `ACTIVE`, 역할 = principal 원래 역할(`CustomUserDetails.getAuthorities()`), 해시 일치, 그리고 **스냅샷의 2단계 사용 여부 = 토큰이 `TOTP_PENDING`인지**. 불일치(로그인 진행 중 등록·해제가 커밋된 경합 포함)면 기존처럼 거부.
  - 완전 토큰: 기존대로 실패 횟수 리셋 + 방문 기록·알림 정리.
  - `TOTP_PENDING` 토큰: **실패 횟수를 리셋하지 않는다**(리셋하면 "TOTP 4회 오답 → 비밀번호 재로그인"으로 대입 제한이 무력화된다). 세션에 `TOTP_PENDING_STATE{memberId, since}`(Clock 시각) 저장 후 `/admin/login/totp`로 302. 방문 기록·알림 정리는 2단계 통과 때.
  - **로그인 진행 중 등록 커밋 경합의 잔여 창**(리뷰 2라운드 지적 1): 완전 토큰이 저장된 뒤 성공 처리기가 거부하기 전까지의 창에서 그 권한을 쓰려면 새 세션 ID가 필요하지만, 새 ID는 이 로그인 요청 안에서 만들어지고(F21) 응답은 처리기가 끝난 뒤에야 나간다 — 아무도 그 ID로 요청할 수 없다. 옛 ID는 교체 즉시 무효다. 교체 전에 시작해 교체 뒤 컨텍스트를 지연 로딩하는 요청만 남는데, 밀리초 창에 계정 주인의 등록 커밋이 겹쳐야 해 공격자가 타이밍을 정할 수 없다 → R8로 수용.
  - `LoginFailureService`에 리셋 없는 `checkActive(userId)`(스냅샷에 `totpEnabled` 포함)를 추가하고 기존 `resetFailuresAndCheckActive`도 같은 스냅샷을 반환한다.

### 결정 2 — 코드 화면과 승격
- `GET /admin/login/totp`(화면), `POST /admin/login/totp`(폼 제출, CSRF). 두 핸들러에 `@PreAuthorize("hasAuthority('TOTP_PENDING')")`를 직접 선언한다(URL 규칙과 이중 방어). `AdminEndpointAuthorizationConventionTest`는 지금 정해진 역할 표현식·권한 애너테이션만 인식하므로(`AdminEndpointAuthorizationConventionTest.java:232,289`) **이 경로와 이 표현식의 조합만** 인정하도록 확장한다 — 공개 예외(`PUBLIC_ENDPOINTS`)에 넣지 않는다. 반례 시험: 선언 제거 시 위반, 다른 `/admin` 경로의 `hasAuthority('TOTP_PENDING')`는 위반(리뷰 4라운드 지적 2). 접근 규칙 `.requestMatchers("/admin/login/totp").hasAuthority("TOTP_PENDING")`을 `/admin/**` 캐치올 **앞**에 추가(**인가 정책 변경**). 익명 → 로그인 302, 완전 인증 → 403.
- `POST /admin/login/totp`를 `LoginSessionMutexFilter`의 대상에 추가 — 같은 세션의 이중 제출·재로그인과 직렬화한다.
- 승격 절차(컨트롤러 → `TotpLoginService.verifySecondFactor` → 커밋된 결과 → 컨트롤러가 컨텍스트 저장):
  1. 세션의 `TOTP_PENDING_STATE`가 없거나, `memberId` ≠ principal id, 또는 **5분** 경과 → 세션 무효화, `/admin/login?totpExpired` 302.
  2. 레지스트리 항목(`sessionRegistry.getSessionInformation(sessionId)`)이 없거나 `expired` → 세션 무효화, 로그인 302.
  3. **한 트랜잭션, 회원 행 잠금(`findByIdForUpdate`) 아래에서** 다음을 모두 한다(판정과 실패 기록을 나누면 여러 중간 세션이 잠금 사이에 추가 판정을 받는다):
     - 재확인: 상태 `ACTIVE`, 역할·해시 = principal, `member_totp` 존재. 불일치 → `REJECTED`.
     - 코드 판정: 6자리 숫자면 TOTP(±1 스텝, **`step > last_used_step`만 인정**, 통과 시 갱신), 그 외면 복구 코드(하이픈 제거·`Locale.ROOT` 대문자 → SHA-256, 미사용 행만, 통과 시 `used_at`).
     - 성공 → `member_totp.failed_attempts` 0 **그리고** 회원 `failed_login_count` 0(두 요소 모두 통과). 결과 `SUCCESS(remainingRecoveryCodes, usedRecoveryCode)`.
     - 실패 → 같은 트랜잭션에서 **`member_totp.failed_attempts` +1**(비밀번호 카운터와 분리 — 비밀번호 재설정·재로그인이 `failed_login_count`를 0으로 만들어도 TOTP 대입 제한은 유지된다, F22), 5 도달 시 회원 `LOCKED` 전이(`lockedAt` = now, 30분 자동 해제 규칙 그대로) + `failed_attempts` 0(잠금 기간 자체가 제한이다) + `AdminAccountAutoLockEvent`·`AdminSessionRevokeEvent` 발행(임계값 5·잠금 전이는 `LoginFailureService`의 상수·전이 규칙을 공용으로 쓴다). 결과 `WRONG_CODE` 또는 `LOCKED`. **트랜잭션은 커밋**한다(실패도 기록이므로 예외로 롤백하지 않는다).
  4. 서비스 반환(= 커밋 완료) 뒤에만 컨트롤러가 처리한다(커밋 전 완전 권한 공개 금지):
     - `SUCCESS`: 새 `SecurityContext`(`authenticated(principal, null, principal.getAuthorities())`) → **세션 생성을 금지한**(`setAllowSessionCreation(false)`, F21) 전용 `HttpSessionSecurityContextRepository`로 `saveContext` → **저장 뒤 재확인**: `request.getSession(false)`가 시작 때와 같은 ID이고, 레지스트리 항목이 있고 만료되지 않았는가 — 아니면(그 사이 로그아웃·강제 만료) 세션이 있으면 무효화하고 `/admin/login-error`(무효화된 세션의 `IllegalStateException`도 같은 처리). 이유: 기본 저장소는 세션을 새로 만들 수 있고 수동 승격은 레지스트리 등록을 다시 하지 않아, 로그아웃과 겹치면 레지스트리 밖 인증 세션이 생길 수 있다(리뷰 2라운드 지적 2). 세션 생성을 막으므로 로그아웃을 직렬화할 필요는 없다. 통과하면 `TOTP_PENDING_STATE` 제거, 방문 기록·알림 정리(성공 처리기의 두 메서드를 공용 컴포넌트로 분리해 재사용), `/admin` 302. 복구 코드를 썼으면 남은 개수 플래시.
     - `WRONG_CODE`: 코드 화면 재표시(일반 오류 문구).
     - `LOCKED`·`REJECTED`: 세션 무효화, `/admin/login-error`.
     - 서비스 예외(커밋 실패 포함): 컨텍스트 변경 없이 세션 무효화, `/admin/login-error`(fail-closed).
- **세션 ID는 바꾸지 않는다**: 고정 공격 방어는 비밀번호 단계에서 이미 했고, 다시 바꾸면 F10의 만료 부활 경로가 생긴다. 남는 위험은 R3.
- 중간 상태 사용자가 다른 `/admin/**` 화면에 오면 코드 화면으로 302, `/admin/api/**`는 JSON 401. `SecurityConfig` accessDeniedHandler에 `TOTP_PENDING` 분기를 익명 분기와 같은 자리에 둔다.
- 코드 화면의 "다른 계정으로 로그인" = `POST /admin/logout`.

### 결정 3 — "로그인 사용자" 판정 공용화 (F8 수정)
- `com.cms.config.auth.AdminAuthentications.isFullyAuthenticated(Authentication)`: 인증됨 && 익명 아님 && `TOTP_PENDING` 없음. `RateLimitFilter`·`PublicTransferInterceptor`의 면제 판정을 이것으로 바꾼다.
- 다른 `isAuthenticated()` 사용처(F9)는 역할 게이트 뒤라 바꾸지 않는다.

### 결정 4 — 등록·해제 API (본인 `me` 별칭 — ALWAYS 게이트 안, URL 규칙 추가 없음)
| 메서드·경로 | 요청 | 응답 | 비고 |
|---|---|---|---|
| `GET /admin/api/members/me/totp` | — | 200 `{enabled, enabledAt, remainingRecoveryCodes}` | 설정 화면 표시 |
| `POST /admin/api/members/me/totp-enrollments` | — | 201 `{enrollmentId, secret, otpauthUri, qrPngBase64}` | 새 비밀값(160비트)을 세션에 `{memberId, enrollmentId(UUID), secret, issuedAt}`로 보관(10분), DB 쓰기 없음. 이미 사용 중이면 409. 다시 부르면 이전 대기를 덮어쓴다 |
| `PUT /admin/api/members/me/totp` | `{enrollmentId, code, currentPassword}` | 200 `{recoveryCodes:[10]}` | 아래 절차 |
| `DELETE /admin/api/members/me/totp` | `{code, currentPassword}` | 204 | TOTP 또는 복구 코드 + 현재 비밀번호 |
| `DELETE /admin/api/members/{id}/totp` | — | 204 | `hasRole('ADMIN')`, 본인 id면 400, 미사용이면 204(멱등, 감사 기록 없음) |
- **세션 뮤텍스 직렬화**(리뷰 3라운드 지적 2): 등록 대기 발급(`POST …/totp-enrollments`)과 확정(`PUT …/totp`)은 컨트롤러가 `WebUtils.getSessionMutex(session)` 안에서 **대기 상태 검사부터 DB 커밋·대기 정리까지** 처리한다 — 회원 행 잠금은 DB 쓰기만 직렬화해, 같은 세션의 다른 탭이 대기 비밀값을 바꾸는 것을 막지 못한다(Servlet은 같은 세션 동시 접근을 허용 — Jakarta Servlet 6.1 §7.7.1). 잠금 순서는 세션 뮤텍스 → 회원 행 잠금으로 로그인 경로(`LoginSessionMutexFilter` → DB)와 같아 교착이 없다.
- **등록 확정 절차**: 세션 대기 상태의 `memberId` = 현재 principal id, `enrollmentId` 일치, 10분 이내 — 하나라도 아니면 409 `RESOURCE_CONFLICT`(다른 탭이 새로 시작했거나 만료·다른 계정 대기). 그 뒤 회원 행 잠금 트랜잭션에서 이미 사용 중이면 409, 현재 비밀번호(BCrypt)·코드를 판정. 성공 → `member_totp` 저장(암호문·`enabled_at`·`last_used_step`), 복구 코드 10개 해시 저장, 실패 횟수 0, **`AdminSessionRevokeEvent`로 본인 전 세션 만료(현재 세션 포함, F20)** — 등록 이전에 만들어진 다른 세션을 2단계 없이 남기지 않기 위해서다. 응답(복구 코드)은 이 요청에서 정상 전달되고, 화면은 "다시 로그인" 안내로 끝난다(다음 요청부터 만료 — 비밀번호 변경과 같은 UX). 세션 대기 상태는 성공 시 제거.
- **재인증 실패 제한**: 등록 확정·본인 해제에서 현재 비밀번호 또는 코드가 틀리면 같은 행 잠금 트랜잭션에서 실패 카운터 +1 — 해제(2단계 사용 중)는 **`member_totp.failed_attempts`**, 등록 확정(아직 행 없음)은 회원 `failed_login_count`(5회 → `LOCKED` + 전 세션 만료 + 잠금 감사), 커밋 후 400 `VALIDATION_ERROR`(고정 문구 "현재 비밀번호 또는 인증 코드가 올바르지 않습니다"). 탈취 세션의 대입은 세션·IP를 바꿔도 계정 카운터로 막힌다. 대가: 세션 소유자가 일부러 틀려 계정을 잠글 수 있으나 수용(R7).
- 해제 성공 → `member_totp`·복구 코드 행 삭제, 실패 횟수 0. 세션은 유지(보안 수준을 낮추는 조작).
- **감사는 `@AdminActionLogged`를 쓰지 않는다**(F23 — 실패·무변경도 정상 반환해 커밋하므로 aspect가 성공으로 기록한다). 실제로 바뀐 경우에만 트랜잭션 안에서 `MemberTotpAuditEvent(actionType, actorMemberId, actorUserId, targetMemberId, ip, uri)`를 발행하고, AFTER_COMMIT 리스너(`AdminAccountAutoLockListener`와 같은 패턴, 메서드 `@Order(20)`, 예외 격리)가 `AdminActionLogService.log(..., SUCCESS, "MEMBER", targetId, ...)`로 기록한다. 유형: `TOTP_ENABLE`·`TOTP_DISABLE`·`TOTP_RESET`(`AdminActionTypes`에 추가). 재인증 실패·미사용 대상 해제(무변경)는 감사 행을 만들지 않는다.
- 모든 쓰기는 `findByIdForUpdate`(member/CLAUDE.md 18행 관례 — 회원 행이 2단계 상태의 직렬화 지점).
- **행 잠금 직후 상태 재확인**(리뷰 5라운드 지적 1): 등록 확정·본인 해제는 회원 행 잠금을 얻은 **직후** fresh 상태 `ACTIVE`이고 역할이 `ROLE_ADMIN`/`ROLE_MANAGER`이며 principal 역할과 같은지 확인하고, 아니면 **코드·비밀번호 판정, 카운터 변경, 등록·삭제 없이** 409 `RESOURCE_CONFLICT`로 끝낸다. 이유: 세션 만료는 이미 인가를 통과한 진행 중 요청을 취소하지 않으므로(`AdminSessionService.java:94`, Spring Security 7.0.7 `ConcurrentSessionFilter`는 체인 진입 전에만 검사), 행 잠금에서 기다리던 요청이 잠긴 계정에 대해 6번째 이후 판정을 받거나 올바른 자격정보로 변경에 성공하면 안 된다. ADMIN 해제(`resetTotp`)는 행위자 쪽은 기존 ADMIN 게이트, 대상 쪽은 상태와 무관하게 해제 허용(복구 조작이므로).
- **유일한 ADMIN이 인증기와 복구 코드를 모두 잃으면** DB 수동 절차(`docs/deployment.md`에 SQL: 해당 회원의 `member_totp`·`member_totp_recovery_code` 행 삭제).

### 결정 5 — TOTP 계산(직접 구현)
- RFC 6238, HMAC-SHA1, 30초, 6자리, T0=0. `TotpCodeVerifier`(순수 클래스, `Clock` 주입): `verify(secret, code, lastUsedStep) → OptionalLong matchedStep`. 비교는 `MessageDigest.isEqual`.
- 비밀값 `SecureRandom` 20바이트, Base32(RFC 4648, 패딩 없음) 직접 구현.
- **`otpauth` URI**(RFC가 아니라 Google `google-authenticator` 위키 "Key Uri Format"이 사실상 표준 — https://github.com/google/google-authenticator/wiki/Key-Uri-Format): `otpauth://totp/{label}?secret={B32}&issuer={issuer}&algorithm=SHA1&digits=6&period=30`.
  - issuer = 설정 `cms.totp.issuer`(기본 `CMS`), **기동 시 검증**: 비어 있지 않고 `:`를 포함하지 않음(위반 시 기동 실패).
  - label = `issuer:accountName`. accountName = userId에서 `:`를 `_`로 바꾼 값(표시 전용 — 판정에 쓰이지 않는다). 아이디 검증 규칙(`AdminSignupRequest`)은 바꾸지 않는다.
  - 인코딩: label 각 부분과 issuer를 **RFC 3986 퍼센트 인코딩**(공백 `%20`, `+` → `%2B`; `URLEncoder`의 폼 인코딩 `+`를 그대로 쓰지 않는다), label 구분자 `:`는 인코딩하지 않는다.
  - 시험: 한글·공백·`+`·`:` 포함 아이디의 URI가 위 규칙대로 나오는지, 생성한 QR PNG를 ZXing core 디코더(`QRCodeReader`)로 읽어 같은 URI로 왕복되는지.
  - 실제 인증 앱 확인: 7단계 실기 검증에서 **사용자 휴대폰의 인증 앱**(앱 이름·버전을 결과 문서에 기록)으로 QR을 등록하고 생성 코드로 로그인한다 — 자동화할 수 없어 사용자에게 요청한다.
- 시험: RFC 6238 부록 B SHA1 벡터(8자리 → 6자리 절단 확인), RFC 4648 Base32 벡터.

### 결정 6 — 비밀값 암호화
- `TotpSecretCipher`: AES-256-GCM(JDK `Cipher "AES/GCM/NoPadding"`), 12바이트 무작위 IV, 128비트 태그, **AAD = `member_totp:{memberId}`**. 저장 형식 `v1:` + Base64(IV‖암호문). 컬럼 `varchar(255)`.
- 키: `cms.totp.encryption-key` ← 환경변수 `CMS_TOTP_ENCRYPTION_KEY`(Base64 32바이트). **prod는 필수**(없거나 길이 틀리면 기동 실패). 운영 배선: `docker-compose.prod.yml`의 `app.environment`는 나열한 변수만 컨테이너에 전달하므로(`env_file` 미사용, `docker-compose.prod.yml:31-66`) `CMS_TOTP_ENCRYPTION_KEY: ${CMS_TOTP_ENCRYPTION_KEY:?…}`를 추가하고, CI `prod-smoke`의 환경 파일 생성(`scripts/ci/_prod-ci-common.sh:135` 부근)에 시험용 무작위 키를 넣는다. issuer는 설정 기본값만 쓰고 환경변수로 노출하지 않는다(필요해지면 후속). dev(`application-dev.yml`)는 고정 개발용 키(운영 사용 금지 주석), 시험은 dev 값을 쓴다.
- 복호 실패 → 그 회원의 TOTP 판정은 실패 처리(fail-closed, ERROR 로그에 memberId만) — 이 경우 **실패 카운터를 올리지 않고** `REJECTED`로 끝낸다(서버 측 키 문제로 사용자가 잠기지 않게). 복구 코드는 키와 무관하게 동작한다. 키 교체 절차는 범위 밖.

### 결정 7 — 스키마 V17 (위험 변경: 마이그레이션) — 비밀값은 `member`와 분리
```sql
CREATE TABLE `member_totp` (
  `member_id` bigint(20) NOT NULL,
  `secret` varchar(255) NOT NULL,
  `enabled_at` datetime(6) NOT NULL,
  `last_used_step` bigint(20) NOT NULL,
  `failed_attempts` int(11) NOT NULL DEFAULT 0,
  PRIMARY KEY (`member_id`),
  CONSTRAINT `fk_member_totp_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
CREATE TABLE `member_totp_recovery_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `member_id` bigint(20) NOT NULL,
  `code_hash` char(64) NOT NULL,
  `used_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_totp_recovery_code_member` (`member_id`),
  CONSTRAINT `fk_member_totp_recovery_code_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```
- 이유: `member` 컬럼으로 두면 `CustomUserDetails`가 보관하는 엔티티를 통해 암호문이 세션·레지스트리에 실린다(F17). 별도 엔티티 `MemberTotp`(`@Id memberId`, 연관관계 매핑 없음 — 프로젝트 관례), `MemberTotpRecoveryCode`. `Member`는 바꾸지 않는다. 2단계 상태 변경의 직렬화는 여전히 `member` 행 잠금이 맡는다(결정 4).
- 기존 회원 = 행 없음 = 미사용 → 동작 변화 없음. 기존 행 값 변환이 없어 업그레이드 경로 시험은 두지 않는다(`docs/migration-guide.md` 규칙 구현 전 재확인).
- 복구 코드: `SecureRandom` **15바이트(120비트)** → Base32 24자, 표기 `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`, 정규화(하이픈·공백 제거, `Locale.ROOT` 대문자) 후 SHA-256 저장. 120비트는 NIST SP 800-63B-4 §3.1.2.2가 솔트 없는 빠른 해시를 허용하는 lookup secret 최소 112비트 이상이라, DB 유출 시 오프라인 대입이 비현실적이다(https://pages.nist.gov/800-63-4/sp800-63b.html#lookupver). 50비트 + SHA-256은 유출 시 대입 가능해 기각(리뷰 4라운드 지적 1). 시험: 생성 바이트 길이 15·표시 24자+하이픈 5개·정규화 왕복.
- 회원 삭제 경로: 회원은 소프트 삭제(`DELETED` 상태)로 알고 있다 — 하드 삭제 경로 유무를 구현 전 grep으로 확인(있으면 자식 행 삭제 추가).
- 롤백: 이전 앱은 새 테이블을 모르므로 2단계 없이 로그인한다(보안 수준 하락을 `docs/deployment.md` 롤백 주의에 기재).

### 결정 8 — 화면
- `templates/admin/login-totp.html`: 코드 입력 1칸(`autocomplete="one-time-code"`, 복구 코드도 받으므로 `inputmode="numeric"` 미사용), 안내 "인증 앱의 6자리 코드 또는 복구 코드", 오류 문구, 다른 계정 로그인.
- `admin-settings.html` "2단계 인증" 카드: 미사용 → [사용하기] → QR(`data:image/png;base64`)+비밀값 텍스트+코드·현재 비밀번호 → 성공 시 복구 코드 10개(복사 버튼, "다시 볼 수 없음", "다시 로그인해야 합니다" 안내 + 로그인 화면 이동 버튼). 사용 중 → 시작 시각·남은 복구 코드 수·[끄기](코드+비밀번호).
- `admin-manage.html` 상세 모달: "2단계 인증: 사용 중/미사용" + ADMIN에게 [2단계 해제](본인 상세 제외).
- QR PNG: ZXing core `QRCodeWriter.encode` → `BitMatrix`를 `BufferedImage`로 직접 그린다(`javase` 모듈 미추가). API는 구현 전 core 3.5.4 jar로 확인.

## 3. 작업 단계 (안쪽 → 바깥쪽)
1. 의존성 `com.google.zxing:core:3.5.4`(POM 라이선스 확인·기록), V17, 엔티티·리포지토리.
2. 순수 로직: `Base32`, `TotpCodeVerifier`, `TotpSecretCipher`, `OtpAuthUri`, `RecoveryCodes`, 설정 프로퍼티(prod 키·issuer 검증).
3. 서비스: 실패 판정 공용화(`LoginFailureService`), `MemberTotpService`(대기·확정·해제·ADMIN 해제·조회), `TotpLoginService`(2단계 판정).
4. 보안 배선: `CustomUserDetails.totpEnabled`, `TotpAwareAuthenticationProvider`, 성공 처리기 분기·방문 기록 분리, `SecurityConfig` 규칙·accessDenied 분기, `LoginSessionMutexFilter` 대상 추가, `AdminAuthentications` + 면제 판정 교체.
5. 웹: `TotpLoginController`, `MemberTotpApiController`, ADMIN 해제 API.
6. 화면: `login-totp.html`, 설정 카드 JS(CSRF 헤더), 관리 모달.
7. 운영 배선·문서: `docker-compose.prod.yml`(키 전달), `scripts/ci/_prod-ci-common.sh`(CI 키), `.env.example`, `config/CLAUDE.md`(표·절), `member/CLAUDE.md`, 루트 `CLAUDE.md` 보안 규칙, `docs/deployment.md`(환경변수·수동 복구 SQL·롤백 주의), `development-workflow.md` 위험 지점 표.

## 4. 성공 기준 (검증 가능)
- S1 미등록 회원: 기존 로그인 흐름·시험 전부 그대로(전체 시험 통과).
- S2 등록 회원: 비밀번호 통과 후 `/admin`·`/admin/api/members/me`·위임 기능·`@PreAuthorize` API·공개 레이트리밋 면제 모두 불가, 코드 화면만 가능. 올바른 코드 → `/admin` 접근 가능.
- S3 같은 스텝 재사용 거부, ±1 스텝 허용, ±2 거부.
- S4 2단계 오답은 별도 카운터(`member_totp.failed_attempts`)에 쌓이고 **비밀번호 재로그인·비밀번호 재설정으로 리셋되지 않는다**. 5회 → `LOCKED` + 중간 세션 만료.
- S5 복구 코드 1회 사용 후 재사용 거부, 남은 개수 감소.
- S6 등록 왕복: 대기 → 확정(복구 코드 10개 1회) → DB 암호문(평문 아님) → 본인 전 세션 만료 → 해제 → 행 삭제.
- S7 ADMIN 해제: 대상 해제 + 감사 행 정확히 1개(수행자·대상 회원 id·`TOTP_RESET`·SUCCESS), 반복 해제·재인증 실패는 감사 행 없음, MANAGER 403.
- S8 prod 프로파일에서 키 없음·issuer에 `:` → 기동 실패(설정 시험), 그리고 **운영 Compose 경로로 키가 전달돼 기동**(PR CI `prod-smoke` 통과 — 로컬 실행은 폐기 가능한 Docker 환경 조건이라 CI 결과로 확인).
- S9 비밀번호 재설정 후에도 2단계 유지.
- S10 등록·해제의 재인증 오답이 카운터에 반영되어 5회 → 잠금.
- S11 승격 저장 직전 로그아웃이 끼면 인증 세션이 남지 않는다(새 세션 미생성).

## 5. 실패 모드와 고정 시험 (최대 5)
| # | 놓치기 쉬운 입력·실패 | 고정 시험 |
|---|---|---|
| FM1 | 프로바이더가 실제 체인에서 안 쓰이거나 중간 세션이 완전 권한을 얻음(F3·F16) | `TotpPendingAuthorizationIntegrationTest`(`@SpringBootTest`+MockMvc, 실제 체인) — 등록 회원 폼 로그인 직후 컨텍스트 권한 = `{TOTP_PENDING}`, `/admin`·`/admin/api/members/me`·위임 기능 API·`@PreAuthorize` API 모두 302/401/403, `/admin/login/totp`만 200. 변이: 프로바이더 빈 제거 → 실패 |
| FM2 | 비밀번호 재로그인·재설정으로 실패 횟수 리셋 / 다른 세션·이미 인가된 동시 요청이 5회 초과 판정 | `TotpLoginServiceIntegrationTest`(실제 DB) — 오답 4회 → 새 세션 비밀번호 로그인 → 오답 1회에 `LOCKED`; 오답 4회 → 메일 재설정(`resetPassword`) → 오답 1회에 `LOCKED`; 두 스레드 동시 오답에서 카운터가 정확히 2 증가; 같은 코드 동시 제출 시 하나만 성공; `MemberTotpReauthConcurrencyIntegrationTest` — 본인 해제 요청 6개를 미리 인가시켜 행 잠금 앞에 세운 뒤(스파이 래치) 5번째 오답으로 잠기면 6번째(올바른 비밀번호·코드)는 판정 없이 409이고 `member_totp` 행이 남는다 |
| FM3 | 로그인 진행 중 등록·해제 커밋 경합 / 등록 전 세션 생존 / 승격 중 로그아웃 | `TotpEnrollmentRaceIntegrationTest` — 스냅샷 2단계 여부 ≠ 토큰 종류면 성공 처리기 거부하고 **쓸 수 있는 세션·활성 레지스트리 항목이 남지 않음**(사용자 정보 읽기 뒤 등록 커밋을 끼워 넣는 스파이), 등록 확정 후 같은 회원의 다른 세션 요청이 만료; `TotpPromotionLogoutRaceTest` — 판정 완료 후 저장 직전에 세션 무효화를 끼워 넣으면(저장소 스파이) 새 세션이 생기지 않고 결과는 로그인 오류 |
| FM4 | 중간 세션의 공개 면제(F8) / 대기 비밀값의 계정 혼동 | `RateLimitFilterTest`·`PublicTransferInterceptorTest`에 `TOTP_PENDING` 케이스, `MemberTotpServiceTest` — 다른 memberId·옛 enrollmentId·만료 대기 → 409; `TotpEnrollmentConcurrencyTest` — A의 확정이 대기 검사를 통과한 직후 B의 발급을 동시에 실행(래치)해도 "B가 새 비밀값을 받음"과 "A가 옛 비밀값으로 등록 성공"이 함께 일어나지 않음(B는 A가 끝날 때까지 뮤텍스에서 대기) |
| FM5 | 암호문을 다른 회원 행에 복사 / 키 불일치 | `TotpSecretCipherTest` — AAD·키 불일치 복호 실패, `v1:` 왕복, 복호 실패 시 카운터 미증가(`TotpLoginServiceIntegrationTest`) |

추가 시험: RFC 벡터(`TotpCodeVerifierTest`·`Base32Test`), `OtpAuthUriTest`(인코딩·QR 디코드 왕복), API 상태 코드(`MemberTotpApiControllerTest`), 감사 행(`MemberTotpAuditIntegrationTest` — 성공 1행, 재인증 실패·반복 해제 0행), V17 적용(기존 Flyway 시험 관례), prod 설정 검증, 브라우저 시험 1건(등록 → 재로그인 → 2단계 통과, `-PbrowserTests=only`).

## 6. 리스크
| # | 리스크 | 대응 |
|---|---|---|
| R1 | 인가 정책 변경(`/admin/login/totp` 규칙 추가) | 4단계 별도 고지·승인. 기본 거부 유지 |
| R2 | 프로바이더 빈 등록으로 전역 관리자 구성이 바뀜 — 이후 `AuthenticationProvider` 빈이 하나 더 생기면 전역 등록이 조용히 꺼진다(F16) | FM1이 실제 체인에서 `TOTP_PENDING` 발급을 고정 → 회귀 시 실패. `config/CLAUDE.md`에 경고 |
| R3 | 세션 ID 미교체 — 중간 세션 쿠키 탈취 시 피해자 코드 입력 후 승격 세션 공유 | HttpOnly·5분 만료로 수용, 문서화 |
| R4 | 암호화 키 분실·교체 | 복구 코드·ADMIN 해제로 재등록, 키 교체 절차는 후속 |
| R5 | 롤백 시 2단계가 조용히 꺼짐 | `docs/deployment.md` 롤백 주의 |
| R6 | 6자리 코드 대입 | TOTP 전용 카운터(2단계·해제 재인증, 재로그인·재설정으로 리셋 안 됨) 5회 → 30분 잠금. 하루 최대 240회 ≈ 성공 확률 7×10⁻⁴ — 수용, 문서화 |
| R8 | 로그인 진행 중 등록 커밋 경합에서 옛 세션 ID로 먼저 시작한 요청이 완전 권한 컨텍스트를 지연 로딩하는 밀리초 창(결정 1) | 공격자가 계정 주인의 등록 시점을 정할 수 없고 성공 처리기가 곧바로 무효화 — 수용, 문서화 |
| R7 | 세션 보유자가 재인증 오답으로 계정을 고의 잠금 | 세션 보유자는 이미 더 큰 조작 가능 — 수용 |

## 7. 범위 밖 (후속 후보)
- 2단계 필수화, 복구 코드 재발급(현재는 끄고 다시 켜기), 암호화 키 교체 절차, 신뢰 기기 기억, WebAuthn.

## 개정 이력
- v1 (2026-11-14): 최초 작성.
- v2 (2026-11-14, 계획 리뷰 1라운드 codex no-ship 10건 전부 수용):
  - v2 변경: 중간 상태 재확인은 실패 횟수를 리셋하지 않음(두 요소 통과 시에만 리셋) — 재로그인으로 대입 제한 무력화 방지(지적 1).
  - v2 변경: fresh 스냅샷에 2단계 사용 여부 포함·토큰 종류와 대조, 등록 확정 시 본인 전 세션 만료(현재 포함, 기존 이벤트 재사용 F20)(지적 2).
  - v2 변경: 2단계 판정 서비스는 커밋 완료 결과만 반환, 컨텍스트 저장은 그 뒤(지적 3).
  - v2 변경: 등록 확정·본인 해제의 재인증 오답도 로그인 실패 카운터에 반영(지적 4).
  - v2 변경: 2단계 판정과 실패 증가·잠금 결정을 회원 행 잠금 아래 한 트랜잭션으로(지적 5).
  - v2 변경: 등록 대기 상태에 memberId·enrollmentId·발급 시각 바인딩, 불일치 409(지적 6).
  - v2 변경: 비밀값을 `member` 컬럼에서 별도 테이블 `member_totp`로 분리(지적 7, F17).
  - v2 변경: 감사 대상 id를 반환 DTO로 전달(지적 8, F19).
  - v2 변경: otpauth 레이블·issuer 콜론 정책, RFC 3986 인코딩, QR 디코드 왕복 시험, 실제 앱 확인은 7단계 사용자 휴대폰(지적 9).
  - v2 변경: 프로바이더 전역 등록 조건 출처(F16)·실제 체인 시험(지적 10).
  - v2 변경(자체): 복호 실패는 실패 카운터를 올리지 않음.
- v3 (2026-11-14, 계획 리뷰 2라운드 codex no-ship 4건):
  - v3 변경: 승격 저장은 세션 생성 금지 저장소 + 저장 뒤 세션 ID·레지스트리 재확인(지적 2 수용).
  - v3 변경: TOTP 실패 카운터를 `member_totp.failed_attempts`로 분리 — 비밀번호 재설정이 `failed_login_count`를 리셋해도 대입 제한 유지(지적 3 수용, F22).
  - v3 변경: 감사는 `@AdminActionLogged` 대신 실제 변경 시에만 AFTER_COMMIT 이벤트로 명시 기록(지적 4 수용, F23).
  - 기각(부분): 지적 1 "등록 경합에서 완전 권한 선공개 창" — 새 세션 ID는 로그인 요청 안에서 만들어지고 응답 전에는 아무도 모른다(F21). 옛 ID로 먼저 시작한 요청의 지연 로딩 밀리초 창만 남아 R8로 수용하고, FM3에 "거부 후 쓸 수 있는 세션·활성 레지스트리 항목 없음" 단언을 추가했다.
- v4 (2026-11-14, 계획 리뷰 3라운드 codex no-ship 2건 전부 수용):
  - v4 변경: 운영 Compose·CI prod-smoke에 암호화 키 전달 작업 추가, S8에 prod-smoke 기동 포함(지적 1).
  - v4 변경: 등록 대기 발급·확정을 세션 뮤텍스로 직렬화(검사~커밋~정리), 동시 실행 시험 추가(지적 2).
- v5 (2026-11-14, 계획 리뷰 4라운드 codex no-ship 2건 전부 수용):
  - v5 변경: 복구 코드를 50비트 → 120비트(Base32 24자)로 — SHA-256 저장 유지 근거를 NIST 112비트 기준으로(지적 1).
  - v5 변경: TOTP 코드 화면 핸들러에 `hasAuthority('TOTP_PENDING')` 직접 선언, 인가 컨벤션 시험을 이 경로·표현식 조합만 인정하도록 확장 + 반례 시험(지적 2).
- v6 (2026-11-14, 계획 리뷰 5라운드 codex no-ship 1건 수용):
  - v6 변경: 등록 확정·본인 해제는 행 잠금 직후 상태·역할 재확인, 실패 시 판정·카운터·변경 없이 409 + 이미 인가된 동시 요청 시험(지적 1).
- 6라운드 (2026-11-14): codex ship — 실질 지적 없음.

---

# 구현·검증 결과 (2026-11-14)

## Context
이슈 #251(deferred → 사용자 착수 지시). 정찰 직후 사용자 결정 5건(§0) → 계획 리뷰 6라운드(no-ship 5회, 지적 19건 중 18 수용·1 부분 기각 → 6라운드 ship) → 승인 → 구현. 브랜치 `feat/totp-two-factor`.

## 핵심 확정 사항 (계획과 달라진 점 포함)
- 계획대로: 역할 권한 없는 중간 상태(`TOTP_PENDING`, 프로바이더에서 생성), 코드 화면 + 회원 행 잠금 한 트랜잭션 판정·커밋 뒤 승격(세션 생성 금지 저장소·전후 재확인·세션 ID 유지), TOTP 전용 실패 카운터, 등록 시 본인 전 세션 만료, 재인증 실패 집계, 행 잠금 직후 상태 재확인, 세션 뮤텍스 직렬화, AFTER_COMMIT 감사 이벤트, AES-GCM(AAD 회원 id), 120비트 복구 코드, ZXing core만.
- **변경 1 — 이미 쓴 코드(REUSED)는 실패로 세지 않음**(구현 중 발견, 사용자 결정 2026-11-14): 등록 확정이 그 스텝을 쓰고 본인 세션을 끊으므로, 같은 30초 안에 다시 로그인하면 앱에 같은 코드가 떠 있어 "오답 + 실패 1회"가 됐다. 정답이지만 `step <= last_used_step`이면 `REUSED` → 통과 없음·카운트 없음·"다음 코드를 기다리세요" 안내(`?reused`). 재사용은 어차피 통과하지 못해 대입에 도움이 안 된다. 본인 해제에서도 세지 않는다.
- **변경 2 — FK `ON DELETE CASCADE`**: 앱에는 회원 하드 삭제가 없고(grep 확인), 시험 정리(`TestMembers.delete`)가 회원 행을 지우므로 막히지 않게 했다(`notification`과 같은 방식).
- **변경 3 — ADMIN 전용 상태 조회 `GET /admin/api/members/{id}/totp` 추가**: 관리자 상세 모달의 "사용 중/미사용" 표시용(계획 API 표에 없던 읽기 경로, ADMIN 캐치올 + `hasRole('ADMIN')`, URL 규칙 추가 없음).
- **변경 4 — 복구 코드 로그인 뒤 이동**: 계획의 "플래시 안내" 대신 `/admin/member/settings?recoveryCodeUsed`로 보내 남은 개수와 재등록 안내를 보인다(대시보드에 플래시 표시 장치가 없음).
- **변경 5 — 이름**: 계획의 `AdminAuthentications` 대신 `TotpAuthorities`(`PENDING`·`isPending`·`isFullyAuthenticated`)에 모았다. 코드 화면 컨트롤러는 페이지 컨트롤러 관례(`com.cms.admin` 스캔)에 따라 `admin.member.controller.TotpLoginController`.
- 400 코드는 `INVALID_REQUEST`(`InvalidRequestException` — 기존 `changeMyPassword`와 같음, 계획의 `VALIDATION_ERROR` 표기 정정).

## 구현 파일
- 스키마·설정: `V17__member_totp.sql`, `build.gradle`(ZXing core 3.5.4), `application.yml`·`application-dev.yml`(`cms.totp.*`), `docker-compose.prod.yml`·`scripts/_prod-env-guard.sh`·`scripts/ci/_prod-ci-common.sh`·`.env.example`(키).
- 순수 로직 `admin/member/totp/`: `Base32`, `TotpCodeVerifier`, `TotpSecretCipher`, `RecoveryCodes`, `OtpAuthUri`, `TotpQrCodes`, `TotpProperties`, `TotpConfig`.
- 도메인·서비스: `MemberTotp`, `MemberTotpRecoveryCode`, 두 리포지토리, `MemberRepository.lockByIdIfActive`, `TotpCredentialChecker`, `MemberTotpService`, `TotpEnrollment`, `MemberTotpAuditEvent`·`Listener`, `AdminActionTypes`(TOTP_ENABLE·DISABLE·RESET).
- 인증 배선 `config/auth/`: `TotpAwareAuthenticationProvider`, `TotpAuthorities`, `TotpPendingState`, `TotpLoginService`, `TotpSessionPromoter`, `LoginFailureService`(스냅샷 `totpEnabled`·`checkActive`·`lockForSecondFactorFailures`), `CustomUserDetails(Service)`, `VisitLoggingAuthenticationSuccessHandler`, `LoginSessionMutexFilter`, `SecurityConfig`, `RateLimitFilter`·`PublicTransferInterceptor`.
- 웹·화면: `TotpLoginController`, `MemberTotpApiController`, 요청 DTO 2종, `AdminMainController`(만료 안내), `login-totp.html`, `admin-settings.html`(카드), `admin-manage.html`(상태·해제), `log/manage.html`(라벨).
- 문서: `config/CLAUDE.md`, `member/CLAUDE.md`, 루트 `CLAUDE.md`, `docs/deployment.md`, `docs/migration-guide.md`, `docs/development-workflow.md`, `plan/README.md`.

## 검증 결과
- 새 시험: `Base32Test`(9)·`TotpCodeVerifierTest`(10)·`TotpSecretCipherTest`(4)·`RecoveryCodesTest`(3)·`OtpAuthUriTest`(6)·`TotpConfigTest`(3)·`BaselineSchemaTest#totpTables`·`TotpLoginServiceIntegrationTest`(8)·`MemberTotpServiceIntegrationTest`(11)·`TotpPendingAuthorizationIntegrationTest`(9 — S9 재설정 뒤 2단계 유지 포함, 코드 리뷰 마무리 때 추가)·`TotpSessionPromoterTest`(5)·`MemberTotpApiIntegrationTest`(5)·`TotpLoginBrowserTest`(1), 기존 시험 보강(`VisitLoggingAuthenticationSuccessHandlerTest` +4, `LoginFailureServiceTest` +1, `RateLimitFilterTest` +1, `PublicTransferLimitIntegrationTest` +1, `AdminEndpointAuthorizationConventionTest` +1).
- 변이 실험(모두 의도한 시험이 실패 → 원복): 판정의 행 잠금 제거(1건), 복호 실패를 오답으로(1건), 등록·해제의 잠금 직후 상태 재확인 제거(1건), 프로바이더 빈 제거(7건 중 6건), 대기 발급의 세션 뮤텍스 제거(1건). 면제 판정 2종은 수정 전 RED 확인.
- 영향 범위 시험: config·member·permission·log·session·publicweb.support·컨벤션·스키마 1,041건 통과(재사용 구분 반영 뒤 회원·인증 492건 재통과). 전체 시험(2026-11-14): `./gradlew test -PbrowserTests=exclude` 3,078건 통과·실패 0·건너뜀 12(291개 클래스), `-PbrowserTests=only` 111건 통과·실패 0. 코드 리뷰 마무리(2026-11-16, S9 시험 추가 뒤): `-PbrowserTests=exclude` 3,079건 통과·실패 0·건너뜀 12.
- 실기(dev, `bootRun` + Playwright, 2026-11-14): 미등록 로그인 → `/admin`(회귀 없음) · 설정 카드 등록 시작 → QR(240px 로드, CSP 차단 없음)·설정 키 · 틀린 비밀번호 400 안내 · 확정 → 복구 코드 10개 · DB: `v1:` 암호문 67자, 복구 코드 10행, 로그인 실패 1→0, 감사 `TOTP_ENABLE` 1행 · 기존 세션 만료(다음 요청 로그인 302) · 재로그인 → `/admin/login/totp` · 중간 상태에서 `/admin/member/manage` 302→코드 화면, `/admin/api/members/me`·`/admin/api/members`·`/admin/api/banners` 401 · 틀린 코드 `?error` 안내 · 올바른 코드 → 대시보드, 완전 인증의 코드 화면 403 · 로그아웃 → 소문자·하이픈 없는 복구 코드 로그인 → `/admin/member/settings?recoveryCodeUsed`(남은 9개 안내) · 회원 9(비활성 MANAGER)에 임시 등록 행 → 상세 "사용 중"+[해제] → 확인 → 행 삭제·감사 `TOTP_RESET`(수행자 1·대상 9) · 본인 상세는 해제 버튼 없음 · 본인 해제(복구 코드+비밀번호) → 행 0개·감사 `TOTP_DISABLE` · 해제 뒤 로그인 `/admin` 직행(원상 복구). 스크린샷 `docs/screenshots/auth/totp-01~05`, `docs/screenshots/member/totp-06~07`.
- **실제 인증 앱 확인**(2026-11-16, 사용자 직접): iPhone 16 Pro의 **Google Authenticator**(앱 안 QR 스캔)로 등록 → 재로그인 2단계 통과 확인(앱 버전은 기록하지 않음). iOS 기본 카메라로 찍으면 Apple **암호** 앱이 받아 "확인 코드를 추가할 암호 선택" 화면을 띄운다 — 확인 코드를 기존(또는 새로 만든) 계정 항목에 붙이는 iOS 동작이며 `otpauth://` 표준 그대로라 앱에서 바꿀 수 없다. 패스키(WebAuthn)와는 별개다.

- 코드 리뷰(2026-11-16, `code-review-loop`): 기본 `review` 1라운드 지적 0건 → 위험 변경이라 실패 모드·초점을 붙인 `adversarial-review` 1라운드 approve·지적 0건(총 2라운드, 수용·기각·보류 0). 마무리 실패 모드 대조에서 S9를 직접 고정하는 시험이 없음을 확인해 시험만 추가했다. FM3은 계획의 시험 이름(`TotpEnrollmentRaceIntegrationTest`·`TotpPromotionLogoutRaceTest`) 대신 성공 처리기 단위 시험·등록 API 통합 시험·`TotpSessionPromoterTest`로 나눠 고정했다.

## 이슈
- Python으로 고친 `.sh` 두 개가 Windows에서 CRLF로 저장됐다(`.gitattributes`가 `*.sh eol=lf`) — LF로 되돌렸다. 시험 결과 확인 시 `-q`는 실패 줄을 출력하지 않아 XML(`build/test-results`)로 판정했다.
- 콘솔 오류 `favicon.ico` 404는 기존 의도 동작이다.

## 후속
- 등록(2026-11-16, 사용자 승인): #256 2단계 인증 필수화(역할별, `security`·`deferred`), #257 복구 코드 재발급(`enhancement`), #258 암호화 키 교체 절차(`security`·`ops`), #259 패스키(WebAuthn) 로그인(`security`·`deferred` — 실기 검증 중 iOS 암호 앱 흐름에서 나온 요구).
- 등록하지 않음: 신뢰 기기 기억.
