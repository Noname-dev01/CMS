# PLAN — 2단계 인증 필수화(ADMIN) (이슈 #256)

- 상태: v2 — 계획 리뷰 2라운드 ship, **사용자 승인 완료 (2026-11-18 — 인가·로그인 정책 변경 포함)**, 구현·실기 검증 완료(2026-11-19), PR 전
- 브랜치: `security/totp-required-admin`
- **위험 변경: 인증·인가**(새 중간 상태 권한·접근 규칙·성공 처리기 판정·인가 컨벤션), **외부 계약**(새 경로 1개(GET·POST), 본인 해제 API가 ADMIN이면 본인 전 세션 만료, 상태 응답 필드 추가), **동시성**(새 경로의 세션 뮤텍스 직렬화)
- 스키마 변경 없음, 새 의존성·설정 키 없음

## 0. 사용자 결정 (2026-11-18)

| 쟁점 | 결정 | 기각한 선택지 |
|---|---|---|
| 필수 대상 | **ROLE_ADMIN만**. MANAGER는 지금처럼 선택 | ADMIN·MANAGER 전원(MANAGER 분실 복구도 ADMIN 의존·시험 파급 확대), 설정 키(`cms.totp.required-roles` — 시험한 정책 ≠ 운영 정책 가능) |
| dev 시드 ADMIN | **dev도 강제 등록**(시드 코드 무변경, 첫 로그인 때 휴대폰 앱으로 등록) | 고정 비밀값으로 미리 등록(비밀값이 코드에 들어가고 강제 화면을 dev에서 보기 어려움) |

## 1. 정찰 요약 (코드 근거)

- 중간 상태 패턴: `TotpAwareAuthenticationProvider.createSuccessAuthentication`이 2단계 사용 회원에게 `{TOTP_PENDING}` 하나뿐인 토큰을 만든다. 모든 관리 게이트가 역할 권한을 보므로 따로 막지 않아도 거부되고, `SecurityConfig` 접근 거부 처리기(213~224행)가 `isPending`이면 화면 302(코드 화면)·API 401로 바꾼다.
- 성공 처리기 `VisitLoggingAuthenticationSuccessHandler.verifyFreshMemberState`: 스냅샷 `totpEnabled != pending`이면 거부, 중간 상태는 `checkActive`(실패 횟수 리셋 없음), 역할 비교는 중간 상태면 principal의 원래 역할.
- 등록: `MemberTotpService.startEnrollment`(세션 대기 `TotpEnrollment`, 10분) → `confirmEnrollment`(현재 비밀번호 + 코드, 회원 행 잠금, `stillAuthorized`는 principal(`CustomUserDetails`)의 **역할 권한**과 비교, 성공 시 본인 전 세션 만료 `AdminSessionRevokeEvent`). API는 `MemberTotpApiController`(`hasAnyRole('ADMIN','MANAGER')`, 세션 뮤텍스 안).
- 해제: 본인 `disable`(비밀번호 + 코드), ADMIN의 타인 해제 `resetByAdmin`(대상 세션 유지).
- 역할 승격(MANAGER→ADMIN)은 `AdminMemberService`가 `AdminSessionRevokeEvent`로 대상 세션을 만료한다(296~303행) → 다음 로그인에서 판정이 다시 돈다.
- 인가 컨벤션 `AdminEndpointAuthorizationConventionTest`는 `/admin/login/totp` + `hasAuthority('TOTP_PENDING')` 조합만 인정한다.
- "로그인 사용자" 면제 판정(`RateLimitFilter:78`·`PublicTransferInterceptor:64`)은 `TotpAuthorities.isFullyAuthenticated`.
- 시험: 실제 폼 로그인을 쓰는 파일 36개 중 ADMIN을 쓰는 파일 약 30개(브라우저 시험 15개는 `BrowserTestSupport.loginAsAdmin` 하나를 거친다, 통합 시험은 파일별). `TotpTestSupport.enroll`·`currentCode`가 이미 있다.

