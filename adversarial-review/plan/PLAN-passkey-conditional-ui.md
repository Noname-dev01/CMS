# PLAN — 로그인 화면 패스키 조건부 UI(아이디 칸 자동 완성) (이슈 #285)

- 상태: v4 (2026-11-20) — 계획 리뷰 4라운드 ship, 사용자 승인(2026-11-20), 구현·검증 완료, 머지 #294 `f45d1a8` — 결과는 문서 끝 "구현·검증 결과"
- 이슈: #285. 선행 #280(`PLAN-passkey-login.md`, #286 머지 `5d18cd5`) — 로그인 화면 [패스키로 로그인] 버튼과 `POST /admin/login/passkey` 처리 필터가 이미 있다. 그 계획의 결정 7·§7이 조건부 UI를 범위 밖으로 남겼다.
- 위험 변경: **인증(로그인 입구의 브라우저 쪽 흐름 — 같은 챌린지를 두 브라우저 요청이 나눠 씀)**. 서버 코드·URL 인가 규칙·레이트리밋·스키마·의존성·설정 키 변경 **없음**.
- 바뀌는 파일(예정): `templates/admin/login.html`, `static/js/admin/passkey-login.js`, `static/js/admin/passkey-codec.js`, 시험 `PasskeyBrowserTest`·`AdminMainControllerTest`·`PasskeyLoginIntegrationTest`(기존 서버 동작 고정 1건), 문서 `com.cms.config`의 `CLAUDE.md`.

## 1. Context

#285 목표: 로그인 화면 아이디 칸에서 브라우저가 저장된 패스키를 자동 완성 목록으로 제안하게 한다(WebAuthn 조건부 UI — `autocomplete="username webauthn"` + `navigator.credentials.get({mediation: "conditional"})`). 지금은 버튼을 눌러야만 패스키 창이 뜬다. 기능·보안 차이는 없고 사용성 개선이다.

완료 기준(이슈 원문):
1. 조건부 요청과 기존 버튼의 동시 요청 처리(하나를 취소하고 다른 하나 시작), 미지원 브라우저는 버튼만
2. 같은 세션 챌린지(`PasskeyLoginState`) 재사용 여부 결정
3. 브라우저 시험(가상 인증기) + 실기 확인

### 정찰에서 확인한 사실

