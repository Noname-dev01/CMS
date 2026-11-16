# PLAN — 2단계 인증 복구 코드 재발급 (이슈 #257)

- 상태: v4 — 계획 리뷰 4라운드 ship (2026-11-16), **사용자 승인 완료 (2026-11-16)**, 구현·검증 완료(PR 전)
- 위험 변경: **2단계 인증 변경 경로(회원 행 잠금·재인증 실패 집계·트랜잭션 경계)**, **외부 계약(새 API 1개)**
- 스키마 변경: 없음 / 인가 정책 변경: 없음 / 새 의존성: 없음
- 이슈: #257 (출처 `PLAN-totp-two-factor.md` §7 범위 밖)

## 0. 사용자 결정 (2026-11-16, 정찰 직후)

| 쟁점 | 결정 | 기각한 선택지 |
|---|---|---|
| 재인증 코드 | **현재 비밀번호 + 인증 앱 6자리 코드만**. 복구 코드는 받지 않는다 | 인증 앱 코드 또는 복구 코드(본인 해제와 같은 규칙) — 인증기를 잃은 채 코드만 재발급하면 쓸 수 없는 인증기가 등록된 채 남는다. 인증기를 잃었으면 복구 코드로 해제 후 재등록하는 기존 길이 맞다 |

## 1. 정찰 사실 (출처)

