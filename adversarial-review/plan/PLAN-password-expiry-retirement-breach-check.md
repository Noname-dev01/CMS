# PLAN — 비밀번호 90일 만료 폐지 + 유출 비밀번호 차단

> 출처: `/suggestRoadmap`에서 고른 "비밀번호 이력 재사용 금지"(로드맵 "이번 선정에서 탈락한 후보")를 논의하던 중, 사용자가 방향 전환을 결정했다(2026-11-05).
> 상태: **PR 1(#203) 머지 완료(2026-11-05), PR 2 구현·검증 완료(브랜치 `feat/retire-password-expiry`, 미커밋)**. 결과는 문서 끝.

## 개정 이력

- v1 (2026-11-05): 최초 작성.
- v2 변경: 1라운드 지적 3건 수용. ① 강제 변경을 같은 비밀번호로 끝낼 수 있음 → 변경·재설정 잠금 안에서 현재 값과 비교해 거부(D5, 오류 코드를 `PASSWORD_REJECTED`로 통합, PR 1에 포함). ② 세션 폐기 실패를 동일값 재저장으로 복구 불가 → `idempotentRelock`에 `PASSWORD_EXPIRED` 추가(D6). ③ 만료·자격 상실 토큰에 유출 오류 반환 → 판정은 트랜잭션 전에 받아 두고 거부는 기존 검증을 모두 통과한 뒤에 한다(D4, 모든 경로 공통).
- v3 변경: 2라운드 지적 3건 반영. ① 구버전이 V14 뒤에 알림을 재생성하는 경합 → PR 2 배포 전제에 구버전 종료 후 신버전 기동 명시(D8). enum을 한 릴리스 더 두는 제안은 실배포 전이고 단일 인스턴스 구성이라 기각. ② 부트스트랩 기동 실패가 배포 스크립트의 안정성 확인보다 늦을 수 있음 → 문제는 수용하되 readiness 도입 제안은 범위 초과로 기각하고, 부트스트랩은 유출이어도 WARN만 남기고 기동한다(D5). ③ 손상된 응답을 "미유출"로 처리할 수 있음 → 엄격 파싱(D1).

## Context

- **문제**: 비밀번호 변경·재설정이 새 비밀번호를 기존 값과 비교하지 않아, 만료된 사용자가 같은 값을 다시 넣으면 90일이 새로 시작된다. 재사용 금지로 막는 안을 검토했다.
- **근거 조사**: NIST SP 800-63B rev.4는 주기적 변경 강제를 금지(`SHALL NOT require subscribers to change passwords periodically`)하고 침해 증거가 있으면 변경을 강제(`SHALL force a change`)하며, 후보 비밀번호를 유출·흔한 비밀번호 목록과 비교하라고 한다. 내 이전 비밀번호 재사용 금지는 요구 사항에 없다. OWASP Authentication Cheat Sheet도 주기적 변경을 피하고 유출 비밀번호를 차단하라고 한다.
- **결정**: 재사용 금지 작업을 중단한다. 시간 기반 만료를 없애고 유출 비밀번호 차단을 넣는다. 이력 테이블은 만들지 않는다.

## 정찰 결과(코드에서 확인한 사실)

### 비밀번호를 정하는 지점 — 4곳

| 지점 | 트랜잭션 | 인증 |
|---|---|---|
| `AdminMemberService.createAdmin` | `@Transactional` + `@AdminActionLogged` | ADMIN |
| `AdminMemberService.changeMyPassword` | `@Transactional` + `@AdminActionLogged`, 첫 문장이 회원 `FOR UPDATE` | 세션 + 현재 비밀번호 |
| `PasswordResetService.resetPassword` | `@Transactional`, 스칼라 id 조회 → 회원 `FOR UPDATE` → 토큰 재검증 | 무인증, 토큰. 레이트리밋 `reset-confirm` 20/3600초 |
| `AdminBootstrapLoader.run` | 저장만 `TransactionTemplate` | 기동 시 환경변수 |

관리자가 다른 사람의 비밀번호를 바꾸는 경로는 없다(`AdminMemberUpdateRequest`에 비밀번호 필드 없음). `TestMemberLoader`는 dev 고정 계정이라 대상이 아니다.

### 90일 만료가 걸린 코드

- `PasswordExpiryService`(`PASSWORD_EXPIRY_DAYS`)와 `MemberRepository`의 조건부 벌크 UPDATE.
- `CustomUserDetailsService.loadUserByUsername` — 전이 호출, `noRollbackFor = CredentialsExpiredException`, 상태 분기.
- `VisitLoggingAuthenticationSuccessHandler` — 전이 재판정, E3 호출. `LoginFailureService.MemberSnapshot.passwordChangedAt`은 E3만 쓴다.
- 알림 E3: `NotificationRecorder.recordPasswordExpiryIfNear`, `NotificationRepository.insertPasswordExpiryIfCurrent`, `NotificationMessages.passwordExpiring`, `NotificationType.PASSWORD_EXPIRY`. `Notification.type`은 `@Enumerated(STRING)`이라 **값을 지우면 남은 행을 읽을 때 실패한다**.
- `PASSWORD_EXPIRED` 상태 소비자: `CustomUserDetails.isCredentialsNonExpired`, `PasswordResetService.isEligible`, `Member.changePassword`(ACTIVE 복귀), `AdminBootstrapLoader.ELIGIBLE_STATUSES`, 쪽지 수신 가능 집합, 알림 라벨, 화면 3곳.
- 시험: 위 키워드가 36개 파일 126곳에 있다. 대부분 빌더의 `.passwordChangedAt(now)`이고, 만료 동작 자체는 `PasswordExpiryServiceTest`·`PasswordExpiryIntegrationTest`·`NotificationLoginIntegrationTest`·`NotificationRepositoryDataJpaTest`·`VisitLoggingAuthenticationSuccessHandlerTest`에 모여 있다.
- `password_changed_at`은 `NOT NULL` 컬럼이다.

### 지금은 강제 변경 수단이 없다

`AdminMemberUpdateRequest`의 `@AllowedStatuses`는 `ACTIVE`·`LOCKED`·`DISABLED`만 받는다. `LOCKED`·`DISABLED`는 재설정 자격(`isEligible`)이 없어 "막고 새 비밀번호를 받게" 할 수 없다. 90일 전이가 유일한 변경 강제 수단이었다.

### 외부 호출

- 선례는 `TurnstileCaptchaVerifier`다: JDK `HttpClient`, `sendAsync` + `get(상한)`으로 벽시계 제한(`HttpRequest.timeout`은 헤더 수신 뒤 본문 지연을 막지 못함), 로그는 예외 클래스명만, 트랜잭션 밖 호출, 시험용 이음매 인터페이스. 설정은 `docker-compose.prod.yml` 매핑·`scripts/_prod-env-guard.sh`·`.env.example`에 함께 등록한다.
- Spring Security 7.0.8 `HaveIBeenPwnedRestApiPasswordChecker`(소스 확인): 타임아웃 설정 없음, `User-Agent`·`Add-Padding` 헤더 설정 없음, 호출 실패는 오류 로그(예외 포함) 후 "유출 아님". `CompromisedPasswordChecker` **빈을 등록하면 `DaoAuthenticationProvider`가 자동으로 집어 로그인 때마다 검사한다**(공식 문서).
- HIBP Pwned Passwords API(공식 문서): `GET https://api.pwnedpasswords.com/range/{SHA-1 앞 5자}`, 키 불필요·레이트리밋 없음, `User-Agent` 필수(없으면 403), `Add-Padding: true`면 응답을 800~1,000건으로 패딩하고 패딩 행은 횟수 0.

### 화면

- `admin-settings.html`은 서버의 `message`를 그대로 보여 준다.
- `password-reset-confirm.html`은 401·403이 아닌 오류를 전부 "링크가 유효하지 않거나 만료되었습니다"로 표시한다. **그대로면 유출로 거부된 사용자가 멀쩡한 링크를 새로 요청하게 된다.**
- 로그인 실패 화면(`/admin/login-error`)은 사유와 무관하게 한 문구다. 만료 계정도 지금 같은 문구를 본다.

## 핵심 쟁점과 결정

### D1. 유출 판정 원천 — **HIBP range API를 JDK `HttpClient`로 직접 호출**

| 안 | 장점 | 단점 |
|---|---|---|
| **A. HIBP API + 자체 클라이언트(채택)** | 데이터가 크고 갱신을 맡길 수 있음, 새 의존성 없음, Turnstile과 같은 호출 규약 | 외부 호출, 장애 시 판정 불가 |
| B. Spring `HaveIBeenPwnedRestApiPasswordChecker` | 코드가 적음 | 타임아웃·UA를 직접 구성해야 함, 빈으로 등록하면 로그인에도 적용됨, 실패 구분 불가 |
| C. 목록 내장·자체 호스팅 | 외부 호출 없음 | 내장 목록은 15자 이상에 걸리는 항목이 적을 것으로 보이고, 전체 목록 운영은 이 규모에 과함 |

**왜**: 필요한 코드가 수십 줄이고 호출 규약의 선례가 있다. B를 일반 객체로 쓸 수도 있지만 실패와 "유출 아님"을 구분할 수 없어 D3의 WARN·시험을 맞추기 어렵다.

구현 규약:
- `BreachedPasswordChecker`(시험 이음매 인터페이스, `CaptchaVerifier`와 같은 역할)와 구현 `HibpBreachedPasswordChecker`. **Spring의 `CompromisedPasswordChecker`를 구현하지 않는다** — 빈이 되면 로그인 자동 검사가 켜진다. 컨벤션 시험으로 그런 빈이 없음을 고정한다.
- SHA-1(UTF-8) 대문자 hex의 앞 5자만 전송한다. `Add-Padding: true`, 일반 이름의 `User-Agent`(이메일 등 개인정보 없음).
- 응답은 `접미부:횟수` 줄. 횟수가 1 이상인 일치가 있으면 유출이다. 그 밖에는 **비어 있지 않은 모든 줄의 형식이 유효해야**(접미부 35자 hex, 횟수는 범위 안의 숫자) 미유출이다. 200이 아니거나, 유효한 줄이 없거나(패딩 응답은 비어 있을 수 없다), 잘못된 줄이 있으면 "판정 불가"다. 리다이렉트는 따르지 않는다.
- 비밀번호·해시·접두부·응답 본문을 로그에 남기지 않는다.
- 호출 전체 3초 벽시계 상한(연결 2초). 사용자 대기를 줄이려고 Turnstile의 5초보다 짧게 둔다.

### D2. 검사 시점 — **비밀번호를 정할 때만, 로그인 때는 하지 않음**

**왜**: NIST의 비교 시점이 설정·변경 때이고, 로그인에 외부 호출을 넣으면 로그인 지연과 장애 의존이 생긴다. 올바른 비밀번호로 로그인이 거부되면 혼란스럽다는 점도 Spring 문서가 지적한다.

**수용한 한계**: 설정 뒤에 유출된 비밀번호는 잡지 못한다. 만료가 없어져 **검사 도입 전에 만든 비밀번호도 스스로 바꾸기 전까지 검사받지 않는다.** 실배포 전이라 지금 운영 계정이 없어 영향이 작다. 침해 증거가 있으면 D6의 강제 변경을 쓴다.

### D3. 판정 불가 시 — **통과시키고 WARN(fail-open)**

| 안 | 결과 |
|---|---|
| **fail-open(채택)** | 외부 장애가 탈취 의심 시의 긴급 변경을 막지 않는다. 그 사이 유출 비밀번호가 통과할 수 있다 |
| fail-closed | 항상 검사되지만 HIBP 장애나 운영망 차단이면 **어떤 계정도 비밀번호를 바꿀 수 없다** |

**왜**: 문의 폼 캡차가 fail-closed인 것은 문의가 기다려도 되기 때문이다. 비밀번호 변경은 보안 사고 대응 경로라 기다릴 수 없다. 15자 규칙은 그대로 남는다. 운영망이 외부 HTTPS를 막으면 검사가 조용히 꺼진 것과 같으므로 WARN 로그로 드러내고 `docs/deployment.md`에 확인 절차를 쓴다.

### D4. 외부 호출 위치 — **트랜잭션과 회원 행 잠금 밖**

외부 호출 3초 동안 커넥션과 `FOR UPDATE`를 쥐면 안 된다(Turnstile 규약). 서비스 메서드의 `@Transactional`을 내부 `TransactionTemplate`으로 옮긴다. 컨트롤러에서 호출하는 안은 규칙이 서비스 밖으로 나가고 감사 FAIL 행이 남지 않아 택하지 않는다. `@AdminActionLogged`는 메서드 바깥을 감싸므로 그대로 동작하고, 유출 거부도 감사 FAIL로 남는다(메시지는 상수).

**판정과 거부를 분리한다**: 외부 판정은 트랜잭션 전에 받아 지역 변수에 두고, 거부는 트랜잭션 안에서 기존 검증을 모두 통과한 뒤에 한다. 기존 오류의 우선순위와 응답이 그대로 유지된다.

| 지점 | 순서 |
|---|---|
| `changeMyPassword` | 새 비밀번호·확인 일치 → **판정** → 트랜잭션(잠금 → 현재 비밀번호 확인 → **유출이면 거부** → **현재 값과 같으면 거부** → 변경) |
| `resetPassword` | 일치 확인 → 스칼라 토큰 조회, 없으면 기존 400 → **판정** → 트랜잭션(기존 잠금·토큰·만료·자격 검증 → **유출이면 거부** → **현재 값과 같으면 거부** → 변경) |
| `createAdmin` | **판정** → 트랜잭션(아이디·이메일 중복 확인 → **유출이면 거부** → 저장) |
| 부트스트랩 | 형식 검증 뒤 **판정**, 유출이면 WARN(D5) → 저장 |

- `resetPassword`가 토큰을 먼저 보는 이유: 무인증 경로라 없는 토큰으로 외부 호출을 일으키지 않게 한다. 없는 토큰의 응답은 비밀번호와 무관하게 지금과 같다. 만료·자격 상실·검사 중 소비된 토큰은 트랜잭션 안의 기존 검증이 기존 400으로 끝낸다.
- `changeMyPassword`는 현재 비밀번호 확인 전에 외부로 나간다. 세션을 가진 사용자만 가능하고 나가는 값이 해시 앞 5자뿐이다. 현재 비밀번호가 틀리면 응답은 기존 오류다.
- 거부 시 쓰기가 없어 재설정 토큰이 살아 있다. 사용자는 같은 링크 화면에서 바로 다시 입력한다.

### D5. 거부 응답 — **400 + 새 코드 `PASSWORD_REJECTED`**

- `PasswordRejectedException extends InvalidRequestException`(`common.exception`), `GlobalApiExceptionHandler`에 전용 핸들러 하나.
- 메시지는 사유별 상수 두 개다. NIST가 거부 사유 제공을 요구한다.
  - 유출: "유출된 적이 있는 비밀번호입니다. 다른 비밀번호를 입력해 주세요."
  - 현재 값과 같음: "현재 비밀번호와 다른 비밀번호를 입력해 주세요."
- **현재 값과 같으면 거부**(BCrypt 1회, 회원 행 잠금 안): 이력 테이블 없이 강제 변경이 알려진 비밀번호로 끝나는 것을 막는다. HIBP 장애와 무관하게 동작한다. `PASSWORD_EXPIRED` 해제 때만이 아니라 모든 변경에 적용한다 — 같은 값으로 "변경"할 이유가 없고 분기가 없는 편이 단순하다. **한계**: 직전 비밀번호로 돌아가는 A→B→A는 막지 않는다(재사용 금지는 범위 밖).
- `password-reset-confirm.html`은 응답 본문의 `code`가 `PASSWORD_REJECTED`일 때만 서버 메시지를 보여 주고 나머지는 지금 문구를 유지한다. 메시지 문자열 비교는 깨지기 쉬워 쓰지 않는다.
- 부트스트랩: 유출이어도 **기동을 막지 않고 WARN**만 남긴다(메시지에 비밀번호 없음). 기동을 외부 판정에 묶으면 러너가 health 응답보다 늦게 실패할 수 있어 배포 스크립트가 성공으로 보고한 뒤 컨테이너가 재시작한다. 값은 운영자가 직접 정하고, `docs/deployment.md`에 "첫 로그인 후 변경"을 적는다. 변경 때는 정상적으로 검사된다. **수용한 한계**: 운영자가 WARN을 무시하고 바꾸지 않으면 유출된 값이 남는다. CI `prod-smoke`는 외부 의존을 피하려고 `CMS_PASSWORD_BREACH_CHECK_ENABLED=false`를 넣는다.

### D6. `PASSWORD_EXPIRED` 상태 — **유지하고 관리자의 "비밀번호 변경 강제"로 쓴다**

| 안 | 장점 | 단점 |
|---|---|---|
| **A. 상태 유지 + 관리자 지정 허용(채택)** | 침해 시 강제 변경 수단이 생김, DB enum 변경 없음, 롤백 안전, 수정 범위 작음 | 이름이 의미와 살짝 다름(`EXPIRED`), 기능이 하나 늘어남 |
| B. 상태 완전 제거 | 깔끔함 | enum 축소 DDL, 소비자·시험 대량 수정, 강제 변경 수단이 없음, 이전 앱 롤백 때 전이 쿼리 실패 |
| C. 상태만 남기고 쓰는 경로 없음 | 수정 최소 | 죽은 코드 |

**왜**: 90일을 없애면 유일한 변경 강제 수단이 사라지는데, NIST·OWASP 모두 침해 시 변경을 요구한다. 기존 경로를 거의 그대로 쓴다.

- `@AllowedStatuses`에 `PASSWORD_EXPIRED`를 더하고, 회원 수정 화면 선택지에 "비밀번호 변경 필요"로 추가, 표시 라벨 통일. 코드 값은 바꾸지 않는다.
- 기존 흐름이 처리한다: 최후 활성 ADMIN 가드, 상태 변경 시 세션 만료, 로그인 거부, 재설정 자격, `changePassword`의 ACTIVE 복귀, 알림 E1.
- `updateAdminMember`의 `idempotentRelock`에 `PASSWORD_EXPIRED`를 더한다. 세션 만료는 best-effort이고 동일값 재저장이 복구 절차라, 첫 폐기가 실패해도 다시 저장해 재시도할 수 있어야 한다.
- 강제 변경은 현재 값과 같은 비밀번호로 끝낼 수 없다(D5).
- **한계**: 로그인 화면은 일반 실패 문구라 대상자는 사유를 모른다. 사유를 보여 주면 비밀번호 검증 전에 상태가 드러나므로 바꾸지 않고, 관리자가 알리는 운영 절차로 둔다. 재설정 메일을 받을 수 있어야 복구된다.

### D7. 시간 기반 만료 제거 범위

**제거**: `PasswordExpiryService`, 벌크 UPDATE, 로그인·성공 처리의 전이 호출, `noRollbackFor`, E3 일체(`recordPasswordExpiryIfNear`·`insertPasswordExpiryIfCurrent`·`passwordExpiring`·`NotificationType.PASSWORD_EXPIRY`), `MemberSnapshot.passwordChangedAt`. 스냅샷을 쓸 곳이 없어지면 `verifyFreshMemberState`는 `boolean`으로 되돌린다.

**유지**: `password_changed_at` 컬럼과 갱신. 변경 시각 기록이고, 이전 앱 롤백에 필요하며, 지우면 시험 빌더 수십 곳이 함께 바뀌어 얻는 것이 없다. 주석만 고친다.

### D8. 마이그레이션 — **`V14__retire_password_expiry.sql`, DML만**

```sql
UPDATE member SET status = 'ACTIVE' WHERE status = 'PASSWORD_EXPIRED';
DELETE FROM notification WHERE type = 'PASSWORD_EXPIRY';
```

- 이 시점의 `PASSWORD_EXPIRED` 행은 전부 90일 전이의 결과다(관리자 지정 경로가 아직 없다).
- 다시 실행해도 결과가 같다. 기존 행에 영향을 주므로 업그레이드 경로 시험 `PasswordExpiryRetirementUpgradeTest`를 쓴다(`LOCKED`·`DISABLED`는 그대로, 다른 알림 종류는 그대로). `BaselineSchemaTest`의 버전 범위 설명을 갱신한다.
- **배포 전제**: 구버전을 모두 멈춘 뒤 신버전을 띄운다(V14는 신버전 기동 때 실행된다). 구버전이 남아 있으면 V14 뒤에 만료 전이와 `PASSWORD_EXPIRY` 알림을 다시 만들어 신버전의 알림 조회가 실패한다. 현재 compose는 단일 인스턴스이고 구·신 앱 혼합 운영 금지 규약과 같다.
- **이전 앱 롤백**: 스키마가 같아 기동한다. 90일 지난 계정은 다음 로그인 때 다시 만료되고 E3도 돌아온다. **롤백 중 생긴 `PASSWORD_EXPIRY` 알림 행이 있으면 재배포 전에 지워야 한다**(남으면 알림 조회가 enum 변환으로 실패) — 위 `DELETE`를 `docs/deployment.md`에 적는다.

### D9. 설정

`cms.password-breach-check.{enabled, range-url}`. `enabled` 기본 `true`, `range-url`은 시험용 로컬 서버를 가리키는 값이다(`verify-url`과 같은 역할).

- `build.gradle` 테스트 JVM에 `CMS_PASSWORD_BREACH_CHECK_ENABLED=false`(시험이 외부로 나가지 않게, `CMS_RATE_LIMIT_ENABLED` 선례). 켜야 하는 시험은 가짜 구현 또는 로컬 서버를 쓴다.
- `enabled=false`는 망이 격리된 설치와 시험용이다. prod에서 꺼져 있으면 기동 시 WARN. `docker-compose.prod.yml`·`_prod-env-guard.sh`·`.env.example`에 함께 등록한다.

### D10. 설계 제약

- 스키마 DDL·`SecurityConfig`·URL 인가 규칙·의존성 변경 없음.
- **로그인 정책 변경**: 시간 기반 만료 폐지, 관리자의 비밀번호 변경 강제 추가. **외부 서비스 추가**: HIBP. 둘 다 승인 대상이다.
- 프라이버시: 나가는 값은 SHA-1 앞 5자와 서버 IP다. 방문자 IP는 나가지 않는다.

### D11. 범위 밖

재사용 금지·이력 테이블, 로그인 시점 검사, 유사도 검사, 강도 측정기, MFA, Argon2 전환, 최대 64자 허용(BCrypt 72바이트 제약), 일괄 강제 변경.

## 작업 단계 — PR 두 개

**순서 이유**: 만료를 없애기 전에 대체 통제를 먼저 넣는다. 각 PR은 혼자 배포해도 안전하다.

### PR 1 — 유출 비밀번호 차단

1. `BreachedPasswordChecker`·`HibpBreachedPasswordChecker`·설정, 로컬 서버 시험(유출·미유출·패딩 0건·200 아님·빈 응답·정상 줄과 손상 줄 혼합·횟수 오버플로·헤더만 보내고 멈춤·UA와 패딩 헤더·접두부 5자만 전송).
2. `PasswordRejectedException`·전역 핸들러.
3. 네 지점 적용(D4)과 현재 값 비교(D5). 단위 시험의 생성자·`@InjectMocks`와, 인코더 목 스텁(현재 값 비교로 `matches` 호출이 늘어남) 수정.
4. 재설정 화면 분기, 설정 화면 안내 문구, 회원 생성 화면의 오류 표시 확인.
5. 통합 시험: 거부 시 DB 불변·토큰 생존·재시도 성공, 현재 값과 같으면 거부(HIBP 장애 중에도), 판정 불가 통과, **외부 호출 중 커넥션·잠금 미보유**, 없는 토큰이면 검사 미호출, 만료·자격 상실·검사 중 소비된 토큰은 유출 비밀번호여도 기존 400, 현재 비밀번호 오류는 기존 응답, 감사 FAIL 행, `CompromisedPasswordChecker` 빈 부재 컨벤션 시험, 부트스트랩은 유출 판정에도 기동·생성되고 WARN에 비밀번호가 없음.
6. 배선·문서(`docs/deployment.md`, 멤버 `CLAUDE.md`).

### PR 2 — 시간 기반 만료 폐지 + 변경 강제

1. V14·업그레이드 시험.
2. 만료 코드 제거(D7), 시험 정리. 91일 지난 계정이 로그인되는 시험으로 바꾼다.
3. `PASSWORD_EXPIRED` 관리자 지정(D6): 요청 검증·화면·라벨·`idempotentRelock`, 시험(세션 만료·동일값 재저장의 재폐기·로그인 거부·재설정 복귀·같은 비밀번호 복귀 불가·최후 ADMIN 가드·MANAGER 403).
4. 문서: README, 루트·`member`·`notification` `CLAUDE.md`, `docs/migration-guide.md`, `docs/deployment.md`, 로드맵은 `/updateRoadmap`.

## 검증

- 반복 중: `com.cms.admin.member.*`·`com.cms.config.auth.*`·`com.cms.admin.notification.*`·컨벤션 시험. `common/**`·마이그레이션을 건드리므로 **각 PR에서 전체 `./gradlew test`를 돌린다.**
- 변이 실험: 검사를 트랜잭션 안으로 옮기면 실패, 패딩 0건을 유출로 보면 실패, 재설정 화면 분기를 지우면 실패.
- 실기(PR별): bootRun + Playwright. 실제 HIBP로 잘 알려진 유출 비밀번호 거부·무작위 15자 통과, 변경·재설정·생성 왕복, 망 차단을 흉내 낸 `range-url`로 fail-open과 WARN, 91일 계정 로그인, 관리자 변경 강제 → 로그인 거부 → 재설정 → 복귀.

## 리스크

| 리스크 | 대응 |
|---|---|
| 운영망 차단으로 검사가 조용히 꺼짐 | WARN 로그, 배포 문서 확인 절차 |
| HIBP 지연으로 변경 요청이 최대 3초 느려짐 | 벽시계 상한, 트랜잭션 밖 |
| 기존 비밀번호는 다시 검사되지 않음 | 실배포 전, 수용. D6로 개별 대응 |
| 강제 변경 대상자가 사유를 모름 | 운영 절차로 안내 |
| 만료 폐지로 오래된 비밀번호가 무기한 유효 | 표준의 권고 방향으로 수용. 규정상 주기 변경이 필요해지면 재평가 |
| 트랜잭션 경계 이동으로 감사·이벤트 순서 변화 | `AdminActionLogCommitOrderIntegrationTest`·세션 폐기 시험으로 확인 |

## 구현·검증 결과 — PR 1(유출 비밀번호 차단)

### 핵심 확정 사항

계획 v3 그대로다. 사용자 승인(2026-11-05): HIBP 외부 호출, 관리자 변경 강제(PR 2), 부트스트랩은 유출이어도 WARN 후 기동.

**계획과 달라진 점**: `enabled=false` 기동 WARN을 prod 한정이 아니라 프로파일과 무관하게 남긴다(프로파일 분기를 두지 않으려는 단순화).

### 구현 파일

- 새 파일: `admin/member/password/`(`BreachedPasswordChecker`·`HibpBreachedPasswordChecker`·`PasswordBreachCheckProperties`·`PasswordBreachCheckConfig`), `common/exception/PasswordRejectedException`.
- 수정: `AdminMemberService`·`PasswordResetService`(`@Transactional` → 내부 `TransactionTemplate`, 판정과 거부 분리, 현재 값 비교), `AdminBootstrapLoader`, `GlobalApiExceptionHandler`, 컨트롤러 Swagger 설명, `password-reset-confirm.html`, `admin-settings.html`.
- 배선: `application.yml`, `build.gradle`, `docker-compose.prod.yml`, `scripts/_prod-env-guard.sh`, `scripts/ci/_prod-ci-common.sh`, `.env.example`.
- 문서: `docs/deployment.md`, `admin/member/CLAUDE.md`, `api-conventions` 스킬.
- 시험: `HibpBreachedPasswordCheckerTest` 13건, `PasswordBreachRejectionIntegrationTest` 16건, `PasswordResetRejectionBrowserTest` 2건, `AdminMemberServiceTest`·`AdminBootstrapLoaderTest` 추가.

### 검증 결과

- **전체 `./gradlew cleanTest test`**: 3,049건, 실패 0, 건너뜀 12(이번 변경과의 관련은 확인하지 않음).
- **변이 실험 4건, 모두 의도한 시험에서 실패 후 원복**: 패딩 0건을 유출로 판정(1건 실패), 손상 응답 판정 불가 처리 제거(3건), 재설정 화면 분기 제거(브라우저 1건), 재설정을 `@Transactional`로 되돌림(트랜잭션 밖 호출 시험 실패).
- **실기(bootRun 8081 + dev DB + 실제 HIBP, 2026-11-05)**:
  - 유출 값 `123456789012345`: 계정 생성·변경 모두 400 `PASSWORD_REJECTED`(응답 0.25~0.59초). 통과 값으로 생성 201.
  - 현재 비밀번호 틀림 + 유출 값은 기존 `INVALID_REQUEST`. 같은 값은 거부. 정상 변경 뒤 옛 세션 401, 옛 비밀번호 로그인 실패, 새 비밀번호 로그인 성공.
  - MANAGER의 계정 생성은 403. 감사 FAIL 행은 상수 메시지뿐이다.
  - 재설정 화면(Playwright): 유출 → 사유 표시·폼 유지, 같은 값 → 사유 표시, 다른 값 → 성공, 토큰 소비. 스크린샷 `docs/screenshots/auth/reset-breached.png`(git 제외).
  - fail-open: 응답 없는 주소로 설정하면 변경 200(2.39초, 연결 상한 2초), WARN 1건, 로그에 비밀번호 없음.
  - 시험 계정과 그 감사·방문 행은 삭제했다.

### 이슈

- 첫 시험 실행의 `initializationError` 20건은 Docker Desktop이 꺼져 있었던 환경 문제다(켠 뒤 통과).
- 실기 스크립트의 `JSON_PARSE_ERROR`는 Windows 셸이 한글 JSON을 CP949로 보낸 입력 문제였다(영문으로 바꾸니 정상, 코드 무관).
- 판정 불가 WARN의 예외 클래스명은 연결 실패에도 `ExecutionException`으로 찍혀 원인을 구분하지 못한다(수용, Turnstile과 같은 방식).

### 확인하지 못한 것

- prod 컨테이너 경로(`.env.prod`·compose)로의 실행과 실제 운영망의 HIBP 접근. CI `prod-smoke`는 검사를 끈 상태로 돈다.
- `HttpClient` 호출의 실제 HIBP 패딩 응답은 위 실기에서 정상 판정으로만 확인했고, 응답 줄 수를 따로 재지 않았다.

### 후속

PR 2(시간 기반 만료 폐지 + 관리자 변경 강제)를 이 PR 머지 뒤에 착수한다. 로드맵 반영은 `/updateRoadmap`.

## 구현·검증 결과 — PR 2(시간 기반 만료 폐지 + 변경 강제)

### 핵심 확정 사항

계획 v3 그대로다. `PASSWORD_EXPIRED` 코드 값은 유지하고 화면 라벨만 "비밀번호 변경 필요"로 바꿨다. `dedupe_key` 컬럼·유니크는 DDL을 만들지 않으려고 남겼다(지금은 쓰는 곳 없음).

### 구현 파일

- 제거: `PasswordExpiryService`와 그 시험 2개, `MemberRepository.expirePasswordIfOutdated`, E3(`recordPasswordExpiryIfNear`·`insertPasswordExpiryIfCurrent`·`passwordExpiring`·`NotificationType.PASSWORD_EXPIRY`), `MemberSnapshot.passwordChangedAt`, `loadUserByUsername`의 `noRollbackFor`.
- 수정: `CustomUserDetailsService`, `VisitLoggingAuthenticationSuccessHandler`(`verifyFreshMemberState`는 `boolean`), `LoginFailureService`, `AdminMemberUpdateRequest`, `AdminMemberService`(`idempotentRelock`), `NotificationMessages`, 화면 3개(`admin-manage`·`admin-my-info`·`permission/manage`).
- 새 파일: `V14__retire_password_expiry.sql`, `PasswordExpiryRetirementUpgradeTest`, `ForcedPasswordChangeIntegrationTest`.
- 문서: `docs/deployment.md`, `docs/migration-guide.md`, README, 루트·`member`·`notification` `CLAUDE.md`, `.env.example`.

### 검증 결과

- **전체 `./gradlew cleanTest test`**: 3,040건, 실패 0, 건너뜀 12(이번 변경과의 관련은 확인하지 않음). 시험 수는 만료 전용 시험을 지워 PR 1 때보다 줄었다.
- **변이 실험 3건, 모두 시험 실패 후 원복**: `idempotentRelock`의 `PASSWORD_EXPIRED` 제거, 요청 검증의 지정 허용 제거, V14의 알림 삭제 제거.
- **실기(bootRun 8081 + dev DB + 실제 HIBP, 2026-11-05)**:
  - V14 업그레이드: V13 상태의 dev DB에 `PASSWORD_EXPIRED` 계정·E3 알림·E1 알림을 넣고 기동 → V14 적용, 계정 `ACTIVE`, E3만 삭제, 알림 목록 조회 200.
  - 비밀번호 변경일이 10개월 전인 계정 로그인 성공, 비밀번호 알림 없음.
  - 관리 화면(Playwright): 수정 폼에 "비밀번호 변경 필요" 선택지 → 저장 → 목록·상세 라벨 표시, 감사 `ADMIN_UPDATE` SUCCESS, E1 문구 "관리자가 내 계정 상태를 '비밀번호 변경 필요'로 변경했습니다."
  - 살아 있던 대상 세션 401, 올바른 비밀번호 로그인 거부(`failed_login_count` 0).
  - 재설정 화면: 유출 값 거부 → 다른 값 성공 → `ACTIVE`·토큰 소비, 옛 비밀번호 실패·새 비밀번호 성공.
  - 시험 계정과 그 감사·방문 행은 삭제했다.
- **실기로 확인하지 않은 것(시험으로만 확인)**: 강제 변경을 같은 비밀번호로 끝내는 거부(시험 계정 비밀번호가 15자 미만이라 형식 검증에 먼저 걸림 — `ForcedPasswordChangeIntegrationTest`), 마지막 활성 ADMIN 409(`AdminMemberServiceTest`), 동일값 재저장의 재폐기(`AdminMemberServiceTest`), MANAGER 403·본인 지정 거부(`ForcedPasswordChangeIntegrationTest`).

### 이슈

- 문서 갱신 스크립트 실수로 `docs/migration-guide.md`의 V13 행이 자리표시 문자열로 바뀌었다. 커밋 전에 발견해 `git restore`로 복구했고 다른 파일에 번지지 않았음을 확인했다.
- dev DB가 V14까지 올라갔다. V14가 없는 옛 브랜치로 돌아가 띄우면 Flyway 검증이 실패할 수 있다(이 PR이 머지되면 해당 없음).

### 후속

로드맵 반영은 머지 후 `/updateRoadmap`. "비밀번호 이력 재사용 금지" 탈락 후보는 이 작업으로 대체된 것으로 기록한다. 실제 운영 전 prod 컨테이너 경로의 HIBP 접근 확인은 PR 1의 후속으로 남아 있다.
