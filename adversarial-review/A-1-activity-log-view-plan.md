# A-1. 활동 로그(AdminActionLog) 조회 기능 구현 (개정판 v5)

> v5 변경(사용자 결정 반영):
> - **결정 1 — 인덱스만 추가, 기동 검증기 제거.** dev는 `ddl-auto: update`이고 `admin_action_log`
>   테이블이 이미 존재(AOP가 계속 로그를 씀)하므로 Hibernate가 기존 테이블에 새 `@Index`를 신뢰성
>   있게 추가하지 못할 수 있다. fail-fast 기동 검증기는 dev 기동까지 막을 위험이 있고 현 '최소 운영
>   골격' 단계엔 과해, **`AdminActionLogIndexVerifier`를 만들지 않는다.** 대신 `@Index` + 마이그레이션
>   SQL + 운영 체크리스트로 처리하고, 인덱스 누락은 (기동 실패가 아니라) **성능 저하**로만 나타난다.
> - **결정 2 — 기간 정책 유지.** 미지정 시 최근 30일, 명시 시 from~to 간격 최대 3개월(초과 400).
> - **결정 3 — 기간 검증은 DTO에서, 에러 코드는 `VALIDATION_ERROR`.** 역전·3개월 초과는 from/to가
>   모두 입력된 경우에만 발생하므로 DTO 클래스레벨 `@AssertTrue`로 검증한다(@Valid 바인딩 → 400
>   `VALIDATION_ERROR`). Service는 기본값 주입만 담당하고 기간 관련 예외를 던지지 않는다. (이 코드베이스에서
>   `VALIDATION_ERROR`는 `@Valid` 바인딩에서만 나오고, Service-throw는 `INVALID_REQUEST`가 되기 때문.)
> - **결정 4 — actionType·actionResult 한글 라벨 매핑.** member 화면(ROLE/STATUS 한글화)과 일관되게
>   `ADMIN_CREATE`→"관리자 생성", `PASSWORD_CHANGE`→"비밀번호 변경", `SUCCESS`→"성공", `FAIL`→"실패"로
>   표시한다. 라벨은 JS 상수 맵 단일 정의, 드롭다운 option의 value는 원문(상수)을 유지.
>
> (v2~v4 누적 결정 유지: 상세는 목록 데이터 재사용(별도 `GET /logs/{id}` 없음), actionType 고정
> 드롭다운 + 상수 단일 출처(`AdminActionTypes`), 기본 정렬 `createAt desc, id desc` 복합 + 복합 인덱스
> `(create_at, id)`, 상세 응답에 `actionId`(행위자 member PK) 포함.)

## Context

이 CMS는 `@AdminActionLogged` AOP로 관리자 행위를 `admin_action_log` 테이블에 기록한다
(`AdminActionLogAspect` → `AdminActionLogService.log()`, REQUIRES_NEW). 그러나 **쌓인 로그를 관리자가
조회할 수단이 전혀 없다** — `AdminActionLogRepository`는 조회 메서드가 없고(`JpaRepository`만 상속),
조회용 Service/Controller/화면도 없다. 감사 로그가 보이지 않는 것은 관리자 CMS의 실질적 결함이다.

이 작업은 **읽기 전용 조회 기능**을 추가한다. 기록 파이프라인(AOP·쓰기 서비스
`AdminActionLogService`)은 건드리지 않는다. member 도메인의 조회 패턴(`MemberRepositoryImpl`,
`AdminMemberController`, `admin-manage.html`)을 기반으로 하되, **로그 데이터의 고유 특성(무한 증가·
append-only·createAt 중복 가능·감사 민감도)** 을 고려해 패턴을 그대로 복제하지 않고 아래처럼 조정한다.

**확정된 요구사항**:
- 검색 필터: 수행자 아이디(`actionUserId`, contains), 액션 유형(`actionType`, **고정 드롭다운 —
  `AdminActionTypes.ALL` 기반**, eq), 결과(`actionResult`, eq), 기간(`createAt` from~to, **미지정 시
  기본 최근 30일**, **명시 시 from~to 간격 최대 3개월** — 초과 시 400 `VALIDATION_ERROR`).
