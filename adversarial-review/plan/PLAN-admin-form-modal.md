# PLAN — 관리 화면 등록·수정 폼을 모달로 전환 (1차: 배너·팝업·게시판 정의·사이트 설정)

> 상태: v4 — 적대적 리뷰 4라운드 **ship**(codex gpt-6.1-sol, 1라운드 P2×5·2라운드 P2×1·3라운드 P2×2 전부 수용, 4라운드 새 지적 없음). 구현 착수는 사용자 승인 필요. 사용자 결정 6건 모두 확정(아래 "확정 결정"). 구현 전 사용자 승인 필요.

## 개정 이력

- 4라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. 사이트 설정이 ADMIN 전용(`SecurityConfig.java:124`, `SiteController.java:38`)이고 배너 절이 현행 상태 구조와 맞음을 확인. 남은 확인은 구현 단계의 브라우저 검증(Quill 숨김 상태 초기화, 중첩 모달).
- v4 변경(3라운드 — codex gpt-6.1-sol, P2×2, 결정 필요 0건):
  - R3-1(P2 게시판 정의 권한 조합 시험은 보안 계약과 충돌): 공통 규약 9·시험 계획 변경 — 게시판 정의 화면·API는 **ADMIN 전용**(`BoardController.java:42`, `AdminFeature.java:45`)이고 MANAGER는 게시판 READ가 있어도 403(`BoardPermissionIntegrationTest.java:132`)이다. 권한 조합 시험은 **배너·팝업에만** 적용하고, 게시판 정의는 "ADMIN의 모달 CRUD + MANAGER 접근 거부 유지"로 명시한다. 이 계획은 권한·인가를 바꾸지 않는다.
  - R3-2(P2 게시판의 404 분기가 모달에서 정의되지 않음): 게시판 절 변경 — 기존 코드는 PATCH 404·DELETE 404 뒤 `startCreate()`로 폼을 초기화한다(`board/manage.html:332,259,365`). 모달에서는 **PATCH 404 → 모달을 닫고 `pageAlert` 안내 + 목록 재조회**(보존할 대상 게시판이 없다), **DELETE 404 → 규약 7의 "이미 삭제됨" 흡수와 동일(모달 닫기·성공 안내·재조회)**로 정한다. 어떤 실패 경로에서도 같은 모달이 생성 모드로 전환되지 않는다. 시험 추가.
- v3 변경(2라운드 — codex gpt-6.1-sol, P2×1, 결정 필요 0건):
  - R2-1(P2 팝업 상세 조회 중 닫으면 `loadingDetail`이 영구 잠김): 팝업 절 변경 — `startEdit()`의 `finally`는 세대가 일치할 때만 `loadingDetail`을 해제하므로(`popup/manage.html:566`), 닫힘 처리에서 세대 무효화만 하면 이후 `startEdit()`이 즉시 반환한다. `hidden.bs.modal`(및 `closeModal()`)에서 **세대 증가 + `loadingDetail = false` + `lockUi()`/`render()` 호출**을 함께 수행한다. 시험을 "닫은 뒤 다른 행을 열어 새 상세 조회·편집이 가능하다"까지 확장.
- v2 변경(1라운드 — codex gpt-6.1-sol, P2×5, 결정 필요 0건):
  - R1-1(P2 사이트 설정에 재사용할 submit 핸들러가 없음): 공통 규약 4 변경 + 사이트 절 변경 — 사이트는 저장이 `click` 핸들러이고 키·이름에 `required`가 없다(`site-tab.html:62,83,321`). 저장 로직을 `form`의 `submit`으로 옮기고 `preventDefault()`·`required`를 추가한다. Enter 제출 시험 추가.
  - R1-2(P2 UPDATE 권한으로 모달 진입을 막으면 READ/DELETE 퇴행): 공통 규약 9 변경 — 진입(행 클릭)은 `canUpdate`를 요구하지 않고, 저장·삭제·[새 …]는 각각 자기 권한만 본다. 권한 조합 시험 추가.
  - R1-3(P2 backdrop은 이미 시작된 목록 요청을 막지 못함): 공통 규약 11 신설, 배너 절의 "모달이 열린 동안 목록 조회 경로 없음" 문장 철회 — 목록 응답이 열린 폼을 `startCreate()`로 초기화하지 않는다.
  - R1-4(P2 Bootstrap 4는 전환 중 `hide()`를 무시): 공통 규약 12 신설 — 닫기는 `closeModal()` 헬퍼를 거치고, 아직 `shown`이 아니면 `shown.bs.modal`에서 닫는다. 팝업 즉시 실패 시험 추가.
  - R1-5(P2 저장 후 재조회 실패 시 낡은 version 재진입): 공통 규약 13 신설 — 저장 응답으로 목록 스냅샷을 먼저 보정한다(사이트의 기존 코드와 같은 방식).

