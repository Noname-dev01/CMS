package com.cms.admin.menu.dto.response;

import com.cms.admin.menu.Menu;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 사이드바 렌더링 전용 경량 응답. jstree용 {@link MenuTreeResponse}와 달리
 * 화면에 그릴 최소 정보만 담는다. SB Admin 2 사이드바가 2단(최상위 + 하위 그룹)까지만
 * 지원하므로 children은 1단계까지만 채워진다.
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
