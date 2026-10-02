# PLAN — 권한관리(MANAGER 위임 권한) 도입과 메뉴 노출 범위(`access_role`) 대체

> 상태: 🚧 PR ① 구현 중 (2026-10-02) — v4 ship(적대적 리뷰 3라운드), 사용자 U1~U8 확정. PR ①(카탈로그·판정기·캐시·V13/V14·공지 `@RequirePermission`) 구현·검증 완료, 코드 리뷰·커밋 단계
>
> **개정 이력**
> - 3라운드: **ship, 신규 지적 0건**. exposure의 최종 트리 기준 규칙이 sidebar.html 렌더링과 일치하고, 트리 API 노드 필드 설계가 현재 화면 흐름(manage.html)과 연결됨을 확인(정적 대조 기준 — 빌드·테스트 미실행)
> - v4 변경(2라운드, 신규 지적 2건 수용 — 사용자 결정 필요 없음):
>   - P-8 수용: v3 그룹 `exposure` 규칙이 §7 가지치기와 모순(자식이 전부 가지치기된 부모는 사이드바에서 자기 URL 리프로 그려지는데 안내는 `ADMIN_ONLY`) → `exposure`를 **가지치기를 끝낸 최종 트리** 기준으로 재정의(남은 자식 있음 → `VISIBLE_BY_CHILDREN`, 없음 → 리프로 자기 URL 기준, 비활성·깊이 밖 → `NOT_SHOWN`), 반례(`/admin` 부모 + ADMIN 전용 자식만) 시험 추가(§7)
>   - P-9 수용: `exposure`를 페이지 Advice 스냅샷으로 계산한다는 설계가 실제 상세 조회 경로(별도 JSON API, 한 행만 읽음)와 연결되지 않음 → **트리 조회 API(`MenuTreeResponse` 노드 필드)가 요청당 스냅샷 1회로 사이드바와 같은 순수 함수(`MenuVisibility`)를 호출해 계산**, `MenuResponse`·상세 API는 변경 없음, 트리 API 응답과 MANAGER 사이드바 렌더 모델을 같은 스냅샷으로 대조하는 시험 추가(§7, 영향 범위)
> - v3 변경(1라운드 no-ship, 지적 7건 전부 수용 — 사용자 결정 필요 항목 없음):
>   - P-1 수용: 캐시 로드가 호출자(사이드바) 읽기 트랜잭션의 REPEATABLE READ 낡은 스냅샷을 읽어 회수된 권한이 "최신"으로 설치될 수 있음 → 로드를 `REQUIRES_NEW` 독립 짧은 읽기 트랜잭션으로 격리 + 실제 MariaDB 교차 실행 테스트(§5, 테스트 계획 4)
>   - P-2 수용: "PR마다 독립 롤백 가능"은 거짓 — 판정기 이전 앱으로 되돌리면 회수한 MANAGER 권한이 되살아나고 V16 이후 ① 이전은 기동 불가 → **롤백 호환표**·roll-forward 우선·배포 문서화·V16 전 백업(단계 절 아래 신설)
>   - P-3 수용: 동작 오표시를 못 잡고 "401/403이 아님"이 400·404·500을 허용으로 통과시킴 → 동작별 단일 권한 조합(READ+CREATE/UPDATE/DELETE)·처리기별 기대 표·유효 데이터/CSRF·저장 결과 단언(테스트 계획 2)
>   - P-4 수용: 컨벤션이 `/admin/api/**`만 덮어 비 API 쓰기 핸들러가 READ 게이트만 통과 → `/admin/**` 전체로 확대, GET/HEAD 읽기 전용만 면제(§3-3 규칙 1)
>   - P-5 수용(우회 미증명, 근거 부재가 결함): 경로 정규화·HTTP 메서드 경계 시험·새 매처 종류 미도입 명시(§3-1, 테스트 계획 2-b)
>   - P-6 수용: 그룹 메뉴 `exposure`를 자기 URL로 계산하면 오설명 → 리프는 자기 URL, 그룹은 보이는 후손 기준(`VISIBLE_BY_CHILDREN`), 요청당 권한 스냅샷 하나로 사이드바·버튼·노출 대상 계산(§5, §7)
>   - P-7 수용: V14는 일회성 초기화이며 복구 수단 아님(회수 권한을 되살림), V13 부분 성공 복구·V16 백업 절차 구체화(§4)
> - v2: **사용자 확정(2026-10-02)** — U1~U8 모두 권장안 채택(U1 공지 4동작 허용·U2 대시보드+내 정보 상시 허용·U3 위임 가능은 NOTICE만·U4 첨부 업로드·삭제는 UPDATE·U5 기존 ADMIN 노출 공지 메뉴도 허용·U6 매핑 먼저 제거 후 DROP은 PR ④·U7 세션 Authentication 권한·U8 노출 안내 표시). "결정 필요" 표는 확정으로 전환
> - v1: 초안(코드 정찰 기반, 빌드·테스트 미실행). 작성은 Opus 모델
>
> 출처: 사용자 요청 "관리자(ADMIN)가 운영 중에 MANAGER가 볼 수 있는 화면을 바꾸고 싶다" → 메뉴 관리의 '노출 범위' 대신 **'권한관리' 메뉴**에서 MANAGER 권한을 지정
> 유형: feat · **인가 정책 변경 있음(이 문서가 사전 협의 제안서)** · **스키마 변경 있음**(테이블 2개 추가, `menu.access_role` 미사용화 후 제거) · 신규 의존성 없음

## Context

지금 MANAGER가 무엇을 할 수 있는지는 **코드 세 곳에 흩어져 고정**돼 있다 — `SecurityConfig`의 URL 규칙, 컨트롤러 `@PreAuthorize` 28곳, 그리고 사이드바 노출을 정하는 `menu.access_role`(ALL/ADMIN). 앞의 두 곳은 배포 없이는 바꿀 수 없고, 세 번째는 "보이기만" 바꾼다(실제 차단은 Security). 그래서 운영 중 ADMIN이 "MANAGER에게 공지 삭제는 막고 싶다"고 해도 방법이 없고, 반대로 메뉴를 `ALL`로 바꾸면 MANAGER 사이드바에 링크가 생기지만 눌러 보면 403이다(노출과 차단이 따로 논다).

이 계획은 **코드가 정한 기능 카탈로그 안에서, ADMIN이 MANAGER에게 기능×동작(조회/생성/수정/삭제) 권한을 켜고 끄는 '권한관리'**를 만든다. 사이드바 노출과 실제 접근 차단은 **같은 허용값 한 곳(판정기 + 캐시)**에서 도출해 다시는 어긋나지 않게 하고, `menu.access_role`은 제거한다.

## 사용자 결정 (확정 — 바꾸지 않음)

| # | 항목 | 결정 |
|---|---|---|
| D1 | 위임 범위 | 코드(카탈로그)에 **'위임 가능'으로 표시된 기능만** MANAGER에게 열 수 있다. 메뉴 관리·권한관리 자체·관리자 추가/수정 등은 **코드로 ADMIN 고정**(권한 상승·자기 잠금을 구조적으로 차단) |
| D2 | 권한 단위 | **화면(기능) + 동작(READ/CREATE/UPDATE/DELETE)**. 예: NOTICE × {READ, CREATE, UPDATE, DELETE} |
| D3 | `menu.access_role` | **제거하고 권한관리로 대체**. 기존 메뉴 데이터는 마이그레이션으로 이관, URL이 카탈로그에 없는 임의 메뉴는 ADMIN 전용 |
| D4 | 반영 시점 | **즉시 반영** — 요청마다 최신 허용값(캐시)으로 판정, 변경 시 캐시 무효화 |
| D5 | 역할 범위 | ROLE_ADMIN·ROLE_MANAGER 2종만. 새 역할·완전한 동적 RBAC는 범위 밖. 단 역할 테이블을 나중에 얹을 수 있게 데이터 모델을 막지 않는다(과설계 금지) |
| D6 | 핵심 원칙 | 노출(사이드바)과 실제 접근 차단은 **같은 허용값 한 곳에서 도출** |

## 추가 결정 U1~U8 (정찰 후 추가 — **2026-10-02 사용자가 전부 권장안으로 확정**)

