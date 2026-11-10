# PLAN — 대표 이미지의 공개 메인 썸네일 + 상세 `og:image`

> 출처: `adversarial-review/project-direction-roadmap.md` "후속 과제 — 게시판 정의 고도화 완료 시 기록 (2026-10-30)" ① — 상세 화면·공개 메인의 대표 이미지 표시(+ `og:image`).
> 상태: **구현·검증 완료(2026-11-03, 브랜치 `feat/home-cover-og-image`, 미커밋)** — 적대적 리뷰 2라운드 ship, 사용자 승인 후 구현. D2는 구현 중 v3로 변경. 결과는 문서 끝 "구현·검증 결과". 코드 리뷰 루프 2라운드 approve, 전체 `./gradlew test` 통과. 브랜치는 승인 뒤 만든다.

## 개정 이력

- v1(2026-11-02): 최초 작성.
- v3 변경(구현 중 실측, 2026-11-03): **D2 A(`<img alt="">` 겹침)를 폐기하고 CSS `background-image` 방식으로 바꿨다.** 계획이 리스크로 적어 둔 "브라우저별 깨진 이미지 렌더링"이 실측으로 확인됐다 — Chromium(Playwright)은 `alt=""`여도 크기가 지정된(`position:absolute; inset:0`) 깨진 `<img>`의 좌상단에 깨진 이미지 아이콘을 그린다(실패한 칸 스크린샷이 `<img>`를 뺀 칸과 달랐다). 대체: 바깥 칸 `.home-post-thumb`이 단색+아이콘 자리표시, 안쪽 `span.home-post-thumb-img`의 인라인 `background-image:url(/content-images/{id}?size=thumb)`가 그 위를 덮는다 — 실패하면 안쪽이 투명해 자리표시가 남는다(브라우저의 깨진 이미지 처리에 의존하지 않음). 인라인 `th:style`은 Long 대표 id뿐(팝업 `th:style` 선례, CSP 없음). 대가: 배경 이미지는 `loading="lazy"`가 없어 메인을 열면 썸네일 ≤5건을 항상 요청한다(D1·D5가 이미 5건으로 묶음). 시험은 컨벤션(style 한 곳·`<img>` 재도입 금지)과 브라우저 화면 일치 비교로 고정.
- v2 변경(리뷰 R1, P2 2건): ① **부분 수용** — 크롤러가 실제로 이미지를 고르는지는 공개 도메인이 있어야 확인할 수 있어 이 환경에서는 불가능하다. 시험 계획에 "구조 검증(메타 존재·절대 URL·그 URL의 공개 응답)까지가 이번 완료 범위, 실제 공유 크롤러 확인은 도메인 확정 후 수동"이라고 명시했다. **기각: `og:title`·`og:type`·`og:url` 추가** — 요청 범위가 `og:image`이고 주요 크롤러는 `<title>`로 대체한다. 필요해지면 별도 후속. 480px 썸네일 수용은 리스크 절에 이미 기록. ② **수용** — 버킷 키가 (규칙, IP)뿐이라 같은 IP의 여러 방문자가 메인을 열면 상세 본문 이미지·크롤러 요청과 한 버킷을 소진한다. D5에 "허용하는 동작"으로 명시(한도 수치 240/60초는 #192에서 사용자가 유지로 결정, 배너 이미지의 공유 IP 수용 선례)하고 메인 한 번의 썸네일 요청 수 ≤ `SECTION_SIZE`를 시험으로 고정한다.

## Context

대표 이미지(`post.cover_image_id`, PR 5)는 지금 공개 **목록**(갤러리·웹진)에서만 쓰인다. 공개 메인의 "새 글"은 제목·게시판·날짜만 보이고, 상세를 링크로 공유하면 미리보기 이미지가 없다. 이 계획은 ① 공개 메인 "새 글"에 대표 썸네일을 보이고 ② 상세 `<head>`에 `og:image`를 넣는다. **스키마·`SecurityConfig`·URL 인가 규칙·의존성 변경 없음**(쿼리 프로젝션 한 필드와 템플릿 둘, 컨트롤러 모델 한 값).

## 정찰 결과(코드에서 확인한 사실)

| 대상 | 사실 |
|---|---|
| 메인 쿼리 | `PostRepositoryImpl.findLatestPublished` — 게시판 `publicYn ∧ ¬deleted ∧ 사이트 소속`, 글 `¬deleted ∧ isPublished(useYn ∧ [publishStart,publishEnd))`을 한 조건 객체로 고정, `Projections.constructor(PublishedPostRow, id, boardId, slug, name, title, createDate)`. 공지 섹션(`boardId` 지정)과 새 글 섹션(`excludeSystemBoards`)이 같은 메서드·같은 행 타입을 쓴다. `PublishedPostRow`를 `new`로 만드는 시험은 없다(리포지토리 시험은 쿼리 결과만 단언). |
| 메인 DTO·템플릿 | `PublicHomePost.from(row)` → `home.html`의 두 `<li>`. `home.html`은 `<script`·`th:utext`·`[(`·`storageKey` 금지(`PublicHomeTemplateConventionTest`). 팝업만 별도 조각. |
| 대표 이미지 공개 판정 | 공개 이미지 `GET /content-images/{id}?size=thumb`는 원본과 **같은 판정**(`viewableImage` → `existsPublishedPostRef(imageId, now)`), 썸네일 행이 없으면(원본 긴 변 ≤ 480) 원본을 그대로 돌려준다. 썸네일 긴 변 최대 480px JPEG. 응답은 `no-store`. 익명 한도 `content-image` 240/60초(로그인 면제). |
| 불변식 | "대표 ∈ 그 글의 저장 후 참조"는 저장 때 서비스가 지킨다. 롤백한 이전 앱이 깰 수 있으나, 그 경우에도 공개 판정은 이미지 요청 시점에 한 번 더 일어난다(다른 공개 글이 참조하는 이미지라면 공개 이미지이고, 아니면 404). |
| 목록의 실패 대체 | 갤러리·웹진 목록은 외부 스크립트 `board-thumb-fallback.js`가 실패한 썸네일을 CSS 자리표시로 바꾼다. **메인은 스크립트 금지**라 그대로 쓸 수 없다. |
| 상세 | `PublicBoardController.renderDetail(PublicPostDetail, Model)` 한 곳. `PublicPostDetail.from`에 `coverImageId`는 없다. `<head>`에 외부 스크립트 `board-content-image-fallback.js` 하나(컨벤션 시험이 스크립트를 외부 파일로만 허용). |
| 절대 URL 원천 | `app.base-url`(`APP_BASE_URL`, prod 필수·dev 기본 `http://localhost:8080`)가 이미 있고 `PasswordResetService`가 기동 시 형식을 검증한다(userinfo/query/fragment 없는 http(s)). 앱은 `forward-headers-strategy`를 쓰지 않으므로 요청의 Host로 절대 URL을 만들면 프록시 뒤에서 틀린다. |
| 레이트리밋 | 메인 1회가 `content-image` 버킷에 쓰는 요청 = 썸네일 수. 메인 자체는 120/60초. |

## 핵심 쟁점과 결정

### D1. 메인의 어느 섹션에 썸네일을 넣나 — **"새 글" 섹션만**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 새 글만(최대 5장)** | 메인 로드당 추가 요청 ≤ 5. 공지는 텍스트 성격이라 현행 유지 | 공지 글의 대표는 메인에서 안 보임 |
| B. 공지·새 글 모두(최대 10장) | 일관됨 | 요청 2배 — 메인 24회/분이면 `content-image` 버킷(240) 소진, 같은 IP의 상세 본문 이미지까지 영향 |

**결정 A**. 공지 섹션은 `PublicHomePost`에 값이 실려도 템플릿이 쓰지 않는다. 공지에 넣고 싶어지면 한도부터 다시 본다.

### D2. 메인은 스크립트 금지 — 실패 대체는 **CSS만**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 자리표시 칸 위에 `<img alt="">`를 겹침 — 실측으로 기각(v3: Chromium이 깨진 아이콘을 그림). 최종은 칸 안쪽 span의 CSS `background-image`** — 칸 자체가 단색+아이콘 배경이고 이미지는 그 위에 덮는다. 이미지가 실패하면 깨진 `<img alt="">`는 아무것도 그리지 않아 배경이 보인다 | 스크립트·규칙 변경 없음. 목록의 `.board-thumb`/`.board-thumb-empty` 시각을 재사용 | 브라우저별로 `alt=""` 깨진 이미지가 그려지는 방식이 달라 **실기(Chromium)로 확인 필요** — 확인 못 하면 B |
| B. 메인에 외부 스크립트 1개 허용 | 목록과 동일한 방식 | 메인의 "JS 없이 동작" 규약·`PublicHomeTemplateConventionTest` 변경 필요(팝업 때처럼 협의 사안) |
| C. 대표가 있어도 썸네일을 안 보임(텍스트만) | — | 목표 미달 |

**결정 A** (B는 A가 실기에서 실패할 때만). `home.css`에 칸 스타일을 추가한다. 이미지 칸 크기는 고정 비율(4:3, 폭 약 4.5rem)이라 레이아웃이 요청 결과에 따라 흔들리지 않는다. 대표가 **없는** 글은 칸 자체를 그리지 않는다(메인은 목록형이라 자리표시가 의미 없고 줄 정렬만 어긋난다).

### D3. `og:image` 절대 URL — **`app.base-url` + 사이트 무관 경로**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `app.base-url` 뒤에 `/content-images/{id}?size=thumb`** | 이미 필수 설정·기동 검증됨, 프록시 헤더 신뢰 문제 없음 | dev 기본값은 `http://localhost:8080`(dev에서는 크롤러가 없으니 무해) |
| B. 요청의 scheme/host로 조립 | 설정 불필요 | 프록시 뒤에서 내부 호스트가 새고, Host 헤더 위조가 메타에 반영(캐시 오염) |
| C. 사이트 설정에 도메인 컬럼 | 사이트별 도메인 | 스키마 변경 — 범위 밖 |

**결정 A**. 컨트롤러가 `@Value("${app.base-url}")`를 받아 끝의 `/`를 제거한 값을 쓰고, 모델 `ogImageUrl`(없으면 null)을 상세 템플릿에 넘긴다. 템플릿은 `<meta th:if="${ogImageUrl != null}" property="og:image" th:content="${ogImageUrl}">`로 **속성 이스케이프된 `th:content`**만 쓴다. 대표가 없으면 메타 자체가 없다. 상세 화면 자체가 공개 판정을 통과한 글에서만 렌더링되므로 "비공개 글에는 메타 없음"은 상세 404와 같은 경로로 보장된다. `og:title`·`og:type` 등 다른 메타는 범위 밖(요청은 `og:image`).

### D4. 상세 DTO — `PublicPostDetail.coverImageId` 추가

`PublicPostDetail.from(board, post, attachments)`에 `coverImageId(post.getCoverImageId())`를 더한다(공개 DTO에는 이미 목록 DTO `PublicPostSummary`에 같은 필드가 있다). 컨트롤러가 이 값으로 URL을 만든다.

### D5. 레이트리밋·캐시 — **변경 없음, 공유 버킷 소진은 수용**

메인 로드당 썸네일 요청은 많아야 5건(`SECTION_SIZE`, 시험으로 고정)이다. 버킷 키는 (규칙, IP)라 같은 IP의 방문자 50명이 짧게 메인을 열면 250건이 되어 `content-image` 버킷(240/60초)이 비고, 그 사이 같은 IP의 상세 본문 이미지·`og:image` 크롤러 요청도 429가 된다(썸네일은 `no-store`라 재방문도 소비). **이 동작을 허용한다** — 한도 수치는 #192에서 사용자가 유지로 결정했고(올리면 IP당 전송량 상한도 커진다), 상세 본문 이미지는 #192의 실패 안내 상자가, 메인 썸네일은 D2의 자리표시가 받는다. 한도 분리(별도 규칙)는 구분이 쿼리(`?size=thumb`)뿐이라 경로 패턴으로 못 하고 공개 경로 변경이라 이번 범위 밖. 크롤러가 `og:image`를 가져오는 요청도 같은 버킷을 쓰지만 글당 1회 수준이다. 썸네일은 `no-store`라 메인을 열 때마다 다시 받는다 — 숨긴 글이 캐시에 남지 않게 하는 기존 계약이라 건드리지 않는다.

## 구현 범위(예상 파일)

- `PublishedPostRow`(+`coverImageId`), `PostRepositoryImpl.findLatestPublished`(프로젝션에 `post.coverImageId`), `PublicHomePost`(+`coverImageId`), `home.html`(새 글 항목에 썸네일 칸), `home.css`.
- `PublicPostDetail`(+`coverImageId`), `PublicBoardController`(base-url 주입·`ogImageUrl` 모델), `board/detail.html`(`<meta>`).
- 지침: `publicweb/home`·`publicweb/board`·`admin/board`의 `CLAUDE.md`, `docs/deployment.md`(`APP_BASE_URL`이 공유 미리보기 절대 URL의 원천임), 로드맵 후속 ① 완료 표기는 `/updateRoadmap` 몫.

## 시험 계획

- `PostRepositoryDataJpaTest`: `findLatestPublished`가 `coverImageId`를 싣는다(있음/없음), 기존 공개 경계 단언 그대로.
- `PublicHomeIntegrationTest`: 대표 있는 새 글의 썸네일 `<img src="/content-images/{id}?size=thumb">` 노출, 대표 없는 글은 칸 없음, **공지 섹션에는 썸네일 없음**, 비공개·예약·만료·삭제 글은 응답에 이미지 id도 나오지 않음.
- `PublicHomeTemplateConventionTest`: 기존 규칙 통과(스크립트 없음) + 썸네일 `<img>`가 `alt=""`·`loading="lazy"`.
- 상세 통합 시험(`PublicBoardIntegrationTest` 인접): 공개 글+대표 → `og:image` 절대 URL(base-url 접두), 대표 없음 → 메타 없음, 비공개·예약·삭제 글 → 404(메타 응답 자체 없음), 이스케이프(base-url에 특수문자 없음은 기동 검증이 보장하므로 id는 숫자뿐).
- `PublicBoardTemplateConventionTest`: 기존 규칙 통과.
- 메인 응답 안의 `?size=thumb` 참조가 `SECTION_SIZE`(5)를 넘지 않음(대표가 있는 새 글을 6개 이상 만들어 확인) — D5의 요청 수 상한.
- **완료 범위 한계**: 실제 공유 크롤러(Facebook·Kakao 등)가 이미지를 고르는지는 공개 도메인 확정 후 수동 확인이다. 이번에는 메타 존재·절대 URL·그 URL의 공개 응답(200, `image/*`)까지만 검증한다.
- 브라우저 시험(`BoardThumbFallbackBrowserTest` 형식): 익명으로 메인 열기 → 썸네일 정상 표시, 이미지 요청을 429로 가로챘을 때 깨진 아이콘이 아니라 자리표시 칸이 보임(D2 A의 실증). 실기(Playwright) 스크린샷.

## 리스크

- D2 A가 Chromium/Firefox에서 기대대로 그려지지 않을 수 있다 → 실기에서 먼저 확인하고 안 되면 B로 협의.
- `app.base-url`이 실제 공개 주소와 다르면 `og:image`가 틀린 호스트를 가리킨다(운영 설정 문제) → `docs/deployment.md`에 명시.
- 썸네일 긴 변 ≤ 480px라 일부 SNS 권장(1200×630)보다 작다 — 수용(원본을 쓰면 전송량·한도 부담이 커진다).

## 구현·검증 결과 (2026-11-03, 브랜치 `feat/home-cover-og-image`, 미커밋)

### Context
대표 이미지를 공개 목록 밖에서도 쓴다 — 공개 메인 "새 글" 썸네일과 공개 상세 `og:image`. 스키마·`SecurityConfig`·URL 인가 규칙·의존성 변경 없음(쿼리 프로젝션 한 필드, 템플릿 둘, 작은 컴포넌트 하나).

### 핵심 확정 사항
- D1 썸네일은 "새 글" 섹션만(≤5건), 공지 섹션은 안 씀. D3 `og:image` 절대 URL은 `app.base-url` 기반(`PublicOgImage`), 대표 없으면 메타 없음. D5 한도 변경 없음·공유 버킷 소진 수용. 다른 OG 메타는 범위 밖(R1 기각).
- **D2는 구현 중 바뀌었다(v3)**: `<img alt="">` 겹침 방식을 Chromium 실측으로 폐기 — 크기가 지정된 깨진 `<img>`에 깨진 아이콘이 그려졌다(실패 칸 스크린샷이 `<img>`를 뺀 칸과 달랐다). 최종은 안쪽 `span`의 CSS `background-image`(인라인 `th:style`, Long id뿐). 실패하면 투명해져 자리표시가 남는다.
- 계획과 달라진 점(보고): 썸네일이 있는 줄은 글자를 세로 가운데로 맞췄다(`.home-post-item a:has(.home-post-thumb)`) — 실기 스크린샷에서 글자가 칸 위쪽에 붙어 보여서.

### 구현 파일
- 메인: `PublishedPostRow`(+`coverImageId`)·`PostRepositoryImpl.findLatestPublished`(프로젝션)·`PublicHomePost`·`templates/public/home.html`·`static/css/public/home.css`
- 상세: `PublicPostDetail`(+`coverImageId`)·`publicweb/board/service/PublicOgImage`(신규)·`PublicBoardController`(`ogImageUrl` 모델)·`templates/public/board/detail.html`
- 시험: `CoverImageExposureIntegrationTest`(신규 6)·`HomeThumbFallbackBrowserTest`(신규 1, 실제 Chromium)·`PostRepositoryDataJpaTest`(+1)·`PublicHomeTemplateConventionTest`(+1)·`PublicBoardTemplateConventionTest`(+1)
- 문서: `docs/deployment.md`(`APP_BASE_URL` 설명 + "공개 메인 썸네일·og:image")·`publicweb/home`·`publicweb/board`·`admin/board`의 `CLAUDE.md`

### 검증 결과
- 범위 시험(`com.cms.publicweb.*` + `com.cms.admin.board.*` + `*ConventionTest`): **737건 통과, 실패 0·건너뜀 0**. 전체 `./gradlew test`는 코드 리뷰 루프 마무리(또는 `commitPR` 직전)에 1회.
- 시험이 실제로 회귀를 잡는지: `<img alt="">` 방식 상태에서 브라우저 시험이 실패했다(깨진 아이콘 검출) — D2 전환의 근거이자 변이 증거. 컨벤션 시험이 `<img>` 재도입·정적 `style`·`th:style` 추가를 막는다.
- 실기(bootRun + 실제 관리 API로 데이터 생성 + Playwright): 1200×800 PNG 업로드 → 대표 지정 글 생성 → 썸네일 파일 생성(39,693B). ① 메인: 대표 있는 글만 썸네일 칸(`background-image:url(/content-images/112?size=thumb)`), 대표 없는 글은 칸 없음, 비노출 대표 글 미노출, 스크립트 0개 ② 상세: `<meta property="og:image" content="http://localhost:8080/content-images/112?size=thumb">`, 대표 없는 글은 메타 0, 비노출 글 404 ③ 그 URL 익명 200 `image/png`·`Cache-Control: no-store`·`nosniff`(원본 207,493B 대비 썸네일 39,693B) ④ 왕복: 노출 끔 → 메인 썸네일 0·상세 404·이미지 URL 404, 켬 → 복귀, 예약(2099) → 다시 사라짐, 기간 비움 → 복귀 ⑤ 숫자 게시판 경로 404(기존 동작). 스크린샷 `home-cover-og-image-screenshots/home-thumb.png`(2026-11-09 정리에서 삭제 — 정렬 전 화면)·`docs/screenshots/public-home/new-posts-thumb-aligned.png`(2026-11-09 정리로 이동). 실기 데이터(게시판·글·이미지·썸네일 파일)는 모두 정리했다.

### 이슈
- 환경: Docker Desktop이 꺼져 있어 Testcontainers 시험이 `initializationError`로 실패 — 코드 문제가 아니라 데몬 미기동이었고 Docker Desktop을 기동해 해결.
- `processResources`가 devtools 재시작을 일으켜 관리자 세션이 사라졌다(실기 중 PATCH 401) — 재로그인으로 해결, 앱 문제 아님.
- 기존 문제(범위 밖): 모든 페이지의 `favicon.ico` 404.

### 한계·후속
- 실제 공유 크롤러(Facebook·Kakao·Slack)의 이미지 선택은 공개 도메인 확정 후 수동 확인. `APP_BASE_URL`이 실제 공개 주소여야 한다(`docs/deployment.md`).
- 썸네일 긴 변 ≤480px — 일부 SNS 권장 크기보다 작다(수용).
- 메인 썸네일은 배경 방식이라 지연 로딩이 없다 — 메인을 열면 ≤5건을 항상 요청한다.
- 로드맵 "후속 과제 — 게시판 정의 고도화" ① 완료 표기는 `/updateRoadmap` 몫.

### 코드 리뷰 루프·전체 시험 (2026-11-03)
- 1라운드 지적 1건(P2, `home.css`) **수용**: 좁은 화면에서 썸네일 행의 제목 공간이 사라진다. 실측(320px 화면 — 썸네일 행 제목 39px, 긴 게시판명이면 0px + 날짜가 행 밖으로 62px 넘침, 썸네일 없는 행은 123px)으로 확인했다. 수정: 썸네일 행만 `flex-wrap: wrap`·제목 `flex: 1 1 8rem`·게시판명 축소 허용 → 제목 170px, 넘침 없음, 넓은 화면(1000px)은 한 줄 유지(스크린샷 `home-thumb-narrow-320.png` — 2026-11-09 스크린샷 정리에서 삭제(현재 화면과 다름) — 320px 배치는 #198에서 다시 바뀜). 회귀 시험 `HomeThumbFallbackBrowserTest.narrowViewportKeepsTitleAndDateInsideRow` 추가, CSS를 되돌리는 변이 실험에서 이 시험만 실패함을 확인. 2라운드 지적 0건(approve).
- 전체 `./gradlew test`: **3,001건 통과, 실패 0, 건너뜀 12**(건너뜀과 이번 변경의 관련은 확인하지 않음 — 직전 작업 #193 기록도 12건).
- 범위 밖 기존 문제: 320px 화면에서 카드(`minmax(300px, 1fr)`)가 뷰포트보다 넓어 가로 스크롤이 생긴다(이번 변경 이전부터).
