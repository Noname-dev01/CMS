# PLAN — 게시글 예약 게시 (노출 시작·종료 시각) (로드맵 Top 8 ④)

> 상태: v5 — 적대적 리뷰 5라운드 **ship**(전 라운드 codex gpt-6.1-sol `codex exec`), 사용자 승인 후 **구현·검증 완료**(2026-10-11). 결과는 문서 끝 "구현·검증 결과". 코드 리뷰·커밋·PR은 사용자 확인 후.

## 개정 이력

- 5라운드: **ship — 새 지적 없음.** 공개 조회 다섯 경로·목록/COUNT 조건 공유·트랜잭션·화면 변경 감지·마이그레이션·롤백 절차를 대조해 추가 결함이 없고, 세터 기반 중복 키 거부(명시적 빈 값 포함)와 엄격 파서의 날짜·24시·오프셋·소수 초 거부를 Jackson 3.1.7·Java 21로 재현해 확인했다. v5 문서 변경 없음.

- v5 변경(4라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×1, 결정 필요 0건. Jackson 3.1.7·Java 21로 재현):
  - R4-1(P2 바깥 `publishPeriod` 키 중복이 중첩 세터 검사를 우회: `{"publishPeriod":{"start":"2026-02-30T10:00"},"publishPeriod":{}}` → 두 번째 객체가 첫 객체를 덮어써 기간이 지워짐): 쟁점 1 변경 — `PostCreateRequest`·`PostUpdateRequest`의 `publishPeriod` 세터도 **두 번째 호출에서 예외**를 던진다(첫 값이 `{}`·명시 `null`이어도 호출 횟수에 포함). 상위 키 중복 요청이 400이고 기존 기간·본문 참조가 유지되는 시험 추가. `title`·`content`·`useYn`의 기존 중복 키 동작은 건드리지 않는다 — 덮어써도 노출 조건이 바뀌지 않아(노출 여부는 `useYn`이 별도) 즉시 공개로 이어지지 않는다.

- v4 변경(3라운드 반영 — codex gpt-6.1-sol `codex exec`, P2×2, 결정 필요 0건. 둘 다 Jackson 3.1.7·Java 21로 재현된 결함):
  - R3-1(P2 서버 파서 예외가 기존 핸들러에서 500): 쟁점 1 변경 — `DisplayPeriod.parseKstLocal`이 `DateTimeParseException`을 잡아 **고정 한국어 메시지의 `InvalidRequestException`**(→ 400)으로 변환한다. 메시지에 입력 원문을 싣지 않는다. 파서 단위 시험.
  - R3-2(P2 `{"start":"2026-02-30T10:00","start":null}` 같은 중복 키가 앞 값을 덮어써 엄격 파싱을 우회하고 기간을 비움): 쟁점 1 변경 — 리뷰어의 "중복 키 거부"는 전역 Jackson 파서 설정이 필요해 다른 API 계약에 영향이 있으므로, 중첩 DTO가 `start`·`end`를 **`@JsonProperty` 세터로 받아 같은 키의 두 번째 호출에서 예외**를 던진다(알 수 없는 키와 같은 방식으로 400). 중복 키 요청이 400이고 기존 기간·본문 참조가 유지되는 시험 추가.

- v3 변경(2라운드 반영 — codex gpt-6.1-sol `codex exec`, P1×2·P2×1, 결정 필요 0건. 리뷰어가 저장소에 고정된 Jackson으로 직접 재현해 확인한 결함 — 세 건 모두 "Jackson의 관대한 역직렬화에 입력 검증을 맡긴 것"이 뿌리):
  - R2-1(P1 `@JsonIgnoreProperties(ignoreUnknown=false)`가 전역 무시 설정을 뒤집지 못해 `{"publishStart":…}` 오타가 빈 객체가 됨): 쟁점 1 변경 — v2의 수단 철회. 중첩 DTO에 `@JsonAnySetter`를 두어 **알 수 없는 키를 만나면 예외**를 던지게 한다(Jackson이 `HttpMessageNotReadableException`으로 감싸 기존 핸들러가 400). MVC 시험으로 400과 DB 불변 확인.
  - R2-2(P1 엄격 패턴이 빈 문자열 `""`을 `null`로 읽어 기간을 비움): 쟁점 1 변경 — 시작·종료를 **`String`으로 수신**하고 서버가 직접 파싱한다(v2의 "거부하지 못하면 전환" 조건을 확정 설계로). 명시적 JSON `null`만 "열림"이고 빈 문자열·공백은 400.
  - R2-3(P2 존재하지 않는 날짜가 다른 날짜로 보정: `2026-02-30`→2월 28일, `24:00`→다음 날 0시): 쟁점 1 변경 — 파서를 `DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)`로 고정한다. 존재하지 않는 날짜·윤년 오류·24시·오프셋·소수 초는 전부 예외 → 400. 파싱은 공용 `DisplayPeriod.parseKstLocal(String)`에 두며 배너는 호출하지 않는다.

