# PLAN-dashboard-demo-cleanup — 대시보드 잔여 SB Admin 2 데모 위젯 정리

## 목표

대시보드(`templates/admin/index.html`)에서 SB Admin 2 템플릿의 **더미 데모 콘텐츠를 제거**하고, 차트 영역을 **실데이터(최근 7일 일별 방문자 수) 차트 1개**로 대체한다.

현황: 상단 통계 카드 4종(신규회원·오늘/이번달/총 방문자)은 `DashboardService` 실데이터로 이미 연결되어 있으나, 그 아래는 전부 데모가 그대로 남아 있다:
- "Earnings Overview" Area 차트 (`/js/demo/chart-area-demo.js` — 하드코딩 더미 데이터)
- "Revenue Sources" Pie 차트 (`/js/demo/chart-pie-demo.js` — 더미 데이터)
- Projects 진행바 카드 (하드코딩 20~100%)
- Illustrations / Development Approach 카드 (undraw.co 홍보 문구)
- 상단 "Generate Report" 버튼 (`href="#"` — 동작 없음)

## 설계 결정 (구현 시 그대로 따를 것)

| 항목 | 결정 |
|------|------|
| 대체 차트 | **최근 7일(오늘 포함) 일별 방문자 수** 라인(Area) 차트 1개. Pie 차트·진행바·일러스트 카드·**Color System 팔레트 카드**(239~305행 — 정찰에서 확인된 누락 데모)는 대체 없이 **삭제** (v2) |
| DTO date 타입 | **String (ISO `yyyy-MM-dd`)** 확정 — Thymeleaf inline JSON에서 `LocalDate`는 객체로 풀려 차트 라벨이 깨진다. 서비스에서 `LocalDate.toString()` 변환 (v2 — 기존 엣지 6의 "안전한 기본값"을 확정으로 승격) |
| 템플릿 null 방어 | 차트 표시 조건을 `dailyVisitors == null or #lists.isEmpty(dailyVisitors)`로 — `AdminSidebarAdviceTest` 등 다른 `@WebMvcTest`가 `/admin` 뷰를 실렌더링할 때 목 서비스의 기본 반환(null)로도 템플릿이 깨지지 않아야 한다. null과 빈 리스트는 동일하게 오류 문구 처리 (v2) |
| 데이터 전달 방식 | REST API 신설 없이 **페이지 모델로 주입** (`AdminMainController` → Thymeleaf inline JSON). 대시보드는 페이지 렌더 시점 데이터로 충분하고, API를 만들면 인가·문서화 부담만 늘어남 |
| 집계 쿼리 | `VisitLogRepository`에 파생 쿼리 추가가 불가능한 GROUP BY이므로 `@Query` JPQL 사용 (단순 GROUP BY라 QueryDSL 불필요 — CLAUDE.md의 "동적 조건·복잡한 조인"에 해당하지 않음) |
| 날짜 빈 구멍 채우기 | DB는 방문 있는 날만 반환하므로 **서비스에서 7일 전 구간을 0으로 채워** 항상 7개 요소를 보장 |
| 실패 폴백 | 기존 `DashboardService.getDashboardStats()`와 동일 철학: 집계 실패 시 예외를 삼키고 **빈 리스트** 반환, 화면은 "차트를 불러올 수 없습니다" 문구 표시 |
| 시간 기준 | 기존과 동일하게 `Clock` 빈 주입 + `LocalDate.now(clock)` (테스트 가능성). **방문 저장 경로도 동일 Clock으로 통일** (v3, R1#1): `VisitLoggingAuthenticationSuccessHandler.tryLogVisit()`이 `LocalDateTime.now()` 직접 호출로 저장 중 — JVM이 KST가 아니면(CI UTC) 자정 전후 방문이 다른 날짜로 집계된다. 기존 통계 카드에도 잠복해 있던 결함이며 차트가 같은 데이터를 소비하므로 이번 범위에서 `Clock` 주입 + `now(clock)`으로 수정 |
| 방문자 정의 | **관리자(ADMIN/MANAGER) 로그인 성공 1회 = 방문 1건** — 기존 통계 카드(오늘/이번달/총 방문자)와 동일 정의를 그대로 따른다 (v3, R1#4). `count(distinct)` 고유 방문자 변경은 기각 — 기존 카드와 정의가 갈라지면 혼란, 정의 변경은 별도 제품 결정 |
| JS 방어 | `dashboard-chart.js`는 `Array.isArray(DAILY_VISITORS) && DAILY_VISITORS.length > 0` + 캔버스 존재를 모두 검사 후에만 차트를 그린다 (v3, R1#2) — 템플릿 `th:if`(HTML 문구)와 이중 방어, null 모델에서도 콘솔 오류 없음 |
| 카드·차트 정합성 | **순간 불일치 허용(eventual consistency)** — 통계 카드와 차트는 별도 조회라 사이에 낀 로그인만큼 어긋날 수 있다. 기존 카드 4종끼리도 개별 트랜잭션(서비스 주석의 의도된 설계)이라 동일 성질이며, 대시보드는 새로고침으로 수렴하는 조회 화면이므로 단일 스냅샷 통합은 과잉 (v5, R3#1 — UI 검증의 "+1 대조"는 차트 값 기준) |
| null 계약 | `getDailyVisitorCounts()`는 **어떤 경로에서도 null을 반환하지 않는다** (실패 시 `List.of()`) — 테스트로 강제. 템플릿의 null 허용은 타 `@WebMvcTest` 목 기본값 호환용 방어일 뿐, 운영 계약이 아니다 (v5, R3#2) |
| Chart.js | 기존 vendor(`/vendor/chart.js/Chart.min.js`) 그대로 사용, 새 라이브러리 추가 금지 |

## 수정해야 할 정확한 파일

### 신규 생성
| 파일 | 내용 |
|------|------|
| `src/main/java/com/cms/admin/dashboard/dto/response/DailyVisitorCountResponse.java` | **`record DailyVisitorCountResponse(String date, long count)`** — date는 ISO `yyyy-MM-dd` String으로 확정, 서비스의 최종 매핑에서 `LocalDate.toString()` 수행 (v3, R1#3 — 전 구간 통일) |
| `src/main/resources/static/js/admin/dashboard-chart.js` | 모델 JSON을 읽어 Chart.js 라인 차트를 그리는 스크립트 (기존 `js/demo/` 파일들은 수정하지 않는다) |
| `src/test/java/com/cms/admin/visit/repository/...` | 집계 쿼리 검증은 기존 `VisitLogRepositoryDataJpaTest.java`에 케이스 추가 (새 파일 불필요) |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/cms/admin/visit/repository/VisitLogRepository.java` | 일별 집계 JPQL 추가 (아래 단계 3) |
| `src/main/java/com/cms/admin/dashboard/service/DashboardService.java` | `getDailyVisitorCounts()` 메서드 추가 (최근 7일, 0 채움, 실패 시 빈 리스트) |
| `src/main/java/com/cms/admin/AdminMainController.java` | `/admin` 대시보드 핸들러에서 모델에 `dailyVisitors` 추가 |
| `src/main/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandler.java` | `Clock` 주입 + `tryLogVisit()`의 `visitAt(LocalDateTime.now())` → `now(clock)` (v3, R1#1). 파급: 핸들러를 직접 `new`하는 테스트 설정 4곳(`VisitLoggingAuthenticationSuccessHandlerTest`·`SecurityConfigTest`·`ApiSecurityConfigTest`·`PasswordResetControllerTest`)에 Clock 인자 추가 |
| `src/main/resources/templates/admin/index.html` | 데모 블록 삭제 + 방문자 차트 카드로 교체, 데모 JS `<script>` 2줄 제거, `th:inline="javascript"`로 데이터 주입 |
| `src/test/java/com/cms/admin/dashboard/service/DashboardServiceTest.java` | 새 메서드 테스트 케이스 추가 |
| `src/test/java/com/cms/admin/AdminMainControllerTest.java` | 모델 속성 `dailyVisitors` 존재 검증 추가 |

## 단계별 작업 순서

1. **브랜치 생성**: `git checkout -b feat/dashboard-visitor-chart`
2. **DTO 작성**: `record DailyVisitorCountResponse(String date, long count)` — date는 ISO String (v3, R1#3).
3. **VisitLogRepository에 집계 쿼리 추가**:
   ```java
   /**
    * [start, end) 구간의 일별 방문 수를 날짜 오름차순으로 반환한다.
    * 방문이 없는 날은 행이 없다 — 0 채움은 서비스 책임.
    */
   @Query("select function('date', v.visitAt) as visitDate, count(v) " +
          "from VisitLog v " +
          "where v.visitAt >= :start and v.visitAt < :end " +
          "group by function('date', v.visitAt) " +
          "order by visitDate")
   List<Object[]> countDailyVisits(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
   ```
   주의: `function('date', ...)`의 반환 타입은 JDBC 드라이버뿐 아니라 Hibernate 함수 타입 해석에도 좌우된다 (v4, R2#1). 서비스 변환은 **`LocalDate`(그대로) / `java.sql.Date`(`toLocalDate()`) / `String`(`LocalDate.parse()`) 3분기 + count는 `Number.longValue()`**로 처리하고, 그 외 타입은 실제 클래스명을 로그에 남기고 전체 폴백한다. typed projection 전환은 기각 — 함수 별칭 프로젝션의 프로바이더 재량이 더 불확실.
4. **DashboardService에 메서드 추가**:
   ```java
   /** 최근 7일(오늘 포함) 일별 방문자 수. 방문 없는 날은 0. 실패 시 빈 리스트(화면에서 오류 문구 처리). */
   public List<DailyVisitorCountResponse> getDailyVisitorCounts() { ... }
   ```
   - `LocalDate today = LocalDate.now(clock);` → 구간 `[today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay())`.
   - 쿼리 결과를 `Map<LocalDate, Long>`으로 만들고 6일 전부터 오늘까지 7개 날짜를 순회하며 `getOrDefault(date, 0L)`로 채운다. **최종 매핑에서 `date.toString()`으로 String 변환해 DTO 생성** (v3, R1#3).
   - 전체를 try-catch로 감싸 실패 시 `log.error` + `List.of()` 반환 (기존 `getDashboardStats()` 주석의 트랜잭션 정책과 동일하게 서비스 레벨 `@Transactional` 붙이지 않음).
5. **AdminMainController 수정**: 대시보드 핸들러에 `model.addAttribute("dailyVisitors", dashboardService.getDailyVisitorCounts());` 추가.
6. **index.html 수정**:
   - 삭제: "Generate Report" 버튼(31~33행 부근), Pie 차트 카드 전체(151~ 부근 "Revenue Sources"), 두 번째 Content Row 전체(Projects 진행바 + Color System 팔레트 + Illustrations·Development Approach 카드, 194~345행 부근), `<script src="/js/demo/chart-area-demo.js">`·`<script src="/js/demo/chart-pie-demo.js">` 2줄. (행 번호는 현재 기준 — 실제 삭제 시 주석 `<!-- Area Chart -->`, `<!-- Pie Chart -->`, `<!-- Content Row -->` 블록 경계를 기준으로 잘라낸다.)
   - Area 차트 카드는 유지하되 제목을 "최근 7일 방문자 추이"로 바꾸고 캔버스 id는 `visitorTrendChart`로 교체.
   - 데이터 주입: `<script th:inline="javascript"> const DAILY_VISITORS = /*[[${dailyVisitors}]]*/ []; </script>` 후 `<script src="/js/admin/dashboard-chart.js">` 로드. DTO date는 이미 String(ISO)으로 확정 (설계 결정 표·R1#3 참조 — v6에서 잔재 문구 정리).
   - 빈 리스트면 캔버스 대신 `<p>차트를 불러올 수 없습니다</p>` 표시 (`th:if`).
7. **dashboard-chart.js 작성**: 첫 줄에서 `Array.isArray(DAILY_VISITORS) && DAILY_VISITORS.length > 0 && document.getElementById('visitorTrendChart')` 검사 — 불충족 시 조용히 종료 (v3, R1#2). 이후 라벨(날짜)·데이터(count) 배열로 기존 `chart-area-demo.js` 옵션 스타일을 참고해 라인 차트 렌더링. y축 최소 0, 정수 눈금.
8. **VisitLoggingAuthenticationSuccessHandler 시간원 수정**: `Clock` 주입, `visitAt(LocalDateTime.now(clock))` (v3, R1#1) + 직접 `new`하는 테스트 설정 4곳에 Clock 전달.
9. **테스트**: 서비스(0 채움·7개 보장·실패 폴백·**`LocalDate`/`java.sql.Date`/`String`/예상외 타입 4분기 변환** — v3 R1#6 + v4 R2#1), 리포지토리 집계(경계 [start, end) 포함/제외 + 실 MariaDB 반환 타입 단언 — **공유 dev DB 오염 방지: 충돌 가능성 없는 고유 날짜 구간 사용 + 삽입 전 구간 부재 확인으로 충돌 시 명확 실패, `deleteAll()` 금지** — v4, R2#2), 컨트롤러 모델 속성(**서비스 반환 리스트가 모델에 그대로 전달되는지 값 단언** — v5, R3#2) + `dailyVisitors` null 모델 렌더 200(타 슬라이스 호환용 방어 케이스 주석 명시), 서비스 **null 미반환 계약 단언**(실패 폴백 포함 — v5, R3#2), **핸들러 시간원 검증: UTC 날짜 ≠ KST 날짜인 고정 Clock(예: `2026-07-17T20:00Z` = KST 07-18 05:00)으로 캡처한 `visitAt == LocalDateTime.now(clock)` 단언 — `LocalDateTime.now()` 잔존 시 실제로 실패하는 테스트** (v4, R2#3). `./gradlew test` 전체 실행.
10. **UI 검증 (필수)**: `make dev-db` + `./gradlew bootRun` → playwright로 `admin/1234` 로그인 → 대시보드 진입:
   - 통계 카드 4종 정상 (회귀 확인)
   - 방문자 차트 렌더링 + **로그인 전후 오늘 집계가 정확히 1 증가했는지 DB 대조** (v3, R1#5 — "로그인했으므로 최소 1" 전제 폐기: 방문 저장은 예외를 삼키므로 로그인 성공이 기록을 보장하지 않는다)
   - 데모 카드(파이·진행바·일러스트)가 사라졌는지
   - 브라우저 콘솔에 JS 오류 없는지 (`browser_console_messages`)
   - 다른 화면(회원 관리·메뉴 관리·로그) 회귀 스크린샷
11. **커밋·PR 생성** (한국어 커밋 메시지). `js/demo/` 파일 자체는 삭제하지 않는다(다른 참조 여부와 무관하게 vendor성 자산 — 이번 범위 아님).

## 엣지 케이스

1. **방문 데이터가 전혀 없는 날**: 0으로 채워져 차트가 7개 점을 유지한다 (선이 끊기지 않음).
2. **DB 장애/쿼리 실패**: 빈 리스트 → 화면에 오류 문구, 나머지 대시보드는 정상 렌더 (전체 페이지 500 금지 — 기존 stats 폴백 철학과 동일).
3. **자정 직후 접속**: `Clock` 기준 "오늘"이 구간에 포함되며 오늘 값은 0부터 시작 — 정상.
4. **MANAGER 로그인**: `/admin` 대시보드는 MANAGER 접근 허용(`SecurityConfig` 46행). 방문자 집계는 민감 정보가 아니므로 MANAGER 노출 허용 — 기존 stats와 동일 취급.
5. **`function('date', ...)` 반환 타입 차이**: 테스트 DB(H2 등)와 MariaDB가 다른 타입을 반환할 수 있음 — 단계 3의 방어 변환 필수. CI는 MariaDB service container로 돌므로 CI 통과로 실환경 호환이 검증된다.
6. **LocalDate Thymeleaf 직렬화**: inline JSON에서 `LocalDate`가 객체로 풀리면 차트 라벨이 깨진다 — DTO에서 String으로 변환하는 것이 안전한 기본값.
7. **프로필 이미지 Base64가 큰 계정으로 접속**: 이 작업과 무관하지만 대시보드 렌더 확인 시 topbar 이미지 로딩이 느릴 수 있음 — 차트 검증과 혼동하지 말 것.

## 완료 기준

- [x] `./gradlew test` 전체 통과 (신규 테스트 포함 — BUILD SUCCESSFUL, 2026-07-19).
- [x] 대시보드에서 데모 콘텐츠(Earnings Overview 더미 차트, Revenue Sources 파이, Projects 진행바, Illustrations/Development Approach 카드, Generate Report 버튼)가 모두 제거되었다 (스크린샷 확인).
- [x] `chart-area-demo.js`·`chart-pie-demo.js`를 로드하는 `<script>` 태그가 index.html에 없다.
- [x] 최근 7일 방문자 차트가 실데이터로 렌더링되고, 방문 없는 날이 0으로 표시된다 (playwright 스크린샷 `dashboard-after-cleanup.png` — 0 채움 구간 + 로그인 전후 +1 대조까지 확인).
- [x] 브라우저 콘솔에 JS 오류가 없다 (유일한 오류는 기존 `favicon.ico` 500 — 이 작업과 무관, "이슈" 참조).
- [x] 통계 카드 4종과 타 화면(회원·메뉴·로그 관리)에 회귀가 없다 (스크린샷 3종 확인, MANAGER 로그인 시나리오 포함).
- [x] 집계 쿼리 실패 시 대시보드가 500 없이 오류 문구로 폴백한다 (서비스 테스트로 검증).
- [x] Color System 팔레트 카드가 제거되었다 (v2 추가).
- [x] `dailyVisitors` 모델이 null이어도 템플릿이 렌더링된다 (컨트롤러 null 렌더 200 테스트 — v2 추가).

## 구현·검증 결과 (2026-07-19, feat/dashboard-visitor-chart)

**Context**: 계획 v6(적대적 리뷰 5라운드 — 전부 codex, 최종 Ship) 그대로 구현. 스키마·인가 정책 변경 없음.

**핵심 확정 사항**: 계획 v6과 동일 — 이탈 없음.

**구현 파일**:
- 신규: `DailyVisitorCountResponse`(String date record), `static/js/admin/dashboard-chart.js`(배열·캔버스 이중 방어)
- 수정: `VisitLogRepository.countDailyVisits`(JPQL GROUP BY), `DashboardService.getDailyVisitorCounts()`(0 채움·4분기 변환·빈 리스트 폴백), `AdminMainController`(모델 주입), `VisitLoggingAuthenticationSuccessHandler`(방문 저장 `Clock` 통일 — R1#1), `index.html`(데모 6종 제거 + 차트 카드, 약 200줄 삭제)
- 테스트: `DashboardServiceTest` +4케이스, `AdminMainControllerTest` +2케이스, `VisitLogRepositoryDataJpaTest` +1케이스(고유 2031 구간·타입 단언), `VisitLoggingAuthenticationSuccessHandlerTest` 시간원 검증(UTC≠KST Clock) + 핸들러 생성자 파급 3곳

**검증 결과**:
- `./gradlew test` 전체 통과.
- Playwright 실기: admin 로그인 → 차트 렌더(7일 라벨·0 채움) → **로그인 전후 오늘 집계 5→6(DB)→7(재로그인) +1 대조 성공, 카드·차트 값 일치** → MANAGER 로그인 차트 노출 확인 → 회원·메뉴·활동로그 3화면 회귀 없음 (스크린샷 5종: dashboard-after-cleanup/plus-one/manager, regression-member/menu/log-manage) → 콘솔 JS 오류 없음.
- 검증용 manager 계정·방문 기록은 원복 완료.

**이슈**:
1. **3월분 잔재 파일 2개로 컴파일 실패**: git 미추적 `MemberService.java`·`MemberController.java`(구 setter API 사용)가 테스트 도중 소스 트리에 나타나 빌드가 깨짐 — IDE 로컬 히스토리 복원 추정. 삭제 대신 스크래치패드로 이동 보존 후 빌드 복구 (사용자 확인 필요 시 복원 가능).
2. **`favicon.ico` 500**: 기존 현상(파비콘 리소스·매핑 부재) — 이 작업 범위 밖, 유일한 브라우저 콘솔 오류.

**후속**: 기존 `VisitLogRepositoryDataJpaTest`의 고정 2024 구간·`deleteAll()` 패턴을 고유 구간 방식으로 개정 (R4#1 잔여 — Testcontainers 전환 검토 시 함께).

## 개정 이력

- **v6 변경 (2026-07-18, 적대적 리뷰 4라운드 — codex "수정 후 ship" 판정, 1건 부분 반박)**:
  - **[부분 반박, R4#1] "기존 `deleteAll()` 테스트가 dev DB 실데이터를 삭제"**: 사실 오인 — `@DataJpaTest`는 기본 롤백 트랜잭션이라 `count_returnsAllVisitLogs()`의 `deleteAll()`은 커밋되지 않는다(실데이터 보존, 실측 확인). 남는 실질(테스트 중 일시 행 잠금 경합, 기존 고정 2024 구간 테스트의 기존 행 취약성)은 R2#2 계열의 기존 테스트 문제로, 이번 범위에서는 수정하지 않고 **후속 항목**으로 기록: "기존 `VisitLogRepositoryDataJpaTest`의 고정 날짜·deleteAll 패턴을 고유 구간 방식으로 개정 (Testcontainers 전환 시 함께 재평가)".
- **v5 변경 (2026-07-18, 적대적 리뷰 3라운드 — codex "수정 후 ship" 판정 반영, 1건 수용·1건 부분 수용)**:
  - **[부분 수용, R3#1] 카드·차트 순간 불일치**: 별도 조회 사이 끼어든 로그인만큼 어긋날 수 있음 — 단일 스냅샷 통합은 기각(기존 카드 4종끼리도 개별 트랜잭션인 의도된 설계와 충돌, 조회 화면 과잉), eventual consistency를 설계 결정으로 명시하고 UI 검증 기준을 차트 값으로 명확화.
  - **[수용, R3#2] null 계약 강제**: 서비스 null 미반환 단언 + 컨트롤러 모델 값 전달 단언 + null 렌더 테스트의 방어 목적 주석 — 컨트롤러 회귀가 DB 장애 폴백으로 위장되는 것 방지.
- **v4 변경 (2026-07-18, 적대적 리뷰 2라운드 — codex "수정 후 ship" 판정 반영, 2건 수용·1건 부분 수용)**:
  - **[수용, R2#1] 변환 분기 확장**: 반환 타입은 Hibernate 타입 해석에도 좌우 — `LocalDate` 분기 추가(정상 쿼리가 폴백으로 위장되는 것 방지), count `Number.longValue()`, 예상외 타입 로그에 클래스명. typed projection은 기각(프로바이더 재량이 더 불확실).
  - **[부분 수용, R2#2] 공유 dev DB 오염 방지**: 신규 GROUP BY 테스트는 고유 날짜 구간 + 삽입 전 부재 확인으로 작성. Testcontainers 전환은 기각 — 로드맵 별도 후보로 관리 중(범위 확대), `deleteAll()` 금지 동의.
  - **[수용, R2#3] 시간원 수정 검증 테스트**: UTC/KST 날짜가 갈리는 고정 Clock으로 `visitAt` 단언 — 컴파일 통과만으로는 `LocalDateTime.now()` 잔존을 못 잡는다는 지적 수용.
- **v3 변경 (2026-07-18, 적대적 리뷰 1라운드 — codex "수정 후 ship" 판정 반영, 5건 수용·1건 부분 수용)**:
  - **[수용, R1#1] 방문 저장·집계 시간원 통일**: `tryLogVisit()`의 `LocalDateTime.now()` 직접 호출은 KST Clock 집계와 불일치(CI UTC에서 자정 전후 왜곡) — 기존 통계 카드에도 잠복한 결함이며 차트가 같은 데이터를 소비하므로 핸들러 Clock 주입으로 수정. 테스트 설정 4곳 파급 명시.
  - **[수용, R1#2] JS null 방어**: 템플릿 `th:if`만으로는 무조건 로드되는 `dashboard-chart.js`의 콘솔 오류를 못 막는다 — `Array.isArray` + 길이 + 캔버스 존재 검사 후에만 렌더. 컨트롤러 테스트에 null 모델 200 케이스 추가.
  - **[수용, R1#3] DTO date=String 전 구간 통일**: 신규 파일 설명·단계 2·단계 4가 `LocalDate`로 남아 구현자가 되돌릴 위험 — record 시그니처와 최종 매핑 지점(`date.toString()`)을 명시.
  - **[부분 수용, R1#4] "방문자" 정의 문서화**: 관리자 로그인 성공 1회=방문 1건(기존 카드와 동일 정의)을 설계 결정으로 명시. `count(distinct)` 고유 방문자 전환은 기각 — 기존 카드 4종과 정의 분열, 별도 제품 결정 사안.
  - **[수용, R1#5] UI 검증 전제 수정**: 방문 저장은 예외를 삼키므로 "로그인했으니 최소 1" 보장 없음 — 로그인 전후 오늘 집계 +1 DB 대조로 절차 교체.
  - **[수용, R1#6] 변환 분기 테스트 명시**: 서비스 `java.sql.Date`/`String`/예상외 타입 3분기 + 리포지토리 실반환 타입 단언 추가.
- **v2 변경 (2026-07-18, 정찰 반영 — 리뷰 착수 전 실측 갱신)**:
  - **Color System 팔레트 카드 삭제 추가**: 정찰에서 계획 삭제 목록에 누락된 데모 블록(239~305행) 확인 — "데모 콘텐츠 제거" 목표의 자연 범위로 포함.
  - **DTO date = String(ISO) 확정**: 엣지 6의 조건부 서술을 확정 결정으로 승격.
  - **템플릿 null 방어 결정**: `AdminSidebarAdviceTest` 등 타 `@WebMvcTest`가 `/admin` 뷰를 실렌더링하며 목 서비스 기본 반환(null)을 받는 파급 실측 — null·빈 리스트 동일 처리.
  - **집계 반환 타입 실측**: 테스트 DB는 실 MariaDB(`Replace.NONE`) — `java.sql.Date` 기준 + String 방어 변환 유지.
