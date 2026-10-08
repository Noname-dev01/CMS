# PLAN — 범용 게시판(Board/Post) 구축 + 게시판별 권한 위임

> 로드맵: `project-direction-roadmap.md` "실행 로드맵 — Top 8 (2026-10-07 선정)" ①-1 (①-2 공지 흡수는 별도 계획 `PLAN-notice-to-board.md`)
> 상태: v8 — 적대적 리뷰 7라운드 ship(2026-10-08) → **PR A 구현·검증 완료(커밋·PR 전)**, PR B 미착수. 결과는 문서 끝 "구현·검증 결과 — PR A"
> 유형: feat · 브랜치 `feat/board` · **스키마 변경(V26~V30) · 인가 정책 변경(`/boards/**` 공개, 게시판 기능 게이트) · 신규 의존성 없음**

## Context

공지사항 하나로 증명한 콘텐츠 패턴(위임 권한·첨부·공개 노출 불변식·감사 로그·레이트리밋·HTML 본문)을 **여러 게시판**으로 일반화한다. ADMIN이 게시판(이름·공개 여부·첨부 허용 여부)을 정의하고, **게시판별로** 권한을 위임받은 MANAGER가 게시글을 작성·수정·삭제한다. 공개 게시판의 게시글은 `/boards/{boardId}`에서 목록·검색·상세·첨부 다운로드로 노출된다.

