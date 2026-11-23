# PLAN — 관리자 React 3단계: 게시글 관리 화면 전환 (이슈 #297)

> 상태: 계획 v3 (2026-11-23) — 적대적 리뷰 3라운드 ship·사용자 승인, 2026-11-23 구현·검증 완료(PR 전 — 결과는 "구현·검증 결과" 절)
> 경로: **정식** (새 npm 의존성, 빌드 진입점 추가, 본문 HTML 출력 경로 이동)
> 위험 변경: **본문 HTML 출력**(React 규약에 예외 1곳). 서버 코드·API·인가·스키마·잠금은 바꾸지 않는다.

## 개정 이력
- v3 변경(2라운드 2건 수용): ① 작업별 응답 본문 필요 여부를 정하고 403은 본문을 읽기 전에 판정(결정 3, `http.test.ts`, 시험 22) — 옛 화면은 403과 본문이 필요 없는 쓰기에서 본문을 기다리지 않는다 ② 내 게시판 조회와 주소 진입 자동 열기를 effect 밖 async 흐름으로 명시(결정 4, 시험 14) — `flushSync`는 effect 안에서 동작하지 않는다.
- v2 변경(1라운드 4건 전부 수용): ① C8 — 검색 폼도 `preventDefault`(v1의 "기본 제출"은 오기) ② 쓰기 뒤 목록 갱신은 시작 시점 키가 아니라 **완료 시점의 현재 게시판·폼 값**으로 정한다(시험 21) ③ 복원 잠금을 휴지통 컴포넌트 밖(`PostManage` ref)에 둔다(시험 13). 요청 식별자는 기각: 잠금이 전역 하나라 풀리기 전에 새 요청이 시작될 수 없다 ④ R18~R20을 배포본으로 재현하고 무결성·명령·결과를 R22에 남겼다.
- v1 (2026-11-23): 최초 작성. 결정 1·2는 사용자 결정.

## 1. Context

