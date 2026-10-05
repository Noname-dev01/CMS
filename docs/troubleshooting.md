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

### `docker run`으로 컨테이너 내부 경로를 직접 인자로 넘기면 Windows Git Bash(MSYS)가 host 경로로 잘못 치환한다 (2026-08-12, PLAN-db-backup.md 구현 중)

#### 오류 메시지

```
tar: C\:/Program Files/Git/source: Cannot open: No such file or directory
tar: Error is not recoverable: exiting now
```
또는
```
df: 'C:/Program Files/Git/target': No such file or directory
```

#### 원인

MSYS(Git Bash)는 `/`로 시작하는 커맨드라인 인자를 자동으로 Windows 경로로 바꾸는 휴리스틱을 갖고 있다. `docker exec ... sh -c '...'`처럼 경로가 **컨테이너 안의 셸이 해석할 문자열 안에 들어있으면** 영향받지 않지만, `docker run --rm -v vol:/source:ro image tar czf - -C /source .`처럼 `/source`가 **`docker run`에 직접 전달되는 별도 인자**면 MSYS가 이를 "호스트 절대 경로"로 오인해 `C:/Program Files/Git/source` 같은 존재하지 않는 host 경로로 바꿔버린다. `docker run -v vol:/path` 형태의 볼륨 마운트 지정 자체는 `:`로 시작 문자가 다르기 때문에 영향받지 않는다 — 문제는 어디까지나 **컨테이너 내부 경로를 가리키는 후속 커맨드 인자**(`tar -C /path`, `df -Pk /path` 등)뿐이다.

#### 해결 방법