## Context

게시글 관리(`board/posts.html`)는 목록 + 모달(`postDetailModal`)로 등록·수정하지만, 배너·팝업·게시판 정의·사이트 설정은 목록 옆(`col-lg-5`)에 인라인 폼이 고정돼 있다. 일관성과 목록 영역 확보를 위해 폼을 모달로 옮긴다. **API·권한·DB·`SecurityConfig`는 바꾸지 않는다** — 화면(템플릿·JS)과 그 화면을 건드리는 시험만 바뀐다.

### 확정 결정 (사용자, 2026-10-20)

| 항목 | 결정 |
|---|---|
| 대상 | 권한 관리 **제외**. 1차 = 배너·팝업·게시판 정의·사이트 설정. 2차 = 정적 페이지, 3차 = FAQ(각각 별도 PLAN·PR) |
| 저장 성공 후 | 모달을 닫는다(게시글 관리와 동일). 확인은 목록에서 다시 연다 |
| PR 단위 | 화면마다 별도 PR (배너 → 팝업 → 게시판 → 사이트 설정 권장 순서) |
| 닫기 정책 | `static` — ×/[닫기]만 허용, 저장 중에는 그것도 비활성 |
| 사이트 [초기화] | [닫기]로 대체 |
| 연속 등록 | 저장 후 닫기 유지, [새 …]를 다시 누른다 |

FAQ의 "409 시 모달 유지·입력 보존"은 3차 FAQ 계획에서 다룬다. 1차는 영향 없음.

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 기준 모달은 Bootstrap 4.6(`data-dismiss`, `$(el).modal("show"/"hide")`)이고 `admin-detail-modal` 클래스 + `admin-manage.css`를 쓴다. 게시글 모달은 backdrop·keyboard 옵션이 기본값이라 **배경 클릭·ESC로 닫히고, 닫을 때 확인이 없다** | `board/posts.html:234`, `grep backdrop` 결과 없음 |
| `admin-manage.css`는 `posts.html`이 직접 `<link>`로 로드한다. 배너·팝업·게시판·사이트 화면은 로드하지 않는다 | `posts.html:7`, `banner/manage.html` 확인 |
| 모달 안의 `<form>`과 푸터 버튼이 분리돼 있어 게시글 화면은 푸터 `btnSave`를 JS 클릭으로 처리한다. 우리 화면들은 **브라우저 기본 검증(`required`, `maxlength`)에 의존**한다(특히 팝업은 숨겨진 invalid 입력이 등록을 막던 결함을 비활성화로 해결한 이력) | `popup/manage.html:407-424` 주석, `PopupAdminFormBrowserTest` |
| 미디어 선택 모달(`media-picker.js`)은 이미 "폼 모달 위 중첩"을 전제로 닫힐 때 `body.modal-open`을 복구한다 | `media-picker.js:37-41`, `MediaPickerNestedModalBrowserTest` |
| 배너·팝업·게시판은 폼 미저장 판정이 없다(`beforeunload`는 배너의 순서 초안만). 사이트 설정만 `baseline`/`isDirty()`와 이탈 확인이 있다 | 각 파일 grep |
| 배너는 순서 초안(`orderDraft`)이 있는 동안 편집 시작·저장·삭제가 잠긴다 | `banner/manage.html:336-349, 435` |
| 팝업 수정은 상세를 별도 GET으로 읽는다(`loadingDetail`, `detailRequestSeq`) | `popup/manage.html:511-572` |
| 사이트 설정 본문은 `site/fragments/site-tab.html` fragment이고 `site/manage.html`이 포함한다. 버튼은 저장·기본 지정·삭제·초기화 | `site-tab.html:59-118` |
| 이 화면들의 DOM id에 의존하는 Playwright 시험이 있다 | `PopupAdminFormBrowserTest`, `ContentScopeAdminBrowserTest`(`#bannerSiteScope option:checked`), `BoardScopeAdminBrowserTest`, `SiteDefaultBrowserTest`, `SiteMenuScreenBrowserTest`(`/admin/site/manage` 부분) |

