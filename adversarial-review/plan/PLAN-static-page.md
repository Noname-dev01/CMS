# PLAN — 정적 페이지 (약관·개인정보처리방침·소개 등) (로드맵 Top 8 ⑤)

> 상태: v6 — 적대적 리뷰 5라운드 **ship**(전 라운드 codex gpt-6.1-sol `codex exec`), 사용자 승인(2026-10-11) 후 **구현·시험·실기 검증 완료, PR #129로 머지(2026-10-11)**. 결과는 문서 끝 "구현·검증 결과".

## 개정 이력

- 5라운드: **ship — P1·P2 없음, P3 1건을 fail-closed로 처리.**
- v6 변경(5라운드 반영 — P3×1, 결정 필요 0건):
  - R5-1(P3 `int` 최댓값에서 `+1` 오버플로 시 음수 개정·`version`이 저장될 수 있음): 부분 수용 — 개정 번호와 `version` 증가를 도메인 메서드에서 `Math.incrementExact`로 하여 오버플로 시 **쓰기 전에 `ArithmeticException`으로 실패**(트랜잭션 롤백, 페이지·개정 불변)하게 한다. 단위 시험 1건(경계에서 예외·상태 불변). 기각: 한계 도달용 409 응답 계약과 본문/메타/no-op 세 갈래 통합 시험 — 20억 번째 저장은 도달 불가능한 규모(초당 1회로도 약 68년)라 계약을 늘리는 것은 과설계이고 데이터 손상만 막으면 충분하다.
- v5 변경(4라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×1·P3×1, 결정 필요 0건):
  - R4-1(P2 구간 조회의 `hasNext` 조건이 배타 하한 기준과 어긋나 개정 21건에서 1번 개정으로 갈 수 없음): 쟁점 4 변경 — 부등식을 하나로 고정한다: 쪽 `p`(0부터)의 `U = current_revision_no − 20p`, 조회 범위 `max(1, U−19) ≤ revision_no ≤ U`(포함 구간), `hasNext = U > 20`, `U < 1`이면 첫 쪽으로 보정. 시험에 개정 20·21·40·41건 추가(45건만으로는 이 경계를 못 잡는다).
  - R4-2(P3 개정 번호 정규식 `{0,8}`이 `int` 범위보다 좁아 10억 번 이상 개정 링크가 404): 쟁점 4 변경 — `^[1-9][0-9]{0,9}$` 통과 후 `Integer.parseInt`로 파싱하고 `NumberFormatException`(범위 초과)은 404. 큰 번호 시험은 구간 계산뿐 아니라 컨트롤러 검증과 상세 열람까지 포함(스키마 `int` 최댓값 개정).
- v4 변경(3라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×1·P3×1, 결정 필요 0건):
  - R3-1(P2 정리 전 원문 크기 제한 없음 — sanitizer가 원문 전체를 파싱해 거대한 `data:` 이미지 등으로 파싱 비용을 키울 수 있음): 쟁점 8 변경 — 페이지 생성·수정 DTO의 `content`에 `@MaxUtf8Bytes(600_000)`(정리 후 상한 500,000보다 큼, 게시글의 `@MaxUtf8Bytes(200_000)` 선례)를 둬 sanitizer 호출 **전에** 400으로 거부한다. 정리 후 500,000 초과는 별도 400. 시험: 원문만 상한 초과인 요청이 파싱 전에 400.
  - R3-2(P3 `MAX_PAGE=1000` 보정이 20,020건 너머 오래된 개정을 목록에서 못 보게 함): 쟁점 4 변경 — 개정 목록은 OFFSET이 아니라 **개정 번호 구간 조회**(`revision_no ≤ current − page×20` 이고 `> current − page×20 − 20`, PK 범위)로 읽는다. 쪽 번호에 `MAX_PAGE`가 필요 없고 비용이 쪽 번호와 무관하며 전 이력이 도달 가능하다. 음수·비숫자·마지막 쪽 초과는 첫 쪽으로 보정(R2-1의 `MAX_PAGE` 규칙 대체). 산술은 `long`. 구간 계산 단위 시험은 `current`가 큰 값(예: `int` 최댓값)에서도 경계가 맞음을 고정하고, 통합 시험은 45건 fixture.
- v3 변경(2라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×2·P3×1, 결정 필요 0건):
  - R2-1(P2 `page`가 음수·거대 값이면 `PageRequest` 예외/과도한 OFFSET): 쟁점 4 변경 — 서비스가 `page < 0 || page > MAX_PAGE(1000)`이면 0으로 보정한다(`PublicBoardService.normalizePage`와 같은 규칙). 음수·1000·1001·`int` 최댓값·비숫자 시험.
  - R2-2(P2 실패 감사의 `errorMessage`에 저장·커밋 예외의 SQL·입력값이 남을 수 있음): 쟁점 14 변경 — 세 쓰기 액션에 `@AdminActionLogged.safeErrorMessage` 고정 문구를 지정한다(쪽지 선례). 대가: 400·409의 구체 사유가 감사 행에서 사라진다(HTTP 응답에는 그대로 나가므로 수용). 서버 로그의 프레임워크 예외 출력은 이 속성의 보호 범위가 아니라는 쪽지의 한계 서술을 그대로 따른다. 사용자 입력 표식을 심은 저장·커밋 예외 주입 시험으로 FAIL 행에 표식이 없음을 고정.
  - R2-3(P3 시험 계획의 폐기된 `baseRevisionNo`): 시험 계획 정정 — 동일한 최신 `version`으로 서로 다른 변경을 동시에 요청, 개정 번호는 같고 `version`만 오른 메타 변경 이후 경합도 실제 DB 통합시험으로 고정.
