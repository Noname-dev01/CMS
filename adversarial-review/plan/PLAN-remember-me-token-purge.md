# PLAN — 만료된 remember-me 토큰 행 정리

> 작성일: 2026-11-11 · 출처: 로드맵 67차 후속 후보("만료된 `persistent_logins` 행 정리"), `PLAN-remember-me-login.md` "후속"·`com.cms.config` `CLAUDE.md` "수용한 한계"
> 상태: v3 — 계획 리뷰 3라운드 ship(2026-11-11) · 구현 후 코드 리뷰에서 조회 문장 제한 추가(아래 "코드 리뷰")
> **위험 변경: 데이터 정합성·동시성**(인증 행을 지운다 — 유효한 행을 지우면 사용자가 로그아웃된다). 스키마 변경 없음 · 인가 정책·로그인 정책·URL 규칙 변경 없음 · 의존성 변경 없음

## Context

- `persistent_logins`(V15)는 폼 로그인에서 "로그인 상태 유지"를 체크할 때마다 행 1개(시리즈)가 생긴다. 지워지는 경우는 폐기 사건(로그아웃·세션 만료·회원 변경·탈취)과 같은 브라우저의 재로그인(`loginSuccess`가 옛 시리즈를 지움)뿐이다.
- **버려진 기기**(쿠키를 지운 브라우저, 다시 오지 않는 PC)의 행은 7일이 지나 만료돼도 남는다. `AdminRememberMeServices.processAutoLoginCookie`는 만료된 토큰을 거부만 하고 행을 지우지 않는다.
- 만료된 행으로는 인증할 수 없다(만료 판정이 먼저 거부한다). 그래서 이 작업의 목적은 **보안 결함 수정이 아니라 데이터 위생**이다. 무기한 쌓이는 것을 막고, 쓸모없는 토큰 값과 아이디를 DB와 백업에 남기지 않는다.
- 코드베이스에는 스케줄러(`@EnableScheduling`·`@Scheduled`)가 없다. 시간이 지난 데이터는 **사용 시점에 정리**하는 것이 기존 관례다.
  - 읽은 지 90일 지난 알림: 로그인 성공 시 `LoginVisitRecorder`
  - 24시간 지난 쪽지 발송 이력: 발송 시

## 정찰 사실 (코드 근거)

