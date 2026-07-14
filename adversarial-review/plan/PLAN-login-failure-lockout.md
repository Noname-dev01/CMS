# PLAN-login-failure-lockout — 로그인 연속 실패 시 LOCKED 자동 전이

> **✅ 구현 완료 (2026-07-14, 브랜치 `feat/login-failure-lockout`)** — 아래 "구현·검증 결과" 참조.

## 구현·검증 결과 (2026-07-14)

### Context
- 계획 v23(적대적 리뷰 21라운드, 총 54건 지적 처리 후 ship 판정)대로 구현. 사용자 결정 3건 반영: 30분 자동 해제 / 재설정 경로에도 lazy 해제 / 내 비밀번호 변경 시 전 세션 폐기.

### 핵심 확정 사항 (계획 대비 변경점)
- **리스너 순서 보장 메커니즘**: `@Order`는 서로 다른 이벤트 타입 간 실행 순서를 결정하지 못하므로(AFTER_COMMIT은 발행 순서대로 재생), `LoginFailureService`가 `AdminSessionRevokeEvent`를 **먼저 발행**하는 것으로 보장하고 `@Order(10/20)`는 계약 명시용으로 병기. (계획 v19의 의도 동일, 수단 보강)
- **`CustomUserDetails` 수정 불필요**: `CredentialsContainer` 미구현이라 인증 당시 해시가 소거되지 않음을 확인 — 계획 v20의 "필요 시" 항목 해소.
- 나머지는 계획 그대로.

### 구현 파일
- 신규: `V4__add_member_login_lockout.sql`, `LoginFailureService`, `LockingAuthenticationFailureHandler`, `AdminAccountAutoLockEvent`, `AdminAccountAutoLockListener`
- 수정: `Member`(@DynamicUpdate·필드 2개·도메인 메서드 3개), `MemberRepository`(쿼리 4개), `SecurityConfig`, `VisitLoggingAuthenticationSuccessHandler`(fail-closed 재확인), `CustomUserDetailsService`(쓰기 트랜잭션 + lazy 해제), `AdminMemberService`(비ACTIVE→ACTIVE 리셋·me/password 행 잠금+세션 폐기), `PasswordResetService`(재설정 경로 lazy 해제), `AdminSessionRevokeListener`(@Order), `AdminActionTypes`+`log/manage.html`(라벨)
- 테스트 신규 5클래스(`MemberLockoutTest` 9, `LockingAuthenticationFailureHandlerTest` 6, `AdminAccountAutoLockListenerTest` 4, `LoginFailureServiceTest` 13, `LoginFailureLockoutIntegrationTest` 4, `LoginFailureConcurrencyIntegrationTest` 1) + 기존 7클래스 파급 보정

### 검증 결과
- `./gradlew test` 전체 **338개 통과** (실패 0, 에러 0)
- 빈 스키마(`cms_fresh`)에서 Flyway V1→V4 전체 적용 + `ddl-auto: validate` 기동 성공 (로그 확인 후 스키마 삭제)
- Playwright 실기 (스크린샷 `lockout-01~04*.png`): 5회 실패 → LOCKED·감사 1건 → 올바른 비밀번호도 거부·카운트 불변 → `locked_at` 31분 전 세팅 후 로그인 → **자동 해제·대시보드 진입**(ACTIVE·카운트 0·`locked_at` null·`update_date` 앱 시계 갱신) → 활동 로그 화면 "계정 자동 잠금" 라벨 노출 → admin이 회원 관리 화면에서 상태 "활성" 저장 → **해제·카운트 리셋** → 재로그인 성공 → 메뉴 관리 화면 회귀 정상
- 완료 기준 체크리스트 전 항목 충족 (잠금 정책 승인 2026-07-14 포함)

### 이슈
- 검증 중 DB 컨테이너 시계가 UTC임을 실측 — SQL `NOW()`로 `locked_at`을 만지면 앱 KST와 9시간 어긋난다. **구현이 앱 Clock(:now 파라미터)으로 기록하는 R16#2 결정의 타당성이 실증됨** (수동 SQL 조작 시에만 주의).

### 후속
- 자동 잠금 계정의 멱등 재잠금(동일값 LOCKED PATCH)은 `locked_at`이 유지되어 30분 해제가 살아 있다 — 영구 잠금 의도라면 다른 상태 경유 필요 (엣지 11, 문서화됨. 개선은 별도 판단).
- 극단 동시 잠금 시 감사 INSERT 지연 가능성은 수용된 잔존 리스크 (v19).

## 개정 이력