- 상세 보기: 행 클릭 시 **모달**로 전체 필드 표시. **별도 상세 API 없이 목록 응답 데이터를 재사용**한다.
  (requestIp/Uri/Method/errorMessage가 목록 페이로드에 실리는 것은 수용된 트레이드오프.)
- 권한: `ROLE_ADMIN` 전용 (SecurityConfig `/admin/**`·`/admin/api/**`가 이미 ADMIN 전용 — 기존 패턴 유지).

## 신규/수정 파일

대상 패키지: `com.cms.admin.log`

**신규 (Java)**
- `dto/request/AdminActionLogSearchRequest.java`
- `dto/response/AdminActionLogResponse.java`
- `dto/response/AdminActionLogPageResponse.java`
- `repository/AdminActionLogRepositoryCustom.java`
- `repository/AdminActionLogRepositoryImpl.java`
- `service/AdminActionLogQueryService.java` (조회 전용 — 기존 쓰기 서비스와 책임 분리)
- `controller/AdminActionLogController.java` (REST — **목록 단일 엔드포인트**)
- `controller/AdminActionLogPageController.java` (페이지)

> `constant/AdminActionTypes.java`는 **이미 존재**한다(ADMIN_CREATE·PASSWORD_CHANGE·ALL 정의 완료).
> 신규 작성이 아니라 그대로 사용한다.
> **결정 1에 따라 `config/AdminActionLogIndexVerifier.java`는 만들지 않는다.**

**수정 (Java)**
- `repository/AdminActionLogRepository.java` — `extends JpaRepository<...>, AdminActionLogRepositoryCustom`
- `domain/AdminActionLog.java` — `@Table`에 인덱스 추가 (아래 5번)
- `member/service/AdminMemberService.java` — `:47`/`:174`의 `@AdminActionLogged(actionType="ADMIN_CREATE")`
  / `"PASSWORD_CHANGE"` 리터럴을 `AdminActionTypes.ADMIN_CREATE` / `AdminActionTypes.PASSWORD_CHANGE`
  상수 참조로 교체.

**신규/수정 (화면)**
- 신규 `templates/admin/log/manage.html`
- 수정 `templates/admin/fragments/sidebar.html` — 활동 로그 메뉴 링크 추가

**신규 (운영 산출물)**
- `docs/migration/{날짜}_add_admin_action_log_indexes.sql` — 인덱스 추가 `ALTER TABLE`. 프로젝트에
  Flyway/Liquibase가 없어 수동 적용이며, **결정 1에 따라 런타임 강제 검증은 없다.** SQL은 dev·prod
  **모두** 적용 대상이다(아래 5·6번 참조).

## 구현 상세

### 1. 상수 — `constant/AdminActionTypes` (기존, 변경 없음)

```java
public static final String ADMIN_CREATE    = "ADMIN_CREATE";
public static final String PASSWORD_CHANGE = "PASSWORD_CHANGE";
public static final List<String> ALL = List.of(ADMIN_CREATE, PASSWORD_CHANGE);
```

- 어노테이션 속성은 컴파일 상수만 허용 → `static final String` 사용 가능.
- `AdminMemberService.java:47`, `:174`의 actionType 리터럴을 이 상수 참조로 교체(위 수정 항목).

### 2. DTO

- **`AdminActionLogSearchRequest`** (`@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`):
  `actionUserId`(`@Size(max=50)`), `actionType`(`@Size(max=100)`), `actionResult`(`AdminActionResult`),
  `from`/`to`(`LocalDate`, `@DateTimeFormat(iso=DATE)`).
  - **기간 검증은 DTO 클래스레벨 `@AssertTrue` 메서드 2개로 수행**(결정 3):
    - 역전 검증: `from`/`to`가 **둘 다 non-null이고 `from > to`이면 invalid**(한쪽이라도 null이면 통과).
    - 3개월 초과 검증: `from`/`to`가 **둘 다 non-null이고 `to > from.plusMonths(3).minusDays(1)`이면
      invalid**(한쪽이라도 null이면 통과). 종료일이 Repository에서 포함(inclusive) 조회되므로 마지막
      포함일은 `from.plusMonths(3).minusDays(1)`이다. 한쪽만/미입력은 Service 기본값 보정으로 위반
      불가하므로 DTO에서 검사하지 않아도 안전.
    - `@AssertTrue` 위반은 `@Valid` 바인딩 실패 → `GlobalApiExceptionHandler`가 400 `VALIDATION_ERROR`로 응답.
    - ⚠️ 핸들러 메시지는 `"필드명: defaultMessage"` 형식이라 `@AssertTrue`의 파생 필드명("dateRangeValid"
      등)이 사용자에게 노출된다. 사람이 읽을 메시지를 위해 각 `@AssertTrue`에 명시적 `message`를 지정한다
      (예: "조회 기간은 3개월을 초과할 수 없습니다.", "조회 시작일이 종료일보다 늦을 수 없습니다.").
  - 기본값 주입(미입력 → 최근 30일, 한쪽만 → 반대편 ±29일)은 **Service에서 수행**(아래 4번). DTO는 검증만.
