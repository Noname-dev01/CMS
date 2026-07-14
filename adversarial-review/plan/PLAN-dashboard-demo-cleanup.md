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
| 대체 차트 | **최근 7일(오늘 포함) 일별 방문자 수** 라인(Area) 차트 1개. Pie 차트·진행바·일러스트 카드는 대체 없이 **삭제** |
| 데이터 전달 방식 | REST API 신설 없이 **페이지 모델로 주입** (`AdminMainController` → Thymeleaf inline JSON). 대시보드는 페이지 렌더 시점 데이터로 충분하고, API를 만들면 인가·문서화 부담만 늘어남 |
| 집계 쿼리 | `VisitLogRepository`에 파생 쿼리 추가가 불가능한 GROUP BY이므로 `@Query` JPQL 사용 (단순 GROUP BY라 QueryDSL 불필요 — CLAUDE.md의 "동적 조건·복잡한 조인"에 해당하지 않음) |
| 날짜 빈 구멍 채우기 | DB는 방문 있는 날만 반환하므로 **서비스에서 7일 전 구간을 0으로 채워** 항상 7개 요소를 보장 |
| 실패 폴백 | 기존 `DashboardService.getDashboardStats()`와 동일 철학: 집계 실패 시 예외를 삼키고 **빈 리스트** 반환, 화면은 "차트를 불러올 수 없습니다" 문구 표시 |
| 시간 기준 | 기존과 동일하게 `Clock` 빈 주입 + `LocalDate.now(clock)` (테스트 가능성) |
| Chart.js | 기존 vendor(`/vendor/chart.js/Chart.min.js`) 그대로 사용, 새 라이브러리 추가 금지 |

## 수정해야 할 정확한 파일