- v2 변경(1라운드 반영 — codex gpt-6.1-sol `codex exec`, P1×2·P2×5, 결정 필요 0건. 공개 경로는 계획의 다섯 곳이 전부이고 여섯 번째는 없다고 코드로 확인함):
  - R1-1(P1 중첩 객체의 오타가 기간을 지움): 쟁점 1 변경 — 전역 `fail-on-unknown-properties: false`는 메뉴 계약이라 못 바꾸므로 **중첩 DTO에만 알 수 없는 필드 거부**(400)를 건다. 계약 명시: 키 없음·명시 `null` = 유지, `{}`·`{start:null,end:null}` = 비움, 한쪽만 = 그쪽만 설정. 기간도 "변경 필드 없음" 검사에 포함. 잘못된 요청 뒤 DB 불변 시험.
  - R1-2(P1 `Z`/오프셋이 KST로 오해석): 쟁점 1에 입력 형식 추가 — 오프셋 없는 KST 벽시계 시각만 받고 엄격 패턴으로 `Z`·`+09:00`을 400으로 거부. 실제 MVC 바인딩 시험으로 확인(거부하지 못하면 `String` + 엄격 `LocalDateTime.parse`로 전환). 배너의 같은 취약점은 후속에 기록.
  - R1-3(P2 낡은 초안이 기간 전체를 덮어씀): 기각 — 게시글에는 낙관적 락 버전이 없고 제목·본문·`useYn`도 같은 마지막 저장 우선 규칙이다. 기간만 버전을 넣는 것은 게시글 전체 낙관적 락 도입이라 범위 밖. 리스크에 **명시 수용**으로 기록.
  - R1-4(P2 롤백 운영 절차): 쟁점 5·리스크 변경 — `deployment.md`에 쓰기 동결·원 `useYn` 백업·정리 후 잔여 0건 검증·재전진 복원 규칙을 쓰고, V35→V36 업그레이드 시험을 추가. "V36 DB에서 이전 앱 기동"은 이전 코드를 띄울 수 없어 `ddl-auto: validate`가 여분 컬럼을 허용한다는 근거로 대신(시험 불가를 명시).
  - R1-5(P2 DB 저장 범위): 쟁점 6 변경 — 기간 값은 2000-01-01 이상 2100-01-01 미만(DB `datetime(6)` 범위 안의 제품 범위)이어야 하고 밖은 400. 공용 `DisplayPeriod`에 새 메서드로 두며 배너 호출은 바꾸지 않는다.
  - R1-6(P2 시험 매트릭스): 쟁점 9·완료 기준 변경 — 익명 GET/HEAD vs READ 권한자 미리보기, 한 이미지를 여러 기간의 글이 참조, 양끝 null·시작만·종료만 × 네 쿼리, 초 절단 후 양끝이 같으면 400(원글·참조 불변), `useYn=false`와 예약/만료가 겹치면 HIDDEN.
  - R1-7(P2 성능 추론): 쟁점 5 변경 — "인덱스 불필요" 결론을 철회하고 실기에서 세 분포(유효 밀집·최신 대부분 무효·전부 무효)를 `EXPLAIN`·스캔 행 수·지연으로 측정해 인덱스 필요 여부를 결정한다.
- v1: 최초 작성(정찰 → 쟁점 10개 결정).

## Context

게시글(`Post`)은 `useYn`(노출)과 `deleted`(소프트 삭제)만으로 공개 여부가 정해진다. 로드맵 ④는 게시글에 **노출 시작·종료 시각**을 선택적으로 지정해, 스케줄러 없이 조회 조건만으로 기간 안에서만 공개되게 한다. 공지는 ①-2에서 공지 게시판의 게시글로 흡수됐으므로 공지에 따로 넣지 않고 게시글에 한 번만 넣으면 `/notices/**`도 따라온다.

로드맵 확정 결정(2026-10-07): 적용 대상은 `Post`만 · `useYn` AND 기간 안일 때만 노출(기간이 비면 `useYn`만) · 시각 단위는 분 · 관리자 화면은 예약 글도 모두 보이고 "예약됨/노출 중/만료"를 표시 · 판정 시각은 주입된 KST `Clock`.

이 작업의 위험은 구현량이 아니라 **공개 노출 경로 누락**이다 — 한 경로라도 기간 조건이 빠지면 예약 글이 미리 샌다.

### 정찰 요약 (읽은 근거)

| 사실 | 근거 |
|---|---|
| 게시글 공개 조건이 쓰이는 곳은 **다섯 곳**이다: ① 공개 목록·제목 검색·COUNT = `PostRepositoryImpl.searchPublished` ② 공개 상세 = `PostRepository.findByIdAndBoardIdAndDeletedFalseAndUseYnTrue` ③ 공개 첨부 다운로드 = `PublicBoardService.findPublishedAttachment`(②를 재사용) ④ 공개 메인 공지·새 글 = `PostRepositoryImpl.findLatestPublished` ⑤ **본문 이미지 공개** = `ContentImageRefRepository.existsPublishedPostRef`(JPQL, `p.useYn = true and p.deleted = false`) | `grep "UseYnTrue\|useYn"` 전수(main) — 이 다섯 곳 외에 `useYn`을 공개 판정에 쓰는 곳 없음 |
| `/notices/**`는 `PublicNoticeService`가 `PublicBoardService`에 위임 → 별도 수정 불필요 | `publicweb/notice/CLAUDE.md` "구조" |
| 관리자 통합 검색 `AdminSearchService`의 게시글 섹션은 노출 여부 무관(관리용) → 기간 조건 불필요 | `PostRepositoryImpl.searchForAdminSearch`에 `useYn` 조건 없음 |
| 본문 이미지 응답은 이미 `Cache-Control: no-store` → 만료·예약 글의 이미지가 브라우저 캐시에 남지 않는다 | `PublicContentImageController` 93행 |
| 기간 규칙의 단일 원본이 이미 있다: `DisplayPeriod.normalize`(분 절단)·`requireValid`(`start < end`)·`isActive`(`[start, end)`) — "⑦ 팝업·④ 예약 게시가 재사용한다"고 주석에 적혀 있다 | `common/display/DisplayPeriod.java` |
| 배너가 DB 조건을 JPQL로 고정하고 `BannerRepositoryDataJpaTest`가 저장→flush→clear→재조회로 경계를 대조하는 패턴 | `BannerRepository.findDisplayable*`, `admin/banner/CLAUDE.md` |
| 배너 관리 응답 `status`(HIDDEN>SCHEDULED>EXPIRED>ACTIVE)를 서버 `Clock`으로 계산하고 화면이 라벨·배지로 매핑 | `BannerResponse.statusOf`, `banner/manage.html` 140행 |
| `Post.createDate`는 `datetime(6)`, 마이그레이션 최신은 V35 → 다음은 **V36** | `V29__*.sql`, `ls db/migration` |
| 공개 서비스에는 `Clock`이 없다(`PublicBoardService`·`PublicHomeService`·`PublicContentImageService`) → 주입 필요. 단위 시험은 `@Mock` 기반이라 생성자/주입 대상이 바뀐다 | 각 클래스, `PublicBoardServiceTest` |
| 관리 PATCH는 "null = 기존값 유지" 시맨틱이다 → **기간을 비우는 방법**이 필요하다 | `PostUpdateRequest`, `Post.update` |
| 영향 시험: `PostRepositoryDataJpaTest`(searchPublished·findLatestPublished·UseYnTrue 직접 호출), `PublicBoardServiceTest`(서비스 단위), `PostServiceTest`·`PostApiIntegrationTest`(DTO), `PublicBoardIntegrationTest`·`PublicHomeIntegrationTest`(공개 통합) | `grep` 전수(test) |

