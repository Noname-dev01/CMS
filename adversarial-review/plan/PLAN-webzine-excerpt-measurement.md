# PLAN — 웹진 요약 비용 측정 (로드맵 "후속 과제 — 게시판 정의 고도화" ④의 착수 조건)

> 상태: **측정 완료 (2026-11-03)** — 계획 v4(적대적 리뷰 4라운드 ship) 그대로 측정, 사전 기준 A·B·C 모두 충족(스트레스 사례에서만). 처방은 ② 목록 전용 조회 + 본문 앞부분만으로 결정(2026-11-04, 구현은 별도 계획). 판정과 선택지는 아래 "측정 결과·판정"

## Context

웹진형 공개 목록의 요약(`PostExcerpt.of`)은 조회 때 저장된 본문 앞 20,000 UTF-16 유닛을 jsoup으로 읽어 만든다(`PLAN-board-definition.md` PR 6 D5). 로드맵 ④는 이 비용을 **측정하지 않았다**고 기록하고 "웹진형 목록 응답 시간이 문제로 확인될 때(측정이 먼저)" `post.excerpt` 컬럼 + 백필 마이그레이션 + 저장 경로 변경을 착수 조건으로 걸어 두었다. 이번 작업은 그 **측정**이다. 결과가 기준을 넘을 때만 스키마 변경을 별도로 고지하고 제안한다.

### 정찰에서 확인한 사실

- 공개 목록은 `PublicBoardService.getPublishedPosts` → `PostRepositoryImpl.searchPublished`(QueryDSL `selectFrom(post)`) → `Page<Post>`(엔티티 전체). **본문 `content`(MEDIUMTEXT, 저장 상한 200,000바이트)를 게시판 유형과 무관하게 읽는다** — 목록형·갤러리형도 마찬가지다. 페이지 크기는 게시판 설정 10·20·30·50.
- 웹진형 일반 목록만 `PostExcerpt.of(post.getContent())`를 부른다(고정 영역은 요약 없음). `PostExcerpt`는 `substring` → `Jsoup.parseBodyFragment` → 텍스트 노드 재귀 순회 → 공백 정규식 접기 → 120 코드 포인트 절단이다.
- 본문 저장 검증(`ContentBodyPolicy.sanitize`): 보이는 글자 `textLength ≤ 10,001`, 정리 HTML ≤ 200,000바이트(UTF-8). **보이는 글자는 텍스트 코드 포인트 + 블록 종료마다 1 + 블록 끝이 아닌 `br`마다 1**이다(`HtmlContentSanitizer.visibleLength`). 이미지는 0자다. 그래서 200KB에 가까운 본문은 이미지·링크처럼 글자 없이 바이트를 쓰는 마크업이 많은 글뿐이다.
- 공개 게시판 레이트리밋 `public-board` 120/60초(IP), `cms.rate-limit.enabled`로 전체를 끈다.
- dev 프로파일은 `spring.jpa.show-sql: true`이고, `bootRun`에는 `developmentOnly` devtools가 붙어 템플릿 캐시를 끈다 — 둘 다 측정을 왜곡한다(D2).
- 성능 측정 시험·JMH는 프로젝트에 없다.

### 핵심 쟁점

1. **비용이 두 갈래로 섞여 있다** — ⓐ 웹진형만 내는 파싱 비용(CPU·할당), ⓑ 모든 유형이 내는 본문 조회 비용(DB → JDBC → 엔티티 → 힙). `excerpt` 컬럼은 ⓐ만 없앤다. 이번 측정은 ⓐ를 판정하고, ⓑ는 **조사가 필요한지**만 가린다(원인 분리에는 코드 계측이 필요해 범위 밖).
2. **판정 기준을 측정 전에 고정해야 한다** — 측정한 뒤 기준을 정하면 결론이 숫자에 끌려간다.
3. **측정 방법** — 신규 의존성 없이, 재현 가능하게, CI를 흔들지 않게, 개발 설정의 왜곡 없이.
4. **입력 데이터** — 실제 저장 경로를 통과할 수 있는 본문만 쓴다. 파싱 상한(20,000 유닛) 때문에 글 한 건의 파싱 입력 크기는 위가 막혀 있다.

## 설계 결정

### D1. 측정 대상을 두 층으로 나눈다

| 층 | 무엇을 재나 | 왜 |
|---|---|---|
| L1 마이크로 측정 | `PostExcerpt.of` 단독 — 한 페이지(50건) 계산의 **스레드 CPU 시간**·경과 시간·스레드 할당 바이트 | 파싱 비용 ⓐ만 본다. JIT 워밍업 후 값 |
| L2 종단 측정 | 실제 앱 `GET /main/boards/{slug}` 응답 시간 — **같은 게시판·같은 글 50건에서 `board_type`만 LIST ↔ WEBZINE으로 바꿔** 비교 | 웹진 − 목록 차이 = 요약 비용 + **템플릿 분기 차이**(사용자 체감 — 파싱만의 절감량이 아니다, v3 R2-1). 목록형 값 = 본문 조회를 포함한 기준선 |

- **요약 컬럼이 없앨 작업은 정확히 `PostExcerpt.of` 호출**이다(컬럼을 읽는 비용은 120자 문자열 하나라 무시한다). 그래서 L1이 "같은 웹진에서 실시간 계산을 사전 계산으로 바꿨을 때의 절감량"을 직접 재는 대조이고, 기준 A는 L1로 판정한다. L2는 그 값이 실제 응답에서도 같은 크기로 보이는지 확인하는 **정합성 검사**다(v3 R2-1).

- 선택지: (a) L1만 — 실제 응답 대비 비중을 모른다 (b) L2만 — 파싱과 템플릿·조회가 섞인다 (c) **둘 다(채택)**. L2의 "같은 데이터·유형만 전환"이 다른 변수를 고정한다.
- L2에는 **본문 크기만 다른 두 데이터 세트**(S1 현실 / L1에서 가장 느렸던 유효 모양)를 둔다. 목록형에서 두 세트의 차이는 "본문을 바꿨을 때의 종단 변화"이고, **본문 조회가 원인이라는 증거는 아니다**(DB 페이지·JDBC 디코딩·엔티티 적재·GC가 섞인다, v2 R1-3).
- **이번 측정이 말할 수 있는 범위: 단일 요청·워밍 상태·로컬 기계.** 동시 요청 부하·콜드 스타트·운영 기계 성능은 판단하지 않는다(v2 R1-5).

### D2. 도구 — 신규 의존성 없음, 측정 코드는 커밋하지 않는다