## 2. 설계 결정

### 결정 1 — 새 중간 상태 `TOTP_ENROLLMENT_REQUIRED`(역할 권한 없음)

- 프로바이더가 **ADMIN이고 2단계 미사용**이면 권한 `{TOTP_ENROLLMENT_REQUIRED}` 하나뿐인 토큰을 만든다(`TotpAuthorities.ENROLLMENT_REQUIRED`). 판정 규칙은 `TotpAuthorities.isRequired(Role)` 한 곳(코드 상수 — ADMIN만).
- 선택지: (A) 새 중간 상태(채택) / (B) 완전 권한을 주고 필터·인터셉터로 "등록 화면 외 차단".
- 이유: (A)는 기존 `TOTP_PENDING`과 같은 원리라 **새 게이트를 하나도 쓰지 않아도 모든 관리 경로가 기본 거부**된다(fail-closed). (B)는 허용 목록 하나를 빠뜨리면 2단계 없는 완전 ADMIN이 열린다(fail-open).

### 결정 2 — 강제 등록 화면 `/admin/login/totp-enrollment`(GET·POST, 서버 렌더링)

- **직렬화(v2)**: GET·POST 모두 `LoginSessionMutexFilter.LOGIN_PROCESSING`에 추가한다 — 이 필터는 `UsernamePasswordAuthenticationFilter` 앞에서 나머지 체인(지연 로딩되는 컨텍스트 조회·인가 판정·컨트롤러) 전체를 세션 뮤텍스로 감싸므로, 같은 세션의 다른 계정 재로그인이 인가 통과와 확정 사이에 끼어들 수 없다. 컨트롤러는 그 안에서 `TotpSessionPromoter.isLive(sessionId)`(레지스트리 항목 존재·만료 아님)와 레지스트리 principal 회원 = 요청 principal 회원을 확인하고, 어긋나면 세션 종료 후 `/admin/login`(코드 화면 `TotpLoginController`와 같은 순서). 세션이 없으면 감싸지 않는다(중간 상태는 항상 세션이 있다).
- `GET`: 세션 뮤텍스 안에서 `startEnrollment`로 대기를 발급해 세션에 두고 QR(PNG Base64)·설정 키를 그린다. `?expired`면 만료 안내.
- `POST`(`enrollmentId`, `code`, `currentPassword`, CSRF): 세션 뮤텍스 안에서 대기를 꺼내 **컨트롤러가 먼저** 대기 없음·회원 불일치·`enrollmentId` 불일치·10분 경과를 보고 `redirect:GET?expired`(새 QR). 그다음 `confirmEnrollment` 호출:
  - 성공 → 대기 제거, **완료 화면을 그 응답에서 렌더링**(복구 코드 10개 + "다시 로그인" 링크). 서비스가 이미 본인 전 세션 만료 이벤트를 냈으므로 다음 요청은 로그인으로 간다. 복구 코드를 세션·리다이렉트 파라미터에 두지 않는다.
  - `InvalidRequestException`(비밀번호·코드 불일치, 이미 쓴 코드) → **같은 대기로** 같은 화면을 오류 문구와 함께 다시 그린다(다시 스캔하지 않게).
  - `ConflictException`(상태 변경·이미 사용 중) → 세션 종료 후 `/admin/login-error`.
