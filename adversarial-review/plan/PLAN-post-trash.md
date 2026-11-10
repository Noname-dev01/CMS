# PLAN — 게시글 휴지통(삭제된 게시글 조회·복원)

> 출처: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 8 (2026-10-07 선정)" > "이번 선정에서 탈락한 후보" — **휴지통(삭제 공지 복원)**. 공지는 2026-10-09부터 공지 게시판의 게시글이라 대상은 모든 게시글이다.
> 상태: **구현·검증 완료(2026-11-02, 브랜치 `feat/post-trash`, 미커밋)** — 적대적 리뷰 2라운드 ship, 사용자 승인 후 구현. 결과는 문서 끝 "구현·검증 결과". 전체 `./gradlew test`·코드 리뷰 루프는 아직 실행 전.

## 개정 이력

- v1(2026-11-02): 최초 작성. 사용자 결정 2건 반영(U1 목록·복원 모두 게시판 `DELETE` 권한, U2 복원은 항상 비노출).
- v2 변경(리뷰 R1, needs-attention 3건): ① **수용** — `innodb_snapshot_isolation=ON`에서 일관 읽기 뒤의 잠금 읽기는 1020(→409)이 될 수 있어 "늦은 쪽 404" 보장이 깨질 수 있다. D4에 "복원 트랜잭션의 첫 DB 문장은 게시글 잠금 읽기, 그 앞에 일관 읽기 금지" 규칙을 넣고, OFF·ON 경합 시험 4종(같은 글 동시 복원·게시판 삭제 선행·복원 선행·쓰기 중지 전환 선행)을 추가했다. ② **부분 수용** — V5 이전 앱으로 롤백한 기간의 삭제는 `updater_id`가 갱신되지 않아 직전 수정자가 삭제자로 보인다. D1·리스크에 명시하고 화면·응답 설명을 "삭제 시점의 최종 수정자"로 적는다. **기각(정리 절차)**: 실배포 전이고 첫 실배포 직전 "마지막 합치기"로 V5가 기준 스키마에 흡수돼 운영 DB가 V5 이전 앱으로 돌아갈 수 없다 — 감사 로그 `POST_DELETE`가 원본임을 문서에 둔다. ③ **수용** — 삭제 성공 시 열린 휴지통을 다시 읽고(닫혀 있으면 다음 열기에 조회), 복원으로 현재 페이지가 비면 이전 유효 페이지로 이동한다(D6).

## Context

게시글 삭제(`DELETE /admin/api/boards/{boardId}/posts/{postId}`)는 소프트 삭제(`post.deleted = 1`)인데, 관리 화면·API 어디에도 삭제된 글을 보거나 되돌리는 수단이 없다. 실수로 지운 글은 DB를 직접 고치지 않으면 복구할 수 없다. 이 계획은 **게시판별로 삭제된 글 목록을 보고 한 건씩 복원**하는 기능을 더한다. 영구 삭제·보존 기간·자동 비움은 범위 밖이다(아래 D7).

## 정찰 결과(코드에서 확인한 사실)

