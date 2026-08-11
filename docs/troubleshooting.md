# 문제 해결 기록 (Troubleshooting)

본 문서는 프로젝트 개발 중 실제로 발생한 문제와  
원인 분석 및 해결 과정을 정리한 문서입니다.

> 새 이슈는 아래 카테고리 중 적절한 곳의 마지막에 추가한다.  
> 카테고리: **개발 환경 / 인프라** · **빌드 / 의존성** · **애플리케이션 / 런타임**

---

## 개발 환경 / 인프라

Docker, WSL, 로컬 DB, IDE 관련 환경 구성 문제를 기록한다.

### WSL에서 docker 명령어를 찾을 수 없는 문제

#### 오류 메시지

```
The command 'docker' could not be found in this WSL 2 distro.
```

#### 원인

Docker Desktop에서 WSL Integration이 활성화되지 않았습니다.

#### 해결 방법

1. Docker Desktop 실행
2. Settings → Resources → WSL Integration 이동
3. 다음 항목 활성화
    - "Enable integration with my default WSL distro"
    - Ubuntu 토글 ON
4. Apply & Restart

확인:

```bash
docker version
docker compose version
```

---

### Docker 권한 오류 (docker.sock)

#### 오류 메시지

```
permission denied while trying to connect to the Docker daemon socket
```

#### 원인

현재 사용자가 docker 그룹에 포함되어 있지 않았습니다.

#### 해결 방법

```bash
sudo usermod -aG docker $USER
```

이후 Ubuntu 종료:

```powershell
exit
```

Ubuntu 재접속 후 확인:

```bash
wsl -d Ubuntu
groups
```

출력에 `docker`가 포함되어 있어야 정상입니다.

---

### menu 테이블 스키마 재생성 전 기존 데이터 확인 (2026-07-07)

#### 배경

`Menu` 엔티티는 모든 필드가 `String`으로 되어 있어(`useYn`→`Boolean`, `ord`→`Integer`, `upMenuNo`→`Long` 등) 타입 정합화가 필요하다. `application-dev.yml`의 `ddl-auto: update`는 기존 컬럼의 타입 변경·삭제를 자동 반영하지 않으므로, dev 로컬 `menu` 테이블을 drop 후 재생성하는 방식을 계획했다. 다만 dev 로컬 DB에 기존 데이터가 남아 있는지 확인 없이 drop하면 데이터 손실 위험이 있어(Codex 적대적 리뷰 지적), 재생성 착수 전 row 수를 먼저 확인했다.

#### 확인 절차 및 결과

```bash
bash scripts/dev-db.sh   # cms-db-dev 컨테이너 기동 (localhost:3307)
docker exec cms-db-dev mariadb -u admin -p1234 cms -e "SELECT COUNT(*) FROM menu;"
```

결과: `menu` 테이블 row 수 **0건** 확인.

#### 결정

row가 0건이므로 데이터 손실 우려 없이 `menu` 테이블 drop 후 재생성을 그대로 진행한다(백업·`ALTER TABLE` 전환 불필요).

---

### Codex 코드 리뷰가 "닫히지 않은 문자열/컴파일 불가"를 대량 오탐 (PowerShell 5.1 인코딩)

#### 오류 메시지

`/codex:review` 실행 시 실제로는 `./gradlew test` 전체 통과 상태인데, 한글 리터럴이 있는 줄마다 P1으로 아래와 같은 지적이 발생:

> `summary` 문자열이 닫히지 않아 이 컨트롤러가 컴파일되지 않습니다 / `log.debug` 문자열이 닫히지 않아 ... / placeholder 속성의 따옴표가 닫히지 않아 ...

#### 원인

Codex CLI는 Windows에서 파일 읽기를 `powershell.exe -Command 'Get-Content -Raw ...'`(Windows PowerShell 5.1)로 수행한다. PS 5.1의 `Get-Content`는 인코딩 미지정 시 시스템 ANSI(**CP949**)로 읽으므로, UTF-8 소스의 한글 주석·문자열이 모지바케로 깨진다. 깨진 바이트가 따옴표를 삼키면 리뷰어가 "닫히지 않은 문자열 → 빌드 실패"로 오판한다. 지적된 위치가 전부 한글 리터럴 줄이라는 것이 특징적 신호다.

#### 해결 방법

이중 방어를 적용 (2026-07-13):

1. **PowerShell 사용자 프로필** (`$PROFILE` = `Documents\WindowsPowerShell\Microsoft.PowerShell_profile.ps1`)에 읽기 기본 인코딩 고정 — Codex가 `-NoProfile` 없이 powershell.exe를 띄우므로 적용된다:

```powershell
$PSDefaultParameterValues['Get-Content:Encoding'] = 'utf8'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
```