- **`AdminActionLogResponse`** (`@Getter @Builder @AllArgsConstructor @NoArgsConstructor`):
  `id, actionId, actionUserId, actionType, actionResult, targetType, targetId, requestIp, requestUri,
  requestMethod, errorMessage, createAt`.
  - **`actionId`(행위자 member PK) 포함** — `actionUserId`는 변경·중복·탈퇴 가능하나 PK는 안정적이라
    감사 식별에 필요. **null 가능**(시스템 로그 — 보안 컨텍스트 없는 경우).
  - ⚠️ **주의**: `application.yml`의 Jackson `default-property-inclusion: non_null` 때문에 `actionId`가
    null이면 **JSON 응답에서 키 자체가 생략**된다. 모달 JS는 `actionId`가 **없거나 null이면 "시스템"**
    으로 표시하도록 작성한다(`item.actionId ?? "시스템"` 형태로 undefined·null 모두 처리).
- **`AdminActionLogPageResponse`** (member의 `AdminMemberPageResponse`와 동일 형태):
  `content, page, size, totalElements, totalPages, last`. **`Page`(totalElements) 유지**.

### 3. Repository (QueryDSL)

- `AdminActionLogRepositoryCustom`: `Page<AdminActionLog> searchActionLogs(AdminActionLogSearchRequest req, Pageable pageable)`.
- `AdminActionLogRepositoryImpl` — `MemberRepositoryImpl` 패턴 복제(`JPAQueryFactory` + `QAdminActionLog`):
  - `BooleanBuilder`: `actionUserId` → `contains`, `actionType` → **`eq`**(드롭다운 고정값), `actionResult` → `eq`.
  - ⚠️ **빈값 가드 필수**: 드롭다운 "전체" 옵션은 `value=""`라 String 필드(`actionUserId`/`actionType`)는
    **빈 문자열**로 바인딩된다. `MemberRepositoryImpl`처럼 `hasText(...)` 가드로 감싸야 한다 — 가드가 없으면
    `actionType.eq("")`가 되어 결과가 0건이 된다. (enum `actionResult`는 Spring이 빈 문자열→null로 변환하므로
    `!= null` 가드로 충분, member의 status 처리와 동일.)
  - 기간: `from` → `createAt.goe(from.atStartOfDay())`, `to` → `createAt.lt(to.plusDays(1).atStartOfDay())`(종료일 포함).
    **기간 정규화는 Service에서 먼저 수행된 값을 받아 적용.** 서버 단일 타임존 가정(한국 운영).
  - 정렬: 화이트리스트(`id, actionType, actionUserId, createAt`). **기본 정렬 `createAt desc, id desc`
    복합** — createAt 중복 시 페이징 안정성 확보. `toOrderSpecifiers`가 화이트리스트로 거른 뒤,
    항상 마지막에 `id.desc()` tie-breaker를 덧붙인다(유효 정렬이 없으면 `createAt.desc(), id.desc()`).
    enum(`actionResult`)은 EnumPath라 정렬 미지원(`MemberRepositoryImpl`의 userType/status와 동일하게 null 반환).

### 4. Service — `AdminActionLogQueryService` (`@Transactional(readOnly = true)`)

