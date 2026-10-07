# PLAN — 공개 공지 목록 검색

> 상태: ✅ 완료 (2026-10-07 · #110 `c762fe4`, PR·master CI `test`·`prod-smoke` success — Linux CI 1392건 실패·건너뜀 0) — v2 승인(적대적 리뷰 2라운드 ship, 검색 대상 제목만 사용자 확정) → 구현 → 테스트 → dev Docker 실기(Playwright 비로그인) + 1만·5만 행 비용 실측. 결과는 문서 끝 "구현·검증 결과" 참조
> 출처: 로드맵 "선정에서 탈락한 후보 > 공개 공지 목록 검색"(`PLAN-public-notice.md`가 의도적으로 범위 제외) — `/suggestRoadmap` 2026-10-07 선택
> 유형: feat · 브랜치 `feat/public-notice-search` · **스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음**

## 개정 이력

- v1 (2026-10-07): 최초 작성.
- v2 변경(1라운드 codex): (1) **수용 — COUNT·페이지 메타데이터의 공개 불변식**: 관리자 `searchNotices`가 목록과 COUNT를 따로 조립하는 구조라, 한쪽에서 공개 조건을 빠뜨리면 비공개 일치 공지의 존재가 페이지 수로 드러날 수 있다. 목록·COUNT가 **같은 `BooleanBuilder`** 를 쓰도록 명시했다(쟁점 2). 실 DB 테스트에 총건수·총페이지·`hasNext` 단언을 추가했다(공개 일치 11 + 비공개·삭제·불일치 혼합 → 11건·2페이지, 비공개만 일치 → 0건·0페이지). (2) **부분 수용 — 비용 근거**: 레이트리밋 서술을 실제 동작인 "버스트 120 + 초당 2토큰 보충, 동시 진입 제한 없음, 캐시 포화 시 fail-open"으로 정정했다. 비용 기준이 공개 공지 수가 아니라 비노출·삭제 포함 **전체 행수**임을 명시했다. 실기에서 1만·5만 행 시드로 흔한 검색어·불일치 검색어·큰 페이지의 쿼리 시간을 실측해 FULLTEXT 재평가 기준을 수치로 기록하기로 했다(쟁점 6·8). 기각: 버스트 동시 부하에서 관리자 요청 지연을 측정하는 부하 테스트. 기존 무검색 공개 목록도 인덱스 없이 요청마다 전체 행 COUNT·스캔을 하고 있고, 검색은 비용 차수를 바꾸지 않고 행당 제목 비교 하나를 더할 뿐이다. 동시성 부하는 기존 공개 목록과 같은 위험이라 이번 범위 밖(후속)으로 둔다. (3) **수용 — 길이 단위 통일**: 서버 상한을 코드포인트에서 `String.length()`(UTF-16 코드 유닛) 100으로 바꿔 HTML `maxlength`(코드 유닛 기준)와 같게 맞췄다. 경계 테스트를 이모지 50개(100유닛) 허용·51개(102유닛) 거부로 바꿨다. XSS 테스트에 따옴표로 속성을 탈출하려는 입력을 추가했다.

## 1. Context

공개 공지 목록 `/notices`는 노출·미삭제 공지를 10건씩 최신순으로 보여 주고 `page` 하나만 받는다(`PLAN-public-notice.md`). 검색은 그 계획이 범위에서 뺐다. 이 계획은 **제목 키워드 검색**을 더한다.

### 정찰에서 확인한 사실 (2026-10-07)

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| 공개 불변식 격리 | "노출+미삭제"는 `PublicNoticeService` 전용 저장소 메서드 이름(`findByDeletedFalseAndUseYnTrue`, `findByIdAndDeletedFalseAndUseYnTrue`)으로 강제한다. 저장소 주석: "조건이 고정이라 QueryDSL 동적 쿼리가 필요 없다 — 메서드명 자체가 조건을 강제" | `NoticeRepository.java:18-26`, `publicweb/notice/CLAUDE.md` |
| 컨트롤러 파싱 규약 | `page`·`id`는 문자열로 받아 직접 파싱(바인딩 실패가 전역 `GlobalApiExceptionHandler`의 JSON 응답으로 새지 않게). 파싱 실패는 0/404로 흡수 | `PublicNoticeController.java:25-40, 222-236` |
| 서비스 목록 규칙 | 페이지 크기 10 고정, `page` 음수·`MAX_PAGE`(1000) 초과 → 0, 정렬 `createDate desc, id desc` 고정. 인덱스 없는 테이블의 큰 OFFSET 방어 | `PublicNoticeService.java:41-59` |
| 관리자 검색 선례 | `NoticeRepositoryImpl.searchNotices`가 QueryDSL `notice.title.contains(keyword.trim())`(제목만), `NoticeSearchRequest.keyword @Size(max=200)`. 상단바 통합 검색(`AdminSearchService`)도 같은 저장소 메서드로 제목만 검색 | `NoticeRepositoryImpl.java:38-41`, `AdminSearchService.java:112-113` |
| LIKE 와일드카드 | 저장소 코드에 직접 이스케이프하는 곳은 없다. QueryDSL `contains`는 `TemplateFactory.escapeForLike`로 상수의 `%`·이스케이프 문자를 이스케이프하는 것을 바이트코드로 확인했다(`_` 포함 여부는 테스트로 확정). `JPAQueryFactory(em)`은 기본 `JPQLTemplates`(이스케이프 문자 `!`)를 쓴다. 실 DB로 와일드카드 동작을 검증한 테스트는 **없다** | `javap TemplateFactory.escapeForLike`, `QuerydslConfig.java:17` |
| Spring Data `Containing` | 이스케이프 문자로 `\`를 쓴다. MariaDB 기본 `sql_mode`에서 `\`는 문자열 이스케이프라 `escape '\'` 렌더링 안전성이 이 프로젝트에서 검증된 적 없음 | Spring Data JPA 동작(미검증) |
| 스키마·인덱스 | `notice.title varchar(200)`, `content TEXT`, 콜레이션 `utf8mb4_general_ci`(대소문자 무시), PK 외 인덱스 없음 | `V8__create_notice.sql` |
| 인가·레이트리밋 | `SecurityConfig`: `/notices`, `/notices/**` GET·HEAD `permitAll`, 그 외 메서드 `denyAll`. 레이트리밋 `public-notice` 규칙 `/notices/**`(PathPattern이라 `/notices` 포함) GET·HEAD IP당 120회/60초. 쿼리 문자열은 경로 매칭과 무관 → 검색 요청도 같은 한도 | `SecurityConfig.java:104-106`, `application.yml:109-113` |
| 템플릿 | `public/notice/list.html`: 목록·빈 상태 문구("등록된 공지사항이 없습니다.")·페이지 링크 `@{/notices(page=${page ± 1})}`. 상세의 "목록으로"는 `@{/notices}`. `PublicNoticeTemplateConventionTest`가 공개 템플릿의 `th:utext` 사용을 금지 | `list.html`, `detail.html:32`, `PublicNoticeTemplateConventionTest` |
| 테스트 파급 | `PublicNoticeControllerTest`(`@WebMvcTest`, 목록 7건이 `getPublishedNotices(0)`·`anyInt()` 스텁), `PublicNoticeServiceTest`(목록 5건이 `findByDeletedFalseAndUseYnTrue` 스텁·정렬·페이지 단언), `NoticeRepositoryDataJpaTest`(Testcontainers, 공개 목록 2건). 서비스 시그니처를 바꾸면 컨트롤러 목록 테스트 스텁을 모두 고쳐야 한다 | `grep` |

## 2. 핵심 쟁점과 결정

### 쟁점 1 — 검색 대상 필드

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 제목만** | 관리자 목록·상단바 통합 검색과 같은 범위. `varchar(200)` LIKE라 비용이 작다 | 본문에만 있는 단어는 못 찾는다 |
| B. 제목+본문 | 찾을 수 있는 범위가 넓다 | 본문 `TEXT`(최대 1만 자) 전 행 LIKE 스캔 — 무인증 경로에서 요청당 비용이 커진다. 관리자 검색과 의미가 달라진다 |

**결정: A.** 관리자·통합 검색과 같은 의미를 유지하고, 무인증 경로의 요청당 비용을 작게 둔다. 본문 검색이 필요해지면 FULLTEXT 인덱스와 함께 별도로 다룬다(후속).

### 쟁점 2 — 쿼리 위치와 공개 불변식 격리

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `NoticeRepositoryCustom`에 공개 전용 QueryDSL 메서드 `searchPublishedByTitle(String keyword, Pageable)` 추가 — 구현에 `deleted=false AND useYn=true`를 하드코딩. 키워드가 없으면 서비스가 기존 `findByDeletedFalseAndUseYnTrue`를 그대로 호출** | 기본 목록 경로가 바뀌지 않는다(회귀 0). 불변식이 메서드 이름·구현에 고정된다. LIKE 이스케이프는 관리자 검색과 같은 QueryDSL 경로. CLAUDE.md "동적 조건은 QueryDSL" 관례와 맞는다 | 노출 조건이 두 메서드에 각각 존재(파생 쿼리 이름 + QueryDSL 구현) |
| B. 파생 쿼리 `findByDeletedFalseAndUseYnTrueAndTitleContaining(String, Pageable)` | 선언 1줄, 이름이 불변식을 강제 | 이스케이프 문자 `\` + MariaDB 조합이 미검증 |
| C. 하나의 QueryDSL 메서드로 키워드 유무 모두 처리하고 기존 파생 메서드 삭제 | 경로 하나 | 이미 테스트된 기본 목록 경로를 바꾸는 회귀 위험, 무관한 정리가 섞인다 |

**결정: A.** 기본 목록은 그대로 두고 검색만 새 경로로 보낸다. **(v2)** 구현은 `deleted=false AND useYn=true AND title contains(keyword)`를 하나의 `BooleanBuilder`로 만들어 **목록 SELECT와 COUNT가 같은 조건 객체를 쓴다** — COUNT에서 공개 조건이 빠지면 비공개 일치 공지의 존재가 총건수·페이지 수로 드러나기 때문이다. 정렬은 서비스가 넘긴 `Pageable`의 정렬(`createDate desc, id desc`)을 기존 `toOrderSpecifiers`(화이트리스트 + `id` 보조 정렬)로 변환해 기본 목록과 같은 순서를 보장한다. 관리자 `searchNotices`(선택적 `useYn`)를 재사용하지 않는 이유는 공개 조건이 "선택 필터"가 되면 안 되기 때문이다(`publicweb/notice/CLAUDE.md`의 격리 원칙).

### 쟁점 3 — 키워드 정규화·검증 (무인증 입력)

- **파라미터**: `keyword`(관리자와 같은 이름), `@RequestParam(required = false) String`. 문자열 바인딩은 실패하지 않으므로 전역 JSON 핸들러로 샐 경로가 없다. 같은 이름이 여러 번 오면 Spring이 쉼표로 합친 문자열을 준다 — 그대로 검색어로 쓴다(오류 아님).
- **정규화(서비스 책임)**: `strip()` 후 빈 문자열이면 검색 없음(기본 목록). 컨트롤러는 원문을 그대로 넘긴다(기존 "파싱은 컨트롤러, 비즈니스 규칙은 서비스" 분담과 같음).
- **길이 상한**: **`String.length()`(UTF-16 코드 유닛) 100**. **(v2)** HTML `maxlength`도 UTF-16 코드 유닛 기준이므로 단위를 같게 맞춘다(코드포인트로 세면 이모지 51개처럼 서버는 허용하지만 입력란은 막는 불일치가 생긴다).

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 100자 초과면 DB를 조회하지 않고 빈 페이지** | 무인증 경로에서 긴 입력이 쿼리까지 가지 않는다. 화면 입력란에 `maxlength="100"`이 있어 정상 사용자는 도달하지 않는다 | 조작된 URL에는 "결과 없음"만 보인다(안내 문구 없음) |
| B. 100자로 잘라 검색 | 결과가 나온다 | 사용자가 모르게 의미가 바뀐다. 서로게이트 쌍 경계 처리 필요 |
| C. 400/오류 페이지 | 명확 | 공개 화면에 새 오류 경로를 만든다(기존은 모든 이상 입력을 흡수) |

**결정: A.** 기존 공개 화면의 "이상 입력은 흡수" 원칙과 같고 가장 단순하다. 100자는 제목 최대 200자의 절반으로, 실제 검색어로 충분하다.

- **와일드카드**: `%`·`_`·`!`(QueryDSL 이스케이프 문자)는 **문자 그대로** 검색된다(QueryDSL 이스케이프). 실 DB 테스트로 고정한다(쟁점 7).
- **대소문자**: 콜레이션 `utf8mb4_general_ci`에 따라 대소문자를 구분하지 않는다(관리자 검색과 동일). 이를 테스트로 문서화한다.
- **SQL 인젝션**: 키워드는 바인딩 파라미터로만 전달된다(QueryDSL 상수 → JPQL 파라미터).

### 쟁점 4 — 서비스·컨트롤러 시그니처

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. `getPublishedNotices(int page, String keyword)` 하나로 바꾸고 정규화를 서비스에 둔다** | 진입점 하나, 규칙이 한 곳 | 컨트롤러·서비스 테스트의 목록 스텁을 기계적으로 고쳐야 한다 |
| B. 기존 메서드 유지 + `searchPublishedNotices(int, String)` 추가, 컨트롤러가 분기 | 기존 서비스 테스트 유지 | 분기(빈 키워드 판정)가 컨트롤러로 새어 정규화 규칙이 두 곳에 생긴다. 컨트롤러 테스트는 어차피 바뀐다 |

**결정: A.** 테스트 수정은 기계적이고, 규칙이 서비스 한 곳에 있는 편이 공개 불변식 격리 원칙과 맞다. 서비스는 정규화된 키워드(없으면 `null`)도 화면이 쓸 수 있게 돌려줘야 하므로 반환형을 `Page<PublicNoticeSummary>` 대신 작은 레코드 `PublicNoticeListResult(Page<PublicNoticeSummary> page, String keyword)`로 바꾼다. 컨트롤러가 정규화를 다시 하지 않기 위해서다.

### 쟁점 5 — 화면(Thymeleaf)

- 목록 상단에 `GET /notices` 검색 폼: `<input type="search" name="keyword" maxlength="100" th:value="${keyword}">` + 검색 버튼. 검색 중이면 "전체 보기"(`@{/notices}`) 링크를 보인다.
- 빈 상태 문구: 검색 중이면 "검색 결과가 없습니다.", 아니면 기존 "등록된 공지사항이 없습니다."
- **페이지 링크에 검색어 유지**: 검색 중일 때만 `keyword`를 붙인다. Thymeleaf 링크 식에 `null` 값을 넘겼을 때의 렌더링(파라미터 생략 여부)을 추측하지 않고, 검색 중/아닐 때 링크를 `th:if`로 나눠 명시적으로 만든다. 키워드는 Thymeleaf 링크 식이 URL 인코딩한다.
- 출력은 전부 `th:text`·`th:value`(이스케이프). `th:utext` 금지(기존 관례 테스트).
- 상세 화면의 "목록으로"는 바꾸지 않는다(검색어를 상세 URL로 전달하는 것은 범위 밖 — 브라우저 뒤로가기로 검색 결과에 돌아간다).
- CSS: 기존 `css/public/notice.css`에 검색 폼 스타일만 추가.

### 쟁점 6 — 인덱스·스키마·비용

| 선택지 | 장점 | 단점 |
|---|---|---|
| **A. 스키마 변경 없음** | 마이그레이션 없음 | `LIKE '%kw%'`는 B-tree를 쓸 수 없어 전 행 스캔 + COUNT |
| B. `title` B-tree 인덱스 | — | 양쪽 `%` LIKE에는 효과 없음 |
| C. FULLTEXT(ngram) 인덱스 | 대량 데이터에서 빠름 | 한국어 ngram 파서 설정·`MATCH AGAINST` 의미 차이·마이그레이션 — 수백 건 규모에 과설계 |

**결정: A.** 기존 공개 목록도 인덱스 없는 COUNT·OFFSET을 수용했고(`MAX_PAGE` 방어), 대상은 `varchar(200)` 한 컬럼이다.

**(v2 정정) 비용 근거의 정확한 범위**:
- 비용을 정하는 것은 노출 공지 수가 아니라 비노출·소프트 삭제를 포함한 **전체 `notice` 행수**다. 짧은 검색어·결과 없는 검색도 전체 스캔 + COUNT를 한다. 100자 상한과 `MAX_PAGE`는 이 스캔 비용을 줄이지 않는다.
- 레이트리밋은 정확한 "120회/분"이 아니라 **IP당 버스트 120 + 초당 2토큰 보충**이고, 동시 진입 수는 제한하지 않으며, 캐시 포화 시 fail-open이다(`Bucket`·`TokenBucketRateLimiter`, 루트 CLAUDE.md 명시 수용). 즉 요청 빈도의 대략적 상한일 뿐 DB 동시 부하의 방어는 아니다.
- 다만 이것은 **기존 무검색 공개 목록과 같은 위험 등급**이다(인덱스 없이 요청마다 전체 행 COUNT·스캔). 검색은 행당 제목 비교 하나를 더할 뿐 비용 차수를 바꾸지 않는다.
- **실측으로 근거를 남긴다(쟁점 8)**: dev DB에 1만·5만 행을 넣고 흔한 검색어·불일치 검색어·큰 페이지의 쿼리 시간을 측정한다. 그 수치로 "전체 행수 X 이상 또는 쿼리 시간 Y ms 이상이면 FULLTEXT(ngram)·별도 검색 인덱스를 재평가"라는 후속 기준을 계획서에 기록한다.
- 동시 부하(버스트 시 관리자 요청 지연) 측정과 동시 검색 수 제한은 기존 공개 목록과 같은 범위 밖 위험으로 후속에 남긴다.

### 쟁점 7 — 테스트

1. **`NoticeRepositoryDataJpaTest`(실 MariaDB)** — `searchPublishedByTitle`:
   - 노출·미삭제만 반환(비노출·삭제는 제목이 맞아도 제외)
   - **(v2) COUNT·페이지 메타데이터**: 공개 일치 11건 + 같은 키워드의 비노출·삭제 + 불일치 공개 공지를 섞어 `totalElements=11`·`totalPages=2`·첫 페이지 `hasNext=true`. 비노출·삭제만 일치하면 `totalElements=0`·`totalPages=0`·내용 비어 있음
   - `%`·`_`·`!`가 문자 그대로 매칭(예: 제목 `"100% 달성"`과 `"1000 달성"`이 있을 때 `"0%"`는 앞의 것만, `"_"` 검색은 `_` 포함 제목만)
   - 대소문자 무시(콜레이션 문서화)
   - 정렬 `createDate desc, id desc`·페이지 크기
2. **`PublicNoticeServiceTest`** — `null`·빈칸·공백만 → 기존 `findByDeletedFalseAndUseYnTrue` 호출(검색 메서드 미호출), 앞뒤 공백 제거한 키워드 전달, 100 코드 유닛 경계(100자는 검색, 101자는 저장소 미호출 빈 페이지. **(v2)** 이모지 50개=100유닛은 검색, 51개=102유닛은 미호출 — HTML `maxlength`와 같은 단위), 검색 시에도 `page` 보정·크기 10·정렬 동일, 결과의 `keyword`가 정규화 값.
3. **`PublicNoticeControllerTest`** — 기존 목록 테스트 스텁을 새 시그니처로 전환. `keyword`가 서비스로 전달되는지, 모델 `keyword`, 검색어 XSS 페이로드(`<script>`와 **(v2)** 따옴표로 속성을 탈출하는 `" autofocus onfocus="alert(1)`)가 `value`에서 이스케이프되는지, 검색 중 페이지 링크에 URL 인코딩된 `keyword`가 붙고 검색 아닐 때는 붙지 않는지, 검색 결과 없음 문구.
4. **`PublicNoticeTemplateConventionTest`** — 기존 `th:utext` 금지가 새 마크업에도 적용됨(변경 없음, 통과 확인).
5. `SecurityConfig`·레이트리밋은 경로가 같아 테스트를 추가하지 않는다(기존 `/notices` 테스트가 쿼리 문자열과 무관하게 적용됨).

### 쟁점 8 — 실기 검증

dev Docker 스택 + Playwright(비로그인 브라우저):
1. 관리자 API로 검증용 공지를 만든다: 노출 공지 12건(같은 키워드 11건 → 2페이지), 비노출 1건·삭제 1건(같은 키워드), 와일드카드 제목(`100% 달성`, `1000 달성`, `a_b`).
2. `/notices`에서 키워드 검색 → 노출 공지만, 2페이지 이동 시 키워드 유지, "전체 보기"로 복귀.
3. `%`·`_` 문자 그대로 검색, XSS 페이로드 키워드가 실행되지 않음, 101자 URL → 결과 없음(200), 결과 없음 문구.
4. 회귀: 키워드 없는 목록·상세·첨부 다운로드, 관리자 공지 검색.
5. **(v2) 비용 실측**: dev DB에 검증용 행을 SQL로 대량 삽입(1만·5만 행, 노출·비노출·삭제 혼합)한 상태에서 검색 쿼리와 동일한 SQL(목록 + COUNT)의 실행 시간을 측정한다. 대상은 흔한 검색어(다수 일치), 불일치 검색어, 기본 목록, 큰 페이지(`page=999`)이고 `ANALYZE`/타이밍을 쓴다. 측정 후 삽입 행을 삭제한다. 결과로 FULLTEXT 재평가 기준을 기록한다.
6. 스크린샷, 검증 데이터 원복.

## 3. 작업 단계

1. 브랜치 `feat/public-notice-search` 생성(작업 트리의 미커밋 로드맵 29차 문서 3개는 건드리지 않고 따라옴).
2. 저장소: `NoticeRepositoryCustom.searchPublishedByTitle` + `NoticeRepositoryImpl` 구현. `compileJava`.
3. DTO `PublicNoticeListResult`, 서비스 `getPublishedNotices(int, String)` + 정규화. `compileJava`.
4. 컨트롤러 `keyword` 파라미터·모델, 템플릿·CSS.
5. 테스트(쟁점 7) → `./gradlew test`.
6. 실기 검증(쟁점 8).
7. 기록: `publicweb/notice/CLAUDE.md`("검색은 이번 범위 제외" 문구 갱신), 이 계획서 결과, `plan/README.md` 35행.

## 4. 리스크

| 리스크 | 영향 | 대응 |
|---|---|---|
| 무인증 검색의 요청당 비용(전체 행 LIKE + COUNT, 비노출·삭제 포함) | 대량 반복·동시 진입 시 DB 부하 | 제목만, 기존 레이트리밋(버스트 120 + 초당 2, 동시 진입 제한 없음). 기존 무검색 목록과 같은 위험 등급. **(v2)** 1만·5만 행 실측으로 FULLTEXT 재평가 기준 기록, 동시 부하는 후속 |
| LIKE 와일드카드가 이스케이프되지 않을 가능성 | `%`로 전체 목록 열람(노출 공지만이라 정보 노출은 없으나 의미 오류) | 실 DB 테스트로 고정 |
| 공개 불변식 누락(비노출·삭제 공지 검색 노출) | 비공개 정보 유출 | 구현에 조건 하드코딩 + 실 DB 테스트(같은 키워드의 비노출·삭제 제외) |
| 검색어 반사 XSS | 공개 화면 스크립트 실행 | `th:value`·`th:text`만 사용, 컨트롤러 테스트 + 실기 |
| 서비스 시그니처 변경으로 테스트 대량 수정 | 리뷰 부담 | 기계적 변경으로 한정, 단언 의미는 유지 |

## 5. 완료 기준

- [x] `/notices?keyword=…`가 노출·미삭제 공지 중 제목에 키워드가 포함된 것만 최신순 10건씩 보여 준다. 키워드가 없거나 공백이면 기존 목록과 같다.
- [x] `%`·`_`·`!`가 문자 그대로 검색되고, 100자 초과 키워드는 DB 조회 없이 빈 결과(200)다.
- [x] 페이지 이동 시 검색어가 유지되고, 검색어는 화면에서 이스케이프된다.
- [x] `SecurityConfig`·레이트리밋·스키마 변경 없음.
- [x] 신규·수정 테스트 통과, `./gradlew test` 전체 통과.
- [x] Playwright 실기(쟁점 8) 통과, 데이터 원복.
- [x] `publicweb/notice/CLAUDE.md`·계획 인덱스 갱신.

## 구현·검증 결과 (2026-10-07)

### Context
공개 공지 목록 `/notices`에 제목 키워드 검색을 더했다. v2 계획대로 구현했고(검색 대상 제목만은 승인 시 사용자가 확정), 스키마·`SecurityConfig`·레이트리밋은 바꾸지 않았다. 브랜치는 `feat/public-notice-search`이고 커밋·PR 전이다. 직전 작업(#109)의 로드맵 29차 반영 문서 3개도 작업 트리에 함께 있으며 별도로 커밋한다.

### 핵심 확정 사항
- 저장소 `NoticeRepositoryCustom.searchPublishedByTitle(keyword, pageable)` — `deleted=false AND useYn=true AND title contains(keyword)`를 하나의 `BooleanBuilder`로 만들어 목록·COUNT가 공유한다. 정렬은 기존 `toOrderSpecifiers`를 재사용한다.
- 서비스 `getPublishedNotices(int page, String rawKeyword)` → `PublicNoticeListResult(page, keyword)`. `strip()` 후 비면 기존 `findByDeletedFalseAndUseYnTrue` 경로, `length()>100`이면 DB 조회 없이 `Page.empty(pageable)`.
- 컨트롤러 `@RequestParam(required = false) String keyword`, 모델 `keyword`. 템플릿에 검색 폼(`maxlength="100"`)·"전체 보기"·검색 결과 없음 문구를 넣고, 페이지 링크는 `th:if`로 키워드 유무에 따라 나눴다.
- 계획과 다른 결정은 없다.

### 구현 파일
- `src/main/java/com/cms/admin/notice/repository/NoticeRepositoryCustom.java`, `NoticeRepositoryImpl.java`
- `src/main/java/com/cms/publicweb/notice/dto/PublicNoticeListResult.java`(신규)
- `src/main/java/com/cms/publicweb/notice/service/PublicNoticeService.java`
- `src/main/java/com/cms/publicweb/notice/controller/PublicNoticeController.java`
- `src/main/resources/templates/public/notice/list.html`, `src/main/resources/static/css/public/notice.css`
- 테스트: `NoticeRepositoryDataJpaTest`(+5), `PublicNoticeServiceTest`(기존 5건 시그니처 전환 + 신규 5메서드 8케이스), `PublicNoticeControllerTest`(기존 목록 7건 시그니처 전환 + 신규 5)
- `src/main/java/com/cms/publicweb/notice/CLAUDE.md`

### 검증 결과
| 검증 | 결과 |
|---|---|
| 대상 테스트(저장소 실 MariaDB·공개 공지 전체) | `NoticeRepositoryDataJpaTest` 18·`PublicNoticeControllerTest` 38·`PublicNoticeServiceTest` 31 등 실패 0 |
| 판별력 — 구현을 이스케이프 없는 `title.like("%"+kw+"%")`로 잠시 바꿔 실행 | 와일드카드 테스트 1건만 정확히 실패 → QueryDSL 이스케이프에 의존한다는 근거를 테스트가 지킨다. 원복 확인 |
| `./gradlew test` 전체 | 1392건(기존 1374 + 18), 실패 0, 건너뜀 11(Windows 링크 테스트) |
| 실기 — dev Docker + Playwright **비로그인** | 앞뒤 공백 검색어 → `실기검색`으로 정규화, 노출 11건만 `1 / 2`(비노출·삭제 제외), 다음/이전 링크에 키워드 유지, 2페이지 1건. `100%`·`a_b`·`%` → 해당 문자가 든 제목만. 불일치 → "검색 결과가 없습니다."(200). 101자 → 결과 없음(200), 100자 → 검색. XSS(`<script>`·`" autofocus onfocus="…`) → 원문 미노출·`onfocus` 속성 없음·입력란에 글자로만 표시. 무검색·공백 검색 → 기존 목록(키워드 링크 없음). 상세 200, 비노출 상세 404, `POST /notices` 403. 관리자 공지 검색은 기존대로 비노출 포함 12건(회귀 없음). 스크린샷 `screenshots/public-notice-search-01-page1.png`·`-02-xss-no-result.png`(커밋 제외) |
| 실제 SQL | `... where deleted=? and use_yn=? and title like ? escape '!'`(목록·COUNT 동일 조건) — dev `show-sql` 로그 |
| 비용 실측(dev Docker MariaDB, 3회 반복 중 마지막=캐시 데워진 상태, `SHOW PROFILES`) | **1만 행**: 흔한 검색어 목록 3.9ms + COUNT 5.3ms, 불일치 3.7+3.1ms, 기본 목록 3.5+3.1ms, 큰 페이지(offset 9990) 16.0+3.8ms. **5만 행**: 흔한 검색어 20.1+18.6ms, 불일치 18.7+16.8ms, 기본 목록 17.4+14.7ms, 큰 페이지 37.0+22.0ms. HTTP 왕복(1만 행, 렌더링 포함): 검색 약 86ms, 기본 목록 약 84ms, 불일치 약 21ms |
| 원복 | 측정용 1만 행 삭제(남은 0), 검증용 공지 17건 삭제(204), 공개 목록 노출 0건(처음 상태), 스택 종료(볼륨 보존) |

**비용 결론**: 검색은 기본 목록과 같은 차수(전체 행 스캔)이고, 검색어 유무의 차이는 수 ms다. 5만 행에서 한 요청(목록+COUNT)은 약 32~39ms(기본 목록 32.1ms, 검색 35.5~38.7ms, 큰 페이지 59.0ms)다. **후속 기준: 전체 `notice` 행수가 5만을 넘거나 운영에서 검색 응답이 100ms를 넘으면 FULLTEXT(ngram) 등 검색 인덱스를 재평가한다.**

### 이슈
- 첫 비용 측정 스크립트의 집계 SQL이 `information_schema.PROFILING`에 없는 `QUERY` 열을 참조해 실패했다 → `SHOW PROFILES`로 바꿔 재측정했다(측정 도구 문제, 앱과 무관).

### 후속
- 버스트 동시 부하(관리자 요청 지연)·동시 검색 수 제한 — 기존 공개 목록과 같은 위험, 범위 밖(v2 기각 사유).
- 본문 검색·FULLTEXT — 위 기준 도달 시.
- 상세 → 목록 복귀 시 검색어 유지 — 필요해지면 별도.