| 대상 | 사실 |
|---|---|
| 삭제 | `PostService.deletePost` — 게시글 행 `FOR UPDATE`(`findByIdAndBoardIdAndDeletedFalseForUpdate`) → 게시판 미삭제 확인 → **첨부가 남아 있으면 409** → `Post.softDelete(updaterId, now)`(`deleted=true` + `updateDate`·`updaterId`). 트랜잭션은 기본 격리, 감사 `POST_DELETE`. **본문 이미지 참조(`content_image_ref`)·대표 이미지(`cover_image_id`)·노출·고정·기간·조회수는 그대로 둔다.** |
| 삭제된 글의 첨부 | 첨부가 있으면 삭제가 막히므로 **삭제된 글에는 첨부가 없다** — 복원할 첨부가 없다. |
| 삭제 시각·삭제자 | `post`에 `deleted_at` 컬럼은 없다(V1 + V5·V8·V12). 그러나 `update_date`에 `ON UPDATE`가 없고, 삭제 후 그 행을 다시 쓰는 경로가 없다 — 모든 관리 쓰기는 `…DeletedFalse…` 조회로 잠그고, 카운터(`ContentCounterWriter`)는 `view_count`만 올리며 삭제된 글은 공개 상세에 도달하지 않는다. 따라서 **삭제된 글의 `update_date`·`updater_id` = 삭제 시각·삭제자**다. 예외: V5(2026-10-27) 이전에 삭제된 글은 `updater_id`가 NULL일 수 있다. 대표 이미지 FK `ON DELETE SET NULL`은 `cover_image_id`만 바꾼다. |
| 이미지 참조 수명 | 미디어 라이브러리의 "사용 중" 판정(`MediaService.delete`·`searchLibrary(unusedOnly)`)은 소유자 글의 삭제 여부를 보지 않는다 → **앱 안에서는 삭제된 글의 이미지가 지워지지 않는다.** 앱을 정지하고 하는 **수동 회수 절차 ②**(`docs/deployment.md`)는 "삭제되지 않은 글이 참조하지 않는 이미지"를 회수하므로 **삭제된 글만 쓰던 이미지를 지운다**(참조 행·이미지 행·파일, 대표는 FK로 NULL). 그 뒤 복원하면 본문의 `<img>`가 없는 이미지를 가리킨다(공개 상세는 `board-content-image-fallback.js` 안내 상자로 보인다). 참고: `docs/deployment.md` 542행의 "삭제된 게시글의 이미지는 미디어 라이브러리 '사용 안 함만'으로 지울 수 있다"는 코드와 다르다(참조가 남아 사용 중으로 셈). |
| 공개 판정 | 공개 경로 다섯 곳 모두 `p.deleted = false`를 조건에 고정한다(`isPublished`·`findPublishedById`·`existsPublishedPostRef` 등). |
| 게시판 삭제 | 게시판 행 `FOR UPDATE` → `existsByBoardIdAndDeletedFalse`면 409 → 소프트 삭제. 게시글 생성은 게시판 `FOR SHARE`로 이 검사와 직렬화된다(`AbstractBoardDeleteRaceTest`가 OFF·ON 두 서브클래스로 고정). 삭제된 게시판에는 복원 API가 없다. |
| 고정 | 고정 개수 = 그 게시판의 미삭제 `pinned` 글 수(노출 무관). 새로 고정하는 쓰기만 상한을 본다. |
| 쓰기 중지 | `writable_yn = 0`이면 생성·수정(노출만 끄는 수정 포함)·첨부 업로드·이미지 업로드가 400, 조회·첨부 삭제·게시글 삭제는 허용. 판정은 게시판 `FOR SHARE` 안. |
| 권한·URL | `AdminFeature.BOARD` 게이트 = `/admin/api/boards/*/posts`, `/admin/api/boards/*/posts/**`(기능 단위 READ). 핸들러는 `@RequireBoardPermission(action)` — 경로 변수 이름은 `boardId` 필수. `MyBoardResponse.actions`가 화면 버튼 표시에 쓰인다. |
| 감사 | `AdminActionTypes` 상수 + `templates/admin/log/manage.html` 라벨을 `AdminActionTypeLabelSyncTest`가 고정. |
| 화면 | `templates/admin/board/posts.html`(1,656줄) — 게시판 선택 → 검색 카드 → 목록 카드 → 상세/편집 모달. 비동기 보호(세대 토큰·`AbortController`)가 이미 있고 게시판을 바꾸면 상태를 초기화한다(`selectBoard`). |

## 사용자 결정(2026-11-02)

- **U1 권한**: 휴지통 목록과 복원 모두 그 게시판의 **`DELETE`** 권한(선택지: 모두 DELETE / 목록 READ·복원 DELETE / UPDATE∧DELETE → 모두 DELETE). "삭제를 되돌리는 도구"이고, READ만 가진 MANAGER에게 일부러 지운 글의 제목이 보이지 않게 한다.
- **U2 복원 상태**: 복원한 글은 **항상 비노출(`useYn=false`)**(선택지: 삭제 전 상태 그대로 / 항상 비노출 → 비노출). 복원이 곧바로 공개로 이어지지 않게 하고, 공개는 관리자가 노출을 켜서 한다.

