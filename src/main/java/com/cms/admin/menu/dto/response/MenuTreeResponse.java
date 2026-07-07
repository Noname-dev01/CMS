package com.cms.admin.menu.dto.response;

import com.cms.admin.menu.Menu;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * jstree 연동용 트리 노드 응답. id/text/children/state는 jstree 표준 필드이고,
 * data에는 상세 조회 없이 기본 표시가 가능하도록 필요한 값과, 트리 조립 시
 * upMenuNo 관계를 따라 계산한 menuLevel·topMenuNo를 함께 담는다.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "메뉴 트리 노드 응답 (jstree 연동)")
public class MenuTreeResponse {

    private String id;
    private String text;
    private State state;
    private Data data;
    private List<MenuTreeResponse> children;

    public static MenuTreeResponse of(Menu menu, int menuLevel, Long topMenuNo, List<MenuTreeResponse> children) {
        return MenuTreeResponse.builder()
                .id(String.valueOf(menu.getMenuNo()))
                .text(menu.getMenuName())
                .state(State.builder().opened(true).build())
                .data(Data.builder()
                        .menuNo(menu.getMenuNo())
                        .menuUrl(menu.getMenuUrl())
                        .menuIcon(menu.getMenuIcon())
                        .useYn(menu.getUseYn())
                        .ord(menu.getOrd())
                        .upMenuNo(menu.getUpMenuNo())
                        .menuLevel(menuLevel)
                        .topMenuNo(topMenuNo)
                        .build())
                .children(children)
                .build();
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class State {
        private boolean opened;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Data {
        private Long menuNo;
        private String menuUrl;
        private String menuIcon;
        private Boolean useYn;
        private Integer ord;
        private Long upMenuNo;
        private Integer menuLevel;
        private Long topMenuNo;
    }
}
