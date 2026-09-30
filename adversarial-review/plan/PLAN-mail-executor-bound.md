# PLAN — 비밀번호 재설정 메일 발송 executor 큐·동시 실행 상한

> 작성일: 2026-09-30
> 로드맵 근거: `adversarial-review/project-direction-roadmap.md` "실행 로드맵 — Top 5 (2026-09-05 선정)" **④ 운영 가시성 확보** 중 잔여분
> (SMTP timeout=PR 5 `affc41f` #41, 서버 오류 로깅=PR 3 `e9bd961` #39는 완료 — 남은 것은 "발송용 `TaskExecutor` 큐 상한")
> 관련: `adversarial-review/remediation-plan.md` PR 5 "공용 executor와의 상호작용"·Deferred Work "mail 전용 bounded executor" 행

## 개정 이력

- v1 (2026-09-30): 최초 작성(정찰·설계 결과). `/plan-review-loop` 리뷰 대상으로 제출.
- v2 (2026-09-30, codex 리뷰 1차 반영 — needs-attention, 3개 지적 전부 수용, 높음 0):
  - **수용(중간1, TTL 여유 주장 근거 약함)**: SMTP timeout은 소켓 단계별 값이라 작업 전체 상한이 없고 `sendResetMail`은 실행 직전 만료를 확인하지 않는다는 지적이 코드로 확인됨. 이 작업의 보장을 **"동시 실행 수·대기 작업 수의 상한"으로 한정**하고, 결정 2의 TTL 계산은 "정상 상황 추정치"로 격하하며 "만료 링크 발송 배제"는 보장하지 않는 것으로 명시(별도 설계 필요 → 범위 밖).
  - **수용(중간2, 같은 계정 재요청 거부 시 대기 중 T1 메일이 무효 링크가 됨)**: 60초 쿨다운 후 토큰이 T2로 교체되고 T2 제출이 거부·클리어되면 T1 메일은 발송되나 DB엔 유효 토큰이 없다 — 코드상 성립. 기존 발송 실패 경로에도 있던 결함이라 서비스 로직 변경(범위 밖) 대신 **잔여 위험으로 명시 + 동작을 고정하는 동일 계정 테스트 추가**(결정 6-3, 리스크 표).
  - **수용(낮음3, 포화 테스트와 변이 목표 불일치)**: 자체 2/2/2 executor 테스트는 운영 yml 제거를 못 잡는다. 검증 역할을 분리 — yml 값 회귀는 설정 적용 테스트, 거부 동작은 포화 테스트(래치로 **실행 2·대기 2·추가 제출 거부**를 각각 단언, 큐를 무제한으로 바꾸면 거부 단언 실패). 작업 순서 3의 변이 확인 문구 정정.

- v3 (2026-09-30, codex 리뷰 2차 — **ship**, 추가 실질 지적 0건): 본문 변경 없음. 리뷰어가 `TaskExecutor` 소비자가 `PasswordResetService` 하나뿐임, 제출 예외 catch·조건부 클리어 실존, 서비스 로직 추가 변경 근거 없음을 코드로 확인. 잔여 위험(만료·무효 링크, 포화 시 요청 스레드 DB 정리)은 이미 수용·기록된 것으로 판정.

## Context

`PasswordResetService`는 토큰 발급 트랜잭션 커밋 후 메일 발송을 `TaskExecutor mailExecutor`에 제출한다(`PasswordResetService.java:133`, 타이밍 부채널 완화 목적으로 응답 경로에서 분리).

### 정찰로 확정한 사실

- **`mailExecutor`는 전용 빈이 아니다.** main 전체에 `TaskExecutor`/`Executor` 빈 정의·`@Async`·`@EnableAsync`·`spring.task.*` 설정이 0건이다. 생성자가 타입(`TaskExecutor`)으로 주입받는 대상은 Spring Boot `TaskExecutionAutoConfiguration`의 `applicationTaskExecutor`(`ThreadPoolTaskExecutor`)다. (`spring.threads.virtual.enabled`도 미설정 → 풀 기반 구현.) 파라미터 이름 `mailExecutor`는 이름일 뿐 빈 이름이 아니다.
- **Boot 기본값은 큐 무제한**: core 8, queue-capacity 무제한(`Integer.MAX_VALUE`) → 큐가 차지 않으므로 max-size는 의미 없고 스레드는 최대 8개, 대기 작업은 무한히 쌓인다. SMTP 무응답(timeout 적용 후에도 작업당 수십 초 점유) 시 유입이 처리량을 넘으면 **큐(힙)가 무한 성장**하고, 큐에 오래 머문 항목은 토큰 TTL(30분, 발급 시점부터 시작)을 소진한 뒤 만료된 링크를 발송한다.
- **거부 경로는 이미 구현·테스트돼 있다.** `dispatchResetMail`이 `mailExecutor.execute` 예외(`TaskRejectedException` 포함)를 `catch (Exception)`으로 삼키고 `log.error`(memberId·예외 타입만) 후 `clearIssuedTokenQuietly`(조건부 토큰 클리어)를 수행한다. `PasswordResetServiceTest.requestReset_executorRejection_stillSilentAndClearsToken`이 이 계약을 고정한다. 즉 **"큐가 유한해지면 거부가 발생한다"는 전제만 충족하면 응답 균일성(항상 200)은 이미 성립**한다.
- **테스트 파급**: `PasswordResetConcurrencyIntegrationTest`는 자체 `SyncTaskExecutor` 빈을 등록해 Boot의 `applicationTaskExecutor` 자동 구성을 `@ConditionalOnMissingBean(Executor.class)`로 물러나게 한다(주석 명시). `PasswordResetServiceTest`·`PasswordResetMailTimeoutIntegrationTest`는 생성자에 직접 `SyncTaskExecutor`/람다를 넘긴다. 따라서 **main에 새 `TaskExecutor` 빈을 추가하면 이 컨텍스트에서 타입 주입이 모호해져 실패**한다(빈 이름·`@Qualifier` 정합 필요). 반대로 **`spring.task.execution.pool.*` 설정만 쓰면 기존 테스트 파급이 0**이다.
- **유입 상한 참고**: `cms.rate-limit` `reset-request`는 IP당 5회/시간(`application.yml:94-98`)이라 단일 IP 유입은 작지만, IP 회전 유입은 막지 못한다(fail-open 수용 명시). 발송은 계정당 60초 쿨다운도 있다. 따라서 큐 상한은 "정상 트래픽을 넘는 유입만 거부"하는 방어선이다.
- **범위 밖(기존 계약)**: 셧다운 시 큐 잔여 작업 폐기(`await-termination` 미설정), 재시도·브로커·메트릭 없음.

## 핵심 쟁점

1. 상한을 어떻게 거는가 — 설정 vs 전용 빈.
2. 풀 크기·큐 크기를 무엇으로 정하는가(토큰 TTL과의 관계).
3. 포화 시 정책 — 거부(현행 catch 경로) vs 호출자 실행 vs 무음 폐기.
4. 거부 경로가 응답 균일성(타이밍 부채널)에 미치는 영향.
5. 설정이 실제로 적용됨을 어떻게 회귀 방지하는가.

## 설계 결정

### 결정 1 — `spring.task.execution.pool.*` 설정으로 공용 executor에 상한을 건다 (전용 빈 도입 안 함)

| 선택지 | 평가 |
|---|---|
| **A. `application.yml`의 `spring.task.execution.pool.{core-size,max-size,queue-capacity}`** | 코드·시그니처 변경 0, 기존 테스트 파급 0, 전 프로파일 공통 — **채택** |
| B. `@Configuration`에서 `mailExecutor` 전용 `ThreadPoolTaskExecutor` 빈 + `@Qualifier` | 공용 executor와 격리되나 `Executor` 빈이 생기면 Boot `applicationTaskExecutor`가 사라짐(MVC async 등 향후 사용 영향), `PasswordResetConcurrencyIntegrationTest`의 Sync 빈과 이름·타입 충돌 → 테스트 수정 필요 — 이번 범위(상한)에 비해 과설계 |
| C. `ThreadPoolTaskExecutor`를 직접 `new`(서비스 내부) | 프레임워크 수명주기(셧다운) 관리 이탈, 테스트 주입 경로 변경 — 기각 |

**왜 A인가**: main 전체에서 이 executor의 유일한 소비자가 `PasswordResetService`이고(`@Async` 0건), 공용 executor를 공유하는 다른 작업이 없으므로 격리의 실익이 아직 없다. remediation-plan Deferred Work가 전용 executor 도입 조건으로 "다른 비동기 작업 간섭이 관측될 때"를 명시했고 현재 그 조건은 아니다. 대신 **"공용 executor에 걸린 상한"임을 설정 주석과 문서에 명시**해, 향후 다른 소비자가 생기면 전용 빈 분리를 재평가하도록 남긴다(제약으로 명문화).

### 결정 2 — 값: core-size 4 = max-size 4, queue-capacity 20

- **왜 core=max**: 큐가 유한해도 core < max이면 큐가 꽉 찬 뒤에야 스레드가 늘어난다(ThreadPoolExecutor 동작). SMTP 병목에서 스레드를 늘려도 이득이 없고 동시 SMTP 연결만 늘어나므로 동시 실행 수를 4로 고정한다. (Boot 기본 `allow-core-thread-timeout=true`이므로 유휴 시 스레드는 회수된다.)
- **왜 4·20**: 관리자 계정 수는 소수이고 계정당 쿨다운 60초·IP당 5회/시간이라 정상 동시 발송은 한 자릿수다. **이 작업이 보장하는 것은 "동시 실행 수 ≤ 4, 대기 작업 수 ≤ 20"이라는 자원 상한뿐이다.** 대기 지연 추정(참고용): `ceil(20 / 4)` × (작업당 시간). SMTP timeout은 connect 10s / read 30s / write 30s의 **소켓 단계별 값이라 작업 전체 상한이 아니며**(PR 5 잔여 위험, `sendResetMail`은 실행 직전 만료를 확인하지 않음) 정상 상황에서 작업당 1분 남짓이면 수 분 규모로, 토큰 TTL 30분 대비 여유가 있다는 **추정**일 뿐이다. 따라서 **"큐 대기로 만료된 링크가 발송되지 않음"은 보장하지 않는다**(전체 deadline·발송 직전 만료 검사는 별도 설계, 범위 밖). 큐를 키울수록 추정 여유가 줄어드는 방향이므로 20을 유지한다.
- 값은 Boot 프로퍼티(`spring.task.execution.pool.*`)로 두어 운영 환경변수로 덮어쓸 수 있으나 **새 커스텀 설정 옵션·문서화된 환경변수는 만들지 않는다**(최소 구현 원칙).

### 결정 3 — 포화 시 정책: 거부(기본 `AbortPolicy`) + 기존 catch 경로 유지

| 선택지 | 평가 |
|---|---|
| **A. 거부(AbortPolicy → `TaskRejectedException`) → 현행 `dispatchResetMail` catch: 오류 로그 + 토큰 조건부 클리어, 응답 200** | 기존 코드·테스트가 그대로 계약을 커버, 발송이 안 된 토큰이 쿨다운을 소모하지 않아 사용자가 즉시 재시도 가능 — **채택** |
| B. `CallerRunsPolicy` | 요청 스레드가 SMTP 대기(수십 초) → 타이밍 부채널 재현 + Tomcat 스레드 고갈 — 기각 |
| C. 무음 폐기(토큰은 그대로 둠) | 메일은 못 받았는데 쿨다운(60s)·유효 토큰만 남음, 보안상 발송되지 않은 유효 토큰이 DB에 잔존 — 기각 |

포화 시 동작 선택지 A/B/C는 **승인 단계에서 사용자에게 재확인**한다(사용자 결정 사항).

### 결정 4 — 거부 경로 타이밍 부채널은 "포화 상태 한정 잔여 위험"으로 수용

거부 시 `clearIssuedTokenQuietly`가 요청 스레드에서 DB 트랜잭션 1회를 수행한다. 이 비용은 발급 트랜잭션과 같은 규모(수 ms)이고 **executor가 포화된 극단 상황에서만, 존재·적격 계정에 한해** 발생한다. 정상 상태의 "응답에 SMTP 지연이 실리지 않는다"는 원 설계 계약(발급 트랜잭션의 미세한 시간 차는 무시 수준)과 같은 등급이므로 새 완화 수단(비동기 정리 등)은 추가하지 않는다 — 대신 계획·문서에 잔여 위험으로 명시한다. 응답 코드·본문은 항상 200 동일(컨트롤러 계약 불변).

### 결정 5 — 로그: 신규 로그 추가 없음, 기존 로그의 민감정보 미포함을 테스트로 고정

기존 거부 로그는 `memberId`·예외 타입 이름만 남긴다(이메일·토큰·해시 미포함). 포화 지속 시 요청마다 error 로그가 남는 것은 진단 가능성 측면에서 유지한다(집계·메트릭은 범위 밖). 포화 테스트에서 로그 캡처로 이메일·토큰 해시·`#token=` 문자열 부재를 단언한다.

### 결정 6 — 회귀 방지 테스트 (설정 실적용 + 포화 동작)

1. **설정 적용 테스트**: `ApplicationContextRunner` + `TaskExecutionAutoConfiguration` 위에 `application.yml`을 `YamlPropertySourceLoader`로 실제 로드해 `applicationTaskExecutor`의 core/max/queue 용량이 4/4/20임을 단언한다(yml 값 삭제·오타 회귀 방지 — 값 복제 테스트가 아니라 yml 자체를 읽는다). 컨텍스트 러너는 DB·전체 앱을 기동하지 않는다.
2. **포화 동작 테스트**(단위, 실제 `ThreadPoolTaskExecutor` — 작은 값으로 동일 형태 core=max=2, queue=2. **운영 yml 값과는 무관한 "상한 executor 위에서 서비스가 올바르게 동작하는가"의 검증**이며 yml 회귀는 1번이 맡는다): `JavaMailSender.send`가 래치로 블록되게 하고 서로 다른 적격 계정 5건을 순차 `requestReset` → (a) 모든 호출이 예외 없이 반환, (b) 래치 유지 중 **실행 중 `send` 진입 정확히 2건·큐 대기 정확히 2건·5번째 제출 거부 1건**을 각각 단언(큐가 무제한이면 거부 단언이 실패하는 구조), (c) 거부된 건만 `clearResetTokenIfMatches` 호출·수락된 건은 미호출, (d) 래치 해제 후 수락된 4건은 전부 발송 완료, (e) 로그에 이메일·해시·`#token=` 부재.
3. **동일 계정 재요청·거부 시나리오 테스트**(리뷰 1차 지적 2): 계정 A 요청(수락·대기, 토큰 T1) → 쿨다운(60s) 경과 시각으로 Clock 전진 → A 재요청(토큰 T2 발급) 제출 거부 → T2만 조건부 클리어되고 **T1 메일은 이후 그대로 발송됨(= DB에 유효 토큰 없음)**을 현 동작으로 고정한다. 이 결과는 "결함 수정"이 아니라 **문서화된 잔여 위험의 동작 고정**이다(서비스 로직 무변경).
4. 기존 `PasswordResetServiceTest`·`PasswordResetMailTimeoutIntegrationTest`·`PasswordResetConcurrencyIntegrationTest` 무수정 통과(파급 0 확인).

## 수정 대상 파일

- 수정: `src/main/resources/application.yml` — `spring.task.execution.pool` 블록 추가(설명 주석 포함: 공용 executor·유일 소비자·TTL 근거·전용 빈 재평가 조건)
- 신규 테스트: 위 결정 6의 1·2 (`src/test/java/com/cms/config/` 또는 `admin/member/service/` — 패키지는 구현 시 기존 관례에 맞춰 확정)
- 문서: `com.cms.admin.member/CLAUDE.md`(비밀번호 재설정 항목에 상한 계약 1줄), 이 계획서 결과 섹션, `plan/README.md` 인덱스, (필요 시) `docs/deployment.md`의 SMTP 관련 절
- 미변경: 서비스·컨트롤러 코드, 스키마, `SecurityConfig`, 의존성

## 작업 순서

1. 브랜치 `fix/mail-executor-bound` 생성
2. `application.yml` 설정 추가 → `./gradlew compileJava`
3. 테스트 3종 작성 → 대상 테스트 실행 → 변이 확인(① `application.yml`의 pool 값 제거·오타 → **설정 적용 테스트**가 실패, ② 포화 테스트의 executor를 큐 무제한으로 바꾸면 **거부 단언(b)**이 실패)
4. `./gradlew test` 전체
5. 실기 검증: 응답하지 않는 로컬 SMTP 블랙홀 + `bootRun`(격리 포트, rate-limit 비활성 오버라이드) + 적격 계정 다수로 `POST /admin/api/password-reset-requests` 연속 호출 → 전 응답 200, 블랙홀 동시 연결 ≤ 4, 초과분 로그에 거부·토큰 클리어, 앱 힙/스레드 무한 성장 없음(`jcmd Thread.print` 스레드 수), 검증 데이터 원복
6. 문서 갱신

## 완료 기준

- [ ] `application.yml`이 core 4 / max 4 / queue 20을 선언하고 설정 테스트가 그 값을 실제 로드해 검증한다
- [ ] SMTP 무응답 상황에서 실행 중 발송 = core, 대기 작업 = queue 용량, 초과 유입은 거부됨을 테스트로 증명한다(보장 범위는 자원 상한이며 만료 링크 발송 방지는 포함하지 않는다)
- [ ] 같은 계정 재요청·거부 시 동작(T2 클리어, T1 메일 발송)이 테스트로 고정되고 잔여 위험으로 문서화된다
- [ ] 큐 포화 시에도 `requestReset`이 예외 없이 반환하고(컨트롤러 항상 200) 거부된 건의 토큰이 조건부 클리어된다
- [ ] 포화 경로 로그에 이메일·토큰(평문·해시)·링크가 없다
- [ ] 기존 재설정 관련 테스트가 무수정 통과, 전체 `./gradlew test` 통과
- [ ] 실기(블랙홀 SMTP)에서 스레드·연결 상한과 균일 200을 확인한다

## 리스크

| 리스크 | 대응 |
|---|---|
| 공용 executor를 앞으로 다른 기능이 쓰면 메일이 그 기능과 상한을 공유 | 설정 주석·CLAUDE.md에 명시, 소비자 추가 시 전용 빈 분리 재평가(remediation-plan 조건 유지) |
| 값(4/20)이 실제 규모에 맞지 않음 | Boot 프로퍼티라 환경변수(`SPRING_TASK_EXECUTION_POOL_QUEUE_CAPACITY` 등)로 재빌드 없이 조정 가능 — 문서 옵션은 신설하지 않음 |
| 포화 시 거부 경로 DB 호출이 요청 스레드에서 실행됨(타이밍) | 결정 4 — 포화 한정 잔여 위험 수용, 명시 |
| 셧다운 시 큐 잔여 작업 폐기 | 범위 밖(기존 계약), 30분 TTL·재요청으로 회복 |
| 작업 전체 deadline 부재(PR 5 잔여) — 큐 대기·점유로 **만료된 링크가 발송될 수 있음** | 이 작업은 큐·동시성 상한만 다룸, 만료 링크 방지는 보장하지 않음(전체 deadline·발송 직전 만료 검사는 별도 설계) |
| 같은 계정 재요청(60초 후) 제출이 거부되면 T2가 클리어되고 앞서 수락된 T1 메일은 **DB에 유효 토큰이 없는 무효 링크로 도착**(리뷰 1차) | 기존 발송 실패 경로에도 있던 동작이며 서비스 로직 변경은 범위 밖 → 잔여 위험으로 수용, 동작을 결정 6-3 테스트로 고정. 사용자는 링크가 무효면 다시 요청하면 된다(거부로 T2가 클리어돼 쿨다운 없이 재요청 가능) |

## 스키마·인가·의존성

- 스키마 변경 없음, 인가 정책 변경 없음, 신규 의존성 없음.

## 구현·검증 결과 (2026-09-30)

### Context
로드맵 Top 5(2026-09-05) ④ 잔여분. SMTP timeout(PR 5)·500 진단 로깅(PR 3)은 완료돼 있었고, 남은 "발송 executor 큐 상한"을 처리했다. 계획 리뷰 codex 2라운드(v3 ship) → 사용자 구현 승인(포화 시 정책 = 거부 + 토큰 조건부 클리어 + 항상 200, 값 4/4/20).

### 핵심 확정 사항
- `mailExecutor`는 전용 빈이 아니라 Boot 기본 공용 `applicationTaskExecutor`(정찰로 확정). `spring.task.execution.pool.{core-size: 4, max-size: 4, queue-capacity: 20}`로 상한을 건다 — 서비스·컨트롤러 코드 무변경, 기존 테스트 파급 0.
- 포화 시 `AbortPolicy` 거부 → 기존 `dispatchResetMail` catch 경로(오류 로그 + 조건부 토큰 클리어, 응답 200)가 그대로 계약을 담당한다.
- 보장 범위는 자원 상한("동시 실행 ≤ 4 · 대기 ≤ 20")뿐이다.

### 구현 파일
- 수정: `src/main/resources/application.yml`(pool 3값 + 설명 주석)
- 신규 테스트: `MailExecutorPoolConfigurationTest`(실제 yml을 읽어 Boot 자동 구성에 바인딩 — core/max/큐 용량 검증), `PasswordResetExecutorSaturationTest`(2건: 실행 2·대기 2·초과 거부 + 로그 민감정보 부재 / 같은 계정 재요청 거부 시 T2만 클리어·T1 메일은 DB에 없는 토큰의 링크로 발송)
- 문서: `com.cms.admin.member/CLAUDE.md`, 이 문서

### 검증 결과
- 전체 `./gradlew test` 825개(기존 822 + 신규 3), 실패·에러 0, 스킵 3(Windows 심볼릭 링크 — 기존과 동일).
- 변이 실험: yml `queue-capacity` 제거 → 설정 테스트 FAILED / 포화 테스트 executor 큐를 무제한으로 변경 → 포화 테스트 2건 FAILED. 원복 후 통과 확인.
- 실기(`bootRun` 8099, dev DB, 응답하지 않는 로컬 SMTP 블랙홀 + rate-limit 비활성 오버라이드, 적격 계정 30개 임시 삽입): 서로 다른 계정 30건 연속 `POST /admin/api/password-reset-requests` → **전부 200**(미존재 이메일도 200). 블랙홀 **동시 연결 최대 4**, DB 토큰 잔존 24건(실행 4 + 대기 20)·클리어 6건(마지막 6개 계정 — 서버 로그의 `TaskRejectedException` 6건과 일치). 로그에는 마스킹 이메일·memberId·예외 타입만 있고 전체 이메일·토큰·링크는 없음. 블랙홀 종료 후 큐가 빠지고(발송 실패 24건 로그) 새 요청은 거부 없이 수락 — executor 회복 확인. 임시 계정 30개 삭제·dev DB 5명으로 원복 확인.
- UI 변경이 없어 화면 검증·스크린샷은 해당 없음(이 세션에서 playwright MCP도 연결 끊김).

### 이슈
- 실기 중 `jcmd`/`jstack`으로 스레드 이름 목록을 얻지 못해 "스레드 수" 직접 관측은 못 했다 — 동시 SMTP 연결 수(블랙홀 max=4)로 동시 실행 상한을 대신 실증했다.

### 후속(범위 밖, 잔여 위험 그대로)
- 작업 전체 deadline·발송 직전 만료 검사 없음 → 큐 대기·점유로 만료된 링크가 발송될 수 있음.
- 같은 계정 재요청이 거부되면 앞서 수락된 메일은 무효 링크(테스트로 동작 고정).
- 포화 상태에서만 거부 경로의 DB 정리가 요청 스레드에서 실행됨(타이밍 잔여 위험 수용).
- 셧다운 시 큐 잔여 작업 폐기(기존 계약).
- 이 공용 executor를 다른 기능이 쓰게 되면 메일 전용 빈 분리 재평가.
