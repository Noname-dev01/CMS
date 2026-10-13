package com.cms.publicweb.home;

import com.cms.publicweb.support.PublicSite;
import com.cms.publicweb.support.PublicSiteService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.view.RedirectView;

import java.util.Optional;

/**
 * 접두어 없는 {@code GET /} — 기본 공개 사이트({@code /{defaultKey}})로 302(PLAN-site-menu.md PR ② P2-5). 사이트 범위 컨트롤러가 아니므로
 * {@code @SiteScopedPublic}을 달지 않는다. HEAD는 Spring MVC가 GET 핸들러로 처리한다. 기본 사이트가 데이터 이상으로 해석되지 않으면(0개·복수·비활성)
 * 오류 로그와 함께 공용 404다. 이동 대상 키는 {@code SiteKey} 형식이 검증된 DB 값이라 오픈 리다이렉트가 아니다.
 */
@Controller
@RequiredArgsConstructor
public class RootRedirectController {

    private final PublicSiteService publicSiteService;

    @GetMapping("/")
    public Object root(HttpServletResponse response) {
        Optional<PublicSite> site = publicSiteService.findDefault();
        if (site.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/404";
        }
        RedirectView redirect = new RedirectView(site.get().prefix(), true);
        redirect.setStatusCode(HttpStatus.FOUND);
        redirect.setExposeModelAttributes(false);
        return redirect;
    }
}