### 신규 생성
| 파일 | 내용 |
|------|------|
| `src/main/java/com/cms/admin/dashboard/dto/response/DailyVisitorCountResponse.java` | `{ LocalDate date, long count }` record 또는 `@Getter @Builder` 클래스 |
| `src/main/resources/static/js/admin/dashboard-chart.js` | 모델 JSON을 읽어 Chart.js 라인 차트를 그리는 스크립트 (기존 `js/demo/` 파일들은 수정하지 않는다) |
| `src/test/java/com/cms/admin/visit/repository/...` | 집계 쿼리 검증은 기존 `VisitLogRepositoryDataJpaTest.java`에 케이스 추가 (새 파일 불필요) |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/cms/admin/visit/repository/VisitLogRepository.java` | 일별 집계 JPQL 추가 (아래 단계 3) |
| `src/main/java/com/cms/admin/dashboard/service/DashboardService.java` | `getDailyVisitorCounts()` 메서드 추가 (최근 7일, 0 채움, 실패 시 빈 리스트) |
| `src/main/java/com/cms/admin/AdminMainController.java` | `/admin` 대시보드 핸들러에서 모델에 `dailyVisitors` 추가 |
| `src/main/resources/templates/admin/index.html` | 데모 블록 삭제 + 방문자 차트 카드로 교체, 데모 JS `<script>` 2줄 제거, `th:inline="javascript"`로 데이터 주입 |
| `src/test/java/com/cms/admin/dashboard/service/DashboardServiceTest.java` | 새 메서드 테스트 케이스 추가 |
| `src/test/java/com/cms/admin/AdminMainControllerTest.java` | 모델 속성 `dailyVisitors` 존재 검증 추가 |

## 단계별 작업 순서

1. **브랜치 생성**: `git checkout -b feat/dashboard-visitor-chart`
2. **DTO 작성**: `DailyVisitorCountResponse(LocalDate date, long count)`.
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
   주의: `function('date', ...)`의 반환 타입은 드라이버에 따라 `java.sql.Date`일 수 있다. 서비스에서 `((java.sql.Date) row[0]).toLocalDate()`로 변환하고, `String`으로 오는 경우 `LocalDate.parse()`로 방어한다 (H2/MariaDB 차이 — `VisitLogRepositoryDataJpaTest`가 어떤 DB로 도는지 확인 후 실제 반환 타입에 맞춘다).
4. **DashboardService에 메서드 추가**:
   ```java
   /** 최근 7일(오늘 포함) 일별 방문자 수. 방문 없는 날은 0. 실패 시 빈 리스트(화면에서 오류 문구 처리). */
   public List<DailyVisitorCountResponse> getDailyVisitorCounts() { ... }
   ```
   - `LocalDate today = LocalDate.now(clock);` → 구간 `[today.minusDays(6).atStartOfDay(), today.plusDays(1).atStartOfDay())`.
   - 쿼리 결과를 `Map<LocalDate, Long>`으로 만들고 6일 전부터 오늘까지 7개 날짜를 순회하며 `getOrDefault(date, 0L)`로 채운다.
   - 전체를 try-catch로 감싸 실패 시 `log.error` + `List.of()` 반환 (기존 `getDashboardStats()` 주석의 트랜잭션 정책과 동일하게 서비스 레벨 `@Transactional` 붙이지 않음).
5. **AdminMainController 수정**: 대시보드 핸들러에 `model.addAttribute("dailyVisitors", dashboardService.getDailyVisitorCounts());` 추가.
6. **index.html 수정**:
   - 삭제: "Generate Report" 버튼(31~33행 부근), Pie 차트 카드 전체(151~ 부근 "Revenue Sources"), Projects 진행바 카드, Illustrations·Development Approach 카드, `<script src="/js/demo/chart-area-demo.js">`·`<script src="/js/demo/chart-pie-demo.js">` 2줄. (행 번호는 현재 기준 — 실제 삭제 시 주석 `<!-- Area Chart -->`, `<!-- Pie Chart -->`, `<!-- Content Row -->` 블록 경계를 기준으로 잘라낸다.)
   - Area 차트 카드는 유지하되 제목을 "최근 7일 방문자 추이"로 바꾸고 캔버스 id는 `visitorTrendChart`로 교체.
   - 데이터 주입: `<script th:inline="javascript"> const DAILY_VISITORS = /*[[${dailyVisitors}]]*/ []; </script>` 후 `<script src="/js/admin/dashboard-chart.js">` 로드. `DashboardStatsResponse`처럼 Jackson 직렬화가 아니라 Thymeleaf 직렬화이므로 `LocalDate`가 어떻게 렌더링되는지 확인하고, 문제가 되면 DTO의 date를 `String`(ISO `yyyy-MM-dd`)으로 바꾼다 — **가장 단순한 해결을 선택**.
   - 빈 리스트면 캔버스 대신 `<p>차트를 불러올 수 없습니다</p>` 표시 (`th:if`).
7. **dashboard-chart.js 작성**: `DAILY_VISITORS`로 라벨(날짜)·데이터(count) 배열을 만들어 기존 `chart-area-demo.js`의 Chart.js 옵션 스타일을 참고해 라인 차트 렌더링. y축 최소 0, 정수 눈금.
8. **테스트**: 서비스(0 채움·7개 보장·실패 폴백), 리포지토리 집계(경계 [start, end) 포함/제외), 컨트롤러 모델 속성. `./gradlew test` 전체 실행.
9. **UI 검증 (필수)**: `make dev-db` + `./gradlew bootRun` → playwright로 `admin/1234` 로그인 → 대시보드 진입:
   - 통계 카드 4종 정상 (회귀 확인)
   - 방문자 차트 렌더링 (방금 로그인했으므로 오늘 최소 1)
   - 데모 카드(파이·진행바·일러스트)가 사라졌는지
   - 브라우저 콘솔에 JS 오류 없는지 (`browser_console_messages`)
   - 다른 화면(회원 관리·메뉴 관리·로그) 회귀 스크린샷
10. **커밋·PR 생성** (한국어 커밋 메시지). `js/demo/` 파일 자체는 삭제하지 않는다(다른 참조 여부와 무관하게 vendor성 자산 — 이번 범위 아님).

## 엣지 케이스

1. **방문 데이터가 전혀 없는 날**: 0으로 채워져 차트가 7개 점을 유지한다 (선이 끊기지 않음).
2. **DB 장애/쿼리 실패**: 빈 리스트 → 화면에 오류 문구, 나머지 대시보드는 정상 렌더 (전체 페이지 500 금지 — 기존 stats 폴백 철학과 동일).
3. **자정 직후 접속**: `Clock` 기준 "오늘"이 구간에 포함되며 오늘 값은 0부터 시작 — 정상.
4. **MANAGER 로그인**: `/admin` 대시보드는 MANAGER 접근 허용(`SecurityConfig` 46행). 방문자 집계는 민감 정보가 아니므로 MANAGER 노출 허용 — 기존 stats와 동일 취급.
5. **`function('date', ...)` 반환 타입 차이**: 테스트 DB(H2 등)와 MariaDB가 다른 타입을 반환할 수 있음 — 단계 3의 방어 변환 필수. CI는 MariaDB service container로 돌므로 CI 통과로 실환경 호환이 검증된다.
6. **LocalDate Thymeleaf 직렬화**: inline JSON에서 `LocalDate`가 객체로 풀리면 차트 라벨이 깨진다 — DTO에서 String으로 변환하는 것이 안전한 기본값.
7. **프로필 이미지 Base64가 큰 계정으로 접속**: 이 작업과 무관하지만 대시보드 렌더 확인 시 topbar 이미지 로딩이 느릴 수 있음 — 차트 검증과 혼동하지 말 것.

## 완료 기준

- [ ] `./gradlew test` 전체 통과 (신규 테스트 포함).
- [ ] 대시보드에서 데모 콘텐츠(Earnings Overview 더미 차트, Revenue Sources 파이, Projects 진행바, Illustrations/Development Approach 카드, Generate Report 버튼)가 모두 제거되었다.
- [ ] `chart-area-demo.js`·`chart-pie-demo.js`를 로드하는 `<script>` 태그가 index.html에 없다.
- [ ] 최근 7일 방문자 차트가 실데이터로 렌더링되고, 방문 없는 날이 0으로 표시된다 (playwright 스크린샷).
- [ ] 브라우저 콘솔에 JS 오류가 없다.
- [ ] 통계 카드 4종과 타 화면(회원·메뉴·로그 관리)에 회귀가 없다 (스크린샷 확인).
- [ ] 집계 쿼리 실패 시 대시보드가 500 없이 오류 문구로 폴백한다 (서비스 테스트로 검증).