- 접근: URL 규칙 `hasAuthority('TOTP_ENROLLMENT_REQUIRED')`를 `/admin/login/totp` 규칙 옆(캐치올 앞)에, 핸들러에도 같은 `@PreAuthorize`. 인가 컨벤션 시험은 **이 경로와 이 표현식 조합만** 추가로 인정한다.
- 선택지: (A) 전용 서버 렌더링 화면(채택) / (B) 기존 JSON API의 `@PreAuthorize`·URL 게이트를 넓혀 새 상태도 허용하고 설정 화면 JS를 재사용.
- 이유: (B)는 `/admin/api/members/me/**`(상시 허용 게이트) 아래 일부 경로만 새 권한에 열어야 해 규칙이 경로 단위로 쪼개지고, 설정 화면은 사이드바·관리 레이아웃(완전 권한 전제)을 쓴다. (A)는 `login-totp.html`처럼 독립 화면이라 새 JS가 없고 서비스는 그대로 재사용한다.
- 현재 비밀번호를 다시 받는 이유: 중간 상태 세션 쿠키만 훔친 공격자가 자기 인증기를 등록하면 주인이 영구히 2단계를 통과하지 못한다(계정 탈취·잠금). 서비스 계약(`confirmEnrollment`)도 그대로 둔다.
- GET이 상태(세션의 대기)를 바꾸는 점: 교차 사이트 GET으로 대기를 바꿔도 피해는 "QR을 다시 스캔"뿐이고 DB 쓰기가 없어 수용한다.

### 결정 3 — 접근 거부 처리·"로그인 사용자" 판정을 두 중간 상태로 일반화

- `TotpAuthorities.isIntermediate(auth)` = PENDING 또는 ENROLLMENT_REQUIRED. `isFullyAuthenticated`는 둘 다 제외한다(공개 레이트리밋·전송 상한 면제를 받지 못하게).
- 접근 거부 처리기: ENROLLMENT_REQUIRED면 화면은 `/admin/login/totp-enrollment` 302, API는 JSON 401(문구 "2단계 인증 등록이 필요합니다."). PENDING은 기존 그대로.

### 결정 4 — 성공 처리기의 토큰 종류 대조를 세 가지로

- 기대 종류 = 스냅샷 `totpEnabled` → PENDING / 미사용이고 `isRequired(role)` → ENROLLMENT_REQUIRED / 그 외 → FULL. 실제 토큰 종류와 다르면 거부(기존 경합 방어를 그대로 넓힌 것 — 로그인 진행 중 승격·해제·등록 커밋).
- ENROLLMENT_REQUIRED는 PENDING처럼 `checkActive`(**실패 횟수 리셋 없음**)·principal 원래 역할 비교·방문 기록 없음(완료 로그인이 아니다 — 등록 뒤 코드로 다시 로그인할 때 기록). 세션 표지는 두지 않는다(등록 대기 자체가 10분 TTL·회원 대조를 가진다).

### 결정 5 — ADMIN 본인 해제 = 인증기 교체(해제 + 본인 전 세션 만료), ADMIN의 타인 해제는 유지 (v2 변경)

- `MemberTotpService.disable`: 판정·해제는 지금 그대로(비밀번호 + 인증 앱 코드 또는 복구 코드). 대상이 필수 역할(`isRequired(member.getUserType())`, 잠금 직후 재확인한 값)이면 **해제 커밋 뒤 본인 전 세션 만료**(`AdminSessionRevokeEvent`, 등록 확정과 같은 AFTER_COMMIT 재생 순서 — 세션 만료가 감사보다 먼저). 다음 로그인은 강제 등록 상태가 된다.
- 이유(리뷰 1라운드 지적 1): 본인 해제를 막으면 **인증기를 잃고 복구 코드는 가진 유일한 ADMIN**이 새 인증기로 옮겨 갈 수 없어 SQL 복구 조건이 넓어진다(복구 코드 재발급은 인증 앱 코드를 요구 — `MemberTotpService:183~197`, 재등록은 기존 행이 있으면 409). 해제 직후 세션을 끊으면 "2단계 없이 쓰는 완전 권한 ADMIN 세션"이 남지 않으므로 필수 정책이 유지된다. 별도 교체 API를 만들지 않아 표면이 늘지 않는다.
- MANAGER 해제는 지금처럼 세션을 끊지 않는다(필수 대상 아님).
- `resetByAdmin`은 그대로(분실 복구 수단). 해제된 대상 ADMIN의 현재 세션은 유지되고(이미 2단계를 통과한 세션) 다음 로그인에서 강제 등록된다.
- 상태 응답 `TotpStatusResponse`에 `required`(boolean) 필드 추가(하위 호환 추가). 설정 화면은 `required`면 끄기 영역 문구를 "인증기 교체 — 끄면 모든 세션이 끝나고 다음 로그인에서 새 인증기를 등록합니다"로 바꾸고, 성공하면 `/admin/login`으로 보낸다.
- 기각(v1안): ADMIN 본인 해제 409 — 위 복구 공백 때문에.

