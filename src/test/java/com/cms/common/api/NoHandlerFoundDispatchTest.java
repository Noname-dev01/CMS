package com.cms.common.api;

import com.cms.admin.AdminSidebarAdvice;
import com.cms.admin.AdminViewAdvice;
import com.cms.support.TestStubController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code NoHandlerFoundException}이 실제로 {@link GlobalApiExceptionHandler}까지 도달하는지
 * 확인하는 순수 MVC 예외 해석 계약 테스트다. 인가는 검증하지 않는다
 * ({@code @AutoConfigureMockMvc(addFilters = false)}로 Security 필터 실행 자체를 배제) —
 * {@code SecurityConfig} 인가 규칙은 {@code SecurityConfigTest}가 별도로 검증한다.
 *
 * <p>{@code spring.web.resources.add-mappings=false}로 정적 리소스 핸들러("/**")를 꺼서
 * 실제 "핸들러 없음" 상태({@code NoResourceFoundException}이 아니라
 * {@code NoHandlerFoundException})에 도달하게 만든다. {@code spring.mvc.throw-exception-if-no-handler-found}는
 * 쓰지 않는다 — Spring Boot 3.5.16에 존재하지 않는 프로퍼티이고({@code WebMvcProperties}에
 * 대응 필드 없음, javap로 확인), {@code DispatcherServlet}(Spring Framework 6.2.19)의
 * {@code throwExceptionIfNoHandlerFound} 기본값이 이미 {@code true}이므로(생성자 바이트코드
 * {@code iconst_1}로 확인) {@code add-mappings=false} 단독으로 충분하다
 * (PLAN-not-found-handling.md 결정 3 참조).
 *
 * <p>{@link AdminSidebarAdvice}({@code MenuService} 필요)·{@link AdminViewAdvice}
 * ({@code AdminSecurityService} 필요)는 이 슬라이스의 목적과 무관하므로 {@code excludeFilters}로
 * 스캔에서 제외한다 — {@code @AutoConfigureMockMvc(addFilters = false)}는 MockMvc의 Security
 * 필터 등록만 끄고 {@code @ControllerAdvice} 빈 스캔·생성은 막지 않으므로, 제외하지 않으면 그
 * 의존 빈 없이는 컨텍스트 기동이 실패한다({@code SecurityConfigTest}가 이 mock들을 제공하는
 * 이유와 동일).
 */
@WebMvcTest(
        controllers = NoHandlerFoundDispatchStubController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {AdminSidebarAdvice.class, AdminViewAdvice.class})
)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.web.resources.add-mappings=false")
@ActiveProfiles({"test", "webmvc-test"})
class NoHandlerFoundDispatchTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("정적 리소스 매핑이 꺼진 상태에서 등록된 컨트롤러는 정상 응답한다 (슬라이스 구성 자체가 깨지지 않았음을 확인)")
    void registeredController_stillOk() throws Exception {
        mockMvc.perform(get("/no-handler-dispatch-test/ping"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("미매핑 경로는 NoHandlerFoundException을 거쳐 여전히 HTML 404로 처리된다 (204는 sendError+null 계약)")
    void unmappedPath_noHandlerFound_still404() throws Exception {
        mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("/admin/api/** 하위 미매핑 경로는 NoHandlerFoundException을 거쳐 JSON 404 RESOURCE_NOT_FOUND")
    void unmappedAdminApiPath_json404() throws Exception {
        mockMvc.perform(get("/admin/api/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}

@TestStubController
class NoHandlerFoundDispatchStubController {

    @GetMapping("/no-handler-dispatch-test/ping")
    String ping() {
        return "pong";
    }
}
