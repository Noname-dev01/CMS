package com.cms.admin.message;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.message.domain.MessageBox;
import com.cms.admin.message.repository.AdminMessageRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 무기한 보관에서 <b>한쪽만 삭제된 행이 대량으로 쌓인 회원</b>의 목록·미읽음 수·보낸 목록·이력 집계 비용(PLAN-admin-message.md §4·§7 ⑧, R1-11).
 * 통과 기준은 "인덱스를 사용했다"가 아니라 <b>실제 InnoDB 핸들러 읽기 수</b>다 — 삭제된 행을 대량으로 건너뛰며 읽으면 인덱스를 써도 비싸기 때문이다.
 * 시험 데이터: 보이는 20건이 가장 오래되고, 그보다 최신 10만 건은 해당 쪽에서 삭제됐다(첫 페이지가 삭제된 행을 대량 스캔하기 쉬운 최악의 배치).
 * 대조군은 삭제 열을 인덱스에 넣지 않은 단순 인덱스로 같은 질의가 이 기준을 위반함을 보여, 측정 장치가 위반을 실제로 잡는다는 것을 증명한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class AdminMessageIndexCostIntegrationTest extends MariaDbContainerSupport {

    /** 통과 기준 — 첫 페이지·커서 페이지·집계의 핸들러 읽기 합. 정상 인덱스는 보이는 행(수십 건)만 읽는다. */
    private static final long MAX_HANDLER_READS = 300;
    private static final int DELETED_ROWS = 100_000;
    private static final int VISIBLE_ROWS = 20;

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminMessageRepository repository;
    @Autowired MemberRepository memberRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Clock clock;

    private Member sender;
    private Member recipient;
    private Member sender2;     // 보낸 쪽이 지운 데이터용 쌍
    private Member recipient2;
    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sender = member("cost-s1");
        recipient = member("cost-r1");
        sender2 = member("cost-s2");
        recipient2 = member("cost-r2");
        LocalDateTime now = LocalDateTime.now(clock);

        // 쌍 1: 받는 쪽(recipient)이 최신 10만 건을 지웠고 오래된 20건만 보인다(미읽음)
        insertBatch(sender, recipient, VISIBLE_ROWS, null, null, now);
        insertBatch(sender, recipient, DELETED_ROWS, null, now, now);
        // 쌍 2: 보내는 쪽(sender2)이 최신 10만 건을 지웠고 오래된 20건만 보인다
        insertBatch(sender2, recipient2, VISIBLE_ROWS, null, null, now);
        insertBatch(sender2, recipient2, DELETED_ROWS, now, null, now);
        // 발송 이력: 창(24시간) 밖의 오래된 이력 10만 건 + 창 안 5건
        jdbc.update("INSERT INTO admin_message_sender_state (member_id) VALUES (?)", sender.getId());
        jdbc.update("INSERT INTO admin_message_send_log (sender_id, sent_at) SELECT ?, ? FROM seq_1_to_" + DELETED_ROWS,
                sender.getId(), now.minusDays(3));
        for (int i = 0; i < 5; i++) {
            jdbc.update("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (?, ?)", sender.getId(), now.minusMinutes(i));
        }
        jdbc.execute("ANALYZE TABLE admin_message, admin_message_send_log");
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(String prefix) {
        Member saved = TestMembers.save(memberRepository, prefix, Role.ROLE_MANAGER);
        memberIds.add(saved.getId());
        return saved;
    }

    /** seq 엔진으로 {@code count}건을 한 문장에 넣는다. */
    private void insertBatch(Member from, Member to, int count, LocalDateTime senderDeleted, LocalDateTime recipientDeleted, LocalDateTime createDate) {
        jdbc.update("INSERT INTO admin_message (sender_id, recipient_id, title, body, sender_deleted_at, recipient_deleted_at, create_date) "
                        + "SELECT ?, ?, CONCAT('t', seq), 'b', ?, ?, ? FROM seq_1_to_" + count,
                from.getId(), to.getId(), senderDeleted, recipientDeleted, createDate);
    }

    /** 트랜잭션 안(같은 JDBC 연결)에서 작업 전후의 InnoDB 핸들러 읽기 합의 차이를 잰다. */
    private <T> Measured<T> measure(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        return template.execute(status -> {
            long before = handlerReads();
            T result = work.get();
            long after = handlerReads();
            return new Measured<>(result, after - before);
        });
    }

    private long handlerReads() {
        Long sum = jdbc.query("SHOW SESSION STATUS WHERE Variable_name IN ('Handler_read_first','Handler_read_key','Handler_read_last',"
                + "'Handler_read_next','Handler_read_prev','Handler_read_rnd','Handler_read_rnd_next')", rs -> {
            long total = 0;
            while (rs.next()) {
                total += rs.getLong(2);
            }
            return total;
        });
        return sum == null ? 0 : sum;
    }

    private record Measured<T>(T value, long reads) {
    }

    // ===================== 통과 기준 =====================

    @Test
    @DisplayName("받은 목록: 최신 10만 건이 수신측 삭제된 회원도 첫 페이지·커서 페이지가 보이는 행만 읽는다(핸들러 읽기 수 기준)")
    void inboxPages_doNotScanDeletedRows() {
        Measured<?> first = measure(() -> repository.findPage(recipient.getId(), MessageBox.INBOX, null, 21));
        @SuppressWarnings("unchecked")
        List<Object> firstRows = (List<Object>) first.value();
        assertThat(firstRows).hasSize(VISIBLE_ROWS);
        assertThat(first.reads()).as("받은 목록 첫 페이지 핸들러 읽기").isLessThan(MAX_HANDLER_READS);

        long maxVisibleId = jdbc.queryForObject("SELECT MAX(id) FROM admin_message WHERE recipient_id = ? AND recipient_deleted_at IS NULL",
                Long.class, recipient.getId());
        Measured<?> cursor = measure(() -> repository.findPage(recipient.getId(), MessageBox.INBOX, maxVisibleId, 21));
        assertThat(cursor.reads()).as("받은 목록 beforeId 페이지 핸들러 읽기").isLessThan(MAX_HANDLER_READS);
    }

    @Test
    @DisplayName("미읽음 수: 삭제된 10만 건을 읽지 않고 보이는 미읽음 20건만 센다")
    void unreadCount_doesNotScanDeletedRows() {
        Measured<Long> unread = measure(() -> repository.countUnread(recipient.getId()));

        assertThat(unread.value()).isEqualTo(VISIBLE_ROWS);
        assertThat(unread.reads()).as("미읽음 수 핸들러 읽기").isLessThan(MAX_HANDLER_READS);
    }

    @Test
    @DisplayName("보낸 목록: 최신 10만 건이 발신측 삭제된 회원도 보이는 행만 읽는다")
    void sentPage_doesNotScanDeletedRows() {
        Measured<?> sent = measure(() -> repository.findPage(sender2.getId(), MessageBox.SENT, null, 21));
        @SuppressWarnings("unchecked")
        List<Object> rows = (List<Object>) sent.value();

        assertThat(rows).hasSize(VISIBLE_ROWS);
        assertThat(sent.reads()).as("보낸 목록 첫 페이지 핸들러 읽기").isLessThan(MAX_HANDLER_READS);
    }

    @Test
    @DisplayName("발송 이력 집계: 창 밖의 오래된 10만 건을 읽지 않고 창 안 이력만 센다(분당·일일 한도 집계)")
    void sendLogCount_doesNotScanOldRows() {
        LocalDateTime now = LocalDateTime.now(clock);

        Measured<Long> perMinute = measure(() -> repository.countSendLogSince(sender.getId(), now.minusMinutes(1)));
        Measured<Long> perDay = measure(() -> repository.countSendLogSince(sender.getId(), now.minusHours(24)));

        assertThat(perDay.value()).isEqualTo(5);
        assertThat(perMinute.reads()).as("분당 한도 집계 핸들러 읽기").isLessThan(MAX_HANDLER_READS);
        assertThat(perDay.reads()).as("일일 한도 집계 핸들러 읽기").isLessThan(MAX_HANDLER_READS);
    }

    // ===================== 대조군(측정 장치 검증) =====================

    @Test
    @DisplayName("대조군: 삭제 열이 없는 단순 인덱스(recipient_id, id)는 같은 질의가 삭제된 10만 건을 훑어 통과 기준을 크게 넘는다 — 측정이 위반을 잡는다")
    void naiveIndexControl_violatesTheCriterion() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        Long naiveReads = template.execute(status -> {
            // 임시 테이블은 연결별이라 트랜잭션 안의 같은 연결에서만 보인다
            jdbc.execute("CREATE TEMPORARY TABLE naive_inbox (id bigint NOT NULL, recipient_id bigint NOT NULL, "
                    + "recipient_deleted_at datetime(6) NULL, PRIMARY KEY (id), KEY idx_naive (recipient_id, id)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO naive_inbox SELECT id, recipient_id, recipient_deleted_at FROM admin_message WHERE recipient_id = ?",
                    recipient.getId());

            long before = handlerReads();
            List<Long> ids = jdbc.queryForList("SELECT id FROM naive_inbox WHERE recipient_id = ? AND recipient_deleted_at IS NULL "
                    + "ORDER BY id DESC LIMIT 21", Long.class, recipient.getId());
            long after = handlerReads();
            assertThat(ids).hasSize(VISIBLE_ROWS);
            return after - before;
        });

        assertThat(naiveReads).as("단순 인덱스는 삭제된 최신 10만 건을 건너뛰며 읽는다").isGreaterThan(DELETED_ROWS / 2);
        assertThat(naiveReads).isGreaterThan(MAX_HANDLER_READS * 100);
    }
}
