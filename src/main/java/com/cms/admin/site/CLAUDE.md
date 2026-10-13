# CLAUDE.md — com.cms.admin.site

이 디렉터리(사이트 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 계획서 `adversarial-review/plan/PLAN-site-menu.md`(적대적 리뷰 5라운드 ship) — **PR ①(사이트 모델·관리·메뉴 사이트 분리 편집)과 PR ②(공개 라우팅 `/{siteKey}/…`·기본 사이트)까지 구현됐고 PR ③~⑤(사이트별 콘텐츠·공지·공개 메뉴 렌더링)는 미착수**다.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## Site

- **시드 2행(V51, 고정 ID)**: `id=1` 관리자 사이트(`admin`, `ADMIN`, 외부 링크 항상 불가)·`id=2` 기본 공개 사이트(`main`, `PUBLIC`, 외부 허용). `Site.ADMIN_SITE_ID`(=1)는 **사이드바가 쿼리 없이 쓰는 상수이자 `menu.site_id`의 DB `DEFAULT`**다 — 시험(`SiteMigrationTest`)이 고정한다. 시드는 삭제·비활성화 불가(409), 이름은 바꿀 수 있다. 사이트 종류(`site_type`)·키는 생성 후 바뀌지 않는다(`@Column(updatable=false)`, 수정 DTO에 필드 없음 — 요청에 와도 **무시**, 400 아님). 새로 만들 수 있는 것은 **공개 사이트뿐**(생성 DTO에 종류 없음).
- **사이트 키(`SiteKey`)**: `^[a-z0-9]+(-[a-z0-9]+)*$` 2~30자, 대문자 자동 변환 없음. 공개 경로 접두어(`/{siteKey}/…`)가 될 값이라 **예약어(`SiteKey.RESERVED`)를 쓸 수 없다**. **새 최상위 공개·정적 경로를 추가하면 예약어에도 넣어야 한다** — `SiteKeyReservedConventionTest`가 `src/main/java`의 컨트롤러 요청 매핑(클래스 수준 `@RequestMapping`이 있으면 그것만, 없으면 메서드 수준)과 `SecurityConfig`의 경로 리터럴 첫 세그먼트를 스캔해 CI에서 잡는다. 사이트 키 형식을 만족하지 않는 세그먼트(점 포함 `favicon.ico`·`swagger-ui.html`, `{…}`·`*`)는 키가 될 수 없어 검사에서 제외한다.
- **링크 정책(`SiteLinkPolicy`, 서비스 계층 판정)**: DTO `@SafeMenuUrl`은 **형식**(내부 경로 또는 외부 http(s))만 본다. 사이트 정책은 이 빈이 판정한다 — 관리자 사이트는 내부 경로만(외부는 같은 출처 절대 URL이어도 거부), 공개 사이트는 내부 경로 + `allow_external_link`이면 외부. **공개 사이트의 내부 경로**는 비공개 접두어(`admin`·`actuator`·`error`·`v3`·`swagger-ui*` — `/swagger-ui.html` 포함)를 가질 수 없다(`?#` 앞에서 자르고 한 번 퍼센트 디코딩·소문자화, `.`/`..` 세그먼트·잔여 `%` 거부). `app.base-url`과 출처(스킴·호스트·포트)가 같은 절대 URL은 출처를 떼어낸 경로로 보고 같은 규칙을 적용한다 — **별칭 호스트는 탐지하지 않는 수용한 한계**다(이 규칙은 인가가 아니라 심층 방어, 편집자는 ADMIN뿐이고 `/admin/**`은 게이트가 막는다). 오류 문구에 입력 원문을 싣지 않는다(400 `INVALID_REQUEST`).
- **저장된 URL 불변식**: 링크 정책 강화(외부 허용 → 불가)는 외부 URL 메뉴가 있으면 409다. 메뉴 URL을 쓰는 모든 쓰기가 같은 사이트 행 잠금을 먼저 지나므로 검사와 쓰기가 경합하지 않는다 → **저장된 메뉴 URL은 항상 현재 정책을 만족한다**(`SiteMenuConcurrencyIntegrationTest`의 정책 강화 경합 시험이 고정, 변이로 잠금을 빼면 실패함을 확인).
- **삭제 가드("내용")는 지금 메뉴 존재뿐**이다(`SiteService.hasContent`). **PR ③·④가 `site_id`를 가진 새 테이블(배너·팝업·FAQ 카테고리·게시판 등)을 추가할 때는 각 FK도 `ON DELETE RESTRICT`로 두고 `hasContent`에 포함시킨다.** 삭제는 하드 삭제이고 감사에 `targetLabel`=사이트 키 스냅샷(`SiteDeleteResult`)을 남긴다.
- **권한**: 사이트 API(`/admin/api/sites`)는 전부 `@PreAuthorize("hasRole('ADMIN')")`, URL 게이트는 `/admin/**` ADMIN 캐치올 — **`AdminFeature`·권한관리·`SecurityConfig` 변경 없음**(MANAGER 403). 화면은 정적 페이지·메뉴 관리 셸(`admin/page/manage.html`)의 **ADMIN 전용 "사이트" 탭**(`admin/site/fragments/site-tab.html`, 조각 `sitePanel`·`siteScript`, 모달 없음·삭제 확인은 `confirm`)이다 — 탭·스크립트 문자열은 MANAGER 응답에 없다(`ContentManagePageIntegrationTest`).
- **감사**: `SITE_CREATE`/`SITE_UPDATE`/`SITE_DELETE`(`targetType=SITE`). 사이트 변경은 `AdminActionTypes.ALL`·활동 로그 화면 라벨과 함께 갱신한다. 메뉴 감사(`MENU_*`)는 행위와 메뉴 ID만 기록하고 URL 이력은 남기지 않는다(수용한 한계).

## 잠금 규약 (메뉴·사이트 쓰기 공통, PLAN-site-menu.md D4)

- **사이트 행 `FOR UPDATE` 하나가 그 사이트의 쓰기 문(gate)** 이다 — 메뉴 생성·수정·재활성화·비활성화·영구삭제·구조 반영과 사이트 수정·삭제가 모두 이 행을 **먼저** 잠근다(빈 사이트도 직렬화). 그 뒤 메뉴 행(사이트 범위 `findAllBySiteIdForUpdate` 또는 대상 행)을 menuNo 오름차순으로 잠근다. **다른 사이트의 행은 어떤 경로에서도 잠그지 않는다** — 부모는 이미 잠근 그 사이트의 집합에서만 찾고(없는 번호와 다른 사이트 소속은 같은 404, 존재 비노출) 별도 부모 잠금 쿼리를 쓰지 않는다. 메뉴 행 전체 잠금은 사이트 문과 중복이지만 사이트 문을 모르는 쓰기(롤백 구간의 이전 앱)에 대한 방어와 검증된 기존 동작 보존을 위해 유지한다.
- **쓰기 서비스 메서드는 컨트롤러가 호출하는 최상위 트랜잭션 진입점**이며 `@Transactional(isolation = READ_COMMITTED)`다(선례 `BannerService`·`MediaService`·`PopupService`). REPEATABLE READ의 **갭·범위 잠금과 읽기 뷰 고정**이 처음 설계의 교착(같은 사이트 부모·자식, 서로 다른 빈 사이트의 갭 락 순환)과 낡은 존재 검사의 뿌리였다 — RC에서는 잠금 읽기가 일치 행만 잠그고 비잠금 읽기도 문장마다 최신 커밋을 본다. `REQUIRED` 전파라 이미 열린 트랜잭션에 참여하면 격리 선언이 무시되고 감사 커밋 순서 계약도 깨진다 — **다른 서비스가 안쪽에서 이 메서드를 부르지 않는다**(시험이 외부 트랜잭션을 만들 때도 RC로 연다).
- **메뉴 번호만 아는 연산(수정·삭제)**: 스칼라 투영 `findSiteIdByMenuNo`(엔티티를 영속성 컨텍스트에 올리지 않는다) → 사이트 행 `FOR UPDATE` → 메뉴 행 `FOR UPDATE`로 다시 읽고 `site_id` 일치 확인(다르면 409, 정상 경로에서는 불가능한 방어).
- 시험: `SiteMenuConcurrencyIntegrationTest`(부모 삭제 ↔ 자식 재활성화, 같은/다른 빈 사이트 동시 첫 생성, 양방향 교차 사이트 부모, 정책 강화 ↔ 외부 URL 쓰기, 사이트 문이 다른 사이트를 막지 않음, 쓰기 진입점이 실제로 RC), `MenuConcurrencyIntegrationTest`의 `verifyOrderedWrites`는 **대기 관측 프로브를 사이트 잠금 쿼리**에 둔다(후행 호출은 메뉴 쿼리에 닿기 전에 사이트 문에서 먼저 대기한다 — 프로브를 메뉴 쿼리에 두면 시험이 타임아웃한다).

## 공개 라우팅과 기본 사이트 (PR ②, 2026-10-13)

- **사이트 키 = 공개 경로 접두어**: 활성 `PUBLIC` 사이트의 `site_key`가 `/{siteKey}/…`로 공개된다. **새 사이트를 만들면(또는 비활성 사이트를 활성화하면) 즉시 무인증 공개 경로가 하나 늘어난다** — `PublicSiteInterceptor`가 요청마다 DB로 판정하므로(캐시 없음) 비활성화는 즉시 404다. 지금(PR ③ 전)은 모든 공개 사이트가 같은 콘텐츠를 보여 준다. 상세는 `com.cms.publicweb.support`의 `CLAUDE.md`, 인가 정책 표는 `com.cms.config`의 `CLAUDE.md`.
- **기본 공개 사이트(`site.default_yn`, V53·V54)**: 접두어 없는 `/`가 302로 이동하는 곳. **정확히 1개의 활성 `PUBLIC` 사이트**여야 하고(아니면 `/`는 오류 로그 + 404 fail-closed) DB 제약은 없다 — 서비스 잠금과 시험이 지킨다. 시드는 `main`(id=2). **비활성화·삭제할 수 없다**(시드 보호 `isSeed()`에 더해 `defaultYn` 검사 — 시드 `main`은 기본이 아니게 된 뒤에도 시드 보호가 계속 막는다).
- **기본 사이트 변경**: `PATCH /admin/api/sites/{id}`에 `defaultSite: true`(활성 `PUBLIC` 대상만, `false`는 400 — 기본은 다른 사이트를 지정해야만 바뀐다, 이미 기본이면 멱등). **잠금 규약의 명시적 예외**: 옛 기본·새 기본 두 행을 함께 바꾸므로 **처음부터 모든 `site` 행을 id 오름차순으로 `FOR UPDATE`**(`findAllForUpdate`)한다 — 대상 행을 먼저 잠근 뒤 확장하면 잠금 순서가 깨져 교착할 수 있으므로 `updateSite()`는 `defaultSite=true`이면 단일 행 잠금을 건너뛴다. 다른 쓰기(메뉴 쓰기·사이트 수정/삭제)는 사이트 행 하나를 잡은 뒤 메뉴 행만 기다리고 기본 변경은 메뉴 행을 쥐지 않으므로 순환하지 않는다(`SiteDefaultConcurrencyIntegrationTest` — 동시 기본 변경·비활성화와의 경합·메뉴 쓰기와의 경합, 변이로 잠금을 빼면 실패함을 확인). 복합 요청 `{defaultSite:true, useYn:false}`는 **최종 상태 기준** 400. 감사는 기존 `SITE_UPDATE`(대상=사이트 ID) 재사용. UI는 사이트 탭의 "기본" 열과 [기본으로 지정] 버튼(활성 공개 비기본 사이트에서만, 미저장 입력이 없을 때만 — 요청은 폼이 아니라 `defaultSite`만 보낸다, `SiteDefaultTabBrowserTest`).
- **사이트 키 예약어**: 공개 경로 둘째 세그먼트 이름(`notices`·`boards`·`pages`·`faq`·`inquiry`)은 옛 최상위 경로를 404로 종료하고 키 충돌을 막기 위해 **계속 예약어**다(`SiteKeyReservedConventionTest.legacyTopLevelNamesStayReserved`·`siteScopedSecondSegmentsAreReserved`).
- 롤백: 공개 사이트 메뉴가 있으면 V52 이전 앱으로 롤백하지 않는다. V53은 가산 컬럼이지만 이전 앱은 `/{key}/…`를 모른다 — **구·신 앱 혼합 운영 금지**(`docs/deployment.md`·`docs/migration-guide.md`).