- **v23 변경 (2026-07-14, 적대적 리뷰 20라운드 — codex no-ship 판정 반영, 1건 수용)**:
  - **[수용, R20#1] 자동 해제 호출부 3인자 통일**: 단계 5의 `unlockIfLockExpired(userId, now - LOCK_DURATION)` 2인자 호출을 저장소 계약(3인자, `updateDate = :now`)과 일치하게 `unlockIfLockExpired(userId, now.minus(LOCK_DURATION), now)`로 교정 — R16#2 결론의 호출부 전파.
- **v22 변경 (2026-07-14, 적대적 리뷰 19라운드 — codex no-ship 판정 반영, 1건 수용)**:
  - **[수용, R19#1] 성공 핸들러 테스트의 인증 측 해시 공급원 명시**: 해시 비교의 `Authentication` 쪽 값이 테스트에서 공급되지 않아 캐스팅 예외/전부 거부/검증 누락 중 하나가 됨 — `VisitLoggingAuthenticationSuccessHandlerTest.authWith()`가 실제 `CustomUserDetails`(인증 당시 해시 보유)를 principal로 제공하도록 변경, 정상 경로 스냅샷의 해시를 그 principal 해시와 일치시킴, **예상 밖 principal 타입·인증 해시 부재도 fail-closed** 되는 케이스 추가.
- **v21 변경 (2026-07-14, 적대적 리뷰 18라운드 — codex no-ship 판정 반영, 사용자 결정 포함)**:
  - **[수용+사용자 결정, R18#1] 본인 비밀번호 변경 시 전 세션 폐기** (2026-07-14 사용자 결정): 해시 재확인 **직후** 커밋되는 본인 비밀번호 변경의 TOCTOU 창은 재확인만으로 닫을 수 없음 — `changeMyPassword()`(내 비밀번호 변경)가 커밋 후 `AdminSessionRevokeEvent(memberId)`를 발행해 대상 계정의 **모든 세션을 폐기**한다 (이메일 재설정·역할/상태 변경 경로와 대칭, 기존 계약 재사용). **행위 변경 명시**: 비밀번호를 변경한 본인 세션도 다음 요청에서 만료되어 재로그인이 필요하다 ("비밀번호 변경 후 재로그인" 관행) — 기존 `refreshAuthentication()` 호출의 의미가 사실상 사라지며, `member/info` 화면의 변경 후 동작을 playwright로 확인한다. 테스트: 본인 비밀번호 변경 시 이벤트 발행 검증(`AdminMemberServiceTest`). 이 조치로 "이전 비밀번호 세션 생존" 창이 닫힌다 — 세션 등록이 성공 핸들러 재확인에 선행하므로, 재확인 전 커밋은 해시 불일치로(v20), 재확인 후 커밋은 전 세션 폐기로(v21) 각각 잡힌다.
- **v20 변경 (2026-07-14, 적대적 리뷰 17라운드 — codex no-ship 판정 반영, 2건 전부 수용)**:
  - **[수용, R17#1] 성공 직전 재확인에 비밀번호 해시 검증 추가**: 인증 중 비밀번호 변경(재설정·본인 변경)이 커밋되면 변경 **전** 비밀번호로 새 세션이 생성될 수 있는 경합(R15#1과 동일 구조) — `resetFailuresAndCheckActive()` 스냅샷에 **fresh 비밀번호 해시**를 포함하고, 인증에 사용된 해시(`CustomUserDetails`가 보관)와 불일치하면 상태·역할 불일치와 동일하게 fail-closed. **구현 주의**: Spring의 `eraseCredentialsAfterAuthentication`이 principal 해시를 지울 수 있음 — `CustomUserDetails`에 `CredentialsContainer` 소거 대상이 아닌 비교용 해시 필드를 보존(구현 시 `CustomUserDetails`의 소거 동작 확인). "변경 전 해시 로드 → 비밀번호 변경 커밋 → 성공 핸들러 진입 → 거부" 결정론적 테스트 추가.
  - **[수용, R17#2] v18 반환형 변경의 스텁 지침 전파**: 성공 경로 스텁을 `true`가 아니라 "**ACTIVE + `Authentication`과 일치하는 역할·해시 스냅샷**" 반환으로 교정 (`VisitLoggingAuthenticationSuccessHandlerTest`·`SecurityConfigTest`·`ApiSecurityConfigTest`·`PasswordResetControllerTest` 공통), empty·비ACTIVE·역할 불일치·해시 불일치·예외는 별도 거부 케이스로 명시.
- **v19 변경 (2026-07-14, 적대적 리뷰 16라운드 — codex no-ship 판정 반영)**:
  - **[부분 수용, R16#1] 리스너 순서 보장 + 비동기 분리 기각**: `AdminSessionRevokeListener`(인메모리 세션 만료, DB 불필요)가 감사 리스너보다 **반드시 먼저** 실행되도록 두 리스너에 `@Order`를 명시하고 순서를 테스트한다. **기각**: 감사 저장의 비동기/durable outbox 분리 — 풀 고갈은 "풀 크기만큼의 서로 다른 관리자 계정이 동시에 5회째 실패 커밋"이 전제인데 역할 allowlist로 대상 계정 수가 적은 백오피스에서 비현실적이며, 최악 영향도 (이미 커밋된 잠금은 유지된 채) 감사 INSERT의 커넥션 대기 지연에 그친다. 새 비동기 인프라는 과설계. **잔존 리스크로 명시**: 극단 동시 잠금 시 감사 기록이 지연·실패(격리됨)할 수 있다.
  - **[수용, R16#2] 로그인 벌크 해제의 시간 기준 통일**: `unlockIfLockExpired` 쿼리의 `updateDate = CURRENT_TIMESTAMP`(DB 시계)를 `:now`(앱 KST Clock) 파라미터로 교체 — `unlockIfLockExpired(userId, cutoff, now)`. DB가 UTC인 배포에서 `updateDate` 9시간 후퇴 방지. 고정 Clock 테스트에서 해제 후 `updateDate == now` 단언.
  - **[수용, R16#3] v18 전파 누락 교정**: `PasswordResetService` 표의 `releaseExpiredAutoLock` 시그니처를 2인자로 통일, 성공 핸들러 수정 표에 역할 불일치 거부 추가, 엣지 케이스 10의 "LOCKED 계정은 재설정 자격이 없다" 단정을 "만료된 자동 잠금은 해제 후 허용(엣지 13)" 예외와 정합하게 교정.
- **v18 변경 (2026-07-14, 적대적 리뷰 15라운드 — codex no-ship 판정 반영, 3건 전부 수용)**:
  - **[수용, R15#1] 성공 직전 재확인에 역할 검증 추가**: 인증 중 역할 강등이 커밋되고 새 세션이 아직 미등록인 창에서 낡은 ADMIN 권한 세션이 생존 가능 — `resetFailuresAndCheckActive()`가 fresh `status`와 함께 **fresh `userType`**을 반환하고, 성공 핸들러가 `Authentication`의 권한과 비교해 불일치(강등·승격 모두)면 상태 비ACTIVE와 동일하게 로그인 거부(세션 무효화 + login-error). `ADMIN→MANAGER` 강등 케이스 테스트 추가. (재로그인하면 새 권한으로 정상 로그인된다.)
  - **[수용, R15#2] `releaseExpiredAutoLock`의 `updateDate` 갱신**: 재설정 발급 경로에서 해제 후 쿨다운 조기 반환 시 ACTIVE 전이만 커밋되고 `updateDate`가 과거에 머무는 이력 불일치 — 시그니처를 `releaseExpiredAutoLock(cutoff, now)`로 바꿔 동일 `Clock`의 `now`로 `updateDate`도 갱신, 로그인 벌크 해제와 필드 계약 통일. 쿨다운 조기 반환 케이스에서도 상태·`updateDate` 동시 갱신 테스트 추가.
  - **[수용, R15#3] v17 잔존 문서 모순 교정**: 설계 결정 표의 `REQUIRES_NEW` 잔존 → 단일 REQUIRED 참여로 교정, 단계 9-1의 "해제 커밋 후 조회" → "같은 트랜잭션에서 해제 후 fresh 조회, 메서드 반환 시 커밋"으로 교정, `LOCK_DURATION` 선언을 `public static final` 하나로 통일.
- **v17 변경 (2026-07-14, 적대적 리뷰 14라운드 — codex no-ship 판정 반영)**:
  - **[수용, R14#1] 로그인 경로 트랜잭션 구조 변경**: 외부 readOnly 트랜잭션 + 내부 `REQUIRES_NEW`는 로그인마다 커넥션 2개를 요구해 병렬 폭주 시 풀 고갈 위험 — `CustomUserDetailsService.loadUserByUsername()`을 **쓰기 가능한 단일 `@Transactional`(REQUIRED)**로 바꾸고 `unlockIfLockExpired()`는 같은 트랜잭션에 참여시킨다(`REQUIRES_NEW` 제거). 메서드는 비밀번호 검증 전에 커밋되므로 틀린 비밀번호여도 해제는 유지된다. **부분 기각**: bounded-pool 병렬 로그인 테스트 — 구조 변경으로 이중 커넥션 획득 자체가 사라져 실패 모드가 제거됨(부재 증명 테스트는 비결정적).
  - **[수용+사용자 결정, R14#2] 재설정 경로에도 만료 자동 잠금 lazy 해제 적용** (2026-07-14 사용자 결정): `Member`에 도메인 메서드 `releaseExpiredAutoLock(LocalDateTime cutoff)` 추가 — LOCKED이고 `lockedAt <= cutoff`면 ACTIVE·카운트 0·`lockedAt` null로 전이하고 true 반환. `PasswordResetService`의 발급(`findByEmailForUpdate`)·토큰 확인(`findByIdForUpdate`) 진입점에서 `isEligible()` 판정 **전에** 호출한다 — 기존 행 잠금 안이라 경합 안전, 수동 잠금(`lockedAt` null)은 여전히 부적격. `LOCK_DURATION`은 `LoginFailureService`의 public 상수를 공유. 테스트: 만료 잠금 계정 발급·확인 가능 / 미만료 잠금 부적격 유지 / 수동 잠금 부적격 유지.
- **v16 변경 (2026-07-14, 적대적 리뷰 13라운드 — codex no-ship 판정 반영, 1건 수용)**:
  - **[수용, R13#1] 실커밋 부작용 정리 완결**: v12의 정리 목록(회원·`AdminActionLog`)이 놓친 부작용 보완 — ① `LoginFailureLockoutIntegrationTest`: 성공 로그인 케이스가 남기는 고유 `visitorUserId`의 `VisitLog` 삭제 + 성공 로그인 세션(`SessionRegistry`) 정리, ② `LoginFailureConcurrencyIntegrationTest`: AFTER_COMMIT 리스너가 `REQUIRES_NEW`로 영구 저장하는 자동 잠금 `AdminActionLog`도 대상 한정 삭제. `deleteAll()` 금지 유지 — 고유 식별자/대상 ID로만 삭제.
- **v15 변경 (2026-07-14, 적대적 리뷰 12라운드 — codex no-ship 판정 반영, 2건 전부 수용)**:
  - **[수용, R12#1] 잠금 생명주기에 역할 allowlist**: 관리자 로그인 폼으로 일반 회원(`ROLE_USER`) 아이디를 5회 틀리면 범위 밖 계정까지 LOCKED 전이 가능 — 증가·잠금·자동 해제·성공 리셋 쿼리 전체에 `userType in (ROLE_ADMIN, ROLE_MANAGER)` 조건 추가 (비밀번호 재설정 `isEligible()`·관리자 관리의 기존 allowlist와 일관). `ROLE_USER`는 5회 실패해도 상태·카운트·이벤트 불변 테스트 추가.
  - **[수용, R12#2] 수정 파일 목록 완결**: `PasswordResetServiceTest.java`(재설정 시 카운트 리셋 검증)와 `docs/troubleshooting.md`(최후 ADMIN 복구 절차 기록)를 수정 파일 목록에 추가 — 완료 기준과의 불일치 해소.
- **v14 변경 (2026-07-14, 적대적 리뷰 11라운드 — codex no-ship 판정 반영, 2건 수용)**:
  - **[수용, R11#1] 테스트 슬라이스 `Clock` 빈 구성 명시**: `@DataJpaTest`는 일반 `@Configuration`(`AppConfig`)을 스캔하지 않아 `Clock` 부재로 컨텍스트 기동 실패 — `LoginFailureServiceTest`에 **고정 `Clock`을 제공하는 중첩 `@TestConfiguration`**을 두고 `@Import`에 포함한다. 경계 3종 테스트는 이 고정 Clock 기준으로 `lockedAt`을 세팅해 검증.
  - **[수용, R11#2] 인증 예외 래핑 사실 교정**: `DaoAuthenticationProvider.retrieveUser()`는 `loadUserByUsername()` 내부에서 던진 `LockedException`/`DisabledException` 등을 `InternalAuthenticationServiceException`으로 **래핑**한다(`UsernameNotFoundException`·`InternalAuthenticationServiceException`만 그대로 전파) — 실패 핸들러에 `LockedException`이 그대로 도달한다는 서술과 엣지 케이스 3을 교정. 카운트 조건은 "**`BadCredentialsException`일 때만 카운트** — 그 외 모든 인증 실패(래핑된 상태 예외 포함)는 비카운트"로 명확화(기능 동작은 동일). 테스트 보강: `InternalAuthenticationServiceException` 비카운트 단위 테스트 + MockMvc 수동 LOCKED 테스트에서 거부와 함께 `failedLoginCount` 불변 단언. **기각(일부)**: `CustomUserDetails` 상태 플래그로 공급자 표준 사전 검사를 타도록 하는 재설계 — 기존 로그인 예외 흐름 전면 변경으로 이번 범위를 벗어남(현행 예외 방식 유지).
- **v13 변경 (2026-07-14, 적대적 리뷰 10라운드 — codex no-ship 판정 반영, 3건 전부 수용)**:
  - **[수용, R10#1] 동시성 테스트 소유권 분리**: `@DataJpaTest` 관리 트랜잭션의 미커밋 준비 데이터는 자식 스레드의 독립 트랜잭션에서 보이지 않아 vacuous pass/실패 — 동시 실패 경합 테스트를 기존 선례(`AdminMemberUpdateConcurrencyIntegrationTest` 등)대로 별도 **`LoginFailureConcurrencyIntegrationTest`**(`@SpringBootTest`, 테스트 관리 트랜잭션 없음, 고유 회원 수동 정리)로 분리.
  - **[수용, R10#2] 감사 필드 계약 확정**: `AdminActionLogService.log()`의 전체 필드 매핑을 확정 — `actionId=null`, `actionUserId=null`(미인증 흐름), `actionResult=SUCCESS`(의미: **자동 잠금 전이 성공** 이벤트, 로그인 실패 로그가 아님), `targetType="MEMBER"`, `targetId=memberId`, `requestMethod="POST"`, `requestIp`/`requestUri`=핸들러 절단값, `errorMessage=null`. 리스너 테스트에서 건수뿐 아니라 필드 값까지 단언. (실제 `log()` 시그니처는 구현 시 확인해 동일 의미로 매핑.)
  - **[수용, R10#3] 기존 `Clock` 빈 주입으로 v5 기각 철회**: 저장소에 이미 KST `Clock` 빈(`AppConfig`)이 있고 `PasswordResetService`가 사용 중 — 새 인프라가 아니므로 "과설계" 기각 사유가 성립하지 않음. `LoginFailureService`는 이 `Clock`을 주입받아 `LocalDateTime.now(clock)`으로 잠금 시각·cutoff를 계산한다(테스트 컨텍스트는 `main()`의 KST 강제를 타지 않으므로 서비스와 동일 Clock 사용이 필수). 자동 해제 테스트를 **경계 3종**(cutoff 직전 → 해제 안 됨 / 정확히 cutoff → 해제됨 / cutoff 직후 → 해제됨)으로 확장 — `lockedAt <= cutoff` 경계 계약 검증.
- **v12 변경 (2026-07-14, 적대적 리뷰 9라운드 — codex no-ship 판정 반영, 3건 전부 수용)**:
  - **[수용, R9#1] `AdminMemberServiceTest` 파급 보정**: `changeMyPassword()`의 `findByIdForUpdate` 전환으로 기존 비밀번호 변경 테스트 4건(변경 성공·재설정 토큰 클리어·현재 비밀번호 불일치·회원 미존재)의 `findById` 스텁이 unstubbed `Optional.empty()`로 실패 — 스텁·`never()` 검증을 `findByIdForUpdate` 기준으로 교체. 수정 파일 목록에 추가.
  - **[수용, R9#2] MockMvc 실배선 테스트 소유권 지정**: formLogin 케이스(5회 실패 잠금·만료 자동 해제·수동 잠금 거부)를 담을 전용 `LoginFailureLockoutIntegrationTest`(`@SpringBootTest` + `@AutoConfigureMockMvc`)를 신규 목록에 추가 — `@DataJpaTest`로는 보안 필터 체인 검증 불가.
  - **[수용, R9#3] 실커밋 테스트 사후 정리**: `TestTransaction` 실커밋은 `@DataJpaTest` 기본 롤백 밖 — 공유 dev DB 잔류 방지를 위해 고유 테스트 식별자 사용 + `@AfterEach`에서 해당 테스트가 생성한 회원·`AdminActionLog`만 대상 한정 삭제 (기존 `AdminMemberUpdateConcurrencyIntegrationTest` 선례).
- **v11 변경 (2026-07-14, 적대적 리뷰 8라운드 — codex no-ship 판정 반영, 2건 전부 수용)**:
  - **[수용, R8#1] 생성자 파급 범위 완결**: 성공 핸들러 1인자 생성자를 직접 호출하고 실제 `SecurityConfig`를 import하는 테스트 3곳 추가 수정 — `SecurityConfigTest`, `ApiSecurityConfigTest`, `PasswordResetControllerTest`. 각각 `LoginFailureService` mock 전달 + `resetFailuresAndCheckActive()` `true` 스텁, 그리고 `SecurityConfig.filterChain`의 새 파라미터 `LockingAuthenticationFailureHandler` 빈을 슬라이스 컨텍스트에 제공.
  - **[수용, R8#2] `@DynamicUpdate` 테스트 트랜잭션 경계 고정**: "별도 EntityManager/JDBC"가 독립 트랜잭션으로 커밋되면 REPEATABLE READ 스냅샷 때문에 같은 트랜잭션 재조회가 변경을 못 봐 거짓 실패 — **같은 물리 트랜잭션에 참여하는 네이티브 UPDATE**(`entityManager.createNativeQuery` 또는 트랜잭션 참여 `JdbcTemplate`)로 영속성 컨텍스트만 우회한 뒤 flush → clear → 재조회로 절차를 고정.
- **v10 변경 (2026-07-14, 적대적 리뷰 7라운드 — codex no-ship 판정 반영, 3건 전부 수용)**:
  - **[수용, R7#1] `changePassword()`는 `lockedAt`을 건드리지 않는다**: 본인 비밀번호 변경(행 잠금 없는 `findById`)과 5회째 실패 잠금이 경합하면 `lockedAt=null` 덮어쓰기로 자동 잠금이 **영구 잠금으로 변질**될 수 있다(@DynamicUpdate는 실제 변경된 필드는 못 막음) — `changePassword()`는 **카운트만 0으로 리셋**하고 `lockedAt`은 보존한다(LOCKED 중 비번 변경이 일어나도 30분 자동 해제 유지, 유해 상태 원천 차단). 추가로 내 비밀번호 변경의 회원 조회를 `findByIdForUpdate`로 전환해 잠금 벌크 UPDATE와 직렬화한다(이메일 재설정 경로가 이미 쓰는 패턴). v2·v6의 "changePassword가 resetFailedLoginCount() 호출" 결정을 이 형태로 대체. LOCKED 상태에서 `changePassword()` 호출 시 `lockedAt` 보존 테스트 추가.
  - **[수용, R7#2] `@DynamicUpdate` 테스트 방식 교정**: 계획했던 "벌크 잠금 UPDATE 후 flush" 순서는 `clearAutomatically`가 컨텍스트를 비워 원본 엔티티가 detach → flush가 UPDATE를 내지 않아 vacuous pass — 별도 `EntityManager`/JDBC 네이티브 UPDATE로 DB 상태만 변경(원본 엔티티 managed 유지)하고, dirty-checking UPDATE가 실제 실행된 사실과 LOCKED·`locked_at` 보존을 함께 검증한다.
  - **[수용, R7#3] 기존 성공 핸들러 테스트 수정 명시**: `VisitLoggingAuthenticationSuccessHandlerTest`를 수정 파일 목록에 추가 — 생성자 의존성 추가 반영 + 공통 설정에서 `resetFailuresAndCheckActive()`를 `true`로 스텁(Mockito 기본 `false` 반환이면 기존 성공 테스트 전부가 거부 분기로 오염), false·예외 케이스에서만 재정의.
- **v9 변경 (2026-07-14, 적대적 리뷰 6라운드 — codex no-ship 판정 반영, 3건 전부 수용 — 전부 v8 반영 과정의 문서 내부 모순 교정)**:
  - **[수용, R6#1] 단계 5의 fail-open 잔존 제거**: `resetFailuresAndCheckActive()` 설명을 fail-closed(예외 시 성공 핸들러가 로그인 거부)로 통일 — R5#1 결정과 충돌하던 서술 교정. "성공 리셋 best-effort"의 의미를 한정: **리셋 결과 0행(경합으로 이미 잠김)은 허용, 메서드 예외는 인증 거부**.
  - **[수용, R6#2] 감사 구조와 의존성·테스트 정합**: `LoginFailureService`에서 `AdminActionLogService` 의존 제거(v8 구조에선 이벤트만 발행) — 서비스 테스트는 `AdminAccountAutoLockEvent` 1회 발행 검증. 신규 `AdminAccountAutoLockListenerTest` 추가(`AdminSessionRevokeListenerTest` 선례 — AFTER_COMMIT 애너테이션·예외 격리 직접 검증). 감사 커밋 1건/롤백 0건 검증은 `@DataJpaTest` 기본 롤백에 의존하지 말고 `TestTransaction`(또는 `TransactionTemplate`)으로 경계를 제어해 실제 publisher·listener·repository로 검증.
  - **[수용, R6#3] 동시성 테스트 기대값 교정**: 증가 쿼리의 `status = ACTIVE` 조건(v3) 때문에 5회째 잠금 커밋 후 후속 증가는 0행 — 초기 0에서 N개 동시 실패의 최종 카운트는 `N`이 아니라 **`min(N, 5)`**. `N >= 5`에서 잠금 전이·세션 폐기 이벤트가 각각 정확히 1회 발행되는지 검증 추가. 엣지 케이스 1의 "6, 7이 되어도" 서술 교정.
- **v8 변경 (2026-07-14, 적대적 리뷰 5라운드 — codex no-ship 판정 반영, 3건 전부 수용)**:
  - **[수용, R5#1] 상태 재확인 fail-closed 전환**: 재확인은 방문 로그 같은 부가 기능이 아니라 **인증 결정 자체** — 예외 시에도 false와 동일하게 세션 무효화 + `SecurityContext` 클리어 + `/admin/login-error` 리다이렉트(로그인 거부). v7의 fail-open 결정을 철회한다 (fail-open은 "LOCKED 계정 세션이 남지 않는다" 불변식을 DB 일시 장애 인터리빙에서 깨뜨림). 재확인 직전까지 DB가 정상이었으므로 가용성 손실은 미미. 테스트도 "예외 시 로그인 거부"로 변경.
  - **[수용, R5#2] 감사 로그를 AFTER_COMMIT으로 이동**: `REQUIRES_NEW` 감사를 잠금 트랜잭션 안에서 호출하면 원 트랜잭션 롤백 시에도 "잠금 성공" 감사가 남는다 — `recordFailure()`는 전용 이벤트 `AdminAccountAutoLockEvent(memberId, userId, requestIp, requestUri)`만 발행하고, 신규 AFTER_COMMIT 리스너가 감사 기록(`AdminActionLogService.log()`) + `log.warn`을 수행한다. 커밋 후 실행이므로 감사 실패가 잠금을 롤백시키지 않는 v5 요구도 자연 충족(리스너 내 try-catch 유지). v5의 "트랜잭션 내 try-catch 감사 호출"은 이 방식으로 대체. "강제 롤백 시 감사 로그 미생성" 테스트 추가.
  - **[수용, R5#3] 재확인을 성공 핸들러의 첫 작업으로 명시**: 상태 재확인 → (ACTIVE일 때만) 방문 로그 → 기본 성공 처리 순서. 잠금으로 거부되는 인증이 성공 방문(`VisitLog`)으로 기록되지 않는 테스트 추가.
- **v7 변경 (2026-07-14, 적대적 리뷰 4라운드 — codex no-ship 판정 반영)**:
  - **[수용, R4#1] 성공 핸들러에 잠금 상태 재확인 추가**: 동시 성공 인증이 잠금 전이를 우회해 LOCKED 계정의 신규 세션이 생존하는 경합 확인 — 성공 핸들러에서 `LoginFailureService.resetFailuresAndCheckActive(userId)`(조건부 리셋 + **fresh 상태 재조회**, 단일 트랜잭션)를 호출하고, 비ACTIVE면 세션 무효화 + `SecurityContextHolder.clearContext()` + `/admin/login-error` 리다이렉트(기본 성공 리다이렉트 생략). **선형화 논증**: 세션 등록(SessionAuthenticationStrategy)은 success handler 이전에 완료되므로, 잠금 커밋이 재확인 전이면 자가 무효화가, 재확인 후면 이미 등록된 세션을 AFTER_COMMIT 리스너가 만료한다 — 어느 인터리빙도 LOCKED 계정 세션이 남지 않는다(리스너 계약 수준의 best-effort). 재확인 호출 자체의 예외는 `log.error` + 로그인 허용(fail-open) — 기존 격리 패턴과 일관. **부분 기각**: 래치 기반 멀티스레드 통합 테스트 — 위 논증으로 인터리빙이 양분되며 각 분기는 순차 테스트(성공 시점 LOCKED → 세션 무효화 + login-error)와 기존 `AdminSessionRevocationIntegrationTest`(리스너 만료)로 결정론적으로 검증된다.
  - **[수용, R4#2] 문서 정합 교정 3건**: ① `AdminActionTypes` 실제 경로는 `admin/log/constant/AdminActionTypes.java`, ② 수정 파일 표의 `AdminMemberService` 리셋 조건을 단계 9와 동일하게 "비ACTIVE→ACTIVE 실변경"으로 통일, ③ "MemberRepository에 쿼리 2개"를 실제 개수인 **4개**(증가·잠금·성공 리셋·자동 해제)로 교정.
- **v6 변경 (2026-07-14, 적대적 리뷰 3라운드 — codex no-ship 판정 반영, 4건 전부 수용)**:
  - **[수용, R3#1] `@DynamicUpdate` 부착**: 프로필·내 정보 수정 등 행 잠금 없는 더티체킹 쓰기 경로가 경합 시 전체 컬럼 UPDATE로 잠금 3필드(status·failedLoginCount·lockedAt)를 stale 값으로 되써 잠금을 소실시킬 수 있다 — `Member`에 `@DynamicUpdate`를 부착해 변경 컬럼만 UPDATE(서로 다른 필드 간 lost update 차단). `@Version`은 벌크 UPDATE가 버전을 증가시키지 않아 부적합, 전면 행 잠금은 과침습이라 기각. 기존 `findByIdForUpdate` 행 잠금(같은 필드 경합 방어)은 그대로 유효. 결정론적 검증: 엔티티 로드 → 벌크 잠금 UPDATE → 더티체킹 flush → 재조회 시 LOCKED 보존 테스트.
  - **[수용, R3#2] `changeStatus()`가 `lockedAt` 클리어**: LOCKED(자동)→DISABLED→LOCKED(수동) 전이에서 `locked_at`이 잔존해 수동 영구 잠금이 30분 뒤 자동 해제되는 정책 위반 — `changeStatus()`(상태 실변경 시에만 호출됨)가 항상 `lockedAt = null`로 정리한다. 수동 →LOCKED는 영구가 되고, 자동 잠금에서 다른 상태로 나갈 때도 잔존 시각이 정리된다. 멱등 재잠금(동일값 LOCKED)은 `changeStatus` 미호출 → 기존 문서화 동작(30분 해제 유지) 불변. `changePassword()`는 내부에서 `resetFailedLoginCount()` 호출로 카운트+`lockedAt` 동시 정리. `LOCKED→DISABLED→LOCKED` 회귀 테스트 추가.
  - **[수용, R3#3] 핸들러-서비스 시그니처 정합**: 핸들러 단계 기술을 3인자 호출(`recordFailure(username, 절단된 IP, 절단된 URI)`)로 교정 — IP·URI 추출·절단은 핸들러 책임. 핸들러 테스트에서 전달값까지 검증.
  - **[수용, R3#4] 감사 action type 파일 명시**: `AdminActionTypes.java`(상수 추가)와 `templates/admin/log/manage.html`(라벨 맵 등록 — `AdminActionTypeLabelSyncTest`가 강제)을 수정 파일 목록에 추가.
- **v5 변경 (2026-07-14, 적대적 리뷰 2라운드 — codex no-ship 판정 반영, 5건 전부 수용)**:
  - **[수용, R2#1] 감사 로그 예외 격리**: `AdminActionLogService.log()`는 자체 예외 격리가 없다(격리는 AOP 호출부에만 존재) — `recordFailure()`에서 감사 호출을 try-catch로 감싸 감사 실패가 잠금 트랜잭션을 롤백시키지 않게 한다. IP·URI는 컬럼 길이에 맞게 절단(기존 `truncateIp` 패턴 미러). "감사 로그 예외에도 잠금 유지" 테스트 추가.
  - **[수용, R2#2] 성공 리셋을 조건부 벌크 UPDATE로 교체**: 더티체킹 전체 컬럼 UPDATE는 경합 시 LOCKED를 ACTIVE로 되살리거나 `LOCKED + locked_at null`(영구 잠금 변질)을 만들 수 있다 — `resetFailuresIfActive`: `set failedLoginCount = 0, lockedAt = null where userId = :userId and status = ACTIVE and failedLoginCount > 0`. 잠금 후 리셋은 0행 no-op, 리셋 후 실패는 1부터 재증가 — 양방향 안전. **v3의 교차 경합 테스트 기각 철회**: 스레드 불필요 — "LOCKED 계정에 resetFailures → no-op(상태·locked_at 보존)" 순차 테스트로 결정론적 검증.
  - **[수용, R2#3] 테스트 슬라이스 구성**: `LoginFailureServiceTest`에서 `AdminActionLogService`는 **mock 등록** (`@MockitoBean`) — 슬라이스가 `@Service`를 자동 등록하지 않고, 실서비스는 `REQUIRES_NEW` 커밋으로 테스트 데이터를 오염시킨다. `ApplicationEventPublisher`도 검증 겸 mock.
  - **[수용, R2#4] 시간 기준 단일화**: `locked_at` 기록과 만료 cutoff를 모두 **앱 시계**로 통일 — `lockIfThresholdReached`에 `:now` 파라미터 전달(DB `CURRENT_TIMESTAMP` 미사용), cutoff도 같은 `LocalDateTime.now()`에서 계산. 주입식 `Clock`은 테스트가 `locked_at` 직접 세팅으로 충분해 과설계로 미채택.
  - **[수용, R2#5] memberId 획득 절차 명시**: 잠금 UPDATE가 1행이면 같은 트랜잭션에서 `findByUserId(userId)` 재조회(`clearAutomatically = true`로 1차 캐시가 비워져 fresh)로 id를 얻어 감사 로그·`AdminSessionRevokeEvent`에 전달. 조회 실패(이론상) 시 이벤트·감사 생략 + `log.error`. MockMvc formLogin 통합 테스트에 "만료 자동 잠금 + 올바른 비밀번호 → 성공", "수동 잠금(locked_at null) + 올바른 비밀번호 → 거부" 2케이스 추가.
- **v4 변경 (2026-07-14, 리뷰#1 사용자 결정 반영 — 30분 자동 해제)**:
  - **잠금 정책 변경**: 영구 잠금 → **30분 자동 해제** (2026-07-14 사용자 결정). DoS가 일시화되고 무차별 대입도 5회/30분으로 여전히 차단된다.
  - **새 컬럼 추가**: `locked_at DATETIME(6) NULL` — 자동 잠금 시각. `lock_expires_at` 대신 잠금 시각 저장 + 기간(30분)은 코드 상수 → 정책 기간 변경 시 데이터 마이그레이션 불필요.
  - **해제 메커니즘 = lazy 조건부 UPDATE**: 로그인 시도 시 `CustomUserDetailsService`가 회원 조회 **전에** `LoginFailureService.unlockIfLockExpired(userId)`(`REQUIRES_NEW` — 외부 readOnly 트랜잭션과 분리해 쓰기 커밋)를 호출. 쿼리는 `status = LOCKED and locked_at is not null and locked_at <= :cutoff`일 때만 ACTIVE·카운트 0·locked_at null로 원자 갱신 — 동시 로그인 경합에도 멱등. 스케줄러 폴링은 신규 인프라 대비 이득 없어 기각.
  - **수동 잠금과의 구분 (의도된 설계)**: PATCH로 수동 잠금(LOCKED)한 계정은 `locked_at = null`이라 자동 해제되지 않는다 — 관리자의 명시적 잠금은 영구, 자동 잠금만 30분 해제.
  - `lockIfThresholdReached`가 `locked_at = CURRENT_TIMESTAMP`도 설정. 비ACTIVE→ACTIVE 수동 해제 시 카운트와 함께 `locked_at`도 클리어(`resetFailedLoginCount()`가 둘 다 처리).
  - 최후 ADMIN 잠금도 30분 후 자동 복구 — DB 직접 복구는 비상 수단으로 격하(troubleshooting 기록은 유지).
- **v3 변경 (2026-07-14, 적대적 리뷰 1라운드 — codex no-ship 판정 반영)**:
  - **[수용, 리뷰#2] 자동 잠금 시 기존 세션 만료**: `LoginFailureService.recordFailure()`에서 잠금 전이 성공 시 `AdminSessionRevokeEvent(memberId)`를 발행한다 (기존 AFTER_COMMIT 리스너 재사용, PATCH 재잠금과 의미 일관). **명시된 트레이드오프**: 공격자가 5회 실패만으로 로그인 중인 관리자를 강제 로그아웃시킬 수 있다 — LOCKED 의미 일관성을 우선해 수용.
  - **[수용, 리뷰#3] 증가 쿼리에 상태 조건 추가**: `increaseFailedLoginCount`에 `and m.status = ACTIVE` 조건을 추가해, 로그인 검증과 관리자 상태 변경이 경합해도 비ACTIVE 계정에 카운트가 숨어 누적되지 않게 한다. 카운트 리셋 조건도 "LOCKED→ACTIVE"에서 "**비ACTIVE→ACTIVE 실변경**"으로 확대 (DISABLED→ACTIVE 복구도 실패 연쇄 단절).
  - **[수용, 리뷰#6] 감사 로그 기록**: 잠금 전이 시 `AdminActionLogService.log()`를 직접 호출해(호출자가 ID 명시 전달 가능, `action_user_id` nullable 확인) `AdminActionLog`에 구조적으로 기록하고 `log.warn`을 병행한다. 새 action type 상수는 `AdminActionType*SyncTest` 규약에 맞춰 추가.
  - **[수용, 리뷰#5] 타이밍 은닉 문구 제거**: 존재/미존재 계정의 SQL 횟수가 달라(2회 vs 1회) "응답 시간으로 새지 않는다"는 주장은 성립하지 않음 — 문구 삭제. 계정 열거 완화(요청 제한 등)는 이번 범위 밖으로 명시.
  - **[수용, 리뷰#7] 테스트 계획 보강**: `@DataJpaTest`는 `@Component`를 자동 등록하지 않으므로 `@Import({LoginFailureService.class, QuerydslConfig.class})` 필요. 동시 실패 경합 테스트(기존 `*ConcurrencyIntegrationTest` 선례), MockMvc formLogin 통합 테스트(SecurityConfig 실배선 검증), `changePassword()` 리셋의 양쪽 경로(내 비번 변경·이메일 재설정) 검증, 빈 DB V1→V4 기동 검증 절차 추가.
  - **[부분 수용, 리뷰#4] "연속"의 선형화 기준 명문화**: 동시 성공·실패의 순서는 DB 반영(행 잠금 획득) 순서를 따르며, 성공 리셋은 best-effort임을 정책으로 명시 (리셋 실패는 `log.error`, 최악 케이스는 조기 잠금 — 잠금 해제로 복구 가능). **기각(일부)**: 성공/실패 교차 경합 통합 테스트 — 결정론적 재현이 어렵고 최악 결과가 "조기 잠금(복구 가능)"이라 비용 대비 가치 낮음. 동시 실패 경합 테스트로 원자성 핵심만 검증.
  - **[결정 필요, 리뷰#1] 영구 잠금 DoS 정책**: 사용자 결정 대기 — 현행 유지(영구 잠금 + DB 복구) vs 시간 기반 자동 해제 vs 알림 병행.
- **v2 변경 (2026-07-14, 착수 정찰 반영)**:
  - `SecurityConfig`의 `failureUrl` 위치를 57행 → **60행**으로 교정 (실측).
  - 테스트 참조 파일 교정: `MemberRepositoryImplSortTest`는 DB 없는 순수 단위 테스트라 벌크 UPDATE 검증에 부적합 — **`VisitLogRepositoryDataJpaTest` 패턴**(`@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `@ActiveProfiles("dev")`, 로컬 MariaDB 필요)을 따른다.
  - **설계 결정 추가**: `Member.changePassword()`에 `failedLoginCount = 0` 리셋 포함 — 비밀번호가 바뀌면 이전 실패 연쇄는 단절된 것이므로(내 비밀번호 변경·이메일 재설정 공통), 카운트 4인 계정이 재설정 직후 1회 오타로 즉시 잠기는 동작을 방지한다. LOCKED 계정은 재설정 자격이 없어(`PasswordResetService.isEligible()`: ACTIVE/PASSWORD_EXPIRED만) 잠금 우회 경로는 생기지 않는다.
  - 로그인 폼 파라미터명 `username` 실측 확인 (`login.html:54`) + 핸들러에서 null/blank 가드 명시.
- v1: 최초 작성 (2026-07-12).

## 목표

비밀번호를 **연속 5회** 틀린 계정을 자동으로 `MemberStatus.LOCKED`로 전이시켜 무차별 대입 공격을 차단한다.
현재 `LOCKED` 상태값과 로그인 거부 로직(`CustomUserDetailsService.validateMemberStatus()` — `LockedException` 발생)은 존재하지만, **LOCKED로 전이시키는 로직이 없다** (CLAUDE.md "핵심 도메인 모델 > Member"에 미구현으로 명시).

## ⚠️ 착수 전 필수 확인 (로그인 정책 변경 승인)

CLAUDE.md 보안 규칙상 로그인 정책 변경은 사전 협의가 필요하다. 구현 시작 전에 아래 정책을 사용자에게 제시하고 승인받아야 한다:

- 연속 실패 임계값: **5회** (5회째 실패 시 잠금)
- 성공 로그인 시 실패 카운트 **0으로 리셋**
- 잠금 해제 (v4 확정, 2026-07-14 사용자 결정): **30분 자동 해제** (자동 잠금에 한함 — lazy 조건부 UPDATE) + 다른 ADMIN이 `PATCH /admin/api/members/{id}`로 즉시 해제 가능. PATCH로 **수동 잠금**한 계정은 자동 해제되지 않는다(영구).
- **최후 활성 ADMIN 예외 없음**: 마지막 ADMIN 계정도 잠긴다 (예외를 두면 그 계정만 무한 대입 가능해 보호 의미가 사라짐). 30분 후 자동 복구되며, 비상 시 DB 직접 복구는 엣지 케이스 7 참조.

## 설계 결정 (구현 시 그대로 따를 것)

| 항목 | 결정 |
|------|------|
| 새 컬럼 | `member.failed_login_count INT NOT NULL DEFAULT 0` + `member.locked_at DATETIME(6) NULL` (v4 — 자동 잠금 시각, 수동 잠금은 null 유지) |
| 자동 해제 (v4, v17·v18 교정) | 로그인 시도 시 lazy 조건부 UPDATE — `LoginFailureService.unlockIfLockExpired(userId)` (**호출자 트랜잭션에 참여하는 단일 REQUIRED** — REQUIRES_NEW는 이중 커넥션 풀 고갈 위험으로 폐기), `status=LOCKED and locked_at <= now-30분`일 때만 ACTIVE·카운트 0·locked_at null. 잠금 기간 상수 `public static final Duration LOCK_DURATION = Duration.ofMinutes(30)`. 재설정 경로는 `Member.releaseExpiredAutoLock(cutoff, now)`로 동일 계약 (v17) |
| 카운트 증가 방식 | JPQL 벌크 UPDATE (`SET m.failedLoginCount = m.failedLoginCount + 1`)로 **DB에서 원자적 증가** — 엔티티 조회 후 +1 저장은 동시 실패 시 lost update 발생. **`status = ACTIVE` 조건 포함** (v3) — 상태 변경과 경합해도 비ACTIVE 계정에 카운트가 숨어 누적되지 않음 |
| 증가 트리거 | `AuthenticationFailureHandler` 커스텀 구현 (`BadCredentialsException`일 때만 카운트) |
| 리셋 트리거 | 기존 `VisitLoggingAuthenticationSuccessHandler.onAuthenticationSuccess()`에서 호출 |
| 트랜잭션 | 실패 핸들러의 카운트 증가는 별도 `@Transactional` 서비스 메서드 (Security 필터 단계는 트랜잭션 밖) |
| 잠금 시각 | (v4 교체) `locked_at` 컬럼에 기록 — 자동 해제 판정 기준. `update_date`도 함께 갱신 |
| 비밀번호 변경 시 카운트 | `Member.changePassword()`에서 `failedLoginCount = 0` 리셋 (v2) — 모든 비번 변경 경로(내 비번 변경·이메일 재설정)에서 실패 연쇄 단절. reset 토큰 클리어와 같은 자리라 일관적. (v10) **`lockedAt`은 보존** — LOCKED 경합/세션 생존 중 변경이 영구 잠금으로 변질되는 것 방지. 내 비밀번호 변경 조회는 `findByIdForUpdate`로 직렬화 |
| 감사 로그 | (v3 도입, v8 교체) 잠금 전이 시 `AdminAccountAutoLockEvent` 발행 → **AFTER_COMMIT 리스너**가 `AdminActionLogService.log()`로 `AdminActionLog` 기록(대상 member id·IP·URI, actionUserId는 null — 미인증 흐름) + `log.warn`. 커밋 후 실행이라 원 트랜잭션 롤백 시 감사 미생성·감사 실패 시 잠금 불변 — 양방향 격리. `@AdminActionLogged` AOP는 인증 컨텍스트 전제라 부적합 |
| 잠금 시 기존 세션 | (v3 추가) 잠금 전이 성공 시 `AdminSessionRevokeEvent(memberId)` 발행 — 커밋 후 AFTER_COMMIT 리스너가 세션 만료(best-effort). 트레이드오프: 5회 실패만으로 로그인 중인 관리자를 강제 로그아웃 가능 — LOCKED 의미 일관성 우선으로 수용 |
| "연속"의 선형화 기준 | (v3 추가, v7 보강, v9 한정) 동시 성공·실패의 최종 카운트는 DB 반영(행 잠금 획득) 순서를 따른다. 성공 리셋의 best-effort는 **0행(경합으로 이미 잠김) 허용**만 의미 — 리셋+재확인 메서드의 **예외는 인증 거부(fail-closed)**. 성공 핸들러가 리셋 후 fresh 상태를 재확인해 비ACTIVE면 신규 세션을 스스로 무효화(R4#1) — 잠긴 계정의 세션 생존 차단 |

## 수정해야 할 정확한 파일

### 신규 생성
| 파일 | 내용 |
|------|------|
| `src/main/resources/db/migration/V4__add_member_login_lockout.sql` | `failed_login_count`·`locked_at` 컬럼 추가 (아래 SQL 참고, v4에서 파일명·컬럼 확장). **작성 전 `src/main/resources/db/migration/`에서 현재 최대 버전을 확인하고 그 다음 번호를 쓴다** — 다른 계획이 먼저 머지되어 V4가 선점됐다면 V5로 |
| `src/main/java/com/cms/config/auth/LoginFailureService.java` | 실패 카운트 증가·잠금 전이 트랜잭션 서비스 |
| `src/main/java/com/cms/config/auth/AdminAccountAutoLockEvent.java` | (v8 추가) 자동 잠금 전이 이벤트 (memberId, userId, requestIp, requestUri) |
| `src/main/java/com/cms/config/auth/AdminAccountAutoLockListener.java` | (v8 추가, v19 보강) AFTER_COMMIT 리스너 — 감사 기록(`AdminActionLogService.log()`) + `log.warn`, 내부 try-catch 격리. `@Order`로 `AdminSessionRevokeListener`보다 **나중에** 실행 보장 (세션 만료가 감사 지연에 막히지 않도록 — 기존 리스너에도 `@Order` 부여) |
| `src/main/java/com/cms/config/auth/LockingAuthenticationFailureHandler.java` | `SimpleUrlAuthenticationFailureHandler` 상속 커스텀 실패 핸들러 |
| `src/test/java/com/cms/config/auth/LoginFailureServiceTest.java` | 서비스 단위 테스트 |
| `src/test/java/com/cms/config/auth/LockingAuthenticationFailureHandlerTest.java` | 핸들러 단위 테스트 |
| `src/test/java/com/cms/config/auth/AdminAccountAutoLockListenerTest.java` | (v9 추가) 잠금 감사 리스너 테스트 — AFTER_COMMIT 애너테이션·예외 격리 검증 |
| `src/test/java/com/cms/config/auth/LoginFailureLockoutIntegrationTest.java` | (v12 추가) `@SpringBootTest` + `@AutoConfigureMockMvc` — formLogin 실배선 검증(5회 실패 잠금·만료 자동 해제·수동 잠금 거부) + 감사 실커밋/롤백 검증. 고유 식별자 + `@AfterEach` 대상 한정 정리 — 회원·`AdminActionLog`에 더해 성공 로그인이 남기는 `VisitLog`(고유 `visitorUserId`)·`SessionRegistry` 세션까지 (v16) |
| `src/test/java/com/cms/config/auth/LoginFailureConcurrencyIntegrationTest.java` | (v13 추가) `@SpringBootTest`, 테스트 관리 트랜잭션 없음 — N개 스레드 동시 `recordFailure` 경합 검증, 고유 회원 수동 정리 (`@DataJpaTest`에선 미커밋 데이터가 자식 스레드에 안 보임) + AFTER_COMMIT 리스너가 남기는 자동 잠금 `AdminActionLog` 대상 한정 삭제 (v16) |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/cms/admin/member/domain/Member.java` | `@DynamicUpdate` 부착 (v6 — 더티체킹 경합의 잠금 소실 차단) + 필드 `private int failedLoginCount;` (`@Column(name = "failed_login_count", nullable = false)`, `@Builder.Default`로 0) + `private LocalDateTime lockedAt;` (v4) + 도메인 메서드 `resetFailedLoginCount()`(카운트 0 + `lockedAt` null — v4) + `changePassword()`가 `failedLoginCount = 0`만 리셋 — `lockedAt` 보존 (v2·v10) + `changeStatus()`가 `lockedAt = null` 정리 (v6) + `releaseExpiredAutoLock(cutoff, now)` — 만료된 자동 잠금 해제 도메인 메서드, `updateDate = now`도 갱신 (v17·v18, 재설정 경로용) |
| `src/main/java/com/cms/admin/log/constant/AdminActionTypes.java` | (v6 추가, v7 경로 교정) 계정 자동 잠금 action type 상수 추가 (`AdminActionTypeSyncTest` 규약 준수) |
| `src/main/resources/templates/admin/log/manage.html` | (v6 추가) 새 action type의 한국어 라벨을 라벨 맵에 등록 (`AdminActionTypeLabelSyncTest`가 강제) |
| `src/main/java/com/cms/config/auth/CustomUserDetailsService.java` | (v4 추가, v17 변경) `loadUserByUsername()`의 `@Transactional(readOnly = true)`를 **쓰기 가능 `@Transactional`**로 변경, 진입부에서 회원 조회 **전에** `loginFailureService.unlockIfLockExpired(userId)` 호출(같은 트랜잭션 참여 — 커넥션 1개) — 만료된 자동 잠금을 해제한 뒤 조회하므로 이후 상태 검증이 그대로 동작 |
| `src/main/java/com/cms/admin/member/service/PasswordResetService.java` | (v17 추가, v18 정합) 발급·토큰 확인 진입점에서 `isEligible()` 전에 `member.releaseExpiredAutoLock(cutoff, now)` 호출 — 만료된 자동 잠금은 재설정 경로에서도 해제 (기존 행 잠금 안, 수동 잠금은 여전히 부적격) |
| `src/main/java/com/cms/admin/member/repository/MemberRepository.java` | 벌크 증가 쿼리 + 조건부 잠금 쿼리 추가 (아래 단계 4 참고) |
| `src/main/java/com/cms/config/SecurityConfig.java` | 60행 `failureUrl("/admin/login-error")`를 `failureHandler(lockingAuthenticationFailureHandler)`로 교체 (핸들러를 `filterChain` 파라미터로 주입) |
| `src/main/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandler.java` | (v7 확장, v8 보강, v18 확장) 성공 핸들러 **첫 작업**으로 `LoginFailureService.resetFailuresAndCheckActive(userId)` 호출 — 조건부 리셋 + fresh 상태·역할 재확인. 비ACTIVE **또는 fresh 역할이 `Authentication` 권한과 불일치 또는 예외** 시 세션 무효화 + `SecurityContext` 클리어 + `/admin/login-error` 리다이렉트, 방문 로그·성공 리다이렉트 생략 (fail-closed — 재확인은 인증 결정) |
| `src/main/java/com/cms/admin/member/service/AdminMemberService.java` | `updateAdminMember()`에서 상태가 **비ACTIVE → ACTIVE로 실변경**될 때 `member.resetFailedLoginCount()` 호출 (v7 — 단계 9와 표현 통일. 잠금 해제 직후 1회 실패로 재잠금되는 것 방지) + (v10) 내 비밀번호 변경 메서드의 회원 조회를 `findByIdForUpdate`로 전환 — 잠금 벌크 UPDATE와 직렬화 + (v21) 내 비밀번호 변경 성공 시 `AdminSessionRevokeEvent(memberId)` 발행 — 전 세션 폐기 (본인 세션 포함, 재로그인 필요) |
| `src/test/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandlerTest.java` | (v10 추가, v20 교정, v22 확장) 생성자 의존성(`LoginFailureService`) 추가 반영 — 공통 설정에서 `resetFailuresAndCheckActive()`를 **"ACTIVE + 일치 역할·해시 스냅샷"** 반환으로 스텁(Mockito 기본 empty로 기존 성공 테스트가 거부 분기로 오염되는 것 방지) + `authWith()`가 실제 `CustomUserDetails`(인증 당시 해시 보유)를 principal로 제공하도록 변경, 스냅샷 해시와 일치시킴. 예상 밖 principal 타입·해시 부재 fail-closed 케이스 추가 |
| `src/test/java/com/cms/config/SecurityConfigTest.java` | (v11 추가, v20 교정) 성공 핸들러 생성 시 `LoginFailureService` mock 전달 + 일치 스냅샷 스텁, `LockingAuthenticationFailureHandler` 빈 제공 |
| `src/test/java/com/cms/config/ApiSecurityConfigTest.java` | (v11 추가) 동일 — 생성자·필터체인 의존성 보정 |
| `src/test/java/com/cms/admin/member/controller/PasswordResetControllerTest.java` | (v11 추가) 동일 — 생성자·필터체인 의존성 보정 |
| `src/main/java/com/cms/config/auth/AdminSessionRevokeListener.java` | (v19 추가) `@Order` 부여 — 감사 리스너보다 먼저 실행 보장 (기능 변경 없음) |
| `src/main/java/com/cms/config/auth/CustomUserDetails.java` | (v20 추가, 필요 시) 성공 핸들러의 해시 비교용으로 인증에 사용된 해시를 보존 — `eraseCredentials` 소거 대상 여부 확인 후, 소거된다면 비교용 필드 추가 |
| `src/test/java/com/cms/admin/member/service/AdminMemberServiceTest.java` | (v12 추가) 비밀번호 변경 테스트 4건의 `findById` 스텁·`never()` 검증을 `findByIdForUpdate` 기준으로 교체 + 비ACTIVE→ACTIVE 카운트 리셋·LOCKED 중 비번 변경 케이스 추가 |
| `src/test/java/com/cms/admin/member/service/PasswordResetServiceTest.java` | (v15 추가, v17·v18 확장) 재설정 성공 시 `failedLoginCount = 0` 리셋(+`lockedAt` 보존) 검증 + 만료 자동 잠금 계정의 발급·확인 가능 / 미만료·수동 잠금 부적격 유지 / 해제 후 쿨다운 조기 반환 시에도 상태·`updateDate` 동시 갱신 검증 |
| `docs/troubleshooting.md` | (v15 추가) 최후 ADMIN 잠금 복구 절차 기록 ("애플리케이션 / 런타임" 카테고리) |

## 단계별 작업 순서

1. **브랜치 생성**: `git checkout -b feat/login-failure-lockout`
2. **Flyway 마이그레이션 작성** (`V4__add_member_login_lockout.sql`, v4 확장):
   ```sql
   -- 로그인 연속 실패 카운트 (5회 도달 시 LOCKED 자동 전이)
   -- locked_at: 자동 잠금 시각 (30분 후 자동 해제 판정 기준). 수동 잠금(PATCH)은 null 유지 → 자동 해제 대상 아님
   ALTER TABLE `member`
     ADD COLUMN `failed_login_count` INT NOT NULL DEFAULT 0,
     ADD COLUMN `locked_at` DATETIME(6) DEFAULT NULL;
   ```
   `ddl-auto: validate`이므로 엔티티 필드와 컬럼 정의가 정확히 일치해야 기동된다.
3. **Member 엔티티 수정**: `@DynamicUpdate` 부착 (v6) + 필드 2개(`failedLoginCount`, `lockedAt`) + `resetFailedLoginCount()` (카운트 0 + `lockedAt` null — v4, `updateDate`는 갱신하지 않음 — 로그인 성공마다 갱신되면 노이즈) + `changeStatus()`에 `this.lockedAt = null;` 추가 (v6 — 수동 상태 변경은 자동 잠금 시각을 항상 정리) + `changePassword()`에서 `this.failedLoginCount = 0;`만 추가 — `lockedAt` 보존 (v2·v10 — LOCKED 경합 시 영구 잠금 변질 방지).
4. **MemberRepository에 쿼리 4개 추가** (v7 교정 — 증가·잠금·성공 리셋·자동 해제):
   ```java
   /** ACTIVE인 관리자(ADMIN/MANAGER) 계정의 로그인 실패 카운트를 DB에서 원자적으로 1 증가시킨다.
       상태 조건으로 비ACTIVE 계정의 숨은 누적을(v3), 역할 조건으로 ROLE_USER 오잠금을(v15) 차단한다. */
   @Modifying(clearAutomatically = true)
   @Query("update Member m set m.failedLoginCount = m.failedLoginCount + 1 " +
          "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE " +
          "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
   int increaseFailedLoginCount(@Param("userId") String userId);

   /** 실패 카운트가 임계값 이상인 ACTIVE 계정을 LOCKED로 전이하고 잠금 시각을 기록. 조건부 UPDATE로 동시 실패에도 멱등.
       잠금 시각은 앱 시계(:now)로 기록 — 만료 cutoff와 동일한 시간 기준 (v5). */
   @Modifying(clearAutomatically = true)
   @Query("update Member m set m.status = com.cms.admin.member.domain.MemberStatus.LOCKED, " +
          "m.lockedAt = :now, m.updateDate = :now " +
          "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE and m.failedLoginCount >= :threshold " +
          "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
   int lockIfThresholdReached(@Param("userId") String userId, @Param("threshold") int threshold, @Param("now") LocalDateTime now);

   /** (v5) 성공 로그인 시 실패 카운트 리셋 — ACTIVE 조건부 벌크 UPDATE.
       더티체킹 전체 컬럼 UPDATE는 경합 시 LOCKED를 되살릴 수 있어 금지. 잠금 후 리셋은 0행 no-op. */
   @Modifying(clearAutomatically = true)
   @Query("update Member m set m.failedLoginCount = 0, m.lockedAt = null " +
          "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE and m.failedLoginCount > 0 " +
          "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
   int resetFailedLoginCountIfActive(@Param("userId") String userId);

   /** (v4) 자동 잠금이 만료된 계정을 해제한다. 수동 잠금(locked_at null)은 대상이 아니며, 조건부 UPDATE라 동시 로그인 경합에도 멱등. */
   @Modifying(clearAutomatically = true)
   @Query("update Member m set m.status = com.cms.admin.member.domain.MemberStatus.ACTIVE, " +
          "m.failedLoginCount = 0, m.lockedAt = null, m.updateDate = :now " +
          "where m.userId = :userId and m.status = com.cms.admin.member.domain.MemberStatus.LOCKED " +
          "and m.lockedAt is not null and m.lockedAt <= :cutoff " +
          "and m.userType in (com.cms.admin.member.domain.Role.ROLE_ADMIN, com.cms.admin.member.domain.Role.ROLE_MANAGER)")
   int unlockIfLockExpired(@Param("userId") String userId, @Param("cutoff") LocalDateTime cutoff, @Param("now") LocalDateTime now);
   ```
5. **LoginFailureService 작성**: `@Component` + `@RequiredArgsConstructor` (의존: `MemberRepository`, `ApplicationEventPublisher`, `Clock` — v9: 감사는 리스너 몫이므로 `AdminActionLogService` 의존 없음. v13: 기존 KST `Clock` 빈(`AppConfig`) 주입, 잠금 시각·cutoff 모두 `LocalDateTime.now(clock)`).
   - `private static final int LOCK_THRESHOLD = 5;` + `public static final Duration LOCK_DURATION = Duration.ofMinutes(30);` (v4, v18 — public 하나로 통일: `PasswordResetService`가 공유)
   - (v4, v17 변경, v23 교정) `@Transactional public boolean unlockIfLockExpired(String userId)`: `LocalDateTime now = LocalDateTime.now(clock);` 후 `unlockIfLockExpired(userId, now.minus(LOCK_DURATION), now)` 벌크 UPDATE (3인자 — `updateDate = :now` 포함), 1행이면 `log.info("자동 잠금 만료 해제 (userId={})", userId)` 후 true. 호출자(`loadUserByUsername` — v17에서 쓰기 가능 트랜잭션으로 변경)와 **같은 트랜잭션에 참여**(REQUIRED) — REQUIRES_NEW의 이중 커넥션 구조는 풀 고갈 위험으로 폐기.
   - (v17) `public static final Duration LOCK_DURATION` — `PasswordResetService`의 재설정 경로 lazy 해제와 공유.
   - `@Transactional public void recordFailure(String userId, String requestIp, String requestUri)` (IP·URI는 핸들러에서 추출·**컬럼 길이로 절단**해 전달 — v5): `increaseFailedLoginCount(userId)` 호출 (0행이면 미존재 또는 비ACTIVE 계정 — 그냥 return). 이어서 `lockIfThresholdReached(userId, LOCK_THRESHOLD, now)`가 1을 반환하면:
     - 같은 트랜잭션에서 `findByUserId(userId)` 재조회로 memberId 획득 (v5 — `clearAutomatically = true`로 1차 캐시가 비워져 fresh. 이론상 조회 실패 시 아래 두 동작 생략 + `log.error`)
     - `AdminAccountAutoLockEvent(memberId, userId, requestIp, requestUri)` 발행 (v8 — AFTER_COMMIT 리스너가 감사 기록 + `log.warn` 수행. 트랜잭션 내 직접 감사 호출은 롤백 시에도 감사가 남는 문제로 폐기. action type 상수는 `AdminActionType*SyncTest` 규약 확인 후 추가)
     - `AdminSessionRevokeEvent(memberId)` 발행 (v3 — 커밋 후 기존 세션 만료, best-effort)
   - `@Transactional public Optional<...> resetFailuresAndCheckActive(String userId)`: (v5 교체, v7 확장, v18 확장) `resetFailedLoginCountIfActive(userId)` 조건부 벌크 UPDATE — 더티체킹 저장 금지(경합 시 LOCKED 훼손) — 후 같은 트랜잭션에서 **fresh 상태·역할 재조회**(`clearAutomatically`로 1차 캐시 클리어됨), `status`·`userType`·`pwd`(해시)를 반환(구현 형태는 status·role·해시 스냅샷 — 성공 핸들러가 ACTIVE 여부, `Authentication` 권한 일치, 인증에 사용된 해시 일치를 함께 판정 — v18·v20). (v9 — fail-closed 통일) 예외는 성공 핸들러에서 `log.error` 후 **로그인 거부**. "best-effort"는 리셋 결과 0행(경합으로 이미 잠김)을 허용한다는 의미로 한정 — 메서드 예외는 인증 거부다.
6. **LockingAuthenticationFailureHandler 작성**:
   - `SimpleUrlAuthenticationFailureHandler`를 상속하고 생성자(또는 `@PostConstruct`)에서 `setDefaultFailureUrl("/admin/login-error")`로 기존 리다이렉트 동작 보존.
   - `onAuthenticationFailure()` 오버라이드: `exception instanceof BadCredentialsException`일 때만 `loginFailureService.recordFailure(username, requestIp, requestUri)` 3인자 호출 (v6 정합 — `username = request.getParameter("username")`, 파라미터명은 `login.html:54` 실측 확인, null/blank면 호출 생략. IP는 `VisitLoggingAuthenticationSuccessHandler.extractClientIp()`와 동일 로직으로 추출하고 IP·URI 모두 컬럼 길이로 **절단**해 전달 — 핸들러 책임). `BadCredentialsException` 이외의 모든 인증 실패는 카운트하지 않는다 (v14 — 상태 기반 거부는 `InternalAuthenticationServiceException`으로 래핑되어 도달하지만 화이트리스트 조건이라 무관). 호출은 try-catch로 격리(카운트 실패가 에러 페이지 리다이렉트를 막으면 안 됨). 마지막에 `super.onAuthenticationFailure(...)` 호출.
   - `@Component` 등록.
7. **SecurityConfig 수정**: `filterChain(...)` 파라미터에 `LockingAuthenticationFailureHandler failureHandler` 추가, `.failureUrl("/admin/login-error")` → `.failureHandler(failureHandler)` 교체. **다른 formLogin 설정은 그대로 유지.**
8. **성공 핸들러 수정** (v7 확장, v8 보강): `VisitLoggingAuthenticationSuccessHandler`에 `LoginFailureService` 주입. `onAuthenticationSuccess()`의 **첫 작업**으로 `resetFailuresAndCheckActive(authentication.getName())`를 호출한다 (v8 — 방문 로그보다 먼저: 거부될 인증이 성공 방문으로 기록되면 안 됨).
   - **비ACTIVE, fresh 역할이 `Authentication` 권한과 불일치(v18 — 인증 중 역할 변경 경합), fresh 해시가 인증에 사용된 해시와 불일치(v20 — 인증 중 비밀번호 변경 경합), 또는 예외 발생 시** (v8 — fail-closed): `request.getSession(false)` 무효화 + `SecurityContextHolder.clearContext()` + `/admin/login-error` 리다이렉트하고 방문 로그·`super.onAuthenticationSuccess()`를 수행하지 않는다 — 인증 완료 직전에 잠긴/강등된 계정의 낡은 세션 생존 차단 (R4#1·R15#1. 세션 등록이 이 핸들러보다 먼저 일어나므로, 여기서 못 잡은 인터리빙은 AFTER_COMMIT 리스너가 등록된 세션을 만료한다). 재확인은 부가 기능이 아니라 인증 결정이므로 `tryLogVisit()`식 fail-open 격리를 적용하지 않는다 (예외는 `log.error`로 추적). 역할 불일치 거부 후 재로그인하면 새 권한으로 정상 로그인된다.
   - ACTIVE 확인 후에만 기존 방문 로그(`tryLogVisit`) → 기본 성공 처리 순서로 진행.
9. **AdminMemberService 수정**: `updateAdminMember()`의 상태 변경 처리 분기에서, **비ACTIVE 상태에서 `ACTIVE`로 실변경**될 때 `member.resetFailedLoginCount()`를 함께 호출 (v3 — LOCKED뿐 아니라 DISABLED→ACTIVE 복구도 실패 연쇄 단절. 상태 전이 경합으로 비ACTIVE 계정에 카운트가 남았을 가능성 방어. v4 — 이 메서드가 `lockedAt`도 함께 클리어).
9-1. **CustomUserDetailsService 수정** (v4, v17·v18 교정): `@Transactional(readOnly = true)`를 쓰기 가능 `@Transactional`로 변경. `loadUserByUsername()` 진입부, 회원 조회 전에 `loginFailureService.unlockIfLockExpired(userId)` 호출 — **같은 트랜잭션에서 해제 후 fresh 조회**하고 메서드 반환 시 커밋된다(비밀번호 검증 전이므로 틀린 비밀번호여도 해제 유지). 기존 `validateMemberStatus()`는 그대로 동작한다.
10. **테스트 작성**:
    - `LoginFailureServiceTest`: 4회 실패 시 ACTIVE 유지, 5회째 LOCKED 전이, 미존재 userId 무동작, 리셋 동작. (`@DataJpaTest` + 실제 쿼리 검증 — 벌크 UPDATE는 mock으로 의미 있는 검증이 안 됨. 기존 `VisitLogRepositoryDataJpaTest` 패턴(`@AutoConfigureTestDatabase(replace = NONE)` + `@ActiveProfiles("dev")`, 로컬 MariaDB 필요)을 따른다. v2에서 참조 파일 교정.)
    - `LoginFailureServiceTest`는 `@Import({LoginFailureService.class, QuerydslConfig.class})` + **고정 `Clock` 중첩 `@TestConfiguration`** (v14 — `@DataJpaTest`는 `AppConfig`를 스캔하지 않아 `Clock` 빈 부재로 기동 실패) 필요 (v3 — `@DataJpaTest`는 `@Component`를 자동 등록하지 않음). (v15) `ROLE_USER` 계정은 5회 실패해도 상태·카운트·이벤트 불변 케이스 포함. 서비스가 `AdminAccountAutoLockEvent`·`AdminSessionRevokeEvent`를 발행하는지 `@RecordApplicationEvents`(또는 publisher mock)로 검증 (v9 — 감사 서비스 의존이 없어져 mock 불필요). 비ACTIVE 계정 증가 무시 케이스 포함.
    - (v5, v9 이동, v13 확장) **감사 로그 예외 격리·필드 매핑 테스트**: `AdminAccountAutoLockListenerTest`에서 검증 — `AdminActionLogService.log()`가 예외를 던져도 리스너가 삼키고 전파하지 않는다 (`AdminSessionRevokeListenerTest` 선례 미러, AFTER_COMMIT 애너테이션 검증 포함) + 감사 필드 매핑 단언 (`actionId=null`, `actionUserId=null`, `actionResult=SUCCESS`, `targetType="MEMBER"`, `targetId=memberId`, `requestMethod="POST"`, 절단된 IP·URI, `errorMessage=null`).
    - (v5) **리셋-잠금 경합 순차 테스트**: LOCKED 계정에 `resetFailures()` 호출 → 0행 no-op, 상태·`locked_at` 보존 (더티체킹이었다면 훼손됐을 케이스).
    - (v6, v10·v11 방식 교정) **더티체킹 잠금 보존 테스트**: 엔티티 로드 → **같은 물리 트랜잭션에 참여하는 네이티브 UPDATE**(`entityManager.createNativeQuery` 또는 트랜잭션 참여 `JdbcTemplate` — 독립 트랜잭션은 REPEATABLE READ 스냅샷으로 거짓 실패)로 DB만 LOCKED·`locked_at` 세팅(원본 엔티티 managed 유지 — 벌크 쿼리의 `clearAutomatically`는 엔티티를 detach시켜 vacuous pass) → 로드해둔 엔티티의 `updateInfo()` 후 flush(**UPDATE 실행 사실 확인**) → clear 후 재조회 시 LOCKED·`locked_at` 보존 (`@DynamicUpdate` 검증).
    - (v10) **LOCKED 중 비밀번호 변경 테스트**: LOCKED + `lockedAt=T` 상태에서 `changePassword()` 호출 → 카운트 0, `lockedAt=T` 보존(영구 잠금 변질 없음).
    - (v6) **수동 잠금 영구성 회귀 테스트**: 자동 잠금(LOCKED, `locked_at=T`) → PATCH DISABLED → PATCH LOCKED 후 `locked_at`이 null(영구 잠금)임을 검증.
    - 핸들러 테스트에서 `recordFailure(username, ip, uri)` 전달값(절단 포함)까지 검증 (v6).
    - (v7, v8 변경, v18·v20 확장) **성공 핸들러 잠금·역할·해시 재확인 테스트**: 비ACTIVE **또는 역할 불일치(ADMIN→MANAGER 강등 포함) 또는 해시 불일치(인증 중 비밀번호 변경) 또는 예외** 시 세션 무효화 + `SecurityContext` 클리어 + `/admin/login-error` 리다이렉트, 기본 성공 리다이렉트·방문 로그(`VisitLog`) 미수행 (fail-closed).
    - (v8, v9 구체화, v12 이동·정리) **감사-잠금 원자성 테스트**: `LoginFailureLockoutIntegrationTest`에서 실제 publisher·listener·repository 구성으로 `TransactionTemplate`(또는 `TestTransaction`)로 경계를 제어해 — 커밋 시 감사 로그(`AdminActionLog`) **정확히 1건**, 강제 롤백 시 **0건** 검증 (기본 롤백 의존 금지 — vacuous pass 방지). 실커밋 데이터는 고유 식별자로 생성하고 `@AfterEach`에서 해당 회원·감사 로그만 삭제(공유 dev DB 잔류 방지, `deleteAll()` 금지).
    - 핸들러 테스트: `BadCredentialsException` → `recordFailure` 호출됨, `LockedException`·`InternalAuthenticationServiceException`(v14 — 실배선에서 상태 예외가 래핑되어 도달하는 실제 타입) → 호출 안 됨, 서비스 예외 발생해도 리다이렉트 정상 (mock).
    - (v3, v9 교정, v13 분리) **동시 실패 경합 테스트** — `LoginFailureConcurrencyIntegrationTest`(`@SpringBootTest`, 테스트 관리 트랜잭션 없음, 준비 데이터 실커밋 + 고유 회원 수동 정리): N개 스레드 동시 `recordFailure` → 최종 카운트는 **`min(N, 5)`** (증가 쿼리의 `ACTIVE` 조건 때문에 잠금 커밋 후 증가는 0행), `N >= 5`면 LOCKED 전이와 잠금·세션 폐기 이벤트가 각각 **정확히 1회** 검증.
    - (v3, v12 소유권 지정) **MockMvc formLogin 통합 테스트** — `LoginFailureLockoutIntegrationTest`(`@SpringBootTest` + `@AutoConfigureMockMvc`, 신규): `SecurityConfig`에 핸들러가 실제 배선됐는지 — 틀린 비밀번호 5회 POST `/admin/login` → 6번째 올바른 비밀번호도 거부(LOCKED) 검증. (v5 추가) 만료된 자동 잠금(`locked_at` 31분 전) + 올바른 비밀번호 → 로그인 성공 / 수동 잠금(`locked_at` null) + 올바른 비밀번호 → 계속 거부 **+ `failedLoginCount` 불변 단언**(v14). 고유 테스트 식별자 + `@AfterEach` 대상 한정 정리(v12).
    - (v3) `changePassword()` 카운트 리셋을 양쪽 경로에서 검증: `AdminMemberServiceTest`(내 비밀번호 변경) + `PasswordResetServiceTest`(이메일 재설정).
    - 기존 `AdminMemberServiceTest`에 비ACTIVE→ACTIVE 해제 시 카운트 리셋 케이스 추가 (LOCKED→ACTIVE, DISABLED→ACTIVE).
    - (v4, v13 경계 확장) **자동 해제 테스트**: 고정 `Clock`으로 경계 3종 — cutoff 직전(해제 안 됨) / 정확히 cutoff(해제됨 — `<=` 계약) / cutoff 직후(해제됨) → 해제 시 ACTIVE·카운트 0·`locked_at` null / `locked_at` null(수동 잠금)이면 해제 안 됨.
11. **검증**: `./gradlew test`. 로컬 DB 기동(`make dev-db`) 후 앱 실행, 틀린 비밀번호 5회 → 6번째에 올바른 비밀번호를 넣어도 "잠긴 계정" 거부되는지 playwright로 확인. 다른 ADMIN으로 로그인해 대상 계정을 ACTIVE로 변경 → 재로그인 성공 확인. (v3) **빈 DB 기동 검증**: dev DB에 임시 스키마를 만들어(`docker exec cms-db-dev mariadb ... "CREATE DATABASE cms_fresh"`) `DB_URL`을 그 스키마로 오버라이드해 `bootRun` — Flyway V1→V4 전체 적용 + `ddl-auto: validate` 통과 확인 후 스키마 삭제.
12. **커밋·PR 생성** (한국어 커밋 메시지).

## 엣지 케이스

1. **동시 로그인 실패 (같은 계정, 여러 요청)**: 벌크 UPDATE의 원자적 증가 + 조건부 잠금 쿼리로 lost update 없음. (v9 교정) 증가 쿼리의 `ACTIVE` 조건 때문에 5회째 잠금 커밋 이후의 증가는 0행 — 카운트는 5에서 멈추고, 잠금 커밋 **전** 경합으로 5를 넘더라도 잠금 쿼리는 `>= threshold` 조건이라 정상 동작.
2. **존재하지 않는 userId로 실패**: `increaseFailedLoginCount`가 0행 반환 — 아무것도 하지 않는다. (v3: 존재/미존재 계정의 SQL 횟수가 달라 응답 시간 기반 계정 열거를 막지는 못한다 — 열거 완화(요청 제한 등)는 이번 범위 밖.)
3. **이미 LOCKED/DISABLED 계정으로 실패**: (v14 교정) `CustomUserDetailsService`가 던진 `LockedException`/`DisabledException`은 `DaoAuthenticationProvider.retrieveUser()`에서 **`InternalAuthenticationServiceException`으로 래핑**되어 핸들러에 도달한다 — 어느 쪽이든 `BadCredentialsException`이 아니므로 카운트가 증가하지 않는다 (조건은 화이트리스트 방식이라 래핑 여부와 무관하게 안전). 단, Spring Security 기본 설정(`hideUserNotFoundExceptions`)상 `UsernameNotFoundException`은 `BadCredentialsException`으로 변환되어 도달함 — 케이스 2로 처리됨.
4. **4회 실패 후 성공**: 카운트 0 리셋. 리셋은 "연속" 실패 정의의 핵심.
5. **관리자가 잠금 해제(ACTIVE 변경)한 직후**: 카운트가 리셋되어 있어야 함 (단계 9). 리셋이 없으면 다음 1회 실패에 즉시 재잠금된다.
6. **PATCH로 LOCKED가 아닌 상태(예: DISABLED)에서 ACTIVE로 변경**: (v3 변경) 카운트 리셋 **적용** — 비ACTIVE→ACTIVE 실변경 전체에 리셋. 상태 전이 경합으로 DISABLED 계정에 카운트가 숨어 있었어도 복구 시 단절된다.
7. **최후 활성 ADMIN이 잠긴 경우**: (v4 완화) 30분 후 자동 해제되므로 대기가 기본 복구 수단. 즉시 복구가 필요하면 DB에서 직접 `UPDATE member SET status='ACTIVE', failed_login_count=0, locked_at=NULL WHERE user_id='...';` 실행. 이 복구 절차를 `docs/troubleshooting.md` "애플리케이션 / 런타임" 카테고리에 기록한다.
8. **세션이 이미 살아있는 계정이 잠긴 경우**: (v3 변경) 잠금 전이 시 `AdminSessionRevokeEvent` 발행으로 기존 세션도 커밋 후 만료된다(best-effort — 기존 계약과 동일). 만료 실패 시 관리자가 PATCH 멱등 재잠금으로 재시도 가능. 트레이드오프: 공격자가 5회 실패만으로 로그인 중인 관리자를 로그아웃시킬 수 있음 — 수용(LOCKED 의미 일관성 우선).
9. **`TestMemberLoader`의 admin 계정**: dev에서 잠겨도 DB 초기화(`make dev-down` 후 재시작) 또는 케이스 7 SQL로 복구 가능.
10. **(v2, v19 정합) 실패 4회 누적 상태에서 비밀번호 변경/재설정**: `changePassword()`가 카운트를 0으로 리셋하므로, 재설정 직후 1회 오타로 즉시 잠기지 않는다. **미만료 자동 잠금·수동 잠금** 계정은 재설정 자격이 없어(`isEligible()`) 이 경로로 잠금을 우회할 수 없다 — 만료된 자동 잠금만 해제 후 허용된다(엣지 13, v17).
11. **(v4·v6) 수동 잠금(PATCH LOCKED) 계정**: 상태 실변경 시 `changeStatus()`가 `lockedAt`을 null로 정리하므로 수동 →LOCKED는 항상 영구 잠금이다. LOCKED(자동)→DISABLED→LOCKED(수동) 회귀 경로에서도 `locked_at` 잔존이 없다 (v6). 단, 자동 잠금 계정에 동일값 LOCKED 멱등 재잠금은 `changeStatus` 미호출이라 `locked_at`이 유지되어 30분 해제가 살아 있다 — 영구 잠금 의도라면 다른 상태를 경유하거나 DISABLED 사용.
12. **(v4) 잠금 만료 직후 동시 로그인 2건**: 둘 다 `unlockIfLockExpired`를 호출하지만 조건부 UPDATE라 한 건만 1행 갱신, 양쪽 모두 이후 조회에서 ACTIVE를 읽어 정상 진행 — 경합 안전.
13-1. **(v21) 해시 재확인 직후 본인 비밀번호 변경 커밋 (TOCTOU)**: 재확인 전 커밋은 해시 불일치 거부(v20), 재확인 후 커밋은 `changeMyPassword()`의 전 세션 폐기(v21)가 잡는다 — 세션 등록이 재확인에 선행하므로 폐기 리스너가 경합 세션을 놓치지 않는다(best-effort 계약 수준).
13. **(v17) 만료된 자동 잠금 상태에서 "비밀번호 찾기"**: 재설정 발급·토큰 확인 진입점이 `releaseExpiredAutoLock()`으로 잠금을 해제한 뒤 자격을 판정하므로 로그인 폼 제출 없이도 재설정이 가능하다. 미만료 자동 잠금·수동 잠금은 여전히 부적격(200 응답이지만 메일 미발송 — 기존 열거 방지 계약 유지).

## 완료 기준

- [x] 잠금 정책(5회/리셋/해제 방식/최후 ADMIN 예외 없음)에 대해 사용자 승인을 받았다 (2026-07-14, 30분 자동 해제 포함).
- [x] Flyway 마이그레이션이 추가되었고 빈 DB에서 `./gradlew bootRun` 기동이 성공한다 (`ddl-auto: validate` 통과).
- [x] `./gradlew test` 전체 통과.
- [x] 틀린 비밀번호 5회 연속 입력 후 올바른 비밀번호로도 로그인이 거부된다 (playwright 확인).
- [x] 4회 실패 후 성공 로그인 → 다시 4회 실패해도 잠기지 않는다 (카운트 리셋 검증, 테스트로 충분).
- [x] 다른 ADMIN이 `PATCH /admin/api/members/{id}`로 ACTIVE 변경 시 즉시 재로그인 가능하고, 이후 1회 실패로 재잠금되지 않는다.
- [x] LOCKED 계정에 대한 로그인 시도는 실패 카운트를 증가시키지 않는다 (테스트로 검증).
- [x] (v3) 잠금 전이 시 대상자의 기존 세션이 만료된다 (`AdminSessionRevokeEvent` 발행 검증).
- [x] (v3) 잠금 전이가 `AdminActionLog`에 기록된다 (감사 로그 검증).
- [x] (v3) 빈 스키마에서 Flyway V1→V4 전체 적용 + `validate` 기동 성공.
- [x] (v4) 자동 잠금 30분 경과 후 로그인 시도 시 자동 해제되어 올바른 비밀번호로 로그인된다 (테스트: `locked_at` 과거 세팅). 수동 잠금(`locked_at` null)은 자동 해제되지 않는다.
- [x] 최후 ADMIN 잠금 복구 절차가 `docs/troubleshooting.md`에 기록되었다.
