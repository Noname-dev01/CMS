package com.cms.admin.menu.dto.response;

import com.cms.admin.menu.Menu;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 사이드바 렌더링 전용 경량 응답. jstree용 {@link MenuTreeResponse}와 달리
 * 화면에 그릴 최소 정보만 담는다. 사이드바는 최대 3단까지 그리므로 children은 재귀적으로
 * 최대 2단계 아래까지 채워진다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SidebarMenuResponse {

    private Long menuNo;
    private String menuName;
    private String menuUrl;
    private String menuIcon;
    private List<SidebarMenuResponse> children;

    /** 자손(하위·하위의 하위) 중 menuUrl이 url과 같은 메뉴가 있는지 — 템플릿이 그룹 펼침 상태를 정할 때 쓴다. */
    public boolean hasDescendantUrl(String url) {
        if (url == null || children == null) {
            return false;
        }
        return children.stream()
                .anyMatch(child -> url.equals(child.getMenuUrl()) || child.hasDescendantUrl(url));
    }

    public static SidebarMenuResponse of(Menu menu, List<SidebarMenuResponse> children) {
        return SidebarMenuResponse.builder()
                .menuNo(menu.getMenuNo())
                .menuName(menu.getMenuName())
                .menuUrl(menu.getMenuUrl())
                .menuIcon(menu.getMenuIcon())
                .children(children)
                .build();
    }
}
