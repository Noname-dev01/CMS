# 사이드바 동적 렌더링 구현 (구현·검증 완료, 커밋 대기)

> 작업일: 2026-07-09 · 브랜치: `feat/sidebar-dynamic-rendering` (미커밋)
> 전체 테스트 통과 · Playwright ADMIN/MANAGER 양쪽 검증 완료

> **v3 변경 (Codex 리뷰 2차 반영 — 마이그레이션/문서만, Java 코드 변경 없음):**
> - **결정 1 — [P2 부분 수용] 레거시 null 행 백필을 "선택"에서 "필수 2단계"로 강화.**
>   `2026-07-09_add_menu_access_role.sql`: ① ADMIN 전용 URL(menu/log/member-manage/member-new)
>   레거시 행을 먼저 `ADMIN`으로 백필 → ② 나머지 null을 `ALL`로 확정.
>   코드 기본값(null→ALL)은 유지 — fail-closed(null→ADMIN)로 뒤집으면 레거시 공용 메뉴가
>   MANAGER에게 통째로 사라지는 더 큰 UX 회귀이고, 실제 접근 차단은 서버(@PreAuthorize)가
>   담당하므로 노출은 보안 문제가 아님. 올바른 지점은 마이그레이션 백필(리뷰어 제안과 동일).
>   스크래치 DB에서 레거시 null 행 5종으로 백필 결과 검증 완료.
> - **결정 2 — [P2 수용·구체화] non-dev 시드용 멱등 SQL 작성.**
>   1차 리뷰 high 지적과 동일 사안. 체크리스트 기록에서 나아가
>   `2026-07-09_seed_default_menus.sql`(menu_url/그룹명 기준 NOT EXISTS 가드) 작성.
>   스크래치 DB에서 2회 연속 실행으로 멱등성(7행 유지·부모 연결) 검증 완료.
>   Flyway 도입 시 repeatable migration으로 이관.