| # | 쟁점 | 선택지 | 확정(권장안) | 근거 |
|---|---|---|---|---|
| U1 | 이관 시 MANAGER 기본 허용값 | (a) 현재 열린 범위 그대로: NOTICE × 4동작 전부 허용 (b) NOTICE READ만 허용 (c) 전부 닫고 ADMIN이 직접 켬 | **(a)** | 현재 `SecurityConfig.java:68-69` + `@PreAuthorize` 9곳이 MANAGER에게 공지 CRUD 전체를 연다(2026-07-20 승인). (b)(c)는 배포 순간 MANAGER 업무가 끊기는 동작 회귀 |
| U2 | 상시 허용 고정 목록(로그인한 ADMIN·MANAGER 전원, 권한관리에서 끌 수 없음) | (a) 대시보드 `/admin` + 내 정보(페이지·`/admin/api/members/me/**`) (b) (a)에서 대시보드를 위임 기능으로 | **(a)** | 로그인 성공 후 항상 `/admin`으로 이동한다(`VisitLoggingAuthenticationSuccessHandler.java:48-49`) — 대시보드를 끄면 로그인 직후 403. 내 정보는 비밀번호 변경·프로필 등 본인 계정 자기관리 경로라 끌 이유가 없다 |
| U3 | 초기 '위임 가능' 기능 구성 | (a) NOTICE만 (b) NOTICE + 활동 로그(READ) (c) NOTICE + 관리자 조회(READ) | **(a)** | 활동 로그는 모든 관리자의 행위·IP를 노출하는 감사 자료, 관리자 조회는 계정 목록·이메일 노출. D1 취지(최소 위임)상 기존 범위 유지가 안전. 추가는 코드 카탈로그 한 줄 + 마이그레이션으로 나중에 가능 |
| U4 | 공지 첨부파일 동작 분류 | (a) 업로드·첨부 삭제 = NOTICE **UPDATE**, 목록·다운로드 = READ (b) 업로드 = CREATE, 첨부 삭제 = DELETE | **(a)** | 화면은 **이미 저장된 공지**에서만 업로드한다(`notice/manage.html:938-962`, `currentDetail.id` 필요) — 공지 내용 수정에 해당. (b)면 CREATE만 받은 MANAGER가 남의 공지에 파일을 붙일 수 있다(소유권 없음, `notice/CLAUDE.md:14`). 부작용: 첨부가 있는 공지는 삭제 전 첨부를 지워야 하므로(409, `notice/CLAUDE.md:16`) **DELETE만 있고 UPDATE 없는 MANAGER는 첨부 있는 공지를 못 지운다** — 화면에 안내 |
| U5 | 노출 범위가 `ADMIN`인데 URL이 위임 기능(공지)인 기존 메뉴 | (a) 무시하고 U1대로 허용 → 그 메뉴가 MANAGER 사이드바에 **새로 보임** (b) 이 경우 NOTICE 권한을 아예 안 줌 | **(a)** | 지금도 그 MANAGER는 공지 화면·API에 **접근 가능**(숨김은 표시만). (b)는 실제 접근을 빼앗는 회귀. 배포 전 점검 SQL(설계 §8)로 해당 행이 있는지 확인해 공지 |
| U6 | `menu.access_role` 컬럼 제거 시점 | (a) PR ②에서 엔티티 매핑만 제거(컬럼 존치) → PR ④에서 `DROP COLUMN` (b) PR ②에서 즉시 DROP (c) 영구 존치 | **(a)** | 매핑만 빼면 `ddl-auto: validate`는 남는 컬럼을 문제 삼지 않고(엔티티→컬럼 방향만 검사), 롤백 시 이전 앱이 그대로 뜬다. DROP은 되돌릴 수 없어 ②가 운영에서 검증된 뒤 분리 |
| U7 | 권한 판정 기준 역할 출처 | (a) `Authentication.getAuthorities()`(로그인 시점 세션 권한) (b) 매 요청 DB의 `member.user_type` | **(a)** | URL 규칙·`@PreAuthorize`가 이미 (a)를 쓴다. 역할 변경 시 세션 강제 만료(`AdminMemberService.java:252-259`)가 이미 있어 정합. (b)는 매 요청 회원 조회 추가 |
| U8 | 메뉴 관리 화면에 "이 메뉴는 누구에게 보이는지" 읽기 전용 안내 | (a) 표시(`상시 / 공지사항 권한 / ADMIN 전용`) (b) 표시 안 함 | **(a)** | 노출 범위 입력이 사라지면 ADMIN은 임의 메뉴가 왜 MANAGER에게 안 보이는지 알 길이 없다. 서버가 계산한 문자열 1필드라 비용 작음 |

## 정찰에서 확정한 사실

### A. 엔드포인트 전수와 현재 허용 역할

