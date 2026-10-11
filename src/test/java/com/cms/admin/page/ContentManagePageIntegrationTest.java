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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 정적 페이지 + 메뉴 구조 통합 화면(PLAN-content-menu-unify.md) 실제 스택 시험. 게이트는 정적 페이지 기능(SITE_PAGE) 그대로이고,
 * 메뉴 탭·모달·jstree는 ADMIN 응답에만 있어야 하며(MANAGER에게는 마크업·스크립트·CDN 참조까지 없다), 옛 {@code /admin/menu/manage}는
 * 새 화면의 메뉴 탭으로 302 된다. 화면 안의 JS 동작(드래그·반영·새로고침 조정)은 시험 대상이 아니라 실기(Playwright)로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class ContentManagePageIntegrationTest extends MariaDbContainerSupport {

    private static final String PAGE = "/admin/site-page/manage";

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

    private String html(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("ADMIN: 정적 페이지·메뉴 두 패널과 탭, jstree가 모두 있고 렌더링된 HTML에 중복 id가 없다")
    void adminSeesBothPanels_withUniqueIds() throws Exception {
        MvcResult result = mockMvc.perform(get(PAGE).with(TestMembers.asMember(admin))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String html = html(result);
        Document doc = Jsoup.parse(html);

        assertThat(doc.getElementById("pagesTabPanel")).isNotNull();
        assertThat(doc.getElementById("menuTabPanel")).isNotNull();
        assertThat(doc.getElementById("contentTabs")).as("탭 내비게이션").isNotNull();
        assertThat(doc.select("h1").text()).isEqualTo("정적 페이지 · 메뉴 관리");
        assertThat(doc.getElementById("menuTree")).isNotNull();
        assertThat(doc.getElementById("pageBtnSave")).as("정적 페이지 저장 버튼은 메뉴의 btnSave와 id가 다르다").isNotNull();
        assertThat(doc.getElementById("btnSave")).isNotNull();
        assertThat(html).contains("jstree.min.js");

        // 합쳐진 화면에서 id 충돌은 곧 버그다(getElementById가 첫 요소만 돌려준다)
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
    @DisplayName("ADMIN: 메뉴 모달(영구삭제·아이콘 선택)은 숨겨지는 탭 패널 밖에 있다 — 패널 안이면 탭 전환 때 배경막만 남아 화면이 갇힌다")
    void menuModalsAreOutsideTabPanels() throws Exception {
        Document doc = Jsoup.parse(html(mockMvc.perform(get(PAGE).with(TestMembers.asMember(admin))).andReturn()));

        for (String modalId : List.of("permanentDeleteModal", "iconPickerModal")) {
            assertThat(doc.getElementById(modalId)).as(modalId).isNotNull();
            assertThat(doc.selectFirst("#menuTabPanel #" + modalId)).as(modalId + "는 메뉴 패널 안에 없다").isNull();
            assertThat(doc.selectFirst(".tab-content #" + modalId)).as(modalId + "는 탭 콘텐츠 안에 없다").isNull();
        }
    }

    @Test
    @DisplayName("스크립트 배치 순서: 의존성(jQuery·jstree·Quill·notice-editor) 뒤에 조각 스크립트가 온다 — 앞에 두면 $·NoticeEditor가 없는 채 실행된다")
    void scriptsComeAfterDependencies() throws Exception {
        String html = html(mockMvc.perform(get(PAGE).with(TestMembers.asMember(admin))).andReturn());

        int jquery = html.indexOf("/vendor/jquery/jquery.min.js");
        int jstree = html.indexOf("jstree.min.js");
        int quill = html.indexOf("/vendor/quill/quill.js");
        int editor = html.indexOf("/js/admin/notice-editor.js");
        int permissions = html.indexOf("const MY_PERMISSIONS");
        int pageScript = html.indexOf("const PAGES_URL");
        int menuScript = html.indexOf("$(document).ready(function ()");

        assertThat(List.of(jquery, jstree, quill, editor, permissions, pageScript, menuScript)).doesNotContain(-1);
        assertThat(jquery).isLessThan(jstree);
        assertThat(editor).isLessThan(pageScript);
        assertThat(jstree).isLessThan(menuScript);
        assertThat(permissions).as("위임 권한 키는 정적 페이지 스크립트보다 먼저 선언된다").isLessThan(pageScript);
        assertThat(html.indexOf("NoticeEditor.create(")).isGreaterThan(editor);
    }

    @Test
    @DisplayName("ADMIN: 셸이 해시 변경(hashchange)을 듣는다 — 같은 문서에서 해시만 바뀌어도 탭이 따라가야 한다(동작은 실기로 확인)")
    void shellListensToHashChanges() throws Exception {
        String html = html(mockMvc.perform(get(PAGE).with(TestMembers.asMember(admin))).andReturn());

        assertThat(html).contains("addEventListener('hashchange'").contains("history.replaceState");
    }

    @Test
    @DisplayName("MANAGER(SITE_PAGE 조회): 정적 페이지 패널만 — 메뉴 패널·탭·모달·jstree·메뉴 API 문자열이 응답에 아예 없다")
    void managerSeesOnlyPagePanel() throws Exception {
        grantRead();

        MvcResult result = mockMvc.perform(get(PAGE).with(TestMembers.asMember(manager))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String html = html(result);
        Document doc = Jsoup.parse(html);

        assertThat(doc.getElementById("pagesTabPanel")).isNotNull();
        assertThat(doc.select("h1").text()).as("MANAGER에게는 메뉴를 관리한다는 머리글을 보이지 않는다").isEqualTo("정적 페이지 관리");
        assertThat(doc.getElementById("menuTabPanel")).isNull();
        assertThat(doc.getElementById("contentTabs")).as("탭이 하나뿐이라 내비게이션을 그리지 않는다").isNull();
        assertThat(doc.getElementById("menuTree")).isNull();
        assertThat(doc.getElementById("permanentDeleteModal")).isNull();
        assertThat(doc.getElementById("iconPickerModal")).isNull();
        assertThat(html).doesNotContain("jstree").doesNotContain("/admin/api/menus").doesNotContain("menuStructureApplied");
    }

    @Test
    @DisplayName("MANAGER는 SITE_PAGE 조회가 있어도 메뉴 API를 직접 호출하면 403이다 — 탭 숨김은 UX이고 서버 판정이 최종이다")
    void managerCannotCallMenuApis() throws Exception {
        grantRead();

        assertThat(mockMvc.perform(get("/admin/api/menus/tree").with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(mockMvc.perform(put("/admin/api/menus/structure").with(TestMembers.asMember(manager)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"menus\":[]}")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("권한 없는 MANAGER는 통합 화면이 403이고 익명은 로그인으로 보낸다")
    void gateIsUnchanged() throws Exception {
        assertThat(mockMvc.perform(get(PAGE).with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus()).isEqualTo(403);

        MvcResult anonymous = mockMvc.perform(get(PAGE)).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(302);
        assertThat(anonymous.getResponse().getRedirectedUrl()).endsWith("/admin/login");
    }

    @Test
    @DisplayName("옛 /admin/menu/manage: ADMIN은 메뉴 탭으로 302(쿼리 없이 해시만), MANAGER는 SITE_PAGE 조회가 있어도 403, 익명은 로그인")
    void legacyMenuUrlRedirectsForAdminOnly() throws Exception {
        grantRead();

        MvcResult adminResult = mockMvc.perform(get("/admin/menu/manage").with(TestMembers.asMember(admin))).andReturn();
        assertThat(adminResult.getResponse().getStatus()).isEqualTo(302);
        assertThat(adminResult.getResponse().getRedirectedUrl()).isEqualTo("/admin/site-page/manage#menu");

        assertThat(mockMvc.perform(get("/admin/menu/manage").with(TestMembers.asMember(manager))).andReturn().getResponse().getStatus())
                .isEqualTo(403);

        MvcResult anonymous = mockMvc.perform(get("/admin/menu/manage")).andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(302);
        assertThat(anonymous.getResponse().getRedirectedUrl()).endsWith("/admin/login");
    }
}
