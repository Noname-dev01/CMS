# PLAN-board-definition — 게시판 정의 관리 고도화

- 작성: 2026-10-26 / 상태: **v1 초안. 전체 분할·결정·PR별 윤곽 작성, PR 1 상세 설계 포함, PR 0(게시글 마지막 수정자) 추가, PR 4를 4a(슬러그)·4b(공지 흡수)로 분할. 사용자 결정 Q1~Q6 반영 완료. 적대적 리뷰 5라운드(v6) — 5라운드 지적 2건 반영. **전체 계획 확정(사용자, 2026-10-26)** — 남은 쟁점은 4a·4b 상세 설계 범위로 해당 PR 착수 때 별도 리뷰. 착수 순서: PR 0 → PR 1. **PR 0·1·2 머지(#179·#180·#181) · PR 3 상세 설계(P3-v2, 리뷰 3라운드 ship) 후 구현·검증 완료(2026-10-28, 미커밋)****
- 로드맵: 아직 정식 항목 아님(이 계획이 리뷰를 통과하면 `/updateRoadmap` 또는 로드맵 갱신으로 등록)
- 개정 이력:
  - v1: 초안(전체 분할·결정·PR 1 상세).
  - v6 변경(리뷰 5라운드): R5-1 지금 열리는 링크를 치환하지 못하는 행(길이·본문 상한 초과, 인코딩 변형, 비정규 id)은 "미치환 + 점검 목록"이 아니라 **마이그레이션 fail-fast** + 배포 전 점검 SQL(0건 확인)로 대체 — 미치환은 배포 순간 404라 보존 계약을 깬다. 도메인 붙은 절대 URL은 수용하는 손실로 명시. R5-2 id 해석은 ASCII 정규식이 아니라 현 `Long.parseLong`(`Character.digit` — 전각·아라비아 인도 숫자 포함)과 같은 **공통 함수 하나**를 거부·치환·공지 일치가 공유. Java 마이그레이션 여부는 4a 상세 설계 핵심 결정(§5 PR 4a·4b).
  - v5 변경(리뷰 4라운드): R4-1 치환 결과가 컬럼 길이·도메인 본문 상한을 넘는 행은 미치환 + 점검 목록(컬럼 확장·상한 완화 안 함). R4-2 id 토큰 규칙 `\+?[0-9]+` + 십진수 정규화를 치환·숫자 경로 거부·4b 공지 일치 판정이 공유(`Long.parseLong`이 `+12`·`0012`를 받음), `long` 초과는 대상 아님(§5 PR 4a·4b).
  - v4 변경(리뷰 3라운드): R3-1 `board-{숫자}`는 시스템 전용(사용자 지정 400) — 영구 예약과 기본 슬러그 충돌 방지(§5 PR 4a). R3-2 PR L은 경로 부분 퍼센트 인코딩 금지(정규형만 저장, 쿼리는 허용), URL 컬럼의 인코딩 변형은 마이그레이션 점검 목록 + 숫자 경로 거부는 디코딩 기준(§5 PR L·4a). R3-3 4b 공지 링크 치환은 그 사이트의 해석 공지 게시판 = 게시글 게시판일 때만, 불일치는 점검 목록(§5 PR 4b).
  - v3 변경(리뷰 2라운드): R2-2 한 번 쓴 슬러그(현재·별칭)는 게시판 삭제 후에도 `RETIRED`로 영구 예약(301 캐시와 재사용 충돌 방지, `no-store` 대안 기각). R2-3 숫자 게시판 경로(`/boards/{숫자}`, 4b는 `/notices/{숫자}`) 링크 저장을 영구 400 + 치환된 배너·팝업 `version` +1(낡은 편집 화면 되돌림 방어). R2-1 sanitizer가 상대 경로 `href`를 제거해(`HtmlContentSanitizer` `addProtocols(http, https, mailto)`, `HtmlContentSanitizerTest.linkProtocols`) 지금 본문에는 내부 상대 링크가 존재할 수 없다 → **본문 내부 링크 허용을 새 PR L로 이 계획에 포함**(사용자 결정, 4a보다 먼저), 4a의 본문 치환·숫자 경로 거부는 PR L 이후 생긴 내부 링크가 대상(§5 PR L·4a).
  - v2 변경(리뷰 1라운드): R1-2 상단 고정 개수 상한은 게시판 행 `FOR UPDATE`로 직렬화(§5 PR 3). R1-3 쓰기 중지 계약 확정 — 첨부 업로드·본문 이미지 업로드 차단, 첨부 삭제·게시글 삭제 허용, 게시판 `FOR SHARE`로 전환과 직렬화, 상태 코드 400(§4 D5). R1-4 `description`·`authorLabel` PATCH는 누락·null = 유지, `""` = 삭제(§4 D6). R1-5 링크 치환은 값 전체가 루트 상대 경로일 때만 — URL 컬럼은 값 시작 고정, HTML은 `href` 값 시작 고정, 본문 텍스트 속 URL은 대상 아님(§5 PR 4a). R1-6 썸네일 디코딩 실패 400·동시 생성 상한(§5 PR 5). R1-1 슬러그 1회 변경 후 링크 재파손 → 옛 슬러그 별칭 301(사용자 결정), 유일성은 현재 ∪ 별칭(`board_slug` 테이블 추천), 저장된 링크 재치환은 기각(§5 PR 4a).
- 이 문서의 **상세 설계 범위는 PR 1**이다. PR 2~6은 "PR별 윤곽"에서 범위·쟁점·영향만 다루고, 각 PR 착수 때 이 문서에 상세 설계를 덧붙여 별도로 리뷰한다(`PLAN-site-menu.md`와 같은 방식).

## 1. Context

게시판 정의 관리 화면(`/admin/board/manage`, ADMIN 전용)이 다루는 속성은 이름·공개 여부(`publicYn`)·첨부 허용(`attachmentYn`)·표시 대상(`siteScope`, 사이트 전용 공지 지정 포함) 4개뿐이다. 게시판마다 달라야 할 동작이 전부 전역 상수에 묶여 있다.

| 지금 고정된 것 | 위치 |
|---|---|
| 공개 목록 10건/페이지 | `PublicBoardService.PAGE_SIZE` |
| 첨부 글당 5개·파일당 10MB·확장자 화이트리스트 | `AttachmentFilePolicy.MAX_COUNT_PER_OWNER`·`MAX_FILE_SIZE`·`ALLOWED_CONTENT_TYPES_BY_EXTENSION` |
| 본문 이미지 허용 | 게시글은 항상 허용(`ContentBodyPolicy`) |
| 공개 목록 정렬 `createDate desc, id desc` | `PostRepositoryImpl.searchPublished` |
| 공개 URL `/{siteKey}/boards/{id}` | `PublicBoardController` |
| 공개 목록 표시 항목(제목·날짜) | `templates/public/board/list.html` |

이 계획은 게시판 정의를 "등록"에서 "게시판별 정책"으로 확장한다.

### 확정 사항 (사용자 결정, 2026-10-26 — 재질문 금지)

| 항목 | 결정 |
|---|---|
| 범위 | ① 표시·식별 ② 작성 정책 ③ 구조·정렬 ④ 슬러그·게시판 유형 |
| 댓글 | **제외. 로드맵 후보로도 남기지 않는다** |
| 갤러리 대표 이미지 | 글마다 대표 이미지를 지정하고 **서버가 썸네일을 생성**한다 |
| 슬러그 URL | **완전 교체**. 공개 게시판 URL은 슬러그만 쓰고 `/{siteKey}/boards/{id}`는 404 |
| 슬러그 유일 범위 | **전역 유일**(사이트 소속과 무관하게 미삭제 게시판 전체에서 하나) |
| 슬러그 변경 | 마이그레이션이 넣은 **기본값일 때만 1회 변경** 가능, 그 뒤 불변. **옛 슬러그는 별칭으로 남아 새 슬러그로 301**(리뷰 R1-1 후 결정) |
| 저장된 링크 치환 | `/boards/{id}`를 **마이그레이션으로 슬러그 URL로 치환**(업그레이드 경로 시험). 대상은 메뉴·배너·팝업 링크 URL과 **본문 전부**(게시글·정적 페이지 **모든 개정**·FAQ 답변·HTML 팝업). 경로 형태만 치환하고 도메인이 붙은 절대 URL은 점검 SQL로 남긴다 |
| 공지 | **`/notices`를 슬러그로 흡수**. `/{siteKey}/notices`는 그 사이트의 공지 게시판(전용 → 없으면 공통)의 `/boards/{slug}`로 **302**, 하위 경로(`/notices/{id}` 등)는 404. 공지 전용 공개 템플릿·컨트롤러는 제거 |
| 썸네일 공개 경로 | `/content-images/{id}?size=thumb` — `SecurityConfig` 매처 변경 없음 |
| PR 4 분할 | **4a 슬러그 / 4b 공지 흡수**, 둘 다 공개 URL 계약 변경 승인 대상 |
| 본문 내부 링크 | 지금은 sanitizer가 상대 경로 `href`를 지워 본문에 내부 링크를 걸 수 없다(도메인을 포함한 절대 URL만 가능). **루트 상대 내부 링크 허용을 PR L로 이 계획에 포함**(리뷰 R2-1 후 결정) |
| 진행 방식 | 이 전체 계획 → `plan-review-loop` → PR 1부터 `/feature` |
| 게시글 마지막 수정자 | 지금은 기록되지 않는다(`Post`에 `authorId`·`updateDate`만 있음). **별도 작은 PR(PR 0)로 추가**하고 PR 1에 섞지 않는다. 개정 이력(변경 전·후 내용)은 **필요 없음** |

## 2. PR 분할

| PR | 내용 | 의존 | 스키마 | 인가 정책 |
|---|---|---|---|---|
| **0** | 게시글 마지막 수정자 기록(`post.updater_id`) — 관리 화면 표시만 | 없음(다른 PR과 독립, 순서 무관) | `post` 컬럼 | 없음 |
| **1** | 표시·식별(설명·목록 크기·작성자/조회수 표시·새 글 표시 기간·사용 여부) + **게시판 `version` 낙관적 검사** | 없음 | `board` 컬럼 | 없음 |
| **2** | 작성 정책(첨부 개수·용량·확장자, 본문 이미지 허용, 예약 게시 허용) | 1(`version`) | `board` 컬럼 | 없음 |
| **3** | 게시판 노출 순서 + 상단 고정 글 | 1 | `board`·`post` 컬럼 | 없음 |
| **L** | 본문 루트 상대 내부 링크 허용(sanitizer 허용 목록·편집기·출력 재검증) | 없음(4a의 선행) | 없음 | `SecurityConfig` 변경 없음. **보안 화이트리스트 변경 — 착수 전 승인** |
| **4a** | 슬러그 URL 완전 교체 + 저장된 링크 치환(메뉴·배너·팝업·본문) | 1·L | `board.slug` + DML | **공개 URL 계약 변경 — 착수 전 승인** |
| **4b** | `/notices` 흡수(302) + 공지 전용 공개 템플릿·컨트롤러 제거 + `/notices/{id}` 링크 치환 | 4a | DML | **공개 URL 계약 변경 — 착수 전 승인** |
| **5** | 대표 이미지 지정 + 썸네일 생성 | 없음(6의 선행) | `content_image` 컬럼 등 | 없음(`?size=thumb`, 매처 불변) |
| **6** | 게시판 유형(목록형·갤러리형 등) | 1·5 | `board` 컬럼 | 없음 |

- PR 0은 게시판 정의가 아니라 게시글 변경이라 별도 PR로 둔다("한 번에 하나의 관심사"). 다른 PR과 의존이 없어 아무 때나 머지할 수 있다.
- 순서 근거: 이후 모든 PR이 게시판 편집 필드를 늘리므로 동시 수정 보호(`version`)를 PR 1에 먼저 둔다. 4a는 1·L 이후 언제든 가능하고 2·3과 독립이다. L이 4a보다 먼저인 이유: L 이전에는 본문에 내부 링크가 없어 4a의 본문 치환·숫자 경로 거부가 대상이 없고, L 이후 생긴 `/{siteKey}/boards/{id}` 본문 링크를 4a 마이그레이션이 한 번에 치환한다. 4b는 4a의 슬러그가 있어야 한다. 6은 5 없이는 갤러리를 만들 수 없다.
- **4b가 끝나면 공개 화면이 한 벌이 된다**(쟁점 1 해소). 따라서 4b 이전에 머지되는 PR(1·3)은 공지 템플릿에도 반영해야 하고, 4b 이후의 PR(5·6)은 게시판 템플릿만 보면 된다. 4b를 앞당기면(1 직후) 1·3의 공지 템플릿 작업이 줄어든다 — 순서 재배치를 리뷰에서 함께 판단한다.
- 한 계획 = 여러 PR인 예외다(`PLAN-site-menu.md`·`PLAN-menu-permission-management.md` 선례).
- 마이그레이션 번호는 작성 시점의 최대 버전 다음을 쓴다(현재 최대 V4). 첫 실배포 직전 합치기(V3~Vn → 기준 스키마)에 흡수될 수 있다(`docs/migration-guide.md`).

## 3. 정찰에서 발견한 공통 쟁점

코드 근거는 모두 읽어서 확인한 것이다.

1. **공개 화면이 두 벌이다.** 공지는 공지 게시판의 게시글이지만 공개 `/notices/**`는 URL·템플릿 계약 유지를 위해 `templates/public/notice/*`·`notice.css`를 따로 쓴다(`PublicNoticeService`는 `PublicBoardService`에 위임하는 얇은 어댑터). 표시 속성(PR 1)·상단 고정(PR 3)·유형(PR 6)은 **두 템플릿 세트에 모두 적용하거나 공지에 적용하지 않음을 명시**해야 한다.
2. **공개 경로는 한 쌍이다**(`com.cms.admin.board` CLAUDE.md "공개 경로 다섯 곳"). 목록·COUNT(`searchPublished`), 메인(`findLatestPublished`), 상세·첨부(`findPublishedById`), 본문 이미지(`existsPublishedPostRef`)가 같은 공개 조건을 쓴다. PR 1의 "사용 여부"와 PR 3의 정렬은 이 경로들을 건드린다.
3. **`authorId`는 관리자 로그인 ID 스냅샷이다.** 공개 DTO는 이 필드를 의도적으로 제외한다(`PublicPostDetail`). "작성자 표시"를 그대로 구현하면 **관리자 로그인 ID가 무인증 공개**된다. 계정 열거·무차별 대입의 표적이 된다. → §4 D3.
4. **게시판 수정에 동시 수정 보호가 없다.** `updateBoard`는 행 `FOR UPDATE` 후 부분 반영이라 낡은 화면의 저장이 다른 관리자의 변경을 덮는다. 정적 페이지(`SitePage.version` — 모든 변경에 +1, 요청 필수, 불일치 409), FAQ(구조 `version`)와 다르다. 필드가 늘수록 위험이 커진다.
5. **`publicYn`과 "사용 여부"의 의미가 겹칠 수 있다.** 공개 노출 차단은 `publicYn=false`가 이미 한다. 사용 여부는 다른 의미여야 존재 이유가 있다. → §4 D5.
6. **롤백 호환.** 이전 앱은 새 컬럼을 모르고 INSERT한다. 새 컬럼은 전부 `NOT NULL DEFAULT <현행 동작>`이어야 롤백한 앱의 게시판 생성이 깨지지 않고, 롤백 중 만든 게시판이 재배포 후 현행 동작을 유지한다. 단 슬러그(PR 4)는 UNIQUE라 DEFAULT가 불가능하다 → §5 PR 4.
7. **본문 이미지 업로드 권한 재판정과 정책 판정의 위치.** 업로드 진입점은 `BoardContentImageController` 하나이고 서비스가 CREATE∨UPDATE를 재판정한다. PR 2의 "본문 이미지 허용"은 같은 지점에 붙는다.
8. **이미지 형식은 PNG·JPEG·GIF뿐이다**(`ImageFileValidator.ALLOWED_CONTENT_TYPES`). JDK `ImageIO`가 셋 다 읽으므로 PR 5 썸네일에 **새 라이브러리가 필요 없다**. GIF는 첫 프레임만 쓴다(애니메이션 GIF는 이미 검증기가 차단).
9. **`/content-images/*` 공개 매처는 한 세그먼트다**(`SecurityConfig` 149~151행, 나머지 하위 경로는 `denyAll`). 썸네일을 `/content-images/{id}/thumbnail`로 두면 매처 변경 = 인가 정책 변경이다.
10. **메뉴 시드에는 `/boards/` 링크가 없다**(V2 확인). 치환 대상은 운영자가 만든 공개 사이트 메뉴 행이다. 배너·팝업 링크와 게시글·정적 페이지 본문 속 링크에도 `/boards/{id}`가 있을 수 있다 → §5 PR 4.

## 4. PR 1 상세 설계 — 표시·식별 + `version`

### D1. 스키마 (`V6__board_display_settings.sql`, DDL만 — V5는 PR 0이 썼다. `version` DEFAULT는 아래 초안의 0이 아니라 **1**, "PR 1 상세 설계 확인" P1-2 ②)

```sql
ALTER TABLE `board`
  ADD COLUMN `description` varchar(500) DEFAULT NULL,
  ADD COLUMN `list_page_size` int(11) NOT NULL DEFAULT 10,
  ADD COLUMN `author_label` varchar(50) DEFAULT NULL,
  ADD COLUMN `show_view_count` bit(1) NOT NULL DEFAULT 0,
  ADD COLUMN `new_badge_days` int(11) NOT NULL DEFAULT 0,
  ADD COLUMN `writable_yn` bit(1) NOT NULL DEFAULT 1,
  ADD COLUMN `version` int(11) NOT NULL DEFAULT 0;
```

- 모든 DEFAULT = 현행 동작(10건, 표시 안 함, 배지 없음, 사용 중). 롤백한 앱이 만든 게시판도 재배포 후 지금과 똑같이 보인다(쟁점 6).
- `description`은 **평문**이다(HTML 아님, 출력은 `th:text`). 공백 줄바꿈 표시는 CSS `pre-line`. HTML로 하면 sanitizer·편집기 1:1 규칙이 따라와 범위가 커진다.
- 값 범위(서비스 검증, 400): `list_page_size ∈ {10, 20, 30, 50}`(허용 목록 — 임의 값이면 COUNT 비용 계산이 흐려진다), `new_badge_days ∈ 0..30`(0 = 끔), `description` 500자·`author_label` 50자, 둘 다 앞뒤 공백 제거·빈 문자열은 null(`author_label` null = 작성자 표시 안 함).
- 컬럼명 `writable_yn`: 화면 이름은 "사용 여부"지만 의미는 "관리자 쓰기 허용"이다(D5). `use_yn`으로 하면 `post.use_yn`(글의 공개 노출)·`menu.use_yn`(활성)과 같은 이름이 다른 의미가 되어, `public_yn`처럼 이름만 보고 뜻을 잘못 읽기 쉽다.

### D2. `version` 낙관적 검사

- 선택지: (a) JPA `@Version` (b) 정적 페이지 방식(엔티티 변경 메서드가 `Math.incrementExact`, 서비스가 행 `FOR UPDATE` 뒤 요청 값과 비교). → **(b)**.
- 왜: 이 프로젝트의 변경 버전은 전부 (b)이고 잠금 순서·오류 메시지·409 계약이 서비스에 드러난다. `@Version`은 flush 시점 예외(`ObjectOptimisticLockingFailureException`)라 전역 핸들러 번역과 감사 실패 기록 경로를 새로 확인해야 한다.
- 계약: `PATCH /admin/api/boards/{id}`·`DELETE /admin/api/boards/{id}`에 **`version` 필수**(PATCH는 본문, DELETE는 쿼리 `?version=`). 누락·형식 오류 400, 불일치 **409**("다른 관리자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요."). 응답(`BoardResponse`)에 `version`.
- 모든 변경(`update`·`changeSite`·이후 PR의 정책 변경)이 `version`을 +1 한다. 소프트 삭제는 행이 사라지므로 올리든 말든 무관하지만 일관성을 위해 +1.
- **잠금 순서는 바뀌지 않는다**: 게시판 행 `FOR UPDATE` → 비교 → 변경. 게시글 생성의 게시판 `FOR SHARE`, 권한 PUT의 `FOR SHARE`와 기존 직렬화 그대로다. 게시글 쓰기는 게시판 `version`을 올리지 않는다.
- **API 호환 영향**: 기존 클라이언트(관리 화면 JS)는 `version`을 안 보내 400이 된다. 화면은 같은 PR에서 고친다. 외부 클라이언트는 없다(관리자 API).

### D3. 작성자 표시 — 로그인 ID를 공개하지 않는다

- 쟁점 3. 선택지: (a) `authorId`(로그인 ID) 노출 (b) 회원 이름(`userName`)을 조회해 노출 (c) **게시판별 고정 표시명**(`author_label`, 예: "관리자", "홍보팀") (d) 작성 시점 회원 이름 스냅샷 컬럼을 `post`에 추가.
- **결정 (c)**(사용자, 2026-10-26): 공개 게시판 작성자는 사실상 운영 주체라 게시판 단위 고정 표시명이 실용적이고, 회원 정보가 공개 경로로 흘러나가지 않는다. (b)는 공개 요청마다 회원 조회가 붙고 퇴사·이름 변경 시 과거 글 표시가 바뀐다. (d)는 `post` 스키마 변경과 기존 글 백필이 필요하다.
- 컬럼은 `author_label varchar(50) DEFAULT NULL`(null = 표시 안 함). 실제 작성자 추적은 `post.author_id`(관리 화면 표시)·감사 로그·PR 0의 최종 수정자가 맡는다.

### D4. 공개 화면 반영

- `PublicBoardService.getPublishedPosts`: `PAGE_SIZE` 상수 대신 게시판의 `listPageSize`. 페이지 번호 상한 검사가 있으면 함께 본다.
- 목록 DTO(`PublicPostSummary`): `viewCount`(게시판 `showViewCount`일 때만 채움 — 꺼진 게시판은 DTO 필드 null, 템플릿이 아니라 **서비스가 거른다**), `isNew`(서버 `Clock` 기준 `createDate`가 `new_badge_days`일 이내). 작성자 표시명은 게시판 단위 값이라 목록 모델에 한 번.
- 상세 DTO(`PublicPostDetail`): 같은 규칙으로 조회수·작성자 표시명.
- 설명: 목록 상단 `th:text`, `pre-line`.
- **공지 템플릿**(`public/notice/*`)에도 같은 반영(쟁점 1). 공지 게시판도 이 설정을 가진 게시판이기 때문이다. 공개 메인(`/`)의 공지 5건·새 글 5건은 바꾸지 않는다(카드 형태라 표시 항목이 다르다).
- `PublicBoardTemplateConventionTest`가 고정하는 "`th:utext`는 본문 한 곳" 규칙을 지킨다 — 설명은 평문이라 `th:text`.

### D5. 사용 여부(`writable_yn`) — 쓰기 중지(보관)

- 쟁점 5. 선택지: (a) **쓰기 중지(보관)**: 관리자 화면에서 새 글 작성·수정·첨부 업로드를 막고(409 또는 400) 조회·삭제만 허용, 공개 노출은 `publicYn`이 따로 결정 (b) **전면 중지**: 공개 404 + 관리 쓰기 차단 (c) 이 속성을 두지 않는다(`publicYn`으로 충분).
- **결정 (a)**(사용자, 2026-10-26): `publicYn`(공개 사이트 노출 — 끄면 관리자 전용 내부 게시판)과 의미가 겹치지 않고(공개는 `publicYn`, 쓰기는 `writable_yn`), 공개 경로 한 쌍(쟁점 2)을 건드리지 않아 PR 1이 공개 불변식을 바꾸지 않는다. 예: 끝난 이벤트 게시판을 공개는 유지하고 새 글만 막는다. (b)는 공개 경로 5곳 + 공지 경로 + 본문 이미지 판정을 모두 바꿔야 한다.
- **동작별 계약**(v2, 리뷰 R1-3):

  | 동작 | 권한 | 쓰기 중지 게시판에서 |
  |---|---|---|
  | 게시글 목록·상세·첨부 다운로드 | READ | 허용 |
  | 게시글 생성 | CREATE | **400** |
  | 게시글 수정(제목·본문·노출·기간) | UPDATE | **400** |
  | 첨부 업로드 | UPDATE | **400** |
  | 본문 이미지 업로드(`BoardContentImageController`) | READ + CREATE∨UPDATE 재판정 | **400** |
  | **첨부 삭제** | UPDATE | **허용** — 첨부가 남은 글은 삭제가 409라, 막으면 보관 게시판의 글을 지울 수 없다 |
  | 게시글 삭제 | DELETE | 허용 |

  - 상태 코드는 **400**("쓰기가 중지된 게시판입니다."): 같은 성격의 게시판 설정 거부인 `attachmentYn=false` 업로드가 400이라 맞춘다.
  - 노출만 끄는 수정(`useYn=false`)도 막힌다. 보관 게시판의 글을 숨기려면 쓰기를 잠시 켜거나 `publicYn`으로 게시판 전체를 숨긴다 — 단순성을 위해 수용하고 화면 안내에 적는다.
- **잠금 — 전환과 진행 중 쓰기를 직렬화한다**: 게시판 전환(PATCH)은 게시판 행 `FOR UPDATE`다. 막아야 할 쓰기는 모두 같은 게시판 행을 `FOR SHARE`로 잡고 그 안에서 `writable_yn`을 판정한다.
  - 생성: 기존 게시판 `FOR SHARE` 그대로(판정만 추가).
  - 수정·첨부 업로드: 기존 게시글 `FOR UPDATE` → **게시판 `FOR SHARE` 추가**(지금은 잠금 없이 미삭제 확인만 한다). 잠금 순서 게시글 → 게시판. 게시판 쪽 잠금(PATCH·삭제)은 게시글 행을 잠그지 않으므로 순환이 없다 — 상세 확인 대상: 게시판 삭제의 `existsByBoardIdAndDeletedFalse`는 잠금 없는 읽기다.
  - 본문 이미지 업로드: 게시판 `FOR SHARE` 후 판정(게시글 행과 무관).
  - 첨부 삭제·게시글 삭제는 판정하지 않으므로 잠금도 추가하지 않는다.
- 공통 공지 게시판은 끌 수 없다(`publicYn` 비공개 전환 금지와 같은 이유).

### D6. 관리 API·화면

- `BoardCreateRequest`·`BoardUpdateRequest`에 새 필드(PATCH는 null = 유지). "변경할 필드 없음" 400 검사에 새 필드를 포함한다.
- **지울 수 있는 문자열 필드(`description`·`authorLabel`)의 PATCH 의미**(v2, 리뷰 R1-4): 키 누락·`null` = 유지, **`""`(또는 공백만) = 삭제(null로 저장)**, 그 밖 = 정리 후 저장. 서비스가 "삭제 요청"을 null과 구분해 엔티티에 넘긴다 — 기존 `Board.update`의 "null이면 유지" 패턴에 정규화 결과를 그대로 넘기면 지울 수 없다. 생성 요청에서는 `""`·누락 모두 null. 화면은 입력을 비우면 `""`를 보낸다.
- `BoardResponse`에 새 필드 + `version`. `MyBoardResponse`에는 `writableYn`만 추가한다(게시글 화면의 작성·수정·첨부 버튼 숨김용 — 서버 판정이 최종).
- 화면(`admin/board/manage.html`): 모달을 "기본"·"공개 화면 표시" 두 묶음으로 나누고 새 필드를 넣는다. 목록 열에 "사용"·"목록 크기" 추가. `publicYn` 체크박스 문구는 "공개 사이트에 노출(끄면 관리자 전용 내부 게시판)"으로 다듬는다 — 이름만으로는 무엇에 대한 공개인지 드러나지 않는다(사용자 피드백). API·컬럼 이름은 바꾸지 않는다. 409 수신 시 안내 후 목록 다시 불러오기. 저장 중 잠금·늦은 응답 폐기 등 기존 비동기 보호를 유지한다.
- 감사: `BOARD_UPDATE` 그대로. `targetLabel` 없음 유지(설명·표시명은 사용자 입력).

### D7. 시험 (PR 1)

- 마이그레이션: 기존 행이 DEFAULT로 채워지고 공개 목록이 10건 그대로임(업그레이드 경로).
- `BoardService`: 범위 검증 400 각 경계, `version` 누락 400·불일치 409·성공 시 +1, 삭제의 `version`.
- 동시성: 두 화면이 같은 `version`으로 PATCH → 하나 200, 하나 409(`BoardPermissionIntegrationTest` 계열 실 DB).
- 공개: 목록 크기·조회수 표시 on/off·새 글 배지 경계(정확히 N일 전 포함/제외 — 경계 정의를 상세 설계에서 고정)·설명 XSS 이스케이프, **공지 경로에도 같은 결과**.
- D5: 쓰기 중지 게시판에서 §D5 표의 동작별 결과(생성·수정·첨부 업로드·본문 이미지 업로드 400, **첨부 삭제 → 게시글 삭제 연속 성공**, 조회 허용), 공통 공지 게시판 중지 불가, 전환과 진행 중 수정·업로드의 직렬화(실 DB 래치 시험 — 전환이 공유 잠금을 기다림).
- D6: `description`·`authorLabel`을 설정 → `""`로 삭제 → 누락 PATCH로 유지 확인.
- 화면: playwright로 생성·수정·409 안내 확인.

## 5. PR 0·2~6 윤곽

### PR 0 — 게시글 마지막 수정자

- 배경: 고정 표시명(D3)을 쓰면 공개 화면에서 실제 작성자가 보이지 않는다. 추적 수단은 `post.author_id`(최초 작성자, 관리 화면에 표시)와 감사 로그(`POST_UPDATE` 등 — 누가·언제·IP·대상 ID·결과)다. 다만 "마지막으로 고친 사람"은 감사 로그를 게시글 ID로 찾아야만 알 수 있다. 이 PR은 그것을 게시글에 직접 남긴다.
- 스키마: `post.updater_id VARCHAR(100) DEFAULT NULL`(DDL만). **기존 글은 NULL**("-" 표시) — 실제 수정자를 모르므로 작성자로 백필하지 않는다.
- 기록: 게시글 수정(`PostService.updatePost`)에서 현재 관리자 ID를 저장한다. `authorId`와 같은 출처(인증 주체의 userId)·같은 길이 규칙. **첨부 업로드·삭제도 수정으로 기록하고 `update_date`도 함께 갱신한다**(사용자 결정 2026-10-26 — 권한상 첨부는 `UPDATE` 동작이라 일관되고, 수정자와 수정 시각이 어긋나지 않는다). 첨부 서비스는 이미 게시글 행 `FOR UPDATE`를 잡으므로 같은 잠금 안에서 갱신한다.
- 노출: 관리 API 응답(`PostResponse`·`PostSummaryResponse`)과 관리 화면(`posts.html` 목록·상세 "최종 수정자", `textContent`/`escapeHtml`)만. **공개 DTO에는 넣지 않는다**(로그인 ID).
- 원본은 감사 로그다. `updater_id`는 조회 편의용 사본이며 감사를 대체하지 않는다.
- 롤백: 이전 앱은 이 컬럼을 모르므로 롤백 중 수정은 `updater_id`를 갱신하지 않는다 → 재배포 후 **이전 수정자가 잘못 표시될 수 있다**. 수용하고 `docs/deployment.md` 롤백 주의에 "최종 수정자는 감사 로그가 원본"을 적는다.
- 시험: 수정 시 기록·작성자와 다른 관리자가 고치면 그 관리자·기존 글 NULL·공개 응답 미포함(목록·상세·공지 경로)·첨부 업로드·삭제 시 수정자와 수정 시각 갱신·실패(400/409)한 첨부 요청은 갱신 안 함.

### PR 2 — 작성 정책

- 컬럼(초안): `attach_max_count`(1..5, DEFAULT 5), `attach_max_size_mb`(1..10, DEFAULT 10), `attach_extensions`(VARCHAR, NULL = 전역 화이트리스트 전체, 값은 전역 목록의 부분집합만), `body_image_yn`(DEFAULT 1), `publish_period_yn`(DEFAULT 1).
- **전역 상한은 천장이다.** 게시판 값은 전역 이하로만 설정할 수 있다. 보안 화이트리스트가 두 벌이 되지 않게 `AttachmentFilePolicy`가 "전역 검증 → 게시판 추가 제한" 순서로 하나의 진입점을 유지한다.
- 기존 데이터: 상한을 낮춰도 **이미 초과한 글은 그대로 두고 새 업로드만 막는다**(`attachmentYn`과 같은 규칙, 기본안).
- 쟁점:
  - 본문 이미지를 끈 게시판에서 **이미지가 있는 기존 글을 수정·저장**하면? (i) 저장 시 sanitizer가 이미지를 지운다(조용한 데이터 손실) (ii) 400으로 거부하고 이미지 제거를 요구 (iii) 기존 이미지는 유지하고 새 이미지만 거부(`replaceRefs`가 새 참조만 막음). 추천 (iii).
  - 예약 게시를 끈 게시판의 기존 예약 글: 기간은 계속 유효하고 새 입력만 400(추천) vs 끄는 순간 기간 무시.
  - 업로드와 정책 변경의 경합: 업로드는 게시글 `FOR UPDATE` 후 게시판을 읽는다. 게시판 `FOR SHARE`를 추가하면 정책 변경과 직렬화된다(잠금 순서 게시글 → 게시판; 게시판 삭제는 게시판 → 권한 행이라 순환 없음 — 상세 설계에서 재확인).
- 화면: `posts.html`이 게시판별 상한을 안내해야 하므로 `MyBoardResponse`에 정책 필드 추가. 편집기 툴바의 이미지 버튼 숨김(서버 판정이 최종).
- 공용 코드(`common/attachment`·`common/html`) 변경이라 **전체 `./gradlew test` 대상**.

### PR 3 — 노출 순서 + 상단 고정

- **게시판 순서**: `board.ord`. 쓰는 곳은 관리 목록·"내 게시판" 셀렉트·권한관리 화면의 게시판 표(공개에는 게시판 색인이 없다). 순서 저장 `PUT /admin/api/boards/order`(FAQ 선례: 낡은 버전 409, 집합 불일치 400). 생성과 순서 저장의 직렬화 수단 — 가드 행(`banner_lock`·`faq_lock` 선례) vs 미삭제 게시판 전체 `FOR UPDATE` — 상세 설계에서 정한다.
  - 가치 대비 비용 확인: 공개 영향이 없어 관리 편의뿐이다. 리뷰에서 범위 축소(관리 목록 정렬만) 여부를 함께 판단한다.
- **상단 고정**: `board.pin_yn`(허용), `board.pin_max`(예: 1..5), `post.pinned`(DEFAULT 0). 고정 글은 공개 목록 **1페이지 상단 별도 영역**에 노출하고 일반 목록에서도 빼지 않는 안(추천) vs 모든 페이지 상단. 메인(`findLatestPublished`)은 고정을 무시(추천).
  - 공개 경로 한 쌍 중 목록·COUNT만 바뀐다(고정 영역은 별도 쿼리, 같은 공개 조건 객체 재사용). 공지 템플릿에도 반영.
  - 고정 개수 상한(v2, 리뷰 R1-2): **고정을 켜는 쓰기는 게시판 행 `FOR UPDATE`** 아래서 "개수 확인 → 반영"을 한다. `FOR SHARE`끼리는 서로 막지 않아 상한 4/5에서 두 글을 동시에 고정하면 6이 된다. 고정 해제는 개수를 늘리지 않으므로 기존 잠금으로 충분하다. 게시판 `FOR UPDATE`는 그 게시판의 게시글 생성(`FOR SHARE`)을 잠시 막는다 — 고정 변경 빈도가 낮아 수용. 잠금 순서는 게시글 `FOR UPDATE` → 게시판 `FOR UPDATE`(PR 1 D5와 같은 방향)이며, 게시판 행만 잡는 쓰기(PATCH·삭제)와 순환하지 않음을 상세 설계에서 재확인한다. 동시 고정 시험(상한 직전 두 요청 → 하나만 성공).
  - 인덱스: `(board_id, deleted, use_yn, pinned, ...)` 필요 여부를 실측으로 판단.

### PR L — 본문 루트 상대 내부 링크 허용 ⚠ 보안 화이트리스트 변경

- 배경(리뷰 R2-1): `HtmlContentSanitizer`의 `a href`는 `http`·`https`·`mailto`만 허용하고 상대 경로 `href`는 지운다(`HtmlContentSanitizerTest.linkProtocols`가 `/admin` 링크의 `href` 제거를 고정). 그래서 관리자가 본문에서 자기 사이트의 다른 페이지로 링크하려면 도메인을 포함한 절대 URL을 써야 하고, 도메인이 바뀌면 깨진다.
- 범위: sanitizer를 쓰는 **모든 본문**(게시글·정적 페이지·FAQ 답변·HTML 팝업). 허용 목록은 한 벌이다(`ContentBodyPolicy`·`sanitize(html, allowImages)`·`sanitizePopup` — 링크 규칙은 공통).
- 허용 규칙(초안 — 상세 설계에서 확정):
  - 루트 상대 경로만: `/`로 시작하고 `//`(프로토콜 상대)·`/\`로 시작하지 않는다. 경로 상대(`boards/12`, `../x`)·쿼리만(`?a`)·프래그먼트만(`#a`)은 상세 설계에서 허용 여부를 정한다(추천: 루트 상대만).
  - 제어 문자·공백·역슬래시 거부.
  - **경로 부분의 퍼센트 인코딩 전부 거부**(v4, 리뷰 R3-2): `/main/boards/%31%32`는 브라우저에서 `/main/boards/12`로 해석되지만 원문 기준의 치환(4a)·숫자 경로 거부와 판정이 갈린다. 경로(`?`·`#` 앞)에 `%`가 있으면 `href`를 지워 **정규형만 저장**되게 한다. 이 CMS의 공개 경로(사이트 키·슬러그·숫자 id·정적 페이지 슬러그)는 전부 ASCII라 잃는 것이 없다 — 착수 시 정적 페이지 슬러그 형식이 ASCII인지 확인한다. 쿼리·프래그먼트의 인코딩(`?keyword=%ED%95%9C`)은 허용한다. `/%2F`·`/%5C` 같은 숨긴 구분자도 이 규칙으로 함께 막힌다.
  - **비공개 경로 금지**: `/admin`·`/actuator`·`/swagger-ui`·`/v3`·`/error` 등 — 공개 메뉴 링크 정책(`SiteLinkPolicy`)의 비공개 접두어 판정을 **재사용**해 두 벌이 되지 않게 한다. 대소문자·끝 슬래시·인코딩 변형도 같은 판정.
  - 위반 링크는 지금처럼 `href`만 지우고 글자는 남긴다(저장 거부 400이 아님 — 기존 sanitizer 동작과 일관). 출력 시에도 같은 정리를 다시 한다(저장 시·출력 시 이중, 멱등).
  - 강제 속성: 지금은 모든 링크에 `target="_blank"`·`rel="noopener noreferrer nofollow"`를 강제한다. 내부 링크도 같게 둘지(추천: 같게 — 규칙 단순·기존 시험 영향 최소), 같은 창으로 열지 상세 설계에서 정한다.
- 편집기: Quill 링크 입력이 상대 경로를 그대로 넣는지, 자체 정리(`Link.sanitize`)가 바꾸는지 context7로 확인(Quill 2.0.3). 툴바·`formats`는 그대로(`a`는 이미 허용).
- 사이트 범위: 링크 경로가 어느 사이트(`/{siteKey}/…`)를 가리키든 허용한다(다른 공개 사이트로의 링크도 유효). 존재하지 않는 경로 검사는 하지 않는다(링크 대상의 존재는 시점마다 바뀐다).
- 시험: 허용·거부 표(루트 상대·프로토콜 상대·역슬래시·인코딩 변형·비공개 접두어 변형·`javascript:`·제어 문자), 저장·출력 멱등, 기존 `linkProtocols` 시험의 `/admin` 기대는 **그대로 제거**(비공개 접두어라 여전히 지워짐)이고 공개 경로 링크 허용 기대를 추가, 네 본문 도메인 각각의 왕복.
- 위험: 보안 화이트리스트가 넓어진다. 오픈 리다이렉트는 없다(서버가 리다이렉트하지 않고 브라우저가 같은 출처로 이동). 착수 전 승인을 받는다.

### PR 4a — 슬러그 완전 교체 ⚠ 승인 대상

- 컬럼: `board.slug VARCHAR(50) NULL` + `UNIQUE`, `board.slug_customized BIT NOT NULL DEFAULT 0`. 미삭제 게시판은 앱이 NOT NULL을 보장하고, 소프트 삭제가 슬러그를 비운다(`board_key`와 같은 규칙 — 삭제 행이 유일성을 붙잡지 않음, MariaDB UNIQUE는 NULL 중복 허용).
- **옛 슬러그 별칭**(v2, 리뷰 R1-1 — 사용자 결정): 마이그레이션이 치환한 링크는 `board-{id}`를 가리키므로, 1회 변경 후에도 살아 있어야 한다. 변경 전 슬러그를 별칭으로 보관하고 `/{siteKey}/boards/{옛 슬러그}/**`는 같은 하위 경로의 새 슬러그로 **301**한다(쿼리스트링 유지). 1회만 바뀌므로 별칭은 게시판당 최대 1개. 저장된 링크는 다시 고치지 않는다(재치환은 관리자 요청 하나가 대량 UPDATE를 하고, 낙관적 락 없는 게시글 동시 편집과 경합해 기각).
  - **유일성은 현재 슬러그 ∪ 별칭 전체에서 하나**여야 한다(새 게시판이 남의 옛 슬러그를 가져가면 301과 충돌). 두 컬럼(`slug`·`previous_slug`)의 교차 유일성은 UNIQUE 인덱스로 표현할 수 없다 → 컬럼 대신 **`board_slug(slug PK, board_id, kind CURRENT|ALIAS)` 테이블**로 두어 DB가 강제하게 하는 안을 추천(상세 설계에서 확정). 이 경우 `board.slug` 컬럼은 두지 않거나 조회 편의 사본이 된다.
  - **한 번 쓴 슬러그는 영구 예약한다**(v3, 리뷰 R2-2): 301은 브라우저가 캐시하므로(RFC 9110 §15.4.2), 해제된 슬러그를 다른 게시판이 가져가면 캐시된 이동이 엉뚱한 게시판을 가리킨다. 소프트 삭제는 슬러그·별칭 행을 지우지 않고 **`RETIRED`로 남겨** 유일성을 계속 차지하게 한다(공개 응답은 404, 301 없음). 삭제된 게시판의 슬러그를 새 게시판이 재사용하는 것도 같은 이유로 막는다. 대안 `Cache-Control: no-store`는 이미 캐시된 이동과 중간 캐시를 보장하지 못해 기각.
  - 별칭 경로는 공개 인벤토리·레이트리밋에서 일반 게시판 경로와 같은 규칙을 탄다. 301 대상 판정도 공개 조건(`findPublicById`의 사이트 소속·공개·미삭제)을 먼저 통과해야 한다 — 비공개 게시판의 새 슬러그가 301 `Location`으로 새지 않게 같은 404.
  - 4b의 `/notices` 302는 현재 슬러그로 보낸다.
  - 시험: 변경 전 링크(목록·상세·첨부) 301 → 새 경로 200, 별칭과 같은 슬러그로 새 게시판 생성 409, 비공개 게시판 별칭 404, 두 번째 변경 400, **삭제된 게시판의 슬러그·별칭으로 새 게시판 생성 409 + 그 경로 404**.
- **낡은 편집 화면의 되돌림 방어**(v3, 리뷰 R2-3): 치환은 일회성인데, 배포 전에 열어 둔 메뉴·배너·팝업 편집 화면이 옛 숫자 경로를 다시 저장할 수 있다(메뉴 수정은 버전 검사가 없다 — `MenuService`).
  - ① **숫자 게시판 경로 저장 거부**: 슬러그는 숫자만으로 될 수 없으므로 `/{siteKey}/boards/{숫자}(/…)?` 링크는 언제나 404다. 메뉴·배너·팝업 링크 검증(DTO 형식 검사 뒤 서비스 정책 — `SiteLinkPolicy` 계열)이 이 형태를 **400**("게시판 주소는 슬러그 주소를 쓰세요")으로 거부한다. **본문(PR L 이후 내부 링크 허용)은 sanitizer가 이 형태의 `href`를 지운다** — 본문 규칙은 "위반 링크는 `href`만 제거"라 400이 아니다. 낡은 편집 화면이 옛 링크가 든 본문을 저장해도 링크가 숫자 경로로 되살아나지 않는다(글자만 남음). 저장 시 화면이 알 수 있게 안내할지는 상세 설계에서 정한다. 일회성 치환을 넘어 영구 규칙이라 낡은 화면·수동 입력 모두를 막는다. 4b는 같은 방식으로 `/{siteKey}/notices/{숫자}(/…)?`를 거부한다(목록 `/notices`는 302로 유효하므로 허용).
  - ② **치환된 배너·팝업 행은 `version`을 +1** 한다 — 낡은 화면의 저장이 409가 된다(제목만 고친 저장도 링크를 되돌리지 못한다).
  - "사이트 저장된 URL 불변식"(정책 강화 시 기존 행 409)과의 관계: 치환 마이그레이션이 숫자 경로를 전부 없앤 뒤에 규칙이 켜지므로, 남은 숫자 경로는 삭제된 게시판을 가리키는 행뿐이다. 그 행을 다른 필드만 바꿔 저장할 때의 동작(409로 막을지, 링크를 고치도록 안내할지)을 상세 설계에서 정한다.
- 마이그레이션: DDL(컬럼) → DML(미삭제 행에 `board-{id}` 백필) → DML(저장된 링크 치환) 분리.
- 형식(초안): `^[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?$`, **숫자만으로 된 슬러그 금지**(옛 id URL과 혼동 방지·향후 정책 여지), 연속 하이픈 금지 여부는 상세 설계.
- **`board-{숫자}`는 시스템 전용 이름 공간**(v4, 리뷰 R3-1): 사용자가 지정하는 슬러그(생성·1회 변경)가 `^board-[0-9]+$`이면 400. 기본 슬러그 `board-{id}`는 그 게시판만 받으므로, 영구 예약(`RETIRED`)이 있어도 다른 게시판의 기본 슬러그와 충돌하지 않는다(롤백 후 재배포 백필 포함). 이 규칙이 없으면 사용자가 쓰고 삭제한 `board-100`이 영구 예약되어 id 100 게시판의 기본 슬러그 부여가 실패한다.
- 변경 규칙: `slug_customized=0`일 때만 1회 변경 가능, 변경하면 1로. 새로 만드는 게시판은 생성 시 슬러그 입력 필수(→ 처음부터 1) vs 생략하면 `board-{id}`(→ 0, 나중에 1회 변경). 상세 설계에서 정한다.
- 라우트: `/{siteKey}/boards/{slug}`, `/{siteKey}/boards/{slug}/posts/{postId}`, `…/attachments/{attachmentId}`. 숫자 id 경로는 404(완전 교체). `SecurityConfig`의 `/boards/**` 계열 매처와 레이트리밋 패턴(`/boards/*/posts/*/attachments/*`)은 세그먼트 형태가 같아 **매처 자체는 바뀌지 않을 가능성이 높지만**, 공개 URL 계약이 바뀌고 공개 인벤토리 시험의 승인 목록이 바뀌므로 **인가 정책 변경으로 취급해 착수 전 승인**을 받는다.
- 코드 링크 갱신 범위: 공개 목록·상세 템플릿, 공개 메인 새 글 5건 링크, 관리 화면의 "공개 화면 보기" 링크(있으면), 사이트 메뉴 렌더링.
- **저장된 링크 치환**(사용자 결정 — 본문까지 모두):

  | 대상 | 테이블·컬럼(착수 시 확인) | 주의 |
  |---|---|---|
  | 공개 사이트 메뉴 | `menu.menu_url`(관리자 사이트 행 제외) | `SiteLinkPolicy` 저장된 URL 불변식을 치환 결과가 통과해야 한다 |
  | 배너·팝업 링크 | 배너·팝업의 링크 URL 컬럼 | |
  | 게시글 본문 | `post.content` | 정리된 HTML. 치환 후에도 `HtmlContentSanitizer` 결과가 같아야 한다(멱등) |
  | 정적 페이지 본문 | `site_page_revision`의 본문 — **모든 개정** | 개정은 불변 기록이지만 공개 사이트에서 이전 개정도 열람되므로 링크가 깨지지 않게 전부 치환한다(사용자 결정). "개정 본문이 마이그레이션으로 바뀌었다"는 사실을 `docs/migration-guide.md`에 남긴다. `version`·개정 번호는 올리지 않는다 |
  | FAQ 답변 | FAQ 답변 HTML | |
  | HTML 팝업 본문 | 팝업 HTML 본문 | |

  - 치환 규칙(v2, 리뷰 R1-5 — 양쪽 경계를 모두 고정한다):
    - **왼쪽**: 값 전체가 루트 상대 경로일 때만. URL 컬럼(메뉴·배너·팝업 링크)은 **값의 시작이** `/{siteKey}/boards/{id}`여야 한다. HTML 본문은 **`href` 속성 값의 시작이** 그 경로여야 한다. `https://…/main/boards/12`, `//host/main/boards/12`, 쿼리 매개변수 안의 `…?next=/main/boards/12`는 일치하지 않는다.
    - **오른쪽**: id 뒤가 값의 끝·`/`·`?`·`#`일 때만(`/boards/12`가 `/boards/123`을 건드리지 않게).
    - **id 해석은 현 파서와 같은 공통 함수 하나**(v6, 리뷰 R4-2·R5-2 — 치환·숫자 경로 거부·4b 공지 일치 판정이 공유): 지금 공개 컨트롤러는 `Long.parseLong`으로 id를 읽는다(`PublicBoardController`·`PublicNoticeController`의 `parseId`). `parseLong`은 각 문자를 `Character.digit(…, 10)`으로 읽으므로 `+12`·`0012`뿐 아니라 **전각 `１２`·아라비아 인도 숫자 `١٢`·혼합**도 12로 연다(`SafeUrls`도 막지 않음). ASCII 정규식(`\+?[0-9]+`)으로 흉내 내면 이 표기들이 치환과 거부를 모두 빠져나간다. 그래서 "경로 세그먼트 → 현 파서가 여는 비음수 `long` 값 또는 없음"을 돌려주는 **공통 함수 하나**를 두고(현 `parseId`와 같은 의미임을 시험으로 고정) 거부 규칙과 치환 판정이 이 함수를 쓴다. `long` 범위를 넘는 값·음수는 지금도 열리지 않으므로 대상이 아니다. 슬러그 형식(ASCII 소문자·숫자·하이픈, 숫자만 금지)은 이 함수가 값을 돌려주는 세그먼트와 겹치지 않는다.
    - **지금 열리는 링크를 치환하지 못하면 마이그레이션을 실패시킨다**(v6, 리뷰 R5-1 — v5의 "미치환 + 점검 목록"을 대체): 미치환 행은 배포 순간 404가 되어(출력 sanitizer는 본문의 숫자 `href`도 지운다) 저장된 링크 보존 계약을 깨므로, 다음 행이 하나라도 있으면 마이그레이션이 **명확한 메시지와 함께 실패**한다(fail-fast — 이전 버전이 계속 운영된다):
      - 치환 결과가 **컬럼 길이**(`menu.menu_url` 255, 배너·팝업 `link_url` 500 — 착수 시 스키마로 재확인)나 **그 도메인의 본문 상한**(`ContentBodyPolicy` — FAQ 답변 30,000바이트 등)을 넘는 행(v5 R4-1). `board-{id}`는 원래 id보다 6자 길다(4b 치환은 더 길다). 컬럼 확장·상한 완화는 하지 않는다.
      - URL 컬럼의 **인코딩 변형**(`/main/boards/%31%32`, v4 R3-2)과 **ASCII 정규형이 아닌 id**(`+12`·`0012`·전각·아라비아 인도 숫자 — SQL로는 `Character.digit` 해석을 재현할 수 없다).
      - `docs/deployment.md`에 **배포 전 점검 SQL**(이전 버전 DB에서 위 행 수 = 0 확인, 있으면 이전 버전 화면에서 내용 축약·링크 수정 후 재검사)을 둔다. 그 뒤에 남는 숫자 경로는 지금도 열리지 않는 행(삭제된 게시판·4b 공지 불일치)뿐이다.
      - 비정규 id를 정규형 SQL로 고쳐 치환까지 할지(=Java 마이그레이션으로 공통 함수를 그대로 쓸지, 이 프로젝트 첫 사례), 비정규 id는 fail-fast로만 다룰지는 **4a 상세 설계의 핵심 결정**이다. 실제로는 0건일 가능성이 높다.
    - **도메인이 붙은 절대 URL은 수용하는 손실**: 우리 도메인을 판별할 수 없어 치환하지 못하고 4a 이후 404가 된다. 아직 실배포 전이라 실데이터는 없다. 실배포 이후 4a를 배포한다면 도메인을 아는 상태이므로 그때 점검 SQL로 확인한다 — 이 손실을 수용함을 배포 문서에 명시한다.
    - **본문 텍스트 속 URL 문자열은 대상이 아니다**(링크가 아닌 글자를 바꾸지 않는다). 저장된 본문은 sanitizer 출력이라 속성 인용 형식이 일정하다는 점을 이용하되, 그 가정을 시험으로 고정한다.
    - `{siteKey}` 자리는 실제 존재하는 공개 사이트 키와 일치할 때만 치환한다.
    - **인코딩 변형**(v4, 리뷰 R3-2): 본문은 PR L이 경로 인코딩을 막아 정규형만 있다. URL 컬럼(메뉴·배너·팝업)에는 `/main/boards/%31%32`처럼 인코딩된 경로가 이미 있을 수 있다(기존 링크 검증이 `%`를 허용하는지 착수 시 `SafeUrls`·`SiteLinkPolicy`로 확인). 마이그레이션은 정규형만 치환하고, 인코딩 변형이 지금 열리는 링크면 아래 fail-fast 대상이다(v6). 4a 이후의 숫자 경로 거부는 **디코딩한 경로**로 판정해 인코딩 변형도 거부한다.
  - 도구: MariaDB `REGEXP_REPLACE`는 치환 값에 다른 행(게시판 슬러그)을 조인해야 해서 단일 문장이 어렵다 — 게시판별 반복 UPDATE(게시판 수만큼 문장 생성) 또는 Flyway Java 마이그레이션. 상세 설계에서 정한다(Java 마이그레이션은 이 프로젝트에 선례가 없다).
  - 도메인이 붙은 절대 URL(`https://…/main/boards/3`)은 도메인이 아직 없어 치환하지 않는다 → 점검 SQL을 `docs/deployment.md`에 둔다.
  - 삭제된 게시판 id를 가리키는 링크: 슬러그가 없으므로 치환하지 않는다(원래도 404).
- **롤백 위험**: 롤백한 앱이 만든 게시판은 `slug=NULL`이다. 재배포 시 슬러그 없는 미삭제 게시판이 생긴다. 대응 후보 — (i) 재배포 전 실행할 백필 SQL을 `docs/deployment.md`에 명시 (ii) 기동 시 검사해 경고/실패 (iii) 공개 조회에서 슬러그 없는 게시판은 404. 상세 설계에서 정한다. 또한 치환된 링크는 이전 앱에서 전부 404가 된다(이전 앱은 슬러그 경로를 모른다) — **4a는 사실상 롤백 불가 PR**로 문서화한다.
- 업그레이드 경로 시험: 위 표의 각 대상에 `/{siteKey}/boards/{id}`·`…/posts/{postId}`·`…/attachments/{id}`·쿼리스트링·프래그먼트 포함·접두 id 충돌(`12` vs `123`)·삭제된 게시판 id·관리자 사이트 메뉴(대상 아님)·**외부 호스트에 같은 경로가 붙은 URL(대상 아님)**·**프로토콜 상대 `//host/…`(대상 아님)**·**쿼리 매개변수 안의 경로(대상 아님)**·**본문 텍스트 속 경로 문자열(대상 아님)**·없는 사이트 키(대상 아님)·**`+12`·`0012`·전각·아라비아 인도 숫자·혼합 표기의 비정규 id(4a 상세 설계 결정에 따라 정규화 후 치환 또는 fail-fast)**·**`long` 범위 초과 id(대상 아님)**·**치환하면 컬럼 길이·본문 상한을 정확히 1 넘는 행(fail-fast)과 정확히 맞는 행(치환)**·**인코딩 변형(fail-fast)** 행을 넣고 치환 결과를 검증. fail-fast 시험은 마이그레이션 실패 후 데이터가 변하지 않았음(트랜잭션 롤백)까지 확인한다. 공통 id 함수는 현 `parseId`와 같은 입력 표(위 표기 전부)에서 같은 값을 돌려줌을 시험으로 고정한다. 치환 후 본문을 sanitizer에 다시 통과시켜 결과가 같음(멱등)도 확인.

### PR 4b — `/notices` 흡수 ⚠ 승인 대상

- `/{siteKey}/notices`(목록, 쿼리스트링 포함) → **302** `/{siteKey}/boards/{공지 게시판 슬러그}`. 공지 게시판 해석은 지금 규칙 그대로(사이트 전용 공지 `NOTICE:{siteId}` → 없으면 공통 `NOTICE`). 쿼리스트링(`page`·`keyword`)을 넘길지는 상세 설계.
- 하위 경로 `/{siteKey}/notices/{id}`·`…/attachments/{id}`는 **404**.
- 제거: `PublicNoticeController`(스트리밍 복제 포함)·`PublicNoticeService`·`templates/public/notice/*`·`static/css/public/notice.css`·관련 시험 이식 또는 삭제. 조회수·다운로드 집계 호출 복제도 사라진다.
- 공지 게시판은 이제 `/boards/{slug}`로 공개된다. 시스템 게시판 제약(비공개 전환 금지·공통 공지 삭제 금지)은 그대로다.
- 저장된 링크 치환: `/{siteKey}/notices/{postId}` → `/{siteKey}/boards/{slug}/posts/{postId}`. 대상은 4a 표와 같다.
  - **치환 조건**(v4, 리뷰 R3-3): 지금 `/{siteKey}/notices/{postId}`는 **그 사이트에서 해석되는 공지 게시판**(전용 → 없으면 공통)에 그 글이 속할 때만 열린다(`PublicNoticeService`). 게시글 id로 소속 게시판만 찾아 치환하면 지금 404인 링크(일반 게시판 글, 전용 공지가 대체한 공통 공지 글)가 다른 글로 열리게 되어 링크 의미가 바뀐다. 그래서 **마이그레이션 시점의 해석 결과(그 사이트의 공지 게시판 id) = 게시글의 `board_id`일 때만 치환**하고, 다르면 치환하지 않고 점검 목록으로 남긴다(그 링크는 이후에도 404 — 기존과 같다).
  - 시험: 해석 공지와 일치(치환), 일반 게시판 글(미치환), 전용 공지가 있는 사이트의 공통 공지 글(미치환), 없는 글·삭제된 글(미치환), `/notices/+42`·`/notices/0042`·전각 표기(4a의 공통 id 함수로 판정 후 같은 조건), 지금 열리는 링크의 길이 초과(fail-fast — 4a와 같은 규칙). `/{siteKey}/notices` 목록 링크는 302가 처리하므로 치환하지 않는다.
- 갱신: 공개 메인 공지 5건 링크, 레이트리밋 규칙(`/notices/**` 계열 — 302 응답에도 적용할지), 공개 인벤토리 승인 목록, `SiteKey.RESERVED`의 `notices`(경로가 남으므로 예약어 유지), 관련 `CLAUDE.md`(`com.cms.publicweb.notice` 문서는 제거 또는 축소).

### PR 5 — 대표 이미지 + 썸네일 ⚠ 공개 경로 방식에 따라 승인

- 대표 이미지 = **그 글 본문이 참조하는 이미지 중 하나를 고른다**(추천 — 공개 판정이 기존 `content_image_ref`를 그대로 따른다) vs 별도 업로드(새 출처·새 참조 종류).
- 썸네일은 **이미지 단위**로 만든다(추천): `content_image.thumb_storage_key`. 같은 이미지를 여러 글이 써도 한 번만 생성하고, 공개 판정은 원본 이미지와 같다. 글 단위(`post.cover_thumb_key`)는 판정·회수가 단순하지만 중복 생성된다.
- 생성 시점: 대표 지정 시 동기 생성(트랜잭션 안, `FileStorageTransactionSupport.deleteOnRollback`) vs 첫 요청 시 지연 생성(공개 GET이 쓰기를 하게 됨 — 비추천).
- 공개 경로: **`/content-images/{id}?size=thumb`**(사용자 결정) — `SecurityConfig`의 `/content-images/*` 매처 변경 없음, 같은 핸들러가 분기한다. 알 수 없는 `size` 값은 원본이 아니라 404(또는 400 — 상세 설계)로 처리해 동작을 열어 두지 않는다. 레이트리밋·브라우저 캐시 헤더는 원본과 같은 규칙인지 확인한다.
- 수명주기: 이미지 삭제(미디어 라이브러리)·이미지 교체 시 썸네일 삭제(커밋 후), `docs/deployment.md` 수동 회수 절차(②·⑤ 계열)에 썸네일 파일 보존·회수 규칙 추가, 카운터 상한(전체 용량)에 썸네일을 포함할지.
- 축소 규칙: 긴 변 기준(예: 480px), 원본이 더 작으면 생성하지 않고 원본을 쓴다, JPEG 출력 품질, PNG 투명도 보존(PNG 출력). `ImageIO` 디코딩 크기 상한은 기존 검증기의 픽셀 상한을 따른다.
- **디코딩 실패**(v2, 리뷰 R1-6): 본문 이미지 업로드 검증은 헤더만 본다(`ContentImageService`의 `LIMITS`, `ImageFileValidator`는 `fullDecode`일 때만 픽셀 디코딩). 그래서 헤더는 정상이고 픽셀이 손상된 기존 이미지가 있을 수 있고, 대표 이미지 지정 때 처음 실패할 수 있다. 계약: 대표 이미지 지정이 포함된 저장은 **400**("이 이미지로 대표 이미지를 만들 수 없습니다. 다른 이미지를 고르세요.")이고 트랜잭션 전체가 롤백된다(본문 수정도 저장되지 않음 — 화면이 입력을 보존). 이미 대표 이미지가 있는 기존 글은 영향이 없다(재생성하지 않는다). 대안(실패 시 원본으로 대체)은 큰 원본이 목록에 조용히 나가므로 기각.
- **동시 생성 상한**: 픽셀 상한은 이미지 한 장의 상한일 뿐이다. 썸네일 생성을 `Semaphore`로 동시 N개(예: 2)로 제한하고, 자리가 없으면 짧게 기다린 뒤(예: 2초) 실패 시 **503** + 재시도 안내. 생성은 트랜잭션 안이므로 대기 시간만큼 커넥션을 쥔다 — 생성을 트랜잭션 밖(저장 전)에서 하고 파일만 트랜잭션에 묶을지 상세 설계에서 정한다.

### PR 6 — 게시판 유형

- 컬럼: `board.board_type VARCHAR(20) NOT NULL DEFAULT 'LIST'`. 값(초안): `LIST`(현행), `GALLERY`(썸네일 격자), `WEBZINE`(썸네일 + 요약). 요약은 본문 텍스트 앞부분을 서버가 추출(sanitizer 결과에서 텍스트만, 길이 상한).
- 유형은 **공개 렌더링만** 바꾼다. 공개 경로·공개 조건은 같다. 템플릿은 `public/board/list.html` 안에서 분기 vs 유형별 파일 — 상세 설계.
- 대표 이미지가 없는 글은 자리표시 이미지(정적 리소스).
- 공지 게시판 유형 변경 허용 여부(공지 템플릿이 따로라 `LIST` 고정 추천).
- 목록 크기(PR 1)의 허용 목록이 갤러리 격자(3·4열)와 맞는지 확인.

## 6. 범위 밖

- 댓글(확정 결정 — 로드맵에도 넣지 않음).
- 게시판 복원(소프트 삭제 취소)·휴지통.
- 게시판 색인 페이지(`/{siteKey}/boards`).
- 도메인이 붙은 절대 URL 링크의 치환(점검 SQL만).

## 7. 사용자 결정 대기

| # | PR | 질문 | 추천 / 결정 |
|---|---|---|---|
| Q1 | 1 | 작성자 표시 방식(로그인 ID 노출 금지 전제) | **결정: 게시판별 고정 표시명(`author_label`)** |
| Q2 | 1 | 사용 여부의 의미 | **결정: 쓰기 중지(보관), 컬럼 `writable_yn` — 공개 노출은 `publicYn`** |
| Q3 | 4 | 공지 게시판 슬러그와 `/notices` | **결정: `/notices`를 슬러그로 흡수, 목록은 해석된 공지 게시판으로 302, 하위 경로 404 → PR 4b** |
| Q4 | 4 | 배너·팝업 링크·본문 속 `/boards/{id}` | **결정: 본문까지 모두 치환(정적 페이지는 모든 개정)** |
| Q5 | 5 | 썸네일 공개 경로 | **결정: `/content-images/{id}?size=thumb`** |
| Q6 | 0 | 첨부 업로드·삭제도 마지막 수정으로 기록할지(`update_date` 포함) | **결정: 포함 + 수정 시각도 갱신** |

위 표의 질문은 모두 결정됐다. 다만 각 PR 절 본문에 "상세 설계에서 정한다"로 남긴 세부 미결정이 있다 — 해당 PR 착수 때 상세 설계에서 정하고 사용자에게 묻는다.

| PR | 미결정(원본은 각 PR 절) |
|---|---|
| 2 | **결정 완료(PR 2 상세 설계 P2-v2)** — ① (iii) 존재하는 새 이미지만 400(깨진 ID는 조용히 뺌) ② 기존 기간 유효·설정만 400·비우기 허용 ③ PR 1 게시판 `FOR SHARE` 재사용, 새 잠금 없음 |
| 3 | 순서 저장과 생성의 직렬화 수단(가드 행 vs 전체 `FOR UPDATE`) / 고정 글 공개 노출 범위 / 순서 저장 범위 축소 여부 / 고정용 인덱스 |
| L | 경로 상대·쿼리만·프래그먼트만 링크 허용 여부(추천 루트 상대만) / 내부 링크 `target`·`rel` / 정적 페이지 슬러그 ASCII 확인 |
| 4a | SQL vs Java 마이그레이션(핵심) / 별칭 저장 구조(`board_slug` 추천) / 생성 시 슬러그 필수 여부 / 롤백 후 슬러그 없는 게시판 대응 / 마이그레이션 후 남은 숫자 경로 행 처리 / HTML 치환 대상 범위 |
| 4b | 302에 쿼리스트링 전달 여부 / 302 레이트리밋 적용 여부 |
| 5 | 썸네일 생성 트랜잭션 안/밖 / 용량 카운터 포함 여부 / 알 수 없는 `size` 404 vs 400 / 이미지 단위 vs 글 단위 |
| 6 | 템플릿 분기 방식 / 공지 게시판 유형 고정 여부 / 요약 추출 규칙 |

PR 1의 새 글 배지 경계는 결정됐다(2026-10-27, "PR 1 상세 설계 확인" P1-2 ④).

## 8. 리스크

- **API 계약 변경(PR 1)**: 게시판 PATCH·DELETE에 `version` 필수. 관리 화면 외 소비자는 없다.
- **공개 URL 파괴(PR 4a·4b)**: 외부에 공유된 `/boards/{id}`·`/notices/{id}` 링크·검색엔진 색인이 404가 된다(완전 교체 결정의 수용 비용). 아직 실배포 전이라 실사용 링크는 없다.
- **본문 일괄 치환(PR 4a·4b)**: 사용자 작성 HTML을 마이그레이션이 고친다. 잘못된 정규식은 본문을 망가뜨리고 되돌릴 수 없다 → 마이그레이션 전 백업 필수(`docs/deployment.md`), 업그레이드 경로 시험으로 경계 사례 고정, 정적 페이지 개정 이력이 바뀐다는 사실 기록.
- **롤백**: PR 0은 롤백 중 수정의 최종 수정자가 갱신되지 않는다(§5 PR 0). PR 1~3·6은 DEFAULT로 롤백 호환. **PR 4a·4b는 사실상 롤백 불가**(치환된 링크를 이전 앱이 모른다 — 롤백하려면 백업 복구). PR 5는 썸네일 파일이 남는다(이전 앱은 모름 — 수동 회수 절차).
- **공용 코드(PR 2)**: 첨부·본문 정책은 게시글 외 소비자가 있는지 착수 시 재확인(공지는 게시글이 됐고 프로필 이미지는 별도 검증기).

---

## PR 0 상세 설계 — 게시글 최종 수정자 (P0-v1, 2026-10-26)

§5 "PR 0" 윤곽의 상세 설계다. 개정 이력은 이 절 끝 "PR 0 개정 이력"에 따로 둔다.

### P0-1. 정찰 결과 (코드 근거)

- `Post`(`admin/board/domain/Post.java`)에는 `authorId`(작성자, NOT NULL)·`updateDate`만 있다. `updateDate`를 바꾸는 메서드는 `update(title, content, useYn, now)`(:75)와 `softDelete(now)`(:98) 둘이다. `changePublishPeriod`는 `updateDate`를 직접 바꾸지 않고 같은 트랜잭션의 `update`에 기댄다(:92).
- `PostService.updatePost`(:96)는 잠금 전 입력 검증 → `lockPost`(게시글 `FOR UPDATE` + 게시판 미삭제 확인) → `update`. 생성(:60)은 `requireCurrentAdminUserId()`(:191 — `AdminSecurityService.getCurrentAdminUserId()`가 null이면 `AccessDeniedException` → 403)로 작성자를 얻는다. 수정은 지금 현재 사용자를 읽지 않는다.
- `PostAttachmentService.upload`(:53)·`delete`(:131)는 게시글 행 `FOR UPDATE`를 잡지만 **반환된 `Post`를 버린다** — 그래서 지금은 첨부를 바꿔도 게시글 `updateDate`가 그대로다. 이 서비스에는 `AdminSecurityService` 의존성이 없다.
- 응답: `PostResponse`·`PostSummaryResponse`는 `authorId`·`updateDate`를 그대로 내보낸다. 공개 `PublicPostDetail.from`은 필드를 명시 매핑하고 `authorId`가 없다 — 새 필드는 손대지 않으면 공개로 나가지 않는다. 공개 템플릿은 `updateDate`를 쓰지 않는다(`templates/public/**` 사용 0건) — 첨부 변경으로 `updateDate`가 바뀌어도 공개 화면은 변하지 않는다.
- 관리 화면 `admin/board/posts.html`: 목록 8열(`ID·제목·작성자·노출 상태·노출 기간·조회수·작성일·상세`, `colspan="8"` 3곳 — :208, :714, :760), 상세 `viewAuthor`(:260)·`viewUpdateDate`(:284). 첨부 업로드·삭제 성공 후에는 첨부 목록만 다시 읽는다(:1246, :1282).
- 다른 `post` 쓰기 경로: `ContentCounterWriter`(조회수, JDBC 원자적 UPDATE — `update_date`를 건드리지 않음), 시험의 JDBC INSERT. 새 컬럼이 NULL 허용이라 둘 다 영향 없다.
- 시험: `PostServiceTest`(Mockito — 수정 시험은 현재 사용자 스텁이 없다), `PostAttachmentServiceTest`(`@InjectMocks` — 새 의존성 목 필요), `PostApiIntegrationTest`(실제 회원 주체 `TestMembers.asMember`, 실 DB), `BaselineSchemaTest`(새로 설치한 스키마 단언). 마이그레이션 가이드: 기존 행에 영향 없는 NULL 허용 컬럼 추가는 업그레이드 경로 시험 대신 `BaselineSchemaTest` 단언으로 충분하다(`docs/migration-guide.md` "시험 정책").

### P0-2. 설계 결정

**D0-1. 스키마** — `V5__add_post_updater_id.sql`(DDL 한 문장): `ALTER TABLE post ADD COLUMN IF NOT EXISTS updater_id varchar(100) DEFAULT NULL;`
- 길이·형식은 `author_id`(varchar(100))와 같다 — 같은 출처(로그인 userId)다.
- NULL 허용·백필 없음: 기존 글의 실제 수정자를 모른다(작성자로 채우면 거짓 정보). 이전 앱은 이 컬럼을 모르고 INSERT·UPDATE해도 실패하지 않는다(`ddl-auto: validate`는 남는 컬럼을 문제 삼지 않는다).
- 인덱스 없음 — 조회 조건·정렬에 쓰지 않는다(정렬 화이트리스트에 넣지 않는다).

**D0-2. 생성 시 값 — 작성자로 채운다** (계획 윤곽은 "수정 시 기록"만 적었다 — 이 결정으로 구체화)
- 선택지: (a) 생성 시 NULL, 수정할 때부터 기록 (b) **생성 시 작성자로 채움**.
- (b)를 고른 이유: `updateDate`는 생성 시 `createDate`와 같은 값으로 채워진다 — 수정자도 "이 `updateDate`를 만든 사람"으로 같은 의미를 갖게 한다. 그러면 **NULL은 오직 "V5 이전에 만들어져 이후 손대지 않은 글"**(수정자 모름)이라는 한 가지 뜻만 남는다. (a)는 "만든 뒤 안 고침"과 "기록 이전"이 같은 NULL이 되어 구분이 안 된다.

**D0-3. 기록 지점 — `updateDate`가 바뀌는 모든 곳에서 함께 바뀐다**
- 불변식: **`updateDate`와 `updaterId`는 항상 같은 쓰기에서 함께 바뀐다.** 엔티티 메서드가 둘을 같이 받는다:
  - `update(title, content, useYn, updaterId, now)` — 기존 시그니처에 `updaterId` 추가
  - `softDelete(updaterId, now)` — 게시글 삭제도 포함(계획 윤곽에 없던 확장 — 이유: 불변식을 예외 없이 유지하면 "updateDate는 바뀌었는데 수정자는 옛값"인 행이 생기지 않는다. 삭제된 글은 화면에 안 나오지만 DB 조사 때 마지막으로 손댄 사람이 맞게 남는다. 비용은 인자 하나)
  - `touch(updaterId, now)` — 신규. 첨부 업로드·삭제가 본문 필드 없이 수정 시각·수정자만 바꿀 때 쓴다.
- 현재 사용자는 `PostService.requireCurrentAdminUserId()`와 같은 규칙(null → 403)으로 얻고, **행 잠금 전에** 읽는다(403이 잠금을 쥔 채 나가지 않게 — 생성·수정의 검증 순서와 같다).
- `PostAttachmentService`에 `AdminSecurityService`를 주입하고 같은 5줄 헬퍼를 둔다. 공용 헬퍼로 추출(`AdminSecurityService.requireCurrentAdminUserId`)은 하지 않는다 — 다른 소비자가 많은 공용 클래스(`config/auth`) 변경이라 범위가 커진다(무관한 리팩터링 금지).

**D0-4. 첨부 업로드·삭제**(사용자 결정 Q6 — 수정자·수정 시각 모두 갱신)
- `findByIdAndBoardIdAndDeletedFalseForUpdate`의 반환 `Post`를 받아 **성공 경로의 끝**에서 `post.touch(updaterId, now)`. 실패(400·409·404·저장 예외)는 예외로 트랜잭션이 롤백되므로 갱신이 남지 않는다 — 시험으로 고정한다.
- 같은 게시글 행 잠금 안이라 동시 수정과 직렬화된다(새 잠금 없음).
- 시각 `now`는 메서드당 1회 산출해 첨부 `createDate`와 게시글 `updateDate`가 같은 값이 되게 한다(`ClockUsageConventionTest` 규약).
- **감사 로그는 그대로**: `POST_ATTACHMENT_UPLOAD`·`POST_ATTACHMENT_DELETE`(`targetType=POST_ATTACHMENT`). 게시글 `updateDate`가 바뀌어도 `POST_UPDATE`를 추가로 남기지 않는다(행위는 첨부 조작 하나).

**D0-5. 노출 범위**
- 관리 API: `PostResponse`·`PostSummaryResponse`에 `updaterId`(`@Schema(description = "마지막으로 생성·수정·첨부 변경을 한 관리자 userId. V5 이전 글은 null", nullable = true)`).
- 공개: 넣지 않는다. `PublicPostDetail`·`PublicPostSummary`는 명시 매핑이라 코드 변경이 없고, 시험으로 공개 응답에 값이 없음을 고정한다.
- 관리 화면 `posts.html`: 목록에 "최종 수정자" 열(작성자 옆, 9열 — `colspan` 3곳 8→9), 상세에 "최종 수정자" 항목(수정일 옆). 값은 `escapeHtml`(목록 `innerHTML`)·`textContent`(상세)로만 넣고 null이면 `-`.
- **수용하는 화면 한계**: 첨부 업로드·삭제 직후 모달의 "수정일"·"최종 수정자"와 목록 행은 갱신되지 않는다(첨부 목록만 다시 읽는 기존 흐름 유지). 모달을 다시 열거나 목록을 새로 읽으면 맞는 값이 보인다. 갱신하려면 상세 재조회를 끼워 넣어야 하는데, 이 화면의 세대 토큰·`AbortController` 보호와 얽혀 PR 범위를 넘는다.

**D0-6. 롤백**
- 롤백한 이전 앱은 `updater_id`를 갱신하지 않는다 → 재배포 후 **롤백 기간에 손댄 글은 그 전 수정자가 표시**될 수 있다. 원본은 감사 로그(`POST_UPDATE`·`POST_ATTACHMENT_*` 등)라 수용하고 `docs/deployment.md`에 "게시글 최종 수정자" 절로 적는다.
- **복구 절차**(P0-v2, 리뷰 P0-R1-1 — 이전 앱의 첨부 조작은 `update_date`를 바꾸지 않아 수정 시각만으로는 대상이 잡히지 않는다):
  - ① 재배포 전, 롤백 기간에 **수정**(`post.update_date`)되었거나 **첨부가 업로드**(`post_attachment.create_date`)된 글을 "모름"으로 돌린다:
    `UPDATE post SET updater_id = NULL WHERE update_date >= '<롤백 시각>' OR id IN (SELECT post_id FROM post_attachment WHERE create_date >= '<롤백 시각>');`
  - ② **롤백 기간의 첨부 삭제는 식별할 수 없다** — 첨부 행이 사라지고 감사 로그의 `target_id`는 첨부 id라 게시글로 이어지지 않는다. 그래서 ①은 **부분 복구**다. 정확성이 필요하면 보수적으로 `UPDATE post SET updater_id = NULL;`(전체를 "모름"으로)을 쓴다 — 이후 수정부터 다시 쌓인다.
  - 롤백 시나리오는 이전 앱 없이는 재현되지 않아 시험이 아니라 문서로 다룬다.

### P0-3. 변경 파일

| 층 | 파일 | 변경 |
|---|---|---|
| 스키마 | `db/migration/V5__add_post_updater_id.sql` | 신규 |
| 도메인 | `admin/board/domain/Post.java` | `updaterId` 필드, `update`·`softDelete` 인자 추가, `touch` 신규 |
| 서비스 | `admin/board/service/PostService.java` | 생성 시 `updaterId(authorId)`, 수정·삭제가 현재 사용자 전달 |
| 서비스 | `admin/board/service/PostAttachmentService.java` | `AdminSecurityService` 주입, 업로드·삭제 성공 끝에 `touch` |
| DTO | `admin/board/dto/response/PostResponse.java`·`PostSummaryResponse.java` | `updaterId` |
| 화면 | `templates/admin/board/posts.html` | 목록 열·상세 항목·`colspan` |
| 시험 | `PostServiceTest`·`PostAttachmentServiceTest`·`PostApiIntegrationTest`·`BaselineSchemaTest`(+ 공개 응답 비노출 단언) | 새 동작 |
| 시험(기존, 인증 전제 영향 — P0-v2) | `PostAttachmentTransactionIntegrationTest`(:118·:133 서비스 직접 호출)·`PostConcurrencyIntegrationTest`(:206 별도 스레드의 `updatePost`)·`PublicNoticeAttachmentIntegrationTest`(`@WithMockUser` — 주체가 `CustomUserDetails`가 아니라 현재 사용자 null)·`AdminPermissionMatrixIntegrationTest.fixture`(:177 컨텍스트 없는 `upload`) | 서비스를 부르는 지점(동시성 시험은 각 스레드)에 실제 관리자 주체(`CustomUserDetails`)를 설정·정리. 첨부 롤백 시험에 `updater_id`·`update_date`가 원래 값으로 남는지 단언 추가 |
| 문서 | `com/cms/admin/board/CLAUDE.md`·`docs/migration-guide.md`(V5 행)·`docs/deployment.md`(롤백 주의) | |

인가 정책·`SecurityConfig`·공개 경로·레이트리밋 변경 없음.

### P0-4. 시험 계획

- 단위(`PostServiceTest`): 생성 시 `updaterId = authorId`, 수정 시 현재 사용자로 바뀜(작성자와 다른 사용자), 수정 중 현재 사용자 null → 403이고 잠금 조회 전(잠금 쿼리 호출 없음), 삭제 시 기록.
- 단위(`PostAttachmentServiceTest`): 업로드·삭제 성공 시 `touch`(수정자·시각 = 첨부 `createDate`와 같은 시각), 현재 사용자 null → 403 + 잠금 전, 실패 경로(빈 파일·확장자·5개 초과·첨부 불허)는 엔티티 미변경.
- 통합(`PostApiIntegrationTest`, 실 DB): 관리자 A가 만든 글을 관리자 B가 수정 → `updater_id = B`·작성자는 A 그대로, B가 첨부 업로드 → 행의 `updater_id`·`update_date` 갱신, 실패한 업로드(400) → 변경 없음, 첨부 삭제 → 갱신, 관리 목록·상세 응답에 `updaterId`, 공개 상세·목록 응답 본문에 수정자 userId 문자열이 없음.
- 스키마(`BaselineSchemaTest`): `post.updater_id`가 `varchar(100)|YES|NULL`.
- 원자성(`PostAttachmentTransactionIntegrationTest`): 첨부 저장 후 롤백되는 기존 시나리오에서 게시글 `updater_id`·`update_date`가 원래 값 그대로(P0-v2).
- 기존 시험 회귀: 위 표의 인증 전제 영향 4개 클래스가 주체 설정 후 원래 의도한 단언까지 통과.
- 화면: playwright로 목록 열·상세 항목 표시(V5 이전 행의 `-` 포함) 확인.

### PR 0 개정 이력

- P0-v1: 초안.
- P0-v2 변경(리뷰 1라운드): P0-R1-1 롤백 복구 SQL을 수정 + 첨부 업로드로 확장, 첨부 삭제는 식별 불가라 부분 복구 명시 + 전체 초기화 대안(D0-6). P0-R1-2 인증 전제 영향 기존 시험 4개 클래스를 범위에 포함(리뷰가 지적한 2개 + 정찰로 추가 발견한 `PublicNoticeAttachmentIntegrationTest`·`AdminPermissionMatrixIntegrationTest`), 첨부 롤백 원자성 단언 추가(P0-3·P0-4).
- 리뷰 2라운드: ship(지적 없음). 사용자 승인 2026-10-26, 구현·검증 2026-10-27(아래 "구현·검증 결과 — PR 0").

### 구현·검증 결과 — PR 0 (2026-10-27)

**Context**: 고정 표시명(PR 1)을 쓰면 공개 화면에서 실제 작성자가 보이지 않는다. 추적 수단 중 "마지막으로 고친 사람"은 감사 로그를 게시글 ID로 찾아야만 알 수 있어, 게시글에 직접 남긴다.

**핵심 확정 사항**: P0-v2 설계 그대로 구현했다 — 생성 시 작성자로 채움(D0-2), `updateDate`와 `updaterId`를 항상 함께 갱신(수정·삭제·첨부 업로드·첨부 삭제, D0-3·D0-4), 현재 사용자는 잠금 전에 읽고 null이면 403, 관리 API·관리 화면에만 노출(D0-5), 롤백은 문서로 수용(D0-6). 계획과 달라진 구현 결정은 없다.

**구현 파일**

| 파일 | 변경 |
|---|---|
| `db/migration/V5__add_post_updater_id.sql` | 신규 — `ADD COLUMN IF NOT EXISTS updater_id varchar(100) DEFAULT NULL` |
| `admin/board/domain/Post.java` | `updaterId` 필드, `update(…, updaterId, now)`·`softDelete(updaterId, now)`, `touch(updaterId, now)` 신규 |
| `admin/board/service/PostService.java` | 생성 `.updaterId(authorId)`, 수정·삭제가 잠금 전 현재 사용자를 읽어 전달 |
| `admin/board/service/PostAttachmentService.java` | `AdminSecurityService` 주입, 업로드·삭제가 잠근 `Post`에 성공 끝 `touch`(업로드는 첨부 `createDate`와 같은 `now`) |
| `admin/board/dto/response/PostResponse.java`·`PostSummaryResponse.java` | `updaterId`(`@Schema nullable`) |
| `templates/admin/board/posts.html` | 목록 "최종 수정자" 열(`escapeHtml`, `colspan` 8→9 세 곳), 상세 "최종 수정자"(`textContent`) |
| 시험 | `PostServiceTest`(+2·단언 추가)·`PostAttachmentServiceTest`(+3·단언 추가)·`PostApiIntegrationTest.updaterIdTracksLastWriter`·`BaselineSchemaTest.postUpdaterIdColumn`·`PostAttachmentTransactionIntegrationTest`(주체 + 롤백 원자성 단언)·`PostConcurrencyIntegrationTest`(스레드 B 주체)·`PublicNoticeAttachmentIntegrationTest`(`@WithMockUser` → `CustomUserDetails` 주체)·`AdminPermissionMatrixIntegrationTest.fixture`(업로드 동안 주체)·`ContentCountIntegrationTest`(시그니처) |
| 문서 | `com/cms/admin/board/CLAUDE.md`(최종 수정자 절)·`docs/migration-guide.md`(V5 행)·`docs/deployment.md`("게시글 최종 수정자" — 롤백 주의·부분 복구 SQL) |

**검증 결과**

- 영향 범위 시험(`com.cms.admin.board.*`·`com.cms.publicweb.board.*`·`com.cms.publicweb.notice.*`·`AdminPermissionMatrixIntegrationTest`·`BaselineSchemaTest`·`*ConventionTest`): **439건 통과, 실패 0**. 새 시험 7건이 실행됐음을 결과 XML로 확인. 전체 `./gradlew test`는 PR 직전 1회(code-review-loop 마무리)로 남긴다.
- 변이 실험: `PostAttachmentService.upload`의 `post.touch(...)`를 지우면 `PostApiIntegrationTest.updaterIdTracksLastWriter`와 `PostAttachmentServiceTest.upload_success_usesLock`이 실패함을 확인 후 복원.
- 실기(dev, `bootRun`, Playwright): 기존 dev DB(V4)에 V5가 적용됨(`Successfully applied 1 migration … v5`). ① V5 이전 행(SQL로 넣은 `updater_id` NULL)이 목록에 `-`로 표시 ② API로 만든 글은 `authorId = updaterId = admin` ③ 상세 모달에 "최종 수정자" 표시(스크린샷 `board-definition-pr0-screenshots/01-detail-modal-updater.png`) ④ 수정자 값 `<img src=x onerror=alert(1)>`가 목록에서 `&lt;img…&gt;`로 이스케이프(이미지 0개) ⑤ 모달 UI로 첨부 업로드 → 게시글 `update_date` = 첨부 `create_date` ⑥ 첨부 삭제·게시글 삭제가 수정자·시각 갱신 ⑦ 익명 공개 `/main/notices`·`/main/notices/{1,2}`·`/main/boards/1/posts/1` 200이고 수정자 값 미노출 ⑧ 익명 관리 API GET·PATCH 401 ⑨ 검증 행 삭제로 dev DB를 게시글 0건으로 원복.
- **롤백 호환 실측**: V5가 적용된 DB에서 V4까지만 아는 이전 앱 이미지(`cms-app-dev` 컨테이너)가 `Schema cms has a version (5) that is newer than the latest available migration (4)` 경고만 남기고 기동, health 200.

**이슈**

- 첫 시험 실행에서 DB 의존 시험이 전부 `initializationError`(Docker Desktop 미기동 — 환경 문제). Docker 기동 후 재실행으로 해결, 코드 원인 없음.
- 실기 검증 중 8080을 Docker가 자동 재시작한 `cms-app-dev`(이전 이미지)가 점유 — 멈추고 `bootRun`, 검증 후 다시 시작했다(그 덕분에 롤백 호환을 실측).

**후속**

- 없음(PR 1 착수 시 `author_label` 등 표시 속성 — 이 문서 §4).

---

## PR 1 상세 설계 확인 — 표시·식별 + `version` (P1-v1, 2026-10-27)

§4가 상세 설계 원본이다. 이 절은 착수 정찰로 확인한 코드 사실과, §4에서 **달라지거나 구체화한 결정**만 적는다. 개정 이력은 이 절 끝 "PR 1 개정 이력".

### P1-1. 정찰 결과 (코드 근거)

- `Board`(`admin/board/domain/Board.java`): `update(name, publicYn, attachmentYn, now)`는 "null = 유지". `changeSite`·`softDelete`의 호출자는 `BoardService` 한 곳씩뿐이다(`changeSite`는 같은 요청의 `update` 뒤에만 불린다). 엔티티는 `@Builder`이고 `Board.builder()` 호출이 main·test에 18곳, 시험의 직접 SQL `INSERT INTO board`가 약 10개 클래스에 있다.
- `BoardService.updateBoard`: 입력 검사 → 게시판 `FOR UPDATE` → 시스템 게시판 규칙 → (사이트 변경 시) 사이트 `FOR SHARE` → 반영. `deleteBoard`: `FOR UPDATE` → 공통 공지 409 → `existsByBoardIdAndDeletedFalse`(잠금 없는 읽기) → 소프트 삭제 → 권한 행 삭제.
- `version` 선례: `site_page`·`banner`·`popup` 모두 `int(11) NOT NULL DEFAULT 1`, 요청은 `@NotNull @Min(1)`, 삭제는 `@RequestParam(required = false) Integer version` + 서비스가 `null || < 1`이면 400(전역 핸들러에 필수 파라미터 누락 처리가 없다 — `com.cms.admin.session` CLAUDE.md), 비교는 잠근 뒤 `ConflictException`.
- 게시글 쓰기의 게시판 확인: `PostService.createPost`는 게시판 `FOR SHARE`. `lockPost`(수정·삭제)는 게시글 `FOR UPDATE` → 게시판 **잠금 없는** 미삭제 확인. `PostAttachmentService.upload`·`delete`도 게시글 `FOR UPDATE` → 게시판 잠금 없는 읽기(업로드는 그 값으로 `attachmentYn` 판정). `ContentImageService.uploadForBoard`는 권한 재판정 → 게시판 잠금 없는 읽기 → 저장(등록기가 카운터 행 잠금).
- 공개: `PublicBoardService.PAGE_SIZE = 10`, `MAX_PAGE = 1000`(→ OFFSET 상한 10,000), 범위 밖 page는 0으로 보정. `PublicNoticeService`는 같은 서비스·같은 DTO에 위임한다 — 차이는 템플릿(`public/notice/*`)과 컨트롤러의 모델 속성 이름뿐. 상세 조회수는 `findPublishedPost` 뒤에 컨트롤러가 `recordView`를 부르므로 화면 값은 이번 조회를 포함하지 않는다. `Post.viewCount`(long)가 이미 있다.
- 화면: `admin/board/manage.html`은 PATCH에 이름·공개·첨부를 항상 보내고 DELETE는 본문이 없다. `admin/board/posts.html`의 버튼은 `MyBoardResponse.actions`와 `attachmentYn`으로만 정한다(`btnNewPost` CREATE, `btnEditMode` UPDATE, 업로드 UPDATE∧`attachmentYn`, 첨부 삭제 UPDATE).
- 시험 영향(게시판 PATCH·DELETE 호출 — `version` 필요): `BoardPermissionIntegrationTest`·`PostApiIntegrationTest`·`BoardSiteScopeApiIntegrationTest`·`BoardSiteScopeConcurrencyIntegrationTest`(서비스 직접)·`AbstractBoardDeleteRaceTest`. 화면 시험 `BoardModalBrowserTest`·`BoardScopeAdminBrowserTest`는 JS가 `version`을 보내면 그대로 통과해야 한다.

### P1-2. §4와 달라지거나 구체화한 결정

1. **마이그레이션 번호 V6**: `V6__board_display_settings.sql`. V5와 같이 `ADD COLUMN IF NOT EXISTS`. 기존 테이블에 `NOT NULL` 컬럼을 더하므로 `docs/migration-guide.md` 시험 정책상 **업그레이드 경로 시험 대상**이다(P1-v2, 리뷰 P1-R1-3) — `BoardDisplaySettingsUpgradeTest`(`ProfileImageUrlShrinkUpgradeTest` 형식: 별도 스키마에 V5까지 적용 → 게시판 행 삽입 → V6 적용 → 기존 행의 7개 컬럼이 기본값). `BaselineSchemaTest`에도 컬럼 단언을 더한다.
2. **`version` DEFAULT 1**(§4 초안 0에서 변경): 선례 세 곳이 모두 1에서 시작하고 "`< 1`이면 400" 규칙을 그대로 쓴다. 기존 행은 1이 된다.
3. **`version` 증가 지점**: 성공한 PATCH마다 정확히 +1(값이 실제로 바뀌었는지와 무관 — 배너·팝업과 같다), 소프트 삭제도 +1. 구현은 `Board.update(...)`가 `Math.incrementExact`로 올리고, `changeSite`는 같은 요청의 `update` 뒤에만 불리므로 버전을 건드리지 않는다(Javadoc에 명시). 이렇게 하면 한 요청이 +2 되지 않는다.
4. **새 글 배지 기준**(사용자 결정 2026-10-27): 기준 시각 = `publishStart`가 있고 `createDate`보다 늦으면 `publishStart`, 아니면 `createDate`("공개 시각"). `newBadgeDays = N > 0`일 때 `기준 ≥ now − N×24시간`이면 배지(경계 **포함**). 공개 목록에 나온 글은 공개 조건상 `publishStart ≤ now`라 기준이 미래일 수 없다. 판정은 서비스가 요청당 1회 산출한 `now`(KST `Clock`)로 하고 DTO에 `isNew` 불리언만 담는다.
   - **NULL 방어**(P1-v3, 리뷰 P1-R2-1 — `post.create_date`는 스키마상 NULL 허용이고 공개 조건이 거르지 않는다): `N = 0`이면 날짜를 보지 않고 `false`. 기준은 `createDate`·`publishStart` 중 **NULL이 아닌 값들의 최댓값**이고, 둘 다 NULL이면 `false`. 비교는 순수 함수 하나(`NewBadge.isNew(createDate, publishStart, days, now)`)에 모아 NPE로 목록 전체가 500이 되지 않게 한다.
5. **목록 크기와 OFFSET 상한**: `MAX_PAGE = 1000`을 그대로 두면 크기 50에서 OFFSET 상한이 50,000으로 커진다. 상한을 **OFFSET 10,000(포함)으로 고정**하고(`MAX_OFFSET` — 지금 크기 10·page 1000이 OFFSET 10,000이라 포함이 현행과 같다) 허용 페이지는 `0 ≤ page ≤ MAX_OFFSET / listPageSize`(정수 나눗셈 — 10→1000, 20→500, 30→333, 50→200)로 계산한다. 넘으면 지금처럼 0으로 보정한다(P1-v2, 리뷰 P1-R1-1 경계 통일). 이유: §4가 허용 목록으로 묶은 이유(COUNT·스캔 비용 계약)를 OFFSET에도 그대로 지키기 위함이다.
   - **알려진 한계(기존 동작, 이 PR에서 고치지 않음)**: 글이 상한보다 많으면 마지막 허용 페이지의 `hasNext`가 참이라 '다음' 링크가 0페이지로 보정된 목록을 연다. 지금도 크기 10·page 1000에서 같고(글 10,010건 이상), 고치려면 두 공개 컨트롤러의 모델 계약(`totalPages`·`hasNext`)을 바꿔야 해 범위 밖이다 — 후속 후보.
6. **엔티티 기본값**: 새 필드는 `@Builder.Default`(`listPageSize = 10`, `showViewCount = false`, `newBadgeDays = 0`, `writableYn = true`, `version = 1`, 문자열은 null). 빌더 호출 18곳과 직접 SQL INSERT(DB DEFAULT)가 손대지 않아도 현행 동작이 된다.
7. **PATCH의 문자열 3상태**(§4 D6 구현 방식): 서비스가 최종값을 계산한다 — 키 누락·null → 현재 값, 공백만 → null, 그 밖 → `strip()` 후 길이 검사. **새 문자열 두 필드(`description`·`authorLabel`)에는 DTO `@Size`를 두지 않는다**(P1-v2, 리뷰 P1-R1-2 — `@Valid`가 서비스보다 먼저 정리 전 길이로 400을 내 "앞뒤 공백 포함 502자 → 정리 후 500자"나 "긴 공백만 = 삭제" 요청이 서비스에 닿지 못한다). 길이 상한은 서비스의 정리 후 검사 한 곳이다. 요청 본문 전체 크기는 기존 서버 한도가 막는다. 기존 `name`의 `@Size(max = 100)`은 그대로 둔다(무관한 변경 금지). 엔티티 `update`는 표시 설정을 **최종값으로 전부 대입**하는 별도 인자 묶음(`BoardDisplay` record)을 받는다 — 기존 "null = 유지" 인자(이름·공개·첨부)와 섞지 않아 "지움"과 "유지"가 엔티티에서 헷갈리지 않는다.
8. **쓰기 중지 판정 위치와 잠금**(§4 D5 구현):
   - 새 리포지토리 메서드 없이 기존 `findByIdAndDeletedFalseForShare`를 쓴다. `PostService`·`PostAttachmentService`·`ContentImageService`가 각각 "공유 잠금 → 없으면 404 → `writableYn=false`면 400 `쓰기가 중지된 게시판입니다.`"를 한다(메시지 상수는 `Board.WRITE_STOPPED_MESSAGE` 하나를 공유 — 세 서비스가 다른 문구를 갖지 않게).
   - 수정: `lockPost`를 둘로 나눈다 — 수정용은 게시글 `FOR UPDATE` → 게시판 `FOR SHARE` + 판정, 삭제용은 지금 그대로(잠금 없는 확인, 판정 없음).
   - 첨부 업로드: 게시글 `FOR UPDATE` → 게시판 `FOR SHARE` → 쓰기 중지 400 → `attachmentYn` 400(같은 게시판 행 값으로 둘 다 판정). 첨부 삭제는 그대로.
   - 본문 이미지 업로드: 권한 재판정(403) → 게시판 `FOR SHARE` → 쓰기 중지 400 → 저장. 판정 순서는 403이 400보다 먼저(권한 없는 사용자에게 게시판 상태를 알려 주지 않는다).
   - 생성: 기존 `FOR SHARE` 뒤에 판정만 추가.
   - **교착 검토**: 새로 생기는 대기는 "게시글 X → 게시판 S" 하나다. 게시판 행을 X로 잡는 쓰기(PATCH·삭제)는 게시글 행을 잠그지 않는다(삭제의 `existsBy…`는 일관 읽기). InnoDB는 대기 중인 X 뒤에 새 S를 줄 세우므로 "생성(게시판 S 보유) → PATCH(X 대기) → 수정(게시글 X 보유, 게시판 S 대기)"는 생성 커밋 → PATCH → 수정 순으로 풀린다. 생성이 수정이 가진 게시글 행을 기다리는 경우는 없고(새 행 INSERT), 수정은 게시판 S를 이미지 참조 교체(`replaceRefs`의 이미지 `FOR SHARE`)보다 **먼저** 잡으므로 이미지 잠금과의 순환도 없다.
9. **공통 공지 게시판은 쓰기 중지 불가**(§4 D5 그대로): `isCommonNotice()`만 막는다(사이트 전용 공지는 허용). 생성은 공통 공지를 만들 수 없으므로 수정만 해당.
10. **`publicYn`과의 독립**: 쓰기 중지는 공개 경로를 바꾸지 않는다 — 공개 쿼리·공개 경로 다섯 곳은 손대지 않는다(시험: 쓰기 중지 게시판의 공개 목록·상세 200).
11. **공개 표시**: 목록 행마다 작성자 표시명(게시판 값 — 같은 값 반복)·조회수(켜진 게시판만, 서비스가 DTO에서 거름)·새 글 배지, 목록 머리에 설명(`th:text` + `white-space: pre-line`). 상세는 메타 줄에 작성자 표시명·조회수(이번 조회 미포함 — 수용). 공지 템플릿 두 개에 같게. 메인(`/`)은 바꾸지 않는다.
12. **값 검증**: `listPageSize ∈ {10,20,30,50}`·`newBadgeDays ∈ 0..30`은 서비스 400(허용 목록은 `Board.ALLOWED_LIST_PAGE_SIZES`). 생성 요청의 새 필드는 모두 선택(누락 = DEFAULT). "변경할 필드 없음" 400은 새 필드까지 포함해 판정하고 `version`만 보낸 PATCH도 400.
13. **화면 409**: `manage.html`은 PATCH 본문·DELETE 쿼리에 편집 시작 시점의 `version`을 보낸다. 409이면 모달을 닫고(입력은 짧은 설정값이라 보존하지 않는다) 페이지 상단에 서버 메시지를 띄운 뒤 목록을 다시 읽는다 — 기존 404 처리와 같은 흐름. 게시글 화면(`posts.html`)은 `MyBoardResponse.writableYn=false`이면 새 글·수정·첨부 업로드 버튼을 숨기고 "쓰기가 중지된 게시판입니다(조회·첨부 삭제·게시글 삭제만 가능)" 안내를 보인다(서버 판정이 최종).

### P1-3. 변경 파일

| 층 | 파일 | 변경 |
|---|---|---|
| 스키마 | `db/migration/V6__board_display_settings.sql` | 신규(컬럼 7개) |
| 도메인 | `admin/board/domain/Board.java`·`BoardDisplay.java`(신규 record) | 필드·기본값·`update` 확장·`version`·상수 |
| 서비스 | `BoardService` | 검증·3상태·`version` 비교·공통 공지 쓰기 중지 금지 |
| 서비스 | `PostService`·`PostAttachmentService`·`contentimage/ContentImageService` | 쓰기 중지 판정 + 게시판 `FOR SHARE` |
| 서비스 | `MyBoardService`, `publicweb/board/PublicBoardService` | `writableYn`, 목록 크기·OFFSET 상한·표시 필드 |
| DTO | `BoardCreateRequest`·`BoardUpdateRequest`·`BoardResponse`·`MyBoardResponse`·`PublicPostSummary`·`PublicPostDetail`·`PublicBoardListResult` | 새 필드 |
| 컨트롤러 | `BoardController`(DELETE `version`), `PublicBoardController`·`PublicNoticeController`(모델 속성) | |
| 화면 | `admin/board/manage.html`·`posts.html`, `public/board/list·detail.html`·`public/notice/list·detail.html`, `css/public/board.css`·`notice.css` | |
| 시험 | 아래 P1-4 | |
| 문서 | `com/cms/admin/board/CLAUDE.md`·`docs/migration-guide.md`(V6 행) | |

인가 정책·`SecurityConfig`·공개 URL·레이트리밋 변경 없음.

### P1-4. 시험 계획

- 스키마(`BaselineSchemaTest`): 새 컬럼 7개의 타입·NULL·DEFAULT.
- 업그레이드 경로(`BoardDisplaySettingsUpgradeTest` 신규): V5까지 적용한 스키마에 게시판 2행(공개·비공개) 삽입 → V6 → 두 행 모두 `description NULL`·`list_page_size 10`·`author_label NULL`·`show_view_count 0`·`new_badge_days 0`·`writable_yn 1`·`version 1`, 기존 컬럼 값 보존.
- MVC(`BoardPermissionIntegrationTest` 계열): 앞뒤 공백 포함 502자 설명(정리 후 500자) → 200·500자 저장, 정리 후 501자 → 400, 공백만 600자 → 200·null(삭제).
- 서비스 단위(`BoardServiceTest` 신규, Mockito): 범위 경계(크기 9·10·50·51·15 / 배지 −1·0·30·31 / 설명 500·501자 정리 후), 3상태(누락 유지·`""` 삭제·공백만 삭제·정리 저장), `version` 누락·0 → 400(잠금 조회 전), 불일치 → 409, 성공 +1(사이트 변경 동반 시에도 +1), 삭제 `version` 누락 400·불일치 409, 공통 공지 쓰기 중지 400·사이트 공지 허용, `version`만 보낸 PATCH 400.
- 통합(실 DB): 같은 `version`으로 두 PATCH 동시 → 하나 200·하나 409. 쓰기 중지 게시판에서 생성·수정·첨부 업로드·본문 이미지 업로드 400, 첨부 삭제 → 게시글 삭제 연속 성공, 관리 조회·공개 목록·상세 200. 직렬화 래치 시험: 수정·첨부 업로드가 게시판 공유 잠금을 쥔 동안 쓰기 중지 PATCH가 기다리고, 그 뒤 들어온 수정은 400.
- 공개(`PublicBoardIntegrationTest`·공지): 기존 행 목록 10건(DEFAULT), 크기 20 반영, OFFSET 상한(크기 50에서 page 200 허용·201 → 0으로 보정, 크기 30에서 333 허용·334 → 0, 크기 10은 1000 허용 — 현행 유지), 조회수 꺼짐 = 응답에 조회수 없음·켜짐 = 표시, 배지 경계(정확히 N×24시간 전 = 배지, 1초 더 이전 = 없음, 예약 글은 `publishStart` 기준, `create_date` NULL 글은 `publishStart` 있음 = 그 기준·없음 = 배지 없음이고 목록 200 — 일반·공지 모두, 순수 함수는 단위 시험으로 경계 표 전체), 설명·표시명 `<script>` 이스케이프, 공지 경로 같은 결과. `PublicBoardTemplateConventionTest`의 `th:utext` 한 곳 규칙 유지.
- 기존 시험 수정: P1-1의 5개 클래스에 `version` 추가.
- 화면(playwright): 생성·수정(설명·표시명 지우기 포함)·409 안내, 쓰기 중지 게시판의 게시글 화면 버튼 숨김, 공개 목록 표시.

### PR 1 개정 이력

- P1-v1: 초안(§4 확인 + 구체화 13항, 새 글 배지 기준은 사용자 결정).
- P1-v2 변경(리뷰 1라운드): P1-R1-1 OFFSET 상한을 "10,000 포함"으로 통일하고 허용 페이지를 `page ≤ MAX_OFFSET / size`로 정의, 시험 기대(50→200 허용·201 보정, 30→333·334) 정정(P1-2 ⑤·P1-4). 기각: 마지막 허용 페이지의 `hasNext`가 0페이지로 이어지는 문제 — 크기 10·page 1000에서 이미 같은 기존 동작이고 게시판 설정과 무관하며, 고치려면 두 공개 컨트롤러의 모델 계약을 바꿔야 해 범위 밖(알려진 한계 + 후속 후보로 기록). P1-R1-2 새 문자열 필드의 DTO `@Size` 제거 — 길이 상한은 서비스의 정리 후 검사 한 곳, MVC 경계 시험 추가(P1-2 ⑦·P1-4). P1-R1-3 기존 테이블 `NOT NULL` 추가라 업그레이드 경로 시험 `BoardDisplaySettingsUpgradeTest` 추가(P1-2 ①·P1-4).
- P1-v3 변경(리뷰 2라운드): P1-R2-1 새 글 배지의 NULL 방어 — `N=0`이면 날짜 미사용, 기준은 NULL 아닌 `createDate`·`publishStart`의 최댓값, 둘 다 NULL이면 배지 없음. 순수 함수 하나로 모으고 NULL 행 공개 목록 시험 추가(P1-2 ④·P1-4).
- 리뷰 3라운드: ship(새 지적 없음). 사용자 승인 2026-10-27, 구현·검증 같은 날(아래 "구현·검증 결과 — PR 1").

### 구현·검증 결과 — PR 1 (2026-10-27)

**Context**: 게시판 정의가 이름·공개·첨부·표시 대상 4개뿐이라 공개 화면의 목록 크기·조회수·작성자·배지·설명이 전역 상수에 묶여 있었고, 게시판 수정에 동시 수정 보호가 없어 필드가 늘수록 낡은 화면의 덮어쓰기 위험이 컸다. 이 PR은 표시·식별 설정 6개와 게시판 `version`(낙관적 검사)을 넣고, `publicYn`과 의미가 겹치지 않는 쓰기 중지(보관)를 도입한다.

**핵심 확정 사항**: P1-v3 설계 그대로 구현했다 — 게시판별 고정 작성자 표시명(로그인 ID 비공개), 쓰기 중지는 새 글·수정·첨부 업로드·본문 이미지 업로드만 400이고 조회·첨부 삭제·게시글 삭제는 허용, 공통 공지 게시판은 중지 불가, 쓰기 쪽은 게시판 `FOR SHARE`로 전환과 직렬화(순서 게시글 → 게시판), `version`은 PATCH·DELETE 필수·409·성공마다 +1, 새 글 배지는 `NewBadge` 한 곳(공개 시각 기준·NULL 방어), 허용 페이지 `≤ 10000 / 목록 크기`. **계획과 달라진 구현 결정은 없다.** 구현 중 정한 세부: 새 글 배지 DTO 필드명은 템플릿의 `new` 예약어 충돌을 피해 `newBadge`, 목록 결과 record(`PublicBoardListResult`)에 설명·표시명을 직접 실었다(생성자 시그니처 변경 — 시험 2곳 수정).

**구현 파일**

| 파일 | 변경 |
|---|---|
| `db/migration/V6__board_display_settings.sql` | 신규 — `board` 컬럼 7개(`IF NOT EXISTS`, 전부 현행 동작 DEFAULT) |
| `admin/board/domain/Board.java`·`BoardDisplay.java` | 필드·`@Builder.Default`·`update(…, BoardDisplay, now)`·`display()`·version +1(`update`·`softDelete`)·상수(`ALLOWED_LIST_PAGE_SIZES`·`WRITE_STOPPED_MESSAGE` 등) |
| `admin/board/service/BoardService.java` | 범위 검증·`optionalText`(정리 후 길이)·3상태·`requireVersionParam`/`requireFreshVersion`·공통 공지 중지 금지·`deleteBoard(id, version)` |
| `admin/board/service/PostService.java`·`PostAttachmentService.java`, `admin/contentimage/service/ContentImageService.java` | 쓰기 중지 판정 + 게시판 `FOR SHARE`(`lockPostForWrite`·`requireWritable`) |
| `admin/board/service/MyBoardService.java`, `dto/**` | `writableYn`·새 필드·`version` |
| `admin/board/controller/BoardController.java` | DELETE `?version=` |
| `publicweb/board/service/PublicBoardService.java`·`NewBadge.java`(신규)·`dto/*`, `publicweb/board·notice/controller` | 목록 크기·`MAX_OFFSET`·조회수 거름·배지·설명·표시명 |
| `templates/admin/board/manage.html`·`posts.html`, `templates/public/board/*`·`public/notice/*`, `css/public/board.css`·`notice.css` | 화면 |
| 시험(신규) | `BoardServiceTest`·`BoardDisplaySettingsApiIntegrationTest`·`BoardDisplaySettingsConcurrencyIntegrationTest`·`BoardDisplaySettingsPublicIntegrationTest`·`NewBadgeTest`·`BoardDisplaySettingsUpgradeTest` + `BaselineSchemaTest`·`PublicBoardServiceTest` 추가 |
| 시험(기존 수정) | `version` 추가: `BoardPermissionIntegrationTest`·`PostApiIntegrationTest`·`BoardSiteScopeApiIntegrationTest`(헬퍼)·`BoardSiteScopeConcurrencyIntegrationTest`·`AbstractBoardDeleteRaceTest`. 스텁/생성자: `PostServiceTest`·`PostAttachmentServiceTest`(게시판 `FOR SHARE` 스텁)·`PublicNoticeControllerTest`·`PublicNoticeServiceTest` |
| 문서 | `com/cms/admin/board/CLAUDE.md`(새 절)·`publicweb/board`·`publicweb/notice` `CLAUDE.md`(고정 10건·`MAX_PAGE` 서술 정정)·`docs/migration-guide.md`(V6)·`docs/deployment.md`(롤백 주의) |

**검증 결과**

- 영향 범위 시험(`admin.board`·`admin.site`·`admin.permission`·`admin.contentimage`·`admin.media`·`publicweb`·`config` 전체 + `*ConventionTest`·`BaselineSchemaTest`·`*UpgradeTest`, 브라우저 제외): 1192건, 기존 시험 스텁 정리 뒤 **실패 0**. 브라우저 시험 `BoardModalBrowserTest`(6)·`BoardScopeAdminBrowserTest`(3) 통과. 새 시험이 실행됐음을 결과 XML로 확인(`BoardDisplaySettingsApiIntegrationTest` 10·`ConcurrencyIntegrationTest` 3·`PublicIntegrationTest` 8·`BoardServiceTest` 7·`UpgradeTest` 1). **전체 `./gradlew test`는 PR 직전 1회(code-review-loop 마무리)로 남긴다.**
- 변이 실험: `PostService.lockPostForWrite`의 게시판 `FOR SHARE`를 일반 읽기로 바꾸면 `postUpdateWaitsForStopTransition`(전환 중 수정이 대기 후 400)이 실패함을 확인 후 복원.
- 실기(dev, `bootRun`, Playwright): dev DB(V5)에 V6 적용(`Successfully applied 1 migration … v6`). ① UI로 게시판 생성 — 설명·표시명 앞뒤 공백 정리 저장, 목록 크기 20 반영(스크린샷 `board-definition-pr1-screenshots/01-create-modal.png`) ② 공개 목록에 설명(줄바꿈)·NEW 배지·작성자 표시명·조회수 표시(`02-public-list.png`) ③ 화면 A를 열어 둔 채 다른 관리자가 먼저 저장 → 화면 A의 저장이 409 안내 + 목록 재조회, 다른 관리자의 수정은 보존 ④ 설명을 `""`로 비우면 NULL 저장·표시명은 유지, version이 저장마다 정확히 +1 ⑤ 쓰기 중지 저장 → 게시글 화면에 안내(`03-posts-write-stopped.png`), "새 게시글"·수정·첨부 업로드 숨김, 삭제 유지, 일반 게시판으로 바꾸면 안내 사라짐 ⑥ API 직접 호출: 쓰기 중지 게시판의 글 생성·수정 400, version 누락 400·낡은 값 409·DELETE 누락 400, 공통 공지 중지 400 ⑦ 익명 PATCH·DELETE 401, 공개 목록·상세·공지 200(쓰기 중지는 공개 노출과 독립), 없는 게시판 404 ⑧ 검증 행 삭제로 dev DB 원복.
- **롤백 호환 실측**: V6가 적용된 DB에서 V5까지만 아는 이전 앱 이미지(`cms-app-dev`)가 정상 기동, health 200.

**이슈**

- Docker Desktop 미기동(PC 재시작)으로 DB 시험이 불가능했다 — 환경 문제. 기동 후 해결.
- 새 시험의 첫 실패 3건은 모두 시험 쪽 가정: ① 이 프로젝트는 null 필드를 JSON에서 생략한다(`absent()` 헬퍼) ② MariaDB는 `FOR SHARE`가 아니라 `LOCK IN SHARE MODE` ③ 같은 시험에서 목을 여러 번 검증해 호출이 누적(`clearInvocations`). 서비스 결함 아님.
- 기존 단위 시험 22건은 서비스가 게시판을 `FOR SHARE`로 읽도록 바뀐 데 따른 예상된 스텁 정리였다.

**후속**

- 마지막 허용 페이지의 '다음' 링크가 0페이지로 돌아가는 기존 한계(글이 10,000건 넘는 극단 경우) — 두 공개 컨트롤러의 모델 계약 변경이 필요해 범위 밖, 후속 후보.
- PR 2 이후: 계획 문서 §5. PR 2 착수 때 §7의 PR 2 미결정 목록을 상세 설계에서 정한다.

---

## PR 2 상세 설계 — 작성 정책 (P2-v1, 2026-10-28)

§5 "PR 2" 윤곽의 상세 설계다. 개정 이력은 이 절 끝 "PR 2 개정 이력".

### P2-1. 정찰 결과 (코드 근거)

- **첨부 검증**: `AttachmentFilePolicy`(공용, `common/attachment`)는 정적 상수 `MAX_FILE_SIZE`(10MB)·`MAX_COUNT_PER_OWNER`(5)·`ALLOWED_CONTENT_TYPES_BY_EXTENSION`(15개 확장자)와 정적 메서드뿐이다. **main 코드의 소비자는 `PostAttachmentService.upload` 하나**(공지는 게시글이 됐고 프로필·배너·팝업·미디어는 `ImageFileValidator`를 쓴다 — §8 "공용 코드" 리스크 확인 완료). `upload`의 판정 순서: 게시글 `FOR UPDATE` → 게시판 `FOR SHARE` → 쓰기 중지 400 → `attachmentYn` 400 → 빈 파일 400 → 파일명 → 확장자 400 → Content-Type 400 → 크기 400 → 개수 **409** → 저장. 멀티파트 상한은 `spring.servlet.multipart.max-file-size: 10MB`(`application.yml`)라 10MB 초과 파일은 서비스에 닿지 않는다.
- **본문 검증**: `ContentBodyPolicy.sanitize(raw, format)`는 게시글 전용(이미지 허용, 10,001자). 다른 소비자(`sanitizePage`·`sanitizePopup`·`sanitizeFaq`)는 이미 이미지를 불허한다. 게시글 이미지 참조는 `ContentImageService.replaceRefs(OWNER_POST, postId, SCOPE_BOARD, boardId, imageIds)` — 이미 `alreadyReferenced`(이 게시글이 지금 참조하는 이미지 ID, 투영 조회)를 계산해 "라이브러리 이미지는 이미 참조 중이면 권한 회수 뒤에도 유지"를 판정한다. 같은 메서드를 배너·팝업·미디어가 다른 owner로 부른다.
- **본문 이미지 업로드**: 진입점은 `ContentImageService.uploadForBoard` 하나 — 권한 재판정(403) → 게시판 `FOR SHARE` → 쓰기 중지 400 → 저장. 라이브러리 이미지는 업로드가 아니라 `replaceRefs`로만 들어온다.
- **예약 게시**: `PostPublishPeriodRequest.toPeriod()`가 잠금 전에 엄격 파싱한다. 생성은 객체 없음 = 기간 없음, 수정은 객체 없음 = 유지·객체 있음 = 통째 교체(`{}` = 비움).
- **잠금(PR 1에서 이미 정리됨)**: 생성 = 게시판 `FOR SHARE`, 수정 = 게시글 `FOR UPDATE` → 게시판 `FOR SHARE`(`lockPostForWrite`), 첨부 업로드 = 게시글 `FOR UPDATE` → 게시판 `FOR SHARE`, 본문 이미지 업로드 = 게시판 `FOR SHARE`. 게시판 PATCH = 게시판 `FOR UPDATE`. **정책을 읽어야 하는 모든 쓰기가 이미 게시판 공유 잠금 안에 있다.**
- **화면**: `posts.html`은 편집기(`NoticeEditor.create`)를 **페이지에 하나만** 만들고 게시판마다 `setUploadUrl`만 바꾼다. `allowImages`는 생성 시 고정 옵션이고, `false`면 이미지 서식(`formats`)까지 빠져 **기존 글을 불러올 때 `<img>`가 지워진다**(`setHtml`은 `clipboard.convert`를 거친다). 첨부 영역은 `MyBoardResponse.attachmentYn`·`writableYn`·`actions`로 표시를 정한다. `manage.html`(ADMIN 전용) 모달은 "기본"·"공개 화면 표시" 두 묶음이다.
- **시험 영향**: `MyBoardResponse`는 `@AllArgsConstructor`(생성자 호출은 `MyBoardService` 한 곳), `Board.update`의 호출자는 `BoardService` 한 곳(시험 직접 호출 없음). `AttachmentFilePolicyTest`가 전역 상수 값을 단언한다(바꾸지 않는다). 첨부 상한 시험은 `PostApiIntegrationTest`(5개 상한·`attachmentYn`)에 있다.

### P2-2. 설계 결정

**D2-1. 스키마** — `V7__board_write_policy.sql`(DDL 한 문장, V5·V6과 같이 `ADD COLUMN IF NOT EXISTS`)

```sql
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `attach_max_count` int(11) NOT NULL DEFAULT 5,
  ADD COLUMN IF NOT EXISTS `attach_max_size_mb` int(11) NOT NULL DEFAULT 10,
  ADD COLUMN IF NOT EXISTS `attach_extensions` varchar(200) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS `body_image_yn` bit(1) NOT NULL DEFAULT 1,
  ADD COLUMN IF NOT EXISTS `publish_period_yn` bit(1) NOT NULL DEFAULT 1;
```

- 모든 DEFAULT = 현행 동작(5개·10MB·전역 확장자 전체·이미지 허용·예약 허용). 롤백한 앱이 만든 게시판도 재배포 후 지금과 같다(쟁점 6). 기존 테이블에 `NOT NULL` 컬럼을 더하므로 **업그레이드 경로 시험 대상**(`BoardWritePolicyUpgradeTest`, PR 1의 `BoardDisplaySettingsUpgradeTest` 형식) + `BaselineSchemaTest` 단언.
- `attach_extensions`: **소문자·중복 제거·사전순·쉼표 구분** 정규형만 저장한다. NULL = 전역 화이트리스트 전체. 길이 200은 전역 15개를 모두 이어도 약 60자라 여유가 있다.
- 단위는 MB 정수(1..10) — 바이트로 저장하면 화면·메시지가 소수 MB가 되고 전역 상한과의 비교가 흐려진다. 판정은 `size > mb × 1024 × 1024`.

**D2-2. 전역 상한은 천장 — 검증 진입점은 `AttachmentFilePolicy` 하나**

- 게시판 값의 범위: `attach_max_count ∈ 1..MAX_COUNT_PER_OWNER(5)`, `attach_max_size_mb ∈ 1..10`(상수 `MAX_FILE_SIZE_MB = 10`을 두고 `MAX_FILE_SIZE`를 그것으로 정의 — 두 값이 갈라지지 않게), `attach_extensions ⊆ 전역 키`. 범위 밖은 게시판 저장 400. **0개(= 첨부 금지)는 받지 않는다** — 그 의미는 `attachmentYn=false`가 이미 가진다(두 표현이 생기면 어느 쪽이 원본인지 흐려진다).
- `AttachmentFilePolicy`에 추가(전역 판정을 먼저 하고 게시판 제한을 뒤에 하는 순서를 이 클래스가 강제):
  - `allowedExtensions()` — 전역 키의 정렬된 불변 목록(화면 체크박스·응답·검증 공용).
  - `normalizeExtensions(Collection<String>)` — 원소마다 `strip`·소문자(`Locale.ROOT`), 중복 제거 후 전역 부분집합인지 검사(아니면 400 — 메시지에 입력 원문을 싣지 않는다), null 원소·빈 집합 400, **전역 전체와 같으면 null**(= "전체", D2-3), 아니면 정렬 집합.
  - `requireAllowedExtension(filename, Set<String> boardExtensions)` — 기존 단일 인자판(전역)을 먼저 호출해 **전역 실패 메시지가 그대로** 나오고, 통과한 확장자가 게시판 집합(null이면 생략)에 없으면 400 "이 게시판에서 허용하지 않는 파일 형식입니다: .x". 기존 단일 인자판은 남긴다.
- **DB 값이 전역 밖이어도 천장이 지킨다**: 직접 SQL로 `attach_extensions='exe'`를 넣어도 전역 판정이 먼저라 `.exe`는 여전히 400이다. 저장 값 파싱(`Board.attachExtensionSet()`)은 쉼표로 자르고 빈 토큰을 버리기만 한다 — 재검증하지 않는 이유는 천장 판정이 업로드마다 먼저 돌기 때문이다(시험으로 고정). 게시판 크기·개수 값도 판정 시 `min(게시판 값, 전역 상한)`으로 쓴다(직접 SQL로 20을 넣어도 천장 유지).
- 업로드 판정 순서(기존 순서에 게시판 값만 끼운다): 쓰기 중지 400 → `attachmentYn` 400 → 빈 파일 → 파일명 → **확장자(전역 → 게시판) 400** → Content-Type 400 → **크기(게시판 MB) 400** → **개수(게시판 상한) 409**. 메시지는 게시판 값으로 만든다("첨부파일은 3MB 이하만…", "게시글당 첨부파일은 최대 2개까지…"). 상태 코드는 기존 그대로다.

**D2-3. 확장자 PATCH 의미** — 2상태 + 정규화

- 요청 필드 `attachExtensions: string[]`. **키 누락·`null` = 유지**, 배열 = 통째 교체. 빈 배열 = 400(첨부 금지는 `attachmentYn`). **전역 전체와 같은 집합 = NULL로 저장**("전체 허용").
- 선택지: (a) 전체 선택을 그대로 목록으로 저장 (b) **전체 = NULL로 정규화**. → (b). 이유: NULL과 "전체 목록"이 두 표현으로 공존하면 전역 화이트리스트에 확장자가 추가될 때 게시판마다 결과가 갈린다(목록 저장 게시판만 새 확장자를 못 받음). 기본값(NULL)과 "모두 체크"가 같은 의미여야 화면 체크박스가 정직하다. 수용하는 결과: 전역에 확장자가 늘면 NULL 게시판은 자동으로 받는다 — 기본 동작과 같다.
- 응답(`BoardResponse.attachExtensions`)은 **저장값 그대로**이고 전체 허용(NULL)이면 **키가 빠진다**(전역 `default-property-inclusion: non_null` — P2-v2, 리뷰 P2-R1-4). 계약은 "키 누락 = 전체". 화면은 `null`·`undefined`를 모두 전체로 읽고, 시험은 PR 1의 `absent()` 헬퍼로 키 부재를 단언한다. `MyBoardResponse.attachExtensions`는 **실효 목록**(NULL이면 전역 전체를 펼쳐서 — 항상 있음)이라 게시글 화면은 "전체"를 따로 해석할 필요 없이 `accept`·안내에 그대로 쓴다.
- **원소 수 상한**(P2-v2, 리뷰 P2-R1-3): 요청 DTO의 `attachExtensions`에 `@Size(max = 50)`(초과 400 — 전역 15개의 중복·대소문자 변형을 넉넉히 받는 값). 문자열 필드에 DTO `@Size`를 두지 않은 PR 1 규칙(정리 전 길이로 400을 내지 않게)과 달리 **원소 개수는 정리 전후가 같아** DTO에서 막아도 의미가 바뀌지 않는다. 원소 하나의 길이 상한은 두지 않는다 — JSON 요청 본문 전체 크기는 이름·설명 같은 기존 문자열 필드와 공통인 프로젝트 차원 사안이고(multipart 한도는 JSON에 적용되지 않는다), ADMIN 전용 API이며, 긴 원소는 전역 화이트리스트 대조에서 즉시 400이다.

**D2-4. 본문 이미지 허용(`body_image_yn`)을 끈 게시판** — 미결정 ① 해소: **(iii) 기존 이미지는 유지, 새 이미지만 거부**

- 선택지: (i) 저장 시 sanitizer가 이미지를 지움 — 관리자가 제목만 고쳐도 본문 이미지가 조용히 사라지는 데이터 손실 (ii) 이미지가 있으면 400 — 기존 글은 이미지를 손으로 지우기 전에는 오타 하나도 못 고친다 (iii) **이 글이 이미 참조 중인 이미지는 유지, 그 밖의 이미지 ID가 본문에 있으면 400**. → (iii)(윤곽의 추천).
- **"새 이미지"의 정의는 참조 테이블 기준이다**: 본문의 이미지 ID 중 **실제로 존재하는 이미지**(`findAllByIdForShare`가 돌려준 행)이면서 `content_image_ref`에서 이 게시글(owner `POST`)이 지금 참조하지 않는 것. 업로드한 이미지·라이브러리에서 고른 이미지·다른 글 본문에서 복사해 붙인 `<img src="/content-images/N">`이 모두 여기에 걸린다. 생성은 참조가 없으므로 **존재하는 이미지가 하나라도 있으면 400**.
- **존재하지 않는 ID는 허용 게시판과 똑같이 조용히 뺀다**(P2-v2, 리뷰 P2-R1-1): sanitizer는 형식이 맞는 `/content-images/{id}`를 남기고 `replaceRefs`는 존재하는 이미지만 참조로 저장하므로, 지금도 **본문에는 있지만 참조가 없는 깨진 ID**가 생길 수 있다. 이것을 "새 이미지"로 거부하면 그런 기존 글은 정책을 끈 뒤 오타 하나도 못 고친다((ii)의 문제가 다시 생김). 존재하지 않는 ID는 아무것도 보여 주지 않으므로(공개 404) 막을 이유가 없다.
- 구현 위치: `replaceRefs`에 오버로드 `replaceRefs(ownerType, ownerId, scopeType, scopeId, imageIds, boolean allowNewImages)`를 더하고 기존 5인자판은 `true`로 위임한다(배너·팝업·미디어 호출부 무변경). `allowNewImages=false`면 이미지 행을 잠가 읽는 루프(기존 출처·라이브러리 판정과 같은 자리)에서 `alreadyReferenced`에 없는 행을 만나면 **삭제·쓰기 전에** 400 "본문 이미지를 허용하지 않는 게시판입니다. 새로 넣은 이미지를 지워 주세요." — 이미 계산하는 `alreadyReferenced`(투영 조회)를 재사용하고, 거부가 쓰기 전이라 기존 참조가 남는다(기존 출처 400과 같은 성질). 판정 순서는 이 새 이미지 검사를 출처·라이브러리 검사보다 **먼저** 둔다(불허 게시판에서는 어떤 새 이미지든 같은 문구).
  - 대안: `PostService`가 참조 ID를 따로 읽어 비교 — 같은 투영 조회가 두 번 돌고 "이미 참조 중" 규칙이 두 곳에 생긴다. 기각.
- 출력·공개 판정은 바꾸지 않는다: 기존 이미지는 공개 화면에 계속 보이고(`existsPublishedPostRef` 그대로), 이 설정은 **쓰기 정책**이지 표시 정책이 아니다.
- **업로드도 막는다**: `uploadForBoard`는 쓰기 중지 판정 뒤에 `body_image_yn=false`면 400(문구 상수 `Board.BODY_IMAGE_DISABLED_MESSAGE` "본문 이미지를 허용하지 않는 게시판입니다."). 막지 않으면 저장에 못 쓰는 미참조 이미지가 쌓여 카운터 용량만 먹는다. 판정 순서는 권한 403 → 404 → 쓰기 중지 400 → 이미지 불허 400.
- **본문 정리는 그대로**(`ContentBodyPolicy.sanitize` 무변경 — `allowImages=true`로 정리). 이미지를 불허하는 sanitizer로 바꾸면 그것이 곧 (i)이다.

**D2-5. 예약 게시 허용(`publish_period_yn`)을 끈 게시판** — 미결정 ② 해소: **기존 기간은 계속 유효, 새로 "설정"하는 입력만 400**

- 선택지: (a) **기존 예약·만료 글의 기간은 그대로 유효하고, 시작이나 종료가 있는 새 입력만 400** (b) 끄는 순간 공개 조건에서 기간을 무시 — 공개 경로 다섯 곳(쟁점 2)과 공지·본문 이미지 판정에 게시판 조건을 넣어야 하고, 끄는 순간 예약 글이 즉시 공개되거나 만료 글이 되살아난다(되돌릴 수 없는 노출). → (a)(윤곽의 추천).
- 규칙: 게시판이 끈 상태에서 `publishPeriod`의 **`start`나 `end` 중 하나라도 값이 있으면 400** "예약 게시를 허용하지 않는 게시판입니다.". **`{}`(비우기)는 허용**한다 — 기간을 없애는 것은 정책을 줄이는 방향이고, 끄기 전에 걸어 둔 예약을 풀 수단이 있어야 한다. 객체 없음(생성 = 기간 없음, 수정 = 유지)은 그대로다. 같은 값을 다시 보내는 PATCH도 400이다(화면은 바뀐 경우에만 보낸다 — 단순성을 위해 "현재 값과 같으면 허용"을 두지 않는다).
- 판정 위치: 파싱(`toPeriod`, 형식 400)은 지금처럼 잠금 전, 게시판 정책 판정은 게시판 `FOR SHARE` 뒤(값을 알아야 하므로). 생성 = 게시판 공유 잠금 직후, 수정 = `lockPostForWrite` 직후. 쓰기 중지 판정 뒤에 둔다(쓰기 중지가 더 넓은 거부).
- 공개 경로·`DisplayPeriod`·공개 쿼리는 **바꾸지 않는다**.

**D2-6. 정책 변경 ↔ 진행 중 쓰기의 경합** — 미결정 ③ 해소: **새 잠금 없음, PR 1의 게시판 `FOR SHARE`를 그대로 쓴다**

- 정책을 읽는 모든 쓰기(생성·수정·첨부 업로드·본문 이미지 업로드)는 PR 1 이후 이미 게시판 행 `FOR SHARE` 안에서 판정한다(P2-1). 정책 변경은 게시판 PATCH(`FOR UPDATE`)다. 따라서 "업로드가 옛 정책(10MB)으로 판정을 마친 뒤 정책 변경(3MB)이 커밋되고 업로드가 그 뒤 커밋" 같은 끼어들기는 생기지 않는다 — 진행 중 업로드가 끝날 때까지 PATCH가 기다리고, PATCH 뒤의 업로드는 새 값을 읽는다.
- 잠금 순서는 PR 1 P1-2 ⑧의 교착 검토 그대로다(게시글 X → 게시판 S, 게시판 쪽 쓰기는 게시글 행을 잠그지 않음). 새 대기 관계가 생기지 않는다.
- 시험: PR 1의 `BoardDisplaySettingsConcurrencyIntegrationTest`가 같은 잠금을 고정했고(변이 실험 확인됨), 이 PR은 잠금을 바꾸지 않는다. 다만 판정이 **잠금 조회가 돌려준 게시판의 값**을 쓰는지(잠금 없는 다른 조회 결과가 아니라)는 단위 시험으로 고정한다.

**D2-7. 기존 데이터 — 상한을 낮춰도 이미 넘은 글은 그대로**(윤곽 기본안 확정)

- 첨부 개수 판정은 업로드 시점에만 한다. 상한을 5→2로 낮춘 게시판의 3개짜리 글은 그대로 보이고 다운로드·삭제된다. 새 업로드만 409("최대 2개")이며 **2개 미만으로 지운 뒤에야** 다시 올릴 수 있다. 확장자·크기도 같다(기존 첨부는 재검증하지 않음). `attachmentYn=false`와 같은 규칙.

**D2-8. 엔티티·DTO·API**

- `Board`: 필드 5개(`@Builder.Default` — `attachMaxCount = 5`, `attachMaxSizeMb = 10`, `bodyImageYn = true`, `publishPeriodYn = true`, `attachExtensions = null`), 읽기 도우미 `attachExtensionSet()`(null = 전체), 문구 상수 `BODY_IMAGE_DISABLED_MESSAGE`·`PUBLISH_PERIOD_DISABLED_MESSAGE`. 쓰기는 PR 1의 `BoardDisplay`와 **분리한 record `BoardWritePolicy`**(`attachMaxCount, attachMaxSizeMb, attachExtensions(정규형 문자열|null), bodyImageYn, publishPeriodYn`)를 받아 `update(name, publicYn, attachmentYn, display, writePolicy, now)`로 전부 대입한다. `BoardDisplay`에 넣지 않는 이유: 이름 그대로 "공개 화면 표시" 묶음이고 화면 모달 묶음과도 맞춘다.
- `BoardService`: 생성은 모든 새 필드 선택(누락 = DEFAULT), 수정은 "누락·null = 유지". 범위 검증은 **잠금 전**(PR 1 규칙). "변경할 필드 없음" 400 판정에 새 필드 5개를 더한다.
- 요청: `BoardCreateRequest`·`BoardUpdateRequest`에 `attachMaxCount`·`attachMaxSizeMb`(Integer)·`attachExtensions`(List<String>)·`bodyImageYn`·`publishPeriodYn`(Boolean). DTO에 범위 어노테이션을 두지 않는다 — 범위·정규화는 서비스 한 곳(PR 1 P1-2 ⑦과 같은 이유). 목록 원소 수는 정규화 과정에서 전역 부분집합 검사로 사실상 15개 이하가 되지만, 수천 원소 배열이 정규화 루프에 들어오는 것은 요청 본문 크기 한도에 맡긴다.
- 응답: `BoardResponse`에 5개(확장자는 저장값 `List<String>|null`), `MyBoardResponse`에 5개(확장자는 실효 목록). `MyBoardService`가 실효 목록을 펼친다.
- API 경로·권한·감사(`BOARD_UPDATE`)는 바뀌지 않는다. `version` +1은 PR 1의 `update` 한 곳이 그대로 맡는다.

**D2-9. 화면**

- `manage.html`(ADMIN): 모달에 세 번째 묶음 **"작성 정책"** — 첨부 최대 개수(select 1..5)·파일당 최대 크기(select 1..10MB)·허용 확장자(체크박스, 목록은 서버 모델 `attachmentExtensions` = `AttachmentFilePolicy.allowedExtensions()`로 렌더해 JS 하드코딩 없음 — 모두 체크 = 전체), 본문 이미지 허용·예약 게시 허용 체크박스. 첨부 관련 입력은 `attachmentYn`이 꺼져 있어도 편집할 수 있게 둔다(첨부를 다시 켤 때의 값). 안내 문구: "상한을 낮춰도 이미 올린 첨부·이미지·예약 기간은 그대로이고 새 입력만 막습니다." 확장자를 하나도 고르지 않으면 클라이언트에서 막는다(서버도 400). 저장 중 잠금 목록(`setSaving`)에 새 입력을 더한다. 목록 열은 늘리지 않는다(정책은 모달에서 본다).
- `posts.html`: 첨부 영역에 안내 "최대 N개 · 파일당 N MB · 허용 형식: pdf, …"(`textContent`)와 파일 입력 `accept`(`.pdf,.docx…`) — 서버 판정이 최종. 본문 이미지를 끈 게시판이면 편집기 이미지·라이브러리 버튼을 숨기고 안내("이 게시판은 새 본문 이미지를 허용하지 않습니다. 기존 이미지는 유지됩니다."), 예약 게시를 끈 게시판이면 생성·수정 폼의 노출 시작·종료 입력을 숨기고 안내("이 게시판은 예약 게시를 허용하지 않습니다. 이미 설정된 기간은 그대로 적용됩니다."). 상세 보기의 상태·기간 표시는 그대로. 기간 입력을 숨기면 변경 감지가 기간을 보내지 않게 한다(숨긴 입력의 값으로 `publishPeriod`를 만들지 않는다).
  - **기간 해제 버튼**(P2-v2, 리뷰 P2-R1-2 — 게시판 정책은 ADMIN 전용이라 "잠시 켜고 비우기"는 게시판별 UPDATE만 가진 MANAGER의 수단이 못 되고, 켜 둔 동안 다른 작성자의 새 예약도 열린다): 예약을 끈 게시판에서 수정 중인 글에 기존 기간(시작·종료 중 하나라도)이 있으면 숨긴 입력 자리에 기존 기간 표시와 **"기간 해제"** 버튼을 보인다. 누르면 폼 상태에 "해제 예정"만 표시하고, 저장 시 `publishPeriod: {}`를 보낸다(다른 변경과 함께 한 번의 PATCH — 버튼이 따로 요청을 보내지 않아 저장 중 잠금·늦은 응답 처리 흐름을 그대로 쓴다). 취소하면 되돌린다. 서버는 D2-5대로 `{}`를 허용한다.
- `notice-editor.js`: 새 메서드 `setImageUploadEnabled(enabled)` — 툴바 `.ql-image`·`.ql-library-image` 버튼 숨김 + 파일 붙여넣기·끌어놓기·버튼 업로드를 `onError` 안내로 끊음. **이미지 서식(`formats`)과 붙여넣기 matcher는 그대로 둔다** — `allowImages=false`처럼 서식을 빼면 기존 글을 불러올 때 이미지가 지워져(P2-1) 저장 시 (i)이 된다. 붙여넣은 `/content-images/N` 이미지는 서버 400이 최종 판정이다. 편집기는 페이지에 하나라 게시판을 열 때마다 `setUploadUrl`과 함께 호출한다.

**D2-10. 롤백**

- 이전 앱(V6까지)은 새 컬럼을 모르므로 **게시판별 작성 정책을 무시**하고 전역 상한(5개·10MB·전체 확장자·이미지·예약 허용)으로 동작한다 — 롤백 기간에 정책보다 큰 첨부·새 이미지·예약 글이 들어올 수 있다. 재배포 후에도 그 데이터는 그대로 둔다(D2-7과 같은 규칙). `docs/deployment.md`에 "게시판 작성 정책" 롤백 주의로 적는다. 롤백 중 만든 게시판은 DEFAULT(현행 동작)다.

### P2-3. 변경 파일

| 층 | 파일 | 변경 |
|---|---|---|
| 스키마 | `db/migration/V7__board_write_policy.sql` | 신규(컬럼 5개) |
| 공용 | `common/attachment/AttachmentFilePolicy.java` | `MAX_FILE_SIZE_MB`·`allowedExtensions()`·`normalizeExtensions()`·`requireAllowedExtension(filename, set)` |
| 도메인 | `admin/board/domain/Board.java`·`BoardWritePolicy.java`(신규 record) | 필드·기본값·`update` 인자·`attachExtensionSet()`·문구 상수 |
| 서비스 | `BoardService` | 범위·정규화·PATCH 유지·변경 없음 판정 |
| 서비스 | `PostService` | 예약 정책 판정, `replaceRefs(…, allowNewImages)` |
| 서비스 | `PostAttachmentService` | 게시판 확장자·크기·개수 |
| 서비스 | `contentimage/ContentImageService` | `replaceRefs` 오버로드, `uploadForBoard` 이미지 불허 400 |
| 서비스·DTO | `MyBoardService`, `BoardCreateRequest`·`BoardUpdateRequest`·`BoardResponse`·`MyBoardResponse` | 새 필드 |
| 컨트롤러 | `BoardPageController` | 모델 `attachmentExtensions` |
| 화면 | `admin/board/manage.html`·`posts.html`, `static/js/admin/notice-editor.js` | D2-9 |
| 시험 | P2-4 | |
| 문서 | `com/cms/admin/board/CLAUDE.md`·`contentimage/CLAUDE.md`(오버로드 한 줄)·`docs/migration-guide.md`(V7)·`docs/deployment.md`(롤백) | |

인가 정책·`SecurityConfig`·공개 경로·공개 쿼리·레이트리밋·sanitizer 허용 목록 변경 없음. **공용 코드(`common/attachment`) 변경이라 PR 직전 전체 `./gradlew test` 대상**.

### P2-4. 시험 계획

- 스키마: `BaselineSchemaTest`(새 컬럼 5개 타입·NULL·DEFAULT), `BoardWritePolicyUpgradeTest` 신규(V6까지 적용 → 게시판 2행 → V7 → 기존 행 `5·10·NULL·1·1`, 기존 컬럼 보존).
- 단위 `AttachmentFilePolicyTest`: `normalizeExtensions` — 대소문자·공백·중복 정리, 전역 밖 400, null 원소·빈 집합 400, 전역 전체 = null, 순서 무관 정규형. `requireAllowedExtension(filename, set)` — 전역 밖(`exe`)은 게시판 집합에 넣어도 **전역 메시지 400**, 전역 안·게시판 밖 400(게시판 메시지), null 집합 = 전역만.
- 단위 `BoardServiceTest`: 개수 0·1·5·6, 크기 0·1·10·11, 확장자 빈 배열·전역 밖 400(**잠금 조회 전**), 전체 선택 → null 저장, 누락 PATCH 유지, 새 필드만 보낸 PATCH가 "변경 없음"이 아님.
- 단위 `PostServiceTest`·`PostAttachmentServiceTest`·`ContentImageServiceTest`: 정책 판정이 잠금 조회가 돌려준 게시판 값을 쓴다(D2-6), 예약 끈 게시판 — 생성 `start`만/`end`만 400·`{}`·객체 없음 통과, 수정 설정 400·`{}` 통과·객체 없음 통과. `replaceRefs` `allowNewImages=false` — 기존 참조만 = 통과, 존재하는 새 ID 하나 = 400이고 삭제·저장 호출 없음, 존재하지 않는 ID = 통과(참조에서 빠짐). 업로드 판정 순서(403 → 쓰기 중지 → 이미지 불허). DB에 전역 밖 값이 들어 있어도 천장(`exe`·크기 20·개수 9).
- 통합 `BoardWritePolicyApiIntegrationTest` 신규(실 DB, MockMvc):
  - 게시판 생성·수정 왕복(응답·DB), 확장자 `["PDF"," pdf ","hwp"]` → `hwp,pdf`, 일부 → 전체로 바꾸면 NULL·응답에 키 없음(`absent()`), `MyBoardResponse` 실효 목록, 원소 51개(같은 값 반복) 400·50개 200.
  - 첨부: 크기 1MB 게시판에 정확히 1MB 200·1MB+1바이트 400, 확장자 `pdf`만 게시판에 png 400·pdf 200·`exe` 전역 메시지 400, 상한 2 게시판에서 2개 뒤 409(메시지에 2), **상한을 5→2로 낮춘 뒤 3개짜리 글은 목록·다운로드·삭제 200, 새 업로드 409, 하나 지운 뒤에도 2개라 409, 하나 더 지우면 200**.
  - 본문 이미지: 끈 게시판 업로드 400, 생성에 이미지 400, **이미지 든 기존 글(허용일 때 작성 → 정책 끔)의 제목만 수정 200·본문 그대로 보낸 수정 200·참조 유지**, 미참조 이미지를 넣은 수정 400·기존 참조 유지, 이미지를 지운 수정 200·참조 감소, 공개 상세에서 기존 이미지 200, **존재하지 않는 이미지 ID가 본문에 든 기존 글(허용일 때 작성) → 정책 끔 → 같은 본문의 글자만 고친 수정 200**(P2-R1-1).
  - 예약: 끈 게시판 생성 시 기간 400·`{}` 200, 예약 글(허용 시 작성) → 정책 끔 → 제목 수정 200·기간 그대로·공개 목록에 시작 전 미노출(기간 유효), 기간 설정 PATCH 400, `{}` PATCH 200·즉시 공개.
  - 쓰기 중지와 겹침: 쓰기 중지 + 이미지 불허 게시판의 업로드는 쓰기 중지 메시지(순서).
- 기존 시험 회귀: `PostApiIntegrationTest`(전역 5개 상한·`attachmentYn`)는 DEFAULT로 그대로 통과해야 한다.
- 화면(playwright): 게시판 모달의 작성 정책 저장·재열기 값 유지(확장자 일부·전체), 게시글 화면의 첨부 안내·`accept`, 이미지 끈 게시판의 툴바 버튼 숨김 + 기존 이미지 글 열기 시 이미지 유지, 예약 끈 게시판의 기간 입력 숨김 + 기존 예약 글의 "기간 해제" → 저장 → 기간 없음(게시판 UPDATE만 가진 MANAGER로 — P2-R1-2).

### PR 2 개정 이력

- P2-v1: 초안(미결정 3건 — ① (iii) 새 이미지만 거부 ② 기존 기간 유효·설정만 400·비우기 허용 ③ PR 1 게시판 `FOR SHARE` 재사용, 새 잠금 없음).
- P2-v2 변경(리뷰 1라운드): P2-R1-1 "새 이미지"를 **존재하는 이미지 중 미참조**로 한정 — 존재하지 않는 ID는 허용 게시판처럼 조용히 빼서, 깨진 ID가 든 기존 글도 정책을 끈 뒤 고칠 수 있게(D2-4, 시험 추가). P2-R1-2 예약 끈 게시판의 기존 기간을 게시판별 UPDATE 권한만으로 풀 수 있게 "기간 해제" 버튼(저장 시 `{}`) 추가 — 게시판 정책은 ADMIN 전용이라 "잠시 켜기" 우회는 MANAGER에게 불가(D2-9). P2-R1-3 `attachExtensions` 원소 수 DTO `@Size(max = 50)`(개수는 정리 전후가 같아 DTO에 둬도 됨, D2-3). 기각: 원소 하나의 길이 상한 — JSON 본문 크기는 기존 문자열 필드와 공통인 프로젝트 차원 사안이고 ADMIN 전용·긴 원소는 화이트리스트 대조에서 즉시 400. P2-R1-4 전역 `non_null`이라 전체 허용은 응답에서 **키 누락**(계약 "누락 = 전체", 시험은 `absent()`)(D2-3).
- 리뷰 2라운드: ship(새 지적 없음). 사용자 승인 2026-10-28, 구현·검증 같은 날(아래 "구현·검증 결과 — PR 2").

### 구현·검증 결과 — PR 2 (2026-10-28)

**Context**: 첨부 개수·크기·확장자, 본문 이미지, 예약 게시가 전역 상수·무조건 허용에 묶여 있어 게시판마다 다른 작성 규칙을 둘 수 없었다. 이 PR은 게시판별 작성 정책 5개를 넣되, 전역 상한을 천장으로 두고 상한을 낮춰도 기존 데이터는 건드리지 않는다(새 입력만 막는다).

**핵심 확정 사항**: P2-v2 설계 그대로 구현했다 — 정책 컬럼 5개(V7, 전부 현행 동작 DEFAULT), 전역 검사 → 게시판 제한 순서를 `AttachmentFilePolicy`가 강제(DB를 직접 고쳐도 전역 상한 유지), 허용 확장자는 전체 선택 = NULL로 정규화(응답에서는 키 누락), 본문 이미지를 끈 게시판은 기존 참조 유지 + 존재하는 새 이미지만 400(깨진 ID는 조용히 뺌), 예약을 끈 게시판은 기존 기간 유효 + 설정만 400·비우기 허용(화면 "기간 해제"), 새 잠금 없음(PR 1의 게시판 `FOR SHARE` 안에서 잠금 읽기 행의 값으로 판정). **계획과 달라진 구현 결정은 없다.** 구현 중 정한 세부: `Board.update`의 인자는 `BoardDisplay`와 분리한 `BoardWritePolicy`, `PostService.lockPostForWrite`가 `LockedPost(post, board)`를 돌려줘 수정이 잠근 게시판 행의 정책을 쓴다.

**구현 파일**

| 파일 | 변경 |
|---|---|
| `db/migration/V7__board_write_policy.sql` | 신규 — `board` 컬럼 5개(`IF NOT EXISTS`, DEFAULT = 현행 동작) |
| `common/attachment/AttachmentFilePolicy.java` | `MAX_FILE_SIZE_MB`·`allowedExtensions()`·`normalizeExtensions()`·`requireAllowedExtension(filename, set)` |
| `admin/board/domain/Board.java`·`BoardWritePolicy.java`(신규 record) | 필드·기본값·`update` 인자·`writePolicy()`·`attachExtensionSet()`·문구 상수 |
| `admin/board/service/BoardService.java` | 범위 검증·확장자 정규화·PATCH 유지·"변경 없음" 판정 확장 |
| `admin/board/service/PostService.java`·`PostAttachmentService.java` | 예약 정책 판정, `replaceRefs(…, allowNewImages)`, 게시판 확장자·크기·개수(전역과 `min`) |
| `admin/contentimage/service/ContentImageService.java` | `replaceRefs` 오버로드, `uploadForBoard` 이미지 불허 400 |
| `admin/board/service/MyBoardService.java`, `dto/**` | 새 필드(내 게시판은 실효 확장자 목록) |
| `admin/board/controller/BoardPageController.java` | 모델 `attachmentExtensions` |
| `templates/admin/board/manage.html`·`posts.html`, `static/js/admin/notice-editor.js` | "작성 정책" 묶음, 첨부 안내·`accept`, 기간 해제, `setImageUploadEnabled` |
| 시험(신규) | `BoardWritePolicyApiIntegrationTest`(13)·`BoardWritePolicyUpgradeTest`(1) + `BaselineSchemaTest`·`BoardServiceTest`·`PostServiceTest`·`PostAttachmentServiceTest`·`AttachmentFilePolicyTest` 추가 |
| 시험(기존 수정) | `PostServiceTest`의 `replaceRefs` 검증을 6인자로 |
| 문서 | `com/cms/admin/board/CLAUDE.md`(새 절)·`contentimage/CLAUDE.md`·`docs/migration-guide.md`(V7)·`docs/deployment.md`(롤백) |

**검증 결과**

- 영향 범위 시험(`admin.board`·`admin.contentimage`·`admin.permission`·`admin.media`·`publicweb.board`·`publicweb.notice`·`common`·`*ConventionTest`·`BaselineSchemaTest`): **실패 0**. 새 통합 시험 13건이 실행됐음을 결과 XML로 확인. 전체 `./gradlew test`는 PR 직전 1회(code-review-loop 마무리)로 남긴다.
- 변이 실험: `replaceRefs`의 새 이미지 거부 조건과 `PostService.requirePeriodAllowed`의 예약 판정을 각각 무력화하면 `BoardWritePolicyApiIntegrationTest`·`PostServiceTest`에서 7건이 실패함을 확인 후 복원.
- 실기(dev, `bootRun`, Playwright): dev DB(V6)에 V7 적용(`Successfully applied 1 migration … v7`). ① UI로 게시판 생성 — 개수 2·3MB·hwp/pdf·이미지 끔·예약 끔 저장, 재열기 때 값 그대로(스크린샷 `board-definition-pr2-screenshots/01-policy-modal.png`·`02-policy-modal-reopened.png`) ② 예약을 끈 게시판에 기간 있는 글 생성 API 400 ③ 예약 글(정책 끄기 전 작성)의 수정 화면: 기간 입력 숨김 + 기존 기간 표시 + "기간 해제", 이미지 버튼 숨김 + 안내(`03-posts-edit-period-locked.png`) ④ "기간 해제" → 저장 → 기간 없음·상태 ACTIVE ⑤ 첨부 영역 안내 "최대 2개 · 파일당 3MB · 허용 형식: hwp, pdf"와 `accept=".hwp,.pdf"` ⑥ 기본 설정 게시판(공지)은 기간 입력·이미지 버튼이 그대로 보임(회귀 없음) ⑦ 익명 게시판 PATCH·목록 401 ⑧ 검증 글·게시판을 API로 삭제(게시판 행은 소프트 삭제로 남음).
- **롤백 호환 실측**: V7이 적용된 DB에서 V4까지만 아는 이전 앱 이미지(`cms-app-dev`)가 경고만 남기고 기동, health 200.

**이슈**

- 첫 시험 실행에서 DB 의존 시험이 전부 `initializationError`(Docker Desktop 미기동 — 환경 문제). 기동 후 해결, 코드 원인 없음. 코드 쪽 실패는 `PostServiceTest`의 5인자 `replaceRefs` 검증 2건뿐(6인자로 정리).
- 8080을 Docker가 자동 재시작한 `cms-app-dev`가 점유 — 멈추고 `bootRun`, 검증 후 다시 시작했다.

**후속**

- 없음(PR 3 착수 때 §7의 PR 3 미결정 목록을 상세 설계에서 정한다).

---

## PR 3 상세 설계 — 표시 순서 + 상단 고정 (P3-v1, 2026-10-28)

§5 "PR 3" 윤곽의 상세 설계다. 개정 이력은 이 절 끝 "PR 3 개정 이력".

### P3-0. 사용자 결정 (2026-10-28 — 재질문 금지)

| # | 질문 | 결정 |
|---|---|---|
| U3-1 | 게시판 순서 방식 | **숫자 "표시 순서" 필드**(`board.sort_order`, 게시판 모달에서 입력, 정렬 = `sort_order` → `id`). FAQ식 순서 반영 API(`PUT /order`·가드 행·0..n-1 불변식)는 **하지 않는다** — 윤곽의 "생성과 순서 저장의 직렬화 수단" 쟁점이 사라진다 |
| U3-2 | 고정 글 공개 노출 | **검색하지 않은 1페이지 상단에만** 고정 영역. 고정 글은 일반 목록에서도 빼지 않는다(COUNT·페이지 수 불변). 공지 템플릿에도 같게 |
| U3-3 | 고정 설정 형태 | **상한 하나 `pin_max_count` 0..5**(0 = 고정 끔, `new_badge_days` 0 = 끔과 같은 방식). "허용 꺼짐 + 상한 3" 같은 모순 상태가 없다 |

### P3-1. 정찰 결과 (코드 근거)

- **게시판 목록 정렬**: 미삭제 게시판 목록은 `BoardRepository.findByDeletedFalseOrderByIdAsc` 하나이고 호출자는 `BoardService.getBoards`(게시판 관리)·`MyBoardService.getMyBoards`(게시글 화면 "내 게시판")·`MemberPermissionService.getMatrix`·`replace`(권한관리 게시판 표) 네 곳이다. 공개 화면에는 게시판 색인이 없다(§6). 시험의 목 호출은 `MemberPermissionServiceBoardTest` 3곳.
- **게시판 수정**: `BoardService.updateBoard`는 입력 검증(잠금 전) → 게시판 `FOR UPDATE` → `version` 409 → "누락 = 유지"로 최종값 계산 → `Board.update(name, publicYn, attachmentYn, BoardDisplay, BoardWritePolicy, now)`(`version` +1). "변경할 필드 없음" 400은 요청 필드를 전부 나열해 판정한다.
- **게시글 쓰기 잠금**: 생성 = 게시판 `FOR SHARE`(`findByIdAndDeletedFalseForShare`), 수정 = 게시글 `FOR UPDATE` → 게시판 `FOR SHARE`(`PostService.lockPostForWrite`, `LockedPost(post, board)` 반환), 삭제 = 게시글 `FOR UPDATE` + 게시판 비잠금 확인. 게시판 PATCH·삭제 = 게시판 `FOR UPDATE`이고 게시글 행을 잠그지 않는다(삭제의 게시글 존재 확인 `existsByBoardIdAndDeletedFalse`는 비잠금 읽기). 권한 PUT = 회원 `FOR UPDATE` → 게시판 `FOR SHARE`.
- **요청 DTO**: `PostCreateRequest`·`PostUpdateRequest`는 `@JsonCreator(mode = DISABLED)` 생성자 + `@Builder`이고 `publishPeriod` 세터만 중복 키를 거부한다(그 밖의 필드는 일반 세터 — `com.cms.admin.board` CLAUDE.md "예약 게시"). 새 `Boolean` 필드는 생성자 인자 추가로 충분하다.
- **공개 목록**: `PublicBoardService.getPublishedPosts`가 게시판 공개 조회 → 목록 크기·페이지 보정 → `PostRepositoryImpl.searchPublished`(QueryDSL, SELECT·COUNT가 `isPublished` 조건 객체 공유). `PublicNoticeService`는 같은 메서드에 위임하므로 **고정 영역 계산은 한 곳**이고, 템플릿은 `public/board/list.html`·`public/notice/list.html` 두 벌(모델 이름 `posts`·`notices`)이다. 공개 메인은 `findLatestPublished`(고정 무시 — 윤곽 추천 그대로).
- **인덱스**: `post`는 `idx_post_board_deleted_use_create(board_id, deleted, use_yn, create_date)` 하나. 공개 목록 요청마다 이 인덱스로 그 게시판의 공개 행 전체를 세는 COUNT가 이미 돈다.
- **화면**: `manage.html` 모달은 "기본"·"공개 화면 표시"·"작성 정책" 세 묶음, 목록 8열. `posts.html`의 목록은 9열(`colspan="9"`), 폼은 `formUseYn` 체크박스 옆에 노출 기간, 변경 감지(`buildEditPayload`)는 바뀐 필드만 보낸다. 게시판 선택은 `MyBoardResponse` 목록 순서 그대로다.

### P3-2. 설계 결정

**D3-1. 스키마** — `V8__board_sort_order_and_pin.sql`(DDL 두 문장, `ADD COLUMN IF NOT EXISTS`)

```sql
ALTER TABLE `board`
  ADD COLUMN IF NOT EXISTS `sort_order` int(11) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS `pin_max_count` int(11) NOT NULL DEFAULT 0;
ALTER TABLE `post`
  ADD COLUMN IF NOT EXISTS `pinned` bit(1) NOT NULL DEFAULT 0,
  ADD INDEX IF NOT EXISTS `idx_post_board_pinned` (`board_id`, `pinned`, `deleted`, `create_date`);
```

- DEFAULT = 현행 동작(순서 0 → 모두 같아 id 순 = 지금과 같음, 고정 끔, 고정 안 된 글). 롤백한 앱이 만든 게시판·게시글도 재배포 후 지금과 같다(쟁점 6). 기존 테이블에 `NOT NULL` 컬럼을 더하므로 **업그레이드 경로 시험**(`BoardSortOrderPinUpgradeTest`, PR 2의 `BoardWritePolicyUpgradeTest` 형식 — V7까지 → 게시판·게시글 행 → V8 → `0·0`·`0`, 기존 컬럼 보존) + `BaselineSchemaTest` 단언.
- **인덱스 `idx_post_board_pinned(board_id, pinned, deleted, create_date)`를 추가한다**(윤곽의 "고정용 인덱스 실측" 해소, P3-v2 — 리뷰 P3-R1-2): 고정 개수 COUNT(D3-3)는 **게시판 `FOR UPDATE`를 쥔 채** 실행되고 비노출·예약 글까지 세므로, 기존 인덱스(`board_id, deleted, use_yn, create_date`)로는 그 게시판의 미삭제 글 전체를 훑는 동안 같은 게시판의 쓰기가 기다린다. 새 인덱스는 COUNT(`board_id = ? ∧ pinned = 1 ∧ deleted = 0`)와 공개 고정 영역(같은 접두 + `create_date` 역순, 남은 `use_yn`·기간은 고정 행 몇 개에만 적용)을 모두 **고정 행 범위(삭제 글 포함 소수)**로 줄인다. 유지 비용은 게시글 쓰기마다 인덱스 하나 — 수용. 구현 후 dev DB에서 두 쿼리의 `EXPLAIN`이 이 인덱스를 쓰는지 확인해 결과에 남긴다(옵티마이저가 다른 인덱스를 고르면 원인을 기록하고 사용자에게 보고).

**D3-2. 표시 순서(`sort_order`)** — U3-1

- 범위 **0..9999 정수**(밖은 400 "표시 순서는 0~9999여야 합니다."). 작은 값이 먼저, 같으면 `id` 오름차순. 연속성(0..n-1)·유일성은 요구하지 않는다 — 그래서 생성·삭제와 직렬화할 것이 없고(다른 행을 고치지 않는다) 게시판 PATCH의 `version` 409가 같은 게시판의 동시 편집만 막으면 된다. 서로 다른 두 게시판에 같은 값을 넣는 동시 저장은 둘 다 성공하고 id 순으로 갈린다 — 의도한 결과다.
- 저장소: `findByDeletedFalseOrderByIdAsc`를 **`findByDeletedFalseOrderBySortOrderAscIdAsc`로 이름째 바꾼다**(옛 이름을 남기면 새 호출자가 다른 순서를 쓰는 표류가 생긴다). 호출자 네 곳(P3-1)이 모두 새 순서를 쓴다 — 게시판 관리 목록·"내 게시판" 선택·권한관리 게시판 표가 같은 순서다.
- 요청: `BoardCreateRequest.sortOrder`(선택, 누락 = 0)·`BoardUpdateRequest.sortOrder`(누락·null = 유지). 범위 검증은 서비스 한 곳·잠금 전(PR 1 규칙 — DTO에 범위 어노테이션 없음). "변경할 필드 없음" 판정에 포함. 응답 `BoardResponse.sortOrder`.
- 엔티티: `Board.update`의 "null = 유지" 인자(이름·공개·첨부와 같은 무리)에 `sortOrder`를 더한다 — 지울 수 없는 값이라 `BoardDisplay`의 "최종값 전부 대입" 무리에 넣을 이유가 없다.

**D3-3. 고정 상한(`pin_max_count`)과 고정 글 개수 규칙** — U3-3

- 범위 **0..5**(`Board.MAX_PIN_COUNT = 5`, 밖은 400). 0 = 고정 끔. "공개 화면 표시" 묶음이므로 `BoardDisplay`에 넣는다(PATCH 누락·null = 유지, 생성 누락 = 0). 응답 `BoardResponse.pinMaxCount`, `MyBoardResponse.pinMaxCount`(게시글 화면 체크박스 표시용).
- **고정 개수 = 그 게시판의 미삭제(`deleted=0`) ∧ `pinned=1` 게시글 수**. 노출 여부·노출 기간은 보지 않는다 — 공개 상태는 시각에 따라 바뀌므로 그것으로 세면 예약 글이 공개되는 순간 상한을 넘을 수 있다. 소프트 삭제된 글의 `pinned`는 그대로 남지만 세지 않는다(복원 API가 없다).
- **새로 고정하는 쓰기**(생성 `pinned=true`, 또는 수정 `pinned=true`이면서 지금 고정 아님): 게시판 `pin_max_count = 0`이면 **400** "상단 고정을 허용하지 않는 게시판입니다."(`Board.PIN_DISABLED_MESSAGE`), 이 글을 뺀 고정 개수 ≥ 상한이면 **409** "상단 고정은 최대 N개까지 가능합니다. 다른 글의 고정을 먼저 해제하세요."(첨부 개수 초과 409와 같은 성질 — 입력 자체는 옳고 현재 상태와 충돌). 판정 순서: 쓰기 중지 400 → 예약 400(PR 2) → 고정 400 → 고정 409.
- **이미 고정된 글에 `pinned=true`**: 개수가 늘지 않으므로 통과(상한을 낮춘 뒤·0으로 끈 뒤에도). **고정 해제(`pinned=false`)는 언제나 허용**(상한·0과 무관) — 개수를 줄이는 방향이다. `null`(키 누락) = 유지.
- **상한을 낮추거나 0으로 꺼도 기존 `pinned` 값은 그대로**(PR 2 D2-7과 같은 규칙 — 데이터를 건드리지 않는다). 공개 고정 영역은 그 시점의 상한만큼만 보이고(D3-5), 0이면 영역이 사라진다. 다시 켜면 돌아온다. 관리 화면에는 고정 표시가 계속 보여 해제할 수 있다.

**D3-4. 고정 상한의 동시성** — 윤곽 v2(R1-2) 확정: **새로 고정할 수 있는 쓰기는 게시판 행을 `FOR UPDATE`로 잡는다**

- `FOR SHARE`끼리는 서로 막지 않아, 상한 4/5에서 두 글을 동시에 고정하면 둘 다 "4 < 5"를 보고 6이 된다. 그래서 **요청에 `pinned=true`가 있으면**(현재 값과 무관하게 — 잠금 모드는 게시글을 읽기 전에 정해야 하고, 읽은 뒤 `FOR SHARE` → `FOR UPDATE`로 올리면 같은 행을 공유 잠금한 두 트랜잭션이 서로의 승격을 기다리는 교착이 생긴다) 게시판을 처음부터 `FOR UPDATE`로 잡는다: 생성 = 게시판 `FOR UPDATE`, 수정 = 게시글 `FOR UPDATE` → 게시판 `FOR UPDATE`. `pinned`가 없거나 `false`면 지금처럼 `FOR SHARE`.
- **개수 읽기가 최신 커밋을 보게 하는 방법**(P3-v2 — 리뷰 P3-R1-1): REPEATABLE READ에서 비잠금 COUNT는 그 트랜잭션의 **첫 일관 읽기 시점 스냅샷**을 쓴다. 메서드 안에서 게시판 잠금 전에 비잠금 읽기가 없더라도, 호출자 트랜잭션에 참여하면 그 앞의 읽기가 스냅샷을 이미 만들었을 수 있고(앞서 고정하고 커밋한 글을 못 봄), 같은 영속성 컨텍스트에 먼저 로드된 `Board`는 잠금 조회가 돌려줘도 값이 갱신되지 않는다. 그래서 `site` 패키지 선례(`SiteService` — "쓰기 서비스는 컨트롤러가 호출하는 최상위 트랜잭션 진입점 + `READ_COMMITTED`")를 따른다:
  - `PostService.createPost`·`updatePost`를 **`@Transactional(isolation = READ_COMMITTED)`**로 바꾼다 — 비잠금 읽기마다 최신 커밋을 보므로 게시판 X 잠금 뒤의 COUNT가 직렬화된 앞 고정을 반드시 본다(메서드 안 읽기 순서에 기대지 않는다). 영향 검토: 두 메서드의 다른 읽기는 잠금 읽기(게시글·게시판·이미지 `FOR SHARE`)와 참조 투영 조회뿐이라 의미가 바뀌지 않고, `media` 패키지가 이미지를 지우는 진입점에 쓰는 것과 같은 격리 수준이라 갭 락이 오히려 준다. 게시글 삭제·첨부 서비스는 고정 판정이 없어 그대로 둔다.
  - **두 메서드는 최상위 진입점이다**(호출자는 `PostController` 둘뿐 — 정찰 확인). 클래스 주석에 계약으로 적고, 시험이 **실행 중 트랜잭션의 격리 수준을 관측**해 `READ_COMMITTED`임을 고정한다(`SiteMenuConcurrencyIntegrationTest`의 진입점 격리 수준 관측과 같은 방식). 참여 트랜잭션에서는 격리 수준 설정이 무시되므로, 서비스 안에 다른 트랜잭션을 열고 부르는 새 호출자를 만들면 이 보장이 깨진다는 점을 `com.cms.admin.board` CLAUDE.md에 남긴다.
  - 대안 기각: COUNT를 잠금 읽기(`FOR SHARE`)로 — 고정 글 행을 공유 잠금하는 동안 그 글을 `FOR UPDATE`로 쥔 수정(게시글 X → 게시판 S/X 대기)과 반대 방향 의존이 생겨 교착한다. 게시판 행에 고정 개수 카운터를 두는 안 — 해제·삭제도 게시판 X를 잡아야 하고 직접 SQL·롤백 기간에 표류한다.
- **교착 검토**: 잠금 순서는 게시글 X → 게시판 X(기존 게시글 X → 게시판 S와 같은 방향). 게시판 행만 잡는 쓰기(PATCH·삭제)는 게시글 행을 잠그지 않고, 권한 PUT은 회원 X → 게시판 S로 게시글을 잠그지 않으며, 고정 쓰기는 회원 행을 잠그지 않는다 → 대기 그래프에 순환이 없다. 이미지 참조 교체(`replaceRefs`의 이미지 `FOR SHARE`)는 지금처럼 게시판 잠금 뒤다.
- 비용: 고정 쓰기 동안 그 게시판의 다른 게시글 생성·수정(`FOR SHARE`)이 잠시 기다린다 — 고정 변경은 드물어 수용(윤곽 그대로). 게시판 PATCH가 고정 쓰기와 직렬화되므로 "상한을 2로 낮추는 PATCH와 세 번째 고정"이 끼어들지 않는다.
- 시험(P3-v2 — 리뷰 P3-R1-3, 요청 시작만 맞추면 `FOR SHARE` 변이도 우연히 직렬화돼 통과할 수 있다): 상한 2·고정 1개 상태에서 **시험이 별도 JDBC 트랜잭션으로 게시판 행을 `LOCK IN SHARE MODE`로 먼저 쥔다** → 두 고정 요청을 보낸다 → `LockWaitObserver`로 **두 요청이 모두 그 게시판 행의 잠금 대기에 들어갔음**을 확인한다(올바른 구현은 X 요청이라 둘 다 대기, `FOR SHARE` 변이는 대기가 0건이라 여기서 확정 실패) → 시험 잠금을 놓는다 → **정확히 하나 200·하나 409**, DB 고정 개수 2. 조합은 생성↔생성·생성↔수정·수정↔수정(생성과 수정은 잠금 순서가 달라 따로 본다). 변이 실험(고정 쓰기의 게시판 잠금을 `FOR SHARE`로, 격리 수준을 기본값으로)으로 실패를 확인한다.

**D3-5. 공개 고정 영역** — U3-2

- `PublicBoardService.getPublishedPosts`: **검색어가 비어 있고 보정된 페이지가 0이며 게시판 `pin_max_count > 0`일 때만** `postRepository.findPinnedPublished(boardId, now, min(pin_max_count, MAX_PIN_COUNT))`를 한 번 더 부른다. 그 밖에는 쿼리 없이 빈 목록. 결과는 `PublicBoardListResult.pinned`(`List<PublicPostSummary>`, 항상 non-null)로 싣고 일반 목록과 같은 `toSummary`(조회수·새 글 배지 규칙)를 쓴다.
- `findPinnedPublished`(`PostRepositoryImpl`, QueryDSL): 조건 = `boardId` ∧ `deleted=false` ∧ **기존 `isPublished(post, now)`** ∧ `pinned=true`, 정렬 `createDate desc, id desc`(일반 목록과 같은 순서), `limit`. **공개 조건 객체를 새로 쓰지 않고 재사용한다** — "공개 경로 한 쌍"(쟁점 2) 중 목록 계열에 하나가 더 붙는 것이고, 상세·첨부·본문 이미지·메인은 바뀌지 않는다(고정은 표시 순서일 뿐 공개 판정이 아니다). `PostRepositoryDataJpaTest`의 경계 대조에 고정 쿼리를 추가해 "비노출·예약·만료·삭제·다른 게시판의 고정 글은 영역에 없다"를 고정한다.
- 고정 글은 **일반 목록에서도 빼지 않는다** — 일반 목록·COUNT 쿼리는 그대로라 페이지 수·`hasNext`가 지금과 같다(1페이지에서 같은 글이 영역과 목록에 함께 보일 수 있다 — 흔한 게시판 동작이고, 빼면 COUNT 조건이 갈려 "공개 경로 한 쌍"의 조건 객체 공유가 깨진다).
- 템플릿: `public/board/list.html`·`public/notice/list.html`에 목록 위 `<ul class="board-pinned-list">`(공지는 `notice-` 접두어) — 행마다 "고정" 표시 + 제목·작성자 표시명·조회수·날짜(일반 행과 같은 `th:text`, 링크는 같은 상세 경로). `pinned`가 비면 영역 자체를 그리지 않는다. CSS는 `board.css`·`notice.css`에 같은 규칙(두 파일이 따로인 기존 구조 유지). 컨트롤러 두 곳이 모델 `pinnedPosts`를 더한다. 이스케이프 없는 출력은 늘지 않는다(`PublicBoardTemplateConventionTest`·`PublicNoticeTemplateConventionTest` 그대로 통과해야 함).
- 공개 메인(`findLatestPublished`)·공개 상세·첨부·본문 이미지: 변경 없음.

**D3-6. 관리 API·화면**

- 요청: `PostCreateRequest.pinned`(`Boolean`, 누락·null = false)·`PostUpdateRequest.pinned`(누락·null = 유지) — 생성자 인자에 추가(`@JsonCreator(DISABLED)` 유지). 수정의 "변경할 필드 없음" 판정에 포함. 권한은 기존 그대로(생성 = CREATE, 수정 = UPDATE — 고정은 게시글 수정이다. 새 권한·경로 없음).
- 엔티티: `Post.pinned`(`@Builder.Default false`), `Post.update(title, content, useYn, pinned, updaterId, now)`로 인자 추가(null = 유지, 같은 쓰기에서 `touch`). 개수 질의 `PostRepository.countByBoardIdAndPinnedTrueAndDeletedFalseAndIdNot(boardId, postId)`(생성은 `countByBoardIdAndPinnedTrueAndDeletedFalse`).
- 응답: `PostResponse.pinned`·`PostSummaryResponse.pinned`. 관리 목록 정렬은 바꾸지 않는다(정렬 화이트리스트에 `pinned`를 넣지 않는다 — 관리 화면은 고정 여부를 표시로만 본다).
- `posts.html`: 폼에 "상단 고정" 체크박스 — **게시판 `pinMaxCount > 0`이거나 수정 중인 글이 이미 고정일 때만** 보인다(끈 게시판에서도 해제는 할 수 있게). 안내 "이 게시판은 최대 N개까지 고정할 수 있습니다. 공개 목록 1페이지 상단에 보입니다." / 끈 게시판의 고정 글이면 "고정이 꺼진 게시판입니다. 해제만 할 수 있습니다." 변경 감지는 값이 바뀐 경우에만 `pinned`를 보낸다. 목록 제목 칸 앞에 "고정" 배지(`textContent`), 상세에 "상단 고정: 예/아니오". 열 수(9)는 늘리지 않는다.
- `manage.html`: "기본" 묶음에 "표시 순서"(number 0..9999, 기본 0, 안내 "작을수록 먼저, 같으면 #번호 순. 게시판 목록·게시글 화면·권한 관리에 쓰입니다."), "공개 화면 표시" 묶음에 "상단 고정 최대 개수"(select 0(끔)..5, 안내 "낮추거나 꺼도 이미 고정된 글은 그대로이고 공개 영역에 상한만큼 보입니다."). 목록에 "순서" 열 추가(8 → 9열, 빈 목록 `colSpan` 9). 저장 중 잠금 목록(`setSaving`)에 두 입력을 더한다. 화면 목록은 서버 응답 순서 그대로(저장 응답으로 보정한 뒤 재조회가 순서를 바로잡는다).
- 감사: 기존 `BOARD_UPDATE`·`POST_CREATE`·`POST_UPDATE` 그대로(새 행위 종류 없음).

**D3-7. 롤백**

- 이전 앱(V7까지)은 새 컬럼을 모른다: 게시판은 id 순으로 보이고, 공개 고정 영역이 없으며, 고정 상한·고정 판정을 하지 않는다(새 고정 자체를 할 수 없다 — 요청 필드를 모름). 이전 앱의 게시글 수정은 `pinned`를 매핑하지 않아 값을 건드리지 않으므로 재배포 후 고정 상태가 그대로 돌아온다. 롤백 중 만든 게시판·게시글은 DEFAULT(순서 0·고정 끔·고정 아님). `docs/deployment.md`에 "게시판 표시 순서·상단 고정" 롤백 주의로 적는다.

### P3-3. 변경 파일

| 층 | 파일 | 변경 |
|---|---|---|
| 스키마 | `db/migration/V8__board_sort_order_and_pin.sql` | 신규(`board` 2·`post` 1 컬럼) |
| 도메인 | `Board.java`·`BoardDisplay.java`, `Post.java` | `sortOrder`·`pinMaxCount`(+`MAX_PIN_COUNT`·`PIN_DISABLED_MESSAGE`), `pinned`·`update` 인자 |
| 저장소 | `BoardRepository`, `PostRepository`·`PostRepositoryCustom`·`PostRepositoryImpl` | 정렬 메서드 이름 교체, 고정 개수·`findPinnedPublished`, 게시판 `FOR UPDATE` 재사용 |
| 서비스 | `BoardService`·`MyBoardService`·`MemberPermissionService`, `PostService` | 순서·상한 검증, 새 정렬, 고정 판정·잠금 모드 |
| 공개 | `PublicBoardService`, `PublicBoardListResult`, `PublicBoardController`·`PublicNoticeController` | 고정 영역·모델 `pinnedPosts` |
| DTO | `BoardCreateRequest`·`BoardUpdateRequest`·`BoardResponse`·`MyBoardResponse`, `PostCreateRequest`·`PostUpdateRequest`·`PostResponse`·`PostSummaryResponse` | 새 필드 |
| 화면 | `admin/board/manage.html`·`posts.html`, `public/board/list.html`·`public/notice/list.html`, `css/public/board.css`·`notice.css` | D3-5·D3-6 |
| 시험 | P3-4 | |
| 문서 | `com/cms/admin/board/CLAUDE.md`·`publicweb/board/CLAUDE.md`·`publicweb/notice/CLAUDE.md`(한 줄)·`docs/migration-guide.md`(V8)·`docs/deployment.md`(롤백) | |

인가 정책·`SecurityConfig`·공개 URL·레이트리밋·sanitizer·공개 판정(상세·첨부·이미지·메인) 변경 없음. 공용 코드(`common/**`·`config/**`)는 바꾸지 않지만 마이그레이션이 있어 **PR 직전 전체 `./gradlew test`**(CLAUDE.md 검증 범위 — 마이그레이션은 전체 대상).

### P3-4. 시험 계획

- 스키마: `BaselineSchemaTest`(새 컬럼 3개 타입·NULL·DEFAULT), `BoardSortOrderPinUpgradeTest` 신규(V7 → V8 기존 행).
- 단위 `BoardServiceTest`: 순서 −1·0·9999·10000, 상한 −1·0·5·6 400(**잠금 조회 전**), 누락 PATCH 유지, 새 필드만 보낸 PATCH가 "변경 없음"이 아님.
- 단위 `PostServiceTest`: `pinned=true`면 게시판을 `FOR UPDATE` 메서드로 잠그고(생성·수정), 없거나 `false`면 `FOR SHARE`, 상한 0 → 400, 개수 = 상한 → 409(쓰기 호출 없음), 이미 고정 + `true` → 개수 질의 없이 통과, 해제는 상한 0에서도 통과, 판정 순서(쓰기 중지 → 예약 → 고정 400 → 409).
- 저장소 `PostRepositoryDataJpaTest`: `findPinnedPublished`가 공개 경계(비노출·시작 전·종료·삭제·다른 게시판·고정 아님) 행을 빼고 `createDate desc, id desc`·`limit`을 지킴. 개수 질의가 삭제 글을 빼고 비노출·예약 글을 셈.
- 통합 `BoardSortOrderPinApiIntegrationTest` 신규(실 DB, MockMvc): 게시판 순서 왕복과 세 목록(게시판 관리·내 게시판·권한 매트릭스)의 순서(`sort_order` → `id`, 같은 값 id 순), 상한 왕복, 고정 생성·수정·해제 왕복(응답·DB), 상한 0 게시판 고정 400, 상한 초과 409, **상한을 2→1로 낮춘 뒤 고정 2개 그대로·새 고정 409·해제 200·0으로 끈 뒤 해제 200**, 쓰기 중지 게시판 고정 400(쓰기 중지 메시지), MANAGER(게시판 UPDATE만) 고정 200·READ만 403.
- 동시성 `BoardPinConcurrencyIntegrationTest` 신규: D3-4 시험(시험 잠금 → 두 요청 대기 관측 → 해제 → 정확히 하나 409, 세 조합) + 고정 쓰기 ↔ 게시판 PATCH(상한 낮춤) 직렬화(PATCH가 먼저 커밋되면 고정 409) + **`createPost`·`updatePost` 실행 중 격리 수준 = `READ_COMMITTED` 관측**. 변이 실험(잠금 모드 `FOR SHARE`로·격리 수준 기본값으로) 확인.
- 스키마 단언에 `idx_post_board_pinned` 열 순서 포함, 구현 후 `EXPLAIN`(고정 COUNT·고정 영역)을 결과에 기록.
- 공개 `BoardPinPublicIntegrationTest` 신규(`publicweb.board`): 1페이지 상단 영역(일반·공지 경로 모두), 2페이지·검색 중에는 영역 없음, 영역 글이 일반 목록에도 있고 총 페이지 수 불변, 상한만큼만(상한 낮춤 후), 상한 0이면 영역 없음, 비노출·예약·만료 고정 글은 영역에 없음, 다른 사이트 전용 게시판 404 그대로, 제목 XSS 이스케이프, 공개 메인 새 글 순서 불변.
- 기존 시험 회귀: `MemberPermissionServiceBoardTest`(메서드 이름 교체), `PostApiIntegrationTest`·`PublicBoardIntegrationTest`·`BoardDisplaySettingsPublicIntegrationTest`는 DEFAULT로 그대로 통과해야 한다. 템플릿 컨벤션 시험 두 개.
- 화면(playwright): 게시판 모달 순서·상한 저장·재열기·목록 순서, 게시글 화면 게시판 선택 순서, 고정 체크박스 표시 조건(상한 0 게시판 숨김·끈 게시판의 고정 글은 해제만), 목록 "고정" 배지, 공개 목록 1페이지 영역·2페이지 없음(일반·공지).

### PR 3 개정 이력

- P3-v1: 초안(사용자 결정 U3-1~3 반영, 인덱스 없음 결정, 고정 개수 = 미삭제 고정 글, 잠금 모드는 요청 `pinned=true`로 사전 결정).
- P3-v2 변경(리뷰 1라운드): P3-R1-1 고정 COUNT의 스냅샷 의존 제거 — `createPost`·`updatePost`를 `READ_COMMITTED` + 최상위 진입점 계약(`SiteService` 선례), 실행 격리 수준 관측 시험(D3-4). 기각: COUNT 잠금 읽기(교착 방향), 게시판 고정 카운터(해제·삭제도 X 잠금·표류). P3-R1-2 "최대 두 배" 논리는 잠금 안 COUNT에 성립하지 않아 철회하고 인덱스 `idx_post_board_pinned(board_id, pinned, deleted, create_date)` 추가(D3-1). P3-R1-3 동시성 시험을 "시험이 게시판 행 공유 잠금 → 두 요청 대기 관측 → 해제" 구조로, 생성↔생성·생성↔수정·수정↔수정 세 조합(D3-4·P3-4).
- 리뷰 2라운드: READ_COMMITTED 전환의 영향(이미지 참조 교체·쓰기 중지·예약·감사 AOP)은 결함 없음 확인. 기각: P3-R2-1 V8 인덱스 생성이 배포 기동 60초 제한을 넘을 위험 — 아직 실배포 전이고 첫 실배포 직전에 V3~Vn을 기준 스키마로 합치므로(루트 CLAUDE.md "스키마 관리") V8이 대규모 운영 `post`에서 실행될 경로가 없다. 부분 적용 후 재실행은 모든 절이 `IF NOT EXISTS`라 `flyway repair` 뒤 그대로 다시 돈다 — 이 사실만 `docs/migration-guide.md` V8 항목에 적는다.
- 리뷰 3라운드: ship(새 지적 없음). 사용자 승인 대기.

### 구현·검증 결과 — PR 3 (2026-10-28)

**Context**: 게시판 목록 순서가 id 순으로 고정돼 관리자가 순서를 정할 수 없었고, 공개 목록에는 중요한 글을 위에 두는 수단이 없었다. 이 PR은 게시판 표시 순서(숫자 필드)와 게시글 상단 고정(게시판별 상한)을 넣는다.

**핵심 확정 사항**: P3-v2 설계 그대로 구현했다 — `board.sort_order`(0..9999, 작은 값 먼저·같으면 id)·`board.pin_max_count`(0..5, 0 = 끔)·`post.pinned`와 인덱스 `idx_post_board_pinned`(V8, 전부 현행 동작 DEFAULT), 순서는 일반 PATCH(`version` 409)로 바꾸고 순서 반영 API·가드 행은 두지 않음, 고정 개수 = 미삭제 `pinned` 글 수(노출·기간 무관), 새 고정만 400(끔)·409(상한), 해제·이미 고정된 글의 재고정은 항상 통과, 상한을 낮춰도 기존 `pinned`는 그대로, 고정 요청은 게시판 `FOR UPDATE`를 처음부터 잡고 `createPost`·`updatePost`는 `READ_COMMITTED` 최상위 진입점, 공개 고정 영역은 검색 없는 1페이지에서만 같은 `isPublished` 조건 객체로 조회(일반 목록·COUNT 불변). **계획과 달라진 결정은 없다.** 구현 중 코드 결함 1건을 시험이 잡아 고쳤다: 고정 판정에서 개수 COUNT를 인자로 먼저 평가해 상한 0(고정 끔) 게시판에서도 불필요한 COUNT가 나갔다 → `requirePinAllowed(board)`가 상한을 먼저 보고 필요할 때만 센다(`PostServiceTest.createPost_pinRejected`).

**구현 파일**

| 파일 | 변경 |
|---|---|
| `db/migration/V8__board_sort_order_and_pin.sql` | 신규 — `board` 2컬럼 + `post.pinned` + 인덱스(`IF NOT EXISTS`, DEFAULT = 현행 동작) |
| `admin/board/domain/Board.java`·`BoardDisplay.java`·`Post.java` | `sortOrder`·`pinMaxCount`(+상수·문구)·`pinned`, `Board.update`(순서 인자)·`Post.update`(고정 인자) |
| `admin/board/repository/**` | 정렬 메서드를 `findByDeletedFalseOrderBySortOrderAscIdAsc`로 교체, 고정 개수 질의, `findPinnedPublished` |
| `admin/board/service/BoardService.java`·`MyBoardService.java`·`PostService.java`, `admin/permission/service/MemberPermissionService.java` | 검증(잠금 전)·새 정렬·고정 판정·`lockBoard(exclusive)`·`READ_COMMITTED` |
| `admin/board/dto/**` | 게시판·게시글 요청/응답 새 필드(`MyBoardResponse.pinMaxCount` 포함) |
| `publicweb/board/**`·`publicweb/notice/controller` | `PublicBoardListResult.pinned`, 고정 영역 조회, 모델 `pinnedPosts`/`pinnedNotices` |
| `templates/admin/board/manage.html`·`posts.html`, `templates/public/board/list.html`·`public/notice/list.html`, `css/public/board.css`·`notice.css` | D3-5·D3-6 |
| 시험(신규) | `BoardSortOrderPinApiIntegrationTest`(14)·`BoardPinConcurrencyIntegrationTest`(5)·`BoardPinPublicIntegrationTest`(7)·`BoardSortOrderPinUpgradeTest`(2) + `BaselineSchemaTest`·`PostRepositoryDataJpaTest`(3)·`PostServiceTest`(8)·`BoardServiceTest`(2) 추가 |
| 시험(기존 수정) | 시그니처 맞춤 4곳(`Post.update`·`PublicBoardListResult` 생성자), `MemberPermissionServiceBoardTest`의 저장소 메서드 이름 |
| 문서 | `com/cms/admin/board/CLAUDE.md`(새 절)·`publicweb/board/CLAUDE.md`·`publicweb/notice/CLAUDE.md`·`docs/migration-guide.md`(V8)·`docs/deployment.md`(롤백) |

**검증 결과**

- 영향 범위 시험(`admin.board`·`publicweb.board`·`publicweb.notice`·`admin.permission`·`admin.contentimage`·`*ConventionTest`)과 새 시험 전부 **실패 0**. 전체 `./gradlew test`는 PR 직전 1회(code-review-loop 마무리)로 남긴다.
- **변이 실험**: 고정 쓰기의 게시판 잠금을 `FOR SHARE`로 바꾸면 `BoardPinConcurrencyIntegrationTest` 3건(생성↔생성·생성↔수정·수정↔수정)이 실패하고, `createPost`·`updatePost`의 `READ_COMMITTED`를 지우면 격리 수준 관측 시험 1건이 실패함을 확인 후 복원. 리뷰 1라운드에서 우려한 "요청 시작만 맞추는 시험은 잘못된 구현도 통과"를 시험이 게시판 행을 먼저 `LOCK IN SHARE MODE`로 쥐고 요청의 잠금 대기를 관측하는 구조로 해소했다.
- **실기(dev, `bootRun`, Playwright)**: dev DB(V7)에 V8 적용(`Successfully applied 1 migration … v8`). ① 게시판 관리 목록에 "순서" 열 ② UI로 게시판 생성(순서 5·상한 2), 재열기 때 값 유지(스크린샷 `board-definition-pr3-screenshots/01~02`) ③ 게시글 목록 "고정" 배지, 수정 모드의 "상단 고정" 스위치와 안내 문구(`03~04`), UI 저장 → DB `pinned=1` ④ 상한 도달 상태에서 세 번째 고정 → 409 + 한국어 안내 ⑤ 익명 `/main/boards/5`: 고정 영역(B·A 최신순) + 일반 목록에도 포함(`05`), `?page=1`·`?keyword=`에는 영역 없음 ⑥ 익명 `GET /admin/api/boards` 401 ⑦ 검증 글·게시판을 API로 삭제(게시판 행은 소프트 삭제로 남음).
- **`EXPLAIN`**(리뷰 1라운드 P3-R1-2): 고정 개수 COUNT는 `idx_post_board_pinned`(Using index), 공개 고정 영역 조회도 같은 인덱스를 쓴다(possible_keys에 기존 인덱스와 함께 올랐고 옵티마이저가 새 인덱스를 고름, rows 2 — 데이터가 적은 dev 기준).
- **롤백 호환 실측**: V8이 적용된 DB에서 V4까지만 아는 이전 앱 이미지(`cms-app-dev`)가 "newer than the latest available migration" 경고만 남기고 기동, health 200.

**이슈**

- 첫 실행에서 시험 3건이 실패했으나 모두 시험 쪽 오류였다(게시글 삭제 응답 204를 200으로 기대, 공개 영역 위치 비교 기준 오류, 고정 판정의 COUNT 평가 순서는 위 코드 결함). 환경 문제는 없었다(Docker Desktop 정상).
- 8080을 Docker가 자동 재시작한 `cms-app-dev`가 점유 — 멈추고 `bootRun`, 검증 후 다시 시작했다.

**후속**

- 없음(PR L은 sanitizer 보안 화이트리스트 변경이라 착수 전 승인이 필요하다).

## PR L 상세 설계 — 본문 루트 상대 내부 링크 허용 (PL-v1, 2026-10-28)

> §5 PR L 초안을 구체화한다. 이 절만 `plan-review-loop` 대상이다. 마이그레이션 없음(현재 최대 V8), `SecurityConfig`·인가 정책 변경 없음.

### PL-0. 사용자 결정 (2026-10-28 — 재질문 금지)

- **sanitizer 보안 화이트리스트 변경 승인**(네 본문 도메인 전부: 게시글·정적 페이지·FAQ 답변·HTML 팝업).
- **루트 상대 경로(`/…`)만 허용.** 경로 상대(`boards/12`, `../x`)·쿼리만(`?a`)·프래그먼트만(`#a`)은 계속 `href` 제거.
- **내부 링크는 같은 창**: `target`을 붙이지 않고 `rel`은 유지한다. 외부(http/https/mailto) 링크는 지금처럼 `target="_blank"` + `rel` 강제.
- 정적 페이지 슬러그가 ASCII인 것은 확인했다(`SiteSlug` `^[a-z0-9]+(-[a-z0-9]+)*$`, 2~50자) — 경로 퍼센트 인코딩 전면 거부로 잃는 공개 경로가 없다.

### PL-1. 정찰 결과 (코드 근거)

1. `HtmlContentSanitizer.SAFELIST`는 `a`의 `href`를 `http`·`https`·`mailto` 프로토콜로만 허용하고 `rel`·`target`을 `addEnforcedAttribute`로 **모든 `a`에** 강제한다. 네 본문(`ContentBodyPolicy.sanitize`·`sanitizePage`·`sanitizePopup`·`sanitizeFaq`와 각 출력용 정리)이 모두 `HtmlContentSanitizer.sanitize(html, allowImages)` 한 곳을 거치므로 허용 규칙은 이 한 곳에서 바꾼다.
2. **실측(jsoup 1.23.2)**: `Safelist.preserveRelativeLinks(true)`를 켜도 `/main/faq`·`boards/12`·`?a=1`·`#a`·`//evil.test/x` 전부 `href`가 제거된다(프로토콜 검사를 통과하지 못하면 상대 값도 지움). 그래서 **Safelist 설정만으로는 루트 상대 링크를 살릴 수 없다.**
3. 비공개 경로 판정 `SiteLinkPolicy.isPrivatePath`는 `admin.site` 패키지 소속 `static` 패키지 전용 메서드다. `common.html`이 `admin.site`를 참조하면 의존 방향(common ← admin)이 뒤집힌다. 시험에서 이 메서드를 직접 부르는 곳은 없고(`SiteLinkPolicyTest`·`MenuServiceTest`는 `permits`/`validate` 경유) 규칙 변경 없이 옮길 수 있다.
4. 루트 상대 경로 형식 규칙은 `SafeUrls.SAME_ORIGIN_PATH_REGEX`(`/` 시작, `//`·`\`·공백·제어문자 금지)가 이미 있다.
5. Quill 2.0.3 링크 서식(`quill.js`): `Link.sanitize(url)`은 `<a>`에 `href`를 넣어 **브라우저가 풀어낸 절대 URL의 스킴**을 `PROTOCOL_WHITELIST`와 비교한다 — 상대 경로는 `http(s)`로 풀려 통과한다. 편집기 쪽 변경은 없다(툴바·`formats`·`PROTOCOL_WHITELIST` 그대로). `//host`도 편집기는 통과시키지만 서버가 제거한다(편집기 ↔ 서버 불일치는 기존과 같은 방향: 서버가 더 엄격).
6. 기존 시험 `HtmlContentSanitizerTest.linkProtocols`는 `/admin` 링크의 `href` 제거를 고정한다 — 비공개 접두어라 이번에도 지워지므로 그대로 통과해야 한다.

### PL-2. 설계 결정

**PL-D1. 루트 상대 `href` 보존 방식 — Cleaner 전·후 표지 속성으로 우회**

- 선택지: ① Cleaner 뒤에 원본 `a`와 정리된 `a`를 순서로 짝지어 `href` 복원 ② Cleaner 전에 유효한 값을 임시 속성으로 옮기고 뒤에 되돌림 ③ 상대 링크를 가짜 절대 URL(`https://x.invalid/…`)로 바꿨다 되돌림.
- **②를 채택**: 전처리에서 모든 `a`의 `data-il` 속성을 지우고(위조 방지), `href`가 `/`로 시작하면 값을 `data-il`로 옮기고 `href`를 지운다. Safelist는 `a`의 `data-il`을 허용 속성으로 둔다. Cleaner 뒤 후처리에서 `data-il` 값이 **내부 링크 검증을 통과하면** `href`로 되돌리고(`target` 제거), 통과하지 못하면 버린다. 결과에 `data-il`은 절대 남지 않는다(항상 제거).
- 이유: ①은 Cleaner가 요소를 복제해 두 트리의 순서 대응에 의존하는 취약한 가정이다. ③은 본문에 같은 문자열이 실제로 있으면 오인할 수 있다. ②는 검증을 **한 곳(후처리)** 에서만 하므로 위조된 `data-il`이 들어와도 `href`와 같은 검증을 받는다(우회 불가).
- 멱등성: 결과 `href="/…"`는 다음 패스에서 같은 경로로 처리되어 같은 값이 나온다. 외부 링크는 지금과 같다. 전·후처리는 `sanitizeOnce()` 안에 두어 고정점 반복의 각 패스가 완결되게 한다(PL-v2).

**PL-D2. 내부 링크 검증 규칙 — `common`에 한 벌**

- 새 클래스 `com.cms.common.web.InternalLinkPaths`(이름은 구현 시 확정)에 `isAllowedBodyLink(String href)`를 둔다. 조건은 전부 만족해야 한다:
  1. `SafeUrls.isSameOriginPath(href)` (`/` 시작, `//`·`\`·공백·제어문자 없음).
  2. 경로 부분(`?`·`#` 앞)에 `%`가 없다(퍼센트 인코딩 전면 거부 — 쿼리·프래그먼트는 허용). `/%2F`·`/%5C` 같은 숨은 구분자와 `%31` 같은 숫자 변형이 함께 막힌다.
  3. 경로 부분에 `;`가 없다(`/admin;v=1`처럼 Tomcat이 경로 매개변수를 떼어내는 우회 방지. 이 CMS의 공개 경로에는 `;`가 없다).
  4. 비공개 경로가 아니다 — `SiteLinkPolicy.isPrivatePath`의 본문을 `common`으로 옮긴 `PrivatePaths.isPrivate(path)`를 공유한다(`.`·`..` 세그먼트, 첫 세그먼트 `admin`·`actuator`·`error`·`v3`·`swagger-ui*`, 소문자화).
- 이유: 비공개 접두어 규칙이 두 벌이 되면 한쪽만 고쳐지는 표류가 생긴다(초안 결정). `SiteLinkPolicy`는 같은 함수를 호출하도록 바꿔 동작 변화가 없다(`SiteLinkPolicyTest`가 고정).
- 사이트 범위: 어느 `/{siteKey}/…`든 허용, 존재 여부는 검사하지 않는다(초안 결정 유지). `/content-images/…` 같은 공개 비사이트 경로도 링크로는 허용된다(공개 판정은 그 경로 쪽에서 한다).

**PL-D3. 위반 처리 — 저장 거부 없이 `href`만 제거**

- 기존 sanitizer 동작과 일관: 위반 링크는 글자만 남기고 `href`를 지운다(400 아님). 출력 시에도 같은 정리를 다시 한다(저장·출력 이중, 멱등).
- 대안(400)은 기각: 4a의 숫자 경로 거부와 달리 여기서는 "허용 목록"이고 기존 동작이 "조용히 제거"다. 화면 안내는 만들지 않는다(과설계).

**PL-D4. 속성 강제 — 내부는 `target` 없음, `rel` 유지**

- `SAFELIST`의 `target` 강제를 없애고 후처리에서 직접 붙인다: 외부 `href`(http/https/mailto)가 남은 `a`에는 `target="_blank"`, 내부 `href`가 남은 `a`에는 붙이지 않는다. `rel="noopener noreferrer nofollow"`는 모든 `a`에 `addEnforcedAttribute` 그대로 유지(사용자 결정 "rel 유지").
- `href`가 제거되어 남은 `<a>`(글자만 남은 경우)는 **현재 동작 그대로** `rel`·`target`이 붙는다(`<a rel=… target="_blank">x</a>`). 이 동작을 바꾸지 않으려고 "href 없는 `a`에도 `target` 부여" 규칙으로 구현한다 — 시험이 고정.
- 속성 순서: 외부 링크는 기존 출력(`href`, `rel`, `target`)을 그대로 유지한다. 내부 링크는 Cleaner가 `rel`을 먼저 붙인 뒤 `href`를 복원하므로 `rel`, `href` 순서가 되며 순서는 계약이 아니다 — 시험은 속성 단위로 단언한다(PL-v2).

**PL-D5. 영향 범위**

- 4개 본문 도메인이 허용 목록을 공유하므로 **정적 페이지·팝업·FAQ 답변에서도 내부 링크가 생긴다.** 공개 렌더는 `th:utext` + 출력 시 재정리라 추가 변경이 없다.
- 4a가 나중에 본문 `/…/boards/{숫자}` 형태를 처리한다(범위 밖). 이 PR은 숫자 경로 거부를 하지 않는다 — 4a 전까지는 게시판 슬러그가 `board-{id}` 기본값이 아직 없다.

### PL-3. 변경 파일

| 파일 | 변경 |
|---|---|
| `common/web/InternalLinkPaths.java`(신규) | `isAllowedBodyLink` |
| `common/web/PrivatePaths.java`(신규) | `SiteLinkPolicy.isPrivatePath` 본문 이동(규칙 동일) |
| `admin/site/SiteLinkPolicy.java` | `isPrivatePath` 호출을 `PrivatePaths`로 교체 |
| `common/html/HtmlContentSanitizer.java` | `data-il` 전·후처리, `target` 강제 → 후처리, 클래스 주석(허용 규칙 갱신) |
| 시험 | `HtmlContentSanitizerTest`(허용·거부 표·멱등·속성), `InternalLinkPathsTest`(신규), `ContentBodyPolicyTest`/`PageBodyPolicyTest`에 왕복 1건씩, 네 도메인 왕복 |
| 문서 | `admin/board/CLAUDE.md`·`admin/page/CLAUDE.md`·루트 `CLAUDE.md`(콘텐츠 본문 절의 허용 목록 문구), `docs/troubleshooting.md`(필요 시) |

### PL-4. 시험 계획

- 허용: `/main/faq`, `/main/boards/b1?page=2`, `/main/pages/privacy-a#x`, 쿼리의 인코딩(`?k=%ED%95%9C`), 프래그먼트.
- 거부(`href` 제거): `//evil.test`, `/\evil`, `boards/12`, `../x`, `?a`, `#a`, `/a%31`, `/%2F`, `/%5C`, `/admin`, `/ADMIN/x`, `/admin;v=1`, `/actuator/health`, `/swagger-ui.html`, `/v3/api-docs`, `/error`, `/main/../admin`, 제어 문자·공백, `javascript:`, 위조 `data-il="/admin"`.
- 우회: 위조 `data-il`(허용 값)과 위험 `href`의 조합, 엔티티 인코딩 입력(`&#47;&#47;evil.test`·`/&#92;evil.test`·`/main/&#46;&#46;/admin`), 중첩 `a`(PL-v2).
- 속성: 내부 링크는 `target` 없음 + `rel` 있음, 외부 링크는 지금과 동일, `href` 제거 후 남은 `a`는 지금과 동일.
- 멱등: 허용·거부 표 각각 `sanitize(sanitize(x)) == sanitize(x)`. `data-il`이 결과에 없다.
- 네 본문 도메인 왕복(저장→출력): 게시글·정적 페이지·FAQ·HTML 팝업 각 1건, 이미지 불허 도메인(`allowImages=false`)에서도 동일.
- `SiteLinkPolicyTest`·`MenuServiceTest` 무변화 통과(이동 리팩터링의 회귀 방어).

### PL-5. 리스크

- 보안 화이트리스트가 넓어진다 — 오픈 리다이렉트는 없다(서버가 리다이렉트하지 않고 같은 출처 이동). 접근 통제는 URL 게이트가 담당하므로 비공개 경로 링크가 걸려도 인가는 우회되지 않는다(심층 방어 목적).
- 별칭 호스트의 절대 URL은 이 PR 범위 밖(기존과 같음, 수용한 한계).

### PR L 개정 이력

- PL-v1 (2026-10-28): 최초 작성.
- PL-v2 (2026-10-28, 리뷰 1라운드 — codex gpt-6.1-sol, ship): 실질 지적 없음. 수용 3건: ① 내부 링크 속성 순서는 `rel`, `href`가 되므로 순서 보장 문구를 삭제하고 시험을 속성 단위로 한다(PL-D4) ② 전·후처리를 `sanitizeOnce()` 안에 둔다(PL-D1) ③ 우회 시험(허용 `data-il` + 위험 `href`, 엔티티 입력, 중첩 `a`)을 PL-4에 추가. 기각 없음.

### 구현·검증 결과 — PR L (2026-10-28)

**Context / 확정 사항**: 본문(HTML)에 루트 상대 내부 링크(`/main/faq` 등)를 허용했다. 사용자 결정(PL-0): 보안 화이트리스트 변경 승인, 루트 상대만, 내부 링크는 같은 창(`rel` 유지). 마이그레이션·인가 정책 변경 없음. 구현 중 계획과 달라진 결정은 없다(리뷰 1라운드 ship 후 PL-v2 보정 3건만 반영).

**구현 파일**

| 파일 | 변경 |
|---|---|
| `common/web/PrivatePaths.java`(신규) | `SiteLinkPolicy.isPrivatePath` 본문을 그대로 옮김(규칙 불변) |
| `common/web/InternalLinkPaths.java`(신규) | `isAllowedBodyLink` — `SafeUrls.isSameOriginPath` ∧ 경로에 `%`·`;` 없음 ∧ 비공개 경로 아님 |
| `admin/site/SiteLinkPolicy.java` | `isPrivatePath`가 `PrivatePaths`에 위임 |
| `common/html/HtmlContentSanitizer.java` | `data-il` 표지로 Cleaner 우회(`markInternalLinks`·`restoreLinks`, `sanitizeOnce` 안), `target` 강제를 후처리로 이동 |
| 시험(신규) | `InternalLinkPathsTest`(41)·`HtmlContentSanitizerInternalLinkTest`(35) |
| 문서 | 루트 `CLAUDE.md` 콘텐츠 본문 항목, 이 문서, `plan/README.md` |

**검증 결과**

- 영향 범위 시험(`common.*`·`admin.site.*`·`admin.menu.*`·`*ConventionTest`, 이어서 `admin.board`·`page`·`popup`·`faq`·`contentimage`·`publicweb`) **실패 0**. 기존 `HtmlContentSanitizerTest.linkProtocols`(`/admin` 링크 `href` 제거)·`SiteLinkPolicyTest`(72)·`MenuServiceTest` 무변경 통과. 전체 `./gradlew test`는 PR 직전 1회(code-review-loop 마무리).
- **실측(설계 근거)**: jsoup 1.23.2는 `preserveRelativeLinks(true)`여도 상대 `href`를 지운다 → 표지 속성 방식 채택. Quill 2.0.3 `Link.sanitize`는 상대 경로를 통과시킨다(편집기 변경 없음, `//evil.test`도 편집기는 통과·서버가 제거).
- **실기(dev `bootRun`, Playwright)**: 정적 페이지를 API로 저장 → 내부 링크는 `href`+`rel`(target 없음), `/admin/…`·`//evil.test/x`는 `href` 제거, 외부 링크는 기존과 동일. 공개 `/main/pages/…` 렌더에서도 같음. 관리 화면 편집기에서 열어 새 내부 링크(`/main/boards/b1?page=2`)를 넣고 UI [저장] → 새 링크 보존·`//evil.test/y`는 제거. 검증용 페이지는 삭제해 원복(204·이후 404). 8080은 `cms-app-dev`를 멈추고 `bootRun`, 끝나고 다시 시작.

**이슈**: 없음(첫 실행부터 전부 통과). 후속: 없음 — 4a가 이 허용 위에서 본문 링크 치환·숫자 경로 거부를 다룬다.

## PR 4a 상세 설계 — 슬러그 완전 교체 (P4a-v3, 2026-10-28)

> §5 PR 4a 초안을 구체화한다. 이 절만 `plan-review-loop` 대상이다. 확정 결정(슬러그 전역 유일·`board-{id}` 기본값일 때만 1회 변경·옛 슬러그 301·한 번 쓴 슬러그 영구 예약·`board-{숫자}` 시스템 전용·메뉴/배너/팝업/본문 링크 치환·지금 열리는 링크를 치환 못 하면 fail-fast·id 해석은 공통 함수 하나)은 다시 열지 않는다. 마이그레이션은 현재 최대 V8 다음인 **V9(DDL)·V10(DML)**.

### P4a-0. 사용자 결정 (2026-10-28 — 재질문 금지)

- **P4a-v3 승인**(리뷰 3라운드 ship 후). 공개 URL 계약 변경·스키마 변경(`board.slug`)·저장된 링크 일괄 치환·사실상 롤백 불가를 고지받고 승인.
- **링크 치환은 Flyway Java 마이그레이션**(D6 ②, 이 프로젝트 첫 Java 마이그레이션).

### P4a-1. 정찰 결과 (코드 근거)

1. 공개 게시판 라우트는 `PublicBoardController` 세 개(목록 `/{boardId}`, 상세 `/{boardId}/posts/{postId}`, 첨부 GET·HEAD)이고 id는 `parseId` = `Long.parseLong`(음수도 통과 — 없는 id라 404)이다. 공개 조건은 `BoardRepository.findPublicById(id, siteId)` 한 곳.
2. 공개 링크를 만드는 곳: `public/board/list.html`(목록·검색·페이지·상세 링크 8곳), `public/board/detail.html`(목록·첨부 3곳), `public/home.html`(새 글 5건 — `PublishedPostRow.boardId`). 관리 화면에는 "공개 화면 보기" 링크가 없다. 배너·팝업 관리 화면의 링크 `placeholder="/boards/2 …"`가 옛 형식이다(사이트 접두어도 없음).
3. 링크 저장 검증: 메뉴는 DTO `@SafeMenuUrl`(형식) + 서비스 `SiteLinkPolicy.validate`(사이트 정책, 출력 시 `permits` 재검증). **배너·팝업은 DTO `@SafeLinkUrl`(`SafeUrls.isSafeLinkUrl`)만** 있고 서비스 정책이 없다. 셋 다 `%`를 허용한다(`SAME_ORIGIN_PATH_REGEX`는 공백·`\`·제어문자만 막음) — URL 컬럼에 인코딩 변형이 있을 수 있다.
4. 낙관적 검사 컬럼: 배너 `version`(1부터), 팝업 `revision`(0부터, 수정마다 +1 — "오늘 하루 보지 않기" 해제와 409 겸용), 정적 페이지 `version`, FAQ 항목 `version`. 메뉴·게시글은 없다.
5. 컬럼 길이: `menu.menu_url` 255, `banner.link_url`·`popup.link_url` 500. 본문은 전부 MEDIUMTEXT이고 도메인 상한은 `ContentBodyPolicy`(정리 후 바이트 — 게시글 200,000·정적 페이지 500,000·팝업 50,000·FAQ 30,000).
6. 게시판을 만드는 경로: 관리 API `createBoard` 하나. 그러나 **시험은 SQL `INSERT INTO board`(32곳)·`Board.builder()`(30곳)로 슬러그 없이 게시판을 만든다** — 롤백한 이전 앱이 만드는 행과 같은 모양이다. 공개 URL을 쓰는 시험은 약 10개 파일·90곳(`/main/boards/{id}`).
7. 사이트 시드: `admin`(id 1, ADMIN), `main`(id 2, PUBLIC). `SiteKey` 형식 `^[a-z0-9]+(-[a-z0-9]+)*$` 2~30자. 정적 페이지 슬러그 `SiteSlug`는 같은 정규식 2~50자.
8. 이 프로젝트에 Java 마이그레이션 선례는 없다. Flyway 위치는 기본값 `classpath:db/migration`(업그레이드 시험도 같은 위치) — 같은 이름의 Java 패키지 `db.migration`에 둔 `BaseJavaMigration` 구현이 함께 스캔된다. MariaDB DDL은 암묵 커밋이라 **fail-fast 롤백이 되려면 DML 마이그레이션을 DDL과 다른 버전으로 분리**해야 한다.
9. 고유 제약 위반 번역 선례: `GlobalApiExceptionHandler`가 루트 메시지의 제약 이름(`uk_member_user_id`)으로 구분한다.

### P4a-2. 설계 결정

**P4a-D1. 기본 슬러그는 저장하지 않는다 — `board.slug`는 "사용자가 정한 슬러그"만 (초안의 `board_slug` 테이블·백필 대체)**

- 선택지: ① 초안 추천 — `board_slug(slug PK, board_id, kind CURRENT|ALIAS|RETIRED)` 테이블 + 미삭제 게시판에 `board-{id}` 백필 ② `board.slug`·`board.previous_slug` 두 컬럼 + 백필 ③ **`board.slug VARCHAR(50) NULL UNIQUE` 한 컬럼에 사용자 지정 슬러그만 저장하고, 기본 슬러그 `board-{id}`는 id에서 계산한다.**
- **③ 채택.** 판정:
  - 현재 슬러그 = `slug ?? "board-" + id`. 변경 가능 = `slug IS NULL`(1회 변경 = NULL → 값, 이후 불변). 별도 `slug_customized` 컬럼이 필요 없다.
  - **별칭은 저장하지 않아도 정확히 하나로 정해진다**: 1회 변경은 언제나 기본값에서 출발하므로 옛 슬러그는 항상 `board-{id}`다. `/{siteKey}/boards/board-{id}/…`를 받으면 그 게시판이 공개 조건을 통과하고 `slug`가 있을 때 301.
  - **유일성(현재 ∪ 별칭)**: 기본 슬러그 이름 공간 `board-{숫자}`는 시스템 전용(확정, 사용자 지정 400)이라 사용자 슬러그와 겹칠 수 없고, 기본 슬러그끼리는 id가 유일하다. 사용자 슬러그끼리는 `UNIQUE(slug)`가 DB에서 강제한다(MariaDB UNIQUE는 NULL 중복 허용).
  - **영구 예약**: 소프트 삭제는 `slug`를 **비우지 않는다**(`board_key`·`site_id`를 비우는 것과 다름) — 삭제된 게시판이 UNIQUE를 계속 쥔다. 기본 슬러그는 id가 재사용되지 않으므로(게시판은 하드 삭제가 없다) 자동으로 영구다. 삭제된 게시판은 현재·별칭 모두 404(301 없음 — 공개 조건이 먼저).
  - **롤백·시험 호환**: 이전 앱이나 시험 SQL이 만든 게시판(`slug` NULL)은 그대로 `board-{id}`로 열린다 — 초안 리스크 "롤백 후 슬러그 없는 게시판"(후보 i~iii)이 **사라진다**. 백필 DML도 없다.
- 기각 이유: ①은 테이블·종류 상태 3개·백필·롤백 대응이 모두 필요하고, 1회 변경 규칙 아래에서는 별칭이 항상 계산 가능하다는 사실을 쓰지 않는다. ②는 백필과 두 컬럼 교차 유일성(UNIQUE로 표현 불가) 문제가 남는다.

**P4a-D2. 슬러그 형식·식별 규칙 — `admin.board.domain.BoardSlug` 한 곳**

- 사용자 지정 슬러그: `SiteSlug`와 같은 `^[a-z0-9]+(-[a-z0-9]+)*$`, **2~50자**(정적 페이지 슬러그와 같은 형식 — 연속·앞뒤 하이픈 금지), 숫자만으로 된 값 금지, `^board-[0-9]+$` 금지(시스템 전용). 위반은 400(입력 원문 비반영).
- 기본 슬러그 식별 `parseDefault(segment)`: `board-` 뒤를 공통 id 함수(P4a-D3)로 읽고 **`"board-" + id`와 원문이 정확히 같을 때만** id를 돌려준다(`board-012`·`board-+12`·전각 숫자는 기본 슬러그가 아니라 404 — 정규형 하나만 열린다).
- 공개 경로 세그먼트 해석은 "기본 슬러그 → 사용자 슬러그 형식 → 그 밖 404" 순서다. **형식 검사를 DB 조회보다 먼저** 한다 — 컬럼 정렬이 `utf8mb4_general_ci`라 `News`로 조회하면 `news`가 맞아 버린다(형식이 대문자를 막는다).

**P4a-D3. 공통 id 함수 — `common.web.PathIds.parse(String)`**

- `Long.parseLong`으로 읽고 음수면 null, 실패면 null(현 `parseId`와 비음수 구간에서 같은 의미 — `+12`·`0012`·전각 `１２`·아라비아 인도 숫자 `١٢`·혼합을 같은 값으로 연다. 음수는 지금도 없는 id라 404이므로 의미가 같다). 표로 고정하는 시험: `PathIdsTest`가 현 `Long.parseLong` 결과와 대조.
- 쓰는 곳: 공개 게시판 컨트롤러의 게시글·첨부 id(현 `parseId` 대체), 기본 슬러그 식별(D2), 숫자 게시판 경로 판정(D5), V10 치환(D6). 4b의 `/notices/{id}` 판정도 이 함수를 쓴다.

**P4a-D4. 공개 라우팅 — 세그먼트 해석 + 301**

- 라우트: `{boardId}` 경로 변수 이름을 `{boardSlug}`로 바꾼다(세그먼트 형태 동일 — `SecurityConfig` 매처·레이트리밋 패턴 `/*/boards/**`·`/*/boards/*/posts/*/attachments/*` 불변).
- `PublicBoardService.resolveBoard(siteId, segment)` → `Found(boardId)` / `Moved(slug)` / `NotFound`(읽기 전용 트랜잭션):
  - 기본 슬러그면 `findPublicById(id, siteId)` → 없으면 NotFound, `slug`가 있으면 **Moved**, 없으면 Found.
  - 사용자 슬러그 형식이면 새 `findPublicBySlug(slug, siteId)`(공개·미삭제·사이트 소속 조건을 `findPublicById`와 같게 쿼리에 고정) → Found / NotFound.
  - 그 밖(숫자만 `12`, 대문자, 형식 위반) NotFound — 숫자 id 경로는 이렇게 404가 된다(완전 교체).
- 기존 목록·상세·첨부 메서드는 id를 받는 그대로 두고 공개 조건을 다시 확인한다(해석과 조회가 다른 트랜잭션이라 사이의 비공개 전환은 기존과 같은 약한 보장 — 쿼리 1회 추가는 수용).
- **301 `Location`은 서버가 정규형으로 다시 만든다**(원문 경로를 잘라 붙이지 않는다): `/{현재 사이트 키}/boards/{새 슬러그}` + 상세는 `/posts/{PathIds 정규형}`, 첨부는 `…/attachments/{정규형}` + 원래 쿼리스트링(`getQueryString()` 원문, 있을 때만). 게시글·첨부 id가 `PathIds`로 읽히지 않으면 301 하지 않고 404(잘못된 주소를 다른 주소로 옮겨 주지 않는다). GET·HEAD 모두 301. 첨부 다운로드 집계·조회수 집계는 301 응답에서 일어나지 않는다(새 경로 요청에서 한 번).
- 비공개·삭제·다른 사이트 게시판의 기본 슬러그는 301 없이 같은 404(새 슬러그가 `Location`으로 새지 않는다).

**P4a-D5. 숫자 게시판 경로 금지 — 판정 함수 하나, 적용 세 곳**

- `common.web.BoardLinkPaths.isNumericBoardPath(String path)`: 루트 상대 경로(또는 메뉴의 자기 출처 절대 URL에서 떼어 낸 경로)의 **경로 부분(`?`·`#` 앞)을 퍼센트 디코딩**(`UriUtils.decode`, 디코딩 실패 = 해당 없음)하고 **점 세그먼트를 브라우저처럼 정리**(`.`은 지우고 `..`은 앞 세그먼트를 지운다 — URL 표준 경로 처리. 디코딩 뒤라 `%2e`·`%2E`도 같다, v3 R2-1)한 뒤 `/`로 나눠 `["", 아무 세그먼트, "boards", s, …]`이고 `PathIds.parse(s) != null`이면 참. 사이트 키 자리는 존재 여부와 무관(형태만 본다 — 저장 시 판정이 사이트 상태에 따라 흔들리지 않게).
- 적용:
  1. **본문**: `InternalLinkPaths.isAllowedBodyLink`가 거부(→ sanitizer가 `href`만 제거, 저장·출력 모두). PR L 규칙상 본문 경로에는 `%`가 없어 디코딩은 영향이 없다.
  2. **배너·팝업 링크**: `SafeLinkUrlValidator`가 거부(DTO 400). 숫자 경로일 때의 문구는 "게시판 주소는 슬러그 주소(예: /main/boards/board-12)를 쓰세요."(`buildConstraintViolationWithTemplate`로 경우별 문구 — 기존 사용처가 없으면 구현 때 확인).
  3. **메뉴 링크**: `SiteLinkPolicy.violation`이 내부 경로와 자기 출처 절대 URL의 경로에 대해 거부(서비스 400) — 출력 재검증 `permits`도 같은 규칙이라 남은 숫자 메뉴는 링크 없는 라벨이 된다(어차피 404).
- `SafeUrls.isSafeLinkUrl` 자체는 바꾸지 않는다(공개 배너 출력 `PublicBanner`·통합 검색이 쓰는 형식 판정이다 — 숫자 배너 링크는 출력에서 숨기지 않고 404로 둔다).
- **마이그레이션 뒤 남은 숫자 경로 행의 저장**(초안 미결정): 그 URL을 그대로 다시 보내는 저장은 위 400이다(배너·팝업 PUT은 전체 교체라 링크를 고치거나 비워야 저장된다). 409가 아니라 400인 이유: 낡은 화면이 아니라 고쳐야 하는 값이고, 화면이 안내 문구를 보여 준다. 메뉴 PATCH에서 `menuUrl`을 보내지 않으면 검증하지 않는다(기존 의미 유지).

**P4a-D6. 링크 치환 — Flyway Java 마이그레이션 `V10__replace_board_links_with_slugs` (핵심 결정, 사용자 승인 대상)**

- 선택지: ① SQL `REGEXP_REPLACE` ② **Java 마이그레이션(`src/main/java/db/migration/V10__….java`, `BaseJavaMigration`)**.
- **② 추천.** 이유:
  - D1 덕분에 치환 값에 조인이 필요 없어 ①도 정규형 id(`/main/boards/12` → `/main/boards/board-12`)는 할 수 있다. 그러나 **비정규 id(`+12`·`0012`·전각·아라비아 인도 숫자)는 지금 열리는 링크**인데 SQL로는 `Character.digit` 해석을 재현할 수 없어 fail-fast로만 다룰 수 있고, 그 검출 자체(SIGNAL·저장 프로시저)도 Flyway SQL 스크립트로 쓰기 까다롭다. ②는 런타임과 **같은 함수(`PathIds`·`BoardLinkPaths`)**로 판정하고 비정규 id도 정규형으로 치환한다(fail-fast 대상이 길이 초과·인코딩 변형만 남는다).
  - 길이·본문 상한(UTF-8 바이트)·HTML 속성 경계를 코드로 정확히 검사하고, 실패 시 어떤 테이블의 어떤 행인지 메시지로 알려 줄 수 있다.
  - 비용: 이 프로젝트의 첫 Java 마이그레이션이다. 마이그레이션이 앱 코드(공통 함수)를 부르므로 **그 함수를 나중에 바꾸면 아직 V10을 적용하지 않은 DB의 결과가 바뀐다** — 클래스 주석과 `docs/migration-guide.md`에 "V10이 쓰는 함수 목록, 바꿀 때 V10 업그레이드 시험을 함께 본다"를 남긴다. 엔티티·리포지토리·Spring 빈은 쓰지 않는다(JDBC만 — 엔티티는 이후 PR에서 바뀐다). Java 마이그레이션은 Flyway 체크섬이 없다(수정 감지 없음 — 머지 후 수정 금지 규칙은 같다). 첫 실배포 직전 합치기 때 빈 DB에는 치환할 데이터가 없으므로 V10은 흡수 대상에서 **삭제**한다.
- 트랜잭션: Flyway 기본(마이그레이션 하나 = 트랜잭션 하나, InnoDB DML) — 실패하면 V10 전체가 롤백되고 `flyway_schema_history`에 성공 기록이 남지 않아 이전 버전 앱이 계속 운영된다. V9(DDL)는 이미 적용된 상태로 남지만 `slug` 컬럼은 이전 앱이 모르는 NULL 허용 컬럼이라 무해하다.
- 대상(전부 키 순으로 읽고 바뀐 행만 UPDATE — **치환 후보만** `LIKE '%/boards/%'`로 줄인다. 사후 검사 후보는 따로다, 아래 fail-fast ②). 키는 `id`이고 **정적 페이지 개정만 복합 키 `(page_id, revision_no)`**(`id` 컬럼이 없다 — 이 키 순으로 읽고 두 키 모두로 UPDATE, 오류 식별자도 `site_page_revision#{page_id}/{revision_no}`, v2 R1-3):

  | 대상 | 컬럼 | 상한(초과 = fail-fast) | 함께 바꾸는 것 |
  |---|---|---|---|
  | 공개 사이트 메뉴 | `menu.menu_url`(`site_id`가 `site_type='PUBLIC'`인 행만) | 255자 | 없음(버전 없음) |
  | 배너 | `banner.link_url` | 500자 | `version + 1` |
  | 팝업 | `popup.link_url`, `popup.content_html` | 500자 / 정리 후 50,000바이트 | 행당 `revision + 1` 한 번(둘 다 바뀌어도 1) |
  | 게시글 | `post.content`(삭제된 글 포함) | 200,000바이트 | 없음 |
  | 정적 페이지 | `site_page_revision.content`(모든 개정) | 500,000바이트 | 없음(`version`·개정 번호 불변 — 초안 결정) |
  | FAQ | `faq.answer_html` | 30,000바이트 | 없음 |

- **치환 규칙**:
  - URL 컬럼: 값이 `/{k}/boards/{s}`로 시작하고(`k` = 공개 사이트 키 중 하나, 사용 여부 무관) `s` 뒤가 값의 끝·`/`·`?`·`#`이며 `PathIds.parse(s) = n`이면 `s`를 `board-{n}`으로 바꾼다. 나머지(하위 경로·쿼리·프래그먼트)는 그대로.
  - HTML 본문: `<a` 태그의 `href="…"` 속성 값에만 같은 규칙(값의 시작 고정). 저장된 본문은 sanitizer 출력이라 태그 이름은 소문자 `a`, 속성은 큰따옴표, 값 안의 `"`는 `&quot;`이다 — 이 가정을 시험으로 고정한다(임의 본문을 sanitizer에 통과시킨 결과에 치환 → 다시 sanitizer = 같은 결과). 태그 경계는 큰따옴표 값을 건너뛰는 정규식(`<a(?:\s+[^\s=>"]+(?:="[^"]*")?)*\s*/?>`)으로 찾는다. 본문 텍스트·`img src`·다른 속성은 바꾸지 않는다(초안 미결정 "HTML 치환 대상" → **`a href`만**).
  - **게시판 존재 여부는 보지 않는다**(초안 "삭제된 게시판은 치환하지 않음"을 바꿈): 없는·삭제된·다른 사이트 게시판 id의 링크는 치환 전에도 404, 치환 후 `board-{n}`도 404다. 게시판 id는 재사용되지 않으므로 의미가 같고, 치환하지 않고 남기면 그 행이 D5 규칙(저장 400·본문 `href` 제거)에 걸려 편집을 방해한다.
  - 공개 사이트 키가 아닌 첫 세그먼트(`/admin/boards/12`, 없는 사이트 `/old/boards/12`)는 치환하지 않는다(초안 결정 유지 — 지금 열리지 않는 링크).
- **fail-fast**(전체 롤백 + 메시지에 `테이블#id` 최대 20개):
  1. 치환 결과가 위 상한을 넘는 행.
  2. **치환 후 검사**: URL 컬럼 값의 경로를 디코딩·점 세그먼트 정리해(D5 함수 — 정리하면 숫자 게시판 경로가 되는 `/main/boards/./12`·`/main/x/../boards/12`도 걸린다, v3 R2-1) 공개 사이트 키 + 숫자 게시판 경로가 남아 있으면 실패 — 인코딩 변형(`/main/boards/%31%32`, `/main/%62oards/12`, `/main/%62%6F%61%72%64%73/12`)이 여기 걸린다. **검사 대상은 치환 후보 필터를 쓰지 않고 URL 컬럼의 값이 있는 모든 행**이다(v2 R1-1 — `boards`를 인코딩하면 `LIKE '%/boards/%'`에 걸리지 않아 검사가 호출되지 않는다. 메뉴·배너·팝업은 작은 테이블이라 전체 검사가 싸다). 본문 `href`는 PR L이 경로의 `%`를 막아 인코딩 변형이 저장될 수 없으므로 치환 후보(`/boards/` 포함 행)만 같은 검사를 한다.
- `docs/deployment.md`에 **배포 전 점검 SQL**(이전 버전 DB에서): URL 컬럼 중 **`%`가 든 모든 행**(`menu_url`·`link_url` `LIKE '%\%%'` — `boards` 인코딩까지 잡도록 `/boards/` 조건을 붙이지 않는다, v2 R1-1)과 **점 세그먼트가 든 모든 행**(`LIKE '%/.%'`, v3 R2-1), 길이 여유(링크 하나당 +6자 — `CHAR_LENGTH(menu_url) > 249` 등). 본문 상한은 마이그레이션이 정확히 판정하고 메시지로 알려 준다. 실배포 전이라 실제로는 0건일 가능성이 높다.
- **V10 실패 후 복구 절차**(v2 R1-2): 업무 데이터는 트랜잭션 롤백으로 그대로지만 **Flyway는 MariaDB에서 실패 이력(`success=0`)을 남겨 다음 `migrate`를 막는다**. 절차는 기존 `docs/migration-guide.md` "DDL 실패 복구"와 같다 — 실패 메시지의 행을 이전 버전 앱 화면(또는 SQL)으로 고친다 → 같은 마이그레이션 구성으로 `flyway repair`(실패 이력만 정리) → 재기동. 이 순서를 `docs/deployment.md`·`migration-guide.md`의 V10 항목에 적는다.

**P4a-D7. 관리 API·화면**

- `POST /admin/api/boards`: 선택 필드 `slug`(누락·null·공백만 = 기본 슬러그). `PATCH /admin/api/boards/{id}`: 선택 필드 `slug`(누락·null = 유지). 규칙: 형식 400(잠금 전) → 이미 사용자 슬러그가 있는데 다른 값 400("슬러그는 한 번만 바꿀 수 있습니다.") → 같은 값이면 변화 없음(멱등, "변경할 필드 없음" 판정에서는 필드로 센다 — `version`은 기존대로 +1) → 사전 검사 `existsBySlug` 409 → flush 중 `uk_board_slug` 위반도 같은 409(동시 지정). 공지 게시판(시스템 게시판)도 바꿀 수 있다(4b가 현재 슬러그로 302).
- `Board.assignSlug(slug, now)`: `slug`가 NULL일 때만, `update` 뒤에만 불러 버전을 한 번만 올린다(`changeSite`와 같은 방식). 생성은 빌더에 넣는다.
- `updateBoard`·`createBoard`는 `saveAndFlush`/`flush`를 `try` 안에서 해 제약 이름으로 409 문구를 고른다(`uk_board_board_key` = 공지 중복, `uk_board_slug` = 슬러그 중복).
- 응답 `BoardResponse`: `slug`(현재 슬러그 — 항상 있음), `slugChangeable`(사용자 슬러그가 없으면 true). 감사는 기존 `BOARD_CREATE`·`BOARD_UPDATE` 그대로.
- 화면 `admin/board/manage.html`: 목록에 "주소" 열(현재 슬러그), 모달 기본 묶음에 "주소(슬러그)" 입력 — 생성 시 선택(비우면 `board-{번호}`), 수정 시 `slugChangeable`일 때만 편집 가능하고 아니면 읽기 전용 + 안내("한 번 바꾼 주소는 다시 바꿀 수 없습니다. 이전 주소 board-N은 새 주소로 자동 이동합니다"). 입력값을 그대로 둔 수정은 `slug`를 보내지 않는다.
- 배너·팝업 화면 `placeholder`를 `/main/boards/board-2 또는 https://example.com`으로 고친다.

**P4a-D8. 공개 링크 생성**

- `Board.publicSlug()`(= `BoardSlug.effective(id, slug)`)를 DTO에 싣는다: `PublicBoardListResult.boardSlug`, `PublicPostDetail.boardSlug`, `PublishedPostRow`/`PublicHomePost.boardSlug`(QueryDSL 프로젝션에 `board.slug`를 더하고 DTO에서 계산). 템플릿의 `{id}` 자리를 슬러그로 바꾼다(`list.html`·`detail.html`·`home.html`). 공지 템플릿(`public/notice/*`)과 공개 메인 공지 5건 링크는 `/notices/…` 그대로(4b 범위).

### P4a-3. 변경 파일

| 파일 | 변경 |
|---|---|
| `db/migration/V9__board_slug.sql`(신규) | `board.slug varchar(50) NULL` + `UNIQUE uk_board_slug`(IF NOT EXISTS) |
| `src/main/java/db/migration/V10__replace_board_links_with_slugs.java`(신규) | D6 치환·fail-fast(JDBC) |
| `common/web/PathIds.java`·`BoardLinkPaths.java`(신규) | D3·D5 |
| `common/web/InternalLinkPaths.java` | 숫자 게시판 경로 거부 |
| `common/web/validation/SafeLinkUrlValidator.java` | 숫자 게시판 경로 거부 |
| `admin/site/SiteLinkPolicy.java` | 숫자 게시판 경로 거부 |
| `admin/board/domain/BoardSlug.java`(신규)·`Board.java` | 형식·식별·`slug` 필드·`assignSlug`·`publicSlug` |
| `admin/board/repository/BoardRepository.java`·`PostRepositoryImpl`·`PublishedPostRow` | `findPublicBySlug`·`existsBySlug`, 프로젝션 |
| `admin/board/service/BoardService.java`·`dto/request/Board*Request`·`BoardResponse` | D7 |
| `publicweb/board/controller/PublicBoardController.java`·`service/PublicBoardService.java`·`dto/*` | D4·D8 |
| `publicweb/home/dto/PublicHomePost.java` | D8 |
| 템플릿 | `public/board/list.html`·`detail.html`·`public/home.html`·`admin/board/manage.html`·`admin/banner/manage.html`·`admin/popup/manage.html` |
| 시험 | 아래 P4a-4, 공개 URL을 쓰는 기존 시험 약 10개 파일의 `/main/boards/{id}` → `/main/boards/board-{id}` 기계적 변경 |
| 문서 | `admin/board/CLAUDE.md`·`publicweb/board/CLAUDE.md`·`publicweb/support/CLAUDE.md`(링크 형식)·`publicweb/home/CLAUDE.md`·루트 `CLAUDE.md`(콘텐츠 본문 링크 규칙)·`docs/deployment.md`(점검 SQL·롤백 불가)·`docs/migration-guide.md`(첫 Java 마이그레이션·개정 본문 변경 사실·합치기 때 V10 삭제) |

### P4a-4. 시험 계획

- `PathIdsTest`: `12`·`+12`·`0012`·`１２`·`١٢`·혼합·`-1`·`9223372036854775808`·빈 값·`1a`를 `Long.parseLong`과 대조(비음수 구간 같음, 음수·초과·실패 null).
- `BoardSlugTest`: 형식 허용·거부 표(2자·50자·51자·숫자만·`board-12`·`board-a`(허용)·대문자·연속/앞뒤 하이픈), `parseDefault`(`board-12` → 12, `board-012`·`board-+12`·`board-１２`·`board-` → null).
- `BoardLinkPathsTest`: `/main/boards/12`·`/x/boards/+12/posts/3?a#b`·`/main/boards/%31%32`·`/main/%62oards/12`(참), `/main/boards/board-12`·`/main/boards/news`·`/main/boards`·`/boards/12`(사이트 세그먼트 없음 — 거짓, 지금도 404)·`/main/boards/-1`(거짓)·잘못된 퍼센트(`%zz`, 거짓), **점 세그먼트 `/main/boards/./12`·`/main/x/../boards/12`·`/main/boards/%2e/12`·`/main/boards/12/.`(참)·`/main/boards/../boards/board-3`(거짓)**(v3 R2-1 — 배너·팝업 저장 400 시험과 V10 fail-fast + 전체 롤백 시험에도 같은 세 사례).
- 적용 세 곳: sanitizer(`href` 제거 + 멱등), 배너·팝업 API 400 + 문구, 메뉴 API 400 + `permits` false.
- 공개 라우팅(`PublicBoardSlugIntegrationTest`, `publicweb.board`): 기본 슬러그 목록·상세·첨부 200, 숫자 id 경로 404(목록·상세·첨부 GET·HEAD), 사용자 슬러그 200, 변경 후 기본 슬러그 → 301 `Location`(목록 쿼리스트링 유지, 상세·첨부 정규형 id, HEAD도 301), 비정규 게시글 id(`+5`)의 301 대상은 `posts/5`, 읽을 수 없는 게시글 id는 301 없이 404, 비공개·삭제·다른 사이트 게시판의 기본 슬러그 404(새 슬러그 미노출), 대문자 슬러그 404, `board-012` 404, 공개 목록·상세·메인 템플릿 링크가 현재 슬러그를 쓴다, 301 응답은 조회수·다운로드 수를 올리지 않는다.
- 관리 API(`BoardSlugApiIntegrationTest`, `admin.board`): 생성 지정·생략 왕복, 1회 변경 후 두 번째 변경 400·같은 값 PATCH 200, 형식·숫자만·`board-7` 400, 다른 게시판의 슬러그 409, **삭제된 게시판의 슬러그로 생성 409 + 그 경로 404**, 동시 지정 하나만 성공(UNIQUE → 409), 공지 게시판 변경 가능, 감사 행.
- V10 업그레이드 시험(`BoardSlugLinkUpgradeTest`, 루트 패키지 — V9까지 적용한 DB에 행을 넣고 V10): §5 PR 4a "업그레이드 경로 시험"의 모든 사례(목록·상세·첨부·쿼리·프래그먼트·`12` vs `123`·삭제/없는 게시판(치환됨 — D6 변경)·관리자 사이트 메뉴·외부 호스트·`//host`·쿼리 매개변수 안·본문 텍스트·없는 사이트 키·비정규 id(정규형으로 치환)·`long` 초과·길이 정확히 맞음(치환)/1 초과(fail-fast)·인코딩 변형(fail-fast — `%31%32`, `%62oards`, `boards` 전체 인코딩 각각 V10 자체가 실패하는지)), **정적 페이지는 같은 페이지의 여러 개정 + 다른 페이지의 같은 개정 번호**를 넣어 모든 개정이 독립적으로 치환되는지(v2 R1-3), fail-fast 시험은 **실패 → 업무 데이터 그대로(롤백) → `flyway_schema_history`의 V10 `success=0` 확인 → 원인 행 수정 → `repair()` → 재실행 성공 + 치환 결과 확인**까지(v2 R1-2, `ProfileImageUrlShrinkUpgradeTest` 선례), 배너 `version`·팝업 `revision`이 치환된 행만 +1(팝업은 링크·본문 둘 다 바뀌어도 +1), 치환된 본문의 sanitizer 멱등.
- V9: `slug` 컬럼·UNIQUE 생성, 재실행 무해(IF NOT EXISTS).
- 기존 시험: 공개 URL 기계적 변경 후 전부 통과, `PublicExposureInventoryTest`·`SitePublicRateLimitConventionTest`·`SecurityConfigTest` 무변화 통과(매처·패턴 불변 확인).

### P4a-5. 리스크

- **공개 URL 계약 변경(승인 대상)**: 숫자 id 게시판 주소가 전부 404. 저장된 링크는 치환하지만 외부 공유·검색 색인은 깨진다(실배포 전이라 수용, §8).
- **사실상 롤백 불가**: 이전 앱은 `board-12`를 숫자로 읽지 못해 치환된 링크가 전부 404 — 롤백하려면 V10 이전 백업 복구(`docs/deployment.md`). 롤백 중 만든 게시판은 D1 덕분에 재배포 후 바로 `board-{id}`로 열리지만, 롤백 중 저장한 숫자 링크는 V10이 다시 돌지 않으므로 남는다 — 재배포 뒤 점검 SQL로 찾고 화면에서 고친다(저장 시 400 안내).
- **사용자 작성 데이터 일괄 수정**: 마이그레이션 전 백업 필수, 경계 사례는 업그레이드 시험으로 고정, 정적 페이지 개정 본문이 바뀐다는 사실을 migration-guide에 기록.
- **첫 Java 마이그레이션**: 앱 코드 함수 의존(D6) — 변경 시 V10 시험이 감지.
- **팝업 `revision` +1의 부작용**: 링크가 치환된 팝업은 방문자의 "오늘 하루 보지 않기"가 풀린다(1회, 수용).

### PR 4a 개정 이력

- P4a-v1 (2026-10-28): 최초 작성. 초안 대비 바꾼 것 — 별칭 테이블·백필 대신 사용자 지정 슬러그만 저장(D1), 삭제·없는 게시판 링크도 치환(D6), Java 마이그레이션 추천(D6).
- P4a-v2 변경 (리뷰 1라운드 — codex gpt-6.1-sol, needs-attention 3건 전부 수용, D1 접근은 리뷰어도 유지 판정): R1-1 URL 컬럼 사후 검사는 치환 후보 필터(`LIKE '%/boards/%'`)를 쓰지 않고 값이 있는 모든 행 — `boards`를 인코딩하면 필터에 안 걸려 fail-fast가 호출되지 않는다. 점검 SQL도 `%` 포함 행 전체(D6). R1-2 MariaDB에서 실패한 V10은 `success=0` 이력을 남겨 다음 기동을 막는다 → 복구 절차(수정 → `flyway repair` → 재기동)를 문서화하고 업그레이드 시험을 repair 후 재실행 성공까지 확장(D6·P4a-4). R1-3 `site_page_revision`은 `id`가 없고 복합 키 `(page_id, revision_no)` — 그 키로 읽고 갱신·보고, 시험에 같은 페이지 여러 개정 + 다른 페이지 같은 개정 번호(D6·P4a-4). 기각 없음.
- P4a-v3 변경 (리뷰 2라운드 — codex, needs-attention 1건 수용, v2 반영은 충분 판정): R2-1 브라우저는 점 세그먼트(`/main/boards/./12`, `/main/x/../boards/12`, `/main/boards/%2e/12`)를 `/main/boards/12`로 정리해 지금 열리는데, 배너·팝업은 `PrivatePaths`를 거치지 않아 저장 거부·V10 사후 검사를 모두 빠져나간다 → `BoardLinkPaths`가 디코딩 뒤 점 세그먼트를 정리하고 판정(저장 400·V10 fail-fast가 같은 함수), 원문 치환 범위는 그대로(정규형만), 점검 SQL에 `LIKE '%/.%'`, 세 사례를 판정·저장·업그레이드 시험에 추가(D5·D6·P4a-4). 기각 없음.

### 구현·검증 결과 — PR 4a (2026-10-28)

**Context / 확정 사항**: 공개 게시판 주소를 슬러그로 완전히 교체했다(`/{siteKey}/boards/{슬러그}`, 숫자 id 404). 사용자 결정(P4a-0): P4a-v3 승인, 링크 치환은 Flyway Java 마이그레이션. 핵심은 D1 — 기본 슬러그 `board-{id}`를 저장하지 않아 별칭 테이블·백필·롤백 후 NULL 대응이 필요 없다.

**구현 중 계획과 달라진 결정**(모두 구현 중 보고)
- 슬러그 중복 409를 `ConflictException`이 아니라 **`DuplicateResourceException`(코드 `DUPLICATE_RESOURCE`)**으로 냈다 — 관리 화면이 409를 받으면 "낡은 화면"으로 보고 모달을 닫는데, 슬러그 중복은 다른 주소를 고르면 되는 입력 오류라 코드로 구분해야 했다(`RESOURCE_CONFLICT` = 낡은 버전).
- V10의 본문 상한은 `ContentBodyPolicy` 상수를 부르지 않고 **마이그레이션 시점 값으로 고정**했다 — 일회성 마이그레이션의 판정이 나중의 상한 변경에 흔들리지 않게. 계획이 공유를 요구한 것은 id 해석·경로 판정(`PathIds`·`BoardLinkPaths`)이며 그것은 공유한다.
- PATCH에 지금 값(기본 슬러그 `board-{id}` 포함)을 그대로 보내면 무변화 200이다(D7 "같은 값 = 무변화"를 기본 슬러그에도 적용 — 형식 검사는 기본 슬러그 형태일 때 잠근 뒤 "지금 값인가"로 판정). 다른 기본 슬러그 형태(`board-7`)·되돌리기는 400.

**구현 파일**

| 파일 | 변경 |
|---|---|
| `db/migration/V9__board_slug.sql`(신규) | `board.slug varchar(50) NULL` + `UNIQUE uk_board_slug` |
| `src/main/java/db/migration/V10__replace_board_links_with_slugs.java`(신규) | 첫 Java 마이그레이션 — 메뉴·배너(+version)·팝업(+revision 행당 1)·게시글·정적 페이지 모든 개정(복합 키)·FAQ 치환, fail-fast(길이·본문 상한·인코딩/점 세그먼트 잔여, URL 컬럼 사후 검사는 전 행) |
| `common/web/PathIds.java`·`BoardLinkPaths.java`(신규) | id 해석 한 벌, 숫자 게시판 경로 판정(세그먼트별 디코딩 + 점 세그먼트 정리) |
| `common/web/InternalLinkPaths.java`·`common/web/validation/SafeLinkUrlValidator.java`·`admin/site/SiteLinkPolicy.java` | 숫자 게시판 경로 거부(본문 `href` 제거·배너/팝업 400 안내 문구·메뉴 400 + 출력 재검증) |
| `admin/board/domain/BoardSlug.java`(신규)·`Board.java` | 형식·기본 슬러그 식별·`slug`·`assignSlug`·`publicSlug`·`isSlugChangeable`, 소프트 삭제는 슬러그 유지 |
| `admin/board/repository/BoardRepository.java`·`PostRepositoryImpl.java`·`PublishedPostRow.java` | `findPublicBySlug`·`existsBySlug`, 메인 새 글 프로젝션에 `board.slug` |
| `admin/board/service/BoardService.java`·`dto/request/Board{Create,Update}Request.java`·`dto/response/BoardResponse.java` | 생성·1회 변경·형식 400·중복 409(사전 검사 + flush 중 제약 이름 판정) |
| `publicweb/board/controller/PublicBoardController.java`·`service/PublicBoardService.java`·`dto/PublicBoardRoute.java`(신규)·`PublicBoardListResult`·`PublicPostDetail`, `publicweb/home/dto/PublicHomePost.java` | 세그먼트 해석·301(정규형 Location)·링크용 `boardSlug` |
| 템플릿 | `public/board/list.html`·`detail.html`·`public/home.html`(슬러그 링크), `admin/board/manage.html`(주소 열·입력·409 코드 구분), `admin/banner`·`admin/popup/manage.html`(placeholder) |
| 시험(신규) | `PathIdsTest`·`BoardLinkPathsTest`·`BoardSlugTest`·`BoardSlugApiIntegrationTest`·`PublicBoardSlugIntegrationTest`·`BoardSlugLinkUpgradeTest` |
| 시험(수정) | 공개 URL을 쓰는 10개 파일 `/{site}/boards/{id}` → `board-{id}`(기계적), `PublicSiteMenuIntegrationTest`(메뉴 픽스처를 슬러그 링크로), 공지 시험 2개(DTO 생성자), `SafeLinkUrlValidatorTest`·`SiteLinkPolicyTest`·`HtmlContentSanitizerInternalLinkTest`(숫자 경로 거부 표 추가) |
| 문서 | `docs/migration-guide.md`(V9·V10·Java 마이그레이션 규칙), `docs/deployment.md` "게시판 슬러그"(점검 SQL·실패 복구·롤백 불가), `admin/board`·`publicweb/board`·`publicweb/support`·`publicweb/home`·루트 `CLAUDE.md`, 이 문서, `plan/README.md` |

**검증 결과**
- 영향 범위 시험(`admin.board.*`·`publicweb.*`·`common.*`·`SiteLinkPolicyTest`·`SecurityConfigTest`·`*ConventionTest`·`BaselineSchemaTest`·`BoardSlugLinkUpgradeTest`) 1,340건 **실패 0**(아래 이슈 정리 후). 그 전에 돈 전체 `./gradlew test`(새 시험 작성 전) 2,717건 중 실패 3건은 모두 시험 픽스처 문제였다(이슈). 전체 시험은 PR 직전 1회(code-review-loop 마무리).
- `SecurityConfigTest`·`PublicExposureInventoryTest`·`SitePublicRateLimitConventionTest`는 **무변경 통과** — 매처·레이트리밋 패턴·공개 인벤토리가 바뀌지 않았음을 확인.
- **변이 실험**: V10 URL 컬럼 사후 검사를 끄면 `BoardSlugLinkUpgradeTest` 6건 실패, `resolveBoard`의 301 분기를 없애면 `PublicBoardSlugIntegrationTest` 1건 실패(둘 다 원복).
- **실기(dev `bootRun`, Playwright)**: 기동 전 dev DB(V8)에 숫자 링크가 든 메뉴·배너·게시글 본문을 넣고 띄우자 **실제 V9·V10이 적용**되어 셋 다 `board-N`으로 바뀌고 배너 `version`이 1→2(0.15초). 관리 화면에서 슬러그 없이 게시판 생성(목록 "주소" `board-6`) → 글 저장 시 본문의 `/main/boards/6` `href`만 제거·`/main/boards/board-6` 유지 → 공개 목록·상세 링크가 슬러그, `/main/boards/6`·`…/posts/11`·`board-06` 404 → 모달에서 `Bad` 저장은 모달 안 오류(유지), `verify-4a` 저장 → 재오픈 시 읽기 전용 + 안내 → `board-6` 목록(쿼리 유지)·상세(`011` → `11` 정규화)·HEAD 모두 301, `posts/x` 404, `VERIFY-4A` 404 → 같은 슬러그로 새 게시판 생성은 모달 안 중복 안내 → 배너 PUT `/main/boards/5`·`%35` 400(안내 문구), `board-5` 200 → 게시판을 비공개로 바꾸면 옛·새 주소 모두 404이고 `Location` 없음 → 공개 메인 새 글 링크가 슬러그. 검증 행(게시판·글·메뉴·배너·감사)은 지워 원복했다(dev DB는 V10 상태로 남음). 스크린샷 `adversarial-review/plan/screenshots/pr4a-0{1,2,3}-*.png`(커밋 제외).

**이슈**
- 공개 URL 일괄 치환(`sed`)이 `SecurityConfigTest`의 **스텁 컨트롤러 매핑 패턴**(`"/main/boards/{boardId}"`)까지 바꿔 요청 URL과 어긋났다(404 2건) — 스텁 6줄만 되돌려 이 파일은 무변경이 됐다. 다른 파일의 `@…Mapping`에는 같은 피해가 없음을 검색으로 확인.
- `PublicSiteMenuIntegrationTest`가 숫자 게시판 메뉴 링크를 픽스처로 써서 새 출력 재검증이 의도대로 숨겼다(1건) — 픽스처를 슬러그 링크로 바꿈.
- MockMvc URI 템플릿은 `%`를 한 번 더 인코딩한다(`%ED` → `%25ED`) — 쿼리 유지 시험은 `URI`를 직접 넘긴다(서버 동작 문제 아님).

**후속**: 없음 — 4b(`/notices` 흡수)가 같은 `PathIds`와 현재 슬러그(302 대상)를 쓴다.
