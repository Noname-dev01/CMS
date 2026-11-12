# PLAN — 세션 관리 화면 "같은 기기" 만료 안내

- 상태: v2 (2026-11-12) — 적대적 리뷰 2라운드 ship(1라운드 지적 2건 수용, 2라운드 지적 0건) → 완료(2026-11-12 · #231 `a3cf65b`)
- 출처: 로드맵 70차 머리말의 남은 후속 후보(본문 항목 아님), `PLAN-remember-me-login.md` "후속" — "세션 관리 화면 문구: 같은 기기의 다른 세션을 만료하면 현재 세션도 끊긴다는 안내 추가 여부"
- 범위 고정: **문구·화면만**. 스키마·인가 정책·URL 규칙·API 응답 형태는 바꾸지 않는다.
- 위험 변경: 없음(인가·데이터 정합성·동시성·마이그레이션을 건드리지 않는다)

## Context

remember-me(#225) v5 설계상 **기기 = 시리즈**다. 브라우저가 세션 쿠키만 잃고 remember-me 쿠키로 복원하면 서버에 남은 옛 세션과 새 세션이 같은 시리즈를 가진다. 세션 관리 화면의 **단일 만료**(`DELETE /admin/api/sessions/{handle}`)는 고른 세션의 시리즈를 먼저 지우고(`RememberMeDeviceRevoker.revokeDevice` — 같은 시리즈의 활성 세션 전부 만료) 그 세션을 만료한다. 그래서 내 옛 세션을 만료하면 **지금 쓰는 세션도 끊기는데**, 화면은 "지금 사용 중인 세션은 만료할 수 없습니다"라고만 말한다(핸들 단위 보호일 뿐). 다른 회원의 세션 하나를 만료할 때도 그 회원의 같은 기기 세션이 함께 끊기고 로그인 상태 유지가 해제되지만 성공 문구는 "세션을 만료했습니다"뿐이다.

정찰로 확인한 사실:

| 사실 | 근거 |
|---|---|
| 목록 응답에 기기(시리즈) 정보가 없다 | `AdminSessionService.ActiveSession`·`SessionItemResponse(handle, lastRequestAt, current)` |
| 단일 만료 = 시리즈 폐기 + 같은 시리즈 세션 전부 만료 + 고른 세션 만료 | `AdminSessionManageService.expireSession` |
| 회원 단위 만료는 현재 세션 ID만 제외 — 현재 기기의 형제 세션은 끊기고 현재 세션은 유지 | `expireSessionsFor(memberId, currentSessionId)`, `RememberMeLoginIntegrationTest`(562~573행 "현재 기기 시리즈 보존·형제 만료") |
| 현재 세션이 끊기면 만료 뒤 목록 재조회가 401 → 목록 비우고 `/admin/login` 이동 | `manage.html` `runExpire` → `loadSessions` → `handleUnauthorized` |
| 시리즈 없는 세션(상태 유지 없이 로그인)은 고른 세션만 만료 | `expireSession` 주석, `revokeDevice(null)` |

## 핵심 확정 사항

### 쟁점 1 — 같은 기기 여부를 정확히 보일 것인가, 조건부 안내로 둘 것인가

- (A) **조건부 정적 안내**(채택): 도움말 문단과 단일 만료 확인창에 "로그인 상태 유지로 접속한 세션이면 같은 기기의 세션이 함께 끊긴다"를 적는다. 서버·API 무변경.
- (B) 응답에 `sameDeviceAsCurrent`(또는 기기 묶음 번호) 필드를 더해 해당 세션에만 배지·경고: 정확하지만 API 응답 형태 변경 + 서비스·DTO·시험 변경이 따른다. 시리즈는 비밀은 아니지만 응답에 기기 상관관계를 새로 노출하는 결정이 생긴다.
- (C) 안내 없음(현행 유지): 실기에서 확인된 놀라움(현재 세션이 갑자기 끊김)이 그대로 남는다.
- **왜 A**: 요청 범위가 "문구·화면만"이고, 이 동작은 설계대로라 사용자가 **미리 알기만 하면** 되는 문제다. 끊겨도 재로그인으로 복구되고 데이터 손실은 "입력 중인 내용" 수준으로 이미 확인창이 경고한다. 정확한 판정(B)은 실제 불편이 반복될 때 별도 작업으로 재평가한다(후속에 기록).

### 쟁점 2 — 어디에 안내하나

- 도움말 문단(`<p class="text-muted small">`)에 한 문장 추가 — 항상 보이는 설명. 별도 요소 `id="sameDeviceNotice"`로 감싸 시험이 존재를 고정한다(문구 자체는 단언하지 않음 — 문구 수정마다 시험이 깨지지 않게. 이 단언은 **요소 존재만** 고정하고 문구·확인창 분기는 실기로 확인한다). 문안: "로그인 상태 유지가 연결된 세션을 만료하면 같은 기기의 다른 세션도 함께 만료되고 로그인 상태 유지가 해제됩니다. 내 다른 세션이 지금 쓰는 기기의 것이면 현재 세션도 함께 끊겨 다시 로그인해야 합니다."
- 기존 도움말 "지금 사용 중인 세션은 만료할 수 없습니다" → **"지금 사용 중인 세션은 직접 선택해 만료할 수 없습니다"**로 한정(v2 — 새 안내와 모순되지 않게).
- **단일 만료 확인창**(`expireOne`): 모든 경우 공통 조건부 문장 "로그인 상태 유지가 연결된 세션이면 같은 기기의 다른 세션도 함께 만료되고 로그인 상태 유지가 해제됩니다"를 덧붙이고, 대상 회원 카드에 현재 세션이 있으면(=내 세션) "지금 사용 중인 세션도 함께 만료되어 다시 로그인해야 할 수 있습니다"를 한 줄 더 붙인다. 브라우저가 아니라 **시리즈**가 기준이다 — 상태 유지 없이 로그인한 같은 브라우저의 옛 세션은 시리즈가 없어(`revokeDevice(null)` 생략) 현재 세션이 유지된다(v2).
- 회원 단위 만료 확인창: 문구 변경 없음 — "지금 사용 중인 세션은 유지됩니다"는 코드상 사실(현재 세션 ID 제외)이다.
- 버튼 `title`·배지·성공 문구: 변경 없음(과설계 회피).
- 기각: 현재 세션이 끊긴 경우 로그인 화면에 사유 표시 — 로그인 화면·만료 전략 변경이 필요해 범위 밖.

### 설계 제약

- 확인창은 기존과 같이 `confirm()`(이 화면의 기존 방식) — 모달로 바꾸지 않는다.
- 이름·아이디 외 사용자 입력이 문구에 들어가지 않는다(새 삽입 지점은 정적 문자열뿐 — XSS 표면 불변).
- `reauth-notice` 조각·`reauth.js`는 건드리지 않는다.

## 작업 단계

1. `templates/admin/session/manage.html` 도움말 문단에 `<span id="sameDeviceNotice">…</span>` 추가.
2. 같은 파일 `expireOne`에서 `member.sessions.some(current)`로 분기해 확인 문구 꼬리 추가.
3. `AdminSessionManagementIntegrationTest` 페이지 200 단언(224행)에 `sameDeviceNotice` 요소 존재 단언 추가.
4. `com.cms.admin.session` `CLAUDE.md` "화면" 항목에 안내 위치 한 줄.

## 성공 기준

- [x] 세션 관리 화면 도움말에 같은 기기 안내가 보인다(ADMIN).
- [x] 내 다른 세션의 [만료] 확인창에 "현재 세션도 끊길 수 있다" 문구가, 다른 회원 세션의 확인창에 "같은 기기 세션·로그인 상태 유지 해제" 문구가 나온다.
- [x] 실기: 같은 기기 형제 세션(세션 쿠키 삭제 → remember-me 복원)을 만들고 옛 세션을 만료하면 확인창 안내 → 현재 세션이 끊겨 `/admin/login`으로 이동(오류 반복·낡은 목록 표시 없음).
- [x] 회귀: 상태 유지 없이 로그인한 다른 세션 만료는 현재 세션을 유지하고 목록이 갱신된다. 회원 단위 만료 확인창·동작 불변. MANAGER는 화면 403 그대로.

## 놓치기 쉬운 입력·실패 모드

| # | 실패 모드 | 고정하는 시험·확인 |
|---|---|---|
| F1 | 안내 요소가 템플릿 수정 중 사라짐 | `AdminSessionManagementIntegrationTest` 페이지 단언에 `id="sameDeviceNotice"` 포함 확인 |
| F2 | 현재 세션이 끊긴 뒤 화면이 낡은 목록·오류를 보이거나 재조회 루프 | 실기(Playwright) — 만료 후 `/admin/login` 도착, 네트워크 요청 1회 재조회 |
| F3 | 상태 유지 없이 로그인한 세션에도 "반드시 끊긴다"로 오인하는 단정 문구 | 문구를 조건부("로그인 상태 유지로 접속한 세션이면")로 작성 — 리뷰·실기에서 확인 |
| F4 | 회원 단위 만료 "현재 세션은 유지" 문구가 거짓이 됨 | 기존 `RememberMeLoginIntegrationTest` 형제 만료·현재 기기 보존 시험(변경 없음) 재실행 |
| F5 | `expireOne` 분기 변경으로 만료 요청 자체가 깨짐·문구 분기 오류 | 실기(Playwright) — 내 세션·다른 회원 세션 두 분기의 확인창 문구, **취소 시 DELETE 미발생**, 승인 시 204·목록 갱신(또는 현재 세션 끊김 → 로그인 이동). 세션 화면을 지나는 기존 브라우저 시험은 없다(v2 정정) |

## 검증 범위

개발 중: `--tests 'com.cms.admin.session.*' --tests 'com.cms.config.auth.RememberMeLoginIntegrationTest' --tests '*ConventionTest'`. PR 직전 전체 1회.

## 구현·검증 결과 (2026-11-12)

### Context
로드맵 70차 머리말의 후속 후보를 `/suggestRoadmap`에서 골라 `/feature`로 진행했다. 계획 v2(적대적 리뷰 2라운드 ship) 그대로 구현했고 계획과 달라진 결정은 없다.

### 핵심 확정 사항
- 조건부 정적 안내(쟁점 1-A) — 서버·API·스키마·인가·URL 무변경.
- 기준은 시리즈: 확인창 공통 문장 + 내 세션이면 "현재 세션도 끊길 수 있다" 한 줄. 회원 단위 만료 확인창은 그대로.
- 기존 도움말 "만료할 수 없습니다" → "직접 선택해 만료할 수 없습니다".

### 구현 파일
- `src/main/resources/templates/admin/session/manage.html` — 도움말 `#sameDeviceNotice`, `expireOne` 확인 문구 분기
- `src/test/java/com/cms/admin/session/AdminSessionManagementIntegrationTest.java` — 페이지 200 시험에 안내 요소 존재 단언
- `src/main/java/com/cms/admin/session/CLAUDE.md` — "화면" 항목에 같은 기기 안내 계약

### 검증 결과
- 영향 범위 시험: `com.cms.admin.session.*`·`RememberMeLoginIntegrationTest`·`*ConventionTest` 136건 통과(실패 0·건너뜀 0). 전체 `./gradlew test`는 PR 직전에 한 번 돌린다.
- 변이: 안내 요소 id를 바꾸면 `page_adminOkAnonymousRedirected`가 단언 실패(F1) — 복원 확인.
- 실기(dev bootRun + Playwright, 스크린샷은 스크래치패드):
  - 골든 패스: ADMIN 화면에 도움말 안내 표시(F3 조건부 문구), MANAGER는 `/admin/session/manage` 403.
  - 확인창 3종(다른 회원 단일·내 세션 단일·내 회원 단위)의 문구가 계획대로이고, **취소 시 DELETE 0건**(F5). `confirm`은 MCP가 대화상자를 가로채 스크립트가 멈춰서 페이지의 `window.confirm`을 기록 함수로 바꿔 확인했다.
  - 다른 회원 세션 승인: 쿠키 세션이라 DELETE 403 → 재인증 204 → DELETE 204 → 목록 200, 대상 MANAGER의 다음 요청은 `/admin/login`, 현재 세션 유지.
  - 같은 기기(세션 쿠키 삭제 → remember-me 복원으로 만든 형제 세션)의 옛 세션 승인: DELETE 204 → 목록 GET 401 **1회** → `/admin/login`, remember-me 쿠키 제거(F2).
  - 회귀: 상태 유지 없이 로그인한 같은 회원의 다른 세션 만료 → 그 세션만 끊기고 현재 세션 유지·목록 1개로 갱신(F3). 회원 단위 만료 "현재 세션 유지"는 기존 `RememberMeLoginIntegrationTest` 통과로 확인(F4).
  - 데이터 원복: 실기용 MANAGER(`sdn-manager`, id 9)를 만들어 썼고 끝에 `DISABLED`로 바꿨다(계정 삭제 API가 없어 행은 남는다). `persistent_logins`에 남은 1행은 이번 실기 이전(02:38) 것이다.

### 이슈
- Playwright MCP는 `page.on('dialog')`로 처리해도 확인창을 모달 상태로 잡아 스크립트 결과가 돌아오지 않았다(기존 기록과 같은 현상) — `window.confirm` 대체로 우회.
- MCP 호출 사이에 `globalThis`가 유지되지 않아 두 번째 컨텍스트는 `browser().contexts()`로 다시 찾았다.

## 후속

- 세션별 같은 기기 표시(쟁점 1-B) — 실제 불편이 반복되면 재평가.

## 개정 이력

- v2 (2026-11-12): 1라운드 리뷰 반영. ① 내 세션 확인창이 "이 브라우저"를 기준으로 단정하던 문구를 시리즈 기준 조건부 문구로 교체하고 기존 도움말 "만료할 수 없습니다"를 "직접 선택해 만료할 수 없습니다"로 한정(수용 — 상태 유지 없는 같은 브라우저 세션은 현재 세션이 유지돼 F3과 충돌). ② F5가 세션 화면을 지나지 않는 `ReauthModalBrowserTest`를 근거로 삼던 것을 Playwright 실기(두 분기 문구·취소 시 DELETE 없음·승인 후 처리)로 교체, ID 단언의 한계 명시(수용 — 코드 검색으로 해당 시험에 `session/manage` 경로 0건 확인). 새 브라우저 시험은 추가하지 않음(템플릿 변경은 TDD 제외 범위, 실기로 확인).
- v1 (2026-11-12): 최초 작성.
