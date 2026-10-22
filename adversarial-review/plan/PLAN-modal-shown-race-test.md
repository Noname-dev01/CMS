# PLAN — 모달 "표시 후·`shown` 전" 응답 경합 시험

> 작성 2026-10-22 · v1 · 출처: 로드맵 52차 후속 후보(`PLAN-e2e-browser-speedup.md` 1라운드 R1-1)

## Context

FAQ·팝업·정적 페이지 관리 화면은 행을 누르면 모달을 열고(`openModal` → `$(modal).modal("show")`) 곧바로 상세를 조회한다. 상세가 실패하면 `closeModal()`이 모달을 닫는데, Bootstrap 4.6은 **열림 전환 중(`_isTransitioning`)의 `hide()`를 무시**하므로 앱은 상태 `opening`이면 `pendingClose`를 세워 두고 `shown.bs.modal`에서 닫는다.

기존 "즉시 404" 시험 3건(`FaqModalBrowserTest`·`PopupModalBrowserTest`·`PageModalBrowserTest`의 `immediateDetailFailure…`)은 `fetch`를 `Promise.resolve(404)`로 바꿔 **응답이 클릭 직후 마이크로태스크로 도착**하는 구간만 덮는다. 이때 Bootstrap은 아직 배경막 전환(약 150ms) 단계라 모달 요소가 `display:none`·`show` 클래스 없음이다. 실사용에서 서버 응답(수~수십 ms)이 닿는 구간은 그 뒤 — **모달이 화면에 표시되고(`show` 클래스·`display:block`) 대화상자 전환(약 300ms)이 끝나 `shown`이 오기 전** — 일 수 있는데, 이 구간을 결정적으로 재현하는 시험이 없다.

### 정찰에서 확인한 사실 (master `c89dde8`)

- Bootstrap v4.6.0 `_showElement`: `display="block"` → reflow → `addClass("show")` → 대화상자 `transitionend`(또는 `emulateTransitionEnd`의 `setTimeout`) 뒤 `_isTransitioning=false`·`shown.bs.modal` 발생. `hide()`는 `_isTransitioning`이면 반환한다.
- 세 화면 모두 같은 상태 기계다: `modalState` `closed → opening → open → closing`, `closeModal()`이 `opening`이면 `pendingClose = true`(faq `manage.html:788`, popup `manage.html:381`, page `page-tab.html:356`). FAQ·정적 페이지는 `modalEl.dataset.state`에도 상태를 쓰고, 팝업은 전역 `let modalState`만 있다(기존 시험이 `page.waitForFunction("() => modalState === 'closed'")`로 읽음).
- 상세 실패 경로: `await fetch` → `!response.ok` → `await errorMessage(response)`(안에서 `await response.json()`) → `closeModal()` → (404면) `await reload()` → `showPageError`.
- `BrowserTestSupport`는 전환을 기본으로 끄고 `restoreModalTransitions(page)`로 되살린다. 응답 지연 헬퍼 `holdFirstResponse`/`releaseHeldResponse`는 **서버 응답을 받은 뒤** 붙잡으므로, 서버 응답이 `show` 클래스보다 늦게 오면 이 구간을 맞히지 못한다.

## 설계

### 설계 1 — 응답 전달 시점을 브라우저 안에서 고정한다

