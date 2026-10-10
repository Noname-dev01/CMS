package com.cms.admin.session;

import com.cms.admin.log.service.AdminActionLogService;
import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.config.auth.AdminSessionService;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 세션 관리 end-to-end(실제 SecurityConfig·로그인·세션 레지스트리·MariaDB·감사 AOP). PLAN-session-management.md 완료 기준 전체를 관통한다:
 * ADMIN 전용(MANAGER 403·CSRF 403)·강제 만료 후 대상의 다음 요청(API JSON 401/페이지 로그인 리다이렉트)·현재 세션 보호(409)·
 * 본인의 다른 세션 만료·회원 단위 만료·원문 세션 ID 비노출·감사 SUCCESS/FAIL(대상·라벨)·감사 저장 실패에도 만료 유지.
 *
 * <p>회원은 시험마다 새로 만들고, 목록 단언은 <b>자기 회원 행만</b> 본다 — 컨텍스트가 캐시되면 다른 시험이 남긴 세션이 레지스트리에 있다.
 * 한계: MockMvc/MockHttpSession 기반이라 실제 서블릿 컨테이너의 세션 파괴·ID 변경 이벤트는 검증하지 못한다(dev Docker 실기로 보완).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminSessionManagementIntegrationTest extends MariaDbContainerSupport {

    private static final String PASSWORD = "Session1234!";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    @Autowired MemberRepository memberRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AdminActionLogService auditService;

    private final List<Long> createdMemberIds = new ArrayList<>();
    private Member admin;
    private Member manager;
    private long auditBaseline;

    @BeforeEach
    void setUp() {
        admin = createMember("sess-admin", Role.ROLE_ADMIN);
        manager = createMember("sess-mgr", Role.ROLE_MANAGER);
        auditBaseline = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM admin_action_log", Long.class);
        clearInvocations(auditService);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : createdMemberIds) {
            try {
                jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", id);
                jdbc.update("DELETE FROM member_permission WHERE member_id = ?", id);
                memberRepository.deleteById(id);
            } catch (Exception ignored) {
            }
        }
    }

    private Member createMember(String prefix, Role role) {
        LocalDateTime now = LocalDateTime.now();
        String unique = prefix + "-" + System.nanoTime();
        Member saved = memberRepository.save(Member.builder()
                .userId(unique.substring(0, Math.min(50, unique.length())))
                .pwd(passwordEncoder.encode(PASSWORD))
                .userName("세션관리테스트")
                .email(unique + "@session.test")
                .userType(role)
                .status(MemberStatus.ACTIVE)
                .createDate(now)
                .updateDate(now)
                .passwordChangedAt(now)
                .build());
        createdMemberIds.add(saved.getId());
        return saved;
    }

    private MockHttpSession login(Member member) throws Exception {
        MvcResult result = mockMvc.perform(formLogin("/admin/login").user(member.getUserId()).password(PASSWORD))
                .andExpect(authenticated())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("로그인 성공 시 세션이 생성되어야 한다").isNotNull();
        return session;
    }

    private static String handleOf(MockHttpSession session) {
        return AdminSessionService.handleOf(session.getId());
    }

    private JsonNode memberRow(MockHttpSession asSession, Member target) throws Exception {
        String body = mockMvc.perform(get("/admin/api/sessions").session(asSession))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (JsonNode row : MAPPER.readTree(body).get("members")) {
            if (row.get("memberId").asLong() == target.getId()) {
                return row;
            }
        }
        return null;
    }

    private void assertSessionDead(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/admin/api/members/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private void assertSessionAlive(MockHttpSession session) throws Exception {
        mockMvc.perform(get("/admin/api/members/me").session(session)).andExpect(status().isOk());
    }

    private List<Map<String, Object>> sessionAudits() {
        return jdbc.queryForList("SELECT action_result, target_type, target_id, target_label, error_message, request_uri, request_method"
                + " FROM admin_action_log WHERE id > ? AND action_type = 'SESSION_EXPIRE' AND action_id = ? ORDER BY id",
                auditBaseline, admin.getId());
    }

    // ===================== 목록 =====================

    @Test
    @DisplayName("목록: 같은 회원의 두 세션이 한 행으로 묶이고 현재 세션만 current — 응답에 원문 세션 ID가 없다")
    void list_groupsSessionsAndMarksCurrent() throws Exception {
        MockHttpSession a1 = login(admin);
        MockHttpSession a2 = login(admin);
        MockHttpSession m1 = login(manager);

        String body = mockMvc.perform(get("/admin/api/sessions").session(a1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode adminRow = memberRow(a1, admin);
        assertThat(adminRow.get("sessionCount").asInt()).isEqualTo(2);
        assertThat(adminRow.get("role").asString()).isEqualTo("ROLE_ADMIN");
        assertThat(adminRow.get("userId").asString()).isEqualTo(admin.getUserId());
        List<String> handles = new ArrayList<>();
        String currentHandle = null;
        for (JsonNode s : adminRow.get("sessions")) {
            handles.add(s.get("handle").asString());
            if (s.get("current").asBoolean()) {
                currentHandle = s.get("handle").asString();
            }
        }
        assertThat(handles).containsExactlyInAnyOrder(handleOf(a1), handleOf(a2));
        assertThat(currentHandle).isEqualTo(handleOf(a1));
        JsonNode managerRow = memberRow(a1, manager);
        assertThat(managerRow.get("sessionCount").asInt()).isEqualTo(1);
        assertThat(managerRow.get("sessions").get(0).get("current").asBoolean()).isFalse();
        assertThat(managerRow.get("sessions").get(0).get("handle").asString()).isEqualTo(handleOf(m1));

        for (MockHttpSession s : List.of(a1, a2, m1)) {
            assertThat(body).as("원문 세션 ID 비노출").doesNotContain("\"" + s.getId() + "\"");
        }
    }

    // ===================== 인가 =====================

    @Test
    @DisplayName("MANAGER는 실제 로그인 세션·유효한 CSRF로도 목록·두 DELETE·페이지가 모두 403이고 대상 세션은 유지된다")
    void manager_isForbiddenEverywhere() throws Exception {
        MockHttpSession mgr = login(manager);
        MockHttpSession adm = login(admin);

        mockMvc.perform(get("/admin/api/sessions").session(mgr)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(adm)).session(mgr).with(csrf())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", String.valueOf(admin.getId())).session(mgr).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/admin/session/manage").session(mgr)).andExpect(status().isForbidden());

        assertSessionAlive(adm);
        assertThat(sessionAudits()).isEmpty();
    }

    @Test
    @DisplayName("ADMIN이어도 CSRF 토큰이 없으면 두 DELETE 모두 403이고 대상 세션은 유지된다")
    void admin_withoutCsrf_isForbiddenAndSessionKept() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession mgr = login(manager);

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(mgr)).session(adm)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", String.valueOf(manager.getId())).session(adm))
                .andExpect(status().isForbidden());

        assertSessionAlive(mgr);
    }

    @Test
    @DisplayName("ADMIN은 세션 관리 페이지 200, 미인증은 로그인 리다이렉트")
    void page_adminOkAnonymousRedirected() throws Exception {
        mockMvc.perform(get("/admin/session/manage").session(login(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/admin/session/manage")).andExpect(status().is3xxRedirection());
    }

    // ===================== 단일 만료 =====================

    @Test
    @DisplayName("다른 회원의 세션을 만료하면 204 — 대상의 다음 API 요청은 JSON 401, 페이지 요청은 로그인 리다이렉트, 감사 SUCCESS(대상 회원·라벨)")
    void expireOtherMembersSession() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession mgr = login(manager);
        assertSessionAlive(mgr);

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(mgr)).session(adm).with(csrf()))
                .andExpect(status().isNoContent());

        assertSessionDead(mgr);
        MockHttpSession mgr2 = login(manager);   // 같은 회원의 새 로그인은 영향 없다
        assertSessionAlive(mgr2);
        assertSessionAlive(adm);

        List<Map<String, Object>> audits = sessionAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("action_result")).isEqualTo("SUCCESS");
        assertThat(audits.get(0).get("target_type")).isEqualTo("MEMBER");
        assertThat(((Number) audits.get(0).get("target_id")).longValue()).isEqualTo(manager.getId());
        assertThat(audits.get(0).get("target_label")).isEqualTo("세션 1개 만료");
        assertThat(audits.get(0).get("request_method")).isEqualTo("DELETE");
        assertThat(String.valueOf(audits.get(0).get("target_label"))).doesNotContain(mgr.getId());
    }

    @Test
    @DisplayName("만료된 세션의 페이지 요청은 /admin/login 리다이렉트")
    void expiredSession_pageRequestRedirectsToLogin() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession mgr = login(manager);
        mockMvc.perform(get("/admin/member/info").session(mgr)).andExpect(status().isOk());

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(mgr)).session(adm).with(csrf())).andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/member/info").session(mgr))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/login"));
    }

    @Test
    @DisplayName("현재 세션을 만료하려 하면 409이고 세션은 유지된다 — 본인의 다른 세션은 만료할 수 있다")
    void currentSessionProtected_otherOwnSessionAllowed() throws Exception {
        MockHttpSession current = login(admin);
        MockHttpSession other = login(admin);

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(current)).session(current).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_CONFLICT"));
        assertSessionAlive(current);
        assertSessionAlive(other);

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(other)).session(current).with(csrf()))
                .andExpect(status().isNoContent());
        assertSessionDead(other);
        assertSessionAlive(current);

        List<Map<String, Object>> audits = sessionAudits();
        assertThat(audits).extracting(a -> a.get("action_result")).containsExactly("FAIL", "SUCCESS");
        assertThat(audits.get(0).get("target_id")).as("실패 행에는 대상 회원이 남지 않는다(Aspect 정책)").isNull();
        assertThat(audits.get(0).get("target_label")).isNull();
        assertThat(((Number) audits.get(1).get("target_id")).longValue()).isEqualTo(admin.getId());
    }

    @Test
    @DisplayName("없는 핸들·이미 만료된 세션은 404 RESOURCE_NOT_FOUND(FAIL 감사), 형식이 틀린 핸들은 400이며 감사 행이 생기지 않는다")
    void notFoundAndInvalidHandle() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession mgr = login(manager);
        String handle = handleOf(mgr);
        mockMvc.perform(delete("/admin/api/sessions/{h}", handle).session(adm).with(csrf())).andExpect(status().isNoContent());

        mockMvc.perform(delete("/admin/api/sessions/{h}", handle).session(adm).with(csrf()))   // 이미 만료
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/admin/api/sessions/{h}", "f".repeat(32)).session(adm).with(csrf()))   // 없는 핸들
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/api/sessions/{h}", adm.getId()).session(adm).with(csrf()))   // 원문 세션 ID는 핸들이 아니다
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(sessionAudits()).extracting(a -> a.get("action_result")).containsExactly("SUCCESS", "FAIL", "FAIL");
        assertSessionAlive(adm);
    }

    // ===================== 회원 단위 만료 =====================

    @Test
    @DisplayName("다른 회원의 세션 전부를 만료하면 200 expiredCount — 호출자의 세션은 영향 없고 대상은 전부 401")
    void expireOtherMemberAllSessions() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession m1 = login(manager);
        MockHttpSession m2 = login(manager);

        mockMvc.perform(delete("/admin/api/sessions").param("memberId", String.valueOf(manager.getId())).session(adm).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiredCount").value(2));

        assertSessionDead(m1);
        assertSessionDead(m2);
        assertSessionAlive(adm);
        assertThat(memberRow(adm, manager)).as("만료된 세션은 활성 목록에서 빠진다").isNull();
        List<Map<String, Object>> audits = sessionAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).get("target_label")).isEqualTo("세션 2개 만료");
    }

    @Test
    @DisplayName("본인 대상 회원 단위 만료는 현재 세션만 남기고 나머지를 만료한다(라벨에 현재 세션 제외 표시)")
    void expireOwnOtherSessions_keepsCurrent() throws Exception {
        MockHttpSession current = login(admin);
        MockHttpSession other1 = login(admin);
        MockHttpSession other2 = login(admin);

        mockMvc.perform(delete("/admin/api/sessions").param("memberId", String.valueOf(admin.getId())).session(current).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiredCount").value(2));

        assertSessionAlive(current);
        assertSessionDead(other1);
        assertSessionDead(other2);
        assertThat(sessionAudits().get(0).get("target_label")).isEqualTo("세션 2개 만료(현재 세션 제외)");
    }

    @Test
    @DisplayName("세션이 없는 회원 ID도 200 expiredCount 0 — memberId 누락·0·문자열은 400이며 감사 행이 생기지 않는다")
    void memberLevel_zeroAndInvalid() throws Exception {
        MockHttpSession adm = login(admin);

        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "999999999").session(adm).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiredCount").value(0));
        mockMvc.perform(delete("/admin/api/sessions").session(adm).with(csrf())).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "0").session(adm).with(csrf())).andExpect(status().isBadRequest());
        mockMvc.perform(delete("/admin/api/sessions").param("memberId", "abc").session(adm).with(csrf())).andExpect(status().isBadRequest());

        assertThat(sessionAudits()).hasSize(1);
        assertThat(sessionAudits().get(0).get("target_label")).isEqualTo("세션 0개 만료");
    }

    // ===================== 감사 저장 실패 =====================

    @Test
    @DisplayName("감사 저장이 실패해도 만료 결과는 유지된다(감사는 최선 노력)")
    void auditFailure_doesNotUndoExpiry() throws Exception {
        MockHttpSession adm = login(admin);
        MockHttpSession mgr = login(manager);
        doThrow(new IllegalStateException("감사 저장 실패 주입")).when(auditService).log(
                any(), any(), anyString(), any(), anyString(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(delete("/admin/api/sessions/{h}", handleOf(mgr)).session(adm).with(csrf()))
                .andExpect(status().isNoContent());

        assertSessionDead(mgr);
        assertThat(sessionAudits()).isEmpty();
    }
}