### 결정 6 — 기존 세션·부트스트랩

- 부트스트랩·dev 시드 코드는 바꾸지 않는다. 첫 로그인 → 비밀번호 → 강제 등록 → 다시 로그인 → 코드. `docs/deployment.md`에 첫 로그인 절차와 "유일한 ADMIN이 인증기·복구 코드를 모두 잃으면 기존 SQL 절차로 `member_totp` 행을 지우고 다음 로그인에서 다시 등록"을 적는다.
- 배포 시점의 미등록 ADMIN 완전 권한 세션: 요청마다 등록 여부를 다시 보지 않는다. 재기동이 메모리 세션을 끊는 것에 기댄다. 출처: Boot 4.0.8 `module/spring-boot-web-server/.../servlet/Session.java`의 `persistent` 기본값 `false`(리뷰 1라운드 codex 제시 — 직접 열람 미확인), 이 저장소 `application*.yml`에 `server.servlet.session.persistent` 설정 없음(grep), 컨테이너는 `build.gradle:18`이 재정의한 Tomcat 11.0.26. **출시 필수 확인(v2)**: 실기 검증과 `docs/deployment.md` 배포 절차에 "재기동 뒤 재기동 전 ADMIN 세션 쿠키로 `/admin` → 로그인 302"를 둔다 — 실패하면 배포를 멈추고 원인(세션 영속 설정)을 제거한다. 유휴 30분은 최대 생존 시간이 아니므로 시간 경과에 기대지 않는다.

### 결정 7 — 시험 지원 코드

- `BrowserTestSupport`: `enableFormLogin`이 ADMIN 회원이면 `TotpTestSupport.enroll`로 등록해 비밀값을 보관하고(회원 id → 비밀값 맵), `loginAsAdmin`은 ADMIN이면 비밀번호 제출이 `/admin/login/totp`로 302인지 확인한 뒤 코드를 제출해 `/admin` 302를 확인한다. **같은 회원이 30초 안에 다시 로그인하면 `REUSED`**가 되므로 로그인 직전 `member_totp.last_used_step`을 현재 스텝 − 2로 되돌린다(시험 전용 조작).
  - 암호기: 시험 컨텍스트의 `TotpSecretCipher` 빈. 정적 도우미라 호출부가 빈을 넘기거나, `BrowserTestSupport`의 주입 필드로 받는다 — 구현 때 기존 호출 형태를 덜 바꾸는 쪽(구현 결과에 기록).
- MockMvc 통합 시험(ADMIN 폼 로그인): 파일별로 (a) 로그인 동작 자체를 보는 시험은 ADMIN에 등록 후 두 단계로 바꾸거나 기대값을 강제 등록 302로 고친다, (b) 로그인이 수단일 뿐이면 같은 두 단계 도우미를 쓴다. 기대값을 바꾸는 경우 이유를 결과 문서에 남긴다.
- 기존 시험이 "ADMIN + 2단계 미사용 → 완전 로그인"을 단언하는 곳(예: `TotpPendingAuthorizationIntegrationTest`의 미사용 대조군)은 MANAGER로 옮기고 ADMIN은 새 상태 단언을 추가한다.

## 3. 변경 파일 (예상)