| 사실 | 근거 |
|---|---|
| 만료 판정은 `stored.getDate() + 7일 < clock.instant()`이면 만료(엄격 부등호). 즉 `last_used == now − 7일`인 행은 아직 유효하다 | `AdminRememberMeServices.processAutoLoginCookie` |
| `last_used` 쓰기 두 경로는 모두 **밀리초 정밀도**다. 갱신은 `new Timestamp(lastUsed.getTime())`를 쓰고, 발급(라이브러리 `createNewToken`)은 `token.getDate()`(`java.util.Date`)를 그대로 JDBC 인자로 넘긴다. `JdbcTemplate`(`StatementCreatorUtils`)은 `java.util.Date`를 `new Timestamp(date.getTime())`로 바인딩하므로 둘 다 **JVM 기본 시간대의 벽시계 값**으로 `datetime(6)`에 저장된다. 입력 시각은 `Date.from(clock.instant())`다(v2 정정 — 리뷰 1-2) | `RememberMeTokenRepository.updateToken`, `AdminRememberMeServices.onLoginSuccess`, `PLAN-remember-me-login.md` 144행 |
| 인덱스는 PK(`series`)와 `username`뿐이고 `last_used` 인덱스는 없다 | `V15__persistent_logins.sql` |
| 저장소는 `JdbcTemplate`(트랜잭션 밖이면 자동 커밋)을 쓴다 | `RememberMeTokenRepository` |
| 로그인 후처리(방문 기록·알림 정리)는 폼 로그인(`VisitLoggingAuthenticationSuccessHandler`)과 자동 로그인(`RememberMeLoginVerificationFilter`)이 **인증 결정을 마친 뒤** 함께 부르고, 실패해도 로그인을 막지 않는다 | `LoginVisitRecorder` |
| 회원 변경 트랜잭션의 BEFORE_COMMIT 폐기(`removeAllForMember`)는 그 트랜잭션이 끝날 때까지 그 회원의 행(만료 행 포함)을 배타 잠근다. MariaDB 기본 `innodb_lock_wait_timeout`은 50초다 | `RememberMeTokenRevokeListener`, V15 |
| 공개·로그인 응답 경로의 쓰기는 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR …`로 대기를 제한하는 선례가 있다 | `ContentCounterWriter`, `NotificationRecorder.recordInquiryReceived` |
| 쪽지 이력 정리는 범위 DELETE 대신 "만료 id를 일관 읽기로 조회 → PK로 삭제"를 쓴다. 범위 DELETE의 갭·넥스트키 잠금이 다른 쓰기를 막을 수 있기 때문이다(R2-2) | `AdminMessageService.purgeExpiredSendLog`, `com.cms.admin.message` `CLAUDE.md` |
| 시험 파급: `VisitLoggingAuthenticationSuccessHandlerTest`가 `new LoginVisitRecorder(...)`로 직접 만든다. 나머지 슬라이스 시험은 `RememberMeTestConfig`·직접 mock을 쓴다 | 테스트 grep |

## 쟁점과 결정

### 쟁점 1 — 언제 정리하나

| 선택지 | 장점 | 단점 |
|---|---|---|
| (a) `@Scheduled` 주기 작업(첫 스케줄러) | 로그인이 없어도 정리된다 | 첫 스케줄링 기반(`@EnableScheduling`)이 들어온다. 시험 컨텍스트마다 작업이 돌아 다른 시험의 행을 지울 수 있어 끄는 장치가 필요하다. 다중 인스턴스에서는 중복 실행된다. 새 운영 설정(주기)도 생긴다 |
| **(b) 로그인 성공 시 전역 정리 — `LoginVisitRecorder`에 추가** | 기존 관례(알림 정리)와 같고 새 기반이 필요 없다. 행을 만드는 사건(로그인)이 정리도 일으키므로 크기가 묶인다 | 아무도 로그인하지 않는 동안에는 남는다 |
| (c) 행을 만드는 곳(`onLoginSuccess`)에서만 정리 | 생성 = 정리라 대칭적이다 | 폼 로그인 + 체크 경로에만 걸리고, `loginSuccess`의 fail-closed 예외 처리 안에 들어가 실패 격리 설계가 복잡해진다 |

**결정: (b).** 이유는 세 가지다.
- 행은 로그인할 때만 생기므로, 로그인할 때 정리하면 크기의 상한이 대략 "최근 7일 안에 쓴 기기 수 + 마지막 로그인 이후 만료된 수"로 유지된다.
- 아무도 로그인하지 않는 기간에 남는 행은 인증에 쓸 수 없고 새로 늘어나지도 않는다.
- (a)가 더 주는 것은 "로그인이 없는 기간의 위생"뿐인데, 그 대가로 새 기반·시험 격리·운영 설정이 필요하다. 과설계로 판단한다.

정리 범위는 **로그인한 회원 것만이 아니라 전역**이다. 버려진 기기의 주인은 다시 로그인하지 않을 수 있기 때문이다(알림 정리가 본인 것만 지우는 것과 다른 점). 자동 로그인 경로에서도 돈다. 비용은 작은 표에 대한 SELECT 한 번이다.

### 쟁점 2 — 삭제 형태와 인덱스

| 선택지 | 판단 |
|---|---|
| (a) 범위 DELETE `WHERE last_used < ?`(인덱스 없음) | 전체 스캔이고, 자동 커밋이어도 문장이 끝날 때까지 훑은 행을 잠근다(RR). 그동안 다른 기기의 `updateToken`(자동 로그인)이 기다린다. 표가 작아 짧지만 쪽지 선례가 피한 형태다 |
| (b) 범위 DELETE + `last_used` 인덱스(V16) | 잠금 범위는 줄지만 **스키마 변경**이 생기고, 갭 잠금이 남는다 |
| **(c) 일관 읽기로 만료 시리즈 조회 → PK 삭제(조건 재확인)** | 잠금 없는 읽기 + PK 행 잠금만 쓴다. 삭제 문장 `DELETE … WHERE series = ? AND last_used < ?`가 **조회 후 갱신된 행을 다시 거른다**. 스키마 변경이 없다 |

**결정: (c), V16 없음.** 쪽지 선례(R2-2)와 같은 형태다. 조회는 인덱스 없는 전체 스캔이지만 표 크기가 "관리자 수 × 기기 수"라 작고, 일관 읽기라 잠그지 않는다. 행 수가 문제가 되는 규모는 이 프로젝트의 전제(소수 관리자) 밖이다.

삭제는 시리즈마다 한 문장씩 보낸다(자동 커밋, 행마다 독립). `batchUpdate`는 문장 하나가 실패했을 때의 동작이 드라이버 설정(배치 재작성 등)에 따라 달라 쓰지 않는다. 한 번에 처리하는 건수 상한은 두지 않는다. 로그인할 때마다 정리하므로 한 번에 쌓이는 양이 작다.

**잠금 대기 상한(v2 — 리뷰 1-1)**: 정리는 로그인 응답 전에 동기로 돈다. 그런데 만료 행이 회원 변경 트랜잭션의 BEFORE_COMMIT 폐기 등으로 잠겨 있으면 PK DELETE가 기본 50초까지 기다리고, try/catch는 기다림이 끝난 뒤에야 동작한다. 그래서 각 DELETE를 선례(`ContentCounterWriter`)와 같은 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR DELETE …`로 감싼다. 조회는 일관 읽기라 잠금을 기다리지 않으므로 감싸지 않는다.

