# CLAUDE.md — com.cms.admin.permission

이 디렉터리(관리자 권한 판정 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-menu-permission-management.md`(적대적 리뷰 3라운드 ship — 역할별 권한관리 PR ①~④, 2026-10-02)와 `adversarial-review/plan/PLAN-member-permission.md`(**역할별 → 사용자별 전환**, 적대적 리뷰 5라운드 ship, 2026-10-03). 지금 권한은 **회원(MANAGER) 개인 단위**다.

## 개념

MANAGER가 무엇을 할 수 있는지는 **코드 카탈로그(`AdminFeature`) ∩ 그 회원 본인의 DB 허용 행(`member_permission`) ∩ 의존 규칙**으로 정해지고, **코드가 최종 권위**다 — DB에 위임 불가 기능 행이 수동으로 들어가도 판정기는 무시한다. 역할은 ADMIN·MANAGER 2종뿐이며 새 역할·역할 테이블·ADMIN의 DB화·기능/URL 규칙의 DB화는 범위 밖이다(lockout·권한 상승·기본 거부 붕괴 위험). **같은 MANAGER 역할이어도 회원마다 권한이 다르다.**

| 기능 | 종류 | 설명 |
|---|---|---|
| `DASHBOARD`, `MY_INFO`, `SEARCH` | `ALWAYS` | 로그인한 ADMIN·MANAGER 전원 상시 허용(`SEARCH`=상단바 통합 검색 API 사용 자체 — 결과의 도메인별 노출은 `AdminSearchService`가 판정기로 필터한다, 아래 "통합 검색"). DB를 보지 않고 끌 수 없다(로그인 직후 `/admin`으로 이동하므로 대시보드를 끄면 403이 난다). **`MY_INFO`의 게이트에는 쪽지함 페이지 `/admin/member/messages`가 정확 경로 1개로 포함**된다(2026-10-05 승인, `com.cms.admin.message`의 `CLAUDE.md`) — ALWAYS 게이트는 HTTP 메서드를 구분하지 않아 그 경로에는 GET 핸들러만 두고 `MessagePageMethodConventionTest`가 CI에서 잠근다. 쪽지 API는 기존 `/admin/api/members/me/**` 안이다 |
| `NOTICE` | `DELEGABLE` | ADMIN은 항상, MANAGER는 **그 회원의** (기능, 동작) 허용 행이 있을 때만. 동작 `READ·CREATE·UPDATE·DELETE` |
| `BOARD` | `BOARD_SCOPED` | 게시글(게시판별 위임, 2026-10-08 — 아래 "게시판별 권한"). ADMIN은 항상, MANAGER는 **그 회원의 (게시판, 동작) 행**(`member_board_permission`)으로 판정. `member_permission`의 `BOARD` 행으로는 부여할 수 없다(캐시가 무시, PUT은 400) |
| `MEMBER`, `MENU`, `ACTION_LOG`, `BOARD_ADMIN`, `PERMISSION` | `ADMIN_ONLY` | 위임 불가. `PERMISSION`을 `DELEGABLE`로 바꾸면 `AdminFeature` 생성자가 **클래스 로딩 시점에 예외**를 던진다(자기 승격 차단). 권한관리 메뉴는 ADMIN만 접근한다 |

**신규 MANAGER는 권한이 없다(0개)** — 대시보드·내 정보만 접근한다. 생성 후 권한관리에서 부여한다(2026-10-03 전환 이전에는 생성 즉시 공지 CRUD가 열렸다).

## 판정 (`AdminPermissionEvaluator`, 빈 이름 `adminPermission`)

유일한 판정 함수다. URL 게이트(`SecurityConfig`)와 메서드 어노테이션(`@RequirePermission`)이 **같은 함수**로 판정해 어긋나지 않는다.

- 익명·인증 없음 → false / `ROLE_ADMIN` → **항상 true(DB 미조회 — 캐시 장애·어떤 저장으로도 ADMIN은 권한을 잃지 않는다)** / `ALWAYS` → ADMIN·MANAGER true / `ADMIN_ONLY` → false / `DELEGABLE` → `ROLE_MANAGER`이고 **그 회원 본인의** 허용 행이 있으며 **쓰기 동작(CREATE·UPDATE·DELETE)은 READ도 있어야** true.
- 역할은 세션 `Authentication` 권한에서, **회원 ID는 principal(`CustomUserDetails.getId()`)에서** 읽는다. principal이 `CustomUserDetails`가 아니면(회원을 식별할 수 없으면) 위임 기능은 **거부(fail-closed)**하고 상시 허용만 통과한다. 그래서 **위임 기능을 다루는 MANAGER 시험은 `@WithMockUser`가 아니라 `@WithManager`(슬라이스) 또는 `TestMembers.asMember`(통합)를 쓴다** — `@WithMockUser(roles = "MANAGER")`는 회원 ID가 없어 위임 기능이 전부 거부된다(거부 단언이 엉뚱한 이유로 통과하는 것도 막는다).
- `check(String, String)`은 SpEL 진입점이다. 이름을 enum으로 파싱하지 못하면(null 포함) **false(fail-closed)**.
- 한 요청에서 여러 번 판정할 때는 `snapshot()`을 한 번 받아 `allows(snapshot, ...)` 오버로드에 넘긴다(중간에 무효화돼도 한 요청 안에서 같은 권한 버전을 쓴다).

## 두 계층 인가 (`SecurityConfig` + `@RequirePermission`)

- **URL 게이트(필터)**: 카탈로그의 `gatePatterns`를 순회해 `ALWAYS`는 `hasAnyRole('ADMIN','MANAGER')`, `DELEGABLE`은 `featureReadGate`(ADMIN 또는 해당 기능 READ 허용 MANAGER)로 등록한다. **기능 단위 READ 게이트**라 HTTP 메서드를 구분하지 않는다. 규칙 순서는 기존과 같다(공개·swagger → 카탈로그 게이트 → `/admin/**` ADMIN 캐치올 → … → `anyRequest().denyAll()`). 카탈로그에 없는 `/admin/**`는 캐치올에 걸려 ADMIN 전용이다.
- **메서드 계층**: `@RequirePermission(feature, action)`이 메타 `@PreAuthorize("@adminPermission.check('{feature}', '{action}')")`를 감싼다(`MethodSecurityConfig`의 `AnnotationTemplateExpressionDefaults`가 자리표시자를 치환 — enum 속성 치환은 테스트로 확인됨). 위임 가능 기능의 핸들러에만 붙인다. **페이지 컨트롤러에는 붙이지 않는다** — `GlobalApiExceptionHandler`가 범위 제한 없는 `@RestControllerAdvice`라 페이지에 메서드 보안을 걸면 HTML이 아니라 JSON 403이 나가므로, 페이지 차단은 URL 게이트가 HTML 403으로 낸다.
- 공지 핸들러 분류(U4): 목록·상세·첨부 목록·다운로드 = READ, 생성 = CREATE, 수정·**첨부 업로드·첨부 삭제 = UPDATE**, 삭제 = DELETE. 부작용: DELETE만 가진 MANAGER는 첨부가 있는 공지를 지울 수 없다(기존 409 규칙).

## 컨벤션 테스트 (CI 잠금)

`AdminEndpointAuthorizationConventionTest`가 `com.cms.admin` 컨트롤러를 스캔해 `/admin` 아래 핸들러를 검사한다. **읽기 전용 GET/HEAD 페이지 핸들러만 면제**하고, 그 밖의 모든 핸들러(경로에 `/api/`가 있든 없든, 메서드 제한 없는 `@RequestMapping` 포함)와 모든 API는 정확히 하나의 인가 선언(`@RequirePermission` | `hasRole('ADMIN')` | ALWAYS 경로 한정 `hasAnyRole('ADMIN','MANAGER')`)을 가져야 한다 — URL 게이트가 HTTP 메서드를 구분하지 않아 READ 게이트만 통과하면 같은 경로의 쓰기 핸들러까지 열리기 때문이다. 새 엔드포인트를 선언 없이 추가하면 CI가 실패한다. 규칙이 실제로 위반을 잡는지는 반례 테스트로 고정돼 있다.

`AdminPermissionMatrixIntegrationTest`(실제 SecurityConfig·판정기·캐시·MariaDB, 시험마다 실제 MANAGER 회원 행을 만들고 지운다)는 공지 핸들러 9개 × MANAGER 권한 조합(전부·없음·READ만·READ+CREATE·READ+UPDATE·READ+DELETE·READ 없는 쓰기)을 시험한다. 허용은 **기대 성공 상태와 실제 저장 결과**, 거부는 403(API JSON `ACCESS_DENIED`)과 **변경 없음**으로 단언하고, 경계 입력(후행 슬래시·`;`·`/./`·HEAD·OPTIONS)도 확인한다. **교차 회원 격리**(회원 A에게만 준 권한이 같은 역할의 회원 B에게 적용되지 않음)와 **신규 MANAGER(권한 0개)** 시험이 있다. 변이 실험으로 "삭제를 UPDATE로 오표시"가 이 시험에서 실패함을 확인했다.

## 캐시 (`PermissionCache`)

- generation 카운터 + 단일 비행 로드. `invalidate()`(실패할 수 없는 메모리 연산)가 올린 뒤 로드는 시작 전 generation과 같을 때만 설치한다 — 로드 도중 무효화가 끼면 이번 요청만 그 결과로 판정하고 다음 요청이 다시 로드한다. **전체 회원의 허용 행을 적재**한다(MANAGER 수 × 위임 동작 수라 작다).
- **로드는 호출자 트랜잭션과 격리된 `REQUIRES_NEW` 읽기 트랜잭션**에서 한다(`PermissionCacheIsolationIntegrationTest`가 실제 MariaDB 교차 실행으로 고정 — 로드가 호출자 트랜잭션에 참여하면 REPEATABLE READ의 낡은 스냅샷이 "최신"으로 설치돼 회수된 권한이 계속 허용된다. 변이 실험으로 확인).
- **로드 실패 = fail-closed**: ERROR 로그 후 빈 스냅샷(MANAGER 위임 기능 전부 거부)으로 판정하고 실패는 캐시하지 않는다. ADMIN·상시 허용 기능은 DB를 보지 않아 영향이 없다.
- 모르는 기능·동작 행, 위임 불가 기능 행은 WARN 후 무시한다(코드에서 기능을 지웠는데 행이 남아도 로딩 전체가 실패하지 않는다). 기능을 카탈로그에서 제거할 때는 행 삭제 마이그레이션을 함께 쓴다.
- **단일 인스턴스 전제**(세션 레지스트리·레이트리밋과 같은 한계). 다중 인스턴스로 확장하면 다른 인스턴스 캐시가 무효화 신호를 받지 못해 낡아진다 — 그때는 DB 버전 폴링 또는 캐시 제거가 필요하다. **직접 SQL로 `member_permission`을 바꿔도 캐시는 무효화되지 않는다**(앱 재시작이 곧 캐시 폐기) — 아래 "롤백 후 재배포" 참조.

## 스키마 (V17~V19, 2026-10-03 · V22, 2026-10-06)

- `member.permission_version bigint NOT NULL DEFAULT 0`(V17): 회원별 권한 매트릭스의 낙관적 버전. 허용 행이 0개인 회원도 잠글 대상(회원 행)과 버전이 있다. `Member.permissionVersion`은 `@Builder.Default 0L` 필수(없으면 기존 생성 경로가 NULL을 INSERT해 깨진다).
- `member_permission(member_id, feature, action)`(V18) PK 3컬럼 + `member` FK(RESTRICT). **행이 있으면 허용**(거부 행 없음), ADMIN 행 없음. `feature`·`action`은 `VARCHAR`(DB enum 아님). **테스트 정리 코드가 `DELETE FROM member`를 쓰면 `member_permission`을 먼저 지워야 한다**(`TestMembers.delete`).
- V19: 배포 시점의 `ROLE_MANAGER` 허용 행을 **`DELETED`가 아닌 기존 MANAGER 전원에게 복사**(정확 일치·NOTICE 유효 4동작만, NOTICE READ 행이 있을 때만) — 배포 직후 기존 계정 동작은 같다. **일회성 초기화이지 복구 수단이 아니다**: 성공한 뒤 수동 재실행하면 ADMIN이 회수한 권한이 되살아난다(`MemberPermissionMigrationTest`가 복사 규칙·재실행 중복 없음을 고정).
- 역할 단위 `permission_role`·`role_permission` 테이블(V13·V14)은 **V22(2026-10-06, PR B)에서 제거됐다**(`DROP TABLE IF EXISTS`, 자식 먼저, 되돌릴 수 없음 — 배포 전 백업). V22 이후 사용자별 전환 이전 앱은 기동하지 못하고, 백업 복원 시에는 V22 없는 이미지로 컨테이너를 먼저 교체해야 V22가 다시 실행되지 않는다(`docs/migration-guide.md` "V22 배포 전 백업과 복구"). 과거 마이그레이션 시험(`PermissionMigrationTest`·`MemberPermissionMigrationTest`의 V19 재실행)은 **`target("21")`로 V22 직전까지만** 적용하고, V22 자체는 `RolePermissionDropMigrationTest`(적용·부분 적용 재실행·실패 repair·롤백 호환)가 고정한다. **새 마이그레이션 시험에서 "이전 앱" location을 흉내 낼 때는 특정 파일만 빼지 말고 그 앱의 버전 이하 파일만 복사한다** — 더 높은 버전 파일이 남으면 적용된 버전이 future가 아니라 missing으로 분류돼 validate가 실패한다.
- **V17·V18 실패 복구**(DDL 암묵 커밋으로 Flyway 이력과 어긋난 경우): `docs/migration-guide.md` "V17~V19 실패 복구". 핵심은 **`success=1`인 버전의 객체는 건드리지 않고 실패·이력 없는 버전의 잔여 객체만 DROP**한 뒤 `flyway repair`다(`MemberPermissionMigrationTest`가 "V17 성공 + V18 실패" 복구와 잘못된 절차의 반례를 고정).

## 롤백·재배포 주의

- **앱 롤백**: V22 이후 사용자별 전환 이전 앱으로는 **되돌릴 수 없다**(테이블 없음 → 기동 실패). V22 이전 DB에서는 이전 앱이 기동하며 `role_permission`을 읽어 회원별 회수·부여 결과가 사라지고 역할 단위 권한으로 돌아간다(2026-10-06 실기 관측). 기본은 roll-forward(수정 버전 배포)다.
- **롤백했다가 신버전을 다시 배포할 때**: 구버전 운영 중의 역할 변경·회원 생성은 `member_permission`에 반영되지 않아(V19는 재실행되지 않는다) 낡은 행이 되살아난다. 앱 정지 → `DELETE FROM member_permission; UPDATE member SET permission_version = permission_version + 1;` 한 트랜잭션 → 기동(fail-closed: MANAGER 전원 권한 0개로 시작, ADMIN이 재부여). 허용 행이 0개인 회원까지 **전원의 버전을 올려야** 정리 전 화면의 오래된 PUT이 과거 권한을 복원하지 못한다. 앱이 켜진 채 SQL을 실행했다면 **재시작이 캐시 폐기**다. 정리 뒤 기존 권한 보유 MANAGER의 공지 접근이 실제 403인지 확인한다(`docs/deployment.md`).

## 통합 검색 (`com.cms.admin.search`, 2026-10-05, `PLAN-admin-unified-search.md` — 적대적 리뷰 4라운드 ship)

`GET /admin/api/search-results?keyword=`(ALWAYS 기능 `SEARCH`, 게이트 패턴은 **이 경로 하나**라 하위 경로는 ADMIN 캐치올) — 상단바 드롭다운이 호출한다. **검색이 권한 우회 경로가 되지 않는 것이 불변식**이라 섹션 노출을 `AdminSearchService`가 이 판정기로 결정하고, 권한 없는 섹션은 빈 배열이 아니라 **응답 키 자체를 생략**한다(`NON_NULL`): 메뉴 = 사이드바와 같은 `getSidebarMenus(menuUrlVisibility)` 결과를 평탄화(자식 있는 그룹은 건너뜀), 공지 = ADMIN 또는 그 회원의 `NOTICE:READ`, 관리자 계정 = ADMIN만(이메일 등 제외). MANAGER 스냅샷은 요청당 한 번만 읽는다(ADMIN은 캐시 미호출). **최소 검색어 2코드포인트**(미만은 쿼리 없이 빈 결과 200 — 서버 비용 제한, 레이트리밋은 두지 않았다), 최대 100자(초과 400), 섹션당 5건 + 전체 건수. **메뉴 이동 URL은 같은 출처 경로만**(`/`로 시작, `//`·`\`·공백·제어문자 없음) 결과에 넣는다 — 메뉴 URL은 저장 시 길이만 검사해 `javascript:` 등이 저장될 수 있기 때문이며(저장 검증·사이드바 `href`는 범위 밖 후속), 화면(`topbar-search.js`)도 같은 검사를 한 번 더 한다. 결과 클릭은 메뉴 → URL, 공지·관리자 → `/admin/notice/manage?id=`·`/admin/member/manage?id=`로 이동해 **관리 화면이 상세 모달을 바로 연다**(`id`는 `^[1-9]\d{0,15}$`만, 연 뒤 `replaceState`로 쿼리에서 제거). 이동 뒤 권한은 기존 게이트·상세 API가 다시 판정한다 — 링크를 연 시점에 권한이 없으면 페이지 게이트 403, 페이지를 받은 뒤 회수되면 상세 API 403이 모달에 표시된다(검색 결과와 이후 요청 사이의 권한 변경은 보장하지 않는다). 시험: `AdminSearchServiceTest`(권한 조합·URL 필터·최소 길이)·`AdminSearchControllerTest`·`AdminSearchIntegrationTest`(실제 스택에서 권한 부여·회수 효과, 메뉴가 사이드바 가시성을 따름)·`MemberKeywordSearchDataJpaTest`.

## 사이드바 연동

사이드바 노출은 이 판정기에서 도출된다 — `menuUrlVisibility(Supplier<snapshot>, authentication)`(ADMIN 항상 true, MANAGER는 `AdminFeature.forMenuUrl`로 URL→기능을 완전 일치로 찾아 **그 회원 본인의** READ 판정, 미분류·null은 false). 메뉴 관리 화면의 노출 안내는 회원 한 명의 시점이 없으므로 `anyManagerMenuUrlVisibility()`(**카탈로그 분류만 — "권한을 받으면 MANAGER가 볼 수 있는가", 스냅샷 불필요**)를 쓴다. 가지치기·노출 안내 규칙은 `com.cms.admin.menu`의 `CLAUDE.md` "노출 계산" 참조. 그래서 MANAGER에게 **보이는 링크는 항상 그 회원에게 READ가 허용된 기능의 URL**이고 그 URL의 게이트도 같은 판정이라 "보이는데 403"은 구조적으로 생기지 않는다(역은 가능 — 메뉴가 없거나 비활성).

## 권한관리 API·화면

- `GET`/`PUT /admin/api/members/{id}/permissions`(`MemberPermissionController`, `hasRole('ADMIN')`) + 화면 `GET /admin/permission/manage`(`PermissionPageController`, `@AdminPage`, `templates/admin/permission/manage.html`). **`SecurityConfig` 변경 없음** — 새 경로는 어떤 카탈로그 `gatePatterns`에도 걸리지 않아 `/admin/**` ADMIN 캐치올이 막는다(`PERMISSION` = ADMIN_ONLY). `/admin/api/members/me/**`는 상시 허용 `MY_INFO` 게이트라 MANAGER도 통과하지만 `{id}=me`는 `Long` 변환 실패로 **400**이라 권한 API에 닿지 못한다(시험으로 고정). 대상은 **ROLE_MANAGER 회원뿐**: ROLE_USER·없는 회원 → 404(관리자 API의 존재 숨김 정책), ROLE_ADMIN → 400, 삭제된 계정은 PUT 409(GET은 허용).
- **PUT은 그 회원의 위임 가능 기능 허용 집합 전체 교체**다(`grants:[{feature,action}]`, 빈 배열=전부 회수). 400: 위임 불가 기능·미지원 동작·중복·**READ 없는 쓰기(자동 보정 없이 거부)**. 검사 순서는 요청 내용(DB 접근 전) → **회원 행 잠금** → 대상(404·400·409) → 버전이라 내용 400이 대상 400·404보다 먼저다.
- **잠금·버전** (`MemberPermissionService.replace`): 첫 DB 조회가 **회원 행 `SELECT … FOR UPDATE`**(`MemberRepository.findByIdForUpdate`)다. 허용 행이 0개여도 잠글 행이 있어 같은 회원의 동시 저장이 직렬화되고, **같은 행을 잠그는 역할·상태 변경(`AdminMemberService.updateAdminMember`)과도 직렬화**된다(`MemberPermissionConcurrencyIntegrationTest`가 `INNODB_LOCK_WAITS`로 두 방향 모두 관측). 버전은 `@Version`이 아니라 **잠금 아래 수동 비교**(`permission_version` ≠ 요청 → 409 `RESOURCE_CONFLICT`). 변경이 없으면 쓰기·버전·무효화가 없다(200 + 현재 상태).
- **역할 변경 시 개별 권한 삭제**(`AdminMemberService.updateAdminMember`, 같은 트랜잭션): 역할이 실제로 바뀌면(방향 무관) 그 회원의 허용 행을 **변형 행까지 전부 삭제**하고(재강등 때 예전 권한이 조용히 되살아나지 않게), **삭제 건수와 무관하게 `permission_version`을 +1**한다(권한 0개 회원이 `MANAGER→ADMIN→MANAGER`로 왕복해도 역할 변경 이전 화면의 PUT은 409 — 잠금은 요청을 직렬화할 뿐 그 사이 역할이 왕복했다는 사실은 버전이 올라야 검출된다). 캐시 무효화 이벤트는 행이 실제로 지워졌을 때만 낸다. 역할이 안 바뀌는 수정(이름·이메일·상태)은 행과 버전을 건드리지 않는다. 별도 감사 행은 없고 기존 `ADMIN_UPDATE`로 남는다(`MemberRoleChangePermissionIntegrationTest`).
- **캐시 무효화**: 변경이 있을 때만 `PermissionChangedEvent`를 발행하고 `PermissionChangedListener`가 **AFTER_COMPLETION**(커밋·롤백·결과 불명 모두)에서 `invalidate()`한다. AFTER_COMMIT만 쓰면 DB는 커밋했는데 호출자가 커밋 예외를 받을 때(응답 직전 연결 끊김 — 트랜잭션 상태 UNKNOWN, AFTER_COMMIT은 COMMITTED에서만 실행) 리스너가 실행되지 않아 회수한 권한이 캐시에 남고, 같은 집합 재저장은 "변경 없음"이라 복구도 안 되기 때문이다(`MemberPermissionApiIntegrationTest`가 실제 커밋 후 SQLException을 던지는 DataSource 프록시로 고정). 롤백 때의 불필요한 폐기는 다음 요청이 한 번 더 로드하는 비용뿐이다. 409·변경 없음은 이벤트 자체가 없다. 역할이 바뀌지 않으므로 세션 만료는 필요 없고 같은 세션의 다음 요청부터 반영된다(역할 변경은 기존대로 세션을 만료시킨다).
- **변형 행 가드**: `member_permission` PK는 `utf8mb4_general_ci`(PAD 비교)라 수동 삽입된 `read`·`'READ '`·`notice` 같은 변형 행이 정상 키와 같은 키로 취급되는데, 판정기는 그 행을 무시한다. PUT은 추가할 키마다 `existsById`(DB PK 동등 비교)로 충돌을 탐지해 **409로 거부하고 자동 삭제하지 않는다**(운영자가 수동 SQL로 정리). 모르는 기능·동작·위임 불가 기능 행도 건드리지 않는다.
- **감사** `PERMISSION_UPDATE`(`targetType=MEMBER_PERMISSION`, **`targetId` = 대상 회원 ID**): 성공은 `v3→v4: +공지사항.생성, -공지사항.삭제`, 변경 없음은 `v3: 변경 없음`(라벨은 코드 상수·enum 이름·숫자만 — 회원 이름 등 사용자 입력 없음). **`targetId`·`targetLabel`은 `AdminActionLogAspect`가 반환 객체의 `getMemberId()`·`getAuditLabel()` 리플렉션으로 추출한다 — 결과 객체(`MemberPermissionUpdateResult`)가 `Long getMemberId()`를 노출해야 하며 없으면 예외를 삼키고 `targetId=null`로 저장된다**(변경 없음 결과도 같다, 시험으로 고정). **실패(400·404·409·커밋 실패)는 FAIL 감사 1건이고 `targetLabel`이 없다**(Aspect 정책). 서비스 메서드가 최상위 트랜잭션 진입점이어야 한다(컨트롤러 → `replace()` 직접 호출). 감사 저장은 최선 노력이라 **저장 실패 시에도 권한 변경은 유지되므로 롤백 판단을 감사만 보고 하지 않는다**.
- **조회**는 캐시가 아니라 DB를 한 읽기 트랜잭션으로 읽어 버전·행이 같은 시점을 보여 준다. `grantedActions`는 판정기와 같은 의미의 유효 허용값이다(상시 허용=지원 동작 전부, 위임 불가=빈 배열, 위임 가능=DB 행 ∩ 지원 동작 ∩ READ 의존).
- **화면**(`manage.html`): 왼쪽 **MANAGER 목록**(`GET /admin/api/members?userType=ROLE_MANAGER&size=100&sort=userName,asc` 재사용 — 서버가 `DELETED`를 이미 제외) + 아이디·이름 검색 + **[더 보기]**(다음 페이지 이어 붙이기)·[새로고침], 오른쪽 선택 회원의 매트릭스(READ 연동·`dirty`·저장 중 잠금·409 재조회·400 초안 유지·`beforeunload`·[되돌리기]). 검색은 `contains`라 정확 일치가 아니므로 [더 보기]가 모든 회원에게 도달하는 경로다(offset 페이징이라 조회 사이에 목록이 바뀌면 누락·중복 가능 — 목록 API의 알려진 한계, [새로고침]으로 복구). **비동기 응답 결합**: `selectedId`·`loadSeq`(요청 세대)·`loaded`·초안을 함께 관리해, 응답이 도착했을 때 자신의 세대가 현재 세대이고 응답 `memberId`가 선택한 회원일 때만 반영한다(늦은 응답이 다른 회원의 매트릭스·버전을 덮어 저장이 엉뚱한 회원에게 적용되는 것을 막는다). 조회·저장 중에는 목록 선택이 잠기고, `dirty`면 회원 전환이 막힌다(저장 또는 [되돌리기] 후 선택). 저장의 URL·`version`은 반영된 응답에서만 가져오며 `loaded.memberId !== selectedId`면 [저장]이 비활성이다. 이름·아이디는 `textContent`로만 삽입한다.
- **화면 버튼 숨김**: `AdminSidebarAdvice`가 한 `@ModelAttribute` 메서드에서 `sidebarMenus`와 `myPermissions`(`"NOTICE:CREATE"` 같은 키 집합, `AdminPermissionEvaluator.grantedActionKeys`)를 같은 스냅샷으로 계산하고, `notice/manage.html`이 [새 공지]·[수정]·[삭제]·첨부 업로드·삭제 버튼을 숨긴다(첨부 업로드·삭제는 **UPDATE** — U4). 서버 판정이 최종이며 화면을 연 사이 권한이 회수되면 다음 API가 403이다 — 메서드 계층 403의 `message`는 영문 `Access Denied`(`NoticeControllerTest`가 고정)라 공지 화면은 **403에 고정 한국어 문구**를 쓴다. DELETE만 가진 MANAGER가 첨부 있는 공지를 지우다 409를 받으면 첨부 삭제에 수정 권한이 필요하다는 안내 한 문장이 덧붙는다.
- 시험: `MemberPermissionServiceTest`(단위), `MemberPermissionControllerTest`(슬라이스), `MemberPermissionApiIntegrationTest`(실제 MariaDB — 저장 결과·감사 targetId·**실제 로그인 세션 재사용 즉시 반영**·커밋 직전 실패 주입·감사 저장 실패 격리·변형 행·대상 검증), `MemberPermissionConcurrencyIntegrationTest`(`INNODB_LOCK_WAITS` 락 대기 관측·동시 PUT 정확히 하나만 성공·PUT↔역할 변경 직렬화), `MemberRoleChangePermissionIntegrationTest`, `MemberPermissionMigrationTest`(V16→V19 복사·재배포 정리 SQL·V17/V18 실패 복구), `PermissionCacheTest`·`PermissionCacheIsolationIntegrationTest`, `AdminPermissionEvaluatorTest`(교차 회원 격리·식별 불가 주체), `AdminSidebarAdviceSnapshotTest`.

## 게시판별 권한 (`BOARD_SCOPED`, V26, 2026-10-08, `adversarial-review/plan/PLAN-board.md` PR A — 적대적 리뷰 7라운드 ship)

- **저장**: 별도 테이블 `member_board_permission(member_id, board_id, action)` PK 3컬럼 + `member`·`board` FK(RESTRICT). 기존 `member_permission`·공지 경로·V17~V22 시험을 건드리지 않으려고 분리했다. `action`은 VARCHAR(관대한 파싱), `utf8mb4_general_ci`라 변형 행 가드가 같다(`existsById` 충돌 → 409).
- **판정은 두 단계**: ① **기능 단위**(`allows(auth, BOARD, action)` — URL 게이트·사이드바·`/admin/board/posts` 메뉴)는 "어느 게시판에서라도 그 동작이 유효한가"(쓰기는 같은 게시판 READ 의존, `PermissionSnapshot.boardIds`). ② **게시판 단위**(`allowsBoard(auth, boardId, action)`·SpEL `checkBoard(boardId, action)`)는 그 게시판의 행 + 쓰기면 같은 게시판 READ. ADMIN은 둘 다 항상 true(DB 미조회). 게시판 존재·삭제 여부는 판정기가 보지 않는다(대상 404는 서비스 몫). 화면 버튼용 `boardActions(supplier, auth, boardId)`(공급자는 호출자가 memoize).
- **`@RequireBoardPermission(action)`**: 메타 `@PreAuthorize("@adminPermission.checkBoard(#boardId, '{action}')")` — 핸들러의 **경로 변수 이름이 반드시 `boardId`**다(이름이 다르면 null → 403, fail-closed). `-parameters`(Boot Gradle 플러그인 기본)에 기대며 `RequireBoardPermissionSpelTest`가 "게시판 A 허용·B 거부"로 고정한다(파라미터 이름 변이로 시험이 실패함을 확인).
- **게이트**(`AdminFeature.BOARD.gatePatterns`): `/admin/board/posts`, `/admin/api/boards/*/posts`, `/admin/api/boards/*/posts/**`, `/admin/api/boards/*/content-images` — `SecurityConfig`가 BOARD_SCOPED를 DELEGABLE과 같은 `featureReadGate`로 등록한다. 게시판 정의 경로(`/admin/api/boards`, `/admin/api/boards/{id}`, `/admin/board/manage`)는 게이트 밖이라 ADMIN 캐치올.
- **컨벤션 테스트 확장**: 게시판 게이트 안의 API는 `@RequireBoardPermission` + 경로의 `{boardId}`를 가져야 하고, `@RequireBoardPermission`은 게이트 밖에 쓸 수 없다(반례 시험 포함). 위임 기능 페이지의 `menuUrls` 규칙은 BOARD_SCOPED에도 적용된다. `@RequirePermission(BOARD, …)`은 위임 불가 판정으로 위반이다.
- **캐시**: `PermissionCache`가 **한 REQUIRES_NEW 읽기 트랜잭션**에서 두 테이블을 차례로 읽어 스냅샷 하나를 만든다(같은 REPEATABLE READ 시점). 어느 쪽이든 실패하면 전체 fail-closed. 모르는 동작 게시판 행은 무시.
- **권한관리 PUT 확장**: 요청에 `boardGrants: [{boardId, action}]` **필수**(빈 배열 허용, 누락 400 — 이 필드를 모르는 낡은 화면이 게시판 권한을 전부 회수하지 못하게). 검사 순서는 기존 + "버전 뒤 요청 게시판 `FOR SHARE` 잠금·존재·미삭제 확인(400)". 게시판 권한 **회수는 키 단위 JPQL 벌크 삭제**(`deleteKey`, 멱등 — 회수 대상 게시판이 동시에 삭제돼도 stale-state 500이 나지 않는다). `innodb_snapshot_isolation=ON`이면 그 경합이 1020 → 409로 전체 롤백될 수 있다(허용). 응답 `boards`는 미삭제 게시판 전부(권한 없는 게시판도 행으로). 버전·잠금·이벤트·알림·감사는 기능 권한과 공유한다(한 PUT·한 버전).
- **감사 라벨·알림 형식(전 권한 공통으로 바뀜)**: `v3→v4: 추가 n·회수 m | -회수항목, … | +추가항목, …`(건수 → 회수 → 추가 — 500자에서 잘려도 건수와 잃은 권한이 먼저 남는다, 넘으면 `…`). 게시판 항목은 `게시판#3.조회`(이름은 사용자 입력이라 넣지 않는다). 알림은 `권한이 변경되었습니다(추가 n·회수 m): -…, +…`(255자, 같은 순서).
- **전체 diff 로그**(INFO, `MemberPermissionService.logAfterCompletion`): 트랜잭션 완료 후 `권한 변경 <결과>: actorMemberId=<숫자>, memberId=…, vN→vM, 회수=[…], 추가=[…]`. **결과 판정 주의**: Spring은 커밋 단계 예외(DB는 커밋됐지만 응답 유실 포함)에서 `afterCompletion(STATUS_ROLLED_BACK)`을 넘긴다(실측) — 그래서 동기화의 `beforeCommit` 호출 여부를 함께 보아 커밋 시도 전 롤백만 `ROLLED_BACK`, 커밋 시도 이후 비커밋은 `UNKNOWN(적용 여부 DB 확인 필요)`로 쓴다. `MemberPermissionApiIntegrationTest`가 COMMITTED·ROLLED_BACK(커밋 직전 실패)·UNKNOWN(커밋 응답 유실)을 고정한다.
- **역할 변경**: `AdminMemberService.updateAdminMember`가 `member_board_permission`도 같은 트랜잭션에서 지운다(이벤트는 두 테이블 합계가 0보다 클 때). **롤백 후 재배포 정리 SQL**에 `DELETE FROM member_board_permission;`이 포함된다(`docs/deployment.md`, `MemberPermissionMigrationTest`). 테스트 정리(`TestMembers.delete`)도 이 테이블을 먼저 지운다.
- **화면**: 권한관리 매트릭스 아래 "게시판별 권한" 표(게시판명 `textContent`, 게시판마다 READ 연동). 기능 표에서 `BOARD_SCOPED` 행은 그리지 않는다.
- **게시판 삭제와의 상호작용(PR B)**: 게시판 삭제는 그 게시판의 `member_board_permission` 행을 전부 지우고(`deleteByBoardId`) 캐시를 폐기하며 권한 버전은 올리지 않는다. 같은 회원의 회수 PUT과 겹치면 회수는 키 단위 벌크 삭제라 `innodb_snapshot_isolation=OFF`에서는 0건으로 성공(200), `ON`에서는 1020 → **409**로 PUT 전체가 롤백된다(버전 불변, 재조회 후 재저장은 성공) — `AbstractBoardDeleteRaceTest`가 두 설정에서 래치로 고정한다. 통합 검색의 게시글 섹션은 `readableBoardIds(snapshot, auth)`(MANAGER의 READ 유효 게시판 집합, ADMIN은 빈 집합이라 호출자가 전체로 취급)로 쿼리를 한정한다.
