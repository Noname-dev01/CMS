package com.cms.config;

import com.cms.admin.menu.service.MenuService;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.admin.permission.PermissionAction;
import com.cms.admin.permission.PermissionCache;
import com.cms.admin.permission.PermissionSnapshot;
import com.cms.admin.permission.RequireBoardPermission;
import com.cms.common.api.GlobalApiExceptionHandler;
import com.cms.config.auth.AdminSecurityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.annotation.AnnotationTemplateExpressionDefaults;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code @RequireBoardPermission}의 메타 {@code @PreAuthorize}가 <b>핸들러 파라미터 {@code boardId}</b>를 실제로 읽는지 고정한다(PLAN-board.md 쟁점 3·리스크).
 * 컴파일러가 파라미터 이름을 남기지 않으면(-parameters 꺼짐) {@code #boardId}가 null → 전부 403이 되어 "게시판 A 허용" 단언이 실패한다.
 * 스텁 컨트롤러는 {@code com.cms.admin} 밖에 두어 인가 컨벤션 시험의 스캔 대상이 되지 않게 한다.
 */
@WebMvcTest(controllers = RequireBoardPermissionSpelTest.StubController.class)
@Import({RequireBoardPermissionSpelTest.Config.class, RequireBoardPermissionSpelTest.StubController.class, GlobalApiExceptionHandler.class})
class RequireBoardPermissionSpelTest {

    private static final long BOARD_A = 10L;
    private static final long BOARD_B = 20L;

    @Autowired
    MockMvc mockMvc;

    @RestController
    static class StubController {

        @GetMapping("/admin/api/boards/{boardId}/posts")
        @RequireBoardPermission(action = PermissionAction.READ)
        public String list(@PathVariable Long boardId) {
            return "ok";
        }

        @GetMapping("/admin/api/boards/{boardId}/posts/write-check")
        @RequireBoardPermission(action = PermissionAction.CREATE)
        public String create(@PathVariable Long boardId) {
            return "ok";
        }
    }

    @TestConfiguration
    @EnableMethodSecurity
    static class Config {

        @Bean
        static AnnotationTemplateExpressionDefaults templateExpressionDefaults() {
            return new AnnotationTemplateExpressionDefaults();
        }

        /** {@link PermissionTestConfig#MANAGER_ID} 회원에게 게시판 A의 READ만 준 스냅샷. */
        @Bean
        PermissionCache permissionCache() {
            PermissionCache cache = Mockito.mock(PermissionCache.class);
            Mockito.when(cache.snapshot()).thenReturn(new PermissionSnapshot(Set.of(),
                    Set.of(new PermissionSnapshot.BoardGrant(PermissionTestConfig.MANAGER_ID, BOARD_A, PermissionAction.READ))));
            return cache;
        }

        @Bean("adminPermission")
        AdminPermissionEvaluator adminPermission(PermissionCache cache) {
            return new AdminPermissionEvaluator(cache);
        }

        // AdminSidebarAdvice·AdminViewAdvice(@ControllerAdvice)가 슬라이스 컨텍스트에 포함되므로 의존 빈이 필요하다(NoticeControllerTest와 같다).
        @Bean
        AdminSecurityService adminSecurityService() {
            return Mockito.mock(AdminSecurityService.class);
        }

        @Bean
        MenuService menuService() {
            return Mockito.mock(MenuService.class);
        }
    }

    @Test
    @WithManager
    @DisplayName("MANAGER: 경로 변수 boardId로 판정 — 게시판 A READ는 200, 게시판 B는 403, A의 CREATE(권한 없음)도 403")
    void managerIsJudgedPerBoardFromPathVariable() throws Exception {
        mockMvc.perform(get("/admin/api/boards/" + BOARD_A + "/posts")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/boards/" + BOARD_B + "/posts")).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/api/boards/" + BOARD_A + "/posts/write-check")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN: 어떤 게시판이든 허용")
    void adminIsAllowedOnAnyBoard() throws Exception {
        mockMvc.perform(get("/admin/api/boards/" + BOARD_B + "/posts/write-check")).andExpect(status().isOk());
    }
}
