# PLAN — 팝업 관리 (로드맵 Top 8 ⑦)

> 상태: v5 — 적대적 리뷰 5라운드 **ship**, 구현·시험·실기 검증 완료(커밋·PR 전, 2026-10-12)(codex `gpt-6.1-sol`; 1라운드 P2×5·P3×1 수용 5·부분 수용 1, 2라운드 P2×2·P3×1·3라운드 P2×1·P3×1·4라운드 P2×2 전부 수용). 구현 전 사용자 승인 필요 — **스키마 변경(V39·V40)** 과 **인가 정책 변경(`/popups/*/image` GET·HEAD 무인증 공개)** 을 수반한다.

## 개정 이력

- 5라운드: **ship — 새 실질 결함 없음**(P1·P2 없음, P3 없음. codex `gpt-6.1-sol`).
- v5 변경(4라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2, 전부 수용, 결정 필요 0건):
  - R4-1(P2 `MenuExposureSidebarIntegrationTest`가 BANNER·SITE_PAGE READ만 시드하면서 모든 `PERMISSION:*` 메뉴 노출을 기대해, V40 팝업 메뉴가 생기면 실패 — 정찰이 이 시험을 짚고도 파급 목록에 빠뜨림. 시험 코드 75~83행·163~165행을 직접 확인): 쟁점 15 파급에 명시 + 시드를 `DELEGABLE` 목록에서 생성.
  - R4-2(P2 순차 `revision` 시험은 `@Lock(PESSIMISTIC_WRITE)`가 빠져 증가분이 유실되는 결함을 못 잡음): 쟁점 15에 `PopupConcurrencyIntegrationTest` 추가 — 첫 수정 커밋 보류 중 두 번째 수정이 대기하는지 `LockWaitObserver`로 확인, `revision == n+2` 단언, `@Lock` 제거 변이 실험.

- v4 변경(3라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×1·P3×1, 전부 수용, 결정 필요 0건):
  - R3-1(P2 롤백 기간에 구버전 수동 회수 ⑤를 실행하면 `popup/` 파일이 전부 고아로 삭제됨 — 신버전 문서의 `popup/` 제외만으로는 롤백 공백이 남음): 쟁점 14 변경 — "롤백 기간에는 구버전 ⑤ 실행 금지" 경고 명시 + 반례 검증 계획(수정한 ⑤는 보존, 옛 ⑤는 삭제 대상으로 잡음).
  - R3-2(P3 `height` 빈 문자열이 바인딩 400이 된다는 v3 가정이 Jackson 3.1.7 실제 동작과 다름 — `""`·`"  "`가 `null`로 읽혀 "null만 해제" 계약을 위반): 쟁점 11 변경 — 필드 한정 엄격 역직렬화(전역 설정 불변) + `null` 성공·빈 문자열/공백 400 MockMvc 시험. `width`·`posX`·`posY`는 `@NotNull`.

- v3 변경(2라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×2·P3×1, 전부 수용, 결정 필요 0건):
  - R2-1(P2 팝업에 `display:flex`를 주면 `hidden` 기본 숨김이 덮여 스크립트가 꺼져도 HTML형 팝업이 보이고 닫기도 안 될 수 있음 — v2의 flex 레이아웃이 새로 만든 문제): 쟁점 9 변경 — `.home-popup[hidden]{display:none !important}` + flex는 `:not([hidden])`에만. 규약 시험으로 규칙 존재 고정, 실기에 스크립트 비활성 HTML형 비표시·닫은 뒤 비표시 추가.
  - R2-2(P2 375×667에서 `pos_y=2000`이면 최대 높이 152px인데 200자 제목이 줄바꿈되는 머리 영역이 예산을 넘겨 "버튼이 항상 보인다" 보장이 깨짐): 쟁점 7 변경 — 제목 한 줄 말줄임·머리/바닥 `flex:none`·스크롤 영역만 축소, 높이 예산 144px ≤ 예약 160px을 문서에 명시. 200자 제목 × 폭 200 × `pos_y` 최대 실기.
  - R2-3(P3 쟁점 6은 수정 JSON의 기간 빈 문자열을 400이라 하고 쟁점 11은 `null`/빈 문자열 = 해제라 적어 모순): 쟁점 11 변경 — 필드별 해제 계약으로 분리(링크는 빈 문자열도 해제, 기간은 `null`만, 높이는 `null`만, 등록 폼은 빈 입력란 = 없음) 후 각각 시험.

- v2 변경(1라운드 반영 — codex `gpt-6.1-sol` `codex exec`, P2×5·P3×1, 결정 필요 0건):
  - R1-1(P2 가로만 클램프하면 `pos_y`가 크거나 이미지·본문이 길 때 닫기·숨김 버튼이 화면 밖으로 나감, 이미지 검증은 종횡비를 제한하지 않음): 쟁점 7 변경 — 세로도 클램프하고 팝업을 `머리(닫기) / 스크롤 영역 / 바닥(오늘 하루 보지 않기)`의 3단 flex로 만들어 **조작 버튼은 스크롤 영역 밖에 고정**한다. 최대 높이는 뷰포트 기준. 실기에 375×667·가로 회전·`pos_y` 최대·긴 이미지·긴 본문 추가.
  - R1-2(P2 `v=수정시각(epoch초)`는 같은 초의 두 번째 수정과 같아 "수정하면 다시 표시"가 깨짐): 쟁점 2·9 변경 — `popup.revision`(int, 행 락 아래 수정마다 +1)을 두고 `data-version`으로 쓴다(정적 페이지의 `version`과 같은 발상). 같은 초 연속 수정 시험 추가.
  - R1-3(P2 행 수 무제한 + 배너식 전체 목록·썸네일 즉시 로딩은 관리 화면 비용 제한이 없음): **부분 수용** — 쟁점 2·13 변경. 페이지네이션은 **기각**(≤30행 화면에 API 형태·UI가 커지는 과설계). 대신 **총 행 수 소프트 상한 30**(409, 가드 행 없는 `count` 검사라 동시 등록 시 요청 수만큼 넘을 수 있음을 명시 수용 — 관리 화면 비용 제한용이지 정확한 계약이 아니다), 목록 응답에서 **본문(`content_html`)을 제외**(상세에서만 반환), 이미지 썸네일은 `loading="lazy"`.
  - R1-4(P2 `loading="lazy"`는 스크립트가 꺼지면 적용되지 않아 숨겨진 이미지가 내려받아짐 — HTML 표준): 쟁점 9 변경 — 서버는 이미지 URL을 `data-src`로만 내보내고 스크립트가 **보이기로 결정한 팝업의 `src`만** 채운다. 스크립트가 없거나 실패하면 팝업도 이미지 요청도 없다. 미요청 시험 추가.
  - R1-5(P2 `normalize`·`requireValid`는 DB 저장 가능 범위를 막지 못함 — 0001년 입력은 DB 오류, 배너 DTO를 복사하면 같은 빈틈을 복제): 쟁점 6 변경 — 기간은 `String`으로 받아 `parseKstLocal`(엄격) → `requireInRange`(2000~2099) → `normalize` → `requireValid` 순서로 검증한다(게시글 예약 게시와 같은 순서). 생성·수정 각각 범위 밖 400 시험. 정찰 중 `common/display/DisplayStatus`·`DisplayPeriod.statusOf`가 이미 있음을 확인해 **상태 열거형을 팝업 전용으로 따로 두려던 쟁점 6 결정을 철회**하고 공용 것을 쓴다. 배너 쪽 같은 빈틈은 이 작업에서 고치지 않고 "후속"에 기록(`requireInRange`를 배너가 호출하지 않음을 grep으로 확인).
  - R1-6(P3 팝업 조회 실패 시 메인이 정상이라는 fail-soft 계약의 실패 주입 시험이 없음): 쟁점 15 변경 — `PublicHomePopupIsolationIntegrationTest` 추가(선례 `PublicHomeFooterIsolationIntegrationTest`).