- v2 변경(1라운드 반영 — codex gpt-6.1-sol `codex exec`, P1×1·P2×4·P3×1, 결정 필요 0건):
  - R1-1(P1 개정 번호만으로는 노출 설정 같은 메타 변경 경합을 못 잡아 낡은 화면이 비공개 페이지를 재공개): 쟁점 2·10 변경 — `site_page.version`을 추가하고 본문·노출·푸터 **어느 변경이든 +1**, 수정 요청의 `baseRevisionNo`를 `version`으로 대체.
  - R1-2(P2 삭제에 낡은 화면 검사가 없어 방금 저장된 수정까지 지움): 쟁점 6·10 변경 — 삭제도 `version` 필수, 불일치 409. "뒤 요청은 404" 문장 정정.
  - R1-3(P2 푸터 조회 예외가 `getHome()` 읽기 트랜잭션을 rollback-only로 만들 수 있음): 쟁점 11 변경 — 푸터는 별도 서비스 호출(자체 트랜잭션)로 읽고 예외 격리는 컨트롤러에서 한다. 조회 실패 주입 시험 추가.
  - R1-4(P2 개정 목록 무제한): 쟁점 4·13 변경 — 공개 개정 목록 페이지당 20건, 관리자 상세는 최근 20건 + 총 개수, 요약·푸터는 `content`를 읽지 않는 프로젝션. 다수 개정 fixture 시험.
  - R1-5(P2 V37 부분 적용 복구): 구현 계획 2·시험 계획 변경 — `docs/migration-guide.md`에 V37~V38 실패 복구 절차, 복구 시험 추가(V33~V34 선례).
  - R1-6(P3 감사가 작성자 기록을 대체한다는 설명 부정확): 쟁점 2 문구 정정("개정별 작성자 추적은 보장하지 않는다") + 쟁점 14에 감사 라벨 `rev=N`(시스템 생성값) 추가. 작성자 컬럼 추가는 기각: 요청된 기능이 아니고 감사 로그가 행위 기록이라는 기존 정책과 같다.
- v1: 최초 작성(정찰 → 쟁점 14개 결정).

## Context

실배포에는 개인정보처리방침·이용약관 같은 고정 페이지가 사실상 필수다. ADMIN 또는 위임받은 MANAGER가 슬러그·제목·HTML 본문·노출 여부를 관리하고, 방문자는 `/pages/{slug}`로 현재 버전을, 개정 이력 화면에서 이전 버전을 본다. **저장할 때마다 개정이 쌓인다**(로드맵 확정 결정 2026-10-07: HTML 편집기, 슬러그 소문자·숫자·하이픈 + 길이 상한 + 예약어 차단, 개정 이력 보존·공개).

### 정찰 요약 (읽은 근거)

- **가장 가까운 선례는 배너**(`admin/banner`, `publicweb/home`): 기능 단위 위임 `DELEGABLE`(`AdminFeature.BANNER`), 게이트는 `SecurityConfig`의 DELEGABLE 루프가 카탈로그 `gatePatterns`에서 자동 생성, 동작별 판정은 핸들러의 `@RequirePermission`, 감사는 `@AdminActionLogged` + 결과 객체의 `getId()`, 메뉴 시드는 DDL과 분리된 멱등 DML 마이그레이션(V33 DDL + V34 시드), 사이드바·`myPermissions`·권한관리 화면의 기능 표는 카탈로그에서 자동 파생.
- **공개 선례는 `publicweb/board`**: 경로 변수를 String으로 받아 직접 파싱(바인딩 실패가 전역 JSON advice로 새는 것 방지), 공개 조건 재검증은 서비스, 실패는 전부 같은 404(`error/404` 뷰), 컨트롤러는 예외를 던지지 않는다(`PublicWebExceptionAdvice`가 HTML 500). `SecurityConfig`는 `GET/HEAD permitAll` + 같은 패턴 `denyAll`.
- **본문 파이프라인**: `HtmlContentSanitizer`(jsoup 허용 목록, 고정점 반복으로 멱등, `img`는 `/content-images/{id}`만 남김) + `ContentBodyPolicy`(보이는 글자 10,001자·정리 후 200,000바이트 상한, `contentFormat="HTML"` 표식 필수). 출력 템플릿의 `th:utext`는 한 곳만 허용(`PublicBoardTemplateConventionTest`류 컨벤션 시험). 편집기 `notice-editor.js`는 `formats`·툴바 상수가 파일에 고정돼 있고 이미지 버튼은 `uploadUrl`이 없으면 오류 안내만 한다.
- **본문 이미지는 게시판에 묶여 있다**(`admin/contentimage`): 업로드 출처 `BOARD:{boardId}`, 참조 소유자 `POST`, 공개 판정은 "공개 게시판의 공개 게시글이 참조" 또는 "그 게시판의 현재 READ 권한자". 정적 페이지가 이미지를 쓰려면 새 출처·소유자·공개 판정·업로드 엔드포인트·개정별 참조 관리가 필요하다.
- **공개 공통 레이아웃/푸터가 없다**: `templates/public/*`는 페이지마다 독립 HTML(CSS도 `home.css`·`board.css`·`notice.css`). 푸터 링크를 노출할 곳은 현재 공개 메인뿐이다.
- 마이그레이션 최대 버전 V36(`post.publish_start/end`). 레이트리밋 규칙은 `application.yml`의 `cms.rate-limit.rules`. 감사 타입은 `AdminActionTypes` + `templates/admin/log/manage.html` 라벨이 동기화 시험으로 잠겨 있다. `Clock`은 KST 주입 하나.
- 영향받는 기존 시험(예상): `SecurityConfigTest`(스텁 컨트롤러·공개/거부 케이스), `AdminSidebarAdviceSnapshotTest`(ADMIN `myPermissions`가 BANNER 키 집합과 정확 일치), `AdminFeatureTest`·`AdminEndpointAuthorizationConventionTest`(DELEGABLE 순회 — 자동으로 새 기능 포함), 감사 타입 동기화 시험, `PublicHomeTemplateConventionTest`·`PublicHomeIntegrationTest`(푸터).

