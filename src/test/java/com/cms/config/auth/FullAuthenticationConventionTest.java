package com.cms.config.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 민감 작업 재인증 표지({@link RequireFullAuthentication})의 적용 범위를 CI에서 잠근다(PLAN-sensitive-reauthentication.md 쟁점 5).
 *
 * <ol>
 *   <li><b>검사 대상 전수</b>: 계정·인증 상태를 바꾸는 컨트롤러({@link #ACCOUNT_CONTROLLERS})의 GET/HEAD가 아닌 모든 핸들러는 표지가 있거나
 *       사유가 적힌 면제({@link #EXEMPT})에 있어야 한다 — 표지 없이 새 쓰기 핸들러를 추가하면 실패. 면제가 낡으면(핸들러가 사라짐·표지와 중복) 실패.</li>
 *   <li><b>승인 집합</b>: 전체 {@code com.cms} 컨트롤러에서 표지가 붙은 핸들러가 {@link #APPROVED}와 정확히 같다 — 제거·다른 곳 오부착 검출.</li>
 * </ol>
 * 클래스 수준 부착은 표지의 {@code @Target(METHOD)}가 컴파일 단계에서 막는다. 한계: {@link #ACCOUNT_CONTROLLERS} 밖에 새 계정 관리 컨트롤러를 만들면
 * 1은 보지 못한다 — 그런 컨트롤러를 만들면 이 목록에 추가한다(`com.cms.config` CLAUDE.md "민감 작업 재인증").
 */
class FullAuthenticationConventionTest {

    /** 계정·권한·세션(인증 상태)을 바꾸는 컨트롤러 — 쓰기 핸들러 전수 검사 대상. */
    static final Set<String> ACCOUNT_CONTROLLERS = Set.of(
            "AdminMemberController", "MemberPermissionController", "AdminSessionController", "AdminReauthenticationController");

    /** 표지가 붙어야 하는 핸들러(승인 목록, 2026-11-11). */
    static final Set<String> APPROVED = Set.of(
            "AdminMemberController#createAdmin",
            "AdminMemberController#updateAdminMember",
            "AdminMemberController#updateMyInfo",
            "AdminMemberController#changeMyPassword",
            "MemberPermissionController#replacePermissions",
            "AdminSessionController#expireSession",
            "AdminSessionController#expireMemberSessions");

    /** 검사 대상 컨트롤러의 쓰기 핸들러 중 표지를 붙이지 않는 것과 그 사유. */
    static final Map<String, String> EXEMPT = Map.of(
            "AdminReauthenticationController#reauthenticate", "재인증 자체 — 표지를 붙이면 쿠키 세션이 재인증할 수 없다(순환)",
            "AdminMemberController#updateMyProfileImage", "본인 프로필 이미지 — 계정 탈취·권한과 무관, 위험 낮음",
            "AdminMemberController#applyDefaultProfileImage", "본인 기본 프로필 이미지 선택 — 위와 같음",
            "AdminMemberController#resetMyProfileImage", "본인 프로필 이미지 초기화 — 위와 같음");

    record Handler(String controller, String method, Set<RequestMethod> httpMethods, boolean marked) {
        String key() {
            return controller + "#" + method;
        }

        boolean write() {
            // 메서드 제한 없는 매핑은 쓰기도 받으므로 쓰기로 본다
            return httpMethods.isEmpty() || httpMethods.stream().anyMatch(m -> m != RequestMethod.GET && m != RequestMethod.HEAD);
        }
    }

    @Test
    @DisplayName("계정·권한·세션 컨트롤러의 쓰기 핸들러는 전부 재인증 표지가 있거나 사유 있는 면제다")
    void accountWriteHandlersAreMarkedOrExempt() {
        assertThat(coverageViolations(scanHandlers(), ACCOUNT_CONTROLLERS, EXEMPT)).isEmpty();
    }

    @Test
    @DisplayName("재인증 표지가 붙은 핸들러 집합은 승인 목록과 정확히 같다")
    void markedHandlersEqualApprovedList() {
        Set<String> marked = scanHandlers().stream().filter(Handler::marked).map(Handler::key)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(marked).isEqualTo(new TreeSet<>(APPROVED));
    }

    @Test
    @DisplayName("반례: 표지 없는 새 쓰기 핸들러·메서드 제한 없는 매핑은 위반, GET은 통과, 낡은 면제(없는 핸들러·표지와 중복)도 위반")
    void counterExamples() {
        Set<String> scope = Set.of("Fake");
        List<Handler> handlers = List.of(
                new Handler("Fake", "newBackdoorPost", Set.of(RequestMethod.POST), false),
                new Handler("Fake", "anyMethod", Set.of(), false),
                new Handler("Fake", "list", Set.of(RequestMethod.GET), false),
                new Handler("Fake", "markedAndExempt", Set.of(RequestMethod.PUT), true),
                new Handler("Other", "outOfScopePost", Set.of(RequestMethod.POST), false));
        Map<String, String> exempt = Map.of(
                "Fake#markedAndExempt", "중복",
                "Fake#removedHandler", "사라진 핸들러");

        assertThat(coverageViolations(handlers, scope, exempt)).containsExactlyInAnyOrder(
                "표지·면제 없음: Fake#newBackdoorPost",
                "표지·면제 없음: Fake#anyMethod",
                "표지와 면제 중복: Fake#markedAndExempt",
                "낡은 면제(쓰기 핸들러 없음): Fake#removedHandler");
    }

    static List<String> coverageViolations(List<Handler> handlers, Set<String> scope, Map<String, String> exempt) {
        List<String> violations = new ArrayList<>();
        Set<String> scopedWrites = new TreeSet<>();
        for (Handler h : handlers) {
            if (!scope.contains(h.controller()) || !h.write()) {
                continue;
            }
            scopedWrites.add(h.key());
            boolean exempted = exempt.containsKey(h.key());
            if (h.marked() && exempted) {
                violations.add("표지와 면제 중복: " + h.key());
            } else if (!h.marked() && !exempted) {
                violations.add("표지·면제 없음: " + h.key());
            }
        }
        for (String key : exempt.keySet()) {
            if (!scopedWrites.contains(key)) {
                violations.add("낡은 면제(쓰기 핸들러 없음): " + key);
            }
        }
        return violations;
    }

    private static List<Handler> scanHandlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        List<Handler> handlers = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.cms")) {
            Class<?> type;
            try {
                type = Class.forName(definition.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                handlers.add(new Handler(type.getSimpleName(), method.getName(), Set.of(mapping.method()),
                        method.isAnnotationPresent(RequireFullAuthentication.class)));
            }
        }
        return handlers;
    }
}
