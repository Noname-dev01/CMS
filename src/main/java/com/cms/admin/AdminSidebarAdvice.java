package com.cms.admin;

import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.permission.AdminPermissionEvaluator;
import com.cms.config.auth.AdminSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;
import org.springframework.security.core.Authentication;
import com.cms.admin.permission.PermissionSnapshot;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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
    private final AdminPermissionEvaluator adminPermissionEvaluator;

    /**
     * 사이드바({@code sidebarMenus})와 현재 사용자의 위임 기능 동작 키({@code myPermissions}, 예 {@code "NOTICE:CREATE"} — 화면 버튼 표시용)를
     * <b>한 메서드에서</b> 계산한다. 두 속성이 같은 권한 스냅샷을 공유하도록 지연 조회 공급자를 한 번만 만들어 둘에 함께 넘긴다 —
     * MANAGER는 이 Advice 안에서 스냅샷 1개, ADMIN·상시 허용 경로는 캐시를 호출하지 않는다. 서버 판정이 최종이며 {@code myPermissions}는
     * 서버 판정을 대신하지 않는다. (URL 게이트의 {@code allows()}는 별도로 캐시를 읽으므로 "전체 요청 1회"는 보증하지 않는다.)
     */
    @ModelAttribute
    public void sidebarAndPermissions(Model model) {
        // 로그인 페이지 등 미인증 요청에는 사이드바가 없으므로 DB 조회를 생략한다.
        if (adminSecurityService.getCurrentAdminId() == null) {
            model.addAttribute("sidebarMenus", List.of());
            model.addAttribute("myPermissions", Set.of());
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Supplier<PermissionSnapshot> snapshotOnce = memoize(adminPermissionEvaluator::snapshot);
        model.addAttribute("sidebarMenus", menuService.getSidebarMenus(
                adminPermissionEvaluator.menuUrlVisibility(snapshotOnce, authentication)));
        model.addAttribute("myPermissions", adminPermissionEvaluator.grantedActionKeys(snapshotOnce, authentication));
    }

    private static <T> Supplier<T> memoize(Supplier<T> delegate) {
        return new Supplier<>() {
            private T value;
            private boolean loaded;

            @Override
            public T get() {
                if (!loaded) {
                    value = delegate.get();
                    loaded = true;
                }
                return value;
            }
        };
    }

    /** 사이드바 active 하이라이트용 현재 요청 URI (Thymeleaf 3.1부터 #request 접근 불가) */
    @ModelAttribute("currentUri")
    public String currentUri(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