**L1**
- 같은 패키지(`com.cms.publicweb.board.service` — `PostExcerpt`가 package-private)의 **임시 JUnit 클래스**로 실행한다(`./gradlew test --tests '…ExcerptMeasure'`, Spring 컨텍스트 없음). 끝나면 파일을 지운다.
- 입력은 D3의 모든 모양을 **`ContentBodyPolicy.sanitize(raw, "HTML")`로 통과시킨 결과(`html()`)**만 쓴다 — 예외가 나면 그 모양은 무효다. 정리 결과의 UTF-8 바이트·UTF-16 길이·`textLength`·요약(앞 30자와 null 여부)을 출력해 기록한다(v2 R1-1).
- **워밍 확인과 순서 회전(v3 R2-2)**: 모양마다 배치(= 10페이지) 단위로 워밍업을 돌려 **마지막 3배치의 CPU 중앙값이 서로 10% 안**에 들면 안정으로 보고 측정에 들어간다(최소 6배치 = 3,000건, 최대 30배치 — 그래도 불안정하면 그 사실을 기록하고 마지막 값으로 잰다). 이것을 **새 JVM 3회**(`./gradlew test --rerun-tasks` 3번, 모양 순서를 실행마다 한 칸씩 회전)로 반복한다. 모양별 값은 세 실행의 중앙값이고, 실행 간 변동(세 실행의 최대 − 최소)도 기록한다. **최악 후보**: CPU 중앙값이 가장 큰 모양. 2위와의 차이가 그 모양의 실행 간 변동보다 작으면 둘 다 공동 후보로 L2에 가져간다.
- 측정: (워밍 확인 뒤) 측정 페이지 30회, 페이지당 50건. 페이지 단위로 `ThreadMXBean.getCurrentThreadCpuTime()`(CPU)·`System.nanoTime()`(경과)·`com.sun.management.ThreadMXBean.getThreadAllocatedBytes(현재 스레드)`(할당)를 잰다. CPU 시간·할당 측정은 시작 전에 `isCurrentThreadCpuTimeSupported()`·`isThreadAllocatedMemorySupported()`를 확인하고 꺼져 있으면 켠다(못 켜면 그 지표는 "측정 불가"로 기록). 결과 문자열의 길이·해시를 누적해 마지막에 출력한다(JIT가 호출을 지우지 못하게, v2 R1-2). 중앙값·p95·최대를 낸다.

**L2**
- dev DB(`cms-db-dev`, 3307)에 측정용 게시판·글을 SQL로 넣는다 — **본문은 L1과 같은 생성기가 `ContentBodyPolicy.sanitize`를 통과시킨 HTML**이라 실제 저장 경로로 만들 수 있는 값이다(SQL은 저장 경로의 감사·참조 행만 생략한다).
- 앱은 **`./gradlew bootJar` 산출물을 `java -jar`로** 띄운다 — `developmentOnly` devtools가 빠져 템플릿 캐시가 운영과 같이 켜진다(v2 — `bootRun`은 devtools가 템플릿 캐시를 끈다). 인자: `-Xms512m -Xmx512m`, `--spring.profiles.active=dev`(환경변수 `SPRING_PROFILES_ACTIVE=dev`), `--server.port=8081`, `--spring.jpa.show-sql=false`(v2 R1-7), `--cms.rate-limit.enabled=false`(반복 요청이 `public-board` 120/60초의 429에 막히지 않게 — 이 실행에만), DB·메일 환경변수는 dev 그대로. 실행 명령 전문을 결과에 기록한다.
- 요청: `curl -s -o 응답파일 -w '%{http_code} %{time_total}'`. **표본마다 HTTP 200이 아니면 그 블록 전체를 무효**로 하고 원인을 확인한 뒤 다시 잰다(v2 R1-6). 측정 전후에 응답 HTML을 검사한다 — 일반 목록 항목 50개, 유형 마커(웹진 `board-webzine`·목록 `board-list-item`), 웹진은 요약 줄 개수(L1에서 기록한 요약 null 여부와 일치), 301이 아닌 정확한 슬러그 경로.
- 순서 편향 제거: 블록 하나 = 같은 유형으로 워밍업 20회 + 측정 50회. 유형 전환은 SQL `UPDATE board SET board_type=…`(요청마다 게시판을 읽으므로 즉시 반영). 순서는 **ABBA ×2**(LIST·WEBZINE·WEBZINE·LIST·LIST·WEBZINE·WEBZINE·LIST) — 유형별 200표본, 블록별 중앙값도 남겨 블록 간 흔들림을 본다(v2 R1-7).

**기각한 선택지**
- JMH — 가장 정확하지만 **새 의존성**(프로젝트 규칙상 사전 제안 대상)이고, 판정 단위가 ms라 그 정밀도가 필요 없다.
- 측정 시험 커밋(`@Tag("perf")`) — CI 기계마다 값이 달라 단언을 둘 수 없고, 단언 없는 시험은 유지 비용만 남는다(요청받지 않은 기반, 루트 `CLAUDE.md` 작업 방식 3). **재현에 필요한 전문은 이 문서 "부록"에 싣는다.**
- 구간 계측(서비스·리포지토리 타이머) — 원인 분리에 좋지만 코드 변경이 필요하다. 기준 B가 걸리면 그 조사의 첫 단계로 남긴다.

### D3. 입력 데이터 — 저장 가능한 모양만

모든 모양은 생성 후 `ContentBodyPolicy.sanitize`를 통과해야 하고(`textLength ≤ 10,001`·≤ 200,000바이트), 통과한 정리 결과를 그대로 쓴다. 이미지 `src`는 `/content-images/{숫자}` 형식이면 정리기가 남긴다(존재 여부는 정리기가 보지 않는다 — 저장 API도 없는 ID를 참조 계산에서만 빼고 HTML은 남긴다).

| 이름 | 모양(생성 규칙) | 의도 |
|---|---|---|
| S1 현실 | 한글 평문 문단 10개 × 150자 | 일반적인 글(약 4.5KB) |
| S2 글자 상한 평문 | 한글 평문 문단 99개 × 100자(= 99 × 101 = 9,999자) | 글자 상한을 채운 평문 — 약 10.4K 유닛이라 **파싱 상한에 걸리지 않고 전부 파싱**된다 |
| S3a 문단 밀집 | 머리: `<p>가</p>` 반복으로 앞 20,000 유닛을 채움 → 꼬리: `<p><img src="/content-images/1"></p>` 반복으로 200,000바이트 직전까지 | 파싱 구간의 블록 요소 수 최대 + 바이트 최대(스트레스 사례) |
| S3b 인라인 중첩 밀집 | 머리: `<p>` 안에 `<strong><em><u><s>가</s></u></em></strong>` 반복(문단당 50개)으로 앞 20,000 유닛 → 꼬리: S3a와 같은 이미지 | 깊이 4 중첩 인라인 — 재귀 순회·노드 수 스트레스 사례 |
| S4 이미지 머리 | 이미지 문단 반복으로 200,000바이트 직전까지, 마지막에 평문 문단 하나 | 파싱만 하고 요약이 null인 글(앞 20,000 유닛이 전부 이미지) |

- 머리 구간의 글자 수는 상한 안이다(20,000 유닛 안에 S3a 약 2,500문단 = 5,000자, S3b 약 500자 + 문단 종료). 정확한 값은 L1이 출력·기록한다. 정리기가 어떤 모양을 바꾸면(예: 빈 서식 제거) 바뀐 결과가 측정 입력이다.
- **S3a·S3b·S4 중 어느 것이 가장 느린지는 가정하지 않는다** — L1의 CPU 중앙값이 가장 큰 유효 모양을 "최악 후보"로 부르고 L2에 쓴다(v2 R1-4). 허용 태그의 조합은 무한하므로 "최악"이 아니라 **"이번에 시험한 스트레스 사례 중 최대"**로 기록한다.
- 한 페이지 = 같은 모양 50건(게시판 페이지 크기 최대). L2 세트는 S1과 최악 후보, 각각 게시판 하나(페이지 크기 50, 공개, 글 50건).

### D4. 판정 기준 — 측정 전에 고정한다

**100ms는 응답 목표(SLO)가 아니라 참고 예산이다** — 근거는 로드맵의 선례("공개 공지 검색 응답이 100ms를 넘으면 FULLTEXT 재평가")다. 그 일부를 요약이 차지하는 정도로 판정한다(v2 R1-5).