- **변경 경로의 틀**: `MemberTotpService.disable`(`MemberTotpService.java:141-171`) — `TransactionTemplate` 안에서 `findByIdForUpdate` → `stillAuthorized`(ACTIVE·관리자 역할·principal과 같은 역할, 아니면 409 판정 없음) → 비밀번호가 틀리면 코드 판정·소비 안 함 → 오답이면 `totp.recordFailureAndCheckThreshold()`, 5회면 `LoginFailureService.lockForSecondFactorFailures` → 커밋 **뒤** 400. 재발급은 이 틀을 그대로 따른다.
- **코드 판정**: `TotpCredentialChecker.checkAndConsume`(MANDATORY 전파)은 6자리면 TOTP, 24자 Base32면 복구 코드로 판정한다. 결과 `WRONG`(세기)·`REUSED`(세지 않기)·`UNREADABLE`(세지 않기, 키 문제)·`TOTP_MATCHED`·`RECOVERY_CODE_MATCHED`.
- **로그인 2단계도 같은 회원 행을 잠근다**(`TotpLoginService.verify` — `@Transactional` + `findByIdForUpdate`) → 재발급과 복구 코드 로그인은 직렬화된다.
- **`deleteAllByMemberId`는 `@Modifying(clearAutomatically = true, flushAutomatically = true)`**(`MemberTotpRecoveryCodeRepository.java:18-20`) — 실행 전에 대기 중인 변경을 flush하고 실행 뒤 영속성 컨텍스트를 비운다. 그래서 `totp`에 대한 변경(쓴 스텝·실패 횟수 0)은 **삭제 호출 전에** 해야 반영된다. 삭제 뒤의 변경은 분리된 엔티티라 조용히 사라진다. (출처: 코드 + Spring Data JPA **4.0.7**(Boot 4.0.8 BOM의 `spring-data-bom` 2025.1.7) `@Modifying`의 `flushAutomatically`·`clearAutomatically` API 문서 https://docs.spring.io/spring-data/jpa/docs/4.0.7/api/org/springframework/data/jpa/repository/Modifying.html — 이 프로젝트의 `disable`·`resetByAdmin`도 같은 순서를 쓴다. 의미는 FM1·FM6 시험이 실제 DB로 고정한다)
- **인가**: `/admin/api/members/me/**`는 `AdminFeature.MY_INFO`(ALWAYS) 게이트(`AdminFeature.java:35-38`)로 ADMIN·MANAGER에게 열려 있다 → 새 경로 `/admin/api/members/me/totp/recovery-codes`는 URL 규칙 추가 없이 덮인다. 핸들러에는 기존 본인 경로와 같이 `@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")`를 단다(`AdminEndpointAuthorizationConventionTest`가 선언을 검사).
- **감사**: `@AdminActionLogged`를 쓰지 않는다(`member/CLAUDE.md` — 실패도 정상 커밋이라 aspect가 성공으로 기록). 실제로 바뀐 경우에만 `MemberTotpAuditEvent` → AFTER_COMMIT `MemberTotpAuditListener`. 새 유형은 `AdminActionTypes.ALL`과 `templates/admin/log/manage.html`의 `ACTION_TYPE_LABELS`에 함께 넣어야 한다(`AdminActionTypeSyncTest`·`AdminActionTypeLabelSyncTest`). `action_type`은 `varchar(100)`이라 스키마 변경이 없다.
- **화면**: `admin-settings.html`의 `totpOn` 영역에 "복구 코드를 새로 받으려면 끄고 다시 등록합니다" 문구가 있다. 서버 값은 `textContent`로만 넣는 규약(스크립트 주석).

## 2. 핵심 설계 결정

### 결정 1 — API: `POST /admin/api/members/me/totp/recovery-codes` → 200 + 복구 코드 10개 (v2: 201 → 200)

| 선택지 | 판단 |
|---|---|
| **A. `POST …/me/totp/recovery-codes` 200** (채택) | 서버가 무작위로 새 묶음을 만든다 — 같은 요청을 반복하면 결과가 달라져 멱등이 아니므로 POST. 응답 본문은 기존 `TotpRecoveryCodesResponse` 재사용. **201이 아니라 200**: 저장소 규칙(`AGENTS.md` — 201은 `Location`과 함께)상 201이면 새 자원을 가리키는 URI가 있어야 하는데, 복구 코드 원문은 다시 조회할 수 없고 묶음을 식별하는 자원도 없다. 조회 가능한 것처럼 보이는 URI를 꾸며 넣지 않고, "처리 결과를 본문에 담은 POST"로 200을 쓴다(RFC 9110 §15.3.1 — POST의 200은 처리 결과 표현) |
| B. `PUT …/me/totp/recovery-codes` | PUT은 멱등·요청 본문이 표현이어야 하는데 본문은 재인증 자격정보뿐이다 |
| C. `PUT …/me/totp`에 동작 플래그 | 등록 확정과 의미가 섞인다 |

- 요청 DTO `TotpRecoveryCodesReissueRequest(code, currentPassword)` — `TotpDisableRequest`와 같은 제약(`@NotBlank`·`@Size`), `toString()`은 값을 숨긴다(비밀번호 로그 유출 방지). 코드 형식(6자리)은 Bean Validation이 아니라 서비스가 판정한다 — 형식 오류도 오답으로 **세야** 하기 때문(아래 결정 2).
- 상태 코드: 200 발급 / 400 재인증 실패(TOTP 실패 횟수 반영)·`VALIDATION_ERROR` / 409 미사용·계정 상태 변경.
- 응답 캐시: 핸들러에서 헤더를 따로 넣지 않는다. `SecurityConfig`는 `headers()`를 바꾸지 않아(grep 결과 `headers(`·`cacheControl` 없음) Spring Security 기본 보안 헤더(`Cache-Control: no-cache, no-store, max-age=0, must-revalidate`)가 붙는다 — 출처: Spring Security **7.0.7**(Boot 4.0.8 BOM `spring-boot-dependencies-4.0.8.pom`의 `spring-security.version`) 레퍼런스 https://docs.spring.io/spring-security/reference/7.0/servlet/exploits/headers.html#servlet-headers-cache-control (기본 활성). 등록 확정 `PUT …/me/totp`도 같은 처리다. 이 전제는 API 통합 시험에서 응답 헤더 `no-store` 포함을 단언해 고정한다(전제가 틀리면 시험이 실패).

### 결정 2 — 서비스 `MemberTotpService.reissueRecoveryCodes` (위험 변경: 잠금·트랜잭션·재인증 집계)

`disable`과 같은 틀, 순서 고정:

1. 트랜잭션 밖에서 새 코드 10개 생성(`RecoveryCodes.generate`) — 잠금 보유 시간 최소화(등록 확정과 같다).
2. `TransactionTemplate` 안: `findByIdForUpdate` → `stillAuthorized` 실패면 `STATE_CHANGED`(409, 판정·카운트 없음).
3. `member_totp` 행 없음 → `NOT_ENABLED`(409).
4. 입력 코드를 먼저 `trim()`한다(v2 — checker와 같은 정규화, `TotpCredentialChecker.java:47`). 비밀번호 불일치 → `WRONG`(코드는 판정·소비하지 않음). 비밀번호 일치 + 정규화한 코드가 6자리 ASCII 숫자가 아님(복구 코드 포함) → `WRONG`. 그 밖에는 정규화한 코드로 `checker.checkAndConsume`.
5. `UNREADABLE` → 400(일반 문구), 세지 않음. `REUSED` → 400, 세지 않음, **별도 문구** "이미 사용한 코드입니다. 인증 앱에 새 코드가 표시되면 다시 입력해주세요."(v2 — 새 Verdict `REUSED_CODE`. 로그인 직후 앱에 같은 코드가 떠 있는 정상 상황이고, 재발급에는 복구 코드라는 대체 입력이 없다. 비밀번호가 맞은 뒤에만 나오므로 비밀번호 추측 신호가 아니다. 본인 해제의 문구는 이 작업에서 바꾸지 않는다). `WRONG` → `recordFailureAndCheckThreshold()`, 5회면 `lockForSecondFactorFailures`(잠금·세션 만료·감사) → 400. `RECOVERY_CODE_MATCHED`는 4에서 막혀 도달하지 않는다.
6. 성공(`TOTP_MATCHED`): `totp.resetFailures()` → `deleteAllByMemberId`(이 호출이 앞의 변경을 flush) → 새 해시 10행 저장 → `MemberTotpAuditEvent(TOTP_RECOVERY_CODES_REISSUE, …, "POST")` 발행.
7. 커밋 뒤 Verdict가 OK가 아니면 예외(`throwUnlessOk` 재사용) — 실패 카운터가 커밋된 다음 400.
   - 전제(v2): 이 메서드는 **바깥 트랜잭션 없이** 호출된다(컨트롤러 직접 호출, 클래스에 `@Transactional` 없음 — `disable`과 같다). 바깥 트랜잭션이 있으면 `TransactionTemplate`(기본 전파 `PROPAGATION_REQUIRED` — Spring Framework **7.0.9**, `TransactionDefinition` API https://docs.spring.io/spring-framework/docs/7.0.9/javadoc-api/org/springframework/transaction/TransactionDefinition.html )이 그것에 참여해 반환 시점에 커밋되지 않고, 뒤의 400 예외가 실패 카운터까지 롤백한다. 서비스 통합 시험은 바깥 트랜잭션 없이 호출하고 예외 뒤 DB를 다시 읽어 이 전제를 고정한다.
- **동시·연속 재발급 계약(v2)**: TOTP는 ±1 스텝을 허용하므로(`TotpCodeVerifier.ALLOWED_SKEW_STEPS = 1`) 이전 스텝 코드와 현재 스텝 코드로 보낸 두 요청은 **둘 다 성공할 수 있고, 마지막으로 커밋된 묶음만 유효**하다(앞 응답의 코드는 즉시 무효). 같은 스텝 코드 두 건이면 뒤의 것이 `REUSED`. 화면은 요청 중 버튼을 막아 한 탭의 중복 제출을 줄이지만 다른 탭까지 막지는 않는다 — 이 계약을 시험으로 고정한다(FM6).
- **커밋 후 응답 유실(v2)**: 커밋 뒤 연결이 끊기면 옛 코드는 무효이고 새 코드는 사용자에게 도착하지 않았다. 서버가 원문을 다시 줄 방법은 없다(해시만 저장 — 의도). 복구 경로는 **인증 앱의 다음 코드로 다시 재발급**(30초 이내 새 코드)이고, 같은 코드로의 재시도는 `REUSED` 문구가 이 경로를 안내한다. 화면은 응답을 받지 못한 경우(네트워크 오류·본문 해석 실패) 일반 오류 대신 "재발급이 이미 처리됐을 수 있습니다. 인증 앱에 새 코드가 표시되면 다시 받아 주세요."를 보여 준다. 자동 재시도는 하지 않는다(같은 코드라 `REUSED`가 될 뿐). 응답 유실 자체의 자동 시험은 두지 않는다 — 서버 쪽 상태는 "성공 커밋"과 구분되지 않고, 화면 문구는 7단계에서 네트워크 차단으로 확인한다.

- **세션 만료 없음**: 보안 수준이 바뀌지 않는다(같은 인증기·같은 비밀값). 등록 확정과 달리 본인 세션을 끊지 않는다.
- **세션 뮤텍스 불필요**: 세션 상태를 읽거나 쓰지 않는다. 잠금은 회원 행 하나 → 로그인·해제와 같은 잠금이라 순서 문제 없음.
- **`failed_login_count`는 건드리지 않는다**: 본인 해제와 같다(로그인 성공만 두 카운터를 0으로).
- 왜 `checker`를 그대로 쓰고 앞에서 형식을 거르나: 판정·소비 로직(쓴 스텝 기록·복호 실패 처리)을 두 벌로 만들지 않기 위해. 거르는 기준은 `TotpCodeVerifier.isSixAsciiDigits`(checker와 같은 함수)라 "checker가 TOTP로 판정할 입력"과 정확히 같다.

### 결정 3 — 감사 유형 `TOTP_RECOVERY_CODES_REISSUE`

- 라벨 "2단계 인증 복구 코드 재발급". targetId = 본인 회원 번호(다른 TOTP 유형과 같다). 실제로 교체된 경우에만 기록 → 실패·409는 감사 없음.

### 결정 4 — 화면 (`admin-settings.html`의 `totpOn`)

- "끄고 다시 등록합니다" 문구를 "복구 코드가 부족하면 아래에서 새로 받을 수 있습니다(기존 코드는 모두 무효)"로 바꾼다.
- `totpOn` 안에 재발급 폼(인증 앱 코드 `inputmode="numeric"`·`maxlength="6"`·현재 비밀번호·버튼 "복구 코드 새로 받기")을 해제 폼 위에 둔다. 제출 전 클라이언트 검사: 6자리 숫자 아니면 서버에 보내지 않고 "인증 앱의 6자리 코드를 입력해주세요" — 복구 코드를 넣어 실패 횟수를 헛되이 쓰는 일을 줄인다(서버 판정은 그대로 계약).
- `window.confirm("기존 복구 코드는 모두 쓸 수 없게 됩니다. 새로 받을까요?")` — 해제와 같은 확인 방식.
- 성공 시 **카드의 독립 화면 상태 `totpReissued`**(`sections` 목록에 추가 — `show()`가 다른 상태를 모두 숨긴다, v2)로 전환한다: 코드를 `textContent`로 표시 + 복사 버튼 + "보관했습니다" 버튼. 이 상태에서는 재발급·해제 폼이 보이지 않는다(보관 전에 다시 발급하거나 해제해 옛 목록이 남는 일이 없다). "보관했습니다"를 누르면 목록 텍스트를 지우고 `loadStatus()`로 `totpOn`(남은 10개)으로 돌아간다. 상태 조회가 실패해도 목록은 이미 지운 뒤라 남지 않는다 — 버튼을 누른 것은 보관을 마쳤다는 뜻이므로 지우는 것이 맞다. 등록 완료 화면(`totpRecovery`)은 "다시 로그인" 버튼이 있어 재사용하지 않는다(재발급은 세션을 끊지 않음).
- **재발급·해제는 카드 공용 진행 플래그 하나로 상호 배제한다**(v3): 어느 한 요청이 진행 중이면 두 폼의 버튼을 모두 막고, 제출 핸들러도 플래그를 먼저 검사해 무시한다. 재발급 응답이 늦는 사이 다음 코드로 해제가 성공하고, 뒤늦게 도착한 재발급 응답이 이미 삭제된 코드를 보여 주는 일을 막는다(서버 행 잠금은 DB 순서만 정하고 응답 도착 순서는 정하지 못한다). 다른 탭은 막지 못한다 — 서버 계약(마지막 커밋이 유효)으로 남는다.
- **오류 분기**(v3, v4 개정): **4xx는 확정 실패**다(서버가 처리하지 않았다고 답함) → 서버 JSON `message`를 보이고(본문이 없으면 상태별 기본 문구: 401 "로그인이 만료됐습니다. 다시 로그인해 주세요.", 403 "페이지를 새로고침한 뒤 다시 시도해 주세요.", 그 밖 일반 문구) 비밀번호 칸을 비운다. 400이면 `totpOn`에 머문다. **409면 상태를 다시 읽는다**(v4 — 다른 탭·ADMIN이 먼저 해제했으면 `NOT_ENABLED` 뒤 미사용 화면으로 바뀌어야 하고, `STATE_CHANGED`면 서버 문구가 재로그인을 안내한다). **5xx(프록시 502/504 포함)·네트워크 예외·성공 본문 해석 실패만 "처리 여부 불명"** → "재발급이 이미 처리됐을 수 있습니다. 인증 앱에 새 코드가 표시되면 다시 받아 주세요."를 보이고 상태를 다시 읽는다(`fetch`는 HTTP 오류 상태에서 reject하지 않는다 — MDN `fetch()` https://developer.mozilla.org/en-US/docs/Web/API/Window/fetch ).

## 3. 작업 단계 (안쪽 → 바깥쪽)

1. `AdminActionTypes.TOTP_RECOVERY_CODES_REISSUE` + `ALL` + `log/manage.html` 라벨.
2. `MemberTotpService.reissueRecoveryCodes` (TDD — 서비스 통합 시험 먼저).
3. DTO `TotpRecoveryCodesReissueRequest` + 컨트롤러 핸들러 (TDD — API 통합 시험 먼저).
4. `admin-settings.html` 폼·스크립트(TDD 제외 — 7단계 실기 검증).
5. `member/CLAUDE.md` 2단계 인증 절 현행화("재발급은 없다" 문구 교체, API 목록 추가), `docs/deployment.md`에 재발급 언급이 필요하면 추가.

## 4. 성공 기준 (검증 가능)

- S1: 사용 중인 회원이 올바른 비밀번호 + 현재 TOTP 코드로 요청하면 200, 응답에 서로 다른 복구 코드 10개, DB `member_totp_recovery_code`에는 그 10개의 해시만 있고(옛 해시 0개, 사용 기록 있던 옛 행도 삭제), 상태 조회 `remainingRecoveryCodes = 10`.
- S2: 재발급 후 옛 복구 코드로는 로그인 2단계를 통과하지 못하고, 새 코드로는 통과한다.
- S3: 감사 `TOTP_RECOVERY_CODES_REISSUE` 1행(성공 시만). 실패·409는 0행.
- S4: 재인증 실패(비밀번호 오답·코드 오답·형식 오류·복구 코드 입력)는 400이고 `member_totp.failed_attempts` +1(커밋), 복구 코드 행은 그대로. 5회째에 계정 `LOCKED`·`failed_attempts` 0·등록과 옛 코드 유지.
- S5: 성공 시 `failed_attempts` 0, `last_used_step`이 쓴 스텝으로 갱신(같은 코드로 바로 로그인 2단계 → `REUSED`).
- S2b (v2): 로그인 직후 같은 코드로 재발급하면 400 + "이미 사용한 코드" 문구, 실패 횟수 변화 없음.
- S6: 미사용 회원 409, 미인증 401, 본문 빈 값 400 `VALIDATION_ERROR`, CSRF 없음 403.
- S7b (v3·v4): 재발급 응답을 지연시킨 동안 해제 버튼이 막혀 있다(playwright route 지연). 5xx 응답이면 "처리 여부 불명" 문구. 다른 탭에서 해제한 뒤 재발급 → 409 문구 + 미사용 화면 전환. 401 응답이면 재로그인 문구(route로 401 JSON 주입).
- S7: 화면에서 재발급 → 새 코드 표시 → 보관했습니다 → 남은 개수 10. 재발급 후에도 현재 세션 유지.

## 5. 실패 모드와 고정 시험 (v2: 리뷰 지적으로 7개 — 5개 상한을 넘긴 이유는 FM6·FM7이 리뷰에서 나온 별개 실패 모드라서. 화면 쪽 경합·오류 분기는 S7b로 7단계에서 확인)

| # | 실패 모드 | 고정 시험 |
|---|---|---|
| FM1 | 삭제(`clearAutomatically`) 뒤에 `resetFailures`·스텝 기록을 하면 조용히 반영되지 않는다 | 서비스 통합: `failed_attempts`=3인 상태에서 성공 → DB 값 0, `last_used_step` 갱신 확인(S5) |
| FM2 | 복구 코드를 재인증으로 받아 버리면 결정 위반 + 그 복구 코드가 소비된 채 400이 아니라 성공 | 서비스 통합: 유효한 복구 코드 입력 → 400·`failed_attempts` +1·그 코드 `used_at` null 유지·코드 10행 그대로 |
| FM3 | 비밀번호 오답인데 TOTP 코드를 판정·소비하면 공격자가 코드 맞히기 신호를 얻고 사용자의 정상 코드가 소진된다 | 서비스 통합: 비밀번호 오답 + 올바른 코드 → 400·`last_used_step` 변화 없음 |
| FM4 | 잠금 대기 중 계정이 잠기면 판정·변경하면 안 된다(409, 카운트 없음) | 서비스 통합: 행을 잠근 채 상태를 `LOCKED`로 바꾸고 커밋하는 동안 재발급 요청이 기다리게 해 409·코드 그대로·카운트 그대로(기존 FM2(v6) 시험 패턴 `LockWaitObserver` 재사용) |
| FM5 | 실패 시 400이 트랜잭션 안에서 던져지면 카운터가 롤백된다 | 서비스 통합(바깥 트랜잭션 없이 호출): 오답 후 DB `failed_attempts` 값 확인(S4), 5회 → `LOCKED` |
| FM6 (v2) | 옛 코드 삭제 뒤 새 코드 저장이 도중에 실패하면 코드가 없거나 일부만 남는다 | 서비스 통합: 복구 코드 리포지토리 `save`를 5번째 호출에서 실패시키고(`@MockitoSpyBean`) → 예외 전파, 옛 코드 10행·사용 기록·`last_used_step`·`failed_attempts` 원래대로, 재발급 감사 0행 |
| FM7 (v2) | ±1 스텝 허용으로 연속 두 요청이 모두 성공 — 앞 응답의 코드가 살아 있다고 착각 | 서비스 통합: 이전 스텝 코드로 재발급 → 현재 스텝 코드로 재발급 둘 다 성공, DB에는 두 번째 묶음 해시만, 첫 묶음 코드로 로그인 2단계 실패. 같은 코드 재사용은 400 `REUSED` 문구·카운트 없음 |

## 6. 리스크

- 동시 재발급 2건: 회원 행 잠금으로 직렬화된다. 같은 스텝이면 뒤가 `REUSED`, 다른 허용 스텝이면 둘 다 성공하고 마지막 묶음만 유효(결정 2 "동시·연속 재발급 계약", FM7).
- 커밋 후 응답 유실: 옛·새 코드 모두 손에 없다 → 다음 앱 코드로 다시 재발급(결정 2).
- 복구 코드 표시는 응답 1회뿐 — 사용자가 화면을 닫으면 다시 볼 수 없다(등록과 같은 계약). 화면 문구로 안내.
- 실수로 재발급하면 출력해 둔 옛 코드가 무효가 된다 — 확인 대화상자로 완화.

## 7. 범위 밖

- ADMIN이 다른 회원의 복구 코드를 재발급하는 기능(코드를 본인 외에게 보여 주면 안 된다).
- 남은 복구 코드가 적을 때의 알림.

## 개정 이력

- v1 (2026-11-16): 초안.
- v2 변경 (1라운드 codex 리뷰): ① 동시·연속 재발급 계약(±1 스텝이면 둘 다 성공·마지막만 유효) 명시 + FM7 ② 커밋 후 응답 유실의 복구 경로(다음 코드로 재발급)와 화면 문구 명시 ③ `REUSED`를 별도 Verdict·문구로 ④ 저장 도중 실패의 원자성 시험 FM6 ⑤ 결과 표시를 독립 화면 상태 `totpReissued`로(보관 전 폼 비노출) ⑥ 코드 `trim()` 정규화를 사전 검사와 checker에 같게 ⑦ 라이브러리 버전(Boot 4.0.8·Security 7.0.7)·문서 링크, `TransactionTemplate` 바깥 트랜잭션 없음 전제 ⑧ 201 → 200(`Location`으로 가리킬 자원이 없음). 부분 기각: ② 중 "응답 유실 자동 시험" — 서버 상태가 정상 성공과 구분되지 않아 시험할 대상이 없고, 화면 문구는 실기 검증으로 확인한다.
- v3 변경 (2라운드 codex 리뷰): ① 재발급·해제 공용 진행 플래그로 같은 탭 상호 배제(늦은 재발급 응답이 삭제된 코드를 보이는 경합) + S7b ② 400·409 외 응답(5xx 등)도 "처리 여부 불명" 안내 ③ Spring Data JPA 4.0.7 `@Modifying`·Spring Framework 7.0.9 `TransactionDefinition` 버전·링크 보강.
- v4 변경 (3라운드 codex 리뷰): ① 409 뒤 상태 재조회(다른 탭·ADMIN 해제 후 화면 불일치) ② "처리 여부 불명"은 5xx·네트워크·본문 해석 실패로 한정하고 4xx(401·403 포함)는 확정 실패로 서버 문구·상태별 기본 문구 표시. S7b에 두 경로 추가.

---

# 구현·검증 결과 (2026-11-16)

## Context

이슈 #257. 브랜치 `feat/totp-recovery-code-reissue`. 계획 v4(리뷰 4라운드 ship) 승인 후 구현.

## 핵심 확정 사항 (계획과 달라진 점 포함)

- 계획대로: `POST /admin/api/members/me/totp/recovery-codes` 200, 재인증은 비밀번호 + 인증 앱 6자리만, `REUSED_CODE` 별도 문구, 성공 순서 `resetFailures` → 삭제 → 저장 → 감사, 세션 유지, 화면 상태 `totpReissued`·공용 진행 플래그·4xx/5xx 분기.
- **계획 밖 보정(실기 검증 중 발견)**: 복구 코드로 로그인해 `?recoveryCodeUsed`로 들어온 화면에서 재발급하면, 상단 안내 "남은 복구 코드는 9개입니다"가 재발급 뒤에도(상태를 다시 읽을 때마다) 다시 떠 틀린 숫자를 보였다. 재발급 성공 시 그 안내를 숨기고 `history.replaceState`로 쿼리를 지운다. (원인 정정: 숫자가 틀렸던 곳은 새 코드 표시 화면 — 상태를 다시 읽지 않아 이전 숫자가 남았다. 상태를 다시 읽으면 숫자는 갱신되지만 "복구 코드로 로그인했습니다" 문맥이 남는다.)
- **계획 밖 보정 2(사용자 요청)**: "처리 여부 불명"(5xx·네트워크 오류·본문 해석 실패) 경로가 인증 코드와 현재 비밀번호를 입력 칸에 남겼다(4xx는 비밀번호를 비움). 이 경로에서도 재발급 폼 전체를 비운다 — 처리됐다면 같은 코드는 다시 쓸 수 없고, 비밀번호를 화면에 남기지 않는다.
- **계획 밖 보정 3(사용자 요청)**: 결정 4의 단일 문구("재발급이 이미 처리됐을 수 있습니다…")를 경우별 세 문구로 나눴다 — ① 네트워크 오류 "네트워크 연결이 끊겨 결과를 확인하지 못했습니다…" ② 5xx "서버 응답에 문제가 있어 결과를 확인하지 못했습니다…" ③ 200인데 본문을 읽지 못함 "재발급은 처리됐지만 새 복구 코드를 표시하지 못했습니다…"(200은 커밋 뒤에만 나가므로 단정). 세 문구 모두 **기존 복구 코드가 무효일 수 있음(③은 무효)**을 알리고 다음 앱 코드로 다시 받으라고 안내한다.
- **코드 리뷰 반영(적대적 리뷰 1건, 수용)**: 결과 불명 안내 뒤 상태 조회(`loadStatus`)까지 실패하면 그 오류가 같은 경고 칸을 덮어 기존 코드 무효 가능성 안내가 사라졌다. 조회 뒤 경고가 바뀌었으면 원래 안내를 다시 세우고 "(현재 상태를 불러오지 못했습니다.)"를 덧붙인다. 실기: POST 네트워크 오류+조회 네트워크 오류, POST 502+조회 503 모두 안내 유지·덧붙임, 조회 성공 시 덧붙임 없음.

## 구현 파일

- `admin/log/constant/AdminActionTypes.java` — `TOTP_RECOVERY_CODES_REISSUE` + `ALL`
- `templates/admin/log/manage.html` — 라벨 "2단계 인증 복구 코드 재발급"
- `admin/member/service/MemberTotpService.java` — `reissueRecoveryCodes`, Verdict `REUSED_CODE`·문구
- `admin/member/dto/request/TotpRecoveryCodesReissueRequest.java` — 요청 DTO(`toString` 값 숨김)
- `admin/member/controller/MemberTotpApiController.java` — 핸들러
- `templates/admin/member/admin-settings.html` — 재발급 폼·결과 화면·상호 배제·오류 분기
- 시험: `MemberTotpServiceIntegrationTest`(재발급 7건), `MemberTotpReissueRollbackIntegrationTest`(FM6, 신규), `MemberTotpApiIntegrationTest`(재발급 2건)
- `admin/member/CLAUDE.md` — 2단계 인증 절

## 검증 결과

- **TDD**: 서비스 시험 8건이 미구현 골격에서 모두 실패(`UnsupportedOperationException`) → 구현 후 통과. API 시험 2건이 404로 실패 → 핸들러 추가 후 통과.
- **변이 실험**(모두 원복): ① `resetFailures`를 삭제 뒤로 → `reissueRoundTrip`(FM1) 실패 ② 6자리 사전 검사 제거 → `reissueRejectsRecoveryCode`(FM2) 실패 ③ 새 코드 저장 예외를 삼키기 → FM6 실패("예외가 나야 함").
- **영향 범위 시험**: `com.cms.admin.member.*`·`com.cms.admin.log.*`·`AdminHeadTitleRenderingTest`·`*ConventionTest` 530건 중 529 통과, 1건은 아래 "이슈"의 기존 간헐 실패. (전체 시험은 PR 직전에 돌린다.)
- **실기 검증**(Playwright, dev DB, 앱 8081): 등록 → 복구 코드 로그인(남은 9) → 6자리 아닌 입력은 요청 없이 안내 → 등록 때 쓴 코드로 재발급 시 "이미 사용한 코드"(400, DB `failed_attempts` 0) → 다음 코드로 재발급 성공(DB 미사용 10행·해시 일치·감사 `TOTP_RECOVERY_CODES_REISSUE` POST 1행·`failed_attempts` 0) → "보관했습니다" 뒤 남은 10·세션 유지 → 응답 지연 중 재발급·해제 버튼 모두 비활성·해제 제출이 요청을 보내지 않음 → 502는 "처리 여부 불명" 안내 + 상태 재조회 → 401은 서버 문구/본문 없으면 재로그인 문구 → 실제 서버 비밀번호 오답 400·`failed_attempts` 1·스텝·코드 그대로 → 다른 탭에서 해제(새 복구 코드로 204 — 새 코드가 실제로 유효) 뒤 재발급 409 → 미사용 화면 전환 → 감사 로그 화면 라벨·필터 옵션. 보정 후 재확인: 재발급 직후·보관 뒤 옛 안내 미표시, 쿼리 제거. 콘솔 오류는 의도한 4xx/5xx 응답과 기존 favicon 404뿐. 추가 확인(사용자 요청): 서버는 커밋했는데 화면에 502가 온 경우 노란 안내·"남은 복구 코드" 줄·DB 미사용 수가 모두 10으로 일치, 서버 미처리 502도 두 숫자 일치(이전 단계에 이어 돌려 시작값이 10이었다 — 9 유지는 따로 보지 않음). 보정 2 뒤 502·네트워크 중단(`connectionreset`) 모두 코드·비밀번호 칸이 비고, 4xx는 기존대로 비밀번호만 빈다. 보정 3 뒤 ①(`route.abort`)·②(502)·③(실제 서버 200 커밋 후 깨진 본문) 각각 해당 문구와 빈 입력 칸을 확인했고, ③ 뒤 옛 복구 코드로 해제하면 400 — '기존 코드 무효' 안내가 사실과 맞다. 정리는 `docs/deployment.md`의 복구 SQL로 했다. 검증 데이터는 해제로 원상 복구(admin 2단계 미사용·코드 0행).
- 스크린샷: `docs/screenshots/member/totp-08-reissue-form.png`·`totp-09-reissue-codes.png`·`totp-10-reissue-done.png`·`totp-11-reissue-unknown-committed.png`·`totp-12-reissue-unknown-cleared.png`

## 이슈

- **FM6 변이가 처음엔 통과했다** — 변이 스크립트가 같은 문장(복구 코드 저장 반복문)이 `confirmEnrollment`에도 있다는 것을 모르고 첫 번째(등록 확정 쪽)를 바꿨다. 스택의 줄 번호로 원인을 찾았고, 재발급 쪽에 다시 걸어 실패를 확인했다.
- **Spring Data 리포지토리 스파이에서 `callRealMethod()` 불가** — JDK 프록시라 "Cannot call abstract real method"가 난다. 스파이의 기본 응답(`mockingDetails(...).getMockCreationSettings().getDefaultAnswer()`)으로 위임했다.
- **기존 간헐 실패(이번 변경과 무관)**: `MemberTotpApiIntegrationTest`의 S6(등록 왕복)이 등록 QR을 ZXing `QRCodeReader`로 해석하다 `NotFoundException`으로 실패한다 — 같은 클래스 5회 반복 중 1회, 영향 범위 실행에서 2회 관측. 이 diff는 QR·등록 대기 코드를 건드리지 않는다. 원인(여백 `MARGIN 2`인지 시험 쪽 해석 힌트 부재인지)은 미확인.

## 후속

- 위 S6 간헐 실패 원인 규명 — 이슈 #265로 등록(2026-11-16, 사용자 승인, 라벨 `test`).

