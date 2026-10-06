package com.cms.admin.message;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.message.repository.AdminMessageRepository;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code innodb_snapshot_isolation} ON·OFF 두 설정에서 쪽지 삭제·발송의 동시성 계약이 같음을 확인하는 공통 시험(PLAN-admin-message.md §5-D·§7 ⑥,
 * R1-5·R2-3·R3-4·R4-6). 설정은 <b>연결 초기화 SQL</b>({@code spring.datasource.hikari.connection-init-sql})로 풀의 모든 연결에 걸고, 서브클래스마다
 * <b>전용 Spring 컨텍스트</b>를 쓰며 {@code @DirtiesContext(AFTER_CLASS)}로 컨텍스트와 풀을 폐기한다 — {@code SET SESSION}은 풀의 물리 연결에 남고
 * HikariCP는 임의 세션 변수를 복원하지 않으므로, 공유 컨텍스트의 연결에 설정하면 다른 시험을 오염시킨다.
 * 서버가 이 변수를 지원하는 것은 <b>필수 사전 조건</b>이다(고정 이미지 MariaDB 10.11.19 — 미지원이면 초기화 SQL에서 컨텍스트 기동이 실패한다).
 */
abstract class AbstractMessageSnapshotIsolationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired MemberRepository memberRepository;
    @Autowired Clock clock;

    private Member alice;
    private Member bob;
    private final List<Long> memberIds = new ArrayList<>();

    /** 이 서브클래스 컨텍스트의 {@code innodb_snapshot_isolation} 기대값. */
    abstract boolean snapshotIsolationExpected();

    // ===================== 계측 =====================

    static final class Probe {
        static volatile long afterMarkDeletedBySenderDelayMillis;
        static volatile long afterCountDelayMillis;
        static volatile Runnable afterCount;
        static final AtomicInteger physicalDeletes = new AtomicInteger();

        static void reset() {
            afterMarkDeletedBySenderDelayMillis = 0;
            afterCountDelayMillis = 0;
            afterCount = null;
            physicalDeletes.set(0);
        }

        static Object around(MethodInvocation invocation) throws Throwable {
            Object result = invocation.proceed();
            switch (invocation.getMethod().getName()) {
                case "markDeletedBySender" -> {
                    if (afterMarkDeletedBySenderDelayMillis > 0) {
                        Thread.sleep(afterMarkDeletedBySenderDelayMillis); // 행 잠금을 쥔 채 대기 — 상대 쪽 삭제가 이 사이에 도착하게 한다
                    }
                }
                case "deleteIfBothDeleted" -> physicalDeletes.addAndGet((Integer) result);
                case "countSendLogSince" -> {
                    if (afterCountDelayMillis > 0) {
                        Thread.sleep(afterCountDelayMillis);
                    }
                    Runnable hook = afterCount;
                    if (hook != null) {
                        hook.run();
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
        static BeanPostProcessor adminMessageRepositoryProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof AdminMessageRepository) {
                        ProxyFactory factory = new ProxyFactory(bean);
                        factory.addAdvice((MethodInterceptor) Probe::around);
                        return factory.getProxy();
                    }
                    return bean;
                }
            };
        }
    }

    // ===================== 준비·정리 =====================

    @BeforeEach
    void setUp() {
        Probe.reset();
        alice = member("snap-alice", Role.ROLE_ADMIN);
        bob = member("snap-bob", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        Probe.reset();
        jdbc.update("DELETE FROM admin_action_log WHERE action_type = 'MESSAGE_SEND' AND action_id IN ("
                + memberIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")");
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(String prefix, Role role) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        memberIds.add(saved.getId());
        return saved;
    }

    private long insertMessage(Member from, Member to) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO admin_message (sender_id, recipient_id, title, body, create_date) VALUES (?, ?, '제목', '본문', ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, from.getId());
            ps.setLong(2, to.getId());
            ps.setTimestamp(3, Timestamp.valueOf(LocalDateTime.now(clock)));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private int deleteAs(Member who, long id) throws Exception {
        return mockMvc.perform(delete("/admin/api/members/me/messages/" + id)
                .with(TestMembers.asMember(who)).with(csrf())).andReturn().getResponse().getStatus();
    }

    // ===================== 시험 =====================

    @Test
    @DisplayName("사전 조건: 이 컨텍스트의 모든 연결이 기대한 innodb_snapshot_isolation 값이다(지원 필수 — 건너뛰지 않는다)")
    void precondition_sessionVariableMatchesContext() throws Exception {
        for (int i = 0; i < 3; i++) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT @@session.innodb_snapshot_isolation")) {
                rs.next();
                String value = rs.getString(1);
                boolean on = "1".equals(value) || "ON".equalsIgnoreCase(value);
                assertThat(on).as("연결 %d의 innodb_snapshot_isolation=%s", i, value).isEqualTo(snapshotIsolationExpected());
            }
        }
    }

    @Test
    @DisplayName("동시 양쪽 삭제: 먼저 지우는 쪽이 행 잠금을 쥐고 있어도 두 요청 모두 204이고, 물리 DELETE 영향 행 합계는 1이며 최종 행이 없다")
    void bothSidesDeletingConcurrently_deletesPhysicallyExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                long id = insertMessage(alice, bob);
                Probe.physicalDeletes.set(0);
                Probe.afterMarkDeletedBySenderDelayMillis = 300; // 발신측 UPDATE 뒤 잠금을 쥔 채 대기

                CountDownLatch senderStarted = new CountDownLatch(1);
                Future<Integer> sender = pool.submit(() -> {
                    senderStarted.countDown();
                    return deleteAs(alice, id);
                });
                assertThat(senderStarted.await(10, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(100); // 발신측이 UPDATE를 끝내고 대기에 들어가도록
                Future<Integer> recipient = pool.submit(() -> deleteAs(bob, id));

                assertThat(sender.get(60, TimeUnit.SECONDS)).as("라운드 %d 발신측", round).isEqualTo(204);
                assertThat(recipient.get(60, TimeUnit.SECONDS)).as("라운드 %d 수신측", round).isEqualTo(204);

                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_message WHERE id = ?", Long.class, id))
                        .as("라운드 %d 최종 행 부재", round).isZero();
                assertThat(Probe.physicalDeletes.get()).as("라운드 %d 물리 DELETE 영향 행 합계", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("발송 중 수신자 행이 수정돼도(집계 직후 커밋) 발송은 오류 1020·500 없이 201이다 — READ COMMITTED 트랜잭션은 이 설정에 영향받지 않는다")
    void sendSucceedsWhenRecipientModifiedAfterCount() throws Exception {
        Probe.afterCountDelayMillis = 50;
        Probe.afterCount = () -> {
            // 서비스 트랜잭션이 집계(일관 읽기)를 마친 뒤, 다른 연결에서 수신자 행을 수정·커밋한다
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("UPDATE member SET user_name = '집계 후 변경' WHERE id = " + bob.getId());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        int status = mockMvc.perform(post("/admin/api/members/me/messages").with(TestMembers.asMember(alice)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":" + bob.getId() + ",\"title\":\"제목\",\"body\":\"본문\"}")).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", Long.class, alice.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_name FROM member WHERE id = ?", String.class, bob.getId())).isEqualTo("집계 후 변경");
    }
}