### 목적·범위
- `templates/admin/board/posts.html`(1,888줄, 인라인 스크립트 2개 약 1,400줄)의 본문을 React + TypeScript 아일랜드로 옮긴다. 목적은 1·2단계와 같다(React 경험, 새 프로젝트 기반).
- 범위 밖: `manage.html`(게시판 정의), `<dialog>`(#298), npm 감시(#299), `notice-editor.js`·`media-picker.js`의 동작 변경, 서버 전부.

### 정찰에서 확인한 사실
| # | 사실 | 근거 |
|---|---|---|
| R1 | 서버가 화면에 넘기는 값은 `CAN_PICK_LIBRARY`(`myPermissions`에 `MEDIA:READ`) 하나다 | `posts.html:483-486` |
| R2 | 이 화면을 여는 브라우저 시험은 `MediaPickerNestedModalBrowserTest` 2건뿐이다. `BoardModalBrowserTest`·`BoardScopeAdminBrowserTest`는 `manage.html` 시험이다 | 두 시험의 `navigate` |
| R3 | 보기 모드는 `viewContent.innerHTML = data.content`다. 서버 `PostResponse.from`이 출력 시에도 `HtmlContentSanitizer`를 거친다. `src/main/java`에 CSP 설정은 없다 | `posts.html:965`, `PostResponse` |
| R4 | `FrontendSourceConventionTest`가 `frontend/src`의 `dangerouslySetInnerHTML`·`innerHTML` 대입을 전면 금지한다 | 시험 |
| R5 | 목록·상세·휴지통 GET은 새 조회 때 이전 요청을 abort하고 컨트롤러 동일성으로 늦은 응답을 버린다. 첨부 목록은 세대 번호다. **저장·삭제·업로드는 abort하지 않고** 시작 시점의 모달 토큰과 비교해 화면 반영만 건너뛴다(저장·삭제의 목록 재조회는 한다) | `posts.html:873-920,1181-1211,1433-1633,1721-1820` |
| R6 | PATCH는 바뀐 필드만 보낸다. `coverImageId`는 사용자가 직접 고른 값이 연 시점과 다를 때만(없음 = 명시 `null`), `publishPeriod`는 시작·종료 중 하나라도 바뀌었을 때 통째로, 예약을 끈 게시판은 [기간 해제] 때만 `{start:null,end:null}`, `pinned`는 바뀐 때만 | `posts.html:1375-1414` |
| R7 | 응답은 전역 `non_null`이라 null 필드는 키가 빠진다(`coverImageId`·`publishStart` 등) | `application.yml:18` |
| R8 | 편집기는 페이지에 하나다. `setUploadUrl`(무효화 포함)로 게시판 출처를 바꾸고, 전환·닫기·취소 때 `invalidate()`한다. `onChange`가 대표 이미지 선택지를 다시 계산하고 `setHtml`(silent) 뒤에는 호출자가 직접 갱신한다 | `notice-editor.js`, `posts.html:986-1051` |
| R9 | 게시글 모달은 **정적 백드롭이 아니다**(배경·ESC로 닫힌다, 미저장 확인 없음). `hidden.bs.modal`에서 상태를 모두 비운다. 저장 중에도 닫을 수 있다 | `posts.html:316,1550-1557` |
| R10 | 목록 조회 파라미터는 조회 순간의 검색 폼 값이다(제출하지 않은 입력도 페이지 이동·저장 뒤 재조회에 실린다). 값은 trim, 빈 값 생략, `size=20`. 페이지가 총 페이지를 넘으면 마지막 유효 페이지로 다시 읽는다 | `posts.html:791-920` |
| R11 | 게시판 전환: 휴지통 닫기·초기화, 검색 폼 초기화, 0페이지 조회, 업로드 URL 교체. 열려 있는 모달의 게시판은 `modalBoard`로 고정된다 | `posts.html:1823-1833` |
| R12 | `writableYn === false`면 CREATE·UPDATE만 닫는다. 첨부 **삭제** 버튼은 `actions`의 UPDATE를 직접 본다(보관 게시판에서도 삭제 가능). 삭제 409이고 UPDATE가 없으면 안내 문장을 덧붙인다 | `posts.html:495-508,1176,1504-1507` |
| R13 | `?boardId=&id=` 진입: `id`는 `^[1-9]\d{0,15}$`이고 그 게시판을 볼 수 있을 때만 상세를 열고 두 파라미터를 `history.replaceState`로 지운다 | `posts.html:1867-1881` |
| R14 | 403 문구가 공용 `errorMessage`와 다르다("권한이 없습니다. 권한이 변경되었을 수 있으니 페이지를 새로고침해 주세요.") | `posts.html:677`, `http.ts` |
| R15 | `api/http.ts`는 `AbortSignal`·`FormData`를 지원하지 않는다 | `http.ts` |
| R16 | 진입점 목록은 `vite.config.ts`·`MenuReactPageIntegrationTest`·`prod-smoke.sh` 세 곳에 있다 | 루트 `CLAUDE.md` |
| R17 | `@tanstack/react-query` 5.107.1은 2026-11-06 배포(14일 규칙 기준일 2026-11-09 이전), MIT, 런타임 의존은 `@tanstack/query-core` 5.107.1뿐, peer `react ^18 \|\| ^19` | npm 레지스트리 `time`, 패키지 `package.json` |
| R18 | TanStack 기본값: `networkMode` 미지정은 `online`이라 오프라인이면 요청을 시작하지 않고 멈춘다. 브라우저 기본 `retry`는 3 | 5.107.1 `query-core/src/retryer.ts` `canFetch`·`run` |
| R19 | 마지막 구독자가 떠날 때 `signal`을 읽은 조회는 취소된다(`cancel({revert:true})`). 취소된 조회의 늦은 값은 retryer가 이미 끝나 무시된다 | 5.107.1 `query.ts` `removeObserver`, `retryer.ts` `resolve` |
| R20 | **데이터가 아직 없는 조회는 `cancelRefetch: true`(invalidate 기본)여도 진행 중 요청을 취소하지 않고 그 promise를 돌려준다** | 5.107.1 `query.ts` `fetch` |
| R22 | R18~R20 **재현**(2026-11-23): `npm pack --ignore-scripts @tanstack/query-core@5.107.1`(integrity `sha512-PSyAik11…CpNgvg==`, react-query는 `sha512-udCOOLp3…h06FA==`, 둘 다 레지스트리 값과 일치)의 `build/modern/index.js`를 Node로 실행. 첫 조회 중 `invalidateQueries`만: 요청 1회·취소 안 됨 / `await cancelQueries` 뒤 `invalidateQueries`: 요청 2회·첫 요청 abort·늦은 첫 값 무시 / 구독 해제: abort / 오프라인 `online`: 요청 0·`paused`, `always`: 요청 1 | scratchpad 실행. 같은 내용을 `queries.test.ts`로 저장소에 고정 |
| R21 | 관련 이슈 #298·#299·#300·#302·#303은 범위 밖. TanStack 관련 과거 결정은 1단계 계획 결정 4(보류)뿐 | `gh issue list --state all` |

## 2. 설계 제약
- **C1 시험 우선**: 전환 **전에** 옛 화면 기준 특성 시험 `PostScreenBrowserTest`(§6)를 작성해 통과시키고, 전환 뒤 **시험 파일을 고치지 않고** 통과시킨다. 단언은 DOM id·문구·요청 본문·DB로만 하고 옛 화면의 전역 변수를 읽지 않는다.
- **C2 동작·DOM 계약 유지**: 기존 id·클래스·문구·`d-none`·`disabled`·`confirm`·`data-id`를 유지한다. 바꾸는 것은 §5에 적은 것뿐이다.
- **C3** Bootstrap이 바꾸는 `#postDetailModal`의 class·style·aria-hidden을 React prop으로 바꾸지 않는다. 모달은 항상 마운트하고 `#wrapper` 밖 `#postModalRoot`에 포털로 그린다.
- **C4** 편집기 호스트의 자식은 React가 관리하지 않는다. 편집기는 마운트 때 하나만 만든다.
- **C5** 사용자 입력은 텍스트로만 렌더링한다. 예외는 결정 1의 한 곳.
- **C6** fetch·CSRF는 호출 시점에 찾는다. GET에는 `method`를 싣지 않는다(`BrowserTestSupport.holdFirstResponse`가 이 모양으로 GET을 식별한다). 동적 `import()`·CSS import 금지.
- **C7** `src/main/java` 무변경(`CLAUDE.md` 제외).
- **C8** 폼 Enter는 저장을 일으키지 않는다(`postForm`은 `preventDefault`만). 검색 폼(`searchForm`)도 `preventDefault` 뒤 0페이지를 조회한다(페이지 탐색 없음).

## 3. 핵심 설계 결정

### 결정 1 — 본문 HTML 출력: 파일 1개만 예외 (2026-11-23 사용자 결정)
- `frontend/src/admin/shared/ServerHtml.tsx`가 유일하게 `dangerouslySetInnerHTML`을 쓴다. 서버가 출력 시 sanitizer를 거친 값만 받는다.
- `FrontendSourceConventionTest`: ① 이 파일에서 `dangerouslySetInnerHTML`은 **정확히 1회**, 다른 금지 패턴은 0 ② 다른 파일은 전부 0(기존) ③ `ServerHtml`을 쓰는 JSX는 전체에서 **1곳**이고 `admin/post/PostView.tsx`의 `html={detail.content …}`여야 한다 — 컴포넌트 재사용으로 예외가 번지는 것을 막는다. 검사 자체 회귀(파일 추가·사용처 추가·2회 사용)를 함께 시험한다.
- 왜: 정적 JS로 빼면 검사 범위 밖으로 숨고, 읽기 전용 Quill은 표시가 바뀌고 편집기 1개 계약과 부딪힌다.
- 브라우저 `setHTML`(Sanitizer API)로 이중 방어하지 않는다: 기본 허용 목록이 서버 목록과 달라 1:1 계약이 흐려진다.

### 결정 2 — TanStack Query를 조회에만 도입 (2026-11-23 사용자 승인)
- `@tanstack/react-query` **5.107.1** 정확히 고정, lock은 Gradle이 내려받은 npm으로 `npm install --before=2026-11-09`. 번들 청크 배치는 Rolldown 기본.
- 대상: 내 게시판·목록·상세·첨부·휴지통 GET 5종. **쓰기는 `useMutation`을 쓰지 않는다** — 이 화면의 쓰기 규칙(중단 금지, 토큰 비교로 화면 반영만 건너뜀, 자동 재시도 없음)은 일반 async 함수가 더 분명하다.
- 1단계에서 보류한 이유(초안이 있으면 재조회 금지·결과 불명 잠금)는 이 화면 조회에 없다. FAQ·메뉴 화면은 바꾸지 않는다.
- 기본 옵션: `retry: false`, `refetchOnWindowFocus: false`, `refetchOnReconnect: false`, `staleTime: 0`, `gcTime: 0`, `networkMode: 'always'`(R18). 옛 화면처럼 사용자가 일으킨 조회만 나가고 다시 볼 때마다 새로 읽는다.
- 키: `['boards']`(`staleTime: Infinity`) · `['posts', boardId, params]` · `['post', sessionId, boardId, postId]`(`staleTime: Infinity`) · `['attachments', sessionId, boardId, postId]` · `['trash', boardId, page]`. `queryFn`은 항상 `signal`을 읽어 넘긴다(R19).
- **다시 읽기는 `refresh(key)` 한 함수로만 한다**: `await cancelQueries({queryKey, exact})` → `invalidateQueries({queryKey, exact})`. R20 때문에 `invalidate`만 부르면 첫 조회가 진행 중일 때 저장 전 응답이 남는다. **쓰기가 끝난 뒤의 목록 갱신은 `loadPosts(현재 페이지)`를 다시 부르는 것**이고, 이 함수는 부르는 시점의 현재 게시판·검색 폼 값·페이지를 ref에서 읽어 params를 새로 만든다 — 시작 시점의 키를 캡처하지 않는다(쓰기 중에 검색을 바꿔 새 조회가 먼저 끝나도 완료 뒤 한 번 더 읽는다). 휴지통·첨부도 완료 시점의 현재 키를 `refresh`한다. `refresh`의 취소를 기다리는 사이 키가 바뀌면 새 키는 쓰기 완료 뒤에 시작하므로 최신이다. vitest로 "첫 조회 중 refresh → 새 요청이 나가고 이전 응답은 버려진다"를 고정한다(QueryClient + QueryObserver, DOM 불필요).
- 화면은 `isFetching`이면 "조회 중입니다.", 아니고 `isError`면 "조회에 실패했습니다."로 그린다(TanStack은 재조회 실패 때 이전 데이터를 남긴다 — 옛 화면은 실패 행을 보인다). 요약·페이지네이션은 조회 중 직전 값을 유지하고 실패하면 페이지네이션만 비운다: 마지막 성공 응답을 상태로 들고 있는다.
- `queryFn`은 실패를 `Error(사용자 문구)`로 던진다. 네트워크 실패 = "네트워크 연결을 확인해주세요.".

### 결정 3 — 공용 `http.ts` 확장
- `RequestOptions`에 `signal?: AbortSignal`, `form?: FormData`(JSON과 동시 사용 불가, `Content-Type`을 정하지 않는다) 추가. `errorMessage(result, fallback, forbidden?)`로 403 문구를 주입한다(기본은 기존 문구). **본문을 읽는 시점을 옛 화면과 맞춘다**: 저장(생성·PATCH)만 2xx 본문이 필요하고, 글 삭제·첨부 업로드·첨부 삭제·복원은 `skipSuccessBody`로 헤더만 보고 성공 처리한다. 403은 새 옵션 `skipForbiddenBody`(게시글 API 전부에서 켬, FAQ·메뉴는 끈 채)로 본문을 읽지 않고 `{status:403, ok:false, body:null, bodyError:false}`를 돌려준다. `http.test.ts`: 본문이 끝나지 않는 403·2xx에서 즉시 반환, 옵션을 끄면 기존과 같음. 그 밖의 반환 계약은 그대로 — abort는 `{kind:'network'}`이고 이미 취소된 조회라 화면에 닿지 않는다(R19).
- `api/post.ts`: 응답 타입은 R7에 맞춰 nullable 필드를 선택 속성으로 둔다.

### 결정 4 — 상태 구조
- 파일: `frontend/src/admin/post/{main,PostManage,PostList,TrashPanel,PostModal,PostForm,PostView,Attachments,Pagination}.tsx`, 순수 모듈 `board.ts`(허용 동작·정책 문구)·`form.ts`(폼 모델·기간 읽기·생성/PATCH 본문)·`format.ts`·`paging.ts`·`queries.ts`, 필요한 곳에 `*.test.ts`.
- **모달 세션**: 모달을 열 때마다 `{id, board, postId|null}`을 새로 만든다(옛 `currentDetailController` 동일성 토큰 + `modalBoard`). `sessionRef`에 동기 반영하고, 저장·삭제·업로드는 시작 세션을 캡처해 응답 뒤 `sessionRef.current`와 비교한다. `hidden.bs.modal`에서 세션을 비운다.
- 상세 표시 값 = `saved ?? detailQuery.data`(`saved`는 저장 응답, 세션이 바뀌면 비움). 저장 뒤 상세를 다시 조회하지 않는 옛 동작과 같다.
- 폼은 controlled(제목·노출·고정·기간 6칸·기간 해제 표시·대표 선택·`coverUserChanged`). 본문만 편집기가 소유한다. 이벤트 핸들러가 즉시 읽는 값은 1단계처럼 ref에 동기 반영한다.
- **내 게시판 조회는 마운트 effect가 시작한 async 함수에서 `queryClient.fetchQuery`를 `await`한 뒤** 처리한다(주소 진입 자동 열기 포함) — `flushSync`는 `useEffect`·`useLayoutEffect` 실행 중에는 동작하지 않으므로(React 공식 문서 react-dom `flushSync` "called from inside a lifecycle method", React 19.3.1에서 콘솔 오류 0을 시험 14로 확인) 조회 결과를 effect로 받아 모달을 열지 않는다. 범위 밖 페이지 effect는 모달을 열지 않으므로 `flushSync`를 쓰지 않는다.
- 모달 열기: `flushSync`로 세션·모드·폼을 반영한 뒤 `modal('show')`. 편집기 `setUploadUrl`·`setHtml`·`setImageUploadEnabled`는 핸들러에서 직접 부른다.
- 저장 잠금은 세션별: `#btnSave` disabled = 편집기 busy 또는 이 세션 저장 중, `#btnCancelEdit` disabled = 이 세션 저장 중. 핸들러에서도 다시 검사한다(중복 제출 방지).
- 목록 `params`는 조회 순간의 폼 값 스냅샷이다(R10). 같은 params면 `refresh`, 다르면 params 교체. 범위 밖 페이지는 effect에서 마지막 페이지로 params를 바꾼다.
- 휴지통은 `key={boardId}`로 게시판 전환 때 초기화하고, 본문은 열렸을 때만 마운트해 열 때마다 조회한다. **복원 진행 잠금(`trashRestoring`)은 `PostManage`의 ref**라 휴지통을 닫거나 게시판을 바꿔도 요청이 끝날 때까지 유지된다(옛 화면과 같은 전역 잠금). 복원 버튼의 `disabled`는 요청 중인 글 하나다.

### 결정 5 — 템플릿·연결
- `posts.html`: `<style>`·링크·사이드바·상단바·`#postManageRoot[data-can-pick-library]`(h1 줄은 [새 게시글] 버튼 때문에 React가 그린다)·`#postModalRoot`·기존 스크립트 + 모듈 하나. 인라인 스크립트 0.
- `data-can-pick-library`는 지금의 null 안전 식을 그대로 쓴다(권한 키 전체를 넘기지 않는다).
- `globals.d.ts`에 `NoticeEditor`의 나머지 멤버와 `MediaPicker`를 더한다.
- `vite.config.ts` 진입점 `post-manage`, `MenuReactPageIntegrationTest`·`prod-smoke.sh` 목록 추가, 새 `PostReactPageIntegrationTest`(모듈 하나·인라인 0·마운트 지점·`data-can-pick-library`가 ADMIN/MANAGER 권한대로).

## 4. 성공 기준
- S1 `PostScreenBrowserTest`가 **옛 화면에서** 통과 → 전환 뒤 무수정 통과. `MediaPickerNestedModalBrowserTest`도 무수정 통과.
- S2 `posts.html` 인라인 스크립트 0, `notice-editor.js`·`media-picker.js`·`common/html`·`src/main/java` 코드 diff 0.
- S3 `frontendCheck`·관련 백엔드 시험·컨벤션 시험, PR 직전 `test -PbrowserTests=exclude`와 `only` 통과.
- S4 번들 증가량 실측 기록(`post-manage.js`, 공유 청크), FAQ·메뉴 진입점이 TanStack을 싣지 않음.
- S5 결정 1의 시험이 변이(다른 파일 사용·사용처 추가)를 잡는다.
- S6 실기 검증(ADMIN·MANAGER, 왕복, 콘솔 오류 0).

## 5. 바뀌는 동작 (수용·기록)
- 저장 성공 문구는 HTML이 아니라 텍스트로 그린다(보이는 글자는 같다).
- 본문이 필요한 조회·저장에서 2xx인데 본문을 읽지 못하면 JS 예외 문구 대신 그 작업의 기본 실패 문구를 보인다(그 밖의 흐름은 옛 화면과 같음).
- GET에 `Accept: application/json`이 붙고, 본문 없는 쓰기에 `Content-Type`이 빠진다(서버 계약 무관).
- 페이지 이동이 실패한 뒤의 재조회는 실패한 그 페이지를 다시 읽는다(옛 화면은 이동 전 페이지).
- 게시판을 A→B→A로 바꾸는 사이 끝난 휴지통 복원의 성공 문구는 보이지 않는다(목록은 최신).

## 6. 특성 시험 (`PostScreenBrowserTest`)
1. 목록·요약·상태 배지·기간·고정 배지, 검색·초기화, 페이지 이동
2. 게시판 전환 뒤 늦은 이전 목록 응답이 덮지 않음
3. 첫 목록 조회 중 글을 저장하면 저장 전 응답이 남지 않음(R20)
4. 범위 밖 페이지(마지막 글 삭제) → 이전 유효 페이지
5. 생성 본문(기본값·고정·기간·대표 생략 규칙)과 저장 뒤 첨부 영역 표시
6. 변경 없음 안내, PATCH가 바뀐 필드만 싣는다(제목만·노출만·고정만)
7. 기간: 시·분만 고르면 오류, 변경 시 통째 전송, 예약 끈 게시판의 해제·취소
8. 대표: 직접 선택·직접 없음(`null` 전송)·본문에서 지워 자동 없음(키 없음)
9. 늦은 상세가 다른 글 모달을 덮지 않음, 모달을 닫은 뒤 늦은 상세 무시
10. 저장 응답 전에 다른 글을 열면 새 모달이 유지되고 목록만 갱신, 저장 중 중복 제출 없음
11. 첨부 업로드(multipart)·삭제·정책 안내, 업로드 중 모달 전환 시 새 모달 무영향
12. 첨부가 남은 글 삭제 409 + UPDATE 없는 MANAGER 안내 문장
13. 휴지통 열기·복원·404·게시판 전환 초기화·복원 중 닫았다 다시 열거나 A→B→A로 바꿔도 중복 복원 요청 없음·삭제 뒤 열린 휴지통 갱신
14. `?boardId=&id=` 진입과 주소 정리, 볼 수 없는 게시판이면 열지 않음, 자동으로 열린 모달의 상세·첨부 표시와 콘솔 오류 0
15. MANAGER 권한별 버튼, 쓰기 중지 게시판(새 글·수정 숨김, 삭제·첨부 삭제 유지), 게시판 없음 안내
16. XSS: 제목·작성자·게시판명·파일명의 HTML이 글자로 보이고, 본문의 `onerror`·`script`가 실행되지 않으며 허용 서식은 보인다
17. 편집기 이미지 업로드 중 저장 차단, 업로드 중 다른 글을 열면 이미지가 새 글에 들어가지 않음
18. 본문 이미지를 끈 게시판에서 기존 이미지 유지·버튼 숨김, 갤러리 유형 안내
19. 제목에서 Enter로 제출되지 않음, 배경·ESC로 닫힘, 여러 번 여닫아도 툴바 1개
20. 403·네트워크 실패 문구, 목록 실패 행
21. 저장 중 검색 조건을 바꿔 새 목록 조회가 먼저 끝나도, 저장 완료 뒤 현재 조건으로 다시 읽어 저장 결과가 보인다
22. 본문이 끝나지 않는 403과 업로드·복원·삭제 2xx에서도 안내·후속 조회가 진행된다(`http.test.ts`가 원본, 브라우저에서는 403 문구만)

## 7. 실패 모드와 고정 시험
- F1 재조회가 저장 전 응답으로 끝남(R20) → `queries.test.ts`, 시험 3
- F2 이전 게시판·글의 늦은 응답이나 이미지가 현재 화면을 오염 → 시험 2·9·10·17
- F3 낡은 화면이 다른 관리자의 대표·기간·고정을 되돌림 → `form.test.ts`, 시험 6~8
- F4 HTML 출력 예외가 다른 값으로 번짐 → `FrontendSourceConventionTest`, 시험 16
- F5 번들·청크 누락으로 본문이 빔 → `PostReactPageIntegrationTest`, `MenuReactPageIntegrationTest`, `prod-smoke.sh`

## 8. 작업 단계
1. 특성 시험 작성 → 옛 화면에서 통과 확인
2. 의존성·`http.ts`·`queries.ts`(TDD) → `frontendCheck`
3. 순수 모듈(TDD) → 컴포넌트 → 템플릿·진입점·서버 렌더링 시험·`ServerHtml` 규약 시험
4. 영향 범위 시험, 변이 실험, 번들 크기 실측
5. 실기 검증 → 문서(`board`·`common/html`·루트 `CLAUDE.md`, 이 문서, 인덱스) → 코드 리뷰 → 커밋·PR

## 9. 리스크
- 화면이 커서 React판이 특성 시험에 없는 동작을 놓칠 수 있다 — 옛 스크립트 함수별 대조표를 단계 3 끝에 점검한다.
- 되돌리기: 스키마·서버가 그대로라 커밋 되돌리기로 끝난다.
- Windows·Linux 모두에서 lock으로 깨끗한 설치가 되는지는 CI에서 확인한다(미확인).

## 구현·검증 결과 (2026-11-23)

### 구현 파일
- 프런트: `frontend/src/api/{http,post}.ts`, `frontend/src/admin/post/*`, `frontend/src/admin/shared/{ServerHtml.tsx,globals.d.ts}`, `package.json`·`package-lock.json`, `vite.config.ts`
- 서버 쪽: `posts.html`(인라인 스크립트 0), `scripts/ci/prod-smoke.sh`. `src/main/java` 코드 변경 없음(`CLAUDE.md`만).
- 시험: `PostScreenBrowserTest`(34), `PostReactPageIntegrationTest`(2), `FrontendSourceConventionTest`·`MenuReactPageIntegrationTest` 확장, `frontend/src/**/*.test.ts`

### 계획과 달라진 점
- 순수 모듈 `paging.ts`는 따로 두지 않고 `format.ts`에 합쳤다(함수 2개).
- 시험 22(끝나지 않는 본문)는 `http.test.ts`가 맡고, 브라우저 시험은 403·네트워크 문구(`listFailureMessages`)로 확인한다.
- 실패 뒤 다시 조회하는 동안 페이지 버튼이 직전 성공 값으로 보인다(옛 화면은 빈 채로 유지) — §5에 더하는 표시 차이.

### 검증
- 옛 화면에서 `PostScreenBrowserTest` 34건·`MediaPickerNestedModalBrowserTest` 통과 → 전환 뒤 **시험 무수정** 통과. 프런트 단위 98건.
- 변이 11건 중 8건이 의도한 시험에서 실패했다: refresh의 취소 생략, 세션 비교 생략, 저장 잠금의 세션 구분 제거, 대표 항상 전송, 시작 시점 조건으로 재조회, 범위 밖 페이지 보정 제거, 본문 대신 제목을 HTML로 출력, 모달 게시판 대신 현재 게시판으로 저장. 살아남은 3건은 이중 방어라 동작이 같은 변이다: 복원 잠금(버튼 `disabled`), 첨부 생존 확인(`key` 재마운트), 닫을 때·전환 때 업로드 무효화(편집기 `setHtml`이 다시 무효화).
- 번들(압축 전): `post-manage.js` 70,465B, 공유 청크 220,585B(2단계 기록 219KB). TanStack은 `post-manage.js`에만 있다.
- 실기(일회용 DB, ADMIN·조회 전용 MANAGER): 작성(이미지·대표·첨부) → DB 확인 → 재조회 → 수정, 첨부 다운로드 200, 라이브러리 선택 모달 중첩, 390px 넘침 없음, FAQ·메뉴·게시판 관리 회귀 없음. MANAGER는 쓰기 버튼 0, 직접 PATCH·DELETE·휴지통 403, 게시판 관리 403, 비로그인 화면 302·번들 200. 콘솔 오류 0(Bootstrap `aria-hidden` 경고는 기존 #298). 스크린샷 `docs/screenshots/board-admin/react-posts-0{1..4}-*.png`(커밋 제외).

### 이슈
- 변이 실험 스크립트가 처음 두 번 Gradle 호출 경로 문제로 시험을 돌리지 못했다 — 그 결과는 버리고 다시 실행했다.
- 구현 중 master(#305)를 fast-forward로 반영했다(문서 2개만 겹침).
