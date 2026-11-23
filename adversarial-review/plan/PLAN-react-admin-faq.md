# PLAN — 관리자 화면 React + TypeScript 점진 도입 1단계: 빌드 체계 + FAQ 관리 화면

> 상태: ✅ **완료 (2026-11-22 · #301 `877dc69`)** — 계획 v4(적대적 리뷰 4라운드 ship) 기준, 결과는 "구현·검증 결과" 절
> 경로: **정식** (새 의존성·빌드 설정 변경 — 외부 계약·라이브러리 동작 의존)
> 위험 변경: **없음** — 인가(URL 규칙·`SecurityConfig`)·서버 API·데이터 정합성·잠금·스키마를 바꾸지 않는다. 빌드·CI·배포 이미지 구성은 바꾼다(아래 "빌드 위험").

## 1. Context

### 목적
- 구직(백엔드·풀스택 직무, React 필수 공고에서 서류 탈락) 대비 React + TypeScript 경험 확보.
- 이 CMS는 배포하지 않고, 공개 화면 컨셉이 정해지면 이를 기반으로 새 프로젝트를 만든다. **관리자 화면은 새 프로젝트로 그대로 넘어가므로** 이번 작업물이 그 기반이 된다.
- 전면 SPA가 아니라 **아일랜드 방식**: Thymeleaf 페이지(head·CSRF 메타·사이드바·상단바·푸터)는 그대로 두고 본문만 React가 그린다. 데이터는 기존 `/admin/api/**`.
- API 클라이언트·타입은 화면 코드와 분리된 모듈로 둔다(향후 React Native 등 재사용).

### 범위
- 1단계: 빌드 체계(Gradle·Docker·CI 통합) + **FAQ 관리 화면 하나**(`templates/admin/faq/manage.html`) 전환.
- 이후 단계(별도 계획): 메뉴 편집 → 게시글 관리.

### 정찰에서 확인한 사실
| # | 사실 | 근거 |
|---|---|---|
| R1 | FAQ 화면은 인라인 스크립트 약 860줄의 상태 기계다: `busy`·`loadingDetail`·`syncFailed`·`writing`, 목록 세대 `loadSeq`·상세 세대 `detailSeq`, 순서 초안 `order`와 `isDirty()`, 모달 상태 `closed→opening→open→closing`·`pendingClose`·`forceClose`, 미저장 기준값 `baseline`, 낡은 화면 기준 `categoryBase{id,version}`·`faqBase{id,categoryId}` | `manage.html:173-1027` |
| R2 | 쓰기는 "요청 결과"와 "목록 재조회"를 분리한다(성공 후 재조회 실패 = `syncFailed`, 응답 유실 = 결과 불명·자동 재전송 없음, 409 = 메시지 + 재조회·초안 폐기·모달 입력 보존, 400 = 초안 유지) | `manage.html:454-516` |
| R3 | **브라우저 시험 28건이 DOM 계약으로 동작을 고정한다**: `FaqAdminOrderBrowserTest` 9건, `FaqModalBrowserTest` 18건, `ContentScopeAdminBrowserTest.faqDetailThatTreeCannotRestoreBlocksEdit` 1건. 판정 수단은 id(`#faqTree`·`#btnApplyOrder`·`#faqModal[data-state]`·`#catFieldset`…), 클래스(`.faq-cat`·`.faq-row`·`.faq-handle`·`.faq-cat-name`·`.faq-row-question`), `data-*`(`data-faq-id`·`data-cat-id`·`data-cat`·`data-missing`), `disabled`·`d-none`, 네트워크 가로채기(`page.route`), `confirm` 대화상자 | 두 시험 파일 |
| R4 | 시험 2곳이 전역 함수 `reload()`를 직접 호출한다(모듈 번들에서는 보이지 않음) | `FaqModalBrowserTest:337,491` |
| R5 | 시험 1곳이 실행 중 `window.fetch`를 바꿔치기한다 | `FaqModalBrowserTest:354` |
| R6 | 답변 편집기는 공용 전역 `NoticeEditor.create(el, {allowImages:false, onError})`(Quill 래퍼, `setHtml`·`getHtml`·`isChanged`·`isBusy`·`quill.enable`)이고 sanitizer 허용 목록과 1:1 계약이다 | `static/js/admin/notice-editor.js`, `common/html` CLAUDE.md |
| R7 | 표시 대상 선택은 공용 전역 `ContentScope.create(select)` — 서버가 그린 `<select>`(`scopeSites`)의 옵션을 읽고, 옵션에 없는 사이트 ID는 "사이트 #ID (목록에 없음)" 옵션을 추가해 보존한다 | `static/js/admin/content-scope.js`, `FaqPageController` |
| R8 | 권한 키는 `th:inline`으로 `MY_PERMISSIONS`(`"FAQ:CREATE"` 등)에 실린다 — 버튼 표시용, 서버 403이 최종 | `manage.html:173-176` |
| R9 | **번들을 `/js/` 아래에 두면 접근 규칙 변경이 없다** — `STATIC_PUBLIC_PATHS = {"/css/**","/js/**","/img/**","/vendor/**","/favicon.ico"}`, `static/` 새 최상위 디렉터리는 `SecurityConfigTest`가 감지 | `SecurityConfig:59`, `SecurityConfigTest:1005` |
| R10 | 브라우저 시험은 `@SpringBootTest(RANDOM_PORT)` 실서버가 **클래스패스의 정적 파일**을 서빙한다 → 번들은 `processResources` 단계에서 클래스패스에 들어가야 시험·`bootRun`·jar·Docker가 모두 동작 | `BrowserTestSupport` |
| R11 | 기존 `/js/admin/*.js`는 고정 파일명이다. `application*.yml`에 정적 리소스 캐시 설정이 없고 `SecurityConfig`에도 캐시 헤더 설정이 없어, Spring Security 7.0.7 기본 `CacheControlHeadersWriter`(`no-cache, no-store, max-age=0, must-revalidate`)가 정적 응답에도 붙을 가능성이 높다 — **실측 예정**(구현 2단계, `curl -I`) (v2 정정: v1은 "Last-Modified 재검증"으로 단정했다) | `application*.yml`, `SecurityConfig`, R1 리뷰 #6 |
| R12 | Docker 빌더는 `eclipse-temurin:21-jdk`(digest 고정)에서 `./gradlew clean bootJar`, `.dockerignore`가 `.gradle/`·`build/` 제외 | `Dockerfile`, `.dockerignore` |
| R13 | CI `test` job은 `./gradlew test -PbrowserTests=exclude`, `browser-test` job은 `-PbrowserTests=only`, `prod-smoke`는 실제 이미지 빌드 + Trivy | `.github/workflows/ci.yml` |
| R14 | 프로젝트에 npm이 없고, npm 버전 규칙도 없다. 기존 규칙: 새 의존성은 먼저 제안, 이미지는 digest 고정, 외부 액션은 SHA 고정, Dependabot 주 1회(메이저 제외) | `CLAUDE.md`, `docs/dependabot.md` |
| R15 | 관련 GitHub 이슈 없음(`React`·`프런트엔드`·`FAQ 관리` 검색) | `gh issue list --state all` |

## 2. 설계 제약 (명문화)
- **C1 DOM 계약 유지**: R3의 id·클래스·`data-*`·`disabled`·`d-none`·`data-state`·`confirm` 문구를 그대로 둔다. 기존 브라우저 시험이 회귀 기준이다. 시험 수정은 R4의 2곳만 허용한다: `page.evaluate("() => reload()")` → `page.evaluate("() => document.getElementById('btnReload').click()")`(프로그램 클릭 — 모달이 열린 채 백드롭이 버튼을 가려 `page.click`은 실패한다. 반환된 비동기 작업을 기다리지 않으므로 뒤따르는 결과 대기는 그대로 둔다). 이 시점에 `#btnReload`가 `disabled`가 아님은 기존 잠금 규칙(`busy || loadingDetail || isDirty()`)으로 성립한다.
- **C2 동작 유지**: 1단계는 "같은 동작, 다른 구현"이다. 동작 변경(네이티브 `<dialog>`, 포인터 기반 드래그 등)은 후속 과제로 모은다(한 PR 한 관심사).
- **C3 fetch는 호출 시점에 `window.fetch`를 찾는다**(R5). 모듈 로드 때 참조를 붙잡지 않는다.
- **C4 CSRF 메타는 호출 시점에 읽는다**(기존 `csrfHeaders()`와 같음).
- **C5 사용자 입력은 텍스트로만 렌더링**: `dangerouslySetInnerHTML`·`innerHTML`·`eval` 금지. 답변 HTML은 `NoticeEditor.setHtml`에만 넘긴다(기존 경로 그대로).
- **C6 Bootstrap 모달 요소의 `className`·`style`·`aria-hidden`을 React가 바꾸지 않는다**: jQuery Bootstrap 4 모달이 이 속성들을 직접 조작하므로, React 쪽 prop은 마운트 때 한 번 정해지는 정적 값만 둔다(바뀌는 것은 `data-state` 하나).
- **C7 Enter 제출은 브라우저 기본 폼 제출만 쓴다**: 커스텀 `keydown` Enter 처리를 추가하지 않는다(가이드 `ime-safe-enter-submit` — 한글 조합 중 Enter는 기본 폼 제출을 막지만 커스텀 핸들러는 `isComposing` 검사가 필요하다).
- **C8 접근 규칙·서버 코드 무변경**: 번들 경로는 `/js/admin-react/**`(R9). `FaqPageController`는 모델 속성 그대로.

## 3. 핵심 설계 결정

### 결정 1 — 통합 방식: Thymeleaf 페이지 안의 React 아일랜드
| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 아일랜드 (채택)** | 세션·CSRF·사이드바·인가 무변경, 화면 단위로 점진 전환, 기존 시험 재사용 | 페이지마다 마운트 지점·초기 데이터 전달이 필요 |
| B. 관리자 SPA | 일관된 라우팅 | `SecurityConfig` 변경·사이드바 API·로그인 흐름 재설계(사전 협의 대상) |
왜: 목적(React 경험 + 새 프로젝트 기반)을 인가 변경 없이 달성한다. 2026-11-22 대화에서 사용자가 B를 보류했다.

### 결정 2 — 프런트엔드 위치와 산출물
- 소스: 저장소 루트 `frontend/`(`package.json`·`package-lock.json`·`.npmrc`·`tsconfig.json`·`vite.config.ts`·`src/`).
- 산출물: `build/frontend/`(Vite `outDir: '../build/frontend'`, `emptyOutDir: true`) → Gradle `processResources`가 `static/js/admin-react/`로 복사. **`src/main/resources`에 생성물을 두지 않는다**(커밋 사고·IDE 혼선 방지).
- 파일명: 진입점은 고정 이름(`faq-manage.js`, `entryFileNames: '[name].js'`). **1단계는 진입점 하나·동적 `import()` 없음 → 산출물이 JS 파일 하나**(공유 청크 없음)이고, 이를 성공 기준 S3으로 확인한다. 왜: 템플릿이 진입점 이름을 알아야 하고(기존 `/js/admin/*.js`와 같은 고정 이름 정책), 청크가 없으면 배포 교체 중 "옛 진입점이 지워진 청크를 찾는" 문제가 생기지 않는다. 진입점이 여러 개가 되는 2단계에서 공유 청크와 그 보존·캐시 정책을 다시 정한다. 매니페스트를 읽는 서버 코드는 두지 않는다.
- `base: '/js/admin-react/'`. 진입점은 `<script type="module">`로 싣는다.
- **CSS·자산은 번들에 넣지 않는다**(R2 리뷰 #2 — Vite에서 CSS를 import하면 별도 CSS 산출물이 생기고 백엔드 렌더링 HTML은 그 링크를 따로 출력해야 한다, Vite "Backend Integration" 문서): FAQ 전용 `<style>` 블록(`manage.html:10-30`)과 `admin-manage.css`·`quill.snow.css` 링크는 **템플릿에 그대로 둔다**. `frontend/src`에서 `.css`·이미지·폰트 import를 금지하고 `FrontendSourceConventionTest`가 잡는다.

### 결정 3 — Node 도구 체인을 Gradle에 넣는 방법
| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `com.github.node-gradle.node` 플러그인 7.1.0 (채택 후보)** | Node 버전 하나를 Gradle이 내려받아 로컬·CI·Docker가 같은 Node를 쓴다(JDK 정책과 같은 취지). CI·Dockerfile에 Node 설치 단계가 필요 없다 | 새 빌드 플러그인. 마지막 릴리스 2024-09-27(2년 무릴리스) — Gradle 8.14.7 호환은 **미확인 → 구현 1단계에서 실측**. 실패하면 B로 전환하고 보고 |
| B. `Exec` 태스크 + 시스템 Node | 플러그인 없음 | 로컬·CI(`setup-node`)·Dockerfile(Node 설치 또는 node 이미지 단계 + digest 고정·`check-image-refs.sh` 확장) 세 곳에 Node를 맞춰야 한다 |
- 설정: `node { download = true; version = '<Node 24 LTS>'; npmInstallCommand = 'ci'; nodeProjectDir = file('frontend') }`. 출처: 플러그인 문서 `docs/usage.md`(context7 `/node-gradle/gradle-node-plugin`) — `download`·`version`·`npmInstallCommand`·`nodeProjectDir`·`NpmTask`의 inputs/outputs 예시.
- 태스크: `frontendBuild`(`npm run build`, inputs `src/`·설정 파일·`package-lock.json`, outputs `build/frontend`), `frontendCheck`(`npm run check` = `tsc --noEmit -p tsconfig.json && tsc --noEmit -p tsconfig.node.json && vitest run`). **둘 다 `dependsOn npmInstall`을 명시한다** — 플러그인 7.1.0의 `NpmTask`는 기본으로 `npmSetup`에만 의존하고, `npmInstallCommand = 'ci'`는 `npmInstall` 태스크의 명령만 바꾼다(출처: 7.1.0 `NpmTask.kt`, R1 리뷰 #3).
- 연결: `processResources { from(tasks.named('frontendBuild')) { into 'static/js/admin-react' } }` — 경로 문자열이 아니라 **태스크 출력 참조**로 의존이 생기게 한다. `test`는 `-PbrowserTests=only`가 아닐 때 `frontendCheck`에 의존 → CI `test` job과 로컬 "전체 시험"이 프런트엔드 검사를 포함한다. `browser-test` job은 번들만 필요하다.
- **증분 빌드 입력**(R3 리뷰 #1 — 7.1.0 `NpmInstallTask`는 `package.json`·lock만 입력으로 추적하고 `.npmrc`는 빼며, `NpmTask`의 `nodeExtension`은 `@Internal`이다): `npmInstall`·`frontendBuild`·`frontendCheck` 모두에 `inputs.file('frontend/.npmrc')`와 `inputs.property('nodeVersion', node.version)`을 더한다. Node 버전은 한 곳(`node { version }`)에서만 정하고 npm은 Node 동봉본을 쓰므로(`npmVersion` 미지정) Node 버전이 npm 버전을 대표한다.
- **lock 생성은 Gradle이 내려받은 Node·npm으로 한다**(Node 24.22.2 동봉 npm 11.19.1 — 로컬 npm 11.9.0과 다르다). 같은 lock을 Windows(로컬)·Linux(CI `test` job·`prod-smoke` Docker 빌드)에서 깨끗한 설치 → 타입 검사 → 시험 → 번들 생성이 모두 통과해야 한다(S2·S3·S7).
- Node 다운로드 위치는 플러그인 기본값 `.gradle/nodejs`(이미 `.gitignore`·`.dockerignore` 대상).

### 결정 4 — npm 의존성 목록과 버전 규칙 (승인 대상)
**버전 규칙(새로 정함, 제안)**:
- 직접 의존성은 정확한 버전 고정(`^`·`~` 없음, `.npmrc save-exact=true`), `package-lock.json` 커밋, 설치는 `npm ci`.
- **배포 후 14일 지난 버전만 — 전이 의존성 포함 트리 전체**: lock을 만들거나 갱신할 때 `npm install --before=<오늘-14일>`로 해석해 트리 전체를 그 날짜 이전 배포본으로 제한한다(`before`는 npm 11.9.0에서 확인한 설정 — `npm config ls -l`. 배포 기간 설정 `min-release-age`는 로컬 npm 11.9.0에 없고 11.19.1 지원 여부는 미확인이라 쓰지 않는다). 기준일은 `frontend/README`가 아니라 커밋 본문에 남긴다. 이유: 새로 배포된 악성 버전이 발견·회수되기 전에 받아들이지 않기 위한 대기 기간이다(근거 없는 "며칠 안에 내려간다" 주장은 v2에서 삭제).
- **예외 — 보안 수정**: 알려진 취약점(npm audit·Trivy·권고)을 고치는 버전은 14일을 기다리지 않는다. 그 패키지만 올리고 사유와 권고 ID를 커밋 본문에 남긴다.
- **한계(수용)**: CI가 lock의 배포일을 다시 검증하지는 않는다(레지스트리 조회가 필요). PR의 lock diff 검토로 대신한다.
- `.npmrc ignore-scripts=true`(설치 스크립트 차단 — 빌드가 되는지 실측, 필요한 패키지가 있으면 보고). Dependabot `npm` 생태계 추가는 후속(결정 9).

| 패키지 | 버전(2026-11-08 이전 배포 중 최고) | 용도 | 런타임/개발 |
|---|---|---|---|
| `react`, `react-dom` | 19.3.1 (10-14) | UI | 런타임(번들에 포함) |
| `typescript` | 7.0.2 (07-08) | 타입 검사(`tsc --noEmit`) | 개발 |
| `vite` | 8.3.6 (11-05) | 번들링(Rolldown) | 개발 |
| `@vitejs/plugin-react` | 6.2.1 (11-02) | JSX 변환 | 개발 |
| `vitest` | 5.1.0 (11-02) | 단위 시험 | 개발 |
| `@types/react`, `@types/react-dom` | 19.3.0 (09-09) | 타입 | 개발 |
| `@types/node` | 24.19.2 (10-09) | `vite.config.ts` 타입 검사(`tsconfig.node.json`). Vite 8.3.6 optional peer `^20.19.0 \|\| >=22.12.0`(레지스트리 메타데이터) | 개발 |
| Node(Gradle 플러그인이 내려받음) | 24.22.2 LTS (10-27), 동봉 npm 11.19.1(nodejs.org `index.json`) | 실행 환경 | 빌드 |
배포일 출처: npm 레지스트리 `time` 메타데이터(2026-11-22 조회), 기준일 2026-11-08.

**넣지 않는 것과 이유**
- TanStack Query: 이 화면의 서버 상태 규칙(쓰기→재조회 분리, 초안이 있으면 재조회 금지, 세대 폐기, 자동 재전송 금지)이 특수해 라이브러리 기본 동작(포커스 재조회·자동 재시도)을 대부분 꺼야 한다. 1단계는 `useReducer` 상태 기계로 명시한다. 목록·필터 화면(3단계 게시글)에서 다시 검토.
- Testing Library·jsdom: 화면 동작은 기존 브라우저 시험 28건이 실제 Chromium으로 고정한다. 1단계 단위 시험은 DOM이 필요 없는 순수 로직(리듀서·API 클라이언트·사이트 표시 대상)만 다룬다. 컴포넌트 단위 시험이 필요해지면 추가.
- TypeScript 7은 네이티브(Go) 구현이고 타입 검사 자체가 플랫폼별 실행 파일로 돈다 — **미확인 → 구현 2단계에서 Windows·Linux 양쪽 실측**(Linux는 CI·Docker). 문제 시 6.0.3으로 내리고 보고. 타입 검사 범위: `tsconfig.json` = `src/**`(시험 파일 포함), `tsconfig.node.json` = `vite.config.ts`.

### 결정 5 — 코드 구조
```
frontend/src/
  api/http.ts        # fetch 래퍼: CSRF 헤더(호출 시점), JSON, 오류 메시지(403 고정 문구) — 화면 무관
  api/faq.ts         # FAQ·카테고리 엔드포인트 함수와 요청·응답 타입 — 화면 무관(재사용 대상)
  admin/shared/      # NoticeEditor·jQuery 전역 타입 선언, Bootstrap 모달 훅, 표시 대상(ContentScope 대응) 로직
  admin/faq/
    main.tsx         # 진입점: #faqManageRoot의 data-*를 읽어 마운트
    state.ts         # 순수 리듀서: 서버 트리·순서 초안·잠금 플래그·세대(loadSeq/detailSeq)
    FaqManage.tsx 외 # 목록·드래그·모달·폼 컴포넌트
```
- `api/`는 React·DOM 전역(`document`)에 의존하지 않는다 — CSRF 토큰 제공자는 주입한다(브라우저 구현이 메타를 읽음). 왜: 새 프로젝트·모바일 재사용(목적).
- **`http.ts` 반환 계약**(기존 `write()`의 판정 보존 — `manage.html:458-498`): 예외를 던지지 않고 다음 중 하나를 돌려준다.
  - `{ kind: 'network' }` — `fetch`가 응답 헤더를 받기 전에 실패(결과 불명 — 화면은 `UNKNOWN_RESULT_MESSAGE`, `syncFailed`, 자동 재전송 없음).
  - `{ kind: 'http', status, ok, body, bodyError }` — 헤더를 받았다. `204`면 본문을 읽지 않고 `body = null`. 그 밖의 응답은 JSON 파싱을 시도해 실패하면 `body = null, bodyError = true`. **`ok`(2xx)면 본문 실패와 무관하게 쓰기 성공**이다(기존과 같음 — 목록 재조회로 계속).
  - 오류 메시지 추출(`403` 고정 문구, 그 밖은 `body.message` 또는 기본 문구)은 별도 함수.
  - 단위 시험(`http.test.ts`): 204, 2xx 뒤 본문 파싱 실패, 헤더 전 실패, 403 고정 문구, CSRF 헤더(호출 시점 값). 화면 동작은 기존 `successWithUnreadableBody_doesNotLockScreen`·`writeResponseLost_showsUnknownResult`·`writeSucceeds_reloadFails_recoversWithoutDuplicate`가 고정한다.

### 결정 6 — 상태 관리: 렌더링 상태는 `useReducer`, 즉시 읽는 전환 정보는 ref
- **렌더링에 쓰는 상태**(서버 트리, 순서 초안, `busy`·`loadingDetail`·`syncFailed`·`writing`, 편집 대상, 알림 문구, 모달 모드)는 리듀서에 둔다. "잠금이 바뀌면 다시 그린다"(R1 `sync()`)는 React 렌더링이 보장한다.
- **같은 실행 흐름 안에서 즉시 읽혀야 하는 값은 ref에 두고 동기적으로 바꾼다**(R1 리뷰 #2 — `dispatch`는 현재 실행 중인 코드의 상태값을 바꾸지 않는다, React `useReducer` 문서): `loadSeq`·`detailSeq`(세대), `modalState`·`pendingClose`·`forceClose`(Bootstrap 이벤트 핸들러가 즉시 읽음 — 예: `closeModal()`은 `forceClose.current = true` 직후 `$(el).modal('hide')`를 부르고 `hide.bs.modal`이 그 값을 읽어 미저장 확인을 건너뛴다), `baseline`(미저장 판정), 잠금 판정에 쓰는 `busy`·`writing`·`loadingDetail`·`syncFailed`·`isDirty`의 최신값(이벤트 핸들러의 재검사용 — ref와 리듀서를 한 함수에서 함께 갱신).
- **외부 부수 효과(편집기 `setHtml`·모달 열기/닫기·`confirm`)는 핸들러가 ref 세대를 확인한 뒤에만 실행**한다 — 늦은 응답은 리듀서에 도달하기 전에 핸들러에서 버린다. 리듀서는 렌더링 상태 전이만 맡고 순수 함수로 시험한다(`state.test.ts`: 순서 초안 이동·`isDirty`·잠금 판정·쓰기 결과 분기).
- 이벤트 핸들러 안에서도 잠금을 ref로 다시 검사한다(버튼 `disabled`만 믿지 않음 — 기존 규칙 유지).
- **폼은 controlled 컴포넌트**다(R2 리뷰 #1): 카테고리 폼(`name`·`useYn`·`siteScope`)과 FAQ 폼(`categoryId`·`question`·`useYn`)의 값은 리듀서의 폼 모델이 소유한다. 미저장 판정 기준값(`baseline`)은 **DOM이 아니라 폼을 채울 때 쓴 모델 값**으로 만든다(기존 `categorySnapshot()`·`faqSnapshot()`의 같은 필드·같은 직렬화). 답변은 기존처럼 `editor.isChanged()`(편집기가 `setHtml` 때 자체 기준을 잡는다)로 따로 판정한다. 선택한 카테고리가 트리에서 사라지면 모델 값을 유지하고 "삭제된 카테고리 #ID" 옵션(`data-missing="true"`)을 렌더링한다(기존 `renderCategorySelect()`와 같은 규칙) — 선택값이 렌더링 결과에 좌우되지 않으므로 "렌더링 뒤에 기준값을 잡는" 순서 의존이 없어진다.
- **DOM 반영 순서**: 모달 열기는 `flushSync(() => dispatch(…모드·폼…))`로 모드 전환(`d-none`)과 폼 값을 DOM에 반영한 **뒤에** `setModalState('opening')`·`$(el).modal('show')`를 부른다(React 문서 "flushSync로 상태 갱신을 동기적으로 반영"). 닫기·`pendingClose`는 결정 6의 ref로 처리한다.
- 고정 시험(기존): 미저장 판정 `unchangedFormClosesWithoutConfirmation`·`baselineDoesNotLeakAcrossKinds`·`everyInputCountsAsAChange`·`answerChangeInEditModeCountsAsAChange`·`dirtyCloseAsksForConfirmation`, 저장·삭제 성공 시 confirm 없이 닫힘 `programmaticClosesSkipTheDiscardConfirmation`, 늦은 상세가 편집기를 덮지 않음 `closingWhileLoadingDetailDoesNotLockOrClobber`, 열림 전환 중 닫기 `detailFailureWhileModalShowingClosesThroughPendingClose`.

### 결정 7 — 기존 전역 스크립트 재사용
- 편집기: `NoticeEditor`를 그대로 쓰는 얇은 React 래퍼. 왜: sanitizer 1:1 계약(위험 영역 `common/html`)을 건드리지 않는다. `quill.js`·`notice-editor.js` 스크립트 태그는 템플릿에 남긴다.
- 모달: Bootstrap 4 jQuery 모달을 그대로 쓰고 이벤트(`shown`·`hide`·`hidden.bs.modal`)를 연결한다(C6). 왜: 기존 상태 기계·정적 백드롭·시험(R3)을 그대로 유지. 네이티브 `<dialog>` 전환은 관리자 화면 전체에 걸친 별도 작업(후속).
- **수명주기 계약**(R1 리뷰 #5 — `NoticeEditor`에는 `destroy`가 없다, `notice-editor.js:306`):
  - 모달 요소와 두 폼(카테고리·FAQ), 편집기 호스트 `<div id="faqAnswerEditor">`는 **항상 마운트**한다. 모드 전환은 기존처럼 `d-none` 클래스로만 하고 조건부 렌더링·`key` 변경을 하지 않는다.
  - 편집기는 `useEffect(…, [])` 안에서 `if (editorRef.current) return` 가드로 **한 번만** 만든다(StrictMode·effect 재실행에도 중복 생성 없음). 정리 함수는 두지 않는다(파괴 API가 없고 페이지 수명과 같다). StrictMode는 쓰지 않는다 — 운영 빌드만 쓰므로 효과가 없고, 위 가드로 안전성은 따로 확보한다.
  - Bootstrap 이벤트는 마운트 effect에서 네임스페이스(`.faqReact`)로 **한 번** 등록하고 정리에서 `off('.faqReact')`한다. 핸들러는 렌더링 시점 값을 캡처하지 않고 ref(결정 6)만 읽는다.
  - 고정 시험(추가): 모달을 카테고리·FAQ 모드로 3회 열고 닫은 뒤 `.ql-toolbar`가 정확히 1개, 마지막 열기에서 미저장 확인이 한 번만 뜬다(이벤트 중복 없음).
- 표시 대상: `ContentScope`는 서버가 그린 `<select>`에 기대므로 React에서는 같은 규칙(라벨·배지·없는 ID 보존 옵션)을 TS로 옮긴다. 사이트 목록은 템플릿이 숨긴 목록 요소의 `data-*`로 넘긴다. `content-scope.js`는 다른 화면이 계속 쓴다(이 화면의 스크립트 태그만 제거). 전환기 동안 같은 규칙이 두 벌이 되는 것은 수용한다(전 화면 전환 시 하나로).

### 결정 8 — 서버 → React 초기 데이터 전달
- 템플릿: `<div id="faqManageRoot" th:data-permissions="${#strings.setJoin(myPermissions, ',')}">` + 숨긴 `<ul id="faqScopeSites" hidden><li th:each … th:data-id th:data-active th:text></li></ul>`. 왜: Thymeleaf 기본 이스케이프만 쓰고 JSON 직렬화기(Jackson 2/3 혼재)에 기대지 않는다. 인라인 스크립트가 0개가 된다(향후 CSP 도입에 유리).
- `myPermissions`의 타입이 `Set<String>`인지와 `#strings.setJoin` 동작은 **구현 시 확인**(다르면 `listJoin` 등으로 맞춤).

### 결정 9 — 범위 밖(후속 과제 후보)
- 네이티브 `<dialog>` 전환(가이드 `dialog`), 포인터 기반 드래그·이동 안내 live region(가이드 `drag-and-drop`), Vite 개발 서버·HMR, CI npm 캐시, Dependabot `npm` 생태계, Trivy `fs`로 `package-lock.json` 스캔, CSP 도입.

## 4. 성공 기준
- S1 브라우저 시험 R3의 28건이 통과한다. 시험 코드 변경은 R4 2곳뿐이다.
- S2 `./gradlew test -PbrowserTests=exclude`가 통과하고, 이 실행에 `frontendCheck`(`tsc --noEmit` + `vitest run`)가 포함된다.
- S3 `build/frontend`의 산출물이 **`faq-manage.js` 파일 하나뿐**이다(청크·CSS·자산·매니페스트 없음 — 산출물 목록 전체를 확인). 템플릿은 그 파일 하나를 `<script type="module">`로 연결하고, 스타일은 기존 템플릿 `<style>`·CSS 링크가 맡는다. `./gradlew bootJar`의 jar에 `BOOT-INF/classes/static/js/admin-react/faq-manage.js`가 있다. prod 이미지(`prod-smoke`)가 `/js/admin-react/faq-manage.js`를 200·JavaScript 콘텐츠 타입으로 응답한다. 모듈 그래프 전체의 로딩 성공은 브라우저 시험이 판정한다(진입점 200만으로 판정하지 않음).
- S7 **깨끗한 checkout**(`frontend/node_modules`·`build/`·`.gradle/nodejs` 없음)에서 `./gradlew bootJar`와 `./gradlew test -PbrowserTests=exclude`가 추가 수동 단계 없이 통과한다(로컬 Windows 1회 + CI Linux).
- S8 정적 응답의 실제 캐시 헤더를 기록한다(R11 실측).
- S4 `admin/faq/manage.html`에 인라인 `<script>`(내용 있는 것)가 없다.
- S5 `git diff`에 `SecurityConfig`·`src/main/java`(컨트롤러·서비스)·마이그레이션 변경이 없다.
- S6 실기: ADMIN 골든 패스(등록·수정·순서 반영·삭제 왕복), MANAGER(FAQ READ만)로 쓰기 버튼 숨김·서버 403, 콘솔 오류 0.

## 5. 실패 모드와 고정 시험 (최대 5)
| # | 실패 모드 | 고정 시험 |
|---|---|---|
| F1 | 번들이 클래스패스·jar·이미지에 없어 화면이 빈다(태스크 연결 누락, Docker 빌드에서 Node 다운로드 실패) | `AdminReactBundleIntegrationTest`(MockMvc `GET /js/admin-react/faq-manage.js` 200·JS 타입) + `prod-smoke.sh`에 번들 응답 검사 한 줄 + 브라우저 시험 전체 |
| F2 | 늦게 온 목록·상세 응답이 초안·편집 중인 폼·편집기를 덮는다(세대를 리듀서에만 두어 부수 효과가 먼저 실행되는 회귀 포함) | 결정 6의 ref 세대 + `whileApplying_dragIsIgnored`·`closingWhileLoadingDetailDoesNotLockOrClobber`·`draftBlocksOpeningTheModal`·`whileLoadingDetail_formAndEditorAreLocked` |
| F3 | React 재렌더링이 Bootstrap이 붙인 모달 클래스·속성을 지우거나, 전환 정보가 비동기로 갱신돼 모달이 안 닫히거나·불필요한 confirm이 뜨거나·이벤트가 중복된다(C6·결정 6·수명주기 계약 위반) | `staticBackdropClosesOnlyByButtons`·`programmaticClosesSkipTheDiscardConfirmation`·`conflictWithHeldReloadStillAllowsClose`·`detailFailureWhileModalShowingClosesThroughPendingClose` + 추가 시험(3회 개폐 뒤 툴바 1개·confirm 1회) |
| F4 | 사용자 입력(카테고리 이름·질문)이 HTML로 해석된다(C5 위반) | `FrontendSourceConventionTest`(`frontend/src`에 `dangerouslySetInnerHTML`·`.innerHTML`·`eval(` 없음, 그리고 결정 2의 CSS·자산 import 없음) + 브라우저 시험 1건 추가: 이름 `<b>x</b>`가 글자 그대로 보이고 `b` 요소가 생기지 않음 |
| F5 | 쓰기 요청에 CSRF 헤더가 빠지거나 낡은 토큰을 쓴다(C4 위반) | `http.test.ts`(호출 시점 토큰 제공자 값으로 헤더 구성, 헤더 이름 기본값 `X-CSRF-TOKEN`) + 쓰기 브라우저 시험(`createAndDeleteCloseTheModal` 등 — 헤더가 없으면 403) |

## 6. 작업 단계
1. 브랜치 `feat/react-admin-faq`.
2. **빌드 골격 실측**: `frontend/` 생성, Gradle이 내려받은 Node·npm으로 `npm install --before=2026-11-08`(결정 4 패키지)해 lock 생성, 빈 진입점으로 `./gradlew processResources`·`bootJar` → jar 안 번들 확인, 깨끗한 checkout 재현(S7), 정적 응답 캐시 헤더 실측(S8), **증분 재실행 확인**(기존 작업 디렉터리에서 `.npmrc`만 바꾸면 `npmInstall`·`frontendBuild`가 `UP-TO-DATE`가 아니라 다시 실행되고, 되돌리면 다시 실행된다). 여기서 플러그인·Gradle 8.14.7 호환, TS 7(Windows), `ignore-scripts` 결과를 확인하고 결정 3·4의 "미확인"을 닫는다(실패 시 보고 후 대안). Linux 쪽은 PR CI(`test`·`prod-smoke`)로 닫는다.
3. `api/http.ts`·`api/faq.ts`(TDD: `http.test.ts`).
4. `state.ts` 리듀서(TDD: `state.test.ts` — 순서 초안·`isDirty`·잠금 판정·세대 폐기·쓰기 결과 분기).
5. 표시 대상 로직(TDD) → 편집기·모달 래퍼 → 컴포넌트.
6. 템플릿 교체(마운트 지점·초기 데이터, 인라인 스크립트 제거), 시험 R4 2곳 수정, F4 브라우저 시험 추가, `FrontendSourceConventionTest`·`AdminReactBundleIntegrationTest`.
7. Dockerfile 확인(변경 필요 여부 — 플러그인 방식이면 무변경 예상)·`.dockerignore`(`frontend/node_modules`)·`.gitignore`·`prod-smoke.sh` 번들 검사.
8. 영향 범위 시험: `--tests 'com.cms.admin.faq.*' --tests 'com.cms.admin.site.ContentScopeAdminBrowserTest' --tests '*ConventionTest' --tests 'com.cms.config.SecurityConfigTest'` + `frontendCheck`.
9. 실기 검증(playwright, S6) → 문서(`admin/faq` CLAUDE.md, 루트 CLAUDE.md "구조상 알아둘 점", `docs/development-workflow.md`의 빌드 안내).

## 7. 리스크
- **빌드 위험**: 모든 빌드(로컬·CI 3 job·Docker)가 Node 다운로드와 npm 레지스트리 접근에 의존하게 된다. 네트워크 장애 시 빌드 실패 — 기존 Gradle 의존성 다운로드와 같은 성격으로 수용.
- 빌드 시간 증가(Node 다운로드·`npm ci`·Vite 빌드). 측정해 보고한다.
- IntelliJ 자체 실행(Gradle 위임 없이)에서는 번들이 없어 FAQ 화면이 빈다 — 문서에 안내.
- 화면 하나에 같은 규칙(표시 대상)이 두 벌 존재(결정 7 수용).
- 플러그인 유지보수 정체(결정 3) — 대안 B로 갈아탈 수 있게 Gradle 설정을 한 블록에 모은다.

## 구현·검증 결과 (2026-11-22)

### Context
계획 v4(codex 리뷰 4라운드 ship, 사용자 승인 — 새 의존성·npm 버전 규칙 포함)대로 FAQ 관리 화면을 React 아일랜드로 옮겼다. 서버 코드(`src/main/java`)·`SecurityConfig`·스키마·API 무변경(S5).

### 핵심 확정 사항
- **실측으로 닫은 "미확인"**: 플러그인 7.1.0은 Gradle 8.14.7에서 경고 없이 동작(`--warning-mode all`). TS 7.0.2 타입 검사는 Windows에서 통과(Linux는 PR CI `test` job). lock에 Linux·Windows 네이티브 패키지(`@rolldown/binding-*`·`@typescript/typescript-*`·`lightningcss-*`)가 모두 들어갔고, 설치 스크립트가 있는 패키지는 macOS 전용 `fsevents`뿐이라 `ignore-scripts=true`로 빌드가 된다. `myPermissions`는 `Set<String>`(`#strings.setJoin`). **R11 실측(S8)**: 번들과 기존 `/js/admin/notice-editor.js` 모두 `Cache-Control: no-store` + `Last-Modified` — 고정 파일명의 캐시 문제가 없다.
- **구현 중 계획과 달라진 점(모두 동작 보존 방향)**:
  1. 상태: `useReducer` 대신 `useState` + 같은 값을 ref에 동기 반영하는 `update()` — 결정 6의 "ref와 리듀서를 한 함수에서 함께 갱신"을 가장 단순하게 구현한 것. 순수 로직은 계획대로 `state.ts`에 두고 단위 시험했다.
  2. **GET 요청에 `method`를 싣지 않는다**(발견): 브라우저 시험의 fetch 가로채기 3종(`holdFirstResponse`·`failDetailWhenModalShows`·즉시 404 스텁)이 "`options.method`가 없으면 GET"으로 상세 조회를 식별한다. 처음에 `method: 'GET'`을 넣어 3건이 시간 초과로 실패했다 — DOM 계약과 같은 종류의 **요청 모양 계약**이라 옛 코드와 같게 맞췄다(`http.test.ts`가 고정).
  3. `#faqModal[data-state="closed"]` 초기값을 정적 prop으로 둔다(발견 — 첫 전환 전 `null`이라 `draftBlocksOpeningTheModal` 실패). 이후 값은 직접 쓴다(C6 유지).
  4. 모달은 `createPortal`로 템플릿의 `#faqModalRoot`(옛 화면처럼 `#wrapper` 밖)에 그린다.
  5. 비브라우저 시험 이름은 `FaqReactPageIntegrationTest`(계획의 `AdminReactBundleIntegrationTest`) — 번들 응답에 더해 화면 HTML(모듈 하나·인라인 스크립트 0·권한 키)까지 보므로 `admin.faq`에 둔다.
  6. controlled 폼의 카테고리 선택값 규칙을 `resolveCategorySelection`으로 명시(옛 선택 박스의 "이전 값 유지, 없으면 첫 옵션"을 브라우저 대신 계산).
- 번들: `faq-manage.js` 하나, 245KB(gzip 77KB).

### 구현 파일
- 빌드: `build.gradle`(플러그인·`node {}`·`npmInstall` 입력·`frontendBuild`·`frontendCheck`·`processResources`·`test` 의존), `.gitignore`·`.dockerignore`(`frontend/node_modules`), `scripts/ci/prod-smoke.sh`(번들 200·JavaScript 검사). `Dockerfile`·CI 워크플로 **무변경**.
- 프런트엔드: `frontend/{package.json,package-lock.json,.npmrc,tsconfig.json,tsconfig.node.json,vite.config.ts}`, `src/api/{http,faq}.ts`, `src/admin/shared/{csrf.ts,globals.d.ts,siteScope.ts}`, `src/admin/faq/{main.tsx,FaqManage.tsx,FaqTreeView.tsx,FaqModal.tsx,state.ts}` + 단위 시험 4개.
- 템플릿: `templates/admin/faq/manage.html` 1,030줄 → 71줄(인라인 스크립트 0, `content-scope.js` 제거).
- 시험: `FaqModalBrowserTest`(R4 2곳 프로그램 클릭 + 새 2건), `FaqReactPageIntegrationTest`(3건), `FrontendSourceConventionTest`(2건).
- 문서: 루트 `CLAUDE.md`, `com.cms.admin.faq` `CLAUDE.md`.

### 검증 결과
- 프런트엔드 단위 시험 32건(`frontendCheck` — `tsc` 2종 + vitest), TDD로 실패 확인 후 구현.
- 브라우저 시험: `FaqAdminOrderBrowserTest` 10·`FaqModalBrowserTest` 21(기존 19 + 새 2)·`ContentScopeAdminBrowserTest` 5 = **36건 통과**(R3의 "9·18건"은 계획 작성 때 잘못 센 숫자 — 실제 10·19). 첫 실행은 30/34 통과, 실패 4건은 위 "달라진 점" 2·3으로 해결.
- 영향 범위 비브라우저 시험(`admin.faq.*`·`publicweb.faq.*`·`*ConventionTest`·`SecurityConfigTest`) 224건 통과.
- **변이 실험**: 번들 복사 경로 변경 → `FaqReactPageIntegrationTest.bundleIsServedFromStaticPath` 실패 / 카테고리 이름을 `dangerouslySetInnerHTML`로 → `userInputIsRenderedAsText`(:748) 실패 / Bootstrap 구독 정리 제거·렌더마다 재등록 → `repeatedOpenCloseKeepsOneEditorAndOneConfirmation`(:777, 확인창 중복) 실패. 모두 원복.
- 증분 빌드: 변경 없음 → `npmInstall`·`frontendBuild` UP-TO-DATE, `.npmrc`만 변경 → 둘 다 재실행, 되돌림 → 재실행.
- **Linux Docker 빌더**(`docker build --target builder`): Node 내려받기 → `npm ci`(46개) → Vite(Linux Rolldown) → `faq-manage.js`가 jar에 포함. 확인용 이미지는 삭제.
- 실기(playwright, bootRun 8081, dev DB): 쓰기 권한 MANAGER로 카테고리 등록 → FAQ 2개 등록(편집기 입력) → 네이티브 드래그로 순서 초안(초안 막대·[새 …]·[새로고침] 잠김) → [순서 반영](DB `ord` 0·1, `faq_lock.version` 증가) → 수정 저장 → 새로고침 뒤 유지 → 삭제 2건·카테고리 삭제(확인창). READ만 위임받은 MANAGER: 권한 키 `FAQ:READ`, [새 …] 숨김, 손잡이 `draggable="false"`, ▲▼ 비활성, 상세는 열리고 저장·삭제 숨김, API 직접 DELETE·POST → 403. 감사 로그 8종 정상 기록. 검증 계정·데이터·로그는 지워 dev DB 원복(`faq_lock.version`만 단조 증가 값으로 남김). 스크린샷 `docs/screenshots/faq/react-faq-{modal,order-draft}.png`(로컬 전용).
- **깨끗한 checkout(S7) + 전체 비브라우저 시험**: `git ls-files -co --exclude-standard`만 scratchpad에 복사(`node_modules`·`build`·`.gradle` 없음)해 `./gradlew bootJar test -PbrowserTests=exclude` → Node 내려받기·`npm ci`·번들·`bootJar`·`frontendCheck`(32건)까지 수동 단계 없이 진행, **313개 클래스 3,281건 통과·실패 0**(건너뜀 12 — 기존 조건부 시험), 8분 31초. **전체 브라우저 시험**(`./gradlew test -PbrowserTests=only`, PR 직전 code-review-loop 마무리): 22개 클래스 133건 통과·실패 0, 3분 24초.
- **코드 리뷰**(code-review-loop): codex `review` 1라운드 지적 0 → 리뷰 초점이 있어 `adversarial-review`(실패 모드 F1~F5 + 초점) 1라운드 approve·지적 0. 32KB인 `FaqManage.tsx`가 리뷰 도구의 새 파일 상한(24KB)에 걸리지 않게 `git add -N`으로 diff에 포함했다.

### 이슈
- Docker Desktop 미기동으로 첫 브라우저 시험이 `initializationError`(환경 문제 — 기동 후 해결).
- 콘솔 경고 "Blocked aria-hidden on an element because its descendant retained focus": 모달이 닫힐 때 포커스가 모달 안 버튼에 남은 채 Bootstrap 4가 `aria-hidden`을 건다. **옛 방식 그대로인 로그아웃 모달에서도 같은 경고가 나 기존 동작**이다(이번 변경과 무관 — 후속 `<dialog>` 전환 대상).
- dev DB를 직접 바꾼 권한은 앱의 권한 캐시가 반영하지 않아(재로그인으로도) 팝업 화면 비교는 하지 못했다 — 같은 화면의 로그아웃 모달로 대신 비교했다.

### 후속
- #296 관리자 React 2단계: 메뉴 편집 화면 전환(공유 청크·캐시 정책 결정 포함)
- #297 관리자 React 3단계: 게시글 관리 화면 전환(TanStack Query 재검토)
- #298 관리자 모달을 네이티브 `<dialog>`로 전환(aria-hidden 포커스 경고 해소)
- #299 npm 의존성 취약점 감시(Dependabot npm + package-lock 스캔)
- #300 FAQ 순서 편집 접근성 보강(이동 안내·터치 드래그, deferred)
- 제안하지 않음: Vite 개발 서버(HMR)·CI npm 캐시 — 필요가 생기면 다시 본다.

## 개정 이력
- v1 (2026-11-22): 최초 작성.
- v2 변경 (R1 codex 리뷰 8건 — 수용 7, 일부 수용 1):
  - #1 수용: R4 시험 수정을 `page.evaluate`의 프로그램 클릭으로 명시(백드롭이 버튼을 가린다).
  - #2 수용: 결정 6 — 즉시 읽는 전환 정보·세대는 ref, 외부 부수 효과는 세대 확인 뒤에만. 기존 고정 시험 명시.
  - #3 수용: `frontendBuild`·`frontendCheck`에 `dependsOn npmInstall`, `processResources`는 태스크 출력 참조, S7(깨끗한 checkout) 추가.
  - #4 수용: `http.ts` 반환 계약(`network` / `http`+`bodyError`)과 단위 시험 5건.
  - #5 수용: 모달·편집기 수명주기 계약(항상 마운트, ref 가드 1회 생성, 네임스페이스 구독·정리, StrictMode 미사용) + 시험 1건 추가.
  - #6 일부 수용: R11을 "실측 예정"으로 정정, 1단계 산출물을 파일 하나로 제한(S3), S8 추가. **기각**: 버전별 자산 보존 정책 — 청크가 없어 대상이 없다. 진입점이 여러 개가 되는 2단계에서 정한다.
  - #7 수용: 14일 규칙을 `npm install --before`로 트리 전체에 적용, 보안 수정 예외, CI 재검증 없음을 한계로 명시, 근거 없는 문장 삭제. `min-release-age`는 로컬 npm 11.9.0에 없어 쓰지 않음.
  - #8 수용: `@types/node` 24.19.2 + `tsconfig.node.json`, lock은 Gradle Node(npm 11.19.1)로 생성, Windows·Linux 양쪽 통과를 조건으로.
- v3 변경 (R2 codex 리뷰 2건 — 모두 수용):
  - #1 수용: 결정 6에 controlled 폼·모델 기준 `baseline`·`flushSync` 뒤 모달 열기 순서 추가, 미저장 판정 기존 시험 5건 명시. 이유: 기존 코드가 필드를 채운 뒤 기준값을 읽는 순서(`manage.html:907,959`)를 React에서는 DOM 대신 모델로 보장해야 한다.
  - #2 수용: 결정 2에 CSS·자산 import 금지(스타일은 템플릿 유지), S3을 "산출물 전체가 JS 파일 하나"로 강화, 규약 시험에 CSS import 검사 추가.
- v4 변경 (R3 codex 리뷰 1건 — 수용):
  - #1 수용: `npmInstall`·`frontendBuild`·`frontendCheck`에 `.npmrc`·Node 버전을 입력으로 추가, 빌드 골격 실측에 증분 재실행 확인 추가. 이유: 플러그인이 `.npmrc`를 추적하지 않아 설정만 바뀌면 옛 설치 결과가 쓰인다.
