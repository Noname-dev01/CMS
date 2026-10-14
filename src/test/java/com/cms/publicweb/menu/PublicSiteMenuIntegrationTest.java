package com.cms.publicweb.menu;

import com.cms.publicweb.menu.dto.PublicMenuItem;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 메뉴 렌더링(PLAN-site-menu.md PR ⑤): 사이트 메뉴 트리 구성 규칙(서비스)과 실제 공개 화면의 렌더링·사이트 분리·이스케이프·실패 격리를 고정한다.
 * 행은 JDBC로 직접 만들고 시험이 끝나면 지운다(시드 관리자 사이트의 메뉴 {@code site_id=1}과 시드 공개 사이트는 건드리지 않는다).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicSiteMenuIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PublicMenuService publicMenuService;

    private String ip;
    private long alpha;
    private long beta;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.98." + (n / 250) + "." + (n % 250 + 1);
        reset(publicMenuService);
        cleanAll();
        alpha = site("alpha", true, true);
        beta = site("beta", true, false);
    }

    @AfterEach
    void cleanUp() {
        cleanAll();
    }

    private void cleanAll() {
        jdbc.update("DELETE FROM menu WHERE site_id > 2");
        jdbc.update("DELETE FROM site WHERE id > 2");
    }

    private long site(String key, boolean use, boolean allowExternal) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, 'PUBLIC', ?, ?, 0)",
                key, key, allowExternal, use);
        return jdbc.queryForObject("SELECT id FROM site WHERE site_key = ?", Long.class, key);
    }

    private long menu(long siteId, Long parent, int ord, String name, String url, boolean use) {
        jdbc.update("INSERT INTO menu (menu_name, menu_url, use_yn, ord, up_menu_no, site_id) VALUES (?, ?, ?, ?, ?, ?)",
                name, url, use, ord, parent, siteId);
        return jdbc.queryForObject("SELECT MAX(menu_no) FROM menu", Long.class);
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private String body(String path) throws Exception {
        return mockMvc.perform(get(path).with(fromIp())).andReturn().getResponse().getContentAsString();
    }

    private static List<String> names(List<PublicMenuItem> items) {
        return items.stream().map(PublicMenuItem::name).toList();
    }

    // ---- 서비스: 트리 구성 규칙 ----

    @Test
    @DisplayName("활성 메뉴만 ord·menuNo 순으로 트리를 만든다 — 비활성 노드, 비활성 부모 아래 활성 자식은 제외")
    void tree_activeOnly_ordered_inactiveParentHidesChildren() {
        long second = menu(alpha, null, 2, "둘째", "/alpha/faq", true);
        long first = menu(alpha, null, 1, "첫째", "/alpha/notices", true);
        menu(alpha, first, 2, "첫째-b", "/alpha/boards/1", true);
        menu(alpha, first, 1, "첫째-a", "/alpha/boards/2", true);
        menu(alpha, null, 3, "꺼진 메뉴", "/alpha/x", false);
        long inactiveParent = menu(alpha, null, 4, "꺼진 부모", "/alpha/y", false);
        menu(alpha, inactiveParent, 1, "꺼진 부모 아래 활성 자식", "/alpha/z", true);

        List<PublicMenuItem> items = publicMenuService.findMenu(alpha);

        assertThat(names(items)).containsExactly("첫째", "둘째");
        assertThat(names(items.get(0).children())).containsExactly("첫째-a", "첫째-b");
        assertThat(second).isPositive();
    }

    @Test
    @DisplayName("깊이 3단까지만 그리고 4단은 자른다")
    void tree_depthCappedAtThree() {
        long l1 = menu(alpha, null, 1, "1단", "/alpha/a", true);
        long l2 = menu(alpha, l1, 1, "2단", "/alpha/b", true);
        long l3 = menu(alpha, l2, 1, "3단", "/alpha/c", true);
        menu(alpha, l3, 1, "4단", "/alpha/d", true);

        List<PublicMenuItem> items = publicMenuService.findMenu(alpha);

        PublicMenuItem third = items.get(0).children().get(0).children().get(0);
        assertThat(third.name()).isEqualTo("3단");
        assertThat(third.children()).isEmpty();
    }

    @Test
    @DisplayName("링크 없는 그룹은 보이는 자식이 있을 때만 남고, 링크도 자식도 없는 노드는 제거된다")
    void tree_groupKeptOnlyWithVisibleChildren() {
        long group = menu(alpha, null, 1, "그룹", null, true);
        menu(alpha, group, 1, "자식", "/alpha/faq", true);
        menu(alpha, null, 2, "빈 그룹", null, true);
        long emptyAfterPrune = menu(alpha, null, 3, "자식도 못 그리는 그룹", null, true);
        menu(alpha, emptyAfterPrune, 1, "관리 경로 자식", "/admin/member/manage", true);

        List<PublicMenuItem> items = publicMenuService.findMenu(alpha);

        assertThat(names(items)).containsExactly("그룹");
        assertThat(items.get(0).url()).isNull();
        assertThat(names(items.get(0).children())).containsExactly("자식");
    }

    @Test
    @DisplayName("출력 시 재검증: 정책을 어기는 URL(관리 경로·허용 꺼진 사이트의 외부 URL)은 링크 없는 라벨이 된다")
    void tree_revalidatesUrlsAtOutput() {
        long group = menu(beta, null, 1, "그룹", "/admin/member/manage", true);
        menu(beta, group, 1, "외부(불허)", "https://example.com/x", true);
        menu(beta, group, 2, "내부", "/beta/faq", true);
        menu(beta, null, 2, "javascript", "javascript:alert(1)", true);

        List<PublicMenuItem> items = publicMenuService.findMenu(beta);

        assertThat(names(items)).containsExactly("그룹");
        assertThat(items.get(0).url()).isNull();
        assertThat(names(items.get(0).children())).containsExactly("내부");
    }

    @Test
    @DisplayName("외부 링크는 허용된 사이트에서만 external=true로 나온다")
    void tree_externalFlag() {
        menu(alpha, null, 1, "외부", "https://example.com/x", true);
        menu(alpha, null, 2, "내부", "/alpha/faq", true);

        List<PublicMenuItem> items = publicMenuService.findMenu(alpha);

        assertThat(items).extracting(PublicMenuItem::external).containsExactly(true, false);
    }

    @Test
    @DisplayName("비활성 사이트·관리자 사이트·없는 사이트는 빈 목록, 다른 사이트의 메뉴는 섞이지 않는다")
    void tree_siteBoundaries() {
        menu(alpha, null, 1, "알파 메뉴", "/alpha/faq", true);
        jdbc.update("UPDATE site SET use_yn = 0 WHERE id = ?", beta);
        menu(beta, null, 1, "베타 메뉴", "/beta/faq", true);

        assertThat(names(publicMenuService.findMenu(alpha))).containsExactly("알파 메뉴");
        assertThat(publicMenuService.findMenu(beta)).isEmpty();
        assertThat(publicMenuService.findMenu(1L)).isEmpty();
        assertThat(publicMenuService.findMenu(987654321L)).isEmpty();
    }

    // ---- 실제 공개 화면 ----

    @Test
    @DisplayName("메인·FAQ·문의(닫힘) 화면이 그 사이트의 메뉴를 그리고 다른 사이트의 메뉴는 그리지 않는다")
    void pages_renderOnlyOwnSiteMenu() throws Exception {
        menu(alpha, null, 1, "알파 소개", "/alpha/faq", true);
        menu(beta, null, 1, "베타 소개", "/beta/faq", true);

        for (String path : List.of("/alpha", "/alpha/faq", "/alpha/inquiry")) {
            String html = body(path);
            assertThat(html).as(path).contains("class=\"site-menu\"").contains("알파 소개").doesNotContain("베타 소개");
        }
        assertThat(body("/beta")).contains("베타 소개").doesNotContain("알파 소개");
    }

    @Test
    @DisplayName("메뉴가 없는 사이트는 nav를 그리지 않는다")
    void pages_noMenuNoNav() throws Exception {
        assertThat(body("/alpha")).doesNotContain("class=\"site-menu\"");
    }

    @Test
    @DisplayName("메뉴 수정이 즉시 반영된다(캐시 없음) — 이름 변경·비활성화")
    void pages_changesAppearImmediately() throws Exception {
        long id = menu(alpha, null, 1, "바뀌기 전", "/alpha/faq", true);
        assertThat(body("/alpha")).contains("바뀌기 전");

        jdbc.update("UPDATE menu SET menu_name = '바뀐 뒤' WHERE menu_no = ?", id);
        assertThat(body("/alpha")).contains("바뀐 뒤").doesNotContain("바뀌기 전");

        jdbc.update("UPDATE menu SET use_yn = 0 WHERE menu_no = ?", id);
        assertThat(body("/alpha")).doesNotContain("바뀐 뒤").doesNotContain("class=\"site-menu\"");
    }

    @Test
    @DisplayName("이름은 이스케이프되고, 직접 삽입된 javascript: URL은 링크로 나오지 않으며, 외부 링크는 noopener noreferrer + 새 탭")
    void pages_escapingAndLinkHardening() throws Exception {
        menu(alpha, null, 1, "<script>alert(1)</script>", "/alpha/faq", true);
        long group = menu(alpha, null, 2, "그룹", null, true);
        menu(alpha, group, 1, "위험", "javascript:alert(1)", true);
        menu(alpha, group, 2, "바깥", "https://example.com/x", true);

        String html = body("/alpha");

        assertThat(html).doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).doesNotContain("javascript:").doesNotContain("위험");
        assertThat(html).containsPattern("<a href=\"https://example.com/x\"[^>]*target=\"_blank\"[^>]*rel=\"noopener noreferrer\"[^>]*>바깥</a>");
        assertThat(html).containsPattern("<a href=\"/alpha/faq\"(?![^>]*target)[^>]*>");
        assertThat(html).doesNotContain("href=\"/admin");
    }

    @Test
    @DisplayName("퍼센트 인코딩 호스트로 같은 출처의 관리 경로를 가리키는 직접 삽입 URL은 링크로 나오지 않는다(코드 리뷰 지적)")
    void pages_percentEncodedHostLinkIsNotRendered() throws Exception {
        menu(alpha, null, 1, "우회", "https://%63ms.example/admin", true);
        menu(alpha, null, 2, "정상", "/alpha/faq", true);

        String html = body("/alpha");

        assertThat(html).doesNotContain("%63ms").doesNotContain("우회").contains("정상");
    }

    @Test
    @DisplayName("메뉴 조회가 실패해도 화면은 200이고 메뉴만 빠진다")
    void pages_menuFailureIsIsolated() throws Exception {
        menu(alpha, null, 1, "알파 소개", "/alpha/faq", true);
        doThrow(new IllegalStateException("db down")).when(publicMenuService).findMenu(anyLong());

        mockMvc.perform(get("/alpha").with(fromIp())).andExpect(status().isOk());
        assertThat(body("/alpha")).doesNotContain("알파 소개").doesNotContain("class=\"site-menu\"");
    }

    @Test
    @DisplayName("사이트 비활성화 뒤에는 화면도 메뉴도 404 — 메뉴 때문에 존재가 드러나지 않는다")
    void pages_inactiveSiteIsStill404() throws Exception {
        menu(alpha, null, 1, "알파 소개", "/alpha/faq", true);
        jdbc.update("UPDATE site SET use_yn = 0 WHERE id = ?", alpha);

        mockMvc.perform(get("/alpha").with(fromIp())).andExpect(status().isNotFound());
        assertThat(body("/alpha")).doesNotContain("알파 소개");
    }
}
