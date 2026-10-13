# PLAN-site-menu — 사이트 설정 메뉴 (다중 사이트·사이트별 메뉴·공개 라우팅·콘텐츠 분리)

- 작성: 2026-10-13 / 상태: **계획 v5 — 적대적 리뷰 5라운드 ship, 사용자 승인(2026-10-13), PR ① 구현·검증 완료(PR ②~⑤ 미착수)**
- 로드맵: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — 사이트 설정 메뉴"
- 확정 결정(재질문 금지): 사용자 메모리 `project-site-menu-decisions` — 아래 "확정 사항"에 그대로 옮긴다.
- 이 문서의 **상세 설계 범위는 PR ①(사이트 모델 · 사이트 관리 UI · 메뉴 사이트 분리 편집)** 이다. PR ②~⑤는 "후속 PR 윤곽"에서 PR ①이 남겨야 할 전제만 다룬다(각 PR 착수 때 이 문서를 갱신해 별도 리뷰).

## 1. Context

지금 `menu` 테이블은 관리자 사이드바 전용이고(`MenuService`가 전 행을 하나의 트리로 다룬다), 공개 `/`의 링크는 템플릿에 고정돼 있다. 공개 사이트를 여러 개 두고 사이트별로 메뉴·콘텐츠를 분리하려면 먼저 **"사이트"라는 소속 개념**과 **사이트별 메뉴 편집**이 필요하다. PR ①은 공개 동작(라우팅·렌더링)을 바꾸지 않는다 — 관리 화면과 데이터 모델만 만든다. 그래서 **인가 정책(`SecurityConfig`)·레이트리밋 변경은 PR ①에 없다.**

### 확정 사항 (사용자 결정, 2026-10-13)

| 항목 | 결정 |
|---|---|
| 데이터 모델 | `site` 테이블 + `menu.site_id` FK. 사이트별 링크 정책 컬럼(외부 링크 허용 여부), **서비스 계층에서 판정**(DTO는 형식 검사만). `site_key` 고유·형식·예약어 제약, 생성 후 불변 |
| 링크 규칙 | 관리자 사이트 메뉴 = 내부 경로만, 공개 사이트 메뉴 = 내부 + 외부 http(s), 공개 메뉴 내부 경로에서 `/admin`·`/actuator` 등 비공개 접두어는 서버가 거부. 배너·팝업 링크는 변경 없음 |
| 권한 | 사이트 관리·모든 사이트 메뉴 편집은 **ADMIN 전용**. `DELEGABLE` 기능·권한 카탈로그·권한관리 화면 변경 없음 |
| 사이트 관리 UI | ADMIN이 공개 사이트 생성·수정·비활성화. 시드 2행(관리자·공개 기본)은 삭제·키 변경 불가, 내용 있는 사이트는 삭제 금지·비활성화만 |
| 라우팅·콘텐츠·공지·기존 URL | `/{siteKey}/…`, `/`는 기본 사이트 302, 접두어 없는 기존 URL 404, 시작 화면·공지·FAQ·푸터 사이트별 분리, `site_id` NULL = 공통, 공지는 `board.site_id` (**PR ②~⑤**) |

## 2. PR 분할과 PR ① 범위

| PR | 내용 | 인가 정책 변경 | 스키마 |
|---|---|---|---|
| **① (이 문서의 상세 대상)** | 사이트 모델, 사이트 관리 UI/API, 메뉴 사이트 분리 편집(링크 정책 판정) | **없음** (`/admin/**` ADMIN 캐치올 그대로) | `site` 신설, `menu.site_id` |
| ② | 라우팅 `/{siteKey}/…`, 공개 경로 이전, `/` 302, 기본 사이트 개념 | **있음**(동적 무인증 경로·레이트리밋 패턴) — 별도 고지·승인 | 기본 사이트 표시 컬럼 가능성 |
| ③ | 사이트별 콘텐츠(배너·팝업·FAQ 카테고리·푸터) | 없음 | `site_id` 여러 곳 |
| ④ | 사이트별 공지(`board.site_id`) | 없음 | `board.site_id` |
| ⑤ | 공개 메뉴 렌더링 | 없음 | 없음 |

PR ①이 끝나도 공개 화면은 이전과 100% 같다(공개 사이트 메뉴는 편집만 가능하고 아직 어디에도 렌더링되지 않는다).

## 3. 정찰에서 발견한 핵심 쟁점

코드 근거는 모두 읽어서 확인한 것이다.

