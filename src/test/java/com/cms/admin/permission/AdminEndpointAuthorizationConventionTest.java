package com.cms.admin.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 엔드포인트 인가 컨벤션(PLAN-menu-permission-management.md §3-3). 인가 선언이나 카탈로그 분류 없이 {@code /admin} 핸들러가
 * 추가되면 CI가 실패한다. URL 게이트는 HTTP 메서드를 구분하지 않으므로, <b>읽기 전용 GET/HEAD 페이지 핸들러만 면제</b>하고 그 밖의
 * 모든 핸들러(경로에 /api/가 있든 없든, 메서드 제한이 없는 {@code @RequestMapping} 포함)는 동작 인가 선언을 가져야 한다.
 */
class AdminEndpointAuthorizationConventionTest {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 인증 없이 열려 있는 공개 핸들러(SecurityConfig의 permitAll) — 명시 허용 목록. */
    private static final Set<String> PUBLIC_ENDPOINTS = Set.of(
            "GET /admin/login", "GET /admin/login-error",
            "GET /admin/password-reset", "GET /admin/password-reset/confirm",
            "POST /admin/api/password-reset-requests", "POST /admin/api/password-resets");

    /** 한 핸들러가 가진 인가 선언을 분류한 결과. */
    record Handler(String className, String methodName, Set<RequestMethod> httpMethods, String path,
                   boolean requirePermission, AdminFeature feature, PermissionAction action,
                   boolean adminOnly, boolean adminOrManager, boolean boardPermission) {

        /** 게시판 단위 선언이 없는 핸들러(기존 시험의 반례 구성용). */
        Handler(String className, String methodName, Set<RequestMethod> httpMethods, String path,
                boolean requirePermission, AdminFeature feature, PermissionAction action,
                boolean adminOnly, boolean adminOrManager) {
            this(className, methodName, httpMethods, path, requirePermission, feature, action, adminOnly, adminOrManager, false);
        }

        String key() {
            return httpMethods.isEmpty() ? "ANY " + path : httpMethods.iterator().next() + " " + path;
        }

        boolean readOnlyGetOrHead() {
            return !httpMethods.isEmpty()
                    && httpMethods.stream().allMatch(m -> m == RequestMethod.GET || m == RequestMethod.HEAD);
        }

        boolean declared() {
            return requirePermission || adminOnly || adminOrManager || boardPermission;
        }

        boolean isApi() {
            return path.startsWith("/admin/api/");
        }

        String describe() {
            return className + "#" + methodName + " [" + key() + "]";
        }
    }

    @Test
    @DisplayName("컨트롤러 스캔이 알려진 핸들러 수를 찾는다(스캔이 조용히 비는 것 방지)")
    void scanFindsHandlers() {
        assertThat(scanHandlers().size()).isGreaterThan(40);
    }

    @Test
    @DisplayName("/admin 아래 쓰기·API 핸들러는 정확히 하나의 인가 선언을 가진다, 읽기 전용 GET/HEAD 페이지만 면제")
    void everyAdminHandlerHasAuthorizationOrIsReadOnlyPage() {
        List<String> violations = violations(scanHandlers());

        assertThat(violations).as("인가 선언 없는 /admin 핸들러").isEmpty();
    }

