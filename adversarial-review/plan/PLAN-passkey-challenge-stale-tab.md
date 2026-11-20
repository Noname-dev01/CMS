# PLAN — 패스키 챌린지를 탭끼리 나눠 쓰기: 화면 GET 재사용 + 식별자가 맞을 때만 지우기 (이슈 #293)

- 상태: v8 (2026-11-20) — 계획 리뷰 7라운드 ship(6·7라운드 서버 설계 결함 없음), 사용자 승인(2026-11-20), 구현·검증 완료(PR 전) — 결과는 문서 끝 "구현·검증 결과"
- 이슈: #293. 출처 `PLAN-passkey-conditional-ui.md` S10·결정 5·R3(#285에서 수용 위험으로 고정), `PLAN-passkey-login.md` 결정 2, `PLAN-passkey-second-factor.md` 결정 4·리뷰 2-1. 재사용 방식의 선례 `PLAN-totp-enrollment-reuse.md`(#277 — 강제 등록 화면 GET이 유효한 등록 대기를 재사용).
- 위험 변경: **인증(로그인 입구의 패스키 챌린지 발급·1회용 소비 — 패스키 단독 로그인 화면·필터, 2단계 코드 화면)**. URL 인가 규칙·레이트리밋·스키마·의존성·설정 키·템플릿·JS 변경 **없음**.
- 바뀌는 파일(예정): `config/auth/PasskeyLoginState.java`·`PasskeyAssertionState.java`·`PasskeyLoginService.java`·`PasskeyLoginFilter.java`·`TotpLoginService.java`, `admin/AdminMainController.java`, `admin/member/controller/TotpLoginController.java`, 시험 `PasskeyLoginIntegrationTest`·`TotpPasskeyLoginIntegrationTest`·`AdminMainControllerTest`·`PasskeyBrowserTest`(BFCache 복원 단언 2건) + 신규 단위 시험 `PasskeyLoginStateTest`·`PasskeyAssertionStateTest`, 문서 `com.cms.config`의 `CLAUDE.md`·`com.cms.admin.member`의 `CLAUDE.md`.

## 1. Context

같은 세션(브라우저)의 두 탭에서 로그인 화면을 열면 세션의 패스키 챌린지는 최신 GET의 것 하나만 남는다. 제출 처리는 세션 챌린지를 **먼저 꺼내 지우고 그다음** 화면 식별자(`loginId`)를 대조한다.

