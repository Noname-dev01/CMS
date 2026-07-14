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
| 만료 판정 시점 | 로그인 인증 시(`CustomUserDetailsService.loadUserByUsername`). 배치/스케줄러는 도입하지 않는다 — 로그인하지 않는 계정은 어차피 위험 노출이 없고 복잡도만 늘어남 |
| 상태 영속화 | **기존 로그인 쓰기 트랜잭션에 참여하는 조건부 벌크 UPDATE** (`REQUIRES_NEW` 아님 — v2, R1#1·#2). `loadUserByUsername`은 이미 lazy unlock 벌크 UPDATE를 수행하는 `@Transactional` 쓰기 트랜잭션이며, 주석에 REQUIRES_NEW가 커넥션 풀 고갈 위험으로 배제됐다고 명시돼 있다. 만료 전이도 같은 트랜잭션에서 `UPDATE ... SET status = PASSWORD_EXPIRED WHERE user_id = :userId AND status = 'ACTIVE' AND user_type IN ('ROLE_ADMIN','ROLE_MANAGER') AND password_changed_at <= :cutoff` 벌크 UPDATE로 수행하고, 이후 fresh 조회 → `validateMemberStatus()`의 기존 `PASSWORD_EXPIRED` 분기가 `CredentialsExpiredException`을 던진다. 커밋 유지를 위해 `loadUserByUsername`에 **`@Transactional(noRollbackFor = CredentialsExpiredException.class)`** 지정 (R1#1 — 기본 롤백 규칙상 unchecked 예외는 벌크 UPDATE를 되돌리므로 필수) |
| 만료 대상 | **`ROLE_ADMIN`·`ROLE_MANAGER` allowlist** — 잠금(②)·재설정(①)과 동일 정책 (v4, R3#1). `ROLE_USER`는 재설정 자격이 없어 만료 전이 시 자가 복구 불가 상태에 빠지므로 만료 UPDATE 조건에서 제외한다 (기존 "ROLE_USER 오잠금 방지"와 같은 원리) |
| 상태 경합 보호 | `status = 'ACTIVE'` 조건부 벌크 UPDATE가 잠금(`LOCKED`)·재설정(`ACTIVE` 복귀 + `passwordChangedAt` 갱신) 경합에서 덮어쓰기를 원천 차단 — ②(로그인 실패 잠금)가 확립한 패턴과 동일 (v2, R1#3). 엔티티 재조회 후 `changeStatus()` flush 방식 금지 |
| 시간 소스 | **`AppConfig`의 기존 KST 고정 `Clock` 빈 주입** + `LocalDateTime.now(clock)` — `LocalDateTime.now()` 직접 호출 금지 (v2, R1#4 — UTC CI 9시간 어긋남 회귀 사례가 troubleshooting에 기록됨). `Clock.systemDefaultZone()` 신규 빈 추가 없음 |
| 만료 기준 | `password_changed_at <= :cutoff`, `cutoff = LocalDateTime.now(clock).minusDays(PASSWORD_EXPIRY_DAYS)` — **90일 도달 시점부터 만료** ("90일 이상" 목표와 일치, v2, R1#6). 상수 `PASSWORD_EXPIRY_DAYS = 90` |
| ACTIVE 복귀 | 비밀번호 변경의 단일 경로는 `Member.changePassword()`뿐 (별도 `resetPassword()` 도메인 메서드는 **존재하지 않는다** — v2, R1#5). 시그니처를 `changePassword(String encodedPwd, LocalDateTime now)`로 확장해 `passwordChangedAt = now` 갱신을 포함시키면 내 비밀번호 변경·재설정 두 흐름 모두 반영된다. `PASSWORD_EXPIRED → ACTIVE` 복귀는 `PasswordResetService`에 이미 구현됨 |
| 로그인 화면 안내 | 만료 계정 로그인 시도 → `/admin/login-error` 리다이렉트는 기존과 동일. "비밀번호를 잊으셨나요?" 링크(재설정 기능에서 추가됨)로 복구 가능 |

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
| `src/main/java/com/cms/admin/member/domain/Member.java` | 필드 `@Column(name = "password_changed_at", nullable = false) private LocalDateTime passwordChangedAt;` 추가. `changePassword()` 시그니처를 `changePassword(String encodedPwd, LocalDateTime now)`로 확장 — 기존 토큰·실패 카운트 클리어에 `this.passwordChangedAt = now;` 추가 |
| `src/main/java/com/cms/admin/member/repository/MemberRepository.java` | 조건부 벌크 UPDATE 추가: `@Modifying(clearAutomatically = true)` `expirePasswordIfOutdated(userId, cutoff, now)` — `SET status = PASSWORD_EXPIRED, update_date = :now WHERE user_id = :userId AND status = 'ACTIVE' AND user_type IN ('ROLE_ADMIN','ROLE_MANAGER') AND password_changed_at <= :cutoff` (기존 잠금 쿼리 4종과 같은 스타일 — 역할 allowlist 포함) |
| `src/main/java/com/cms/config/auth/CustomUserDetailsService.java` | ① `@Transactional`에 `noRollbackFor = CredentialsExpiredException.class` 추가, ② `unlockIfLockExpired(userId)` 다음 줄에 `passwordExpiryService.expireIfPasswordOutdated(userId)` 호출 추가 (fresh 조회 전 — 조회된 회원은 이미 만료 반영된 상태), `PasswordExpiryService` 주입 |
| `src/main/java/com/cms/admin/member/service/AdminMemberService.java` | `createAdmin()` 빌더에 `passwordChangedAt(LocalDateTime.now(clock))` 세팅, `changeMyPassword()`의 `changePassword()` 호출을 2인자(`now`)로 변경 — `Clock` 주입 추가 |
| `src/main/java/com/cms/admin/member/service/PasswordResetService.java` | `resetPassword()` 내 `member.changePassword()` 호출을 2인자(`now`)로 변경 (이미 `Clock` 보유) |
| `src/main/java/com/cms/admin/member/TestMemberLoader.java` | dev 기본 admin 계정 빌더에 `passwordChangedAt(LocalDateTime.now(clock))` 세팅 — `Clock` 주입 추가 |

### 기존 테스트 파급 (v2, R1#9 — 실측 기준)
| 파일 | 사유 |
|------|------|
| `src/test/java/com/cms/admin/member/service/AdminMemberServiceTest.java` | `AdminMemberService`에 `Clock` 의존성 추가 → `@InjectMocks` 구성에 고정 `Clock` 제공 필요 |
| `src/test/java/com/cms/admin/member/domain/MemberLockoutTest.java` | `changePassword()` 2인자 시그니처 변경 반영 |
| `src/test/java/com/cms/config/auth/LoginFailureLockoutIntegrationTest.java`, `src/test/java/com/cms/config/auth/AdminSessionRevocationIntegrationTest.java` | 실로그인 fixture 회원에 `passwordChangedAt` 세팅 필요 |
| `AdminMemberUpdateConcurrencyIntegrationTest` · `PasswordResetConcurrencyIntegrationTest` · `VisitLogRepositoryDataJpaTest` · `LoginFailureConcurrencyIntegrationTest` · `LoginFailureServiceTest` | `Member.builder()`로 DB 직접 저장하는 fixture — `NOT NULL` 위반 방지 위해 `passwordChangedAt` 세팅 추가 |

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
   - 분리 이유: MariaDB `ALTER TABLE`은 암묵 커밋 — 한 파일에 섞으면 부분 실패 시 컬럼은 남고 Flyway는 실패로 기록되어 재실행이 첫 `ADD COLUMN`부터 다시 깨진다(수동 repair 함정). 파일당 커밋 단위 1개면 실패 지점부터 그대로 재실행 가능하다. V6의 `WHERE ... IS NULL`은 재실행 멱등성 보강.
4. **Member 엔티티 수정**: `nullable = false` 필드 추가, `changePassword(String encodedPwd, LocalDateTime now)` 시그니처 확장(내부에서 `passwordChangedAt = now`). 호출부 2곳(`AdminMemberService.changeMyPassword`, `PasswordResetService.resetPassword`)에 각자의 `Clock` 기반 `now` 전달.
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

## 완료 기준

- [x] `PLAN-password-reset.md` 완료 후 착수했다 (2026-07-14 #10 확인).
- [ ] 만료 정책(90일, 로그인 시점 검사, 마이그레이션 시각 백필, NOT NULL)에 대해 사용자 승인을 받았다 (착수 전 — 백필·NOT NULL은 2026-07-14 승인, 90일·검사 방식은 착수 시점 최종 확인).
- [ ] Flyway 마이그레이션 3개(V5 컬럼 / V6 백필 / V7 NOT NULL)가 추가되었고 기존 데이터가 있는 DB와 빈 DB 모두에서 기동이 성공한다 (`ddl-auto: validate` 통과).
- [ ] `./gradlew test` 전체 통과.
- [ ] `password_changed_at`을 91일 전으로 조작한 계정의 로그인이 거부되고, DB status가 `PASSWORD_EXPIRED`로 **커밋 유지**된다 (noRollbackFor 실증 — 통합 테스트 + 수동 검증).
- [ ] 89일 경과 계정은 정상 로그인되고, 정확히 90일 도달 계정은 만료된다 (경계 테스트).
- [ ] 만료 로그인 거부가 `failed_login_count`를 증가시키지 않는다 (테스트로 검증).
- [ ] 만료 전이가 LOCKED·비ACTIVE 계정을 덮어쓰지 않는다 (조건부 UPDATE 0행 테스트).
- [ ] `ACTIVE ROLE_USER`는 91일 경과여도 만료 전이되지 않는다 (allowlist 0행 테스트).
- [ ] 만료 계정이 비밀번호 재설정 흐름으로 새 비밀번호 설정 후 `ACTIVE`로 복귀하고 로그인에 성공한다.
- [ ] 신규 생성 계정(`createAdmin`·`TestMemberLoader`)의 `password_changed_at`이 세팅된다 (NOT NULL 위반 없이 기동·생성 성공).
- [ ] 비밀번호 변경(`PATCH /admin/api/members/me/password`) 시 `password_changed_at`이 갱신된다 (테스트로 검증).
- [ ] 시간 비교 로직·테스트가 전부 KST `Clock` 빈 기준이다 (`LocalDateTime.now()` 직접 호출 없음 — UTC CI 통과).

## 개정 이력

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
