# PLAN-password-expiry — 비밀번호 만료(PASSWORD_EXPIRED) 자동 전이

## 목표

비밀번호를 **90일 이상** 변경하지 않은 계정을 로그인 시점에 `MemberStatus.PASSWORD_EXPIRED`로 전이시키고, 만료된 사용자는 비밀번호 재설정 흐름으로 새 비밀번호를 설정한 뒤 `ACTIVE`로 복귀하게 한다.
현재 `PASSWORD_EXPIRED` 상태값과 로그인 거부 로직(`CustomUserDetailsService.validateMemberStatus()` — `CredentialsExpiredException`)은 존재하지만 **전이 로직이 미구현**이다 (CLAUDE.md "핵심 도메인 모델 > Member"에 명시).

## ⚠️ 선행 조건 및 승인

1. ✅ **`PLAN-password-reset.md` 완료** (2026-07-14 · `99359d3` #10). 재설정 흐름에 "`PASSWORD_EXPIRED` 계정 재설정 허용 + 재설정 성공 시 `ACTIVE` 복귀"가 구현되어 있다 — 만료 사용자의 자가 복구 경로 확보됨.
2. **로그인 정책 변경 승인 필요** (CLAUDE.md 보안 규칙): 만료 기간 **90일**, 로그인 시점 검사 방식(스케줄러 없음), 만료 시 재설정 메일 흐름으로 복구 — 이 정책을 사용자에게 제시하고 승인받은 뒤 착수한다.
   - 백필 기준(마이그레이션 시각)·NOT NULL 강제는 2026-07-14 승인 완료 (리뷰 라운드 1 결정).

## 설계 결정 (구현 시 그대로 따를 것)

| 항목 | 결정 |
|------|------|
| 새 컬럼 | `member.password_changed_at DATETIME(6) NOT NULL` — 추가 시 NULL로 생성 → 전체 백필 → `NOT NULL` 강제. **3단계를 마이그레이션 3개(V5/V6/V7)로 분리** — MariaDB `ALTER TABLE`은 암묵 커밋이라 단일 파일은 비원자적이며, 부분 실패 시 "컬럼은 이미 존재 + Flyway 실패 기록" 재실행 불가 함정이 생긴다 (v3, R2#2) |
| 기존 행 백필 | **마이그레이션 실행 시각**(`NOW(6)`)으로 전 행 백필 — 배포일부터 전원 90일 유예. `update_date`는 토큰 발급·잠금 해제 등으로도 갱신되는 오염된 대리값이라 사용하지 않는다 (v2, R1#7). DB 세션이 UTC면 백필 값이 KST보다 최대 9시간 이르지만, 90일 지평에서 무시 가능한 오차로 수용하고 여기 문서화한다 |
| null 처리 | **null 불허** — 엔티티 `@Column(nullable = false)` + DB `NOT NULL`. 모든 생성 경로(`createAdmin`·`TestMemberLoader`·테스트 fixture)가 명시적으로 세팅한다. null fail-open 경로 자체를 제거 (v2, R1#8) |
| 만료 판정 시점 | 로그인 인증 시(`CustomUserDetailsService.loadUserByUsername`) + **성공 처리 직전 재판정** (v8, R7#1): `VisitLoggingAuthenticationSuccessHandler.verifyFreshMemberState()` 첫머리(기존 try 블록 안 — 예외 시 fail-closed)에서 `passwordExpiryService.expireIfPasswordOutdated()`를 한 번 더 호출 — 인증 처리 중(BCrypt 검증 등) 90일 경계를 넘는 TOCTOU를 닫는다. 전이되면 직후 fresh 조회가 `PASSWORD_EXPIRED`를 읽어 기존 `status != ACTIVE` 거부 분기가 그대로 잡는다(새 분기 없음). 배치/스케줄러는 도입하지 않는다 — 로그인하지 않는 계정은 어차피 위험 노출이 없고 복잡도만 늘어남 |
| 상태 영속화 | **기존 로그인 쓰기 트랜잭션에 참여하는 조건부 벌크 UPDATE** (`REQUIRES_NEW` 아님 — v2, R1#1·#2). `loadUserByUsername`은 이미 lazy unlock 벌크 UPDATE를 수행하는 `@Transactional` 쓰기 트랜잭션이며, 주석에 REQUIRES_NEW가 커넥션 풀 고갈 위험으로 배제됐다고 명시돼 있다. 만료 전이도 같은 트랜잭션에서 `UPDATE ... SET status = PASSWORD_EXPIRED WHERE user_id = :userId AND status = 'ACTIVE' AND user_type IN ('ROLE_ADMIN','ROLE_MANAGER') AND password_changed_at <= :cutoff` 벌크 UPDATE로 수행하고, 이후 fresh 조회 → `validateMemberStatus()`의 기존 `PASSWORD_EXPIRED` 분기가 `CredentialsExpiredException`을 던진다. 커밋 유지를 위해 `loadUserByUsername`에 **`@Transactional(noRollbackFor = CredentialsExpiredException.class)`** 지정 (R1#1 — 기본 롤백 규칙상 unchecked 예외는 벌크 UPDATE를 되돌리므로 필수) |
| 만료 대상 | **`ROLE_ADMIN`·`ROLE_MANAGER` allowlist** — 잠금(②)·재설정(①)과 동일 정책 (v4, R3#1). `ROLE_USER`는 재설정 자격이 없어 만료 전이 시 자가 복구 불가 상태에 빠지므로 만료 UPDATE 조건에서 제외한다 (기존 "ROLE_USER 오잠금 방지"와 같은 원리) |
| 상태 경합 보호 | `status = 'ACTIVE'` 조건부 벌크 UPDATE가 잠금(`LOCKED`)·재설정(`ACTIVE` 복귀 + `passwordChangedAt` 갱신) 경합에서 덮어쓰기를 원천 차단 — ②(로그인 실패 잠금)가 확립한 패턴과 동일 (v2, R1#3). 엔티티 재조회 후 `changeStatus()` flush 방식 금지 |
| 시간 소스 | **`AppConfig`의 기존 KST 고정 `Clock` 빈 주입** + `LocalDateTime.now(clock)` — `LocalDateTime.now()` 직접 호출 금지 (v2, R1#4 — UTC CI 9시간 어긋남 회귀 사례가 troubleshooting에 기록됨). `Clock.systemDefaultZone()` 신규 빈 추가 없음 |
| 만료 기준 | `password_changed_at <= :cutoff`, `cutoff = LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS)` — **90일 도달 시점부터 만료** ("90일 이상" 목표와 일치, v2, R1#6). 상수 `PASSWORD_EXPIRY_DAYS = 90` |
| ACTIVE 복귀 | 비밀번호 변경의 단일 경로는 `Member.changePassword()`뿐 (별도 `resetPassword()` 도메인 메서드는 **존재하지 않는다** — v2, R1#5). 시그니처를 `changePassword(String encodedPwd, LocalDateTime now)`로 확장해 `passwordChangedAt = now` 갱신을 포함시키면 내 비밀번호 변경·재설정 두 흐름 모두 반영된다. **`updateDate`도 기존 `LocalDateTime.now()` 직접 호출 대신 같은 `now` 파라미터로 통일** — 한 변경의 두 시각이 다른 시간원(UTC JVM이면 9시간 차)에서 나오는 자기모순 제거 (v5, R4#1). **`PASSWORD_EXPIRED → ACTIVE` 복귀도 `changePassword()` 내부로 중앙화** (v7, R6#1): 만료 전이는 세션을 폐기하지 않으므로 살아있는 세션의 `changeMyPassword()`가 만료 전이 후 실행되면 "새 비밀번호 + `PASSWORD_EXPIRED` 잔존 + 전 세션 폐기 → 로그인 불가" 고착 상태가 생긴다. 기존 reset 토큰 클리어와 같은 단일 관문 계약("비밀번호가 바뀌면 만료 상태도 해소")으로 도메인 메서드에 내장하고, `PasswordResetService`의 기존 명시적 복귀 블록은 중복이 되므로 제거. `LOCKED`·`DISABLED`는 건드리지 않는다(`PASSWORD_EXPIRED`일 때만 `ACTIVE` 복귀, lockedAt 보존 계약 불변) |
| 로그인 화면 안내 | 만료 계정 로그인 시도 → `/admin/login-error` 리다이렉트는 기존과 동일 — **만료 전용 메시지 구분은 하지 않는다** (v5, R4#3 기각). `CredentialsExpiredException`은 비밀번호 검증 전에 던져지므로 메시지를 구분하면 미인증 공격자에게 계정 상태가 누설된다 (`LOCKED`가 generic 메시지인 것과 동일 정책). "비밀번호를 잊으셨나요?" 링크(로그인 화면 상시 노출)가 복구 경로 |

## 수정해야 할 정확한 파일

### 신규 생성
| 파일 | 내용 |
|------|------|
| `src/main/resources/db/migration/V5__add_member_password_changed_at.sql` | 컬럼 추가(NULL 허용) — 단일 `ALTER`만 수행. **작성 전 `src/main/resources/db/migration/`의 현재 최대 버전을 확인해 다음 번호 사용** (현재 최대 V4 — V5 유효 확인됨, 2026-07-14) |
| `src/main/resources/db/migration/V6__backfill_member_password_changed_at.sql` | 전 행 `NOW(6)` 백필 — 트랜잭션 가능한 DML만 포함 |
| `src/main/resources/db/migration/V7__member_password_changed_at_not_null.sql` | `NOT NULL` 강제 — 단일 `ALTER`만 수행 (V6 성공 후에만 도달하므로 미백필 행 없음) |
| `src/main/java/com/cms/config/auth/PasswordExpiryService.java` | 만료 조건부 벌크 UPDATE 위임 (기본 전파 `REQUIRED` — 바깥 로그인 트랜잭션에 참여). `Clock` 주입, `PASSWORD_EXPIRY_DAYS = 90` 상수 보유 |
| `src/test/java/com/cms/config/auth/PasswordExpiryServiceTest.java` | `@DataJpaTest` + 고정 `Clock` `@TestConfiguration` (기존 `LoginFailureServiceTest` 선례) — 벌크 UPDATE 조건 검증 |
| `src/test/java/com/cms/config/auth/PasswordExpiryIntegrationTest.java` | `@SpringBootTest` + `@AutoConfigureMockMvc` formLogin — 만료 전이 커밋 유지(noRollbackFor)·실패 카운트 미증가·경계 검증 (기존 `LoginFailureLockoutIntegrationTest` 선례) |

### 수정
| 파일 | 변경 내용 |
|------|-----------|
| `src/main/java/com/cms/admin/member/domain/Member.java` | 필드 `@Column(name = "password_changed_at", nullable = false) private LocalDateTime passwordChangedAt;` 추가. `changePassword()` 시그니처를 `changePassword(String encodedPwd, LocalDateTime now)`로 확장 — 기존 토큰·실패 카운트 클리어에 `this.passwordChangedAt = now;` 추가, **`this.updateDate`도 `LocalDateTime.now()` 대신 `now`로 통일** (v5, R4#1), **`if (this.status == PASSWORD_EXPIRED) this.status = ACTIVE;` 복귀 내장** — lockedAt은 건드리지 않음 (v7, R6#1) |
| `src/main/java/com/cms/admin/member/repository/MemberRepository.java` | 조건부 벌크 UPDATE 추가: `@Modifying(clearAutomatically = true)` `expirePasswordIfOutdated(userId, cutoff, now)` — `SET status = PASSWORD_EXPIRED, update_date = :now WHERE user_id = :userId AND status = 'ACTIVE' AND user_type IN ('ROLE_ADMIN','ROLE_MANAGER') AND password_changed_at <= :cutoff` (기존 잠금 쿼리 4종과 같은 스타일 — 역할 allowlist 포함) |
| `src/main/java/com/cms/config/auth/CustomUserDetailsService.java` | ① `@Transactional`에 `noRollbackFor = CredentialsExpiredException.class` 추가, ② `unlockIfLockExpired(userId)` 다음 줄에 `passwordExpiryService.expireIfPasswordOutdated(userId)` 호출 추가 (fresh 조회 전 — 조회된 회원은 이미 만료 반영된 상태), `PasswordExpiryService` 주입 |
| `src/main/java/com/cms/admin/member/service/AdminMemberService.java` | `createAdmin()`의 메서드 내 지역 변수 `now`를 `LocalDateTime.now(clock)`으로 바꾸고 빌더에 `passwordChangedAt(now)` 세팅 — 단일 INSERT 행 안에서 `createDate`와 `passwordChangedAt`이 다른 시간원을 갖지 않게 통일 (v5, R4#2, 해당 메서드 한정). `changeMyPassword()`의 `changePassword()` 호출을 2인자(`now`)로 변경 — `Clock` 주입 추가 |
| `src/main/java/com/cms/admin/member/service/PasswordResetService.java` | `resetPassword()` 내 `member.changePassword()` 호출을 2인자(`now`)로 변경 (이미 `Clock` 보유). 직후의 명시적 `PASSWORD_EXPIRED → changeStatus(ACTIVE)` 블록은 `changePassword()` 내장 복귀와 중복이 되므로 제거 (v7, R6#1 — 기존 재설정 테스트의 ACTIVE 복귀 단언이 회귀 검증) |
| `src/main/java/com/cms/admin/member/TestMemberLoader.java` | dev 기본 admin 계정 빌더에 `passwordChangedAt` 세팅 — `Clock` 주입 추가, `createDate`와 같은 `LocalDateTime.now(clock)` 값으로 통일 (v5, R4#2) |
| `src/main/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandler.java` | `PasswordExpiryService` 주입, `verifyFreshMemberState()`의 try 블록 첫 줄에 `passwordExpiryService.expireIfPasswordOutdated(authentication.getName())` 호출 추가 — 인증 중 90일 경계 통과 TOCTOU 차단, 예외는 기존 catch가 fail-closed 거부 (v8, R7#1) |

### 기존 테스트 파급 (v2, R1#9 — 실측 기준)
| 파일 | 사유 |
|------|------|
| `src/test/java/com/cms/admin/member/service/AdminMemberServiceTest.java` | `AdminMemberService`에 `Clock` 의존성 추가 → `@InjectMocks` 구성에 고정 `Clock` 제공 필요 |
| `src/test/java/com/cms/admin/member/domain/MemberLockoutTest.java` | `changePassword()` 2인자 시그니처 변경 반영 + 도메인 단위 테스트 추가: `PASSWORD_EXPIRED`에서 `changePassword` → `ACTIVE` 복귀, `LOCKED`에서 `changePassword` → 상태 불변·lockedAt 보존 (v7, R6#1) |
| `src/test/java/com/cms/config/auth/LoginFailureLockoutIntegrationTest.java`, `src/test/java/com/cms/config/auth/AdminSessionRevocationIntegrationTest.java` | 실로그인 fixture 회원에 `passwordChangedAt` 세팅 필요 |
| `AdminMemberUpdateConcurrencyIntegrationTest` · `PasswordResetConcurrencyIntegrationTest` · `VisitLogRepositoryDataJpaTest` · `LoginFailureConcurrencyIntegrationTest` · `LoginFailureServiceTest` | `Member.builder()`로 DB 직접 저장하는 fixture — `NOT NULL` 위반 방지 위해 `passwordChangedAt` 세팅 추가 |
| `src/test/java/com/cms/config/auth/VisitLoggingAuthenticationSuccessHandlerTest.java` | 핸들러에 `PasswordExpiryService` 의존성 추가 → 목 구성 반영 + 성공 경로에서 만료 재판정 호출 검증 (v8, R7#1) |

주: `CustomUserDetailsService`를 직접 생성(new)하는 기존 테스트는 없다 — 생성자 인자 보정 파급은 발생하지 않는다 (v2, R1#9 실측).

## 단계별 작업 순서

1. **선행 확인**: ~~`PLAN-password-reset.md` 완료 여부~~ → 완료 확인됨 (2026-07-14 #10). 만료 정책(90일·로그인 시점 검사) 사용자 승인만 확인.
2. **브랜치 생성**: `git checkout -b feat/password-expiry`
3. **Flyway 마이그레이션 3개 작성** (설명 줄은 실파일에서도 반드시 `--` SQL 주석으로 작성한다 — v3, R2#1 재발 방지):
   - `V5__add_member_password_changed_at.sql` — ALTER 1개만 (암묵 커밋 단위 격리):
   ```sql
   -- 비밀번호 최종 변경 시각 (90일 도달 시 PASSWORD_EXPIRED 전이 기준)
   ALTER TABLE `member`
     ADD COLUMN `password_changed_at` DATETIME(6) NULL;
   ```
   - `V6__backfill_member_password_changed_at.sql` — DML만:
   ```sql
   -- 기존 계정 백필: 마이그레이션 시각 기준 전원 90일 유예
   -- (update_date는 토큰 발급·잠금 해제로도 갱신되는 오염된 대리값이라 사용하지 않음.
   --  DB 세션이 UTC면 KST 대비 최대 9시간 이르지만 90일 지평에서 수용)
   UPDATE `member`
   SET `password_changed_at` = NOW(6)
   WHERE `password_changed_at` IS NULL;
   ```
   - `V7__member_password_changed_at_not_null.sql` — ALTER 1개만:
   ```sql
   -- 보안 정책 컬럼 — null fail-open 경로 차단 (V6 백필 성공 후에만 도달)
   ALTER TABLE `member`
     MODIFY COLUMN `password_changed_at` DATETIME(6) NOT NULL;
   ```
   - 분리 이유: MariaDB `ALTER TABLE`은 암묵 커밋 — 한 파일에 섞으면 부분 실패 시 컬럼은 남고 Flyway는 실패로 기록되어 재실행이 첫 `ADD COLUMN`부터 다시 깨진다("컬럼 이미 존재" 오류 함정). 파일당 커밋 단위 1개로 분리하면 이 함정이 제거된다. 단, DDL 마이그레이션(V5/V7) 실패 시 flyway_schema_history의 실패 기록 정리(`flyway repair`)는 여전히 필요할 수 있다 — 분할이 없애는 것은 repair 자체가 아니라 repair 후 재실행이 깨지는 문제다 (v7, R6#2 서술 정정). V6의 `WHERE ... IS NULL`은 재실행 멱등성 보강.
   - **배포 전제 (v6, R5#1)**: 이 3분할은 **단일 인스턴스 배포 전제**다 — Flyway는 새 버전 앱 기동 시 실행되고, 마이그레이션 중 구버전 인스턴스가 공존하지 않는다(현재 운영 환경 미구축, 로컬/dev 단일 인스턴스만 존재). 롤링/무중단 배포를 도입하는 시점(로드맵 3단계)에는 이런 NOT NULL 강제 마이그레이션을 expand(nullable+신구 호환 배포) → 전체 교체 → contract(NOT NULL 별도 릴리스) 다중 릴리스로 분리해야 한다.
4. **Member 엔티티 수정**: `nullable = false` 필드 추가, `changePassword(String encodedPwd, LocalDateTime now)` 시그니처 확장(내부에서 `passwordChangedAt = now`, `updateDate = now`, `PASSWORD_EXPIRED → ACTIVE` 복귀). 호출부 2곳(`AdminMemberService.changeMyPassword`, `PasswordResetService.resetPassword`)에 각자의 `Clock` 기반 `now` 전달, 재설정 서비스의 중복 복귀 블록 제거.
5. **MemberRepository에 조건부 벌크 UPDATE 추가**: `expirePasswordIfOutdated(userId, cutoff, now)` — `status = 'ACTIVE' AND user_type IN ('ROLE_ADMIN','ROLE_MANAGER') AND password_changed_at <= :cutoff` 조건, `update_date = :now`(앱 KST Clock — 기존 잠금 쿼리들과 동일 계약), `@Modifying(clearAutomatically = true)`.
6. **PasswordExpiryService 작성**: `@Service` + `@RequiredArgsConstructor`, 의존성 `MemberRepository`·`Clock`.
   ```java
   public static final int PASSWORD_EXPIRY_DAYS = 90;

   /**
    * 비밀번호가 90일에 도달한 ACTIVE 계정을 PASSWORD_EXPIRED로 전이한다 (조건부 벌크 UPDATE).
    * 로그인 쓰기 트랜잭션(loadUserByUsername)에 참여한다 — REQUIRES_NEW 금지
    * (커넥션 풀 고갈·자기 락 대기, lazy unlock과 동일한 이유. R1#2).
    */
   @Transactional
   public void expireIfPasswordOutdated(String userId) {
       LocalDateTime now = LocalDateTime.now(clock);
       memberRepository.expirePasswordIfOutdated(userId, now.minusDays(PASSWORD_EXPIRY_DAYS), now);
   }
   ```
   - 예외를 여기서 던지지 않는다 — 전이만 수행하고, 거부는 fresh 조회 후 `validateMemberStatus()`의 기존 `PASSWORD_EXPIRED` 분기가 담당한다.
7. **CustomUserDetailsService 수정**: `PasswordExpiryService` 주입, `unlockIfLockExpired(userId)` 다음 줄에 `passwordExpiryService.expireIfPasswordOutdated(userId);` 추가 (해제 → 만료 전이 → fresh 조회 → 상태 검증 순서), `@Transactional(noRollbackFor = CredentialsExpiredException.class)`로 변경 — 만료 전이 벌크 UPDATE가 인증 예외에도 커밋 유지되도록.
8. **createAdmin·TestMemberLoader 수정**: `Clock` 주입 + 빌더에 `passwordChangedAt(LocalDateTime.now(clock))` 추가.
9. **테스트 작성·수정**:
   - `PasswordExpiryServiceTest` (`@DataJpaTest` + 고정 `Clock` `@TestConfiguration` — `LoginFailureServiceTest` 선례): 90일 미달 → 0행, 90일 도달(경계 `== cutoff`) → 전이, 90일 초과 → 전이, 비ACTIVE(LOCKED·PASSWORD_EXPIRED·DISABLED) → 0행(덮어쓰기 없음), **`ACTIVE ROLE_USER` 91일 경과 → 0행·ACTIVE 유지** (allowlist 검증, R3#1), 전이 시 `updateDate == now` 단언.
   - `PasswordExpiryIntegrationTest` (`@SpringBootTest` + MockMvc formLogin, 실커밋·고유 식별자·`@AfterEach` 한정 정리 — `LoginFailureLockoutIntegrationTest` 선례):
     - 만료 계정 로그인 → 거부 + **DB status가 `PASSWORD_EXPIRED`로 커밋 유지** (noRollbackFor 실증)
     - 만료 로그인 거부가 `failed_login_count`를 증가시키지 않음 (`CredentialsExpiredException`은 `BadCredentialsException`이 아님 — 기존 잠금 카운트 규약 확인)
     - 89일 경과 계정 정상 로그인
     - 재설정(또는 `changePassword`) 후 로그인 성공 + `passwordChangedAt` 갱신 확인
     - **`PASSWORD_EXPIRED` 상태에서 `changeMyPassword` 실행 → `ACTIVE` 복귀 + 재로그인 성공** (R6#1 "만료 선행" 인터리빙의 결정적 재현 — 살아있는 세션이 만료 전이 후 비밀번호를 변경하는 시나리오). "변경 선행" 인터리빙은 위 "재설정 후 만료 UPDATE 0행" 검증이 커버 — 스레드 기반 비결정적 동시성 테스트는 도입하지 않는다
     - **인증 중 90일 경계 통과(TOCTOU) 등가 재현** (R7#1): `ACTIVE` + `passwordChangedAt` 91일 전 상태(= loadUserByUsername 이후 경계를 넘은 것과 등가)로 성공 핸들러의 재판정 경로를 호출 → 만료 전이 + 로그인 거부 확인
   - 시간 비교는 전부 서비스와 같은 `Clock` 빈 기준 (troubleshooting "KST 고정 Clock ↔ `LocalDateTime.now()` 혼용 CI 실패" 재발 방지).
   - 기존 테스트 파급 수정: 위 "기존 테스트 파급" 표의 9개 파일.
10. **검증**: `./gradlew test`. 수동 검증: dev DB에서 `UPDATE member SET password_changed_at = NOW() - INTERVAL 91 DAY WHERE user_id='admin';` 실행 → 로그인 시도 → 거부 확인 → DB status `PASSWORD_EXPIRED` 확인 → 재설정 메일 흐름으로 새 비밀번호 설정 → 로그인 성공 + status `ACTIVE` + `password_changed_at` 갱신 확인. (수동 SQL의 `NOW()`는 DB 세션 시계 — 컨테이너 DB는 UTC이므로 91일이면 KST 오차와 무관하게 만료 확정)
11. **커밋·PR 생성** (한국어 커밋 메시지).

## 엣지 케이스

1. **정확히 90일째**: `password_changed_at <= cutoff` — 90일 도달 시점부터 만료 ("90일 이상" 목표와 일치). 89일 23:59는 로그인 가능 (테스트에 경계 명시).
2. **만료 전이 커밋 직후 인증 예외**: `noRollbackFor = CredentialsExpiredException.class`로 벌크 UPDATE가 커밋 유지되고 로그인만 거부된다. 다음 로그인 시도는 벌크 UPDATE가 0행(이미 `PASSWORD_EXPIRED`)이고 `validateMemberStatus()`에서 바로 거부된다(재전이 시도 없음).
3. **LOCKED와 중첩**: lazy unlock(`LOCKED→ACTIVE`) → 만료 전이(`ACTIVE→PASSWORD_EXPIRED`) → fresh 조회 순서라, 만료된 자동 잠금이 해제된 직후 비밀번호도 만료라면 그 로그인에서 곧바로 `PASSWORD_EXPIRED`로 전이·거부된다 — 올바른 최종 상태. 살아있는 잠금(LOCKED 유지)은 만료 벌크 UPDATE의 `status = 'ACTIVE'` 조건 불충족으로 건드리지 않고 `LockedException`이 우선한다.
4. **동시 로그인 2건이 동시에 만료 감지**: 조건부 벌크 UPDATE라 먼저 커밋한 쪽만 1행, 나머지는 0행 — 자연 멱등, 문제 없음.
5. **만료 전이 ↔ 비밀번호 재설정 경합**: 재설정이 먼저 커밋되면 `passwordChangedAt`이 갱신되어 만료 UPDATE 조건(`<= cutoff`) 불충족 → 0행, 새 비밀번호가 만료로 덮이지 않는다. 만료 전이가 먼저 커밋되면 재설정은 `PASSWORD_EXPIRED` 허용 대상이므로 정상 복구된다. (R1#3의 덮어쓰기 시나리오가 조건부 UPDATE로 해소됨)
6. **PASSWORD_EXPIRED 상태에서 `PATCH /admin/api/members/{id}`로 ACTIVE 변경 (관리자 수동 복구)**: 허용되지만 `passwordChangedAt`이 갱신되지 않아 **다음 로그인 때 즉시 재만료**된다. 이는 의도된 동작(비밀번호를 바꾸지 않았으므로). 관리자 수동 복구는 임시 조치일 뿐이라는 사실을 PR 설명에 명시한다. 운영상 문제로 판단되면 별도 논의.
7. **재설정 흐름에서 90일 이내인데 PASSWORD_EXPIRED인 계정** (케이스 6에서 수동 전이된 경우 등): 재설정은 상태 기준(`ACTIVE`/`PASSWORD_EXPIRED` 허용)이므로 정상 동작한다.
8. **세션이 살아있는 중에 만료일이 도래**: 로그인 시점 검사 방식이므로 기존 세션은 세션 타임아웃까지 유효하다. best-effort 계약(CLAUDE.md 세션 만료 문단)과 일관된 수준으로 허용한다. 성공 핸들러의 fresh 재확인은 인증 완료 직전 커밋된 `PASSWORD_EXPIRED`를 fail-closed로 거부한다(기존 ② 인프라 재사용 — 추가 구현 없음).
9. **`ACTIVE ROLE_USER`의 사용자명이 로그인 폼에 제출됨**: 만료 UPDATE의 `user_type IN ('ROLE_ADMIN','ROLE_MANAGER')` 조건 불충족 → 0행, 상태 불변. `ROLE_USER`는 재설정 allowlist 밖이라 만료 전이되면 자가 복구가 불가능하므로 애초에 전이 대상에서 제외한다 (기존 오잠금 방지 정책과 동일 원리, v4).
10. **경합으로 만료 전이가 인증 성공과 겹침**: 벌크 UPDATE 이후 다른 요청이 같은 계정으로 인증을 통과 중이더라도, `VisitLoggingAuthenticationSuccessHandler`의 fresh 상태 재확인이 `PASSWORD_EXPIRED`를 거부한다 — 기존 fail-closed 계약으로 커버.
11. **살아있는 세션이 만료 전이 후 `changeMyPassword` 실행** (v7, R6#1): 만료 전이는 세션을 폐기하지 않으므로(케이스 8) 이 순서는 경합 없이도 평범하게 도달 가능하다. `changePassword()` 내장 복귀로 새 비밀번호 저장과 함께 `ACTIVE`로 복귀 — 세션 폐기(기존 동작) 후 새 비밀번호로 재로그인 가능. 역순(변경 선행)은 `passwordChangedAt` 갱신으로 만료 UPDATE 조건 불충족(0행) — 케이스 5와 동일 원리로 안전.
12. **인증 처리 중 90일 경계 도달(TOCTOU)** (v8, R7#1): `loadUserByUsername` 판정 시점엔 미만료였으나 BCrypt 검증 등 처리 중 경계를 넘는 경우 — 성공 핸들러의 `verifyFreshMemberState()`가 만료 재판정(조건부 벌크 UPDATE)을 먼저 수행하므로 fresh 조회가 `PASSWORD_EXPIRED`를 읽어 거부한다. 재판정 호출이 예외를 던지면 기존 catch가 fail-closed 거부. 비용은 성공 로그인당 조건부 UPDATE 1회(인덱스 조건, 대부분 0행)로 수용.

## 완료 기준

- [x] `PLAN-password-reset.md` 완료 후 착수했다 (2026-07-14 #10 확인).
- [x] 만료 정책(90일, 로그인 시점 검사, 마이그레이션 시각 백필, NOT NULL)에 대해 사용자 승인을 받았다 (2026-07-17 — 성공 직전 재판정 포함 v8 전체 승인).
- [x] Flyway 마이그레이션 3개(V5 컬럼 / V6 백필 / V7 NOT NULL)가 추가되었고 빈 DB에서 V1~V7 전체 성공 + `ddl-auto: validate` 기동 통과 (2026-07-18 실측 — 기존 데이터 DB는 로컬에 없음, "이슈" 참조).
- [x] `./gradlew test` 전체 통과 (350 tests, BUILD SUCCESSFUL).
- [x] 91일 조작 계정 로그인 거부 + `PASSWORD_EXPIRED` 커밋 유지 (통합 테스트 + 실기 curl 검증).
- [x] 89일 정상 로그인 / 정확히 90일 만료 (경계 테스트 — 서비스·통합 양쪽).
- [x] 만료 로그인 거부가 `failed_login_count`를 증가시키지 않는다 (통합 테스트 + 실기 DB 확인 0 유지).
- [x] 만료 전이가 LOCKED·PASSWORD_EXPIRED·DISABLED를 덮어쓰지 않는다 (0행 테스트).
- [x] `ACTIVE ROLE_USER` 91일 경과 무전이 (allowlist 0행 테스트).
- [x] 만료 계정의 비밀번호 변경 후 `ACTIVE` 복귀 + 로그인 성공 (통합 테스트 — changeMyPassword 경로; 재설정 경로는 동일 도메인 관문(`changePassword`) 공유 + 기존 재설정 테스트의 ACTIVE 복귀 단언이 회귀 검증).
- [x] 신규 생성 계정(`createAdmin`·`TestMemberLoader`)의 `password_changed_at` 세팅 (NOT NULL 기동·시드 성공 실측).
- [x] 비밀번호 변경 시 `password_changed_at` 갱신 (통합 테스트 단언).
- [x] `PASSWORD_EXPIRED`에서 `changeMyPassword` → `ACTIVE` 복귀 + 재로그인 성공, `LOCKED`의 `changePassword`는 상태 불변 (도메인·통합 테스트, R6#1).
- [x] 성공 핸들러 만료 재판정 — `ACTIVE` + 91일 상태로 성공 경로 진입 시 전이 + 거부 (통합 테스트, R7#1).
- [x] 시간 비교 전부 KST `Clock` 빈 기준 (`LocalDateTime.now()` 직접 호출 없음 — 신규 코드 한정; 기존 엔티티의 잔존 직접 호출은 이 계획 범위 밖).

## 구현·검증 결과 (2026-07-18, feat/password-expiry)

**Context**: 계획 v8(적대적 리뷰 7라운드 — codex 5회 + 자체 2회, 최종 codex SHIP) 그대로 구현. 정책·스키마 승인 2026-07-17.

**핵심 확정 사항**: 계획 v8과 동일 — 이탈 없음. 구현 중 계획에 없던 파급 1건 발견·수정(아래 이슈 1).

**구현 파일**:
- 신규: `V5/V6/V7` 마이그레이션, `PasswordExpiryService`, `PasswordExpiryServiceTest`(@DataJpaTest 6케이스), `PasswordExpiryIntegrationTest`(@SpringBootTest 5케이스)
- 수정: `Member`(필드 + `changePassword(encodedPwd, now)` 확장·복귀 내장), `MemberRepository.expirePasswordIfOutdated`, `CustomUserDetailsService`(noRollbackFor + 호출), `VisitLoggingAuthenticationSuccessHandler`(재판정), `AdminMemberService`(Clock·createAdmin·changeMyPassword), `PasswordResetService`(2인자 + 중복 복귀 블록 제거), `TestMemberLoader`(Clock + passwordChangedAt, 중복 `.status()` 정리)
- 테스트 파급 수정 12곳: 계획의 10곳 + `SecurityConfigTest`·`ApiSecurityConfigTest`·`PasswordResetControllerTest`(핸들러 직접 생성 — 계획 누락분, 이슈 1)

**검증 결과**:
- `./gradlew test` 350건 전체 통과. 빈 DB Flyway V1~V7 전체 success + `validate` 기동 성공.
- 실기(curl E2E — Playwright MCP 서버 미연결로 브라우저 검증 불가, 아래 이슈 3): 91일 조작 → 로그인 거부(`/admin/login-error`) + DB `PASSWORD_EXPIRED` + 카운트 0 → 재시도 거부(멱등) → 복구 후 로그인 성공(`/admin`) → 기존 화면 4종(`/admin`, `member/manage`, `member/info`, `menu/manage`) 200 회귀 확인.

**이슈**:
1. **계획 파급 누락**: R7#1(성공 핸들러 생성자 확장)로 핸들러를 직접 `new`하는 슬라이스 테스트 설정 3곳 컴파일 실패 — 목 추가로 해결. R7 수용 시 파급 재실측을 생략한 것이 원인.
2. **로컬 dev DB가 Flyway 이전 세대**: `flyway_schema_history`·`visit_log` 부재로 통합 테스트 전체 실패 — baseline 불가 드리프트(가이드 체크리스트 기준 중단 대상)라 사용자 승인 하에 스키마 초기화로 해결. `docs/troubleshooting.md` 기록.
3. **Playwright MCP 미연결**: 브라우저 스크린샷 검증은 수행하지 못함 — curl 기반 E2E(실 필터 체인·DB 왕복)로 대체. 이 기능은 템플릿 변경이 없어 UI 회귀 표면은 페이지 200 확인으로 커버.

**후속**: 없음 (R4#4 감사 로그·R5 expand/contract 전환은 기각·조건부 문서화로 종결).

## 개정 이력

- **v8 변경 (2026-07-17, 적대적 리뷰 7라운드 — codex no-ship 판정 반영, 1건 수용)**:
  - **[수용, R7#1] 인증 중 90일 경계 통과 TOCTOU**: 만료 판정이 `loadUserByUsername` 시작부뿐이라 BCrypt 검증 중 경계를 넘으면 만료 계정이 로그인에 성공 — 창은 밀리초 수준이지만 성공 핸들러 fail-closed 재확인(잠금·강등·구 비밀번호 경합 차단)의 계약과 비대칭이고, 이미 계획된 `PasswordExpiryService` 재사용으로 수정 비용이 1줄 + 테스트라 수용. `verifyFreshMemberState()` try 첫머리에 만료 재판정 호출 추가 — 전이 시 기존 `status != ACTIVE` 분기가 거부(새 분기 없음). 엣지 케이스 12·결정적 등가 테스트·핸들러 단위 테스트 파급·완료 기준 신설.
- **v7 변경 (2026-07-17, 적대적 리뷰 6라운드 — codex no-ship 판정 반영, 2건 수용)**:
  - **[수용, R6#1] 만료 전이 ↔ `changeMyPassword` 불일치**: 만료 전이는 세션을 폐기하지 않으므로 살아있는 세션이 만료 전이 후 내 비밀번호를 변경하면 "새 비밀번호 + `PASSWORD_EXPIRED` 잔존 + 전 세션 폐기 → 로그인 불가" 고착 상태가 됨 (경합 없이도 도달 가능 — 실측: `PasswordResetService:270-272`에만 복귀 블록 존재). codex 제시 해법 중 도메인 계약 중앙화 채택 — `Member.changePassword()`에 `PASSWORD_EXPIRED → ACTIVE` 복귀 내장(기존 reset 토큰 클리어 단일 관문 계약과 동일 원리), 재설정 서비스의 중복 블록 제거, 엣지 케이스 11·도메인/통합 테스트·완료 기준 신설. 스레드 기반 동시성 테스트 요구는 결정적 등가 테스트로 대체(만료 선행 = `PASSWORD_EXPIRED`에서 변경 호출, 변경 선행 = 만료 UPDATE 0행 기존 테스트).
  - **[수용, R6#2] repair 서술 정정 (부수)**: 3분할이 없애는 것은 "재실행이 `ADD COLUMN`부터 깨지는 함정"이지 `flyway repair` 필요성 자체가 아님 — DDL 실패 시 실패 기록 정리는 여전히 필요할 수 있음을 명시.
- **v6 변경 (2026-07-17, 적대적 리뷰 5라운드 — codex no-ship 판정 반영, 1건 부분 수용)**:
  - **[부분 수용, R5#1] 마이그레이션 3분할의 롤링 배포 비호환**: 지적 자체는 정확 — V5/V6/V7은 DDL 부분 실패 복구용이지 expand/contract 배포 호환 분리가 아니다. 그러나 이 프로젝트는 운영 환경 미구축(단일 인스턴스 로컬/dev만 존재)이라 마이그레이션 중 구버전 인스턴스 공존 시나리오가 없다 — codex 선택지 1대로 **단일 인스턴스 배포 전제를 계획에 명시**하고, 실배포(로드맵 3단계) 도입 시 expand/contract 다중 릴리스 전환 필요를 문서화. 다중 릴리스 분할·트리거 호환 장치·롤링 호환 테스트는 존재하지 않는 배포 방식에 대한 과잉으로 **기각** (R2의 "운영 규모 실행시간 측정" 기각과 동일 근거 계열).
- **v5 변경 (2026-07-17, 적대적 리뷰 4라운드 — codex CLI 부재로 자체 리뷰 폴백(출처 명시), no-ship 판정 반영, 2건 수용·2건 기각)**:
  - **[수용, R4#1] `changePassword` 내 시간원 통일**: `passwordChangedAt = now`(Clock 파라미터)와 `updateDate = LocalDateTime.now()`(JVM 직접 호출)가 한 메서드에 공존하면 UTC JVM(CI)에서 두 시각이 9시간 어긋난다 — R1#4의 KST Clock 계약과 자기모순. `updateDate = now`로 통일.
  - **[수용, R4#2] `createAdmin`·`TestMemberLoader` 단일 INSERT 내 시간원 통일**: `createDate`/`updateDate`(기존 `LocalDateTime.now()`)와 `passwordChangedAt`(Clock)이 한 행에서 다른 시간원을 갖는 문제 — 메서드 내 지역 `now`를 `LocalDateTime.now(clock)`으로 교체해 통일 (해당 메서드 한정, 무관 리팩터링 아님).
  - **[기각, R4#3] 만료 전용 로그인 에러 메시지**: `CredentialsExpiredException`은 비밀번호 검증 **전**에 던져지므로 메시지 구분은 미인증 공격자에게 계정 상태를 누설한다 — `LOCKED` generic 메시지와 동일 정책 유지, "비밀번호를 잊으셨나요?" 상시 링크가 복구 경로 (설계 결정 표에 근거 문서화).
  - **[기각, R4#4] 만료 전이 감사 로그**: 잠금 전이는 공격 신호(IP·URI 컨텍스트)라 감사 가치가 있지만, 만료는 시간 경과 정책 전이로 상태값 자체가 원인을 자명하게 설명 — 이벤트+리스너 추가는 가치 대비 범위 확대, 필요 시 후속 분리.
- **v4 변경 (2026-07-14, 적대적 리뷰 3라운드 — codex no-ship 판정 반영, 1건 수용)**:
  - **[수용, R3#1] 만료 대상 역할 allowlist**: 만료 UPDATE에 역할 조건이 없으면 재설정 자격(`ROLE_ADMIN`/`ROLE_MANAGER`)이 없는 `ACTIVE ROLE_USER`가 사용자명 제출만으로 자가 복구 불가능한 `PASSWORD_EXPIRED`로 전이될 수 있음 — 잠금(②)·재설정(①)과 동일한 `user_type IN ('ROLE_ADMIN','ROLE_MANAGER')` 조건 추가, `ROLE_USER` 0행 테스트·완료 기준·엣지 케이스 9 신설. `ROLE_USER`까지 만료 대상으로 확장하는 대안은 기각 — 관리자 CMS에서 `ROLE_USER`는 로그인 주체가 아니며 재설정 allowlist 확장은 별도 인가 정책 변경이 됨.
- **v3 변경 (2026-07-14, 적대적 리뷰 2라운드 — codex no-ship 판정 반영, 1건 수용·1건 기각)**:
  - **[수용, R2#2] 마이그레이션 3분할 (V5/V6/V7)**: MariaDB `ALTER TABLE`은 암묵 커밋이라 "한 파일 3단계"는 비원자적 — 부분 실패 시 컬럼은 남고 Flyway는 실패 기록이라 재실행이 `ADD COLUMN`부터 깨지는 수동 repair 함정. 파일당 커밋 단위 1개로 분리 + V6 백필에 `WHERE ... IS NULL` 멱등 조건 추가. 단, 부속 요구인 "운영 데이터 규모 실행시간 측정·락 타임아웃·유지보수 창"은 **기각** — 운영 환경 미구축(로드맵 3단계 미착수)·소규모 테이블에 과잉.
  - **[기각, R2#1] "V5 SQL 첫 줄 주석 누락 문법 오류"**: 사실 오인 — v2 계획서의 SQL 블록은 설명 줄 전부가 이미 `--` 주석이다(리뷰어 측 인코딩/파싱 문제로 추정). 재발 방지용으로 "실파일에서도 설명은 반드시 `--` SQL 주석" 문구만 추가.
- **v2 변경 (2026-07-14, 적대적 리뷰 1라운드 — codex no-ship 판정 반영, 7건 수용 + 2건 사용자 결정)**:
  - **[수용, R1#1] REQUIRES_NEW 내 예외 롤백 결함 제거**: `CredentialsExpiredException`(RuntimeException)을 같은 트랜잭션에서 던지면 기본 롤백 규칙으로 상태 저장이 소실 — 전이(벌크 UPDATE)와 예외 발생(기존 `validateMemberStatus()`)을 분리하고 `loadUserByUsername`에 `noRollbackFor` 지정.
  - **[수용, R1#2] 현행 트랜잭션 구조 반영**: `loadUserByUsername`은 ② 구현으로 이미 쓰기 트랜잭션 + lazy unlock 벌크 UPDATE 구조(REQUIRES_NEW는 풀 고갈 위험으로 배제됨이 코드 주석에 명시) — 만료 전이를 같은 트랜잭션 참여 조건부 벌크 UPDATE로 재설계, `REQUIRES_NEW`·detached 엔티티 재조회 방식 폐기.
  - **[수용, R1#3] 상태 덮어쓰기 경합 차단**: `findById()` + `changeStatus()` flush 방식은 재설정·잠금 경합에서 최신 상태를 덮어씀 — ②의 확립 패턴대로 `status = 'ACTIVE' AND password_changed_at <= :cutoff` 조건부 벌크 UPDATE로 교체 (엣지 케이스 5 신설).
  - **[수용, R1#4] KST `Clock` 계약 통일**: `Clock.systemDefaultZone()`·`LocalDateTime.now()` 직접 호출 전부 폐기, `AppConfig` KST 고정 `Clock` 주입 + `changePassword(encodedPwd, now)` 시그니처로 서비스 계산 `now` 전달 (troubleshooting의 UTC CI 실패 회귀 사례 근거).
  - **[수용, R1#5] 유령 메서드 제거**: `Member.resetPassword()`는 존재하지 않음 — 실제 구조는 `PasswordResetService.resetPassword()` → `member.changePassword()` 단일 경로. `changePassword` 하나만 수정하면 두 흐름 모두 반영.
  - **[수용, R1#6] 만료 경계 통일**: "90일 이상" 목표와 `plusDays(90).isBefore(now)`(90일 당일 허용)의 모순 — `password_changed_at <= :cutoff`(90일 도달 시 만료)로 통일.
  - **[수용, R1#9] 테스트 파급 실측 교체**: `CustomUserDetailsService` 직접 생성 테스트는 없음(기존 서술 폐기) — 실제 파급은 `AdminMemberServiceTest`(Clock)·`MemberLockoutTest`(시그니처)·로그인 fixture 2곳·`Member.builder()` 직접 저장 fixture 5곳. Mockito 단위 테스트만으로는 커밋·롤백 규칙 검증 불가 — `@SpringBootTest` 통합 테스트(`PasswordExpiryIntegrationTest`) 신설.
  - **[사용자 결정, R1#7] 백필 기준 = 마이그레이션 시각** (2026-07-14): `update_date`는 토큰 발급·잠금 해제로도 갱신되는 오염된 대리값 — 배포일부터 전원 90일 유예로 확정. `COALESCE(update_date, ...)` 백필 폐기.
  - **[사용자 결정, R1#8] NOT NULL 강제** (2026-07-14): nullable + null 허용은 영구 fail-open 경로 — 백필 후 `NOT NULL` + 엔티티 `nullable = false` + 전 생성 경로 명시 세팅으로 확정. null 방어 분기 자체를 제거.
