package com.cms.config;

import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OpenApiDocsTestController.class)
@Import({
        SecurityConfig.class,
        SecurityConfigTest.MockConfig.class
})
class SecurityConfigTest {

    @Autowired
    MockMvc mockMvc;

    @TestConfiguration
    static class MockConfig {

        @Bean
        public AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }
    }

    @Test
    @DisplayName("OpenAPI docs require authentication")
    void openApiDocs_unauthenticated_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/admin/login"));
    }

    @Test
    @DisplayName("OpenAPI docs reject non-admin users")
    @WithMockUser(roles = "USER")
    void openApiDocs_userRole_forbidden() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OpenAPI docs allow admin users")
    @WithMockUser(roles = "ADMIN")
    void openApiDocs_adminRole_ok() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

}

@RestController
class OpenApiDocsTestController {

    @GetMapping("/v3/api-docs")
    String openApiDocs() {
        return "{}";
    }
}
