# PLAN — 조회수·다운로드 카운트 + 대시보드 콘텐츠 현황

> 상태: 구현·검증 완료(2026-10-13, 커밋·PR 전). 계획 v3(적대적 리뷰 2라운드 ship). 로드맵 "이번 선정에서 탈락한 후보 > 휴지통·조회수/다운로드 카운트 + 대시보드 고도화" 중 **조회수·다운로드 카운트 + 대시보드**만 다룬다(휴지통은 범위 밖).

## Context

- 공개 게시판(`/boards/**`)과 공지(`/notices/**`)가 갖춰졌지만 어떤 글이 읽히고 어떤 첨부가 내려받히는지 알 수 없다. 대시보드는 관리자 방문자 지표뿐이다.
- 공지는 공지 게시판(`board_key='NOTICE'`)의 게시글이라 **글 상세·첨부 다운로드의 공개 판정이 `PublicBoardService` 한 곳**을 지난다. 단 컨트롤러는 `PublicBoardController`·`PublicNoticeController` 두 벌(스트리밍 코드 복제)이다.
- 정찰 확인 사실:
  - `Post`·`PostAttachment`에는 카운터가 없다. `Post`는 낙관적 락(`@Version`)이 없고 수정·삭제는 `findByIdAndBoardIdAndDeletedFalseForUpdate`(행 `FOR UPDATE`) 후 엔티티 변경 → 플러시 시 **전체 컬럼 UPDATE**다.
  - `PublicBoardService.findPublishedPost`/`findPublishedAttachment`는 `@Transactional(readOnly = true)`다. 쓰기를 여기에 넣을 수 없다.
  - 상세 핸들러는 `@GetMapping`이라 Spring이 **HEAD도 같은 핸들러로** 보낸다. 첨부는 HEAD 전용 핸들러가 따로 있다.
  - `cms.rate-limit` 규칙(`public-board` `/boards/**` 120/60초, `board-attachment` 20/60초, 공지 대응 규칙)이 IP당 진입 빈도를 이미 제한한다. 키는 `getRemoteAddr()`(프록시 뒤에서는 프록시 IP 하나로 보이는 한계, `docs/deployment.md`).
  - Caffeine은 직접 의존성이다(`build.gradle`). `@EnableScheduling`은 없다(스케줄러 없음).
  - 대시보드 `DashboardService`는 비트랜잭션 + 전체 폴백(null) 패턴이고 `AdminMainController.main`이 모델에 넣는다. 대시보드는 ADMIN·MANAGER 둘 다 본다.
  - `AdminPermissionEvaluator.readableBoardIds(snapshot, authentication)`가 MANAGER의 READ 가능 게시판 집합을 준다(통합 검색이 사용).
  - 마지막 마이그레이션은 V48. 이번은 V49.

## 쟁점과 결정

### 쟁점 1. 무엇을 저장하나
- 선택지 A: `post.view_count`·`post_attachment.download_count` 누적 카운터 컬럼 2개.
- 선택지 B: 이벤트 테이블(일별 집계 포함)로 기간별 추이.
- **결정: A.** 왜: 요구("조회수·다운로드 카운트 + 대시보드")를 만족하는 최소 구현. B는 테이블·보존·집계·정리 정책이 따로 필요한 과설계이고 스케줄러도 없다. 기간별 추이가 필요해지면 후속으로 B를 얹는다(A의 컬럼은 그대로 유효).
- 컬럼: `BIGINT NOT NULL DEFAULT 0`(V49, 단일 `ALTER TABLE` 두 개). 구앱은 이 컬럼을 모르지만 DEFAULT 0이라 INSERT 호환 → **롤백 가능**(롤백 중 카운트만 멈추고 값은 보존).

