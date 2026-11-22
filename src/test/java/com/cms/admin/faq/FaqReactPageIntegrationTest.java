package com.cms.admin.faq;

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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * FAQ 관리 화면의 React 아일랜드 연결(PLAN-react-admin-faq.md S3·S4·F1).
 *
 * <p>번들이 클래스패스(= jar·이미지)의 {@code static/js/admin-react/}에 들어가는지와, 템플릿이 그 번들 하나만 모듈 스크립트로 싣고 인라인
 * 스크립트 없이 서버 데이터를 {@code data-*}로만 넘기는지를 고정한다. 번들이 없으면 화면이 조용히 비므로(브라우저 시험만으로는 원인이 늦게 보인다)
 * 비브라우저 시험에서 먼저 잡는다. 화면 동작 자체는 {@code FaqAdminOrderBrowserTest}·{@code FaqModalBrowserTest}가 고정한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class FaqReactPageIntegrationTest extends MariaDbContainerSupport {

    private static final String BUNDLE = "/js/admin-react/faq-manage.js";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member admin;
    private Member manager;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "fr-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "fr-manager", Role.ROLE_MANAGER);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, List.of(admin.getId(), manager.getId()));
        cache.invalidate();
    }

    private Document page(Member member) throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/faq/manage").with(TestMembers.asMember(member))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return Jsoup.parse(result.getResponse().getContentAsString());
    }

    private static Set<String> permissions(Document doc) {
        Element root = doc.getElementById("faqManageRoot");
        assertThat(root).as("마운트 지점").isNotNull();
        return Arrays.stream(root.attr("data-permissions").split(",")).filter(s -> !s.isBlank()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("번들은 로그인 없이 정적 경로로 JavaScript로 응답된다(processResources가 build/frontend를 static/js/admin-react/로 복사)")
    void bundleIsServedFromStaticPath() throws Exception {
        MvcResult result = mockMvc.perform(get(BUNDLE)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).contains("javascript");
        assertThat(result.getResponse().getContentAsString()).as("FAQ 화면 진입점이다").contains("faqManageRoot");
    }

    @Test
    @DisplayName("화면은 번들 하나를 모듈 스크립트로 싣고, 인라인 스크립트가 없다")
    void pageLoadsOneModuleAndHasNoInlineScript() throws Exception {
        Document doc = page(admin);

        assertThat(doc.select("script[type=module]")).extracting(e -> e.attr("src")).containsExactly(BUNDLE);
        assertThat(doc.select("script:not([src])")).as("인라인 스크립트").isEmpty();
        assertThat(doc.select("script[src='/js/admin/content-scope.js']")).as("표시 대상 규칙은 번들이 갖는다").isEmpty();
        assertThat(doc.select("script[src='/js/admin/notice-editor.js']")).as("편집기는 공용 전역을 재사용한다").hasSize(1);
        assertThat(doc.getElementById("faqModalRoot")).as("모달 마운트 지점(#wrapper 밖)").isNotNull();
        assertThat(doc.getElementById("faqModalRoot").parents()).extracting(Element::id).doesNotContain("wrapper");
        assertThat(doc.getElementById("faqScopeSites")).as("표시 대상 사이트 목록").isNotNull();
    }

    @Test
    @DisplayName("권한 키는 data-permissions로 넘긴다 — ADMIN은 쓰기 키를 모두 갖고, READ만 위임받은 MANAGER는 쓰기 키가 없다")
    void permissionsArePassedAsDataAttribute() throws Exception {
        assertThat(permissions(page(admin))).contains("FAQ:CREATE", "FAQ:UPDATE", "FAQ:DELETE");

        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'FAQ', 'READ')", manager.getId());
        cache.invalidate();
        assertThat(permissions(page(manager))).doesNotContain("FAQ:CREATE", "FAQ:UPDATE", "FAQ:DELETE");
    }
}
