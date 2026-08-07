package com.cms.error;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Spring 컨텍스트 없는 순수 단위 테스트 — {@link CustomErrorController}의 admin 경로 판정
 * ({@code PathPattern} 기반, 컨텍스트 경로·매트릭스 파라미터 처리 포함)을 검증한다.
 * PLAN-not-found-handling.md 결정 7 참조.
 */
class CustomErrorControllerTest {

    private final CustomErrorController controller = new CustomErrorController();

    // ==================== 컨텍스트 경로 없음 (현재 전 프로파일 기본값) ====================

    @Test
    @DisplayName("/admin(루트)는 관리자 404")
    void adminRoot_adminView() {
        assertViewName("/admin", "", "error/admin/404");
    }

    @Test
    @DisplayName("/admin/member/manage는 관리자 404")
    void adminSubPath_adminView() {
        assertViewName("/admin/member/manage", "", "error/admin/404");
    }

    @Test
    @DisplayName("/admin;v=1/missing(매트릭스 파라미터)도 관리자 404 — 결정 7 v4 검증")
    void adminPathWithMatrixParam_adminView() {
        assertViewName("/admin;v=1/missing", "", "error/admin/404");
    }

    @Test
    @DisplayName("/administrator/missing은 관리자 404가 아니다 — 접두사 오분류 경계 수정 검증")
    void administratorPrefix_notAdminView() {
        assertViewName("/administrator/missing", "", "error/404");
    }

    @Test
    @DisplayName("/admin-api/missing은 관리자 404가 아니다 — 접두사 오분류 경계 수정 검증")
    void adminApiHyphenPrefix_notAdminView() {
        assertViewName("/admin-api/missing", "", "error/404");
    }

    @Test
    @DisplayName("/notices/999는 일반 404")
    void publicPath_notAdminView() {
        assertViewName("/notices/999", "", "error/404");
    }

    @Test
    @DisplayName("requestURI가 null이면 일반 404 (기존 null 처리 무회귀)")
    void nullRequestUri_notAdminView() {
        assertViewName(null, "", "error/404");
    }

    // ==================== 컨텍스트 경로 있음 (/cms) — 결정 7 v3 검증 ====================

    @Test
    @DisplayName("컨텍스트 경로 /cms 하에서 /cms/admin/missing은 관리자 404")
    void contextPath_adminSubPath_adminView() {
        assertViewName("/cms/admin/missing", "/cms", "error/admin/404");
    }

    @Test
    @DisplayName("컨텍스트 경로 /cms + 매트릭스 파라미터 조합도 관리자 404")
    void contextPath_adminPathWithMatrixParam_adminView() {
        assertViewName("/cms/admin;v=1/missing", "/cms", "error/admin/404");
    }

    @Test
    @DisplayName("컨텍스트 경로 /cms 하에서 /cms/administrator/missing은 관리자 404가 아니다")
    void contextPath_administratorPrefix_notAdminView() {
        assertViewName("/cms/administrator/missing", "/cms", "error/404");
    }

    private void assertViewName(String requestUri, String contextPath, String expectedViewName) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getAttribute("jakarta.servlet.error.status_code")).willReturn(404);
        given(request.getAttribute("jakarta.servlet.error.request_uri")).willReturn(requestUri);
        given(request.getContextPath()).willReturn(contextPath);

        ModelAndView modelAndView = controller.handleError(request);

        assertThat(modelAndView.getViewName()).isEqualTo(expectedViewName);
    }
}
