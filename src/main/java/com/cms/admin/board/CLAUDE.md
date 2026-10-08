# CLAUDE.md — com.cms.admin.board

이 디렉터리(범용 게시판) 작업 시에만 로드된다. 공통 규칙은 프로젝트 루트 `CLAUDE.md` 참조. 설계 결정·적대적 리뷰 7라운드 기록은 `adversarial-review/plan/PLAN-board.md`(PR A = 게시판 정의 + 게시판별 권한, PR B = 게시글·공개·검색).

(필드 목록은 엔티티 코드가 원본이다. 여기에는 코드만 봐서는 알기 어려운 사실만 기록한다.)

## 지금 있는 것 (PR A, 2026-10-08)

- **게시판 정의**(`Board`, V26): `name`(100자, 공백 거부, 중복 허용 — 화면은 `#id`를 함께 보인다)·`publicYn`(공개 사이트 노출)·`attachmentYn`(새 첨부 업로드 허용 — 끄면 새 업로드만 막고 기존 첨부는 그대로, PR B)·`deleted`(소프트 삭제). 정렬은 id 순.
- **API·화면은 ADMIN 전용**: `GET·POST /admin/api/boards`, `GET·PATCH /admin/api/boards/{boardId}`, 화면 `GET /admin/board/manage`(`@AdminPage`). 카탈로그 게이트 밖이라 `/admin/**` ADMIN 캐치올이 막고 핸들러도 `hasRole('ADMIN')`. 메뉴는 V28 시드(`BOARD_ADMIN` = ADMIN_ONLY라 MANAGER 사이드바에 없다).
- **삭제 API는 아직 없다** — PR B에서 "살아 있는 게시글이 있으면 409"와 **함께** 추가한다. 검사 없는 삭제가 먼저 배포되면 PR B → PR A 롤백 중 게시글이 있는 게시판을 지울 수 있기 때문이다(리뷰 R1-5). 삭제는 그 게시판의 `member_board_permission` 행을 지우고 `PermissionChangedEvent`를 낸다(버전은 올리지 않는다 — 낡은 권한 화면의 PUT은 게시판 존재 확인에서 400).
- **게시판 행 잠금 규칙**: 수정은 `findByIdAndDeletedFalseForUpdate`(PESSIMISTIC_WRITE). 권한 저장은 부여할 게시판들을 `findAllByIdInForShare`(PESSIMISTIC_READ, id 순)로 잠가 게시판 삭제(`FOR UPDATE`, PR B)와 직렬화한다 — 잠금 순서는 권한 PUT이 회원 → 게시판, 게시판 삭제가 게시판 → 권한 행이라 순환이 없다. PR B의 게시글 생성도 게시판 행을 `FOR SHARE`로 잠근다.
- **내 게시판 목록**(`GET /admin/api/members/me/boards`, `MyBoardController`, `hasAnyRole('ADMIN', 'MANAGER')`): 기존 ALWAYS `MY_INFO` 게이트 안이라 `SecurityConfig` 변경 없음. ADMIN은 미삭제 게시판 전부·전 동작, MANAGER는 READ가 유효한 게시판만 + 게시판별 허용 동작(쓰기는 같은 게시판 READ 의존). 게시글 관리 화면(PR B)의 게시판 선택·버튼 표시용이며 서버 판정을 대신하지 않는다.
- **감사**: `BOARD_CREATE`·`BOARD_UPDATE`(`targetType=BOARD`, `targetId`=게시판 ID, `targetLabel` 없음 — 이름은 사용자 입력).
- 게시판별 권한의 판정·저장·캐시는 `com.cms.admin.permission`의 `CLAUDE.md` "게시판별 권한".

## 시험

`BoardPermissionIntegrationTest`(ADMIN 생성·수정·감사·검증, MANAGER 403(API·페이지), 게시판별 부여·매트릭스·내 게시판 목록·교차 회원 격리·회수, 삭제·없는 게시판·`boardGrants` 누락 400), `BoardMigrationTest`(V25 → 최신: 이미지 출처 기본값·메뉴 시드·멱등, V25 이하 앱 롤백 기동 호환).