## 쟁점과 결정

### 1. 기간 입력 형태 — 중첩 객체 `publishPeriod`, 존재하면 **통째 교체** (결정)

PATCH의 "null = 유지"와 "기간 지우기"를 한 필드로 표현할 수 없다.

| 선택지 | 장단 |
|---|---|
| A. 평평한 `publishStart`/`publishEnd` + null=유지 | 기간을 지울 방법이 없다(탈락) |
| B. 평평한 필드 + null=지움 | 제목만 고치는 기존 클라이언트·시험이 기간을 지운다(회귀, 탈락) |
| C. 평평한 필드 + `clearPublishPeriod` 플래그 | 필드 3개가 서로 얽혀 검증 분기가 늘어난다 |
| **D. 중첩 객체 `publishPeriod {start, end}`** | 객체가 **없으면 유지**, **있으면 start·end 둘 다 교체**(내부 null = 그쪽 열림, 둘 다 null = 제한 없음). 부분 갱신으로 `start ≥ end`가 되는 병합 시나리오가 없다 |

**D를 택한다.** 생성도 같은 객체를 선택 입력으로 받는다(없으면 기간 없음). 응답은 평평한 `publishStart`·`publishEnd`·`status`를 준다(읽기 쉬움). 입력은 중첩, 출력은 평평이라 비대칭이지만 입력 쪽 모호성이 훨씬 비싸다. 객체가 있으면 범위 검사(쟁점 6) → `DisplayPeriod.normalize`(분 절단) → `requireValid`(`start < end`, 아니면 400)를 거친다.

**입력 계약(v2)**:

| 요청 | 결과 |
|---|---|
| `publishPeriod` 키 없음 / 명시 `null` | 기간 유지(PATCH) · 기간 없음(생성) |
| `{}` / `{"start":null,"end":null}` | 기간 비움(제한 없음) |
| `{"start":"…"}` 한쪽만 | 그쪽만 설정, 나머지는 열림(기존 값을 병합하지 않는다 — 통째 교체) |
| 알 수 없는 필드(`{"publishStart":…}` 오타) | **400** — 중첩 DTO에 `@JsonAnySetter` 수신 메서드를 두고 알 수 없는 키가 오면 예외를 던진다(Jackson이 `HttpMessageNotReadableException`으로 감싸 기존 핸들러가 400 응답). 전역 `fail-on-unknown-properties: false`는 메뉴 계약이라 건드리지 않는다. 오타가 조용히 기간을 지우는 것을 막는다. (v2의 `@JsonIgnoreProperties(ignoreUnknown=false)`는 전역 무시 설정을 뒤집지 못해 철회 — 2라운드가 재현으로 확인) |
| 시작·종료의 JSON 값 | **`String`으로 수신하고 서버가 직접 엄격 파싱**한다(v3 확정). Jackson의 `LocalDateTime` 역직렬화는 끝의 `Z`를 떼어 내고, 빈 문자열을 `null`로 읽고, `2026-02-30`·`24:00`을 다른 날짜로 보정하므로 어떤 어노테이션 조합도 신뢰하지 않는다. 명시적 JSON `null`만 "그쪽 열림"이다. 파서는 `DisplayPeriod.parseKstLocal(String)` — `DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm[:ss]").withResolverStyle(ResolverStyle.STRICT)`. **빈 문자열·공백, 오프셋(`Z`·`+09:00`), 소수 초, 날짜만, 존재하지 않는 날짜(2월 30일·윤년 오류), `24:00`, 숫자·배열·객체 값은 전부 400**. 서버가 직접 파싱하므로 `DateTimeParseException`이 바인딩 오류로 감싸지지 않아 기존 핸들러에서 500이 된다 — `parseKstLocal`이 이 예외를 **잡아 고정 한국어 메시지의 `InvalidRequestException`(400)으로 변환**한다(입력 원문은 메시지에 싣지 않는다) |
| 바깥 `publishPeriod` 키의 중복(`{"publishPeriod":{"start":"2026-02-30T10:00"},"publishPeriod":{}}`) | **400** — 두 객체는 다른 인스턴스라 중첩 세터 검사가 발동하지 않고 뒤 객체가 앞 객체를 덮어써 기간이 지워진다. `PostCreateRequest`·`PostUpdateRequest`의 `publishPeriod` 세터도 **두 번째 호출에서 예외**를 던진다(첫 값이 `{}`·명시 `null`이어도 횟수에 포함) |
| 같은 키의 중복(`{"start":"2026-02-30T10:00","start":null}`) | **400** — Jackson은 뒤 값이 앞 값을 덮어써서 나쁜 값이 `null`(= 열림)로 바뀌고 `@JsonAnySetter`도 호출되지 않는다. 전역 파서 설정(`STRICT_DUPLICATE_DETECTION`)은 다른 API 계약을 건드리므로 쓰지 않고, 중첩 DTO가 `start`·`end`를 **`@JsonProperty` 세터로 받아 같은 키의 두 번째 호출에서 예외**를 던진다 |

기간만 바꾸는 PATCH도 유효한 요청이어야 하므로 `PostService.updatePost`의 "변경할 필드가 없습니다" 검사에 `publishPeriod`를 포함한다.

### 2. 판정 시각 — 서비스가 요청당 1회 `LocalDateTime.now(clock)`을 만들어 쿼리에 넘긴다 (결정)

DB `NOW()`를 쓰지 않는다(CLAUDE.md 시각 원천 규약, `ClockUsageConventionTest`가 스캔). 공개 서비스 세 곳에 `Clock`을 주입한다. 한 요청 안에서 여러 쿼리(게시판→게시글→첨부)가 나가도 같은 `now`를 쓴다(경계 순간에 요청 중간에 판정이 뒤집히지 않는다).

### 3. 쿼리 형태 — 시각 인자를 받는 메서드로 **교체**하고 기존 메서드는 제거 (결정)