영향받는 `docker run`/`docker exec` 호출 앞에 `MSYS_NO_PATHCONV=1`을 붙여 자동 변환을 끈다:

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v myvolume:/source:ro myimage tar czf - -C /source .
```

`sh -c '...'`로 감싼 스크립트 문자열 안의 경로는 애초에 영향받지 않으므로(컨테이너 안의 `sh`가 해석) 그 경로에는 이 플래그가 불필요하다. 텍스트만으로 하는 코드 리뷰(codex 등)는 이 상호작용을 잡아내지 못한다 — 실제 Windows Git Bash + Docker 환경에서 스크립트를 직접 실행해야만 드러난다.

### `sha256sum` 출력 형식이 플랫폼(텍스트/바이너리 모드 기본값)에 따라 다르다 (2026-08-12, PLAN-db-backup.md 구현 중)

#### 증상

자체 생성한 `sha256sum`을 정규식으로 검증하는 로직이 스스로 만든 정상 체크섬 파일도 형식 불일치로 거부한다.

#### 원인

GNU coreutils `sha256sum`은 텍스트 모드일 때 `<해시>  <파일명>`(스페이스 2개), 바이너리 모드일 때 `<해시> *<파일명>`(스페이스+별표) 형식을 출력한다. **Windows Git Bash(MSYS)는 기본이 바이너리 모드, 대부분의 Linux 배포판은 기본이 텍스트 모드**다 — 같은 `sha256sum file1 file2 > SHA256SUMS` 명령이라도 플랫폼에 따라 출력 형식이 갈린다.

#### 해결 방법

체크섬 파일 형식을 직접 정규식으로 검증할 때는 두 형식을 모두 허용한다:

```bash
grep -qE "^[0-9a-f]{64} [ *]<파일명>\$" line   # 스페이스 뒤 공백 또는 별표 모두 허용
```

### `set -e` 상태에서 `EXIT` 트랩의 마지막 명령이 조건부로 실패하면 트랩 자체의 실패가 스크립트 종료 코드를 덮어쓴다 (2026-08-12, PLAN-db-backup.md 구현 중)

#### 증상

`prod-backup.sh`가 실제로는 완전히 성공(모든 산출물 생성·무결성 검증 통과, `✅ 백업 완료` 출력)했는데도 호출자(`prod-restore.sh`)가 "안전 백업 생성 실패"로 판정하고 복구를 중단한다. **이 버그가 있는 채로는 복구가 단 한 번도 성공할 수 없었다** — 계획 리뷰 6라운드(codex 적대적 리뷰)로도 잡히지 않고 실기 검증 중에야 발견됐다.

#### 원인

트랩 함수의 마지막 문장이 다음과 같은 형태였다:

```bash
cleanup() {
  ...
  [ "$lock_acquired" = "1" ] && { rmdir "$LOCK_DIR" 2>/dev/null || echo "경고"; }
}
trap 'cleanup $?' EXIT
```

`lock_acquired`가 `"1"`이 아닌 경로(예: 다른 스크립트가 내부 호출해 잠금 획득 자체를 건너뛴 경우)에서는 `[ ... ]`가 거짓이 되고 `&&`가 단락평가돼 `{ ... }`는 실행되지 않는다 — 이때 **이 bare `[ cond ] && cmd` 문장 자체가 함수의 마지막 실행 문장이라 그 진위값(거짓=1)이 그대로 `cleanup()` 함수의 반환값이 된다.** `set -e` 활성 상태에서 `EXIT` 트랩의 실행 결과가 비정상(0이 아님)이면, 트랩 실행 전에 이미 결정돼 있던 원래 종료 코드(여기서는 성공, 0)가 트랩 자신의 실패로 **덮어써진다** — 스크립트는 실제로 성공했는데도 호출자에게는 실패(1)로 보고된다.

최상위(트랩이 아닌) 위치의 동일한 `[ cond ] && cmd` 패턴은 이 문제가 없다 — AND-OR 리스트에서 `&&`/`||`의 마지막 항목이 아닌 명령의 실패는 `set -e`를 트리거하지 않기 때문이다(별도로 직접 확인). 문제는 오직 **트랩 함수 자체의 반환값**이 스크립트의 최종 종료 코드에 영향을 주는 `EXIT` 트랩이라는 특수한 위치에서만 발생한다.

#### 해결 방법

트랩 함수의 마지막 문장을 조건부로 실패할 수 있는 bare `[ ] && cmd` 대신 `if ... fi`로 바꾸고, 함수 끝에 명시적으로 `return 0`을 추가해 트랩이 항상 성공으로 끝나도록 보장한다:

```bash
cleanup() {
  ...
  if [ "$lock_acquired" = "1" ]; then
    rmdir "$LOCK_DIR" 2>/dev/null || echo "경고"
  fi
  return 0   # EXIT 트랩은 항상 성공으로 끝나야 원래 종료 코드가 그대로 전파된다
}
```

**검증 방법**: `cleanup() { [ "$lock_acquired" = "1" ] && { echo ok; }; }; trap cleanup EXIT; echo done` 형태의 최소 재현으로 `echo $?`가 1이 되는지 직접 확인 후 고친다. EXIT 트랩이 있는 스크립트는 트랩 함수의 **모든 실행 경로**가 명시적으로 성공(`return 0` 또는 마지막 명령이 항상 성공)으로 끝나는지 반드시 점검한다.

### Windows에서 TCP backlog를 포화시켜도 후속 connect()가 즉시 성공한다 — connection timeout을 결정적으로 재현하지 못함 (감사 M-02, 2026-09-27)

#### 오류 메시지

없음(예외 아님) — SMTP connection timeout(`mail.smtp.connectiontimeout`)을 소켓 fault로 자동 검증하려던 테스트 설계가 예상과 다르게 동작한 사례.

#### 원인

`ServerSocket(port, backlog=0, ...)`을 만들고 `accept()`를 전혀 호출하지 않은 채 여러 클라이언트를 연속으로 `connect()`시켜 accept 큐를 포화시키면, 이후의 `connect()` 시도가 TCP handshake 단계에서 블로킹되다 `connectiontimeout`으로 종료될 것이라 예상했다(Linux 기반 네트워크 fault 테스트 기법으로 흔히 인용됨). 실제로 Windows(loopback, JDK 17)에서 최소 재현 코드로 실측한 결과 `backlog=0`이어도 필러 연결 5개 + 추가 probe 연결까지 전부 0~1ms 만에 즉시 성공했다 — Windows의 TCP/IP 스택이 `backlog` 힌트를 사실상 무시하거나 더 큰 기본 큐로 대체하는 것으로 보인다(커널 구현이 OS별로 다름). `connect()`가 즉시 성공해버리므로 connection timeout이 전혀 트리거되지 않는다.

#### 해결 방법

이 기법은 OS 커널의 TCP accept 큐 구현에 의존하는 network fault 재현이라 크로스플랫폼(Windows dev / Linux CI) 결정성을 보장할 수 없다는 것을 실측으로 확인 후, connection timeout의 자동 시험 자체를 범위에서 제외했다 — 대신 **설정 전달 계약**(YAML `${VAR:default}` → compose `${VAR:-default}` → 실제 `JavaMailSenderImpl.getJavaMailProperties()`에 `mail.smtp.connectiontimeout` 값이 정확히 반영되는지)만 검증하고, 실제 TCP 연결 지연 재현은 Gate F(운영 staging 검증)의 수동 확인으로 남겼다. 자동화하려면 실제 방화벽 드롭·라우팅 블랙홀 등 OS 외부의 네트워크 fault 주입 도구가 필요하며, 이번 범위(PR 5, M-02)를 벗어난다.

**검증**: 최소 재현 코드(`ServerSocket(0, 0, loopback)` + 필러 커넥션 5개 + probe 커넥션, 전부 `System.nanoTime()`으로 경과 시간 측정)를 `java`/`javac` 직접 실행으로 확인 — 전 연결이 0~1ms. 관련 결정 근거는 `adversarial-review/remediation-plan.md` "PR 5 실행 기록" 참조.

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

### 동시성 시험이 잠금을 제거해도 통과한다 — Hikari 기본 풀(10)이 경합을 한도와 같은 수로 제한하고 "병렬로 시작"만으로는 요청이 겹치지 않는다 (2026-10-05, PLAN-admin-message.md 구현 중 변이 실험으로 발견)

#### 증상

쪽지 발송 한도의 동시성 시험(같은 발신자의 병렬 12건 중 정확히 10건만 201)이 처음부터 통과했는데, 발신자별 상태 행 잠금 호출을 **주석 처리한 변이 실험에서도 통과**했다 — 잠금이 한도를 지킨다는 증거가 아니었다.

#### 원인 (두 가지)

1. **요청이 겹치지 않는다.** `CountDownLatch`로 12개 스레드를 동시에 풀어도 각 요청의 처리 시간(수 ms)이 짧고 스레드 시작이 어긋나, 집계(COUNT)와 저장(INSERT) 사이의 경합 창에 두 요청이 함께 들어가지 못한다.
2. **HikariCP 기본 풀 크기 10이 동시 서비스 트랜잭션 수를 한도(10)와 우연히 같게 만든다.** 풀을 넘는 요청은 연결을 기다리므로 먼저 들어간 10건이 모두 COUNT=0을 읽고 10건을 저장하고, 나머지 2건이 COUNT=10을 보고 거부된다 — 잠금이 없어도 결과가 똑같다.

#### 해결 방법

- **경합 창을 강제로 넓힌다**: 리포지토리 프록시 advice(`BeanPostProcessor`)로 한도 집계 직후 120ms 지연을 주입한다.
- **한도를 풀 크기보다 낮추고 스레드를 풀 크기 아래로 둔다**: `cms.message.send-per-minute=4`(`@SpringBootTest(properties=...)`)·스레드 8 — 잠금이 없으면 8건이 모두 성공해 실패한다. 이 조합에서 잠금 제거 변이가 시험을 실제로 실패시킴을 확인했다.

#### 교훈

동시성 시험은 **통과만 보지 말고 보호 장치를 제거한 변이 실험으로 실패하는지** 확인한다. 이 프로젝트에서 같은 방식으로 감도를 확인한 변이는 쪽지에 모두 기록돼 있다(`com.cms.admin.message`의 `CLAUDE.md` "시험").

### 시험이 DB `NOW()`로 시각을 넣으면 앱의 `Clock`(KST) 창 밖에 놓인다 — Testcontainers MariaDB는 UTC다 (2026-10-05, PLAN-admin-message.md 구현 중)

#### 증상

"외부 RR 스냅샷에서 호출해도 서비스가 직전 커밋된 발송 이력 10건을 본다"는 시험이 `Expecting code to raise a throwable`로 실패했다 — 시험이 JDBC로 넣은 이력(`NOW(6)`)이 서비스의 1분 한도 창에 잡히지 않았다. 일일 한도 시험(`NOW(6) - INTERVAL 12 HOUR`)은 **시차 덕분에 우연히 통과**하고 있었다.

#### 원인

앱의 시각 원천은 KST `Clock` 하나다(`AppConfig.clock()` — 루트 `CLAUDE.md`). 쪽지 한도 창도 `LocalDateTime.now(clock) - 1분`으로 계산하는데, 시험이 `NOW(6)`으로 넣은 값은 **DB 서버(컨테이너) 시간대(UTC)**의 naive 시각이라 앱 기준으로 9시간 과거다.

#### 해결 방법

시험 데이터의 시각은 DB 함수가 아니라 **주입한 `Clock`에서 만든 `LocalDateTime` 파라미터**로 넣는다(`LocalDateTime.now(clock).minusHours(25)` 등).

### MariaDB 10.11에는 `@@transaction_isolation`이 없다 — 변수명은 `@@session.tx_isolation`이다 (2026-10-05, PLAN-admin-message.md 2라운드 리뷰 + 구현 중)

`transaction_isolation`은 11.1.1에서 도입된 이름이다. 10.11(운영·시험 이미지 10.11.19)에서 `SELECT @@transaction_isolation`은 `Unknown system variable`로 실패하고, 값은 `READ-COMMITTED`·`REPEATABLE-READ`(하이픈) 형식이다. 트랜잭션 계약 시험은 서비스가 쓰는 연결에서 `@@session.tx_isolation`을 읽는다. 이미지를 11.x로 올릴 때 이 질의와 `innodb_snapshot_isolation` 기본값 변화(11.6+ ON 방향)를 함께 재확인한다. 참고로 `innodb_snapshot_isolation`은 10.11.9+에서 존재하고 10.11.19의 기본값은 `OFF`다.

### `innodb_snapshot_isolation=ON`에서 REPEATABLE READ 트랜잭션의 잠금 문장이 오류 1020으로 실패하고 `PessimisticLockingFailureException`으로 변환되지 않는다 (2026-10-05, PLAN-admin-message.md 2라운드 리뷰 + 변이 실험으로 재현)

#### 증상

이 설정이 ON이고 트랜잭션이 일관 읽기로 스냅샷을 연 뒤, **스냅샷 이후 다른 트랜잭션이 커밋한 행**을 잠그려 하면(외래키 검사의 공유 잠금 포함) MariaDB가 오류 1020(`Record has changed since last read`)을 낸다. 쪽지 발송에서 집계(COUNT) 뒤 수신자 행이 수정·커밋되면 INSERT의 FK 검사가 실패한다. `GlobalApiExceptionHandler`는 `PessimisticLockingFailureException`만 409로 처리하고 1020은 Hibernate·Spring 변환 규칙에 없어 `JpaSystemException`으로 올라와 **500**이 될 수 있었다.

#### 해결 방법

- 쪽지 발송·삭제 서비스는 `@Transactional(propagation = REQUIRES_NEW, isolation = READ_COMMITTED)`다. RC에서는 일관 읽기가 문장마다 최신 커밋을 보고 스냅샷 격리 오류가 적용되지 않는다. `REQUIRES_NEW`가 필수인 이유는 `REQUIRED`가 기존 트랜잭션에 **참여하면서 격리 수준을 무시**하기 때문이다.
- 방어로 `GlobalApiExceptionHandler`가 `UncategorizedDataAccessException`·`TransactionSystemException`의 **원인 체인에서 MariaDB 오류 1020·1213**을 찾아 409로 매핑한다(없으면 기존 catch-all 500).

#### 검증

`AdminMessageSnapshotIsolation{Off,On}IntegrationTest`(`connection-init-sql`로 풀 전체에 설정, 전용 컨텍스트 + `@DirtiesContext`). 변이 실험으로 발송 격리를 `REPEATABLE_READ`로 바꾸면 **ON 컨텍스트에서 이 오류가 실제로 재현돼** 시험이 실패한다. `SET SESSION innodb_snapshot_isolation`을 공유 컨텍스트의 풀에 설정하면 HikariCP가 임의 세션 변수를 복원하지 않아 다른 시험을 오염시키므로 전용 컨텍스트로 격리한다.

### 이벤트 리스너 순서가 설정과 다르게 동작함 — 클래스 `@Order`는 메서드 리스너에 적용되지 않고 `AFTER_COMMIT`은 `AFTER_COMPLETION`보다 항상 먼저 실행된다 (2026-10-05, PLAN-admin-notification.md 계획 리뷰 2라운드 + 구현 중 발견)

#### 증상 (구현 전에 계획 리뷰와 코드 확인으로 발견 — 운영 사고 아님)

알림 저장(`AFTER_COMMIT`, 연결·락 대기가 길어질 수 있음)이 **세션 만료·권한 캐시 무효화를 막지 않도록** "알림 리스너에 `@Order(30)`을 붙여 기존 리스너 뒤에 실행한다"고 계획했다. 그대로 구현하면 알림이 오히려 **먼저** 실행되거나, 순서 설정 자체가 적용되지 않는다.

#### 원인 (두 가지)

1. **메서드 리스너는 메서드의 `@Order`만 읽는다.** `@TransactionalEventListener` 메서드(`ApplicationListenerMethodAdapter`)는 클래스에 붙은 `@Order`를 무시하고, 메서드에 없으면 `LOWEST_PRECEDENCE`다. 기존 `AdminSessionRevokeListener`(`@Order(10)`)·`AdminAccountAutoLockListener`(`@Order(20)`)는 `@Order`가 **클래스**에 있어 사실상 우선순위가 없었다 — 그래서 알림에 `@Order(30)`을 **메서드**에 붙이면 기존 두 리스너보다 먼저 실행된다.
2. **`AFTER_COMMIT` 콜백은 모두 실행된 뒤에야 `AFTER_COMPLETION`이 실행된다.** 권한 캐시 무효화(`PermissionChangedListener`)는 커밋·롤백·결과 불명을 모두 덮으려고 `AFTER_COMPLETION`에 있다. 같은 트랜잭션의 알림 저장이 `AFTER_COMMIT`이므로 `@Order`를 어떻게 매겨도 **알림 저장이 끝날 때까지 캐시 무효화가 늦어진다**(알림 저장이 연결 풀 포화·락 대기로 막히면 회수한 권한이 그동안 계속 허용된다).

#### 해결 방법

- 순서가 필요한 리스너의 **메서드에 `@Order`를 명시**한다: 세션 만료 `10`, 감사 `20`, 권한 캐시 무효화 `10`, 알림 `100`(클래스 `@Order`는 그대로 두되 메서드 값이 실제 순서를 정한다는 주석을 남김).
- 캐시 무효화를 **`AFTER_COMMIT`(`@Order(10)`)에도 추가**하고 기존 `AFTER_COMPLETION` 무효화는 롤백·결과 불명용 백스톱으로 유지한다. 무효화는 멱등이라 커밋 성공 경로에서 `invalidate()`가 2회 호출돼도 안전하다 — 단, 기존 시험의 `verify(cache, times(1)).invalidate()`는 커밋 성공 경로에서 `atLeastOnce()`로 바꿔야 한다(롤백·결과 불명 경로는 `AFTER_COMPLETION`뿐이라 `times(1)` 유지).

#### 검증

`NotificationGenerationIntegrationTest.sessionRevokeAndCacheInvalidation_runBeforeNotificationSave`가 세션 만료·캐시 무효화·알림 저장 호출 순서를 단언한다. 변이 실험으로 `PermissionChangedListener.onCommitted`의 `@Order(10)`을 제거하면 이 시험이 실패함을 확인했다.

#### 교훈

`@Order`는 **어느 위치에 붙었는지**(클래스 vs 메서드)와 **어느 단계의 콜백인지**(`AFTER_COMMIT` vs `AFTER_COMPLETION`)를 함께 봐야 의미가 있다. 순서 계약은 설정을 믿지 말고 호출 순서를 직접 단언하는 시험으로 고정한다.

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

### PATCH 본문의 `null`(의미 있는 값)을 자바 `Long`으로 받으면 `{}`·`""`·`10.9` 같은 잘못된 입력이 실제 최상위 승격·이동이 된다 (2026-09-30, PLAN-menu-move.md 계획 리뷰 중 발견)

#### 증상 (구현 전에 발견 — 운영에 나간 적 없음)

메뉴 부모 이동 API의 본문 `{ "upMenuNo": null }`은 "최상위로 승격"이라는 **의미 있는 값**이다. 요청 DTO의 `upMenuNo`를 `Long`으로 받으면:

- 본문 `{}`(필드 없음)와 `{"upMenuNo": null}`(명시적 null)이 둘 다 자바 `null`이라 **구분되지 않는다** — 빈 본문·오타 본문이 메뉴를 최상위로 올린다.
- 더 나쁘게는 Jackson의 기본 **강제 변환**이 setter 호출 **전에** 일어나 `""`·`" "`·`"null"` 문자열을 `null`로, `10.9`를 `10`으로 바꾼다. 그러면 `@JsonSetter`로 "명시 여부" 플래그를 기록하더라도 `{"upMenuNo": ""}`가 **명시 여부=true인 승격 요청**이 된다. 일반적인 `"abc"` 타입 오류 테스트로는 잡히지 않는다.

#### 해결 방법

필드를 `JsonNode`로 받아 **원본 토큰**을 그대로 검사한다(자바 `null` = 필드 없음, `NullNode` = 명시적 null). 검증은 `@AssertTrue @JsonIgnore` 메서드로 하고 "명시 여부"를 담는 별도 필드는 두지 않는다(클라이언트가 JSON으로 조작할 수 없게).

```java
private JsonNode upMenuNo;   // null=필드 없음, NullNode=명시적 null

