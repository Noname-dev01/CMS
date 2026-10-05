package com.cms.admin.message;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.message.dto.request.MessageSendRequest;
import com.cms.admin.message.repository.AdminMessageRepository;
import com.cms.admin.message.service.AdminMessageService;
import com.cms.common.exception.RateLimitedException;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.zaxxer.hikari.HikariDataSource;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 쪽지 보내기의 <b>트랜잭션 계약</b>과 <b>실패 경로</b>를 리포지토리 프록시 계측으로 확인한다(PLAN-admin-message.md §5-F·§5-G·§7 ⑥,
 * R1-2·R2-3·R2-4·R3-1·R4-1·R4-2). 계측은 기존 {@code MenuConcurrencyIntegrationTest}의 방식(리포지토리 프록시 advice로 {@code beforeCommit} 예외 주입)과 같다.
 *
 * <ul>
 *   <li>경합 창을 넓히려고 한도 집계 직후 지연을 주입한다 — 지연이 없으면 병렬 요청이 충분히 겹치지 않아 상태 행 잠금 제거 같은 회귀를 잡지 못한다
 *       (변이 실험으로 확인: 잠금을 빼도 지연 없는 병렬 시험은 통과했다)</li>
 *   <li>실패 주입은 <b>실제 커밋 전에</b> 실패하는 {@code beforeCommit} 동기화다 — 실제 {@code commit()} 뒤에 예외를 던지는 DataSource 프록시는
 *       "커밋 응답 유실" 모사라 롤백 시험의 선례가 아니다</li>
 * </ul>
 */
// 분당 한도를 4로 낮추고 스레드를 Hikari 기본 풀(10)보다 적게 쓴다 — 풀이 동시 서비스 트랜잭션을 한도와 같은 수로 제한하면 잠금이 없어도 우연히 통과한다
@SpringBootTest(classes = CmsTestApplication.class, properties = "cms.message.send-per-minute=4")
@AutoConfigureMockMvc
@Import(AdminMessageInstrumentedIntegrationTest.Instrumentation.class)
class AdminMessageInstrumentedIntegrationTest extends MariaDbContainerSupport {

    private static final String SEND = "/admin/api/members/me/messages";
    private static final String AUDIT_FAIL_MESSAGE = "쪽지 발송에 실패했습니다.";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MemberRepository memberRepository;
    @Autowired AdminMessageService adminMessageService;
    @Autowired com.cms.admin.message.service.MessageActorGuard actorGuard;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    private Member sender;
    private Member recipient;
    private final List<Long> memberIds = new ArrayList<>();
    private ListAppender<ILoggingEvent> logAppender;

    // ===================== 계측 =====================

    /** 리포지토리 호출 앞뒤에 시험 계측을 끼운다 — 정적 노브는 시험마다 {@link #resetProbe()}로 되돌린다. */
    static final class Probe {
        static volatile long countDelayMillis;
        static volatile boolean failBeforeCommit;
        static volatile Long deleteRecipientBeforeSave;
        static volatile JdbcTemplate jdbc;
        static volatile DataSource dataSource;
        static final List<String> sessionIsolation = Collections.synchronizedList(new ArrayList<>());
        static final List<Integer> springIsolation = Collections.synchronizedList(new ArrayList<>());
        static final List<Integer> activeConnections = Collections.synchronizedList(new ArrayList<>());