1. **`MenuService`의 모든 불변식이 "전 테이블 = 트리 하나"를 전제한다.** `createMenu`/`applyStructure`는 `findAllForUpdate()`로 **전체 행**을 잠그고, `applyStructure`의 낙관적 검사는 "요청 집합 == 전체 도달 가능 집합"이다(`MenuService.java:65,201,210`). 사이트가 둘 이상이면 이 범위를 사이트로 좁혀야 한다.
2. **사이드바·통합 검색은 활성 메뉴 전부를 읽는다**(`getSidebarMenus` → `findAllByUseYnTrue…`, `AdminSidebarAdvice`, `AdminSearchService`). 공개 사이트 메뉴가 관리자 사이드바에 섞이면 안 된다 — 사이드바는 관리자 사이트만 읽어야 한다.
3. **메뉴 URL 규칙이 한 벌(내부 경로만)로 DTO 어노테이션에 박혀 있다**(`@SafeMenuUrl` → `SafeUrls.isSafeMenuUrl`, PR #141). 공개 사이트는 외부 http(s)도 허용해야 하므로 "DTO = 형식, 서비스 = 사이트 정책" 2단계로 나눠야 한다. 화면 JS에도 같은 정규식이 복제돼 있다(`menu-tab.html` `SAME_ORIGIN_PATH`).
4. **노출 안내(`exposure`)와 `MenuVisibility`는 "관리자 사이드바 + MANAGER 권한" 의미**라 공개 사이트 메뉴에는 의미가 없다.
5. **롤백 호환**: 이전 앱은 `menu`에 INSERT할 때 `site_id`를 모른다. `NOT NULL` 컬럼이면 롤백 후 메뉴 생성이 깨진다.
6. **사이트 삭제/정책 변경과 메뉴 쓰기의 경합**: 사이트를 지우는 순간 메뉴가 생기거나, 외부 링크 허용을 끄는 순간 외부 링크 메뉴가 생기는 경합을 막아야 한다(게시판의 "생성 `FOR SHARE` / 삭제 `FOR UPDATE`" 선례가 있다).
7. **테스트 파급**: `MenuServiceTest`(Mockito, `MenuRepository` 호출 103곳)·`MenuConcurrencyIntegrationTest`(47곳)·`MenuServiceStructureTest`·`MenuControllerTest`·`SafeMenuUrlValidationTest`·`AdminSidebarAdvice*Test`·`MenuUrlSidebarRenderingIntegrationTest`가 시그니처/쿼리 변경에 직접 영향을 받는다. `Menu.builder()` 사용 테스트 13곳.
8. **화면**: 메뉴 구조 편집은 정적 페이지 관리 셸의 ADMIN 전용 탭이다(`PLAN-content-menu-unify.md`). 사이트 선택과 사이트 관리 UI를 어디에 둘지 결정해야 한다.
9. **사이트 키 예약어의 원천**: 지금 공개 최상위 경로는 `/`, `/banners`, `/popups`, `/notices`, `/boards`, `/pages`, `/faq`, `/inquiry`, `/content-images` + 정적 `/css /js /img /vendor /favicon.ico` + `/admin /actuator /swagger-ui /v3 /error`. 새 공개 경로가 생길 때 예약어 갱신을 잊으면 사이트 키가 그 경로를 가린다 → **자동 검사**가 필요하다.

## 4. 설계 결정 (PR ①)

각 결정: 선택지 → 선택 → **왜**.

### D1. 스키마 — `site` 테이블과 `menu.site_id`

- **`site`**: `id` BIGINT PK AUTO_INCREMENT, `site_key` VARCHAR(30) NOT NULL **UNIQUE**, `site_name` VARCHAR(100) NOT NULL, `site_type` VARCHAR(10) NOT NULL (`ADMIN`|`PUBLIC`), `allow_external_link` BIT NOT NULL, `use_yn` BIT NOT NULL, `create_date`/`update_date` DATETIME(6).
  - 시드 2행을 **고정 ID로** 넣는다: `id=1 'admin' 관리자 ADMIN allow_external_link=0`, `id=2 'main' 기본 사이트 PUBLIC allow_external_link=1`.
  - 왜 `site_type`: 사이드바가 읽을 "관리자 사이트"를 식별하고 시드 보호·링크 정책 고정(관리자는 항상 내부만)을 코드에서 한 곳으로 표현하기 위해. 컬럼 없이 `id=1`만 쓰면 의미가 코드에 숨는다. (`site_key`는 사용자 입력 대상이라 식별자로 쓰지 않는다.)
- **`menu.site_id`**: `BIGINT NOT NULL DEFAULT 1`, FK → `site(id)` **ON DELETE RESTRICT**.
  - 선택지: (a) NULLABLE (NULL=공통) (b) NOT NULL (c) **NOT NULL DEFAULT 1**. → (c).
  - 왜: 메뉴는 "공통" 개념이 없다(콘텐츠 분리의 NULL=공통은 PR ③ 이후 대상의 규칙). `DEFAULT 1`(관리자 사이트)이면 **롤백한 이전 앱이 `site_id` 없이 INSERT해도 관리자 메뉴로 정확히 들어간다**(쟁점 5). 이전 앱은 공개 사이트 메뉴를 모르므로 그 행들은 이전 앱의 트리·사이드바에 섞여 보인다 — 롤백 주의로 문서화한다(아래 R2).
  - 기존 행 백필은 컬럼 추가 시 DEFAULT가 그대로 채운다(별도 UPDATE 불필요).
- **마이그레이션**(프로젝트 규칙: DDL과 DML을 섞지 않는다, V13·V16 선례). 현재 최신은 V49:
  - `V50__create_site.sql` — DDL: `site` 생성
  - `V51__seed_site.sql` — DML: 시드 2행(고정 ID, `WHERE NOT EXISTS`로 재실행 안전)
  - `V52__add_menu_site_id.sql` — DDL: `menu.site_id` 추가 + FK + 인덱스
  - (V53 이상은 PR ③~④가 이어 쓴다)
- **SQL 초안**(기존 `V37`·`V34` 규칙: `utf8mb4_general_ci`, 시드는 `WHERE NOT EXISTS`, 시각은 NULL로 두어 `NOW()`를 쓰지 않는다):
  ```sql
  -- V50 (DDL)
  CREATE TABLE `site` (
    `id` bigint(20) NOT NULL AUTO_INCREMENT,
    `site_key` varchar(30) NOT NULL,
    `site_name` varchar(100) NOT NULL,
    `site_type` varchar(10) NOT NULL,
    `allow_external_link` bit(1) NOT NULL,
    `use_yn` bit(1) NOT NULL,
    `create_date` datetime(6) DEFAULT NULL,
    `update_date` datetime(6) DEFAULT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_site_site_key` (`site_key`)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

  -- V51 (DML) — 고정 ID 시드. 이후 AUTO_INCREMENT는 3부터
  INSERT INTO site (id, site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date)
  SELECT 1, 'admin', '관리자', 'ADMIN', 0, 1, NULL, NULL FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM site WHERE id = 1);
  INSERT INTO site (id, site_key, site_name, site_type, allow_external_link, use_yn, create_date, update_date)
  SELECT 2, 'main', '기본 사이트', 'PUBLIC', 1, 1, NULL, NULL FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM site WHERE id = 2);

  -- V52 (DDL) — 기존 행은 DEFAULT 1(관리자 사이트)로 채워진다. FK가 `site_id` 인덱스를 만든다
  ALTER TABLE `menu`
    ADD COLUMN IF NOT EXISTS `site_id` bigint(20) NOT NULL DEFAULT 1,
    ADD CONSTRAINT `fk_menu_site` FOREIGN KEY (`site_id`) REFERENCES `site` (`id`) ON DELETE RESTRICT;
  ```
  `ddl-auto: validate`: `Menu.siteId`(Long) ↔ `bigint`, `Site` 엔티티 컬럼 타입은 기존 `bit(1)`↔`Boolean` 규약과 같다 — 마이그레이션 시험이 Hibernate 검증 통과까지 확인한다. V52가 중간에 실패하면 DDL은 암묵 커밋이라 부분 적용 가능성이 있다 — 복구는 `docs/migration-guide.md`의 `flyway repair` 절차(V16 선례)를 따른다.
- 선택지 중 시드 행을 마이그레이션이 아니라 앱 부트스트랩이 만드는 방식은 기각 — 메뉴 시드(V3 등)와 같은 Flyway 방식이고, 앱 기동 순서·다중 인스턴스 경합 문제가 없다.

### D2. 사이트 키 규칙 — 형식·예약어·불변

- 형식: `^[a-z0-9]+(-[a-z0-9]+)*$`, 2~30자(`SiteSlug`와 같은 문법, 길이만 짧게). 소문자 정규화는 하지 않고 거부한다.
  - 왜 `SiteSlug`를 재사용하지 않고 새 클래스(`SiteKey`): `SiteSlug`는 정적 **페이지** 슬러그 규칙(이름이 겹쳐 헷갈릴 뿐 별개 개념)이고 길이·예약어가 다르다. 문법 정규식 상수만 같은 모양으로 둔다(공유 추상화는 만들지 않는다 — 과설계).
- **예약어**(대소문자 무관): `admin actuator error swagger-ui v3 api notices boards pages faq inquiry content-images banners popups css js img vendor images static assets favicon login logout`. `favicon.ico` 같은 점 포함 값은 형식에서 이미 탈락한다.
- **자동 검사(쟁점 9)**: 컨벤션 시험이 ① `SecurityConfig.STATIC_PUBLIC_PATHS`의 각 첫 세그먼트 ② `publicweb`의 클래스 레벨 `@RequestMapping` 첫 세그먼트 ③ `SecurityConfig` 소스의 `requestMatchers(...)` 문자열 리터럴의 첫 세그먼트가 모두 예약어 집합에 포함됨을 단언한다. **키 형식을 만족하지 않는 세그먼트(점 포함 `favicon.ico`·`swagger-ui.html`, `{…}`·`*` 변수·와일드카드, 빈 값)는 사이트 키가 될 수 없으므로 검사에서 제외한다.** 새 공개 경로를 추가하면서 예약어를 안 고치면 CI가 실패한다. (SecurityConfig 소스 스캔은 `ClockUsageConventionTest`처럼 `src/main/java` 텍스트 스캔 선례를 따른다.)
- 불변: 수정 DTO에 `siteKey` 필드를 두지 않는다. 낡은 클라이언트가 보내도 Jackson이 무시(400 아님)하는 기존 관례와 같다 — 알 수 없는 키 거부는 `PostPublishPeriodRequest`의 특별 계약이고 일반 `BoardUpdateRequest`는 무시하므로(라운드 4 확인) 사이트 수정 DTO도 **무시**(400 아님)로 둔다.

### D3. 링크 정책 — DTO는 형식, 서비스가 사이트 정책

- `@SafeMenuUrl`을 **"링크 형식"** 검사(내부 경로 또는 외부 http(s) 형태 = `SafeUrls.isSafeLinkUrl`)로 일반화하고, 서비스에서 `SiteLinkPolicy.validate(site, url)`로 정책을 판정한다.
  - 규칙: `ADMIN` 사이트 → 내부 경로만(`allow_external_link` 무시, 항상 false). `PUBLIC` 사이트 → 내부 경로, `allow_external_link=1`이면 외부 http(s)도 허용. **공개 사이트의 내부 경로**는 추가로 비공개 접두어 금지 — 경로를 `?`·`#` 앞에서 자르고 한 번 퍼센트 디코딩(실패 시 거부)·소문자화한 뒤, **첫 세그먼트가 `admin`·`actuator`·`error`·`v3`이거나 `swagger-ui`로 시작(`swagger-ui.html` 포함)** 이면 거부한다. `.`/`..` 세그먼트와 디코딩 뒤에도 `%`가 남는 경로도 거부(브라우저 정규화 우회 차단). **같은 출처 절대 URL(라운드 3 지적 2)**: 외부 http(s) 허용 사이트에서도 URL의 출처가 CMS 자신(`app.base-url`, 기존 설정 — `PasswordResetService`가 쓰는 값)과 같으면 출처를 떼어낸 경로로 보고 위 내부 경로 규칙(비공개 접두어 포함)을 적용한다(`https://cms.example/admin/login` 거부). 그래서 `SiteLinkPolicy`는 `app.base-url`을 주입받는 빈이다. 별칭 호스트(CDN·다른 도메인)는 알 수 없으므로 **수용한 한계**로 문서화한다 — 이 규칙은 인가 장치가 아니라 "공개 메뉴에 관리 경로가 실수로 들어가지 않게" 하는 심층 방어이고 편집자는 ADMIN뿐이다(관리 경로는 어차피 `/admin/**` ADMIN 게이트가 막는다). 시험: 같은 출처/다른 출처/포트·대소문자 차이.