### 쟁점 2. 증가 방식과 동시성
- A: 엔티티 로드 → `increment()` → 플러시 (읽고-쓰기, 경합 시 손실).
- B: 원자적 `UPDATE post SET view_count = view_count + 1 WHERE id = ?`.
- C: 메모리 버퍼 후 주기 플러시(스케줄러 없음, 크래시 시 유실).
- **결정: B**, 호출마다 짧은 별도 트랜잭션(`@Transactional`, 공개 조회 트랜잭션 밖). 왜: 손실 없이 가장 단순. 한 행에 대한 증가는 InnoDB 행 락으로 직렬화되며 락 보유 시간이 한 문장이라 이 규모에서 충분하다.
- **엔티티는 카운터를 절대 덮어쓰지 않는다**: `Post.viewCount`·`PostAttachment.downloadCount` 필드는 `@Column(updatable = false)`다. 관리자 수정이 로드된 낡은 값으로 전체 컬럼 UPDATE를 해도 카운터는 SET에서 빠진다(없으면 관리자 저장이 카운트를 되돌리는 결함). `@Builder.Default`로 0.
- 관리자 수정(`FOR UPDATE`) 중에는 카운터 UPDATE가 그 트랜잭션이 끝날 때까지 기다린다. **대기 상한(v2)**: 일반 `@Modifying @Query`로는 문장 제한이 걸리지 않으므로 증가는 `NotificationRecorder.recordInquiryReceived()`와 같은 방식 — `JdbcTemplate`으로 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR UPDATE post SET view_count = view_count + 1 WHERE id = ?`(첨부도 동일) — 을 쓴다(`innodb_lock_wait_timeout`은 락마다 새로 계산돼 합산 상한이 아니므로 두 설정을 함께 건다. 대략의 상한이지 엄밀한 벽시계 상한은 아님). 초과·실패는 쟁점 5의 격리로 버린다(카운트 1회 유실 수용). 커넥션 획득 대기는 문장 제한 밖이다 — 중복 억제 캐시가 DB 접근 자체를 줄이고 레이트리밋이 진입 빈도를 제한하며, 풀이 포화되면 기존 공개 조회도 같은 풀을 기다리므로 카운터가 새로 만드는 위험이 아니다(수용). 따라서 증가는 `@Modifying @Query`가 아니라 `JdbcTemplate`이다.

### 쟁점 3. 무엇을 "1회"로 보나 — 봇·반복 새로고침
- A: 요청마다 증가. B: (IP, 대상) 시간 창당 1회. C: 쿠키/세션 기반.
- **결정: B.** 같은 `getRemoteAddr()` + 같은 대상(글 ID 또는 첨부 ID)은 **30분에 1회**만 센다. 구현: Caffeine `Cache<String, Boolean>`(키 `V:{postId}`/`D:{attachmentId}` + IP, `expireAfterWrite` 30분, `maximumSize` 100,000). `cache.asMap().putIfAbsent` 성공 시에만 DB 증가. 왜: C는 무인증 공개 경로에 쿠키를 새로 심어야 해 과설계이고 봇은 쿠키를 버린다. A는 레이트리밋(120/분)만큼 부풀 수 있다. 캐시는 DB 쓰기 폭주를 줄이는 이점도 있다.
- 한계(수용·문서화): 프록시 뒤에서는 모든 방문자가 한 IP라 30분에 글당 1회만 센다(`getRemoteAddr()` 한계가 레이트리밋과 같다 — ingress 도입 시 `forward-headers-strategy`와 함께 재평가). 재기동 시 캐시가 비어 중복 집계가 한 번 더 가능. 캐시가 가득 차면 Caffeine이 오래된 키를 제거해 중복 집계가 늘 수 있다(fail-open, 레이트리밋과 같은 수용 방침). 조회수는 "대략적 인기도"이며 정확한 방문 수 보장이 아니다.
- 관리자 로그인 상태의 열람도 센다(제외 로직은 과설계).

### 쟁점 4. 어디서 세나 — 공개 판정과 정합
- **조회**: `PublicBoardController.detail`·`PublicNoticeController`의 상세에서 `findPublishedPost...`가 **존재했고 요청이 GET일 때만** 컨트롤러가 `ContentCounterService.recordView(postId, remoteAddr)`를 호출한다. HEAD(암묵 매핑)는 제외 — 요청 메서드를 컨트롤러가 확인한다. 404/비공개는 호출 자체가 없어 존재 여부가 카운터로 새지 않는다.
- **다운로드**: 파일 열기에 성공(`openAttachment` non-empty)한 뒤 **GET 핸들러에서만** `recordDownload(attachmentId, remoteAddr)`를 호출한다(HEAD 핸들러 제외). "내려받기 시작" 기준이며 전송 완료·중단은 구분하지 않는다. 왜: 전송 완료 후 세려면 `writeBody` 3구간 실패 처리 계약을 건드려야 한다.
- UPDATE는 ID로만 한다(공개 판정은 직전에 끝났다). 그 사이 삭제·비공개로 바뀌어도 증가는 무해하다.
- 컨트롤러 두 벌에 같은 호출을 넣는다 — 스트리밍 복제 주의 규약대로 두 컨트롤러와 두 스트리밍 시험을 함께 본다.

### 쟁점 5. 집계 실패가 응답에 영향을 주면 안 된다
- **트랜잭션 경계(v2)**: `@Transactional` 메서드 안의 try/catch는 트랜잭션 시작 실패(본문 진입 전)·커밋 실패(본문 반환 후)를 잡지 못한다(`DashboardService` 주석과 같은 이유). 그래서 **두 빈으로 나눈다** — 비트랜잭션 `ContentCounterService`(캐시 검사 + 호출 전체를 try/catch)가 `@Transactional`을 가진 별도 빈 `ContentCounterWriter.incrementView(postId)`/`incrementDownload(attachmentId)`(`JdbcTemplate` UPDATE 한 문장)를 부른다. 같은 클래스 내부 호출은 프록시를 우회하므로 반드시 별도 빈이다.
- 다운로드 호출은 컨트롤러의 기존 `try (PublicPostAttachmentDownload opened = ...)` **블록 안**, `writeBody` 호출 앞에 둔다(스트림 닫기 보장 유지, 카운터는 예외를 던지지 않으므로 3구간 실패 처리에 영향 없음).
- 서비스는 모든 예외를 잡아 `log.warn`(예외 클래스명만, 메시지·IP 비로그)으로 격리하고 응답에는 영향이 없다. 카운터는 최선 노력(best-effort)이다 — 감사 로그가 아니다.
- 별도 서비스 `ContentCounterService`(`com.cms.admin.board.service`가 아니라 도메인 쓰기이므로 `com.cms.admin.board.service`에 둔다 — 공개 패키지는 Repository만 의존하는 기존 방향을 유지)(증가는 위 `ContentCounterWriter`의 `JdbcTemplate` — Repository 쿼리 없음).

### 쟁점 6. 대시보드 — 무엇을, 누구에게
- 표시: ① 총 조회수 ② 총 다운로드 수 ③ 조회 상위 5개 게시글(제목·게시판 이름·조회수) ④ 다운로드 상위 5개 첨부(파일명·소속 글·횟수).
- **MANAGER에게는 READ 가능한 게시판의 항목만** 보인다(통합 검색과 같은 규칙, `readableBoardIds`). ADMIN은 미삭제 게시판 전부. 이유: 대시보드는 MANAGER도 보는 화면이라 제한 없이 상위 목록을 보이면 권한 없는 게시판의 제목이 샌다. 삭제된 글·삭제된 게시판은 제외, 비노출 글은 포함(관리자 화면). READ 가능 게시판이 없으면 섹션 대신 안내 문구, 합계 0.
- `DashboardService`에 별도 메서드 `getContentStats(Authentication)`를 추가하고 **기존 방문자 지표와 폴백을 분리**한다(콘텐츠 집계 실패가 방문자 카드까지 null로 만들지 않게). 실패 시 null → 화면은 "-"/오류 문구.
- 쿼리: QueryDSL 우선 규약에 따라 `PostRepositoryImpl`에 집계·상위 N을 추가(`SUM(view_count)`, `ORDER BY view_count DESC, id DESC LIMIT 5`). 인덱스 추가 없음 — 상위 N 정렬이 전 테이블 스캔이지만 게시글 규모가 작다(리뷰에서 재평가). 첨부는 `post_attachment` ⨝ `post`.
- 화면: `admin/index.html`에 "콘텐츠 현황" 카드 2장(총 조회·총 다운로드)과 상위 5 목록 2개. 제목·파일명은 `th:text`.

### 쟁점 7. 관리 화면·API
- `PostResponse`/`PostSummaryResponse`에 `viewCount`, `PostAttachmentResponse`에 `downloadCount` 추가(읽기 전용). 게시글 목록 열 "조회수"와 정렬 화이트리스트에 `viewCount` 추가. 요청 DTO에는 카운터가 없다(수정 불가 — 조작 방지).
- 공개 화면에는 카운트를 **노출하지 않는다**(요구 없음, 열거·조작 동기 방지). 필요하면 후속.

### 쟁점 8. 인가·보안 영향
- `SecurityConfig` 변경 **없음**, 신규 엔드포인트 **없음**, 기존 `/boards/**`·`/notices/**` GET·HEAD 무인증 경로가 DB 쓰기를 하게 된다는 점이 유일한 새 표면. 완화: IP·대상당 30분 1회 중복 억제 + 기존 레이트리밋 + 별도 짧은 트랜잭션 + 실패 격리.
- 카운터는 응답에 노출되지 않아(공개) 조회수 조작이 사이트 기능에 영향을 주지 않는다. 관리자 화면 표시 지표 오염만 가능하고 이는 수용한다.

## 작업 단계 (의존 안쪽 → 바깥쪽)
1. `V49__add_view_download_counts.sql`(두 컬럼) + 마이그레이션 시험(V48→V49, 기본값 0, 구앱 INSERT 호환).
2. 엔티티 `Post.viewCount`·`PostAttachment.downloadCount`(`updatable=false`, 기본 0).
3. `ContentCounterWriter`(`@Transactional`, JdbcTemplate `SET STATEMENT` UPDATE) + `ContentCounterService`(비트랜잭션, Caffeine 중복 억제, 예외 격리) + 단위 시험.
4. 공개 컨트롤러 두 벌에 GET 한정 호출 삽입.
5. 집계 쿼리 + `DashboardService.getContentStats` + 모델·템플릿.
6. 관리 응답 DTO·목록 열·정렬 화이트리스트.
7. 시험·실기 검증·문서(`com.cms.admin.board`·`publicweb.board`·`publicweb.notice` CLAUDE.md, 필요 시 `docs/deployment.md` 롤백 메모).

## 시험 계획
- 단위: 중복 억제(같은 IP·대상 30분 내 1회, 다른 IP/대상은 각각, 만료 후 재집계), 예외 격리, HEAD 미집계.
- 격리(v2): 트랜잭션 시작 실패(풀 고갈/DataSource 예외 주입)·커밋 실패·UPDATE 예외 각각에서 상세/다운로드 응답이 정상이고 스트림이 닫힘. 관리자 트랜잭션이 `FOR UPDATE`로 행을 잡은 상태에서 공개 GET이 수 초 내 정상 응답(카운트는 유실)하고 커넥션이 반환됨(작은 풀 시험, `PublicInquirySmallPoolIntegrationTest` 방식).
- 저장소 시험(v2, `updatable=false` 변이 검출): 엔티티를 영속성 컨텍스트에 로드 → JDBC 증가 → 로드된 엔티티 수정·flush → clear → 재조회 시 카운터 보존. `updatable=false` 제거 시 실패해야 한다(정상 PATCH 경로는 `FOR UPDATE`가 먼저라 이 변이를 못 잡는다).
- 통합(Testcontainers): 상세 GET 후 `view_count`=1, 새로고침 동일 IP는 그대로, 404·비공개·예약 글은 증가 없음, 관리자 PATCH 후에도 카운트 유지(`updatable=false` 회귀 — 변이: 제거 시 실패), 공지 경로도 동일, 다운로드 GET만 증가·HEAD 미증가·파일 없음 404는 미증가.
- 동시성: 서로 다른 IP N개가 동시에 같은 글을 열면 정확히 N 증가.
- 대시보드: ADMIN 전체, MANAGER는 READ 게시판만(권한 없는 게시판 제목 비노출), 삭제 글 제외, 집계 실패 시 방문자 카드는 정상.
- 기존 `PublicBoardAttachmentStreamingServerTest`·`PublicAttachmentStreamingServerTest`·`PostRepositoryDataJpaTest` 회귀.

## 리스크
- 무인증 GET의 DB 쓰기 도입(쟁점 3·5로 완화).
- 프록시 뒤 단일 IP 한계로 과소 집계(수용·문서화).
- 상위 N 정렬이 인덱스 없는 스캔(규모 작음, 리뷰 대상).
- `updatable=false`를 빠뜨리면 관리자 수정이 카운터를 되돌린다(시험으로 고정).

## 범위 밖
휴지통, 기간별 추이/이벤트 테이블, 공개 화면의 조회수 노출, 관리자 열람 제외, 카운터 초기화 API.

## 개정 이력
- v1 (2026-10-13): 초안.
- v3 변경(R2: ship): 작업 단계 2·3의 "Repository 증가 쿼리" 표현을 `ContentCounterWriter` JdbcTemplate UPDATE로 정리(문서 정합, 설계 변경 없음).
- v2 변경(R1, codex 3건 전부 수용): (1) 트랜잭션 시작·커밋 실패는 메서드 내부 try/catch가 못 잡음 → 비트랜잭션 `ContentCounterService` + 별도 빈 `ContentCounterWriter`로 분리, 다운로드 호출은 try-with-resources 안에 배치, 격리 시험 추가. (2) `max_statement_time` 미적용 → `SET STATEMENT`로 문장·락 대기 제한을 거는 JdbcTemplate UPDATE로 확정, 커넥션 획득 대기는 수용 사유 명시, 락 점유 중 공개 GET 시험 추가. (3) PATCH 시험이 `updatable=false` 제거를 못 잡음(정상 경로는 `FOR UPDATE`가 선행) → 영속성 컨텍스트 로드→증가→flush→재조회 저장소 시험으로 변이 검출.

## 구현·검증 결과 (2026-10-13, 브랜치 `feat/view-download-count`)

### 핵심 확정 사항
- 계획 v3 그대로 구현했다. 달라진 결정은 없다(구현 중 보고할 변경 없음).

### 구현 파일
- 스키마: `V49__add_view_download_counts.sql`(`post.view_count`·`post_attachment.download_count`, `bigint NOT NULL DEFAULT 0`).
- 도메인: `Post.viewCount`·`PostAttachment.downloadCount`(`@Builder.Default`, `updatable=false`).
- 집계: `ContentCounterWriter`(`@Transactional`, JdbcTemplate `SET STATEMENT … FOR UPDATE …`), `ContentCounterService`(비트랜잭션, Caffeine 30분 중복 억제, 예외 격리).
- 공개 컨트롤러: `PublicBoardController`·`PublicNoticeController`(상세 GET·첨부 GET만 호출, HEAD 제외).
- 대시보드: `ContentStatsQueryRepository`(QueryDSL), `ContentStatsService`, `ContentStatsResponse`, `AdminMainController`·`admin/index.html`.
- 관리 화면·API: `PostResponse`·`PostSummaryResponse`·`PostAttachmentResponse`(읽기 전용 카운터), `PostRepositoryImpl` 정렬 화이트리스트 `viewCount`, `admin/board/posts.html`(조회수 열·상세·첨부 다운로드 횟수).
- 문서: `docs/migration-guide.md` "V49", `com.cms.admin.board`·`publicweb.board`·`publicweb.notice`·루트 `CLAUDE.md`.

### 검증 결과
- 전체 `./gradlew test` 통과(2,239건 이상, 실패 0). 새 시험: `ContentCounterServiceTest`(5)·`ContentCountMigrationTest`(1)·`ContentCountIntegrationTest`(10)·`AdminMainControllerTest` 2건 추가. 슬라이스 시험 3개(`AdminSidebarAdviceTest`·`PasswordResetControllerTest`·`PublicNoticeControllerTest`)는 새 서비스의 목 빈 추가만 했다.
- **변이 확인**: `Post.viewCount`의 `updatable=false`를 제거하면 `entityUpdate_doesNotOverwriteCounter`가 실패한다(리뷰 R1-3 요구).
- **실기(dev 프로파일, 임시 MariaDB 3308 + 앱 8081 — 사용자 dev DB(3307)는 건드리지 않음)**: 공지 게시판에 글·첨부 생성(관리 API 응답에 `viewCount`·`downloadCount` 포함) → 익명 상세 GET 1→같은 IP 반복 1→HEAD 1→첨부 HEAD 0·GET 1→반복 1→없는 글 404 미집계→`/boards` 경로의 같은 글·같은 IP는 중복 억제로 1 유지. 관리자 PATCH 후 조회수 1 보존, `sort=viewCount,desc` 동작. Playwright 스크린샷: 대시보드 "콘텐츠 현황"(총 1·1, 상위 목록), 게시글 관리 목록 "조회수" 열·상세 조회수·첨부 "다운로드 1회". 스크린샷 `adversarial-review/plan/screenshots-view-download-count/`. 검증 후 앱·임시 DB 정리.
- 숨은 경로·권한 경계: MANAGER가 권한 없는 게시판 제목을 대시보드에서 못 보는 것은 통합 시험(`dashboard_scopedByReadableBoards`)으로 확인했다. 인가 정책·`SecurityConfig` 변경 없음. (브라우저 MANAGER 로그인 실기는 하지 않았다 — 서비스 단위 판정은 시험이 고정.)
- 기존 동작 회귀: 공개 스트리밍 실서버 시험(`PublicBoardAttachmentStreamingServerTest`·`PublicAttachmentStreamingServerTest`)·게시글 API·예약 게시 시험 전부 통과.

### 이슈
- 시험 중 Docker Desktop이 꺼져 있어 Testcontainers 시험이 실패했다(환경 문제, 코드 무관) — Docker 기동 후 통과.
- 시험 픽스처의 `storage_key` 유일 제약 충돌 1건(시험 코드 수정).
- 셸 PATH에 `java`/`python`이 없어 일부 편집을 Edit 도구로 대체했다(기능 영향 없음).

### 후속
- 상위 N 정렬이 느려지면 `post(view_count)`·`post_attachment(download_count)` 인덱스를 새 마이그레이션으로 추가.
- ingress 도입 시 `getRemoteAddr()` 한계(프록시 뒤 과소 집계) 재평가.
- 기간별 추이(이벤트 테이블)·공개 화면 조회수 노출은 필요 시 별도 작업.
- 로드맵 완료 반영은 `/updateRoadmap`(커밋·PR 머지 뒤).

### 코드 리뷰 R1 (codex, 2026-10-13) — 수용 1건
- [medium] 카운터 동시 쓰기가 공용 DB 풀을 점유할 수 있음 → **수용**(계획 v3은 연결 획득 대기를 "새 위험 아님"으로 수용했으나, 관리자가 행을 잡은 동안 서로 다른 IP가 몰리면 UPDATE마다 최대 1초 연결을 쥐어 풀이 찬다). `ContentCounterService`에 동시 쓰기 상한 3(`Semaphore.tryAcquire`, 대기 없음)을 추가하고, 버려진 집계는 중복 억제 키를 되돌린다. 시험: `ContentCounterServiceTest.saturated_dropsWithoutWaiting_andKeepsKeyRetryable`, 통합 동시성 시험을 "상한 이내 정확히 N / 폭주는 응답 정상 + 최선 노력"으로 조정. 설계 변경 아님(계약: 폭주 시 일부 유실 허용).