- `searchActionLogs(req, pageable)` → `AdminActionLogPageResponse` 변환.
- **기간 기본값 주입 로직** (Repository 호출 전 수행, `LocalDate.now()` 기준). 역전·3개월 초과 **검증은
  DTO `@AssertTrue`가 이미 통과시킨 입력**만 들어오므로(결정 3) Service는 주입만 하고 예외를 던지지 않는다:
  1. `from`/`to` 둘 다 null → `to = today`, `from = today.minusDays(29)` (최근 30일).
  2. `to`만 null → `to = from.plusDays(29)`.
  3. `from`만 null → `from = to.minusDays(29)`.
  4. 둘 다 입력 → 그대로 사용(DTO에서 역전·3개월 이미 검증됨).
- **size clamp**: `pageable.getPageSize() > 100`이면 `PageRequest.of(pageNumber, 100, sort)`로 재구성.
  전역 resolver는 member 등 타 도메인에 영향을 주므로 채택하지 않고 **로그 도메인 Service에 한정 적용.**
- 기존 `AdminActionLogService`(쓰기, REQUIRES_NEW)는 수정하지 않는다.
- **`getActionLog(id)` 단건 조회 메서드는 만들지 않는다** (상세는 목록 데이터 재사용).

### 5. 엔티티 인덱스 — `domain/AdminActionLog.java` (스키마 변경)

- `@Table(name = "admin_action_log", indexes = {`
  `@Index(name="idx_log_create_at_id", columnList="create_at, id"),`
  `@Index(name="idx_log_action_user_id", columnList="action_user_id"),`
  `@Index(name="idx_log_action_type", columnList="action_type") })`
- **`columnList`는 물리 컬럼명**(`create_at`, `action_user_id`, `action_type`). 네이밍 전략 설정이 없어
  Spring Boot 기본(snake_case)이 적용되므로 필드명(`createAt`)을 그대로 쓰면 어긋난다.
- **설계 근거**: 기본 정렬이 `create_at desc, id desc`이므로 `(create_at, id)` 복합 인덱스가 정렬·기간
  필터를 함께 커버한다. 단일 `create_at`은 복합의 prefix라 중복이므로 두지 않는다. `action_result`는
  카디널리티 2값(SUCCESS/FAIL)이라 단독 인덱스 효용이 낮아 제외. `actionUserId contains`는 선두 LIKE라
  인덱스를 완전히 활용하진 못하나, 핵심 비용(정렬·기간)은 복합 인덱스가 줄인다.
- **⚠️ DDL 적용 한계 (결정 1로 검증기를 제거했으므로 중요)**:
  - `admin_action_log` 테이블은 **이미 존재**한다(AOP가 기록 중). Hibernate `ddl-auto: update`는 **기존
    테이블에 새 인덱스를 신뢰성 있게 추가하지 못하는 경우가 많다.** 따라서 dev에서도 `@Index` 선언만으로는
    인덱스가 안 생길 수 있다.
  - 그러므로 **dev·prod 모두 `docs/migration/` SQL을 수동 적용**하는 것을 표준 절차로 한다. 적용 누락 시
    기능은 정상 동작하되 대용량에서 느려질 뿐(기동 실패 아님). 적용 여부는 검증 단계의 `SHOW INDEX` 수동
    확인으로 점검한다.
  - prod는 `validate` 모드라 인덱스를 검증하지 않는다(스키마 검증 대상이 아님) — SQL 선적용이 전제.
- FK·연관관계 없어(append-only) 데이터 영향 없음.

### 6. 마이그레이션 SQL — `docs/migration/{날짜}_add_admin_action_log_indexes.sql`

- 복합 `(create_at, id)` + `action_user_id` + `action_type` 인덱스 생성.
- 이전에 단일 `create_at` 인덱스를 수동 생성한 환경이 있다면 `DROP INDEX idx_log_create_at ON
  admin_action_log;` 후 복합 인덱스를 생성하는 순서를 파일에 주석으로 명시.
- 멱등성을 위해 적용 전 `SHOW INDEX FROM admin_action_log;`로 중복 확인하라는 주석 포함(MariaDB는
  `CREATE INDEX IF NOT EXISTS` 미지원 버전이 있으므로 SQL 자동 분기 대신 주석 안내로 둔다).
