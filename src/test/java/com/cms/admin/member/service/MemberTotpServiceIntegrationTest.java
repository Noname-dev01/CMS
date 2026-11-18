package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.member.repository.MemberTotpRecoveryCodeRepository;
import com.cms.admin.member.repository.MemberTotpRepository;
import com.cms.admin.member.totp.Base32;
import com.cms.admin.member.totp.RecoveryCodes;
import com.cms.admin.member.totp.TotpCodeVerifier;
import com.cms.admin.member.totp.TotpSecretCipher;
import com.cms.common.exception.ConflictException;
import com.cms.common.exception.InvalidRequestException;
import com.cms.common.exception.ResourceNotFoundException;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.LockWaitObserver;
import com.cms.support.MariaDbContainerSupport;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2단계 인증 등록·해제·ADMIN 해제(PLAN-totp-two-factor.md 결정 4)를 실제 MariaDB로 검증한다.
 * 재인증 실패는 커밋돼야 하므로(카운터) 서비스는 판정 트랜잭션을 끝낸 뒤 예외를 던진다 — 시험은 예외 뒤 DB 값을 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MemberTotpServiceIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String IP = "127.0.0.1";
    private static final String URI = "/admin/api/members/me/totp";

    @Autowired MemberTotpService service;
    @Autowired com.cms.config.auth.TotpLoginService totpLoginService;
    @Autowired MemberRepository memberRepository;
    @Autowired MemberTotpRepository memberTotpRepository;
    @Autowired MemberTotpRecoveryCodeRepository recoveryCodeRepository;
    @Autowired TotpSecretCipher cipher;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcConnectionDetails connectionDetails;
    @Autowired org.springframework.security.core.session.SessionRegistry sessionRegistry;

    private final List<Long> created = new ArrayList<>();
    private Member member;

    @BeforeEach
    void setUp() {
        member = newMember("totp-svc", Role.ROLE_MANAGER);
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

    private String code(byte[] secret) {
        return TotpTestSupport.currentCode(secret, clock);
    }

    private int auditCount(String type, long targetId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE action_type = ? AND target_id = ?", Integer.class, type, targetId);
    }

    private int loginFailures() {
        return jdbc.queryForObject("SELECT failed_login_count FROM member WHERE id = ?", Integer.class, member.getId());
    }

    private boolean enabled() {
        return memberTotpRepository.existsById(member.getId());
    }

    // ---- 등록 ----

    @Test
    @DisplayName("등록 왕복: 대기(DB 쓰기 없음) → 확정 시 암호문 저장·복구 코드 10개(해시만 저장)·감사 TOTP_ENABLE 1행·로그인 실패 횟수 0")
    void enrollRoundTrip() {
        jdbc.update("UPDATE member SET failed_login_count = 2 WHERE id = ?", member.getId());
        TotpEnrollment pending = service.startEnrollment(principal(member));
        assertThat(pending.memberId()).isEqualTo(member.getId());
        assertThat(pending.secret()).hasSize(20);
        assertThat(enabled()).isFalse();

        List<String> codes = service.confirmEnrollment(principal(member), pending, pending.enrollmentId(), code(pending.secret()), PASSWORD, IP, URI);

        assertThat(codes).hasSize(10);
        String stored = jdbc.queryForObject("SELECT secret FROM member_totp WHERE member_id = ?", String.class, member.getId());
        assertThat(stored).startsWith("v2:").doesNotContain(Base32.encode(pending.secret()));
        assertThat(cipher.decrypt(member.getId(), stored)).isEqualTo(pending.secret());
        assertThat(jdbc.queryForList("SELECT code_hash FROM member_totp_recovery_code WHERE member_id = ?", String.class, member.getId()))
                .containsExactlyInAnyOrderElementsOf(codes.stream().map(RecoveryCodes::hash).toList());
        assertThat(auditCount("TOTP_ENABLE", member.getId())).isEqualTo(1);
        assertThat(loginFailures()).isZero();
        assertThat(service.status(member.getId()).remainingRecoveryCodes()).isEqualTo(10);
    }

    @Test
    @DisplayName("등록 확정에 쓴 코드는 바로 다음 로그인에 다시 쓸 수 없다(쓴 스텝을 기록)")
    void enrollmentCodeStepIsRecorded() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        service.confirmEnrollment(principal(member), pending, pending.enrollmentId(), code(pending.secret()), PASSWORD, IP, URI);
        long step = jdbc.queryForObject("SELECT last_used_step FROM member_totp WHERE member_id = ?", Long.class, member.getId());
        assertThat(step).isEqualTo(new TotpCodeVerifier(clock).currentStep());
    }

    @Test
    @DisplayName("FM4: 대기 상태가 다른 회원 것·옛 enrollmentId·10분 경과·없음이면 409이고 아무것도 바뀌지 않는다(카운터 포함)")
    void pendingMustMatch() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        String ok = code(pending.secret());
        Member other = newMember("totp-svc-other", Role.ROLE_MANAGER);

        assertThatThrownBy(() -> service.confirmEnrollment(principal(other), pending, pending.enrollmentId(), ok, PASSWORD, IP, URI))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirmEnrollment(principal(member), pending, UUID.randomUUID().toString(), ok, PASSWORD, IP, URI))
                .isInstanceOf(ConflictException.class);
        TotpEnrollment stale = new TotpEnrollment(pending.enrollmentId(), pending.memberId(), pending.secret(),
                LocalDateTime.now(clock).minusMinutes(11));
        assertThatThrownBy(() -> service.confirmEnrollment(principal(member), stale, stale.enrollmentId(), ok, PASSWORD, IP, URI))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirmEnrollment(principal(member), null, pending.enrollmentId(), ok, PASSWORD, IP, URI))
                .isInstanceOf(ConflictException.class);
        assertThat(enabled()).isFalse();
        assertThat(loginFailures()).isZero();
    }

    @Test
    @DisplayName("이미 사용 중이면 대기 발급도 확정도 409")
    void alreadyEnabledConflicts() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        assertThatThrownBy(() -> service.startEnrollment(principal(member))).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.confirmEnrollment(principal(member), pending, pending.enrollmentId(), code(pending.secret()), PASSWORD, IP, URI))
                .isInstanceOf(ConflictException.class);
    }

    // ---- 강제 등록 화면의 대기 재사용(PLAN-totp-enrollment-reuse.md 결정 1, 이슈 #277) ----

    @Test
    @DisplayName("#277: 같은 회원의 유효한 대기는 그대로 돌려준다(같은 enrollmentId·비밀값·발급 시각 — 연장 없음)")
    void resumeReturnsValidPending() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        TotpEnrollment resumed = service.resumeOrStartEnrollment(principal(member), pending);
        assertThat(resumed).isSameAs(pending);
    }

    @Test
    @DisplayName("#277: 대기 없음·다른 회원 것·10분 지남이면 새로 발급한다(요청 회원·새 enrollmentId·새 비밀값)")
    void resumeIssuesFreshWhenNotReusable() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        Member other = newMember("totp-svc-other", Role.ROLE_MANAGER);
        TotpEnrollment othersPending = service.startEnrollment(principal(other));
        TotpEnrollment stale = new TotpEnrollment(pending.enrollmentId(), pending.memberId(), pending.secret(),
                LocalDateTime.now(clock).minus(MemberTotpService.ENROLLMENT_TTL).minusMinutes(1));

        for (TotpEnrollment current : java.util.Arrays.asList(null, othersPending, stale)) {
            TotpEnrollment fresh = service.resumeOrStartEnrollment(principal(member), current);
            assertThat(fresh.memberId()).isEqualTo(member.getId());
            assertThat(fresh.enrollmentId()).isNotEqualTo(pending.enrollmentId()).isNotEqualTo(othersPending.enrollmentId());
            assertThat(fresh.secret()).hasSize(20).isNotEqualTo(pending.secret()).isNotEqualTo(othersPending.secret());
        }
    }

    @Test
    @DisplayName("#277 FM3: 유효한 대기가 있어도 이미 사용 중이면 409(재사용 경로도 등록 여부를 본다)")
    void resumeConflictsWhenAlreadyEnabled() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        assertThatThrownBy(() -> service.resumeOrStartEnrollment(principal(member), pending)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("S10: 등록 확정의 비밀번호·코드 오답은 400이고 로그인 실패 횟수를 올린다(커밋) — 5회면 LOCKED")
    void enrollmentReauthFailuresCount() {
        TotpEnrollment pending = service.startEnrollment(principal(member));
        String wrong = TotpTestSupport.wrongCode(pending.secret(), clock);

        assertThatThrownBy(() -> service.confirmEnrollment(principal(member), pending, pending.enrollmentId(), code(pending.secret()), "wrong", IP, URI))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(loginFailures()).isEqualTo(1);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.confirmEnrollment(principal(member), pending, pending.enrollmentId(), wrong, PASSWORD, IP, URI))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("LOCKED");
        assertThat(enabled()).isFalse();
    }

    // ---- 본인 해제 ----

    @Test
    @DisplayName("본인 해제: 비밀번호 + TOTP 또는 복구 코드 → 등록·복구 코드 행 삭제, 감사 TOTP_DISABLE 1행")
    void disableWithTotpOrRecovery() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        service.disable(principal(member), code(enrollment.secret()), PASSWORD, IP, URI);
        assertThat(enabled()).isFalse();
        assertThat(recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(member.getId())).isZero();
        assertThat(auditCount("TOTP_DISABLE", member.getId())).isEqualTo(1);

        Enrollment again = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        service.disable(principal(member), again.recoveryCodes().get(3), PASSWORD, IP, URI);
        assertThat(enabled()).isFalse();
    }

    @Test
    @DisplayName("본인 해제의 오답은 400이고 TOTP 실패 횟수를 올린다 — 비밀번호가 틀리면 코드는 판정·소비하지 않는다. 5회면 LOCKED·등록 유지")
    void disableReauthFailuresCount() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        String good = code(enrollment.secret());
        String wrong = TotpTestSupport.wrongCode(enrollment.secret(), clock);

        assertThatThrownBy(() -> service.disable(principal(member), good, "wrong", IP, URI)).isInstanceOf(InvalidRequestException.class);
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId())).isEqualTo(1);
        long stepBefore = new TotpCodeVerifier(clock).currentStep() - 2;
        assertThat(jdbc.queryForObject("SELECT last_used_step FROM member_totp WHERE member_id = ?", Long.class, member.getId()))
                .as("비밀번호가 틀리면 코드를 소비하지 않는다").isEqualTo(stepBefore);
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.disable(principal(member), wrong, PASSWORD, IP, URI)).isInstanceOf(InvalidRequestException.class);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("LOCKED");
        assertThat(enabled()).isTrue();
        assertThat(auditCount("TOTP_DISABLE", member.getId())).isZero();
    }

    /** 회원 세션 하나를 레지스트리에 등록하고 세션 ID를 돌려준다(세션 만료 리스너는 레지스트리에서 회원 세션을 찾는다). */
    private String registerSession(Member m) {
        String sessionId = "totp-svc-" + java.util.UUID.randomUUID();
        sessionRegistry.registerNewSession(sessionId, principal(m));
        return sessionId;
    }

    private boolean sessionExpired(String sessionId) {
        var info = sessionRegistry.getSessionInformation(sessionId);
        return info == null || info.isExpired();
    }

    @Test
    @DisplayName("FM4(PLAN-totp-required-admin.md 결정 5): ADMIN 본인 해제(복구 코드) = 인증기 교체 — 등록 삭제 + 본인 전 세션 만료(다음 로그인은 강제 등록)")
    void adminDisableExpiresOwnSessions() {
        Member admin = newMember("totp-svc-admin", Role.ROLE_ADMIN);
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, admin.getId(), clock);
        String sessionId = registerSession(admin);
        try {
            service.disable(principal(admin), enrollment.recoveryCodes().get(0), PASSWORD, IP, URI);

            assertThat(memberTotpRepository.existsById(admin.getId())).isFalse();
            assertThat(auditCount("TOTP_DISABLE", admin.getId())).isEqualTo(1);
            assertThat(sessionExpired(sessionId)).as("필수 역할의 해제는 2단계 없는 완전 권한 세션을 남기지 않는다").isTrue();
        } finally {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }

    @Test
    @DisplayName("FM4: ADMIN 본인 해제의 판정 실패(오답)는 세션을 끊지 않고 등록이 남는다")
    void adminDisableFailureKeepsSessions() {
        Member admin = newMember("totp-svc-admin", Role.ROLE_ADMIN);
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, admin.getId(), clock);
        String sessionId = registerSession(admin);
        try {
            assertThatThrownBy(() -> service.disable(principal(admin), TotpTestSupport.wrongCode(enrollment.secret(), clock), PASSWORD, IP, URI))
                    .isInstanceOf(InvalidRequestException.class);

            assertThat(memberTotpRepository.existsById(admin.getId())).isTrue();
            assertThat(sessionExpired(sessionId)).isFalse();
        } finally {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }

    @Test
    @DisplayName("FM4: MANAGER(필수 대상 아님) 본인 해제는 지금처럼 세션을 유지한다")
    void managerDisableKeepsSessions() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        String sessionId = registerSession(member);
        try {
            service.disable(principal(member), code(enrollment.secret()), PASSWORD, IP, URI);

            assertThat(enabled()).isFalse();
            assertThat(sessionExpired(sessionId)).isFalse();
        } finally {
            sessionRegistry.removeSessionInformation(sessionId);
        }
    }

    @Test
    @DisplayName("상태의 required는 필수 역할(ADMIN)만 true")
    void statusReportsRequired() {
        Member admin = newMember("totp-svc-admin", Role.ROLE_ADMIN);
        assertThat(service.status(admin.getId()).required()).isTrue();
        assertThat(service.status(member.getId()).required()).isFalse();
    }

    @Test
    @DisplayName("사용 중이 아니면 본인 해제는 409")
    void disableWhenNotEnabled() {
        assertThatThrownBy(() -> service.disable(principal(member), "123456", PASSWORD, IP, URI)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("FM2(v6): 이미 인가돼 회원 행 잠금에서 기다리던 해제 요청은, 그 사이 계정이 잠기면 올바른 자격정보여도 판정 없이 409이고 등록이 남는다")
    void waitingRequestRechecksStateAfterLock() throws Exception {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        CustomUserDetails authorized = principal(member); // 잠기기 전에 인가를 통과한 요청의 주체
        String good = code(enrollment.secret());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holder = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, member.getId());
                holder.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.update("UPDATE member SET status = 'LOCKED', locked_at = NOW(6) WHERE id = ?", member.getId());
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> waiting = executor.submit(() -> {
                try {
                    service.disable(authorized, good, PASSWORD, IP, URI);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword()).awaitWaiterFor(holder.get(), Duration.ofSeconds(8));
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(enabled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId())).isZero();
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

    // ---- 복구 코드 재발급 (PLAN-totp-recovery-code-reissue.md) ----

    private static final String REISSUE_URI = "/admin/api/members/me/totp/recovery-codes";

    private int totpFailures() {
        return jdbc.queryForObject("SELECT failed_attempts FROM member_totp WHERE member_id = ?", Integer.class, member.getId());
    }

    private long lastUsedStep() {
        return jdbc.queryForObject("SELECT last_used_step FROM member_totp WHERE member_id = ?", Long.class, member.getId());
    }

    private List<String> storedHashes() {
        return jdbc.queryForList("SELECT code_hash FROM member_totp_recovery_code WHERE member_id = ?", String.class, member.getId());
    }

    @Test
    @DisplayName("S1·S5·FM1: 재발급 → 새 코드 10개(해시만 저장, 옛 행·사용 기록 행 모두 삭제), 실패 횟수 0·쓴 스텝 기록, 감사 1행, 로그인 실패 횟수는 그대로")
    void reissueRoundTrip() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        jdbc.update("UPDATE member_totp_recovery_code SET used_at = NOW(6) WHERE member_id = ? AND code_hash = ?",
                member.getId(), RecoveryCodes.hash(enrollment.recoveryCodes().get(0)));
        jdbc.update("UPDATE member_totp SET failed_attempts = 3 WHERE member_id = ?", member.getId());
        jdbc.update("UPDATE member SET failed_login_count = 2 WHERE id = ?", member.getId());
        long step = new TotpCodeVerifier(clock).currentStep();

        List<String> codes = service.reissueRecoveryCodes(principal(member), " " + code(enrollment.secret()) + " ", PASSWORD, IP, REISSUE_URI);

        assertThat(codes).hasSize(10).doesNotHaveDuplicates().doesNotContainAnyElementsOf(enrollment.recoveryCodes());
        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(codes.stream().map(RecoveryCodes::hash).toList());
        assertThat(service.status(member.getId()).remainingRecoveryCodes()).isEqualTo(10);
        assertThat(totpFailures()).as("FM1: 삭제 전에 초기화해야 반영된다").isZero();
        assertThat(lastUsedStep()).as("FM1: 쓴 스텝 기록").isGreaterThanOrEqualTo(step);
        assertThat(auditCount("TOTP_RECOVERY_CODES_REISSUE", member.getId())).isEqualTo(1);
        assertThat(loginFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("S2b: 이미 쓴 스텝의 코드(로그인 직후 앱에 떠 있는 코드)는 400 '이미 사용한 코드' — 실패로 세지 않고 코드는 그대로")
    void reissueWithReusedCode() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        String good = code(enrollment.secret());
        service.reissueRecoveryCodes(principal(member), good, PASSWORD, IP, REISSUE_URI);
        List<String> hashes = storedHashes();

        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), good, PASSWORD, IP, REISSUE_URI))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("이미 사용한 코드");
        assertThat(totpFailures()).isZero();
        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(hashes);
        assertThat(auditCount("TOTP_RECOVERY_CODES_REISSUE", member.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("S4·FM3·FM5: 비밀번호 오답은 코드를 판정·소비하지 않고 실패 횟수 +1(커밋), 5회째 LOCKED·실패 횟수 0·등록과 옛 코드 유지·감사 없음")
    void reissueReauthFailuresCount() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        String good = code(enrollment.secret());
        String wrong = TotpTestSupport.wrongCode(enrollment.secret(), clock);
        long stepBefore = lastUsedStep();
        List<String> hashes = storedHashes();

        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), good, "wrong", IP, REISSUE_URI))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(totpFailures()).isEqualTo(1);
        assertThat(lastUsedStep()).as("FM3: 비밀번호가 틀리면 코드를 소비하지 않는다").isEqualTo(stepBefore);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), wrong, PASSWORD, IP, REISSUE_URI))
                    .isInstanceOf(InvalidRequestException.class);
        }
        assertThat(totpFailures()).isEqualTo(4);
        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), "12a456", PASSWORD, IP, REISSUE_URI))
                .as("형식 오류도 오답으로 센다").isInstanceOf(InvalidRequestException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, member.getId())).isEqualTo("LOCKED");
        assertThat(totpFailures()).isZero();
        assertThat(enabled()).isTrue();
        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(hashes);
        assertThat(auditCount("TOTP_RECOVERY_CODES_REISSUE", member.getId())).isZero();
    }

    @Test
    @DisplayName("FM2: 유효한 복구 코드는 재인증으로 받지 않는다 — 400·실패 횟수 +1·그 복구 코드는 소비되지 않고 코드 10행 그대로")
    void reissueRejectsRecoveryCode() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        String recovery = enrollment.recoveryCodes().get(2);
        List<String> hashes = storedHashes();

        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), recovery, PASSWORD, IP, REISSUE_URI))
                .isInstanceOf(InvalidRequestException.class);

        assertThat(totpFailures()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM member_totp_recovery_code WHERE member_id = ? AND code_hash = ? AND used_at IS NULL",
                Integer.class, member.getId(), RecoveryCodes.hash(recovery))).isEqualTo(1);
        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(hashes);
    }

    @Test
    @DisplayName("S6: 사용 중이 아니면 재발급은 409이고 아무것도 쓰지 않는다")
    void reissueWhenNotEnabled() {
        assertThatThrownBy(() -> service.reissueRecoveryCodes(principal(member), "123456", PASSWORD, IP, REISSUE_URI))
                .isInstanceOf(ConflictException.class);
        assertThat(loginFailures()).isZero();
        assertThat(storedHashes()).isEmpty();
    }

    @Test
    @DisplayName("FM7·S2: ±1 스텝 허용 — 이전 스텝 코드와 현재 스텝 코드로 연속 재발급하면 둘 다 성공하고 마지막 묶음만 유효(첫 묶음·옛 코드로는 로그인 2단계 실패, 마지막 묶음으로는 성공)")
    void consecutiveReissueKeepsOnlyLastBatch() {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        long step = new TotpCodeVerifier(clock).currentStep();

        List<String> first = service.reissueRecoveryCodes(principal(member), TotpCodeVerifier.codeAt(enrollment.secret(), step - 1), PASSWORD, IP, REISSUE_URI);
        List<String> second = service.reissueRecoveryCodes(principal(member), TotpCodeVerifier.codeAt(enrollment.secret(), step), PASSWORD, IP, REISSUE_URI);

        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(second.stream().map(RecoveryCodes::hash).toList());
        assertThat(totpLoginService.verify(principal(member), first.get(0), IP, "/admin/login/totp").status())
                .isEqualTo(com.cms.config.auth.TotpLoginService.Status.WRONG_CODE);
        assertThat(totpLoginService.verify(principal(member), enrollment.recoveryCodes().get(0), IP, "/admin/login/totp").status())
                .isEqualTo(com.cms.config.auth.TotpLoginService.Status.WRONG_CODE);
        assertThat(totpLoginService.verify(principal(member), second.get(0), IP, "/admin/login/totp").status())
                .isEqualTo(com.cms.config.auth.TotpLoginService.Status.SUCCESS);
        assertThat(auditCount("TOTP_RECOVERY_CODES_REISSUE", member.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("FM4: 이미 인가돼 회원 행 잠금에서 기다리던 재발급은, 그 사이 계정이 잠기면 올바른 자격정보여도 판정 없이 409이고 코드·카운터가 그대로다")
    void waitingReissueRechecksStateAfterLock() throws Exception {
        Enrollment enrollment = TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);
        CustomUserDetails authorized = principal(member);
        String good = code(enrollment.secret());
        long stepBefore = lastUsedStep();
        List<String> hashes = storedHashes();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong holder = new AtomicLong();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> locker = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, member.getId());
                holder.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                jdbc.update("UPDATE member SET status = 'LOCKED', locked_at = NOW(6) WHERE id = ?", member.getId());
                held.countDown();
                await(release);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> waiting = executor.submit(() -> {
                try {
                    service.reissueRecoveryCodes(authorized, good, PASSWORD, IP, REISSUE_URI);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });
            new LockWaitObserver(connectionDetails.getJdbcUrl(), connectionDetails.getPassword()).awaitWaiterFor(holder.get(), Duration.ofSeconds(8));
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
            assertThat(waiting.get(10, TimeUnit.SECONDS)).isInstanceOf(ConflictException.class);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        assertThat(storedHashes()).containsExactlyInAnyOrderElementsOf(hashes);
        assertThat(totpFailures()).isZero();
        assertThat(lastUsedStep()).isEqualTo(stepBefore);
        assertThat(auditCount("TOTP_RECOVERY_CODES_REISSUE", member.getId())).isZero();
    }

    // ---- ADMIN 해제 ----

    @Test
    @DisplayName("S7: ADMIN 해제 → 대상 등록 삭제 + 감사 TOTP_RESET 1행(수행자·대상) — 반복 해제는 감사 행을 더하지 않는다")
    void adminReset() {
        Member admin = newMember("totp-svc-admin", Role.ROLE_ADMIN);
        TotpTestSupport.enroll(memberTotpRepository, recoveryCodeRepository, cipher, member.getId(), clock);

        service.resetByAdmin(principal(admin), member.getId(), IP, "/admin/api/members/" + member.getId() + "/totp");
        service.resetByAdmin(principal(admin), member.getId(), IP, "/admin/api/members/" + member.getId() + "/totp");

        assertThat(enabled()).isFalse();
        assertThat(recoveryCodeRepository.countByMemberIdAndUsedAtIsNull(member.getId())).isZero();
        assertThat(jdbc.queryForList("SELECT CONCAT(action_id, '|', action_user_id, '|', action_result) FROM admin_action_log"
                + " WHERE action_type = 'TOTP_RESET' AND target_id = ?", String.class, member.getId()))
                .containsExactly(admin.getId() + "|" + admin.getUserId() + "|SUCCESS");
    }

    @Test
    @DisplayName("ADMIN 해제: 본인 대상은 400, 없는 회원은 404")
    void adminResetGuards() {
        Member admin = newMember("totp-svc-admin2", Role.ROLE_ADMIN);
        assertThatThrownBy(() -> service.resetByAdmin(principal(admin), admin.getId(), IP, URI)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.resetByAdmin(principal(admin), 999_999_999L, IP, URI)).isInstanceOf(ResourceNotFoundException.class);
    }
}
