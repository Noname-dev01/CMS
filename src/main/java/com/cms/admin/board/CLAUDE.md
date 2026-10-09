# CLAUDE.md — com.cms.admin.board

이 디렉터리(범용 게시판) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 설계 결정·적대적 리뷰 기록은 `adversarial-review/plan/PLAN-board.md`(PR A = 게시판 정의 + 게시판별 권한, PR B = 게시글·첨부·본문 이미지·공개·검색·게시판 삭제). 공개 측은 `com.cms.publicweb.board`의 `CLAUDE.md`.

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## 게시판 정의 (PR A, 2026-10-08)

- **게시판**(`Board`, V26): `name`(100자, 공백 거부, 중복 허용 — 화면은 `#id`를 함께 보인다)·`publicYn`(공개 사이트 노출)·`attachmentYn`(**새** 첨부 업로드 허용 — 끄면 새 업로드만 400이고 기존 첨부의 조회·다운로드·삭제는 그대로)·`deleted`(소프트 삭제). 정렬은 id 순.
- **API·화면은 ADMIN 전용**: `GET·POST /admin/api/boards`, `GET·PATCH·DELETE /admin/api/boards/{boardId}`, 화면 `GET /admin/board/manage`(`@AdminPage`). 카탈로그 게이트 밖이라 `/admin/**` ADMIN 캐치올이 막고 핸들러도 `hasRole('ADMIN')`. 메뉴는 V28 시드(`BOARD_ADMIN` = ADMIN_ONLY라 MANAGER 사이드바에 없다).
- **게시판 삭제**(`DELETE /admin/api/boards/{boardId}`, PR B에서 **게시글 검사와 함께** 도입 — 검사 없는 삭제가 먼저 배포되면 PR B → PR A 롤백 중 게시글이 있는 게시판을 지울 수 있다, 리뷰 R1-5): 게시판 행 `FOR UPDATE` → 살아 있는(`deleted=0`) 게시글이 있으면 **409**(노출 여부 무관) → 소프트 삭제 + 그 게시판의 `member_board_permission` 행 전부 삭제(`deleteByBoardId` 벌크). 지운 행이 있으면 `PermissionChangedEvent(null)`(캐시 무효화는 전역 generation이라 회원 ID를 쓰지 않는다). 권한 버전은 올리지 않는다 — 낡은 권한 화면의 PUT은 게시판 존재 확인에서 400. 감사 `BOARD_DELETE`. 삭제된 게시판의 행은 테이블에 남고 복원 API는 없다.
- **게시판 행 잠금 규칙**: 수정·삭제는 `findByIdAndDeletedFalseForUpdate`(PESSIMISTIC_WRITE). 권한 저장은 부여할 게시판들을 `findAllByIdInForShare`(PESSIMISTIC_READ, id 순), **게시글 생성은 그 게시판 한 행을 `findByIdAndDeletedFalseForShare`(PESSIMISTIC_READ)**로 잠가 게시판 삭제(`FOR UPDATE`)와 직렬화한다 — 삭제 검사 직후 생기는 고아 게시글이 없다. 잠금 순서는 권한 PUT이 회원 → 게시판, 게시판 삭제가 게시판 → 권한 행이라 순환이 없다. 시험은 `AbstractBoardDeleteRaceTest`(ON·OFF 두 서브클래스, 아래).
- **내 게시판 목록**(`GET /admin/api/members/me/boards`, `MyBoardController`, `hasAnyRole('ADMIN', 'MANAGER')`): 기존 ALWAYS `MY_INFO` 게이트 안이라 `SecurityConfig` 변경 없음. ADMIN은 미삭제 게시판 전부·전 동작, MANAGER는 READ가 유효한 게시판만 + 게시판별 허용 동작(쓰기는 같은 게시판 READ 의존). 게시글 관리 화면의 게시판 선택·버튼 표시용이며 서버 판정을 대신하지 않는다.
- 게시판별 권한의 판정·저장·캐시는 `com.cms.admin.permission`의 `CLAUDE.md` "게시판별 권한".

## 게시글·첨부 (PR B, 2026-10-08)