- v1: 최초 작성(정찰 → 쟁점 15개 결정). 로드맵 "⑦ 팝업 관리"의 확정 결정(2026-10-07)을 그대로 따른다 — 노출 화면은 공개 메인(`/`)만, 엔티티·테이블·권한·화면은 배너와 분리, 노출 기간 판정과 이미지·링크 검증은 ②에서 만든 공용 코드 재사용, 이미지 공개 경로 `/popups/{id}/image`, "오늘 하루 보지 않기"는 브라우저 측에서만 처리.

## Context

관리자가 노출 기간·위치·크기·내용(이미지 또는 HTML 본문)을 지정한 팝업을 등록하면, 방문자가 공개 메인(`/`)에서 레이어 팝업으로 본다. 선행 ⓪(HTML 편집기·sanitizer)·②(공개 메인·배너)가 끝났고, ⑧ 미디어 라이브러리가 이 항목의 저장 구조를 옮겨 가므로 ⑧의 선행 조건을 푼다.

### 정찰 요약 (읽은 근거)

| 읽은 곳 | 알게 된 것 |
|---|---|
| `admin/banner/*` + `CLAUDE.md` | 배너 = 행(제목·링크·`storage_key`·기간·`use_yn`·`ord`) + `banner_lock` 가드 행. 생성·삭제·순서 저장을 가드로 직렬화하는 이유는 **개수 상한(10)과 `ord` 계산** 때문이다. 단건 수정은 행 락만. 이미지 교체 없음(삭제 후 재등록). |
| `common/display/DisplayPeriod` | `normalize`(분 절단)·`requireValid`(`start < end`)·`isActive`(`[start,end)`)가 이미 공용. 팝업이 재사용하라고 만들어 둔 코드다. |
| `common/image/ImageFileValidator` | `validate(bytes, type, Limits)` 공용. 한도는 호출 측이 `Limits`로 정한다. |
| `common/storage/LocalDiskFileStorage` | `RESERVED_NAMESPACES = {"profile","banner"}` — 예약 네임스페이스만 루트 API(`load/open/delete`)가 거부한다. **팝업을 추가해야 한다.** `FileStorageTransactionSupport`의 `deleteOnRollback`·`deleteAfterCommit` 네임스페이스 오버로드가 있다. |
| `publicweb/home/*`, `publicweb/banner/*` | 공개 조회는 노출 조건이 **쿼리에 고정**된 전용 서비스가 한다. 이미지 컨트롤러는 문자열 id 직접 파싱 → 모든 실패가 같은 404, `PublicImageStreamer`(배너가 먼저 쓰고 팝업이 재사용하라고 명시). 푸터는 `HomeController`가 **별도 호출로 예외를 격리**한다(PLAN-static-page R1-3). |
| `templates/public/home.html` + `PublicHomeTemplateConventionTest` | 메인은 `th:utext`·`[(`·`<script`·`storageKey`가 **한 곳도 없다**고 시험이 고정한다. 팝업은 sanitize된 HTML(`utext`)과 "하루 보지 않기" 스크립트가 필요해 이 규약과 정면으로 부딪힌다 → 쟁점 9. |
| `common/html/ContentBodyPolicy`·`HtmlContentSanitizer` | 정적 페이지가 `allowImages=false` 변형(`sanitizePage`·`sanitizeForPageOutput`)을 이미 만들었다. 링크는 `rel="noopener noreferrer nofollow"`·`target=_blank` 강제. 저장 시·출력 시 모두 정리(멱등). |
| `config/SecurityConfig` | `/banners/*/image` GET·HEAD `permitAll` + `/banners`·`/banners/**` `denyAll` + 마지막 `anyRequest().denyAll()`. 정적 `/css/**`·`/js/**`는 이미 공개 → **팝업 스크립트는 정적 파일이라 보안 설정 추가가 필요 없다.** CSP 헤더는 설정돼 있지 않다. |
| `application.yml` 레이트리밋 | `public-home` 120/60초, `banner-image` 240/60초. 팝업 이미지 규칙을 추가해야 한다. |
| `admin/permission/AdminFeature` | 새 `DELEGABLE` 기능은 `gatePatterns`만 적으면 `SecurityConfig` 루프가 게이트를 만든다(`BANNER`·`SITE_PAGE` 선례, `SecurityConfig` 무변경). |
| 시험 파급 | 새 `DELEGABLE` 기능을 열거하는 시험이 있다: `AdminSidebarAdviceSnapshotTest`(ADMIN `myPermissions` 전 동작)·`AdminPermissionEvaluatorTest`(`grantedActionKeys` 진리표)·`SecurityConfigTest`(게이트 블록)·`AdminActionTypes`↔`log/manage.html` 라벨 동기화 시험. `MenuExposureSidebarIntegrationTest`는 기능별 시드를 직접 넣는다. |
| `docs/deployment.md` | 수동 회수 ⑤는 `profile/`·`banner/`를 **제외**하고 ⑤-3이 배너 테이블로 대조한다. **`popup/`을 제외하지 않으면 정상 팝업 이미지가 고아로 삭제된다**(배너 R4-1과 같은 함정). |

## 핵심 쟁점과 결정

### 쟁점 1 — 팝업의 종류와 불변성
- 선택지: (A) 종류 둘(이미지형·HTML형)을 한 테이블에 두고 종류는 생성 후 **불변**. (B) 한 팝업이 이미지와 본문을 모두 가진다. (C) 종류 전환 허용.
- **결정: A.** 로드맵이 "이미지 **또는** HTML 본문"이라 했다. B는 레이아웃·검증 조합이 늘고, C는 종류를 바꾸면 이미지 파일 정리·본문 비우기 경로가 하나씩 더 생긴다. 배너가 "이미지 교체 없음"으로 범위를 자른 것과 같은 이유로 **종류 변경·이미지 교체는 없다**(삭제 후 재등록, ⑧에서 해결).
- 종류별 필수·금지 필드는 서비스가 400으로 강제한다: 이미지형은 이미지 파일 필수·본문 금지·`height` 금지, HTML형은 본문 필수·이미지 파일 금지.

