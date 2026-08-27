package com.cms.config;

import com.cms.support.CmsTestApplication;
import com.cms.support.MariaDbContainerSupport;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code RateLimitFilter}가 서블릿 컨테이너에 자동 등록되지 않는지 실제 embedded 서버로
 * 검증한다(PLAN-public-endpoint-rate-limit.md 쟁점 10, codex 8라운드 지적).
 *
 * <p>{@code MockMvc}는 embedded server를 띄우지 않아 컨테이너 레벨 필터 이중 등록을 증명하지
 * 못한다 — {@code webEnvironment = RANDOM_PORT}로 실제 서버를 기동한 뒤
 * {@code ServletContext#getFilterRegistrations()}로 직접 확인한다. {@code RateLimitFilter}는
 * {@code @Component}를 부착하지 않고 {@code RateLimitFilterConfig}가 {@code FilterRegistrationBean}을
 * {@code setEnabled(false)}로 등록하므로, 여기 나타나면 안 된다 — {@code SecurityConfig}의
 * {@code addFilterAfter(rateLimitFilter, CsrfFilter.class)}로만 등록돼야 필터 순서 계약이
 * 유지된다.
 */
@SpringBootTest(classes = CmsTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterContainerRegistrationTest extends MariaDbContainerSupport {

    @Autowired
    ServletContext servletContext;

    @Test
    @DisplayName("RateLimitFilter는 서블릿 컨테이너 필터로 등록되지 않는다")
    void rateLimitFilter_notRegisteredInServletContainer() {
        Map<String, ? extends FilterRegistration> registrations = servletContext.getFilterRegistrations();

        boolean anyRateLimitFilterRegistered = registrations.values().stream()
                .anyMatch(reg -> reg.getClassName() != null && reg.getClassName().contains("RateLimitFilter"));

        assertThat(anyRateLimitFilterRegistered)
                .as("RateLimitFilter가 컨테이너 필터로 등록되면 addFilterAfter(CsrfFilter.class) 위치 계약이 깨질 수 있다")
                .isFalse();
    }
}
