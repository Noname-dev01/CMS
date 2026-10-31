# PLAN — 게시글 관리 화면의 갤러리형·웹진형 게시판 안내

> 출처: `adversarial-review/project-direction-roadmap.md` "후속 과제 — 게시판 정의 고도화 완료 시 기록 (2026-10-30)" ②.
> 선행: `PLAN-board-definition.md` PR 5(대표 이미지, V12)·PR 6(게시판 유형, V13) — 모두 머지됨(#186·#188·#189).
> 상태: **구현·검증 완료(2026-10-31, 브랜치 `feat/board-type-editor-note`, 미커밋)** — 적대적 리뷰 1라운드 ship. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- v1(2026-10-31): 최초 작성.
- v2 변경(리뷰 R1, 판정 ship): ① **수용** — 안내 문구가 공개 화면의 예외를 생략함(상단 고정 영역은 갤러리·웹진에서도 이미지 없는 목록형 `public/board/list.html:29`, 웹진 요약은 본문 글자가 있을 때만 `:81`). D3 문구를 "공개 **일반 목록**"·"본문에 글이 있으면 요약"으로 좁혔다(동적 분기 없음). ② **수용** — 실기 검증에 같은 페이지에서 갤러리 → 목록형 → 이미지 금지 웹진 순으로 모달을 다시 열어 안내·꼬리 문장이 이전 게시판에서 남지 않는지 확인하는 시나리오 추가(D7·작업 단계 6). 기각 없음.

## Context

게시판 유형(`LIST`·`GALLERY`·`WEBZINE`)은 공개 목록의 모양만 바꾸고, 갤러리·웹진은 목록에 게시글의 **대표 이미지**(`post.cover_image_id`)를 쓴다. 대표가 없으면 공개 목록에 CSS 자리표시가 보인다(#189). 그런데 게시글을 쓰는 화면(`templates/admin/board/posts.html`)은 그 게시판의 유형을 모른다:

- 화면이 쓰는 `GET /admin/api/members/me/boards`(`MyBoardController` → `MyBoardService` → `MyBoardResponse`)에는 `boardType`이 없다.
- 유형은 게시판 정의 API(`BoardResponse.boardType`)에만 있고 그 API는 **ADMIN 전용**이라 MANAGER 화면은 얻을 방법이 없다.
- 그 결과 갤러리 게시판에 글을 쓰는 사람이 대표를 고르지 않아도 아무 안내가 없고, 공개 목록에는 자리표시만 줄지어 보일 수 있다.

## 정찰 결과(코드에서 확인한 사실)

| 대상 | 사실 |
|---|---|
| `MyBoardService.getMyBoards` | 미삭제 게시판을 `sortOrder, id` 순으로 돌며 판정기의 게시판별 동작에 READ가 있는 것만 `MyBoardResponse`로 만든다. 이미 `pinMaxCount`·첨부 정책·`bodyImageYn`·`publishPeriodYn`(게시판 정의 값)을 READ 권한자에게 내보낸다. |
| `MyBoardResponse` | `@Getter @AllArgsConstructor` 불변 DTO, 필드별 `@Schema` 설명("화면 … 서버 판정이 최종"). 생성자 호출부는 `MyBoardService` 한 곳뿐(grep). |
| `Board.type()` | 저장 원본 `board_type`의 해석값(`BoardType.fromStored` — null·알 수 없는 값은 `LIST`). 공개 목록·`BoardResponse`가 같은 해석값을 쓴다. |
| `posts.html` | 모달을 열 때 `modalBoard = currentBoard`(내 게시판 응답 한 건)를 고정하고 `fillForm` → `applyWritePolicy(data)`가 `modalBoard.bodyImageYn`으로 `imagePolicyNote`(본문 이미지 금지 안내)를 토글한다. 대표 이미지 묶음 `coverGroup`은 **본문에 이미지가 하나도 없으면 `d-none`**(`renderCoverChoices`, 편집기 `onChange`마다 다시 그림). |
| 시험 | 내 게시판 API는 `BoardPermissionIntegrationTest`(MANAGER 부여·격리)·`BoardWritePolicyApiIntegrationTest`·`BoardSortOrderPinApiIntegrationTest` 등이 필드별로 단언한다(전체 JSON 일치 단언 없음 → 필드 추가가 기존 시험을 깨지 않음). 유형 시험은 `BoardTypeApiIntegrationTest`(실스택, 알 수 없는 원본 삽입 헬퍼 `insertBoardWithRawType` 있음). `posts.html`을 대상으로 한 브라우저 시험 클래스는 없다. |

## 핵심 쟁점과 결정

### D1. 응답에 무엇을 싣나 — **해석값 `boardType`(enum, 항상 있음)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 해석값 `Board.type()`** | 공개 목록·`BoardResponse`와 같은 해석 규칙 하나 — 화면이 보는 유형 = 방문자가 보는 모양. 알 수 없는 원본은 `LIST`라 화면 분기가 3값으로 닫힌다. | 알 수 없는 원본을 화면이 구분할 수 없다(구분할 이유도 없다). |
| B. 저장 원본 문자열 | 원본 그대로 | 화면이 해석 규칙을 복제해야 하고, 롤백·미래 값이 화면에 그대로 새어 나간다. |
| C. 불리언 `coverUsedInList` | 화면 분기가 가장 단순 | 갤러리와 웹진의 안내 문구를 구분할 수 없고, 유형이 늘면 의미가 흐려진다. |

**결정 A** — 로드맵 항목이 이미 "해석값 — 알 수 없는 저장값은 `LIST`"로 정했고, 공개 화면과 해석이 갈라지지 않는 것이 이 안내의 정확성 조건이다. 직렬화는 enum 이름(`"GALLERY"`) — `BoardResponse`와 같다. 이 DTO는 **응답 전용**이라 PR 6의 "요청은 문자열 엄격 파싱" 함정과 무관하다.

### D2. 노출 범위 — **READ 권한자에게 그대로 내보낸다(인가 변경 없음)**

내 게시판 목록은 이미 게시판 정의 값(`pinMaxCount`·첨부 정책·`bodyImageYn`·`publishPeriodYn`)을 READ 권한자에게 준다. 유형은 공개 게시판이면 공개 목록 모양으로 누구나 관찰할 수 있는 값이고, 비공개 게시판이어도 그 게시판의 글을 읽는 사람에게 숨길 정보가 아니다. 게시판 정의 API(`/admin/api/boards/**`)의 ADMIN 전용은 그대로다 — `SecurityConfig`·`AdminFeature`·`@PreAuthorize` 변경 없음.

### D3. 안내를 어디에 두나 — **편집기 아래 독립 안내 `coverTypeNote`(대표 묶음 밖)**

| 선택지 | 장점 | 단점 |
|---|---|---|
| A. `coverGroup` 안에 문구 추가 | 대표 선택지 옆이라 맥락이 가깝다 | `coverGroup`은 본문에 이미지가 없으면 숨는다 — **안내가 가장 필요한 순간(아직 이미지를 넣지 않음)에 안 보인다**. 보이게 하려면 `renderCoverChoices`의 키 기반 재렌더 상태 기계를 바꿔야 한다. |
| **B. 편집기 묶음의 `imagePolicyNote` 옆에 독립 `<small>`** | 이미지 유무와 무관하게 모달을 연 게시판 유형으로만 정해진다. 기존 `applyWritePolicy`의 토글 패턴을 그대로 쓴다. 대표 상태 기계 무변경. | 대표 선택지와 위치가 약간 떨어진다(바로 아래 묶음이라 실질 차이 작음). |

**결정 B**. `applyWritePolicy`에서 `modalBoard.boardType`이 `GALLERY`·`WEBZINE`일 때만 보이고 `LIST`·값 없음(구버전 응답·`modalBoard` 없음)이면 숨긴다. 문구는 **코드 상수**를 `textContent`로 넣는다(사용자 입력 없음).

문구(초안):
- 갤러리: "이 게시판은 갤러리형입니다. 공개 일반 목록에 글마다 대표 이미지가 격자로 보이고, 대표를 고르지 않은 글은 빈 자리표시로 보입니다."
- 웹진: "이 게시판은 웹진형입니다. 공개 일반 목록에 대표 이미지가 보이고(본문에 글이 있으면 요약도 함께), 대표를 고르지 않은 글은 빈 자리표시로 보입니다."
- (상단 고정 영역은 유형과 무관하게 이미지 없는 한 줄이라 "일반 목록"으로 범위를 좁힌다 — R1.)
- 공통 꼬리(이미지 허용 게시판만): " 본문에 이미지를 넣은 뒤 아래 '대표 이미지'에서 고르세요."

### D4. 본문 이미지를 끈 갤러리·웹진 게시판 — **꼬리 문장만 뺀다**

`bodyImageYn=false`이면 새 이미지를 넣을 수 없으므로 "본문에 이미지를 넣은 뒤" 안내는 거짓이 된다. 이때는 유형 문장만 보이고, 기존 `imagePolicyNote`("새 본문 이미지를 허용하지 않습니다. 이미 들어 있는 이미지는 유지됩니다")가 이어서 사실을 말한다(기존 이미지는 여전히 대표로 고를 수 있다 — PR 5 계약). 별도 경고·저장 차단은 하지 않는다(정의 조합의 타당성은 ADMIN의 판단이고 서버는 이 조합을 막지 않는다).

### D5. 보기 모드·목록 — **편집 폼에만 둔다**

완료 기준이 "편집 폼"이고, 보기 모드는 대표 미리보기("없음" 포함)를 이미 보인다. 게시판 선택 영역·목록 열에 유형 배지를 다는 것은 요청 범위 밖이라 하지 않는다(과설계 방지).

### D6. 낡은 유형 — **수용**

`modalBoard`는 페이지 로드 시점의 내 게시판 응답이다. 그 뒤 ADMIN이 유형을 바꾸면 안내가 낡지만 안내일 뿐 서버 동작·저장 내용에 영향이 없다(대표 지정 계약은 유형과 무관). 다른 정책 필드(`pinMaxCount` 등)와 같은 수준의 한계다.

### D7. 시험 범위 — **API는 실스택 통합 시험, 화면은 Playwright 실기 검증(새 브라우저 시험 클래스 없음)**

- API: `BoardTypeApiIntegrationTest`에 추가 — ⓐ READ만 가진 MANAGER가 GALLERY·WEBZINE·LIST 게시판의 `boardType`을 받는다 ⓑ 알 수 없는 저장값(`FUTURE`)은 `LIST`로 보인다 ⓒ 같은 MANAGER의 `GET /admin/api/boards/{id}`는 여전히 403(정의 API 권한 불변).
- 화면: 토글은 `modalBoard.boardType` 3분기 + `bodyImageYn` 1분기뿐이라 회귀 위험이 작고, `posts.html` 브라우저 시험 픽스처(회원·게시판·편집기)를 새로 세우는 비용(브라우저 job 시간 증가)이 이득보다 크다. 실기 검증에서 갤러리·웹진·목록형·이미지 금지 갤러리 4경우를 스크린샷으로 확인한다. — 리뷰에서 반대 근거가 나오면 재검토.

## 설계 제약(프로젝트 규칙에서 온 것)

- DTO 경계: 엔티티 비노출, 응답 DTO에만 필드 추가(`@Schema` 설명 포함, 기존 "서버 판정이 최종" 문체).
- 화면 문자열은 `textContent`만(기존 `posts.html` 규칙). 새 인라인 스크립트 블록을 만들지 않고 기존 스크립트 안에서 처리한다.
- 인가 정책·스키마·마이그레이션 변경 없음.

## 작업 단계

1. `MyBoardResponse`에 `BoardType boardType` 필드 + `@Schema`("공개 목록 모양 — 해석값, 알 수 없는 저장값은 LIST. 화면 안내용").
2. `MyBoardService`가 `board.type()`을 넘긴다.
3. `posts.html`: `imagePolicyNote` 옆에 `<small id="coverTypeNote" class="form-text text-muted d-none">`, `applyWritePolicy`에서 문구·표시 결정.
4. `BoardTypeApiIntegrationTest`에 D7 ⓐ~ⓒ 시험.
5. 영향 범위 시험: `com.cms.admin.board.*` + `*ConventionTest`.
6. 실기 검증(Playwright): ADMIN·MANAGER로 네 경우 확인, **같은 페이지에서 갤러리 → 목록형 → 이미지 금지 웹진 순으로 모달을 다시 열어 안내·꼬리 문장이 남지 않는지**(R1), 목록형 회귀, 기존 대표 선택 동작 회귀.
7. 기록: `com.cms.admin.board`의 `CLAUDE.md`("`MyBoardResponse`에는 없다" → 갱신), 이 문서의 구현·검증 결과.

## 리스크

| 리스크 | 대응 |
|---|---|
| 응답 필드 추가가 다른 소비자를 깨뜨림 | 소비자는 `posts.html` 하나(grep), 시험은 필드별 단언이라 영향 없음. 전역 `non_null`이어도 enum은 항상 값이 있어 키가 빠지지 않는다. |
| 안내 문구가 실제 공개 화면과 어긋남 | 공개 목록과 같은 해석값(D1). 자리표시 동작은 #189 계약 그대로. |
| 롤백 | 응답 필드 하나·화면 문구 하나라 이전 앱으로 돌아가면 안내만 사라진다. 정리할 데이터 없음. |

## 구현·검증 결과 (2026-10-31)

### Context

게시글 관리 화면이 게시판 유형을 몰라 갤러리·웹진 게시판에 글을 쓰는 사람이 대표 이미지의 쓰임을 알 수 없던 문제를, 내 게시판 응답에 해석된 유형을 싣고 편집 폼에 안내 한 줄을 두는 것으로 해소했다. 계획과 달라진 결정은 없다.

### 핵심 확정 사항

- `MyBoardResponse.boardType` = `Board.type()`(해석값, enum 이름 직렬화). READ 권한자에게 그대로 — `SecurityConfig`·`AdminFeature`·`@PreAuthorize` 무변경, 게시판 정의 API는 MANAGER 403 그대로(시험·실기 확인).
- 안내 `coverTypeNote`는 `imagePolicyNote` 바로 아래(대표 묶음 밖), `applyWritePolicy` → `renderCoverTypeNote(imagesAllowed)`가 모달을 열 때마다 계산. 문구는 코드 상수(`COVER_TYPE_NOTES`)를 `textContent`로, 조회는 `hasOwnProperty`로 3값 밖 값을 배제.
- 스키마·마이그레이션·인가 정책 변경 없음.

### 구현 파일

| 파일 | 변경 |
|---|---|
| `src/main/java/com/cms/admin/board/dto/response/MyBoardResponse.java` | `BoardType boardType` 필드 + `@Schema` |
| `src/main/java/com/cms/admin/board/service/MyBoardService.java` | `board.type()` 전달 |
| `src/main/resources/templates/admin/board/posts.html` | `#coverTypeNote` 요소, `COVER_TYPE_NOTES`·`renderCoverTypeNote` |
| `src/test/java/com/cms/admin/board/BoardTypeApiIntegrationTest.java` | 시험 2건(아래) |
| `src/main/java/com/cms/admin/board/CLAUDE.md` | "`MyBoardResponse`에는 없다" 정정, 편집 폼 안내 규칙 기록 |

### 검증 결과

- **시험**: `./gradlew test --tests 'com.cms.admin.board.*' --tests '*ConventionTest' -PbrowserTests=exclude` — 455건 통과(실패 0). 새 시험: `myBoardsExposeTypeToReadOnlyManager`(READ만 가진 MANAGER가 GALLERY·WEBZINE·LIST를 받고 `actions`는 `["READ"]`, ADMIN도 받음, 같은 MANAGER의 `GET /admin/api/boards/{id}` 403), `myBoardsUnknownStoredValueReadsAsList`(`FUTURE` → `LIST`, 응답에 원본 없음). 전체 `./gradlew test`는 PR 직전(code-review-loop 마무리)에 1회 돌린다.
- **실기(bootRun + Playwright, dev DB)**: READ·CREATE만 가진 MANAGER와 ADMIN으로 확인.
  - API: 6개 게시판(갤러리·웹진·목록·이미지 금지 갤러리·이미지 금지 웹진·저장값 `FUTURE`)의 `boardType`이 각각 GALLERY·WEBZINE·LIST·GALLERY·WEBZINE·LIST, 응답에 `FUTURE` 문자열 없음, 게시판 정의 API 403.
  - 화면: 갤러리 새 글 — 본문 이미지가 없어 대표 묶음은 숨었지만 안내는 보임(`.playwright-mcp/note-01-gallery-manager.png`). **같은 페이지에서 목록 → 이미지 금지 웹진 → 이미지 금지 갤러리 → 웹진 → `FUTURE` → 갤러리 순으로 모달을 다시 열었을 때** 안내 표시·꼬리 문장·`imagePolicyNote`가 매번 그 게시판에 맞게 바뀌고 이전 상태가 남지 않음(R1).
  - 데이터 왕복: MANAGER가 대표 있는 글·없는 글 생성(201) → ADMIN 편집 폼에서 안내와 대표 선택지(없음/108 선택됨)가 함께 보임(`.playwright-mcp/note-02-gallery-admin-edit-with-cover.png`) → 제목만 바꿔 저장 → 대표 108 유지(키 미전송 = 유지, 기존 계약 회귀 없음) → 공개 `/main/boards/board-14`에서 대표 없는 글은 자리표시, 있는 글은 `?size=thumb` 썸네일(안내 문구와 일치).
  - 콘솔 오류: 기존 favicon 404와 의도한 403 탐침뿐.
  - 정리: 검증 게시판 6개·게시글 2개는 관리 API로 소프트 삭제, 검증용 MANAGER와 권한 행은 삭제. 업로드한 이미지 108은 라이브러리 수동 회수 대상으로 dev DB에 남음.

### 이슈

- 첫 영향 범위 시험이 18건 실패 — Docker Desktop이 꺼져 Testcontainers가 기동하지 못한 환경 문제(`Could not find a valid Docker environment`). 기동 후 재실행 통과. 코드 문제 아님.
- Docker 기동과 함께 이전 이미지의 `cms-app-dev` 컨테이너가 8080을 점유 — 실기 검증 동안 멈추고 끝난 뒤 다시 시작했다.
- 실기 스크립트의 모달 연속 열기에서 열림 전환 중 닫기 호출이 무시돼 1회 시간 초과 — 시험 스크립트의 대기 문제(전환 완료 후 닫도록 수정), 앱 결함 아님.

### 후속

- 없음(계획 범위 완료). 로드맵 반영은 `/updateRoadmap`.
