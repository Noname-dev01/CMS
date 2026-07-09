package com.cms.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 컨벤션 검증: com.cms.admin의 모든 페이지 컨트롤러(@Controller, @RestController 제외)는
 * {@link AdminPage}를 가져야 한다. 누락 시 AdminSidebarAdvice가 적용되지 않아
 * 해당 페이지의 사이드바가 조용히 비어 보이는 회귀를 컴파일/런타임 오류 없이 놓치게 된다.
 * (Codex 리뷰 지적 반영: assignableTypes 수동 열거 → 마커 어노테이션 + 이 테스트)
 */
class AdminPageAnnotationConventionTest {

    @Test
    @DisplayName("com.cms.admin의 모든 페이지 컨트롤러는 @AdminPage를 가진다")
    void allPageControllersHaveAdminPageAnnotation() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        List<String> violations = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.cms.admin")) {
            Class<?> controllerClass = Class.forName(definition.getBeanClassName());
            boolean isRestController = AnnotatedElementUtils.hasAnnotation(controllerClass, RestController.class);
            boolean isAdminPage = AnnotatedElementUtils.hasAnnotation(controllerClass, AdminPage.class);
            if (!isRestController && !isAdminPage) {
                violations.add(controllerClass.getName());
            }
        }

        assertTrue(violations.isEmpty(),
                "@AdminPage 누락 페이지 컨트롤러 (사이드바가 비어 보이게 됨): " + violations);
    }
}