## 공통 설계 (네 화면 공통 규약)

각 화면은 **자기 파일 안에서 완결**한다. 공통 fragment·JS 헬퍼는 만들지 않는다(요청 범위 밖 추상화 금지, CLAUDE.md 작업 방식 3). 네 화면이 같은 규약을 따르는지는 리뷰에서 확인한다.

1. **레이아웃**: 목록이 `col-12` 전체 폭. 목록 카드 헤더에 [새 배너 / 새 팝업 / 새 게시판 / 새 공개 사이트] 버튼(생성 권한이 없으면 숨김 — 지금 `btnSave` 비활성화 규칙을 그대로 적용). 행 클릭 = 수정 모달 열기.
2. **모달 껍데기**: `modal fade admin-detail-modal` + `modal-dialog modal-lg modal-dialog-centered`(사이트·게시판은 기본 폭도 가능). 헤더 제목은 생성/수정에 따라 JS가 바꾼다(지금 `formTitle`을 재사용 → id 유지).
3. **닫기 정책**: `data-backdrop="static"`, `data-keyboard="false"`. 닫기 수단은 헤더 ×·푸터 [닫기] 두 곳뿐이다. **저장 중(`saving`)에는 두 버튼도 비활성화**한다. 게시글 모달보다 엄격하게 하는 이유: 인라인 폼과 달리 배경 클릭 한 번으로 입력이 사라지는 새 위험이 생기기 때문이다(결정 필요 1 참조).
4. **폼 제출**: 푸터 [등록/저장] 버튼을 `type="submit" form="<폼 id>"`로 두어 **브라우저 기본 검증을 유지**한다(JS 클릭 우회 금지). 배너·팝업·게시판은 기존 `submit` 핸들러를 그대로 쓴다. **사이트 설정은 예외** — 저장이 버튼 `click` 핸들러이고 키·이름에 `required`가 없으므로(`site-tab.html:62,83,321`), 저장 로직을 `form`의 `submit` 핸들러로 옮기고 `preventDefault()`와 `required`를 추가한다(`click`을 남긴 채 버튼만 submit으로 바꾸면 검증 전에 요청이 나가거나 기본 폼 제출이 발생한다).
5. **알림 위치**: 모달이 열려 있는 동안 페이지 상단 `pageAlert`는 backdrop 뒤에 가려진다. 모달 본문 맨 위에 `modalAlert`를 두고, 저장·삭제·상세 조회 실패는 모달 안에 보인다. **성공 메시지**는 모달을 닫은 뒤 페이지 상단 `pageSuccess`에 표시한다. 모달 밖(목록 조회·순서 저장) 실패는 기존대로 `pageAlert`.
6. **상태 초기화**: `hidden.bs.modal`에서 `editing`, `editBase`, 진행 중 요청 세대(`detailRequestSeq` 등)를 무효화하고 모달 알림을 지운다(게시글 모달의 `hidden.bs.modal` 패턴). 모달을 열 때마다 폼을 처음부터 채운다.
7. **삭제**: [삭제]는 수정 모달 푸터(`d-none` 토글). `confirm()` 문구·`version` 처리·404 흡수는 그대로. 성공하면 모달을 닫고 목록을 다시 읽는다.
8. **CSS**: `admin-manage.css`를 `<link>`로 추가하고(게시글 화면과 동일), 화면 전용 스타일은 지금처럼 `<style>`에 둔다. 모달 CSS 클래스(`admin-detail-modal`)는 `admin-manage.css`에 이미 있으므로 새 규칙을 만들지 않는다.
9. **요청·권한·API**: 엔드포인트, 메서드, 요청 본문, `version`/`revision` 규칙, `MY_PERMISSIONS` 판정은 **전혀 바꾸지 않는다**. 권한 가드는 **작업별로 분리**한다: 행 클릭(수정 모달 진입)은 `canUpdate`를 요구하지 않는다(현재도 `startEdit`은 `saving`/`orderDraft`만 본다 — READ 전용 사용자는 상세를 볼 수 있고, READ+DELETE 사용자는 푸터 [삭제]에 도달해야 한다). [새 …] 버튼은 `canCreate`, 저장 버튼은 생성/수정 모드에 따라 `canCreate`/`canUpdate`, [삭제]는 `canDelete`만 본다. READ 전용이면 입력은 보이되 저장 버튼이 비활성이다(현재 `btnSave.disabled` 규칙 유지). 이 분리는 기능 단위 위임(배너·팝업)에 해당하며, **게시판 정의와 사이트 설정은 ADMIN 전용**이라 권한 조합이 없다.
11. **목록 응답은 열린 폼에 간섭하지 않는다**: backdrop은 모달이 열리기 전에 이미 시작된 목록 요청(예: [새로고침] 직후 행 클릭)을 막지 못한다. 지금 배너·팝업의 `loadList()`는 편집 대상이 목록에서 사라졌으면 `startCreate()`로 폼을 비운다(`banner/manage.html:475`, `popup/manage.html:590`). 모달에서는 이를 **폼 초기화 대신 `modalAlert` 안내**("이 항목이 삭제되었거나 보이지 않습니다. 저장하면 실패할 수 있습니다")로 바꾸고 입력은 그대로 둔다. 저장하면 서버가 404/409로 답하는 기존 처리(`errorMessage`, 삭제의 404 흡수)가 마무리한다. 모달이 닫혀 있을 때의 `loadList()` 동작은 그대로다.
12. **닫기는 `closeModal()`을 거친다**: Bootstrap 4.6은 열림·닫힘 전환 중의 `hide()`를 무시한다(`bootstrap.bundle.js:4794`). 상세 GET이 열림 애니메이션 도중 404·403으로 빨리 실패하면 `.modal("hide")`가 무시되어 모달이 남고 오류가 가려진 `pageAlert`에 표시된다. 각 화면은 `closeModal()` 헬퍼를 두어 아직 `shown.bs.modal` 전이면 플래그를 세우고 `shown` 이벤트에서 닫는다. 오류는 모달이 닫힌 뒤 `pageAlert`에, 모달이 유지되는 오류는 `modalAlert`에 보인다.
13. **저장 응답으로 목록 스냅샷을 먼저 보정한다**: 저장 성공 후 `loadList()`가 실패하면 기존 목록 객체(저장 전 `version`)가 남아, 닫았다 다시 연 행이 낡은 값으로 편집된다. 저장 응답(`saved`)이 목록의 해당 항목과 같은 형태인 화면(배너·게시판·사이트)은 재조회 전에 목록 배열의 해당 항목을 `saved`로 교체(등록이면 추가)한 뒤 `loadList()`를 호출한다 — 사이트가 이미 하는 방식이다(`site-tab.html:342`). 팝업은 수정 모달을 열 때마다 상세 GET을 새로 하므로 해당 없음.
10. **DOM id 유지**: 기존 입력·버튼 id(`bannerTitle`, `bannerSiteScope`, `btnSave`, `btnDelete`, `formTitle` …)는 그대로 둔다. 시험 수정량이 줄고, 코드 변경이 "컨테이너 이동"으로 한정된다.

