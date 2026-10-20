# PLAN — 관리 화면 등록·수정 폼을 모달로 전환 (3차: FAQ)

> 상태: v3 — 적대적 리뷰 3라운드 **ship**(codex gpt-6.1-sol, 1라운드 P2×1·2라운드 P2×1·P3×1 수용, 3라운드 새 지적 없음). `PLAN-admin-form-modal.md`(1차)·`PLAN-admin-form-modal-page.md`(2차)의 후속이다. 1차의 공통 규약과 확정 결정(static 모달, 저장 후 닫기, 연속 등록은 [새 …] 재클릭)을 그대로 따르고, 이 문서는 FAQ에만 있는 차이를 다룬다.

## 개정 이력

- 3라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. 설계 5(`closeModal()` → `await reload()` → 안내 순서)와 설계 13(모드별 안내)이 타당함을 확인(`manage.html:369`, `FaqService.java:173,213`).
- v3 변경(2라운드 — codex gpt-6.1-sol, P2×1·P3×1, 결정 필요 0건):
  - R2-1(P2 상세 실패 안내가 후속 `reload()`에 지워짐): 설계 5 변경 + 시험 보강 — `reload()`는 성공하면 `pageAlert`를 숨긴다(`manage.html:377`). 현재 상세·트리 불일치 경로는 이 때문에 **재조회 뒤** 안내한다(`:765`). 404·불일치는 `loadingDetail`을 풀고 `closeModal()` → `await reload()` → **그 뒤** `showPageError` 순서로 한다(404 경로도 같은 순서로 맞춘다). 시험 ⑧은 재조회 완료·모달 `closed` 이후에도 안내가 남는지 확인한다.
  - R2-2(P3 설계 13의 복구 수단이 등록 모드에는 성립하지 않음): 설계 13 변경 + 시험 추가 — 등록 모드에는 편집 시작 시점의 소속 기준(`baseCategoryId`)이 없어 존재하는 다른 카테고리를 고르면 저장된다(`FaqService.java:178`). 제출 차단 안내를 모드별로 나눈다: 등록은 "선택한 카테고리가 삭제되었습니다. 존재하는 다른 카테고리를 선택해 주세요"(입력 유지, 복구는 재선택), 수정은 기존 문구(닫고 다시 선택).
- v2 변경(1라운드 — codex gpt-6.1-sol, P2×1, 결정 필요 0건):
  - R1-1(P2 409 뒤 선택했던 카테고리가 삭제돼 선택값이 사라짐): 설계 13 신설 + 시험 추가 — `renderCategorySelect()`는 옵션을 전부 다시 만들고 기존 선택이 새 트리에 있을 때만 복원해(`manage.html:539,543`), A 소속 FAQ를 연 뒤 다른 관리자가 FAQ를 C로 옮기고 빈 A를 지우면 409 재조회 뒤 A 옵션이 사라져 다른 카테고리가 자동 선택된다(FAQ는 존재하므로 "편집 항목 없음" 방어에 걸리지 않는다). FAQ 모달이 열려 있으면 사라진 선택값을 **임시 옵션**(삭제된 카테고리 표시)으로 보존하고, 그 상태의 저장은 요청 전에 막는다.

## Context

FAQ 관리(`admin/faq/manage.html`, 단일 파일 ~850줄)는 왼쪽에 카테고리·FAQ **트리**(드래그 앤 드롭 + ▲▼ 순서 **초안** + [순서 반영]), 오른쪽에 카테고리 폼·FAQ 폼(둘 다 Quill 답변 포함)이 인라인으로 있다. **항목 등록·수정·삭제 폼만 모달로 옮기고 순서 편집(트리·draftBar·[반영]·[취소]·새로고침)은 페이지에 그대로 둔다.** API·권한·DB·`SecurityConfig`는 바꾸지 않는다.

### 확정 결정 (사용자, 2026-10-20)