## 핵심 쟁점과 결정

### D1. 스키마 — **변경 없음(삭제 시각·삭제자 = `update_date`·`updater_id`)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 기존 컬럼 재사용** | 마이그레이션·업그레이드 시험·롤백 고려가 없다 | "삭제된 행은 다시 쓰이지 않는다"는 불변식에 기댄다. V5 이전 삭제 글은 삭제자 NULL |
| B. `deleted_at`·`deleted_by` 추가 | 의미가 명확 | 마이그레이션 + 기존 삭제 글 백필(값을 알 수 없어 `update_date` 복사가 전부) — A와 같은 정보를 두 번 저장 |

**결정 A** — B의 백필도 결국 `update_date`를 복사할 뿐이라 얻는 정보가 없다. 불변식은 지침 문서에 명시한다("삭제된 글의 행을 고치는 새 쓰기 경로를 만들면 삭제 시각 표시가 깨진다"). 응답 필드 이름은 의미대로 `deletedAt`·`deletedBy`로 내보내되 설명은 **"삭제 시점의 최종 수정 시각·수정자"**로 적는다(v2, 리뷰 R1-2).

**알려진 예외(v2)**: V5(`updater_id` 도입) 이전 앱은 삭제 때 `updater_id`를 갱신하지 않는다. 그런 앱으로 롤백한 기간에 삭제된 글은 **직전 수정자가 삭제자로 보이고**, V5 이전에 삭제된 글은 NULL("-")이다. 정확한 삭제자는 감사 로그(`POST_DELETE`)가 원본이다. 별도 정리 절차는 두지 않는다 — 실배포 전이고 첫 실배포 직전 "마지막 합치기"가 V5를 기준 스키마에 흡수하므로 운영 DB에서 이 예외가 새로 생길 수 없다(dev DB에만 해당).

### D2. API — **게이트 안의 두 경로**

- 목록: `GET /admin/api/boards/{boardId}/posts/trash?page=&size=` — 삭제된 글을 **삭제 시각 내림차순 + id 내림차순**, 페이지 크기 100 clamp(기존 목록과 같은 상한). 응답 `{content:[{id, title, authorId, createDate, deletedAt, deletedBy}], page, size, totalElements, totalPages, last}`. 본문은 싣지 않는다(목록 용도).
- 복원: `POST /admin/api/boards/{boardId}/posts/{postId}/restoration` — 200 + 복원된 글의 `PostResponse`.

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `/posts/trash`·`/posts/{id}/restoration`** | 기존 게이트 `/posts/**` 안 — `AdminFeature`·`SecurityConfig` 무변경(인가 정책 변경 없음). 동사 없는 명사 경로(api-conventions) | `trash`가 `{postId}` 자리와 같은 깊이 — 리터럴 매핑이 우선이라 충돌은 없지만 `/posts/trash`에 PATCH·DELETE는 `{postId}` 타입 변환 400이 된다 |
| B. `/admin/api/boards/{boardId}/deleted-posts` | 별도 컬렉션이라 REST상 가장 깔끔 | 게이트 패턴 추가 = URL 접근 규칙 변경(사전 승인 필요) |
| C. 기존 목록에 `?deleted=true` | 경로 추가 없음 | 한 핸들러의 권한 선언이 하나라 목록(READ)과 휴지통(DELETE, U1)을 나눌 수 없다 |

**결정 A**. 두 핸들러 모두 `@RequireBoardPermission(action = DELETE)`(U1). 권한이 없는 게시판은 기존과 같이 존재 여부와 무관한 403.

### D3. 복원 규칙 — **비노출·고정 해제로, 나머지는 보존**