- ⚠️ **온라인 DDL(운영)**: `admin_action_log`는 AOP가 **계속 INSERT**하는 테이블이라, prod에서 데이터가
  많을 때 인덱스 추가 `ALTER TABLE`이 테이블을 잠그면 감사 쓰기 경로가 막힐 수 있다. MariaDB 온라인 DDL을
  사용해 잠금을 피한다 — `ALTER TABLE admin_action_log ADD INDEX ... , ALGORITHM=INPLACE, LOCK=NONE;`
  (지원 안 되는 버전이면 점검창에서 적용). 마이그레이션 SQL과 운영 체크리스트에 이 옵션을 명시한다.

### 7. Controller

- `AdminActionLogController` `@RestController @RequestMapping("/admin/api")`, `@Tag`:
  - `GET logs` — `@ParameterObject @PageableDefault(size = 20) Pageable` + `@Valid @ModelAttribute
    AdminActionLogSearchRequest` → 200, `@PreAuthorize("hasRole('ADMIN')")`. (member의 `GET members` 패턴 동일.)
  - **`GET logs/{id}` 없음** (목록 데이터 재사용).
- `AdminActionLogPageController` `@Controller @RequestMapping("/admin/log")`:
  - `GET /manage` → 모델에 **`AdminActionTypes.ALL`**(`actionTypes`) + **기간 기본값
    `defaultFrom`(`LocalDate.now().minusDays(29)`)·`defaultTo`(`LocalDate.now()`)** 주입,
    `"admin/log/manage"` 뷰 반환. Thymeleaf `th:value`로 날짜 input에 바인딩 — JS 초기화 불필요.

### 8. 화면 `templates/admin/log/manage.html`

- `admin/member/admin-manage.html` 베이스로 복제:
  - **한글 라벨 매핑(결정 4)**: member의 `getRoleLabel`/`getStatusLabel` 패턴을 따라 JS 상수 맵을 단일
    정의한다 — `ACTION_TYPE_LABELS = {ADMIN_CREATE:"관리자 생성", PASSWORD_CHANGE:"비밀번호 변경"}`,
    `RESULT_LABELS = {SUCCESS:"성공", FAIL:"실패"}` + `getActionTypeLabel(v)`/`getResultLabel(v)`(미정의
    값은 원문 fallback). 테이블·모달·드롭다운 표기 모두 이 맵을 단일 출처로 사용.
  - 검색폼: 수행자 아이디(text), **액션 유형(select: `th:each`로 `${actionTypes}`(=AdminActionTypes.ALL)을
    `th:value`로 렌더 — option의 value는 원문 상수. 표시 텍스트는 페이지 로드 시 JS가 `ACTION_TYPE_LABELS`
    로 채운다(라벨 단일 출처 유지). 서버는 value만, 라벨은 JS 맵.)**, 결과(select: 전체/성공/실패 —
    value는 SUCCESS/FAIL), 기간(date `from`~`to`, 기본값 최근 30일: PageController가 주입한
    `defaultFrom`·`defaultTo`를 `th:value`로 바인딩).
  - 테이블: 일시(createAt), 수행자(actionUserId), 액션(`getActionTypeLabel`), 결과(배지 성공=초록/실패=빨강,
    `getResultLabel`), 대상(targetType/targetId), 상세 버튼.
  - **상세 모달: 목록 fetch로 받은 행 객체를 JS 배열에 보관했다가 클릭 시 그대로 렌더링**(추가 fetch
    없음 — member 템플릿의 `loadAdminDetail` 별도 fetch 방식과 다른 점). `actionId`(null/undefined면
    "시스템") / `actionUserId` / `errorMessage` / `requestIp` / `requestUri` / `requestMethod` 포함.
  - 기존 `formatDate`/`escapeHtml`/페이징/`buildQueryString` JS 재사용. GET 조회뿐이라 **CSRF 토큰 불필요**.
  - 기간 간격 3개월 초과 등 서버 400 응답은 `extractErrorMessage`로 메시지를 꺼내 `showPageError`로 노출.
  - ⚠️ **XSS 필수 처리(보안)**: 로그 필드 중 `requestUri`·`errorMessage`·`requestIp`는 **공격자가
    영향을 줄 수 있는 값**이다(요청 URL·헤더, 사용자 입력이 섞인 예외 메시지). member 화면은 통제된 값
    위주라 덜 민감했지만, 로그 화면은 **테이블·모달에 출력하는 모든 로그 필드를 예외 없이 `escapeHtml`로
    이스케이프**해야 한다. 특히 `errorMessage`(자유 텍스트, 개행 포함 가능)와 `requestUri`를 절대 raw HTML로
    삽입하지 않는다. `actionId`/`targetId` 등 숫자 필드도 일관되게 `escapeHtml` 처리(member 패턴 유지).