## 설계 제약 (명문화)

- `publicweb`은 `@AdminPage` 미부착. 공개 컨트롤러는 예외를 던지지 않는다(던지면 404가 아니라 HTML 500). 공개 템플릿의 이스케이프 없는 출력은 본문 한 곳뿐.
- 시각은 주입된 `Clock`(`LocalDateTime.now(clock)`), 엔티티는 `LocalDateTime now`를 인자로 받는다. 엔티티 `@Setter` 금지·연관관계 매핑 없음(ID 컬럼만). 조회는 JPQL/QueryDSL, 생성자 주입, DTO 경계.
- 새 컨트롤러 인가 선언 컨벤션(`AdminEndpointAuthorizationConventionTest`): 위임 기능 API는 `@RequirePermission`, 페이지 컨트롤러는 `@AdminPage`이고 선언을 붙이지 않는다.
- 인가 정책 변경(`SecurityConfig` `/pages/**` 공개)은 **사용자 승인 후에만** 반영한다(승인 단계에서 별도 고지).

## 쟁점과 결정

### 쟁점 1 — 개정 저장 단위: 저장마다 vs "개정 게시" 버튼
- 선택지: (A) 저장 1회 = 개정 1건. (B) 초안/게시 상태를 두고 "개정 게시" 때만 개정 생성.
- **결정: A**(로드맵 확정 결정 "저장할 때마다 버전을 남기고"). B는 초안 상태·미리보기·게시 권한이 새로 필요해 과설계. 단 **내용이 같은 저장은 개정을 만들지 않는다**: 정리(sanitize)된 제목·본문이 현재 개정과 같으면 개정 번호를 올리지 않고 메타(노출·푸터)만 반영한다(이때도 메타가 실제로 바뀌면 `version`은 오른다. 아무것도 안 바뀌면 `version`도 그대로 — 200 + 현재 상태)(재저장·더블클릭이 이력을 부풀리지 않게).
- 리스크: 오타 수정도 공개 개정이 된다. 수용(사용자 확정 결정의 귀결).

### 쟁점 2 — 데이터 모델
- 선택지: (A) 페이지 행에 현재 본문, 개정 테이블에 과거 본문(중복 저장). (B) **개정 테이블만 본문을 보유**하고 페이지 행은 `current_revision_no`만 가진다.
- **결정: B.** 현재 본문과 개정 1건이 어긋날 수 없고 MEDIUMTEXT 중복이 없다. 대가: 현재 본문 조회가 `page ⨝ revision(current_revision_no)` 조인이 된다(PK `(page_id, revision_no)` 조회라 비용 무시 가능).
- 스키마(V37, 2문 DDL):
  - `site_page(id AUTO_INCREMENT PK, slug varchar(50) NOT NULL UNIQUE, current_revision_no int NOT NULL, version int NOT NULL, use_yn bit NOT NULL, footer_yn bit NOT NULL, create_date, update_date datetime(6))` — 슬러그·노출·푸터는 **개정되지 않는 페이지 메타**다. `version`은 낡은 화면 검출용 변경 버전으로 본문·노출·푸터 중 **무엇이든 바뀌면** +1 한다(`current_revision_no`는 본문 개정에만 오른다 — 둘은 다른 값이다).
  - `site_page_revision(page_id bigint NOT NULL, revision_no int NOT NULL, title varchar(200) NOT NULL, content MEDIUMTEXT NOT NULL, create_date datetime(6) NOT NULL, PK(page_id, revision_no), FK page_id → site_page(id) RESTRICT)`. 작성자 컬럼은 두지 않는다 — **개정별 작성자 추적은 보장하지 않는다**(감사 로그는 행위와 페이지 ID, 개정 번호 라벨만 기록하며 저장 실패 시 누락될 수 있다).
- 엔티티는 연관관계 매핑 없이 ID 컬럼만(프로젝트 규약). 개정 번호 중복의 최종 방어선은 복합 PK.

### 쟁점 3 — 슬러그
- 형식 `^[a-z0-9]+(-[a-z0-9]+)*$`, 길이 2~50. 정규화: 앞뒤 공백 제거 후 **검증만**(자동 소문자화 없음 — 대문자 입력은 400, 입력 의도와 저장값이 어긋나지 않게). 예약어 차단: `revisions`·`new`·`edit`·`index`(현재 `/pages` 아래 정적 형제 경로는 없지만 로드맵 확정 결정이며, 앞으로 생길 고정 경로 이름을 비워 둔다). 중복은 409.
- **슬러그는 생성 후 변경할 수 없다**(수정 요청에 슬러그 없음). 변경을 허용하면 이미 공유된 URL이 깨지고 리다이렉트 표를 따로 관리해야 한다. 바꾸려면 새 슬러그로 만들고 기존을 삭제한다.
- 동시 생성: 사전 `existsBySlug` 검사 + 유니크 키 위반(`DataIntegrityViolationException`)을 409로 변환(같은 슬러그 동시 등록의 패자는 409). DB 콜레이션이 `utf8mb4_general_ci`라 대소문자를 구분하지 않지만 슬러그는 소문자만 허용되므로 영향이 없다.

