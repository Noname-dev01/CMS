package com.cms.admin.menu.service;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import com.cms.admin.permission.AdminFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 사이드바 트리와 메뉴별 노출 안내({@link Exposure})를 같은 규칙으로 계산하는 순수 함수(PLAN-menu-permission-management.md §7).
 * 사이드바 렌더링과 메뉴 관리 화면의 "노출 대상" 안내가 이 클래스 하나를 공유하므로 안내와 실제가 어긋날 수 없다.
 *
 * <p>규칙(아래에서 위로 가지치기): 표시 깊이({@link MenuService#MAX_MENU_DEPTH}) 안의 자식 중 남은 것이 있으면 그 노드는 그룹으로
 * 노출되고(자기 URL은 쓰이지 않는다 — 사이드바가 그룹의 URL을 그리지 않는다), 남은 자식이 없으면 리프로서
 * {@code urlVisible(자기 URL)}일 때만 노출된다. 비활성 메뉴, 비활성(또는 가지치기된) 조상 아래 메뉴, 깊이 밖 메뉴는 트리에 없다.
 */
final class MenuVisibility {

    private MenuVisibility() {
    }

    enum Kind { ALL_ADMINS, PERMISSION, VISIBLE_BY_CHILDREN, ADMIN_ONLY, NOT_SHOWN }

    /** 한 메뉴의 노출 안내. {@code feature}는 {@link Kind#PERMISSION}일 때만 있다. */
    record Exposure(Kind kind, AdminFeature feature) {

        static final Exposure NOT_SHOWN = new Exposure(Kind.NOT_SHOWN, null);
        static final Exposure VISIBLE_BY_CHILDREN = new Exposure(Kind.VISIBLE_BY_CHILDREN, null);
        static final Exposure ALL_ADMINS = new Exposure(Kind.ALL_ADMINS, null);
        static final Exposure ADMIN_ONLY = new Exposure(Kind.ADMIN_ONLY, null);

        /** 화면·테스트가 쓰는 코드: {@code PERMISSION:NOTICE}처럼 기능 이름이 붙고 그 외는 종류 이름 그대로. */
        String code() {
            return kind == Kind.PERMISSION ? "PERMISSION:" + feature.name() : kind.name();
        }

        /** 메뉴 관리 화면에 그대로 표시하는 한국어 안내. */
        String label() {
            return switch (kind) {
                case ALL_ADMINS -> "상시 표시 (ADMIN·MANAGER)";
                case PERMISSION -> feature.getLabel() + " 조회 권한이 있을 때 MANAGER에게 표시";
                case VISIBLE_BY_CHILDREN -> "보이는 하위 메뉴에 따라 표시";
                case ADMIN_ONLY -> "ADMIN 전용";
                case NOT_SHOWN -> "사이드바에 표시되지 않음";
            };
        }

        /** 리프(남은 자식 없음)의 URL 기준 분류. 카탈로그 밖·위임 불가 기능·URL null은 ADMIN 전용이다. */
        static Exposure ofLeafUrl(String menuUrl) {
            return AdminFeature.forMenuUrl(menuUrl)
                    .map(feature -> switch (feature.getKind()) {
                        case ALWAYS -> ALL_ADMINS;
                        case DELEGABLE -> new Exposure(Kind.PERMISSION, feature);
                        case ADMIN_ONLY -> ADMIN_ONLY;
                    })
                    .orElse(ADMIN_ONLY);
        }
    }

    /** {@code sidebar}는 {@code urlVisible} 관점의 사이드바 트리, {@code exposures}는 사이드바 대상 메뉴의 노출 안내(없으면 NOT_SHOWN). */
    record Result(List<SidebarMenuResponse> sidebar, Map<Long, Exposure> exposures) {

        Exposure exposureOf(Long menuNo) {
            return exposures.getOrDefault(menuNo, Exposure.NOT_SHOWN);
        }
    }

    /**
     * @param menus      표시 순서로 정렬된 메뉴(비활성 포함 가능 — 활성만 대상으로 한다)
     * @param urlVisible 메뉴 URL(null 가능)이 이 관점에서 보이는지
     */
    static Result evaluate(List<Menu> menus, Predicate<String> urlVisible) {
        Map<Long, List<Menu>> childrenByParent = new LinkedHashMap<>();
        for (Menu menu : menus) {
            if (Boolean.TRUE.equals(menu.getUseYn())) {
                childrenByParent.computeIfAbsent(menu.getUpMenuNo(), key -> new ArrayList<>()).add(menu);
            }
        }

        Map<Long, Exposure> exposures = new HashMap<>();
        List<SidebarMenuResponse> sidebar = new ArrayList<>();
        for (Menu root : childrenByParent.getOrDefault(null, List.of())) {
            SidebarMenuResponse node = prune(root, 1, childrenByParent, urlVisible, exposures);
            if (node != null) {
                sidebar.add(node);
            }
        }
        return new Result(List.copyOf(sidebar), exposures);
    }

    /** 노출 대상이면 사이드바 노드를, 가지치기돼 사라지면 null을 돌려주고 방문한 메뉴의 노출 안내를 기록한다. */
    private static SidebarMenuResponse prune(Menu menu, int depth, Map<Long, List<Menu>> childrenByParent,
                                             Predicate<String> urlVisible, Map<Long, Exposure> exposures) {
        List<SidebarMenuResponse> children = new ArrayList<>();
        if (depth < MenuService.MAX_MENU_DEPTH) {
            for (Menu child : childrenByParent.getOrDefault(menu.getMenuNo(), List.of())) {
                SidebarMenuResponse node = prune(child, depth + 1, childrenByParent, urlVisible, exposures);
                if (node != null) {
                    children.add(node);
                }
            }
        }

        if (!children.isEmpty()) {
            exposures.put(menu.getMenuNo(), Exposure.VISIBLE_BY_CHILDREN);
            return SidebarMenuResponse.of(menu, List.copyOf(children));
        }
        exposures.put(menu.getMenuNo(), Exposure.ofLeafUrl(menu.getMenuUrl()));
        return urlVisible.test(menu.getMenuUrl()) ? SidebarMenuResponse.of(menu, List.of()) : null;
    }
}
