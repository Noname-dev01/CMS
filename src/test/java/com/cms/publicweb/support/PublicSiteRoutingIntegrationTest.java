package com.cms.publicweb.support;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**

 * 공개 사이트 라우팅의 실제 스택 시험(PLAN-site-menu.md PR ② P2-1~P2-6): 존재하지 않는·비활성·형식 위반·예약어·관리자 타입 사이트 키가 모두 같은 404,
 * 접두어 없는 옛 URL 404, {@code /} 302(기본 사이트, 데이터 이상이면 404), 두 번째 사이트가 같은 콘텐츠를 자기 접두어로 제공하고 모든 내부 링크가
 * 그 접두어를 가진다. MockMvc는 {@code sendError}의 오류 재디스패치를 실행하지 않으므로 같은 404 <b>화면</b>은 실서버 시험이 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicSiteRoutingIntegrationTest extends MariaDbContainerSupport {

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    /** 정적·이미지 경로는 사이트 무관 — 접두어 검사에서 제외한다. */
    private static final Pattern SITE_NEUTRAL = Pattern.compile("^/(css|js|img|vendor|banners|popups|content-images|favicon\\.ico)(/|$)");
    private static final Pattern URL_ATTRIBUTE = Pattern.compile("(?:href|action|src)=\"(/[^\"]*)\"");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private String ip;

    @BeforeEach
    void setUp() {
        int n = IP_SEQUENCE.getAndIncrement();
        ip = "10.96." + (n / 250) + "." + (n % 250 + 1);
        cleanSites();
    }

    @AfterEach
    void cleanUp() {
        cleanSites();
    }

    private void cleanSites() {
        jdbc.update("DELETE FROM site WHERE id > 2");
        jdbc.update("UPDATE site SET default_yn = CASE WHEN id = 2 THEN 1 ELSE 0 END, use_yn = 1");
    }

    private RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private void site(String key, String type, boolean useYn) {
        jdbc.update("INSERT INTO site (site_key, site_name, site_type, allow_external_link, use_yn, default_yn) VALUES (?, ?, ?, 0, ?, 0)",
                key, "사이트 " + key, type, useYn);
    }

    private int status(String path) throws Exception {
        return mockMvc.perform(get(path).with(fromIp())).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("없는·형식 위반·예약어 키는 홈과 하위 경로 모두 같은 404다 (보안 계층에서 302/403이 되지 않는다)")
    void unknownInvalidAndReservedKeys_sameNotFound() throws Exception {
        List<String> keys = List.of("nosuch", "MAIN", "a_b", "x", "-bad", "bad-", "a--b", "x".repeat(40), "notices", "boards", "pages", "faq",
                "inquiry", "login", "logout", "api", "static", "assets", "images");
        List<String> failures = new ArrayList<>();
        for (String key : keys) {
            for (String suffix : List.of("", "/notices", "/notices/1", "/boards/1", "/pages/terms", "/faq", "/inquiry")) {
                String path = "/" + key + suffix;
                int actual = status(path);
                if (actual != 404) {
                    failures.add(path + " → " + actual);
                }
            }
        }
        assertThat(failures).as("사이트 키 자리에 온 모든 비정상 키는 같은 404여야 한다").isEmpty();
    }

    @Test
    @DisplayName("관리자 타입 사이트·비활성 공개 사이트도 같은 404이고, 활성화하면 즉시 200이 된다")
    void adminTypeAndInactiveSites_notFound_thenActivated() throws Exception {
        site("opsadmin", "ADMIN", true);
        site("news", "PUBLIC", false);

        assertThat(status("/opsadmin")).isEqualTo(404);
        assertThat(status("/news")).isEqualTo(404);
        assertThat(status("/news/faq")).isEqualTo(404);

        jdbc.update("UPDATE site SET use_yn = 1 WHERE site_key = 'news'");
        assertThat(status("/news")).isEqualTo(200);
        assertThat(status("/news/faq")).isEqualTo(200);

        jdbc.update("UPDATE site SET use_yn = 0 WHERE site_key = 'news'");
        assertThat(status("/news")).as("비활성화는 캐시 없이 즉시 반영된다").isEqualTo(404);
    }

    @Test
    @DisplayName("HEAD도 같은 판정이다 — 없는 사이트는 404, 있는 사이트는 200 (본문 없음은 실서버 시험이 확인)")
    void head_sameDecision() throws Exception {
        assertThat(mockMvc.perform(head("/nosuch").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mockMvc.perform(head("/main").with(fromIp())).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("접두어 없는 옛 공개 URL은 모두 404다 (기본 거부의 302/403이 아니다)")
    void legacyUrls_areGone() throws Exception {
        for (String path : List.of("/notices", "/notices/1", "/notices/1/attachments/1", "/boards/1", "/boards/1/posts/2", "/pages/terms",
                "/pages/terms/revisions", "/faq", "/inquiry", "/faq/1", "/inquiry/1")) {
            assertThat(status(path)).as(path).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("옛 POST /inquiry는 접수되지 않는다 — 비인증은 로그인 302로 거부된다")
    void legacyInquiryPost_denied() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/inquiry").with(csrf()).with(fromIp())).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).endsWith("/admin/login");
    }

    @Test
    @DisplayName("/ 는 기본 공개 사이트로 302 — GET·HEAD 모두, 쿼리는 무시한다")
    void root_redirectsToDefaultSite() throws Exception {
        MockHttpServletResponse get = mockMvc.perform(get("/").with(fromIp())).andReturn().getResponse();
        assertThat(get.getStatus()).isEqualTo(302);
        assertThat(get.getHeader("Location")).isEqualTo("/main");

        MockHttpServletResponse head = mockMvc.perform(head("/").with(fromIp())).andReturn().getResponse();
        assertThat(head.getStatus()).isEqualTo(302);
        assertThat(head.getHeader("Location")).isEqualTo("/main");

        MockHttpServletResponse withQuery = mockMvc.perform(get("/?next=//evil.example").with(fromIp())).andReturn().getResponse();
        assertThat(withQuery.getHeader("Location")).as("오픈 리다이렉트 없음 — 이동 대상은 DB의 기본 사이트 키뿐").isEqualTo("/main");
    }

    @Test
    @DisplayName("기본 사이트 표시가 바뀌면 / 의 이동 대상이 즉시 바뀌고, 0개·2개 이상·비활성이면 404다 (fail-closed)")
    void root_followsDefaultFlag_andFailsClosed() throws Exception {
        site("news", "PUBLIC", true);
        jdbc.update("UPDATE site SET default_yn = 0 WHERE id = 2");
        jdbc.update("UPDATE site SET default_yn = 1 WHERE site_key = 'news'");
        assertThat(mockMvc.perform(get("/").with(fromIp())).andReturn().getResponse().getHeader("Location")).isEqualTo("/news");

        jdbc.update("UPDATE site SET default_yn = 1 WHERE id = 2");
        assertThat(status("/")).as("기본 표시가 2개").isEqualTo(404);

        jdbc.update("UPDATE site SET default_yn = 0");
        assertThat(status("/")).as("기본 표시가 0개").isEqualTo(404);

        jdbc.update("UPDATE site SET default_yn = 1 WHERE site_key = 'news'");
        jdbc.update("UPDATE site SET use_yn = 0 WHERE site_key = 'news'");
        assertThat(status("/")).as("기본 사이트가 비활성").isEqualTo(404);
    }

    @Test
    @DisplayName("두 번째 공개 사이트는 같은 콘텐츠를 자기 접두어로 제공하고, 렌더링된 내부 링크가 전부 그 접두어를 가진다")
    void secondSite_servesWithOwnPrefix_allLinksPrefixed() throws Exception {
        site("news", "PUBLIC", true);

        int rendered = 0;
        for (String path : List.of("/news", "/news/faq", "/news/notices", "/news/inquiry")) {
            MockHttpServletResponse response = mockMvc.perform(get(path).with(fromIp())).andReturn().getResponse();
            if (response.getStatus() != 200 && response.getStatus() != 503) {
                continue;
            }
            rendered++;
            String html = response.getContentAsString();
            Matcher matcher = URL_ATTRIBUTE.matcher(html);
            while (matcher.find()) {
                String url = matcher.group(1);
                if (SITE_NEUTRAL.matcher(url).find()) {
                    continue;
                }
                assertThat(url).as(path + " 의 링크 " + url + " 는 /news 접두어를 가져야 한다").startsWith("/news");
            }
        }
        assertThat(rendered).as("검사한 페이지가 있어야 이 시험이 의미가 있다").isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("사이트 범위 경로의 쓰기 메서드는 거부된다 — 문의 POST만 열려 있다")
    void writesOnSiteScopedPaths_denied() throws Exception {
        for (String path : List.of("/main", "/main/notices", "/main/boards/1", "/main/pages/x", "/main/faq")) {
            MockHttpServletResponse response = mockMvc.perform(post(path).with(csrf()).with(fromIp())).andReturn().getResponse();
            assertThat(response.getStatus()).as("POST " + path).isEqualTo(302);
            assertThat(response.getHeader("Location")).endsWith("/admin/login");
        }
    }
}
