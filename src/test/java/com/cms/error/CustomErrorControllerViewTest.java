package com.cms.error;

import com.cms.admin.menu.service.MenuService;
import com.cms.config.MethodSecurityTestConfig;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * 오류 화면 템플릿이 실제 Thymeleaf로 렌더링되는지 확인하는 슬라이스 시험(이슈 #117). 컨트롤러 단위 시험은 뷰 이름과 모델만 보므로, 템플릿 안의
 * 표현식 오류나 {@code null} 표시 회귀는 여기서 잡는다. MockMvc는 {@code sendError} 뒤의 ERROR 디스패치를 하지 않으므로 {@code /error}를 직접 호출하고
 * 컨테이너가 넣는 요청 속성을 흉내 낸다(필터는 끈다 — 인가·ERROR 디스패치 공개는 {@code DefaultDenyErrorDispatchIntegrationTest}가 실서버로 본다).
 */
@WebMvcTest(controllers = CustomErrorController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({CustomErrorControllerViewTest.MockConfig.class, MethodSecurityTestConfig.class})
class CustomErrorControllerViewTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    AdminSecurityService adminSecurityService;

    @BeforeEach
    void setUp() {
        // AdminViewAdvice가 com.cms.admin 전체에 적용되므로 기본 스텁이 필요하다.
        given(adminSecurityService.getCurrentAdminName()).willReturn("관리자");
        given(adminSecurityService.getCurrentAdminProfileImageUrl()).willReturn(null);
    }

    @TestConfiguration
    static class MockConfig {
        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        // AdminSidebarAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다.
        @Bean
        public MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }
    }

    private String render(Integer status, String uri, String expectedView) throws Exception {
        MockHttpServletRequestBuilder request = get("/error");
        if (uri != null) {
            request = request.requestAttr("jakarta.servlet.error.request_uri", uri);
        }
        if (status != null) {
            request = request.requestAttr("jakarta.servlet.error.status_code", status);
        }
        return mockMvc.perform(request)
                .andExpect(view().name(expectedView))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("관리자 경로 403 — 관리자 403 화면이 렌더링되고 관리자 홈 링크가 있으며 null이 없다")
    void forbiddenAdmin_rendersAdminView() throws Exception {
        String html = render(403, "/admin/log/manage", "error/admin/403");

        assertThat(html).contains("403").contains("접근할 수 없는 페이지입니다").contains("관리자 홈으로").contains("/admin\"");
        assertThat(html).doesNotContain("null");
    }

    @Test
    @DisplayName("일반 경로 403 — 일반 403 화면이 렌더링되고 홈 링크가 있으며 null이 없다")
    void forbiddenPublic_rendersPublicView() throws Exception {
        String html = render(403, "/notices", "error/403");

        assertThat(html).contains("403").contains("접근할 수 없는 페이지입니다").contains("홈으로 돌아가기");
        assertThat(html).doesNotContain("null");
    }

    @Test
    @DisplayName("폴백 — 상태 코드가 있으면 그 코드만, 오류 메시지·예외·경로는 화면에 나오지 않는다")
    void otherStatus_fallbackShowsOnlyStatusCode() throws Exception {
        String html = render(500, "/admin/secret-path", "error");

        assertThat(html).contains("500").contains("에러가 발생했습니다").contains("요청을 처리하지 못했습니다");
        assertThat(html).doesNotContain("null").doesNotContain("secret-path");
    }

    @Test
    @DisplayName("폴백 — 상태 코드를 알 수 없어도 null 없이 렌더링된다")
    void unknownStatus_fallbackWithoutNull() throws Exception {
        String html = render(null, null, "error");

        assertThat(html).contains("오류").contains("에러가 발생했습니다");
        assertThat(html).doesNotContain("null");
    }

    @Test
    @DisplayName("기존 화면 무회귀 — 404(일반·관리자)와 429가 그대로 렌더링된다")
    void existingViews_stillRender() throws Exception {
        assertThat(render(404, "/notices/1", "error/404")).contains("404").doesNotContain("null");
        assertThat(render(404, "/admin/nope", "error/admin/404")).contains("관리자 페이지를 찾을 수 없습니다");
        assertThat(render(429, "/notices", "error/429")).contains("429");
    }
}