### 쟁점 2 — 스키마: 가드 행·순서·개수 상한을 두지 않는다
- 선택지: (A) 배너처럼 `popup_lock` 가드 행 + `ord` + 개수 상한. (B) 가드·순서·행 수 상한 없음, **공개 조회가 최대 N건만** 자른다.
- **결정: B.** 배너의 가드는 "개수 상한(10)과 `ord` 계산을 직렬화"하려고 생겼다(빈 테이블 경합). 팝업은 순서 개념이 필요 없고(겹쳐 뜨는 순서는 등록 순으로 충분) 행 수를 제한하지 않으면 직렬화할 대상이 없다. 비용 폭탄은 **공개 쪽 상한**으로 막는다(쟁점 8). 요청받지 않은 순서 저장 API·가드 테이블을 만들지 않는다.
- **총 행 수 소프트 상한 30**(`MAX_POPUPS`, 초과 409, R1-3): 가드 행이 없으므로 `count` 검사만 한다 — 동시 등록 시 동시 요청 수만큼 넘을 수 있으나 이 상한은 관리 화면 비용 제한용이라 수용한다.
- `popup` 테이블(V39): `id`·`title`(200)·`revision`(int, 기본 0 — 수정마다 행 락 아래 +1, 쟁점 9)·`popup_type`(`IMAGE`|`HTML`)·`link_url`(500, 이미지형만)·`storage_key`·`content_type`·`file_size`(이미지형, 그 외 NULL)·`content_html`(MEDIUMTEXT, HTML형만)·`pos_x`·`pos_y`·`width`·`height`(NULL 허용)·`display_start`·`display_end`(`datetime(6)`, NULL 허용)·`use_yn`·`create_date`·`update_date`. 하드 삭제. 인덱스 `idx_popup_use_id (use_yn, id)`, `uk_popup_storage_key (storage_key)`(NULL 다수 허용).
- V40: 메뉴 시드(멱등 `WHERE NOT EXISTS`, `/admin/popup/manage`, 최상위 맨 끝) — DDL과 DML을 나누는 V33/V34 선례를 따른다.
- 번호는 구현 시점에 `db/migration` 최대 버전을 다시 확인한다(현재 최대 V38).

### 쟁점 3 — 권한: 새 `DELEGABLE` 기능 `POPUP`
- 선택지: (A) `POPUP`(READ·CREATE·UPDATE·DELETE) 신설. (B) `BANNER` 권한 공유.
- **결정: A.** 로드맵이 "권한은 분리"로 확정했다. `gatePatterns`: `/admin/popup/manage`, `/admin/api/popups`, `/admin/api/popups/**` — `SecurityConfig` 코드 변경 없음. 핸들러는 전부 `@RequirePermission(POPUP, …)`(목록·상세·**관리자 이미지 보기** = READ).
- 롤백 시 권한 부활 주의가 새로 생긴다(쟁점 14).

### 쟁점 4 — 이미지 저장·검증
- **결정**: `FileStorage`의 **`popup` 네임스페이스**(예약 네임스페이스에 추가). 이미지는 png·jpeg·gif, **2MB·한 변 2560px·총 4,000,000픽셀**, 헤더 검사만, 애니메이션 거부(공용 `ImageFileValidator`). 배너의 `LIMITS`는 패키지 전용 상수라 **참조하지 않고 팝업 서비스에 같은 값을 따로 둔다**(두 도메인이 서로의 비공개 상수에 묶이지 않게 — 한쪽 한도를 바꿀 때 다른 쪽이 따라 바뀌면 안 된다).
- 등록은 롤백 시 정리(`deleteOnRollback`), 삭제는 커밋 후 정리(`deleteAfterCommit`) — 배너와 같다.

### 쟁점 5 — HTML 본문: 이미지 불허·팝업 전용 상한·출력 시 재정리
- **결정**: 정적 페이지와 같은 `allowImages=false`. 본문 이미지의 출처·공개 판정이 게시판에 묶여 있고(`content_image`), 이미지가 필요하면 이미지형 팝업을 쓴다. 상한은 팝업에 맞게 작게: **보이는 글자 2,001(=2,000+마지막 문단 종료 1자)·정리 후 50,000바이트·요청 원문 100,000바이트**. `ContentBodyPolicy`에 `sanitizePopup`·`sanitizeForPopupOutput`을 추가한다(기존 게시글·정적 페이지 규칙은 건드리지 않는다).
- **저장 시와 출력 시 모두 정리**한다(멱등). `th:utext`는 이 정리 결과에만 쓴다 — DB를 직접 고쳐 `<script>`·`<img>`를 넣어도 렌더되지 않는다.
- 편집기는 `NoticeEditor.create(…, {allowImages:false})`를 그대로 쓴다(정적 페이지 선례, 편집기 `formats`·툴바·sanitizer 허용 목록 1:1 유지).

### 쟁점 6 — 노출 판정
- **결정**: `useYn ∧ [displayStart, displayEnd)`, 기간이 비면 그쪽은 열림. 규칙은 `DisplayPeriod`가 원본이고 DB 조건은 `PopupRepository.findDisplayable*`가 같은 의미로 고정한다. 판정 시각은 주입된 KST `Clock`을 호출당 1회. **입력 검증 순서(R1-5)**: 시작·종료는 `String`으로 받아 `DisplayPeriod.parseKstLocal`(엄격 — 오프셋·존재하지 않는 날짜·24:00 거부) → `requireInRange`(2000-01-01 이상 2100-01-01 미만) → `normalize` → `requireValid`. 등록(multipart)의 빈 입력란은 "없음"으로 보고, 수정(JSON `PUT`)의 빈 문자열은 400이며 `null`만 해제다. 두 표현의 일치는 `PopupRepositoryDataJpaTest`가 저장·flush·초기화·재조회 후 경계(시작 직전·시작·종료 직전·종료)로 대조한다(배너 `BannerRepositoryDataJpaTest` 방식).
- 관리 응답의 상태 배지(`HIDDEN > SCHEDULED > EXPIRED > ACTIVE`)는 서버가 계산하고, 공용 `DisplayStatus`·`DisplayPeriod.statusOf`를 쓴다(게시글이 이미 쓴다).

### 쟁점 7 — 위치·크기
- 선택지: (A) 픽셀 오프셋(`pos_x`·`pos_y`) + `width`·`height`. (B) 이름 있는 위치(가운데·좌상단…). 
- **결정: A.** 로드맵이 "위치·크기를 지정"이라 했다. 범위: `width` 200~1000(기본 400), `height` NULL 또는 144~1000(HTML형만 — 본문이 넘치면 내부 스크롤, 이미지형은 이미지 비율을 유지하려고 금지), `pos_x` 0~2000, `pos_y` 0~2000(기본 100, 100). 범위 밖은 400.
- **화면 안 유지는 CSS가 한다(R1-1)**: 폭은 `min(width, 100vw − 16px)`, 왼쪽은 `min(pos_x, 100vw − 폭 − 8px)`, **위쪽도 `top = min(pos_y, 100dvh − 160px)`**로 클램프하고 `max-height = 100dvh − top − 8px`다. 팝업은 `머리(제목·닫기 ×) / 스크롤 영역(내용) / 바닥(오늘 하루 보지 않기·닫기)`의 3단 flex라 **조작 버튼은 스크롤 영역 밖에 있어 어떤 내용 길이·`pos_y`·세로로 긴 이미지(종횡비 제한 없음)에서도 항상 보인다.** 스크롤 영역만 `overflow:auto`, 이미지는 `max-width:100%; height:auto`. **높이 예산(R2-2)**: 머리·바닥은 `flex:none`(축소 불가)이고 머리의 제목은 **한 줄 말줄임**(`white-space:nowrap; overflow:hidden; text-overflow:ellipsis`, 200자 제목이 줄바꿈으로 머리를 키우지 못한다 — 전체 제목은 `title` 속성)이라 머리 40px + 바닥 48px + 스크롤 영역 최소 48px + 여백 8px = 144px ≤ 클램프 예약값 160px이다. 스크롤 영역은 `flex:1 1 auto; min-height:0`으로만 줄어든다. 실기: 375×667, 가로 회전(667×375), `pos_y` 최대, 긴 이미지·긴 본문, **200자 제목 × 폭 200 × `pos_y` 최대에서 닫기·\"오늘 하루 보지 않기\"가 모두 보임**.

