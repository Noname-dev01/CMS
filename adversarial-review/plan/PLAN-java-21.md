# PLAN — Java 17 → 21 전환

> 상태: ✅ 완료 (2026-10-07 · #106 `b143f5a`) — v3 승인(적대적 리뷰 3라운드 ship) → 구현·검증 → 머지. 머지 후 IntelliJ 직접 실행 경로도 사용자 실행으로 확인(`corretto-21.0.9`, `/admin/login` 200) · 작성 2026-10-07
> 출처: 로드맵 "현재 상태 진단 > 공백 5(버전 부채)"·"선정에서 탈락한 후보"에 남은 마지막 항목(`/suggestRoadmap` 2026-10-07 선택)
> 유형: chore(빌드·런타임 상향) · 브랜치 `chore/java-21` · **스키마 변경 없음 · 인가 정책 변경 없음 · 신규 의존성 없음**

## 개정 이력

- v1 (2026-10-07): 최초 작성.
- v2 변경(1라운드 codex): (1) Java 17(Unicode 13) → 21(Unicode 15)의 대소문자 변환 차이를 쟁점 7로 신설했다. `EmailNormalizer`의 `toLowerCase(Locale.ROOT)`도 결과가 바뀜을 실측했다(U+10570: 17은 그대로, 21은 U+10597). 코드는 유지하고 비ASCII 이메일 데이터 점검을 검증에 추가했다. (2) 쟁점 3에 IntelliJ 직접 실행 경로(Project SDK·언어 수준·실행 JRE) 안내를 추가했다. (3) 쟁점 1의 근거를 "프로젝트 정책"으로 정정하고 #50 원인 단정을 뺐다(`docs/dependabot.md`가 추정이라고 명시). (4) prod-smoke는 종료 시 `down -v`로 스택을 지우므로 JVM 확인을 남은 이미지로 `docker run`하는 방식으로 바꿨다. (5) 로컬 Trivy 옵션을 CI와 맞추고(`--exit-code 1`·`--scanners vuln`·`--timeout 20m`) 도구 버전·이미지 ID를 기록하게 했다. (6) 쟁점 5의 실패 대응을 attach 실패 / 클래스 해석·변환 실패로 나눴다. (7) 정찰 표를 정정했다. 인자 없는 `toLowerCase()` 2곳(`NoticeAttachmentService:208`·`LocalDiskFileStorage:324`)이 있으며, JDK와 무관한 기존 로케일 의존이라 후속으로 기록했다. **기각**: 4곳의 메이저를 비교하는 CI 검사 추가 — 어긋남은 사람이 고칠 때만 생기고(Dependabot은 메이저 상향을 무시), 위험한 방향(런타임 < 컴파일 대상)은 prod-smoke 기동이 `UnsupportedClassVersionError`로 이미 잡으므로 과설계다.
- v3 변경(2라운드 codex): §6 운영 롤백에서 "DB와 무관" 단정을 제거했다. 21 운영 중 저장된 이메일이 17에서 다시 처리되는 역방향 경로가 있으므로, 롤백 직전에도 쟁점 7의 SQL로 점검한다. **기각**: §5에 "21 저장 → 17 전환 → 원문 재제출" 실기 시나리오 추가 — 최악의 영향이 재설정 토큰 삭제(재요청으로 회복)이고 실배포 DB가 없으며, 롤백 직전 SQL 점검으로 대상 행 유무를 판별할 수 있어 비용 대비 과하다.

## 1. Context

- 현재 빌드·실행 JDK는 17이다: `build.gradle` toolchain `JavaLanguageVersion.of(17)`, `Dockerfile` 빌더 `eclipse-temurin:17-jdk@sha256:5d6042fb…`·런타임 `eclipse-temurin:17-jre@sha256:207ecae0…`, CI `actions/setup-java@v6` `java-version: '17'`.
- Spring Boot 4.0.8(#97)·모듈식 스타터(#99)는 끝났다. 로드맵이 "Java 21만 남음"으로 기록한 항목이다.
- 21은 LTS이고 Boot 4.0의 최소 버전(17)보다 높아 프레임워크 쪽 제약은 없다. 이 작업은 **실행 기반만 바꾸고 애플리케이션 동작은 바꾸지 않는 것**이 목표다.

### 정찰에서 확인한 사실 (2026-10-07)

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| JDK 버전이 나오는 곳 | `build.gradle:24`, `Dockerfile:5,16`, `.github/workflows/ci.yml:17,20`, `README.md:3`(배지), `AGENTS.md:20`. 그 밖의 언급(`.trivyignore.yaml` 주석, `Dockerfile` 주석 `gradle:8.7-jdk17`, `docs/dependabot.md` 첫 실행 스냅샷, `docs/verification/admin-detail-rendering.md`)은 **이력 서술**이다 | `git grep` |
| 로컬 toolchain | Corretto 21.0.9가 설치돼 있고 Gradle이 자동 탐지한다(IntelliJ 경유). `JAVA_HOME`은 Corretto 17. Gradle daemon은 17로 돌고 컴파일·테스트·bootRun만 toolchain 21로 포크된다 | `./gradlew -q javaToolchains` |
| toolchain 자동 다운로드 | `settings.gradle`에 foojay resolver가 없어 **JDK 21이 없는 PC에서는 자동 다운로드가 안 된다**(설치돼 있어야 함) | `settings.gradle` |
| IntelliJ 로컬 설정 | `.idea`(커밋 대상 아님)는 Project SDK `corretto-17`·언어 수준 17·Gradle 위임 빌드 끔·테스트 실행기 `PLATFORM` | `.idea/misc.xml`·`gradle.xml`(1라운드 리뷰 확인) |
| Gradle wrapper | 8.14.5. Java 21에서 실행·toolchain 컴파일을 지원한다(지원 시작 8.5) | `gradle-wrapper.properties` |
| temurin 21 이미지 | `21-jdk` index digest `sha256:3e3c176f…c5c2b`, `21-jre` index digest `sha256:cff19e62…a36c5`. 기존 17 참조도 index digest(OCI image index)라 같은 형식이다 | `docker buildx imagetools inspect` |
| 17/21 런타임 이미지 차이 | 둘 다 Ubuntu 26.04.1 LTS, `LANG=en_US.UTF-8`, `file.encoding=UTF-8`, `groupadd` 있음, UID 10001 미사용. 21-jre는 `21.0.12.1` | `docker run … cat /etc/os-release` 등 |
| 기본 문자셋(JEP 400, Java 18+) | 운영 컨테이너는 원래 UTF-8이라 변화가 없다. Windows 로컬 JVM만 기본 문자셋이 MS949 → UTF-8로 바뀐다. `src/main`에서 기본 문자셋에 기대는 호출은 0건(`file.getBytes()`는 `MultipartFile`). 테스트의 `"…".getBytes()`는 같은 JVM 안에서 쓰고 읽는 대칭 비교라 영향이 없다 | Grep |
| 로케일 서식(CLDR 42, Java 20+) | 서식은 전부 숫자 패턴(`yyyy-MM-dd HH:mm` 등)이고 `a`(오전/오후)·`FormatStyle`·지역화 서식은 0건 | Grep |
| 대소문자 변환 | 대부분 `Locale.ROOT`이지만 **인자 없는 `toLowerCase()` 2곳**(`NoticeAttachmentService:208` 확장자 판정, `LocalDiskFileStorage:324` 저장 확장자)이 기본 로케일에 기댄다. JDK 버전과 무관한 기존 성질이다(터키어 로케일에서 `.GIF`→`.gıf`). 운영 컨테이너 기본 로케일은 `en_US`라 현재 영향은 없다 | Grep (v2 정정) |
| Unicode 버전(17=13.0, 21=15.0) | `Locale.ROOT`로 고정해도 대소문자 매핑 자체가 바뀐다. 실측: `U+10570`(Vithkuqi 대문자)의 `.toLowerCase(Locale.ROOT)` → 17은 그대로, 21은 `U+10597`. Hibernate `@Email`은 비ASCII 로컬 파트를 허용하므로 이메일 정규화(`EmailNormalizer:43`)에 들어올 수 있다 | 실측(Corretto 17.0.18·21.0.9) |
| 제거·폐기 API | `new URL(`·`new Locale(`·`Thread.stop/suspend`·`finalize()`·`SecurityManager` 0건. 컴파일러 `-Werror` 없음 | Grep, `build.gradle` |
| Dependabot | `docker` 생태계는 메이저 상향을 무시한다. 태그를 `21-jdk`/`21-jre`로 바꾸면 이후 21 라인의 digest·마이너 갱신은 계속 받고 25 상향 PR은 받지 않는다 | `.github/dependabot.yml` |
| dev/prod 이미지 | `docker-compose.dev.yml`·`docker-compose.prod.yml` 모두 `build: .`로 같은 `Dockerfile`을 쓴다 | compose 파일 |
| prod-smoke 정리 | EXIT 트랩이 `docker compose down -v`로 스택·볼륨을 지운다(성공 시에도). 이미지 `cms-prod-app:latest`는 남는다 | `scripts/ci/_prod-ci-common.sh:147,177` |

## 2. 핵심 쟁점과 결정

### 쟁점 1 — 무엇을 21로 올리나 (컴파일 대상 vs 실행 JDK)

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| A | toolchain만 21, 이미지·CI는 17 유지 | 불가능 — 21 바이트코드(class 65)는 17 JRE에서 실행되지 않고, 이미지 빌더·CI에 21이 없으면 toolchain 해석이 실패한다(자동 다운로드 수단 없음) |
| B | 실행 JDK만 21, `options.release = 17` 유지 | 런타임 개선만 얻고 언어 수준은 17에 묶인다. 이번 항목의 목적(버전 부채 해소)과 어긋나고 설정이 하나 늘어난다 |
| **C (채택)** | toolchain·빌더 JDK·런타임 JRE·CI JDK를 **모두 21**로 | 4곳을 한 PR에서 맞춰야 하지만, "테스트한 JVM = 운영 JVM"이 유지된다 |

**왜**: 기술적으로는 "새 JVM이 옛 바이트코드를 실행"하는 조합도 돌아간다. 하지만 이 프로젝트는 **테스트가 검증한 JVM과 운영 JVM이 같아야 한다**는 정책을 택한다. 테스트 1360건이 통과한 근거가 운영 런타임에도 그대로 성립해야 하기 때문이다(예: 쟁점 7의 Unicode 차이는 JVM 메이저마다 다르다). 반대 방향(런타임 < 컴파일 대상)은 기동 자체가 `UnsupportedClassVersionError`로 실패하므로 prod-smoke가 잡는다. (Dependabot #50의 실패 원인은 `docs/dependabot.md`가 추정이라고 명시하므로 근거로 쓰지 않는다.)

### 쟁점 2 — 이미지 참조 형식

- **결정**: 기존 관례대로 `eclipse-temurin:21-jdk@sha256:<index digest>`·`eclipse-temurin:21-jre@sha256:<index digest>`(태그+index digest)를 쓴다. 플랫폼별 manifest digest는 쓰지 않는다.
- **왜**: `scripts/ci/check-image-refs.sh`는 temurin FROM 줄에 digest가 있는지만 검사하고, jdk·jre digest가 서로 다른 것은 허용한다. 태그를 남겨야 Dependabot이 21 라인을 추적한다. index digest여야 다른 아키텍처 빌드도 같은 참조로 된다. 스크립트 수정은 필요 없다.
- digest는 **구현 시점에 다시 조회**한다. 정찰 값과 다르면(그사이 재빌드) 새 값을 쓰고 PR 본문에 조회 시각을 남긴다.

### 쟁점 3 — 개발자 PC에 JDK 21이 없을 때

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| A | `settings.gradle`에 `org.gradle.toolchains.foojay-resolver-convention` 플러그인 추가(자동 다운로드) | 편하지만 **새 빌드 플러그인 의존성**이라 CLAUDE.md "검증되지 않은 새 라이브러리/의존성은 먼저 제안" 대상이다. 빌드가 외부 다운로드에 기대게 되고 범위가 커진다 |
| **B (채택)** | 플러그인 없이 README "개발환경"에 **JDK 21 필요**를 명시 | 신규 의존성 없음. 현재 개발 PC에는 이미 21이 있다. Docker 경로(`make dev-up`·prod)는 이미지 안의 21을 쓰므로 영향이 없다 |

**왜**: 최소 변경 원칙이다. JDK 21이 없으면 Gradle이 "toolchain을 찾을 수 없음"으로 명확히 실패하므로 조용한 오동작은 없다.

- **IntelliJ 직접 실행 경로(v2)**: README "개발환경"은 IntelliJ에서 `CmsApplication`을 직접 실행하도록 안내하는데, 이 경로는 Gradle toolchain을 따르지 않는다(로컬 `.idea`가 SDK 17·`PLATFORM` 실행기). 그래서 README에 "Project SDK와 언어 수준을 21로, Gradle 프로젝트 다시 불러오기(Reload)"를 함께 적는다. 검증에서도 이 경로로 한 번 기동한다(§5). `.idea`는 커밋하지 않는다.

### 쟁점 4 — Java 21 기능 도입 범위

- **결정**: 가상 스레드(`spring.threads.virtual.enabled`), 패턴 매칭 `switch`·record 패턴, `SequencedCollection` API 등으로 **코드를 고치지 않는다**. 이번 PR은 기반 상향만 한다.
- **왜**: 가상 스레드는 Tomcat 요청 처리·`@Async` 메일 executor(큐 상한 core 4/queue 20, #66)·JDBC 커넥션 풀의 동시성 특성을 바꾼다. 이 저장소의 동시성 계약(비관적 락, 메일 큐 포화 시 균일 응답 등)을 다시 검증해야 하는 별도 작업이다. 문법 현대화는 무관한 리팩터링이다(작업 방식 2·3).
- 후속 후보로만 기록한다(§8).

### 쟁점 5 — Mockito 인라인 mock maker의 동적 에이전트 경고

- Java 21은 실행 중에 에이전트를 붙이면(Mockito가 Byte Buddy 에이전트를 스스로 붙임) **경고**를 출력한다(JEP 451). 21에서는 경고일 뿐 실패가 아니다.

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| **A (채택)** | 그대로 둔다(경고 허용) | 변경 0. 테스트 로그에 경고 몇 줄 |
| B | 테스트 태스크 `jvmArgs '-XX:+EnableDynamicAgentLoading'` | 경고만 끈다. 문제를 숨기는 설정이 하나 늘어난다 |
| C | Mockito를 `-javaagent`로 명시 로드(Mockito 문서 권장) | 정석이지만 `configurations` 추가 등 빌드 스크립트가 커진다. 동적 로딩이 **기본 금지되는 미래 JDK**로 갈 때 할 일 |

**왜 A**: 21에서는 동작에 영향이 없다. 경고는 다음 메이저(25) 이행 때 C로 처리할 신호로 남긴다.

**실패가 나오면(v2, 원인별로 분리)** — 어느 경우든 즉시 보고한다(계획 변경):
- 에이전트 **attach 실패**(예: `Could not initialize inline Byte Buddy mock maker`·`AttachNotSupportedException`) → C를 검토한다.
- **클래스 파일 해석·변환 실패**(예: `Unsupported class file major version`) → C로는 해결되지 않는다. `./gradlew dependencies --configuration testRuntimeClasspath`로 실제 해석된 Mockito·Byte Buddy 버전과 예외 원인을 확인한 뒤 따로 결정한다(버전 오버라이드는 새 결정).

### 쟁점 6 — 문서 정합 범위

- **고친다(현재 상태 서술)**: `README.md` 배지 `Java-17`→`Java-21`, README "개발환경"에 JDK 21 필요·IntelliJ 설정 명시, `AGENTS.md:20` "Java 17"→"Java 21", 루트 `CLAUDE.md` "구조상 알아둘 점"의 Boot 4 항목에 Java 21(toolchain·이미지·CI를 함께 맞출 것) 한 줄.
- **고치지 않는다(이력 서술)**: `.trivyignore.yaml`의 `17-jre digest 207ecae0…` 주석(예외 삭제 근거 기록), `Dockerfile`의 `gradle:8.7-jdk17` 주석(결정 9의 배경), `docs/dependabot.md` 첫 실행 스냅샷 표·추정 설명, `docs/verification/*` 실증 기록, `AdminActionLogAspect` Javadoc의 "Java 17의 `\p{Cntrl}`"(21에서도 사실이며 코드 변경과 무관).
- **왜**: L-02(#104)에서 확정한 원칙 — 이력 문서는 다시 쓰지 않는다.

### 쟁점 7 — Unicode 13 → 15 대소문자 매핑 변화 (v2)

- 사실: `EmailNormalizer`는 `toLowerCase(Locale.ROOT)`로 정규화한다. 21에서는 Unicode 14·15에 추가된 대소문자 쌍(예: Vithkuqi `U+10570`~)이 소문자로 바뀌지만 17에서는 그대로였다. 영향 경로: 기존 저장 이메일에 그런 문자가 있으면 같은 값을 다시 제출해도 정규화 결과가 달라진다. 그러면 `Member`가 이메일 변경으로 보고 재설정 토큰을 지우거나, 중복 검사·재설정 요청 조회가 저장값과 어긋난다.

| 선택지 | 내용 | 트레이드오프 |
|---|---|---|
| **A (채택)** | 코드 유지(21의 Unicode 15 동작 수용) + **전환 전 데이터 점검**: 비ASCII 이메일 행이 0건인지 확인 | 코드 변경 0. 새 입력은 더 정확한 Unicode 규칙으로 정규화된다. 점검이 0건이면 기존 데이터 중 어긋날 대상이 없다 |
| B | ASCII 범위만 소문자화하도록 `EmailNormalizer` 변경 | JDK와 무관해지지만 **애플리케이션 동작 변경**이다(17에서 소문자화되던 비ASCII 문자도 안 바뀜). 이 PR의 "동작 불변" 목표와 충돌하고 정책 결정이 필요하다 |

**왜 A**: 실배포 DB가 아직 없고(로드맵 "실배포 인프라" 미착수), 대상 문자는 Unicode 14·15 신규 문자로 한정돼 실제 관리자 이메일에 쓰일 가능성이 낮다. 점검 SQL로 근거를 확보한다:
`SELECT id, email FROM member WHERE email REGEXP '[^ -~]';` — 결과가 0건이면 기존 데이터 영향이 없다. 0건이 아니면 해당 행의 17·21 정규화 결과를 비교해 보고한다(계획 변경).
점검 대상은 로컬 dev DB다(prod-smoke 합성 데이터는 ASCII 고정). 실배포 시점에는 이 SQL을 배포 전 점검으로 쓴다(PR 본문에 기록).

### 설계 제약 (명문화)

1. 빌더 JDK·런타임 JRE·CI JDK·toolchain의 메이저는 **항상 같다**(프로젝트 정책 — CLAUDE.md에 기록. 자동 검사는 두지 않는다: 쟁점 1·v2 기각 사유).
2. 이미지 참조는 태그+index digest. `check-image-refs.sh` 통과가 필수다.
3. 애플리케이션 소스(`src/main`)·마이그레이션·설정 YAML은 수정하지 않는다. 수정이 필요해지면 계획 변경으로 보고한다.

## 3. 변경 파일

| 파일 | 변경 |
|---|---|
| `build.gradle` | `JavaLanguageVersion.of(17)` → `of(21)` |
| `Dockerfile` | 빌더 `eclipse-temurin:21-jdk@sha256:…`, 런타임 `eclipse-temurin:21-jre@sha256:…` |
| `.github/workflows/ci.yml` | test job `Set up JDK 21`, `java-version: '21'` |
| `README.md` | 배지, 개발환경에 JDK 21 필요 + IntelliJ Project SDK·언어 수준 21·Gradle Reload |
| `AGENTS.md` | "Java 17" → "Java 21" |
| `CLAUDE.md` | Java 21·메이저 일치 정책 한 줄 |
| `adversarial-review/plan/README.md` | 32행 추가(완료 시 표시) |
| 이 문서 | 구현·검증 결과 |

`prod-smoke` job은 JDK를 설치하지 않고 Dockerfile로 빌드하므로 CI 파일의 다른 곳은 바꾸지 않는다.

## 4. 작업 단계

1. `chore/java-21` 브랜치 생성.
2. `build.gradle` toolchain 21 → `./gradlew clean compileJava compileTestJava`로 Lombok·QueryDSL APT(Q클래스 생성)·`-parameters` 컴파일 확인. `javap -v`로 class major version 65 확인.
3. `./gradlew cleanTest test`(dev, Testcontainers) 전체 통과 → `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` 전체 통과. 테스트 JVM이 21인지 로그·리포트로 확인한다.
4. temurin 21 digest 재조회 → `Dockerfile` 수정 → `bash scripts/ci/check-image-refs.sh`.
5. `ci.yml` JDK 21.
6. 문서 4종 수정.
7. 로컬 `prod-smoke`(`CMS_CI_DISPOSABLE_DOCKER=1 bash scripts/ci/prod-smoke.sh`) — 이미지 빌드·기동·보안 응답·백업복구 왕복. 스크립트는 종료 시 스택을 `down -v`로 지우므로, 끝난 뒤 남은 이미지로 `docker run --rm --entrypoint java cms-prod-app:latest -version`을 실행해 21을 확인한다.
8. Trivy: 로컬에 trivy가 없으므로 `aquasec/trivy` 컨테이너로 `cms-prod-app:latest`를 CI와 같은 옵션(`image --scanners vuln --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --timeout 20m --ignorefile .trivyignore.yaml`)으로 스캔하고 trivy 버전·이미지 ID를 기록한다. 로컬 trivy 버전이 CI 액션과 다를 수 있으므로 **최종 판정은 PR CI의 `prod-smoke` Trivy 단계**다.
9. 실기 검증(§5) → 기록(§7 형식) → `code-review-loop` → `commitPR`.

## 5. 검증 계획

| 검증 | 방법 | 통과 기준 |
|---|---|---|
| 컴파일·APT | `compileJava`·`compileTestJava`, `build/generated/.../Q*.java` 생성, `javap -v` major 65 | 성공 |
| 전체 테스트 | `./gradlew cleanTest test` | 실패·오류 0 (현재 기준 1360건, 스킵 3) |
| 시간대 독립 | `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` | 실패·오류 0 |
| 이미지 참조 | `scripts/ci/check-image-refs.sh` | 통과 |
| prod 이미지 | 로컬 `prod-smoke.sh` + 종료 후 이미지 `java -version` | 통과 + JVM 21 |
| 취약점 | Trivy(로컬 컨테이너 + PR CI) | 수정판 있는 HIGH/CRITICAL 0. 나오면 원인(JRE 21 OS 레이어 vs jar)을 확인해 보고 — 임의로 예외 추가 금지 |
| CI | PR의 `test`·`prod-smoke` | pass |
| Unicode 데이터 점검 | dev DB에 쟁점 7의 SQL | 0건(아니면 보고) |
| IntelliJ 직접 실행 | Project SDK 21로 `CmsApplication` 실행 후 로그인 페이지 200 | 기동 성공(실행 JVM 21을 로그로 확인) |
| 실기(dev) | toolchain 21로 `bootRun`(폐기용 MariaDB) 후 Playwright: 로그인 → 대시보드 → 공지 작성·첨부 업로드·공개 화면 확인·다운로드 → 원복(삭제) / 비로그인 `/admin` 302, MANAGER 권한 없는 기능 403 / 로그인 실패 메시지 등 기존 동작 | 전부 기존과 동일, 스크린샷 저장 |
| 실기(prod 이미지) | prod-smoke가 띄운 스택에서 `/actuator/health` 200, `/admin/login` 200, 합성 ADMIN 로그인, Actuator 302/403, ADMIN 인증 후 `/swagger-ui.html`·`/v3/api-docs` 404 | `prod-smoke.sh` 판정(스크립트가 이미 검사하는 항목) |

## 6. 리스크

| 리스크 | 가능성 | 대응 |
|---|---|---|
| Lombok·QueryDSL 5.1.0 APT가 21 javac에서 실패 | 낮음(Boot BOM Lombok은 21 지원, QueryDSL APT는 표준 `javax.annotation.processing` API만 사용) | 2단계에서 바로 드러남. 실패 시 원인 확인 후 보고(버전 상향은 새 결정) |
| Mockito/Byte Buddy가 21에서 실패 | 낮음(Boot 4 BOM의 Mockito 5.x는 21 지원) | 쟁점 5 — 원인별 대응 |
| Windows 로컬 테스트의 기본 문자셋 변화로 테스트 결과가 바뀜 | 낮음(정찰상 대칭 비교뿐) | 실패하면 테스트가 기본 문자셋에 기댄 것 — 원인 데이터 확인 후 보고 |
| Unicode 15 대소문자 매핑으로 비ASCII 이메일 정규화 결과 변화(쟁점 7) | 낮음(대상 문자 한정) | 데이터 점검 SQL, 0건이 아니면 보고 |
| IntelliJ 직접 실행이 17로 남음 | 중간 | README 안내 + 검증 1회 |
| JRE 21 이미지의 OS 패키지에서 Trivy 차단 | 중간(digest 시점에 따라 다름) | 같은 태그의 더 새 digest로 해결되는지 먼저 확인, 아니면 보고 |
| 운영 롤백 | — | PR revert 후 이미지를 다시 빌드해 17로 돌아간다. 스키마 변경은 없다. 단, 21 운영 중 저장된 이메일은 Unicode 15 규칙으로 정규화됐을 수 있다(v3) — **롤백 직전에도 쟁점 7의 SQL을 실행**하고, 비ASCII 행이 있으면 그 행의 원문 입력을 17·21 정규화 결과로 비교해 이미지 단독 롤백 여부를 판단한다(최악의 영향은 그 회원이 같은 이메일을 다시 제출할 때 이메일 변경으로 판정돼 재설정 토큰이 지워지는 것). **21로 빌드된 jar를 17 JRE에서 띄우면 `UnsupportedClassVersionError`** — 이미지 단위로만 롤백한다 |
| JDK 21이 없는 개발 PC | 중간 | README 명시(쟁점 3) |

## 7. 구현·검증 결과 (2026-10-07, 브랜치 `chore/java-21`)

### Context
v3 계획(3라운드 ship) 승인 후 구현했다. 계획과 달라진 결정은 없다. `src/main`·마이그레이션·설정 YAML은 수정하지 않았다(설계 제약 3 준수).

### 핵심 확정 사항
- toolchain·빌더·런타임·CI 4곳 모두 21. 이미지 digest는 구현 시점(2026-10-06T16:43Z) 재조회 결과가 정찰 값과 같았다: `21-jdk@sha256:3e3c176f…c5c2b`, `21-jre@sha256:cff19e62…a36c5`.
- Mockito 동적 에이전트는 **경고 1회만** 출력됐다(`Mockito is currently self-attaching…`). 실패는 없어 쟁점 5의 A를 유지한다.
- 쟁점 7 실측 보강: Hibernate `@Email`은 **보충 평면 문자(`U+10570`)가 든 이메일을 입력 단계에서 거부**하지만(400 VALIDATION_ERROR), **BMP의 Unicode 14·15 신규 쌍은 허용**한다. 실제 앱(21)에서 `U+A7C0vk@example.com`을 저장하면 `U+A7C1…`로 저장된다(17은 `U+A7C0` 그대로 — `U+A7C0`·`U+2C2F`를 17·21 JVM에서 각각 실측). 즉 위험 경로는 실재하며 BMP 문자로 한정된다. 점검 SQL(`email REGEXP '[^ -~]'`)이 이 행(`HEX` `EA9F81…`)을 검출함을 확인했다.
- 정찰 정정(기본 문자셋): Windows 로컬의 Gradle 경유 JVM은 Gradle이 `-Dfile.encoding=x-windows-949`를 명시로 넘긴다(bootRun 앱 프로세스 명령줄로 확인). 그래서 로컬 bootRun JVM은 21에서도 MS949이고, JEP 400의 UTF-8 기본값 변화는 로컬 bootRun에 나타나지 않는다. 운영 컨테이너는 17·21 모두 UTF-8이라 변화가 없다. (테스트 워커 JVM의 값은 따로 확인하지 않았다.)

### 구현 파일
`build.gradle`(toolchain 21), `Dockerfile`(FROM 2줄), `.github/workflows/ci.yml`(JDK 21), `README.md`(배지, 개발환경 JDK 21·IntelliJ 안내), `AGENTS.md`(Java 21), `CLAUDE.md`(Java 21 항목 — 메이저 일치 정책·foojay 없음·가상 스레드 미사용·Unicode 점검), `adversarial-review/plan/README.md`(32행), 이 문서.

### 검증 결과
| 검증 | 결과 |
|---|---|
| 컴파일·APT | `clean compileJava compileTestJava` 성공, class major 65, Q클래스 9개 생성. 테스트 컴파일의 deprecation·unchecked Note는 17에서도 똑같이 나오는 기존 경고(17로 되돌려 비교) |
| 전체 테스트 | `./gradlew cleanTest test` — 1360건, 실패 0·오류 0·스킵 3, 실행 JVM `Java 21.0.9`(Spring 기동 로그 35회 전부 21) |
| 시간대 독립 | `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test` — 1360건, 실패·오류 0, 스킵 3 |
| 이미지 참조 | `check-image-refs.sh` 통과(mariadb 7개, temurin FROM 2개) |
| prod 이미지 | 로컬 `prod-smoke.sh`(`CMS_CI_DISPOSABLE_DOCKER=1`) 전체 통과 — 기동·health·로그인·Actuator 302/403·Swagger 404·백업복구 왕복. 종료 후 이미지 `java -version` = Temurin `21.0.12.1+1-LTS`. 이미지 ID `sha256:fa3481bd…` |
| 취약점 | `aquasec/trivy` 0.74.0, CI와 같은 옵션 — ubuntu 26.04 OS 0건, `app/app.jar` 0건(HIGH/CRITICAL, 수정판 있는 것). 최종 판정은 PR CI |
| Unicode 데이터 점검 | 로컬 dev DB(`cms-db-dev`, 회원 6명) 비ASCII 이메일 **0건** |
| 실기(dev, toolchain 21 `bootRun` + 폐기용 MariaDB 10.11 동일 digest + Playwright) | 기동 로그 `using Java 21.0.9`, 마이그레이션 22개 적용. 비로그인 `/admin` → `/admin/login`. 잘못된 비밀번호 → "아이디 또는 비밀번호가 올바르지 않습니다." ADMIN 로그인 → 대시보드(KST 기준 2026-10-07 집계, 스크린샷 `.playwright-mcp/java21-01-dashboard.png`). 공지 작성 201 → 첨부(`검증.PDF`, 대문자 확장자·한글 파일명) 201 → 관리자 다운로드 200(바이트 동일, `filename*=UTF-8''…`) → 공개 상세 렌더링(스크린샷 `java21-02-public-notice.png`) → 무인증 공개 다운로드 200(바이트 동일) → 원복: 첨부·공지 삭제 204, 공개 상세·첨부 404, 관리 목록 0건. 권한 경계: 권한 없는 MANAGER 로그인 302 → `/admin/api/notices`·`/admin/notice/manage`·`/admin/api/members` 403, 비인증 API 401. 비밀번호 정책(15자) 400 유지. 서버 ERROR 로그 0건, 브라우저 콘솔 오류는 의도한 400·404뿐 |
| IntelliJ 직접 실행 | PR 시점에는 **미검증**(GUI 조작 불가, README 안내만 반영). 머지 후 사용자가 IntelliJ에서 실행한 `CmsApplication`(PID 17920, 부모 `idea64.exe`, `corretto-21.0.9`)이 `/admin/login` 200으로 확인됨(2026-10-07) |

검증 중 사용자 dev 앱(`cms-app-dev`)은 승인을 받아 잠시 멈췄다가(8080 충돌) 끝난 뒤 `docker start`로 복구했다(기존 이미지, 로그인 페이지 200). 폐기용 DB·저장 디렉터리는 삭제했다.

### 이슈
- 없음(계획 변경 없음). 정찰 사실 2건 정정(위 "핵심 확정 사항": `@Email` 범위, 로컬 `file.encoding`).

### 후속
- §8 항목 유지.
- IntelliJ 직접 실행 경로는 사용자가 Project SDK 21로 한 번 확인한다.
- PR CI(`test`·`prod-smoke` Trivy)는 커밋 후 확인한다.

## 8. 후속 (이번 범위 밖)

- 가상 스레드 도입 검토(쟁점 4) — 동시성 계약 재검증과 함께.
- Mockito `-javaagent` 명시 로드(쟁점 5 C) — 다음 JDK 메이저(25) 이행 시.
- 인자 없는 `toLowerCase()` 2곳(`NoticeAttachmentService:208`·`LocalDiskFileStorage:324`)을 `Locale.ROOT`로 — JDK와 무관한 기존 로케일 의존(v2 정찰 정정). 무관한 수정이라 이번 PR에 넣지 않는다. → 해소(2026-10-07): `PLAN-extension-locale-root.md`(영향 확장자는 `gif`·`zip`).
