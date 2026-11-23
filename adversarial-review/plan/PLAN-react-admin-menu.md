# PLAN — 관리자 화면 React 2단계: 메뉴 편집 화면 전환 (이슈 #296)

> 상태: 계획 v6 (2026-11-22) — 적대적 리뷰 5라운드 ship·사용자 승인, 2026-11-23 구현·검증 완료(PR 전 — 결과는 "구현·검증 결과" 절), 드래그는 포인터 이벤트(사용자 결정)
> 경로: **정식** (빌드 설정 변경 — 진입점 추가·공유 청크 정책, 정적 산출물 계약 변경)
> 위험 변경: **없음** — 인가(URL 규칙·`SecurityConfig`)·서버 API·데이터 정합성·잠금·스키마를 바꾸지 않는다. 화면 동작 중 **드래그 방식은 바꾼다**(결정 1, 사용자 선택).

## 1. Context

### 목적
- 1단계(#301, `PLAN-react-admin-faq.md`)에서 만든 빌드 체계·아일랜드 방식으로 메뉴 편집 화면(`templates/admin/menu/fragments/menu-tab.html`의 `menuScript`, 약 1,170줄)을 React + TypeScript로 옮긴다.
- 구직용 React 경험 확보와 새 프로젝트 기반(관리자 화면은 새 프로젝트로 넘어간다)이 1단계와 같은 목적이다.
- **트리와 드래그도 React로 직접 구현한다**(2026-11-22 사용자 선택: jstree 유지·dnd-kit 도입 대신). jstree(jQuery 플러그인, cdnjs)와 CDN 참조를 화면에서 없앤다.

### 범위
- 메뉴 관리 화면(`admin/menu/manage.html` 셸 + `menu-tab.html` 조각) 전환. 서버 코드·API·`SecurityConfig`·스키마 무변경.
- 진입점이 둘(`faq-manage`·`menu-manage`)이 되며 생기는 공유 청크의 캐시·보존 정책 결정과 산출물 기준(S3) 갱신.
- 범위 밖: 게시글 화면(#297), `<dialog>` 전환(#298), 드래그 키보드 대안(#300과 같은 성격 — 후속). 터치 드래그는 옛 화면이 지원하므로(R21) **유지한다**(v4).

### 정찰에서 확인한 사실
| # | 사실 | 근거 |
|---|---|---|
| R1 | 메뉴 스크립트는 `$(document).ready` 클로저 하나다. 상태: `mode`·`selectedMenuNo`·`pendingParentMenuNo`·`selectedMenuSnapshot`·`permanentDeleteTarget`·`treeScope`·세대 `loadSeq`·`selectSeq`·`sitesSeq`·`busy`·`baseStructure`·`draftChanged`·`leavingAfterApply`·`queuedNotice`·사이트 상태 `sites`·`currentSiteId`·`currentSite`·`lastAppliedSiteId`·`switching` | `menu-tab.html:465-1631` |
| R2 | jstree와 맞닿은 부분: 트리 초기화(`initTree` — `core.data`·`check_callback`·`dnd`·`types` 폴더 아이콘), 트리 교체 때 `destroy` 후 재초기화, 구조 읽기(`readCurrentStructure`), `select_node`·`move_node` 이벤트, `deselect_all`, `get_selected` | `menu-tab.html:741-1137` |
| R3 | **드래그 규칙(서버와 같은 범위)**: 같은 부모 안 재정렬은 깊이와 무관하게 항상 허용, 부모가 바뀌면 자기·자손 아래 금지(순환)와 `새 부모 깊이 + 이동 서브트리 높이 ≤ 3` 검사. 다중·외부 드래그 금지. 드래그 가능 조건 `treeScope === 'all' && !busy`. 드롭 뒤 `canDrag()`가 거짓이면 재조회로 되돌림 | `menu-tab.html:1086-1137`, 메뉴 `CLAUDE.md` |
| R4 | **반영 대기 건수** = 부모가 바뀐 메뉴 + 같은 부모에 남은 메뉴 중 기준 인덱스의 최장 증가 부분 수열 밖의 메뉴. 반영 요청은 깊이 우선 순서의 전체 메뉴 `{menuNo, baseUpMenuNo, baseOrd, upMenuNo}`이고 `base*`는 트리 응답의 `?? null`(최상위·레거시 행은 키 생략, 서버는 필드 누락 400) | `menu-tab.html:727-799, 980-993` |
| R5 | 반영 결과: 2xx → `sessionStorage('menuStructureApplied')`에 건수 저장, `leavingAfterApply`, `content:menu-applied` 이벤트(셸이 `#contentReloadNotice` 표시), `location.reload()`. 409 → 오류 알림 + "서버의 최신 구조로 새로고침했습니다", 선택 초기화, 재조회(초안 폐기). 그 밖(400 등)·네트워크 오류 → 초안 유지·오류 알림 | `menu-tab.html:995-1052`, `manage.html:51-63` |
| R6 | 잠금(`refreshDraftUi`): 초안 있음 또는 `switching`이면 "비활성 포함" 토글·[새 최상위]·[하위 추가]·[저장]·[비활성화]·[영구삭제]·[초기화]·아이콘 버튼·폼 입력 전부 `disabled`. 사이트 셀렉트는 초안 또는 `busy`면 `disabled`. [반영]·[취소]는 초안이 없거나 `busy`면 `disabled`. 모드 안내 문구 3종 | `menu-tab.html:797-824` |
| R7 | 영구삭제: 비활성 메뉴만, 모달 여는 순간 `{menuNo, menuName}` 고정, 확인 문자열은 `escapeForConfirm`(`\\`·`\r`·`\n`·`\t`·제어문자·U+2028/2029·일반 공백 외 공백류 → `\uXXXX`), 입력은 trim 없이 정확 비교, 204/2xx → 성공 알림, 404 → 경고 알림(둘 다 모달 닫고 재조회), 그 밖 → 모달 안 오류. 모달은 `data-backdrop="static"`·`data-keyboard="false"`, `shown`에서 입력 포커스, `hide`에서 포커스 해제 | `menu-tab.html:1272-1427` |
| R8 | 비활성화: `confirm('선택한 메뉴를 비활성화하시겠습니까?')` 뒤 `PATCH {useYn:false}`만 전송(폼 값 미포함), 대상은 확정 스냅샷 | `menu-tab.html:1229-1268` |
| R9 | 저장: 생성 `POST /admin/api/menus` `{menuName,menuUrl,menuIcon,menuDesc,useYn,upMenuNo,siteId}`(값은 trim), 수정 `PATCH /{id}` 같은 필드(`useYn` 포함 — 메뉴 `CLAUDE.md`의 알려진 stale-form 한계, 이번에 바꾸지 않음). 저장 전 URL 안내 검사(`SAME_ORIGIN_PATH`·`EXTERNAL_HTTP_URL` — 서버 `SafeUrls` 복제, 사이트 링크 정책) | `menu-tab.html:1150-1226` |
| R10 | 사이트: 시작 때 `GET /admin/api/sites` 한 번, `sessionStorage('menuSiteId')`의 사이트가 목록에 있으면 그것, 아니면 관리자 사이트(1). 전환은 초안·`busy`면 거부, 전환 중 `switching` 잠금, 조회 실패 시 이전 사이트로 셀렉트·편집 대상 복원 + "이전 사이트로 되돌렸습니다". 노출 안내 그룹은 관리자 사이트에서만, URL 도움말·사이트 안내 문구는 링크 정책별 | `menu-tab.html:1453-1631` |
| R11 | 아이콘 선택기: 고정 아이콘 이름 목록, 검색(부분 일치), 선택 시 `fas fa-fw fa-<이름>`을 숨은 입력에, 미리보기·라벨. 현재 값과 같은 항목 `.selected`. 모달 `#iconPickerModal`(닫기 버튼 있음, 정적 백드롭 아님) | `menu-tab.html:614-680` |
| R12 | `beforeunload`: 초안이 있고 반영 직후가 아니면 경고 | `menu-tab.html:1433-1438` |
| R13 | **브라우저 시험은 3건뿐**(`SiteMenuScreenBrowserTest` — 저장 뒤 셀렉트 해제·재진입 시 최신 사이트 목록·삭제된 기억 사이트 대체). 셀렉터 `#siteSelect`·`#menuTree .jstree-anchor`·`#btnNewTop`·`#menuName`·`#menuUrl`·`#btnSave`. 드래그·반영·영구삭제·비활성화·사이트 전환 실패는 **브라우저 시험이 없다** | `SiteMenuScreenBrowserTest:73-138` |
| R14 | 서버 렌더링 시험 `ContentManagePageIntegrationTest`가 메뉴 화면 HTML에 `jstree.min.js`·`content:menu-applied`·모달 2개(패널 밖)·`#contentReloadNotice`·`#contentReloadNow`, 스크립트 순서(`jQuery < jstree < $(document).ready`)를 단언한다 | `ContentManagePageIntegrationTest:120-183` |
| R15 | 트리 응답 `MenuTreeResponse`: `id`·`text`(=메뉴명)·`state.opened=true`·`data{menuNo,menuUrl,menuIcon,useYn,ord,upMenuNo,exposure,exposureLabel,menuLevel,topMenuNo}`·`children`, `non_null` 직렬화 | `MenuTreeResponse.java` |
| R16 | jstree 3.3.12는 `core.force_text` 기본값이 false다(**확인**: 화면이 싣는 `cdnjs …/3.3.12/jstree.min.js`의 `force_text:!1`, 2026-11-22). 지금 화면은 메뉴명을 HTML로 해석한다(프로젝트 규약 C5 위반 — 메뉴 편집은 ADMIN 전용이라 노출 범위는 ADMIN끼리). 특성 시험 F5는 옛 화면에서 실패가 기대값이다 | `initTree`에 `force_text` 없음, CDN 파일 |
| R21 | jstree 3.3.12 dnd 플러그인은 **터치 드래그를 지원한다**(**확인**: 같은 파일의 dnd 기본값 `touch:!0`, `touchstart.jstree`·`touchmove.vakata.jstree`·`touchend.vakata.jstree` 처리, 2026-11-22) — v2의 "옛 화면도 터치 없음"은 틀렸다(R2 리뷰 #3) | CDN 파일 |
| R17 | **진입점이 둘이면 공유 청크가 생긴다(실측)**: 현재 `vite.config.ts`에 두 번째 진입점을 더해 scratchpad로 빌드하면 `chunks/http-<hash>.js`(219KB — React·`react-dom`·`api/http.ts`)가 생기고 두 진입점이 정적 `import "./chunks/…"`로 불러온다 | 2026-11-22 scratchpad 빌드(Vite 8.3.6·Rolldown) |
| R18 | 정적 응답은 `Cache-Control: no-store` + `Last-Modified`(1단계 S8 실측) | `PLAN-react-admin-faq.md` 구현 결과 |
| R19 | FAQ 화면은 HTML5 드래그(`draggable`·`dragover`·`drop`)를 React로 구현했고, 브라우저 시험은 Playwright `page.dragAndDrop(source, target)`으로 재현한다(Chromium) | `FaqManage.tsx:305-365`, `FaqAdminOrderBrowserTest:178` |
| R20 | 화면은 서버 모델 데이터를 쓰지 않는다. `MAX_DEPTH = 3`(서버 `MenuService.MAX_MENU_DEPTH`)·`ADMIN_SITE_ID = 1`(서버 `Site.ADMIN_SITE_ID`)은 JS 상수 복제다 | `MenuPageController`, `menu-tab.html:467,505` |

## 2. 설계 제약 (명문화)
- **C1 시험 우선**: 전환 **전에** 옛 화면(jstree) 기준으로 특성 브라우저 시험을 추가해 통과시키고, 전환 뒤 같은 시험이 그대로 통과해야 한다. 시험은 트리 노드 찾기·드래그를 **도우미 메서드 두 개**(`treeLabel(name)`·`moveMenu(source, target, position)`)로만 하고, 전환 때는 이 도우미의 구현만 바꾼다(옛: jstree API, 새: `page.dragAndDrop` + 위치 좌표). 단언(DOM id·문구·DB·요청 본문)은 바꾸지 않는다.
- **C2 동작 유지, 드래그만 예외**: 결정 1의 드래그 UX 변경과 결정 2의 표시 변경(C5) 외에는 R1~R12의 동작·id·문구를 그대로 둔다. 기존 id(`#siteSelect`·`#includeInactiveToggle`·`#btnNewTop`·`#btnNewChild`·`#btnApply`·`#btnCancel`·`#draftStatus`·`#draftBar.dirty`·`#treeAlert`·`#treeWarning`·`#treeSuccess`·`#treeHint`·`#formAlert`·`#formModeLabel`·폼 필드 id·`#btnSave`·`#btnDeactivate`·`#btnPermanentDelete`·`#btnReset`·`#permanentDeleteModal`과 그 안의 id·`#iconPickerModal`·`#iconSearch`·`#iconGrid`·`#iconEmpty`·`#contentReloadNotice`·`#contentReloadNow`)를 유지한다.
- **C3 fetch·CSRF는 호출 시점에 찾는다**(1단계 C3·C4와 같음 — 공용 `api/http.ts`·`metaCsrf` 재사용). GET에는 `method`를 싣지 않는다(1단계 발견 — 요청 모양 계약). **공용 래퍼에 `PATCH`를 더한다**(v2 — 지금 `RequestOptions.method`는 `GET|POST|PUT|DELETE`뿐인데 메뉴 수정·비활성화가 PATCH다, `http.ts:22`). PATCH도 다른 쓰기처럼 CSRF 헤더·JSON 본문을 싣고, `http.test.ts`에 PATCH 경우를 추가한다(타입 단언 우회 금지).
- **C4 Bootstrap 모달 요소의 `className`·`style`·`aria-hidden`을 React가 바꾸지 않는다**(1단계 C6). 모달 두 개는 항상 마운트하고 `createPortal`로 `#wrapper` 밖 `#menuModalRoot`에 그린다.
- **C5 사용자 입력은 텍스트로만**: 메뉴명·사이트 이름·확인 문자열은 JSX 텍스트로 렌더링한다(`FrontendSourceConventionTest`). jstree가 HTML로 해석하던 메뉴명(R16)은 글자 그대로 보이게 된다 — 사이드바(Thymeleaf 이스케이프)와 같아지는 방향의 변경이다.
- **C6 동적 `import()` 금지**: 공유 청크 정책(결정 4)의 전제다. `FrontendSourceConventionTest`에 추가한다.
- **C7 서버·접근 규칙 무변경**: `src/main/java` 무변경. 번들은 `/js/admin-react/**`(이미 공개 정적 경로).
- **C8 Enter 제출은 기본 폼 동작에 맡기지 않는다**: 옛 화면의 `#menuForm`에는 submit 버튼이 없고 Enter 처리도 없다(입력란 하나짜리 폼이 아니라 Enter가 제출되지 않음). 새 폼도 `onSubmit`에서 `preventDefault`만 하고 커스텀 Enter 처리를 넣지 않는다.

## 3. 핵심 설계 결정

### 결정 1 — 트리와 드래그: React로 직접 구현 (사용자 선택)
| 선택지 | 장점 | 단점 |
|---|---|---|
| A. jstree 유지 | 동작·DOM 계약 그대로, 위험 최소 | 트리가 jQuery·CDN으로 남음 |
| **B. React 직접 구현 (채택)** | 화면 전체가 React, jstree·CDN 제거, 새 의존성 없음 | 드래그 UX 변경, 시험 도우미 교체, 구현량 증가 |
| C. dnd-kit | 키보드·터치 드래그 | 새 의존성(마지막 배포 2024-12), 중첩 트리 로직은 직접 조립 |
왜: 2026-11-22 사용자가 B를 골랐다(차이 설명 후 재질문). **드래그 입력은 포인터 이벤트로 한다**(v4 — 2라운드에서 jstree가 터치 드래그를 지원함을 확인한 뒤 사용자에게 다시 물어 결정: HTML5 드래그(터치 포기)·jstree 복귀 대신 포인터 이벤트로 터치 유지). 그래서 FAQ의 HTML5 드래그 코드는 재사용하지 않는다.

**트리 렌더링**:
```
<div id="menuTree" role="tree">
  <ul class="menu-tree-list">
    <li class="menu-node" data-menu-no="12" role="treeitem" aria-expanded="true">
      <div class="menu-node-row [menu-drop-before|after|inside]">
        <button type="button" class="menu-node-toggle" aria-label="접기">▾</button>   (자식이 있을 때만)
        <button type="button" class="menu-node-label [is-selected] [menu-inactive] [is-draggable]">
          <i class="fas fa-folder"></i> {메뉴명}
        </button>
      </div>
      <ul class="menu-tree-list">…자식…</ul>   (펼쳐진 경우만)
    </li>
```
- 모든 노드는 처음에 펼쳐진다(옛 `state.opened=true`). 접기/펼치기는 화면 상태(`collapsed: Set<menuNo>`)이고 트리 교체 때 초기화한다(옛 화면도 재초기화 때 모두 펼쳐짐).
- 선택은 클릭(라벨 버튼)으로 한다. 선택 표시 `is-selected`.
- **트리 선택과 편집 대상은 별개 상태다**(v3, 옛 화면 그대로): `treeSelection`(트리에 강조된 메뉴 번호 — 옛 jstree 선택)과 `editTarget`(상세 조회가 확정한 스냅샷 `{menuNo, menuName, useYn}` — 옛 `selectedMenuSnapshot`·`selectedMenuNo`). 클릭은 `treeSelection`을 즉시 바꾸고 상세 조회를 시작한다(응답이 세대 검사를 통과해야 `editTarget`·폼이 바뀐다). [하위 추가]는 `treeSelection`을 부모로 쓴다(상세 응답 전이어도 — 옛 `get_selected`). [새 최상위]·[하위 추가]는 `editTarget`만 비우고 `treeSelection`은 유지한다. `treeSelection`이 비워지는 곳은 옛 `deselect_all`·트리 교체와 같은 셋: 트리 교체(재조회 적용), [초기화], 사이트 전환(실패 복원 포함).
- **드롭 위치**: 드롭 대상 요소는 **행 `.menu-node-row`** 하나다(`dragover`·`drop` 판정과 시험 좌표가 같은 요소 기준 — v2). 대상 행의 세로 위치(`getBoundingClientRect` 기준)로 정한다 — 위 1/4 = 대상 **앞**(같은 부모), 아래 1/4 = 대상 **뒤**, 가운데 = 대상 **안의 마지막 자식**. 대상이 접혀 있어도 "안"은 마지막 자식이다. 허용되지 않는 위치면 표시 없음이고 놓을 때(`pointerup`)도 다시 검사해 무시한다(드래그 중과 드롭 때 같은 함수 — 옛 `check_callback`의 두 단계 교훈, `docs/troubleshooting.md`).
- **이동 계산** `moveNode`는 원본을 먼저 떼어 낸 뒤 대상 위치를 다시 찾아 넣는다(같은 부모 아래 방향 이동의 인덱스 밀림 방지 — v2). 자기 자신 앞·뒤·안 드롭과 이미 그 자리인 드롭은 트리를 바꾸지 않는다(같은 객체 반환 → 초안 건수 변화 없음). 서브트리는 통째로 옮긴다. 입력(기준·현재 트리)을 바꾸지 않는 불변 갱신이다.
- 허용 판정은 순수 함수 `canMove(tree, menuNo, newParentNo)`(R3 규칙 그대로): 새 부모 = 현재 부모면 항상 허용, 자기 자신·자손이 새 부모면 거부, `depth(newParent) + height(subtree) ≤ MAX_DEPTH`.
- **포인터 드래그 계약**(v4):
  - **포인터 하나만 소유한다**(v5): 라벨의 `pointerdown`(마우스면 왼쪽 버튼)에서 진행 중인 후보·드래그가 없을 때만 `activePointerId`를 기록한다. 끝날 때까지 다른 `pointerdown`은 무시하고, `window`의 이동·놓음·취소 이벤트도 `event.pointerId === activePointerId`일 때만 처리한다(`isPrimary`는 입력 종류별이라 마우스와 터치가 동시에 주 포인터일 수 있다 — Pointer Events Level 3 §4.1.2).
  - **포인터 캡처를 쓰지 않는다**(v5): 후보가 생기면 `window`에 `pointermove`·`pointerup`·`pointercancel`을 등록하고 끝나면 해제한다. 대상은 좌표(`elementFromPoint`)로 찾으므로 라벨 밖 이동도 받는다. `setPointerCapture`는 활성 포인터가 아니면 `NotFoundError`를 던지므로(Pointer Events Level 3 §9.2) 쓰지 않는 쪽이 단순하다. 터치 포인터는 브라우저가 묵시적으로 대상 요소에 캡처하지만 이벤트는 `window`까지 버블링되므로 같은 경로로 받는다.
  - 시작: 포인터가 **5px 넘게 움직여야** 드래그가 시작된다(그 전에 놓으면 평범한 클릭 = 선택). 시작 순간 `canDrag()`(`treeScope === 'all' && !busy`)를 다시 검사한다.
  - 드래그 가능한 라벨(`is-draggable`)에는 CSS `touch-action: none`을 둔다 — 터치로 라벨을 끌면 페이지 스크롤 대신 드래그가 된다. 라벨 밖(행 여백·접기 버튼·트리 빈 곳)을 끌면 스크롤된다. 드래그가 불가능한 상태(`treeScope !== 'all'`·`busy`)에서는 클래스를 빼 터치 스크롤을 막지 않는다.
  - 이동 중: 마지막 좌표를 기억하고 `document.elementFromPoint(clientX, clientY)`로 `#menuTree` 안의 `.menu-node-row`를 찾고(포인터 캡처와 무관하게 좌표로 찾는다) 위의 드롭 위치 규칙으로 표시를 정한다. 포인터 옆에 메뉴명을 담은 고정 위치 표시(`.menu-drag-ghost`, React가 그리는 우리 요소 — 위치는 ref로 `transform`만 바꾼다)를 띄운다.
  - 자동 스크롤: 포인터가 트리 스크롤 영역(`.tree-container`, `overflow:auto`) 위·아래 가장자리 40px 안이면 `requestAnimationFrame` 반복으로 스크롤한다(HTML5 드래그가 주던 기능을 직접 구현). **스크롤한 프레임마다 마지막 좌표로 대상 행·위치·허용 여부를 다시 계산한다**(v5 — 포인터가 멈춰 있어도 행이 움직이므로 표시와 놓는 결과가 같게). RAF는 가장자리를 벗어남·놓음·취소·트리 교체·언마운트 모두에서 `cancelAnimationFrame`하고, `window` 리스너도 같은 정리 함수 하나로 해제한다.
  - 끝: `pointerup`이면 **표시 중인 대상**(마지막 계산 결과)을 다시 검사해 초안에 반영, `pointercancel`·Escape 키·트리 교체(재조회 적용·`treeScope` 변경)·언마운트면 **취소**(초안 불변). **종료 이벤트를 놓친 경우의 복구**(v6 — 캡처가 없어 창 밖에서 놓으면 `pointerup`이 오지 않을 수 있다): `window` `blur`, `visibilitychange`(`document.hidden`), 마우스 포인터의 `pointermove`에서 `(event.buttons & 1) === 0`(버튼이 이미 떨어짐 — Pointer Events Level 3 `buttons`)도 같은 취소 함수를 부른다. 취소 함수는 상태를 `idle`로, RAF·`window` 리스너를 정리한다. 드래그가 끝난 직후의 `click`은 무시한다(놓은 자리의 라벨이 선택되지 않게).
  - 단위 시험 대상(순수 함수, `dragState.ts`): `dropPosition(rowTop, rowHeight, y)`(경계 1/4·3/4), 드래그 상태 전이 리듀서(`idle → pending(pointerId) → dragging → idle`) — 5px 임계값, 진행 중 다른 `pointerdown` 무시, 다른 `pointerId`의 이동·놓음·취소 무시(원래 드래그 유지), 마우스 이동인데 버튼이 없으면 취소(v6), 취소 뒤 `idle`·새 후보 허용.
- 드롭하면 서버를 호출하지 않고 화면의 **초안 트리**(서버 트리의 복사본)만 바꾼다. 옛 `handleMoveNode`의 "드롭 뒤 `canDrag()` 거짓이면 재조회"는 드롭 때 검사하고 무시하는 것으로 대신한다(옛 방어 경로는 jstree가 이미 이동을 끝낸 뒤라 필요했다).

**바뀌는 동작(수용·기록)**: 드래그 위치 표시 모양(jstree 표식 → 행 위·아래 선·안 강조, 포인터 옆 메뉴명 표시), 펼침 아이콘, 노드 키보드 이동(jstree의 화살표 키 탐색 없음 — 라벨이 버튼이라 Tab·Enter로 선택은 된다), 메뉴명 HTML 해석 없음(C5), 터치로 라벨을 끌면 스크롤이 아니라 드래그(스크롤은 라벨 밖에서). 터치 드래그는 유지(R21). 키보드 드래그는 옛 화면도 없었다(jstree dnd는 포인터·터치 이벤트만 처리 — 후속 후보).

### 결정 2 — 상태 구조: FAQ와 같은 `useState` + ref 동기 반영
- 1단계 구현 결과 1("`useReducer` 대신 `useState` + 같은 값을 ref에 동기 반영하는 `update()`")을 그대로 따른다. 이벤트 핸들러·비동기 흐름이 즉시 읽어야 하는 값(세대 `loadSeq`·`selectSeq`·`sitesSeq`, `busy`, `switching`, 초안 건수, `leavingAfterApply`, `queuedNotice`, `permanentDeleteTarget`)은 ref가 진실 원천이다. 늦은 응답은 세대 확인 뒤에만 상태를 바꾼다(R1).
- 순수 로직은 `frontend/src/admin/menu/` 아래 DOM 없는 모듈로 나누고 vitest로 TDD한다:
  - `tree.ts`: 서버 트리 → 화면 트리, `indexBase`(기준 구조 `menuNo → {upMenuNo, ord, parentNo, index}`, `?? null`), `flatten`(깊이 우선 `{menuNo,parentNo,index}`), `canMove`, `moveNode(tree, menuNo, target, position)`, `countDraftChanges`(LIS), `buildStructureRequest(siteId, tree, base)`.
  - `confirmText.ts`: `escapeForConfirm`(R7 규칙 그대로).
  - `menuUrl.ts`: `menuUrlError(url, site)`(R9 정규식·문구 그대로).
  - `icons.ts`: 아이콘 이름 목록·`iconNameOf`·검색 필터.
- **쓰기 성공은 헤더로 판정하고 본문을 기다리지 않는다**(v3 — 옛 화면은 `response.ok`만 보고 본문을 읽지 않는다, `menu-tab.html` 저장·비활성화·반영). 공용 래퍼에 `RequestOptions.skipSuccessBody?: boolean`을 더해, 켜면 2xx에서 본문을 읽지 않고 `{kind:'http', ok:true, body:null, bodyError:false}`를 돌려준다(실패 응답은 오류 문구 때문에 그대로 읽는다). 메뉴의 생성·수정·비활성화·반영·삭제에 켠다. FAQ 호출은 바꾸지 않는다. `http.test.ts`: 켰을 때 2xx 본문이 끝나지 않는 스트림이어도 즉시 반환, 끈 기본값은 기존과 같음, 실패 응답은 켜도 본문을 읽음.
- API: `frontend/src/api/menu.ts`(`getTree`·`getMenu`·`create`·`update`·`applyStructure`·`remove`)·`frontend/src/api/site.ts`(`list`)와 응답 타입. 화면·DOM 비의존(1단계 결정 5).

### 결정 3 — 템플릿: 마운트 지점만 남기고 인라인 스크립트 0
- `menu-tab.html`: `menuPanel` 조각은 `<style>`(트리 CSS를 jstree 규칙 → 새 클래스 규칙으로 교체) + `<div id="menuManageRoot"></div>`. `menuModals` 조각은 `<div id="menuModalRoot"></div>`. `menuScript` 조각은 `<script type="module" src="/js/admin-react/menu-manage.js">` 한 줄. 조각 세 개 구조·이름은 유지(메뉴 `CLAUDE.md`의 조각 규칙 ①②④).
- `manage.html`: jstree CSS·JS CDN 줄 삭제, `#contentReloadNotice`와 인라인 스크립트 삭제(안내와 [지금 새로고침]은 React가 `#menuTabPanel` 맨 위에 같은 id로 그린다 — `content:menu-applied` 이벤트는 같은 번들 안이라 필요 없어 없앤다). `#menuTabPanel` 래퍼는 유지(CSS 스코프).
- 서버 데이터: 없음(R20). `MAX_DEPTH`·`ADMIN_SITE_ID`는 옛 화면처럼 TS 상수로 두고 서버 상수 이름을 주석에 남긴다. 왜: 서버 모델을 새로 만들면 컨트롤러가 바뀌는데(C7), 옛 화면도 같은 복제였고 바뀐 적이 없는 값이다.
- jQuery·Bootstrap·sb-admin-2 스크립트는 남긴다(사이드바·모달·로그아웃 모달).

### 결정 4 — 공유 청크·캐시·보존 정책 (완료 기준 ③)
| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 해시 이름 공유 청크 허용 + 동적 import 금지 (채택)** | Rolldown 기본 동작 그대로, React를 한 번만 내려받음(화면 간 이동 시에도 no-store라 매번 받지만 바이트는 1벌) | 배포 교체 순간 "옛 진입점 + 새 서버" 조합이면 청크 404(아래 분석) |
| B. 진입점마다 독립 번들(공유 없음) | 진입점 = 파일 하나, 혼합 불가 | React가 화면마다 중복(각 ~220KB), 빌드를 진입점 수만큼 나눠야 함(Rolldown 다중 입력은 공유 청크를 만든다) |
| C. 고정 이름 공유 청크(`chunks/vendor.js`) | 404 없음 | 옛 진입점 + 새 청크가 **조용히 섞여** 실행된다(내보내기 이름이 압축돼 어긋나면 런타임 오류) — 404보다 나쁘다 |

**정책(A)**:
- 진입점은 고정 이름(`<name>.js`), 공유 청크는 `chunks/[name]-[hash].js`(기존 설정 그대로). 진입점은 청크를 **정적 import로만** 참조한다(C6) — 그래서 모듈 그래프 전체가 페이지를 읽을 때 한 번에 내려받아지고, 페이지가 떠 있는 동안 나중에 청크를 찾는 일이 없다.
- **보존하지 않는다**: 배포는 jar(이미지) 교체라 옛 청크는 새 서버에 없다. 한 페이지 로드가 교체 시점에 걸치면(옛 진입점 응답 완료 → 서버 교체 → 청크 요청) **단일 인스턴스에서도** 청크가 404가 된다(v2 정정 — v1의 "진입점 요청부터 실패한다"는 틀렸다. `no-store`는 한 페이지 로드의 요청들을 같은 배포 버전에 묶어 주지 않는다). 모듈 그래프 로딩이 실패하면 번들 코드가 한 줄도 실행되지 않으므로 React 안내도 뜨지 않고 본문이 빈 채로 남는다(사이드바·상단바는 서버 렌더링이라 보인다). **복구는 수동 새로고침**이다(no-store, R18 — 새로고침하면 새 진입점과 새 청크를 받는다). 페이지가 떠 있는 동안 나중에 청크를 찾는 일은 없다(C6 — 동적 import 금지). **수용**: 이 CMS는 배포하지 않고 운영 무중단 배포(여러 인스턴스 순차 교체)를 하게 되면 그때 보존 정책(이전 청크 유지)을 다시 정한다 — 방향 문서의 알려진 한계에 기록.
- 캐시: 지금은 no-store라 해시의 캐시 이점이 없다. 해시는 버전 혼합을 404로 드러내는 용도다. 장기 캐시(`immutable`)는 정적 자원 캐시 정책 전체를 다룰 때(범위 밖).
- **S3 갱신**: 산출물 = 진입점 `faq-manage.js`·`menu-manage.js` + `chunks/*.js`(1개 이상) **만**(CSS·자산·매니페스트·`.map` 없음). 진입점이 import하는 청크가 모두 서빙되는지 시험한다(F1).

출처: 공유 청크 생성·정적 import 모양은 R17 실측. Rolldown의 청크 분할 옵션 이름은 쓰지 않는다(기본 동작만 사용). no-store는 R18 실측.

### 결정 5 — 모달: Bootstrap 4 jQuery 모달 유지
- 1단계 결정 7과 같다(`<dialog>` 전환은 #298). 영구삭제 모달은 정적 백드롭·키보드 닫힘 없음 그대로, `shown`에서 입력 포커스, `hide`에서 포커스 해제(옛 코드 그대로 — aria-hidden 경고 회피). 이벤트는 마운트 effect에서 네임스페이스(`.menuReact`)로 한 번 등록·정리.
- `globals.d.ts`의 `$` 타입은 그대로 쓴다(`modal('show'|'hide')`·`on`·`off`).
- `confirm()`은 옛 화면처럼 비활성화에만 쓴다.

### 결정 6 — 반영 성공 뒤 새로고침 흐름
- 옛 흐름 그대로: `sessionStorage` 저장 → `leavingAfterApply.current = true` → 새로고침 안내 표시(`flushSync`로 DOM에 반영한 뒤) → `location.reload()`. 다시 읽은 페이지가 저장값을 읽어 첫 트리 적용 때 "메뉴 구조를 반영했습니다(N건 이동)."를 보인다. 안내가 떠 있는 동안 잠금은 풀지 않는다(옛 셸 주석 — 시간 초과로 풀지 않음).

### 결정 7 — 범위 밖(후속 후보)
- 드래그 키보드 대안(▲▼ 이동 등), 트리 화살표 키 탐색, 수정 PATCH의 `useYn` 재전송 한계(R9), 메뉴 폼 `beforeunload` 보호, 공유 청크 장기 캐시·보존(무중단 배포 시), `<dialog>`(#298).

## 4. 성공 기준
- S1 **특성 브라우저 시험**(새 `MenuStructureScreenBrowserTest`, §6 단계 2)이 옛 화면에서 먼저 통과하고(F5는 R16이 사실이면 옛 화면에서 실패가 기대값 — 실측해 기록), 전환 뒤 도우미 구현만 바꿔 전부 통과한다.
- S2 기존 `SiteMenuScreenBrowserTest` 5건 통과. 시험 수정은 셀렉터 `#menuTree .jstree-anchor` → 새 도우미(라벨 셀렉터)뿐이다(사유: 트리 구현 교체).
- S3 `build/frontend` 산출물이 `faq-manage.js`·`menu-manage.js`·`chunks/*.js`뿐이다(목록 전체 확인). jar에 셋 다 있고, 진입점이 import하는 청크가 모두 200·JavaScript로 응답한다(MockMvc). prod 이미지(`prod-smoke`)가 두 진입점을 200·JavaScript로 응답한다.
- S4 메뉴 관리 화면 HTML에 인라인 `<script>`(내용 있는 것)·`jstree`·cdnjs 참조가 없고, 모듈 스크립트는 `menu-manage.js` 하나다.
- S5 `git diff`에 `src/main/java`·`SecurityConfig`·마이그레이션 변경이 없다.
- S6 `./gradlew test -PbrowserTests=exclude` 통과(`frontendCheck` 포함), `-PbrowserTests=only` 통과(FAQ 브라우저 시험 회귀 포함 — 공유 청크로 FAQ 진입점 모양이 바뀐다).
- S7 실기(playwright): ADMIN 골든 패스 — 생성·수정·드래그 재정렬과 다른 부모로 이동·[반영]·[취소]·비활성화·영구삭제, 공개 사이트 전환, 콘솔 오류 0. MANAGER는 화면 403(무변경 확인).

## 5. 실패 모드와 고정 시험 (최대 5)
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 공유 청크가 클래스패스·jar·이미지에 없거나 경로가 어긋나 화면이 빈다(FAQ 화면도 함께 빈다) | `MenuReactPageIntegrationTest`(두 진입점에서 시작해 정적 import 지정자 `from"./…"`·`import"./…"`를 **재귀로** 따라가며 모든 모듈이 200·JS — 따라간 청크가 0개면 실패, 추출 함수의 회귀 시험 포함, v2) + `FaqReactPageIntegrationTest` 그대로 + `prod-smoke.sh`에 `menu-manage.js`와 두 진입점이 **직접** import하는 청크(1개 이상) 200·JS(v2 — 이미지의 정적 파일은 `processResources`의 같은 복사 작업이라 일부만 빠지지 않으므로 셸은 재귀하지 않는다) + 브라우저 시험 전체 |
| F2 | 드래그 허용 규칙·이동 계산이 틀린다 — 같은 부모 재정렬이 깊이 때문에 막히거나(기존 4단 메뉴), 순환·4단 이동이 초안에 들어가거나, 아래 방향 이동이 한 칸 어긋난다 | `tree.test.ts` — `canMove`(같은 부모 4단 허용, 자기·자손 거부, 깊이 경계 3 허용·4 거부, 최상위로 이동) + `moveNode`(v2: 같은 부모 위·아래 양방향 이동의 최종 트리, 자기 자신 앞·뒤·안 무동작, 인접 위치 무동작, 서브트리 보존, 옮겼다 되돌리면 초안 0건, 기준 트리 불변) + 브라우저: `cycleAndDepthViolationsAreNotDrafted`, `reorderWithinParentMakesDraft` |
| F3 | 반영 요청 모양·건수가 틀린다 — `base*` 누락(서버 400), 같은 부모 순서가 배열 순서와 어긋남, 한 칸 이동이 형제 수만큼 부풀려 셈 | `tree.test.ts`(`buildStructureRequest`의 `?? null`·깊이 우선 순서, `countDraftChanges` LIS) + 브라우저: `applySendsFullStructureAndReloadsWithSuccess`(요청 본문 가로채기·DB·성공 문구) |
| F4 | 늦은 응답·전환 중 상태가 화면을 덮는다 — 늦은 상세 응답이 다른 선택·사이트·새 메뉴 모드의 폼을 덮어 쓰기 대상이 바뀜, 사이트 전환 실패 뒤 셀렉트·편집 대상 불일치, 409 뒤 초안이 남음, 400 뒤 초안이 사라짐, 영구삭제 대상이 모달 연 뒤 바뀜 | 브라우저(v2 — 상세 `GET /admin/api/menus/{A}`를 `page.route`로 붙잡아 순서를 뒤집는다): `lateDetailDoesNotOverrideLaterSelection`(A 붙잡음 → B 선택·채워짐 → A 풀기 → 폼·모드 문구는 B, [저장]이 B로 PATCH), `lateDetailDoesNotSurviveSiteSwitch`(A 붙잡음 → 사이트 전환 완료 → A 풀기 → 폼 비어 있음·편집 모드 아님), `lateDetailDoesNotLeaveCreateMode`(A 붙잡음 → [새 최상위] → A 풀기 → 모드 문구 "새 메뉴 추가 (최상위)"·폼 비어 있음), `childCreateUsesTreeSelectionDuringPendingDetail`(v3 — A 붙잡음 → [하위 추가] → A 풀기 → 모드 문구 "새 메뉴 추가 (상위: A)" 유지 → 저장 요청 `upMenuNo=A`) + `siteSwitchFailureRevertsSelection`, `applyConflictDiscardsDraft`, `applyValidationErrorKeepsDraft`, `permanentDeleteRequiresExactEscapedName` (+ 기존 `siteSelectIsEnabledAfterSave`) |
| F5 | 사용자 입력이 HTML로 해석된다(트리 라벨·확인 문자열) | 브라우저: `menuNameIsRenderedAsText`(이름 `SMS_<b>x</b>` → 라벨 텍스트 그대로, `#menuTree b` 없음) + `FrontendSourceConventionTest`(동적 `import(` 금지 추가, 기존 HTML 해석 금지) |

## 6. 작업 단계
1. 브랜치 `feat/react-admin-menu`.
2. **특성 시험(옛 화면)**: `com.cms.admin.menu.MenuStructureScreenBrowserTest` — 도우미 `treeLabel(name)`(옛: `#menuTree .jstree-anchor` 텍스트 일치)·`moveMenu(source, target, Position.BEFORE|AFTER|INSIDE)`(옛: `$('#menuTree').jstree(true).move_node(src, parentOrTarget, pos)` — `check_callback`을 거친다). 늦은 응답 시험 3종(F4)도 이 단계에서 옛 화면으로 먼저 통과시킨다. 시험 목록(F2~F5 이름 + `deactivateSendsOnlyUseYnFalse`(요청 본문 가로채기·`confirm` 수락) + `inactiveScopeDisablesDrag`(토글 끔 → 안내 문구·드래그 불가) + `cancelRestoresServerStructure` + `iconPickerSetsIcon` + `touchPointerDragMakesDraft`(v4 — 옛 화면은 jstree 터치 이벤트 경로라 이 시험만 새 화면에서 추가한다) + `autoScrollKeepsMarkAndResultInSync`(v5 — 새 화면에서 추가: 스크롤이 생기는 긴 트리에서 포인터를 영역 아래 가장자리에 멈춰 자동 스크롤시킨 뒤 표시된 대상 행을 읽고 놓으면 그 행 기준으로 이동, 다른 드래그를 Escape로 취소한 뒤 `scrollTop`이 더 바뀌지 않음) + `windowBlurCancelsDragAndAllowsNewOne`(v6 — 새 화면에서 추가: 마우스로 드래그 시작·가장자리 자동 스크롤 중 `window`에 `blur` 발생 → 놓음 → 초안 "변경 없음"·`scrollTop` 정지 → 다시 드래그하면 초안이 된다)). 옛 화면에서 실행해 통과(F5는 실측 기록). 여기서 R16을 닫는다.
3. 빌드: `vite.config.ts`에 `menu-manage` 진입점, `FrontendSourceConventionTest`에 동적 import 금지(검사 회귀 시험 포함).
4. TDD 순수 로직: `api/menu.ts`·`api/site.ts`(필요한 만큼), `tree.ts`·`confirmText.ts`·`menuUrl.ts`·`icons.ts` + 단위 시험.
5. 컴포넌트: `main.tsx`(마운트)·`MenuManage.tsx`(상태·흐름)·`MenuTree.tsx`(트리·드래그)·`MenuForm.tsx`·`PermanentDeleteModal.tsx`·`IconPickerModal.tsx`.
6. 템플릿 교체(결정 3)·CSS 교체, 시험 도우미를 새 구현으로 교체(`treeLabel` → `#menuTree .menu-node-label`, `moveMenu` → `page.dragAndDrop(<원본 라벨>, <대상 행 .menu-node-row>, targetPosition)`(포인터 이벤트 발생은 미확인 — 리스크 절) — 좌표는 대상 행 padding box 기준 높이의 1/8·1/2·7/8, Playwright 1.65.0 `Page.DragAndDropOptions.setTargetPosition`(공식 API `page-drag-and-drop`), v2), `SiteMenuScreenBrowserTest` 셀렉터 교체, `ContentManagePageIntegrationTest` 메뉴 단언 갱신(jstree·인라인 스크립트 없음, 모듈 하나, 마운트 지점), `MenuReactPageIntegrationTest` 추가, `prod-smoke.sh`에 `menu-manage.js`.
7. 영향 범위 시험: `--tests 'com.cms.admin.menu.*' --tests 'com.cms.admin.site.*' --tests 'com.cms.admin.page.ContentManagePageIntegrationTest' --tests 'com.cms.admin.faq.*' --tests '*ConventionTest'` + `frontendCheck`.
8. 실기(S7) → 문서(메뉴 `CLAUDE.md` 화면 절, 루트 `CLAUDE.md` React 아일랜드 항목, FAQ 계획서의 S3 참조, 방향 문서 알려진 한계).

## 7. 리스크
- **드래그 UX 회귀**: jstree의 다듬어진 드롭 판정·자동 스크롤·터치 처리를 포인터 이벤트로 직접 만든다. 특성 시험이 규칙·결과를 고정하지만 "손에 잡히는 느낌"은 실기로만 본다. **실제 터치 기기 확인은 이 환경에서 할 수 없다** — 브라우저 시험 `touchPointerDragMakesDraft`는 Chromium CDP `Input.dispatchTouchEvent`(`touchStart`→`touchMove` 여러 번→`touchEnd`, Playwright Java `BrowserContext.newCDPSession(page)`)로 **실제 터치 포인터**를 주입해 라벨 밖까지 이동한 드래그가 초안이 되는지 고정한다(`hasTouch` 컨텍스트) — **미확인 → 구현 6단계에서 실측**(v5 — 합성 `PointerEvent`는 활성 포인터를 만들지 않는다). 안 되면 보고하고, 합성 이벤트로 바꾸면 "로직 경로 시험"으로 이름·한계를 구분한다.
- Playwright(1.65.0) `page.dragAndDrop`은 마우스 이동·누름·이동·놓음을 보내고 Chromium은 이에 맞춰 포인터 이벤트를 발생시킨다고 보고 있다 — **미확인 → 구현 6단계 첫 시험으로 실측**(실패하면 도우미를 `page.mouse.move/down/move(steps)/up`으로 바꾸고 보고).
- Playwright(1.65.0) `dragAndDrop`의 `targetPosition`은 **대상 요소의 padding box 기준**이다. 그래서 화면의 드롭 판정 요소와 시험의 대상 요소를 같은 행(`.menu-node-row`)으로 맞추고 행 높이의 1/8·1/2·7/8 지점을 쓴다(v2). 긴 메뉴명(줄바꿈되는 행)으로 세 위치를 한 번 확인한다(`reorderWithinParentMakesDraft`의 대상 하나를 긴 이름으로).
- 공유 청크로 FAQ 진입점의 모양이 바뀐다(React가 청크로 빠짐) — FAQ 브라우저 시험·통합 시험이 회귀를 잡는다.
- 화면 하나가 1,000줄 넘게 바뀌어 리뷰 도구의 파일 크기 상한에 걸릴 수 있다 — 컴포넌트를 나눈다(1단계는 `git add -N`으로 해결).

## 구현·검증 결과 (2026-11-23)

### Context
계획 v6대로 메뉴 편집 화면을 React 아일랜드로 옮겼다. `src/main/java`의 코드·`SecurityConfig`·스키마·API 무변경(S5), 새 의존성 없음.

### 핵심 확정 사항
- **"미확인"을 닫은 실측**: Playwright 1.65.0 `dragTo`는 포인터 이벤트를 발생시킨다. CDP `Input.dispatchTouchEvent`는 실제 터치 드래그가 된다(`touch-action`을 빼면 시험이 실패하는 것으로 확인). 옛 화면은 `SMT_<b>x</b>`를 `SMT_x`로 해석했다(R16).
- **계획과 달라진 점**:
  1. 자동 스크롤 대상: 계획은 `.tree-container`였으나 실측하니 그 상자는 스크롤되지 않는다(부모 높이가 auto라 client = scroll = 2173). 트리의 스크롤 가능한 조상과 문서를 안쪽부터 시도한다. 실제 대상은 `#content-wrapper`와 문서다.
  2. `role="tree"`·`treeitem`을 쓰지 않고 목록 + 버튼(`aria-expanded`·`aria-current`)으로 했다 — 화살표 키 탐색이 없는 트리 역할은 보조 기술에 잘못된 조작을 약속한다.
  3. 제자리 드롭에는 표시를 하지 않는다(웹 가이드 `drag-and-drop`).
  4. 첫 렌더링을 `flushSync`로 끝낸다 — 시험이 `load` 직후 `#siteSelect`를 읽는다.
  5. 네트워크 실패 문구가 브라우저 영문(`Failed to fetch`) 대신 화면의 한국어 기본 문구이고, 403은 공용 `errorMessage`의 고정 문구다.
  6. 모드 안내는 상태에서 계산한다 — 초안을 만들었다가 되돌리면 옛 화면은 초안 문구가 남았지만 이제 원래 모드 문구로 돌아온다.
- 산출물(S3): `faq-manage.js` 25.6KB·`menu-manage.js` 29.7KB·`chunks/jsx-runtime-<hash>.js` 219.9KB(gzip 8.3·9.8·68.8KB)뿐.

### 구현 파일
- 프런트엔드: `frontend/src/admin/menu/`(`main`·`MenuManage`·`MenuTree`·`MenuForm`·`MenuModals`·`tree`·`dragState`·`confirmText`·`menuUrl`·`icons`), `frontend/src/api/{menu,site}.ts`, `api/http.ts`(`PATCH`·`skipSuccessBody`), `vite.config.ts`.
- 템플릿: `menu-tab.html` 1,634줄 → 320줄(CSS·마운트 지점), `manage.html`(인라인 스크립트·CDN 제거).
- 시험: `MenuStructureScreenBrowserTest`(신규 21건)·`MenuReactPageIntegrationTest`(신규 3건)·`FrontendSourceConventionTest`(동적 import 금지)·단위 시험 32건 추가. **기존 시험 수정과 사유**: `SiteMenuScreenBrowserTest`는 셀렉터만(트리 구현 교체), `ContentManagePageIntegrationTest`는 메뉴 화면 단언 2곳(서버가 그리던 마크업·jstree가 번들로 옮겨짐).
- 스크립트·문서: `scripts/ci/prod-smoke.sh`(두 진입점 + 직접 import 청크), 루트·메뉴 `CLAUDE.md`, 인덱스.

### 검증 결과
- 옛 화면 기준선: 특성 시험 16건 중 15건 통과, `menuNameIsRenderedAsText`만 실패(기대값 — R16), 두 번 반복 같음. 새 화면: 같은 시험 + 포인터 드래그 4건 = 20건 통과, 두 번 반복 같음(코드 리뷰 뒤 1건을 더해 21건).
- 변이 실험 8종 모두 검출: 상세 세대 검사 제거(4건)·자동 스크롤 표시 재계산 제거·`blur` 취소 제거·드롭 뒤 click 무시 제거·하위 추가가 확정 대상을 부모로 씀·409에도 초안 유지·드래그 가능 조건 제거·`touch-action` 제거. 첫 실행에서 "표시 재계산 제거"를 놓쳐(놓은 결과가 표시를 그대로 써 비교가 항상 참) 포인터 아래의 실제 행과 비교하도록 보강했다.
- 영향 범위 시험(`admin.menu`·`admin.site`·`ContentManagePage`·`admin.faq`·컨벤션) 55개 클래스 545건 통과, 프런트엔드 단위 시험 64건 통과. 전체 시험은 PR 직전에 돌린다.
- 실기(playwright, bootRun 8081, dev DB): ADMIN으로 사이트 전환 → 생성 3건 → 수정 왕복 → 다른 메뉴 안으로 드래그 → 이탈 경고 → [반영](성공 문구·사이트 유지) → 접기/펼치기 → 활성 하위가 있는 비활성화 409 → 비활성화 → 영구삭제(포커스·ESC로 안 닫힘·배경막 정리) → 활성만 보기에서 드래그 없음 → FAQ 화면 정상(공유 청크). 콘솔 오류는 의도한 409 기록뿐. MANAGER는 화면·API 403. 검증 계정·메뉴·로그는 지워 dev DB 원복. 스크린샷 `docs/screenshots/menu-admin/react-menu-0{1..5}-*.png`(로컬 전용, 새 카테고리).

- **코드 리뷰**(code-review-loop): codex `review` 1라운드 지적 0 → `adversarial-review` 2라운드 — 1건(수용: 드래그 중 포인터를 멈춘 채 휠로 스크롤하면 낡은 자리에 놓임 → 실패 시험 `manualScrollKeepsMarkUnderPointer`로 재현한 뒤 캡처 단계 `scroll` 리스너로 수정), 다음 라운드 approve·지적 0.

### 이슈
- Playwright가 정규식 원문을 JS로 넘겨 `Pattern.quote`(`\Q…\E`)가 일치하지 않았다 — 시험 도우미에서 직접 이스케이프.
- Bash heredoc이 역슬래시를 줄여 `prod-smoke.sh`의 `tr -d '\r'`에 실제 CR이 들어갔다 — `git ls-files --eol`로 발견해 고쳤다.

### 한계
- 실제 터치 기기는 확인하지 못했다(CDP 주입까지). `prod-smoke.sh` 변경은 구문 검사와 로컬 번들의 추출만 확인했고 실제 실행은 PR CI가 한다.

### 후속
- #302 메뉴 트리 드래그의 키보드 대안과 이동 안내(deferred)
- #303 메뉴 편집 화면 표시 결함(제목 색·스위치 겹침 — 기존 CSS)

## 개정 이력
- v1 (2026-11-22): 최초 작성.
- v2 변경 (R1 codex 리뷰 6건 — 수용 5, 일부 수용 1):
  - #1 수용: 공용 `http.ts`에 `PATCH` 추가 + 단위 시험(C3). 이유: 메뉴 수정·비활성화가 PATCH인데 래퍼 계약에 없다.
  - #2 수용: F4에 늦은 상세 응답 시험 3종(A→B 선택·상세→사이트 전환·상세→새 메뉴) 추가, 옛 화면에서 먼저 통과. 이유: 세대 검사가 핵심 방어인데 실패 응답 시험으로는 순서 보존이 고정되지 않는다.
  - #3 수용: `moveNode` 계산 규칙(떼어 낸 뒤 삽입·무동작·불변)과 단위 시험 경계 6종(F2). 이유: 새로 짜는 이동 코드의 고전적 인덱스 오류.
  - #4 일부 수용: 통합 시험은 두 진입점부터 정적 import를 재귀로 따라가고 0개면 실패(검사 회귀 시험 포함). **기각**: `prod-smoke.sh` 재귀 — 이미지의 정적 파일은 같은 `processResources` 복사라 일부만 빠지지 않는다. 셸은 진입점 + 직접 import 청크만 본다.
  - #5 수용: 결정 4 정정 — 단일 인스턴스에서도 청크 404 가능, 번들 실행 전 실패라 안내 없음, 수동 새로고침 복구.
  - #6 수용: 드롭 판정·시험 대상 요소를 행 `.menu-node-row`로 통일, Playwright 1.65.0·좌표 기준 명시, 긴 메뉴명 1회 확인.
- v3 변경 (R2 codex 리뷰 3건 — 수용 2, 결정 필요 1):
  - #1 수용: 래퍼 `skipSuccessBody` 옵션 — 메뉴 쓰기는 2xx 헤더로 성공 판정, 본문 대기 없음(옛 화면과 같음) + 단위 시험 3종. 이유: 성공 본문이 멈추면 저장이 끝났는데 화면이 잠긴다.
  - #2 수용: `treeSelection`(트리 강조)과 `editTarget`(확정 스냅샷) 분리, 비워지는 지점 3곳 명시, [하위 추가] 늦은 응답 시험 추가. 이유: 옛 [하위 추가]는 jstree 선택을 읽어 상세 응답 전에도 동작한다.
  - #3 결정 필요 → 사용자 결정: jstree 3.3.12가 터치 드래그를 지원함을 CDN 파일로 확인(R21). v2의 "옛 화면도 터치 없음"은 틀렸다.
- v4 변경 (사용자 결정 반영): 드래그 입력을 HTML5 드래그 → **포인터 이벤트**(마우스·터치·펜)로. 포인터 드래그 계약(5px 임계값·포인터 캡처·`elementFromPoint` 판정·`touch-action`·자동 스크롤·취소 조건·드래그 뒤 클릭 무시), 순수 함수 단위 시험, 터치 경로 브라우저 시험, Playwright 포인터 이벤트 발생 미확인 표시, 실제 기기 확인 불가 한계. 이유: 옛 화면의 터치 드래그를 후퇴시키지 않는다.
- v5 변경 (R3 codex 리뷰 3건 — 모두 수용):
  - #1 수용(단순화): 포인터 캡처를 쓰지 않고 `window` 리스너 + 좌표 판정으로 라벨 밖 이동을 받는다(`NotFoundError` 경로 제거, `lostpointercapture` 취소 조건 삭제). 터치 시험은 CDP `Input.dispatchTouchEvent`로 실제 터치 포인터 주입(미확인 → 실측). 이유: 합성 `PointerEvent`는 활성 포인터가 아니다.
  - #2 수용: `activePointerId` 소유·다른 ID 무시를 계약과 순수 리듀서 시험으로. 이유: `isPrimary`는 입력 종류별이다.
  - #3 수용: 자동 스크롤 프레임마다 대상 재계산, 놓을 때 표시 중인 대상 사용, 모든 종료 경로·언마운트에서 RAF·리스너 정리, 브라우저 시험 1건. 이유: 포인터가 멈춰도 행이 움직여 표시와 결과가 어긋난다.
- v6 변경 (R4 codex 리뷰 1건 — 수용): 종료 이벤트 유실 복구 — `blur`·`visibilitychange`(숨김)·버튼 없는 마우스 이동을 공통 취소로, 리듀서 시험·브라우저 시험 1건. 이유: 캡처 없이 창 밖에서 놓으면 `pointerup`을 못 받아 드래그·자동 스크롤이 남는다.
- R5 (2026-11-22): codex 리뷰 ship — 새 지적 없음.