### 쟁점 8 — 공개 메인 연동: 최대 5건·fail-soft
- **결정**: 공개 조회는 노출 중인 팝업을 `id` 오름차순으로 **최대 5건**만 돌려준다(`PublicPopupService`, 쿼리에 `LIMIT`이 아니라 `Pageable`로 고정). 5건을 넘게 켜 두면 나머지는 공개 메인에 나오지 않는다 — **관리 목록에 "공개 메인에는 최대 5개까지 표시됩니다"를 노출 중 건수와 함께 안내**한다(조용히 사라지지 않게).
- 팝업은 메인의 **선택 섹션**이다. 푸터처럼 `HomeController`가 **별도 호출로 예외를 격리**해 실패 시 팝업만 빠지고 메인은 정상이다(`getHome()`의 읽기 트랜잭션이 rollback-only로 표시되지 않게 호출을 분리 — PLAN-static-page R1-3와 같은 이유). `PublicHomeView`는 건드리지 않고 컨트롤러가 모델에 `popups`를 따로 넣는다.
- 공개 모델 `PublicPopup`은 관리 응답을 재사용하지 않는다(노출 여부·저장 키·기간이 딸려 나가지 않게). 링크는 출력 시 `SafeUrls`로 한 번 더 거른다(배너 `PublicBanner.from`과 같은 방어). HTML형 본문은 `sanitizeForPopupOutput`을 거친 값만 담는다.

### 쟁점 9 — 렌더링·"오늘 하루 보지 않기"·메인 템플릿 규약
- **충돌**: `PublicHomeTemplateConventionTest`는 메인에 `th:utext`·`<script`가 없어야 한다고 고정한다. 팝업은 둘 다 필요하다.
- **결정**: 팝업 마크업을 **별도 조각** `templates/public/fragments/home-popups.html`로 두고 `home.html`은 `th:replace`로 포함만 한다. `home.html` 자체의 금지 규약은 그대로 유지하고, **조각에 대한 규약 시험을 새로 둔다**: (a) `th:utext`는 정확히 1곳(`popup.bodyHtml`), (b) 인라인 `<script>` 본문 없음 — 외부 스크립트 `<script th:src="@{/js/public/home-popup.js}">` 1개만, (c) `[(`·`storageKey`·`th:utext` 외 비이스케이프 출력 없음. 규약을 "없애는" 것이 아니라 **허용 위치를 한 곳으로 좁혀 일부러 늘린다**(시험 주석이 요구한 절차).
- 팝업은 서버가 `hidden`으로 그리고, 스크립트가 숨김 여부를 판단해 보인다. **`display:flex` 같은 작성자 CSS는 브라우저의 `[hidden]` 기본 숨김을 덮어쓴다(R2-1)** — 팝업 CSS에 `.home-popup[hidden]{display:none !important}`를 두고, flex 레이아웃은 `.home-popup:not([hidden])`에만 적용한다. 규약 시험이 이 규칙의 존재를 고정하고, 실기에서 **스크립트를 끈 상태의 HTML형 팝업 비표시**와 **닫은 뒤 비표시**를 확인한다. 스크립트가 없거나 실패하면 팝업이 **뜨지 않는다**(안전한 쪽으로 실패 — 팝업은 부가 기능). **이미지 URL은 `data-src`로만 내보내고 스크립트가 보이기로 한 팝업의 `src`만 채운다**(R1-4 — `loading="lazy"`는 스크립트가 꺼진 환경에서 적용되지 않아 숨겨진 이미지를 내려받을 수 있다). 그래서 "하루 보지 않기"를 누른 방문자·스크립트 없는 환경은 이미지 요청이 0건이다(실기로 네트워크 요청을 확인).
- **"오늘 하루 보지 않기"**: `localStorage` 키 `cms.popup.hide.{id}`에 `{v: revision, until: 다음 자정(브라우저 로컬, epoch ms)}`를 저장한다. 읽을 때 `v`가 현재 팝업의 `data-version`(= `popup.revision`)과 다르면(관리자가 수정함) 무시하고 다시 보인다. **수정시각이 아니라 `revision`을 쓰는 이유(R1-2)**: 시각은 초 단위라 같은 초의 두 번째 수정이 같은 값이 되어 숨김이 유지된다. `revision`은 수정 서비스가 대상 행 락을 잡은 뒤 +1하므로 수정마다 반드시 달라진다. `localStorage` 접근은 전부 try/catch — 막혀 있으면 저장 없이 매번 보인다. 서버는 이 상태를 모른다(로드맵 확정: 브라우저 측에서만).
- 접근성: 팝업은 `role="dialog"`·`aria-label=제목`, 닫기 버튼, `Esc`로 닫기. 포커스 가두기는 만들지 않는다(모달이 아니라 비차단 레이어 — 과설계 방지).

### 쟁점 10 — 이미지 공개 경로와 인가 정책 변경 (사전 승인 필요)
- **변경**: `SecurityConfig`에 `GET·HEAD /popups/*/image` `permitAll` + `/popups`·`/popups/**` 나머지 `denyAll`을 추가한다(`/banners` 블록과 대칭). **무인증 공개 경로 1개 신설**이다.
- 노출 여부·기간 재검증은 `PublicPopupService`가 쿼리로 한다. 비숫자·0·음수·범위 밖·없는 id·비노출·시작 전·종료 후·**HTML형 팝업의 이미지 요청**·파일 없음이 **모두 같은 404**(존재 여부 비노출). 컨트롤러는 예외를 던지지 않는다. 응답은 저장된 `image/*` 인라인 + `nosniff` + `no-store`, 스트리밍은 `PublicImageStreamer`, 조회(트랜잭션)와 파일 열기(트랜잭션 없음)를 분리해 `open-in-view: false` 계약을 지킨다.
- 레이트리밋 `popup-image` `/popups/*/image` GET·HEAD **120/60초**(메인 1회에 최대 5장이고 숨겨진 팝업은 받지 않아 배너(240)의 절반).
- `docs/deployment.md`의 리버스 프록시 점검 문구에 새 공개 경로를 추가한다.

### 쟁점 11 — 동시성
- 생성: 직렬화할 대상이 없다(쟁점 2). 수정·삭제: 대상 행만 `findByIdForUpdate`. 수정 요청은 종류별 필드를 최종 값으로 검증한다(`PUT` 전체 교체, 배너 R2-2와 같은 의미). **해제 계약은 필드별로 다르다(R2-3)**: 링크(`linkUrl`)는 `null`·빈 문자열 모두 해제(배너와 같음), **시작·종료는 `null`만 해제이고 빈 문자열은 400**(쟁점 6 — 기간을 잘못 읽으면 팝업이 의도보다 오래 노출되므로 엄격), `height`는 `null`만 해제 — **Jackson 3.1.7은 `Integer`로 읽을 때 `""`·`"  "`를 `null`로 바꾼다**(3라운드가 같은 버전으로 재현)이므로 숫자 필드라고 400이 되지 않는다(R3-2). 해당 필드에 한정한 엄격 역직렬화(전용 deserializer 또는 필드 단위 coercion 설정 — 구현 시 같은 Jackson으로 재현해 고른다, **전역 `ObjectMapper` 설정은 다른 API에 영향을 주므로 바꾸지 않는다**)를 쓰고 `null` 성공·`""`·`"  "` 400을 MockMvc로 고정한다. 같은 이유로 `width`·`posX`·`posY`는 `@NotNull`로 빈 값을 막는다. 등록(multipart)은 폼 특성상 빈 입력란을 "없음"으로 본다. 두 쪽을 각각 시험으로 고정한다.
- 삭제와 공개 조회의 경합: 공개 이미지는 행이 사라졌거나 파일이 이미 없으면 같은 404다. 삭제가 롤백되면 행과 파일이 모두 남는다(`deleteAfterCommit`).
- 낡은 편집 화면이 다른 관리자의 변경을 덮는 문제는 배너·메뉴와 같은 한계로 **수용**한다(낙관적 락 없음).

