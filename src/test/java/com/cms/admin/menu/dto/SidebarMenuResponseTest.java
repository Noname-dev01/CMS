package com.cms.admin.menu.dto;

import com.cms.admin.menu.dto.response.SidebarMenuResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarMenuResponseTest {

    private SidebarMenuResponse node(long menuNo, String url, List<SidebarMenuResponse> children) {
        return SidebarMenuResponse.builder().menuNo(menuNo).menuName("m" + menuNo).menuUrl(url).children(children).build();
    }

    @Test
    @DisplayName("hasDescendantUrl: 자손(2단 아래 포함)에 같은 URL이 있으면 true")
    void hasDescendantUrl_findsDeepDescendant() {
        SidebarMenuResponse tree = node(1, null, List.of(
                node(2, null, List.of(node(3, "/admin/deep", List.of())))));

        assertTrue(tree.hasDescendantUrl("/admin/deep"));
    }

    @Test
    @DisplayName("hasDescendantUrl: 자기 자신의 URL·다른 URL·null은 false")
    void hasDescendantUrl_ignoresSelfAndOthers() {
        SidebarMenuResponse tree = node(1, "/admin/self", List.of(node(2, "/admin/other", List.of())));

        assertFalse(tree.hasDescendantUrl("/admin/self"));
        assertFalse(tree.hasDescendantUrl("/admin/none"));
        assertFalse(tree.hasDescendantUrl(null));
    }
}
