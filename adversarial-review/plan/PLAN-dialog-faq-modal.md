# PLAN: FAQ 관리 모달을 네이티브 `<dialog>`로 전환 (이슈 #298 1단계)

- 이슈: #298 (관리자 모달 18개 전체가 목표 — 이 PR은 FAQ 모달 1개, `Refs #298`)
- 경로: 정식 / 위험 변경: 없음(API·인가·스키마·의존성·설정 변경 없음). 이후 모달이 따를 공용 방식을 정하므로 정식으로 진행한다.
- 사용자 결정(2026-11-25): 정식 경로, FAQ 모달만, modern-web-guidance 시험은 포함하지 않음

## 개정 이력
- v1: 최초 작성
- v2 변경(1라운드): ESC `keydown` 차단 추가(설계 1, F2), 포커스 성공 기준 축소, 초기 포커스·스크롤 시험 F6·F7 추가, 출처 보강.
  - 일부 기각(1라운드 #1): `span` 진입점을 포커스 가능하게 바꾸거나 저장·삭제 뒤 대체 복귀 위치를 만드는 것은 하지 않는다 — 지금 Bootstrap 화면도 복귀하지 않아 회귀가 아니고, 별도 동작 변경이다(후속 후보).
- v3 변경(2라운드): 지원 범위를 기능·시각 효과로 나눠 명시(설계 1·5), 폭 검증을 F7에서 실기 합격 조건으로 이동(Playwright 헤드리스는 스크롤바를 숨긴다).
- v4 변경(3라운드): 상시 `scrollbar-gutter`를 열린 동안·스크롤바가 있을 때만으로 변경(Bootstrap 로그아웃 모달의 폭 보정과 충돌), 설계 목표와 검증 완료 범위 구분.
- v5 변경(grilling, 2026-11-25): 사용자 결정 Q1~Q7 반영 — `…ThroughPendingClose`를 삭제하지 않고 페이드 중 닫기 시험으로 교체(F3), Firefox·WebKit 로컬 1회 확인과 문제 처리 기준 추가, 공용 방식 구체화. v1~v4는 이 결정들을 사용자 확인 없이 정한 상태에서 리뷰받았다(4라운드 ship).
- v6 변경(5라운드): 변이별 기대 결과와 조합 변이 명시, 방어별 시험 분리(F1·F2), 출처 URL 추가. 이 변경은 재리뷰 전이다.
- v7 변경(6라운드): F3가 close() 호출 시점의 애니메이션 실행 중을 단언하도록 변경, BCD 커밋 고정.
- v8 변경(7라운드): F3에서 시험 전용 스타일로 페이드를 60초로 늘려 실행 중 구간을 보장. 재리뷰 전(7라운드는 이 보완 뒤 접근법을 막을 새 쟁점이 없다고 판정).

- v9 변경(구현 중): ① 폭 보정을 `scrollbar-gutter` 클래스에서 `body` `padding-right`로 바꿈 — 실기 스크린샷에서 gutter가 dialog·배경막에 빈 띠를 남기는 것을 확인. 원칙(열린 동안·스크롤바가 있을 때만)은 같다. ② 연 직후 `scrollTop = 0` 추가(F7 단언 추가). ③ F6 변이: Chromium은 `tabindex=-1`만으로 컨테이너에 포커스해 동작으로 검출되지 않으므로 `autofocus` 속성을 단언. ④ F5: 헤드리스에서 경고가 나오도록 CDP `Accessibility.enable` 사용, 포커스 복귀 시험과 분리. 본문의 결정 5·P9·P10의 gutter 서술은 이 항목으로 대체한다.

## 사용자 결정 (grilling, 2026-11-25)
| # | 결정 | 이유 |
|---|---|---|
| Q1 | 열림만 짧은 페이드, 닫힘은 즉시 | 브라우저별 차이가 없고 코드가 적다(`overlay`는 Firefox·Safari 미지원) |
| Q2 | 자동 시험은 Chromium, 실기에서 Firefox·WebKit을 로컬로 1회 확인(실행되지 않으면 미확인으로 남김) | 공용 방식을 정하는 첫 화면 |
| Q3 | 버튼으로 열고 저장 없이 닫은 경우만 포커스 복귀 보장, `span` 키보드 접근은 후속 이슈 후보 | 목록 동작 변경은 별개 작업 |
| Q4 | 열림 페이드 중 상세 조회가 실패해도 닫히는 시험으로 "열림 전환 중 닫기 계약"을 고정 | 이슈 완료 기준에 더 충실 |
| Q5 | 공용 방식 = 문서 규칙 + `.admin-dialog` CSS. React 훅은 두 번째 React 화면, 템플릿용 JS는 첫 템플릿 화면에서 추출 | 쓰는 곳이 하나일 때의 추상화는 중첩을 겪으며 바뀌기 쉽다 |
| Q6 | 구버전의 취소 불가 강제 닫힘은 수용하고 상태만 정리(입력은 사라짐) | 드물고, 다시 여는 코드는 해당 브라우저에서 검증하기 어렵다 |
| Q7 | Firefox·WebKit에서 ESC로 닫힘·입력 유실·닫을 수 없음은 이 PR에서 수정(설계가 바뀌면 먼저 보고), 모양·포커스 표시 차이는 기록. Q6의 경우는 제외 | 입력 보호 우선 |

## Context
Bootstrap 4는 모달을 닫을 때 포커스가 안에 남은 채 `aria-hidden`을 걸어(`bootstrap.bundle.js:4995-5000`) Chrome이 "Blocked aria-hidden…" 경고를 낸다. 네이티브 모달 `<dialog>`는 `aria-hidden` 조작 없이 브라우저가 나머지 문서를 inert로 만들고 포커스를 되돌린다.

### 정찰 사실
| # | 사실 | 근거 |
|---|---|---|
| R1 | 모달 18개: 템플릿 13, `media-picker.js` 1, React 4(FAQ·메뉴 2·게시글) | `modal fade` 전수 조회 |
| R2 | 배너·팝업·게시글은 미디어 선택기가 중첩된다 — top layer 때문에 선택기와 함께 바꿔야 한다. FAQ는 편집기가 `allowImages:false`라 중첩이 없다 | `MediaPicker.open` 호출 3곳 |
| R3 | 상태 기계: `closed→opening→open→closing`, `forceClose`·`pendingClose`, `shown/hide/hidden.bs.modal` 구독 | `FaqManage.tsx:370-445` |
| R4 | Bootstrap 전제 시험: `FaqModalBrowserTest`의 `.show` 선택자 4곳, `countHidePrevented`, `restoreModalTransitions` 2곳, `failDetailWhenModalShows`. 공용 도우미는 팝업·페이지·배너·게시판·사이트 시험도 쓰므로 지우지 않는다 | 시험 전수 조회 |
| R5 | `FaqAdminOrderBrowserTest`·`ContentScopeAdminBrowserTest`는 `data-state`와 버튼 id만 쓴다 | 같음 |

### 브라우저 동작 전제와 출처
| # | 전제 | 출처 |
|---|---|---|
| P1 | `cancel`의 `preventDefault`만으로는 ESC를 연속으로 누르면 닫힌다(두 번째는 `cancelable=false`) | MDN `cancel` event, 실측 Chromium 158: 두 번째 ESC에 닫힘 |
| P2 | `closedby="none"`이면 ESC 4회·배경 클릭에도 열려 있고 `cancel`도 오지 않는다 | MDN `closedBy`, 실측 Chromium 158 |
| P3 | `closedby` 지원: Chrome 134·Firefox 141·Safari 27.3 | MDN browser-compat-data(2026-11-25 조회) |
| P4 | `<dialog>`·`showModal`: Chrome 37·Firefox 98·Safari 15.4 | 같음 |
| P5 | (HTML 표준 close the dialog: `open` 제거는 동기, `close`는 큐에 넣은 태스크) `showModal()`은 동기로 열고 `close()`는 `open`을 동기로 끄며 `close` 이벤트는 나중에 온다 | 실측: `close()` 직후 `open=false`, 이벤트 없음 → 이후 도착 |
| P6 | `close()`는 열기 직전 포커스 요소로 되돌린다 | HTML 표준 previously focused element, 실측 |
| P7 | 기본 초기 포커스는 첫 포커스 가능 요소(×)다. 안쪽 `tabindex=-1 autofocus` 요소가 있으면 그곳이다. `<dialog>` 자체에 `tabindex`를 쓰면 안 된다 | MDN `<dialog>`, 실측 |
| P8 | React DOM 19.3.1은 `dialog`에 `cancel`·`close`를 직접 듣는다(`ReactDOMComponent.js:1381-1386`). 타입에 `closedby`·`onCancel`·`onClose`가 있다 | `@types/react` 19.3.0 `index.d.ts:3193-3198` |
| P9 | `:has()` Chrome 105·Firefox 121·Safari 15.4, `scrollbar-gutter` Chrome 94·Firefox 97·Safari 18.2 | MDN browser-compat-data |
| P10 | 기존 `.modal`·`.modal-dialog` 배치가 `<dialog>`에서도 유지되고(UA 스타일 초기화 필요) 배경 스크롤이 잠기며 폭이 변하지 않는다 | Chromium 158 축약 CSS 실측(전체 화면은 실기에서 확인) |
| P12 | ESC가 닫기 요청인 플랫폼은 `keydown`을 먼저 보내고 `preventDefault()`하면 닫기 요청을 처리하지 않는다 | HTML 표준 "Close requests", 실측 Chromium 158: `keydown`만 막아도 ESC 4회에 유지 |
| P13 | React DOM 19.3.1은 `autoFocus` prop을 클라이언트에서 속성으로 쓰지 않는다 | `ReactDOMComponent.js:697-701`(v19.3.1) |
| P11 | Firefox·Safari의 실제 동작 | **미확인** — CI는 Chromium만 설치한다(`build.gradle` `installPlaywrightBrowsers`). 로컬에 `ms-playwright/firefox-1572`·`webkit-2388` 폴더가 있으나 실행 여부는 미확인, 실기에서 확인(Q2). Playwright WebKit은 Safari와 같다고 보지 않는다 |

출처 URL(2026-11-25 조회): MDN https://developer.mozilla.org/docs/Web/API/HTMLDialogElement/cancel_event · https://developer.mozilla.org/docs/Web/API/HTMLDialogElement/closedBy · https://developer.mozilla.org/docs/Web/HTML/Reference/Elements/dialog , BCD https://github.com/mdn/browser-compat-data (커밋 ec2734641141768c940bea778e9f29ef1e033ab9: `html/elements/dialog.json`·`css/selectors/has.json`·`css/properties/scrollbar-gutter.json`), HTML 표준 https://html.spec.whatwg.org/multipage/interactive-elements.html#close-the-dialog · https://html.spec.whatwg.org/multipage/interaction.html#close-requests , React https://github.com/react/react/blob/v19.3.1/packages/react-dom-bindings/src/client/ReactDOMComponent.js , Playwright https://github.com/microsoft/playwright/blob/v1.65.1/packages/playwright-core/src/server/chromium/chromium.ts (리뷰어 인용, 구현 때 재확인).

실측 조건: Windows 10, Playwright MCP의 Chromium 158.0.8127.38, `setContent`한 최소 `<dialog>`에 실제 키·마우스 입력. 저장소 시험(Playwright Java 1.65.1의 번들 Chromium)에서는 F1~F7이 같은 전제를 다시 고정한다.

## 설계 결정
1. **ESC·배경 닫기 차단: `closedby="none"` + 열려 있는 동안 `document` `keydown`(Escape) `preventDefault` + `onCancel` `preventDefault`**. 왜: `cancel`만으로는 우회되고(P1) `closedby`는 Safari 27.3부터라(P3) 미지원 브라우저의 연속 ESC는 `keydown`이 막는다(P12). `document`인 이유는 포커스 요소가 비활성화되면(저장 중) 키 이벤트 대상이 `body`가 되기 때문이다. 리스너는 `modalState !== 'closed'`일 때만 막는다. `onCancel`은 키보드가 아닌 닫기 요청(뒤로 가기 등)용이다.
   - 지원 범위: 설계 목표는 `<dialog>` 지원 브라우저(P4)에서 모달 기능과 키보드 입력 보호다. **자동 검증 범위는 Chromium(Playwright 1.65.1 번들)이고** Firefox·WebKit은 로컬 1회 확인 결과만 적는다(Q2). 실제 Safari와 구형 엔진은 보증하지 않는다(P11). 배경 스크롤 잠금은 `:has()`, 폭 유지는 `scrollbar-gutter` 지원 범위(P9)이고, 미지원이면 그 효과만 빠진다(배경은 여전히 inert — 대체 코드를 두지 않는다). `closedby` 미지원 브라우저에서 키보드가 아닌 닫기 요청이 취소 불가로 오면 닫힐 수 있다(수용). 그때도 `close` 처리로 상태는 정리되지만 입력은 사라진다.
2. **닫기는 `requestClose()`(앱 함수) 하나로 모은다**: ×·[닫기]의 `onClick` → 미저장 확인 → `closeDialog()`. 프로그램 닫기(저장·삭제 성공, 상세 실패)는 `closeModal()`이 확인 없이 닫는다. 왜: 확인이 이벤트 취소에 기대지 않으므로 `forceClose`가 필요 없다.
3. **`opening`·`pendingClose`·`forceClose` 제거**. 왜: 열기가 동기라(P5) "열림 전환 중 닫기가 무시되는" 구간이 없다. 완료 기준의 "열림 전환 중 닫기 계약"은 "열린 직후에도 닫힌다"로 유지된다. `data-state`는 `closed|open|closing`.
4. **`closing`은 유지**하고 `close` 이벤트에서 `closed`로 정리한다. 왜: `close`가 비동기라 그 사이 새로 열면 늦은 이벤트가 새 폼을 지운다. 열기 진입점은 이미 `closed`일 때만 허용한다.
5. **CSS는 `.modal` 배치를 재사용**하고 `admin-manage.css`에 `.admin-dialog`(UA 초기화·`[open]` 표시·`::backdrop`·배경 스크롤 잠금)를 둔다. 왜: 기존 모양을 유지하고 다음 화면이 같은 클래스를 쓴다. 폭 유지: `showModal()` 직전에 `innerWidth > documentElement.clientWidth`(실제 스크롤바 있음)이면 `<html>`에 `admin-dialog-gutter` 클래스(`scrollbar-gutter: stable`)를 붙이고 `close` 처리에서 뗀다. 왜 상시가 아닌가: Bootstrap 4.6.0 `_checkScrollbar`/`_setScrollbar`(`bootstrap.bundle.js:5118`)가 본문 폭과 `innerWidth` 차이만큼 `padding-right`를 더해, gutter가 남아 있으면 로그아웃 모달에서 이중 보정된다. 열려 있는 동안 배경은 inert라 사용자가 로그아웃 모달을 함께 열 수 없다.
   - 열림 효과는 `@keyframes` 짧은 페이드만 두고 닫힘 효과는 없다(`prefers-reduced-motion`이면 끔). 왜: 닫힘 전환은 `allow-discrete`·`overlay`가 필요해 복잡도가 는다.
6. **초기 포커스는 `.modal-content`**(`tabindex=-1`, `autofocus` 속성을 마운트 effect에서 설정 — React의 `autoFocus` prop은 속성을 남기지 않는다, P13). 왜: 지금처럼 컨테이너에 둔다. ×에 두면 Enter를 누른 채 열 때 키 반복이 ×를 누른다.
7. **공용 React 컴포넌트·훅은 만들지 않는다**. 왜: Q5. 공용 방식은 루트 `CLAUDE.md`의 규칙(결정 1의 세 겹 차단, 닫기 함수 하나, `closing` 가드, 컨테이너 초기 포커스, 중첩되는 모달은 함께 전환)과 `.admin-dialog` CSS다. React 훅은 두 번째 React 화면, 템플릿용 JS는 첫 템플릿 화면에서 추출한다.
8. **제거**: `data-dismiss`·`data-backdrop`·`data-keyboard`·`role`·`aria-hidden`·`tabIndex`(dialog), jQuery 구독. `aria-labelledby`는 유지.

## 성공 기준
- FAQ 모달을 여닫는 동안(포커스가 ×·[닫기]·저장 버튼에 있을 때 포함) 콘솔 "aria-hidden" 경고 0 — 변경 전 같은 시험이 실패함을 먼저 확인한다.
- ESC 연속·배경 클릭으로 닫히지 않음, 미저장 확인·조회 중 닫기·권한·409 계약 유지, 관련 브라우저 시험 통과.
- 포커스 가능한 버튼으로 열어 저장 없이 닫으면 그 버튼으로 포커스가 돌아온다(행의 `span`으로 열거나 저장·삭제로 닫은 경우는 약속하지 않는다).

## 실패 모드와 고정 시험 (`FaqModalBrowserTest`)
| # | 실패 모드 | 시험 |
|---|---|---|
| F1 | ESC 연속으로 닫힘 | `staticBackdropClosesOnlyByButtons`: ESC 3회 + 배경 클릭, 닿은 증거(`keydown`·`event.target===dialog` 횟수)와 `open` 유지. `closedBy === 'none'`도 단언(다른 방어가 ESC를 먼저 막아 동작만으로는 이 방어의 제거를 잡지 못한다) |
| F2 | `closedby` 미지원 브라우저에서 첫 ESC에 닫힘 | 추가: 시험에서 `closedby` 속성을 지우고 ESC 3회 → 유지, 저장 요청을 붙잡아 포커스 요소가 비활성인 동안에도 유지. `cancelable` `cancel` 이벤트를 보내 `defaultPrevented`도 단언(앱 처리기 확인이지 실제 닫기 요청 검증이 아니다). 최신 Chromium의 기본값 경로 시험이지 구형 엔진의 재현은 아니다 |
| F3 | 열자마자 실패한 상세 응답에 닫히지 않음, 안내가 지워짐 | `immediateDetailFailure…` 유지(전환 복원 호출 제거). `…ThroughPendingClose`는 페이드 중 닫기 시험으로 교체한다: 상세 `GET`을 가짜 404로 바꿔 두고 시험 전용 스타일로 열림 페이드를 60초로 늘린 뒤(애니메이션 이벤트는 늦게 전달될 수 있다 — Web Animations https://drafts.csswg.org/web-animations-1/#update-animations-and-send-events) 페이지 안에서 `#faqModal`의 `animationstart`에 넘기며, 넘긴 순간 `getAnimations()`에 `running`이 있고, 감싼 `HTMLDialogElement.prototype.close`의 호출 직전에도 열림 애니메이션이 `running`임을 단언하고(`close` 이벤트 시점으로 보지 않는다) `closed`·안내 유지·확인창 0을 본다. 공용 도우미와 팝업·페이지 시험은 바꾸지 않는다 |
| F4 | 늦은 `close`가 다시 연 폼을 지움 | 추가: 닫은 같은 태스크에서 열기를 시도하면 무시되고, `closed` 뒤에는 열리며 값이 유지됨 |
| F5 | 닫을 때 `aria-hidden` 경고·포커스 유실 | 추가: 콘솔 수집(취소 닫기·저장 성공·상세 실패) + 버튼으로 열고 닫은 뒤 `activeElement`가 그 버튼 |
| F6 | 초기 포커스가 ×로 가서 Enter 반복에 닫힘, Tab이 배경으로 나감 | 추가: 첫 열기·재열기·FAQ 모드의 `activeElement`가 `.modal-content`, Enter 유지로 열어도 열림·요청 0, Tab/Shift+Tab 반복이 배경 요소에 닿지 않음 |
| F7 | 배경 스크롤·긴 모달 하단 접근 불가·폭 흔들림 | 추가: 긴 목록과 낮은 화면에서 휠에도 페이지 스크롤 불변, 푸터 버튼이 보이게 스크롤됨, 닫은 뒤 스크롤 위치 유지. 폭은 헤드리스가 `--hide-scrollbars`(Playwright 1.65.1 `chromium.ts:345-352`)라 실기로 확인한다 |

## 작업 단계
1. 브랜치 `refactor/faq-native-dialog`. F5 시험을 먼저 쓰고 변경 전 실패 확인.
2. `FaqModal.tsx`·`FaqManage.tsx` 전환, `admin-manage.css` 추가, `manage.html` 주석 정리.
3. 시험 갱신: `.show` → `dialog[open]`, F1~F7. `BrowserTestSupport`는 바꾸지 않는다(닿은 증거는 FAQ 시험 안).
4. 검증: FAQ·`ContentScopeAdmin` 브라우저 시험, `frontendCheck`, 컨벤션 시험. 변이 실험과 기대 결과:
   - `closedby` 제거 → F1의 `closedBy` 단언 실패(ESC 동작은 `keydown` 차단이 막아 통과)
   - `keydown` 차단 제거 → F2(시험이 `closedby`를 지운 상태)의 연속 ESC 실패
   - `onCancel` 제거 → F2의 `defaultPrevented` 단언 실패
   - 닫기를 `animationend`까지 미룸 → F3 실패
   - `closing` 가드 제거 → F4 실패 / 초기 포커스 설정 제거 → F6 실패 / 스크롤 잠금 제거 → F7 실패
5. 실기: 변경 전후 화면 비교(넓은 화면·모바일 폭), ADMIN·조회 전용 MANAGER, 로그아웃 모달 회귀. 폭 합격 조건: 모달을 열기 전(gutter 클래스 없음) `clientWidth < innerWidth`를 확인하고, FAQ 모달·로그아웃 모달 각각 열기 전·열린 동안·닫힌 뒤 `#content`의 폭이 같으며 짧은 화면에서도 같고, `scrollbar-gutter`를 빼면 FAQ 모달에서 폭이 변한다. 터치 스크롤은 모바일 에뮬레이션으로 확인한다. Firefox·WebKit: 로컬에서 실행되면 ESC 연속·배경 클릭·미저장 확인·초기 포커스·여닫기를 확인하고 문제는 Q7 기준으로 처리한다.
6. `com.cms.admin.faq`·루트·`src/test/java`의 `CLAUDE.md`, 인덱스, 이 문서 갱신. 정식 경로라 커밋 전 로컬 전체 시험 1회.

## 구현 파일
- `frontend/src/admin/faq/FaqModal.tsx`·`FaqManage.tsx`, `src/main/resources/static/css/admin/admin-manage.css`(`.admin-dialog`)
- 시험 `FaqModalBrowserTest`(기존 시험 중 `…ThroughPendingClose`는 `detailFailureDuringFadeInClosesTheModal`로 교체, 추가 6건). `BrowserTestSupport`·`manage.html`은 바꾸지 않았다(`manage.html`의 주석은 그대로 맞다)
- 문서: 루트·`com.cms.admin.faq`·`src/test/java`의 `CLAUDE.md`, `docs/troubleshooting.md`

## 검증 결과 (2026-11-25)
- RED: 변경 전 코드에서 전환 관련 시험 10건 실패, 경고 시험은 CDP 접근성을 켠 뒤 "Blocked aria-hidden"으로 실패. 구현 뒤 통과.
- 관련 시험: `admin.faq`·`publicweb.faq`·`ContentScopeAdmin`·컨벤션·`frontendCheck` 통과(v9 수정 뒤에는 FAQ·`ContentScopeAdmin` 브라우저 시험, 컨벤션, `FaqReactPage`, `frontendCheck`를 다시 돌려 통과). 로컬 전체 시험(`master` `d1a2786` 기준): 브라우저 제외 3287건(건너뜀 12, 실패 0)·브라우저 198건(실패 0) 통과.
- 코드 리뷰: 기본 1회·초점 1회, 지적 0건.
- 변이 7종(`closedby` 제거·`keydown` 차단 제거·`onCancel` 제거·닫기를 `animationend`까지 미룸·`closing` 가드 제거·초기 포커스 제거·스크롤 잠금 제거): 모두 기대한 시험이 실패. 초기 포커스는 단언을 보강한 뒤 검출됐다.
- 실기(일회용 DB, Playwright 1.65.1 패키지 — 스크린샷 `docs/screenshots/faq-admin/dialog-*`, 새 카테고리): Chromium 159에서 FAQ 모달 `aria-hidden` 경고 0(변경 전 1), 본문 폭 유지(1041 → 1041 → 1041)·배경막이 화면 끝까지·짧은 화면 폭 유지·보정을 빼면 변함, 로그아웃 모달 폭 유지(변경 전과 같음), 등록 → DB → 재조회 → 삭제, 터치 스크롤, 조회 전용 MANAGER(버튼 없음·등록 API 403), `admin-manage.css`를 쓰는 다른 화면 5개 200·스크립트 오류 0.
- Firefox 159·WebKit 27.2(로컬 1회, 둘 다 `closedBy` 지원): 초기 포커스·ESC 4회·배경 클릭·미저장 확인·여닫기·포커스 복귀, `closedby`를 지운 상태까지 모두 확인. Playwright WebKit은 실제 Safari가 아니다.

## 이슈
- **WebKit에서 긴 모달이 조금 스크롤된 채 열린다**(위 여백이 잘림, 조작에는 문제 없음) — Q7에 따라 기록만 한다.
- 모달 위치가 화면 정중앙이 됐다(변경 전 Bootstrap은 스크롤바 보정으로 7px 왼쪽).
- 로그아웃 등 나머지 17개 모달의 경고는 그대로다.

## 후속
- #321 FAQ 목록의 수정 진입점 키보드 접근
- #322 WebKit에서 긴 모달이 스크롤된 채 열리는 문제
- 나머지 모달 17개 전환은 #298에서 계속

## 범위 밖
나머지 모달 17개, `span` 진입점의 키보드 접근(후속 이슈 후보), 미디어 선택기, `aria-labelledby`가 숨긴 제목까지 함께 읽는 기존 동작, 닫힘 전환 효과.