### 쟁점 12 — 감사
- `POPUP_CREATE`·`POPUP_UPDATE`·`POPUP_DELETE`(`targetType=POPUP`, `targetId`=팝업 ID). 제목은 사용자 입력이라 `targetLabel`을 두지 않고, HTML 본문은 감사에 남기지 않는다. 실패 행의 `errorMessage`는 정적 페이지처럼 본문이 섞이지 않게 `safeErrorMessage`를 쓴다 — **본문(HTML)이 예외 메시지에 실릴 수 있는 경로를 구현 때 확인하고**, 그렇지 않다면 배너와 같은 기본 동작을 쓴다(확인 결과는 "구현·검증 결과"에 남긴다).
- `AdminActionTypes` 상수·`ALL`·`log/manage.html` 라벨은 기존 동기화 시험이 고정한다.

### 쟁점 13 — 관리 화면
- `templates/admin/popup/manage.html`(`@AdminPage`): 목록(제목·종류·기간·상태 배지·이미지형은 `loading="lazy"` 썸네일, **HTML형은 본문 없이 종류 표시만** — 목록 응답에 `content_html`을 싣지 않고 수정 버튼을 누르면 상세 API로 가져온다, R1-3) + 등록(`multipart`)/수정 폼. 배너 화면의 날짜 + 시·분 `<select>` 방식과 상태 배지를 그대로 따른다(복사해 팝업에 맞게 줄이되 공용 부분을 추출하는 리팩터링은 하지 않는다). ▲▼·순서 저장은 없다(쟁점 2).
- 종류를 고르면 이미지 업로드 영역 또는 편집기가 나타난다. 위치·크기 입력은 숫자 필드(범위 안내). 버튼은 `myPermissions`(`"POPUP:CREATE"` 등)로 숨기되 서버 판정(403)이 최종이다. 제목은 `textContent`로만 삽입한다.
- **미리보기**: 별도 미리보기 화면은 만들지 않는다(요청 범위 밖). 관리자 이미지 보기(`GET /admin/api/popups/{id}/image`, READ)는 노출 여부와 무관하게 썸네일을 그리려고 필요하다(공개 경로가 비노출을 404로 막는다).
- 편집기 스크립트 의존성(Quill·`notice-editor.js`)은 정적 페이지 화면이 이미 쓰는 것을 그대로 로드한다.

### 쟁점 14 — 배포·롤백 문서
- `docs/deployment.md`에 "팝업" 절을 추가한다: 새 공개 경로, 팝업 이미지는 `popup/` 하위 디렉터리, **수동 회수 ⑤가 `popup/`을 제외하도록 수정하고 ⑤-4가 `popup` 테이블 대조로 따로 회수**한다(비노출·만료 팝업 행도 보존), 롤백 시 `POPUP` 권한 행 정리 트랜잭션(배너 절과 같은 형식).
- **롤백 중 구버전 회수 절차 금지(R3-1)**: V38 앱으로 되돌려도 `popup` 테이블·`popup/` 파일은 남는데, 구버전 ⑤는 `content_image`·`post_attachment`만 보존하고 `profile/`·`banner/`만 제외하므로 **`popup/` 파일 전부를 고아로 삭제한다**(재배포해도 복구되지 않음). 기존 `ready` 가드는 V32 성공만 보므로 이를 막지 못한다. 그래서 문서에 "롤백 기간에는 구버전 문서의 수동 회수 ⑤를 실행하지 않는다 — 팝업 제외가 들어간 신버전 절차만 쓴다"를 배너 절의 같은 경고 옆에 명시하고, 신버전 ⑤ 블록에는 `popup/` 제외를 넣는다. 검증: 가능하면 일회용 스키마·볼륨에서 **수정한 ⑤가 `popup/` 파일을 보존하고, 제외가 없는 옛 ⑤가 같은 입력에서 삭제 대상으로 잡는 반례**를 실행해 확인한다(배너 R4-1 방식). Docker를 쓸 수 없으면 그 사실을 기록하고 완료를 주장하지 않는다.
- `LocalDiskFileStorageTest`에 `popup` 예약 네임스페이스 시험을 추가한다(`banner`와 같은 형태).

### 쟁점 15 — 테스트 계획
- 단위: `PopupServiceTest`(종류별 필수·금지 필드, 범위 검증, 정규화, 삭제 콜백).
- DB: `PopupRepositoryDataJpaTest`(경계 대조), `PopupMigrationTest`(V39 컬럼·V40 멱등·기존 행 영향 없음).
- 통합: `PublicHomePopupIsolationIntegrationTest`(R1-6 — 팝업 서비스에 예외 주입 → `/` 200·공지/새 글/푸터 유지·팝업만 제거), `PopupApiIntegrationTest`(생명주기·감사·파일 정리·링크·이미지·본문 XSS 벡터 제거·공개 메인 노출/비노출/기간 경계·`MutableClock`, 수정마다 `revision` +1 — **같은 초 연속 수정에서도 다름**, 기간 범위 밖(0001년 등)·오프셋·24:00 400, 소프트 상한 409, 목록 응답에 본문 없음), `PopupPermissionMatrixIntegrationTest`, `PublicPopupImageIntegrationTest`(모든 404 동일·헤더), 필요 시 실서버 스트리밍 시험 1건(커넥션 0 가드).
- 규약: `PublicHomePopupTemplateConventionTest`(쟁점 9 — 추가로 `<img>`에 `src` 속성이 직접 없고 `data-src`만 있음을 고정), `PublicHomeTemplateConventionTest`는 변경 없이 계속 통과해야 한다.
- 파급: `AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`·`SecurityConfigTest`·`LocalDiskFileStorageTest` 보강. **`MenuExposureSidebarIntegrationTest`(R4-1)**: `setBoardRead(true)`가 BANNER·SITE_PAGE의 READ 행만 넣는데 모든 `PERMISSION:*` 메뉴가 보이길 기대하므로(75~83행·163~165행), V40 팝업 메뉴가 생기면 `POPUP` READ 시드가 없어 실패한다 — `POPUP` READ를 추가한다. 다음 기능에서 같은 누락이 반복되지 않게 시드를 `AdminFeature`의 `DELEGABLE` 기능 목록에서 생성하도록 바꾼다(이 시험 안의 작은 변경, 새 추상화 없음).
- **동시성(R4-2)**: `PopupConcurrencyIntegrationTest` — 수정 서비스가 대상 행 조회에 거는 **`@Lock(PESSIMISTIC_WRITE)` 선언**이 `revision` 증가분 유실을 막는 것이므로(메서드 이름이 아니라 선언), 독립 트랜잭션 두 개로 같은 행을 수정한다. 첫 수정의 커밋을 보류한 동안 두 번째 수정이 행 조회에서 **대기**하는지 `LockWaitObserver`로 확인(`Thread.sleep` 금지 — `src/test` 지침)하고, 둘 다 끝난 뒤 재조회한 `revision == n+2`를 단언한다. **변이 실험**: `findByIdForUpdate`의 `@Lock`을 지우면 이 시험이 실패함을 확인해 결과에 기록한다.
- 실기(Playwright): 팝업 등록 → `/` 진입 시 표시 → "오늘 하루 보지 않기" 후 새로고침 시 미표시(숨겨진 이미지 미요청 포함) → 수정 시 다시 표시(같은 초 연속 수정 포함) → 기간 만료 후 미표시, 스크립트 비활성·"하루 보지 않기" 상태에서 팝업 이미지 요청 0건, 375×667·가로 회전·`pos_y` 최대·긴 이미지/본문에서 닫기 버튼 가시성, MANAGER 권한 부여·회수·403, 비노출 팝업 이미지 404 직접 접근.