2. **`AGENTS.md`에 "파일 인코딩 지침" 절 추가** — 한글 모지바케는 인코딩 문제로 간주하고 UTF-8로 재독해할 것, 빌드 실패 주장 전 `./gradlew compileJava compileTestJava`로 검증할 것 (다른 머신·CI에서 실행돼도 판단 오류 방지).

검증 명령 (자식 powershell.exe에서 기본 읽기와 UTF-8 명시 읽기가 일치하면 정상):

```bash
powershell.exe -Command "\$a = Get-Content -Raw <한글 포함 파일>; \$b = Get-Content -Raw <같은 파일> -Encoding UTF8; \$a -ceq \$b"   # True 기대
```

### Flyway 이전 세대 로컬 dev DB에서 통합 테스트 전체가 컨텍스트 로드 실패 (2026-07-18)

**오류 메시지**:

```
FlywayException: Found non-empty schema(s) `cms` but no schema history table. Use baseline() ...
IllegalStateException: ApplicationContext failure threshold (1) exceeded (이후 테스트 전부 도미노 실패)
```

**원인**: 로컬 `cms-db-dev` 컨테이너의 `cms` 스키마가 Flyway 도입(#7, V1 baseline) 이전 세대로 남아 있었다 — `flyway_schema_history` 없음, `visit_log` 테이블 없음, V4 잠금 컬럼 없음. Flyway는 "비어 있지 않은데 이력 없는" 스키마에서 기동을 거부하고, 첫 컨텍스트 로드 실패가 threshold에 걸려 이후 모든 `@SpringBootTest`/`@DataJpaTest`가 같은 메시지로 스킵된다(진짜 원인은 첫 실패 클래스 리포트의 `Caused by`에만 있음). `docs/migration-guide.md`의 일회성 baseline 절차는 "기존 스키마 = V1"일 때만 허용되는데, 이 DB는 V1보다 오래된 드리프트라 체크리스트 기준 중단 대상이었다.

**해결 방법**: dev 데이터가 폐기 가능함을 확인(사용자 승인)한 뒤 스키마를 재생성해 빈 DB 경로로 V1~V7 전체를 실행시켰다. 부수 확인: 테스트를 호스트에서 돌릴 때 `.env.dev`를 그대로 source하면 `DB_URL`이 컨테이너 내부 호스트명(`db`) 기준이라 접속 실패한다 — `DB_URL`은 unset해서 `application-dev.yml` 기본값(`localhost:3307`)을 쓰게 한다.

```bash
docker exec cms-db-dev mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "DROP DATABASE cms; CREATE DATABASE cms CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
set -a; source .env.dev; set +a; unset DB_URL; ./gradlew test   # V1~V7 자동 적용 + 전체 통과 확인
```

**후기 (2026-07-27)**: Testcontainers 전환(`com.cms.support.MariaDbContainerSupport`, `adversarial-review/plan/PLAN-testcontainers.md`) 이후 이 문제 자체가 원천 해소됐다. DB 접속 테스트가 매 실행마다 빈 컨테이너에서 V1부터 전체 마이그레이션을 새로 적용하므로 "로컬 DB가 이전 세대로 드리프트된 상태"라는 전제 자체가 성립하지 않는다. 다만 이 항목은 Flyway의 "비어 있지 않은데 이력 없는 스키마" 거부 동작과 컨텍스트 로드 실패 도미노의 원리를 보여주는 사례로 보존한다.

### Windows에서 Docker Desktop 재기동 직후 `bootRun`이 "Port already in use"로 반복 실패 (2026-08-10)

**오류 메시지**:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Web server failed to start. Port 8080 was already in use.
```

**원인**: `netstat`/`Get-NetTCPConnection`으로 확인해도 8080(그리고 대체로 시도한 8090)을 점유한 프로세스가 전혀 없는데도 바인딩이 계속 실패했다. `netsh interface ipv4 show excludedportrange protocol=tcp`로 확인한 결과 Windows가 **7506~8280 범위 전체를 TCP 포트 제외 범위(excluded port range)로 예약**하고 있었다 — 8080·8090 둘 다 이 범위 안에 있어 애초에 어떤 프로세스도 bind()할 수 없는 상태였다. 이 범위는 Docker Desktop(WSL2 백엔드)이 내부적으로 Hyper-V 가상 스위치를 재구성할 때 동적으로 예약되며, 특히 Docker Desktop을 방금 재기동한 직후에 넓게 잡히는 경향이 있다. Spring Boot DevTools의 `restartedMain` 스레드명 때문에 처음엔 devtools 재시작 레이스로 오인했으나(`SPRING_DEVTOOLS_RESTART_ENABLED=false`로도 동일하게 재현되어 devtools는 무관함을 확인), 실제 원인은 OS 레벨 포트 예약이었다.

**해결 방법**: 제외 범위 밖의 포트를 확인해 그 포트로 기동한다.

```powershell
# 현재 제외된 범위 확인
netsh interface ipv4 show excludedportrange protocol=tcp

# 범위 밖 포트(예: 9000)로 bootRun
./gradlew bootRun --args="--server.port=9000"
```

근본 해결(관리자 권한 필요, 이번엔 적용하지 않음)은 `net stop winnat && net start winnat`으로 WinNAT을 재시작해 예약을 초기화하는 것이지만, Docker Desktop이 사용 중인 네트워킹을 함께 재설정할 위험이 있어 로컬 개발 중에는 포트를 우회하는 쪽을 권장한다.

---

## 빌드 / 의존성

Gradle 빌드, QueryDSL Q클래스 생성, 라이브러리 호환성 등 빌드·의존성 관련 문제를 기록한다.

### `./gradlew bootRun` 실행 중 템플릿(리소스) 파일만 수정하면 devtools가 재시작을 감지하지 못함 (2026-07-22)

#### 증상

`bootRun`을 이미 띄운 상태에서 `src/main/resources/templates/**/*.html`을 수정한 뒤 브라우저를 새로고침해도 변경 사항이 전혀 반영되지 않는다. `spring-boot-devtools`가 붙어 있고 로그에 "Devtools property defaults active!"가 찍혀 있어 자동 재시작이 되고 있다고 착각하기 쉽다.

#### 원인

`bootRun`은 `build/resources/main`을 classpath로 사용한다. IDE(IntelliJ 등)에서 저장 시 자동으로 리소스를 컴파일 출력 디렉터리에 복사해주는 것과 달리, `src/main/resources`의 소스 파일을 텍스트 에디터나 CLI 도구로 직접 수정하는 것만으로는 `build/resources/main`이 갱신되지 않는다. `spring-boot-devtools`의 재시작 트리거는 classpath 디렉터리(`build/classes`, `build/resources`)의 변경을 감시하는 것이지, `src/main/resources` 원본을 감시하지 않는다 — 즉 devtools가 감지할 대상 자체가 갱신되지 않아 재시작이 아예 트리거되지 않는다.

#### 해결 방법

리소스(템플릿·정적 파일)를 수정한 뒤 별도 프로세스에서 다음을 실행한다:

```bash
./gradlew processResources
```

이 명령이 `src/main/resources`를 `build/resources/main`으로 복사하면, devtools가 그 변경을 감지해 자동으로 애플리케이션을 재시작한다(수 초 내). `bootRun`을 매번 처음부터 재시작할 필요는 없다.

**주의**: devtools 재시작은 인메모리 세션(로그인 상태 등)을 초기화한다 — 재시작 후에는 다시 로그인해야 한다.

IDE로 개발할 때는 저장 시 자동 컴파일(Build project automatically)이 켜져 있으면 이 문제가 발생하지 않는다. CLI/에이전트로 파일만 직접 편집하는 워크플로우에서만 겪는 함정이다.

---

## 애플리케이션 / 런타임

Spring Security 필터, AOP 로깅, 트랜잭션 경계, JPA/QueryDSL 동작 등 런타임 문제를 기록한다.

### 최후 활성 ADMIN 계정이 로그인 실패 자동 잠금(LOCKED)으로 잠긴 경우 복구

#### 오류 메시지

```
로그인 화면에서 올바른 비밀번호를 입력해도 /admin/login-error로 거부됨
(서버 로그: "로그인 5회 연속 실패로 계정 잠금" WARN, admin_action_log에 ACCOUNT_AUTO_LOCK 기록)
```

#### 원인

연속 5회 로그인 실패 시 계정이 `LOCKED`로 자동 전이된다 (2026-07-14 도입, `LoginFailureService`).
활성 ADMIN이 1명뿐인 환경에서 그 계정이 잠기면, 화면(PATCH)으로 해제해 줄 다른 ADMIN이 없어
애플리케이션 차원의 즉시 복구 경로가 사라진다.

#### 해결 방법

1. **기본 복구 (권장)**: 자동 잠금은 **30분 후 자동 해제**된다 — 잠금 시각(`locked_at`)에서 30분이
   지난 뒤 올바른 비밀번호로 다시 로그인하면 된다. "비밀번호 찾기"(재설정 메일) 경로도 30분 경과 후엔 동작한다.
2. **즉시 복구 (비상)**: DB에서 직접 해제한다.
   ```sql
   UPDATE member SET status='ACTIVE', failed_login_count=0, locked_at=NULL WHERE user_id='<잠긴 계정>';
   ```
   dev 환경 실행 예: `docker exec cms-db-dev mariadb -uadmin -p1234 cms -e "<위 SQL>"`
3. 참고: `locked_at`이 `NULL`인 LOCKED는 관리자가 화면에서 **수동 잠금**한 계정이다 — 자동 해제되지
   않으며(의도된 영구 잠금), 다른 ADMIN의 PATCH 또는 위 SQL로만 해제된다.

검증: 해제 후 올바른 비밀번호로 로그인 성공, `failed_login_count`가 0으로 리셋됐는지 확인.

### @DataJpaTest 슬라이스에서 JPAQueryFactory 빈을 찾지 못해 컨텍스트 로딩 실패

#### 오류 메시지

```
NoSuchBeanDefinitionException: No qualifying bean of type
'com.querydsl.jpa.impl.JPAQueryFactory' available
  → UnsatisfiedDependencyException: Error creating bean 'adminActionLogRepositoryImpl'
  → Failed to load ApplicationContext
```

#### 원인

`@DataJpaTest`는 JPA 관련 컴포넌트(엔티티, Spring Data 리포지토리)만 로드하는 슬라이스 테스트다.
`JPAQueryFactory` 빈을 정의하는 `QuerydslConfig`는 일반 `@Configuration`이라 슬라이스 컨텍스트에 포함되지 않는다.
이 때문에 `JPAQueryFactory`에 의존하는 QueryDSL 커스텀 구현체(`*RepositoryImpl`: `MemberRepositoryImpl`, `AdminActionLogRepositoryImpl`)가 빈 생성에 실패하고, ApplicationContext 자체가 뜨지 못한다.

#### 해결 방법

테스트 클래스에 `QuerydslConfig`를 명시적으로 import 한다.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class) // *RepositoryImpl이 의존하는 JPAQueryFactory 빈을 슬라이스 컨텍스트에 포함
@ActiveProfiles("dev")
class VisitLogRepositoryDataJpaTest { ... }
```

검증:

```bash
./gradlew test --tests "com.cms.admin.visit.repository.VisitLogRepositoryDataJpaTest"
```

> 참고 (2026-07-27 갱신): Testcontainers 전환 이후 `VisitLogRepositoryDataJpaTest`는
> `MariaDbContainerSupport`를 상속해 `DB_PASS` 등 환경변수 없이도 실행된다 — 위 컨텍스트 문제만
> 재현하면 된다. (Testcontainers 전환 전에는 `.env.dev` 미주입 시 `Access denied for user 'admin'`이
> 먼저 발생해 컨텍스트 문제와 혼동하기 쉬웠다.)

---

### `th:replace`로 치환되는 `<head>` 안에 페이지 전용 CSS를 넣으면 조용히 무시됨

#### 증상

`manage.html`에 jstree CSS `<link>`와 페이지 전용 `<style>`(비활성 메뉴 회색 처리 등)을 `<head th:replace="~{admin/fragments/head :: adminHead}">` 태그 **안**에 작성했더니, 브라우저에 아무 오류도 없이 해당 CSS가 전혀 적용되지 않았다(예: 비활성 메뉴가 회색으로 표시되어야 하는데 파란색 그대로 렌더링됨).

#### 원인

Thymeleaf `th:replace`는 **호스트 엘리먼트 자체(자식 포함 전체)를 프래그먼트로 치환**한다. 즉 `<head th:replace="...">...내용...</head>`은 `...내용...` 부분이 렌더링 결과에서 통째로 사라지고 `adminHead` 프래그먼트만 남는다. `document.head.innerHTML`을 직접 확인해 jstree CSS `<link>`와 `<style>`이 렌더링 결과에 전혀 없음을 확인해 원인을 특정했다.

이미 `admin-manage.html`(회원 관리 화면)도 동일한 이유로 페이지 전용 `<link rel="stylesheet">`를 `</head>` 밖, `<body>` 시작 직후에 두는 방식으로 이 문제를 우회하고 있었다(기존 코드에 이미 존재하던 컨벤션).

#### 해결 방법

페이지 전용 `<link>`·`<style>`을 `<head th:replace="...">` **안이 아니라 `</head>` 다음, `<body>` 시작 지점**에 둔다(기존 `admin-manage.html` 컨벤션과 동일).

```html
<head th:replace="~{admin/fragments/head :: adminHead}">
    <title>메뉴 관리</title>
</head>
<body id="page-top">
<link rel="stylesheet" href="...">
<style> ... </style>
...
```

검증: `document.head.innerHTML`에 원하는 태그가 포함되는지, 또는 `getComputedStyle(el).color` 등으로 실제 스타일이 적용되는지 브라우저에서 직접 확인한다.

---

### jstree `instance.destroy()`가 컨테이너에 바인딩한 커스텀 이벤트까지 함께 제거함

#### 오류 메시지

```
TypeError: Cannot read properties of undefined (reading 'menuNo')
    at HTMLDivElement.<anonymous> ... select_node.jstree 핸들러
    at a.jstree.plugins.types.select_node (jstree.min.js)
```

#### 원인

메뉴 트리를 새로고침할 때 `instance.settings.core.data`만 바꾸고 `refresh()`를 호출하는 대신 매번 `instance.destroy()` 후 `$tree.jstree({...})`로 재초기화하도록 구현했다. 문제는 `destroy()`가 해당 컨테이너 엘리먼트(`#menuTree`)에 바인딩된 **모든** jQuery 이벤트(우리가 페이지 로드 시 한 번만 등록한 `select_node.jstree` 커스텀 핸들러 포함)를 제거한다는 점이었다. 첫 로드 직후에는 정상 동작하지만, "비활성 포함" 토글 등으로 트리를 한 번이라도 재생성한 뒤에는 노드를 클릭해도 우리 핸들러가 더 이상 호출되지 않아, jstree 내부 `trigger`가 참조하는 데이터 접근 경로가 어긋나며 위 오류가 발생했다. (실제로는 `data.node.original.data.menuNo`가 아니라 `data.node.data.menuNo`가 맞는 접근 경로였다는, 별개의 API 오용도 같은 클릭 흐름에서 함께 발견되어 같이 수정했다.)

`$._data(element, 'events')`로 바인딩된 이벤트 목록을 직접 조회해 `select_node`가 사라졌음을 확인하여 원인을 특정했다.

#### 해결 방법

트리를 재초기화하는 함수(`initTree()`) **내부에서** 매번 이벤트를 다시 바인딩한다.

```js
function initTree(data) {
    $tree.jstree({ ... });
    // destroy()가 기존 바인딩을 지우므로 재초기화할 때마다 다시 바인딩한다.
    $tree.off('select_node.jstree').on('select_node.jstree', handleSelectNode);
}
```

검증: 트리를 한 번 이상 새로고침(비활성 포함 토글 등)한 뒤 노드를 클릭해도 상세 폼이 정상적으로 채워지는지 playwright로 확인한다.

---

### `@SpringBootTest(classes = ...)` 명시 시 중첩 `@TestConfiguration`이 조용히 무시됨

#### 오류 메시지

```
Wanted but not invoked:
mailSender.send(<any org.springframework.mail.SimpleMailMessage>);
Actually, there were zero interactions with this mock.
```

`PasswordResetConcurrencyIntegrationTest.concurrentRequestWithSameEmail_onlyOneMailSent`가 간헐 실패 (플레이키).

#### 원인

테스트 안에 메일 발송 executor를 동기(`SyncTaskExecutor`)로 교체하는 중첩 `@TestConfiguration`(`SyncMailExecutorConfig`)을 두었지만, `@SpringBootTest(classes = CmsTestApplication.class)`처럼 **`classes` 속성을 명시하면 중첩 `@TestConfiguration` 자동 감지가 비활성화**된다(자동 감지는 classes/locations 미지정일 때만 동작). 그 결과 테스트 빈은 등록되지 않고 Boot 자동 구성 `applicationTaskExecutor`(비동기)가 `PasswordResetService`에 주입돼, `verify(mailSender)` 시점과 백그라운드 발송이 경합했다.

로그의 스레드명으로 원인을 특정했다: 발송 성공 로그가 `[task-1]`(applicationTaskExecutor 기본 접두사)에서 찍혀 있어 동기 교체가 적용되지 않았음을 확인.

#### 해결 방법

중첩 `@TestConfiguration` 클래스를 `classes` 배열에 **명시적으로 함께 나열**한다.

```java
@SpringBootTest(classes = {
        CmsTestApplication.class,
        PasswordResetConcurrencyIntegrationTest.SyncMailExecutorConfig.class
})
```

`applicationTaskExecutor` 자동 구성은 `@ConditionalOnMissingBean(Executor.class)`라(Boot 3.5.16 기준), 테스트 Executor 빈이 등록되면 물러나서 컨텍스트에 executor가 하나만 남는다 — 빈 이름 충돌·모호성 걱정 없이 동기 executor가 주입된다.

검증: 테스트 실행 후 리포트 XML에서 발송 성공 로그의 스레드가 executor 스레드(`task-1`)가 아니라 호출자 스레드(`pool-N-thread-M`)인지 확인한다.

---

### KST 고정 Clock 빈과 테스트의 `LocalDateTime.now()` 혼용 — 로컬(KST)만 통과하고 CI(UTC)에서 실패

#### 오류 메시지

```
PasswordResetConcurrencyIntegrationTest > 같은 토큰 동시 제출 2건 중 정확히 1건만 성공한다 FAILED
org.opentest4j.AssertionFailedError: 같은 토큰 동시 제출은 정확히 1건만 성공해야 한다 ==> expected: <1> but was: <0>
```

로컬에서는 통과하는데 GitHub Actions(UTC 러너) CI에서만 실패.

#### 원인

`AppConfig`의 `Clock` 빈은 `Clock.system(ZoneId.of("Asia/Seoul"))`(KST 고정)이고, `PasswordResetService`는 토큰 만료 판정에 `LocalDateTime.now(clock)`(KST)를 쓴다. 그런데 테스트는 만료 시각을 **시스템 기본 타임존**의 `LocalDateTime.now().plusMinutes(30)`으로 생성했다.

- 로컬(KST 머신): 테스트 now = 서비스 clock now → 통과
- CI(UTC 러너): 테스트가 UTC 기준 naive 시각으로 저장(예: 17:02+30분) ↔ 서비스는 KST now(다음날 02:02)와 비교 → `expiryAt.isAfter(now)`가 거짓 → **발급 직후인데 만료 판정** → 두 스레드 모두 잠금 후 재검증에서 거부

진단 단서: CI 테스트 리포트(아티팩트)의 Hibernate SQL 로그에서 두 스레드 모두 `SELECT ... FOR UPDATE`까지 도달했지만 `UPDATE`문과 에러 로그가 전혀 없음 — 수정 없는 조용한 거부는 잠금 후 재검증(만료/불일치/무자격) 경로뿐이다.

#### 해결 방법

시간 비교 로직(만료 판정 등)을 검증하는 테스트에서 기준 시각을 만들 때는 반드시 **서비스와 같은 `Clock` 빈을 주입**받아 사용한다.

```java
@Autowired
Clock clock; // AppConfig의 KST 고정 Clock

Member member = createMember(sha256Hex(plainToken), LocalDateTime.now(clock).plusMinutes(30));
```

검증(CI 재현): `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew test --tests "...PasswordResetConcurrencyIntegrationTest"` — 수정 전 동일 실패 재현, 수정 후 통과. (`JAVA_TOOL_OPTIONS`는 Gradle이 포크하는 테스트 JVM까지 전달된다)

단순 `createDate`/`updateDate`처럼 서비스가 시각 비교를 하지 않는 필드는 시스템 기본 `LocalDateTime.now()`여도 무방하다.

### 비관리자(공개) Thymeleaf 페이지 컨트롤러의 예외가 HTML이 아니라 JSON으로 응답됨

#### 오류 메시지

```
비로그인 사용자가 접근하는 페이지 컨트롤러에서 예외가 나면 브라우저에
{"timestamp":"...","path":"/notices/abc","code":"INTERNAL_ERROR","message":"서버 오류가 발생했습니다."}
같은 JSON이 그대로 뿌려짐 (404/500 HTML 페이지가 아님)
```

#### 원인

`GlobalApiExceptionHandler`는 `@RestControllerAdvice`다. Spring의 `@ControllerAdvice`/`@RestControllerAdvice`는 기본적으로 **모든 컨트롤러**(`@RestController`뿐 아니라 `@Controller` 페이지 컨트롤러도 포함)에 적용된다 — `basePackages`나 `assignableTypes` 같은 selector를 지정하지 않으면 admin API 전용으로 설계한 전역 예외 처리기가 새로 추가한 공개 페이지 컨트롤러(`com.cms.publicweb`)의 예외까지 가로채 JSON으로 바꿔버린다. `@PathVariable Long id`처럼 Spring이 자동 타입 변환을 시도하는 파라미터는 변환 실패 시 `MethodArgumentTypeMismatchException`이 **컨트롤러 진입 전**에 발생하므로, 컨트롤러 안에서 `Optional`/try-catch로 방어해도 이 경로는 흡수되지 않는다.

#### 해결 방법

1. `@PathVariable`/`@RequestParam`을 `Long`/`Integer` 대신 `String`으로 받아 컨트롤러가 직접 파싱한다 — 파싱 실패를 404 등 원하는 응답으로 직접 제어할 수 있고, Spring이 타입 변환 예외를 던질 여지 자체가 없어진다.
2. 남는 예외(Service/DB 장애 등)를 위해 문제 되는 패키지에만 적용되는 별도 `@ControllerAdvice(basePackages = "...")`를 신설하고 `@Order(Ordered.HIGHEST_PRECEDENCE)`를 붙인다 — advice 빈은 대상 컨트롤러에 selector가 일치하는 빈들 중 `@Order`로 우선순위가 갈리므로, 범위를 좁힌 advice가 전역 advice보다 먼저 매칭된다. 전역 `GlobalApiExceptionHandler`는 selector가 다른 패키지 컨트롤러에는 애초에 적용 후보가 되지 않으므로 admin API 동작에는 영향이 없다.
3. 이 범위 한정 advice의 보장 범위는 **컨트롤러·Service 실행 중 예외**로 한정된다 — Thymeleaf 렌더링(뷰 반환 이후) 단계 예외는 `DispatcherServlet.doDispatch()`가 핸들러 실행만 try/catch로 감싸고 `render()`는 별도로 감싸지 않아 이 advice가 잡지 못하고 컨테이너 `/error` 경로로 전파된다. 신규 템플릿이 항상 유효한 모델로만 렌더링되도록 테스트로 보증해 이 경로가 실제로 트리거되지 않게 하는 것으로 보완한다.

참고: `com.cms.publicweb.notice.controller.PublicNoticeController`(파싱 안전성) + `com.cms.publicweb.support.PublicWebExceptionAdvice`(범위 한정 advice) 구현. 상세 설계 결정은 `adversarial-review/plan/PLAN-public-notice.md` 결정 3-1·3-2 참조.

### 핸들러가 아예 없는 경로(정적 리소스 미존재 등)가 404가 아니라 500으로 응답됨 (2026-07-30, 해결: 2026-08-06)

#### 오류 메시지

```
매핑되는 컨트롤러·정적 리소스가 전혀 없는 경로에 접근하면 404 대신
{"timestamp":"...","path":"/swagger-ui.html","code":"INTERNAL_ERROR","message":"서버 오류가 발생했습니다."}
같은 JSON 500이 응답됨
```

#### 원인

바로 위 항목("비관리자 페이지 컨트롤러의 예외가 JSON으로 응답됨")과 같은 근본 원인(`GlobalApiExceptionHandler`가 selector 없는 전역 `@RestControllerAdvice`)이지만, 이번엔 컨트롤러 예외가 아니라 **핸들러 자체가 없는 요청**이 대상이다. Spring MVC는 매핑되는 핸들러·정적 리소스를 못 찾으면 `NoResourceFoundException`(또는 유사 예외)을 던지는데, 이 예외도 `@ExceptionHandler(Exception.class)` catch-all에 그대로 잡혀 500으로 바뀐다. prod 프로파일에서 `springdoc.swagger-ui.enabled=false`·`springdoc.api-docs.enabled=false`로 springdoc 핸들러 자체를 껐을 때 `/swagger-ui.html`·`/v3/api-docs`에서 이 현상이 재현됨을 실측 확인했다(PLAN-prod-profile.md Docker 실기 검증, 2026-07-30). `GET /admin/logout`(POST 전용 설계)·`GET /favicon.ico`도 `PLAN-public-notice.md` 실기 검증 당시 같은 증상으로 이미 발견된 바 있다.

#### 해결 방법

`GlobalApiExceptionHandler`(selector 없는 전역 `@RestControllerAdvice`) 안에 `NoResourceFoundException`·`NoHandlerFoundException` 전용 `@ExceptionHandler`를 `Exception` catch-all보다 먼저(같은 클래스 내 구체 예외 우선 매칭 규칙) 추가했다. 신규 advice를 별도로 만들지 않은 이유는, 위 항목의 패키지 범위 한정 advice(`PublicWebExceptionAdvice`)는 `basePackages` selector가 있어 "컨트롤러가 아예 없는 요청"(handler type이 없거나 다른 패키지에 속함)에는 애초에 적용 후보가 되지 않기 때문이다 — selector가 없는 전역 advice에 추가하는 것만이 모든 미매핑 경로를 잡을 수 있다.

응답 형식은 경로로 분기한다 — `/admin/api/**`는 `Content-Type: application/json`을 명시한 `ApiErrorResponse` JSON 404(`RESOURCE_NOT_FOUND`), 그 외는 `response.sendError(404)` + `null` 반환으로 기존 `error/404.html`(또는 `/admin` 하위는 `error/admin/404.html`)을 그대로 재사용한다(`PublicNoticeController.attachment()`가 이미 쓰던 `sendError`+null 패턴 재사용 — `HttpEntityMethodProcessor`가 반환값 null이면 `requestHandled=true`로 처리하고 종료하므로 `ResponseEntity` 반환 타입에서도 안전하다). `/admin/api/**` 판정에는 `SecurityConfig`가 인가 규칙에 쓰는 것과 **동일한** `RequestMatcher` 인스턴스(`GlobalApiExceptionHandler.API_MATCHER`, `SecurityConfig`·`AdminSessionExpiredStrategy`가 정적 임포트로 재사용)를 쓴다 — raw 문자열 비교(`uri.startsWith(...)`)는 컨텍스트 경로·세미콜론 매트릭스 파라미터가 섞인 경로에서 Security의 판정과 어긋날 수 있다(`/admin/api;v=1/foo`처럼 `PathPattern`은 매칭하지만 문자열 비교는 실패하는 경우가 실측으로 확인됨).

같은 리뷰 과정에서 `CustomErrorController`의 `requestURI.startsWith("/admin")` 분기도 함께 고쳤다 — 이 raw 문자열 비교는 `/administrator/missing`·`/admin-api/missing` 같은 비-admin 경로를 관리자 404로 오분류했다. `request.getContextPath()`를 제거한 뒤 `PathPattern.parse("/admin/**")`+`PathContainer.parsePath()`로 판정하도록 교체했다 — 이 컨트롤러는 컨테이너 ERROR 디스패치(`/error`) 시점에 실행되므로 `RequestMatcher.matches(HttpServletRequest)`를 쓸 수 없다(그 시점의 `request.getRequestURI()`는 원 경로가 아니라 포워드 대상인 `/error` 자체를 가리킨다 — 원 경로는 `jakarta.servlet.error.request_uri` 속성 문자열로만 존재한다). `PathPattern`을 문자열에 직접 적용하는 이 방식이 컨텍스트 경로·매트릭스 파라미터 양쪽을 실측으로 정확히 처리함을 확인했다(`/admin/**` 패턴 하나로 `/admin`(루트)·`/admin;v=1/missing` 전부 매칭, `/administrator/missing`은 불일치).

**검증**: `spring.mvc.throw-exception-if-no-handler-found`는 Spring Boot 3.5.16에 존재하지 않는 프로퍼티다(javap로 `WebMvcProperties`에 대응 필드 없음 확인) — `DispatcherServlet`(Spring Framework 6.2.19)의 `throwExceptionIfNoHandlerFound` 기본값이 이미 `true`이므로(생성자 바이트코드 `iconst_1` 확인) `spring.web.resources.add-mappings=false` 단독으로 실제 `NoHandlerFoundException` 디스패치를 재현할 수 있다(`NoHandlerFoundDispatchTest`). 관련 코드: `GlobalApiExceptionHandler.handleNoHandlerFound()`, `CustomErrorController.isAdminPath()`. 상세 설계 결정·적대적 리뷰 4라운드 기록은 `adversarial-review/plan/PLAN-not-found-handling.md` 참조.

### Spring Data JPA 리포지토리를 `Mockito.spy()`로 감싸면 `UnfinishedStubbingException`이 난다 (2026-08-11)

#### 오류 메시지

```
java.lang.IllegalStateException
	Caused by: org.mockito.exceptions.misusing.UnfinishedStubbingException
	at (doAnswer(...).when(repoSpy).someMethod(...) 호출 지점)
```

#### 원인

Spring Data JPA가 리포지토리 인터페이스(`MemberRepository` 등)의 실제 구현체로 런타임에 생성하는 것은 일반 POJO가 아니라 동적 프록시다. `Mockito.spy(realInstance)`는 해당 인스턴스의 런타임 클래스를 서브클래싱(바이트버디)해 스파이를 만드는데, Spring Data가 생성한 프록시 클래스를 다시 서브클래싱하는 과정이 Mockito의 스터빙 상태 추적과 충돌해 `doAnswer(...).when(spy).method(matchers)` 형태의 스터빙 중에 `UnfinishedStubbingException`이 발생한다. 순수 POJO나 일반 `@Component` 구현체(예: `LocalDiskFileStorage`)를 스파이할 때는 이 문제가 재현되지 않는다 — Spring Data 리포지토리 인터페이스에 한정된 증상이다.

`ProfileImageMigrationRunnerIntegrationTest`의 동시성 보조 검증 테스트(`run_concurrentRunners_migratesExactlyOnce`)에서, `MemberRepository`의 특정 메서드 호출만 계측(barrier 동기화 + 실제 위임 호출 성공/예외 기록)하기 위해 `Mockito.spy(memberRepository)`를 시도하다가 실측으로 재현됐다(적대적 리뷰 3·4라운드에서 "Spring Data 동적 프록시에 대한 `callRealMethod()` 위임이 문서화된 계약이 아니다"라고 지적했던 우려가 실제 오류로 나타난 사례).

#### 해결 방법

`spy()` 대신 순수 `mock(MemberRepository.class)`을 만들고, 계측이 필요한 메서드는 `doAnswer` 안에서 **원본 리포지토리 빈(`@Autowired`로 별도 보관한 참조)을 명시적으로 호출**하도록 전부 위임한다. 계측 대상(mock)과 실제 위임 대상(원본 빈)을 프록시 서브클래싱 없이 완전히 분리하면 문제가 사라진다. 반면 일반 구체 클래스(`FileStorage`의 `LocalDiskFileStorage` 구현체 등)는 `Mockito.spy()` + `invocation.callRealMethod()`가 표준적으로 안전하다.

**검증**: 위 방식으로 전환 후 `ProfileImageMigrationRunnerIntegrationTest` 4개 테스트 전부 통과(`./gradlew test --tests`), 락이 실제로 두 동시 실행 중 하나만 이관을 완료하고 다른 하나는 스킵함을 로그로 확인. 관련 코드: `ProfileImageMigrationRunnerIntegrationTest.run_concurrentRunners_migratesExactlyOnce()`. 상세 설계 결정·적대적 리뷰 5라운드 기록은 `adversarial-review/plan/PLAN-profile-image-storage.md` "후속 작업 계획" 섹션 참조.

---

# 정리

본 프로젝트는 단순 기능 구현뿐 아니라  
실제 개발 환경에서 발생할 수 있는 문제를 직접 경험하고 해결했습니다.
