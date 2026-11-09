# PLAN — 비밀번호 재설정 발급을 응답 경로 밖으로 옮겨 계정 열거 신호 제거

> 상태: **완료 (2026-11-09 · #216 `d166850`)** — 계획 v3(적대적 리뷰 3라운드 ship, 2026-11-08 승인). 재측정 통과. 결과는 아래 "구현·검증 결과"
> 근거: `PLAN-password-reset-timing.md` 측정 결과(강한 신호) + 사용자 결정(2026-11-08, 보정안 1 "발급 작업 전체를 executor로" + 단점 보완)
> **위험 변경: 동시성(트랜잭션 실행 스레드 이동·새 executor), 보안 계약(계정 열거 방지)**. 스키마·인가 정책·`SecurityConfig`·의존성 변경 없음.

## Context

`POST /admin/api/password-reset-requests`는 계정 존재와 무관하게 항상 200을 돌려주지만, 측정에서 메일이 발급되는 계정은 응답이 중앙값 6.6ms 느려 요청 1건으로 구분됐다(AUC 0.999). 토큰이 있는 쿨다운·무자격 계정도 약 0.16ms 느렸다(약한 신호). 원인은 요청 스레드가 하는 DB 작업(잠금 조회·토큰 쓰기·커밋)이 계정 상태에 따라 다르기 때문이다.

### 정찰에서 확인한 사실

- **현재 흐름**: `requestReset` → 정규화·로그 → `transactionTemplate.execute(issueToken)`(요청 스레드) → 발급 결과가 있으면 `mailExecutor.execute(sendResetMail)`. 발송 제출이 거부되면 요청 스레드에서 `clearIssuedTokenQuietly`(DB 쓰기)를 한다.
- **예외**: `PessimisticLockingFailureException`만 잡는다. 그 밖의 DB 예외는 컨트롤러로 올라가 500이 된다. 이것도 "행이 있을 때만 나는 실패"면 신호가 된다.
- **`mailExecutor`**: 타입으로 주입되는 Boot 공용 `applicationTaskExecutor`(core=max=4·queue 20, `application.yml`). 사용처는 이 서비스 하나다. MVC 비동기 반환형·`@Async`·`@Scheduled`는 코드에 없다.
- **Boot 4.0 문서(context7)**: 사용자 `Executor` 빈이 있으면 `applicationTaskExecutor` 자동 구성이 철회된다. Spring MVC·JPA 부트스트랩 등은 그 이름의 빈을 쓴다. `@Bean(defaultCandidate = false)`면 자동 구성이 유지되고 그 빈은 **타입 + `@Qualifier`로만** 주입된다(Framework 6.2+).
- **`@TestBean`(Framework 7)**: 시험에서 이름으로 빈을 정적 팩토리 결과로 바꿔 끼울 수 있다.
- **영향받는 시험**: `PasswordResetServiceTest`·`PasswordResetExecutorSaturationTest`·`PasswordResetMailTimeoutIntegrationTest`(생성자 직접 호출), `PasswordResetConcurrencyIntegrationTest`(동기 `TaskExecutor` 빈으로 공용 executor 대체), `MailExecutorPoolConfigurationTest`(yml 값 고정). 컨트롤러 시험은 서비스 목이라 영향 없다.
- **레이트리밋**: `reset-request` IP당 5회/3600초.

### 핵심 쟁점

1. 요청 스레드가 하는 일이 **입력의 존재·상태와 무관하게 같아야** 한다.
2. 발급 큐가 차거나 발급 작업이 실패해도 응답이 달라지면 안 된다.
3. 새 executor가 Boot 자동 구성과 기존 메일 executor를 깨지 않아야 한다.
4. 재기동 때 큐에 남은 요청의 처리.
5. 보정 효과를 무엇으로 확인하는가.

## 설계 결정

### D1. 요청 스레드는 정규화·로그·제출만 한다

```
requestReset(email, ip):
  normalized = EmailNormalizer.normalize(email)   // 형식·길이 위반은 지금처럼 400 (입력만 보고 정해짐)
  log.info(요청 수신, 마스킹 이메일, ip)
  acceptedAt = clock.instant()
  try { issueExecutor.execute(() -> issueAndDispatch(normalized, acceptedAt)) }
  catch (TaskRejectedException e) { log.warn(발급 제출 거부, 마스킹 이메일) }   // 응답은 그대로 200
```

`issueAndDispatch`(발급 스레드)는 먼저 **대기 시간을 확인**한다. `clock.instant() − acceptedAt > 30초`면 DB에 닿지 않고 WARN(마스킹 이메일·대기 초)으로 버린다(v2, D6). 아니면 지금의 `issueToken` 트랜잭션 → `dispatchResetMail`을 그대로 실행한다. 잠금 실패는 지금처럼 WARN으로 삼키고, **그 밖의 모든 예외도 잡아 ERROR 로그(예외 타입만)**로 남긴다. 예외 메시지·스택에 이메일이나 SQL 파라미터가 섞일 수 있어 throwable을 통째로 남기지 않는다(기존 토큰 로그 금지 규칙과 같은 이유).

| 선택지 | 판단 |
|---|---|
| A. 발급+발송 전체를 executor로(채택) | 응답 경로에 DB가 없다. 0.16ms 신호·DB 장애 차이까지 사라진다 |
| B. 최소 응답 시간 패딩 | 기각 — 무인증 경로의 스레드 점유, 패딩을 넘는 꼬리(최대 135ms·잠금 대기)는 여전히 샌다 |
| C. 조회는 요청 스레드, 쓰기만 비동기 | 기각 — 잠금 조회 결과(행 유무)에 따른 0.16ms 차이와 분기 비용이 남는다 |

### D2. 발급 전용 executor를 추가하고 메일 executor는 그대로 둔다

- 새 빈 `passwordResetIssueExecutor`: `ThreadPoolTaskExecutor`, **core=max=2, queue 100**, 스레드 이름 `pwreset-issue-`, `@Bean(defaultCandidate = false)`, 서비스는 `@Qualifier("passwordResetIssueExecutor")`로 받는다.
  - **2개인 이유**: 발급 작업은 측정상 수 ms이고 DB 연결을 하나씩 쓴다. 적게 둬서 요청 스레드의 연결 몫을 빼앗지 않는다.
  - **100인 이유**: 작업 하나가 이메일 문자열 정도라 메모리는 무시할 수준이다. IP당 시간당 5회 한도에서 100을 한 번에 채우려면 IP 20개 이상이 동시에 몰려야 한다. 상한을 두는 이유는 `PLAN-mail-executor-bound.md`와 같다(무제한 큐 금지).
  - 값은 설정 속성이 아니라 상수로 둔다. 바꿀 요구가 없고 속성은 요청받지 않은 설정이다.
- **메일 단계는 기존 `applicationTaskExecutor`(4/4/20)를 그대로 쓴다.** 발송 제출 거부 시 토큰 정리 계약도 유지된다(이제 발급 스레드에서 실행).
  - 선택지 "메일도 전용 빈으로 분리"는 기각한다. 지금 공용 executor 사용처가 이 서비스뿐이라 얻는 것이 없고, 분리하면 `spring.task.execution` 설정과 `MailExecutorPoolConfigurationTest`를 함께 옮겨야 한다. 다른 기능이 이 executor를 쓰게 되면 재평가한다는 기존 규칙(member `CLAUDE.md`)을 유지한다. (단점 보완 제안 ②를 바꾼 결정 — 사용자 보고 대상)
- `defaultCandidate = false`를 빼면 Boot의 `applicationTaskExecutor`가 사라지고 메일 단계가 새 빈을 받게 된다. 이를 시험으로 고정한다(F4).

### D3. 종료 처리

`setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(5)`.

**정확한 계약(v2)**: 종료 때 executor는 새 작업을 받지 않고 실행 중·대기 작업을 계속 처리한다. 컨텍스트 종료는 이 executor를 **최대 5초 기다린 뒤** 나머지 빈(데이터소스 포함)을 닫는다. **5초가 지나도 작업이 중단되지는 않는다.** 그 뒤 실행되는 발급 작업은 닫힌 자원 때문에 DB 예외로 실패해 F3 경로(ERROR 로그·발송 없음)로 끝나고, 컨테이너 종료 유예(기본 10초)가 끝나면 프로세스와 함께 사라진다. 5초는 컨테이너 유예 안에서 정상 상태의 발급(수 ms)과 대기 작업을 비울 시간으로 잡은 값이며, 발송까지 마친다는 보장은 아니다(발송은 기존 메일 executor 계약).

종료 중에 생길 수 있는 경우는 모두 기존 시험 경로의 조합이다.
- 메일 executor가 먼저 닫혀 발송 제출이 거부되면 기존 계약대로 토큰을 지운다(기존 거부 시험).
- 토큰 정리 자체가 실패하면 조용히 넘어가고 토큰은 30분 뒤 만료된다(기존 `requestReset_clearFailure_stillSilent`).
- DB가 닫힌 뒤의 발급은 F3이다.

그래서 별도 종료 시험은 두지 않고 설정값만 F4로 고정한다. 사용자 영향은 "메일이 안 오면 다시 요청"이다.

### D4. 바뀌는 계약

| 항목 | 지금 | 바뀐 뒤 |
|---|---|---|
| 응답 시간 | 계정 상태에 따라 다름 | 입력 형식만 보고 정해짐 |
| DB 예외(잠금 실패 제외) | 500 | 200 + ERROR 로그 |
| 발송 제출 거부 시 토큰 정리 | 요청 스레드 | 발급 스레드(계약은 같음) |
| 새 거부 지점 | 없음 | 발급 큐 포화 → 200 + WARN, 발급 없음 |
| 쿨다운·행 잠금·같은 이메일 동시 요청 직렬화 | 그대로 | 그대로(발급 스레드에서 같은 트랜잭션) |
| 메일 도착 시점 | 응답 뒤 | 응답 뒤(발급 대기만큼 조금 더 늦을 수 있음) |

`PLAN-mail-executor-bound.md` 결정 4("포화 시 거부 경로의 요청 스레드 DB 호출 — 타이밍 잔여 위험")는 이 변경으로 해소된다.

### D6. 발급 대기 상한 30초 (v2)

큐 용량 상한은 대기 시간 상한이 아니다. 쿨다운은 **실행 시각**으로 판정하므로, 같은 이메일로 함께 접수된 요청이 큐에서 오래 기다리면 실행 간격이 60초를 넘어 토큰이 다시 발급되고 앞선 링크가 무효가 될 수 있다. 접수 뒤 비밀번호가 바뀌었는데 오래된 요청이 뒤늦게 토큰을 발급하는 경우도 생긴다.

| 선택지 | 판단 |
|---|---|
| A. 접수 시각을 함께 넘기고 30초 초과 작업은 버림(채택) | 함께 접수된 요청의 실행 간격이 최대 약 30초라 쿨다운(60초) 안에 들어간다. 버려지는 것은 큐가 30초 넘게 막힌 비정상 상황뿐이고, 사용자는 재요청한다 |
| B. 오래된 요청도 실행 | 기각 — 링크 무효화와 뒤늦은 발급이 정상 계약이 된다 |
| C. 쿨다운을 접수 시각으로 판정 | 기각 — 발급 시각 역산 방식(`expiryAt − TTL`)과 저장 형식을 바꿔야 해 범위가 커진다 |

**검사 위치는 두 곳이다(v3)**:
1. **작업 시작 때**: DB에 닿기 전에 버린다(큐 대기).
2. **`issueToken`에서 잠금 조회가 반환된 직후, 어떤 상태 변경(`releaseExpiredAutoLock` 포함)보다 앞**: 행 잠금 대기로 30초를 넘긴 경우를 버린다. 쿨다운 판정에 쓰는 `now`와 같은 값으로 비교하므로, 이 검사를 통과한 발급은 항상 접수 후 30초 이내의 시각에 판정된다. 쓰기가 없어 트랜잭션은 그대로 커밋돼도 행이 바뀌지 않는다.

**30초인 이유**: 쿨다운 60초의 절반이라 함께 접수된 요청 사이 여유가 있고, 정상 발급(수 ms)과 큐 100개를 2스레드로 비우는 시간보다 충분히 길다. 시각은 주입된 `Clock`을 쓴다(`ClockUsageConventionTest`). 버림은 DB에 닿기 전이므로 응답·DB 상태에 흔적이 없다.

### D5. 확인 방법

- **구조 회귀 시험(결정적)**: 발급 executor를 "제출만 받고 실행하지 않는" 것으로 두고 `requestReset`을 부른 직후 `memberRepository`·`transactionManager` 호출이 0건임을 단언한다. 누가 DB 접근을 응답 경로로 되돌리면 실패한다. 시간 측정 시험은 CI 기계마다 값이 달라 두지 않는다.
- **재측정**: `PLAN-password-reset-timing.md`의 도구·조건·판정 알고리즘을 그대로 써서 9블록(JVM 3회)을 다시 잰다. 원인 분리 3블록은 뺀다.
  - **통과 조건(사전 고정)**: 대조 N1−N2 유효성 통과 + **판정 쌍(P1~P6, 묶음 최댓값·중앙값) 전부 "사전 기준 미달"**.
  - **완료 확인(v2)**: 측정 실행에서만 `LOGGING_LEVEL_COM_CMS_ADMIN_MEMBER_SERVICE_PASSWORDRESETSERVICE=DEBUG`를 켠다. 그러면 발급 작업마다 기존 로그 중 **끝 줄이 정확히 하나** 남는다: 미존재·무자격·쿨다운(DEBUG), 발송 성공(INFO, 메일 작업 끝), 잠금 실패·일반 실패·대기 초과·발송 실패·제출 거부(WARN·ERROR). 블록이 끝나면 JVM 시작 뒤 누적 끝 줄 수가 누적 요청 수와 같아질 때까지 기다리고, **30초를 넘기면 그 블록은 무효**다.
  - 그 뒤 기존 DB 상태·SMTP 수신 대조를 한다. 거부·대기 초과·실패 로그는 0건이어야 한다.
  - DEBUG 로그는 발급 스레드에서 쓰이므로 요청 스레드 측정에는 들어가지 않는다. 다만 운영 로그 수준과 다른 조건임을 결과에 적는다.
  - 하나라도 약한·강한 신호면 완료로 보지 않고 원인을 조사해 보고한다.

## 놓치기 쉬운 실패 모드

| # | 실패 모드 | 고정할 시험 |
|---|---|---|
| F1 | 누군가 조회·발급을 다시 요청 스레드로 옮김 | `PasswordResetServiceTest` — 실행을 미루는 executor로 `requestReset` 직후 repository·트랜잭션 호출 0건, 미뤄 둔 작업을 실행하면 발급·발송 |
| F2 | 발급 큐 포화의 `TaskRejectedException`이 컨트롤러로 새어 500 | `PasswordResetServiceTest` — 거부하는 executor에서 예외 없이 반환, DB 호출 0건, WARN 로그에 원문 이메일 없음(마스킹 형태만) |
| F3 | 발급 작업의 일반 DB 예외가 처리되지 않아 스택·메시지가 로그에 남거나 발송 단계로 진행 | `PasswordResetServiceTest` — `findByEmailForUpdate`가 `DataAccessResourceFailureException`을 던져도 작업이 예외 없이 끝나고, ERROR 로그는 예외 타입만 담고 발송 없음 |
| F4 | `defaultCandidate = false`가 빠져 Boot `applicationTaskExecutor`가 사라지거나 메일 단계가 발급 executor를 받음 | `MailExecutorPoolConfigurationTest`에 추가 — 자동 구성 + 새 설정을 함께 띄우면 `applicationTaskExecutor`(4/4/20)와 `passwordResetIssueExecutor`(2/2/100, 종료 대기 켜짐)가 둘 다 있고, 한정자 없는 `TaskExecutor` 주입이 `applicationTaskExecutor`를 받음 |
| F6 | 큐에서 오래 기다린 작업이 뒤늦게 발급해 링크를 무효화(v2) | `PasswordResetServiceTest` — 실행을 미루는 executor + 움직이는 시계: 30초 경과 작업은 DB 호출 0건·WARN, 30초 이하는 정상 발급(경계 포함). **잠금 조회 목이 반환하는 순간 시계를 30초 넘게 옮기면** 토큰·상태(자동 잠금 해제 포함) 변경과 발송이 없음(v3) |
| F5 | 같은 이메일 동시 요청이 비동기화 뒤 두 번 발송 | 기존 `PasswordResetConcurrencyIntegrationTest`(실제 DB) — 발급 executor를 `@TestBean`으로 동기화해 두 요청 스레드가 실제로 경합하게 두고 발송 1회 유지 |

## 작업 단계

1. 실패 시험 먼저(F1~F4·F6) — 현재 코드에서 실패 확인.
2. `PasswordResetExecutorConfig`(새 빈) 추가.
3. `PasswordResetService`: 생성자에 발급 executor, `requestReset` 분리, 발급 작업 예외 처리.
4. 기존 시험 생성자·빈 교체 반영(`PasswordResetExecutorSaturationTest`·`PasswordResetMailTimeoutIntegrationTest`·`PasswordResetConcurrencyIntegrationTest`).
5. 영향 범위 시험: `com.cms.admin.member.*`, `com.cms.config.*`, `*ConventionTest`.
6. 실기: dev bootRun으로 재설정 요청 화면 → 메일 발송 로그(dev SMTP는 더미라 발송 실패 로그와 토큰 정리 확인) → 없는 이메일·잘못된 형식 응답 확인.
7. 재측정(D5).
8. 문서: member `CLAUDE.md`, `application.yml` 주석, 서비스 주석, `PLAN-password-reset-timing.md` 후속, 이 문서 결과.

## 성공 기준

- [ ] F1~F6 시험 통과, F1~F4·F6은 현재 코드에서 실패함을 먼저 확인
- [ ] 영향 범위 시험 통과, PR 직전 전체 `./gradlew test` 1회 통과
- [ ] 재측정 통과 조건 충족(D5)
- [ ] 실기에서 재설정 요청 화면 동작과 발급·정리 로그 확인
- [ ] 스키마·인가 정책·`SecurityConfig`·의존성 변경 없음

## 리스크

| 리스크 | 대응 |
|---|---|
| 발급 큐 포화 시 정상 요청이 조용히 버려짐 | WARN 로그로 운영에서 보이게 함, 사용자는 재요청. IP 한도로 채우기 어려움 |
| 재기동 때 큐의 요청 유실 | 종료 시 최대 5초 대기(D3), 재요청으로 복구 |
| 큐가 30초 넘게 막히면 요청이 버려짐 | WARN 로그, 재요청(D6) |
| 발급이 비동기라 DB 장애가 응답에 안 보임 | ERROR 로그. 지금도 "항상 200" 계약이라 사용자 화면 동작은 같음 |
| 백그라운드 작업이 동시 요청의 CPU를 간접적으로 늦춤 | 재측정으로 확인 |

## 구현·검증 결과 (2026-11-08)

### Context

`PLAN-password-reset-timing.md` 측정에서 재설정 요청 응답 시간으로 계정 존재가 요청 1건 만에 드러났다(강한 신호). 원인은 요청 스레드가 하던 발급 트랜잭션이었다. 이 계획대로 발급을 전용 executor로 옮기고, 같은 도구·조건·판정 알고리즘으로 다시 쟀다.

### 핵심 확정 사항

- 요청 스레드는 정규화·접수 로그·제출만 한다. 계정 조회·토큰 발급·발송 제출(거부 시 토큰 정리 포함)은 `passwordResetIssueExecutor`(2/2/100)에서 실행한다.
- 메일 발송은 기존 `applicationTaskExecutor`(4/4/20)를 그대로 쓴다. 새 빈은 `@Bean(defaultCandidate = false)` + `@Qualifier`라 Boot 자동 구성을 거두지 않는다(Boot 4.0 문서 확인).
- 발급 제출 거부는 200 + WARN, 발급 작업의 예외는 작업 안에서 끝난다(잠금 실패 WARN, 그 밖은 ERROR, 예외 타입만).
- 접수 후 30초 초과 작업은 작업 시작 때와 행 잠금 직후·상태 변경 전 두 곳에서 버린다.
- 종료: 최대 5초 대기 후 나머지 빈 종료, 작업은 중단되지 않는다.
- 스키마·인가 정책·`SecurityConfig`·의존성 변경 없음.

### 구현 파일

| 파일 | 변경 |
|---|---|
| `admin/member/service/PasswordResetExecutorConfig.java` | 신규 — 발급 executor 빈 |
| `admin/member/service/PasswordResetService.java` | 생성자에 발급 executor, `requestReset` 분리, `issueAndDispatch`·`waitedTooLong`·`MAX_ISSUE_WAIT`, `issueToken`의 잠금 직후 검사 |
| `src/main/resources/application.yml` | 메일 executor 주석에 역할 분리 기록(값 변경 없음) |
| `admin/member/CLAUDE.md` | 새 계약 기록 |
| `PasswordResetServiceTest` | F1·F2·F3·F6 시험 6건 추가, 생성자 반영 |
| `MailExecutorPoolConfigurationTest` | F4 시험 추가 |
| `PasswordResetConcurrencyIntegrationTest` | F5 — 발급 executor를 `@TestBean`으로 동기화 |
| `PasswordResetExecutorSaturationTest`·`PasswordResetMailTimeoutIntegrationTest` | 생성자 반영(동기 발급 executor) |

### 검증 결과

**시험**
- TDD: 새 시험 7건(F1·F2·F3·F6 6건 + F4 1건)이 구현 전에 의도한 이유로 실패했다. F1·F2는 요청 스레드의 DB 호출, F3는 예외가 호출자로 전파, F6는 작업이 제출되지 않음, F4는 빈 없음이었다. 구현 뒤 모두 통과했다.
- 영향 범위 시험 `com.cms.admin.member.*`·`com.cms.config.*`·`*ConventionTest` 663건 통과(실패·오류·건너뜀 0). 실제 빈 구성으로 뜨는 통합 시험이 포함돼 두 executor가 함께 주입되는 것도 확인했다.
- 전체 `./gradlew test`는 PR 직전에 돌린다(아직 실행 전).

**실기(dev bootRun, 메일은 로컬 가짜 SMTP)**

| 확인 | 결과 |
|---|---|
| 존재 계정 요청 | 안내 문구 표시, 토큰 저장, 가짜 SMTP 수신, 발송 성공 로그는 메일 executor(`task-1`) |
| 없는 이메일 요청 | 같은 안내 문구, 메일 없음 |
| 형식 오류 | 400 |
| CSRF 없음 | 401(기존 동작, `SecurityConfig` 무변경) |
| 레이트리밋 | 같은 IP 6번째 요청 429 |
| 원복 | 토큰 NULL로 복원. `update_date`는 발급 때 갱신된 값으로 남음(원래 값을 미리 기록하지 않았다) |

스크린샷은 저장소 밖에 두었다(존재·미존재 이메일 화면이 같은 문구).

**재측정(같은 도구·조건, 9블록·JVM 3회)**

- 유효성: 비200 0건, 블록 DB 대조 통과, 가짜 SMTP 수신 2,536건 = 기대값(9 × 240 + 워밍업 375 + 사전 점검 1), 앱 실패·거부·대기 초과 로그 0건, **블록마다 비동기 끝 줄 수 = 누적 요청 수**(최종 15,902/15,902). 대조 N1−N2 AUC 0.508.
- **판정: 모든 판정 쌍 사전 기준 미달**(JVM 3회 모두 같음).

| 쌍 | 보정 전 AUC / 중앙값 차 | 보정 후 AUC / 중앙값 차 | 보정 후 BA 중앙값 | 보정 후 판정 |
|---|---|---|---:|---|
| P1 E−N | 0.999 / 6.567ms | 0.494 / −0.004ms | 0.498 | 사전 기준 미달 |
| P2 C−N | 0.673 / 0.163ms | 0.492 / −0.008ms | 0.503 | 사전 기준 미달 |
| P3 DL−N | 0.659 / 0.159ms | 0.480 / −0.020ms | 0.505 | 사전 기준 미달 |
| P4 DD−N | 0.668 / 0.160ms | 0.498 / −0.005ms | 0.491 | 사전 기준 미달 |
| P5 DX−N | 0.667 / 0.163ms | 0.500 / 0.000ms | 0.478 | 사전 기준 미달 |
| P6 DU−N | 0.660 / 0.164ms | 0.488 / −0.010ms | 0.515 | 사전 기준 미달 |
| 묶음 최댓값 BE−BN | 0.996 / 5.496ms | 0.518 / 0.012ms | 0.538 | 사전 기준 미달 |
| 묶음 중앙값 BE−BN | 0.746 / 0.206ms | 0.513 / 0.009ms | 0.525 | 사전 기준 미달 |

- 보정 전 C−N 차이는 모든 블록에서 양수(0.151~0.351ms)였는데, 보정 후에는 블록마다 부호가 섞인다(−0.025~0.011ms).
- 없는 이메일의 중앙값도 2.630ms에서 1.300ms로 줄었다. 요청 스레드가 더는 트랜잭션을 열지 않기 때문으로 보인다(원인 분리는 하지 않았다).
- JVM 첫 요청은 51.2~59.2ms(보정 전 98.2~114.0ms)로 여전히 콜드 스타트 영향이 있지만, 계정 상태와 무관한 값이다.
- 재표집 참고값(판정에 쓰지 않음) 중 DD−N k=15의 TPR@1% 0.760은 합산 FPR이 47%로 임계값이 옮겨 가지 않은 결과라 의미가 없다. 같은 표의 나머지 값도 BA가 0.48~0.51이다.

### 이슈

- 측정 계획서는 DEBUG 로그를 `LOGGING_LEVEL_…_PASSWORDRESETSERVICE` 환경변수로 켜기로 했으나, Boot가 이 키를 소문자로 바꿔 클래스 이름과 맞지 않았다(끝 줄 1/14로 발견). `SPRING_APPLICATION_JSON`으로 클래스 이름을 정확히 지정했다. 판정 방식은 같다.
- 첫 사전 점검 스크립트가 같은 시드를 두 번 넣어 중복 키로 멈췄다. 측정 전 단계라 영향 없다.
- 실기 검증의 `update_date` 원복 누락(위 표).

### 후속

- 인터넷 경로·운영 디스크·큐 포화 조건의 재측정은 하지 않았다(측정 계획 D7과 같은 한계). 다만 이제 요청 스레드가 DB에 닿지 않으므로 이 조건들이 계정 상태를 응답 시간에 실을 경로가 코드상 없다. `PasswordResetServiceTest`가 이 구조를 고정한다.
- 발급 큐 포화·30초 초과는 WARN 로그로만 보인다. 운영 로그 감시 대상에 넣을지는 실배포 때 정한다.

### 재측정 부록

측정 도구는 `PLAN-password-reset-timing.md` 부록과 같고, 다음만 바뀌었다.
- `lib.sh`: `app_failures` 패턴에 `제출 거부|발급 실패|대기 초과` 추가. `term_count`(끝 줄 패턴: `미존재 이메일|무자격 계정|쿨다운 내 재요청|발송 성공|제출 실패|제출 거부|발송 실패|잠금 실패|발급 실패|대기 초과`)와 `wait_done`(최대 30초, 0.5초 간격) 추가.
- `setup.sh` 재정의 파일의 앱 환경에 `SPRING_APPLICATION_JSON: '{"logging":{"level":{"com.cms.admin.member.service.PasswordResetService":"DEBUG"}}}'` 추가.
- `run-after.sh`: `run.sh`에서 원인 분리 블록을 빼고, 워밍업·블록마다 `wait_done "$sent"`로 비동기 완료를 확인한 뒤 `check_block`을 돌린다.

## 개정 이력

- v1: 최초 작성.
- v2 변경: 1라운드 지적 3건 수용. ① 접수 시각을 넘기고 30초 초과 발급 작업은 DB에 닿기 전에 버림(D6, F6) — 리뷰는 "결정 필요"로 분류했으나 선택지 비교가 명확해 채택하고 사용자 승인 단계에서 보고. ② 종료 계약을 "최대 5초 기다린 뒤 나머지 빈 종료, 작업은 중단되지 않음"으로 정정. 기각: 별도 종료 시험 — 종료 중 경우는 기존 시험 경로(발송 거부→정리, 정리 실패 무시, F3)의 조합이고 설정값은 F4가 고정한다. ③ 재측정 완료 확인을 DEBUG 끝 줄 수 = 요청 수로 바꾸고 30초 초과 블록은 무효.
- v3 변경: 2라운드 지적 1건 수용. 30초 검사를 작업 시작 때와 잠금 조회 직후(상태 변경 전) 두 곳에서 하도록 고쳤다 — 시작 때만 보면 행 잠금 대기 뒤 뒤늦은 재발급을 막지 못한다. F6에 잠금 대기 초과 경우 추가.