| 경로 | 변경 |
|---|---|
| ① 목록·COUNT | `searchPublished(boardId, keyword, **now**, pageable)` — SELECT와 COUNT가 같은 조건 객체를 쓰는 기존 구조 유지, 조건 객체에 기간 조건 추가 |
| ② 상세·③ 첨부 | `findByIdAndBoardIdAndDeletedFalseAndUseYnTrue` 삭제 → `findPublishedById(id, boardId, now)`(JPQL `@Query`, 조건 고정) |
| ④ 메인 최신 글 | `findLatestPublished(boardId, excludeBoardId, **now**, limit)` |
| ⑤ 본문 이미지 | `existsPublishedPostRef(imageId, **now**)` — JPQL에 기간 조건 추가 |

기간 조건의 의미는 `(publish_start is null or publish_start <= :now) and (publish_end is null or publish_end > :now)` = `DisplayPeriod.isActive`. 기존 메서드를 **남기지 않아** 시각 없는 우회 경로가 컴파일 단계에서 사라진다(호출자는 전부 이 계획이 고친다). QueryDSL 조건은 한 헬퍼(`publishedCondition(post, now)`)로 ①·④가 공유하고, JPQL 두 곳(②·⑤)은 같은 문구를 쓴다. 네 표현이 같은 의미임은 DB 시험이 경계값으로 고정한다(쟁점 9).

### 4. 본문 이미지 공개 판정에도 기간을 건다 (결정, 가장 새기 쉬운 경로)

예약·만료 글이 본문에 이미지를 넣고 있으면, ⑤에 기간 조건이 없을 때 `/content-images/{id}`가 미리 공개된다 — 글 자체는 404인데 그 글의 이미지 URL을 아는 사람에게는 열린다. `PublicContentImageService`에 `Clock`을 주입해 ⑤에 `now`를 넘긴다. 그 게시판 READ 권한자의 미리보기 경로(관리자 미리보기)는 기간과 무관하게 그대로 유지한다.

### 5. 스키마 — `post`에 NULL 허용 컬럼 2개, 인덱스는 추가하지 않는다 (결정, **스키마 변경**)

`V36__add_post_publish_period.sql`: `publish_start datetime(6) NULL`, `publish_end datetime(6) NULL`(`ALTER TABLE ... ADD COLUMN`, 기존 행은 둘 다 NULL = 기간 제한 없음 → 기존 공개 동작 불변). 인덱스는 **지금 결정하지 않는다**(v2) — 기존 `(board_id, deleted, use_yn, create_date)`가 목록·COUNT를 받치지만, 기간 조건이 붙으면 "최신 글 5건을 찾으려고 예약·만료 글을 많이 건너뛰는" 낮은 적중률 부하가 생길 수 있어 기존 36ms(5만 행) 실측으로는 안전을 증명하지 못한다. 실기 단계에서 5만 행에 **세 분포**(유효 글 밀집 / 최신 글 대부분 무효 / 전부 무효)를 만들어 `EXPLAIN`·스캔 행 수·지연(목록·COUNT·메인 새 글·공지)을 측정하고, 기준(메인 두 쿼리 합 50ms)을 넘으면 그때 인덱스(예: 기간 컬럼 포함)를 같은 V36 안에서 추가한다. 측정 결과는 구현 결과에 기록한다.

**롤백·운영 절차(v2)**: `docs/deployment.md`에 쓰기 동결 → 새 버전 인스턴스 정지 → 기간이 걸린 글의 원래 `useYn` 백업(`select id, use_yn from post where publish_start is not null or publish_end is not null`) → 해당 글 `use_yn=0`으로 변경 → 잔여 0건 검증 쿼리 → 이전 버전 기동 순서와, 재전진 후 백업으로 `use_yn` 복원하는 규칙을 쓴다(이전 버전 관리자는 예약 제약을 모르므로 롤백 기간에는 해당 글을 켜지 말 것). roll-forward를 우선한다. V35 → V36 업그레이드 시험(기존 행 기간 NULL, 기존 공개 동작 유지)을 추가한다. "V36 DB에서 이전 앱 기동"은 이전 코드를 띄울 수 없어 시험하지 않는다 — `ddl-auto: validate`는 엔티티에 없는 여분 컬럼을 허용한다는 점에 의존한다(`BoardMigrationTest`의 이전 앱 호환 시험과 달리 코드 부재로 대체 불가, 문서에 명시).

### 6. 도메인 — `Post.update`에 기간 파라미터, 종료 시각만 있는 글도 허용 (결정)

`Post`에 `publishStart`·`publishEnd` 필드, `update(title, content, useYn, applyPeriod, publishStart, publishEnd, now)` 형태(`applyPeriod=false`면 기간 유지). 한쪽만 있는 기간은 허용한다(배너와 같음: 시작만 = 시작 후 계속, 종료만 = 즉시 시작 후 종료). 과거 종료 시각의 생성은 막지 않는다(생성 즉시 "만료" 상태로 보인다 — 배너와 같은 규칙, 막으면 시험·복사 편집이 번거롭다).

**값 범위(v2)**: 기간 값은 `2000-01-01T00:00` 이상 `2100-01-01T00:00` 미만이어야 하고 밖은 400이다. `LocalDateTime`은 `+10000-01-01` 같은 값을 표현하지만 MariaDB `datetime(6)`는 1000~9999년만 저장하며, DB 예외는 입력 400이 아니라 충돌·서버 오류로 번역된다(`GlobalApiExceptionHandler`의 `DataIntegrityViolation` 핸들러). 공용 `DisplayPeriod`에 `requireInRange`를 **추가**하고 게시글만 호출한다(배너는 바꾸지 않는다 — 후속). 검사 순서는 범위 → 분 절단 → `start < end`.

### 7. 응답 상태 — 공용 `DisplayStatus` + `DisplayPeriod.statusOf` (결정)

