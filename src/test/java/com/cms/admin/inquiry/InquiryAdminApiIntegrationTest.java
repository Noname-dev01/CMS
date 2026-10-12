package com.cms.admin.inquiry;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 문의함 관리 API 실제 스택 시험(PLAN-inquiry.md 쟁점 7·10) — 목록·상세·상태 변경·삭제·감사·본문 비변형·동시성. */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class InquiryAdminApiIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;

    private Member admin;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM inquiry");
        admin = TestMembers.save(memberRepository, "ia-admin", Role.ROLE_ADMIN);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM inquiry");
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    private long insert(String name, String email, String title, String body, String status) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO inquiry (name, email, title, body, status, consent_at, create_date, update_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                name, email, title, body, status, now, now, now);
        return jdbc.queryForObject("SELECT MAX(id) FROM inquiry", Long.class);
    }

    private String statusOf(long id) {
        List<String> values = jdbc.queryForList("SELECT status FROM inquiry WHERE id = ?", String.class, id);
        return values.isEmpty() ? null : values.get(0);
    }

    private int auditCount(String actionType, String result) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = ? AND action_result = ?",
                Integer.class, auditBaseline, actionType, result);
    }

    @Test
    @DisplayName("목록: 최신순·본문 미포함·상태 필터·페이지네이션·미처리 전체 건수")
    void list() throws Exception {
        long a = insert("가", "a@example.com", "첫째", "본문 A", "RECEIVED");
        long b = insert("나", "b@example.com", "둘째", "본문 B", "IN_PROGRESS");
        long c = insert("다", "c@example.com", "셋째", "본문 C", "ANSWERED");

        mockMvc.perform(get("/admin/api/inquiries").with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].id").value(c))
                .andExpect(jsonPath("$.content[2].id").value(a))
                .andExpect(jsonPath("$.content[0].body").doesNotExist())
                .andExpect(jsonPath("$.content[0].title").value("셋째"))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.unresolvedCount").value(2));

        mockMvc.perform(get("/admin/api/inquiries").param("status", "IN_PROGRESS").with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(b))
                .andExpect(jsonPath("$.unresolvedCount").value(2));

        mockMvc.perform(get("/admin/api/inquiries").param("size", "2").param("page", "1").with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.last").value(true));

        mockMvc.perform(get("/admin/api/inquiries").param("status", "NOPE").with(TestMembers.asMember(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("상세: 본문 포함, 조회가 상태·수정 시각을 바꾸지 않는다, 없으면 404")
    void detail() throws Exception {
        long id = insert("홍길동", "visitor@example.com", "제목", "본문\n둘째 줄", "RECEIVED");
        Object updateBefore = jdbc.queryForObject("SELECT update_date FROM inquiry WHERE id = ?", Object.class, id);

        mockMvc.perform(get("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("홍길동"))
                .andExpect(jsonPath("$.email").value("visitor@example.com"))
                .andExpect(jsonPath("$.body").value("본문\n둘째 줄"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.consentAt").exists());

        assertThat(statusOf(id)).isEqualTo("RECEIVED");
        assertThat(jdbc.queryForObject("SELECT update_date FROM inquiry WHERE id = ?", Object.class, id)).isEqualTo(updateBefore);
        mockMvc.perform(get("/admin/api/inquiries/{id}", id + 999).with(TestMembers.asMember(admin))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("방문자 입력은 서버가 변형하지 않는다 — HTML·스크립트가 든 값이 원문 그대로 JSON 문자열로 나온다(이스케이프는 표시 계층 책임)")
    void visitorTextIsReturnedVerbatim() throws Exception {
        String attack = "<img src=x onerror=alert(1)>";
        long id = insert(attack, "a@b.co", attack, attack + "<script>alert(2)</script>", "RECEIVED");

        mockMvc.perform(get("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(attack))
                .andExpect(jsonPath("$.title").value(attack))
                .andExpect(jsonPath("$.body").value(attack + "<script>alert(2)</script>"));
    }

    @Test
    @DisplayName("상태 변경: 접수 → 처리 중 → 답변 완료 → 접수(제한 없음), 같은 값은 멱등 200으로 수정 시각을 바꾸지 않고, 잘못된 값 400·없는 문의 404, 감사 INQUIRY_STATUS(targetId)")
    void changeStatus() throws Exception {
        long id = insert("홍길동", "a@b.co", "제목", "본문", "RECEIVED");

        for (String next : new String[]{"IN_PROGRESS", "ANSWERED", "RECEIVED"}) {
            mockMvc.perform(patch("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + next + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(next));
            assertThat(statusOf(id)).isEqualTo(next);
        }

        Object updateBefore = jdbc.queryForObject("SELECT update_date FROM inquiry WHERE id = ?", Object.class, id);
        mockMvc.perform(patch("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RECEIVED\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT update_date FROM inquiry WHERE id = ?", Object.class, id)).as("같은 값 재요청은 쓰기 없음").isEqualTo(updateBefore);

        for (String badBody : new String[]{"{\"status\":\"DONE\"}", "{}", "{\"status\":null}", "not json"}) {
            mockMvc.perform(patch("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(badBody))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(patch("/admin/api/inquiries/{id}", id + 999).with(TestMembers.asMember(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANSWERED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ANSWERED\"}"))
                .andExpect(status().isForbidden());                                              // CSRF 없음

        assertThat(auditCount("INQUIRY_STATUS", "SUCCESS")).isEqualTo(4);                         // 3번 변경 + 같은 값 1번 (400은 서비스 진입 전, 404·403은 감사 대상 아님 또는 FAIL)
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'INQUIRY_STATUS' AND action_result = 'SUCCESS' "
                + "AND target_type = 'INQUIRY' AND target_id = ? AND target_label IS NULL", Integer.class, auditBaseline, id)).isEqualTo(4);
    }

    @Test
    @DisplayName("삭제: 204로 행이 사라지고 다시 삭제하면 404, 감사 INQUIRY_DELETE에 targetId가 남고 방문자 입력은 감사에 남지 않는다")
    void delete_() throws Exception {
        long id = insert("MARKER-NAME", "marker@example.com", "MARKER-TITLE", "MARKER-BODY", "ANSWERED");

        mockMvc.perform(delete("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())).andExpect(status().isNoContent());
        assertThat(statusOf(id)).isNull();
        mockMvc.perform(delete("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND action_type = 'INQUIRY_DELETE' AND action_result = 'SUCCESS' "
                + "AND target_type = 'INQUIRY' AND target_id = ?", Integer.class, auditBaseline, id)).isEqualTo(1);
        assertThat(auditCount("INQUIRY_DELETE", "FAIL")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_action_log WHERE id > ? AND (COALESCE(target_label,'') LIKE '%MARKER%' OR COALESCE(error_message,'') LIKE '%MARKER%')",
                Integer.class, auditBaseline)).isZero();
    }

    @Test
    @DisplayName("같은 문의에 대한 PATCH와 DELETE를 동시에 쏴도 교착·500 없이 200/204/404만 나오고 행은 사라진다")
    void concurrentPatchAndDelete() throws Exception {
        long id = insert("홍길동", "a@b.co", "제목", "본문", "RECEIVED");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String status = i % 2 == 0 ? "IN_PROGRESS" : "ANSWERED";
                calls.add(() -> mockMvc.perform(patch("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}")).andReturn().getResponse().getStatus());
            }
            for (int i = 0; i < 2; i++) {
                calls.add(() -> mockMvc.perform(delete("/admin/api/inquiries/{id}", id).with(TestMembers.asMember(admin)).with(csrf()))
                        .andReturn().getResponse().getStatus());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).allMatch(code -> code == 200 || code == 204 || code == 404);
            assertThat(statuses.stream().filter(code -> code == 204).count()).as("삭제는 정확히 한 번 성공").isEqualTo(1);
            assertThat(statusOf(id)).isNull();
        } finally {
            pool.shutdownNow();
        }
    }
}
