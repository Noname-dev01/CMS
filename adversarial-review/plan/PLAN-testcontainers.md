# PLAN-testcontainers — Testcontainers 전환 (테스트 DB 격리)

> 로드맵: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 5 (2026-07-20 재선정)" ④

## 개정 이력

- v2 변경 (리뷰 1차 반영, codex CLI):
  - **수용 #1** — "컨테이너를 UTC로 두면 로컬에서도 CI 시간 함정이 재현된다"는 주장이 근거 오류(`LocalDateTime.now()`는 DB가 아니라 테스트 JVM 타임존을 씀)로 확인되어 삭제. 시간대 통일은 이 작업 범위 밖으로 명시.
  - **수용 #2** — 완료 기준 "실행당 컨테이너 1개"가 Gradle `maxParallelForks`/`forkEvery` 기본값에 암묵 의존하던 것을 `build.gradle`에 `maxParallelForks = 1` 명시 + 문구를 "테스트 JVM당 컨테이너 1개"로 정정.
  - **목표 명확화 후 수용 #3** — "상태 오염 제거"가 "실행 간(런 투 런)" 오염 제거이지 "같은 실행 내 클래스 간" 오염 제거가 아님을 목표 절에 명시. 완료 기준에 연속 2회 실행 검증 추가(클래스 실행 순서는 JUnit 5가 기본적으로 비결정적이라 별도 조치 불필요).
  - **수용 #4** — `TestMemberLoader` 시드 영향 없음의 근거였던 "`AdminMemberUpdateConcurrencyIntegrationTest` 통과"가 시드 자체를 검증하지 않는다는 지적을 받아, 완료 기준에 "`admin` 계정 정확히 1건 존재" 직접 확인 추가.
  - **수용 #5** — mail 더미 값이 username/password만 채워 mock 누락 시 실제 `smtp.gmail.com` 연결을 시도할 수 있다는 지적을 받아, `@DynamicPropertySource`에 `spring.mail.host=localhost`+미사용 포트를 추가해 즉시 connection refused로 fail-fast.
  - **기각 #6** — "`mariadb:10.11` 태그 고정"을 digest 고정 미비로 지적했으나, 이 작업 범위는 "dev·CI와 동일 표기 사용"이지 신규 재현성 관행 도입이 아님. 설명 문구만 "동일 태그 사용(digest 고정 아님)"으로 정확히 수정.
  - **수용 #7** — 컴파일 검증 단계의 `./gradlew compileJava`가 test 소스를 컴파일하지 않는다는 지적을 받아 `compileTestJava`로 정정. `VisitLogRepositoryDataJpaTest`의 낡은 "ddl-auto:update" 테스트명도 전환 시 함께 정리 대상에 추가.
- v3 변경 (리뷰 2차 반영, codex CLI):
  - **수용 #1** — "연속 2회 실행" 완료 기준이 Gradle up-to-date 캐시로 두 번째 실행이 스킵될 수 있어 실행 간 격리를 증명하지 못한다는 지적을 받아, `./gradlew cleanTest test`로 명시.
  - **수용 #2** — `maxParallelForks = 1`만으로는 "테스트 JVM당 1개"를 보장 못 하고(동시 실행 수만 제한) `forkEvery`가 바뀌면 순차적으로 여러 JVM이 생길 수 있다는 지적을 받아, `forkEvery = 0`도 함께 명시.
  - **범위 축소 후 수용 #3** — `TestMemberLoader` 검증에 "서로 다른 컨텍스트가 붙은 뒤 재확인" 시나리오를 요구했으나, 이 프로젝트의 `@DataJpaTest` 4개는 `@Import`로 최소 빈만 올리는 슬라이스라 `@Component`(`TestMemberLoader`)를 로드하지 않는다 — 로더가 도는 컨텍스트는 `@SpringBootTest(classes = CmsTestApplication.class)` 계열뿐이라 "여러 컨텍스트 간 중복 시드"는 애초에 `count() == 0` 가드로 발생 불가능한 시나리오. 검증 범위를 "전체 스위트 실행 후 `admin` 정확히 1건"으로 실용적으로 좁힘.
  - **수용 #4** — 성능 기준선 선측정이 리스크 절에만 언급되고 작업 단계 순서에는 없었다는 지적을 받아, 단계 0번(전환 착수 전 측정)으로 승격.
  - **수용 #5** — `compileTestJava` 실행 위치가 `MariaDbContainerSupport` 작성 **전**이라 `@ServiceConnection` 오류를 못 잡는다는 지적을 받아, 베이스 클래스 작성 **후**로 순서 이동.
  - **수용(사소) #6** — "51개 테스트 클래스"라는 표현이 부정확(`*Test.java`는 47개, 나머지가 전부 WebMvcTest/Mockito도 아님)하다는 지적을 받아 "51개 테스트 소스 파일(지원 클래스 포함)"로 정정.
