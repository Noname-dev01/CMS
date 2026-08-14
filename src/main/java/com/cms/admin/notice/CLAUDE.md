# CLAUDE.md — com.cms.admin.notice

이 디렉터리(관리자 공지사항 도메인) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 공개(비로그인) 공지 페이지는 `com.cms.publicweb.notice`의 `CLAUDE.md` 참조.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## Notice (공지사항, 2026-07-20 구현 완료 — 첫 콘텐츠 도메인)

- 필드: `title`(200자)·`content`(TEXT, 최대 10,000자)·`useYn`(노출 여부)·`deleted`(소프트 삭제)·`authorId`(작성 시점 로그인 userId 문자열 스냅샷, member FK 아님) — 5개 컬럼 모두 NOT NULL
- `useYn`(노출)과 `deleted`(소프트 삭제)는 **별도 컬럼**이다 — 메뉴처럼 하나로 겸치지 않는다. 목록·상세·수정·삭제는 항상 `deleted=false` 필터
- **PATCH·DELETE는 비관적 락**(`NoticeRepository.findByIdAndDeletedFalseForUpdate` — 명시적 `@Query` + `@Lock(PESSIMISTIC_WRITE)`, `MenuRepository.findByIdForUpdate`와 동일 패턴)으로 직렬화한다. 락 없이는 DELETE 커밋 후 먼저 읽은 PATCH가 삭제 상태를 되돌리는 lost update가 발생한다. `DELETE`도 `NoticeResponse`를 반환(컨트롤러가 버리고 204) — `AdminActionLogAspect`가 반환 객체 getter에서만 targetId를 추출하므로 `void`면 감사 로그 targetId가 항상 null이 된다(`MenuService.deactivateMenu()`와 동일 이유)
- 목록(`GET /admin/api/notices`)은 `NoticeSummaryResponse`(본문 제외), 상세·생성·수정·삭제는 `NoticeResponse`(본문 포함) — 목록 응답의 JSON 직렬화·전송량만 줄이며, QueryDSL 조회 자체(DB에서 content 읽기)는 줄지 않는다
- 목록 페이지 크기는 100으로 clamp(`NoticeService.MAX_PAGE_SIZE`, `AdminActionLogQueryService`와 동일 패턴)
- 인가는 `ROLE_ADMIN`·`ROLE_MANAGER` 공용(작성자별 소유권 없음 — 누구나 서로 수정·삭제 가능). 사이드바 메뉴는 `V9__seed_notice_menu.sql`로 **멱등(WHERE NOT EXISTS) 자동 등록** — 다른 메뉴처럼 API 수동 등록이 아님(신규 환경마다 등록을 빠뜨리는 결함 방지)
- 상세 설계 결정·적대적 리뷰 5라운드 기록은 `adversarial-review/plan/PLAN-notice-board.md` 참조
- **첨부파일 구현 완료** (`NoticeAttachment`, `NoticeAttachmentService`, 2026-07-22): 첨부는 `deleted` 컬럼 없이 하드 삭제(개별 DELETE = 행+파일 제거). 첨부 업로드·삭제는 모두 notice의 기존 비관적 락(`findByIdAndDeletedFalseForUpdate`)을 재사용해 동일 notice의 첨부 개수 상한(5개)·동시 삭제 경합을 직렬화한다. **notice 삭제 시 첨부가 남아있으면 409로 차단**된다(`NoticeService.deleteNotice()`) — 관리자가 첨부를 먼저 모두 삭제해야 notice를 소프트 삭제할 수 있어, 소프트 삭제된(=API로 영원히 도달 불가능해지는) notice에 딸린 첨부가 영구 오펀이 되는 상황을 원천 차단한다. 허용 확장자 pdf/doc(x)/xls(x)/ppt(x)/hwp/txt/csv/zip/png/jpg/jpeg/gif, 파일당 10MB·공지당 5개 상한. 확장자+선언 Content-Type 화이트리스트 검증(매직바이트 검사 없음 — Tika 등 신규 의존성 도입 안 함, 스푸핑 방어 수단이 아님을 인지하고 저장 루트를 웹 루트 밖에 두고 다운로드를 `octet-stream`+`attachment`+`nosniff`로 강제하는 것으로 보완). 파일 I/O는 `TransactionSynchronizationManager`로 DB 트랜잭션과 동기화(업로드 실패/롤백 시 파일 정리, 삭제는 커밋 후에만 파일 제거). 상세 설계 결정·적대적 리뷰 5라운드 기록은 `adversarial-review/plan/PLAN-notice-attachment.md` 참조