`PostResponse`·`PostSummaryResponse` 모두 상태가 필요하다(상세·목록). 우선순위는 배너와 같다: `HIDDEN`(useYn 꺼짐) > `SCHEDULED`(시작 전) > `EXPIRED`(종료됨) > `ACTIVE`. 상태 계산을 DTO 두 곳에 복제하지 않도록 `common/display/DisplayStatus`(enum)와 `DisplayPeriod.statusOf(useYn, start, end, now)`를 둔다. **배너는 건드리지 않는다**(자체 enum 유지 — 무관한 리팩터링 금지; 통합은 후속). `PostService`가 `now`를 1회 산출해 `PostResponse.from(post, now)`·`PostSummaryResponse.from(post, now)`에 넘긴다.

### 8. 관리 화면 — 배너 화면의 날짜+시·분 분리 입력 패턴을 복사 (결정, 과설계 경계)

`posts.html`의 작성/수정 폼에 "노출 시작/종료"(날짜 + 시 + 분 선택, 비우면 제한 없음)와 목록·상세의 상태 배지(노출 중/예약됨/만료/비노출)와 기간 표시를 넣는다. 배너 화면이 `datetime-local`을 피한 이유(달력이 바깥 클릭까지 열려 있음)가 같아 같은 방식을 쓴다. 배너 화면의 인라인 스크립트를 공용 파일로 뽑지 않는다(배너 수정 = 무관한 리팩터링) — 필요한 함수(`readDateTime`/`writeDateTime`)만 `posts.html`에 둔다. 변경 감지는 기존 방식(바뀐 필드만 PATCH)을 유지하되 기간은 시작·종료 중 하나라도 바뀌면 `publishPeriod`를 통째로 보낸다.

### 9. 시험 전략 — 고정 `Clock` + DB 경계 대조 (결정)

- **DB 대조(`PostRepositoryDataJpaTest`)**: 저장 → flush → 영속성 컨텍스트 초기화 → 재조회로 시작 직전/시작/종료 직전/종료 경계를 네 쿼리(`searchPublished`·`findPublishedById`·`findLatestPublished`, JPQL 이미지 판정은 통합에서)에 대해 `DisplayPeriod.isActive`와 대조.
- **서비스 단위**: `PublicBoardServiceTest`는 `Clock`을 고정해 `now`가 쿼리로 전달됨을 검증.
- **통합(Testcontainers + 고정 `Clock`)**: 신규 `PostPublishPeriodIntegrationTest` — 테스트 전용 `@Primary` 가변 `Clock`(`MutableClock`)으로 경계 시각마다 **목록·검색·COUNT(총건수 일치)·상세·첨부 다운로드·본문 이미지·공개 메인(공지/새 글)·`/notices` 목록·상세·첨부**를 모두 점검. 기간 NULL 글의 회귀, 시작 ≥ 종료 400(생성·수정), PATCH `publishPeriod` 유지/교체/비움, 응답 `status`.
- **확장 매트릭스(v2, R1-6)**: ① 첨부·본문 이미지는 **익명 GET과 HEAD**를 각각 시험하고, 본문 이미지는 **그 게시판 READ 권한자(ADMIN 포함) 미리보기**를 따로 시험한다(예약·만료 글의 이미지도 권한자는 200, 익명은 404 — JPQL 기간 조건이 틀려도 미리보기 분기가 200을 주어 오류를 가리지 않도록 익명 요청으로 판정). ② **같은 이미지를 기간이 다른 여러 글이 참조**: 한 참조가 만료돼도 다른 공개 참조가 있으면 계속 공개, 모든 참조가 비공개일 때만 404. ③ 기간 형태 **양끝 null / 시작만 / 종료만**을 네 쿼리 각각에 대해 경계 시험. ④ 초 절단 후 `start == end`는 400이고 원글·본문 참조는 변하지 않음. ⑤ `useYn=false`에 예약·만료가 겹쳐도 공개 쿼리는 거부하고 관리 응답 `status`는 `HIDDEN`.
- **입력 계약 시험(v2·v3, R1-1·R1-2·R1-5·R2-1~3)**: 실제 MVC 바인딩(`MockMvc` 또는 실서버)으로 `Z`·`+09:00` 오프셋·빈 문자열·공백·소수 초·배열·숫자·객체 값·날짜만·**존재하지 않는 날짜(`2026-02-30`·평년 `02-29`)·`24:00`**·중첩 오타 필드(`publishStart`)·**중복 키(안쪽 `{"start":"2026-02-30T10:00","start":null}`, 바깥 `"publishPeriod"` 두 번)**·범위 밖 연도(`1999`·`2100`·`+10000`)가 모두 400이고, 실패한 PATCH 뒤 DB의 기간·본문 참조가 그대로임을 단언한다(Java DTO 직접 생성 시험으로 대체하지 않는다). 범위 상·하한(`2000-01-01T00:00` 허용, `2099-12-31T23:59` 허용, `2100-01-01T00:00` 거부)은 실제 MariaDB에서 저장 왕복까지 확인.
- **마이그레이션 시험(v2, R1-4)**: V35 상태 DB에 게시글을 넣고 V36 적용 후 기간 컬럼이 NULL이며 공개 동작이 그대로임(`BannerMigrationTest`의 Flyway 패턴).
- 기존 시험은 새 시그니처로 갱신(의미 변경 없음).

### 10. 인가·보안 — 변경 없음 (결정, **인가 정책 변경 없음**)

새 엔드포인트·경로 규칙·권한 없음. 기존 `@RequireBoardPermission`(CREATE/UPDATE)이 기간 쓰기를 그대로 지킨다(별도 권한 분리 없음). `SecurityConfig`·레이트리밋 변경 없음.

## 영향 범위 (파일)

- 신규: `db/migration/V36__add_post_publish_period.sql`, `common/display/DisplayStatus.java`, 요청 DTO `PostPublishPeriodRequest`, 시험 `PostPublishPeriodIntegrationTest`·`MutableClock`(테스트 지원).
- 수정(main): `Post`, `PostCreateRequest`·`PostUpdateRequest`, `PostResponse`·`PostSummaryResponse`, `PostService`, `PostRepository`·`PostRepositoryCustom`·`PostRepositoryImpl`, `ContentImageRefRepository`, `PublicBoardService`·`PublicHomeService`·`PublicContentImageService`(Clock 주입), `DisplayPeriod.statusOf`, `templates/admin/board/posts.html`, `docs/deployment.md`(롤백 주의), `admin/board`·`publicweb/board`·`publicweb/home`의 `CLAUDE.md`, `docs/troubleshooting.md`(필요 시).
- 수정(test): `PostRepositoryDataJpaTest`, `PublicBoardServiceTest`, `PostServiceTest`, `PostApiIntegrationTest`, `PublicBoardIntegrationTest`·`PublicHomeIntegrationTest`(시그니처/조회 변화에 따른 최소 수정).
- 변경 없음: `SecurityConfig`, `application.yml`, `AdminSearchService`, 배너 코드.

