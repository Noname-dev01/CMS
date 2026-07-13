package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.config.auth.AdminSessionRevokeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-13T03:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);
    private static final String BASE_URL = "http://localhost:8080";
    private static final String PLAIN_TOKEN = "0123456789abcdef".repeat(4); // 64자 hex

    @Mock
    MemberRepository memberRepository;

    @Mock
    PasswordEncoder passwordEncoder;

    @Mock
    JavaMailSender mailSender;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    PlatformTransactionManager transactionManager;

    PasswordResetService passwordResetService;

    @BeforeEach
    void setUp() {
        // 실제 TransactionTemplate + mock 트랜잭션 매니저 — 콜백이 그대로 실행된다.
        // 메일 executor는 동기(SyncTaskExecutor)로 두어 기존 발송 검증을 단순하게 유지한다.
        passwordResetService = new PasswordResetService(
                memberRepository,
                passwordEncoder,
                mailSender,
                eventPublisher,
                new TransactionTemplate(transactionManager),
                Clock.fixed(FIXED_INSTANT, ZONE),
                new SyncTaskExecutor(),
                BASE_URL
        );
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Member member(MemberStatus status, Role role) {
        return Member.builder()
                .id(1L)
                .userId("admin01")
                .pwd("encoded")
                .userName("홍길동")
                .email("Admin01@Test.com") // DB 저장값은 정규화되어 있지 않을 수 있다
                .userType(role)
                .status(status)
                .createDate(NOW.minusDays(1))
                .updateDate(NOW.minusDays(1))
                .build();
    }

    // ==================== requestReset ====================

    @Test
    @DisplayName("정상 발급: 해시가 저장되고(평문 아님) 메일은 DB 저장 이메일로 발송된다")
    void requestReset_success_issuesHashAndSendsMail() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        // 저장값은 64자 hex — SecureRandom 평문 토큰과 다르다(해시)
        assertNotNull(member.getResetToken());
        assertTrue(member.getResetToken().matches("^[0-9a-f]{64}$"));
        assertEquals(NOW.plusMinutes(30), member.getResetTokenExpiryAt());

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage message = captor.getValue();
        // 수신자는 정규화 입력값이 아니라 DB 저장값
        assertArrayEquals(new String[]{"Admin01@Test.com"}, message.getTo());
        String text = message.getText();
        assertNotNull(text);
        assertTrue(text.contains(BASE_URL + "/admin/password-reset/confirm#token="),
                "메일 본문에 fragment 형식 링크가 있어야 한다");
        // 본문 링크의 토큰은 평문 — 저장된 해시가 본문에 노출되면 안 된다
        assertFalse(text.contains(member.getResetToken()));
        // 링크의 평문 토큰을 해시하면 저장값과 일치한다 (왕복 검증)
        String plainInMail = text.substring(text.indexOf("#token=") + "#token=".length()).split("\\s")[0];
        assertEquals(member.getResetToken(), sha256Hex(plainInMail));
    }

    @Test
    @DisplayName("대문자·공백 포함 이메일도 trim + lowercase(Locale.ROOT) 정규화 후 조회한다")
    void requestReset_normalizesEmail() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("  ADMIN01@Test.com  ", "127.0.0.1");

        verify(memberRepository).findByEmailForUpdate("admin01@test.com");
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("미존재 이메일은 조용히 종료 — 발송 없음, 예외 없음 (계정 열거 방지)")
    void requestReset_unknownEmail_silentlyReturns() {
        given(memberRepository.findByEmailForUpdate(anyString())).willReturn(Optional.empty());

        assertDoesNotThrow(() -> passwordResetService.requestReset("nobody@test.com", "127.0.0.1"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("LOCKED 계정은 발송하지 않는다")
    void requestReset_lockedMember_notSent() {
        Member member = member(MemberStatus.LOCKED, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        assertNull(member.getResetToken());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("allowlist 외 역할(ROLE_USER)은 발송하지 않는다")
    void requestReset_nonAllowlistedRole_notSent() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_USER);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        assertNull(member.getResetToken());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("PASSWORD_EXPIRED 계정은 발송 대상이다")
    void requestReset_passwordExpired_isSent() {
        Member member = member(MemberStatus.PASSWORD_EXPIRED, Role.ROLE_MANAGER);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        assertNotNull(member.getResetToken());
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("쿨다운: 발급 60초 이내 재요청은 토큰 미변경·메일 미발송")
    void requestReset_withinCooldown_noReissue() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        String existingHash = sha256Hex("existing-token");
        // 30초 전 발급 → expiry = 발급 + 30분
        member.issueResetToken(existingHash, NOW.minusSeconds(30).plusMinutes(30));
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        assertEquals(existingHash, member.getResetToken());
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("쿨다운 경과(발급 61초 후) 재요청은 새 토큰으로 덮어쓰고 재발송한다")
    void requestReset_afterCooldown_reissues() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        String existingHash = sha256Hex("existing-token");
        member.issueResetToken(existingHash, NOW.minusSeconds(61).plusMinutes(30));
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        passwordResetService.requestReset("admin01@test.com", "127.0.0.1");

        assertNotEquals(existingHash, member.getResetToken());
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("발송 실패 시 내가 발급한 해시로만 조건부 클리어하고 예외를 내지 않는다 (응답 균일성)")
    void requestReset_mailFailure_conditionallyClearsToken() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));
        willThrow(new MailSendException("smtp down")).given(mailSender).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> passwordResetService.requestReset("admin01@test.com", "127.0.0.1"));

        // 클리어는 발급된 그 해시를 조건으로 호출된다
        verify(memberRepository).clearResetTokenIfMatches(1L, member.getResetToken());
    }

    @Test
    @DisplayName("발송 실패 후 클리어 자체가 실패해도 예외를 내지 않는다 — 토큰은 자연 만료에 맡긴다")
    void requestReset_clearFailure_stillSilent() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));
        willThrow(new MailSendException("smtp down")).given(mailSender).send(any(SimpleMailMessage.class));
        given(memberRepository.clearResetTokenIfMatches(anyLong(), anyString()))
                .willThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> passwordResetService.requestReset("admin01@test.com", "127.0.0.1"));
    }

    @Test
    @DisplayName("발급 잠금 실패(락 타임아웃)도 조용히 종료 — 발송 없음, 예외 없음")
    void requestReset_lockFailure_silentlyReturns() {
        given(memberRepository.findByEmailForUpdate(anyString()))
                .willThrow(new PessimisticLockingFailureException("lock timeout"));

        assertDoesNotThrow(() -> passwordResetService.requestReset("admin01@test.com", "127.0.0.1"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("메일 발송은 executor에 위임된다 — 응답 반환 시점에는 send가 실행되지 않는다 (타이밍 부채널 완화)")
    void requestReset_mailSendIsDeferredToExecutor() {
        List<Runnable> deferred = new ArrayList<>();
        PasswordResetService service = new PasswordResetService(
                memberRepository, passwordEncoder, mailSender, eventPublisher,
                new TransactionTemplate(transactionManager), Clock.fixed(FIXED_INSTANT, ZONE),
                deferred::add, BASE_URL);

        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        service.requestReset("admin01@test.com", "127.0.0.1");

        // 요청 처리(응답 경로)에서는 SMTP 호출이 없어야 한다
        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertEquals(1, deferred.size());

        // executor가 나중에 실행하면 그때 발송된다
        deferred.forEach(Runnable::run);
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("executor 제출 실패도 조용히 삼키고 토큰을 조건부 클리어한다 (응답 균일성)")
    void requestReset_executorRejection_stillSilentAndClearsToken() {
        PasswordResetService service = new PasswordResetService(
                memberRepository, passwordEncoder, mailSender, eventPublisher,
                new TransactionTemplate(transactionManager), Clock.fixed(FIXED_INSTANT, ZONE),
                task -> { throw new TaskRejectedException("pool saturated"); }, BASE_URL);

        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        assertDoesNotThrow(() -> service.requestReset("admin01@test.com", "127.0.0.1"));

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        verify(memberRepository).clearResetTokenIfMatches(1L, member.getResetToken());
    }

    // ==================== resetPassword ====================

    private Member memberWithValidToken(MemberStatus status, Role role) {
        Member member = member(status, role);
        member.issueResetToken(sha256Hex(PLAIN_TOKEN), NOW.plusMinutes(10));
        return member;
    }

    private void stubTokenLookup(Member member) {
        given(memberRepository.findIdsByResetToken(sha256Hex(PLAIN_TOKEN))).willReturn(List.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));
    }

    @Test
    @DisplayName("재설정 성공: 비밀번호 교체 + 토큰 클리어 + 세션 만료 이벤트 발행")
    void resetPassword_success() {
        Member member = memberWithValidToken(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        stubTokenLookup(member);
        given(passwordEncoder.encode("NewPassword1!")).willReturn("encodedNew");

        passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!");

        assertEquals("encodedNew", member.getPwd());
        assertNull(member.getResetToken(), "사용한 토큰은 즉시 클리어되어야 한다");
        assertNull(member.getResetTokenExpiryAt());

        ArgumentCaptor<AdminSessionRevokeEvent> captor = ArgumentCaptor.forClass(AdminSessionRevokeEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(1L, captor.getValue().targetMemberId());
    }

    @Test
    @DisplayName("PASSWORD_EXPIRED 계정은 재설정 성공 시 ACTIVE로 복귀한다 (다른 상태는 전이 없음)")
    void resetPassword_passwordExpired_becomesActive() {
        Member member = memberWithValidToken(MemberStatus.PASSWORD_EXPIRED, Role.ROLE_MANAGER);
        stubTokenLookup(member);
        given(passwordEncoder.encode("NewPassword1!")).willReturn("encodedNew");

        passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!");

        assertEquals(MemberStatus.ACTIVE, member.getStatus());
    }

    @Test
    @DisplayName("새 비밀번호와 확인이 불일치하면 400")
    void resetPassword_confirmMismatch_throws() {
        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "Different1!"));
        verify(memberRepository, never()).findIdsByResetToken(anyString());
    }

    @Test
    @DisplayName("존재하지 않는 토큰은 400")
    void resetPassword_unknownToken_throws() {
        given(memberRepository.findIdsByResetToken(anyString())).willReturn(List.of());

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
    }

    @Test
    @DisplayName("같은 해시의 토큰 행이 2건 이상이면 500이 아니라 400 (데이터 오염 방어)")
    void resetPassword_duplicateTokenRows_returns400() {
        given(memberRepository.findIdsByResetToken(anyString())).willReturn(List.of(1L, 2L));

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
        verify(memberRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("만료된 토큰은 400")
    void resetPassword_expiredToken_throws() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        member.issueResetToken(sha256Hex(PLAIN_TOKEN), NOW.minusSeconds(1)); // 이미 만료
        stubTokenLookup(member);

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
        assertNotNull(member.getResetToken(), "재검증 거부 시 토큰은 유지된다 (롤백과 일관)");
    }

    @Test
    @DisplayName("잠금 후 재검증: 락 획득 시점에 토큰이 이미 클리어/교체됐으면 400 (동시 제출 직렬화)")
    void resetPassword_tokenChangedAfterLock_throws() {
        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        member.issueResetToken(sha256Hex("someone-else-token"), NOW.plusMinutes(10)); // 다른 해시
        given(memberRepository.findIdsByResetToken(sha256Hex(PLAIN_TOKEN))).willReturn(List.of(1L));
        given(memberRepository.findByIdForUpdate(1L)).willReturn(Optional.of(member));

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
    }

    @Test
    @DisplayName("토큰 발급 후 계정이 잠기면(LOCKED) 재검증에서 400")
    void resetPassword_lockedAfterIssue_throws() {
        Member member = memberWithValidToken(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        member.changeStatus(MemberStatus.LOCKED);
        stubTokenLookup(member);

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("토큰 발급 후 역할이 대상 외로 바뀌면 재검증에서 400")
    void resetPassword_demotedAfterIssue_throws() {
        Member member = memberWithValidToken(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        member.changeRole(Role.ROLE_USER);
        stubTokenLookup(member);

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
    }

    @Test
    @DisplayName("행 잠금 실패는 409가 아니라 동일 메시지의 400으로 감싼다 (유효 토큰 존재 신호 차단)")
    void resetPassword_lockFailure_wrappedAs400() {
        given(memberRepository.findIdsByResetToken(anyString())).willReturn(List.of(1L));
        given(memberRepository.findByIdForUpdate(1L))
                .willThrow(new PessimisticLockingFailureException("lock timeout"));

        assertThrows(InvalidRequestException.class,
                () -> passwordResetService.resetPassword(PLAIN_TOKEN, "NewPassword1!", "NewPassword1!"));
    }

    // ==================== base-url 검증 ====================

    @Test
    @DisplayName("base-url 이상값(비 http/https, userinfo, query, fragment)은 기동 실패")
    void constructor_invalidBaseUrl_fails() {
        List<String> invalid = List.of(
                "ftp://cms.example.com",
                "http://user:pw@cms.example.com",
                "http://cms.example.com?debug=1",
                "http://cms.example.com#frag",
                "not a url",
                "/relative/path"
        );
        for (String url : invalid) {
            assertThrows(IllegalStateException.class, () -> new PasswordResetService(
                            memberRepository, passwordEncoder, mailSender, eventPublisher,
                            new TransactionTemplate(transactionManager), Clock.fixed(FIXED_INSTANT, ZONE),
                            new SyncTaskExecutor(), url),
                    "기동 실패해야 하는 base-url: " + url);
        }
    }

    @Test
    @DisplayName("base-url 후행 슬래시는 제거되어 //admin 링크가 생기지 않는다")
    void requestReset_baseUrlTrailingSlash_normalized() {
        PasswordResetService service = new PasswordResetService(
                memberRepository, passwordEncoder, mailSender, eventPublisher,
                new TransactionTemplate(transactionManager), Clock.fixed(FIXED_INSTANT, ZONE),
                new SyncTaskExecutor(), "http://localhost:8080/");

        Member member = member(MemberStatus.ACTIVE, Role.ROLE_ADMIN);
        given(memberRepository.findByEmailForUpdate("admin01@test.com")).willReturn(Optional.of(member));

        service.requestReset("admin01@test.com", "127.0.0.1");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertTrue(captor.getValue().getText().contains("http://localhost:8080/admin/password-reset/confirm#token="));
        assertFalse(captor.getValue().getText().contains("8080//admin"));
    }
}
