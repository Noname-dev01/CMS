package com.cms.admin.member.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 상한이 걸린 executor 위에서 {@code PasswordResetService}가 SMTP 무응답 + 큐 포화를 어떻게 다루는지 검증한다
 * (PLAN-mail-executor-bound.md 결정 3·5·6). 운영 yml 값(4/4/20) 자체의 적용은
 * {@code MailExecutorPoolConfigurationTest}가 맡고, 여기서는 같은 형태(core=max, 유한 큐)의 작은 executor로
 * "실행 N·대기 M·초과 거부"와 그 결과를 실측한다 — 큐를 무제한으로 바꾸면 거부 단언이 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetExecutorSaturationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Instant FIXED_INSTANT = Instant.parse("2026-07-13T03:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZONE);
    private static final String BASE_URL = "http://localhost:8080";

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

    private ThreadPoolTaskExecutor executor;
    private final CountDownLatch release = new CountDownLatch(1);
    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        release.countDown(); // 실패 시에도 블록된 발송 스레드를 풀어 준다
        if (executor != null) {
            executor.shutdown();
        }
        serviceLogger.detachAppender(logAppender);
    }

    /** 운영 yml과 같은 형태(core=max, 유한 큐)의 작은 executor — AbortPolicy(기본)가 포화 시 거부한다. */
    private ThreadPoolTaskExecutor boundedExecutor(int poolSize, int queueCapacity) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(poolSize);
        e.setMaxPoolSize(poolSize);
        e.setQueueCapacity(queueCapacity);
        e.setThreadNamePrefix("mail-test-");
        e.initialize();
        return e;
    }

    private PasswordResetService service(Clock clock) {
        return new PasswordResetService(memberRepository, passwordEncoder, mailSender, eventPublisher,
                new TransactionTemplate(transactionManager), clock, executor, BASE_URL);
    }

    private Member member(long id) {
        return Member.builder()
                .id(id)
                .userId("admin" + id)
                .pwd("encoded")
                .userName("관리자" + id)
                .email("admin0" + id + "@test.com")
                .userType(Role.ROLE_ADMIN)
                .status(MemberStatus.ACTIVE)
                .createDate(NOW.minusDays(1))
                .updateDate(NOW.minusDays(1))
                .build();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** SMTP 무응답 흉내 — send는 release 래치가 풀릴 때까지 반환하지 않는다. */
    private CountDownLatch blockSendUntilReleased(int expectedEntries) {
        CountDownLatch entered = new CountDownLatch(expectedEntries);
        doAnswer(inv -> {
            entered.countDown();
            release.await(30, TimeUnit.SECONDS);
            return null;
        }).when(mailSender).send(any(SimpleMailMessage.class));
        return entered;
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("SMTP 무응답 + 포화: 실행 2·대기 2·초과 1건 거부, 전 요청 예외 없이 반환, 거부된 건만 토큰 클리어, 로그에 민감정보 없음")
    void saturation_boundsExecutionAndQueue_rejectsOverflow_keepsUniformReturn() throws Exception {
        executor = boundedExecutor(2, 2);
        PasswordResetService service = service(Clock.fixed(FIXED_INSTANT, ZONE));

        List<Member> members = new ArrayList<>();
        for (long id = 1; id <= 5; id++) {
            Member m = member(id);
            members.add(m);
            given(memberRepository.findByEmailForUpdate(m.getEmail())).willReturn(Optional.of(m));
        }
        CountDownLatch entered = blockSendUntilReleased(2);

        // 1·2번: 실행 중(send에서 블록) — 실제로 진입한 뒤에 다음 요청을 보낸다
        service.requestReset(members.get(0).getEmail(), "127.0.0.1");
        service.requestReset(members.get(1).getEmail(), "127.0.0.1");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

        // 3·4번: 큐 대기, 5번: 포화 → 거부. 모두 예외 없이 반환해야 한다(컨트롤러는 항상 200)
        service.requestReset(members.get(2).getEmail(), "127.0.0.1");
        service.requestReset(members.get(3).getEmail(), "127.0.0.1");
        service.requestReset(members.get(4).getEmail(), "127.0.0.1");

        // 래치 유지 중 상태: 실행 정확히 2, 큐 정확히 2 (큐가 무제한이면 5번째가 거부되지 않아 아래 단언이 실패)
        assertThat(executor.getActiveCount()).isEqualTo(2);
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(2);
        verify(memberRepository, times(1)).clearResetTokenIfMatches(anyLong(), anyString());
        verify(memberRepository).clearResetTokenIfMatches(eq(5L), eq(members.get(4).getResetToken()));
        verify(mailSender, times(2)).send(any(SimpleMailMessage.class)); // 동시 진입 = core

        // 래치 해제 → 수락된 4건(실행 2 + 대기 2)은 전부 발송 완료, 거부된 1건은 발송 없음
        release.countDown();
        verify(mailSender, timeout(10_000).times(4)).send(any(SimpleMailMessage.class));
        verify(memberRepository, times(1)).clearResetTokenIfMatches(anyLong(), anyString());

        // 로그에 이메일 원문·토큰 해시·링크가 없다 (거부 로그는 memberId·예외 타입만)
        List<String> messages = logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(messages).anyMatch(m -> m.contains("발송 제출 실패") && m.contains("memberId=5")
                && m.contains("TaskRejectedException"));
        for (Member m : members) {
            assertThat(messages).noneMatch(line -> line.contains(m.getEmail()));
            assertThat(messages).noneMatch(line -> m.getResetToken() != null && line.contains(m.getResetToken()));
        }
        assertThat(messages).noneMatch(line -> line.contains("#token="));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("잔여 위험 고정: 같은 계정 재요청이 거부되면 T2만 클리어되고, 앞서 수락된 T1 메일은 DB에 없는 토큰의 링크로 발송된다")
    void sameAccountReRequestRejected_clearsOnlyNewToken_earlierAcceptedMailCarriesStaleLink() throws Exception {
        executor = boundedExecutor(1, 1);
        AtomicReference<Instant> now = new AtomicReference<>(FIXED_INSTANT);
        Clock movable = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZONE;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        PasswordResetService service = service(movable);

        Member blocker = member(1);
        Member account = member(2);
        given(memberRepository.findByEmailForUpdate(blocker.getEmail())).willReturn(Optional.of(blocker));
        given(memberRepository.findByEmailForUpdate(account.getEmail())).willReturn(Optional.of(account));
        // 실제 repository의 조건부 클리어를 흉내 — 해시가 일치할 때만 지운다
        doAnswer(inv -> {
            Long id = inv.getArgument(0);
            String hash = inv.getArgument(1);
            if (id.equals(account.getId()) && hash.equals(account.getResetToken())) {
                account.clearResetToken(NOW);
                return 1;
            }
            return 0;
        }).when(memberRepository).clearResetTokenIfMatches(anyLong(), anyString());
        CountDownLatch entered = blockSendUntilReleased(1);

        // 1) blocker가 유일한 스레드를 점유, 2) 계정 A의 T1이 발급되어 큐에 수락된다
        service.requestReset(blocker.getEmail(), "127.0.0.1");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        service.requestReset(account.getEmail(), "127.0.0.1");
        String t1Hash = account.getResetToken();
        assertThat(t1Hash).isNotNull();
        assertThat(executor.getThreadPoolExecutor().getQueue()).hasSize(1);

        // 3) 쿨다운(60초) 경과 후 재요청 → T2로 교체되지만 큐 포화로 제출 거부 → T2 클리어
        now.set(FIXED_INSTANT.plus(Duration.ofSeconds(61)));
        service.requestReset(account.getEmail(), "127.0.0.1");
        verify(memberRepository).clearResetTokenIfMatches(eq(account.getId()), argThat(
                hash -> !hash.equals(t1Hash)));
        verify(memberRepository, never()).clearResetTokenIfMatches(anyLong(), eq(t1Hash));
        assertThat(account.getResetToken()).as("T2는 클리어되어 DB에 유효 토큰이 없다").isNull();

        // 4) 래치 해제 → 큐에 있던 T1 메일이 그대로 발송되지만 그 링크의 토큰은 DB에 존재하지 않는다
        release.countDown();
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, timeout(10_000).times(2)).send(sent.capture());
        SimpleMailMessage accountMail = sent.getAllValues().stream()
                .filter(m -> m.getTo() != null && m.getTo()[0].equals(account.getEmail()))
                .findFirst().orElseThrow();
        String linkedPlainToken = accountMail.getText().substring(accountMail.getText().indexOf("#token=") + 7)
                .lines().findFirst().orElseThrow();
        assertThat(sha256Hex(linkedPlainToken)).as("발송된 링크는 T1의 것").isEqualTo(t1Hash);
        assertThat(account.getResetToken()).as("DB에는 T1도 T2도 없다 → 이 링크로는 재설정 불가").isNull();
    }
}
