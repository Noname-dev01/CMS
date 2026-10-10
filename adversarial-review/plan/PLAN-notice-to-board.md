# PLAN — 공지사항을 "공지" 게시판으로 흡수 (로드맵 Top 8 ①-2)

> 상태: v6 — 적대적 리뷰 5라운드 ship, **구현·검증 완료, PR #114로 머지(2026-10-09, `9d339c6`)**. 결과는 문서 끝 "구현·검증 결과".
> 선행: ①-1 게시판(#112 `23547ba`, #113 `6e4b37b`) 머지 완료. 후행: ④ 예약 게시(공지가 게시글이 된 뒤 적용).

## 개정 이력

- v1: 최초 작성(정찰 → 쟁점 14개 결정).
- v2 변경(1라운드 반영 — codex gpt-6.1-sol, needs-attention P1×2·P2×3 전부 수용):
  - R1-1(P1 롤백 시 회수 권한 부활): 쟁점 6·5 변경 — V32가 `member_permission`의 NOTICE 행을 게시판 권한으로 **복사 후 삭제(이동)**. 롤백해도 fail-closed. 재구성 SQL 안은 기각(이동이 더 단순하고 카탈로그 제거 시 행 삭제 규약과 일치).
  - R1-2(P1 백업 경합): "배포 순서" 절 신설 — 앱 정지 → 정지 상태 정규 백업 → 신버전 기동. `make prod-backup`만으로는 부족함을 명시.
  - R1-3(P2 이미지 롤백 왕복): 쟁점 4·7 변경 — **V32 이후 앱만 되돌리는 롤백은 미지원**(roll-forward 또는 백업 복원, V22 선례). 이미지 복구 SQL 삭제. 동결 테이블은 대조·안전망으로 유지.
  - R1-4(P2 메뉴 고아): 쟁점 10·5 변경 — 자식이 있으면 삭제 대신 `menu_url=NULL`(그룹 메뉴 보존), 없으면 삭제. 자식 있는/없는 시험 추가.
  - R1-5(P2 재번호 링크 파손): 쟁점 3·리스크 변경 — 재번호 유지(대안 모두 영구 특수 처리 필요, 공지 ID는 이미 퍼진 URL), 단 **배포 전 사전 점검 쿼리와 "게시판 기능 운영 미배포" 조건**을 명시하고 리스크 문구를 "감사 이력 한정"에서 "기존 게시글 URL 파손"으로 정정.
- v3 변경(2라운드 반영 — codex gpt-6.1-sol, 새 P2×2 전부 수용):
  - R2-1(P2 목록 fail-closed 404): 쟁점 8 정정 — `PublicNoticeService.getPublishedNotices`가 `Optional<PublicNoticeListResult>`를 반환하고 `PublicNoticeController.list`가 empty를 기존 `error/404`(상세와 같은 방식)로 응답한다. 게시판 키 없음·비공개·삭제 상태의 GET/HEAD 시험 추가. "컨트롤러 그대로 유지"는 목록 핸들러의 이 분기를 제외한다는 뜻으로 고친다.
  - R2-2(P2 대체 메뉴 부재 시 MANAGER 메뉴 숨김): 쟁점 5⑦·10 변경 — 사이드바는 `menuUrls` 완전 일치로 판정하므로 옛 URL을 카탈로그에 남기지 않고, **`'/admin/board/posts'` 메뉴가 없을 때는 공지 메뉴 행을 `menu_url='/admin/board/posts'`·이름 '게시글 관리'로 전환**한다(`menuUrls` 영구 등록 안은 죽은 URL이 카탈로그에 남아 기각). 대체 메뉴가 있을 때만 삭제/그룹 보존 규칙을 적용. 대체 메뉴 없는 상태에서 이관 후 MANAGER 메뉴 노출·이동 시험 추가. (→ v4에서 R3-2로 규칙을 다시 정리)
- v4 변경(3라운드 반영 — codex gpt-6.1-sol, 새 P2×2 전부 수용):
  - R3-1(P2 동결 NOTICE 이미지 참조가 수동 회수를 막음): 쟁점 5⑤·7 변경 — 이미지 참조는 **복사가 아니라 이동**(POST로 옮기고 `owner_type='NOTICE'` 행 삭제; 롤백 미지원이라 구 행을 남길 이유가 없다). 같은 유형이 첨부에도 있어 확대 적용: 수동 회수 ⑤의 보존 목록에서 동결 `notice_attachment`를 제외(동결 테이블은 대조용, 회수 절차는 참조하지 않음 — 남겨 두면 "행 없는 파일"이 영구 보존). 시험: 이관된 글의 이미지 제거·글 삭제 후 회수 대상에 포함.
  - R3-2(P2 행 존재 ≠ 클릭 가능한 링크): 쟁점 5⑦·10 규칙 재정의 — `N`(공지 메뉴)에 자식이 있으면 `menu_url=NULL`(원래도 그룹이라 링크 없음). `N`이 리프면 **"깨끗한" 게시글 관리 행(활성·자식 없음·상위 체인 활성)이 있을 때만 `N` 삭제**, 아니면 `N`을 게시글 관리 링크로 전환(위치·활성 상태 유지 = 기존 진입 경로 보존). 시험은 MANAGER 사이드바 HTML의 실제 `href="/admin/board/posts"` 존재로 5개 상태 검증.
- v5 변경(4라운드 반영 — codex gpt-6.1-sol, 새 P1×1 수용):
  - R4-1(P1 수동 회수가 V32 성공을 확인하지 않아 이관 전 DB에서 공지 파일을 삭제할 수 있음): 쟁점 4 보강 — 회수 절차의 `ready()` 가드에 `flyway_schema_history`의 `version='32' AND success=1` 확인을 추가한다(미적용·실패·조회 오류면 산출·삭제 전부 중단 — 모든 단계가 `&&` 체인이라 `ready` 실패 시 멈춘다). 이관 전 DB(V31 이하)에는 이전 절차를 쓰도록 문서에 명시. 시험: V31 DB·V32 실패 DB에서 가드가 중단하고 행·파일이 보존됨, V32 성공 DB에서는 R3-1 회수 시나리오 통과.
- v6 변경(5라운드 반영 — codex gpt-6.1-sol, 새 P2×1 수용, P0·P1 없음):
  - R5-1(P2 자식이 있는 공지 메뉴를 무조건 그룹으로 취급하면 MANAGER 진입 링크 소실): 쟁점 5⑦·10 규칙 단순화 — 사이드바는 **보이는 자식이 모두 제거되면 부모 URL을 링크로 쓴다**(`MenuVisibility.java:112`)는 점을 R3-2가 놓쳤다. 이제 자식 유무로 갈라 판단하지 않는다: 깨끗한 대체 행이 **있으면** `N`이 리프일 때 삭제 / 자식이 있을 때 `menu_url=NULL`(MANAGER는 대체 행으로 진입), **없으면** 자식 유무와 무관하게 `N`을 `menu_url='/admin/board/posts'`·`menu_name='게시글 관리'`로 전환(행·계층 보존, 그룹/링크 결정은 렌더러에 맡김). 시험은 자식 구성(없음 / 비활성만 / 활성이 모두 MANAGER 권한 밖 / 보이는 활성 자식) × 대체 행(정상 / 부재 / 비활성 / 자식 있음)의 대표 조합이며 MANAGER 사이드바 HTML의 실제 `href="/admin/board/posts"`를 단언한다.
  - 루프 종료: 5라운드 도달. 남은 지적은 규칙 수정으로 해소되고 리뷰어도 추가 라운드 대신 규칙·시험 확인을 권고했으므로 ship 처리한다(승인 시 메뉴 규칙은 구현 단계에서 시험으로 재확인).

## Context

공지(`Notice`)와 게시글(`Post`)은 거의 같은 모양이다(`useYn`/`deleted` 분리, HTML 본문, `authorId` 스냅샷, 첨부 하드 삭제·글당 5개, 삭제 시 첨부 잔존 409, 행 잠금, 본문 이미지 참조 교체). ①-1이 이 구조를 게시판 소속으로 일반화했으므로 공지를 "공지 게시판의 게시글"로 옮기면 콘텐츠 도메인이 하나가 된다.

**불변 계약**(바뀌면 안 된다): 공개 URL `/notices`, `/notices/{id}`, `/notices/{id}/attachments/{attachmentId}`의 **같은 ID**, 공개 노출 조건(`useYn ∧ ¬deleted`)과 모든 실패의 동일 404, 첨부 스트리밍 계약(3구간 실패 처리·`no-store`), 공개 DTO의 `authorId` 비노출, `SecurityConfig`의 `/notices` GET·HEAD 공개 + 나머지 `denyAll`, 레이트리밋 규칙(`public-notice`·`attachment`).

### 정찰 요약 (읽은 근거)

| 영역 | 사실 | 근거 |
|---|---|---|
| 스키마 | `notice`/`post`, `notice_attachment`/`post_attachment`가 컬럼·인덱스·FK까지 사실상 동일. `post`는 `board_id`가 더해짐 | V8·V10·V23·V29 |
| 서비스 | `NoticeService` ≈ `PostService`(게시판 `FOR SHARE` 잠금만 추가). 첨부·검증·sanitize 정책은 이미 `common/`으로 공용화됨 | `ContentBodyPolicy`·`AttachmentFilePolicy` |
| 공개 | `PublicNoticeService` ≈ `PublicBoardService`(게시판 공개 조건만 추가). 컨트롤러는 스트리밍 코드 복제 | `publicweb/*` CLAUDE.md |
| 권한 | `AdminFeature.NOTICE`가 **유일한 `DELEGABLE`**. `BOARD`는 `BOARD_SCOPED`(`member_board_permission`) | `AdminFeature` |
| 본문 이미지 | `content_image.scope_type`='NOTICE', 참조 `content_image_ref.owner_type`='NOTICE'. 게시판 글은 'BOARD:{id}'·'POST' | V24·V27 |
| 소비처 | 통합 검색 `notices` 섹션, 공개 이미지 판정의 `NOTICE` READ 미리보기, 메뉴 시드 V9, 감사 라벨 `NOTICE_*`, `topbar-search.js`, `notice-editor.js` 기본 업로드 URL | grep 전수 |
| 시험 | 공지 관련 테스트 파일 약 70개. 공지 전용 10개(`admin/notice/**`), 공개 공지 5개, 권한 시험 다수가 `NOTICE`를 `DELEGABLE` 픽스처로 사용 | grep 전수 |

## 쟁점과 결정

### 1. 공지 게시판을 어떻게 식별하나 — `board.board_key` 컬럼 (결정)

`/notices`는 URL에 게시판 ID가 없으므로 "어느 게시판이 공지인가"를 서버가 알아야 한다.
- (a) 설정값 `cms.notice-board-id`: 환경마다 auto-increment ID가 달라 배포마다 어긋남 → 기각.
- (b) 이름 관례("공지사항"): 이름은 ADMIN이 바꿀 수 있고 중복도 허용 → 기각.
- **(c) `board.board_key varchar(30) NULL UNIQUE`, 공지 게시판은 `'NOTICE'`**: 자기 서술적이고 환경 독립. DDL 1문(별도 마이그레이션 V31).
- **스키마 변경 — 사전 고지 대상.**

### 2. 시스템 게시판 보호 — `board_key IS NOT NULL`인 게시판은 삭제·비공개 전환 불가 (결정)

`/notices`가 이 게시판에 의존하므로: 삭제 → 409, `publicYn=false`로 변경 → 400. 이름·`attachmentYn`은 변경 허용(공개 `/notices` 화면 제목은 템플릿 고정 문구, 첨부 끄기는 운영 선택). 게시판이 어떤 경로로든 비공개/삭제 상태면 `/notices`는 fail-closed 404(공개 서비스가 게시판 공개 조건을 재검증).

### 3. 공개 URL의 `{id}` 유지 — 같은 ID로 `post`에 복사하고, 충돌하는 기존 게시글을 재번호 (결정)

- (a) 새 ID 부여 + 매핑 컬럼(`legacy_notice_id`): 신규 글의 ID와 옛 ID가 한 URL 공간에서 겹쳐 모호 → 기각.
- (b) 충돌 시 마이그레이션 실패(fail-fast): 단순하지만 dev DB에 게시글이 있으면 기동 불가, 운영자 수작업 요구 → 기각.
- **(c) 공지 ID를 보존하고, `id <= MAX(notice.id)`인 기존 게시글·게시글 첨부를 `+offset`으로 재번호**: 공지 ID는 외부에 퍼진 URL이고 게시글은 출시 직후(2026-10-08)라 재번호 비용이 작다. offset = `GREATEST(MAX(notice.id), MAX(post.id))`라 충돌 없음. `post_attachment.post_id`·`content_image_ref(POST, id)`를 함께 옮기고 `FOREIGN_KEY_CHECKS=0` 구간에서 수행. 충돌이 없으면(일반적) 아무것도 옮기지 않는다. 감사 로그의 옛 `targetId`는 갱신하지 않는다(이력 — 문서에 명시).
- **(v2, R1-5) 재번호의 실제 영향은 기존 게시글의 공개 URL(`/boards/{b}/posts/{id}`·첨부) 파손**이다. 허용 조건을 명시한다: 게시판 기능은 아직 운영 배포 전이다(`CLAUDE.md` — 실배포는 별도 범위). 배포 전 사전 점검 `SELECT COUNT(*) FROM post WHERE id <= (SELECT MAX(id) FROM notice)`(첨부는 `post_attachment`·`notice_attachment`로 동일)가 0이거나 파손을 허용할 때만 배포한다. 이 쿼리를 `docs/deployment.md` 배포 전 점검에 둔다. (기각한 대안: 외부 ID 매핑 컬럼은 신규·옛 ID가 한 URL 공간에서 겹쳐 영구 특수 처리가 필요하다.)
- 공지 첨부 ID도 같은 방식(`notice_attachment.id` 보존, `post_attachment` 충돌 행 재번호). 시험: 충돌 있는/없는 두 경로 + 이관 후 신규 INSERT가 `MAX+1` 이상(AUTO_INCREMENT).

### 4. 데이터 이관은 "복사 + 구 테이블 동결" (결정)

`notice`·`notice_attachment`는 **지우지 않고** 동결(이관 시점 스냅샷, 대조·안전망)하며 DROP은 후속 PR로 미룬다. 앱의 `Notice*` 엔티티·리포지토리는 이번 PR에서 삭제(DB validate는 매핑된 엔티티만 본다). 첨부 파일은 이동하지 않고 `storage_key`만 같은 값으로 `post_attachment`에 복사한다. (v4 R3-1) 동결 `notice_attachment`는 **대조용 스냅샷일 뿐 회수 절차가 참조하지 않는다** — `docs/deployment.md` 수동 회수 ⑤의 보존 목록에서 `notice_attachment`를 제외하고(남기면 삭제 실패로 남은 "행 없는 파일"이 영구 보존된다), ②의 공지 참조 조건도 제거한다(V32가 `NOTICE` 참조를 지움). **(v5 R4-1) 이 변경은 V32가 성공한 DB에서만 안전하다** — 이관 전 DB에서 새 절차를 실행하면 유효한 공지 이미지·첨부가 "미참조"로 판정되어 SQL 오류 없이 삭제된다(`post` 테이블은 V29부터 있어 기존 가드가 못 막는다). 그래서 `ready()` 가드가 `flyway_schema_history`에서 `version='32' AND success=1`을 확인하고, 아니면 산출·삭제 전체를 중단한다. 이관 전 DB에는 이전 절차를 사용한다(문서에 명시).
- **(v2, R1-3) 롤백 정책: V32 이후 앱만 이전 버전으로 되돌리는 롤백은 지원하지 않는다**(V22 선례). 권한(R1-1)·본문 이미지 출처(R1-3)·롤백 중 신규 공지 쓰기가 서로 얽혀 안전한 왕복 절차를 만들 수 없다. 복구 수단은 ① 수정 버전 배포(roll-forward) ② **정지 상태에서 만든 이관 전 정규 백업의 복원**(`docs/migration-guide.md`에 V22와 같은 형식으로 기록; 복원 시 V31·V32가 다시 실행되지 않도록 V30 이하 이미지로 컨테이너를 먼저 교체)뿐이다.

### 5. 마이그레이션 구성 (결정)

- `V31__add_board_key.sql` — DDL(`ALTER TABLE board ADD COLUMN board_key ..., ADD UNIQUE`). DDL 단독.
- `V32__absorb_notice_into_board.sql` — **순수 DML 한 트랜잭션**(부분 실패 시 롤백): ① 공지 게시판 INSERT(`board_key='NOTICE'`, 이름 '공지사항', 공개·첨부 허용; 이미 있으면 건너뜀) ② 충돌 재번호 ③ `notice` → `post` 복사(ID·날짜 보존) ④ `notice_attachment` → `post_attachment` 복사 ⑤ 본문 이미지: `content_image`의 `scope_type='NOTICE'` → `'BOARD'` + `scope_id=공지게시판`, `content_image_ref`의 `NOTICE` 참조를 `POST`로 **이동**(복사 후 `owner_type='NOTICE'` 행 삭제 — v4 R3-1: 동결 참조를 남기면 이관된 글에서 이미지를 지워도 수동 회수가 "사용 중"으로 판정해 업로드 상한을 영구 점유) ⑥ 권한(v2 R1-1: 이동): `member_permission`의 NOTICE 행(정확 일치 `BINARY`·READ가 있는 MANAGER·DELETED 제외, V19와 같은 기준)을 `member_board_permission`으로 복사하고 해당 회원의 `permission_version`을 +1(낡은 권한 화면의 PUT이 이관된 권한을 조용히 회수하지 못하게 409로 막음)한 뒤 **`member_permission`의 NOTICE 행을 전부 삭제**한다(변형·READ 없는 쓰기 행 포함 — 카탈로그에서 기능을 제거할 때 행 삭제 마이그레이션을 함께 쓰는 규약; 남겨 두면 롤백 시 회수 권한이 부활한다) ⑦ 메뉴(v2 R1-4, v3 R2-2, v4 R3-2, v6 R5-1): `N` = `menu_url='/admin/notice/manage'` 행. **"깨끗한" 게시글 관리 행**(`menu_url='/admin/board/posts'`, 활성, 자식 없음, 상위 체인(최대 2단계)이 모두 활성)이 **있으면** — `N`이 리프면 삭제, 자식이 있으면 `menu_url=NULL`(행·계층 보존; MANAGER는 대체 행으로 진입). **없으면** — 자식 유무와 무관하게 `N`을 `menu_url='/admin/board/posts'`·`menu_name='게시글 관리'`로 전환한다(위치·활성 상태·계층 유지 → 렌더러가 "보이는 자식이 있으면 그룹, 없으면 부모 URL 링크"로 처리하므로 기존 진입 경로가 보존된다).
- 멱등성: Flyway가 한 번만 실행하므로 재실행 안전성은 요구하지 않되, 중간 실패는 트랜잭션 롤백으로 원상태.
- **스키마/데이터 변경 — 사전 고지 대상.**

### 6. NOTICE 기능 권한 → 게시판 권한 (사용자 결정 A, 2026-10-09)

`AdminFeature.NOTICE`를 카탈로그에서 제거한다. 결과로 `DELEGABLE`이 0개가 되어 `member_permission` 경로(기능 단위 위임)는 코드는 남지만(②배너가 곧 사용) 실제 기능 픽스처가 사라진다.
- 시험: `DELEGABLE` 전용 케이스는 `BOARD_SCOPED` 대응 케이스로 옮기거나 삭제하고, **삭제한 케이스 목록과 공백 사실을 구현·검증 결과에 기록**한다(② 도입 때 복구).
- `member_permission`의 NOTICE 행은 V32가 게시판 권한으로 옮기며 삭제한다(쟁점 5 ⑥, v2 R1-1). 시험: 이관 후 NOTICE 행 0건·게시판 권한 행 일치·"권한 회수 후에는 어떤 경로로도 부활하지 않음".

### 7. 본문 이미지 — 출처를 'BOARD:공지게시판'으로 전환 (결정)

공지 편집기도 게시글 화면의 `setUploadUrl` 경로(`/admin/api/boards/{noticeBoardId}/content-images`)를 쓰므로 공지 전용 업로드(`NoticeContentImageController`·`uploadForNotice`)·`existsPublishedNoticeRef`·공개 이미지 판정의 `NOTICE` READ 미리보기를 삭제한다(게시판 READ 분기가 대신한다). 이미지 행을 'BOARD'로 바꾸므로 이전 앱(공개 판정이 `scope_type='NOTICE'` 요구)으로는 공지 이미지가 비공개가 된다 — 앱만 되돌리는 롤백은 쟁점 4에 따라 미지원이라 복구 SQL은 두지 않는다(v2 R1-3). `notice-editor.js`의 기본 업로드 URL(삭제될 엔드포인트)은 제거하고 `uploadUrl` 미설정이면 이미지 업로드를 비활성화한다.

### 8. 공개 `/notices` — 컨트롤러·템플릿·DTO 유지, 서비스만 게시판 서비스에 위임 (결정)

`PublicNoticeController`(목록 핸들러의 아래 404 분기 제외)·`public/notice/*`·`PublicNotice*` DTO와 레이트리밋·`SecurityConfig` 규칙은 그대로 둔다(계약·기존 시험 보존). `PublicNoticeService`가 공지 게시판 ID를 `board_key`로 조회해(`BoardRepository.findIdByBoardKey`, 요청당 인덱스 조회 1회) `PublicBoardService`의 공개 조회를 호출하고 결과를 기존 공지 DTO로 변환한다. **(v3 R2-1) 게시판 키 없음·비공개·삭제면 `PublicBoardService`가 `Optional.empty()`를 주므로** `getPublishedNotices`도 `Optional<PublicNoticeListResult>`를 반환하고 `PublicNoticeController.list`가 empty를 기존 `error/404`로 응답한다(빈 목록 200이나 예외 500으로 번역하지 않는다). 상세·첨부는 이미 empty → 404다. 공개 불변식의 원천이 `PublicBoardService` 하나가 된다. 중복 노출(`/boards/{공지게시판ID}`)은 같은 불변식이라 허용한다(별도 리다이렉트는 범위 밖).

### 9. 관리자 화면·API — `/admin/notice/manage`는 302 리다이렉트, `/admin/api/notices/**`는 삭제 (결정)

`GET /admin/notice/manage[?id=]` → `/admin/board/posts?boardId={공지게시판}[&id=]`(북마크·옛 검색 링크 호환). `NoticeController`·`NoticeAttachmentController`·`NoticeContentImageController`·`notice/manage.html`(1132줄)·`Notice*` 서비스/DTO/리포지토리는 삭제. 외부 소비자는 없다(관리 화면 JS만 호출).
- **인가 정책 변경 — 사전 고지 대상**: `NOTICE`의 게이트(`/admin/notice/**`, `/admin/api/notices/**`)가 사라지고, 리다이렉트용 `/admin/notice/manage` 정확 경로 1개를 `BOARD` 게이트(기능 단위 READ = "어느 게시판이든 조회 권한")에 추가한다. 나머지 두 패턴은 `/admin/**` ADMIN 캐치올로 떨어진다(MANAGER 403 유지, ADMIN은 404).

### 10. 메뉴 — 공지 메뉴 행 삭제, 게시글 관리 메뉴가 대체 (결정)

V30이 시드한 '게시글 관리'가 깨끗하게 있으면 V9의 '공지사항 관리' 행은 중복이다. 단 `menu.up_menu_no`에는 FK가 없어 자식이 있는 행을 지우면 자식이 루트에서 도달 불가가 된다(R1-4). 사이드바는 `menuUrls` 완전 일치로 판정하고(R2-2) 비활성·자식 있는 행은 링크가 되지 못하며(R3-2), 반대로 보이는 자식이 없는 부모는 자기 URL이 링크가 된다(R5-1). 그래서 대체 행이 "깨끗하지 않으면" `N`을 지우지 않고 게시글 관리 링크로 전환하고, 깨끗하면 `N`이 리프일 때만 삭제하며 자식이 있으면 URL만 비운다. 규칙은 쟁점 5⑦. 시험(MANAGER 사이드바 HTML에 실제 `href="/admin/board/posts"` 존재 확인): 자식 구성(없음 / 비활성만 / 활성이 모두 MANAGER 권한 밖 / 보이는 활성 자식) × 대체 행(정상 / 부재 / 비활성 / 자식 있음)의 대표 조합.

### 11. 통합 검색 — `notices` 섹션 삭제, 공지는 `posts` 섹션에 (결정)

`AdminSearchService`/`AdminSearchResponse.NoticeItem`/`topbar-search.js`의 공지 섹션 삭제. 공지 게시판 글은 게시글 섹션에 게시판 이름 '공지사항'과 함께 나온다(권한: 그 게시판 READ). 기존 `NOTICE:READ` 판정 분기 삭제.

### 12. 감사 로그 — `NOTICE_*` 상수·라벨 유지, 신규 행위는 `POST_*` (결정)

과거 로그 표시를 위해 상수와 `admin/log/manage.html` 라벨을 남기고 "과거 이력 표시용" 주석을 단다. 동기화 시험(`AdminActionTypes` ↔ 템플릿)은 그대로 통과한다. 공지 게시판에서의 새 행위는 `POST_*`로 남는다(이관 전 `targetType=NOTICE` 이력과 구분됨 — 문서화).

### 13. 공지 게시판 시드 이름·초기 상태 (결정)

이름 '공지사항', `public_yn=1`, `attachment_yn=1`(이전 공지는 첨부를 항상 허용), `board_key='NOTICE'`. 공지가 0건인 DB에서도 게시판은 생성한다(`/notices`가 빈 목록으로 동작).

### 14. 시험 이관 계획 (결정)

| 대상 | 처리 |
|---|---|
| `admin/notice/**` 10개 중 `NoticeContentHtmlMigrationTest`(V22→V25, JDBC) | 유지(엔티티 미사용 확인) |
| 나머지 9개(컨트롤러·서비스·리포지토리·동시성·트랜잭션) | 게시글 대응 시험 존재 여부를 파일별로 대조해 **없는 케이스만 `Post*`로 이식** 후 삭제(공백 목록 기록) |
| 공개 공지 5개(`PublicNoticeControllerTest`·`ServiceTest`·`AttachmentIntegrationTest`·`PublicAttachmentStreamingServerTest`·템플릿 규약) | **같은 단언으로 유지**하고 시드만 `Notice` → 공지 게시판 `Post`로 교체 — "기존 공개 공지 계약 동일" 증거 |
| `DELEGABLE` 픽스처 의존 권한 시험(`PermissionCacheTest`·`AdminPermissionEvaluatorTest`·`MemberPermissionServiceTest` 등) | 쟁점 6 규칙대로 `BOARD_SCOPED`로 이전 또는 삭제 + 공백 기록 |
| 신규 | `NoticeAbsorbMigrationTest`(V31+V32: 건수·ID·첨부 바이트·공개상태·권한(NOTICE 행 0건·게시판 권한 일치)·이미지(NOTICE 참조 0건·이동 후 글 수정/삭제 → 회수 대상 포함)·메뉴 5상태·충돌 재번호·AUTO_INCREMENT), 시스템 게시판 보호(삭제 409·비공개 400), `/admin/notice/manage` 리다이렉트·권한, 공개 `/notices` 신규 글 ID 연속성 |

## 작업 단계 (구현 순서)

1. 마이그레이션 V31·V32 + `NoticeAbsorbMigrationTest`(이관 정합 먼저 고정).
2. 도메인/서비스: `Board.boardKey`, 시스템 게시판 보호, `BoardRepository.findIdByBoardKey`.
3. 공개: `PublicNoticeService` 위임 전환 + 공개 시험 시드 교체(계약 동일 확인).
4. 본문 이미지: 공지 전용 경로 삭제, 공개 판정 정리, 편집기 기본 URL 제거.
5. 권한: `AdminFeature.NOTICE` 제거, 게이트 조정, 검색 섹션 삭제, 시험 이전.
6. 관리자: 리다이렉트 컨트롤러, `Notice*` 삭제, 로그 상수 주석, 메뉴.
7. 전체 시험 → dev 실기(Playwright) → 문서(CLAUDE.md 지도, `docs/deployment.md`·`migration-guide.md`, 로드맵은 `/updateRoadmap`).

## 배포 순서 (v2, R1-2)

`make prod-backup`은 앱을 멈추지 않고 DB 덤프 뒤 파일 볼륨을 압축하므로, 그 사이 첨부 삭제가 커밋되면 복원 시 행은 있고 파일이 없다(`docs/deployment.md` 정규 백업 절의 이유). 이관은 되돌리기 어려우므로 다음 순서를 `docs/deployment.md`에 명시한다.
1. 사전 점검: `SELECT COUNT(*) FROM post WHERE id <= (SELECT MAX(id) FROM notice)` 등 충돌 점검(쟁점 3).
2. 앱 정지(쓰기 종료 확인) → **정지 상태에서 정규 백업**(DB + 파일 볼륨) → 신버전 기동(V31·V32 실행).
3. 기동 후: 공지 건수·`/notices` 샘플 ID·MANAGER 권한을 이관 전 기록과 대조.

## 리스크

- **데이터 이관(되돌리기 어려움)**: V32는 한 트랜잭션이라 중간 실패는 롤백되지만 성공 후 복구는 정지 상태 백업 복원뿐이다(앱만 롤백 미지원, 쟁점 4).
- **재번호의 연쇄 영향(v2 정정)**: 충돌 시 기존 게시글의 공개 URL·첨부 URL이 바뀌고 감사 로그의 옛 `targetId`도 어긋난다. 게시판 기능이 운영 미배포라는 조건과 사전 점검 쿼리로 통제한다(쟁점 3).
- **권한 시험 공백**: `DELEGABLE` 실기능이 없는 기간(②까지) — 기록하고 ②에서 복구.
- **이중 노출**: 공지가 `/notices`와 `/boards/{id}` 양쪽에 보인다(같은 불변식).
- **회귀 범위가 넓다**: 공개 계약은 기존 시험의 단언 불변으로, 권한은 `BoardPermissionIntegrationTest`로 방어.

## 구현·검증 결과 (2026-10-09)

### Context

계획 v6(적대적 리뷰 5라운드 ship)의 승인 범위를 그대로 구현했다. 브랜치 `feat/notice-to-board`. 스키마(V31·V32)와 인가 정책(`/admin/notice/manage` 리다이렉트 게이트·옛 공지 게이트 제거)은 사전 고지·승인(2026-10-09).

### 핵심 확정 사항 (구현 중 계획과 달라진 점 포함)

- 계획과 달라진 결정 없음. 구현 중 확정한 세부: ① `NoticeRedirectController`는 `@RestController`+302 `ResponseEntity`(사이드바 모델 주입 불필요, `@AdminPage` 미부착) ② 공개 DTO는 새로 만들지 않고 게시판 DTO(`PublicBoardListResult`·`PublicPostDetail` 등)를 재사용(템플릿 모델 이름·URL 불변) ③ `PublicNoticeService.getPublishedNotices`는 `Optional` ④ 시스템 게시판 보호는 `Board.isSystem()`(삭제 409·비공개 400) ⑤ V32의 메뉴 규칙은 "깨끗한 대체 행" 분기(R5-1 반영)로 SQL 변수 `@clean_posts_menu` 하나.
- 과거 마이그레이션 시험 3종(`MemberPermissionMigrationTest`·`RolePermissionDropMigrationTest`·`BoardMigrationTest`)은 NOTICE 행·공지 출처를 다루므로 **V31(또는 V30)까지만 적용**하도록 고쳤다(프로젝트 관례 — 특정 버전 직전까지).

### 구현 파일

- 스키마: `V31__add_board_key.sql`, `V32__absorb_notice_into_board.sql`
- 도메인·서비스: `Board`(`boardKey`·`isSystem`·`NOTICE_KEY`), `BoardRepository.findIdByBoardKey`, `BoardService`(시스템 게시판 보호·`getNoticeBoardId`), `NoticeRedirectController`
- 공개: `PublicNoticeService`(어댑터), `PublicNoticeController`(목록 404 분기·게시판 DTO), `PublicContentImageService`(공지 분기 삭제), 공지 전용 DTO 6종 삭제
- 삭제: `com.cms.admin.notice` 전체(엔티티·리포지토리·서비스·컨트롤러·DTO·`CLAUDE.md`), `NoticeContentImageController`, `templates/admin/notice/manage.html`, `ContentImageService.uploadForNotice`·`OWNER_NOTICE`·`SCOPE_NOTICE`, `ContentImageRefRepository.existsPublishedNoticeRef`
- 권한·검색·화면: `AdminFeature`(NOTICE 제거, BOARD 게이트에 `/admin/notice/manage`), `AdminSearchService`·`AdminSearchResponse`(공지 섹션 삭제), `topbar-search.js`·`topbar.html`, `notice-editor.js`(기본 업로드 URL 제거)
- 문서: 루트 `CLAUDE.md`, `admin/board`·`admin/permission`·`admin/contentimage`·`config`·`publicweb/notice`·`publicweb/board`의 `CLAUDE.md`, `docs/deployment.md`(배포 순서·사전 점검·`ready()` V32 가드·회수 ②⑤), `docs/migration-guide.md`(V31~V32)
- 시험(신규): `NoticeAbsorbMigrationTest`(12), `PublicBoardServiceTest`, `PostServiceTest`, `PostAttachmentServiceTest`, `PostControllerTest`, `PostAttachmentControllerTest`, `PostRepositoryDataJpaTest`, `PostRepositoryImplSortTest`, `PostConcurrencyIntegrationTest`, `PostAttachmentTransactionIntegrationTest` / (이식·갱신) `PublicNotice*`(서비스·컨트롤러·첨부 통합·스트리밍), `AdminPermissionMatrixIntegrationTest`(공지 게시판 대상), `ContentImageIntegrationTest`, `AdminSearch*`, 권한 시험 다수, `SecurityConfigTest`, 메뉴 시험

### 검증 결과

- **전체 `./gradlew cleanTest test`**: 1608건 실패 0(건너뜀 11 — 로컬 Windows 전용, 이전과 같음), BUILD SUCCESSFUL.
- **이관 정합(`NoticeAbsorbMigrationTest`, 실제 MariaDB 10.11)**: 공지·첨부 같은 ID·플래그·날짜·바이트, 충돌 재번호와 첨부·이미지 참조 연결 유지, AUTO_INCREMENT 이어짐, 이미지 출처·참조 이동(NOTICE 참조 0건), 권한 이동(READ 있는 활성 MANAGER만·버전+1·NOTICE 행 0건), 메뉴 6상태, 회수 가드 SQL(V31 DB 0건·최신 1건).
- **실기(dev Docker MariaDB·앱, Playwright + curl)**: 구버전(V30) 앱에서 공개 공지(이미지·첨부 포함)·비노출 공지(첨부)·자료실 게시판과 글(ID가 공지 ID 범위와 충돌)을 만든 뒤 DB 백업(scratchpad `dev-cms-before-v31.sql`) → 새 코드로 재빌드 → `now at version v32`. ① DB: 공지 게시판 #8 생성, 공지 34건 → 게시글 34건(같은 ID), 충돌한 자료실 글 3 → 56624 재번호와 첨부(2 → 62)·이미지 참조 동반 이동, 이미지 출처 `BOARD:8`/`BOARD:7`, MANAGER 3명의 NOTICE 행 → 공지 게시판 4동작, NOTICE 행 0건, '공지사항 관리' 메뉴 삭제·'게시글 관리' 유지 ② **공개 응답 이관 전후 비교**: `/notices` 목록·`/notices/56620` 상세·첨부 다운로드 본문이 **바이트 동일**, 비노출 공지·첨부는 이전과 같은 404, 이미지 200, 비숫자·없는 ID 404 ③ 관리: `/admin/notice/manage?id=56620` → 302 → 공지 게시판 게시글 화면에서 상세 모달 자동 오픈(첨부 목록 포함), 사이드바에 '공지사항 관리' 없음·'게시글 관리' 있음, 새 공지 생성 201(ID 56625로 이어짐)·`/notices/56625` 200, 공지 게시판 삭제 409·비공개 전환 400, 옛 `/admin/api/notices` ADMIN 404, 통합 검색 `posts` 섹션에 결과 ④ 권한관리 화면(스크린샷 `.playwright-mcp/notice-to-board/02-permission-screen.png`): 기능 표에 위임 가능 행이 없고 게시판별 권한 표에 '공지사항'이 4동작 체크로 표시 ⑤ 앱 로그 예외 0건. 검증 데이터(공지·자료실·첨부)는 검증 뒤 API로 삭제했고 dev DB는 V32 상태로 남는다.
- **실기로 확인하지 못한 것**: MANAGER 실제 로그인 화면(dev MANAGER 비밀번호를 몰라 로그인하지 않음) — 접근 매트릭스·사이드바·리다이렉트·권한 이동은 `AdminPermissionMatrixIntegrationTest`·`NoticeAbsorbMigrationTest`·`MenuExposureSidebarIntegrationTest`(실제 스택 MockMvc)로만 검증했다.

### 이슈

- 첫 전체 시험에서 19건 실패 — 전부 NOTICE 픽스처·공지 경로를 쓰던 시험의 기대값이었다(코드 결함 아님): 과거 마이그레이션 시험의 적용 상한, 메뉴·사이드바·알림 문구·보안 설정·규약 시험. 이식 후 전체 통과.
- Windows Git Bash의 `curl.exe`는 `/c/...` 경로와 비 UTF-8 인자를 처리하지 못해 실기 데이터 생성이 두 번 실패했다 — 상대 경로와 UTF-8 JSON 파일(`--data-binary @file`)로 해결(제품 문제 아님).

### 시험 공백 (A안 결정의 결과 — 계획 쟁점 6)

`DELEGABLE` 기능이 0개가 되어 기능 단위 위임(`member_permission`) 경로의 실제 기능 픽스처가 없다. 다음은 `BOARD_SCOPED` 판으로 옮기거나 삭제했다: `AdminPermissionEvaluatorTest`(기능 행 + READ 의존 진리표는 `BOARD` 기능 단위 판정으로 대체, `grantedActionKeys` 진리표는 빈 집합 계약으로 축소), `PermissionCacheTest`(기능 행 로드 케이스 → 게시판 행), `MemberPermissionServiceTest`(`replace_*` 5건 삭제 — 같은 규칙의 게시판 판은 `MemberPermissionServiceBoardTest`), `MemberPermissionApiIntegrationTest`·`MemberPermissionConcurrencyIntegrationTest`·`MemberRoleChangePermissionIntegrationTest`(공지 게시판 권한으로 이식), `AdminSidebarAdviceSnapshotTest`(`myPermissions` 빈 집합). **②배너 등 다음 DELEGABLE 기능이 들어오면** 위 기능 단위 시험을 되살린다.

### 후속

- 동결 테이블 `notice`·`notice_attachment` DROP(롤백 창 이후 별도 PR).
- 공지 공개 화면(`public/notice/*`·`notice.css`)과 게시판 공개 화면(`public/board/*`·`board.css`)의 중복 정리·스트리밍 컨트롤러 복제 해소(별도 리팩터링).
- ④ 예약 게시: ①-2 완료로 선행 조건 충족.