- **쟁점 1 — 응답을 언제 어떻게 넘길지**: (a) 시험 쪽에서 `waitForFunction("show 클래스")` 뒤 `releaseHeldResponse` 호출, (b) **페이지 안 `MutationObserver`가 모달의 `show` 클래스가 붙는 순간 지연해 둔 404 응답을 넘김**, (c) 고정 대기. → **(b)**. (a)는 Playwright 왕복(수십 ms)과 서버 응답 도착 시점에 따라 `shown` 뒤로 밀릴 수 있어 느린 CI에서 다른 구간을 시험하게 된다. (c)는 규약 위반(고정 대기 0건). `MutationObserver` 콜백은 `addClass("show")`가 일어난 태스크의 끝에서 **마이크로태스크로** 실행되고, `shown`은 대화상자 전환 완료라는 **이후 매크로태스크**에서 오므로 순서가 보장된다.
- **쟁점 2 — 서버 응답 vs 페이지 안 가짜 응답**: 기존 "즉시 404" 시험처럼 상세 `GET`만 페이지 안에서 가짜 404 `Response`로 바꾼다(서버 왕복을 타지 않아 서버 응답 도착 시점과 무관하게 결정적). 가짜 응답의 `json()`은 `holdFirstResponse`와 같은 이유(R2-2 — 네이티브 본문 소비 완료 시점 비보장)로 `Promise.resolve({})`로 덮어써, 앱의 후속(`errorMessage` → `closeModal`)이 마이크로태스크만으로 끝나 **`shown` 전에 `closeModal`이 실행됨**을 보장한다.
- **쟁점 3 — 그 구간에 실제로 넘겼다는 양성 증거**: 넘기는 순간 페이지에 `{ showClass, displayBlock, shownCount }`를 기록한다. `shownCount`는 시험이 단 `shown.bs.modal` 리스너가 센 횟수다(넘길 때 0이어야 한다). 상세 `fetch`가 불린 시점에 이미 `show` 클래스가 있었다면(구간을 놓침) 기록해 단언이 실패하게 한다 — 조용히 통과하지 않는다.
- **쟁점 3-2 — 닫기가 `pendingClose` 경로로 일어났다는 증거(v2, R1-2)**: 응답 전달 시점만으로는 앱의 `closeModal()`이 `shown` 전에 실행됐다는 보장이 없다(예: 본문 소비가 `shown` 뒤로 밀리면 일반 `open → hide` 경로로 닫혀도 판정이 통과한다). 그래서 **`Modal.prototype.hide`(`$.fn.modal.Constructor.prototype.hide`) 호출을 기록**하고, 각 호출이 **`jQuery.fn.trigger`로 `shown.bs.modal`을 발생시키는 동안**(Bootstrap 4.6은 전환 완료 콜백에서 `$(element).trigger(shownEvent)`로 동기 발생시키고, 앱의 `shown` 핸들러가 `pendingClose`면 그 안에서 `modal("hide")`를 부른다) 일어났는지 표시한다. 단언: `hide` 호출이 1회 이상이고 **모두 `shown` 발생 구간 안**이다. 열림 전환 중 무시되는 `hide`(직접 경로 회귀)나 `shown` 이후의 일반 `hide`(본문 지연)는 구간 밖 호출로 잡힌다.
- **판정**: 모달이 결국 `closed`가 되고(`pendingClose` 경로가 아니면 Bootstrap이 `hide()`를 무시해 `open`에 멈춰 시간 초과), 페이지 상단 안내가 보이고, 미저장 확인창이 뜨지 않는다 — 기존 즉시 404 시험과 같은 판정에 위 증거 단언 두 가지를 더한다.
- **순서 보장의 전제(v2 정정)**: `MutationObserver` 마이크로태스크가 `shown`보다 먼저인 것은 모달에 `fade` 클래스가 있어 Bootstrap이 전환 완료를 `transitionend`/`emulateTransitionEnd`(`setTimeout`)라는 **이후 매크로태스크**로 미루기 때문이다. `fade` 클래스가 없으면 Bootstrap은 완료 콜백을 같은 호출 스택에서 실행한다(`bootstrap.bundle.js` `_showElement`). 현재 `BrowserTestSupport`는 `fade` 클래스를 지우지 않고 CSS 전환만 끄므로 꺼진 상태에서도 순서는 성립하지만, 시험은 실사용 전환 시간을 위해 전환을 켠다.

### 설계 2 — 공용 헬퍼 하나 + 화면별 시험 3건

- `BrowserTestSupport`에 헬퍼 두 개:
  - `failDetailWhenModalShows(Page page, String urlPattern, String modalSelector)` — 위 가짜 응답 지연·`MutationObserver`·`shown` 카운터·기록, 그리고 `hide` 호출 기록(`shown` 발생 구간 표시)을 설치한다(같은 문서에서 한 번).
  - `assertClosedThroughPendingClose(Page page)` — 기록이 있고 `showClass && displayBlock && shownCount == 0`이며 "구간 놓침" 표지가 없고, `hide` 호출이 1회 이상이며 모두 `shown` 발생 구간 안임을 단언한다. 모달이 `closed`가 된 **뒤에** 부른다.