로드맵 확정 결정(2026-10-07): 첨부는 게시글 전용 새 테이블(`NoticeAttachment` 범용화 안 함) / 권한은 **게시판별 위임** / 본문은 ⓪ HTML 편집기(#111) / 공지 흡수는 ①-2(이번 범위 밖, `/notices` 유지).

핵심 난제는 게시판이 아니라 **권한 구조**다. 지금 권한 키는 `(member_id, feature, action)`이고 기능은 코드 카탈로그(enum)라서 "게시판 3번의 조회"처럼 **데이터에 묶인 권한**을 표현할 수 없다. 이를 확장하되 기존 공지 권한(`NOTICE`)의 동작·잠금·버전·캐시·감사 계약은 그대로 지켜야 한다.

## 정찰 결과 (코드 확인 사실)

- **판정기**(`AdminPermissionEvaluator`): `decide()` → ADMIN 항상 true(DB 미조회) / `FeatureKind` 3종(`ALWAYS`·`DELEGABLE`·`ADMIN_ONLY`)을 `switch`로 분기 / MANAGER는 principal(`CustomUserDetails.getId()`)의 회원 ID로 스냅샷 조회, 식별 불가면 fail-closed / 쓰기 동작은 READ 의존. SpEL 진입점 `check(feature, action)`. `menuUrlVisibility`는 `AdminFeature.forMenuUrl` 완전 일치 → READ 판정. `grantedActionKeys`는 `"NOTICE:CREATE"` 키 집합(화면 버튼용, `AdminSidebarAdvice`가 `myPermissions`로 주입).
- **캐시**(`PermissionCache`): generation + 단일 비행 로드, **REQUIRES_NEW 읽기 트랜잭션**으로 `member_permission` 전체 적재, 실패 시 빈 스냅샷(fail-closed). 모르는·위임 불가 행은 WARN 후 무시. `PermissionSnapshot`은 `Set<Grant(memberId, feature, action)>`.
- **스키마**: `member_permission(member_id, feature VARCHAR(50), action VARCHAR(20))` PK 3컬럼, `member` FK, `utf8mb4_general_ci`(PAD 비교 — 변형 행 가드가 `existsById`로 409). `member.permission_version`이 회원별 낙관적 버전.
- **권한관리**(`MemberPermissionService.replace`): 요청 검증(DB 전) → **회원 행 `FOR UPDATE`** → 대상(404·400·409) → 버전 비교 → diff → 변형 행 가드 → 삭제·삽입 → 버전 +1 → `PermissionChangedEvent`(AFTER_COMPLETION 무효화)·알림 E2 → 감사 `PERMISSION_UPDATE`(라벨은 **코드 상수·enum·숫자만** — 사용자 입력 없음). 역할 변경(`AdminMemberService.updateAdminMember`)은 같은 트랜잭션에서 `member_permission`을 전부 지우고 버전 +1.
- **URL 게이트**(`SecurityConfig`): 카탈로그를 순회해 ALWAYS → `hasAnyRole`, DELEGABLE → `featureReadGate`(기능 단위 READ, HTTP 메서드 미구분), 그 뒤 `/admin/**` ADMIN 캐치올 → 공개 경로 → `anyRequest().denyAll()`.
- **메서드 계층**: `@RequirePermission(feature, action)`(메타 `@PreAuthorize`, `AnnotationTemplateExpressionDefaults`로 치환). **SpEL에서 메서드 파라미터(`#boardId`)를 읽는 선례는 없다** — Boot Gradle 플러그인이 `-parameters`를 켜므로 동작해야 하지만 테스트로 고정해야 한다.
- **컨벤션 테스트**(`AdminEndpointAuthorizationConventionTest`): `/admin` 아래 핸들러는 읽기 전용 GET/HEAD 페이지를 빼고 정확히 하나의 선언(`@RequirePermission` | `hasRole('ADMIN')` | ALWAYS 경로의 `hasAnyRole('ADMIN', 'MANAGER')`)을 가져야 한다. 위임 가능 기능 게이트 안의 API는 같은 기능의 `@RequirePermission`, 위임 가능 기능 페이지는 `menuUrls`에 있어야 한다. `AdminFeatureTest`는 게이트 중복·`/admin` 영역 밖 금지 등 카탈로그 불변식을 고정.
- **공지 구조**(미러 대상): `Notice`(`useYn`/`deleted` 분리, `authorId` 스냅샷, `update(..., now)`) / `NoticeService`(PATCH·DELETE **비관적 락**, 첨부 남으면 삭제 409, 저장 시 `HtmlContentSanitizer` + 길이 규칙 + `contentFormat=HTML` 표식 + `ContentImageService.replaceRefs(OWNER_NOTICE, …)`) / `NoticeAttachmentService`(공지 락 재사용, 10MB·5개, 확장자+Content-Type 화이트리스트, 롤백 시 파일 정리·커밋 후 삭제, **저장소 루트**). 검증 상수·로직은 `private`라 재사용하려면 추출이 필요하다.
- **공개 공지**(`publicweb/notice`): 노출+미삭제 불변식을 별도 서비스·파생 쿼리로 격리, `id`·`page`를 문자열로 받아 직접 파싱(전역 `@RestControllerAdvice`가 페이지 타입 변환 예외를 JSON으로 바꾸기 때문), 미노출·삭제·없음·비숫자 모두 같은 404, 첨부는 조회 트랜잭션과 파일 열기를 분리한 4KB 스트리밍 + `no-store`, 제목 검색은 목록·COUNT 같은 조건, 100 코드 유닛 상한, `PublicWebExceptionAdvice`가 HTML 500.
- **본문 이미지**(`contentimage`): owner는 `NOTICE`뿐(`OWNER_NOTICE`). 업로드 `POST /admin/api/notices/content-images`는 NOTICE CREATE∨UPDATE 재판정. 공개 판정은 `existsPublishedNoticeRef`(세타 조인) **또는 NOTICE READ 권한자**(미참조·작성 중 이미지 미리보기 포함). 편집기 `notice-editor.js`의 업로드 URL은 **상수로 고정**돼 있다.
- **스토리지**: `FileStorage`는 루트와 네임스페이스(`profile`만 예약) 두 영역. 네임스페이스에는 `open()`(스트리밍)이 **없다**. `docs/deployment.md` 수동 회수 ⑤는 "보존 = `content_image` + `notice_attachment`의 키, `profile/` 제외"로 디스크를 대조한다 — **새 첨부 테이블을 루트에 두면 보존 목록에 넣지 않는 한 회수 절차가 게시글 첨부를 지운다.**
- **메뉴**: 시드는 V9·V15처럼 멱등 DML(`WHERE NOT EXISTS`). 사이드바는 메뉴 URL이 카탈로그 `menuUrls`와 완전 일치할 때만 MANAGER에게 READ 판정으로 노출.
- **통합 검색**(`AdminSearchService`): 섹션별 판정기 필터, 권한 없는 섹션은 키 생략. 화면 `topbar-search.js`가 섹션을 그린다.
- 마지막 마이그레이션 V25. 감사 액션 라벨은 `AdminActionTypes.ALL`과 `templates/admin/log/manage.html` 라벨을 동기화 테스트 2개가 고정한다.

## 핵심 쟁점과 결정

### 쟁점 0. PR 분할 — **PR A(게시판 정의 + 게시판별 권한) / PR B(게시글·공개·검색)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| 한 PR | 로드맵 그대로, 중간 상태 없음 | 신규 파일 40개+·권한 구조 변경·공개 경로가 한 리뷰에 섞여 리뷰 품질이 떨어진다 |
| **A/B 두 PR** | A는 "권한 구조 확장 + 기존 공지 회귀"에 집중(로드맵 작업 순서 2단계와 일치), B는 콘텐츠·공개 측에 집중 | A만 머지된 상태는 "게시판을 만들고 권한을 줄 수 있지만 게시글이 없음" — 기능적으로 무해하지만 쓸모도 없다 |

**결정**: 두 PR. 이 계획 하나가 둘을 모두 다루고, 각 PR의 범위는 "작업 단계"에 나눠 적는다. **왜**: 권한 구조 확장은 공지 권한 회귀가 핵심 위험이라 단독 리뷰가 필요하고(로드맵도 "회귀 테스트 먼저"), B는 공지 패턴의 반복이라 리뷰 초점이 다르다. A만 배포돼도 공개 경로·게시글 경로가 없어 노출 위험이 없다.

### 쟁점 1. 권한 저장 구조 — **별도 테이블 `member_board_permission(member_id, board_id, action)`**

| 선택지 | 장점 | 단점 |
|---|---|---|
| `member_permission`에 `resource_id` 추가 + PK 변경 | 행 하나의 모양으로 통일 | **기존 PK를 바꾸는 DDL**(V18 테이블, 변형 행 가드·마이그레이션 시험·재배포 정리 SQL이 PK 모양에 기대고 있다), 기능 전체 권한은 `resource_id=0` 같은 센티널 필요, 게시판 FK를 걸 수 없음 |
| feature 문자열에 `BOARD:3` 인코딩 | 스키마 변경 없음 | FK 불가, 게시판 삭제 시 문자열 매칭 정리, 판정기 파싱 규칙이 문자열에 숨는다 — 오타·변형이 곧 권한 결함 |
| **별도 테이블** | 기존 `member_permission`·공지 경로·V17~V22 시험을 **건드리지 않는다**(회귀 위험 최소), `board_id`에 FK, PK `(member_id, board_id, action)` | 캐시·권한관리·역할 변경이 두 테이블을 함께 다뤄야 한다 |

**결정**: 별도 테이블. **왜**: 이번 작업의 1순위 위험이 공지 권한 회귀이고, 기존 테이블을 그대로 두면 그 위험이 "새 코드가 기존 코드를 부르는 지점"으로만 좁아진다. ①-2(공지 흡수)에서 `NOTICE` 행을 공지 게시판 행으로 옮기는 것도 별도 테이블이 단순하다(행 복사).
- `action VARCHAR(20)`(enum 아님 — 기존 테이블과 같은 관대한 파싱), `utf8mb4_general_ci`. 변형 행 가드는 기존과 같이 `existsById` 충돌 → 409.
- FK: `member`(RESTRICT), `board`(RESTRICT). 게시판은 소프트 삭제라(쟁점 6) FK가 삭제를 막지 않는다.

### 쟁점 2. 카탈로그 표현 — **새 `FeatureKind.BOARD_SCOPED` + 기능 `BOARD`(게시글) + 게시판 정의는 ADMIN 캐치올**

판정기·URL 게이트·사이드바·`myPermissions`·권한관리 응답이 모두 카탈로그(`AdminFeature`)를 순회하므로 게시판 권한도 카탈로그에 한 줄이 있어야 사이드바·게이트가 같은 판정을 쓴다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| 카탈로그 밖에서 별도 판정기 | 기존 enum 무변경 | 사이드바·게이트·컨벤션 테스트가 이 기능을 모른다 — "보이는데 403"·게이트 누락 위험 |
| `BOARD`를 `DELEGABLE`로 두고 행만 다르게 | 종류 추가 없음 | `member_permission`의 `(BOARD, READ)` 행과 게시판별 행이 둘 다 의미를 가져 혼동, 권한관리 PUT이 `BOARD`를 기능 전체로 부여할 수 있게 된다 |
| **새 종류 `BOARD_SCOPED`** | `switch`가 컴파일 오류로 모든 분기 지점을 알려 준다, 의미가 분명 | 판정기·캐시·권한관리·컨벤션 테스트에 분기 추가 |

**결정**: `FeatureKind.BOARD_SCOPED`와 `AdminFeature.BOARD("게시판", READ·CREATE·UPDATE·DELETE)`.
- **기능 단위 판정**(`allows(auth, BOARD, action)` — URL 게이트·사이드바·메뉴 노출이 쓴다): ADMIN true / MANAGER는 **어느 한 게시판에서라도** 그 동작이 유효(쓰기는 같은 게시판의 READ 의존)하면 true. 게시판 정보가 없는 계층(필터·사이드바)의 "이 영역에 들어올 자격"이다.
- **게시판 단위 판정**(새 `allowsBoard(auth, boardId, action)`·SpEL `checkBoard(boardId, action)`): ADMIN true / MANAGER는 `(회원, 게시판, 동작)` 행 + 쓰기면 같은 게시판 READ. **게시글·첨부·본문 이미지 업로드 핸들러는 전부 이것으로 판정한다.**
- `gatePatterns`: `/admin/board/posts`(게시글 관리 페이지), `/admin/api/boards/*/posts`, `/admin/api/boards/*/posts/**`, `/admin/api/boards/*/content-images`. `menuUrls`: `/admin/board/posts`.
- **게시판 정의**(`/admin/board/manage`, `/admin/api/boards`, `/admin/api/boards/{id}`)는 카탈로그에 넣지 않고 `/admin/**` ADMIN 캐치올 + 핸들러 `hasRole('ADMIN')`. 사이드바는 기존 규칙상 카탈로그 밖 URL을 ADMIN에게만 보인다.
- `PermissionCache.toSnapshot`의 "위임 불가 행 무시"는 `member_permission`에 `BOARD` 행이 수동으로 들어가도 무시하도록 `kind != DELEGABLE` 조건을 유지한다(BOARD_SCOPED는 `member_permission` 행으로 부여할 수 없다).
- 생성자 가드 추가: `BOARD_SCOPED`는 READ를 지원해야 한다(DELEGABLE과 같은 규칙).
- **왜 게시판 정의를 별도 기능(ADMIN_ONLY)로 넣지 않나**: 카탈로그의 ADMIN_ONLY 기능은 `menuUrls`만 있고 게이트가 없다(캐치올이 막음). 넣으면 권한관리 매트릭스에 "게시판 관리 — 위임 불가" 행이 하나 생길 뿐 판정은 같다. **권한관리 화면에 나오는 편이 운영자에게 명확하므로 `BOARD_ADMIN(ADMIN_ONLY, "게시판 관리", menuUrls=/admin/board/manage)`로 넣는다**(MEMBER·MENU와 같은 방식, 판정 비용 없음).

### 쟁점 3. 게시판별 판정을 거는 방식 — **`@RequireBoardPermission(action)` 메타 어노테이션(경로 변수 `boardId` 필수) + 컨벤션 테스트 확장**

| 선택지 | 장점 | 단점 |
|---|---|---|
| 서비스에서 판정(`ContentImageService.uploadForNotice` 방식) | 어노테이션 불필요 | 핸들러마다 호출을 잊으면 열린다 — 컨벤션 테스트가 잡지 못한다 |
| `@PreAuthorize("@adminPermission.checkBoard(#boardId, 'READ')")` 직접 | 표준 | 컨벤션 테스트가 문자열을 파싱해야 하고 오타(`#boardID`)가 null → 거부로 조용히 바뀐다 |
| **메타 어노테이션 `@RequireBoardPermission(action)`** | `@RequirePermission`과 같은 모양, 컨벤션 테스트가 타입으로 인식, 표현식은 한 곳 | 경로 변수 이름이 `boardId`여야 한다는 규칙이 생긴다 |

**결정**: `@RequireBoardPermission(action = ...)` — 메타 `@PreAuthorize("@adminPermission.checkBoard(#boardId, '{action}')")`.
- `checkBoard`는 `boardId == null`·동작 파싱 실패면 **false(fail-closed)**. 게시판 존재 여부는 보지 않는다(MANAGER는 행이 없으면 403, ADMIN은 이후 서비스가 404) — 존재 여부를 판정기가 DB로 확인하면 캐시 밖 쿼리가 생긴다.
- **컨벤션 테스트 확장**: ① `BOARD` 게이트 안의 API 핸들러는 `@RequireBoardPermission`을 가져야 하고 **경로에 `{boardId}`가 있어야 한다** ② `@RequireBoardPermission`은 `BOARD` 게이트 밖에 쓸 수 없다 ③ 선언 개수 규칙에 새 선언을 포함한다(정확히 하나) ④ 반례 테스트(경로 변수 없음·게이트 밖)로 규칙이 위반을 잡는지 고정.
- **SpEL 파라미터 이름 의존**을 슬라이스 테스트로 고정한다(게시판 A 권한 MANAGER가 B 경로에 403, A에 200 — 파라미터 이름을 못 읽으면 null → 전부 403이라 A 허용 단언이 실패한다).

### 쟁점 4. 스냅샷·캐시 — **같은 REQUIRES_NEW 트랜잭션에서 두 테이블을 적재, 스냅샷 하나**

- `PermissionSnapshot`에 `Set<BoardGrant(memberId, boardId, action)>`을 추가하고 `hasBoard(memberId, boardId, action)`·`anyBoard(memberId, action)`(쓰기는 같은 게시판 READ 의존을 만족하는 게시판이 하나라도 있는지)·`boardIds(memberId, action)`을 제공한다.
- 로드는 **한 `loadTransaction` 안에서** `member_permission`과 `member_board_permission`을 차례로 읽는다 — 두 읽기가 같은 REPEATABLE READ 스냅샷을 봐 버전이 섞이지 않는다. 한쪽이라도 실패하면 전체 fail-closed(빈 스냅샷).
- **삭제된 게시판의 행**: 게시판 삭제가 행을 지우므로(쟁점 6) 정상 경로에는 없다. 그래도 수동 SQL·경합으로 남으면 판정은 행 기준이라 허용될 수 있지만 서비스가 삭제된 게시판을 404로 막는다(게시판 단위 판정은 "권한", 존재·삭제는 "대상" — 공지와 같은 분리).
- 무효화는 기존 `PermissionChangedEvent`(AFTER_COMPLETION) 재사용.

### 쟁점 5. 권한관리 API·화면 — **기존 PUT 하나에 `boardGrants`를 추가(같은 버전·같은 잠금·같은 감사)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| 별도 API `/members/{id}/board-permissions` | 기존 PUT 무변경 | 버전이 둘이거나 같은 버전을 두 API가 올려 **한 화면의 두 저장이 서로 409**를 낸다, 감사·알림이 둘로 나뉜다, 역할 변경 삭제도 두 경로 |
| **기존 PUT 확장** | 회원 행 잠금·버전·변경 없음 판정·이벤트·알림·감사가 한 트랜잭션 | 요청 DTO·응답·화면이 커진다 |

**결정**: 기존 `GET`/`PUT /admin/api/members/{id}/permissions` 확장.
- 요청: `boardGrants: [{boardId, action}]` **필수(`@NotNull`, 빈 배열 허용)**. 누락이면 400 — 배포 전에 열어 둔 화면(필드 없음)의 PUT이 게시판 권한을 전부 회수하는 사고를 막는다(⓪의 `contentFormat` 표식과 같은 이유). `grants`에 `BOARD`가 오면 400("게시판 권한은 게시판별로 부여").
- 검증 순서(기존 유지 + 추가): 요청 내용(동작 지원·중복·**게시판별 READ 의존**, DB 전) → 회원 행 잠금 → 대상 → 버전 → **요청 게시판들을 `FOR SHARE`로 잠그고 존재·미삭제 확인(없거나 삭제면 400 "존재하지 않는 게시판")** → diff(두 테이블) → 변형 행 가드(두 테이블) → 쓰기 → 버전 +1(한 번) → 이벤트·알림·감사.
  - `FOR SHARE`인 이유: 확인 직후 게시판 삭제가 커밋되면 삭제된 게시판에 권한 행이 생긴다. 게시판 삭제는 `FOR UPDATE`로 잠그므로 둘이 직렬화된다. 잠금 순서 PUT(회원 → 게시판) / 삭제(게시판 → 권한 행)라 순환이 없다.
  - **게시판 권한 회수는 키 단위 JPQL 벌크 삭제**(v4 — 리뷰 R3-3): 요청에서 빠진(회수) 게시판은 잠그지 않으므로, PUT이 기존 행을 읽은 뒤 그 게시판 삭제가 행을 먼저 지우고 커밋할 수 있다. 엔티티 `deleteAll`이면 이미 지워진 행을 다시 지우다 stale-state 예외(낙관적 잠금 실패로 변환 — 전역 핸들러에 매핑 없음 → 500)가 난다. `@Modifying` JPQL `delete … where memberId=:m and boardId=:b and action=:a`는 0건이어도 성공하므로 멱등이다. 감사·알림의 "회수"는 그 회원 관점에서 사실(권한을 잃음)이라 그대로 기록한다. 회원 잠금과 게시판 잠금을 교차하는 대안은 잠금 순서가 PUT(회원 → 게시판)·삭제(게시판 → 회원)로 뒤집혀 교착을 만들어 기각했다. 시험: "B 회수 PUT의 diff 조회 → 게시판 B 삭제 커밋 → PUT 반영" 순서를 래치로 고정한다.
    - **MariaDB 설정 의존(v5 — 리뷰 R4-2)**: `innodb_snapshot_isolation=OFF`면 벌크 삭제가 0건으로 끝나 PUT은 200이다. `ON`이면 스냅샷 이후 지워진 레코드의 잠금이 오류 1020이 되고, 기존 `GlobalApiExceptionHandler` 매핑대로 **409(`RESOURCE_CONFLICT`)** — PUT 전체(공지 권한 변경 포함)가 롤백된다. 이 드문 경합(같은 회원의 회수 PUT과 그 게시판 삭제가 겹침)에 트랜잭션 전체 재시도를 설계하지 않고 **409를 허용**한다(기존 동시성 계약 — 쪽지 발송 등과 같음). 시험은 기존 `AdminMessageSnapshotIsolationOnIntegrationTest`처럼 ON·OFF 두 설정에서 돌려 OFF=200·ON=409를 확인하고, 409이면 행·버전 무변경, 두 경우 모두 재조회 후 재저장이 성공함을 단언한다.
- 응답: 기존 `features`에 더해 `boards: [{boardId, name, publicYn, attachmentYn, supportedActions, grantedActions}]` — **삭제되지 않은 게시판 전부**(권한 없는 게시판도 행으로 보여야 부여할 수 있다), id 순. `features`의 `BOARD` 행은 `kind=BOARD_SCOPED`, `grantedActions`는 기능 단위 판정 의미(어느 게시판에서든 유효한 동작) — 화면은 이 행을 그리지 않고 `boards` 표로 대신한다.
- 감사 라벨: 항목은 `게시판#3.조회` 형식 — **게시판 이름(사용자 입력)은 넣지 않는다**(기존 "코드 상수·숫자만" 규칙). 알림 문장도 `게시판 #3 조회`(같은 이유).
  - **잘림 대비(v2 — 리뷰 R1-7)**: 감사 라벨은 500자(`AdminActionLogAspect`), 권한 알림은 255자(`NotificationMessages`)로 잘린다. 게시판이 많으면 일괄 교체가 상한을 넘으므로 라벨을 `v3→v4: 추가 12·회수 5 | -게시판#3.조회, … | +공지사항.생성, …` 순서(**건수 → 회수 → 추가**)로 만들고, 잘리면 끝에 `…`이 남는다 — 건수와 회수 항목(접근권을 잃은 쪽)이 먼저 보존된다. 알림도 같은 순서. 전체 diff는 애플리케이션 INFO 로그(`memberId`·버전·추가·회수 키 목록 — 코드 상수·숫자만)에 남긴다. **기록 시점은 트랜잭션 완료 후**(v3 — 리뷰 R2-2): 서비스가 커밋 전에 로그를 쓰면 커밋 실패 시 일어나지 않은 회수가 남는다. **결과 상태를 얻는 방식(v4 — 리뷰 R3-2)**: `@TransactionalEventListener`는 이벤트만 받고 완료 상태를 받지 못하므로, 서비스가 트랜잭션 안에서 **실행자 회원 ID(숫자, principal의 `CustomUserDetails.getId()` — v6, 리뷰 R5-2: 아이디 문자열은 개행·제어문자를 허용해 로그 한 줄이 갈라질 수 있다)**·대상 회원·버전 전이·추가/회수 키를 **불변 값으로 캡처**한 뒤 `TransactionSynchronizationManager.registerSynchronization`으로 직접 등록한 `TransactionSynchronization.afterCompletion(int status)`에서 **트랜잭션 결과(`STATUS_COMMITTED`→`COMMITTED`·`STATUS_ROLLED_BACK`→`ROLLED_BACK`·`STATUS_UNKNOWN`→`UNKNOWN`)**와 함께 기록한다. 캐시 무효화는 기존 `PermissionChangedListener`가 그대로 맡아 로그 처리 실패의 영향을 받지 않는다(로그 콜백은 예외를 삼키고 WARN만 남긴다). `UNKNOWN`은 "적용 여부 DB 확인 필요"로 표시하고 확정 변경으로 쓰지 않는다. 기존 커밋 실패 주입 시험(`MemberPermissionApiIntegrationTest`)에 로그 결과 단언을 추가한다(요청 식별자 체계는 프로젝트에 없어 실행자로 대신한다). 기존 공지만 바뀌는 경우의 라벨도 이 형식으로 바뀐다(기존 감사 라벨 단언 테스트는 새 형식으로 갱신).
  - 기각: "구조화된 감사 상세(별도 컬럼·테이블)" — 감사 로그 스키마 변경이라 이번 범위를 넘는다. `targetLabel` 500자가 기존 계약이고, 잘려도 건수·회수 우선 순서와 INFO 로그로 추적할 수 있다.
- 화면(`permission/manage.html`): 매트릭스 아래 "게시판별 권한" 표(게시판명은 `textContent`), 같은 READ 연동·`dirty`·[되돌리기]·저장 1회. 게시판이 0개면 "게시판이 없습니다" 안내.
- **역할 변경**(`AdminMemberService.updateAdminMember`): 역할이 바뀌면 `member_board_permission`도 같은 트랜잭션에서 전부 삭제(기존 `member_permission` 삭제와 같은 지점). 버전 +1은 기존대로 한 번. 이벤트는 둘 중 하나라도 지워졌으면.
- 테스트 정리: `TestMembers.delete`가 `member_board_permission`도 먼저 지운다(FK).

### 쟁점 6. 게시판 생명주기 — **소프트 삭제, 살아 있는 게시글이 있으면 409, 삭제 시 권한 행 정리**

- 필드: `name`(100자, 공백 거부, 중복 허용 — 화면은 `#id`를 함께 보인다), `publicYn`(공개 게시판 여부), `attachmentYn`(첨부 허용), `deleted`, `create_date`·`update_date`. 정렬은 id 오름차순(순서 필드는 범위 밖).
- 수정: 이름·공개 여부·첨부 허용(부분 수정, 비관적 락). **첨부 허용을 끄면 새 업로드만 막고 기존 첨부는 그대로 노출·관리된다**(삭제는 가능) — 끄는 순간 기존 파일을 숨기면 운영자가 의도치 않게 공개 자료를 잃는다.
- 삭제(`DELETE /admin/api/boards/{id}`, ADMIN): 게시판 행 `FOR UPDATE` → **살아 있는(`deleted=false`) 게시글이 있으면 409** → 소프트 삭제 + 그 게시판의 `member_board_permission` 행 전부 삭제(있으면 `PermissionChangedEvent`). 버전은 올리지 않는다 — 낡은 권한 화면의 PUT은 쟁점 5의 게시판 존재 확인에서 400으로 걸린다.
  - **왜 하드 삭제가 아닌가**: 소프트 삭제된 게시글·첨부·본문 이미지 참조가 `board_id`를 가리킨다(FK). 하드 삭제를 허용하려면 그것들을 함께 지워야 하는데 ①-2 전까지 데이터 정리 정책이 없다(공지와 같은 수준 유지).
  - **삭제 API는 PR B에서 처음 추가한다**(v2 — 리뷰 R1-5). v1은 PR A에 "권한 행 정리만 하는 삭제"를 두고 게시글 검사를 PR B에서 붙이려 했는데, PR B 운영 중 PR A로 앱만 롤백하면 게시글이 있는 게시판을 지울 수 있다(복원 API 없음). 삭제와 게시글 검사가 같은 PR에서 함께 생기면 "삭제는 있는데 검사가 없는 버전"이 존재하지 않는다. PR A의 게시판 정의는 생성·조회·수정만.
- 감사: `BOARD_CREATE`·`BOARD_UPDATE`·`BOARD_DELETE`(`targetType=BOARD`, `targetId`=게시판 ID, `targetLabel` 없음 — 이름은 사용자 입력이라 기존 공지처럼 라벨을 두지 않는다).

### 쟁점 7. 게시글·첨부 — **공지 구조 미러 + 공용 검증 추출**

- `post(id, board_id FK, title 200, content MEDIUMTEXT, use_yn, deleted, author_id, create_date, update_date)`, 인덱스 `(board_id, deleted, use_yn, create_date)` — 공개 목록·관리 목록이 게시판 단위 조회다(공지는 단일 테이블 전체 스캔이었지만 게시판은 여러 개가 한 테이블을 나눠 쓴다).
- `post_attachment`는 `notice_attachment`와 같은 모양(`post_id` FK RESTRICT, `storage_key` UNIQUE, `(post_id, id)` 인덱스).
- 잠금: 수정·삭제·첨부 업로드·첨부 삭제는 게시글 행 `FOR UPDATE`(공지와 같음). **생성은 게시판 행 `FOR SHARE`**(게시판 삭제의 `FOR UPDATE`와 직렬화 — 삭제 검사 직후 생성되는 고아 게시글 방지). 수정·삭제·첨부도 게시판 미삭제를 확인한다(게시판이 삭제됐으면 404).
- 삭제: 첨부가 남아 있으면 409(공지와 같은 오펀 방지 규칙).
- 첨부: 파일당 10MB·게시글당 5개·같은 확장자/Content-Type 화이트리스트, **게시판 `attachmentYn=false`면 업로드 400**("첨부를 허용하지 않는 게시판"). 저장 위치는 **스토리지 루트**(공지 첨부와 같은 영역).
  - **왜 네임스페이스가 아닌가**: 네임스페이스에는 스트리밍 `open()`이 없어 공개 다운로드를 위해 `FileStorage`·`LocalDiskFileStorage`를 넓혀야 하고, ①-2에서 공지 첨부(루트)를 게시글 첨부로 옮길 때 파일 이동이 생긴다. 대신 **`docs/deployment.md` 수동 회수 절차를 두 군데 고친다**(v2 — 리뷰 R1-4):
    - ② 회수 대상 SQL: 지금은 "살아 있는 공지(`deleted=0`)가 참조하면 제외"뿐이라, 살아 있는 게시글만 참조하는 이미지가 회수 대상이 돼 ③④에서 행·파일이 지워진다. **살아 있는 게시글(`post.deleted=0` — 노출 여부·게시판 공개 여부와 무관) 참조도 제외 조건에 추가**한다. 게시판 소프트 삭제는 살아 있는 게시글이 없을 때만 가능하므로 "살아 있는 게시글"이면 게시판도 살아 있다.
    - ⑤ 행 없는 파일 정리의 보존 목록에 `post_attachment`의 `storage_key`를 추가한다.
    - **PR A의 중단 조건(v5 — 리뷰 R4-1)**: PR A가 롤백 하한이므로 PR A 시점의 회수 절차도 PR B 데이터를 지우면 안 된다. **PR A에서** 회수 절차 맨 앞(① 앱 정지 직후)에 가드를 넣는다 — `information_schema.tables`에 `post` 또는 `post_attachment`가 있으면 "게시글 데이터가 있는 DB — 이 절차는 게시글 참조·첨부를 보존하지 못한다. 최신 문서의 절차를 쓴다"를 출력하고 이후 단계를 실행하지 않는다(`&&` 체인, 기존 `ready` 가드와 같은 방식). PR B는 이 가드를 위의 게시글 보존 조건으로 대체한다. 실기 검증: PR B 데이터가 있는 dev DB에서 PR A 절차를 따르면 가드에서 멈추는지 확인한다.
    - 검증: 실기 단계에서 dev에 공지 전용·게시글 전용(비공개 게시판·미노출 글 포함)·공지+게시글 혼합·삭제 콘텐츠 전용·미참조 이미지와 게시글 첨부를 만들고 ②의 대상이 "삭제 콘텐츠 전용 + 미참조"만이고 ⑤의 고아 목록에 게시글 첨부가 없음을 확인한다(삭제 단계는 실행하지 않는다).
- **롤백 파일 정리는 확정 롤백일 때만(v6 — 리뷰 R5-1)**: 지금 `FileStorageTransactionSupport.deleteOnRollback`과 `NoticeAttachmentService.registerCleanupOnRollback`은 `status != STATUS_COMMITTED`이면 파일을 지운다. 커밋 후 응답이 유실된 `STATUS_UNKNOWN`에서 DB는 커밋됐을 수 있으므로, 지우면 **행은 있는데 파일이 없는**(다운로드 영구 404·카운터 불일치) 복구 불가 상태가 된다. 반대로 남기면 최악이 "행 없는 파일"이고 수동 회수 ⑤가 정리한다. 그래서 공용 헬퍼를 **확정 롤백일 때만 삭제, 그 밖(커밋 시도 이후 실패)은 보존 + storageKey·네임스페이스 WARN 로그**로 바꾸고(**구현 중 정정(2026-10-08, PR A 실측)**: Spring은 커밋 단계 예외 — DB는 커밋됐지만 응답이 유실된 경우 포함 — 에서 `afterCompletion` 상태를 `STATUS_UNKNOWN`이 아니라 **`STATUS_ROLLED_BACK`**으로 넘긴다(`MemberPermissionApiIntegrationTest`의 커밋 응답 유실 주입으로 확인). 상태 코드만으로는 구분할 수 없으므로, 동기화에 `beforeCommit` 표시를 두어 **커밋 시도 전에 끝난 롤백만 확정 롤백**으로 보고 삭제한다 — PR A의 권한 diff 로그가 같은 방식이다), 공지 첨부의 복제 구현도 이 헬퍼를 쓰도록 바꾼다(같은 결함 — 공지 본문 이미지·프로필 이미지·공지 첨부·게시판 이미지·게시글 첨부 모두 적용). 시험: 헬퍼 단위 시험(COMMITTED·ROLLED_BACK·UNKNOWN 세 상태), 게시판 이미지·게시글 첨부 업로드에 커밋 후 실패를 주입해 커밋된 행의 파일이 남는지(통합).
  - **프로필 네임스페이스 회수(v7 — 리뷰 R6-1)**: 프로필 업로드도 이 헬퍼(`profile` 네임스페이스 오버로드)를 쓰므로 `UNKNOWN`에서 남은 프로필 파일(롤백된 새 파일, 또는 커밋 후 `afterCommit` 삭제가 실행되지 않은 옛 파일)이 생길 수 있는데, 회수 ⑤는 `profile/`을 제외한다. `docs/deployment.md`에 **⑤-2 프로필 파일 대조**를 추가한다 — 보존 = `member`의 `profile_image_kind='UPLOADED'` 행의 `profile_image_url`(네임스페이스 로컬 키), 디스크 = `profile/` 아래 파일(접두어 제거), 차이만 고아 목록, 앞 단계와 같은 `ready` 가드 + `&&` 체인(조회 실패 시 목록 미생성). 실기 검증: dev에서 회원이 참조하는 프로필 파일은 보존되고 미참조 프로필 파일만 목록에 나오는지(삭제는 실행하지 않음).
- 본문: 공지와 같은 규칙(`contentFormat=HTML` 필수, 보이는 글자 10,001, 정리 HTML 200,000바이트, 이미지만 있는 본문 허용, 저장·출력 이중 sanitize).
- **공용 추출**(동작 불변): 첨부 검증(`sanitizeFilename`·확장자/Content-Type 화이트리스트·크기)은 `common/attachment/AttachmentFilePolicy`로, 본문 검증(`sanitizeContent` + 상수)은 `common/html/ContentBodyPolicy`로 옮기고 공지 서비스가 위임한다. **왜**: 보안 화이트리스트가 두 벌이 되면 한쪽만 고쳐지는 표류가 생긴다. 기존 공지 테스트가 그대로 통과해야 한다(동작 불변 확인).
- 감사: `POST_CREATE`·`POST_UPDATE`·`POST_DELETE`(`targetType=POST`), `POST_ATTACHMENT_UPLOAD`·`POST_ATTACHMENT_DELETE`(`targetType=POST_ATTACHMENT`).
- 핸들러 동작 분류(공지 U4와 같음): 목록·상세·첨부 목록·다운로드 = READ, 생성 = CREATE, 수정·첨부 업로드·첨부 삭제 = UPDATE, 삭제 = DELETE.

### 쟁점 8. 관리자 API 경로

| 경로 | 선언 | 비고 |
|---|---|---|
| `GET·POST /admin/api/boards`, `GET·PATCH·DELETE /admin/api/boards/{boardId}` | `hasRole('ADMIN')` | 게시판 정의(캐치올 ADMIN 영역). DELETE는 PR B(쟁점 6) |
| `GET /admin/api/members/me/boards` | `hasAnyRole('ADMIN','MANAGER')` | **내가 조회할 수 있는 게시판 + 게시판별 허용 동작**(게시글 관리 화면의 게시판 선택·버튼 표시용). 기존 ALWAYS `MY_INFO` 게이트(`/admin/api/members/me/**`) 안이라 `SecurityConfig` 변경 없음. ADMIN은 미삭제 게시판 전부·전 동작 |
| `GET·POST /admin/api/boards/{boardId}/posts`, `GET·PATCH·DELETE …/posts/{postId}` | `@RequireBoardPermission` | 목록은 keyword(제목)·useYn·페이지 100 clamp·`id` 보조 정렬(QueryDSL) |
| `GET·POST …/posts/{postId}/attachments`, `GET …/{attachmentId}/content`, `DELETE …/{attachmentId}` | `@RequireBoardPermission` | IDOR: 첨부는 `(attachmentId, postId)`, 게시글은 `(postId, boardId)`로 조회 |
| `POST /admin/api/boards/{boardId}/content-images` | `@RequireBoardPermission(READ)` + 서비스가 그 게시판 CREATE∨UPDATE 재판정 | 공지 업로드와 같은 이유(쟁점 9) |

페이지: `GET /admin/board/manage`(ADMIN, `@AdminPage`), `GET /admin/board/posts`(BOARD 게이트, `@AdminPage`, `?boardId=&id=` 진입 지원).

### 쟁점 9. 본문 이미지 — **업로드 출처(scope)를 이미지에 기록하고, 참조·미리보기를 출처로 판정** (v2 — 리뷰 R1-1·2·3)

v1은 "공개 판정에 조건을 추가만" 했지만 리뷰가 세 가지 경로를 재현했다: ① 참조 저장이 이미지 존재만 확인해 **다른 게시판의 비공개 이미지를 자기 공개 글에 넣어 익명 공개**할 수 있다(공지 ↔ 게시판 양방향) ② 기존 "NOTICE READ면 모든 이미지 200"이 **새 게시판 비공개 이미지까지** 열린다 ③ 업로더 본인 조건은 **권한 회수 뒤에도** 접근을 남긴다. 모두 "이미지가 어느 콘텐츠 영역에 속하는지"를 모르는 데서 나온다.

| 선택지 | 장점 | 단점 |
|---|---|---|
| 참조 기준 판정만(출처 없음) | 스키마 변경 없음 | 미참조(작성 중) 이미지의 소유 영역을 알 수 없다 — ①·③이 남는다 |
| 업로더 기준 | 단순 | 권한 회수와 무관하게 접근 유지(③), 같은 게시판 동료 편집자는 못 봄 |
| **업로드 출처(scope) 컬럼** | 참조 자격·미리보기를 "그 영역의 현재 권한"으로 판정, 권한 회수 즉시 반영, 기존 공지 동작 불변(기존 행은 전부 NOTICE) | `content_image`에 컬럼 추가(DDL 1문) |

**결정**: 업로드 출처 컬럼.
- 스키마(**PR A** — v4, 아래 "스키마" 절 참조): `content_image`에 `scope_type VARCHAR(30) NOT NULL DEFAULT 'NOTICE'`, `scope_id BIGINT NULL` 추가(기존 행 = 공지 편집기 업로드 = `NOTICE`/NULL). 게시판 업로드는 `BOARD`/게시판 ID. FK는 걸지 않는다(소프트 삭제 게시판 ID를 그대로 보존 — 기록 용도).
- 업로드: 공지 `POST /admin/api/notices/content-images` → `NOTICE` 출처(기존 그대로), 게시판 `POST /admin/api/boards/{boardId}/content-images` → `BOARD:{boardId}` 출처(서비스가 그 게시판 CREATE∨UPDATE 재판정, 게시판 미삭제 확인). 검증·카운터 잠금·저장 로직은 공유하고 권한 판정과 출처만 진입점별로 다르다.
- **참조 자격(저장 시)**: 콘텐츠의 출처와 이미지 출처가 같을 때만 참조한다 — 공지는 `NOTICE` 이미지만, 게시판 X의 게시글은 `BOARD:X` 이미지만. **다른 출처의 이미지 ID가 본문에 있으면 400**("다른 게시판·공지에서 올린 이미지는 사용할 수 없습니다. 이미지를 다시 올려 주세요") — 조용히 빼면 편집자 화면에서는 보이고 공개 화면에서는 깨지는 불일치가 남는다. 존재하지 않는 ID는 지금처럼 참조에서 뺀다(무해). 공지 쪽에도 같은 규칙이 적용되지만 지금 공지가 참조하는 이미지는 전부 `NOTICE` 출처라 기존 동작은 바뀌지 않는다.
- **공개 판정**(`PublicContentImageService.findViewable`) — 다음 중 하나면 200, 아니면 404:
  1. (기존) 공개 공지가 참조
  2. (좁힘) 출처가 `NOTICE`이고 요청자가 `NOTICE` READ — 기존 공지 이미지는 전부 `NOTICE` 출처라 **기존 공지 동작은 그대로**다(미참조 미리보기 포함). 게시판 이미지에는 적용되지 않는다.
  3. (추가) 공개 게시판(`publicYn` ∧ ¬`deleted`)의 공개 게시글(`useYn` ∧ ¬`deleted`)이 참조 — `existsPublishedPostRef`
  4. (추가) 출처가 `BOARD:X`이고 요청자가 **게시판 X의 현재 READ**(ADMIN 포함) — 작성 중(미참조)·비공개 게시글 미리보기 모두. 권한을 회수하면 즉시 404다(별도 만료 정책 불필요).
  - 업로더 본인 조건(v1의 5번)은 **삭제**했다(R1-3).
  - **공개 참조에도 출처 일치를 요구한다**(v3 — 리뷰 R2-1): 1번은 "출처가 `NOTICE`인 이미지를 공개 공지가 참조", 3번은 "출처가 `BOARD:X`인 이미지를 게시판 X의 공개 게시글이 참조"로 쿼리 조건에 넣는다. 저장 시 검증(참조 자격)이 있어도, **PR B 이전 버전으로 롤백한 동안**에는 구버전이 출처를 모르고 참조를 저장하므로 불일치 참조가 생길 수 있다 — 재배포 후 그 참조로는 익명 공개되지 않는다.
  - 참조 자격 규칙 때문에 게시판 X 이미지가 다른 게시판·공지의 공개 글로 노출되는 경로는 없다(3번은 출처 = 게시판 X인 글만 참조할 수 있음).
- 편집기: `notice-editor.js`의 업로드 URL을 `options.uploadUrl`(기본값 = 지금 공지 URL)로 바꾸고, **`setUploadUrl(url)` API를 추가**한다 — 진행 중인 업로드를 무효화(세대 증가·대기 삽입 폐기·busy 해제)한 뒤 URL만 바꾼다. 게시판 화면은 편집기 인스턴스를 **페이지에 하나만** 만들고 게시판·게시글을 열 때마다 `setUploadUrl('/admin/api/boards/{boardId}/content-images')` + `setHtml(...)`을 부른다(v4 — 리뷰 R3-4: 지금 편집기에는 인스턴스·툴바 해제 API가 없어 재생성하면 이전 툴바·핸들러가 이전 게시판 URL로 남는다). UI 검증: A→B→A 반복 전환 후 업로드가 현재 게시판 출처로 저장되는지, 업로드 대기 중 게시판 전환 시 늦은 응답이 버려지는지.
- 회수 절차(`docs/deployment.md`) — 쟁점 7 참조.

### 쟁점 10. 공개 측 — **`publicweb/board`, 공지와 같은 격리·404 흡수·스트리밍**

- `GET /boards/{boardId}`(목록 + `keyword` 제목 검색, 10건/페이지, `MAX_PAGE` 1000, 100 코드 유닛), `GET /boards/{boardId}/posts/{postId}`(상세), `GET·HEAD /boards/{boardId}/posts/{postId}/attachments/{attachmentId}`(스트리밍 다운로드).
- 불변식: **게시판 `publicYn=true ∧ deleted=false` ∧ 게시글 `useYn=true ∧ deleted=false` ∧ 게시글이 그 게시판 소속**. 목록 SELECT와 COUNT가 같은 조건(QueryDSL `BooleanBuilder` 하나). 위반·비숫자·없음은 모두 같은 404(`sendError(404)`). 별도 `PublicBoardService`에만 이 조건을 둔다(관리 서비스 재사용 금지).
- 경로 변수는 문자열로 받아 직접 파싱(전역 JSON 예외 처리 회피), 예외는 `PublicWebExceptionAdvice`(기존 `basePackages="com.cms.publicweb"`가 그대로 덮는다).
- 다운로드: `findPublishedAttachment`(조회 트랜잭션) → 트랜잭션 없는 `openAttachment` → 4KB 버퍼 스트리밍, `octet-stream`·`attachment`·`nosniff`·`no-store`, HEAD 전용 핸들러. 공개 공지 컨트롤러의 3구간 실패 처리를 그대로 따른다.
- 본문 출력은 `th:utext`(정리된 필드만) — 공개 게시판 템플릿 컨벤션 테스트를 공지와 같이 둔다(그 한 곳 외 `th:utext` 금지).
- 화면: 공지 템플릿·CSS 구조를 따르되 게시판 이름을 머리에 표시(`th:text`). 게시판 목록 색인 페이지(`/boards`)는 범위 밖.
- `SecurityConfig`: `/boards`, `/boards/**` GET·HEAD `permitAll` + 나머지 메서드 `denyAll`(공지와 같은 3줄, `/notices` 규칙 바로 아래).
- 레이트리밋(`application.yml`, 순서 중요 — 첨부가 먼저): `board-attachment` `/boards/*/posts/*/attachments/*` 20/60s, `public-board` `/boards/**` 120/60s.

### 쟁점 11. 사이드바·메뉴 — **메뉴 2개 시드, 노출은 기존 판정 그대로**

- PR A: V28 `'게시판 관리'`(`/admin/board/manage`, ADMIN에게만 보임 — `BOARD_ADMIN`이 ADMIN_ONLY).
- PR B: V30 `'게시글 관리'`(`/admin/board/posts`, `BOARD` 기능 단위 READ = 어느 게시판이든 READ가 있으면 MANAGER에게 보임).
- 둘 다 최상위 끝 순서(V15와 같은 `LEAST(COALESCE(MAX(ord),-1)+1, MAX_INT)`), 멱등 `WHERE NOT EXISTS`.

### 쟁점 12. 통합 검색 — **게시글 섹션 추가(조회 가능한 게시판만)**

- `posts` 섹션: ADMIN은 미삭제 게시판의 미삭제 게시글, MANAGER는 스냅샷의 READ 게시판 집합(`boardIds`)에 속한 것만. 집합이 비면 섹션 키 생략(기존 규칙). 결과에 게시판 이름(`textContent`로만 렌더링)·게시글 제목·노출 여부·작성일, 이동 링크 `/admin/board/posts?boardId=&id=`.
- 화면 `topbar-search.js`에 섹션 하나 추가.
- **왜 이번에 넣나**: 로드맵 수정 파일 목록에 명시돼 있고, 빼면 "MANAGER가 검색으로 권한 없는 게시판 글을 찾는" 문제는 없지만 기능이 반쪽이다. 비용이 작다(쿼리 1개 + 섹션 1개).

## 설계 제약 (프로젝트·UI)

- 관리 화면은 Thymeleaf + SB Admin 2(Bootstrap 4) + 바닐라 JS. 새 페이지 컨트롤러는 `@AdminPage` 필수, 상태 변경 fetch는 CSRF 헤더 필수. 프레임워크·번들러 도입 없음.
- 페이지 핸들러에는 메서드 보안을 걸지 않는다(전역 `@RestControllerAdvice`가 HTML 대신 JSON 403을 낸다) — 페이지 차단은 URL 게이트(HTML 403).
- 공개 컨트롤러는 경로 변수를 문자열로 받는다(같은 이유).
- 시각은 주입된 `Clock`(`LocalDateTime.now(clock)`), 엔티티 변경 메서드는 `now` 파라미터. 대소문자 변환은 `Locale.ROOT`. 동적 조건은 QueryDSL(`*RepositoryImpl`), 정렬은 `id` 보조 정렬.
- 본문을 내보내는 모든 경로는 `HtmlContentSanitizer`를 거친다. 편집기 `formats`·툴바·허용 목록 1:1은 기존 편집기를 재사용하므로 자동으로 유지된다.
- 머지된 마이그레이션 수정 금지. DDL과 시드 DML은 파일을 나눈다.
- 관리 게시글 화면은 공지 관리 화면(`notice/manage.html`)의 구조·비동기 보호(세대 토큰·AbortController·저장 토큰)를 그대로 옮긴다 — **①-2에서 공지 화면이 이 화면으로 대체될 예정이라 일시적 중복을 수용**한다(공통 JS 추출은 ①-2에서 판단).

## 변경 범위

### 스키마 (Flyway, 사전 고지 대상)
- PR A: `V26__create_board.sql`(`board`, `member_board_permission` — DDL 2문), `V27__add_content_image_scope.sql`(`content_image`에 `scope_type`·`scope_id` 추가 — DDL 1문, 기존 행 기본값 `NOTICE`), `V28__seed_board_admin_menu.sql`(DML).
- PR B: `V29__create_post.sql`(`post`, `post_attachment` — DDL 2문), `V30__seed_board_post_menu.sql`(DML).
- **출처 컬럼을 PR A로 앞당긴 이유(v4 — 리뷰 R3-1)**: PR A가 출처 판정(공개 판정 2번 좁힘·공개 참조 출처 일치·공지 저장 시 참조 자격)을 갖고 있어야 PR B → PR A 롤백이 안전하다. PR A는 게시판 이미지를 만들지 않지만, PR B가 만든 `BOARD` 출처 이미지를 PR A가 받아도 NOTICE 조회 권한자에게 404이고 공지에 넣으면 400이다. 즉 PR A가 **이 기능의 안전한 롤백 하한**이다.
- V27만 기존 테이블 변경(컬럼 추가, 기본값 있음 — 구버전 앱의 INSERT는 컬럼을 몰라도 기본값 `NOTICE`가 들어간다). 나머지는 새 테이블. 롤백 시 구버전 앱은 새 테이블·컬럼을 무시하고 기동한다(Hibernate `validate`는 매핑된 엔티티만 검사하고, Flyway는 적용된 미래 버전을 무시한다 — 롤백 호환 기동을 마이그레이션 시험으로 확인).
- **롤백 하한(v3 — 리뷰 R2-1, v4 — R3-1로 강화)**: 게시판 이미지(`scope_type='BOARD'`)가 하나라도 생긴 뒤 **PR A 이전 앱**으로 롤백하면, 구버전의 "NOTICE READ면 모든 이미지 200"이 되살아나 NOTICE 조회 권한자가 비공개 게시판 이미지를 본다 — 쓰기 동결로는 이 조회를 막을 수 없다(v3의 "공지 쓰기 동결" 예외 절차는 **삭제**). 그래서 **PR A 이전으로의 롤백은 금지하고 roll-forward만** 한다. PR B → PR A 롤백은 허용된다(PR A가 출처 판정을 가짐 — 시험으로 고정: PR A 코드에서 `BOARD` 출처 이미지가 NOTICE 조회 권한자에게 404). 만약 금지를 어기고 롤백했다면, 재배포 전 점검 SQL(출처가 다른 참조 — `content_image_ref r JOIN content_image i` where (`r.owner_type='NOTICE'` and `i.scope_type<>'NOTICE'`) or (`r.owner_type='POST'` and 게시판·출처 불일치))로 불일치 참조를 찾고, 찾으면 해당 본문을 운영자가 고친다(공개 판정이 출처 일치를 요구하므로 재배포 후 익명 노출은 없지만, 편집 화면에서 저장하면 400이 난다). 이 내용을 `docs/migration-guide.md`에 적는다.
- **롤백 후 재배포(v2 — 리뷰 R1-6)**: PR A 이전 앱으로 롤백한 동안의 역할 변경은 `member_board_permission`을 지우지 않는다(구버전은 테이블을 모른다). 신버전을 다시 배포하면 남은 행이 되살아난다 — 기존 `member_permission`과 같은 위험이다. `docs/deployment.md`·`admin/permission/CLAUDE.md`의 "롤백했다가 신버전을 다시 배포할 때" 정리 SQL에 `DELETE FROM member_board_permission;`을 추가하고(같은 트랜잭션, 회원 전원 버전 +1 유지), 정리 SQL이 두 테이블을 모두 비우는지 마이그레이션 시험(`MemberPermissionMigrationTest`의 재배포 정리 시험)에 고정한다.

### 인가 정책 (사전 승인 필요)
- 카탈로그: `BOARD`(BOARD_SCOPED, 게이트 4패턴·메뉴 `/admin/board/posts`), `BOARD_ADMIN`(ADMIN_ONLY, 메뉴 `/admin/board/manage`).
- `SecurityConfig`: BOARD_SCOPED 게이트 등록(기능 단위 READ), `/boards`·`/boards/**` GET·HEAD 공개 + 그 외 `denyAll`.
- 본문 이미지 공개 판정: 2번을 `NOTICE` 출처로 좁히고(기존 공지 동작 불변), 3·4번 추가(쟁점 9).

### 코드 (주요)
- 권한: `FeatureKind`·`AdminFeature`·`AdminPermissionEvaluator`(`allowsBoard`·`checkBoard`·`readableBoardIds`)·`PermissionSnapshot`·`PermissionCache`·신규 `MemberBoardPermission`(+Id·Repository)·`RequireBoardPermission`·`MemberPermissionService`·DTO 2종·`permission/manage.html`·`AdminMemberService`(역할 변경 삭제).
- 게시판: `admin/board/`(domain `Board`·`Post`·`PostAttachment`, repository + QueryDSL, service `BoardService`·`PostService`·`PostAttachmentService`, controller API·페이지, dto), `templates/admin/board/manage.html`·`posts.html`.
- 공용 추출: `common/attachment/AttachmentFilePolicy`, `common/html/ContentBodyPolicy`(공지 서비스가 위임).
- 본문 이미지: `ContentImageService`(owner POST·게시판 업로드 진입점), `ContentImageRefRepository.existsPublishedPostRef`, `PublicContentImageService`, `notice-editor.js`(`uploadUrl` 옵션).
- 공개: `publicweb/board/`(controller·service·dto), `templates/public/board/list.html`·`detail.html`·`error.html`, `static/css/public/board.css`.
- 검색: `AdminSearchService`·`AdminSearchResponse`·`topbar-search.js`.
- 설정: `SecurityConfig`, `application.yml`(레이트리밋), `AdminActionTypes`·`templates/admin/log/manage.html`(라벨).
- 문서: `docs/deployment.md`(회수 ⑤ 보존 목록), CLAUDE.md(루트 지침 지도, `admin/permission`, 신규 `admin/board`·`publicweb/board`, `config` 경로 표, `contentimage`).

## 작업 단계

**PR A — 게시판 정의 + 게시판별 권한**
1. 회귀 기준선: 기존 권한·공지 테스트 전체 통과 확인.
2. V26·V28 → `Board` 엔티티·리포지토리 → `BoardService`(생성·조회·수정 — 삭제는 PR B) → 게시판 정의 API·화면.
3. 권한: `FeatureKind.BOARD_SCOPED`·`AdminFeature`(`BOARD`·`BOARD_ADMIN`) → `MemberBoardPermission` → 스냅샷·캐시 → 판정기(`allowsBoard`·`checkBoard`·기능 단위 판정) → `RequireBoardPermission` → `SecurityConfig` 게이트 → 컨벤션 테스트 확장.
4. 권한관리: 서비스(`boardGrants`·게시판 잠금 확인·두 테이블 diff·감사/알림 라벨) → DTO → 화면 → 역할 변경 삭제 → `TestMembers.delete`.
5. `GET /admin/api/members/me/boards`.
5-1. V27 → `docs/deployment.md` 회수 절차에 게시글 테이블 존재 시 중단 가드(R4-1) → 본문 이미지 출처(공지 업로드 = `NOTICE` 출처, 공지 저장 시 참조 자격 400, 공개 판정 1번 출처 일치·2번 `NOTICE` 출처로 좁힘) — PR B → PR A 롤백 하한을 만드는 단계. 시험: `BOARD` 출처 이미지 행을 직접 넣어 NOTICE 조회 권한자 404·공지 저장 400.
6. 테스트 → 실기(ADMIN 게시판 생성 → MANAGER에게 게시판 A만 부여 → 화면·버전·감사 확인 → 공지 권한 회귀) → 기록 → 코드 리뷰 → PR.

**PR B — 게시글·공개·검색**
7. 공용 추출(`AttachmentFilePolicy`·`ContentBodyPolicy`) — 공지 테스트로 동작 불변 확인. `FileStorageTransactionSupport.deleteOnRollback`을 확정 롤백일 때만 삭제로 바꾸고 공지 첨부의 복제 구현을 헬퍼로 교체(R5-1).
8. V29 → `Post`·`PostAttachment` → QueryDSL 리포지토리 → `PostService`·`PostAttachmentService`(잠금·게시판 확인·첨부 허용) → 게시판 삭제 API(살아 있는 게시글 409 + 권한 행 정리) → 관리 화면 삭제 버튼.
9. 본문 이미지 게시판 쪽(게시판 업로드 진입점 = `BOARD:X` 출처·게시글 저장 시 참조 자격 400·공개 판정 3·4 추가) → 편집기 `uploadUrl`·`setUploadUrl`.
10. 관리 API·게시글 관리 화면(`posts.html`).
11. 공개 서비스·컨트롤러·템플릿·CSS → `SecurityConfig` `/boards/**` → 레이트리밋.
12. 통합 검색 게시글 섹션.
13. `docs/deployment.md` 회수 절차 ②(게시글 참조 보존)·⑤(게시글 첨부 보존)·⑤-2(프로필 파일 대조).
14. 테스트 → 실기(완료 기준 Playwright 시나리오) → 기록 → 코드 리뷰 → PR.

## 완료 기준

- `./gradlew test` 통과, CI(`test`·`prod-smoke`) 통과 — PR A·B 각각.
- **게시판별 위임**: 게시판 A 권한만 있는 MANAGER가 게시판 B의 글 목록·상세·작성·수정·삭제·첨부·이미지 업로드에서 403, A에서는 허용(보안 슬라이스 + 실제 스택 매트릭스 통합 테스트 — 공지 매트릭스처럼 허용은 저장 결과, 거부는 403 + 변경 없음).
- **공지 권한 회귀**: 기존 `AdminPermissionMatrixIntegrationTest`·`MemberPermission*` 시험이 수정 없이(요청 DTO의 `boardGrants` 필수화에 따른 요청 본문 보강 제외) 통과.
- 권한관리: `boardGrants` 누락 400, 삭제·없는 게시판 400, READ 없는 쓰기 400, 변경 없음 200(버전 유지), 버전 충돌 409, 역할 변경 시 게시판 권한 삭제, 게시판 삭제 시 권한 행 삭제·캐시 반영, 감사 라벨에 게시판 이름 없음.
- 공개: 비공개 게시판·삭제 게시판·미노출·삭제 게시글·다른 게시판 소속 게시글이 목록·COUNT·상세·첨부 모두에서 404(Testcontainers), `/boards/**` 비-GET/HEAD 거부, 레이트리밋 적용, 본문 `th:utext`는 한 곳.
- 본문 이미지(실제 스택 통합 테스트): 공개 게시글 참조 200, 비공개 게시판·미노출 게시글만 참조하면 익명 404, 게시판 X READ 권한자는 X 출처 이미지(미참조 포함) 200, **권한 회수 직후 404**, **NOTICE READ만 가진 MANAGER는 게시판 이미지 GET·HEAD 404**, **게시판 A 글에 게시판 B 출처 이미지를 넣으면 400 + 참조·공개 상태 불변**, 공지에 게시판 출처 이미지를 넣어도 400, **출처가 다른 참조 행을 직접 넣어도(롤백 중 생성 모사) 공개 공지·공개 게시글 경로로 익명 404**, 기존 공지 이미지 시험은 수정 없이 통과.
- 권한 변경 전체 diff 로그: 커밋 성공 `COMMITTED`, 롤백 `ROLLED_BACK`, 커밋 후 실패 주입 `UNKNOWN` 표시.
- 회수 절차: dev 실기에서 ② 대상이 "삭제 콘텐츠 전용 + 미참조"뿐이고(살아 있는 게시글 참조 이미지 제외) ⑤ 고아 목록에 게시글 첨부가 없음을 확인.
- 롤백 호환: V26~V30 적용 DB에서 구버전(이전 머지 커밋 기준) 마이그레이션 집합으로 `validate` 통과, 재배포 정리 SQL이 두 권한 테이블을 비움(마이그레이션 시험).
- Playwright: 게시판 생성 → MANAGER에게 그 게시판만 위임 → 편집기로 게시글 + 첨부 작성 → 공개 화면 노출·다운로드 → 다른 게시판 접근 차단(관리 403·공개 비공개 게시판 404).

## 리스크

- **SpEL 파라미터 이름**: `-parameters`가 꺼지면 `#boardId`가 null → 전부 403(fail-closed, 열리지는 않음). 슬라이스 테스트의 "A 허용" 단언이 잡는다.
- **기능 단위 게이트는 "어느 게시판이든"**: 게이트를 통과한 MANAGER가 다른 게시판 경로에 닿는 것은 정상이며 차단은 메서드 계층 몫이다 — 컨벤션 테스트가 선언 누락을 막는다.
- **본문 이미지 출처 규칙의 제약**: 다른 게시판(또는 공지)에서 올린 이미지는 복사해 붙여도 저장이 400이다 — 편집자가 다시 올려야 한다(의도된 경계). 게시판 X의 이미지를 같은 게시판 안의 다른 글로 옮기는 것은 허용된다.
- **관리 화면 중복**: 공지·게시글 화면 JS가 당분간 두 벌.
- **게시판 이름 중복 허용**: 화면에 `#id`를 함께 보여 구분한다.
- **캐시 적재량**: 게시판 수 × MANAGER 수 × 4 — 수백 게시판·수십 MANAGER까지 메모리 부담 없음(단일 인스턴스 전제는 기존과 같음).

## 개정 이력

- v1 (2026-10-08): 최초 작성
- v2 (2026-10-08) 변경 — 적대적 리뷰 1라운드(no-ship, P1 6·P2 1) 수용 6·일부 수용 1:
  - R1-1 다른 게시판 비공개 이미지를 자기 공개 글에 참조해 공개: `content_image`에 업로드 출처(`scope_type`·`scope_id`, V29) 추가, 저장 시 콘텐츠 출처와 이미지 출처가 다르면 400(공지 쪽 포함)
  - R1-2 NOTICE READ의 전역 이미지 접근이 게시판 비공개 이미지까지 확장: 공개 판정 2번을 `NOTICE` 출처로 좁힘(기존 공지 이미지는 전부 NOTICE 출처라 동작 불변)
  - R1-3 업로더 본인 조건이 권한 회수 뒤에도 유지: 삭제하고 "출처 게시판의 현재 READ"로 대체(회수 즉시 404)
  - R1-4 회수 절차 ②가 살아 있는 게시글 참조 이미지를 지움: ②에 게시글 참조 보존 조건 추가, ⑤ 보존 목록에 `post_attachment`, dev 실기로 대상 확인
  - R1-5 PR B→A 롤백 시 게시글 있는 게시판 삭제 가능: 게시판 삭제 API를 PR B로 이동(게시글 검사와 동시 도입)
  - R1-6 롤백 후 재배포 시 게시판 권한 부활: 재배포 정리 SQL에 `member_board_permission` 추가 + 마이그레이션 시험
  - R1-7(일부 수용) 감사 라벨·알림 잘림: 건수 → 회수 → 추가 순서 + 잘림 표시 + 전체 diff INFO 로그. 기각: 구조화된 감사 상세 컬럼(감사 스키마 변경, 범위 밖)
- v3 (2026-10-08) 변경 — 적대적 리뷰 2라운드(no-ship, P1 1·P2 1) 전부 수용:
  - R2-1 PR B→이전 앱 롤백이 출처 경계를 다시 열고 불일치 참조가 재배포 후에도 공개를 남김: 공개 판정 1·3번에 출처 일치 조건, PR B 이후 앱 단독 롤백 제한(roll-forward 기본·불가피 시 공지 쓰기 동결)·재배포 전 불일치 참조 점검 SQL(`docs/migration-guide.md`), 불일치 참조 행 직접 삽입 시 익명 404 시험
  - R2-2 전체 diff INFO 로그가 커밋 결과를 구분하지 않음: AFTER_COMPLETION 리스너에서 실행자·버전·트랜잭션 결과(COMMITTED/ROLLED_BACK/UNKNOWN)와 함께 기록, 커밋 실패 주입 시험에 단언 추가
- v4 (2026-10-08) 변경 — 적대적 리뷰 3라운드(no-ship, P1 1·P2 3) 전부 수용:
  - R3-1 쓰기 동결로는 롤백 중 조회 유출을 막지 못함: 출처 컬럼·공지 참조 자격·공개 판정 좁힘을 **PR A로 앞당겨**(V27) PR A를 안전한 롤백 하한으로 만들고, PR A 이전으로의 롤백은 금지(roll-forward만) — v3의 "공지 쓰기 동결" 예외 절차 삭제. 마이그레이션 번호 재배치(PR A V26~V28, PR B V29~V30)
  - R3-2 `@TransactionalEventListener`는 완료 상태를 받지 못함: 서비스가 불변 diff를 캡처하고 `TransactionSynchronization.afterCompletion(status)`를 직접 등록해 결과와 함께 기록, 캐시 무효화는 기존 리스너 유지
  - R3-3 회수 대상 게시판이 동시 삭제되면 엔티티 재삭제 stale-state 예외: 게시판 권한 회수를 키 단위 JPQL 벌크 삭제(멱등)로, 교차 잠금안은 교착 위험으로 기각, 순서 고정 동시성 시험 추가
  - R3-4 편집기 재생성 시 이전 툴바·핸들러 잔존: 인스턴스 하나 유지 + `setUploadUrl`(업로드 무효화 후 URL 교체), 반복 전환·업로드 중 전환 UI 검증
- v5 (2026-10-08) 변경 — 적대적 리뷰 4라운드(no-ship, P1 1·P2 1) 전부 수용:
  - R4-1 롤백 하한 PR A의 회수 절차가 PR B 데이터(게시글 참조 이미지·게시글 첨부)를 지울 수 있음: PR A에서 회수 절차에 "`post`·`post_attachment` 테이블이 있으면 중단" 가드 추가, PR B가 게시글 보존 조건으로 대체, dev 실기로 가드 확인
  - R4-2 벌크 삭제의 항상 200 가정이 `innodb_snapshot_isolation`에 의존: ON이면 1020 → 409 허용을 명시(재시도 설계 안 함), 래치 시험을 ON·OFF 두 설정에서 실행해 OFF=200·ON=409·무변경·재저장 성공 단언
- v6 (2026-10-08) 변경 — 적대적 리뷰 5라운드(no-ship, P1 1·P2 1) 전부 수용:
  - R5-1 공용 업로드 정리가 `UNKNOWN`을 롤백으로 취급해 커밋된 파일 삭제: `FileStorageTransactionSupport.deleteOnRollback`을 `STATUS_ROLLED_BACK`일 때만 삭제(UNKNOWN 보존 + WARN), 공지 첨부 복제 구현을 헬퍼로 교체(기존 결함 동시 수정), 세 상태 단위 시험 + 커밋 후 실패 주입 통합 시험
  - R5-2 diff 로그 실행자 userId가 제어문자를 허용: 실행자를 숫자 회원 ID로 기록
- v7 (2026-10-08) 변경 — 적대적 리뷰 6라운드(no-ship, P2 1) 수용:
  - R6-1 UNKNOWN에서 보존한 프로필 파일을 회수 ⑤(profile/ 제외)로 정리할 수 없음: `docs/deployment.md`에 ⑤-2 프로필 파일 대조(UPLOADED 행 키 vs `profile/` 파일) 추가, WARN에 네임스페이스 기록, dev 실기 확인
- v8 (2026-10-08) 구현 중 정정 — PR A 실측 결과: Spring은 커밋 단계 예외(커밋 응답 유실 포함)에서 `afterCompletion(STATUS_ROLLED_BACK)`을 넘긴다. v3·v6의 "`STATUS_UNKNOWN`으로 불명을 구분" 가정이 틀렸으므로, 권한 diff 로그(PR A)와 파일 정리 헬퍼(PR B)는 **동기화의 `beforeCommit` 호출 여부**로 판정한다 — 커밋 시도 전 롤백만 확정 `ROLLED_BACK`(파일 삭제), 커밋 시도 이후 비커밋은 `UNKNOWN`(파일 보존). 시험: 커밋 직전 실패 → ROLLED_BACK, 커밋 응답 유실 → UNKNOWN

## 구현·검증 결과 — PR A (2026-10-08)

### Context

계획 v7(적대적 리뷰 7라운드 ship) 승인 범위 중 **PR A(게시판 정의 + 게시판별 권한 + 본문 이미지 출처)**를 구현했다. 브랜치 `feat/board`(31차 로드맵 갱신 문서 3개 포함). 게시글·공개 화면·통합 검색·게시판 삭제·파일 정리 헬퍼 수정은 PR B다.

### 핵심 확정 사항 (구현 중 계획과 달라진 점 포함)

- **(정정, v8)** Spring은 커밋 단계 예외(커밋 응답 유실 포함)에서 `afterCompletion(STATUS_ROLLED_BACK)`을 넘긴다 — 상태 코드만으로 불명을 구분한다는 v3·v6 가정이 틀렸다. 권한 diff 로그는 동기화의 `beforeCommit` 호출 여부로 `ROLLED_BACK`(커밋 시도 전)·`UNKNOWN`(커밋 시도 후 비커밋)을 나눈다. PR B의 `FileStorageTransactionSupport` 수정도 같은 방식을 쓴다(`docs/troubleshooting.md` 기록).
- 수동 회수 절차의 PR A 중단 가드는 별도 단계가 아니라 기존 `ready()` 가드에 넣었다(모든 삭제 단계가 같은 조건에서 멈춘다).
- 참조 자격 검사는 JPQL 대신 `findAllById`로 이미지 행을 읽어 서비스가 출처를 비교한다(NULL 파라미터 비교 회피). 그 결과 `ContentImageRepository.findExistingIds`는 쓰이지 않아 삭제했다.
- 과거 마이그레이션 시험 2건(`MenuAccessRoleDropMigrationTest`·`PermissionMenuMigrationTest`)은 최신까지 올린 뒤 메뉴 수·맨 끝 메뉴를 단언해 V28 시드에 깨졌다 — 프로젝트 관례(V22 시험)대로 대상 버전(16·15)까지만 적용하도록 고쳤다.
- 화면 제목(`<title>`)은 기존 관리 화면들과 같이 공통 head 프래그먼트 값("관리자 대시보드")이다(기존 관례, 변경하지 않음).

### 구현 파일

- 스키마: `V26__create_board.sql`, `V27__add_content_image_scope.sql`, `V28__seed_board_admin_menu.sql`
- 게시판: `admin/board/`(`domain/Board`, `repository/BoardRepository`(수정 잠금·공유 잠금), `service/BoardService`·`MyBoardService`, `controller/BoardController`·`BoardPageController`·`MyBoardController`, `dto/`), `templates/admin/board/manage.html`, `admin/board/CLAUDE.md`
- 권한: `FeatureKind`(`BOARD_SCOPED`), `AdminFeature`(`BOARD`·`BOARD_ADMIN`), `MemberBoardPermission`(+`Id`·`Repository`), `PermissionSnapshot`, `PermissionCache`, `AdminPermissionEvaluator`(`allowsBoard`·`checkBoard`·`boardActions`), `RequireBoardPermission`, `MemberPermissionService`(+DTO 2종), `templates/admin/permission/manage.html`, `AdminMemberService`(역할 변경 삭제), `MenuVisibility`(노출 안내), `NotificationMessages`(순서), `SecurityConfig`(게이트)
- 본문 이미지: `ContentImage`(출처 필드), `ContentImageService`(`SCOPE_NOTICE`·참조 자격), `ContentImageRefRepository`(공개 판정 출처 조건), `PublicContentImageService`, `NoticeService`(호출부)
- 감사 라벨: `AdminActionTypes`(`BOARD_CREATE`·`BOARD_UPDATE`)·`templates/admin/log/manage.html`
- 문서: `docs/deployment.md`(회수 가드·재배포 정리 SQL·출처 롤백 하한), `docs/migration-guide.md`(V26~V28), `docs/troubleshooting.md`, CLAUDE.md(루트 지도·`admin/permission`·`config`·`contentimage`·신규 `admin/board`)
- 시험(신규): `AdminPermissionEvaluatorBoardTest`, `MemberPermissionServiceBoardTest`, `RequireBoardPermissionSpelTest`, `BoardPermissionIntegrationTest`, `BoardMigrationTest` / (보강) `PermissionCacheTest`, `AdminEndpointAuthorizationConventionTest`, `AdminFeatureTest`, `ContentImageIntegrationTest`, `MemberPermissionApiIntegrationTest`(결과 로그 3상태), `MemberRoleChangePermissionIntegrationTest`, `MemberPermissionMigrationTest`(재배포 정리) / (요청 본문·라벨·목 갱신) 권한·알림·공지·회원 시험

### 검증 결과

- **단위·통합 시험**: 신규·보강 시험 전부 통과. 판별력 확인 — `RequireBoardPermissionSpelTest`는 핸들러 파라미터 이름을 `boardId`가 아니게 바꾸면 "게시판 A 허용" 단언이 실패한다(변이 후 원복).
- **전체 `./gradlew test`**: 1531건 실패 0(건너뜀 11 — 로컬 Windows 전용, 이전 PR과 같음), BUILD SUCCESSFUL.
- **실기(dev Docker, Playwright + curl, 스크린샷 `.playwright-mcp/board/` — git 무시 경로)**:
  1. dev DB 백업 후 재빌드 → V26~V28 적용(`now at version v28`).
  2. ADMIN이 게시판 화면에서 공개 게시판 `자료실<img src=x onerror=alert(1)>`·비공개 `보도자료` 생성 — 이름은 텍스트로만 표시(삽입 `img` 0), 사이드바에 "게시판 관리"(01).
  3. 권한관리 화면: 기능 표에 `BOARD_SCOPED` 행 없음·"게시판 관리 — 위임 불가" 표시, 게시판별 표 2행. 게시판 #1 "생성" 클릭 시 "조회" 자동 체크 → 저장 → DB 행 2개(#1 READ·CREATE)·버전 v0→v1·감사 라벨 `v0→v1: 추가 2·회수 0 | +게시판#1.조회, +게시판#1.생성`(이름 없음)·`권한 변경 COMMITTED: actorMemberId=1, memberId=614 …` 로그(02).
  4. MANAGER 로그인: `GET /admin/api/members/me/boards` = #1·`[READ, CREATE]`만, 게시판 정의 API(목록·상세·생성)·화면·권한관리 API 403(화면은 HTML 403, 03), 공지(권한 없음) 403, 사이드바에 "게시판 관리" 없음.
  5. 공지 회귀: NOTICE READ 부여(게시판 권한 유지) → MANAGER 공지 API·화면 200·공지 생성 403·사이드바 공지 표시, 전부 회수 → 공지 403·내 게시판 `[]`. `boardGrants` 없는 구 화면 형태 PUT → 400 `boardGrants: must not be null`.
  6. 원복: 검증용 MANAGER·게시판·권한 행 삭제, 캐시 폐기를 위해 앱 재시작. dev DB는 V28 적용 상태로 남는다(적용 전 백업: 세션 scratchpad `dev-cms-before-v26.sql`).
- 수동 회수 절차의 `ready()` 가드는 문서 SQL만 갱신했고 dev에서 실행 확인은 하지 않았다(게시글 테이블은 PR B에서 생긴다 — PR B 실기에서 "게시글 테이블이 있으면 중단"을 확인한다).

### 이슈

- 전체 시험 첫 실행에서 `AdminMemberServiceTest` 3건 실패 — `@InjectMocks` 대상에 새 리포지토리 목이 없었다(코드 결함 아님). 목 추가 후 통과.
- 재실행 한 번은 Docker Desktop이 꺼져 있어 Testcontainers 통합 시험 86건이 "Could not find a valid Docker environment"로 실패했다(환경 문제) — Docker를 켠 뒤 전체 통과.

### 후속 (PR B)

- 게시글·첨부·게시판 삭제(살아 있는 게시글 409 + 권한 행 정리)·게시판 이미지 업로드(`BOARD:X` 출처)·공개 판정 3·4·편집기 `setUploadUrl`·게시글 관리 화면·공개 `/boards/**`·레이트리밋·통합 검색·회수 절차 ②⑤⑤-2 갱신.
- `FileStorageTransactionSupport.deleteOnRollback`을 `beforeCommit` 표시 기반 확정 롤백 삭제로 바꾸고 공지 첨부 복제 구현을 교체(v8).
- 게시판 삭제와 회수 PUT의 래치 동시성 시험(ON·OFF 두 설정, R3-3·R4-2).
