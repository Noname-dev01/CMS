package com.cms.admin.page;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 사이트 관리 그룹의 세 화면(정적 페이지 관리·메뉴 관리·사이트 설정, PLAN-content-menu-unify.md U3) 실제 스택 시험. 정적 페이지 화면의 게이트는
 * SITE_PAGE 기능 그대로이고, 메뉴·사이트 화면은 ADMIN 전용(캐치올)이다. 서로의 마크업·스크립트가 섞이지 않아야 한다.
 * 화면 안의 JS 동작(드래그·반영·새로고침 조정)은 시험 대상이 아니라 브라우저 시험·실기(Playwright)로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentManagePageIntegrationTest extends MariaDbContainerSupport {

    private static final String PAGE = "/admin/site-page/manage";
    private static final String MENU = "/admin/menu/manage";
    private static final String SITE = "/admin/site/manage";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PermissionCache cache;
    @Autowired MemberRepository memberRepository;

    private Member admin;
    private Member manager;
    private final List<Long> memberIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        admin = TestMembers.save(memberRepository, "cm-admin", Role.ROLE_ADMIN);
        manager = TestMembers.save(memberRepository, "cm-manager", Role.ROLE_MANAGER);
        memberIds.add(admin.getId());
        memberIds.add(manager.getId());
    }

    @AfterEach
    void cleanUp() {
        TestMembers.delete(jdbc, memberIds);
        cache.invalidate();
    }

    private void grantRead() {
        jdbc.update("DELETE FROM member_permission WHERE member_id = ?", manager.getId());
        jdbc.update("INSERT INTO member_permission (member_id, feature, action) VALUES (?, 'SITE_PAGE', 'READ')", manager.getId());
        cache.invalidate();
    }

    private String html(String url, Member member) throws Exception {
        MvcResult result = mockMvc.perform(get(url).with(TestMembers.asMember(member))).andReturn();
        assertThat(result.getResponse().getStatus()).as(url).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    private static void assertNoDuplicateIds(Document doc) {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Element element : doc.getElementsByAttribute("id")) {
            if (!seen.add(element.id())) {
                duplicates.add(element.id());
            }
        }
        assertThat(duplicates).as("중복 id").isEmpty();
    }

    @Test
    @DisplayName("ADMIN 정적 페이지 화면: 정적 페이지 패널만 있고 탭·메뉴·사이트 마크업·jstree가 없으며 중복 id가 없다")
    void adminPageScreen_hasOnlyPagePanel() throws Exception {
        String html = html(PAGE, admin);
        Document doc = Jsoup.parse(html);

        assertThat(doc.select("h1").text()).isEqualTo("정적 페이지 관리");
        assertThat(doc.getElementById("pagesTabPanel")).isNotNull();
        assertThat(doc.getElementById("pageBtnSave")).isNotNull();
        assertThat(doc.getElementById("contentTabs")).as("탭 내비게이션은 없다").isNull();
        assertThat(doc.getElementById("menuTabPanel")).isNull();
        assertThat(doc.getElementById("siteTabPanel")).isNull();
        assertThat(doc.getElementById("menuTree")).isNull();
        assertThat(html).doesNotContain("jstree").doesNotContain("/admin/api/menus").doesNotContain("/admin/api/sites")
                .doesNotContain("ContentScreen").doesNotContain("content:sites-changed").doesNotContain("scopeStaleNotice");
        assertNoDuplicateIds(doc);
    }

    @Test
    @DisplayName("ADMIN 정적 페이지 화면: 옛 북마크(#menu·#site)를 새 화면으로 옮기는 호환 스크립트가 있다 — ADMIN 응답에만")
    void adminPageScreen_hasLegacyHashRedirect() throws Exception {
        assertThat(html(PAGE, admin)).contains("'#menu': '/admin/menu/manage'").contains("'#site': '/admin/site/manage'");
    }

    @Test
    @DisplayName("ADMIN 메뉴 화면: 200(302 아님), 메뉴 패널·jstree·모달 2개(패널 밖), 새로고침 안내가 있고 정적 페이지·사이트 마크업은 없다")
    void adminMenuScreen_hasMenuPanelOnly() throws Exception {
        String html = html(MENU, admin);
        Document doc = Jsoup.parse(html);

        assertThat(doc.select("h1").text()).isEqualTo("메뉴 관리");
        assertThat(doc.getElementById("menuTabPanel")).isNotNull();
        assertThat(doc.getElementById("menuTree")).isNotNull();
        assertThat(doc.getElementById("siteSelect")).as("편집할 사이트 선택").isNotNull();
        assertThat(doc.getElementById("contentReloadNotice")).isNotNull();
        assertThat(doc.getElementById("contentReloadNow")).isNotNull();
        assertThat(doc.getElementById("contentTabs")).isNull();
        assertThat(doc.getElementById("pagesTabPanel")).isNull();
        assertThat(doc.getElementById("siteTabPanel")).isNull();
        for (String modalId : List.of("permanentDeleteModal", "iconPickerModal")) {
            assertThat(doc.getElementById(modalId)).as(modalId).isNotNull();
            assertThat(doc.selectFirst("#menuTabPanel #" + modalId)).as(modalId + "는 패널 밖").isNull();
        }
        assertThat(html).contains("jstree.min.js").contains("content:menu-applied")
                .doesNotContain("/admin/api/site-pages").doesNotContain("ContentScreen")
                .doesNotContain("content:menu-applying").doesNotContain("content:sites-changed");
        assertNoDuplicateIds(doc);
    }

    @Test
    @DisplayName("ADMIN 사이트 설정 화면: 사이트 패널만 있고 메뉴·정적 페이지 마크업·jstree·Quill이 없다")
    void adminSiteScreen_hasSitePanelOnly() throws Exception {
        String html = html(SITE, admin);
        Document doc = Jsoup.parse(html);

        assertThat(doc.select("h1").text()).isEqualTo("사이트 설정");
        assertThat(doc.getElementById("siteTabPanel")).isNotNull();
        assertThat(doc.getElementById("siteTableBody")).isNotNull();
        assertThat(doc.getElementById("siteBtnSave")).isNotNull();
        assertThat(doc.getElementById("contentTabs")).isNull();
        assertThat(doc.getElementById("menuTabPanel")).isNull();
        assertThat(doc.getElementById("pagesTabPanel")).isNull();
        assertThat(html).doesNotContain("jstree").doesNotContain("quill").doesNotContain("/admin/api/menus")
                .doesNotContain("/admin/api/site-pages").doesNotContain("ContentScreen").doesNotContain("content:sites-changed");
        assertNoDuplicateIds(doc);
    }

    @Test
    @DisplayName("스크립트 배치 순서: 의존성(jQuery·jstree·Quill·notice-editor) 뒤에 각 화면의 조각 스크립트가 온다")
    void scriptsComeAfterDependencies() throws Exception {
        String page = html(PAGE, admin);
        int jquery = page.indexOf("/vendor/jquery/jquery.min.js");
        int quill = page.indexOf("/vendor/quill/quill.js");
        int editor = page.indexOf("/js/admin/notice-editor.js");
        int permissions = page.indexOf("const MY_PERMISSIONS");
        int pageScript = page.indexOf("const PAGES_URL");
        assertThat(List.of(jquery, quill, editor, permissions, pageScript)).doesNotContain(-1);
        assertThat(editor).isLessThan(pageScript);
        assertThat(permissions).as("위임 권한 키는 정적 페이지 스크립트보다 먼저 선언된다").isLessThan(pageScript);
        assertThat(page.indexOf("NoticeEditor.create(")).isGreaterThan(editor);

        String menu = html(MENU, admin);
        int menuJquery = menu.indexOf("/vendor/jquery/jquery.min.js");
        int jstree = menu.indexOf("jstree.min.js");
        int menuScript = menu.indexOf("$(document).ready(function ()");
        assertThat(List.of(menuJquery, jstree, menuScript)).doesNotContain(-1);
        assertThat(menuJquery).isLessThan(jstree);
        assertThat(jstree).isLessThan(menuScript);

        String site = html(SITE, admin);
        int siteJquery = site.indexOf("/vendor/jquery/jquery.min.js");
        int siteScript = site.indexOf("$(document).ready(function ()");
        assertThat(List.of(siteJquery, siteScript)).doesNotContain(-1);
        assertThat(siteJquery).isLessThan(siteScript);
    }

    @Test
    @DisplayName("MANAGER(SITE_PAGE 조회): 정적 페이지 화면만 — 메뉴·사이트 마크업·API 문자열·옛 북마크 호환 스크립트가 응답에 아예 없다")
    void managerSeesOnlyPageScreen() throws Exception {
        grantRead();

        String html = html(PAGE, manager);
        Document doc = Jsoup.parse(html);

        assertThat(doc.getElementById("pagesTabPanel")).isNotNull();
        assertThat(doc.select("h1").text()).isEqualTo("정적 페이지 관리");
        assertThat(doc.getElementById("menuTabPanel")).isNull();
        assertThat(doc.getElementById("siteTabPanel")).isNull();
        assertThat(doc.getElementById("contentTabs")).isNull();
        assertThat(html).doesNotContain("jstree").doesNotContain("/admin/api/menus").doesNotContain("/admin/api/sites")
                .doesNotContain("/admin/menu/manage").doesNotContain("/admin/site/manage").doesNotContain("siteTableBody");
        assertNoDuplicateIds(doc);
    }

    @Test
    @DisplayName("MANAGER는 SITE_PAGE 조회가 있어도 메뉴·사이트 화면과 API가 403이다 — 숨김은 UX이고 서버 판정이 최종이다")
    void managerCannotReachMenuOrSite() throws Exception {
        grantRead();

        assertThat(mockMvc.perform(get(MENU).with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get(SITE).with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/api/menus/tree").with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(put("/admin/api/menus/structure").with(TestMembers.asMember(manager)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"menus\":[]}")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(get("/admin/api/sites").with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(post("/admin/api/sites").with(TestMembers.asMember(manager)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"siteKey\":\"cmp-mgr\",\"siteName\":\"x\"}")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("게이트: 권한 없는 MANAGER는 정적 페이지 화면이 403, 익명은 세 화면 모두 로그인으로 보낸다")
    void gates() throws Exception {
        assertThat(mockMvc.perform(get(PAGE).with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus()).isEqualTo(403);

        for (String url : List.of(PAGE, MENU, SITE)) {
            MvcResult anonymous = mockMvc.perform(get(url)).andReturn();
            assertThat(anonymous.getResponse().getStatus()).as(url).isEqualTo(302);
            assertThat(anonymous.getResponse().getRedirectedUrl()).as(url).endsWith("/admin/login");
        }
    }
}
