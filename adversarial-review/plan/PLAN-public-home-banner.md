# PLAN — 공개 메인 페이지(`/`) + 배너 관리 (로드맵 Top 8 ②)

> 상태: v5 — 적대적 리뷰 5라운드 ship, 사용자 승인 후 **구현·검증 완료, PR #115로 머지(2026-10-10, `e644b71`)**. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- v1: 최초 작성(정찰 → 쟁점 14개 결정).
- v5 변경(4라운드 반영 — codex gpt-6.1-sol, 새 P1×1 수용):
  - R4-1(P1 기존 수동 회수 ⑤가 정상 배너 이미지를 삭제): 영향 범위·쟁점 3·리스크 변경 — `docs/deployment.md` ⑤(469~479행 확인됨)는 보존 목록이 `content_image`+`post_attachment`뿐이고 디스크 검색이 `profile/`만 제외해 `banner/…` 파일을 고아로 분류·삭제한다. → ⑤의 `find`에 `-not -path "./banner/*"`를 추가하고 **새 ⑤-3 배너 파일 대조**(⑤-2와 같은 구조: 보존 = `banner` 테이블의 `storage_key`, 디스크 = `banner/` 아래 파일(접두어 제거), 차이만 고아, `ready` 가드 + `&&` 체인)를 추가한다. 비노출·만료 배너도 행이 있으면 보존. 검증: dev Docker에서 배너 행이 있는 파일은 ⑤·⑤-3 후에도 남고 행 없는 파일만 삭제됨을 실행 확인(완료 기준에 추가). 새 코드 없음.
- v4 변경(3라운드 반영 — codex gpt-6.1-sol, 새 P1×1 수용):
  - R3-1(P1 네임스페이스 스트리밍 읽기 API 없음): 쟁점 3·11, 영향 범위, 작업 단계 1 변경 — `FileStorage`에는 `open(String)`만 있고 네임스페이스 오버로드는 `store/load/delete`뿐이다(`FileStorage.java:45`, 확인됨). 예약어에 `banner`를 추가하면 `open("banner/…")`도 막혀 공개 GET·HEAD를 구현할 수 없고 `load(key,"banner")`를 감싸면 스트리밍 목적(힙 보호)을 훼손한다. → **`FileStorage.open(storageKey, namespace)` 추가**(기본 구현은 다른 네임스페이스 메서드와 같은 `UnsupportedOperationException`), `LocalDiskFileStorage`는 기존 `openUnder(resolveNamespaceRoot(namespace,false), key)`를 재사용해 `NOFOLLOW_LINKS`·경로 검증을 그대로 얻는다. 배너 공개 서비스가 이 오버로드를 쓴다. 시험: 네임스페이스 스트림 왕복·루트 접근 차단·최종 심볼릭 링크 거부(Linux 한정, 기존 링크 시험 방식)·정상 배너 GET·HEAD.
- v3 변경(2라운드 반영 — codex gpt-6.1-sol, 새 P1×1·P2×1 전부 수용):
  - R2-1(P1 구버전 롤백 중 회수한 BANNER 권한이 재배포 후 부활): 쟁점 9·리스크 변경 — 배너 도입 전 버전으로 롤백했다가 재배포할 때 기존 "롤백 후 재배포 정리 SQL"(`DELETE FROM member_permission; UPDATE member SET permission_version = permission_version + 1;`)을 반드시 수행하도록 `docs/deployment.md`에 배너 롤백 절차를 추가하고, `MemberPermissionMigrationTest`의 정리 SQL 시험에 BANNER 행 케이스(구버전이 못 지운 행이 정리 후 판정에 반영되지 않음)를 추가한다. 새 코드 없음(타당: 카탈로그에 없는 기능 행은 구버전 diff에서 제외돼 남는다).
  - R2-2(P2 수정 API의 기간 해제 계약): 쟁점 4·7·9 변경 — 수정은 **`PUT` 메타데이터 전체 교체**(`null`=해제), 최종 값에 `normalize`→`requireValid`. 시작·종료 각각/동시 해제·한쪽만 변경해 역전되는 경우 시험 추가. stale-form 한계는 수용.
- v2 변경(1라운드 반영 — codex gpt-6.1-sol, needs-attention P1×1·P2×4):
  - R1-1(P1 빈 테이블에서 전체 행 잠금이 직렬화를 보장하지 못함): 쟁점 2·5 변경 — 항상 존재하는 **단일 가드 행 `banner_lock`(id=1, V34 시드)** 을 생성·삭제·순서 저장의 첫 조회로 `FOR UPDATE` 잠근다(`content_image_usage`·회원 행 잠금과 같은 패턴). 상한·`ord` 직렬화가 행 수와 무관해지고 갭 잠금·격리 수준에 기대지 않는다. 빈 테이블 동시 생성·마지막 삭제↔생성·상한 직전 경합을 실제 MariaDB 시험으로 고정.
  - R1-2(P2 DATETIME 소수 초): 쟁점 2·7 변경 — 컬럼 `datetime(6)`, 서버가 입력을 **분 단위로 절단**(`truncatedTo(MINUTES)`)한 뒤 `start < end`를 검증(절단 후 같거나 역전이면 400). 경계 시험은 저장·flush·영속성 컨텍스트 초기화·재조회 후 판정.
  - R1-3(P2 페이지 자원 비용, 부분 수용): 쟁점 3·5·11·13 변경 — 배너 전용 이미지 예산 **2MB·한 변 2560px·총 4,000,000픽셀**, 상한 **10개**, 2번째 이후 `loading="lazy"`, 완료 기준에 최대 구성 페이지 로드 추가. 기각: 전역 전송량·동시 전송 제한(기존 모든 공개 엔드포인트와 같은 범위 밖 사안, 수용 위험으로 명시). 공유 IP의 429도 수용 위험.
  - R1-4(P2 최신 글 쿼리 인덱스, 부분 수용): 쟁점 12 변경 — 추측성 인덱스는 추가하지 않고 구현 단계에서 5만 행(비공개 게시판 편중 포함) `EXPLAIN`·응답 시간을 **측정**, 기준(두 쿼리 합 50ms) 초과 시에만 인덱스 마이그레이션 추가(이전 공개 검색 작업 방식).
  - R1-5(P2 기능 권한 회수 시험): 쟁점 16 변경 — BANNER 권한 MANAGER의 역할 왕복 비부활·역할 변경↔권한 PUT 경합(행·버전·캐시 일치) 시험 2종을 이번 범위에 포함.

## Context

지금 공개 진입점은 `/notices`·`/boards/{id}`뿐이고 `/`는 `anyRequest().denyAll()`에 걸려 비로그인은 관리자 로그인으로 302된다(`SecurityConfigTest.defaultDeny_anonymousRoot_redirectsToLogin`이 이 동작을 고정). 이 작업은 ① 비로그인 방문자가 보는 `/`(배너 + 최신 공지 + 새 글), ② 배너 CRUD·정렬·노출 기간·비노출 관리(ADMIN 또는 위임받은 MANAGER), ③ 배너 이미지 공개 경로 `/banners/{id}/image`를 만든다.