### 쟁점 4 — 공개 URL
- `GET /pages/{slug}` 현재 버전, `GET /pages/{slug}/revisions?page=N` 개정 목록(**페이지당 20건**, 최신순, `page`는 String으로 받아 파싱 실패는 0, 음수·마지막 쪽 초과도 서비스가 0으로 보정 — 단 `MAX_PAGE`는 두지 않는다. 개정 번호는 연속이라 OFFSET 대신 **번호 구간 조회**로 읽는다: 쪽 `p`(0부터)는 `U = current_revision_no − 20p`일 때 `max(1, U−19) ≤ revision_no ≤ U`(포함 구간, 최대 20건)인 PK 범위이며, 비용이 `p`와 무관하고 전 이력에 도달할 수 있다. `hasNext = U > 20`, `U < 1`이면 첫 쪽으로 보정), `GET /pages/{slug}/revisions/{no}` 특정 개정. 현재 개정 번호로 접근해도 그 개정을 그대로 보여 준다(리다이렉트 없음 — 단순). 개정 목록·푸터·관리 목록 조회는 본문(`content`)을 읽지 않는 DTO 프로젝션만 쓴다. 이전 개정 화면에는 "이전 버전입니다 · 현재 버전 보기" 안내를 띄운다.
- 경로 변수는 전부 String으로 받아 직접 검증한다: 슬러그는 위 정규식·길이, `no`는 `^[1-9][0-9]{0,9}$`를 통과한 뒤 `Integer.parseInt`(범위 초과는 `NumberFormatException` → 404, `int` 최댓값 개정까지 열람 가능). 형식 위반·없는 슬러그·비노출 페이지·없는 개정(`no`>현재 개정 번호 포함)은 **같은 404**.

### 쟁점 5 — 시행일 표시
- **별도 "시행일" 필드를 두지 않는다.** 개정 목록·화면에는 개정을 저장한 시각(`revision.create_date`)을 "개정일"로 표시한다. 법적 시행일이 저장일과 다르면 본문에 직접 쓴다. 시행일 필드·예약 게시는 요청되지 않은 기능이다.

### 쟁점 6 — 삭제
- 개정 단독 삭제는 **허용하지 않는다**(이력의 의미가 없어진다). 페이지 삭제는 **하드 삭제**(개정 포함, 한 트랜잭션: 개정 먼저 → 페이지)이며 `DELETE` 동작 권한과 화면이 가진 `version`이 필요하다(그 사이 다른 관리자가 수정했으면 409 — 방금 저장된 수정을 모르고 이력 전체를 지우지 않게). 소프트 삭제 상태를 만들지 않는다 — 비공개 수단은 `useYn=false`가 이미 있다.
- 알려진 한계: 개정에 실수로 넣은 민감 정보를 **그 개정만** 가릴 방법이 없다(페이지 비노출·삭제뿐). 개정별 숨김은 후속 과제.

### 쟁점 7 — 본문에 이미지를 허용하지 않는다 (범위 결정 — 승인 단계에서 고지)
- 선택지: (A) 정적 페이지 본문에서 이미지 불허. (B) 콘텐츠 이미지에 `PAGE` 출처·소유자 추가.
- **결정: A.** B는 업로드 출처 enum, 참조 소유자, 공개 판정(개정마다 참조), 업로드 엔드포인트·권한, 용량 카운터, 롤백 정리까지 `contentimage` 도메인 전체를 건드리고 ⑧ 미디어 라이브러리가 어차피 이를 재설계한다. 약관·방침·소개는 텍스트 중심이다.
- 구현: `HtmlContentSanitizer.sanitize(html, boolean allowImages)` 오버로드 추가(기존 `sanitize(html)`은 `true`로 위임 — 동작 불변). `false`면 `filterImages`가 **모든 `img`를 제거**한다. 멱등성은 유지된다. 저장 시와 **출력 시 모두** `false`로 정리한다(DB 직접 수정으로 들어온 `img`도 렌더하지 않는다). 편집기는 `NoticeEditor.create(…, { allowImages: false })` 옵션으로 `image` 포맷·툴바 버튼·파일 붙여넣기/끌어놓기 업로드를 끈다(기본값 `true`라 게시판 화면 불변).

### 쟁점 8 — 본문 길이 상한을 페이지용으로 분리
- 기존 `ContentBodyPolicy`의 보이는 글자 10,001자는 게시글 기준이다. 개인정보처리방침은 한국어로 1만 자를 넘기기 쉽다.
- **결정**: 페이지는 보이는 글자 **50,000자**, 정리 후 **500,000바이트**(한글 3바이트 × 5만 자 = 150KB + 태그 여유). 컬럼은 MEDIUMTEXT라 충분. **원문 상한**: 생성·수정 DTO의 `content`에 `@MaxUtf8Bytes(600_000)`(정리 후 상한보다 큼)를 둬 sanitizer가 원문을 파싱하기 **전에** 400으로 거부한다(게시글 DTO의 `@MaxUtf8Bytes(200_000)` 선례 — 위임 MANAGER도 대상). `ContentBodyPolicy`에 상한을 인자로 받는 내부 메서드를 두고 기존 공개 메서드는 기존 상수·메시지를 그대로 쓴다(게시글 동작 불변). 오류 메시지는 상한 값을 포함해 생성.

### 쟁점 9 — 권한: 기능 단위 위임 `SITE_PAGE`
- `AdminFeature.SITE_PAGE(DELEGABLE, "정적 페이지", READ·CREATE·UPDATE·DELETE, menuUrls=["/admin/site-page/manage"], gatePatterns=["/admin/site-page/manage", "/admin/api/site-pages", "/admin/api/site-pages/**"])`. ADMIN은 항상, MANAGER는 그 회원의 허용 행(쓰기는 READ 의존). 신규 MANAGER는 권한 0개.
- 핸들러 분류: 목록·상세 = READ, 등록 = CREATE, 수정 = UPDATE, 삭제 = DELETE. `SecurityConfig` 관리자 측 변경 없음(DELEGABLE 루프).
- 이름이 `PAGE`가 아닌 이유: `@AdminPage`(Thymeleaf 페이지 컨트롤러 마커)와 혼동 방지.
- 롤백 주의: 새 `DELEGABLE` 기능이라 이전 앱으로 롤백했다가 재배포하면 `SITE_PAGE` 허용 행이 되살아날 수 있다 — `docs/deployment.md`의 기존 정리 트랜잭션 절차에 항목을 추가한다.