시험: `/swagger-ui.html`·`/swagger-ui/index.html`·`/admin?x=1`·`/ADMIN/x`·`/%61dmin`·`/a/../admin`.
  - 위반은 400(`InvalidRequestException`, 고정 문구 — 입력값 원문을 반영하지 않는다).
- 선택지: (a) DTO가 사이트 모른 채 느슨하게, 서비스가 판정 (b) 사이트별 커스텀 Validator(사이트를 DTO가 알아야 함, 불가). → (a). 이미 결정된 방침이다.
- **출력 시 재검증**은 기존 관리자 사이드바(`SidebarMenuResponse.of`)가 유지한다. 공개 렌더링의 재검증은 PR ⑤.
- 화면 JS 정규식: 선택한 사이트의 `allowExternalLink`·`siteType`에 따라 내부/외부를 판정(서버가 최종).
- 감사: 기존 `MENU_CREATE`/`MENU_UPDATE`는 **행위와 메뉴 ID만 기록**한다(`targetIdExpression="menuNo"`뿐 — URL·변경 전후 값은 남기지 않는다. 어떤 외부 URL을 추가했는지의 이력은 남지 않는 것을 **수용한 한계**로 두고 D9처럼 라벨 확장은 하지 않는다). 사이트 변경은 신규 `SITE_CREATE/UPDATE/DELETE`.

### D4. 잠금 규약 — **사이트 행 하나가 문(gate)** + `READ_COMMITTED` (v3 개정)

v2의 "생성=`FOR UPDATE` / 단일 행=`FOR SHARE` + 잠금 읽기 존재 검사"는 라운드 2에서 세 가지 결함이 지적됐다: ① 같은 사이트 부모·자식 교착(삭제가 부모를 잠그고 자식 잠금 읽기를 기다리는 동안 재활성화가 자식을 잠그고 부모를 기다림, `FOR SHARE`끼리는 서로 통과) ② 존재 검사에 `site_id` 조건이 없어 REPEATABLE READ의 범위 잠금이 다른 사이트까지 번짐 ③ 빈 사이트 INSERT의 갭 락 순환. 근본 원인은 **REPEATABLE READ에서의 갭/범위 잠금과 스냅샷**이다. 그래서 설계를 단순화한다.

**규칙**
1. **메뉴를 바꾸는 모든 연산(생성·수정·재활성화/비활성화·영구삭제·구조 반영)과 사이트를 바꾸는 모든 연산(수정·삭제)은 해당 사이트 행을 `FOR UPDATE`로 먼저 잠근다.** 사이트 행 하나가 "그 사이트의 쓰기 문"이라 같은 사이트의 쓰기는 완전히 직렬화된다(부모·자식 교착 불가, 빈 사이트도 동일). 서로 다른 사이트는 서로 다른 행이라 병렬이다. 관리자 전용 저빈도 쓰기라 사이트 내부 병렬성 손실은 무관하다.
2. **메뉴·사이트 쓰기 트랜잭션은 `@Transactional(isolation = READ_COMMITTED)`** 로 실행한다(선례: `BannerService:189`·`MediaService:178`·`PopupService:217` — 갭 락 순환 방지). RC에서는 잠금 읽기가 일치 행만 잠그고(갭 락 없음) 비잠금 읽기도 문장마다 최신 커밋을 본다 → **선조회가 만든 낡은 읽기 뷰 문제(리뷰 1)와 범위 잠금이 다른 사이트로 번지는 문제(리뷰 2-3·4)가 구조적으로 사라진다.** 따라서 `existsByUpMenuNo`·`existsByUpMenuNoAndUseYnTrue`는 **비잠금 그대로** 둔다(v2의 `PESSIMISTIC_READ` 교체는 철회). 사이트 FK 검사의 공유 행 잠금은 사이트 `FOR UPDATE` 보유자와 충돌하지 않는다.
3. **단일 행 연산(메뉴 번호만 안다)의 사이트 도출**: 스칼라 투영 선조회(`select m.siteId from Menu m where m.menuNo = :id` — 엔티티를 영속성 컨텍스트에 올리지 않는다) → 사이트 행 `FOR UPDATE` → 메뉴 행 `FOR UPDATE`(기존)로 다시 읽어 `site_id`가 선조회와 같은지 확인(다르면 409 — 정상 경로에서는 불가능한 방어) → 이하 기존 로직. RC라 선조회가 읽기 뷰를 고정하지 않는다.
4. **잠금 순서는 항상 사이트 행 → 메뉴 행(menuNo 오름차순)**. 생성·구조 반영은 기존대로 그 사이트의 메뉴 행 전체를 `FOR UPDATE`(`findAllForUpdate` → `findAllBySiteIdForUpdate`)로 잠근다 — 사이트 문이 이미 직렬화하므로 중복이지만 **이전 앱(롤백 구간)·직접 SQL처럼 문을 모르는 쓰기와의 방어로, 그리고 검증된 기존 동작·시험 보존을 위해 유지**한다. 다른 사이트의 메뉴 행은 어떤 경로에서도 잠그지 않는다(부모 조회는 D5 규칙). 재활성화 경로의 부모 행 잠금은 같은 사이트 부모만(불일치 시 거부).
5. **사이트 삭제**: 문 잠금 후 메뉴 존재 검사(비잠금이어도 RC라 최신 커밋을 본다, 모든 메뉴 쓰기가 같은 문을 지나므로 경합 없음) → 있으면 409. FK `RESTRICT`는 최후 방어선.
6. **링크 정책 강화(허용 → 비허용)**: 문 잠금 후 그 사이트의 외부 URL 메뉴 검사 → 있으면 409(개수 안내). URL을 쓰는 모든 메뉴 쓰기가 같은 문을 지나므로 **저장된 메뉴 URL은 항상 현재 정책을 만족한다.**
7. 교착 분석: 모든 쓰기가 "사이트 행(단일 문) → 해당 사이트 메뉴 행" 순이고 다른 사이트 행은 건드리지 않는다. 사이트 행을 기다리는 트랜잭션은 아무 메뉴 행도 쥐고 있지 않다(문이 먼저). → 순환 불가. 사이트 FK 삽입 검사가 잡는 사이트 행 공유 잠금은 문 보유자와의 직렬화로만 나타나며 보유자가 메뉴 행을 기다리지 않는다.
8. **최상위 진입점 계약(라운드 3 지적 1)**: `REQUIRED` 전파에서는 이미 열린 트랜잭션에 참여하면 메서드의 격리 선언이 무시된다. 메뉴·사이트 쓰기 서비스는 컨트롤러가 호출하는 **최상위 진입점**이고(다른 서비스가 안쪽에서 호출하지 않는다) `AdminActionLogAspect`의 감사 커밋 순서 계약도 같은 전제다 — 서비스 Javadoc에 명시한다. `REQUIRES_NEW`로 일괄 교체하지 않는다(호출자 원자성이 달라지고 다른 서비스 선례와 다르다). 시험이 서비스를 외부 트랜잭션 안에서 부르면 그 `TransactionTemplate`도 RC로 지정하고, 쓰기 진입점이 실제로 RC인지(`@@transaction_isolation`)를 확인하는 시험을 둔다.
9. 시험으로 고정(§7): 삭제 vs 재활성화(부모·자식)·양방향 교차 사이트 부모·빈 사이트 동시 첫 생성(같은/다른 사이트, **양쪽이 범위 조회를 끝낸 뒤 동시에 INSERT하는 배리어**)·정책 강화 vs 외부 URL 쓰기. `EXPLAIN`으로 `site_id` 범위 조회가 FK 인덱스를 타는지 확인.