## 수정해야 할 파일

신규: `admin/popup/{controller,domain,dto,repository,service}`·`CLAUDE.md`, `publicweb/popup/`(이미지 컨트롤러·서비스·DTO), `V39__create_popup.sql`·`V40__seed_popup_menu.sql`, `templates/admin/popup/manage.html`, `templates/public/fragments/home-popups.html`, `static/js/public/home-popup.js`, `static/css/public/home.css`(팝업 규칙 추가), 시험 위 목록.
수정: `AdminFeature`(POPUP), `AdminActionTypes`·`log/manage.html`, `LocalDiskFileStorage`(예약 네임스페이스), `ContentBodyPolicy`(팝업 규칙), `SecurityConfig`(`/popups` 블록), `application.yml`(레이트리밋), `HomeController`(팝업 별도 호출)·`home.html`(조각 포함), `docs/deployment.md`, `docs/migration-guide.md`(V39~V40 실패 복구), 루트 `CLAUDE.md`·`config/CLAUDE.md`·`permission/CLAUDE.md`·`publicweb/home/CLAUDE.md`·`banner/CLAUDE.md`(자매 참조).

## 수용한 한계

- 총 행 수 상한 30은 가드 없는 소프트 상한이라 동시 등록 시 조금 넘을 수 있다.
- "오늘 하루 보지 않기"는 브라우저 저장소에만 있어 시크릿 창·저장소 삭제·다른 기기에서는 다시 보인다(로드맵 확정).
- 노출 팝업이 5개를 넘으면 나머지는 공개 메인에 나오지 않는다(관리 화면이 안내).
- 이미지 교체·종류 변경 없음(삭제 후 재등록, ⑧에서 해결).
- 낡은 편집 화면의 덮어쓰기(낙관적 락 없음).
- 이미지는 `no-store`라 팝업이 보일 때마다 다시 받는다(5개·2MB·레이트리밋으로 제한).
- 스크립트가 꺼진 환경에서는 팝업이 뜨지 않는다.

## 후속(발견, 이번 범위 밖)

