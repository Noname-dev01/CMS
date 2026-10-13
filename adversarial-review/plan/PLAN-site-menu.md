# PLAN-site-menu — 사이트 설정 메뉴 (다중 사이트·사이트별 메뉴·공개 라우팅·콘텐츠 분리)

- 작성: 2026-10-13 / 상태: **PR ① 구현·검증 완료(계획 v5, #143 머지). PR ② 구현·검증 완료·머지(#144). PR ③ 구현·검증 완료(P3-v6, 리뷰 6라운드 ship — 파일 말미 "PR ③ 상세 설계"·"구현·검증 결과 — PR ③", 미커밋), PR ④⑤ 미착수**
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


---

# PR ② 상세 설계 — 라우팅·공개 경로 이전 (2026-10-13, P-v1)

> 이 부분이 PR ②의 리뷰 대상이다. 위 PR ① 본문(D1~D9·v1~v5)은 머지된 사실의 기록이며 이번 리뷰 범위가 아니다. **이 PR은 `SecurityConfig`·레이트리밋의 인가 정책을 바꾼다 — 승인 단계에서 별도 고지한다.**

## P1. Context와 범위

공개 화면 경로를 `/{siteKey}/…` 아래로 옮긴다(공통 포털 없음). 사용자 확정 사항(재질문 금지): 루트 접두어 · `/`는 기본 공개 사이트로 **302** · 접두어 없는 기존 URL(`/notices`·`/boards`·`/pages`·`/faq`·`/inquiry`)은 **404로 종료** · 존재하지 않는·비활성 사이트 키는 기존 공개 불변식처럼 **같은 404** · 기본 사이트를 가리키는 컬럼 추가 · 본문 속 `/content-images/{id}`는 사이트 무관 경로 · 모든 공개 링크가 접두어를 포함해 생성.

**이 PR의 범위 밖(PR ③~⑤)**: 사이트별 콘텐츠 분리(지금은 **모든 공개 사이트가 같은 콘텐츠**를 각자의 접두어 아래에서 보여 준다), 사이트별 공지, 공개 메뉴 렌더링, 기본 사이트 **변경** 기능(P2-5).

### 정찰에서 확인한 사실 (코드를 읽은 것)
- 공개 컨트롤러: `HomeController`(`/`), `PublicNoticeController`(`/notices`), `PublicBoardController`(`/boards`), `PublicSitePageController`(`/pages`), `PublicFaqController`(`/faq`), `PublicInquiryController`(`/inquiry` GET·POST, PRG `RedirectView("/inquiry?sent")`), 이미지 3종(`/banners/{id}/image`·`/popups/{id}/image`·`/content-images/{id}`).
- `SecurityConfig`는 경로마다 `permitAll`(GET/HEAD) + 같은 경로의 나머지 메서드 `denyAll` + 마지막 `anyRequest().denyAll()`이고, 첫 일치 규칙이 이긴다. 레이트리밋 규칙은 `application.yml`의 패턴 문자열을 `PathPatternRequestMatcher`로 해석하며 **버킷 키는 `(규칙 id, 원격 IP)`** 다(`TokenBucketRateLimiter:85`) — 사이트 키는 버킷 키에 들어가지 않는다.
- 공개 템플릿의 내부 링크는 모두 `@{/notices…}` 식으로 하드코딩돼 있다(`templates/public/**` 약 40곳). 공개 `static/js`(`faq.js`·`home-popup.js`)는 내부 경로를 만들지 않는다. 본문 HTML은 `http/https/mailto` 링크와 `/content-images/{id}` 이미지만 허용하므로 본문에 내부 경로가 없다.
- `PublicWebExceptionAdvice`는 `basePackages="com.cms.publicweb"`이고 500 페이지 템플릿 `public/notice/error.html`과 공용 `error/404.html`이 `@{/notices}` 링크를 가진다(사이트를 모르는 상태에서 렌더링될 수 있다).
- 정적 리소스는 `static/{css,js,img,vendor}`뿐(최상위 파일 없음). 최상위 단일 세그먼트 MVC 매핑은 `/error`와 관리자 쪽 `/admin…`뿐이다.
- 테스트 파급: 옛 경로 문자열을 가진 테스트 파일 약 40개(공개 통합·스트리밍·레이트리밋·`SecurityConfigTest` 스텁 컨트롤러·`DefaultDenyErrorDispatchIntegrationTest`·`CustomErrorController*Test`·템플릿 컨벤션 시험 등). 관리자 템플릿·JS에는 공개 경로 하드코딩이 없고 Swagger 예시 문구(`BannerCreateRequest` "/boards/2")만 있다.
- 마이그레이션 최신은 V52. 기존 시드 `main`은 `id=2`, `isSeed()`가 삭제·비활성화를 막는다.

## P2. 핵심 쟁점과 결정

### P2-1. 사이트 해석(존재·활성 판정)을 어디서 하는가
선택지: (a) 각 컨트롤러가 경로 변수를 받아 서비스 호출 (b) 인자 리졸버 (c) **`HandlerInterceptor` + 표지 어노테이션**.
→ **(c)**. 사이트 범위 컨트롤러에 표지 `@SiteScopedPublic`을 달고, 인터셉터가 `HandlerMethod`의 빈 클래스에 표지가 있을 때만 경로 변수 `siteKey`를 해석한다. 해석 순서: ① 키 형식(`SiteKey` 형식 정규식 — 대문자·`_`·길이 위반이 여기서 탈락) ② 예약어 ③ DB 조회(`type=PUBLIC ∧ use_yn=1`). 어느 하나라도 실패하면 `response.sendError(404)` → 기존 `CustomErrorController`의 공용 404 화면. 성공하면 요청 속성에 `PublicSite(id, key, name)`를 싣는다.
- 왜 (c): 새 라우트를 추가해도 사이트 검증을 빠뜨릴 수 없고(표지가 클래스 단위, 컨벤션 시험이 `{siteKey` 매핑 ↔ 표지의 일치를 강제), 경로 패턴 기반 인터셉터는 **`/error` 재디스패치와 정적 리소스까지 가로채** 404 루프를 만들 수 있어 기각했다. (a)는 컨트롤러 6곳 복제, (b)는 예외를 던져야 해 기존 "공개 컨트롤러는 예외를 던지지 않는다" 관례와 어긋난다.
- 왜 `sendError`: 첨부 다운로드가 이미 쓰는 방식이고 404 화면이 한 곳이다. 모든 불가 사유(형식 위반·예약어·없음·비활성·관리자 타입)가 **같은 상태·같은 화면**이다.
- 비용: 요청당 인덱스 조회 1회(`uk_site_site_key`), 캐시 없음 — 레이트리밋이 모든 사이트 범위 경로에 걸려 있어 상한이 있고, 캐시는 무효화 규약(사이트 비활성화 즉시 반영)을 새로 만들어야 해 과설계다.

### P2-2. 경로 문법과 MVC 매핑
- 사이트 범위 경로: `/{siteKey}`(사이트 홈), `/{siteKey}/notices[/**]`, `/{siteKey}/boards/**`, `/{siteKey}/pages/**`, `/{siteKey}/faq`, `/{siteKey}/inquiry`. 경로 변수의 정규식은 **"점이 없는 비어 있지 않은 세그먼트"(`{siteKey:[^.]+}`)** 로만 제한한다(P-v2 — 키 형식 정규식을 걸면 `/MAIN`·`/a_b` 같은 형식 위반이 MVC·보안 계층에서 먼저 탈락해 302/403이 되어 "형식 위반도 같은 404" 계약이 깨진다). 형식·예약어·존재 판정은 전부 인터셉터가 맡는다. 점이 있는 `favicon.ico`·`index.html` 같은 경로는 이 매핑에 걸리지 않아 정적 리소스 처리를 가리지 않는다. 상수는 `PublicSiteRoutes`(publicweb/support)에 두고 매핑·`SecurityConfig`·시험이 공유한다.
- 이미지 3종(`/banners/{id}/image`·`/popups/{id}/image`·`/content-images/{id}`)은 **사이트 무관 경로로 그대로 둔다**(ID로 식별되고 노출 재검증이 서비스에 있어 사이트 소속이 필요 없다 — 사용자 결정 "본문 속 `/content-images` 사이트 무관"을 배너·팝업 이미지로 확장). 정적 `/css /js /img /vendor`도 그대로. 이들은 모두 **예약어**라 사이트 키와 충돌하지 않는다(PR ①의 자동 검사가 이미 보장).
- 같은 URL에 두 핸들러가 겹치는지(`/content-images/faq` 등 예약어를 사이트 키 자리에 넣은 경우)는 시험으로 고정한다 — 어떤 예약어 × 어떤 경로 형태도 **500이 아니라 404/302/403** 이어야 한다(MVC "Ambiguous handler" 500 방지).

### P2-3. 인가 정책 변경 — `SecurityConfig` (별도 고지 대상)
**제거**: `/`를 제외한 옛 공개 permit 규칙 5묶음(`/notices`·`/boards`·`/pages`·`/faq`·`/inquiry`)과 그 `denyAll` 짝.
**추가**(모두 기존 `/admin/**`·swagger·actuator·정적·이미지 규칙 **뒤**, `anyRequest().denyAll()` **앞**; 키 자리는 `PublicSiteRoutes`의 `{siteKey:[^.]+}`):

| 경로 | 메서드 | 결과 |
|---|---|---|
| `/{siteKey}` | GET·HEAD 공개, 나머지 `denyAll` | 사이트 홈 |
| `/{siteKey}/notices`, `/{siteKey}/notices/**` | GET·HEAD 공개, 나머지 `denyAll` | 공지 목록·상세·첨부 |
| `/{siteKey}/boards`, `/{siteKey}/boards/**` | GET·HEAD 공개, 나머지 `denyAll` | 게시판 |
| `/{siteKey}/pages`, `/{siteKey}/pages/**` | GET·HEAD 공개, 나머지 `denyAll` | 정적 페이지 |
| `/{siteKey}/faq` (정확) | GET·HEAD 공개, `/{siteKey}/faq`·`/{siteKey}/faq/**`의 나머지 `denyAll` | FAQ |
| `/{siteKey}/inquiry` (정확) | **GET·HEAD·POST 공개**, 나머지 `denyAll` | 문의 폼(두 번째 무인증 POST가 **경로만 이동**, 새 쓰기 경로 아님) |
| 옛 접두어 없는 `/notices`, `/boards`, `/pages`, `/faq`, `/inquiry` 와 각 `/**` | GET·HEAD 공개(핸들러 없음 또는 사이트 홈 매핑에 걸려 인터셉터 404), 나머지 `denyAll` | **종료(404)** — 기본 거부로 두면 비인증 302·인증 403이 되어 사용자 결정("404")과 어긋난다 |

`/error`는 직접 요청이 키 정규식에 걸리므로 **사이트 규칙보다 앞에서 `denyAll`을 명시**한다(ERROR 디스패치는 맨 앞 `dispatcherTypeMatchers`가 그대로 허용). `/`는 GET·HEAD 공개를 유지(302 핸들러).
- **새로 공개되는 표면**: 점이 없는 **임의의 단일 세그먼트 `GET /xxx`** 와 `GET /xxx/{notices|boards|pages|faq|inquiry}…`. 전부 인터셉터가 같은 404로 끝낸다(핸들러 본문 도달 전 DB 조회 1회 이하). 이전에는 비인증 302였다. 승인 이력 표(`com.cms.config/CLAUDE.md`)를 이에 맞게 고친다.
- 선택지: (B) 사이트 키 집합을 `AuthorizationManager`에서 DB로 판정해 존재하는 키에만 permit. 기각 — 보안 계층이 DB를 조회하게 되고, 존재하지 않는 키가 **404가 아니라 302/403**이 되어 사용자 결정과 어긋난다. 와일드 permit의 안전성은 "사이트 범위 경로의 모든 핸들러가 인터셉터를 지난다"는 구조적 보장(P2-1)에 의존하며 컨벤션 시험이 이를 강제한다.
- 예약어 안전성 시험: `SiteKey.RESERVED`의 **모든 단어 × {`/w`, `/w/notices`, `/w/faq`, `/w/inquiry`(GET·POST)}** 가 공개 페이지(200)가 되지 않는다. 결과는 단어별로 기존 규칙이 정한다 — `admin`·`actuator`·`swagger-ui`·`v3`·`error`는 기존 게이트(비인증 302 등)가 먼저 이기며 **이 경우는 "같은 404"가 아니다(예외 명시)**, 나머지 예약어는 인터셉터 404. 이 구분을 시험 기대값 표로 고정한다.
- **구조적 보장의 범위(P-v2)**: 안전성은 "새 permit 영역에 걸리는 모든 핸들러가 인터셉터를 지난다"에 의존하므로, 컨트롤러 시험은 `publicweb`만이 아니라 **등록된 전체 `RequestMappingHandlerMapping`** 을 대상으로 한다. 단, "패턴이 겹치면 표지 필수"로 정의하면 규칙 순서(앞선 `/admin/**` 게이트·`/error` 거부)를 무시해 정상 `/admin`·`/error` 핸들러까지 실패시키고(P-v3 지적), 변수마다 `x` 하나만 넣는 검사는 `/{section}/{view}` 같은 매핑과 공개 영역의 교집합(`/reports/notices`)을 놓친다. 그래서 **실제 공개 여부를 행동으로 정의**한다: 전체 보안 체인이 붙은 앱(`@SpringBootTest` + MockMvc)에서 모든 비-`@SiteScopedPublic` 핸들러 매핑에 대해 변수 자리를 탐침 값 집합 {`x`, `notices`, `boards`, `pages`, `faq`, `inquiry`, `main`}의 조합으로 채운 URL(변수 ≤3개라 최대 343개)을 **익명 GET·HEAD**로 호출하고, 응답이 \"로그인 302·401·403\"(보안이 막음)이 아닌 경우의 (매핑, 메서드) 목록이 **승인된 공개 비사이트 목록**(`/`, 이미지 3종, 로그인·비밀번호 재설정 페이지, `/actuator/health` 등 — 주석으로 근거를 단 상수)과 정확히 같아야 한다. 다른 패키지의 `GET /reports/notices` 같은 매핑이 공개 영역에 들어오면 목록 밖 공개로 실패한다. 표지 컨트롤러가 실제로 인터셉터를 지나는지는 아래 실서버 시험이 행동으로 확인한다.

### P2-4. 레이트리밋 (인가 정책의 일부)
패턴만 접두어를 포함하도록 바꾼다. 규칙 id·용량·순서(더 엄격한 첨부 규칙이 먼저)는 그대로이고, `*`는 한 세그먼트 와일드카드다.
`/*/notices/*/attachments/*`, `/*/notices/**`, `/*/boards/*/posts/*/attachments/*`, `/*/boards/**`, `/*/pages/**`, `/*/faq`, `/*/inquiry`(POST 10/3600, GET·HEAD 120/60). **신규**: 사이트 홈 `public-site-home`(120/60). 패턴은 `/{siteKey:(?!(?:예약어 전체)$)[^.]+}` — **예약어를 부정 선행탐색으로 제외**한다(P-v2: 레이트리밋 필터는 인가보다 먼저 돌고 핸들러를 모르므로 제외하지 않으면 공개 홈 한도를 소진한 IP의 관리자가 `/admin` 대시보드에서 429를 받는다). yml과 `SiteKey.RESERVED`의 드리프트는 **행동 시험**으로 막는다: 모든 예약어 `/w`가 어떤 사이트 규칙에도 매칭되지 않고, 공개 홈 버킷을 소진한 뒤에도 `/admin` 등 비공개/예약 경로가 429가 아니다. `/*/…` 규칙들도 같은 시험으로 **등록된 전체 비사이트 핸들러의 예시 URL이 어떤 사이트 규칙에도 맞지 않음**을 단언한다. `/`(`public-home`)는 302 전용이 되지만 규칙을 유지한다. **버킷 키가 `(규칙, IP)` 라서 무작위 사이트 키를 돌려도 버킷이 늘지 않는다**(사이트 키 공간 공격 방어) — 시험으로 고정한다. `RateLimitFilter`가 첫 일치 규칙을 쓰므로 `/*/notices/*/attachments/*` → `/*/notices/**` 순서를 유지한다(컨벤션 시험 갱신).
- 공유 한도의 부작용(수용): 한 IP가 사이트 A에서 소비한 한도는 사이트 B에도 적용된다.

### P2-5. 기본 공개 사이트와 `/` 302
- 스키마: `V53__add_site_default.sql`(DDL) `site.default_yn bit(1) NOT NULL DEFAULT 0` + `V54__seed_default_site.sql`(DML) `main`(id=2)을 기본으로 지정(`WHERE NOT EXISTS (기본 사이트 있음)` 재실행 안전). DDL/DML 분리 규약(V50~V52와 같음). `NOT NULL DEFAULT 0`이라 롤백한 이전 앱의 INSERT도 호환된다.
- `/` → `302 Location: /{defaultKey}`(GET·HEAD, `RedirectView` 컨텍스트 상대). 기본 사이트 조회 = `type=PUBLIC ∧ default_yn=1 ∧ use_yn=1`. 없으면 404(fail-closed). 키는 형식이 검증된 DB 값이라 오픈 리다이렉트가 아니다. 302(영구 301 아님) — 기본 사이트가 바뀔 수 있다.
- 불변식: 기본 사이트는 정확히 1개, 비활성화·삭제 불가. 지금은 시드 보호(`isSeed()`)가 `main`을 막고 있으므로 **서비스 변경 없이** 성립하고, 시험이 "default 행 = 시드이며 PUBLIC·활성"을 고정한다. `Site`에 `defaultYn`(primitive, 빌더 기본 false)을 추가한다.
- **기본 사이트 변경(API·UI)을 이 PR에 포함한다 — 사용자 승인 시 선택(2026-10-13, P-v4)**. 설계:
  - API: 기존 `PATCH /admin/api/sites/{id}`에 선택 필드 `defaultSite`(Boolean, null=유지)를 추가한다. `true`만 허용하고 `false`는 400(고정 문구 — 기본 사이트는 다른 사이트를 지정해야만 바뀐다). 대상은 `PUBLIC ∧ use_yn=1`이어야 한다(아니면 400/409). 이미 기본이면 멱등 성공. 응답·목록에 `defaultSite` 포함.
  - **잠금(PR ①의 "다른 사이트 행 비잠금" 규약의 명시적 예외)**: 기본 사이트 변경은 옛 기본·새 기본 두 행을 바꾸므로 **모든 `site` 행을 `id` 오름차순으로 `FOR UPDATE`**(`READ_COMMITTED`) 잠근 뒤 판정·갱신한다(행 수가 적은 관리자 전용 저빈도 쓰기라 단순함을 택한다). 교착 분석: 다른 쓰기(메뉴 쓰기·사이트 수정/삭제)는 **사이트 행 하나를 잡은 뒤 메뉴 행**만 기다리고 기본 변경은 메뉴 행을 쥐지 않는다 → 순환 불가. 기본 변경끼리는 id 오름차순이라 교착 불가. 기본 플래그는 이 경로에서만 바뀌므로, 비활성화·삭제가 자기 사이트 행을 잠근 뒤 읽는 `defaultYn`은 항상 일관적이다(기본 변경은 그 행도 잠그므로 직렬화).
  - 불변식: 기본 사이트는 정확히 1개이며 **비활성화·삭제 불가**(시드 보호에 더해 `defaultYn` 검사 추가 — 시드 `main`은 기본이 아니게 된 뒤에도 기존 시드 보호로 계속 비활성화·삭제 불가이다, 변경 최소화). DB 수준 유일 제약은 두지 않고(생성 컬럼 유일 인덱스는 `ddl-auto: validate`와의 마찰이 커서 기각) 서비스 잠금 + 시험으로 고정한다. 읽는 쪽(`/` 302)은 `defaultYn=1` 행이 정확히 1개일 때만 리다이렉트하고 0개·2개 이상이면 오류 로그 + 404(fail-closed).
  - 감사: 기존 `SITE_UPDATE`(대상=사이트 ID)를 재사용한다 — 새 감사 유형 없음.
  - UI: 사이트 탭 목록에 "기본" 배지, 활성 공개·비기본 사이트 행에 [기본으로 지정] 버튼(확인 모달 없이 즉시 PATCH — 되돌릴 수 있는 설정이고 브라우저 대화상자를 쓰지 않는다). 성공하면 `content:sites-changed`. 새로고침 보호·`busy` 규칙은 사이트 저장과 같다.
  - 효과: `/`의 302 목적지가 즉시 바뀐다(캐시 없음, 302).
  - **구현 조건(라운드 4 리뷰)**: ① `updateSite()`는 `defaultSite=true`인 요청이면 **대상 행을 먼저 잠그지 말고** 처음부터 전체 행 잠금 분기를 탄다(대상부터 잠근 뒤 확장하면 id 순서가 깨져 교착 가능) ② 복합 요청 `{"defaultSite":true,"useYn":false}`는 **최종 상태 기준**으로 400 거부한다(변경 전 플래그만 검사하지 않는다).

### P2-6. 링크 생성 — 접두어를 모델로 주입
- `@ControllerAdvice(basePackages="com.cms.publicweb")` 모델 어드바이스가 요청 속성의 `PublicSite`에서 `sitePrefix`(`/main`)와 `site`(키·이름)를 모델에 싣는다. 템플릿은 `@{${sitePrefix} + '/notices'}` 형태로 쓴다(Thymeleaf 링크 표현식이 이 형태를 지원하는지 구현 첫 단계에서 확인하고, 안 되면 `|…|` 리터럴 치환).
- 홈(`public/home.html`)의 `@{/}`는 `@{${sitePrefix}}`. 푸터·페이지네이션·검색 폼 `th:action`·첨부 링크·문의 폼 `th:action`·PRG 리다이렉트(`/{key}/inquiry?sent`)를 모두 같은 방식으로.
- 오류 템플릿(`error/404.html`, `public/notice/error.html`)은 사이트를 모를 수 있으므로 링크를 **`@{/}`** (→ 302로 기본 사이트)로 바꾸고 문구를 "홈으로 돌아가기"로 맞춘다.
- **컨벤션 시험**(템플릿 텍스트 스캔): `templates/public/**`(정상 화면)에는 `@{/notices`·`@{/boards`·`@{/pages`·`@{/faq`·`@{/inquiry` **와 루트 `@{/}`** 하드코딩이 없다(P-v2: FAQ·문의 폼/완료·정적 페이지의 "홈으로"가 `@{/}`라 두 번째 사이트에서 기본 사이트로 새는 것을 막는다; 허용: `/css /js /img /vendor /banners /popups /content-images`). 루트 `@{/}`는 **오류 템플릿 두 개에서만** 허용. 텍스트 스캔과 별개로 **두 번째 사이트의 렌더링 결과**(`href`·`action`·PRG `Location`)가 전부 `/{key}` 접두어로 시작하는지 통합 시험이 검사한다.
- 알려진 한계(수용): 관리자가 저장해 둔 **배너·팝업·메뉴의 내부 링크 URL**(예: `/boards/2`)은 이전 경로라 404가 된다. 실배포 전이라는 전제(사용자 결정 4/6)와 같다. Swagger 예시 문구를 `/main/boards/2`로 고친다.

### P2-7. 인터셉터와 예외 어드바이스
`PublicWebExceptionAdvice`(500)는 사이트 해석 전에 던져진 예외에서도 렌더링될 수 있어 모델 속성에 의존하지 않는다(P2-6의 오류 템플릿 규칙). 인터셉터 `preHandle`의 DB 예외는 `@ControllerAdvice`의 예외 처리 대상이므로 기존 HTML 500 페이지로 간다 — 인터셉터 안에서 잡지 않는다(시험: 사이트 조회 실패 주입 시 500 HTML, JSON 아님).

## P3. 작업 단계 (안쪽 → 바깥)
1. V53·V54 + `Site.defaultYn` + 마이그레이션 시험
2. `PublicSiteService`(키로 활성 공개 사이트 조회·기본 사이트 조회), `PublicSite`, `@SiteScopedPublic`, `PublicSiteInterceptor`(+`WebMvcConfigurer` 등록), 모델 어드바이스
3. 컨트롤러 매핑 이전(사이트 범위 6개), **`HomeController`를 `RootRedirectController`(`/` 302, 표지 없음)와 `PublicSiteHomeController`(`/{siteKey}`, 표지 있음)로 분리**(클래스 단위 표지가 충돌하지 않게, P-v2), PRG 리다이렉트
4. `SecurityConfig`·`application.yml` 레이트리밋 패턴
5. 템플릿 링크 접두어화, 오류 템플릿
6. 시험(P4) → `./gradlew test`
7. 실기 검증(P5) → 문서(`com.cms.config`·`publicweb.*`·`admin.site` `CLAUDE.md`, 루트 지도, `docs/deployment.md` 롤백 주의, 이 계획서)

## P4. 테스트 계획
- **라우팅 통합**(`PublicSiteRoutingIntegrationTest`): 없는 키·비활성 사이트·`admin`(ADMIN 타입)·예약어(`notices` 등)·형식 위반(대문자·`a_b`·초과 길이)이 **상태 코드·본문이 서로 같은 404**(`/MAIN`·`/a_b`·`/MAIN/notices` 포함 — 보안 계층에서 302/403이 되지 않음. `admin` 자체는 기존 관리자 게이트가 이긴다는 예외를 별도 단언). 기본 사이트 `/` → 302 `Location: /main`(GET·HEAD, 쿼리 무시). 두 번째 공개 사이트 생성 후 `/news`·`/news/notices`·`/news/faq` 등이 같은 콘텐츠로 200, 비활성화하면 즉시 404, 다시 활성화하면 200. 옛 `/notices`·`/notices/1`·`/boards/1`·`/pages/x`·`/faq`·`/inquiry`(GET) 404, 옛 `POST /inquiry`는 거부.
- **실서버 시험**(`@SpringBootTest(RANDOM_PORT)`, P-v2 — MockMvc는 `sendError`의 ERROR 재디스패치를 실행하지 않아 "같은 404 화면"을 증명하지 못한다; `DefaultDenyErrorDispatchIntegrationTest` 선례): 없는·비활성·형식 위반 사이트의 **GET과 HEAD**(HEAD는 본문 없이 같은 상태), 공지·게시판 **첨부 HEAD**, `/error` 직접 요청, 거부된 사이트가 공개 서비스(`Public*Service`)에 **도달하지 않음**(서비스 호출 0회). 사이트 범위 컨트롤러는 동기 핸들러만 허용(`Callable`/`DeferredResult`/`StreamingResponseBody` 반환 금지 컨벤션) — ASYNC 재디스패치의 재검증 정책이 필요 없게 한다.
- **보안/레이트리밋 슬라이스**(`SecurityConfigTest` 갱신): 새 규칙 표 전 행(익명 GET·HEAD 허용, 비허용 메서드 거부, `/error` 직접 요청 거부, 예약어 × 경로 형태, 점 포함 경로 미허용), 스텁 컨트롤러 경로 이전. `RateLimitFilterTest`/`RateLimitResponseTest`/규칙별 통합 시험(첨부가 목록보다 엄격, 사이트 키를 바꿔도 같은 버킷, 신규 홈 규칙, POST 문의 한도).
- **컨벤션**: ① `{siteKey` 매핑을 가진 `publicweb` 컨트롤러 ↔ `@SiteScopedPublic` 일치 ② 표지 없는 `publicweb` 컨트롤러의 매핑은 예약어 단일 세그먼트(이미지 3종)와 `/`뿐 ③ 비-GET `publicweb` 매핑은 `POST /{siteKey}/inquiry` 하나(기존 `PublicInquiryTemplateConventionTest` 갱신) ④ 템플릿 하드코딩 금지(P2-6) ⑤ 레이트리밋 규칙 순서.
- **기존 시험 이전**: 공개 통합·스트리밍(`PublicAttachmentStreamingServerTest`·`PublicBoardAttachmentStreamingServerTest`)·FAQ·문의·정적 페이지·홈·`CustomErrorController*Test`·`DefaultDenyErrorDispatchIntegrationTest` 등 약 40개의 경로 문자열을 `/main` 접두어로 이전한다(시험 의도 불변, 의도를 바꾸는 단언은 개별 보고).
- **기존 시험 단언 변경(P-v2)**: `SiteKeyReservedConventionTest`는 `:35`·`:95`에서 `notices/boards/pages/faq/inquiry`를 **실제 매핑에서 발견**해야 통과하는데 이전 후 최상위 리터럴이 사라진다 → 두 단언으로 분리: ① 옛 최상위 이름이 `RESERVED`에 **여전히 포함**(옛 URL 404·사이트 키 충돌 방지) ② 매핑 발견은 "이미지 3종 리터럴 + `{siteKey` 변수 매핑의 존재와 그 두 번째 세그먼트 집합"으로 재정의. 이 시험이 공허 통과(vacuous pass)하지 않는지 변이 확인한다.
- **기본 사이트 변경(P-v4)**: 서비스 단위(대상 비공개/비활성/관리자 타입 거부, `false` 400, 멱등, 기본 비활성화·삭제 409, 기본 플래그 0개·2개 시 `/` 404), 컨트롤러(ADMIN 전용·MANAGER 403·익명 401), 통합 동시성(실제 락: 서로 다른 두 사이트로의 동시 변경 → 최종 기본 정확히 1개·교착 없음, 기본 변경 vs 옛 기본 비활성화, 기본 변경 vs 새 대상 비활성화, 기본 변경 vs 메뉴 쓰기 비교착), `/` 302 목적지가 변경 직후 바뀜, 브라우저(배지·버튼·잠금).
- **마이그레이션**: V53·V54 후 default 행이 정확히 `id=2` 하나, 재실행 안전, 기존 행 `default_yn=0`.
- **인터셉터 장애 격리**: 사이트 조회 실패 주입 → 500 HTML(JSON 아님), 민감 정보 비노출.

## P5. 실기 검증 계획
일회용 DB + 앱: `/` 302 → `/main` 렌더 → 모든 내부 링크 클릭 경로(공지·게시판·페이지·FAQ·문의 폼 제출 PRG) → 사이트 탭에서 두 번째 공개 사이트 생성 → `/{key}/…` 동일 화면·링크 접두어 확인 → 비활성화 404 → 옛 URL 404 → 첨부 다운로드·이미지(배너·팝업·본문) → 레이트리밋 429 화면. Playwright 스크린샷.

## P6. 리스크
- **R1 새 무인증 표면**: 키 정규식을 만족하는 모든 경로가 인터셉터 도달. 완화: 표지+컨벤션 시험, 예약어 전수 시험, 핸들러 도달 전 DB 조회 1회로 한정, 레이트리밋.
- **R2 모든 템플릿 링크 변경**: 한 곳이라도 옛 경로를 남기면 404 → 컨벤션 시험 + 실기 클릭 검증.
- **R3 롤백**: 이전 앱은 `/notices` 등 옛 경로를 되살리고 `/{key}/…`는 사라진다. 데이터 손상은 없다(V53은 가산 컬럼). 롤백 후 외부에서 `/main/…` 링크를 공유했다면 죽는다. **구·신 앱을 동시에 서비스(롤링·블루그린 겹침)하면 새 URL과 옛 URL의 성공 여부가 인스턴스마다 달라지므로 혼합 버전 운영을 금지**하고 전환·롤백 모두 일괄 교체로 한다. `docs/deployment.md`에 기록.
- **R4 저장된 내부 링크(배너·팝업·메뉴)**: 옛 경로를 가리키면 404 — 수용(실배포 전).
- **R5 요청당 DB 조회 1회 추가**: 수용(P2-1).

## P7. 승인 시 사용자에게 별도 확인할 것
1. **인가 정책 변경**: P2-3·P2-4 전체(새 무인증 경로 표, 옛 경로 5묶음 GET/HEAD 공개→404, 단일 세그먼트 임의 GET 공개 표면, 레이트리밋 패턴).
2. **스키마**: `site.default_yn`(V53·V54), 되돌리는 마이그레이션 없음.
3. ~~기본 사이트 변경 기능을 만들지 않음~~ → **사용자가 포함을 선택(P-v4)** — P2-5의 변경 API·잠금 예외·UI.
4. **배너·팝업 이미지를 사이트 무관 경로로 유지**(P2-2).

## P8. 개정 이력 (PR ②)
- P-v1 (2026-10-13): 최초 작성.
- P-v2 (2026-10-13, 라운드 1 — codex `gpt-6.1-sol` no-ship, 6건 + 롤백 문서 1건 전부 수용) 변경:
  - P2-2·P2-3(지적 1): 키 자리 정규식을 형식 정규식이 아니라 `[^.]+`로 넓혀 형식 위반(`/MAIN`·`/a_b`)도 인터셉터의 같은 404로 끝낸다. `admin` 등 기존 게이트가 이기는 예약어는 "같은 404"의 예외로 명시. 근거: 정규식이 보안·MVC에서 먼저 탈락시켜 302/403이 됨.
  - P2-4(지적 2): `public-site-home` 패턴에서 예약어를 부정 선행탐색으로 제외 + 행동 시험. 근거: 필터가 인가·핸들러 선택보다 먼저 돌아 `/admin`을 제한하는 실제 회귀.
  - P2-3·P3(지적 3): 컨벤션 시험을 전체 `RequestMappingHandlerMapping`으로 확대, `HomeController` 분리.
  - P2-6(지적 4): 정상 화면의 루트 `@{/}`도 금지(오류 템플릿만 예외) + 두 번째 사이트 렌더링 결과 검사.
  - P4(지적 5): `SiteKeyReservedConventionTest` 단언 분리·변이 확인.
  - P4(지적 6): 실서버 시험(GET/HEAD·첨부 HEAD·`/error`·서비스 미도달) 추가, 사이트 범위 컨트롤러 동기 한정.
  - P6 R3: 구·신 앱 혼합 운영 금지.
- P-v3 (2026-10-13, 라운드 2 — codex `gpt-6.1-sol` no-ship, 2건 전부 수용) 변경:
  - P2-2·P2-3·P2-4(지적 1): 문자 클래스 `[^./]+`는 Spring 7.0.9 `PathPattern`이 `PatternParseException`으로 거부(이스케이프 안 된 `/`)해 기동이 막힌다 → `[^.]+`로 교체(단일 세그먼트 경계는 PathPattern이 처리, 부정 선행탐색은 파싱·매칭 확인됨). 근거: 리뷰어가 저장소 JAR로 직접 실행.
  - P2-3(지적 2): \"패턴 겹침이면 표지 필수\" 컨벤션 정의를 **익명 GET·HEAD 실제 응답 기반 공개 인벤토리 = 승인 목록** 시험으로 교체, 변수 매핑은 탐침 값 집합 조합으로 교집합 검사. 근거: 규칙 순서를 무시하면 `/admin`·`/error`가 거짓 실패하고 `/{section}/{view}`를 놓친다.
  - 라운드 3: codex ship (새 차단 사유 없음, 소스 대조 기준 설계 승인).
- P-v4 (2026-10-13, 사용자 승인 시 범위 추가): **기본 사이트 변경 API·UI 포함**(P2-5). 모든 site 행 id 오름차순 `FOR UPDATE` 잠금(PR ① "다른 사이트 행 비잠금" 규약의 명시적 예외), 기본 비활성화·삭제 금지, 기본 플래그 0/2개 시 fail-closed. 추가분만 라운드 4에서 재리뷰 — **codex ship**(구현 조건 2건을 P2-5에 명시). 사용자 승인 완료, 구현 착수.
  - 확인(변경 없음): Thymeleaf `@{${sitePrefix} + '/notices'}` 파싱 OK, 이미지 경로와 사이트 경로의 모호성 없음, ERROR 허용 순서·첨부 HEAD 새 차단 사유 없음.

---

## 구현·검증 결과 — PR ② (2026-10-13)

### Context
사이트 설정 메뉴 중 **PR ②(라우팅·공개 경로 이전)** 를 계획 P-v4(적대적 리뷰 4라운드 ship, 사용자 승인 시 "기본 사이트 변경 기능 포함" 선택)대로 구현했다. 공개 화면 경로가 `/{siteKey}/…` 아래로 이동했고, `/`는 기본 공개 사이트로 302, 접두어 없는 옛 URL은 404로 종료된다. **인가 정책(`SecurityConfig`·레이트리밋)이 바뀌었다**(사용자 별도 고지·승인). PR ③~⑤는 미착수.

### 핵심 확정 사항 (계획 P-v4에서 변경 없음)
- 사이트 해석: `@SiteScopedPublic` 표지 컨트롤러 앞의 `PublicSiteInterceptor`(형식 → 예약어 → DB), 실패는 전부 같은 `sendError(404)`. 요청당 DB 조회 1회, 캐시 없음.
- 경로 문법: 키 자리 `{siteKey:[^.]+}`(`PublicSiteRoutes`). 형식·예약어·존재 판정은 인터셉터가 전담.
- 보안: 옛 permit 5묶음 제거 → 사이트 범위 규칙 추가 + 옛 경로 GET·HEAD 열어 404 종료 + `/error` 명시 거부. 레이트리밋은 `/*/…` 패턴 + 신규 `public-site-home`(예약어 부정 선행탐색).
- 기본 사이트: `site.default_yn`(V53) + 시드 `main` 지정(V54), `/` 302(0개·복수·비활성이면 404), 변경은 `PATCH defaultSite=true` + 모든 site 행 id 오름차순 `FOR UPDATE`(복합 요청은 최종 상태 기준 400).
- 링크: 모델 `sitePrefix`, 템플릿 `@{|${sitePrefix}/…|}`, 오류 템플릿만 `@{/}`. 이미지 3종·정적 리소스는 사이트 무관 경로 유지.

### 구현 파일
- 마이그레이션: `V53__add_site_default.sql`·`V54__seed_default_site.sql`
- 신규 `com.cms.publicweb.support`: `PublicSiteRoutes`·`PublicSite`·`SiteScopedPublic`·`PublicSiteService`·`PublicSiteInterceptor`·`PublicWebMvcConfig`·`PublicSiteModelAdvice`·`CLAUDE.md`, `home/RootRedirectController`
- 변경: `Site`·`SiteRepository`·`SiteService`·`SiteUpdateRequest`·`SiteResponse`, 공개 컨트롤러 6개(매핑·표지, 문의 PRG), `SecurityConfig`, `application.yml`, 공개·오류 템플릿 16개, `site-tab.html`(기본 열·버튼)
- 문서: 루트·`com.cms.config`·`com.cms.publicweb.*`·`com.cms.admin.site` `CLAUDE.md`, `docs/deployment.md`(공개 경로 이전·혼합 버전 금지)·`docs/migration-guide.md`(V53~V54)·`docs/troubleshooting.md`(3건)

### 검증 결과
- **시험**: 전체 `./gradlew test` — 2,432건 중 9건이 이전 경로(`get("/")`, 단일 세그먼트 미분류 경로)를 쓰던 기존 시험의 의도 변경으로 실패 → 갱신 후 재통과(배너·팝업 통합, 권한 매트릭스). 신규: `SiteDefaultMigrationTest`·`SiteDefaultApiIntegrationTest`·`SiteDefaultConcurrencyIntegrationTest`·`SiteDefaultTabBrowserTest`·`SiteServiceDefaultTest`·`PublicSiteRoutingIntegrationTest`·`PublicSiteRoutingServerTest`(실서버: 같은 404 화면·HEAD 본문 없음·서비스 미도달·`/error`)·`PublicExposureInventoryTest`·`PublicSiteTemplateConventionTest`·`SitePublicRateLimitConventionTest`, 갱신 `SecurityConfigTest`(+7)·`SiteKeyReservedConventionTest`(단언 분리)·`RateLimit*`·공개 시험 약 40개의 경로 이전.
- **변이 실험**(3건): ① 다른 패키지에 `GET /reports/notices` 추가 → 공개 인벤토리 시험 실패 ② 레이트리밋 예약어 선행탐색 제거 → 3건 실패(`/admin` 429 회귀 재현) ③ 기본 변경의 전체 행 잠금을 일반 조회로 교체 → 동시성 시험 실패. 모두 원복.
- **실기(Playwright + 일회용 MariaDB 3317 + 앱 8081)**: `/` 302 → `/main` → 사이트 탭에서 `news` 생성 → [기본으로 지정] → `/`가 `/news`로 이동, "기본" 열 이동·버튼 소멸 → `/news` 홈·FAQ·공지·문의·페이지·게시판의 모든 `href`/`action`이 `/news` 접두어(공지·게시글·푸터 데이터를 넣고 확인) → 옛 `/notices/2` 404 화면의 "홈으로 돌아가기"가 `/`를 거쳐 `/news`로 → main 재지정·news 비활성화 후 `/news` 404 → 원복 → `/error`·`/robots.txt`·`/nosuch/unknown` 302 로그인 → 임의 키 140회 연속 요청 시 429 화면. 스크린샷 4장(`adversarial-review/screenshots/site-routing/`).

### 이슈
- **계획 리뷰가 잡은 설계 결함 4건**(구현 전 수정): 형식 정규식이 형식 위반을 302/403으로 바꿈, 홈 레이트리밋이 `/admin`을 제한, `[^./]+`가 PathPattern 파싱 실패로 기동 불가, 컨벤션 시험이 규칙 순서를 무시. `docs/troubleshooting.md`에 3건 기록.
- **구현 중 발견**: `WebMvcConfigurer` 빈이 `@WebMvcTest`에 포함되어 서비스 의존이 있으면 슬라이스 100여 건이 깨짐 → `ObjectProvider` 지연 조회.
- **수용한 한계**: 관리자가 저장한 배너·팝업·메뉴의 옛 내부 링크는 404(실배포 전 전제), 공유 레이트리밋 버킷(한 IP의 사이트 간 한도 공유), 모든 공개 사이트가 같은 콘텐츠를 보여 줌(PR ③ 전), 구·신 앱 혼합 운영 금지.
- **브라우저로 못 본 것**: 기본 지정 중 다른 탭의 구조 반영 충돌(새로고침 보호 참여)은 코드로만 확인 — PR ①의 `isSitePanelBusyOrDirty` 경로를 그대로 쓴다.

### 후속
- 코드 리뷰 게이트(`/code-review-loop`) → `/commitPR`. 로드맵 반영(`/updateRoadmap`)은 사이트 설정 메뉴 전체 완료 후 한 번에.
- PR ③(사이트별 콘텐츠: 새 `site_id` FK `RESTRICT` + `SiteService.hasContent` 확장), ④(사이트별 공지), ⑤(공개 메뉴 렌더링).

---

# PR ③ 상세 설계 — 사이트별 콘텐츠 (배너·팝업·FAQ 카테고리·정적 페이지/푸터)

- 작성: 2026-10-13 / 브랜치 `feat/site-content`(master 기반, PR ② #144 머지 후) / 상태: **P3-v6 (적대적 리뷰 5라운드 반영)**
- 확정 결정(재질문 금지): 메모리 `project-site-menu-decisions` — "사이트별로 다른 것: 메뉴, 시작 화면(배너·팝업·최신 글), 공지, FAQ·푸터(정적 페이지 링크). 공통 콘텐츠는 `site_id` NULL". 사용자 선택(2026-10-13): 범위 = **배너·팝업·FAQ 카테고리·푸터 전부**, 기준 브랜치 = PR ② 머지 후 master.

## Q1. Context와 범위

PR ②로 공개 화면이 `/{siteKey}/…` 아래로 이동했지만 **모든 사이트가 같은 콘텐츠**를 보여 준다. PR ③은 콘텐츠 행에 소속 사이트(`site_id`, **NULL = 모든 공개 사이트 공통**)를 붙이고 공개 조회가 `site_id IS NULL OR site_id = 현재 사이트`로 거르게 한다. 인가 정책(`SecurityConfig`·레이트리밋)·새 공개 경로·`AdminFeature` 변경은 **없다**.

**범위 안**: `banner`·`popup`·`faq_category`·`site_page`에 `site_id` 컬럼(V55) / 관리 API·화면에 "표시 대상" 선택 / 공개 홈(배너·팝업·푸터)·FAQ·정적 페이지 조회의 사이트 필터 / `SiteService.hasContent` 확장 / 롤백 주의 문서.
**범위 밖(PR ④⑤)**: 공지·새 글(게시판 소속), 공개 메뉴 렌더링. 미디어 라이브러리 이미지(`content_image`)는 사이트 무관 공용 자원으로 둔다. 문의(`inquiry`)는 사이트 소속을 두지 않는다(PR ② 그대로).

### 정찰에서 확인한 사실 (코드를 읽은 것)
1. **쓰기 직렬화는 기능별 단일 가드**다 — 배너 `banner_lock`(생성·삭제·순서), FAQ `faq_lock`(모든 쓰기 + 구조 `version`), 팝업은 행 락 + 소프트 상한, 정적 페이지는 페이지 행 락 + `version`. 상한은 전역 값(배너 10·팝업 30·FAQ 카테고리 30·FAQ 200)이고 `count()`로 센다.
2. **순서는 전역 `ord`**: 배너 `ord`(순서 저장은 전체 집합 일치 필수, 아니면 409), FAQ 카테고리 `ord` 0..n-1 항상 연속 + FAQ는 카테고리 안 0..n-1, `PUT /order`가 전체 트리를 `version`과 함께 교체. 공개 쿼리는 `(ord, id)` 순.
3. **공개 조회 불변식은 쿼리에 고정**(`findDisplayable*`·`findPublicRows`·`findPublicCurrent/Revision/Header`·`findFooterLinks`)이고 서비스 파라미터에 사이트 개념이 없다. 사이트는 `PublicSiteInterceptor`가 요청 속성(`PublicSiteRoutes.current`)으로 싣고 `PublicSiteModelAdvice`가 `sitePrefix`를 모델에 싣는다 — **서비스에는 아직 사이트가 전달되지 않는다**.
4. **공개 이미지 경로는 사이트 무관**이다 — `/banners/{id}/image`·`/popups/{id}/image`·`/content-images/{id}`는 `@SiteScopedPublic`이 아니고 `findDisplayableById`로 노출 여부·기간만 재검증한다(PR ② 결정: 이미지 3종은 사이트 무관 경로 유지).
5. **정적 페이지 슬러그는 전역 유일**(`uk_site_page_slug`)이고 공개 `/{siteKey}/pages/{slug}`가 슬러그로만 찾는다. 푸터는 `footer_yn`인 노출 페이지의 링크 목록이다(`findFooterLinks`).
6. **관리 화면**: 배너 621·팝업 711·FAQ 810·정적 페이지 탭 503줄 템플릿(인라인 JS). 관리자 사이트 목록 API `/admin/api/sites`는 **ADMIN 전용**이라 `BANNER`·`POPUP`·`FAQ`·`SITE_PAGE`를 위임받은 **MANAGER는 호출할 수 없다**. 페이지 컨트롤러는 `@AdminPage`(사이드바 모델 주입) 한 줄짜리다.
7. **삭제 가드**: `SiteService.deleteSite`는 사이트 행 `FOR UPDATE` 뒤 `hasContent`(메뉴 존재)로 409. 공유 잠금 선례는 게시글 생성이 게시판 행을 `FOR SHARE`로 읽는 `PostService`(`findByIdAndDeletedFalseForShare`)다. 사이트 쓰기는 READ_COMMITTED다.
8. **시험 파급**: 배너 18·팝업 15·FAQ 11·정적 페이지 11개 시험 파일이 해당 도메인을 건드리고, 공개 서비스 시험(홈 6·FAQ 5·페이지 5·팝업 5·배너 4)이 서비스·리포지토리 시그니처에 직접 의존한다. 엔티티 `@Builder`에 필드가 늘어도 nullable이면 기존 빌더 호출은 컴파일된다(12개 파일).
9. **`MediaService`**가 `BannerRepository`·`PopupRepository`를 사용처 요약에 쓴다(`findAllById`) — 시그니처 변경 영향 없음(확인 필요 항목으로 구현 때 컴파일로 검증).

## Q2. 핵심 쟁점과 결정

### S1. 스키마 — 네 테이블에 nullable `site_id` + FK `RESTRICT` (V55)
- 선택지: (A) 각 테이블에 `site_id` NULL 허용 컬럼(NULL = 공통) / (B) 매핑 테이블 `content_site(owner_type, owner_id, site_id)`로 다대다(여러 사이트 선택) / (C) 모든 행에 `site_id NOT NULL` + "공통" 시드 사이트.
- **결정 (A)**. 확정 결정이 "NULL = 공통"이고 필요한 표현은 "공통 or 사이트 하나"뿐이다. (B)는 다대다 UI·삭제 가드·정리 로직이 따라붙는 과설계이고 "여러 사이트에 같은 것"은 공통으로 충분히 표현된다. (C)는 FK가 가짜 사이트를 가리켜 `SiteService`/인터셉터의 "공개 사이트만 존재" 가정을 흐린다.
- 형태: `site_id bigint(20) NULL DEFAULT NULL`, `FOREIGN KEY (site_id) REFERENCES site(id) ON DELETE RESTRICT`(FK가 인덱스를 만든다). **DEFAULT NULL = 공통**이라 기존 행은 그대로 공통, 롤백한 이전 앱이 `site_id` 없이 INSERT해도 공통으로 들어간다. 추가로 **낡은 화면 검사용 `version int NOT NULL DEFAULT 1`을 `banner`·`faq_category`에 둔다**(S6b — 팝업은 이미 수정마다 오르는 `revision`을 쓴다, 정적 페이지는 이미 `version`이 있다). 멱등: `ADD COLUMN IF NOT EXISTS` + `ADD CONSTRAINT … FOREIGN KEY IF NOT EXISTS`(V52 선례), DDL 단독 파일. 네 테이블 ALTER는 문마다 암묵 커밋이라 실패 복구 절차를 `docs/migration-guide.md`에 적는다.
- **FAQ는 카테고리에만** 둔다 — FAQ 항목은 카테고리를 따라간다(항목 단위로 따로 두면 공통 카테고리 안에 사이트 전용 항목이 섞여 카테고리 숨김 규칙과 순서 모델이 복잡해진다). **정적 페이지 개정 테이블은 변경 없음**(페이지 행의 `site_id`만).
- `site_id`는 **공개 사이트(`PUBLIC`)만 가리킬 수 있다** — 관리자 사이트(id=1) 지정은 서비스가 400으로 거부한다(DB 제약 없음, 서비스 검증).

### S2. 대상 지정 검증과 잠금 — 사이트 행 `FOR SHARE`
- 쓰기가 `siteId != null`이면 **기능 가드/대상 행을 잠근 뒤 대상 사이트 행을 `FOR SHARE`(PESSIMISTIC_READ)로 읽어** 존재·`PUBLIC` 타입을 확인한다(없음/타입 불일치 400 — 존재하지 않는 참조는 본문 오류). 비활성 공개 사이트는 지정할 수 있다(비활성화 중에도 내용을 준비해 둘 수 있게; 공개는 인터셉터가 막는다).
- 이유: `SiteService.deleteSite`는 사이트 행 `FOR UPDATE` 뒤 `hasContent`를 본다. 콘텐츠 생성이 사이트 행에 `FOR SHARE`를 쥐고 INSERT하면 삭제가 그 커밋을 기다린 뒤 `hasContent`가 새 행을 본다 → 고아가 안 생긴다(FK `RESTRICT`가 최종 방어선이지만 사용자에게 500이 아니라 409를 주려면 앱 검사가 필요). 게시글 생성이 게시판 행을 `FOR SHARE`로 읽는 규약(`PostService`)과 같다.
- **잠금 순서**: 기능 가드 → (소유자 행) → 사이트 행 `FOR SHARE` → (이미지 행·참조 등 기존 순서). 사이트 쓰기 서비스는 기능 가드를 잡지 않는다(사이트 행 → 메뉴 행) → 순환 없음. 격리 수준: **삭제가 `hasContent`를 보는 시점 가시성이 READ_COMMITTED(사이트 삭제)에서 보장**되므로 콘텐츠 쪽 격리 수준은 바꾸지 않는다.
- 구현: `SiteRepository.findByIdForShare`(PESSIMISTIC_READ) 1개 + 얇은 컴포넌트 `ContentSiteScope.requireAssignable(Long siteId)`(null이면 아무것도 안 함) — 네 서비스가 공유(규칙 한 벌).
- **사이트 삭제 가드**: `SiteService.hasContent`를 `menu ∨ banner ∨ popup ∨ faq_category ∨ site_page` 존재로 확장. 각 리포지토리에 `existsBySiteId`. 409 문구는 "메뉴·콘텐츠가 있는 사이트는 삭제할 수 없습니다".

### S3. 공개 필터 — 서비스에 `siteId`를 전달, 쿼리에 고정
- 선택지: (A) 쿼리 조건 `(x.siteId is null or x.siteId = :siteId)`를 각 `findDisplayable*`·`findPublic*`·`findFooterLinks`에 추가하고 서비스 메서드에 `Long siteId`를 필수 파라미터로 / (B) 서비스가 전체를 읽은 뒤 메모리에서 거름 / (C) 요청 스코프 빈으로 사이트를 암묵 주입.
- **결정 (A)**. 공개 불변식이 "쿼리에 고정"이라는 이 프로젝트의 규약을 따른다(호출자가 필터를 빠뜨릴 수 없다). `siteId`는 컨트롤러가 `PublicSiteRoutes.current(request).id()`로 얻어 넘긴다(`@SiteScopedPublic` 컨트롤러는 인터셉터가 항상 채우므로 null 아님). 서비스는 null을 받으면 `IllegalArgumentException`으로 거부해 "필터 없음" 호출을 막는다.
- 대상: 홈 배너(`findDisplayable`)·팝업(`findDisplayable`)·푸터(`findFooterLinks`)·FAQ(`findPublicRows`)·정적 페이지(`findPublicCurrent`·`findPublicRevision`·`findPublicHeader`). 다른 사이트 전용 페이지/개정/목록은 비노출·없음과 **같은 404**(존재 비노출).
- **이미지 경로**(`findDisplayableById`·`findDisplayableImageById`)는 사이트 무관을 유지하되 **소속 사이트가 비활성이면 404**를 추가한다: `and (x.siteId is null or exists (select 1 from Site s where s.id = x.siteId and s.useYn = true))`. 이유: 사이트를 끄면 그 사이트 전용 콘텐츠가 공개에서 사라져야 하는데 이미지 id URL만 계속 열리는 것을 막는다. 다른 사이트 접두어 아래에서의 id 직접 접근은 막지 않는다(id 경로는 사이트 맥락이 없고 노출 중인 공개 이미지라는 점은 이전과 같다 — **수용한 한계**).
- 정렬: 전역 `ord`/`id` 순을 그대로 쓴다. 공통·전용이 섞여도 관리자가 정한 전역 순서가 그대로 보인다.

### S4. 정적 페이지 슬러그는 전역 유일 유지
- 선택지: (A) 슬러그 전역 유일 유지 + 페이지가 보이는 사이트만 필터 / (B) `(site_id, slug)` 복합 유일 — 같은 슬러그를 사이트마다.
- **결정 (A)**. (B)는 공통 "terms"와 사이트 전용 "terms"의 우선순위(가림), 개정 URL 해석, 슬러그 유일성 검사(NULL은 MariaDB 유니크 인덱스에서 중복 허용)·동시 등록 경합을 새로 풀어야 한다. (A)는 기존 유니크 키·동시성 방어선을 그대로 쓴다. 비용: 사이트마다 같은 이름의 페이지를 만들 수 없다(`privacy-a`/`privacy-b`로 구분) — 수용하고 승인 시 확인 사항에 넣는다.
- `uk_site_page_slug`·`SiteSlug` 예약어·개정 모델 변경 없음. 수정 시 `version`은 대상 변경에도 오른다(`changeFlags`에 대상 포함, 본문 개정은 올리지 않음).

### S5. 순서·상한은 전역 유지 (과설계 경계)
- 사이트마다 가드·`ord`·상한을 따로 두면 `banner_lock`/`faq_lock`을 사이트별 행으로 쪼개고 순서 저장·구조 `version`·집합 일치 검사·`PUT /order` 계약을 전부 사이트 범위로 바꿔야 한다 — PR 하나로는 위험하고 확정 결정에도 없다.
- **결정**: 가드·`ord`·`version`·상한(배너 10·팝업 30·FAQ 카테고리 30·FAQ 200)은 **전체 합계 기준 그대로**. 공개 화면은 전체 순서의 부분 수열이라 순서가 일관된다. 배너 10개 상한이 "공개 메인이 활성 배너 전부를 매번 내려받는" 부담을 한 사이트 기준으로도 넘지 않게 지킨다(합계 ≥ 한 사이트의 노출 수). 사이트가 여러 개이면 합계 상한이 빡빡해지는 것이 비용이다 — **승인 시 확인 사항**.
- 순서 저장 화면은 전체 목록을 그대로 다룬다(필터 없음) — 행마다 "표시 대상" 배지를 보여 준다.

### S6. 관리 API 계약 — `siteScope` 문자열, 수정에서는 필수 (v2 개정)
- 요청 필드 `String siteScope`: 값은 **`"COMMON"`(모든 사이트 공통) 또는 양의 정수 문자열(사이트 ID)**. `@Pattern("COMMON|[1-9][0-9]{0,18}")`로 형식을 거르고 파싱은 공용 `SiteScope` 값 객체 하나가 한다. 정규식은 `Long` 범위(9223372036854775807)를 넘는 19자리 값을 통과시키므로 **`SiteScope`가 `NumberFormatException`/범위 초과를 `InvalidRequestException`(400)으로 바꾼다**(500 방지, `9223372036854775808`·`9999999999999999999` 시험). 응답 DTO에는 `siteId`(Long, null = 공통)를 싣는다.
- **등록**(배너 multipart·팝업·FAQ 카테고리·정적 페이지): 필드 선택, **누락 = `COMMON`**(새 행의 기본값이라 노출 범위가 "넓어지는" 변화가 없다).
- **수정**(배너·팝업 PUT, FAQ 카테고리 PUT, 정적 페이지 PUT): 필드 **필수**(`@NotNull`+`@Pattern`). 누락·빈 문자열·형식 위반은 **400**. 이유: PUT 전체 교체에서 누락이 `COMMON`으로 해석되면 A 전용 콘텐츠의 제목만 고친 호출이 모든 사이트 공개로 바뀐다 — 노출 범위 확대는 요청이 명시해야 한다(정적 페이지 `useYn`·`footerYn` 필수, 팝업 높이 빈 문자열 거부와 같은 방향). Jackson이 `Long`의 `""`를 `null`로 바꾸는 함정(팝업 CLAUDE.md)도 문자열 필드로 피한다.
- **등록용·수정용 요청 클래스를 분리**한다 — 배너(`BannerCreateRequest`/`BannerUpdateRequest`)·팝업·정적 페이지는 이미 분리돼 있고, **FAQ 카테고리는 POST·PUT이 같은 `FaqCategoryRequest`라서** 수정용 `FaqCategoryUpdateRequest`(필수 `siteScope`·`version`)를 새로 둔다. MVC 시험으로 같은 누락이 POST는 성공·PUT은 400임을 고정한다.
- 새 엔드포인트·새 URL이 없어 인가 영향이 없다. FAQ 항목(질문)은 카테고리를 따르므로 필드가 없다. 감사: 기존 액션 유지(새 액션 타입 없음).
- 기존 시험·화면 JS 중 PUT 본문에 `siteScope`가 없는 것은 400이 되므로 갱신 대상이다(시험 파급에 포함).

### S6b. 낡은 편집 화면의 공개 범위 확대 방지 — 수정에 `version` 필수 (v3 신설, 2라운드 P1 + 사용자 결정 "버전 컬럼 추가")
- 문제: `siteScope` 필수만으로는 공통 콘텐츠를 열어 둔 화면이 그 사이 다른 관리자가 A 전용으로 바꾼 값을 `COMMON`으로 되돌려 모든 사이트에 다시 공개할 수 있다(명시값이라 필수 검사를 통과). 정적 페이지만 `version` 검사가 있다.
- **결정**: 배너·팝업·FAQ 카테고리 수정에도 정적 페이지와 같은 낙관적 버전 검사를 둔다. 행 락(배너·팝업 `findByIdForUpdate`, FAQ 카테고리는 가드 행 뒤 대상 조회)을 잡은 **뒤** 요청의 버전과 현재 값이 다르면 **409**(문구는 정적 페이지 `STALE_VERSION`과 같은 계열).
  - 배너: 새 컬럼 `banner.version`(V55, 기본 1). `replaceMetadata`가 +1(제목·링크·기간·노출·**대상** 중 무엇이든). 순서 저장(`changeOrder`)은 올리지 않는다(순서만 바뀐 뒤의 편집 화면까지 무효화하지 않으려고 — 대상/노출 변경은 모두 `replaceMetadata`를 지난다).
  - 팝업: **기존 `revision`을 그대로 쓴다**(응답에 이미 있고 수정마다 락 아래에서 +1, 신규 컬럼 없음). 수정 요청에 `revision` 필수. 대상 변경도 `replace`를 지나 revision이 오르므로 "오늘 하루 보지 않기"도 함께 풀린다.
  - FAQ 카테고리: 새 컬럼 `faq_category.version`(V55, 기본 1). 카테고리 `replace`(이름·노출·대상)가 +1. `ord` 변경(순서 반영·삭제 후 재번호)은 올리지 않는다. 구조 `version`(`faq_lock`)과는 다른 값이다.
  - 응답 DTO(`BannerResponse`·`PopupResponse`(기존 revision)·`FaqCategoryResponse`·`FaqTreeResponse.Category`)에 버전을 싣고 화면 JS는 편집 폼에 보관했다가 PUT에 보낸다. 요청에서 누락은 400. **배너·FAQ 카테고리 `version`은 양수(`@Positive`, 시작값 1), 팝업 `revision`은 시작값이 0이므로 `@NotNull @PositiveOrZero`**(V39 기본값 0·`PopupService`가 `revision(0)`으로 생성) — 음수는 400.
- 이전에 "수용한 한계"로 둔 배너·팝업·FAQ 카테고리 단건 수정의 낡은 폼 덮어쓰기는 이 변경으로 **해소**된다(문서 `CLAUDE.md` 3곳의 한계 문구 갱신). FAQ **항목**(질문)은 아래 "FAQ 항목" 항목처럼 소속 카테고리가 사실상 대상이므로 소속 검사만 추가한다(질문·답변 덮어쓰기의 기존 수용 한계는 유지, 삭제는 범위 밖).
- **FAQ 항목(v4, 3라운드 P1)**: 항목의 `categoryId`가 표시 대상을 결정한다. 낡은 폼이 이전 `categoryId`를 그대로 보내면 다른 관리자가 사이트 전용 카테고리로 옮긴 항목이 공통 카테고리로 돌아가 재공개된다. 따라서 **FAQ 항목 수정 요청에 편집 시작 당시의 `baseCategoryId`를 필수로** 보내고, 가드 행 아래에서 현재 `categoryId`와 다르면 **409**로 거부한다(질문만 고쳐도 소속이 바뀌었으면 새로고침 요구). 이동 대상 카테고리의 표시 대상이 편집 중 바뀐 경우는 "이동은 그 카테고리의 **현재** 대상을 따른다"로 두고 목록의 배지로 보인다(수용 — 이동은 사용자가 대상 카테고리를 고른 행위이고, 대상 카테고리 자체의 재지정은 카테고리 `version`이 막는다).
- **화면의 편집 스냅샷 규칙(v4 → v5 교정)**: 버전을 `editing.version`처럼 목록 객체에서 읽으면 안 된다 — 배너 `loadList()`는 편집 중 항목을 최신 목록 객체로 교체하면서 폼 입력은 다시 채우지 않으므로, 폼은 옛 값인데 버전만 최신이 되어 검사를 통과한다. 규칙: 편집 폼을 **서버에서 읽어 채우는 순간** 별도 **검증 기준 스냅샷** `{id, version(revision), (FAQ 항목은 baseCategoryId)}`을 보관하고, PUT의 `version`/`revision`/`baseCategoryId`는 **이 스냅샷에서만** 보낸다. 반대로 사용자가 바꾸는 **요청 값**(`siteScope`, FAQ 항목의 이동할 `categoryId`, 제목 등)은 **현재 폼 입력에서 읽는다**(스냅샷에 넣지 않는다 — 넣으면 선택 박스를 바꿔도 옛 값이 전송돼 변경이 조용히 무시된다). 목록 갱신·순서 저장 409 처리는 스냅샷도 폼 입력도 바꾸지 않는다. 더 새 버전을 쓰려면 폼 전체를 다시 불러와(스냅샷·입력 동시 재설정) 입력도 최신이 되게 한다. 네 화면(배너·팝업·FAQ 카테고리/항목)에 공통 적용한다.
- **FAQ 상세·트리 불일치 방어(v5, 4라운드 P1)**: FAQ 편집 폼은 상세 응답으로 채우고 카테고리 선택지는 트리 응답으로 만든다. 두 응답의 조회 시점이 다르면(트리를 읽은 뒤 다른 관리자가 새 카테고리를 만들고 항목을 옮김) 상세의 `categoryId`가 선택지에 없어 선택이 옛 공통 카테고리로 남을 수 있고, 이때 `baseCategoryId`(최신 상세)는 현재 소속과 같아 검사를 통과한 채 `categoryId`(선택 박스 값)로 항목이 이동한다. 규칙: **상세의 `categoryId`를 선택 박스에 정확히 복원하지 못하면 저장을 막고(폼 잠금 + "화면이 낡았습니다") 트리를 다시 읽어 폼을 다시 채운다.** 사이트 대상 선택(`siteScope`) 박스의 "목록에 없음 옵션 보존"(S8)과 같은 계열의 원칙 — 복원 불가한 값은 조용히 다른 값으로 대체하지 않는다.
- 비용: V55에 컬럼 2개 추가, 수정 요청 계약 변경(배너·팝업·FAQ 카테고리 PUT에 `version`/`revision` 필수), 기존 PUT 시험·JS 갱신. 롤백: 이전 앱은 `version`을 올리지 않지만 읽지도 않으므로 무해(재배포 시 낡은 화면 검사만 약해짐).

### S7. 사이트 목록을 MANAGER에게 어떻게 주는가 — 서버 렌더링 모델
- 선택지: (A) 4개 페이지 컨트롤러가 모델 `scopeSites`(공개 사이트 `id/name/key/useYn`)를 서버 렌더링 / (B) 새 읽기 API `/admin/api/site-options` 추가 / (C) `/admin/api/sites`를 MANAGER에 개방.
- **결정 (A)**. (B)(C)는 `/admin/**` ADMIN 캐치올·URL 게이트 변경, 즉 **인가 정책 변경**이 필요하다. (A)는 정책 변경 없이 기존 `@AdminPage` 모델 주입 방식과 같은 서버 측 렌더링이다. 구현: 얇은 컴포넌트 `ContentScopeOptions`가 `SiteRepository`에서 `PUBLIC` 사이트를 읽고 네 `*PageController`가 `Model`에 싣는다. 정적 페이지 화면은 메뉴/사이트 탭과 한 셸이므로 같은 모델을 쓴다.
- 사이트 이름·키는 관리자가 입력한 값이라 템플릿은 `th:text`/JS `textContent`만 쓴다(HTML 삽입 금지).
- `ContentScopeOptions`는 공개 사이트 목록과 함께 **기본 공개 사이트의 키**(`default_yn`)도 싣는다(S8의 정적 페이지 링크용).
- 권한 모델: 배너/팝업/FAQ/정적 페이지 위임 권한은 **기능 단위라 모든 표시 대상(공통·모든 사이트)에 적용**된다. 사이트별 위임은 하지 않는다(확정 결정에 없음, `member_permission` 변경 없음) — **승인 시 확인 사항**.

### S8. 관리 화면 — 폼에 선택 박스, 목록에 배지 (최소 변경)
- 네 화면: 등록/수정 폼에 `<select name="siteId">`(첫 옵션 "공통(모든 사이트)", 이어서 공개 사이트들 — 비활성은 "(비활성)" 표시), 목록 행에 배지(공통이면 "공통", 전용이면 사이트 이름). FAQ는 카테고리 폼에만. 팝업은 종류 불변이지만 표시 대상은 수정 가능. 정적 페이지는 `version` 낡은 화면 검사를 그대로 쓴다.
- 필터·그룹핑·사이트별 탭은 만들지 않는다(과설계). 순서 DnD/▲▼는 전체 목록 그대로.
- 기존 폼 JS 상태 기계(`busy`·`syncFailed`·초안 잠금 등)에 새 필드가 끼어들 뿐 흐름은 바꾸지 않는다.
- **낡은 사이트 목록 방어(v2)**: `scopeSites`는 문서 로드 시점 값이다. 항목을 편집 폼에 불러올 때 서버가 준 `siteId`가 옵션에 **없으면 "사이트 #id (목록에 없음)" 옵션을 추가해 그 값을 보존**한다 — `select.value = …`가 조용히 비워져 `COMMON`으로 저장되는 일이 없다(수정이 `siteScope` 필수이기도 하다). 같은 셸의 사이트 탭이 쏘는 `content:sites-changed` 이벤트는 정적 페이지 탭이 구독해 "사이트 목록이 바뀌었습니다. 새로고침하면 선택지에 반영됩니다" 안내를 띄운다(옵션 자체는 새로고침으로 갱신). 오래 열린 화면이 새 사이트로 지정하려다 서버가 400(없는 사이트)을 주는 경로는 기존 오류 표시로 처리한다.
- **FAQ**: 편집·배지의 입력원은 카테고리 단건 응답이 아니라 **트리 응답**이므로 `FaqTreeResponse.Category`(+`toTree`)에 `siteId`를 싣는다(`FaqCategoryResponse`도 같이).
- **정적 페이지 목록**: 배지는 `SitePageSummaryRow`(JPQL 생성자 프로젝션)와 `findAllSummaries`, `SitePageSummaryResponse`·`SitePageDetailResponse`에 `siteId`를 싣는다.
- **정적 페이지 공개 주소·개정 링크(PR ②에서 남은 결함, v2 포함)**: 현재 `page-tab.html`이 `/pages/{slug}`·`/pages/{slug}/revisions/{no}`(종료된 경로, 404)로 링크를 만든다. 규칙: 전용 페이지는 **그 사이트 키**, 공통 페이지는 **기본 공개 사이트 키**를 접두어로 `/{key}/pages/…`를 만든다. 전용 사이트가 비활성이거나 옵션에 없으면 링크 대신 "공개 안 됨" 텍스트. 기본 공개 사이트가 없으면(비정상) 링크 없음.

### S9. 롤백·호환 (v2 개정)
- **PR ② 앱(V55 이전 코드)으로의 롤백도 위험하다**: 그 앱은 `/{siteKey}/…` 경로를 유지하면서 `site_id`를 무시하므로 **사이트 전용 콘텐츠가 모든 사이트에 노출**된다. 이전 앱은 V55 컬럼을 모르므로 사이트 삭제도 FK에만 막힌다. 따라서 **사이트 전용(`site_id IS NOT NULL`) 콘텐츠가 하나라도 있으면 V55 이전 앱으로 롤백하지 않는다**(`docs/deployment.md`·`docs/migration-guide.md`에 조건과 점검 SQL을 적는다).
- 롤백이 불가피하면 먼저 전용 콘텐츠를 **삭제 또는 백업 복구**한다. `site_id`를 NULL로 바꾸는 것은 삭제 대안이 아니다(공통 공개가 된다). FAQ는 카테고리가 항목이 남아 있으면 삭제를 거부하므로 **항목 → 카테고리 순**으로 지운다.
- 기존 행은 모두 공통이라 PR ③ 배포 직후 공개 화면은 이전과 100% 같다. 구·신 앱 혼합 운영 금지(PR ②)는 그대로다.

### S10. 하지 않는 것 (과설계 경계)
다대다 사이트 선택, 사이트별 가드/순서/상한, 사이트별 위임 권한, 관리 화면 사이트 필터·탭, FAQ 항목 단위 소속, 슬러그 사이트별 유일, 새 감사 액션, 새 공개 경로·인가 변경, 이미지 3종 경로의 사이트 접두어화, 사이트 응답의 콘텐츠 건수 표시.

## Q3. 인가·스키마 영향 고지 (승인 시 별도 확인 대상)
- **인가 정책**: 변경 없음(`SecurityConfig`·레이트리밋·`AdminFeature`·권한 카탈로그·새 URL 없음).
- **스키마**: **V55 신규**(네 테이블 `site_id` NULL 허용 + FK `RESTRICT`). 머지된 마이그레이션 수정 없음. 되돌리기 어려운 점: 사이트 전용 콘텐츠를 만든 뒤에는 롤백이 노출 범위를 넓힌다(S9).
- **공개 동작**: 배포 직후 변화 없음(모두 공통). 이후 관리자가 전용 대상을 지정하면 그 사이트에서만 보인다.

## Q4. 작업 단계 (안쪽 → 바깥)
1. `V55__add_content_site_id.sql` + 엔티티 4개 필드(`siteId`, 도메인 메서드로 변경) — 컴파일 확인.
2. `SiteRepository.findByIdForShare`, `ContentSiteScope`(검증+잠금), 각 리포지토리 `existsBySiteId`, `SiteService.hasContent` 확장.
3. 공개 쿼리·공개 서비스·컨트롤러(`HomeController`·`PublicFaqController`·`PublicSitePageController`)에 `siteId` 전달 — **배너 호출 지점은 컨트롤러가 아니라 `PublicHomeService.getHome(siteId)`**(그 안에서 `PublicBannerService`로 전달). 이미지 쿼리의 비활성 사이트 조건.
4. 공용 `SiteScope`(파싱·범위 초과 400) + `banner.version`·`faq_category.version`(V55)·낡은 화면 검사(S6b) + 관리 서비스·DTO(FAQ 카테고리 수정용 요청 분리 포함): 요청 `siteScope`(등록 선택·수정 필수), 응답 `siteId` — 배너·팝업·FAQ 카테고리(`FaqCategoryResponse`+**`FaqTreeResponse.Category`/`toTree`**)·정적 페이지(`SitePageSummaryRow` 프로젝션·`findAllSummaries`·요약/상세 응답).
5. `ContentScopeOptions`(공개 사이트 + 기본 공개 사이트 키) + 4개 페이지 컨트롤러 모델 + 템플릿 4곳(선택 박스·배지·낡은 옵션 보존·정적 페이지 링크 규칙·`content:sites-changed` 안내).
6. 시험·`./gradlew test`, 실기 검증, 문서(`CLAUDE.md` 루트·`admin.site`·`admin.banner/popup/faq/page`·`publicweb.home/faq/page`, `docs/deployment.md`·`docs/migration-guide.md`).

## Q5. 테스트 계획
- 마이그레이션: `ContentSiteMigrationTest` — 컬럼 4개·FK RESTRICT·기본값 NULL·멱등 재실행(`SiteMigrationTest` 선례).
- 공개 필터(DB 통합): 공통·A전용·B전용 혼합 데이터로 `/a`·`/b`에서 각각 배너·팝업·FAQ·푸터·페이지가 기대대로(교차 노출 없음), 다른 사이트 전용 페이지/개정/개정 목록 → 404, 비활성 사이트 전용 이미지 → 404, 공통은 영향 없음, 기존 공개 시험은 공통 데이터라 그대로 통과.
- 관리 서비스: 지정 검증(없음 400·관리자 사이트 400·null=공통), 수정으로 대상 변경, FAQ 카테고리 대상 변경 후 공개 반영, 정적 페이지 대상 변경이 `version`만 올리고 개정은 유지.
- 삭제 가드: 사이트에 배너·팝업·카테고리·페이지 각각 있으면 409, 지우면 삭제 가능; FK RESTRICT가 우회 삭제를 막는지(DB 직접).
- 동시성(v2): 사이트 삭제 ↔ 해당 사이트로 지정하는 생성 **및 공통→전용 수정** 경합(`LockWaitObserver`로 순서 고정). 단언은 "고아 없음"이 아니라 **계약**이다: 지정 쪽이 공유 잠금을 먼저 얻으면 → 지정 성공·삭제 **409**, 삭제가 먼저 커밋하면 → 지정 **400**(없는 사이트). 어느 경우에도 FK 예외·교착·500이 없어야 한다. 변이 실험: `findByIdForShare`를 일반 읽기로 바꾸면 이 시험이 실패하는지 확인(FK가 고아를 막아도 계약 위반은 드러난다).
- PUT 계약(v2): 수정에서 `siteScope` 누락/빈 문자열/`abc`/`0` → 400, `COMMON` → 공통, 숫자 → 해당 사이트. 등록 누락 → 공통. 팝업·배너·FAQ 카테고리·정적 페이지 네 곳 모두.
- FAQ 항목 재공개(v4): B가 공통 카테고리의 항목 편집 화면을 연 뒤 A가 그 항목을 A 전용 카테고리로 이동 → B가 질문만 고쳐 저장하면 **409**(`baseCategoryId` 불일치), 항목 소속 불변. 소속이 같으면 성공.
- FAQ 상세·트리 불일치(v5): 트리를 읽은 뒤 다른 세션이 새 전용 카테고리를 만들고 항목을 옮긴 상태에서 이 항목의 상세를 열면 저장이 막히고(복원 불가) 트리 재조회 후 정상 편집 가능. 선택 박스를 사용자가 바꾼 대상(`siteScope`)·이동할 `categoryId`가 **스냅샷에 덮이지 않고** 요청에 실리는지(정상 버전에서 박스만 바꿔 저장하면 대상이 바뀐다)도 같은 브라우저 시험에 포함.
- 브라우저(v4): ① 다른 관리자 대상 변경 → [목록 새로고침] → 옛 폼 저장 → 409(스냅샷 불변) ② 409 후 폼을 다시 불러와 재저장 → 성공. 배너·팝업·FAQ에 각각.
- 팝업 revision 경계(v6): 생성 직후 `revision=0`으로 수정 성공 → `revision`이 1로 증가 → 이전 값 0으로 재수정은 409, 누락·음수는 400. 배너·FAQ 카테고리 `version=0`은 400.
- 낡은 화면(v3): 두 세션 시나리오 — 공통 콘텐츠를 열어 둔 세션 B가 그 사이 세션 A가 A 전용으로 바꾼 뒤 `COMMON`+이전 버전으로 저장 → **409**, 대상 불변. 배너·팝업(revision)·FAQ 카테고리 각각. 같은 버전이면 성공하고 버전이 +1. 순서 저장이 배너/카테고리 `version`을 올리지 않음. 낡은 버전으로 제목만 고쳐도 409.
- DTO 분리(v3): FAQ 카테고리 같은 입력에서 POST는 `siteScope` 누락 성공(공통), PUT은 400. `siteScope=9223372036854775808` 등 범위 초과는 400(500 아님).
- FAQ 소속(v2): 항목을 A 전용 카테고리 → B 전용/공통 카테고리로 옮기면 공개 노출이 새 카테고리 소속을 따른다. 트리 응답의 `siteId`가 편집 폼 복원에 쓰인다.
- 브라우저(v2): 편집 폼에 옵션에 없는 `siteId`를 가진 항목을 불러와 저장해도 대상이 보존되는지, 정적 페이지 공개 주소/개정 링크가 `/{key}/pages/…`를 가리키고 클릭하면 200인지.
- 컨벤션: 공개 인벤토리 시험은 새 경로가 없어 무변경으로 통과해야 한다. `scopeSites` 모델이 네 페이지에 실리는지 MockMvc.
- 권한: MANAGER(위임 있음)가 `siteId` 지정 등록이 되고 사이트 관리 API는 여전히 403.
- 브라우저(playwright 실기): 네 화면의 선택 박스·배지, 사이트 두 개로 공개 화면 교차 확인.

## Q6. 실기 검증 계획
일회용 MariaDB(3317)+앱 8081(`project-site-menu-progress` 메모리 팁). 사이트 `news` 생성 → 공통/`main`/`news` 전용 배너·팝업·FAQ 카테고리·푸터 페이지 등록 → `/main`·`/news` 화면 비교(교차 비노출) → 대상 변경 반영 → 사이트 삭제 409 → 비활성화 시 전용 이미지 404 → 원복. 스크린샷 저장.

## Q7. 리스크
1. **수정 요청에 `siteScope` 필수화**(S6) — 기존 PUT 시험·JS·API 호출자는 필드를 추가해야 한다(누락은 400). 반대 위험인 "누락이 조용히 공통 공개"는 없다.
2. **합계 상한**(S5): 다중 사이트에서 배너 10개 합계가 빡빡함 — 승인 시 확인.
3. **공개 서비스 시그니처 변경으로 시험 대량 수정**(공개 서비스 시험 25여 개 파일) — 기계적이지만 범위가 넓다. `siteId` 필수화로 누락을 컴파일 오류로 드러낸다.
4. **이미지 id 경로의 사이트 무관**(수용): 노출 중인 공개 이미지는 id로 열람 가능 — PR ② 이전과 같은 노출 모델.
5. **MANAGER가 모든 표시 대상 지정 가능**(S7): 기능 단위 위임의 자연스러운 결과, 사이트별 위임은 범위 밖.
6. 정적 페이지가 사이트 전용인데 다른 곳(메뉴 URL·배너 링크)이 그 슬러그를 가리키면 그 사이트 밖에서는 404 — 링크 무결성 검사는 하지 않는다(수용).

## Q8. 승인 시 사용자에게 별도 확인할 것
1. 스키마 V55(되돌리기 어려움: 롤백 시 전용 콘텐츠 노출 범위 확대, S9).
2. **합계 상한 유지**(S5) vs 상한 상향.
3. **슬러그 전역 유일 유지**(S4) — 사이트마다 같은 슬러그 불가.
4. **기능 단위 위임이 모든 표시 대상에 적용**(S7) — 사이트별 위임 없음.
5. 수정 요청의 `siteScope`·`version` 필수화(S6·S6b) — 사용자 결정(2라운드): 낡은 화면 방지를 위해 버전 컬럼 추가. 누락은 400이므로 기존 PUT 시험·JS가 함께 바뀐다.
6. 롤백 조건(S9): 전용 콘텐츠가 있으면 V55 이전 앱으로 롤백 금지.
7. 버전 검사(S6b)로 배너·팝업·FAQ 카테고리 PUT 계약이 바뀐다 — 기존 시험·JS 갱신 범위가 커진다(기계적).

## Q9. 개정 이력 (PR ③)
- P3-v1 (2026-10-13): 초안.
- P3-v2 (2026-10-13, 리뷰 1라운드 — codex `gpt-6.1-sol`, no-ship, P1 2·P2 4·추가 2): 변경 — ① S6 재작성: 수정 요청 `siteScope`(`COMMON`|ID 문자열) **필수**·누락/빈 값 400(PUT 누락이 공통 공개로 승격하는 결함 제거), 등록은 누락=공통. ② S8 낡은 사이트 목록 방어(없는 `siteId`는 "목록에 없음" 옵션으로 보존, `content:sites-changed` 안내). ③ FAQ `FaqTreeResponse.Category`/`toTree`·정적 페이지 `SitePageSummaryRow` 프로젝션을 변경 목록에 명시. ④ 정적 페이지 관리 화면의 공개 주소·개정 링크를 `/{key}/pages/…`로(전용=소속 키, 공통=기본 사이트 키, 비활성이면 링크 없음). ⑤ S9 롤백: PR ② 앱으로의 롤백도 전용 콘텐츠가 있으면 금지, NULL화는 복구 수단 아님, FAQ는 항목→카테고리 순 삭제. ⑥ 동시성 시험을 계약 단언(성공/409/400, 500·교착 없음)으로 교체하고 공통→전용 수정 경합 포함. ⑦ `PublicHomeService.getHome(siteId)` 호출 지점 명시, 잠금 선례를 `PostService`로 정정. 기각 없음.
- P3-v3 (2026-10-13, 리뷰 2라운드 — codex `gpt-6.1-sol`, no-ship, P1 1·P2 2): 변경 — ① S6b 신설: 낡은 화면이 `COMMON`을 명시 전송해 방금 전용으로 바뀐 콘텐츠를 공통 공개로 되돌리는 경로를 **사용자 결정("버전 컬럼 추가")**으로 막는다 — `banner.version`·`faq_category.version`(V55)·팝업은 기존 `revision` 재사용, 수정 요청에 버전 필수·불일치 409, 순서 변경은 버전 미증가. ② FAQ 카테고리 POST·PUT 공용 요청 분리(`FaqCategoryUpdateRequest`)와 POST 성공/PUT 400 MVC 시험. ③ `SiteScope`가 Long 범위 초과를 400으로 변환(500 방지) + 경계 입력 시험. 기각 없음.
- P3-v4 (2026-10-13, 리뷰 3라운드 — codex `gpt-6.1-sol`, no-ship, P1 1·P2 1): 변경 — ① FAQ **항목** 수정에 `baseCategoryId` 필수·불일치 409(소속이 사실상 표시 대상이라 낡은 폼이 전용 항목을 공통 카테고리로 되돌려 재공개하는 경로 차단; v3의 "항목은 대상이 없다" 판단은 오류였음). ② 화면의 편집 스냅샷 규칙(버전·대상은 폼을 서버에서 채우는 순간 고정, 목록 갱신이 바꾸지 않음) + 브라우저 시험 2건. 기각 없음.
- P3-v5 (2026-10-13, 리뷰 4라운드 — codex `gpt-6.1-sol`, no-ship, P1 1·P2 1): 변경 — ① FAQ 상세·트리 응답의 조회 시점 불일치로 선택 박스가 옛 카테고리로 남는 경로 차단: 상세의 `categoryId`를 선택지에 복원하지 못하면 저장을 막고 트리를 다시 읽는다. ② v4 스냅샷 규칙 교정: 스냅샷에는 **검증 기준**(id·version/revision·baseCategoryId)만 두고 사용자가 바꾸는 **요청 값**(siteScope·이동할 categoryId)은 폼 입력에서 읽는다(v4 문구는 변경을 무시하는 결함이었음). 기각 없음.
- P3-v6 (2026-10-13, 리뷰 5라운드 — codex `gpt-6.1-sol`, no-ship, P2 1): 변경 — 팝업 `revision`의 시작값이 0(V39 기본값·`PopupService` 생성)인데 "누락/비양수 400"으로 쓰면 신규·미수정 팝업의 첫 수정이 막히므로 팝업은 `@NotNull @PositiveOrZero`, 배너·FAQ 카테고리 `version`은 `@Positive`로 구분하고 경계 시험 추가. 기각 없음.

## 구현·검증 결과 — PR ③ (2026-10-14)

### Context
사이트 설정 메뉴 중 **PR ③(사이트별 콘텐츠: 배너·팝업·FAQ 카테고리·정적 페이지/푸터)** 을 계획 P3-v6(적대적 리뷰 6라운드 ship, 사용자 승인 시 "낡은 화면 = 버전 컬럼 추가"·"범위 = 전부" 선택)대로 구현했다. **인가 정책(`SecurityConfig`·레이트리밋·`AdminFeature`)은 바뀌지 않았다.** 스키마는 V55 한 개다. PR ④⑤는 미착수.

### 핵심 확정 사항 (계획 P3-v6에서 변경 없음)
- `banner`·`popup`·`faq_category`·`site_page`에 `site_id` NULL(=공통) + FK `RESTRICT`, `banner`·`faq_category`에 `version`(팝업은 `revision`, 페이지는 `version` 재사용). 대상 지정은 `ContentSiteScope`가 사이트 행 `FOR SHARE`로 검증(삭제 ↔ 지정이 409/400 중 하나로 갈림).
- 수정 요청: `siteScope`(`COMMON`|ID 문자열)·버전 필수, 등록은 `siteScope` 선택. FAQ 카테고리는 수정용 DTO 분리, FAQ 항목은 `baseCategoryId` 필수.
- 공개 필터는 쿼리에 고정하고 공개 서비스가 `siteId`를 필수로 받는다. 이미지 id 경로는 사이트 무관(소속 사이트 비활성이면 404).
- 화면: 서버 렌더링 선택지(`ContentScopeOptions`), `content-scope.js`(없는 사이트 ID 보존), 편집 스냅샷(검증 기준)과 요청 값(폼 입력) 분리.

### 구현 파일
- 마이그레이션: `V55__add_content_site_id.sql`
- 신규: `SiteScope`·`ContentSiteScope`·`ContentScopeOptions`(`com.cms.admin.site`), `FaqCategoryUpdateRequest`, `static/js/admin/content-scope.js`
- 변경(백엔드): 엔티티 4개·리포지토리 6개(`findByIdForShare`·`existsBySiteId`·공개 쿼리의 소속 조건)·관리 서비스 4개·요청/응답 DTO·`SiteService.hasContent`·공개 서비스/컨트롤러(`HomeController`·`PublicFaqController`·`PublicSitePageController` 등)·`PublicSite.requireId`·페이지 컨트롤러 4개
- 변경(화면): `admin/banner·popup·faq/manage.html`, `admin/page/manage.html`·`fragments/page-tab.html`
- 문서: 루트·`admin.site/banner/popup/faq/page`·`publicweb.support/home/faq/page` `CLAUDE.md`, `docs/deployment.md`·`docs/migration-guide.md`

### 검증 결과
- **시험**: 신규 6개 클래스 — `ContentSiteMigrationTest`·`ContentSiteScopeApiIntegrationTest`·`ContentSiteScopeConcurrencyIntegrationTest`·`PublicContentSiteScopeIntegrationTest`·`ContentScopeAdminBrowserTest`(Playwright 5건)·기존 클래스에 소속 필터/비활성 이미지/삭제 가드 추가(`BannerRepositoryDataJpaTest`·`PopupRepositoryDataJpaTest`·`SiteServiceTest`). 1차 전체 실행에서 2,432건 중 50건이 새 계약(PUT에 `siteScope`·버전 필수, 생성자·시그니처 변경)으로 실패 → 갱신, 2차 2,461건 중 9건(갱신 누락·시험 결함) → 수정 후 대상 클래스 통과, **최종 전체 `./gradlew test`: 2,461건 중 실패 0·오류 0(건너뜀 12 — 기존 `@Disabled`/조건부)**.
- **변이 실험**: `findByIdForShare`의 `@Lock(PESSIMISTIC_READ)`를 제거하면 `ContentSiteScopeConcurrencyIntegrationTest` 1건이 실패한다(복원 확인).
- **브라우저 시험이 잡은 실제 결함 1건**: FAQ 상세/트리 불일치 안내 문구가 이어지는 `reload()`에 지워져 사용자에게 보이지 않았다 → 재조회 뒤에 띄우도록 수정.
- **실기(Playwright + 일회용 MariaDB 3317 + 앱 8081)**: 사이트 `news` 생성 → 공통/전용 FAQ 카테고리·정적 페이지 등록 → `/main/faq`는 공통만, `/news/faq`는 공통+전용, 푸터도 동일 / `/main/pages/terms-news`·개정·개정 목록 404, `/news/pages/terms-all` 200 / 옛 경로 `/faq`·`/pages/…` 404 / 관리 화면 선택 박스·배지·정적 페이지 공개 주소(`/news/pages/…`, 공통은 `/main/pages/…`) / UI로 카테고리 대상 변경 → 공개 반영 → 같은 폼에서 이어서 원복 / 사이트 삭제 409 / `siteScope` 누락 PUT 400 / MANAGER(FAQ 읽기·수정 위임): 선택지 렌더링, 대상 지정 200, `/admin/api/sites`·정적 페이지 화면 403.

### 이슈
- **구현 중 계획과 달라진 점 없음.** 시험 관점의 조정: 팝업 동시성 시험 2건은 revision 검사 때문에 "같은 revision을 든 동시 수정"이 하나만 성공하므로 8스레드 시험은 409 뒤 재시도로, 락 대기 시험은 대기자가 `before+1`을 들고 요청하도록 바꿨다(증가분 유실 없음 단언은 유지).
- **수용한 한계(계획대로)**: 합계 상한(배너 10 등), 슬러그 전역 유일, 기능 단위 위임이 모든 표시 대상에 적용, 이미지 id 경로의 사이트 무관, FAQ 항목 질문·답변의 stale-form 덮어쓰기.
- **배너 화면은 브라우저로 직접 못 본 부분이 있다**: 실기에서는 배너 업로드 흐름을 돌리지 않았다(이미지 업로드 필요) — 대신 `ContentScopeAdminBrowserTest`가 배너 화면의 대상 전송·낡은 폼 거부·보존 옵션을 실제 브라우저로 확인한다. 팝업 화면의 선택 박스는 코드와 기존 `PopupAdminFormBrowserTest`(통과)로만 확인.

### 후속
- 코드 리뷰 게이트(`/code-review-loop`) → `/commitPR`. 로드맵 반영(`/updateRoadmap`)은 사이트 설정 메뉴 전체 완료 후 한 번에.
- PR ④(사이트별 공지: `board.site_id`, `SiteService.hasContent`에 게시판 추가), PR ⑤(공개 메뉴 렌더링).
