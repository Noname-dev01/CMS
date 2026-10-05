package com.cms.admin.message;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 쪽지 보내기·수신자 검색을 실제 SecurityConfig·가드·한도·MariaDB로 확인한다(PLAN-admin-message.md §5-C·§5-E·§5-F·§5-G·§5-J·§7 ③⑥).
 * 시험마다 실제 회원·쪽지·이력 행을 만들고 {@link TestMembers#delete}로 지운다(FK가 RESTRICT라 종속 행 먼저).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminMessageSendIntegrationTest extends MariaDbContainerSupport {

    private static final String SEND = "/admin/api/members/me/messages";
    private static final String SEARCH = "/admin/api/members/me/message-recipients";
    private static final String INELIGIBLE = "쪽지를 받을 수 없는 수신자입니다.";
    private static final String AUDIT_FAIL_MESSAGE = "쪽지 발송에 실패했습니다.";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired Clock clock;

    private Member sender;
    private Member recipient;
    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sender = member("msg-sender", Role.ROLE_ADMIN);
        recipient = member("msg-recipient", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM admin_action_log WHERE action_type = 'MESSAGE_SEND' AND action_id IN ("
                + memberIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")");
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(String prefix, Role role) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        memberIds.add(saved.getId());
        return saved;
    }

    private String json(long recipientId, String title, String body) throws Exception {
        return "{\"recipientId\":" + recipientId + ",\"title\":" + quote(title) + ",\"body\":" + quote(body) + "}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private MvcResult send(Member from, long recipientId, String title, String body) throws Exception {
        return mockMvc.perform(post(SEND).with(TestMembers.asMember(from)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json(recipientId, title, body))).andReturn();
    }

    private int sendStatus(Member from, long recipientId, String title) throws Exception {
        return send(from, recipientId, title, "본문").getResponse().getStatus();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long messagesBy(Member from) {
        return count("SELECT COUNT(*) FROM admin_message WHERE sender_id = ?", from.getId());
    }

    private long logsBy(Member from) {
        return count("SELECT COUNT(*) FROM admin_message_send_log WHERE sender_id = ?", from.getId());
    }

    private List<Map<String, Object>> auditRows(Member actor) {
        return jdbc.queryForList("SELECT * FROM admin_action_log WHERE action_type = 'MESSAGE_SEND' AND action_id = ? ORDER BY id",
                actor.getId());
    }

    // ===================== 성공 경로 =====================

    @Test
    @DisplayName("보내기: 쪽지·발송 이력·상태 행이 저장되고 201이며, 감사에는 수신자 ID만 남고 제목·본문은 어떤 컬럼에도 없다")
    void send_success_savesRowsAndAuditsWithoutContent() throws Exception {
        String titleMarker = "제목표식-" + System.nanoTime();
        String bodyMarker = "본문표식-" + System.nanoTime();

        MvcResult result = send(sender, recipient.getId(), titleMarker, bodyMarker);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(result.getResponse().getHeader("Location")).startsWith("http://localhost" + SEND + "/");
        assertThat(result.getResponse().getContentAsString()).contains(titleMarker).doesNotContain(bodyMarker);

        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM admin_message WHERE sender_id = ?", sender.getId());
        assertThat(saved.get("recipient_id")).isEqualTo(recipient.getId());
        assertThat(saved.get("title")).isEqualTo(titleMarker);
        assertThat(saved.get("body")).isEqualTo(bodyMarker);
        assertThat(saved.get("read_at")).isNull();
        assertThat(saved.get("sender_deleted_at")).isNull();
        assertThat(saved.get("recipient_deleted_at")).isNull();
        assertThat(logsBy(sender)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM admin_message_sender_state WHERE member_id = ?", sender.getId())).isEqualTo(1);

        List<Map<String, Object>> audit = auditRows(sender);
        assertThat(audit).hasSize(1);
        Map<String, Object> row = audit.get(0);
        assertThat(row.get("action_result")).isEqualTo("SUCCESS");
        assertThat(row.get("target_type")).isEqualTo("MEMBER");
        assertThat(row.get("target_id")).isEqualTo(recipient.getId());
        assertThat(row.get("target_label")).isNull();
        assertThat(row.get("error_message")).isNull();
        // 어떤 컬럼에도 제목·본문이 없다(D12)
        assertThat(row.values().toString()).doesNotContain(titleMarker).doesNotContain(bodyMarker);
    }

    @Test
    @DisplayName("보내기: 본문은 HTML·스크립트를 포함해도 원문 그대로 저장되고 개행은 LF로 정규화된다(서버는 이스케이프하지 않는다)")
    void send_storesBodyVerbatimWithNormalizedNewlines() throws Exception {
        String body = "<script>alert(1)</script>\r\n<img src=x onerror=alert(1)>\rjavascript:alert(1) 😀";

        assertThat(mockMvc.perform(post(SEND).with(TestMembers.asMember(sender)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":" + recipient.getId() + ",\"title\":\"  앞뒤 공백 제목  \",\"body\":"
                        + quote(body).replace("\r", "\\r") + "}")).andReturn().getResponse().getStatus()).isEqualTo(201);

        Map<String, Object> saved = jdbc.queryForMap("SELECT title, body FROM admin_message WHERE sender_id = ?", sender.getId());
        assertThat(saved.get("title")).isEqualTo("앞뒤 공백 제목");
        assertThat(saved.get("body")).isEqualTo("<script>alert(1)</script>\n<img src=x onerror=alert(1)>\njavascript:alert(1) 😀");
    }

    @Test
    @DisplayName("4바이트 문자(이모지) 제목·본문이 손상·치환 없이 왕복 저장된다(연결 utf8mb4)")
    void send_roundTripsFourByteCharacters() throws Exception {
        String title = "안녕 😀🚀 𠮷";
        String body = "본문 😀\n👨\u200d👩\u200d👧 끝";

        assertThat(sendStatus(sender, recipient.getId(), title)).isEqualTo(201);
        assertThat(send(sender, recipient.getId(), title, body).getResponse().getStatus()).isEqualTo(201);

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT title, body FROM admin_message WHERE sender_id = ? ORDER BY id", sender.getId());
        assertThat(rows.get(1).get("title")).isEqualTo(title);
        assertThat(rows.get(1).get("body")).isEqualTo(body);
        // 연결이 4바이트를 지원하는 문자셋·strict 모드인지(R5-1 사전 조건)
        Map<String, Object> session = jdbc.queryForMap("SELECT @@session.character_set_client AS cs, @@session.sql_mode AS mode");
        assertThat(session.get("cs").toString()).startsWith("utf8mb4");
        assertThat(session.get("mode").toString()).containsAnyOf("STRICT_TRANS_TABLES", "STRICT_ALL_TABLES");
    }

    // ===================== 수신자 검증 =====================

    @Test
    @DisplayName("수신자 거부: 없음·자기 자신·DISABLED·DELETED·ROLE_USER는 모두 같은 400 문구이고 아무 것도 저장되지 않으며 FAIL 감사만 남는다")
    void send_ineligibleRecipients_sameMessage_nothingSaved() throws Exception {
        Member disabled = member("msg-disabled", Role.ROLE_MANAGER);
        Member deleted = member("msg-deleted", Role.ROLE_MANAGER);
        Member plainUser = member("msg-user", Role.ROLE_MANAGER);
        jdbc.update("UPDATE member SET status = 'DISABLED' WHERE id = ?", disabled.getId());
        jdbc.update("UPDATE member SET status = 'DELETED' WHERE id = ?", deleted.getId());
        jdbc.update("UPDATE member SET user_type = 'ROLE_USER' WHERE id = ?", plainUser.getId()); // API로는 만들 수 없는 상태 — SQL fixture

        long[] ineligibleIds = {999_999_999L, sender.getId(), disabled.getId(), deleted.getId(), plainUser.getId()};
        for (long recipientId : ineligibleIds) {
            MvcResult result = send(sender, recipientId, "제목", "본문");

            assertThat(result.getResponse().getStatus()).as("수신자 %d", recipientId).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains(INELIGIBLE);
        }

        assertThat(messagesBy(sender)).isZero();
        assertThat(logsBy(sender)).isZero();
        List<Map<String, Object>> audit = auditRows(sender);
        assertThat(audit).hasSize(ineligibleIds.length);
        assertThat(audit).allSatisfy(row -> {
            assertThat(row.get("action_result")).isEqualTo("FAIL");
            assertThat(row.get("error_message")).isEqualTo(AUDIT_FAIL_MESSAGE); // 고정 문구 — 수신자 입력·SQL 없음
            assertThat(row.get("target_id")).isNull();
        });
    }

    @Test
    @DisplayName("수신자 허용(D5): LOCKED·PASSWORD_EXPIRED 수신자는 받을 수 있다, ADMIN→MANAGER 강등된 수신자도 받을 수 있다")
    void send_lockedAndExpiredRecipientsAllowed() throws Exception {
        Member locked = member("msg-locked", Role.ROLE_MANAGER);
        Member expired = member("msg-expired", Role.ROLE_MANAGER);
        Member demoted = member("msg-demoted", Role.ROLE_ADMIN);
        jdbc.update("UPDATE member SET status = 'LOCKED' WHERE id = ?", locked.getId());
        jdbc.update("UPDATE member SET status = 'PASSWORD_EXPIRED' WHERE id = ?", expired.getId());
        jdbc.update("UPDATE member SET user_type = 'ROLE_MANAGER' WHERE id = ?", demoted.getId());

        assertThat(sendStatus(sender, locked.getId(), "제목")).isEqualTo(201);
        assertThat(sendStatus(sender, expired.getId(), "제목")).isEqualTo(201);
        assertThat(sendStatus(sender, demoted.getId(), "제목")).isEqualTo(201);
    }

    @Test
    @DisplayName("입력 거부: 제목 개행·고립 서로게이트 등은 400이고 저장되지 않으며 FAIL 감사의 메시지는 고정 문구다(입력 비포함)")
    void send_invalidText_rejectedWithFixedAuditMessage() throws Exception {
        String marker = "입력표식-" + System.nanoTime();

        MvcResult newlineTitle = mockMvc.perform(post(SEND).with(TestMembers.asMember(sender)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":" + recipient.getId() + ",\"title\":\"" + marker + "\\n줄바꿈\",\"body\":\"본문\"}")).andReturn();
        MvcResult loneSurrogate = mockMvc.perform(post(SEND).with(TestMembers.asMember(sender)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipientId\":" + recipient.getId() + ",\"title\":\"제목\",\"body\":\"" + marker + "\\ud83d\"}")).andReturn();

        assertThat(newlineTitle.getResponse().getStatus()).isEqualTo(400);
        assertThat(loneSurrogate.getResponse().getStatus()).isEqualTo(400);
        assertThat(newlineTitle.getResponse().getContentAsString()).doesNotContain(marker);
        assertThat(loneSurrogate.getResponse().getContentAsString()).doesNotContain(marker);
        assertThat(messagesBy(sender)).isZero();
        assertThat(auditRows(sender)).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("error_message")).isEqualTo(AUDIT_FAIL_MESSAGE);
            assertThat(row.values().toString()).doesNotContain(marker);
        });
    }

    // ===================== 빈도 제한(삭제와 무관한 이력) =====================

    @Test
    @DisplayName("분당 한도: 10건 후 11번째는 429 + Retry-After이고, 쪽지를 모두 삭제해도 한도는 회복되지 않으며, 이력이 1분을 지나야 풀린다")
    void sendLimit_perMinute_notRestoredByDeletion() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(sendStatus(sender, recipient.getId(), "제목" + i)).as("%d번째", i + 1).isEqualTo(201);
        }

        MvcResult limited = send(sender, recipient.getId(), "11번째", "본문");
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString()).contains("RATE_LIMITED");
        assertThat(Long.parseLong(limited.getResponse().getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(messagesBy(sender)).isEqualTo(10);

        // 발송→삭제 자동화로 한도를 회복할 수 없다 — 한도는 admin_message가 아니라 이력으로 센다(R1-3)
        jdbc.update("DELETE FROM admin_message WHERE sender_id = ?", sender.getId());
        assertThat(sendStatus(sender, recipient.getId(), "삭제 후")).isEqualTo(429);

        // 이력이 1분 창을 벗어나면 다시 보낼 수 있다
        jdbc.update("UPDATE admin_message_send_log SET sent_at = sent_at - INTERVAL 2 MINUTE WHERE sender_id = ?", sender.getId());
        assertThat(sendStatus(sender, recipient.getId(), "창 경과 후")).isEqualTo(201);

        // 429·400 실패는 FAIL 감사로 남고 고정 문구다
        assertThat(auditRows(sender)).filteredOn(row -> "FAIL".equals(row.get("action_result")))
                .allSatisfy(row -> assertThat(row.get("error_message")).isEqualTo(AUDIT_FAIL_MESSAGE));
    }

    @Test
    @DisplayName("일일 한도: 24시간 안 300건이면 429이고, 24시간 지난 이력은 다음 발송에서 PK로 정리된다")
    void sendLimit_perDay_andPurgesExpiredLog() throws Exception {
        jdbc.update("INSERT INTO admin_message_sender_state (member_id) VALUES (?) ON DUPLICATE KEY UPDATE member_id = member_id", sender.getId());
        for (int i = 0; i < 300; i++) {
            jdbc.update("INSERT INTO admin_message_send_log (sender_id, sent_at) VALUES (?, ?)", sender.getId(), LocalDateTime.now(clock).minusHours(12));
        }

        assertThat(sendStatus(sender, recipient.getId(), "한도")).isEqualTo(429);
        assertThat(logsBy(sender)).isEqualTo(300);

        // 이력이 24시간을 지나면 한도에서 빠지고, 발송 트랜잭션이 만료 이력을 정리한다
        jdbc.update("UPDATE admin_message_send_log SET sent_at = ? WHERE sender_id = ?", LocalDateTime.now(clock).minusHours(25), sender.getId());
        assertThat(sendStatus(sender, recipient.getId(), "회복")).isEqualTo(201);
        assertThat(logsBy(sender)).isEqualTo(1);
    }

    // ===================== 현재 자격 가드 =====================

    @Test
    @DisplayName("가드(D16): 세션은 유효하지만 DB에서 비ACTIVE·ROLE_USER가 된 계정은 보내기·검색 모두 403이고 아무 것도 저장하지 않는다")
    void guard_staleSessionIsRejected() throws Exception {
        jdbc.update("UPDATE member SET status = 'DISABLED' WHERE id = ?", sender.getId());

        assertThat(sendStatus(sender, recipient.getId(), "제목")).isEqualTo(403);
        assertThat(mockMvc.perform(get(SEARCH).param("keyword", "ab").with(TestMembers.asMember(sender))).andReturn()
                .getResponse().getStatus()).isEqualTo(403);

        jdbc.update("UPDATE member SET status = 'ACTIVE', user_type = 'ROLE_USER' WHERE id = ?", sender.getId());
        assertThat(sendStatus(sender, recipient.getId(), "제목")).isEqualTo(403);

        assertThat(messagesBy(sender)).isZero();
        assertThat(auditRows(sender)).isEmpty(); // 가드 거부는 서비스에 진입하지 않아 감사에 남지 않는다
    }

    @Test
    @DisplayName("가드(D16): ADMIN→MANAGER 강등은 허용 집합 안이라 낡은 ADMIN 세션도 계속 보낼 수 있다")
    void guard_demotionToManagerStillAllowed() throws Exception {
        jdbc.update("UPDATE member SET user_type = 'ROLE_MANAGER' WHERE id = ?", sender.getId());

        assertThat(sendStatus(sender, recipient.getId(), "제목")).isEqualTo(201);
    }

    // ===================== 수신자 검색 =====================

    @Test
    @DisplayName("검색: 원문 정확 일치(앞 공백 아이디·한 글자 아이디)와 trim 후 부분 일치, 자기 제외·비수신 상태 제외, 노출 필드는 id·userId·userName뿐이다")
    void search_exactVerbatimAndPartial() throws Exception {
        String token = "zqs" + (System.nanoTime() % 1_000_000);
        Member leading = member("msg-leading", Role.ROLE_MANAGER);
        Member single = member("msg-single", Role.ROLE_MANAGER);
        Member disabled = member("msg-search-disabled", Role.ROLE_MANAGER);
        Member plainUser = member("msg-search-user", Role.ROLE_MANAGER);
        jdbc.update("UPDATE member SET user_id = ? WHERE id = ?", " " + token, leading.getId());       // 앞 공백이 든 정상 아이디
        jdbc.update("UPDATE member SET user_id = ?, user_name = ? WHERE id = ?", "ㅋ", "한글자", single.getId());
        jdbc.update("UPDATE member SET user_id = ?, status = 'DISABLED' WHERE id = ?", token + "-off", disabled.getId());
        jdbc.update("UPDATE member SET user_id = ?, user_type = 'ROLE_USER' WHERE id = ?", token + "-usr", plainUser.getId());
        jdbc.update("UPDATE member SET user_id = ? WHERE id = ?", token + "-self", sender.getId());

        // 원문 그대로(앞 공백 포함) 입력하면 정확 일치로 찾는다 — trim했다면 닿지 않는 계정
        String exact = mockMvc.perform(get(SEARCH).param("keyword", " " + token).with(TestMembers.asMember(sender)))
                .andReturn().getResponse().getContentAsString();
        assertThat(exact).contains("\"id\":" + leading.getId()).contains("\"userId\":\" " + token + "\"");
        assertThat(exact).doesNotContain("email").doesNotContain("userType").doesNotContain("status");

        // 한 글자 아이디는 정확 일치로만 선택된다
        String oneChar = mockMvc.perform(get(SEARCH).param("keyword", "ㅋ").with(TestMembers.asMember(sender)))
                .andReturn().getResponse().getContentAsString();
        assertThat(oneChar).contains("\"id\":" + single.getId());

        // trim 후 2코드포인트 이상이면 부분 일치(아이디·이름) — 자기·DISABLED·ROLE_USER는 결과에 없다
        String partial = mockMvc.perform(get(SEARCH).param("keyword", token).with(TestMembers.asMember(sender)))
                .andReturn().getResponse().getContentAsString();
        assertThat(partial).contains("\"id\":" + leading.getId());
        assertThat(partial).doesNotContain("\"id\":" + disabled.getId())
                .doesNotContain("\"id\":" + plainUser.getId())
                .doesNotContain("\"id\":" + sender.getId());
        assertThat(partial).contains("\"truncated\":false");
    }

    @Test
    @DisplayName("검색: LIKE 와일드카드(%·_)는 리터럴이다 — '%%'가 모든 회원에 일치하지 않는다")
    void search_likeWildcardsAreLiterals() throws Exception {
        String result = mockMvc.perform(get(SEARCH).param("keyword", "%%").with(TestMembers.asMember(sender)))
                .andReturn().getResponse().getContentAsString();

        assertThat(result).contains("\"content\":[]");
    }

    // ===================== 동시성 =====================

    @Test
    @DisplayName("동시 발송: 같은 발신자의 병렬 12건 중 정확히 10건만 201이고 나머지는 429다(상태 행 잠금으로 한도가 정확)")
    void concurrentSendsFromOneSender_limitIsExact() throws Exception {
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String title = "동시" + i;
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
                statuses.add(future.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(s -> s == 201).hasSize(10);
            assertThat(statuses).filteredOn(s -> s == 429).hasSize(2);
            assertThat(messagesBy(sender)).isEqualTo(10);
            assertThat(logsBy(sender)).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("상호 발송: A→B와 B→A 동시 발송은 교착 없이 모두 201이다(409 0건 — 발신자별 상태 행 잠금)")
    void mutualConcurrentSends_noDeadlock() throws Exception {
        Member a = sender;
        Member b = recipient;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
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

                assertThat(aToB.get(60, TimeUnit.SECONDS)).as("라운드 %d A→B", round).isEqualTo(201);
                assertThat(bToA.get(60, TimeUnit.SECONDS)).as("라운드 %d B→A", round).isEqualTo(201);
            }
            assertThat(messagesBy(a)).isEqualTo(8);
            assertThat(messagesBy(b)).isEqualTo(8);
        } finally {
            pool.shutdownNow();
        }
    }
}