- **기준 A — 요약 컬럼(`post.excerpt`) 도입 제안**: L1 최악 후보 50건 계산의 **스레드 CPU 시간 중앙값 > 20ms**(참고 예산의 20%)이면 "문제 수준"(v3 R2-1 — L1이 컬럼이 없앨 작업 자체를 잰다).
  - **L1 실행 간 일관성(v4 R3-1)**: 새 JVM 세 실행의 CPU 중앙값이 20ms 초과·이하로 갈리거나, 어느 실행이든 최대 워밍업까지 안정되지 않으면 A는 **판정 유보**다. 세 실행을 한 차례 더 돌리고, 그래도 같으면 판정하지 않고 불확실성(실행별 값)을 기록해 사용자에게 묻는다.
  - **정합성 검사(제안 전 필수)**: L2 최악 후보 세트의 (웹진 − 목록) 중앙값 차이가 L1 경과 시간 중앙값의 **50%~200%** 밖이면, 템플릿 분기나 측정 문제로 L1과 실제 응답이 어긋난 것이다. 이때는 제안하지 않고 원인부터 확인한다(어긋난 방향·크기를 기록하고 사용자에게 묻는다).
  - 충족하고 정합성도 맞으면 이번 작업에서 구현하지 않고 **스키마 변경 제안**(새 마이그레이션 컬럼 + 백필 + `PostService` 저장 경로, 롤백 영향)을 사용자에게 별도로 고지한다.
- **기준 C — 원인 조사 후보(웹진 응답 자체)**: 기준 A가 미충족이어도 L2 최악 후보 세트의 **웹진 응답 중앙값 > 100ms** 또는 **(웹진 − 목록) p95 차이 > 20ms**이면 "웹진 목록 응답 조사 후보"로 기록한다(구간 계측부터 — 요약 컬럼이 답인지 아직 모른다).
- **기준 B — 본문 조회 비용 조사 후보**: L2 최악 후보 세트의 **목록형** 응답 중앙값 > 100ms, 또는 (최악 후보 − S1) 목록형 중앙값 차이 > 50ms이면 "목록 쿼리가 본문 전체를 읽는 비용"을 **조사 후보**로 기록한다. 원인 단정이 아니며(v2 R1-3), 후속에서 구간 계측 또는 본문을 빼는 대조 조회로 확인한다(예: 목록 전용 프로젝션 + 웹진형만 `SUBSTRING(content, 1, 20000)` — 스키마 변경 없이 ⓐ의 입력과 ⓑ를 함께 줄이는 안이 후보).
- A·B·C 모두 미충족: ④는 **보류 유지**, 측정값·날짜·환경을 로드맵·`CLAUDE.md`에 남겨 다음 재평가의 기준선으로 쓴다.
- **블록 일관성**: ABBA 블록별 중앙값이 판정 방향(기준 초과/미만)에서 서로 다르면 측정 전체를 한 번 더 반복한다. 그래도 엇갈리면 사용자에게 묻는다. 결과가 기준값 ±20% 안이면 같은 규칙을 적용한다.

### D5. 측정 환경 기록

결과에는 다음을 함께 적는다: 날짜, OS·CPU(코어 수), JDK(`java -version`), JVM 인자, 실행 방식(`bootJar`)·프로파일·포트·설정 덮어쓰기, dev DB 컨테이너 이미지, 측정 시 다른 부하 유무. 로컬 Windows 개발 기계의 값이므로 **절대값이 아니라 기준과의 거리**로 판단하고, 운영 기계에서는 다를 수 있음을 결과에 명시한다.

### D6. 데이터 원복

측정용 게시판은 실행 식별자를 붙인 고유 이름(`excerpt-measure-{실행ID}-S1` 등)으로 만든다. SQL로 넣으므로 감사 로그·이미지 참조 행은 생기지 않고, 조회수 카운터는 측정용 글에만 쌓인다.

- **정리 SQL은 삽입 전에 준비하고 재실행 가능하게 쓴다(v3 R2-3)**: 이름 접두어 `excerpt-measure-`로 게시판 id를 찾아 그 게시판의 글 → 게시판 순으로 지운다(FK RESTRICT). 일부만 삽입된 상태·이미 지운 상태에서 다시 돌려도 오류 없이 0행이 된다. 측정 시작 전에도 한 번 돌려 이전 실패의 잔재를 지운다.
- **측정 스크립트는 `trap … EXIT`로 정리를 보장한다**: 자기가 띄운 앱의 PID만 종료하고(8081을 점유한 다른 프로세스는 건드리지 않는다 — 기동 전에 8081이 비었는지 확인하고, 차 있으면 시작하지 않는다), 정리 SQL을 실행한다. 시드 삽입 일부 실패·앱 기동 실패·비정상 응답 반복·스크립트 예외 모두 이 경로를 탄다.
- **정리 결과는 측정 결과와 따로 기록한다**: 정리 후 측정용 이름의 게시판·글 0행, 8081 비어 있음. 정리가 실패하면 측정이 성공했어도 작업을 끝난 것으로 보지 않고 수동으로 정리한 뒤 기록한다.

## 범위·변경 사항

