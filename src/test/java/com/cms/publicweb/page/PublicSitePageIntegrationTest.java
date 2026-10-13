package com.cms.publicweb.page;

import com.cms.publicweb.page.dto.PublicSitePageRevisions;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 공개 정적 페이지 실제 스택 시험(PLAN-static-page.md 쟁점 4·10·11·12): 현재 버전·개정 목록·이전 개정, 비노출/없음/형식 위반의 같은 404,
 * 개정 번호 구간 페이지네이션 경계, 출력 시 재정리, 푸터. 레이트리밋은 규칙을 켜야 해서 {@code PublicSitePageRateLimitIntegrationTest}가 따로 본다. 행은 JDBC로 직접 만들고 시험이 끝나면 지운다.
 * 공개 요청은 시험마다 고유 원격 IP를 쓴다(테스트 환경은 레이트리밋이 꺼져 있다).
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicSitePageIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicSitePageService service;

    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.91." + (n / 250) + "." + (n % 250 + 1);
        cleanPages();
    }

    @AfterEach
    void cleanUp() {
        cleanPages();
    }

    private void cleanPages() {
        jdbc.update("DELETE FROM site_page_revision");
        jdbc.update("DELETE FROM site_page");
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** 개정 1..revisions를 가진 페이지를 만든다. 개정 i의 제목은 "제목 i", 본문은 "&lt;p&gt;본문 i&lt;/p&gt;". */
    private long page(String slug, boolean use, boolean footer, int revisions) {
        jdbc.update("INSERT INTO site_page (slug, current_revision_no, version, use_yn, footer_yn, create_date, update_date) VALUES (?, ?, 1, ?, ?, NOW(), NOW())",
                slug, revisions, use, footer);
        long id = jdbc.queryForObject("SELECT id FROM site_page WHERE slug = ?", Long.class, slug);
        for (int i = 1; i <= revisions; i++) {
            jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, ?, ?, ?, NOW())",
                    id, i, "제목 " + i, "<p>본문 " + i + "</p>");
        }
        return id;
    }

    private MvcResult getPage(String path) throws Exception {
        return mockMvc.perform(get(path).with(fromIp())).andReturn();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    // ===== 현재 버전 =====

    @Test
    @DisplayName("현재 버전: 노출 중이면 200으로 제목·본문·개정 번호·이력 링크를 보여 주고 이전 버전 안내는 없다. HEAD도 같은 상태")
    void currentVersion() throws Exception {
        page("terms", true, false, 3);

        MvcResult result = getPage("/main/pages/terms");
        String html = body(result);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(html).contains("제목 3").contains("본문 3").contains("/main/pages/terms/revisions");
        assertThat(html).doesNotContain("이전 버전입니다").doesNotContain("본문 1");
        assertThat(mockMvc.perform(head("/main/pages/terms").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("개정이 쌓이면 현재 버전은 항상 최신 개정이다(관리자 수정 후 공개 화면이 새 개정으로 바뀐다)")
    void currentFollowsLatestRevision() throws Exception {
        long id = page("follow", true, false, 1);
        assertThat(body(getPage("/main/pages/follow"))).contains("제목 1");

        jdbc.update("INSERT INTO site_page_revision (page_id, revision_no, title, content, create_date) VALUES (?, 2, '새 제목', '<p>새 본문</p>', NOW())", id);
        jdbc.update("UPDATE site_page SET current_revision_no = 2, version = 2 WHERE id = ?", id);

        String html = body(getPage("/main/pages/follow"));
        assertThat(html).contains("새 제목").contains("새 본문").doesNotContain("제목 1");
    }

    // ===== 비공개·없음·형식 위반 =====

    @Test
    @DisplayName("비노출 페이지는 현재 버전·개정 목록·이전 개정이 모두 404이고, 없는 슬러그의 404와 응답 본문이 같다(존재 여부 비노출)")
    void hiddenPageIsIndistinguishableFromMissing() throws Exception {
        page("secret", false, false, 2);

        MvcResult missing = getPage("/main/pages/does-not-exist");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        for (String path : List.of("/main/pages/secret", "/main/pages/secret/revisions", "/main/pages/secret/revisions/1", "/main/pages/secret/revisions/2")) {
            MvcResult hidden = getPage(path);
            assertThat(hidden.getResponse().getStatus()).as(path).isEqualTo(404);
            assertThat(body(hidden)).as(path + " 본문이 없는 슬러그와 같다").isEqualTo(body(missing));
        }
        assertThat(body(missing)).doesNotContain("제목 2");
    }

    @Test
    @DisplayName("슬러그 형식 위반(대문자·1자·연속 하이픈·점·밑줄·51자·예약어 등)은 DB 조회 없이 404이고 500이 아니다")
    void invalidSlugsAre404() throws Exception {
        page("valid-one", true, false, 1);
        for (String slug : List.of("Valid-One", "a", "a--b", "-ab", "ab-", "a_b", "a.b", "x".repeat(51), "revisions")) {
            assertThat(getPage("/main/pages/" + slug).getResponse().getStatus()).as(slug).isEqualTo(404);
        }
        assertThat(getPage("/main/pages/valid-one").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("라우트가 없는 /pages 자체와 하위 경로는 404다(공개 규칙이 열려 있어도 핸들러가 없다)")
    void unknownRoutesAre404() throws Exception {
        page("known", true, false, 1);

        assertThat(getPage("/main/pages").getResponse().getStatus()).isEqualTo(404);
        assertThat(getPage("/main/pages/known/other").getResponse().getStatus()).isEqualTo(404);
        assertThat(getPage("/main/pages/known/revisions/1/extra").getResponse().getStatus()).isEqualTo(404);
    }

    // ===== 개정 =====

    @Test
    @DisplayName("이전 개정은 안내 문구·옛 본문으로 보이고, 현재 개정 번호로 열면 안내가 없다")
    void oldRevision() throws Exception {
        page("history", true, false, 3);

        String old = body(getPage("/main/pages/history/revisions/1"));
        assertThat(old).contains("이전 버전입니다").contains("제목 1").contains("본문 1").contains("현재 버전 보기");

        String current = body(getPage("/main/pages/history/revisions/3"));
        assertThat(current).contains("제목 3").doesNotContain("이전 버전입니다");
    }

    @Test
    @DisplayName("개정 번호 형식 위반·0·없는 번호·int 초과는 모두 404다")
    void invalidRevisionNumbers() throws Exception {
        page("rev-check", true, false, 2);

        for (String no : List.of("0", "3", "abc", "01", "-1", "1.0", "99999999999", "2147483648", "9999999999")) {
            assertThat(getPage("/main/pages/rev-check/revisions/" + no).getResponse().getStatus()).as(no).isEqualTo(404);
        }
        assertThat(getPage("/main/pages/rev-check/revisions/2").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("int 최댓값 개정 번호도 상세 열람과 개정 목록이 모두 동작한다(컨트롤러 검증 + long 산술)")
    void maxIntRevision() throws Exception {
        long id = page("max-rev", true, false, 1);
        jdbc.update("UPDATE site_page_revision SET revision_no = ? WHERE page_id = ?", Integer.MAX_VALUE, id);
        jdbc.update("UPDATE site_page SET current_revision_no = ? WHERE id = ?", Integer.MAX_VALUE, id);

        assertThat(getPage("/main/pages/max-rev/revisions/" + Integer.MAX_VALUE).getResponse().getStatus()).isEqualTo(200);
        assertThat(getPage("/main/pages/max-rev/revisions/" + (Integer.MAX_VALUE - 1)).getResponse().getStatus()).isEqualTo(404);
        PublicSitePageRevisions first = service.findRevisions("max-rev", 0).orElseThrow();
        assertThat(first.items()).extracting(item -> item.revisionNo()).containsExactly(Integer.MAX_VALUE);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.hasPrevious()).isFalse();
        assertThat(service.findRevisions("max-rev", 1).orElseThrow().items()).as("개정이 없는 구간은 빈 쪽").isEmpty();
        assertThat(service.findRevisions("max-rev", Integer.MAX_VALUE).orElseThrow().page()).as("범위를 넘는 쪽은 첫 쪽").isZero();
        assertThat(getPage("/main/pages/max-rev/revisions?page=1").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("개정 목록 쪽: 개정 20·21·40·41건에서 쪽당 20건·hasNext·1번 개정 도달 가능(번호 구간 조회)")
    void revisionListBoundaries() {
        record Case(int revisions, List<Integer> pageSizes) {}
        List<Case> cases = List.of(
                new Case(1, List.of(1)),
                new Case(20, List.of(20)),
                new Case(21, List.of(20, 1)),
                new Case(40, List.of(20, 20)),
                new Case(41, List.of(20, 20, 1)));
        for (Case c : cases) {
            cleanPages();
            page("paged", true, false, c.revisions());

            int seen = 0;
            for (int p = 0; p < c.pageSizes().size(); p++) {
                PublicSitePageRevisions result = service.findRevisions("paged", p).orElseThrow();
                String label = c.revisions() + "건 / 쪽 " + p;
                assertThat(result.items()).as(label).hasSize(c.pageSizes().get(p));
                assertThat(result.page()).as(label).isEqualTo(p);
                assertThat(result.hasPrevious()).as(label).isEqualTo(p > 0);
                assertThat(result.hasNext()).as(label).isEqualTo(p < c.pageSizes().size() - 1);
                // 최신순으로 이어지고 중복·누락이 없다
                for (var item : result.items()) {
                    assertThat(item.revisionNo()).as(label).isEqualTo(c.revisions() - seen);
                    seen++;
                }
            }
            assertThat(seen).as(c.revisions() + "건 전부 도달").isEqualTo(c.revisions());
        }
    }

    @Test
    @DisplayName("개정 목록 쪽 번호 보정: 음수·마지막 쪽 초과·int 최댓값은 첫 쪽, HTTP의 비숫자·음수·범위 초과도 200")
    void revisionListPageNormalization() throws Exception {
        page("norm", true, false, 25);

        assertThat(service.findRevisions("norm", -1).orElseThrow().page()).isZero();
        assertThat(service.findRevisions("norm", 2).orElseThrow().page()).as("마지막 쪽(1) 초과").isZero();
        assertThat(service.findRevisions("norm", Integer.MAX_VALUE).orElseThrow().page()).isZero();
        for (String page : List.of("abc", "-5", "99999999999", "1", "0", "2")) {
            assertThat(getPage("/main/pages/norm/revisions?page=" + page).getResponse().getStatus()).as(page).isEqualTo(200);
        }
        String second = body(getPage("/main/pages/norm/revisions?page=1"));
        assertThat(second).contains("개정 5").contains("개정 1").doesNotContain("개정 25");
    }

    @Test
    @DisplayName("개정 목록 화면: 현재 표시, 제목은 이스케이프, 이전/다음 링크")
    void revisionListRendering() throws Exception {
        long id = page("render", true, false, 22);
        jdbc.update("UPDATE site_page_revision SET title = '<b>굵게</b>' WHERE page_id = ? AND revision_no = 22", id);

        String html = body(getPage("/main/pages/render/revisions"));

        assertThat(html).contains("&lt;b&gt;굵게&lt;/b&gt;").doesNotContain("<b>굵게</b>");
        assertThat(html).contains("현재").contains("page=1");
        assertThat(html).contains("/main/pages/render/revisions/22");
    }

    // ===== 출력 시 정리 =====

    @Test
    @DisplayName("DB에 직접 들어온 script·이벤트 속성·img는 출력 시 제거되고, 제목은 이스케이프된다")
    void outputIsSanitized() throws Exception {
        long id = page("dirty", true, false, 1);
        jdbc.update("UPDATE site_page_revision SET title = ?, content = ? WHERE page_id = ?",
                "<script>alert(1)</script>제목",
                "<p onclick=\"x()\">정상 본문<script>alert(2)</script><img src=\"/content-images/3\"><img src=\"http://evil.example/a.png\"></p>", id);

        String html = body(getPage("/main/pages/dirty"));

        assertThat(html).contains("정상 본문");
        assertThat(html).doesNotContain("<script>alert").doesNotContain("onclick").doesNotContain("<img").doesNotContain("evil.example");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;제목");
    }

    // ===== 푸터 =====

    @Test
    @DisplayName("공개 메인 푸터: 노출 중이고 푸터로 지정된 페이지만 만든 순서대로 현재 개정 제목으로 링크되고, 제목은 이스케이프된다")
    void homeFooter() throws Exception {
        page("footer-a", true, true, 1);
        long b = page("footer-b", true, true, 2);
        page("footer-hidden", false, true, 1);
        page("footer-off", true, false, 1);
        jdbc.update("UPDATE site_page_revision SET title = '<i>B</i> 제목' WHERE page_id = ? AND revision_no = 2", b);

        String html = body(mockMvc.perform(get("/main").with(fromIp())).andReturn());

        assertThat(html).contains("home-footer");
        assertThat(html).contains("/main/pages/footer-a").contains("/main/pages/footer-b");
        assertThat(html).doesNotContain("/main/pages/footer-hidden").doesNotContain("/main/pages/footer-off");
        assertThat(html.indexOf("/main/pages/footer-a")).isLessThan(html.indexOf("/main/pages/footer-b"));
        assertThat(html).contains("&lt;i&gt;B&lt;/i&gt; 제목").doesNotContain("<i>B</i>");
    }

    @Test
    @DisplayName("푸터 대상이 없으면 푸터 요소 자체가 없다")
    void homeWithoutFooter() throws Exception {
        page("no-footer", true, false, 1);

        String html = body(mockMvc.perform(get("/main").with(fromIp())).andReturn());

        assertThat(html).doesNotContain("<footer");
    }
}
