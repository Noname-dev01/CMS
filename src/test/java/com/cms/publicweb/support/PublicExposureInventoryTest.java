package com.cms.publicweb.support;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;

/**
 * 공개 인벤토리 시험(PLAN-site-menu.md PR ② P2-3, 리뷰 라운드 1·2): {@code /{siteKey}} 아래를 무인증으로 여는 대신, 그 영역에 걸리는 모든 핸들러가
 * 사이트 해석 인터셉터({@link SiteScopedPublic})를 지난다는 보장을 <b>행동으로</b> 확인한다. 패턴이 겹치는지를 보지 않고 실제 보안 체인에 익명으로
 * GET·HEAD를 보내, 로그인 302·401·403으로 막히지 않는 비사이트 핸들러 매핑의 목록이 승인된 공개 목록과 같은지 비교한다 — 규칙 순서(앞선 {@code /admin/**} 게이트,
 * {@code /error} 거부)를 반영하므로 정상 관리자·오류 핸들러가 거짓 실패하지 않고, 다른 패키지의 {@code GET /reports/notices} 같은 매핑이 공개 영역에 들어오면
 * 승인 목록 밖 공개로 잡힌다. 변수 자리는 사이트 범위 경로의 둘째 세그먼트 이름을 포함한 탐침 값의 조합으로 채워 {@code /{section}/{view}} 같은
 * 매핑과 공개 영역의 교집합도 놓치지 않는다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
@AutoConfigureMockMvc
class PublicExposureInventoryTest extends MariaDbContainerSupport {

    private static final List<String> PROBES = List.of("x", "1", "notices", "boards", "pages", "faq", "inquiry", "main");
    private static final int MAX_SAMPLES_PER_PATTERN = 600;
    private static final Pattern VARIABLE = Pattern.compile("\\{[^}]*}");

    /**
     * 사이트와 무관하게 무인증으로 열려 있기로 승인된 매핑(패턴 문자열 그대로). 새 항목을 더하려면 그 경로의 공개 승인 이력이 {@code SecurityConfig}와
     * {@code com.cms.config/CLAUDE.md}에 있어야 한다.
     */
    private static final Set<String> APPROVED_PUBLIC_PATTERNS = Set.of(
            "/",                                   // 기본 사이트로 302 (RootRedirectController)
            "/banners/{id}/image",                 // 배너 이미지 (2026-10-09 승인)
            "/popups/{id}/image",                  // 팝업 이미지 (2026-10-12 승인)
            "/content-images/{id}",                // 편집기 본문 이미지 (2026-10-07 승인)
            "/admin/login",                        // 로그인 페이지
            "/admin/login-error",
            "/admin/password-reset",               // 비밀번호 재설정 (2026-07-13 승인)
            "/admin/password-reset/confirm",
            "/admin/api/password-reset-requests",
            "/admin/api/password-resets");

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired MockMvc mockMvc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("사이트 범위 표지(@SiteScopedPublic) 컨트롤러의 매핑은 모두 /{siteKey…} 로 시작하고, 첫 세그먼트가 변수인 매핑은 모두 표지 컨트롤러다")
    void siteScopedMappingsAndVariableRootsCoincide() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            boolean scoped = AnnotatedElementUtils.hasAnnotation(entry.getValue().getBeanType(), SiteScopedPublic.class);
            for (String pattern : patterns(entry.getKey())) {
                boolean variableRoot = pattern.startsWith("/{");
                if (scoped != variableRoot) {
                    violations.add(entry.getValue().getBeanType().getSimpleName() + " " + pattern + " (표지=" + scoped + ")");
                }
                if (scoped) {
                    assertThat(pattern).as("사이트 범위 매핑은 PublicSiteRoutes의 키 자리를 써야 한다").startsWith("/" + PublicSiteRoutes.KEY);
                }
            }
        }
        assertThat(violations)
                .as("첫 세그먼트가 변수인 매핑은 사이트 해석 인터셉터를 지나는 @SiteScopedPublic 컨트롤러여야 하고 그 반대도 같다")
                .isEmpty();
    }

    @Test
    @DisplayName("사이트 범위가 아닌 모든 핸들러 매핑 중 익명 GET·HEAD가 보안에 막히지 않는 것은 승인된 공개 목록 안에 있다")
    void anonymousExposureOfNonSiteHandlersIsApproved() throws Exception {
        TreeSet<String> exposed = new TreeSet<>();
        int probed = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (AnnotatedElementUtils.hasAnnotation(entry.getValue().getBeanType(), SiteScopedPublic.class)) {
                continue;
            }
            for (String pattern : patterns(entry.getKey())) {
                for (String url : samples(pattern)) {
                    for (boolean headRequest : new boolean[] {false, true}) {
                        probed++;
                        MockHttpServletResponse response = mockMvc.perform((headRequest ? head(url) : get(url)).with(request -> {
                            int n = IP_SEQUENCE.getAndIncrement();
                            request.setRemoteAddr("10.97." + (n / 250 % 250) + "." + (n % 250 + 1));
                            return request;
                        })).andReturn().getResponse();
                        if (!isBlockedBySecurity(response)) {
                            exposed.add(pattern);
                        }
                    }
                }
            }
        }
        assertThat(probed).as("탐침이 실제로 돌았다(공허 통과 방지)").isGreaterThan(100);
        assertThat(exposed)
                .as("승인 목록에 없는 비사이트 핸들러가 무인증으로 열려 있다 — /{siteKey} 공개 영역과 겹치지 않는지, 새 공개 경로라면 승인 이력을 먼저 확인하라")
                .isSubsetOf(APPROVED_PUBLIC_PATTERNS);
        assertThat(exposed).as("승인 목록의 핵심 항목이 실제로 탐지된다").contains("/", "/banners/{id}/image", "/admin/login");
    }

    /** 로그인 302·401·403 — 보안 계층이 핸들러에 도달시키지 않은 응답. */
    private static boolean isBlockedBySecurity(MockHttpServletResponse response) {
        int status = response.getStatus();
        if (status == 401 || status == 403) {
            return true;
        }
        String location = response.getHeader("Location");
        return status == 302 && location != null && location.endsWith("/admin/login");
    }

    private static List<String> patterns(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? List.of()
                : new ArrayList<>(info.getPathPatternsCondition().getPatternValues());
    }

    /** 변수·와일드카드 자리를 탐침 값의 조합으로 채운 URL 목록(상한 있음). */
    private static List<String> samples(String pattern) {
        List<String> results = new ArrayList<>();
        results.add(pattern.replace("/**", "/x"));
        List<String> current = List.of(pattern.replace("/**", "/x").replace("*", "x"));
        while (current.stream().anyMatch(s -> VARIABLE.matcher(s).find()) && current.size() < MAX_SAMPLES_PER_PATTERN) {
            List<String> next = new ArrayList<>();
            for (String template : current) {
                var matcher = VARIABLE.matcher(template);
                if (!matcher.find()) {
                    next.add(template);
                    continue;
                }
                for (String probe : PROBES) {
                    next.add(template.substring(0, matcher.start()) + probe + template.substring(matcher.end()));
                }
            }
            current = next;
        }
        List<String> urls = new ArrayList<>(current.stream().filter(s -> !VARIABLE.matcher(s).find()).limit(MAX_SAMPLES_PER_PATTERN).toList());
        return urls;
    }
}