- `config/auth/TotpAuthorities`(상수·`isRequired`·`isIntermediate`·`isFullyAuthenticated`), `TotpAwareAuthenticationProvider`, `LoginSessionMutexFilter`(대상 경로 추가), `VisitLoggingAuthenticationSuccessHandler`, `config/SecurityConfig`(규칙 1줄·거부 처리기)
- `admin/member/controller/TotpEnrollmentLoginController`(신규, `@AdminPage`), `admin/member/service/MemberTotpService`(`disable`의 ADMIN 세션 만료, `status`의 `required`), `MemberTotpApiController`(`TotpStatusResponse.required`)
- 템플릿 `admin/login-totp-enrollment.html`(등록·오류·만료), `admin/login-totp-enrollment-done.html`(복구 코드), `admin/member/admin-settings.html`(ADMIN 끄기 문구·성공 후 로그인 이동)
- 시험: 아래 §5, 지원 코드 `BrowserTestSupport`·`TotpTestSupport`, 컨벤션 `AdminEndpointAuthorizationConventionTest`
- 문서: `config/CLAUDE.md`(접근 규칙 표·2단계 절), `admin/member/CLAUDE.md`(선택 → ADMIN 필수), 루트 `CLAUDE.md`(보안 규칙 2단계 줄), `docs/deployment.md`(첫 로그인·분실 복구), `docs/development-workflow.md` 위험 지점 표(새 컨트롤러)

## 4. 성공 기준

1. 2단계 미사용 ADMIN이 비밀번호로 로그인하면 `/admin/login/totp-enrollment`로 302, 그 세션으로 `/admin`·`/admin/member/settings`·`/admin/api/**`·`/admin/login/totp`는 화면 302(등록 화면)·API 401, 완전 권한 없음.
2. 등록 화면에서 비밀번호 + 코드로 확정하면 복구 코드 10개가 그 응답에만 보이고, 그 세션은 다음 요청에서 로그인으로 간다. 다시 로그인하면 코드 화면(`TOTP_PENDING`) → 코드 → `/admin`.
3. MANAGER(미사용)는 지금처럼 바로 `/admin`. MANAGER(사용)도 지금처럼 코드 화면.
4. ADMIN의 본인 해제(복구 코드 포함)는 해제 + 본인 전 세션 만료이고 다음 로그인은 강제 등록 상태다. MANAGER 해제는 세션 유지. ADMIN의 타인 해제는 그대로 된다.
7. 재기동 뒤 재기동 전 ADMIN 세션 쿠키는 로그인으로 간다(실기 검증 필수 항목).
5. 중간 상태(두 종류 모두) 요청은 공개 레이트리밋·전송 상한 면제를 받지 않는다.
6. 전체 시험(`-PbrowserTests=exclude`·`only`) 통과.

## 5. 실패 모드와 고정 시험 (v2: 6개 — FM6은 리뷰 지적으로 추가)

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| FM1 | 강제 등록 상태가 새 경로 외 어딘가에서 완전 권한처럼 통과(게이트 누락·`isFullyAuthenticated` 미갱신) | `TotpEnrollmentRequiredAuthorizationIntegrationTest`(신규, 실제 필터 체인): 대표 화면·API·`/admin/login/totp`·공개 면제 판정 단언 + 컨벤션 시험 반례 |
| FM2 | 로그인 진행 중 승격·등록·해제 커밋 경합으로 기대 종류와 다른 토큰이 세션에 남음 | `VisitLoggingAuthenticationSuccessHandlerTest`에 세 종류 × 스냅샷 불일치 표 시험 |
| FM3 | 등록 화면 POST 오류 처리 — 비밀번호 오답 뒤 새 QR이 발급돼 사용자가 다시 스캔해야 함, 또는 409를 오답으로 처리 | `TotpEnrollmentLoginControllerIntegrationTest`(신규): 오답 → 같은 `enrollmentId` 유지·`failed_login_count` +1, 만료 → `?expired`, 상태 변경 → 세션 종료 |
| FM4 | ADMIN 본인 해제 뒤 2단계 없는 완전 권한 세션이 남음(세션 만료 누락·해제 롤백 시 만료) | `MemberTotpServiceIntegrationTest`: ADMIN 복구 코드 해제 → 행 삭제 + 본인 세션 만료(레지스트리 `isExpired`), 판정 실패 시 만료 없음, MANAGER 해제 → 세션 유지 |
| FM6 | 등록 확정 대기 중 같은 세션의 다른 계정 재로그인 → A의 등록이 B의 세션에서 확정 | `TotpEnrollmentLoginControllerIntegrationTest`: 필터 대상 단언 + `ConcurrentFormLoginIntegrationTest` 방식(스파이로 확정 직전 정지 → 재로그인이 뮤텍스에서 대기함을 `Thread.State`로 확인) |
| FM5 | 시험 도우미가 같은 회원 30초 내 재로그인에서 `REUSED`로 간헐 실패 | `BrowserTestSupport.loginAsAdmin`의 `last_used_step` 되돌림 + 같은 회원 2회 연속 로그인 시험 1건 |