### D5. 서비스 범위 — 사이트 스코프 적용

- `createMenu`: 요청의 `siteId` 필수. 사이트 존재(404)·링크 정책. **부모는 이미 잠근 그 사이트의 메뉴 집합(`allMenus`)에서 찾는다** — 별도 `findByIdForUpdate(parent)` 쿼리를 쓰지 않아 다른 사이트 행을 잠그지 않는다(교착 방지). 집합에 없으면(없는 번호이거나 다른 사이트 소속) 존재를 드러내지 않도록 **같은 404**. 재활성화 경로의 부모 잠금은 부모 `site_id`가 대상과 다르면 거부한다.
- `applyStructure`: 요청에 `siteId`. 도달 가능 집합을 **그 사이트 행만**으로 계산하고 요청 집합과 비교(다른 사이트 메뉴 번호가 섞이면 집합 불일치 → 409 기존 계약 그대로).
- `updateMenu`/`deleteMenu`/`getMenu`: 경로가 `menuNo`라 사이트는 행에서 도출. 수정은 URL 변경 시 그 메뉴 사이트의 정책으로 판정.
- `getMenuTree(siteId, useYn)`: 해당 사이트 메뉴만. **노출 안내는 ADMIN 사이트에서만 계산**하고 PUBLIC 사이트는 `exposure`/`exposureLabel`을 응답에서 생략한다(JSON `non_null`, 화면은 없으면 안내 영역을 숨김). 쟁점 4.
- `getSidebarMenus`: **관리자 사이트(`ADMIN_SITE_ID = 1`)만** 읽는다(`findAllBySiteIdAndUseYnTrue…`). 사이드바 렌더마다 사이트 조회 쿼리를 늘리지 않으려고 상수 ID를 쓰고, 시험이 "시드 `id=1`이 ADMIN 타입"을 고정한다. 통합 검색은 `getSidebarMenus`를 그대로 쓰므로 자동으로 관리자 메뉴만 본다.
- `MenuVisibility`는 변경하지 않는다(입력이 이미 관리자 사이트 행뿐이다).
- 깊이 3·비활성 부모 규칙·ord 규칙·감사 규칙은 사이트 안에서 그대로 적용된다.

### D6. 사이트 CRUD

- 신규 패키지 `com.cms.admin.site` (`Site`·`SiteType`·`SiteRepository`·`SiteService`·`SiteController`·DTO·`SiteKey`·`SiteLinkPolicy`). 엔티티 규약: `@Setter` 없음, 도메인 메서드, 연관 없이 ID만.
- API (전부 `@PreAuthorize("hasRole('ADMIN')")`, `/admin/api/sites`):
  - `GET /admin/api/sites` — 전체 목록(비활성 포함, 메뉴 개수 포함 → 화면 삭제 가능 표시용은 서버 판정이 최종)
  - `POST /admin/api/sites` — 생성(`PUBLIC`만 생성 가능, 타입은 요청에 받지 않는다). 키 중복 409(`DUPLICATE_RESOURCE`), 예약어/형식 400
  - `GET /admin/api/sites/{id}`, `PATCH /admin/api/sites/{id}` — `siteName`·`allowExternalLink`·`useYn`(null=유지), 키 변경 불가
  - `DELETE /admin/api/sites/{id}` — 204. 시드 2행 409, 메뉴가 있으면 409, 없으면 404
- 보호 규칙: **시드 2행**(타입 `ADMIN` 또는 `site_key in (admin, main)` 대신 `id <= 2`가 아니라 `SiteService.SEED_SITE_IDS = {1,2}` 상수)은 삭제 409·**비활성화 409**(PR ②에서 "기본 사이트" 개념이 생기기 전까지 `/` 이동 대상이 사라지지 않게)·`allowExternalLink` 변경은 `main`만 가능(`admin`은 항상 false라 변경 400). 이름은 변경 가능.
- 삭제 가드의 "내용" 정의는 지금은 **메뉴 존재**뿐이다. PR ③·④가 `site_id`를 가진 새 테이블(배너·팝업·FAQ 카테고리·게시판 등)을 추가할 때 **각 FK도 `RESTRICT`로 두고** 삭제 가드(`SiteService.hasContent`)에 포함시키는 것을 PR ③·④의 완료 기준에 적는다(여기서 추상화를 미리 만들지 않는다).
- 감사: `SITE_CREATE`/`SITE_UPDATE`/`SITE_DELETE`(`targetType=SITE`, `targetId=siteId`, 삭제는 `targetLabel`=키; 사이트 이름은 관리자 입력이지만 방문자 입력이 아니므로 `MENU_DELETE`처럼 스냅샷 라벨 허용). `AdminActionTypes.ALL`·로그 화면 라벨 동기화 시험 갱신.

### D7. 화면 — 같은 셸에 "사이트" 탭 + 메뉴 탭의 사이트 선택

