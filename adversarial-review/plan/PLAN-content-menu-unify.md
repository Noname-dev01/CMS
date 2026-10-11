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

### 쟁점 5 — 사이드바 항목은 둘 다 유지
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