## 6. 리스크

- R1 시험 파급(약 30개 파일): 도우미로 모으고 기대값을 바꾸는 곳은 결과 문서에 이유를 남긴다.
- R2 유일한 ADMIN의 인증기·복구 코드 분실: 운영 SQL(기존 절차) 후 다음 로그인 재등록 — 문서화.
- R3 배포 시점 기존 세션(결정 6 — 출시 필수 확인으로 관리).
- R4 강제 등록 화면이 TOFU다 — 비밀번호를 가진 공격자가 주인보다 먼저 로그인하면 자기 인증기를 등록한다. 비밀번호 유출 자체가 이미 완전 탈취였던 현재보다 나빠지지 않는다(수용).

## 개정 이력

- v1 (2026-11-18): 최초 작성.
- v2 (2026-11-18, 계획 리뷰 1라운드 codex no-ship 3건 전부 수용):
  - v2 변경: 결정 5 — ADMIN 본인 해제 409 → "해제 + 본인 전 세션 만료"(인증기 교체). 복구 코드만 가진 유일 ADMIN의 자체 복구 경로 유지(지적 1).
  - v2 변경: 결정 2 — 새 경로 GET·POST를 `LoginSessionMutexFilter` 대상에 추가, 컨트롤러에서 레지스트리 생존·회원 일치 확인, FM6 경합 시험 추가(지적 2).
  - v2 변경: 결정 6 — 세션 영속 기본값 출처(codex 제시, 직접 열람 미확인)·Tomcat 11.0.26 명기, "재기동 뒤 옛 ADMIN 쿠키 → 로그인"을 출시 필수 확인으로(지적 3). 세션 제거 코드는 추가하지 않음(재기동이 끊는다는 사실의 확인으로 충분).
- 2라운드 (2026-11-18): codex ship — 새 실질 지적 없음(등록 확정의 principal 역할 대조·실패 카운터 커밋 순서·7.0.7 컨텍스트 지연 로딩이 뮤텍스 안에서 일어남을 확인).

---

# 구현·검증 결과 (2026-11-19)

## Context
이슈 #256(deferred → `suggestRoadmap`에서 사용자 선택). 정찰 뒤 사용자 결정 2건(§0: ADMIN만 필수, dev도 강제 등록) → 계획 리뷰 2라운드(1라운드 no-ship 3건 전부 수용 → 2라운드 ship) → 승인 → 구현. 브랜치 `security/totp-required-admin`.