### 쟁점 10 — 동시성과 낡은 화면
- **수정**: 첫 DB 조회가 페이지 행 `SELECT … FOR UPDATE`(`findByIdForUpdate`). 잠금 아래에서 요청의 `version`과 `site_page.version`을 비교해 다르면 **409**(다른 관리자가 그 사이 본문·노출·푸터 중 무엇이든 저장함 — 새로고침 안내). 같으면 변경이 있을 때 `version + 1`, 본문이 바뀌었으면 개정 번호 = `current_revision_no + 1`로 INSERT하고 페이지 행을 갱신한다. 개정 번호 중복은 잠금으로 직렬화되고 복합 PK가 최종 방어선. **메타만 바꾸는 저장도 같은 `version` 검사를 거친다** — 개정 번호만으로 검사하면 노출 끄기 직후 낡은 화면의 저장이 비공개 페이지를 되살린다(R1-1).
- **삭제**: 페이지 행 `FOR UPDATE` 후 `version` 비교(불일치 409) → 개정 → 페이지 순으로 삭제. 수정이 먼저 커밋됐다면 삭제는 **409**, 삭제가 먼저면 수정은 404다(수정↔삭제 두 순서를 각각 시험).
- **생성**: 잠금 대상 행이 없으므로 슬러그 유니크 키가 직렬화 수단이다(쟁점 3).
- **공개 조회**: 페이지와 현재 개정을 한 쿼리(조인)로 읽는다 — 개정 INSERT와 페이지 UPDATE가 한 트랜잭션이라 "페이지 행은 새 번호인데 개정은 없음" 같은 불일치가 없다.

### 쟁점 11 — 푸터 링크
- 공개 공통 레이아웃이 없다. **결정**: `site_page.footer_yn` 플래그(페이지 메타)로 지정하고, **공개 메인(`/`) 하단 푸터**에만 `footer_yn ∧ use_yn` 페이지를 `id` 오름차순(= 만든 순서)으로 링크한다. 정렬 지정 기능은 두지 않는다(요청된 적 없음, ⑥ FAQ 정렬 패턴과 별개). 푸터는 **선택 섹션이라 실패가 메인을 깨뜨리면 안 된다**. `PublicHomeService.getHome()`이 읽기 트랜잭션 전체이므로 거기에 푸터를 넣고 예외만 잡으면 rollback-only로 반환 시 다시 실패할 수 있다(R1-3). 그래서 푸터는 `PublicSitePageService.findFooterLinks()`(자체 읽기 트랜잭션, 호출자 트랜잭션 없음)를 **`HomeController`가 `getHome()`과 별도로 호출**하고, 예외는 컨트롤러에서 잡아 빈 목록으로 대체(WARN 로그, 예외 클래스명만)한다. 조회 실패 주입 시험으로 `/`가 200이고 기존 섹션이 유지됨을 고정한다. 다른 공개 화면(공지·게시판·정적 페이지 자체)에 푸터를 넣지 않는다 — 공통 레이아웃 도입은 별도 작업.
- 푸터에는 제목(현재 개정 제목)을 `th:text`로 출력한다.

### 쟁점 12 — 공개 인가·레이트리밋 (인가 정책 변경)
- `SecurityConfig`: `GET·HEAD /pages/**` `permitAll`, `/pages`·`/pages/**`의 그 외 메서드 `denyAll`(공개 게시판 `/boards/**`와 같은 구조, 규칙 위치도 거기 옆). 라우트가 `/pages/{slug}`, `/pages/{slug}/revisions`, `/pages/{slug}/revisions/{no}` 셋뿐이므로 라우트 추가만으로 무인증 공개가 되는 구조는 게시판과 같다 — 접근 통제는 `PublicSitePageService`의 공개 조건 재검증.
- 레이트리밋 규칙 `public-page`: `/pages/**` GET·HEAD 120/60초(게시판 목록·상세와 같은 값; 본문 외 자원을 내려주지 않아 별도 엄격 규칙 불필요).

### 쟁점 13 — 관리자 화면
- 메뉴 `정적 페이지 관리`(`/admin/site-page/manage`, V38 시드 — 최상위 맨 끝, 멱등). 한 화면: 페이지 표(슬러그·제목·노출·푸터·개정 번호·수정일·공개 링크) + 등록/수정 모달(슬러그는 등록 때만 입력, 수정 때는 읽기 전용; 제목; 이미지 없는 Quill; 노출·푸터 체크박스) + 상세 모달 하단의 개정 요약(**최근 20건** + 총 개정 수; 번호·제목·개정일; 본문 보기는 공개 링크로 — 비노출 페이지는 링크 없음) + 삭제 확인.
- 화면의 동작 버튼은 `myPermissions`(`"SITE_PAGE:CREATE"` 등)로 숨길 뿐 서버 판정(403)이 최종. 403 메시지는 영문이므로 고정 한국어 문구. 사용자 입력은 `textContent`로만 삽입. 409(낡은 화면)는 "새로고침" 안내.
- API(전부 `@RequirePermission`): `GET /admin/api/site-pages`(목록), `GET /{id}`(본문 + 개정 요약 목록), `POST`(생성, 201 + Location), `PUT /{id}`(수정 — 제목·본문·`contentFormat`·노출·푸터·`version`), `DELETE /{id}?version=N`(`version` 필수 — 누락·비숫자는 400). 응답에는 항상 최신 `version`을 담는다.

