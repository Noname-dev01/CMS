package com.cms.admin.menu.dto.response;

import com.cms.admin.menu.Menu;
import com.cms.admin.menu.MenuAccessRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "메뉴 단건 상세 응답")
public class MenuResponse {

    private Long menuNo;
    private String menuName;
    private String menuUrl;
    private String menuIcon;
    private String menuDesc;
    private Boolean useYn;
    private MenuAccessRole accessRole;
    private Integer ord;
    private Long upMenuNo;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;

    public static MenuResponse from(Menu menu) {
        return MenuResponse.builder()
                .menuNo(menu.getMenuNo())
                .menuName(menu.getMenuName())
                .menuUrl(menu.getMenuUrl())
                .menuIcon(menu.getMenuIcon())
                .menuDesc(menu.getMenuDesc())
                .useYn(menu.getUseYn())
                .accessRole(menu.getAccessRole())
                .ord(menu.getOrd())
                .upMenuNo(menu.getUpMenuNo())
                .createDate(menu.getCreateDate())
                .updateDate(menu.getUpdateDate())
                .build();
    }
}
