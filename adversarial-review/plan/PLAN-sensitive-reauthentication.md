# PLAN — 민감 작업 재인증 (쿠키 자동 로그인 세션 차단)

> 상태: v10(2026-11-11) — 재인증 모달 + 세션 ID 교체·폐기 동기화 + 폼 로그인 경계 세션 뮤텍스. 적대적 리뷰 8라운드 ship, 사용자 승인, **구현·검증 완료(커밋 전)**
> 출처: 로드맵 68차 머리말 "남은 후속 후보" — 민감 작업 재인증(`fullyAuthenticated`). 로드맵 본문 항목 아님.
> **위험 변경: 인가** (기존에 허용되던 요청 일부를 새로 거부한다 — URL 접근 규칙(`SecurityConfig`)은 바꾸지 않음)
> **위험 변경: 인증·로그인 정책**(v5) — 비밀번호를 검증해 세션의 인증 종류를 바꾸는 **새 인증 경로** `POST /admin/api/members/me/reauthentication`
> **위험 변경: 동시성**(v7) — 세션 만료 전 경로와 재인증의 세션 ID 교체를 한 모니터로 직렬화(`AdminSessionService`)
> **위험 변경: 로그인 경로**(v9) — 세션이 있는 상태의 폼 로그인(`POST /admin/login`)을 그 세션의 뮤텍스 안에서 처리하는 필터 추가(`SecurityConfig` 필터 체인 변경 — URL 접근 규칙은 그대로)

## Context