### 쟁점 14 — 감사
- `SITE_PAGE_CREATE`·`SITE_PAGE_UPDATE`·`SITE_PAGE_DELETE`, `targetType=SITE_PAGE`, `targetId`=페이지 ID, `targetLabel` 없음(제목·슬러그는 사용자 입력 — 배너와 같은 정책). 응답 객체가 `getId()`를 노출해야 한다. 개정과 로그를 대응시키도록 `getAuditLabel()`로 `rev=N`(시스템 생성 개정 번호; 본문 변경 없는 메타 저장은 `rev=N 유지`, 삭제는 삭제된 마지막 `rev=N`)을 남긴다 — 사용자 입력이 없다. 감사 저장은 최선 노력이라 로그가 빠질 수 있음을 전제한다. 실패(FAIL) 행의 `errorMessage`는 세 액션 모두 `safeErrorMessage` 고정 문구("정적 페이지 등록에 실패했습니다." 등)다 — 저장·커밋 예외 메시지에 SQL·입력값(제목·본문)이 섞여 감사 테이블에 사본이 남는 것을 막는다(쪽지 `AdminMessageService` 선례, 4xx 사유는 HTTP 응답으로만 전달). `AdminActionTypes.ALL`·`log/manage.html` 라벨 동기화.

## 구현 계획 (의존 방향 안쪽 → 바깥쪽)

1. **공통(`common/html`)**: `HtmlContentSanitizer.sanitize(html, allowImages)` 오버로드, `ContentBodyPolicy`에 상한 인자 경로 + 페이지용 진입점. 시험: 이미지 제거·멱등·기존 `sanitize(html)` 불변.
2. **스키마**: `V37__create_site_page.sql`(DDL 2문), `V38__seed_site_page_menu.sql`(멱등 DML). 마이그레이션 시험(`SitePageMigrationTest`: 적용·메뉴 시드 멱등·복합 PK/유니크·FK RESTRICT·부분 적용 실패 복구) + `docs/migration-guide.md`에 "V37~V38 실패 복구" 절차(V33~V34와 같은 방식: 성공 이력이 있는 객체는 보존, 실패·이력 없는 버전의 잔여 객체만 자식→부모 순 DROP 후 `flyway repair`).
3. **관리 도메인(`admin/page`)**: `SitePage`·`SitePageRevision` 엔티티(도메인 메서드), 리포지토리(`findByIdForUpdate`, 현재 개정 조인 조회, 개정 목록), `SitePageService`(생성·수정·삭제·조회, 슬러그 검증, no-op 저장 판정), DTO, 컨트롤러 + `@AdminPage` 페이지 컨트롤러.
4. **권한·감사**: `AdminFeature.SITE_PAGE`, `AdminActionTypes` 3개, `log/manage.html` 라벨.
5. **공개(`publicweb/page`)**: `PublicSitePageService`(공개 조건 재검증·출력 시 재정리·`findFooterLinks`), 컨트롤러(`/pages/**`), 템플릿 3개 + `css/public/page.css`, 공개 메인 푸터(`HomeController`의 별도 호출·`home.html`·`home.css`; `PublicHomeService`는 건드리지 않는다).
6. **설정**: `SecurityConfig` 공개 규칙, `application.yml` 레이트리밋 규칙.
7. **화면**: `templates/admin/page/manage.html`, `notice-editor.js`의 `allowImages` 옵션.
8. **시험·문서**: 아래 시험 계획, 패키지 `CLAUDE.md` 2개, 루트·`config`·`permission` `CLAUDE.md` 갱신, `docs/deployment.md`(롤백·권한 정리), 계획 인덱스.

## 시험 계획