@JsonIgnore @AssertTrue(message = "upMenuNo는 필수이며 정수 또는 null만 허용됩니다.")
public boolean isUpMenuNoValid() {
    if (upMenuNo == null) return false;                       // 필드 없음
    if (upMenuNo.isNull()) return true;                        // 최상위 승격
    return upMenuNo.isIntegralNumber() && upMenuNo.canConvertToLong();   // "10"·10.9·true·[]·{}·범위 초과는 모두 거부
}
```

검증: `MenuControllerTest`가 `{}`·`""`·`" "`·`"null"`·`"10"`·`10.9`·`10.0`·`true`·`[]`·`{}`·범위 초과 정수를 모두 400 `VALIDATION_ERROR`(서비스 미호출)로, 명시적 `null`을 승격으로 확인하고, 실서버에서도 같은 본문이 400이며 대상 메뉴가 그대로임을 확인했다. 교훈: PATCH에서 `null`이 값 자체로 의미를 가지면 자바 래퍼 타입으로 받지 말고 원본 토큰으로 받는다.

---

### 부모가 바뀔 수 있는 트리에서는 "스냅샷으로 정한 잠금 목록"이 어떤 정적 잠금 순서로도 교착을 완전히 피하지 못한다 (2026-09-30, PLAN-menu-move.md 계획 리뷰 1라운드)

#### 상황

메뉴 부모 이동을 설계하면서 처음에는 전역 잠금 순서 `(−깊이, menuNo)` + 사전 읽기 + `TransactionTemplate` 구조로 **무교착**을 주장했다. 적대적 리뷰가 반례를 냈고 단계별로 검증해 성립함을 확인했다.

| 순서 | 실행 |
|---|---|
| 1 | 재조정 R이 최상위 형제 `[N=10, T=20]`을 읽고(스냅샷) 첫 잠금 전에 정지 |
| 2 | 이동 M이 `10→20` 순서로 잠그고 T를 N 아래로 옮겨 커밋 |
| 3 | R이 낡은 목록대로 N(10)을 잠금 |
| 4 | 재활성화 U가 T(20)를 잠근 뒤 부모 N(10)을 기다림(자식→부모) |
| 5 | R이 다음 행 T(20)를 기다림 → **R은 N을, U는 T를 잡고 서로 기다린다** |

#### 원인

`(−깊이, menuNo)`는 **계층이 고정된 동안에만** 성립하는 순서다. 재조정은 "스냅샷으로 정한 잠금 목록"을 그 뒤에 바뀐 계층에서도 그대로 쓰므로, 부모가 바뀔 수 있는 한 정적 잠금 순서로는 "스냅샷 → 잠금" 구조의 교착을 막을 수 없다. 부모 재검사 한 줄로도 못 막는다(T를 잠그기 전에 교착한다).

#### 해결 방법 (방향 전환)

- 사전 읽기·별도 트랜잭션·전역 순서 구조를 **폐기**하고 잠금 순서를 요청의 두 id `menuNo` 오름차순으로 단순화했다. 최상위 형제끼리의 일반 경합(루트 재조정 ↔ 최상위 이동)은 이 순서로 무교착이다.
- 교착은 InnoDB 탐지로 한쪽을 롤백하고 기존 `GlobalApiExceptionHandler`가 **409 `RESOURCE_CONFLICT`** 로 응답하는 계약으로 **허용**한다. 정합성(불변식)은 모든 검사를 **잠근 최신 값**으로 하는 것으로 보장하고, 재조정은 잠근 형제의 부모를 재검사한다. 전역 뮤텍스는 모든 변경 경로를 고쳐야 해 채택하지 않았다.
- 알려진 교착 계열: ① 낡은 스냅샷 재조정 + 이동 + 재활성화(3자), ② 무변경 이동 ↔ 재활성화, ③ 이동·재활성화가 둘씩 얽힌 4자 이상, ④ **결국 거부될 이동 ↔ 재활성화**(검증이 두 잠금을 얻은 뒤라 검증 전에 교착 가능).

검증: 일반 경합은 무교착을, 알려진 교착은 "잠금 예외는 정확히 하나, HTTP 409는 피해자에 따라 하나 또는 둘(R이 피해자면 U 성공·U가 피해자면 R이 부모 불일치로 409), 이동은 유지"를 `MenuConcurrencyIntegrationTest`가 고정한다. 잠금 순서를 어기면(변이 실험) 일반 경합 테스트가 교착으로 실패한다. 교훈: "무교착" 주장은 스냅샷과 잠금 사이에 구조가 바뀔 수 있는지부터 따지고, 증명할 수 없다면 가용성 문제(409 재시도)로 계약을 명시한다.

---

### jstree `check_callback`에서 `more.dnd`를 요구하면 정상 드롭도 최종 검사에서 거부된다 (2026-09-30, PLAN-menu-reorder.md 계획 리뷰 중 발견)

#### 증상 (구현 전에 발견 — 운영에 나간 적 없음)

메뉴 트리 드래그 앤 드롭 계획이 `core.check_callback`을 "`operation === 'move_node'`이고 **`more.dnd`이고** 같은 부모이고 `more.pos !== 'i'`일 때만 true"로 설계했다. 이대로면 드래그 중에는 이동 가능 표시가 뜨는데도 마우스를 놓으면 이동이 거부되고, 저장을 담당하는 `move_node.jstree` 이벤트도 발생하지 않아 서버 호출이 한 번도 나가지 않는다.

#### 원인

jstree 3.3.12는 `check_callback`을 두 단계에서 호출하며 `more`에 담는 값이 다르다.

| 단계 | `more`에 담기는 값 |
|---|---|
| 드래그 중 위치 검사(dnd 플러그인) | `{ dnd: true, pos: 'b'\|'a'\|'i', ... }` |
| 드롭 후 `move_node()` 내부 최종 검사 | `{ core: true, origin, is_multi, is_foreign }` — **`dnd`·`pos`가 없다** |

설치된 CDN 빌드(`cdnjs .../jstree/3.3.12/jstree.min.js`)의 `move_node` 내부 `this.check("move_node", ...)` 호출부에서 `{core:!0,origin:i,is_multi:...,is_foreign:...}`만 넘기는 것을 직접 확인해 특정했다(적대적 리뷰 1라운드 P1 지적을 소스로 검증).

#### 해결 방법

"같은 부모" 조건(`parent.id === node.parent`)은 **두 단계 모두**에 적용하고, `more.dnd`는 요구하지 않는다. "안으로 넣기" 차단(`more.dnd && more.pos === 'i'`)만 드래그 중 검사에 적용한다. `pos === 'i'`일 때 `parent`는 대상 노드 자신이 되어 같은 부모 조건에서 이미 거부되며, jstree는 거부된 위치 대신 같은 부모의 앞/뒤 위치로 대체한다.

```js
check_callback: function (operation, node, parent, position, more) {
    if (operation !== 'move_node') return false;
    if (!canDrag()) return false;
    if (!parent || parent.id !== node.parent) return false;      // 같은 부모만 — 두 단계 공통
    if (more) {
        if (more.is_multi || more.is_foreign) return false;
        if (more.dnd && more.pos === 'i') return false;          // 안으로 넣기 — 드래그 중에만 존재하는 정보
    }
    return true;
}
```

검증: Playwright에서 **표시만이 아니라 실제 마우스 드롭 → `PUT /admin/api/menus/order` 요청 발생 → 새로고침 후 순서 유지**까지 확인했다. 검사 함수에 계측을 넣어 `pos=i, parent=대상 노드 → false`(안으로 넣기 거부)와 다른 부모 위에 놓았을 때 PUT이 0회임도 확인했다. 교훈: 라이브러리 콜백의 인자는 호출 단계마다 다를 수 있으므로, 조건을 설계할 때 "어느 단계에서 어떤 값이 오는지"를 소스로 확인하고 브라우저 실기에서 최종 결과(요청 발생)까지 검증한다.


> 2026-10-01 후속: 위 `check_callback`은 PLAN-menu-structure-apply.md PR C에서 "같은 부모는 항상 허용, 다른 부모는 순환·깊이만 검사"로 바뀌었고 `PUT /order`도 삭제됐다. **두 단계 호출(`more.dnd`는 드래그 중에만 존재)** 교훈은 그대로 유효하다.
---

### 드래그 초안 화면을 자동 검증할 때 스크롤 영역 밖 대상은 "거부"와 구분되지 않고, 남은 초안의 이탈 경고(beforeunload)가 후속 스크립트를 막는다 (2026-10-01, PLAN-menu-structure-apply.md PR C 실기 검증)

#### 증상

Playwright로 "깊이 초과·순환 드롭은 거부된다"를 검증하다가, 허용돼야 하는 양성 대조군(3단 허용)조차 "변경 없음"으로 보여 거부 결과를 믿을 수 없었다. 또 초안(반영 대기)이 남은 채 다음 스크립트가 `page.goto`를 호출하자 `beforeunload` 대화상자가 떠서 스크립트가 멈추거나, 대화상자 핸들러를 달아 두면 이어서 **다음 단계(반영 버튼)까지 실행돼 서버 구조가 바뀌었다.**

#### 원인

- `.tree-container`가 `overflow:auto`·`height: calc(100vh - 200px)`라 기본 뷰포트에서는 트리 아래쪽 노드(QA-C 등)가 보이는 영역 밖이다. 마우스 드롭 좌표가 대상에 닿지 않으면 jstree가 아무 일도 하지 않으므로 "거부"와 "닿지 않음"이 같은 결과(변경 없음)가 된다.
- 초안이 있으면 `beforeunload`로 이탈 경고를 띄우도록 의도했으므로(반영 안 한 변경 보호) 자동화가 초안을 정리하지 않은 채 이동하면 대화상자가 뜬다. `page.on('dialog', accept)` 핸들러는 스크립트마다 누적 등록돼 "이미 처리된 대화상자" 오류도 낸다.

#### 해결 방법

- 검증 전에 **뷰포트를 크게**(예: 1400×1600) 잡아 모든 노드가 보이게 하고, **양성 대조군(허용되는 드롭)을 먼저 확인**해 좌표가 닿는지 입증한 뒤 거부 케이스를 본다.
- 거부 규칙은 마우스 위치에 의존하지 않게 `$('#menuTree').jstree(true).check('move_node', node, parent, 'last')`를 **직접 호출**해 결정적으로 검증한다(허용 4·거부 4 케이스를 이렇게 확인).
- 각 시나리오 끝에 **[취소]로 초안을 정리**해 다음 `goto`에서 대화상자가 뜨지 않게 하고, 대화상자 핸들러는 한 번만 등록한다.

**교훈**: "거부됨"을 주장하려면 같은 조건에서 "허용됨"이 성공하는 대조군이 먼저 있어야 한다. 이탈 경고 같은 보호 장치는 자동화에서 부작용(다음 단계 진행)을 낳을 수 있으므로 시나리오 사이에 상태를 명시적으로 정리한다.

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

### 일반적인 클라이언트 입력 오류(경로 변수 타입 불일치·미지원 메서드/미디어타입/Accept)가 500으로 오분류됨 (감사 M-03, 2026-09-26)

#### 오류 메시지

```
GET /admin/api/menus/abc (경로 변수 id는 Long) 요청 시
{"timestamp":"...","path":"/admin/api/menus/abc","code":"INTERNAL_ERROR","message":"서버 오류가 발생했습니다."}
같은 JSON 500이 응답됨 (405/415/406 상황도 동일하게 500으로 떨어짐)
```

#### 원인

바로 위 항목("핸들러가 아예 없는 경로가 404가 아니라 500으로 응답됨")과 같은 근본 패턴이 반복됐다 — `GlobalApiExceptionHandler`의 `Exception` catch-all이 `MethodArgumentTypeMismatchException`(경로 변수/쿼리 파라미터 타입 불일치)·`HttpRequestMethodNotSupportedException`(미지원 HTTP 메서드)·`HttpMediaTypeNotSupportedException`(미지원 요청 Content-Type)까지 전부 잡아 정상적인 클라이언트 입력 오류를 서버 오류로 바꿔버렸다. 계획 리뷰(codex CLI) 1라운드에서 네 번째 예외인 `HttpMediaTypeNotAcceptableException`(응답 형식/Accept 협상 실패)도 원래 계획의 세 handler에 빠져 있어 같은 catch-all로 새는 구멍이 추가로 발견됐다 — `HttpMediaTypeNotSupportedException`(요청 Content-Type 문제)과는 서로 다른 예외라는 점이 계획 단계에서는 간과됐다.

부수적으로 발견된 문제 2건: (1) 신규 handler를 추가해도 `@RestControllerAdvice`만으로는 `Accept: text/html` 요청에 JSON 응답이 보장되지 않는다 — 기존 handler 대부분이 응답 `Content-Type`을 명시하지 않았다. (2) 기존 `BindException` 처리(`buildValidationMessage()`)가 `FieldError.getDefaultMessage()`를 그대로 응답해, 타입 변환 실패(예: 열거형 필드에 정의되지 않은 값)로 생기는 Spring 기본 메시지에 입력값 원문이 그대로 반영되는 실제 경로가 있었다(`GET /admin/api/members?userType=FAKE_TOKEN_MARKER` 등).

#### 해결 방법

`GlobalApiExceptionHandler`에 4개의 좁은 `@ExceptionHandler`(400/405/415/406)를 `Exception` catch-all보다 먼저 추가했다. `MethodArgumentTypeMismatchException`은 `e.getName()`(파라미터명)만 메시지에 담고 잘못 입력된 값 원문은 재출력하지 않는다. `HttpRequestMethodNotSupportedException`은 `e.getSupportedHttpMethods()`로 실제 지원 메서드 집합을 `Allow` 헤더에 구성한다(`ResponseEntity.BodyBuilder.allow(HttpMethod...)`는 가변 인자라 `Set<HttpMethod>`를 그대로 넘기면 컴파일 오류 — `toArray(new HttpMethod[0])`로 변환해야 한다).

모든 오류 응답의 JSON Content-Type 보장은 `jsonError(status, path, code, message)` 공통 조립 메서드로 중앙화했다 — 기존 12개 handler 전부가 이 메서드를 거치도록 리팩터링해 개별 handler에서 `.contentType(...)` 누락 여지를 없앴다. `@ExceptionHandler(produces = "application/json")`로 handler 선택 자체를 제한하는 방식은 `Accept: text/html`에서 handler가 아예 선택되지 않는 별도 문제를 만들 수 있어 채택하지 않았다(계획 리뷰 2라운드에서 확인).

`buildValidationMessage()`는 `FieldError.isBindingFailure()`로 타입 변환 실패와 일반 Bean Validation 실패를 구분한다 — 문구·언어에 의존하지 않는 신뢰성 있는 판별 수단이며, `@Valid @ModelAttribute` 바인딩 실패(`MethodArgumentNotValidException`, `BindException`의 하위 타입)도 같은 공통 함수를 호출하므로 두 경로 모두 함께 보호된다. 타입 변환 실패로 판정되면 고정된 안전한 문구("입력값 형식이 올바르지 않습니다.")로 대체하고, 일반 Bean Validation 문구는 그대로 유지한다.

catch-all의 진단 로그는 `request.getMethod()`·`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`(라우트 패턴, 없으면 고정 `"unmatched"`)·`e.getClass().getName()`·`StackTraceElement[]` 상위 10개 프레임만 남긴다. 예외 인스턴스를 SLF4J 로거의 마지막 인자(Throwable)로 직접 넘기지 않은 것은, 그렇게 하면 로거가 `message`/`cause` 체인까지 자동 출력하기 때문이다 — 메일 본문·재설정 토큰을 담은 예외가 이 catch-all로 흘러올 수 있어 의도적으로 피했다. raw URI/query/body/header/cookie도 로그에 넣지 않는다.

**검증**: `GlobalApiExceptionHandlerTest`(순수 단위, ERROR 이벤트 정확히 1개·민감정보 미노출·Allow 헤더 구성·타입 변환 메시지 대체 검증)·`ApiErrorContractIntegrationTest`(실제 `SecurityConfig` 포함 400/405/406/415/500 요청 행렬, `Accept: text/html`에도 JSON 유지 회귀)·`MenuControllerTest`/`AdminMemberControllerTest`의 실제 프로덕션 엔드포인트 신규 케이스(경로 변수 타입 불일치, 검색 열거형 필드에 입력 표식을 담은 값) 전부 통과. `SPRING_PROFILES_ACTIVE=dev ./gradlew test` 전체 통과(신규 순증). 관련 코드: `GlobalApiExceptionHandler`. 상세 설계 결정·적대적 리뷰 2라운드 기록은 `adversarial-review/remediation-plan.md` "PR 3" 섹션 참조.

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

### `@WebMvcTest` 슬라이스에서 레이트리밋 버킷이 테스트 메서드 간에 공유돼 실행 순서에 따라 실패한다 (2026-08-12)

#### 오류 메시지

```
java.lang.AssertionError
  Expected: 200 OK
  Actual:   429 TOO_MANY_REQUESTS
