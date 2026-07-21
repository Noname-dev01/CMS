# PLAN-notice-board — 공지사항(notice) 관리 도메인

## 구현·검증 결과 (2026-07-20, feat/notice-board)

**Context**: 계획 v7(적대적 리뷰 5라운드 — 4라운드 needs-attention 반복 후 5라운드에서 사용자가 "현재 수준으로 ship" 결정) 그대로 구현. 스키마 변경(V8·V9)·인가 정책 변경(SecurityConfig) 모두 사전 승인됨.

**구현 파일**: 계획서 "수정/신규 파일" 섹션에 명시된 파일 전부 v7 설계대로 구현 완료 — `Notice` 엔티티, `NoticeRepository`(+Impl, QueryDSL), `NoticeService`, DTO 6종, `NoticeController`/`NoticePageController`, `templates/admin/notice/manage.html`, `SecurityConfig` 인가 매처, `AdminActionTypes` 상수 3종, `V8__create_notice.sql`, `V9__seed_notice_menu.sql`.

- **(v7 대비 구현 중 결정 변경, 승인 불필요)** `NoticeConcurrencyIntegrationTest`의 (A) 락 존재 증명은 계획의 `jakarta.persistence.lock.timeout=0` 대신, 이 코드베이스에서 이미 검증된 `AdminMemberUpdateConcurrencyIntegrationTest.guardQuery_actuallyAcquiresRowLocks()` 기법(SQL 세션 레벨 `SET SESSION innodb_lock_wait_timeout = 1` + `TransactionTemplate`)을 재사용했다. 목적은 동일(타이밍 무관 결정적 락 증명), 미검증 신규 접근법 대신 실증된 패턴 채택.
- **`AdminActionTypeLabelSyncTest`(기존 컨벤션 테스트) 대응**: 계획서에 명시되지 않았던 기존 테스트가 로그 관리 화면(`templates/admin/log/manage.html`)의 `ACTION_TYPE_LABELS` JS 맵에도 새 액션 타입 라벨 등록을 강제해, `NOTICE_CREATE`·`NOTICE_UPDATE`·`NOTICE_DELETE` 3개 라벨("공지 생성"·"공지 수정"·"공지 삭제")을 추가했다.

**검증 결과**:
- `./gradlew test` 전체 통과 (신규 테스트: `NoticeServiceTest`, `NoticeControllerTest`, `NoticeRepositoryImplSortTest`, `NoticeRepositoryDataJpaTest`, `NoticeConcurrencyIntegrationTest`(A·B 시나리오), `SecurityConfigTest` notice 케이스 8개 추가. `AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest`·`AdminPageAnnotationConventionTest` 회귀 없음).
- `bootRun` 기동 성공 — Flyway `Current version of schema cms: 9`, `ddl-auto: validate` 통과(엔티티-스키마 1:1 매핑 정합 확인).
- **Playwright 골든 패스**: ADMIN(`admin`)·검증용 MANAGER(`noticeqamgr01`, 검증 후 삭제) 각각 로그인 → 사이드바에 "공지사항 관리" 메뉴 자동 노출(V9 시드, 수동 등록 불필요) 확인 → 생성(XSS 페이로드 `<script>`·`<img onerror>` 포함) → 목록·상세·수정(노출→비노출 토글 포함)→ 검색 필터(`useYn=노출` 필터링 시 비노출 공지 정확히 제외) → 소프트 삭제 후 목록 미노출까지 양쪽 역할 모두 확인.
- **데이터 왕복**: DB 직접 조회로 `use_yn=0, deleted=1`(bit 컬럼 CAST 확인) — 하드 삭제가 아니라 소프트 삭제임을 확인. 검증용 데이터(notice·MANAGER 계정)는 전부 원복 완료.
- **XSS**: `window.__xssFired` 전역 플래그로 스크립트 미실행 확인. 목록·상세·수정 폼 모두 원문이 텍스트로만 표시되고 실행되지 않음.
- **감사 로그**: 로그 관리 화면에서 `NOTICE_CREATE`/`NOTICE_UPDATE`/`NOTICE_DELETE` 라벨·`targetId`(`NOTICE #27`, `NOTICE #28` 등 — null 아님) 정상 표시 확인.
- **인가 차단**: `SecurityConfigTest`로 USER 403·미인증 401(API)/리다이렉트(페이지)·CSRF 누락 403 자동화 검증(브라우저 세션 쿠키가 HttpOnly라 실기 재현 대신 자동화 테스트 결과로 갈음).

**이슈**: 없음(모든 지적은 적대적 리뷰 단계에서 계획에 반영되어 구현 단계 이슈로 넘어오지 않음). `docs/troubleshooting.md` 기록 대상 비자명 이슈 없음.

**후속**: 로드맵 ②(파일 스토리지 추상화 + 공지 첨부파일), ③(공개 공지 페이지)의 선행 조건 충족.

## 개정 이력

