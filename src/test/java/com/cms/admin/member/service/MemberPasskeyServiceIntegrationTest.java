package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.PasskeyTestSupport;
import com.cms.support.TestMembers;
import com.cms.support.TotpTestSupport;
import com.cms.support.TotpTestSupport.Enrollment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 패스키 등록·목록·삭제와 TOTP 연동(PLAN-passkey-second-factor.md 결정 1·3·5, F4)을 실제 MariaDB로 검증한다.
 * 시험 프로파일(dev)은 rp-id {@code localhost}라 기능이 켜져 있다. 실제 공개키 검증은 브라우저 가상 인증기 시험이 맡는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberPasskeyServiceIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "passkey service password";
    private static final String IP = "127.0.0.1";
    private static final String URI = "/admin/api/members/me/passkeys";
    /** 형식만 맞는 등록 응답 — 서명 검증은 실패한다(카운트 없음 경로) */
    private static final String BOGUS_ATTESTATION = """
            {"id":"AAEC","rawId":"AAEC","type":"public-key","clientExtensionResults":{},
             "response":{"attestationObject":"AAEC","clientDataJSON":"AAEC","transports":["internal"]}}""";

    @Autowired MemberPasskeyService service;
    @Autowired MemberTotpService totpService;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;

    private final List<Long> created = new ArrayList<>();
    private Member member;
    private Enrollment enrollment;

    @BeforeEach
    void setUp() {
        member = newMember("passkey-svc", Role.ROLE_MANAGER);
        enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : created) {
            jdbc.update("DELETE FROM admin_action_log WHERE target_id = ? AND target_type = 'MEMBER'", id);
        }
        TestMembers.delete(jdbc, created);
    }

    private Member newMember(String prefix, Role role) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        jdbc.update("UPDATE member SET pwd = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), saved.getId());
        created.add(saved.getId());
        return memberRepository.findById(saved.getId()).orElseThrow();
    }

    private CustomUserDetails principal(Member m) {
        return new CustomUserDetails(memberRepository.findById(m.getId()).orElseThrow());
    }

    private int totpFailures() {
        return jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId());
    }

    private LocalDateTime afterTotp() {
        return PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()).plusSeconds(1);
    }

    // ---- F4: TOTP 연동·유효 조건 ----

    @Test
    @DisplayName("F4: TOTP 본인 해제는 같은 트랜잭션에서 패스키를 모두 지운다(핸들은 남는다)")
    void disableDeletesPasskeys() {
        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", afterTotp());
        PasskeyTestSupport.insert(jdbc, member.getId(), "휴대폰", afterTotp());

        totpService.disable(principal(member), enrollment.recoveryCodes().get(0), PASSWORD, IP, URI);

        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_passkey_user WHERE member_id = ?", Integer.class, member.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("F4: ADMIN의 타인 리셋도 패스키를 모두 지운다")
    void resetByAdminDeletesPasskeys() {
        Member admin = newMember("passkey-admin", Role.ROLE_ADMIN);
        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", afterTotp());

        totpService.resetByAdmin(principal(admin), member.getId(), IP, URI);

        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
    }

    @Test
    @DisplayName("F4(롤백 시나리오): 앱 밖에서 TOTP를 지우고 더 늦은 시각으로 다시 넣으면 옛 패스키는 목록에서 빠지고 기동 정리가 지운다")
    void stalePasskeysHiddenAndCleaned() {
        PasskeyTestSupport.insert(jdbc, member.getId(), "옛 패스키", afterTotp());
        assertThat(service.list(member.getId()).items()).hasSize(1);

        // 이전 앱으로 롤백한 동안의 해제·재등록 — 패스키 행은 남는다
        jdbc.update("DELETE FROM member_totp WHERE member_id = ?", member.getId());
        assertThat(service.list(member.getId()).items()).as("TOTP 없음 = 유효 패스키 없음").isEmpty();
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        jdbc.update("UPDATE member_totp SET enabled_at = ? WHERE member_id = ?", afterTotp().plusMinutes(5), member.getId());

        assertThat(service.list(member.getId()).items()).as("현재 TOTP 등록보다 먼저 만든 패스키는 무효").isEmpty();
        assertThat(service.countValid(member.getId())).isZero();

        PasskeyTestSupport.insert(jdbc, member.getId(), "새 패스키", afterTotp().plusMinutes(10));
        assertThat(service.deleteStalePasskeys()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("SELECT label FROM member_passkey WHERE member_id = ?", String.class, member.getId()))
                .containsExactly("새 패스키");
    }

    // ---- 등록 ----

    @Test
    @DisplayName("등록 옵션: TOTP 세대·회원·5분 대기를 만들고, 첫 발급이면 핸들 행을 만든다. 옵션의 user.id = 저장된 핸들, rp = localhost")
    void startRegistrationCreatesHandle() {
        PasskeyRegistration registration = service.startRegistration(principal(member));

        assertThat(registration.memberId()).isEqualTo(member.getId());
        assertThat(registration.totpEnabledAt()).isEqualTo(PasskeyTestSupport.totpEnabledAt(jdbc, member.getId()));
        byte[] stored = jdbc.queryForObject("SELECT user_handle FROM member_passkey_user WHERE member_id = ?", byte[].class, member.getId());
        assertThat(registration.options().getUser().getId()).isEqualTo(new Bytes(stored));
        assertThat(registration.options().getRp().getId()).isEqualTo("localhost");
    }

    @Test
    @DisplayName("리뷰 1-2: 서로 다른 두 세션의 첫 등록 옵션 동시 발급 — 핸들 행 1개, 두 옵션의 user.id가 그 핸들과 같다")
    void concurrentFirstRegistrationSharesHandle() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<PasskeyRegistration>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return service.startRegistration(principal(member));
                }));
            }
            start.countDown();
            Bytes first = futures.get(0).get(20, TimeUnit.SECONDS).options().getUser().getId();
            Bytes second = futures.get(1).get(20, TimeUnit.SECONDS).options().getUser().getId();
            byte[] stored = jdbc.queryForObject("SELECT user_handle FROM member_passkey_user WHERE member_id = ?", byte[].class, member.getId());
            assertThat(first).isEqualTo(new Bytes(stored));
            assertThat(second).isEqualTo(new Bytes(stored));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("등록 옵션: TOTP 미사용은 409, 유효 패스키 10개면 409")
    void startRegistrationGuards() {
        Member noTotp = newMember("passkey-nototp", Role.ROLE_MANAGER);
        assertThatThrownBy(() -> service.startRegistration(principal(noTotp))).isInstanceOf(ConflictException.class);

        for (int i = 0; i < 10; i++) {
            PasskeyTestSupport.insert(jdbc, member.getId(), "k" + i, afterTotp());
        }
        assertThatThrownBy(() -> service.startRegistration(principal(member))).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("확정: 대기 없음·다른 ID·5분 초과·다른 회원은 409(카운트 없음)")
    void confirmRejectsStalePending() {
        PasskeyRegistration registration = service.startRegistration(principal(member));
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), null, registration.registrationId(),
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, "other",
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
        PasskeyRegistration old = new PasskeyRegistration(registration.registrationId(), member.getId(), registration.totpEnabledAt(),
                registration.options(), LocalDateTime.now(clock).minus(PasskeyRegistration.TTL).minusSeconds(1));
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), old, old.registrationId(),
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
        Member other = newMember("passkey-other", Role.ROLE_MANAGER);
        assertThatThrownBy(() -> service.confirmRegistration(principal(other), registration, registration.registrationId(),
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
        assertThat(totpFailures()).isZero();
    }

    @Test
    @DisplayName("리뷰 3-1: TOTP 리셋·재등록 뒤 옛 등록 대기로 확정하면 409이고 패스키가 생기지 않는다")
    void confirmRejectsOtherTotpGeneration() {
        PasskeyRegistration registration = service.startRegistration(principal(member));
        jdbc.update("UPDATE member_totp SET enabled_at = ? WHERE member_id = ?", registration.totpEnabledAt().plusSeconds(3), member.getId());

        assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, registration.registrationId(),
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
        assertThat(totpFailures()).isZero();
    }

    @Test
    @DisplayName("확정: 현재 비밀번호가 틀리면 member_totp.failed_attempts가 커밋되고 400, 5회면 계정 잠금")
    void confirmWrongPasswordCounts() {
        PasskeyRegistration registration = service.startRegistration(principal(member));
        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, registration.registrationId(),
                    BOGUS_ATTESTATION, "노트북", "wrong password", IP, URI)).isInstanceOf(InvalidRequestException.class);
            assertThat(totpFailures()).isEqualTo(i);
        }
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, registration.registrationId(),
                BOGUS_ATTESTATION, "노트북", "wrong password", IP, URI)).isInstanceOf(InvalidRequestException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("LOCKED");
    }

    @Test
    @DisplayName("확정: 공개키 검증 실패는 400이고 세지 않으며 행이 없다 / 형식 오류 JSON도 400")
    void confirmVerificationFailureNotCounted() {
        PasskeyRegistration registration = service.startRegistration(principal(member));
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, registration.registrationId(),
                BOGUS_ATTESTATION, "노트북", PASSWORD, IP, URI)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.confirmRegistration(principal(member), registration, registration.registrationId(),
                "{not json", "노트북", PASSWORD, IP, URI)).isInstanceOf(InvalidRequestException.class);
        assertThat(totpFailures()).isZero();
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
    }

    @Test
    @DisplayName("이름 검증: 앞뒤 공백 제거 후 1~50 코드포인트, 제어 문자 거부")
    void labelValidation() {
        assertThat(MemberPasskeyService.normalizeLabel("  노트북  ")).isEqualTo("노트북");
        assertThat(MemberPasskeyService.normalizeLabel("😀".repeat(50))).hasSize(100);
        assertThatThrownBy(() -> MemberPasskeyService.normalizeLabel("  ")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MemberPasskeyService.normalizeLabel("a".repeat(51))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MemberPasskeyService.normalizeLabel("a\nb")).isInstanceOf(InvalidRequestException.class);
    }

    // ---- 삭제 ----

    @Test
    @DisplayName("삭제: 내 패스키는 지우고 감사 PASSKEY_DELETE 1행, 남의 것은 404")
    void deleteOwnOnly() {
        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", afterTotp());
        Long id = jdbc.queryForObject("SELECT id FROM member_passkey WHERE member_id = ?", Long.class, member.getId());
        Member other = newMember("passkey-other", Role.ROLE_MANAGER);

        assertThatThrownBy(() -> service.delete(principal(other), id, IP, URI)).isInstanceOf(ResourceNotFoundException.class);
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isEqualTo(1);

        service.delete(principal(member), id, IP, URI);
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action_type = 'PASSKEY_DELETE' AND target_id = ?",
                Integer.class, member.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("리뷰 1-3: 삭제는 회원 행 잠금에서 기다린다(인증·등록과 직렬화)")
    void deleteWaitsForMemberLock() throws Exception {
        PasskeyTestSupport.insert(jdbc, member.getId(), "노트북", afterTotp());
        Long id = jdbc.queryForObject("SELECT id FROM member_passkey WHERE member_id = ?", Long.class, member.getId());
        CustomUserDetails authorized = principal(member);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holder = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, member.getId());
                holder.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            Future<?> deleting = executor.submit(() -> service.delete(authorized, id, IP, URI));
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword()).awaitWaiterFor(holder.get(), Duration.ofSeconds(8));
            assertThat(PasskeyTestSupport.count(jdbc, member.getId())).as("잠금을 쥔 동안에는 지워지지 않는다").isEqualTo(1);
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
            deleting.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(PasskeyTestSupport.count(jdbc, member.getId())).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("해제 신호 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
