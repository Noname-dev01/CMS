# CLAUDE.md — com.cms.admin.permission

이 디렉터리(관리자 권한 판정 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조.
계획서: `adversarial-review/plan/PLAN-menu-permission-management.md`(적대적 리뷰 3라운드 ship). 이 패키지는 PR ①(2026-10-02)에서 도입됐고 사이드바 연동은 PR ②, 권한관리 화면·API는 후속 PR이다.

## 개념

MANAGER가 무엇을 할 수 있는지는 **코드 카탈로그(`AdminFeature`) ∩ DB 허용 행(`role_permission`) ∩ 의존 규칙**으로 정해지고, **코드가 최종 권위**다 — DB에 위임 불가 기능 행이 수동으로 들어가도 판정기는 무시한다. 역할은 ADMIN·MANAGER 2종뿐이며 새 역할은 범위 밖이다.

| 기능 | 종류 | 설명 |
|---|---|---|
| `DASHBOARD`, `MY_INFO` | `ALWAYS` | 로그인한 ADMIN·MANAGER 전원 상시 허용. DB를 보지 않고 끌 수 없다(로그인 직후 `/admin`으로 이동하므로 대시보드를 끄면 403이 난다) |
| `NOTICE` | `DELEGABLE` | ADMIN은 항상, MANAGER는 (기능, 동작) 허용 행이 있을 때만. 동작 `READ·CREATE·UPDATE·DELETE` |
| `MEMBER`, `MENU`, `ACTION_LOG`, `PERMISSION` | `ADMIN_ONLY` | 위임 불가. `PERMISSION`을 `DELEGABLE`로 바꾸면 `AdminFeature` 생성자가 **클래스 로딩 시점에 예외**를 던진다(자기 승격 차단) |

## 판정 (`AdminPermissionEvaluator`, 빈 이름 `adminPermission`)

유일한 판정 함수다. URL 게이트(`SecurityConfig`)와 메서드 어노테이션(`@RequirePermission`)이 **같은 함수**로 판정해 어긋나지 않는다.

- 익명·인증 없음 → false / `ROLE_ADMIN` → **항상 true(DB 미조회 — 캐시 장애·어떤 저장으로도 ADMIN은 권한을 잃지 않는다)** / `ALWAYS` → ADMIN·MANAGER true / `ADMIN_ONLY` → false / `DELEGABLE` → `ROLE_MANAGER`이고 허용 행이 있으며 **쓰기 동작(CREATE·UPDATE·DELETE)은 READ도 있어야** true.
- 역할은 세션 `Authentication` 권한에서 읽는다(기존 URL 규칙과 같은 출처, `@WithMockUser`도 동작).
- `check(String, String)`은 SpEL 진입점이다. 이름을 enum으로 파싱하지 못하면(null 포함) **false(fail-closed)**.
- 한 요청에서 여러 번 판정할 때는 `snapshot()`을 한 번 받아 `allows(snapshot, ...)` 오버로드에 넘긴다(중간에 무효화돼도 한 요청 안에서 같은 권한 버전을 쓴다).

## 두 계층 인가 (`SecurityConfig` + `@RequirePermission`)

- **URL 게이트(필터)**: 카탈로그의 `gatePatterns`를 순회해 `ALWAYS`는 `hasAnyRole('ADMIN','MANAGER')`, `DELEGABLE`은 `featureReadGate`(ADMIN 또는 해당 기능 READ 허용 MANAGER)로 등록한다. **기능 단위 READ 게이트**라 HTTP 메서드를 구분하지 않는다. 규칙 순서는 기존과 같다(공개·swagger → 카탈로그 게이트 → `/admin/**` ADMIN 캐치올 → … → `anyRequest().denyAll()`). 카탈로그에 없는 `/admin/**`는 캐치올에 걸려 ADMIN 전용이다.
- **메서드 계층**: `@RequirePermission(feature, action)`이 메타 `@PreAuthorize("@adminPermission.check('{feature}', '{action}')")`를 감싼다(`MethodSecurityConfig`의 `AnnotationTemplateExpressionDefaults`가 자리표시자를 치환 — enum 속성 치환은 테스트로 확인됨). 위임 가능 기능의 핸들러에만 붙인다. **페이지 컨트롤러에는 붙이지 않는다** — `GlobalApiExceptionHandler`가 범위 제한 없는 `@RestControllerAdvice`라 페이지에 메서드 보안을 걸면 HTML이 아니라 JSON 403이 나가므로, 페이지 차단은 URL 게이트가 HTML 403으로 낸다.
- 공지 핸들러 분류(U4): 목록·상세·첨부 목록·다운로드 = READ, 생성 = CREATE, 수정·**첨부 업로드·첨부 삭제 = UPDATE**, 삭제 = DELETE. 부작용: DELETE만 가진 MANAGER는 첨부가 있는 공지를 지울 수 없다(기존 409 규칙).

## 컨벤션 테스트 (CI 잠금)

`AdminEndpointAuthorizationConventionTest`가 `com.cms.admin` 컨트롤러를 스캔해 `/admin` 아래 핸들러를 검사한다. **읽기 전용 GET/HEAD 페이지 핸들러만 면제**하고, 그 밖의 모든 핸들러(경로에 `/api/`가 있든 없든, 메서드 제한 없는 `@RequestMapping` 포함)와 모든 API는 정확히 하나의 인가 선언(`@RequirePermission` | `hasRole('ADMIN')` | ALWAYS 경로 한정 `hasAnyRole('ADMIN','MANAGER')`)을 가져야 한다 — URL 게이트가 HTTP 메서드를 구분하지 않아 READ 게이트만 통과하면 같은 경로의 쓰기 핸들러까지 열리기 때문이다. 새 엔드포인트를 선언 없이 추가하면 CI가 실패한다. 규칙이 실제로 위반을 잡는지는 반례 테스트로 고정돼 있다.

`AdminPermissionMatrixIntegrationTest`(실제 SecurityConfig·판정기·캐시·MariaDB)는 공지 핸들러 9개 × MANAGER 권한 조합(전부·없음·READ만·READ+CREATE·READ+UPDATE·READ+DELETE·READ 없는 쓰기)을 시험한다. 허용은 **기대 성공 상태와 실제 저장 결과**, 거부는 403(API JSON `ACCESS_DENIED`)과 **변경 없음**으로 단언하고, 경계 입력(후행 슬래시·`;`·`/./`·HEAD·OPTIONS)도 확인한다. 변이 실험으로 "삭제를 UPDATE로 오표시"가 이 시험에서 실패함을 확인했다.

## 캐시 (`RolePermissionCache`)

- generation 카운터 + 단일 비행 로드. `invalidate()`(실패할 수 없는 메모리 연산)가 올린 뒤 로드는 시작 전 generation과 같을 때만 설치한다 — 로드 도중 무효화가 끼면 이번 요청만 그 결과로 판정하고 다음 요청이 다시 로드한다.
- **로드는 호출자 트랜잭션과 격리된 `REQUIRES_NEW` 읽기 트랜잭션**에서 한다(`RolePermissionCacheIsolationIntegrationTest`가 실제 MariaDB 교차 실행으로 고정 — 로드가 호출자 트랜잭션에 참여하면 REPEATABLE READ의 낡은 스냅샷이 "최신"으로 설치돼 회수된 권한이 계속 허용된다. 변이 실험으로 확인).
- **로드 실패 = fail-closed**: ERROR 로그 후 빈 스냅샷(MANAGER 위임 기능 전부 거부)으로 판정하고 실패는 캐시하지 않는다. ADMIN·상시 허용 기능은 DB를 보지 않아 영향이 없다.
- 모르는 기능·동작 행, 위임 불가 기능 행은 WARN 후 무시한다(코드에서 기능을 지웠는데 행이 남아도 로딩 전체가 실패하지 않는다). 기능을 카탈로그에서 제거할 때는 행 삭제 마이그레이션을 함께 쓴다.
- **단일 인스턴스 전제**(세션 레지스트리·레이트리밋과 같은 한계). 다중 인스턴스로 확장하면 다른 인스턴스 캐시가 무효화 신호를 받지 못해 낡아진다 — 그때는 DB 버전 폴링 또는 캐시 제거가 필요하다.

## 스키마 (V13 DDL / V14 시드)

- `permission_role(role PK, version, update_date)`: 권한 매트릭스를 가진 역할의 기준 행 + 낙관적 버전. 허용 행이 하나도 없는 빈 집합에서도 잠글 대상이 있도록 둔다(권한관리 PUT이 후속 PR에서 사용). 지금은 `ROLE_MANAGER` 한 행.
- `role_permission(role, feature, action)` PK 3컬럼 + `permission_role` FK. **행이 있으면 허용**(거부 행 없음), ADMIN 행 없음. 컬럼은 `VARCHAR`(DB enum 아님).
- **V14는 일회성 초기화이지 복구 수단이 아니다**: Flyway는 성공한 V14를 다시 실행하지 않고, 수동으로 다시 실행하면 `WHERE NOT EXISTS`가 ADMIN이 회수한 권한을 되살린다(`PermissionMigrationTest`가 고정). 누락된 권한의 복구는 권한관리 화면/API로만 한다. V13의 `CREATE TABLE` 2개 중 첫 번째만 성공하고 실패하면(MariaDB DDL 암묵 커밋) 두 테이블 존재 여부를 확인해 존재하는 것을 수동 `DROP`(시드 전이라 안전)한 뒤 `flyway repair` 후 재기동한다.
- 마이그레이션 시드 U1(a): MANAGER × NOTICE × 4동작으로 오늘의 접근 범위를 그대로 옮겨 배포 직후 동작 회귀가 없다.

## 롤백 주의 (계획서 "롤백 호환표")

판정기 이전 앱(이 PR 이전)은 MANAGER 공지 권한을 코드에 고정하므로, ADMIN이 권한을 회수한 뒤 그 앱으로 되돌리면 **DB에 회수 결과가 남아 있어도 MANAGER의 공지 CRUD가 전부 되살아난다**(재로그인과 무관). 권한 회수 이력이 있으면 되돌리기보다 roll-forward(수정 버전 배포)를 기본으로 한다. 이 PR(①) 자체는 회수 UI가 없어 시드 그대로이므로 되돌려도 동작은 오늘과 같다.

## 사이드바 연동 (PR ②, 2026-10-02)

사이드바 노출은 이 판정기에서 도출된다 — `menuUrlVisibility(Supplier<snapshot>, authentication)`(ADMIN 항상 true, MANAGER는 `AdminFeature.forMenuUrl`로 URL→기능을 완전 일치로 찾아 READ 판정, 미분류·null은 false)와 메뉴 관리 화면용 `managerMenuUrlVisibility(snapshot)`. 가지치기·노출 안내 규칙은 `com.cms.admin.menu`의 `CLAUDE.md` "노출 계산" 참조. 그래서 MANAGER에게 **보이는 링크는 항상 READ가 허용된 기능의 URL**이고 그 URL의 게이트도 같은 판정이라 "보이는데 403"은 구조적으로 생기지 않는다(역은 가능 — 메뉴가 없거나 비활성). `menu.access_role` 컬럼은 존치하지만 엔티티가 매핑하지 않는다(DROP은 PR ④).

## 아직 없는 것 (후속 PR)

권한관리 화면·API(`PUT /admin/api/roles/{role}/permissions`)·`PERMISSION_UPDATE` 감사·V15 메뉴 시드·공지 화면 버튼 숨김(③), `access_role` 컬럼 DROP(④).