**부분 삭제 계약(v2 — 리뷰 1-3)**: 처음 실패한 문장(잠금 대기 초과·DB 오류)에서 정리를 멈추고 예외를 호출자에게 넘긴다. 호출자(`LoginVisitRecorder`)는 ERROR 로그를 남기고 로그인을 계속한다. 그 앞에서 이미 지운 행은 자동 커밋이라 지워진 채로 남고(허용), 남은 행은 다음 로그인에서 다시 시도한다. 

**경과 시간 예산(v3 — 리뷰 2-1)**: 첫 실패 중단만으로는 "각 문장이 1초 안에 성공하며 쌓이는" 대기를 막지 못한다. 그래서 정리 시작 시각을 `System.nanoTime()`으로 재고, **다음 DELETE를 보내기 전에** 경과가 2초를 넘었으면 멈춘다. 남은 행은 다음 로그인으로 넘긴다(점진적 정리). 한 로그인의 정리 대기는 최악의 경우 "예산 2초 + 마지막 문장 상한 3초"로 묶인다. `nanoTime`은 시각이 아니라 경과 측정이라 Clock 규약(`ClockUsageConventionTest`는 `now()`만 검사) 대상이 아니다. 대상 행이 0개면 측정하지 않는다.

### 쟁점 3 — 기준 시각과 시간대

- 기준 시각 `cutoff = clock.instant() − VALIDITY_SECONDS`(주입된 KST `Clock`)이고, **`Timestamp.from(Instant)`로 바인딩**한다. 쓰기와 같은 변환(Instant → JVM 기본 시간대의 벽시계)이라 JVM 시간대와 무관하게 대칭이다.
- `LocalDateTime.now(clock).minusDays(7)`(KST 벽시계)를 바인딩하면 안 된다. UTC JVM(CI)에서는 `last_used`가 UTC 벽시계로 저장돼 9시간 어긋난다. 그러면 **아직 유효한 행(만료까지 9시간 이내)을 지운다**.
- `VALIDITY_SECONDS`는 `AdminRememberMeServices.VALIDITY_SECONDS`를 그대로 쓴다(두 벌 금지 — 만료 판정과 정리 기준이 갈라지지 않게).

