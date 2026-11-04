# PLAN — 공개 목록 전용 조회 + 웹진 본문 앞부분만 읽기

> 상태: **구현·검증 완료 (2026-11-04, 커밋·PR 전)** — 계획 v2(적대적 리뷰 2라운드 ship) 그대로 구현, 대조 측정 기준 충족. 결과는 아래 "구현·검증 결과"

## Context

`PLAN-webzine-excerpt-measurement.md`(PR #200)가 공개 목록의 비용을 쟀다. 스트레스 사례(200KB 본문 50건)의 목록형 응답이 현실적인 글(4.4KB)보다 73~74ms 느렸고, 그 원인은 "목록 쿼리가 유형과 무관하게 본문 전체를 읽는 비용"이라는 **가설**이다(본문을 뺀 대조군은 재지 않았다). 사용자는 2026-11-04에 처방 ②를 골랐다 — 공개 목록이 본문 전체를 읽지 않게 하고, 웹진형만 요약에 필요한 앞부분을 읽는다. **스키마·인가 정책·의존성 변경 없음.** 요약 계산 비용(스트레스 사례 59ms)은 남는 것으로 수용했다.

### 정찰에서 확인한 사실

- `PostRepositoryImpl.searchPublished`는 공용 `page()`(`selectFrom(post)`)로 `Page<Post>`를, `findPinnedPublished`는 `List<Post>`를 돌려준다. 둘 다 `content`(MEDIUMTEXT, 최대 200,000바이트)를 읽는다. 호출자는 `PublicBoardService.getPublishedPosts` 하나뿐이다. `page()`는 관리 `searchPosts`도 쓰므로 건드리지 않는다.
- 공개 목록 DTO(`PublicPostSummary.from` + `NewBadge.isNew`)가 쓰는 `Post` 필드는 `id`·`title`·`createDate`·`viewCount`·`coverImageId`·`publishStart`와, 웹진형 일반 목록의 `content`뿐이다. `PublicPostSummary.from(Post, …)`의 호출자는 서비스뿐이다.
- 프로젝션 선례가 있다: `PublishedPostRow`·`PostSearchRow`(`Projections.constructor` + record, 패키지 `admin.board.repository`, `publicweb.home`이 소비).
- `Post.content`는 `@Lob`이다. **Spike(실제 MariaDB) 결과**:
  - HQL `substring(post.content, …)`은 QueryDSL `content.substring`·`stringTemplate` 모두 `FunctionArgumentException`(`mapped to 'CLOB'`)으로 실패한다.
  - `function('mid', post.content, 1, n)`과 네이티브 `SUBSTRING(content, 1, n)`은 둘 다 `String`을 돌려준다.
  - 경계 실측(`n`=20,000): `a`×19,999 + 이모지 + … → 20,001 유닛(20,000 코드 포인트), 이모지 25,000개 → 40,000 유닛, 한글 30,000자 → 20,000 유닛, 짧은 글 → 그대로. 즉 **코드 포인트 단위**다.
- 시험 파급: `PostRepositoryDataJpaTest`(`Page<Post>`·`Post::getId` 단언 약 12곳), `PublicBoardServiceTest`(`post(1L)` 픽스처를 쓰는 stub 약 5곳). 실스택 시험(`PublicBoardIntegrationTest`·`BoardTypePublicIntegrationTest`·`BoardPinPublicIntegrationTest`·`PostPublishPeriodPublicIntegrationTest`)은 HTTP로만 보므로 코드가 바뀌지 않고 회귀 그물이 된다.

### 핵심 쟁점

1. `@Lob`이라 HQL `substring`을 못 쓴다 — 앞부분을 어떻게 읽나.
2. 요약 결과가 지금과 **문자 그대로 같아야** 한다 — `PARSE_LIMIT`은 UTF-16 유닛, `SUBSTRING`은 코드 포인트.
3. 공개 불변식(`isPublished` 조건 객체를 SELECT·COUNT가 공유)을 유지해야 한다.
4. 73~74ms 절감은 가설이다 — 구현 전후를 같은 절차로 재서 확인해야 한다.

## 설계 결정

### D1. 목록 쿼리는 유형과 무관하게 본문을 읽지 않는다 — record 프로젝션

`searchPublished`·`findPinnedPublished`가 `PublishedPostListRow(Long id, String title, LocalDateTime createDate, Long viewCount, Long coverImageId, LocalDateTime publishStart)`를 돌려준다.

- **왜**: `PublishedPostRow` 선례와 같고, 명시적 스칼라 프로젝션이라 **생성 SQL이 본문 컬럼을 선택하지 않는다**. 이것이 이번 변경의 핵심 계약이다. record 타입만으로는 보장되지 않으므로(엔티티를 읽어 변환해도 컴파일된다, v2 R1-2) **생성 SQL을 검사하는 시험으로 고정**한다(D6).
- 기각: 엔티티에서 `content`를 지연 로딩(`@Basic(fetch = LAZY)`) — 바이트코드 향상이 필요하고 프로젝트 전체 엔티티 동작이 바뀐다. `content` 매핑에서 `@Lob` 제거 — `ddl-auto: validate`·관리 경로까지 파급되어 이 작업 범위를 넘는다.
- `searchPublished`는 공용 `page()`를 쓰지 않고 자기 프로젝션 쿼리를 갖되, **조건 객체 하나를 SELECT·COUNT가 공유**하는 구조·`toOrderSpecifiers` 정렬을 그대로 쓴다. `isPublished`는 그대로다. `findPinnedPublished`의 조건·정렬·`limit`도 그대로다.

### D2. 웹진 앞부분은 id로 따로 읽는다 — 네이티브 쿼리 + 웹진일 때만

`PostRepository`에 추가:

```java
@Query(value = "SELECT id AS id, SUBSTRING(content, 1, :length) AS head FROM post WHERE id IN (:ids)", nativeQuery = true)
List<PostContentHead> findContentHeads(@Param("ids") Collection<Long> ids, @Param("length") int length);
```

서비스는 웹진형일 때만, 일반 목록의 현재 페이지 id로 부른다(빈 페이지면 부르지 않는다 — `IN ()` 방지).

| 안 | 장점 | 단점 |
|---|---|---|
| **B. 2단계: 목록 + id로 앞부분(채택)** | 목록 쿼리가 모든 유형에 **하나**(분기 없음). 본문 접근이 PK 조회 ≤50행으로 **구조적으로 상한** — 게시판 글 수와 무관. 네이티브 쿼리라 Hibernate 함수 검사와 무관 | 웹진형에 쿼리 1개 추가 |
| A. 한 쿼리에 `function('mid', content, 1, n)` | 쿼리 1개, 앞부분과 공개 조건이 구조상 같은 쿼리 | 프로젝션이 유형별로 갈린다. 정렬 전 행까지 본문을 건드리는지를 DB 최적화기에 맡긴다(filesort 동작). Hibernate에 등록되지 않은 함수 이름에 기댄다 |
| C. `@Lob` 제거 후 `substring` | 코드가 가장 평범 | 쟁점 범위 초과(D1 기각과 같다) |

- **공개 불변식과의 관계**: `findContentHeads`는 자체 공개 조건이 없다. **입력 id가 공개 판정을 통과한 목록 결과일 때만** 부를 수 있고 호출자는 `PublicBoardService` 한 곳이다(javadoc과 시험으로 고정). 응답은 이 값으로 `PostExcerpt`를 거친 **평문 요약**만 내보낸다(`th:text`, 기존 그대로) — 다만 평문이어도 공개 판정을 받지 않은 본문이 나가면 안 되므로 아래 스냅샷 고정이 필요하다.
- **스냅샷을 명시로 고정한다(v2 R1-1)**: 앱·compose 어디에도 격리 수준 설정이 없어 지금은 서버 기본값에 기댄다. READ COMMITTED로 돌면 목록 조회 뒤 관리자가 글을 비공개로 바꾸며 본문을 고쳤을 때, 두 번째 쿼리가 **공개 판정을 받지 않은 새 본문**을 읽는다. 그래서 `getPublishedPosts`를 `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`로 한다(격리 수준 명시는 서비스 9곳에 선례). 호출자가 컨트롤러뿐이라 최상위 트랜잭션이다. 스냅샷은 첫 일관 읽기(게시판 조회)에서 정해지고 두 쿼리가 같은 버전을 본다. 별도 연결로 수정을 끼워 넣는 시험으로 고정한다(D6).
- 같은 스냅샷이므로 목록에 있던 행이 앞부분 조회에서 사라지는 일은 **기대하지 않는다**. `heads.get`이 null이면 요약 줄을 생략하는 것은 방어일 뿐 정상 경로가 아니다.
- 기각(v2): 앞부분 조회에 공개 조건을 복제 — 공개 조건 표현이 다섯 곳에서 여섯 곳으로 늘어(`com.cms.admin.board` "한 곳을 바꾸면 모두 바꾼다") 표류 위험이 커진다.
- `length`는 `PostExcerpt.PARSE_LIMIT`을 서비스가 넘긴다(상수 이중화 방지, `PostExcerpt`는 `publicweb` 소속이라 리포지토리가 직접 참조하지 않는다).

### D3. 요약 결과는 지금과 같다 — 코드 포인트 앞부분이면 충분하다

주장: `n = PARSE_LIMIT`일 때 `PostExcerpt.of(코드 포인트 앞 n개) == PostExcerpt.of(전체)`.

근거: 코드 포인트 n개는 UTF-16 유닛 n개 이상이다. `PostExcerpt`는 입력이 `PARSE_LIMIT` 유닛을 넘으면 그 자리에서 다시 자르므로(하위 서로게이트 경계 처리 포함) 결과가 전체를 넣었을 때와 같다. 경우별:
- 전체 ≤ n 코드 포인트: 앞부분 = 전체, 자명.
- 초과 + 앞 n개가 전부 BMP: 앞부분 정확히 n 유닛 → 자르지 않음. 전체는 n 유닛에서 잘리는데 마지막 문자가 BMP라 경계 보정이 없어 같은 문자열.
- 초과 + 앞 n개에 보충 문자 포함: 앞부분 > n 유닛 → 두 경우 모두 같은 위치에서 같은 규칙으로 자름.

그래서 `PostExcerpt`는 **바꾸지 않는다**. 단위 시험으로 위 경우를 전부 고정하고, 실제 DB 시험으로 `findContentHeads` 결과가 코드 포인트 앞부분임을 고정한다(둘을 합치면 주장이 성립).

- 전제: `post.content`는 utf8mb4이고 커넥션 `character_set_results`=utf8mb4(spike 확인).
- 전송량 상한: 한 글 최대 20,000 코드 포인트(최대 80KB, 보통 그 이하) × 50.

### D4. 서비스·DTO

- `PublicPostSummary.from(Post, …)`을 `from(PublishedPostListRow, …)`로 바꾼다(호출자는 서비스뿐, 오버로드를 남기지 않는다). `Post` import 제거.
- `getPublishedPosts`: `toPinnedSummary`는 요약 없음 그대로. 일반 목록은 `Page<PublishedPostListRow>`를 받은 뒤 `webzine`이면 `findContentHeads` → `Map<Long, String>`, 요약은 `PostExcerpt.of(heads.get(row.id()))`(`of(null)`은 이미 null). 검색 있는 경로와 없는 경로가 같은 처리를 쓰도록 private 메서드 하나로 묶는다.
- 고정 영역은 앞부분을 읽지 않는다(지금도 요약을 쓰지 않는다).
- `NewBadge.isNew(createDate, publishStart, …)`, 조회수 필터, 페이지 보정, 키워드 처리는 그대로다.

### D5. 구현 전후 대조 측정 — 완료 기준

`PLAN-webzine-excerpt-measurement.md`와 **같은 절차·같은 S1·S3a 데이터**(부록 `l2-measure.sh`, 시드 `l2-seed.sql`, `bootJar`·`-Xms512m -Xmx512m`·`show-sql` 끔·레이트리밋 끔, 세트별 ABBA ×2, 표본 200, 비-200 블록 무효, `trap` 정리)로 **구현 전 jar와 구현 후 jar를 같은 세션에서** 잰다. 순서는 **전 → 후 → 후 → 전**(v2 R1-3 — 전·후 어느 쪽도 늘 나중에 재지 않게 균형을 맞춘다, 지난 두 실행의 차이가 약 3ms였다). 쌍은 (전1, 후1)과 (전2, 후2)이고 전2가 마지막 실행이다. 실행마다 앱을 새로 띄워 같은 워밍업을 거치고, 실행별·블록별 중앙값을 모두 보고한다. 구현 전 jar는 정찰 때 보관했다(앱 코드가 `origin/master`와 같음을 diff로 확인).

판정 기준(측정 전에 고정):

- **절감 확인**: S3a LIST 중앙값 (전 − 후)을 두 쌍 모두 기록한다. **두 쌍 모두 20ms 이상 줄면 "가설 확인"**으로 본다. 그렇지 않으면 원인 가설이 틀렸거나 일부만 맞은 것이므로 **완료를 주장하지 않고** 값을 보고해 이 변경을 머지할지 사용자에게 묻는다.
- **스트레스 웹진 회귀 없음(v2 R1-3)**: S3a WEBZINE의 (후 − 전)이 두 쌍 모두 **5ms 초과**면 회귀다. 59ms 수용은 새 조회 방식이 더하는 지연까지 수용한다는 뜻이 아니므로, 초과하면 완료를 주장하지 않고 원인을 확인해 사용자에게 묻는다. 줄어들면 그 값을 기록한다.
- **회귀 없음**: S1 LIST·S1 WEBZINE의 (후 − 전)이 두 쌍 모두 **5ms 초과**면 회귀로 보고 원인을 확인한다(S1 WEBZINE은 쿼리가 하나 는다).
- 측정 범위의 한계는 그 문서와 같다(로컬 Windows·단일 요청·워밍 상태).

### D6. 시험

- `PostRepositoryDataJpaTest`: 기존 `searchPublished`·`findPinnedPublished` 단언을 row로 옮긴다(공개 경계·COUNT 누수·LIKE·정렬 시험의 **의미는 그대로**). 추가: row의 필드 매핑 왕복(조회수·대표·`publishStart`), `findContentHeads` — 코드 포인트 경계(위 spike의 네 경우)·요청하지 않은 id 제외·없는 id 무시.
- `PostExcerptTest`: D3의 세 경우 + 경계 이모지에서 `of(코드 포인트 앞부분) == of(전체)`, 꼬리에 다른 텍스트.
- `PublicBoardServiceTest`: 픽스처를 row로 바꾼다. 추가: 목록형·갤러리형은 `findContentHeads`를 부르지 않는다, 웹진형은 현재 페이지 id와 `PARSE_LIMIT`으로 부른다, 빈 페이지·고정 영역은 부르지 않는다, 앞부분이 없는 id는 요약 null.
- 실스택: `BoardTypePublicIntegrationTest`에 긴 본문(파싱 상한 초과, 경계 이모지) 웹진 요약 한 건. 기존 공개 시험은 그대로 통과해야 한다.
- **본문 미조회 계약(v2 R1-2)**: 시험 전용 Hibernate `StatementInspector`로 실제 서비스를 부를 때의 SQL을 모은다. 목록형·갤러리형: 어떤 문장도 `content` 컬럼을 선택하지 않는다. 웹진형: `content`가 나오는 문장은 앞부분 조회 하나뿐이고 `substring`으로 감싸여 있으며, 고정 영역 쿼리에도 없다. 단어 경계로 검사한다(`cover_image_id` 등과 섞이지 않게). 변이 실험: `searchPublished`를 엔티티 조회로 되돌리면 실패하는지 확인한다.
- **스냅샷 계약(v2 R1-1)**: `@MockitoSpyBean` `PostRepository`로 `searchPublished` 호출 직후·`findContentHeads` 전에 **별도 연결**이 그 글의 본문을 바꾸고 비공개로 전환해 커밋한다. 응답 요약은 옛 본문이어야 하고 새 본문 문자열이 없어야 한다. 실행 격리 수준 단언은 `TransactionSynchronizationManager.getCurrentTransactionIsolationLevel()`. 변이 실험: `isolation`을 `READ_COMMITTED`로 바꾸면 실패하는지 확인한다.
- 컨벤션·변이 실험: `isPublished`를 목록 쿼리에서 빼면 시험이 실패하는지, `findContentHeads` 호출 조건을 `webzine` 없이 바꾸면 시험이 실패하는지 확인한다.

## 범위·변경 사항

- 변경: `PublishedPostListRow`·`PostContentHead`(신규), `PostRepositoryCustom`·`PostRepositoryImpl`·`PostRepository`, `PublicBoardService`, `PublicPostSummary`, 시험, `CLAUDE.md`(`com.cms.publicweb.board`·`com.cms.admin.board`의 공개 목록 서술).
- **스키마·`SecurityConfig`·공개 경로·레이트리밋·템플릿 변경 없음.** 롤백은 코드 되돌리기뿐이고 데이터 영향이 없다.
- 브랜치: `refactor/public-list-projection`, **PR #200 머지 후 `origin/master`에서 딴다**(같은 `CLAUDE.md` 줄을 고쳐 충돌하므로).

## 작업 단계

1. record 2개·리포지토리 시그니처·쿼리 → 컴파일 확인.
2. `PublicPostSummary`·`PublicBoardService` → 컴파일 확인.
3. 시험 이관·추가, 영향 범위 시험(`com.cms.admin.board.*`·`com.cms.publicweb.board.*`·`*ConventionTest`) 통과, 변이 실험.
4. 실기: 8081에서 목록형·갤러리형·웹진형 화면 + 검색·페이지 이동·고정 영역·비공개 글 비노출을 Playwright로 확인.
5. D5 대조 측정 → 판정.
6. 문서 갱신.

## 리스크

- **절감이 가설보다 작을 수 있다**: `SUBSTRING`도 DB 안에서 off-page 본문을 읽을 수 있고, 목록형의 차이가 다른 원인일 수 있다 → D5가 머지 전에 드러낸다.
- **`findContentHeads`가 공개 조건 없이 본문을 읽는다**: 호출자 하나·공개 판정을 통과한 id만·평문 요약으로만 출력. 다른 호출자가 생기면 위험하므로 javadoc에 명시한다.
- **기존 공개 시험의 기대값 변경 유혹**: 단언의 의미를 약화하지 않고 타입만 옮긴다.

## 구현·검증 결과 (2026-11-04)

### Context

공개 목록이 유형과 무관하게 본문 전체를 읽던 것을 없애고, 웹진형만 요약에 필요한 앞부분을 따로 읽게 했다. 측정 문서의 "73~74ms는 본문 조회 비용"이라는 가설을 구현 전후 대조로 확인했다. **스키마·인가 정책·공개 경로·레이트리밋·템플릿·의존성 변경 없음.**

### 핵심 확정 사항

- 목록·고정 쿼리는 스칼라 프로젝션 `PublishedPostListRow`, 웹진 앞부분은 네이티브 `findContentHeads`(D1·D2). `isPublished`와 SELECT·COUNT 조건 공유는 그대로다.
- `getPublishedPosts`에 `REPEATABLE_READ` 명시(D2). 요약 동등성은 `PostExcerpt` 무변경으로 성립(D3).
- 계획과 달라진 결정 없음. 시험 수단만 구체화했다 — 스냅샷 시험은 스파이 대신 `StatementInspector` 훅으로 앞부분 조회 직전에 별도 스레드·연결이 커밋한다(스파이가 필요 없고 시점이 결정적이다).

### 구현 파일

| 파일 | 내용 |
|---|---|
| `admin/board/repository/PublishedPostListRow.java`·`PostContentHead.java`(신규) | 목록 행·앞부분 결과 |
| `admin/board/repository/PostRepositoryCustom.java`·`PostRepositoryImpl.java`·`PostRepository.java` | 프로젝션 쿼리, `findContentHeads` |
| `publicweb/board/service/PublicBoardService.java`·`dto/PublicPostSummary.java` | 웹진만 앞부분 조회, 격리 수준, DTO 입력 교체 |
| 시험 | `PublicListQueryContractIntegrationTest`(신규), `PostRepositoryDataJpaTest`·`PublicBoardServiceTest`·`PostExcerptTest`·`BoardTypePublicIntegrationTest` |
| 문서 | `publicweb/board`·`admin/board`의 `CLAUDE.md`, `docs/troubleshooting.md`, 계획 인덱스 |

### 검증 결과

**시험**: 영향 범위(`com.cms.admin.board.*`·`com.cms.publicweb.board.*`·`*ConventionTest`, 브라우저 제외) 608건 통과. 전체 `./gradlew cleanTest test`(브라우저 시험 포함) 3,015건 통과, 실패·오류 0, 건너뜀 12건은 전부 이번 변경과 무관한 `LocalDiskFileStorageTest`. 코드 리뷰 1라운드 지적 0건.

**변이 실험(전부 원복 확인)**:

| 변이 | 실패한 시험 |
|---|---|
| 목록이 엔티티를 함께 읽음 | SQL 계약 2건 |
| 격리 수준 `READ_COMMITTED` | 스냅샷 시험 1건 |
| 목록 조건에서 `isPublished` 제거 | `PostRepositoryDataJpaTest` 공개 경계·COUNT 누수 7건 |
| 유형과 무관하게 앞부분 읽음 | SQL 계약 1건 + `PublicBoardServiceTest` 1건 |

**대조 측정(D5)**: 측정 문서 부록과 같은 스크립트·S1·S3a 데이터, `-Xms512m -Xmx512m`·`show-sql` 끔·레이트리밋 끔, 세트별 ABBA ×2. 환경은 Windows 10 Pro 19045, i5-10400F, Corretto 21.0.9, `mariadb:10.11@sha256:d769e379…`. 순서 전 → 후 → 후 → 전, 실행마다 앱을 새로 띄움, 실행마다 16블록 전부 유효(표본 전부 200, 블록 전후 응답 검사 통과), 정리 후 측정 게시판·글 0행·8081 비어 있음. 중앙값 ms:

| 실행 | S1 LIST | S1 WEBZINE | S3a LIST | S3a WEBZINE |
|---|---|---|---|---|
| 전1 | 47.7 | 47.6 | 122.9 | 182.2 |
| 후1 | 42.8 | 46.7 | 37.7 | 110.4 |
| 후2 | 41.1 | 46.0 | 37.7 | 109.6 |
| 전2 | 50.6 | 53.0 | 124.1 | 179.7 |

| 판정 | 전1 → 후1 | 전2 → 후2 | 기준 | 결과 |
|---|---|---|---|---|
| S3a LIST | −85.2 | −86.4 | 둘 다 −20 이상 | **가설 확인** |
| S3a WEBZINE | −71.8 | −70.1 | 둘 다 +5 초과면 회귀 | 회귀 없음(감소) |
| S1 LIST | −4.9 | −9.5 | 〃 | 회귀 없음 |
| S1 WEBZINE | −0.9 | −7.0 | 〃 | 회귀 없음 |

블록 중앙값 ms: 전1 S3a L 124.5·120.9·118.1·127.1 / W 183.9·182.9·180.4·179.8, 후1 S3a L 38.9·38.0·36.9·36.3 / W 112.1·110.4·112.6·108.6, 후2 S3a L 37.6·37.2·42.1·37.0 / W 114.6·107.7·109.8·108.9, 전2 S3a L 127.8·124.1·124.8·120.6 / W 179.6·183.8·177.7·179.4. S1은 전1 L 49.3·40.3·48.6·44.3 / W 51.3·44.6·43.2·51.8, 후1 L 45.5·38.7·38.6·37.8 / W 49.4·44.7·42.9·43.5, 후2 L 46.3·39.2·38.4·38.1 / W 48.0·44.4·44.1·45.9, 전2 L 53.4·49.9·51.7·48.3 / W 60.5·48.1·44.0·43.0.

해석과 한계:
- 절감(약 85ms)이 측정 때의 S3a − S1 차이(73~74ms)보다 크다. 구현 후 S3a LIST(37.7)가 S1 LIST(41~43)보다 빠른 것은 각 실행에서 S1을 먼저 재는 워밍 편향으로 보인다(S1 후반 블록은 38ms대로 같다) — 전후 비교에는 똑같이 들어간다. 원인을 따로 검증하지는 않았다.
- 웹진형에 남은 약 72ms(110 − 38)는 파싱 약 59ms(L1)와 앞부분 쿼리·전송 몫이다. 앞부분 쿼리 비용만 따로 재지는 않았다.
- 로컬 Windows·단일 요청·워밍 상태 값이고 동시 부하·운영 기계는 판단하지 않는다. 글이 아주 많은 게시판(필터 행이 많은 경우)은 재지 않았다.

**실기(`after.jar`, 8081, dev DB, 익명, Playwright)**: 목록형·갤러리형·웹진형 게시판(글 12 + 고정 1 + 숨긴 1 + 예약 1)에서 1페이지·2페이지·제목 검색이 설계대로다 — 항목 수(일반 10 + 고정 영역 1, 2페이지 3건), 웹진 요약 표시, 고정 글은 고정 영역과 일반 목록 양쪽에 보임(기존 동작), **숨긴·예약 글은 목록·검색·본문 문자열 어디에도 없음**, 숨긴 글 상세·숫자 id·없는 슬러그 404, `POST` 403, 공개 글 상세는 본문 그대로. 콘솔 오류는 기존 `favicon.ico` 404뿐, 앱 로그 오류 0건. 검증 행은 지워 원복했다(측정 게시판 0행, 게시판 9·글 6). 스크린샷 `public-list-projection-screenshots/`(커밋 제외).

### 이슈

1. `@Lob` 때문에 HQL `substring`이 거부됐다(`docs/troubleshooting.md`). 구현 전 spike가 잡았다.
2. 시험 추가 스크립트가 셸 따옴표 오류로 멈췄고 적용된 것이 없음을 확인한 뒤 편집 도구로 넣었다. 계약 시험의 `CmsTestApplication` import 경로 오류는 컴파일에서 잡혀 고쳤다(앱 코드와 무관).

### 후속

- 로드맵 ④(요약 컬럼)는 채택하지 않았다 — 머지 후 `updateRoadmap`에서 이 측정과 함께 반영한다.
- 요약 파싱 비용을 더 줄이려면 `PARSE_LIMIT` 축소가 남은 선택지다(측정 문서 안 ④, 요약이 비는 글이 늘어나는 맞바꿈). 지금 필요하다는 근거는 없다.

## 개정 이력

- v1 (2026-11-04): 최초 작성.
- v2 변경(리뷰 1라운드, 3건 수용): R1-1 "같은 트랜잭션이라 같은 스냅샷"은 근거 없는 전제였다(격리 수준 설정 없음) — `getPublishedPosts`에 `REPEATABLE_READ` 명시, 별도 연결로 끼워 넣는 동시 수정 시험 추가, 행 누락은 방어일 뿐 정상 경로가 아님을 명시(D2·D6). 기각: 앞부분 조회에 공개 조건 복제 — 공개 조건 표현이 하나 더 늘어 표류 위험이 커진다. R1-2 "record라 컴파일 단계에서 보장"은 과장이었다 — 보장을 "스칼라 프로젝션의 생성 SQL"로 고치고 `StatementInspector` 시험·변이 실험 추가(D1·D6). R1-3 S3a 웹진 회귀 기준(두 쌍 모두 +5ms 초과면 회귀) 추가, 측정 순서를 전→후→후→전으로 균형, 실행별·블록별 값 보고(D5).