- v4 변경 (리뷰 3차 반영, codex CLI):
  - **수용 #1** — "전체 스위트 실행 **후** `member` 테이블 조회"라는 완료 기준이 컨테이너 수명주기와 모순된다는 지적을 받아들임: 수동 singleton도 테스트 JVM 종료 시 Ryuk이 정리하므로 `./gradlew test` 반환 후에는 조회할 DB 자체가 없다. → 완료 기준을 "**테스트 코드 내부**에서 `admin` 1건 단언"으로 전환(`CmsApplicationTests`에 케이스 추가, 신규 클래스 생성 아님). 컨테이너 ID 확인도 `docker ps -a` 사후 확인 대신 `MariaDbContainerSupport` static 블록의 로그 출력으로 대체.
  - **수용 #2** — `TestMemberLoader`의 `count() == 0` 체크가 비원자적 check-then-act라 "여러 컨텍스트 중복 시드가 코드상 불가능"이라는 표현이 과장이라는 지적을 받아들임(`maxParallelForks`는 Gradle 프로세스 수만 제한, JUnit Jupiter 내부 병렬 실행과는 별개 계층). → "코드상 불가능" 표현을 삭제하고 "현재 JUnit 5 기본값(순차 실행, 병렬성 설정 없음)을 전제로 한다"는 제약으로 정정. 신규 `junit.jupiter.execution.parallel.*` 설정 도입은 이 작업 범위 밖(현재 프로젝트에 없던 설정을 새로 들이는 것은 범위 확장)이라 채택하지 않음.
  - **수용(사소) #3** — 목표 절에 "51개 테스트 클래스" 표현이 정찰 절 정정(리뷰 2차 #6) 이후에도 남아 있었다는 지적을 받아 통일.
- v5 변경 (리뷰 4차 반영, codex CLI):
  - **수용 #1** — "빌드 로그에서 컨테이너 ID 대조"가 `testLogging.showStandardStreams` 미설정 시 성공한 테스트의 표준 출력이 콘솔에 노출된다는 보장이 없다는 지적을 받아, `build.gradle`에 `testLogging { showStandardStreams = true }` 추가.
  - **수용 #2** — "서로 다른 컨테이너 ID = Ryuk 정리 성공"이 아니며, "종료 후 확인 불가"라는 설명도 부정확(기록한 ID로 `docker ps -a --filter id=<ID>` 조회 시 부재 확인 가능)하다는 지적을 받아, 리스크·완료 기준에 종료 후 부재 확인 절차를 추가.
  - **범위 축소 후 수용 #3** — v4의 "전체 스위트 실행 후 시드 검증"이 실제로는 `CmsApplicationTests` 실행 시점의 존재만 증명하고 다른 컨텍스트들이 모두 초기화된 후 상태는 증명하지 못한다는 지적을 받아, "전체 스위트"라는 과장된 표현을 "첫 `@SpringBootTest`(dev) 컨텍스트에서 시드 존재 확인"으로 정직하게 축소. 복수 컨텍스트 검증용 별도 테스트 추가는 채택하지 않음(이 계획의 검증 목적 대비 과한 신규 테스트 인프라).
  - **수용 #4** — 결정 1에서 `jdbc:tc:` URL 방식을 기각한 사유 중 "IDE 비-Gradle 러너 미동작"이 Testcontainers 공식 문서(JDBC URL은 일반 Java 앱·IDE에서도 클래스패스만 있으면 동작)와 배치되는 사실 오류라는 지적을 받아 삭제. 나머지 사유(수명주기 제어 명시성 부족, 자격증명 처리 문서 불확실)로 기각 결정은 유지.
  - **수용 #5** — 성능 기준선이 단일 측정값이라 Gradle 데몬 예열·Docker 부하 등 노이즈에 흔들릴 수 있다는 지적을 받아, 전환 전/후 각 3회 실행 후 중앙값 비교로 변경(첫 실행은 예열로 제외).
- v6 변경 (리뷰 5차 반영, codex CLI — plan-review-loop 5라운드 도달):
  - **수용 #1** — `CmsApplicationTests`의 시드 확인 단언이 "첫 `@SpringBootTest` 컨텍스트 로드 시점"을 증명한다는 표현이 과잉이라는 지적을 받아들임(JUnit 순차 실행은 동시 실행만 막을 뿐 클래스 순서를 보장하지 않으므로, 다른 클래스가 같은 컨텍스트를 먼저 캐시했을 수 있다). → "첫 컨텍스트" 표현을 삭제하고 "이 테스트 실행 시점에 `admin` 존재"로 검증 범위를 정직하게 재축소.
  - **수용 #2** — Ryuk 정리 확인이 JVM 종료와 실제 Docker 삭제 사이의 비동기 경쟁을 고려하지 않아 정상 정리 중인 컨테이너를 잔류로 오판할 수 있다는 지적을 받아, 즉시 1회 조회 대신 최대 30초·1초 간격 폴링 후 판정으로 변경.
  - **수용 #3** — "3회 실행, 첫 회 제외, 중앙값"이 실질적으로 2개 표본만 남겨 이상치에 취약하다는 지적을 받아, 4회 실행·첫 회 예열 제외·나머지 3회 중앙값으로 변경.

