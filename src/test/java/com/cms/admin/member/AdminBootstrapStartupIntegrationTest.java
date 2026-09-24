package com.cms.admin.member;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.auth.LoginFailureService;
import com.cms.config.auth.PasswordExpiryService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * D-01 대안 A(감사 H-01, 2026-09-23 사용자 승인 — remediation-plan.md PR 2) 상태 행렬을
 * 실제 MariaDB로 검증한다. {@link AdminBootstrapLoaderTest}(Mockito 단위)는 호출 계약만
 * 고정하므로, 실제 unique 제약·트랜잭션 커밋·병렬 경합은 이 클래스가 담당한다.
 *
 * <p>이 프로젝트의 기존 관례(AdminBootstrapConcurrencyIntegrationTest)를 따라 {@code prod}
 * 프로파일로 컨텍스트를 전환하지 않는다 — {@code AdminBootstrapLoader}는 평범한 POJO라
 * {@code @Profile("prod")} 빈 등록 여부와 무관하게 직접 생성해 호출할 수 있고, dev 프로파일
 * 컨텍스트(Testcontainers MariaDB)에서 실제 DB 제약·트랜잭션까지 그대로 검증된다. 실제
 * {@code prod} 프로파일 JAR 기동 자체(Swagger 비활성·시크릿 등)는 이 클래스의 검증 대상이
 * 아니며 별도 실기 검증(docs/verification/)으로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class AdminBootstrapStartupIntegrationTest extends MariaDbContainerSupport {

    @Autowired
    MemberRepository memberRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    Clock clock;
    @Autowired
    TransactionTemplate transactionTemplate;
    @Autowired
    Validator validator;
    @Autowired
    LoginFailureService loginFailureService;
    @Autowired
    PasswordExpiryService passwordExpiryService;

    private final List<String> createdUserIds = new ArrayList<>();

    /**
     * {@code TestMemberLoader}(dev 프로파일)가 컨텍스트 최초 기동 시 1회 시드하는 {@code admin}
     * 계정 백업. Spring TestContext가 동일 설정({@code @SpringBootTest(classes=CmsTestApplication)})의
     * ApplicationContext·Testcontainers 컨테이너를 여러 테스트 클래스에 걸쳐 재사용하므로, 이
     * 클래스가 실행되는 시점에는 이미 ACTIVE ROLE_ADMIN(admin)이 존재한다 — 그대로 두면 모든
     * "적격 ADMIN 없음" 상태 행렬 케이스가 오염된다(존재 질의가 항상 true). 테스트 동안만
     * 제거하고, 종료 후 동일 값으로 복원해 {@code CmsApplicationTests.testMemberLoader_seedsAdminAccount}
     * 등 이 seed의 영속 존재를 전제하는 다른 테스트에 영향을 주지 않는다.
     */
    private Member devSeedBackup;

    @BeforeEach
    void isolateFromDevSeed() {
        devSeedBackup = memberRepository.findByUserId("admin").orElse(null);
        if (devSeedBackup != null) {
            memberRepository.deleteById(devSeedBackup.getId());
            memberRepository.flush();
        }
    }

    @AfterEach
    void cleanUp() {
        for (String userId : createdUserIds) {
            memberRepository.findByUserId(userId).ifPresent(m -> memberRepository.deleteById(m.getId()));
        }
        createdUserIds.clear();

        if (devSeedBackup != null && memberRepository.findByUserId("admin").isEmpty()) {
            memberRepository.saveAndFlush(Member.builder()
                    .userId(devSeedBackup.getUserId())
                    .userName(devSeedBackup.getUserName())
                    .email(devSeedBackup.getEmail())
                    .pwd(devSeedBackup.getPwd())
                    .userType(devSeedBackup.getUserType())
                    .status(devSeedBackup.getStatus())
                    .createDate(devSeedBackup.getCreateDate())
                    .passwordChangedAt(devSeedBackup.getPasswordChangedAt())
                    .build());
        }
        devSeedBackup = null;
    }

    private AdminBootstrapLoader newLoader(MockEnvironment environment) {
        return new AdminBootstrapLoader(memberRepository, passwordEncoder, clock, transactionTemplate,
                validator, environment);
    }

    private MockEnvironment envWithCredentials(String userId, String password, String email) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("ADMIN_BOOTSTRAP_USER_ID", userId);
        environment.setProperty("ADMIN_BOOTSTRAP_PASSWORD", password);
        environment.setProperty("ADMIN_BOOTSTRAP_EMAIL", email);
        return environment;
    }

    private Member seedMember(String userId, String email, Role role, MemberStatus status,
                               LocalDateTime lockedAt, LocalDateTime passwordChangedAt) {
        Member saved = memberRepository.saveAndFlush(Member.builder()
                .userId(userId)
                .userName(userId)
                .email(email)
                .pwd(passwordEncoder.encode("Seed-Pass-1234567!"))
                .userType(role)
                .status(status)
                .createDate(LocalDateTime.now(clock))
                .lockedAt(lockedAt)
                .passwordChangedAt(passwordChangedAt)
                .build());
        createdUserIds.add(userId);
        return saved;
    }

    // ── 상태 행렬: 적격(ACTIVE/LOCKED/PASSWORD_EXPIRED) ADMIN 존재 → 변수 검사 없이 skip ──

    @Test
    @DisplayName("ACTIVE ADMIN이 있으면 변수 없이도 아무것도 하지 않는다")
    void activeAdmin_skipsWithoutVariables() {
        seedMember("startup-active", "startup-active@example.com", Role.ROLE_ADMIN, MemberStatus.ACTIVE,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatCode(loader::run).doesNotThrowAnyException();

        assertThat(memberRepository.findByUserId("startup-active")).isPresent();
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("자동 잠금(LOCKED) ADMIN만 있어도 변수 없이 기동하고 잠금 상태를 그대로 둔다")
    void autoLockedAdmin_skipsWithoutTouchingLockState() {
        LocalDateTime lockedAt = LocalDateTime.now(clock).minusMinutes(5); // 30분 미경과(자동 해제 대상 아님)
        seedMember("startup-locked", "startup-locked@example.com", Role.ROLE_ADMIN, MemberStatus.LOCKED,
                lockedAt, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatCode(loader::run).doesNotThrowAnyException();

        Member reloaded = memberRepository.findByUserId("startup-locked").orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(reloaded.getLockedAt()).isEqualToIgnoringNanos(lockedAt);
    }

    @Test
    @DisplayName("비밀번호 만료(PASSWORD_EXPIRED) ADMIN만 있어도 변수 없이 기동한다")
    void passwordExpiredAdmin_skipsWithoutVariables() {
        seedMember("startup-expired", "startup-expired@example.com", Role.ROLE_ADMIN, MemberStatus.PASSWORD_EXPIRED,
                null, LocalDateTime.now(clock).minusDays(91));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatCode(loader::run).doesNotThrowAnyException();

        assertThat(memberRepository.findByUserId("startup-expired")).isPresent();
        assertThat(memberRepository.count()).isEqualTo(1);
    }

    // ── 상태 행렬: 적격 ADMIN 없음 → 변수 필요, 없으면 fail-fast ──

    @Test
    @DisplayName("빈 DB에서 변수가 없으면 기동을 실패시킨다")
    void emptyDb_missingVariables_failsFast() {
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(loader::run);

        assertThat(memberRepository.count()).isZero();
    }

    @Test
    @DisplayName("빈 DB에서 변수가 유효하면 신규 ADMIN을 생성한다")
    void emptyDb_validVariables_createsAdmin() {
        AdminBootstrapLoader loader = newLoader(
                envWithCredentials("startup-new", "Startup-Pass-2026!", "startup-new@example.com"));
        createdUserIds.add("startup-new");

        loader.run();

        Member created = memberRepository.findByUserId("startup-new").orElseThrow();
        assertThat(created.getUserType()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(created.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("MANAGER만 있으면 빈 DB와 동일하게 변수가 필요하다")
    void managerOnly_missingVariables_failsFast() {
        seedMember("startup-manager", "startup-manager@example.com", Role.ROLE_MANAGER, MemberStatus.ACTIVE,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(loader::run);

        assertThat(memberRepository.count()).isEqualTo(1); // MANAGER만 그대로, 신규 ADMIN 없음
    }

    @Test
    @DisplayName("DISABLED ADMIN만 있으면 변수 없이는 기동을 실패시킨다")
    void disabledAdminOnly_missingVariables_failsFast() {
        seedMember("startup-disabled", "startup-disabled@example.com", Role.ROLE_ADMIN, MemberStatus.DISABLED,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(loader::run);
    }

    @Test
    @DisplayName("DISABLED ADMIN만 있어도 다른 신규 ID의 유효한 변수면 신규 ADMIN을 만들고 기존 계정은 그대로 둔다")
    void disabledAdminOnly_validVariablesNewId_createsSecondAdminWithoutRevivingFirst() {
        seedMember("startup-disabled-2", "startup-disabled-2@example.com", Role.ROLE_ADMIN, MemberStatus.DISABLED,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(
                envWithCredentials("startup-new-2", "Startup-Pass-2026!", "startup-new-2@example.com"));
        createdUserIds.add("startup-new-2");

        loader.run();

        Member disabled = memberRepository.findByUserId("startup-disabled-2").orElseThrow();
        assertThat(disabled.getStatus()).isEqualTo(MemberStatus.DISABLED); // 부활하지 않음

        Member created = memberRepository.findByUserId("startup-new-2").orElseThrow();
        assertThat(created.getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }

    @Test
    @DisplayName("DELETED ADMIN만 있으면 변수 없이는 기동을 실패시킨다")
    void deletedAdminOnly_missingVariables_failsFast() {
        seedMember("startup-deleted", "startup-deleted@example.com", Role.ROLE_ADMIN, MemberStatus.DELETED,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(loader::run);
    }

    // ── 상태 혼합(v5 추가) ──

    @Test
    @DisplayName("DISABLED ADMIN + ACTIVE MANAGER 조합은 적격 ADMIN이 없으므로 변수가 필요하다")
    void disabledAdminPlusActiveManager_treatedAsNoEligibleAdmin() {
        seedMember("startup-mix-disabled", "startup-mix-disabled@example.com", Role.ROLE_ADMIN, MemberStatus.DISABLED,
                null, LocalDateTime.now(clock));
        seedMember("startup-mix-manager", "startup-mix-manager@example.com", Role.ROLE_MANAGER, MemberStatus.ACTIVE,
                null, LocalDateTime.now(clock));
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatIllegalStateException().isThrownBy(loader::run);
    }

    @Test
    @DisplayName("ACTIVE ADMIN A + DISABLED ADMIN B가 있으면 bootstrap 자격증명 ID가 B와 같아도 변수 검사 없이 그대로 기동한다(B는 방치)")
    void activeAdminPlusDisabledAdminSameBootstrapId_skipsWithoutTouchingEitherAccount() {
        seedMember("startup-mix-active", "startup-mix-active@example.com", Role.ROLE_ADMIN, MemberStatus.ACTIVE,
                null, LocalDateTime.now(clock));
        seedMember("startup-mix-b", "startup-mix-b@example.com", Role.ROLE_ADMIN, MemberStatus.DISABLED,
                null, LocalDateTime.now(clock));
        // bootstrap 자격증명의 ID가 우연히 B와 같아도(예: 재배포 시 남은 옛 설정), 전역 skip이 먼저 적용돼
        // B의 변수 검사·충돌 흡수 경로 자체를 타지 않아야 한다.
        AdminBootstrapLoader loader = newLoader(
                envWithCredentials("startup-mix-b", "Ignored-Pass1!", "ignored@example.com"));

        assertThatCode(loader::run).doesNotThrowAnyException();

        Member b = memberRepository.findByUserId("startup-mix-b").orElseThrow();
        assertThat(b.getStatus()).isEqualTo(MemberStatus.DISABLED); // 여전히 DISABLED — 흡수·부활 안 됨
        assertThat(memberRepository.count()).isEqualTo(2); // 신규 ADMIN 생성 안 됨
    }

    // ── 재조회 전 상태 변경(v5 추가, round2 명확화 3사례) — 실 DB unique 충돌 경로 ──

    @Test
    @DisplayName("재조회 전 상태 변경 사례 2: exists=false 이후 같은 ID가 LOCKED로 이미 생성돼 있으면 재조회 기준으로 흡수한다")
    void createOrReconcile_realConflict_existingLocked_absorbs() {
        seedMember("startup-conflict-locked", "startup-conflict-locked@example.com",
                Role.ROLE_ADMIN, MemberStatus.LOCKED, LocalDateTime.now(clock).minusMinutes(5),
                LocalDateTime.now(clock));
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials(
                "startup-conflict-locked", "Conflict-Pass1!", "another-email@example.com");
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        // 같은 userId로 INSERT를 시도하면 uk_member_user_id 유니크 제약으로 실제 DataIntegrityViolationException이 나고,
        // 재조회에서 LOCKED(적격)를 확인해 흡수해야 한다.
        assertThatCode(() -> loader.createOrReconcile(credentials)).doesNotThrowAnyException();

        Member existing = memberRepository.findByUserId("startup-conflict-locked").orElseThrow();
        assertThat(existing.getStatus()).isEqualTo(MemberStatus.LOCKED); // 덮어쓰지 않음(비밀번호·이메일 원본 유지)
        assertThat(existing.getEmail()).isEqualTo("startup-conflict-locked@example.com");
    }

    @Test
    @DisplayName("재조회 전 상태 변경 사례 3: exists=false 이후 같은 ID가 DISABLED로 생성돼 있으면 흡수하지 않고 실패한다")
    void createOrReconcile_realConflict_existingDisabled_rethrows() {
        seedMember("startup-conflict-disabled", "startup-conflict-disabled@example.com",
                Role.ROLE_ADMIN, MemberStatus.DISABLED, null, LocalDateTime.now(clock));
        AdminBootstrapCredentials credentials = new AdminBootstrapCredentials(
                "startup-conflict-disabled", "Conflict-Pass1!", "another-email-2@example.com");
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());

        assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> loader.createOrReconcile(credentials));

        Member existing = memberRepository.findByUserId("startup-conflict-disabled").orElseThrow();
        assertThat(existing.getStatus()).isEqualTo(MemberStatus.DISABLED); // 그대로 — 부활하지 않음
    }

    // ── 병렬 존재확인 경합(v5 추가) ──

    @Test
    @DisplayName("서로 다른 bootstrap ID의 두 실행이 동시에 신규 ADMIN INSERT를 시도하면 충돌 없이 서로 다른 ADMIN 두 명이 생성된다(운영 전제의 경계 — 결함 아님)")
    void concurrentDifferentBootstrapIds_bothInsertConcurrently_createsTwoAdmins() throws Exception {
        // 빈 DB에서 존재 질의(run() 내부의 existsByUserTypeAndStatusIn)는 커밋된 행이 아직 없으므로
        // 항상 false다 — 단순 SELECT는 서로를 막지 않으므로 이 부분은 barrier 없이도 결정적이다
        // (자체 검증: codex 리뷰 1라운드 지적 — 이 사실 자체를 barrier로 동기화하려 하면 검증하는
        // 지점(테스트의 별도 조회)과 실제로 동작하는 지점(run() 내부 재조회)이 달라져 오히려 간헐적
        // 실패를 유발한다). 그래서 진짜 결정적으로 검증해야 할 지점 — "서로 다른 ID의 두 INSERT가
        // 유니크 제약 충돌 없이 동시에 커밋되는가" — 만 CyclicBarrier로 실제 실행 지점(createOrReconcile,
        // 존재 재확인 없이 곧바로 INSERT를 시도하는 지점) 바로 앞에서 동기화한다.
        assertThat(memberRepository.existsByUserTypeAndStatusIn(Role.ROLE_ADMIN,
                EnumSet.of(MemberStatus.ACTIVE, MemberStatus.LOCKED, MemberStatus.PASSWORD_EXPIRED))).isFalse();

        AdminBootstrapCredentials credentialsA = new AdminBootstrapCredentials(
                "startup-race-a", "Race-Pass-2026-Boot!", "race-a@example.com");
        AdminBootstrapCredentials credentialsB = new AdminBootstrapCredentials(
                "startup-race-b", "Race-Pass-2026-Boot!", "race-b@example.com");
        AdminBootstrapLoader loaderA = newLoader(new MockEnvironment());
        AdminBootstrapLoader loaderB = newLoader(new MockEnvironment());
        createdUserIds.add("startup-race-a");
        createdUserIds.add("startup-race-b");

        CyclicBarrier bothAboutToInsert = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(() -> {
                awaitBarrierQuietly(bothAboutToInsert);
                loaderA.createOrReconcile(credentialsA);
            });
            Future<?> b = executor.submit(() -> {
                awaitBarrierQuietly(bothAboutToInsert);
                loaderB.createOrReconcile(credentialsB);
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(memberRepository.findByUserId("startup-race-a")).isPresent();
        assertThat(memberRepository.findByUserId("startup-race-b")).isPresent();
        assertThat(memberRepository.count()).isEqualTo(2); // 서로 다른 ID라 unique 충돌 없이 둘 다 생성됨
    }

    private static void awaitBarrierQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 자동 잠금 만료 + 비밀번호 만료 동시 발생(v5 추가) ──

    @Test
    @DisplayName("자동 잠금이 만료되고 비밀번호도 만료된 계정은 bootstrap 직후 LOCKED를 유지하고, 로그인 시도 시(잠금 해제 다음 만료 판정) PASSWORD_EXPIRED로 전이해 거절된다")
    void autoLockExpiredAndPasswordExpired_bootstrapPreservesLocked_thenLoginRejectsAsExpired() {
        LocalDateTime lockedAt = LocalDateTime.now(clock).minusMinutes(31); // 30분 초과(자동 해제 대상)
        LocalDateTime passwordChangedAt = LocalDateTime.now(clock).minusDays(91); // 90일 초과(만료 대상)
        seedMember("startup-lock-expiry-both", "startup-lock-expiry-both@example.com",
                Role.ROLE_ADMIN, MemberStatus.LOCKED, lockedAt, passwordChangedAt);

        // bootstrap 자체는 이 계정을 건드리지 않는다 — LOCKED도 D-01 allowlist라 존재 질의만으로 skip.
        AdminBootstrapLoader loader = newLoader(new MockEnvironment());
        assertThatCode(loader::run).doesNotThrowAnyException();

        Member afterBootstrap = memberRepository.findByUserId("startup-lock-expiry-both").orElseThrow();
        assertThat(afterBootstrap.getStatus()).isEqualTo(MemberStatus.LOCKED);
        assertThat(afterBootstrap.getLockedAt()).isEqualToIgnoringNanos(lockedAt);

        // 로그인 시도를 흉내낸다: CustomUserDetailsService와 동일 순서(잠금 해제 → 만료 판정)로 호출한다.
        loginFailureService.unlockIfLockExpired("startup-lock-expiry-both");
        passwordExpiryService.expireIfPasswordOutdated("startup-lock-expiry-both");

        Member afterLoginAttempt = memberRepository.findByUserId("startup-lock-expiry-both").orElseThrow();
        assertThat(afterLoginAttempt.getStatus()).isEqualTo(MemberStatus.PASSWORD_EXPIRED); // ACTIVE로 로그인 성공하지 않음
        assertThat(afterLoginAttempt.getLockedAt()).isNull(); // 잠금은 해제됨
    }
}