| 항목 | 결정 |
|---|---|
| 범위 | 항목(카테고리·FAQ) 등록·수정·삭제만 모달. 순서 편집은 페이지 유지 |
| 저장 후 | 모달을 닫는다 |
| **409(낡은 화면)** | **모달을 유지하고 입력을 보존한다** |
| PR | FAQ 하나의 PR |

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 상태 플래그가 **전역 하나씩**이다: `busy`(쓰기 요청부터 후속 재조회 완료까지·목록 조회 중), `loadingDetail`, `syncFailed`(쓰기 결과 불명·재조회 실패 → [새로고침]만 허용), 순서 초안 `isDirty()`. `formLocked() = busy \|\| loadingDetail \|\| syncFailed \|\| isDirty()`이고 두 `<fieldset>`과 편집기를 잠근다 | `manage.html` `interactionLocked`·`formLocked`·`applyLock` |
| 모든 쓰기가 `write()` 한 곳을 지난다. 409면 `fetchTree()`로 목록을 다시 읽고 **초안을 버리며**, `busy`는 재조회가 끝나야 풀린다. 쓰기 성공 뒤 재조회가 실패하면 `syncFailed`, 응답을 잃으면(네트워크 오류) `UNKNOWN_RESULT_MESSAGE` + `syncFailed` | `write()` |
| `setServer()`는 편집 중이던 카테고리·FAQ가 새 트리에 없으면 **폼을 생성 모드로 되돌린다**(`startCategoryCreate()`/`startFaqCreate()`) | `setServer` |
| FAQ 수정은 행 클릭 → 상세 GET(`startFaqEdit`) → 폼 채움. 상세의 카테고리가 화면 트리에 없으면 저장하지 않고 목록을 다시 읽는다(PLAN-site-menu v5). 카테고리 수정의 낡은 화면 기준은 `categoryBase.version`, FAQ는 `faqBase.categoryId` | `startFaqEdit`, `catForm`·`faqForm` submit |
| 카테고리 선택 박스는 렌더링마다 현재 선택값을 보존한다(거절된 저장 뒤 선택 유지, 코드 리뷰 R1-1) | `renderCategorySelect` |
| 이 화면에는 **폼 미저장 판정이 없다**(`isDirty()`는 순서 초안 전용, `beforeunload`도 초안만) | 코드 전체 |
| 폼 버튼 중 [새 카테고리 작성]·[새 FAQ 작성]·[삭제]·[등록/저장]이 `refreshFormButtons()`로 `d-none` 토글된다(저장 버튼은 권한이 없으면 숨김) | `refreshFormButtons` |
| 시험이 폼 입력·`fieldset` 상태·`faqFormTitle`을 직접 읽는다 | `FaqAdminOrderBrowserTest`, `ContentScopeAdminBrowserTest`의 FAQ 시험 |

## 설계