## 목표

테스트가 로컬 MariaDB(3307) 기동·환경변수 주입 없이 **`./gradlew test` 단독으로 도는 상태**를 만든다. 현재 51개 테스트 소스 파일 중 14개가 실제 MariaDB에 붙는데, 전제가 "개발자가 `make dev-db`로 컨테이너를 띄우고 `.env.dev`를 source하되 `DB_URL`은 unset한다"는 암묵적 로컬 의식이다. 이 전제가 깨졌을 때의 비용은 `docs/troubleshooting.md`(133행, "Flyway 이전 세대 로컬 dev DB에서 통합 테스트 전체가 컨텍스트 로드 실패")에 이미 기록돼 있다 — 스키마 드리프트 하나로 `ApplicationContext failure threshold` 도미노가 발생했다.

**해결할 문제 3가지**
1. **환경 의존** — DB 기동 + `DB_PASS`/`MAIL_USER`/`MAIL_PASS` 없이는 테스트 실행 불가.
2. **상태 오염** — 공유 dev DB에 테스트 데이터가 누적. 현재는 "공유 dev DB — `deleteAll()` 금지" 주석 + 대상 한정 `@AfterEach` 정리로 증상만 방어.
3. **이중 구성** — 로컬(`docker-compose.dev.yml` 3307 컨테이너)과 CI(`ci.yml` service container)가 같은 목적에 두 벌 설정.

**범위 밖**: 스키마 변경 없음. 인가 정책 변경 없음. `bootRun`·`docker-compose.dev.yml` 등 런타임 경로 무변경 — `make dev-db`는 애플리케이션 실행용으로 그대로 남는다. **테스트 JVM의 시간대(타임존) 통일도 범위 밖** — 이 작업이 없애는 "상태 오염"은 **실행 간(런 투 런)** 오염이다. **같은 실행 내 여러 테스트 클래스가 컨테이너 하나를 공유하며 발생하는 클래스 간 오염**은 기존에도 존재했고(공유 dev DB 시절과 동일 성질) 각 테스트의 대상 한정 `@AfterEach`가 이미 담당하는 책임이며 이 계획이 새로 만들거나 없애는 문제가 아니다.

## 정찰 결과 (실측)

### DB에 실제로 붙는 테스트 = 14개 클래스

**A. `@SpringBootTest(classes = CmsTestApplication.class)` — 10개** (`@ActiveProfiles` 미지정 → `application.yml`의 `${SPRING_PROFILES_ACTIVE:dev}`로 dev 활성)

`CmsApplicationTests` · `AdminMemberUpdateConcurrencyIntegrationTest` · `MenuConcurrencyIntegrationTest` · `PasswordResetConcurrencyIntegrationTest`(`classes = {CmsTestApplication, SyncMailExecutorConfig}`) · `PasswordExpiryIntegrationTest` · `NoticeConcurrencyIntegrationTest` · `AdminSessionRevocationIntegrationTest` · `NoticeAttachmentTransactionIntegrationTest` · `LoginFailureConcurrencyIntegrationTest` · `LoginFailureLockoutIntegrationTest`

**B. `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@ActiveProfiles("dev")` — 4개**

`VisitLogRepositoryDataJpaTest` · `PasswordExpiryServiceTest` · `LoginFailureServiceTest` · `NoticeRepositoryDataJpaTest`