선택지:
- (A) 정적 페이지·메뉴 셸에 **세 번째 탭 "사이트"**(ADMIN 전용, `menu-tab.html`과 같은 방식의 조각) + 메뉴 탭 상단 **사이트 선택 셀렉트**
- (B) 별도 페이지 `/admin/site/manage` + 사이드바 메뉴 시드·`AdminFeature` 추가
- (C) 메뉴 탭 안에 사이트 관리 모달

→ **(A)**. 왜: (B)는 `AdminFeature` 항목·사이드바 시드 마이그레이션·권한 매트릭스 표시·검색 노출 등 인가 표면을 새로 만든다. 기존 `/admin/site-page/manage` 게이트와 `/admin/**` ADMIN 캐치올만으로 끝나고 "인가 정책 변경 없음"을 유지하는 편이 PR ①의 성격(공개 동작 불변)과 맞다. (C)는 사이트 목록 표·생성/수정 폼이 모달에 들어가기엔 크다.
- 메뉴 탭: 사이트 셀렉트(활성/비활성 표기)가 `loadTree`의 `siteId`를 정한다. 사이트를 바꾸면 초안 잠금 규칙(초안 있으면 전환 차단)을 기존 "비활성 포함 토글"과 같이 적용. 선택은 `sessionStorage`에 보존(편의, 실패해도 기본 `관리자` 사이트).
- URL 해시: `#menu`(+사이트는 기억), 새 탭 `#site`. 메뉴 구조 탭 CSS는 `#menuTabPanel` 스코프, 새 조각은 `#siteTabPanel` 스코프.
- **사이트 전환 상태 전이(라운드 2 지적 1)**: 현재 `loadTree()`는 조회 시작 시 `selectedMenuNo`·폼을 비우지 않고 저장은 `selectedMenuNo`로 PATCH한다. 단건 API는 사이트 조건을 받지 않으므로 서버는 "A 메뉴 수정"을 정상 처리한다. 따라서 사이트 셀렉트 변경 **즉시** ① 선택·폼·삭제 모달의 고정 대상(`selectedMenuSnapshot`)을 초기화하고 ② 새 트리가 적용될 때까지 저장·삭제·추가·반영 버튼을 잠그며(트리 응답 세대 `loadSeq`가 최신일 때만 해제) ③ 조회가 실패하면 잠금을 유지한 채 오류 문구와 [다시 시도], 또는 이전 사이트 값으로 셀렉트를 되돌린다(어느 쪽이든 "셀렉트 표시 = 편집 대상"이 항상 성립). 지연·실패 응답 브라우저 시험.
- **새로고침 보호 참여(리뷰 지적 5)**: 구조 반영 성공은 문서 전체를 새로고침하므로, 사이트 패널의 미저장 입력·저장 중 상태를 `ContentScreen`의 반영 전 검사(`isPagePanelBusyOrDirty`와 같은 자리)에 포함하고, `content:menu-applying` 동안 사이트 입력·저장 버튼을 잠근다. 대칭으로 사이트 저장·삭제 요청 중에는 구조 반영을 막는다.
- **`content:sites-changed` 처리(라운드 4 지적 1)**: 사이트 셀렉트 재구성은 사용자 전환과 다른 경로다. `loadTree()`는 트리를 `destroy()`하고 `baseStructure`를 다시 만들어 초안을 지우므로 이벤트에서 호출하지 않는다. ① 선택 중인 사이트가 목록에 남아 있으면 **ID·트리·초안을 유지**하고 옵션의 표시명·링크 정책·활성 표기만 갱신한다(링크 정책이 바뀌면 화면 JS의 URL 판정만 갱신) ② 선택 사이트가 삭제됐거나 목록에서 사라졌으면 위 "사이트 전환 상태 전이" 절차(초기화·잠금·조회)로 기본 사이트로 전환하되, 초안이 있으면 그 사실을 알리고 폐기한다(대상 자체가 사라졌으므로 보존 불가). 초안이 있는 상태의 사이트 생성/이름 수정/삭제 이벤트 브라우저 시험.
- 사이트 변경(생성/수정/삭제) 성공 시 `content:sites-changed` 이벤트를 보내 메뉴 탭이 셀렉트를 다시 채운다. 사이트 탭은 새 조각 `admin/site/fragments/site-tab.html`(panel·modals·script 3조각, 기존 조각 이름 규칙 준수).
- `/admin/menu/manage` 302 유지, 사이드바의 "메뉴 관리" 메뉴 이름·URL 변경 없음.
- 셸 제목은 "정적 페이지 · 메뉴 · 사이트 관리"(ADMIN) / "정적 페이지 관리"(MANAGER)로 갱신.

### D8. API 형태 — 기존 메뉴 엔드포인트 유지 + 사이트는 파라미터

- `GET /admin/api/menus/tree?siteId=&useYn=` (`siteId` 필수 — 누락 400. 기본값을 주지 않는다: 어느 사이트를 편집하는지 암묵적이면 잘못된 사이트에 쓰는 사고가 난다. **전역 `GlobalApiExceptionHandler`에는 `MissingServletRequestParameterException` 처리가 없어 `required=true`면 500이 된다**(`com.cms.admin.session` 선례) → `required=false`로 받아 컨트롤러가 `InvalidRequestException`(400 `INVALID_REQUEST`, 고정 문구)으로 직접 검증하고, 빈 값·`abc`·Long 초과는 기존 타입 불일치 핸들러/동일 검증이 400. 전역 핸들러는 건드리지 않는다)
- `POST /admin/api/menus` 본문에 `siteId` 필수, `PUT /admin/api/menus/structure` 본문에 `siteId` 필수.
- `PATCH|GET|DELETE /admin/api/menus/{id}` 변경 없음(사이트는 메뉴가 안다). 응답 `MenuResponse`에 `siteId` 추가.
- 선택지 중 중첩 경로 `/admin/api/sites/{siteId}/menus…`는 기존 엔드포인트 6개를 전부 옮겨야 하고 화면·시험 파급이 커서 기각. 호출 화면: `menu-tab.html` 한 곳(`manage.html`이 포함).
- 낡은 클라이언트가 `siteId` 없이 호출하면 400 — 배포와 화면이 한 앱이라 수용.

### D9. 과설계 경계 (하지 않는 것)

- 사이트 간 메뉴 이동/복사, 사이트별 MANAGER 권한, 사이트 순서 변경, 사이트 설명·로고 같은 부가 필드, `site_key` 변경, 공개 경로 바인딩, 기본 사이트 컬럼(PR ②에서), 메뉴 감사 라벨 확장 — 전부 이번 범위 밖.

## 5. 인가·스키마 영향 고지 (승인 시 별도 확인 대상)

- **스키마**: 새 테이블 `site`(V50·V51), `menu.site_id` NOT NULL DEFAULT 1 + FK(V52). 되돌리기: 컬럼/테이블 삭제 마이그레이션은 만들지 않는다. 애플리케이션 롤백은 가능하나 R2 주의.
- **인가 정책 변경: 없음.** `SecurityConfig`·레이트리밋·`AdminFeature`·권한관리 변경 없음. 새 API는 `/admin/api/**`(기존 ADMIN 캐치올) + `@PreAuthorize("hasRole('ADMIN')")`. 인가 선언 컨벤션 시험이 새 컨트롤러를 감지한다.
- 공개 경로·공개 출력 변경 없음.

## 6. 작업 단계 (PR ①, 안쪽 → 바깥)