- 원문 상한: 정리 후에는 상한 이하지만 원문이 600,000바이트를 넘는 요청(제거될 태그/`data:` 이미지를 덧붙임)이 sanitizer 호출 전에 400.
- 단위: 슬러그 검증(형식·길이·예약어·대문자), 서비스(no-op 저장은 개정 불변·메타만 변경, 변경 저장은 개정+1, `version` 불일치 409(본문→본문, **메타→본문, 메타→메타**, 수정→삭제, 삭제→수정 각각), 삭제 시 개정 동반 삭제, 존재하지 않는 ID 404, 본문 이미지 제거·상한 400·`contentFormat` 불일치 400).
- 슬라이스: 컨트롤러 검증 400/201/409/403 매핑, `SecurityConfigTest`(`/pages/**` 익명 GET·HEAD 허용, POST/PUT/DELETE 거부, 관리자 게이트 MANAGER 권한 없음 403·ADMIN 통과), `AdminSidebarAdviceSnapshotTest` 갱신.
- DataJpa(Testcontainers): 현재 개정 조인 조회, 푸터 조회(`footer_yn ∧ use_yn`), 개정 PK 중복 거부.
- 통합(실제 스택): 생성→수정→수정→개정 3건 쌓임·공개 `/pages/{slug}`가 최신, 이전 개정 열람, 비노출 시 현재·이력·이전 개정 모두 같은 404, 삭제 후 404, 존재하지 않는 슬러그·형식 위반·비숫자/범위 밖 개정 번호 404 동일(본문·상태 비교), **동시 수정**(같은 최신 `version`을 가진 두 요청이 서로 다른 변경을 동시에 보냄 → 정확히 하나 성공·하나 409, 개정 번호 중복 없음; 메타 변경으로 개정 번호는 같고 `version`만 오른 뒤의 경합도 동일하게 하나만 성공), 동시 생성(같은 슬러그 → 하나 201·하나 409), `BANNER`식 권한 매트릭스(핸들러 5 × 권한 조합, 신규 MANAGER, 교차 회원), 감사 행(타입·targetId, 실패 FAIL), XSS 벡터(`<script>`·`on*`·`javascript:`·`data:` 이미지) 저장/출력 제거, DB 직접 수정으로 넣은 `img`/`script`가 출력되지 않음, 레이트리밋 429.
- 컨벤션: 공개 페이지 템플릿에 `th:utext`는 본문 한 곳뿐(`PublicPageTemplateConventionTest`), 푸터/제목은 `th:text`.
- 마이그레이션 복구: V37 첫 DDL만 적용된 상태(실패 이력 포함)에서 `docs/migration-guide.md` 절차(성공 이력이 있는 객체 보존, 잔여 객체만 자식→부모 DROP, `flyway repair`)로 복구되는 시험(V33~V34 `BannerMigrationTest` 선례).
- 다수 개정 fixture(개정 45건): 공개 개정 목록이 20건씩 3쪽, 관리자 상세 요약 20건 + 총 45, 목록·푸터 쿼리가 `content`를 읽지 않음(프로젝션 클래스 구조로 고정).
- 개정 건수 경계: 20·21·40·41건에서 쪽 수·각 쪽 건수·`hasNext`·1번 개정 도달 가능, `int` 최댓값 개정 번호로 상세 열람(컨트롤러 검증 포함).
- 쪽 번호 경계: `page`가 음수·`2147483647`·마지막 쪽 초과·비숫자일 때 예외 없이 첫 쪽이 렌더되고 404/500이 아님. 구간 계산 단위 시험은 `current_revision_no`가 `int` 최댓값 근처일 때도 20건 단위 경계·마지막 쪽·`hasNext`가 맞음(`long` 산술).
- 감사 비유출: 제목·본문에 표식 문자열을 심은 요청에서 저장 예외·`beforeCommit` 실패를 주입했을 때 FAIL 감사 행의 `errorMessage`가 고정 문구이고 표식이 없음.
- 푸터 격리: 푸터 서비스가 예외를 던지도록 주입했을 때 `/`가 200이고 배너·공지·새 글이 유지됨.
- 변이 실험: (1) 공개 조회에서 `use_yn` 조건 제거 → 시험 실패 확인, (2) `version` 비교 제거 → 낡은 화면 시험(메타→본문 포함) 실패 확인, (3) 출력 시 `allowImages=true`로 바꾸기 → 이미지 출력 시험 실패 확인.
- 실기(Playwright): 페이지 작성→`/pages/{slug}` 노출→수정→이력에서 이전 버전 열람→비노출 전환 시 404→푸터 링크 노출/제거. MANAGER 권한 부여/없음 화면.

## 리스크

1. **인가 정책 변경(무인증 공개 `/pages/**`)** — 사전 승인 필수. 라우트 3개뿐이며 모든 공개 판정은 서비스 재검증.
2. **개정 공개로 인한 정보 잔존**: 실수로 넣은 민감 정보가 이전 개정에 남는다 — 페이지 비노출·삭제만 수단. 수용(후속: 개정별 숨김).
3. **롤백**: V37 이후 이전 앱은 새 테이블을 무시하고 기동한다(테이블 추가뿐). 그러나 `SITE_PAGE` 권한 행이 있는 DB를 이전 앱이 다루면 권한관리 PUT이 "모르는 기능 행"을 건드리지 않아 행이 남을 수 있다 → 재배포 시 정리 트랜잭션 절차 필수(기존 문서 항목에 추가). 메뉴 행(V38)은 이전 앱에도 보이지만 URL이 ADMIN 캐치올에 걸려 404가 아니라 403/404 — 롤백 시 메뉴 비활성화 안내를 문서화.
4. **본문 상한 완화(5만 자)**: 개정마다 최대 ~500KB — 개정이 수백 개 쌓이면 수백 MB. 페이지 수·개정 수 상한은 두지 않는다(관리자 전용 쓰기, 요청된 적 없음). 모니터링 포인트로만 기록.
5. **푸터 정렬 불가**: 만든 순서 고정. 요구가 생기면 `ord` 컬럼을 별도 마이그레이션으로 추가.
6. **스키마 변경 있음**(V37·V38, 신규 테이블 2개 + 시드). 기존 테이블 변경 없음.

## 범위 밖 (명시)

슬러그 변경·리다이렉트, 본문 이미지, 개정 복원/되돌리기·개정별 숨김·개정 단독 삭제, 시행일 필드, 초안/개정 게시 상태, 푸터 정렬·공통 공개 레이아웃, 페이지 검색, 관리자 화면의 과거 개정 본문 조회.

## 구현·검증 결과 (2026-10-11)

### Context
로드맵 Top 8 ⑤. 정적 페이지(약관·방침·소개)를 슬러그·제목·HTML 본문·노출/푸터로 관리하고 `/pages/**`로 현재 버전·개정 이력·이전 개정을 공개한다. 승인 시 고지한 스키마 변경(V37·V38), 인가 정책 변경(`/pages/**` GET·HEAD 무인증), 본문 이미지 불허 범위가 모두 승인됐다.

### 핵심 확정 사항 (v6 그대로 구현)
저장마다 개정(내용 같으면 생략) · 본문은 개정 테이블에만 · `version`(모든 변경 +1)과 개정 번호 분리, 수정·삭제 모두 `version` 필수(409) · 페이지 행 `FOR UPDATE` · 슬러그 불변·예약어 4개 · 이미지 불허(저장·출력 모두) · 본문 5만 자/500KB/원문 600KB · `SITE_PAGE` 기능 단위 위임 · 개정 목록 번호 구간 조회(쪽당 20건) · 푸터는 `HomeController`가 별도 호출·예외 격리 · 감사 `safeErrorMessage`+`rev=N` 라벨.