- **기존 즉시 404 스텁 3곳 정정(v2, R1-1)**: 가짜 응답의 `json()`을 `Promise.resolve({})`로 덮어써, 클릭부터 `closeModal()`까지가 클릭 태스크의 마이크로태스크 안에서 끝나게 한다 — 네이티브 본문 소비가 밀리면 기존 시험도 표시 후 구간이나 `shown` 뒤로 넘어가 "앞 구간(배경막 전환 중)을 덮는다"는 주장이 성립하지 않는다. 한 줄 추가이며 공용화는 하지 않는다.
- **쟁점 4 — 헬퍼로 둘지, 시험마다 인라인으로 쓸지**: 기존 즉시 404 시험은 인라인 `page.evaluate`다. 이번 것은 관찰자·카운터·기록까지 있어 세 번 복제하면 한쪽만 고쳐지는 표류가 생긴다 → **헬퍼**. 기존 인라인 3건은 이번 범위가 아니라 그대로 둔다(무관한 리팩터링 금지).
- 화면별 새 시험(기존 즉시 404 시험 옆): `detailFailureWhileModalShowingStillCloses…`(FAQ는 재조회 뒤 안내 유지까지 기존 시험과 같은 판정). 시험마다 `restoreModalTransitions(page)`를 부른다 — 전환이 꺼져 있어도 순서 보장은 성립하지만(`emulateTransitionEnd`도 `setTimeout`) 시험 목적이 실사용 전환 시간이므로 켠다(R1-1 결정과 일치).
- **기존 즉시 404 시험은 유지**한다 — 앞 구간(배경막 전환 중)을 덮는 유일한 시험이다.

### 설계 3 — 검출력 확인(변이 실험, 커밋하지 않음)

| 변이 | 기대 |
|---|---|
| M1 세 화면 각각 `closeModal()`의 열림 판정을 `modalState` 대신 `modalEl.classList.contains("show")`로 바꿈(표시됐으면 열린 것으로 보고 바로 `hide()`) | **새 시험만 실패**(모달이 `open`에 멈춤), 기존 즉시 404 시험은 통과(그 구간엔 `show` 클래스가 없어 지연 경로) — 새 시험의 고유 검출력 |
| M2 FAQ `shown.bs.modal`의 `pendingClose` 처리 제거 | 새 시험·기존 즉시 404 시험 모두 실패 |
| M3(시험 쪽) 헬퍼가 `show`를 기다리지 않고 즉시 넘기게 바꿈 | `assertClosedThroughPendingClose`의 전달 시점 단언이 실패(구간 이탈을 잡는다) |
| M4(시험 쪽, R1-2 반례) 헬퍼의 `json()`을 `shown.bs.modal`에서 해결되는 Promise로 바꿈 | 모달은 일반 경로로 닫히지만 `assertClosedThroughPendingClose`의 `hide` 구간 단언이 실패 |

결과(실패한 단언·소요)를 구현 결과에 기록하고 변이는 되돌린다.

### 설계 제약

- 시험 코드만 바꾼다. 앱 코드·스키마·인가·`SecurityConfig` 변경 없음(앱 결함이 드러나면 멈추고 보고).
- `src/test/java/CLAUDE.md` 브라우저 속도 규약: 고정 `waitForTimeout` 0건, 대기는 `waitForFunction`·자동 대기, 부정 판정은 양성 증거 뒤. `waitForFunction` 인자로 `long`을 넘기지 않는다.
- 새 시험은 `BrowserTestSupport`를 상속한 기존 클래스에 둔다(CI `browser-test` job).

## 작업 단계

1. master 기준 브랜치 `test/modal-shown-race`(로컬의 커밋 전 로드맵 53차 변경은 스테이징하지 않는다).
2. `BrowserTestSupport` 헬퍼 2개 → 컴파일.
3. 세 화면 시험 추가 → 세 클래스 실행.
4. 변이 M1~M4 실험 → 실패한 단언 기록 → 되돌림(`git diff`로 앱 코드 무변경 확인).
5. `src/test/java/CLAUDE.md` 브라우저 규약의 "아직 없다" 문장 갱신.
6. 공용 시험 지원 코드 변경이라 전체 `./gradlew test`(code-review-loop 마무리).

## 리스크

| 리스크 | 대응 |
|---|---|
| 순서 가정(`MutationObserver` 마이크로태스크 < `shown` 매크로태스크)이 틀림 | 증거 단언(`shownCount == 0`, `show` 클래스)이 매 실행 검증, M3로 증거 단언의 검출력 확인 |
| 상세 `fetch`가 `show` 뒤에 불리는 경우(구간 놓침)가 조용히 통과 | "구간 놓침" 표지 단언 |
| 브라우저 시험 시간 증가 | 시험 3건, 각 약 1초 예상(측정해 기록) |

## 범위 밖

- 기존 즉시 404 시험 3건의 인라인 스텁을 헬퍼로 옮기기.
- 닫힘 전환 중(`closing`)의 재진입 경합.

## 개정 이력