### 9. 사이드바 (`sidebar.html`)

- "회원 관리" 섹션(40~57행) 뒤에 `<hr>` + "시스템" `sidebar-heading` + `@{/admin/log/manage}`
  nav-item(`fa-clipboard-list`) 추가. 주석 처리된 메뉴 관리(28~35행)는 건드리지 않는다.
- 참고: ROLE_MANAGER는 `/admin/**` 자체 차단이라 이 링크가 무의미(member 메뉴와 동일한 기존 이슈) — A-1 범위 밖.

### 10. Q클래스

- 코드 작성 후 `./gradlew compileJava`로 `QAdminActionLog` 생성.

## 범위 밖

- `@AdminActionLogged` **적용 지점 확대**(신규 기록 지점 추가, 현 2곳). 단 기존 2곳의 리터럴 →
  `AdminActionTypes.*` 상수 리팩터링은 **범위 안**(상수 단일 출처 정책 — v2~v4 누적 결정).
- 로그 보관기간/마스킹 정책, CSV 내보내기.
- 기동 시 인덱스 자동 검증(결정 1로 제외).

## 보안·성능 점검 결과 (재검토)

**보안/인가**
- 인가: `GET /admin/api/logs`는 `@PreAuthorize("hasRole('ADMIN')")` + SecurityConfig `/admin/api/**`
  ADMIN 전용(이중 방어, member 패턴). `/admin/log/manage` 페이지는 `/admin/**` ADMIN 전용 — 페이지
  컨트롤러에 별도 `@PreAuthorize` 불필요(member 페이지 컨트롤러와 동일).
- **XSS가 이번 작업의 핵심 신규 리스크** — `requestUri`·`errorMessage`·`requestIp`는 공격자 영향 가능
  값이므로 화면 렌더링 시 전 필드 `escapeHtml` 필수(8번에 반영).
- 정렬 화이트리스트로 임의 property 정렬(경로 주입) 차단, size clamp로 과대 페이지 DoS 억제. GET 전용이라
  CSRF 무관. 감사 민감 필드(errorMessage 등)를 최고권한 ADMIN에만 노출 — 수용된 트레이드오프.

**성능/운영**
- 복합 인덱스 `(create_at, id)`가 기본 정렬·기간 필터를 함께 커버. COUNT는 기간 상한(≤3개월)으로 스캔
  범위가 묶임. `actionUserId contains`(선두 LIKE)는 인덱스 미활용이나 기간 필터로 후보 집합이 제한됨.
- append-only·연관관계 없음 → N+1·지연로딩 없음. errorMessage(≤500자) 포함 목록 페이로드는 size≤100에서
  수십 KB 수준, 모달 재사용을 위한 수용된 비용.
- 대용량 깊은 페이지(높은 page 번호)의 OFFSET 비용은 기간 상한으로 제한. 커서 페이징은 미채택(향후 분리).
- prod 인덱스 추가 시 온라인 DDL로 감사 쓰기 잠금 회피(6번에 반영).

## 테스트

> 슬라이스/단위 우선(CLAUDE.md). `@DataJpaTest`는 H2/`src/test/resources` 부재로 불가하므로 실제 쿼리
> 결과(`between`/`contains`/`eq`)는 playwright 수동검증으로 보완 — 인프라 보강은 별도 작업(B-1).

- **`AdminActionTypeSyncTest`** — `ClassPathScanningCandidateComponentProvider`로 `com.cms` 메인
  클래스패스 스캔 → `@AdminActionLogged` 메서드의 `actionType` 값이 전부 `AdminActionTypes.ALL`에
  포함되는지 검증. Spring 컨텍스트·DB 불필요. 테스트 전용 `TEST_ACTION`은 메인 클래스패스 밖이라 제외.
  - `모든_어노테이션_actionType이_상수목록에_포함됨()`

