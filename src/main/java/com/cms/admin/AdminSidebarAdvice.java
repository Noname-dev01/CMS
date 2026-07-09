package com.cms.admin;

import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.config.auth.AdminSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/**
 * {@link AdminPage}가 붙은 페이지 컨트롤러에만 사이드바 렌더링용 모델 속성을 주입한다.
 *
 * <p>{@link AdminViewAdvice}(basePackages)와 달리 마커 어노테이션으로 범위를 제한하는 이유:
 * 사이드바 메뉴 조회는 DB 쿼리를 수반하므로, 뷰를 렌더링하지 않는 REST API 요청
 * (@RestController)에까지 실행되면 매 API 호출마다 불필요한 쿼리가 나간다.
 *
 * <p>새 페이지 컨트롤러에는 {@link AdminPage}를 붙이면 자동 적용된다.
 * 누락은 AdminPageAnnotationConventionTest가 잡아낸다.
 */
@ControllerAdvice(annotations = AdminPage.class)
@RequiredArgsConstructor
public class AdminSidebarAdvice {

    private final MenuService menuService;
    private final AdminSecurityService adminSecurityService;

    @ModelAttribute("sidebarMenus")
    public List<SidebarMenuResponse> sidebarMenus() {
        // 로그인 페이지 등 미인증 요청에는 사이드바가 없으므로 DB 조회를 생략한다.
        if (adminSecurityService.getCurrentAdminId() == null) {
            return List.of();
        }
        return menuService.getSidebarMenus(adminSecurityService.hasAdminAuthority());
    }

    /** 사이드바 active 하이라이트용 현재 요청 URI (Thymeleaf 3.1부터 #request 접근 불가) */
    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