1. **모달은 하나, 모드가 둘**: `faqModal` 하나에 카테고리 섹션(`#catSection`: `catForm`·`catFieldset`)과 FAQ 섹션(`#faqSection`: `faqForm`·`faqFieldset`·Quill)을 두고 `modalMode`(`'category'`/`'faq'`)로 보이는 섹션·제목(`catFormTitle`/`faqFormTitle`)·푸터를 바꾼다. 한 번에 하나만 열리므로 상태기계(`modalState`·`pendingClose`·`data-state`)가 하나다. 푸터마다 [삭제](`btnCatDelete`/`btnFaqDelete`, mr-auto)·[닫기]·[등록/저장](submit, `form="catForm"`/`"faqForm"`)을 둔다. 모달 마크업은 화면 셸 안 body 쪽(컨테이너 밖). `admin-manage.css`를 불러온다.
2. **ID 유지**: `catForm`, `catFieldset`, `catName`, `catUse`, `catSiteScope`, `btnCatSave`, `btnCatDelete`, `faqForm`, `faqFieldset`, `faqCategory`, `faqQuestion`, `faqAnswerEditor`, `faqUse`, `btnFaqSave`, `btnFaqDelete`, `catFormTitle`, `faqFormTitle`, `draftBar`, `btnApplyOrder`, `btnCancelOrder`, `btnReload`, `faqTree`. `btnCatNew`/`btnFaqNew`는 목록 카드 헤더의 [새 카테고리]/[새 FAQ]로 옮긴다(`canCreate`일 때만 보임). 새 ID: `faqModal`, `modalAlert`, `btnModalClose`, `btnModalCloseX`.
3. **열기·닫기**: 새로 여는 동작(`[새 …]`·행 클릭)은 `modalState === 'closed'`이고 `!formLocked()`일 때만 — 순서 초안·`busy`·`loadingDetail`·`syncFailed` 중에는 열 수 없다(현재 폼 잠금과 같은 규칙). 닫기는 `closeModal()`(열림 전환 중이면 `pendingClose`로 `shown` 뒤에 닫음 — 1차 규약 12). 배경 클릭·ESC 불가(static). **미저장 닫기 확인은 두지 않는다** — 이 화면에는 폼 미저장 판정이 원래 없고(배너·팝업·게시판과 같다), static 모달이 실수 닫힘을 막는다. ×·[닫기]는 **쓰기 요청이 진행 중인 동안**(`writing`: `write()`의 `fetch`부터 응답까지)만 비활성이다 — 후속 재조회·상세 조회 중에는 닫을 수 있다.
4. **오류 위치**: `opening`/`open`이면 `modalAlert`, 아니면 `pageAlert`. 모달을 닫으면서 안내해야 하는 경로(상세 조회 실패·상세와 트리 불일치)는 `showPageError` + `closeModal()`.
5. **수정 모달은 연 직후 상세를 읽는다**(`startFaqEdit`): 모달을 먼저 열고 `loadingDetail`로 폼·편집기를 잠근다. 실패(404 포함)·불일치는 `loadingDetail`을 풀고 모달을 닫은 뒤 **`reload()`가 끝난 다음** 페이지 상단에 안내한다 — `reload()`는 성공하면 `pageAlert`를 숨기므로 안내를 먼저 띄우면 사라진다(재조회가 필요 없는 실패(403·네트워크)는 바로 안내). 닫힘(`hidden`) 처리에서 **`detailSeq` 증가 + `loadingDetail = false`**를 함께 해 늦은 응답이 새 폼을 덮지 못하고 다음 진입이 막히지 않게 한다(1차 R2-1). 카테고리 수정은 목록의 `version`으로 바로 채운다(상세 GET 없음, 기존과 같다).
6. **저장·삭제 성공**: `write()`의 `onSuccess`에서 `closeModal()`을 호출해 **응답 직후 모달을 닫고**(재조회를 기다리지 않는다), 성공 안내는 페이지 상단. 재조회가 실패하면 기존대로 `syncFailed`+페이지 상단 안내만 남는다. 기존의 성공 뒤 폼 갱신 코드(`categoryBase`/`editingFaq`/`faqBase` 갱신, 등록 뒤 `startFaqCreate()`+카테고리 선택 유지)는 쓰지 않는다 — 닫힘 처리가 폼을 초기화한다.
7. **409(낡은 화면·상한·자식 있는 카테고리 등) — 모달 유지·입력 보존**: `write()`의 409 분기는 지금처럼 목록을 다시 읽고 초안을 버리되 **모달은 닫지 않는다**. 재조회가 `setServer()`를 부르므로, **편집 중이던 항목이 새 트리에 없을 때 폼을 생성 모드로 되돌리지 않고** 모달이 열려 있으면 `modalAlert`로 안내만 한다("이 항목이 삭제되었거나 목록에 없습니다. 저장하면 실패할 수 있으니 닫고 목록을 확인해 주세요" — 입력은 둔다). 모달이 닫혀 있을 때(`closing`·`closed`)는 안내하지 않고 닫힘 처리가 폼을 초기화한다. 409 안내 문구는 모달 맥락으로 바꾼다("… 입력한 내용은 그대로 두었으니 복사해 두고, 닫은 뒤 다시 선택해 최신 내용을 불러오세요").
8. **쓰기 잠금과 재조회의 소유권**: `busy`는 `write()` 하나가 소유한다(쓰기 시작에 켜고, 재조회까지 끝낸 뒤 끈다). `busy` 동안 모든 새 동작(`[새 …]`·행 클릭·[반영]·[새로고침])이 막히므로, 사용자가 409 뒤 모달을 닫아도 재조회가 끝나기 전에는 새 모달을 열 수 없다 — 이전 `write()`의 늦은 해제가 새 요청의 잠금을 푸는 경합(정적 페이지 R2-1)은 구조적으로 없다. 닫기 버튼은 `writing` 동안만 비활성이라 재조회가 지연돼도 닫을 수 있다.
9. **쓰기 결과 불명(`syncFailed`)**: 응답을 잃으면(네트워크 오류) 모달을 유지하고 `UNKNOWN_RESULT_MESSAGE`를 `modalAlert`에 보이며 폼은 잠긴다(자동 재전송 방지, 기존과 같음). 사용자는 [닫기] 후 [새로고침]으로 복구한다 — 닫으면 입력은 사라진다(이 PR이 의도한 변경이며 안내 문구에 반영: "입력은 닫으면 사라지니 필요하면 복사해 두세요"). 쓰기는 성공했는데 재조회만 실패하면 모달은 이미 닫혔고 `syncFailed`로 [새로고침]만 남는다.
10. **순서 편집과의 상호 배제**: 초안이 있으면 `formLocked()`라 모달을 열 수 없다. 모달이 열려 있는 동안은 트리가 배경 뒤라 초안이 생길 수 없다. 드래그·▲▼·[반영]·[취소]·`draftBar`·`beforeunload`(초안 기준)는 **변경하지 않는다**. `sync()` 재렌더 규칙(잠금 상태가 바뀌면 호출)도 그대로이며 모달 상태 변화(`hidden`)에서도 `sync()`를 호출한다.
11. **권한**: 행 클릭 진입은 `canUpdate`를 요구하지 않는다(현재도 없음). 저장 버튼은 `editing ? canUpdate : canCreate`가 아니면 숨김(기존 `refreshFormButtons`), [삭제]는 `canDelete`, [새 카테고리]/[새 FAQ]는 `canCreate`. 순서 손잡이는 `canUpdate`(기존).
12. **Quill**: 모달 안(초기 숨김, FAQ 섹션 `d-none`)에서 생성한다(팝업·정적 페이지와 같은 구조). `editor.quill.enable(!locked)` 잠금은 그대로.