- **`AdminActionLogRepositoryImplSortTest`** — `MemberRepositoryImplSortTest` 패턴(DB 없이
  `toOrderSpecifiers` 단위 검증).
  - `화이트리스트_없는_필드는_무시된다()`
  - `유효_정렬_없으면_createAt_desc_id_desc_기본정렬()`
  - `createAt_정렬_뒤에_id_desc_tiebreaker가_붙는다()`
  - `actionResult_enum은_정렬에서_무시된다()`

- **`AdminActionLogControllerTest`** — `@WebMvcTest(controllers = AdminActionLogController.class)` +
  `@Import({MockConfig, MethodSecurityTestConfig, GlobalApiExceptionHandler})`(member 패턴 동일).
  - `미인증이면_401()`
  - `ROLE_MANAGER면_403_ACCESS_DENIED()` (hasRole('ADMIN') 위반 — JSON 403 확인)
  - `ROLE_ADMIN이면_200이고_서비스에_위임된다()`
  - `검색_파라미터가_DTO에_바인딩되어_서비스로_전달된다()` (actionUserId/actionType/actionResult/from/to 캡처)
  - `기간_역전이면_400_VALIDATION_ERROR()` (DTO `@AssertTrue`)
  - `기간_3개월_초과면_400_VALIDATION_ERROR()` (DTO `@AssertTrue`)
  - `기간_정확히_3개월이면_200()` (DTO `@AssertTrue` 경계 통과)
  - `기간_미지정이면_200()` (서비스 mock — 기본값 주입은 서비스 책임)

- **`AdminActionLogQueryServiceTest`** — Mockito(repository mock).
  - `from_to_둘다_null이면_최근_30일을_주입한다()`
  - `to만_지정되면_from을_to_minus29로_보정한다()`
  - `from만_지정되면_to를_from_plus29로_보정한다()`
  - `from_to_둘다_지정되면_그대로_사용한다()`
  - `size_100초과면_100으로_clamp한다()` / `size_100이하면_유지한다()`
  - `엔티티가_응답DTO로_매핑된다()` (actionId 포함 전 필드 매핑)
  - (역전·3개월 초과 검증은 DTO로 이동 → 여기서 검증하지 않음, 결정 3.)

## 검증 (Verification)

1. `./gradlew compileJava` (Q클래스) → `./gradlew test` 통과.
   - `AdminActionTypeSyncTest`가 어노테이션 값과 `AdminActionTypes.ALL` 동기화 검증.
   - `AdminActionLogControllerTest`: 기간 역전·3개월 초과 400 `VALIDATION_ERROR`, 기간 미지정 200(기본 30일) 통과.
   - `AdminActionLogQueryServiceTest`: 기간 기본값/한쪽 보정, size clamp(예 100000→100) 통과.
2. **인덱스 적용** — `docs/migration/` SQL을 dev DB에 수동 적용하고 `SHOW INDEX FROM admin_action_log;`로
   `idx_log_create_at_id`가 `(create_at[Seq 1], id[Seq 2])` 순서로 생성됐는지 확인(결정 1로 런타임
   검증기가 없으므로 이 수동 확인이 인덱스 적용 보증 수단).
3. `./gradlew bootRun`(dev) 후 playwright:
   - `admin`/`1234` 로그인 → 사이드바 "활동 로그" → `/admin/log/manage`.
   - 기간 기본값(최근 30일) 초기 렌더링 확인(from/to date input에 값 채워짐).
   - 로그 비어 있으면 내 비밀번호 변경으로 `PASSWORD_CHANGE` 로그 생성 후 재조회.
   - actionType 드롭다운(`AdminActionTypes.ALL`)·결과·기간 필터, 페이징, 행 클릭 시 상세 모달(추가
     네트워크 요청 없이 표시, `actionId` 표시 — null이면 "시스템") 확인.
   - 기간 간격 3개월 초과 지정 시 검증 오류 메시지 노출 확인.
   - 관리자 조회 화면(`/admin/member/manage`) 회귀 없음 스크린샷.
4. (prod 배포 시) `docs/migration/` SQL을 먼저 적용한 뒤 기동. 인덱스 누락은 기동 실패가 아니라 성능
   저하로만 나타나므로, 배포 체크리스트에 `SHOW INDEX` 확인 항목을 포함한다(결정 1).