나머지(리뷰 2차 #6 — "51개 테스트 클래스"는 부정확한 표현이었다. `src/test/java` 하위 Java 파일은 51개, 그중 `*Test.java` 명명은 47개이며 나머지는 지원 설정 클래스(`CmsTestApplication`, `TestStubController`, `MethodSecurityTestConfig` 등)다. DB 접속 클래스 14개를 제외한 나머지는 `@WebMvcTest` 슬라이스 또는 `@ExtendWith(MockitoExtension.class)` 순수 단위이거나 지원 클래스) → DB 불필요, 무변경.

### 환경 확인
- Docker Desktop 29.1.3 / linux-x86_64 / 12 CPU / 8GB — 가용. `cms-db-dev`(mariadb:10.11) 이미지 로컬 캐시됨.
- 마이그레이션 V1~V10 전부 존재. 빈 DB에서 전체 실행 경로가 이미 CI에서 검증됨.
- 테스트 클래스 중 `extends`/`implements`를 쓰는 것은 0개 → 베이스 클래스 도입에 상속 충돌 없음.
- `Clock` 빈은 `AppConfig`(프로파일 무관, KST 고정) → 컨테이너 도입과 무관.

### 정찰이 드러낸 숨은 요구사항
`application-dev.yml`의 `spring.mail.username`/`password`는 `${MAIL_USER}`/`${MAIL_PASS}`로 **기본값이 없다**. Testcontainers가 DB를 해결해도 이 둘이 없으면 `@SpringBootTest` 10개가 placeholder 미해결로 컨텍스트 로드 실패한다. "env 없이 `./gradlew test`" 목표 달성에 mail 더미 값 주입이 필수. (`DB_URL`·`DB_USER`·`APP_BASE_URL`은 yml 기본값이 있어 무관.)

## 설계 결정

| # | 쟁점 | 결정 | 왜 |
|---|------|------|-----|
| 1 | 컨테이너 배선 방식 | `com.cms.support.MariaDbContainerSupport`(abstract) 베이스 클래스 + `@ServiceConnection`, static 초기화 블록으로 직접 시작 (`@Testcontainers`/`@Container` 미사용) | Boot 3.5 공식 문서가 "static fields within interfaces or parent classes"를 지원 경로로 명시. `@Container` static 필드는 "stopped after the test class execution"이라 14개 클래스마다 재기동되어 전환 목적(속도)이 무너진다. 대안 검토: `jdbc:tc:` URL(컨테이너 수명주기가 드라이버 내부로 숨어 명시적 제어·`@ServiceConnection` 연동이 안 되고 자격증명 처리 방식이 문서로 불확실 — 리뷰 4차 #4: "IDE 미동작"은 사실과 다르므로 기각 사유에서 제외), `LauncherSessionListener` 전역 초기화(배선이 숨는 마법 + 단위 테스트에도 비용), `src/test/resources` yml 섀도잉(병합이 아닌 통째 대체라 설정 이중화) 모두 기각 |
| 2 | 컨테이너 재사용(`reuse.enable`) | 미사용 — 테스트 JVM(워커)당 새 컨테이너 1개 | 재사용은 반복 실행을 빠르게 하지만 데이터가 누적되어 이 작업이 없애려는 "상태 오염"이 되돌아온다 |
| 2-1 | 컨테이너 수 보장 | `build.gradle`의 `test` 태스크에 `maxParallelForks = 1` **+ `forkEvery = 0`** 명시 | static 필드는 "테스트 JVM당 1개"이지 "실행당 1개"가 아니다 — Gradle 기본값에 암묵 의존하면 향후 병렬화 설정이 들어올 때 조용히 컨테이너가 여러 개로 늘어난다(리뷰 1차 #2). `maxParallelForks`는 **동시 실행 프로세스 수**만 제한하고 `forkEvery`는 **한 프로세스가 처리할 테스트 클래스 수**를 제한한다 — 전자만 고정하면 `forkEvery`가 0이 아닌 값으로 바뀔 때 순차적으로 여러 JVM(=여러 컨테이너)이 생길 수 있어 **둘 다** 필요하다(리뷰 2차 #2, `maxParallelForks`만 고정한 v2의 미비 보완). 완료 기준 문구도 "테스트 JVM당 컨테이너 1개"로 정정 |
| 3 | 활성 프로파일 | `dev` 유지, 신규 `test` 프로파일 미도입 | dev의 `ddl-auto: validate`·mail 설정이 그대로 필요. 프로파일을 바꾸면 `TestMemberLoader`(`@Profile("dev")`) 등 dev 전용 빈 존재 여부가 바뀌어 검증 범위가 조용히 달라진다. 최소 diff — "DB가 어디 있는가"만 바꾼다 |
| 4 | CI service container | 제거. `services.mariadb` 블록 + `DB_URL`/`DB_USER`/`DB_PASS`/`MAIL_USER`/`MAIL_PASS`/`APP_BASE_URL` env 삭제 (`SPRING_PROFILES_ACTIVE: dev`만 유지) | 남겨두면 "테스트가 어느 DB에 붙는가"의 답이 두 개가 되어 이중 구성이 그대로 남는다. GitHub-hosted ubuntu 러너는 Docker 기본 제공 |
| 5 | 신규 의존성 | `spring-boot-testcontainers` + `org.testcontainers:mariadb` (버전 미지정, Boot 3.5.16 BOM 관리). 로드맵이 제안한 `org.testcontainers:junit-jupiter`는 **제외** | junit-jupiter 모듈은 `@Testcontainers`/`@Container` 확장을 제공하는데 결정 1에서 싱글턴 패턴을 택해 쓰지 않는다. 클래스패스에 올려두면 훗날 `@Container`가 잘못 붙어 컨테이너가 클래스마다 재기동되는 사고를 부른다 |
| 6 | Docker 미가용 폴백 | 없음 — Testcontainers 실패 메시지로 fail-fast | 프로젝트가 이미 `make dev-db`로 Docker를 전제. 폴백 경로는 테스트되지 않은 분기를 만들고 "환경 차이 제거"라는 목적과 어긋난다 |

### 설계 제약 (명문화)
- MariaDB 컨테이너 타임존은 설정하지 않는다(컨테이너 기본 UTC) — 현재 CI service container와 동일 조건. **주의**: `LocalDateTime.now()` 관련 시간대 함정(`docs/troubleshooting.md` 355행)은 DB 컨테이너가 아니라 **테스트 JVM의 기본 타임존**에 좌우된다(리뷰 1차 #1 — "컨테이너 UTC가 로컬에서도 CI 시간 함정을 재현시킨다"는 애초 주장은 근거 오류로 삭제). 테스트 JVM 타임존 통일은 이 작업 범위 밖.
- 이미지 태그 `mariadb:10.11`을 dev·CI와 **동일 표기로 사용**한다(리뷰 1차 #6 — 이것은 digest 고정이 아니다. `10.11`은 패치 릴리스가 갱신될 수 있는 가변 태그이며, 이 작업의 목적은 "다른 곳과 같은 표기를 쓴다"이지 "이미지 재현성을 강화한다"가 아니다. digest 고정은 범위 밖).
- 컨테이너는 **테스트 JVM(워커)당 1개**(결정 2-1의 `maxParallelForks = 1` + `forkEvery = 0`로 보장), 여러 Spring 컨텍스트가 공유. Flyway는 첫 컨텍스트에서 V1~V10 적용, 이후는 `flyway_schema_history` 기준 no-op.
- **범위 경계**: 이 계획은 "실행 간(런 투 런)" 상태 오염(반복 실행 시 이전 실행 데이터가 남는 문제)을 없앤다. 같은 실행 내 14개 클래스가 컨테이너 하나를 공유하며 생기는 클래스 간 상태 의존(예: `TestMemberLoader` 시드 시점)은 **기존에도 있던 성질**(공유 dev DB 시절과 동일)이며, 대상 한정 `@AfterEach`가 담당하는 기존 책임 그대로 유지한다(리뷰 1차 #3).
- **JUnit 실행 모델 전제**: `TestMemberLoader`의 `count() == 0` 체크는 조회 후 삽입하는 비원자적 check-then-act다. 이 계획은 현재 저장소에 JUnit 5 병렬 실행 설정(`junit.jupiter.execution.parallel.*`)이 **없다는 전제** 하에 "여러 `@SpringBootTest` 컨텍스트가 동시에 초기화되어 둘 다 `count()==0`을 읽는" 경합이 실제로는 발생하지 않는다고 본다 — "코드상 불가능"이 아니라 **"현재 순차 실행 기본값에서는 발생하지 않음"**이다(리뷰 3차 #2). `maxParallelForks=1`은 Gradle 프로세스 수만 제한할 뿐 JUnit 엔진 내부 병렬성과는 별개 계층이므로, 향후 JUnit 병렬 실행이 도입되면 이 전제가 깨진다는 점을 명시해 둔다. 신규 병렬성 설정 도입은 이 작업 범위 밖.

## 수정해야 할 정확한 파일

### 신규
| 파일 | 내용 |
|------|------|
| `src/test/java/com/cms/support/MariaDbContainerSupport.java` | `@ServiceConnection` MariaDB 컨테이너 + mail 더미 `@DynamicPropertySource`. DB 테스트 14개가 `extends` |

구현 스케치 (리뷰 1차 #5 반영 — mail host/port까지 명시해 mock 누락 시에도 실제 SMTP로 나가지 않게 함):

```java
package com.cms.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * DB에 실제로 접속하는 테스트 14개가 상속하는 베이스 클래스.
 * static 초기화 블록으로 컨테이너를 직접 시작한다(@Testcontainers/@Container 미사용) —
 * @Container static 필드는 "테스트 클래스 종료 후 정지"되므로 14개 클래스마다 재기동되어
 * 이 전환의 목적(속도)이 무너진다. 대신 JVM 생애주기 동안 살아있는 진짜 싱글턴으로 둔다.
 *
 * 테스트 JVM(워커)당 컨테이너 1개를 보장하려면 build.gradle의 maxParallelForks=1 + forkEvery=0이 함께 필요하다.
 */
public abstract class MariaDbContainerSupport {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(MariaDbContainerSupport.class);

    @ServiceConnection
    static final MariaDBContainer<?> MARIA_DB =
            new MariaDBContainer<>(DockerImageName.parse("mariadb:10.11"));

    static {
        MARIA_DB.start();
        // 컨테이너 ID를 시작 시점에 로그로 남긴다 — 테스트 JVM 종료 시 Ryuk이 컨테이너를
        // 정리하므로 사후에 DB(JDBC)로 확인하는 것은 불가능하다(리뷰 3차 #1).
        // 단, 기록해 둔 ID 자체는 종료 후에도 `docker ps -a --filter id=`로 조회 가능하다
        // (리뷰 4차 #2) — 이 로그는 (a) 실행마다 컨테이너 ID가 다른지 대조하고
        // (b) 종료 후 해당 ID가 실제로 제거됐는지 확인하는 두 검증의 근거가 된다.
        // build.gradle의 testLogging.showStandardStreams=true가 없으면 이 로그가
        // 빌드 콘솔에 노출되지 않는다(리뷰 4차 #1).
        log.info("MariaDB 테스트 컨테이너 시작: containerId={}", MARIA_DB.getContainerId());
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        // host/port까지 명시 — username/password만 채우면 JavaMailSender mock이 빠진
        // 테스트에서 실제 smtp.gmail.com:587로 나가 네트워크 타임아웃이라는 별도 실패 모드가
        // 생긴다(리뷰 1차 #5). localhost 미사용 포트로 돌려 즉시 connection refused로 fail-fast.
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> "1");
        registry.add("spring.mail.username", () -> "test@example.com");
        registry.add("spring.mail.password", () -> "test");
    }
}
```

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `build.gradle` | `testImplementation 'org.springframework.boot:spring-boot-testcontainers'`, `testImplementation 'org.testcontainers:mariadb'` 추가. `test { maxParallelForks = 1; forkEvery = 0; testLogging { showStandardStreams = true } }` 명시(`maxParallelForks`+`forkEvery`는 리뷰 1·2차 #2 — "테스트 JVM당 컨테이너 1개" 계약을 Gradle 기본값 암묵 의존이 아니라 명시적으로 고정. `testLogging.showStandardStreams`는 리뷰 4차 #1 — 이게 없으면 Gradle 기본 `LIFECYCLE` 로그 레벨에서 테스트 표준 출력이 콘솔에 노출되지 않아 컨테이너 ID 로그를 빌드 콘솔에서 대조할 수 없다) |
| DB 접속 테스트 14개 (위 A·B 목록) | `extends MariaDbContainerSupport` 추가. "로컬 실행: DB(make dev-db) 기동 + env 필요" 류 javadoc을 실제 동작(Docker만 필요)에 맞게 갱신. `VisitLogRepositoryDataJpaTest`의 낡은 "ddl-auto:update로 자동 생성 검증" 테스트명·설명도 실제 계약(Flyway + `validate`)에 맞게 정정(리뷰 1차 #7) |
| `src/test/java/com/cms/CmsApplicationTests.java` | `TestMemberLoader` 시드 검증 케이스 1개 추가: `memberRepository.findByUserId("admin")`이 정확히 1건 존재함을 컨텍스트 로드 **직후, 테스트 코드 내부**에서 단언(리뷰 3차 #1 — 컨테이너가 JVM 종료 시 회수되므로 "전체 스위트 실행 후 외부 조회"는 실행 불가능. 신규 클래스가 아니라 기존 스모크 테스트에 케이스 추가) |
| `.github/workflows/ci.yml` | `services.mariadb` 블록 삭제, env에서 DB/MAIL 관련 키 삭제(`SPRING_PROFILES_ACTIVE: dev`만 유지) |
| `CLAUDE.md` | 테스트 규칙·환경 설정 절의 "로컬 MariaDB 기동 필요" 전제를 Testcontainers 기반으로 갱신 |
| `docs/development-workflow.md` | 6단계 "환경 문제(DB 미기동, env 오염)" 진단 문구를 Docker 가용성 기준으로 갱신 |
| `docs/troubleshooting.md` | 133행 항목에 "Testcontainers 전환으로 해소" 후기 추가 |
| `adversarial-review/plan/README.md` | 인덱스에 본 계획 행 추가 |

## 단계별 작업 순서

0. **전환 전 성능 기준선 측정** (리뷰 2차 #4 — 작업 착수 전, 로컬 dev DB **기동 상태**에서 `./gradlew cleanTest test`를 **4회** 실행해 첫 회(예열)를 버리고 나머지 3회의 중앙값을 기준선으로 기록(리뷰 4·5차 #5·#3 — 3회 중 1회 제외는 표본 2개만 남아 중앙값이 이상치에 취약하므로 4회로 늘려 3개 표본 확보). 완료 기준의 "2배 이내" 비교 대상이 되므로 브랜치 생성·의존성 추가보다 먼저 수행)
1. `feat/testcontainers-test-db` 브랜치 생성
2. `build.gradle`에 의존성 2개 + `maxParallelForks = 1` + `forkEvery = 0` 추가
3. `MariaDbContainerSupport` 작성 (mail host/port 더미 포함) → `./gradlew compileTestJava`(리뷰 2차 #5 — 베이스 클래스 작성 **후**에 실행해야 `@ServiceConnection` 선언·제네릭·import 오류를 실제로 검증한다. 의존성 추가 직후 실행하는 v2 순서는 베이스 클래스가 아직 없어 검증 효과가 없었다)
4. 파일럿: `CmsApplicationTests`만 먼저 전환 → 로컬 dev DB를 **내린 상태**에서 단독 실행 통과 확인 (도미노 실패 방지)
5. 나머지 13개 전환 + javadoc 갱신 (`VisitLogRepositoryDataJpaTest` 낡은 테스트명 정정 포함)
6. CI 워크플로 정리
7. 문서 갱신(`CLAUDE.md`·`docs/development-workflow.md`·`docs/troubleshooting.md`·`plan/README.md`)

## 완료 기준

- 로컬 MariaDB를 **내린 상태**, `DB_PASS`/`MAIL_USER`/`MAIL_PASS` **환경변수 없이** `./gradlew test` 전체 통과
- **연속 2회** `./gradlew cleanTest test` 실행 모두 통과 + `MariaDbContainerSupport`가 `testLogging.showStandardStreams=true`로 콘솔에 남기는 컨테이너 ID가 두 실행에서 서로 다름을 빌드 콘솔 대조로 확인(리뷰 2·3·4차 #1 — 단순 재실행은 Gradle up-to-date 캐시로 스킵될 수 있어 `cleanTest`로 강제 재실행하고, `showStandardStreams` 없이는 성공한 테스트의 로그가 콘솔에 노출되지 않아 대조 자체가 불가능했다)
- 각 실행이 남긴 컨테이너 ID로 **JVM 종료 후** `docker ps -a --no-trunc --filter id=<기록한 ID>`를 **최대 30초, 1초 간격으로 폴링**해 빈 결과가 확인되면 성공, 시간 내 사라지지 않으면 Ryuk 정리 실패로 판정 — 실제 정리 완료의 직접 증거(리뷰 4·5차 #2 — "서로 다른 ID 관찰"은 정리 완료를 증명하지 않으며, JVM 종료와 실제 Docker 삭제 사이에는 비동기 지연이 있어 즉시 1회 조회는 정상 정리 **중**인 컨테이너를 오탐할 수 있다. "종료 후 확인 불가"라는 v4 표현도 부정확했음 — ID를 알면 Docker 메타데이터로 부재를 확인할 수 있다)
- **테스트 JVM(워커)당** 컨테이너 1개만 기동 확인(`maxParallelForks=1` + `forkEvery=0` 계약 실증, 위 콘솔 로그 기준) / 단위 테스트만 필터 실행 시 컨테이너 미기동 확인(지연 시작 실증)
- **`TestMemberLoader` 시드 확인**: `CmsApplicationTests`에 추가한 케이스가 **그 테스트 메서드 실행 시점**에 `admin` 계정 정확히 1건 존재를 단언하고 통과(리뷰 1·2·3·4·5차 — 검증 범위를 정직하게 명시: 이 단언은 "이 테스트가 실행되는 시점에 시드가 존재한다"만 증명하며, "첫 `@SpringBootTest` 컨텍스트 로드 직후"라는 특정 시점을 증명하지 않는다 — JUnit 5의 순차 실행은 동시 실행을 막을 뿐 클래스 실행 순서를 보장하지 않으므로 다른 클래스가 같은 컨텍스트를 먼저 캐시했을 수 있다(리뷰 5차 #1). `uk_member_user_id` 유니크 제약으로 "그 시점에 정확히 1건"은 보장됨). "여러 컨텍스트 간 중복 시드 시도"는 현재 JUnit 5 순차 실행 기본값(병렬성 설정 없음)을 전제로 검증 범위에서 제외 — `@DataJpaTest` 4개는 `@Import`로 최소 빈만 올리는 슬라이스라 `TestMemberLoader`(`@Component`)를 애초에 로드하지 않는다
- **전환 전(단계 0에서 측정한 기준선) 대비 전환 후** `./gradlew cleanTest test` 소요 시간 비교 — 각각 **4회** 실행해 **첫 회는 예열로 버리고 나머지 3회의 중앙값**으로 비교, 중앙값 기준 2배 이상 증가하지 않음(리뷰 4·5차 #5·#3 — 단일 측정값은 노이즈에 흔들리고, "3회 중 1회 제외"는 표본 2개만 남아 중앙값이 사실상 중간값이 되어 이상치에 취약. 4회로 늘려 예열 제외 후에도 3개 표본을 확보)
- `make dev-db` 복구 + `./gradlew bootRun` 정상 기동(런타임 무회귀)
- CI가 service container 없이 통과

Playwright 실기 검증은 해당 없음 — 프로덕션 코드·화면·API 무변경(테스트 인프라 전용 변경). `bootRun` 기동 확인으로 런타임 무회귀를 대체한다.

## 리스크

| 리스크 | 영향 | 대응 |
|--------|------|------|
| 14개 중 일부 누락한 채 CI service container 제거 | 해당 클래스가 CI에서 접속 실패 | 정찰로 14개 확정 목록 고정 + 로컬 DB 내린 상태 전체 실행으로 누락 강제 검출 |
| 컨테이너 기동 + 마이그레이션 적용으로 시간 증가 | 완료 기준(2배 이내) 미달 가능 | 전환 전 기준선 선측정, 초과 시 재사용 전략 재검토를 사용자에게 질문 |
| 빈 DB에서 `TestMemberLoader` 실제 시드 | 활성 ADMIN 수 전제 테스트 영향 | 결정 3에서 분석 완료, 완료 기준에 실측 포함 |
| Windows Docker Desktop Ryuk 정리 실패 | 컨테이너 잔류 | `MariaDbContainerSupport`가 콘솔에 남긴 컨테이너 ID를 기록해 두고, **JVM 종료 후** `docker ps -a --no-trunc --filter id=<ID>`로 부재를 확인(리뷰 4차 #2 — "종료 전 확인"이나 "종료 후 확인 불가"는 각각 정리 완료를 증명 못 하거나 부정확한 설명이었음). 잔류 시 troubleshooting 기록 |
| mail 더미 값이 host/port 없이 username/password만 채워지면 mock 누락 시 실제 SMTP로 연결 시도 | 네트워크 타임아웃으로 인한 불명확한 실패 | `MariaDbContainerSupport`에 `spring.mail.host=localhost`+미사용 포트 명시로 즉시 connection refused (리뷰 1차 #5 반영) |

## 착수 게이트

- 스키마 변경 없음 / 인가 정책 변경 없음
- **신규 의존성 2개 추가 → 사용자 승인 필요**(`spring-boot-testcontainers`, `org.testcontainers:mariadb`)
- CI 워크플로에서 MariaDB service container 제거

---

## 구현·검증 결과 (2026-07-27, `feat/testcontainers-test-db`)

### Context
5라운드 적대적 리뷰(codex CLI, v2~v6) 통과 후 사용자 승인을 받아 구현. 스키마·인가 정책 변경 없음. 신규 의존성 2개(`spring-boot-testcontainers`, `org.testcontainers:mariadb`) + CI service container 제거 승인 완료.

### 핵심 확정 사항
- `MariaDbContainerSupport`(abstract, `@ServiceConnection` + static 초기화 블록) — 계획서 구현 스케치 그대로 채택.
- `build.gradle` `test` 태스크에 `maxParallelForks=1` + `forkEvery=0` + `testLogging.showStandardStreams=true` 추가.
- DB 접속 테스트 14개(A그룹 10 + B그룹 4) 전부 `extends MariaDbContainerSupport` 전환 완료.
- `CmsApplicationTests`에 `TestMemberLoader` 시드 검증 케이스 추가(계획에서 정한 "그 테스트 메서드 실행 시점" 범위로 한정).
- `VisitLogRepositoryDataJpaTest`의 낡은 "ddl-auto:update" 테스트명·설명을 Flyway 기준으로 정정.
- `.github/workflows/ci.yml`에서 `services.mariadb` 블록 + DB/MAIL 관련 env 전부 제거(`SPRING_PROFILES_ACTIVE: dev`만 유지).

### 구현 파일
- 신규: `src/test/java/com/cms/support/MariaDbContainerSupport.java`
- 수정: `build.gradle`(의존성 2개 + test 태스크 설정), DB 접속 테스트 14개 클래스, `.github/workflows/ci.yml`, `CLAUDE.md`(테스트 규칙), `docs/development-workflow.md`(6단계 진단 문구), `docs/troubleshooting.md`(133행 후기 + 254행 참고 문구 정정), `adversarial-review/plan/README.md`(인덱스 행 추가)

### 검증 결과 (전부 실측, 로컬 Windows + Docker Desktop 29.1.3)
- **전환 전 기준선**: 로컬 dev DB 기동 상태에서 `cleanTest test` 4회(41/37/37/36초), 첫 회 예열 제외 중앙값 **37초**.
- **파일럿**(`CmsApplicationTests` 단독): 로컬 DB 내린 상태에서 통과 — Flyway V1~V10 빈 스키마 적용, `TestMemberLoader` 시드 확인 케이스 통과.
- **전체 스위트 연속 2회 실행** (로컬 DB 내린 상태, `DB_PASS`/`MAIL_USER`/`MAIL_PASS` 등 env 전부 unset): 1회차 BUILD SUCCESSFUL(50초, containerId=`3c3070eb...`), 2회차 BUILD SUCCESSFUL(49초, containerId=`cc337227...`) — **서로 다른 컨테이너 ID로 매 실행 새 컨테이너가 뜸을 확인**.
- **Ryuk 정리 확인**: 두 실행의 컨테이너 ID 모두 JVM 종료 후 30초 이내(실제 1초 이내) `docker ps -a --filter id=`에서 부재 확인.
- **지연 시작(lazy start) 확인**: `MenuServiceTest`(순수 Mockito 단위 테스트) 단독 실행 시 컨테이너 미기동, 4초 만에 완료.
- **전환 후 성능**: 4회 실행(51/50/49/51초), 첫 회 예열 제외 중앙값 **50초** — 기준선(37초) 대비 **1.35배**, 2배 이내 통과.
- **테스트 개수**: 474개 테스트, 48개 결과 파일 — 누락 없음.
- **`bootRun` 무회귀**: `make dev-db`로 로컬 DB 복구 후 `./gradlew bootRun` 정상 기동, `/actuator/health` 200 `{"status":"UP"}` 확인, Flyway "Schema is up to date" 확인.
- CI(GitHub Actions)는 이번 세션에서 실제 푸시로 검증하지 못함 — PR 생성 후 워크플로 실행 결과로 최종 확인 필요.

### 이슈
- 없음(계획서의 리스크 항목 전부 대응책대로 해소 확인).

### 후속
- CI 워크플로 실행 결과(PR 생성 후) 확인 필요 — 사용자 PR 생성 이후 GitHub Actions 로그로 재확인 권장.
- 프로필 이미지 Base64-in-DB → 파일 스토리지 이관 등 로드맵의 별도 미완료 항목과는 무관, 이 계획의 범위는 여기서 종료.