13. **사라진 카테고리 선택값 보존**: FAQ 모달이 열려 있는 동안 `renderCategorySelect()`는 이전 선택값이 새 트리에 없으면 그 값을 **임시 옵션**("삭제된 카테고리 #id", `data-missing`)으로 다시 만들어 선택 상태를 유지한다(입력 보존). FAQ 폼 제출은 선택된 카테고리가 `categoryById`에 없으면 **요청을 보내지 않고** `modalAlert`로 안내한다. **모드별로 안내가 다르다** — *수정 모드*: "선택한 카테고리가 삭제되었습니다. 닫은 뒤 이 FAQ를 다시 선택해 최신 내용을 불러오세요"(다른 카테고리를 골라 저장해도 편집 시작 때의 `baseCategoryId`가 낡아 서버가 다시 409를 주므로 복구는 닫고 다시 여는 것뿐이다). *등록 모드*: "선택한 카테고리가 삭제되었습니다. 존재하는 다른 카테고리를 선택해 주세요"(등록에는 소속 기준이 없어 다른 카테고리를 고르면 저장되고 입력은 유지된다). 모달이 닫힌 뒤(`hidden`)에는 임시 옵션을 쓰지 않는다.

## 영향 범위

| 구분 | 변경 |
|---|---|
| 서버 코드·API·권한·DB | 없음 |
| 템플릿 | `admin/faq/manage.html`(단일 파일) |
| 시험 | `FaqAdminOrderBrowserTest`(폼 입력 시험을 모달 흐름으로), `ContentScopeAdminBrowserTest`의 FAQ 시험(상세·트리 불일치), 신규 `FaqModalBrowserTest` |
| 문서 | `com.cms.admin.faq` CLAUDE.md가 인라인 폼 구조를 전제하면 갱신 |

## 시험 계획

- 기존: `FaqAdminOrderBrowserTest`의 폼 시험 5건(상세 로딩 잠금, 재조회 실패 복구, 거절된 저장 뒤 카테고리 선택 유지, 깨진 성공 본문, 응답 유실)을 모달 흐름으로. 순서 시험(드래그·▲▼·취소·409·요청 중 잠금)은 **수정 없이 통과해야 한다**(회귀 증거). 닫힘 판정은 `data-state`로.
- 신규 `FaqModalBrowserTest`: ① 배경·ESC로 닫히지 않음 ② 카테고리 이름·FAQ 질문을 비운 저장이 브라우저 검증으로 막힘(첫 무효 입력 포커스) ③ 카테고리·FAQ 등록 → 모달 닫힘·트리 반영 ④ 삭제 → 닫힘 ⑤ **카테고리 PUT 409: 모달 유지·입력 보존·"입력한 내용은 그대로" 안내, 닫고 다시 열어 최신 version으로 저장 성공** ⑥ **409 뒤 재조회를 붙잡아도 ×·[닫기] 활성·입력 보존, 그동안 행 클릭으로 새 모달이 열리지 않음(`busy`), 재조회가 끝나면 열린다** ⑦ 편집 중 항목이 사라진 트리가 도착(`reload()` 호출)해도 폼이 생성 모드로 비워지지 않고 안내만 ⑧ 상세 즉시 404 → 모달이 결국 닫히고 **재조회가 끝난 뒤에도 페이지 안내가 남는다** ⑨ 상세 조회 중 닫아도 다음 행이 열리고 늦은 응답이 덮지 않음 ⑩ READ 전용 MANAGER: 행을 열어 볼 수 있고 저장 버튼 숨김·[새 …] 숨김 ⑪ 초안이 있으면 [새 …]·행 클릭으로 모달이 열리지 않음. ⑬ **등록 모드: 선택했던 카테고리가 삭제된 트리가 도착하면 임시 옵션으로 선택이 유지되고 제출이 요청 없이 막히며, 존재하는 다른 카테고리를 고르면 저장된다.** ⑫ **선택했던 카테고리가 삭제된 409: 열린 FAQ 폼의 카테고리 선택이 "삭제된 카테고리" 임시 옵션으로 유지되고(다른 카테고리로 바뀌지 않음), 그 상태의 저장은 요청 없이 안내로 막히며, 닫고 다시 열면 최신 소속으로 저장된다**(기존 시험은 400 뒤 대상이 존재하는 경우만 본다).
- 변이 실험: static·form 연결, 409 분기의 모달 유지, `setServer` 불일치 처리, `loadingDetail` 해제, `pendingClose`, `writing` 닫기 비활성, `busy` 중 진입 가드를 각각 제거해 시험이 실패하는지 확인.

## 이번 범위에서 하지 않는 것

순서 편집 로직 변경, 폼 미저장 확인 추가, 서버 API·권한 변경, 카테고리 폼의 상세 GET 도입, 공통 모달 추상화.