- **게시글**(`Post`, V29): 공지(`Notice`)와 같은 구조 — `useYn`(노출)·`deleted`(소프트 삭제)가 별도, 본문은 정리된 HTML(MEDIUMTEXT), `authorId`는 작성자 userId 스냅샷, `boardId`는 plain `Long`(연관관계 매핑 없음, DB FK RESTRICT). 인덱스 `(board_id, deleted, use_yn, create_date)`. 본문 검증은 공지와 같은 `ContentBodyPolicy`(형식 표식 `contentFormat=HTML` 필수·보이는 글자 10,001·정리 HTML 200,000바이트·이미지만 있는 본문 허용).
- **관리 API**(`PostController`, `/admin/api/boards/{boardId}/posts`): 목록·상세 = `READ`, 생성 = `CREATE`, 수정 = `UPDATE`, 삭제 = `DELETE`. **전부 `@RequireBoardPermission`이고 경로 변수 이름은 반드시 `boardId`다**(다르면 SpEL이 null → 403). 목록은 `keyword`(제목)·`useYn`·페이지 크기 100 clamp·정렬 화이트리스트 + `id` 보조 정렬(`PostRepositoryImpl`, QueryDSL — 게시판 소속 조건이 항상 붙는다).
- **IDOR**: 게시글 조회는 `(postId, boardId)`, 첨부 조회는 `(attachmentId, postId)` 조합이라 다른 게시판 경로로 접근하면 ADMIN도 같은 404다. 권한이 없는 게시판은 존재 여부와 무관하게 403(판정기가 대상 존재를 보지 않는다).
- **잠금**: 생성 = 게시판 `FOR SHARE`, 수정·삭제·첨부 업로드·첨부 삭제 = 게시글 `FOR UPDATE`(첨부 개수 상한 5개 검사와 동시 삭제 경합을 직렬화) 후 게시판 미삭제 확인(삭제됐으면 404). 게시글 삭제는 **첨부가 남아 있으면 409**(공지와 같은 오펀 방지) — 첨부 삭제는 `UPDATE`라 DELETE만 가진 MANAGER는 첨부 있는 글을 지울 수 없다.
- **첨부**(`PostAttachmentController`·`PostAttachmentService`, `post_attachment` V29): 업로드·삭제 = `UPDATE`, 목록·다운로드 = `READ`. 검증은 공지와 같은 `AttachmentFilePolicy`(10MB·글당 5개·확장자+Content-Type 화이트리스트). **게시판 `attachmentYn=false`면 새 업로드만 400**("첨부를 허용하지 않는 게시판입니다"). 파일은 **스토리지 루트**(네임스페이스 없음)에 저장하고 롤백 시 정리(`FileStorageTransactionSupport.deleteOnRollback`), 삭제는 커밋 후(`deleteAfterCommit` 루트 오버로드). **수동 회수 절차(`docs/deployment.md`)의 ②·⑤가 게시글 참조·첨부를 보존해야 한다** — 이 조건을 빼면 게시글 파일이 지워진다.
- **본문 이미지**(`com.cms.admin.contentimage`의 `BoardContentImageController`, `POST /admin/api/boards/{boardId}/content-images`): 핸들러 선언은 그 게시판의 `READ`이고 서비스가 **같은 게시판의 CREATE∨UPDATE를 재판정**(403)한다. 이미지는 `BOARD:{boardId}` 출처로 저장되고 게시글 저장 시 `replaceRefs("POST", postId, "BOARD", boardId, imageIds)`가 **같은 게시판 출처가 아니면 400**(다른 게시판·공지 이미지 거부 — 기존 참조 유지). 공개 판정은 `contentimage`·`publicweb.board`의 `CLAUDE.md`.
- **감사**: `BOARD_CREATE`·`BOARD_UPDATE`·`BOARD_DELETE`(`targetType=BOARD`), `POST_CREATE`·`POST_UPDATE`·`POST_DELETE`(`targetType=POST`), `POST_ATTACHMENT_UPLOAD`·`POST_ATTACHMENT_DELETE`(`targetType=POST_ATTACHMENT`) — `targetId`는 대상 ID, **`targetLabel` 없음**(게시판 이름·게시글 제목은 사용자 입력). 라벨은 `AdminActionTypes`와 `templates/admin/log/manage.html`을 동기화 테스트가 고정한다.
- **화면**: 게시판 관리 `templates/admin/board/manage.html`(저장 중 입력·작성 전환 잠금, 늦은 목록 응답 폐기, 삭제 버튼·409 안내), 게시글 관리 `posts.html`(`GET /admin/board/posts`, `@AdminPage`, URL 게이트 = BOARD 기능 단위 READ). 옛 공지 화면(`notice/manage.html`)의 구조·비동기 보호(세대 토큰·`AbortController`·저장 토큰)를 옮겼고 ①-2에서 공지 화면이 삭제돼 이 화면이 유일한 편집 화면이다. 상단의 "내 게시판" 선택으로 게시판을 고르고(`?boardId=&id=` 진입 지원), 편집기(`notice-editor.js`)는 **페이지에 하나만** 만들어 게시판을 열 때마다 `setUploadUrl`로 업로드 출처만 바꾼다(진행 중 업로드는 무효화). 게시판별 허용 동작(`actions`)과 게시판 `attachmentYn`으로 버튼을 숨길 뿐 서버 판정(403)이 최종이다. 게시판 이름·제목은 `textContent`/`escapeHtml`로만 넣는다.
- **통합 검색**: `AdminSearchService`의 `posts` 섹션 — ADMIN은 미삭제 게시판의 미삭제 게시글(노출 여부 무관), MANAGER는 `AdminPermissionEvaluator.readableBoardIds`(READ가 유효한 게시판)로 쿼리를 한정하고 그런 게시판이 없으면 섹션 키 생략. 결과 이동은 `/admin/board/posts?boardId=&id=`.

