# CLAUDE.md — com.cms.publicweb.notice

이 디렉터리(공개 공지 진입점) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 공지는 **공지 게시판의 게시글**이고(관리 측 `com.cms.admin.board`의 `CLAUDE.md` "공지 게시판 흡수"), 공개 화면은 **게시판 한 벌**이다(`com.cms.publicweb.board`). 설계·리뷰 기록은 `adversarial-review/plan/PLAN-board-definition.md` "PR 4b 상세 설계"(P4b-v2, 2026-10-28).

## `/{siteKey}/notices` 흡수 (2026-10-28, PR 4b — 공개 URL 계약 변경 승인)

- **남은 것은 목록 진입점 하나**: `PublicNoticeRedirectController`(`@SiteScopedPublic`, `GET·HEAD /{siteKey}/notices`)가 `PublicBoardService.findNoticeBoardSlug(siteId)`로 그 사이트의 공지 게시판(전용 `NOTICE:{siteId}` → 없으면 공통 `NOTICE`, 공개 조건은 `findPublicById`)을 찾아 **302** `Location: /{현재 사이트 키}/boards/{현재 슬러그}` + 원래 쿼리스트링 **원문**(옛 검색·페이지 링크 유지 — 경로는 서버가 만들고 쿼리만 붙어 오픈 리다이렉트 아님, `%0D%0A`는 디코딩되지 않는다). 공지 게시판이 없거나 공개 조건을 통과하지 못하면 302 없이 404(`error/404` 뷰).
- **302인 이유**: 대상이 바뀔 수 있다(전용 공지 게시판 생성·삭제, 공지 게시판 슬러그 1회 변경) — 301은 브라우저가 캐시한다.
- **상세·첨부 하위 경로(`/{siteKey}/notices/{id}`·`…/attachments/{id}`)는 핸들러가 없어 404**다(접두어 없는 옛 `/notices/1`과 같은 컨테이너 404 화면 — `PublicSiteRoutingServerTest`). `SecurityConfig`의 `NOTICES + "/**"` GET·HEAD `permitAll`은 **일부러 남겼다** — 지우면 하위 경로가 404가 아니라 로그인 302·403이 된다. 하위 경로에 핸들러를 다시 만들지 않는다(공지 상세는 `/{siteKey}/boards/{슬러그}/posts/{id}`).
- **함정 — `Optional.map`에 `null`을 돌려주는 람다를 쓰지 않는다**: 302를 응답에 직접 쓰고 `null`을 돌려주는 핸들러에서 `findNoticeBoardSlug(...).map(slug -> { …; return null; }).orElseGet(404)`로 쓰면 `map`이 빈 값이 되어 **Location을 쓴 뒤 상태가 404로 덮인다**(구현 중 실제로 냈다 — Location만 단언한 시험은 통과했다). 분기로 쓰고 시험은 상태 코드를 함께 단언한다.
- **레이트리밋**: `public-notice`(`/*/notices/**` 120/60초)가 302와 하위 404를 모두 센다(302도 DB를 친다). 옛 `attachment` 규칙(`/*/notices/*/attachments/*`)은 대상 라우트가 사라져 지웠다 — 공지 글의 첨부는 `board-attachment`(`/*/boards/*/posts/*/attachments/*`)가 받는다.
- **저장된 링크**: 숫자 공지 경로(`/{k}/notices/{숫자}(/…)`)는 V11이 공지 게시판의 게시글 주소로 치환했고(그 사이트의 해석 공지 게시판 소속 글만), 이후 저장은 거부한다(배너·팝업·메뉴 400, 본문은 `href`만 제거 — `BoardLinkPaths.isNumericNoticePath`). 목록 링크 `/{k}/notices`는 허용. 상세는 `com.cms.admin.board`의 `CLAUDE.md`·`docs/deployment.md`.
- 지운 것: `PublicNoticeController`(스트리밍·집계 복제)·`PublicNoticeService`(어댑터)·`templates/public/notice/{list,detail}.html`·`static/css/public/notice.css`. 공용 500 뷰는 `templates/public/error.html`로 옮겼다(`PublicWebExceptionAdvice`). `SiteKey.RESERVED`의 `notices`는 경로가 남아 예약어 그대로다.

## 시험

`PublicNoticeRedirectIntegrationTest`(302 GET·HEAD·쿼리 원문·슬러그 변경·공지 게시판 없음/비공개 404·하위 경로 404), `PublicBoardSiteScopeIntegrationTest`(사이트별 해석·전용 삭제 후 복귀), `SitePublicRateLimitConventionTest`(302가 한도에 세어짐 — 시험은 기본으로 레이트리밋이 꺼져 있어 켠 이 클래스에 둔다), `PublicSiteRoutingServerTest`(하위 경로 404 화면), `NoticeLinkUpgradeTest`(V11). 옛 공지 시험 중 실제 DB의 TOCTOU·IDOR·파일 없음은 `publicweb.board.service.PublicBoardAttachmentIntegrationTest`로 옮겼다.