```
(첫 요청부터 429가 나오거나, 반대로 소진돼야 할 요청이 계속 200으로 통과하는 등 테스트 실행 순서에 따라 증상이 달라짐)

#### 원인

`TokenBucketRateLimiter`(내부 Caffeine 캐시)는 `@WebMvcTest` 슬라이스 컨텍스트에서 싱글턴 Bean이고, Spring TestContext 프레임워크는 같은 테스트 클래스의 모든 `@Test` 메서드가 이 컨텍스트를 공유하게 한다. `MockMvc`가 기본으로 쓰는 원격 주소(`127.0.0.1`)를 그대로 두면, 같은 규칙을 건드리는 여러 테스트 메서드가 사실상 같은 버킷을 나눠 쓰게 되어 실행 순서(JUnit 5는 기본적으로 결정적 순서를 보장하지 않음)에 따라 테스트가 통과하거나 실패한다.

#### 해결 방법

`MockHttpServletRequest.setRemoteAddr(ip)`를 적용하는 `RequestPostProcessor`를 만들어 **테스트 메서드마다 서로 다른 IP**를 부여해 버킷을 격리한다.

```java
private static RequestPostProcessor from(String ip) {
    return request -> { request.setRemoteAddr(ip); return request; };
}
// ...
mockMvc.perform(get("/notices").with(from("10.10.10.1"))).andExpect(status().isOk());
```

**검증**: `RateLimitFilterTest`·`RateLimitResponseTest`·`PasswordResetControllerTest`의 레이트리밋 관련 테스트에 서로 다른 IP를 부여한 뒤 실행 순서를 바꿔도(`--tests` 단독 실행, 클래스 전체 실행 모두) 안정적으로 통과함을 확인. 관련 코드: `com.cms.config.RateLimitFilterTest`. 상세 설계는 `adversarial-review/plan/PLAN-public-endpoint-rate-limit.md` 참조.

### `build.gradle`의 전역 `CMS_RATE_LIMIT_ENABLED=false`가 레이트리밋을 검증하는 슬라이스 테스트에도 적용돼 필터가 항상 통과만 한다 (2026-08-12)

#### 오류 메시지

증상은 "예외 없음, 그러나 기대한 429가 절대 발생하지 않고 항상 200" — 별도 오류 메시지 없이 조용히 실패(assertion만 실패).

#### 원인

`build.gradle`의 `test` 태스크는 같은 IP로 반복 요청하는 기존 MockMvc 테스트 수백 개의 429 회귀를 막기 위해 `environment 'CMS_RATE_LIMIT_ENABLED', 'false'`를 전역 주입한다(PLAN-public-endpoint-rate-limit.md 결정). OS 환경변수는 Spring Boot 프로퍼티 우선순위상 `application.yml`보다 높으므로, 레이트리밋 자체를 검증하려는 슬라이스 테스트에서도 이 값이 그대로 적용돼 `cms.rate-limit.enabled`가 `false`로 바인딩되고, `RateLimitFilter`는 항상 `chain.doFilter()`만 호출한다.

#### 해결 방법

레이트리밋 동작을 실제로 검증하는 테스트 클래스에 `@TestPropertySource(properties = "cms.rate-limit.enabled=true")`를 명시해 전역 환경변수를 오버라이드한다. IDE에서 개별 테스트를 실행하면 이 Gradle 환경변수 자체가 적용되지 않아 `application.yml` 기본값(`enabled: true`)으로 동작하는 차이가 있다는 점도 함께 유의한다 — 반대로 레이트리밋과 무관한 기존 테스트를 IDE에서 개별 실행하면 예상치 못한 429를 만날 수 있다.

**검증**: `PasswordResetControllerTest`에 `@TestPropertySource`를 추가한 뒤 CSRF-레이트리밋 순서 검증 테스트(운영 설정 `capacity=5` 그대로 사용)가 안정적으로 통과함을 확인. 관련 코드: `com.cms.admin.member.controller.PasswordResetControllerTest`.

### 메뉴 일반 수정과 비활성화가 같은 행에서 겹치면 방금 커밋된 비활성화가 되돌아간다 (감사 M-04, 2026-09-27)

#### 오류 메시지

```
스레드 A: PATCH /admin/api/menus/1 {"menuName": "새 이름"} (useYn 미포함)
스레드 B: PATCH /admin/api/menus/1 {"useYn": false} (비활성화)
A 조회 → B 비활성화 커밋(useYn=false) → A가 낡은 useYn=true를 그대로 재반영해 커밋
→ 최종 useYn=true (B의 비활성화가 유실됨, lost update)
```

별도 오류·예외 없이 조용히 실패(assertion만 실패) — 독립 검증이 실제 MariaDB 서비스 경합으로 재현해 확정했다.

#### 원인

`MenuService.updateMenu()`가 `useYn=false`(비활성화) 요청일 때만 `menuRepository.findByIdForUpdate()`(`PESSIMISTIC_WRITE`)를 쓰고, 그 외 일반 수정(이름·URL 등, `useYn` 필드를 생략/null로 보낸 요청)은 잠금 없는 `findById()`를 썼다. 같은 서비스의 한 메서드가 분기에 따라 잠금 유무를 달리한 것 — 위 Top5(2026-09-05)의 `AdminMemberService.updateMyInfo` 행 잠금 누락(감사 H-02)과 같은 결함군이 메뉴 도메인에서도 발견된 것이다.

#### 해결 방법

`updateMenu()`의 `deactivationRequested` 조건부 조회 분기를 제거하고, 어떤 수정이든 최초 조회부터 `findByIdForUpdate()`를 쓰도록 통일했다(`deactivateMenu()`와 동일한 잠금). null 필드는 잠금 획득 후 읽은 최신 값을 그대로 유지한다.

**범위 밖으로 남은 잔여 위험(Out of Scope, 계획 리뷰에서 확인)**: Thymeleaf 화면(`templates/admin/menu/manage.html`의 `buildPayload()`)은 어떤 필드를 편집했는지와 무관하게 매 PATCH 요청에 `useYn: menuUseYnInput.checked`(화면에 로드된 시점의 체크박스 상태)를 항상 포함한다. 따라서 "A가 메뉴를 화면에 로드(활성 상태) → B가 비활성화 커밋 → A가 이름만 편집해 저장"하는 실제 UI 순서에서는, A의 요청에 여전히 명시적 `useYn=true`가 실려 있어 이번 수정 이후에도 서버가 이를 "의도된 재활성화"로 처리해 비활성화가 되돌아갈 수 있다. 이는 고전적인 stale-form 전체 재전송 문제이며, 서버는 "명시적으로 보낸 true"와 "우연히 오래된 화면 값인 true"를 구분할 방법이 없어 행 잠금만으로는 해결할 수 없다(무시 처리하면 정상 재활성화 계약이 깨진다). 근본 해결(변경 필드만 전송하는 UI 개편, 또는 optimistic lock 버전 컬럼 도입)은 별도 후속 과제다.

**당시 검증(2026-09-27 이력)**: `MenuServiceTest`의 잠금 호출, MariaDB 잠금 실증 및 barrier 동시 제출, 화면 골든 패스를 확인했다. 당시 전체 테스트/화면 검증 수치·결과는 `adversarial-review/remediation-plan.md` "PR 4 실행 기록"에 남긴다. **barrier는 두 순서를 각각 보장하지 않으므로 결정적 A-first/B-first 검증과 동등하다는 주장은 철회한다.**

**PR4-T1 보완(2026-09-28)**: `MenuConcurrencyIntegrationTest`는 실제 서비스의 최초 `findByIdForUpdate()` 호출에 테스트 전용 advice를 붙여 A-first/B-first를 각각 고정한다(제품 latch 없음). 두 connection ID에 대한 `INNODB_LOCK_WAITS`와 대기 중인 `SELECT FOR UPDATE`를 관측한 뒤 선행 작업을 해제하고, 후행 조회의 최신 값·두 commit·최종 `useYn=false` 및 이름 변경을 확인한다. 보조 timeout 시험은 `PessimisticLockingFailureException`과 MariaDB 1205를 함께 단언해 deadlock/기타 오류를 성공으로 흡수하지 않는다. 실패 경로도 latch 해제·worker 종료 대기·Future 예외 전파·advice 제거·fixture 삭제를 거친다. 부모/자식 시험은 기존 불변식 검증이며 모든 순서를 강제하는 시험으로 표현하지 않는다. 제품의 stale-form 제외 범위는 그대로다.

### `AdminActionLogAspect`와 `@Transactional` 어드바이저의 순서 미지정으로 감사 SUCCESS가 커밋 전에 먼저 커밋될 수 있었음

#### 오류 메시지

```
별도 예외 없이 조용히 발생 — 통합 테스트로 실 MariaDB 경합을 재현해서만 드러남
(@Order 제거 후 재현: "expected: FAIL but was: SUCCESS")
```

#### 원인

`AdminActionLogAspect`(성공 시 `@AfterReturning`, 실패 시 `@AfterThrowing`)가 `@Aspect` 순서를 지정하지 않아 기본값 `Ordered.LOWEST_PRECEDENCE`를 가졌고, `@Transactional`의 프록시 어드바이저(`BeanFactoryTransactionAttributeSourceAdvisor`)도 기본값이 동일한 `Ordered.LOWEST_PRECEDENCE`였다 — 두 어드바이저가 동률이면 어느 쪽이 상대를 감싸는지가 Spring 내부 구현(등록 순서 등)에 의존하는 비결정적 상태가 된다. 감사 Aspect가 트랜잭션 어드바이저보다 안쪽에 위치하면, 대상 메서드가 정상 반환되는 즉시(원 트랜잭션이 아직 커밋되지 않은 시점에) `@AfterReturning`이 실행돼 `AdminActionLogService.log()`(REQUIRES_NEW)가 SUCCESS를 별도 트랜잭션으로 먼저 커밋해버린다. 이후 원 트랜잭션이 커밋 단계에서 실패해도 이미 커밋된 SUCCESS 로그는 되돌릴 수 없다(외부 기술 감사 H-03·M-01).

#### 해결 방법

`AdminActionLogAspect` 클래스에 `@Order(Ordered.LOWEST_PRECEDENCE - 1)`을 명시해 `@Transactional` 어드바이저보다 확실히 바깥(먼저 진입, 나중에 반환)에 위치시켰다. 이 순서에서는 대상 메서드의 반환값이 아니라 커밋까지 포함한 전체 프록시 체인의 결과가 `@AfterReturning`/`@AfterThrowing`의 관측 대상이 되므로, 커밋 실패가 `TransactionInterceptor`의 예외로 전파되어 자동으로 `@AfterThrowing`(FAIL 기록)으로 전환된다.

**보장 범위(중요)**: 이 순서 고정은 `@AdminActionLogged`가 붙은 메서드가 해당 요청의 **최상위 트랜잭션 진입점**일 때만 유효하다 — 이미 열려 있는 다른 `@Transactional` 메서드 안에서 참여 호출(`REQUIRED`)되면, 그 메서드의 반환은 물리 커밋과 무관해져 이 보장이 깨질 수 있다(업무 행은 롤백되지만 SUCCESS 감사 행은 남는 알려진 한계). 오늘 기준 감사 대상 4개 서비스(`AdminMemberService`·`MenuService`·`NoticeService`·`NoticeAttachmentService`)의 11개 메서드 전부가 Controller에서 직접 호출되는 최상위 진입점임을 확인했다 — 향후 이 전제가 깨지면 재검토가 필요하다.

**검증(2026-09-28)**: `AdminActionLogCommitOrderIntegrationTest`(Testcontainers 실 MariaDB)가 `TransactionSynchronizationManager.registerSynchronization`의 `beforeCommit()`에서 예외를 던져 "커밋 직전 실패"를 주입 — 정상 커밋 시 SUCCESS 1건, 커밋 직전 실패 시 FAIL 1건(SUCCESS 0건)·업무 행 롤백을 확인했다. `@Order`를 일시적으로 제거해 재실행하면 실제로 `expected: FAIL but was: SUCCESS`로 실패함을 관측해 회귀를 재현한 뒤 복원했다. 참여 트랜잭션 시나리오(외부 `TransactionTemplate` 안에서 호출 후 외부 트랜잭션 실패)는 별도 "알려진 한계 재현 테스트"로 "업무 행 롤백 + SUCCESS 감사 행 잔존"을 고정 기록만 한다(해결 아님). 상세 설계·리뷰 이력은 `adversarial-review/plan/PLAN-audit-log-integrity.md` 참조.

### 감사 로그·방문 로그·로그인 실패 카운트·비밀번호 재설정 4곳이 각자 `X-Forwarded-For`/`X-Real-IP` 헤더를 신뢰해 IP 위조·파싱 예외에 노출돼 있었음

#### 오류 메시지

```
admin_action_log·visit_log의 requestIp가 클라이언트가 보낸 헤더 값 그대로 저장됨(위조 가능)
X-FORWARDED-FOR: , 헤더를 보내면 ArrayIndexOutOfBoundsException 발생:
  - LockingAuthenticationFailureHandler: 예외가 try-catch에 잡혀 로그인 실패 카운트 기록 자체가 건너뛰어짐
    (5회 연속 실패 시 자동 잠금 방어를 매 요청마다 무력화 가능)
  - PasswordResetController: try-catch 없이 예외가 그대로 전파돼 500 반환
    ("이메일 존재 여부와 무관하게 항상 200"이라는 계정 열거 방지 계약 위반)