## 핵심 확정 사항 (계획과 달라진 점 포함)
- 계획대로: 새 중간 상태 `TOTP_ENROLLMENT_REQUIRED`(프로바이더에서 생성, 필수 판정 `TotpAuthorities.isRequired` — ADMIN 코드 상수), 강제 등록 화면 GET·POST(서버 렌더링, 현재 비밀번호 재입력), 뮤텍스 필터 대상 추가 + `TotpSessionPromoter.isLiveFor`, 접근 거부 처리기·`isFullyAuthenticated`를 두 중간 상태로 일반화, 성공 처리기 토큰 종류 3분기 대조, ADMIN 본인 해제 = 해제 + 본인 전 세션 만료, 상태 응답 `required`, 설정 화면 문구·로그인 이동, 인가 컨벤션 시험의 "경로-표현식 짝" 일반화.
- **변경 1 — 완료 화면 전 세션 무효화**(실기 검증에서 발견): 서비스의 레지스트리 만료 표시만으로는 완료 화면의 CSS 요청이 `ConcurrentSessionFilter`에 걸려 로그인으로 302 → 스타일 없는 화면. 컨트롤러가 응답 전에 현재 세션을 무효화한다(`docs/troubleshooting.md`에 기록). 대기(비밀값)도 함께 사라지므로 별도 `removeAttribute`는 없앴다.
- **변경 2 — 시험 지원 코드 형태**: `BrowserTestSupport.enableFormLogin`·`loginAsAdmin`을 정적 → 인스턴스 메서드로(호출 형태 그대로) 바꿔 주입된 `TotpSecretCipher` 등을 쓴다. MockMvc 시험용 `TotpTestSupport.loginWithSecondFactor` 추가. 둘 다 로그인 직전 `last_used_step`을 현재 스텝 − 2로 되돌린다(FM5).
- **변경 3 — 기존 시험 조정 방침**: ADMIN 기능 자체를 보는 시험(세션 관리·2단계 API)은 등록 후 두 단계 로그인, 역할과 무관한 시험(알림 정리·잠금 해제·시간 만료 없음·remember-me 제거·코드 대기 대조군)은 MANAGER로 옮겼다. 성공 처리기 단위 시험의 ADMIN 완전 토큰 성공 경로는 MANAGER로, ADMIN 방문 기록은 `recordCompletedLogin` 직접 시험으로.
- 계획의 "약 30개 파일" 중 실제로 깨진 기존 시험은 7개 파일 17건이었다(브라우저 시험 15개 파일은 `loginAsAdmin` 한 곳으로 흡수).

## 구현 파일
- 인증·접근: `config/auth/TotpAuthorities`·`TotpAwareAuthenticationProvider`·`VisitLoggingAuthenticationSuccessHandler`·`LoginSessionMutexFilter`·`TotpSessionPromoter`, `config/SecurityConfig`.
- 계정: `admin/member/controller/TotpEnrollmentLoginController`(신규)·`MemberTotpApiController`(`required`), `admin/member/service/MemberTotpService`(해제 세션 만료·`required`·`ENROLLMENT_TTL` 공개).
- 화면: `admin/login-totp-enrollment.html`·`login-totp-enrollment-done.html`(신규), `admin/member/admin-settings.html`.
- 시험: 신규 `TotpEnrollmentRequiredAuthorizationIntegrationTest`(3)·`TotpEnrollmentLoginControllerIntegrationTest`(5), 보강 `VisitLoggingAuthenticationSuccessHandlerTest`(+5)·`MemberTotpServiceIntegrationTest`(+4)·`LoginSessionMutexFilterTest`(+1)·`AdminEndpointAuthorizationConventionTest`(+1)·`TotpLoginBrowserTest`(+2), 지원 `BrowserTestSupport`·`TotpTestSupport`, 조정 7개 파일.
- 문서: 루트 `CLAUDE.md`, `config/CLAUDE.md`, `member/CLAUDE.md`, `docs/deployment.md`(첫 로그인·배포 직후 필수 확인·복구·롤백), `docs/development-workflow.md`(위험 지점 표), `docs/troubleshooting.md`.