## 작업 단계 (구현 순서)

1. 브랜치 `feat/post-publish-period` → V36 마이그레이션 → `Post` 필드·`update` → `DisplayStatus`·`DisplayPeriod.statusOf`(단위 시험).
2. 요청/응답 DTO·`PostService`(검증·`now` 전달) → `PostServiceTest`.
3. 저장소 4개 메서드 교체 + 호출자(`PublicBoardService`·`PublicHomeService`·`PublicContentImageService`)에 `Clock` 주입 → `compileJava`로 우회 호출이 남지 않음을 확인.
4. DB 경계 시험(`PostRepositoryDataJpaTest`)·서비스 단위 시험 갱신.
5. 통합 시험 `PostPublishPeriodIntegrationTest` + 기존 통합 시험 갱신 → 전체 `./gradlew test`(+ UTC 재실행).
6. `posts.html` 화면 → Playwright 실기(미래 시작 글 작성 → 공개 화면 미노출, 관리자 "예약됨" 표시, 경계 데이터 직접 조작 포함).
7. 문서(`CLAUDE.md` 3곳·`deployment.md` 롤백 주의·PLAN 구현 결과·README 인덱스).

## 완료 기준 (로드맵 반영)

- [ ] `./gradlew test` 통과(UTC 재실행 포함)
- [ ] 고정 `Clock`으로 시작 직전·시작 시각·종료 직전·종료 시각 경계를 목록·검색·COUNT·상세·첨부 다운로드·본문 이미지·공개 메인·`/notices` **모든 공개 경로**에서 검증(Testcontainers)
- [ ] 기간 NULL 게시글의 공개 동작이 그대로(회귀), 시작 ≥ 종료 입력 400(생성·수정)
- [ ] `publishPeriod` PATCH: 없으면 유지·있으면 교체·`{null,null}`이면 비움
- [ ] 기존 시각 없는 공개 조회 메서드가 코드에 남아 있지 않음
- [ ] 쟁점 9 확장 매트릭스(익명 GET/HEAD·권한자 미리보기·공유 이미지·양끝 null/시작만/종료만·초 절단 후 동일 400·`useYn=false` 겹침) 통과
- [ ] 입력 계약(오프셋·빈 값·배열·오타 필드·범위 밖 연도 400, 실패 뒤 DB 불변)과 V35→V36 마이그레이션 시험 통과
- [ ] 세 분포(유효 밀집·최신 대부분 무효·전부 무효) 성능 측정과 `EXPLAIN` 결과를 구현 결과에 기록, 기준 초과 시 인덱스 추가
- [ ] `deployment.md` 롤백·운영 절차 작성
- [ ] Playwright: 미래 시작 게시글 작성 → 공개 화면 미노출, 관리자 화면 "예약됨" 표시

## 리스크

- **롤백 시 예약·만료 글이 공개된다**: V36 이후 이전 앱 버전은 `publish_*` 컬럼을 모르고 `useYn`만 본다(컬럼이 늘어나는 것은 `ddl-auto: validate`가 허용). 이전 버전으로 되돌리면 시작 전 글이 즉시 공개되고 만료 글이 다시 보인다. 완화: 쟁점 5의 롤백·운영 절차(쓰기 동결·백업·정리·잔여 0건 검증·재전진 복원)를 `docs/deployment.md`에 쓰고 roll-forward를 우선한다(V16·권한관리 롤백 주의와 같은 성격, 수용). 정리 중 진행 중이던 요청이 글을 다시 켤 수 있는 틈은 **쓰기 동결**로만 막을 수 있어 절차의 첫 단계로 둔다.
- **마지막 저장 우선(수용, v2 R1-3)**: 기간은 통째 교체라서, 낡은 편집 화면이 다른 관리자가 이미 줄인 기간을 되돌릴 수 있다(A가 종료를 12:00으로 줄인 뒤 B가 낡은 18:00을 포함해 저장). 게시글에 낙관적 락 버전이 없고 제목·본문·`useYn`도 같은 규칙이라 기간만 별도 보호하지 않는다. 화면은 기간이 바뀐 경우에만 `publishPeriod`를 보내 노출을 줄인다.
- **경계의 약한 보장**: 다운로드 시작 후 종료 시각이 지나도 진행 중 전송은 끝까지 간다(기존 TOCTOU 약한 보장과 같음). 조회 시점 판정이라 스케줄러 지연은 없다.
- **분 단위 절단**: 입력의 초·소수 초는 버려진다(`10:00:59` → `10:00`). 사용자 예상보다 이르게 시작할 수 있어 UI는 분 단위 입력만 받는다.
- **서버 시계 의존**: 모든 판정이 서버 KST `Clock`이다. 관리 화면 "예약됨" 상태도 서버 계산이라 브라우저 시계와 어긋나지 않는다.
- **복제 지점**: JPQL 두 곳(상세·이미지)과 QueryDSL 조건이 같은 의미를 따로 표현한다 → DB 시험이 고정하고 `CLAUDE.md`에 "한 곳을 바꾸면 모두"를 명시한다(`existsPublishedPostRef` 주석 관례와 같음).

## 후속(이 계획 밖)

- 배너의 `Status` enum을 `DisplayStatus`로 통합(⑦ 팝업 착수 때 함께 판단).
- 예약 게시 글의 "예약 알림"·스케줄러 기반 자동 비노출은 범위 밖(조회 조건 판정 방식).

## 구현·검증 결과 (2026-10-11)

### Context

