# PLAN — 팝업·FAQ 모달의 미저장 닫기 확인

> 상태: v2 — 적대적 리뷰 2라운드 **ship**(codex gpt-6.1-sol, 1라운드 P2×2 수용, 2라운드 새 지적 없음). `PLAN-admin-form-modal.md`(1차)·`PLAN-admin-form-modal-page.md`(2차)·`PLAN-admin-form-modal-faq.md`(3차)의 후속이다. 모달 전환 PR 여섯 개(#155~#160)가 머지된 뒤 로드맵 49차에 "미저장 닫기 확인의 화면 간 불일치"로 남긴 후속 정리 후보 3번이다. 1~3차의 공통 규약(static 모달, `modalState`·`pendingClose`·`data-state`, 푸터 `form=` 연결)을 그대로 따른다.

## 개정 이력

- 2라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. R1-1(`{kind, value}` 기준값)·R1-2(시험 분류 + 확인창 0회 단언)의 반영이 코드와 맞고, 기준값 시점(FAQ 생성은 `sync()` 뒤, 수정은 카테고리 복원 뒤)이 실제 초기화 순서와 일치함을 확인.
- v2 변경(1라운드 — codex gpt-6.1-sol, `needs-attention` P2×2, 결정 필요 0건, 둘 다 코드로 확인하고 수용):
  - R1-1(P2 FAQ 카테고리 수정에서 기준값과 `modalMode`의 순서): 설계 4·7 변경 + 시험 추가 — `modalMode`는 `openModal(mode)`(`faq/manage.html:750`)에서 바뀌는데 `startCategoryEdit`는 필드를 채운 **뒤** `openModal("category")`(`:827`)를 부르고 `hidden`도 `modalMode`를 되돌리지 않는다. 모드 분기 스냅샷을 쓰면 FAQ를 닫고 카테고리 수정을 열 때 옛 모드의 스냅샷이 기준값이 돼 무변경인데 확인창이 뜰 수 있다. 기준값을 `{kind, value}`로 **종류와 함께** 저장하고 판정은 그 `kind`의 스냅샷 함수(`categorySnapshot()`/`faqSnapshot()`)로 한다 — 현재 `modalMode`에 의존하지 않는다. 시험 7에 FAQ→카테고리 수정→무변경 닫기를 추가한다.
  - R1-2(P2 기존 시험 수정 목록이 사실과 다름): 영향 범위·시험 계획 변경 — 정찰 목록 중 실제로 변경이 남은 채 닫는 시험이 아닌 곳이 있다(`FaqModalBrowserTest:163·182` 빈 카테고리 폼, `:332` 상세 도착 전, `PopupModalBrowserTest:131` 변경 없는 수정 폼, `:230` 상세 도착 전, `ContentScopeAdminBrowserTest:155` 이번 범위 밖 배너, `:210` 변경 없는 정적 페이지). 무조건 `dialog.accept()`를 붙이면 불필요한 확인창이 떠도 시험이 통과해 회귀를 숨긴다. **각 닫기 호출을 읽어 분류**하고, 변경이 남은 닫기에만 수락 핸들러를 넣으며, 무변경·상세 로딩 중 닫기는 **확인창 0회**를 단언한다(다이얼로그를 세는 핸들러도 반드시 `accept()`/`dismiss()`를 호출한다).

## Context

관리 화면 등록·수정 폼을 모달로 옮긴 뒤 미저장 입력을 [닫기]·[×]로 닫을 때의 동작이 화면마다 다르다.

| 화면 | 입력 중 [닫기]·[×] | 이유 |
|---|---|---|
| 사이트 설정 | 확인창 있음 | 원래 폼 `isDirty()`가 있었다 |
| 정적 페이지 | 확인창 있음 | 원래 폼 `isDirty()`가 있었다 |
| 팝업 | 즉시 닫힘, 입력 소실 | 폼 변경 판정이 없었다 |
| FAQ(카테고리·FAQ 폼) | 즉시 닫힘, 입력 소실 | 폼 변경 판정이 없었다(`isDirty()`는 순서 초안 전용) |
| 배너·게시판 정의 | 즉시 닫힘, 입력 소실 | 필드가 적어 이번 범위에서 제외 |

팝업(HTML 본문·노출 기간·이미지 선택)과 FAQ(긴 답변 편집기)는 입력을 잃는 비용이 크다. 이 두 화면에 사이트 설정·정적 페이지와 **같은 방식**(`hide.bs.modal`에서 확인, 취소하면 `preventDefault`, 프로그램 닫기는 `forceClose`로 우회)의 미저장 닫기 확인을 넣는다. API·권한·DB·`SecurityConfig`·서버 코드는 바꾸지 않는다.

### 확정 결정 (사용자, 2026-10-21)

| 항목 | 결정 |
|---|---|
| 범위 | 팝업 + FAQ. 배너·게시판 정의는 제외(필드가 적다) |
| FAQ 카테고리 폼 | **포함** — 한 모달의 두 폼 동작을 일치시킨다 |
| PR | **화면당 PR 1개**(팝업 PR, FAQ PR 따로) |
| 확인 문구 | 네 화면 모두 "저장하지 않은 변경이 있습니다. 버리고 닫으시겠습니까?"로 **통일**한다(정적 페이지의 기존 문구). 사이트 설정의 "…버리고 닫을까요?"는 같은 문구로 바꾼다 — 팝업 PR에 한 줄로 포함 |

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 정적 페이지·사이트 설정의 구현: `forceClose` 플래그, `closeModal()`이 `forceClose = true`, `openModal()`·`hidden`이 `false`로 되돌림, `hide.bs.modal`에서 `!forceClose && isDirty() && !confirm(...)`이면 `event.preventDefault()` 후 return(상태를 `closing`으로 바꾸지 않음) | `page-tab.html` `closeModal`·`hide.bs.modal`, `site-tab.html` 같은 이름 |
| 사이트 설정은 폼을 채운 직후 값을 `baseline`으로 저장해 두고 `formValues() !== baseline`으로 판정한다. 정적 페이지는 `editing`의 서버 값과 직접 비교한다 | `site-tab.html` `setForm`·`isDirty`, `page-tab.html` `isDirty` |
| Quill 래퍼 `NoticeEditor`에 `isChanged()`가 있다: `setHtml()`이 로드 직후의 `getSemanticHTML()`을 `initialHtml`로 저장하고 `isChanged()`는 현재 값과 비교한다. 로드 시 정규화(공백·빈 문단)가 있어도 기준이 정규화 이후 값이라 거짓 양성이 없다 | `notice-editor.js` `setHtml`·`isChanged` |
| 팝업 모달: ×(`btnModalCloseX`)·[닫기](`btnModalClose`)는 `data-dismiss="modal"`로 Bootstrap이 직접 닫는다. 프로그램 닫기는 `closeModal()` 네 곳(상세 실패 둘, 저장·삭제 성공 둘). `hide.bs.modal`은 `modalState = "closing"`만 한다. 폼을 채우는 곳은 `startCreate`(→`resetFormState`)·`startEdit`(상세 도착 뒤), 비우는 곳은 `hidden`의 `resetFormState` | `popup/manage.html` |
| 팝업 폼 입력: 제목·종류(IMAGE/HTML 라디오)·링크·이미지 파일·**라이브러리에서 고른 이미지**(`pickedImage`)·위치 X/Y·폭·높이·노출 시작/종료(날짜+시+분)·사용·표시 대상·HTML 본문 편집기 | `popup/manage.html` |
| FAQ 모달: 한 모달에 카테고리 폼(`catForm`)·FAQ 폼(`faqForm`)이 있고 `modalMode`로 하나만 보인다. 프로그램 닫기는 `closeModal()` 일곱 곳(카테고리·FAQ의 저장·삭제 성공 넷, FAQ 상세 실패·불일치·예외 셋). 열기는 `startCategoryCreate`·`startCategoryEdit`·`startFaqCreate`·`startFaqEdit`, 비우기는 `hidden`의 `resetCategoryForm`+`resetFaqForm` | `faq/manage.html` |
| 팝업·FAQ의 `hidden` 처리에서 `loadingDetail`을 풀고 세대를 올린다. 상세 조회 중(`loadingDetail`)에는 입력이 기본값이라 변경이 없다 | `popup/manage.html`·`faq/manage.html` `hidden.bs.modal` |
| 미디어 라이브러리 선택 모달은 `document.body`의 직접 자식으로 만들어(`media-picker.js:125`) 폼 모달의 DOM 자손이 아니다 — 그 모달의 `hide.bs.modal`이 폼 모달 핸들러로 버블링되지 않는다 | `media-picker.js` |
| 기존 브라우저 시험이 입력한 뒤 [닫기]·[×]를 누르는 곳: `FaqAdminOrderBrowserTest:418`, `FaqModalBrowserTest` 여럿(163·182·242·275·332·405), `PopupModalBrowserTest:131·230`, `PopupAdminFormBrowserTest:91`, `ContentScopeAdminBrowserTest:155·210`. Playwright는 `onDialog` 핸들러가 없으면 `confirm()`을 **취소**로 자동 처리하므로, 입력이 남은 채 닫는 시험은 모달이 안 닫혀 실패한다 | `src/test/java` |

## 설계

1. **판정은 기준값 비교**: 폼을 채운 **직후** `baseline = formSnapshot()`을 저장하고 `isFormDirty() = baseline !== null && (formSnapshot() !== baseline || editor.isChanged())`로 판정한다. 서버 응답 객체와 직접 비교하지 않는다(필드가 많고, 서버 값과 입력 형식이 달라 — 노출 기간은 날짜+시·분 select — 거짓 양성이 나기 쉽다). 사이트 설정과 같은 방식이다. 기준값은 `baseline = {kind, value}`로 **종류(`popup`/`category`/`faq`)와 함께** 저장하고, 판정은 `kind`에 해당하는 스냅샷 함수로 한다(현재 `modalMode`에 의존하지 않는다 — 설계 4). `baseline`은 `hidden`에서 `null`로 되돌린다(`resetFormState`/`resetCategoryForm`·`resetFaqForm`이 맡는다).
2. **`baseline`이 `null`인 동안은 변경 없음**: 수정 모달을 열고 상세를 읽는 동안(`loadingDetail`)은 기준값이 아직 없다 → 확인 없이 닫을 수 있다(기존 동작 유지, 정적 페이지 주석과 같은 규칙). 상세가 도착해 폼을 채운 직후 기준값을 정한다.
3. **스냅샷에 넣는 값**(문자열 배열의 `JSON.stringify`):
   - **팝업**: 제목, 선택된 종류, 링크, 위치 X·Y, 폭, 높이, 노출 시작(날짜·시·분)·종료(날짜·시·분), 사용, 표시 대상(`scope.value()`), 선택한 이미지 파일(이름·크기 — 파일 입력의 첫 파일, 없으면 빈 값), 라이브러리 이미지(`pickedImage`의 id 또는 없음). 본문은 `editor.isChanged()`.
   - **FAQ 카테고리 폼**: 이름, 사용, 표시 대상(`scope.value()`).
   - **FAQ 항목 폼**: 카테고리 선택값, 질문, 사용. 답변은 `editor.isChanged()`.
4. **기준값을 정하는 시점**: 팝업 — `startCreate`는 `resetFormState()` 직후, `startEdit`은 상세를 폼에 채운 마지막(편집기 `setHtml` 뒤)에. FAQ — `startCategoryCreate`·`startFaqCreate`는 폼을 비운 뒤 `openModal()`·`sync()`까지 끝낸 **다음**(선택 박스가 `render()`로 다시 만들어진 뒤라야 카테고리 선택값이 안정된다), `startCategoryEdit`는 필드를 채운 직후, `startFaqEdit`는 `fFaqCategory.value`를 복원한 뒤. 위 어느 경우든 기준값은 `kind`를 명시한 스냅샷 함수(`categorySnapshot()`·`faqSnapshot()`)로 만든다 — `startCategoryEdit`는 필드를 채운 **뒤** `openModal("category")`를 부르고 `modalMode`는 `hidden`에서도 되돌리지 않으므로, 모드에 따라 분기하는 공통 함수를 쓰면 옛 모드의 값이 기준값이 된다(R1-1). 늦은 상세 응답이 세대 불일치로 버려지는 경로(`seq !== detailRequestSeq`/`detailSeq`)에서는 기준값을 건드리지 않는다.
5. **닫기 가로채기**: `hide.bs.modal` 핸들러 맨 앞에서 `!forceClose && isFormDirty() && !confirm("저장하지 않은 변경이 있습니다. 버리고 닫으시겠습니까?")`이면 `event.preventDefault()` 하고 상태를 바꾸지 않고 끝낸다(`modalState`는 `open` 그대로). 취소하면 입력·잠금 상태가 모두 보존된다.
6. **`forceClose`**: 팝업·FAQ의 `closeModal()`이 `forceClose = true`로 설정하고, `openModal()`·`hidden`이 `false`로 되돌린다(사이트 설정·정적 페이지와 같다). 저장·삭제 성공, 상세 조회 실패, 상세·트리 불일치 같은 **프로그램 닫기**는 확인하지 않는다. [닫기]·[×](`data-dismiss`)만 확인을 거친다.
7. **FAQ — 종류별 판정**: `isFormDirty()`는 `baseline.kind`에 따라 카테고리 또는 FAQ 스냅샷(+편집기는 `faq` 종류에서만)을 쓴다. 한 번에 하나의 폼만 열리므로 `baseline` 변수는 하나다. 현재 `modalMode`는 판정에 쓰지 않는다(설계 4).
8. **FAQ — 기존 안내와의 관계**: `UNKNOWN_RESULT_MESSAGE`("모달을 닫고 … 입력은 닫으면 사라지니 필요하면 복사해 두세요")와 409 안내("입력한 내용은 그대로 두었으니 복사해 두고, 닫은 뒤 …")는 **그대로 둔다**. 이제 닫을 때 확인창이 한 번 더 뜨지만, 안내 문구와 모순되지 않는다(복사해 둔 뒤 확인하고 닫으면 된다). 폼이 `fieldset`으로 잠긴 상태(`formLocked()`)에서도 입력이 바뀌었으면 확인한다.
9. **저장 중 닫기는 기존대로 비활성**: 팝업 `saving`, FAQ `writing`일 때 ×·[닫기]는 이미 `disabled`라 확인 로직과 겹치지 않는다.
10. **하지 않는 것(`beforeunload`)**: 페이지 이탈(`beforeunload`) 경고는 이번 범위가 아니다. 사이트 설정·정적 페이지에는 있지만 팝업·FAQ에 새로 넣지 않는다(순서 초안용 FAQ의 기존 `beforeunload`는 그대로).
11. **확인 문구 통일**: 팝업·FAQ의 새 확인과 정적 페이지의 기존 확인은 이미 같은 문구다. 사이트 설정(`site-tab.html:242`)의 `window.confirm('저장하지 않은 변경이 있습니다. 버리고 닫을까요?')`만 같은 문구로 바꾼다. 이 문구를 단언하는 시험은 없다(`src` 전체 검색으로 확인) — 시험 수정 불필요. 사이트 설정 변경은 문구 한 줄이라 **팝업 PR에 포함**한다(이 화면의 동작은 바뀌지 않는다).
12. **공통 헬퍼로 묶지 않는다**: 네 화면(사이트 설정·정적 페이지·팝업·FAQ)이 같은 패턴을 각자 구현하게 둔다. 화면마다 폼 구조(상세 도착 시점, 두 폼 모달, 파일 입력)가 달라 추상화가 요청 범위를 넘는다.

## 영향 범위

| 구분 | 변경 |
|---|---|
| 서버 코드·API·권한·DB | 없음 |
| 템플릿 | 팝업 PR: `admin/popup/manage.html` + `admin/site/fragments/site-tab.html`(문구 한 줄) / FAQ PR: `admin/faq/manage.html` |
| 기존 시험 | 정찰 표의 위치는 **후보**일 뿐이다. 구현 때 각 닫기 호출을 읽어 (a) 변경이 남은 채 닫는 것 → 수락 핸들러 추가, (b) 변경 없음·빈 폼·상세 로딩 중·이번 범위 밖 화면(배너·정적 페이지) → 핸들러를 넣지 않고 확인창 0회 단언으로 분류하고, 분류표를 PR 본문에 적는다. 확인된 비대상: `FaqModalBrowserTest:163·182·332`, `PopupModalBrowserTest:131·230`, `ContentScopeAdminBrowserTest:155·210`(R1-2) |
| 신규 시험 | `PopupModalBrowserTest`·`FaqModalBrowserTest`에 케이스 추가(아래) |
| 문서 | `com.cms.admin.faq`·`com.cms.admin.popup` CLAUDE.md에 "미저장 닫기 확인" 한 줄(필요 시) |

## 시험 계획

각 PR이 자기 화면의 시험을 추가한다.

**팝업(`PopupModalBrowserTest`)**
1. 등록 모달에서 제목을 입력하고 [닫기] → 확인창이 뜨고 취소하면 모달·입력 유지, 확인하면 닫힌다. ×도 같다.
2. 등록 모달을 열고 아무것도 입력하지 않은 채 [닫기] → 확인창이 **뜨지 않는다**(`onDialog`로 발생 여부를 센다).
3. **수정 모달을 열고(HTML형, 공백·빈 문단이 정규화되는 본문 포함) 변경 없이 [닫기] → 확인창이 뜨지 않는다**(편집기 정규화 거짓 양성 방지).
4. 수정 모달에서 제목만 바꾸고 변경을 되돌린 뒤 닫기 → 확인 없음(기준값 비교).
5. 이미지 파일 선택·라이브러리 이미지 선택·노출 기간 변경·표시 대상 변경·HTML 본문 입력 각각이 변경으로 인식된다.
6. 저장 성공·삭제 성공으로 닫힐 때 확인창이 뜨지 않는다.
7. 상세 조회가 실패(404)해 모달이 닫힐 때와 상세 조회 중 [닫기]는 확인창이 뜨지 않는다.
8. 라이브러리 선택 모달을 열고 닫아도 폼 모달에 확인창이 뜨지 않는다.

**FAQ(`FaqModalBrowserTest`)**
1. 카테고리 폼·FAQ 폼 각각 입력 후 [닫기]·× → 확인창(취소 시 유지·확인 시 닫힘).
2. 입력 없이 닫기, 수정 모달을 변경 없이 닫기(답변 HTML 정규화 포함) → 확인창 없음.
3. 카테고리 선택·질문·답변·사용 변경이 변경으로 인식되고, 카테고리 폼의 이름·사용·표시 대상 변경도 인식된다.
4. 저장·삭제 성공과 상세 실패·불일치로 닫힐 때 확인창이 없다.
5. 카테고리 PUT 409 뒤 모달 유지 상태에서 [닫기] → 확인창이 뜨고 취소하면 입력이 보존된다.
6. `syncFailed`(응답 유실)로 폼이 잠긴 상태에서도 입력이 있으면 닫을 때 확인한다.
7. 한쪽 종류에서 닫고 다른 종류를 열면 이전 기준값이 남지 않는다(`baseline` 초기화) — **카테고리→FAQ**와 **FAQ→카테고리 수정→무변경 닫기** 양방향(R1-1).
8. 무변경·빈 폼·상세 로딩 중 닫기는 확인창이 **0회**임을 다이얼로그 카운터로 단언한다(카운터 핸들러도 `accept()`를 호출한다 — R1-2). 팝업도 같다.

**변이 실험**: `forceClose` 우회, `baseline`을 채운 직후가 아니라 열기 전에 잡기, 편집기 `isChanged()` 제외, 파일·라이브러리 이미지 제외를 각각 제거/변경해 해당 시험이 실패하는지 확인한다.

## 이번 범위에서 하지 않는 것

배너·게시판 정의 모달, 페이지 이탈(`beforeunload`) 경고 추가, 공통 헬퍼/추상화, 서버 API·권한·DB 변경.