- **v7 (2026-07-20, 구현 중 결정 변경 — 승인 불필요, 검증된 기존 기법으로 대체)**: `NoticeConcurrencyIntegrationTest` (A) 락 존재 증명을 계획서의 `jakarta.persistence.lock.timeout=0` + `EntityManager.find()` 대신, **`AdminMemberUpdateConcurrencyIntegrationTest.guardQuery_actuallyAcquiresRowLocks()`가 이미 이 코드베이스에서 실제로 통과 검증된 기법**(SQL 세션 레벨 `SET SESSION innodb_lock_wait_timeout = 1` + `TransactionTemplate`)으로 구현했다. 목적(타이밍 무관 결정적 락 증명)은 동일하고, 미검증 신규 접근법 대신 이미 실증된 패턴을 재사용해 리스크를 낮췄다. 실행 결과: (A)·(B) 시나리오 모두 실제 MariaDB로 통과 확인.
- **v6 (2026-07-20, codex 적대적 리뷰 5라운드 결과 반영 — 사용자 결정으로 리뷰 루프 종료)**: 라운드 5는 hang 방지·size clamp 계층은 해소로 확인했으나, (B) PATCH–DELETE 경합 시나리오가 "B가 실제 락 대기에 진입했음"을 결정론적으로 보장하지 못한다는 잔여 지적을 남겼다(스케줄링에 따라 A가 B의 락 대기 진입 전에 커밋해버리면 진짜 경합 없이도 테스트가 통과할 이론적 가능성). codex는 완전한 결정론화를 위해 MariaDB `information_schema`/`performance_schema` 락 대기 메타데이터 폴링을 제안했으나, 이는 5라운드째이자 구현 복잡도·MariaDB 버전 스키마 의존 리스크가 크다.
  - **사용자 결정(2026-07-20)**: 스킬의 "5라운드 도달 시 남은 쟁점 보고 후 계속 여부 질문" 규칙에 따라 사용자에게 질문한 결과, **현재 수준으로 ship** 선택. 근거: (A) 락 타임아웃 힌트 시나리오가 "Notice에 비관적 락이 실제로 걸리는가" 자체는 4라운드에서 이미 완전한 결정론으로 증명 완료(타이밍 무관). 만약 `@Lock`·`@Query` 선언이 누락되는 회귀가 생기면 (A)가 100% 결정적으로 이를 검출한다. (B)는 그 위에 얹은 통합 동작 확인(락이 존재하는 전제 하에 PATCH–DELETE 순서가 삭제를 유실하지 않는가)으로 **역할을 재정의**하고, 잔존하는 이론적 스케줄링 비결정성은 이 MVP 관리 화면 기능의 테스트 인프라에 락 대기 메타데이터 폴링까지 투자하기엔 과도한 리스크로 판단해 수용.
  - **수용(반영)**: hang 방지 규칙(해제 래치 `finally` 카운트다운·모든 `await`/`Future.get` 타임아웃·`executor.shutdownNow()`·예외 언랩)을 (A) 시나리오뿐 아니라 **(B) 시나리오에도 동일 적용**(리뷰 5라운드 지적 #2 — 비용 낮고 명백히 타당, B에서 assertion이 실패해도 A가 무기한 대기하지 않도록 보장).
  - **반박**: 리뷰 5라운드 지적 #1(B의 락 대기 진입 완전 결정론화) — 위 사용자 결정 근거와 동일. (A)가 락 존재를 이미 결정적으로 증명하므로 (B)의 잔존 비결정성은 실제 회귀 검출 능력을 훼손하지 않는다고 판단해 현재 설계를 유지한다.
- **v5 (2026-07-20, codex 적대적 리뷰 4라운드 — needs-attention, "반영 후 ship 가능" 명시)**: 라운드 3의 락 타임아웃 힌트 방향은 유효하다고 확인됐으나 테스트 구현 디테일 3건 지적. 전부 수용.
  - **수용 #1** 락 검증 실패(회귀) 시 테스트가 해제 래치 대기에서 무기한 멈출 수 있음(정확히 검출해야 할 회귀 상황에서 테스트 스위트가 종료되지 않음) → 해제 래치 `countDown()`을 메인 스레드 `finally`에서 실행 + 모든 `await()`에 제한 시간 + `Future.get(timeout, unit)` + 종료 시 `executor.shutdownNow()` + `ExecutionException` 언랩으로 반영.
  - **수용 #2** 테스트가 이름·완료 기준과 달리 실제 PATCH–DELETE 경합을 실행하지 않음(락 존재만 검증하고 `updateNotice()`는 호출조차 안 함 — 완료 기준 문구와 실제 검증 내용이 불일치) → **A가 락 획득 후 `softDelete()`까지 수행하고 커밋 직전 대기 → B가 `updateNotice()` 호출(A의 락에 막혀 대기) → A 커밋 → B의 후속 락 조회가 404 → DB `deleted=true` 유지 확인**하는 결정적 시나리오로 반영(타이밍 측정 불필요 — A가 먼저 커밋하도록 구조적으로 강제).
  - **수용 #3** size clamp 검증이 잘못된 계층(`NoticeRepositoryImplSortTest`)에 배치되어 실제로는 서비스 clamp를 우회함 → `NoticeServiceTest`에서 `ArgumentCaptor<Pageable>`로 Repository에 전달된 Pageable을 캡처해 size 101+→100 clamp·100 이하는 그대로 유지되는 경계값을 검증하도록 반영. `NoticeRepositoryImplSortTest`는 정렬·삭제 필터 검증만 남김.
  - **반박**: 없음.
- **v4 (2026-07-20, codex 적대적 리뷰 3라운드 — needs-attention)**: 라운드 2의 4개 지적 중 3개(명시적 @Query·NOT NULL 확대·NoticeSummaryResponse 설명 정정) 해소 확인. 동시성 테스트는 "부분 해소"로 재지적, 신규 지적 2개 추가. 3개 전부 수용.
  - **수용 (재지적, 이전보다 강화)** v3의 결정적 동시성 테스트 설계도 여전히 "짧은 지연" 시간에 의존해 (a) B가 스케줄 아웃되면 락 없는 구현도 우연히 통과, (b) B가 시작 전에 A가 해제하면 올바른 구현도 실패할 수 있음 → **JPA 락 타임아웃 힌트(`jakarta.persistence.lock.timeout=0`)로 진짜 결정적 검증**으로 재설계(설계 결정 3 v4 갱신 참조): 시간 지연에 의존하지 않고, A가 락 보유 중 B가 즉시 실패형 락 시도를 하면 **반드시** `LockTimeoutException`/`PessimisticLockException`이 발생함을 단언 — 타이밍 무관하게 결정적.
  - **수용 #2** `NoticeSearchRequest.keyword`의 `@Size`가 컨트롤러 서명에 `@Valid`가 없어 실행되지 않음(`AdminMemberController`는 `@Valid @ModelAttribute` 사용) → 컨트롤러 서명에 `@Valid` 추가로 반영.
  - **수용 #3** 목록 페이지 크기(`size`)에 도메인 상한이 없어 큰 값 요청 시 `content`(TEXT)까지 포함해 DB·힙 비용이 커짐 → `AdminActionLogQueryService`의 `MAX_PAGE_SIZE=100` clamp 패턴을 그대로 미러해 반영.
  - **반박**: 없음.
- **v3 (2026-07-20, codex 적대적 리뷰 2라운드 — needs-attention)**: 라운드 1의 10개 지적은 설계 수준에서 전부 해소 확인. 신규 지적 4개 전부 수용.
  - **수용 #1** `findByIdAndDeletedFalseForUpdate`를 Spring Data 파생 쿼리 메서드명만으로 선언하면 `ForUpdate`가 예약 접미사가 아니라서 `forUpdate`라는 존재하지 않는 프로퍼티를 찾다가 기동 실패 위험 → **명시적 `@Query` + `@Lock(PESSIMISTIC_WRITE)`**로 반영(`MenuRepository.findByIdForUpdate`와 동일 패턴, 실제 코드 대조로 확인).
  - **수용 #2** 제안된 동시성 테스트(`CyclicBarrier`로 시작만 맞추고 최종 상태만 검사)는 락을 제거한 잘못된 구현도 타이밍에 따라 우연히 통과할 수 있음 → `MenuConcurrencyIntegrationTest` 원문을 대조해 확인한 결과 사실. **결정적 락 검증 방식으로 강화**(설계 결정 3 v3 갱신 참조): 한 스레드가 `TransactionTemplate`으로 직접 트랜잭션을 열어 락을 획득한 채 래치로 대기하고, 두 번째 스레드는 락 획득 신호를 받은 뒤에만 서비스 메서드를 호출해 "두 번째 호출이 첫 번째의 커밋(락 해제)까지 관측 가능하게 지연되었는지"를 경과 시간으로 단언한다.
  - **수용 #3** `author_id`만 NOT NULL로 명시되고 `title`·`content`·`use_yn`·`deleted`는 미명시 → `deleted` null이면 `deleted=false` 필터가 해당 행을 조용히 숨기고, `use_yn` null이면 이진 노출 상태가 깨짐(기존 `menu.use_yn`도 동일 이유로 `bit(1) NOT NULL`). **V8·엔티티 양쪽에 5개 컬럼 전부 NOT NULL**로 반영.
  - **수용 #4** `NoticeSummaryResponse`가 "DB 조회까지 줄인다"는 설명은 부정확(QueryDSL `selectFrom(notice)`는 `@Lob content`도 함께 조회 — `MemberRepositoryImpl` 전례와 동일 구조) → **설명을 "JSON 직렬화·전송량을 줄인다"로 정정**(최소 변경 채택, DB 프로젝션 최적화는 현재 범위에서 보류).
  - **반박**: 없음.
- **v2 (2026-07-20, codex 적대적 리뷰 1라운드 — needs-attention)**: 10개 지적 중 8개 수용, 2개는 사용자 결정 완료.
  - **수용 #1** PATCH/DELETE 경합 시 삭제 취소·수정 유실 가능 → Menu와 동일한 비관적 락(`findByIdAndDeletedFalseForUpdate`)으로 반영. (`@Version` 낙관적 락 대신 채택 — `GlobalApiExceptionHandler`에 이미 `PessimisticLockingFailureException→409` 핸들러가 있어 신규 예외 처리기 추가 없이 기존 인프라 재사용 가능. 낙관적 락은 `ObjectOptimisticLockingFailureException`용 신규 핸들러가 필요해 변경 범위가 더 큼)
  - **수용 #2** 생성/수정 필드 불변식 불일치(PATCH로 공백 본문 저장 가능, 빈 PATCH도 성공) → 수정 DTO도 값이 오면 공백 거부 + 빈 PATCH(`{}`)는 400으로 반영.
  - **수용 #3** 저장형 XSS 방어가 목록에만 명시됨(상세 모달 등에서 ADMIN이 MANAGER 저장 콘텐츠를 열람하는 권한상승 경로) → 전 출력 경로(상세·수정 폼·메시지) 이스케이프 규칙 명시로 반영.
  - **수용 #4(결정 완료)** content DB 타입·길이 미정 → **TEXT + 최대 10,000자**로 확정(사용자 승인).
  - **수용 #5** 목록 API가 본문 전체 직렬화 → `NoticeSummaryResponse`(content 제외) 신설, 상세 GET만 `content` 포함.
  - **수용 #6** DELETE 감사 로그 targetId가 null이 될 위험(AOP가 반환값 getter에서만 추출) → `deleteNotice()`가 `NoticeResponse` 반환, 컨트롤러가 버리고 204 반환(Menu의 `deactivateMenu()`와 동일 패턴)으로 반영.
  - **수용 #7** `author_id` nullable이 불변식 약화 → `NOT NULL` + `getCurrentAdminUserId()` null 시 `AccessDeniedException`으로 반영.
  - **수용 #8** Controller slice 테스트만으론 실제 SecurityConfig 인가 미검증 → `SecurityConfigTest`에 MANAGER 허용/USER 403/미인증 401·리다이렉트/CSRF 케이스 추가로 반영.
  - **수용 #9(결정 완료)** 메뉴 등록이 수동 API 호출로 배포 절차 밖에 남음 → **멱등 Flyway DML(`WHERE NOT EXISTS`)로 자동 등록**하도록 변경(사용자 승인 — 로드맵의 "메뉴 등록은 데이터로" 원 결정에서 편차, V9 신설).
  - **수용 #10** 정렬 tie-breaker 부재, 삭제 검증 범위 부족 → 정렬 끝에 `id` 보조 정렬 추가 + 삭제 후 상세/PATCH/재DELETE 404 테스트 추가로 반영.
  - **반박**: 없음.

## 목표

이 프로젝트는 잘 만들어진 관리자 백오피스 골격이지만 "관리할 콘텐츠"가 없다(도메인이 member·menu·log·visit·dashboard뿐 — 전부 "관리를 위한 관리"). 로드맵(`adversarial-review/project-direction-roadmap.md`, 2026-07-20 재선정 Top 5 ①)은 **공지사항 도메인**을 첫 콘텐츠 도메인으로 추가해 2단계(정체성 확보)를 개시하는 것을 최고 레버리지 작업으로 선정했다. 이 작업은 후속 ②(첨부파일·파일 스토리지)·③(공개 페이지)의 선행 조건이며, 기존 골격(계층 분리·QueryDSL 검색·AOP 감사 로깅·`@AdminPage` 화면·CSRF)이 새 도메인에 그대로 재사용되는지를 검증하는 첫 실전 사례다.

**1차 범위**: 제목·내용·노출여부·작성자·작성/수정일의 **관리 화면 CRUD**. 첨부파일 제외(→ 후속 ②), 공개 프론트 제외(→ 후속 ③).

## 설계 결정 (구현 시 그대로 따를 것)

### 1. 인가: ADMIN + MANAGER 관리 (사용자 승인 완료 — 인가 정책 변경)
- **결정**: 공지 CRUD는 ADMIN·MANAGER 모두 가능. 누가 쓴 글이든 서로 수정·삭제 가능(1차 범위 단순화 — 작성자별 소유권 없음).
- **왜**: 로드맵 완료 기준("ADMIN·MANAGER CRUD 골든 패스")·메뉴 accessRole=ALL과 정합. 공지는 운영 콘텐츠라 매니저 운영이 자연스럽다.
- **제약(중요)**: `SecurityConfig.java:55`의 catch-all `requestMatchers("/admin/**").hasRole("ADMIN")` 때문에, 예외를 추가하지 않으면 공지 경로는 ADMIN 전용이 된다. 따라서 **line 55 앞에** 아래 두 매처를 추가한다 → **인가 정책 변경**:
  ```java
  .requestMatchers("/admin/notice/**").hasAnyRole("ADMIN", "MANAGER")
  .requestMatchers("/admin/api/notices/**").hasAnyRole("ADMIN", "MANAGER")
  ```
- 컨트롤러 메서드에도 `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")`를 이중으로 건다(필터체인 + 메서드 보안, 기존 컨벤션).
- **(v2 추가, 리뷰 #8)** `SecurityConfigTest`(`@SpringBootTest` 또는 기존 슬라이스 패턴)에 다음 케이스를 추가한다: MANAGER가 `/admin/notice/manage`·`/admin/api/notices` 접근 가능, USER는 두 경로 모두 403, 미인증 API는 JSON 401·미인증 페이지는 로그인 리다이렉트, 쓰기 요청 CSRF 누락 시 403·정상 포함 시 통과.

### 2. `use_yn`(노출)과 소프트 삭제를 별도 컬럼으로 분리
- **결정**: `use_yn`(Boolean, 노출 여부 — 폼에서 토글, true/false 모두 관리 목록에 표시)과 `deleted`(Boolean, 소프트 삭제 — 관리 목록에서 제외)를 **별개 컬럼**으로 둔다.
- **왜**: 메뉴는 둘을 `use_yn` 하나로 겸했지만(deactivate=useYn false), 공지는 완료 기준이 "노출여부 필드 보유" + "소프트 삭제 후 목록 미노출"을 **동시에** 요구한다. 하나로 겸하면 "노출 끔" 공지와 "삭제된" 공지를 구분할 수 없다. 별도 `deleted` 플래그가 최소 정답(공지엔 member 같은 status 상태기계가 불필요).
- DELETE = `deleted=true`(하드 삭제 아님). 목록·상세·수정 쿼리는 항상 `deleted=false` 필터.

### 3. 동시성: PATCH·DELETE는 비관적 락으로 직렬화 (v2 결정, v3에서 구현 명세·테스트 강화)
- **결정**: `NoticeRepository`에 `findByIdAndDeletedFalseForUpdate(Long id)`를 추가하고, `updateNotice`·`deleteNotice`는 이 메서드로 대상을 조회한다. `getNotice`(단건 조회)와 목록 검색은 락 없는 `findByIdAndDeletedFalse`/QueryDSL을 그대로 쓴다.
- **왜**: 락이 없으면 DELETE가 `deleted=true`로 커밋한 뒤, 먼저 읽어 둔 PATCH가 `deleted=false` 상태를 그대로 다시 저장해 삭제된 공지가 되살아날 수 있다(PATCH끼리도 마지막 커밋이 앞선 변경을 덮어씀). `MenuService`가 활성 하위 메뉴 불변식을 지키려고 이미 `findByIdForUpdate`(`MenuRepository.java:36`)로 이 문제를 해결한 전례를 그대로 미러한다. `@Version`(낙관적 락)도 후보였으나, 이미 존재하는 `PessimisticLockingFailureException→409`(`GlobalApiExceptionHandler.java:187`) 핸들러를 그대로 재사용할 수 있어 변경 범위가 더 작다.
- **(v3 필수 수정, 리뷰 2라운드 #1)** `ForUpdate`는 Spring Data 파생 쿼리의 예약 접미사가 아니다 — 메서드명만으로 선언하면 Spring Data가 `forUpdate`라는 존재하지 않는 엔티티 프로퍼티를 찾다가 **애플리케이션 기동에 실패**한다. `MenuRepository.findByIdForUpdate`(`MenuRepository.java:36`)와 동일하게 **명시적 `@Query` + `@Lock(PESSIMISTIC_WRITE)`**로 선언해야 한다:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select n from Notice n where n.id = :id and n.deleted = false")
  Optional<Notice> findByIdAndDeletedFalseForUpdate(@Param("id") Long id);
  ```
- 락 획득 실패는 기존 전역 핸들러가 자동으로 409(`RESOURCE_CONFLICT`)로 변환하므로 Service에서 별도 try-catch 불필요.
- **테스트 (v5 — 락 존재의 결정적 증명 + 실제 PATCH–DELETE 경합 재현을 분리, 리뷰 4라운드 반영)**: 두 가지를 각각 별도로 검증한다 — (A) 락이 실제로 걸리는지 자체의 결정적 증명, (B) 완료 기준이 요구하는 실제 PATCH–DELETE 경합 시나리오.
  - **(A) 락 존재 증명** — 타이밍이 아니라 "즉시 실패형 락 시도가 반드시 예외를 던지는가"로 결정적 검증한다:
    1. 스레드 A가 `TransactionTemplate`으로 트랜잭션을 열어 `findByIdAndDeletedFalseForUpdate`(기본 락, 대기함)로 락을 획득한 뒤 "락 획득 완료" 래치를 카운트다운하고 "해제 신호" 래치 대기에 들어간다.
    2. 스레드 B는 "락 획득 완료" 신호를 받은 뒤 `@PersistenceContext EntityManager`로 `em.find(Notice.class, id, LockModeType.PESSIMISTIC_WRITE, Map.of("jakarta.persistence.lock.timeout", 0))`(즉시 실패형 락)를 별도 트랜잭션에서 호출한다. A가 락을 보유 중이므로 **반드시 `LockTimeoutException`/`PessimisticLockException`이 발생**해야 한다.
    3. **(v5 필수 — 리뷰 4라운드 #1)** 이 예외 확인은 메인 스레드의 **`try/finally`** 안에서 이뤄져야 한다: assertion이 실패해도(즉, 락이 걸리지 않는 회귀라도) `finally`에서 "해제 신호" 래치를 반드시 `countDown()`해 스레드 A가 무기한 대기하지 않게 한다. 모든 `CountDownLatch.await()`·`Future.get()`에 명시적 제한 시간을 두고, 시간 초과 시에도 테스트가 실패로 종료되도록 한다. 종료 시 `executor.shutdownNow()`로 정리하고, `ExecutionException`은 언랩해 실제 JPA 예외 타입을 검사한다.
  - **(B) PATCH–DELETE 통합 동작 확인** (v6 — 역할 재정의, 리뷰 5라운드 사용자 결정 참조): 락의 **존재**는 (A)가 이미 결정적으로 증명하므로, (B)는 "락이 존재하는 전제 하에 PATCH–DELETE 순서가 삭제를 유실하지 않는가"를 확인하는 통합 시나리오로 둔다(스케줄링에 따라 B가 A의 락 대기에 진입하기 전에 A가 먼저 커밋할 이론적 가능성은 남아 있으나, 회귀 검출은 (A)가 담당하므로 수용 가능한 잔존 리스크로 판단):
    1. 스레드 A가 락을 획득하고 **`softDelete()`까지 수행한 뒤 커밋 직전(해제 래치 대기)**에 멈춘다.
    2. 스레드 B가 `noticeService.updateNotice(id, ...)`를 호출한다.
    3. 메인 스레드가 해제 래치를 카운트다운해 A를 커밋시킨다(→ `deleted=true` 확정).
    4. B의 `updateNotice()` 호출 결과가 **404**로 종료됨을 단언한다.
    5. DB 상태를 재조회해 `deleted=true`가 유지됨(PATCH로 되살아나지 않음)을 최종 확인한다.
    - **(v6 필수 — 리뷰 5라운드 #2)** (A)와 동일하게 **hang 방지 규칙을 적용**한다: 해제 래치 `countDown()`을 메인 스레드 `finally`에서 실행, 모든 `await()`·`Future.get()`에 제한 시간, `executor.shutdownNow()`로 정리, A·B 양쪽 Future의 결과·예외를 확인해 assertion 실패 시에도 테스트가 무기한 멈추지 않게 한다.
  - 프로덕션 `findByIdAndDeletedFalseForUpdate`는 기본 대기형 락을 그대로 유지한다(즉시 실패형은 (A) 테스트 전용 EntityManager 직접 호출로 한정 — 실제 PATCH/DELETE 요청이 짧은 경합에도 즉시 409로 실패하면 사용성이 나빠지므로 대기가 정상 동작).

### 4. 생성·수정 입력 불변식을 동일하게 유지 (v2, 리뷰 #2 수용)
- **결정**: `NoticeUpdateRequest`도 `title`·`content`가 값으로 오면(null이 아니면) 공백을 거부한다(트림 후 빈 문자열이면 400). 또한 `title`·`content`·`useYn`이 **셋 다 null인 PATCH**(`{}`)는 400 `INVALID_REQUEST`로 거부한다("변경할 필드가 없습니다").
- **왜**: null=기존값 유지 시맨틱은 유지하되, 값을 보냈는데 공백이거나 아무 필드도 안 보낸 경우까지 성공 처리하면 의미 없는 `updateDate` 갱신과 `NOTICE_UPDATE` 감사 로그만 쌓인다(Menu의 `updateMenu`도 `requireNonBlank`로 값이 오면 공백을 거부하는 동일 원칙을 따른다).
- title `@Size(max=200)`, content `@Size(max=10000)`은 생성·수정 DTO 양쪽에 동일하게 적용(설계 결정 6 참조).

### 5. 저장형 XSS: 모든 출력 경로에 이스케이프 규칙 적용 (v2, 리뷰 #3 수용)
- **결정**: `escapeHtml()`(admin-manage.html에 이미 있는 구현 재사용)을 목록 테이블뿐 아니라 **상세 모달·수정 폼 채움·성공/오류 메시지 삽입** 등 `title`·`content`가 등장하는 모든 DOM 지점에 적용한다. 구체 규칙:
  - 목록 테이블 셀: `escapeHtml()` 후 `innerHTML` 삽입(기존 admin-manage.html 패턴).
  - 상세 모달 본문·제목: `escapeHtml()` 후 삽입, 또는 `textContent` 사용.
  - 수정 폼 채움(`<input>`/`<textarea>`): `.value` 대입(HTML 해석 없음 — 별도 이스케이프 불필요하나 반대로 `innerHTML`에 절대 넣지 않는다).
  - 저장/삭제 성공 메시지에 제목을 포함할 경우 `escapeHtml()` 필수.
- **왜**: MANAGER가 저장한 악성 본문(`<img onerror=...>`, `<svg onload=...>` 등)을 ADMIN이 상세를 열람하며 실행시키면 ADMIN 세션 문맥의 권한 상승 경로가 된다. CSRF는 XSS가 있으면 방어막이 되지 않는다.
- **테스트**: 컨트롤러/서비스 테스트에 스크립트 태그가 포함된 title/content 왕복(저장→조회) 케이스를 추가해 서버가 원본을 그대로 저장·반환하는지(이스케이프는 렌더링 시점 클라이언트 책임)만 확인한다. 실제 무해화 확인은 Playwright 실기 검증(11단계)에서 브라우저 alert 미발생으로 확인.

### 6. content 컬럼: TEXT, 최대 10,000자 (v2, 사용자 결정 완료 — 리뷰 #4)
- **결정**: `Notice.content`는 `@Lob` + `columnDefinition = "TEXT"`(V1의 `profile_image_url longtext`와 달리 base64 이미지를 담지 않으므로 LONGTEXT는 과함). DTO(`NoticeCreateRequest`·`NoticeUpdateRequest`)의 `content`에 `@Size(max = 10000)` 적용.
- **왜**: 1차 범위는 첨부파일·이미지 임베드를 제외하므로 일반 텍스트 공지에 TEXT(약 64KB, utf8mb4 바이트 기준이라 문자 수 보장은 아니지만 10,000자 제한이 already 훨씬 작아 문제 없음)면 충분. 무제한(LONGTEXT)은 후속 ②(첨부파일)에서 재평가.

### 7. 작성자는 로그인 userId 문자열 스냅샷, NOT NULL (v2, 리뷰 #7 수용)
- **결정**: `author_id` varchar(100) **NOT NULL**에 `AdminSecurityService.getCurrentAdminUserId()` 결과를 저장. member FK 아님. Service에 `requireCurrentAdminUserId()` 헬퍼를 두어 null이면 `AccessDeniedException`(→ 403)을 던진다.
- **왜**: 기존 `visit_log.visitor_user_id`·`admin_action_log.action_user_id`가 모두 문자열 스냅샷 방식이다. 이 컨벤션을 따르면 조인 불필요 + 작성자 개명·삭제에도 표시가 견고. `getCurrentAdminUserId()`는 예상 못한 principal이면 null을 반환하는데(`AdminSecurityService.java:42`), `@PreAuthorize` 통과 후라면 정상적으로 null이 되지 않아야 하므로 nullable로 두면 인증 연계 버그가 조용히 데이터 손상(작성자 없는 공지)으로 이어진다. `AdminMemberController.requireCurrentAdminId()`의 방어 패턴과 동일 원칙.
- 작성 시 자동 채움(요청 DTO에 author 없음). 수정 시 author 불변.

### 8. 목록 응답은 요약 DTO로 분리, 본문은 상세 조회에서만 반환 (v2, 리뷰 #5 수용 / v3 설명 정정, 리뷰 2라운드 #4)
- **결정**: `NoticeSummaryResponse`(`id`,`title`,`useYn`,`authorId`,`createDate`,`updateDate` — **content 제외**)를 신설해 목록(`GET /admin/api/notices`)에 사용한다. `NoticeResponse`(전체 필드, content 포함)는 단건 조회(`GET /admin/api/notices/{id}`)·생성·수정 응답에만 사용한다.
- **왜**: 목록 20건 조회 시 화면에서 쓰지 않는 본문까지 **JSON으로 직렬화·전송**하는 낭비를 막는다. `AdminMemberPageResponse`가 이미 요약형 `AdminMemberResponse`를 쓰는 것과 같은 원칙.
- **(v3 정정)** `searchNotices()`가 `QueryDSL selectFrom(notice)`로 `Page<Notice>` 엔티티를 반환하는 구조(`MemberRepositoryImpl.java:63`와 동일)이므로, **DB 조회 자체(`@Lob content` 포함)는 줄어들지 않는다** — 이 결정의 효과는 JSON 직렬화·응답 크기 절감에 한정된다. DB 프로젝션 최적화(목록 쿼리에서 content 컬럼 자체를 select하지 않기)는 현재 범위에서 보류하고, 목록 성능이 실제 병목이 되면 QueryDSL DTO 프로젝션으로 후속 개선한다.

### 9. 감사 로깅: DELETE도 응답을 반환해 targetId를 보존 (v2, 리뷰 #6 수용)
- **결정**: `deleteNotice(Long id)`는 `void`가 아니라 `NoticeResponse`를 반환한다(soft-delete 후 갱신된 엔티티 매핑). 컨트롤러가 응답을 버리고 204를 반환한다.
- **왜**: `AdminActionLogAspect`는 **메서드 반환 객체의 getter**에서만 `targetIdExpression`을 추출한다(`AdminActionLogAspect.java:44`). `void` 메서드에 `targetIdExpression="id"`를 붙이면 항상 null이 기록된다. `MenuService.deactivateMenu()`가 정확히 같은 이유로 `MenuResponse`를 반환하는 기존 전례를 그대로 따른다.
- `AdminActionTypes`에 `NOTICE_CREATE`·`NOTICE_UPDATE`·`NOTICE_DELETE` 추가 + `ALL` 목록에 등록(누락 시 `AdminActionTypeSyncTest` 실패). Service 메서드에 `@AdminActionLogged(actionType=..., targetType="NOTICE", targetIdExpression="id")`.
- **테스트**: 감사 로그 검증 시 액션 타입 존재뿐 아니라 실제 `targetId`가 채워지는지(널이 아님)까지 확인한다.

### 10. 화면은 메뉴 트리가 아니라 회원 목록 패턴을 미러 (로드맵과의 정당한 편차)
- **결정**: `templates/admin/notice/manage.html`은 `menu/manage.html`(jstree 계층 UI)이 아니라 **`member/admin-manage.html` 패턴**(검색 폼 + 테이블 + 페이지네이션 + 모달 상세/편집)을 미러한다.
- **왜**: 로드맵은 "menu/manage.html 구조 미러"라 적었지만, 공지는 **평면 페이징 컬렉션**이지 계층이 아니다. 트리 UI는 부적합. 단일 `manage.html` 한 파일에 검색·목록·페이징 + 생성/수정/상세/삭제 모달을 담아 로드맵의 "manage.html 단일 파일" 의도는 유지. 본문(content)은 modal-lg 안 textarea로 처리.
- CSRF: `head.html`이 렌더링하는 `<meta name="_csrf">`·`_csrf_header`를 JS에서 읽어 `X-CSRF-TOKEN` 헤더로 전송(admin-manage.html의 `getCsrfHeaders()` 패턴 재사용).

### 11. 정렬은 항상 `id`를 보조 정렬로 포함 (v2, 리뷰 #10 수용)
- **결정**: `NoticeRepositoryImpl.toOrderSpecifiers()`가 반환하는 `OrderSpecifier[]`의 **마지막에 항상 `notice.id.desc()`(또는 요청 정렬 방향에 맞춘 id)를 추가**한다. 정렬 화이트리스트는 `id`,`title`,`useYn`,`createDate`,`updateDate`(기본 정렬 `id desc`).
- **왜**: `title`·날짜만으로 정렬하면 동률 행 사이 순서가 매 쿼리마다 달라질 수 있어 페이지 이동 시 항목이 누락되거나 중복 노출된다. 기존 `MemberRepositoryImpl`도 이 tie-breaker가 없는 기지 결함이지만(`MemberRepositoryImpl.java:84`), 신규 구현에서는 반복하지 않는다.

### 12. 소프트 삭제 후 상태 검증 확대 (v2, 리뷰 #10 수용)
- **결정**: 삭제된 공지에 대해 상세 조회(404)·PATCH(404)·재DELETE(404) 모두 검증하고, 검색(keyword·useYn 필터 각 조합)에서도 제외됨을 Repository 테스트로 확인한다.

### 13. 감사 로깅 액션 타입 3종 신규
- `AdminActionTypes`에 `NOTICE_CREATE`·`NOTICE_UPDATE`·`NOTICE_DELETE` 추가 + `ALL` 목록에 등록(설계 결정 9 참조).

### 14-1. 핵심 컬럼 전부 NOT NULL (v3, 리뷰 2라운드 #3 수용)
- **결정**: `title`, `content`, `use_yn`, `deleted`, `author_id` **5개 컬럼 모두 NOT NULL**로 V8·엔티티(`@Column(nullable = false, ...)`)에 명시한다.
- **왜**: `author_id`만 NOT NULL로 명시했던 v2는 불완전했다. `deleted`가 null이면 모든 조회의 `deleted=false` 필터(설계 결정 2)가 해당 행을 조용히 숨기고, `use_yn`이 null이면 노출 여부라는 이진 상태가 깨진다. 기존 `menu.use_yn`도 동일한 이유로 `bit(1) NOT NULL`이다(`V1__init_schema.sql:47`). 서비스 생성 경로에서 `deleted=false`·`useYn` 기본값(true)을 반드시 채워야 하며, Repository 통합 테스트로 이 제약이 실제 DB 스키마에 반영됐는지 확인한다.

### 14. 스키마: Flyway V8(테이블 생성) + V9(멱등 메뉴 시드) (v2, 리뷰 #9 사용자 결정 완료)
- **결정**: `V8__create_notice.sql`로 `notice` 테이블 생성. 사이드바 메뉴는 **`V9__seed_notice_menu.sql`로 멱등(WHERE NOT EXISTS) 자동 등록** — `POST /admin/api/menus` 수동 호출을 하지 않는다.
- **왜**: 원 로드맵은 "메뉴 등록은 데이터(API 호출)"로 명시했으나, 신규·CI·향후 스테이징 등 매 환경마다 수동 호출을 빠뜨리면 기능은 존재하되 사이드바에서 발견되지 않는 결함이 생긴다(적대적 리뷰 #9, 사용자 승인). V3(빈 테이블 전용 시드)와 달리 이 마이그레이션은 `menu_url` 존재 여부로 조건부 삽입해 기존 커스터마이즈 데이터를 건드리지 않는다. DDL 금지(순수 DML만 — MariaDB 암묵적 커밋 회피, V3와 동일 규칙).
- V9 예시:
  ```sql
  INSERT INTO menu (menu_name, menu_url, menu_icon, use_yn, access_role, ord, up_menu_no, create_date, update_date)
  SELECT '공지사항 관리', '/admin/notice/manage', 'fas fa-fw fa-bullhorn', 1, 'ALL', 4, NULL, NOW(), NOW()
  FROM DUAL
  WHERE NOT EXISTS (SELECT 1 FROM menu WHERE menu_url = '/admin/notice/manage');
  ```
- `V1__init_schema.sql`의 member·menu 컬럼 규약(감사 컬럼 datetime(6), utf8mb4)을 그대로 따른다. `ddl-auto: validate`이므로 엔티티 매핑과 1:1 대응해야 한다.

## 수정/신규 파일

### 신규 — 도메인·데이터 계층 `src/main/java/com/cms/admin/notice/`
- `domain/Notice.java` — `@Entity`, `@Builder`, `@Getter`, `@NoArgsConstructor`, `@AllArgsConstructor`. `@Setter`/`@Data` 금지. 필드(**title·content·useYn·deleted·authorId 전부 `@Column(nullable = false, ...)`** — v3 설계 결정 14-1): `id`, `title`(varchar 200), `content`(`@Lob`, `columnDefinition="TEXT"`), `useYn`(Boolean), `deleted`(Boolean), `authorId`(varchar 100), `createDate`, `updateDate`. 도메인 메서드: `update(title, content, useYn)`(각 파라미터 null이 아닐 때만 반영 + updateDate 갱신), `softDelete()`(deleted=true + updateDate). Menu 엔티티 패턴 미러.
- `repository/NoticeRepository.java` — `JpaRepository<Notice,Long>, NoticeRepositoryCustom`. `findByIdAndDeletedFalse(Long)`(락 없음, 단건 조회용, 파생 쿼리로 충분), `findByIdAndDeletedFalseForUpdate(Long)`(**명시적 `@Query` + `@Lock(PESSIMISTIC_WRITE)`** — v3 필수 수정, 설계 결정 3 참조. `ForUpdate`는 파생 쿼리 예약 접미사가 아니므로 메서드명만으로 선언하면 기동 실패 위험. `MenuRepository.findByIdForUpdate` 패턴 미러).
- `repository/NoticeRepositoryCustom.java` — `Page<Notice> searchNotices(NoticeSearchRequest, Pageable)`.
- `repository/NoticeRepositoryImpl.java` — QueryDSL. `deleted=false` 강제 + 제목 keyword(contains) + useYn 필터 optional + 정렬 화이트리스트(`id`,`title`,`useYn`,`createDate`,`updateDate`) + **항상 `id` 보조 정렬 추가**(설계 결정 11). `MemberRepositoryImpl` 패턴(BooleanBuilder + `toOrderSpecifiers`) 미러.

### 신규 — 서비스·DTO
- `service/NoticeService.java` — `@Transactional` 쓰기 / `@Transactional(readOnly=true)` 조회.
  - `createNotice(request)`: `requireCurrentAdminUserId()`(null이면 `AccessDeniedException`), title/content 공백 검증, useYn 기본 true, 저장. `@AdminActionLogged` NOTICE_CREATE.
  - `updateNotice(id, request)`: `findByIdAndDeletedFalseForUpdate`로 락 조회(없으면 404), 3필드 모두 null이면 400, 값이 온 title/content는 공백 거부, `update()` 호출. `@AdminActionLogged` NOTICE_UPDATE.
  - `deleteNotice(id)`: `findByIdAndDeletedFalseForUpdate`로 락 조회(없으면 404 — 이미 삭제된 것도 404), `softDelete()` 호출 후 `NoticeResponse` 반환. `@AdminActionLogged` NOTICE_DELETE, `targetIdExpression="id"`.
  - `getNotice(id)`: `findByIdAndDeletedFalse`(락 없음) → `NoticeResponse`(본문 포함). 없으면 404.
  - `getNotices(request, pageable)`: **`pageable.getPageSize() > MAX_PAGE_SIZE(100)`이면 100으로 clamp**(v4, 리뷰 3라운드 #3 — `AdminActionLogQueryService.MAX_PAGE_SIZE` 패턴 미러) 후 `searchNotices` → `NoticePageResponse`(content: `List<NoticeSummaryResponse>`, 본문 제외).
- `dto/request/NoticeCreateRequest.java` — `title`(@NotBlank @Size(max=200)), `content`(@NotBlank @Size(max=10000)), `useYn`(Boolean, 누락 시 true 기본).
- `dto/request/NoticeUpdateRequest.java` — 부분 수정: `title`(@Size(max=200), null 허용), `content`(@Size(max=10000), null 허용), `useYn`(null 허용). 값이 오면 공백 거부·3필드 모두 null이면 400은 서비스에서 처리(설계 결정 4).
- `dto/request/NoticeSearchRequest.java` — `keyword`(@Size(max=200)), `useYn`(Boolean). `@Getter @Setter`(@ModelAttribute 바인딩). **컨트롤러에서 `@Valid @ModelAttribute`로 바인딩**해야 `@Size`가 실제로 실행된다(v4, 리뷰 3라운드 #2 — `AdminMemberController.getAdminMembers()`와 동일 패턴).
- `dto/response/NoticeResponse.java` — `from(Notice)` 정적 팩토리. `id`,`title`,`content`,`useYn`,`authorId`,`createDate`,`updateDate`. (상세·생성·수정·삭제 응답)
- `dto/response/NoticeSummaryResponse.java` — `from(Notice)` 정적 팩토리. `id`,`title`,`useYn`,`authorId`,`createDate`,`updateDate`(content 제외). (목록 전용, 설계 결정 8)
- `dto/response/NoticePageResponse.java` — `AdminMemberPageResponse` 구조 미러(`content: List<NoticeSummaryResponse>`, page, size, totalElements, totalPages, last).

### 신규 — 웹 계층
- `controller/NoticeController.java` — `@RestController`, `/admin/api/notices`, 전 메서드 `@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")`, Swagger 어노테이션.
  - `GET /admin/api/notices` — 목록(페이징/검색), `@PageableDefault(size=20)` + **`@Valid @ModelAttribute NoticeSearchRequest`**(v4 — `@Valid` 누락 시 keyword 길이 검증 미실행) → 200(`NoticePageResponse`, size는 서비스에서 100 clamp)
  - `GET /admin/api/notices/{id}` — 상세 → 200(`NoticeResponse`) / 404
  - `POST /admin/api/notices` — 생성 → 201 + Location(`ServletUriComponentsBuilder`)
  - `PATCH /admin/api/notices/{id}` — 부분 수정 → 200 / 400(전체 필드 누락·공백) / 404 / 409(동시 변경 충돌)
  - `DELETE /admin/api/notices/{id}` — 소프트 삭제(서비스 응답은 버리고) → 204 / 404 / 409
- `controller/NoticePageController.java` — `@Controller` + **`@AdminPage`(필수 — `AdminPageAnnotationConventionTest`)**, `GET /admin/notice/manage` → `admin/notice/manage`.

### 신규 — 템플릿
- `templates/admin/notice/manage.html` — `admin-manage.html` 미러(검색 폼 + 테이블 + 페이지네이션 + 생성/수정/상세/삭제 모달, CSRF 헤더). **모든 title/content 출력 지점에 escapeHtml() 적용**(설계 결정 5). 인라인 `<script>`.

### 수정
- `config/SecurityConfig.java` — line 55 앞에 notice 경로 2개 `hasAnyRole('ADMIN','MANAGER')` 추가 (**인가 정책 변경**).
- `admin/log/constant/AdminActionTypes.java` — NOTICE_CREATE/UPDATE/DELETE 상수 + ALL 등록.
- `src/main/resources/db/migration/V8__create_notice.sql` — 신규(notice 테이블).
- `src/main/resources/db/migration/V9__seed_notice_menu.sql` — 신규(멱등 메뉴 시드, 설계 결정 14).

### 신규 — 테스트
- `NoticeServiceTest` — 생성(author 자동 채움·기본 useYn·author null 시 AccessDenied)·수정(부분 수정 null=기존값, 값 공백 거부, 전체 null 400, 락 조회 사용 확인)·소프트 삭제(deleted=true, 락 조회 사용 확인, NoticeResponse 반환 확인)·조회 404(존재하지 않음/이미 삭제됨 모두)·목록 페이징(요약 DTO 매핑). **size clamp 검증(v5, 리뷰 4라운드 #3 — 계층 정정)**: `getNotices()` 호출 시 size 101(또는 큰 값)을 넘기고 `ArgumentCaptor<Pageable>`로 Repository에 실제 전달된 Pageable을 캡처해 `pageSize==100`·page/sort는 보존됨을 확인, size 100 이하는 원래 Pageable이 그대로 유지되는 경계값도 확인. Mockito.
- `NoticeControllerTest` — `@WebMvcTest(NoticeController.class)` + `MethodSecurityTestConfig` + `GlobalApiExceptionHandler`. CRUD 성공/검증실패(400: 공백·전체 null)/404/409(동시 변경)/미인증(401)/**USER 403** + **ADMIN·MANAGER 각각 200/201/204** 확인. XSS 페이로드(스크립트 태그 포함 title/content) 왕복 확인. `MenuControllerTest` 패턴 미러.
- `NoticeConcurrencyIntegrationTest` — `@SpringBootTest(classes=CmsTestApplication.class)`. `TransactionTemplate` + `CountDownLatch` + **즉시 실패형 락 시도(`jakarta.persistence.lock.timeout=0`)로 타이밍 무관 결정적 검증**(설계 결정 3 v4 참조) + 최종 불변식(삭제된 공지가 되살아나지 않음) 확인. 서비스 테스트(`NoticeServiceTest`)에서 PATCH·DELETE가 락 조회 메서드를 호출하는지도 별도 확인.
- `NoticeRepositoryImplSortTest` — 정렬 화이트리스트 + id 보조 정렬(`MemberRepositoryImplSortTest` 패턴), 소프트 삭제된 행이 모든 검색 조합에서 제외되는지 확인. **(v5 — 리뷰 4라운드 #3) size clamp 검증은 여기 두지 않는다**: Repository를 직접 호출하면 Service의 clamp를 거치지 않아 검증이 무의미하므로 `NoticeServiceTest`로 이관(위 참조).
- `NoticeControllerTest`에 keyword 200자 초과 시 `@Valid` 검증으로 400 케이스 추가.
- `SecurityConfigTest`에 notice 관련 케이스 추가(설계 결정 1 참조).

## 단계별 작업 순서

1. `feat/notice-board` 브랜치 생성.
2. **Flyway** `V8__create_notice.sql`(테이블) → `V9__seed_notice_menu.sql`(멱등 메뉴 시드).
3. **도메인** `Notice.java` → `./gradlew compileJava`.
4. **Repository** Custom/Impl(QueryDSL) + `findByIdAndDeletedFalseForUpdate` → compileJava(Q클래스 생성 확인).
5. **DTO** request 3 + response 3(NoticeResponse, NoticeSummaryResponse, NoticePageResponse).
6. **Service** (`@AdminActionLogged`, 락 조회, requireCurrentAdminUserId) + `AdminActionTypes` 상수 추가 → compileJava.
7. **웹**: `NoticeController` → `NoticePageController`(@AdminPage) → `SecurityConfig` 인가 매처 추가 → compileJava.
8. **템플릿** `manage.html`(escapeHtml 전 출력 지점 적용).
9. **테스트** 작성(Service·Controller·Concurrency·RepositoryImplSort·SecurityConfig) → `./gradlew test` 전체 통과.
10. **실기 검증**(Playwright) — 메뉴는 V9 시드로 이미 등록되어 있으므로 별도 수동 등록 불필요.

## 완료 기준

- `./gradlew test` 전체 통과(신규 테스트 + `AdminActionTypeSyncTest`·`AdminPageAnnotationConventionTest` 회귀 없음).
- 빈 DB `bootRun` 기동 성공(`ddl-auto: validate` 통과 = V8 매핑 정합, V9 멱등 시드 정상 삽입).
- **Playwright 골든 패스**: ADMIN·MANAGER 각각 로그인 → 공지 생성 → 목록·검색·페이징 → 수정 → 상세 → 소프트 삭제 후 목록 미노출 확인.
- **데이터 왕복**: 저장 → DB(또는 API 재조회)로 확인 → 원복.
- **인가 차단**: USER 계정으로 `/admin/api/notices` 직접 호출 시 403, 미인증 401.
- **동시성**: `NoticeConcurrencyIntegrationTest` 통과(PATCH-DELETE 경합 시 삭제 유실 없음).
- **XSS**: 스크립트 태그 포함 공지 생성 후 목록·상세·수정 폼에서 실행되지 않음(브라우저 alert 미발생, Playwright 콘솔 확인).
- **감사 로그**: `AdminActionLog`에 NOTICE_CREATE/UPDATE/DELETE 기록 + targetId 채워짐 확인(로그 관리 화면).
- **사이드바**: V9 시드로 ADMIN·MANAGER 사이드바에 공지 메뉴 자동 노출(수동 등록 불필요).
- 스크린샷 저장(`docs/snapshot/`).

## 기록 (완료 후)

- `CLAUDE.md` 현행화(패키지 구조에 notice 추가, 엔드포인트 목록, 핵심 도메인 모델에 Notice, SecurityConfig 인가 표에 notice 경로).
- `PLAN-notice-board.md`에 구현·검증 결과 갱신 + `plan/README.md`·로드맵 완료 표시.
- 비자명 이슈 발생 시 `docs/troubleshooting.md`.
- 커밋/PR은 사용자 확인 후 `/code-review-loop` → `/commitPR`.

## 리스크

- **인가 정책 변경**(SecurityConfig 2줄) — catch-all `/admin/**` 앞에 배치 순서 중요. (2026-07-20 사용자 승인 완료: ADMIN+MANAGER)
- `ddl-auto: validate` — V8 컬럼 타입(bit(1)↔Boolean, TEXT↔content) 불일치 시 기동 실패. 엔티티-스키마 1:1 대조 필수.
- **V9 멱등 시드**(신규 리스크, v2) — `WHERE NOT EXISTS` 조건이 `menu_url` 유니크 제약에 의존하지 않으므로(메뉴 테이블엔 유니크 제약 없음), 동시 마이그레이션 실행 경합 자체는 Flyway 스키마 락으로 방지되나, 수동으로 동일 URL 메뉴가 이미 있으면(사용자가 임의로 다른 이름으로 등록) 중복 삽입을 건너뛰는지 반드시 재검증.
- QueryDSL Q클래스 미생성으로 인한 컴파일 실패 — 각 단계 후 `compileJava` 확인으로 조기 검출.
