# PLAN — 권한관리를 역할별에서 사용자별(MANAGER 개별)로 전환

> 상태: v5 (2026-10-03) — **적대적 리뷰 5라운드 ship, 신규 지적 0건**(정적 계획 리뷰). **PR A 구현 완료(2026-10-03, #86 `1b6b196` 머지)** — 구현 중 계획과 달라진 점은 문서 맨 아래 "구현 메모" 참조. **PR B(V22 DROP): 맨 아래 "PR B 계획" 절(v7, 적대적 리뷰 2라운드 ship) — 구현·검증 완료(2026-10-06, 커밋·PR 전), 결과는 "PR B 구현·검증 결과" 절.**
>
> **개정 이력**
> - PR B 2라운드: **ship, 신규 지적 0건**(codex `gpt-6.1-sol`). 제안 1건(B-3 4단계 문구 — 로컬 전체 테스트는 실행, UTC 재실행만 생략임을 명확히) 반영
> - v7 (PR B 1라운드 needs-attention, codex `gpt-6.1-sol`, 지적 3건):
>   - R-B1 수용(사실 확인: `v19_isRetrySafe`가 `flyway(null)` 뒤 V19 SQL을 직접 실행 — V22 후 `role_permission` 없음으로 실패): 이 시험도 `target("21")`로 고정, 최신 업그레이드 보존·삭제는 별도 시험이 맡는다(D-B4)
>   - R-B2 수용(사실 확인: `AdminMessageMigrationTest`가 V21만 빼고 전부 복사 → V22가 location에 있으면 적용된 V21은 future가 아니라 **missing**으로 분류돼 기본 validate 실패): 그 시험의 이전 앱 location을 **버전 ≤ 20만** 복사하도록 조정, 새 V22 롤백 시험도 버전 상한(≤ 21)으로 파일을 고른다(D-B4). 다른 마이그레이션 시험에 같은 패턴 없음(grep)
>   - R-B3 부분 수용(사실 확인: `prod-restore.sh:193`이 복구 후 **현재** 앱 컨테이너를 재기동 → PR B 이미지면 복원된 V21 이력 위에서 V22가 다시 실행돼 테이블을 또 지움): 백업 복원 런북에 "**V22 없는 이미지로 컨테이너를 먼저 교체(정지 상태) → 복구**" 순서를 명시, 실기에서 DB 수준으로 관측(D-B3 d, B-3 5). **기각한 부분**: prod 스택 전체 복원 drill — `prod-restore.sh`는 무변경이고 CI `prod-smoke` 왕복이 검증하며, 새 쟁점은 "어느 이미지가 기동되는가" 하나라 jar 수준 관측이 그 사실을 직접 증명한다
> - v6 (2026-10-06): PR B 계획 초안 추가(맨 아래 "PR B 계획" 절). 번호는 V20(알림)·V21(쪽지)이 이미 써서 **V22**
> - 5라운드: **ship, 신규 지적 0건**(codex `gpt-6.1-sol`). 실제 배포 승인은 §11의 테스트·브라우저 검증·구버전 기동 및 롤백 왕복 시험 결과가 충족돼야 한다는 단서
> - v5 변경(4라운드 needs-attention, codex `gpt-6.1-sol`, 지적 2건 전부 수용):
>   - R-12 수용: R-11에서 추가한 복구 절차가 "DDL이 적용됐으면 DROP"만 말해, V17 `success=1`·V18만 실패한 경우 두 객체를 다 지우면 V17이 재실행되지 않아 `permission_version` 없이 기동 → 절차를 **버전별 분기**(해당 버전의 성공 이력이 없는 잔여 객체만 DROP, `success=1`인 버전의 객체는 보존)로 정정하고 "V17 성공 + V18 실패" 복구 시험 추가(§8, §7-1 ⑬)
>   - R-13 수용: §4의 3단계(판정기 전환)와 4단계(API 전환)를 분리하면 3단계 종료 시점에 판정기는 `member_permission`을 읽는데 기존 역할 API·시험은 `role_permission`을 고쳐 "단계마다 전체 테스트 통과"를 만족할 수 없음(§3의 배포 결합 원칙이 검증 순서에 빠져 있었음) → **3·4단계를 하나로 합쳐** 판정기·캐시·API·서비스 전환과 관련 통합 테스트 이식을 같은 검증 단계에 둔다(§4)
> - v4 변경(3라운드 needs-attention, codex `gpt-6.1-sol`):
>   - R-9 수용(`AdminActionLogAspect.extractTargetId`가 반환 객체의 `getMemberId()`를 리플렉션 호출, 실패하면 null — 확인함): 결과 객체 계약이 계획에 없었음 → `MemberPermissionUpdateResult`가 `Long getMemberId()`를 노출, 변경 없음 결과도 동일, 시험으로 고정(§5-D, §7-1 ④)
>   - R-10 부분 수용: 더 보기는 offset 페이징이라 조회 사이 목록이 바뀌면 누락 가능 → **보장 문구를 "조회 사이에 목록이 바뀌지 않는 동안"으로 한정**하고 복구 경로 [새로고침](처음부터 재조회)을 추가(§5-G). **기각(커서 방식)**: ADMIN 전용 관리 화면에서 동시 승격·삭제가 겹치는 일은 드물고 이 한계는 `member/CLAUDE.md`에 이미 명시된 목록 API의 알려진 한계 — 이 PR이 목록 API를 바꾸는 것은 범위 밖
>   - R-11 수용: "DDL 1문 — 부분 실패 없음"은 SQL 문장 수와 Flyway 이력 기록을 혼동한 표현(MariaDB DDL 암묵 커밋 → DDL 커밋 후 이력 기록 전 중단되면 `success=0` 이력 또는 이력 없는 적용 상태가 남음) → 문구 정정, V17·V18 복구 절차(V13·V16 선례: 실제 상태 확인 → 비어 있는 객체 수동 DROP → `flyway repair` → 재기동)를 §8·§9에 추가, V16 선례(`MenuAccessRoleDropMigrationTest`)대로 실패 이력 복구 시험 추가(§7-1 ⑬)
> - v3 변경(2라운드 needs-attention, codex `gpt-6.1-sol`, 지적 3건 전부 수용):
>   - R-6 수용: 재배포 초기화(`DELETE FROM member_permission`)가 버전을 올리지 않아 R-1 방어를 우회 — 이전 매트릭스를 든 ADMIN 화면의 PUT이 성공해 과거 권한을 복원 → 초기화 SQL이 **같은 트랜잭션에서 모든 회원의 `permission_version`을 +1**(허용 행 0개 회원 포함)(§8, Q5)
>   - R-7 수용: "트래픽 수신 전 정리"는 절차가 보장하지 않고, 직접 SQL은 `PermissionChangedEvent`를 내지 않아 캐시(만료 없음)에 낡은 허용 행이 남을 수 있음 → 런북 순서를 **앱 정지 → 정리 트랜잭션 커밋 → 앱 기동**으로 고정, 앱이 켜진 채 실행했다면 **재시작 = 캐시 폐기**, 완료 기준에 정리 후 MANAGER 실제 403 확인 추가(§8, §11)
>   - R-8 수용: `contains` 검색만으로는 같은 아이디 접두 계정이 100명을 넘으면(예: `manager`+`manager001`~`manager100`) 특정 회원에 도달할 수 없음 → 목록에 **"더 보기"(다음 페이지 이어 붙이기, 기존 API `page` 파라미터)** 추가, 검색은 보조로 유지(§5-G, §7-1 ⑪, Q3)
> - v2 변경(1라운드 needs-attention, codex `gpt-6.1-sol`):
>   - R-1 수용: 역할 변경 시 버전 증가가 `deleted > 0`에 묶여 있어 권한 0개 회원은 역할을 왕복해도 오래된 PUT이 통과 → **역할이 실제로 바뀌면 삭제 건수와 무관하게 버전 +1**, 무효화 이벤트만 행 삭제 시에 발행(§5-E, §7-1 ⑥)
>   - R-2 수용: 롤백 → 구버전 운영 중 변경 → 재배포 시 `member_permission`이 낡은 채 되살아남(V19는 재실행되지 않음) → 재배포 전 절차(`member_permission` 비우기 = fail-closed)·왕복 시험·구버전 Flyway 기동 검증을 완료 기준에 추가(§8, §11, Q5)
>   - R-3 **기각**: DELETED 회원의 기존 세션은 "세션 만료는 best-effort"라는 기존 계약(`AdminSessionRevokeListener` — 실패 시 ERROR 로그·재저장으로 재시도, 루트 `CLAUDE.md`)과 같다. 이 PR 이전에도 삭제된 MANAGER의 낡은 세션은 역할 단위 허용 행으로 공지에 접근할 수 있었으므로 새 위험이 아니며, ADMIN 낡은 세션은 더 큰 노출이지만 같은 계약으로 수용돼 있다. 행 삭제 방어는 범위 확대라 추가하지 않는다. 다만 F8·§5-B의 "무해" 표현은 부정확해 "기존 세션 만료 계약과 동일"로 정정
>   - R-4 수용: 회원 전환 시 비동기 응답 역전으로 A의 매트릭스·버전이 B 선택 상태에 덮일 수 있음(서버 버전 검사는 회원이 달라도 같은 숫자면 통과) → 요청 세대 토큰·응답 `memberId` 일치 검사·조회/저장 중 선택 잠금(§5-G, §7-3)
>   - R-5 수용: 101번째 이후 MANAGER는 화면에서 편집 불가 → 목록에 아이디·이름 검색창 추가(기존 목록 API의 `userId`·`userName` 필터 재사용), 100명 초과 데이터로 부여·회수 시험(§5-G, §7-1, Q3 갱신). 확인 중 발견: 목록 API가 서버에서 이미 DELETED를 제외하므로 "클라이언트 DELETED 숨김" 문구 삭제
> - v1: 초안
>
> 근거: **정적 정찰 기준**(코드·테스트·문서를 열어 대조, 빌드·테스트 미실행). master = `3679f86`(권한관리 PR ①~④ #81~#84 머지·로드맵 반영 완료)
> 유형: feat/security · **인가 정책 변경**(MANAGER 위임 권한의 단위가 역할 → 회원) · **스키마 변경**(V17~V19, PR B의 V20 DROP) · 신규 의존성 없음 · `SecurityConfig` 변경 없음

## 0. 요약

지금은 ADMIN이 `ROLE_MANAGER` **역할 하나**의 공지사항 권한(조회·생성·수정·삭제)을 켜고 끄며, 모든 MANAGER가 같은 권한을 공유한다. 이를 **MANAGER 회원마다 따로** 켜고 끄도록 바꾼다. ADMIN 고정(DB 미조회), 코드 카탈로그(`AdminFeature`)가 최종 권위인 구조, 위임 불가 기능, 판정 함수 하나(URL 게이트 = 메서드 계층 = 사이드바)는 바꾸지 않는다. 판정 키만 `role` → `member_id`로 바뀐다.

## 1. 확정된 사용자 결정 (2026-10-03)

| # | 쟁점 | 결정 |
|---|---|---|
| D1 | 권한관리 메뉴 접근 | **ADMIN만**(현행 유지 — `PERMISSION` ADMIN_ONLY, 생성자 가드, `/admin/**` 캐치올, `hasRole('ADMIN')`) |
| D2 | 권한 단위 | **사용자별**(MANAGER 회원 개별 허용 행). 역할 단위 허용 행은 폐지 |
| D3 | 역할이 바뀔 때(MANAGER→ADMIN 등) | 그 회원의 개별 허용 행을 **같은 트랜잭션에서 삭제**(재강등 시 예전 권한이 조용히 되살아나지 않게) |
| D4 | 신규 MANAGER 기본 권한 | **없음**(대시보드·내 정보만 — 최소 권한). 생성 후 권한관리에서 부여 |
| D5 | 화면 | 기존 **권한관리 메뉴 유지**(`/admin/permission/manage`) — MANAGER 목록 + 선택 회원의 권한 매트릭스 |
| D6 | 범위 밖(검토 시 기각) | 새 역할·역할 테이블 확장, ADMIN의 DB 역할화, 기능·URL 규칙의 DB화, `MEMBER`·`PERMISSION` 위임 — lockout·권한 상승·기본 거부 붕괴 위험 |

**동작 변화(사용자 고지 완료)**: 지금은 MANAGER 계정을 만들면 바로 공지 CRUD가 열린다 → 전환 후에는 권한 0개로 시작한다. **기존 MANAGER는 배포 시점의 역할 권한을 그대로 복사**하므로 배포 직후 기존 계정의 동작은 같다.

---

## 2. 정찰 사실 표

### 2-A. 설계에 영향을 주는 사실

| # | 사실 | 근거 | 이 계획의 처리 |
|---|---|---|---|
| F1 | 판정기는 역할을 세션 `Authentication`의 권한 문자열로만 읽고, 허용 행 키도 `"ROLE_MANAGER"` 상수다 | `AdminPermissionEvaluator.java:27-28,72-75,129-135` | 회원 ID를 principal(`CustomUserDetails.getId()`)에서 읽는다. principal이 `CustomUserDetails`가 아니면 위임 기능 **false(fail-closed)** |
| F2 | 실제 로그인 principal은 `CustomUserDetails`(회원 엔티티 보유, `getId()`) | `config/auth/CustomUserDetails.java:22-24,41-44` | F1의 키 출처 |
| F3 | **슬라이스 테스트 다수가 `@WithMockUser(roles = "MANAGER")`** — principal이 `CustomUserDetails`가 아니라서 전환 후 위임 기능이 전부 거부된다 | Grep: `SecurityConfigTest`(:157,165 공지 허용 단언 등), `NoticeControllerTest`, `NoticeAttachmentControllerTest`, `MenuControllerTest`, `AdminMemberControllerTest`, `AdminActionLogControllerTest`, `RolePermissionControllerTest` | §7-1 테스트 주체 규칙 — **위임 기능을 다루는 MANAGER 테스트는 허용·거부 모두 `CustomUserDetails` 주체를 쓴다**(거부 단언이 "ID 없음"이라는 엉뚱한 이유로 통과해 약해지는 것 방지) |
| F4 | 통합 테스트(`AdminPermissionMatrixIntegrationTest`·`RolePermissionApiIntegrationTest`)는 이미 `CustomUserDetails` 주체를 쓰고, 권한은 `role_permission`에 직접 INSERT한다 | `AdminPermissionMatrixIntegrationTest.java:101-125`, `RolePermissionApiIntegrationTest.java:188-208` | 주체는 그대로 두고 INSERT 대상만 `member_permission`으로 이식 |
| F5 | 회원 쓰기 경로는 전부 `findByIdForUpdate`로 **회원 행을 잠근다**. 역할 변경(`updateAdminMember`)도 대상 행 잠금 아래에서 `changeRole` + 세션 만료 이벤트 | `AdminMemberService.java`(`updateAdminMember` — `findByIdForUpdate`, `roleChanged`, `AdminSessionRevokeEvent`), `MemberRepository.java:48` | 권한 저장도 **같은 회원 행**을 잠근다 → 권한 저장 ↔ 역할·상태 변경이 직렬화(§5-B) |
| F6 | `Member`는 `@Builder`로 생성되며 NOT NULL 컬럼은 `@Builder.Default`가 없으면 기존 생성 경로 3곳이 NULL을 INSERT해 깨진다(`profileImageKind` 선례 주석) | `Member.java`(`profileImageKind` Javadoc) | `permissionVersion`에 `@Builder.Default 0L` 필수 |
| F7 | `Member`는 `@DynamicUpdate` — 바뀐 컬럼만 UPDATE | `Member.java` 클래스 주석 | 버전 증가가 다른 컬럼을 되쓰지 않는다 |
| F8 | `DELETED` 계정은 수정 불가(409 "삭제된 계정은 수정할 수 없습니다.") — 사실상 종단 상태. 하드 삭제 경로는 없다 | `AdminMemberService.updateAdminMember` | 권한 저장도 `DELETED`면 409. 남은 행은 새 로그인이 불가해 정리하지 않는다. 이미 로그인한 세션이 남는 한계는 **기존 세션 만료 best-effort 계약과 동일**하며 이 전환이 넓히지 않는다(R-3) |
| F9 | 대상이 `ROLE_USER`면 관리자 API는 404로 숨긴다(`validateAdminTarget`, 쟁점 11) | `AdminMemberService.java:384,412` | 권한 API도 같은 정책(404) |
| F10 | 메뉴 관리 화면의 노출 안내는 **"MANAGER 한 명의 시점"**(`managerMenuUrlVisibility(snapshot)`)으로 가지치기한다 — 사용자별 체계에서는 그런 단일 시점이 없다 | `MenuService.java:355-360`, `MenuVisibility.java:53-62,96-113`, `menu/CLAUDE.md:23` | **"어떤 MANAGER든 볼 수 있는가"(카탈로그 분류만)**로 바꾼다 — 스냅샷 불필요(§5-D) |
| F11 | 노출 안내 문구는 이미 조건형이다: `PERMISSION` = "공지사항 조회 권한이 있을 때 MANAGER에게 표시" | `MenuVisibility.java:45-49` | 문구 변경 없음 |
| F12 | `GET /admin/api/members?userType=ROLE_MANAGER`(ADMIN 전용, 페이징 기본 20)가 이미 있다 | `AdminMemberController.java:61-63`, `AdminMemberSearchRequest` | 권한관리 화면의 MANAGER 목록에 재사용(새 목록 API 없음) |
| F13 | `/admin/api/members/me/**`는 상시 허용 `MY_INFO`의 게이트 패턴 → MANAGER도 URL 게이트 통과 | `AdminFeature.java:29-31` | `/admin/api/members/{id}/permissions`에 `id=me`가 오면 `Long` 변환 실패로 **400**(메서드 보안 이전, 처리 대상 아님). 시험으로 고정(§7-1 ⑨) |
| F14 | 감사 상수 `PERMISSION_UPDATE`(화면 라벨 "권한 변경")가 이미 있다 | `AdminActionTypes.java:33` | 재사용. `targetType`만 `MEMBER_PERMISSION`, `targetId` = 회원 ID |
| F15 | 최신 마이그레이션 V16 | `db/migration/` | V17·V18·V19(PR A), V20(PR B). 머지 직전 재확인 |
| F16 | `member.id`는 `bigint(20)` | `V1__init_schema.sql:20` | FK 컬럼 타입 |

### 2-B. 그대로 유지되는 전제

| 사실 | 근거 |
|---|---|
| ADMIN은 DB 미조회 항상 true, `ALWAYS` = ADMIN·MANAGER, `ADMIN_ONLY` = false, 쓰기 동작은 READ 의존 | `AdminPermissionEvaluator.java:67-85,129-135` |
| 캐시: generation + 단일 비행, REQUIRES_NEW 읽기 로드, 실패 fail-closed, 모르는·위임 불가 행 무시 | `RolePermissionCache.java` |
| 무효화: `PermissionChangedEvent` → AFTER_COMPLETION `invalidate()` | `PermissionChangedListener.java` |
| URL 게이트·`@RequirePermission`·컨벤션 테스트 | `permission/CLAUDE.md` "두 계층 인가"·"컨벤션 테스트" |
| `AdminSidebarAdvice`가 메모이즈 공급자로 `sidebarMenus`·`myPermissions`를 같은 스냅샷으로 계산 | `AdminSidebarAdvice.java:55-56` |

---

## 3. PR 분할

> **PR을 3개로 나누지 않는 이유**: 검토 단계에서 "① 스키마·판정기 → ② API·화면 → ③ 정리"를 제시했지만, ①만 배포되면 판정기는 `member_permission`을 읽는데 화면·API는 여전히 `role_permission`을 고친다. **ADMIN이 저장해도 효력이 없는 기간**이 생긴다(저장 성공 응답 + 실제 무변화 → 회수했다고 믿은 권한이 살아 있음). 그래서 판정 전환과 편집 수단 전환을 한 PR로 묶는다.

| PR | 브랜치 | 내용 | 되돌리기 |
|---|---|---|---|
| **A** | `feat/member-permission` | V17~V19, 판정기·캐시 키 전환, 회원별 API·화면, 역할 변경 시 행 삭제, 노출 안내 단순화, 역할 API·엔티티 제거(테이블은 남김), 문서 | 앱 롤백 가능(§8) — 단 회원별 회수 결과는 사라진다 |
| **B** | `chore/drop-role-permission` | V20 `DROP TABLE role_permission, permission_role`, 문서 정리 | **되돌릴 수 없음** — PR A가 운영에서 안정된 뒤 |

PR A는 크므로 커밋을 §4 단계 단위로 나눈다.

---

## 4. 구현 순서 (PR A — 단계마다 컴파일·`./gradlew test` 통과)

| 단계 | 내용 | 테스트 |
|---|---|---|
| 1 | V17(`member.permission_version`), V18(`member_permission`), V19(복사 시드), `Member.permissionVersion` 매핑, `MemberPermission` 엔티티·ID·리포지토리 | `MemberPermissionMigrationTest`(§7-1 ①) |
| 2 | 테스트 주체 도구(`TestPrincipals.manager(id, grants…)`)와 `PermissionTestConfig`의 회원 키 스냅샷 — **판정기 전환 전에** 기존 MANAGER 위임 테스트를 `CustomUserDetails` 주체로 옮긴다(이 단계에서는 동작 무변화라 그대로 통과해야 함) | 기존 테스트 전부 |
| 3 | **판정 전환 + 편집 수단 전환 + 시험 이식을 한 검증 단계로 묶는다(R-13)** — `PermissionSnapshot`·캐시를 회원 키로 전환(`RolePermissionCache` → `PermissionCache`), 판정기 principal ID 판정, `menuUrlVisibility`·`grantedActionKeys` 회원 기준, 노출 안내 `anyManagerMenuUrlVisibility`, `MemberPermissionService`·DTO·`MemberPermissionController`(GET/PUT), 역할 API·서비스·`RolePermission`·`PermissionRole` 엔티티 제거. 판정기만 먼저 바꾸면 기존 역할 API·시험이 판정에 쓰이지 않는 `role_permission`을 고쳐 "단계마다 전체 테스트 통과"가 성립하지 않는다(커밋은 나눌 수 있으나 `./gradlew test` 통과는 이 단계 끝에서 한 번) | `AdminPermissionEvaluatorTest`(회원 격리), `PermissionCacheTest`, `PermissionCacheIsolationIntegrationTest`(이식), `AdminPermissionMatrixIntegrationTest`(이식 + 교차 회원), `MenuServiceTest`·`MenuExposureSidebarIntegrationTest`, `MemberPermissionServiceTest`, `MemberPermissionControllerTest`, `MemberPermissionApiIntegrationTest`(즉시 반영·감사 이식), `MemberPermissionConcurrencyIntegrationTest`, 기존 `RolePermission*` 시험 제거 |
| 4 | `AdminMemberService.updateAdminMember` 역할 변경 시 행 삭제·버전 증가·무효화 이벤트 | `AdminMemberServiceTest` 추가, `MemberRoleChangePermissionIntegrationTest` |
| 5 | `permission/manage.html` 화면 전환(3단계 직후부터 이 단계 전까지 기존 화면은 제거된 역할 API를 호출해 동작하지 않는다 — 같은 PR 안의 중간 커밋 상태일 뿐 배포 단위가 아니다) | playwright(§7-3) |
| 6 | 문서(§9) | — |

---

## 5. 설계 상세

### 5-A. 스키마

**V17 `V17__add_member_permission_version.sql`** (DDL 1문 — 파일 안에서 일부만 성공하는 일은 없다. 단 MariaDB DDL은 암묵 커밋이라 **DDL 커밋 후 Flyway 이력 기록 전에 중단되면** 실제 스키마와 이력이 어긋날 수 있다 — 복구는 §8, R-11)

```sql
-- 회원별 권한 매트릭스의 낙관적 버전(잠금 아래 수동 비교 — @Version 아님). 허용 행이 0개여도 버전이 있어야 한다.
ALTER TABLE `member` ADD COLUMN `permission_version` bigint(20) NOT NULL DEFAULT 0;
```

**V18 `V18__create_member_permission.sql`** (DDL 1문)

```sql
-- MANAGER 회원 개별 허용 행. 행이 있으면 허용(거부 행 없음), ADMIN 행 없음. feature·action은 VARCHAR(DB enum 아님 — V13과 같은 이유).
CREATE TABLE `member_permission` (
  `member_id` bigint(20) NOT NULL,
  `feature` varchar(50) NOT NULL,
  `action` varchar(20) NOT NULL,
  PRIMARY KEY (`member_id`, `feature`, `action`),
  CONSTRAINT `fk_member_permission_member` FOREIGN KEY (`member_id`) REFERENCES `member` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
```

- DDL을 파일당 1문으로 나눠 V13의 "두 번째 CREATE만 실패" 같은 **파일 안 부분 성공**은 없다. 이력 불일치(DDL 적용·이력 없음 또는 `success=0`)는 별개로 남으므로 복구 절차가 필요하다(§8).
- `ON DELETE` 없음(RESTRICT) — 회원 하드 삭제 경로가 없다(F8). 테스트 정리 코드가 `DELETE FROM member`를 쓰면 `member_permission`을 먼저 지워야 한다(§7-1 주의).
- 콜레이션은 V13과 같이 `general_ci` → `feature`·`action` 대소문자·후행 공백 변형 행 문제는 그대로 남는다 → PR ③의 `existsById` 충돌 가드를 유지(§5-B).

**V19 `V19__copy_manager_permissions_to_members.sql`** (DML — 일회성 복사)

```sql
-- 배포 시점의 ROLE_MANAGER 유효 허용 행을 기존 MANAGER 회원 전원에게 복사한다 — 배포 직후 기존 계정의 동작은 같다.
-- 정확 일치(BINARY)·카탈로그 유효 행만 복사: 변형 행(read, 'READ ')·모르는 행은 판정기가 무시하던 것이라 옮기지 않는다.
-- READ 없는 쓰기 행은 판정기가 거부하던 것이라 READ가 없으면 아무것도 복사하지 않는다.
-- DELETED 회원은 제외(수정 불가 종단 상태). 이후 생성되는 MANAGER는 권한 0개(D4).
-- ⚠️ 일회성 초기화다. 성공 후 수동 재실행하면 ADMIN이 회원별로 회수한 권한을 되살린다(V14와 같은 경고).
INSERT INTO `member_permission` (`member_id`, `feature`, `action`)
SELECT m.`id`, rp.`feature`, rp.`action`
FROM `member` m
JOIN `role_permission` rp ON BINARY rp.`role` = 'ROLE_MANAGER'
WHERE m.`user_type` = 'ROLE_MANAGER'
  AND m.`status` <> 'DELETED'
  AND BINARY rp.`feature` = 'NOTICE'
  AND BINARY rp.`action` IN ('READ', 'CREATE', 'UPDATE', 'DELETE')
  AND EXISTS (SELECT 1 FROM `role_permission` r
              WHERE BINARY r.`role` = 'ROLE_MANAGER' AND BINARY r.`feature` = 'NOTICE' AND BINARY r.`action` = 'READ')
  AND NOT EXISTS (SELECT 1 FROM `member_permission` mp
                  WHERE mp.`member_id` = m.`id` AND mp.`feature` = rp.`feature` AND mp.`action` = rp.`action`);
```

- `NOTICE`·4동작을 리터럴로 쓴 이유: 현재 카탈로그의 위임 가능 기능은 `NOTICE` 하나이고(`AdminFeature.java:33-35`) 마이그레이션은 코드 카탈로그를 읽을 수 없다. 이 시점 이후 기능이 늘어도 이 파일은 다시 실행되지 않는다.
- `NOT EXISTS`는 부분 실패 후 재시도 중복 방지용이다.

**엔티티**
- `Member`: `@Builder.Default @Column(name = "permission_version", nullable = false) private Long permissionVersion = 0L;` + 도메인 메서드 `increasePermissionVersion()`(`updateDate`는 건드리지 않는다 — 회원 정보 변경이 아니다).
- 신규 `MemberPermission`(`@IdClass(MemberPermissionId)`, 필드 `memberId`·`feature`·`action` 문자열, `@Setter` 없음, 생성자만), `MemberPermissionRepository`:
  - `@Query("select p.memberId, p.feature, p.action from MemberPermission p") List<Object[]> findAllGrantRows()`
  - `List<MemberPermission> findByMemberId(Long memberId)`
  - `@Modifying @Query("delete from MemberPermission p where p.memberId = :memberId") int deleteByMemberId(Long memberId)` — 역할 변경 시 일괄 삭제(변형 행 포함 전부 삭제한다 — 역할이 바뀐 회원에게 남길 행은 없다)
- 제거: `RolePermission`·`RolePermissionId`·`RolePermissionRepository`·`PermissionRole`·`PermissionRoleRepository`. **테이블은 PR B까지 남긴다**(앱 롤백 대비 §8). `ddl-auto: validate`는 매핑된 엔티티만 검사하므로 매핑 없는 테이블이 남아도 기동에 문제없다.

### 5-B. 판정기·캐시

```text
PermissionSnapshot.Grant(Long memberId, AdminFeature feature, PermissionAction action)
PermissionSnapshot.has(Long memberId, feature, action)       // memberId null → false

AdminPermissionEvaluator.decide(snapshot, auth, feature, action)
  미인증·익명 → false
  ROLE_ADMIN → true                                           // 스냅샷 미조회(현행)
  ROLE_MANAGER 아님 → false
  switch kind
    ALWAYS     → feature.supports(action)                     // 스냅샷 미조회(현행)
    ADMIN_ONLY → false
    DELEGABLE  → id = memberIdOf(auth); id != null && grantedBy(snapshot.get(), id, feature, action)

memberIdOf(auth) = auth.getPrincipal() instanceof CustomUserDetails d ? d.getId() : null
grantedBy: supports && has(id, f, a) && (a == READ || has(id, f, READ))   // 의존 규칙 현행
```

- `menuUrlVisibility(supplier, auth)`·`grantedActionKeys(supplier, auth)`는 내부에서 `decide`를 쓰므로 자동으로 회원 기준이 된다. 시그니처 변경 없음 → `AdminSidebarAdvice` 무변경.
- **principal 의존**: `com.cms.admin.permission` → `com.cms.config.auth.CustomUserDetails` 의존이 새로 생긴다. `config.auth`는 `permission`을 참조하지 않아 순환이 없다(구현 시 Grep 재확인).
- 세션 principal의 `Member`는 로그인 시점 사본이지만 **ID만** 쓰므로 낡음 문제가 없다. 역할은 기존처럼 authority에서 읽고, 역할 변경 시 세션 만료(현행)로 맞춘다.
- 캐시(`PermissionCache`, 기존 클래스 이름 변경): 로드 대상이 `member_permission` 전체 행일 뿐 generation·단일 비행·REQUIRES_NEW·fail-closed·모르는 행 무시는 그대로다. 행 수는 MANAGER 수 × 최대 4라 전체 적재로 충분하다. 단일 인스턴스 전제(현행 한계) 유지.

### 5-C. API

| 메서드·경로 | 인가 | 성공 |
|---|---|---|
| `GET /admin/api/members/{id}/permissions` | URL `/admin/**` ADMIN 캐치올 + `@PreAuthorize("hasRole('ADMIN')")` | 200 매트릭스 |
| `PUT /admin/api/members/{id}/permissions` | 같음 + CSRF | 200 매트릭스(변경 후 상태, 변경 없음이면 현재 상태) |

- 경로: `api-conventions` "하위 관계는 중첩 경로"(`/admin/api/members/me/profile-image` 선례). `SecurityConfig` 무변경 — `/admin/api/members/{숫자}/permissions`는 어떤 `gatePatterns`에도 걸리지 않아 캐치올이 ADMIN만 허용한다(F13의 `me` 예외는 400).
- `@PathVariable Long id`.

**GET 응답 예**

```json
{
  "memberId": 12, "userId": "manager01", "userName": "김매니저", "status": "ACTIVE",
  "version": 3,
  "actions": [ {"action": "READ", "label": "조회"}, {"action": "CREATE", "label": "생성"},
               {"action": "UPDATE", "label": "수정"}, {"action": "DELETE", "label": "삭제"} ],
  "features": [ ... PR ③ 매트릭스와 같은 형식(ALWAYS·DELEGABLE·ADMIN_ONLY 행) ... ]
}
```

- `features`·`grantedActions`의 의미는 PR ③와 같다(판정기와 같은 유효 허용값). `RolePermissionMatrixResponse` → `MemberPermissionMatrixResponse`로 이름을 바꾸고 `role` 대신 회원 식별 필드를 둔다. `userName`은 화면 제목 표시용이며 DOM에는 `textContent`로만 넣는다.
- 조회는 `@Transactional(readOnly = true)` 한 트랜잭션에서 회원 → 허용 행을 읽는다(버전과 행이 같은 시점). 캐시를 읽지 않는다(현행 원칙).

**PUT 요청**: `{ "version": 3, "grants": [ {"feature": "NOTICE", "action": "READ"} ] }` — PR ③와 같은 DTO 규칙(`@NotNull Long version`, `@NotNull List<@NotNull @Valid Grant>`), 전체 교체, 빈 배열 = 전부 회수.

**상태 코드**

| 상황 | 상태 | `code` | 메시지(초안) |
|---|---|---|---|
| `{id}` 숫자 아님(`me` 포함) | 400 | `INVALID_REQUEST` | 기존 고정 문구 |
| 본문 JSON 오류·미정의 enum | 400 | `JSON_PARSE_ERROR` | 기존 |
| 필드 누락 | 400 | `VALIDATION_ERROR` | 기존 |
| 위임 불가 기능·미지원 동작·중복·READ 없는 쓰기 | 400 | `INVALID_REQUEST` | PR ③와 같은 문구 |
| 회원 없음 또는 `ROLE_USER` | 404 | `RESOURCE_NOT_FOUND` | "관리자를 찾을 수 없습니다."(F9) |
| 대상이 `ROLE_ADMIN` | 400 | `INVALID_REQUEST` | "관리자(ADMIN)는 모든 권한이 코드로 고정되어 변경할 수 없습니다." |
| (PUT) 대상이 `DELETED` | 409 | `RESOURCE_CONFLICT` | "삭제된 계정의 권한은 변경할 수 없습니다." |
| (PUT) `version` 불일치 | 409 | `RESOURCE_CONFLICT` | "다른 관리자가 먼저 권한을 변경했거나 계정의 역할이 바뀌었습니다. 새로고침한 뒤 다시 시도해 주세요." |
| (PUT) 변형 행 충돌 | 409 | `RESOURCE_CONFLICT` | PR ③와 같은 문구 |
| 락 대기 실패 | 409 | `RESOURCE_CONFLICT` | 기존 핸들러 |
| MANAGER·익명 | 403 / 401 | 필터 핸들러 | URL 게이트 |

- **검사 순서(PUT)**: 요청 내용(DB 접근 전) → **회원 행 잠금**(`memberRepository.findByIdForUpdate`, 이 트랜잭션의 첫 DB 조회) → 존재·`ROLE_USER`(404) → `ROLE_ADMIN`(400) → `DELETED`(409) → 버전(409) → diff. 대상 역할 검사가 DB를 봐야 하므로 PR ③("역할 → 내용")과 달리 **내용 400이 대상 400·404보다 먼저**다.

### 5-D. 서비스 `MemberPermissionService.replace`

```text
@Transactional
@AdminActionLogged(actionType = PERMISSION_UPDATE, targetType = "MEMBER_PERMISSION",
                   targetIdExpression = "memberId", targetLabelExpression = "auditLabel")
MemberPermissionUpdateResult replace(Long memberId, MemberPermissionUpdateRequest request)
  // 반환 객체 MemberPermissionUpdateResult는 Long getMemberId()를 노출해야 한다(R-9) — AdminActionLogAspect.extractTargetId가
  // 반환 객체의 getMemberId()를 리플렉션으로 호출하고, 없으면 예외를 삼켜 targetId=null 감사가 저장된다. 변경 없음 결과도 같다.
  requested = validateGrants(request.grants)                    // PR ③ 그대로
  member = memberRepository.findByIdForUpdate(memberId) or 404  // 첫 DB 조회 = 잠금
  validateTarget(member)                                         // ROLE_USER 404, ROLE_ADMIN 400, DELETED 409
  if member.permissionVersion != request.version → 409
  current = readValidRows(memberId)                              // 잠금 뒤 읽기 → 최신 커밋 기준
  added / removed diff
  변경 없음 → result(현재, "v3: 변경 없음")                        // 쓰기·버전·이벤트 없음
  added 키마다 existsById → true면 409(변형 행 가드, 자동 삭제 없음)
  삭제·저장, member.increasePermissionVersion()
  publish PermissionChangedEvent(memberId)
  return result(새 상태, "v3→v4: +공지사항.생성, -공지사항.삭제")
```

- **잠금 근거**: PR ③의 `permission_role` 행 잠금을 **회원 행 잠금**으로 바꾼다. 허용 행 0개여도 잠글 행이 있다. 같은 행을 `updateAdminMember`(역할·상태 변경)도 잠그므로 "권한 저장"과 "역할 변경"이 직렬화된다 → 역할 변경이 먼저 커밋되면 뒤 PUT은 `ROLE_ADMIN`(400) 또는 바뀐 버전(409)을 본다. 권한 저장은 회원 행 하나만 잠그고 `updateAdminMember`도 대상 행을 먼저 잠그므로 잠금 순서가 같아 새 교착 경로가 없다(최후 ADMIN 가드의 `findActiveAdminIdsForUpdate`는 대상이 활성 ADMIN일 때만 — 권한 PUT 대상은 MANAGER뿐이라 겹치지 않음).
- **감사 라벨**: `"v{old}→v{new}: " + 변경 목록` — 회원은 `targetId`로 식별되므로 라벨에 회원 이름(사용자 입력)을 넣지 않는다(현행 원칙: 라벨은 코드 상수·enum·숫자만). FAIL 감사는 라벨 없음(Aspect 정책, 현행).
- 결과 객체·컨트롤러 구조, 최상위 트랜잭션 진입점 조건, 감사 저장 실패 격리는 PR ③와 같다.
- 조회 `getMatrix(memberId)`: `findById` + `validateTarget`(단 `DELETED`도 조회는 허용 — 화면이 읽기 전용으로 보여 준다).

### 5-E. 역할 변경 시 행 삭제 (D3) — `AdminMemberService.updateAdminMember`

```text
if (roleChanged) {
    target.changeRole(effectiveRole, now);
    int deleted = memberPermissionRepository.deleteByMemberId(target.getId());
    target.increasePermissionVersion();                          // 삭제 건수와 무관 — 열려 있던 권한 화면의 다음 PUT은 409 (R-1)
    if (deleted > 0) {
        eventPublisher.publishEvent(new PermissionChangedEvent(target.getId()));   // 캐시 무효화는 행이 지워진 때만
    }
}
```

- 방향과 무관하게 삭제한다(MANAGER→ADMIN이 주 경로, ADMIN→MANAGER는 보통 0행 — 방어). 대상 행 잠금 아래이므로 같은 회원의 동시 권한 PUT과 직렬화된다.
- **버전은 항상 올린다(R-1)**: 잠금은 요청을 직렬화할 뿐 그 사이 역할이 `MANAGER→ADMIN→MANAGER`로 왕복했다는 사실을 검출하지 못한다. 권한 0개 회원도 역할 변경마다 버전이 올라 역할 변경 이전 화면의 오래된 PUT은 409다.
- 세션 만료 이벤트는 기존대로 역할 변경 시 이미 발행된다.
- `member` 서비스가 `permission` 리포지토리·이벤트를 직접 쓴다(패키지 간 의존 member → permission 추가). 서비스 빈 간 순환은 없다(`MemberPermissionService`는 `MemberRepository`만 의존).
- **감사**: 별도 감사 행을 만들지 않는다 — 역할 변경은 기존 `ADMIN_UPDATE` 감사로 남고 "역할이 바뀌면 개별 권한이 삭제된다"는 규칙을 문서로 고정한다(**결정 필요 Q1**).
- `DELETED`로의 상태 변경은 행을 지우지 않는다(F8 — 종단 상태, 로그인 불가, 권한 API도 409).
- 신규 MANAGER 생성(`createAdmin`)은 변경 없음 → 행 0개(D4).

### 5-F. 메뉴 노출 안내 단순화 (F10)

- `AdminPermissionEvaluator.managerMenuUrlVisibility(snapshot)`를 제거하고 스냅샷이 필요 없는 정적 판정 `anyManagerMenuUrlVisibility()`로 바꾼다: `url → AdminFeature.forMenuUrl(url).map(f -> f.getKind() != ADMIN_ONLY).orElse(false)` — "권한을 받으면 MANAGER가 볼 수 있는 메뉴인가".
- `MenuService.getMenuTree`는 스냅샷을 받지 않는다 → PR ②의 "스냅샷을 메뉴 조회보다 먼저 받는다(커넥션 풀 고갈 회피)" 주석과 제약이 사라진다.
- 결과 차이: 지금은 MANAGER 역할에 공지 READ가 없으면 공지만 담은 그룹이 `ADMIN_ONLY`로 안내되지만, 전환 후에는 `VISIBLE_BY_CHILDREN`("보이는 하위 메뉴에 따라 표시")가 된다 — 사용자별 체계에서는 이것이 맞는 안내다. 리프 문구는 그대로(F11).
- 사이드바(`menuUrlVisibility`)는 실제 로그인 회원 기준이라 "보이는데 403"이 없다는 보장은 유지된다.

### 5-G. 화면 `permission/manage.html` (D5)

- 레이아웃: 왼쪽 **MANAGER 목록**, 오른쪽 **선택 회원의 매트릭스**(PR ③ 매트릭스 렌더링·READ 연동·`dirty`·저장 중 잠금·409 재조회·400 초안 유지·`beforeunload` 규칙 그대로).
- 목록: `GET /admin/api/members?userType=ROLE_MANAGER&size=100&sort=userName,asc` 재사용(F12 — 서버가 이미 `DELETED`를 제외한다, `MemberRepositoryImpl.searchAdminMembers`). 각 행에 아이디·이름·상태 배지. `LOCKED`·`DISABLED`·`PASSWORD_EXPIRED`는 편집 가능(복귀 시 권한이 바로 적용되도록).
- **검색(R-5)과 더 보기(R-8)**: 목록 위에 아이디·이름 검색 입력을 둔다 — 같은 API의 `userId`·`userName` 파라미터(서버 `contains`)를 쓴다(새 API 없음). 검색은 `contains`라 정확 일치가 아니고 정렬 동률은 `id desc`라서, 검색만으로는 모든 회원에게 도달함이 보장되지 않는다 — 접두가 같은 계정이 100명을 넘으면 특정 회원이 계속 101번째에 남는다. 그래서 응답의 `last`가 false이면 목록 끝에 **[더 보기]**를 두어 `page+1`을 이어 붙인다(검색 조건 유지, 같은 API). 이로써 검색 없이도 모든 MANAGER에게 도달할 수 있다. 번호 페이지 UI는 만들지 않는다. **보장 범위(R-10)**: 목록 API는 offset 페이징이라 이 도달 보장은 **조회 사이에 회원 목록이 바뀌지 않는 동안**에만 성립한다(다른 ADMIN이 그 사이 승격·삭제·이름 변경을 하면 누락·중복 가능 — `member/CLAUDE.md`에 기록된 목록 API의 알려진 한계). 복구 경로로 목록 위에 **[새로고침]**(처음부터 재조회)을 둔다. 커서 방식은 이 PR의 범위가 아니다.
- 안내 문구: "관리자(ADMIN)는 모든 권한이 코드로 고정됩니다. 새로 만든 매니저는 권한이 없으므로 여기서 부여하세요. 역할이 바뀌면 개별 권한은 삭제됩니다."
- 회원 전환: `dirty`면 전환을 막고 "저장하지 않은 변경이 있습니다 — 저장하거나 [되돌리기] 후 선택하세요"를 표시한다(브라우저 `confirm` 대화상자를 쓰지 않는다).
- **비동기 응답 결합 규칙(R-4)**: 화면은 `selectedId`·`loadSeq`(요청 세대 카운터)·`matrix`(응답)·`draft`를 함께 관리한다. 조회를 보낼 때마다 `loadSeq`를 올리고, 응답이 도착했을 때 **자신의 세대가 현재 `loadSeq`와 같고 `response.memberId === selectedId`일 때만** 반영한다(아니면 폐기). 조회·저장 중(`busy`)에는 목록 선택을 `disabled`로 잠근다. 저장 요청의 URL·본문 `version`은 반영된 `matrix`(= 마지막으로 정상 반영한 응답)에서만 가져오며, `matrix`가 없거나 `memberId`가 `selectedId`와 다르면 [저장]을 비활성화한다.
- 목록이 비면 "권한을 설정할 매니저가 없습니다. 회원 관리에서 매니저 계정을 만드세요."
- 회원 이름·아이디는 `textContent`로만 삽입한다.

---

## 6. 보안 점검표

| 항목 | 판단 |
|---|---|
| 권한관리 접근 | ADMIN만(D1). 새 API는 `/admin/**` 캐치올 + `hasRole('ADMIN')` + 컨벤션 테스트 |
| 자기 승격 | MANAGER는 권한 API에 닿지 못하고, ADMIN 대상은 400, 위임 불가 기능은 400 + 판정기 무시 |
| 다른 회원 권한 누수 | 판정 키가 principal ID — **교차 회원 시험 필수**(§7-1 ②) |
| principal 위조 | principal은 서버 세션의 인증 객체라 클라이언트가 바꿀 수 없다. `CustomUserDetails`가 아니면 거부 |
| 역할 변경 후 잔존 권한 | 같은 트랜잭션 삭제 + 버전 증가 + 캐시 무효화 + 세션 만료(현행) |
| 캐시 실패 | fail-closed(현행), ADMIN·상시 허용 무영향 |
| 기본 거부·URL 규칙 | `SecurityConfig` 무변경 |

---

## 7. 테스트

### 7-1. 신규·이식

| # | 시험 | 핵심 단언 |
|---|---|---|
| ① | `MemberPermissionMigrationTest`(V16→V19 업그레이드) | 활성·잠금 MANAGER 전원에 4행 복사, `DELETED`·ADMIN·USER 제외, READ 없으면 0행, 변형 행(`read`, `'READ '`, `role_manager`) 미복사, `permission_version`=0, 재실행 시 중복 없음 |
| ② | `AdminPermissionEvaluatorTest` | 회원 A 허용이 회원 B에 적용 안 됨, principal이 `CustomUserDetails` 아님 → 위임 기능 false·상시 허용 true, ADMIN은 공급자 미호출(현행) |
| ③ | `AdminPermissionMatrixIntegrationTest` 이식 | 기존 조합 × 핸들러 9개 + **교차 회원**(A만 허용 → B 403·변경 없음) |
| ④ | `MemberPermissionApiIntegrationTest`(PR ③ 이식) | 저장 결과·감사(**실제 변경과 변경 없음 모두 `targetId` = 대상 회원 ID**, R-9)·실제 로그인 세션 즉시 반영·커밋 직전 실패 주입(AFTER_COMPLETION)·감사 저장 실패 격리·변형 행 409 |
| ⑤ | `MemberPermissionConcurrencyIntegrationTest` | 같은 회원 동시 PUT 정확히 하나 성공(`INNODB_LOCK_WAITS` 관측), **PUT ↔ 역할 변경 직렬화**(역할 변경 먼저 → PUT 400/409, PUT 먼저 → 역할 변경 후 행 0) |
| ⑥ | `MemberRoleChangePermissionIntegrationTest` | MANAGER→ADMIN 시 행 삭제·버전 +1·캐시 무효화, 다시 MANAGER로 강등하면 공지 403. **0행 회원도 역할 변경마다 버전 +1**(무효화 이벤트는 없음), **`MANAGER→ADMIN→MANAGER` 왕복 뒤 왕복 이전 버전의 PUT은 409**(R-1), 역할이 안 바뀌는 수정(이름·이메일)은 버전 불변 |
| ⑬ | V17·V18 실패 이력 복구(R-11) — `MenuAccessRoleDropMigrationTest`(:113) 선례 | DDL이 적용됐는데 Flyway 이력이 `success=0`이거나 없는 상태를 만든 뒤: 단순 `migrate`는 거부되고, 실제 상태 확인 → **`success=1`이 아닌 버전의** 비어 있는 컬럼·테이블만 수동 DROP → `repair` → `migrate`가 성공해 V17~V19가 정상 적용(V19 복사 포함). **반례(R-12)**: V17 `success=1` + V18만 CREATE 후 이력 실패 상태에서 `member_permission`만 DROP하면 복구되고 `permission_version`은 보존돼야 하며, 두 객체를 다 지우면 복구 후 `permission_version`이 없어 `validate`가 실패함을 보여 절차의 분기 조건을 고정 |
| ⑫ | 롤백 후 재배포 정리(R-6·R-7) | `member_permission` 비우기 + `permission_version` +1 트랜잭션 뒤: 허용 행 0개 회원 포함 전원의 버전이 올랐고, 정리 전 버전의 PUT은 409, 앱 재기동 후 기존 권한 보유 MANAGER의 공지 접근이 403 |
| ⑪ | 100명 초과 목록(R-5·R-8) | MANAGER 101명 이상일 때 검색으로 101번째 회원을 찾아 매트릭스를 열고 부여·회수까지 성공(API·화면). **반례**: 아이디 `manager`+`manager001`~`manager100`·같은 이름이면 검색이 101명을 반환하므로 [더 보기]로 `manager`에 도달해 부여·회수 |
| ⑦ | 신규 MANAGER | 생성 직후 대시보드 200·공지 페이지/API 403·사이드바에 공지 없음 |
| ⑧ | `MemberPermissionControllerTest`(슬라이스) | 상태 코드 표 전부, MANAGER 403, CSRF 없음 403 |
| ⑨ | `me` 경로 | MANAGER·ADMIN의 `GET /admin/api/members/me/permissions`가 200이 아님(400) |
| ⑩ | `MenuServiceTest`·`MenuExposureSidebarIntegrationTest` | 노출 안내가 스냅샷과 무관, 공지 READ를 모두 가진 MANAGER의 사이드바와 안내 일치 |

**테스트 주체 규칙(F3)**: 위임 기능(공지)을 다루는 MANAGER 테스트는 허용·거부 모두 `TestPrincipals.manager(id)`(`CustomUserDetails` + `with(user(...))`)를 쓴다. `@WithMockUser(roles = "MANAGER")`는 상시 허용·ADMIN 전용 경로 시험에만 남긴다. 변이 실험: 판정기가 회원 ID를 무시하고 아무 회원 행이나 허용하도록 바꾸면 ②·③이 실패하는지 확인한다.

**주의**: `member_permission` FK 때문에 `DELETE FROM member`를 쓰는 테스트 정리 코드는 먼저 `member_permission`을 지워야 한다(구현 시 Grep `DELETE FROM member`).

### 7-2. 제거

`RolePermissionServiceTest`·`RolePermissionControllerTest`·`RolePermissionApiIntegrationTest`·`RolePermissionConcurrencyIntegrationTest`·`RolePermissionCache*Test`(이식 후 제거). `PermissionMigrationTest`(V13·V14)는 과거 마이그레이션 시험이라 PR A에서는 유지하고 PR B에서 V20과 함께 조정한다.

### 7-3. playwright

ADMIN과 MANAGER 두 명(A·B)을 각각 별도 browser context로: ADMIN이 A에게만 공지 조회·생성 부여 → A 사이드바·[새 공지] 표시·[삭제] 숨김, B는 공지 메뉴 없음·직접 URL 403 → ADMIN이 A의 생성 회수 → A 새로고침 시 [새 공지] 숨김 → ADMIN이 A를 ADMIN으로 승격 후 다시 MANAGER로 강등 → A 재로그인 시 공지 없음. 권한 화면의 `dirty` 상태 회원 전환 차단·409 재조회 확인. **응답 역전(R-4)**: 회원 A 조회 응답을 지연시키고(playwright 라우트 지연) B를 선택하려 할 때 선택이 잠기는지, 잠금을 우회해(API 직접 호출 등) A 응답이 B 선택 뒤에 도착해도 화면이 폐기하고 B의 매트릭스를 유지하는지, 이 상태에서 저장이 B에게만 적용되는지 확인. **검색(R-5)**: 101명 이상 시드에서 검색 → 선택 → 부여 → 회수.

---

## 8. 배포·롤백

- **PR A 배포 직후**: 기존 MANAGER는 V19 복사로 동작 동일. 배포 후 생성한 MANAGER부터 권한 0개.
- **앱 롤백(PR A → 이전 버전)**: 이전 앱은 `role_permission`을 읽는다. 이 테이블은 PR A 배포 시점 그대로 남아 있으므로 **회원별 회수·부여 결과는 사라지고 배포 시점의 역할 단위 권한으로 돌아간다**(권한 부활 가능 — PR ①의 롤백 주의와 같은 유형). 회원별 회수 이력이 생긴 뒤에는 롤백보다 **roll-forward**가 기본이다. 이전 앱의 엔티티는 `permission_version`·`member_permission`을 매핑하지 않으므로 `validate`에 걸리지 않는다. Flyway가 이전 앱에서 "앞선 버전(V17~V19) 적용됨"을 실패로 보는지는 **미확인** — 확인 방법: 이전 커밋 이미지로 V19까지 적용된 DB에 기동해 본다(`prod-smoke` 백업복구 왕복 스크립트 재사용 가능 여부 포함). 결과를 `docs/deployment.md`에 적는다.
- **롤백 후 재배포 절차(R-2)**: 구버전 운영 중에는 역할 변경·회원 생성이 `member_permission`에 반영되지 않는다(구버전은 이 테이블을 모른다). V19는 일회성이라 재배포 시 다시 실행되지 않으므로 낡은 행이 되살아난다 — 예: 롤백 전 A에게 개별 CRUD 부여 → 구버전에서 A를 승격·강등(행 미삭제) → 재배포하면 A의 CRUD가 부활. 따라서 **롤백했다가 신버전을 다시 배포할 때는 아래 정리를 먼저 한다**(fail-closed: 모든 MANAGER가 권한 0개로 시작 → ADMIN이 권한관리 화면에서 재부여). V19 재실행은 회수한 권한을 되살리므로 쓰지 않는다. 시험(`신버전 → 구버전에서 역할 변경 → 신버전`)으로 낡은 행이 정리 없이는 되살아남을 고정한다(**결정 필요 Q5** — 기본값은 fail-closed).

  ```sql
  -- 한 트랜잭션. 허용 행이 0개인 회원도 버전을 올려야 한다(R-6): 구버전에서 역할이 왕복했을 수 있고,
  -- 정리 전 화면을 연 ADMIN의 오래된 PUT이 과거 권한을 복원하지 못하게 한다.
  START TRANSACTION;
  DELETE FROM member_permission;
  UPDATE member SET permission_version = permission_version + 1;
  COMMIT;
  ```

  - **순서(R-7)**: ① 구버전 앱 정지 → ② 위 트랜잭션 커밋 → ③ 신버전 기동. 캐시는 메모리에만 있고 직접 SQL은 `PermissionChangedEvent`를 내지 않으며 캐시에는 만료가 없다 — 정리 전에 신버전이 먼저 떠서 낡은 행을 적재했다면 **앱을 재시작해야 캐시가 폐기된다**(단일 인스턴스 전제). 정리 후 PUT은 "변경 없음" 분기라 캐시를 무효화하지 않으므로 화면 저장으로는 복구되지 않는다.
  - **확인(R-7)**: 정리·기동 뒤 기존에 공지 권한이 있던 MANAGER 로그인으로 `GET /admin/api/notices`가 **실제 403**임을 확인한다(SQL 실행 기록만으로 완료 처리하지 않는다).
  - `docs/deployment.md`에 위 SQL·순서·확인 절차를 넣고, 정리 후 이전 버전(`permission_version` 갱신 전) PUT이 409인 시험을 더한다(§7-1 ⑫).
- **V17·V18 실패 복구(R-11)**: MariaDB DDL은 암묵 커밋이라 DDL 커밋 후 Flyway 이력 기록 전에 프로세스가 끊기면 실제 스키마와 `flyway_schema_history`가 어긋난다(V13·V16 선례). ① 실패 원인 제거·앱 인스턴스 정지 ② 실제 상태 확인 — `SHOW COLUMNS FROM member LIKE 'permission_version';`·`SHOW TABLES LIKE 'member_permission';`와 `SELECT version, success FROM flyway_schema_history WHERE version IN ('17','18','19');` ③ **버전별로 분기한다(R-12)** — 해당 버전의 `flyway_schema_history` 행이 `success=1`이면 그 버전의 객체는 **건드리지 않는다**(`repair`는 성공한 마이그레이션을 되감지 않고 재실행도 하지 않으므로 지우면 되살릴 방법이 없다). `success=0`이거나 이력이 없는데 DDL만 적용된 버전의 잔여 객체만 **수동 `DROP`**(시드 V19 전이라 비어 있어 안전: V17은 `ALTER TABLE member DROP COLUMN permission_version;`, V18은 `DROP TABLE member_permission;`). 예: V17 `success=1` + V18만 실패면 `member_permission`만 DROP하고 `permission_version`은 남긴다. ④ **같은 마이그레이션 구성으로 `flyway repair`**(실패 이력만 정리) ⑤ 재기동(`migrate`)으로 남은 버전이 실행돼 V17~V19 성공 확인. V19(DML)가 일부만 반영된 경우는 `NOT EXISTS`로 재시도해도 안전하다. `docs/migration-guide.md`에 V17~V19 행과 이 절차를 추가한다.
- **PR B(V20 DROP)**: 되돌릴 수 없다. PR A가 운영에서 안정된 뒤 배포하며, 배포 전 백업(V16 절차와 동일).

---

## 9. 문서 갱신 (PR A)

- `admin/permission/CLAUDE.md`: 개념(키 = 회원), 판정(principal ID·fail-closed), 캐시, 스키마(V17~V19), API·화면, 역할 변경 시 삭제, 롤백 주의 갱신. PR ③의 `permission_role` 잠금 설명 → 회원 행 잠금.
- `config/CLAUDE.md`: `/admin/notice/**` 행 "공지 조회 권한이 있는 `ROLE_MANAGER`" → "공지 조회 권한을 개별로 받은 `ROLE_MANAGER` 회원(2026-10 사용자별 전환)", `/admin/**` 행의 `/admin/api/roles/**` → `/admin/api/members/{id}/permissions`.
- `admin/member/CLAUDE.md`: 역할 변경 시 개별 권한 삭제·버전 증가, 신규 MANAGER 권한 0개.
- `admin/menu/CLAUDE.md`: 노출 안내가 "MANAGER 관점 스냅샷" → "카탈로그 분류(권한을 받으면 볼 수 있음)".
- 루트 `CLAUDE.md` 보안 규칙의 "코드 카탈로그 ∩ DB 허용 행" 문장에 "회원별" 명시.
- `docs/deployment.md` 롤백 절(§8). `docs/migration-guide.md`에 V17~V19 행과 실패 복구 절차(§8, R-11). `adversarial-review/plan/README.md` 26번 행.

---

## 10. 결정 필요 (기본값으로 진행 가능)

| # | 쟁점 | 기본값 | 대안 |
|---|---|---|---|
| Q1 | 역할 변경으로 삭제된 권한의 감사 | 별도 감사 없음 — 기존 `ADMIN_UPDATE` + 문서 규칙 | `ADMIN_UPDATE`에 `targetLabel`("역할 변경: 개별 권한 n건 삭제")을 추가 |
| Q2 | 기존 역할 API(`/admin/api/roles/**`) | PR A에서 제거(효력 없는 편집 수단을 남기지 않음) | — |
| Q3 | MANAGER 100명 초과 | 아이디·이름 검색창 + [더 보기](다음 페이지 이어 붙이기 — R-5·R-8, 번호 페이지 UI는 만들지 않음) | 번호 페이지 UI |
| Q4 | PR B(테이블 DROP) 시점 | PR A 운영 안정 확인 후 별도 | PR A에 포함(롤백 수단 상실 — 비권장) |
| Q5 | 롤백 후 재배포 시 낡은 `member_permission` | 앱 정지 → `DELETE FROM member_permission` + 전원 `permission_version`+1 한 트랜잭션 → 기동(fail-closed — MANAGER 전원 권한 0개로 시작, ADMIN이 재부여) | 롤백 중 역할 변경 이력을 수동 대조해 정리(운영 부담 큼) |

## 11. 완료 기준

- §7 시험 전부 통과(`./gradlew test`, `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` 포함), 변이 실험 기록
- `SecurityConfig` 무변경, 컨벤션 테스트 통과
- playwright 시나리오(§7-3) 통과 및 스크린샷 보고
- §9 문서 갱신
- 롤백 왕복 시험(§8)과 이전 앱의 V17~V19 적용 DB 기동 검증 결과, 재배포 정리 절차(순서·SQL·정리 후 MANAGER 403 확인)가 `docs/deployment.md`에 기록됨

---

## 구현 메모 (PR A, 2026-10-03)

계획과 다르게 구현했거나 구현 중 확인한 사실만 적는다.

- **테스트 인프라**: 계획 §4 단계 2의 `TestPrincipals`는 `TestMembers`(`com.cms.support` — 실제 회원 행 저장·`asMember`·`detached`·`delete`)와 슬라이스용 `@WithManager`(`com.cms.config`, 회원 ID `PermissionTestConfig.MANAGER_ID`)로 나눠 구현했다. `PermissionTestConfig`의 캐시 목은 그 회원 ID에 공지 4동작을 준 스냅샷을 돌려준다.
- **계획 §4 단계 3·4 병합(R-13)**대로 판정기·캐시·API·서비스 전환과 시험 이식을 한 단계로 구현했다. 캐시 클래스는 `RolePermissionCache` → `PermissionCache`로 이름을 바꿨다.
- **메뉴 노출 안내**: `MenuService.getMenuTree`가 스냅샷을 더 받지 않아 PR ②의 "스냅샷을 메뉴 조회보다 먼저 받는다" 제약과 시험이 사라졌고, `MenuServiceTest`의 "공지 권한 회수 → 그룹 ADMIN_ONLY" 시험은 "위임 가능 기능 URL만 담은 그룹은 VISIBLE_BY_CHILDREN"으로 바뀌었다.
- **감사 `targetId`**: 반환 객체 `MemberPermissionUpdateResult.getMemberId()`가 `AdminActionLogAspect.extractTargetId`의 입력이다(R-9). 변경 없음 결과도 같다.
- **화면 검증(playwright, 2026-10-03, 일회용 MariaDB·8081)**: 목록 100명 + [더 보기] 107명·아이디 검색, READ 자동 체크, dirty 상태 회원 전환 차단, 저장 후 DB 행·버전 0→1·감사(`MEMBER_PERMISSION`, `target_id`=회원 ID), **같은 MANAGER 로그인 세션**에서 회수 직후 공지 API·페이지 403·사이드바에서 공지 제거·대시보드 유지, MANAGER의 권한 API 403·`me` 경로 400, 응답 지연+선택 잠금 우회 시 늦은 응답 폐기를 확인했다.
- **미확인(배포 승인 조건 그대로)**: 이전 앱이 V17~V19가 적용된 DB에서 Flyway 검증을 통과해 기동하는지(`docs/deployment.md`에 "미확인"으로 기록 — 되돌릴 일이 생기면 백업본으로 먼저 확인).

---

## PR B 계획 — 역할 단위 레거시 테이블 DROP (V22, 2026-10-06)

> 유형: chore · **스키마 변경(되돌릴 수 없음)** · 인가 정책 변경 없음 · `SecurityConfig`·Java 제품 코드 변경 없음 · 신규 의존성 없음
> 브랜치: `chore/drop-role-permission` · 기준 master = `9a6e3ef`

### B-0. 정찰 결과 (코드·테스트·문서를 열어 확인한 사실)

| # | 사실 | 근거 |
|---|---|---|
| B-F1 | 제품 코드(`src/main/java`)에 `role_permission`·`permission_role`·`RolePermission*`·`PermissionRole*` 참조 0건. 엔티티 매핑이 없어 `ddl-auto: validate`는 두 테이블 존재 여부와 무관 | 저장소 전체 grep(adversarial-review 제외) — 참조는 마이그레이션 V13·V14·V16(주석)·V19, 테스트 2개, 문서뿐 |
| B-F2 | 최신 마이그레이션은 V21. V19(회원별 복사)가 `role_permission`을 읽으므로 DROP은 반드시 V19 뒤 — 새 번호 V22면 신규 DB(V1→최신)에서도 순서가 보장된다 | `db/migration/` |
| B-F3 | `role_permission`이 FK `fk_role_permission_role`로 `permission_role`을 참조 → **자식(`role_permission`)부터** 지워야 한다 | V13 |
| B-F4 | 테스트 파급 2곳: `PermissionMigrationTest`(V12→**최신** migrate 후 두 테이블·시드를 단언, `seedIsOneTimeInitialization`도 최신 migrate 뒤 `role_permission`을 조작) · `MemberPermissionMigrationTest.upgrade_copiesRoleGrantsToActiveManagersOnly`(최신 migrate 뒤 `role_permission` 4행 단언, 146행). 나머지 V16 준비(`v16Database`)는 `target("16")`이라 영향 없음 | 두 파일 |
| B-F5 | 모든 통합 테스트는 공용 Testcontainers DB(V1→최신 전체 적용)로 기동 → V22 추가 후 **테이블 없이 현재 앱이 뜨는 것**을 전체 스위트가 자동으로 증명 | `MariaDbContainerSupport`, `src/test/java/CLAUDE.md` |
| B-F6 | 이전 앱 롤백 호환 시험 선례: `AdminMessageMigrationTest.rollbackToAppWithoutV21_startsAgainstV21Database`(V21 파일을 뺀 임시 디렉터리를 `filesystem:` location으로 써서 validate·migrate — Flyway 기본 `ignoreMigrationPatterns=*:future`) | 해당 시험 262행 |
| B-F7 | DROP 선례 V16: 단일 DDL·`IF EXISTS`·배포 전 백업 필수·실패 복구 문서(`migration-guide.md` "V16 실패 복구")·`MenuAccessRoleDropMigrationTest` | V16, migration-guide 22~30행 |
| B-F8 | PR A 직전 앱 = `3679f86`(Boot 3.5.16, Java 17, 마이그레이션 V1~V16, `RolePermission`·`PermissionRole` 엔티티 매핑). `docs/deployment.md` 353행이 "이 앱이 V17~V19 적용 DB에서 기동하는지 **미확인**"으로 남겨 둠 | `git show 3679f86:build.gradle` |
| B-F9 | 운영(prod) 실배포는 아직 없다(로드맵: 실배포 인프라 미착수). PR A의 "운영 안정" 게이트는 실운영 관측이 아니라 dev·CI 기준으로만 판단 가능 | 로드맵 "후속 과제 ①" |

**핵심 쟁점**: (1) DROP 파일 형태와 부분 실패 복구, (2) 테이블을 지울지 보관(이름 변경)할지, (3) 이전 앱(PR A 이전) 기동 여부 검증 방법과 V22 이후 롤백 표의 변화, (4) 기존 마이그레이션 테스트를 어떻게 조정할지.

### B-1. 설계 결정

**D-B1. 마이그레이션 형태 — `V22__drop_role_permission_tables.sql` 한 파일, `DROP TABLE IF EXISTS` 2문(자식 → 부모)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 한 파일 2문 + `IF EXISTS` (채택)** | 의미 단위 하나. 중간 중단(첫 DROP 커밋 후 끊김) 뒤에도 같은 SQL 재실행이 안전 → 복구가 **원인 제거 → `flyway repair` → 재기동**으로 끝나고 수동 DROP 분기가 없다 | MariaDB DDL 암묵 커밋으로 "파일 안 부분 성공"은 생긴다(그러나 IF EXISTS가 흡수) |
| B. V22·V23 두 파일 1문씩 | 이력이 문장 단위 | 파일만 늘고 A와 복구 난이도 같음(IF EXISTS로 이미 해결) |
| C. `IF EXISTS` 없이 | 예상 밖 상태를 큰 소리로 실패 | 부분 실패 후 수동 DROP 분기 필요(V13·V17~V19처럼 버전별 분기 절차) — 복구 절차가 복잡해진다 |

왜 A: V16이 같은 이유로 `IF EXISTS`를 썼고, 지울 대상의 **잔여 상태가 무엇이든 목표 상태(둘 다 없음)가 같다** — 생성 마이그레이션(V13 등)과 달리 "있어야 할 것을 잘못 지우는" 위험이 없다. **DDL 단독 파일**(DML 혼합 금지 규칙).

**D-B2. 지운다(DROP) — 이름 변경 보관은 하지 않는다**

| 선택지 | 판단 |
|---|---|
| **DROP + 배포 전 백업 필수 (채택)** | PR A부터 앱이 읽지도 쓰지도 않는 동결 데이터. 값은 V19가 이미 `member_permission`으로 옮겼고, 원본 값은 `make prod-backup` 논리 덤프에 남는다(V16과 같은 정책) |
| `RENAME TABLE … TO …_archived_v22` | 되돌리기는 쉬워지지만 이전 앱은 원래 이름을 매핑하므로 어차피 수동 개입 필요 → 백업 복원과 수고가 같고, 정리 목적(레거시 제거)을 못 이룬다 |
| 안전 가드(예: `member_permission`이 비어 있으면 중단) | 과설계 — `member_permission`이 빈 것은 정상 상태(신규 MANAGER 0개·전부 회수)라 가드가 정상 배포를 막는다 |

**D-B3. 롤백 호환 — 시험으로 고정할 것과 실기로 1회 확인할 것을 나눈다**

- **(a) PR B 앱 → PR A 시대 앱(현재 master `9a6e3ef`)**: 현재 앱은 두 테이블을 매핑하지 않으므로(B-F1) Hibernate 검증 무관, Flyway는 이력의 미래 버전 V22를 기본값으로 무시 → **기동 가능해야 한다.** 자동 시험으로 고정: V22 파일을 뺀 임시 location으로 V22 적용 DB를 `validate`·`migrate`(B-F6 선례 재사용).
- **(b) PR A 이전 앱(`3679f86`) on V17~V21 DB** — 22차 로드맵의 "미확인" 항목: 실기로 1회 확인한다. 방법: 일회용 MariaDB를 **현재 master 앱으로 V21까지 올린 뒤** `3679f86`을 별도 worktree에서 빌드한 jar로 같은 DB에 기동 → health·로그(Flyway "future" 경고, Hibernate validate 통과)·MANAGER 공지 접근이 역할 단위로 판정됨을 확인. 자동 시험으로 만들지 않는 이유: V22 이후에는 이 경로 자체가 불가능해져 회귀 대상이 아니다(일회성 사실 확인 → 문서 기록).
- **(c) PR A 이전 앱 on V22 DB**: **불가**(엔티티가 매핑하는 `role_permission`이 없어 `ddl-auto: validate` 실패)가 예상 — (b)에서 만든 jar로 함께 실기 확인해 문서의 "불가"를 추정이 아니라 관측으로 기록한다.
- **(d) 백업 복원으로 V22 이전 상태로 되돌리기(R-B3)**: `prod-restore.sh`는 복구 뒤 **그 시점의 앱 컨테이너**를 재기동한다. PR B 이미지인 채로 V21 백업을 복원하면 복원된 이력에 V22가 없어 기동 시 V22가 다시 실행돼 테이블이 또 지워진다(health는 정상). 런북 순서: ① 앱 정지 ② **V22 파일이 없는 버전의 이미지로 앱 컨테이너를 재생성하되 기동하지 않음** ③ `prod-restore.sh`(복구 후 그 컨테이너를 기동) ④ 두 테이블 존재·`flyway_schema_history` 최신 버전 확인. 실기에서 DB 수준으로 관측: V21 덤프 → V22 적용 → 덤프 복원 → master jar(V22 없음) 기동 = 테이블 유지 / PR B jar 기동 = 다시 삭제.
- 결과에 따라 `docs/deployment.md`의 롤백 표를 갱신: V22 배포 후 PR A 이전 앱으로의 롤백은 **백업 복원 외 수단 없음**.

**D-B4. 기존 마이그레이션 테스트 조정 — 과거 경로 시험은 `target("21")`로 고정, V22 효과는 새 시험으로**

- `PermissionMigrationTest`: 두 시험의 "최신" migrate를 `target("21")`로 바꾼다 — V13·V14 시드 동작은 **V22 이전 DB 상태에 대한 사실**이라 대상 버전을 고정하는 것이 의미에 맞다(삭제하지 않는 이유: V22 배포 전 DB에서 V14 수동 재실행 위험은 여전히 사실이고, 문서의 경고 근거다). 클래스 주석에 "V22에서 두 테이블이 지워지므로 V21까지로 고정" 한 줄.
- `MemberPermissionMigrationTest.v19_isRetrySafe`(R-B1): `flyway(null)` → `flyway("21")` — V19 재실행 안전성은 `role_permission`이 있는 V22 이전 DB에 대한 사실이다.
- `AdminMessageMigrationTest.rollbackToAppWithoutV21_startsAgainstV21Database`(R-B2): 이전 앱 location 복사 조건을 "V21 파일 제외"에서 **"버전 ≤ 20"**으로(파일명에서 버전 숫자 파싱). V22가 location에 남으면 적용된 V21이 missing으로 분류돼 실패하기 때문 — 이전 앱은 자기 버전 이하 파일만 갖는다는 실제 조건을 정확히 모사한다. 복사 개수 단언도 정확히 20으로.
- `MemberPermissionMigrationTest.upgrade_copiesRoleGrantsToActiveManagersOnly`: 146행 "역할 단위 행은 이 PR에서 지우지 않는다" 단언을 **"V16 → 최신: 복사된 회원별 행은 남고 두 역할 테이블은 없다"**로 교체(V19 복사가 V22 DROP보다 먼저 실행돼 데이터가 보존되는 **업그레이드 경로 전체**를 증명). 클래스 주석의 "V17~V19" 범위에 V22 언급 추가.
- 신규 `RolePermissionDropMigrationTest`(`com.cms.admin.permission`, `MariaDbContainerSupport`):
  1. **V21 → V22**: V21 DB(시드 4행 + MANAGER 회원·회원별 행)에서 migrate → 두 테이블 없음, `member_permission` 행·`member.permission_version` 그대로, V22 `success=1`.
  2. **이력 없는 부분 적용**(첫 DROP 커밋 후 중단 모사: `DROP TABLE role_permission` 수동 실행) → 재기동 migrate가 그대로 성공(IF EXISTS).
  3. **실패 이력 복구**: V21 DB에 `permission_role`을 참조하는 시험용 FK 테이블을 만들어 두 번째 DROP을 실패시킴 → `success=0` + 다음 migrate가 "failed migration to version 22"로 거부 → 시험용 FK 테이블 제거 → `repair` → migrate 성공, 두 테이블 없음. (첫 DROP은 이미 커밋된 상태 = 실제 부분 실패를 재현)
  4. **롤백 호환 (D-B3 a)**: V22 적용 DB를 **버전 ≤ 21 파일만 복사한** location으로 `validate`·`migrate` 성공, 테이블은 여전히 없음.

**D-B5. 배포 전제 — "PR A 운영 안정" 게이트의 해석**

실운영이 없으므로(B-F9) 게이트는 **dev·CI 관측**(PR A 머지(2026-10-03) 뒤 master에 #87~#100 14개 커밋이 회원별 권한 위에서 CI를 통과했고, 그중 #91 통합 검색은 판정기로 섹션 노출을 거른다 — 권한 관련 결함 수정 커밋 0건, `git log 1b6b196..9a6e3ef`)으로 판단한다. 실배포가 생기기 전에 PR B를 머지하면 **첫 실배포는 처음부터 V22까지 적용**되므로 "PR A 이전 앱으로의 롤백"이라는 경로는 운영에 존재한 적이 없게 된다 — 이 점이 지금 진행해도 되는 근거다(사용자 확인 사항으로 승인 단계에 명시).

### B-2. 설계 제약

- 머지된 마이그레이션(V13·V14·V19 포함) 수정 금지 — 주석의 "PR B가 지운다" 문구도 고치지 않는다.
- 제품 Java 코드·`SecurityConfig`·인가 판정 무변경(바뀌면 계획 이탈로 보고).
- 문서만으로 끝나는 "불가" 주장은 실기 관측(D-B3 c)으로 뒷받침한다.

### B-3. 작업 단계

1. 브랜치 `chore/drop-role-permission`.
2. `V22__drop_role_permission_tables.sql` 작성(헤더 주석: 근거·되돌릴 수 없음·백업·IF EXISTS 의미·DDL 단독).
3. 테스트 조정(D-B4) + 신규 `RolePermissionDropMigrationTest` → 해당 클래스 실행 → 변이 실험(① IF EXISTS 제거 → 시험 2 실패 ② DROP 순서 뒤집기 → 시험 1 실패(FK) 확인).
4. 로컬 전체 `./gradlew test` 실행(PR CI `test`·`prod-smoke` 통과도 완료 조건). `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` 재실행만 시각 코드 무변경이라 생략.
5. 실기(D-B3 b·c + 현재 앱 골든 패스): 일회용 MariaDB → master 앱으로 V21 → `3679f86` jar 기동 확인 → PR B 앱 기동(V22 적용, 로그 확인) → ADMIN 권한관리 화면에서 MANAGER 공지 권한 부여→MANAGER 공지 접근→회수→403 왕복(playwright, 스크린샷) → `3679f86` jar를 V22 DB에 기동해 실패 관측 → master 앱(V22 미보유)을 V22 DB에 기동해 성공 관측 → (d) V21 시점 덤프를 V22 DB에 복원 → master jar 기동 시 테이블 유지·V22 미실행, PR B jar 기동 시 다시 삭제 관측.
6. 문서: `docs/migration-guide.md`(V22 행·"V22 배포 전 백업과 실패 복구"·백업 복원 순서(D-B3 d)), `docs/deployment.md`(353행 "미확인" → 관측 결과, V22 이후 롤백 표, 330~345행 PR ① 이전 롤백 절에 "V22 이후 불가"), `admin/permission/CLAUDE.md`(스키마 절·롤백 주의·"남은 작업" 제거), 계획 인덱스 26행, 이 문서의 구현·검증 결과.

### B-4. 리스크

| 리스크 | 대응 |
|---|---|
| 되돌릴 수 없음 — V22 후 PR A 이전 앱 기동 불가 | 배포 전 백업 필수(V16 절차), 롤백 표에 명시, 실배포 전 머지라 운영에 그 롤백 경로가 생기지 않음(D-B5) |
| 두 번째 DROP 실패 시 `success=0`으로 기동 거부 | IF EXISTS + `repair` 절차 문서화, 시험 3이 재현 |
| DROP의 메타데이터 잠금 대기(다른 세션이 테이블을 열고 있을 때) | 앱은 두 테이블을 열지 않음(B-F1). 운영자가 수동 조회 중이면 대기 → `lock_wait_timeout` 초과 시 실패 = 시험 3과 같은 복구 |
| 백업 복원 후 PR B 이미지가 재기동돼 V22 재실행(R-B3) | 런북에 "이미지 교체(정지) → 복구" 순서 명시, 실기 (d) 관측 |
| 수동 SQL·외부 도구가 두 테이블을 읽고 있었다면 깨짐 | 저장소 내 참조 0건(B-F1). `docs/deployment.md` 340행의 `role_permission` 조회 SQL은 "V22 이전 DB 전용"으로 표시 |

---

## PR B 구현·검증 결과 (2026-10-06)

### Context
PR A(#86) 이후 동결 상태로 남아 있던 역할 단위 테이블을 지우고, 로드맵 22차에 "미확인"으로 남은 "전환 이전 앱이 V17~V19 DB에서 기동하는가"를 실기로 확인했다. 실배포가 없어 "PR A 운영 안정" 게이트는 dev·CI 관측으로 판단했다(D-B5, 사용자 승인 2026-10-06).

### 핵심 확정 사항
- V22 = 한 파일 `DROP TABLE IF EXISTS` 2문(자식 → 부모), DDL 단독. 보관(RENAME)하지 않고 백업으로 대체(D-B1·D-B2).
- 과거 마이그레이션 시험은 `target("21")`로 고정, V22 효과는 새 시험 클래스(D-B4). "이전 앱" location은 **버전 상한으로** 파일을 고른다(R-B2).
- 백업 복원으로 되돌릴 때는 V22 없는 이미지로 컨테이너를 먼저 교체(R-B3) — 문서화 + 실기 재현.
- 계획 대비 달라진 점: 없음.

### 구현 파일
- 신규: `src/main/resources/db/migration/V22__drop_role_permission_tables.sql`, `src/test/java/com/cms/admin/permission/RolePermissionDropMigrationTest.java`(4개 시험)
- 수정(테스트): `PermissionMigrationTest`(2개 시험 `target("21")`), `MemberPermissionMigrationTest`(업그레이드 시험 단언을 "복사 행 유지 + 두 테이블 없음"으로, `v19_isRetrySafe` `target("21")`), `AdminMessageMigrationTest`(이전 앱 location = 버전 ≤ 20, 복사 개수 정확히 20)
- 문서: `docs/migration-guide.md`(V22 행·"V22 배포 전 백업과 복구"·V14 경고 보충), `docs/deployment.md`(롤백 절 V22 이후 불가 표기·"미확인" → 실기 결과), `com.cms.admin.permission`의 `CLAUDE.md`(스키마·롤백·"남은 작업" 제거·이전 앱 location 규칙), `plan/README.md` 26행
- 제품 Java 코드·`SecurityConfig` 변경 없음

### 검증 결과
- **대상 시험**: `RolePermissionDropMigrationTest` 4 · `PermissionMigrationTest` 2 · `MemberPermissionMigrationTest` 9 · `AdminMessageMigrationTest` 6 — 전부 통과.
- **변이 실험**: ① `IF EXISTS` 제거 → 부분 적용 재실행·실패 repair 2개 실패 ② DROP 순서 뒤집기 → FK 위반(`foreign key constraint fails`)으로 3개 실패 ③ (R-B2 전제) 쪽지 시험을 원래 "V21만 제외" 필터로 되돌리면 `Detected applied migration not resolved locally: 21.`로 실패 — 셋 다 원복.
- **전체**: `SPRING_PROFILES_ACTIVE=dev ./gradlew test` BUILD SUCCESSFUL — 138개 클래스 1360건, 실패·에러 0, 스킵 3(기존 Windows 심볼릭 링크 시험). 공용 Testcontainers DB가 V22까지 적용된 상태로 모든 통합 테스트가 기동 = 테이블 없이 현재 앱 정상. UTC 재실행은 시각 코드 무변경이라 생략.
- **실기(일회용 MariaDB 10.11 고정 digest, 포트 3399, dev 프로파일 8091)**:
  1. master(`9a6e3ef`) jar로 V21 적용, MANAGER `mgr1`(회원별 권한 0개) 생성, V21 덤프 저장.
  2. **전환 이전 앱(`3679f86`) on V21 DB → 기동 성공**(`newer than the latest available migration (16)` 경고만, Hibernate validate 통과). `mgr1` `GET /admin/api/notices` **200** — 역할 단위 시드 4행으로 판정(문서의 "회수 결과가 사라짐"이 실제 동작). → 22차 "미확인" 해소.
  3. PR B jar on V21 DB → V22 적용(`now at version v22`), `%permission%` 테이블은 `member_permission`만, V22 `success=1`. `mgr1` 공지 API 403(권한 0개).
  4. playwright(ADMIN): 권한 관리에서 `mgr1`에 공지 조회 부여 → DB 행 1·`permission_version` 0→1·감사 `PERMISSION_UPDATE SUCCESS target_id=2 "v0→v1: +공지사항.조회"` → `mgr1` 같은 세션 공지 API 200 → 회수 → 행 0·버전 2·감사 `"v1→v2: -공지사항.조회"` → 403. 경계: `mgr1`의 권한 API·공지/메뉴/로그/회원 관리 페이지 403, 대시보드 200, CSRF 없는 POST 403. ADMIN 공지·활동 로그 화면 정상. 스크린샷 `.playwright-mcp/prb-grant-saved.png`·`prb-revoke-saved.png`·`prb-admin-notice.png`·`prb-admin-log.png`(git 추적 제외 폴더).
  5. **전환 이전 앱 on V22 DB → 기동 실패**: `Schema-validation: missing table [permission_role]`.
  6. master jar(V22 없음) on V22 DB → 기동 성공(`newer than … (21)`), ADMIN 권한 API 200.
  7. **R-B3 재현**: V21 덤프 복원 → master jar 기동: 세 테이블 유지·최신 이력 21 / 이어서 PR B jar 기동: V22 재실행 → 두 테이블 다시 삭제·이력 22.
  - 정리: 컨테이너·worktree 삭제, 8091 포트 해제 확인.

### 이슈
- 실기 첫 기동 실패는 Git Bash PATH에 `java`가 없어서였다(환경 — `JAVA_HOME`의 corretto-17 경로로 해결). 코드 문제 아님.
- 긴 경로로 worktree 제거가 `Filename too long` — `\?\` 경로로 삭제 후 `git worktree prune`.

### 후속
- PR CI `test`·`prod-smoke` 통과 확인(머지 조건).
- 실배포 시 V22는 첫 배포에 함께 적용된다 — 배포 전 백업 절차는 V16과 같다.
