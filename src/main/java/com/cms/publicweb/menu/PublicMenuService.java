package com.cms.publicweb.menu;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuRepository;
import com.cms.admin.menu.service.MenuService;
import com.cms.admin.site.Site;
import com.cms.admin.site.SiteLinkPolicy;
import com.cms.admin.site.SiteRepository;
import com.cms.admin.site.SiteType;
import com.cms.common.web.SafeUrls;
import com.cms.publicweb.menu.dto.PublicMenuItem;
import com.cms.publicweb.support.PublicSite;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 공개 사이트 메뉴 조회(PLAN-site-menu.md PR ⑤ P5-3). 그 사이트의 <b>활성</b> 메뉴를 루트에서 내려가며 트리로 만든다 — 루트에서 도달하지
 * 못하는 행(고아·비활성 부모 아래 자식·순환)은 자연히 빠지고, 깊이는 편집 허용 깊이({@link MenuService#MAX_MENU_DEPTH})까지만 그린다.
 * 저장된 URL은 출력 시 {@link SiteLinkPolicy#permits}로 한 번 더 거른다(통과 못 하면 링크 없는 라벨). 링크도 자식도 없는 노드는 제거한다.
 * 캐시는 없다(메뉴 수정·사이트 비활성화가 즉시 반영돼야 하고 레이트리밋이 요청 수를 제한한다).
 */
@Service
@RequiredArgsConstructor
public class PublicMenuService {

    private final SiteRepository siteRepository;
    private final MenuRepository menuRepository;
    private final SiteLinkPolicy siteLinkPolicy;

    @Transactional(readOnly = true)
    public List<PublicMenuItem> findMenu(Long siteId) {
        PublicSite.requireId(siteId);
        Site site = siteRepository.findById(siteId).orElse(null);
        if (site == null || site.getSiteType() != SiteType.PUBLIC || !Boolean.TRUE.equals(site.getUseYn())) {
            return List.of();
        }
        Map<Long, List<Menu>> childrenByParent = new HashMap<>();
        for (Menu menu : menuRepository.findAllBySiteIdAndUseYnTrueOrderByOrdAscMenuNoAsc(siteId)) {
            childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
        }
        return build(childrenByParent.get(null), childrenByParent, site, 1, new HashSet<>());
    }

    private List<PublicMenuItem> build(List<Menu> menus, Map<Long, List<Menu>> childrenByParent, Site site, int depth,
                                       Set<Long> visited) {
        if (menus == null || depth > MenuService.MAX_MENU_DEPTH) {
            return List.of();
        }
        List<PublicMenuItem> items = new ArrayList<>();
        for (Menu menu : menus) {
            if (!visited.add(menu.getMenuNo())) {
                continue;
            }
            List<PublicMenuItem> children = build(childrenByParent.get(menu.getMenuNo()), childrenByParent, site, depth + 1, visited);
            String url = siteLinkPolicy.permits(site, menu.getMenuUrl()) ? menu.getMenuUrl() : null;
            if (url == null && children.isEmpty()) {
                continue;
            }
            items.add(new PublicMenuItem(menu.getMenuName(), url, url != null && !SafeUrls.isSameOriginPath(url), children));
        }
        return items;
    }
}