- v1 (2026-10-22): 최초 작성.
- v2 변경(1라운드 — needs-attention, P2×2):
  - R1-1(P2 기존 즉시 404 스텁은 네이티브 `json()`이라 앞 구간 결정성 미입증): 설계 2에 기존 스텁 3곳의 `json()` 즉시 해결 덮어쓰기 추가(공용화는 범위 밖 유지). M1의 "기존 시험 통과" 기대는 이 정정 뒤에야 근거가 있다.
  - R1-2(P2 전달 시점 증거만으로는 `closeModal()`이 `shown` 전에 실행됐는지 모름): 쟁점 3-2 추가 — `Modal.prototype.hide` 호출을 기록하고 모두 `shown` 발생 구간 안인지 단언. 헬퍼 이름을 `assertClosedThroughPendingClose`로 바꾸고 변이 M4(본문을 `shown`에서 해결) 추가.
  - 보충(fade 전제): "전환을 꺼도 순서 성립"은 현재 지원 코드가 `fade` 클래스를 유지하고 CSS 전환만 끄기 때문이라고 정정.
- v3 변경(2라운드 — **ship**, 비차단 P3×1): R2-1(P3 작업 단계가 M1~M3로 남아 M4 미실행 위험) — 작업 단계 4를 "M1~M4 실험 → 실패한 단언 기록"으로 정정.

## 구현·검증 결과 (2026-10-22)

### 구현 파일 (시험 코드만, 앱·스키마·인가 변경 없음 — `git diff -- src/main` 0줄)
- `BrowserTestSupport`: `failDetailWhenModalShows`(상세 `GET`을 가짜 404로 바꿔 두고 `MutationObserver`가 `show` 클래스가 붙는 순간 넘김, `shown.bs.modal` 횟수·`Modal.prototype.hide` 호출의 "shown 발생 안" 표지를 `window.__race`에 기록)·`assertClosedThroughPendingClose`(전달 시점·구간 놓침·`hide` 구간 단언).
- 새 시험 3건: `FaqModalBrowserTest`·`PopupModalBrowserTest`·`PageModalBrowserTest`의 `detailFailureWhileModalShowingClosesThroughPendingClose`(전환을 켠 채, 각 약 1.7초).
- 기존 "즉시 404" 스텁 3곳에 `json()` 즉시 해결 덮어쓰기 추가(리뷰 R1-1).
- `src/test/java/CLAUDE.md` 브라우저 규약: "아직 없다" 문장을 새 시험·헬퍼·순서 보장 전제(모달 `fade` 클래스 유지)로 갱신.

### 검증 결과
- 세 클래스(FAQ 19건·팝업 15건·정적 페이지 12건) 통과, 건너뜀 0, **3회 연속 통과**(`cleanTest`로 매번 새로 실행).
- 변이 실험(커밋하지 않음, 모두 되돌림 — 앱·헬퍼 diff 0 확인):

  | 변이 | 결과 |
  |---|---|
  | M1 FAQ `closeModal()`이 `show` 클래스면 열린 것으로 보고 바로 `hide()` | **새 시험만** 실패(19건 중 1건) — `waitModalState("closed")` 15초 시간 초과(열림 전환 중 `hide()`를 Bootstrap이 무시해 `open`에 멈춤). 기존 즉시 404 시험은 통과 → 새 시험의 고유 검출력 |
  | M2 FAQ `shown` 핸들러의 `pendingClose` 처리 제거 | 새 시험·기존 즉시 404 시험 **둘 다** 실패(19건 중 2건) |
  | M3 헬퍼가 `show`를 기다리지 않고 즉시 전달 | 증거 단언 "표시 중(display:block)이었다" 실패 |
  | M4(리뷰어 반례) 헬퍼 `json()`을 `shown.bs.modal`에서 해결 | 모달은 닫히지만 단언 "모든 hide() 호출이 shown.bs.modal 발생 안에서 일어났다" 실패 |
- 실기: 새 시험이 실제 서버(`RANDOM_PORT`)·Testcontainers DB·실제 Chromium으로 돈다. 앱 화면 변경이 없어 별도 화면 확인·스크린샷은 해당 없음.

### 이슈
- 없음. 계획과 달라진 결정 없음(리뷰 반영분은 v2·v3에 이미 반영).
- 수용한 한계: 순서 보장은 모달에 `fade` 클래스가 있을 때의 Bootstrap 4.6 동작에 기댄다(증거 단언이 매 실행 검증).

### 후속
- 기존 "즉시 404" 인라인 스텁 3건을 헬퍼로 옮기기, 닫힘 전환 중(`closing`) 재진입 경합은 범위 밖으로 남긴다.
- 전체 `./gradlew test`는 공용 시험 지원 코드를 바꿨으므로 `code-review-loop` 마무리에서 1회 실행한다.