**실제 브라우저에서의 현재 동작(리뷰 1-1로 정정)**: 옛 탭 A 제출 → B의 챌린지를 지우고 `/admin/login?passkeyExpired`로 302 → 브라우저가 그 GET을 따라가며 **새 챌린지 C가 발급**된다. 그래서 A는 만료 안내와 함께 바로 다시 쓸 수 있고, **실패하는 것은 다른 탭 B 하나**다(B는 새로고침해야 한다). 이슈 본문의 "두 탭 모두 실패"는 리다이렉트를 따라가지 않는 MockMvc 시험(`staleTabSubmitConsumesLatestChallenge`)에서 본 결과다. 그리고 **만료 안내 GET 자체가 교체하므로 제출 판정만 고쳐서는 브라우저 동작이 바뀌지 않는다**(v1의 결함). 조건부 UI(#285)는 화면을 열어 둔 채 고르게 하므로 "열어 둔 다른 탭이 조용히 무효가 되는" 일이 늘었다.

목표(사용자 결정 2026-11-20): **같은 세션의 탭들이 유효한 챌린지 하나를 나눠 써서, 어느 탭에서 제출해도 동작**하게 한다. 1회용(한 챌린지로 검증되는 어설션은 최대 1개)과 5분 수명은 유지한다.

완료 기준(이슈 원문 + 정정):
1. `loginId`가 맞지 않으면 세션 챌린지를 지우지 않는다(맞으면 지금처럼 1회용으로 꺼내 지운다). 만료(5분 초과)는 지금처럼 지운다
2. 고정 시험 `staleTabSubmitConsumesLatestChallenge`의 기대를 바꾼다 — **리다이렉트 GET까지 포함한 순서**로 "옛 탭 `?passkeyExpired` → 다른 탭은 정상 검증"
3. 1회용 성질 유지 — 같은 `loginId` 재제출은 `?passkeyExpired`(`challengeSingleUse`), 맞지 않는 `loginId` 반복 제출로 검증이 일어나지 않음(레이트리밋 `admin-passkey-login` 그대로)
4. 2단계 화면의 `PasskeyAssertionState`(`assertionId`)에도 적용 — **적용**(사용자 결정 2026-11-20, 결정 4)
5. (추가, 리뷰 1-1) 화면 GET은 유효한 챌린지가 있으면 교체하지 않고 재사용한다(결정 1)

### 정찰에서 확인한 사실

| # | 사실 | 출처 |
|---|---|---|
| S1 | `takeState`는 `getAttribute` 직후 무조건 `removeAttribute`하고, 호출부가 그 뒤 `state.matches(loginId, now)`(`loginId` 일치 **그리고** `issuedAt + 5분 >= now`)로 판정한다. 불일치·만료·없음은 모두 `PasskeyLoginExpiredException` → 실패 처리기가 `/admin/login?passkeyExpired`로 302, 검증(`identify`·`verify`) 전이다. | `PasskeyLoginFilter.java:55-58, 83-91`, `PasskeyLoginConfigurer.java:50` |
| S2 | 2단계 코드 화면 제출도 같은 구조다 — `PasskeyAssertionState`를 꺼내며 지운 뒤 `matches(memberId, assertionId, now)`, 불일치면 `/admin/login/totp?passkeyExpired`(카운트 없음, 검증 없음). 코드(TOTP 6자리) 제출은 이 상태를 건드리지 않는다. | `TotpLoginController.java:101-111` |
| S3 | **만료 안내·오류 안내 GET도 챌린지를 새로 발급한다** — `GET /admin/login`(모든 쿼리)은 `loginPage`로 가서 `passkeyLoginService.start()` 결과로 세션 속성을 **대체**한다. 코드 화면 GET(모든 쿼리)도 `startPasskeyAssertion`으로 대체한다. | `AdminMainController.java:39-85`, `TotpLoginController.java:57-75` |
| S4 | 두 POST(`/admin/login/passkey`·`/admin/login/totp`) 모두 `LoginSessionMutexFilter`의 세션 뮤텍스 안에서 처리된다. 챌린지를 발급하는 **GET은 뮤텍스 밖**이다. | `LoginSessionMutexFilter.java:35-55` |
| S5 | 발급: `PasskeyLoginService.start()`는 기능 꺼짐이면 빈 값, 아니면 `PasskeyLoginState(UUID, discoverableRequestOptions(), now)` — DB 접근 없음. `TotpLoginService.startPasskeyAssertion(principal)`은 기능 꺼짐·유효 패스키 0개·`allowCredentials` 비면 빈 값(컨트롤러가 속성 제거 → 버튼 없음), 아니면 `PasskeyAssertionState(UUID, memberId, requestOptions(userId), now)` — `allowCredentials`는 그 시점의 유효 자격 목록. 식별자는 화면 폼에 실린다 — 비밀이 아니다(같은 세션 쿠키 + CSRF 토큰이 있어야 제출할 수 있다). | 해당 코드 |
| S6 | 성공 정리: 패스키 단독 로그인 성공 때 성공 처리기가 두 상태를 모두 지우고(`VisitLoggingAuthenticationSuccessHandler.java:80-85`), 2단계 성공 때 컨트롤러가 `PasskeyAssertionState`를 지운다(`TotpLoginController.java:135`). 검증 실패(서명 불일치 등)는 일치 갈래라 이미 지운 뒤다. 바꾸지 않는다. | 해당 코드 |
| S7 | 레이트리밋 `admin-passkey-login`(POST `/admin/login/passkey`, IP당 30/60초, `login-redirect`)은 챌린지 판정보다 앞이라 불일치 제출도 토큰을 쓴다. 코드 화면 POST는 이 규칙 대상이 아니다(2단계는 회원별 실패 5회 잠금). | `application.yml:212-217` |
| S8 | 화면 쪽: 로그인 화면은 진입 때 조건부 요청을 시작하고 성공하면 바로 폼을 제출한다. 버튼은 클릭 때마다 `data-options`를 다시 읽는다. Chromium 가상 인증기는 조건부 요청에 대기 없이 응답하므로 브라우저 시험은 `com.cms.support.PasskeyRequestHarness`로 조건부 요청을 붙잡는다. Playwright Java **1.65.0**(`build.gradle:88`). | `passkey-login.js:64, 134`, `PasskeyRequestHarness.java:11` |
| S9 | 시험 파급: `PasskeyLoginIntegrationTest` F3의 `replacedChallengeExpired`(GET 두 번 → 첫 `loginId` 만료)와 `TotpPasskeyLoginIntegrationTest.replacedAssertionExpiresWithoutCount`(GET 두 번 → `second != first` 단언)는 "GET마다 대체"를 전제한다 → 재사용 뒤에는 전제가 바뀐다. `AdminMainControllerTest`는 `passkeyLoginService.start()`를 목으로 둔다(2건). `PasskeyBrowserTest`의 BFCache 복원 시험 2건(`secondFactorScreenDoesNotStartConditional` 474행 `assertionId` 변경, `restoredLoginPageReloads` 485행 `loginId` 변경)도 "GET마다 대체"를 단언한다(리뷰 2-2). | 시험 코드 |

## 2. 핵심 설계 결정

### 결정 1 — 화면 GET은 "충분히 남은" 유효 챌린지를 재사용한다(resume-or-start)

- 로그인 화면 GET: 세션에 `PasskeyLoginState`가 있고 **남은 수명이 `REUSE_MIN_REMAINING`(= TTL의 절반, 2분 30초) 이상**이면 그대로 그린다(같은 `loginId`·같은 옵션). 아니면 새로 발급해 대체한다. 기능 꺼짐이면 지금처럼 속성 제거.
- 코드 화면 GET: 세션의 `PasskeyAssertionState`가 **같은 `memberId`**이고 남은 수명이 위 기준 이상이며, 지금도 기능 켜짐·유효 패스키 1개 이상이면 재사용. 기능 꺼짐·유효 패스키 0개면 지금처럼 빈 값(속성 제거·버튼 없음) — **재사용보다 이 판정이 먼저**다.
- 선택지: (a) 재사용 — **채택**(사용자 결정). (b) 만료 안내 GET만 재사용 — 기각: "다른 탭을 새로 여는 GET"도 교체해 같은 문제가 남는다(A·B를 순서대로 열기만 해도 A가 무효). (c) 식별자별 다중 챌린지 — 기각: 세션 상한·정리 규칙이 새로 생긴다. 재사용이면 탭 수와 무관하게 세션에 하나다.
- **재사용 하한(남은 수명 ≥ 2분 30초)을 두는 이유**: 재사용하면 뒤에 연 탭은 남은 수명만 갖는다. 하한이 없으면 수명이 1초 남은 챌린지를 막 연 화면에 그려 곧바로 만료 안내가 된다(지금은 GET마다 5분 전체를 받는다 — 사용성 후퇴). 하한 아래면 새로 발급하므로 **막 연 화면은 항상 2분 30초 이상**을 갖는다. 그 경우 옛 탭(남은 수명 2분 30초 미만)은 무효가 되지만, 결정 2(불일치 보존)와 만료 안내 GET의 재사용으로 옛 탭이 제출하면 새 챌린지로 수렴한다(성공 기준 2). 값은 상수로 두고 설정 키를 만들지 않는다.
- **왜 1회용·보안이 유지되는가**: 재사용은 "검증에 쓰이지 않은 챌린지"를 같은 세션의 다른 화면에 다시 보여 줄 뿐이다. 검증은 여전히 일치 갈래에서 지운 뒤에만 일어나므로(결정 2) 한 챌린지로 검증되는 어설션은 최대 1개다. 챌린지의 서버 수명은 발급 시각 기준 5분 그대로다(재사용이 수명을 늘리지 않는다). 챌린지 값은 같은 세션에만 보인다(세션 속성 → 그 세션의 화면).
- **재사용된 2단계 옵션의 `allowCredentials`는 발급 시점 목록이다**: 그 사이 다른(완전 인증된) 세션에서 패스키를 추가하면 재사용 화면에는 새 자격이 빠진다 — 그 탭은 다음 발급(만료·소비) 뒤 보인다. 삭제된 자격은 검증 때 저장소 어댑터가 유효한 것만 돌려주므로 통과하지 못한다(`PasskeyCredentialRepository.findByUserId` — 기존 계약). 수용.

### 결정 2 — 제출: "맞으면 꺼내 지우고, 만료면 지우고, 그 밖의 불일치면 남긴다"

| 세션 상태 | 판정 | 세션 챌린지 | 응답 |
|---|---|---|---|
| 없음(세션 없음·속성 없음) | 만료 | — | `?passkeyExpired` |
| 다른 타입 값 | 만료 | **지운다**(지금과 같다) | `?passkeyExpired` |
| 발급 5분 초과(식별자 무관) | 만료 | **지운다** | `?passkeyExpired` |
| 5분 이내 + 식별자 불일치(누락 포함) | 만료 | **남긴다** | `?passkeyExpired` |
| 5분 이내 + 식별자 일치 | 통과 | **지운다**(검증 전, 1회용 — 지금과 같다) | 검증으로 |

- 결정 1만으로는 부족한 경우가 있다: 옛 탭 A(챌린지 X)가 X의 남은 수명이 하한 아래일 때 열린 탭 B 때문에 Y로 바뀐 상태에서 A가 제출하면, 지금 코드는 Y를 지운다 → A의 만료 안내 GET이 새 Z → B 실패. 남기면 A의 만료 안내 GET이 Y를 재사용해 A·B가 Y로 모인다.
- 1회용: 검증에 도달하는 유일한 갈래(일치)는 검증 **전에** 지운다. 남기는 것은 검증에 쓰이지 않은 챌린지뿐이다.
- 불일치 제출은 검증·DB 접근 없이 끝나고 레이트리밋을 그대로 쓴다(S7). 공격자가 할 수 있는 일은 "세션 챌린지를 지우는 것"이 **줄어드는** 방향뿐이다(식별자를 알아도 서명 없이는 검증을 통과하지 못한다).

### 결정 3 — 판정은 상태 객체에, 지우기·재사용 결정은 호출부·서비스에

- `PasskeyLoginState`·`PasskeyAssertionState`에 `isExpired(now)`와 `isReusable(now)`(남은 수명 ≥ 하한, 2단계는 `isReusableFor(memberId, now)`)를 더한다. `matches`는 그대로(일치 = 식별자 일치 ∧ ¬만료, 정확히 5분은 유효 — 지금과 같다).
- 발급: `PasskeyLoginService.start()` → `resumeOrStart(PasskeyLoginState current)`, `TotpLoginService.startPasskeyAssertion(principal)` → `resumeOrStartPasskeyAssertion(principal, PasskeyAssertionState current)`. 컨트롤러가 세션에서 현재 값을 읽어 넘긴다. 선례 `MemberTotpService.resumeOrStartEnrollment`와 같은 모양이다.
- **GET은 새로 발급한 값만 세션에 쓴다 — 재사용(반환값이 넘긴 값과 같은 객체)이면 세션에 쓰지 않는다**(리뷰 2-1). GET은 세션 뮤텍스 밖이라(S4) "읽기 → 재사용 결정" 사이에 제출이 그 챌린지를 소비(지우고 검증)할 수 있다. 재사용 값을 다시 쓰면 **소비된 챌린지가 세션에 되살아나** 같은 챌린지로 두 번째 검증이 가능해진다. 쓰는 값이 항상 방금 만든 챌린지면 되살릴 경로가 없다 — 소비 뒤 재사용 화면에 그려진 식별자는 세션에 없으므로 그 화면의 제출은 `?passkeyExpired`(결정 5의 설계상 결과). 기능 꺼짐·패스키 0개의 속성 제거는 지금과 같다(지우기는 되살림이 아니다).
- GET 전체를 세션 뮤텍스로 감싸는 안(리뷰 2-1 수정 방향의 일부)은 기각: 위 규칙으로 되살림이 구조적으로 막히고, 뮤텍스 편입은 `LoginSessionMutexFilter` 매처 변경(로그인 경로 변경)이라 범위가 넓어진다. 남는 GET 경합은 "마지막 새 발급이 남는다"뿐이고 결정 2로 수렴한다.
- 제출: 필터·컨트롤러 안의 private 메서드(공용 도우미 없음 — 입력이 다르고 호출처가 둘뿐).

### 결정 4 — 2단계 코드 화면에도 적용하되, 다른 회원의 대기는 재사용하지도 남기지도 않는다

- 사용자 결정(2026-11-20): 두 "1회용 챌린지" 계약을 같게 둔다.
- `memberId`가 다르면: GET은 재사용하지 않고 새로 발급, 제출은 **지운다**(만료와 같이 취급) — 지금 principal의 것이 아니라 어떤 탭에서도 쓸 수 없다. 남기는 것은 "같은 회원 + 5분 이내 + `assertionId`만 불일치"뿐이다.
- 2단계 오답 카운트(`member_totp.failed_attempts`)는 바뀌지 않는다.

### 결정 5 — 뮤텍스 밖 GET과의 경합은 지금 수준 그대로 둔다

- 제출은 세션 뮤텍스 안이지만 GET은 밖이다(S4). 제출이 "일치"로 읽은 뒤 지우기 전에 다른 탭 GET이 그 챌린지를 재사용해 그리면(재사용 뒤에는 같은 객체라 지우기가 그 탭의 챌린지도 지운 셈) 그 탭은 `?passkeyExpired` 뒤 새 챌린지로 회복한다. 한 챌린지를 나눠 쓰는 이상 "먼저 제출한 탭이 소비하면 나머지 탭은 만료 안내"는 **설계상 결과**다(조건부 UI 계획 결정 1과 같은 성질).
- GET끼리의 경합(두 탭이 동시에 하한 아래 챌린지를 보고 각자 새로 발급)은 마지막 쓰기가 남고 다른 탭은 결정 2 경로로 수렴한다. GET을 뮤텍스에 넣는 것은 범위 밖(§7).

## 3. 성공 기준

1. 로그인 화면: 같은 세션에서 GET 두 번(A, B)이 **같은 `loginId`**를 그린다. A 제출 성공 뒤 B 제출은 `?passkeyExpired`(1회용), B의 만료 안내 GET은 새 챌린지.
2. 로그인 화면(수렴): 챌린지 X를 하한 아래로 늙힌 뒤 GET(B) → 새 Y. A가 X의 `loginId`로 제출 → `?passkeyExpired`(검증 0회) → **A가 만료 안내 GET을 따라가면 Y를 재사용** → B가 Y로 제출하면 검증되어 `/admin`(검증 1회, 넘긴 옵션이 Y의 것).
3. 코드 화면: 같은 회원·같은 세션에서 GET 두 번이 같은 `assertionId`, 수렴 시나리오(2와 같은 순서, `?passkeyExpired` GET 포함)가 같게 동작, `failed_attempts` 불변. 다른 `memberId`의 대기는 GET이 재사용하지 않고 제출이 지운다. 유효 패스키 0개·기능 꺼짐이면 재사용 후보가 있어도 버튼 없음·속성 제거.
4. 1회용 유지: 일치 제출(성공·실패 무관) 뒤 같은 식별자 재제출은 `?passkeyExpired`, 검증 0회. 불일치 식별자 반복(3회 + 누락 1회)은 검증 0회이고 세션 챌린지가 같은 객체로 남는다.
5. 5분 초과 챌린지는 식별자가 맞든 틀리든 제출 때 지워지고, GET은 재사용하지 않는다.
6. 재사용 경계: 남은 수명이 정확히 2분 30초면 재사용, 그보다 1초 적으면 새로 발급. **재사용이면 세션 상태가 같은 객체(`options`·`issuedAt` 그대로), 새 발급이면 식별자뿐 아니라 `options.challenge` 바이트가 달라지고 `issuedAt`이 지금**이다(리뷰 6-1 — WebAuthn 서명이 묶는 것은 챌린지라 식별자만 바뀌면 같은 챌린지의 수명이 늘어난다).
7. 되살림 없음(리뷰 2-1): GET이 재사용을 결정한 뒤 멈춘 사이 제출이 그 챌린지를 소비(검증 실패)하고, GET이 끝난 뒤 같은 식별자로 다시 제출하면 `?passkeyExpired`·검증 누적 1회. 로그인·코드 화면 모두.
8. BFCache 복원(리뷰 2-2): 복원 이벤트 뒤 화면이 실제로 다시 불린다(복원 전 `window`에 심은 표지가 사라짐 — 클라이언트 `phase` 등 초기화의 증거). 챌린지가 젊으면 식별자는 **같다**(재사용). 소비·교체 뒤 GET이 새 챌린지를 받는 것은 통합 시험(F2·F3)이 고정한다 — JS는 어느 경우나 다시 불러올 뿐이다.
9. 그 밖의 기존 시험(`PasskeyLoginRateLimitIntegrationTest`·`VisitLoggingAuthenticationSuccessHandlerTest`·`PasskeyBrowserTest`의 나머지 등) 무변경 통과. 기대가 바뀌는 시험은 S9의 것과 `staleTabSubmitConsumesLatestChallenge`뿐이고, 바뀐 이유를 표시명에 남긴다.

## 4. 실패 모드와 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 만료 안내 GET·다른 탭 GET이 유효 챌린지를 교체해 다른 탭이 무효(이슈의 실제 증상, 리뷰 1-1) | `PasskeyLoginIntegrationTest`: 기존 `staleTabSubmitConsumesLatestChallenge`를 `staleTabConvergesOnLatestChallenge`로 바꿔 성공 기준 2의 순서(**리다이렉트 GET을 명시적으로 수행**)로 고정 + 신규 `reopenReusesChallenge`(성공 기준 1). 2단계 같은 쌍을 `TotpPasskeyLoginIntegrationTest`에(기존 `replacedAssertionExpiresWithoutCount`는 하한 아래로 늙힌 뒤 GET하는 형태로 바꿔 "교체 후 옛 식별자 만료·카운트 없음"을 유지) |
| F2 | 재사용·보존 때문에 같은 챌린지로 두 번 검증 | 기존 `challengeSingleUse`·2단계 "같은 대기 두 번째 제출" 그대로 + 성공 기준 1의 "A 성공 뒤 B 만료"(F1 시험 안), 성공 뒤 세션 속성 없음 단언 |
| F3 | 만료 챌린지가 불일치 제출에서 남거나 GET이 재사용 | `PasskeyLoginIntegrationTest` 신규 `staleChallengeRemovedOnMismatch`(5분 1초 전 + 틀린 `loginId` → 속성 없음, 다음 GET은 새 `loginId`). 2단계 같은 쌍 |
| F4 | 재사용 하한 경계 오류(1초 남은 챌린지를 새 화면에 그림 / 하한 상수 반대 부등호) | 상태 레코드 단위 시험 `PasskeyLoginStateTest`·`PasskeyAssertionStateTest`(신규): 발급 후 2분 30초 → 재사용 가능, 2분 30초 + 1초 → 불가, 5분 → `matches` 참·`isExpired` 거짓, 5분 + 1초 → 만료. 통합 시험에서 하한 아래로 늙힌 상태 GET → 새 `loginId` **그리고 `options().getChallenge()` 바이트가 이전과 다르고 `issuedAt`이 지금**, 재사용 GET → 세션 상태 `isSameAs`(F1 수렴·`reopenReusesChallenge` 안). 2단계도 같은 단언 |
| F6 | 뮤텍스 밖 GET이 소비된 챌린지를 세션에 다시 써 두 번째 검증(리뷰 2-1) | `PasskeyLoginIntegrationTest` 신규 `reuseDoesNotResurrectConsumedChallenge`: 기존 `@MockitoSpyBean PasskeyLoginService`의 `resumeOrStart`를 실제 호출 뒤 래치에서 멈추게 하고(GET 스레드), 그동안 같은 세션 POST(검증 실패 → `?passkeyError`) → 래치 해제·GET 완료 → 같은 `loginId` POST `?passkeyExpired`, `authenticate` 누적 1회, 세션 속성 없음. 2단계는 `TotpPasskeyLoginIntegrationTest`에 `TotpLoginService` 스파이로 같은 순서(`?passkeyError` → `?passkeyExpired`, 검증 누적 1회). GET이 정말 재사용 갈래였음을 GET 응답 모델의 식별자 = 첫 식별자로 확인 |
| F7 | 브라우저 복원 시험이 재사용 뒤에도 "재로드"를 증명하지 못함(식별자 변경 단언 제거만 하면 약해짐, 리뷰 2-2) | `PasskeyBrowserTest` 두 시험 변경: 복원 전 `window.__beforeRestore = true` → 복원 뒤 `undefined`(재로드), 식별자는 같음 |
| F8 | 제출 판정에 재사용 하한을 잘못 써 실제 제출 수명이 2분 30초로 줄어듦(리뷰 7-1 — F4 단위 시험은 호출부가 `matches`를 쓰는지 보장하지 않는다) | `PasskeyLoginIntegrationTest` 신규 `challengeValidUntilFiveMinutes`: GET 뒤 세션 상태의 `issuedAt`만 4분 전으로 바꾼 같은 옵션 상태를 넣고 **중간 GET 없이** 일치 `loginId`로 제출 → `/admin`, `authenticate` 1회·넘긴 옵션이 그 상태의 옵션, 세션 속성 없음. 2단계도 같은 시험(`TotpPendingState`는 시험 시작 때 새로 만들어 대기 만료가 먼저 오지 않게 — 대기 상태의 `since`는 건드리지 않는다). 정확히 5분 경계는 F4 단위 시험이 맡는다(통합 시험의 실제 시계로는 정확한 경계를 만들 수 없다) |
| F5 | 2단계에서 다른 회원의 대기를 재사용·보존, 또는 패스키 0개인데 재사용 대기로 버튼을 그림 | `TotpPasskeyLoginIntegrationTest` 신규: ① 세션 대기를 다른 `memberId`로 바꿔 넣고 GET → 새 `assertionId`, ② 다른 `memberId` 대기로 제출 → 속성 없음·검증 0회, ③ 유효 대기를 둔 채 패스키 행 삭제 후 GET → 버튼 없음·속성 없음 |

변이 확인(구현 후): ① 불일치 갈래에 지우기 되살림 → F1 수렴 실패, ② 일치 갈래의 지우기 제거 → F2 실패, ③ 만료 갈래 지우기 제거 → F3 실패, ④ 재사용 하한 부등호 반전/제거 → F4 실패, ⑤ GET 재사용 제거(항상 새로 발급) → F1 실패, ⑥ 2단계 재사용 전 패스키 존재 판정 순서 뒤집기 → F5③ 실패, ⑦ 2단계 `memberId` 대조 제거 → F5①② 실패, ⑧ 재사용 갈래에서도 세션에 다시 쓰기 → F6 실패(로그인·2단계 각각), ⑨ 하한 미달 교체에서 새 식별자·현재 시각에 옛 `options`를 재활용 → F4 챌린지 바이트 단언 실패(로그인·2단계 각각, 리뷰 6-1), ⑩ 제출 판정의 만료 조건을 재사용 하한으로 바꿈 → F8 실패(로그인·2단계 각각, 리뷰 7-1).

## 5. 작업 단계

1. 브랜치 `security/passkey-challenge-stale-tab`.
2. 상태 레코드: 단위 시험 F4 먼저 → `isExpired`·`isReusable(For)`·`REUSE_MIN_REMAINING`.
3. 로그인 화면·필터: 통합 시험 F1·F3·F4(통합)·성공 기준 4 먼저(실패 확인) → `PasskeyLoginService.resumeOrStart` + `AdminMainController.loginPage`(세션 현재 값 전달) + `PasskeyLoginFilter` 판정 후 지우기. `AdminMainControllerTest` 목을 새 메서드로.
4. 코드 화면: 시험 F1·F3·F5 먼저 → `TotpLoginService.resumeOrStartPasskeyAssertion` + `TotpLoginController` GET·제출.
5. 브라우저 시험 F7 변경.
6. 변이 10건 확인.
7. 영향 범위 시험: `--tests 'com.cms.config.auth.*' --tests 'com.cms.admin.member.*' --tests 'com.cms.admin.AdminMainControllerTest' --tests '*ConventionTest'`. `config/**`·공용 로그인 경로라 PR 직전 전체 시험(`-PbrowserTests=exclude`)은 `code-review-loop` 마무리에서, 화면 무변경이라 브라우저 시험은 PR CI `browser-test`에 맡기되 `PasskeyBrowserTest`만 로컬로 1회 돌린다(챌린지 발급 방식이 바뀌어 브라우저 흐름의 전제가 바뀌므로).
8. 실기(dev 기동, playwright MCP Chromium + CDP 가상 인증기 — 사용한 Chromium 버전을 결과에 기록): **조건부 요청이 어느 화면 이동에서도 자동 제출하지 않도록** 화면을 열기 전에 브라우저 컨텍스트 초기화 스크립트로 조건부 지원 판정(`PublicKeyCredential.isConditionalMediationAvailable`)을 `false`로 고정한다(`PasskeyRequestHarness`의 `UNAVAILABLE`과 같은 방식 — 초기화 스크립트라 실패 뒤 리다이렉트로 열린 `?passkeyError`·`?passkeyExpired` 화면에도 적용된다, 리뷰 4-1). CDP 가상 인증기는 탭(페이지)마다 따로라(리뷰 5-1 — `PasskeyBrowserTest`도 페이지별 CDP 세션) **탭 A·B 각각에 CDP 세션과 가상 인증기를 붙이고, 각 탭의 인증기로 같은 회원의 패스키를 하나씩 등록**한다(보안 설정 화면, 회원당 상한 10개 안 — 자격 복사·서명 횟수 관리가 필요 없다). 그다음 같은 컨텍스트(쿠키 공유)에서 로그아웃한 뒤 탭 A·B로 로그인 화면을 연다 → 두 탭의 숨은 `loginId`가 같음을 확인. **회복 경로**는 소비가 실패로 끝나는 순서로 확인한다: B에서 서명이 거부되는 제출(예: `PasskeyBrowserTest`처럼 클릭 전 `data-options`의 `userVerification`을 바꿔 UV 없는 서명 → 서버 거부 `?passkeyError`, 챌린지 소비) → A에서 버튼 → `?passkeyExpired` 안내와 함께 A의 화면이 새 챌린지를 받음(숨은 `loginId` 변화) → A에서 다시 버튼(원래 옵션) → 성공. **성공 뒤의 다른 탭**은 기존 동작이다: 인증 성공이 CSRF 토큰을 교체하므로(Spring Security 7.0.7 `CsrfAuthenticationStrategy` — 리뷰 3-1 출처, 공식 문서 `servlet/exploits/csrf`) 남은 탭의 제출은 챌린지 판정 전에 403이고 새로고침으로 회복한다 — 이번 변경과 무관하며 결과에 관찰만 기록한다. 결과에는 Chromium 버전과 탭별 인증기 구성(인증기 옵션·등록한 패스키 이름)을 남긴다. 수렴 시나리오(시각 조작 필요)는 통합 시험으로 갈음한다. 코드 화면도 두 탭 `assertionId` 동일 확인. 조건부 UI 경로의 경쟁은 기존 `PasskeyBrowserTest`(하네스)가 담당 — 이번 변경은 JS를 바꾸지 않는다.
9. 기록: `com.cms.config` `CLAUDE.md`(패스키 단독 로그인 "챌린지" 문구 — GET 재사용·하한·제출 판정표, 조건부 UI 절의 수용 위험 문장 삭제·#293 해결 표시), `com.cms.admin.member` `CLAUDE.md` 패스키 절(코드 화면 대기 재사용)이 해당하면, `plan/README.md` 행 추가(+ 74행 #285 완료 표시 `f45d1a8`/#294).

## 6. 리스크

- R1 1회용 계약 약화 오해: "불일치는 남긴다/GET은 재사용한다"를 "검증 실패도 남긴다"로 읽을 수 있다 → 코드 주석·`CLAUDE.md`에 "검증에 도달하는 유일한 갈래가 검증 전에 지운다"를 명시, F2 고정.
- R2 남은 수명 단축: 재사용 화면은 최소 2분 30초. 그보다 오래 열어 둔 화면은 지금처럼 만료 안내 뒤 새 챌린지(조건부 UI 계획 결정 5와 같은 경로).
- R3 브라우저 시험: 화면·JS 무변경. 다만 "같은 세션에서 화면을 다시 열면 같은 챌린지"가 되므로, 시험이 GET마다 새 챌린지를 기대하는지(예: 실패 뒤 재시도) 구현 단계 7에서 `PasskeyBrowserTest`를 로컬로 돌려 확인한다("GET마다 대체"를 단언하는 2건은 F7로 바꾼다). 검증 실패·성공은 소비 갈래라 다음 GET은 어차피 새 챌린지다.
- R4 GET 경합(결정 5) — 수용.

## 7. 범위 밖

- 다른 탭이 로그인에 성공한 뒤 남은 탭의 제출(CSRF 토큰 교체로 403 — 지금도 같다, 리뷰 3-1). 자동 회복은 화면 처리 변경이 필요해 범위 밖.

- 두 탭이 서로 다른 챌린지를 동시에 유효하게 갖는 다중 챌린지(결정 1 (c)).
- 로그인·코드 화면 GET의 세션 뮤텍스 편입.
- TOTP 등록 대기(`TotpEnrollment`)·패스키 등록 대기(`PasskeyRegistration`) — 별도 계약("부를 때마다 대체"/#277 재사용)이 있다.
- 재사용 하한의 설정 키화.

## 구현·검증 결과 (2026-11-20)

### Context
#293을 계획 v8대로 구현했다. 같은 세션의 탭들이 패스키 챌린지(로그인 화면 `PasskeyLoginState`·코드 화면 `PasskeyAssertionState`)를 나눠 쓴다. URL 인가 규칙·레이트리밋·스키마·의존성·설정 키·템플릿·JS 무변경.

### 핵심 확정 사항
- 화면 GET은 남은 수명 ≥ `REUSE_MIN_REMAINING`(TTL/2 = 2분 30초)인 챌린지를 재사용하고, **새로 발급한 값만 세션에 쓴다**(`state != current`일 때만 `setAttribute`).
- 제출 판정표(결정 2) 그대로: 일치 ∧ 5분 이내만 검증 전에 꺼내 지움, 만료·다른 타입·(코드 화면) 다른 회원은 지움, 식별자만 다르면 남김.
- 코드 화면은 유효 패스키 존재 판정이 재사용보다 먼저다(`resumeOrStartPasskeyAssertion`).
- 계획과 달라진 점: 없음. 옛 발급 메서드 `start()`·`startPasskeyAssertion()`은 호출처가 없어져 `resumeOrStart*`로 대체했다(공개 메서드 이름 변경 — 호출처는 컨트롤러 2곳과 시험 목 1곳뿐).

### 구현 파일
- 상태: `config/auth/PasskeyLoginState.java`·`PasskeyAssertionState.java`(`isExpired`·`isReusable`/`isReusableFor`·`REUSE_MIN_REMAINING`)
- 발급: `config/auth/PasskeyLoginService.resumeOrStart`, `config/auth/TotpLoginService.resumeOrStartPasskeyAssertion`
- GET: `admin/AdminMainController.loginPage`, `admin/member/controller/TotpLoginController.page`
- 제출: `config/auth/PasskeyLoginFilter.takeState`, `TotpLoginController.takeAssertion`
- 시험: 신규 `PasskeyLoginStateTest`·`PasskeyAssertionStateTest`(경계 8건), `PasskeyLoginIntegrationTest`(F1~F8 — 기존 `staleTabSubmitConsumesLatestChallenge`·`replacedChallengeExpired`를 `staleTabConvergesOnLatestChallenge`·`lowRemainingChallengeReplaced` 등으로 대체, 실행 22 → 28건), `TotpPasskeyLoginIntegrationTest`(기존 `replacedAssertionExpiresWithoutCount` 갱신 + 7건, 실행 14 → 21건, `TotpLoginService` 스파이 추가), `PasskeyBrowserTest`(BFCache 복원 2건 — 재로드를 문서 표지 속성 소멸로 확인·식별자 동일), `AdminMainControllerTest`(목 메서드 교체)
- 문서: `com.cms.config`의 `CLAUDE.md`("챌린지 공유" 항목, 2단계·챌린지 문구, #285 수용 위험 문장 정리), `plan/README.md` 74행 완료·75행 추가, `PLAN-passkey-conditional-ui.md` 상태 줄

### 검증 결과
- TDD: 단위 시험은 메서드 없음(컴파일)으로, 통합 시험은 **지금 동작 그대로의 임시 `resumeOrStart*`(재사용 없음)**로 RED 확인 — 로그인 4건·2단계 3건이 의도한 이유로 실패(재사용·보존·래치 미도달) 뒤 구현으로 GREEN. 나머지 신규 시험(1회용·만료 지우기·4분 유효·하한 아래 새 발급)은 기존 동작을 지키는 회귀 고정이라 처음부터 통과.
- 영향 범위 시험(`com.cms.config.auth.*`·`com.cms.admin.member.*`·`AdminMainControllerTest`·`*ConventionTest`, 브라우저 제외): 88개 클래스 743건 통과.
- `PasskeyBrowserTest`(로컬, `-PbrowserTests=only`): 16건 통과, 2회 연속.
- 변이 15종 전부 검출(scratchpad 스크립트가 적용 → 대상 시험 실행 → 원복): ① 불일치 지우기 되살림 → F1·F4 / ② 일치 지우기 제거 → F6·`challengeSingleUse` / ③ 만료 지우기 제거 → F3 / ④ 재사용 하한 제거 → F1·F3·F4 / ⑤ GET 재사용 제거 → F1 3건 / ⑥ 2단계 재사용을 존재 판정보다 먼저 → F5③ / ⑦a·b 2단계 회원 대조 제거(GET·제출) → F5 / ⑧a·b 재사용 값 다시 쓰기 → F6(로그인·2단계) / ⑨a·b 새 발급에 옛 옵션 재활용 → F4 챌린지 바이트 단언 / ⑩a·b 제출 판정에 재사용 하한 → F8 / JS BFCache 재로드 제거 → F7 2건.
- 실기(dev bootRun 8081 — `CMS_PASSKEY_ALLOWED_ORIGINS=http://localhost:8081`, Playwright MCP Chromium **157.0.8103.80**): 컨텍스트 초기화 스크립트로 `isConditionalMediationAvailable`을 `false` 고정(리뷰 4-1). admin(2단계 미등록)을 강제 등록 화면에서 TOTP 등록(코드는 화면의 설정 키로 브라우저 안에서 계산) → 탭 A·B 각각 CDP 가상 인증기(ctap2·internal·상주 키·UV·자동 존재 확인)를 붙여 패스키 `탭A 인증기`·`탭B 인증기` 등록(DB 2행 확인).
  - 로그인 화면: 로그아웃(쿠키 삭제) 뒤 A·B를 열면 **`loginId` 동일** → B에서 `data-options`의 챌린지를 바꿔 제출 → 서버 검증 실패 `?passkeyError`(소비) → A에서 버튼 → `?passkeyExpired` 안내 + 새 `loginId` → A 재시도 → `/admin` 로그인. 스크린샷 scratchpad `293-tabA-expired.png`·`293-tabA-success.png`(커밋 대상 아님).
  - 코드 화면: 비밀번호 로그인 뒤 A(코드 화면)·B(같은 URL)의 **`assertionId` 동일** → B 패스키 통과 `/admin`. 남은 탭 A의 제출은 **403**(범위 밖 기존 동작 — 성공이 CSRF 토큰을 교체, §7).
  - 경계: CSRF 없는 `POST /admin/login/passkey` 403, 로그인 화면 200, 비밀번호+TOTP 코드 로그인 정상(회귀 없음). 콘솔 오류는 기존 favicon 404와 위 403뿐.
  - 원복: admin의 `member_passkey`·`member_passkey_user`·`member_totp_recovery_code`·`member_totp` 행 삭제(실기 전 상태 — 2단계 미등록·패스키 0). 방문·감사 로그 행은 dev 데이터로 남겼다.
- 코드 리뷰(`code-review-loop`): 기본 리뷰 "결함 없음" → 초점 적대적 리뷰(F1~F8·세션 뮤텍스 밖 GET 경합) approve, 지적 0건.
- PR 직전 전체 시험: `./gradlew test -PbrowserTests=exclude` 311개 클래스 3,276건 통과(건너뜀 12 — 기존 조건부), `-PbrowserTests=only` 22개 클래스 131건 통과.

### 이슈
- 브라우저 시험의 재로드 확인을 처음에 `page.evaluate`로 썼더니 "Execution context was destroyed"로 1회 실패: `waitForResponse`가 내비게이션 응답 도착 때 돌아오고 `waitForLoadState`는 **옛 문서**의 load 상태로 바로 통과해 `evaluate`가 화면 이동과 겹쳤다. 화면 이동을 넘어 재시도하는 locator 대기(`body[data-before-restore]` DETACHED)로 바꿨다(기존 단언이 `inputValue` locator라 드러나지 않던 순서).
- 변이 스크립트(scratchpad, 커밋 대상 아님): `cmd /c gradlew.bat`(상대 이름)이 "명령을 찾을 수 없음"으로 실행되지 않아 이전 결과 파일을 읽은 무효 결과가 나왔다 — 절대 경로 호출 + 결과 파일 수정 시각이 실행 시작 이후인지 검사로 고쳐 다시 돌렸다.
- dev 패스키 허용 origin 기본값이 `http://localhost:8080`이라 8081 실기에는 `CMS_PASSKEY_ALLOWED_ORIGINS`를 맞춰 줘야 한다.

### 후속
- (제안, 미등록) 다른 탭이 로그인에 성공한 뒤 남은 탭의 패스키 제출이 CSRF 403 오류 화면이 되는 UX — 이번 범위 밖(§7), 지금도 같은 기존 동작.
- 실제 기기에서의 확인은 기존 #281.

## 개정 이력

- v1 (2026-11-20): 초안 — 제출 판정만 변경.
- v2 변경 (리뷰 1라운드, 2건 수용): 1-1 만료 안내 GET·다른 탭 GET이 챌린지를 교체해 제출 판정만으로는 브라우저 동작이 바뀌지 않음 — 사실 확인(S3) 후 사용자 결정으로 방향 전환: 화면 GET이 남은 수명 ≥ 2분 30초인 유효 챌린지를 재사용(결정 1), 제출의 불일치 보존은 수렴 경로로 유지(결정 2), F1을 리다이렉트 GET 포함 순서로, 재사용 경계 F4·2단계 F5 확장, Context의 "두 탭 모두 실패"를 실제 브라우저 동작으로 정정 / 1-2 실기 절차에 조건부 요청 자동 제출 회피(자격 없이 연 뒤 자격 추가)·Playwright 1.65.0·Chromium 버전 기록·버튼 경로와 조건부 경로 구분(후자는 기존 하네스 시험) 명시.
- v3 변경 (리뷰 2라운드, 2건 수용): 2-1 뮤텍스 밖 GET이 재사용 값을 다시 써 소비된 챌린지를 되살리는 경합 — GET은 새로 발급한 값만 세션에 쓴다(결정 3), 래치 시험 F6(로그인·2단계)·변이 ⑧·성공 기준 7. 일부 기각: GET의 세션 뮤텍스 편입(쓰기 규칙으로 되살림이 구조적으로 막히고, 매처 변경은 로그인 경로 변경이라 범위 확대) / 2-2 `PasskeyBrowserTest` BFCache 복원 2건이 식별자 변경을 단언 — S9·변경 파일에 포함, 재로드를 `window` 표지로 증명하고 식별자 동일 단언(F7·성공 기준 8), 소비 뒤 새 챌린지는 통합 시험이 고정.
- v4 변경 (리뷰 3라운드, 1건 부분 수용): 3-1 성공 뒤 남은 탭은 CSRF 토큰 교체로 403(챌린지 판정 전) — 실기 단계 8을 "검증 실패로 소비 → 다른 탭 `?passkeyExpired` 회복"으로 바꾸고 성공 뒤 403은 기존 동작으로 범위 밖(§7)에 명시. 기각: 성공 후 403 고정 시험 추가 — 지금 코드에서도 같고 이번 변경(챌린지 발급·소비)과 무관해 범위 확대.
- v5 변경 (리뷰 4라운드, 1건 수용): 4-1 실패 리다이렉트로 열린 새 로그인 화면에서 조건부 요청이 자격으로 즉시 성공해 자동 로그인 → 회복 경로 대신 성공 후 403 경로로 빠짐 — 실기 단계 8을 "자격 없이 열기" 대신 컨텍스트 초기화 스크립트로 조건부 지원 판정을 끄는 방식(모든 화면 이동에 적용)으로 바꿈.
- v6 변경 (리뷰 5라운드, 1건 수용): 5-1 CDP 가상 인증기는 탭별이라 같은 컨텍스트의 두 탭이 인증기를 공유하지 않음 — 실기 단계 8에 탭별 CDP 세션·인증기 + 탭마다 패스키 1개씩 등록, 결과에 탭별 구성 기록. 리뷰어가 서버 설계에는 새 결함이 없다고 명시.
- v7 변경 (리뷰 6라운드, 1건 수용): 6-1 식별자 변경만 단언하면 "새 식별자·현재 시각 + 옛 옵션 재활용" 변이(같은 챌린지 수명 연장)를 못 잡음 — 성공 기준 6·F4에 재사용 시 같은 객체, 새 발급 시 챌린지 바이트 변경·`issuedAt` 지금 단언, 변이 ⑨ 추가. 리뷰어가 서버 설계의 새 1회용 위반 경로는 없다고 명시.
- v8 변경 (리뷰 7라운드, 1건 수용): 7-1 2분 30초 초과~5분 이내 챌린지를 중간 GET 없이 제출해 검증에 도달하는 통합 시험이 없어 제출부가 재사용 하한을 잘못 쓰는 변이를 못 잡음 — F8(발급 4분 상태 제출 → 검증 1회, 로그인·2단계)·변이 ⑩ 추가. 정확히 5분 경계의 호출부 검증(고정 시계)은 기각: 4분 시험이 재사용 하한 오용을 잡고, 경계 식은 단위 시험이 고정하며, 고정 시계는 이 통합 시험 클래스에 추가 컨텍스트를 만든다.