1. 마이그레이션 V50~V52 + 마이그레이션 시험(시드 고정 ID·재실행 안전·`menu.site_id` 기본값/FK, 기존 메뉴 행이 모두 `site_id=1`)
2. `Site`/`SiteType`/`SiteRepository`(잠금 쿼리 포함), `SiteKey`(형식·예약어), `SiteLinkPolicy`
3. `Menu.siteId`, `MenuRepository` 사이트 스코프 쿼리, `MenuService` 사이트 스코프화 + `SiteService`
4. 컨트롤러·DTO·`AdminActionTypes`·감사 라벨, `SafeMenuUrl` 일반화
5. 화면: 사이트 탭 조각, 메뉴 탭 사이트 셀렉트, 셸 갱신
6. 시험(§7) → `./gradlew test`
7. 실기 검증(§8) → 문서(CLAUDE.md 지도·`com.cms.admin.menu`/신규 `com.cms.admin.site` CLAUDE.md, `docs/deployment.md`·`docs/migration-guide.md` 롤백 주의, 이 문서 결과 갱신)

## 7. 테스트 계획

- 마이그레이션: V50~V52 적용 후 시드 2행·타입·고정 ID, 재실행 안전, 기존 메뉴 `site_id=1`, FK RESTRICT.
- 단위: `SiteKey` 형식/예약어, `SiteLinkPolicy`(관리자 내부만, 공개 내부+외부, 비공개 접두어·인코딩·dot segment 우회, 외부 userinfo 위장), `SiteService`(시드 보호 3종, 키 중복, 삭제 가드, 정책 강화 409).
- `MenuServiceTest` 갱신: 사이트 스코프 쿼리·교차 사이트 부모·없는 부모 모두 404(구조 요청에 다른 사이트 메뉴 번호가 섞이면 집합 불일치 409와 구분)·정책 위반 400·트리에서 PUBLIC 사이트 exposure 생략.
- 통합(`MenuConcurrencyIntegrationTest` 확장, 실제 락): 서로 다른 사이트 편집 비직렬화, 메뉴 생성 vs 사이트 삭제, 메뉴 생성 vs 정책 강화(항상 불변식 유지), 다른 사이트 메뉴 번호가 섞인 낡은 초안 409.
- 추가 동시성(라운드 1·2): ① 삭제/비활성화의 선조회 뒤·잠금 전에 다른 트랜잭션이 자식을 커밋해도 하위 존재 검사가 이를 본다(RC) ② 양방향 교차 사이트 부모 지정 동시 생성 — 교착 없이 둘 다 404 ③ **같은 사이트의 부모 영구삭제 vs 자식 재활성화** 동시 — 교착 없음 ④ 빈 사이트 동시 첫 메뉴 생성(같은 사이트: 직렬화) ⑤ **서로 다른 빈 사이트 동시 생성**(양쪽이 범위 조회를 끝낸 뒤 동시에 INSERT하는 `CyclicBarrier` — 교착·대기 없음) ⑥ 정책 강화 vs 외부 URL 메뉴 쓰기 ⑦ 사이트 문 `FOR UPDATE` 보유 중 다른 사이트 쓰기는 대기하지 않음 ⑧ MockMvc(실제 advice)로 `siteId` 누락·빈 값·`abc` → 400, 500 아님.
- 브라우저(리뷰 5): 사이트 폼 입력 중 메뉴 탭 구조 반영 시 차단/안내, 사이트 저장 중 반영 차단.
- 사이드바/검색: 공개 사이트 메뉴가 관리자 사이드바·통합 검색에 나오지 않음.
- 컨트롤러 슬라이스: `siteId` 누락 400, 비-ADMIN 403(MANAGER)·익명 401, 예약어/중복.
- 컨벤션: 예약어 포괄 검사(§D2), `AdminActionTypes.ALL` 동기화, 인가 선언.
- **`MenuConcurrencyIntegrationTest` 프로브 이동(라운드 3 지적 3)**: `verifyOrderedWrites`(`:270~308`)는 후행 호출의 **메뉴 `findByIdForUpdate` 진입**을 대기 신호로 쓰지만, 사이트 문 도입 후 후행 호출은 사이트 잠금 쿼리에서 먼저 대기해 메뉴 쿼리에 도달하지 못해 시험이 타임아웃한다(운영 교착이 아님). 대기 관측 프로브를 **사이트 잠금 쿼리 호출 직전**으로 옮기고(선행이 사이트 문을 쥔 채 멈추는 위치도 맞춘다), 선행 커밋 뒤 메뉴 값 확인은 유지한다. 헬퍼를 쓰는 두 시험 모두 해당. 래치·프로브·관측 위치 변경은 메서드명/시그니처 변경과 별개 작업으로 추적한다.
- **누락돼 있던 파괴 시험 2개(라운드 4 지적 2)**: ① `MenuExposureSidebarIntegrationTest:106`은 `siteId` 없이 `/admin/api/menus/tree?useYn=all`을 호출해 200을 기대하므로 `siteId=1`(관리자 사이트)을 붙이도록 갱신한다 ② `ContentManagePageIntegrationTest:90`은 ADMIN 제목을 `"정적 페이지 · 메뉴 관리"`로 단언하므로 새 제목으로 갱신하고, 이 시험을 사이트 탭·모달·스크립트 조각(패널/모달/스크립트 존재, 중복 ID 없음)과 **MANAGER 응답에 사이트 탭·모달·API 문자열이 없음**까지 확장한다.
- 기존 시험 갱신 범위: `SafeMenuUrlValidationTest`(의미 변경), `MenuControllerTest`, `AdminSidebarAdvice*Test`, `MenuUrlSidebarRenderingIntegrationTest`, `Menu.builder()` 13곳(`siteId`).

## 8. 실기 검증 계획

골든 패스(사이트 생성 → 해당 사이트 메뉴 생성·외부 링크 → 구조 드래그 반영) → 왕복(수정·비활성화·메뉴 영구삭제·사이트 삭제 후 원복) → 경계(MANAGER가 `/admin/api/sites` 403, 관리자 사이트에 외부 링크 400, 시드 삭제 409, 예약어 400, 공개 사이트 메뉴가 사이드바에 안 보임) → 회귀(기존 관리자 메뉴 편집·사이드바·공개 `/` 불변). Playwright 스크린샷.

## 9. 리스크

- **R1 화면 회귀**: 1,442줄 `menu-tab.html` 수정. 사이트 전환·초안 잠금 상호작용이 새 결함 지점 → 실기 검증과 기존 JS 규칙(잠금/`busy`) 재사용.
- **R2 롤백(v2 개정)**: **공개 사이트 메뉴가 하나라도 있으면 V52 이전 앱으로의 롤백은 미지원**이다(V43 이전 롤백 미지원 선례). 이전 앱은 `site_id`를 모른 채 모든 행을 한 트리로 읽고(구조 반영 `MenuService:201`, 비활성 포함 트리 `:360`) 이동·재활성화도 허용하므로 **비활성화로는 보호되지 않는다**(공개 메뉴를 관리자 부모 아래로 옮기면 재배포 후 사이트 분리가 깨진다). 허용 절차: ① 공개 사이트 메뉴를 전부 영구삭제한 뒤 롤백(관리자 사이트 메뉴는 `DEFAULT 1`로 호환) ② 또는 V52 이전 백업 복구. 구·신 앱 동시 실행 배포도 금지. `DEFAULT 1`은 이전 앱의 INSERT만 호환시킨다. `docs/deployment.md`·`docs/migration-guide.md`에 기록한다. V52를 되돌리는 마이그레이션은 만들지 않는다(`ddl-auto: validate`는 엔티티에 없는 DB 컬럼을 허용해 이전 앱은 기동한다).
- **R3 전체 행 잠금 범위 축소**의 미묘함: 서로 다른 사이트는 병렬이 되지만 한 사이트 내부 규칙은 그대로여야 한다 → 동시성 시험으로 고정.
- **R4 예약어 표류**: 자동 검사로 완화하되 검사가 놓치는 경로(문자열 조합으로 만든 매핑)는 리뷰 확인.
- **R5 공개 사이트 메뉴는 PR ⑤ 전까지 어디에도 렌더링되지 않는다** — 정상. 문서에 명시해 "저장했는데 안 보임" 혼동을 막는다.

