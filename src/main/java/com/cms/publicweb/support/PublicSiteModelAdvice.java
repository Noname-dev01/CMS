package com.cms.publicweb.support;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.ui.Model;

/**
 * 해석된 사이트를 템플릿 모델로 옮긴다 — {@code publicSite}(키·이름)와 {@code sitePrefix}(예: {@code /main}). 템플릿의 모든 내부 링크는
 * {@code @{${sitePrefix} + '/notices'}} 식으로 접두어를 붙여 만든다(PLAN-site-menu.md PR ② P2-6). 사이트 범위 컨트롤러가 아닌 요청(예외 페이지,
 * 사이트 해석 전에 던져진 예외)에는 속성이 없어 아무것도 싣지 않는다.
 */
@ControllerAdvice(basePackages = "com.cms.publicweb")
@Order(Ordered.LOWEST_PRECEDENCE)
public class PublicSiteModelAdvice {

    @ModelAttribute
    public void addSite(HttpServletRequest request, Model model) {
        PublicSite site = PublicSiteRoutes.current(request);
        if (site != null) {
            model.addAttribute("publicSite", site);
            model.addAttribute("sitePrefix", site.prefix());
        }
    }
}