**두 가지가 이 작업의 성격을 정한다.**
- `SecurityConfig`에 `/`·`/banners/*/image` 공개 규칙을 넣는 **인가 정책 변경**이다(CLAUDE.md: 사전 협의 대상 — 4단계 승인에서 별도 고지).
- 배너는 **카탈로그의 첫 번째 `DELEGABLE`(기능 단위 위임) 기능**이다. 공지 흡수(①-2)로 0개가 됐던 기능 단위 위임 경로(`member_permission`·`PermissionCache` 기능 행·`PUT grants`·권한관리 화면의 기능 표·`featureReadGate`)가 처음 실제 기능을 만난다 — 이 경로의 시험 공백(`permission/CLAUDE.md` "시험 공백")을 이 작업에서 복구한다.

### 정찰 요약 (읽은 근거)

| 영역 | 사실 | 근거 |
|---|---|---|
| 인가 | `SecurityConfig`에 `DELEGABLE` 루프가 이미 있어(`featureReadGate`) 카탈로그에 기능을 추가하면 URL 게이트가 자동 생성된다. 공개 규칙은 GET·HEAD `permitAll` + 그 외 `denyAll` 3줄 패턴 | `SecurityConfig.java:93-122` |
| 카탈로그 | `AdminFeature` 한 줄 추가 + `gatePatterns`/`menuUrls`. `DELEGABLE`은 `READ` 지원 필수 | `AdminFeature.java:86` |
| 판정 | `AdminPermissionEvaluator`·`PermissionCache`·`MemberPermissionService`가 `DELEGABLE` 분기 코드를 갖고 있으나 호출되지 않던 상태 | grep `DELEGABLE` |
| 컨벤션 테스트 | `delegablePathsUseRequirePermissionOfSameFeature`·`requirePermissionIsConsistentWithCatalog`가 `DELEGABLE`을 순회 — 배너 핸들러에 `@RequirePermission`이 없으면 CI 실패 | `AdminEndpointAuthorizationConventionTest` |
| 이미지 | `ImageFileValidator.validate(bytes, contentType, Limits)` 공용(png·jpeg·gif, 애니메이션 거부). 파일 저장은 `FileStorage.store(content, name, namespace)` + `FileStorageTransactionSupport.deleteOnRollback/deleteAfterCommit` 네임스페이스 오버로드 존재. 네임스페이스 패턴 `[a-z0-9_-]+`, 예약어는 `profile`뿐 | `LocalDiskFileStorage` |
| 공개 이미지 | `PublicContentImageController`가 스트리밍 다운로드의 기준 구현(첫 청크 선읽기 → 미커밋이면 reset 후 재던짐, `nosniff`·`no-store`, 문자열 ID 직접 파싱 → 모든 실패 동일 404, HEAD 별도 핸들러) | 해당 클래스 |
| 링크 검증 | `SafeUrls.isSafeMenuUrl` = 같은 출처 경로 ∨ 외부 http(s)(호스트 필수·userinfo·공백·`\`·제어문자 거부). 확정 결정("http/https 절대 URL + 내부 상대 경로")과 정확히 같다 | `SafeUrls.java` |
| 공개 게시글 | 공개 불변식은 `PublicBoardService`·`PostRepositoryImpl.searchPublished`에 격리. 게시판 `publicYn ∧ ¬deleted`, 게시글 `useYn ∧ ¬deleted`. 공지 게시판 식별은 `BoardRepository.findIdByBoardKey("NOTICE")` | `publicweb/board/CLAUDE.md` |
| 레이트리밋 | `application.yml` 규칙 목록(`PathPatternRequestMatcher`)에 순서대로 평가. 더 엄격한 규칙을 앞에 둔다 | `application.yml:106-` |
| 메뉴 시드 | DDL과 분리된 멱등 DML(`WHERE NOT EXISTS`, `ord` = 최상위 MAX+1, 날짜 NULL) | V30 |
| 최신 마이그레이션 | V32 | `db/migration` |
| 시각 | KST `Clock` 주입(`LocalDateTime.now(clock)`) — `ClockUsageConventionTest`가 `now()` 직접 호출을 CI에서 잡는다 | CLAUDE.md |
| 공개 화면 | `templates/public/*`는 `@AdminPage` 미부착, `PublicWebExceptionAdvice`(`com.cms.publicweb` 한정)가 HTML 500 | `PublicWebExceptionAdvice` |

## 쟁점과 결정

### 1. `/` 공개 인가 규칙 (사용자 승인 필요 — 인가 정책 변경)

- 제안: `GET`·`HEAD` **정확 경로 `/`** 만 `permitAll`, `/` 의 그 외 메서드는 `denyAll`. `/banners/*/image`는 GET·HEAD `permitAll`, `/banners`·`/banners/**`의 그 외는 `denyAll`(`/content-images`와 같은 구조 — `/banners/*/image` 외 하위 경로는 라우트가 없으므로 기본 거부로도 막히지만 명시한다).
- 대안 (a) `/**` 와일드카드 공개: 기본 거부(M-08)를 무너뜨림 → 기각. (b) 현행(`/`를 로그인으로 302) 유지: 목표 자체 불성립.
- 영향: 비로그인 `GET /`가 302(로그인)에서 200(메인)으로 바뀐다. 로그인한 관리자가 `/`에 가도 같은 공개 메인이 나온다(관리자 전용 분기 없음 — 단순). 로그인 성공 후 이동(`/admin`)·로그아웃 후 이동(`/admin/login`)은 그대로.
- 계약 변경이 되는 기존 시험: `SecurityConfigTest.defaultDeny_anonymousRoot_redirectsToLogin` → "비로그인 `/` 200"으로 교체하고, `/` POST·PUT·DELETE 차단과 `/does-not-exist` 기본 거부(회귀)를 추가한다.

### 2. 배너 저장 모델 — 새 테이블 `banner`, 하드 삭제 (결정)

- 컬럼: `id`, `title`(200, 대체 텍스트 겸 관리용 이름, 필수), `link_url`(500, NULL 허용), `storage_key`(UNIQUE), `content_type`, `file_size`, `display_start`·`display_end`(DATETIME, NULL 허용 = 제한 없음), `use_yn`, `ord`(INT NOT NULL), `create_date`·`update_date`(날짜는 서비스가 `Clock`으로 채움).
- 소프트 삭제 대신 **하드 삭제**: 배너는 다른 곳에서 참조하지 않고(FK 없음), 복원 요구가 없으며, 소프트 삭제는 이미지 파일을 영구 보존하거나 별도 정리 로직이 필요하다. 삭제 = 행 삭제 + 커밋 후 파일 삭제(`deleteAfterCommit`). 감사 로그가 삭제 사실을 남긴다.
- 인덱스: `(use_yn, ord, id)` — 공개 조회(노출 대상, `ord, id` 정렬)용. 배너 수 상한이 작아 실제 효과는 작지만 비용이 없다.
- 날짜 컬럼(`display_start/end`·`create_date`·`update_date`)은 `datetime(6)`(V29와 같은 정밀도). 서버가 기간 입력을 분 단위로 절단해 저장한다(쟁점 7).
- 동시성 가드용 단일 행 테이블 `banner_lock(id TINYINT PK)`(쟁점 5).
- **스키마 변경 — 사전 고지 대상.** V33(DDL 2문: `banner`, `banner_lock`) + V34(DML: 가드 행 시드 + 메뉴 시드, 모두 멱등).

### 3. 이미지 저장 — 전용 네임스페이스 `banner`, 본문 이미지 인프라와 분리 (결정)

- (a) `content_image`(본문 이미지) 재사용: 출처 의미(BOARD 게시판 단위)·참조 테이블·카운터·수동 회수 절차가 얽히고, ⑧ 미디어 라이브러리가 "배너·팝업·본문 이미지"를 나중에 옮기는 구조라 지금 섞으면 이관이 두 번 필요 → 기각.
- (b) **네임스페이스 `banner`에 별도 저장**: `fileStorage.store(content, name, "banner")` — 프로필(`profile`)과 같은 방식으로 물리 격리. `banner` 이름은 `RESERVED_NAMESPACES`(루트 `store/load/delete`가 접근하지 못하게 막는 목록)에 **추가**해야 루트 API로 배너 파일에 닿지 못한다 — 현재 예약어는 `profile`뿐이므로 `Set.of("profile", "banner")`로 확장한다.
- 이미지 검증은 `ImageFileValidator`를 **배너 전용 예산**으로 사용(R1-3): png·jpeg·gif, **2MB**, 한 변 2560px·총 4,000,000픽셀, 헤더 검사만(`fullDecode=false`). 공개 메인이 활성 배너 전부를 매번 내려받고 브라우저가 디코딩하므로 본문 이미지(5MB·4096²)보다 작게 둔다(최악 10장 × 4M픽셀 RGBA ≈ 160MB). 애니메이션 GIF는 공용 검증기가 거부한다(수용). 예산이 부족하면 상수 한 곳만 조정.
- 롤백/삭제: 생성은 `deleteOnRollback(storage, key, "banner", ctx)`, 삭제는 `deleteAfterCommit(... "banner" ...)`.

### 4. 이미지 교체 — 이번 범위에서 제외 (결정, 승인 시 뒤집을 수 있음)

수정 API는 **`PUT /admin/api/banners/{id}` 메타데이터 전체 교체**(제목·링크·시작·종료·노출 여부 모두 요청에 포함, 링크·시작·종료의 `null` = 해제 — R2-2)이고 이미지는 바꾸지 않는다. 최종 값에 `normalize`→`requireValid`를 적용하므로 시작만/종료만 지우기, 둘 다 지우기, 한쪽만 바꿔 역전되는 경우가 모두 한 규칙으로 판정된다. PATCH(`null`=유지)를 쓰지 않는 이유: 기존 PATCH 관례로는 만료 배너의 종료 시각을 지워 재노출할 수 없고, 누락과 명시적 `null`을 구분하려면 별도 JSON 처리가 필요하다. 대가: 낡은 폼이 방금 다른 관리자가 바꾼 노출 여부를 덮을 수 있다(메뉴 관리와 같은 stale-form 한계 — 행 잠금은 동시 쓰기만 직렬화, 수용). 이미지를 바꾸려면 삭제 후 재등록(순서·기간은 다시 입력). 이유: 교체는 구 파일 삭제 시점·동시성·롤백 경로가 추가되고(생성/삭제와 다른 세 번째 파일 수명 경로), 로드맵 요구("등록·정렬·노출 기간·비노출")에 없다. 필요해지면 ⑧ 미디어 라이브러리(이미지 선택 구조)에서 자연스럽게 해결된다.

### 5. 개수 상한과 동시성 — 단일 가드 행 잠금 + 상한 10 (결정, v2 개정)

- **가드 행**: `banner_lock`(id=1, V34 시드)을 `FOR UPDATE`로 먼저 잠그는 것이 **생성·삭제·순서 저장의 첫 DB 조회**다. 배너 행이 하나도 없어도 잠글 행이 있어 갭 잠금·격리 수준에 기대지 않고 세 경로가 직렬화된다(R1-1). 가드 행 부재(시드 누락)는 `IllegalStateException`(`content_image_usage`와 같은 처리).
- 가드를 잠근 **뒤에** 비잠금 읽기(배너 id·개수·최대 `ord`)를 한다 — 스냅샷이 잠금 이후에 확정되도록(메뉴 영구삭제와 같은 규칙).
- 단건 수정(제목·링크·기간·노출)은 가드 없이 대상 행만 `findByIdForUpdate`. 단건 수정은 가드를 잡지 않으므로 잠금 순서는 항상 가드 → 행이고 순환 대기가 없다(순서 저장이 동시 단건 수정의 행 잠금을 잠깐 기다릴 뿐). 삭제는 가드 → 대상 행.
- 상한 `MAX_BANNERS = 10`(초과 시 409 `RESOURCE_CONFLICT`, 한국어 메시지). 이유: 공개 메인이 활성 배너 전부의 이미지를 매 조회마다 내려받는다(쟁점 11) — 총량 상한(R1-3). 값은 승인 시 조정 가능.
- 새 배너의 `ord` = 현재 MAX+1(비어 있으면 0).

### 6. 정렬 — ▲▼ 버튼 + [순서 저장]이 `PUT /admin/api/banners/order` 한 번 (결정)

- API: `PUT /admin/api/banners/order` 본문 `{ids:[3,1,2]}` — 화면이 만든 **전체 배너의 최종 순서**. 서버는 가드 행 잠금 후 요청 id 집합이 현재 집합과 **다르면 409**(낡은 초안 — 다른 관리자가 추가·삭제함), 같으면 배열 순서대로 `ord=0..n-1`을 매기되 값이 같은 행은 쓰지 않는다(`update_date` 보존). 중복 id·null 원소는 400. 상한 10이라 요청 크기는 `@Size(max=10)`로 DTO에서 차단.
- 화면은 드래그 대신 **▲▼ 버튼**: 메뉴 드래그는 jstree 의존이고 배너는 최대 10개의 평면 목록이라 불필요하며, 버튼은 키보드·접근성이 기본으로 되고 Playwright 검증도 단순하다. 초안이 있는 동안 [순서 저장]·[취소]가 활성화되고 다른 저장 동작은 잠근다(메뉴 화면의 초안 규칙을 축소해 따름). 동시 두 관리자가 같은 집합에 서로 다른 순서를 저장하면 후자가 이긴다(last-write-wins, 순서만의 충돌이라 수용).

### 7. 노출 기간 판정 — 공용 `DisplayPeriod`, 시작 포함·종료 **미포함** (결정)

- 위치 `com.cms.common.display.DisplayPeriod`(정적 유틸): `isActive(start, end, now)` = `(start == null || !now.isBefore(start)) && (end == null || now.isBefore(end))`, `normalize(t)` = `t.truncatedTo(MINUTES)`(null 통과), `requireValid(start, end)` = 정규화된 둘이 모두 있으면 `start < end` 아니면 400(절단 후 같아지는 경우 포함). 배너가 먼저 쓰고 ⑦ 팝업·④ 예약 게시가 재사용한다. QueryDSL 조건은 각 도메인 Repository가 같은 의미로 만든다(`start is null or start <= now`, `end is null or end > now`) — Q클래스에 의존하므로 공용 유틸에 두지 않는다. **두 표현이 같은 의미임을 배너 경계 시험이 DB로 고정**한다.
- 종료 미포함 이유: 구간 `[start, end)`가 인접 배너를 겹침 없이 이을 수 있고, 관리 화면이 "종료 시각 전까지 노출"로 한 문장 안내한다. 시각 단위는 분(날짜(`date`) 입력 + 시·분 `<select>` 두 개(`datetime-local`·`time` 팝업은 값을 골라도 바깥을 클릭할 때까지 열려 있어 분리·select로 대체, 2026-10-10 사용자 확인), 서버가 입력을 `truncatedTo(MINUTES)`로 분 단위 절단한 뒤 검증·저장 — 직접 API 호출의 소수 초·초 단위가 저장 후 start==end를 만들거나 시작을 앞당기는 일이 없다, R1-2).
- 판정 시각은 서비스가 1회 산출한 `LocalDateTime.now(clock)` 하나(KST). 판정 시각을 리포지토리 메서드 인자로 받는다(내부에서 `now()` 호출 금지 — `ClockUsageConventionTest`).

### 8. 링크 검증 — `SafeUrls.isSafeMenuUrl` 재사용, 저장 시 + 출력 시 이중 (결정)

- 저장: 전용 제약 `@SafeLinkUrl`(`common/web/validation`, `SafeUrls.isSafeMenuUrl`에 위임, null·공백 통과 — 빈 값은 서비스가 `null`로 저장)을 요청 DTO에 둔다. `@SafeMenuUrl`을 직접 쓰지 않는 이유: `admin.menu` 패키지 DTO에 `admin.banner`가 의존하게 되고, 이름이 메뉴 전용이라 ⑦ 팝업이 쓰기에 어색하다(중복은 위임 한 줄뿐). 위반 400.
- 출력: 공개 템플릿이 `href`를 그리기 전에 서비스 DTO 생성 시점에 **한 번 더** `SafeUrls.isSafeMenuUrl`로 거르고 통과하지 못하면 링크 없는 배너로 그린다(DB 직접 수정·과거 값 방어 — 메뉴 `SidebarMenuResponse.of`와 같은 방어). 외부 http(s) 링크는 `target="_blank" rel="noopener noreferrer"`, 내부 경로는 같은 탭.
- 길이 500 초과 400(`@Size`).

### 9. 권한 — 카탈로그 `BANNER`(`DELEGABLE`, READ·CREATE·UPDATE·DELETE) (결정)

- `AdminFeature.BANNER(DELEGABLE, "배너", EnumSet.of(READ, CREATE, UPDATE, DELETE), menuUrls=["/admin/banner/manage"], gatePatterns=["/admin/banner/manage", "/admin/api/banners", "/admin/api/banners/**"])`.
- 핸들러 분류: 목록·상세·이미지 보기 = READ, 생성 = CREATE, 수정(`PUT /{id}` 메타데이터 전체 교체)·**순서 저장** = UPDATE, 삭제 = DELETE. 모든 `/admin/api/banners/**` 핸들러에 `@RequirePermission(feature=BANNER, action=…)`. 화면 `GET /admin/banner/manage`는 `@AdminPage`, 메서드 보안 없음(URL 게이트가 HTML 403).
- **`SecurityConfig` 코드 변경 없음**(DELEGABLE 루프가 게이트를 만든다) — 공개 규칙만 추가.
- 순서 저장을 UPDATE로 분류하는 이유: 새 동작 종류를 만들지 않는다(이미 있는 배너의 속성 변경이므로 UPDATE).
- 화면 버튼 숨김은 기존 `myPermissions`(`"BANNER:CREATE"` 키) 사용 — 서버 판정이 최종.
- 감사: `BANNER_CREATE`·`BANNER_UPDATE`·`BANNER_DELETE`·`BANNER_ORDER`(`targetType=BANNER`, `targetId` = 배너 ID, 순서 저장은 `targetId` 없음). 제목은 사용자 입력이라 `targetLabel` 없음. `AdminActionTypes.ALL`·`templates/admin/log/manage.html` 라벨 동기화 테스트 대상.

### 10. 관리자 이미지 보기 — 별도 `GET /admin/api/banners/{id}/image` (결정)

공개 경로는 비노출·기간 외 배너를 404로 막으므로 관리 화면 미리보기에 쓸 수 없다. 관리자 이미지 엔드포인트(`@RequirePermission(BANNER, READ)`, 게이트 `/admin/api/banners/**` 안)가 DB 행의 `storageKey`로 **노출 여부와 무관하게** 파일을 읽어 준다. 파일 크기가 5MB 이하라 `FileStorage.load(key, "banner")`(byte[])를 쓴다(권한이 있는 소수의 관리자 요청 — 공개 스트리밍의 힙 우려 없음, 프로필·첨부 admin 다운로드와 같은 방식). `Content-Type`은 저장된 값, `nosniff`·`no-store`, 인라인.

### 11. 공개 배너 이미지 `GET·HEAD /banners/{id}/image` — 도메인 서비스가 노출 재검증, 스트리밍 (결정)

- `PublicBannerService.findDisplayable(id, now)`: `useYn=true ∧ 기간 안`일 때만 반환(메서드/쿼리가 조건을 고정). 없음·비노출·기간 외·비숫자·범위 밖 ID는 **모두 같은 404**. 조회(트랜잭션)와 파일 열기(트랜잭션 없음)를 분리 — `open-in-view: false` 계약 유지. 파일은 신설 `fileStorage.open(storageKey, "banner")`로 스트림으로 연다(R3-1 — 기존 `open(key)`는 루트만 보고 `load(key, ns)` 래핑은 힙을 쓴다).
- 스트리밍: 새 컨트롤러가 `PublicContentImageController`의 로직을 **세 번째로 복제하지 않도록** `com.cms.publicweb.support.PublicImageStreamer`(정적 헬퍼: 첫 청크 선읽기·헤더·reset 처리)를 만들어 배너 컨트롤러가 쓴다. 기존 두 컨트롤러는 건드리지 않는다(무관한 리팩터링 금지 — 후속 정리 후보로 기록). ⑦ 팝업이 같은 헬퍼를 쓴다.
- 헤더: 저장된 `image/*` 인라인, `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`(숨긴 배너가 클라이언트 캐시에 남지 않게 — 본문 이미지와 같은 정책). 대가: 메인 조회마다 이미지를 다시 받는다 → 쟁점 5의 개수 상한과 쟁점 13의 레이트리밋으로 총량을 제한(수용 위험으로 명시).
- 레이트리밋: `banner-image` `/banners/*/image` GET·HEAD **240/60초**(메인 1회에 최대 10장이라 메인 로드 24회분. 공유 IP에서 한도를 넘으면 이미지가 429로 깨질 수 있음 — 수용 위험), `public-home` `/` GET·HEAD **120/60초**. 두 규칙은 겹치지 않아 순서 의존 없음(`/` 정확 경로).

### 12. 공개 메인의 데이터 — 배너 + 공지 5건 + 새 글 5건 (결정)

- **배너**: `useYn ∧ 기간 안`, `ord asc, id asc`. 이미지 URL은 `/banners/{id}/image`, 대체 텍스트 = 제목.
- **공지**: 공지 게시판(`board_key='NOTICE'`) 공개 게시글 최신 5건(`createDate desc, id desc`). 게시판이 없거나 비공개/삭제면 섹션 생략(메인이 500/404가 되지 않는다 — `/notices`와 달리 메인의 부속 섹션이므로 fail-soft). 링크는 `/notices/{id}`(기존 공개 URL 유지).
- **새 글**: 공지 게시판을 **제외한** 모든 공개 게시판의 공개 게시글 최신 5건, 게시판 이름 병기, 링크 `/boards/{boardId}/posts/{postId}`. 공지와 중복 노출을 피하려고 공지 게시판을 제외한다.
- 쿼리: 기존 `PostRepositoryImpl`에 `findLatestPublished(Long boardId, Long excludeBoardId, int limit)`를 추가 — 게시판 조인(`board.publicYn ∧ ¬board.deleted`) + 게시글 `useYn ∧ ¬deleted`를 **한 조건 객체**로 고정하고 DTO 프로젝션(본문 `content`는 읽지 않는다 — MEDIUMTEXT 비용 회피). 공개 불변식 격리를 위해 호출은 `com.cms.publicweb.home` 서비스 한 곳에서만 한다. ④ 예약 게시가 이 메서드에도 기간 조건을 넣어야 함을 ④ 항목에 이미 있는 "공개 메인 최신 글 조회"가 가리킨다(이 계획의 후속 주의로 남긴다).
- 쿼리 비용(R1-4): V29 인덱스 `(board_id, deleted, use_yn, create_date)`는 단일 게시판 목록용이라 전체 게시판 `create_date desc` 정렬을 직접 돕지 않는다. 구현 단계에서 5만 행(공개/비공개·삭제 편중 포함)으로 `EXPLAIN`과 시간을 재고, 기준(공지+새 글 두 쿼리 합 50ms)을 넘을 때만 인덱스 마이그레이션(V35)을 추가한다.
- 2번째 이후 배너 `<img>`는 `loading="lazy"`(R1-3).
- 본문 HTML은 메인에 출력하지 않는다(제목·날짜만, `th:text`) — 새 `utext` 지점이 생기지 않는다.

### 13. 공개 화면 구조 (결정)

- `GET /`(`HomeController`, `com.cms.publicweb.home`, `@AdminPage` 미부착) → `templates/public/home.html` + `static/css/public/home.css`. 공개 화면의 기존 관례(독립 CSS 파일, 레이아웃 프래그먼트 없음)를 따른다. 공통 헤더/푸터 프래그먼트는 ⑤ 정적 페이지(푸터 링크)에서 만든다 — 지금은 만들지 않는다.
- 배너 표현: 순서대로 세로 나열(슬라이더·자동 회전 없음 — JS 불필요, 과설계 회피). 링크가 있으면 이미지를 `<a>`로 감싼다.
- 예외: 컨트롤러·서비스 실행 중 예외는 기존 `PublicWebExceptionAdvice`(`com.cms.publicweb` 한정)가 HTML 500으로 처리한다.

### 14. 관리 화면 (결정)

- `GET /admin/banner/manage` + `templates/admin/banner/manage.html`(SB Admin 2, 기존 `admin/board/manage.html` 구조 차용). 목록(미리보기 썸네일·제목·기간·노출 상태 배지 "노출 중/예약됨/만료/비노출"·▲▼), 등록 폼(`multipart`: 제목·링크·시작·종료·노출 여부·이미지), 수정 폼(제목·링크·기간·노출), 삭제(확인 모달), 순서 저장. 버튼은 `myPermissions`로 숨김. 이름·제목은 `textContent`로만 삽입.
- 노출 상태 배지는 **서버가 계산**해 응답에 넣는다(`status`: `ACTIVE`/`SCHEDULED`/`EXPIRED`/`HIDDEN`, 판정 시각은 서버 `Clock`) — 브라우저 시계에 의존하지 않는다.
- 등록 API는 `multipart/form-data`(`POST /admin/api/banners`: `title`·`linkUrl`·`displayStart`·`displayEnd`·`useYn`·`image`). CSRF 헤더 필수.

### 15. 메뉴 시드 (결정)

V34: `'배너 관리'`, `'/admin/banner/manage'`, 아이콘 `fas fa-fw fa-images`, 최상위 맨 끝(V30 규칙: `LEAST(COALESCE(MAX(ord),-1)+1, 2147483647)`, 날짜 NULL, `WHERE NOT EXISTS`). 노출은 카탈로그가 정한다(ADMIN 항상, MANAGER는 BANNER READ를 받았을 때).

### 16. 시험 공백 복구 범위 (결정 — 승인 시 범위 조정 가능)

배너가 첫 `DELEGABLE` 픽스처이므로 `permission/CLAUDE.md` "시험 공백" 목록 중 **기능 단위 위임 경로의 핵심**을 배너로 복구한다.
1. `AdminPermissionEvaluatorTest`: 기능 행 + READ 의존 진리표(`BANNER`), 교차 회원 격리, `grantedActionKeys`.
2. `PermissionCacheTest`: 기능 행 로드·알 수 없는 기능 행 무시.
3. `MemberPermissionServiceTest`: `replace` diff·전체 회수·변경 없음·변형 행 충돌(기능 행 판).
4. `MemberPermissionApiIntegrationTest`: 기능 행 PUT(400 위임 불가·READ 없는 쓰기·409 버전)·실제 로그인 세션 즉시 반영.
5. `AdminPermissionMatrixIntegrationTest`: 배너 핸들러 × MANAGER 권한 조합(없음·READ만·READ+CREATE·READ+UPDATE·READ+DELETE·READ 없는 쓰기) 매트릭스 — 허용은 성공 상태와 저장 결과, 거부는 403 + 변경 없음.
6. **(R1-5) 역할 변경 시험 2종은 범위에 포함**: `MemberRoleChangePermissionIntegrationTest`에 BANNER 권한 MANAGER의 `MANAGER→ADMIN→MANAGER` 왕복 후 권한 비부활(행 삭제·버전 +1·캐시 폐기), `MemberPermissionConcurrencyIntegrationTest`에 역할 변경 ↔ BANNER 권한 PUT 경합(실제 락 대기, 최종 행·버전·캐시 일치). `AdminMemberService`가 기능 권한과 게시판 권한을 서로 다른 호출로 지우므로 게시판 시험으로는 기능 행 삭제 회귀를 잡을 수 없다. 두 파일의 나머지 케이스 복구는 후속.
8. **(R2-1) 롤백 정리 시험**: `MemberPermissionMigrationTest` 정리 SQL 시험에 BANNER 행 케이스. **(R2-2)** 수정 PUT 시험: 시작/종료 각각·동시 해제, 한쪽만 변경해 역전(400), 소수 초 절단 후 동일(400).
7. **(R1-1) 가드 행 동시성 시험**(실제 MariaDB): 빈 테이블 동시 생성(`ord` 중복 없음), 마지막 배너 삭제 ↔ 생성, 상한 직전(9개) 동시 생성 2건 중 정확히 하나만 성공.

## 영향 범위 (파일)

신규
- `src/main/java/com/cms/admin/banner/`: `domain/Banner`, `repository/BannerRepository`(전체 행 잠금·단건 잠금·공개 조회), `service/BannerService`·`BannerImageService`(필요 시 한 클래스), `controller/BannerController`(API)·`BannerPageController`(`@AdminPage`), `dto/request`(Create·Update·Order)·`dto/response`, `CLAUDE.md`
- `src/main/java/com/cms/common/display/DisplayPeriod`, `src/main/java/com/cms/common/web/validation/SafeLinkUrl`·`SafeLinkUrlValidator`
- `src/main/java/com/cms/publicweb/home/`(`HomeController`, `PublicHomeService`, DTO), `src/main/java/com/cms/publicweb/banner/`(이미지 컨트롤러·서비스), `src/main/java/com/cms/publicweb/support/PublicImageStreamer`
- `V33__create_banner.sql`(`banner`·`banner_lock`), `V34__seed_banner_menu.sql`(가드 행 + 메뉴 시드)
- `templates/public/home.html`, `templates/admin/banner/manage.html`, `static/css/public/home.css`
- 시험 다수(아래)

수정
- `config/SecurityConfig`(공개 규칙 3묶음) + `config/CLAUDE.md` 접근 표, `application.yml`(레이트리밋 2규칙), `common/storage/FileStorage`(`open(key, namespace)` default 오버로드)·`LocalDiskFileStorage`(예약 네임스페이스에 `banner` + 오버로드 구현), `admin/permission/AdminFeature`(`BANNER`), `admin/log/constant/AdminActionTypes`·`templates/admin/log/manage.html` 라벨, `admin/board/repository/PostRepositoryCustom·Impl`(`findLatestPublished`), `admin/permission/CLAUDE.md`(시험 공백·DELEGABLE 서술), 루트 `CLAUDE.md` 지침 지도(`com.cms.admin.banner`·`com.cms.publicweb.home`), `docs/migration-guide.md`(V33~V34), `docs/deployment.md`(수동 회수 ⑤에서 `banner/` 제외 + 새 ⑤-3 배너 파일 대조, 배너 롤백 절차 — R4-1·R2-1)
- 기존 시험 교체: `SecurityConfigTest` `/` 계약

## 작업 단계 (구현 순서)

1. **기반**: `DisplayPeriod`·`SafeLinkUrl`·예약 네임스페이스·**`FileStorage.open(key, namespace)`**(R3-1) + 단위 시험. V33·V34 + 마이그레이션 시험.
2. **도메인·저장소**: `Banner`·`BannerRepository`·`findLatestPublished` + DataJpa 시험(경계·정렬·공개 조건).
3. **서비스**: 생성(검증·잠금·상한·파일·롤백 정리)·수정·삭제(커밋 후 파일 삭제)·순서 저장(409·0 쓰기) + 단위·동시성 시험.
4. **관리 API·화면·권한**: `AdminFeature.BANNER`, 컨트롤러, 화면, 감사 상수, 컨벤션 테스트 통과 확인.
5. **공개 측**: `PublicImageStreamer`, 배너 이미지 컨트롤러·서비스, `HomeController`·서비스·템플릿·CSS.
6. **인가·레이트리밋**: `SecurityConfig`·`application.yml` + `SecurityConfigTest` 갱신.
7. **시험 공백 복구**(쟁점 16) → 전체 `./gradlew test` → dev Docker 실기(Playwright) → 문서 갱신.

## 완료 기준 (로드맵 반영)

- [ ] `./gradlew test` 통과, CI 통과
- [ ] 노출 기간 경계(시작 직전·시작 시각·종료 직전·종료 시각)를 고정 `Clock`으로 검증, 비노출·기간 외 배너의 `/banners/{id}/image`가 404
- [ ] 링크 검증: 허용 외 스킴 400, 내부 상대 경로·http/https 허용
- [ ] MANAGER 위임 전 403·위임 후 허용(보안 슬라이스 + 매트릭스 통합)
- [ ] `/` 비로그인 GET 200, 비-GET/HEAD 거부, 기존 `/admin/**` 규칙 회귀 없음(`SecurityConfigTest`)
- [ ] Playwright: 배너 등록 → `/`에 노출 → 기간 만료(또는 비노출) 후 사라짐
- [ ] 최대 구성(배너 10개·2MB 이미지) 메인 로드 완료·`lazy` 적용, 소수 초 입력이 분 단위로 절단돼 flush·clear·재조회 후 경계 판정이 일치
- [ ] 수동 회수(⑤·⑤-3) 실행 확인: 배너 행이 있는 파일(비노출·만료 포함)은 보존, 행 없는 `banner/` 파일만 삭제
- [ ] 최신 글 쿼리 5만 행 `EXPLAIN`·시간 측정 기록(기준 50ms)

## 리스크

- **공개 인가 정책 변경(`/`)**: `/` 정확 경로 + 메서드 한정으로 최소화. 기본 거부 회귀 시험 유지.
- **`no-store` 이미지 대역폭**: 메인 조회마다 활성 배너 전부 재다운로드 — 상한 10·2MB·레이트리밋 240/60초로 총량 제한. 전역 전송량 제한은 없고(기존 공개 엔드포인트와 동일) 공유 IP에서 이미지 429 가능(수용). 필요하면 ETag 재검증으로 개선(후속).
- **첫 DELEGABLE 사용**: 한 번도 실행되지 않던 기능 단위 위임 코드가 실제로 돈다. 판정·캐시·권한관리 화면 기능 표의 숨은 결함이 드러날 수 있다 → 쟁점 16의 시험으로 선제 검증.
- **애니메이션 GIF 배너 불가**: 공용 검증기가 거부(수용).
- **이미지 교체 없음**: 삭제 후 재등록(쟁점 4).
- **롤백 후 권한 부활(R2-1)**: 배너 도입 전 앱으로 롤백하면 구 앱의 권한관리가 `BANNER` 행을 diff에서 제외해 전체 회수가 그 행을 남긴다. 재배포 전 정리 SQL(`member_permission` 전체 삭제 + 전원 버전 증가)이 필수이며 `docs/deployment.md`에 배너 롤백 절차로 추가한다(기존 선례와 같은 절차).
- **스키마 변경 되돌리기**: V33은 테이블 생성뿐이라 구 앱은 그대로 기동한다(미사용 테이블). 롤백 시 `banner` 파일 디렉터리와 행은 남는다 — roll-forward 우선.
- **4개 공개 이미지 스트리밍 복제 부채**: `PublicImageStreamer`로 신규분은 한 곳. 기존 두 컨트롤러 통합은 후속.

## 후속(이 계획 밖)

- ④ 예약 게시: `findLatestPublished`에도 기간 조건 필요.
- ⑦ 팝업: `DisplayPeriod`·`SafeLinkUrl`·`PublicImageStreamer` 재사용.
- ⑧ 미디어 라이브러리: `banner` 네임스페이스 이미지를 라이브러리로 이관.
- `PublicContentImageController`·`PublicNoticeController` 첨부 스트리밍을 `PublicImageStreamer`로 통합.

---

## 구현·검증 결과 (2026-10-10)

> 상태: **완료 — 머지됨(2026-10-10, PR #115 `e644b71`)**(브랜치 `feat/public-home-banner`).

### Context

로드맵 Top 8 ②. 비로그인 방문자가 `/`에서 노출 기간 안의 배너·공지 5건·새 글 5건을 보고, ADMIN 또는 배너 권한을 위임받은 MANAGER가 배너를 등록·수정·삭제·정렬한다. 카탈로그의 **첫 기능 단위 위임(`DELEGABLE`) 기능**이라 공지 흡수 때 생긴 `member_permission` 경로의 시험 공백도 이 작업에서 복구했다. 인가 정책 변경(`/`·`/banners/*/image` 공개)·스키마 V33~V34·`FileStorage` 인터페이스 확장은 사용자 승인 후 구현했다.

### 핵심 확정 사항 (계획 v5 그대로 + 구현 중 달라진 점)

계획 v5의 쟁점 1~16이 그대로 구현됐다. 구현 중 달라진 점은 아래 둘뿐이다.
- **`V33.banner_lock.id`를 `bigint(20)`로**(계획 초안은 `tinyint`): 엔티티 `Long id`와 `ddl-auto: validate`의 타입 검증이 어긋나 컨텍스트가 뜨지 않았다(시험이 즉시 잡음). 머지 전이라 V33을 직접 수정했다.
- **슬라이스 시험 보조**: `SecurityConfigTest`는 `HomeStubController`·`PublicBannerStubController`·`AdminBannerStubController`를 추가했다(컨트롤러 스텁 방식 기존 관례).

### 구현 파일

신규
- `admin/banner/`: `domain/{Banner,BannerLock}`, `repository/{BannerRepository,BannerLockRepository}`, `service/BannerService`, `controller/{BannerController,BannerPageController}`, `dto/request/{BannerCreateRequest,BannerUpdateRequest,BannerOrderRequest}`, `dto/response/{BannerResponse,BannerImageDownload}`, `CLAUDE.md`
- `common/display/DisplayPeriod`, `common/web/validation/{SafeLinkUrl,SafeLinkUrlValidator}`
- `publicweb/banner/{PublicBannerService,PublicBannerImageController}` + `dto/{PublicBanner,PublicBannerImageRef}`, `publicweb/home/{HomeController,PublicHomeService}` + `dto/{PublicHomePost,PublicHomeView}`, `publicweb/support/PublicImageStreamer`, `publicweb/home/CLAUDE.md`
- `admin/board/repository/PublishedPostRow`, `V33__create_banner.sql`, `V34__seed_banner_lock_and_menu.sql`
- `templates/public/home.html`, `static/css/public/home.css`, `templates/admin/banner/manage.html`

수정
- `AdminFeature`(`BANNER`), `AdminActionTypes`(+`BANNER_*` 4종)·`templates/admin/log/manage.html` 라벨, `SecurityConfig`(`/`·`/banners` 규칙 3묶음), `application.yml`(레이트리밋 2규칙), `FileStorage`(`open(key, namespace)`)·`LocalDiskFileStorage`(구현 + 예약 네임스페이스 `banner`), `PostRepositoryCustom/Impl`(`findLatestPublished`)
- 문서: 루트 `CLAUDE.md`(지침 지도·보안 규칙·FileStorage), `config/CLAUDE.md`(접근 표), `permission/CLAUDE.md`(`DELEGABLE` 행·시험 복구), `docs/deployment.md`(공개 메인·배너 절, 수동 회수 ⑤ `banner/` 제외 + ⑤-3, 롤백 권한 정리), `docs/migration-guide.md`(V33~V34), `plan/README.md`

### 검증 결과

- **전체 `./gradlew cleanTest test`**: 1743건 중 실패 0·오류 0·건너뜀 12(5분 27초, BUILD SUCCESSFUL, 2026-10-10 로컬 Windows·Docker Desktop). **`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로도 동일**(4분 54초, 1743건·실패 0·건너뜀 12 — CI 시간대. 단 JVM 기본 시간대가 실제 UTC였는지는 별도로 출력해 확인하지 않았다). **건너뜀 12건은 전부 `LocalDiskFileStorageTest`의 심볼릭 링크 시험**(Windows는 링크 생성 불가)이며 새로 추가한 `open(key, ns)` 링크 거부 시험을 포함한다 → Linux(`eclipse-temurin:21-jdk` 컨테이너)에서 `LocalDiskFileStorageTest`를 실행해 **36건·건너뜀 0·실패 0** 확인. 첫 실행에서 실패 1건이 있었다 — `AdminSidebarAdviceSnapshotTest`의 ADMIN `myPermissions` 기대가 BANNER 도입으로 바뀐 것, 기대값 수정 뒤 통과. 중간에 메모리 부족으로 실행이 중단된 적과 Docker 엔진이 꺼진 채 돌려 DB 시험 119건이 `initializationError`로 실패한 적이 있으나 환경 문제였고, Docker를 다시 켠 뒤 최종 실행이 위 결과다.
- **변이 실험(시험이 실제로 회귀를 잡는지)**: ① `BannerService.createBanner`의 `lockGuard()` 제거 → `BannerConcurrencyIntegrationTest` 2건 실패(빈 테이블 12개 동시 등록, 상한 직전 2개 동시 등록). ② 삭제 핸들러의 `@RequirePermission(DELETE)`를 UPDATE로 → `BannerPermissionMatrixIntegrationTest` 실패. ③ `AdminMemberService`의 `memberPermissionRepository.deleteByMemberId` 제거 → 역할 변경 시험 4건 실패. 세 변이 모두 원복했고 원복을 확인했다.
- **리뷰 지적 대응 시험**: R1-1(가드 행 — 빈 테이블 동시 등록·락 실증), R1-2(소수 초 절단 + 저장·flush·clear·재조회 후 경계), R1-3(이미지 예산 400·상한 409·`lazy`), R1-4(5만 행 실측 — 아래), R1-5(BANNER 역할 왕복·역할 변경↔PUT 경합), R2-1(`MemberPermissionMigrationTest`의 BANNER 정리 SQL 케이스 + `BannerMigrationTest`의 V32 이하 앱 롤백 기동), R2-2(`PUT` 전체 교체 — 시작·종료 각각/동시 해제, 역전 400), R3-1(`LocalDiskFileStorageTest`의 네임스페이스 `open` 왕복·루트 접근 차단·링크 거부), R4-1(수동 회수 ⑤ 제외 — 문서 + 아래 실기 한계).
- **dev Docker 실기(Playwright + curl, 새 이미지를 빈 일회용 스키마 `cms_verify`(V34까지 자동 적용)에서 구동, 검증 뒤 컨테이너·볼륨·스키마 삭제)**:
  - 공개: 비로그인 `GET·HEAD /` 200, `POST /` 403(CSRF 없음), `/index.html`·`/home`·`/banners`·`/banners/1`은 302(기본 거부), `/banners/1/image`(없음)·`/banners/abc/image` 404. 배너 3개 등록 후 `/`에 노출 중인 2개만 저장한 순서(▲▼ → [순서 저장])대로 표시, 링크 속성(외부 `target="_blank" rel="noopener noreferrer"`, 내부 같은 탭, 첫 배너 `eager`·그 다음 `lazy`), 이미지 응답 `image/png`·`nosniff`·`no-store`·바이트 일치(4489), 예약 배너 이미지 404, 비노출 전환 즉시 404(관리자 미리보기는 200).
  - 관리 화면: 등록(파일 선택·기간 입력)·상태 배지(노출 중·예약됨·비노출)·`<b>x</b>` 제목이 글자로 보임(이스케이프)·순서 초안 중 저장 버튼 잠금·`javascript:` 링크 저장 시 서버 400 메시지 표시·수정(링크 비움·노출 끔)·삭제(행 삭제 + **디스크 파일 삭제 확인**, 재삭제 404)·감사 로그(`BANNER_*`, 순서는 `targetId` 없음)·상한(10개 후 11번째 409)·2MB 초과/이미지 아님 400.
  - 권한: 권한관리 화면에 **"배너" 기능 행(조회·생성·수정·삭제)이 처음으로 그려지고** 조회·수정 저장 → `member_permission` 2행·`permission_version` +1·감사 라벨 `v0→v1: 추가 2·회수 0 | +배너.조회, +배너.수정`. 그 MANAGER로 로그인하면 사이드바에 "배너 관리"만, 목록·수정·순서 저장 200, 등록·삭제 **403**, `/admin/board/manage`·권한관리 API 403, 화면의 등록 버튼 비활성·▲▼ 활성.
  - 레이트리밋: 병렬 300회 → `/` 133건 200·167건 429, 이미지 병렬 500회 → 283건 200·217건 429(순차 130·250회는 refill 때문에 429가 나지 않아 병렬 버스트로 확인).
  - DB 직접 수정으로 넣은 `javascript:alert(1)` 링크는 `/`에서 링크 없는 배너로 그려지고 응답에 `javascript:`가 없다.
  - **최신 글 쿼리 비용(R1-4)**: 게시글 5만 행(공개 3·비공개 2·공지 게시판, 미노출·삭제 혼합) — 새 글 쿼리 35.5ms(공개 게시판 전체 임시 테이블 정렬, `EXPLAIN`: `Using temporary; Using filesort`), 공지 쿼리 0.3ms(`idx_post_board_deleted_use_create`) → 합 ≈ 36ms로 계획 기준 50ms 안. 인덱스는 추가하지 않았다. 공개 행이 10만 단위를 넘으면 재평가.
- **실기 한계와 사후 보완(완료를 과장하지 않는다)**:
  - ① Playwright 자동화 세션이 MANAGER 화면 확인 중간부터 마우스 클릭이 페이지에 전달되지 않았다(파일 선택창 가로채기 이벤트가 쌓인 도구 문제로 보이며 앱 문제는 아님 — JS `.click()`은 정상). 그래서 "행 클릭 → 수정 폼"·삭제 확인창(`confirm()`)·MANAGER 화면은 **사용자가 일반 브라우저에서 5번 체크리스트 전부 직접 확인**했다(2026-10-10: 등록·행 클릭 수정 폼·▲▼ 초안·[순서 저장]/[취소]·삭제 확인창·`mgr1`의 수정 폼 열림·[삭제] 숨김·[등록] 비활성).
  - ② **429 화면**은 사용자가 브라우저에서 직접 확인했고(상태 429, `Retry-After: 1`, "요청이 너무 많습니다", 로그인으로 튀지 않음) 같은 응답을 curl로도 확인했다. 공용 `error/429.html`의 "홈으로 돌아가기" 링크가 `/notices`를 가리킨다 — 이번 범위 밖이라 바꾸지 않았다(이제 `/`가 공개 메인이므로 필요하면 후속으로 `/`로 변경).
  - ③ **수동 회수 ⑤·⑤-3 실행 검증(2026-10-10)**: 문서에서 `ready()`·⑤·⑤-3 블록을 **그대로 추출**해(볼륨·컨테이너 이름만 치환) 일회용 스키마·볼륨(앱 정지 상태)에서 실행했다. 사전 상태 — 배너 3개(노출 중·`use_yn=0`·종료가 지난 만료) 파일 + 행 없는 `banner/…/banner-orphan.png` + 루트의 행 없는 `…/root-orphan.bin` + `profile/x/profile-keep.png`. 결과: `ready` 통과, ⑤ 후보는 **루트 고아 1건만**(`banner/`·`profile/` 파일은 후보에 없음), ⑤-3 후보는 **배너 고아 1건만**, 실행 후 디스크에 **세 배너 파일(비노출·만료 포함)과 `profile` 파일이 남고 고아 둘만 삭제**됐다. `banner/` 제외가 없던 이전 판 ⑤의 오삭제는 재현하지 않았다(문서가 지적한 조건을 코드 대조로만 확인).
  - ④ 날짜·시간 입력: 사용자가 `datetime-local`(및 분리한 `time` 입력)의 달력/시간 팝업이 값을 골라도 닫히지 않는다고 보고 → **날짜 입력 + 시·분 `<select>` 두 개**로 변경(2026-10-10, 사용자 확인: 드롭다운은 잘 닫힘). 변경 뒤 Playwright로 저장 의미(시·분만 고르면 오류, 날짜만 = 00:00, 시 또는 분만 = 나머지 00, 전부 비움 = 해제, 등록 경로)를 확인했다. 네이티브 팝업 자체는 자동화로 볼 수 없어 사용자 확인에 의존했다.
  - ⑤ 스크린샷은 저장소 밖(임시 폴더)에 두었다.

### 이슈

- **dev 스택의 `cms_db_data_dev` 볼륨 DB는 Flyway V32 체크섬 불일치**(applied `-1109782241` vs resolved `142820050`)로 새 이미지가 기동하지 않는다 — 이 작업과 무관하다(V32는 이 브랜치에서 수정하지 않았다). 공지 흡수 작업 중 코드 리뷰 라운드가 V32를 수정했는데 그 전에 적용된 dev DB가 남은 것으로 보인다(이 세션에서 확인한 원인은 체크섬 불일치까지이고, 어느 시점에 어떤 내용이 달랐는지는 추정이다). 사용자 데이터를 건드리지 않으려고 `flyway repair`를 하지 않고 별도 스키마로 검증했다. **기존 dev 컨테이너 `cms-app-dev`는 재시작 루프를 막으려고 중지한 상태다** — dev 스택을 다시 쓰려면 dev DB를 V32 최신 내용으로 재생성하거나 `flyway repair`(+ 필요 시 V32 효과 확인)가 필요하다.
- 시험 작성 중 발견·수정: `@DataJpaTest`는 `QuerydslConfig` import가 필요(`AdminMessageRepositoryImpl`이 `JPAQueryFactory`를 요구), 슬라이스 시험의 `put(...)` 헬퍼 이름이 정적 임포트와 충돌, 멀티파트 엔드포인트의 권한 거부 시험은 JSON이 아니라 멀티파트 요청이어야 403이 난다(아니면 매핑 단계에서 415).

### 후속

- 코드 리뷰 루프 → `/commitPR`(PR 제목 후보 질문 포함). 머지 후 `/updateRoadmap`으로 Top 8 ② 완료 반영.
- ④ 예약 게시: `findLatestPublished`에 기간 조건 추가 필수. ⑦ 팝업: `DisplayPeriod`·`SafeLinkUrl`·`PublicImageStreamer` 재사용. ⑧ 미디어 라이브러리: `banner` 네임스페이스 이미지 이관.
- 기존 `PublicContentImageController`·`PublicNoticeController`의 스트리밍 복제를 `PublicImageStreamer`로 통합(별도 작업).
- `PermissionCacheIsolationIntegrationTest`·`INNODB_LOCK_WAITS` 락 대기 관측 시험의 기능 단위(BANNER) 판 복구(게시판 판이 같은 코드를 이미 고정).
