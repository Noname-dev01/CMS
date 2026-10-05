package com.cms.admin.message;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 쪽지함 페이지 경로 {@code /admin/member/messages}는 상시 허용 게이트({@code AdminFeature.MY_INFO})에 정확 경로로 열려 있다. ALWAYS 게이트는
 * HTTP 메서드를 구분하지 않으므로 이 경로에 POST 등 쓰기 핸들러가 생기면 MANAGER가 URL 게이트를 통과한다 — 이 시험이 <b>이 경로에 매칭되는 모든 핸들러 매핑은
 * GET/HEAD뿐</b>이라는 규칙을 CI에서 잠근다(PLAN-admin-message.md §5-H, R1-6·R2-5).
 *
 * <p>검사는 기존 {@code AdminEndpointAuthorizationConventionTest}의 스캐너(클래스·메서드 경로 배열의 <b>첫 경로만</b> 읽음)를 재사용하지 않고,
 * {@link RequestMappingHandlerMapping}에 <b>실제 등록된 매핑 전체</b>의 모든 경로 패턴(별칭 포함)과 메서드 조건으로 한다.
 */
@SpringBootTest(classes = CmsTestApplication.class)
class MessagePageMethodConventionTest extends MariaDbContainerSupport {

    static final String PAGE_PATH = "/admin/member/messages";
    private static final Set<RequestMethod> ALLOWED = Set.of(RequestMethod.GET, RequestMethod.HEAD);
    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    /**
     * {@code PAGE_PATH}에 매칭되는 패턴을 하나라도 가진 매핑 중 GET/HEAD 전용이 아닌 것의 설명 목록. 메서드 조건이 비어 있으면(ANY) 위반이다.
     * 별칭(두 번째 경로)·와일드카드 패턴도 {@link PathPattern#matches}로 판정한다.
     */
    static List<String> violations(Map<RequestMappingInfo, HandlerMethod> mappings) {
        PathContainer target = PathContainer.parsePath(PAGE_PATH);
        List<String> violations = new ArrayList<>();
        mappings.forEach((info, handler) -> {
            var patterns = info.getPathPatternsCondition();
            if (patterns == null || patterns.getPatterns().stream().noneMatch(pattern -> pattern.matches(target))) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty() || !ALLOWED.containsAll(methods)) {
                violations.add(handler.getShortLogMessage() + " " + patterns.getPatterns() + " methods="
                        + (methods.isEmpty() ? "ANY" : methods));
            }
        });
        return violations;
    }

    private static Map<RequestMappingInfo, HandlerMethod> synthetic(RequestMappingInfo info) throws NoSuchMethodException {
        HandlerMethod handler = new HandlerMethod(new Object(), Object.class.getMethod("toString"));
        return Map.of(info, handler);
    }

    private static RequestMappingInfo.BuilderConfiguration config() {
        RequestMappingInfo.BuilderConfiguration configuration = new RequestMappingInfo.BuilderConfiguration();
        configuration.setPatternParser(PARSER);
        return configuration;
    }

    // ===================== 실제 등록 매핑 =====================

    @Test
    @DisplayName("실제 등록된 매핑: /admin/member/messages에 매칭되는 핸들러가 있고 모두 GET/HEAD 전용이다")
    void realMappings_areGetOnly() {
        Map<RequestMappingInfo, HandlerMethod> all = handlerMapping.getHandlerMethods();

        long matching = all.keySet().stream()
                .filter(info -> info.getPathPatternsCondition() != null)
                .filter(info -> info.getPathPatternsCondition().getPatterns().stream()
                        .anyMatch(pattern -> pattern.matches(PathContainer.parsePath(PAGE_PATH))))
                .count();

        assertThat(matching).as("쪽지함 페이지 핸들러가 등록돼 있어야 한다").isGreaterThanOrEqualTo(1);
        assertThat(violations(all)).as("쪽지함 경로의 GET/HEAD 외 핸들러(MANAGER가 게이트를 통과한다)").isEmpty();
    }

    // ===================== 규칙이 위반을 실제로 잡는지(반례) =====================

    @Test
    @DisplayName("반례 ①: 정확 경로의 POST는 위반이다")
    void counterexample_exactPathPost() throws Exception {
        var info = RequestMappingInfo.paths(PAGE_PATH).methods(RequestMethod.POST).options(config()).build();

        assertThat(violations(synthetic(info))).hasSize(1);
    }

    @Test
    @DisplayName("반례 ②: 첫 경로가 다른 경로이고 두 번째 경로 별칭이 쪽지함 경로인 POST도 위반이다(기존 스캐너는 첫 경로만 읽어 놓친다)")
    void counterexample_secondPathAlias() throws Exception {
        var info = RequestMappingInfo.paths("/admin/member/settings", PAGE_PATH).methods(RequestMethod.POST).options(config()).build();

        assertThat(violations(synthetic(info))).hasSize(1);
    }

    @Test
    @DisplayName("반례 ③: 메서드 제한이 없는(ANY) 매핑은 위반이다")
    void counterexample_anyMethod() throws Exception {
        var info = RequestMappingInfo.paths(PAGE_PATH).options(config()).build();

        assertThat(violations(synthetic(info))).hasSize(1);
    }

    @Test
    @DisplayName("반례 ④: 와일드카드 패턴 /admin/member/*의 POST도 쪽지함 경로에 매칭되므로 위반이다")
    void counterexample_wildcardPattern() throws Exception {
        var info = RequestMappingInfo.paths("/admin/member/*").methods(RequestMethod.POST).options(config()).build();

        assertThat(violations(synthetic(info))).hasSize(1);
    }

    @Test
    @DisplayName("반례 ⑤: GET과 POST를 함께 허용하는 매핑은 위반이고, PUT·DELETE·PATCH·OPTIONS도 허용되지 않는다")
    void counterexample_mixedAndOtherMethods() throws Exception {
        for (RequestMethod method : new RequestMethod[]{RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH, RequestMethod.OPTIONS}) {
            var info = RequestMappingInfo.paths(PAGE_PATH).methods(method).options(config()).build();
            assertThat(violations(synthetic(info))).as(method.name()).hasSize(1);
        }
        var mixed = RequestMappingInfo.paths(PAGE_PATH).methods(RequestMethod.GET, RequestMethod.POST).options(config()).build();
        assertThat(violations(synthetic(mixed))).hasSize(1);
    }

    @Test
    @DisplayName("정상 사례: GET·HEAD 전용이나 쪽지함 경로와 무관한 경로의 POST는 위반이 아니다")
    void allowedAndUnrelatedMappings() throws Exception {
        var get = RequestMappingInfo.paths(PAGE_PATH).methods(RequestMethod.GET).options(config()).build();
        var getHead = RequestMappingInfo.paths(PAGE_PATH).methods(RequestMethod.GET, RequestMethod.HEAD).options(config()).build();
        var unrelatedPost = RequestMappingInfo.paths("/admin/member/settings").methods(RequestMethod.POST).options(config()).build();
        var subPathPost = RequestMappingInfo.paths(PAGE_PATH + "/x").methods(RequestMethod.POST).options(config()).build();

        assertThat(violations(synthetic(get))).isEmpty();
        assertThat(violations(synthetic(getHead))).isEmpty();
        assertThat(violations(synthetic(unrelatedPost))).isEmpty();
        assertThat(violations(synthetic(subPathPost))).as("하위 경로는 이 규칙의 대상이 아니다(게이트가 캐치올로 ADMIN만 허용)").isEmpty();
    }
}
