# PLAN — 정적 페이지 관리와 메뉴 관리를 한 화면으로 통합 (방법 3′)

> 상태: v6 — 적대적 리뷰 5라운드 **ship**(전 라운드 codex gpt-6.1-sol `codex exec`; 5라운드 도달로 종료, 마지막 지적은 P3 1건을 반영). 사용자 승인(2026-10-11) 후 **구현·시험·실기 검증 완료, 머지 완료(2026-10-11 · PR #130 `a894ca6`)**. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- 5라운드: **ship — 새 실질 결함 없음(P3 1건 반영).**
- v6 변경(5라운드 반영 — codex gpt-6.1-sol `codex exec`, P3×1, 결정 필요 0건):
  - R5-1(P3 쟁점 6이 모달을 `#menuTabPanel` 밖으로 옮기는데 쟁점 7은 메뉴 CSS를 전부 `#menuTabPanel` 하위로 스코프해, 아이콘 선택 모달(`#iconPickerModal`)의 `.icon-grid`·`.icon-item` 규칙(그리드 배치·최대 높이 스크롤·선택 강조)이 매칭되지 않음): 쟁점 7 정정 — CSS는 **조각별 루트 id**로 스코프한다(패널 규칙 → `#menuTabPanel`, 모달 규칙 → 각 모달 id `#iconPickerModal`·`#permanentDeleteModal`). 실기에 아이콘 선택기(배치·검색·선택 강조·스크롤) 검증을 추가한다.
- v5 변경(4라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×2, 결정 필요 0건):
  - R4-1(P2 메뉴의 `data-backdrop="static"`·`data-keyboard="false"` 영구삭제 모달을 탭 패널 안으로 옮기면, 모달을 연 채 해시가 바뀌거나 뒤로 가기를 눌렀을 때 모달은 숨겨져도 body에 붙은 배경막·포커스 강제가 남아 화면이 갇힘 — 저장소 Bootstrap은 이미 부모가 있는 모달을 body로 옮기지 않는다): 쟁점 6 변경 — 메뉴 프래그먼트를 `body`·`modals`·`script` **세 조각**으로 나누고 `modals`는 ADMIN 조건으로 **탭 패널 밖**(셸 하단, 로그아웃 모달 옆)에 둔다. 실기에 "모달 열기 → 해시 전환·뒤로 가기" 추가.
  - R4-2(P2 반영 성공 후 새 HTML 로딩이 중단되면(로딩 중지·`window.stop()`) v4의 "문서 교체까지 잠금 유지"는 복구 경로가 없어 두 패널이 잠긴 채 남음 — `handleApply()` 모의 실행으로 확인): 쟁점 9 변경 — 시간 초과로 잠금을 풀지 **않고**(R3-1의 입력 소실 재발 방지) **수동 복구**를 둔다. 메뉴 스크립트가 성공 시 `content:menu-applied`를 발행하면 셸이 "반영되었습니다. 화면을 새로 고치는 중입니다. 멈췄다면 [지금 새로고침]" 안내와 버튼을 표시한다(잠금 유지, 버튼은 `location.reload()`를 다시 시도). 메뉴 스크립트 수정은 가드·`applying` 시작/실패·`applied` 이벤트 발행 지점으로 한정된다. 실기에 탐색 중단 시나리오 추가.
- v4 변경(3라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×1, 결정 필요 0건):
  - R3-1(P2 `content:menu-applying`을 "끝/실패=false"로 정의하면 성공 직후에도 잠금이 풀려, `reload()`가 새 문서를 받기 전에 정적 페이지 입력·저장이 다시 가능해짐 — `reload()`는 문서 로드 완료를 기다리지 않는다): 쟁점 9 변경 — `false`는 **요청이 실패해 현재 화면에서 작업을 계속하는 경우에만** 발행하고, **성공 경로는 `reload()`로 문서가 교체될 때까지 잠금을 유지**한다(`false`를 발행하지 않는다). 실기 7번에 "메뉴 PUT 성공 후 다음 HTML 응답을 지연시키고 그동안 정적 페이지 입력·저장이 계속 차단됨"을 추가한다.
- v3 변경(2라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×1, 결정 필요 0건):
  - R2-1(P2 정적 페이지 화면의 `isDirty()` 신규 작성 분기가 슬러그·제목·본문만 보고 노출·푸터 플래그는 안 봐서, 플래그만 바꾼 채 메뉴를 반영하면 가드를 통과하고 reload가 플래그를 기본값으로 되돌림 — `isDirty()`를 추출한 Node 모의 실행으로 재현): 쟁점 9 변경 — 신규 작성 분기에 `fUse.checked !== true || fFooter.checked !== false`를 추가한다(`beforeunload`와 화면 내 전환 확인도 같은 함수라 함께 고쳐진다). 실기 7번에 "플래그만 바꾼 신규 작성 상태에서 메뉴 반영이 차단됨" 사례를 추가한다.
- v2 변경(1라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×4·P3×1, 결정 필요 0건):
  - R1-1(P2 다른 패널 미저장 때문에 메뉴 반영 후 새로고침을 취소하면 메뉴 화면이 `busy=true`로 잠김 — 실제 `handleApply()` 추출 모의 실행으로 재현): 쟁점 9 변경 — **메뉴 반영 요청 전에** 정적 페이지 패널이 미저장·저장/삭제 중이면 반영을 막고 안내한다. 반영 요청이 진행 중인 동안은 정적 페이지 패널 입력을 잠가 새로고침 확인창이 뜰 수 있는 경로 자체를 없앤다. 메뉴 스크립트 수정은 이 가드·이벤트 2곳으로 한정한다(설계 제약 "동작 불변"을 "최소 수정 2곳"으로 정정).
  - R1-2(P2 정적 페이지 저장 중 메뉴 반영의 새로고침이 저장 응답을 가로챔 — 저장 중에는 `beforeunload` 경고를 생략하기 때문): 같은 가드로 해결(저장·삭제 중이면 반영 차단). 실기 계획에 경쟁 시나리오 추가.
  - R1-3(P2 메뉴 `beforeunload`는 구조 초안만 검사하고 폼 입력은 안 봄 — "각 패널 보호 그대로 유지" 문구가 부정확): 문구 정정 — 보호 대상은 **구조 초안만**. 기각: 메뉴 폼 변경 감지 추가 — 기존 메뉴 화면의 한계이고 통합과 무관하며 요청된 적 없다(범위 밖에 한계로 명시).
  - R1-4(P2 프래그먼트 안의 스크립트가 하단 의존성보다 먼저 실행됨): 쟁점 6 변경 — 각 탭 프래그먼트를 `body`(마크업)와 `script`(초기화) 둘로 나눠 셸이 마크업은 패널 위치에, 스크립트는 의존성 로딩 **뒤**에 포함한다. 의존성 로딩 순서를 시험(실기)으로 확인.
  - R1-5(P3 `AdminSidebarAdviceTest`의 `@WebMvcTest(controllers=…)`에 컨트롤러를 추가해야 하고, `<head th:replace>`가 `<title>`을 교체해 셸 제목이 반영되지 않음): 시험 계획에 `SitePagePageController` 추가 명시. 제목 요구는 제거 — 브라우저 제목은 기존에도 모든 관리 화면이 공통 fragment의 "관리자 대시보드"이고 화면 이름은 머리글(h1)로만 표시한다(쟁점 1 정정).
- v1: 최초 작성(정찰 → 쟁점 12개 결정). 사용자 요청: "정적 페이지 관리를 메뉴 관리로 통합 — 한 화면에서 둘 다 관리, MANAGER 위임은 유지" → 검토한 방법 중 **3′**(합친 화면을 `SITE_PAGE` 게이트가 걸린 기존 URL에 올림)로 계획.

## Context

관리자 사이드바에는 "메뉴 관리"(`/admin/menu/manage`, ADMIN 전용)와 "정적 페이지 관리"(`/admin/site-page/manage`, 기능 단위 위임 `SITE_PAGE`)가 따로 있다. 사용자는 **한 화면에서 둘 다 관리**하길 원하고, **MANAGER에게 정적 페이지 편집을 위임하는 기능은 유지**해야 한다.

권한 구조가 충돌한다 — 메뉴 관리는 위임 불가(`MENU`, `ADMIN_ONLY`: 사이드바 구조를 바꾸는 권한이라 위임하지 않는다)이고 정적 페이지는 위임 가능이다. 검토한 방법과 결론:

| 방법 | 결론 |
|---|---|
| 1. ADMIN 전용 탭 통합 | 위임 포기 — 요구 위반 |
| 2. `MENU`를 위임 가능으로 승격 | 비권장 — MANAGER가 ADMIN 사이드바를 변조 가능, 인가 정책 변경 |
| 3. 한 URL을 두 기능이 공유 | `featureReadGate`·`forMenuUrl` 구조 변경이 커서 비권장 |
| **3′. 합친 화면을 `SITE_PAGE` 게이트 URL에 올림** | **채택** — 게이트·권한 모델 불변 |
| 4. 탭 링크만 | 한 화면 편집이 아님 |

**3′의 핵심**: `/admin/site-page/manage`를 "정적 페이지 · 메뉴 구조" 탭 화면으로 확장한다. 게이트는 지금 그대로(ADMIN 항상, MANAGER는 `SITE_PAGE` READ). **메뉴 탭은 ADMIN에게만 서버가 렌더링**하고 메뉴 API(`/admin/api/menus/**`)는 지금처럼 ADMIN 전용이라 MANAGER가 직접 호출해도 403이다. `/admin/menu/manage`는 새 화면의 메뉴 탭으로 302 리다이렉트한다(공지 흡수 때의 `NoticeRedirectController` 선례).

### 정찰 요약 (읽은 근거)

- **메뉴 화면**(`templates/admin/menu/manage.html`, 1357줄): 스크립트 대부분이 `$(document).ready(function () { … })` **클로저 안**이라 전역 이름 충돌이 거의 없다. 외부 의존: jstree 3.3.12(**cdnjs CDN** CSS·JS), jQuery, Bootstrap. 반영 성공 시 `sessionStorage('menuStructureApplied')`에 알림을 남기고 **`window.location.reload()`**로 사이드바까지 갱신한다. `beforeunload`로 드래프트 이탈을 막는다. DOM `id` 46개, 페이지 전용 CSS에 **전역 `.card-header` 규칙**(`.tree-container .card-header` 등 외에 무한정 선택자)이 있다.
- **정적 페이지 화면**(`templates/admin/page/manage.html`, 513줄): 스크립트가 최상위 `const`/`function`(`csrfHeaders`·`showError`·`startCreate` …)이며 Quill 의존. DOM `id` 22개.
- **두 화면의 DOM `id` 충돌은 `btnSave` 하나뿐**(공통 프레임 id `content`·`wrapper` 등은 같은 레이아웃).
- `MenuPageController`(`@AdminPage`, `GET /admin/menu/manage` → 뷰 반환)와 `SitePagePageController`(`GET /admin/site-page/manage`). 둘 다 `@AdminPage`라 사이드바·`myPermissions`를 주입받는다. `thymeleaf-extras-springsecurity6`가 있어 `sec:authorize="hasRole('ADMIN')"`를 쓸 수 있다(`topbar.html` 선례).
- **게이트**(`SecurityConfig`): `MENU`는 `gatePatterns`가 비어 `/admin/**` ADMIN 캐치올, `SITE_PAGE`는 DELEGABLE 루프의 `featureReadGate`. 새 구조는 어느 쪽도 바꾸지 않는다.
- **사이드바 활성 표시**는 `menu.menuUrl == currentUri`(요청 URI) 비교다 — 리다이렉트 후에는 현재 URI가 `/admin/site-page/manage`라 "메뉴 관리" 항목이 활성 표시되지 않는다.
- 영향 시험: `AdminSidebarAdviceTest.adminPages_receiveSidebarModel`이 `GET /admin/menu/manage`를 **200으로** 기대한다(리다이렉트가 되면 바뀐다). `AdminPermissionMatrixIntegrationTest`는 MANAGER가 `/admin/menu/manage`를 **거부**당함을 확인한다(리다이렉트 핸들러도 같은 URL 게이트라 그대로 통과해야 한다). 나머지 메뉴 관련 시험은 URL 문자열을 데이터로 쓸 뿐 화면을 렌더링하지 않는다.

## 설계 제약 (명문화)

- **인가 정책 변경이 없어야 한다.** `SecurityConfig`·`AdminFeature`·`member_permission`·URL 게이트 규칙은 한 줄도 바꾸지 않는다(바꿔야 하면 이 계획의 전제가 깨진 것이므로 멈추고 재협의).
- 사용자 입력은 `textContent`로만 삽입한다(두 화면 기존 규약 유지). 메서드 보안 403 메시지는 영문이라 고정 한국어 문구를 쓴다.
- 요청받지 않은 기능을 만들지 않는다: 메뉴 화면은 자리만 옮기고 동작은 바꾸지 않되, 통합이 만드는 상호작용을 막는 **최소 수정 2곳**(반영 전 가드, 반영 중 알림 이벤트 — 쟁점 9)만 허용한다. 두 도메인의 서비스·API·권한은 건드리지 않는다.
- 머지된 마이그레이션은 수정하지 않는다(이 계획은 스키마 변경이 없다).

## 쟁점과 결정

### 쟁점 1 — 화면 URL과 게이트
- 선택지: (A) 기존 `/admin/site-page/manage`를 확장. (B) 새 URL(`/admin/content/manage`) 신설.
- **결정: A.** B는 `AdminFeature.SITE_PAGE`의 `gatePatterns`·`menuUrls`와 메뉴 행 URL(V39로 갱신)을 바꿔야 해 **게이트 구성 변경**이라 전제를 깬다. A는 게이트가 그대로다. 화면 이름은 머리글(h1)에서 "정적 페이지 · 메뉴"로 표시한다. 브라우저 탭 제목은 모든 관리 화면이 공통 fragment(`head :: adminHead`)의 값을 쓰므로 바꾸지 않는다(사이드바 항목 이름은 메뉴 데이터라 코드 변경 없음).

### 쟁점 2 — 메뉴 탭은 ADMIN에게만, 서버가 렌더링
- `sec:authorize="hasRole('ADMIN')"`로 메뉴 탭 버튼·패널·jstree CSS/JS를 **ADMIN 응답에만** 넣는다. MANAGER의 응답에는 메뉴 마크업·스크립트·CDN 참조가 **아예 없다**(정보 노출·불필요한 CDN 로딩 방지).
- 서버 판정이 최종이다 — 화면 숨김은 UX일 뿐 메뉴 API는 `hasRole('ADMIN')`으로 막혀 있다(시험으로 고정).
- MANAGER에게는 탭 내비게이션 자체를 그리지 않는다(탭이 하나라 의미 없음 — 기존 정적 페이지 화면과 같은 모습).

### 쟁점 3 — 탭 상태와 URL 해시
- 탭은 URL 해시(`#pages`·`#menu`)로 표현한다. 기본은 `#pages`. MANAGER가 `#menu`로 들어오면(메뉴 패널이 없으므로) 정적 페이지 탭으로 폴백한다.
- 메뉴 화면의 반영 후 `window.location.reload()`는 **해시를 보존**하므로 reload 후에도 메뉴 탭이 유지된다(실기로 확인). `sessionStorage` 알림 흐름은 그대로 둔다.
- 두 패널은 **둘 다 DOM에 마운트한 채** `display` 토글만 한다 — 탭을 전환해도 한쪽의 작성 중 내용·드래그 초안이 사라지지 않는다.

### 쟁점 4 — `/admin/menu/manage` 처리
- `MenuPageController.menuManagePage()`가 뷰 대신 **`redirect:/admin/site-page/manage#menu`**(302)를 반환한다. 권한은 그대로: 같은 URL의 게이트가 ADMIN 캐치올이라 MANAGER는 리다이렉트 이전에 403이다(`AdminPermissionMatrixIntegrationTest`가 계속 통과해야 한다).
- 컨벤션 시험(`AdminEndpointAuthorizationConventionTest`)은 "읽기 전용 GET 페이지 핸들러"를 면제하므로 통과한다.
- 사이드바의 "메뉴 관리"·상단 검색 결과·북마크는 그대로 동작한다(리다이렉트).

### 쟁점 5 — 사이드바 항목은 둘 다 유지 (※ 2026-10-14 후속 PR이 한 항목으로 합쳤다 — 파일 말미 "후속 PR — 사이드바 두 항목 통합")
- **결정: 두 항목 유지**(변경 없음, 스키마 변경 없음). "메뉴 관리"를 누르면 같은 화면의 메뉴 탭으로, "정적 페이지 관리"는 정적 페이지 탭으로 간다. 합쳐서 한 항목으로 줄이려면 V39 메뉴 행 정리(머지된 시드 수정 불가)와 ADMIN/MANAGER별 라벨 문제가 생겨 범위 밖으로 둔다.
- **알려진 한계**: 활성 표시가 `menuUrl == currentUri` 비교라 "메뉴 관리"로 들어와도 현재 URI가 `/admin/site-page/manage`라서 **"정적 페이지 관리" 항목만 활성** 표시된다. 수용한다(고치려면 사이드바 비교 규칙·해시 처리를 바꿔야 한다 — 요청된 적 없음).

### 쟁점 6 — 템플릿 구성: 프래그먼트로 분리
- 합치는 대상이 1870줄이라 한 파일로 붙이면 리뷰·유지가 어렵다. 구성:
  - `admin/page/manage.html` — 셸(레이아웃·탭 내비·두 패널 포함·공통 스크립트 로딩)
  - `admin/page/fragments/page-tab.html` — 현재 정적 페이지 패널(마크업 + 스크립트)
  - `admin/menu/fragments/menu-tab.html` — `git mv`한 메뉴 패널(마크업 + 스크립트 + 전용 CSS). 기존 `admin/menu/manage.html`은 삭제(이력 보존을 위해 이동으로 처리).
- **각 탭 프래그먼트는 `body`(마크업·전용 CSS)와 `script`(초기화 스크립트) 조각으로 나누고, 메뉴는 추가로 `modals` 조각을 둔다**(R1-4·R4-1). **메뉴의 모달(영구삭제 확인 등)은 `modals` 조각으로 분리해 ADMIN 조건으로 탭 패널 밖(셸 하단, 로그아웃 모달 옆)에 렌더링한다** — Bootstrap은 이미 부모가 있는 모달을 body로 옮기지 않고 배경막만 body에 붙이므로, 모달이 숨겨지는 패널 안에 있으면 해시 전환·뒤로 가기 때 배경막·포커스 강제만 남아 화면이 갇힌다(`data-backdrop="static"`·`data-keyboard="false"`라 Escape로도 못 닫는다). 모달은 패널 밖이라 탭이 바뀌어도 열린 채 정상 닫을 수 있다. 기존 화면은 의존성(jQuery·Bootstrap·jstree·Quill·`notice-editor.js`)을 하단에서 로드한 **뒤** 초기화 스크립트를 실행한다. 마크업·스크립트를 한 프래그먼트로 패널 위치에 넣으면 `$`·`NoticeEditor.create()`가 의존성보다 먼저 실행되고 IIFE·`$(document).ready`는 이를 지연시키지 못한다. 셸의 배치 순서: 페이지 전용 CSS(body 시작) → 탭 내비 → 패널 `body` 조각들 → 메뉴 `modals` 조각(ADMIN) → **공통 의존성**(jQuery·Bootstrap·easing·sb-admin-2, 메뉴 패널이 있으면 jstree, Quill·`notice-editor.js`) → 패널 `script` 조각들.
- 메뉴 프래그먼트(`body`·`script`·jstree CSS/JS)는 ADMIN 조건으로만 포함한다. 외부 스크립트는 셸이 한 번만 로드한다.

### 쟁점 7 — 충돌 정리 규칙
- **DOM id**: `btnSave` 충돌 → 정적 페이지 쪽을 `pageBtnSave`로 바꾼다(정적 페이지 id는 메뉴 id와 겹치지 않는지 구조 시험으로 고정 — 쟁점 11).
- **CSS**: 메뉴 프래그먼트의 `.card-header` 등 전역 선택자를 **조각별 루트 id로 스코프**한다 — 패널 규칙은 `#menuTabPanel` 하위, **모달 규칙은 각 모달의 id**(`#iconPickerModal`의 `.icon-grid`·`.icon-item`, `#permanentDeleteModal`)로 제한한다(R5-1: 모달이 패널 밖으로 이동하므로 `#menuTabPanel` 하위 선택자는 모달에 매칭되지 않아 아이콘 그리드 배치·스크롤·선택 강조가 사라진다). 정적 페이지 카드 스타일 오염 방지가 목적이다. 정적 페이지 CSS(`.page-card`·`.page-row`)는 이미 접두 클래스다.
- **JS**: 정적 페이지 스크립트를 IIFE로 감싼다(메뉴 스크립트는 `$(document).ready` 클로저라 변경 없음). 두 스크립트가 공유하는 건 `<meta name="_csrf">` 읽기뿐이다.
- **로딩 순서**: jstree는 jQuery 뒤, Quill은 독립. 메뉴 스크립트는 `$(document).ready`라 셸의 스크립트 로딩 순서만 맞으면 된다.

### 쟁점 8 — jstree를 숨겨진 패널에서 초기화
- 기본 탭이 `#pages`면 메뉴 패널은 숨겨진 상태로 jstree가 초기화된다. jstree는 데이터 로딩·렌더링은 보이지 않는 컨테이너에서도 되지만 **드래그 계산은 표시 후에만** 의미가 있다. 우선 지금처럼 **즉시 초기화**하고(메뉴 스크립트 무변경) 실기에서 드래그·반영·reload가 정상인지 확인한다. 문제가 있으면 **탭 첫 표시 시 초기화**(`shown` 이벤트)로 바꾼다 — 이 경우만 메뉴 스크립트를 최소 수정한다(계획 변경으로 즉시 보고).

### 쟁점 9 — 이탈·미저장 보호와 새로고침 조정 (R1-1·R1-2·R1-3)
- **현황(기존 동작)**: 메뉴 화면의 `beforeunload`는 **구조 드래그 초안**(`draftChanged > 0`)만 보호하고 메뉴 이름·URL·설명 같은 **폼 입력은 보호하지 않는다**(기존 한계, 이 계획의 범위 밖). 정적 페이지 화면은 미저장 입력(제목·본문·플래그)을 보호하되 **저장 중에는 경고를 생략**한다. 메뉴 반영 성공 시 `window.location.reload()`로 사이드바까지 갱신한다.
- **통합이 만드는 새 문제**: 한 문서 안에서 한쪽의 `reload()`가 다른 쪽의 미저장 입력·진행 중 저장을 건드린다 — (a) 정적 페이지에 미저장 입력이 있는 채 메뉴를 반영하면 `beforeunload` 확인창이 뜨고 "현재 화면 유지"를 고르면 메뉴 화면이 `busy`·`leavingAfterApply`로 **잠긴 채** 남는다(서버에는 이미 반영됨), (b) 정적 페이지 저장 중에 메뉴를 반영하면 저장 응답을 받기 전에 문서가 교체된다.
- **결정 — 반영 전 가드 + 반영 중 잠금**(메뉴 스크립트 수정은 이 2곳이 전부):
  1. 셸이 작은 조정 객체 `window.ContentScreen`을 둔다. 정적 페이지 스크립트가 `isPagePanelBusyOrDirty()`(저장·삭제·상세 로딩 중이거나 `isDirty()`)를 등록한다.
  2. **메뉴 `handleApply`의 첫 줄 가드**: `ContentScreen.isPagePanelBusyOrDirty()`면 **서버 요청 없이** "정적 페이지 탭에 저장하지 않은 변경이 있습니다. 먼저 저장하거나 취소한 뒤 반영하세요."를 메뉴 알림에 표시하고 반환한다. 메뉴 구조 초안은 그대로 남는다.
  3. **반영 요청이 시작되면** 메뉴 스크립트가 `content:menu-applying` 이벤트(`true`)를 발행하고, 정적 페이지 패널은 이 동안 입력·저장·삭제를 잠근다(`lockUi`에 외부 잠금 반영, 저장·삭제 핸들러도 외부 잠금이면 시작하지 않는다). **`false`는 요청이 실패(4xx·409·네트워크 오류)해 사용자가 현재 화면에서 계속 작업하는 경우에만 발행한다. 성공 경로는 `false`를 발행하지 않고 `reload()`로 문서가 교체될 때까지 잠금을 유지한다**(R3-1 — `reload()`는 새 문서 로드 완료를 기다리지 않으므로 성공 직후 잠금을 풀면 그 사이 입력·저장이 가능해져 문서 교체 때 소실된다). 그래서 반영이 시작된 뒤에는 정적 페이지 입력이 새로 생겨 새로고침 확인창이 뜨거나 저장 응답을 잃는 경로가 없다.
- 반대 방향(정적 페이지 저장 중 메뉴 탭에서 다른 작업)은 정적 페이지 쪽 잠금이 아니라 **메뉴 반영 가드**로만 막으면 충분하다 — 정적 페이지 저장·삭제는 `reload()`를 하지 않는다. 메뉴 드래그 초안이 있는 채 정적 페이지 탭에서 작업하는 것은 두 상태가 독립이라 문제없다(메뉴 초안은 `beforeunload`가 계속 보호).
- **`isDirty()` 보강(R2-1)**: 가드가 `isDirty()`를 전제로 하므로 정적 페이지 `isDirty()`의 **신규 작성 분기**에 `fUse.checked !== true || fFooter.checked !== false`를 추가한다(기본값이 노출 켜짐·푸터 꺼짐이다). 지금은 신규 작성에서 두 플래그만 바꾸면 `dirty=false`라, 가드 통과 → 메뉴 reload → `startCreate()`가 플래그를 기본값으로 되돌려 입력이 경고 없이 사라진다. 기존 페이지 수정 분기는 이미 플래그를 검사한다. 이 보강은 `beforeunload`·화면 내 전환 확인(#129)의 누락도 함께 바로잡는다(통합과 무관하게 필요했던 수정이라 변경 이력에 따로 기록).
- 탭 전환은 두 패널이 마운트된 채 `display` 토글이라 상태가 보존된다.
- **성공 후 문서 교체가 멈추는 경우의 복구(R4-2)**: 성공 경로는 잠금을 유지하므로(R3-1) 탐색이 중단되면(로딩 중지·네트워크 지연) 두 패널이 잠긴 채 남을 수 있다. **시간 초과로 잠금을 풀지 않는다**(입력 소실 재발). 대신 메뉴 스크립트가 성공 시 `content:menu-applied`를 발행하고 **셸이** 화면 상단에 "반영되었습니다. 화면을 새로 고치는 중입니다. 멈췄다면 [지금 새로고침]" 안내와 버튼을 표시한다. 버튼은 `location.reload()`를 다시 시도한다(잠금은 문서가 실제로 교체될 때까지 유지). 메뉴 스크립트 수정 지점은 가드·`applying`(시작/실패)·`applied` 이벤트 발행으로 한정되고 안내 UI는 셸이 소유한다.
- 알려진 한계(수용): 메뉴 폼 입력은 여전히 미보호(기존 한계). 메뉴 반영이 가드로 막히는 경우 사용자는 정적 페이지 탭에서 저장·취소한 뒤 다시 반영해야 한다.

### 쟁점 10 — 알림 영역
- 메뉴 패널의 `treeAlert`·`formAlert`·`treeSuccess`와 정적 페이지 패널의 `pageAlert`·`pageSuccess`는 **각 패널 안에** 둔다(다른 탭의 오류가 보이는 혼선 방지).

### 쟁점 11 — 구조 시험
- 합쳐진 화면은 id 충돌이 곧 버그이므로 **렌더링된 HTML의 중복 `id` 속성이 없음을 시험으로 고정**한다(ADMIN 응답 기준, 가장 큰 구성).

### 쟁점 12 — 문서
- `admin/menu/CLAUDE.md`(메뉴 화면 위치·리다이렉트·한계), `admin/page/CLAUDE.md`(화면 구성·탭 규칙), 루트 `CLAUDE.md` 지도 갱신. `docs/`에는 배포 영향이 없다(스키마·설정 변경 없음).

## 구현 계획 (안쪽 → 바깥쪽)

1. **컨트롤러**: `MenuPageController` → 리다이렉트. `SitePagePageController`는 뷰 이름만 유지(템플릿이 셸로 바뀜).
2. **템플릿 이동·분리**: `git mv`로 메뉴 화면을 프래그먼트로, 정적 페이지 화면을 프래그먼트로 분리하고 셸 작성. id·CSS·IIFE 정리(쟁점 7).
3. **ADMIN 조건 렌더링**(`sec:authorize`)과 탭 해시 처리 스크립트(셸에 소량).
4. **시험**: 아래 시험 계획.
5. **실기 검증**(Playwright): 아래 목록.
6. **문서·계획서 갱신**, `plan/README.md` 인덱스.

## 시험 계획

- 신규 `ContentManagePageIntegrationTest`(Testcontainers·실제 스택):
  - ADMIN: `GET /admin/site-page/manage` 200, 메뉴 탭·패널·jstree 스크립트 포함, **렌더링된 HTML에 중복 `id` 없음**.
  - MANAGER + `SITE_PAGE` READ: 200, 정적 페이지 패널만 있고 **메뉴 패널 마크업·jstree 참조·`/admin/api/menus` 문자열이 없음**.
  - MANAGER + `SITE_PAGE` READ여도 `GET /admin/api/menus/tree` 403, 메뉴 변경 API 403.
  - MANAGER 권한 없음 403, 익명 302(로그인).
  - `GET /admin/menu/manage`: ADMIN 302 + `Location`이 `/admin/site-page/manage#menu`, MANAGER 403(리다이렉트 이전 게이트), 익명 302(로그인).
- 기존 시험 갱신: `AdminSidebarAdviceTest.adminPages_receiveSidebarModel` — `/admin/menu/manage`(이제 302)를 목록에서 빼고 `/admin/site-page/manage`를 넣는다. 이 시험은 `@WebMvcTest(controllers = …)`에 컨트롤러를 명시하므로 **`SitePagePageController`를 목록에 추가**해야 한다(R1-5 — URL만 바꾸면 슬라이스에 대상 컨트롤러가 없다). `AdminPermissionMatrixIntegrationTest`·`AdminEndpointAuthorizationConventionTest`는 **수정 없이 통과**해야 한다(통과하지 않으면 전제 위반 — 멈춘다).
- 변이 실험: (1) 메뉴 탭의 `sec:authorize`를 제거 → MANAGER 응답에 메뉴 패널이 새는 시험 실패 확인. (2) 정적 페이지의 `pageBtnSave`를 `btnSave`로 되돌림 → 중복 id 시험 실패 확인.
- 전체 `./gradlew cleanTest test`(기본·UTC).

## 실기 검증 계획 (Playwright, dev)

1. ADMIN: 두 탭 전환, 정적 페이지 등록·수정이 그대로 동작, 메뉴 탭에서 트리 로딩·노드 선택·수정·**드래그 → [반영] → 새로고침 후 메뉴 탭 유지와 성공 알림**.
2. ADMIN: 한 탭에 미저장 입력이 있는 채 다른 탭으로 갔다 와도 입력이 남음, 페이지 이탈 시 경고.
3. 사이드바 "메뉴 관리" 클릭 → 메뉴 탭, "정적 페이지 관리" → 정적 페이지 탭, 검색 결과로 메뉴 이동.
4. MANAGER(`SITE_PAGE` 부여): 정적 페이지 화면만(탭 내비·메뉴 없음), 네트워크 요청에 메뉴 API·jstree CDN이 없음. `/admin/menu/manage` 직접 접근 403.
5. 정적 페이지 카드·메뉴 카드의 스타일이 서로 오염되지 않음(스크린샷 비교).
6. **의존성 로딩 순서**(R1-4): ADMIN·MANAGER 모두 첫 로드에서 콘솔 오류 0건(`$ is not defined`·`NoticeEditor is not defined` 없음).
7. **새로고침 조정**(R1-1·R1-2): (a) 정적 페이지에 미저장 입력이 있는 채 메뉴 드래그 → [반영] → **요청이 나가지 않고** 안내가 표시되며 메뉴 초안·정적 페이지 입력이 모두 남음. (b) 정적 페이지 저장 요청을 느리게 만든(fetch 지연) 상태에서 메뉴 [반영] → 차단됨. (c) 메뉴 반영 요청이 진행 중인 동안 정적 페이지 입력이 잠기고 성공 후 새로고침되어도 메뉴 탭·성공 알림 유지. (d) 정적 페이지가 깨끗할 때의 정상 반영·새로고침. (d′) **성공 후 문서 교체 지연**(R3-1): 메뉴 PUT은 통과시키고 다음 HTML 요청을 지연시킨 동안 정적 페이지 입력·저장·삭제가 계속 차단됨. (d″) 반영 요청이 실패(409 등)하면 잠금이 풀리고 정적 페이지 입력이 다시 가능함. (d‴) **탐색 중단**(R4-2): 반영 성공 후 새 HTML 응답을 지연시키고 로딩을 중단하면 안내와 [지금 새로고침]이 보이고 버튼으로 새로고침이 완료되어 잠금이 풀림(시간 초과로는 풀리지 않음).
10. **아이콘 선택기**(R5-1): 모달을 열면 아이콘 그리드가 배치·최대 높이 스크롤로 보이고 검색이 동작하며 현재 선택 아이콘이 강조됨(기존 화면과 동일).
9. **메뉴 모달과 해시 전환**(R4-1): 영구삭제 모달을 연 채 탭 해시를 바꾸거나 뒤로 가기를 눌러도 모달이 패널 밖에 있어 정상적으로 닫을 수 있고 배경막이 남지 않음.
   (e) **플래그만 바꾼 신규 작성**: 새 페이지에서 슬러그·제목·본문은 비운 채 '노출'을 끄거나 '푸터 링크'를 켠 상태로 메뉴 [반영] → 차단된다(R2-1).
8. 메뉴 폼 입력은 미보호라는 기존 한계가 그대로임을 확인(계획의 알려진 한계와 일치).

## 리스크

1. **UI 이식 회귀**(가장 큼): 1357줄 메뉴 화면을 옮기며 드래그·반영·reload 흐름이 깨질 수 있다 → 동작을 바꾸지 않는 이동 위주, 실기 1·2번으로 확인.
2. **숨겨진 패널의 jstree**(쟁점 8) → 실기에서 확인, 필요 시 지연 초기화.
3. **CSS·id 오염**(쟁점 7) → 스코프·접두·구조 시험.
4. **사이드바 활성 표시 한계**(쟁점 5) — 수용.
5. **서버 숨김에만 의존하지 않기**: 메뉴 API 403 시험으로 서버 판정을 고정한다.
6. **두 패널 사이 새로고침 경쟁**(R1-1·R1-2): 가드·반영 중 잠금으로 막되 메뉴 스크립트를 2곳 수정하므로 메뉴 반영 시험(실기 7번)으로 회귀를 확인한다.
7. **롤백**: 스키마·설정 변경이 없어 이전 앱으로 되돌려도 DB 호환 문제가 없다(템플릿·컨트롤러만 되돌아감).

## 범위 밖 (명시)

메뉴 폼 입력(이름·URL·설명)의 미저장 보호 추가(기존 한계 — R1-3 기각), 브라우저 탭 제목 변경(모든 관리 화면이 공통 fragment 값을 쓴다 — R1-5), 사이드바 두 항목을 하나로 합치는 것(V39 메뉴 행 정리), 사이드바 활성 표시 규칙 변경, `MENU` 권한 위임, 공개 사이트 메뉴(헤더) 신설, 메뉴 화면 자체의 기능 변경, iframe 임베딩(`X-Frame-Options: DENY` 정책 변경이 필요해 배제).

## 구현·검증 결과 (2026-10-11)

### Context
정적 페이지 관리와 메뉴 구조 편집을 한 화면(`/admin/site-page/manage`)의 탭으로 합쳤다(방법 3′). 게이트·`SecurityConfig`·`AdminFeature`·권한 모델·스키마는 바뀌지 않았다. 승인 시 고지한 대로 메뉴 스크립트는 **반영 전 가드·`content:menu-*` 이벤트 발행 지점만** 수정했다.

### 핵심 확정 사항 (v6 그대로 구현)
`SITE_PAGE` 게이트 URL에 탭 셸 · 메뉴 탭·모달·jstree는 `sec:authorize="hasRole('ADMIN')"`로 ADMIN 응답에만 · `/admin/menu/manage`는 `redirect:/admin/site-page/manage#menu` · 조각 `pagePanel`/`pageScript`와 `menuPanel`/`menuModals`/`menuScript`(모달은 패널 밖, 스크립트는 의존성 뒤) · CSS는 패널·모달 루트 id로 스코프 · 두 패널 새로고침 조정(반영 전 가드, 진행 중 잠금, 성공은 문서 교체까지 유지 + 셸의 "지금 새로고침") · `isDirty()` 신규 작성 플래그 보강.

### 구현 파일
- 셸·조각: `templates/admin/page/manage.html`(셸), `admin/page/fragments/page-tab.html`, `admin/menu/fragments/menu-tab.html`(`git mv`한 옛 `admin/menu/manage.html`)
- 컨트롤러: `MenuPageController`(302)
- 시험: 신규 `ContentManagePageIntegrationTest`(7건), 갱신 `AdminSidebarAdviceTest`(컨트롤러 목록·경로)
- 문서: `docs/troubleshooting.md` 2건, 패키지 `CLAUDE.md`(menu·page·config)·루트 지도

### 계획과 달라진 점 (구현 중 즉시 보고한 것)
1. **조각 이름 충돌**: 처음 `th:fragment="body"`·`"script"`로 지었더니 Thymeleaf가 HTML 요소 `<body>`·`<script>`로 해석해 조각 파일 전체가 포함됐다(모달 중복·스크립트가 의존성보다 먼저 실행·MANAGER 응답에 ADMIN 마크업 누출). `menuPanel` 등으로 개명. 시험이 잡았다(`docs/troubleshooting.md`).
2. **일반 HTML 주석이 응답에 노출**: 셸의 설명 주석(`jstree` 언급)이 MANAGER 응답에 나갔다 → 파서 수준 주석(`<!--/* */-->`)으로 변경.
3. **메뉴 영역 높이**: 계획의 `calc(100vh - 200px)` 유지 대신 `calc(100vh - 290px)`로 줄였다. 탭 내비게이션 때문에 콘텐츠+푸터가 뷰포트를 16px 넘겨 문서 스크롤바와 `#content-wrapper` 스크롤바가 둘이 됐기 때문이다(실기로 측정해 보정, 보정 후 기준선과 동일한 스크롤 구조).
4. **머리글**: MANAGER에게 "메뉴 관리"가 보이면 오해를 주므로 ADMIN만 "정적 페이지 · 메뉴 관리", MANAGER는 "정적 페이지 관리"(시험으로 고정). 계획에 없던 작은 추가.

### 검증 결과
- `ContentManagePageIntegrationTest` 7건 통과 + 변이 실험 2건을 시험이 잡음(메뉴 패널의 `sec:authorize` 제거 → MANAGER 응답 시험 실패, `pageBtnSave`를 `btnSave`로 → 중복 id 시험 실패). 전체 `./gradlew cleanTest test` 결과는 아래 "최종 전체 실행".
- dev 실기(Playwright, 앱을 8081에서 구동): ① 첫 로드 콘솔 오류 0건, 의존성 로딩·숨겨진 패널의 jstree 초기화(노드 13개) 정상 ② 이식 전 화면(8080 컨테이너)과 시각 비교 — 스타일 동일, 스크롤바 문제는 위 3번으로 보정 ③ 정적 페이지 탭에 미저장 입력(슬러그, 또는 **노출만 끈 신규 작성**)이 있는 채 메뉴 [반영] → 서버 요청 0건·안내 표시·입력·초안 유지 ④ 깨끗한 상태의 [반영] → 새로고침 후 `#menu` 탭 유지·성공 알림 "메뉴 구조를 반영했습니다(1건 이동)"·메뉴 순서 DB 반영 ⑤ 이벤트 계약 직접 확인: applying(true) → 정적 페이지 패널 입력·저장·편집기 잠금, applied → 안내 표시, 3초 후에도 잠금 유지, applying(false) → 해제 ⑥ 아이콘 선택기(4열 그리드·최대 높이 스크롤·선택 강조·검색) 정상 ⑦ 모달(아이콘·영구삭제)을 연 채 탭 전환 → 모달 유지, 닫으면 배경막 0·`modal-open` 해제 ⑧ 탭 전환 시 두 패널의 작성 중 입력 보존 ⑨ 통합 화면에서 정적 페이지 등록→수정(개정 2) 회귀 없음 ⑩ 사이드바 "메뉴 관리" → 메뉴 탭, "정적 페이지 관리" → 정적 페이지 탭, `/admin/menu/manage` 직접 접근 → `#menu` 302 ⑪ MANAGER(`SITE_PAGE` 조회): 탭·메뉴 패널·모달·jstree 없음, jstree·메뉴 API 요청 0건, 메뉴 API·옛 메뉴 URL 직접 호출 403, 사이드바에 정적 페이지만. 스크린샷 `adversarial-review/plan/screenshots/content-menu-unify-*.png`.
- 검증 뒤 dev 데이터 정리: 임시 페이지·MANAGER 계정·권한·알림·관련 감사 행 삭제. **메뉴 `ord` 값이 0~9로 재번호된 채 남는다**(상대 순서·내용은 원래와 같음 — 원래 값에는 삭제된 행 때문에 빈 번호가 있었다).

### 추가 확인 (최초 검증에서 못 한 두 가지 — 같은 날 재검증)
- **실제로 멈춘 새 문서 로딩**: CDP `Fetch`로 문서 요청을 붙잡아(붙잡힌 문서 요청 1건) 반영 성공 뒤 문서 교체가 끝나지 않는 상태를 **실제로** 만들었다. `Page.stopLoading`(로딩 중지) 직후에도, 그로부터 3.5초가 더 지나도 정적 페이지 패널은 **계속 잠겨 있고**(제목·저장·노출 비활성, 편집기 `contenteditable=false`) "지금 새로고침" 안내가 보였다(시간 초과로 풀리지 않음). 요청 차단을 풀고 버튼을 누르자 새 문서가 로드되어 `#menu` 탭 유지·잠금 해제·성공 알림 "메뉴 구조를 반영했습니다(1건 이동)."·반영된 순서를 확인했다. 도구 한계: 문서 교체가 붙잡힌 동안에는 `page.evaluate`류가 응답하지 않아(브라우저 제목이 "Loading …"인 채 정지 — 정체가 실제로 일어났다는 증거) CDP `Runtime.evaluate`와 단계별 제한 시간으로 측정했고, 붙잡힌 순간의 첫 측정값은 제한 시간 초과였다(`Page.stopLoading` 이후의 값은 정상 측정).
- **좁은 화면**: 375px(휴대폰)·768px(태블릿)에서 ADMIN의 두 탭 모두 가로 넘침 없음(`scrollWidth == clientWidth`, 범위를 벗어나는 요소 없음), 탭 내비게이션 정상 표시. 사이드바가 펼쳐진 폭에서는 탭 이름이 줄바꿈된다. 메뉴 탭의 "새 최상위"·"선택 아래 하위 추가" 버튼이 좁은 칸에서 글자가 세로로 쪼개지는 현상은 **이식 전 화면(8080 기준선)에도 똑같이 있는 기존 한계**다(스크린샷 `content-menu-unify-phone-*.png`). 이번 변경이 새로 만든 반응형 결함은 확인되지 않았다.
- **새로 발견한 작은 한계 → 수정함**: 같은 문서에서 주소의 해시만 바꾸면(`…/manage#pages` ↔ `#menu`를 주소창에서 직접 편집) 탭이 따라 바뀌지 않았다 — 해시를 문서를 열 때 한 번만 읽고 `hashchange`를 듣지 않았기 때문이다. 셸 스크립트에 `hashchange` 리스너를 추가해 해시에 맞는 탭을 보이게 했다(알 수 없는 해시는 정적 페이지 탭, 이미 보이는 탭이면 무동작, MANAGER는 탭이 없어 리스너를 달지 않는다). `replaceState`는 `hashchange`를 일으키지 않아 탭 클릭과 순환하지 않는다. 실기 확인: `#menu`↔`#pages` 직접 변경 시 탭이 따라감, 알 수 없는 해시는 정적 페이지, 탭 클릭은 이력을 늘리지 않음, 같은 해시 재설정은 무동작, 정적 페이지에 쓰던 입력은 전환을 오가도 유지. 시험은 응답에 `hashchange` 리스너가 있음을 고정한다(동작 자체는 실기).
- 사이드바 활성 표시 한계(쟁점 5)는 계획대로 수용했다 — "메뉴 관리"로 들어와도 "정적 페이지 관리" 항목이 활성으로 보인다.

### 이슈
- 실기 중 8080 포트를 점유한 프로세스가 앱이 아니라 **Docker Desktop 백엔드(`cms-app-dev` 컨테이너의 포트 게시)**였는데 이름 확인 없이 PID를 종료해 Docker가 내려갔다. Docker를 재시작해 복구했고(데이터 유지 확인) `docs/troubleshooting.md`에 기록했다. 이후 앱은 8081에서 구동했다.

### 후속
사이드바 두 항목 통합(V39 메뉴 행 정리)과 활성 표시 규칙, 메뉴 폼 입력의 미저장 보호, 좁은 화면 배치 검증.

### 최종 전체 실행
`./gradlew cleanTest test` 기본·`-Duser.timezone=UTC`(`JAVA_TOOL_OPTIONS`가 테스트 JVM에 전달된 것을 실행 로그로 확인) 각 1995건(기존 1988 + 신규 7) 실패 0, 건너뜀 12건(`LocalDiskFileStorageTest`의 Windows 심볼릭 링크 제약 — CI의 리눅스에서는 실행된다).

---

# 후속 PR — 사이드바 두 항목 통합 (U2-v1, 2026-10-14)

## U2.1 Context

이 문서의 쟁점 5는 "사이드바 두 항목 유지"를 결정하고 합치기를 후속으로 남겼다(V39 메뉴 행 정리, ADMIN/MANAGER별 라벨 문제). 지금 사이드바에는 **"메뉴 관리"**(`/admin/menu/manage`, ADMIN 전용, 같은 화면의 `#menu` 탭으로 302)와 **"정적 페이지 관리"**(`/admin/site-page/manage`, 위임 `SITE_PAGE`)가 따로 있고, 둘 다 같은 화면(탭: 정적 페이지 · 메뉴 구조 · 사이트)으로 들어간다. 이 PR은 두 항목을 **한 항목으로 합친다**. 사이드바를 카테고리 그룹으로 재구성하는 일은 범위 밖이다(사용자가 나중에 직접 한다). **스키마 변경 없음(데이터 마이그레이션 1개), 인가 정책(`SecurityConfig`·`AdminFeature`·권한관리) 변경 없음.**

## U2.2 정찰에서 발견한 쟁점 (코드로 확인)

1. **살아남는 쪽은 `/admin/site-page/manage` 행이어야 한다.** 이 URL이 `AdminFeature.SITE_PAGE.menuUrls`(사이드바 노출 판정 키, 문자열 완전 일치)이고, "메뉴 관리" URL(`MENU.menuUrls`)은 ADMIN 전용이라 MANAGER에게 어차피 안 보인다. 반대로 `/admin/menu/manage` 행을 남기면 MANAGER가 정적 페이지 메뉴를 잃는다.
2. **라벨 문제**: 사이드바 이름은 DB 행의 값 하나라 ADMIN·MANAGER에게 같다. 화면 머리글은 이미 역할별로 갈린다(ADMIN "정적 페이지 · 메뉴 · 사이트 관리", MANAGER "정적 페이지 관리" — "MANAGER에게는 메뉴를 관리한다는 머리글을 보이지 않는다", `ContentManagePageIntegrationTest:168`).
3. **메뉴 행은 관리자가 UI에서 편집·이동·삭제할 수 있는 데이터**라 마이그레이션이 "시드 그대로인 환경"만 건드려야 한다. 관리자가 이름을 바꿨거나, 그룹으로 옮겼거나, 자식을 달았거나, 합쳐질 쪽 행을 비활성화/삭제한 환경에서 무작정 지우면 ADMIN의 사이드바 진입점을 잃는다.
4. **머지된 시드는 수정 금지**(V3 `메뉴 관리`·V38 `정적 페이지 관리`) → 새 마이그레이션(V57, 데이터만)으로 정리한다. 최신은 V56. `menu`를 참조하는 외래키는 없다(`information_schema` 확인).
5. **활성 표시 한계가 저절로 풀린다**: 활성 판정은 `menu.menuUrl == currentUri`인데 "메뉴 관리"는 302 뒤 URI가 `/admin/site-page/manage`라 항상 "정적 페이지 관리"만 활성이었다. 한 항목이면 그 한계가 사라진다(코드 변경 없음).
6. **통합 검색은 사이드바 이름을 평탄화해 거른다**(`AdminSearchService.searchMenus`, 이름의 연속 부분 문자열) — 합치면 `메뉴 관리`·`정적 페이지 관리`라는 **구문 검색은 더 이상 일치하지 않는다**(한 이름에 둘을 담을 수 없음, 수용). 키워드 `메뉴`·`사이트`·`정적`은 합친 이름이 담는다. `/admin/menu/manage` 302·북마크는 `MenuPageController`가 그대로 처리한다.
7. **테스트 파급**: `MenuExposureSidebarIntegrationTest:132`가 ADMIN 사이드바에 `href="/admin/menu/manage"`가 있다고 단언(시드 의존) → 갱신. `MenuAccessRoleDropMigrationTest`는 V15 시점 스키마에서 도는 시험이라 무관. `SitePageMigrationTest`는 V36→V38까지라 무관. 새 V57 마이그레이션 시험이 필요하다(`SitePageMigrationTest`의 단계 적용 패턴).

## U2.3 설계 결정

### U2-1. 라벨 — 두 항목을 **"정적 페이지 · 메뉴 · 사이트 관리"** 한 이름으로 (역할별 라벨은 만들지 않는다)
- 선택지: (a) 공통 라벨을 화면 제목과 같은 문구로 (b) 사이드바가 역할별로 이름을 바꿔 렌더링 (c) 이름을 바꾸지 않고 "메뉴 관리" 행만 삭제.
- 선택: **(a)**. 화면 `<title>`·ADMIN 머리글과 같은 문구라 ADMIN은 사이드바에서 무엇이 있는지 알 수 있고, 검색 키워드 `메뉴`·`사이트`·`정적`이 이 항목을 찾는다(구문 `메뉴 관리`·`정적 페이지 관리` 검색은 일치하지 않는다 — 수용, 리뷰 P2). (b)는 데이터 기반 사이드바(`menu_name`)에 URL별 역할 분기를 넣는 특수 처리라 과설계이고 검색·가시성 계산에도 같은 분기가 필요하다. (c)는 가장 단순하지만 ADMIN이 사이드바에서 메뉴·사이트 편집 화면의 존재를 알 수 없고 `메뉴 관리` 검색이 사라진다.
- 비용(수용): MANAGER(SITE_PAGE 조회 권한)에게도 같은 이름이 보이지만 들어가면 정적 페이지 탭뿐이다 — 이름은 위임 불가 기능의 **존재를 암시할 뿐 접근을 열지 않는다**(탭·모달·API 문자열은 서버가 MANAGER 응답에서 제외하고 있다). 화면 머리글이 MANAGER에게 "정적 페이지 관리"로 보이는 기존 규칙은 그대로다.

### U2-2. 마이그레이션 `V57__merge_menu_manage_into_site_page.sql` (DML만, 전부-아니면-전무, v2 개정)
**적격 판정은 변경 전에 한 번만** 내려 세션 변수에 담고(`V3`의 `@menu_empty` 선례 — Flyway가 한 연결에서 실행), UPDATE와 DELETE가 **같은 변수**를 쓴다. 한 조건이라도 어긋나면 **두 행 모두 전혀 바뀌지 않는다**(개명도 삭제도 없음).
- **적격 조건(모두 만족)**: 문자열 비교는 `COLLATE utf8mb4_nopad_bin`(대소문자·후행 공백 구분, 프로젝트 선례 문의 알림 쿼리) —
  - **A(삭제 대상)**: `menu_url='/admin/menu/manage' AND menu_name='메뉴 관리' AND menu_icon='fas fa-fw fa-bars' AND menu_desc IS NULL AND use_yn=1 AND site_id=1 AND up_menu_no IS NULL`이고 **자식 없음**(어떤 행도 `up_menu_no`로 가리키지 않음)이며 같은 URL 행이 **정확히 1개**.
  - **B(남길 행)**: `menu_url='/admin/site-page/manage' AND menu_name='정적 페이지 관리' AND menu_icon='fas fa-fw fa-file-alt' AND menu_desc IS NULL AND use_yn=1 AND site_id=1 AND up_menu_no IS NULL`이고 **자식 없음**(리뷰 P1 — 활성 자식이 생기면 이 행이 그룹이 돼 링크가 그려지지 않아 두 진입점이 모두 사라진다)이며 같은 URL 행이 **정확히 1개**.
  - 날짜(`create_date`·`update_date`)와 `ord`는 판정에 쓰지 않는다(V3 시드는 `NOW()`, V38은 NULL이라 일관된 기준이 아니고, 드래그 재정렬을 보존 사유로 삼을 이유가 없다).
- **실행**: ① `SET @merge_ok = (SELECT … A와 B가 모두 적격이면 1 아니면 0)`(`menu` 읽기만, 서브쿼리 하나) ② `UPDATE menu SET menu_name='정적 페이지 · 메뉴 · 사이트 관리' WHERE menu_url='/admin/site-page/manage' AND @merge_ok = 1`(이름만 바꾼다, `update_date` 불변) ③ `DELETE FROM menu WHERE menu_url='/admin/menu/manage' AND @merge_ok = 1`. DELETE에 같은 테이블 서브쿼리·별칭이 없으므로 MariaDB 10.11 제약(`DELETE … AS 별칭` 미지원)을 피한다. URL 조건은 대소문자 구분 비교.
- **재실행 멱등**: 두 번째 실행에서는 A 행이 없어 `@merge_ok=0`이라 아무것도 하지 않는다. 관리자가 같은 이름의 행을 새로 만든 환경도 "같은 URL 행이 정확히 1개" 조건과 이름·아이콘·위치 조건이 맞지 않으면 건드리지 않는다.
- 선택지: URL만으로 지우기 / 이름·위치만 보기 → **거부**(리뷰 P2: 관리자 편집 범위를 놓친다). 사용자 확정 요구("이미 손본 환경은 덮어쓰지 않는다")를 문서화된 판정표로 구현한다.
- 신규 설치도 V3·V38이 먼저 두 행을 만들고 V57이 합치므로 기존 환경과 같은 결과다. 시드 파일(V3·V38)은 수정하지 않는다.

### U2-3. 코드 변경은 없다 (경로·권한·템플릿 불변)
`MenuPageController`의 302(`/admin/menu/manage` → `/admin/site-page/manage#menu`), `SecurityConfig`, `AdminFeature`(`MENU.menuUrls`는 노출 안내(`exposure`) 분류용으로 그대로), 통합 화면 템플릿은 바꾸지 않는다. `MENU.menuUrls`의 `/admin/menu/manage`는 더 이상 시드 행이 가리키지 않지만, 관리자가 직접 그 URL로 메뉴 행을 만들 수 있고 노출 계산은 카탈로그 기준이라 삭제하지 않는다(변경 시 `MenuServiceTest`의 노출 시험 5개 반례가 영향받는다 — 범위 밖).

### U2-4. 하지 않는 것
카테고리 그룹 재구성, 사이드바의 역할별 라벨, `V3`·`V38` 수정, `MENU` 기능 카탈로그 정리, 사이드바 활성 규칙 변경, 메뉴 탭으로 바로 가는 별도 입구.

## U2.4 테스트 계획

- **`SiteMenuMergeMigrationTest`**(실제 MariaDB, `SitePageMigrationTest`처럼 V56까지 적용 → 시나리오 데이터 구성 → V57 적용). 제외 시나리오는 **삭제 여부뿐 아니라 두 행(과 전체 `menu` 행)의 모든 컬럼이 그대로인지** 단언한다: ① 시드 그대로 → "메뉴 관리" 행 삭제·남은 행 개명·다른 행 불변 ② 재실행 멱등(적격 판정 문장부터 다시 실행해도 변화 없음) ③ "메뉴 관리" 이름을 바꾼 환경 ④ 그룹으로 옮긴 환경 ⑤ "메뉴 관리"에 자식이 달린 환경 ⑥ **남길 행에 활성(·비활성) 자식이 달린 환경**(P1) ⑦ 남길 행이 비활성/삭제/개명/아이콘 변경/이동된 환경 → 두 행 모두 불변(개명도 없음) ⑧ "메뉴 관리" 아이콘·설명·활성 상태가 시드와 다른 환경 ⑨ **같은 URL 후보가 2개**(관리자가 같은 URL로 행을 하나 더 만든 환경) ⑩ 대소문자·후행 공백이 다른 이름·URL(`nopad_bin` 비교로 보존) ⑪ 이미 새 이름인 관리자 작성 행 ⑫ 공개 사이트 메뉴 행(`site_id>1`)에 같은 URL이 있어도 불변 ⑬ 신규 설치(V1부터) 최종 상태: `/admin/menu/manage` 행 없음·새 이름 행 정확히 1개.
- **보존 시나리오의 실제 렌더링·검색(P1)**: 남길 행에 활성 자식이 있는 환경에서 마이그레이션 후에도 ADMIN 사이드바에 "메뉴 관리" 링크가 남고 검색으로 닿는다(통합 시험으로 고정).
- 기존 시험 갱신: `MenuExposureSidebarIntegrationTest`(ADMIN 사이드바에 새 이름·`/admin/site-page/manage` 링크가 있고 `href="/admin/menu/manage"`는 없다, MANAGER 단언은 유지). **검색**: 신규 이름이 `메뉴`·`사이트`·`정적` 검색에 나오고 MANAGER(SITE_PAGE 조회)도 같은 항목이 검색·사이드바에 보인다(`AdminSearchService` 통합 시험).

## U2.5 실기 검증 계획

개발 DB(V56)에 V57 적용 → ADMIN 사이드바에서 "메뉴 관리" 항목이 사라지고 "정적 페이지 · 메뉴 · 사이트 관리" 하나가 활성 표시 → 클릭하면 통합 화면(탭 3개), `/admin/menu/manage` 직접 접근은 `#menu` 탭으로 302되고 활성 표시가 그 항목 → 통합 검색에서 `메뉴`·`정적` 둘 다 이 항목이 나옴 → MANAGER(SITE_PAGE 조회)에게 같은 항목이 보이고 들어가면 정적 페이지 패널뿐 → 관리자가 이름을 바꾼 데이터로 마이그레이션을 다시 돌려도 보존(마이그레이션 시험이 고정). Playwright 스크린샷.

## U2.6 리스크

- **R1 라벨의 암시**: MANAGER에게 "메뉴·사이트 관리"라는 이름이 보인다(U2-1 수용).
- **R2 롤백**: 데이터 변경뿐이라 이전 앱은 그대로 기동한다(새 이름 행은 같은 URL이라 동작하고, 삭제된 "메뉴 관리" 항목은 이전 앱 사이드바에서도 사라진 채 `/admin/menu/manage` URL·검색 결과로만 닿는다). 되돌리려면 시드 행을 수동 INSERT해야 한다(`docs/migration-guide.md`에 SQL 기록).
- **R3 조건 오판**: 시드와 미세하게 다른 환경(이름·아이콘·설명·활성·위치·자식·중복 후보)은 `nopad_bin` 엄격 비교로 **전부-아니면-전무**의 보존 쪽(두 항목 유지)으로 실패한다 — 안전한 방향. 단 두 항목이 남은 환경은 중복이 그대로라 관리자가 메뉴 구조 편집 화면에서 직접 정리해야 한다.
- **R4 동시 편집**: 마이그레이션은 기동 시점 한 번, 관리자 편집과의 경합 없음(앱 기동 전 Flyway).

## U2.7 개정 이력
- U2-v1 (2026-10-14): 최초 작성 — 쟁점 7건, 결정 U2-1~U2-4.
- U2-v2 (2026-10-14, 라운드 1 — codex `gpt-6.1-sol` no-ship, 5건): **수용 4·부분 수용 1.** ① [P1] 남길 행에 활성 자식이 생기면 그룹이 돼 링크가 사라져 두 진입점이 모두 없어짐 → 남길 행도 최상위·자식 없음 조건 추가(수용) ② [P2] 두 문장이 서로 독립이라 개명만 실행되는 경로가 있음 → 적격 판정을 변경 전에 `@merge_ok` 한 번만, UPDATE·DELETE가 같은 값을 사용(수용) ③ [P2] 이름·URL·위치만으로는 시드 식별 불충분, V3 시드는 날짜가 `NOW()` → 아이콘·설명·활성 여부·후보 행 개수 검사 추가, 날짜·`ord`는 판정에서 제외(수용, `ord` 제외는 이유를 명시) ④ [P2] `utf8mb4_general_ci`는 대소문자·후행 공백을 구분하지 못함 → `COLLATE utf8mb4_nopad_bin` 비교(수용) ⑤ [P2] 검색어 `메뉴 관리` 구문이 사라짐 → 한 이름에 두 구문을 담을 수 없어 **회귀를 수용**하고 서술을 정정, 신규 이름 검색 시험 추가(부분 수용). MariaDB 10.11이 `DELETE … AS 별칭`을 지원하지 않는 점은 서브쿼리·별칭 없는 SQL로 회피. 시험 계획에 제외 시나리오의 전체 컬럼 불변 단언과 중복 후보·남길 행 자식·대소문자 케이스 추가.

## 구현·검증 결과 — 사이드바 두 항목 통합 (2026-10-14)

### Context
쟁점 5에서 후속으로 남긴 "사이드바 두 항목 통합"을 계획 U2-v2(적대적 리뷰 2라운드 ship, 사용자 승인)대로 구현했다. **스키마 변경 없음, 인가 정책(`SecurityConfig`·`AdminFeature`·권한관리) 변경 없음, 코드(Java·템플릿) 변경 없음** — 데이터 마이그레이션 1개(V57)와 시험·문서뿐이다. 카테고리 그룹 재구성은 사용자가 나중에 직접 하므로 범위 밖.

### 핵심 확정 사항
- 살아남는 행은 `/admin/site-page/manage`(SITE_PAGE의 사이드바 노출 판정 키)이고 이름은 **"정적 페이지 · 메뉴 · 사이트 관리"**(화면 `<title>`·ADMIN 머리글과 같은 문구). "메뉴 관리" 행은 지운다. 역할별 라벨은 만들지 않았다.
- `V57__merge_menu_manage_into_site_page.sql`: 적격 판정을 변경 전에 한 번 `@merge_ok`에 담고 UPDATE·DELETE가 같은 값을 쓴다 — 전부-아니면-전무. 조건: 두 행 모두 관리자 사이트에 같은 URL 행이 정확히 1개이고 시드 그대로의 이름·아이콘·설명(NULL)·활성·최상위·자식 없음, 문자열은 `utf8mb4_nopad_bin`. 날짜·`ord`는 판정에 쓰지 않는다. DELETE에 같은 테이블 서브쿼리·별칭이 없다.
- 통합 검색은 키워드 `메뉴`·`사이트`·`정적`이 합친 항목을 찾고 구문 `메뉴 관리`·`정적 페이지 관리`는 더 이상 일치하지 않는다(수용). 활성 표시 한계(쟁점 5)는 한 항목이 되며 해소됐다.

### 구현 파일
- 신규: `db/migration/V57__merge_menu_manage_into_site_page.sql`, `SiteMenuMergeMigrationTest`(9개 시험, 시나리오 13종)
- 시험 갱신: `MenuExposureSidebarIntegrationTest`(ADMIN·MANAGER 사이드바 단언), `AdminSearchIntegrationTest`(합친 항목 키워드 검색 2개 추가)
- 문서: `docs/migration-guide.md`("V57" 절·롤백 SQL), `com.cms.admin.menu/CLAUDE.md`, 이 계획서

### 검증 결과
- **전체 `./gradlew cleanTest test`** BUILD SUCCESSFUL(13분 13초).
- **변이 실험**(DELETE의 `@merge_ok` 조건 제거 + `nopad_bin`→`general_ci`): `SiteMenuMergeMigrationTest` 9건 중 5건 실패 — 관리자가 손본 "메뉴 관리" 행·남길 행 개명/이동/비활성·남길 행의 자식·중복 후보·대소문자/후행 공백 시나리오가 각각 잡았다. 원본 복원 후 통과.
- **실기(개발 DB V56 → V57 적용, 앱 8081, Playwright)**: Flyway가 V57을 success로 적용, `menu`에서 "메뉴 관리" 행만 사라지고 나머지 15행 불변·"정적 페이지 관리" 개명 / ADMIN 사이드바에 "메뉴 관리"가 없고 합친 항목 하나 / 클릭 시 통합 화면(탭 "정적 페이지·메뉴 구조·사이트")·합친 항목이 활성 표시 / `/admin/menu/manage` 직접 접근은 `#menu` 탭으로 302되고 활성 표시도 합친 항목 / 통합 검색 `메뉴`·`사이트`·`정적` 일치, `메뉴 관리` 구문은 0건 / 익명은 페이지 302 로그인·API 401 / **문서의 롤백 SQL 적용 → 두 항목 복원 → V57 SQL 재실행 → 다시 합쳐짐 → 한 번 더 재실행해도 행 수 불변(멱등)**. 스크린샷 `adversarial-review/screenshots/sidebar-merge/`.

### 이슈
- **구현 중 계획과 달라진 점 없음.**
- **시험 전제 정정**: 처음 추가한 단언("MANAGER 사이드바에 `/admin/site-page/manage`가 없다")이 틀렸다 — 이 시험의 MANAGER는 `setBoardRead(true)`로 위임 가능 기능 전체의 READ를 받으므로 합친 항목이 보이는 것이 정상이다. 단언을 고쳤고 "권한이 없으면 안 보임"은 `AdminSearchIntegrationTest`가 고정한다.
- **관찰(미조치)**: 1280px 사이드바에서 긴 이름("정적 페이지 · 메뉴 · 사이트 관리")이 두 줄로 접히고 마지막 글자가 혼자 내려간다. 이름을 줄이면(예: "페이지 · 메뉴 · 사이트 관리") 해결되지만 승인된 이름이라 바꾸지 않았다.
- **미확인**: 실제 MANAGER 계정의 브라우저 화면(개발 DB에 MANAGER 계정이 없어 만들지 않았다 — MANAGER 사이드바·검색 노출은 통합 시험으로 확인), 좁은 화면(모바일) 사이드바.
- **수용한 한계(계획대로)**: MANAGER에게도 같은 이름이 보인다(들어가면 정적 페이지 탭뿐), 구문 검색 회귀, 시드와 다른 환경은 두 항목이 그대로 남는다.

### 후속
- 코드 리뷰 게이트(`/code-review-loop`) → `/commitPR`. 사이드바를 카테고리 그룹으로 재구성하는 일은 사용자가 직접 한다.

---

# 후속 PR 2 — 탭 통합 화면을 "사이트 관리" 그룹 아래 세 화면으로 되돌린다 (U3-v1, 2026-10-16)

## U3.1 Context

#130에서 정적 페이지·메뉴 구조·사이트를 한 화면의 탭으로 합쳤고(방법 3′), #150(V57)에서 사이드바의 두 항목을 "정적 페이지 · 메뉴 · 사이트 관리" 한 항목으로 합쳤다. 사용자 요청(2026-10-16): **탭을 없애고 사이드바에 "사이트 관리" 그룹을 두어 그 하위 메뉴로 "정적 페이지 관리"·"메뉴 관리"·"사이트 설정"을 둔다.** 이슈 #151(긴 이름이 두 줄로 접힘)은 긴 이름이 사라져 같이 해소된다(닫을지는 구현 뒤 사용자에게 확인). 사이드바의 다른 항목 카테고리 재구성은 범위 밖이다(사용자가 직접). **스키마 변경 없음(데이터 마이그레이션 V58), 인가 정책(`SecurityConfig`·`AdminFeature`·권한관리) 변경 없음.**

## U3.2 정찰에서 발견한 쟁점 (코드로 확인)

1. **셸은 단순하고 조각은 이미 독립이다.** `admin/page/manage.html`(139줄)이 탭 내비게이션·세 패널·메뉴 모달·스크립트 배치 순서만 맡고, 패널은 `page-tab :: pagePanel/pageScript`·`menu-tab :: menuPanel/menuModals/menuScript`·`site-tab :: sitePanel/siteScript`다. 화면별 셸 3개로 나누면 된다.
2. **탭 간 결합은 소수고 분리 뒤에는 전부 쓸모없다**: ① `window.ContentScreen.isPagePanelBusyOrDirty`/`isSitePanelBusyOrDirty`(+ 메뉴 반영 가드 `handleApply`) ② `content:menu-applying` 이벤트(`notifyApplying`, 정적 페이지·사이트 패널의 `externalLock`) ③ `content:sites-changed`(사이트 패널이 발행 `notifySitesChanged`, 메뉴 패널의 수신 리스너 약 30줄, 정적 페이지 패널의 `scopeStaleNotice`) ④ `content:menu-applied` → 셸의 "새로 고치는 중" 안내(`contentReloadNotice`). 반영 성공 뒤 `location.reload()`는 같은 URL이라 해시 보존 로직이 필요 없다.
3. **URL과 게이트**: 정적 페이지 `/admin/site-page/manage`(`SITE_PAGE` 게이트·`menuUrls` — 불변), 메뉴 `/admin/menu/manage`(지금은 302만 하는 `MenuPageController`, `AdminFeature.MENU.menuUrls`에 이미 있음), **사이트 설정은 새 URL 필요**. `SecurityConfig`는 `/admin/**` ADMIN 캐치올이라 새 ADMIN 전용 페이지에 변경이 없고, 인가 컨벤션(`AdminEndpointAuthorizationConventionTest`)은 **읽기 전용 GET 페이지 핸들러를 면제**한다. 새 페이지 컨트롤러는 `@AdminPage`가 필수(`AdminPageAnnotationConventionTest`).
4. **사이드바는 이미 그룹(2단)·3단 중첩과 활성 그룹 펼침을 지원**하고(`sidebar.html`), MANAGER는 `MenuVisibility`가 보이는 자식이 없는 그룹을 가지친다(그룹 URL은 판정에 쓰이지 않는다). `SITE_PAGE`만 있는 MANAGER는 그룹 아래 "정적 페이지 관리" 하나만 본다.
5. **메뉴 행은 관리자가 UI에서 편집·이동·삭제할 수 있는 데이터**라 V58은 V57과 같은 "시드 그대로일 때만(전부-아니면-전무)" 원칙이 필요하다. 신규 설치는 V3·V38·V57·V58이 순서대로 돌아 기존 환경과 같은 결과가 된다.
6. **테스트 파급**: `ContentManagePageIntegrationTest`(7건, 탭·패널·MANAGER 응답 문자열 단언)는 화면별 시험으로 재작성, `SiteMenuTabBrowserTest`(`#menu`·`#site` URL)·`SiteDefaultTabBrowserTest`(`#site`)는 URL 갱신, `SiteMenuMergeMigrationTest`는 `flyway(null)`을 `flyway("57")`로(V58까지 적용되면 V57 결과가 달라진다), `MenuExposureSidebarIntegrationTest`·`AdminSearchIntegrationTest`는 새 이름·그룹 기준으로 갱신. `ContentScopeAdminBrowserTest`(`/admin/site-page/manage`)·`SitePagePermissionMatrixIntegrationTest`는 URL 불변이라 영향 없음.
7. **통합 검색**은 사이드바를 평탄화해 이름으로 거른다(그룹 자체는 결과에서 제외, 자식만) — 분리하면 구문 `정적 페이지 관리`·`메뉴 관리`가 다시 일치하고 `사이트 설정`이 새로 일치한다(#150의 검색 회귀가 해소된다).

## U3.3 설계 결정

### U3-1. URL — 사이트 설정은 `/admin/site/manage`, 나머지는 그대로
- 선택지: (a) `/admin/site/manage` (b) `/admin/site-setting/manage` (c) 사이트 설정을 `/admin/site-page/manage` 아래로(`/admin/site-page/site`).
- 선택: **(a)**. 사이트 API가 `/admin/api/sites`라 대응되고, `site-page`(정적 페이지)와 이름이 겹치지 않는다. (c)는 위임 가능 `SITE_PAGE` 게이트 안이라 ADMIN 전용 화면이 MANAGER 게이트 경로 안에 들어가는 혼동을 만든다.
- **카탈로그에 새 기능을 만들지 않는다**: ADMIN 전용 화면은 카탈로그 밖 URL이 곧 "ADMIN만(MANAGER fail-closed)"이고 `exposure`도 `ADMIN_ONLY`로 분류된다(`MENU`가 이미 같은 방식). 새 `AdminFeature`는 과설계다.
- `/admin/menu/manage`는 302 대신 **실제 화면**을 렌더링하고, 정적 페이지 화면은 URL·게이트·`SITE_PAGE` 판정 키 모두 그대로다.

### U3-2. 셸을 세 개로 쪼갠다 — 조각은 재사용, 결합 코드만 제거
- 새 셸: `admin/page/manage.html`(정적 페이지 패널만, MANAGER도 같은 화면, h1은 "정적 페이지 관리" 하나라 `sec:authorize` 이중 머리글이 사라진다), `admin/menu/manage.html`(메뉴 패널+모달+jstree CDN+반영 후 새로고침 안내), `admin/site/manage.html`(사이트 패널). ADMIN 전용 화면이라 셸의 `sec:authorize="hasRole('ADMIN')"` 래핑은 두지 않는다(URL 게이트가 최종 판정, 응답에 ADMIN 전용 마크업이 MANAGER에게 가는 경로 자체가 없다).
- 조각 파일 이름(`*-tab.html`)은 바꾸지 않는다(파일 이동은 diff만 키운다). 문서에 "탭이 아니라 패널 조각"으로 적는다.
- **제거하는 결합 코드**(분리 뒤 의미가 없고 주석이 사실과 달라지는 것만): 정적 페이지 패널의 `externalLock`·`ContentScreen.isPagePanelBusyOrDirty`·`content:menu-applying` 리스너·`scopeStaleNotice`(+마크업), 사이트 패널의 `externalLock`·`ContentScreen.isSitePanelBusyOrDirty`·`content:menu-applying` 리스너·`notifySitesChanged`와 호출, 메뉴 패널의 `notifyApplying`·`handleApply`의 두 `ContentScreen` 가드·`content:sites-changed` 리스너. **남기는 것**: 메뉴 반영 성공 시 `content:menu-applied`(셸이 새로고침 안내를 띄움), 각 패널 자체의 미저장 보호·`busy`·초안 규칙(메뉴 패널의 "편집할 사이트" 전환 상태 전이 포함).
- 결합이 사라지는 의미: 사이트 설정 화면에서 사이트를 바꾼 뒤 메뉴·정적 페이지 화면은 **다음 진입 때 새로 읽는다**(각 화면이 로드 시 서버 렌더링·API로 사이트 목록을 읽는다). 한 화면 안에서 실시간 반영하던 동작은 사라진다(수용).

### U3-3. 마이그레이션 `V58__split_site_admin_menu_group.sql` (DML만, 전부-아니면-전무)
- **적격 판정을 한 번만** `@group_ok`에 담고 모든 문장이 같은 값을 쓴다(V57과 같은 방식, 문자열은 `utf8mb4_nopad_bin`). 모두 만족해야 한다:
  - **A(옮길 행)**: 관리자 사이트(`site_id=1`)에 URL `/admin/site-page/manage` 행이 **정확히 1개**이고 이름 "정적 페이지 · 메뉴 · 사이트 관리"·아이콘 `fas fa-fw fa-file-alt`·설명 NULL·활성·최상위·자식 없음(V57 결과 그대로).
  - 같은 사이트에 URL `/admin/menu/manage`·`/admin/site/manage` 행이 **하나도 없다**(이미 있으면 중복이 된다).
  - 이름이 "사이트 관리"인 행이 없다(관리자가 같은 이름의 그룹을 이미 만들었으면 건드리지 않는다).
- **실행**(`@group_ok = 1`일 때만): ① 그룹 행 INSERT — 이름 "사이트 관리"·URL NULL·아이콘 `fas fa-fw fa-sitemap`·활성·`ord`는 A와 같은 값·최상위·날짜 NULL ② `SET @group_no = LAST_INSERT_ID()`(② 이후 모든 문장에 `@group_ok = 1` 조건이 있어 INSERT가 0행이어도 이전 세션 값을 쓰지 않는다) ③ A를 그룹 아래로 옮기고(`up_menu_no=@group_no`, `ord=0`) 이름을 "정적 페이지 관리"로 되돌린다 ④ 그룹 아래 "메뉴 관리"(`/admin/menu/manage`, 아이콘 `fas fa-fw fa-bars`, `ord=1`)와 "사이트 설정"(`/admin/site/manage`, 아이콘 `fas fa-fw fa-cog`, `ord=2`) INSERT.
- 신규 설치는 V3·V38·V57·V58이 순서대로 돌아 같은 결과다. 시드 파일은 수정하지 않는다.
- **적격하지 않은 환경**(관리자가 V57의 행을 손봤거나 V57이 건너뛰어 두 항목이 남은 환경): 아무것도 바뀌지 않는다. 이 환경에서도 새 코드로 "메뉴 관리"(`/admin/menu/manage`)와 "정적 페이지 관리" 항목은 정상 동작하지만 **"사이트 설정" 화면은 사이드바 진입점이 없다**(ADMIN이 URL로 열거나 메뉴 구조 편집에서 행을 추가) — 롤백·배포 문서에 적는다.

### U3-4. MANAGER 노출은 가지치기가 결정한다 (코드 변경 없음)
그룹 URL은 NULL이라 노출 판정에 쓰이지 않는다. ADMIN은 그룹 아래 3개, `SITE_PAGE` READ가 있는 MANAGER는 "정적 페이지 관리" 하나(그룹 노출), 권한이 없는 MANAGER는 그룹 전체가 숨겨진다. 시험으로 고정한다.

### U3-5. 하지 않는 것
`AdminFeature`·`SecurityConfig` 변경, 조각 파일 이름 변경, 사이드바의 다른 항목 재구성, 메뉴 트리 편집 규칙 변경, 사이트별 콘텐츠 규칙 변경, #151 이슈 임의 종료.

## U3.4 테스트 계획

- **`SiteMenuGroupMigrationTest`**(V57까지 적용 → 시나리오 구성 → V58 적용, 제외 시나리오는 `menu` 전체 행 스냅샷 불변): ① V57 결과 그대로 → 그룹 1개·A가 그룹 아래 "정적 페이지 관리"로·그룹 아래 "메뉴 관리"·"사이트 설정"·다른 행 불변 ② 재실행 멱등(적격 판정부터 다시 실행해도 변화 없음) ③ A를 개명·이동·비활성·아이콘 변경한 환경 ④ A에 자식이 달린 환경 ⑤ A가 중복·없음 ⑥ 이미 `/admin/menu/manage`·`/admin/site/manage` 행이 있는 환경 ⑦ 이름이 "사이트 관리"인 행이 있는 환경 ⑧ 대소문자·후행 공백 ⑨ 공개 사이트(`site_id>1`) 행은 불변 ⑩ V57이 건너뛰어 "메뉴 관리"·"정적 페이지 관리"가 따로 남은 환경은 불변 ⑪ 신규 설치(V1부터) 최종 상태(그룹 1·자식 3·`/admin/site-page/manage` 1·합친 이름 행 없음).
- **`SiteMenuMergeMigrationTest`**: `flyway(null)` → `flyway("57")`로 V57 시점에 고정(시험 의도 불변).
- **`ContentManagePageIntegrationTest` 재작성**: ADMIN — 정적 페이지 화면은 페이지 패널만(탭·메뉴·사이트 마크업 없음, 중복 id 없음, 스크립트 배치 순서), 메뉴 화면(`/admin/menu/manage`는 302가 아니라 200)은 메뉴 패널·모달 2개(`body` 직계)·jstree·`content:menu-applied` 안내, 사이트 화면(`/admin/site/manage`)은 사이트 패널 / MANAGER — 정적 페이지 200·메뉴·사이트 403 / 비로그인 302 / 응답에 다른 화면의 마크업·API 문자열이 섞이지 않음.
- **브라우저 시험(Playwright)**: `SiteMenuTabBrowserTest`·`SiteDefaultTabBrowserTest`의 이동 URL 갱신, 분리 뒤에도 메뉴 화면의 사이트 전환·구조 반영 후 새로고침, 사이트 화면의 기본 지정·삭제 흐름이 그대로 통과.
- **사이드바·검색**: `MenuExposureSidebarIntegrationTest` — ADMIN 사이드바에 그룹 "사이트 관리"와 하위 3개 링크, MANAGER(SITE_PAGE 허용)는 그룹 아래 "정적 페이지 관리"만, SITE_PAGE가 없는 MANAGER는 그룹이 없음 / `AdminSearchIntegrationTest` — 구문 `정적 페이지 관리`·`메뉴 관리`·`사이트 설정`이 각 항목 URL로 일치하고 그룹은 결과에 없음, `정적 페이지 관리`는 SITE_PAGE 권한을 따름.
- 회귀(무변경 통과): `SitePagePermissionMatrixIntegrationTest`·`ContentScopeAdminBrowserTest`·`AdminPageAnnotationConventionTest`·`AdminEndpointAuthorizationConventionTest`·`SecurityConfigTest`·`MenuServiceTest`·`AdminSidebarAdviceTest`.

## U3.5 실기 검증 계획

개발 DB(V57 상태)에 V58 적용 → ADMIN 사이드바에 그룹 "사이트 관리"(펼침) 아래 3개 → 각 링크 이동·활성 그룹 펼침·탭 없는 화면 → 정적 페이지 등록·수정, 메뉴 구조 드래그 → [반영] → 새로고침 안내·반영 확인, 사이트 설정에서 사이트 생성·기본 지정·삭제 왕복 → 메뉴 화면의 "편집할 사이트"에 새 사이트가 다음 진입 때 보임 → `/admin/menu/manage` 직접 접근이 302가 아니라 화면 → MANAGER(SITE_PAGE만) 화면 → 익명·MANAGER의 메뉴·사이트 화면 직접 접근 차단(302·403). 문서의 롤백 SQL로 왕복(그룹 해체 → 한 항목 복원 → V58 재실행). 스크린샷.

## U3.6 리스크

- **R1 큰 템플릿 수정**: `menu-tab.html`(1,682줄)에서 결합 코드만 제거 — 실수로 다른 동작을 건드릴 위험 → 제거 범위를 위 목록으로 한정하고 브라우저 시험(`SiteMenuTabBrowserTest`)이 사이트 전환·반영 흐름을 고정.
- **R2 V58 적격하지 않은 환경**: "사이트 설정" 진입점이 사이드바에 없음 → 문서화, 관리자가 메뉴 구조 편집으로 행을 추가할 수 있다.
- **R3 롤백**: 이전 앱(탭 화면)은 그룹과 "정적 페이지 관리" 항목은 그대로 보이고, "메뉴 관리"(`/admin/menu/manage`)는 옛 302로 동작하지만 "사이트 설정"(`/admin/site/manage`)은 404다 — 롤백 전에 두 행(과 그룹)을 정리하는 수동 SQL을 `docs/migration-guide.md`에 적는다. 구·신 앱 혼합 운영은 금지(사이드바 데이터가 두 코드에 모두 보인다).
- **R4 사이트 변경의 실시간 반영 상실**: 사이트 화면에서 사이트를 바꿔도 이미 열린 메뉴·정적 페이지 화면은 낡은 선택지를 가진다(다음 진입 때 갱신) → 수용(한 화면 안의 이벤트 결합을 만들지 않는다).
- **R5 #130 계획과의 방향 전환**: 한 화면 통합 결정을 되돌리는 변경이라 `PLAN-content-menu-unify.md`·루트 `CLAUDE.md`·`com.cms.admin.page`·`com.cms.admin.menu`·`com.cms.admin.site` 지침의 "탭" 서술을 모두 갱신한다.

## U3.7 개정 이력
- U3-v1 (2026-10-16): 최초 작성 — 쟁점 7건, 결정 U3-1~U3-5.

- U3-v2 (2026-10-16, 라운드 1 — codex `gpt-6.1-sol` no-ship, 7건): **수용 6·결정 필요 1.**
  - ① [major] V58 비적격 환경(관리자가 V57 결과 행의 이름·아이콘을 바꿈)에서는 `/admin/menu/manage` 행이 V57에서 이미 지워졌으므로 U3 이후 남은 링크가 정적 페이지 화면만 열어 **"메뉴 관리" 진입점까지 사라진다** — U3-3의 "메뉴 관리는 정상 동작" 서술은 틀렸다(수용, 서술 정정). **결정 필요**: 비적격 환경 처리 방식(아래 U3-3 개정안 선택지). 결정 전까지 반영하지 않는다.
  - ② [major] 롤백 SQL이 "배포 뒤 관리자가 그룹을 옮기거나 자식을 추가한 경우"에 안전하지 않고, V58 성공 이력은 수동 복원과 별개 → **이전 앱 롤백 → 신버전 재배포** 경로 미검증(수용). 롤백 SQL은 ⓐ 대상 행을 `menu_url`+그룹 소속으로 식별하고 ⓑ 그룹에 다른 자식이 있으면 그룹을 지우지 않고 자식만 상위로 올리며 ⓒ 한 트랜잭션으로 실행한다고 `docs/migration-guide.md`에 적고, 실기 검증에 "이전 앱 이미지 기동 → 신버전 재기동" 왕복을 넣는다. V57·V58 파일 수정으로 체크섬을 맞추는 방식은 금지(문서에 명시).
  - ③ [major] `@group_ok` 공유가 "전부-아니면-전무"의 실패 시 원자성을 증명하지 않음(수용). 시험 `SiteMenuGroupMigrationTest`에 **실패 주입**(그룹 INSERT 뒤 후속 문장 실패를 유도하는 제약 위반 시나리오)을 넣어 Flyway 실행 후 `menu` 스냅샷 불변·이력 상태를 단언한다. MariaDB DDL이 없는 DML 전용 스크립트라 Flyway가 트랜잭션으로 감싸는지(`flyway.group`/문장별 커밋 여부)를 정찰로 먼저 확인하고, 감싸지 않으면 V58을 `START TRANSACTION … COMMIT`으로 묶는다.
  - ④ [major] `SiteMenuTabBrowserTest`에는 제거 대상 `content:sites-changed`를 직접 발행하고 지연 응답을 기다리는 시험이 있어 "URL만 바꾸면 통과"는 틀렸다(수용). 사라지는 이벤트 계약의 시험은 **삭제**하고, 대체 계약 "사이트 화면에서 변경 후 메뉴 화면에 새로 진입하면 최신 목록을 읽는다"·"삭제된 사이트를 기억한 상태(저장된 선택)로 진입해도 안전하게 기본값으로 떨어진다"를 시험으로 추가한다. U3.4의 브라우저 시험 서술 정정.
  - ⑤ [minor] `LAST_INSERT_ID()` 설계 자체는 합리적, 다만 반례 시험 부재(수용): 같은 연결에 이전 INSERT ID를 남긴 비적격 실행·비기본 `AUTO_INCREMENT` 값(예: 1000부터)·재실행 뒤 부모 ID 정확성을 `SiteMenuGroupMigrationTest`에 추가.
  - ⑥ [minor] 메뉴·사이트 조각의 CSS가 `#menuTabPanel`·`#siteTabPanel` 래퍼 ID 아래에서만 적용되고 메뉴 높이는 탭 공간을 감안한 값(수용): 새 셸은 **래퍼 ID를 그대로 유지**하고(`id`만 남기고 탭 의미는 없음) 높이 계산식을 탭 바 제거에 맞춰 조정, 실기 검증에서 높이·스크롤을 스크린샷으로 확인.
  - ⑦ [minor] `/admin/site-page/manage#menu`·`#site` 북마크가 조용히 정적 페이지 화면을 연다(수용): 정적 페이지 셸에 **작은 호환 스크립트**(해시가 `#menu`/`#site`이고 ADMIN 응답에 해당 이동 링크가 있을 때만 `location.replace`)를 두거나 호환을 종료한다 — 구현 단계에서 ADMIN 전용 마크업 노출 규칙(MANAGER 응답에 메뉴·사이트 문자열 금지)과 충돌하지 않는 쪽으로 하며, 스크립트는 ADMIN 응답에서만 렌더링한다.
  - ① 결정(사용자, 2026-10-16): **완화된 적격 + 보존.** U3-3 개정 — 적격 = 관리자 사이트(`site_id=1`)에 URL `/admin/site-page/manage` 행이 **정확히 1개**이고 최상위·자식 없음·활성, `/admin/menu/manage`·`/admin/site/manage` 행이 없으며 이름 "사이트 관리"인 행이 없을 것(이름·아이콘·설명은 판정에서 제외). 실행: 그룹 INSERT 후 그 행을 그룹 아래(ord 0)로 옮기되 **이름·아이콘·설명은 건드리지 않는다** — 단 이름이 V57 시드 그대로("정적 페이지 · 메뉴 · 사이트 관리")일 때만 "정적 페이지 관리"로 되돌린다. 메뉴 관리·사이트 설정 행 INSERT는 동일. 시험에 이름·아이콘만 바꾼 환경(이름 보존·진입점 3개 존재) 추가, 이름이 시드 그대로인 환경과 구분.
- U3-v3 (2026-10-16, 라운드 2 — codex `gpt-6.1-sol` no-ship, 새 지적 1건): **수용 1.** [major] 탭 이동은 DOM을 유지했지만 분리 뒤 사이드바 링크는 문서를 교체하므로, 정적 페이지 저장·삭제 요청이 진행 중(`saving=true`)일 때 `beforeunload`가 경고를 생략(`page-tab.html:523`)해 응답(409·검증 오류) 처리 문맥과 입력이 경고 없이 사라질 수 있다(수용). 그 조건은 메뉴 반영 후 `location.reload()`와 경쟁하지 않게 하려던 탭 시절의 것이라 분리 뒤에는 필요 없다 → **`beforeunload` 경고 조건을 `isDirty() || saving`으로 바꾼다**(정적 페이지·사이트·메뉴 패널 모두 같은 규칙 확인; 메뉴 패널의 반영 성공 `location.reload()` 직전에는 `dirty`·`busy`를 해제하는 기존 처리를 그대로 두고 확인). 사이드바 링크 가로채기(대기/차단)는 만들지 않는다 — 브라우저 표준 이탈 경고로 충분(과설계). 시험: 저장 응답을 지연시킨 채 다른 화면으로 이동 시도 시 `beforeunload` 경고가 발생하고(취소하면 화면·입력 유지) 성공·409·실패 각각 응답 처리 후 입력·안내가 유지되는지 브라우저 시험에 추가.
- U3-v4 (2026-10-16, 사용자 지시 — 운영 배포 전 확인): **운영 배포가 아직 진행되지 않았다**(개발 DB만 존재). 이에 따라 범위 축소: ① U3-v2 ②의 롤백 SQL은 `docs/migration-guide.md`에 **짧은 참고용**(대상 식별·추가 자식 보존·트랜잭션 원칙 한 단락)으로만 적고, "이전 앱 이미지 기동 → 신버전 재배포" 왕복 시험과 실기 검증의 롤백 왕복은 **생략**한다(롤백할 운영 환경이 없고 개발 DB는 재생성 가능). 구·신 앱 혼합 운영 금지 서술은 문서에 유지. ② **유지**: V58 실패 주입 시험·재실행 멱등 시험·`LAST_INSERT_ID` 반례(비기본 AUTO_INCREMENT·이전 INSERT ID 잔존) 시험·완화된 적격 시나리오 — 배포 시점과 무관한 마이그레이션 정확성이다. ③ U3.6 R3(롤백)은 "운영 배포 전이라 영향 없음, 배포 후 롤백 필요 시 참고 SQL 사용"으로 낮춘다. ④ U3.6 R4(사이트 변경의 실시간 반영 상실)는 **동시성 문제가 아니라 UI 낡음**임을 명확히 한다 — 서버가 사이트 존재를 매번 검증(행 잠금·409)해 데이터는 깨지지 않으며, 두 화면을 동시에 여는 드문 경우에만 낡은 선택지가 보이고 새로고침으로 해소; 별도 동기화는 만들지 않고 "삭제된 사이트를 기억한 채 진입" 시험으로 고정.

## U3 구현·검증 결과 (2026-10-16)

계획 U3-v4(적대적 리뷰 3라운드 ship + 운영 미배포 범위 축소)대로 구현했다. **스키마 변경 없음(데이터 마이그레이션 V58), 인가 정책(`SecurityConfig`·`AdminFeature`·권한관리) 변경 없음.**

- **화면**: `admin/page/manage.html`(정적 페이지 패널만, ADMIN 응답에만 `#menu`·`#site` 북마크 호환 스크립트), `admin/menu/manage.html`(신규 셸, `MenuPageController`가 302 대신 렌더링), `admin/site/manage.html`(신규 셸, `SiteManagePageController` — 정적 페이지 쪽 `SitePageController`와 빈 이름이 겹쳐 이름을 달리했다). 래퍼 id(`menuTabPanel`·`siteTabPanel`·`pagesTabPanel`)는 조각 CSS가 의존하므로 유지, 메뉴 높이는 `calc(100vh - 200px)`.
- **결합 코드 제거**: `ContentScreen`·`externalLock`·`content:menu-applying`·`content:sites-changed`·`notifyApplying`·`notifySitesChanged`·`scopeStaleNotice`. 남김: `content:menu-applied`(새로고침 안내). 정적 페이지·사이트 패널의 `beforeunload`는 `isDirty() || saving`(U3-v3).
- **V58**: 완화된 적격(URL 1행·활성·최상위·자식 없음, 이름·아이콘·설명 보존, 시드 이름일 때만 개명), `@group_ok`·`LAST_INSERT_ID` 가드. `SiteMenuGroupMigrationTest` 11건(적격·신규 설치·멱등·값 보존·비적격 10종·후행 공백·공개 사이트 불변·비기본 AUTO_INCREMENT·이전 INSERT ID 잔존·**실패 주입** — 롤백 확인, Flyway 이력에 실패 행이 남아 `repair` 후 재적용).
- **시험**: `ContentManagePageIntegrationTest` 재작성(8건), `SiteMenuTabBrowserTest`→`SiteMenuScreenBrowserTest`(사라진 `sites-changed` 시험 삭제, "재진입 시 최신 목록"·"삭제된 사이트를 기억한 채 진입" 추가)·`SiteDefaultTabBrowserTest`→`SiteDefaultBrowserTest`, 신규 `PageScreenLeaveGuardBrowserTest`(조건을 되돌리는 변이로 실패 확인), `MenuExposureSidebarIntegrationTest`(그룹 가지치기)·`AdminSearchIntegrationTest`(세 항목 구문 검색) 갱신, `SiteMenuMergeMigrationTest`는 target 57로 고정. **전체 `./gradlew cleanTest test` 통과(15분).**
- **실기(Playwright, 개발 DB에 V58 적용)**: 사이드바에 "사이트 관리" 그룹(활성 시 펼침) 아래 3개, 세 화면 모두 탭 없이 렌더링, `/admin/menu/manage`가 302가 아닌 화면, `/admin/site-page/manage#site`가 `/admin/site/manage`로 이동. 이슈 #151(긴 이름 줄바꿈)은 항목 이름이 짧아져 해소됨(닫을지는 사용자에게 확인).
- **운영 배포 전이라 생략**: 이전 앱 롤백→재배포 왕복 시험(롤백 SQL은 `docs/migration-guide.md`에 참고용).