복원은 `deleted=false`, **`useYn=false`(U2)**, **`pinned=false`**, `updateDate=now`, `updaterId=복원한 사람`. 제목·본문·노출 기간·대표 이미지·조회수·작성자·작성일은 그대로.

- **고정 해제 이유**: 고정 개수는 노출과 무관하게 미삭제 고정 글을 센다. 고정을 유지하면 복원이 상한(0 포함)을 넘길 수 있어 409/400 판정과 게시판 배타 잠금이 필요해진다. 복원을 "안전한 비공개 상태로 되돌리기"(U2)로 정한 이상 고정도 끄고, 필요하면 관리자가 수정으로 다시 고정한다(그때 기존 상한 판정이 적용된다).
- **노출 기간은 보존**: 비노출이라 기간이 공개에 영향을 주지 않고, 노출을 켤 때 원래 예약 의도가 남아 있는 편이 낫다.
- **본문·이미지 참조는 다시 계산하지 않는다**: 참조는 삭제 때 남겨 뒀으므로 그대로 맞다. 수동 회수로 이미지가 지워진 경우 참조 행도 함께 지워졌고 대표는 FK로 NULL이라 정합성은 이미 맞다(본문 `<img>`만 없는 이미지를 가리킨다 — 리스크로 기록, 화면이 사용자에게 알리는 것은 범위 밖).

### D4. 잠금·정합성 — **게시글 `FOR UPDATE` → 게시판 `FOR SHARE` → 쓰기 중지 판정**

- 새 조회 `findByIdAndBoardIdAndDeletedTrueForUpdate`(PESSIMISTIC_WRITE)로 삭제된 글만 잡는다. 없으면(살아 있는 글·다른 게시판 글·없는 id·이미 복원된 글) **같은 404** "삭제된 게시글을 찾을 수 없습니다."(IDOR 규칙과 같다). 같은 글의 동시 복원은 행 잠금으로 직렬화되고, 늦은 쪽은 잠금 읽기가 최신 커밋(`deleted=false`)을 봐 404다.
- 그다음 게시판 행 `FOR SHARE`(`findByIdAndDeletedFalseForShare`) — **삭제된 게시판이면 404**(게시판 복원 API가 없으므로 그 글은 복원할 수 없다). 이 공유 잠금이 게시판 삭제(`FOR UPDATE` + 살아 있는 글 검사)와 직렬화한다: 복원이 먼저면 삭제는 기다렸다가 복원된 글을 보고 409, 삭제가 먼저면 복원은 404. 순서(게시글 X → 게시판 S)는 수정과 같아 새 순환이 없다.
- **쓰기 중지 게시판은 400**(`Board.WRITE_STOPPED_MESSAGE`): 복원은 게시판에 글을 되돌리는 쓰기다(생성과 같은 성질). 같은 공유 잠금 안에서 판정해 쓰기 중지 전환과 직렬화된다. ADMIN이 쓰기를 잠시 켜고 복원할 수 있다.
- 복원한 사람은 **행 잠금 전에** 읽고 null이면 403(기존 규약). 이 값은 보안 컨텍스트에서 읽어 DB 문장이 아니다.
- **첫 DB 문장 규칙(v2, 리뷰 R1-1)**: 복원 트랜잭션의 **첫 DB 문장은 게시글 잠금 읽기**이고, 그 앞에 일관 읽기(일반 SELECT)를 두지 않는다. `innodb_snapshot_isolation=ON`에서는 일관 읽기로 스냅샷이 생긴 뒤 그 이후 바뀐 행을 잠그려 하면 오류 1020(전역 핸들러 409)이 된다. 잠금 읽기만으로 시작하면 스냅샷이 없어 늦은 쪽이 최신 커밋을 읽으므로, 계약은 OFF·ON 모두 **같은 글 동시 복원 → 늦은 쪽 404, 게시판 삭제 선행 → 404, 쓰기 중지 전환 선행 → 400**이다. 감사 AOP는 `REQUIRES_NEW` 별도 트랜잭션이라 이 순서에 끼지 않는다. 이 규칙을 시험으로 고정한다(아래 경합 시험).
- 트랜잭션은 기본 격리(`@Transactional`) — 개수 판정이 없어 `READ_COMMITTED` 최상위 규약이 필요 없다.

