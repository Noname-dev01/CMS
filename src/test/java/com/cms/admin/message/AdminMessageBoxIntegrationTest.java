package com.cms.admin.message;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * 쪽지함 조회·읽음·삭제를 실제 SecurityConfig·가드·MariaDB로 확인한다(PLAN-admin-message.md §5-D·§7 ③⑥).
 * 쪽지는 발송 한도(분당 10건)를 피하려고 JDBC로 직접 만든다 — 보내기 경로는 {@code AdminMessageSendIntegrationTest}가 검증한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminMessageBoxIntegrationTest extends MariaDbContainerSupport {

    private static final String BASE = "/admin/api/members/me/messages";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;
    @Autowired Clock clock;

    private Member alice;   // 보내는 사람
    private Member bob;     // 받는 사람
    private Member carol;   // 제3자
    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        alice = member("box-alice", Role.ROLE_ADMIN);
        bob = member("box-bob", Role.ROLE_MANAGER);
        carol = member("box-carol", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
    }

    private Member member(String prefix, Role role) {
        Member saved = TestMembers.save(memberRepository, prefix, role);
        memberIds.add(saved.getId());
        return saved;
    }

    /** 시각은 DB NOW()(컨테이너 UTC)가 아니라 앱 KST Clock으로 넣는다. */
    private long insertMessage(Member from, Member to, String title, String body) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO admin_message (sender_id, recipient_id, title, body, create_date) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, from.getId());
            ps.setLong(2, to.getId());
            ps.setString(3, title);
            ps.setString(4, body);
            ps.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now(clock)));
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private MvcResult getAs(Member who, String url) throws Exception {
        return mockMvc.perform(get(url).with(TestMembers.asMember(who))).andReturn();
    }

    private int patchRead(Member who, long id) throws Exception {
        return mockMvc.perform(patch(BASE + "/" + id).with(TestMembers.asMember(who)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}")).andReturn().getResponse().getStatus();
    }

    private int deleteAs(Member who, long id) throws Exception {
        return mockMvc.perform(delete(BASE + "/" + id).with(TestMembers.asMember(who)).with(csrf())).andReturn().getResponse().getStatus();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private Object column(long id, String column) {
        return jdbc.queryForMap("SELECT " + column + " AS v FROM admin_message WHERE id = ?", id).get("v");
    }

    private long messageCount(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_message WHERE id = ?", Long.class, id);
    }

    // ===================== IDOR =====================

    @Test
    @DisplayName("IDOR: 제3자는 남의 쪽지를 조회·읽음·삭제할 수 없다(모두 404) — 원본은 변하지 않는다")
    void thirdParty_cannotReadPatchOrDelete() throws Exception {
        long id = insertMessage(alice, bob, "비공개 제목", "비공개 본문");

        assertThat(getAs(carol, BASE + "/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(patchRead(carol, id)).isEqualTo(404);
        assertThat(deleteAs(carol, id)).isEqualTo(404);

        assertThat(column(id, "read_at")).isNull();
        assertThat(column(id, "sender_deleted_at")).isNull();
        assertThat(column(id, "recipient_deleted_at")).isNull();
        assertThat(messageCount(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("IDOR: 제3자의 목록에는 남의 쪽지가 없다")
    void thirdParty_listsOnlyOwnMessages() throws Exception {
        insertMessage(alice, bob, "A→B", "본문");

        assertThat(json(getAs(carol, BASE + "?box=inbox")).get("content")).isEmpty();
        assertThat(json(getAs(carol, BASE + "?box=sent")).get("content")).isEmpty();
    }

    // ===================== 읽음 =====================

    @Test
    @DisplayName("읽음: 수신자만 가능하다 — 발신자 본인의 읽음 요청은 404이고 읽음 상태가 바뀌지 않는다")
    void onlyRecipientCanMarkRead() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");

        assertThat(patchRead(alice, id)).isEqualTo(404);
        assertThat(column(id, "read_at")).isNull();

        assertThat(patchRead(bob, id)).isEqualTo(200);
        assertThat(column(id, "read_at")).isNotNull();
    }

    @Test
    @DisplayName("읽음: 멱등이다 — 이미 읽은 쪽지는 기존 read_at을 유지한 채 200이고 응답에 서버가 센 미읽음 수가 담긴다")
    void markRead_isIdempotentAndKeepsFirstReadAt() throws Exception {
        long first = insertMessage(alice, bob, "첫째", "본문");
        insertMessage(alice, bob, "둘째", "본문");

        MvcResult firstRead = mockMvc.perform(patch(BASE + "/" + first).with(TestMembers.asMember(bob)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}")).andReturn();
        assertThat(firstRead.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(firstRead).get("unreadCount").asLong()).isEqualTo(1);
        Object firstReadAt = column(first, "read_at");

        Thread.sleep(20); // 두 번째 읽음이 시각을 덮어쓴다면 달라질 수 있는 간격
        assertThat(patchRead(bob, first)).isEqualTo(200);
        assertThat(column(first, "read_at")).isEqualTo(firstReadAt);
    }

    @Test
    @DisplayName("읽음: 수신자가 이미 지운 쪽지는 404다")
    void markRead_afterRecipientDeleted_is404() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");
        assertThat(deleteAs(bob, id)).isEqualTo(204);

        assertThat(patchRead(bob, id)).isEqualTo(404);
    }

    @Test
    @DisplayName("읽음 경합: 같은 쪽지에 동시 읽음 PATCH가 와도 모두 200이고 최초 읽음 시각이 보존된다")
    void concurrentMarkRead_preservesFirstReadAt() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return patchRead(bob, id);
                }));
            }
            go.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
            Object readAt = column(id, "read_at");
            assertThat(readAt).isNotNull();

            Thread.sleep(20);
            assertThat(patchRead(bob, id)).isEqualTo(200);
            assertThat(column(id, "read_at")).isEqualTo(readAt);
        } finally {
            pool.shutdownNow();
        }
    }

    // ===================== 단건·목록 =====================

    @Test
    @DisplayName("단건: 본문 전체와 내 기준 방향·상대를 돌려주고 읽음을 일으키지 않는다(GET 부작용 없음), 이메일 등은 노출하지 않는다")
    void detail_doesNotMarkRead_andShowsDirectionAndCounterpart() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문 전체");

        MvcResult received = getAs(bob, BASE + "/" + id);
        JsonNode receivedBody = json(received);
        assertThat(received.getResponse().getStatus()).isEqualTo(200);
        assertThat(receivedBody.get("direction").asText()).isEqualTo("RECEIVED");
        assertThat(receivedBody.get("body").asText()).isEqualTo("본문 전체");
        assertThat(receivedBody.get("counterpart").get("id").asLong()).isEqualTo(alice.getId());
        assertThat(receivedBody.get("counterpart").get("userId").asText()).isEqualTo(alice.getUserId());
        assertThat(received.getResponse().getContentAsString()).doesNotContain("email").doesNotContain("userType").doesNotContain("pwd");
        assertThat(column(id, "read_at")).as("GET은 읽음을 일으키지 않는다").isNull();

        JsonNode sentBody = json(getAs(alice, BASE + "/" + id));
        assertThat(sentBody.get("direction").asText()).isEqualTo("SENT");
        assertThat(sentBody.get("counterpart").get("id").asLong()).isEqualTo(bob.getId());
    }

    @Test
    @DisplayName("목록: 받은/보낸 쪽지함이 id 내림차순 beforeId 커서로 이어지고(hasMore), 목록에는 본문이 없으며 사이에 새 쪽지가 들어와도 중복·누락이 없다")
    void list_cursorPaging_stableUnderInsertion() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(insertMessage(alice, bob, "제목" + i, "본문" + i));
        }

        MvcResult page1 = getAs(bob, BASE + "?box=inbox&size=2");
        JsonNode first = json(page1);
        assertThat(first.get("content")).hasSize(2);
        assertThat(first.get("hasMore").asBoolean()).isTrue();
        assertThat(first.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(4));
        assertThat(first.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(3));
        assertThat(page1.getResponse().getContentAsString()).doesNotContain("본문").doesNotContain("\"body\"");
        assertThat(first.get("content").get(0).get("counterpart").get("userId").asText()).isEqualTo(alice.getUserId());

        // 첫 페이지를 읽은 뒤 새 쪽지가 들어와도 커서(beforeId) 이후 페이지는 중복·누락이 없다
        insertMessage(alice, bob, "새 쪽지", "본문");
        long cursor = first.get("content").get(1).get("id").asLong();
        JsonNode second = json(getAs(bob, BASE + "?box=inbox&size=2&beforeId=" + cursor));
        assertThat(second.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(2));
        assertThat(second.get("content").get(1).get("id").asLong()).isEqualTo(ids.get(1));
        assertThat(second.get("hasMore").asBoolean()).isTrue();

        JsonNode third = json(getAs(bob, BASE + "?box=inbox&size=2&beforeId=" + ids.get(1)));
        assertThat(third.get("content")).hasSize(1);
        assertThat(third.get("content").get(0).get("id").asLong()).isEqualTo(ids.get(0));
        assertThat(third.get("hasMore").asBoolean()).isFalse();

        // 보낸 쪽지함은 받는 사람이 상대로 나온다
        JsonNode sent = json(getAs(alice, BASE + "?box=sent&size=50"));
        assertThat(sent.get("content")).hasSize(6);
        assertThat(sent.get("content").get(0).get("counterpart").get("id").asLong()).isEqualTo(bob.getId());
    }

    @Test
    @DisplayName("목록: size 누락·0은 기본값, 상한(50) 초과는 상한으로, 잘못된 box는 400이다")
    void list_sizeClampAndInvalidBox() throws Exception {
        for (int i = 0; i < 3; i++) {
            insertMessage(alice, bob, "제목" + i, "본문");
        }

        assertThat(json(getAs(bob, BASE + "?box=inbox")).get("content")).hasSize(3);
        assertThat(json(getAs(bob, BASE + "?box=inbox&size=0")).get("content")).hasSize(3);
        assertThat(json(getAs(bob, BASE + "?box=inbox&size=1000")).get("content")).hasSize(3);
        assertThat(getAs(bob, BASE).getResponse().getStatus()).isEqualTo(400);
        assertThat(getAs(bob, BASE + "?box=trash").getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("보낸 쪽지함: 읽음 확인(read·readAt)이 수신자의 읽음 뒤에 나타난다(D10)")
    void sentBox_showsReadConfirmation() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");

        JsonNode before = json(getAs(alice, BASE + "?box=sent")).get("content").get(0);
        assertThat(before.get("read").asBoolean()).isFalse();
        // 프로젝트 전역 Jackson 설정(NON_NULL)이라 아직 읽지 않았으면 readAt 키가 생략된다 — 화면은 키 부재를 미읽음으로 처리한다
        assertThat(before.has("readAt")).isFalse();

        patchRead(bob, id);

        JsonNode after = json(getAs(alice, BASE + "?box=sent")).get("content").get(0);
        assertThat(after.get("read").asBoolean()).isTrue();
        assertThat(after.has("readAt")).isTrue();
        assertThat(after.get("readAt").asText()).isNotBlank();
    }

    @Test
    @DisplayName("상대 회원이 DELETED여도 쪽지는 보이고 이름·아이디는 현재 값이다(이메일은 노출하지 않는다)")
    void counterpartDeleted_messageStillVisible() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");
        jdbc.update("UPDATE member SET status = 'DELETED', user_name = '바뀐 이름' WHERE id = ?", alice.getId());

        MvcResult inbox = getAs(bob, BASE + "?box=inbox");
        JsonNode counterpart = json(inbox).get("content").get(0).get("counterpart");
        assertThat(counterpart.get("userName").asText()).isEqualTo("바뀐 이름");
        assertThat(inbox.getResponse().getContentAsString()).doesNotContain("email");
        assertThat(getAs(bob, BASE + "/" + id).getResponse().getStatus()).isEqualTo(200);
    }

    // ===================== 삭제(각자 보관함) =====================

    @Test
    @DisplayName("삭제: 한쪽이 지워도 상대에게는 남고, 양쪽 모두 지우면 물리 삭제된다 — 이미 지운 쪽의 반복 요청은 404다")
    void delete_eachSideOnly_thenPhysical() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");

        assertThat(deleteAs(alice, id)).isEqualTo(204);
        assertThat(column(id, "sender_deleted_at")).isNotNull();
        assertThat(column(id, "recipient_deleted_at")).isNull();
        assertThat(messageCount(id)).as("한쪽만 지웠으므로 물리 삭제 아님").isEqualTo(1);
        assertThat(json(getAs(alice, BASE + "?box=sent")).get("content")).isEmpty();
        assertThat(getAs(alice, BASE + "/" + id).getResponse().getStatus()).isEqualTo(404);
        assertThat(getAs(bob, BASE + "/" + id).getResponse().getStatus()).as("상대에게는 남는다").isEqualTo(200);
        assertThat(json(getAs(bob, BASE + "?box=inbox")).get("content")).hasSize(1);
        assertThat(deleteAs(alice, id)).as("이미 지운 쪽의 반복 요청").isEqualTo(404);

        assertThat(deleteAs(bob, id)).isEqualTo(204);
        assertThat(messageCount(id)).as("양쪽 모두 지우면 물리 삭제").isZero();
        assertThat(deleteAs(bob, id)).isEqualTo(404);
    }

    @Test
    @DisplayName("삭제: 받는 쪽이 먼저 지우고 보낸 쪽이 나중에 지워도 같은 결과다(물리 삭제)")
    void delete_recipientFirstThenSender() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");

        assertThat(deleteAs(bob, id)).isEqualTo(204);
        assertThat(messageCount(id)).isEqualTo(1);
        assertThat(getAs(alice, BASE + "/" + id).getResponse().getStatus()).isEqualTo(200);

        assertThat(deleteAs(alice, id)).isEqualTo(204);
        assertThat(messageCount(id)).isZero();
    }

    @Test
    @DisplayName("미읽음 수: 읽은 쪽지와 본인이 지운 쪽지는 빠지고 발신자의 미읽음에는 영향이 없다")
    void unreadCount_excludesReadAndDeleted() throws Exception {
        long readOne = insertMessage(alice, bob, "읽을 것", "본문");
        long deleteOne = insertMessage(alice, bob, "지울 것", "본문");
        insertMessage(alice, bob, "남길 것", "본문");

        assertThat(json(getAs(bob, BASE + "/unread-count")).get("unreadCount").asLong()).isEqualTo(3);
        patchRead(bob, readOne);
        deleteAs(bob, deleteOne);

        assertThat(json(getAs(bob, BASE + "/unread-count")).get("unreadCount").asLong()).isEqualTo(1);
        assertThat(json(getAs(alice, BASE + "/unread-count")).get("unreadCount").asLong()).isZero();
    }

    // ===================== 현재 자격 가드 =====================

    @Test
    @DisplayName("가드(D16): 세션은 유효하지만 DB에서 비ACTIVE가 된 계정은 목록·단건·읽음·삭제·미읽음 수 모두 403이다")
    void guard_staleSessionRejectedOnEveryEndpoint() throws Exception {
        long id = insertMessage(alice, bob, "제목", "본문");
        jdbc.update("UPDATE member SET status = 'DISABLED' WHERE id = ?", bob.getId());

        assertThat(getAs(bob, BASE + "?box=inbox").getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs(bob, BASE + "/unread-count").getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs(bob, BASE + "/" + id).getResponse().getStatus()).isEqualTo(403);
        assertThat(patchRead(bob, id)).isEqualTo(403);
        assertThat(deleteAs(bob, id)).isEqualTo(403);

        assertThat(column(id, "read_at")).isNull();
        assertThat(column(id, "recipient_deleted_at")).isNull();
    }
}