```

#### 원인

`AdminActionLogAspect.getClientIp()`·`VisitLoggingAuthenticationSuccessHandler.extractClientIp()`·`LockingAuthenticationFailureHandler.extractClientIp()`·`PasswordResetController.extractClientIp()` 4곳이 완전히 동일한 로직(`X-FORWARDED-FOR` 마지막 홉 → `X-Real-IP` → `RemoteAddr` 순으로 신뢰)을 각자 복제하고 있었다. 이 프로젝트에는 실제 리버스 프록시가 없어(Gate H NOT RUN) 이 헤더들은 클라이언트가 임의로 조작할 수 있다(외부 기술 감사 H-03). 최초 조사에서는 2곳(`AdminActionLogAspect`·`VisitLoggingAuthenticationSuccessHandler`)만 확인됐으나, 계획 적대적 리뷰에서 `LockingAuthenticationFailureHandler`가 **같은 `admin_action_log` 테이블**에 `ACCOUNT_AUTO_LOCK` 항목으로 IP를 기록한다는 사실과, `PasswordResetController`도 동일 로직을 복제하고 있다는 사실이 추가로 드러났다.

또한 `",".split(",")`가 빈 배열을 반환해 `ips[ips.length - 1]`이 예외를 던지는 파싱 버그가 두 곳(`LockingAuthenticationFailureHandler`·`PasswordResetController`)에서 각각 실질적 보안·가용성 결함으로 이어졌다.

#### 해결 방법

`com.cms.common.web.ClientIpResolver`(정적 유틸, `EmailNormalizer`와 동일한 프로젝트 관례)를 신설해 4곳 모두 `request.getRemoteAddr()`만 신뢰하도록 통일했다(레이트리밋(`com.cms.config.ratelimit`)과 동일 정책) — 헤더는 전혀 읽지 않으므로 위조·파싱 예외 가능성 자체가 사라진다. 45자(컬럼 길이) 초과 시 절단하는 방어도 이 유틸 한 곳에만 구현해 4곳에 자동 적용한다. `resolve(null)`·`getRemoteAddr()`가 `null`인 경우도 예외 없이 `null`을 반환하도록 계약을 명시해, 비HTTP 호출 등 요청 컨텍스트가 없는 기존 시나리오(감사 저장 자체는 계속됨)를 회귀시키지 않는다.

**검증**: `ClientIpResolverTest`(단위)로 헤더 무시·null 계약·길이 절단을 확인. `LockingAuthenticationFailureHandlerTest`·`PasswordResetControllerTest`·`VisitLoggingAuthenticationSuccessHandlerTest`에 조작된 `X-Forwarded-For: ,` 헤더로도 각각 로그인 실패 카운트가 정상 기록되고(회귀 확인) 200이 정상 반환됨을 확인하는 회귀 테스트 추가. 실제 리버스 프록시가 도입되면 이 4곳(과 별개로 이미 `getRemoteAddr()`를 직접 쓰는 레이트리밋)의 IP 해석 전체를 함께 재검토해야 한다. 상세 설계·리뷰 이력은 `adversarial-review/plan/PLAN-audit-log-integrity.md` 참조.

### OSIV(기본값 true)가 서비스 트랜잭션 종료 후에도 요청이 끝날 때까지 DB 커넥션을 붙잡는다 (2026-09-29, 공개 첨부 스트리밍 전환)

```text
공개 첨부 다운로드를 스트리밍으로 바꾸면 전송 중에는 DB 커넥션이 필요 없다고 가정했다.
실제 웹 요청에서 서비스 @Transactional 메서드가 끝난 뒤 컨트롤러가 대기하는 동안 Hikari 상태를 재보니:
  [SPIKE] 요청 진행 중 Hikari activeConnections=1, idle=9, total=10   (open-in-view 기본값)
  [SPIKE] 요청 진행 중 Hikari activeConnections=0, idle=10, total=10  (SPRING_JPA_OPEN_IN_VIEW=false)
