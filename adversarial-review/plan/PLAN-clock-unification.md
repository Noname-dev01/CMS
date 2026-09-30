# PLAN — 시각 원천 KST Clock 단일화 (`LocalDateTime.now()` 직접 호출 제거)

> 작성일: 2026-09-30
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "우선순위에서 밀린 감사 항목" 표 **M-05**
> (`AppConfig`의 KST `Clock`과 별개로 도메인·서비스가 `LocalDateTime.now()`를 직접 호출)

## 개정 이력

- v1 (2026-09-30): 최초 작성(정찰·설계 결과). `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-09-30, codex 리뷰 1차 반영 — needs-attention, 4개 지적 전부 수용, 높음 0):
  - **수용(중간1, UTC 재실행이 UP-TO-DATE로 생략될 수 있음)**: `build.gradle`이 `JAVA_TOOL_OPTIONS`를 테스트 입력으로 선언하지 않아 환경변수만 바꾼 두 번째 실행을 Gradle이 캐시로 건너뛸 수 있다는 지적 — Gradle 입력 추적 계약상 타당. 결정 6·완료 기준에서 두 조건 모두 `cleanTest test`(또는 `--rerun-tasks`)로 강제 실행하고, UTC 실행의 출력에 `Picked up JAVA_TOOL_OPTIONS: -Duser.timezone=UTC` 배너와 실행 테스트 수(스킵 제외 815 부근)를 확인한 뒤에만 통과로 기록한다("BUILD SUCCESSFUL"만으로 완료 처리 금지). `build.gradle` 자체는 수정하지 않는다(무관한 변경).
  - **수용(중간2, 감사 로그 날짜 테스트의 시스템 시계 의존)**: `AdminActionLogQueryServiceTest:55`가 `LocalDate.now()`로 기대값을 계산하고 서비스를 `new AdminActionLogQueryService(repository)`로 직접 생성함을 코드로 확인 — 서비스만 KST Clock으로 바꾸면 UTC JVM에서 KST 00:00~08:59에 실패하고 컴파일로는 발견되지 않는다. 변경 대상 테스트에 추가하고, 고정 Clock `2026-09-29T15:00:00Z`(= KST 2026-09-30) 경계 케이스를 조회 서비스·페이지 컨트롤러 기본값 양쪽에 신규 테스트로 넣는다.
  - **수용(낮음3, 컨트롤러의 `now()` 2회 호출)**: `AdminActionLogPageController`가 `defaultFrom`/`defaultTo`를 각각 별도 호출로 계산해 자정 경계에서 31일이 될 수 있음 — 기존 결함이지만 이번 변경 줄에 직접 해당하므로 `LocalDate today = LocalDate.now(clock)`를 1회 산출해 재사용(결정 3의 단일 산출 규칙을 컨트롤러에 적용).
  - **수용(낮음4, 컨벤션 테스트의 보장 범위)**: 결정 4의 `now(AppConfig.KST)`와 인자 없는 호출만 검사하는 결정 5가 "주입 Clock 사용"을 보장하지 못한다는 지적 — 타당. 결정 5의 검사를 "허용 목록 외 `.now(`는 인자가 `clock` 또는 `AppConfig.KST`인 경우만 통과, `Clock.systemDefaultZone()`·`systemUTC()`·`system(` 호출 금지"로 강화하고, `AppConfig.KST` 사용은 `ApiErrorResponse` 1곳만 **명시적 예외**로 허용 목록에 둔다. 문서·완료 기준에서 "시간대 통일(ApiErrorResponse)"과 "주입 Clock 사용(저장·조회 코드)"의 보장 범위를 구분해 표기한다.

- v3 (2026-09-30, codex 리뷰 2차 — **ship**, 추가 실질 지적 0건): 본문 변경 없음. 리뷰어가 시각 호출부·회원 수정·비밀번호 재설정·메뉴 잠금·감사 로그·`AppConfig`·Gradle 테스트 설정을 직접 확인했고 트랜잭션·잠금·토큰 만료 계약 변경 필요 없음, 예외·범위 제외 타당하다고 판정.

## Context

`AppConfig`가 `Clock.system(Asia/Seoul)` 빈을 제공하고 `DashboardService`·`LoginFailureService`·`PasswordExpiryService`·`AdminMemberService`·`PasswordResetService`·`VisitLoggingAuthenticationSuccessHandler` 등은 이미 이를 쓴다. 그러나 아래 지점은 JVM 기본 시간대에 의존하는 `now()`를 직접 호출한다.

| # | 위치 | 호출 |
|---|---|---|
| 1 | `Member` (`updateInfo`·`issueResetToken`·`clearResetToken`·`changeRole`·`changeStatus`) | `LocalDateTime.now()` 5곳 |
| 2 | `Notice` (`update`·`softDelete`) | 2곳 |
| 3 | `Menu` (`update`·`deactivate`) | 2곳 |
| 4 | `NoticeService.createNotice` | 1곳 |
| 5 | `MenuService.createMenu` | 1곳 |
| 6 | `NoticeAttachmentService.upload` | 1곳 (`createDate`) |
| 7 | `AdminActionLogService.log` | 1곳 (`createAt`) |
| 8 | `ProfileImageMigrationRunner` | 1곳 (`resetProfileImage`) |
| 9 | `ApiErrorResponse.of` | 1곳 (응답 `timestamp`) |
| 10 | `AdminActionLogQueryService.applyDateDefaults`·`AdminActionLogPageController` | `LocalDate.now()` 3곳 (감사 로그 조회 기본 기간) |

### 정찰로 확정한 사실 (로드맵 서술의 보정)

- **운영 경로에서는 이미 KST다.** `CmsApplication.main()`이 `TimeZone.setDefault(Asia/Seoul)`을 강제하므로 `bootRun`·`java -jar` 경로의 `LocalDateTime.now()`는 KST 벽시계다(로드맵의 철회된 M-06과 같은 사실). "JVM이 UTC면 9시간 어긋남"은 **`main()`을 거치지 않는 JVM** — `@SpringBootTest`/슬라이스 테스트(CI Linux는 UTC) — 에서만 성립한다.
- 따라서 이 작업의 성격은 **"현재 운영 버그 수정"이 아니라 "시각 원천 이원화 제거(방어적 정합성)"** 이다. 같은 행 안에서 `createDate`는 Clock, `updateDate`는 `now()`처럼 두 원천이 섞여 있는 구조가 `main()`의 전역 기본 시간대 고정 한 줄에만 의존하고 있다. 실제 전례: `docs/troubleshooting.md` "시스템 TZ와 KST Clock 혼용으로 CI에서만 실패"(498행 부근).
- **스키마·데이터 이동 없음.** 저장 타입은 `LocalDateTime`(시간대 없는 DATETIME)이고 운영 DB의 기존 값은 이미 KST 벽시계라 값의 의미가 변하지 않는다.
- `Member`는 이미 `changePresetProfileImage(url, now)`·`resetProfileImage(now)`·`lockAt`/비밀번호 변경 계열이 **`now` 파라미터를 받는 패턴**을 쓴다(엔티티는 시계를 모르고 서비스가 `LocalDateTime.now(clock)`을 전달).
- `Member.clearResetToken()`은 **main·test 어디서도 호출되지 않는다**(죽은 코드). 그러나 `now()`를 포함하므로 컨벤션 테스트(결정 5)에 걸린다.
- DB 측 시각: Flyway `V3__seed_default_menus.sql`·`V9__seed_notice_menu.sql`이 시드 행의 `create_date`/`update_date`에 DB `NOW()`를 쓴다 — 머지된 마이그레이션이라 수정 금지(체크섬), 1회성 시드이므로 범위 밖.
- `LocalDiskFileStorage:332`의 `LocalDate.now()`는 저장 디렉터리 샤딩 이름일 뿐 어떤 시각 비교·저장 의미도 없다.

## 설계 결정

### 결정 1 — 범위

- **포함**: 위 표의 1~10 (요청 14곳 + `LocalDate.now()` 2개 파일).
- **10 포함 이유**: 감사 로그의 저장 시각(`createAt`, 7번)을 Clock으로 바꾸면서 같은 도메인의 "조회 기본 기간"이 기본 시간대를 쓰면 시간원이 갈라진다(UTC JVM에서 기본 조회 구간이 하루 어긋남).
- **제외**: `LocalDiskFileStorage` 디렉터리 샤딩(의미 없음, `FileStorage`에 Clock을 넣는 것은 무관한 변경), Flyway 시드 `NOW()`(수정 금지·1회성).

### 결정 2 — 엔티티는 시각을 파라미터로 받는다 (Clock 주입 없음)

| 선택지 | 평가 |
|---|---|
| **A. 도메인 메서드에 `LocalDateTime now` 파라미터 추가** | 기존 `Member` 패턴과 일치, 엔티티가 시계를 모름, 테스트에서 시각 고정이 자명 — **채택** |
| B. 엔티티에 Clock 보유 | JPA 엔티티는 프레임워크가 생성해 주입 불가 — 기각 |
| C. 정적 시간 제공자(`TimeProvider.now()`) | 전역 가변 상태, 테스트 격리 악화, 이번 컨벤션의 반대 방향 — 기각 |

시그니처 변경: `Member.updateInfo(name, email, now)`·`issueResetToken(hash, expiryAt, now)`·`clearResetToken(now)`·`changeRole(role, now)`·`changeStatus(status, now)`, `Notice.update(title, content, useYn, now)`·`softDelete(now)`, `Menu.update(..., now)`·`deactivate(now)`. `clearResetToken`은 죽은 코드지만 삭제는 "무관한 정리"이므로 시그니처만 맞춘다(삭제 여부는 승인 시 별도 확인).

### 결정 3 — 서비스는 `private final Clock clock`을 주입받는다

`NoticeService`·`MenuService`·`NoticeAttachmentService`·`AdminActionLogService`·`ProfileImageMigrationRunner`에 `Clock` 필드 추가(`@RequiredArgsConstructor`, 프로젝트 DI 규약). `AdminMemberService`·`PasswordResetService`는 이미 보유하므로 호출부만 `now` 전달. 한 서비스 메서드 안에서는 `LocalDateTime now = LocalDateTime.now(clock)`을 1회 산출해 여러 필드에 동일 값을 쓴다(한 행의 `createDate == updateDate` 유지 — 현재 `NoticeService.createNotice`가 이미 이 방식).

### 결정 4 — `ApiErrorResponse`는 Clock 주입 대신 KST `ZoneId` 상수를 공유한다

| 선택지 | 평가 |
|---|---|
| A. 5개 호출부(필터·핸들러·`SecurityConfig` static 상수)에 Clock 주입 | 응답 전용·비저장 값 하나 때문에 static 상수·`new`·테스트 직접 생성 지점 다수를 재배선 — 과설계 |
| **B. `AppConfig.KST`(`ZoneId`) 상수를 `AppConfig.clock()`과 `ApiErrorResponse`가 공유, `LocalDateTime.now(AppConfig.KST)`** | 시간대 정의의 단일 원천 확보, 호출부 무변경 — **채택** |
| C. 그대로 둔다 | 컨벤션 테스트의 예외가 되고 "단일 원천" 목표와 모순 |

트레이드오프: B는 이 값의 시각 고정 테스트가 불가하나, 현재 어떤 테스트도 timestamp 값을 단언하지 않는다(`jsonPath("$.timestamp").exists()`뿐).

### 결정 5 — 재발 방지: 소스 스캔 컨벤션 테스트 (신규 의존성 없음)

`src/main/java` 전체에서 `LocalDateTime`·`LocalDate`·`LocalTime`·`Instant`·`ZonedDateTime`·`OffsetDateTime`의 `.now(` 호출을 정규식으로 찾아 다음만 통과시킨다: 인자가 `clock`인 호출, 그리고 **명시적 허용 목록** — `LocalDiskFileStorage`(인자 없는 `LocalDate.now()`, 디렉터리 샤딩)·`ApiErrorResponse`(`now(AppConfig.KST)`). 또한 `Clock.systemDefaultZone()`·`Clock.systemUTC()`·`Clock.system(` 호출은 `AppConfig` 외 금지. 보장 범위: 저장·조회 코드는 **주입된 Clock 사용**, `ApiErrorResponse`만 **시간대 통일**(결정 4). `AdminPageAnnotationConventionTest`처럼 "컨벤션을 테스트로 강제"하는 프로젝트 관례를 따른다. ArchUnit 도입은 신규 의존성이라 기각(CLAUDE.md: 신규 의존성은 사전 제안).

### 결정 6 — 검증 전략

1. 서비스·도메인 단위 테스트: **`Clock.fixed(과거 고정 시각, KST)`** 를 주입해 저장 시각이 고정 시각과 정확히 같음을 단언(시스템 시각과 다르므로 원천 혼용을 구별해 잡는다).
2. 전체 테스트를 두 번 실행: 기본 + **`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`**(기존 troubleshooting 검증 방식). UTC 실행이 이번 변경의 핵심 증명이다. **매번 `cleanTest test`로 강제 실행**(환경변수는 Gradle 태스크 입력이 아니라 UP-TO-DATE로 생략될 수 있음)하고, UTC 출력의 `Picked up JAVA_TOOL_OPTIONS` 배너·실행 테스트 수를 확인한다.
2-1. 감사 로그 기본 기간: 고정 Clock `2026-09-29T15:00:00Z`(KST 09-30, UTC 09-29)에서 조회 서비스·페이지 컨트롤러가 KST 날짜(`to=2026-09-30`, `from=2026-09-01`)를 쓰는지 단언하는 경계 테스트 신규(기존 `AdminActionLogQueryServiceTest:55`의 `LocalDate.now()` 기대값도 고정 Clock 기반으로 교체).
3. 실기: `bootRun` KST 경로에서 공지·메뉴·관리자 수정 후 DB 값이 KST 벽시계와 일치, 감사 로그 화면 기본 기간 회귀 없음.
4. 컨벤션 테스트는 변이 실험(`now()` 1곳 복원 → 실패 확인 → 원복)으로 실제로 잡는지 확인.

## 변경 파일

- 수정(main): `Member`·`Notice`·`Menu`(도메인), `NoticeService`·`MenuService`·`NoticeAttachmentService`·`AdminMemberService`·`PasswordResetService`(호출부/Clock), `AdminActionLogService`·`ProfileImageMigrationRunner`, `ApiErrorResponse`·`AppConfig`, `AdminActionLogQueryService`·`AdminActionLogPageController`(`LocalDate.now(clock)`).
- 신규(test): `ClockUsageConventionTest`.
- 수정(test): 시그니처·생성자 변경 영향 파일 — `NoticeServiceTest`·`NoticeAttachmentServiceTest`·`MenuServiceTest`·`AdminActionLogServiceTest`·`ProfileImageMigrationRunnerTest`·`ProfileImageMigrationRunnerIntegrationTest`·`AdminActionLogQueryServiceTest`(`new`로 직접 생성·`LocalDate.now()` 기대값)·`MemberLockoutTest`·`LoginFailureServiceTest`·`PasswordResetServiceTest`·`AdminMemberEmailResetTokenConcurrencyIntegrationTest`·`AdminMemberUpdateConcurrencyIntegrationTest`·`NoticeConcurrencyIntegrationTest`(정확한 목록은 컴파일 결과로 확정).
- 문서: `CLAUDE.md`(시각 원천 규약 1줄), 필요 시 패키지 `CLAUDE.md`.
- 스키마·마이그레이션·`SecurityConfig` 인가: **변경 없음**.

## 단계별 작업 순서

1. 브랜치 `refactor/unify-clock-source`.
2. 도메인 시그니처 변경(`Member`·`Notice`·`Menu`) → 컴파일 오류로 호출부 목록 확정.
3. 서비스 Clock 주입·호출부 전달, `AdminActionLogService`·`ProfileImageMigrationRunner`.
4. `ApiErrorResponse`·`AppConfig.KST`, `LocalDate.now(clock)` 2곳.
5. 테스트 갱신 + 신규 고정 Clock 단언 + 컨벤션 테스트.
6. 전체 테스트(기본 / UTC 강제) → 변이 실험 → 실기 검증 → 문서.

## 완료 기준

- [ ] `src/main/java`의 저장·조회 코드는 허용 목록 외 전부 주입 `clock` 사용(컨벤션 테스트 통과) — 명시적 예외는 `LocalDiskFileStorage`(샤딩)·`ApiErrorResponse`(`AppConfig.KST` 시간대 통일만) 2곳.
- [ ] 감사 로그 조회 기본 기간이 고정 Clock 경계(UTC 09-29 15:00 = KST 09-30)에서 KST 날짜를 쓴다(서비스·컨트롤러 각 1개 이상).
- [ ] UTC 강제 실행이 `cleanTest`로 실제 실행됐고 `Picked up JAVA_TOOL_OPTIONS` 배너를 확인했다.
- [ ] 고정 KST Clock 주입 시 `Member`·`Notice`·`Menu`·`NoticeAttachment`·`AdminActionLog`의 저장 시각이 고정값과 정확히 일치(각 1개 이상 테스트).
- [ ] `./gradlew test` 전체 통과 — 기본 시간대와 `-Duser.timezone=UTC` 두 조건 모두.
- [ ] 컨벤션 테스트 변이 실험에서 실제로 실패함을 확인.
- [ ] `bootRun` 실기: 공지 생성·수정·삭제 / 메뉴 수정 / 관리자 수정 후 DB 시각이 KST 벽시계와 일치, 활동 로그 화면 정상.
- [ ] 스키마·인가 정책 변경 0건.

## 리스크 / 범위 밖

- **`@InjectMocks` NPE**: 서비스 생성자에 `Clock`이 추가되면 mock 미등록 테스트가 null 주입으로 NPE. 대응: 해당 테스트에 `Clock` 필드 명시 등록.
- **관측상 동작 변화 없음(운영)**: `main()`이 이미 KST라 운영 경로의 저장 값은 변하지 않는다 — 이 작업의 가치는 테스트 JVM·향후 진입점 변경에 대한 방어이며 운영 동작 개선을 주장하지 않는다.
- **`AppConfig.KST`와 `main()`의 `TimeZone.setDefault`는 여전히 별개 선언**이다(후자를 상수로 바꾸는 것은 이번 범위 밖 — 한 줄 중복은 수용).
- **`ApiErrorResponse` timestamp는 시각 고정 테스트 불가**(결정 4 트레이드오프).
- **Flyway 시드 `NOW()`**: DB 세션 시간대 의존이나 머지된 1회성 시드라 수정하지 않는다.
- `clearResetToken()` 죽은 코드 정리는 별도 작업.

## 구현·검증 결과 (2026-09-30)

### 핵심 확정 사항

- 계획 v3(codex 리뷰 2라운드: 1차 needs-attention 4건 전부 수용 → 2차 ship)대로 구현했고, 승인 시 `Member.clearResetToken()`은 삭제하지 않고 `now` 파라미터만 추가하기로 확정됨(호출처 0건인 죽은 코드).
- **운영 동작은 변하지 않는다**: `CmsApplication.main()`이 JVM 기본 시간대를 KST로 고정하므로 운영·`bootRun` 저장값은 원래 KST였다. 이 작업의 가치는 `main()`을 거치지 않는 JVM(테스트, CI Linux=UTC)에서 시각 원천이 갈라지는 것을 막는 방어다. 로드맵의 "JVM이 UTC면 9시간 어긋남"은 운영 경로에서는 성립하지 않는다.
- 스키마·마이그레이션·인가 정책·신규 의존성 변경 없음.

### 구현 파일

- main: `Member`(5개 메서드에 `now` 파라미터)·`Notice`(2)·`Menu`(2) 시그니처, `AdminMemberService`(`updateAdminMember`는 `now` 1회 산출 후 info/role/status에 동일 값)·`PasswordResetService`·`NoticeService`·`MenuService`·`NoticeAttachmentService`·`AdminActionLogService`·`ProfileImageMigrationRunner`·`AdminActionLogQueryService`·`AdminActionLogPageController`(`LocalDate today` 1회 산출)에 `Clock` 사용, `ApiErrorResponse`+`AppConfig.KST`(시간대 상수 공유).
- 신규 test: `ClockUsageConventionTest`(소스 스캔 — 허용 목록 `LocalDiskFileStorage`·`ApiErrorResponse` 2건), `AdminActionLogPageControllerTest`, `MemberTimestampTest`. 기존 테스트 16개 파일 갱신(시그니처·생성자 변경, `@Spy Clock`, 고정 Clock 단언, 경계 케이스 UTC 2026-09-29 15:00 = KST 2026-09-30).
- 문서: 루트 `CLAUDE.md`에 "시각 원천은 KST Clock 하나" 규약 추가.

### 검증 결과

- 전체 `./gradlew test --rerun`: **기본 시간대 821개 통과(실패·에러 0, 스킵 3 — 기존 Windows 심볼릭 링크)**, **`JAVA_TOOL_OPTIONS=-Duser.timezone=UTC` 강제 실행도 821개 통과**(`Picked up JAVA_TOOL_OPTIONS: -Duser.timezone=UTC` 배너 확인, `--rerun`으로 UP-TO-DATE 생략 방지).
- 변이 실험: `NoticeService`의 `LocalDateTime.now(clock)`을 `LocalDateTime.now()`로 되돌리자 `ClockUsageConventionTest`와 `NoticeServiceTest`(고정 Clock 단언)가 둘 다 실패 → 원복 후 통과.
- 실기(`bootRun --server.port=8099`, dev DB, DB 시계=UTC): 공지 생성·수정·삭제, 첨부 업로드, 메뉴 생성·수정·비활성화, 회원 생성·역할/상태 수정 후 DB의 `create_date`/`update_date`/`create_at`이 모두 KST 벽시계(15:27~15:28)와 일치(DB `now()`는 06:28), 활동 로그 화면 기본 기간 `from=2026-09-01`·`to=2026-09-30`. 검증용 행은 삭제해 member 5·notice 9로 원복(감사 로그 행 일부는 남김).

### 이슈

- **테스트 파급으로 발견된 시각 혼용 사례**: `AdminMemberServiceTest.updateMyInfo_success`는 픽스처를 시스템 `now()`로, 서비스는 고정 Clock(2026-07-17)으로 만들어 이전엔 서비스도 `now()`라 우연히 통과했다 — 정확한 값 단언으로 교체. `AdminSidebarAdviceTest`(`@WebMvcTest`)는 `AdminActionLogPageController`가 `Clock`을 요구해 컨텍스트 로딩이 실패 → 테스트 설정에 `Clock` 빈 추가.
- `cleanTest`는 이 환경에서 `build/test-results/test`를 삭제하지 못해(작업 디렉터리 점유) 실패했다 — 코드 문제 아님, `test --rerun`으로 대체.
- 관찰(이번 변경과 무관, 미수정): `ApiAuthenticationEntryPoint`의 401 응답 `timestamp`가 `[2026,9,30,15,...]` 배열로 직렬화되고 핸들러 응답은 문자열이다(별도 `ObjectMapper` 사용 추정) — 값(KST 15시)은 정확.

### 후속

- Flyway 시드 `V3`·`V9`의 DB `NOW()`는 머지된 마이그레이션이라 미수정(범위 밖).
- `Member.clearResetToken()` 죽은 코드 정리, 401 `timestamp` 직렬화 형식 통일은 별도 작업.
- `TimeZone.setDefault`(`main()`)와 `AppConfig.KST`는 여전히 별개 선언(한 줄 중복 수용).