- ~~배너 등록·수정 경로는 `DisplayPeriod.requireInRange`를 호출하지 않는다.~~ → **해소**(별도 PR #132, 2026-10-12): 재현해 보니 `0001-01-01` 같은 값은 DB 오류(500)가 아니라 **201/200으로 그대로 저장**됐다(위 "DB 범위 오류로 이어질 수 있다"는 추정은 이 값에서는 틀렸다). 배너 등록·수정에 범위 검증을 추가했다. 임시 탐침으로 관측한 **남은 한계**(수정 DTO가 `LocalDateTime` 바인딩이라 빈 문자열이 200으로 기간 해제, 오프셋 `Z`가 200으로 받아들여진 뒤 무시)는 API 계약이 바뀌는 엄격 파싱 전환이라 별도 작업이다.

## 구현·검증 결과 (2026-10-12)

### Context
공개 메인(`/`)에 노출할 레이어 팝업(이미지형·HTML형)을 관리하는 기능을 구현했다. 승인된 v5 계획 그대로이며 스키마 변경(V39·V40)과 인가 정책 변경(`/popups/*/image` GET·HEAD 무인증 공개)이 포함된다. 브랜치 `feat/popup-management`(master에서 출발, 커밋·PR 전).

### 핵심 확정 사항 (v5 그대로 구현)
`POPUP` 기능 단위 위임(`DELEGABLE`, 배너와 권한 독립) · 종류 불변(IMAGE/HTML, 종류별 필수·금지 필드 400) · 가드 행·순서 없음 + 소프트 상한 30 + 공개 메인 최대 5개 · 수정마다 `revision` +1(행 락 `@Lock` 필수) · 이미지 `popup` 네임스페이스(예약) · HTML 본문 이미지 불허(`sanitizePopup`, 출력 시 재정리) · 기간 `String` 엄격 파싱 + 범위 검증 · 수정의 해제 계약 필드별(링크만 빈 문자열 허용, 기간·높이는 `null`만, `height`는 세터가 원시 값 검사) · 팝업은 별도 조각 + 외부 스크립트, 이미지는 `data-src`만, `[hidden]` 보호 CSS · 조회는 별도 호출로 예외 격리 · 문서 `deployment.md`(⑤ `popup/` 제외·⑤-4·롤백 기간 구버전 ⑤ 금지)·`migration-guide.md`.

### 구현 파일
- 마이그레이션: `V39__create_popup.sql`, `V40__seed_popup_menu.sql`
- 관리 측 `com.cms.admin.popup`: `domain/{Popup,PopupType,PopupLimits}`, `repository/PopupRepository`, `service/PopupService`, `dto/{request/PopupCreateRequest,PopupUpdateRequest, response/PopupResponse,PopupImageDownload}`, `controller/{PopupController,PopupPageController}`, `CLAUDE.md`; 화면 `templates/admin/popup/manage.html`
- 공개 측 `com.cms.publicweb.popup`: `PublicPopupService`, `PublicPopupImageController`, `dto/{PublicPopup,PublicPopupImageRef}`; `HomeController`(팝업 별도 호출), `templates/public/home.html`(조각 포함), `templates/public/fragments/home-popups.html`, `static/js/public/home-popup.js`, `static/css/public/home.css`
- 공통·설정: `AdminFeature.POPUP`, `AdminActionTypes`·`log/manage.html`, `ContentBodyPolicy.sanitizePopup`·`sanitizeForPopupOutput`, `LocalDiskFileStorage`(예약 네임스페이스 `popup`), `SecurityConfig`(`/popups` 블록), `application.yml`(`popup-image` 레이트리밋)
- 시험(신규): `PopupApiIntegrationTest`(21)·`PopupPermissionMatrixIntegrationTest`(7)·`PopupConcurrencyIntegrationTest`(2)·`PopupFileCleanupIntegrationTest`(2)·`PublicPopupImageStreamingServerTest`(5)·`PopupMigrationTest`(4)·`PopupRepositoryDataJpaTest`(7)·`PublicHomePopupTemplateConventionTest`(6)·`PublicHomePopupIsolationIntegrationTest`(1) + `SecurityConfigTest` 6건·`LocalDiskFileStorageTest` 1건 추가
- 시험(수정): `AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`(ADMIN 전 동작 키에 POPUP), `MenuExposureSidebarIntegrationTest`(위임 READ 시드를 카탈로그 `DELEGABLE`에서 생성)

### 계획과 달라진 점
1. **감사 `errorMessage`(쟁점 12의 "구현 때 확인")**: 본문이 예외 메시지에 실릴 수 있는 경로를 일일이 확인하는 대신 정적 페이지와 같이 **`safeErrorMessage` 고정 문구**를 쓰기로 정했다. `PopupApiIntegrationTest.auditFailureMessageIsFixed`가 입력한 본문이 감사에 남지 않음을 고정한다.
2. **계획에 없던 시험 추가**: `PopupFileCleanupIntegrationTest`(저장소 계층 예외 주입 — 등록 롤백 시 파일 정리, 삭제 롤백 시 행·파일 보존). 계획의 실서버 스트리밍 시험 `PublicPopupImageStreamingServerTest`(5건)는 **처음엔 만들지 않았다가 같은 날 추가했다**(배너 시험을 팝업 경로로 옮김 — 골든 패스·HEAD, 전송 중 DB 커넥션 0, 첫 청크 실패 HTML 500, 미커밋 reset, 커밋 후 연결 중단). 처음엔 공용 `PublicImageStreamer`를 재사용한다는 이유로 갈음했으나 이는 추정이었다 — 팝업 컨트롤러가 조회(트랜잭션)와 파일 열기(트랜잭션 없음)를 올바르게 분리했는지는 팝업 경로에서 직접 확인해야 한다.
3. **`PopupServiceTest`(Mockito 단위)는 만들지 않았다** — 검증 순서·종류별 필수/금지·범위·기간 규칙을 실제 스택 시험(`PopupApiIntegrationTest`)이 더 직접 고정한다.
4. 수정 API에 JSON으로 등록을 보내면 403이 아니라 **415**다(`consumes = multipart` 매핑이 메서드 보안보다 먼저 판정). 정보 노출은 없고 배너도 같다 — 등록의 403은 multipart로 시험한다.

### 검증 결과
- **전체 `./gradlew cleanTest test`(기본 시간대)**: 2053건 실패 0·오류 0·건너뜀 12(스트리밍 시험 5건 추가 전). 스트리밍 시험을 추가한 뒤 다시 전체를 실행해 **2058건 실패 0·오류 0·건너뜀 12**(`LocalDiskFileStorageTest`의 Windows 심볼릭 링크 제약, 기존과 같음). 기존 1995건 + 신규 63건(스트리밍 5건 포함). (UTC 재실행 결과는 아래 "최종 전체 실행".)
- **변이 실험**: `PopupRepository.findByIdForUpdate`의 `@Lock`을 지우면 `PopupConcurrencyIntegrationTest` 2건이 모두 실패한다 — 락 대기를 관측하지 못하고(`실제 DB row-lock 대기를 관측하지 못함`), 8스레드 동시 수정의 `revision`이 +8이 아니라 **+1**에 그친다(증가분 7건 유실). `@Lock`을 복구한 뒤 2건 통과. 4라운드 리뷰(R4-2)가 우려한 "순차 시험은 `@Lock`이 빠져도 통과"를 실제로 반증했다.
- **리뷰 지적의 실재 확인**: 4라운드가 짚은 `MenuExposureSidebarIntegrationTest`는 수정 전에 실제로 실패했다(`granted=true url=/admin/popup/manage exposure=PERMISSION:POPUP expected: true but was: false`).
- **수동 회수 블록 전체 실행(일회용 MariaDB 컨테이너·볼륨, 운영·dev 자원 무관)**: 실제 앱으로 빈 DB를 V1~V40까지 마이그레이션(`Successfully applied 40 migrations`)한 일회용 DB와 정지된 가짜 앱 컨테이너·일회용 볼륨에서, **`docs/deployment.md`의 블록을 awk로 그대로 추출**해(컨테이너·볼륨 이름과 ①의 `make prod-backup`만 치환) 실행했다 — `ready` 가드(V32 성공 이력·표지 파일·앱 정지)·②③④·⑤·⑤-2·⑤-3·⑤-4·⑥ 전부. 시나리오는 파일 13개: 보존돼야 할 7개(살아 있는 글이 참조하는 본문 이미지·게시글 첨부·**활성/비노출/만료 팝업 이미지 3개**·배너·프로필)와 지워져야 할 6개(삭제 글 전용·무참조 본문 이미지, 루트·프로필·배너·**팝업 고아**). **결과: 13개 모두 기대대로**(7 보존·6 삭제, `content_image_usage` 카운터 1건·10바이트로 재계산, `popup` 행 4개 그대로).
- **반례 실행(같은 시나리오·같은 방식으로 master의 옛 문서 블록)**: `popup/` 제외가 없는 옛 블록은 DB에 행이 멀쩡히 있는 **팝업 이미지 4개(활성·비노출·만료·고아)를 전부 삭제**했다(`popup` 행은 그대로 남아 이미지만 사라짐 — 롤백 기간 구버전 ⑤ 금지 경고(R3-1)가 실제 위험임을 확인). 단계 중 한 번 `rm` 단계가 실패했다 — 제 하네스가 Git Bash의 가상 `$PWD`(`/tmp/…`)를 Docker 마운트에 넘긴 환경 문제였고(Windows 경로 + `MSYS_NO_PATHCONV=1`로 치환해 해결), 그 시도에서도 목록 계산은 정확했고 `③` 이후 "행 없는 파일"이 남는 안전한 방향으로만 실패했다(문서 가드의 설계 의도와 일치). 운영 Linux 셸에서는 이 변환 문제가 없다.
- **dev 실기(Playwright, 앱을 8081에서 구동 — 8080은 `cms-app-dev` 컨테이너가 점유)**: V39·V40이 dev DB에 적용됐다(적용 로그 확인).
  - 골든 패스: 관리 화면에서 이미지형(업로드·링크·배치)·HTML형(편집기·높이) 등록 → 공개 `/`에 둘 다 표시, 120자 제목은 말줄임, 이미지는 `/popups/1/image` 200.
  - **"오늘 하루 보지 않기"**: 누르면 `localStorage` `{v:"0", until:<다음 로컬 자정>}` 저장 → 새로고침 후 숨김, `src` 속성 없음(`data-src`만), **`/popups/*` 요청 0건**. 관리 화면에서 그 팝업을 수정(`revision` 0→1)하면 `data-version="1"`이 되어 다시 표시.
  - **스크립트 차단**(`home-popup.js` abort): 팝업 둘 다 `display:none`·폭 0, 이미지 요청 0건(R1-4·R2-1 실증).
  - **화면 안 유지**: 최악 조건(세로로 긴 이미지 300×1800 `posX/posY` 2000·폭 1000, 200자 제목·긴 본문·폭 200·`posY` 2000)을 375×667·667×375·1280×800에서 측정 — 모든 팝업의 닫기 ×·"오늘 하루 보지 않기"·닫기 버튼이 **뷰포트 안**, 내용만 스크롤. 375×667의 최악 팝업 높이 152px로 4라운드 계산(R2-2)과 일치.
  - **권한 경계(실제 로그인 세션, MANAGER 계정 생성)**: 권한 없음 → 페이지·API 403·사이드바에 항목 없음. `POPUP` READ+UPDATE 부여 → 페이지·목록 200, 수정 200, 삭제 403, **배너 API 403(권한 독립)**, 사이드바 항목 표시, 화면의 `MY_PERMISSIONS`는 `POPUP:READ`·`POPUP:UPDATE`. 회수 → 같은 세션의 다음 요청부터 목록·수정·페이지 403. 권한관리 매트릭스에 `POPUP`이 자동으로 나타남.
  - **익명 직접 접근**: 비노출·만료·HTML형·없는 ID·비숫자 `/popups/{id}/image` 404, `/popups`·`/popups/1` 302, 익명 POST 403, 노출 중 이미지 GET·HEAD 200(`nosniff`·`no-store`·`image/png`). 공개 메인에는 노출 중 팝업만 나옴.
  - **XSS(실제 스택)**: `<script>`·`<img onerror>`·`javascript:` 링크를 넣은 HTML 팝업 → 렌더된 DOM에 `script`·`img` 없음, 위험 링크는 `href` 제거, 안전한 링크는 `rel="noopener noreferrer nofollow" target="_blank"` 강제, `window.__xss` 미설정. `Esc`로 가장 위 팝업이 닫힘. 콘솔 오류 0건.
- 실기 데이터는 검증 뒤 정리했다(팝업 5건 API 삭제 — 파일 포함, 임시 MANAGER 계정·권한·감사 행 삭제). **dev DB는 V40까지 올라간 채 남는다**(되돌리지 않음 — 추가 전용 마이그레이션).

### 이슈
- 정찰이 짚지 못한 시험 파급을 4라운드 리뷰가 잡았다(`MenuExposureSidebarIntegrationTest`). 새 `DELEGABLE` 기능을 열거하는 시험이 3곳이라는 사실은 `com.cms.admin.popup`·`permission`의 `CLAUDE.md`에 기록했다.
- 첫 시험 실행에서 3건이 실패했다 — `LocalDateTime.toString()`이 소수 초를 붙여 엄격 파싱(R1-5)이 400으로 거부한 **시험 쪽 문제**였다(설계대로 동작). 분 단위 포맷 헬퍼로 고쳤다.
- 정리 SQL에 존재하지 않는 컬럼(`actor_member_id`)을 추측해 쓴 시험이 한 번 실패했다 — 다른 시험처럼 감사 로그 기준 id 방식으로 고쳤다.
- 같은 위치·크기로 여러 팝업을 켜면 서로 **겹쳐 뜬다**(관리자가 정한 배치의 결과 — 자동 배치 없음, 수용).

### 후속
- 배너 등록·수정이 날짜 범위(`requireInRange`)를 검증하지 않음(위 "후속(발견)") — 재현은 아직 하지 않았다.
- ⑧ 미디어 라이브러리가 팝업 이미지 저장을 옮겨 갈 때 `popup/` 네임스페이스·`popup.storage_key`·수동 회수 ⑤-4를 함께 다룬다.
- 같은 위치 팝업의 자동 겹침 방지·팝업 순서 지정은 요청이 있을 때 별도로 다룬다(이번 범위 아님).

### 최종 전체 실행
기본 시간대: 코드 리뷰 반영 뒤 전체 재실행 **2058건 실패 0·오류 0·건너뜀 12**(위 "검증 결과"의 2053건은 스트리밍 시험 추가 전).  UTC(`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`, `cleanTest`; 실행 로그에 `Picked up JAVA_TOOL_OPTIONS: -Duser.timezone=UTC`가 찍힌 것을 확인): 스트리밍 시험을 추가하기 전 2053건 실패 0, **추가·코드 리뷰 반영 뒤 전체 2058건 실패 0·오류 0·건너뜀 12**(PR 생성 직후 재실행으로 확인 — 브라우저 시험 4건 추가 전).

### 코드 리뷰 루프 (2026-10-12, codex `gpt-6.1-sol`)
- **1라운드**: P2 1건 — 높이 허용 범위 하한(100px)이 레이아웃 최소 높이(머리 40 + 내용 최소 48 + 바닥 48 = 136px)보다 낮아, 100~135px로 등록한 HTML 팝업의 바닥 "오늘 하루 보지 않기" 버튼이 `overflow:hidden`에 잘린다. **수용**(제 결함 — 2라운드 계획 리뷰의 높이 예산이 `pos_y` 클램프만 다뤘고 실기는 높이 240만 써서 놓쳤다). 반영: ① `PopupLimits.HEIGHT_MIN` 100→144(서버·관리 화면 입력 `min`·레이블·스키마·`CLAUDE.md`) — 100으로 저장했는데 144로 보이는 불일치를 막으려고 입력 계약을 레이아웃에 맞췄다, ② `home.css`의 `.home-popup:not([hidden])`에 `min-height: 144px`(DB 직접 수정으로 작은 값이 들어와도 버튼이 잘리지 않게 하는 방어선), ③ 시험: `PopupApiIntegrationTest`(143 → 400, 144 → 201, 수정 JSON도 143 → 400)·`PublicHomePopupTemplateConventionTest`(`min-height ≥ 136px` 규칙 존재). 실기: DB로 높이 100 팝업을 직접 넣어 **선언 100px → 표시 144px, 두 버튼이 팝업 안**을 확인했고, 같은 화면에서 `min-height`만 해제하면 **버튼 하단 229 > 팝업 하단 200(29px 잘림)**이 재현됐다(결함 실재). 임시 행은 삭제했다.
- **2라운드**: P2 1건 — 새 팝업 작성 중 HTML형에서 높이를 범위 밖 값(예: 100)으로 입력한 뒤 이미지형으로 전환하면, 높이 입력이 `d-none`으로 숨겨져도 **활성 상태로 남아** 브라우저 기본 폼 검증(`min`/`max`)이 제출을 막고 숨겨진 invalid 입력은 포커스할 수 없어 안내 없이 등록이 실패한다. **수용**(1라운드에서 하한을 100→144로 올리며 100~143이 무효가 되어 발생 가능성이 커졌다). 반영: `lockUi()`가 현재 종류(수정 중이면 대상 종류, 아니면 라디오 선택)가 HTML형일 때만 높이 입력을 활성화하고, 종류 전환 핸들러가 `lockUi()`를 다시 호출한다. 실기(Playwright): 수정 후 — HTML형 높이 100 입력 → 이미지형 전환 시 높이 입력 **비활성**, 이미지 등록 성공(0→1행), HTML형으로 되돌리면 다시 활성. 수정 전 상태(입력을 강제로 활성) — 폼 `checkValidity()`가 false("값은 144 이상이어야 합니다")이고 등록이 조용히 차단되어 행이 늘지 않음(결함 실재). 이 관리 화면 동작은 처음엔 자동 시험이 없었다(브라우저 폼 검증은 MockMvc로 재현되지 않는다) — 같은 날 **Playwright for Java**(사용자 승인, 새 테스트 의존성 `com.microsoft.playwright:playwright:1.63.0`)로 `PopupAdminFormBrowserTest` 4건을 추가했다(종류 전환 시 높이 입력 비활성·폼 유효·이미지 등록 성공, 종류를 오가며 활성 상태 추종, 높이 143·1001은 브라우저가 막고 144는 등록, 수정 모드의 종류별 입력 상태). 변이 실험: 숨겨진 높이 입력의 비활성화를 제거하면 4건 중 3건 실패, 높이 입력 `min`을 144→100으로 바꾸면 1건 실패. CI에는 `installPlaywrightBrowsers` 단계를 추가했다. 실기 데이터는 삭제했다.
- **3라운드**: 지적 0건 — 통과(정적 검토, codex는 읽기 전용이라 테스트 미실행. 코드 변경 뒤 전체 시험은 아래 "최종 전체 실행"에서 별도 확인).