```

#### 원인

`spring.jpa.open-in-view`를 설정하지 않으면 Boot 기본값이 true라 `OpenEntityManagerInViewInterceptor`가 요청 시작부터 끝까지 EntityManager를 열어 두고, 그 사이 트랜잭션이 끝나도 JDBC 연결이 요청이 끝날 때까지 유지된다. 서비스 빈을 직접 호출하는 테스트는 이 인터셉터를 통과하지 않으므로 이 조건을 재현하지 못한다(응답 전송이 긴 요청이 풀을 점유하는 문제는 `byte[]` 시절에도 이미 있었다).

#### 해결 방법

`application.yml` 공통에 `spring.jpa.open-in-view: false`를 추가했다. 엔티티에 연관관계 매핑이 없어 지연 로딩 의존이 없음을 확인했고, 전체 테스트 813개와 관리자 화면 실기 검증으로 회귀가 없음을 확인했다. `PublicAttachmentStreamingServerTest`의 "전송 진행 중 활성 커넥션 0" 테스트가 실제 웹 요청(latch로 전송 중 대기)으로 회귀를 막는다 — 변이 실험(OSIV 재활성)으로 이 테스트가 `expected: 0 but was: 1`로 실패함을 확인했다.

**교훈**: 트랜잭션 경계와 커넥션 점유 범위는 다르다. "서비스 메서드가 끝나면 커넥션이 반환된다"는 전제는 OSIV 설정에 따라 성립하지 않으며, 서비스 빈 직접 호출 테스트로는 증명할 수 없다.

---

### 컨트롤러가 응답 전송 중 IOException을 삼키면 Tomcat이 연결을 끊지 않아 클라이언트가 무한정 대기한다 (2026-09-29, 공개 첨부 스트리밍 전환)

```text
Content-Length 100000을 선언하고 40000바이트만 보낸 뒤 서버 쪽 읽기가 실패한 상황.
컨트롤러가 IOException을 catch하고 정상 반환하게 바꾸자(변이 실험):
  클라이언트: 남은 60000바이트를 계속 기다림(10초 제한 시간 초과)
  전송 전 오류 케이스: 500이어야 하는데 200 반환