## 화면별 설계

### 1. 배너 관리 (`banner/manage.html`)

- 인라인 폼 카드(`col-lg-5`)를 모달로 옮기고 목록 카드를 `col-12`로 넓힌다.
- `startCreate()`/`startEdit()` = "폼 채우기 + 모달 열기". `startCreate()`는 초기 호출(페이지 로드)에서 빼고 [새 배너] 클릭에서만 호출한다. 지금 `startCreate()`는 모드 전환·강조 해제도 하므로 **"폼 초기화"와 "모달 열기"를 분리**한다.
- 순서 초안이 있으면 [새 배너] 비활성화, 행 클릭 무시(`startEdit` 가드 유지).
- 저장 성공(등록·수정 모두) → 모달 닫기 → `loadList()` → `pageSuccess`. 지금 수정 성공 후 `editBase`를 새 `version`으로 갱신하던 코드는 모달이 닫히므로 불필요해지지만, 모달 재진입 시 목록에서 다시 `startEdit`하므로 최신 `version`을 얻는다(사이드 이펙트 없음).
- `loadList()`는 모달이 열린 동안에도 응답이 도착할 수 있다(모달을 열기 전에 시작된 [새로고침]·이전 저장 후 재조회). 공통 규약 11에 따라 폼을 초기화하지 않는다.
- 이미지 선택 모달(`MediaPicker`)은 이미 중첩 지원. 시험으로 고정(아래).
- `beforeunload`는 순서 초안 기준 그대로.

### 2. 팝업 관리 (`popup/manage.html`)