        static Object around(MethodInvocation invocation) throws Throwable {
            String name = invocation.getMethod().getName();
            if ("save".equals(name) && deleteRecipientBeforeSave != null) {
                // 수신자 검증이 끝난 뒤 INSERT 직전에 수신자 행을 지워(별도 커넥션) 실제 FK 위반을 만든다
                try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                    statement.execute("DELETE FROM member WHERE id = " + deleteRecipientBeforeSave);
                }
            }
            Object result = invocation.proceed();
            switch (name) {
                case "lockSenderState" -> {
                    // 서비스 트랜잭션의 첫 DB 문장 — 이 시점의 격리 수준·연결 점유를 관측한다
                    sessionIsolation.add(jdbc.queryForObject("SELECT @@session.tx_isolation", String.class));
                    Integer level = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
                    springIsolation.add(level);
                    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
                    activeConnections.add(hikari.getHikariPoolMXBean().getActiveConnections());
                }
                case "countSendLogSince" -> {
                    if (countDelayMillis > 0) {
                        Thread.sleep(countDelayMillis); // 집계와 저장 사이의 경합 창을 넓힌다
                    }
                }
                case "insertSendLog" -> {
                    if (failBeforeCommit) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException("커밋 직전 실패 모사");
                            }
                        });
                    }
                }
                default -> { }
            }
            return result;
        }
    }

    @TestConfiguration
    static class Instrumentation {
        @Bean
        static BeanPostProcessor adminMessageRepositoryInstrumentation() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AdminMessageRepository) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) Probe::around);
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }

    @BeforeEach
    void setUp() {
        resetProbe();
        Probe.jdbc = jdbc;
        Probe.dataSource = dataSource;
        sender = member("inst-sender", Role.ROLE_ADMIN);
        recipient = member("inst-recipient", Role.ROLE_MANAGER);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logAppender);
    }

    @AfterEach
    void cleanUp() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logAppender);
        resetProbe();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type = 'MESSAGE_SEND' AND action_id IN ("
                + memberIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")");
        TestMembers.delete(jdbc, memberIds);
    }

    private static void resetProbe() {
        Probe.countDelayMillis = 0;
        Probe.failBeforeCommit = false;
        Probe.deleteRecipientBeforeSave = null;
        Probe.sessionIsolation.clear();
        Probe.springIsolation.clear();
        Probe.activeConnections.clear();
    }

    private Member member(String prefix, Role role) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        memberIds.add(saved.getId());
        return saved;
    }

    private MvcResult send(Member from, long recipientId, String title, String body) throws Exception {
        return mockMvc.perform(post(SEND).with(TestMembers.asMember(from)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":" + recipientId + ",\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")).andReturn();
    }

    private int sendStatus(Member from, long recipientId, String title) throws Exception {
        return send(from, recipientId, title, "본문").getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private List<Map<String, Object>> auditRows(Member actor) {
        return jdbc.queryForList("SELECT * FROM admin_action_log WHERE action_type = 'MESSAGE_SEND' AND action_id = ? ORDER BY id", actor.getId());
    }

    /** 캡처한 로그 이벤트의 메시지·Throwable(스택·원인 체인 메시지 포함) 전체 텍스트. */
    private String allLoggedText() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : new ArrayList<>(logAppender.list)) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    // ===================== 트랜잭션 계약 =====================

    @Test
    @DisplayName("트랜잭션 계약: 서비스는 새 트랜잭션·READ COMMITTED로 실행되고, 가드·서비스·감사가 순차라 최대 동시 점유 연결은 1이다")
    void send_runsInReadCommittedTransactionWithSingleConnection() throws Exception {
        assertThat(sendStatus(sender, recipient.getId(), "제목")).isEqualTo(201);

        assertThat(Probe.sessionIsolation).containsExactly("READ-COMMITTED"); // DB 세션의 실제 격리 수준(10.11 변수명 tx_isolation)
        assertThat(Probe.springIsolation).containsExactly(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(Probe.activeConnections).containsExactly(1); // 요청 전체의 연결 개수가 아니라 동시 점유 수
    }

    @Test
    @DisplayName("REQUIRES_NEW: 호출자가 외부 RR 트랜잭션에서 스냅샷을 연 채 호출해도 서비스는 직전 커밋된 발송 이력을 보고 한도를 적용한다")
    void send_ignoresOuterSnapshot() throws Exception {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            // 외부 트랜잭션의 일관 읽기 스냅샷을 연다(이력 0건)
            Long before = jdbc.queryForObject("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", Long.class, sender.getId());
            assertThat(before).isZero();

            // 다른 연결에서 10건을 커밋한다(외부 스냅샷에는 보이지 않는다). 시각은 DB NOW()(컨테이너 UTC)가 아니라 앱 KST Clock이다 —
            // 서비스가 한도 창을 앱 Clock으로 계산하므로 시험 데이터도 같은 시각원이어야 한다
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO admin_message_sender_state (member_id) VALUES (" + sender.getId()
                        + ") ON DUPLICATE KEY UPDATE member_id = member_id");
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (?, ?)")) {
                    for (int i = 0; i < 10; i++) {
                        insert.setLong(1, sender.getId());
                        insert.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now(clock)));
                        insert.executeUpdate();
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", Long.class, sender.getId()))
                    .as("외부 RR 스냅샷은 커밋된 10건을 보지 못한다").isZero();

            // 서비스는 REQUIRES_NEW라 새 트랜잭션에서 10건을 보고 거부한다(REQUIRED였다면 0건으로 보고 발송했을 것이다)
            adminMessageService.send(sender.getId(), MessageSendRequest.builder()
                    .recipientId(recipient.getId()).title("외부 스냅샷").body("본문").build());
        })).isInstanceOf(RateLimitedException.class);

        assertThat(count("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", sender.getId())).isZero();
    }

    @Test
    @DisplayName("가드 반례(R3-2): 외부 RR 트랜잭션이 ACTIVE 상태를 읽어 둔 뒤 다른 연결에서 비활성화가 커밋돼도, 같은 외부 트랜잭션에서 호출한 가드는 현재 DB 값을 읽어 403이다")
    void guard_ignoresOuterSnapshot() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            // 외부 트랜잭션의 일관 읽기 스냅샷에 sender의 상태(ACTIVE)가 고정된다
            String before = jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, sender.getId());
            assertThat(before).isEqualTo("ACTIVE");

            // 다른 연결에서 비활성화를 커밋한다(가드 실행 전에 이미 커밋된 변경)
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("UPDATE member SET status = 'DISABLED' WHERE id = " + sender.getId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, sender.getId()))
                    .as("외부 RR 스냅샷은 비활성화를 보지 못한다").isEqualTo("ACTIVE");

            // 가드가 외부 트랜잭션에 참여했다면 낡은 ACTIVE를 읽어 통과시켰을 것이다 — 새 트랜잭션·RC 스칼라 조회라 현재 값(DISABLED)을 읽는다
            actorGuard.requireActive(sender.getId());
        })).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    // ===================== 동시성 =====================

    @Test
    @DisplayName("동시 발송(경합 창 확대): 한도(4) 집계 직후 지연을 주입해도 같은 발신자 병렬 8건 중 정확히 4건만 201이다 — 상태 행 잠금 제거 시 실패해야 하는 시험")
    void concurrentSendsWithWidenedWindow_limitIsExact() throws Exception {
        Probe.countDelayMillis = 120;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String title = "지연동시" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return sendStatus(sender, recipient.getId(), title);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(120, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(4);
            assertThat(statuses).filteredOn(s -> s == 429).hasSize(4);
            assertThat(count("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", sender.getId())).isEqualTo(4);
            assertThat(count("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", sender.getId())).isEqualTo(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("상호 발송(경합 창 확대): A→B와 B→A 동시 발송은 집계 지연이 있어도 교착 없이 모두 201이다(409 0건)")
    void mutualSendsWithWidenedWindow_noDeadlock() throws Exception {
        Probe.countDelayMillis = 100;
        Member a = sender;
        Member b = recipient;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 3; round++) {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                int r = round;
                Future<Integer> aToB = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return sendStatus(a, b.getId(), "A→B " + r);
                });
                Future<Integer> bToA = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return sendStatus(b, a.getId(), "B→A " + r);
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();

                assertThat(aToB.get(120, TimeUnit.SECONDS)).as("라운드 %d A→B", round).isEqualTo(201);
                assertThat(bToA.get(120, TimeUnit.SECONDS)).as("라운드 %d B→A", round).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ===================== 실패 경로: 롤백·감사·로그 =====================

    @Test
    @DisplayName("커밋 직전 실패: 쪽지·이력이 함께 롤백되고 SUCCESS 감사 없이 FAIL 1건(고정 문구)만 남으며, 응답·서버 로그에 제목·본문이 없다")
    void commitStageFailure_rollsBackAndAuditsFixedMessage() throws Exception {
        String marker = "커밋표식" + System.nanoTime();
        Probe.failBeforeCommit = true;

        MvcResult result = send(sender, recipient.getId(), marker + "제목", marker + "본문");

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        // 실제 커밋 전에 실패했으므로 쪽지·이력이 롤백됐다(커밋 응답 유실 시뮬레이션과 다르다)
        assertThat(count("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", sender.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", sender.getId())).isZero();

        List<Map<String, Object>> audit = auditRows(sender);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action_result")).isEqualTo("FAIL");
        assertThat(audit.get(0).get("error_message")).isEqualTo(AUDIT_FAIL_MESSAGE); // e.getMessage()("커밋 직전 실패 모사")가 아니다
        assertThat(audit.get(0).get("target_id")).isNull();
        assertThat(audit.get(0).values().toString()).doesNotContain(marker);

        assertThat(result.getResponse().getContentAsString()).doesNotContain(marker);
        assertThat(allLoggedText()).doesNotContain(marker);
    }

    @Test
    @DisplayName("FK 위반(검증 직후 수신자 행이 사라짐): 저장되지 않고 FAIL 감사는 고정 문구이며, 응답·서버 로그 전체(메시지·Throwable·cause)에 제목·본문이 없다")
    void foreignKeyFailure_neverLeaksContentIntoLogs() throws Exception {
        String marker = "FK표식" + System.nanoTime();
        // 바인딩 값 로깅·SQL DEBUG가 꺼져 있다는 R5-1 사전 조건
        assertThat(LoggerFactory.getLogger("org.hibernate.orm.jdbc.bind").isTraceEnabled()).isFalse();
        assertThat(LoggerFactory.getLogger("org.hibernate.SQL").isDebugEnabled()).isFalse();
        Probe.deleteRecipientBeforeSave = recipient.getId();

        MvcResult result = send(sender, recipient.getId(), marker + "제목", marker + "본문");

        assertThat(result.getResponse().getStatus()).isNotEqualTo(201);
        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(count("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", sender.getId())).isZero();
        assertThat(count("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", sender.getId())).isZero();

        List<Map<String, Object>> audit = auditRows(sender);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action_result")).isEqualTo("FAIL");
        assertThat(audit.get(0).get("error_message")).isEqualTo(AUDIT_FAIL_MESSAGE);
        assertThat(audit.get(0).values().toString()).doesNotContain(marker);

        assertThat(result.getResponse().getContentAsString()).doesNotContain(marker);
        assertThat(allLoggedText()).as("FK 위반 오류의 서버 로그 출력 전체").doesNotContain(marker);
    }
}