## 10. 후속 PR 윤곽 (참고, 이 리뷰의 대상 아님)

- ② 라우팅: 기본 사이트 컬럼 추가, `SecurityConfig` 동적 무인증 경로(예: `/{siteKey}/…` 패턴 + 존재하지 않는 키 404 흡수), 레이트리밋 패턴. **인가 정책 변경이므로 별도 고지·승인.** PR ①이 만든 예약어 집합과 자동 검사가 전제.
- ③④: `site_id` 컬럼 FK `RESTRICT` + `SiteService.hasContent` 확장 + 롤백 주의.
- ⑤: 공개 템플릿이 사이트 메뉴를 렌더(출력 시 `SiteLinkPolicy` 재검증, 통과 못 하면 `#`, `rel="noopener noreferrer"`).

## 개정 이력

- v1 (2026-10-13): 최초 작성 — 정찰 기반 쟁점 9건, 결정 D1~D9.
- v2 (2026-10-13, 라운드 1 — codex `gpt-6.1-sol` no-ship, 7건 전부 수용) 변경:
  - D4 전면 개정(지적 1·4): 생성·구조 반영은 사이트 행 `FOR UPDATE`(빈 사이트에서도 직렬화), 단일 행 연산은 `FOR SHARE`. 선조회는 스칼라 투영 + 잠금 후 재읽기·`site_id` 일치 확인, 하위 존재 검사 2종을 `PESSIMISTIC_READ` 쿼리로 교체(스냅샷 비의존). 교착 분석 재작성. 근거: 비잠금 선조회가 읽기 뷰를 고정해 `existsByUpMenuNo`가 낡은 상태를 볼 수 있음(`MenuService:149,200`), 사이트 `FOR SHARE`만으로는 빈 사이트 동시 INSERT를 직렬화하지 못함.
  - D5(지적 3): 부모를 이미 잠근 사이트 집합에서 조회, 없으면 404 통일, 다른 사이트 행 비잠금. 근거: 교차 사이트 부모 지정으로 인한 양방향 교착.
  - D3(지적 6): 비공개 접두어에 `swagger-ui*` 시작 일치·`?#` 절단 추가(`SecurityConfig:81` `/swagger-ui.html`).
  - D2(지적 7): 예약어 자동 검사에서 키 형식 불가 세그먼트 제외 명시.
  - D7(지적 5): 사이트 패널을 새로고침 보호(`ContentScreen` busy/dirty, `menu-applying` 잠금)에 참여.
  - R2(지적 2): 비활성화는 롤백 보호가 아님 → 공개 사이트 메뉴가 있으면 롤백 미지원, 허용 절차 명시.
  - 테스트 계획에 동시성·브라우저 시험 추가.
- v3 (2026-10-13, 라운드 2 — codex `gpt-6.1-sol` no-ship, 5건 전부 수용) 변경:
  - D4 재개정(지적 2·3·4): v2의 `FOR SHARE`/`PESSIMISTIC_READ` 존재 검사 설계를 **철회**하고 "사이트 행 `FOR UPDATE` = 단일 문 + 메뉴·사이트 쓰기는 `READ_COMMITTED`"로 단순화. 근거: 삭제(부모 잠금 → 자식 잠금 읽기)와 재활성화(자식 잠금 → 부모 잠금)의 같은 사이트 교착, 존재 검사에 `site_id` 조건이 없어 REPEATABLE READ 범위 잠금이 다른 사이트로 번짐, 빈 사이트 서로 다른 가드 간 갭 락 순환 — 셋 모두 RR의 갭/범위 잠금이 뿌리이고 RC 선례(`BannerService:189`·`MediaService:178`·`PopupService:217`)가 있다. RC에서는 라운드 1 지적 1(낡은 읽기 뷰)도 구조적으로 해소돼 `existsByUpMenuNo*`는 비잠금 유지.
  - D7(지적 1): 사이트 전환 즉시 선택·폼·삭제 모달 대상 초기화, 새 트리 적용 전 쓰기 잠금, 조회 실패 시 잠금 유지 또는 복귀. 근거: `menu-tab.html:832,1205`의 `selectedMenuNo` 잔존으로 A 메뉴 수정이 B 화면에서 가능.
  - D8(지적 5): `siteId` 누락 400을 컨트롤러 직접 검증(`required=false`)으로 — 전역 핸들러에 `MissingServletRequestParameterException` 처리가 없다는 사실을 `AdminSessionController` 주석으로 확인. 전역 핸들러 수정은 범위 밖(기각 사유: 무관한 전역 동작 변경).
  - 테스트 계획: 부모삭제 vs 자식재활성화, 배리어 기반 빈 사이트 동시 INSERT, MockMvc 누락 파라미터 시험 추가.
- v4 (2026-10-13, 라운드 3 — codex `gpt-6.1-sol` no-ship, 3건) 변경:
  - D4 계약 추가(지적 1, 수용): 쓰기 서비스는 최상위 진입점이라는 계약을 명시하고 시험의 외부 트랜잭션도 RC로 지정, 실제 격리 수준을 확인하는 시험 추가. 기각: `REQUIRES_NEW` 일괄 교체(호출자 원자성 변경).
  - D3(지적 2, **부분 수용**): `app.base-url`(기존 설정)과 출처가 같은 절대 URL은 내부 경로 규칙(비공개 접두어 포함)으로 판정. 기각 범위: 별칭 호스트 탐지 — 호스트 집합을 알 수 없고 새 설정 항목이 과설계이며 이 규칙은 인가가 아닌 심층 방어(편집자 ADMIN만, `/admin/**`은 게이트가 막음). 수용한 한계로 문서화.
  - §7(지적 3, 수용): `MenuConcurrencyIntegrationTest.verifyOrderedWrites` 프로브를 사이트 잠금 쿼리 직전으로 이동하는 작업을 시험 갱신 범위에 명시.
  - D1(리뷰어 한계 언급 대응): V50~V52 SQL 초안과 `validate`·부분 실패 복구 설명을 계획서에 포함해 리뷰 가능하게 함.