    @Test
    @DisplayName("hasAnyRole('ADMIN','MANAGER')는 상시 허용 기능 경로의 핸들러에만 허용된다")
    void adminOrManagerOnlyOnAlwaysFeaturePaths() {
        List<String> violations = new ArrayList<>();
        for (Handler handler : scanHandlers()) {
            if (handler.adminOrManager() && !insideGates(handler.path(), FeatureKind.ALWAYS)) {
                violations.add(handler.describe());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("위임 가능 기능 경로의 모든 API 핸들러는 같은 기능의 @RequirePermission을 가진다")
    void delegablePathsUseRequirePermissionOfSameFeature() {
        List<String> violations = new ArrayList<>();
        for (Handler handler : scanHandlers()) {
            for (AdminFeature feature : AdminFeature.ofKind(FeatureKind.DELEGABLE)) {
                if (handler.isApi() && matchesAny(handler.path(), feature.getGatePatterns())
                        && !(handler.requirePermission() && handler.feature() == feature)) {
                    violations.add(handler.describe() + " → " + feature);
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("@RequirePermission의 동작은 기능이 지원해야 하고, 기능은 위임 가능이며 경로가 그 기능의 게이트 안이어야 한다")
    void requirePermissionIsConsistentWithCatalog() {
        List<String> violations = new ArrayList<>();
        for (Handler handler : scanHandlers()) {
            if (!handler.requirePermission()) {
                continue;
            }
            if (handler.feature().getKind() != FeatureKind.DELEGABLE) {
                violations.add(handler.describe() + " — 위임 불가 기능 " + handler.feature());
            } else if (!handler.feature().supports(handler.action())) {
                violations.add(handler.describe() + " — 지원하지 않는 동작 " + handler.action());
            } else if (!matchesAny(handler.path(), handler.feature().getGatePatterns())) {
                violations.add(handler.describe() + " — 경로가 " + handler.feature() + " 게이트 밖(URL 게이트가 READ를 강제하지 못함)");
            }
        }
        assertThat(violations).isEmpty();
    }

    /** 게이트 안에 있지만 사이드바 메뉴가 아닌 리다이렉트 전용 경로 — {@code NoticeRedirectController}(PLAN-notice-to-board.md 쟁점 9). */
    private static final Set<String> REDIRECT_ONLY_GATE_PATHS = Set.of("/admin/notice/manage");

    @Test
    @DisplayName("위임 가능 기능의 페이지 핸들러 경로는 그 기능의 사이드바 URL(menuUrls)에도 있다 — 리다이렉트 전용 경로 제외")
    void delegablePageHandlersAreInMenuUrls() {
        List<String> violations = new ArrayList<>();
        for (Handler handler : scanHandlers()) {
            if (handler.isApi() || !handler.readOnlyGetOrHead()) {
                continue;
            }
            if (REDIRECT_ONLY_GATE_PATHS.contains(handler.path())) {
                continue;   // 사이드바에 나오는 페이지가 아니라 옛 주소를 새 화면으로 보내는 리다이렉트 전용 경로(AdminFeature.BOARD 주석)
            }
            for (AdminFeature feature : delegatedFeatures()) {
                if (matchesAny(handler.path(), feature.getGatePatterns()) && !feature.getMenuUrls().contains(handler.path())) {
                    violations.add(handler.describe() + " — " + feature + " menuUrls에 없음");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    // ── 컨벤션 규칙이 실제로 위반을 잡는지(변이 확인) ─────────────────────

    @Test
    @DisplayName("반례: 읽기 전용 페이지(GET)는 선언 없이 통과하지만, 같은 영역의 POST·메서드 제한 없는 매핑·선언 없는 API는 위반")
    void rulesCatchNonApiWriteHandlersAndMissingDeclarations() {
        Handler getPage = new Handler("Fake", "page", Set.of(RequestMethod.GET), "/admin/notice/manage",
                false, null, null, false, false);
        Handler postPage = new Handler("Fake", "submit", Set.of(RequestMethod.POST), "/admin/notice/manage",
                false, null, null, false, false);
        Handler anyMethod = new Handler("Fake", "any", Set.of(), "/admin/notice/manage",
                false, null, null, false, false);
        Handler undeclaredApi = new Handler("Fake", "api", Set.of(RequestMethod.GET), "/admin/api/things",
                false, null, null, false, false);
        Handler declaredPost = new Handler("Fake", "ok", Set.of(RequestMethod.POST), "/admin/notice/manage",
                false, null, null, true, false);

        assertThat(violations(List.of(getPage, declaredPost))).isEmpty();
        assertThat(violations(List.of(postPage))).hasSize(1);
        assertThat(violations(List.of(anyMethod))).hasSize(1);
        assertThat(violations(List.of(undeclaredApi))).hasSize(1);
    }

    @Test
    @DisplayName("게시판 게이트 안의 API는 @RequireBoardPermission과 경로의 {boardId}를 갖고, @RequireBoardPermission은 게시판 게이트 밖에 쓰지 않는다")
    void boardGatedApisUseRequireBoardPermission() {
        assertThat(boardViolations(scanHandlers())).isEmpty();
    }

    @Test
    @DisplayName("반례: 게시판 게이트 안의 API가 선언이 없거나 경로에 {boardId}가 없거나, 게시판 선언이 게이트 밖이면 위반")
    void boardRulesCatchViolations() {
        Handler ok = new Handler("Fake", "list", Set.of(RequestMethod.GET), "/admin/api/boards/{boardId}/posts",
                false, null, null, false, false, true);
        Handler undeclared = new Handler("Fake", "list", Set.of(RequestMethod.GET), "/admin/api/boards/{boardId}/posts",
                false, null, null, false, false, false);
        Handler wrongVariable = new Handler("Fake", "list", Set.of(RequestMethod.GET), "/admin/api/boards/{id}/posts",
                false, null, null, false, false, true);
        Handler adminOnlyInsideGate = new Handler("Fake", "list", Set.of(RequestMethod.GET), "/admin/api/boards/{boardId}/posts",
                false, null, null, true, false, false);
        Handler outsideGate = new Handler("Fake", "get", Set.of(RequestMethod.GET), "/admin/api/boards/{boardId}",
                false, null, null, false, false, true);

        assertThat(boardViolations(List.of(ok))).isEmpty();
        assertThat(boardViolations(List.of(undeclared))).hasSize(1);
        assertThat(boardViolations(List.of(wrongVariable))).hasSize(1);
        assertThat(boardViolations(List.of(adminOnlyInsideGate))).as("게이트가 MANAGER에게 열리므로 게시판별 판정이 필요하다").hasSize(1);
        assertThat(boardViolations(List.of(outsideGate))).hasSize(1);
    }

    // ── 구현 ───────────────────────────────────────────────────────────

    /** 기능 단위로 MANAGER에게 위임되는 기능(기능 단위 위임 + 게시판 단위 위임). */
    private static List<AdminFeature> delegatedFeatures() {
        List<AdminFeature> features = new ArrayList<>(AdminFeature.ofKind(FeatureKind.DELEGABLE));
        features.addAll(AdminFeature.ofKind(FeatureKind.BOARD_SCOPED));
        return features;
    }

    /**
     * 게시판 단위 규칙(PLAN-board.md 쟁점 3): 게시판 게이트는 "어느 게시판이든 READ"라 MANAGER에게 넓게 열리므로, 그 안의 API는 게시판별
     * 판정({@code @RequireBoardPermission})을 해야 하고 판정에 쓸 {@code {boardId}} 경로 변수가 있어야 한다. 반대로 게이트 밖에서는
     * URL 게이트가 READ를 강제하지 못하므로 {@code @RequireBoardPermission}을 쓰지 않는다.
     */
    private static List<String> boardViolations(List<Handler> handlers) {
        List<String> violations = new ArrayList<>();
        for (Handler handler : handlers) {
            boolean insideBoardGate = insideGates(handler.path(), FeatureKind.BOARD_SCOPED);
            if (handler.isApi() && insideBoardGate
                    && !(handler.boardPermission() && handler.path().contains("{boardId}"))) {
                violations.add(handler.describe() + " — 게시판 게이트 안 API는 @RequireBoardPermission + {boardId} 필요");
            }
            if (handler.boardPermission() && !insideBoardGate) {
                violations.add(handler.describe() + " — @RequireBoardPermission이 게시판 게이트 밖");
            }
        }
        return violations;
    }

    private static List<String> violations(List<Handler> handlers) {
        List<String> violations = new ArrayList<>();
        for (Handler handler : handlers) {
            if (!handler.path().equals("/admin") && !handler.path().startsWith("/admin/")) {
                continue;
            }
            if (PUBLIC_ENDPOINTS.contains(handler.key())) {
                continue;
            }
            // 읽기 전용 GET/HEAD 페이지 핸들러만 면제 — API는 GET이라도 선언 필수(데이터 노출 경로)
            if (!handler.isApi() && handler.readOnlyGetOrHead()) {
                continue;
            }
            if (!handler.declared()) {
                violations.add(handler.describe());
            }
        }
        return violations;
    }

    private static boolean insideGates(String path, FeatureKind kind) {
        for (AdminFeature feature : AdminFeature.ofKind(kind)) {
            if (matchesAny(path, feature.getGatePatterns())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String path, List<String> patterns) {
        // {id}·{noticeId} 같은 경로 변수는 구체 세그먼트로 치환해 게이트 패턴(/**)과 비교한다.
        String concrete = path.replaceAll("\\{[^/]+}", "x");
        return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, concrete));
    }

    private static List<Handler> scanHandlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Handler> handlers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.cms.admin")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String basePath = classMapping == null || classMapping.path().length == 0 ? "" : classMapping.path()[0];
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String methodPath = mapping.path().length == 0 ? "" : mapping.path()[0];
                String fullPath = join(basePath, methodPath);
                Set<RequestMethod> httpMethods = Set.of(mapping.method());

                RequirePermission requirePermission = AnnotatedElementUtils.findMergedAnnotation(method, RequirePermission.class);
                PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class); // 직접 선언(메타 @PreAuthorize는 @RequirePermission이 담당)
                String expression = preAuthorize == null ? "" : preAuthorize.value();
                handlers.add(new Handler(type.getSimpleName(), method.getName(), httpMethods, fullPath,
                        requirePermission != null,
                        requirePermission == null ? null : requirePermission.feature(),
                        requirePermission == null ? null : requirePermission.action(),
                        expression.equals("hasRole('ADMIN')"),
                        expression.equals("hasAnyRole('ADMIN', 'MANAGER')"),
                        AnnotatedElementUtils.findMergedAnnotation(method, RequireBoardPermission.class) != null));
            }
        }
        return handlers;
    }

    private static String join(String base, String path) {
        String joined = (base + "/" + path).replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }
}