### 구현 파일
- 공통: `HtmlContentSanitizer.sanitize(html, allowImages)`, `ContentBodyPolicy.sanitizePage`·`sanitizeForPageOutput`(게시글 경로 불변)
- 스키마: `V37__create_site_page.sql`, `V38__seed_site_page_menu.sql`
- 관리: `admin/page/`(`SitePage`·`SitePageRevision`·`SiteSlug`, 리포지토리·프로젝션 레코드, `SitePageService`, `SitePageController`·`SitePagePageController`, DTO), `AdminFeature.SITE_PAGE`, `AdminActionTypes` 3종, `templates/admin/page/manage.html`, `notice-editor.js`의 `allowImages`
- 공개: `publicweb/page/`(서비스·컨트롤러·DTO), `templates/public/page/{detail,revisions}.html`, `css/public/page.css`, 공개 메인 푸터(`HomeController`·`home.html`·`home.css`)
- 설정: `SecurityConfig` `/pages`·`/pages/**`, `application.yml` `public-page`(120/60초)
- 문서: 패키지 `CLAUDE.md` 2개, 루트·`config`·`permission` `CLAUDE.md`, `docs/deployment.md` "정적 페이지", `docs/migration-guide.md` "V37~V38"(표에 V35·V36 행도 함께 채움)

### 검증 결과
- 시험(신규): `PageBodyPolicyTest`·`SitePageDomainTest`·`SitePageApiIntegrationTest`(생명주기·검증·낡은 화면 4종·삭제·`int` 오버플로·감사 고정 문구)·`SitePagePermissionMatrixIntegrationTest`(5×7)·`SitePageConcurrencyIntegrationTest`(8요청 동시)·`SitePageMigrationTest`(V37 부분 적용 실패→repair 복구 포함)·`PublicSitePageIntegrationTest`(개정 20·21·40·41건 경계·`int` 최댓값·동일 404·출력 정리·푸터)·`PublicHomeFooterIsolationIntegrationTest`·`PublicPageTemplateConventionTest`·`PublicSitePageRateLimitIntegrationTest`·`SecurityConfigTest` 확장. 기존 시험 3건(`AdminSidebarAdviceSnapshotTest`·`AdminPermissionEvaluatorTest`·`MenuExposureSidebarIntegrationTest`)은 새 기능 키가 늘어난 기대값만 갱신.
- 변이 실험 3건을 시험이 잡음: 공개 조회에서 `use_yn` 조건 제거(1건 실패), `version` 비교 제거(5건 실패), 출력 정리를 이미지 허용으로(1건 실패).
- 전체 `./gradlew cleanTest test`: 최종 결과는 아래 "최종 전체 실행" 참조.
- dev 실기(Playwright + curl, dev DB에 V37·V38 적용): 메뉴 노출·이미지 버튼 없는 편집기 → 등록(개정 1) → 본문 수정(개정 2) → 공개 `/pages/privacy-policy`가 개정 2 · `/revisions`에 두 건 · `/revisions/1`에 "이전 버전입니다"+옛 본문 · 없는 개정/대문자 슬러그/없는 슬러그 404 · HEAD 200 · CSRF 없는 POST 403 · 익명 관리 API 401 · 메인 푸터 링크 노출. **다른 관리자가 노출을 끈 뒤(`version` 3) 낡은 화면(`version` 2)의 제목 수정은 409**: 페이지는 계속 비노출·개정 2 유지, 익명 현재/이력/이전 개정 전부 404, 푸터 링크 사라짐, 입력한 제목은 폼에 남음, 감사 FAIL 행 고정 문구. 낡은 `version` 삭제도 409 후 재선택한 삭제는 204로 개정까지 삭제(DB 0건 확인). 콘솔 오류는 의도한 409 두 건뿐. 스크린샷은 `adversarial-review/plan/screenshots/static-page-*.png`. 검증 뒤 dev 감사 행은 지웠고 V37·V38은 dev DB에 적용된 채 남는다.
- 보고된 한계(미확인): MANAGER 권한 부여/회수의 **브라우저 화면 확인은 하지 않았다**(권한 매트릭스·세션 즉시 회수는 통합 시험으로만 확인). 감사 실패 문구는 4xx 경로로 확인했고 저장·커밋 예외를 직접 주입한 시험은 없다(쪽지 `AdminMessageInstrumentedIntegrationTest` 같은 주입 시험은 만들지 않음 — 같은 `safeErrorMessage` 메커니즘이고 서버 로그는 보호 범위 밖). 리버스 프록시 본문 크기 제한은 운영에서 확인해야 한다.

### 이슈
- 테스트 환경은 `CMS_RATE_LIMIT_ENABLED=false`라 429 시험을 `cms.rate-limit.enabled=true`를 켠 별도 클래스로 분리했다(처음엔 같은 클래스에 넣어 0건 429로 실패). 토큰 버킷이 요청 중 보충되어 상한은 120~126으로 단언한다.
- 계획과 달라진 점 없음(마이그레이션 번호·구조·API 모두 v6 그대로). `docs/migration-guide.md` 표에 V35·V36 행이 비어 있어 함께 채웠다.

### 후속
개정별 숨김·단독 삭제, 본문 이미지(⑧ 미디어 라이브러리), 공개 공통 레이아웃/푸터와 푸터 정렬, 개정 복원, 시행일 필드, 페이지·개정 수 상한(DB 용량 모니터링), MANAGER 화면 실기.
