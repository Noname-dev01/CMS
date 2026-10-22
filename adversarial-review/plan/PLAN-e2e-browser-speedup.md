# PLAN — 브라우저 E2E 시험 속도 개선

> 상태: **구현 완료(2026-10-22)** — v3, 적대적 리뷰 3라운드 **ship**(codex gpt-6.1-sol, 1라운드 P2×4·2라운드 P2×2 반영, 3라운드 새 지적 없음). 테스트 시간 단축 작업(#166 tmpfs, #167·#168 기준 스키마 합치기)의 다음 단계. 앱 코드(`src/main`)는 바꾸지 않는다 — 시험 지원 코드와 시험만 바꾼다.

## 개정 이력

- 3라운드: codex gpt-6.1-sol **ship — 새 지적 없음**. 로그인 성공 목적지 `/admin` 고정, A 묶음 `hidePrevented` 동기 발생과 취소되지 않은 `hide()`의 동기 `.show` 제거, E·F 묶음의 동기·마이크로태스크 설명을 코드와 대조해 확인. 요청 장벽의 이벤트 순서와 전환 끈 시험의 검출력은 구현 때 변이 실험으로 확인한다.
- v3 변경(2라운드 — codex gpt-6.1-sol, `needs-attention` P2×2):
  - R2-1(P2 로그인 실패 경로 — 실패 핸들러·성공 핸들러의 상태 재검증 실패는 `/admin/login-error`로 보내 v2의 "`/admin/login`이 아니면 성공" 판정을 통과한다, `LockingAuthenticationFailureHandler:38`·`VisitLoggingAuthenticationSuccessHandler:131`): 설계 2 변경 — 성공은 "302이고 Location의 경로가 정확히 `/admin`"(`setAlwaysUseDefaultTargetUrl(true)`로 고정된 성공 목적지, 같은 파일 :51-52).
  - R2-2(P2 F 묶음의 `setTimeout(0)` 두 번은 네이티브 `json()` 본문 소비 완료를 보장하지 않음): 설계 4 F 변경 — 보관한 응답 객체의 `json`을 인스턴스에서 덮어써 미리 파싱한 값을 `Promise.resolve`로 돌려준다. 앱 후속(`await fetch` → `await response.json()` → 폼 갱신)이 마이크로태스크만으로 이어지므로, 해제 Promise를 `setTimeout(0)` 한 번 뒤에 해결하면 그 전에 마이크로태스크가 전부 비워진다. 세 화면의 상세 처리 후속에 다른 매크로태스크 대기가 없음을 구현 때 코드로 확인해 기록한다.
- v2 변경(1라운드 — codex gpt-6.1-sol, `needs-attention` P2×4):
  - R1-1(P2 즉시 404 변이로 전환 끄기를 정하면 "표시 후·`shown` 전" 구간 보호를 보장 못 함): 설계 3 변경 — 세 화면의 "열림 전환 중 404" 시험은 변이 결과와 무관하게 **항상 전환을 켠다**(시험 목적이 전환 자체). "표시 후·`shown` 전 응답" 구간 시험은 지금도 없어 이번 변경으로 검출력이 줄지 않는다 — 신규 시험은 범위 밖 후속 후보로 기록.
  - R1-2(P2 B 묶음 변이가 장벽을 검증하지 못함 — `form` 속성 제거는 제출 처리 자체를 막아 요청이 생기지 않는다): 설계 4 변경 — 장벽 검증 변이는 폼 연결을 유지한 채 저장 버튼 `click`에 쓰기 `fetch`를 추가로 보내는 처리를 넣는다(포커스 이동은 그대로, 요청만 발생). 실패한 단언이 **장벽 직후 요청 수 단언**인지 확인해 기록한다. `form` 속성 제거는 포커스 검출용 변이로 따로 둔다. 변이 검출이 이벤트 순서의 일반적 보장은 아니므로 R1은 위험으로 남긴다.
  - R1-3(P2 G 묶음 변이 부적합 — `reload()`는 원래 성공 시 안내를 지우고, 보존은 상세 실패 경로가 `await reload()` 뒤에 `showPageError`를 부르기 때문): 설계 4 변경 — 변이를 "`showPageError`를 `await reload()` 앞으로 옮김"으로.
  - R1-4(P2 고정 대기 누락 — 리뷰는 "33곳·FAQ 13곳"이라 했으나 `grep` 결과 FAQ 11곳·전체 31곳이 맞다(리뷰가 든 442·480줄은 클릭 줄, 대기는 444·482줄). 다만 444·482가 설계 표에서 빠진 것은 사실): 설계 4에 묶음 I 추가 — 앱의 카테고리 존재 검사가 요청 없이 막음, 장벽 대체 + 검사 뒤 `return` 제거 변이.

## Context

tmpfs 뒤 전체 `./gradlew test`는 약 8분 20초이고 클래스 시간 합 약 380초 중 **브라우저 E2E 15개 클래스(98건)가 약 254초**로 가장 크다(2026-10-22 전체 실행). 모달 화면 3개(FAQ 68초·팝업 44초·정적 페이지 30초)가 절반 이상이다.

### 측정 (2026-10-22, `FaqModalBrowserTest` 18건 단독 실행, 기준 74초)

`BrowserTestSupport`에 임시 계측을 넣어 쟀다(측정 후 되돌림).

| 항목 | 측정값 | 스위트 전체 추정 |
|---|---|---|
| 모달 `fade` 전환 | 테스트 쪽에서 CSS로 전환을 끄자 **74초 → 36초**, 18건 모두 통과 | 가장 큼(모달 화면 위주) |
| 실제 폼 로그인 `loginAsAdmin` | 18회 15.3초(평균 0.85초, 최대 1.7초). 로그인 화면 렌더 약 0.43초 + 제출 후 대시보드 렌더 약 0.38초. 서버 응답 자체는 각 100ms 미만 — 새 컨텍스트(캐시 없음)에서 두 화면의 정적 자원·스크립트를 통째로 읽는 비용 | 98회 × 약 0.7초 ≈ 60초 |
| 브라우저 기동 `Playwright.create()` + `launch` | 클래스당 2.6초 | 15클래스 ≈ 36초(1회만 남기면 약 34초 절감) |
| 고정 대기 `waitForTimeout` | 이 클래스 11곳 합계 6.4초 | 7개 클래스 31곳 ≈ 17초 |
| `browser.newContext` | 회당 3ms | 무시 |
| Spring 컨텍스트 기동 | 11초(클래스 첫 시험에 포함) | 범위 밖 — 컨텍스트 캐시는 이미 공유되고 있다 |

### 확정 결정 (사용자, 2026-10-22)

| 항목 | 결정 |
|---|---|
| 범위 | **네 가지 모두** — 전환 끄기·로그인·브라우저 공유·고정 대기 제거를 한 PR에 |
| 전환 의존 시험 | **기본은 끄고, 전환 자체가 시험 조건인 시험만 켠다** |
| 로그인 | **HTTP 폼 제출로 변경** — 화면 렌더 없이 실제 로그인 엔드포인트·BCrypt 검증은 그대로 거친다 |

### 정찰 요약

| 사실 | 근거 |
|---|---|
| 테스트 JVM은 1개이고 JUnit 병렬 실행이 꺼져 있다 — 시험 클래스가 한 스레드에서 차례로 돈다 | `build.gradle` `maxParallelForks = 1`·`forkEvery = 0`, `junit-platform.properties` 없음 |
| `loginAsAdmin` 호출처 14곳은 모두 로그인 직후 목적 화면으로 `navigate`한다 — 로그인 뒤 대시보드에 머무는 것을 전제로 한 시험이 없다 | `grep -rn "loginAsAdmin("` |
| 로그인 폼은 `POST /admin/login`(`username`·`password`·`_csrf`), CSRF는 모든 경로에 활성 | `templates/admin/login.html:46`, `SecurityConfig:182-183` |
| 관리 화면 모달은 모두 Bootstrap 4.6 `fade` + `data-backdrop="static" data-keyboard="false"`다. 4.6은 정적 백드롭 클릭·ESC를 거부할 때 `hidePrevented.bs.modal`을 동기적으로 발생시킨다 | `templates/admin/{banner,board,faq,popup}/manage.html`, `page-tab.html:63`, `site-tab.html:57`, `vendor/bootstrap/js/bootstrap.bundle.min.js`(v4.6.0) |
| Bootstrap 4.6은 show/hide 때마다 `getTransitionDurationFromElement`로 **그 시점의** CSS 전환 시간을 읽는다 — 전환 시간이 0이어도 `emulateTransitionEnd(0)`(setTimeout)로 비동기 완료한다. 시험 도중 스타일을 되돌리면 그다음 show/hide부터 원래 전환(약 300ms)이 적용된다 | Bootstrap 4.6 `Modal._showElement`·`hide` |
| FAQ 화면: 행 클릭 → `startFaqEdit`/`startCategoryEdit`가 가드를 동기 검사한 뒤 `openModal`이 `data-state="opening"`을 동기적으로 쓴다. "열리지 않음"은 클릭 직후 `data-state === "closed"`로 결정적으로 단언할 수 있다 | `templates/admin/faq/manage.html:748-761, 836-905` |
| 정적 페이지 화면: 목록 응답 처리 `loadList`는 `renderList()`(`pageList.replaceChildren()`)까지 동기 연속이고, 호출자 `await loadList()`의 후속(잠금 해제 여부)은 그 직후 마이크로태스크에서 돈다 | `templates/admin/page/fragments/page-tab.html:270-271, 494-516, 568-606` |
| 고정 대기 31곳 — Faq 11·Page 6·Popup 5·Site 3·Banner 2·Board 2·FaqAdminOrder 2(50ms 폴링 루프) | `grep -rn waitForTimeout src/test/java` |
| `src/test/java/CLAUDE.md`: 대기는 고정 sleep 대신 `waitForFunction`·자동 대기, 부정 단언은 "브라우저가 동기적으로 막는 지점"을 증거로 삼는다 | 해당 파일 "브라우저 기본 동작·화면 JS" 항목 |

## 설계

### 1. 브라우저 공유 — JVM당 1회 기동

`BrowserTestSupport`의 `@BeforeAll`/`@AfterAll`(클래스마다 기동·종료)을 **지연 초기화 static 싱글턴 + JVM 종료 훅**으로 바꾼다.

- 첫 `newContext` 호출 때 `Playwright.create()` + `chromium().launch(headless)`. 이후 클래스는 같은 `Browser`를 쓴다.
- 종료는 `Runtime.getRuntime().addShutdownHook`에서 `browser.close()` → `playwright.close()`. 종료 훅이 돌지 못해도(강제 종료) Playwright 드라이버는 부모 프로세스 종료 시 함께 끝난다 — 지금도 `@AfterAll`이 못 돌면 같은 상황이다.
- 격리는 지금처럼 **시험마다 새 `BrowserContext`**(쿠키·저장소·캐시 분리)로 유지한다. 브라우저 프로세스만 공유한다.
- 스레드: Playwright Java 객체는 스레드 안전하지 않다. JUnit 병렬 실행이 꺼져 있어 모든 시험이 같은 스레드에서 돈다(정찰). 다른 스레드에서 쓰면 즉시 알 수 있도록, 처음 만든 스레드와 다른 스레드에서 `newContext`가 호출되면 `IllegalStateException`을 던진다 — 나중에 병렬 실행을 켜는 사람이 조용한 경합 대신 명확한 실패를 보게 한다.
- `BrowserTestSupport` Javadoc의 "브라우저는 테스트 클래스당 하나" 서술과 `src/test/java/CLAUDE.md`의 같은 서술을 고친다.

### 2. 로그인 — 화면 렌더 없는 HTTP 폼 제출

`loginAsAdmin(Page page, int port, Member member)`의 시그니처는 유지하고(호출처 14곳 무변경) 내부만 바꾼다.

1. `page.context().request()`(`APIRequestContext` — 브라우저 컨텍스트와 쿠키 저장소를 공유한다)로 `GET /admin/login` → 응답 HTML에서 `name="_csrf"` 입력의 `value`를 정규식으로 꺼낸다(못 찾으면 `IllegalStateException`).
2. 같은 요청 컨텍스트로 `POST /admin/login`에 `username`·`password`·`_csrf`를 `application/x-www-form-urlencoded`로 보내고 **리다이렉트는 따라가지 않는다**(`setMaxRedirects(0)`).
3. 응답이 302이고 `Location`의 경로가 **정확히 `/admin`**(성공 핸들러의 고정 목적지)인지 확인한다 — 실패는 `/admin/login-error`로 간다(R2-1). 아니면 `IllegalStateException`(상태·Location 포함) — 로그인 실패가 다음 화면의 엉뚱한 시간 초과로 번지지 않게 한다.

지키는 것: 실제 로그인 필터·`CustomUserDetailsService`·BCrypt 검증·로그인 성공 핸들러(방문 기록 포함)·세션 고정 보호·CSRF 회전. 로그인 뒤 화면들은 새 세션의 새 CSRF 토큰을 각자 렌더한다.
잃는 것: 로그인 **화면** UI(폼 마크업·버튼) 자체의 브라우저 검증 — 이 15개 클래스의 시험 대상이 아니다. 로그인 화면의 서버 측 계약은 기존 MockMvc·통합 시험이 맡는다(구현 때 해당 시험 존재를 확인해 완료 보고에 적는다).

### 3. 모달 전환 끄기 — 기본 끔, 전환 의존 시험만 켬

`newContext(...)`가 컨텍스트마다 `addInitScript`로 다음을 넣는다.

```js
document.addEventListener("DOMContentLoaded", () => {
  const s = document.createElement("style");
  s.id = "__test-no-transitions";
  s.textContent = ".fade, .modal.fade .modal-dialog { transition: none !important; }";
  document.head.appendChild(s);
});
```

- 대상은 Bootstrap의 `fade` 계열뿐이다(모달·백드롭·알림). 앱의 JS·상태 기계(`data-state` opening/open/closing/closed, `pendingClose`)는 그대로 돈다 — Bootstrap은 전환 시간 0이어도 `emulateTransitionEnd(0)`로 **비동기** 완료하므로 "열림 중"·"닫힘 중" 상태는 짧게나마 여전히 존재한다.
- 전환을 다시 켜는 헬퍼 `restoreModalTransitions(Page page)`: 현재 문서의 `#__test-no-transitions`를 지운다. 초기화 스크립트는 다음 `navigate`에서 다시 넣으므로 **화면 이동 뒤에 호출**해야 한다(Javadoc에 명시).
- **항상 켜는 시험**: 세 화면(FAQ·정적 페이지·팝업)의 "상세 조회가 즉시 404로 실패해도(열림 전환 중) 모달이 결국 닫힌다" — 시험 목적이 전환 중 동작이므로 변이 결과와 무관하게 켠다(R1-1). 이 시험은 응답이 첫 비동기 전환 완료보다 먼저 오는 구간만 덮는다 — "모달은 표시됐으나 `shown` 전" 구간 응답 시험은 지금도 없으며 후속 후보다.
- 나머지 후보의 결정: 아래 후보마다 **전환을 끈 상태에서** 보호 대상 앱 코드를 망가뜨리는 변이 실험을 한다. 끈 상태로도 실패하면 끈 채로 두고, 통과(검출 상실)하면 그 시험만 `restoreModalTransitions`를 부른다.

| 후보 시험 | 보호 대상(변이) |
|---|---|
| 세 화면의 "상세 조회 중 모달을 닫아도 … 늦게 온 응답이 새 폼을 덮어쓰지 않는다" | 상세 응답의 순번 검사(`seq !== detailSeq`) 제거 |
| `MediaPickerNestedModalBrowserTest` 2건 | 중첩 모달 닫힘 뒤 `modal-open`·포커스 복원 처리 제거(구현 때 `media-picker.js`에서 해당 코드를 특정) |

변이 실험 결과(켠 시험·끈 채로 둔 시험과 그 근거)는 이 문서와 `src/test/java/CLAUDE.md`에 기록한다.

### 4. 고정 대기 31곳 제거

원칙: 고정 시간 대신 **양성 증거**(일어나야 할 일이 일어났다는 신호)를 기다린 뒤 단언한다. 묶음별 대체:

| 묶음 | 위치 | 지금 | 대체 |
|---|---|---|---|
| A. ESC·배경 클릭으로 닫히지 않음 | Banner 124·Board 104·Faq 186·Page 135·Popup 162·Site 103 | ESC·클릭 뒤 500ms 후 `.show` 1개 | 열기 전에 모달 요소에 `hidePrevented.bs.modal` 카운터를 단다. ESC·배경 클릭 뒤 **카운터 = 2**를 `waitForFunction`으로 기다린다(두 입력이 모두 Bootstrap에 도달해 거부됐다는 양성 증거) → `.show` 1개 단언. 전환이 꺼져 있어 거부되지 않고 `hide()`가 불렸다면 `.show`는 `hide()` 안에서 동기적으로 빠진다 |
| B. 폼 검증이 제출을 막음 | Banner 146·Board 126·Page 155·Popup 184·Site 132·Faq 216 | 클릭 뒤 500ms 후 포커스·요청 0 | 포커스 증거(첫 무효 입력으로 이동, 이미 있음)는 유지. 요청 0 단언은 **요청 장벽** 뒤로 옮긴다: 클릭 뒤 페이지에서 장벽 요청(`fetch('/favicon.ico?__barrier=n')`)을 보내고 `page.waitForRequest`로 그 요청이 Java에 도달할 때까지 기다린 다음 쓰기 요청 수를 단언한다. 앱 제출 처리가 동기 연속에서 `fetch`를 불렀다면 그 요청이 장벽보다 먼저 발행된다(**검증 필요 — 아래 위험 R1**) |
| C. 확인창 취소 뒤 모달 유지 | Faq 503·509·520·Popup 315·322·Page 200·Site 165 | 클릭 뒤 500ms | `confirm()`은 클릭 처리 안에서 동기적으로 뜨고, 확인창 핸들러가 닫을 때까지 `page.click`이 돌아오지 않는다. 클릭이 돌아온 시점에 취소 결과가 확정돼 있으므로 대기를 지우고 바로 단언한다. 확인창 수 단언은 `page.waitForCondition(() -> dialogMessages.size() == n)`로 바꿔 순서 가정에 기대지 않는다 |
| D. 가드가 모달 열기를 막음 | Faq 314·406 | 클릭 뒤 500ms 후 `data-state` | 가드와 `openModal`의 `data-state="opening"` 기록이 클릭 처리 안에서 동기적이다(정찰). 대기를 지우고 클릭 직후 `closed` 단언 |
| E. 요청 보류 해제 뒤 상태 유지 | Page 269·307 | 목록 GET `resume()` 뒤 500ms 후 잠금 단언 | 해제 전에 `#pageList`에 표지 자식 요소를 넣고, 해제 뒤 표지가 사라질 때까지(`renderList`의 `replaceChildren`) `waitForFunction`. 호출자의 후속(잠금 해제 여부)은 그 직후 마이크로태스크에서 끝나 폴링보다 먼저다(정찰) → 잠금 단언 |
| F. 늦게 온 응답이 폼을 덮지 않음 | Faq 378·Page 373·Popup 274 | 첫 상세 조회를 `setTimeout(1500)`으로 늦추고 2000ms 대기 | 지연을 시험이 쥔 해제 함수로 바꾼다: 느린 응답을 원래 fetch로 미리 받아 본문을 파싱해 두고, 그 응답 객체의 `json`을 인스턴스에서 덮어써 파싱한 값을 `Promise.resolve`로 돌려주게 한 채 보관한다. 두 번째 폼이 열린 뒤 `page.evaluate`로 해제한다. 해제가 돌려주는 Promise는 앱에 응답을 넘긴 뒤 `setTimeout(0)` **한 번** 뒤에 해결된다 — 앱 후속이 마이크로태스크만으로 이어지므로 그 전에 끝난다(R2-2) → 덮어쓰기 없음 단언. 장벽의 충분성은 변이(순번 검사 제거)로 확인한다 |
| G. 재조회 뒤 안내 유지 | Faq 353 | `waitUntilIdle` 뒤 300ms | `reload()`는 `busy = false` 뒤 같은 동기 연속에서 안내를 지운다(`faq/manage.html:436-440`). `waitUntilIdle`(`btnReload` 활성)이 통과한 시점이면 그 연속은 끝났으므로 대기를 지운다(구현 때 `sync()` 호출 위치로 재확인) |
| H. 요청 가로채기 폴링 | FaqAdminOrder 270·298 | 50ms × 최대 100회 루프 | `page.waitForCondition(() -> held.get() != null)` — 시간은 거의 같고 관용구를 맞춘다 |
| I. 앱 검사가 제출을 막음(삭제된 카테고리 선택) | Faq 444·482 | 안내 표시 뒤 300ms 후 쓰기 요청 0 | 안내 표시와 `return`은 제출 처리의 같은 동기 연속이다(`faq/manage.html:980-985`). 안내를 기다린 뒤 B와 같은 요청 장벽을 거쳐 요청 수를 단언한다 |

모든 대체는 **변이 실험**으로 검출력을 확인한다 — 묶음마다 대표 1곳 이상에서 보호 대상 앱 코드를 망가뜨려 시험이 실패하는지 본다(A: `data-backdrop` 제거, B: ① 저장 버튼의 `form` 속성 제거(포커스 단언이 잡는다) ② 폼 연결을 유지한 채 저장 버튼 `click`에 쓰기 `fetch` 추가(장벽 직후 요청 수 단언이 잡아야 한다 — 실패한 단언을 기록), C: 확인창 취소 시 `preventDefault` 제거, D: 가드 제거, E: 이전 요청의 후속이 잠금을 푸는 회귀, F: 순번 검사 제거, G: 상세 실패 경로의 `showPageError`를 `await reload()` 앞으로 옮김, I: 카테고리 존재 검사 뒤 `return` 제거(장벽 직후 요청 수 단언이 잡아야 한다)).

### 5. 문서

- `src/test/java/CLAUDE.md` 브라우저 항목: 브라우저 공유(JVM당 1회, 스레드 가드), HTTP 로그인, 전환 기본 끔과 `restoreModalTransitions`, 묶음별 대기 대체 관용구(`hidePrevented` 카운터·요청 장벽·표지 요소·해제 함수), 변이 실험 결과.
- `BrowserTestSupport` Javadoc.
- 비자명한 사실을 발견하면 `docs/troubleshooting.md`(테스트 카테고리).

## 위험

- **R1 요청 장벽의 순서 보장**: Chromium에서 앱 `fetch`의 `request` 이벤트와 장벽 `fetch`의 `request` 이벤트가 발행 순서대로 Java에 도착하는지 확실하지 않다(둘 다 같은 페이지의 네트워크 이벤트라 같은 순서로 전달될 것으로 보이나 미확인). 구현 때 "검증을 우회하는 제출" 변이로 B 묶음 시험이 반드시 실패하는지 확인하고, 순서가 보장되지 않으면 B는 포커스 증거만 남기고 요청 0 단언에 짧은 대기를 남기는 쪽을 사용자에게 묻는다.
- **R2 전환 끄기로 가려지는 회귀**: 실사용자는 300ms 전환을 겪는다. 전환 중 경합 버그는 전환을 켠 시험(설계 3)만 잡는다. 켤 대상은 변이 실험으로 정하지만, 지금 시험이 없는 새 전환 경합은 앞으로도 전환을 켠 시험을 따로 써야 잡힌다 — `src/test/java/CLAUDE.md`에 명시한다.
- **R3 공유 브라우저의 상태 누수**: 컨텍스트는 시험마다 새로 만들고 닫으므로 쿠키·저장소·캐시는 새지 않는다. 브라우저 프로세스가 도중에 죽으면 그 뒤 모든 클래스가 실패한다(지금은 그 클래스만) — 실패 메시지가 명확하므로 수용.
- **R4 CI 환경 차이**: CI는 느리다. 고정 대기를 없애면 시간 여유가 아니라 신호를 기다리므로 오히려 덜 불안정해야 한다. 기본 대기 15초는 유지한다.

## 완료 기준

1. 브라우저 E2E 15개 클래스 전부 통과, 전체 `./gradlew test` 통과.
2. 브라우저 E2E 클래스 시간 합이 **254초 → 120초 이하**(측정 근거: 전환 끄기만으로 FAQ 74→36초, 로그인·기동·대기 절감 합 약 110초). 전·후 클래스별 시간을 PR에 표로 남긴다.
3. 설계 3·4의 변이 실험 결과(변이·실패한 시험 수)를 이 문서에 기록하고, 검출력이 떨어진 곳이 없다.
4. `grep -rn waitForTimeout src/test/java` 결과 0건(R1로 사용자가 짧은 대기를 남기기로 하면 그 곳만 예외로 기록).
5. 같은 클래스를 3회 연속 실행해 불안정 실패가 없다(FAQ·팝업·정적 페이지 모달 클래스).

## 구현 결과 (2026-10-22)

### 시간 (브라우저 E2E 15개 클래스, `./gradlew test --tests '*BrowserTest'`, 98건 전부 통과)

| 클래스 | 전(초) | 후(초) |
|---|---|---|
| FaqModalBrowserTest | 68.4 | 22.3 |
| PopupModalBrowserTest | 43.5 | 15.6 |
| PageModalBrowserTest | 30.4 | 12.1 |
| BannerModalBrowserTest | 16.6 | 11.9 |
| FaqAdminOrderBrowserTest | 15.5 | 9.3 |
| BoardModalBrowserTest | 13.6 | 5.4 |
| SiteModalBrowserTest | 12.5 | 5.0 |
| ContentScopeAdminBrowserTest | 10.7 | 4.8 |
| PopupAdminFormBrowserTest | 8.7 | 4.2 |
| SiteMenuScreenBrowserTest | 7.9 | 4.5 |
| BoardScopeAdminBrowserTest | 6.7 | 3.0 |
| MediaPickerNestedModalBrowserTest | 5.5 | 1.9 |
| SiteDefaultBrowserTest | 5.4 | 1.4 |
| FaqPublicFilterBrowserTest | 3.3 | 3.2 |
| PageScreenLeaveGuardBrowserTest | 2.8 | 1.7 |
| **합계** | **254** | **106.3** |

클래스 시간에는 그 설정의 Spring 컨텍스트 첫 기동(약 11초)이 처음 실행된 클래스에 포함된다. 모달 3개 클래스를 `--rerun`으로 3회 연속 실행해 불안정 실패가 없었다(완료 기준 5). `grep -rn waitForTimeout src/test/java` 0건(완료 기준 4).

### 전환 의존 시험 결정 (설계 3)

- 항상 켬: FAQ·팝업·정적 페이지의 "상세 조회가 즉시 404로 실패해도(열림 전환 중)" 3건(R1-1).
- 끈 채로 둠: 세 화면의 "상세 조회 중 모달을 닫아도 … 늦게 온 응답이 새 폼을 덮지 않는다"(순번 검사 제거 변이가 전환을 끈 상태로도 실패), `MediaPickerNestedModalBrowserTest` 2건(`modal-open` 복원 제거·포커스 복원 제거 변이가 각각 두 시험 모두에서 실패).

### 변이 실험 (40건 — 전부 실패 = 검출, 대상 파일은 실행 뒤 원복. 경계 확인용 1건은 별도)

| 묶음 | 변이 | 화면 | 실패 지점 |
|---|---|---|---|
| A | `data-backdrop`·`data-keyboard` 제거 | FAQ·정적 페이지·팝업·배너·게시판·사이트 | `assertHidePrevented` 즉시 실패(`expected: 2 but was: 0`) — 처음 구현(`waitHidePrevented`)은 15초 시간 초과로 실패해 후속 조정에서 바꿨다 |
| B① | 저장 버튼의 `form` 속성 제거 | FAQ | 포커스 단언 |
| B② | 폼 연결 유지 + 저장 버튼 `onclick`으로 쓰기 요청 | FAQ·정적 페이지·팝업·배너·게시판·사이트 | **장벽 직후 요청 수 단언**(6개 화면 모두 — R1의 순서 가정을 실측으로 확인) |
| C | 미저장 확인 취소 시 `preventDefault` 제거 | FAQ·정적 페이지·팝업·사이트 | "취소하면 모달이 유지된다" |
| D | 열기 가드의 `formLocked()` 제거(재조회 중·순서 초안) | FAQ 2건 | "열리지 않는다" 단언 |
| E | 목록 렌더 직후 마이크로태스크로 `setSaving(false)` | 정적 페이지(PUT·DELETE 409) | "B가 진행 중이면 [닫기] 잠금이 유지된다" |
| E0 | 목록 렌더 뒤 `setTimeout(…, 0)`으로 `setSaving(false)` | 정적 페이지(PUT·DELETE 409) | 같은 단언 — 표지 행 뒤 한 태스크 대기를 넣은 후속 조정으로 잡힌다 |
| F | 상세 응답 순번 검사 제거 | FAQ·정적 페이지·팝업 | "늦게 온 첫 응답이 폼을 덮지 않았다"·입력값 단언 |
| G | 상세 404 경로의 `showPageError`를 `await reload()` 앞으로 | FAQ | 안내가 재조회 뒤 사라져 `waitForText` 시간 초과 |
| I | 카테고리 존재 검사 뒤 `return` 제거 | FAQ(수정·등록) | 장벽 직후 요청 수 단언 |
| P | 열림 전환 중 `hide()` 예약(`pendingClose`) 제거 | FAQ·정적 페이지·팝업(전환 켬) | `closed` 대기 시간 초과 |
| M | `media-picker.js`의 `modal-open`·포커스 복원 제거 | 미디어 선택(전환 끔) | 각 복원 단언(2건 × 2) |

한계(기록): E의 대체(표지 행 + 한 태스크)는 목록 응답 뒤 **한 태스크보다 늦게** 미뤄진 잠금 해제 회귀를 잡지 못한다 — 경계 확인 변이 `setTimeout(…, 50)`은 통과(미검출)했다. 옛 500ms 고정 대기는 이 범위를 잡았으므로 이번 변경에서 실제로 좁아진 유일한 검출 범위이며, 고정 대기 0건 규약과 맞바꾼 수용 한계다(사용자 결정 2026-10-22).

### 구현 후 조정 (사용자 결정, 2026-10-22)

| 한계 | 결정 |
|---|---|
| E — 목록 응답 뒤 미뤄진 잠금 해제 | 표지 행이 사라진 뒤 한 태스크(`setTimeout(0)`)를 더 기다린다. 바로 다음 태스크로 미룬 회귀(E0)까지 잡는다 |
| A — 정적 백드롭 회귀가 15초 시간 초과로 느리게 실패 | 거부 이벤트가 키·마우스 이벤트 처리 안에서 동기 발생하므로 누른 직후 바로 횟수를 단언한다(`assertHidePrevented`). 정상 6건 통과, 변이 6건 즉시 실패 |
| 전환을 끈 시험은 실제 300ms 전환 중 경합을 못 잡음 | "모달은 표시됐으나 `shown` 전" 구간의 응답 시험을 **로드맵 후속 후보**로 등록한다(`/updateRoadmap` 때). 이번 PR 범위는 그대로 |
