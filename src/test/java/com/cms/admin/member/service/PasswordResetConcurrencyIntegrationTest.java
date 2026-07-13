package com.cms.admin.member.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.common.exception.InvalidRequestException;
import com.cms.support.CmsTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 비밀번호 재설정의 동시성 제어를 실제 MariaDB로 검증하는 통합 테스트.
 * Mockito 단위 테스트는 잠금 호출 여부만 확인할 뿐 실제 FOR UPDATE 직렬화와
 * 스칼라 id 조회의 1차 캐시 회피를 증명하지 못한다.
 *
 * <p>두 스레드가 독립 트랜잭션과 비관적 락을 획득해야 하므로 {@code @Transactional}을 붙이지
 * 않는다. 생성한 row는 {@link #cleanUp()}에서 수동 삭제한다.
 *
 * <p>로컬 실행: DB(make dev-db) 기동 + DB_PASS/MAIL_USER/MAIL_PASS 환경변수 설정 필요.
 * CI는 ci.yml에서 자동 주입된다.
 */
// classes를 명시하면 중첩 @TestConfiguration 자동 감지가 꺼진다 — 반드시 함께 나열해야 한다
@SpringBootTest(classes = {
        CmsTestApplication.class,
        PasswordResetConcurrencyIntegrationTest.SyncMailExecutorConfig.class
})
class PasswordResetConcurrencyIntegrationTest {

    @Autowired
    PasswordResetService passwordResetService;

    @Autowired
    MemberRepository memberRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    /**
     * 토큰 만료 시각은 반드시 서비스와 같은 Clock(KST 고정 빈)으로 만들어야 한다.
     * 시스템 기본 타임존의 LocalDateTime.now()를 쓰면 UTC 러너(CI)에서 9시간 차이로
     * 만료 판정돼 두 스레드 모두 거부된다 (로컬 KST에서만 통과하는 함정).
     */
    @Autowired
    Clock clock;

    // 실제 SMTP 발송을 차단하고 발송 횟수를 검증한다
    @MockitoBean
    JavaMailSender mailSender;

    /**
     * 메일 발송 executor를 동기로 교체 — 이 테스트의 검증 대상은 DB 잠금 직렬화이며,
     * 발송이 백그라운드 스레드로 넘어가면 verify(mailSender) 시점이 비결정적이 된다.
     *
     * <p>Executor 빈이 존재하면 Boot의 applicationTaskExecutor 자동 구성이 물러나므로
     * ({@code @ConditionalOnMissingBean(Executor.class)}) 컨텍스트에 executor는 이것 하나만 남는다.
     */
    @TestConfiguration
    static class SyncMailExecutorConfig {
        @Bean
        TaskExecutor syncMailExecutor() {
            return new SyncTaskExecutor();
        }
    }

    private final List<Long> createdMemberIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Member createMember(String tokenHash, LocalDateTime tokenExpiryAt) {
        LocalDateTime now = LocalDateTime.now(clock);
        String unique = "pwreset-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd(passwordEncoder.encode("OldPassword1!"))
                .userName("재설정동시성")
                .email(unique + "@pwreset.test")
                .userType(Role.ROLE_MANAGER)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .resetToken(tokenHash)
                .resetTokenExpiryAt(tokenExpiryAt)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("같은 토큰 동시 제출 2건 중 정확히 1건만 성공한다 (행 잠금 + 잠금 후 재검증 직렬화)")
    void concurrentResetWithSameToken_onlyOneSucceeds() throws Exception {
        String plainToken = HexFormat.of().formatHex(
                java.security.SecureRandom.getInstanceStrong().generateSeed(32));
        Member member = createMember(sha256Hex(plainToken), LocalDateTime.now(clock).plusMinutes(30));

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable submit = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                passwordResetService.resetPassword(plainToken, "NewPassword1!", "NewPassword1!");
                successCount.incrementAndGet();
            } catch (InvalidRequestException expected) {
                // 잠금 후 재검증 거부 — 정상적인 직렬화 결과
                rejectedCount.incrementAndGet();
            } catch (PessimisticLockingFailureException lockFailure) {
                // 커밋 시점 락 충돌 — 거부로 집계
                rejectedCount.incrementAndGet();
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };

        executor.submit(submit);
        executor.submit(submit);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "두 스레드가 제한 시간 내에 종료되어야 한다");

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        assertEquals(1, successCount.get(), "같은 토큰 동시 제출은 정확히 1건만 성공해야 한다");
        assertEquals(1, rejectedCount.get(), "나머지 1건은 400으로 거부되어야 한다");

        Member finalMember = memberRepository.findById(member.getId()).orElseThrow();
        assertNull(finalMember.getResetToken(), "사용한 토큰은 클리어되어야 한다");
        assertTrue(passwordEncoder.matches("NewPassword1!", finalMember.getPwd()),
                "비밀번호가 새 값으로 교체되어야 한다");
    }

    @Test
    @DisplayName("같은 이메일 동시 발급 요청 2건 중 1건만 발송된다 (findByEmailForUpdate 직렬화 — 쿨다운 원자성)")
    void concurrentRequestWithSameEmail_onlyOneMailSent() throws Exception {
        Member member = createMember(null, null);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicReference<Exception> unexpectedError = new AtomicReference<>();

        Runnable request = () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
                passwordResetService.requestReset(member.getEmail(), "127.0.0.1");
            } catch (Exception e) {
                unexpectedError.set(e);
            }
        };

        executor.submit(request);
        executor.submit(request);
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS), "두 스레드가 제한 시간 내에 종료되어야 한다");

        if (unexpectedError.get() != null) {
            throw unexpectedError.get();
        }

        // 먼저 잠근 쪽만 발급·발송, 나중 요청은 쿨다운에서 조용히 종료
        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));

        Member finalMember = memberRepository.findById(member.getId()).orElseThrow();
        assertNotNull(finalMember.getResetToken(), "토큰이 정확히 한 번 발급되어야 한다");
    }
}
