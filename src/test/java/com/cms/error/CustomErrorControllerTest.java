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

    // ==================== 403 (이슈 #117) ====================

    @Test
    @DisplayName("403: 관리자 경로는 관리자 403 화면, 그 밖의 경로는 일반 403 화면 — 404와 같은 경로 판정")
    void forbidden_adminAndPublicViews() {
        assertViewName(403, "/admin/log/manage", "", "error/admin/403");
        assertViewName(403, "/admin", "", "error/admin/403");
        assertViewName(403, "/admin;v=1/log/manage", "", "error/admin/403");
        assertViewName(403, "/notices", "", "error/403");
        assertViewName(403, "/administrator/x", "", "error/403");
        assertViewName(403, "/cms/admin/log/manage", "/cms", "error/admin/403");
        assertViewName(403, null, "", "error/403");
    }

    @Test
    @DisplayName("403: 화면용 모델은 timestamp·path뿐이다 — 예외 메시지 등 내부 정보를 싣지 않는다")
    void forbidden_modelHasOnlyTimestampAndPath() {
        ModelAndView modelAndView = handle(403, "/admin/log/manage", "", "내부 오류 메시지");

        assertThat(modelAndView.getModel()).containsOnlyKeys("timestamp", "path");
        assertThat(modelAndView.getModel().get("path")).isEqualTo("/admin/log/manage");
    }

    // ==================== 그 밖의 상태: 안전한 폴백 ====================

    @Test
    @DisplayName("전용 템플릿이 없는 상태(400·401·405·500·503)는 폴백 error 뷰이고 모델에는 상태 코드뿐이다 — null 표시 회귀 방지")
    void otherStatuses_fallbackViewWithOnlyStatus() {
        for (int status : new int[]{400, 401, 405, 500, 503}) {
            ModelAndView modelAndView = handle(status, "/admin/x", "", "SQL 오류: 내부 정보");

            assertThat(modelAndView.getViewName()).as("상태 " + status).isEqualTo("error");
            assertThat(modelAndView.getModel()).as("상태 " + status + " 모델").containsOnlyKeys("status");
            assertThat(modelAndView.getModel().get("status")).isEqualTo(status);
        }
    }

    @Test
    @DisplayName("상태 코드를 알 수 없으면(null) 폴백 error 뷰이고 모델은 비어 있다")
    void unknownStatus_fallbackViewWithEmptyModel() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getContextPath()).willReturn("");

        ModelAndView modelAndView = controller.handleError(request);

        assertThat(modelAndView.getViewName()).isEqualTo("error");
        assertThat(modelAndView.getModel()).isEmpty();
    }

    @Test
    @DisplayName("기존 계약 유지: 429는 error/429, 404는 timestamp·path 모델")
    void existingStatuses_unchanged() {
        assertViewName(429, "/notices", "", "error/429");
        assertThat(handle(404, "/notices/1", "", null).getModel()).containsOnlyKeys("timestamp", "path");
    }

    private void assertViewName(String requestUri, String contextPath, String expectedViewName) {
        assertViewName(404, requestUri, contextPath, expectedViewName);
    }

    private void assertViewName(int status, String requestUri, String contextPath, String expectedViewName) {
        assertThat(handle(status, requestUri, contextPath, null).getViewName()).isEqualTo(expectedViewName);
    }

    /** {@code errorMessage}는 컨테이너가 넣는 jakarta.servlet.error.message — 어떤 값이어도 모델로 새면 안 된다. */
    private ModelAndView handle(int status, String requestUri, String contextPath, String errorMessage) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getAttribute("jakarta.servlet.error.status_code")).willReturn(status);
        given(request.getAttribute("jakarta.servlet.error.request_uri")).willReturn(requestUri);
        given(request.getAttribute("jakarta.servlet.error.message")).willReturn(errorMessage);
        given(request.getContextPath()).willReturn(contextPath);

        return controller.handleError(request);
    }
}