| 엔드포인트 | 현재 허용 | 근거 |
|---|---|---|
| `GET /admin`(대시보드) | ADMIN·MANAGER (URL) | `AdminMainController.java:18`, `SecurityConfig.java:64` |
| `GET /admin/login`, `/admin/login-error`, `/admin/password-reset`, `/admin/password-reset/confirm` | 공개 | `AdminMainController.java:25-43`, `SecurityConfig.java:58-60` |
| `POST /admin/api/password-reset-requests`, `/admin/api/password-resets` | 공개(CSRF 필요) | `PasswordResetController.java:37,49`, `SecurityConfig.java:61` |
| `GET /admin/member/info`(내 정보 페이지) | ADMIN·MANAGER | `AdminMemberPageController.java:23`, `SecurityConfig.java:65` |
| `GET /admin/member/new`, `/admin/member/manage` | ADMIN (`/admin/**` 캐치올) | `AdminMemberPageController.java:13,18`, `SecurityConfig.java:71` |
| `/admin/api/members/me` GET·PATCH, `/me/profile-image` PUT(multipart)·PUT(JSON)·DELETE·GET, `/me/password` PATCH — 7개 | ADMIN·MANAGER (URL + `@PreAuthorize hasAnyRole`) | `AdminMemberController.java:108-174`, `SecurityConfig.java:66` |
| `/admin/api/members` POST·GET, `/{id}` GET·PATCH, `/{id}/profile-image` GET — 5개 | ADMIN (`@PreAuthorize hasRole`) | `AdminMemberController.java:47-98` |
| `GET /admin/notice/manage` | ADMIN·MANAGER | `NoticePageController.java:13`, `SecurityConfig.java:68` |
| `/admin/api/notices` GET·POST, `/{id}` GET·PATCH·DELETE — 5개 | ADMIN·MANAGER (URL + `hasAnyRole`) | `NoticeController.java:32-84`, `SecurityConfig.java:69` |
| `/admin/api/notices/{noticeId}/attachments` POST·GET, `/{attachmentId}/content` GET, `/{attachmentId}` DELETE — 4개 | ADMIN·MANAGER | `NoticeAttachmentController.java:41-88` |
| `GET /admin/menu/manage` | ADMIN | `MenuPageController.java:13`, `SecurityConfig.java:71` |
| `/admin/api/menus/tree` GET, `/{id}` GET·PATCH·DELETE, POST, `/structure` PUT — 6개 | ADMIN (`hasRole`) | `MenuController.java:33-98` |
| `GET /admin/log/manage` | ADMIN | `AdminActionLogPageController.java:22`, `SecurityConfig.java:71` |
| `GET /admin/api/logs` | ADMIN (`hasRole`) | `AdminActionLogController.java:27-28` |
| Swagger `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | ADMIN (dev 전용) | `SecurityConfig.java:62` |
| `/notices/**` GET·HEAD 공개, 그 외 denyAll / `/actuator/health` 공개, `/actuator/**` denyAll / 정적 4접두사+favicon GET·HEAD 공개 / ERROR 디스패치 공개 / 그 외 `anyRequest().denyAll()` | 비관리자 경로 — **위임 대상 아님** | `SecurityConfig.java:57,75-93` |
| `@PreAuthorize` 합계 28 = `hasRole('ADMIN')` 12(메뉴 6·회원 5·로그 1) + `hasAnyRole('ADMIN','MANAGER')` 16(내 정보 7·공지 5·첨부 4). **페이지 컨트롤러에는 `@PreAuthorize`가 없다**(URL 규칙만) | Grep 결과(위 컨트롤러 행) |

### B. 노출 범위(`accessRole`) 사용처

| 사실 | 근거 |
|---|---|
| 엔티티 필드 + null→ALL 정규화 getter, `update(...)` 인자 | `Menu.java:47-49,60-62,69-79` |
| enum `ALL`/`ADMIN` | `MenuAccessRole.java:11-18` |
| 사이드바 필터: `isAdmin`이 아니면 `ADMIN` 메뉴 제외, 부모가 빠지면 자식도 렌더링 안 됨(부모 맵에서 루트부터 조립) | `MenuService.java:391-404` |
| 사이드바 입력은 `AdminSidebarAdvice`가 `adminSecurityService.hasAdminAuthority()`(세션 principal의 `Member.userType`)로 넘김 — URL 규칙이 보는 `Authentication.authorities`와 출처가 다르다(둘 다 로그인 시점 값이라 현재는 일치) | `AdminSidebarAdvice.java:36`, `AdminSecurityService.java:23-31`, `CustomUserDetails.java:42-44` |
| 권한 불변식 "ALL 메뉴의 조상에 ADMIN 없음": 생성(`MenuService.java:77-79`), 수정 `validateRoleChange`(`:128-130,446-475`), 구조 반영 `validateFinalPlacement`(`:349-355`), 조상 검사 `inspectChain`(`:419-435`) | `MenuService.java` |
| `accessRole`이 수정 요청에 있으면 전체 행 잠금(`findAllForUpdate`) 경로를 탄다 | `MenuService.java:112-113` |
| DTO: `MenuCreateRequest:42`, `MenuUpdateRequest:47`, `MenuResponse:26,40`, `MenuTreeResponse:41,68` | 각 파일 |
| 화면: "노출 범위" select와 저장 페이로드 | `menu/manage.html:286-287,384,507,969` |
| Flyway: V1 컬럼 정의 `enum('ADMIN','ALL') DEFAULT NULL`, V2 백필(미분류 → ADMIN fail-closed), V3 시드(대시보드·회원 관리 그룹·내 정보 = ALL, 나머지 ADMIN), V9 공지 메뉴 = ALL | `V1:48`, `V2:18-48`, `V3:24-53`, `V9:15-18` |
| 테스트: `MenuServiceTest`(59회), `MenuServiceStructureTest`(9회), `MenuControllerTest:210-245` | Grep count |
| 문서: `menu/CLAUDE.md:9,12,13`, `.claude/commands/deploy-check.md:35,81`, `docs/migration-guide.md:17`, `docs/development-workflow.md:56` | Grep |
| Jackson은 Boot 기본 `FAIL_ON_UNKNOWN_PROPERTIES=false`(별도 설정 없음) → DTO에서 `accessRole`을 빼도 낡은 클라이언트가 보낸 값은 **조용히 무시**(400 아님) | `application.yml:8-11` |

### C. 인증·세션·기타

| 사실 | 근거 |
|---|---|
| 권한(authority)은 `Member.userType` 1개(`ROLE_ADMIN`/`ROLE_MANAGER`/`ROLE_USER`). 세션 principal은 로그인 시점 `Member` 스냅샷 | `CustomUserDetails.java:16,42-44`, `Role.java:4` |
| 메서드 보안은 `@EnableMethodSecurity` 기본값 | `MethodSecurityConfig.java:6-9` |
| 역할·상태 실변경 시 `AdminSessionRevokeEvent` → AFTER_COMMIT 세션 만료(best-effort). 최후 활성 ADMIN 가드 존재 | `AdminMemberService.java:213-223,252-259`, `AdminSessionRevokeListener.java:28-36` |
| `SessionRegistryImpl`(인메모리) — 이미 **단일 인스턴스 전제**. 레이트리밋 Caffeine도 단일 인스턴스 전제로 문서화 | `SecurityConfig.java:145-148`, `docs/deployment.md:286` |
| 로그인 성공 기본 이동 `/admin` | `VisitLoggingAuthenticationSuccessHandler.java:48-49` |
| `GlobalApiExceptionHandler`는 범위 없는 `@RestControllerAdvice` — 컨트롤러까지 도달한 `AccessDeniedException`은 **페이지 요청이어도 JSON 403**. 페이지 차단은 반드시 URL(필터) 계층에서 해야 HTML 403이 유지된다 | `GlobalApiExceptionHandler.java:41,330-340`, `SecurityConfig.java:121-135` |
| 사이드바: 자식이 있는 노드는 collapse 토글로 그려져 **자기 URL을 쓰지 않는다**. 자식 없는 노드만 링크(URL null이면 `#`) | `sidebar.html:47-97` |
| 사이드바는 `@AdminPage` 컨트롤러에만 주입(REST에 메뉴 조회 금지 규약) | `AdminSidebarAdvice.java:13-25` |
| 감사: `@AdminActionLogged(targetIdExpression, targetLabelExpression)` — 결과 객체 getter 리플렉션, 라벨은 성공 로그만·500자 절단 | `AdminActionLogged.java:10-23`, `log/CLAUDE.md` |
| 액션 상수 단일 출처 + 화면 라벨 동기화 테스트 2종 | `AdminActionTypes.java:35-39`, `log/manage.html:163`, `AdminActionTypeSyncTest`, `AdminActionTypeLabelSyncTest` |
| 최신 마이그레이션 V12 | `db/migration/V12__add_admin_action_log_target_label.sql` |
| 업그레이드 경로 테스트 선례: 별도 스키마에 `target("11")` 적용 후 최신까지 적용 | `AdminActionLogTargetLabelMigrationTest.java:23-63` |
| 컨벤션 테스트 선례: 클래스패스 스캔(`AdminPageAnnotationConventionTest.java:27-43`), 소스 스캔(`ClockUsageConventionTest`) | 각 파일 |
| `SecurityConfig`를 임포트하는 슬라이스 테스트 6개(`SecurityConfigTest` 52개 테스트 등), 메서드 보안 슬라이스는 `MethodSecurityTestConfig`를 임포트(Notice·Member·Menu·Log 컨트롤러 테스트) | Grep `SecurityConfig.class`, `NoticeControllerTest.java:44-49` |
| Spring Boot 3.5.16(→ Spring Security 6.5). 메타 어노테이션 템플릿(`AnnotationTemplateExpressionDefaults`)은 공식 문서로 확인. **enum 속성의 템플릿 치환 여부는 미확인** — 확인 방법: PR ① 첫 단계 슬라이스 테스트 1개 | `build.gradle:3`, context7 |

## 설계

### 1. 개념 모델 — "허용값 한 곳"

```
코드 카탈로그(AdminFeature)  ─┐
                              ├─► AdminPermissionEvaluator.allows(auth, feature, action)  ◄── 유일한 판정 함수
DB 허용 행(role_permission) ──┘        ▲            ▲                ▲
   (캐시 스냅샷)                        │            │                │
                          URL 게이트(SecurityConfig)  @RequirePermission   사이드바·화면 버튼
```

- **유효 허용값 = 카탈로그 규칙 ∩ DB 행 ∩ 의존 규칙**. 코드가 최종 권위다 — DB에 위임 불가 기능 행이 수동으로 들어가도 무시된다.
- 판정 규칙(`allows`):
  1. 인증 없음/익명 → false
  2. `ROLE_ADMIN` → **항상 true**(DB 미조회. 캐시 장애와 무관)
  3. 기능이 `ALWAYS`(상시 허용) → `ROLE_ADMIN`·`ROLE_MANAGER`면 true(DB 미조회)
  4. 기능이 `ADMIN_ONLY` → false
  5. 기능이 `DELEGABLE` → `ROLE_MANAGER`이고 스냅샷에 (MANAGER, feature, action)이 있고 **action≠READ면 READ도 있어야** true. 그 외 역할(`ROLE_USER`) false
- 역할은 `Authentication.getAuthorities()`에서 읽는다(U7). `@WithMockUser(roles="MANAGER")`도 그대로 동작한다(principal 타입 비의존).

### 2. 기능 카탈로그 (코드, `com.cms.admin.permission.AdminFeature` enum)

| 기능 | 종류 | 동작 | 사이드바 매칭 URL | URL 게이트 패턴 |
|---|---|---|---|---|
| `DASHBOARD` | ALWAYS | READ | `/admin` | `/admin` |
| `MY_INFO` | ALWAYS | READ·UPDATE | `/admin/member/info` | `/admin/member/info`, `/admin/api/members/me`, `/admin/api/members/me/**` |
| `NOTICE` | **DELEGABLE** | READ·CREATE·UPDATE·DELETE | `/admin/notice/manage` | `/admin/notice/**`, `/admin/api/notices`, `/admin/api/notices/**` |
| `MEMBER` | ADMIN_ONLY | — | `/admin/member/manage`, `/admin/member/new` | (`/admin/**` 캐치올) |
| `MENU` | ADMIN_ONLY | — | `/admin/menu/manage` | (캐치올) |
| `ACTION_LOG` | ADMIN_ONLY (U3) | — | `/admin/log/manage` | (캐치올) |
| `PERMISSION` | ADMIN_ONLY(D1, 영구) | — | `/admin/permission/manage` | (캐치올) |

- 각 상수: `kind`, 지원 동작 집합, 한국어 라벨(화면용), `menuUrls`, `gatePatterns`. 동작 enum `PermissionAction { READ, CREATE, UPDATE, DELETE }`.
- `PERMISSION`은 enum 생성자에서 `DELEGABLE`로 바꾸면 **클래스 로딩 시 예외**(자기 승격 차단을 테스트가 아닌 구조로) + 단위 테스트로 이중 고정.
- 카탈로그 불변식(단위 테스트): ① DELEGABLE의 동작 집합은 READ 포함 ② 두 기능의 `gatePatterns`가 겹치지 않음 ③ `menuUrls` 중복 없음 ④ 모든 `gatePatterns`가 `/admin` 또는 `/admin/` 하위(공개 경로를 위임 대상으로 만들 수 없음) ⑤ 정적 예약 접두사(`STATIC_PUBLIC_PATHS`) 침범 없음.

### 3. 엔드포인트 → (기능, 동작) 분류: **URL 게이트(기능 단위 READ) + 메서드 어노테이션(동작 단위)** 이중

검토한 방식:

| 방식 | 장점 | 단점 |
|---|---|---|
| (A) URL 패턴+HTTP 메서드 선언형(카탈로그에 `(method, pattern)→action`) 단일 `AuthorizationManager` | 한 곳 | `/admin/api/notices/{id}/attachments/{aid}/content` 같은 패턴 순서·겹침 오류가 조용히 권한을 연다. 핸들러를 고쳐도 표가 따라오지 않음 |
| (B) 메서드 어노테이션만 | 핸들러 옆에 명시 | 페이지 차단이 JSON 403이 됨(사실 C), 필터 단계 기본 거부와 분리 |
| **(C) 둘 다(권장)** | 지금 구조(URL+`@PreAuthorize` 이중)를 유지. 페이지는 필터에서 HTML 403, 동작은 핸들러에서 정밀 판정. 두 계층 모두 같은 `allows()` 호출 | 두 곳 선언 → **컨벤션 테스트로 정합 강제**(§3-3) |

#### 3-1. `SecurityConfig` 변경 (규칙 순서 보존)

```
1. dispatcherTypeMatchers(ERROR).permitAll()                 (변경 없음, 맨 앞)
2. 로그인·재설정 permitAll                                     (변경 없음)
3. swagger hasRole ADMIN                                     (변경 없음)
4. ALWAYS 기능 gatePatterns → hasAnyRole(ADMIN, MANAGER)     (기존 64-66행과 동일 경로, 카탈로그에서 생성)
5. DELEGABLE 기능별 gatePatterns → access(featureGate(f))    (기존 68-69행 대체: ADMIN 또는 MANAGER∧READ)
6. /admin/** hasRole ADMIN                                   (ADMIN_ONLY·미분류 전부 — 변경 없음)
7. /notices, actuator, 정적, anyRequest().denyAll()           (변경 없음)
```

- 4·5는 카탈로그를 순회해 등록한다(경로 문자열 중복 정의 제거). `featureGate(f)` = `AuthorizationManager<RequestAuthorizationContext>` 람다 → `evaluator.allows(auth, f, READ)`.
- **기본 거부 유지**: 6·7 규칙과 `anyRequest().denyAll()`은 손대지 않는다. 카탈로그에 없는 `/admin/**`는 6에 걸려 ADMIN 전용, `/admin` 밖 미분류는 denyAll.
- `/admin/**`의 HTTP 메서드는 5에서 구분하지 않는다(게이트는 READ). 동작 판정은 3-2.
- **경로 매칭 방식 고정(리뷰 1라운드 P-5)**: 게이트 패턴은 기존 `SecurityConfig`가 쓰는 것과 같은 매처 종류(`requestMatchers(String...)` → Spring MVC가 있으면 `MvcRequestMatcher`/`PathPatternRequestMatcher`)로 등록하고, 새 매처 종류를 도입하지 않는다 — MVC 핸들러 매칭과 같은 경로 해석(후행 슬래시·`;` 파라미터·`.` 세그먼트 정규화)을 공유해 "게이트가 보는 경로 ≠ 핸들러가 받는 경로" 불일치를 만들지 않는다. 우회가 확인된 것은 아니며(리뷰도 미증명으로 판정), **경계 입력 시험을 CI에 둬서 근거를 만든다**(테스트 계획 2-b): 후행 슬래시·중복 슬래시·`%2F` 인코딩 구분자·`;jsessionid` 파라미터·`/./`·`/../` 점 경로·`HEAD`/`OPTIONS`/선언 안 된 메서드가 MANAGER(권한 회수 상태)에게 거부되고 **업무 핸들러가 실행되지 않음**(서비스 호출 0회)을 단언한다. 컨테이너가 먼저 거르는 입력(인코딩 슬래시·`..` 등)은 실제 서버(`RANDOM_PORT`) 시험으로 확인한다.

#### 3-2. 메서드 계층: `@RequirePermission(feature, action)`

- `@PreAuthorize("@adminPermission.check('{feature}', '{action}')")`를 메타 어노테이션으로 감싼 커스텀 어노테이션 + `AnnotationTemplateExpressionDefaults` 빈(`MethodSecurityConfig`). `check(String, String)`은 이름을 enum으로 파싱(실패 시 **false**, fail-closed)하고 `allows()` 호출.
- enum 속성 치환이 안 되면(사실 C 미확인) 속성을 `String`으로 두고 컨벤션 테스트가 enum 이름 유효성을 검사한다.
- 적용: 공지 9곳의 `hasAnyRole('ADMIN','MANAGER')` → `@RequirePermission`(U4 매핑):

| 핸들러 | 동작 |
|---|---|
| `GET /admin/api/notices`, `GET /{id}`, 첨부 `GET` 목록, `GET .../content` | READ |
| `POST /admin/api/notices` | CREATE |
| `PATCH /{id}`, 첨부 `POST`, 첨부 `DELETE` | UPDATE |
| `DELETE /{id}` | DELETE |

- 내 정보 7곳은 `hasAnyRole('ADMIN','MANAGER')` **유지**(ALWAYS). ADMIN 12곳 `hasRole('ADMIN')` **유지**. 권한관리 신규 API도 `hasRole('ADMIN')`.
- 페이지 컨트롤러(`GET /admin/notice/manage`)에는 메서드 어노테이션을 달지 않는다 — URL 게이트(READ)가 HTML 403을 낸다.

#### 3-3. 컨벤션 테스트 `AdminEndpointAuthorizationConventionTest` (CI 잠금)

`com.cms.admin` 컨트롤러를 클래스패스 스캔해 모든 핸들러 메서드의 (HTTP 메서드, 경로)를 `AnnotatedElementUtils.findMergedAnnotation(..., RequestMapping.class)`로 계산하고 `PathPatternParser`로 카탈로그와 대조:

1. **`/admin/**` 아래 모든 핸들러(경로에 `/api/`가 있든 없든)**는 아래 둘 중 하나여야 한다(리뷰 1라운드 P-4 — URL 게이트는 HTTP 메서드를 구분하지 않아 READ 게이트만 통과하면 같은 경로의 쓰기 핸들러까지 열린다):
   - **읽기 전용 페이지 핸들러**: HTTP 메서드가 **명시적으로 `GET`(또는 `GET`+`HEAD`)로만 제한**된 `@GetMapping`류. 이 경우에만 메서드 어노테이션 면제(URL 게이트가 인가).
   - 그 밖(POST·PUT·PATCH·DELETE, **메서드 제한 없는 `@RequestMapping`**, 비 GET 페이지 폼 처리기 전부): 정확히 하나의 인가 선언 — `@RequirePermission` | `@PreAuthorize("hasRole('ADMIN')")` | (ALWAYS 기능 경로 한정) `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")`. 공개 permitAll 2개(재설정 API)는 명시 허용 목록. **선언 없는 새 쓰기 핸들러 → 실패**
   - API(`/admin/api/**`)는 GET이라도 반드시 선언을 가진다(데이터 노출 경로).
2. `hasAnyRole('ADMIN','MANAGER')`는 ALWAYS 기능 `gatePatterns` 안의 핸들러에만 허용
3. DELEGABLE 기능 `gatePatterns` 안의 **모든 API 핸들러는 같은 기능의 `@RequirePermission`**을 가진다(공지 경로에 `hasRole('ADMIN')`이 섞여도 MANAGER가 URL 게이트를 통과한 뒤 막히므로 기능상 안전하지만, 매트릭스 화면과 실제가 달라지므로 금지)
4. `@RequirePermission(f, a)`의 `a`는 `f`의 지원 동작이어야 하고, `f`는 DELEGABLE이어야 한다. 핸들러 경로가 `f.gatePatterns` 밖이면 실패(URL 게이트가 READ를 강제하지 않는 경로)
5. 페이지 핸들러 경로가 DELEGABLE `gatePatterns`에 걸리면 그 기능의 `menuUrls`에도 있어야 한다(사이드바와 게이트 정합)

추가로 **접근 매트릭스 통합 테스트**(§9-2)가 실제 `RequestMappingHandlerMapping`의 전 핸들러 목록과 기대값 표를 비교해, 새 엔드포인트가 기대값 없이 추가되면 실패한다.

### 4. 데이터 모델 (PR ①, V13 DDL + V14 DML)

```sql
-- V13 (DDL만)
CREATE TABLE permission_role (
  role        VARCHAR(30) NOT NULL,      -- 'ROLE_MANAGER' (Role enum 이름)
  version     BIGINT      NOT NULL,      -- 권한 매트릭스 낙관적 버전(잠금 기준 행)
  update_date DATETIME(6) NULL,
  PRIMARY KEY (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE role_permission (
  role    VARCHAR(30) NOT NULL,
  feature VARCHAR(50) NOT NULL,
  action  VARCHAR(20) NOT NULL,
  PRIMARY KEY (role, feature, action),
  CONSTRAINT fk_role_permission_role FOREIGN KEY (role) REFERENCES permission_role (role)
) ENGINE=InnoDB ...;

-- V14 (DML만, **일회성 초기화** — 한 번 성공하면 Flyway가 다시 실행하지 않는다)
permission_role: ('ROLE_MANAGER', 0, NOW())
role_permission: ROLE_MANAGER × NOTICE × {READ, CREATE, UPDATE, DELETE}   -- U1(a)
```

- **V14는 "초기화"이지 "복구 수단"이 아니다**(리뷰 1라운드 P-7). 시드 INSERT는 `WHERE NOT EXISTS`로 부분 실패 후 재시도에서 중복 삽입을 막을 뿐이며, 이미 성공한 V14를 다시 실행해 **ADMIN이 회수한 권한을 되살리는 용도로 쓰지 않는다**(운영 절차에 "누락 권한을 V14 재실행으로 복구" 금지를 명시). 누락·삭제된 권한의 복구는 권한관리 화면/API(`PUT`)로만 한다. 테스트: "V14 SQL을 권한 회수 뒤 수동 재실행하면 회수한 행이 되살아남"을 **알려진 동작으로 고정**(경고 주석)하되, 정상 Flyway 경로(`flyway_schema_history`에 V14 성공 기록 시 재실행 없음)를 함께 확인한다.
- **부분 성공 복구 절차**: MariaDB DDL 암묵 커밋 때문에 V13의 `CREATE TABLE` 2개 중 첫 번째만 성공하고 실패하면 `flyway_schema_history`에 실패 기록이 남는다. 복구는 ① 실패 원인 제거 ② `flyway repair` 전에 두 테이블 존재 여부를 확인해 존재하는 것을 수동 `DROP`(권한 행이 아직 시드 전이므로 안전) ③ `repair` 후 재기동. 이 절차를 `docs/migration-guide.md`에 한 문단으로 추가(PR ①). V16(DROP COLUMN)은 **실행 전 `menu` 테이블 백업 필수**(되돌리려면 백업에서 컬럼 복원), 실행 후 되돌릴 수 있는 앱 버전은 §롤백 호환표 참조.

- **행 존재 = 허용**(거부 행 없음). ADMIN 행 없음(코드 고정).
- `VARCHAR`(DB enum 아님): 카탈로그에 기능을 추가할 때 `ALTER`가 필요 없게. 엔티티는 `String` 필드로 매핑하고 스냅샷 빌더가 **관대하게 파싱**(모르는 기능·동작·역할 행은 WARN 후 무시) — 코드에서 기능을 지웠는데 행이 남아도 로딩 전체가 실패해 MANAGER가 전부 막히는 일을 막는다. 기능 제거 시 행 삭제 마이그레이션을 함께 쓰는 규칙은 `CLAUDE.md`에 남긴다.
- **확장 경로(D5)**: `permission_role`이 곧 미래 역할 테이블의 자리다(행 추가 + `member.user_type` 확장으로 연결). 지금은 `ROLE_MANAGER` 한 행만, 화면·API도 MANAGER만 받는다. 이 테이블은 과설계가 아니라 **빈 허용 집합에서도 잠글 기준 행 + 버전**이 필요해서 둔다(§6).
- MariaDB DDL은 암묵 커밋이라 V3·V9 규칙대로 DDL(V13)과 시드(V14)를 분리.
- 번호는 머지 시점 최대 버전을 재확인한다(병행 작업 충돌 시 재번호).

### 5. 캐시와 즉시 반영 (D4)

- `RolePermissionCache`(싱글턴 빈): `AtomicReference<Holder(generation, snapshot)>` + `AtomicLong generation`.
  - `snapshot()`: holder가 유효(현 generation과 같고 snapshot≠null)하면 반환. 아니면 **단일 비행 로드**(`synchronized`): 로드 시작 전 `g = generation.get()` → DB 전체 행 조회(작은 표) → 불변 `Set<Grant>` 구성 → `generation.get() == g`일 때만 설치. 로드 도중 무효화가 끼면 설치하지 않고 그 결과로 이번 요청만 판정(다음 요청이 다시 로드).
  - `invalidate()`: `generation.incrementAndGet()` + holder 비움 — 실패할 수 없는 메모리 연산.
  - **경합 근거**: "요청 R이 커밋 전 값을 읽는 중 → 커밋 → 무효화 → R이 낡은 값 설치"를 generation 비교가 막는다(리뷰 단골 지점).
- 무효화 시점: 권한 저장 트랜잭션의 `PermissionChangedEvent`를 `@TransactionalEventListener(AFTER_COMMIT)`로 받아 `invalidate()`. 롤백이면 무효화 없음(값 불변). 커밋과 무효화 사이 수 μs 창의 요청은 이전 값으로 판정(허용 — "다음 요청부터 반영").
- **로드 실패 = fail-closed**: DB 오류면 예외를 삼키고 ERROR 로그, 이번 판정은 "MANAGER의 DELEGABLE 전부 거부". ADMIN과 ALWAYS는 DB를 보지 않으므로 영향 없음(대시보드·내 정보·관리자 업무 유지). 실패는 캐시하지 않아 다음 요청이 재시도.
- 판정 시점은 **요청 진입 시**(필터·메서드 인터셉터). 이미 진행 중인 요청(대용량 업로드 등)은 끝까지 진행된다 — 문서화.
- 세션: 권한 변경은 **역할을 바꾸지 않으므로 세션 만료가 필요 없다**(판정이 매 요청 캐시를 읽음). 역할 변경 시 기존 세션 만료 경로는 그대로.
- **다중 인스턴스 미지원**(사실 C — 세션 레지스트리·레이트리밋과 같은 전제). 확장 시 다른 인스턴스 캐시는 무효화 신호를 받지 못해 영구히 낡는다 → 그때는 DB 버전 폴링 또는 캐시 제거가 필요(위험 표에 명시).
- **로드는 호출자 트랜잭션과 격리된 독립 짧은 읽기 트랜잭션**(`Propagation.REQUIRES_NEW` + `readOnly`, `TransactionTemplate`)에서 수행한다(리뷰 1라운드 P-1). 이유: 사이드바 렌더링(`MenuService.getSidebarMenus`, `MenuService.java:390`·`:392`의 읽기 전용 트랜잭션)처럼 이미 열린 호출자 트랜잭션 안에서 로드하면, MariaDB `REPEATABLE READ`에서 **호출자 트랜잭션의 낡은 스냅샷**으로 권한 행을 읽게 된다 — "사이드바 트랜잭션이 메뉴를 먼저 조회(스냅샷 고정) → ADMIN이 권한을 회수하고 `invalidate()`(generation+1) → 판정기가 새 generation을 읽지만 호출자 스냅샷이 낡은 허용값을 반환 → generation이 안 바뀐 채 **낡은 허용값이 '최신'으로 설치**" 되면 다음 무효화 전까지 모든 요청이 회수된 권한을 허용한다. 독립 트랜잭션은 매번 새 스냅샷을 만들고 호출자의 영속성 컨텍스트도 쓰지 않으므로 이 경로가 사라진다. 로드는 엔티티가 아니라 `(role, feature, action)` 행만 읽는 읽기 전용 쿼리로 한정한다.
- **한 요청 안의 일관성**: 같은 함수가 가변 캐시를 여러 번 읽으면 한 번의 렌더링에서 사이드바와 화면 버튼이 서로 다른 버전을 볼 수 있다. 판정 API는 `PermissionSnapshot snapshot()`(불변)과 `allows(snapshot, auth, feature, action)` 오버로드를 함께 제공하고, **페이지 모델을 만드는 곳(`AdminSidebarAdvice`)은 요청당 스냅샷을 한 번만 받아** 사이드바·`myPermissions`·노출 대상 계산에 모두 쓴다(§7).

### 6. 권한관리 API·서비스 (PR ③)

`api-conventions` 기준(복수 명사·중첩 관계):

| 메서드·경로 | 권한 | 설명 |
|---|---|---|
| `GET /admin/permission/manage` | ADMIN(캐치올) | 페이지, `@AdminPage` |
| `GET /admin/api/roles/{role}/permissions` | `hasRole('ADMIN')` | 카탈로그(기능·라벨·종류·지원 동작) + 현재 허용 + `version` |
| `PUT /admin/api/roles/{role}/permissions` | `hasRole('ADMIN')` | 본문 `{version, grants:[{feature, action}]}` — 그 역할의 **전체 허용 집합 교체** |

- `{role}`은 `ROLE_MANAGER`만 허용. `ROLE_ADMIN` → 400("관리자 권한은 코드로 고정"), `ROLE_USER`·미정의 → 400(타입 변환 실패 고정 문구 규칙).
- 검증(400): 미정의 기능·동작, **위임 불가 기능**(ALWAYS·ADMIN_ONLY — 권한 상승 차단의 서버 측 보장), 기능이 지원하지 않는 동작, **READ 없이 CREATE/UPDATE/DELETE**(의존 규칙 — 자동 보정하지 않고 거부. 화면이 체크박스 연동으로 미리 맞춤), 중복 항목, `version` 누락.
- 동시성: 첫 테이블 조회가 `permission_role` 행 `PESSIMISTIC_WRITE` → `version` ≠ 요청 → **409**("다른 관리자가 먼저 변경했습니다. 새로고침"). 이후 diff(추가·삭제)만 적용, 변경이 있으면 `version+1`·`update_date = now(clock)`. 빈 집합에서도 잠글 행이 있어 lost update가 없다.
- 변경 없음: 버전·무효화·DB 쓰기 없음, 200 + 현재 상태. 감사는 기록(라벨 "변경 없음").
- 감사: `PERMISSION_UPDATE`(신규 상수 + `ALL` + `ACTION_TYPE_LABELS` "권한 변경"), `targetType="ROLE_PERMISSION"`, `targetId` 없음(역할에 숫자 ID 없음), `targetLabelExpression="auditLabel"` → `"ROLE_MANAGER v3→v4: +공지사항.생성, -공지사항.삭제"`(500자 절단은 Aspect가 처리). 서비스는 `@JsonIgnore getAuditLabel()`을 가진 결과 객체를 반환하고 컨트롤러가 응답 DTO로 변환(`MenuDeleteResult` 선례). 감사는 최상위 트랜잭션 진입점 조건을 만족(컨트롤러 → 서비스 직접 호출).
- **자기 잠금·권한 상승 불가 근거**: 권한관리 화면·API는 `PERMISSION`(ADMIN_ONLY, 카탈로그 생성자 가드)이고 ADMIN 판정은 DB를 보지 않는다 → 어떤 저장으로도 ADMIN이 권한관리를 잃지 않는다. 마지막 ADMIN 보호는 기존 가드(`AdminMemberService.java:213-223`)가 담당하며 이 기능과 무관.
- 권한관리 메뉴 시드(V15, DML, V9 패턴): `'권한 관리'`, `/admin/permission/manage`, 아이콘 `fas fa-fw fa-user-lock`, 최상위 맨 끝(`COALESCE(MAX(ord), -1) + 1` — 같은 테이블 INSERT…SELECT 동작은 마이그레이션 테스트로 확인), `WHERE NOT EXISTS (menu_url = ...)`. PR ② 이후라 `access_role`은 지정하지 않는다(NULL).

### 7. 사이드바·화면 연동 (PR ②, 화면 버튼은 PR ③)

- `MenuService.getSidebarMenus(boolean)` → `getSidebarMenus(Predicate<String> urlVisible)` 또는 `(MenuVisibility)` 인자. `AdminSidebarAdvice`가 `AdminPermissionEvaluator`로 판정 함수를 만들어 넘긴다(REST에는 여전히 주입 안 됨 — `@ControllerAdvice(annotations = AdminPage.class)` 유지).
- URL 가시성 `urlVisible(url)`:
  - ADMIN → **항상 true**(URL null 포함 — 오늘 ADMIN이 보는 것과 동일)
  - 그 외: url이 어떤 기능의 `menuUrls`와 **문자열 완전 일치**하면 `allows(auth, f, READ)`, 아니면 false(D3 — 미분류·외부 URL·쿼리스트링 붙은 URL·URL null은 ADMIN 전용, fail-closed)
- 트리 가지치기(기존 3단 조립 뒤, 아래에서 위로): 표시 깊이 안의 자식 중 보이는 것만 남긴다 → **남은 자식이 있으면 그룹으로 노출**(자기 URL 무시 — 사이드바가 그룹의 URL을 쓰지 않으므로, 사실 C), 없으면 리프로서 `urlVisible(자기 URL)`일 때만 노출.
  - 결과: V3의 '회원 관리' 그룹(URL null)은 MANAGER에게 '내 정보'만 담아 보인다(오늘과 동일). ADMIN 전용 부모 아래 공지 메뉴를 둬도 MANAGER에게 부모가 그룹으로 보인다 → **"ALL 메뉴의 조상에 ADMIN 없음" 불변식은 더 필요 없다**(§8).
  - 핵심 원칙 검증: MANAGER에게 보이는 링크는 항상 `allows(READ)`가 true인 기능의 URL이고, 그 URL의 게이트도 같은 `allows(READ)` → **보이는데 403은 구조적으로 불가**. 역(접근 가능하지만 메뉴 없음)은 허용(메뉴 비활성 등).
- 역할 출처 통일: `hasAdminAuthority()`(principal의 `Member.userType`) 대신 `Authentication` 권한을 쓴다(U7).
- 공지 화면 버튼(PR ③): `AdminSidebarAdvice`가 `@ModelAttribute("myPermissions")`(현재 사용자의 `"NOTICE:CREATE"` 같은 문자열 집합, 캐시에서 계산)를 추가 → `notice/manage.html`이 [등록]·[수정]·[삭제]·첨부 업로드/삭제 버튼을 숨긴다. 서버 판정이 최종이며, 화면을 연 사이 권한이 회수되면 다음 API가 JSON 403 → 기존 `extractErrorMessage`로 "권한이 없습니다" 표시. U4 부작용 안내 문구(삭제 409 시 "첨부를 먼저 삭제") 기존 문구 재사용.
- **요청당 권한 스냅샷 하나**(리뷰 1라운드 P-6): `AdminSidebarAdvice`는 요청마다 `PermissionSnapshot`을 한 번만 받아 사이드바(`urlVisible`)·`myPermissions` 계산에 같은 스냅샷을 쓴다(§5). 한 번의 렌더링에서 메뉴와 버튼이 서로 다른 권한 버전을 보는 일이 없다. (`exposure`는 페이지 Advice가 아니라 트리 조회 API가 자기 요청의 스냅샷으로 계산한다 — 아래 항목.)
- 메뉴 관리 화면(PR ②): "노출 범위" select 제거, U8(a)면 읽기 전용 "노출 대상"을 표시한다. **`exposure`는 트리 조회 API(`GET /admin/api/menus/tree`, `MenuTreeResponse` 노드 필드)가 계산해 내려보낸다**(리뷰 2라운드 P-9). 상세 API(`GET /admin/api/menus/{id}`)는 한 행만 읽고 페이지 Advice(`@AdminPage` 한정) 밖이라 가지치기 트리·스냅샷을 가질 수 없고, 페이지 요청과 상세 API 요청 사이의 스냅샷 공유도 보장할 수 없다. 트리 API는 **한 요청에서 전체 메뉴를 읽고 `PermissionSnapshot`을 한 번만 받아** 가지치기와 `exposure` 계산에 같은 입력을 쓴다. 화면은 이미 트리 노드 `data`를 들고 있으므로(`manage.html` 선택 시 상세 조회와 별개) 노드 `data.exposure`를 읽어 표시하고 `MenuResponse`는 바꾸지 않는다.
  - **계산은 사이드바와 같은 순수 함수를 공유한다**(`MenuVisibility`): 입력 = 활성 메뉴 전체·스냅샷·"MANAGER 관점" 고정(ADMIN은 항상 전부 보이므로 안내 대상이 아니다), 출력 = 사이드바 트리와 노드별 `exposure`. 두 곳이 같은 코드를 쓰므로 안내와 실제가 어긋날 수 없다.
  - **기준은 가지치기를 끝낸 최종 트리**다(리뷰 2라운드 P-8 — 사이드바도 가지치기 후 자식 목록이 비었는지로 링크를 정한다, `sidebar.html:47`). 노드별:
    1. **가지치기 후 남은 자식이 있다** → `VISIBLE_BY_CHILDREN`("보이는 하위 메뉴에 따라 표시" — 자기 URL은 쓰이지 않음)
    2. **남은 자식이 없다**(원래 자식이 없었거나 전부 가지치기됨) → 리프로서 **자기 URL 기준**: `ALL_ADMINS`(ALWAYS 기능 URL) / `PERMISSION:<기능>`(DELEGABLE 기능 URL — MANAGER에게는 그 기능 READ 권한이 있을 때 보임) / `ADMIN_ONLY`(카탈로그 밖·위임 불가·URL null)
    3. **비활성 메뉴, 또는 비활성 조상 아래 메뉴, 또는 표시 깊이(3단) 밖** → `NOT_SHOWN`(사이드바 트리에 없음)
  - 예: URL이 `/admin`인 활성 부모 아래 ADMIN 전용 자식만 있으면 자식은 가지치기돼 사라지고, 부모는 **리프로서 자기 URL(`/admin`) 기준 `ALL_ADMINS`**가 된다(MANAGER에게 대시보드 링크로 보임 — 안내도 그렇게 나간다). URL null 그룹은 자식이 모두 가지치기되면 `ADMIN_ONLY`.
  - 단위 테스트(P-8·P-9): ① URL null 그룹 + 내 정보 자식 ② ADMIN 전용 URL 부모 + 공지 자식 ③ 자식이 모두 ADMIN 전용인 URL null 그룹 ④ **`/admin` URL 부모 + ADMIN 전용 자식만(위 반례)** ⑤ 비활성 부모 아래 메뉴. 각 경우에 **트리 API의 `exposure`가 같은 스냅샷으로 만든 실제 `getSidebarMenus(MANAGER)` 결과와 일치**함을 대조한다(통합 테스트: 트리 API 응답 노드와 MANAGER 사이드바 렌더 모델을 함께 비교).

### 8. `access_role` 제거와 메뉴 서비스 정리

PR ②(매핑 제거, 컬럼 존치):
- `Menu.accessRole` 필드·getter·`update(...)` 인자, `MenuAccessRole` enum, DTO 4종 필드, `manage.html` select·페이로드 삭제.
- 권한 불변식 코드 삭제: `createMenu` 77-79행, `updateMenu`의 `touchesRole`·`validateRoleChange`(→ **수정은 항상 단일 행 잠금**), `validateFinalPlacement` 349-355행, `inspectChain`의 `hasAdminOnly`(깊이만 남김), `roleOf`. 잠금 서술 갱신: 전체 행 잠금은 구조 반영·생성 2경로뿐(`MenuRepository.java:35` Javadoc, `menu/CLAUDE.md:13`).
- 엔티티에서 컬럼 매핑이 빠지면 JPA INSERT는 `access_role`을 생략 → `DEFAULT NULL`로 채워짐(컬럼 nullable, `V1:48`). `ddl-auto: validate` 통과.
- **롤백 안전성은 PR 단위가 아니라 "권한을 회수했는지"에 달려 있다**(리뷰 1라운드 P-2 — 아래 "롤백 호환표"). PR ② 단독의 이전 앱 복귀는 새로 만든 메뉴가 `access_role NULL` → 이전 코드가 ALL로 정규화(`Menu.java:60-62`) → MANAGER 사이드바에 보이지만 접근은 이전 URL 규칙이 막는다는 점에서 표시 회귀뿐이다. 그러나 이는 **권한 테이블에 아무 변경이 없을 때(= 시드 그대로)**에 한한다.
- 이관(D3): 데이터 변환 SQL은 필요 없다 — 노출은 이제 URL+권한에서 계산되고 U1 시드가 오늘의 접근 범위를 그대로 옮긴다. **배포 전 점검 SQL**(릴리스 노트/PR 본문에 포함, 마이그레이션 아님):
  - `access_role='ALL'` 이고 `menu_url`이 카탈로그 `menuUrls`(`/admin`, `/admin/member/info`, `/admin/notice/manage`) 밖인 활성 리프 → MANAGER에게서 **사라질** 메뉴(D3)
  - `access_role='ADMIN'` 이고 `menu_url='/admin/notice/manage'` → MANAGER에게 **새로 보일** 메뉴(U5)

PR ④(V16, DDL만): `ALTER TABLE menu DROP COLUMN access_role`. V2·V3·V9는 이 컬럼을 쓰지만 항상 V16보다 먼저 실행되므로 신규 DB도 안전. 머지된 V2/V3/V9는 수정하지 않는다.

### 9. 권한관리 화면 UX (PR ③, `templates/admin/permission/manage.html`)

- 대상 역할 표시(MANAGER 고정, 선택 UI 없음). 행 = 기능, 열 = 조회/생성/수정/삭제 체크박스 매트릭스.
- ALWAYS 기능: 지원 동작 체크·잠금(🔒 "모든 관리자 상시 허용"), ADMIN_ONLY 기능: 비체크·잠금("관리자 전용 — 위임 불가"), 미지원 동작 칸: "—".
- 연동: 생성/수정/삭제를 켜면 조회 자동 체크, 조회를 끄면 나머지 자동 해제(서버는 위반 시 400).
- [저장]: `PUT`(CSRF `X-CSRF-TOKEN` 헤더, `head.html` 메타), 409면 서버 메시지 + 최신 상태 재조회, 400이면 초안 유지. 변경 전 이탈 시 `beforeunload` 경고(메뉴 구조 편집 선례).
- 화면은 playwright로 검증(MANAGER 계정으로 공지 버튼 숨김·사이드바 변화 포함).

## 영향 범위

| 영역 | 파일 | PR |
|---|---|---|
| 카탈로그·판정·캐시 | 신규 `com.cms.admin.permission`: `AdminFeature`, `PermissionAction`, `FeatureKind`, `AdminPermissionEvaluator`(빈 이름 `adminPermission`), `RolePermissionCache`, `RolePermission`·`PermissionRole` 엔티티·리포지토리, `RequirePermission` | ① |
| 보안 설정 | `SecurityConfig`(64-69행 → 카탈로그 순회), `MethodSecurityConfig`(템플릿 빈), `com.cms.config/CLAUDE.md` 표 | ① |
| 컨트롤러 | `NoticeController`(5), `NoticeAttachmentController`(4) `@PreAuthorize` → `@RequirePermission` | ① |
| 마이그레이션 | V13(DDL), V14(시드) / V15(권한 관리 메뉴) / V16(DROP) | ①/③/④ |
| 사이드바·메뉴 | `AdminSidebarAdvice`, `MenuService`(사이드바·불변식 삭제), `Menu`, `MenuAccessRole`(삭제), DTO 4종(+ `MenuTreeResponse.exposure` 필드 신설), 신규 `MenuVisibility`(사이드바·exposure 공용 순수 함수), `menu/manage.html`, `MenuRepository` Javadoc, `menu/CLAUDE.md` | ② |
| 권한관리 | 신규 `PermissionController`·`PermissionPageController`·서비스·DTO·`permission/manage.html`·패키지 `CLAUDE.md`, `AdminActionTypes`, `log/manage.html` 라벨 | ③ |
| 공지 화면 | `notice/manage.html` 버튼 표시 조건 | ③ |
| 문서 | 루트 `CLAUDE.md`(지침 지도·보안 규칙·도메인), `docs/migration-guide.md`, `.claude/commands/deploy-check.md:35,81`, `docs/troubleshooting.md`(비자명 이슈 발생 시) | ②③④ |
| 화면/JS 호출 영향 | `menu/manage.html`(accessRole 송수신 제거), `notice/manage.html`(버튼 숨김·403 처리), 신규 권한관리 화면. 그 외 JS 호출처 없음(Grep) | ②③ |

### 테스트 영향(추정)

| 테스트 | 변경 |
|---|---|
| `SecurityConfigTest`(52) | `MockConfig`에 테스트용 판정기(시드와 같은 고정 스냅샷) 추가 — **기대값 무변경**이 PR ①의 회귀 없음 증거. MANAGER 공지 회수 시 403(페이지 HTML·API JSON) 추가 |
| `ApiSecurityConfigTest`, `RateLimitFilterTest`, `RateLimitResponseTest`, `PasswordResetControllerTest`, `ApiErrorContractIntegrationTest` | 같은 테스트 설정 임포트만 |
| `MethodSecurityTestConfig` 사용 슬라이스(Notice·NoticeAttachment·Member·Menu·Log 컨트롤러 테스트) | `adminPermission` 빈 + 템플릿 빈 추가(공용 `PermissionTestConfig`). 공지 테스트에 동작별 MANAGER 허용/거부 케이스 추가 |
| `MenuServiceTest`(accessRole 59회)·`MenuServiceStructureTest`(9)·`MenuControllerTest:210-245` | 불변식·accessRole 케이스 삭제, 사이드바 가지치기 케이스로 교체(②) |
| `AdminSidebarAdviceTest`, `AdminMainControllerTest:150-180` | `getSidebarMenus(anyBoolean())` 스텁 → 새 시그니처(②) |
| `AdminActionTypeSyncTest`·`LabelSyncTest` | 상수·라벨 추가로 통과(③) |
| `AdminPageAnnotationConventionTest` | 권한관리 페이지 컨트롤러 `@AdminPage` 필요(③) |

## 테스트 계획

1. **카탈로그·판정 단위**: §2 불변식 ①~⑤, `PERMISSION` DELEGABLE 변경 시 예외, `allows` 진리표(익명·USER·MANAGER·ADMIN × ALWAYS·DELEGABLE·ADMIN_ONLY × 행 유무 × READ 의존), `check()` 잘못된 이름 → false, 스냅샷 관대 파싱(모르는 기능 행 무시·위임 불가 기능 행 무시).
2. **접근 매트릭스 통합**(`@SpringBootTest` + Testcontainers + MockMvc, 실제 컨트롤러)(리뷰 1라운드 P-3):
   - **MANAGER 역할 조합을 동작별 단일 권한까지 세분**: {시드(전부), 전부 회수, READ만, **READ+CREATE, READ+UPDATE, READ+DELETE**} + 비로그인 + ADMIN. "전부"나 "READ만"만으로는 동작 오표시(예: 삭제를 UPDATE로 표시)를 못 잡으므로, 각 조합에서 **처리기별로 정확히 그 동작을 가진 경우에만 성공**해야 한다.
   - **처리기별 기대 표**(핸들러 → 동작)를 테스트에 고정한다: 목록·상세·첨부 목록·다운로드=READ, 공지 생성=CREATE, 공지 수정·첨부 업로드·**첨부 삭제=UPDATE(U4 예외, 표에 주석)**, 공지 삭제=DELETE. 표에 없는 핸들러가 매핑에 있으면 실패(§3-3 보완).
   - **성공 판정은 "401/403/302가 아님"이 아니다**: 허용 케이스는 **유효한 본문·유효한 CSRF·존재하는 대상**으로 기대 성공 상태(200/201/204)와 **실제 저장 결과**(행 생성·수정·삭제됨)를 단언한다(400·404·500이 '허용'으로 통과하는 것을 막고, 메서드 보안에 닿기 전 400으로 어노테이션 유효성을 가리는 일을 막는다). 거부 케이스는 **유효한 CSRF와 유효한 본문**을 실어 CSRF·검증 거부가 권한 거부로 오인되지 않게 하고, 응답 403(API JSON `ACCESS_DENIED`·페이지 HTML)과 함께 **대상 데이터가 바뀌지 않았음**을 단언한다.
   - **2-b 경계 입력**(§3-1): 권한 회수 상태 MANAGER로 후행 슬래시·중복 슬래시·`%2F`·`;jsessionid`·`/./`·`/../`·`HEAD`·`OPTIONS`·선언 안 된 메서드 → 거부되고 업무 서비스 호출 0회(`verifyNoInteractions`). 알려지지 않은 `/admin/x`는 MANAGER에게 거부(ADMIN 전용 캐치올), `/admin` 밖 알려지지 않은 경로는 기본 거부(`denyAll`). 컨테이너가 먼저 거르는 입력은 `RANDOM_PORT` 실서버로 확인.
3. **컨벤션**: §3-3 규칙 1~5 — 일부러 어긋난 픽스처 클래스로 각 규칙이 실패하는지 검증(변이 확인). 규칙 1의 **비 API 쓰기 핸들러**(`/admin/notice/manage`에 POST 처리기, 메서드 제한 없는 `@RequestMapping`, 선언 없는 PATCH) 반례가 실패해야 하고, GET 전용 읽기 페이지 핸들러는 통과해야 한다.
4. **캐시 경합**: 로드 도중 무효화 끼우기(리포지토리 지연 스텁) → 낡은 스냅샷 미설치, 로드 실패 → MANAGER DELEGABLE 거부·ADMIN/ALWAYS 허용·다음 요청 재시도, 커밋 후에만 무효화(롤백 시 미무효화). **교차 실행 테스트(P-1, 실제 MariaDB)**: ① 호출자 읽기 트랜잭션이 먼저 메뉴를 조회해 스냅샷을 고정 → ② 다른 스레드가 권한 회수를 커밋하고 `invalidate()` → ③ 호출자가 같은 트랜잭션 안에서 판정기를 호출 → **회수된 값(거부)이 보여야 한다**(독립 트랜잭션 덕분). 변이 확인: 로드를 호출자 트랜잭션에 참여시키면 이 테스트가 실패하는지 확인한다.
5. **즉시 반영 통합**: MANAGER 세션으로 공지 API 200 → ADMIN이 `PUT`으로 READ 회수 → **같은 세션** 다음 요청 403·사이드바에서 공지 사라짐(세션 재로그인 없이).
6. **권한관리 API**: 400 경계(ADMIN 역할·위임 불가 기능·미지원 동작·READ 의존 위반·중복·version 누락), 409(낡은 version), 변경 없음 처리, 감사 SUCCESS 1건·라벨 내용, MANAGER 호출 403. 동시 `PUT` 2건(실제 MariaDB 락 대기) → 하나 성공·하나 409(`MenuConcurrencyIntegrationTest` 패턴).
7. **사이드바 가지치기**: URL null 그룹, ADMIN 부모 아래 공지 메뉴, 4단 데이터, 쿼리스트링 URL, 외부 URL, 빈 그룹(ADMIN은 보임·MANAGER는 숨김).
8. **마이그레이션 업그레이드 경로**(`AdminActionLogTargetLabelMigrationTest` 선례): V12 DB(메뉴·회원 존재) → 최신 적용 후 MANAGER 시드 4행·버전 0·FK, V14는 일회성 초기화(정상 Flyway 경로에서 재실행 없음 확인, 권한 회수 뒤 SQL 수동 재실행은 회수 행을 되살린다는 **알려진 동작 고정** — §4). ④에서 V15 DB(access_role 값 있음) → V16 DROP 후 컨텍스트 기동(`validate`) 통과, V15 `INSERT…SELECT MAX(ord)` 결과 검증.
9. **시각 측정 없이 환경 무관**: `update_date`는 주입 `Clock`(`ClockUsageConventionTest` 통과).
10. **UI**: playwright — 권한관리 매트릭스 잠금 표시·연동 체크·409 재조회, MANAGER 공지 버튼 숨김, 메뉴 관리 노출 대상 표시.

## 단계 (PR 분할 제안)

각 PR은 `./gradlew test` 통과. **되돌리기는 PR마다 독립이 아니다** — 권한을 한 번이라도 회수한 뒤에는 아래 "롤백 호환표"의 제약을 따른다(리뷰 1라운드 P-2). **노출과 차단이 어긋나는 중간 상태가 없도록** 사이드바 연동(②)을 편집 UI(③)보다 먼저 둔다(③이 먼저 나가면 권한을 회수해도 `access_role=ALL` 메뉴가 계속 보여 403 링크가 생긴다).

| PR | 내용 | 동작 변화 | 완료 기준 |
|---|---|---|---|
| ① `security/permission-catalog` | 카탈로그·판정기·캐시·V13/V14·`SecurityConfig` 카탈로그화·공지 9곳 `@RequirePermission`·컨벤션 테스트·접근 매트릭스 테스트 | **없음**(시드 = 오늘 범위). 허용값은 DB로만 바꿀 수 있음 | 기존 `SecurityConfigTest` 52개 기대값 무변경 통과, 매트릭스·컨벤션·캐시 테스트 통과, `com.cms.config/CLAUDE.md` 표 갱신 |
| ② `refactor/menu-exposure-from-permission` | 사이드바를 판정기에서 도출·`access_role` 매핑 제거(컬럼 존치)·불변식 삭제·메뉴 화면 select 제거(+U8) | D3: 카탈로그 밖 `ALL` 메뉴가 MANAGER에게서 사라짐 / U5 해당 시 새로 보임 | 사이드바 가지치기 테스트, 메뉴 서비스 테스트 정리, 점검 SQL을 PR 본문에, playwright로 MANAGER·ADMIN 사이드바 확인 |
| ③ `feat/permission-management` | 권한관리 API·화면·V15 메뉴 시드·`PERMISSION_UPDATE` 감사·공지 버튼 숨김 | ADMIN이 MANAGER 공지 권한을 즉시 조절 가능 | API·동시성·즉시 반영 테스트, 감사 동기화 테스트, playwright 검증, 패키지 `CLAUDE.md` 신설 |
| ④ `chore/drop-menu-access-role` | V16 `DROP COLUMN`, 잔여 문서 정리 | 없음 | 업그레이드 경로 테스트, `docs/migration-guide.md` 갱신 |

### 롤백 호환표 (리뷰 1라운드 P-2)

**구조적 사실**: 판정기가 없는 이전 앱(PR ① 이전)은 MANAGER 공지 권한을 코드에 고정(`SecurityConfig.java:68-69` + `NoticeController`의 `hasAnyRole`)해 둔다. 따라서 ADMIN이 권한을 회수한 뒤 그런 앱으로 되돌리면 **DB에 회수 결과가 남아 있어도 MANAGER의 공지 CRUD가 전부 되살아난다**(재로그인과 무관). 이는 새 취약점이 아니라 2026-07-20 승인된 이전 정책으로의 복귀이지만, 운영자가 "회수했는데 열린다"를 겪지 않도록 절차로 막는다.

| 되돌릴 대상 → 복귀 버전 | 권한 회수 이력 없음(시드 그대로) | 권한 회수 이력 있음 |
|---|---|---|
| ② 이후 → ① | 보안 회귀 없음, **표시 회귀 가능** — ①은 `access_role`을 여전히 매핑하고 컬럼이 존치돼 기동은 되지만, ② 이후 새로 만든 메뉴는 `access_role NULL`이라 ①이 ALL로 정규화해 MANAGER 사이드바에 보일 수 있다(접근은 ①의 판정기가 막음 → 링크가 있는데 403) | 같음(① 앱도 판정기가 있어 회수는 유지) |
| ③ 이후 → ② | 안전(화면·API만 사라짐, 권한 테이블은 ②도 읽음) | 안전(②도 판정기 보유, 회수 유지·편집 UI만 없음) |
| ① 이후 → **① 이전**(판정기 없음) | 안전(오늘 정책과 동일) | **금지** — 회수가 무효화되고 MANAGER 공지 CRUD 전부 개방. 되돌리기 전에 ADMIN이 변경 이력(감사 `PERMISSION_UPDATE`)을 확인하고, 필요하면 되돌린 앱 위에서 해당 권한이 열려도 되는지 승인받거나 **수정 버전(판정기 유지)을 앞으로 내는 것**(roll-forward)을 기본으로 한다 |
| ④(V16 이후) → ③/② | 컬럼 매핑이 이미 없는 버전(②·③)으로만 가능 | 위와 동일 |
| ④ 이후 → ① | **불가** — ①은 `access_role`을 매핑하므로 컬럼이 없으면 `ddl-auto: validate` 기동 실패. 불가피하면 백업에서 컬럼을 복원(`ALTER TABLE ADD COLUMN access_role VARCHAR(20) NULL`) 후 | 위와 동일 |

- 운영 규칙: ③ 배포 후에는 **roll-forward 우선**(수정 버전 배포). 어쩔 수 없이 ① 이전으로 되돌릴 때는 MANAGER 로그인을 일시 차단하거나(회원 상태 변경) 되돌린 직후 공지 권한 개방을 감수한다는 승인을 받는다. 이 절차를 `docs/deployment.md`에 추가(PR ③).
- V16 전 백업: `menu` 테이블 `mysqldump`(또는 `CREATE TABLE ... AS SELECT`)를 PR ④ 배포 절차에 필수 단계로 둔다.

## 위험과 한계

| 위험 | 대응·수용 |
|---|---|
| 판정기 이전 앱으로 되돌리면 회수한 MANAGER 권한이 되살아남 / V16 이후 ① 이전으로는 기동 불가 | 롤백 호환표, roll-forward 우선 + 배포 문서화 |
| 캐시 로드가 호출자 트랜잭션 스냅샷을 읽어 낡은 허용값이 설치됨 | 로드는 `REQUIRES_NEW` 독립 짧은 읽기 트랜잭션 + 실제 MariaDB 교차 실행 테스트(§5, 테스트 계획 4) |
| 동작 오표시(예: 삭제를 UPDATE로 표시)가 테스트에 안 잡힘 | 동작별 단일 권한 조합 시험 + 처리기별 기대 표(테스트 계획 2) |
| 비 API 경로에 쓰기 핸들러가 READ 게이트만 통과 | 컨벤션 규칙 1: GET/HEAD 읽기 전용만 면제(§3-3) |
| 경로 정규화·HTTP 메서드 경계에서 기본 거부가 깨질 위험(미증명) | 경계 입력 시험(테스트 계획 2-b), 새 매처 종류 미도입(§3-1) |
| V14 재실행이 회수한 권한을 되살림(운영자가 복구 수단으로 오용) | V14는 일회성 초기화 — 복구는 `PUT`으로만, 문서화(§4) |
| 다중 인스턴스에서 캐시 무효화가 다른 인스턴스에 전파되지 않음 | 단일 인스턴스 전제(세션 레지스트리와 동일) 명시. 확장 시 재설계 필요 |
| 무효화 직전 수 μs·진행 중 요청은 이전 권한으로 처리 | "다음 요청부터 반영"으로 정의 |
| 캐시 로드 실패 시 MANAGER 공지 업무 중단 | fail-closed 의도. ADMIN·상시 기능은 유지, 다음 요청 재시도 |
| URL 게이트 패턴과 메서드 어노테이션 이중 선언의 불일치 | 컨벤션 테스트 + 접근 매트릭스 테스트로 CI 차단 |
| 메뉴 URL 완전 일치 규칙이라 쿼리스트링·후행 슬래시 URL은 MANAGER에게 안 보임 | fail-closed로 수용, 메뉴 관리 화면 "노출 대상"(U8)이 알려줌 |
| D3로 기존 MANAGER 사이드바 항목이 사라질 수 있음 / U5로 새로 보일 수 있음 | 배포 전 점검 SQL로 사전 확인·공지 |
| U4(a)로 DELETE만 가진 MANAGER는 첨부 있는 공지 삭제 불가 | 화면 안내, 필요 시 UPDATE 함께 부여 |
| enum 속성 템플릿 치환 미확인 | PR ① 첫 단계에서 확인, 안 되면 String 속성 + 컨벤션 검사 |
| `ROLE_ADMIN` 판정이 DB 비의존이라 ADMIN의 권한 축소는 불가 | D1 의도(ADMIN = 전권) |
| 권한관리 감사는 최선 노력(감사 저장 실패 시 변경은 유지) | 기존 감사 격리 정책과 동일 |
| 권한 회수 시 이미 열린 공지 화면 버튼은 남아 있음 | 서버 403 + 오류 문구, 새로고침 시 숨김 |

## 완료 기준

- MANAGER 권한(공지 × 조회/생성/수정/삭제)을 ADMIN이 권한관리 화면에서 바꾸면 **같은 세션의 다음 요청부터** API·페이지 접근과 사이드바·버튼 노출이 함께 바뀐다
- 위임 불가 기능(메뉴·권한관리·관리자·활동 로그)은 어떤 요청으로도 MANAGER에게 열리지 않고(API 400 + 판정기 무시), ADMIN은 어떤 저장으로도 권한을 잃지 않는다
- `/admin/**`에 카탈로그 분류·인가 선언 없이 핸들러를 추가하면 CI가 실패하고, 기본 거부(`anyRequest().denyAll()`)와 `/admin/**` ADMIN 캐치올은 유지된다
- 이관 직후 MANAGER의 접근 범위는 오늘과 동일(U1), `menu.access_role`은 코드에서 사라지고(②) 컬럼은 ④에서 제거된다
- 동시 저장은 하나만 성공하고 나머지는 409, 모든 변경은 `PERMISSION_UPDATE` 감사에 변경 요약과 함께 남는다
- `./gradlew test` 통과, 화면 변경은 playwright 검증, 관련 `CLAUDE.md`·문서 갱신
