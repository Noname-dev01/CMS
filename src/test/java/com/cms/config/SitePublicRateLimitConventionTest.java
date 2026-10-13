package com.cms.config;

import com.cms.admin.site.SiteKey;
import com.cms.config.ratelimit.RateLimitRule;
import com.cms.config.ratelimit.TokenBucketRateLimiter;
import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 사이트 범위 공개 레이트리밋 규칙의 계약(PLAN-site-menu.md PR ② P2-4, 리뷰 라운드 1 지적 2). 필터는 인가·핸들러 선택보다 먼저 돌아 경로 모양만 보므로,
 * 사이트 규칙이 {@code /admin} 같은 예약·비공개 경로를 삼키면 공개 홈 한도를 소진한 IP의 관리자가 429를 받는다. {@code application.yml}의 예약어
 * 부정 선행탐색이 {@link SiteKey#RESERVED}와 어긋나지 않는지 행동으로 확인한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(properties = "cms.rate-limit.enabled=true")
class SitePublicRateLimitConventionTest extends MariaDbContainerSupport {

    /** 사이트 범위 공개 경로에만 걸려야 하는 규칙 id. */
    private static final Set<String> SITE_RULE_IDS = Set.of("attachment", "public-notice", "board-attachment", "public-board", "public-page",
            "public-faq", "public-site-home", "inquiry-submit", "public-inquiry");

    @Autowired TokenBucketRateLimiter limiter;
    @Autowired MockMvc mockMvc;

    private static List<String> matchingSiteRules(TokenBucketRateLimiter limiter, String path) {
        List<String> matched = new ArrayList<>();
        for (String method : List.of("GET", "POST")) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, path);
            for (RateLimitRule rule : limiter.rules()) {
                if (SITE_RULE_IDS.contains(rule.id()) && rule.matcher().matches(request)) {
                    matched.add(method + " " + path + " → " + rule.id());
                }
            }
        }
        return matched;
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("예약어 단일 세그먼트(/admin, /actuator, /error 등)는 어떤 사이트 규칙에도 걸리지 않는다 — yml 선행탐색과 SiteKey.RESERVED가 어긋나면 실패")
    void reservedWordsNeverMatchSiteRules() {
        TreeSet<String> leaked = new TreeSet<>();
        for (String word : SiteKey.RESERVED) {
            leaked.addAll(matchingSiteRules(limiter, "/" + word));
        }
        assertThat(leaked).as("예약어 /{word} 가 사이트 규칙에 걸리면 그 규칙의 한도가 비공개·인프라 경로까지 제한한다").isEmpty();
    }

    @Test
    @DisplayName("기존 비사이트 경로(관리자 페이지·API, 로그인, 헬스, 정적, 이미지)는 어떤 사이트 규칙에도 걸리지 않는다")
    void nonSitePathsNeverMatchSiteRules() {
        List<String> leaked = new ArrayList<>();
        for (String path : List.of("/", "/admin", "/admin/faq/manage", "/admin/inquiry/manage", "/admin/board/posts", "/admin/api/boards/1/posts",
                "/admin/login", "/admin/api/password-resets", "/actuator/health", "/error", "/swagger-ui.html", "/v3/api-docs",
                "/css/public/home.css", "/js/public/faq.js", "/img/x.png", "/vendor/quill/quill.js", "/favicon.ico",
                "/banners/1/image", "/popups/1/image", "/content-images/1")) {
            leaked.addAll(matchingSiteRules(limiter, path));
        }
        assertThat(leaked).isEmpty();
    }

    @Test
    @DisplayName("사이트 범위 경로는 실제 규칙에 걸린다 (공허 통과 방지) — 첨부가 목록 규칙보다 먼저 평가된다")
    void siteScopedPathsMatchTheirRules() {
        assertThat(firstMatch("/main")).isEqualTo("public-site-home");
        assertThat(firstMatch("/news/notices")).isEqualTo("public-notice");
        assertThat(firstMatch("/news/notices/1/attachments/2")).isEqualTo("attachment");
        assertThat(firstMatch("/news/boards/1")).isEqualTo("public-board");
        assertThat(firstMatch("/news/boards/1/posts/2/attachments/3")).isEqualTo("board-attachment");
        assertThat(firstMatch("/news/pages/terms")).isEqualTo("public-page");
        assertThat(firstMatch("/news/faq")).isEqualTo("public-faq");
        assertThat(firstMatch("/news/inquiry")).isIn("public-inquiry", "inquiry-submit");
    }

    private String firstMatch(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        for (RateLimitRule rule : limiter.rules()) {
            if (rule.methods().contains(org.springframework.http.HttpMethod.GET) && rule.matcher().matches(request)) {
                return rule.id();
            }
        }
        return null;
    }

    @Test
    @DisplayName("공개 사이트 홈 한도를 소진한 IP도 /admin·정적 경로는 429가 아니다")
    void exhaustingSiteHomeBucket_doesNotLimitAdminOrStatic() throws Exception {
        String ip = "10.98.1.1";
        int tooMany = 0;
        // 사이트 홈 규칙은 /{키} 모양이면 전부 같은 버킷이라 값싼 404 경로로 소진한다(/main 렌더링은 느려 그 사이 토큰이 다시 찬다).
        for (int i = 0; i < 400; i++) {
            if (mockMvc.perform(get("/burn" + i).with(fromIp(ip))).andReturn().getResponse().getStatus() == 429) {
                tooMany++;
            }
        }
        assertThat(tooMany).as("사이트 홈 한도(120/60초)가 실제로 소진된다").isGreaterThan(0);

        assertThat(mockMvc.perform(get("/admin").with(fromIp(ip))).andReturn().getResponse().getStatus())
                .as("관리자 대시보드는 비인증이면 로그인 302 — 429가 아니다").isEqualTo(302);
        assertThat(mockMvc.perform(get("/css/public/home.css").with(fromIp(ip))).andReturn().getResponse().getStatus()).isNotEqualTo(429);
    }

    @Test
    @DisplayName("무작위 사이트 키를 돌려도 한도는 (규칙, IP) 하나의 버킷을 공유한다 — 키 공간으로 한도를 우회할 수 없다")
    void randomSiteKeys_shareOneBucket() throws Exception {
        String ip = "10.98.1.2";
        int firstLimited = -1;
        for (int i = 0; i < 130; i++) {
            int status = mockMvc.perform(get("/nosuch" + i).with(fromIp(ip))).andReturn().getResponse().getStatus();
            if (status == 429) {
                firstLimited = i;
                break;
            }
            assertThat(status).isEqualTo(404);
        }
        assertThat(firstLimited).as("서로 다른 키 130회 안에 한도(120)에 걸린다").isBetween(100, 125);
    }
}
