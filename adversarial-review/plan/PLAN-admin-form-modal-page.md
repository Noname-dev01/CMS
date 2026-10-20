# PLAN — 관리 화면 등록·수정 폼을 모달로 전환 (2차: 정적 페이지)

> 상태: v3 — 적대적 리뷰 3라운드 **ship**(codex gpt-6.1-sol, 1라운드 P2×1·2라운드 P2×1 수용, 3라운드 새 지적 없음). `PLAN-admin-form-modal.md`(1차: 배너·팝업·게시판 정의·사이트 설정, #155~#158 머지 완료)의 후속이다. 1차의 공통 규약 1~13과 확정 결정(static 모달, 저장 후 닫기, 연속 등록은 [새 …] 재클릭)을 **그대로 따르고**, 이 문서는 정적 페이지에만 있는 차이를 다룬다.

## 개정 이력

- 3라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. 409 분기에서 `loadList()`를 `await`하지 않는 설계가 늦은 `finally` 경합을 없애고, 늦은 목록 응답의 `lockUi()`가 현재 `saving`으로 잠금을 계산함을 확인(`page-tab.html:385,257,208`).
- v3 변경(2라운드 — codex gpt-6.1-sol, P2×1, 결정 필요 0건):
  - R2-1(P2 이전 409 요청의 늦은 `finally`가 새 요청의 잠금을 해제): 설계 6 변경 + 시험 추가 — v2처럼 409 분기에서 잠금을 풀고도 `await loadList()`와 `finally`를 유지하면, A의 목록 GET이 지연되는 동안 사용자가 닫고 다시 열어 B를 시작한 뒤 A의 `finally`가 B의 잠금을 푼다(PUT `:470`, DELETE `:501`의 무조건 해제). **409 분기는 `loadList()`를 `await`하지 않고 호출한다**(`loadList`는 내부에서 예외를 처리하고 폼을 건드리지 않는다) — 핸들러의 `finally`가 곧바로 끝나 늦은 해제가 없다. 성공 경로는 `saving`이 끝날 때까지 새 요청이 시작될 수 없어(`startEdit`이 `saving`이면 반환) 같은 경합이 없다. 요청별 토큰은 쓰지 않는다(더 단순한 쪽).
- v2 변경(1라운드 — codex gpt-6.1-sol, P2×1, 결정 필요 0건):
  - R1-1(P2 409 뒤 목록 재조회가 지연되면 모달을 닫을 수 없음): 설계 6 변경 + 시험 추가 — 현재 PUT·DELETE는 `await loadList()`가 끝나야 `finally`에서 `setSaving(false)`를 해 409 응답이 끝나도 후속 GET이 지연되는 동안 ×·[닫기]와 입력이 잠긴다(`page-tab.html:460,494`). **409 분기는 `setSaving(false)`를 목록 재조회보다 먼저 한다**(재조회는 잠금 없이 이어 간다). 후속 목록 GET을 붙잡은 상태에서 입력 보존·닫기 확인·닫기가 되는지 PUT·DELETE 각각 시험.

## Context

정적 페이지 관리(`admin/page/manage.html` + `fragments/page-tab.html`)는 목록(`col-lg-5`) 옆에 폼(`col-lg-7`: 슬러그·제목·Quill 본문·표시 대상·노출·푸터)과 개정 이력이 인라인으로 있다. 이를 1차와 같은 모달로 옮긴다. **API·권한·DB·`SecurityConfig`는 바꾸지 않는다.**

### 확정 결정 (사용자, 2026-10-20)

| 항목 | 결정 |
|---|---|
| 저장 후 | 모달을 닫는다. 개정 이력은 목록에서 행을 다시 열어 확인한다 |
| PR | 정적 페이지 하나의 PR. FAQ(3차)는 별도 계획·PR |

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 정적 페이지 화면은 **미저장 판정 `isDirty()`가 이미 있다**(신규·수정 모드별, Quill `editor.isChanged()` 포함)와 `confirmDiscard()`, 그리고 `beforeunload`(`isDirty() \|\| saving`) | `page-tab.html` isDirty·confirmDiscard·beforeunload |
| 수정은 행 클릭 → 상세 GET(`startEdit`) → `applyDetail`. 상세의 `version`이 PUT·DELETE에 필수(낡은 화면 409). 목록(요약)은 `version`을 쓰지 않는다 | `startEdit`, `pageForm` submit, `btnDelete` |
| PUT 409면 입력을 지우지 않고 "복사해 두고 목록에서 다시 선택" 안내 후 목록 재조회 | submit 핸들러의 409 분기 |
| 개정 이력(`#revisionGroup`)은 폼 아래에 있고 `editing.revisions`로 그린다. 공개 주소 링크는 `publicPrefix(siteId)` | `renderRevisions`, `applyDetail` |
| 본문 편집기 `NoticeEditor`는 이미지 불허, 페이지 로드 때 생성된다 | `NoticeEditor.create(..., {allowImages:false})` |
| 화면 셸이 위임 권한 `MY_PERMISSIONS`를 선언하고, MANAGER는 `SITE_PAGE` 조회 권한이 있으면 들어온다. 행 클릭은 `canUpdate`를 보지 않는다 | `manage.html`, `startEdit` |
| 이탈 경고 시험이 폼 입력을 직접 채운다 | `PageScreenLeaveGuardBrowserTest` |
| 슬러그·개정 링크 시험이 행을 연속 클릭한다 | `ContentScopeAdminBrowserTest`의 정적 페이지 시험 |

## 설계 (1차 규약 적용 + 정적 페이지 고유 처리)

1. **레이아웃·모달 껍데기**: 목록 `col-12`, 헤더에 [새 페이지](`canCreate`일 때만 보임). 모달 `admin-detail-modal modal-lg`, `data-backdrop="static" data-keyboard="false"`. 모달 마크업은 `pageModal` 조각으로 분리해 화면 셸이 컨테이너 밖에 포함한다(사이트 설정과 같은 이유). 푸터: [삭제](mr-auto) · [닫기] · [등록/저장](submit, `form="pageForm"`). 개정 이력은 모달 본문 폼 아래. `admin-manage.css`를 셸에서 불러온다.
2. **ID 유지**: `pageForm`, `pageSlug`, `pageTitle`, `pageContentEditor`, `pageSiteScope`, `pageUse`, `pageFooter`, `pageBtnSave`, `btnNew`, `btnDelete`, `formTitle`(모달 제목), `slugLink`, `revisionGroup/Summary/List`. 새 ID: `pageModal`, `modalAlert`, `btnModalClose`, `btnModalCloseX`. 셸·조각 합쳐 중복 id가 없어야 한다(`ContentManagePageIntegrationTest`가 검사).
3. **상태기계**: `modalState`(closed→opening→open→closing→closed), `pendingClose`, `forceClose`와 `closeModal()`/`openModal()`. 상태는 `data-state`에도 남긴다(IIFE 클로저라 변수를 밖에서 읽을 수 없다 — 사이트 설정과 같다). 새로 여는 동작은 `modalState === 'closed'`일 때만.
4. **수정 모달은 연 직후 상세를 읽는다**(팝업 방식): 모달을 열고 `loadingDetail`로 입력·저장·삭제를 잠근 뒤 GET. 실패(404·403·네트워크)는 `showPageError` + `closeModal()`(`pendingClose` 경유), 404면 목록 재조회. 닫힘(`hidden`) 처리에서 **요청 세대 증가 + `loadingDetail = false`**를 함께 해 늦은 응답이 새 폼을 덮지 못하고 다음 진입이 막히지 않게 한다(1차 R2-1).
5. **미저장 닫기 확인(사이트 설정 방식)**: `hide.bs.modal`을 가로채 `!forceClose && isDirty()`이면 확인(취소 시 `preventDefault`). 저장·삭제 성공 뒤의 프로그램 닫기는 `forceClose`로 건너뛴다. 상세 로딩 중(`editing === null`, 입력은 기본값)에는 `isDirty()`가 거짓이라 확인 없이 닫을 수 있다. `beforeunload`는 그대로(`isDirty() || saving`) — 닫힘 처리에서 폼을 기본값으로 되돌리므로 닫힌 화면에서는 거짓이다.
6. **409(낡은 화면) — 모달 유지·입력 보존**: PUT·DELETE 409는 **모달을 닫지 않고** `modalAlert`에 안내를 띄우며 입력은 그대로 둔다(저장 후 닫기의 예외). 안내 문구는 모달 맥락으로 바꾼다("닫은 뒤 목록에서 이 페이지를 다시 선택해 최신 내용을 불러오세요"). 그 뒤 목록 재조회(`loadList`)는 폼에 간섭하지 않는다(이 화면의 `loadList`는 목록만 다시 그린다). **409 분기는 잠금을 먼저 풀고(`finally`가 곧바로 끝나게 `loadList()`를 `await`하지 않고 호출한다) 목록을 재조회한다** — 기존 코드는 `await loadList()`가 끝나야 `finally`에서 잠금을 풀어, 재조회가 지연되면 안내를 본 뒤에도 ×·[닫기]·입력이 잠긴다. 잠금이 풀려 있으면 사용자는 [닫기]로 나갈 수 있다(`isDirty()`면 확인). 성공 경로(저장·삭제)는 모달이 닫히므로 재조회 동안 잠금이 남아도 무방하다.
7. **저장·삭제 성공**: 저장 성공 → 모달 닫기 + 페이지 상단 `pageSuccess`(개정 번호) + 목록 재조회. 삭제 성공·404("이미 삭제됨") → 모달 닫기 + 안내 + 재조회. 기존 `applyDetail(saved)`(저장 직후 폼 유지)는 쓰지 않는다. 목록이 `version`을 쥐지 않고 수정 모달이 열 때마다 상세를 새로 읽으므로 1차 규약 13(저장 응답으로 목록 보정)은 해당 없다.
8. **오류 위치**: 모달이 열리고 있거나 열린 동안(`opening`/`open`)은 `modalAlert`, 그 밖은 `pageAlert`. 편집기 `onError`도 같은 경로.
9. **권한**: 행 클릭(진입)은 `canUpdate`를 요구하지 않는다. 저장은 `editing ? canUpdate : canCreate`로 비활성, [삭제]는 `canDelete`, [새 페이지]는 `canCreate`.
10. **닫기 버튼**: 저장 중(`saving`)에는 ×·[닫기] 비활성(상세 로딩 중에는 닫을 수 있다).
11. **Quill**: 모달 안(초기 숨김)에서 생성해도 열린 뒤 `setHtml` 값이 보이는지는 팝업에서 이미 확인된 구조다. `editor.quill.enable(!busy)` 잠금은 그대로.

## 영향 범위

| 구분 | 변경 |
|---|---|
| 서버 코드·API·권한·DB | 없음 |
| 템플릿 | `admin/page/manage.html`, `admin/page/fragments/page-tab.html` |
| 시험 | `PageScreenLeaveGuardBrowserTest`(모달을 먼저 열고 입력), `ContentScopeAdminBrowserTest`의 정적 페이지 시험(행 사이에 모달 닫기), `ContentManagePageIntegrationTest`(수정 필요 여부 확인), 신규 `PageModalBrowserTest` |
| 문서 | `com.cms.admin.page`의 CLAUDE.md가 인라인 폼 구조를 전제하면 갱신 |

## 시험 계획

- 기존: 위 표의 두 클래스를 모달 흐름으로 수정(시험 의도 유지). 닫힘 판정은 `modalState`/`data-state`로 한다 — 요소의 "숨김" 대기는 열림 초기(`display:none`)에 거짓으로 통과한다.
- 신규 `PageModalBrowserTest`: ① 배경 클릭·ESC로 닫히지 않음 ② 슬러그·제목을 비운 저장이 브라우저 검증으로 막힘(첫 무효 입력 포커스) ③ 등록 → 모달 닫힘·목록·개정 1 ④ 미저장 닫기 확인의 취소(유지)·확인(닫힘, 저장 안 됨) ⑤ **409: 모달 유지·입력 보존·안내, 닫고 다시 열어 최신 내용으로 저장 성공** ⑥ 상세 즉시 404 → 모달이 결국 닫히고 안내 ⑦ 상세 조회 중 닫아도 다음 행이 열리고 늦은 응답이 새 폼을 덮지 않음 ⑧ 삭제 → 닫힘·목록에서 사라짐 ⑨ READ 전용·READ+DELETE MANAGER의 버튼 상태. ⑩ **PUT 409 뒤 후속 목록 GET을 붙잡아도 ×·[닫기]가 활성이고 입력이 남으며, 닫기 확인을 거쳐 닫힌다** ⑪ **DELETE 409도 같다**(삭제 요청의 409 뒤 목록 GET 보류). ⑫ **경합: A(409)의 목록 GET 보류 → 닫고 다시 열기 → B(PUT 또는 DELETE) 보류 → A의 GET 해제 → B가 끝날 때까지 입력·저장·닫기 잠금이 유지된다**(PUT·DELETE 각각).
- 변이 실험: static·form 연결, 미저장 확인, 저장 중 닫기 비활성, 409 분기의 모달 유지, `loadingDetail` 해제, `pendingClose` 각각을 제거해 시험이 실패하는지 확인.

## 이번 범위에서 하지 않는 것

FAQ(3차), 서버 API·권한 변경, 개정 이력 화면 개편, 공통 모달 추상화.
