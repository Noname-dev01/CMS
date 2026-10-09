package com.cms.admin.menu.service;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.MemberStatus;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.TestMembers;
import org.junit.jupiter.api.BeforeEach;
import com.cms.config.auth.CustomUserDetails;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 사이드바 노출과 메뉴 관리 화면의 노출 안내가 같은 권한 스냅샷·같은 규칙에서 나오는지 실제 스택(SecurityConfig·판정기·캐시·MariaDB,
 * Flyway 시드 메뉴)으로 검증한다(PLAN-menu-permission-management.md 테스트 계획 5·7, §7 P-9).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MenuExposureSidebarIntegrationTest extends MariaDbContainerSupport {

    private static final String NOTICE_URL = "/admin/board/posts";   // 공지는 공지 게시판의 게시글이라 게시글 관리 메뉴가 공지 진입점이다

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;
    @Autowired ObjectMapper objectMapper;

    /** 권한 판정 키가 회원 ID라 실제 MANAGER 회원 행이 필요하다(member_permission FK). 시험마다 만들고 지운다. */
    private Member manager;

    /** 게시판 단위 위임 메뉴(BOARD_SCOPED)는 member_permission이 아니라 member_board_permission으로 노출을 정한다 — 어느 게시판이든 READ가 있으면 보인다. */
    private Long boardId;

    @BeforeEach
    void createManager() {
        manager = TestMembers.save(memberRepository, "exposure-manager", Role.ROLE_MANAGER);
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted, create_date, update_date) VALUES ('exposure-board', 1, 1, 0, NOW(6), NOW(6))");
        boardId = jdbc.queryForObject("SELECT MAX(id) FROM board WHERE name = 'exposure-board'", Long.class);
        setBoardRead(true);
    }

    @AfterEach
    void deleteManager() {
        TestMembers.delete(jdbc, List.of(manager.getId()));
        jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        cache.invalidate();
    }

    private void setBoardRead(boolean granted) {
        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", manager.getId());
        if (granted) {
            jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        }
        cache.invalidate();
    }


    private RequestPostProcessor principal(Role role) {
        if (role == Role.ROLE_MANAGER) {
            return TestMembers.asMember(manager);
        }
        Member member = Member.builder().id(1L).userId("u01").userName("u01").email("u01@example.com")
                .userType(role).status(MemberStatus.ACTIVE).build();
        CustomUserDetails details = new CustomUserDetails(member);
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private String dashboardHtml(Role role) throws Exception {
        return mockMvc.perform(get("/admin").with(principal(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private List<JsonNode> treeNodes() throws Exception {
        String json = mockMvc.perform(get("/admin/api/menus/tree").param("useYn", "all").with(principal(Role.ROLE_ADMIN)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<JsonNode> nodes = new ArrayList<>();
        collect(objectMapper.readTree(json), nodes);
        return nodes;
    }

    private static void collect(JsonNode array, List<JsonNode> out) {
        for (JsonNode node : array) {
            out.add(node);
            collect(node.path("children"), out);
        }
    }

    @Test
    @DisplayName("게시판 READ가 있는 MANAGER는 게시글 관리 메뉴를 보고, 위임 불가 메뉴는 보지 못한다. ADMIN은 전부 본다")
    void managerSidebarFollowsPermission() throws Exception {
        String manager = dashboardHtml(Role.ROLE_MANAGER);
        assertThat(manager).contains("href=\"" + NOTICE_URL + "\"");
        assertThat(manager).contains("href=\"/admin/member/info\"");
        assertThat(manager).doesNotContain("href=\"/admin/menu/manage\"");
        assertThat(manager).doesNotContain("href=\"/admin/log/manage\"");
        assertThat(manager).as("V15 권한 관리 메뉴는 위임 불가라 MANAGER에게 보이지 않는다").doesNotContain("href=\"/admin/permission/manage\"");

        String admin = dashboardHtml(Role.ROLE_ADMIN);
        assertThat(admin).contains("href=\"/admin/menu/manage\"").contains("href=\"" + NOTICE_URL + "\"")
                .contains("href=\"/admin/permission/manage\"");
    }

    @Test
    @DisplayName("권한을 회수하면 같은 요청 흐름의 다음 렌더링부터 게시글 관리 메뉴가 사라진다(재로그인 없이)")
    void revokedBoardMenuDisappearsImmediately() throws Exception {
        assertThat(dashboardHtml(Role.ROLE_MANAGER)).contains("href=\"" + NOTICE_URL + "\"");

        jdbc.update("DELETE FROM member_board_permission WHERE member_id = ?", manager.getId());
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'CREATE')", manager.getId(), boardId);
        cache.invalidate(); // READ 없는 쓰기 권한은 의존 규칙상 무효

        assertThat(dashboardHtml(Role.ROLE_MANAGER)).doesNotContain("href=\"" + NOTICE_URL + "\"");
        assertThat(dashboardHtml(Role.ROLE_ADMIN)).contains("href=\"" + NOTICE_URL + "\"");
    }

    @Test
    @DisplayName("트리 API의 exposure가 실제 MANAGER 사이드바와 일치한다 — 링크가 보이는 리프만 ALL_ADMINS·PERMISSION")
    void treeExposureMatchesManagerSidebar() throws Exception {
        for (boolean granted : new boolean[] {true, false}) {
            setBoardRead(granted);   // PERMISSION:BOARD는 어느 게시판이든 READ가 있을 때 보인다
            String html = dashboardHtml(Role.ROLE_MANAGER);

            int checkedLeaves = 0;
            for (JsonNode node : treeNodes()) {
                JsonNode data = node.path("data");
                String exposure = data.path("exposure").asText();
                String url = data.path("menuUrl").asText(null);
                boolean leaf = node.path("children").isEmpty();
                if (!leaf || url == null || !data.path("useYn").asBoolean()) {
                    continue;
                }
                boolean linked = html.contains("href=\"" + url + "\"");
                boolean predicted = exposure.equals("ALL_ADMINS") || (exposure.startsWith("PERMISSION:") && granted);
                assertThat(linked).as("granted=%s url=%s exposure=%s", granted, url, exposure).isEqualTo(predicted);
                checkedLeaves++;
            }
            assertThat(checkedLeaves).as("시드 메뉴 리프를 실제로 대조했는지").isGreaterThanOrEqualTo(3);
        }
    }
}