로드맵 Top 8 ④. 게시글에 노출 시작·종료 시각(`publishStart`·`publishEnd`)을 선택적으로 지정하면 공개 목록·검색·COUNT·상세·첨부 다운로드(`/notices` 포함)·공개 메인·본문 이미지가 그 기간 안에서만 글을 보여 준다. 판정은 스케줄러 없이 조회 조건 + 요청당 1회 주입된 KST `Clock`이다. 브랜치 `feat/post-publish-period`(커밋 전).

### 핵심 확정 사항 (계획 v5 그대로 + 구현 중 달라진 점)

계획 v5의 결정(쟁점 1~10)을 그대로 구현했다. **달라진 점·구현 중 발견한 것**:

1. **`@JsonCreator(mode = DISABLED)`가 필수였다(계획 누락)** — 계획 v4·v5는 `start`·`end`·`publishPeriod` 세터에서 중복 키를 거부한다고 했고 리뷰어도 최소 객체로 재현했지만, 실제 요청 DTO에는 `@Builder`용 생성자가 있어 Jackson 3가 매개변수 이름으로 그 생성자를 creator로 자동 인식했다. creator 경로에서는 같은 키의 마지막 값이 이겨 **세터의 중복 거부가 아예 호출되지 않았다**(`{"start":"2090-02-30T10:00","start":null}`이 통과). 오타 키 거부(`@JsonAnySetter`)는 creator 경로에서도 동작해 이 부분만 통과하는 것이 원인 추적의 단서였다. 실서버 스택 시험(`PostPublishPeriodApiIntegrationTest`의 중복 키 6건)이 처음 잡았고, 세 클래스의 생성자에 `@JsonCreator(mode = DISABLED)`를 붙여 해소했다. 지우면 그 6건이 실패한다.
2. **`Post.update` 시그니처는 바꾸지 않고 `Post.changePublishPeriod`를 따로 뒀다**(계획 쟁점 6은 `update`에 기간 파라미터를 넣는 형태였다) — 기존 호출·시험 파급을 줄이고 "기간 객체가 있을 때만 교체"를 서비스에서 분명히 하기 위해서다. `updateDate`는 `update`가 그대로 갱신한다.
3. **테스트 컨테이너 `max_connections`를 151 → 500으로 올렸다**(계획에 없던 변경) — 새 `MutableClock` 컨텍스트가 캐시된 스프링 컨텍스트를 하나 늘리자 전체 실행에서만 `Too many connections`가 나 `LockWaitObserverTest` 5건이 실패했다(단독 실행은 통과). 컨텍스트마다 Hikari 풀(최대 10)을 쥐어 기본 한도 근처였다. `MariaDbContainerSupport`에 `.withCommand("--max-connections=500")`.
4. **`MutableClock` 설정은 `@Import`가 필요했다** — `@SpringBootTest(classes = CmsTestApplication.class)`에서 중첩 `@TestConfiguration`이 자동으로 잡히지 않아 "No qualifying bean"이 났다(원인 규명은 하지 않았다). `@Import(…ClockConfig.class)`로 해결, `src/test/java/CLAUDE.md`에 기록.
5. **화면 CSS**: 상태 배지가 좁은 폭에서 접혀 `white-space: nowrap`을 줬고, 기간 셀까지 `nowrap`으로 두면 제목 열이 한 글자씩 접혀 배지에만 적용하도록 되돌렸다. `badge-status-scheduled`·`badge-status-expired`는 이 화면의 `<style>`에만 있다.
6. **인덱스는 추가하지 않았다(측정 근거, 아래 "성능")**.

### 구현 파일

- 신규(main): `db/migration/V36__add_post_publish_period.sql`, `common/display/DisplayStatus.java`, `admin/board/dto/request/PostPublishPeriodRequest.java`.
- 수정(main): `Post`(필드·`changePublishPeriod`), `PostCreateRequest`·`PostUpdateRequest`(`publishPeriod`·세터 중복 거부·`@JsonCreator(DISABLED)`), `PostResponse`·`PostSummaryResponse`(기간·`status`), `PostService`(검증·전달), `PostRepository`(`findPublishedById` JPQL, 옛 `…UseYnTrue` 제거)·`PostRepositoryCustom`·`PostRepositoryImpl`(`isPublished` 조건 객체), `ContentImageRefRepository.existsPublishedPostRef(imageId, now)`, `DisplayPeriod`(`parseKstLocal`·`requireInRange`·`statusOf`), 공개 서비스 3곳(`PublicBoardService`·`PublicHomeService`·`PublicContentImageService`에 `Clock`), `templates/admin/board/posts.html`.
- 문서: `CLAUDE.md`(루트 지도 행)·`admin/board`·`publicweb/board`·`publicweb/home`·`src/test/java`의 `CLAUDE.md`, `docs/deployment.md`("게시글 예약 게시" — 롤백 순서), `docs/migration-guide.md`(V36).
- 시험 신규: `PostPublishPeriodApiIntegrationTest`(44건), `PostPublishPeriodPublicIntegrationTest`(6건), `PostPublishPeriodMigrationTest`(3건), `support/MutableClock`. 시험 수정: `PostRepositoryDataJpaTest`(경계 대조 5건 추가)·`DisplayPeriodTest`(파서·범위·상태)·`PublicBoardServiceTest`(`Clock` 주입, `now` 전달 단언)·`MariaDbContainerSupport`.
- 변경 없음: `SecurityConfig`·`application.yml`·`AdminSearchService`·배너 코드.

### 검증 결과