- 구조는 배너와 같다. 추가 사항:
  - **수정 모달은 상세 GET이 끝나기 전에 연다**(게시글 모달 방식) — 모달을 열자마자 `loadingDetail`로 입력·저장·삭제 잠금(현 `lockUi`가 이미 이를 처리). 조회 실패(404 포함)는 모달을 닫고 `pageAlert`로 안내, 404면 `loadList()`.
  - 모달을 닫을 때(`hidden.bs.modal`) **세대 토큰(`detailRequestSeq`) 증가와 `loadingDetail = false`, `lockUi()`/`render()`를 한 묶음으로** 수행한다. 늦게 온 응답은 세대 불일치로 폼을 채우지 못하고, 그 응답의 `finally`는 `loadingDetail`을 해제하지 않으므로(`popup/manage.html:566`) 닫힘 처리가 대신 해제해야 한다. 세대만 올리면 다음 `startEdit()`이 `loadingDetail === true`로 즉시 반환해 모달 진입이 영구히 막힌다.
  - Quill(`NoticeEditor`)은 현재도 페이지 로드 시 생성한다. 모달 안(초기 `display:none`)에서 생성해도 동작하는지, 열린 뒤 `setHtml` 값이 보이는지는 구현 1단계에서 Playwright로 먼저 확인한다(게시글 모달이 같은 구조라 위험 낮음, 그래도 가정으로 명시).
  - 종류(이미지/HTML) 전환·높이 입력 활성화 규칙(`applyTypeUi`, `fHeight.disabled`)은 그대로. **숨겨진 invalid 입력이 제출을 막는 결함 방지 규칙을 보존**하는 것이 이 화면의 핵심 회귀 포인트다.
  - 노출 중 5개 초과 안내(`renderActiveNotice`)는 목록 영역에 남는다.

### 3. 게시판 정의 (`board/manage.html`)

- 필드가 가장 적어 가장 단순하다(이름·표시 대상·공지 게시판·공개·첨부). 구조는 배너와 같다.
- **게시판 정의는 ADMIN 전용**이다(MANAGER는 게시판 READ가 있어도 403). 화면의 권한 분기(`canCreate/canUpdate/canDelete`)는 ADMIN 기준이며, 이 계획은 인가를 바꾸지 않는다.
- **404 종료 정책**: 기존 코드는 PATCH 404와 DELETE 404 뒤에 `startCreate()`를 호출해 폼을 비운다. 모달에서는 같은 모달이 생성 모드로 바뀌는 결과가 되므로 다음처럼 바꾼다 — PATCH 404: 모달 닫기 + 페이지 `pageAlert`("이미 삭제된 게시판입니다") + 목록 재조회. DELETE 404: "이미 삭제된 게시판입니다."를 성공으로 흡수하고 모달 닫기 + 목록 재조회(배너·팝업의 삭제 404 처리와 동일). 400·409 등 다른 실패는 모달을 유지하고 `modalAlert`에 표시한다.
- 공지 게시판 체크(`boardNotice`)의 활성 규칙(표시 대상에 따라 비활성)은 그대로 유지하되, **모달을 열 때마다 `scope` 상태에서 다시 계산**되는지 확인한다(인라인에서는 폼이 계속 살아 있어 이전 상태가 남을 수 있었음).

### 4. 사이트 설정 (`site/fragments/site-tab.html`)

- 본문이 fragment이므로 변경은 fragment 안에서 끝난다(`site/manage.html`은 include만).
- 저장 로직을 `siteBtnSave`의 `click`에서 `siteForm`의 `submit`으로 옮긴다(공통 규약 4). `siteKeyInput`·`siteNameInput`에 `required`를 추가하되, 기존 서버 검증 메시지 흐름은 바꾸지 않는다. 저장 성공 후에는 지금의 `startEdit(fresh)`(폼 유지) 대신 모달을 닫는다(확정 결정). 저장 후 재조회 실패 시 `saved`로 스냅샷을 보정하는 기존 코드는 그대로 둔다(`SiteMenuScreenBrowserTest`의 해당 회귀 시험도 "닫았다 다시 열어 저장된 값 확인"으로 갱신).
- 목록은 `col-12`, 모달 폼. 버튼 배치: 푸터에 [저장(submit)], [기본 사이트로 지정], [삭제], [닫기]. 지금의 [초기화]는 모달에서 의미가 사라지므로 **[닫기]로 대체**한다(결정 필요 2 참조).
- **미저장 보호는 이 화면만 이미 있다**(`isDirty()` + `baseline`). 모달에서도 유지: ×·[닫기]를 눌렀을 때 `isDirty()`면 `confirm('저장하지 않은 변경이 있습니다…')`. 목록의 다른 행 클릭은 backdrop 때문에 불가능하므로 기존 "다른 사이트 선택" 확인 분기는 삭제 가능하나, **제거하지 않고 둔다**(무관한 정리 금지) — 도달 불가 코드가 됨을 리뷰 메모로 남긴다.
- `beforeunload`(`isDirty() || saving`)는 그대로.
- [기본 사이트로 지정]은 `!isDirty()`일 때만 보이는 현행 규칙 유지.
- 시드 사이트·메뉴 있는 사이트의 삭제 숨김, 키 불변(`mode === 'edit'`일 때 키 입력 비활성) 규칙은 `refreshUi()`가 그대로 처리한다.