## 검증 결과
- 변이 실험(의도한 시험이 실패 → 원복): 성공 처리기의 강제 등록 대조 제거(FM2 "2단계 없는 ADMIN 완전 토큰" 1건), `isLiveFor`의 회원 대조 제거(FM6 1건). 그 밖의 새 동작은 시험 먼저(RED 확인): FM1 2건, 뮤텍스 필터 1건, 강제 등록 확정 5건, ADMIN 해제 세션 만료 1건, 완료 화면 CSS 1건(브라우저).
- 전체 시험(2026-11-19, 실기 검증 수정 전): `./gradlew test -PbrowserTests=exclude` 통과(exit 0 — 건수는 이어서 돈 브라우저 실행이 결과 XML을 덮어써 미기록, 직전 실패 회차 기준 3,149건), `-PbrowserTests=only` 115건 통과·실패 0. 실기 검증 수정(완료 화면 세션 무효화) 뒤에는 영향 시험(`TotpEnrollmentLoginControllerIntegrationTest`·`TotpLoginBrowserTest`)만 다시 돌렸다 — **PR 전 전체 시험 1회가 남았다**.
- 실기 검증(dev, playwright, 임시 ADMIN `verify-totp` — 끝나고 계정·감사·방문 기록 삭제로 원복): ① 비밀번호 → 강제 등록 화면(QR·설정 키) ② 그 상태에서 `/admin`·`/admin/member/settings`·`/admin/permission/manage`·`/admin/login/totp` → 등록 화면 302, `/admin/api/members/me`·`…/me/totp`·`/admin/api/members` → 401 ③ 비밀번호 오답 → 같은 QR·오류 문구, `failed_login_count` 1, 등록 없음 ④ 확정 → 복구 코드 10개(수정 뒤 스타일 정상), DB `v2:` 암호문·코드 10행·감사 `TOTP_ENABLE` ⑤ 다시 로그인 → 코드 화면 → 대시보드 ⑥ 보안 설정: 필수 안내·"끄고 인증기 교체하기" ⑦ **재기동 뒤 재기동 전 ADMIN 쿠키로 `/admin` → 로그인 화면(출시 필수 확인 통과)** ⑧ 복구 코드로 해제 → 확인창 → `/admin/login` 이동, 옛 세션 API 401, 등록 삭제·감사 `TOTP_DISABLE`, 다음 로그인 → 강제 등록 화면. 스크린샷 `docs/screenshots/auth/totp-06~08`(로컬 보관, git 무시).

- 전체 시험(2026-11-19, 코드 리뷰 마무리 — 모든 수정 반영 뒤, `cleanTest`): `./gradlew test -PbrowserTests=exclude` 3,149건 통과·실패 0·건너뜀 12, `-PbrowserTests=only` 115건 통과·실패 0.
- 코드 리뷰(2026-11-19, `code-review-loop`): 1라운드 codex `review` 지적 0건 → 위험 변경이라 2라운드 `adversarial-review`(FM1~FM6·리뷰 초점) **approve, 지적 0건**. 수용·기각·보류 0건.
- FM6 고정 방식(계획과 다름): 계획은 `ConcurrentFormLoginIntegrationTest` 방식(스레드 정지 + `Thread.State`)을 지정했으나, 필터가 인가부터 컨트롤러까지 감싼다는 사실은 그 시험이 이미 실제 스택으로 고정하므로 새 경로는 `LoginSessionMutexFilterTest`(GET·POST가 뮤텍스 안)와 `TotpEnrollmentLoginControllerIntegrationTest`(레지스트리 회원 교체 → 확정 안 함·세션 종료, 변이로 실패 확인)로 고정했다.

## 이슈
- 실기 검증 중 상태 확인용 `fetch`가 리다이렉트를 따라 등록 화면 GET을 다시 부르면서 새 대기가 발급돼, 열려 있던 화면의 `enrollmentId`가 낡았다. 설계대로("GET은 대기를 새로 발급")지만 사용자가 등록 화면을 새로고침하거나 다른 관리 주소를 열면 스캔한 QR이 무효가 된다 — 후속 후보로 모음.
- 브라우저 시험의 첫 단언(계산된 스타일·사후 `fetch`)이 결함을 놓쳤다(원인: 사후 요청은 무효화된 세션으로 나감). 응답 계측으로 바꿨다.

## 후속
- 이슈 #277 등록(2026-11-19, 사용자 승인): 강제 등록 화면 GET이 아직 유효한 대기가 있으면 재사용 — 위 "이슈" 첫 항목.