- **전체 `./gradlew test`**: 기본 시간대 BUILD SUCCESSFUL(7분 51초), **`-Duser.timezone=UTC` 재실행도 BUILD SUCCESSFUL(6분 39초)**. 1894건(스킵 12). 첫 전체 실행에서 `LockWaitObserverTest` 5건이 `Too many connections`로 실패 → 위 3번으로 해소(단독 실행은 통과했었다).
- **변이 실험(시험이 실제로 결함을 잡는지)**: ① 본문 이미지 JPQL의 기간 조건 제거 → 공개 이미지 시험 3건 실패 ② 상세 JPQL(`findPublishedById`)의 기간 조건 제거 → 상세 시험 2건 실패 ③ QueryDSL `isPublished`의 기간 조건 제거 → 목록·COUNT·메인 시험 3건 실패. 모두 원복.
- **입력 계약(실제 MVC 바인딩)**: 오프셋·빈 문자열·공백·소수 초·날짜만·존재하지 않는 날짜·24시·형식 불일치·값 타입 오류·오타 키·중복 키(안쪽·바깥쪽)·범위 밖 연도(1999·2100·+10000)·순서 위반 — 전부 400이고 기존 기간·제목 불변. 범위 경계(`2000-01-01T00:00`·`2099-12-31T23:59` 저장 왕복, `2100-01-01T00:00` 400).
- **실기(dev 앱 + Playwright, 2026-10-11)**: 기존 dev DB(V34)에 V35·V36이 정상 적용되고 앱 기동. 로그인 → 게시글 관리: 기존 글은 "노출 중 / 제한 없음". 미래 시작(2090-01-01 09:00) 글 작성 → 관리 화면 "예약됨"·기간 표시, **익명 `/boards/3` 목록에 제목 없음·상세 404·메인 미노출**. UI에서 시작을 과거로 수정(PATCH — 종료는 병합되지 않고 NULL) → 공개 목록·상세·메인 노출. 종료를 과거로 → "만료"·공개 404. 기간을 모두 비움(왕복 원복) → DB `NULL/NULL`·공개 200. 라이브 서버 PATCH 6종(오프셋·빈 문자열·오타 키·중복 키 2종·start=end) 전부 400. 익명 PATCH 401. 기간 없는 기존 글·`/`·`/notices`·`/boards/2`·`/boards/3` 회귀 없음(200). 스크린샷 `adversarial-review/plan/screenshots/post-period-01~05*.png`(PNG는 `.gitignore` 대상). 시험 글은 삭제하고 임시 `perf_` 테이블도 지워 dev DB를 원래대로 되돌렸다.
- **성능(5만 행, dev MariaDB, 앱이 보내는 SQL 그대로, 5회 측정, 기존 조건 vs 기간 조건)**:

  | 분포 | 메인 새 글(공지 제외) | 메인 공지(공지 게시판) | 공개 목록(10건) | COUNT |
  |---|---|---|---|---|
  | 유효 글 밀집(기간 없음) | 52~58ms vs 50~59ms | 0.44 vs 0.48ms | 0.5 vs 0.5ms | 12ms vs 4ms |
  | 최신 4만 건이 예약 | 35ms vs 50ms | 15ms vs 0.45ms | 18~21ms vs 0.6ms | 12ms vs 3.6ms |
  | 전부 무효 | 35~41ms vs 51~58ms | 19ms vs 0.35ms | 24ms vs 0.6ms | 13~16ms vs 3.6ms |

  해석: 기간이 없는 글이 대부분이면 기존과 같다. 최신 글이 대부분 예약·만료인 낮은 적중률에서는 후보 행을 건너뛰느라 공지 쿼리가 0.4ms → 15~19ms, 목록이 0.6ms → 18~24ms로 늘지만 **끝까지 스캔해도 25ms 안**이다. COUNT는 기간 컬럼 때문에 인덱스만으로 못 세어 4ms → 12~16ms(3~4배)다. 메인 두 쿼리 합 최악은 약 59ms(전부 무효)로, 기존 밀집 분포의 합(약 53ms — 계획이 인용한 36ms는 이 기기에서 50ms대로 재현됐다)보다 약 10% 크다. **인덱스는 추가하지 않는다** — 증가분이 모두 한 자릿수~20ms대이고 그 분포(최신 대부분이 예약·만료)는 비정상적이다. 공개 행이 10만 단위를 넘거나 COUNT가 병목이면 `(board_id, deleted, use_yn, publish_start, publish_end)` 류 커버링 인덱스를 새 마이그레이션으로 재평가한다. (V36에는 넣지 않았다.)

### 이슈

- 위 "달라진 점" 1(JsonCreator)·3(max_connections)이 이번 작업에서 새로 알게 된 비자명한 사실이다 — `docs/troubleshooting.md` 기록 대상이다(별도로 기록).
- 계획서 R1-3으로 수용한 한계(마지막 저장 우선·낡은 초안이 기간을 되돌림)는 시험으로 고정하지 않았다(문서 수용).
- "V36 DB에서 이전 앱 기동"은 이전 코드를 띄울 수 없어 시험하지 못했다(`ddl-auto: validate`의 여분 컬럼 허용에 의존, 문서 명시).

### 후속

- 배너의 `Status` enum을 `DisplayStatus`로 통합, 배너에도 `parseKstLocal`·`requireInRange` 적용(배너 JSON 요청의 같은 Jackson 관대함 — 이 작업 범위 밖).
- 전역 `fail-on-unknown-properties: false`·`@JsonCreator` 자동 인식은 다른 요청 DTO에도 같은 구조로 존재한다(중복 키 덮어쓰기). 이 작업의 `publishPeriod`는 기간이 지워져 즉시 공개되는 위험 때문에 막았고 다른 DTO는 손대지 않았다.
- 예약 알림·스케줄러 기반 자동 비노출은 범위 밖(조회 조건 판정 방식).

### 코드 리뷰 (2026-10-11, `/code-review-loop` 2라운드)

- **1라운드 P2 1건 수용**: 중복 검사의 상태 플래그(`startSet`·`endSet`·`publishPeriodSet`)가 클래스 `@Getter` 때문에 JSON 속성이 되어, 요청이 `{"start":"2090-02-30T10:00","startSet":false,"start":null}`처럼 플래그를 되돌려 중복 검사를 우회하고 기간을 지울 수 있었다(Jackson이 보이는 getter가 있는 비공개 필드를 mutator로 추론). 세 플래그를 `@JsonIgnore` + `@Getter(NONE)`(요청 DTO는 `@Setter(NONE)`도)로 바꾸고 회귀 시험 5건(안쪽 `start`·`end`·바깥 `publishPeriod`, PATCH·생성)을 추가했다. 변이 실험으로 보호 어노테이션을 지우면 그 5건이 실패하고 복원하면 통과함을 확인했다.
- **2라운드 지적 0건**. 이후 전체 `./gradlew test` 통과(5분 26초). 이 항목의 총 시험 수는 `PostPublishPeriodApiIntegrationTest` 49건이다.