## 공지 게시판 흡수 (①-2, 2026-10-09, `adversarial-review/plan/PLAN-notice-to-board.md` — 적대적 리뷰 5라운드 ship)

공지는 **시스템 게시판 `board_key='NOTICE'`("공지사항")의 게시글**이다. 옛 `com.cms.admin.notice` 패키지(엔티티·서비스·컨트롤러·`notice/manage.html`·`NoticeContentImageController`)는 삭제됐고 `notice`·`notice_attachment` 테이블은 V32 시점 **동결 스냅샷**으로 남는다(앱이 읽지 않음, 후속 PR이 DROP).

- **시스템 게시판**(V31 `board.board_key` UNIQUE, `Board.isSystem()`): 키가 있는 게시판은 **삭제 409**(`deleteBoard`, 게시글 검사보다 먼저)·**비공개 전환 400**(`updateBoard`의 `publicYn=false`)이다. 이름·`attachmentYn`은 바꿀 수 있다. 공개 `/notices`가 이 게시판에 의존하기 때문이다(`BoardRepository.findIdByBoardKey` — 삭제된 게시판이면 empty).
- **이관(V32, 한 트랜잭션 DML)**: 공지·첨부를 **같은 ID로 복사**(공개 URL 유지), ID 범위에 있던 기존 게시글·첨부는 `+offset` 재번호, 본문 이미지 출처·참조를 `BOARD`/`POST`로 **이동**, MANAGER `NOTICE` 권한을 공지 게시판의 `member_board_permission`으로 **이동**(READ가 있는 활성 MANAGER만, `permission_version`+1, NOTICE 행 삭제), '공지사항 관리' 메뉴 정리(깨끗한 대체 메뉴가 있으면 삭제/자식 있으면 URL만 비움, 없으면 '게시글 관리'로 전환). 배포 순서·롤백 미지원·복원 순서는 `docs/deployment.md` "공지 → 공지 게시판 흡수 배포"·`docs/migration-guide.md` "V31~V32".
- **관리 화면·API**: 공지 관리는 게시글 관리(`/admin/board/posts?boardId=공지게시판`)가 대신한다. 옛 `GET /admin/notice/manage[?id=]`는 **`NoticeRedirectController`가 302**로 보낸다(`@RestController` + `ResponseEntity` — 페이지가 아니라 사이드바 모델 주입이 필요 없다, `id`는 `^[1-9][0-9]{0,15}$`만 유지). 이 경로는 `AdminFeature.BOARD`의 게이트(기능 단위 READ = 어느 게시판이든 조회 권한)에 **정확 경로 1개**로 들어 있고 `menuUrls`에는 없다(`AdminEndpointAuthorizationConventionTest`의 `REDIRECT_ONLY_GATE_PATHS` 예외). `/admin/api/notices/**`는 사라졌다(ADMIN 404, MANAGER 403 — 게이트가 없어 `/admin/**` ADMIN 캐치올).
- **권한**: 공지 권한은 일반 게시판과 같은 게시판별 권한이다(`AdminFeature.NOTICE` 제거). 결과로 **`DELEGABLE`(기능 단위 위임) 기능이 0개**가 되어 `member_permission` 경로(`PermissionCache` 기능 행·`MemberPermissionService` grants·권한관리 화면의 기능 표)는 코드만 남고 실제 기능 픽스처가 없다 — 그 시험은 `BOARD_SCOPED` 판으로 옮기거나 삭제했다(공백 목록은 `PLAN-notice-to-board.md` "구현·검증 결과"). 다음 `DELEGABLE` 기능(②배너)이 들어올 때 복구한다.
- **본문 이미지**: 공지 편집기도 게시판 업로드 경로(`POST /admin/api/boards/{noticeBoardId}/content-images`)를 쓰고, `notice-editor.js`의 기본 업로드 URL은 삭제됐다(`uploadUrl`이 없으면 이미지 업로드는 오류 안내만 한다).
- **통합 검색**: `notices` 섹션은 삭제됐고 공지 글은 `posts` 섹션에 게시판 이름 '공지사항'과 함께 나온다(권한 = 그 게시판 READ).
- **감사**: `NOTICE_*` 상수·라벨은 **과거 이력 표시용**으로 남기고 새 행위는 `POST_*`(`targetType=POST`)로 기록된다.
- **시험 이식**: 옛 `admin/notice/**` 시험 9개를 `PostServiceTest`·`PostAttachmentServiceTest`·`PostControllerTest`·`PostAttachmentControllerTest`·`PostRepositoryDataJpaTest`·`PostRepositoryImplSortTest`·`PostConcurrencyIntegrationTest`·`PostAttachmentTransactionIntegrationTest`로 옮겼고(`NoticeContentHtmlMigrationTest`는 V22→V25 JDBC 시험이라 유지), 이관 정합은 `NoticeAbsorbMigrationTest`, 접근 매트릭스는 `AdminPermissionMatrixIntegrationTest`(공지 게시판 대상)가 고정한다.

## 시험

`BoardPermissionIntegrationTest`(PR A: ADMIN 생성·수정·감사, MANAGER 403, 게시판별 부여·회수), `BoardMigrationTest`(V25 → 최신: 출처 기본값·메뉴 시드·멱등·FK RESTRICT, 이전 앱 두 하한(V25·V28) 롤백 기동 호환), **`PostApiIntegrationTest`**(게시글 생명주기·감사, 게시판 A만 가진 MANAGER의 B 전 API 403, READ 의존, IDOR 404, 첨부 왕복·상한·`attachmentYn`, 이미지 출처 400, 게시판 삭제, 페이지 게이트, 통합 검색), **`AbstractBoardDeleteRaceTest`**(`innodb_snapshot_isolation` OFF·ON 서브클래스 — 회수 PUT ↔ 게시판 삭제 래치: OFF=200·ON=409, 게시글 생성 ↔ 게시판 삭제: 삭제가 공유 잠금을 기다리다 409; 변이 실험으로 `FOR SHARE` 제거 시 실패 확인), `com.cms.publicweb.board`의 시험(공개 불변식·스트리밍·템플릿), `FileStorageTransactionSupportTest`·`AttachmentFilePolicyTest`·`ContentBodyPolicyTest`(공용 추출).