| # | 사실 | 출처 |
|---|---|---|
| S1 | 로그인 화면 GET마다 `PasskeyLoginState{loginId, options, issuedAt}` **하나**를 세션에 두고(대체), 옵션 JSON을 `#passkeyForm`의 `data-options`로, `loginId`를 숨은 필드로 그린다. 기능 꺼짐·옵션 생성 실패면 블록과 스크립트가 없다. | `AdminMainController.loginPage`, `login.html:84-112` |
| S2 | 제출 때 필터가 세션 상태를 **꺼내며 지우고**(1회용) `loginId`·5분이 맞지 않으면 검증 전에 `?passkeyExpired`. 옵션은 UV `REQUIRED`·`allowCredentials` 비움(discoverable)·timeout 5분. | `PasskeyLoginFilter.takeState`, `PasskeyLoginState.TTL`, `PasskeyRelyingParty.discoverableRequestOptions` |
| S3 | `passkey-login.js`는 **2단계 코드 화면(`login-totp.html`)도 같은 요소 ID로 쓴다**(`#btnPasskeyLogin`·`#passkeyForm`·`#passkeyBlock`). 2단계 화면에는 아이디 입력이 없다. | `login-totp.html:47-83` |
| S4 | 버튼은 클릭할 때마다 `data-options`를 다시 읽는다. 브라우저 쪽 실패(취소·시간 초과·자격 없음)는 서버로 보내지 않고 안내 문구만 보인다. | `passkey-login.js:22-37` |
| S5 | `CmsPasskey.get(options)`는 `mediation`·`signal`을 받지 않는다. 호출처는 `passkey-login.js` 한 곳(설정 화면은 `create`만). | `passkey-codec.js`, `grep CmsPasskey.get` |
| S6 | 아이디 입력(`input[name=username]`)에 `autocomplete` 속성이 없다. | `login.html` |
| S7 | **Chromium CDP 가상 인증기는 조건부 요청에 즉시 응답한다** — `automaticPresenceSimulation: true`에서 상주 자격이 있으면 1ms 안에 resolve, 없으면 1ms 안에 `NotAllowedError`로 reject. 실제 브라우저처럼 "사용자가 자동 완성 항목을 고를 때까지 대기"를 재현하지 못한다. `isConditionalMediationAvailable()`은 `true`. 대기 중인 조건부 요청을 `AbortController.abort()`하면 `AbortError`로 reject되고 그 뒤 모달 `get`은 정상 성공한다. | 실측 2026-11-20(scratchpad 일회용 페이지 + Playwright MCP Chromium, 시험이 쓰는 Playwright Java 1.65.0의 Chromium과 버전이 다를 수 있음 — 구현 1단계에서 시험 Chromium으로 재확인) |
| S8 | `PasskeyBrowserTest`의 버튼 시험 3건(`passwordlessLogin`·`signatureWithoutUserVerificationRejected`·`signatureCounterRegressionRejected`)은 상주 자격이 있는 가상 인증기로 `/admin/login`에 들어간 뒤 버튼을 누른다. 그중 하나는 **클릭 전에 `data-options`를 바꾼다**. S7 때문에 조건부 요청을 그대로 켜면 페이지 진입 즉시 자동 제출돼 이 시험들의 전제가 깨진다. | 시험 코드 |
| S9 | WebAuthn L3: 조건부 요청은 `mediation: "conditional"`, 지원 여부는 `PublicKeyCredential.isConditionalMediationAvailable()`, 입력 필드의 `autocomplete` 토큰 목록 마지막이 `webauthn`이어야 브라우저가 패스키를 자동 완성 후보로 보인다. 같은 문서의 한 요청이 대기 중일 때 다른 `get`을 부르는 동작은 브라우저마다 다르다(Chromium은 실측상 성공, 그 밖은 **미확인**) — 그래서 명시적으로 취소한 뒤 시작한다. **조건부 요청의 수명 타이머(`lifetimeTimer`)는 무한대**다 — 옵션 timeout이 적용되지 않는다(리뷰 1-5). | W3C WebAuthn Level 3(https://www.w3.org/TR/webauthn-3/) §5.1.4 `isConditionalMediationAvailable`·§5.1.4.1 `[[DiscoverFromExternalSource]]`의 lifetimeTimer 단계, HTML `autocomplete` `webauthn` 토큰, S7 |
| S10 | `PasskeyLoginFilter.takeState`는 `loginId`를 대조하기 **전에** 세션 상태를 지운다 — 탭 A(옛 챌린지)가 제출하면 탭 B가 GET으로 받은 최신 챌린지까지 사라져 탭 B의 제출도 `?passkeyExpired`가 된다. 기존 시험은 "다른 탭 교체"(`PasskeyLoginIntegrationTest` F3, 225행)와 "1회용"(249행)을 따로 고정하고, 둘을 잇는 결과는 고정하지 않는다. | 코드, 리뷰 1-6 |

## 2. 핵심 설계 결정

### 결정 1 — 챌린지: 화면의 챌린지 하나를 조건부 요청과 버튼이 나눠 쓴다(서버 무변경)
- 선택지: (a) 공유 — 같은 `data-options` / (b) 조건부 요청용 챌린지를 별도 JSON API로 발급 / (c) GET이 챌린지 둘을 세션에 둔다.
- **(a) 채택**. 이유: 챌린지의 역할은 "이번 세션에서 서버가 낸 값에 대한 새 서명"을 보장하는 것이고, 서버는 **제출 때 꺼내며 지워**(S2) 한 챌린지로 검증되는 어설션은 최대 1개다. 화면에서 두 요청이 같은 챌린지를 써도 제출되는 것은 하나이고(결정 2의 제출 가드), 혹시 두 어설션이 모두 만들어져 둘 다 제출돼도 두 번째는 상태가 없어 `?passkeyExpired`로 검증 전에 끝난다. origin·rpId·UV 요구는 옵션이 같으니 그대로다.
- (b) 기각: 새 무인증 공개 JSON API(외부 계약·레이트리밋·인가 규칙 추가)가 생긴다. (c) 기각: 서버·필터를 바꿔야 하는데 (a)보다 막는 위협이 없다.
- 결과: 버튼 실패 뒤 조건부 요청을 다시 시작할 때도 같은 챌린지를 쓴다(제출 전이라 소비되지 않았다).

### 결정 2 — 브라우저 쪽 요청 조정(취소 후 시작, 제출 1회)
`passkey-login.js`에 화면 하나당 상태 하나를 둔다 — `phase ∈ {idle, conditional, modal, submitted}`와 대기 중인 조건부 요청(`AbortController` + 끝남 promise). 요청은 언제나 **많아야 하나**만 대기하고, **비동기 지점(지원 판정 완료·취소 대기 완료·요청 결과 도착) 뒤에는 반드시 `phase`를 다시 보고** 지금도 그 동작이 유효할 때만 진행한다(리뷰 1-1).
1. **시작**: 조건부 대상 화면(결정 3)이고 `CmsPasskey.supported()`이면 `conditionalAvailable()`을 부른다. 결과가 `true`이고 **그 시점의 `phase`가 `idle`일 때만** 조건부 요청을 시작한다(`phase = conditional`). 판정이 거짓·예외이거나 그 사이 버튼·비밀번호 제출로 `phase`가 바뀌었으면 시작하지 않는다(버튼만 — 완료 기준 1).
2. **조건부 결과 도착**: `phase`가 여전히 `conditional`이고 이 요청이 현재 요청일 때만 처리한다. 성공 → 자격을 숨은 필드에 넣고 `phase = submitted` 후 패스키 폼 제출. 그 밖(늦게 온 성공 포함)은 버린다.
3. **조건부 실패**: `AbortError`(우리가 취소)·그 밖의 오류 모두 **안내 문구를 보이지 않고 다시 시작하지 않는다**(`phase`가 `conditional`이면 `idle`로). 이유: 사용자가 버튼을 누르지 않았으니 알릴 일이 없고, 자격이 없을 때 즉시 reject하는 구현(S7 가상 인증기)에서 자동 재시작은 바쁜 반복이 된다.
4. **버튼 클릭**: `phase`가 `submitted`·`modal`이면 무시. `conditional`이면 `phase = modal`로 먼저 바꾸고(늦게 온 조건부 성공을 2가 버리도록) `abort()` 뒤 **그 요청의 끝남 promise를 기다린다**. 기다린 뒤 `phase`가 여전히 `modal`일 때만 모달 요청을 시작한다. 버튼은 그 동안 비활성.
5. **버튼(모달) 결과**: 결과 도착 뒤 `phase`가 `modal`일 때만 처리한다. 성공 → `phase = submitted` 후 제출. 실패 → 기존처럼 안내 문구, 버튼 재활성, `phase = idle` 뒤 조건부 대상이면 조건부 요청을 **한 번** 다시 시작한다(1과 같은 경로라 판정도 다시 한다). 3의 규칙 때문에 이 재시작이 즉시 실패해도 반복되지 않는다.
6. **비밀번호 폼 제출**(리뷰 1-2): 비밀번호 폼의 `submit` 이벤트에서 `phase`가 `submitted`이면 기본 동작을 막는다(패스키 제출이 이미 진행 중 — 이중 POST·계정 전환 방지). 아니면 `phase = submitted`로 두고 대기 중인 조건부 요청을 `abort()`한다(늦게 온 조건부 성공은 2가 버린다). 모달 요청 중이면 브라우저 창이 떠 있어 사실상 낼 수 없지만, 낸 경우에도 5가 `phase` 재확인으로 모달 결과를 버린다.
7. 제출은 화면당 한 번이다 — `submitted`는 되돌리지 않는다(제출 뒤에는 화면 이동으로 끝난다).
8. **화면 복원**(리뷰 3-1): 뒤로 가기 등으로 화면이 BFCache에서 복원되면(`pageshow` 이벤트의 `persisted === true`) `location.reload()`로 새 GET을 받는다 — 세션 챌린지는 이미 소비·교체됐을 수 있어 `phase`만 되돌리면 만료 제출이 되고, 새 GET이 새 챌린지·새 상태·조건부 요청 재시작을 한 번에 준다. 패스키 블록이 있는 두 화면(로그인·2단계) 모두에 적용한다(기존 버튼도 제출 뒤 비활성으로 복원되는 같은 문제가 있다). 복원 여부는 브라우저·버전·`Cache-Control: no-store` 처리에 따라 다르므로(Chrome 문서 https://developer.chrome.com/docs/web-platform/bfcache-ccns) 복원을 막으려 하지 않고 복원됐을 때 다시 받는다.
- 기각: 취소 없이 모달을 바로 부름 — Chromium은 실측상 되지만(S7) 다른 브라우저 동작이 미확인(S9)이다.

### 결정 3 — 조건부 UI는 로그인 화면에서만
- `login.html`의 패스키 폼에 `data-conditional="true"`를 두고, JS는 이 표지가 있을 때만 결정 2의 1·5를 한다. 2단계 코드 화면은 표지가 없어 지금과 똑같이 버튼만이다(S3 — 그 화면에는 아이디 입력이 없고, 패스키 대기 상태도 회원에 묶인 별도 챌린지다).
- 아이디 입력의 `autocomplete="username webauthn"`은 **패스키 옵션이 있을 때만** 그린다(`th:attr`). 기능이 꺼진 화면(운영 기본)에는 지금처럼 속성이 없다 — 조건부 요청이 없는 화면에 `webauthn` 토큰만 두는 것은 의미가 없다.
- 기각: 별도 JS 파일 — 같은 폼·버튼·제출을 다루므로 둘로 나누면 제출 가드가 갈라진다.

### 결정 4 — 코덱 확장은 덧붙이기만
- `CmsPasskey.get(options, request)`의 두 번째 인자(선택)로 `{mediation, signal}`을 받아 `navigator.credentials.get`에 그대로 넘긴다. 첫 인자만 주는 기존 호출은 동작이 같다.
- `CmsPasskey.conditionalAvailable()`(async)을 더한다 — `PublicKeyCredential.isConditionalMediationAvailable`이 없으면 `false`, 예외도 `false`.

### 결정 5 — 챌린지 만료는 기존 안내에 맡긴다
- 화면을 5분 넘게 열어 둔 뒤 자동 완성으로 고르면 서버가 `?passkeyExpired`로 끝내고(S2), 그 화면 GET이 새 챌린지를 내 조건부 요청이 다시 시작된다. 기존 버튼과 같은 동작이다. 만료 전에 챌린지를 갱신하는 장치는 두지 않는다(과설계 — 새 API가 필요하다).
- 브라우저 쪽 조건부 요청은 수명 제한이 없다(S9 — 규격상 `lifetimeTimer` 무한대). 서버의 5분 TTL(`PasskeyLoginState.TTL`)과는 별개라, 오래 열어 둔 화면의 자동 완성 선택은 서버에서 만료로 끝난다 — 위 안내 흐름 그대로다. 규격과 달리 브라우저가 timeout을 적용해 reject해도 결정 2-3대로 조용히 끝나고 버튼은 그대로 동작한다.
- **다른 탭**(S10, 리뷰 1-6): 같은 세션의 두 탭이 각각 로그인 화면을 열면 최신 GET의 챌린지만 유효하고, 옛 탭의 제출은 최신 탭의 챌린지까지 지운다(1회용 꺼내기가 대조보다 먼저). 그래서 옛 탭 제출 뒤에는 최신 탭의 제출도 만료 안내가 된다 — 다시 열면 된다. 조건부 UI는 화면을 오래 열어 두게 하므로 겪을 가능성이 커지지만, **서버 동작은 바꾸지 않고 수용한다**: 대조 뒤에만 지우도록 바꾸는 것은 인증 필터 변경(위험 영역)이고, 실패가 안내 후 재시도로 끝나 보안·데이터 영향이 없다. 결과는 시험으로 고정한다(F8). 개선은 후속 후보(R3).

## 3. 성공 기준

1. 패스키 기능이 켜진 로그인 화면: 아이디 입력에 `autocomplete="username webauthn"`, 지원 브라우저에서 페이지 로드 때 조건부 요청 1회 시작(`mediation: "conditional"`과 `signal`이 실제 인자로 넘어감). 상주 자격이 있는 가상 인증기면 **클릭 없이** 대시보드로 가고 `POST /admin/login/passkey`는 정확히 1회다.
2. 조건부 미지원(`isConditionalMediationAvailable` 거짓): 조건부 요청 0회, 버튼으로 기존과 같이 로그인.
3. 버튼 클릭: 대기 중인 조건부 요청이 먼저 취소(`AbortError`)된 **뒤** 모달 요청이 시작된다. 성공하면 대시보드.
4. 버튼 실패: 안내 문구, 조건부 요청 정확히 1회 재시작. 조건부 요청의 즉시 실패는 재시작을 일으키지 않는다(요청 수가 늘지 않음).
5. 2단계 코드 화면: 조건부 요청 0회, 버튼 동작 그대로(기존 시험 통과).
6. 기능 꺼짐 로그인 화면: `webauthn` 토큰 없음(기존과 같은 HTML).
7. 지원 판정이 늦게 끝나도, 취소 직전에 조건부 요청이 성공해도, 비밀번호 폼과 겹쳐도 화면의 로그인 POST는 합쳐서 정확히 1회다.

## 4. 실패 모드와 고정 시험

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 대기 중인 조건부 요청을 취소하지 않고 모달을 시작 → 비 Chromium 브라우저에서 버튼이 실패할 수 있다 / 코덱이 `signal`·`mediation`을 넘기지 않음 | `PasskeyBrowserTest` 신규 "버튼은 조건부 요청 취소가 끝난 뒤 모달 시작" — 초기화 스크립트로 `navigator.credentials.get`을 감싼다: 조건부 호출(실제 인자의 `mediation === "conditional"`)은 **실제로 넘어온 `signal`**의 abort 때만 `AbortError`로 끝나는 대기 promise로 바꾸고(signal이 없으면 끝나지 않아 시험이 실패), 모달 호출은 실제 인증기로 넘긴다. 사건 순서(`conditional-start`·`conditional-signal-aborted`·`conditional-settled`·`modal-start`)를 기록 → `conditional-settled`가 `modal-start`보다 앞 + 대시보드 + 패스키 POST 1회 (리뷰 1-4) |
| F2 | 조건부 요청 실패 때 자동 재시작 → 즉시 reject하는 환경에서 바쁜 반복 | 신규 "조건부 실패는 재시작하지 않는다" — 감싼 조건부 호출을 즉시 `NotAllowedError`로 끝내고 요청 장벽 뒤 조건부 호출 수 = 1, 안내 문구 숨김 |
| F3 | 버튼 실패 뒤 조건부 요청을 다시 시작하지 않음(자동 완성 상실) 또는 여러 번 시작 | 신규 "버튼 실패 → 안내 + 조건부 정확히 1회 재시작" — 감싼 모달 호출을 `NotAllowedError`로 끝내고 조건부 호출 수 = 2 |
| F4 | 공유 JS가 2단계 코드 화면에서도 조건부 요청을 시작 | 신규 "2단계 화면은 조건부 요청 0회" — 같은 감싸기로 호출 수 0 + 기존 `registerLoginAndDelete` 통과 |
| F5 | 꺼짐 화면에 `webauthn` 토큰 / 켜짐 화면에 없음 / 2단계 화면에 조건부 표지 | `AdminMainControllerTest` 신규 2건(MockMvc 렌더링): 켜짐(`start()`가 상태 반환) → 아이디 입력 `autocomplete="username webauthn"`·패스키 폼 `data-conditional="true"`, 꺼짐(`start()` 빈 값) → 둘 다 없음. 브라우저 골든 패스에서는 속성을 보지 않는다 — 조건부 자동 성공이 진입 직후 화면을 떠나 시점 경쟁이 된다(리뷰 3-2) |
| F9 | BFCache 복원 화면이 `submitted`로 굳거나 소비된 챌린지로 제출(리뷰 3-1) | 브라우저 신규 — 로그인 화면에서 `loginId` 값을 읽고, 페이지에 합성 `pageshow`(`new PageTransitionEvent('pageshow', {persisted: true})`)를 보내 새 화면 로드를 기다린 뒤 `loginId`가 바뀌었음을 단언. 2단계 화면은 `assertionId`로 같은 1건. 실제 복원은 Playwright가 BFCache를 끄므로(공식 문서 Navigations "Back/forward cache" — 1.65.0에서 확인 후 기록) 실기에서 확인 |
| F6 | 비동기 경쟁(리뷰 1-1): ⓐ 지원 판정이 끝나기 전 버튼 클릭 → 판정 뒤 조건부 요청까지 시작해 요청 둘 대기 ⓑ `abort()` 직전 조건부 성공 → 제출 뒤 모달까지 시작 | 신규 2건 — ⓐ `isConditionalMediationAvailable`을 시험이 풀 때까지 대기하는 promise로 바꾸고 버튼 클릭(모달은 감싸서 대기) → 판정 해제 → 요청 장벽 뒤 조건부 호출 0회 ⓑ-1 조건부 성공 처리가 버튼 클릭보다 먼저 끝남: 감싼 조건부 호출을 성공시키고 패스키 POST를 라우트로 붙잡은 상태(`submitted`)에서 버튼 클릭 → 모달 호출 0회, 패스키 POST 1회 ⓑ-2 버튼이 `modal`로 바꾼 뒤 abort 처리 중 조건부 성공: 감싼 조건부 호출이 abort 신호를 받는 순간 **성공 값으로 resolve** → 조건부 결과는 버려지고 모달 호출 1회(감싼 모달을 붙잡아 둔 동안 POST 0회), 모달을 실제 인증기로 넘긴 뒤 패스키 POST 1회·대시보드. 요청 수는 `page.onRequest`로 센다(리뷰 1-3, 2-1) |
| F7 | 비밀번호 폼 제출과 조건부 성공이 겹쳐 POST 둘(다른 계정 전환 가능)(리뷰 1-2) | 신규 — 조건부 호출을 대기시킨 채 비밀번호 폼 제출 → 그 직후 감싼 조건부가 성공 값으로 resolve → `POST /admin/login` 1회·패스키 POST 0회. 역순(조건부 성공으로 `submitted` 뒤 비밀번호 폼 제출이 막힘)은 제출을 붙잡아 둔 상태에서 1건 |
| F8 | 다른 탭 챌린지 교체(S10)의 결과가 바뀌어도 모름 | `PasskeyLoginIntegrationTest` 신규(MockMvc, 서버 동작 고정) — 같은 세션으로 GET 두 번(loginId A·B) → A 제출 `?passkeyExpired` → B 제출도 `?passkeyExpired`, 둘 다 검증 없음 |

브라우저 시험은 시간값(1ms 등)에 기대지 않는다 — 대기는 사건 기록·`waitForFunction`·요청 장벽(`awaitRequestBarrier`)으로 하고, 부정 단언(호출 0회)은 장벽 뒤에 한다(`src/test/java` CLAUDE.md 규약).

기존 버튼 시험 3건(S8)은 초기화 스크립트로 `isConditionalMediationAvailable`을 `false`로 만들어 **미지원 브라우저 경로**로 돌린다 — 성공 기준 2를 겸한다(그중 `passwordlessLogin`에 "조건부 호출 0회" 단언 추가).

## 5. 작업 단계

1. 시험 Chromium(Playwright Java 1.65.0이 실행하는 것 — `browser.version()`으로 기록)으로 S7 재확인(가상 인증기가 조건부 요청에 대기 없이 응답하는가). 측정 코드와 버전을 결과 문서 "검증 결과"에 남긴다. 결과가 다르면 골든 패스 시험 설계를 보고 후 조정.
2. 시험 먼저: `PasskeyBrowserTest`에 감싸기 초기화 스크립트 헬퍼와 F1~F5 시험, 기존 3건에 미지원 스크립트 — 실패 확인.
3. `passkey-codec.js` 확장(결정 4) → `passkey-login.js` 상태 흐름(결정 2·3) → `login.html` 속성(결정 3).
4. 영향 범위 시험: `./gradlew test -PbrowserTests=only --tests '*Passkey*' --tests '*TotpLoginBrowserTest'`, `./gradlew test --tests 'com.cms.admin.AdminMainControllerTest' --tests 'com.cms.config.auth.PasskeyLoginIntegrationTest' --tests '*ConventionTest'`.
5. 실기: dev bootRun + Playwright MCP Chromium 가상 인증기로 골든 패스·버튼·2단계 화면 확인. 실제 기기(Windows Hello·휴대폰) 자동 완성 목록 표시는 사용자 확인 요청(불가하면 #281 운영 도메인 실기와 함께 남긴다).
6. 문서: `com.cms.config`의 `CLAUDE.md` "패스키 단독 로그인"에 조건부 UI 한 항목, `PLAN-passkey-login.md` §7에 #285 완료 참조, 이 계획서 결과 갱신, `plan/README.md` 인덱스 행.

## 6. 리스크

- R1 실제 브라우저의 자동 완성 표시 조건(아이디 칸 포커스, 저장된 패스키 유무, 브라우저별 지원)은 가상 인증기로 재현되지 않는다(S7) — 시험은 "요청 조정이 맞는가"까지만 고정하고, 목록 표시는 실기에 맡긴다.
- R3 다른 탭 챌린지 교체(S10, 결정 5) — 수용. 후속 후보: `takeState`가 `loginId` 일치 때만 지우기(인증 필터 변경이라 별도 계획).
- R2 가상 인증기의 조건부 즉시 응답에 기대는 골든 패스 시험은 Chromium 동작이 바뀌면(대기하도록) 시간 초과로 실패한다 — 그때는 감싸기 방식으로 옮긴다.

## 7. 범위 밖
- 2단계 코드 화면의 조건부 UI, 비밀번호 입력의 `webauthn` 토큰, 챌린지 자동 갱신, 서버 코드·레이트리밋 변경.

## 구현·검증 결과 (2026-11-20)

### Context
#285 조건부 UI를 계획 v4대로 넣었다. 서버 코드·URL 인가 규칙·레이트리밋·스키마·의존성·설정 키 무변경.

### 핵심 확정 사항
- 챌린지 공유(결정 1), `phase` 상태 하나의 요청 조정(결정 2-1~2-8), 로그인 화면 한정(결정 3), 코덱 덧붙이기(결정 4) — 계획과 같다.
- **계획과 달라진 것(시험 방법만)**: §4는 "패스키 POST를 라우트로 붙잡고 페이지 상태를 읽는다"였지만, Playwright Java 1.65.0에서 **네트워크(`page.route`)에서 붙잡힌 폼 제출 중에는 `page.evaluate`가 끝나지 않았다**(스레드 덤프: `PasskeyRequestHarness.events` → `FrameImpl.evaluate`에서 무기한 대기). 그래서 하네스가 **페이지 안에서** 제출을 기록·붙잡고(`HTMLFormElement.prototype.submit`·창 `submit` 이벤트 감싸기, `holdSubmit`), 화면 이동을 일으키는 호출은 `later`(다음 태스크)로 부른다. 실제 네트워크 POST 수는 화면 이동이 끝난 뒤 `page.onRequest`로 센다(골든 패스·F6ⓑ-2·F7). 단언 대상은 같다.

### 구현 파일
- `static/js/admin/passkey-codec.js` — `get(options, request)`·`conditionalAvailable()`
- `static/js/admin/passkey-login.js` — `phase` 상태 흐름, 비밀번호 폼 연동, `pageshow.persisted` 새로고침
- `templates/admin/login.html` — 비밀번호 폼 `id="loginForm"`, 아이디 입력 `th:autocomplete`(켜짐일 때만 `username webauthn`), 패스키 폼 `data-conditional="true"`
- 시험: `support/PasskeyRequestHarness`(신규), `PasskeyBrowserTest`(신규 10건 + 기존 3건 미지원 경로), `AdminMainControllerTest`(F5 2건), `PasskeyLoginIntegrationTest`(F8 1건)
- 문서: `com.cms.config`의 `CLAUDE.md` "패스키 단독 로그인 > 조건부 UI"

### 검증 결과
- **S7 재확인**(시험 Chromium **158.0.8127.18**, Playwright Java 1.65.0): 가상 인증기(`automaticPresenceSimulation: true`)는 자격이 있으면 조건부 요청에 대기 없이 성공 — 골든 패스 시험이 클릭 없이 대시보드에 도착.
- 구현 전 빨간불: 신규 브라우저 시험 10건 모두 실패(조건부 요청이 시작되지 않아 대기 시간 초과), 기존 5건 통과.
- 영향 범위 시험: `PasskeyBrowserTest` 15/15·`TotpLoginBrowserTest` 3/3, `AdminMainControllerTest`·`PasskeyLoginIntegrationTest`·`*ConventionTest` 21개 클래스 111건 모두 통과. (전체 시험은 PR 직전 1회)
- **변이 실험 10건 전부 검출**: M1 취소·대기 없이 모달 → F1 실패 / M2 조건부 실패 시 재시작 → F2 / M3 모달 실패 뒤 재시작 없음 → F3 / M4 판정 뒤 `phase` 재확인 없음 → F6ⓐ / M5 비밀번호 폼 미연동 → F7 2건 / M6 늦은 조건부 성공도 제출 → F6ⓑ-2·F7 / M7 BFCache 복원 무시 → F9 / M8 코덱이 `signal` 미전달 → F1·골든 패스 / M9 버튼의 `submitted` 무시 제거 → F6ⓑ-1 / M10 템플릿 `autocomplete` 제거 → F5(MockMvc). 각 변이 뒤 원본 복원(md5 일치).
- **코드 리뷰 1라운드(적대적)에서 F4 검출력 보완**: F4·F9를 한 시험에서 지원 판정 "미지원"으로 돌려, 공유 JS가 `data-conditional` 표지를 무시해도 2단계 화면 호출이 0회로 남았다(변이 누락). F4를 분리해 지원 판정 실제(true)·조건부 HOLD로 돌리고 같은 설정의 로그인 화면 1회를 양성 대조로 둔다. **M11 화면 제한 제거(`conditional = true`) → F4 실패** 확인, F9는 로그인 화면 복원 시험으로 따로 둔다(브라우저 시험 16건).
- **실기**(dev bootRun 8081 + Playwright MCP Chromium 가상 인증기, `CMS_PASSKEY_ALLOWED_ORIGINS=http://localhost:8081`): ① 패스키 없는 로그인 화면 — 조건부 요청 1회, 즉시 거부돼도 안내 없음·반복 없음 ② 비밀번호 → 강제 등록 → 코드 로그인(기존 흐름 회귀 없음), 2단계 코드 화면 조건부 요청 0회 ③ 보안 설정에서 패스키 등록 → 쿠키 비우고 로그인 화면 진입 → **클릭 없이 대시보드**, POST는 `/admin/login/passkey` 1회 ④ 콘솔 오류 없음(기존 `favicon.ico` 404만). 데이터 원복: dev 관리자의 `member_totp`·복구 코드·`member_passkey` 행 삭제(검증 전과 같은 0건). 감사 로그·방문 기록 행은 남겼다.
- **실기로 확인하지 못한 것**: 실제 기기(Windows Hello·휴대폰)에서 아이디 칸 자동 완성 목록에 패스키가 보이는지, 실제 BFCache 복원(Playwright는 BFCache를 끈다) — 운영 도메인 실기(#281) 때 함께 본다.

### 이슈
- 구현 중 Docker Desktop이 꺼져 있어 Testcontainers가 `initializationError` — 기동 후 해결(환경).
- 변이 스크립트 첫 실행이 `cmd /c gradlew.bat`을 찾지 못해 Gradle이 돌지 않았는데, 직전 결과 XML을 읽어 "모두 통과"로 보였다 — 전체 경로 호출 + 실행 전 결과 파일 삭제로 고쳐 다시 돌렸다(위 결과는 재실행분).

### 후속
- R3: `PasskeyLoginFilter.takeState`가 `loginId` 일치 때만 지우기(인증 필터 변경 — 별도 계획) — **#293**.
- 실제 기기 자동 완성 목록·BFCache 복원 확인 — **#281에 항목 추가**(댓글, 2026-11-20).

## 개정 이력
- v1 (2026-11-20): 최초 작성.
- v2 변경 (리뷰 1라운드, 6건 모두 수용): 1-1 결정 2를 `phase` 상태 + 비동기 지점 뒤 재확인으로 다시 씀, F6 2건 / 1-2 비밀번호 폼 제출을 같은 상태에 포함(결정 2-6), F7 / 1-3 제출 횟수는 방문 기록이 아니라 POST 수로 단언(성공 기준 1·7) / 1-4 감싸기는 실제 인자의 `signal`로만 끝나고 settled 순서까지 기록(F1) / 1-5 조건부 요청 수명 무한대로 정정(S9·결정 5), 시험 Chromium 버전 기록·시간값 비의존 / 1-6 다른 탭 교체 결과를 S10·결정 5·R3으로 수용 명문화, 서버 동작 고정 시험 F8(서버 코드 무변경).
- v4 변경 (리뷰 3라운드, 2건 수용): 3-1 BFCache 복원 시 `location.reload()`로 새 챌린지·상태(결정 2-8, 두 화면), F9 / 3-2 `autocomplete`·`data-conditional` 속성 검증을 브라우저 골든 패스에서 MockMvc 렌더링 시험(켜짐·꺼짐)으로 옮김(F5).
- v3 변경 (리뷰 2라운드, 1건 수용): 2-1 F6ⓑ의 기대 결과가 결정 2-4(먼저 `modal`로 전환 → 늦은 조건부 성공 폐기)와 반대였다 — ⓑ-1(조건부 성공이 먼저: 모달 0회·POST 1회)·ⓑ-2(전환 뒤 성공: 조건부 폐기·모달 1회·모달 성공 뒤 POST 1회)로 나눔.