- **코드·스키마·인가 정책·의존성 변경 없음.** 측정 코드는 커밋하지 않는다.
- 산출물: 이 문서의 "측정 결과"·"판정", `com.cms.publicweb.board`의 `CLAUDE.md` 요약 항목(측정값과 판정 한 줄), 계획 인덱스(`README.md`) 행. 로드맵 반영은 `updateRoadmap` 몫이다.
- 브랜치: `docs/webzine-excerpt-measurement`(현재 브랜치 `docs/roadmap-home-cover-og-image`는 #199로 머지됨 — `origin/master`에서 새로 딴다).

## 작업 단계

1. 브랜치 생성(`origin/master` 기준).
2. L1: 임시 클래스 작성 → S1~S4 생성·`ContentBodyPolicy.sanitize` 검증·제원 출력 → 워밍 확인 + 측정 × 새 JVM 3회(순서 회전) → 결과 기록 → 최악 후보(공동 후보) 선정 → 임시 클래스 삭제.
3. L2: 생성기로 S1·최악 후보 본문 SQL과 **정리 SQL** 작성 → 정리 SQL 선실행 → 8081 비었는지 확인 → dev DB 삽입 → `bootJar` 기동(8081) → 사전 응답 검사 → ABBA 블록 측정(세트별) → 사후 응답 검사 → (`trap`) 앱 종료·정리·확인.
4. 판정(D4) → 결과 기록 → 기준 A 충족 시 스키마 변경 제안을 사용자에게 별도 고지.
5. 문서 갱신(`CLAUDE.md`·인덱스).

## 리스크

- **측정 잡음**: 로컬 기계의 다른 프로세스·GC가 값을 흔든다 → 중앙값, 워밍업, ABBA 교차, 블록 일관성 규칙(D4).
- **dev DB 오염**: 원복 누락 → 고유 이름 + 원복 후 0행 확인(D6).
- **레이트리밋을 끈 앱**: 로컬 8081 측정 실행에만 명령행 인자로 쓰고 측정 직후 종료한다. 설정 파일은 바꾸지 않는다.
- **입력 대표성**: 시험한 모양이 실제 최악을 대표한다는 보장은 없다(D3) — "시험한 스트레스 사례 중 최대"로만 기록한다.
- **결론 오용**: 로컬·단일 요청 값으로 운영 성능·동시 부하를 단정하지 않는다(D1·D5).

## 측정 결과·판정 (2026-11-03)

### Context

로드맵 ④(웹진 요약을 저장 시 계산하는 컬럼으로 이동)는 "측정이 먼저"라는 착수 조건으로 보류돼 있었다. 계획 v4대로 L1(`PostExcerpt.of` 단독)·L2(실제 앱 종단)를 재고, 측정 전에 고정한 기준 D4로 판정했다. **코드·스키마·인가 정책·의존성 변경은 없다.**

### 핵심 확정 사항(판정)

| 기준 | 조건 | 측정값 | 결과 |
|---|---|---|---|
| A 요약 컬럼 제안 | L1 최악 후보 CPU > 20ms, 세 실행 일관, L2 차이가 L1의 50~200% | S3a CPU 58.9 / 59.4 / 57.8ms(복원 확인 실행 62.5ms), L2 차이 59.3 / 63.6ms(1차 / 2차) ÷ L1 경과 약 59ms ≈ **100% / 108%** | **충족** |
| C 웹진 응답 조사 후보 | 웹진 중앙값 > 100ms 또는 p95 차이 > 20ms | S3a 웹진 177.5 / 183.6ms, p95 차이 48.7 / 65.1ms | **충족**(웹진 − 목록 차이는 L1과 같은 크기라 대부분 A로 설명됨) |
| B 본문 조회 조사 후보 | 목록형 중앙값 > 100ms 또는 (최악 − S1) 목록형 > 50ms | S3a 목록형 118.2 / 120.0ms, S3a − S1 = 74.3 / 73.3ms | **충족**(두 실행 모두. 118.2ms가 기준 ±20% 안이라 D4대로 전체를 재측정했다. 원인 단정 아님 — 본문을 읽지 않는 대조군은 재지 않았다. 목록 쿼리는 모든 유형이 공유하지만 잰 유형은 LIST·WEBZINE이고 갤러리형은 재지 않았다) |

**단서 — 기준을 넘은 것은 스트레스 사례뿐이다.** S3a는 200KB 본문 50건이고 각 본문의 앞 20,000 유닛이 한 글자 문단 2,500개다. 현실적인 S1(4.4KB)은 웹진 − 목록 차이가 **−0.5 / +0.1ms**(차이 없음)이고, 글자 상한을 채운 평문 S2(29.6KB)도 L1 약 9ms로 기준 아래다. 기준 A는 계획대로 "시험한 스트레스 사례 중 최대"로 판정했으므로 충족이지만, **실제 운영 글에서 이 비용이 나타나는지는 알 수 없다**(운영 데이터 분포가 없다). 그래서 스키마 변경을 바로 구현하지 않고 아래 "후속"의 선택지를 사용자에게 고지한다(계획 D4).

### 구현 파일

없음(측정 작업). 측정 코드는 커밋하지 않았고 전문을 이 문서 "부록"에 실었다. 산출물: 이 문서, `src/main/java/com/cms/publicweb/board/CLAUDE.md` 요약 항목, 계획 인덱스.

### 검증 결과

**환경(D5)**: Windows 10 Pro 19045, Intel Core i5-10400F(6코어 12스레드), Amazon Corretto 21.0.9(Gradle toolchain), dev DB `mariadb:10.11`(컨테이너 `cms-db-dev`, Flyway V13 적용 상태 — L1은 DB를 쓰지 않고, L2 1차는 digest `7db29378…`, 2차는 #197 갱신 뒤의 `d769e379…`), 일상 개발 상태(IDE·Docker Desktop 실행 중, 별도 부하 없음). 로컬 단일 요청·워밍 상태 값이며 운영 기계·동시 부하는 판단하지 않는다.

**L1 — `PostExcerpt.of` 50건(한 페이지)**: 새 JVM 3회, 모양 순서 회전(0: S1→S4, 1: S4→S3b, 2: S3b→S3a), 모든 모양이 6배치에서 안정(마지막 3배치 경과 중앙값 10% 안). 입력은 전부 `ContentBodyPolicy.sanitize` 통과·정리 멱등.

| 모양 | 정리 후 제원(UTF-8 바이트·UTF-16 유닛·보이는 글자·요약) | 경과 중앙값 ms(실행 0/1/2) | CPU 평균 ms(30페이지 합계 ÷ 30) | 할당/페이지 |
|---|---|---|---|---|
| S1 현실 | 4,450 · 1,570 · 1,510 · 있음 | 1.51 / 1.47 / 1.35 | 1.56 / 1.56 / 1.04 | 2.1MB |
| S2 글자 상한 평문 | 29,601 · 10,593 · 9,999 · 있음(전부 파싱) | 8.76 / 9.45 / 8.63 | 8.85 / 9.90 / 8.85 | 12.9MB |
| **S3a 문단 밀집 + 이미지 꼬리** | 199,636 · 194,636 · 9,851 · 있음 | **59.34 / 59.07 / 58.49** | **58.85 / 59.38 / 57.81** | **46.5MB** |
| S3b 인라인 중첩 + 이미지 꼬리 | 199,626 · 198,626 · 5,456 · 있음 | 35.61 / 35.40 / 36.02 | 35.42 / 34.90 / 36.98 | 27.9MB |
| S4 이미지 머리 | 199,919 · 199,727 · 5,646 · **null** | 18.13 / 18.19 / 17.96 | 18.23 / 17.71 / 18.23 | 14.0MB |

- 최악 후보는 **S3a 단독**(2위 S3b와 약 23ms 차이 ≫ 실행 간 변동 약 1ms → 공동 후보 없음).
- 복원 확인 실행(아래 "이슈" ②)에서도 S3a 경과 62.34ms·CPU 62.50ms, 다른 모양도 같은 범위였다.

**L2 — 실제 앱 `GET /main/boards/board-{id}`(50건 한 페이지)**: `bootJar` + `-Xms512m -Xmx512m --server.port=8081 --spring.jpa.show-sql=false --cms.rate-limit.enabled=false`, `SPRING_PROFILES_ACTIVE=dev`. 세트별 ABBA ×2(블록 = 워밍업 20 + 측정 50), 유형별 200표본. **실행마다 2세트 × 8블록 = 16블록, 두 실행의 32블록 모두 유효**(표본 전부 200, 블록마다 사전·사후 검사: 목록형 `board-list-item` 50·웹진 0, 웹진형 `board-webzine-item` 50·요약 50·목록 0).

| 실행 | 세트 | LIST 중앙값 / p95 / 최대 | WEBZINE 중앙값 / p95 / 최대 | 웹진 − 목록(중앙값) | 블록 중앙값 ms |
|---|---|---|---|---|---|
| 1차(11-03) | S1 | 43.9 / 64.4 / 83.9 | 43.4 / 66.2 / 89.8 | −0.5 | L 47.5·39.9·39.4·38.1 / W 46.2·43.3·41.4·41.3 |
| 1차(11-03) | S3a | 118.2 / 156.7 / 388.8 | 177.5 / 205.4 / 797.7 | **+59.3** | L 116.6·117.9·128.0·115.2 / W 180.1·177.4·175.9·175.8 |
| 2차(11-04) | S1 | 46.7 / 66.6 / 84.9 | 46.8 / 66.6 / 79.3 | +0.1 | L 50.7·43.5·41.5·41.1 / W 48.3·48.8·44.0·43.5 |
| 2차(11-04) | S3a | 120.0 / 142.2 / 160.1 | 183.6 / 207.3 / 219.3 | **+63.6** | L 121.5·118.7·122.9·118.1 / W 187.1·178.9·185.2·182.7 |

- **재측정 규칙(D4)**: 1차의 S3a 목록형 118.2ms가 기준 B(100ms)의 ±20% 안이라 L2 전체를 한 번 더 쟀다. 2차에서도 판정 방향이 같다(120.0ms는 여전히 ±20% 경계지만 두 실행 모두 기준 초과이고 B의 다른 조건 73~74ms도 충족). 블록별 중앙값도 판정 방향에서 엇갈리지 않았다.
- 1차 S3a의 최대값(388.8·797.7ms)은 2차(160.1·219.3ms)에서 재현되지 않은 단발 이상치다(원인은 확인하지 않았다).
- 2차는 #197로 DB 이미지 digest가 바뀐 뒤다(자동 재생성, 데이터 볼륨 유지). 앱 jar는 1차와 같다.
- **원복(D6)**: 두 실행 모두 `trap EXIT` 정리 — 측정 게시판 0행·측정 글 0행, 8081 비어 있음. SQL 삽입이라 감사·이미지 참조 행은 생기지 않았다. 측정 게시판 id는 23·24, 25·26이었다(auto_increment는 되돌리지 않는다).

### 이슈

1. **Windows 스레드 CPU 시간 해상도(계획과 달라진 점)**: 1회차 L1에서 CPU 값이 전부 15.625ms(스케줄러 틱)의 배수였다(0.00·15.63·31.25·62.50). 페이지 하나(1~60ms)를 재기에 해상도가 모자라고, CPU 기준 워밍 안정 판정이 양자화된 값끼리 비교해 자동 통과했다. 그래서 ⓐ 워밍 안정 판정은 **경과 시간 배치 중앙값**으로, ⓑ CPU는 **측정 30페이지 합계를 한 번에 재 평균**(오차 ±15.6ms ÷ 30 ≈ ±0.5ms/페이지)으로 바꿨다. 기준 A는 CPU 평균(중앙값이 아닌 평균)과 경과 중앙값이 같은 방향일 때만 판정했다 — S3a는 둘이 거의 같다(58~59ms). 위 표는 수정 후 실행 값이다. 리눅스에서는 이 해상도 문제가 없을 수 있다.
2. **측정 클래스 복원**: 부록을 만드는 스크립트가 `python3` 없음으로 실패했는데 실패를 `|| echo`로 삼켜 다음 줄의 `rm`이 실행돼, 커밋하지 않는 임시 측정 클래스가 부록에 옮겨지기 전에 지워졌다(저장소 영향 없음). 작성·수정 이력으로 최종본을 복원했고, **복원본으로 다시 실행해 모양 제원 다섯 줄이 원래 실행과 글자 단위로 같음**을 확인한 뒤 부록에 실었다(측정값도 실행 간 변동 범위 안).
3. 처음 작성한 측정 클래스는 바이트 상한을 찾는 루프에서 `ContentBodyPolicy`가 던지는 400 예외를 크기 비교로 처리하려다 실패했다 — 예외를 "상한 초과"로 받도록 고친 뒤 측정했다(측정 코드 결함, 앱 코드와 무관).
4. **재측정 누락(코드 리뷰 R2)**: 처음 결과를 쓸 때 블록 일관성만 보고 "재측정이 발동하지 않았다"고 적었지만, D4의 "기준값 ±20% 안이면 반복" 규칙에 118.2ms가 해당했다. 리뷰 지적으로 L2를 다시 쟀고 판정은 바뀌지 않았다. 같은 지적으로 "32개 블록"(실제 16블록, 32는 응답 검사 횟수)도 바로잡았다.

### 후속 — 사용자에게 고지하는 선택지(이번 작업에서는 구현하지 않음)

관측값과 가설을 구분한다.

- ⓐ **직접 측정**: 웹진형만 내는 요약 파싱 — 스트레스 사례 약 59ms/페이지, 할당 46MB(L1, L2 차이와 일치).
- ⓑ **관측값**: 본문 크기가 다른 두 세트의 목록형 종단 응답 차이 +74.3 / +73.3ms(S3a − S1, 1차 / 2차). **원인 가설**: 목록 쿼리(모든 유형 공통)가 본문 전체를 읽는 비용. 이 차이에는 DB 페이지 접근·JDBC 디코딩·엔티티 적재·GC가 섞여 있고, 본문을 빼거나 앞부분만 읽는 대조군은 재지 않았다. 아래 표의 "줄이는 것"에서 ⓑ는 **예상이고 절감량은 미측정**이다.

선택지(트레이드오프):

| 안 | 내용 | 줄이는 것 | 스키마 | 트레이드오프 |
|---|---|---|---|---|
| ① 요약 컬럼(로드맵 ④ 원안) | `post.excerpt` 컬럼 + 기존 글 백필 마이그레이션 + `PostService` 저장 경로에서 계산 | ⓐ 전부 | **변경**(새 마이그레이션·백필, 롤백 시 이전 앱이 요약을 갱신하지 않아 낡은 요약) | ⓑ는 그대로 — 목록 쿼리가 계속 본문을 읽는다. 요약 규칙을 바꾸면 백필 재실행 필요 |
| ② 목록 전용 조회 + 본문 앞부분만 | 목록 쿼리를 프로젝션으로 바꿔 `content`를 읽지 않고, 웹진형만 `SUBSTRING(content, 1, 20000)`을 읽는다 | ⓑ의 본문 전송·엔티티 적재 감소 **예상**(모든 유형, 절감량 미측정 — `SUBSTRING`이 DB 내부의 본문 접근 비용까지 없앤다는 증거도 없다) | 없음 | 파싱 비용 ⓐ는 그대로(스트레스 사례 59ms). `Page<Post>`를 쓰는 공개 목록 경로·DTO 변환을 바꾼다. MariaDB `SUBSTRING`은 문자 단위라 UTF-16 유닛과 다르다(서로게이트 처리 재검토) |
| ③ ① + ② | 요약 컬럼 + 목록 프로젝션(본문 미조회) | ⓐ 전부 + ⓑ 감소 예상(절감량 미측정) | 변경 | 가장 큰 변경(두 PR로 나누는 것이 자연스럽다) |
| ④ 파싱 상한 축소 | `PARSE_LIMIT` 20,000 → 예: 4,000 유닛 | ⓐ를 대략 비례해 축소(측정 필요) | 없음 | 앞부분이 이미지·빈 문단인 글의 요약이 더 자주 비거나 짧아진다(지금도 수용한 한계가 넓어짐). ⓑ는 그대로 |
| ⑤ 보류 유지 | 현실적인 글에서는 차이가 없으므로 운영 데이터가 생길 때까지 둔다 | — | 없음 | 스트레스 사례(악의적·비정상 글) 한 게시판이 웹진형이면 목록 1회에 약 180ms·할당 수십 MB. 공개 게시판 레이트리밋(120/60초)이 반복 요청 빈도를 제한한다 |

- **사용자 결정(2026-11-04): ② 목록 전용 조회 + 본문 앞부분만.** 스키마 변경 없이 모든 유형의 본문 전송·엔티티 적재를 줄일 것으로 예상하는 안이다. 요약 컬럼(①·③)은 채택하지 않았고, 요약 계산 비용(스트레스 사례 59ms)은 남는 것으로 받아들인다. 구현은 이 측정 작업과 별도 계획·별도 PR이다(미착수). **그 계획에는 같은 조건의 구현 전후 대조 측정을 넣어 실제 절감량을 확인한다** — +73~74ms 전부가 줄어든다는 보장은 없다.
- 이 측정으로 판단할 수 없는 것: 실제 운영 글의 본문 분포(S1과 S3a 사이 어디인지), 동시 요청에서의 GC·힙 압박(할당 46MB/페이지 × 동시 요청), 운영 기계 값.

## 개정 이력

- v1 (2026-11-03): 최초 작성.
- v2 변경(리뷰 1라운드, 7건 전부 수용): R1-1 S2·S3이 저장 불가였다(보이는 글자에 블록 종료·`br` 포함, S2 10,100자, S3 패턴은 74KB에서 글자 상한) — 모든 모양을 `ContentBodyPolicy.sanitize`로 검증하고 제원을 기록, S2를 99×100자로 바로잡고 "파싱 상한에서 잘린다"는 설명을 정정(약 10.4K 유닛, 전부 파싱), 크기 최악은 "밀집 머리 + 이미지 꼬리"로 재설계(D3). R1-2 L1 판정 지표를 스레드 CPU 시간으로 명시하고 경과·할당을 함께 기록, 지원 여부 확인, 결과 소비(D2). R1-3 기준 B를 "본문 조회 비용 조사 후보"로 낮추고 원인 단정을 뺐다(D1·D4). R1-4 최악 모양을 가정하지 않고 L1에서 가장 느린 유효 모양을 L2에 쓴다, 인라인 중첩 모양 S3b 추가(D3). R1-5 100ms를 참고 예산으로 명시, 웹진 응답 자체·p95 차이에 대한 기준 C 추가, 판단 범위를 단일 요청·워밍 상태로 한정(D1·D4). R1-6 표본별 HTTP 200·블록 무효화·전후 응답 검사(D2). R1-7 `show-sql` 끄기·힙 고정·ABBA 교차 블록 — 그리고 리뷰 검증 중 발견한 **devtools 템플릿 캐시 해제** 때문에 `bootRun` 대신 `bootJar`로 실행(D2).

- v3 변경(리뷰 2라운드): R2-1 **부분 수용** — 유형 간 차이에 템플릿 분기가 섞여 파싱 절감량이 아니라는 지적은 맞다. 기준 A를 L1 CPU 중앙값 단독으로 판정하고(컬럼이 없앨 작업이 정확히 `PostExcerpt.of`라 L1이 그 대조다), L2 차이는 게이트가 아니라 정합성 검사(L1 경과 시간의 50%~200% 밖이면 제안 보류·원인 확인)로 내렸다(D1·D4). 기각: "A를 원인 확인 착수 조건으로 낮추고 사전 계산 대조를 따로 만든다" — 사전 계산 대조는 코드 변경이 필요하고, 그 대조가 재는 차이는 L1이 이미 직접 잰다. R2-2 수용 — L1 워밍 안정 확인(마지막 3배치 중앙값 10% 안), 새 JVM 3회·모양 순서 회전, 실행 간 변동보다 작은 차이는 공동 후보(D2). R2-3 수용 — 실행 식별자 접두어, 삽입 전 준비·선실행하는 재실행 가능 정리 SQL, `trap EXIT`로 자기 PID 종료·정리, 정리 결과를 측정과 따로 기록(D6·작업 단계).

- v4 변경(리뷰 3라운드): R3-1 수용 — L1 세 실행이 20ms 기준의 양쪽으로 갈리거나 워밍 미안정이면 A 판정 유보, 한 차례 재측정 후에도 같으면 불확실성 기록·사용자 질문(D4). L2 블록 일관성 규칙은 L1에 걸리지 않아 생긴 구멍이었다.

## 부록 — 재현용 코드

측정에 실제로 쓴 코드 전문이다(커밋하지 않은 임시 파일). 다시 잴 때는 L1 클래스를 `src/test/java/com/cms/publicweb/board/service/`에 두고, 환경변수 `MEASURE_DIR`(결과 디렉터리)와 그 안의 `rotate.txt`(0·1·2)·`run-id.txt`를 준비한 뒤 `./gradlew --no-daemon -q cleanTest test --tests 'com.cms.publicweb.board.service.ExcerptMeasureTest.measure'`를 실행마다 새 JVM으로 돌린다(`--no-daemon`은 환경변수가 시험 JVM까지 확실히 전달되게 하려는 것). 시드는 같은 클래스의 `dumpSeed`로 만든다. L2는 `./gradlew bootJar` 후 `bash l2-measure.sh <프로젝트 루트>`(스크립트·`l2-seed.sql`·`run-id.txt`가 같은 디렉터리). JDK 경로·포트·DB 계정은 이 기계의 값이다.

### L1 측정 클래스 `ExcerptMeasureTest.java`

```java
package com.cms.publicweb.board.service;

import com.cms.common.html.ContentBodyPolicy;
import com.cms.common.html.SanitizedHtml;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 임시 측정 클래스(커밋하지 않는다) — PLAN-webzine-excerpt-measurement.md L1.
 * 모양별 본문을 ContentBodyPolicy.sanitize로 통과시킨 뒤, 한 페이지(50건) PostExcerpt.of 계산의 스레드 CPU·경과·할당을 잰다.
 * 입력: 환경변수 MEASURE_DIR(결과 디렉터리), 그 안의 rotate.txt(모양 순서 회전 칸 수). 출력: MEASURE_DIR/l1-run-{rotate}.txt
 */
class ExcerptMeasureTest {

    private static final int PAGE = 50;
    private static final int BATCH_PAGES = 10;
    private static final int MIN_WARM_BATCHES = 6;
    private static final int MAX_WARM_BATCHES = 30;
    private static final int MEASURE_PAGES = 30;
    private static final String IMG = "<p><img src=\"/content-images/1\"></p>";
    private static final String HANGUL = "웹진형목록의요약은조회때저장된본문앞부분을읽어만든다 공개목록한페이지는최대오십건이다 ";

    private static long sink;

    @Test
    void measure() throws Exception {
        Path dir = Path.of(System.getenv("MEASURE_DIR"));
        int rotate = Integer.parseInt(Files.readString(dir.resolve("rotate.txt")).strip());
        StringBuilder out = new StringBuilder();

        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (mx.isCurrentThreadCpuTimeSupported() && !mx.isThreadCpuTimeEnabled()) {
            mx.setThreadCpuTimeEnabled(true);
        }
        if (mx.isThreadAllocatedMemorySupported() && !mx.isThreadAllocatedMemoryEnabled()) {
            mx.setThreadAllocatedMemoryEnabled(true);
        }
        out.append("cpuTime=").append(mx.isCurrentThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled())
                .append(" alloc=").append(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled())
                .append(" java=").append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version"))
                .append(" rotate=").append(rotate).append('\n');

        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("S1", sanitize(s1()));
        shapes.put("S2", sanitize(s2()));
        shapes.put("S3a", sanitize(withImageTail(s3aHead())));
        shapes.put("S3b", sanitize(withImageTail(s3bHead())));
        shapes.put("S4", sanitize(s4()));

        for (Map.Entry<String, String> e : shapes.entrySet()) {
            String html = e.getValue();
            SanitizedHtml again = ContentBodyPolicy.sanitize(html, "HTML");
            String excerpt = PostExcerpt.of(html);
            out.append(String.format("SPEC %s bytes=%d units=%d textLength=%d idempotent=%s excerptNull=%s excerptHead=%s%n",
                    e.getKey(), html.getBytes(StandardCharsets.UTF_8).length, html.length(), again.textLength(),
                    again.html().equals(html), excerpt == null,
                    excerpt == null ? "-" : excerpt.substring(0, Math.min(30, excerpt.length())).replace(' ', '_')));
        }

        List<String> order = new ArrayList<>(shapes.keySet());
        java.util.Collections.rotate(order, rotate);
        out.append("ORDER ").append(order).append('\n');

        for (String name : order) {
            String html = shapes.get(name);
            List<Long> batchMedians = new ArrayList<>();
            boolean stable = false;
            while (batchMedians.size() < MAX_WARM_BATCHES) {
                // Windows 스레드 CPU 시간은 15.625ms 틱 단위라 배치 안정 판정은 경과 시간 중앙값으로 한다
                long[] wallBatch = new long[BATCH_PAGES];
                for (int i = 0; i < BATCH_PAGES; i++) {
                    wallBatch[i] = page(mx, html)[1];
                }
                batchMedians.add(median(wallBatch));
                int n = batchMedians.size();
                if (n >= MIN_WARM_BATCHES) {
                    List<Long> last = batchMedians.subList(n - 3, n);
                    long max = last.stream().mapToLong(Long::longValue).max().orElseThrow();
                    long min = last.stream().mapToLong(Long::longValue).min().orElseThrow();
                    if (max <= min * 1.10) {
                        stable = true;
                        break;
                    }
                }
            }
            long[] cpu = new long[MEASURE_PAGES];
            long[] wall = new long[MEASURE_PAGES];
            long[] alloc = new long[MEASURE_PAGES];
            long cpuTotal0 = mx.getCurrentThreadCpuTime();
            for (int i = 0; i < MEASURE_PAGES; i++) {
                long[] r = page(mx, html);
                cpu[i] = r[0];
                wall[i] = r[1];
                alloc[i] = r[2];
            }
            long cpuTotal = mx.getCurrentThreadCpuTime() - cpuTotal0;
            out.append(String.format("RESULT %s warmBatches=%d stable=%s cpuMsMeanOf30=%.2f (total=%.1fms, tick±15.6) wallMs[med=%.2f p95=%.2f max=%.2f] allocMB[med=%.2f]%n",
                    name, batchMedians.size(), stable,
                    ms(cpuTotal) / MEASURE_PAGES, ms(cpuTotal),
                    ms(median(wall)), ms(p95(wall)), ms(max(wall)),
                    median(alloc) / 1_048_576.0));
        }
        out.append("sink=").append(sink).append('\n');
        Files.writeString(dir.resolve("l1-run-" + rotate + ".txt"), out.toString());
    }

    /** L2 시드 SQL — L1과 같은 생성기·같은 정리 결과. MEASURE_DIR/run-id.txt의 실행 ID를 게시판 이름에 붙인다. */
    @Test
    void dumpSeed() throws Exception {
        Path dir = Path.of(System.getenv("MEASURE_DIR"));
        String runId = Files.readString(dir.resolve("run-id.txt")).strip();
        Map<String, String> sets = new LinkedHashMap<>();
        sets.put("S1", sanitize(s1()));
        sets.put("S3a", sanitize(withImageTail(s3aHead())));
        StringBuilder sql = new StringBuilder("SET NAMES utf8mb4;\n");
        for (Map.Entry<String, String> e : sets.entrySet()) {
            String board = "excerpt-measure-" + runId + "-" + e.getKey();
            sql.append("INSERT INTO board (name, public_yn, attachment_yn, deleted, list_page_size, board_type, create_date, update_date) VALUES ('")
                    .append(board).append("', 1, 1, 0, 50, 'LIST', NOW(6), NOW(6));\n");
            sql.append("SET @b = LAST_INSERT_ID();\n");
            String content = e.getValue().replace("\\", "\\\\").replace("'", "''");
            for (int i = 1; i <= PAGE; i++) {
                sql.append("INSERT INTO post (board_id, title, content, use_yn, deleted, author_id, create_date, update_date) VALUES (@b, '")
                        .append(e.getKey()).append(" 측정 글 ").append(i).append("', '").append(content)
                        .append("', 1, 0, 'measure', DATE_ADD(NOW(6), INTERVAL ").append(i).append(" SECOND), NOW(6));\n");
            }
        }
        Files.writeString(dir.resolve("l2-seed.sql"), sql.toString());
    }

    /** 한 페이지(50건) 계산 — {cpuNs, wallNs, allocBytes}. 결과는 sink에 누적해 호출이 제거되지 않게 한다. */
    private static long[] page(com.sun.management.ThreadMXBean mx, String html) {
        long tid = Thread.currentThread().threadId();
        long a0 = mx.getThreadAllocatedBytes(tid);
        long c0 = mx.getCurrentThreadCpuTime();
        long w0 = System.nanoTime();
        for (int i = 0; i < PAGE; i++) {
            String r = PostExcerpt.of(html);
            sink += r == null ? 1 : r.length() + r.hashCode();
        }
        long w1 = System.nanoTime();
        long c1 = mx.getCurrentThreadCpuTime();
        long a1 = mx.getThreadAllocatedBytes(tid);
        return new long[]{c1 - c0, w1 - w0, a1 - a0};
    }

    private static String sanitize(String raw) {
        return ContentBodyPolicy.sanitize(raw, "HTML").html();
    }

    private static String text(int codePoints) {
        StringBuilder sb = new StringBuilder();
        while (sb.codePointCount(0, sb.length()) < codePoints) {
            sb.append(HANGUL);
        }
        String s = sb.toString();
        return s.substring(0, s.offsetByCodePoints(0, codePoints)).strip();
    }

    private static String s1() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("<p>").append(text(150)).append("</p>");
        }
        return sb.toString();
    }

    private static String s2() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 99; i++) {
            sb.append("<p>").append(text(100)).append("</p>");
        }
        return sb.toString();
    }

    private static String s3aHead() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < PostExcerpt.PARSE_LIMIT) {
            sb.append("<p>가</p>");
        }
        return sb.toString();
    }

    private static String s3bHead() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < PostExcerpt.PARSE_LIMIT) {
            sb.append("<p>");
            for (int i = 0; i < 50; i++) {
                sb.append("<strong><em><u><s>가</s></u></em></strong>");
            }
            sb.append("</p>");
        }
        return sb.toString();
    }

    /** 머리 뒤에 이미지 문단을 붙여 정리 결과가 200,000바이트를 넘지 않는 최대 개수로 맞춘다. */
    private static String withImageTail(String head) {
        int count = (ContentBodyPolicy.MAX_CONTENT_BYTES - head.getBytes(StandardCharsets.UTF_8).length) / IMG.length() + 50;
        while (true) {
            String raw = head + IMG.repeat(Math.max(count, 0));
            if (fits(raw)) {
                return raw;
            }
            count -= 20;
        }
    }

    /** 저장 검증 통과 여부 — 바이트·글자 상한을 넘으면 정책이 400(InvalidRequestException)을 던진다. */
    private static boolean fits(String raw) {
        try {
            sanitize(raw);
            return true;
        } catch (com.cms.common.exception.InvalidRequestException e) {
            return false;
        }
    }

    private static String s4() {
        String tail = "<p>" + text(100) + "</p>";
        int count = ContentBodyPolicy.MAX_CONTENT_BYTES / IMG.length() + 50;
        while (true) {
            String raw = IMG.repeat(count) + tail;
            if (fits(raw)) {
                return raw;
            }
            count -= 20;
        }
    }

    private static long median(long[] v) {
        long[] s = v.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    private static long p95(long[] v) {
        long[] s = v.clone();
        Arrays.sort(s);
        return s[(int) Math.ceil(s.length * 0.95) - 1];
    }

    private static long max(long[] v) {
        return Arrays.stream(v).max().orElseThrow();
    }

    private static double ms(long ns) {
        return ns / 1_000_000.0;
    }
}
```

### L2 측정 스크립트 `l2-measure.sh`

```bash
#!/usr/bin/env bash
# L2 종단 측정 — PLAN-webzine-excerpt-measurement.md D2·D6. 사용: bash l2-measure.sh <프로젝트 루트>
set -u
ROOT="$1"
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA="C:/Users/user/.jdks/corretto-21.0.9/bin/java.exe"
JAR="$ROOT/build/libs/CMS-0.0.1-SNAPSHOT.jar"
PORT=8081
DB=(docker exec -i cms-db-dev mariadb -uadmin -p1234 cms)
CLEAN_SQL="DELETE FROM post WHERE board_id IN (SELECT id FROM board WHERE name LIKE 'excerpt-measure-%'); DELETE FROM board WHERE name LIKE 'excerpt-measure-%';"
APP_PID=""
LOG="$DIR/l2-log.txt"
: > "$LOG"

port_busy() { netstat -ano | grep -qE "[:.]$PORT +.*LISTENING"; }

cleanup() {
  local status=$?
  {
    echo "== 정리 시작 (측정 종료 코드 $status)"
    if [ -n "$APP_PID" ]; then
      local winpid
      winpid=$(cat "/proc/$APP_PID/winpid" 2>/dev/null || true)
      kill "$APP_PID" 2>/dev/null || true
      [ -n "$winpid" ] && taskkill //PID "$winpid" //T //F >/dev/null 2>&1 || true
      sleep 2
    fi
    "${DB[@]}" -e "$CLEAN_SQL" && echo "정리 SQL 성공" || echo "정리 SQL 실패"
    echo "남은 측정 게시판: $("${DB[@]}" -N -e "SELECT COUNT(*) FROM board WHERE name LIKE 'excerpt-measure-%'")"
    echo "남은 측정 글: $("${DB[@]}" -N -e "SELECT COUNT(*) FROM post p JOIN board b ON b.id=p.board_id WHERE b.name LIKE 'excerpt-measure-%'")"
    if port_busy; then echo "포트 $PORT: 아직 점유"; else echo "포트 $PORT: 비어 있음"; fi
  } >> "$LOG" 2>&1
}
trap cleanup EXIT

if port_busy; then echo "포트 $PORT 점유 중 — 시작하지 않음" >> "$LOG"; exit 2; fi

# 이전 실패의 잔재 정리 → 시드
"${DB[@]}" -e "$CLEAN_SQL" || exit 3
"${DB[@]}" < "$DIR/l2-seed.sql" || { echo "시드 실패" >> "$LOG"; exit 4; }
RUN_ID=$(cat "$DIR/run-id.txt")
B_S1=$("${DB[@]}" -N -e "SELECT id FROM board WHERE name='excerpt-measure-$RUN_ID-S1'")
B_S3=$("${DB[@]}" -N -e "SELECT id FROM board WHERE name='excerpt-measure-$RUN_ID-S3a'")
echo "boards S1=$B_S1 S3a=$B_S3 posts=$("${DB[@]}" -N -e "SELECT COUNT(*) FROM post WHERE board_id IN ($B_S1,$B_S3)")" >> "$LOG"

# 앱 기동 — bootJar(devtools 없음 = 템플릿 캐시 켜짐), SQL 출력 끔, 레이트리밋 끔(이 실행에만), 힙 고정
JVM_ARGS=(-Xms512m -Xmx512m -jar "$JAR" --server.port=$PORT --spring.jpa.show-sql=false --cms.rate-limit.enabled=false)
echo "실행: SPRING_PROFILES_ACTIVE=dev $JAVA ${JVM_ARGS[*]}" >> "$LOG"
SPRING_PROFILES_ACTIVE=dev DB_PASS=1234 MAIL_USER=dev@example.com MAIL_PASS=dummy \
  "$JAVA" "${JVM_ARGS[@]}" > "$DIR/l2-app.log" 2>&1 &
APP_PID=$!
for i in $(seq 1 120); do
  curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/actuator/health" 2>/dev/null | grep -q 200 && break
  sleep 1
done
curl -s "http://localhost:$PORT/actuator/health" | grep -q UP || { echo "앱 기동 실패" >> "$LOG"; exit 5; }
echo "앱 기동 완료(${i}초)" >> "$LOG"

set_type() { "${DB[@]}" -e "UPDATE board SET board_type='$2' WHERE id=$1"; }

# 응답 검사 — 200·일반 목록 50건·유형 마커·웹진 요약 50건
verify() {
  local board=$1 type=$2 out="$DIR/l2-verify-$1-$2.html" code
  code=$(curl -s -o "$out" -w '%{http_code}' "http://localhost:$PORT/main/boards/board-$board")
  local list web exc
  list=$(grep -o 'class="board-list-item"' "$out" | wc -l)
  web=$(grep -o 'class="board-webzine-item"' "$out" | wc -l)
  exc=$(grep -o 'class="board-webzine-excerpt"' "$out" | wc -l)
  echo "검사 board=$board type=$type code=$code list=$list webzine=$web excerpt=$exc" >> "$LOG"
  if [ "$code" != 200 ]; then return 1; fi
  if [ "$type" = LIST ] && { [ "$list" != 50 ] || [ "$web" != 0 ]; }; then return 1; fi
  if [ "$type" = WEBZINE ] && { [ "$web" != 50 ] || [ "$exc" != 50 ] || [ "$list" != 0 ]; }; then return 1; fi
}

# 블록 — 워밍업 20 + 측정 50, 200이 아닌 표본이 있으면 블록 무효(측정 중단)
block() {
  local set=$1 board=$2 type=$3 idx=$4
  set_type "$board" "$type"
  verify "$board" "$type" || { echo "사전 검사 실패 $set $type" >> "$LOG"; exit 6; }
  for i in $(seq 1 20); do curl -s -o /dev/null "http://localhost:$PORT/main/boards/board-$board"; done
  local f="$DIR/l2-samples-$set-$idx-$type.txt"
  : > "$f"
  for i in $(seq 1 50); do
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' "http://localhost:$PORT/main/boards/board-$board" >> "$f"
  done
  if grep -qv '^200 ' "$f"; then echo "블록 무효(비-200) $set $idx $type" >> "$LOG"; exit 7; fi
  verify "$board" "$type" || { echo "사후 검사 실패 $set $type" >> "$LOG"; exit 8; }
}

for SET in S1 S3a; do
  if [ "$SET" = S1 ]; then B=$B_S1; else B=$B_S3; fi
  idx=0
  for T in LIST WEBZINE WEBZINE LIST LIST WEBZINE WEBZINE LIST; do
    idx=$((idx + 1))
    block "$SET" "$B" "$T" "$idx"
  done
done
echo "측정 완료" >> "$LOG"
```