## 영향 범위

| 구분 | 변경 |
|---|---|
| 서버 코드·API·권한·DB | **없음** (마이그레이션 없음, `SecurityConfig` 변경 없음) |
| 템플릿 | `banner/manage.html`, `popup/manage.html`, `board/manage.html`, `site/fragments/site-tab.html`(+ `site/manage.html`은 확인만) |
| 호출 JS | 각 템플릿 인라인 스크립트(외부 JS 파일 변경 없음. `media-picker.js`·`content-scope.js` 무변경) |
| 시험 | 기존 Playwright 시험의 "인라인 폼 직접 조작"을 "[새 …] 클릭 → 모달 대기 → 조작"으로 수정: `PopupAdminFormBrowserTest`, `ContentScopeAdminBrowserTest`, `BoardScopeAdminBrowserTest`, `SiteDefaultBrowserTest`, `SiteMenuScreenBrowserTest`(사이트 화면 부분). `ContentManagePageIntegrationTest` 등 HTML 문자열 단언이 있는지 구현 시 확인 |
| 문서 | `docs/troubleshooting.md`(모달 안 submit·중첩 모달 이슈가 실제로 나오면), CLAUDE.md 패키지 지침(`banner`/`popup`/`site`)의 "화면 구조" 문구가 인라인 폼을 전제로 하면 갱신 |

## 작업 단계 (PR 순서)

공통 1단계(각 PR 시작 시): `develop`가 아닌 `master`에서 `feat/admin-modal-<화면>` 브랜치.

1. **배너** — 기준 구현. 위 공통 규약 확정(여기서 정해진 모양을 이후 화면이 따른다). Playwright로 확인: 등록·수정·삭제·순서 저장, 이미지 라이브러리 선택(중첩 모달), 저장 중 닫기 불가, 순서 초안 중 잠금, 권한별(READ만 / CREATE만) 버튼 노출.
2. **팝업** — 1의 규약 + 상세 지연 로딩·Quill. `PopupAdminFormBrowserTest` 수정.
3. **게시판 정의** — `BoardScopeAdminBrowserTest` 수정.
4. **사이트 설정** — fragment, dirty 확인, 기본 지정. `SiteDefaultBrowserTest`·`SiteMenuScreenBrowserTest` 수정.

각 PR 공통 완료 기준: `./gradlew test` 통과, Playwright(MCP) 수동 확인 결과 보고, 스크린샷 첨부.

## 시험 계획