### D5. 감사 — **`POST_RESTORE`("게시글 복원"), `targetType=POST`, `targetId`=글 id**

라벨은 `AdminActionTypes`·`log/manage.html`에 함께 넣는다(동기화 시험). `targetLabel`은 기존 게시글 규칙대로 없다.

### D6. 화면 — **게시글 관리 화면 아래에 "휴지통" 카드(같은 페이지)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 같은 페이지의 별도 카드** | 새 페이지 경로·메뉴·게이트 없음. 기존 목록·모달 상태 기계와 분리 | 페이지가 길어진다 |
| B. 별도 페이지 `/admin/board/trash` | 화면 분리 | 새 페이지 경로는 게이트·메뉴·`@AdminPage`·인가 컨벤션 추가(인가 정책 변경) |
| C. 기존 목록에 "삭제됨" 필터 | UI 최소 | 목록·모달·버튼이 삭제 글 상태를 모두 알아야 해 기존 상태 기계를 넓게 건드린다 |

**결정 A**. 카드는 선택한 게시판의 `actions`에 `DELETE`가 있을 때만 보인다(서버 403이 최종). 처음에는 닫혀 있고 "휴지통 보기"를 눌러야 불러온다(평소 목록 로딩에 쿼리를 더하지 않는다). 표: 제목·작성자·작성일·삭제일·삭제자·[복원]. 복원은 확인 창("비노출·상단 고정 해제 상태로 복원됩니다. 공개하려면 복원 후 노출을 켜세요.") 뒤 요청하고, 성공하면 휴지통과 본 목록을 다시 읽는다. **복원으로 현재 휴지통 페이지가 비면 이전 유효 페이지(0 이상)로 이동**한다(v2, 리뷰 R1-3). **기존 삭제 성공 처리도 바꾼다**: 휴지통이 열려 있으면 본 목록과 함께 휴지통도 다시 읽고, 닫혀 있으면 다음 열기 때 새로 조회한다(열 때마다 조회하므로 별도 무효화 상태가 필요 없다). 게시판을 바꾸면 휴지통 카드는 닫히고 늦게 온 응답은 세대 토큰으로 버린다. 제목·작성자는 `textContent`로만 넣는다. 쓰기 중지 게시판에서는 복원 버튼 대신 서버 400 문구를 그대로 보인다(버튼을 숨기지 않는다 — 판정은 서버 하나).

### D7. 범위 밖

- **영구 삭제(하드 삭제)·보존 기간·자동 비움**: 하드 삭제는 이미지 참조·썸네일·카운터·파일 수명까지 다뤄야 하고 스케줄러도 없다. 삭제된 글은 지금처럼 계속 쌓인다.
- **게시판 복원**: 삭제된 게시판은 `board_key`·`site_id`를 비운 채 남아 복원 의미가 별도 설계 사안이다. 그 게시판의 글은 휴지통에서도 볼 수 없다(게시판 404).
- **복원 전 본문 미리보기**: 목록만 둔다.

## 설계 제약

- 경로 변수 이름 `boardId`(SpEL 판정), 모든 상태 변경 fetch에 CSRF 헤더.
- 엔티티에 `@Setter` 금지 — `Post.restore(updaterId, now)` 도메인 메서드(`updateDate`·`updaterId`를 함께 받는 규약).
- 시각은 주입된 `Clock`.
- 화면 문자열은 `textContent`/`escapeHtml`.

## 작업 단계

