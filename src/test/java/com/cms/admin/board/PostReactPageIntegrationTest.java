package com.cms.admin.board;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.admin.permission.PermissionCache;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 게시글 관리 화면의 React 아일랜드 연결(PLAN-react-admin-posts.md 결정 5). 번들이 빠지면 화면이 조용히 비므로 비브라우저 시험에서 먼저 잡는다.
 * 화면 동작은 {@code PostScreenBrowserTest}, 공유 청크까지의 서빙은 {@code MenuReactPageIntegrationTest}가 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PostReactPageIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member admin;
    private Member manager;
    private long boardId;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "pr-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "pr-manager", Role.ROLE_MANAGER);
        jdbc.update("INSERT INTO board (name, public_yn, attachment_yn, deleted) VALUES ('pr-board', 1, 1, 0)");
        boardId = jdbc.queryForObject("SELECT MAX(id) FROM board WHERE name = 'pr-board'", Long.class);
        jdbc.update("INSERT INTO member_board_permission (member_id, board_id, action) VALUES (?, ?, 'READ')", manager.getId(), boardId);
        cache.invalidate();
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        jdbc.update("DELETE FROM board WHERE id = ?", boardId);
        cache.invalidate();
    }

    private Document page(Member member) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/board/posts").with(TestMembers.asMember(member))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    private String canPickLibrary(Member member) throws Exception {
        Element root = page(member).getElementById("postManageRoot");
        assertThat(root).as("마운트 지점").isNotNull();
        return root.attr("data-can-pick-library");
    }

    @Test
    @DisplayName("화면은 번들 하나를 모듈 스크립트로 싣고 인라인 스크립트가 없으며, 편집기·미디어 선택은 공용 전역을 재사용한다")
    void pageLoadsOneModuleAndHasNoInlineScript() throws Exception {
        Document doc = page(admin);

        assertThat(doc.select("script[type=module]")).extracting(e -> e.attr("src")).containsExactly("/js/admin-react/post-manage.js");
        assertThat(doc.select("script:not([src])")).as("인라인 스크립트").isEmpty();
        assertThat(doc.select("script[src='/js/admin/notice-editor.js']")).hasSize(1);
        assertThat(doc.select("script[src='/js/admin/media-picker.js']")).hasSize(1);
        assertThat(doc.getElementById("postModalRoot")).as("모달 마운트 지점(#wrapper 밖)").isNotNull();
        assertThat(doc.getElementById("postModalRoot").parents()).extracting(Element::id).doesNotContain("wrapper");
    }

    @Test
    @DisplayName("미디어 라이브러리 버튼 표시 값은 MEDIA 조회 권한을 따른다")
    void libraryFlagFollowsMediaPermission() throws Exception {
        assertThat(canPickLibrary(admin)).isEqualTo("true");
        assertThat(canPickLibrary(manager)).isEqualTo("false");

        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'MEDIA', 'READ')", manager.getId());
        cache.invalidate();
        assertThat(canPickLibrary(manager)).isEqualTo("true");
    }
}