- **기존 시험 갱신**: 위 표의 Playwright 5개를 새 흐름으로. 시험 의도(스코프 선택, 기본 사이트 지정, 종류 전환 검증)는 바꾸지 않는다.
- **신규 브라우저 시험(화면당 최소 1개, 기존 클래스에 메서드 추가 가능)**:
  - 모달 backdrop 클릭·ESC로 닫히지 않는다.
  - 저장 중(느린 응답 모킹)에는 ×·[닫기]가 비활성이다.
  - 폼 필수 항목을 비운 채 [등록] → 서버 요청이 나가지 않고 브라우저 검증이 뜬다(푸터 submit 연결 회귀 방지).
  - 저장 성공 → 모달이 닫히고 목록에 반영되며 `pageSuccess`가 보인다.
  - 모달 안에서 서버 오류(400/409/403)가 `modalAlert`에 보인다.
  - (배너·팝업) 라이브러리 선택 모달이 폼 모달 위에 열렸다 닫혀도 폼 모달이 스크롤된다(`MediaPickerNestedModalBrowserTest` 패턴 확장).
  - (팝업) 수정 모달을 열자마자 닫았을 때(상세 응답을 지연시켜) 늦게 온 응답이 폼을 채우지 않는다. **그 뒤 다른 행을 눌러 새 상세 조회가 시작되고 편집·저장이 정상 동작한다**(`loadingDetail` 영구 잠금 회귀).
  - (사이트) 변경 후 [닫기] → confirm 취소 시 모달 유지, 확인 시 닫힘.
  - (사이트) 키·이름을 비운 채 Enter 제출 → 요청이 나가지 않고 브라우저 검증이 뜬다. 기본 폼 제출(페이지 이동)이 일어나지 않는다.
  - (게시판 정의) ADMIN은 모달에서 등록·수정·삭제가 된다. MANAGER는 게시판 READ가 있어도 화면·API 모두 403이다(기존 `BoardPermissionIntegrationTest` 유지, 권한 조합 시험 대상 아님). 수정 중 대상이 삭제된 상태에서 저장(PATCH 404) → 모달이 닫히고 오류 안내, 삭제(DELETE 404) → "이미 삭제됨"으로 닫힘. 어느 쪽도 **모달이 생성 모드로 바뀌지 않는다**.
  - (권한 조합, 배너·팝업 각각) READ 전용: 행 클릭으로 모달이 열리고 저장·삭제 비활성 / READ+DELETE: 푸터 [삭제] 도달 / READ+UPDATE: 저장 가능·삭제 숨김 / CREATE 없음: [새 …] 숨김. (`BannerPermissionMatrixIntegrationTest`는 API 권한이라 화면 시험은 별도)
  - (배너·팝업) 편집 대상이 삭제된 상태에서 늦게 도착한 목록 응답 → 입력이 유지되고 `modalAlert` 안내가 보인다(폼이 생성 모드로 초기화되지 않는다).
  - (팝업) 상세 GET이 즉시 404/403으로 응답(열림 애니메이션 중) → 모달이 결국 닫히고 오류가 보인다.
  - (배너·게시판·사이트) 저장은 성공하고 목록 재조회만 실패 → 모달 닫기 → 같은 행을 다시 열면 저장된 값·새 `version`이 채워진다.
- Playwright 시험이 `confirm()`을 쓰는 경로는 `dialog` 핸들러를 먼저 등록한다(세션 관리 계획 R2-3과 같은 이유).

## 위험과 대응

| 위험 | 대응 |
|---|---|
| 모달 안 `<form>`을 푸터 버튼이 제출하지 못해 검증이 우회됨 | 공통 규약 4(`form="…"` submit), 신규 시험으로 고정 |
| 닫기 경로가 늘어나 저장 중 닫혀 응답 처리 후 상태 불일치 | 규약 3(저장 중 닫기 비활성), 규약 6(`hidden.bs.modal` 초기화) |
| 팝업 상세 응답이 닫힌 모달을 채움 | 세대 토큰을 닫을 때도 증가 |
| Quill이 숨겨진 컨테이너에서 초기화돼 레이아웃 오류 | 팝업 구현 1단계에서 먼저 확인 |
| 중첩 모달에서 `body.modal-open` 소실 | 기존 `media-picker.js` 복구 로직 + 신규 시험 |
| 알림이 backdrop 뒤에 가려져 오류를 못 봄 | 규약 5(`modalAlert`) |
| 이미 시작된 목록 응답이 열린 폼을 생성 모드로 초기화 | 규약 11 |
| 전환 중 `hide()` 무시로 모달이 남음 | 규약 12(`closeModal()`) |
| 저장 후 재조회 실패로 낡은 `version` 재진입 | 규약 13 |
| 사이트 설정 저장이 `click` 기반이라 submit 전환 시 이중 처리 | 사이트 절·규약 4(`click` 핸들러 제거) |
| 도달 불가 코드 잔존(사이트 화면의 행 전환 확인) | 제거하지 않고 리뷰 메모, 후속 정리 후보 |

## 이번 범위에서 하지 않는 것

권한 관리, 정적 페이지, FAQ(각각 2차·3차 계획), 공통 모달 fragment/JS 추출, 서버 API 변경, 게시글 관리 모달의 닫기 정책 변경(비교 기준으로만 사용).

## 결정이 필요한 항목

없음 — 위 "확정 결정" 표에 모두 반영됨(2026-10-20).