1. `Post.restore(updaterId, now)`, `PostRepository.findByIdAndBoardIdAndDeletedTrueForUpdate`·삭제 글 페이지 조회.
2. `PostService.getDeletedPosts`·`restorePost`, DTO `DeletedPostSummaryResponse`·`DeletedPostPageResponse`.
3. `PostController` 두 핸들러, `AdminActionTypes.POST_RESTORE` + `log/manage.html` 라벨.
4. `posts.html` 휴지통 카드.
5. 시험 → 실기 → 문서(`com.cms.admin.board` `CLAUDE.md`, `docs/deployment.md` 수동 회수 주의 + 542행 정정).

## 시험

| 시험 | 내용 |
|---|---|
| `PostTrashApiIntegrationTest`(신규, 실스택) | 목록: 삭제된 글만·그 게시판만·삭제 시각 내림차순·페이지·`deletedAt/deletedBy` = 삭제 요청 시각·사람·본문 미포함. 복원: `deleted=false`·`useYn=false`·`pinned=false`·`updaterId`·`updateDate` 갱신, 기간·대표·조회수·작성자 보존, 감사 `POST_RESTORE`, 복원 후 관리 목록에 보이고 휴지통에서 빠짐, 공개 상세는 비노출이라 404·노출을 켜면 200. 경계: 살아 있는 글·다른 게시판 글·없는 id 404, 두 번째 복원 404, 삭제된 게시판 404, 쓰기 중지 400(상태 불변), 고정이던 글을 상한 0 게시판에서 복원해도 200. 권한: READ·UPDATE만 가진 MANAGER 목록·복원 403, DELETE를 가진 MANAGER 200, 다른 게시판 권한만 있으면 403 |
| `AbstractBoardDeleteRaceTest`(메서드 추가, OFF·ON 두 서브클래스에서 모두 실행) | ① 복원 선행: 복원이 게시판 공유 잠금을 쥔 동안 게시판 삭제는 기다리고, 복원 커밋 뒤 삭제는 409 ② 게시판 삭제 선행: 삭제가 게시판 행을 쥔 동안 복원은 기다리고, 삭제 커밋 뒤 복원은 404(409 아님) ③ 같은 글 동시 복원: 먼저 잡은 쪽 200, 늦은 쪽 404(409 아님) ④ 쓰기 중지 전환 선행: 게시판 PATCH(`writableYn=false`)가 행을 쥔 동안 복원은 기다리고 커밋 뒤 400(409 아님) — 모두 DB 상태(삭제 여부·게시판 삭제 여부) 단언 포함 |
| `AdminActionTypeLabelSyncTest` | 새 라벨 자동 검사(수정 없음) |
| `PostServiceTest`(단위) | 복원의 잠금 순서(게시글 → 게시판 공유)·쓰기 중지 판정이 잠금 행 값 사용 |

영향 범위: `com.cms.admin.board.*`·`com.cms.publicweb.board.*`·`com.cms.admin.log.*`·`*ConventionTest`, PR 직전 전체 `./gradlew test`.

## 실기 검증

1. 게시글 삭제 → 휴지통에 삭제일·삭제자와 함께 보임 → 복원 → 목록에 비노출로 돌아옴(스크린샷) → 노출을 켜면 공개 상세 200.
2. READ만 가진 MANAGER: 휴지통 카드 없음, API 직접 호출 403. DELETE를 가진 MANAGER: 복원 가능.
3. 쓰기 중지 게시판에서 복원 → 400 문구, 상태 불변.
4. 회귀: 기존 목록·삭제·첨부 409·검색 정상.
5. 검증 데이터 원복.

## 리스크

- **수동 회수 뒤 복원**: 회수가 삭제 글의 이미지를 지운 뒤 복원하면 본문 이미지가 없다(대표는 NULL). 공개 상세는 안내 상자로 보이고, 관리 편집기에서는 깨진 이미지로 보인다 — 운영 문서에 "복원 가능성을 남기려면 회수 전에 휴지통을 확인" 주의를 적는다.
- **삭제 시각 불변식**: 삭제된 행을 고치는 새 쓰기 경로가 생기면 `deletedAt` 표시가 그 시각으로 바뀐다(지침 문서에 명시).
- **V5 이전 삭제 글·V5 이전 앱 롤백 중 삭제된 글**: 삭제자가 NULL("-")이거나 직전 수정자로 보인다(D1 알려진 예외). 감사 로그가 원본이다.
- **복원은 공개가 아니다**: 실수 삭제 복구에 "노출 켜기" 한 단계가 더 든다(U2로 수용).