- v5 (2026-10-13, 라운드 4 — codex `gpt-6.1-sol` no-ship, 4건 전부 수용, 설계 변경 없이 명세 보강) 변경:
  - D7 `content:sites-changed` 처리 정의(지적 1): 선택 사이트가 남으면 초안·트리 유지(표시/정책만 갱신), 사라지면 전환 절차 + 초안 폐기 안내. 근거: `loadTree()`가 `destroy()`·`baseStructure` 재생성으로 초안을 지움(`menu-tab.html:832,859~865`).
  - §7(지적 2): `MenuExposureSidebarIntegrationTest:106`(siteId 누락 → 400)·`ContentManagePageIntegrationTest:90`(제목 단언) 갱신 + MANAGER 응답 제외 검증 확장.
  - D3 감사 서술 정정(지적 3): 행위·메뉴 ID만 기록, URL 이력 없음은 수용한 한계(`AdminActionLogAspect`가 지정된 ID·라벨만 추출).
  - §7 상태 코드 통일(지적 4): 생성의 교차 사이트 부모 = 404, 구조 요청의 타 사이트 번호 혼입 = 409.
  - D2 `siteKey` 수정 DTO는 알 수 없는 키 무시로 확정(`BoardUpdateRequest` 선례, 엄격 거부는 `PostPublishPeriodRequest` 특별 계약).
- v5 라운드 5: codex ship (새 차단 사유 없음). 사용자 승인 대기.

---

## 구현·검증 결과 — PR ① (2026-10-13)

### Context
사이트 설정 메뉴(다중 사이트·사이트별 메뉴·공개 라우팅·콘텐츠 분리) 중 **PR ①(사이트 모델 · 사이트 관리 UI · 메뉴 사이트 분리 편집)** 을 계획 v5 그대로 구현했다. 공개 화면·`SecurityConfig`·레이트리밋·`AdminFeature`는 바뀌지 않았다. PR ②~⑤는 미착수.

### 핵심 확정 사항 (계획 v5에서 변경 없음)
- `site`(시드 `admin` id=1·`main` id=2) + `menu.site_id NOT NULL DEFAULT 1` FK RESTRICT(V50~V52), `site_type`로 관리자 사이트 식별(`Site.ADMIN_SITE_ID`).
- 링크 정책: DTO는 형식, `SiteLinkPolicy`가 서비스에서 사이트 정책 판정(관리자=내부만 / 공개=외부 허용 설정 + 비공개 경로 금지 + `app.base-url` 같은 출처 절대 URL은 내부 경로 규칙).
- 잠금: 사이트 행 `FOR UPDATE` 단일 문 + `READ_COMMITTED`, 쓰기 서비스는 최상위 진입점, 다른 사이트 행 비잠금, 부모는 잠근 집합에서 조회(404 통일).
- UI: 기존 셸의 ADMIN 전용 "사이트" 탭 + 메뉴 탭 "편집할 사이트" 셀렉트(전환 상태 전이·`sites-changed` 처리·새로고침 보호 참여).
- 사이트 키 예약어 + 컨벤션 시험, 시드 삭제·비활성화 불가, 정책 강화 409.

### 구현 파일
- 마이그레이션: `V50__create_site.sql`·`V51__seed_site.sql`·`V52__add_menu_site_id.sql`
- 신규 `com.cms.admin.site`: `Site`·`SiteType`·`SiteKey`·`SiteLinkPolicy`·`SiteRepository`·`service/SiteService`·`controller/SiteController`·DTO 5종·`CLAUDE.md`
- 변경: `Menu`·`MenuRepository`·`MenuService`(사이트 스코프·잠금·정책)·`MenuController`·메뉴 DTO(`siteId`·`@SafeMenuUrl` 형식화)·`AdminActionTypes`(+`SITE_*`)·활동 로그 라벨
- 화면: `admin/site/fragments/site-tab.html`(신규)·`admin/menu/fragments/menu-tab.html`·`admin/page/manage.html`·`admin/log/manage.html`
- 문서: 루트·메뉴 `CLAUDE.md`, `docs/deployment.md`·`docs/migration-guide.md`(롤백 주의)·`docs/troubleshooting.md`, 이 계획서·인덱스

### 검증 결과
- **시험**: `./gradlew test` 전체 — 신규 시험 포함 통과(`SiteKeyTest`·`SiteLinkPolicyTest`·`SiteKeyReservedConventionTest`·`SiteMigrationTest`·`SiteServiceTest`·`SiteControllerTest`·`SiteMenuApiIntegrationTest`·`SiteMenuConcurrencyIntegrationTest`, 갱신 `MenuServiceTest`·`MenuServiceStructureTest`·`MenuConcurrencyIntegrationTest`·`MenuControllerTest`·`SafeMenuUrlValidationTest`·`MenuExposureSidebarIntegrationTest`·`MenuUrlSidebarRenderingIntegrationTest`·`ContentManagePageIntegrationTest`·`AdminActionLogCommitOrderIntegrationTest`).
- **변이 실험**: 사이트 잠금(`findByIdForUpdate`)을 일반 조회로 바꾸면 빈 사이트 동시 생성의 ord 중복(`[0,0]`)·정책 강화 불변식 위반·`verifyOrderedWrites`·RC 관측 시험 5건이 실패함을 확인하고 원복했다.
- **실기(Playwright, 일회용 MariaDB 3317 + 앱 8081)**: V50~V52 적용·기존 메뉴 17개 전부 `site_id=1` → 사이트 탭 생성(키 편집 잠금)·새 사이트가 메뉴 탭 셀렉트에 새로고침 없이 반영 → 공개 사이트 메뉴 생성(외부 URL 허용·노출 안내 숨김·트리 격리)·비공개 경로 거부 문구 → 관리자 사이트 외부 URL 클라이언트 안내·생성→비활성화→영구삭제 원복(17개) → 사이드바에 공개 메뉴 미노출 → 정책 강화 409(문구)·사이트 삭제(메뉴 정리 후 204)·시드 삭제 409 → 감사 로그 `SITE_CREATE/UPDATE/DELETE`(삭제 라벨 `news`) → 공개 `/`·`/faq`·`/notices`·`/inquiry` 불변. 스크린샷 3장(`adversarial-review/screenshots/site-menu/`).

### 이슈
- **실기에서 잡은 결함 1건(수정함)**: 저장·비활성화·영구삭제 직후 사이트 셀렉트가 `disabled`로 남음 — `finally`에서 `refreshDraftUi()` 누락(`docs/troubleshooting.md` 기록).
- **자동화로 확인하지 못한 것**: "구조 초안이 있는 상태에서 `content:sites-changed`가 초안을 보존" 경로는 jstree DnD를 Playwright 입력으로 재현하지 못해 **브라우저로 직접 확인하지 못했다**(코드상 `loadTree()`를 호출하지 않고 표시명·정책만 갱신, 사이트 목록 반영·셀렉트 유지는 확인). 사이트 전환 실패 시 되돌림·폼 초기화·잠금 해제는 `fetch`를 강제 실패시켜 확인했다.
- 수용한 한계(계획대로): 별칭 호스트 비탐지, `MENU_*` 감사에 URL 이력 없음, 공개 사이트 메뉴는 PR ⑤ 전까지 렌더링 없음.

### 후속
- PR ② 라우팅(`/{siteKey}/…`, `/` 302, **`SecurityConfig` 동적 무인증 경로·레이트리밋 — 인가 정책 변경, 별도 고지·승인**), PR ③ 사이트별 콘텐츠, PR ④ 사이트별 공지, PR ⑤ 공개 메뉴 렌더링 — 각 PR 착수 때 이 문서를 갱신해 리뷰한다. PR ③·④는 새 `site_id` FK를 `RESTRICT`로 두고 `SiteService.hasContent`를 확장한다.
- 로드맵 반영(`/updateRoadmap`)은 사이트 설정 메뉴 전체 완료 후 한 번에(사용자 지시, 보류 stash 포함).