예외를 그대로 던지면(원래 구현): 연결이 끊기고 클라이언트가 잘린 응답을 감지함.
```

#### 원인

소켓 자체는 정상이라, 서블릿 컨테이너 입장에서는 핸들러가 정상 종료한 요청이다. 선언된 Content-Length보다 짧게 끝나도 Tomcat이 연결을 닫지 않는다(코드 리뷰에서 지적된 `IdentityOutputFilter.end()`의 동작을 실서버 테스트로 실증). 또한 미커밋 상태여도 `Content-Length`·`getOutputStream()` 선택 상태가 남아 있어 그대로 HTML 오류 뷰를 렌더링할 수 없다.

#### 해결 방법

응답 상태를 3구간으로 나눠 처리한다: (1) 헤더·출력 스트림에 손대기 전에 첫 청크를 먼저 읽어 실패 시 기존 HTML 500, (2) 미커밋이지만 오염된 구간은 `response.reset()` 후 재던짐, (3) 커밋 후에는 `PublicWebExceptionAdvice`가 뷰를 렌더링하지 않고 예외를 재던져 컨테이너가 연결을 중단하게 한다. 복사 버퍼(4KB)는 컨테이너 출력 버퍼(Tomcat 8KB)보다 작게 유지해 "첫 청크 직후 미커밋"이 컨테이너와 무관하게 성립하게 한다.

**검증**: `PublicAttachmentStreamingServerTest`(실제 Tomcat)가 세 구간을 각각 검증한다(HTML 500·`Content-Length` 미잔존·보안 헤더 복원 / 커밋 후 연결 종료·HTML 미혼입·이후 요청 정상). 변이 실험(컨트롤러가 예외를 삼킴)에서 2건이 실패함을 확인했다.

**교훈**: 응답이 커밋된 뒤의 실패는 "삼켜서 정상 종료"가 아니라 "예외를 컨테이너까지 전파"해야 연결이 끊긴다. MockMvc는 서블릿 컨테이너의 커밋·연결 종료를 재현하지 못하므로 실서버 테스트가 필요하다.

---

---

# 정리

본 프로젝트는 단순 기능 구현뿐 아니라  
실제 개발 환경에서 발생할 수 있는 문제를 직접 경험하고 해결했습니다.
