package com.cms.admin.menu;

import com.cms.admin.member.domain.Member;
import com.cms.admin.member.domain.Role;
import com.cms.admin.member.repository.MemberRepository;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import com.cms.support.TestMembers;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
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

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 메뉴 관리 화면의 React 아일랜드 연결과 공유 청크(PLAN-react-admin-menu.md S3·S4·F1). 진입점이 둘이라 공용 모듈이 해시 이름 청크가 되고, 청크 하나라도
 * 빠지면 두 화면이 모두 조용히 빈다 — 진입점에서 시작해 정적 import를 끝까지 따라가며 모두 응답되는지 본다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class MenuReactPageIntegrationTest extends MariaDbContainerSupport {

    private static final String BASE = "/js/admin-react/";
    private static final Pattern STATIC_IMPORT = Pattern.compile("(?:\\bfrom|\\bimport)\\s*[\"'`](\\.{1,2}/[^\"'`]+)[\"'`]");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository memberRepository;

    private Member admin;

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "mr-admin", Role.ROLE_ADMIN);
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, List.of(admin.getId()));
    }

    private static List<String> importsOf(String source) {
        List<String> found = new ArrayList<>();
        Matcher matcher = STATIC_IMPORT.matcher(source);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    @Test
    @DisplayName("두 진입점과 거기서 이어지는 모든 공유 청크가 로그인 없이 JavaScript로 응답된다")
    void everyModuleOfBothEntriesIsServed() throws Exception {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(BASE + "faq-manage.js", BASE + "menu-manage.js"));
        while (!queue.isEmpty()) {
            String path = queue.poll();
            if (!seen.add(path)) {
                continue;
            }
            MvcResult result = mockMvc.perform(get(path)).andReturn();
            assertThat(result.getResponse().getStatus()).as(path).isEqualTo(200);
            assertThat(result.getResponse().getContentType()).as(path).contains("javascript");
            for (String specifier : importsOf(result.getResponse().getContentAsString())) {
                queue.add(URI.create(path).resolve(specifier).getPath());
            }
        }
        assertThat(seen).as("진입점 둘은 React를 공유한다").anyMatch(path -> path.startsWith(BASE + "chunks/"));
    }

    @Test
    @DisplayName("검사 자체 회귀: 압축된 import 모양을 모두 찾는다")
    void importExtractorFindsMinifiedForms() {
        assertThat(importsOf("import{r as e}from\"./chunks/a-1.js\";import\"./chunks/b-2.js\";import x from'../c.js';export*from`./d.js`"))
                .containsExactly("./chunks/a-1.js", "./chunks/b-2.js", "../c.js", "./d.js");
        assertThat(importsOf("const a=\"./not-import.js\"")).isEmpty();
    }

    @Test
    @DisplayName("화면은 번들 하나를 모듈 스크립트로 싣고, 인라인 스크립트와 외부 CDN이 없다")
    void pageLoadsOneModuleAndHasNoInlineScript() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/menu/manage").with(TestMembers.asMember(admin))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Document doc = Jsoup.parse(result.getResponse().getContentAsString());

        assertThat(doc.select("script[type=module]")).extracting(e -> e.attr("src")).containsExactly(BASE + "menu-manage.js");
        assertThat(doc.select("script:not([src])")).as("인라인 스크립트").isEmpty();
        assertThat(doc.html()).doesNotContain("jstree").doesNotContain("cdnjs");
    }
}