### 쟁점 4 — 경계

- 정리 조건은 `last_used < cutoff`(엄격)다. 만료 판정 `last_used + 7일 < now` ⇔ `last_used < now − 7일`과 정확히 같다.
- 따라서 `last_used == cutoff`인 행은 인증할 수 있고, 지우지도 않는다.

### 쟁점 5 — 실패 격리

- 정리 실패(DB 예외·잠금 대기 초과)는 로그인을 막지 않는다. 대기 시간은 쟁점 2의 문장 제한으로 묶는다. `LoginVisitRecorder`의 기존 try/catch + ERROR 로그 관례를 따른다.
- 순서는 방문 기록 → 알림 정리 → 토큰 정리이고, 셋은 서로 독립이다.
- `LoginVisitRecorder.record`는 ADMIN·MANAGER 로그인에서만 동작한다. remember-me를 쓰는 주체가 이 둘뿐이라 충분하다.
- 로그인 경로의 "요청당 커넥션 2개 금지" 규칙: 호출 시점에 열린 트랜잭션이 없고(`JdbcTemplate` 자동 커밋), 앞선 정리 단계가 끝나 커넥션을 반납한 뒤 실행하므로 동시에 2개를 잡지 않는다.

## 설계 제약 (명문화)

- 스케줄러·새 스타터·새 설정 키를 만들지 않는다.
- `persistent_logins`의 스키마를 바꾸지 않는다.
- 정리 기준의 유효 기간은 `AdminRememberMeServices.VALIDITY_SECONDS` 하나만 쓴다.
- 바인딩은 `Timestamp.from(Instant)`만 쓴다(`LocalDateTime` 금지).
- 정리 DELETE는 반드시 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1 FOR`로 감싼다(로그인 응답 대기 상한).

## 변경 파일

| 파일 | 변경 |
|---|---|
| `config/auth/RememberMeTokenRepository.java` | `findExpiredSeries(Instant cutoff)`(일관 읽기 `SELECT series … WHERE last_used < ?`)·`deleteExpiredSeries(String series, Instant cutoff)`(PK + 조건 재확인, `SET STATEMENT` 문장 제한), 이 둘을 묶는 `purgeExpired(Instant now)` — `cutoff = now − VALIDITY_SECONDS`, 지운 행 수 반환 |
| `config/auth/LoginVisitRecorder.java` | `RememberMeTokenRepository` 의존 추가, `tryPurgeExpiredRememberMeTokens()` — 전역 정리, 예외 격리. 클래스 주석 갱신 |
| `test/.../VisitLoggingAuthenticationSuccessHandlerTest.java` | 생성자 인자 추가(mock 저장소) |
| `test/.../RememberMeTokenPurgeIntegrationTest.java`(신규) | 아래 실패 모드 F1~F5 |
| `com.cms.config` `CLAUDE.md` | "수용한 한계"의 "만료 행 정리 작업 없음"을 정리 계약으로 교체 |

## 성공 기준

1. 폼 로그인 또는 자동 로그인이 성공하면, 그 시각 기준 `last_used < now − 7일`인 **모든 회원의** 행이 지워진다. 단 경과 예산(2초)을 넘으면 남은 행은 다음 로그인에서 이어서 지운다(점진적 정리).
2. 유효한 행(경계 포함)은 지워지지 않고, 그 쿠키로 자동 로그인이 계속 된다.
3. 정리가 실패하거나 행이 잠겨 있어도 폼·자동 로그인은 성공하고(잠금이 있는 경우 수 초 안에) ERROR 로그가 남으며, 남은 행은 다음 로그인에서 지워진다.
4. 스키마·`SecurityConfig`·의존성 변경이 없고, `ClockUsageConventionTest` 등 컨벤션 시험이 통과한다.
5. `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC`로 돌려도 신규 시험이 통과한다(시간대 대칭).

## 실패 모드와 고정 시험 (`RememberMeTokenPurgeIntegrationTest`, MariaDB Testcontainers + `MutableClock` `@Primary`)

| # | 놓치기 쉬운 입력·실패 | 고정 시험 |
|---|---|---|
| F1 | **경계 불일치**: `<=`로 쓰거나 기준을 다르게 잡아 아직 유효한 행을 지운다 | `MutableClock`을 **밀리초에 정렬된 시각**으로 고정한다. 행 3개를 운영과 같은 경로(`createNewToken` + `Date.from(instant)`)로 만든다 — `last_used = cutoff − 1ms`, `= cutoff`, `= now − 1일`. 먼저 저장된 `last_used`가 의도한 값인지 확인한 뒤 로그인한다. 첫 행만 사라지고, 경계 행은 남아 그 행이 만료 판정을 통과하는 행임을 확인한다(`cutoff`·`now`는 같은 고정 시계에서 나온다). µs 입력 시험은 두지 않는다 — 운영 쓰기 경로가 모두 밀리초라서(v2) |
| F2 | **시간대 비대칭**: KST `LocalDateTime`을 바인딩해 UTC JVM에서 9시간 어긋난다 | F1을 같은 시험 클래스에서 실행하고, 검증 단계에서 `-Duser.timezone=UTC`로 한 번 더 돌린다. 행은 운영과 같은 경로(`Timestamp`)로 넣는다 |
| F3 | **조회 후 갱신된 행 삭제**: 만료로 조회된 뒤 그 사이 갱신된 행을 PK만으로 지운다 | 스파이로 `findExpiredSeries`가 반환된 직후 그 행의 `last_used`를 현재로 갱신한다 → 행이 남는다(삭제 문장의 조건 재확인) |
| F4 | **정리 실패가 로그인을 깨뜨림** | ① `findExpiredSeries`에 `doThrow` → 폼 로그인 302(대시보드)·자동 로그인 200, 방문 기록이 남고 ERROR 로그(`OutputCaptureExtension`)가 남는다. ② 만료 행 2개 중 두 번째 `deleteExpiredSeries`에서 `doThrow` → 로그인은 성공하고, 첫 행은 지워지고 둘째 행은 남는다. 스파이를 해제하고 다시 로그인하면 둘째 행도 지워진다(부분 삭제·재시도 계약) |
| F7 | **성공한 대기의 누적**(v3 — 리뷰 2-1) | 만료 행 10개를 만들고, 스파이로 각 `deleteExpiredSeries`를 0.5초 늦춘 뒤 실제 삭제를 하게 한다 → 로그인 응답이 4초 안에 오고, 일부 행이 남는다. 스파이를 해제하고 다시 로그인하면 모두 지워진다 |
| F6 | **잠금 대기로 로그인 지연**(v2 — 리뷰 1-1) | 별도 커넥션에서 트랜잭션을 열고 만료 행을 `SELECT … FOR UPDATE`로 쥔 채 폼 로그인과 자동 로그인을 보낸다 → 각각 성공 응답이 **5초 안에** 돌아오고, 그 행은 남는다. 잠금을 풀고 다시 로그인하면 지워진다 |
| F5 | **범위 오류**: 로그인한 회원 것만 지우거나 다른 회원의 유효한 행을 지운다 | 회원 A가 로그인하면 회원 B의 만료 행은 사라지고 B의 유효 행은 남는다. 자동 로그인 경로에서도 정리가 도는지 확인한다 |

변이 실험(구현 후): ① `<`→`<=` ② 삭제 문장에서 `AND last_used < ?` 제거 ③ cutoff를 `LocalDateTime.now(clock)` 바인딩으로 교체(UTC JVM) ④ try/catch 제거 ⑤ `SET STATEMENT` 제거 ⑥ 경과 예산 검사 제거. 각각 F1·F3·F2·F4·F6·F7이 실패하는지 확인한다.

## 리스크

| 리스크 | 대응 |
|---|---|
| 유효한 행을 지워 사용자가 로그아웃된다 | F1·F2·F3으로 고정하고, 기준 상수를 하나만 쓴다 |
| 로그인 지연 | 작은 표 SELECT 1회 + 드문 PK 삭제. 잠긴 행은 문장 제한(잠금 대기 1초, 문장 3초)·첫 실패 중단·경과 예산 2초로 대기를 묶는다(F6·F7). 실기에서 로그인 응답을 확인한다 |
| 로그인이 없는 기간에 행이 남는다 | 수용한다 — 인증에 쓸 수 없고 늘지도 않는다. 문서에 기록한다 |
| 다중 인스턴스 | 전역 정리가 여러 인스턴스에서 동시에 돌아도 PK + 조건 삭제라 멱등이다(현재는 단일 인스턴스 전제) |

## 작업 단계

1. 브랜치 `feat/remember-me-token-purge`
2. TDD — `RememberMeTokenPurgeIntegrationTest` F1·F3·F5(저장소 + 로그인 경로)를 먼저 쓰고 실패를 확인한다 → 저장소 메서드 → `LoginVisitRecorder` 연결 → F4·F6·F7
3. `VisitLoggingAuthenticationSuccessHandlerTest` 생성자 수정, `./gradlew compileJava compileTestJava`
4. 영향 범위 시험: `com.cms.config.*`·`*ConventionTest`, 그리고 UTC JVM으로 신규 시험을 한 번 더 실행
5. 변이 실험 6종
6. 실기: bootRun → 체크 로그인 → DB에서 행의 `last_used`를 8일 전으로 수정 → 다른 계정으로 로그인 → 행 삭제 확인. 유효 행 쿠키의 자동 로그인 회귀도 확인한다
7. 기록: `com.cms.config` `CLAUDE.md`, 이 문서의 결과 절, `plan/README.md` 행 추가

## 개정 이력

- v1: 최초 작성
- v2 변경(리뷰 1라운드): ① 정리 DELETE에 `SET STATEMENT max_statement_time=3, innodb_lock_wait_timeout=1`을 걸고, 첫 실패에서 중단하는 부분 삭제·다음 로그인 재시도 계약을 추가했다. 잠금을 쥔 채 로그인하는 시험 F6을 추가했다(수용 — BEFORE_COMMIT 폐기가 행을 잡으면 기본 50초 대기가 실제로 가능). ② F1을 밀리초 정렬 시계와 운영 경로(`createNewToken` + `Date`)로 바꿨다(수용 — 운영 쓰기가 밀리초뿐이다). ③ `createNewToken`이 `Date`를 그대로 넘긴다는 사실을 정정했다(수용, 변환 결과는 같다). ④ F4에 삭제 단계 실패·부분 삭제·로그 단언을 추가했다(수용). 삭제를 `batchUpdate`에서 문장별 실행으로 바꿨다(실패 지점을 확정하려고).
- v3 변경(리뷰 2라운드): ① 정리 전체에 경과 예산 2초를 두고, 다음 DELETE를 보내기 전에 확인해 넘으면 다음 로그인으로 넘긴다(수용 — 각 문장이 상한 안에서 성공해도 대기가 쌓일 수 있다). 성공 기준 1을 점진적 정리로 고치고 F7을 추가했다.
- v3 정정(리뷰 3라운드 ship): 작업 단계 5의 변이 실험 수를 6종으로 맞췄다.
- v3 기각: 조회 SELECT의 메타데이터 잠금(MDL) 대기 상한 — MDL 대기는 DDL·`LOCK TABLES` 유지보수 때만 생기고, 그때는 로그인 경로가 원래 쓰는 `member`·`visit_log`·`notification`도 같이 기다린다. 이 표만 막아도 로그인 보호가 생기지 않고, 이 변경이 새로 만드는 실패 유형도 아니다.
- v2 기각: DB 마이크로초 경계 시험 — 운영에서 µs `last_used`를 쓰는 경로가 없고, 비교 의미(`<`)는 ms 경계 시험이 고정한다.

---

## 구현·검증 결과 (2026-11-11, PR #227 `b06e201` 머지 — CI `test`·`browser-test`·`prod-smoke` SUCCESS)

### Context
로드맵 67차 후속 후보. `persistent_logins`의 만료 행을 아무도 지우지 않던 문제를, 스케줄러 없이 로그인 성공 시 전역 정리로 해결했다.

### 핵심 확정 사항
- 계획 v3 그대로 구현했다. 계획과 달라진 결정은 없다.
- 스키마·인가 정책·로그인 정책·의존성·설정 키 변경 없음.

### 구현 파일
| 파일 | 변경 |
|---|---|
| `src/main/java/com/cms/config/auth/RememberMeTokenRepository.java` | `purgeExpired(Instant now)`(공개), `findExpiredSeries`·`deleteExpiredSeries`(패키지 — 시험 스파이 지점), `PURGE_BUDGET`(2초), `STATEMENT_LIMITS` |
| `src/main/java/com/cms/config/auth/LoginVisitRecorder.java` | 저장소 의존 추가, `tryPurgeExpiredRememberMeTokens` — 방문 기록·알림 정리 뒤, 예외 격리 |
| `src/test/java/com/cms/config/auth/RememberMeTokenPurgeIntegrationTest.java` | 신규 8건(F1·F5, F5 자동 로그인, F3, F4①, F4②, F6, F7, F8 — F8은 코드 리뷰 반영) |
| `src/test/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandlerTest.java` | 생성자 인자 추가(mock 저장소) |
| `src/main/java/com/cms/config/CLAUDE.md` | "만료 행 정리" 계약, 수용한 한계 갱신, 시험 목록 |

### 검증 결과
- **TDD**: 저장소 메서드만 있고 연결이 없는 상태에서 7건 중 6건이 "정리가 일어나지 않음"으로 실패했다(RED). F3은 이 시점에 그냥 통과했지만, 변이 ②가 F3을 실패시켜 시험이 의미 있음을 확인했다. 연결 후 7/7 통과(GREEN).
- **UTC JVM**: `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC ./gradlew cleanTest test --tests '…RememberMeTokenPurgeIntegrationTest'`에서 7/7 통과했다. 로그 시각이 `Z`로 찍혀 JVM 시간대가 UTC였음을 확인했다.
- **변이 실험 6종** — 모두 의도한 시험에서 실패했다:

| 변이 | 실패한 시험 |
|---|---|
| ① `<` → `<=` | F1 |
| ② 삭제 문장의 `last_used < ?` 재확인 제거 | F3 |
| ③ KST `LocalDateTime` 바인딩(UTC JVM) | F1 |
| ④ try/catch 제거 | F4①·F4②, 그리고 F6 — 잠금 대기 초과 예외도 로그인까지 전파된다 |
| ⑤ `SET STATEMENT` 제거 | F6 |
| ⑥ 경과 예산 검사 제거 | F7 |

- **영향 범위 시험**: `com.cms.config.*`·`com.cms.admin.notification.*`·`com.cms.admin.session.*`·`com.cms.common.api.*`·`com.cms.admin.member.*`·`*ConventionTest` 809건 통과(실패 0, 건너뜀 0). 코드 리뷰 반영(F8) 뒤 **전체 `./gradlew test` 3,133건 통과**(실패 0, 건너뜀 12 — 직전 머지 #225 기준과 같은 건너뜀 수이고, 이번 변경과의 관련은 따로 확인하지 않았다). F8 추가 후 신규 시험 8건은 UTC JVM에서도 통과했다.
- **실기**(dev bootRun + 로컬 MariaDB + Playwright):
  - 다른 아이디의 만료 행 2개(`ghost-user`·`admin`, 약 8일 9시간 전)와 유효 행 1개(약 6일 9시간 전)를 넣었다. 임시 MANAGER가 "로그인 상태 유지"로 폼 로그인(736ms)하자 만료 2개는 지워지고 유효 행은 남았다. 새 기기 행의 `last_used`가 KST 벽시계였다.
  - 만료 행을 하나 더 넣고 세션 쿠키만 지운 뒤 재접속했다. 자동 로그인으로 대시보드가 200으로 열렸고, 토큰 교체·방문 +1이 일어났으며, 만료 행이 지워졌다(스크린샷 `.playwright-mcp/remember-purge-autologin-dashboard.png`).
  - 권한 경계·회귀: MANAGER의 세션 관리 화면·API는 403, 로그아웃은 이 기기 행과 쿠키를 제거, ADMIN이 체크 없이 로그인하면 정상이고 쿠키 없음, 유효 행은 유지, 정리 실패 로그 0건.
  - 실기 데이터(임시 MANAGER·토큰 행·방문 기록)는 지웠다.

### 코드 리뷰 (code-review-loop, 2026-11-11)
- **1라운드 기본 리뷰(`review`)**: 지적 0건.
- **위험 변경 확인 → 적대적 리뷰(`adversarial-review`) 1라운드**: needs-attention 1건 [medium] — 조회 SELECT에 대기 상한이 없다. 다른 연결이 `LOCK TABLES persistent_logins WRITE`로 이 표만 잡으면 메타데이터 잠금 대기(기본 `lock_wait_timeout` 1일)로 **remember-me를 쓰지 않는 폼 로그인까지** 멈춘다는 지적이다.
  - **수용**. 계획 v3에서 "DDL 유지보수 때는 다른 표도 기다린다"는 근거로 기각했던 주제다. 하지만 이 표만 잠기는 경우는 그 근거가 성립하지 않는 새 회귀라서 판단을 바꿨다.
  - 반영: `STATEMENT_LIMITS`에 `lock_wait_timeout=1`을 더하고 조회 SELECT에도 적용, 경과 예산 측정을 조회 전으로 옮김, 시험 F8 추가.
  - 확인: 수정 전 코드에서 F8이 10초 시간 초과로 실패(RED), 수정 후 1.21초에 통과.
  - 변이 결과: `lock_wait_timeout`만 빼면 F8이 **통과**한다(3.2초) — `max_statement_time=3`이 메타데이터 잠금 대기도 끊기 때문이다. 상한은 두 겹(1초 / 3초 최종 저지선)이다. F8 기준을 5초로 둔 것은 느린 CI의 간헐 실패를 피하려는 선택이고, 그 대가로 이 단일 변이는 잡지 못한다.
- **적대적 리뷰 2라운드**: approve(지적 0건).

### 이슈
- 처음 시험을 실행할 때 Docker Desktop이 꺼져 있어 `initializationError`(Testcontainers가 Docker를 찾지 못함)가 났다. 코드 문제가 아니라 환경 문제였고, Docker를 기동한 뒤 재실행했다.
- 변이 실험 스크립트의 백업 경로를 잘못 지정해, 1차 실행에서 M1 변이가 남은 채로 M2·M3가 돌았다. 그 결과는 무효 처리했다. 원본을 복구해 확인한 뒤 M2~M6을 다시 실행했다(위 표가 재실행 결과).
- Git Bash의 `TZ=Asia/Seoul date`는 시간대를 적용하지 않았다. 실기 데이터의 시각은 UTC 기준으로 계산됐지만, KST 기준으로도 만료·유효 조건이 그대로 성립해 결과에 영향은 없었다.
- 경계 시험의 `Role` 열거값 이름(`ROLE_ADMIN`) 오타는 컴파일 단계에서 바로 고쳤다.

### 후속
- 로드맵 반영은 `/updateRoadmap`이 맡는다(67차 후속 후보 중 "만료된 `persistent_logins` 행 정리").
- 로그인이 없는 기간의 정리가 필요해지면 그때 스케줄러를 재평가한다.
