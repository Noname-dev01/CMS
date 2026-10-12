# PLAN-media-library — 미디어 라이브러리 (로드맵 Top 8 ⑧)

- 상태: **v5.1 (2026-10-12) — 적대적 리뷰 5라운드 ship, 사용자 승인(2026-10-12), 구현 완료·머지(PR #136 `e03da10`, 2026-10-12)**
- 브랜치(예정): `feat/media-library`
- 선행: ⓪ 편집기·본문 이미지, ② 배너, ⑦ 팝업 (모두 머지 완료)

## Context

이미지를 올리는 곳이 셋으로 갈라져 있다. 같은 일을 하는 코드가 세 벌이고 서로의 파일을 볼 수 없다.

| 위치 | 테이블 | 파일 위치 | 한도 | 공개 경로 | 삭제 |
|---|---|---|---|---|---|
| 편집기 본문 | `content_image` + `content_image_ref` + `content_image_usage`(카운터) | 스토리지 **루트** | 5MB·4096px·4096², 전체 1GB·1만 개 | `/content-images/{id}` (공개 게시글 참조 ∧ **같은 게시판 출처**) | 없음(수동 회수 ②) |
| 배너 | `banner`(`storage_key` UNIQUE) | `banner/` 네임스페이스 | 2MB·2560px·400만 px, 10개 | `/banners/{id}/image` (노출 재검증) | 배너 삭제 시 커밋 후 파일 삭제 |
| 팝업(이미지형) | `popup`(`storage_key`) | `popup/` 네임스페이스 | 위와 같은 값 | `/popups/{id}/image` | 팝업 삭제 시 커밋 후 파일 삭제 |

목표: 관리자가 올린 이미지를 한 화면에서 목록·검색·미리보기하고, 배너·팝업·편집기에서 이미 올린 이미지를 다시 고르며, 사용 중인 파일은 삭제를 막고 사용처를 보여준다. 로드맵이 사용자와 확정한 결정(2026-10-07): 이관 범위는 배너·팝업·본문 이미지만(공지·게시글 **첨부**, 프로필 이미지 제외) / 사용처 추적은 참조 테이블 / 공개는 공개 콘텐츠가 참조하는 파일만 / 이미지만(png·jpeg·gif, 매직 바이트)·파일당 5MB·전체 용량 상한.

## 정찰에서 발견한 핵심 쟁점

1. **본문 이미지는 게시판 출처로 격리돼 있다**(`content_image.scope_type/scope_id`, `replaceRefs`가 다른 출처 이미지를 400으로 거부, `existsPublishedPostRef`가 `i.scopeId = p.boardId`를 요구). 비공개 게시판에 쓰기 권한만 가진 사람이 다른 게시판의 비공개 이미지를 자기 공개 글에 넣어 익명 공개하는 경로를 막으려는 장치다(PLAN-board.md 쟁점 9). 라이브러리의 "어디서나 다시 고르기"와 정면으로 충돌한다.
2. **배너·팝업 이미지는 카운터 밖에 있다.** `content_image_usage`(1GB·1만 개)는 본문 이미지만 센다. "라이브러리 전체 용량 상한"이 의미를 가지려면 이관 시 기존 바이트를 카운터에 합산하고 이후 배너·팝업 업로드도 카운터를 잡아야 한다 → **잠금 순서가 늘어난다**(`banner_lock` → `content_image_usage` → 행).
3. **`banner.storage_key`는 UNIQUE**다. 한 이미지를 두 배너가 고르면 충돌한다 → 새 행은 이 컬럼을 쓰지 않으므로(NULL) 충돌하지 않는다(D4).
4. **한도가 용도별로 다르다.** 라이브러리 업로드 한도(5MB·4096²)로 올린 이미지를 배너에 고르면 "공개 메인이 활성 배너 전부를 매번 내려받는다"는 2MB 예산이 깨진다.
5. **파일 이름이 저장돼 있지 않다.** `content_image`는 원본 파일명이 없어 "검색"할 대상이 없다.
6. **삭제 경합**: 라이브러리 삭제와 배너 등록(선택)·게시글 저장(참조 교체)이 동시에 일어날 수 있다.
7. **기존 동작에 걸린 시험·문서**: 배너·팝업 삭제 시 파일 삭제를 검증하는 시험(`BannerApiIntegrationTest`, `PopupFileCleanupIntegrationTest`, 동시성 시험), `LocalDiskFileStorageTest`의 예약 네임스페이스, 수동 회수 ②·⑤·⑤-3·⑤-4(`docs/deployment.md`) — 라이브러리 도입 후에도 틀리지 않아야 한다.
8. 본문 `src`는 `/content-images/{id}`로 sanitizer·편집기·공개 컨트롤러가 못박고 있다. 이미 저장된 본문을 다시 쓰지 않으려면 **URL을 바꿀 수 없다.**

## 설계 결정 (모든 결정에 "왜")

### D1. 새 테이블 대신 `content_image`를 라이브러리 원본으로 승격한다
- 선택지: (a) 신규 `media` 테이블 + 3곳 이관 (b) `content_image`에 컬럼 추가해 승격. **(b) 채택.**
- 왜: 카운터·참조 테이블(FK RESTRICT)·업로드 검증·공개 판정이 이미 여기 있고 시험이 붙어 있다. (a)는 같은 장치를 복제하고 본문 이미지 URL·참조를 전부 옮겨야 한다.
- 변경(V43, DDL): `content_image`에 `namespace varchar(30) NOT NULL DEFAULT ''`(파일이 있는 `FileStorage` 네임스페이스, **빈 문자열 = 루트** — NULL을 쓰면 MariaDB UNIQUE가 NULL 중복을 허용해 루트 키의 유일성이 깨진다. 리뷰 R4-1), `original_name varchar(200) NULL`(검색·표시용, 서버가 정리한 파일명). `scope_type` 값에 `LIBRARY`(라이브러리 직접 업로드), `BANNER`, `POPUP`(배너·팝업에서 직접 올린 것 / 이관된 것)을 추가한다(`scope_id`는 NULL). 참조 `owner_type`에 `BANNER`, `POPUP` 추가(`POST`는 그대로).

### D2. 기존 파일은 옮기지 않는다 — 행만 만든다
- 선택지: (a) 모든 파일을 루트로 이동 + 키 변경 (b) 파일은 제자리, `namespace` 컬럼으로 위치만 기록. **(b) 채택.**
- 왜: 이동은 디스크 작업이 마이그레이션 트랜잭션 밖에서 일어나 중간 실패 시 행/파일이 어긋난다. 위치 기록만 하면 마이그레이션은 순수 DML이고 공개 URL(`/banners/{id}/image`, `/popups/{id}/image`)·수동 회수 ⑤-3/⑤-4 대상이 그대로다. `FileStorage` 인터페이스 변경 불필요(`open/load/delete(key, namespace)`가 이미 있다).
- 이관(V44, 백필 DML): 기존 `banner`·`popup`(이미지형) 각 행마다 `content_image` 행을 만들고(`scope_type='BANNER'/'POPUP'`, `namespace`, 기존 `storage_key/content_type/file_size`, `uploader_id='system'`, `original_name` = 제목) `content_image_ref(BANNER|POPUP, 소유자 id, image_id)`를 만든다. 본문 이미지 행은 그대로.

### D3. 이미지의 "출처 격리"는 유지하고 라이브러리 이미지만 교차 사용을 허용한다
- 선택지: (a) 격리 전면 폐기 (b) 격리 유지 + 라이브러리 계열(`LIBRARY`·`BANNER`·`POPUP`)만 교차 사용 허용 (c) 격리 유지, 라이브러리는 조회 전용. **(b) 채택.**
- 왜: (a)는 쟁점 1의 유출 경로를 다시 연다. (c)는 "다시 고르기"라는 목적을 포기한다. (b)에서 교차 사용 가능한 이미지는 처음부터 "게시판 권한이 아니라 라이브러리 권한이 지키는" 이미지다.
- 규칙:
  - `BOARD`/`NOTICE` 출처(게시판 에디터에서 올린 본문 이미지)는 **지금과 같다.** 라이브러리 화면에는 목록·사용처·삭제(미사용일 때)가 보이지만 **다른 곳에서 고를 수는 없다.**
  - 라이브러리 계열 이미지를 참조로 저장할 때는 **저장하는 사람의 현재 `MEDIA` READ**를 요구한다(ADMIN 항상 허용, 아래 D6). 판정은 `replaceRefs`/배너·팝업 서비스 안에서 하며 거부는 쓰기 전 403이다. 이미지 ID는 연속 정수라 짐작으로 넣을 수 있기 때문이다.
  - 공개 판정(`existsPublishedPostRef`)은 `(i.scopeType='BOARD' ∧ i.scopeId=p.boardId) ∨ i.scopeType IN ('LIBRARY','BANNER','POPUP')`로 넓힌다 — 라이브러리 이미지도 공개 글이 참조하면 `/content-images/{id}`로 공개된다.
  - 에디터 안 미리보기(저장 전)를 위해 `findViewable`의 둘째 분기에 "라이브러리 계열 ∧ 요청자의 `MEDIA` READ"를 추가한다.
  - **파일 위치 전달(리뷰 R2-4)**: `ContentImageMeta`에 `namespace`를 추가하고 `PublicContentImageService.open`은 `namespace`가 빈 문자열이면 루트 `open(key)`, 아니면 `open(key, namespace)`를 쓴다(`LocalDiskFileStorage`의 namespace API는 `[a-z0-9_-]+`만 받아 빈 문자열을 거부하므로 분기가 필요). 이관된 배너·팝업 이미지를 게시글 본문에 넣어도 파일이 `banner/`·`popup/`에서 읽힌다.
  - `/content-images/{id}`는 **배너·팝업만 참조하는 이미지를 공개하지 않는다**(배너·팝업은 자기 경로의 노출 재검증으로만 나간다) → 공개 표면이 늘지 않고 `SecurityConfig`의 공개 경로 표는 바뀌지 않는다.

- **출처별 인가(리뷰 R1-3, P1)**: 라이브러리 화면·API는 `MEDIA` READ만으로 모든 이미지를 보이게 하지 않는다.
  - 목록·상세·파일 보기·삭제 대상은 호출자에 따라 SQL 조건으로 걸러진다: **ADMIN은 전부, MANAGER는 `scope_type IN ('LIBRARY','BANNER','POPUP')`만**. `BOARD`/`NOTICE` 출처(게시판 본문 이미지)는 ADMIN에게만 보인다(게시판 권한은 기존대로 그 게시판 편집기·`/content-images/{id}` 미리보기가 지킨다). SQL 조건이라 페이지네이션·총건수가 어긋나지 않고, ID를 추측해 `/admin/api/media/{id}/file`·`/{id}`·`DELETE`를 호출해도 같은 404다.
  - 사용처 목록: 소유자 종류·ID는 항상 보이되, 게시글 라벨(제목·게시판)은 호출자가 그 게시판 READ일 때만 채우고 아니면 고정 문구 "권한이 없는 게시판의 게시글"이다.
  - 근거: 격리의 목적(다른 게시판 비공개 이미지가 권한 밖으로 새지 않음)을 "고르기 금지"뿐 아니라 "보기·받기 금지"까지 확장해야 한다.

### D4. 배너·팝업은 이미지 ID로 고르거나(신규) 직접 올린다(기존, 호환)
- 요청에 `imageId` XOR `image`(파일)를 받는다. 직접 올리면 `content_image`에 `BANNER`/`POPUP` 출처 행이 함께 생긴다(= 라이브러리에 자동 등록).
- **배너·팝업과 이미지의 연결은 `content_image_ref`(`owner_type` = `BANNER`/`POPUP`, `owner_id` = 배너·팝업 ID) 한 곳뿐이다 — `banner`/`popup`에 `image_id` 컬럼을 만들지 않는다**(리뷰 R2-3, 사용자 확정 "사용처 추적은 참조 테이블"). 소유자 테이블에 FK 컬럼을 또 두면 같은 사실의 이중 장부가 되고, 소유자 DELETE↔이미지 DELETE 순서·팝업 HTML형 NULL·배너 NOT NULL 전환 같은 문제가 따라온다. 서빙은 `ref → image`로 한다. 불변식 "배너와 이미지형 팝업은 참조 행이 정확히 1개, HTML형 팝업은 0개"는 서비스가 한 트랜잭션에서 강제하고 서비스·통합 시험이 고정한다(대가: DB가 막아 주지 못함).
- **공개·관리 조회의 SQL 계약(리뷰 R3-2)**: 소유자 쿼리가 **`LIMIT` 전에** 이미지 적격성을 판정한다 — 공개 배너: `use_yn ∧ 기간 ∧ EXISTS(참조 BANNER ∧ 그 이미지 행)`, 공개 팝업: `use_yn ∧ 기간 ∧ (popup_type='HTML' ∨ EXISTS(참조 POPUP ∧ 그 이미지 행))` 후 `id` 순 최대 5개(**팝업만** — 배너는 기존대로 `ord, id` 순 활성 전체이고 개수 제한이 없다, 리뷰 R4-4). 그다음 선택된 소유자 ID 집합의 참조·이미지를 **한 번의 `IN` 쿼리**로 가져와 DTO에 붙인다(N+1 없음, 소유자 쿼리에 이미지를 조인하지 않으므로 행 증식·LIMIT 왜곡 없음). 불변식을 어긴 데이터(참조 2개 이상)는 **`image_id` 최솟값 하나**로 결정적으로 고른다. 참조 없는 이미지형 팝업·배너는 공개에서 제외된다(슬롯도 차지하지 않는다). 관리자 목록도 같은 일괄 조회를 쓰고 참조가 없으면 "이미지 없음" 상태로 보인다. 공개 이미지 경로(`/banners/{id}/image`, `/popups/{id}/image`)도 같은 적격성·최솟값 규칙을 쓴다. 시험: HTML형 혼합, 이미지형인데 참조 누락, 복수 참조, 5개 상한 앞의 불완전 팝업이 슬롯을 차지하지 않음.
- **기존 `storage_key/content_type/file_size` 사본은 이중 쓰기하지 않는다**(리뷰 R1-9): V45에서 `banner`의 세 컬럼을 NULL 허용으로 완화하고(팝업은 이미 NULL 허용) **새 앱은 쓰지도 읽지도 않는다.** 서빙·삭제·관리자 미리보기는 참조된 `content_image(namespace, storage_key, content_type, file_size)`만 본다. 이관된 기존 행의 값만 화석으로 남고 컬럼 삭제는 후속 PR이다. 엔티티에서는 이 필드를 뺀다(`ddl-auto: validate`는 매핑된 컬럼만 본다). **`storage_key` UNIQUE는 그대로 둔다** — 새 행은 NULL이라 중복 제약과 충돌하지 않아 UNIQUE 해제 DDL이 필요 없다(쟁점 3 해소).
- 선택 시 용도 한도 재검증: 이미지 바이트를 읽어 `ImageFileValidator.validate(bytes, type, 배너·팝업 Limits)` 후 `file_size ≤ 2MB`를 검사한다(쟁점 4, 헤더만 읽는 검증이라 5MB 한 번 읽기). 위반은 400 "배너/팝업에 쓰기엔 큽니다".
- 삭제 규칙: 배너/팝업을 지우면 **참조 행을 지우고**, 그 이미지가 `BANNER`/`POPUP` 출처이고 **다른 참조가 없으면** 이미지 행·파일도 함께 지운다(커밋 후 파일 삭제, 카운터 감소). `LIBRARY`/`BOARD` 출처는 남긴다. 왜: 기존 동작과 시험(삭제 시 파일 삭제)을 보존하고, 라이브러리에 올려 둔 이미지를 소유자 삭제가 날리지 않게 한다.

### D5. 전체 용량 상한은 기존 카운터를 재사용한다 (값 변경 없음) + 잠금 계약 표
- `content_image_usage`(기본 1GB·1만 개, `cms.content-image.*`)를 라이브러리 상한으로 그대로 쓴다. 새 설정 키를 만들지 않는다. V44가 카운터를 **원본 재계산**(`total_bytes = COALESCE(SUM(content_image.file_size), 0)`, `total_count = COUNT(*)` — 빈 집합에서 SUM이 NULL이라 NOT NULL 컬럼에 쓰면 실패하므로 `COALESCE` 필수, 리뷰 R4-3)으로 맞춘다 — 덧셈이 아니라 재계산이라 부분 실패 후 재시도에도 중복 합산이 없다(리뷰 R1-2). 현재 카운터는 업로드 때 증가만 하고 감소 경로가 없어 재계산 값과 의미가 같다.
- 이후 **새 이미지 행을 만드는** 모든 경로(라이브러리·게시판 에디터·배너/팝업 직접 업로드)가 카운터를 `FOR UPDATE`로 잡고 `reserve`한다. 삭제는 `release`한다(신규).
- **전역 잠금 순서(리뷰 R1-4·R2-1, 확정)**: `가드 행(banner_lock)` → `소유자 행(banner/popup/post)` → `이미지 행(content_image)` → `참조 행(content_image_ref)` → `카운터 행(content_image_usage)`. 핵심 규칙: **참조 행을 지우거나 새 이미지에 대한 판단을 하기 전에 반드시 이미지 행을 먼저 잠근다.**

| 경로 | 잠금 순서 | 비고 |
|---|---|---|
| 라이브러리/게시판 에디터 업로드 | 카운터 → (새 이미지 행 INSERT) | 새 행은 다른 트랜잭션이 잠글 수 없어 순서 위반이 아니다 |
| 배너 직접 업로드(생성) | `banner_lock` → 카운터 → (새 이미지·배너·참조 INSERT) | |
| 팝업 직접 업로드(생성) | 카운터 → (새 이미지·팝업·참조 INSERT) | 팝업은 가드 행이 없다 |
| 배너/팝업 **선택**(생성) | (`banner_lock`) → 이미지 행 `FOR SHARE` → (배너·참조 INSERT) | **카운터를 잡지 않는다**(새 행이 없다). 이미지가 없으면 404 |
| 배너/팝업 **삭제** | (`banner_lock`) → 소유자 행 `FOR UPDATE` → **이미지 행 `FOR UPDATE`** → 자기 참조 삭제 → 참조 **잠금 읽기** → 소유자 행 DELETE → (`BANNER`/`POPUP` 출처 ∧ 참조 0이면) 카운터 → 이미지 삭제 | 다른 소유자가 같은 이미지를 참조 중이면 이미지는 남는다. 이미지를 먼저 잠그므로 상대 소유자는 자기 참조를 삭제하기 전에 이미지 잠금에서 대기 → 서로의 참조 행을 기다리는 순환이 생기지 않는다 |
| 라이브러리 삭제 | 이미지 행 `FOR UPDATE` → 참조 **잠금 읽기** → 있으면 409(+사용처) / 없으면 카운터 → 삭제 | |
| 게시글 저장(`replaceRefs`) | 게시글 행(기존) → **대상 이미지 행 `FOR SHARE`(id 오름차순)** → 참조 교체 | 존재 확인과 삭제 방지를 한 번에 한다(D8) |

- **호출 그래프·감사 계약(리뷰 R4-2)**: `@AdminActionLogged`는 감사 Aspect가 커밋 뒤 별도 트랜잭션으로 기록하므로 **최상위 진입점에서만** 보장된다. 그래서 (a) 감사 애너테이션은 최상위 업무 진입점(`MediaService.upload/delete`, `BannerService`/`PopupService`의 생성·삭제 등)에만 붙이고, (b) 이미지 등록(검증·카운터 `reserve`·파일 저장·행 INSERT)과 제거(참조 확인·카운터 `release`·행 삭제·커밋 후 파일 삭제)는 **애너테이션 없는 공통 컴포넌트**(`MediaImageRegistrar`/`MediaImageRemover`)로 두어 호출자의 트랜잭션에 `REQUIRED`로 참여한다(내부 `REQUIRES_NEW` 금지 — 업무 원자성이 깨진다). 배너 생성이 후속 단계에서 실패하면 이미지·카운터·파일·배너가 함께 롤백되고 감사는 배너 생성 실패 한 건뿐이다. 시험: 실제 서비스 조합에서 후속 실패 주입 후 데이터·파일·카운터·감사 행을 함께 검증.
- **격리 수준 계약(리뷰 R3-1·R4-2)**: 격리 수준은 **최상위 삭제 진입점 메서드**(`MediaService.delete`, `BannerService.deleteBanner`, `PopupService.deletePopup`)에 `@Transactional(isolation = READ_COMMITTED)`로 선언한다 — 모두 최상위라 `REQUIRED`가 새 트랜잭션을 만들며 격리가 적용된다(외부 트랜잭션에 참여하면 내부 격리는 무시되므로 공통 제거 컴포넌트에는 선언하지 않는다). 시험이 진입 직후 `TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()`로 고정한다. (정정: 프로젝트의 쪽지 발송 선례는 `REQUIRES_NEW + READ_COMMITTED`다 — 우리는 최상위라 `REQUIRES_NEW`가 필요 없다.) `content_image_ref.image_id`는 **비고유 인덱스**라 REPEATABLE READ의 잠금 읽기는 빈 범위에도 갭 잠금을 걸어 다른 이미지에 대한 참조 INSERT(직접 업로드)를 막을 수 있다 — 카운터를 쥔 업로드와 카운터를 기다리는 삭제가 서로를 기다리는 순환이 가능하다. 그래서 **이미지를 지우는 모든 경로(라이브러리 삭제, 배너·팝업 삭제)는 `@Transactional(isolation = READ_COMMITTED)`** 로 실행한다(RC는 인덱스 탐색 갭 잠금이 없고 문장마다 최신 커밋을 읽는다 — 프로젝트의 쪽지 발송이 같은 방식으로 격리 수준을 계약으로 쓴다). `replaceRefs`의 이미지 `FOR SHARE`는 기본 키 정확 일치라 레코드 락만 잡는다. 시험: **미사용 이미지 삭제 ↔ 팝업 직접 업로드**(래치로 삭제의 참조 읽기와 업로드의 카운터 보유를 동시에 강제) + 변이 실험(삭제를 REPEATABLE READ로 바꾸면 데드락).
- **참조 존재 확인은 반드시 잠금 읽기**(`SELECT … FOR UPDATE`/`FOR SHARE`)다. 이미지 행을 먼저 잠근 트랜잭션이 보는 참조는 **상대가 아직 지우지 않은 것**이라 공유 이미지를 동시에 지워도 먼저 잠근 쪽이 이미지를 남기고 나중 쪽이 정리한다(양쪽 모두 "상대 참조가 남아 있다"고 보는 고아 누수 방지). 시험: ① 배너↔팝업 동시 삭제(공유 이미지)가 데드락 없이 끝나고 이미지 행·파일·카운터가 일관됨 — **두 트랜잭션이 이미지 잠금 지점까지 반드시 도달하도록 래치로 강제**(`LockWaitObserver` 패턴), ② 팝업 둘의 공유 이미지 동시 삭제, ③ 변이 실험(이미지 잠금을 참조 삭제 뒤로 옮기면 데드락/실패, 잠금 읽기를 일반 읽기로 바꾸면 불일치).

### D6. 권한: 다섯 번째 `DELEGABLE` 기능 `MEDIA` (**인가 정책 변경 — 승인 필요**)
- 동작: `READ`(목록·상세·파일 보기·고르기), `CREATE`(업로드), `DELETE`(삭제). `UPDATE`는 없다(이름 변경 등 수정 기능을 만들지 않는다).
- 게이트: `/admin/media/manage`, `/admin/api/media`, `/admin/api/media/**` (`AdminFeature.gatePatterns` → `SecurityConfig`의 DELEGABLE 루프. **`SecurityConfig` 코드 수정 없음**).
- 선택지: (a) ADMIN 전용 (b) `DELEGABLE`. **(b) 권장.** (a)는 구현이 가볍지만 배너/팝업/게시판을 맡은 MANAGER가 라이브러리에서 고를 수 없어 목표("배너·팝업·편집기에서 다시 고르기")가 ADMIN에게만 열린다. 열거 시험이 함께 바뀐다(`AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`·`MenuExposureSidebarIntegrationTest`).
- 기능 권한 없이도 되는 것: BANNER/POPUP 권한자의 **직접 업로드**(기존 흐름)는 그대로 된다. 라이브러리에서 **고르기**에만 `MEDIA` READ가 추가로 필요하다.
- 롤백 후 권한 부활(배너·팝업·FAQ와 같은 문제): `docs/deployment.md`에 `feature='MEDIA'` 정리 SQL을 같은 형식으로 적는다.

### D7. 공개 접근 규칙 요약
공개 = 공개 콘텐츠가 참조하는 이미지뿐이다. 배너/팝업은 자기 경로(노출 재검증), 게시글은 `/content-images/{id}`(D3). 어느 쪽도 참조하지 않거나 비공개 콘텐츠만 참조하는 파일은 404(기존 규칙 유지). 라이브러리 관리 화면의 미리보기는 **관리자 전용 엔드포인트**(`GET /admin/api/media/{id}/file`, READ, byte[])로 한다 — 공개 경로를 관리 용도로 넓히지 않는다.

### D8. 삭제 경합 계약 (리뷰 R1-5·R1-6 반영)
- 라이브러리 삭제: D5 표대로 이미지 행 `FOR UPDATE` → 참조 잠금 읽기 → 있으면 **409 + 사용처 목록(같은 트랜잭션의 이 읽기에서 조립 — FK 위반 후 재조회가 아니다)**, 없으면 카운터 감소 + 행 삭제 + 커밋 후 파일 삭제.
- 명시적 선택(배너·팝업 `imageId`): 이미지 행 `FOR SHARE`로 삭제를 막은 채 참조를 넣는다. 이미지가 없으면 404.
- **게시글 본문 저장** (리뷰 R2-2 반영): `replaceRefs`는 대상 이미지 행을 **id 오름차순으로 `FOR SHARE`** 로 읽는다(기존 `findAllById`를 대체 — 존재 확인과 삭제 방지가 한 문장이다). 결과는 둘로 닫힌다: ① 삭제가 먼저 끝났으면 그 ID는 조회되지 않아 기존 계약대로 **조용히 참조에서 빠진다**(본문에 죽은 `<img>`가 남지만 무해), ② 저장의 잠금이 먼저면 삭제는 대기했다가 새 참조를 보고 **409**. "존재 확인 후 삭제 커밋 → 참조 INSERT가 FK 위반으로 저장 전체 롤백"이라는 세 번째 결과는 공유 잠금 때문에 일어나지 않는다. 시험은 존재 확인 직후 삭제 시도를 끼워 넣어 삭제가 대기함을 고정한다.
- FK `RESTRICT`는 **최후 방어선**이다. 위 잠금 계약 아래서는 도달하지 않아야 한다. 기존 `GlobalApiExceptionHandler`가 FK 위반을 이미 일반 `409 DUPLICATE_RESOURCE`로 바꾸므로 새 매핑은 만들지 않는다(도달하는 경로가 있다면 시험으로 드러난 잠금 계약 결함이다).
- 파일 삭제 실패는 "행 없는 파일"로 남고 수동 회수가 정리한다(기존 계약).

### D9. 검색·표시를 위해 원본 파일명을 저장한다 (`original_name`)
- 업로드 시 `MultipartFile.getOriginalFilename()`의 마지막 경로 요소를 NFC 정규화·제어문자 제거·200자 절단해 저장(표시는 항상 `textContent`). 이관분은 배너/팝업 제목, 본문 이미지는 `NULL`(화면에 `이미지 #id`).
- 검색: `original_name LIKE %keyword%`(와일드카드 문자 그대로 이스케이프) + 숫자 키워드는 ID 일치. 목록은 `id desc`, 페이지네이션은 기존 목록 API 컨벤션(`page`/`size`) 따름, `size` 상한 50.
- 필터: `origin`(`ALL`·`LIBRARY`·`BANNER`·`POPUP`·`BOARD`), `unused=true`(참조 없음).

### D10. 화면
- 새 화면 `/admin/media/manage`(`templates/admin/media/manage.html`, `@AdminPage`): 업로드, 검색·필터, 썸네일 그리드, 사용처 보기, 삭제. 메뉴 시드는 V46(멱등 `WHERE NOT EXISTS`, 순수 DML).
- 선택 모달: `templates/admin/fragments/media-picker.html` + `static/js/admin/media-picker.js` 하나를 배너·팝업·편집기가 공유한다. 모달은 `GET /admin/api/media?origin=LIBRARY,BANNER,POPUP`(고를 수 있는 것만)을 쓰며 `MEDIA` READ가 없으면 서버가 버튼을 렌더링하지 않고(권한 노출) API도 403이다.
- 편집기: `notice-editor.js`에 선택 콜백 옵션 `pickImage`(없으면 버튼 숨김)를 추가하고 sanitizer 허용 목록·`formats`는 **건드리지 않는다**(본문에는 여전히 `/content-images/{id}`).
- 정적 페이지·FAQ·팝업 HTML형은 이미지 불허라 선택 모달을 연결하지 않는다.

### D11. 마이그레이션 분할과 롤백 정책 (리뷰 R1-2·R1-7)
- DDL과 DML을 섞지 않는다. 문마다 암묵 커밋이므로 단계를 나눠 부분 실패 지점을 명확히 한다.
  - **V43 (DDL)**: `content_image` +`namespace`(NOT NULL DEFAULT '')·`original_name`, 그리고 **`uk_content_image_storage_key`(storage_key 단독 UNIQUE)를 `UNIQUE(namespace, storage_key)`로 교체**한다(리뷰 R4-1 — 다른 namespace에 같은 키 문자열이 있는 기존 데이터도 이관할 수 있어야 한다). 소유자 테이블 변경 없음(D4).
  - **V44 (DML, 문장별 독립 멱등 — 리뷰 R3-3)**: ① **이미지 행**: `banner`·이미지형 `popup`의 `storage_key`마다 `(namespace, storage_key)`가 같은 `content_image` 행이 **없을 때만** 생성, ② **참조 행**: 소유자의 `storage_key`로 위 이미지 행을 찾아 `(owner_type, owner_id)` 참조가 **없을 때만** 생성, ③ 카운터 **원본 재계산**. ①과 ②는 서로의 완료 여부를 가정하지 않으므로 어느 지점에서 끊겨도 재실행으로 수렴한다(Flyway는 DML 마이그레이션을 한 트랜잭션으로 감싸 정상 실행은 원자적이다 — 이 성질은 수동 복구·재개 절차를 위한 것). 정합성(이미지형 소유자 수 = 해당 owner_type 참조 수 = 이관 이미지 수)은 `MediaMigrationTest`와 문서의 확인 SQL이 검증한다.
  - **V45 (DDL)**: `banner.storage_key/content_type/file_size` NULL 허용(팝업은 이미 NULL 허용). 실패하면 V44 결과는 남아 있으므로 V45만 재개하면 된다. `docs/migration-guide.md`에 "V43~V46 실패 복구"(어느 버전에서 멈췄는지 → 확인 SQL → 재개 방법)를 같은 형식으로 추가한다.
  - **V46 (DML)**: `MEDIA` 메뉴 시드(멱등).
- **V43 이전 앱으로의 롤백은 지원하지 않는다(roll-forward만)**: 구버전은 배너·팝업 삭제 때 `storage_key`로 파일을 지우는데 새 행은 그 컬럼이 NULL이고 공유 이미지를 모른다. V32와 같은 등급으로 `migration-guide.md`에 명시한다.
- **수동 회수(`docs/deployment.md`) 개정 — 필수**: ② 회수 대상은 "살아 있는 **어떤 소유자**(POST·BANNER·POPUP)도 참조하지 않는 이미지"로, ⑤의 보존 목록은 `content_image` 전 행의 키를 **namespace별로**(빈 문자열=루트, `banner`, `popup`) 만들며, ⑤-3·⑤-4는 `content_image`의 해당 namespace 키를 보존 기준으로 삼고 파일 삭제는 namespace를 구분해 수행한다. 개정한 블록은 일회용 MariaDB·볼륨에서 **실제로 실행**해 (a) 배너 삭제 후 게시글만 참조하는 이미지 보존, (b) 미참조 라이브러리 이미지 회수, (c) 행 없는 파일만 삭제를 확인한다.

## 범위 밖 (이번에 만들지 않는다)
- 이미지 이름 변경·교체·태그·폴더, 공지/게시글 **첨부**와 프로필 이미지의 이관, 자동 정리(미참조 이미지 자동 삭제), 썸네일 생성, 이미지 외 형식.
- 공개 경로·`SecurityConfig` 변경.

## 작업 단계
1. V43 DDL → V44 백필 DML(멱등, 카운터 재계산) → V45 제약 강화 DDL → V46 메뉴 시드 (D11).
2. 도메인·리포지토리: `ContentImage` 컬럼, `ContentImageRef` owner 확장, 사용처 조회, 목록/검색 QueryDSL(`*RepositoryImpl`), 카운터 `release`.
3. 서비스: `MediaService`(업로드·목록·상세·파일·삭제), `ContentImageService.replaceRefs` 자격 확장(D3), 배너·팝업 서비스(이미지 선택·직접 업로드의 카운터·출처 행·삭제 규칙).
4. 공개 판정: `existsPublishedPostRef`·`PublicContentImageService` 확장, 배너/팝업 공개 서비스의 파일 위치 해석을 `image_id` 기반으로.
5. `AdminFeature.MEDIA`, 컨트롤러·DTO, 감사 `MEDIA_UPLOAD`·`MEDIA_DELETE`(`AdminActionTypes` + 로그 화면 라벨 동기화).
6. 화면: 라이브러리 관리, 선택 모달, 배너·팝업 폼·편집기 연동.
7. 시험 → 전체 `./gradlew test`(UTC 재실행 포함) → 실기 검증 → 문서(`CLAUDE.md` 지도, 패키지 `CLAUDE.md`, `deployment.md` 수동 회수 ②·⑤·⑤-3·⑤-4 갱신, `migration-guide.md`, `troubleshooting.md`).

## 완료 기준 (로드맵 + 이 계획)
- [ ] `./gradlew test` 통과(UTC 재실행 포함)
- [ ] 사용 중 파일 삭제 409 + 사용처 목록, 미사용 삭제 시 행·파일·카운터 모두 정리(Testcontainers + 실제 `LocalDiskFileStorage`)
- [ ] 업로드 롤백 시 저장 파일·카운터가 남지 않음, 허용 외 형식·5MB 초과·전체 용량 초과 거부
- [ ] 참조되지 않은/비공개만 참조하는 파일의 공개 다운로드 404, 라이브러리 이미지를 공개 글이 참조하면 200
- [ ] 이관 전후 배너·팝업·본문 이미지 바이트·공개 표시 동일, 첨부·프로필 이미지 회귀 없음
- [ ] 다른 게시판 출처 이미지는 여전히 고를 수 없음(회귀), `MEDIA` READ 없는 사용자는 라이브러리 이미지를 참조 저장할 수 없음(403)
- [ ] 삭제↔배너 선택, 삭제↔게시글 저장 동시성 시험 + 변이 실험
- [ ] **V42 데이터가 든 DB**(배너·이미지형/HTML형 팝업·본문 이미지)에서 V43~V46 업그레이드 후 바이트·공개 표시 동일, 카운터 = 원본 재계산 값, V44 재실행(부분 실패 모사)에서 중복 행·중복 합산 없음
- [ ] 역사적 마이그레이션 시험(`BannerMigrationTest`·`PopupMigrationTest`)은 Flyway 대상 버전을 고정하고, 최신 스키마용 시험(같은 이미지를 여러 배너·팝업이 참조·HTML형 팝업 참조 0개·`banner` 사본 컬럼 NULL 허용·참조 FK RESTRICT)을 추가. 배너·팝업 API/정리 시험의 `cleanUp`은 이미지·참조 행과 카운터 재계산까지 정리
- [ ] 출처별 인가: `MEDIA` READ만 가진 MANAGER가 BOARD 출처 이미지 ID로 목록·상세·파일·삭제를 호출하면 목록 제외 / 404, 비공개 게시판 게시글 사용처 라벨은 고정 문구
- [ ] 삭제 경합: 삭제↔배너 선택(선택 404 또는 삭제 409), 삭제↔게시글 저장(저장 잠금이 먼저면 삭제 대기 후 409, 삭제가 먼저면 저장 성공 + 죽은 `<img>` 무시 — **존재 확인 직후 삭제를 끼워 넣어도 FK 위반으로 저장이 롤백되지 않음**), 배너↔팝업·팝업↔팝업 공유 이미지 동시 삭제가 데드락 없이 일관(래치로 이미지 잠금 지점 도달 강제), 변이 실험(이미지 잠금 순서·잠금 읽기)
- [ ] 업그레이드 시험 DB 구성: 이미지 없는 V42 DB / 완전히 빈 DB(카운터 `(0,0)`, `COALESCE`) / 배너·팝업·본문 이미지가 **같은 `storage_key` 문자열**(서로 다른 바이트)을 가진 DB — 모두 V46까지 성공하고 파일·바이트가 각자 맞음
- [ ] 호출 그래프: 삭제 진입점의 격리 수준이 READ_COMMITTED, 배너/팝업 생성 후속 실패 주입 시 이미지·카운터·파일·배너 롤백과 감사 행(배너 생성 실패 1건)이 함께 검증됨
- [ ] V44 부분 상태 재실행: 이미지 행만 있고 참조가 없는 상태 / 참조까지 있는 상태 모두에서 재실행해 중복 행 없이 수렴
- [ ] 공개 배너·팝업 조회 계약(D4): HTML형 혼합, 이미지형 참조 누락, 복수 참조(최솟값), 5개 상한 앞 불완전 팝업이 슬롯을 차지하지 않음, 일괄 조회로 N+1 없음(쿼리 수 고정)
- [ ] 이관된 배너·팝업 이미지를 게시글 본문에 넣은 뒤 **익명 GET/HEAD 응답 바이트가 원본과 일치**(namespace 전달)
- [ ] 개정한 수동 회수 블록을 일회용 환경에서 실제 실행해 보존/회수 결과 확인
- [ ] Playwright: 라이브러리 업로드 → 배너 등록 화면·편집기에서 선택 → 사용 중 삭제 차단

## 리스크
- **스키마 변경 큼**(V43~V45, 기존 두 테이블 DDL·UNIQUE 해제·대량 백필). 되돌리기 어렵다 → 승인 때 별도 고지.
- **인가 정책 변경**: 신규 `DELEGABLE` 기능 `MEDIA`.
- 잠금 순서 확장(`banner_lock` → 카운터)으로 배너 업로드 직렬화 범위가 넓어진다(카운터 행을 잡는 모든 업로드가 직렬화됨 — 현재 본문 이미지 업로드가 이미 그렇다).
- 변경 범위가 넓어 PR 하나로 가면 리뷰가 어렵다 → 구현은 한 브랜치에서 하되 커밋을 `스키마·도메인 / 서비스·공개 / API·화면` 단위로 나눈다(커밋 분할은 commitPR 단계에서 확정).

## 개정 이력
- v1 (2026-10-12): 최초 작성.
- v2 변경 (codex 적대적 리뷰 1라운드, 판정 no-ship, 지적 9건 전부 수용 — R1-3은 범위 확장):
  - R1-1 (P1) 수용: `popup.image_id`는 NULL 유지(HTML형), 배너만 NOT NULL. 불변식 "이미지형⇔image_id"는 서비스+시험으로 강제. (D4)
  - R1-2 (P1) 수용: 마이그레이션을 V43 DDL / V44 멱등 DML / V45 제약 DDL / V46 메뉴 DML로 분리, 카운터는 원본 재계산, 실패 복구 절차 문서화. (D5·D11)
  - R1-3 (P1) 수용: 출처별 인가 추가 — BOARD 출처 이미지는 ADMIN에게만 목록·상세·파일·삭제 가능(SQL 조건), 사용처 게시글 라벨은 그 게시판 READ일 때만. (D3)
  - R1-4 (P2) 수용: 전역 잠금 순서(가드→소유자→이미지→카운터)와 경로별 표 확정, 선택은 카운터 미취득 명시, 참조 확인은 잠금 읽기. (D5)
  - R1-5 (P2) 수용: `replaceRefs`의 "없는 ID 무시" 계약 유지, 완료 기준을 두 실행 순서로 정정. 명시적 `imageId`는 404. (D8)
  - R1-6 (P2) 수용: 409+사용처는 같은 트랜잭션의 잠금 읽기에서 조립, FK는 최후 방어선(번역 매핑을 만들지 않음). (D8)
  - R1-7 (P1) 수용: 수동 회수 ②·⑤·⑤-3·⑤-4를 `content_image`(namespace별) 기준으로 개정하고 일회용 환경에서 실행 검증. (D11)
  - R1-8 (P2) 수용: 마이그레이션 시험 버전 고정·최신 스키마 시험 추가, 정리 헬퍼 확장, V42 데이터 DB 업그레이드·부분 실패 복구를 완료 기준에 추가.
  - R1-9 (P3) 수용(변형): 사본 이중 쓰기 제거 — `banner.storage_key/content_type/file_size`는 NULL 허용으로 완화하고 새 앱은 쓰지도 읽지도 않음, UNIQUE 해제 DDL 불필요. (D4)
- v5.1 (codex 적대적 리뷰 5라운드, **ship 판정** — 구현 차단 수준의 P1/P2 없음): P3 수용 — 수동 회수 설명의 "NULL=루트"를 "빈 문자열=루트"로 통일.
- v5 변경 (codex 적대적 리뷰 4라운드, 판정 no-ship, P2 3건·P3 1건 전부 수용):
  - R4-1 (P2) 수용: `storage_key` 단독 UNIQUE가 다른 namespace의 같은 키 문자열 이관을 막음 → `namespace NOT NULL DEFAULT ''`(루트=빈 문자열)와 `UNIQUE(namespace, storage_key)`로 교체, 소비 코드는 빈 문자열이면 루트 API. 같은 키 문자열 DB 업그레이드 시험. (D1·D3·D11)
  - R4-2 (P2) 수용: 호출 그래프 계약 — 감사는 최상위 진입점에만, 공통 등록·제거 컴포넌트는 감사 없이 호출자 트랜잭션 참여, `READ_COMMITTED`는 최상위 삭제 진입점에 선언하고 시험으로 고정. 쪽지 선례가 `REQUIRES_NEW + READ_COMMITTED`라는 서술 정정. (D5)
  - R4-3 (P2) 수용: 카운터 재계산에 `COALESCE(SUM(file_size), 0)` 명시, 빈 DB·이미지 없는 V42 DB 시험. (D5)
  - R4-4 (P3) 수용: 배너(`ord, id` 전체)와 팝업(`id` 순 최대 5)의 정렬·개수 규칙 분리. (D4)
- v4 변경 (codex 적대적 리뷰 3라운드, 판정 no-ship, 지적 3건 전부 수용):
  - R3-1 (P2) 수용: 비고유 인덱스의 갭 잠금 순환 가능성 — 이미지를 지우는 모든 경로를 `READ_COMMITTED`로 실행하는 격리 수준 계약 추가, "미사용 이미지 삭제 ↔ 팝업 직접 업로드" 시험 + 변이 실험. (D5)
  - R3-2 (P2) 수용: 공개·관리 조회의 SQL 계약 명시 — 적격성(`HTML ∨ (IMAGE ∧ 유효 참조)`)을 `LIMIT` 전에 판정, 이미지는 소유자 ID 집합에 대한 일괄 `IN` 쿼리, 복수 참조는 `image_id` 최솟값. (D4)
  - R3-3 (P2) 수용: V44를 문장별로 독립 멱등하게(이미지 존재는 `(namespace, storage_key)`로, 참조는 그 이미지를 찾아 연결). (D11)
- v3 변경 (codex 적대적 리뷰 2라운드, 판정 no-ship, 지적 4건 전부 수용):
  - R2-1 (P2) 수용: 배너/팝업 삭제는 **이미지 행 잠금을 자기 참조 삭제보다 먼저** 잡는다. 상대는 이미지 잠금에서 대기해 서로의 참조 행을 기다리는 순환이 없다. 전역 순서를 `가드 → 소유자 → 이미지 → 참조 → 카운터`로 재확정, 래치로 두 트랜잭션의 도달을 강제하는 동시성 시험 + 변이 실험. (D5)
  - R2-2 (P2) 수용: `replaceRefs`가 대상 이미지를 id 오름차순 `FOR SHARE`로 읽어 존재 확인과 삭제 방지를 한 번에 한다(세 번째 결과 제거). "FK 위반 번역 매핑을 만들지 않는다"의 근거를 기존 핸들러가 이미 일반 409로 바꾼다는 사실로 정정. (D5·D8)
  - R2-3 (P2) 수용 + 단순화: `banner/popup.image_id` 컬럼·FK를 **폐기**하고 연결을 `content_image_ref`(BANNER/POPUP) 한 곳으로 둔다(이중 장부 제거, 로드맵 확정 결정 "참조 테이블"과 일치). 소유자 DDL·팝업 NULL/배너 NOT NULL·소유자 DELETE 순서 문제가 함께 사라진다. 불변식은 서비스·시험이 강제. V43은 `content_image` 컬럼 2개만, V44는 참조 행 백필. (D4·D11)
  - R2-4 (P2) 수용: `ContentImageMeta.namespace` 추가, NULL이면 루트 API/값이 있으면 namespace API. 이관 배너·팝업 이미지를 본문에 넣은 뒤 익명 GET/HEAD 바이트 일치를 완료 기준에 추가. (D3)

---

## 구현·검증 결과 (2026-10-12)

> 상태: **구현 완료·머지**(PR #136 `e03da10`, 브랜치 `feat/media-library`). 계획 v5.1(적대적 리뷰 5라운드 ship, 사용자 승인 2026-10-12)대로 구현했다.

### Context
이미지 업로드 경로 셋(본문·배너·팝업)이 같은 일을 하는 코드 세 벌이었고 서로의 파일을 볼 수 없었다. `content_image`를 라이브러리 원본으로 승격하고 배너·팝업·본문이 한 카운터·한 참조 테이블을 쓰게 했다.

### 핵심 확정 사항 (계획 대비)
계획 D1~D11 그대로이며, 구현 중 정해진 세부와 **계획과 달라진 점**:
- **409 사용처 목록은 메시지 문자열**("사용처: 배너 #3, 게시글 #12 외 N건" — 종류·ID만)이다. `ApiErrorResponse`에 구조화 필드를 추가하면 모든 오류 응답 계약이 바뀌어서 하지 않았다. 구조화된 사용처(권한에 맞춘 제목 라벨)는 `GET /admin/api/media/{id}`가 준다.
- 선택 모달용 `selectable=true` 쿼리 파라미터를 목록 API에 추가했다(라이브러리 계열 출처만 — ADMIN이어도 게시판 본문 이미지는 다른 곳에서 못 고르므로 모달에서 뺀다).
- `replaceRefs`가 현재 참조를 **이미지 ID 투영**(`findImageIdsByOwner`)으로 읽는다 — 엔티티로 읽으면 벌크 삭제 뒤 `saveAll`이 INSERT를 건너뛴다(아래 이슈 1).
- 게시판 에디터 업로드(`ContentImageService.uploadForBoard`)도 공통 등록 작업자를 쓰며 `original_name`을 저장한다(계획은 "본문 이미지는 NULL"이었으나 값이 있으면 라이브러리의 ADMIN 목록에서 검색에 유리해 같이 저장).
- 카운터 상한 메시지의 "본문 이미지"를 "이미지"로 일반화했다(시험이 문구에 의존하지 않음을 확인).
- `0` 바이트 표시는 "0KB"로 표시한다(실기에서 "1KB"로 보임 — 수정).

### 구현 파일
- 마이그레이션: `V43__media_library_columns.sql`·`V44__media_library_backfill.sql`·`V45__media_library_relax_banner_copy.sql`·`V46__seed_media_menu.sql`
- 신규 `com.cms.admin.media`: `service/{MediaService,MediaImageRegistrar,MediaImageRemover,MediaImageSelector,MediaImageLookup,MediaImageInfo}`, `controller/{MediaController,MediaPageController}`, `dto/{MediaResponse,MediaDetailResponse,MediaPageResponse}`, `CLAUDE.md`
- 수정: `ContentImage`(+namespace·original_name)·`ContentImageUsage`(+release)·`ContentImageRepository`(+Custom/Impl QueryDSL 검색, 락 조회)·`ContentImageRefRepository`(잠금 읽기·일괄 조회·공개 판정 확장)·`ContentImageService`(공통 등록 위임·`replaceRefs` 자격/공유 잠금)·`Banner`/`Popup`(사본 컬럼 제거)·`BannerService`/`PopupService`(imageId 선택·참조 연결·삭제 규칙·`READ_COMMITTED`)·배너/팝업 DTO·리포지토리(적격성 EXISTS)·공개 `PublicBannerService`/`PublicPopupService`/`PublicContentImageService`(+`ContentImageMeta.namespace`)·`AdminFeature.MEDIA`·`AdminActionTypes`(+로그 화면 라벨)
- 화면: `templates/admin/media/manage.html`·`static/js/admin/media-picker.js`·배너/팝업 폼의 "라이브러리에서 선택"·`notice-editor.js`(`pickLibraryImage` 옵션)·`board/posts.html` 연결
- 문서: 루트·패키지별 `CLAUDE.md`(media·contentimage·banner·popup·permission·test), `docs/deployment.md`(수동 회수 ②·⑤·⑤-3·⑤-4 개정 + "미디어 라이브러리"), `docs/migration-guide.md`(V43~V46), `docs/troubleshooting.md`
- 시험(신규): `MediaMigrationTest`·`MediaLibraryApiIntegrationTest`·`MediaConcurrencyIntegrationTest`·`MediaTestSupport`. 기존 시험 정리: 배너·팝업·공개 이미지 시험의 정리/픽스처를 새 구조로 이관, `AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`(DELEGABLE 열거 + MEDIA)

### 검증 결과
- **전체 테스트**: `./gradlew cleanTest test` **2158건 통과·실패 0·건너뜀 12**(기존에 건너뛰던 것)를 기본 시간대와 `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` 두 번 모두 확인했다(신규 시험: 미디어 마이그레이션 5·API 통합 13·동시성 7).
- **변이 실험**(동시성 계약이 실제로 보호하는지): ① 삭제 진입점의 `READ_COMMITTED` 제거 → 격리 수준 시험·갭 락 시험 2건 실패(REPEATABLE READ에서 다른 이미지의 참조 INSERT가 **실제로 막힘**을 실측), ② `replaceRefs`의 `FOR SHARE` 제거 → "존재 확인과 INSERT 사이 삭제" 시험 실패(FK 위반으로 저장 롤백 재현 — 단순 동시 출발 시험은 FK의 암묵 공유 잠금 때문에 이 변이를 못 잡아 훅 방식으로 교체), ③ 배너/팝업 삭제의 이미지 잠금을 참조 삭제 뒤로 → 공유 이미지 동시 삭제 시험에서 `Deadlock found` 재현. 세 변이 모두 원복.
- **마이그레이션**: V42 데이터 DB(같은 키 문자열을 세 네임스페이스가 가진 경우 포함) 업그레이드, 빈 DB·이미지 없는 V42, V44 부분 상태 재실행 수렴, 제약 변화를 `MediaMigrationTest`가 고정.
- **수동 회수 블록 실제 실행**(일회용 MariaDB·볼륨, 이름만 치환): 배너 삭제 뒤 게시글만 참조하는 `banner/` 이미지 보존, 배너가 쓰는 이미지 보존, 라이브러리 업로드 미참조 이미지 보존, 소유자 없는 팝업 이미지·삭제된 게시글 이미지 회수(행·파일·카운터), 행 없는 파일(루트·`banner/`·`popup/`)만 삭제, **루트의 같은 문자열 키 파일은 삭제**(banner 네임스페이스 키가 루트를 보존하지 않음) — 기대한 최종 파일 4개와 정확히 일치.
- **실기(Playwright, 일회용 DB·임시 스토리지·포트 8099)**: 업로드 → 그리드 → 배너 폼 선택 모달에서 선택 → 배너 등록(공개 `/banners/1/image` 200·같은 바이트, `/content-images/1` 익명 404) → 게시글 편집기 "라이브러리" 버튼으로 삽입 → 저장 → 익명 `/content-images/2` 200 → 사용 중 삭제 409(`사용처: 게시글 #1`) → 배너 삭제 후 라이브러리 이미지 유지 → UI 삭제(확인창) 후 행·파일·카운터 정리. 권한 화면에 MEDIA 행(조회·생성·삭제, 수정 칸 "—"). 스크린샷: `adversarial-review/plan/media-library-screenshots/`.

### 이슈 (비자명, `docs/troubleshooting.md`에 기록)
1. `replaceRefs`: 참조 엔티티를 읽은 뒤 벌크 삭제 → `saveAll`이 INSERT를 건너뜀(수정 시 참조 소실). 통합 시험이 처음 잡음.
2. REPEATABLE READ 갭 락 + FK 공유 잠금 + 잠금 순서 — 위 변이 실험으로 3건 모두 실증.
3. 기존 통합 시험들이 배너·팝업의 `storage_key` 컬럼에 의존해 정리에서 NPE — `MediaTestSupport`로 이관.

### 후속
- `banner.storage_key/content_type/file_size`(NULL 허용 상태의 화석 컬럼)·`popup` 같은 컬럼 DROP + `UNIQUE` 정리(별도 PR).
- 로드맵 ⑧ 완료 반영(`/updateRoadmap`)은 머지 뒤.
- 이름 변경·교체·태그 등은 범위 밖으로 남겼다.
