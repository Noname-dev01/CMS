package com.cms.admin.menu.dto.response;

import com.cms.admin.menu.Menu;
import com.cms.common.web.SafeUrls;
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

    /** 외부 http(s) 주소인가 — 템플릿이 새 탭·noopener로 여는 데 쓴다. */
    public boolean isExternal() {
        return SafeUrls.isExternalHttpUrl(menuUrl);
    }

    /**
     * 저장된 URL이 {@link SafeUrls#isSafeMenuUrl}을 통과하지 못하면(저장 검증 도입 전에 들어간 값 등) {@code null}로 바꿔 담는다.
     * 템플릿은 null이면 {@code #}을 그리므로, 위험 값이 href에 나가거나 Thymeleaf가 {@code javascript:}에서
     * 예외를 던져 모든 관리자 페이지가 500이 되는 것을 막는다.
     */
    public static SidebarMenuResponse of(Menu menu, List<SidebarMenuResponse> children) {
        String url = menu.getMenuUrl();
        return SidebarMenuResponse.builder()
                .menuNo(menu.getMenuNo())
                .menuName(menu.getMenuName())
                .menuUrl(SafeUrls.isSafeMenuUrl(url) ? url : null)
                .menuIcon(menu.getMenuIcon())
                .children(children)
                .build();
    }
}
