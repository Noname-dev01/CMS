# CLAUDE.md — com.cms.publicweb.page

이 디렉터리(공개 정적 페이지 `/pages/**`) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 설계 결정·적대적 리뷰 5라운드 기록은 `adversarial-review/plan/PLAN-static-page.md`(2026-10-11). 관리 측은 `com.cms.admin.page`의 `CLAUDE.md`. 공개 게시판(`com.cms.publicweb.board`)과 같은 격리·404 흡수 원칙이다.

(필드 목록은 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

- **`/pages/**`는 GET·HEAD만 무인증 공개**(2026-10-11 승인, `SecurityConfig`): `/pages`·`/pages/**`의 그 외 메서드는 `denyAll`이다. 라우트는 `/pages/{slug}`(현재 버전), `/pages/{slug}/revisions?page=N`(개정 목록), `/pages/{slug}/revisions/{no}`(특정 개정) **셋뿐**이고 라우트를 추가하면 곧바로 무인증 공개가 되는 구조다(`/boards/**`와 같다) — 접근 통제는 전량 `PublicSitePageService`의 공개 조건 재검증이다. `/pages` 자체와 그 밖의 하위 경로는 핸들러가 없어 404다. `@AdminPage`를 붙이지 않는다. 레이트리밋 `public-page`(`/pages/**` 120/60초).
- **공개 불변식은 쿼리에 고정**: 페이지 `useYn=true`가 `SitePageRepository.findPublic*` JPQL에 들어 있다. **비노출이면 현재 버전·개정 목록·이전 개정이 모두 empty**이고 컨트롤러가 같은 404로 흡수한다 — 없는 슬러그·형식 위반 슬러그·없는 개정·범위 밖 개정 번호도 응답 본문이 같다(`PublicSitePageIntegrationTest`가 본문 비교로 고정). 페이지와 현재 개정은 한 쿼리(조인)로 읽는다.
- **경로 값은 String으로 받아 직접 검증한다**(바인딩 실패가 컨트롤러 진입 전에 전역 JSON advice로 새는 것 방지, 컨트롤러는 예외를 던지지 않는다 — 던지면 404가 아니라 HTML 500). 슬러그는 `SiteSlug.isValid`(형식·길이)를 DB 조회 전에 본다. 개정 번호는 `^[1-9][0-9]{0,9}$`를 통과한 뒤 `Integer.parseInt`(범위 초과는 404, `int` 최댓값 개정까지 열람 가능 — R4-2).
- **개정 목록은 OFFSET이 아니라 번호 구간 조회**(R3-2·R4-1): 쪽 `p`(0부터)는 `U = 현재 개정 번호 − 20p`일 때 `max(1, U−19) ≤ 번호 ≤ U`(포함 구간, 쪽당 20건), `hasNext = U > 20`, 음수·`U < 1`(마지막 쪽 초과)이면 첫 쪽으로 보정한다(`long` 산술). 비용이 쪽 번호와 무관하고 전 이력에 도달할 수 있다(게시판의 `MAX_PAGE=1000` 규칙을 복사하면 20,020건 너머의 개정이 목록에서 사라진다). 목록 쿼리는 본문을 읽지 않는다. 시험: 개정 20·21·40·41건 경계, `int` 최댓값.
- **본문 출력 시 재정리**: `ContentBodyPolicy.sanitizeForPageOutput`(이미지 불허)을 거친 HTML만 `detail.html`의 `th:utext="${page.contentHtml}"` **한 곳**에서 이스케이프 없이 출력한다(`PublicPageTemplateConventionTest`). 제목·개정 제목·슬러그는 `th:text`/경로 변수다. 현재 개정 번호로 `revisions/{no}`를 열어도 그 개정을 그대로 보여 준다(리다이렉트 없음, `current=true`라 이전 버전 안내가 없다).
- **공개 메인 푸터**: `footer_yn ∧ use_yn` 페이지를 만든 순서(`id` 오름차순)로 현재 개정 제목과 함께 링크한다(정렬 지정 기능 없음). 푸터는 **선택 섹션**이라 `HomeController`가 `getHome()`과 **별도 호출**(`findFooterLinks()` — 자체 읽기 트랜잭션)로 읽고 예외를 컨트롤러에서 잡아 빈 목록으로 대체한다 — `getHome()`은 읽기 트랜잭션 전체라 그 안에서 예외만 잡으면 rollback-only로 반환 때 메인까지 깨질 수 있다(R1-3). 시험: `PublicHomeFooterIsolationIntegrationTest`(서비스에 DB 예외 주입 → `/`가 200).
- 공개 레이아웃 공통 푸터는 없다(템플릿이 페이지마다 독립이다) — 푸터는 공개 메인에만 있다. 공통 레이아웃 도입은 별도 작업.
- 시험: `PublicSitePageIntegrationTest`(현재 버전·이전 개정·비노출 동일 404·형식 위반·개정 경계·쪽 번호 보정·출력 정리·푸터·레이트리밋)·`PublicHomeFooterIsolationIntegrationTest`·`PublicPageTemplateConventionTest`·`SecurityConfigTest`의 `/pages/**` 계약.