> **v2 변경 (Codex 적대적 리뷰 1차 + 사용자 결정 반영):**
> - **결정 1 — [medium 수용] assignableTypes → `@AdminPage` 마커 어노테이션 전환.**
>   `AdminSidebarAdvice`가 `@ControllerAdvice(annotations = AdminPage.class)`로 매칭한다.
>   수동 열거 목록 유지보수가 사라지고, 누락은 `AdminPageAnnotationConventionTest`
>   (클래스패스 스캔: com.cms.admin의 모든 페이지 컨트롤러에 @AdminPage 강제)가 잡는다.
>   대표 페이지 4곳이 sidebarMenus·currentUri를 받는지 `AdminSidebarAdviceTest`
>   (@ParameterizedTest)로도 검증한다.
> - **결정 2 — [high 부분 수용] dev 전용 시딩 지적은 운영 준비 체크리스트로 기록.**
>   이 프로젝트는 prod 골격을 의도적으로 제거한 dev 전용 상태(#3)라 "프로덕션 빈 사이드바"는
>   현 단계에 발생 불가한 시나리오. 다만 운영 전환(3단계) 시 기본 메뉴를 idempotent
>   migration으로 이관해야 한다는 지적은 타당 → 아래 "운영 준비 체크리스트"에 기록.

## Context

메뉴 관리(#5)에서 Menu CRUD·트리 API·관리 화면은 완성됐지만, 정작 사이드바(`sidebar.html`)는
하드코딩 상태였다. `sec:authorize`로 역할별 노출을 정적으로 구분했고, SB Admin 2 테마 잔재
(Components/Utilities/Pages/Charts/Tables 죽은 링크)도 그대로 남아 있었다. 즉 **메뉴 데이터와
실제 내비게이션이 연결되지 않아 메뉴 관리 기능이 의미를 갖지 못하는 상태**였다.

이 작업은 사이드바를 Menu 데이터 기반 동적 렌더링으로 전환해 메뉴 관리 기능을 완결시킨다.
"1단계(마무리·정리) → 2단계(콘텐츠 도메인) → 3단계(운영 준비)" 로드맵의 1단계 첫 항목.

## 핵심 확정 사항 (불변식)

- **노출 범위는 Menu 데이터가 보유**: `access_role` 컬럼 추가. 코드(sec:authorize)가 아니라
  데이터가 역할별 노출을 결정한다.
- **`MenuAccessRole` 전용 enum (`ALL` / `ADMIN`)**: 계획 단계에서는 nullable `Role` 컬럼이었으나
  구현 시 조정. 이유: PATCH가 "null=기존값 유지" 시맨틱이라 nullable 컬럼으로는 ADMIN 전용 →
  공용 복귀가 불가능하다. `ALL`을 명시적 값으로 두면 복귀 가능. MANAGER 전용 메뉴 개념이 현재
  없으므로 2값이 정직한 모델링(과설계 방지).
- **DB null = ALL 정규화**: `ddl-auto: update`가 기존 행에 null을 남기므로 `Menu.getAccessRole()`
  커스텀 getter에서 null→ALL 정규화. 백필 마이그레이션 없이 레거시 행 호환.
- **accessRole은 사이드바 노출 제어일 뿐**: 실제 접근 차단은 Security(`@PreAuthorize`, URL matcher)가
  담당. MANAGER가 숨겨진 URL로 직접 접근 시 서버가 차단(검증 완료).
- **2단 렌더링 제한**: SB Admin 2 UI가 최상위 + collapse 하위까지만 지원. 3단 이하와, 부모가
  노출 대상에서 빠진 하위 메뉴는 그리지 않는다.
- **전용 advice, `@AdminPage` 마커 어노테이션 매칭** (v2에서 assignableTypes 대체):
  `basePackages` 방식(AdminViewAdvice)이면 `@RestController`에도 `@ModelAttribute`가 실행되어
  **매 API 호출마다 메뉴 DB 조회**가 나가기 때문에 페이지 컨트롤러로 범위를 제한한다.
  새 페이지 컨트롤러에는 `@AdminPage`만 붙이면 되고, 누락은
  `AdminPageAnnotationConventionTest`가 빌드에서 잡는다.
- **미인증 요청은 DB 조회 생략**: `getCurrentAdminId() == null`이면 빈 목록 반환(로그인 페이지 등).
- **시드는 dev 전용·빈 테이블일 때만**: `MenuDataLoader`(TestMemberLoader 패턴)가 기존 정적
  사이드바 구조를 그대로 데이터로 옮겨 시드.

## 구현 파일

### 신규
- `admin/menu/MenuAccessRole.java` — ALL/ADMIN enum (Role과 분리한 이유 javadoc 명시)
- `admin/menu/MenuDataLoader.java` — dev 시드 (대시보드·메뉴 관리[ADMIN]·회원 관리 그룹
  [조회·추가=ADMIN, 내 정보=ALL]·활동 로그[ADMIN])
- `admin/menu/dto/response/SidebarMenuResponse.java` — 사이드바 전용 경량 DTO
  (jstree용 MenuTreeResponse와 분리)
- `admin/AdminPage.java` — 페이지 컨트롤러 마커 어노테이션 (v2)
- `admin/AdminSidebarAdvice.java` — `@ControllerAdvice(annotations = AdminPage.class)`로
  `sidebarMenus`·`currentUri` 모델 주입 (Thymeleaf 3.1부터 `#request` 접근 불가 →
  currentUri를 advice에서 주입)
- `docs/migration/2026-07-09_add_menu_access_role.sql` — 수동 적용용 ALTER + 선택 백필

### 수정
- `Menu.java` — accessRole 컬럼(@Enumerated STRING, length 20), null→ALL getter, update() 시그니처
- `MenuCreateRequest`/`MenuUpdateRequest`/`MenuResponse`/`MenuTreeResponse.Data` — accessRole 필드
- `MenuService` — 생성 기본화(ALL)·수정 반영 + `getSidebarMenus(boolean isAdmin)` 신설
  (활성 메뉴 + 역할 필터 + 2단 조립)
- `templates/admin/fragments/sidebar.html` — 전면 재작성: th:each 동적 렌더링, 그룹 collapse
  (`collapseMenu{menuNo}` 고유 id), `currentUri` 비교로 active/펼침(`groupActive` SpEL 프로젝션
  `children.![menuUrl]` + `#lists.contains`), SB Admin 2 잔재 전부 삭제
- `templates/admin/menu/manage.html` — "노출 범위" select(공용/관리자 전용) + JS fillForm/payload 반영

### 테스트 (200건 전체 통과)
- `MenuServiceTest` +8: 생성 기본화/ADMIN 저장, PATCH 유지/ALL 복귀, 사이드바 필터
  (ADMIN 전체·MANAGER 제외·레거시 null=ALL·2단 제한)
- `MenuControllerTest` +2: accessRole 왕복(캡터+jsonPath), 잘못된 enum 값 400
- `AdminMainControllerTest` +2: 사이드바 렌더링(모델+HTML 내용), 미인증 시 빈 목록
- `@WebMvcTest` 5곳(AdminMain/AdminMember/AdminActionLog/ApiSecurityConfig/SecurityConfig)에
  `MenuService` 목 빈 추가 — **@WebMvcTest는 앱의 모든 @ControllerAdvice를 슬라이스 컨텍스트에
  포함**하므로 AdminSidebarAdvice의 의존 빈이 전 슬라이스에 필요(기존 AdminSecurityService
  목 패턴과 동일한 구조적 비용)
- (v2) `AdminPageAnnotationConventionTest` — com.cms.admin 클래스패스 스캔으로 @AdminPage 누락 감지
- (v2) `AdminSidebarAdviceTest` — 페이지 4곳(@ParameterizedTest)이 sidebarMenus·currentUri를 받는지 검증

## Playwright 검증 결과

| 시나리오 | 결과 |
|---|---|
| ADMIN 로그인 → 사이드바 | 대시보드·메뉴 관리·회원 관리(하위 3)·활동 로그 노출, 잔재 제거 확인 |
| `/admin/member/info` 진입 | 회원 관리 그룹 자동 펼침(`collapse show`) + li `active` + 내 정보 `collapse-item active` |
| 메뉴 관리 화면 | 노출 범위 select 렌더·선택 메뉴의 accessRole 폼 반영(메뉴 관리→ADMIN) |
| 저장 왕복 | 대시보드 ALL→ADMIN 저장→DB 확인→재선택 시 폼 반영→**ALL 복귀 저장**(설계 핵심) 확인 |
| MANAGER(`manager01`) 로그인 | 대시보드 + 회원 관리>내 정보만 노출. 메뉴 관리·활동 로그·관리자 조회/추가 숨김 |
| MANAGER가 `/admin/menu/manage` 직접 접근 | 서버측 차단(에러 페이지) — 노출 숨김과 접근 차단 이중 확인 |
| 회귀 | 활동 로그(20행)·관리자 조회(12행) 정상, active 하이라이트 정상 |

스크린샷: `sidebar-dynamic-admin.png`, `sidebar-dynamic-manager.png` (루트, 미추적)

## 검증 중 발견·해결한 이슈

1. **사이드바가 비어 보임** → 버그 아님. dev DB 볼륨에 이전 세션의 **비활성 테스트 메뉴 3건**이
   남아 있어 시드 로더가 건너뛰었고(count>0), 활성 메뉴 0건이라 정상적으로 빈 렌더링.
   menu 테이블 truncate 후 앱 재시작으로 시드 재생성.
2. **`./gradlew test` DB 접속 거부** → 셸 환경의 잘못된 `DB_PASS` 탓. DB_URL/DB_USER/DB_PASS/
   MAIL_* env를 명시해 통과. (CI는 원래 명시함)
3. **앱 재시작 시 Port 8080 점유** → TaskStop이 gradle 래퍼만 죽이고 JVM이 살아남음.
   `netstat -ano | grep :8080`으로 PID 확인 후 `taskkill //PID <pid> //F`.
4. **기존 이슈(범위 외)**: 전 페이지에서 `favicon.ico` 500 콘솔 에러 — 이번 변경과 무관.

## 문서·후속

- CLAUDE.md 갱신(로컬 전용, gitignore): menu 패키지 서술 현행화, AdminSidebarAdvice 추가,
  Menu 도메인 모델·menus API 5종 추가
- **커밋/PR 대기**: 사용자 확인 후 진행
- 후속 후보: 루트 스크린샷 8개 정리, favicon 500 수정, (1단계 계속) 회원 상태/역할 변경 API,
  비밀번호 재설정 완성-or-제거 결정

## 운영 준비 체크리스트 (3단계 진행 시 필수 — Codex 리뷰 지적 반영)

- [ ] **기본 메뉴 시드 migration 편입**: `MenuDataLoader`는 `@Profile("dev")` 전용이라
  prod/staging에서 실행되지 않는다. 멱등 시드 SQL은 작성·검증 완료
  (`docs/migration/2026-07-09_seed_default_menus.sql`, NOT EXISTS 가드) — Flyway 도입 시
  repeatable migration(R__seed_default_menus.sql)으로 이관만 하면 된다.
- [ ] **`access_role` 컬럼 추가 + 2단계 백필** (`docs/migration/2026-07-09_add_menu_access_role.sql`)
  을 migration 체계에 편입. 백필 순서(ADMIN 전용 URL 먼저 → 나머지 ALL)를 지킬 것 —
  건너뛰면 레거시 ADMIN 전용 링크가 MANAGER 사이드바에 노출된다(서버 차단은 유지되나 UI 회귀).