## 구현·검증 결과 (2026-11-02)

**Context / 확정 사항**: 삭제된 게시글을 게시판별로 보고 한 건씩 복원한다. **스키마·마이그레이션·`SecurityConfig`·`AdminFeature`·의존성 변경 없음**(두 API가 기존 게이트 `/admin/api/boards/*/posts/**` 안). 사용자 결정 U1(목록·복원 모두 게시판 `DELETE` 권한)·U2(복원은 항상 비노출) 그대로. 복원은 비노출·상단 고정 해제로 되살리고 나머지(제목·본문·노출 기간·대표 이미지·조회수·작성자·작성일)는 보존, 삭제 시각·삭제자는 삭제된 글의 `updateDate`·`updaterId`다. 복원 트랜잭션의 첫 DB 문장은 게시글 잠금 읽기다(리뷰 R1-1).

**구현 파일**
| 파일 | 변경 |
|---|---|
| `admin/board/domain/Post.java` | `restore(updaterId, now)` — `deleted=false`·`useYn=false`·`pinned=false` + `touch` |
| `admin/board/repository/PostRepository.java` | `findByIdAndBoardIdAndDeletedTrueForUpdate`(PESSIMISTIC_WRITE, 삭제된 글만) |
| `admin/board/repository/PostRepositoryCustom.java`·`Impl.java` | `searchDeleted(boardId, pageable)` — 삭제 시각 내림차순 + id 내림차순, 게시판 소속 항상 |
| `admin/board/dto/response/DeletedPostSummaryResponse.java`·`DeletedPostPageResponse.java`(신규) | 본문 제외 목록 응답, `deletedAt`·`deletedBy` |
| `admin/board/service/PostService.java` | `restorePost`(게시글 X → 게시판 S → 쓰기 중지 400, 삭제된 게시판 404)·`getDeletedPosts`(페이지 크기 100 상한) |
| `admin/board/controller/PostController.java` | `GET /trash`·`POST /{postId}/restoration`, 둘 다 `@RequireBoardPermission(DELETE)` |
| `admin/log/constant/AdminActionTypes.java`·`templates/admin/log/manage.html` | `POST_RESTORE`("게시글 복원") |
| `templates/admin/board/posts.html` | 휴지통 카드(`DELETE` 권한 있을 때만, 열 때마다 조회, 복원 확인 창, 빈 페이지면 이전 페이지로, 삭제 성공 시 열린 휴지통 갱신, 게시판 전환 시 초기화) |
| 시험 | `PostTrashApiIntegrationTest`(신규 8), `AbstractBoardDeleteRaceTest`(+4, OFF·ON 서브클래스 모두), `PostServiceTest`(+5) |
| 문서 | `com.cms.admin.board` `CLAUDE.md`(새 섹션)·루트 `CLAUDE.md` 지도 행·`docs/deployment.md`("게시글 휴지통" 섹션 + 수동 회수 주의 + 상한 도달 항목 정정) |