"로그인 상태 유지"(#225, remember-me 영속 토큰 7일)로 **비밀번호 없이 쿠키만으로 인증된 세션**이 생겼다. 지금은 이 세션도 폼 로그인 세션과 똑같이 모든 작업을 할 수 있어, 쿠키를 탈취당하면 다음이 가능하다:

- 쿠키 폐기 뒤에도 남는 **백도어 계정 생성**(`POST /admin/api/members` — ADMIN 생성 가능)
- 다른 관리자 강등·잠금, 권한 교체, 정상 관리자 세션 강제 만료(탈취자만 남기기)
- **내 이메일 변경 → 비밀번호 재설정 메일을 탈취자가 받음 → 완전한 계정 탈취**(MANAGER 포함)

`com.cms.config` `CLAUDE.md` "로그인 상태 유지 — 수용한 한계"에 "쿠키 탈취 시 7일 노출(민감 작업 재인증 없음 — 후속 후보)"로 기록돼 있다.

## 정찰 결과 (코드 근거)

1. **인증 출처는 세션 내내 보존된다.** 쿠키 자동 로그인 세션의 인증은 `RememberMeAuthenticationToken`이고, `AdminSecurityService.refreshAuthentication`도 같은 타입으로 다시 만든다(`RememberMeLoginIntegrationTest` F5③ 고정). 폼 로그인 세션은 `UsernamePasswordAuthenticationToken`. → Spring의 `AuthenticationTrustResolver.isFullyAuthenticated`(익명도 remember-me도 아님)로 판정 가능. **스키마 변경 불필요.**
2. **인가 선언은 핸들러당 정확히 하나**(`AdminEndpointAuthorizationConventionTest`) — `@PreAuthorize("hasRole('ADMIN') and isFullyAuthenticated()")`로 합치면 이 컨벤션의 허용 형태를 바꿔야 하고, `@PreAuthorize`를 두 번 붙이는 것은 Spring이 거부한다.
3. 민감 핸들러(7개 — v3에서 비밀번호 변경 추가):
   | 핸들러 | 경로 | 역할 |
   |---|---|---|
   | `AdminMemberController#createAdmin` | `POST /admin/api/members` | ADMIN |
   | `AdminMemberController#updateAdminMember` | `PATCH /admin/api/members/{id}` | ADMIN |
   | `MemberPermissionController#replacePermissions` | `PUT /admin/api/members/{id}/permissions` | ADMIN |
   | `AdminSessionController#expireSession` | `DELETE /admin/api/sessions/{handle}` | ADMIN |
   | `AdminSessionController#expireMemberSessions` | `DELETE /admin/api/sessions?memberId=` | ADMIN |
   | `AdminMemberController#updateMyInfo` | `PATCH /admin/api/members/me` | ADMIN·MANAGER |
   | `AdminMemberController#changeMyPassword` | `PATCH /admin/api/members/me/password` | ADMIN·MANAGER |
4. **비밀번호 변경을 포함하는 이유(v3, 사용자 결정 2026-11-11)**: 현재 비밀번호를 요구하지만 오답은 400(`InvalidRequestException`)만 돌려주고 로그인 실패 횟수에 더하지 않는다(`AdminMemberService.changeMyPassword` — `LoginFailureService.recordFailure`는 `LockingAuthenticationFailureHandler`에서만 호출), 이 경로의 레이트리밋 규칙도 없다(`application.yml` `cms.rate-limit`). 쿠키 세션으로 횟수 제한 없는 추측 → 맞히면 변경 후 폼 로그인으로 차단 우회. 포함하면 추측은 폼 로그인의 5회 잠금을 거친다. `PASSWORD_EXPIRED`(관리자 변경 강제) 계정은 `CustomUserDetailsService.validateMemberStatus`가 `CredentialsExpiredException`을 던져 쿠키 자동 로그인 자체가 안 되고 복구는 재설정 메일이라 영향 없음. 호출 화면은 `member/admin-settings.html` 하나이며 실패 시 본문 `message`를 보여 준다.
   **제외**: 프로필 이미지(`/me/profile-image` PUT×2·DELETE)는 위험이 낮고 별도 API. 콘텐츠 삭제류(게시판·사이트·메뉴)는 계정 탈취와 성격이 달라 범위 밖(사용자 결정 2026-11-11).
5. **재로그인 경로는 이미 있다**: `GET /admin/login`은 로그인 상태에서도 폼을 보여 주고, 폼 로그인 성공은 항상 `/admin`으로 간다(`setAlwaysUseDefaultTargetUrl(true)`). 같은 브라우저에서 다시 로그인하면 `loginSuccess`가 옛 시리즈(쿠키·세션 속성)를 먼저 지운다(`reLoginWithoutCheck_dropsOldSeries`·`reLoginInSameSessionWithoutCookie_dropsSeriesFromSessionAttribute` 고정).
6. 화면: `admin-create`·`admin-manage`·`admin-my-info`·`admin-settings`는 실패 시 응답 본문 `message`를 보여 준다 → 서버 문구만으로 안내가 된다. `session/manage`(`errorMessage`가 403이면 고정 문구)·`permission/manage`(403 분기 고정 문구)는 403 문구를 하드코딩해 수정이 필요하다. `thymeleaf-extras-springsecurity6`가 이미 의존성에 있다(`sec:authorize`).
7. 기존 시험 파급: `RememberMeLoginIntegrationTest`의 두 시험이 **쿠키 자동 로그인 세션으로 민감 API를 부른다** — F5③ `refreshAuthentication_keepsRememberMeOrigin`(`PATCH /me`)·F4③⑦ `memberExpire_keepsCurrentDevice_evenAfterExpiredSiblingRequest`(`DELETE /sessions?memberId=`). 슬라이스 시험(`AdminMemberControllerTest` 등)은 `@WithMockUser`(완전 인증)라 영향 없음. `addFilters=false` 시험 2개는 이 컨트롤러들과 무관.

## 핵심 결정

### 쟁점 1 — 재인증 방식 (사용자 결정 2026-11-11)
- (a) 차단 + 다시 로그인: v1~v4 채택안. 구현·실기까지 끝났으나 **사용자가 불편을 문제 삼아 v5에서 (b)로 바꿨다**(2026-11-11) — 로그인 후 항상 `/admin`이라 원래 화면을 다시 찾아가야 하고, 권한 화면처럼 만든 초안이 사라진다.
- (b) **비밀번호 재입력 모달로 제자리 승격** ← v5 채택: 민감 API는 그대로 403 `REAUTHENTICATION_REQUIRED`(쟁점 2·3 불변). 화면 공용 스크립트가 이 응답을 받으면 그 자리에서 비밀번호 모달을 띄우고, 새 API로 현재 세션을 완전 인증으로 바꾼 뒤 **같은 요청을 다시 보낸다**. 화면 이동·초안 손실이 없다.
- **비용(수용)**: 새 인증 경로라 폼 로그인이 해 주던 보호를 이 API가 스스로 갖춰야 한다 — 쟁점 6.

### 쟁점 2 — 판정 위치
- (a) `@PreAuthorize` 식 확장(`hasRole('ADMIN') and isFullyAuthenticated()`): 컨벤션 시험의 허용 형태 변경 필요, 거부가 일반 `ACCESS_DENIED`라 화면이 구분할 수 없다.
- (b) URL 규칙(`SecurityConfig`)에 메서드+경로별 `fullyAuthenticated` 추가: 첫 일치 규칙 순서·메서드 구분 매처가 늘고, remember-me 거부는 진입점 람다가 일반 403으로 바꾸므로 역시 구분 불가. 인가 표의 URL 규칙 변경.
- (c) **표지 어노테이션 `@RequireFullAuthentication` + MVC 인터셉터** ← 채택: 핸들러 메서드에 붙은 표지를 인터셉터 `preHandle`이 보고, 완전 인증이 아니면 `ReauthenticationRequiredException`을 던진다 → `GlobalApiExceptionHandler`가 403 JSON `REAUTHENTICATION_REQUIRED`.
- **왜 (c)**: URL 규칙·인가 선언 컨벤션을 건드리지 않고(기존 선언은 "누가", 새 표지는 "어떻게 인증됐는가"로 직교), 전용 오류 코드로 화면이 안내를 구분할 수 있다. 인터셉터는 보안 필터(URL 게이트) 뒤·핸들러 호출(메서드 보안·`@Valid` 바인딩) 앞에서 돈다 → 권한 없는 역할은 기존대로 `ACCESS_DENIED`가 먼저이고, 쿠키 세션은 본문 검증 전에 거부돼 상태 변경이 없다.
- **판정 규칙(fail-closed)**: `SecurityContextHolder`의 인증이 `null`·익명·remember-me면 거부. `AuthenticationTrustResolverImpl.isFullyAuthenticated` 사용.
- 표지는 **메서드에만** 붙인다(클래스 수준 금지 — 같은 컨트롤러의 조회까지 막히지 않게). 인터셉터는 `HandlerMethod`의 메서드 어노테이션만 본다.

### 쟁점 3 — 응답 계약 (`api-conventions`)
- 상태 403(인증은 됐지만 이 작업에는 부족한 인증 — 401은 "인증 없음"이고 기존 화면 일부가 401을 로그인 이동으로 처리하므로 쓰지 않는다), 코드 `REAUTHENTICATION_REQUIRED`, 메시지 고정 문구 "로그인 상태 유지로 접속한 세션에서는 이 작업을 할 수 없습니다. 비밀번호로 다시 로그인한 뒤 시도해 주세요."
- Swagger: 7개 핸들러에 `@ApiResponse(403, "… 또는 재인증 필요(REAUTHENTICATION_REQUIRED)")` 보강.
- 거부 시 INFO 로그 1줄(회원 ID·메서드·경로 패턴) — 탈취 시도 추적용, 정상 사용자도 겪을 수 있어 WARN 아님. 감사 로그(`admin_action_log`)는 남기지 않는다(행위가 일어나지 않았다).

### 쟁점 4 — 화면 (v5 개정)
- **공용 스크립트 `static/js/admin/reauth.js`** — `CmsReauth.fetch(url, init)`: `fetch`를 그대로 부르고, 응답이 403이며 본문 `code === "REAUTHENTICATION_REQUIRED"`일 때만 비밀번호 모달(처음 필요할 때 DOM에 만든다 — 6개 템플릿에 마크업을 넣지 않는다)을 띄운다.
  - 확인 → `POST /admin/api/members/me/reauthentication`(CSRF 헤더는 `meta[name=_csrf]`) → 204면 모달을 닫고 **같은 `url`·`init`으로 한 번 다시 보낸 응답**을 돌려준다(재시도 결과가 또 재인증 403이어도 다시 묻지 않는다 — 무한 반복 방지).
  - 400 `REAUTHENTICATION_FAILED` → 모달 안에 서버 문구, 입력 비우고 대기(더 시도할 수 있는 오답). **401(잠금·비활성·폐기로 세션 종료 — 쟁점 6) → `/admin/login`으로 이동**(v6). 그 밖의 오류 → 모달 안 일반 문구.
  - 취소·닫기 → **원래 403 응답(복제본)**을 돌려줘 화면의 기존 처리(서버 문구 표시)가 그대로 돈다.
  - **늦게 도착한 403(v8)**: 문서 단위 **재인증 성공 세대 번호**를 둔다. `CmsReauth.fetch`는 원 요청을 보낼 때의 세대를 기억하고, 재인증 403을 받았을 때 **그 뒤 성공한 재인증이 이미 있으면 모달 없이** 원 요청을 한 번 재시도한다(재시도 상한 1회는 그대로).
  - **동시성(v6)**: 문서 단위로 진행 중 재인증 Promise **하나만** 둔다 — 그 사이 재인증 403을 받은 다른 호출은 같은 Promise를 기다린다(모달은 하나). 검증 요청이 나가 있는 동안 확인 버튼·Enter 제출을 막는다(중복 검증 없음). 성공하면 기다리던 **각 원 요청이 한 번씩만** 재시도하고, 취소하면 각자 자기 원래 403을 받는다. 검증 중에는 취소도 막는다(응답이 오면 결과대로 진행 — 서버에서 이미 승격됐을 수 있어서).
  - 대상 호출 7곳만 `CmsReauth.fetch`로 바꾼다(재시도 가능한 문자열 본문·본문 없음뿐 — `FormData`·스트림 본문은 대상이 아니다).
- 6개 화면의 안내 상자는 남기되 문구를 "…저장할 때 비밀번호를 한 번 더 확인합니다."로 바꾸고 로그인 링크를 뺀다(재로그인 유도 대신 모달).
- `permission/manage`·`session/manage`의 403 분기(v4)는 유지 — 모달을 취소했을 때 서버 문구를 보여 준다.

### 쟁점 5 — 회귀 방지 (v2 보강)
- **`FullAuthenticationConventionTest`** — 두 겹:
  1. **검사 대상 수집**: 계정·권한·세션 관리 컨트롤러(`AdminMemberController`·`MemberPermissionController`·`AdminSessionController`)의 **GET/HEAD가 아닌 모든 핸들러**를 수집하고, 각각 `@RequireFullAuthentication` 또는 **사유가 적힌 명시적 면제 목록**(시험 안 상수, 예: 프로필 이미지 PUT×2·DELETE — "위험 낮음, 별도 API")에 있어야 한다. 표지도 면제도 없는 새 쓰기 핸들러는 실패. 면제 목록에 있는데 핸들러가 사라졌거나 표지가 함께 붙은 경우도 실패(낡은 면제 방지).
  2. **승인 집합 고정**: 전체 `com.cms` 컨트롤러에서 표지가 붙은 핸들러 집합이 승인 목록과 정확히 같다(제거·오부착 검출). 클래스 수준 부착은 실패.
  - **반례 시험**: 시험 전용 가짜 컨트롤러(표지 없는 `POST`)를 검사기에 넣으면 실패를 보고함을 확인(`AdminEndpointAuthorizationConventionTest`의 반례 방식).
  - 한계: 대상 컨트롤러 목록 밖에 새 계정 관리 컨트롤러를 만들면 1은 보지 못한다 — 목록은 "계정·인증 상태를 바꾸는 컨트롤러"로 이 문서·`com.cms.config` `CLAUDE.md`에 적어 리뷰에서 확인한다.

### 쟁점 6 — 재인증 API (v5 신설, 위험 변경: 인증)
- **경로·계약**: `POST /admin/api/members/me/reauthentication` 본문 `{"password": "…"}`(`@NotBlank`) → 성공 204. `me` 하위 단수 자원(`/me/password`와 같은 형태 — `api-conventions`). URL 게이트는 기존 상시 허용 `/admin/api/members/me/**`(ADMIN·MANAGER)라 **`SecurityConfig` 변경 없음**, 인가 선언 `hasAnyRole('ADMIN','MANAGER')`. 새 컨트롤러 `AdminReauthenticationController`(`admin.member.controller`) — 컨벤션 시험의 `ACCOUNT_CONTROLLERS`에 넣고 `EXEMPT`(사유 "재인증 자체 — 표지를 붙이면 순환")로 둔다.
- **비밀번호 검증**: 미사용이던 `AuthenticationManager` 빈으로 `UsernamePasswordAuthenticationToken.unauthenticated(<현재 principal의 DB 정식 아이디>, password)`를 인증한다 — 폼 로그인과 같은 `DaoAuthenticationProvider`·`CustomUserDetailsService`(만료 자동 잠금 해제·상태 검사)·`BCryptPasswordEncoder`를 그대로 탄다. 요청 본문의 아이디는 받지 않는다(다른 계정 추측 경로 차단).
- **실패(v6 — 계속 시도 가능한 오답과 세션 종료를 구분)**:
  - `BadCredentialsException` → `LoginFailureService.recordFailure(userId, ClientIpResolver.resolve(request), 절단 URI)`(폼 로그인과 **같은 카운터**). `recordFailure`가 **"이번 실패로 잠겼는지"를 반환**하도록 바꾼다(기존 호출자 `LockingAuthenticationFailureHandler`는 반환값을 무시 — 동작 무변경). 잠기지 않았으면 400 `REAUTHENTICATION_FAILED`("비밀번호가 올바르지 않습니다."), **잠겼으면 세션 종료**(아래). 기록 자체가 예외면 ERROR 로그 + 400(폼 로그인 처리기와 같은 관용).
  - 그 밖의 `AuthenticationException`(잠김·비활성·변경 강제 — `CustomUserDetailsService`가 던짐) → **세션 종료**.
  - **세션 종료** = 요청 시작 때의 세션 무효화 + `SecurityContextHolder` 비움 + remember-me 쿠키 취소 → **401 `UNAUTHORIZED`**("다시 로그인해 주세요."). 잠금 전이 자체의 세션·토큰 폐기는 기존 `AdminSessionRevokeEvent`가 한다(이 요청의 세션은 필터를 이미 지나 그 만료가 이번 응답을 바꾸지 못하므로 직접 끝낸다).
  - 어느 경우든 **승격하지 않는다**.
- **이미 완전 인증인 세션(v6)**: 비밀번호만 같은 방식으로 확인(오답 집계 동일)하고 성공하면 204 — **세션 ID도 인증 객체도 바꾸지 않는다**(권한 상승이 없으므로 고정 방지 대상이 아니고, 시리즈 없는 세션의 만료 표시를 ID 교체로 지울 경로를 아예 만들지 않는다).
- **승격(remember-me 세션만, v7 재설계 — 5라운드 지적 1·2, 사용자 결정 "세션 ID 교체 + 폐기 동기화")**: 요청 시작 때 `request.getSession(false)`로 잡은 **그 세션 객체 S에만** 귀속한다. S의 뮤텍스(`WebUtils.getSessionMutex`)로 같은 세션의 재인증을 직렬화한 뒤:
  - **사실(실측)**: Spring Security 7.0.7 `SessionRegistryImpl`은 `SessionIdChangedEvent`에서 옛 항목을 **지우고 새로 등록**한다(`removeSessionInformation` → `registerNewSession`, `javap`로 확인) — 옛 항목의 만료 표시는 옮겨지지 않는다. v6의 "ID 교체 뒤 DB 재확인"은 DB로 드러나지 않는 폐기 두 가지를 놓쳤다: ⓐ 같은 기기(시리즈)를 보존하는 **회원 단위 만료의 형제 세션**(`AdminSessionService.expireSessionsFor(memberId, exceptSessionId)` — 호출 세션 ID만 제외, 시리즈 행은 남음) ⓑ **탈취 감지 후 토큰 삭제 실패**(만료 → 삭제 실패 → 만료, 행은 남고 `pendingTheftRevocations`만 막음).
  - **해법 — 만료와 ID 교체를 한 모니터로 직렬화**: `AdminSessionService`에 모니터 `REGISTRY_MUTATION`을 둔다.
    - **모든 만료 경로**는 이 모니터 안에서 레지스트리 조회와 `expireNow()`를 한다 — `expireByHandle`·`expireSessionsWithSeries`·`expireSessionsForUserId`·`expireSessionsFor`(2종), 그리고 지금 서비스 밖에서 직접 `found.info().expireNow()`하는 `AdminSessionManageService.expireSession`은 새 `AdminSessionService.expireByHandleIfActive(handle)`(모니터 안 재조회 + 만료)로 바꾼다. **모니터 안에서는 DB를 쓰지 않는다**(메모리 연산만 — 토큰 삭제 같은 DB 작업은 지금처럼 모니터 밖·만료 앞). 다만 상수 시간은 아니다(principal·세션 순회, 핸들 해시) — 전역 모니터라 서로 다른 회원의 만료·승격도 서로 기다린다. 관리자 세션 수 규모에서 수용하고, "지연이 없다"고 보장하지 않는다.
    - **재인증의 ID 교체** `AdminSessionService.changeSessionIdIfActive(request, oldSessionId, memberId)`도 같은 모니터 안에서: 레지스트리 옛 항목이 **없거나 만료됐거나 principal의 회원이 다르면 false**(교체 안 함), 활성이면 `request.changeSessionId()`. 컨테이너의 `HttpSessionIdListener` → `HttpSessionEventPublisher` → `SessionRegistryImpl`의 옛 항목 제거·새 등록은 **이 호출 안에서 동기로** 끝난다(기본 동기 이벤트 멀티캐스터 — 6라운드 리뷰가 Tomcat 11.0.26·저장소 설정으로 확인). **교체 뒤 검증(v8)**: Tomcat은 리스너 예외를 잡아 로그만 남기므로 정상 반환이 레지스트리 이동을 보장하지 않는다 → 같은 모니터 안에서 새 ID 항목이 없으면(옛 항목이 남았으면) **직접 옮긴다**(옛 항목 제거 → 같은 principal로 새 ID 등록 — 리스너가 했을 일과 같다. 리스너가 없는 MockMvc도 같은 상태가 된다). 옮긴 뒤 새 항목의 회원이 다르면 false.
    - 결과: 어떤 만료든 교체와 겹치지 않는다 — 교체보다 **먼저** 끝났으면 옛 항목이 만료 상태라 교체하지 않고 세션 종료(401), **나중**이면 이미 새 ID로 등록된 항목을 조회해 만료한다. 형제 세션 회원 단위 만료(ⓐ)·탈취 감지의 첫 만료(ⓑ)·역할·상태 변경·로그아웃·수동 만료가 모두 이 규칙 하나로 덮인다.
    - 수동 단일 만료의 경계: 관리자가 목록에서 본 핸들은 옛 세션 ID의 해시다. 교체 뒤 그 핸들로 만료하면 404(이미 다른 ID)다 — 다만 remember-me 세션(교체 대상)은 `revokeDevice`가 **시리즈로** 같은 기기 세션을 모니터 안에서 다시 찾아 만료하므로 교체된 새 ID도 끊긴다. 교체되지 않는 시리즈 없는 세션은 핸들이 그대로다.
  - ⓪ **인증 일치 사전 확인(v8 — 6라운드 지적 1)**: S의 `SPRING_SECURITY_CONTEXT` 속성의 인증이 **이 요청이 필터에서 읽은 인증 객체와 같다**(다른 탭의 폼 로그인이 이미 S의 인증을 바꿨으면 다르다). 다르면 승격 없이 409가 아니라 **세션 종료(401)** — 어느 인증이 맞는지 이 요청이 정할 수 없으므로 다시 로그인하게 한다.
  - ① 위 `changeSessionIdIfActive`가 false → 세션 종료(401).
  - ② **추가 방어 재확인(fail-closed)** — 폼 로그인 성공 처리기·자동 로그인 사후 검증과 같은 판정: `resetFailuresAndCheckActive` 스냅샷이 `ACTIVE`·현재 권한의 역할과 일치·인증 결과 principal의 해시와 일치, 현재 principal의 **시리즈 행이 있음**, **`!rememberMeServices.isTheftRevocationPending(userId)`**. 어긋남·예외 → 세션 종료(401).
  - ③ **새 `SecurityContext` 객체**(`SecurityContextHolder.createEmptyContext()`)에 인증 결과의 새 `CustomUserDetails` + **현재 principal의 시리즈를 옮겨**(기기 연속성) `UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities)`, `details` 보존. 공유 중인 기존 컨텍스트 객체는 수정하지 않는다.
  - ④ **S에 직접** `setAttribute(SPRING_SECURITY_CONTEXT, 새 컨텍스트)` — 저장소 기본값(`allowSessionCreation=true`)처럼 세션을 새로 만들 경로가 없다. 그 사이 다른 탭의 로그아웃으로 S가 무효화됐으면 `IllegalStateException` → 승격 없이 401(새 세션 생성 없음). 성공하면 현재 요청의 `SecurityContextHolder`도 새 컨텍스트로 바꾼다.
  - ⑤ **사후 일치 확인(v8 — 6라운드 지적 1)**: 여전히 S의 뮤텍스 안에서, 레지스트리의 **S 현재 ID 항목의 회원 = 이 회원**이고 S의 컨텍스트 속성이 **④에서 넣은 객체 그대로**인지 본다. 어긋나면(다른 탭 폼 로그인이 그 사이 다른 회원으로 등록·저장) S를 무효화하고 401 — 레지스트리와 실제 인증이 다른 회원인 세션이 남지 않는다. 근거: 폼 로그인의 ID 교체도 같은 세션 뮤텍스를 쓴다(Spring `AbstractSessionFixationProtectionStrategy`) → 폼 로그인 F는 이 구간 **앞**에서 교체했거나 **뒤**에서 교체한다. 뒤면 F의 등록·저장이 우리 것을 덮어 최종 상태가 F의 회원으로 일관된다. 앞이면 F의 등록·저장이 ⓪·⑤ 중 하나에서 드러나고(⓪ 전 저장 → ⓪, ⑤ 전 등록 → ⑤), ⑤ 뒤의 F 저장은 무효화된 S 대신 새 세션에 F의 회원으로 들어간다(F 자신의 정상 로그인).
  - **CSRF 토큰은 바꾸지 않는다**(세션 속성이라 ID 변경 뒤에도 같다 — 화면이 같은 헤더로 원 요청을 다시 보낼 수 있어야 한다).
  - 남는 창(수용): ①과 ④ 사이에 시작된 만료는 이미 새 ID 항목을 만료하므로 다음 요청에서 끊긴다 — 승격된 인증이 그 한 응답만큼 존재(기존 best-effort 만료 계약과 같다).
  - **뮤텍스 순서**: 항상 S의 세션 뮤텍스 → `REGISTRY_MUTATION`. 역순 획득 경로는 없다(만료 경로는 세션 뮤텍스를 잡지 않는다 — 6라운드 리뷰 확인).

### 쟁점 7 — 폼 로그인 경계 직렬화 (v9 신설 — 7라운드 지적, 사용자 결정 "폼 로그인 경계까지 뮤텍스로 묶기")
- **사실(바이트코드 확인, Spring Security 7.0.7)**: `AbstractSessionFixationProtectionStrategy.onAuthentication`은 `applySessionFixation`(ID 교체)**만** `WebUtils.getSessionMutex(session)` 안에서 하고, `RegisterSessionAuthenticationStrategy`는 그 밖에서 `request.getSession().getId()`를 읽어 `registerNewSession`을 부른다. 컨텍스트 저장·`rememberMeServices.loginSuccess`·성공 처리기는 세션 전략 뒤(`AbstractAuthenticationProcessingFilter`). 그래서 같은 세션의 다른 탭 폼 로그인 F가 **미리 읽은 옛 ID로 늦게 등록**하면 재인증 R의 ⓪·⑤를 모두 통과한 뒤 "실제 인증 B, 레지스트리 회원 A"가 남을 수 있다(7라운드). 같은 구조로 **두 탭의 동시 폼 로그인**도 지금 어긋날 수 있다(바이트코드 기반 추론, 재현은 안 함).
- **해법**: 새 필터 `LoginSessionMutexFilter`(`config/auth`, 빈이 아님)를 `UsernamePasswordAuthenticationFilter` **앞**에 둔다. `POST /admin/login`이고 **요청 시작 때 세션이 이미 있으면** `synchronized (WebUtils.getSessionMutex(session))` 안에서 나머지 체인을 실행한다 — ID 교체·레지스트리 등록·컨텍스트 저장·`loginSuccess`(옛 시리즈 정리)·성공 처리기(재확인·방문 기록)·실패 처리기(실패 집계)가 전부 재인증과 **같은 뮤텍스** 안이다. 재인증도 같은 `WebUtils.getSessionMutex`로 잡는다(같은 세션 객체 → 같은 모니터; Tomcat `StandardSession`은 같은 facade를 돌려준다, MockMvc는 같은 `MockHttpSession`). `synchronized`는 재진입이라 Spring 고정 방지 전략의 같은 뮤텍스 획득과 교착하지 않는다.
- **세션이 없는 로그인**은 감싸지 않는다 — 재인증은 기존 세션 S에만 귀속하므로 겹칠 상대가 없다(로그인이 새로 만든 세션은 재인증 요청이 아직 가질 수 없다).
- **결과**: F는 R의 구간 전체보다 앞이거나 뒤다. 앞이면 R의 ⓪이 바뀐 인증을 보고 401, 뒤면 F가 등록·저장 모두 현재 ID로 덮어 최종 상태가 F의 회원으로 일관된다. 두 탭 동시 로그인도 직렬화된다.
- **비용(수용)**: 같은 세션의 로그인 요청은 BCrypt·DB 시간 동안 서로·재인증과 줄을 선다(다른 세션·다른 요청 종류는 영향 없음). 뮤텍스 안 DB 사용은 세션 단위라 전역 경합이 아니다. 잠금 순서는 세션 뮤텍스 → `REGISTRY_MUTATION`(로그인 안의 기기 정리 `expireSessionsWithSeries`) — 재인증과 같은 방향, 역순 없음.
- **범위 밖(근거)**: 쿠키 자동 로그인(`RememberMeAuthenticationFilter`)도 세션 전략으로 등록하지만 **컨텍스트가 비어 있는 요청에서만** 돈다 — 재인증 대상 세션 S는 이미 인증 컨텍스트가 있어 같은 S에서 자동 로그인이 일어나지 않는다. 로그아웃은 S를 무효화하며 재인증 ④·⑤가 처리한다.
- **로그·감사**: 성공·실패 INFO 로그(아이디만). `admin_action_log`에는 남기지 않는다 — 폼 로그인 성공도 감사가 아니라 방문 기록이며, 실패 누적의 잠금 전이는 기존 `ACCOUNT_AUTO_LOCK` 감사가 남는다. 방문 기록(`visit_log`)도 남기지 않는다(새 로그인이 아니다).
- **쿠키 단독 직접 호출**: 세션이 없으면 CSRF 세션 저장소 때문에 기존 401(성공 기준 1 ⓑ와 같은 이유) — 정상 화면 흐름은 이미 세션이 있다.

## 과설계 경계 / 설계 제약
- "최근 N분 내 인증" 시간창·원래 화면 복귀 리다이렉트·별도 재인증 레이트리밋(기존 5회 잠금으로 충분)·재인증 전용 감사 유형 — 만들지 않는다.
- `SecurityConfig` URL 규칙·스키마·의존성 변경 없음. **로그인 정책은 바뀐다**(새 인증 경로 — v5).
- 페이지(HTML) 핸들러는 막지 않는다 — 화면은 보이고 변경 API만 거부(조회·검토는 쿠키 세션으로도 가능).

## 성공 기준
1. remember-me 인증으로 민감 API 호출 → 403 `REAUTHENTICATION_REQUIRED`, **민감 작업 대상 상태 불변**(회원 수·대상 회원의 역할·상태·이메일·이름·권한 행·권한 버전, 강제 만료 대상 세션의 생존·시리즈 행, 감사 로그 행 수). 경로(v4 — CSRF 저장소가 기본 `HttpSessionCsrfTokenRepository`이고 `CsrfFilter`가 remember-me 필터보다 먼저라 구분):
   - ⓐ **쿠키 단독 GET으로 복원한 remember-me 세션**과 그 세션의 CSRF 토큰으로 쓰기 → 403 `REAUTHENTICATION_REQUIRED`(브라우저의 실제 경로 — 화면을 연 뒤 저장). 호출자 자신의 인증 부수 효과(복원 GET의 토큰 교체·세션 등록·방문 기록)는 정상 자동 로그인이라 불변 대상이 아니다.
   - ⓑ **세션 없이 쿠키만으로 바로 쓰기**(CSRF 토큰 없음) → 기존 계약 그대로 401 `UNAUTHORIZED`(`ApiSecurityConfigTest`의 미인증 CSRF 실패와 같음), 대상 상태와 **remember-me 토큰 행 불변**(필터가 자동 로그인 전에 끝냄).
   - 시험에서 `.with(csrf())`는 ⓐ의 CSRF 통과 수단으로만 쓰고 ⓑ에는 쓰지 않는다(시험용 CSRF가 실제 정책 차이를 가리지 않게).
2. 같은 회원의 폼 로그인 세션 → 기존과 같은 성공 응답.
3. 쿠키 자동 로그인 세션에서 같은 브라우저로 폼 로그인(재인증) → 같은 API 성공(end-to-end, 실제 쿠키·실제 폼 로그인).
4. remember-me 세션의 비민감 작업(목록·상세 조회, 프로필 이미지, 게시글 등)은 그대로 성공.
4-1. remember-me 세션의 비밀번호 변경은 **현재 비밀번호가 맞든 틀리든** 403 `REAUTHENTICATION_REQUIRED`(정답·오답 구분 응답이 나가지 않음 — 추측 신호 차단), 비밀번호 해시·세션·토큰 불변.
5. 역할 부족이 먼저: remember-me MANAGER의 ADMIN 전용 민감 API → 기존 `ACCESS_DENIED`(재인증 코드로 바뀌지 않음).
6. 화면(v5): remember-me 세션에서만 6개 화면에 안내 상자. 저장하면 모달 → 정답이면 **같은 화면에서 저장 완료**(초안 유지·이동 없음), 오답이면 모달 안 문구, 취소하면 기존 재인증 문구(Playwright·브라우저 시험).
7. (v5·v6) 재인증 API: 정답 → 204·세션 ID 변경·인증 종류 `UsernamePasswordAuthenticationToken`·시리즈 보존, 이어지는 민감 API 성공. 오답 → 400 `REAUTHENTICATION_FAILED`·실패 카운트 +1·여전히 remember-me. **5회째 오답 → 그 응답이 401**·`LOCKED`·세션 무효·토큰 삭제. 계정 상태가 비활성(DB 직접 변경)이면 정답이어도 401·승격 없음. 이미 완전 인증인 세션은 204·세션 ID 그대로. 승격 뒤 로그아웃이 이 기기 시리즈를 지운다.

## 놓치기 쉬운 실패 모드 → 고정 시험
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 인터셉터 예외가 JSON 핸들러로 가지 않고 500/HTML이 된다(`Accept: text/html` 포함) | `SensitiveReauthenticationIntegrationTest` — Accept 헤더 변형에서도 403 JSON 코드 단언 |
| F2 | 거부가 본문 바인딩·서비스 실행 뒤에 일어나 상태가 바뀐다(인터셉터가 아니라 서비스 단 판정이었을 때), 또는 쿠키 단독 첫 호출 경로에서만 다르게 동작 | 같은 시험 — 핸들러마다 대상 상태 불변 단언(성공 기준 1의 범위), 잘못된 본문으로도 400이 아니라 403, ⓐ 복원 세션(403)·ⓑ 쿠키 단독 CSRF 없는 쓰기(401, 토큰 행 불변) 두 경로 |
| F3 | `refreshAuthentication`이나 재로그인 경로에서 인증 출처가 잘못 바뀐다(쿠키 세션이 완전 인증으로 승격 / 재로그인 후에도 계속 거부) | `RememberMeLoginIntegrationTest` F5③ 수정판(프로필 기본 이미지 PUT 뒤에도 remember-me 유지 → 이어서 `PATCH /me` 403) + 신규 재로그인 end-to-end 시험 |
| F4 | 새 민감 핸들러에 표지 누락·기존 표지 제거·클래스 수준 부착으로 조회까지 차단 | `FullAuthenticationConventionTest` |
| F5 | 인증 없음·익명이 통과(fail-open) | `FullAuthenticationInterceptorTest` 단위 — null·익명·remember-me 거부, 폼·Testing 토큰 통과, 표지 없는 핸들러·비 `HandlerMethod` 통과 |
| F6(v5) | 재인증 API가 무차별 대입 통로가 된다(오답이 잠금 카운터 밖) | `ReauthenticationApiIntegrationTest` — 오답마다 `failed_login_count` 증가, 5회째 `LOCKED`·세션 401·토큰 행 0 |
| F7(v5) | 승격이 세션 고정·기기 연속성을 깬다(세션 ID 그대로 / 시리즈 유실로 로그아웃이 기기를 못 지움) | 같은 시험 — 승격 전후 세션 ID 다름, principal 시리즈 = 쿠키 시리즈, 승격 뒤 로그아웃이 그 시리즈 행 삭제. 레지스트리 이동은 MockMvc가 `HttpSessionIdListener`를 부르지 않아 **실기(실제 Tomcat)**에서 세션 관리 화면으로 확인 |
| F8(v5~v7) | 비활성·잠금 상태나 **폐기 경합**인데 승격된다(ID 교체가 만료 표시를 지움) | `ReauthenticationApiIntegrationTest` — 정적: DB로 `LOCKED`/`DISABLED` 뒤 정답 → 401·승격 없음. **경합(결정적, `@MockitoSpyBean AuthenticationManager` — 비밀번호 확인 직후·ID 교체 전에 끼움)**: ⓐ 역할 변경(실제 `AdminMemberService.updateAdminMember`) → 401 ⓑ **같은 시리즈의 폼 로그인 세션 A가 본인 회원 단위 만료**(실제 `AdminSessionManageService.expireMemberSessions`, 현재 세션 = A — 시리즈 행 보존) → 재인증 중인 형제 B는 401·승격 없음 ⓒ **탈취 감지 + 토큰 삭제 실패**(기존 `theftWithDeleteFailure…` 구조, `RememberMeTokenRepository` 스파이) → 401. **재확인 지점(`resetFailuresAndCheckActive` 스파이)**에서 세션 무효화(다른 탭 로그아웃) → 401·**새 세션 없음**·인증 부활 없음. **교체·만료 직렬화 단위 시험** `AdminSessionServiceSwapTest` — 실제 `SessionRegistryImpl` + `changeSessionId()`가 `SessionIdChangedEvent`를 발행하는 시험용 요청(Tomcat 리스너 모사): 교체 전 만료 → false·교체 안 함, 교체 뒤 만료(옛 핸들·회원·시리즈·아이디 경로 각각) → 새 ID 항목 만료, 두 스레드가 모니터를 다툴 때 만료가 사라지지 않음(래치로 교체 진행 중에 만료 호출) |
| F10(v8·v9) | 다른 탭의 폼 로그인(다른 회원)과 재인증이 같은 세션에서 겹쳐 레지스트리 회원과 실제 인증이 어긋난다 | `ReauthenticationApiIntegrationTest` — **7라운드 순서 그대로(결정적)**: `@MockitoSpyBean SessionRegistry`로 폼 로그인 F가 `registerNewSession(id, B)`에 들어오면 래치로 멈추게 하고, 그 사이 같은 세션으로 재인증 R을 다른 스레드에서 보낸다 → ① F가 뮤텍스를 쥔 동안 R이 진행하지 못함(래치 대기 중 R 미완료 단언) ② F 완료 직후(R 재개 전) 세션 컨텍스트·레지스트리 현재 ID 항목·시리즈가 모두 B ③ R 재개 뒤 ⓪ 불일치로 **401·S 무효화**(쟁점 6의 세션 종료 정책 그대로 — B가 방금 로그인한 세션도 끝난다, fail-closed)·새 세션 미생성·인증 부활 없음. **B 로그인 유지와 후속 B 회원 단위 만료로 그 세션이 끊김**은 R이 끼지 않는 별도 시험(두 탭 동시 폼 로그인, F11)에서 확인. **변이**: 필터를 빼면 이 시험이 실패함을 확인. 추가로 ⓐ 재인증 진입 전 다른 회원 컨텍스트(⓪ 401) ⓑ ⑤ 직전 레지스트리 항목 변경(⑤ 401) |
| F11(v9) | 뮤텍스 필터가 로그인을 깨뜨린다(세션 없는 로그인 감쌈·교착·실패 처리 누락·다른 경로 감쌈) | `LoginSessionMutexFilterTest`(단위 — `POST /admin/login`+세션 있음만 감쌈, GET·다른 경로·세션 없음은 그대로, 같은 세션 두 요청이 직렬화됨) + 기존 `RememberMeLoginIntegrationTest`·`LoginFailure*`·`VisitLogging*` 전체 통과(폼 로그인 계약 회귀) + 두 탭 동시 폼 로그인(다른 회원) 결정적 시험 → 최종 컨텍스트·레지스트리 회원 일치 |
| F9(v5·v6·v8) | 모달 재시도가 요청을 중복·누락하거나, 취소 시 기존 처리(서버 문구)를 깨뜨리거나, 동시 403·연타로 검증이 겹치거나, **재인증 뒤 늦은 403이 모달을 다시 띄운다** | 브라우저 시험 `ReauthModalBrowserTest`(`BrowserTestSupport`) — 쿠키 복원 세션에서 권한 저장 → 모달 → 오답 문구 → 정답 → 저장 1회 반영·초안 유지 / 취소 → 기존 문구·저장 0회 / **재인증 403 두 건 동시 → 모달 1개·검증 요청 1회·원 요청 각 1회 재시도** / **확인 연타·Enter → 검증 요청 1회** / 5회째 오답 → 로그인 화면 이동 / **두 번째 403을 첫 재인증 완료 뒤 전달 → 모달 없이 1회 재시도** |

## 기존 시험 수정 (의도된 동작 변화)
- F5③ `refreshAuthentication_keepsRememberMeOrigin`: 자기수정 경로를 `PUT /me/profile-image`(JSON 기본 이미지 — `refreshAuthentication` 호출)로 바꾸고, 그 뒤 `PATCH /me`가 403 `REAUTHENTICATION_REQUIRED`임을 추가 단언(승격되지 않음).
- F4③⑦ `memberExpire_keepsCurrentDevice_evenAfterExpiredSiblingRequest`: 호출 세션을 **폼 로그인(remember 체크) 세션**으로 바꾸고 형제 세션을 그 쿠키의 자동 로그인으로 만든다 — 검증 대상(현재 기기 시리즈 보존·형제 만료·만료 세션 후속 요청이 시리즈를 지우지 않음)은 그대로.

## 작업 단계
1. `common/exception/ReauthenticationRequiredException` + `GlobalApiExceptionHandler` 핸들러(403 `REAUTHENTICATION_REQUIRED`).
2. `config/auth/RequireFullAuthentication`(메서드 대상) + `FullAuthenticationInterceptor` + 등록 `config/auth/FullAuthenticationWebMvcConfig`. 단위 시험 먼저(TDD).
3. 7개 핸들러에 표지 + Swagger 403 설명. 통합 시험·컨벤션 시험 먼저(TDD).
4. 기존 시험 2건 수정.
5. 조각 `reauth-notice.html` + 6개 화면 포함, 2개 화면 JS 403 분기.
6. 영향 범위 시험: `com.cms.config.*`·`com.cms.admin.member.*`·`com.cms.admin.permission.*`·`com.cms.admin.session.*`·`*ConventionTest` (공용 `GlobalApiExceptionHandler` 변경이므로 PR 전 전체 1회).
7. 실기: bootRun + Playwright — 체크 로그인 → 세션 쿠키 삭제(브라우저 재시작 모사) → 쿠키 복원 → 6화면 안내 확인 → 저장 403 문구 → 재로그인 → 저장 성공·원복, MANAGER 내 정보, 폼 로그인 세션 무안내 회귀.
7-1. (v5·v7·v9) `AdminSessionService` 모니터·`changeSessionIdIfActive`·`expireByHandleIfActive`(단위 시험 먼저) → `LoginSessionMutexFilter`·`SecurityConfig` 등록(단위·경합 시험 먼저) → 재인증 API(`AdminReauthenticationController`·`config/auth/ReauthenticationService`·`ReauthenticationFailedException`, `LoginFailureService.recordFailure` 반환값) → 통합 시험 먼저(TDD) → `reauth.js`·7개 호출 교체·안내 문구 → 브라우저 시험 → 실기(7개 작업의 모달 흐름, 세션 관리 화면에서 승격 뒤 세션이 하나로 보이고 현재 세션 표시가 맞는지 — 레지스트리 이동 확인).
8. 기록: `com.cms.config` `CLAUDE.md`(로그인 상태 유지 — 수용한 한계 갱신 + 재인증 절), `com.cms.admin.member`·`permission`·`session` `CLAUDE.md` 해당 줄, 루트 `CLAUDE.md` 보안 규칙 한 줄, 이 문서 결과, `plan/README.md`.

## 리스크
- **사용 불편(v5 완화)**: 쿠키로 복원한 세션은 민감 작업 저장 때 비밀번호를 한 번 묻는다. 승격 뒤 그 세션(유휴 30분 기본)이 끝날 때까지 다시 묻지 않는다. 화면 이동·초안 손실은 없다.
- **(v5) 새 인증 경로**: 폼 로그인 경로 밖의 비밀번호 검증이다 — 같은 실패 카운터·같은 재확인을 쓰지만, 폼 로그인에 나중에 보호가 추가되면 이 API에도 맞춰야 한다(`com.cms.config` `CLAUDE.md`에 명시). 유출 비밀번호 검사는 하지 않는다(새 비밀번호를 정하는 경로가 아니다 — 폼 로그인도 하지 않는다).
- **쿠키 세션에서는 비밀번호도 바꿀 수 없다**: 바꾸려면 먼저 비밀번호로 다시 로그인해야 한다(v3). 폼 로그인 세션의 비밀번호 변경 오답이 실패 잠금에 들어가지 않는 기존 성질은 그대로다 — 폼 로그인 세션 자체가 비밀번호를 아는 사람의 것이라 이번 위협(쿠키 탈취)의 범위 밖.
- 쿠키 세션으로도 콘텐츠(게시글·게시판·사이트·메뉴) 변경·삭제는 여전히 가능하다(범위 밖, 사용자 결정).
- 단일 인스턴스·세션 내 판정이라 분산 고려 없음(기존과 동일).

## 개정 이력
- v1(2026-11-11): 초안.
- v2 변경(1라운드 리뷰 반영): ① 컨벤션 시험을 "대상 컨트롤러의 쓰기 핸들러 전수 수집 + 표지 또는 사유 있는 면제 필수 + 반례"로 보강(집합 비교만으로는 표지 없는 새 핸들러를 못 잡는다는 지적 수용), ② 불변 단언 범위를 "민감 작업의 대상 상태"로 좁히고 쿠키 단독 첫 호출 경로를 시험에 추가(자동 로그인 필터가 인터셉터보다 먼저 토큰 교체·세션 등록을 한다는 지적 수용). 지적 1(비밀번호 변경 제외 시 실패 잠금 우회 추측 경로)은 사용자 결정 대기.
- v3 변경: 지적 1 — 사용자 결정 "재인증 대상에 포함"(2026-11-11). 대상 7개, 안내 화면 6개(`admin-settings` 추가), 성공 기준 4-1(정답·오답 모두 403 — 추측 신호 차단) 추가. v1의 "비밀번호 변경은 사실상 재인증" 근거는 오답이 실패 집계·레이트리밋 밖이라 틀렸음을 코드로 확인해 철회.
- v4 변경(2라운드 리뷰 반영): 성공 기준 1의 경로를 ⓐ "쿠키 단독 GET으로 복원한 세션 + 그 세션 CSRF 토큰으로 쓰기 → 403 `REAUTHENTICATION_REQUIRED`"와 ⓑ "세션 없이 쿠키만으로 바로 쓰기 → 기존 401 `UNAUTHORIZED`, 토큰 행 불변"으로 분리(CSRF 저장소가 세션 기반이고 `CsrfFilter`가 remember-me 필터보다 먼저라 v2의 "쿠키 단독 첫 쓰기 → 403"은 성립하지 않는다는 지적 수용 — `ApiSecurityConfigTest`의 미인증 CSRF 401 계약으로 확인). CSRF 설정은 바꾸지 않는다.
- 3라운드 리뷰: ship(새 실질 지적 없음).
- v5 변경(2026-11-11, 구현·실기 완료 후 사용자 결정): 재인증 방식을 (a) 차단 + 다시 로그인 → (b) 비밀번호 재입력 모달로 제자리 승격으로 바꿨다. 쟁점 1 개정, 쟁점 4 개정(공용 `reauth.js`·안내 문구), 쟁점 6 신설(재인증 API — 같은 실패 카운터·성공 재확인·세션 ID 교체·시리즈 보존·CSRF 유지), 성공 기준 6 개정·7 신설, F6~F9 추가. 인터셉터·표지·403 계약·컨벤션 시험(쟁점 2·3·5)은 그대로 쓴다. 위험 변경에 "인증·로그인 정책" 추가. 아래 "구현·검증 결과 (2026-11-11)"는 v4 구현 기준이다.
- 4라운드 리뷰(v5 대상): 지적 4건 모두 수용. ① [높음] `SessionRegistryImpl`이 ID 변경 때 만료 표시를 옮기지 않음(바이트코드로 재확인 — v5의 "옮긴다" 서술 정정) → 재확인을 ID 교체 **뒤** DB 판정(상태·역할·해시·시리즈 생존)으로 옮기고, 이미 완전 인증인 세션은 ID를 바꾸지 않음. ② [높음] 기본 저장소가 무효화된 세션 대신 새 세션을 만들 수 있음 → 요청 시작 때의 세션 객체에 직접 `setAttribute`, 새 컨텍스트 객체, 무효화 시 401. ③ [중간] 모달 동시성 계약 없음 → 문서 단위 Promise 하나·검증 중 제출 차단·원 요청별 1회 재시도, 서버는 세션 뮤텍스로 직렬화. ④ [낮음] 잠금 시 400이라 모달이 계속 입력을 받음 → `recordFailure`가 잠금 여부 반환, 잠금·비활성·폐기는 세션 종료 + 401. F8·F9 시험 확장. v6.
- 5라운드 리뷰(v6 대상): 지적 2건 모두 수용 — [높음] 같은 시리즈를 보존하는 회원 단위 만료의 형제 세션은 DB 재확인으로 못 잡는다, [높음] 탈취 감지 후 토큰 삭제 실패(폐기 대기)도 못 잡는다. 공통 원인은 ID 교체가 레지스트리 만료 표시를 지우는 것. 루프 상한(5라운드) 도달로 사용자에게 보고 → 사용자 결정 "세션 ID 교체 + 폐기 동기화"(세션 ID 유지·v4 복귀 대안은 기각).
- v7 변경: 모든 세션 만료 경로와 재인증의 ID 교체를 `AdminSessionService` 모니터 하나로 직렬화(만료가 교체 전이면 교체 중단·401, 후면 새 ID 항목 만료), `AdminSessionManageService.expireSession`의 직접 `expireNow()`를 서비스 메서드로 이동, 재확인에 탈취 대기 검사 추가(추가 방어), F8 경합 시험을 형제 세션·탈취·직렬화 단위 시험으로 확장, 위험 변경에 "동시성" 추가.
- 6라운드 리뷰(v7 대상): 지적 2건 수용 — [높음] 다른 탭 폼 로그인과 재인증이 같은 세션에서 겹치면 레지스트리 회원과 실제 인증이 어긋날 수 있다(폼 로그인의 등록·저장은 세션 뮤텍스 밖), [중간] 재인증 뒤 늦게 도착한 403이 모달을 다시 띄운다. 리뷰 확인 사항: 만료 경로 열거 누락 없음, 이벤트 동기성 가정은 기본 설정에서 맞음(단 Tomcat이 리스너 예외를 삼킴), 교착 경로 없음, 전역 모니터는 상수 시간이 아님.
- v8 변경: 승격 전 인증 일치 확인(⓪)·승격 뒤 레지스트리·컨텍스트 일치 확인(⑤, 어긋나면 세션 무효), 교체 뒤 레지스트리 새 항목이 없으면 모니터 안에서 직접 옮김, 화면은 재인증 성공 세대 번호로 늦은 403을 모달 없이 1회 재시도, 모니터 성능 문구 정정, F10 신설·F9 확장.
- 7라운드 리뷰(v8 대상): [높음] 1건 — 폼 로그인의 레지스트리 등록은 세션 뮤텍스 밖에서 미리 읽은 ID로 일어나 ⓪·⑤를 모두 통과할 수 있다(바이트코드 재확인). 리뷰 확인: 레지스트리 직접 이동은 타당, 화면 세대 번호는 늦은 403을 닫음. 사용자에게 수용·로그인 경계 직렬화·v4 복귀를 물어 "폼 로그인 경계까지 뮤텍스로 묶기" 결정(수용·v4 복귀는 기각).
- v9 변경: 쟁점 7 신설(`LoginSessionMutexFilter` — 세션이 있는 폼 로그인을 그 세션 뮤텍스 안에서 처리, 두 탭 동시 로그인 경합도 함께 닫음), F10을 7라운드 순서의 결정적 시험으로 교체, F11 신설, 위험 변경에 "로그인 경로" 추가.
- 8라운드 리뷰(v9 대상): **조건부 ship** — 쟁점 7이 7라운드 경합을 닫음을 확인(세션 뮤텍스 동일성: `HttpSessionMutexListener` 미등록·Tomcat 11.0.26 facade 캐시, 로그인 전체 경계 포함, 교착 없음, 필터 순서 `CsrfFilter → RateLimitFilter → LogoutFilter → LoginSessionMutexFilter → UsernamePasswordAuthenticationFilter`). [중간] 1건 — F10의 "R 401 뒤에도 B 세션 유지" 기대가 ⓪ 불일치 시 세션 종료 정책과 모순(소멸 이벤트로 레지스트리 항목도 제거) → 수용.
- v10 변경: F10 기대값을 ① F 보유 중 R 대기 ② F 완료 직후 B 일치 ③ R 재개 뒤 401·S 무효화·새 세션 없음으로 정정, B 유지·후속 만료는 F11의 별도 시험으로 분리. 리뷰가 제시한 ship 조건 충족 — 루프 종료.

## 구현·검증 결과 (2026-11-11)

### Context
쿠키 자동 로그인(#225) 세션이 계정·권한·세션·비밀번호 작업을 그대로 할 수 있던 것을 막았다. 브랜치 `security/sensitive-reauthentication`(master `b06e201`에서 분기).

### 핵심 확정 사항
- 차단 + 다시 로그인(새 인증 경로 없음), 표지 `@RequireFullAuthentication` + MVC 인터셉터, 403 `REAUTHENTICATION_REQUIRED`, 대상 7개(v3에서 비밀번호 변경 추가), 안내 화면 6개.
- 계획과 달라진 점 없음. 세부: 표지는 `@Target(METHOD)`라 클래스 수준 부착은 컴파일 단계에서 막혀 컨벤션 시험의 "클래스 수준 실패" 항목은 시험이 아니라 어노테이션 정의로 고정했다.

### 구현 파일
- 신규: `common/exception/ReauthenticationRequiredException`, `config/auth/RequireFullAuthentication`·`FullAuthenticationInterceptor`·`FullAuthenticationWebMvcConfig`, `templates/admin/fragments/reauth-notice.html`
- 수정: `GlobalApiExceptionHandler`(핸들러 1개), `AdminMemberController`(표지 4·Swagger 403), `MemberPermissionController`(1), `AdminSessionController`(2), 템플릿 6개(안내 조각), `permission/manage.html`·`session/manage.html`(403 분기)
- 시험: 신규 `SensitiveReauthenticationIntegrationTest`(7건)·`FullAuthenticationInterceptorTest`(4건)·`FullAuthenticationConventionTest`(3건), 수정 `RememberMeLoginIntegrationTest` F5③·F4③⑦(계획 "기존 시험 수정" 그대로)
- 문서: `com.cms.config`·`admin.member`·`admin.permission`·`admin.session` `CLAUDE.md`, 루트 `CLAUDE.md` 보안 규칙, `plan/README.md` 58행

### 검증 결과
- TDD: 통합 시험 7건 중 5건이 구현 전 예상 이유로 실패(400·201 백도어 생성 성공·200·406·200) → 구현 후 7건 통과. 단위 시험은 빈 인터셉터(항상 통과)로 거부 2건 실패 확인 후 통과.
- 변이: `changeMyPassword` 표지 제거 → 컨벤션 2건·통합 1건 실패(원복).
- 영향 범위 시험: `com.cms.config.*`·`com.cms.admin.member.*`·`com.cms.admin.permission.*`·`com.cms.admin.session.*`·`*ConventionTest` BUILD SUCCESSFUL. **전체 `./gradlew test`는 아직 돌리지 않았다**(공용 `GlobalApiExceptionHandler`를 바꿨으므로 PR 전 필수 — `code-review-loop` 마무리).
- 실기(dev bootRun + Playwright, 로컬 HTTP):
  - 폼 로그인(로그인 상태 유지 체크) 세션: 6개 화면 안내 없음, 계정 생성 201(회귀 없음).
  - 세션 쿠키 삭제 → 쿠키 복원: 6개 화면에 화면별 안내 표시. 7개 API(비밀번호 정답·오답 포함) 전부 403 `REAUTHENTICATION_REQUIRED`, 비민감(`GET /members`·프로필 기본 이미지) 200. 회원·권한 행·권한 버전·감사 로그 수·토큰 행이 거부 전후 동일. 서버 INFO 로그 `재인증 필요로 민감 작업 거부 … AdminSessionController#expireSession` 확인.
  - 세션 관리 [만료] 클릭 → 확인창 → 재인증 문구(스크린샷 `.playwright-mcp/reauth-session-expire-denied.png`). 권한 관리 저장 → 재인증 문구, 초안 유지(`reauth-permission-save-denied.png`).
  - 안내의 [비밀번호로 다시 로그인] → 폼 로그인 → `/admin` → 권한 화면 안내 없음 → 같은 저장 성공(`BANNER READ`, 버전 0→1, 감사 `PERMISSION_UPDATE`) → 해제 저장으로 원복(행 0, 버전 2, `reauth-permission-save-after-relogin.png`).
  - MANAGER 쿠키 복원: 내 설정 비밀번호 변경 폼 제출 → 재인증 문구, 비밀번호 해시 불변(`reauth-manager-settings-denied.png`). ADMIN 전용 화면 403·API `ACCESS_DENIED`(역할 우선), `PATCH /me`만 `REAUTHENTICATION_REQUIRED`.
  - 정리: 시험용 MANAGER(`reauthmgr`)와 그 토큰 행 삭제.

### 이슈
- 실기 중 dev `admin` 계정의 프로필 이미지가 비민감 작업 확인 호출로 `PRESET /img/undraw_profile_2.svg`로 바뀌었다. 프로필 변경은 감사 로그에 남지 않아 **이전 값을 데이터로 확인할 수 없어 되돌리지 않았다**(dev 데이터).
- 권한 화면에 저장 안 한 초안이 있는 상태에서 안내 링크로 떠나면 기존 `beforeunload` 확인창이 뜬다(기존 동작, 의도대로).
- Playwright MCP의 `page.once('dialog')`가 MCP 자체 확인창 처리와 겹쳐 한 번 멈췄다(검증 도구 문제, 제품 무관).

### 후속
- 콘텐츠(게시글·게시판·사이트·메뉴) 변경·삭제는 쿠키 세션에서 여전히 가능 — 범위 밖(사용자 결정).
- 폼 로그인 세션의 비밀번호 변경 오답이 실패 잠금에 들어가지 않는 성질은 그대로(위협 모델 밖).
- 재로그인 후 원래 화면 복귀(로그인 성공 URL 정책 변경 필요)는 하지 않았다.

## 구현·검증 결과 — v10 (2026-11-11)

### Context
v4(차단 + 다시 로그인)를 구현·실기한 뒤 사용자가 "다시 로그인은 불편하고 자동 로그인의 의미가 없다"고 보고 **비밀번호 재입력 모달(제자리 승격)**로 바꿨다. 새 인증 경로가 생기면서 리뷰 4~8라운드에서 세션 레지스트리·폼 로그인 경합이 드러났고, 사용자 결정 두 번("세션 ID 교체 + 폐기 동기화", "폼 로그인 경계까지 뮤텍스")을 거쳐 v10이 됐다. v4 구현(표지·인터셉터·403 계약·컨벤션 시험·안내 조각)은 그대로 쓰고 그 위에 더했다.

### 핵심 확정 사항
- 민감 API 7개는 v4 그대로 403 `REAUTHENTICATION_REQUIRED`. 화면 `reauth.js`가 모달 → `POST /admin/api/members/me/reauthentication` → 같은 요청 1회 재시도.
- 재인증: 폼 로그인과 같은 `AuthenticationManager`·같은 실패 카운터, 오답 400 / 잠금·비활성·불일치 401(세션 무효화), 완전 인증 세션은 확인만.
- 승격: 세션 뮤텍스 안 ⓪ 인증 일치 → ① 레지스트리 모니터 안 ID 교체(만료·불일치면 중단, 리스너 미이동 시 직접 이동) → ② DB·시리즈·탈취 대기 재확인 → ③ 새 컨텍스트 → ④ 잡아 둔 세션에 직접 저장 → ⑤ 사후 일치.
- `AdminSessionService` 모든 만료 + 교체가 한 모니터. `LoginSessionMutexFilter`가 세션 있는 폼 로그인을 세션 뮤텍스 안에서.
- **계획 대비 변경**: `expireByHandleIfActive`를 새로 만들지 않고 기존 `expireByHandle`을 모니터 안으로 옮겨 재사용(동작 동일, 이름만 다름). `BrowserTestSupport.TEST_PASSWORD`를 `protected`로(브라우저 시험이 모달에 비밀번호를 입력해야 해서). 취소 시 보이는 403 문구를 "…비밀번호 확인이 필요합니다. 다시 저장하고 비밀번호를 입력해 주세요."로 바꿨다(모달 흐름에 맞춤). 안내 조각의 로그인 링크를 빼고 문구를 "저장할 때 비밀번호를 한 번 더 확인합니다"로.

### 구현 파일
- 신규: `config/auth/ReauthenticationService`·`LoginSessionMutexFilter`, `admin/member/controller/AdminReauthenticationController`, `admin/member/dto/request/ReauthenticationRequest`, `common/exception/ReauthenticationFailedException`·`ReauthenticationEndedException`, `static/js/admin/reauth.js`
- 수정: `AdminSessionService`(모니터·`changeSessionIdIfActive`), `AdminSessionManageService`(단일 만료를 `expireByHandle`로), `LoginFailureService.recordFailure`(잠금 여부 반환), `SecurityConfig`(필터 등록), `GlobalApiExceptionHandler`(처리기 2), 템플릿 6개(`reauth.js`·`CmsReauth.fetch` 7곳·안내 문구), `reauth-notice.html`, `ReauthenticationRequiredException` 문구
- 시험: 신규 `ReauthenticationApiIntegrationTest`(13)·`AdminSessionServiceSwapTest`(5)·`LoginSessionMutexFilterTest`(2)·`ReauthModalBrowserTest`(4), 수정 `FullAuthenticationConventionTest`(재인증 컨트롤러 대상·면제), `BrowserTestSupport`(`TEST_PASSWORD` protected)
- 문서: `com.cms.config`·`admin.member`·`admin.permission`·`admin.session` `CLAUDE.md`, 루트 `CLAUDE.md`, `docs/troubleshooting.md`(세션 레지스트리 만료 표시·폼 로그인 등록 경합), `plan/README.md`

### 검증 결과
- TDD: 교체 단위 시험 RED(껍데기 false로 4건 실패) → GREEN, 필터 단위 RED → GREEN, 재인증 통합 RED(13건 중 11건 404 — 통과 2건은 이미 구현된 필터의 F11과 기존 CSRF 401 계약) → GREEN, 브라우저 4건 GREEN.
- 변이(전부 원복): 교체를 모니터 밖으로 → 1건, 만료 검사 제거 → 1건, 로그인 필터 등록 제거 → F10·F11, 직렬화 교체 생략 → 형제 세션 경합·정상 승격 2건, JS 확인 중 잠금 제거 → 1건, JS 세대 번호 제거 → 1건(처음엔 시험이 결과 Promise를 무기한 기다려 **멈췄다** → 시간 제한 있는 `waitForFunction`으로 고쳐 실패로 드러나게 함).
- 영향 범위 시험(`com.cms.config.*`·`admin.member.*`·`admin.permission.*`·`admin.session.*`·`*ConventionTest`): 894건 통과·실패 0. 브라우저 시험은 JS 접근성 수정 뒤 재실행 통과. **전체 `./gradlew test`는 아직 돌리지 않았다**(공용 `SecurityConfig`·`GlobalApiExceptionHandler`·`BrowserTestSupport` 변경 — PR 전 필수).
- 실기(dev bootRun + Playwright, 로컬 HTTP·실제 Tomcat):
  - ADMIN 쿠키 복원 세션: 권한 화면 안내 → 저장 → 모달(`reauth-modal-open.png`) → 오답 문구(`reauth-modal-wrong.png`) → 정답 → **같은 화면에서 저장 완료·초안 유지**(`reauth-modal-saved.png`), DB `BANNER READ`, 오답으로 오른 실패 횟수는 성공 뒤 0.
  - **실제 Tomcat의 세션 ID 변경 → 레지스트리 이동**: 세션 관리 API의 현재 세션 핸들 = 브라우저 새 `JSESSIONID`의 SHA-256 앞 32자, 옛 ID 항목 없음, 승격 뒤 안내 사라짐(`reauth-session-after-upgrade.png`).
  - 승격된 세션에서 같은 기기(같은 시리즈)의 다른 세션을 만료 → 모달 없이 처리되고 **현재 세션도 끊김**(기존 문서화된 기기 단위 폐기 — 승격 뒤에도 시리즈가 유지됨을 보여 줌), 그 기기 토큰 행 삭제.
  - 폼 로그인 세션: 안내·모달 없이 권한 원복 저장(v1→v2, 0개).
  - MANAGER 쿠키 복원: 내 정보 수정 → 모달 취소 → 서버 문구·입력값 유지 → 다시 저장·정답 → 저장(`reauth-manager-myinfo-saved.png`). 재기동 뒤 쿠키로 복원한 세션에서도 같은 흐름, 콘솔 경고 0.
  - 정리: 시험용 MANAGER(`reauthmgr2`)·토큰 행 삭제, 앞선 v4 실기에서 남긴 admin 토큰 행(17:05) 삭제.

### 이슈
- 실기 중 모달을 닫을 때 `aria-hidden` 접근성 경고(포커스가 모달 안 취소 버튼에 남음) → 닫기 전 포커스 해제로 고침(재기동 후 경고 0 확인 — bootRun은 기동 때 복사한 정적 파일을 내보내 수정 반영에 재기동이 필요했다).
- Playwright MCP의 `page.once('dialog')`가 MCP 자체 확인창 처리와 겹쳐 스크립트가 멈추는 현상이 다시 있었다(검증 도구 문제).
- 조사 명령에서 디스크 쓰기·삭제를 섞으려다 사용자 지적으로 중단 — 이후 `javap -cp`(읽기 전용)로만 확인(메모리 기록).

### 후속
- 콘텐츠(게시글·게시판·사이트·메뉴) 변경·삭제는 쿠키 세션에서 여전히 비밀번호 확인 없이 가능 — 범위 밖(사용자 결정).
- 세션 관리 화면의 회원 단위 만료와 같은 브라우저 재로그인의 기존 경합은 그대로(만료가 세션 뮤텍스를 잡지 않는다 — `com.cms.admin.session` `CLAUDE.md`).
- 승격과 동시에 시작된 만료는 다음 요청에서 끊긴다(승격된 인증이 한 응답만큼 존재 — 수용).

## 코드 리뷰 결과 (code-review-loop, 2026-11-11)

5라운드(기본 리뷰 3 + 위험 변경 초점 2). 수용 5건·기각 0·보류 0 — 모두 실패 시험(RED)부터 작성해 반영했다.

| 라운드 | 지적 | 처리 |
|---|---|---|
| 1 기본 | [P2] 같은 세션의 두 탭이 동시에 재인증하면 늦은 쪽이 ⓪에서 이미 승격된 정상 세션을 끝낸다 | 수용 — 같은 회원의 완전 인증이면 204·변경 없음(`concurrentReauthOfSameMember_secondSucceedsWithoutEndingSession`) |
| 1 기본 | [P2] Bootstrap 4는 전환 중 `show()`/`hide()`를 무시 — 표시 중 취소면 모달이 남고, 닫힘 중 새 403이면 모달이 안 뜬다 | 수용 — `shown` 전 잠금·`hidden` 뒤 다음 표시(브라우저 시험 2건, 변이 각 1건 검출) |
| 2 기본 | [P2] 재인증 중 같은 회원의 프로필 변경(`refreshAuthentication`이 인증 객체 교체)이 ⓪의 객체 비교에 걸려 정답인데 401 | 수용 — ⓪을 (회원, 시리즈) 비교로(`sameMemberRefreshDuringReauth_stillUpgrades`) |
| 3 기본 | 지적 없음 | 통과 → 위험 변경 초점 리뷰로 |
| 4 초점 | [medium] 실패 집계 예외를 재시도 가능한 오답으로 처리 — 집계 장애 중 무제한 시도 | 수용 — 집계 예외면 세션 종료 401(`failureRecordingError_endsSession`, `@MockitoSpyBean LoginFailureService`) |
| 4 초점 | [medium] 응답 없는 검증 요청이 모달과 대기 요청을 영구히 붙잡는다 | 수용 — 10초 `AbortController` 제한 뒤 재조작(`unansweredVerification_timesOutAndRecovers`) |
| 5 초점 | approve(새 실질 지적 없음) | 종료 |

- 반영 중 발견: F10 시험의 잠재 불안정 — 재인증이 세션을 무효화한 직후 폼 로그인 스레드의 MockMvc 기본 결과 출력기가 세션 속성을 읽다 `IllegalStateException`(시험 도구 경합, 제품 동작 아님). 이 시험 클래스의 결과 출력을 꺼서 해결, 3회 연속 통과 확인.
- 반영으로 바뀐 파일: `ReauthenticationService.java`, `reauth.js`, `ReauthenticationApiIntegrationTest.java`, `ReauthModalBrowserTest.java`, 문서(`com.cms.config` `CLAUDE.md`, 이 문서).
