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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 쪽지함 페이지(`/admin/member/messages`)를 실제 SecurityConfig·Thymeleaf·사이드바로 렌더링해 인가 경계를 확인한다(PLAN-admin-message.md §5-H). */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class AdminMessagePageIntegrationTest extends MariaDbContainerSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;

    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        memberIds.clear();
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

    private MvcResult getAs(Member who, String url) throws Exception {
        return mockMvc.perform(get(url).with(TestMembers.asMember(who))).andReturn();
    }

    @Test
    @DisplayName("쪽지함 페이지: ADMIN·권한 0개 MANAGER 모두 200으로 렌더링되고 ROLE_USER는 403이다(실제 SecurityConfig·사이드바)")
    void page_rendersForAdminAndManager_forbidsUser() throws Exception {
        Member admin = member("page-admin", Role.ROLE_ADMIN);
        Member manager = member("page-manager", Role.ROLE_MANAGER);
        Member plainUser = member("page-user", Role.ROLE_USER);

        for (Member who : new Member[]{admin, manager}) {
            MvcResult result = getAs(who, "/admin/member/messages");
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).contains("쪽지함").contains("messageBadge").contains("message-box.js");
        }
        assertThat(getAs(plainUser, "/admin/member/messages").getResponse().getStatus()).isEqualTo(403);
        assertThat(getAs(manager, "/admin/member/messages/x").getResponse().getStatus()).as("하위 경로는 ADMIN 캐치올").isEqualTo(403);
    }
}
