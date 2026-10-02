# PLAN — 권한관리 PR ③ `feat/permission-management` 구현 계획

> 상태: v4 (2026-10-02) — 적대적 리뷰 4라운드 ship, 구현 대기. 상위 계획 `PLAN-menu-permission-management.md`(v4, 적대적 리뷰 3라운드 ship, D1~D6·U1~U8 확정)의 PR ③ 실행 계획
>
> **개정 이력**
> - 4라운드: **ship, 신규 지적 0건**(정적 계획 리뷰 — 빌드·테스트·브라우저 미실행). `fa-user-lock` 아이콘이 번들 CSS에 존재함을 확인(§5-4 미확인 항목 1건 해소)
> - v4 변경(3라운드 needs-attention, 신규 지적 2건 전부 수용, 결정 필요 0):
>   - R-12 수용: 충돌 가드가 `(feature, action)` 대소문자만 봐서 역할 소문자·동작 후행 공백 변형을 놓침 → 자바 비교 대신 **DB PK 동등 비교**(`existsById`)로 추가 키마다 충돌을 탐지하고, 현재 행은 역할 문자열까지 정확히 일치하는 파싱 가능 행만 인정(§5-B, §7-1 ⑧). `general_ci` 직접 근거는 `V13:30`
>   - R-13 수용: 롤백 확인을 원시 행 수가 아니라 **정확 일치(BINARY) 쿼리로 NOTICE 유효 4동작 존재 여부**로 바꾸고, 이력이 불확실하면 승인 또는 roll-forward 적용(§8)
> - v3 변경(2라운드 needs-attention, 신규 지적 3건 전부 수용, 결정 필요 0):
>   - R-9 수용: 감사 저장은 최선 노력이라 "감사에 없음 ≠ 회수 안 함" → 롤백 절차에 현재 DB 유효 허용 집합 조회 병행 + 감사 저장 실패 시에도 변경·무효화 유지 시험(§8, §7-1 ⑦)
>   - R-10 수용(V13 `general_ci` 확인): `read` 같은 대소문자 변형 행이 정상 키 `READ`와 PK 충돌 → 잠금 뒤 탐지해 409 + 운영 정리 안내, 시험 추가(§5-B, §7-1)
>   - R-11 수용: `ROLE_X` 미포함 단언을 `$.message` 한정, `$.path`는 기존 계약대로 별도 검증(§7-1)
>   - 정정(비차단): F3 근거는 `V14:12,16`, F13 추적 파일은 PLAN 27개 + README 1개
> - v2 변경(1라운드 needs-attention, 지적 8건 중 수용 7·부분 수용 1, 결정 필요 0):
>   - R-1 수용: V15 `ord`가 INT 상한에서 넘침 → `LEAST(…, 2147483647)`(표시 순서가 `ord`, `menuNo`라 맨 끝 유지) + 상한 업그레이드 테스트(§5-4, §7-1)
>   - R-2 수용: 외부 롤백 시험은 감사 SUCCESS 독립 커밋을 못 잡음 → 무효화 0회로 범위 한정 + 실제 최상위 커밋 실패 주입 시험 추가(§7-1 ⑤)
>   - R-3 수용(Aspect 확인): FAIL 감사는 `targetLabel`이 항상 null → 라벨 완료 기준을 성공·변경 없음으로 한정(§5-B, §10)
>   - R-4 수용: 저장 중 체크박스 잠금(§5-1)
>   - R-5 수용: 즉시 반영 시험은 로그인 세션 재사용, playwright는 역할별 별도 browser context(§7-1 ④, §7-4)
>   - R-6 수용: 스냅샷 1회 보증을 "Advice 내부 공유"로 한정(§5-3, §7-2)
>   - R-7 수용(직접 확인): F12 재계수 정정 — 현재 4개 서비스·12개 메서드, 이 PR 후 5개·13개
>   - R-8 부분 수용: 미지원 동작 분기는 현재 카탈로그에서 도달 불가한 방어 분기임을 명시, 테스트용 카탈로그 추상화는 만들지 않음(§7-1)
> - v1: 초안
>
> 근거: **정적 정찰 기준**(코드·테스트·문서를 열어 대조, 빌드·테스트 미실행). master = `9c651b8`(PR ① #81·PR ② #82 머지 완료)
> 유형: feat · 인가 정책 변경 없음(상위 계획에서 협의 완료, `SecurityConfig` 변경 없음 — §보안 점검표) · 스키마 변경 없음(V15는 DML 시드만) · 신규 의존성 없음

## 0. 요약

ADMIN이 `권한 관리` 화면에서 MANAGER의 공지사항 권한(조회·생성·수정·삭제)을 켜고 끄는 API 2종과 화면을 추가하고, 저장이 커밋되면 캐시를 무효화해 **같은 세션의 다음 요청부터** 접근·사이드바·공지 화면 버튼이 함께 바뀌게 한다. 상위 계획의 확정 결정은 바꾸지 않는다.

---

## 1. 정찰 사실 표 (상위 계획 가정 ↔ 현재 코드)

### 1-A. 상위 계획과 다르거나 상위 계획이 정하지 않은 점

| # | 상위 계획 가정 | 현재 코드 사실 | 근거 | 이 계획의 처리 |
|---|---|---|---|---|
| F1 | 패키지 `CLAUDE.md` **신설**(단계표 ③ 완료 기준) | 이미 PR ①에서 만들어졌고 "아직 없는 것" 절에 ③ 항목이 적혀 있다 | `admin/permission/CLAUDE.md:1-4,60-62` | **신설 → 갱신**(§4 단계 6) |
| F2 | "최신 마이그레이션 V12"(사실 C) | 최신은 **V14**, V15는 비어 있다 | `db/migration/V13__…`, `V14__…` | V15 사용, 머지 직전 재확인 |
| F3 | V15는 "V9 패턴"(V9는 `create_date`·`update_date`에 DB `NOW()`) | 2026-09-30 Clock 단일화 이후 새 마이그레이션은 DB `NOW()`를 피한다 — V14는 같은 이유로 `update_date`를 **NULL**로 둠. `menu.create_date`·`update_date`는 nullable이고 메뉴 화면은 날짜를 표시하지 않는다 | `V9:15-16`, `V14:12,16`, `V1:39,46`, 루트 `CLAUDE.md`(Clock 절 "한계: V3·V9는 NOW()"), `menu/manage.html`(createDate 참조 0건) | V15의 날짜 두 컬럼은 **NULL**(V14 선례). 나머지는 V9 패턴(`WHERE NOT EXISTS`) 유지 |
| F4 | `PermissionMigrationTest`는 영향 없음(테스트 영향 표에 없음) | V12→최신 업그레이드 후 **메뉴 행 수가 그대로인지** 단언한다 → V15가 메뉴 1행을 넣으면 **실패** | `PermissionMigrationTest.java:46-55,87-90` | 단계 4에서 해당 단언을 `target("14")`까지로 한정하거나 기대값을 `+1`(권한 관리 메뉴)로 고친다 — 아래 §7 |
| F5 | `PermissionRole`로 잠금·버전 증가 | 엔티티에 변경 메서드가 없고 Javadoc이 "이 PR에서는 조회·잠금 경로가 없다"고 적혀 있다. `PermissionRoleRepository`가 **없다** | `PermissionRole.java:12-29`(특히 :15), `RolePermissionRepository.java:8-13` | 도메인 메서드·잠금 리포지토리 추가, Javadoc 갱신 |
| F6 | 감사 라벨 `+공지사항.생성` | `AdminFeature`에는 한국어 라벨이 있지만(`"공지사항"`) `PermissionAction`에는 **라벨이 없다** | `AdminFeature.java:33`, `PermissionAction.java:4-9` | `PermissionAction`에 라벨(조회/생성/수정/삭제) 필드 추가 — 감사 라벨·API 응답·화면 열 머리글의 단일 출처 |
| F7 | `AdminSidebarAdvice`가 요청당 스냅샷 1개로 사이드바·`myPermissions`를 만든다 | 지금은 `@ModelAttribute("sidebarMenus")` 하나가 `adminPermissionEvaluator::snapshot` **공급자**를 넘긴다(MANAGER일 때만 지연 조회). 같은 공급자를 별도 `@ModelAttribute` 메서드에서 또 부르면 **스냅샷을 두 번** 받는다 | `AdminSidebarAdvice.java:33-42`, `AdminPermissionEvaluator.java:74-85` | 두 속성을 **한 `@ModelAttribute` 메서드**에서 같은 메모이즈 공급자로 계산(§5-3) |
| F8 | 결과 객체에 `@JsonIgnore getAuditLabel()`(MenuDeleteResult 선례) | `MenuDeleteResult`는 직렬화되지 않으므로 `@JsonIgnore`가 없다. 감사 어노테이션은 컨트롤러가 아니라 **서비스 메서드**에 붙는다 | `MenuDeleteResult.java:10-30`, `MenuService.java:152-155,187-189` | 서비스가 내부 결과 객체(응답 DTO + `getAuditLabel()`)를 반환, 컨트롤러는 응답 DTO만 꺼내 반환 → `@JsonIgnore` 불필요 |
| F9 | 권한 회수 뒤 공지 API 403 → `extractErrorMessage`가 "권한이 없습니다" 표시 | 필터 계층 403(`ApiAccessDeniedHandler`)은 고정 문구 "권한이 없습니다."지만, **메서드 계층 403**(READ는 있고 CREATE가 없는 경우 등)은 `GlobalApiExceptionHandler.handleAccessDenied`가 `e.getMessage()`를 그대로 쓴다 — Spring Security 기본 메시지(영문 "Access Denied"로 추정) | `ApiAccessDeniedHandler.java:20-24`, `GlobalApiExceptionHandler.java:330-339` | **미확인** — 확인 방법: `NoticeControllerTest`에 READ만 가진 MANAGER의 `POST` 케이스를 추가해 `$.message`를 단언. 결과와 무관하게 공지 화면 JS가 **403이면 고정 한국어 문구**를 쓰도록 한다(서버 핸들러는 건드리지 않음 — 범위 밖) |
| F10 | 409 코드 | `api-conventions` 스킬은 409를 `DUPLICATE_RESOURCE`로 적지만 `ConflictException`은 **`RESOURCE_CONFLICT`**, 락 대기 실패(`PessimisticLockingFailureException`)도 409 `RESOURCE_CONFLICT` | `GlobalApiExceptionHandler.java:271-276,284-289` | 버전 충돌은 `ConflictException` → 409 `RESOURCE_CONFLICT`(메뉴 구조 반영과 동일) |
| F11 | U4 안내는 "기존 문구 재사용" | 기존 409 문구는 "첨부파일이 남아있어 삭제할 수 없습니다. 첨부를 먼저 삭제해주세요." — 그런데 UPDATE가 없는 MANAGER는 첨부 삭제 버튼이 숨겨져 **따를 수 없는 안내**가 된다 | `NoticeService.java:97` | 기존 문구를 그대로 표시하고, `!canUpdate`일 때만 한 문장("첨부 삭제에는 공지 수정 권한이 필요합니다. 관리자에게 요청하세요.")을 덧붙인다(U4 "화면에 안내" 범위) |
| F12 | — | `log/CLAUDE.md`의 "감사 대상 4개 서비스 12개 메서드"(v1은 이를 낡았다고 잘못 판단 — 리뷰 R-7로 정정) | `log/CLAUDE.md:9`, Grep `@AdminActionLogged`(v2 재확인) | 재계수 결과 현재 **4개 서비스·12개 메서드**(AdminMember 3·Menu 4·Notice 3·NoticeAttachment 2 — `PasswordResetService`는 공개 흐름이라 `@AdminActionLogged`를 쓰지 않음, `PasswordResetService.java:38`)라 문장은 사실상 맞다. 이 PR이 `RolePermissionService.replace` 1개를 추가하므로 **5개 서비스·13개 메서드**로 갱신(구현 시 Grep으로 재계수) |
| F13 | — | `adversarial-review/plan/*.md`는 `.gitignore` 대상이 **아니다**(무시 규칙은 이미지 3종뿐, PLAN 27개 + README 1개가 추적 중이며 상위 계획도 #81에 포함) | `.gitignore:59-61`, `git ls-files adversarial-review/plan` | 이 문서를 PR에 포함할지는 사용자 판단(§9) |

### 1-B. 그대로 확인된 전제

| 사실 | 근거 |
|---|---|
| `PERMISSION`은 ADMIN_ONLY, `menuUrls=/admin/permission/manage`, `gatePatterns` 비어 있음 → `/admin/**` ADMIN 캐치올. 생성자 가드가 DELEGABLE 변경을 막음 | `AdminFeature.java:46-48,58-60` |
| 판정기: ADMIN 항상 true(DB 미조회), DELEGABLE은 READ 의존 규칙, `allows(snapshot, …)` 오버로드 존재 | `AdminPermissionEvaluator.java:45-58,111-117` |
| 캐시: `invalidate()`는 메모리 연산, 로드는 REQUIRES_NEW 읽기 트랜잭션, 위임 불가·모르는 행은 무시 | `RolePermissionCache.java:54-57,59-77,80-97` |
| 역할 enum `ROLE_ADMIN, ROLE_MANAGER, ROLE_USER` | `member/domain/Role.java:4` |
| 경로 변수 타입 변환 실패 → 400 `INVALID_REQUEST` 고정 문구(입력 원문 미반영), JSON enum 파싱 실패 → 400 `JSON_PARSE_ERROR` | `GlobalApiExceptionHandler.java:147-152,202-208` |
| AFTER_COMMIT 이벤트 리스너 선례 | `config/auth/AdminSessionRevokeListener.java:28-29` |
| 감사 상수 단일 출처 + 화면 라벨 동기화 테스트 2종(`ALL`에 없는 actionType 사용 금지 / `ALL` 전부가 `log/manage.html` `ACTION_TYPE_LABELS`에 있어야 함) | `AdminActionTypes.java:35-39`, `log/manage.html:163-180`, `AdminActionTypeSyncTest`, `AdminActionTypeLabelSyncTest` |
| CSRF: `head.html`이 `_csrf`·`_csrf_header` 메타를 렌더링, 공지 화면은 `getCsrfHeaders()`/`getCsrfHeaderOnly()`로 읽음 | `fragments/head.html:9-10`, `notice/manage.html:321-341` |
| 메뉴 화면 선례: 409면 서버 메시지 + 초안 폐기·재조회, 그 외(400)는 초안 유지, `beforeunload` 경고 | `menu/manage.html:809-817,1215-1220` |
| Thymeleaf JS 인라인 선례 | `admin/index.html:190` |
| 공지 화면 버튼: `btnNewNotice`(:69), 첨부 업로드(:232-236), 첨부 삭제(렌더 :635), `btnDelete`·`btnEditMode`·`btnSave`(:242-246). `showViewMode()`가 수정·삭제 버튼을 **무조건** 보이게 함(:545-555) | `notice/manage.html` |
| 컨벤션 테스트: `/admin` 아래 GET 전용 페이지는 면제, API는 `hasRole('ADMIN')` 등 정확히 하나의 선언 필요 | `AdminEndpointAuthorizationConventionTest.java:71-77`, `permission/CLAUDE.md:33` |
| 동시성 테스트 도구: `INNODB_LOCK_WAITS` 관측으로 실제 락 대기 확인(`assertDatabaseLockWait`·`awaitSomeoneWaitingFor`), 잠금 보유 트랜잭션 헬퍼 | `MenuConcurrencyIntegrationTest.java:340-368,474-510` |
| 노출 안내·사이드바 대조 통합 테스트(시드 메뉴 사용, V15 메뉴가 생겨도 규칙상 일치) | `MenuExposureSidebarIntegrationTest.java:89-139` |
| 슬라이스 테스트용 판정기 설정(시드 스냅샷 고정 캐시 목) | `config/PermissionTestConfig.java` |

---

## 2. 범위와 비범위

**범위 (PR ③)**

1. 권한관리 API 2종 `GET`/`PUT /admin/api/roles/{role}/permissions` + 서비스 + DTO + `PermissionChangedEvent`·AFTER_COMMIT 캐시 무효화 리스너
2. 감사 `PERMISSION_UPDATE` 상수 + `ALL` + `log/manage.html` 라벨("권한 변경")
3. 페이지 컨트롤러 `GET /admin/permission/manage`(`@AdminPage`) + `templates/admin/permission/manage.html`
4. V15 권한 관리 메뉴 시드(DML)
5. `AdminSidebarAdvice`의 `myPermissions` 모델 속성 + 판정기 보조 메서드 1개
6. `notice/manage.html` 버튼 숨김·403 고정 문구·U4 안내
7. `docs/deployment.md` 권한관리 롤백 절차, `permission`·`config`·`log` 패키지 `CLAUDE.md`와 루트 `CLAUDE.md` 지침 지도 한 줄 갱신
8. 위 항목의 테스트, playwright 화면 검증

**비범위**

- `ALTER TABLE menu DROP COLUMN access_role`(V16)·잔여 문서 정리 → PR ④
- 새 역할·역할 선택 UI(D5), 위임 가능 기능 추가(U3 — NOTICE만), 감사 로그 화면 개편
- `GlobalApiExceptionHandler`의 403 메시지 변경(F9 — 화면에서 흡수)
- `SecurityConfig` 변경(§6에서 불필요 근거)

---

## 3. 파일 단위 변경 목록

| 구분 | 파일 | 변경 |
|---|---|---|
| 수정 | `admin/permission/PermissionAction.java` | 한국어 라벨 필드·getter(`READ 조회`, `CREATE 생성`, `UPDATE 수정`, `DELETE 삭제`) |
| 수정 | `admin/permission/PermissionRole.java` | 도메인 메서드 `increaseVersion(LocalDateTime now)`(version+1, updateDate=now). Javadoc의 "조회·잠금 경로 없음" 문장 갱신. `@Setter` 금지 유지 |
| 신규 | `admin/permission/PermissionRoleRepository.java` | `@Lock(PESSIMISTIC_WRITE) @Query("select p from PermissionRole p where p.role = :role") Optional<PermissionRole> findByIdForUpdate(String role)` |
| 수정 | `admin/permission/RolePermissionRepository.java` | `List<RolePermission> findByRole(String role)` 추가 |
| 신규 | `admin/permission/PermissionChangedEvent.java` | `record PermissionChangedEvent(String role)` |
| 신규 | `admin/permission/PermissionChangedListener.java` | `@TransactionalEventListener(AFTER_COMMIT)` → `RolePermissionCache.invalidate()` |
| 수정 | `admin/permission/AdminPermissionEvaluator.java` | `Set<String> grantedActionKeys(Supplier<PermissionSnapshot>, Authentication)` 추가(§5-3) |
| 신규 | `admin/permission/service/RolePermissionService.java` | 조회·교체(§5-B) |
| 신규 | `admin/permission/service/RolePermissionUpdateResult.java` | 내부 결과(응답 DTO + `getAuditLabel()`), 직렬화하지 않음 |
| 신규 | `admin/permission/dto/request/RolePermissionUpdateRequest.java` | `version`, `grants[{feature, action}]` |
| 신규 | `admin/permission/dto/response/RolePermissionMatrixResponse.java` | 역할·버전·기능 행·동작 열 |
| 신규 | `admin/permission/controller/RolePermissionController.java` | API 2종, `@PreAuthorize("hasRole('ADMIN')")`, SpringDoc 어노테이션 |
| 신규 | `admin/permission/controller/PermissionPageController.java` | `@Controller @AdminPage @RequestMapping("/admin/permission")`, `@GetMapping("/manage")` → `admin/permission/manage` |
| 신규 | `templates/admin/permission/manage.html` | 권한 매트릭스 화면(§5-1) |
| 수정 | `admin/log/constant/AdminActionTypes.java` | `PERMISSION_UPDATE` 상수 + `ALL` |
| 수정 | `templates/admin/log/manage.html` | `ACTION_TYPE_LABELS`에 `PERMISSION_UPDATE: "권한 변경"` |
| 신규 | `db/migration/V15__seed_permission_menu.sql` | §5-4 |
| 수정 | `admin/AdminSidebarAdvice.java` | 단일 `@ModelAttribute` 메서드로 `sidebarMenus`·`myPermissions` 계산(§5-3) |
| 수정 | `templates/admin/notice/manage.html` | §5-2 |
| 수정 | `docs/deployment.md` | "권한관리 롤백 주의" 절 신설(§8) |
| 수정 | `admin/permission/CLAUDE.md`, `config/CLAUDE.md`, `admin/log/CLAUDE.md`, 루트 `CLAUDE.md` | §4 단계 6 |
| 테스트 | §7 표 | 신규 5개 파일 + 기존 6개 수정 |

패키지 배치: 판정 핵심(카탈로그·판정기·캐시·엔티티·리포지토리·이벤트)은 기존처럼 `com.cms.admin.permission` 평면에 두고, 컨트롤러·서비스·DTO는 `menu`·`notice`와 같은 하위 패키지(`controller`·`service`·`dto.request`·`dto.response`)에 둔다. 의존 방향 Controller → Service → Repository → Entity 유지.

---

## 4. 구현 순서 (단계마다 컴파일·`./gradlew test` 통과)

| 단계 | 내용 | 테스트 | 통과 근거 |
|---|---|---|---|
| 1 | 도메인 보강: `PermissionAction` 라벨, `PermissionRole.increaseVersion`, `PermissionRoleRepository`, `RolePermissionRepository.findByRole`, `PERMISSION_UPDATE` 상수·`ALL`·`log/manage.html` 라벨 | `AdminActionTypeSyncTest`·`LabelSyncTest` 통과 확인 | 사용처 없는 상수도 두 동기화 테스트를 만족(라벨 동시 추가) |
| 2 | 서비스·DTO·결과 객체·이벤트·리스너 | `RolePermissionServiceTest`(단위) | 컨트롤러가 없어 경로 변화 없음 |
| 3 | `RolePermissionController` | `RolePermissionControllerTest`(슬라이스), `RolePermissionApiIntegrationTest`(실제 MariaDB: 감사·즉시 반영·무효화), `RolePermissionConcurrencyIntegrationTest`, 매트릭스 테스트 경로 추가 | 새 핸들러가 `hasRole('ADMIN')`이라 컨벤션 테스트 통과, `/admin/**` 캐치올로 MANAGER 차단 |
| 4 | `PermissionPageController` + `permission/manage.html` + V15 | `PermissionMigrationTest` 조정 + V15 테스트, `AdminSidebarAdviceTest` 페이지 추가, `MenuExposureSidebarIntegrationTest` 단언 1줄 | GET 전용 페이지는 컨벤션 면제, `@AdminPage`로 `AdminPageAnnotationConventionTest` 통과 |
| 5 | `grantedActionKeys` + `AdminSidebarAdvice` `myPermissions` + `notice/manage.html` | `AdminPermissionEvaluatorTest` 추가, 사이드바 Advice 스냅샷 1회 테스트, 즉시 반영 통합 테스트의 공지 페이지 모델 단언 | 기존 슬라이스의 목 판정기는 `Set` 반환에 빈 집합을 돌려줌(Mockito 기본값) → 기존 페이지 테스트 영향 없음 |
| 6 | 문서: `docs/deployment.md`, `permission/CLAUDE.md`("아직 없는 것"에서 ③ 제거 + "권한관리 API·화면" 절), `config/CLAUDE.md`(`/admin/notice/**` 행의 "권한관리(후속 PR)" → 화면 경로 명시, `/admin/**` 행이 `/admin/permission/manage`·`/admin/api/roles/**`를 덮는다는 한 줄), `log/CLAUDE.md`(F12), 루트 `CLAUDE.md` 지침 지도 `com.cms.admin.permission` 설명에 "권한관리 API·화면" 추가 | — | 문서만 |
| 7 | playwright 검증(§7-4), 비자명 이슈가 있었으면 `docs/troubleshooting.md` | — | — |

---

## 5. 설계 상세

### 5-A. API 계약

| 메서드·경로 | 인가 | 성공 |
|---|---|---|
| `GET /admin/api/roles/{role}/permissions` | URL `/admin/**` ADMIN + `@PreAuthorize("hasRole('ADMIN')")` | 200 매트릭스 |
| `PUT /admin/api/roles/{role}/permissions` | 같음 + CSRF | 200 매트릭스(변경 후 상태, 변경 없음이면 현재 상태) |

`{role}`은 `@PathVariable Role role`(enum)로 받는다.

**GET 응답 예** (`ROLE_MANAGER`, 시드 상태)

```json
{
  "role": "ROLE_MANAGER",
  "version": 0,
  "actions": [
    {"action": "READ", "label": "조회"}, {"action": "CREATE", "label": "생성"},
    {"action": "UPDATE", "label": "수정"}, {"action": "DELETE", "label": "삭제"}
  ],
  "features": [
    {"feature": "DASHBOARD", "label": "대시보드", "kind": "ALWAYS",
     "supportedActions": ["READ"], "grantedActions": ["READ"]},
    {"feature": "MY_INFO", "label": "내 정보", "kind": "ALWAYS",
     "supportedActions": ["READ", "UPDATE"], "grantedActions": ["READ", "UPDATE"]},
    {"feature": "NOTICE", "label": "공지사항", "kind": "DELEGABLE",
     "supportedActions": ["READ", "CREATE", "UPDATE", "DELETE"], "grantedActions": ["READ", "CREATE", "UPDATE", "DELETE"]},
    {"feature": "MEMBER", "label": "회원 관리", "kind": "ADMIN_ONLY", "supportedActions": [], "grantedActions": []},
    {"feature": "MENU", "label": "메뉴 관리", "kind": "ADMIN_ONLY", "supportedActions": [], "grantedActions": []},
    {"feature": "ACTION_LOG", "label": "활동 로그", "kind": "ADMIN_ONLY", "supportedActions": [], "grantedActions": []},
    {"feature": "PERMISSION", "label": "권한관리", "kind": "ADMIN_ONLY", "supportedActions": [], "grantedActions": []}
  ]
}
```

- 기능 순서 = `AdminFeature` 선언 순서, 동작 순서 = `PermissionAction` 선언 순서(결정적).
- `grantedActions`는 **판정기와 같은 의미의 유효 허용값**이다: ALWAYS = 지원 동작 전부, ADMIN_ONLY = 빈 배열, DELEGABLE = DB 행 중 지원 동작이면서 READ 의존 규칙을 만족하는 것(READ 없는 쓰기 행은 표시하지 않음 — 실제로도 거부되므로 화면과 동작이 같다). 다음 저장 시 그런 행은 교체로 사라지고 라벨에 `-` 항목으로 남는다.
- 조회는 `@Transactional(readOnly = true)` 한 트랜잭션에서 `permission_role` → `role_permission`을 읽는다(REPEATABLE READ 일관 스냅샷 — 버전과 행이 같은 시점). **캐시를 읽지 않는다**(저장 직후 수 μs 무효화 창과 무관하게 DB 기준 버전을 보여 주기 위해).

**PUT 요청 예**

```json
{ "version": 3, "grants": [ {"feature": "NOTICE", "action": "READ"}, {"feature": "NOTICE", "action": "CREATE"} ] }
```

- `grants`는 **그 역할의 DELEGABLE 허용 집합 전체**(교체). 빈 배열 `[]` = 전부 회수(허용). ALWAYS·ADMIN_ONLY 항목은 보내지 않는다(보내면 400).
- `RolePermissionUpdateRequest`: `@NotNull Long version`, `@NotNull List<@NotNull @Valid Grant> grants`, `Grant{ @NotNull AdminFeature feature; @NotNull PermissionAction action; }`.

**상태 코드·에러 코드**

| 상황 | 상태 | `code` | 메시지(초안) | 발생 위치 |
|---|---|---|---|---|
| `{role}`이 enum에 없음(`ROLE_X`, `manager`) | 400 | `INVALID_REQUEST` | 고정 문구 "요청 파라미터 형식이 올바르지 않습니다: role"(입력 원문 미반영) | 경로 변수 변환 |
| `{role}` = `ROLE_ADMIN` | 400 | `INVALID_REQUEST` | "관리자(ROLE_ADMIN) 권한은 코드로 고정되어 변경할 수 없습니다." | 서비스(GET·PUT 공통, DB 접근 전) |
| `{role}` = `ROLE_USER` | 400 | `INVALID_REQUEST` | "권한을 관리할 수 없는 역할입니다." | 서비스 |
| 본문 JSON 오류·미정의 `feature`/`action` 값 | 400 | `JSON_PARSE_ERROR` | "요청 JSON 형식이 올바르지 않습니다." | Jackson |
| `version`·`grants`·원소·`feature`·`action` 누락(null) | 400 | `VALIDATION_ERROR` | Bean Validation 문구 | `@Valid` |
| 위임 불가 기능(ALWAYS·ADMIN_ONLY) | 400 | `INVALID_REQUEST` | "위임할 수 없는 기능입니다: {기능 라벨}" | 서비스 |
| 기능이 지원하지 않는 동작 | 400 | `INVALID_REQUEST` | "{기능 라벨}은(는) {동작 라벨} 동작을 지원하지 않습니다." | 서비스 |
| 중복 항목 | 400 | `INVALID_REQUEST` | "중복된 권한 항목이 있습니다." | 서비스 |
| READ 없이 CREATE/UPDATE/DELETE | 400 | `INVALID_REQUEST` | "{기능 라벨}의 생성·수정·삭제 권한은 조회 권한과 함께 부여해야 합니다." (자동 보정 없음) | 서비스 |
| MANAGER·익명 | 403 / 401 | `ACCESS_DENIED` / `UNAUTHORIZED` | 필터 핸들러 고정 문구 | URL 게이트(`/admin/**`) — 본문 검증보다 먼저 |
| CSRF 토큰 없음 | 403 | (필터) | — | `CsrfFilter` |
| `permission_role`에 해당 역할 기준 행 없음(V14 이후 정상 운영에선 발생 안 함) | 404 | `RESOURCE_NOT_FOUND` | "권한 정보를 찾을 수 없습니다." | 서비스 |
| `version` ≠ 현재 버전 | 409 | `RESOURCE_CONFLICT` | "다른 관리자가 먼저 권한을 변경했습니다. 화면을 새로고침한 뒤 다시 시도해 주세요." | 서비스(`ConflictException`) |
| 락 대기 실패 | 409 | `RESOURCE_CONFLICT` | 기존 고정 문구 | 기존 핸들러 |

- **검사 순서**: 역할 → 기능·동작·중복·READ 의존(전부 순수 코드, DB 접근 전) → 잠금 → 버전. 따라서 400이 409보다 우선한다.
- `version`은 `Long`이며 Jackson 기본 강제 변환(`"3"`→3, `3.9`→3)이 적용된다. 값 비교에만 쓰이고 ADMIN 전용이라 보안 영향이 없어 메뉴 구조 반영처럼 `JsonNode`로 받지 않는다(과설계 회피).

### 5-B. 서비스 `RolePermissionService`

```text
@Transactional(readOnly = true)
RolePermissionMatrixResponse getMatrix(Role role)
  validateManageable(role)
  PermissionRole base = permissionRoleRepository.findById(role.name()) or 404
  rows = rolePermissionRepository.findByRole(role.name())
  return 조립(base.version, 유효 허용값(rows))

@Transactional
@AdminActionLogged(actionType = PERMISSION_UPDATE, targetType = "ROLE_PERMISSION",
                   targetLabelExpression = "auditLabel")          // targetId 없음(역할에 숫자 ID 없음)
RolePermissionUpdateResult replace(Role role, RolePermissionUpdateRequest request)
  validateManageable(role)
  Set<Grant> requested = validateGrants(request.grants)            // 400 규칙 전부
  PermissionRole base = permissionRoleRepository.findByIdForUpdate(role.name()) or 404
                                                                    // ↑ 이 트랜잭션의 첫 DB 조회 = 잠금 읽기
  if (!base.version.equals(request.version)) throw ConflictException  // 409
  current = 카탈로그 유효 행(findByRole(...))                         // 잠금 뒤 읽으므로 최신 커밋 기준
  added = requested − current, removed = current − requested
  if (added, removed 모두 비어 있음)
      return result(현재 상태, label "ROLE_MANAGER v3: 변경 없음")   // 쓰기·버전·이벤트 없음
  removed 행 삭제, added 행 저장(new RolePermission(role, feature, action))
  LocalDateTime now = LocalDateTime.now(clock)                      // 주입 Clock
  base.increaseVersion(now)
  eventPublisher.publishEvent(new PermissionChangedEvent(role.name()))
  return result(새 상태, label)
```

- **대소문자 변형 행 충돌 가드(R-10)**: `role_permission` PK는 `utf8mb4_general_ci`(`V13:30`)라 수동 삽입된 `(ROLE_MANAGER, NOTICE, read)`가 정상 키 `(…, READ)`와 **같은 키**로 비교된다. 반면 캐시·판정기의 `Enum.valueOf`는 대소문자를 구분해 그 행을 무시한다. 그대로 두면 "무시 행 보존"과 "정상 READ 추가"가 동시에 성립하지 않아(INSERT가 PK 충돌 또는 merge가 기존 행에 흡수) 저장은 성공해 보이는데 READ가 효력이 없는 불일치가 생긴다. **(R-12) 자바에서 대소문자·공백 비교를 흉내 내지 않고 DB 자신의 PK 동등 비교를 쓴다**: `current`(diff 대상)는 `findByRole` 결과 중 **역할 문자열까지 정확히 일치하고 카탈로그로 파싱되는 행**만 인정한다(`role = :role`도 `general_ci`라 `role_manager` 변형 행이 섞여 들어올 수 있으므로 `role.name().equals(row.getRole())` 확인). 그리고 `added`의 각 키에 대해 잠금 뒤 `rolePermissionRepository.existsById(new RolePermissionId(role, feature, action))`를 호출해 **이미 `true`이면**(= `current`에 정확한 행이 없는데 DB PK로는 존재 — 대소문자·후행 공백 PAD 비교로 충돌하는 변형 행) `ConflictException`(409 `RESOURCE_CONFLICT`, "권한 테이블에 형식이 올바르지 않은 행이 있어 저장할 수 없습니다. 운영자가 해당 행을 정리한 뒤 다시 시도해 주세요.")으로 거부**한다 — 자동 삭제는 하지 않는다("모르는 행은 건드리지 않음" 원칙 유지). 정리는 마이그레이션 규칙(`permission/CLAUDE.md:42`)대로 수동 SQL이다. 정상 운영(앱만 쓰기)에선 발생하지 않는 방어 분기다.
- **"현재 행"의 범위**: 카탈로그로 파싱되는 DELEGABLE·지원 동작 행만 diff 대상이다. 모르는 기능·위임 불가 기능 행(수동 삽입·코드에서 제거된 기능)은 판정기가 이미 무시하므로 **건드리지 않는다**(정리는 마이그레이션 규칙 — `permission/CLAUDE.md:42`). READ 없는 쓰기 행은 카탈로그상 유효하므로 diff에 포함돼 교체 시 정상 처리된다.
- **잠금 근거**: MariaDB REPEATABLE READ에서 일관 읽기 스냅샷은 첫 비잠금 SELECT에서 고정된다. 첫 조회를 `SELECT … FOR UPDATE`로 하면 뒤따르는 `findByRole`의 스냅샷이 잠금 획득 **이후**에 만들어져 앞선 저장의 커밋을 본다(`MenuService.applyStructure` 주석과 같은 규칙). 허용 행이 0개여도 `permission_role` 행이 있어 잠글 대상이 있다 → 동시 PUT 2건은 직렬화되고 뒤 요청은 바뀐 버전을 보고 409(lost update 없음).
- 버전은 JPA `@Version`이 아니라 **비관 잠금 아래 수동 비교**다(상위 계획 §6). `version` 필드명은 `@Version` 없이 일반 컬럼으로 매핑돼 있다.
- `RolePermission`은 할당 ID라 `save()`가 `merge` 경로(SELECT 후 INSERT)를 탄다 — 행 수가 최대 4라 수용. 삭제는 `deleteAll(엔티티 목록)`.
- **감사 라벨**: `"{role} v{old}→v{new}: " + 항목 목록`. 항목은 추가(`+`) 먼저, 그 다음 삭제(`-`), 각각 기능 선언 순서→동작 선언 순서, 구분자 `", "`, 형식 `{기능 라벨}.{동작 라벨}` → 예 `ROLE_MANAGER v3→v4: +공지사항.생성, -공지사항.삭제`. 변경 없음은 `ROLE_MANAGER v3: 변경 없음`. 라벨은 코드 상수와 enum 이름만으로 만들어 사용자 입력이 섞이지 않는다. 500자 절단·로그 이스케이프는 Aspect 담당(`log/CLAUDE.md:10`).
- **감사의 최상위 트랜잭션 진입점 조건**: 컨트롤러는 `@Transactional`이 없고 OSIV는 꺼져 있으며(루트 `CLAUDE.md`), 컨트롤러 → `replace()` 직접 호출이다. 서비스 내부에서 다른 `@Transactional` 메서드를 거치지 않는다. 따라서 `@Order(LOWEST_PRECEDENCE - 1)` Aspect가 트랜잭션 바깥에 서고, 커밋 실패는 FAIL로 귀속된다(`log/CLAUDE.md:9`). 서비스 안에서 던지는 400·404·409는 FAIL 감사 1건으로 남고(메뉴 서비스와 동일 — **FAIL 감사는 `targetId`·`targetLabel`이 항상 null**, `AdminActionLogAspect.logFailure`, 그래서 버전·diff 라벨은 성공·변경 없음에만 남는다: R-3), `@Valid`·경로 변수·JSON 오류와 MANAGER 403은 서비스에 닿지 않아 감사가 없다.
- **캐시 무효화**: `PermissionChangedListener`가 `@TransactionalEventListener(phase = AFTER_COMMIT)`로 `invalidate()`. 롤백되면 호출되지 않는다. 변경 없음이면 이벤트를 내지 않는다. `invalidate()`는 실패할 수 없는 메모리 연산이라 리스너에 try-catch가 필요 없다.
- **세션**: 역할이 바뀌지 않으므로 세션 만료 이벤트를 내지 않는다(판정이 매 요청 캐시를 읽음 — 상위 계획 §5).

`RolePermissionUpdateResult`: `RolePermissionMatrixResponse response` + `String auditLabel`(getter `getAuditLabel()`). 컨트롤러는 `ResponseEntity.ok(result.getResponse())`.

### 5-1. 권한관리 화면 `templates/admin/permission/manage.html`

- 골격은 `menu/manage.html`과 같다: `head` 프래그먼트(`adminHead`), 사이드바·탑바·푸터 프래그먼트, `/css/admin/admin-manage.css`, 같은 vendor 스크립트. 제목 "권한 관리", 안내 "대상 역할: MANAGER(매니저) — 관리자(ADMIN)는 코드로 모든 권한이 고정됩니다." 역할 선택 UI 없음. 현재 버전 표시(`v3`).
- 로드: `GET /admin/api/roles/ROLE_MANAGER/permissions` → 표 렌더. 행 = `features`, 열 = `actions`(머리글은 응답 라벨).
- **칸 렌더링 규칙**

| 기능 종류 | 지원 동작 칸 | 미지원 동작 칸 | 행 표기 |
|---|---|---|---|
| ALWAYS | 체크됨 + `disabled` | "—" | `fas fa-lock` + "모든 관리자 상시 허용" |
| DELEGABLE | 편집 가능 체크박스(`grantedActions` 반영) | "—" | — |
| ADMIN_ONLY | (지원 동작 없음) 네 칸 모두 체크 안 됨 + `disabled` | — | `fas fa-lock` + "관리자 전용 — 위임 불가" |

- **READ 연동**: 같은 행에서 CREATE/UPDATE/DELETE를 켜면 READ 자동 체크, READ를 끄면 나머지 자동 해제(서버는 위반 시 400 — 최종 방어).
- **초안 상태**: 로드한 DELEGABLE 허용 집합과 현재 체크 상태를 비교해 `dirty`를 계산, [저장] 버튼은 `!dirty || busy`면 비활성, "저장하지 않은 변경이 있습니다" 표시.
- **저장 중 편집 잠금(R-4)**: `busy`(PUT 전송~응답) 동안 매트릭스의 모든 체크박스를 `disabled`로 만든다. 그렇지 않으면 전송 뒤 사용자가 바꾼 체크가 이전 요청의 200 응답으로 전체 재렌더링되며 지워지고 `dirty`도 해제된다. 응답(200·409·400·그 외)이 끝나면 잠금을 푼다(400·그 외는 초안 유지, 200·409는 응답/재조회 상태로 갱신).
- **저장**: `PUT`, 헤더는 공지 화면의 `getCsrfHeaders()`와 같은 방식(`_csrf_header` 메타 → 없으면 `X-CSRF-TOKEN`)으로 `Content-Type: application/json` + CSRF. 본문은 DELEGABLE 행의 체크된 동작만 `grants`로, `version`은 로드 시점 값.
  - 200: 응답으로 다시 렌더(새 버전), 성공 문구 "권한을 저장했습니다(v3→v4)." — 저장 직후 공지 담당 MANAGER에게 다음 요청부터 반영됨을 한 줄 안내.
  - 409: 서버 메시지 + " 최신 상태로 다시 불러왔습니다." 후 **초안 폐기·재조회**(메뉴 화면 선례).
  - 400: 서버 메시지 표시, **초안 유지**.
  - 그 외(401·403·500·네트워크): 메시지 표시, 초안 유지.
- 응답 메시지는 `extractErrorMessage(response, fallback)` 패턴(공지·메뉴 화면과 같은 함수 형태를 이 페이지에 둔다), DOM 삽입은 `textContent`만 사용(라벨은 코드 값이지만 규칙 통일).
- `beforeunload`: `dirty && !leavingAfterSave`면 경고(메뉴 화면 선례 `menu/manage.html:1215-1220`).
- 새로운 공용 JS 파일·추상화는 만들지 않는다(기존 페이지들도 함수를 페이지 안에 둔다).

### 5-2. 공지 화면 `notice/manage.html`

- 상단 스크립트에 `th:inline="javascript"`(선례 `index.html:190`)로 `const MY_PERMISSIONS = new Set(/*[[${myPermissions}]]*/ []);` → `canCreate = has("NOTICE:CREATE")`, `canUpdate`, `canDelete`.
- 숨김 조건

| 요소 | 표시 조건 |
|---|---|
| `btnNewNotice`(:69) | `canCreate` |
| `btnEditMode`(view 모드) | `canUpdate` — `showViewMode()`(:545-555)에서 조건부로 `d-none` 해제 |
| `btnDelete`(view 모드) | `canDelete` — 같은 곳 |
| 첨부 업로드 영역(`.attachment-upload`, :232-236) | `canUpdate`(U4 — 업로드 = UPDATE) |
| 첨부 행의 [삭제](`renderAttachments` :635) | `canUpdate`(U4 — 첨부 삭제 = UPDATE) |
| 목록·상세·첨부 다운로드 링크 | 항상(페이지 진입 자체가 READ 게이트 통과) |

- 생성 모드 `btnSave`는 `btnNewNotice`가 있어야 열리므로 별도 조건 불필요. 이벤트 핸들러 쪽 가드는 추가하지 않는다 — 서버 판정이 최종이다.
- **403 처리**: 이 페이지의 `extractErrorMessage`(:374-381)에 `response.status === 403`이면 본문과 무관하게 "권한이 없습니다. 권한이 변경되었을 수 있으니 페이지를 새로고침해 주세요."를 반환하는 분기를 넣는다(F9 — 메서드 계층 403의 영문 메시지 가능성 흡수, 화면을 연 사이 회수된 경우 안내).
- **U4 안내**: 공지 삭제가 409이고 `!canUpdate`이면 서버 메시지(기존 문구) 뒤에 " 첨부 삭제에는 공지 수정 권한이 필요합니다. 관리자에게 요청하세요."를 덧붙인다(F11). 그 외 409는 기존대로.
- ADMIN은 `myPermissions`에 NOTICE 4동작이 모두 들어가므로 화면이 오늘과 같다(회귀 없음).

### 5-3. `myPermissions` 계산 (`AdminSidebarAdvice` + 판정기)

- 판정기에 추가:

```text
/** 현재 사용자가 가진 위임 기능 동작 키("NOTICE:CREATE") — 화면 버튼 표시용. 서버 판정을 대신하지 않는다. */
public Set<String> grantedActionKeys(Supplier<PermissionSnapshot> snapshot, Authentication authentication)
  → DELEGABLE 기능 f × f.getActions() a 마다 decide(snapshot, auth, f, a)가 true인 "f:a"
```

  기존 private `decide(Supplier, …)`를 재사용한다. ADMIN이면 스냅샷 공급자를 호출하지 않는다(DB 비의존 유지). 익명·`ROLE_USER` → 빈 집합.
- `AdminSidebarAdvice`: 두 개의 `@ModelAttribute` 메서드가 아니라 **하나의 `@ModelAttribute` void 메서드(`Model` 인자)**가 `sidebarMenus`·`myPermissions`를 함께 넣는다. 메서드 안에서 `adminPermissionEvaluator::snapshot`을 감싼 **메모이즈 공급자**(첫 `get()`에서만 로드, 지역 변수 하나)를 만들어 `menuUrlVisibility(once, auth)`와 `grantedActionKeys(once, auth)`에 함께 넘긴다 → MANAGER는 **Advice 안에서** 스냅샷 1개를 두 속성이 공유하고(URL 게이트의 `allows()`는 별도로 캐시를 읽으므로 "전체 요청 1회"는 보증하지 않는다 — R-6), ADMIN은 0회. 미인증(`getCurrentAdminId() == null`)이면 두 속성 모두 빈 값, DB 조회 없음(기존 동작). `currentUri`는 그대로 별도 메서드.
- `@ControllerAdvice(annotations = AdminPage.class)` 범위 유지 — REST에는 주입되지 않는다.

### 5-4. V15 `V15__seed_permission_menu.sql`

- **번호 재확인 지침**: 브랜치 생성 시와 머지 직전에 `src/main/resources/db/migration/`의 최대 버전을 확인한다. 병행 PR이 V15를 먼저 머지했으면 다음 번호로 재번호하고(이 PR은 미머지라 수정 가능), 테스트의 `target("…")` 값도 함께 고친다. 머지된 파일은 수정 금지.
- DML만(DDL 금지 — MariaDB 암묵 커밋, V3·V9·V14 규칙).

```sql
-- ============================================================
-- V15: 권한 관리 메뉴 시드 (멱등 — WHERE NOT EXISTS) (2026-10-xx)
-- PLAN-menu-permission-management.md §6, PLAN-permission-management-pr3.md §5-4.
-- 최상위 맨 끝에 둔다. access_role은 PR ②부터 엔티티가 매핑하지 않으므로 지정하지 않는다(DEFAULT NULL).
-- 노출은 카탈로그(PERMISSION = ADMIN_ONLY)가 정한다 — MANAGER에게는 보이지 않는다.
-- create_date·update_date는 NULL — DB NOW()와 앱 KST Clock의 시간대 혼용을 피한다(V14와 같은 이유).
-- 메뉴를 지운 뒤 Flyway가 이 파일을 다시 실행하지는 않는다(시드 메뉴 영구삭제와 같은 규칙).
-- ============================================================
INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, ord, up_menu_no, create_date, update_date)
SELECT '권한 관리', '/admin/permission/manage', 'fas fa-fw fa-user-lock', 1,
       (SELECT LEAST(COALESCE(MAX(m.ord), -1) + 1, 2147483647) FROM menu m WHERE m.up_menu_no IS NULL),
       NULL, NULL, NULL
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/permission/manage');
```

- **`ord` 상한(R-1)**: `menu.ord`는 signed INT라 최상위 `MAX(ord) = 2147483647`이면 `+1`이 컬럼 범위를 넘어 strict 모드에서는 마이그레이션이 실패해 기동이 막힌다(앱 `MenuService.resolveNextOrdForCreate`는 같은 경계를 재번호로 처리). `LEAST(…, 2147483647)`로 묶는다 — 사이드바 표시 순서는 `(ord, menuNo)`라 `ord`가 같아도 더 큰 `menuNo`를 가진 새 행이 맨 끝에 놓여 "최상위 맨 끝"이 보존된다.
- 같은 테이블을 서브쿼리로 읽는 `INSERT … SELECT`가 MariaDB에서 허용되는지는 **미확인 — 마이그레이션 테스트로 확인**(상위 계획도 같은 지시). 실패하면 `SET @next_ord := (SELECT …);` 후 `SELECT …, @next_ord …`로 바꾼다(V3의 `SET @…` 선례).
- `fa-user-lock` 아이콘이 번들된 Font Awesome 버전에 있는지는 **미확인 — playwright 화면 검증에서 확인**(없으면 `fa-user-shield` 등 기존 아이콘으로 교체).

---

## 6. 보안 점검표

| 항목 | 판단 | 근거 |
|---|---|---|
| `SecurityConfig` 변경 필요? | **불필요** | `/admin/permission/manage`·`/admin/api/roles/**`는 어떤 카탈로그 `gatePatterns`에도 걸리지 않는다(`PERMISSION`은 비어 있고, ALWAYS의 `/admin`은 정확 일치, `/admin/api/members/me/**`와 무관 — `AdminFeature.java:26-48`) → `/admin/**` `hasRole('ADMIN')` 캐치올. 새 매처 없음, 기본 거부 유지 |
| 권한 상승(MANAGER가 자신에게 권한 부여) | 불가 | ① MANAGER는 필터에서 403(본문 검증·서비스 도달 전) ② API 메서드에 `hasRole('ADMIN')` 이중 선언 ③ 서비스가 DELEGABLE 외 기능을 400 ④ 판정기·캐시가 DB의 위임 불가 행을 무시(`RolePermissionCache.java:90-93`) ⑤ `PERMISSION` 생성자 가드(`AdminFeature.java:58-60`) |
| `PERMISSION` ADMIN_ONLY 불변 | 유지 | 이 PR은 `AdminFeature`를 바꾸지 않는다. 화면도 ADMIN_ONLY 행을 잠금 표시만 하고 전송하지 않음 |
| ADMIN 자기 잠금 | 불가 | ADMIN 판정은 DB를 보지 않는다(`AdminPermissionEvaluator.java:54-56`). `ROLE_ADMIN` 경로는 400, `permission_role`에 ADMIN 행 없음. 마지막 ADMIN 보호는 기존 회원 가드 담당 |
| `{role}` 검증 | enum 변환 실패 400(원문 미반영), `ROLE_ADMIN`·`ROLE_USER` 400, 미존재 기준 행 404 | §5-A |
| 컨벤션 테스트(`AdminEndpointAuthorizationConventionTest`) | 통과 | API 2개는 정확히 하나의 선언 `hasRole('ADMIN')`, 페이지는 GET 전용이라 면제 + `@AdminPage`(`AdminPageAnnotationConventionTest`). `@RequirePermission`·`hasAnyRole`을 쓰지 않으므로 규칙 2~5와 무관 |
| CSRF | `PUT`은 토큰 필수(전 경로 활성) | 슬라이스 테스트로 토큰 없음 403 고정 |
| 감사 라벨 인젝션 | 없음 | 라벨은 코드 상수·enum 이름만으로 구성 |
| XSS | 없음 | 화면은 `textContent`만, 공지 화면 `MY_PERMISSIONS`는 Thymeleaf JS 인라인 직렬화 |
| 즉시 반영의 한계 | 수용(상위 계획) | 커밋~무효화 수 μs 창·진행 중 요청은 이전 권한, 단일 인스턴스 전제 |
| 화면 버튼 숨김은 보안 경계가 아님 | 명시 | 서버 403이 최종, 버튼은 UX |

---

## 7. 테스트 계획

### 7-1. 신규 테스트

| 파일 | 종류 | 핵심 단언 | 상위 계획 |
|---|---|---|---|
| `permission/service/RolePermissionServiceTest` | 단위(Mockito, 고정 `Clock`) | 400 경계 전부(ROLE_ADMIN·ROLE_USER·ALWAYS 기능·ADMIN_ONLY 기능·중복·READ 없는 쓰기 — 각각 메시지와 **DB 미접근**까지 단언. **"기능이 지원하지 않는 동작" 분기는 현재 카탈로그에서 도달 불가한 방어 분기**다: 유일한 DELEGABLE인 NOTICE가 4동작을 모두 지원하고, DASHBOARD 등의 미지원 동작은 앞선 "위임 불가" 검사에서 먼저 거부된다 — 이 분기를 검증했다고 오인하지 않도록 구분하고, 테스트용 카탈로그 추상화는 만들지 않는다(R-8, 후속으로 지원 동작이 제한된 위임 기능이 생길 때 그 기능으로 시험)) — **잠금 리포지토리 미호출**(DB 전 거부) / 기준 행 없음 404 / 버전 불일치 409이고 쓰기·이벤트 없음 / diff(추가·삭제만 저장, 변경 없는 행 미접촉) / 변경 시 version+1·`updateDate == LocalDateTime.now(fixedClock)`·이벤트 1회 / 변경 없음이면 version 그대로·쓰기·이벤트 0회·라벨 "변경 없음" / 라벨 순서·형식(`ROLE_MANAGER v3→v4: +공지사항.생성, -공지사항.삭제`) / 모르는·위임 불가 행은 diff에서 제외되고 삭제되지 않음 / GET `grantedActions`(ALWAYS=지원 전부, ADMIN_ONLY=빈, READ 없는 쓰기 행 제외) | 6 |
| `permission/controller/RolePermissionControllerTest` | `@WebMvcTest` + `SecurityConfig`·`MethodSecurityTestConfig`·`PermissionTestConfig`(기존 공지 슬라이스와 같은 임포트) | MANAGER GET·PUT 403 JSON `ACCESS_DENIED`·서비스 미호출 / 익명 401 / CSRF 없음 403 / `version` null·`grants` null·원소 null 400 `VALIDATION_ERROR` / 미정의 `feature` 400 `JSON_PARSE_ERROR` / `{role}=ROLE_X` 400 `INVALID_REQUEST`·**`$.message`에 `ROLE_X` 미포함**(오류 응답의 `$.path`에는 요청 URI가 들어가는 기존 계약 — `GlobalApiExceptionHandler.java:202`, `ApiErrorResponse.java:7` — 이라 전체 응답 기준 단언은 불가, `$.path`는 기존 계약대로 별도 단언: R-11) / ADMIN 200·응답 JSON 형태 / 서비스 `ConflictException` → 409 `RESOURCE_CONFLICT` | 6 |
| `permission/RolePermissionApiIntegrationTest` | `@SpringBootTest` + `MariaDbContainerSupport` + MockMvc(실제 Security·Aspect·리스너) | ① ADMIN PUT(READ만) → DB 행·`version` 1 증가·`update_date` 기록, 감사 `PERMISSION_UPDATE` SUCCESS 1건·`target_type=ROLE_PERMISSION`·`target_id` NULL·`target_label` 정확히 일치 ② 변경 없음 PUT → version 불변, 감사 SUCCESS 1건 라벨 "변경 없음" ③ 낡은 version → 409, DB 불변, 감사 FAIL 1건 ④ **즉시 반영(테스트 계획 5)**: **실제 로그인(`POST /admin/login`, 실제 CSRF)으로 얻은 MANAGER `MockHttpSession`을 재사용하고 이후 요청에 인증을 다시 주입하지 않는다**(요청별 `authentication()` 주입은 세션 만료·인증 유지 문제를 우회하므로 "같은 세션"의 증거가 못 된다 — R-5; 권한 변경 뒤 세션이 만료되지 않았음도 확인) — 공지 API 200 → ADMIN이 **실제 PUT**으로 READ 회수 → 같은 인증의 다음 공지 API 403·대시보드 HTML에 공지 링크 없음·`/admin/notice/manage` HTML 403 → READ+CREATE 재부여 PUT → 공지 페이지 모델 `myPermissions`에 `NOTICE:CREATE` 있고 `NOTICE:DELETE` 없음 ⑤ **커밋 후에만 무효화**: `@MockitoSpyBean RolePermissionCache` — 정상 PUT 후 `invalidate()` 1회, 변경 없음 0회, 409 0회, 바깥 `TransactionTemplate`에서 호출 후 `setRollbackOnly()` → 무효화 0회·DB 불변(**이 시험은 이벤트 리스너 검증으로만 범위를 둔다** — 바깥 트랜잭션 안에서 부르면 서비스 반환 시점에 SUCCESS 감사가 독립 커밋돼 감사의 최상위 조건이 깨지며, 이 잔존 SUCCESS 감사는 단언하지 않고 주석으로 한계를 기록: `AdminActionLogCommitOrderIntegrationTest`와 같은 한계, R-2) + **실제 최상위 `replace()` 호출에 커밋 직전 실패를 주입**(`AdminActionLogCommitOrderIntegrationTest`의 `beforeCommit` 주입 패턴)해 **권한 행·버전 롤백, `invalidate()` 0회, SUCCESS 감사 0건·FAIL 1건**을 함께 단언 ⑦ **감사 저장 실패 격리(R-9)**: 감사 서비스가 예외를 던지게 해도(`@MockitoSpyBean`) PUT은 200이고 권한 행·버전은 변경 유지, `invalidate()` 1회(권한 변경이 감사 실패로 되돌려지지 않음 — 그래서 롤백 절차가 감사만 믿으면 안 된다) ⑧ **대소문자 변형 행(R-10)**: 실제 MariaDB에 변형 행 — 동작 소문자 `(ROLE_MANAGER, NOTICE, 'read')`, **역할 소문자 `('role_manager', NOTICE, 'READ')`**(FK가 `general_ci`라 삽입 가능), **동작 후행 공백 `(ROLE_MANAGER, NOTICE, 'READ ')`** 각각 — 을 수동 삽입한 상태에서 READ를 추가하는 PUT → 409 `RESOURCE_CONFLICT`·DB 불변·무효화 0회·FAIL 감사 1건, 그리고 해당 행을 삭제하면 같은 PUT이 200(응답·DB·캐시 판정 일치) ⑥ `@AfterEach`로 시드(4동작·version은 값 무관) 복원 + `cache.invalidate()` | 5, 6 |
| `permission/RolePermissionConcurrencyIntegrationTest` | `@SpringBootTest` + MariaDB | ① **실제 락 대기**: 다른 스레드가 `findByIdForUpdate`로 잠금 보유 → 서비스 `replace()` 시작 → `INNODB_LOCK_WAITS`로 대기 관측(`MenuConcurrencyIntegrationTest` 헬퍼 패턴, sleep을 증거로 쓰지 않음) → 보유 트랜잭션이 version+1 커밋 → 대기하던 요청 409, 행 불변 ② **동시 PUT 2건**(같은 version, `CyclicBarrier`) → 정확히 하나 성공·하나 409, 최종 version = 시작+1, 최종 행 = 성공한 요청의 집합, 감사 SUCCESS 1·FAIL 1 | 6 |
| `permission/PermissionMenuMigrationTest`(또는 `PermissionMigrationTest`에 메서드 추가) | Flyway 별도 스키마 | V14 DB(V3·V9 시드 메뉴 존재) → 최신 적용 후 `/admin/permission/manage` 행 1개: 이름·아이콘·`use_yn=1`·`up_menu_no` NULL·`access_role` NULL·날짜 NULL·`ord = 이전 최상위 MAX(ord)+1` / V15 SQL 수동 재실행 시 중복 없음(`WHERE NOT EXISTS`) / 최상위 메뉴가 없는 스키마에서 `ord = 0`(COALESCE) / **최상위 `MAX(ord) = 2147483647`인 V14 DB에서 마이그레이션이 성공하고 새 행 `ord = 2147483647`이며 표시 순서(ord, menuNo)상 맨 끝(R-1)** / 같은 URL 메뉴가 이미 있으면 삽입 안 함 | 8 |

### 7-2. 기존 테스트 수정

| 파일 | 변경 | 이유 |
|---|---|---|
| `PermissionMigrationTest` | `upgradeFromV12_…`의 "기존 메뉴 데이터 보존" 단언을 메뉴 수 `+1`(권한 관리 메뉴) 또는 `target("14")` 기준으로 조정 — 권장: 기존 행이 그대로이고 추가된 행은 권한 관리 메뉴 하나뿐임을 단언 | F4 — 그대로 두면 실패 |
| `AdminPermissionMatrixIntegrationTest.unknownAndAdminOnlyPathsDeniedForManager` | 경로 목록에 `/admin/permission/manage`, `/admin/api/roles/ROLE_MANAGER/permissions` 추가 + 시드 상태 MANAGER의 `PUT`(유효 CSRF·유효 본문) 403·DB 불변 | 권한관리 자체가 위임 불가임을 실제 스택으로 고정 |
| `AdminSidebarAdviceTest` | `@WebMvcTest(controllers=…)`에 `PermissionPageController` 추가, `@ValueSource`에 `/admin/permission/manage`, `model().attributeExists("myPermissions")` 단언 | 새 페이지·새 모델 속성 |
| `AdminPermissionEvaluatorTest` | `grantedActionKeys` 진리표: ADMIN(공급자 미호출, NOTICE 4키) / MANAGER 시드(4키) / MANAGER CREATE만(빈 — READ 의존) / MANAGER READ+DELETE(2키) / 익명·USER(빈) / ALWAYS·ADMIN_ONLY 키 미포함 | 5-3 |
| (신규 메서드 또는 기존 Advice 테스트) | 실제 판정기 + 목 캐시로 **`AdminSidebarAdvice` 호출 안에서** MANAGER는 `cache.snapshot()` 1회·ADMIN 0회이고, `sidebarMenus`와 `myPermissions`가 **같은 스냅샷**으로 계산됨(캐시를 호출 사이에 바꿔 끼워도 두 속성이 일치)을 단언. 전체 HTTP 요청 기준 1회는 단언하지 않는다 — URL 게이트(`SecurityConfig` `featureReadGate`)가 별도로 `allows()`→`cache.snapshot()`을 부른다(R-6) | Advice 내부 스냅샷 공유(상위 계획 P-6) |
| `MenuExposureSidebarIntegrationTest.managerSidebarFollowsPermission` | MANAGER HTML에 `href="/admin/permission/manage"` 없음, ADMIN HTML에 있음 | V15 메뉴의 노출 규칙 확인 |
| `NoticeControllerTest` | READ만 가진 MANAGER의 `POST` 403 케이스에서 `$.message` 확인(F9 미확인 해소용, 결과에 맞춰 단언) | F9 |

영향 없음 확인 대상(수정 불필요 예상): `AdminEndpointAuthorizationConventionTest`(새 핸들러 규칙 충족), `AdminPageAnnotationConventionTest`(`@AdminPage` 부착), `AdminActionTypeSyncTest`·`LabelSyncTest`(상수·라벨 동시 추가), `SecurityConfigTest`(기존 기대값 무변경 — `/admin/**` 행에 신규 경로 추가 단언은 선택), 목 판정기를 쓰는 슬라이스들(`Set` 반환은 Mockito 기본값 빈 집합).

### 7-3. 실제 MariaDB가 필요한 것 vs 슬라이스로 충분한 것

- **MariaDB 필요**: 잠금 직렬화·락 대기 관측·동시 PUT, 감사 Aspect의 REQUIRES_NEW 기록·FAIL 귀속, AFTER_COMMIT 무효화(실제 트랜잭션 동기화), 같은 인증 다음 요청 반영(실제 캐시 로드), V15 `INSERT…SELECT` 동작.
- **슬라이스/단위로 충분**: 검증 규칙·diff·라벨·버전 비교(서비스 단위), 인가·CSRF·DTO·에러 코드 매핑(컨트롤러 슬라이스), `grantedActionKeys` 진리표(판정기 단위), 모델 속성 존재(Advice 슬라이스).

### 7-4. UI 검증 (playwright, 테스트 계획 10)

dev 프로파일 bootRun(로컬 DB·env 필요 — 메모리 "테스트/실행 환경" 참고), ADMIN·MANAGER 계정 각 1개.

1. ADMIN: 사이드바 최상위 끝에 "권한 관리"(아이콘 렌더 확인 — §5-4 미확인 해소), 페이지 진입, 매트릭스 — 대시보드·내 정보 잠금 체크, 관리자 전용 4행 잠금 비체크, 미지원 칸 "—".
2. 연동: 공지 "삭제" 켜기 → "조회" 자동 체크, "조회" 끄기 → 나머지 해제. dirty 표시·[저장] 활성, 새로고침 시 `beforeunload` 경고.
3. 저장 성공(버전 증가 표시) → **역할별 별도 browser context**(ADMIN 한 개·MANAGER 한 개 — 같은 컨텍스트의 탭은 세션 쿠키를 공유해 두 역할을 동시에 유지할 수 없다: R-5)의 MANAGER 세션에서 새로고침 시 공지 메뉴 유무·[새 공지]/[수정]/[삭제]/첨부 업로드·삭제 버튼 표시가 권한대로 바뀜(재로그인 없이).
4. 409: 탭 두 개에서 ADMIN 저장 경합 → 늦은 쪽에 서버 메시지 + 재조회로 초안 폐기.
5. 400: 개발자 도구로 READ 없는 CREATE를 보내 초안 유지·메시지 확인(또는 API 테스트로 대체 기록).
6. MANAGER가 공지 화면을 연 상태에서 ADMIN이 CREATE 회수 → MANAGER가 저장 시 "권한이 없습니다. … 새로고침" 문구.
7. MANAGER DELETE만(+READ): 첨부 있는 공지 삭제 → 기존 409 문구 + U4 안내 문장.
8. MANAGER로 `/admin/permission/manage` 직접 접근 → HTML 403, 사이드바에 "권한 관리" 없음.

---

## 8. 배포·롤백 (`docs/deployment.md` 추가 절 + PR 본문)

`docs/deployment.md`에 "권한관리 롤백 주의(권한관리 PR ①~④)" 절을 신설한다(상위 계획 롤백 호환표 요약):

- **③ 이후 → ②**: 안전. 권한 테이블은 ②도 읽고 회수 결과가 유지된다. 권한관리 화면·API만 사라지고, V15 메뉴는 남아 ADMIN 사이드바에 링크가 보이지만 페이지 핸들러가 없어 404(표시 회귀). 필요하면 메뉴 관리에서 비활성화.
- **③ 이후 → ①**: 보안 회귀는 없으나(판정기 유지) ①은 `access_role`을 매핑해 V15 메뉴(`access_role NULL`)를 ALL로 정규화 → **MANAGER 사이드바에 "권한 관리" 링크가 보이고 누르면 403**(표시 회귀). 비활성화로 숨긴다.
- **① 이전(판정기 없는 앱)으로 되돌리기**: 권한 회수 이력이 있으면 **금지 기본** — 회수가 무효화돼 MANAGER 공지 CRUD가 전부 열린다. 되돌리기 전 감사 이력을 확인한다:
  `SELECT action_date, action_user_id, target_label FROM admin_action_log WHERE action_type = 'PERMISSION_UPDATE' AND result = 'SUCCESS' ORDER BY id DESC;`(컬럼명은 구현 시 `AdminActionLog` 엔티티로 확인)
  **감사 이력만으로 판단하지 않는다(R-9)** — 감사 저장은 최선 노력이라(`AdminActionLogAspect`가 저장 예외를 격리) 회수는 성공했는데 SUCCESS 감사가 유실됐을 수 있다. **현재 DB의 NOTICE 유효 허용 집합을 정확 일치로 함께 확인**한다(R-13 — 원시 행 수로 판단하지 않는다: 폐기된 기능 행 등이 섞이면 4행이어도 DELETE가 회수된 상태일 수 있고, 역할·동작 대소문자 변형 행은 판정기가 허용하지 않는다):
  `SELECT action FROM role_permission WHERE BINARY role = 'ROLE_MANAGER' AND BINARY feature = 'NOTICE' AND BINARY action IN ('READ','CREATE','UPDATE','DELETE');` → **정확히 4행(4동작 전부)**이어야 시드 상태다(`BINARY`로 `general_ci` 비교를 피함). 4행이 아니면 회수된 것이다. 4행이어도 **과거 회수 이력이 없었다고 단정하지 않는다** — 회수 후 다시 부여됐을 수 있고 감사가 유실됐을 수 있으므로, 이력이 불확실하면 아래 승인 절차 또는 roll-forward를 적용한다.
  불가피하면 MANAGER 계정 상태를 잠금/비활성으로 바꿔 로그인을 막거나, 공지 권한 개방을 감수한다는 승인을 받는다. **기본은 roll-forward(수정 버전 배포)**.
- **V14 재실행 금지**: 권한 복구는 권한관리 화면/`PUT`으로만(기존 `docs/migration-guide.md:76` 참조 링크).
- **다중 인스턴스 미지원**: 저장 시 다른 인스턴스 캐시는 무효화되지 않는다(세션 레지스트리·레이트리밋과 같은 전제).

PR 본문 "배포·롤백 주의"에는 위 다섯 줄 요약 + "배포 직후 동작 변화: ADMIN 사이드바에 '권한 관리' 메뉴 추가, MANAGER 동작은 변화 없음(권한을 바꾸기 전까지)"을 적는다.

---

## 9. 위험과 미결정

### 사용자 결정이 필요한 트레이드오프

**없음.** 상위 계획 확정 결정 안에서 구현 세부만 정했다. 아래는 구현자가 근거와 함께 정한 사항으로, 이견이 있으면 계획 리뷰에서 다룬다.

| 사항 | 정한 내용 | 근거 |
|---|---|---|
| V15 날짜 컬럼 | NULL | F3 — Clock 규약·V14 선례, 화면 미표시 |
| 403 문구 | 공지 화면 JS에서 고정 한국어 문구 | F9 — 서버 핸들러 변경은 범위 밖·전 API 영향 |
| U4 안내 | 기존 409 문구 + `!canUpdate`일 때 한 문장 | F11 |
| `PermissionAction` 라벨 | enum 필드 추가 | F6 — 감사·API·화면 단일 출처 |
| 모르는·위임 불가 DB 행 | PUT이 건드리지 않음 | 판정기가 이미 무시, 정리는 마이그레이션 규칙 |

### 사용자 확인이 필요한 사실(결정 아님)

- F13: `adversarial-review/plan/`은 git 추적 대상이다(요청 전제와 다름). 이 문서를 PR ③에 포함할지(상위 계획은 #81에 포함됨) 커밋 단계에서 확인.

### 미확인 항목과 확인 방법

| 항목 | 확인 방법 |
|---|---|
| 메서드 계층 403의 `message`(영문 "Access Denied" 추정) | `NoticeControllerTest`에 READ만 가진 MANAGER `POST` 케이스로 `$.message` 단언(§7-2) |
| MariaDB에서 같은 테이블 서브쿼리를 쓰는 `INSERT…SELECT` | V15 마이그레이션 테스트, 실패 시 `SET @next_ord` 방식으로 교체 |
| `fa-user-lock` 아이콘 존재 | playwright 화면 확인 |
| Thymeleaf JS 인라인의 `Set<String>` 직렬화 형태(JSON 배열 기대) | 슬라이스/통합 테스트에서 렌더 HTML 문자열 확인 또는 playwright 콘솔 |

### 위험

| 위험 | 대응 |
|---|---|
| 병행 PR과 V15 번호 충돌 | 머지 직전 재확인·재번호(§5-4) |
| ③→①/② 롤백 시 V15 메뉴 표시 회귀 | `docs/deployment.md`에 비활성화 절차 명시(§8) |
| 화면을 연 사이 권한 회수 시 버튼이 남음 | 서버 403 + 고정 문구로 새로고침 안내(상위 계획 수용) |
| 동시성 테스트의 불안정성 | sleep이 아닌 `INNODB_LOCK_WAITS` 관측·`CyclicBarrier`(기존 패턴) |

---

## 10. 완료 기준

- ADMIN이 권한관리 화면에서 MANAGER 공지 권한을 바꾸면 **같은 세션의 다음 요청부터** 공지 API·페이지 접근, 사이드바 공지 메뉴, 공지 화면 버튼이 함께 바뀐다(통합 테스트 + playwright).
- 위임 불가 기능·`ROLE_ADMIN`·READ 없는 쓰기는 400, MANAGER는 권한관리 화면·API에 403, ADMIN은 어떤 저장으로도 권한을 잃지 않는다.
- 동시 저장은 하나만 성공하고 나머지는 409(실제 락 대기 관측). 성공한 변경은 `PERMISSION_UPDATE` 감사에 `ROLE_MANAGER vN→vM: …` 라벨로, 변경 없음은 `… 변경 없음` 라벨로 남고, 실패(400·404·409·커밋 실패)는 FAIL 감사 1건으로 남는다(FAIL은 기존 Aspect 정책상 `targetLabel` 없음 — R-3).
- 캐시 무효화는 커밋 후에만 일어난다(롤백·409·변경 없음은 무효화 없음). 최상위 호출의 커밋 실패 시 권한 행·버전 롤백, 무효화 0회, SUCCESS 감사 0건·FAIL 1건(R-2).
- `SecurityConfig` 무변경, 컨벤션 테스트·매트릭스 테스트·감사 동기화 테스트 통과, `./gradlew test` 통과(Clock 규약 확인 필요 시 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test`).
- `docs/deployment.md` 롤백 절, 패키지·루트 `CLAUDE.md` 갱신, playwright 결과 보고.