**검증 결과**
- 신규·변경 시험: `PostTrashApiIntegrationTest` 8건, `PostServiceTest` 53건(신규 5), 경합 시험 `BoardDeleteRaceSnapshotIsolationOff/On` 각 7건(기존 3 + 신규 4) 모두 통과.
- **영향 범위 시험**(`admin.board.*`·`admin.log.*`·`admin.permission.*`·`publicweb.board.*`·`*ConventionTest`) 773건 중 1건 실패 — 이번 작업과 무관한 **#192의 `SitePublicRateLimitConventionTest.contentImageBucket_exhaustedForAnonymousButNotForLoggedIn`**(아래 이슈), 수정 후 같은 클래스 8건을 2회 연속 통과. 휴지통 관련 실패는 0.
- **변이 실험 2건**: ① 복원의 게시판 `FOR SHARE`를 일반 읽기로 바꾸면 경합 시험 6건(OFF·ON 각 3: 복원 선행 409·게시판 삭제 선행 404·쓰기 중지 선행 400) 실패 ② 복원 첫 문장 앞에 `requireBoard`(일관 읽기)를 넣으면 ON 3건·OFF 1건 실패 — 둘 다 원복 확인.
- **실기(dev `bootRun` 8081, Playwright + curl)**: 게시판 #21에 고정글·일반글·예약글을 API로 만든 뒤 화면에서 — 휴지통 카드는 처음엔 닫혀 있고 열면 삭제일·삭제자와 함께 보임(`docs/screenshots/board-admin/post-trash-01-open.png`), **휴지통을 연 채 다른 글을 삭제하면 휴지통이 자동 갱신**(2건, 삭제 시각 내림차순), 복원 확인 창 문구 확인, 복원한 고정글은 목록에 **"고정" 배지 없이 비노출**로 돌아오고 공개 상세 404 → 노출을 켜면 200, 예약글은 **노출 기간(2090-01-01 ~ 2090-02-01) 보존·상태 HIDDEN**, 감사 `POST_RESTORE` 기록, 글 11건 삭제 후 2페이지의 마지막 항목을 복원하면 **1페이지(10건)로 자동 이동**(`docs/screenshots/board-admin/post-trash-02-after-restore-page-move.png`). 권한: MANAGER가 READ+UPDATE만 가지면 trash·복원 모두 **403**(일반 목록은 200)이고 `actions`에 `DELETE`가 없어 카드가 숨겨짐, READ+UPDATE+DELETE면 200·복원자는 `trashmgr`로 기록, 다른 게시판(#1) 403, CSRF 토큰 없이 복원 403. 검증 행(게시판·글 14건·MANAGER 계정·권한·감사 로그)은 모두 삭제해 원복, 앱·dev DB 종료.
- **아직 실행하지 않은 것**: 전체 `./gradlew test`(공용 코드 변경이 없어 규칙상 PR 직전 `code-review-loop` 마무리에서 1회)·코드 리뷰 루프. 화면의 확인 창은 `confirm`을 자동 승인으로 대체해 검증했다(브라우저 자동화가 막히지 않게) — 실제 취소 동작은 코드 경로(`return`)로만 확인.

**이슈**
- **#192의 시험이 시간에 민감했다**: 익명 300건으로 버킷을 비운 뒤 관리자 40건을 보내는 사이(약 0.8초) 토큰이 초당 4개씩 차올라 마지막 익명 요청이 429 대신 404를 받았다. 마지막 단언을 "연속 30건 중 429가 나온다"로 바꿨다(이 PR에 **별도 커밋**으로 분리 — 휴지통과 무관한 수정). 면제가 버킷을 소비하지 않음은 `RateLimitFilterTest`가 결정적으로 고정한다.
- 첫 영향 범위 시험 전 Docker Desktop은 이미 켜져 있었다(이번 세션의 이전 작업에서 기동).

**후속 / 수용한 한계**: 영구 삭제·보존 기간·자동 비움·게시판 복원은 범위 밖. 수동 회수 ②는 삭제된 글만 쓰던 이미지도 회수하므로 복원 가능성이 남은 글이 있으면 회수 전에 휴지통을 확인한다(`docs/deployment.md`). V5 이전 앱으로 롤백한 기간에 삭제된 글은 직전 수정자가 삭제자로 보일 수 있다(감사 로그가 원본). 삭제된 글의 이미지는 앱 안에서 지워지지 않는다(미디어 라이브러리 "사용 중" 판정이 소유자 글의 삭제 여부를 보지 않음) — `docs/deployment.md` 상한 도달 항목의 옛 서술을 이번에 정정했다. 로드맵 항목 반영은 `/updateRoadmap`.
