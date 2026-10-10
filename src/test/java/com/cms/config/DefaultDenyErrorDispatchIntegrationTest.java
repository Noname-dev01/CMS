package com.cms.config;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기본 거부(anyRequest().denyAll()) 전환 후에도 컨테이너의 ERROR 재디스패치가 정상 동작하는지
 * 실제 embedded 서버로 검증한다(PLAN-default-deny-authorization.md 결정 2·5, 감사 M-08).
 *
 * <p>MockMvc는 {@code sendError} 뒤의 ERROR 디스패치(/error)를 수행하지 않아 이를 증명하지 못한다 —
 * {@code dispatcherTypeMatchers(ERROR).permitAll()}이 빠지면 404·403 오류 페이지가 로그인 302 등으로
 * 뒤바뀌는데, 그 회귀는 실서버에서만 관측된다. 리다이렉트를 따라가지 않도록 HttpClient를 NEVER로 둔다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DefaultDenyErrorDispatchIntegrationTest extends MariaDbContainerSupport {

    /** 403 전용 오류 화면(error/403.html)의 본문 문구 — 이슈 #117 전에는 모델이 빈 error.html("에러가 발생했습니다", 값 전부 null)이었다. */
    private static final String FORBIDDEN_VIEW_MARKER = "접근할 수 없는 페이지입니다";

    @LocalServerPort
    int port;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Accept", "text/html")
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("(a) GET /favicon.ico — ERROR 재디스패치로 error/404 페이지가 렌더링된다 (로그인 302로 바뀌지 않음)")
    void favicon_rendersErrorNotFoundPage() throws Exception {
        HttpResponse<String> res = send("GET", "/favicon.ico");

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.headers().firstValue("Location")).isEmpty();
        assertThat(res.body()).isNotBlank();
    }

    @Test
    @DisplayName("(b) CSRF 없는 비인증 POST /notices — 403이며 403 오류 화면 본문이 렌더링된다 (302·빈 본문·컨테이너 기본 응답이면 실패, null 표시도 실패)")
    void csrfRejectedPost_rendersErrorViewWith403() throws Exception {
        HttpResponse<String> res = send("POST", "/notices");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Location")).isEmpty();
        assertThat(res.body()).contains(FORBIDDEN_VIEW_MARKER);
        assertThat(res.body()).doesNotContain("null");
    }

    @Test
    @DisplayName("(b2) CSRF 없는 POST /admin/login(만료된 로그인 폼 제출) — 403이며 관리자 403 화면이 렌더링된다 (이슈 #117)")
    void csrfRejectedAdminLoginPost_rendersAdminForbiddenView() throws Exception {
        HttpResponse<String> res = send("POST", "/admin/login");

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.headers().firstValue("Location")).isEmpty();
        assertThat(res.body()).contains(FORBIDDEN_VIEW_MARKER).contains("관리자 홈으로");
        assertThat(res.body()).doesNotContain("null");
    }

    @Test
    @DisplayName("(c) 비인증 GET 미분류 경로 — 기본 거부로 /admin/login 302")
    void unclassifiedPath_redirectsToLogin() throws Exception {
        HttpResponse<String> res = send("GET", "/foo-unclassified");

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElse("")).endsWith("/admin/login");
    }

    @Test
    @DisplayName("(d) 비인증 직접 GET /error — REQUEST 디스패치는 기본 거부에 걸려 302 (ERROR 디스패치만 공개)")
    void directErrorRequest_isDenied() throws Exception {
        HttpResponse<String> res = send("GET", "/error");

        assertThat(res.statusCode()).isEqualTo(302);
        assertThat(res.headers().firstValue("Location").orElse("")).endsWith("/admin/login");
    }

    @Test
    @DisplayName("(e) 컨트롤러 매핑은 정적 전용 예약 접두사(/css·/js·/img·/vendor)와 겹치지 않는다 — 접두사 permit이 핸들러를 통과시키는 것 방지")
    void noControllerMappingUnderReservedStaticPrefixes() {
        List<String> reserved = List.of("/css", "/js", "/img", "/vendor");

        List<String> violations = handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::getPathPatternsCondition)
                .filter(c -> c != null)
                .flatMap(c -> c.getPatternValues().stream())
                .filter(p -> reserved.stream().anyMatch(r -> p.equals(r) || p.startsWith(r + "/")))
                .toList();

        assertThat(violations).as("정적 예약 접두사 하위 컨트롤러 매핑").isEmpty();
    }
}
